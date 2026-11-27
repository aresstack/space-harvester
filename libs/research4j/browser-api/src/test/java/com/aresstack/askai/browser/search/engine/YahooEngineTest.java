package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Yahoo Search and Yahoo! JAPAN are driven like the other page engines: start page, search field, "next". */
public class YahooEngineTest {

    @Test
    public void yahooIsAFormEngineOfTheResearchBrowser() {
        BrowserSearchEngine yahoo = BrowserSearchEngineCatalog.byId("yahoo");
        assertNotNull(yahoo);
        assertEquals("Yahoo Search", yahoo.getDisplayName());
        assertTrue(yahoo.usesSearchForms());
        BrowserSearchEngine.SearchForm form = yahoo.formOf(0);
        assertEquals("https://search.yahoo.com/", form.getHomeUrl());
        assertEquals("input#yschsp", form.getSearchBoxSelectors().get(0));
        assertFalse(form.getNextPageSelectors().isEmpty());
        assertNull("no result URL is ever built", yahoo.pageUrl(0, "q", 2));
        assertFalse(BrowserSearchEngineCatalog.runsInSearchBrowser("yahoo"));
    }

    @Test
    public void yahooJapanIsAFormEngineOfTheResearchBrowser() {
        BrowserSearchEngine japan = BrowserSearchEngineCatalog.byId("yahoo-japan");
        assertNotNull(japan);
        assertEquals("Yahoo! JAPAN", japan.getDisplayName());
        assertEquals("https://search.yahoo.co.jp/", japan.formOf(0).getHomeUrl());
        assertFalse(japan.formOf(0).getNextPageSelectors().isEmpty());
        assertFalse(BrowserSearchEngineCatalog.runsInSearchBrowser("yahoo-japan"));
    }

    @Test
    public void bothAreOfferedAsExperimentalUntilValidatedLive() {
        for (String id : new String[]{"yahoo", "yahoo-japan"}) {
            SearchEngineCatalogEntry entry = SearchEngineCatalog.byId(id);
            assertEquals(id, "yahoo".equals(id) ? SearchEngineImplementationStatus.SUPPORTED
                    : SearchEngineImplementationStatus.EXPERIMENTAL, entry.getStatus()); // yahoo validated 2026-11-17
            assertTrue(id, entry.isSearchable());
        }
    }

    @Test
    public void onlyTheirSearchHostsAreEngineHosts() {
        assertTrue(SearchEngineHosts.isEngineHost("https://search.yahoo.com/search?p=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://de.search.yahoo.com/search?p=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://r.search.yahoo.com/_ylt=a/RU=https%3a%2f%2fx.example/"));
        assertTrue(SearchEngineHosts.isEngineHost("https://search.yahoo.co.jp/search?p=x"));
        assertFalse("Yahoo Finance is a site, not the engine",
                SearchEngineHosts.isEngineHost("https://finance.yahoo.com/quote/X"));
        assertFalse(SearchEngineHosts.isEngineHost("https://news.yahoo.co.jp/articles/1"));
        assertFalse(SearchEngineHosts.isEngineHost("https://research.yahoo.com.example/"));
    }
}
