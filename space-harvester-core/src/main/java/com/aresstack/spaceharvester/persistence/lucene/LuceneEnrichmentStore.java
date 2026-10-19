package com.aresstack.spaceharvester.persistence.lucene;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingPayload;
import com.aresstack.spaceharvester.application.facts.ArgumentCondition;
import com.aresstack.spaceharvester.application.facts.EnrichmentFacts;
import com.aresstack.spaceharvester.application.facts.FactStore;
import com.aresstack.spaceharvester.application.facts.MetadataFact;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
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
import org.apache.lucene.document.SortedDocValuesField;
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
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermInSetQuery;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TermRangeQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.store.LockObtainFailedException;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.BytesRef;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

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
 * <p>Seit {@code enrichment-store/v3} liegen außerdem die Fakten eines Ergebnisses ({@link FactStore}, #182) als eigene
 * Dokumente daneben, abgeleitet beim Speichern ({@link EnrichmentFacts}), mit Typ, Quelle und Argumenten indiziert. Wie
 * die Vektoren gehören sie zu ihrem Ergebnis, werden mit ihm ersetzt und gelöscht, und {@link #find} liefert sie nie.
 * Ein Index in v1 oder v2 wird beim Öffnen hochgestuft: Die Fakten werden aus den gespeicherten Ergebnissen abgeleitet,
 * ohne etwas neu zu berechnen; die Ergebnisse selbst und ihre Formate bleiben unverändert.
 *
 * <p>Ergebnisse lassen sich jederzeit neu berechnen. Darum wird nicht jedes {@link #save} einzeln dauerhaft gemacht,
 * sondern spätestens alle {@value #COMMIT_EVERY} Änderungen, bei {@link #flush()} und beim Schließen; Lesen sieht
 * jede Änderung sofort. Was ein Absturz verliert, fehlt danach und wird beim nächsten Lauf neu berechnet.
 */
public final class LuceneEnrichmentStore implements EnrichmentStore, EmbeddingIndex, FactStore, AutoCloseable {

    /** Schemaversion in den Commit-Daten. */
    public static final String VERSION = "enrichment-store/v3";

    /** Vorgängerschema ohne Vektoren und Fakten; wird beim Öffnen hochgestuft. */
    static final String VERSION_1 = "enrichment-store/v1";

    /** Vorgängerschema ohne Fakten; wird beim Öffnen hochgestuft. */
    static final String VERSION_2 = "enrichment-store/v2";

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
    /** Ergebnis ({@link #ID}), zu dem ein Faktendokument gehört. */
    private static final String FACT_OF = "factOf";
    /** Seite eines Faktendokuments; getrennt von {@link #PAGE}, damit {@link #find} nur Ergebnisse sieht. */
    private static final String FACT_PAGE = "factPage";
    private static final String FACT_SOURCE = "factSource";
    private static final String FACT_TYPE = "factType";
    /** Rolle und normalisierter Wert je Argument, getrennt durch {@link #SEPARATOR}. */
    private static final String FACT_ARGUMENT = "factArgument";
    private static final String FACT_ID = "factId";
    /** Reihenfolge innerhalb einer Seite: Prozessor und Position im Ergebnis. */
    private static final String FACT_ORDER = "factOrder";
    private static final String FACT = "fact";
    /** Mehr Fakten je Seite liefert {@link #factsOf} nicht. */
    private static final int MAX_FACTS_PER_PAGE = 3 * EnrichmentFacts.MAX_PER_RESULT;
    private static final JsonMapper JSON = JsonMapper.builder().build();
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
                if (!VERSION.equals(schema) && !VERSION_1.equals(schema) && !VERSION_2.equals(schema)) {
                    throw new PageCacheException("Anreicherungsablage " + root + " hat Schema "
                            + (schema == null ? "(unbekannt)" : schema) + ", erwartet " + VERSION
                            + "; Verzeichnis löschen (die Ergebnisse werden neu berechnet)");
                }
            }
            IndexWriterConfig config = new IndexWriterConfig(new StandardAnalyzer());
            config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
            config.setCommitOnClose(false);
            writer = new IndexWriter(directory, config);
            String previous = exists ? SegmentInfos.readLatestCommit(directory).getUserData()
                    .get(PageCacheSchema.SCHEMA_KEY) : VERSION;
            boolean upgrade = !VERSION.equals(previous);
            if (VERSION_1.equals(previous)) {
                dropEmbeddingResults(directory, writer);
            }
            if (upgrade) {
                deriveFacts(writer);
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
            writer.deleteDocuments(new Term(VECTOR_OF, id), new Term(FACT_OF, id));
            writer.updateDocument(new Term(ID, id), document(enrichment));
            writer.addDocuments(vectors(enrichment));
            writer.addDocuments(facts(enrichment));
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
            writer.deleteDocuments(new Term(PAGE, page(key)), new Term(VECTOR_PAGE, page(key)),
                    new Term(FACT_PAGE, page(key)));
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
                    prefix)), new PrefixQuery(new Term(FACT_PAGE, prefix)));
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

    @Override
    public List<MetadataFact> factsOf(PageKey key) {
        Objects.requireNonNull(key, "key");
        return searchFacts(new TermQuery(new Term(FACT_PAGE, page(key))), MAX_FACTS_PER_PAGE,
                new Sort(new SortField(FACT_ORDER, SortField.Type.STRING)));
    }

    @Override
    public List<MetadataFact> facts(FactStore.Query query, String after, int limit) {
        Objects.requireNonNull(query, "query");
        if (limit < 1 || limit > FactStore.MAX_LIMIT) {
            throw new IllegalArgumentException("limit muss zwischen 1 und " + FactStore.MAX_LIMIT + " liegen");
        }
        if (query.sourceIds().isEmpty()) {
            return List.of();
        }
        BooleanQuery.Builder filter = new BooleanQuery.Builder()
                .add(new TermInSetQuery(FACT_SOURCE, query.sourceIds().stream().map(BytesRef::new).toList()),
                        BooleanClause.Occur.FILTER);
        if (query.type() != null) {
            filter.add(new TermQuery(new Term(FACT_TYPE, query.type())), BooleanClause.Occur.FILTER);
        }
        for (ArgumentCondition condition : query.conditions()) {
            filter.add(new TermQuery(new Term(FACT_ARGUMENT, condition.role() + SEPARATOR + condition.value())),
                    BooleanClause.Occur.FILTER);
        }
        if (after != null) {
            filter.add(new TermRangeQuery(FACT_ID, new BytesRef(after), null, false, false),
                    BooleanClause.Occur.FILTER);
        }
        return searchFacts(filter.build(), limit, new Sort(new SortField(FACT_ID, SortField.Type.STRING)));
    }

    private List<MetadataFact> searchFacts(org.apache.lucene.search.Query query, int limit, Sort sort) {
        requireOpen();
        try {
            IndexSearcher searcher = searchers.acquire();
            try {
                TopDocs top = searcher.search(query, limit, sort);
                StoredFields stored = searcher.storedFields();
                List<MetadataFact> facts = new ArrayList<>(top.scoreDocs.length);
                for (ScoreDoc hit : top.scoreDocs) {
                    MetadataFact fact = fact(stored.document(hit.doc));
                    if (fact != null) {
                        facts.add(fact);
                    }
                }
                return facts;
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

    /** Ein Dokument je Fakt des Ergebnisses ({@link EnrichmentFacts}); andere Prozessoren tragen keine. */
    private static List<Document> facts(Enrichment enrichment) {
        List<MetadataFact> facts = EnrichmentFacts.of(enrichment);
        if (facts.isEmpty()) {
            return List.of();
        }
        String id = id(enrichment.key(), enrichment.processorId());
        List<Document> docs = new ArrayList<>(facts.size());
        for (int i = 0; i < facts.size(); i++) {
            MetadataFact fact = facts.get(i);
            Document doc = new Document();
            doc.add(new StringField(FACT_OF, id, Field.Store.NO));
            doc.add(new StringField(FACT_PAGE, page(enrichment.key()), Field.Store.NO));
            doc.add(new StringField(FACT_SOURCE, enrichment.key().sourceId(), Field.Store.NO));
            doc.add(new StringField(FACT_TYPE, fact.type(), Field.Store.NO));
            for (MetadataFact.Argument argument : fact.arguments()) {
                doc.add(new StringField(FACT_ARGUMENT, argument.role() + SEPARATOR + argument.value(),
                        Field.Store.NO));
            }
            doc.add(new StringField(FACT_ID, fact.id(), Field.Store.NO));
            doc.add(new SortedDocValuesField(FACT_ID, new BytesRef(fact.id())));
            doc.add(new SortedDocValuesField(FACT_ORDER, new BytesRef(enrichment.processorId() + SEPARATOR
                    + String.format("%06d", i))));
            doc.add(new StoredField(FACT, factJson(fact)));
            docs.add(doc);
        }
        return docs;
    }

    private static String factJson(MetadataFact fact) {
        ObjectNode node = JSON.createObjectNode();
        node.put("id", fact.id());
        node.put("sourceId", fact.page().sourceId());
        node.put("pageId", fact.page().pageId());
        node.put("type", fact.type());
        if (fact.role() != null) {
            node.put("role", fact.role());
        }
        node.put("payload", fact.payloadJson());
        ArrayNode arguments = node.putArray("arguments");
        for (MetadataFact.Argument argument : fact.arguments()) {
            arguments.addObject().put("role", argument.role()).put("ordinal", argument.ordinal())
                    .put("value", argument.value());
        }
        MetadataFact.Provenance provenance = fact.provenance();
        ObjectNode origin = node.putObject("provenance");
        origin.put("processorId", provenance.processorId());
        origin.put("processorVersion", provenance.processorVersion());
        origin.put("inputSha256", provenance.inputSha256());
        if (provenance.revision() != null) {
            origin.put("revision", provenance.revision());
        }
        origin.put("computedAt", provenance.computedAt().toString());
        if (fact.anchor() != null) {
            ObjectNode anchor = node.putObject("anchor");
            if (fact.anchor().section() != null) {
                anchor.put("section", fact.anchor().section());
            }
            if (fact.anchor().charStart() != null) {
                anchor.put("charStart", fact.anchor().charStart());
            }
            if (fact.anchor().charEnd() != null) {
                anchor.put("charEnd", fact.anchor().charEnd());
            }
        }
        return node.toString();
    }

    /** Der Fakt oder {@code null}, wenn das Dokument sich nicht lesen lässt; er wird beim nächsten Lauf neu abgeleitet. */
    private static MetadataFact fact(Document doc) {
        try {
            JsonNode node = JSON.readTree(doc.get(FACT));
            List<MetadataFact.Argument> arguments = new ArrayList<>();
            for (JsonNode argument : node.path("arguments")) {
                arguments.add(new MetadataFact.Argument(argument.path("role").asString(),
                        argument.path("ordinal").asInt(), argument.path("value").asString()));
            }
            JsonNode origin = node.path("provenance");
            JsonNode anchor = node.get("anchor");
            return new MetadataFact(node.path("id").asString(), new PageKey(node.path("sourceId").asString(),
                    node.path("pageId").asString()), node.path("type").asString(),
                    node.hasNonNull("role") ? node.get("role").asString() : null, node.path("payload").asString(),
                    arguments, new MetadataFact.Provenance(origin.path("processorId").asString(),
                    origin.path("processorVersion").asString(), origin.path("inputSha256").asString(),
                    origin.hasNonNull("revision") ? origin.get("revision").asString() : null,
                    Instant.parse(origin.path("computedAt").asString())),
                    anchor == null ? null : new MetadataFact.Anchor(
                            anchor.hasNonNull("section") ? anchor.get("section").asString() : null,
                            anchor.hasNonNull("charStart") ? anchor.get("charStart").asInt() : null,
                            anchor.hasNonNull("charEnd") ? anchor.get("charEnd").asInt() : null));
        } catch (JacksonException | IllegalArgumentException | NullPointerException | DateTimeParseException e) {
            return null;
        }
    }

    /** v1/v2 → v3: Fakten aus den gespeicherten Ergebnissen ableiten, ohne etwas neu zu berechnen. */
    private static void deriveFacts(IndexWriter writer) throws IOException {
        // Nahezu-Echtzeit-Leser: sieht die Löschungen der v1-Stufe ohne Commit.
        try (DirectoryReader reader = DirectoryReader.open(writer)) {
            for (LeafReaderContext leaf : reader.leaves()) {
                Bits live = leaf.reader().getLiveDocs();
                StoredFields stored = leaf.reader().storedFields();
                for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                    if (live != null && !live.get(doc)) {
                        continue;
                    }
                    Document found = stored.document(doc);
                    String processorId = found.get(PROCESSOR_ID);
                    if (processorId == null || !EnrichmentFacts.derivesFrom(processorId)) {
                        continue;
                    }
                    Enrichment enrichment;
                    try {
                        enrichment = enrichment(found);
                    } catch (RuntimeException e) {
                        continue;
                    }
                    writer.deleteDocuments(new Term(FACT_OF, id(enrichment.key(), processorId)));
                    writer.addDocuments(facts(enrichment));
                }
            }
        }
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
