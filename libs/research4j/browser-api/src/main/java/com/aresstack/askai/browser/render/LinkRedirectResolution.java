package com.aresstack.askai.browser.render;

/** Outcome of the static search-redirect resolution for one captured link. */
public enum LinkRedirectResolution {
    /** No known redirect wrapper — the raw href IS the target. */
    NOT_A_REDIRECT,
    /** A known wrapper whose direct target was extracted; the target is the navigation candidate. */
    RESOLVED,
    /**
     * A known wrapper whose target could not be extracted — never classified by the wrapper host. It can still be a
     * result: its raw href is resolved later, one at a time, in the search browser (Google {@code /goto}).
     */
    UNRESOLVED,
    /**
     * A known wrapper whose target is neither in the URL nor on the page and that nothing resolves later (Baidu's
     * {@code /link?url=} without its result block's target): nothing to navigate, never a result, so an engine page
     * made of them delivers nothing and the next engine is asked.
     */
    DISCARDED
}
