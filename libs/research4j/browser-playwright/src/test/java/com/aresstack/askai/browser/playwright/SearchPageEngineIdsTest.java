package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.assertEquals;

/** Search pages of the user's own travel through the search tools with their URL intact. */
public class SearchPageEngineIdsTest {

    private static final String PORTAL = "https://portal.example/Suche?q={query}&bereich=TeamA,TeamB";

    @Test
    public void anEscapedSearchPageSurvivesTheCommaSeparatedEnginesArgument() {
        Map<String, Object> args = Collections.<String, Object>singletonMap("engines",
                "duckduckgo," + BrowserSearchEngineSelection.escapeId(PORTAL));
        assertEquals(Arrays.asList("duckduckgo", PORTAL), BrowserToolSet.engines(args));
    }

    @Test
    public void aSearchPageKeepsItsCaseWhenItIsSelected() throws Exception {
        assertEquals(PORTAL, PlaywrightBrowserSession.selectEngines(Collections.singletonList(PORTAL)).get(0)
                .getEndpointTemplates().get(0));
        assertEquals("bing", PlaywrightBrowserSession.selectEngines(Collections.singletonList(" Bing ")).get(0)
                .getId());
    }
}
