package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;

import java.util.List;

/**
 * A browser session that runs one {@code web_search_prepare} for ONE priority group of Search → Engines: exactly the
 * named engines, every one of them. The Playwright session does it itself; the owner-thread actor in front of the
 * research browser's session hands the whole call to its owner thread, because the group is the session's per-call
 * state.
 */
interface EngineGroupSession {

    /**
     * Runs {@code call} (a web_search_prepare) with exactly {@code engineIds} as the engines of the search.
     *
     * @throws BrowserException an unknown engine id, a browser that searches only a fixed override, or the call's
     *                          own failure
     */
    <T> T withEngineGroup(List<String> engineIds, PlaywrightBrowserSession.BrowserCall<T> call)
            throws BrowserException;
}
