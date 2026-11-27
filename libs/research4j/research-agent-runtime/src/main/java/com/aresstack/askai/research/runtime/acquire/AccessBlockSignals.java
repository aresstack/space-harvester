package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

import java.util.Locale;

/**
 * Detects a TERMINAL access block from a page's already-probed title/excerpt text — a hard "you cannot read this"
 * wall (Cloudflare {@code Error 1020} / "Access denied", geo/IP blocks) that has NOTHING to click or solve, as
 * opposed to a solvable interactive challenge ("Just a moment…", "verify you are human"). Domain-agnostic: it
 * keys on the block's own wording, never on a specific site. Returns a short reason code (for logging) or
 * {@code null} when the page is not hard-blocked.
 *
 * <p>Deliberately narrow to avoid false positives on legitimate articles that merely mention "access denied":
 * an unambiguous Cloudflare {@code 1020} marker, or "access denied" together with a Cloudflare/"you do not have
 * access" context.</p>
 */
final class AccessBlockSignals {

    private AccessBlockSignals() {
    }

    static String reason(BrowserPageReadiness probe) {
        if (probe == null) {
            return null;
        }
        String text = ((probe.title == null ? "" : probe.title) + " \n "
                + (probe.excerpt == null ? "" : probe.excerpt)).toLowerCase(Locale.ROOT);
        if (text.contains("error 1020") || text.contains("error reference number: 1020")) {
            return "CLOUDFLARE_1020";
        }
        boolean cloudflareContext = text.contains("cloudflare") || text.contains("ray id")
                || text.contains("you do not have access") || text.contains("performance & security");
        if (text.contains("access denied") && cloudflareContext) {
            return "ACCESS_DENIED";
        }
        // Cloudflare's "Attention Required!" interstitial (blocked request, "you have been blocked", "why have I
        // been blocked"): a block page with ~700 characters of boilerplate — never a source, nothing to solve.
        if (cloudflareContext && (text.contains("attention required") || text.contains("you have been blocked")
                || text.contains("sorry, you have been blocked"))) {
            return "CLOUDFLARE_BLOCKED";
        }
        // Other terminal Cloudflare/edge blocks (rate-limit / banned) — no interactive step either.
        if (text.contains("error 1015") && cloudflareContext) {
            return "CLOUDFLARE_1015";
        }
        return null;
    }

    static boolean isBlocked(BrowserPageReadiness probe) {
        return reason(probe) != null;
    }
}
