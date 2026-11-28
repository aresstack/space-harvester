package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.askai.browser.search.repair.SearchChallengeState;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Predicate;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class SearchBrowserWebSearchTest {

    private static final String PORTAL = "https://portal.example/suche?q={query}";
    private static final String WIKI = "https://wiki.example/find?text={query}";

    private static BrowserSearchEngineSelection table(String... ids) {
        List<BrowserSearchEngineSelection.Entry> rows = new ArrayList<BrowserSearchEngineSelection.Entry>();
        for (String id : ids) {
            rows.add(new BrowserSearchEngineSelection.Entry(id, true, 1, 0, 0, null, null));
        }
        return new BrowserSearchEngineSelection(rows, EngineAcquisitionMode.ALL_ENABLED);
    }

    private static List<String> ids(BrowserSearchEngineSelection selection) {
        List<String> ids = new ArrayList<String>();
        for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
            ids.add(entry.getEngineId());
        }
        return ids;
    }

    @Test
    public void theBlockListLeavesOutTheEnginesWhosePagesItRefuses() throws Exception {
        Predicate<String> blockList = url -> url.contains("wiki.example");
        assertEquals(Arrays.asList("startpage", PORTAL), ids(SearchBrowserWebSearch.admitted(
                table("startpage", PORTAL, WIKI), null, blockList)));
        assertEquals(Collections.singletonList(PORTAL), ids(SearchBrowserWebSearch.admitted(
                table("startpage", PORTAL, WIKI), Collections.singletonList(PORTAL), blockList)));
        try {
            SearchBrowserWebSearch.admitted(table(WIKI), null, blockList);
            fail("every engine refused: nothing is searched");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.BLOCKED, expected.kind());
        }
    }

    @Test
    public void anEngineTheChatDoesNotHaveIsRefusedByName() {
        try {
            SearchBrowserWebSearch.admitted(table(PORTAL), Collections.singletonList("bing"), url -> false);
            fail("expected INVALID_REQUEST");
        } catch (BrowserException expected) {
            assertEquals(BrowserException.Kind.INVALID_REQUEST, expected.kind());
        }
    }

    private static SearchResultCandidate hit(String id, String url, String title) {
        return new SearchResultCandidate(id, "s", url, url, title, "snippet " + id, "", 1, "", "", 1, 1,
                Collections.emptyList());
    }

    @Test
    public void theHitsAreTextWithoutTheRefusedOnes() {
        InitialSearchResult result = new InitialSearchResult(Arrays.asList(
                hit("a", "https://kept.example/a", "Kept"), hit("b", "https://refused.example/b", "Refused"),
                hit("c", "https://kept.example/c", "Also kept")), Collections.<String>emptyList(),
                Collections.singletonList(new SearchChallengeState("startpage.com", "https://www.startpage.com/")),
                Collections.<String>emptyList());
        assertEquals("r1: Kept — https://kept.example/a\n   snippet a\n"
                        + "r2: Also kept — https://kept.example/c\n   snippet c\n"
                        + "CHALLENGE: startpage.com https://www.startpage.com/\n",
                SearchBrowserWebSearch.render(result, url -> url.contains("refused.example")));
        assertEquals("Keine Treffer.", SearchBrowserWebSearch.render(InitialSearchResult.empty(
                Collections.<String>emptyList()), url -> false));
    }

    @Test
    public void thePageEngineBrowserTakesPagesAndPauseFromTheChatsTable() {
        String page = "https://portal.example/suche?q={query}&a=1,2";
        BrowserSearchEngineSelection table = new BrowserSearchEngineSelection(Arrays.asList(
                new BrowserSearchEngineSelection.Entry(page, true, 4, 2500, 0, Collections.<String>emptyList(),
                        Collections.<String>emptyList()),
                new BrowserSearchEngineSelection.Entry("duckduckgo", true, 2, 0, 1,
                        Collections.<String>emptyList(), Collections.<String>emptyList())),
                EngineAcquisitionMode.FIRST_USABLE);

        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings = SearchBrowserWebSearch.withTable(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create(), table);

        assertEquals(4, settings.navigation.engineSelection.resultPagesFor(page));
        assertEquals(2500, settings.navigation.engineSelection.delayMillisFor(page));
        assertEquals(2, settings.navigation.engineSelection.resultPagesFor("duckduckgo"));
    }
}
