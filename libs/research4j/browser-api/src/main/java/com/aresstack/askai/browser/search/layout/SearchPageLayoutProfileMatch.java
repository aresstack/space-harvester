package com.aresstack.askai.browser.search.layout;

/**
 * The result of a profile lookup: either a compatible {@link SearchPageLayoutProfile} was found or
 * not, with a short reason for diagnostics. A match is a candidate for reuse — it still has to
 * re-resolve against the current containers and re-validate before it may be applied.
 */
public final class SearchPageLayoutProfileMatch {

    public final boolean matched;
    public final SearchPageLayoutProfile profile;
    public final String reason;

    private SearchPageLayoutProfileMatch(boolean matched, SearchPageLayoutProfile profile,
                                         String reason) {
        this.matched = matched;
        this.profile = profile;
        this.reason = reason == null ? "" : reason;
    }

    public static SearchPageLayoutProfileMatch of(SearchPageLayoutProfile profile) {
        return new SearchPageLayoutProfileMatch(true, profile, "compatible profile found");
    }

    public static SearchPageLayoutProfileMatch none(String reason) {
        return new SearchPageLayoutProfileMatch(false, null, reason);
    }
}
