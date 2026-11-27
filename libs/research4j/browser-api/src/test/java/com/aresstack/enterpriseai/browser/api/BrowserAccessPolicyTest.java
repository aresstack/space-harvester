package com.aresstack.enterpriseai.browser.api;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class BrowserAccessPolicyTest {

    private static BrowserAccessPolicy policy(BrowserAccessPolicy.Mode mode, String allow, String block) {
        return new BrowserAccessPolicy(mode,
                allow == null ? Collections.<String>emptyList() : Arrays.asList(allow.split(",")),
                block == null ? Collections.<String>emptyList() : Arrays.asList(block.split(",")));
    }

    @Test
    public void exactRuleMatchesOnlyThatHost() {
        BrowserAccessPolicy p = policy(BrowserAccessPolicy.Mode.BLOCK_BY_DEFAULT, " Example.ORG. ", null);
        assertEquals(BrowserAccessPolicy.Verdict.ALLOWED, p.decide("https://example.org/x"));
        assertEquals(BrowserAccessPolicy.Verdict.UNKNOWN, p.decide("https://www.example.org/"));
        assertEquals(BrowserAccessPolicy.Verdict.UNKNOWN, p.decide("https://evil-example.org/"));
    }

    @Test
    public void wildcardMatchesSubdomainsOnly() {
        BrowserAccessPolicy p = policy(BrowserAccessPolicy.Mode.BLOCK_BY_DEFAULT, "*.example.org", null);
        assertEquals(BrowserAccessPolicy.Verdict.ALLOWED, p.decide("http://a.b.example.org:8080/"));
        assertEquals(BrowserAccessPolicy.Verdict.UNKNOWN, p.decide("https://example.org/"));
        assertEquals(BrowserAccessPolicy.Verdict.UNKNOWN, p.decide("https://evil-example.org/"));
    }

    @Test
    public void blockListWins() {
        BrowserAccessPolicy p = policy(BrowserAccessPolicy.Mode.ALLOW_BY_DEFAULT, "example.org", "example.org");
        assertEquals(BrowserAccessPolicy.Verdict.BLOCKED, p.decide("https://example.org/"));
        assertEquals(BrowserAccessPolicy.Verdict.ALLOWED, p.decide("https://other.org/"));
    }

    @Test
    public void invalidRulesAreIgnoredAndOtherSchemesAllowed() {
        BrowserAccessPolicy p = policy(BrowserAccessPolicy.Mode.BLOCK_BY_DEFAULT,
                "https://a.org,a.org/path,a.org:80,a b.org,ok.org", null);
        assertEquals(Collections.singletonList("ok.org"), p.allow());
        assertEquals(BrowserAccessPolicy.Verdict.ALLOWED, p.decide("about:blank"));
        assertEquals(BrowserAccessPolicy.Verdict.UNKNOWN, BrowserAccessPolicy.defaults().decide("https://x.org"));
        assertNull(BrowserAccessPolicy.hostOf("about:blank"));
        assertEquals("xn--bcher-kva.de", BrowserAccessPolicy.hostOf("https://xn--bcher-kva.de/"));
        assertNull(BrowserAccessPolicy.hostOf("https://bücher.de/"));
        assertEquals(BrowserAccessPolicy.Verdict.BLOCKED, p.decide("https://bücher.de/"));
    }
}
