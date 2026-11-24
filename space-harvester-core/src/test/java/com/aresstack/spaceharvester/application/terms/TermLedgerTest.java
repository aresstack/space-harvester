package com.aresstack.spaceharvester.application.terms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Die Verwalter der Begriffe ({@link TermLedger}): Jede Ebene liefert dieselbe Zahl wie das Zählen über die Einträge,
 * und nach einer Änderung wird nur der betroffene Space neu gelesen.
 */
class TermLedgerTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final Instant T0 = Instant.parse("2026-11-24T10:00:00Z");
    private static final PageTextProjector PLAIN = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));

    private final List<String> configured = new ArrayList<>(List.of("wiki", "other"));
    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(TermLedgerTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };
    /** Zählt, wie oft die Verwalter Schlüssel lesen. */
    private final AtomicInteger keyReads = new AtomicInteger();
    private final InMemoryEnrichmentStore index = new InMemoryEnrichmentStore();
    private final TermIndex counting = new TermIndex() {
        @Override
        public List<TermPosting> postings(Filter filter, String from, boolean inclusive, int max) {
            return index.postings(filter, from, inclusive, max);
        }

        @Override
        public List<TermKey> keys(Filter filter, String from, int max) {
            keyReads.incrementAndGet();
            return index.keys(filter, from, max);
        }

        @Override
        public Runnable subscribe(java.util.function.Consumer<Change> listener) {
            return index.subscribe(listener);
        }
    };
    private final TermProcessor processor = new TermProcessor();
    private final Enrichments enrichments = new Enrichments(PLAIN, List.of(processor), index,
            Clock.fixed(T0, ZoneOffset.UTC));
    private final Map<PageKey, PageSnapshot> cache = new HashMap<>();
    private boolean sourceWide = true;
    private final AccessPolicy policy = new AccessPolicy() {
        @Override
        public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
            return AccessDecision.ALLOW;
        }

        @Override
        public AccessDecision decideSourceWide(AccessContext context, String sourceId, AccessKind kind) {
            return sourceWide ? AccessDecision.ALLOW : AccessDecision.UNDETERMINED;
        }
    };
    private TermQueries terms;
    private com.aresstack.spaceharvester.application.holdings.HoldingsChanges cacheChanges;
    private int revision = 1;

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private void page(String sourceId, String space, String pageId, String text) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId,
                "Seite " + pageId, space, null, Integer.toString(revision++), T0, null,
                PageMetadata.sorted(Map.of()));
        PageSnapshot snapshot = PageSnapshot.of(sourceId, metadata, new NativePayload(
                text.getBytes(StandardCharsets.UTF_8), "text/plain", "test"), T0);
        cache.put(snapshot.key(), snapshot);
        enrichments.refresh(snapshot);
        cacheChanges.pagesChanged(sourceId, space, List.of(pageId));
    }

    private void remove(PageKey key) {
        PageSnapshot gone = cache.remove(key);
        index.removeAll(key);
        cacheChanges.pagesChanged(key.sourceId(), gone.metadata().partitionKey(), List.of(key.pageId()));
    }

    @BeforeEach
    void setUp() {
        terms = new TermQueries(catalog, counting, new AccessGuard(policy),
                key -> Optional.ofNullable(cache.get(key)).map(PageSnapshot::header),
                key -> Optional.ofNullable(cache.get(key)), PLAIN, processor.version());
        cacheChanges = terms.managedTotals();
        page("wiki", "DEV", "1", "Der RSL steht im Beleg. Die BKZ gehört zum Konto.");
        page("wiki", "DEV", "2", "Die Buchung braucht einen Beleg. Der RSL ist gesetzt.");
        page("wiki", "OPS", "3", "Die ZV meldet sich. Der Beleg und das KfzKennzeichen stehen fest.");
        page("other", "DEV", "1", "Hier steht nur ein Konto und die BKZ.");
    }

    private TermQueries.TermTotals node(String sourceId, String spaceKey) {
        return terms.totals(CALLER, new TermQueries.TermSearch(sourceId, spaceKey, null, null, false, 10, null));
    }

    private PageSet pagesOf(String sourceId, String spaceKey) {
        return PageSet.ofKeys(cache.values().stream()
                .filter(page -> sourceId == null || page.key().sourceId().equals(sourceId))
                .filter(page -> spaceKey == null || spaceKey.equals(page.metadata().partitionKey()))
                .map(PageSnapshot::key).toList());
    }

    /** Dieselbe Frage unabhängig gezählt: die Begriffsliste der Seiten vollständig durchgeblättert. */
    private TermQueries.TermTotals counted(String sourceId, String spaceKey) {
        return listed(pagesOf(sourceId, spaceKey));
    }

    private TermQueries.TermTotals listed(PageSet pages) {
        Map<TermKind, Integer> counts = new java.util.EnumMap<>(TermKind.class);
        String cursor = null;
        do {
            TermQueries.TermList list = terms.terms(CALLER, new TermQueries.TermSearch(null, null, null, null, false,
                    2, cursor).within(pages));
            list.terms().forEach(term -> counts.merge(term.kind(), 1, Integer::sum));
            cursor = list.nextCursor();
        } while (cursor != null);
        return new TermQueries.TermTotals(counts);
    }

    private TermQueries.TermTotals selection(PageSet pages) {
        return terms.totals(CALLER, new TermQueries.TermSearch(null, null, null, null, false, 10, null)
                .within(pages));
    }

    private void assertEveryNodeMatches() {
        assertThat(node(null, null)).isEqualTo(counted(null, null));
        assertThat(node("wiki", null)).isEqualTo(counted("wiki", null));
        assertThat(node("other", null)).isEqualTo(counted("other", null));
        assertThat(node("wiki", "DEV")).isEqualTo(counted("wiki", "DEV"));
        assertThat(node("wiki", "OPS")).isEqualTo(counted("wiki", "OPS"));
        assertThat(node("other", "DEV")).isEqualTo(counted("other", "DEV"));
    }

    @Test
    void everyLevelCountsTheUnionOfItsChildren() {
        assertThat(counted(null, null).total()).isPositive();
        assertEveryNodeMatches();
        // „Beleg“ steht in zwei Spaces und zählt in der Quelle einmal; „Konto“ in zwei Quellen, in der Wurzel einmal.
        assertThat(node(null, null).total()).isLessThan(node("wiki", null).total() + node("other", null).total());
        assertThat(node("wiki", null).total()).isLessThan(node("wiki", "DEV").total() + node("wiki", "OPS").total());
    }

    @Test
    void aSecondQuestionReadsNothingFromTheIndex() {
        assertEveryNodeMatches();
        keyReads.set(0);

        node(null, null);
        node("wiki", null);
        node("wiki", "OPS");

        assertThat(keyReads).hasValue(0);
    }

    @Test
    void aChangedPageRereadsOnlyItsSpaceAndPassesTheDifferenceUp() {
        assertEveryNodeMatches();

        page("wiki", "OPS", "3", "Die ZV meldet sich. Neu ist der Mahnlauf.");

        keyReads.set(0);
        node(null, null);
        // Ein Stapel für den einen Space; „wiki“/DEV und „other“ werden nicht neu gelesen.
        assertThat(keyReads).hasValue(1);
        assertEveryNodeMatches();
        // „Beleg“ verschwand aus OPS, bleibt aber über DEV in der Quelle.
        assertThat(terms.terms(CALLER, new TermQueries.TermSearch("wiki", "OPS", null, "beleg", true, 10, null))
                .terms()).isEmpty();
        assertThat(node("wiki", null)).isEqualTo(counted("wiki", null));
    }

    @Test
    void aRemovedPageAndAMovedPageLeaveTheirOldSpace() {
        assertEveryNodeMatches();

        remove(new PageKey("wiki", "3"));
        assertEveryNodeMatches();
        assertThat(node("wiki", "OPS").total()).isZero();

        page("wiki", "OPS", "2", "Die Buchung braucht einen Beleg. Der RSL ist gesetzt. Verschoben.");
        assertEveryNodeMatches();
        assertThat(node("wiki", "OPS").total()).isPositive();
    }

    @Test
    void aSpaceMarkedAsChangedIsRereadAsAWhole() {
        assertEveryNodeMatches();
        keyReads.set(0);

        cacheChanges.spaceChanged("wiki", "DEV");
        assertEveryNodeMatches();

        cacheChanges.sourceChanged("other");
        assertEveryNodeMatches();
    }

    @Test
    void aRemovedSourceLeavesTheRoot() {
        assertEveryNodeMatches();

        configured.remove("other");
        remove(new PageKey("other", "1"));

        assertThat(node(null, null)).isEqualTo(node("wiki", null)).isEqualTo(counted("wiki", null));
    }

    @Test
    void rebuildingByHandReadsTheSourceAgain() {
        assertEveryNodeMatches();
        keyReads.set(0);

        terms.rebuildTotals("wiki");
        assertEveryNodeMatches();
        int one = keyReads.get();
        assertThat(one).isPositive();

        terms.rebuildTotals(null);
        assertEveryNodeMatches();
        assertThat(keyReads.get()).isGreaterThan(one);
    }

    @Test
    void withoutSourceWideReadingTheEntriesAreCounted() {
        sourceWide = false;
        keyReads.set(0);

        TermQueries.TermTotals root = node(null, null);

        sourceWide = true;
        assertThat(root).isEqualTo(node(null, null));
    }

    @Test
    void aSelectionOfPagesRemembersItsCountsUntilOneOfItsPagesChanges() {
        PageSet tree = PageSet.ofKeys(List.of(new PageKey("wiki", "1"), new PageKey("wiki", "3")));
        assertThat(selection(tree)).isEqualTo(listed(tree));
        keyReads.set(0);

        assertThat(selection(tree)).isEqualTo(listed(tree));
        assertThat(keyReads).as("gemerkt, nichts gelesen").hasValue(0);

        // Eine Seite außerhalb der Auswahl lässt sie gelten.
        page("wiki", "DEV", "2", "Die Buchung braucht jetzt eine Mahnung.");
        selection(tree);
        assertThat(keyReads).hasValue(0);

        // Eine Seite der Auswahl ändert sich: veraltet, beim nächsten Fragen neu gezählt und wieder gemerkt.
        page("wiki", "OPS", "3", "Die ZV meldet sich. Neu ist der Mahnlauf.");
        assertThat(selection(tree)).isEqualTo(listed(tree));
        assertThat(keyReads.get()).isPositive();
        keyReads.set(0);
        selection(tree);
        assertThat(keyReads).hasValue(0);

        // Quelle als Ganzes geändert oder von Hand neu aufgebaut: ebenfalls veraltet.
        cacheChanges.sourceChanged("wiki");
        selection(tree);
        assertThat(keyReads.get()).isPositive();
        keyReads.set(0);
        terms.rebuildTotals(null);
        assertThat(selection(tree)).isEqualTo(listed(tree));
        assertThat(keyReads.get()).isPositive();
    }
}
