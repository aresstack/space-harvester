package com.aresstack.askai.browser.search.engine;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SearchEngineHostsTest {

    @Test
    public void recognisesTheEnginesByUrlOrHost() {
        assertTrue(SearchEngineHosts.isEngineHost("https://www.google.com/goto?url=CAESAAAA"));
        assertTrue(SearchEngineHosts.isEngineHost("https://www.google.de/search?q=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://google.co.uk/url?q=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://www.google.com.br/"));
        assertTrue(SearchEngineHosts.isEngineHost("https://www.bing.com/ck/a?!&&p=x"));
        assertTrue(SearchEngineHosts.isEngineHost("https://html.duckduckgo.com/html/?q=x"));
        assertTrue(SearchEngineHosts.isEngineHost("duckduckgo.com"));
        assertFalse(SearchEngineHosts.isEngineHost("https://www.haufe.de/id/beitrag/x.html"));
        assertFalse("a site that merely names an engine is a site", SearchEngineHosts.isEngineHost(
                "https://googleblog.example/post"));
        assertFalse(SearchEngineHosts.isEngineHost("https://notgoogle.com/"));
        assertFalse(SearchEngineHosts.isEngineHost(""));
        assertFalse(SearchEngineHosts.isEngineHost(null));
    }

    @Test
    public void recognisesGoogleObstaclePages() {
        assertTrue(SearchEngineHosts.isGoogleObstacleUrl("https://www.google.com/sorry/index?continue=x"));
        assertTrue(SearchEngineHosts.isGoogleObstacleUrl("https://consent.google.com/m?continue=x"));
        assertFalse(SearchEngineHosts.isGoogleObstacleUrl("https://www.google.com/goto?url=CAESAAAA"));
        assertFalse(SearchEngineHosts.isGoogleObstacleUrl("https://example.org/sorry/"));
    }

    @Test
    public void hostOfHandlesPortsCredentialsAndBareHosts() {
        assertEquals("www.google.com", SearchEngineHosts.hostOf("https://user:pw@www.google.com:443/x?y#z"));
        assertEquals("bing.com", SearchEngineHosts.hostOf("BING.com/search"));
        assertEquals("", SearchEngineHosts.hostOf(null));
    }
}
