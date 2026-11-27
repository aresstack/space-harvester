package com.aresstack.askai.research.search.api;

/**
 * Whether a hit's URL is a usable source address (issue #41).
 *
 * <p>A search hit and its navigation target are separate facts: a provider may deliver a valid hit whose URL is
 * a search-engine redirect (Google {@code /goto?url=CAES...}). Only {@link #DIRECT} and {@link #RESOLVED} hits may
 * ever be treated as verified original sources; {@link #UNRESOLVED} hits stay diagnosable but are never
 * presented as an original source or classified by the redirect host.</p>
 */
public enum UrlResolution {
    /** The provider URL is no known redirect. */
    DIRECT,
    /** The provider URL was a redirect and has been resolved to its target; the original is kept separately. */
    RESOLVED,
    /** A known redirect whose target is unknown. Never a verified source. */
    UNRESOLVED;

    public boolean isUsableSource() {
        return this != UNRESOLVED;
    }
}
