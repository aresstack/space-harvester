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
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.PageTextIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.progressive.VariantSearch;
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
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@link SemanticVariantSearch}: Die Variante findet, die Vektorsuche sucht mit der ursprünglichen Anfrage; Rechte,
 * Spaces und Bewertung kommen aus der {@link RelevanceSearch}.
 */
class SemanticVariantSearchTest {

    private static final HarvestSource SOURCE = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[] {HarvestSource.class}, (proxy, method, args) -> null);

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

    private static final AccessContext CALLER = AccessContext.technicalClient("app");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final SearchIndexState STATE = new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z"));
    private static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");
    private static final String ORIGINAL = "Wie läuft der nächtliche Betrieb";

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final List<String> embeddedQueries = Collections.synchronizedList(new ArrayList<>());
    private final List<String> discovered = Collections.synchronizedList(new ArrayList<>());
    private final List<Set<String>> excludedOnDiscovery = Collections.synchronizedList(new ArrayList<>());
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
            embeddedQueries.addAll(texts);
            return texts.stream().map(SemanticVariantSearchTest::axes).toList();
        }
    }, 1500, KnowledgeTextSegmentation.defaults());

    @BeforeEach
    void publish() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "10", "Architektur-Übersicht"), confirmed("g1")),
                entry(page("DEV", "11", "Nachtbetrieb"), confirmed("g1")),
                entry(page("OPS", "20", "Urlaubsplanung"), confirmed("g2")),
                entry(page("OPS", "21", "Geheimer Betrieb"), confirmed("g2"))));
        embed("10", "[0,1,0]");
        embed("11", "[0.2,0,0.9]", "[1,0.1,0]");
        embed("20", "[0,0,1]");
        embed("21", "[1,0,0]");
    }

    @Test
    void theVariantFindsButTheVectorsSearchWithTheOriginalAndTheSimilarityIsTheScore() {
        VariantSearch.Outcome outcome = search(ALLOW_ALL).run(CALLER, query("Nachtbetrieb Ablauf", Set.of()),
                ORIGINAL, 10);

        assertThat(discovered).containsExactly("Nachtbetrieb Ablauf");
        assertThat(embeddedQueries).containsExactly(ORIGINAL);
        KeywordSearch.Results results = outcome.results();
        assertThat(results.hits()).extracting(KeywordSearch.Hit::pageId).startsWith("21", "11")
                .containsExactlyInAnyOrder("21", "11", "10", "20");
        // Alles aus dem Cache: keine vorläufigen Treffer der Quellen, nichts lädt mehr.
        assertThat(results.found()).isEmpty();
        assertThat(results.progress()).isNull();
        assertThat(outcome.scores().get(new PageKey("wiki", "11"))).isCloseTo(1 / Math.sqrt(1.01), within(1e-4));
        assertThat(outcome.scores()).containsOnlyKeys(new PageKey("wiki", "21"), new PageKey("wiki", "11"),
                new PageKey("wiki", "10"), new PageKey("wiki", "20"));
        // Der Auszug ist das ähnlichste Stück des Lesetexts.
        assertThat(results.hits()).filteredOn(hit -> hit.pageId().equals("11")).singleElement()
                .satisfies(hit -> {
                    assertThat(hit.snippet()).isEqualTo("Nachtlauf");
                    assertThat(hit.spaceKey()).isEqualTo("DEV");
                    assertThat(hit.title()).isEqualTo("Nachtbetrieb");
                });
    }

    @Test
    void withoutContentAccessAPageIsNeitherHitNorScore() {
        AccessPolicy noOpsContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;

        VariantSearch.Outcome outcome = search(noOpsContent).run(CALLER, query("Betrieb", Set.of()), ORIGINAL, 10);

        assertThat(outcome.results().hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("11", "10");
        assertThat(outcome.scores()).containsOnlyKeys(new PageKey("wiki", "11"), new PageKey("wiki", "10"));
    }

    @Test
    void excludedSpacesFallAway() {
        VariantSearch.Outcome outcome = search(ALLOW_ALL).run(CALLER, query("Betrieb", Set.of("OPS")), ORIGINAL, 10);

        assertThat(outcome.results().hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("11", "10");
        assertThat(outcome.scores()).doesNotContainKeys(new PageKey("wiki", "21"), new PageKey("wiki", "20"));
    }

    @Test
    void withoutAnEmbeddingServiceTheSearchIsUnavailableBeforeItStarts() {
        RelevanceSearch withoutModel = new RelevanceSearch(CATALOG, index, new AccessGuard(ALLOW_ALL), store, store,
                null);
        SemanticVariantSearch search = new SemanticVariantSearch(withoutModel, null);

        assertThatThrownBy(() -> search.requireAvailable(CALLER, query("Betrieb", Set.of())))
                .isInstanceOf(SemanticSearchUnavailableException.class);
        assertThatThrownBy(() -> search.run(CALLER, query("Betrieb", Set.of()), ORIGINAL, 10))
                .isInstanceOf(SemanticSearchUnavailableException.class).hasMessageNotContaining("Betrieb");
        search(ALLOW_ALL).requireAvailable(CALLER, query("Betrieb", Set.of()));
    }

    @Test
    void withoutAccessToTheNamedSourceTheCallerNeverLearnsWhetherAModelExists() {
        AccessPolicy noSource = (context, scope, kind) -> scope.isSource() ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        RelevanceSearch withoutModel = new RelevanceSearch(CATALOG, index, new AccessGuard(noSource), store, store,
                null);

        // Fail-closed wie die Relevanz-API: dieselbe Ablehnung mit und ohne Modell.
        assertThatThrownBy(() -> new SemanticVariantSearch(withoutModel, null).requireAvailable(CALLER,
                query("Betrieb", Set.of()))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> search(noSource).requireAvailable(CALLER, query("Betrieb", Set.of())))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void anExcludedTopHitLeavesRoomForAnAllowedOneAndIsNeverLoaded() {
        VariantSearch.Outcome outcome = search(ALLOW_ALL).run(CALLER, query("Betrieb", Set.of("OPS")), ORIGINAL, 1);

        // 21 (OPS) wäre der ähnlichste; erst gefiltert, dann gekürzt.
        assertThat(outcome.results().hits()).extracting(KeywordSearch.Hit::pageId).containsExactly("11");
        assertThat(excludedOnDiscovery).containsExactly(Set.of("OPS"));
    }

    private SemanticVariantSearch search(AccessPolicy policy) {
        RelevanceSearch.Preparation preparation = new RelevanceSearch.Preparation() {
            @Override
            public int prepare(AccessContext caller, PageSearchQuery query, String text) {
                throw new AssertionError("ohne Ausschlüsse vorbereitet");
            }

            @Override
            public int prepare(AccessContext caller, PageSearchQuery query, String text, Set<String> excluded) {
                discovered.add(text);
                excludedOnDiscovery.add(excluded);
                return 0;
            }
        };
        RelevanceSearch relevance = new RelevanceSearch(CATALOG, index, new AccessGuard(policy), store, store,
                processor, null, SearchReranker.NONE, texts(), preparation);
        return new SemanticVariantSearch(relevance, texts());
    }

    private static KeywordSearch.Query query(String text, Set<String> excluded) {
        return new KeywordSearch.Query(text, "wiki", Set.of(), Set.of(), excluded);
    }

    /** Lesetext: zehn Zeichen je Stück, das zweite Stück von 11 ist „Nachtlauf“. */
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
                return Optional.of("Übersicht Nachtlauf  und mehr");
            }

            @Override
            public void remove(PageKey key) {
            }

            @Override
            public void removeSource(String sourceId) {
            }

            @Override
            public List<Match> search(String text, Set<String> sourceIds, SearchSpaces spaces, int limit) {
                throw new AssertionError("die semantische Suche fragt den Volltextindex nicht nach Kandidaten");
            }

            @Override
            public void flush() {
            }
        };
    }

    private static float[] axes(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        float[] vector = new float[AXES.size()];
        for (int i = 0; i < AXES.size(); i++) {
            vector[i] = lower.contains(AXES.get(i)) ? 1 : 0;
        }
        return vector;
    }

    private void embed(String pageId, String... vectors) {
        StringBuilder json = new StringBuilder("{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[");
        for (int i = 0; i < vectors.length; i++) {
            json.append(i == 0 ? "" : ",").append("{\"charStart\":").append(i * 10).append(",\"charEnd\":")
                    .append(i * 10 + 10).append(",\"section\":null,\"vector\":").append(vectors[i]).append('}');
        }
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), "1", Enrichment.Status.OK, json.append("]}").toString(), List.of(),
                null, Instant.parse("2026-10-17T07:00:00Z")));
    }

    private static PageSnapshot page(String space, String pageId, String title) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, null, "1",
                Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
