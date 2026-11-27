package com.aresstack.askai.browser;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class UrlSafetyPolicyEngineTest {

    private final UrlSafetyPolicy worker = UrlSafetyPolicy.allowingPrivateNetworks()
            .refusingSearchEngines(Collections.singleton("www.serp-provider.example"));

    @Test
    public void workerPolicyRefusesEveryEngineHostTyped() {
        for (String url : new String[]{"https://www.google.com/search?q=x", "https://google.de/goto?url=y",
                "https://www.bing.com/ck/a?u=1", "https://html.duckduckgo.com/html?q=z",
                "https://serp-provider.example/api", "https://eu.serp-provider.example/x"}) {
            try {
                worker.check(url);
                fail("not refused: " + url);
            } catch (EngineHostRefusedException expected) {
                assertTrue(expected.getMessage().startsWith(EngineHostRefusedException.CODE));
            } catch (BrowserException other) {
                fail("untyped refusal for " + url + ": " + other.getMessage());
            }
        }
    }

    @Test
    public void workerPolicyLetsOrdinaryPagesThrough() throws Exception {
        assertEquals("www.researchgate.net", worker.check("https://www.researchgate.net/publication/1").getHost());
        assertEquals("googleblog.example", worker.check("https://googleblog.example/a").getHost());
        assertTrue(worker.isRefusingSearchEngines());
    }

    @Test
    public void chatAndSearchBrowserPolicyIsUnchanged() throws Exception {
        UrlSafetyPolicy plain = UrlSafetyPolicy.allowingPrivateNetworks();
        assertFalse(plain.isRefusingSearchEngines());
        assertEquals("www.google.com", plain.check("https://www.google.com/search?q=x").getHost());
    }
}
