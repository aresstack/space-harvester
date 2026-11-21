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
import java.util.EnumMap;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Gesamtzahlen für den Reiter „Begriffe“ (BG1b): {@link TermQueries#totals} und {@link TermRelations#counts} ergeben
 * genau so viel wie vollständiges Blättern mit {@code terms()} bzw. {@code relations()}.
 */
class TermTotalsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-17T10:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final List<String> configured = List.of("wiki", "other");
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(TermTotalsTest::source).toList();
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
    private TermQueries terms;

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

    private void add(String from, String to, RelationType type, RelationScope scope) {
        relations.add(CALLER, new TermRelations.NewRelation(from, to, type, scope, null, List.of()));
    }

    @BeforeEach
    void setUp() {
        AccessGuard guard = new AccessGuard(policy);
        relations = new TermRelations(catalog, new InMemoryTermRelationStore(), guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header), Clock.fixed(T0, ZoneOffset.UTC),
                index, processor.version());
        terms = new TermQueries(catalog, index, guard,
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)), PLAIN, processor.version());
        page("wiki", "DEV", "1", "Der RSL steht im Beleg. Die BKZ gehört zum Konto.");
        page("wiki", "DEV", "2", "Die Buchung braucht einen Beleg. Der RSL ist gesetzt.");
        page("wiki", "OPS", "3", "Die ZV meldet sich. Das KfzKennzeichen steht fest.");
        page("other", "DEV", "1", "Hier steht nur ein Konto und die BKZ.");
        add("RSL", "Rückmeldeschlüssel", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Beleg", "Buchungsbeleg", RelationType.SYNONYM, new RelationScope("wiki", null));
        add("ZV", "Zahlungsverkehr", RelationType.ABBREVIATION, RelationScope.GLOBAL);
        add("Konto", "Kontonummer", RelationType.RELATED, RelationScope.GLOBAL);
    }

    private Map<TermKind, Integer> paged(TermQueries.TermSearch search) {
        Map<TermKind, Integer> counts = new EnumMap<>(TermKind.class);
        String cursor = null;
        do {
            TermQueries.TermList list = terms.terms(CALLER, new TermQueries.TermSearch(search.sourceId(),
                    search.spaceKey(), search.kind(), search.q(), search.exact(), 2, cursor, search.startsWith(),
                    search.origin(), search.pages()));
            list.terms().forEach(term -> counts.merge(term.kind(), 1, Integer::sum));
            cursor = list.nextCursor();
        } while (cursor != null);
        return counts;
    }

    private Map<RelationType, Integer> paged(TermRelations.RelationSearch search) {
        Map<RelationType, Integer> counts = new EnumMap<>(RelationType.class);
        String cursor = null;
        do {
            TermRelations.RelationList list = relations.relations(CALLER, new TermRelations.RelationSearch(
                    search.term(), search.type(), search.status(), search.origin(), search.sourceId(), 1, cursor,
                    search.startsWith(), search.pages()));
            list.relations().forEach(relation -> counts.merge(relation.type(), 1, Integer::sum));
            cursor = list.nextCursor();
        } while (cursor != null);
        return counts;
    }

    private List<TermQueries.TermSearch> termSearches() {
        PageSet dev = PageSet.ofKeys(List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2")));
        return List.of(
                new TermQueries.TermSearch(null, null, null, null, false, 1, null),
                new TermQueries.TermSearch("wiki", null, null, null, false, 1, null),
                new TermQueries.TermSearch("wiki", "DEV", TermKind.ACRONYM, null, false, 1, null),
                new TermQueries.TermSearch(null, null, null, null, false, 1, null, "b"),
                new TermQueries.TermSearch(null, null, null, null, false, 1, null).within(dev),
                new TermQueries.TermSearch(null, null, null, null, false, 1, null).within(PageSet.EMPTY));
    }

    private List<TermRelations.RelationSearch> relationSearches() {
        PageSet dev = PageSet.ofKeys(List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2")));
        return List.of(
                new TermRelations.RelationSearch(null, null, null, null, null, 1, null),
                new TermRelations.RelationSearch(null, null, null, null, "wiki", 1, null),
                new TermRelations.RelationSearch(null, RelationType.ABBREVIATION, null, null, null, 1, null),
                new TermRelations.RelationSearch(null, null, null, null, null, 1, null, "r"),
                new TermRelations.RelationSearch(null, null, null, null, null, 1, null).within(dev));
    }

    @Test
    void totalsEqualWhatPagingThroughTermsReturns() {
        for (TermQueries.TermSearch search : termSearches()) {
            assertThat(terms.totals(CALLER, search).byKind()).as(search.toString()).isEqualTo(paged(search));
        }
        assertThat(terms.totals(CALLER, termSearches().get(0)).total()).isPositive();
    }

    @Test
    void countsEqualWhatPagingThroughRelationsReturns() {
        for (TermRelations.RelationSearch search : relationSearches()) {
            assertThat(relations.counts(CALLER, search)).as(search.toString()).isEqualTo(paged(search));
        }
        assertThat(relations.counts(CALLER, relationSearches().get(0))).containsEntry(RelationType.ABBREVIATION, 2);
    }

    @Test
    void hiddenPagesCountNowhere() {
        hidden.add(new PageKey("wiki", "3"));

        for (TermQueries.TermSearch search : termSearches()) {
            assertThat(terms.totals(CALLER, search).byKind()).as(search.toString()).isEqualTo(paged(search));
        }
        for (TermRelations.RelationSearch search : relationSearches()) {
            assertThat(relations.counts(CALLER, search)).as(search.toString()).isEqualTo(paged(search));
        }
        PageSet ops = PageSet.ofKeys(List.of(new PageKey("wiki", "3")));
        assertThat(terms.totals(CALLER, termSearches().get(0).within(ops)).total()).isZero();
    }

    /** Fahrplan P1: je Begriff die Zahl der Seiten einer Menge, wie {@code terms().pages()} innerhalb der Menge. */
    @Test
    void pageCountsEqualThePagesOfEachTermWithinTheSet() {
        PageSet below = PageSet.ofKeys(List.of(new PageKey("wiki", "2"), new PageKey("wiki", "3"),
                new PageKey("other", "1")));
        Map<TermQueries.TermRef, Integer> expected = new HashMap<>();
        String cursor = null;
        do {
            TermQueries.TermList list = terms.terms(CALLER, new TermQueries.TermSearch(null, null, null, null, false,
                    2, cursor).within(below));
            list.terms().forEach(term -> expected.put(TermQueries.TermRef.of(term), term.pages()));
            cursor = list.nextCursor();
        } while (cursor != null);

        assertThat(terms.pageCounts(CALLER, below)).isEqualTo(expected).isNotEmpty();
        assertThat(terms.pageCounts(CALLER, below)).containsEntry(new TermQueries.TermRef("bkz", TermKind.ACRONYM), 1)
                .containsEntry(new TermQueries.TermRef("rsl", TermKind.ACRONYM), 1);
        assertThat(terms.pageCounts(CALLER, PageSet.EMPTY)).isEmpty();

        hidden.add(new PageKey("wiki", "3"));
        assertThat(terms.pageCounts(CALLER, PageSet.ofKeys(List.of(new PageKey("wiki", "3"))))).isEmpty();
    }

    /** Messung für den Architekten: etwa 5.000 Seiten, eine Zählung über alles; die Zeit steht in der Ausgabe. */
    @Test
    void fiveThousandPagesAreCountedInOnePass() {
        for (int i = 0; i < 5_000; i++) {
            page("wiki", "BIG", "p" + i, "Der Vorgang V" + i + " nutzt den RSL und die Buchung " + (i % 97)
                    + ". Das Konto K" + (i % 311) + " gehört zur BKZ.");
        }
        TermQueries.TermSearch all = new TermQueries.TermSearch("wiki", null, null, null, false, 1, null);
        TermRelations.RelationSearch every = new TermRelations.RelationSearch(null, null, null, null, "wiki", 1,
                null).within(PageSet.ofKeys(cache.keySet().stream().filter(key -> key.sourceId().equals("wiki"))
                .toList()));
        terms.totals(CALLER, all);
        long start = System.nanoTime();
        TermQueries.TermTotals totals = terms.totals(CALLER, all);
        long termsMillis = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        Map<RelationType, Integer> counts = relations.counts(CALLER, every);
        long relationsMillis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("BG1b-Messung: 5000 Seiten, Begriffe " + totals.total() + " in " + termsMillis
                + " ms, Beziehungen " + counts.values().stream().mapToInt(Integer::intValue).sum() + " in "
                + relationsMillis + " ms");
        assertThat(totals.total()).isGreaterThan(5_000);
    }
}
