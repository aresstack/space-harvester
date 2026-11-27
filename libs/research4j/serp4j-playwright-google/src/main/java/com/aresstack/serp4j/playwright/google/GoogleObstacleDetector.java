package com.aresstack.serp4j.playwright.google;

import java.util.Locale;

/**
 * Classifies Google pages and redirect targets into {@link GoogleObstacle}s. Pure, no I/O, thread-safe.
 *
 * <p>The signals come from the S0 spike (docs/spikes/s0-google-http.md): a cookie-free {@code GET /search} answers
 * either with HTTP 302 to {@code consent.google.com} or with HTTP 200 and a page whose {@code noscript} block
 * refreshes to {@code /httpservice/retry/enablejs}. A page that contains organic results is never an obstacle, even
 * when a consent overlay is shown on top of it (as in the browser).</p>
 */
public final class GoogleObstacleDetector {

    private GoogleObstacleDetector() {
    }

    /**
     * @param url a redirect target or final page URL; may be {@code null}
     * @return the obstacle the URL points to, or {@link GoogleObstacle#NONE}
     */
    public static GoogleObstacle fromUrl(String url) {
        if (url == null) {
            return GoogleObstacle.NONE;
        }
        String u = url.toLowerCase(Locale.ROOT);
        if (u.contains("://consent.google.") || u.contains("://consent.youtube.")) {
            return GoogleObstacle.CONSENT;
        }
        if (u.contains("/sorry/") || u.contains("/sorry?")) {
            return GoogleObstacle.CHALLENGE;
        }
        if (u.contains("://accounts.google.")) {
            return GoogleObstacle.LOGIN;
        }
        if (u.contains("/httpservice/retry/enablejs")) {
            return GoogleObstacle.JAVASCRIPT_REQUIRED;
        }
        return GoogleObstacle.NONE;
    }

    /**
     * @param page parsed page
     * @return the obstacle of a page without results, or {@link GoogleObstacle#NONE} when results exist
     */
    public static GoogleObstacle fromPage(String finalUrl, GoogleSerpPage page) {
        GoogleObstacle byUrl = fromUrl(finalUrl);
        if (byUrl != GoogleObstacle.NONE && page.getOrganicResults().isEmpty()) {
            return byUrl;
        }
        return page.getOrganicResults().isEmpty() ? page.getPageObstacle() : GoogleObstacle.NONE;
    }

    /** Detects an obstacle in raw HTML; used by the parser. */
    static GoogleObstacle fromHtml(String html) {
        if (html == null) {
            return GoogleObstacle.NONE;
        }
        if (html.contains("/httpservice/retry/enablejs")) {
            return GoogleObstacle.JAVASCRIPT_REQUIRED;
        }
        if (html.contains("id=\"captcha-form\"") || html.contains("g-recaptcha") || html.contains("/sorry/index")) {
            return GoogleObstacle.CHALLENGE;
        }
        if (html.contains("consent.google.com")) {
            return GoogleObstacle.CONSENT;
        }
        return GoogleObstacle.NONE;
    }
}
