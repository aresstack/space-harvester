package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.StoredFields;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.PrefixQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;

/**
 * Ablage der Anreicherungen ({@link EnrichmentStore}, #29) in einem eigenen Lucene-Index neben dem Seiten-Index: ein
 * Dokument je Seite und Prozessor, alle Felder gespeichert, Schema {@value #VERSION} in den Commit-Daten. Payload und
 * Kopfdaten der Seiten bleiben davon unberührt.
 *
 * <p>Seit {@code enrichment-store/v2} liegt außerdem jedes Stück eines {@code embedding}-Ergebnisses als eigenes
 * Dokument mit {@link KnnFloatVectorField} im selben Index ({@link EmbeddingIndex}, ADR 0004, #89). Diese Dokumente
 * gehören zu ihrem Ergebnis: Sie werden mit ihm ersetzt und gelöscht, {@link #find} liefert sie nie. Ein Index im
 * Schema {@code enrichment-store/v1} wird beim Öffnen hochgestuft; v1 kannte keine Vektoren, also werden seine
 * {@code embedding}-Ergebnisse gelöscht und beim nächsten Lauf mit Vektoren neu berechnet, alle übrigen bleiben.
 *
 * <p>Ergebnisse lassen sich jederzeit neu berechnen. Darum wird nicht jedes {@link #save} einzeln dauerhaft gemacht,
 * sondern spätestens alle {@value #COMMIT_EVERY} Änderungen, bei {@link #flush()} und beim Schließen; Lesen sieht
 * jede Änderung sofort. Was ein Absturz verliert, fehlt danach und wird beim nächsten Lauf neu berechnet.
 */
public final class LuceneEnrichmentStore implements EnrichmentStore, EmbeddingIndex, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "enrichment-store/v2";

    /** Vorgängerschema ohne Vektoren; wird beim Öffnen hochgestuft. */
    static final String VERSION_1 = "enrichment-store/v1";

    /** Prozessor, dessen Ergebnisse Vektoren tragen ({@code EmbeddingProcessor#id()}). */
    static final String EMBEDDING = "embedding";

    /** Höchste Dimension, die Lucenes HNSW-Format annimmt; größere Vektoren werden nicht indiziert. */
    static final int MAX_VECTOR_DIMENSIONS = 1_024;

    static final int COMMIT_EVERY = 500;

    private static final String ID = "id";
    private static final String PAGE = "page";
    private static final String SOURCE_ID = "sourceId";
    private static final String PAGE_ID = "pageId";
    private static final String PROCESSOR_ID = "processorId";
    private static final String PROCESSOR_VERSION = "processorVersion";
    private static final String INPUT_SHA256 = "inputSha256";
    private static final String REVISION = "revision";
    private static final String STATUS = "status";
    private static final String PAYLOAD = "payloadJson";
    private static final String WARNING = "warning";
    private static final String ERROR_CODE = "errorCode";
    private static final String COMPUTED_AT = "computedAt";
    /** Ergebnis ({@link #ID}), zu dem ein Vektordokument gehört. */
    private static final String VECTOR_OF = "vectorOf";
    /** Seite eines Vektordokuments; getrennt von {@link #PAGE}, damit {@link #find} nur Ergebnisse sieht. */
    private static final String VECTOR_PAGE = "vectorPage";
    /** Quelle und Prozessorversion eines Vektordokuments, indiziert für den Filter der Vektorsuche. */
    private static final String VECTOR_SOURCE = "vectorSource";
    private static final String VECTOR_VERSION = "vectorVersion";
    private static final String CHUNK = "chunk";
    private static final String CHAR_START = "charStart";
    private static final String CHAR_END = "charEnd";
    /** Trennzeichen in zusammengesetzten Schlüsseln; kommt in Quellen-, Seiten- und Prozessorkennungen nicht vor. */
    private static final char SEPARATOR = '\u0000';
    /** Mehr Prozessoren je Seite gibt es nicht. */
    private static final int MAX_PER_PAGE = 1_000;

    private final Path path;
    private final Directory directory;
    private final IndexWriter writer;
    private final SearcherManager searchers;
    private int uncommitted;
    private volatile boolean closed;

    private LuceneEnrichmentStore(Path path, Directory directory, IndexWriter writer) throws IOException {
        this.path = path;
        this.directory = directory;
        this.writer = writer;
        this.searchers = new SearcherManager(writer, null);
    }

    /**
     * Öffnet die Ablage im Verzeichnis {@code path} und legt sie an, wenn es dort noch keinen Index gibt.
     *
     * @throws PageCacheException wenn der Index gesperrt, beschädigt oder in einem anderen Schema ist
     */
    public static LuceneEnrichmentStore open(Path path) {
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
                if (!VERSION.equals(schema) && !VERSION_1.equals(schema)) {
                    throw new PageCacheException("Anreicherungsablage " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (die Ergebnisse werden neu berechnet)");
                }
            }
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            boolean upgrade = exists && VERSION_1.equals(SegmentInfos.readLatestCommit(directory).getUserData()
                    .get(PageCacheSchema.SCHEMA_KEY));
            if (upgrade) {
                dropEmbeddingResults(directory, writer);
            }
            writer.setLiveCommitData(Map.of(PageCacheSchema.SCHEMA_KEY, VERSION).entrySet());
            if (!exists || upgrade) {
                writer.commit();
            }
            return new LuceneEnrichmentStore(root, directory, writer);
        } catch (LockObtainFailedException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Anreicherungsablage " + root + " ist von einem anderen Prozess geöffnet",
                    e);
        } catch (IOException e) {
            closeQuietly(writer, directory);
            throw new PageCacheException("Anreicherungsablage " + root + " kann nicht geöffnet werden (beschädigt "
                    + "oder unlesbar)", e);
        } catch (RuntimeException e) {
            closeQuietly(writer, directory);
            throw e;
        }
    }

    /** Verzeichnis des Index. */
    public Path path() {
        return path;
    }

    @Override
    public synchronized void save(Enrichment enrichment) {
        Objects.requireNonNull(enrichment, "enrichment");
        requireOpen();
        try {
            String id = id(enrichment.key(), enrichment.processorId());
            writer.deleteDocuments(new Term(VECTOR_OF, id));
            writer.updateDocument(new Term(ID, id), document(enrichment));
            writer.addDocuments(vectors(enrichment));
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public List<Enrichment> find(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = searcher.search(new TermQuery(new Term(PAGE, page(key))), MAX_PER_PAGE);
                StoredFields stored = searcher.storedFields();
                List<Enrichment> found = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    found.add(enrichment(stored.document(hit.doc)));
                }
                found.sort(Comparator.comparing(Enrichment::processorId));
                return found;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht gelesen werden", e);
        }
    }

    @Override
    public synchronized void removeAll(PageKey key) {
        Objects.requireNonNull(key, "key");
        requireOpen();
        try {
            writer.deleteDocuments(new Term(PAGE, page(key)), new Term(VECTOR_PAGE, page(key)));
            changed();
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    /** Entfernt alle Ergebnisse und Vektoren einer Quelle (#188), auch abgelegte Lese-Ansichten. */
    public synchronized void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        requireOpen();
        String prefix = sourceId + SEPARATOR;
        try {
            writer.deleteDocuments(new PrefixQuery(new Term(PAGE, prefix)), new PrefixQuery(new Term(VECTOR_PAGE,
                    prefix)));
            writer.commit();
            uncommitted = 0;
            searchers.maybeRefreshBlocking();
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    @Override
    public List<ChunkHit> nearest(Set<String> sourceIds, String processorVersion, float[] query, int k) {
        Objects.requireNonNull(sourceIds, "sourceIds");
        Objects.requireNonNull(processorVersion, "processorVersion");
        Objects.requireNonNull(query, "query");
        if (k < 1 || k > MAX_K) {
            throw new IllegalArgumentException("k muss zwischen 1 und " + MAX_K + " liegen");
        }
        requireOpen();
        float[] normalized = EmbeddingIndex.normalized(query);
        if (query.length > MAX_VECTOR_DIMENSIONS || isZero(normalized) || sourceIds.isEmpty()) {
            return List.of();
        }
        BooleanQuery filter = new BooleanQuery.Builder()
                .add(new TermInSetQuery(VECTOR_SOURCE, sourceIds.stream().map(BytesRef::new).toList()),
                        BooleanClause.Occur.FILTER)
                .add(new TermQuery(new Term(VECTOR_VERSION, processorVersion)), BooleanClause.Occur.FILTER)
                .build();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = searcher.search(new KnnFloatVectorQuery(vectorField(query.length), normalized, k,
                        filter), k);
                StoredFields stored = searcher.storedFields();
                List<ChunkHit> hits = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    Document doc = stored.document(hit.doc);
                    // DOT_PRODUCT liefert (1 + Kosinus) / 2.
                    double similarity = Math.max(-1, Math.min(1, 2.0 * hit.score - 1));
                    hits.add(new ChunkHit(new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID)),
                            doc.getField(CHUNK).numericValue().intValue(),
                            doc.getField(CHAR_START).numericValue().intValue(),
                            doc.getField(CHAR_END).numericValue().intValue(), doc.get(INPUT_SHA256), similarity));
                }
                hits.sort(Comparator.comparingDouble(ChunkHit::similarity).reversed()
                        .thenComparing(hit -> hit.page().pageId()).thenComparing(hit -> hit.page().sourceId())
                        .thenComparingInt(ChunkHit::chunk));
                return hits;
            } finally {
                searchers.release(searcher);
            }
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht gelesen werden", e);
        }
    }

    /** Macht alle bisherigen Änderungen dauerhaft. */
    @Override
    public synchronized void flush() {
        requireOpen();
        try {
            writer.commit();
            uncommitted = 0;
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht geschrieben werden", e);
        }
    }

    /** Macht die Änderungen dauerhaft, schließt die Ablage und gibt die Sperre frei. */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        try {
            if (uncommitted > 0) {
                writer.commit();
            }
        } catch (IOException e) {
            throw new PageCacheException("Anreicherungsablage " + path + " kann nicht geschrieben werden", e);
        } finally {
            closed = true;
            try {
                searchers.close();
            } catch (IOException ignored) {
                // Der Writer wird trotzdem geschlossen.
            }
            closeQuietly(writer, directory);
        }
    }

    private void changed() throws IOException {
        if (++uncommitted >= COMMIT_EVERY) {
            writer.commit();
            uncommitted = 0;
        }
        searchers.maybeRefreshBlocking();
    }

    private static String page(PageKey key) {
        return key.sourceId() + SEPARATOR + key.pageId();
    }

    private static String id(PageKey key, String processorId) {
        return page(key) + SEPARATOR + processorId;
    }

    private static Document document(Enrichment enrichment) {
        Document doc = new Document();
        doc.add(new StringField(ID, id(enrichment.key(), enrichment.processorId()), Field.Store.NO));
        doc.add(new StringField(PAGE, page(enrichment.key()), Field.Store.NO));
        doc.add(new StoredField(SOURCE_ID, enrichment.key().sourceId()));
        doc.add(new StoredField(PAGE_ID, enrichment.key().pageId()));
        doc.add(new StoredField(PROCESSOR_ID, enrichment.processorId()));
        doc.add(new StoredField(PROCESSOR_VERSION, enrichment.processorVersion()));
        doc.add(new StoredField(INPUT_SHA256, enrichment.inputSha256()));
        storeIfPresent(doc, REVISION, enrichment.revision());
        doc.add(new StoredField(STATUS, enrichment.status().name()));
        doc.add(new StoredField(PAYLOAD, enrichment.payloadJson()));
        for (String warning : enrichment.warnings()) {
            doc.add(new StoredField(WARNING, warning));
        }
        storeIfPresent(doc, ERROR_CODE, enrichment.errorCode());
        doc.add(new StoredField(COMPUTED_AT, enrichment.computedAt().toString()));
        return doc;
    }

    /**
     * Ein Dokument je Stück eines gültigen {@code embedding}-Ergebnisses, normiert für {@code DOT_PRODUCT}. Ein
     * Ergebnis, das sich nicht lesen lässt, ein zu großer oder ein Nullvektor bekommt keinen Vektor; das Ergebnis selbst
     * bleibt gespeichert, die Suche findet das Stück nur nicht.
     */
    private static List<Document> vectors(Enrichment enrichment) {
        if (!EMBEDDING.equals(enrichment.processorId()) || enrichment.status() != Enrichment.Status.OK) {
            return List.of();
        }
        EmbeddingPayload payload;
        try {
            payload = EmbeddingPayload.parse(enrichment.payloadJson());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        if (payload.dimensions() < 1 || payload.dimensions() > MAX_VECTOR_DIMENSIONS) {
            return List.of();
        }
        String id = id(enrichment.key(), enrichment.processorId());
        List<Document> docs = new ArrayList<>(payload.chunks().size());
        for (int i = 0; i < payload.chunks().size(); i++) {
            EmbeddingPayload.Chunk chunk = payload.chunks().get(i);
            float[] vector = EmbeddingIndex.normalized(chunk.vector());
            if (isZero(vector)) {
                continue;
            }
            Document doc = new Document();
            doc.add(new StringField(VECTOR_OF, id, Field.Store.NO));
            doc.add(new StringField(VECTOR_PAGE, page(enrichment.key()), Field.Store.NO));
            doc.add(new StringField(VECTOR_SOURCE, enrichment.key().sourceId(), Field.Store.NO));
            doc.add(new StringField(VECTOR_VERSION, enrichment.processorVersion(), Field.Store.NO));
            doc.add(new StoredField(SOURCE_ID, enrichment.key().sourceId()));
            doc.add(new StoredField(PAGE_ID, enrichment.key().pageId()));
            doc.add(new StoredField(INPUT_SHA256, enrichment.inputSha256()));
            doc.add(new StoredField(CHUNK, i));
            doc.add(new StoredField(CHAR_START, chunk.charStart()));
            doc.add(new StoredField(CHAR_END, chunk.charEnd()));
            doc.add(new KnnFloatVectorField(vectorField(vector.length), vector,
                    VectorSimilarityFunction.DOT_PRODUCT));
            docs.add(doc);
        }
        return docs;
    }

    /** Je Dimension ein eigenes Feld, weil Lucene je Feld nur eine Dimension zulässt. */
    private static String vectorField(int dimensions) {
        return "vector" + dimensions;
    }

    private static boolean isZero(float[] vector) {
        for (float v : vector) {
            if (v != 0) {
                return false;
            }
        }
        return true;
    }

    /** v1 → v2: {@code embedding}-Ergebnisse ohne Vektoren löschen, damit sie neu berechnet werden. */
    private static void dropEmbeddingResults(Directory directory, IndexWriter writer) throws IOException {
        try (DirectoryReader reader = DirectoryReader.open(directory)) {
            for (LeafReaderContext leaf : reader.leaves()) {
                Bits live = leaf.reader().getLiveDocs();
                StoredFields stored = leaf.reader().storedFields();
                for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                    if (live != null && !live.get(doc)) {
                        continue;
                    }
                    Document found = stored.document(doc);
                    if (EMBEDDING.equals(found.get(PROCESSOR_ID))) {
                        writer.deleteDocuments(new Term(ID, id(new PageKey(found.get(SOURCE_ID),
                                found.get(PAGE_ID)), EMBEDDING)));
                    }
                }
            }
        }
    }

    private static Enrichment enrichment(Document doc) {
        return new Enrichment(new PageKey(doc.get(SOURCE_ID), doc.get(PAGE_ID)), doc.get(PROCESSOR_ID),
                doc.get(PROCESSOR_VERSION), doc.get(INPUT_SHA256), doc.get(REVISION),
                Enrichment.Status.valueOf(doc.get(STATUS)), doc.get(PAYLOAD), List.of(doc.getValues(WARNING)),
                doc.get(ERROR_CODE), Instant.parse(doc.get(COMPUTED_AT)));
    }

    private static void storeIfPresent(Document doc, String field, String value) {
        if (value != null) {
            doc.add(new StoredField(field, value));
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Anreicherungsablage ist geschlossen");
        }
    }

    private static void closeQuietly(IndexWriter writer, Directory directory) {
        try {
            if (writer != null) {
                writer.rollback();
            }
        } catch (IOException | RuntimeException ignored) {
            // Beim Aufräumen zählt der ursprüngliche Fehler; Ungesichertes wird neu berechnet.
        }
        try {
            if (directory != null) {
                directory.close();
            }
        } catch (IOException | RuntimeException ignored) {
            // wie oben
        }
    }
}
