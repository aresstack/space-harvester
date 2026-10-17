package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Relevanzsuche über mehrere Quellen (#114): ein Kandidatenfenster für alle Quellen, eine Einbettung der Anfrage,
 * Deduplikation nach {@code sourceId + pageId}, Rechte je Quelle und Seite.
 */
class GlobalRelevanceSearchTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final Instant T0 = Instant.parse("2026-10-17T08:00:00Z");
    private static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");

    private final List<String> configured = new ArrayList<>(List.of("wiki-a", "wiki-b"));
    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final List<String> embeddedQueries = new ArrayList<>();
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
            return texts.stream().map(GlobalRelevanceSearchTest::axes).toList();
        }
    }, 1500);

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(GlobalRelevanceSearchTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };

    /** Beide Quellen haben eine Seite 10 im Space DEV, mit anderem Inhalt. */
    private void twoSources() {
        index.publish("wiki-a", new SearchIndexState("a1", T0), List.of(
                entry(page("wiki-a", "DEV", "10", "Architektur-Übersicht"), confirmed("c1")),
                entry(page("wiki-a", "DEV", "11", "Nachtbetrieb"), confirmed("c1")),
                entry(page("wiki-a", "OPS", "20", "Geheimer Betrieb"), confirmed("c1"))));
        index.publish("wiki-b", new SearchIndexState("b1", T0), List.of(
                entry(page("wiki-b", "DEV", "10", "Betrieb Übersicht"), confirmed("c1")),
                entry(page("wiki-b", "DEV", "11", "Urlaub"), confirmed("c1"))));
        embed("wiki-a", "10", "[0,1,0]");
        embed("wiki-a", "11", "[1,0.1,0]");
        embed("wiki-a", "20", "[1,0,0]");
        embed("wiki-b", "10", "[0.9,0.2,0]");
        embed("wiki-b", "11", "[0,0,1]");
    }

    @Test
    void oneGlobalWindowRanksPagesOfAllSourcesWithOneQueryEmbedding() {
        twoSources();

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, global(null), "Betrieb", 10);

        assertThat(results.global()).isTrue();
        assertThat(results.sourceId()).isNull();
        assertThat(keys(results).subList(0, 3)).containsExactly("wiki-a/20", "wiki-a/11", "wiki-b/10");
        // Gleiche Seiten-ID, andere Quelle: zwei Kandidaten.
        assertThat(keys(results)).contains("wiki-a/10", "wiki-b/10").doesNotHaveDuplicates().hasSize(5);
        assertThat(embeddedQueries).containsExactly("Betrieb");
        // Ähnlichkeiten aus demselben Modell, über Quellen vergleichbar und absteigend.
        List<Double> similarities = results.hits().stream().map(hit -> hit.relevance().similarity()).toList();
        assertThat(similarities).isSortedAccordingTo((left, right) -> Double.compare(right, left));
    }

    @Test
    void lexicalAndSemanticCandidatesAreDeduplicatedByPageKeyNotPageId() {
        twoSources();
        // Titel „übersicht“: Seite 10 in beiden Quellen, jede kommt aus Titel- und Vektorsuche.
        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, global("übersicht"), "Betrieb",
                10);

        assertThat(keys(results)).containsExactly("wiki-b/10", "wiki-a/10");
        assertThat(RelevanceSearch.interleave(results.hits().stream().map(RelevanceSearch.RankedHit::hit).toList(),
                results.hits().stream().map(RelevanceSearch.RankedHit::hit).toList())).hasSize(2);
    }

    @Test
    void contentOfOneSourceStaysHiddenWithoutContentAccess() {
        twoSources();
        AccessPolicy noContentInB = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "wiki-b".equals(scope.sourceId()) ? AccessDecision.DENY : AccessDecision.ALLOW;

        assertThat(keys(search(noContentInB).search(CALLER, global(null), "Betrieb", 10)))
                .containsExactly("wiki-a/20", "wiki-a/11", "wiki-a/10");
        RelevanceSearch.RelevanceResults byTitle = search(noContentInB).search(CALLER, global("betrieb"), "Betrieb",
                10);
        assertThat(byTitle.hits()).filteredOn(hit -> hit.hit().page().header().key().sourceId().equals("wiki-b"))
                .singleElement().satisfies(hit -> assertThat(hit.relevance()).isNull());
        assertThat(keys(byTitle).get(keys(byTitle).size() - 1)).isEqualTo("wiki-b/10");
    }

    @Test
    void aSourceWithoutMetadataAccessNeverAppearsAndIsOnlyRefusedWhenNamed() {
        twoSources();
        AccessPolicy noB = (context, scope, kind) -> "wiki-b".equals(scope.sourceId()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;

        RelevanceSearch.RelevanceResults results = search(noB).search(CALLER, global("betrieb"), "Betrieb", 10);

        assertThat(keys(results)).allMatch(key -> key.startsWith("wiki-a/")).isNotEmpty();
        assertThat(results.index().generation()).doesNotContain("wiki-b");
        assertThatThrownBy(() -> search(noB).search(CALLER, PageSearchQuery.of("wiki-b", List.of(), null, null, null,
                null, null, null, SearchSort.TITLE), "Betrieb", 10)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSourceListLimitsTheRelevanceWindowToTheseSources() {
        twoSources();
        PageSearchQuery onlyB = PageSearchQuery.of(null, List.of("wiki-b"), List.of(), null, null, null, null, null,
                null, SearchSort.TITLE);

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, onlyB, "Betrieb", 10);

        assertThat(results.global()).isTrue();
        assertThat(keys(results)).allMatch(key -> key.startsWith("wiki-b/")).isNotEmpty();
    }

    @Test
    void withoutAModelTheGlobalTitleSearchStillWorks() {
        twoSources();
        RelevanceSearch withoutModel = new RelevanceSearch(catalog, index, new AccessGuard(ALLOW_ALL), store, store,
                null);

        RelevanceSearch.RelevanceResults results = withoutModel.search(CALLER, global("betrieb"), "Betrieb", 10);

        assertThat(results.reranker()).isEqualTo("none");
        assertThat(keys(results)).containsExactly("wiki-b/10", "wiki-a/20");
        assertThatThrownBy(() -> withoutModel.search(CALLER, global(null), "Betrieb", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class);
    }

    @Test
    void theWindowStaysBoundedAcrossManySources() {
        configured.clear();
        for (int s = 0; s < 6; s++) {
            String sourceId = "quelle-" + s;
            configured.add(sourceId);
            List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                entries.add(entry(page(sourceId, "DEV", String.valueOf(i), "Betrieb " + i), confirmed("c1")));
                embed(sourceId, String.valueOf(i), "[1," + (i / 100.0) + ",0]");
            }
            index.publish(sourceId, new SearchIndexState("s" + s, T0), entries);
        }

        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, global("betrieb"), "Betrieb",
                Reranking.MAX_WINDOW);

        assertThat(results.hits()).hasSize(Reranking.MAX_WINDOW);
        assertThat(keys(results)).doesNotHaveDuplicates();
        assertThat(embeddedQueries).hasSize(1);
        // Die ähnlichsten Seiten (kleinstes i) aus allen Quellen stehen vorn, nicht die einer einzelnen Quelle.
        assertThat(keys(results).subList(0, 6)).extracting(key -> key.substring(key.indexOf('/')))
                .containsOnly("/0");
    }

    @Test
    void withoutIndexedSourcesTheGlobalResultIsEmpty() {
        RelevanceSearch.RelevanceResults results = search(ALLOW_ALL).search(CALLER, global(null), "Betrieb", 10);

        assertThat(results.hits()).isEmpty();
        assertThat(results.index()).isNull();
    }

    private RelevanceSearch search(AccessPolicy policy) {
        return new RelevanceSearch(catalog, index, new AccessGuard(policy), store, store, processor);
    }

    private static PageSearchQuery global(String title) {
        return PageSearchQuery.of(null, List.of(), title, null, null, null, null, null, SearchSort.TITLE);
    }

    private static List<String> keys(RelevanceSearch.RelevanceResults results) {
        return results.hits().stream().map(hit -> hit.hit().page().header().key())
                .map(key -> key.sourceId() + "/" + key.pageId()).toList();
    }

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private static float[] axes(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        float[] vector = new float[AXES.size()];
        for (int i = 0; i < AXES.size(); i++) {
            vector[i] = lower.contains(AXES.get(i)) ? 1 : 0;
        }
        return vector;
    }

    private void embed(String sourceId, String pageId, String vector) {
        String json = "{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[{\"charStart\":0,\"charEnd\":10,"
                + "\"section\":null,\"vector\":" + vector + "}]}";
        store.save(new Enrichment(new PageKey(sourceId, pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), "1", Enrichment.Status.OK, json, List.of(), null, T0));
    }

    private static PageSnapshot page(String sourceId, String space, String pageId, String title) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId, title, space,
                null, "1", T0, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(sourceId, metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), T0);
    }
}
