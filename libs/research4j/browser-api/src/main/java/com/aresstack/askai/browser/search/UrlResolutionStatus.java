package com.aresstack.askai.browser.search;

/**
 * Whether a search hit's URL is its verified target address (askai #41). A hit, its URL resolution and the
 * acceptance of its page as a research source are three separate facts:
 * <ul>
 * <li>every valid hit stays a candidate, whatever its resolution;</li>
 * <li>only {@link #DIRECT} and {@link #RESOLVED} URLs are verified target addresses;</li>
 * <li>an {@link #UNRESOLVED} hit (e.g. a Google {@code /goto} link) is still navigated by the browser, and only the
 *     page it actually lands on, its capture and the source check decide whether it becomes a source.</li>
 * </ul>
 */
public enum UrlResolutionStatus {
    /** The provider URL is no known redirect. */
    DIRECT,
    /** The provider URL was a redirect and has been resolved; the provider URL is kept as original. */
    RESOLVED,
    /** A redirect whose target is not known yet. Never a verified target, but still a candidate to navigate. */
    UNRESOLVED;

    public boolean isVerifiedTarget() {
        return this != UNRESOLVED;
    }
}
