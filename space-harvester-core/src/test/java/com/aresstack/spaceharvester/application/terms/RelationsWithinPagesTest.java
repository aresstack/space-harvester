package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Beziehungen einer Seitenmenge (BG1, ADR 0029): Quelle oder Space setzen sich aus ihren Seiten zusammen. Eine
 * Beziehung gehört dazu, wenn einer ihrer Begriffe auf einer Seite der Menge vorkommt und ihr Geltungsbereich diese
 * Seite deckt; für genau eine Seite wie {@link TermRelations#forTerms}.
 */
class RelationsWithinPagesTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final List<String> configured = List.of("wiki", "other");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(RelationsWithinPagesTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    private final InMemoryEnrichmentStore index = new InMemoryEnrichmentStore();
    private final TermProcessor processor = new TermProcessor();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(processor), index,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private final Set<PageKey> hidden = new HashSet<>();
    private final AccessPolicy policy = (context, scope, kind) -> kind == AccessKind.CONTENT
            && scope.pageId() != null && hidden.contains(new PageKey(scope.sourceId(), scope.pageId()))
            ? AccessDecision.DENY : AccessDecision.ALLOW;
    private TermRelations relations;

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private void page(String sourceId, String space, String pageId, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId,
                "Seite " + pageId, space, null, "1", T0, null, PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
    }

    private TermRelation add(String from, String to, RelationType type, RelationScope scope) {
        return relations.add(CALLER, new TermRelations.NewRelation(from, to, type, scope, null, List.of()));
    }

    private List<String> within(PageSet pages) {
        return relations.relations(CALLER, new TermRelations.RelationSearch(null, null, null, null, null, 200, null)
                .within(pages)).relations().stream().map(TermRelation::id).toList();
    }

    private static PageSet pages(PageKey... keys) {
        return PageSet.ofKeys(List.of(keys));
    }

    @BeforeEach
    void setUp() {
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), new AccessGuard(policy),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC),
                index, processor.version());
        page("wiki", "DEV", "1", "Der RSL steht im Beleg.");
        page("wiki", "DEV", "2", "Die Buchung braucht einen Beleg.");
        page("wiki", "OPS", "3", "Die ZV meldet sich.");
        page("other", "DEV", "1", "Hier steht nur ein Konto.");
    }

    @Test
    void aSpaceCollectsTheRelationsOfItsPages() {
        TermRelation rsl = add("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        TermRelation beleg = add("Beleg", "Buchungsbeleg", RelationType.SYNONYM, new RelationScope("wiki", null));
        TermRelation zv = add("ZV", "Zahlungsverkehr", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Konto", "Kontonummer", RelationType.RELATED, RelationScope.GLOBAL);

        PageSet dev = pages(new PageKey("wiki", "1"), new PageKey("wiki", "2"));

        assertThat(within(dev)).containsExactlyInAnyOrderElementsOf(List.of(rsl.id(), beleg.id()));
        assertThat(within(pages(new PageKey("wiki", "1"), new PageKey("wiki", "2"), new PageKey("wiki", "3"))))
                .contains(zv.id()).hasSize(3);
    }

    @Test
    void forExactlyOnePageItIsWhatForTermsReturns() {
        add("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Beleg", "Buchungsbeleg", RelationType.SYNONYM, new RelationScope("wiki", null));
        add("Beleg", "Quittung", RelationType.VARIANT, new RelationScope("wiki", "OPS"));
        add("ZV", "Zahlungsverkehr", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Beleg", "Nachweis", RelationType.SYNONYM, new RelationScope("other", null));
        PageKey key = new PageKey("wiki", "1");
        Enrichment terms = index.find(key).stream().filter(result -> result.processorId().equals("terms"))
                .findFirst().orElseThrow();
        List<String> normalized = TermPayload.postings(terms).stream().map(TermPosting::normalized).toList();

        List<String> expected = relations.forTerms(CALLER, normalized, "wiki", "DEV").stream()
                .map(TermRelation::id).toList();

        assertThat(expected).hasSize(2);
        assertThat(within(pages(key))).containsExactlyElementsOf(expected);
    }

    @Test
    void theScopeMustCoverThePageWhereTheTermOccurs() {
        // Beleg kommt nur in DEV vor; eine Beziehung des Space OPS deckt diese Seiten nicht.
        add("Beleg", "Quittung", RelationType.VARIANT, new RelationScope("wiki", "OPS"));
        add("Beleg", "Nachweis", RelationType.SYNONYM, new RelationScope("other", null));

        assertThat(within(pages(new PageKey("wiki", "1"), new PageKey("wiki", "2")))).isEmpty();
    }

    @Test
    void hiddenPagesAndAnEmptySetContributeNothing() {
        add("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        hidden.add(new PageKey("wiki", "1"));

        assertThat(within(pages(new PageKey("wiki", "1")))).isEmpty();
        assertThat(within(PageSet.EMPTY)).isEmpty();
    }

    @Test
    void withoutASetEverythingStaysAsBefore() {
        add("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Konto", "Kontonummer", RelationType.RELATED, RelationScope.GLOBAL);

        assertThat(within(null)).hasSize(2);
    }

}
