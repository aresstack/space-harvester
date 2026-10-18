package com.aresstack.spaceharvester.application.query;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
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
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PageQueries} gegen den {@link InMemoryPageCache}: Paging, Baum, Rechte, Generationen, Refresh. */
class PageQueriesTest {

    private static final SourceCatalog CATALOG = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(mock(HarvestSource.class)) : Optional.empty();
        }
    };

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-16T22:00:00Z"), ZoneOffset.UTC);
    /** Ohne Inhaltsabruf: refresh=true ist nicht möglich. */
    private static final PageFreshness NO_REFRESH = new PageFreshness(new SourceQueries(CATALOG, CLOCK), null, CLOCK);

    private final InMemoryPageCache cache = new InMemoryPageCache();

    /** 25 Seiten in DEV: zwei Wurzeln (p00, p01), die übrigen darunter, eine verwaiste (p24 → fehlt). */
    private List<PageSnapshot> devPages() {
        List<PageSnapshot> pages = new ArrayList<>();
        pages.add(page("wiki", "DEV", "p00", null));
        pages.add(page("wiki", "DEV", "p01", null));
        for (int i = 2; i < 24; i++) {
            pages.add(page("wiki", "DEV", String.format("p%02d", i), i % 2 == 0 ? "p00" : "p01"));
        }
        pages.add(page("wiki", "DEV", "p24", "gone"));
        return pages;
    }

    private PageQueries queries(AccessPolicy policy) {
        return new PageQueries(CATALOG, cache, new AccessGuard(policy), NO_REFRESH, 1000);
    }

    @Test
    void pagingReturnsEveryPageExactlyOnceAndTheSamePagesAsTheTree() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(ALLOW_ALL);

        List<String> listed = readAll(queries, 7);

        assertThat(listed).hasSize(25).doesNotHaveDuplicates().isSorted();
        SpaceTree tree = queries.tree(CALLER, "wiki", "DEV");
        assertThat(treeIds(tree.tree())).isEqualTo(new HashSet<>(listed));
        assertThat(tree.tree().roots()).extracting(PageTree.Node::pageId).containsExactly("p00", "p01", "p24");
        assertThat(tree.tree().anomalies()).containsExactly(
                new PageTree.Anomaly("p24", PageTree.Kind.MISSING_PARENT));
        assertThat(tree.generation().id()).isEqualTo("g1");
    }

    @Test
    void lastBatchIsCompleteWithoutCursor() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        PageListing listing = queries(ALLOW_ALL).pages(CALLER, "wiki", "DEV", null, 100);

        assertThat(listing.pages()).hasSize(25);
        assertThat(listing.complete()).isTrue();
        assertThat(listing.nextCursor()).isNull();
    }

    @Test
    void hiddenPagesAppearNowhereAndTheCursorNeverNamesThem() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        Set<String> hidden = Set.of("p00", "p12", "p14", "p16", "p18", "p20", "p22");
        // Space DEV ist freigegeben, einzelne Seiten darin nicht (wie später bei delegierter Prüfung je Seite, #24).
        PageQueries queries = queries((context, scope, kind) -> kind == AccessKind.METADATA
                && (scope.pageId() == null || !hidden.contains(scope.pageId()))
                ? AccessDecision.ALLOW : AccessDecision.DENY);

        List<String> cursors = new ArrayList<>();
        List<String> listed = new ArrayList<>();
        String cursor = null;
        do {
            PageListing listing = queries.pages(CALLER, "wiki", "DEV", cursor, 3);
            listing.pages().forEach(page -> listed.add(page.pageId()));
            cursor = listing.nextCursor();
            if (cursor != null) {
                cursors.add(cursor);
            }
        } while (cursor != null);

        assertThat(listed).hasSize(18).doesNotContainAnyElementsOf(hidden).contains("p02", "p24");
        for (String c : cursors) {
            String decoded = new String(Base64.getUrlDecoder().decode(c.substring(3)), StandardCharsets.UTF_8);
            assertThat(listed).contains(decoded.substring(decoded.lastIndexOf('\n') + 1));
        }
        SpaceTree tree = queries.tree(CALLER, "wiki", "DEV");
        assertThat(treeIds(tree.tree())).isEqualTo(new HashSet<>(listed));
        assertThat(tree.pages()).doesNotContainKey("p00");
        // Kinder der unsichtbaren Wurzel p00 werden abgelöste Wurzeln, ohne p00 zu nennen.
        assertThat(tree.tree().anomalies()).extracting(PageTree.Anomaly::pageId).contains("p02", "p04");
        assertThat(tree.tree().anomalies()).extracting(PageTree.Anomaly::kind)
                .containsOnly(PageTree.Kind.MISSING_PARENT);
    }

    @Test
    void hiddenSinglePageIsACacheMiss() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> queries((context, scope, kind) -> "p03".equals(scope.pageId())
                ? AccessDecision.DENY : AccessDecision.ALLOW).page(CALLER, "wiki", "p03", false))
                .isInstanceOf(CacheMissException.class);
        assertThat(queries(ALLOW_ALL).page(CALLER, "wiki", "p03", false).snapshot().key().pageId())
                .isEqualTo("p03");
    }

    @Test
    void listAndTreeShowTheCurrentHeaderEvenWhenItIsNewerThanThePayload() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageSnapshot stored = devPages().get(3);
        PageMetadata renamed = new PageMetadata("p03", stored.metadata().resourceId(), "Umbenannt", "DEV", "p00",
                "3", stored.metadata().modifiedAt(), null, PageMetadata.sorted(Map.of("confluence.labels", "neu")));
        cache.updateHeader("DEV", PageHeader.of("wiki", renamed, Instant.parse("2026-10-16T21:00:00Z")));
        cache.updateHeader("DEV", PageHeader.of("wiki", new PageMetadata("p99", "r", "Ohne Body", "DEV", "p00", "1",
                null, null, PageMetadata.sorted(Map.of())), Instant.parse("2026-10-16T21:00:00Z")));
        PageQueries queries = queries(ALLOW_ALL);

        ListedPage listed = queries.pages(CALLER, "wiki", "DEV", null, 100).pages().stream()
                .filter(page -> page.pageId().equals("p03")).findFirst().orElseThrow();
        assertThat(listed.header().metadata().title()).isEqualTo("Umbenannt");
        assertThat(listed.header().metadata().parentPageId()).isEqualTo("p00");
        assertThat(listed.rawSha256()).isEqualTo(stored.payload().rawSha256());
        SpaceTree tree = queries.tree(CALLER, "wiki", "DEV");
        assertThat(tree.pages().get("p03").header().metadata().title()).isEqualTo("Umbenannt");
        assertThat(tree.tree().roots().get(0).children()).extracting(PageTree.Node::pageId).contains("p03", "p99");
        assertThat(tree.pages().get("p99").rawSha256()).isNull();

        DeliveredPage page = queries.page(CALLER, "wiki", "p03", false);
        assertThat(page.header().metadata().title()).isEqualTo("Umbenannt");
        assertThat(page.snapshot().metadata().title()).isEqualTo("Seite p03");
        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "p99", false)).isInstanceOf(CacheMissException.class);
    }

    @Test
    void metadataGrantGivesListAndTreeButNoPayload() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries((context, scope, kind) -> kind == AccessKind.METADATA
                ? AccessDecision.ALLOW : AccessDecision.DENY);

        assertThat(queries.pages(CALLER, "wiki", "DEV", null, 100).pages()).hasSize(25);
        assertThat(queries.tree(CALLER, "wiki", "DEV").tree().nodeCount()).isEqualTo(25);
        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "p03", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void contentGrantForAnotherSpaceDoesNotRevealThePage() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries((context, scope, kind) -> scope.spaceKey() == null
                || scope.spaceKey().equals("OPS") ? AccessDecision.ALLOW : AccessDecision.DENY);

        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "p03", false)).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", null, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.tree(CALLER, "wiki", "DEV")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void missingCallerIsDenied() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(ALLOW_ALL);

        assertThatThrownBy(() -> queries.pages(null, "wiki", "DEV", null, 10))
                .isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.tree(null, "wiki", "DEV")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> queries.page(null, "wiki", "p03", false))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void cursorOfAReplacedGenerationIsRejected() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(ALLOW_ALL);
        String cursor = queries.pages(CALLER, "wiki", "DEV", null, 5).nextCursor();

        cache.publish("wiki", "DEV", confirmed("g2"), devPages());

        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", cursor, 5))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void cursorIsBoundToSourceAndSpace() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        cache.publish("wiki", "OPS", confirmed("g1"), List.of(page("wiki", "OPS", "o1", null)));
        PageQueries queries = queries(ALLOW_ALL);
        String cursor = queries.pages(CALLER, "wiki", "DEV", null, 5).nextCursor();

        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "OPS", cursor, 5)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", "kaputt", 5))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", "c1.!!", 5)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void limitIsChecked() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(ALLOW_ALL);

        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", null, 0)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", null, 101)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void unknownSourceAndUncachedSpaceAreDistinguished() {
        PageQueries queries = queries(ALLOW_ALL);

        assertThatThrownBy(() -> queries.pages(CALLER, "nope", "DEV", null, 5)).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.tree(CALLER, "nope", "DEV")).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.page(CALLER, "nope", "1", false)).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.pages(CALLER, "wiki", "DEV", null, 5)).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.tree(CALLER, "wiki", "DEV")).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "1", false)).isInstanceOf(CacheMissException.class);
    }

    @Test
    void movedPageIsFoundByPageIdAlone() {
        cache.publish("wiki", "OPS", confirmed("g7"), List.of(page("wiki", "OPS", "101", null)));

        DeliveredPage page = queries(ALLOW_ALL).page(CALLER, "wiki", "101", false);

        assertThat(page.snapshot().spaceKey()).isEqualTo("OPS");
        assertThat(page.generation().id()).isEqualTo("g7");
    }

    @Test
    void refreshNeverFallsBackToTheCache() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> queries(ALLOW_ALL).page(CALLER, "wiki", "p03", true))
                .isInstanceOf(RefreshUnavailableException.class);
    }

    // refresh=true gegen eine Quelle, die die Einzelmetadaten aus live liefert

    private final HarvestSource live = mock(HarvestSource.class);
    private final List<String> fetched = new ArrayList<>();
    private PageSnapshot fetchAnswer;

    private final List<PageSnapshot> stored = new ArrayList<>();
    private final List<String> expectedStates = new ArrayList<>();

    private PageQueries refreshing(AccessPolicy policy) {
        return refreshing(policy, PageCacheFill.NONE);
    }

    private PageQueries filling(AccessPolicy policy) {
        return refreshing(policy, PageCacheFill.of(key -> cache.page(key)
                .map(page -> page.snapshot().payload().rawSha256()).orElse(null), (snapshot, expected) -> {
                    expectedStates.add(expected);
                    return stored.add(snapshot);
                }));
    }

    private PageQueries refreshing(AccessPolicy policy, PageCacheFill fill) {
        when(live.id()).thenReturn("wiki");
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(live);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(live) : Optional.empty();
            }
        };
        PageFreshness freshness = new PageFreshness(new SourceQueries(catalog, CLOCK), (sourceId, pageId) -> {
            fetched.add(pageId);
            if (fetchAnswer == null) {
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.NOT_FOUND, "weg", null);
            }
            return fetchAnswer;
        }, CLOCK);
        return new PageQueries(catalog, cache, new AccessGuard(policy), freshness, fill, 1000);
    }

    private static PageMetadata version(PageSnapshot page, String space, String version) {
        PageMetadata metadata = page.metadata();
        return new PageMetadata(metadata.pageId(), metadata.resourceId(), metadata.title(), space,
                metadata.parentPageId(), version, metadata.modifiedAt(), metadata.url(), metadata.attributes());
    }

    @Test
    void refreshOfAnUnchangedPageDeliversTheCacheWithoutFetching() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageSnapshot stored = page("wiki", "DEV", "p03", "p01");
        when(live.pageMetadata("p03")).thenReturn(stored.metadata());

        DeliveredPage page = refreshing(ALLOW_ALL).page(CALLER, "wiki", "p03", true);

        assertThat(page.live()).isFalse();
        assertThat(page.refresh()).isEqualTo(PageFreshness.Outcome.CURRENT);
        assertThat(page.checkedAt()).isEqualTo(CLOCK.instant());
        assertThat(page.generation().id()).isEqualTo("g1");
        assertThat(page.snapshot().payload().rawSha256()).isEqualTo(stored.payload().rawSha256());
        assertThat(fetched).isEmpty();
    }

    @Test
    void refreshOfANewRevisionDeliversItLiveAndLeavesTheCacheAlone() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageSnapshot stored = page("wiki", "DEV", "p03", "p01");
        when(live.pageMetadata("p03")).thenReturn(version(stored, "DEV", "4"));
        fetchAnswer = page("wiki", "DEV", "p03", "p01", "{\"v\":4}".getBytes(StandardCharsets.UTF_8));

        DeliveredPage page = refreshing(ALLOW_ALL).page(CALLER, "wiki", "p03", true);

        assertThat(page.live()).isTrue();
        assertThat(page.refresh()).isEqualTo(PageFreshness.Outcome.CONTENT_CHANGED);
        assertThat(page.snapshot()).isSameAs(fetchAnswer);
        assertThat(page.generation().id()).isEqualTo("g1");
        assertThat(fetched).containsExactly("p03");
        assertThat(cache.page(stored.key()).orElseThrow().snapshot().payload().rawSha256())
                .isEqualTo(stored.payload().rawSha256());
    }

    @Test
    void refreshFindsPagesThatAreNotYetCached() {
        PageSnapshot fresh = page("wiki", "DEV", "p77", null);
        when(live.pageMetadata("p77")).thenReturn(fresh.metadata());
        fetchAnswer = fresh;

        DeliveredPage page = refreshing(ALLOW_ALL).page(CALLER, "wiki", "p77", true);

        assertThat(page.live()).isTrue();
        assertThat(page.generation()).isNull();
        assertThat(page.snapshot()).isSameAs(fresh);
    }

    @Test
    void aPageMissingInTheCacheIsLoadedStoredAndDeliveredWithoutRefresh() {
        PageSnapshot fresh = page("wiki", "DEV", "p77", null);
        when(live.pageMetadata("p77")).thenReturn(fresh.metadata());
        fetchAnswer = fresh;

        DeliveredPage page = filling(ALLOW_ALL).page(CALLER, "wiki", "p77", false);

        assertThat(page.snapshot()).isSameAs(fresh);
        assertThat(page.refresh()).isEqualTo(PageFreshness.Outcome.CONTENT_CHANGED);
        assertThat(fetched).containsExactly("p77");
        assertThat(stored).containsExactly(fresh);
    }

    @Test
    void aMissingPageTheCallerMayNotReadIsNeverFetched() {
        PageSnapshot fresh = page("wiki", "OPS", "p77", null);
        when(live.pageMetadata("p77")).thenReturn(fresh.metadata());
        fetchAnswer = fresh;
        AccessPolicy devOnly = (context, scope, kind) -> scope.spaceKey() == null || "DEV".equals(scope.spaceKey())
                ? AccessDecision.ALLOW : AccessDecision.DENY;

        assertThatThrownBy(() -> filling(devOnly).page(CALLER, "wiki", "p77", false))
                .isInstanceOf(CacheMissException.class);
        // Rechte an den Metadaten zuerst: kein Inhaltsabruf, nichts abgelegt.
        assertThat(fetched).isEmpty();
        assertThat(stored).isEmpty();
    }

    @Test
    void aCachedPageTheCallerMayNotReadIsNotFetchedEither() {
        PageSnapshot cached = page("wiki", "OPS", "p90", null);
        cache.publish("wiki", "OPS", confirmed("g1"), List.of(cached));
        when(live.pageMetadata("p90")).thenReturn(cached.metadata());
        AccessPolicy devOnly = (context, scope, kind) -> scope.spaceKey() == null || "DEV".equals(scope.spaceKey())
                ? AccessDecision.ALLOW : AccessDecision.DENY;

        assertThatThrownBy(() -> filling(devOnly).page(CALLER, "wiki", "p90", false))
                .isInstanceOf(CacheMissException.class);
        assertThat(fetched).isEmpty();
    }

    @Test
    void aPlainReadChecksTheVersionLiveAndFetchesANewerOne() {
        PageSnapshot old = page("wiki", "DEV", "p91", null);
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(old));
        when(live.pageMetadata("p91")).thenReturn(version(old, "DEV", "8"));
        fetchAnswer = page("wiki", "DEV", "p91", null, "{\"v\":8}".getBytes(StandardCharsets.UTF_8));

        DeliveredPage page = filling(ALLOW_ALL).page(CALLER, "wiki", "p91", false);

        assertThat(page.snapshot()).isSameAs(fetchAnswer);
        assertThat(fetched).containsExactly("p91");
        assertThat(stored).containsExactly(fetchAnswer);
    }

    @Test
    void aPlainReadOfTheSameVersionDeliversTheCacheAfterTheLiveCheck() {
        PageSnapshot cached = page("wiki", "DEV", "p92", null);
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(cached));
        when(live.pageMetadata("p92")).thenReturn(cached.metadata());

        DeliveredPage page = filling(ALLOW_ALL).page(CALLER, "wiki", "p92", false);

        assertThat(page.refresh()).isEqualTo(PageFreshness.Outcome.CURRENT);
        assertThat(page.live()).isFalse();
        assertThat(fetched).isEmpty();
    }

    @Test
    void anUnreachableSourceFallsBackToTheCacheOnlyForReadersAndNeverOnRefresh() {
        PageSnapshot cached = page("wiki", "OPS", "p93", null);
        cache.publish("wiki", "OPS", confirmed("g1"), List.of(cached));
        when(live.pageMetadata("p93")).thenThrow(
                new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "aus", null));
        AccessPolicy devOnly = (context, scope, kind) -> scope.spaceKey() == null || "DEV".equals(scope.spaceKey())
                ? AccessDecision.ALLOW : AccessDecision.DENY;

        assertThat(filling(ALLOW_ALL).page(CALLER, "wiki", "p93", false).refresh()).isNull();
        assertThatThrownBy(() -> filling(devOnly).page(CALLER, "wiki", "p93", false))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> filling(ALLOW_ALL).page(CALLER, "wiki", "p93", true))
                .isInstanceOf(SourceAccessException.class);
        assertThat(fetched).isEmpty();
    }

    @Test
    void anUnreachableSourceIsReportedForAMissingPageNotHiddenAsAMiss() {
        when(live.pageMetadata("p77")).thenThrow(
                new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "aus", null));

        assertThatThrownBy(() -> filling(ALLOW_ALL).page(CALLER, "wiki", "p77", false))
                .isInstanceOf(SourceAccessException.class);
        assertThat(stored).isEmpty();
    }

    @Test
    void refreshStoresANewRevision() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageSnapshot old = page("wiki", "DEV", "p03", "p01");
        when(live.pageMetadata("p03")).thenReturn(version(old, "DEV", "4"));
        fetchAnswer = page("wiki", "DEV", "p03", "p01", "{\"v\":4}".getBytes(StandardCharsets.UTF_8));

        filling(ALLOW_ALL).page(CALLER, "wiki", "p03", true);

        assertThat(stored).containsExactly(fetchAnswer);
        // Nur über den vor dem Abruf gelesenen Stand, damit ein langsamer Abruf keinen neueren überschreibt.
        assertThat(expectedStates).containsExactly(old.payload().rawSha256());
    }

    @Test
    void aFailingStoreStillDeliversThePage() {
        PageSnapshot fresh = page("wiki", "DEV", "p77", null);
        when(live.pageMetadata("p77")).thenReturn(fresh.metadata());
        fetchAnswer = fresh;
        PageQueries queries = refreshing(ALLOW_ALL, PageCacheFill.of(key -> null, (snapshot, expected) -> {
            throw new IllegalStateException("Cache geschlossen");
        }));

        assertThat(queries.page(CALLER, "wiki", "p77", false).snapshot()).isSameAs(fresh);
    }

    @Test
    void withoutContentFetchAMissingPageStaysAMiss() {
        assertThatThrownBy(() -> new PageQueries(CATALOG, cache, new AccessGuard(ALLOW_ALL), NO_REFRESH, 1000)
                .page(CALLER, "wiki", "p77", false)).isInstanceOf(CacheMissException.class);
    }

    @Test
    void refreshChecksRightsInTheSpaceTheSourceReports() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageSnapshot stored = page("wiki", "DEV", "p03", "p01");
        // In der Quelle nach OPS verschoben, der Aufrufer darf OPS nicht lesen.
        when(live.pageMetadata("p03")).thenReturn(version(stored, "OPS", "3"));
        AccessPolicy devOnly = (context, scope, kind) -> scope.spaceKey() == null || "DEV".equals(scope.spaceKey())
                ? AccessDecision.ALLOW : AccessDecision.DENY;

        assertThatThrownBy(() -> refreshing(devOnly).page(CALLER, "wiki", "p03", true))
                .isInstanceOf(CacheMissException.class);
    }

    @Test
    void pagesTheSourceNoLongerKnowsAreCacheMisses() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        when(live.pageMetadata("p03")).thenThrow(
                new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "weg", null));
        PageSnapshot stored = page("wiki", "DEV", "p04", "p00");
        // Metadaten noch da, Inhalt beim Abruf schon weg.
        when(live.pageMetadata("p04")).thenReturn(version(stored, "DEV", "9"));
        PageQueries queries = refreshing(ALLOW_ALL);

        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "p03", true)).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.page(CALLER, "wiki", "p04", true)).isInstanceOf(CacheMissException.class);
    }

    @Test
    void sourceFailuresAreNeverReplacedByTheCache() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        when(live.pageMetadata("p03")).thenThrow(
                new SourceAccessException("wiki", SourceAccessException.Reason.UNAVAILABLE, "aus", null));

        assertThatThrownBy(() -> refreshing(ALLOW_ALL).page(CALLER, "wiki", "p03", true))
                .isInstanceOf(SourceAccessException.class);
    }

    @Test
    void treeLargerThanTheLimitIsRefused() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> new PageQueries(CATALOG, cache, new AccessGuard(ALLOW_ALL), NO_REFRESH, 24)
                .tree(CALLER, "wiki", "DEV")).isInstanceOf(TreeTooLargeException.class);
        assertThat(new PageQueries(CATALOG, cache, new AccessGuard(ALLOW_ALL), NO_REFRESH, 25).tree(CALLER, "wiki", "DEV")
                .tree().nodeCount()).isEqualTo(25);
    }

    @Test
    void treeDeeperThanTheLimitIsRefused() {
        List<PageSnapshot> chain = new ArrayList<>();
        for (int i = 0; i <= PageQueries.MAX_TREE_DEPTH; i++) {
            chain.add(page("wiki", "DEV", String.format("c%04d", i), i == 0 ? null : String.format("c%04d", i - 1)));
        }
        cache.publish("wiki", "DEV", confirmed("g1"), chain);

        assertThatThrownBy(() -> queries(ALLOW_ALL).tree(CALLER, "wiki", "DEV"))
                .isInstanceOf(TreeTooLargeException.class);
    }

    @Test
    void treeReadsInBatchesAcrossLargeSpaces() {
        List<PageSnapshot> pages = new ArrayList<>();
        pages.add(page("wiki", "BIG", "root", null));
        for (int i = 0; i < 2500; i++) {
            pages.add(page("wiki", "BIG", String.format("n%05d", i), "root"));
        }
        cache.publish("wiki", "BIG", confirmed("g1"), pages);

        SpaceTree tree = new PageQueries(CATALOG, cache, new AccessGuard(ALLOW_ALL), NO_REFRESH, 5000).tree(CALLER, "wiki", "BIG");

        assertThat(tree.tree().nodeCount()).isEqualTo(2501);
        assertThat(tree.tree().roots()).singleElement().satisfies(root ->
                assertThat(root.children()).hasSize(2500));
    }

    private static List<String> readAll(PageQueries queries, int limit) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            PageListing listing = queries.pages(CALLER, "wiki", "DEV", cursor, limit);
            listing.pages().forEach(page -> ids.add(page.pageId()));
            cursor = listing.nextCursor();
            assertThat(listing.complete()).isEqualTo(cursor == null);
        } while (cursor != null);
        return ids;
    }

    private static Set<String> treeIds(PageTree tree) {
        Set<String> ids = new HashSet<>();
        List<PageTree.Node> open = new ArrayList<>(tree.roots());
        while (!open.isEmpty()) {
            PageTree.Node node = open.remove(open.size() - 1);
            assertThat(ids.add(node.pageId())).as("einmal im Baum: %s", node.pageId()).isTrue();
            open.addAll(node.children());
        }
        return ids;
    }
}
