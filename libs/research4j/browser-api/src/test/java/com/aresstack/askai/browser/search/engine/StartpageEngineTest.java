package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Startpage is driven like the other page engines: its start page, its search field, its "next" control. */
public class StartpageEngineTest {

    private final BrowserSearchEngine startpage = BrowserSearchEngineCatalog.byId("startpage");

    @Test
    public void startpageIsAFormEngineOfTheResearchBrowser() {
        assertNotNull(startpage);
        assertEquals("Startpage", startpage.getDisplayName());
        assertTrue(startpage.usesSearchForms());
        BrowserSearchEngine.SearchForm form = startpage.formOf(0);
        assertEquals("https://www.startpage.com/", form.getHomeUrl());
        assertEquals("input#q", form.getSearchBoxSelectors().get(0));
        assertFalse("deeper result pages through its own next control", form.getNextPageSelectors().isEmpty());
        assertNull("no result URL is ever built", startpage.pageUrl(0, "q", 2));
        assertFalse("it runs in the research browser, not in Google's search browser",
                BrowserSearchEngineCatalog.runsInSearchBrowser("startpage"));
    }

    @Test
    public void itIsSupportedAfterTheLiveSweep() {
        SearchEngineCatalogEntry entry = SearchEngineCatalog.byId("startpage");
        assertEquals(SearchEngineImplementationStatus.SUPPORTED, entry.getStatus());
        assertTrue(entry.isSearchable());
    }

    @Test
    public void itsHostsAreEngineHostsTheResearchBrowserNeverOpensAsSources() {
        assertTrue(SearchEngineHosts.isEngineHost("https://www.startpage.com/sp/search"));
        assertTrue(SearchEngineHosts.isEngineHost("https://eu.startpage.com/av/proxy?ep=x"));
        assertTrue(SearchEngineHosts.isStartpageHost("startpage.com"));
        assertFalse(SearchEngineHosts.isEngineHost("https://startpagefan.example/"));
        assertFalse(SearchEngineHosts.isEngineHost("https://www.mystartpage.com/"));
    }

    @Test
    public void aWebSearchMayNameStartpage() {
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(java.util.Arrays.asList(
                new BrowserSearchEngineSelection.Entry("startpage", true, 1, 0, 0,
                        java.util.Arrays.asList("de", "en"), java.util.Collections.<String>emptyList()),
                new BrowserSearchEngineSelection.Entry("duckduckgo", true, 1, 0, 1,
                        java.util.Arrays.asList("de"), java.util.Collections.<String>emptyList())), null);
        assertEquals("startpage", selection.resolvedEnabledEngines().get(0).getId());
        assertEquals(2, selection.resolvedEnabledEngines().size());
    }
}
