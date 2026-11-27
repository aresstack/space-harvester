package com.aresstack.askai.browser.search.engine;

import com.aresstack.askai.browser.search.layout.EngineFamily;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Seznam, Yandex, Naver, Cốc Cốc and Baidu are page engines with a search form, experimental until validated live. */
public class RegionalEnginesTest {

    private static final String[] IDS = {"seznam", "yandex", "naver", "coccoc", "baidu"};

    @Test
    public void eachIsAFormEngineOfTheResearchBrowser() {
        for (String id : IDS) {
            BrowserSearchEngine engine = BrowserSearchEngineCatalog.byId(id);
            assertNotNull(id, engine);
            assertEquals(id, SearchEngineCatalog.displayName(id), engine.getDisplayName());
            assertTrue(id, engine.usesSearchForms());
            assertFalse(id, engine.formOf(0).getSearchBoxSelectors().isEmpty());
            assertFalse(id, engine.formOf(0).getNextPageSelectors().isEmpty());
            assertNull(id + ": no result URL is ever built", engine.pageUrl(0, "q", 2));
            assertFalse(id, BrowserSearchEngineCatalog.runsInSearchBrowser(id));
        }
    }

    @Test
    public void eachIsExperimentalUntilValidatedLive() {
        for (String id : IDS) {
            boolean validated = "yandex".equals(id) || "naver".equals(id); // live sweep 2026-11-17
            assertEquals(id, validated ? SearchEngineImplementationStatus.SUPPORTED
                    : SearchEngineImplementationStatus.EXPERIMENTAL, SearchEngineCatalog.byId(id).getStatus());
        }
        assertTrue("Baidu's note says how its opaque links resolve",
                SearchEngineCatalog.byId("baidu").getStatusNote().contains("mu attribute"));
    }

    @Test
    public void searchHostsAreEngineHostsTheirContentSitesAreNot() {
        assertTrue(SearchEngineHosts.isEngineHost("https://search.seznam.cz/?q=x"));
        assertFalse(SearchEngineHosts.isEngineHost("https://www.seznamzpravy.cz/clanek/1"));
        assertTrue(SearchEngineHosts.isEngineHost("https://yandex.com/search/?text=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://yandex.ru/search/?text=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://yandex.com.tr/search/?text=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://ya.ru/search/?text=x"));
        assertFalse(SearchEngineHosts.isEngineHost("https://notyandex.com/"));
        assertTrue(SearchEngineHosts.isEngineHost("https://search.naver.com/search.naver?query=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://m.search.naver.com/search.naver?query=x"));
        assertFalse("a Naver blog is a site", SearchEngineHosts.isEngineHost("https://blog.naver.com/someone/1"));
        assertTrue(SearchEngineHosts.isEngineHost("https://coccoc.com/search?query=x"));
        assertFalse(SearchEngineHosts.isEngineHost("https://coccocfan.example/"));
        assertTrue(SearchEngineHosts.isEngineHost("https://www.baidu.com/s?wd=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://www.baidu.com/link?url=abc"));
        assertTrue(SearchEngineHosts.isEngineHost("https://m.baidu.com/s?word=x"));
        assertFalse("Baidu Baike is a site", SearchEngineHosts.isEngineHost("https://baike.baidu.com/item/x"));
    }

    @Test
    public void layoutProfilesAreScopedPerEngine() {
        assertEquals(EngineFamily.SEZNAM, EngineFamily.fromUrlOrHost("https://search.seznam.cz/"));
        assertEquals(EngineFamily.YANDEX, EngineFamily.fromUrlOrHost("https://yandex.com/search/"));
        assertEquals(EngineFamily.NAVER, EngineFamily.fromUrlOrHost("https://search.naver.com/"));
        assertEquals(EngineFamily.COCCOC, EngineFamily.fromUrlOrHost("https://coccoc.com/search"));
        assertEquals(EngineFamily.YAHOO, EngineFamily.fromUrlOrHost("https://search.yahoo.co.jp/search"));
        assertEquals(EngineFamily.BAIDU, EngineFamily.fromUrlOrHost("https://www.baidu.com/s?wd=x"));
    }
}
