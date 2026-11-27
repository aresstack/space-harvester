package com.aresstack.askai.browser;

import java.util.List;

/**
 * The browser port behind the MCP web_* tools. One session per research session. Implementations differ in
 * capability and MUST be visibly selected via {@link BrowserBackendKind} — there is no silent fallback from
 * the Playwright sidecar to the static backend. Failures are reported as {@link BrowserException} (mapped to
 * tool errors), never as raw stack traces to the model.
 */
public interface BrowserSession {

    BrowserBackendKind getBackendKind();

    WebSearchResult search(String query) throws BrowserException;

    /**
     * A search with exactly these engines (catalog ids, in this order) for this one call; {@code null} = the engines
     * enabled in the settings. A wrong id is refused, never corrected or replaced by another engine. Backends without
     * engine selection search as {@link #search(String)} does when no selection is given and refuse one otherwise.
     */
    default WebSearchResult search(String query, List<String> engineIds) throws BrowserException {
        if (engineIds == null) {
            return search(query);
        }
        throw new BrowserException("This browser backend cannot choose search engines per call.");
    }

    BrowserPageSnapshot open(String url) throws BrowserException;

    BrowserPageSnapshot currentPage() throws BrowserException;

    List<BrowserLink> links() throws BrowserException;

    BrowserPageSnapshot follow(String linkId) throws BrowserException;

    BrowserPageSnapshot back() throws BrowserException;

    /**
     * Status of a pending manual challenge (CAPTCHA) parked in the browser, one typed line per state:
     * {@code CHALLENGE: <domain-family> <url>} while the user still has to solve it,
     * {@code RESOLVED: <domain-family>} exactly once after it disappeared, {@code NONE} otherwise.
     * Backends without challenge support report {@code NONE}.
     */
    default List<String> challengeStatus() {
        return java.util.Collections.singletonList("NONE");
    }

    /**
     * Navigate to {@code url} and return a cheap readability PROBE (not the full text): the signals needed to
     * decide whether the page can be read now or is blocked by a consent banner / manual challenge. Step 1 of
     * the two-step "scan then read" visit. The default navigates via {@link #open} and reports body size only
     * (no consent/challenge detection); the Playwright backend overrides it with real detection.
     */
    default BrowserPageReadiness probe(String url) throws BrowserException {
        open(url);
        return probeCurrent();
    }

    /** Re-probe the CURRENT page without navigating (after a consent click or while waiting for a CAPTCHA). */
    default BrowserPageReadiness probeCurrent() throws BrowserException {
        BrowserPageSnapshot s = currentPage();
        int len = s.getText() == null ? 0 : s.getText().length();
        return new BrowserPageReadiness(s.getUrl(), s.getTitle(), len,
                BrowserPageReadiness.excerptOf(s.getText()), false, "", false, "");
    }

    /**
     * Try to dismiss a consent/cookie banner on the CURRENT page by clicking one unambiguously positive
     * control. @return {@code "clicked:…"} when something was clicked, {@code "none"} otherwise. The default
     * (backends without consent handling) does nothing.
     */
    default String dismissConsent() throws BrowserException {
        return "none";
    }

    /**
     * Render the Research HUD overlay (pause/skip/status/countdown) onto the CURRENT visited page from a
     * serialized {@link com.aresstack.askai.browser.hud.ResearchHudState}. Returns {@code "rendered"} or
     * {@code "unsupported"}. The default (backends without an injectable overlay) does nothing.
     */
    default String renderHud(String stateLine) throws BrowserException {
        return "unsupported";
    }

    /**
     * Drain the HUD commands the user triggered in the overlay (newline-separated PAUSE/RESUME/SKIP/…), or an
     * empty string. The default reports none.
     */
    default String pollHudCommands() throws BrowserException {
        return "";
    }

    void close();
}
