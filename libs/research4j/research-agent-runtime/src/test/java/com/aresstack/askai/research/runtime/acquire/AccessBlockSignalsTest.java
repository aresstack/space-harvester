package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/** Terminal-block detection from the page text — domain-agnostic, narrow enough to avoid false positives. */
public class AccessBlockSignalsTest {

    private static BrowserPageReadiness page(String title, String excerpt) {
        return new BrowserPageReadiness("https://x/y", title, excerpt.length(), excerpt, false, "", false, "");
    }

    @Test
    public void detectsCloudflare1020() {
        assertEquals("CLOUDFLARE_1020", AccessBlockSignals.reason(page("Access denied",
                "You do not have access to this site. Error reference number: 1020. Cloudflare Ray ID: 9a.")));
    }

    @Test
    public void detectsAccessDeniedWithACloudflareContext() {
        assertEquals("ACCESS_DENIED", AccessBlockSignals.reason(page("Attention Required | Cloudflare",
                "Access denied. Performance & security by Cloudflare.")));
    }

    @Test
    public void detectsCloudflareAttentionRequiredInterstitial() {
        // mokosmart.com, live run 2026-11-09: stored as a NEW source with 700 characters of block boilerplate.
        assertEquals("CLOUDFLARE_BLOCKED", AccessBlockSignals.reason(page("Attention Required! | Cloudflare",
                "Sorry, you have been blocked. You are unable to access mokosmart.com. Why have I been blocked? "
                        + "Cloudflare Ray ID: 9c1 • Performance & security by Cloudflare")));
        assertNull("the phrase alone, without a Cloudflare context, is an article",
                AccessBlockSignals.reason(page("Attention required", "Attention required when working at height.")));
    }

    @Test
    public void doesNotFlagALegitimateArticleMentioningAccessDenied() {
        // A real article ABOUT access-control must not be misread as a block (no Cloudflare/1020 context).
        assertNull(AccessBlockSignals.reason(page("Understanding HTTP 403",
                "A 403 access denied response means the server refused the request for that resource.")));
    }

    @Test
    public void doesNotFlagNormalContent() {
        assertNull(AccessBlockSignals.reason(page("Smart glasses", "Smart glasses overlay text on the lens.")));
        assertNull(AccessBlockSignals.reason(page("", "")));
    }
}
