package com.aresstack.askai.research.runtime.acquire;

/**
 * Layer 2 semantic readiness: how well a visited page's own text matches what the SERP result PROMISED
 * (its title + snippet). An embedding-backed implementation returns a cosine-like score in {@code [0,1]};
 * when no embedder is wired (or a text is empty) it returns {@code NaN} = "no opinion", and the deterministic
 * verdict stands unchanged. This is an ADDITIVE safety net over the DOM signals, never a replacement.
 */
public interface PageContentSimilarity {

    /**
     * @param expectedTitleAndSnippet the SERP anchor (result title + snippet) for the page being judged
     * @param pageTitleAndExcerpt     the page's own title + a representative text excerpt
     * @return a similarity in {@code [0,1]}, or {@code NaN} when unavailable / not computable
     */
    double score(String expectedTitleAndSnippet, String pageTitleAndExcerpt);

    /** The default: no embedder wired → no opinion, so the deterministic readiness verdict is never overridden. */
    PageContentSimilarity NONE = new PageContentSimilarity() {
        public double score(String expectedTitleAndSnippet, String pageTitleAndExcerpt) {
            return Double.NaN;
        }
    };
}
