package com.aresstack.serp4j.playwright.google;

import com.aresstack.serp4j.core.error.SearchErrorCategory;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.id.ProviderId;

/** HTTP status classification of the Google result page; obstacles and unknown pages use PlaywrightSearchFailures. */
public final class GoogleSearchFailures {

    private GoogleSearchFailures() {
    }

    /**
     * Classifies a non-200 HTTP status of the result page: 429 {@code RATE_LIMITED}, 403 {@code BLOCKED}, 5xx
     * {@code UNAVAILABLE}, other 4xx {@code RESPONSE_INVALID}.
     *
     * @return the failure, or {@code null} for statuses below 400
     */
    public static SearchProviderException forStatus(ProviderId provider, int status) {
        SearchErrorCategory category;
        if (status == 429) {
            category = SearchErrorCategory.RATE_LIMITED;
        } else if (status == 403) {
            category = SearchErrorCategory.BLOCKED;
        } else if (status >= 500) {
            category = SearchErrorCategory.UNAVAILABLE;
        } else if (status >= 400) {
            category = SearchErrorCategory.RESPONSE_INVALID;
        } else {
            return null;
        }
        return SearchProviderException.builder(provider, category, "Google answered HTTP " + status)
                .providerStatusCode(status).build();
    }
}
