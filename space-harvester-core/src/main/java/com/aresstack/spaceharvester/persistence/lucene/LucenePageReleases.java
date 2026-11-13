package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.release.InMemoryPageReleases;
import com.aresstack.spaceharvester.application.release.PageReleases;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
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
 * Die Liste der für die KI freigegebenen Seiten ({@link PageReleases}, ADR 0028) in einem eigenen kleinen Lucene-Index
 * neben dem Seiten-Cache, wie {@link LuceneFillQueue}: ein Dokument je Seite mit Quelle, Seiten-ID, Space, Knoten,
 * Herkunft, Zeitpunkt und laufender Nummer; Schema {@value #VERSION} in den Commit-Daten. Keine Inhalte, keine Titel.
 *
 * <p>Jede Änderung ist ein eigener Commit, bevor sie gilt; gelesen wird aus der Hash-Menge im Speicher, die beim Öffnen
 * aus dem Index entsteht ({@link InMemoryPageReleases}). Ein einziger Writer hält die Sperre, solange die Liste offen
 * ist.
 */
public final class LucenePageReleases implements PageReleases, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "page-releases/v1";

    private static final String ID = "id";
    private static final String SOURCE_ID = "sourceId";
    private static final String PAGE_ID = "pageId";
    private static final String SPACE_KEY = "spaceKey";
    private static final String NODE = "node";
    private static final String ORIGIN = "origin";
    private static final String RELEASED_AT = "releasedAt";
    private static final String SEQUENCE = "sequence";

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private InMemoryPageReleases list;
    private long sequence;
    private boolean closed;

    private LucenePageReleases(Path path, Directory directory, IndexWriter writer) {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
    }

    /**
     * Öffnet die Liste im Verzeichnis {@code path} und legt sie an, wenn es dort noch keinen Index gibt.
     *
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LucenePageReleases open(Path path) {
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
                    throw new PageCacheException("Liste der freigegebenen Seiten " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION);
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
            LucenePageReleases releases = new LucenePageReleases(root, directory, writer);
            releases.load();
            return releases;
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Liste der freigegebenen Seiten " + root
                    + " ist von einem anderen Prozess geöffnet", e);
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Liste der freigegebenen Seiten " + root
                    + " kann nicht geöffnet werden (beschädigt oder unlesbar)", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    private record Stored(Entry entry, long sequence) {
    }

    private void load() throws IOException {
        List<Stored> loaded = new ArrayList<>();
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            int count = Math.max(1, searcher.count(new MatchAllDocsQuery()));
            StoredFields stored = searcher.storedFields();
            for (ScoreDoc hit : searcher.search(new MatchAllDocsQuery(), count).scoreDocs) {
                Document doc = stored.document(hit.doc);
                loaded.add(new Stored(new Entry(new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID)), doc.get(SPACE_KEY),
                        doc.get(NODE), doc.get(ORIGIN), Instant.parse(doc.get(RELEASED_AT))),
                        Long.parseLong(doc.get(SEQUENCE))));
            }
        }
        loaded.sort(Comparator.comparingLong(Stored::sequence));
        for (Stored entry : loaded) {
            sequence = Math.max(sequence, entry.sequence());
        }
        list = new InMemoryPageReleases(loaded.stream().map(Stored::entry).toList(), this::write);
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    @Override
    public boolean contains(PageKey key) {
        return list.contains(key);
    }

    @Override
    public List<Entry> entries() {
        return list.entries();
    }

    @Override
    public List<Entry> add(Collection<Entry> entries) {
        return list.add(entries);
    }

    @Override
    public List<Entry> remove(Collection<PageKey> keys) {
        return list.remove(keys);
    }

    @Override
    public void removeSource(String sourceId) {
        list.removeSource(sourceId);
    }

    @Override
    public Subscription subscribe(Consumer<Change> listener) {
        return list.subscribe(listener);
    }

    /** Schreibt eine Änderung in einem Commit; läuft unter der Sperre der Liste im Speicher. */
    private synchronized void write(List<Entry> added, List<PageKey> removed) {
        requireOpen();
        try {
            long next = sequence;
            for (Entry entry : added) {
                writer.updateDocument(term(entry.key()), document(entry, ++next));
            }
            for (PageKey key : removed) {
                writer.deleteDocuments(term(key));
            }
            writer.commit();
            sequence = next;
        } catch (IOException e) {
            try {
                writer.rollback();
            } catch (IOException | RuntimeException ignored) {
                // der Writer ist danach geschlossen; requireOpen meldet es beim nächsten Schreiben
            }
            closed = true;
            throw new PageCacheException("Liste der freigegebenen Seiten " + path + " kann nicht geschrieben werden",
                    e);
        }
    }

    /** Schließt die Liste und gibt die Sperre frei; die Einträge bleiben für den nächsten Start. */
    @Override
    public synchronized void close() {
        if (closed) {
            closeQuietly(null, directory);
            return;
        }
        closed = true;
        try {
            writer.rollback();
        } catch (IOException e) {
            throw new PageCacheException("Liste der freigegebenen Seiten " + path + " kann nicht geschlossen werden",
                    e);
        } finally {
            closeQuietly(null, directory);
        }
    }

    private static Term term(PageKey key) {
        return new Term(ID, key.sourceId() + "\u0000" + key.pageId());
    }

    private static Document document(Entry entry, long sequence) {
        PageKey key = entry.key();
        Document doc = new Document();
        doc.add(new StringField(ID, key.sourceId() + "\u0000" + key.pageId(), Field.Store.YES));
        doc.add(new StoredField(SOURCE_ID, key.sourceId()));
        doc.add(new StoredField(PAGE_ID, key.pageId()));
        doc.add(new StoredField(SPACE_KEY, entry.spaceKey()));
        if (entry.node() != null) {
            doc.add(new StoredField(NODE, entry.node()));
        }
        doc.add(new StoredField(ORIGIN, entry.origin()));
        doc.add(new StoredField(RELEASED_AT, entry.releasedAt().toString()));
        doc.add(new StoredField(SEQUENCE, Long.toString(sequence)));
        return doc;
    }

    private void requireOpen() {
        if (closed) {
            throw new PageCacheException("Liste der freigegebenen Seiten " + path + " ist geschlossen");
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
