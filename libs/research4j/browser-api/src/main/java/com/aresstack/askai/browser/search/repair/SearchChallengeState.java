package com.aresstack.askai.browser.search.repair;

/**
 * A typed manual-challenge (CAPTCHA) state carried WITH a prepared search — so the research runtime
 * locks the domain family and defers its urls exactly as it does today, without parsing a
 * {@code CHALLENGE:} text line. {@link #family} is the registrable domain the challenge is parked on;
 * {@link #url} is where the user solves it.
 */
public final class SearchChallengeState {

    public final String family;
    public final String url;

    public SearchChallengeState(String family, String url) {
        this.family = family == null ? "" : family;
        this.url = url == null ? "" : url;
    }
}
