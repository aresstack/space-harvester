package com.aresstack.askai.browser.playwright;

import java.util.Map;

/**
 * Firefox prefs every Firefox launched over WebDriver BiDi adds to its own profile: Camoufox (on top of the
 * fingerprint prefs) and the installed Firefox (research4j#1). Only the application's own profiles get them, never
 * the user's regular Firefox profile; a value the launch profile already sets is kept.
 *
 * <ul>
 *   <li>{@code browser.sessionhistory.max_total_viewers = 0}: Playwright's BiDi layer misses history
 *       traversals restored from the back-forward cache (page.url() stays stale, goBack never settles;
 *       playwright4j#79, verified with the installed Firefox on 26 Nov 2026).</li>
 *   <li>{@code dom.confirm_repost.testing.always_accept = true}: without the bfcache, every history
 *       traversal or reload onto a page reached via POST (typically the result of a solved challenge
 *       form) opens Firefox's modal "resend data" prompt. That prompt is browser chrome, not a page
 *       dialog, so Playwright cannot dismiss it; it blocks the tab and every probe of it, and
 *       "Cancel" simply lets it reappear. Accepting it re-submits the same request the user already
 *       made, which is what the user ends up clicking anyway.</li>
 * </ul>
 */
final class FirefoxSessionPrefs {

    static final String NO_BFCACHE = "browser.sessionhistory.max_total_viewers";
    static final String ALWAYS_ACCEPT_REPOST = "dom.confirm_repost.testing.always_accept";

    private FirefoxSessionPrefs() {
    }

    /** Adds the session prefs without overriding a value the launch profile already sets. */
    static Map<String, Object> apply(Map<String, Object> prefs) {
        prefs.putIfAbsent(NO_BFCACHE, 0);
        prefs.putIfAbsent(ALWAYS_ACCEPT_REPOST, Boolean.TRUE);
        return prefs;
    }
}
