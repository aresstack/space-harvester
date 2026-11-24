package com.aresstack.spaceharvester.app.chat;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessGuard;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.query.KeywordSearch;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SearchSpaces;
import com.aresstack.spaceharvester.application.sources.SourceCapability;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceSearchMatch;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Suche des Chats in einem Bereich fragt die Quelle im selben Umfang wie die Suchleiste mit derselben Auswahl
 * (Angelo 24.11.2026): Gewählt war eine Seite samt Unterseiten; die Suchleiste fand „Vorhaben Datenhaus (Team 10)“ als
 * Erstes, der Chat nichts.
 */
class ChatAreaQueryTest {

    private static final AccessContext APP = AccessContext.technicalClient("app");
    private static final String AREA_PAGE = "datenhaus";

    @Test
    void theAreaQueryCarriesTheSourceAndTheSpacesOfTheSelectionLikeTheSearchBar() {
        KeywordSearch.Query query = ChatTools.areaQuery("Team 10", area());

        assertThat(query.sourceId()).isEqualTo("wiki");
        assertThat(query.spaceKeys()).containsExactly("CL");
        assertThat(query.pages()).isEqualTo(area().pages());
    }

    @Test
    void withoutTheSpaceOfASourceTheAreaQueryKeepsEverySpaceSoThatNoPageIsLost() {
        PageSelection twoSources = new PageSelection(PageSet.of(Map.of("wiki", List.of(AREA_PAGE),
                "files", List.of("a"))), new HoldingCounts(2, 2, 0, 0),
                List.of(new SpaceSummary("wiki", "CL", null, new HoldingCounts(1, 1, 0, 0))), true);

        KeywordSearch.Query query = ChatTools.areaQuery("Team 10", twoSources);

        assertThat(query.sourceId()).isNull();
        assertThat(query.sourceIds()).containsExactlyInAnyOrder("wiki", "files");
        assertThat(query.spaceKeys()).isEmpty();
    }

    @Test
    void aPageOfTheAreaThatTheSourceRanksBehindPagesOfOtherSpacesIsFound() {
        // Die Quelle (wie Confluence) rankt im ganzen Wiki viele Seiten anderer Spaces vor die Seite im Bereich.
        RankingSource wiki = new RankingSource();
        for (int i = 0; i < 60; i++) {
            wiki.page("anders-" + i, "ANDERS", "Team " + i + " Sprint 10");
        }
        wiki.page(AREA_PAGE, "CL", "Vorhaben Datenhaus (Team 10)");
        KeywordSearch search = new KeywordSearch(catalog(wiki),
                new AccessGuard((context, scope, kind) -> AccessDecision.ALLOW));
        int limit = 20;

        KeywordSearch.Results before = search.search(APP, new KeywordSearch.Query("Team 10", null, Set.of(),
                Set.of()).within(area().pages()), null, limit);
        KeywordSearch.Results now = search.search(APP, ChatTools.areaQuery("Team 10", area()), null, limit);

        assertThat(before.hits()).as("ohne Spaces fragt die Quelle das ganze Wiki").isEmpty();
        assertThat(now.hits()).extracting(KeywordSearch.Hit::pageId).containsExactly(AREA_PAGE);
    }

    private static PageSelection area() {
        return new PageSelection(PageSet.of(Map.of("wiki", List.of("cl2", AREA_PAGE))),
                new HoldingCounts(2, 2, 0, 0),
                List.of(new SpaceSummary("wiki", "CL", null, new HoldingCounts(2, 2, 0, 0))), true);
    }

    private static SourceCatalog catalog(HarvestSource source) {
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return source.id().equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
    }

    /** Eine Quelle mit eigener Suche: alle Seiten mit einem Wort der Anfrage, in Einfügereihenfolge, mit Space-Filter. */
    private static final class RankingSource implements HarvestSource {

        private final Map<String, String[]> pages = new LinkedHashMap<>();

        void page(String id, String space, String title) {
            pages.put(id, new String[] {space, title});
        }

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "fake";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public Set<SourceCapability> capabilities() {
            return Set.of(SourceCapability.PARTITIONS, SourceCapability.LIVE_SEARCH);
        }

        @Override
        public List<Partition> partitions() {
            return List.of();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            String[] page = pages.get(pageId);
            return new PageMetadata(pageId, pageId, page[1], page[0], null, "1", null, null, new TreeMap<>());
        }

        @Override
        public List<SourceSearchMatch> searchPages(String text, int limit, SearchSpaces spaces) {
            List<SourceSearchMatch> found = new ArrayList<>();
            for (Map.Entry<String, String[]> page : pages.entrySet()) {
                String space = page.getValue()[0];
                String title = page.getValue()[1];
                boolean inSpaces = spaces.only().isEmpty() || spaces.only().contains(space);
                boolean matches = false;
                for (String word : text.split("\\s+")) {
                    matches |= title.contains(word);
                }
                if (inSpaces && matches && found.size() < limit) {
                    found.add(new SourceSearchMatch(page.getKey(), title, title, null, "1", null));
                }
            }
            return found;
        }
    }
}
