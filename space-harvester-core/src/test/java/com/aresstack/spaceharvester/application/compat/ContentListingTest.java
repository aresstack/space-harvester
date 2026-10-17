package com.aresstack.spaceharvester.application.compat;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.query.PageSearch;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** {@link ContentListing} gegen Suche und Seitencache im Speicher: Offset, Grenzen, Rechte, Vollständigkeit. */
class ContentListingTest {

    private static final HarvestSource WIKI = (HarvestSource) Proxy.newProxyInstance(
            HarvestSource.class.getClassLoader(), new Class<?>[]{HarvestSource.class}, (proxy, method, args) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    private static final SourceCatalog CATALOG = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return List.of(WIKI);
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return "wiki".equals(sourceId) ? Optional.of(WIKI) : Optional.empty();
        }
    };
    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final SearchIndexState STATE = new SearchIndexState("s1", Instant.parse("2026-10-16T20:10:00Z"));

    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final InMemoryPageCache cache = new InMemoryPageCache();

    private static PageSnapshot page(String pageId, String parent, String title) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, "DEV", parent, "1",
                null, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload(("{\"id\":\"" + pageId + "\"}")
                .getBytes(StandardCharsets.UTF_8), "application/json", "confluence-rest-content+json"),
                Instant.parse("2026-10-16T20:00:00Z"));
    }

    private void publish(CacheGeneration generation, List<PageSnapshot> pages) {
        cache.publish("wiki", "DEV", generation, pages);
        index.publish("wiki", STATE, pages.stream().map(page -> entry(page, generation)).toList());
    }

    private ContentListing listing(AccessPolicy policy) {
        return listing(policy, ContentListing.DEFAULT_MAX_RESPONSE_BYTES);
    }

    private ContentListing listing(AccessPolicy policy, long maxResponseBytes) {
        AccessGuard guard = new AccessGuard(policy);
        return new ContentListing(new PageSearch(CATALOG, index, guard), new PageQueries(CATALOG, cache, guard,
                new PageFreshness(new SourceQueries(CATALOG, Clock.systemUTC()), null, Clock.systemUTC()), 1000),
                guard, maxResponseBytes);
    }

    private static List<String> ids(ContentListing.Slice slice) {
        return slice.pages().stream().map(page -> page.header().key().pageId()).toList();
    }

    @Test
    void startAndLimitWalkThePagesInPageIdOrder() {
        List<PageSnapshot> pages = new ArrayList<>();
        IntStream.range(0, 230).forEach(i -> pages.add(page(String.format("%04d", i), null, "Seite " + i)));
        publish(confirmed("g1"), pages);
        ContentListing listing = listing(ALLOW_ALL);

        ContentListing.Slice first = listing.space(CALLER, "wiki", "DEV", null, 0, 25);
        assertThat(ids(first)).hasSize(25).first().isEqualTo("0000");
        assertThat(first.more()).isTrue();

        ContentListing.Slice middle = listing.space(CALLER, "wiki", "DEV", null, 150, 100);
        assertThat(ids(middle)).hasSize(80).first().isEqualTo("0150");
        assertThat(middle.more()).isFalse();

        ContentListing.Slice capped = listing.space(CALLER, "wiki", "DEV", null, 0, 500);
        assertThat(capped.limit()).isEqualTo(ContentListing.MAX_LIMIT);
        assertThat(capped.pages()).hasSize(ContentListing.MAX_LIMIT);
        assertThat(capped.more()).isTrue();

        assertThat(listing.space(CALLER, "wiki", "DEV", null, 230, 25).pages()).isEmpty();
        assertThat(listing.space(CALLER, "wiki", "DEV", null, 229, 25).more()).isFalse();
    }

    @Test
    void theByteBudgetEndsTheSliceEarlyAndNextCoversTheRest() {
        List<PageSnapshot> pages = new ArrayList<>();
        IntStream.range(0, 7).forEach(i -> pages.add(page(String.format("%04d", i), null, "Seite " + i)));
        publish(confirmed("g1"), pages);
        // Jede Payload {"id":"000n"} hat 13 Bytes: zwei passen in 30, die dritte nicht mehr.
        ContentListing listing = listing(ALLOW_ALL, 30);

        List<String> walked = new ArrayList<>();
        int start = 0;
        ContentListing.Slice slice;
        do {
            slice = listing.space(CALLER, "wiki", "DEV", null, start, 100);
            assertThat(slice.pages()).hasSizeBetween(1, 2);
            assertThat(slice.limit()).isEqualTo(100);
            walked.addAll(ids(slice));
            start += slice.pages().size();
        } while (slice.more());

        assertThat(walked).containsExactly("0000", "0001", "0002", "0003", "0004", "0005", "0006");
    }

    @Test
    void aSinglePageLargerThanTheBudgetIsStillDelivered() {
        publish(confirmed("g1"), List.of(page("1", null, "a"), page("2", null, "b")));

        ContentListing.Slice slice = listing(ALLOW_ALL, 5).space(CALLER, "wiki", "DEV", null, 0, 25);

        assertThat(ids(slice)).containsExactly("1");
        assertThat(slice.more()).isTrue();
    }

    @Test
    void titleMatchesExactly() {
        publish(confirmed("g1"), List.of(page("1", null, "Betrieb"), page("2", null, "Betrieb und Wartung"),
                page("3", null, "betrieb")));

        assertThat(ids(listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", "Betrieb", 0, 25))).containsExactly("1");
    }

    @Test
    void childrenAreTheDirectChildrenOfThePage() {
        publish(confirmed("g1"), List.of(page("1", null, "Wurzel"), page("2", "1", "Kind"), page("3", "2", "Enkel"),
                page("4", "1", "Kind 2")));

        assertThat(ids(listing(ALLOW_ALL).children(CALLER, "wiki", "1", 0, 25))).containsExactly("2", "4");
        assertThat(ids(listing(ALLOW_ALL).children(CALLER, "wiki", "1", 1, 25))).containsExactly("4");
        assertThatThrownBy(() -> listing(ALLOW_ALL).children(CALLER, "wiki", "99", 0, 25))
                .isInstanceOf(CacheMissException.class);
    }

    @Test
    void pagesWithoutContentGrantAreLeftOutAndNotCounted() {
        publish(confirmed("g1"), List.of(page("1", null, "a"), page("2", null, "geheim"), page("3", null, "c")));
        AccessPolicy noSecret = (context, scope, kind) -> kind == AccessKind.CONTENT && "2".equals(scope.pageId())
                ? AccessDecision.DENY : AccessDecision.ALLOW;

        ContentListing.Slice slice = listing(noSecret).space(CALLER, "wiki", "DEV", null, 1, 25);

        assertThat(ids(slice)).containsExactly("3");
    }

    @Test
    void anIncompleteSpaceOrAPageWithoutPayloadIsRefused() {
        publish(new CacheGeneration("g0", CacheState.INCOMPLETE, null, null), List.of(page("1", null, "a")));
        assertThatThrownBy(() -> listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, 0, 25))
                .isInstanceOf(ContentListing.IncompleteListingException.class);

        PageSnapshot full = page("1", null, "a");
        PageSnapshot headerOnly = page("2", null, "b");
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(full));
        index.publish("wiki", STATE, List.of(entry(full, confirmed("g1")),
                new InMemorySearchIndex.Entry(new ListedPage(headerOnly.header(), null), confirmed("g1"))));
        assertThatThrownBy(() -> listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, 0, 25))
                .isInstanceOf(ContentListing.IncompleteListingException.class);
    }

    @Test
    void negativeStartOrLimitIsInvalid() {
        publish(confirmed("g1"), List.of(page("1", null, "a")));
        assertThatThrownBy(() -> listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, -1, 25))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, 0, -1))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void startBeyondTheCapIsInvalid() {
        publish(confirmed("g1"), List.of(page("1", null, "a")));
        assertThatThrownBy(() -> listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, ContentListing.MAX_START + 1, 25))
                .isInstanceOf(InvalidQueryException.class);
        assertThat(listing(ALLOW_ALL).space(CALLER, "wiki", "DEV", null, ContentListing.MAX_START, 25).pages()).isEmpty();
    }
}
