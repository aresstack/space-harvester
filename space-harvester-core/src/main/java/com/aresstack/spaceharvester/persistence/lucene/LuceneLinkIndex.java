package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.links.InMemoryLinkIndex;
import com.aresstack.spaceharvester.application.links.LinkIndex;
import com.aresstack.spaceharvester.application.links.LinkTarget;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
 * Das Verzeichnis der Verweise ({@link LinkIndex}, Paket V1) in einem eigenen kleinen Lucene-Index neben dem
 * Seiten-Cache, wie {@link LucenePageReleases}: ein Dokument je erfasster Seite mit Quelle, Seiten-ID, Space, Titel,
 * Hash der Payload und den rohen Verweiszielen; Schema {@value #VERSION} in den Commit-Daten. Gelesen wird aus dem
 * Arbeitsstand im Speicher ({@link InMemoryLinkIndex}), der beim Öffnen aus dem Index entsteht.
 *
 * <p>Abgeleitet und neu aufbaubar: Geschrieben wird sofort, dauerhaft gemacht bei {@link #flush()} und spätestens alle
 * {@value #COMMIT_EVERY} Änderungen. Was ein Absturz verliert, erfasst der Abgleich beim nächsten Start neu.
 */
public final class LuceneLinkIndex implements LinkIndex, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "page-links/v1";

    static final int COMMIT_EVERY = 500;

    private static final String ID = "id";
    private static final String SOURCE_ID = "sourceId";
    private static final String PAGE_ID = "pageId";
    private static final String SPACE_KEY = "spaceKey";
    private static final String TITLE = "title";
    private static final String RAW_SHA256 = "rawSha256";
    private static final String TARGETS = "targets";
    /** Trennt Felder eines Ziels; kommt in IDs, Space-Keys und Titeln nicht vor. */
    private static final char FIELD = '\u0000';
    /** Trennt Ziele. */
    private static final char TARGET = '\u0001';

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private InMemoryLinkIndex entries;
    private int uncommitted;
    private boolean closed;

    private LuceneLinkIndex(Path path, Directory directory, IndexWriter writer) {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
    }

    /**
     * Öffnet das Verzeichnis unter {@code path} und legt es an, wenn es dort noch keinen Index gibt.
     *
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LuceneLinkIndex open(Path path) {
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
                    throw new PageCacheException("Verzeichnis der Verweise " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (die Verweise werden neu erfasst)");
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
            LuceneLinkIndex index = new LuceneLinkIndex(root, directory, writer);
            index.load();
            return index;
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Verzeichnis der Verweise " + root + " ist von einem anderen Prozess geöffnet",
                    e);
        } catch (IOException | IllegalArgumentException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Verzeichnis der Verweise " + root
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
                loaded.add(new Entry(new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID)), doc.get(SPACE_KEY),
                        doc.get(TITLE), doc.get(RAW_SHA256), targets(doc.get(TARGETS))));
            }
        }
        entries = new InMemoryLinkIndex(loaded);
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    @Override
    public Optional<Entry> entry(PageKey page) {
        return entries.entry(page);
    }

    @Override
    public synchronized void put(Entry entry) {
        requireOpen();
        try {
            writer.updateDocument(term(entry.page()), document(entry));
        } catch (IOException e) {
            throw new PageCacheException("Verzeichnis der Verweise " + path + " kann nicht geschrieben werden", e);
        }
        entries.put(entry);
        changed();
    }

    @Override
    public synchronized boolean remove(PageKey page) {
        requireOpen();
        if (entries.entry(page).isEmpty()) {
            return false;
        }
        try {
            writer.deleteDocuments(term(page));
        } catch (IOException e) {
            throw new PageCacheException("Verzeichnis der Verweise " + path + " kann nicht geschrieben werden", e);
        }
        entries.remove(page);
        changed();
        return true;
    }

    @Override
    public List<String> pageIds(String sourceId) {
        return entries.pageIds(sourceId);
    }

    @Override
    public int count(String sourceId) {
        return entries.count(sourceId);
    }

    @Override
    public List<Entry> linkingToId(String sourceId, String pageId) {
        return entries.linkingToId(sourceId, pageId);
    }

    @Override
    public List<Entry> linkingToTitle(String sourceId, String titleKey) {
        return entries.linkingToTitle(sourceId, titleKey);
    }

    @Override
    public List<Entry> titled(String sourceId, String titleKey) {
        return entries.titled(sourceId, titleKey);
    }

    @Override
    public synchronized void flush() {
        if (closed || uncommitted == 0) {
            return;
        }
        try {
            writer.commit();
            uncommitted = 0;
        } catch (IOException e) {
            throw new PageCacheException("Verzeichnis der Verweise " + path + " kann nicht geschrieben werden", e);
        }
    }

    /** Macht den Stand dauerhaft und gibt die Sperre frei. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            if (uncommitted > 0) {
                writer.commit();
            }
            writer.close();
        } catch (IOException e) {
            throw new PageCacheException("Verzeichnis der Verweise " + path + " kann nicht geschlossen werden", e);
        } finally {
            closed = true;
            closeQuietly(null, directory);
        }
    }

    private void changed() {
        if (++uncommitted >= COMMIT_EVERY) {
            flush();
        }
    }

    private static Term term(PageKey key) {
        return new Term(ID, key.sourceId() + FIELD + key.pageId());
    }

    private static Document document(Entry entry) {
        PageKey key = entry.page();
        Document doc = new Document();
        doc.add(new StringField(ID, key.sourceId() + FIELD + key.pageId(), Field.Store.YES));
        doc.add(new StoredField(SOURCE_ID, key.sourceId()));
        doc.add(new StoredField(PAGE_ID, key.pageId()));
        doc.add(new StoredField(SPACE_KEY, entry.spaceKey()));
        doc.add(new StoredField(TITLE, entry.title()));
        doc.add(new StoredField(RAW_SHA256, entry.rawSha256()));
        StringBuilder targets = new StringBuilder();
        for (LinkTarget target : entry.targets()) {
            if (!targets.isEmpty()) {
                targets.append(TARGET);
            }
            if (target.byId()) {
                targets.append('i').append(FIELD).append(target.pageId());
            } else {
                targets.append('t').append(FIELD).append(target.spaceKey()).append(FIELD).append(target.title());
            }
        }
        doc.add(new StoredField(TARGETS, targets.toString()));
        return doc;
    }

    private static List<LinkTarget> targets(String stored) {
        List<LinkTarget> targets = new ArrayList<>();
        if (stored == null || stored.isEmpty()) {
            return targets;
        }
        for (String part : stored.split(String.valueOf(TARGET), -1)) {
            String[] fields = part.split(String.valueOf(FIELD), -1);
            if (fields.length == 2 && "i".equals(fields[0])) {
                targets.add(LinkTarget.page(fields[1]));
            } else if (fields.length == 3 && "t".equals(fields[0])) {
                targets.add(LinkTarget.titled(fields[1], fields[2]));
            } else {
                throw new IllegalArgumentException("Unlesbares Verweisziel");
            }
        }
        return targets;
    }

    private void requireOpen() {
        if (closed) {
            throw new PageCacheException("Verzeichnis der Verweise " + path + " ist geschlossen");
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
