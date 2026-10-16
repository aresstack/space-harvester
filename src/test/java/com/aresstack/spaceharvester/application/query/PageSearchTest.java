package com.aresstack.spaceharvester.application.query;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

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
import com.aresstack.spaceharvester.application.sources.UnknownSourceException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** {@link PageSearch} gegen den {@link InMemorySearchIndex}: Semantik, Sortierung, Paging, Rechte, Grenzen. */
class PageSearchTest {

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
    private static final SearchIndexState STATE = new SearchIndexState("i1", Instant.parse("2026-10-16T20:10:00Z"));

    private final InMemorySearchIndex index = new InMemorySearchIndex();

    private static PageSnapshot page(String space, String pageId, String parent, String title, String version,
                                     Instant modifiedAt) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, space, parent,
                version, modifiedAt, null, PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "test"), Instant.parse("2026-10-16T20:00:00Z"));
    }

    private void publishSample() {
        index.publish("wiki", STATE, List.of(
                entry(page("DEV", "10", null, "Architektur-Übersicht", "3", Instant.parse("2026-10-01T00:00:00Z")),
                        confirmed("g1")),
                entry(page("DEV", "11", "10", "architektur Entscheidungen", "1", Instant.parse("2026-10-05T00:00:00Z")),
                        confirmed("g1")),
                entry(page("DEV", "12", "10", "Betrieb", "7", null), confirmed("g1")),
                entry(page("OPS", "20", null, "Architekturen im Betrieb", "1", Instant.parse("2026-10-03T00:00:00Z")),
                        confirmed("g2")),
                entry(page("OPS", "21", "20", "ÜBERSICHT Betrieb", "2", Instant.parse("2026-10-05T00:00:00Z")),
                        confirmed("g2"))));
    }

    private PageSearch search(AccessPolicy policy) {
        return new PageSearch(CATALOG, index, new AccessGuard(policy));
    }

    private static PageSearchQuery query(String title, SearchSort sort, String... spaces) {
        return PageSearchQuery.of("wiki", List.of(spaces), title, null, null, null, null, null, sort);
    }

    private static List<String> ids(SearchResults results) {
        return results.hits().stream().map(hit -> hit.page().pageId()).toList();
    }

    @Test
    void titleWordsMatchWholeWordsIgnoringCase() {
        publishSample();
        PageSearch search = search(ALLOW_ALL);

        assertThat(ids(search.search(CALLER, query("ARCHITEKTUR", SearchSort.PAGE_ID), null, 10)))
                .containsExactly("10", "11");
        assertThat(ids(search.search(CALLER, query("übersicht betrieb", SearchSort.PAGE_ID), null, 10)))
                .containsExactly("21");
        // Kein Präfix und keine Abfragesprache: Sonderzeichen trennen nur Wörter.
        assertThat(ids(search.search(CALLER, query("Archi*", SearchSort.PAGE_ID), null, 10))).isEmpty();
        assertThat(ids(search.search(CALLER, query("Architektur-Übersicht", SearchSort.PAGE_ID), null, 10)))
                .containsExactly("10");
    }

    @Test
    void filtersCombine() {
        publishSample();
        PageSearch search = search(ALLOW_ALL);

        assertThat(ids(search.search(CALLER, PageSearchQuery.of("wiki", List.of("DEV"), null, null, "10", null, null,
                null, SearchSort.PAGE_ID), null, 10))).containsExactly("11", "12");
        assertThat(ids(search.search(CALLER, PageSearchQuery.of("wiki", List.of(), "betrieb", null, null, "1", null,
                null, SearchSort.PAGE_ID), null, 10))).containsExactly("20");
        assertThat(ids(search.search(CALLER, PageSearchQuery.of("wiki", List.of(), null, "21", null, null, null, null,
                SearchSort.PAGE_ID), null, 10))).containsExactly("21");
        // Zeitgrenzen: ab einschließlich, vor ausschließlich; ohne Änderungszeit nie.
        assertThat(ids(search.search(CALLER, PageSearchQuery.of("wiki", List.of(), null, null, null, null,
                Instant.parse("2026-10-03T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"), SearchSort.PAGE_ID),
                null, 10))).containsExactly("20");
    }

    @Test
    void sortingIsStable() {
        publishSample();
        PageSearch search = search(ALLOW_ALL);

        assertThat(ids(search.search(CALLER, query(null, SearchSort.TITLE), null, 10)))
                .containsExactly("11", "10", "20", "12", "21");
        // Gleiche Änderungszeit (11, 21): pageId entscheidet; ohne Änderungszeit (12) zuletzt.
        assertThat(ids(search.search(CALLER, query(null, SearchSort.MODIFIED_DESC), null, 10)))
                .containsExactly("11", "21", "20", "10", "12");
    }

    @Test
    void pagingDeliversEveryHitOnceAndTheCursorNamesOnlyDeliveredHits() {
        List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            entries.add(entry(page("DEV", String.format("p%02d", i), null, "Seite " + i, "1", null), confirmed("g1")));
        }
        index.publish("wiki", STATE, entries);
        Set<String> hidden = Set.of("p05", "p06", "p07", "p08", "p09", "p10", "p29");
        PageSearch search = search((context, scope, kind) -> scope.pageId() != null && hidden.contains(scope.pageId())
                ? AccessDecision.DENY : AccessDecision.ALLOW);
        PageSearchQuery query = query("seite", SearchSort.PAGE_ID);

        List<String> all = new ArrayList<>();
        String cursor = null;
        SearchResults results;
        do {
            results = search.search(CALLER, query, cursor, 4);
            all.addAll(ids(results));
            cursor = results.nextCursor();
            if (cursor != null) {
                String plain = new String(Base64.getUrlDecoder().decode(cursor.substring(3)), StandardCharsets.UTF_8);
                assertThat(hidden).noneMatch(plain::contains);
                assertThat(results.hits()).isNotEmpty();
            }
        } while (!results.complete());

        assertThat(all).hasSize(23).doesNotHaveDuplicates().isSorted().doesNotContainAnyElementsOf(hidden);
    }

    @Test
    void cursorIsBoundToQueryAndIndexGeneration() {
        publishSample();
        PageSearch search = search(ALLOW_ALL);
        String cursor = search.search(CALLER, query(null, SearchSort.PAGE_ID), null, 2).nextCursor();

        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.TITLE), cursor, 2))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.PAGE_ID), "s1.kaputt", 2))
                .isInstanceOf(InvalidQueryException.class);

        index.publish("wiki", new SearchIndexState("i2", Instant.parse("2026-10-16T21:00:00Z")), List.of());
        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.PAGE_ID), cursor, 2))
                .isInstanceOf(CacheGenerationChangedException.class);
    }

    @Test
    void namedSpacesNeedMetadataEachAndHitsAreFilteredPerPage() {
        publishSample();
        List<String> checked = new ArrayList<>();
        PageSearch search = search((context, scope, kind) -> {
            checked.add(scope.sourceId() + "/" + scope.spaceKey() + "/" + scope.pageId() + "/" + kind);
            if (kind != AccessKind.METADATA) {
                return AccessDecision.DENY;
            }
            return "OPS".equals(scope.spaceKey()) ? AccessDecision.DENY : AccessDecision.ALLOW;
        });

        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.PAGE_ID, "DEV", "OPS"), null, 10))
                .isInstanceOf(AccessDeniedException.class);
        // Ohne spaceKey: Quelle prüfen, dann jeden Treffer; OPS-Treffer fallen weg.
        checked.clear();
        assertThat(ids(search.search(CALLER, query("betrieb", SearchSort.PAGE_ID), null, 10))).containsExactly("12");
        assertThat(checked).startsWith("wiki/null/null/METADATA").contains("wiki/OPS/20/METADATA");
    }

    @Test
    void inputsAreBounded() {
        publishSample();
        PageSearch search = search(ALLOW_ALL);

        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.PAGE_ID), null, 0))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.PAGE_ID), null, PageSearch.MAX_LIMIT + 1))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> query("a b c d e f g h i", SearchSort.TITLE)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> query("x".repeat(PageSearchQuery.MAX_TITLE_LENGTH + 1), SearchSort.TITLE))
                .isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> query("*?!", SearchSort.TITLE)).isInstanceOf(InvalidQueryException.class);
        String[] spaces = new String[PageSearchQuery.MAX_SPACE_KEYS + 1];
        for (int i = 0; i < spaces.length; i++) {
            spaces[i] = "S" + i;
        }
        assertThatThrownBy(() -> query(null, SearchSort.TITLE, spaces)).isInstanceOf(InvalidQueryException.class);
        assertThatThrownBy(() -> PageSearchQuery.of("wiki", List.of(), null, null, null, null,
                Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T00:00:00Z"), SearchSort.TITLE))
                .isInstanceOf(InvalidQueryException.class);
        // Doppelte Wörter zählen einmal.
        assertThat(query("Betrieb betrieb BETRIEB", SearchSort.TITLE).titleTerms()).containsExactly("betrieb");
    }

    @Test
    void unknownSourcesAndMissingIndexesAreDistinguished() {
        PageSearch search = search(ALLOW_ALL);

        assertThatThrownBy(() -> search.search(CALLER, PageSearchQuery.of("nope", List.of(), null, null, null, null,
                null, null, SearchSort.TITLE), null, 10)).isInstanceOf(UnknownSourceException.class);
        assertThatThrownBy(() -> search.search(CALLER, query(null, SearchSort.TITLE), null, 10))
                .isInstanceOf(CacheMissException.class);
        assertThatThrownBy(() -> new PageSearch(CATALOG, PageSearchIndex.unavailable(), new AccessGuard(ALLOW_ALL))
                .search(CALLER, query(null, SearchSort.TITLE), null, 10)).isInstanceOf(CacheUnavailableException.class);
    }
}
