package com.aresstack.askai.browser;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/** The URL gate: schemes, private/loopback blocking, explicit test allowance. */
public class UrlSafetyPolicyTest {

    private static void expectBlocked(UrlSafetyPolicy policy, String url) {
        try {
            policy.check(url);
            fail("must be blocked: " + url);
        } catch (BrowserException expected) {
            // ok
        }
    }

    @Test
    public void forbiddenSchemesAreRejected() {
        UrlSafetyPolicy policy = UrlSafetyPolicy.allowingPrivateNetworks();
        expectBlocked(policy, "file:///etc/passwd");
        expectBlocked(policy, "jar:file:///x.jar!/y");
        expectBlocked(policy, "data:text/html,<b>x</b>");
        expectBlocked(policy, "javascript:alert(1)");
        expectBlocked(policy, "ftp://example.com/x");
        expectBlocked(policy, "not a url");
        expectBlocked(policy, "");
    }

    @Test
    public void privateAndLoopbackBlockedByDefaultButAllowedExplicitly() throws Exception {
        expectBlocked(UrlSafetyPolicy.strict(), "http://127.0.0.1:8080/x");
        expectBlocked(UrlSafetyPolicy.strict(), "http://localhost/x");
        // Explicitly allowed (local test servers).
        assertEquals("127.0.0.1",
                UrlSafetyPolicy.allowingPrivateNetworks().check("http://127.0.0.1:8080/x").getHost());
    }

    @Test
    public void httpAndHttpsPassSchemeCheck() throws Exception {
        UrlSafetyPolicy policy = UrlSafetyPolicy.allowingPrivateNetworks();
        assertEquals("http", policy.check("http://127.0.0.1/x").getScheme());
        assertEquals("https", policy.check("https://127.0.0.1/y").getScheme());
    }

    @Test
    public void theHostGateDecidesAboutHostsButNotAboutSchemes() throws Exception {
        java.util.List<String> asked = new java.util.ArrayList<>();
        UrlSafetyPolicy policy = UrlSafetyPolicy.withHostGate(target -> {
            asked.add(target.getHost());
            if (target.getHost().endsWith(".verboten.example")) {
                throw new BrowserException("Nicht freigegeben: " + target.getHost());
            }
        });
        // An intranet name the public rule could not even resolve passes when the host admits it.
        assertEquals("wiki.intern.example", policy.check("https://wiki.intern.example/start").getHost());
        assertEquals("127.0.0.1", policy.check("http://127.0.0.1:8080/x").getHost());
        expectBlocked(policy, "https://seite.verboten.example/");
        // The scheme rule runs before the gate and is never handed to the host.
        expectBlocked(policy, "file:///etc/passwd");
        assertEquals(java.util.Arrays.asList("wiki.intern.example", "127.0.0.1", "seite.verboten.example"), asked);
    }

    @Test
    public void withoutAGateThePublicRuleStays() {
        expectBlocked(UrlSafetyPolicy.withHostGate(null), "http://127.0.0.1:8080/x");
    }
}
