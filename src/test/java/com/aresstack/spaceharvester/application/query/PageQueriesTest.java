package com.aresstack.spaceharvester.application.query;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PageQueries} gegen den {@link InMemoryPageCache}: Paging, Baum, Sichtbarkeit, Generationen, Refresh. */
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

    private PageQueries queries(PageVisibility visibility) {
        return new PageQueries(CATALOG, cache, visibility, 1000);
    }

    @Test
    void pagingReturnsEveryPageExactlyOnceAndTheSamePagesAsTheTree() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(PageVisibility.unrestricted());

        List<String> listed = readAll(queries, 7);

        assertThat(listed).hasSize(25).doesNotHaveDuplicates().isSorted();
        SpaceTree tree = queries.tree("wiki", "DEV");
        assertThat(treeIds(tree.tree())).isEqualTo(new HashSet<>(listed));
        assertThat(tree.tree().roots()).extracting(PageTree.Node::pageId).containsExactly("p00", "p01", "p24");
        assertThat(tree.tree().anomalies()).containsExactly(
                new PageTree.Anomaly("p24", PageTree.Kind.MISSING_PARENT));
        assertThat(tree.generation().id()).isEqualTo("g1");
    }

    @Test
    void lastBatchIsCompleteWithoutCursor() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        PageListing listing = queries(PageVisibility.unrestricted()).pages("wiki", "DEV", null, 100);

        assertThat(listing.pages()).hasSize(25);
        assertThat(listing.complete()).isTrue();
        assertThat(listing.nextCursor()).isNull();
    }

    @Test
    void hiddenPagesAppearNowhereAndTheCursorNeverNamesThem() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        Set<String> hidden = Set.of("p00", "p12", "p14", "p16", "p18", "p20", "p22");
        PageVisibility visibility = page -> !hidden.contains(page.pageId());
        PageQueries queries = queries(visibility);

        List<String> cursors = new ArrayList<>();
        List<String> listed = new ArrayList<>();
        String cursor = null;
        do {
            PageListing listing = queries.pages("wiki", "DEV", cursor, 3);
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
        SpaceTree tree = queries.tree("wiki", "DEV");
        assertThat(treeIds(tree.tree())).isEqualTo(new HashSet<>(listed));
        assertThat(tree.headers()).doesNotContainKey("p00");
        // Kinder der unsichtbaren Wurzel p00 werden abgelöste Wurzeln, ohne p00 zu nennen.
        assertThat(tree.tree().anomalies()).extracting(PageTree.Anomaly::pageId).contains("p02", "p04");
        assertThat(tree.tree().anomalies()).extracting(PageTree.Anomaly::kind)
                .containsOnly(PageTree.Kind.MISSING_PARENT);
    }

    @Test
    void hiddenSinglePageIsACacheMiss() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> queries(page -> !page.pageId().equals("p03")).page("wiki", "p03", false))
                .isInstanceOf(CacheMissException.class);
        assertThat(queries(PageVisibility.unrestricted()).page("wiki", "p03", false).snapshot().key().pageId())
                .isEqualTo("p03");
    }

    @Test
    void cursorOfAReplacedGenerationIsRejected() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(PageVisibility.unrestricted());
        String cursor = queries.pages("wiki", "DEV", null, 5).nextCursor();

        cache.publish("wiki", "DEV", confirmed("g2"), devPages());

        assertThatThrownBy(() -> queries.pages("wiki", "DEV", cursor, 5))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void cursorIsBoundToSourceAndSpace() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        cache.publish("wiki", "OPS", confirmed("g1"), List.of(page("wiki", "OPS", "o1", null)));
        PageQueries queries = queries(PageVisibility.unrestricted());
        String cursor = queries.pages("wiki", "DEV", null, 5).nextCursor();

        assertThatThrownBy(() -> queries.pages("wiki", "OPS", cursor, 5)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages("wiki", "DEV", "kaputt", 5))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages("wiki", "DEV", "c1.!!", 5)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void limitIsChecked() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());
        PageQueries queries = queries(PageVisibility.unrestricted());

        assertThatThrownBy(() -> queries.pages("wiki", "DEV", null, 0)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> queries.pages("wiki", "DEV", null, 101)).isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void unknownSourceAndUncachedSpaceAreDistinguished() {
        PageQueries queries = queries(PageVisibility.unrestricted());

        assertThatThrownBy(() -> queries.pages("nope", "DEV", null, 5)).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.tree("nope", "DEV")).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.page("nope", "1", false)).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> queries.pages("wiki", "DEV", null, 5)).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.tree("wiki", "DEV")).isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> queries.page("wiki", "1", false)).isInstanceOf(CacheMissException.class);
    }

    @Test
    void movedPageIsFoundByPageIdAlone() {
        cache.publish("wiki", "OPS", confirmed("g7"), List.of(page("wiki", "OPS", "101", null)));

        CachedPage page = queries(PageVisibility.unrestricted()).page("wiki", "101", false);

        assertThat(page.snapshot().spaceKey()).isEqualTo("OPS");
        assertThat(page.generation().id()).isEqualTo("g7");
    }

    @Test
    void refreshNeverFallsBackToTheCache() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> queries(PageVisibility.unrestricted()).page("wiki", "p03", true))
                .isInstanceOf(RefreshUnavailableException.class);
    }

    @Test
    void treeLargerThanTheLimitIsRefused() {
        cache.publish("wiki", "DEV", confirmed("g1"), devPages());

        assertThatThrownBy(() -> new PageQueries(CATALOG, cache, PageVisibility.unrestricted(), 24)
                .tree("wiki", "DEV")).isInstanceOf(TreeTooLargeException.class);
        assertThat(new PageQueries(CATALOG, cache, PageVisibility.unrestricted(), 25).tree("wiki", "DEV")
                .tree().nodeCount()).isEqualTo(25);
    }

    @Test
    void treeDeeperThanTheLimitIsRefused() {
        List<PageSnapshot> chain = new ArrayList<>();
        for (int i = 0; i <= PageQueries.MAX_TREE_DEPTH; i++) {
            chain.add(page("wiki", "DEV", String.format("c%04d", i), i == 0 ? null : String.format("c%04d", i - 1)));
        }
        cache.publish("wiki", "DEV", confirmed("g1"), chain);

        assertThatThrownBy(() -> queries(PageVisibility.unrestricted()).tree("wiki", "DEV"))
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

        SpaceTree tree = new PageQueries(CATALOG, cache, PageVisibility.unrestricted(), 5000).tree("wiki", "BIG");

        assertThat(tree.tree().nodeCount()).isEqualTo(2501);
        assertThat(tree.tree().roots()).singleElement().satisfies(root ->
                assertThat(root.children()).hasSize(2500));
    }

    private static List<String> readAll(PageQueries queries, int limit) {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        do {
            PageListing listing = queries.pages("wiki", "DEV", cursor, limit);
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
