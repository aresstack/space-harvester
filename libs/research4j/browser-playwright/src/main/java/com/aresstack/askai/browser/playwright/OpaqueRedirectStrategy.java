package com.aresstack.askai.browser.playwright;

/**
 * How AskAI treats a search engine's opaque result wrapper: a link whose target is not in its URL and is only known to
 * the engine (Google's {@code /goto?url=<token>}, see {@link SearchRedirectResolver#isOpaqueWrapper}). The one place a
 * later resolution strategy plugs in; whatever it returns is accepted only as an absolute http(s) URL that is not itself
 * such a wrapper, so the wrapper URL can never become a source.
 *
 * <p>The only strategy today is {@link #DISCARD}: the link stays {@code UNRESOLVED}, is never classified by the
 * engine's host and never becomes a result. Asking Google for each target ahead of time (the 302 of {@code /goto})
 * was tried live on 2026-10-31 and rejected: about 1.5 s per link through the browser, and after the first burst of
 * direct requests Google answered with a check page instead of a redirect. No automated clicks, no repeated
 * resolution attempts.</p>
 */
interface OpaqueRedirectStrategy {

    /** The wrapper's real target, or null when it stays unresolved. */
    String targetOf(String wrapperUrl);

    /** Leaves every opaque wrapper unresolved: no request, no click, no guess. */
    OpaqueRedirectStrategy DISCARD = wrapperUrl -> null;
}
