package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.PartId;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingIndex;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.searchrun.SearchRunLog;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link RelevanceSearch} mit Anhängen und enthaltenen Dateien (ADR 0026, AP9a): ein Treffer je Seite mit dem besten
 * Stück aus Seite und Dokumenten, Felder und Namen des Dokuments, Rechte der Seite, unverändert ohne Dokumentvektoren
 * und abschaltbar.
 */
class DocumentRelevanceSearchTest {

    private static final SourceCatalog CATALOG = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(SOURCE) : Optional.empty();
        }
    };

    private static final HarvestSource SOURCE = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[] {HarvestSource.class}, (proxy, method, args) -> null);

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final SearchIndexState STATE = new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z"));
    private static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");
    private static final String EMBEDDED = "sha256:" + "1".repeat(64);
    private static final String SECTION_TEXT = "sha256:" + "5".repeat(64);
    private static final String DOCUMENT_TEXT = "sha256:" + "d".repeat(64);
    private static final PartId PART = new PartId.Content("c".repeat(64), 1);
    private static final DocumentKey ATTACHMENT = DocumentKey.attachment(new PageKey("wiki", "10"), "att-1");
    private static final DocumentKey CONTAINED = ATTACHMENT.child(PART);

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final List<DocumentKey> textsRead = new ArrayList<>();
    private int documentSearches;
    private final EmbeddingProcessor processor = new EmbeddingProcessor(new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "axes";
        }

        @Override
        public int dimensions() {
            return AXES.size();
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new AssertionError("Seiten werden in diesem Test nicht eingebettet");
        }

        @Override
        public List<float[]> embedQueries(List<String> texts) {
            return texts.stream().map(DocumentRelevanceSearchTest::axes).toList();
        }
    }, 1500, KnowledgeTextSegmentation.defaults());

    /** Die Ablage als Vektorsuche, die mitzählt, wie oft in Dokumenten gesucht wird. */
    private final EmbeddingIndex vectors = new EmbeddingIndex() {
        @Override
        public List<ChunkHit> nearest(Set<String> sourceIds, String processorVersion, float[] query, int k) {
            return store.nearest(sourceIds, processorVersion, query, k);
        }

        @Override
        public List<ChunkHit> nearestDocuments(Set<String> sourceIds, String processorVersion, float[] query,
                                               int k) {
            documentSearches++;
            return store.nearestDocuments(sourceIds, processorVersion, query, k);
        }
    };

    @BeforeEach
    void publish() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "10", "Architektur-Übersicht", "3"), confirmed("g1")),
                entry(page("DEV", "11", "Nachtbetrieb", "5"), confirmed("g1")),
                entry(page("DEV", "12", "Betrieb Handbuch", "2"), confirmed("g1")),
                entry(page("OPS", "20", "Urlaubsplanung", "1"), confirmed("g2")),
                entry(page("OPS", "21", "Geheimer Betrieb", "1"), confirmed("g2"))));
        embed("10", "3", "[0,1,0]");
        embed("11", "5", "[0.2,0,0.9]", "[1,0.1,0]");
        embed("12", "2", "[0.8,0.6,0]");
        embed("20", "1", "[0,0,1]");
        embed("21", "1", "[1,0,0]");
    }

    @Test
    void theBestChunkInAContainedFileRanksItsPageAndNamesTheDocument() {
        embed(CONTAINED, 2, "[0,0,1]", "[1,0.05,0]");

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL, true).search(CALLER, query(), "Betrieb", 10);

        assertThat(ids(results)).containsExactly("21", "10", "11", "12", "20");
        RelevanceSearch.Relevance best = results.hits().get(1).relevance();
        assertThat(best.similarity()).isGreaterThan(results.hits().get(2).relevance().similarity());
        assertThat(best.attachmentId()).isEqualTo("att-1");
        assertThat(best.partId()).isEqualTo(PART.value());
        assertThat(best.section()).isEqualTo(2);
        assertThat(best.attachmentTitle()).isEqualTo("Bericht.zip");
        assertThat(best.partTitle()).isEqualTo("innen/Betrieb.docx");
        assertThat(best.charStart()).as("Stelle im Lesetext des Abschnitts").isEqualTo(10);
        assertThat(best.charEnd()).isEqualTo(20);
        assertThat(best.freshness()).isEqualTo(RelevanceSearch.Freshness.CURRENT);
        assertThat(best.passageId()).as("ohne Absätze des Dokuments").isNull();
        assertThat(results.hits().get(0).relevance().attachmentId()).as("Seite selbst").isNull();
        assertThat(results.hits().get(0).relevance().section()).isNull();
    }

    @Test
    void aHitInADocumentNamesThePassageIdOfItsPassage() {
        embed(CONTAINED, 2, "[0,0,1]", "[1,0.05,0]");
        String passage = ",\"firstSentence\":0,\"sentences\":1,\"section\":null,\"kind\":\"paragraph\","
                + "\"boundary\":\"structure\",\"similarity\":null}";
        store.save(new Enrichment(CONTAINED.page(), "passages", "passages@1", SECTION_TEXT, DOCUMENT_TEXT,
                Enrichment.Status.OK, "{\"mode\":\"structure\",\"reason\":\"no-model\",\"model\":null,"
                        + "\"segmentation\":\"passages;eac\",\"sentences\":2,\"textSha256\":\"x\",\"passages\":["
                        + "{\"start\":0,\"end\":10" + passage + ",{\"start\":10,\"end\":20" + passage + "]}",
                List.of(), null, Instant.parse("2026-10-17T07:00:00Z"), CONTAINED, 2));

        RelevanceSearch.Relevance best = search(ALLOW_ALL, true).search(CALLER, query(), "Betrieb", 10).hits().get(1)
                .relevance();

        PassageId id = PassageId.parse(best.passageId());
        assertThat(id.capture()).isEqualTo("d".repeat(64));
        assertThat(id.document()).contains(CONTAINED.fileName());
        assertThat(id.position()).isEqualTo(1);
    }

    @Test
    void anAttachmentItselfHasNoPartAndTheExcerptComesFromItsSectionText() {
        embed(ATTACHMENT, 0, "[1,0.05,0]");
        RelevanceSearch search = search(ALLOW_ALL, true);

        RelevanceSearch.RankedHit hit = search.search(CALLER, query(), "Betrieb", 10).hits().get(1);

        assertThat(hit.relevance().attachmentId()).isEqualTo("att-1");
        assertThat(hit.relevance().partId()).isNull();
        assertThat(hit.relevance().partTitle()).isNull();
        assertThat(hit.relevance().attachmentTitle()).isEqualTo("Bericht.zip");
        assertThat(search.documentExcerpt(hit, 100)).as("bis zum Ende des Stücks [0, 10)").isEqualTo("Betrieb im");
        assertThat(search.documentExcerpt(hit, 4)).isEqualTo("Betr");
    }

    @Test
    void anExcerptNeedsExactlyTheTextTheChunkCameFrom() {
        embed(ATTACHMENT, 0, "[1,0.05,0]");
        store.save(new Enrichment(ATTACHMENT.page(), "embedding", processor.version(), "sha256:" + "6".repeat(64),
                DOCUMENT_TEXT, Enrichment.Status.OK, json("[1,0.05,0]"), List.of(), null,
                Instant.parse("2026-10-17T07:00:00Z"), ATTACHMENT, 0));
        RelevanceSearch search = search(ALLOW_ALL, true);

        RelevanceSearch.RankedHit hit = search.search(CALLER, query(), "Betrieb", 10).hits().get(1);

        assertThat(hit.relevance().attachmentId()).isEqualTo("att-1");
        assertThat(search.documentExcerpt(hit, 100)).as("anderer Lesetext").isEmpty();
    }

    @Test
    void onATieThePageKeepsItsOwnChunk() {
        embed(DocumentKey.attachment(new PageKey("wiki", "21"), "att-2"), 0, "[1,0,0]");

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL, true).search(CALLER, query(), "Betrieb", 10);

        RelevanceSearch.Relevance page21 = results.hits().get(0).relevance();
        assertThat(ids(results).getFirst()).isEqualTo("21");
        assertThat(page21.attachmentId()).isNull();
    }

    @Test
    void aDocumentOfAPageWithoutContentAccessIsNeitherAHitNorAnExcerpt() {
        embed(CONTAINED, 2, "[1,0.05,0]");
        AccessPolicy noContentFor10 = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "10".equals(scope.pageId()) ? AccessDecision.DENY : AccessDecision.ALLOW;
        List<String> rerankerDocuments = new ArrayList<>();
        SearchReranker capturing = new SearchReranker() {
            @Override
            public String id() {
                return "sieht@1";
            }

            @Override
            public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
                return candidates;
            }

            @Override
            public List<SearchHit> rerank(Request request) {
                request.candidates().forEach(hit -> rerankerDocuments.add(request.documents().apply(hit)));
                return request.candidates();
            }
        };

        RelevanceSearch.RelevanceResults results = search(noContentFor10, true, capturing)
                .search(CALLER, query(), "Betrieb", 10);

        assertThat(ids(results)).containsExactly("21", "11", "12", "20");
        assertThat(results.hits()).noneMatch(hit -> hit.relevance() != null
                && hit.relevance().attachmentId() != null);
        assertThat(rerankerDocuments).noneMatch(document -> document.contains("Anhang"));
        assertThat(textsRead).isEmpty();
    }

    @Test
    void withoutDocumentVectorsHitsAndOrderStayExactlyTheSame() {
        RelevanceSearch.RelevanceResults before = new RelevanceSearch(CATALOG, index, new AccessGuard(ALLOW_ALL),
                store, store, processor).search(CALLER, query(), "nächtlicher Betrieb", 10);

        RelevanceSearch.RelevanceResults after = search(ALLOW_ALL, true).search(CALLER, query(), "nächtlicher Betrieb",
                10);

        assertThat(after.hits()).isEqualTo(before.hits());
        assertThat(ids(after)).containsExactly("21", "11", "12", "10", "20");
    }

    @Test
    void switchedOffNoDocumentVectorIsSearched() {
        embed(CONTAINED, 2, "[1,0.05,0]");

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL, false).search(CALLER, query(), "Betrieb", 10);

        assertThat(documentSearches).isZero();
        assertThat(ids(results)).containsExactly("21", "11", "12", "10", "20");
        assertThat(results.hits()).allSatisfy(hit -> assertThat(hit.relevance().attachmentId()).isNull());
        assertThat(textsRead).isEmpty();
    }

    private RelevanceSearch search(AccessPolicy policy, boolean documents) {
        return search(policy, documents, SearchReranker.NONE);
    }

    private RelevanceSearch search(AccessPolicy policy, boolean documents, SearchReranker reranker) {
        return new RelevanceSearch(CATALOG, index, new AccessGuard(policy), store, vectors, processor, null,
                reranker, texts(), null, SearchRunLog.NONE, () -> documents, (document, section, inputSha256, extraction) -> {
                    textsRead.add(document);
                    return SECTION_TEXT.equals(inputSha256)
                            ? Optional.of(new PageText("document-text/v1", "Betrieb im Anhang", List.of()))
                            : Optional.empty();
                });
    }

    private static PageTextIndex texts() {
        return new PageTextIndex() {
            @Override
            public void put(Entry entry) {
            }

            @Override
            public Optional<String> inputSha256(PageKey key) {
                return Optional.empty();
            }

            @Override
            public Optional<String> text(PageKey key) {
                return Optional.of("Lesetext der Seite " + key.pageId());
            }

            @Override
            public Optional<DocumentTitles> documentTitles(PageKey page, String attachmentId, String partId) {
                return "att-1".equals(attachmentId) ? Optional.of(new DocumentTitles("Bericht.zip",
                        partId.isEmpty() ? "" : "innen/Betrieb.docx")) : Optional.empty();
            }

            @Override
            public void remove(PageKey key) {
            }

            @Override
            public void removeSource(String sourceId) {
            }

            @Override
            public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
                return List.of();
            }

            @Override
            public void flush() {
            }
        };
    }

    private static PageSearchQuery query() {
        return PageSearchQuery.of("wiki", List.of(), null, null, null, null, null, null, SearchSort.TITLE);
    }

    private static List<String> ids(RelevanceSearch.RelevanceResults results) {
        return results.hits().stream().map(hit -> hit.hit().page().pageId()).toList();
    }

    private static float[] axes(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        float[] vector = new float[AXES.size()];
        for (int i = 0; i < AXES.size(); i++) {
            vector[i] = lower.contains(AXES.get(i)) ? 1 : 0;
        }
        return vector;
    }

    private void embed(String pageId, String revision, String... vectors) {
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(), EMBEDDED, revision,
                Enrichment.Status.OK, json(vectors), List.of(), null, Instant.parse("2026-10-17T07:00:00Z")));
    }

    /** Embedding eines Abschnitts eines Dokuments aus dem Lesetext {@link #SECTION_TEXT}. */
    private void embed(DocumentKey document, int section, String... vectors) {
        store.save(new Enrichment(document.page(), "embedding", processor.version(), SECTION_TEXT, DOCUMENT_TEXT,
                Enrichment.Status.OK, json(vectors), List.of(), null, Instant.parse("2026-10-17T07:00:00Z"),
                document, section));
    }

    private static String json(String... vectors) {
        StringBuilder json = new StringBuilder("{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[");
        for (int i = 0; i < vectors.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"charStart\":").append(i * 10).append(",\"charEnd\":")
                    .append(i * 10 + 10).append(",\"section\":null,\"vector\":").append(vectors[i]).append('}');
        }
        return json.append("]}").toString();
    }

    private static PageSnapshot page(String space, String pageId, String title, String version) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, null,
                version, Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
