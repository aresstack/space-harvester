package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link RelevanceSearch}: Kandidaten, Kosinus-Reihenfolge, Rechte (auch negativ), Aktualität, Rückfall ohne Modell. */
class RelevanceSearchTest {

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

    /** Spielzeugmodell: je Achse ein Stichwort. */
    private static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final List<String> embeddedQueries = new ArrayList<>();
    private boolean modelDown;
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
            if (modelDown) {
                throw new EmbeddingUnavailableException("ausgelastet");
            }
            embeddedQueries.addAll(texts);
            return texts.stream().map(RelevanceSearchTest::axes).toList();
        }
    }, 1500);

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
        embed("12", "1", "[0.8,0.6,0]");
        embed("20", "1", "[0,0,1]");
        embed("21", "1", "[1,0,0]");
    }

    @Test
    void semanticCandidatesAreOrderedByCosineWithTheirBestChunk() {
        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, query(null), "nächtlicher Betrieb",
                10);

        assertThat(ids(results)).containsExactly("21", "11", "12", "10", "20");
        assertThat(results.reranker()).isEqualTo("embedding-cosine@" + processor.version());
        RelevanceSearch.Relevance best = results.hits().get(1).relevance();
        assertThat(best.similarity()).isCloseTo(1 / Math.sqrt(1.01), within(1e-4));
        assertThat(best.charStart()).isEqualTo(10);
        assertThat(best.charEnd()).isEqualTo(20);
        assertThat(best.freshness()).isEqualTo(RelevanceSearch.Freshness.CURRENT);
        assertThat(embeddedQueries).containsExactly("nächtlicher Betrieb");
    }

    @Test
    void aRerankerOfTheEmbeddingProgramReordersTheWindowAfterTheCosine() {
        SearchReranker reverse = new SearchReranker() {
            @Override
            public String id() {
                return "umgekehrt@1";
            }

            @Override
            public List<SearchHit> rerank(PageSearchQuery query, List<SearchHit> candidates) {
                return candidates.reversed();
            }
        };
        RelevanceSearch search = new RelevanceSearch(CATALOG, index, new AccessGuard(ALLOW_ALL), store, store,
                processor, null, reverse);

        RelevanceSearch.RelevanceResults results = search.search(CALLER, query(null), "nächtlicher Betrieb", 10);

        assertThat(ids(results)).containsExactly("20", "10", "12", "11", "21");
        assertThat(results.reranker()).isEqualTo("embedding-cosine@" + processor.version() + "+umgekehrt@1");
    }

    @Test
    void anEmbeddingFromAnOlderRevisionIsMarkedStale() {
        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, query(null), "Betrieb", 10);

        assertThat(results.hits()).filteredOn(hit -> hit.hit().page().pageId().equals("12")).singleElement()
                .satisfies(hit -> assertThat(hit.relevance().freshness()).isEqualTo(RelevanceSearch.Freshness.STALE));
    }

    @Test
    void withoutContentAccessAPageNeverComesFromTheVectorsAndHasNoSimilarity() {
        // OPS: metadata ja, content nein. Seite 21 wäre der ähnlichste Treffer.
        AccessPolicy noOpsContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;

        assertThat(ids(search(noOpsContent).search(CALLER, query(null), "Betrieb", 10)))
                .containsExactly("11", "12", "10");

        // Als Titeltreffer bleibt sie sichtbar, aber ohne Ähnlichkeit und hinter allen Treffern mit Ähnlichkeit.
        RelevanceSearch.RelevanceResults byTitle = search(noOpsContent).search(CALLER, query("betrieb"), "Betrieb",
                10);
        assertThat(ids(byTitle)).containsExactly("12", "21");
        assertThat(byTitle.hits().get(0).relevance()).isNotNull();
        assertThat(byTitle.hits().get(1).relevance()).isNull();
    }

    @Test
    void withoutMetadataAccessAPageNeverAppears() {
        AccessPolicy noPage21 = (context, scope, kind) -> "21".equals(scope.pageId()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;

        assertThat(ids(search(noPage21).search(CALLER, query("betrieb"), "Betrieb", 10))).doesNotContain("21");
    }

    @Test
    void theSourceOrNamedSpacesNeedMetadataAccess() {
        AccessPolicy noSource = (context, scope, kind) -> scope.isSource() ? AccessDecision.DENY
                : AccessDecision.ALLOW;

        assertThatThrownBy(() -> search(noSource).search(CALLER, query(null), "Betrieb", 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThat(embeddedQueries).isEmpty();
    }

    @Test
    void everyFilterAppliesToVectorCandidatesToo() {
        PageSearchQuery dev = PageSearchQuery.of("wiki", List.of("DEV"), null, null, null, null, null, null,
                SearchSort.TITLE);

        assertThat(ids(search(ALLOW_ALL).search(CALLER, dev, "Betrieb", 10))).containsExactly("11", "12", "10");
    }

    @Test
    void theTitleIsTheQueryWhenThereIsNoQAndLimitCutsTheWindow() {
        assertThat(ids(search(ALLOW_ALL).search(CALLER, query("urlaubsplanung"), null, 1))).containsExactly("20");
        assertThat(embeddedQueries).containsExactly("urlaubsplanung");
    }

    @Test
    void withoutAServiceTheSearchIsUnavailable() {
        RelevanceSearch withoutModel = new RelevanceSearch(CATALOG, index, new AccessGuard(ALLOW_ALL), store, store,
                null);

        assertThat(withoutModel.semantic()).isFalse();
        // ADR 0011: kein stiller Rückfall auf die Titelsuche, auch mit title.
        assertThatThrownBy(() -> withoutModel.search(CALLER, query("betrieb"), "Betrieb", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class);
        assertThatThrownBy(() -> withoutModel.search(CALLER, query(null), "Betrieb", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class);
    }

    @Test
    void aFailingServiceMakesTheSearchUnavailable() {
        modelDown = true;

        assertThatThrownBy(() -> search(ALLOW_ALL).search(CALLER, query("betrieb"), "Betrieb", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class)
                .hasMessageNotContaining("Betrieb");
        assertThatThrownBy(() -> search(ALLOW_ALL).search(CALLER, query(null), "Betrieb", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class)
                .hasMessageNotContaining("Betrieb");
    }

    @Test
    void inputsAreBounded() {
        RelevanceSearch search = search(ALLOW_ALL);

        assertThatThrownBy(() -> search.search(CALLER, query(null), null, 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, query(null), "x".repeat(501), 10))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, query(null), "Betrieb", Reranking.MAX_WINDOW + 1))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void interleavingAlternatesAndDropsDuplicates() {
        var a = index.search(query(null), "i1", null, 10);
        List<String> mixed = RelevanceSearch.interleave(List.of(a.get(0), a.get(1), a.get(2)),
                List.of(a.get(1), a.get(3))).stream().map(hit -> hit.page().pageId()).toList();

        assertThat(mixed).containsExactly(a.get(0).page().pageId(), a.get(1).page().pageId(),
                a.get(3).page().pageId(), a.get(2).page().pageId());
    }

    private RelevanceSearch search(AccessPolicy policy) {
        return new RelevanceSearch(CATALOG, index, new AccessGuard(policy), store, store, processor);
    }

    private static PageSearchQuery query(String title) {
        return PageSearchQuery.of("wiki", List.of(), title, null, null, null, null, null, SearchSort.TITLE);
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
        StringBuilder json = new StringBuilder("{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[");
        for (int i = 0; i < vectors.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"charStart\":").append(i * 10).append(",\"charEnd\":")
                    .append(i * 10 + 10).append(",\"section\":null,\"vector\":").append(vectors[i]).append('}');
        }
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), revision, Enrichment.Status.OK, json.append("]}").toString(), List.of(),
                null, Instant.parse("2026-10-17T07:00:00Z")));
    }

    private static PageSnapshot page(String space, String pageId, String title, String version) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, null,
                version, Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
