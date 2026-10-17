package com.aresstack.spaceharvester.application.query;

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
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Globale Suche über mehrere Quellen (#114) mit {@link PageSearch} gegen den {@link InMemorySearchIndex}: Quellen mit
 * gleichen Seiten-IDs und Spaces, globale Sortierung und Pagination, Indexänderungen und unterschiedliche Rechte.
 */
class GlobalPageSearchTest {

    private static final AccessContext CALLER = AccessContext.technicalClient("etl");
    private static final AccessPolicy ALLOW_ALL = (context, scope, kind) -> AccessDecision.ALLOW;
    private static final Instant T0 = Instant.parse("2026-10-01T00:00:00Z");

    private final List<String> configured = new ArrayList<>(List.of("wiki-a", "wiki-b"));
    private final InMemorySearchIndex index = new InMemorySearchIndex();
    private final List<ListedPage> reference = new ArrayList<>();

    private final SourceCatalog catalog = new SourceCatalog() {
        @Override
        public List<HarvestSource> all() {
            return configured.stream().map(GlobalPageSearchTest::source).toList();
        }

        @Override
        public Optional<HarvestSource> find(String sourceId) {
            return configured.contains(sourceId) ? Optional.of(source(sourceId)) : Optional.empty();
        }
    };

    private static HarvestSource source(String id) {
        HarvestSource source = mock(HarvestSource.class);
        when(source.id()).thenReturn(id);
        return source;
    }

    private static PageSnapshot page(String sourceId, String space, String pageId, String title, Instant modifiedAt) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:" + sourceId + "/page/" + pageId, title, space,
                null, "1", modifiedAt, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of(sourceId, metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), T0);
    }

    private void publish(String sourceId, String generation, PageSnapshot... pages) {
        List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
        reference.removeIf(listed -> listed.header().key().sourceId().equals(sourceId));
        for (PageSnapshot snapshot : pages) {
            entries.add(entry(snapshot, confirmed("c1")));
            reference.add(ListedPage.of(snapshot.header(), snapshot));
        }
        index.publish(sourceId, new SearchIndexState(generation, T0), entries);
    }

    /** Zwei Quellen mit denselben Seiten-IDs und Spaces, aber anderen Titeln und Zeiten. */
    private void twoSources() {
        publish("wiki-a", "a1",
                page("wiki-a", "DEV", "1", "Architektur Übersicht", T0.plusSeconds(10)),
                page("wiki-a", "DEV", "2", "Betrieb", T0.plusSeconds(30)),
                page("wiki-a", "OPS", "3", "Architektur Betrieb", null));
        publish("wiki-b", "b1",
                page("wiki-b", "DEV", "1", "Architektur Übersicht", T0.plusSeconds(10)),
                page("wiki-b", "DEV", "2", "Anleitung", T0.plusSeconds(20)),
                page("wiki-b", "OPS", "4", "Architektur Zielbild", T0.plusSeconds(40)));
    }

    private PageSearch search(AccessPolicy policy) {
        return new PageSearch(catalog, index, new AccessGuard(policy));
    }

    private static PageSearchQuery global(String title, SearchSort sort, String... spaces) {
        return PageSearchQuery.of(null, List.of(spaces), title, null, null, null, null, null, sort);
    }

    private static List<String> keys(SearchResults results) {
        return results.hits().stream().map(hit -> key(hit.page())).toList();
    }

    private static String key(ListedPage page) {
        return page.header().key().sourceId() + "/" + page.pageId();
    }

    private List<String> pageThrough(PageSearch search, PageSearchQuery query, int limit) {
        List<String> keys = new ArrayList<>();
        String cursor = null;
        do {
            SearchResults results = search.search(CALLER, query, cursor, limit);
            assertThat(results.hits()).hasSizeLessThanOrEqualTo(limit);
            keys.addAll(keys(results));
            cursor = results.nextCursor();
            assertThat(cursor == null).isEqualTo(results.complete());
        } while (cursor != null);
        return keys;
    }

    @Test
    void aGlobalSearchFindsHitsOfBothSourcesEachWithItsRealSource() {
        twoSources();

        SearchResults results = search(ALLOW_ALL).search(CALLER, global("architektur", SearchSort.TITLE), null, 10);

        assertThat(results.global()).isTrue();
        assertThat(results.sourceId()).isNull();
        // Gleicher Titel und gleiche ID in beiden Quellen: zwei Treffer, nach Quelle geordnet ("Ü" folgt "Z").
        assertThat(keys(results)).containsExactly("wiki-a/3", "wiki-b/4", "wiki-a/1", "wiki-b/1");
        assertThat(results.complete()).isTrue();
    }

    @Test
    void aSingleSourceSearchStaysWhereItWas() {
        twoSources();

        SearchResults results = search(ALLOW_ALL).search(CALLER, PageSearchQuery.of("wiki-b", List.of(),
                "architektur", null, null, null, null, null, SearchSort.TITLE), null, 10);

        assertThat(results.global()).isFalse();
        assertThat(results.sourceId()).isEqualTo("wiki-b");
        assertThat(results.index().generation()).isEqualTo("b1");
        assertThat(keys(results)).containsExactly("wiki-b/4", "wiki-b/1");
    }

    @Test
    void globalFiltersApplyInEverySource() {
        twoSources();
        PageSearch search = search(ALLOW_ALL);

        assertThat(keys(search.search(CALLER, global(null, SearchSort.PAGE_ID, "DEV"), null, 10)))
                .containsExactly("wiki-a/1", "wiki-b/1", "wiki-a/2", "wiki-b/2");
        assertThat(keys(search.search(CALLER, PageSearchQuery.of(null, List.of(), null, "1", null, null, null, null,
                SearchSort.PAGE_ID), null, 10))).containsExactly("wiki-a/1", "wiki-b/1");
        assertThat(keys(search.search(CALLER, global(null, SearchSort.MODIFIED_DESC), null, 10)))
                .containsExactly("wiki-b/4", "wiki-a/2", "wiki-b/2", "wiki-a/1", "wiki-b/1", "wiki-a/3");
    }

    @Test
    void manySourcesPageThroughOneGloballySortedListWithOneIndexQueryPerBatch() {
        configured.clear();
        for (int s = 0; s < 8; s++) {
            String sourceId = "quelle-" + s;
            configured.add(sourceId);
            List<PageSnapshot> pages = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                pages.add(page(sourceId, i % 2 == 0 ? "DEV" : "OPS", String.valueOf(i), "Seite " + (i % 4),
                        i % 5 == 0 ? null : T0.plusSeconds(i % 3)));
            }
            publish(sourceId, "g" + s, pages.toArray(PageSnapshot[]::new));
        }
        PageSearch search = search(ALLOW_ALL);

        for (SearchSort sort : SearchSort.values()) {
            PageSearchQuery query = global("seite", sort);
            List<String> expected = reference.stream().filter(query::matches).sorted(sort.order())
                    .map(GlobalPageSearchTest::key).toList();
            assertThat(expected).hasSize(240);
            for (int limit : new int[] {7, 100}) {
                int before = index.searches();
                List<String> keys = pageThrough(search, query, limit);
                assertThat(keys).as("%s, limit %d", sort, limit).containsExactlyElementsOf(expected)
                        .doesNotHaveDuplicates();
                // Eine Abfrage je Stapel über alle Quellen, nicht eine je Quelle.
                assertThat(index.searches() - before).isEqualTo((240 + limit) / limit);
            }
        }
    }

    @Test
    void aChangedSourceInTheScopeEndsTheGlobalPagination() {
        twoSources();
        PageSearch search = search(ALLOW_ALL);
        SearchResults first = search.search(CALLER, global(null, SearchSort.PAGE_ID), null, 2);

        publish("wiki-b", "b2", page("wiki-b", "DEV", "9", "Neu", T0));

        assertThatThrownBy(() -> search.search(CALLER, global(null, SearchSort.PAGE_ID), first.nextCursor(), 2))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void aSourceThatComesIntoTheIndexEndsTheGlobalPagination() {
        twoSources();
        configured.add("wiki-c");
        PageSearch search = search(ALLOW_ALL);
        SearchResults first = search.search(CALLER, global(null, SearchSort.PAGE_ID), null, 2);

        publish("wiki-c", "c1", page("wiki-c", "DEV", "1", "Neu", T0));

        assertThatThrownBy(() -> search.search(CALLER, global(null, SearchSort.PAGE_ID), first.nextCursor(), 2))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void aChangeInAHiddenSourceNeitherShowsNorEndsTheGlobalPagination() {
        twoSources();
        configured.add("geheim");
        publish("geheim", "x1", page("geheim", "DEV", "1", "Geheim", T0));
        AccessPolicy noSecret = (context, scope, kind) -> scope.sourceId().equals("geheim") ? AccessDecision.DENY
                : AccessDecision.ALLOW;
        PageSearch search = search(noSecret);
        SearchResults first = search.search(CALLER, global(null, SearchSort.PAGE_ID), null, 2);

        publish("geheim", "x2", page("geheim", "DEV", "1", "Geheim", T0), page("geheim", "DEV", "2", "Mehr", T0));
        SearchResults rest = search.search(CALLER, global(null, SearchSort.PAGE_ID), first.nextCursor(), 10);

        assertThat(first.index().generation()).isEqualTo(rest.index().generation());
        assertThat(first.index().generation()).doesNotContain("geheim");
        assertThat(keys(first)).containsExactly("wiki-a/1", "wiki-b/1");
        assertThat(keys(rest)).containsExactly("wiki-a/2", "wiki-b/2", "wiki-a/3", "wiki-b/4");
    }

    @Test
    void hiddenSourcesSpacesAndPagesNeverAppearAndPaginationSkipsThem() {
        twoSources();
        configured.add("geheim");
        publish("geheim", "x1", page("geheim", "DEV", "1", "Architektur geheim", T0));
        // wiki-a: alles; wiki-b: nur DEV, und darin Seite 2 nicht; geheim: nichts.
        AccessPolicy partial = (context, scope, kind) -> {
            if (kind != AccessKind.METADATA || scope.sourceId().equals("geheim")) {
                return AccessDecision.DENY;
            }
            if (scope.sourceId().equals("wiki-b")) {
                if ("OPS".equals(scope.spaceKey()) || "2".equals(scope.pageId())) {
                    return AccessDecision.DENY;
                }
            }
            return AccessDecision.ALLOW;
        };
        PageSearch search = search(partial);

        assertThat(pageThrough(search, global(null, SearchSort.PAGE_ID), 1))
                .containsExactly("wiki-a/1", "wiki-b/1", "wiki-a/2", "wiki-a/3");
        // spaceKey=OPS global: nur Quellen, in denen OPS lesbar ist; kein 403 für wiki-b.
        assertThat(keys(search.search(CALLER, global(null, SearchSort.PAGE_ID, "OPS"), null, 10)))
                .containsExactly("wiki-a/3");
        // Ausdrücklich angefragt bleibt die Verweigerung wie bisher.
        assertThatThrownBy(() -> search.search(CALLER, PageSearchQuery.of("geheim", List.of(), null, null, null, null,
                null, null, SearchSort.PAGE_ID), null, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> search.search(CALLER, PageSearchQuery.of("wiki-b", List.of("OPS"), null, null, null,
                null, null, null, SearchSort.PAGE_ID), null, 10)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void theCursorNamesNoHiddenHit() {
        twoSources();
        AccessPolicy hideB4 = (context, scope, kind) -> "wiki-b".equals(scope.sourceId()) && "4".equals(scope.pageId())
                ? AccessDecision.DENY : AccessDecision.ALLOW;
        SearchResults first = search(hideB4).search(CALLER, global("architektur", SearchSort.TITLE), null, 2);

        // Reihenfolge im Index: wiki-a/3, wiki-b/4 (verborgen), wiki-a/1, wiki-b/1.
        assertThat(keys(first)).containsExactly("wiki-a/3", "wiki-a/1");
        String decoded = new String(Base64.getUrlDecoder().decode(first.nextCursor().substring(3)),
                StandardCharsets.UTF_8);
        assertThat(decoded).endsWith("wiki-a/1").doesNotContain("wiki-b/4");
        SearchResults rest = search(hideB4).search(CALLER, global("architektur", SearchSort.TITLE),
                first.nextCursor(), 2);
        assertThat(keys(rest)).containsExactly("wiki-b/1");
        assertThat(rest.complete()).isTrue();
    }

    @Test
    void withoutReadableIndexedSourcesTheGlobalResultIsEmptyNotACacheMiss() {
        PageSearch search = search(ALLOW_ALL);

        SearchResults nothingIndexed = search.search(CALLER, global(null, SearchSort.TITLE), null, 10);
        assertThat(nothingIndexed.hits()).isEmpty();
        assertThat(nothingIndexed.complete()).isTrue();
        assertThat(nothingIndexed.index()).isNull();

        configured.clear();
        assertThat(search.search(CALLER, global(null, SearchSort.TITLE), null, 10).hits()).isEmpty();

        twoSources();
        configured.addAll(List.of("wiki-a", "wiki-b"));
        String cursor = search.search(CALLER, global(null, SearchSort.TITLE), null, 1).nextCursor();
        AccessPolicy denyAll = (context, scope, kind) -> AccessDecision.DENY;
        SearchResults hidden = search(denyAll).search(CALLER, global(null, SearchSort.TITLE), null, 10);
        assertThat(hidden.hits()).isEmpty();
        assertThat(hidden.index()).isNull();
        // Ein Cursor aus einem Stand, den es für diesen Aufrufer nicht (mehr) gibt.
        assertThatThrownBy(() -> search(denyAll).search(CALLER, global(null, SearchSort.TITLE), cursor, 10))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void globalAndSingleSourceCursorsAreNotInterchangeable() {
        twoSources();
        PageSearch search = search(ALLOW_ALL);
        String globalCursor = search.search(CALLER, global(null, SearchSort.TITLE), null, 1).nextCursor();
        PageSearchQuery single = PageSearchQuery.of("wiki-a", List.of(), null, null, null, null, null, null,
                SearchSort.TITLE);
        String singleCursor = search.search(CALLER, single, null, 1).nextCursor();

        assertThatThrownBy(() -> search.search(CALLER, single, globalCursor, 1))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, global(null, SearchSort.TITLE), singleCursor, 1))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, global(null, SearchSort.TITLE), null, 0))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void theSameScopeYieldsTheSameHitsForEveryCaller() {
        twoSources();
        Set<String> first = new HashSet<>(keys(search(ALLOW_ALL).search(CALLER, global(null, SearchSort.TITLE),
                null, 100)));
        Set<String> second = new HashSet<>(keys(search(ALLOW_ALL).search(AccessContext.technicalClient("ui"),
                global(null, SearchSort.TITLE), null, 100)));

        assertThat(first).isEqualTo(second).hasSize(6);
    }
}
