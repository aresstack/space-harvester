package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class SearchEngineCatalogTest {

    @Test
    public void theCatalogHoldsAllTwentyEightEngines() {
        List<String> expected = Arrays.asList("Google", "Bing", "Yahoo Search", "DuckDuckGo", "Brave Search", "Mojeek",
                "Qwant", "Ecosia", "Startpage", "Swisscows", "Seznam", "Yandex", "Rambler", "Baidu", "Sogou",
                "360 Search", "Shenma", "Naver", "Daum", "Yahoo! JAPAN", "Cốc Cốc", "Yaani", "Kagi", "Yep", "MetaGer",
                "SearXNG", "Marginalia Search", "YaCy");
        assertEquals(28, SearchEngineCatalog.entries().size());
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i), SearchEngineCatalog.entries().get(i).getDisplayName());
        }
    }

    @Test
    public void idsAreUniqueStableAndTheExistingOnesAreKept() {
        Set<String> ids = new HashSet<String>();
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            assertTrue("duplicate id " + entry.getId(), ids.add(entry.getId()));
            assertTrue(entry.getId(), entry.getId().matches("[a-z][a-z0-9-]*"));
            assertEquals(entry, SearchEngineCatalog.byId(" " + entry.getId() + " "));
        }
        // ids already persisted in users' settings must never change
        assertEquals("google", SearchEngineCatalog.GOOGLE);
        assertEquals("bing", SearchEngineCatalog.BING);
        assertEquals("duckduckgo", SearchEngineCatalog.DUCKDUCKGO);
        assertNull(SearchEngineCatalog.byId("altavista"));
        assertEquals("altavista", SearchEngineCatalog.displayName("altavista"));
    }

    @Test
    public void defaultLanguagesAreTheAgreedStartingValues() {
        assertEquals(Arrays.asList("de", "en", "fr", "es", "it"), SearchEngineCatalog.defaultLanguages("google"));
        assertEquals(Arrays.asList("de", "en", "fr", "es"), SearchEngineCatalog.defaultLanguages("bing"));
        assertEquals(Arrays.asList("en", "de"), SearchEngineCatalog.defaultLanguages("yahoo"));
        assertEquals(Arrays.asList("de", "en"), SearchEngineCatalog.defaultLanguages("duckduckgo"));
        assertEquals(Arrays.asList("de", "en", "fr"), SearchEngineCatalog.defaultLanguages("startpage"));
        assertEquals(Arrays.asList("cs", "sk"), SearchEngineCatalog.defaultLanguages("seznam"));
        assertEquals(Arrays.asList("ru", "en"), SearchEngineCatalog.defaultLanguages("yandex"));
        assertEquals(Arrays.asList("zh-CN"), SearchEngineCatalog.defaultLanguages("baidu"));
        assertEquals(Arrays.asList("ko"), SearchEngineCatalog.defaultLanguages("naver"));
        assertEquals(Arrays.asList("ja"), SearchEngineCatalog.defaultLanguages("yahoo-japan"));
        assertEquals(Arrays.asList("vi"), SearchEngineCatalog.defaultLanguages("coccoc"));
        assertEquals(Arrays.asList("tr"), SearchEngineCatalog.defaultLanguages("yaani"));
        assertEquals(Arrays.asList("en"), SearchEngineCatalog.defaultLanguages("marginalia"));
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            assertFalse(entry.getId(), entry.getDefaultLanguages().isEmpty());
            for (String code : entry.getDefaultLanguages()) {
                assertEquals(code, SearchLanguages.normalize(code));
            }
        }
    }

    @Test
    public void anEngineIsSearchableExactlyWhenItsBrowserDriverExists() {
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            boolean driver = BrowserSearchEngineCatalog.byId(entry.getId()) != null;
            assertEquals(entry.getId() + " status " + entry.getStatus() + " vs driver " + driver,
                    driver, entry.isSearchable());
        }
        for (BrowserSearchEngine driver : BrowserSearchEngineCatalog.engines()) {
            assertNotNull("driver without catalog entry: " + driver.getId(),
                    SearchEngineCatalog.byId(driver.getId()));
        }
    }

    @Test
    public void notImplementedEnginesStayVisibleWithAReason() {
        int notImplemented = 0;
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            if (!entry.isSearchable()) {
                notImplemented++;
                assertFalse(entry.getId() + " needs a note", entry.getStatusNote().isEmpty());
            }
        }
        assertTrue(notImplemented > 0);
        assertEquals(SearchEngineImplementationStatus.NOT_IMPLEMENTED,
                SearchEngineCatalog.byId("searxng").getStatus());
        assertTrue(SearchEngineCatalog.byId("searxng").getStatusNote().contains("instance"));
        assertTrue(SearchEngineCatalog.byId("kagi").getStatusNote().contains("account"));
    }

    @Test
    public void onlyGoogleStartsWithARequestDelay() {
        assertEquals("the Google delay the migration and new rows start with",
                15_000, SearchEngineCatalog.GOOGLE_REQUEST_DELAY_MILLIS);
        for (SearchEngineCatalogEntry entry : SearchEngineCatalog.entries()) {
            int expected = SearchEngineCatalog.GOOGLE.equals(entry.getId())
                    ? SearchEngineCatalog.GOOGLE_REQUEST_DELAY_MILLIS : 0;
            assertEquals(entry.getId() + " default delay", expected, entry.getDefaultDelayMillis());
            assertEquals(entry.getId() + " default delay by id", expected,
                    SearchEngineCatalog.defaultDelayMillis(entry.getId()));
        }
        assertEquals("an unknown engine has no delay", 0, SearchEngineCatalog.defaultDelayMillis("nope"));
    }
}
