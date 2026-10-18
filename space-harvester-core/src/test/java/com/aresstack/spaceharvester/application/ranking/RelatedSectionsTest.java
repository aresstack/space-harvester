package com.aresstack.spaceharvester.application.ranking;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.assertj.core.api.Assertions.within;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** {@link RelatedSections}: Kantenarten und ihre Reihenfolge, Grenzen, Rechte (auch negativ). */
class RelatedSectionsTest {

    private static final HarvestSource SOURCE = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[] {HarvestSource.class},
            (proxy, method, args) -> method.getName().equals("id") ? "wiki" : null);

    private static final SourceCatalog CATALOG = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(SOURCE);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(SOURCE) : Optional.empty();
        }
    };

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final SearchIndexState STATE = new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z"));

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryEnrichmentStore store = new InMemoryEnrichmentStore();
    private final EmbeddingProcessor processor = new EmbeddingProcessor(new EmbeddingProvider() {
        @Override
        public String modelId() {
            return "axes";
        }

        @Override
        public int dimensions() {
            return 3;
        }

        @Override
        public List<float[]> embed(List<String> texts) {
            throw new AssertionError("verwandte Abschnitte betten nichts ein");
        }
    }, 1500, KnowledgeTextSegmentation.defaults());

    @BeforeEach
    void publish() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "0", "Handbuch", null), confirmed("g1")),
                entry(page("DEV", "1", "Betrieb", "0"), confirmed("g1")),
                entry(page("DEV", "2", "Nachtlauf", "0"), confirmed("g1")),
                entry(page("DEV", "3", "Urlaub", "0"), confirmed("g1")),
                entry(page("OPS", "9", "Geheimer Nachtlauf", null), confirmed("g2"))));
        embed("0", chunk(null, "[0,1,0]"));
        // Stück 2 ist der Ausgang; 1 und 3 sind seine Nachbarn und ihm am ähnlichsten.
        embed("1", chunk("Betrieb", "[0,1,0]"), chunk("Betrieb > Nacht", "[1,0.1,0]"),
                chunk("Betrieb > Nacht", "[1,0,0]"), chunk("Betrieb > Nacht", "[1,0.05,0]"),
                chunk("Betrieb > Nacht", "[0.9,0.2,0]"));
        embed("2", chunk(null, "[0.95,0.1,0]"), chunk(null, "[0.85,0.4,0]"));
        embed("3", chunk(null, "[0,0,1]"));
        embed("9", chunk(null, "[1,0,0]"));
    }

    @Test
    void theNeighbourhoodHasEveryKindInOrderAndNeverTheSeedOrItsDirectNeighboursAsSimilar() {
        RelatedSections.Results results = search(ALLOW_ALL).related(CALLER, "wiki", "1", 2,
                RelatedSections.Query.all());

        assertThat(results.origin()).isEqualTo(new RelatedSections.Section(2, 20, 30, "Betrieb > Nacht"));
        assertThat(results.relations()).extracting(relation -> relation.kind(),
                        relation -> relation.page().page().header().key().pageId(),
                        relation -> relation.section() == null ? null : relation.section().number())
                .containsExactly(
                        tuple(RelatedSections.Kind.SIMILAR_TO, "9", 0),
                        tuple(RelatedSections.Kind.SIMILAR_TO, "2", 0),
                        tuple(RelatedSections.Kind.SIMILAR_TO, "1", 4),
                        tuple(RelatedSections.Kind.PARENT, "0", 0),
                        tuple(RelatedSections.Kind.NEXT, "1", 3),
                        tuple(RelatedSections.Kind.PREVIOUS, "1", 1));
        assertThat(results.relations().getFirst().score()).isEqualTo(1.0, within(1e-6));
        assertThat(results.complete()).isTrue();
    }

    @Test
    void sameSectionKeepsTheNearestChunksAndEachTargetAppearsOnce() {
        RelatedSections.Results results = search(ALLOW_ALL).related(CALLER, "wiki", "1", 2,
                RelatedSections.Query.all().withKinds(List.of(RelatedSections.Kind.SAME_SECTION)));

        // 1 und 3 sind Nachbarn, 0 liegt in einem anderen Abschnitt.
        assertThat(results.relations()).extracting(relation -> relation.section().number()).containsExactly(4);
        assertThat(results.relations().getFirst().section().section()).isEqualTo("Betrieb > Nacht");
    }

    @Test
    void limitsAndThresholdApplyPerKind() {
        RelatedSections.Query one = new RelatedSections.Query(EnumSet.of(RelatedSections.Kind.SIMILAR_TO), 1, 0.99,
                Set.of());

        assertThat(search(ALLOW_ALL).related(CALLER, "wiki", "1", 2, one).relations())
                .extracting(relation -> relation.page().page().header().key().pageId()).containsExactly("9");
        assertThatThrownBy(() -> new RelatedSections.Query(Set.of(), 0, 0.5, Set.of()))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> new RelatedSections.Query(Set.of(), 3, 1.5, Set.of()))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void aTargetWithoutContentAccessNeverAppears() {
        AccessPolicy noOpsContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;

        assertThat(search(noOpsContent).related(CALLER, "wiki", "1", 2, RelatedSections.Query.all()).relations())
                .extracting(relation -> relation.page().page().header().key().pageId()).doesNotContain("9");
    }

    @Test
    void aSeedThatIsNotVisibleOrNotCachedIsACacheMissAndAnUnknownChunkIsInvalid() {
        AccessPolicy noOpsContent = (context, scope, kind) -> kind == AccessKind.CONTENT
                && "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;

        assertThatThrownBy(() -> search(noOpsContent).related(CALLER, "wiki", "9", 0, RelatedSections.Query.all()))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> search(ALLOW_ALL).related(CALLER, "wiki", "404", 0, RelatedSections.Query.all()))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> search(ALLOW_ALL).related(CALLER, "wiki", "1", 5, RelatedSections.Query.all()))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void withoutAnEmbeddingOnlyTheParentRemains() {
        store.removeAll(new PageKey("wiki", "2"));

        RelatedSections.Results results = search(ALLOW_ALL).related(CALLER, "wiki", "2", 0,
                RelatedSections.Query.all());

        assertThat(results.origin()).isNull();
        assertThat(results.relations()).extracting(RelatedSections.Relation::kind)
                .containsExactly(RelatedSections.Kind.PARENT);
    }

    @Test
    void targetsCanBeLimitedToSources() {
        RelatedSections.Query elsewhere = new RelatedSections.Query(EnumSet.allOf(RelatedSections.Kind.class), 3, 0.8,
                Set.of("anderswo"));

        RelatedSections.Query here = new RelatedSections.Query(EnumSet.allOf(RelatedSections.Kind.class), 3, 0.8,
                Set.of("wiki"));

        // Wie im Enterprise AI Client gilt der Filter für jede Kante, auch innerhalb der Seite.
        assertThat(search(ALLOW_ALL).related(CALLER, "wiki", "1", 2, elsewhere).relations()).isEmpty();
        assertThat(search(ALLOW_ALL).related(CALLER, "wiki", "1", 2, here).relations()).hasSize(6);
    }

    private RelevanceSearch search(AccessPolicy policy) {
        return new RelevanceSearch(CATALOG, index, new AccessGuard(policy), store, store, processor);
    }

    private static String chunk(String section, String vector) {
        return (section == null ? "null" : "\"" + section + "\"") + "|" + vector;
    }

    private void embed(String pageId, String... chunks) {
        StringBuilder json = new StringBuilder("{\"model\":\"axes\",\"dimensions\":3,\"chunks\":[");
        for (int i = 0; i < chunks.length; i++) {
            String[] parts = chunks[i].split("\\|");
            json.append(i == 0 ? "" : ",").append("{\"charStart\":").append(i * 10).append(",\"charEnd\":")
                    .append(i * 10 + 10).append(",\"section\":").append(parts[0]).append(",\"vector\":")
                    .append(parts[1]).append('}');
        }
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), "1", Enrichment.Status.OK, json.append("]}").toString(), List.of(),
                null, Instant.parse("2026-10-17T07:00:00Z")));
    }

    private static PageSnapshot page(String space, String pageId, String title, String parent) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, parent, "1",
                Instant.parse("2026-10-01T00:00:00Z"), null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }
}
