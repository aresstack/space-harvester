package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.acquisition.BackgroundFill;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;

/**
 * Dauerhafte Warteschlange der Hintergrundfüllung ({@link BackgroundFill.FillQueue}, ADR 0016) in einem eigenen
 * kleinen Lucene-Index neben dem Seiten-Cache, wie {@link LuceneJobStore}: ein Dokument je Seite mit Quelle, Seiten-ID,
 * Art, Änderungsdatum und laufender Nummer; Schema {@value #VERSION} in den Commit-Daten. Keine Anmeldedaten, keine Inhalte.
 *
 * <p>Jede Änderung ist ein eigener Commit, damit die Schlange einen Absturz übersteht; gelesen wird aus einer Kopie im
 * Speicher, die beim Öffnen aus dem Index entsteht. Ein einziger Writer hält die Sperre, solange die Ablage offen ist.
 */
public final class LuceneFillQueue implements BackgroundFill.FillQueue, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "fill-queue/v1";

    private static final String ID = "id";
    private static final String SOURCE_ID = "sourceId";
    private static final String PAGE_ID = "pageId";
    private static final String KIND = "kind";
    private static final String SEQUENCE = "sequence";
    private static final String MODIFIED_AT = "modifiedAt";

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final Map<PageKey, Entry> entries = new LinkedHashMap<>();
    private long sequence;
    private boolean closed;

    private record Entry(BackgroundFill.Gap gap, long sequence) {
    }

    private LuceneFillQueue(Path path, Directory directory, IndexWriter writer) {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
    }

    /**
     * Öffnet die Ablage im Verzeichnis {@code path} und legt sie an, wenn es dort noch keinen Index gibt.
     *
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LuceneFillQueue open(Path path) {
        Objects.requireNonNull(path, "path");
        Path root = path.toAbsolutePath().normalize();
        Directory directory = null;
        IndexWriter writer = null;
        try {
            Files.createDirectories(root);
            directory = FSDirectory.open(root);
            boolean exists = DirectoryReader.indexExists(directory);
            if (exists) {
                String schema = SegmentInfos.readLatestCommit(directory).getUserData()
                        .get(PageCacheSchema.SCHEMA_KEY);
                if (!VERSION.equals(schema)) {
                    throw new PageCacheException("Warteschlange der Hintergrundfüllung " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (die Füllung sucht den Katalog dann neu ab)");
                }
            }
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, VERSION).entrySet());
            if (!exists) {
                writer.commit();
            }
            LuceneFillQueue queue = new LuceneFillQueue(root, directory, writer);
            queue.load();
            return queue;
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + root
                    + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + root
                    + " kann nicht geöffnet werden (beschädigt oder unlesbar)", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    private void load() throws IOException {
        List<Entry> loaded = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int count = Math.max(1, searcher.count(new MatchAllDocsQuery()));
            StoredFields stored = searcher.storedFields();
            for (ScoreDoc hit : searcher.search(new MatchAllDocsQuery(), count).scoreDocs) {
                Document doc = stored.document(hit.doc);
                String modifiedAt = doc.get(MODIFIED_AT);
                loaded.add(new Entry(new BackgroundFill.Gap(new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID)),
                        BackgroundFill.Kind.valueOf(doc.get(KIND)),
                        modifiedAt == null ? null : Instant.parse(modifiedAt)),
                        Long.parseLong(doc.get(SEQUENCE))));
            }
        }
        loaded.sort(Comparator.comparingLong(Entry::sequence));
        for (Entry entry : loaded) {
            entries.put(entry.gap().key(), entry);
            sequence = Math.max(sequence, entry.sequence());
        }
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    @Override
    public synchronized List<BackgroundFill.Gap> pending(int limit) {
        requireOpen();
        return entries.values().stream().limit(Math.max(0, limit)).map(Entry::gap).toList();
    }

    @Override
    public synchronized void add(List<BackgroundFill.Gap> gaps) {
        requireOpen();
        List<Entry> added = new ArrayList<>();
        for (BackgroundFill.Gap gap : gaps) {
            if (!entries.containsKey(gap.key()) && added.stream().noneMatch(e -> e.gap().key().equals(gap.key()))) {
                added.add(new Entry(gap, sequence + added.size() + 1));
            }
        }
        if (added.isEmpty()) {
            return;
        }
        try {
            for (Entry entry : added) {
                writer.updateDocument(term(entry.gap().key()), document(entry));
            }
            writer.commit();
        } catch (IOException e) {
            // Die Kopie im Speicher bleibt beim bestätigten Stand; ein späterer Commit kann die Einträge nachholen,
            // je Seite gibt es ohnehin nur einen.
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + path
                    + " kann nicht geschrieben werden", e);
        }
        sequence += added.size();
        added.forEach(entry -> entries.put(entry.gap().key(), entry));
    }

    @Override
    public synchronized void remove(PageKey key) {
        requireOpen();
        if (!entries.containsKey(key)) {
            return;
        }
        try {
            writer.deleteDocuments(term(key));
            writer.commit();
        } catch (IOException e) {
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + path
                    + " kann nicht geschrieben werden", e);
        }
        entries.remove(key);
    }

    @Override
    public synchronized int size() {
        return entries.size();
    }

    /** Schließt die Ablage und gibt die Sperre frei; die Einträge bleiben für den nächsten Start. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            writer.rollback();
        } catch (IOException e) {
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + path
                    + " kann nicht geschlossen werden", e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    private static Term term(PageKey key) {
        return new Term(ID, key.sourceId() + "\u0000" + key.pageId());
    }

    private static Document document(Entry entry) {
        PageKey key = entry.gap().key();
        Document doc = new Document();
        doc.add(new StringField(ID, key.sourceId() + "\u0000" + key.pageId(), Field.Store.YES));
        doc.add(new StoredField(SOURCE_ID, key.sourceId()));
        doc.add(new StoredField(PAGE_ID, key.pageId()));
        doc.add(new StoredField(KIND, entry.gap().kind().name()));
        doc.add(new StoredField(SEQUENCE, Long.toString(entry.sequence())));
        if (entry.gap().modifiedAt() != null) {
            doc.add(new StoredField(MODIFIED_AT, entry.gap().modifiedAt().toString()));
        }
        return doc;
    }

    private void requireOpen() {
        if (closed) {
            throw new PageCacheException("Warteschlange der Hintergrundfüllung " + path + " ist geschlossen");
        }
    }

    private static void closeQuietly(IndexWriter writer, Directory directory) {
        try {
            if (writer != null) {
                writer.rollback();
            }
        } catch (IOException | RuntimeException ignored) {
            // beim Aufräumen nach einem Fehler
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // beim Aufräumen nach einem Fehler
        }
    }
}
