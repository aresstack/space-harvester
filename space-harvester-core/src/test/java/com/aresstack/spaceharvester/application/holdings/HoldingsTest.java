package com.aresstack.spaceharvester.application.holdings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.CatalogPage;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.LockedPage;
import com.aresstack.spaceharvester.application.holdings.HoldingsCatalog.Stock;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.TreeTooLargeException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Bestand (ADR 0027, Abschnitt 4): Zustände, Zähler je Teilbaum, Teilübersicht, Rechte und gebündelte Meldungen. */
class HoldingsTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("app");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final CacheGeneration CONFIRMED = new CacheGeneration("g2", CacheState.STALE,
            Instant.parse("2026-11-13T10:00:00Z"), Instant.parse("2026-11-13T10:00:00Z"));
    private static final CacheGeneration INITIAL = new CacheGeneration("g0", CacheState.INCOMPLETE, null, null);
    private static final SourceCatalog SOURCES = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(mock(HarvestSource.class)) : Optional.empty();
        }
    };

    private final Map<String, Stock> stocks = new HashMap<>();
    private final HoldingsCatalog catalog = new HoldingsCatalog() {
        @Override
        public Optional<Stock> stock(String sourceId, String spaceKey) {
            return Optional.ofNullable(stocks.get(sourceId + "/" + spaceKey));
        }

        @Override
        public List<String> spaceKeys(String sourceId) {
            return stocks.keySet().stream().filter(key -> key.startsWith(sourceId + "/"))
                    .map(key -> key.substring(sourceId.length() + 1)).sorted().toList();
        }

        @Override
        public Optional<String> spaceOf(String sourceId, String pageId) {
            spaceLookups++;
            return stocks.entrySet().stream().filter(entry -> entry.getKey().startsWith(sourceId + "/"))
                    .filter(entry -> entry.getValue().pages().stream().anyMatch(page -> page.pageId().equals(pageId)))
                    .map(entry -> entry.getKey().substring(sourceId.length() + 1)).findFirst();
        }
    };
    private int spaceLookups;
    private final List<Holdings> opened = new ArrayList<>();

    @AfterEach
    void close() {
        opened.forEach(Holdings::close);
    }

    /**
     * DEV: 1 (lokal) mit 2 (nur Quelle) und 3 (Kopfdaten neuer als Inhalt), unter 3 die laut Generation veraltete 4 und
     * die gesperrte 6; 5 nennt eine Elternseite, die der Cache nicht kennt; 7 ist gesperrt ohne bekannte Elternseite.
     */
    private void dev() {
        stocks.put("wiki/DEV", new Stock(CONFIRMED, List.of(
                new CatalogPage("1", null, true, "3", "3"),
                new CatalogPage("2", "1", false, "1", null),
                new CatalogPage("3", "1", true, "4", "3"),
                new CatalogPage("4", "3", true, "2", "2"),
                new CatalogPage("5", "gone", true, null, "1")),
                Set.of("4"), List.of(new LockedPage("6", "3"), new LockedPage("7", null))));
    }

    private Holdings holdings(AccessPolicy policy, int maxTreeNodes) {
        Holdings holdings = new Holdings(SOURCES, catalog, new AccessGuard(policy), maxTreeNodes, Duration.ofMillis(30));
        opened.add(holdings);
        return holdings;
    }

    @Test
    void statesAndSubtreeCountsFollowTheCatalogTree() {
        dev();

        SpaceHoldings overview = holdings(ALLOW_ALL, 100).overview(CALLER, "wiki", "DEV");

        assertThat(overview.pages()).extractingFromEntries(entry -> entry.getKey() + ":" + entry.getValue().state())
                .containsExactly("1:LOCAL", "2:REMOTE", "3:OUTDATED", "4:OUTDATED", "5:LOCAL", "6:LOCKED",
                        "7:LOCKED");
        assertThat(overview.pages().get("1").subtree()).isEqualTo(new HoldingCounts(5, 1, 2, 1));
        assertThat(overview.pages().get("3").subtree()).isEqualTo(new HoldingCounts(3, 0, 2, 1));
        assertThat(overview.pages().get("2").subtree()).isEqualTo(new HoldingCounts(1, 0, 0, 0));
        assertThat(overview.pages().get("6").parentPageId()).isEqualTo("3");
        assertThat(overview.pages().get("5").parentPageId()).as("Elternseite fehlt im Cache: Wurzel").isNull();
        assertThat(overview.space().counts()).isEqualTo(new HoldingCounts(7, 2, 2, 2));
        assertThat(overview.space().generation()).isEqualTo(CONFIRMED);
        assertThat(overview.space().inventoried()).isTrue();
        assertThat(overview.space().asOf()).isEqualTo(CONFIRMED.syncedAt());
    }

    @Test
    void aPartialOverviewNamesThePathUpwardsAndPagesThatAreGone() {
        dev();

        SpaceHoldings partial = holdings(ALLOW_ALL, 100).overview(CALLER, "wiki", "DEV", List.of("4", "99"));

        assertThat(partial.pages()).containsOnlyKeys("1", "3", "4", "99");
        assertThat(partial.pages().get("99").state()).isEqualTo(HoldingState.REMOVED);
        assertThat(partial.pages().get("99").subtree()).isEqualTo(HoldingCounts.ZERO);
        assertThat(partial.pages().get("1").subtree()).isEqualTo(new HoldingCounts(5, 1, 2, 1));
        assertThat(partial.space().counts()).isEqualTo(new HoldingCounts(7, 2, 2, 2));
    }

    @Test
    void pagesTheCallerMayNotSeeAreLeftOutAsInThePageTree() {
        dev();
        AccessPolicy withoutThree = (context, scope, kind) -> "3".equals(scope.pageId()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;

        SpaceHoldings overview = holdings(withoutThree, 100).overview(CALLER, "wiki", "DEV");

        assertThat(overview.pages()).doesNotContainKey("3");
        assertThat(overview.pages().get("1").subtree()).isEqualTo(new HoldingCounts(2, 1, 0, 0));
        assertThat(overview.pages().get("4").parentPageId()).isNull();
        assertThat(overview.space().counts()).isEqualTo(new HoldingCounts(6, 2, 1, 2));
    }

    @Test
    void aSpaceWithoutInventoryIsMarkedAndAnUnknownOneIsEmpty() {
        stocks.put("wiki/OPS", new Stock(INITIAL, List.of(new CatalogPage("9", "8", true, "1", "1")), Set.of(),
                List.of()));
        Holdings holdings = holdings(ALLOW_ALL, 100);

        SpaceSummary known = holdings.overview(CALLER, "wiki", "OPS").space();
        SpaceHoldings unknown = holdings.overview(CALLER, "wiki", "NONE");

        assertThat(known.inventoried()).as("keine Inventur, nicht nichts lokal").isFalse();
        assertThat(known.counts()).isEqualTo(new HoldingCounts(1, 1, 0, 0));
        assertThat(unknown.space().generation()).isNull();
        assertThat(unknown.space().inventoried()).isFalse();
        assertThat(unknown.space().counts()).isEqualTo(HoldingCounts.ZERO);
        assertThat(unknown.pages()).isEmpty();
    }

    @Test
    void anOverviewAboveTheTreeLimitIsRefusedButTheSpaceRowsStillCount() {
        dev();
        stocks.put("wiki/OPS", new Stock(INITIAL, List.of(new CatalogPage("9", null, false, "1", null)), Set.of(),
                List.of()));
        AccessPolicy withoutOps = (context, scope, kind) -> "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        Holdings holdings = holdings(withoutOps, 6);

        assertThatThrownBy(() -> holdings.overview(CALLER, "wiki", "DEV")).isInstanceOf(TreeTooLargeException.class);
        assertThat(holdings.spaces(CALLER, "wiki")).extracting(SpaceSummary::spaceKey, SpaceSummary::counts)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DEV", new HoldingCounts(7, 2, 2, 2)));
        assertThatThrownBy(() -> holdings.spaces(CALLER, "nirgends")).isInstanceOf(UnknownSourceException.class);
    }

    @Test
    void changesArriveBundledPerSpaceAtTheMatchingSubscribersOnly() throws InterruptedException {
        Holdings holdings = holdings(ALLOW_ALL, 100);
        Recorder dev = new Recorder();
        Recorder ops = new Recorder();
        Recorder all = new Recorder();
        holdings.subscribe(CALLER, "wiki", "DEV", dev);
        holdings.subscribe(CALLER, "wiki", "OPS", ops);
        holdings.subscribe(CALLER, "wiki", "", all);

        holdings.pagesChanged("wiki", "DEV", List.of("1"));
        holdings.pagesChanged("wiki", "DEV", List.of("2", "1"));

        assertThat(dev.next()).isEqualTo("changed:wiki/DEV:[1, 2]");
        assertThat(all.next()).isEqualTo("changed:wiki/DEV:[1, 2]");
        assertThat(dev.none()).as("eine Zustellung für beide Vormerkungen").isTrue();
        assertThat(ops.none()).isTrue();
    }

    @Test
    void theStartOfLoadingComesBeforeItsEndInTheSameDelivery() throws InterruptedException {
        Holdings holdings = holdings(ALLOW_ALL, 100);
        Recorder recorder = new Recorder();
        holdings.subscribe(CALLER, "wiki", "DEV", recorder);

        holdings.loading("wiki", "DEV", List.of("1"));
        holdings.pagesChanged("wiki", "DEV", List.of("1"));

        assertThat(recorder.next()).isEqualTo("loading:wiki/DEV:[1]");
        assertThat(recorder.next()).isEqualTo("changed:wiki/DEV:[1]");
    }

    @Test
    void aContentFetchReportsLoadingInTheSpaceOfThePageAndItsEndEvenWithoutAWrite() throws InterruptedException {
        dev();
        Holdings holdings = holdings(ALLOW_ALL, 100);
        assertThat(holdings.started(new PageKey("wiki", "2")))
                .as("ohne Empfänger").isNotNull();
        assertThat(spaceLookups).as("ohne Empfänger kein Lesen im Katalog").isZero();
        Recorder recorder = new Recorder();
        holdings.subscribe(CALLER, "wiki", "DEV", recorder);

        Runnable ended = holdings.started(new PageKey("wiki", "2"));
        assertThat(recorder.next()).isEqualTo("loading:wiki/DEV:[2]");
        ended.run();
        assertThat(recorder.next()).isEqualTo("changed:wiki/DEV:[2]");

        holdings.started(new PageKey("wiki", "unbekannt")).run();
        assertThat(recorder.none()).as("Seite außerhalb des Katalogs").isTrue();
    }

    @Test
    void aNewGenerationOrMoreThanAThousandPagesBecomeOneSpaceChange() throws InterruptedException {
        Holdings holdings = holdings(ALLOW_ALL, 100);
        Recorder recorder = new Recorder();
        holdings.subscribe(CALLER, "wiki", "DEV", recorder);
        List<String> many = new ArrayList<>();
        for (int i = 0; i <= Holdings.MAX_PAGES_PER_MESSAGE; i++) {
            many.add("p" + i);
        }

        holdings.pagesChanged("wiki", "DEV", many);
        assertThat(recorder.next()).isEqualTo("space:wiki/DEV");

        holdings.pagesChanged("wiki", "DEV", List.of("1"));
        holdings.spaceChanged("wiki", "DEV");
        assertThat(recorder.next()).isEqualTo("space:wiki/DEV");
        assertThat(recorder.none()).isTrue();
    }

    @Test
    void aRemovedSourceReachesEverySubscriberOfThatSource() throws InterruptedException {
        Holdings holdings = holdings(ALLOW_ALL, 100);
        Recorder dev = new Recorder();
        Recorder all = new Recorder();
        holdings.subscribe(CALLER, "wiki", "DEV", dev);
        holdings.subscribe(CALLER, "wiki", null, all);

        holdings.sourceChanged("wiki");

        assertThat(dev.next()).isEqualTo("space:wiki/DEV");
        assertThat(all.next()).isEqualTo("space:wiki/");
    }

    @Test
    void hiddenPagesAreNotNamedAndAClosedSubscriptionGetsNothing() throws InterruptedException {
        AccessPolicy withoutThree = (context, scope, kind) -> "3".equals(scope.pageId()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        Holdings holdings = holdings(withoutThree, 100);
        Recorder recorder = new Recorder();
        Subscription subscription = holdings.subscribe(CALLER, "wiki", "DEV", recorder);

        holdings.pagesChanged("wiki", "DEV", List.of("3"));
        assertThat(recorder.none()).isTrue();
        holdings.pagesChanged("wiki", "DEV", List.of("3", "1"));
        assertThat(recorder.next()).isEqualTo("changed:wiki/DEV:[1]");

        subscription.close();
        holdings.pagesChanged("wiki", "DEV", List.of("1"));
        assertThat(recorder.none()).isTrue();
    }

    @Test
    void subscribingNeedsTheRightToSeeTheSpace() {
        AccessPolicy withoutOps = (context, scope, kind) -> "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        Holdings holdings = holdings(withoutOps, 100);

        assertThatThrownBy(() -> holdings.subscribe(CALLER, "wiki", "OPS", new Recorder()))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> holdings.subscribe(CALLER, "nirgends", "DEV", new Recorder()))
                .isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> holdings.overview(CALLER, "wiki", "OPS")).isInstanceOf(AccessDeniedException.class);
    }

    /** Sammelt Meldungen als Text; Seiten sortiert. */
    private static final class Recorder implements HoldingsListener {
        private final BlockingQueue<String> seen = new LinkedBlockingQueue<>();

        @Override
        public void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
            seen.add("changed:" + sourceId + "/" + spaceKey + ":" + new TreeSet<>(pageIds));
        }

        @Override
        public void spaceChanged(String sourceId, String spaceKey) {
            seen.add("space:" + sourceId + "/" + spaceKey);
        }

        @Override
        public void loading(String sourceId, String spaceKey, Set<String> pageIds) {
            seen.add("loading:" + sourceId + "/" + spaceKey + ":" + new TreeSet<>(pageIds));
        }

        String next() throws InterruptedException {
            String next = seen.poll(5, TimeUnit.SECONDS);
            assertThat(next).as("Meldung").isNotNull();
            return next;
        }

        boolean none() throws InterruptedException {
            return seen.poll(300, TimeUnit.MILLISECONDS) == null;
        }
    }
}
