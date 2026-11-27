package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * ONE search engine as the user knows it — "DuckDuckGo", not "html.duckduckgo.com" and
 * "lite.duckduckgo.com". The technical endpoints are the engine's own business: they are alternative ways
 * to ask the SAME provider, tried in order until one answers, and a user ordering providers should never
 * have to order their transports.
 */
public final class BrowserSearchEngine {

    private final String id;
    private final String displayName;
    private final List<String> endpointTemplates;
    /**
     * Per endpoint (same index): the template for RESULT PAGE 2..n, or "" when that endpoint cannot
     * page. Placeholders: {query}, {page} (1-based), {offset0} ((page-1)*resultsPerPage) and
     * {offset1} (offset0+1) — engines address follow-up pages differently, so the math stays DATA in
     * the catalog, never engine-specific code.
     */
    private final List<String> nextPageTemplates;
    /** How many organic results one page of this engine carries (drives the offset placeholders). */
    private final int resultsPerPage;
    /**
     * Per endpoint (same index): how a person asks it through its regular start page, or empty for an engine that is
     * addressed by URL template (the dev/test {@code --search-url} override). See {@link SearchForm}.
     */
    private final List<SearchForm> forms;

    /**
     * How a person asks one endpoint (Argos' SearchFormInteraction, issue #22): open the engine's regular start page,
     * click into its search field, type the query key by key and press Enter, all as real input; follow-up result
     * pages by a real click on the engine's own "next" control. No result URL is ever built for such an endpoint:
     * opening a result URL directly made the engines answer with random, wrong result sets.
     */
    public static final class SearchForm {
        private final String homeUrl;
        private final List<String> searchBoxSelectors;
        private final List<String> nextPageSelectors;

        /**
         * @param homeUrl            the start page, opened without any query
         * @param searchBoxSelectors CSS selectors of the search field, tried in order (layout alternatives)
         * @param nextPageSelectors  CSS selectors of the "next result page" control, tried in order; empty = no paging
         */
        public SearchForm(String homeUrl, List<String> searchBoxSelectors, List<String> nextPageSelectors) {
            if (homeUrl == null || homeUrl.trim().isEmpty()) {
                throw new IllegalArgumentException("search form without start page");
            }
            if (searchBoxSelectors == null || searchBoxSelectors.isEmpty()) {
                throw new IllegalArgumentException("search form " + homeUrl + " has no search field selector");
            }
            this.homeUrl = homeUrl.trim();
            this.searchBoxSelectors = Collections.unmodifiableList(new ArrayList<String>(searchBoxSelectors));
            this.nextPageSelectors = Collections.unmodifiableList(new ArrayList<String>(
                    nextPageSelectors == null ? Collections.<String>emptyList() : nextPageSelectors));
        }

        public String getHomeUrl() {
            return homeUrl;
        }

        public List<String> getSearchBoxSelectors() {
            return searchBoxSelectors;
        }

        public List<String> getNextPageSelectors() {
            return nextPageSelectors;
        }
    }

    public BrowserSearchEngine(String id, String displayName, List<String> endpointTemplates) {
        this(id, displayName, endpointTemplates, java.util.Collections.<String>emptyList(), 10);
    }

    public BrowserSearchEngine(String id, String displayName, List<String> endpointTemplates,
                               List<String> nextPageTemplates, int resultsPerPage) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("engine id must not be empty");
        }
        if (endpointTemplates == null || endpointTemplates.isEmpty()) {
            throw new IllegalArgumentException("engine " + id + " has no endpoint template");
        }
        this.id = id.trim();
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? this.id : displayName.trim();
        this.endpointTemplates = Collections.unmodifiableList(
                new ArrayList<String>(endpointTemplates));
        this.nextPageTemplates = Collections.unmodifiableList(new ArrayList<String>(
                nextPageTemplates == null ? Collections.<String>emptyList() : nextPageTemplates));
        this.resultsPerPage = resultsPerPage > 0 ? resultsPerPage : 10;
        this.forms = Collections.<SearchForm>emptyList();
    }

    /**
     * An engine that is asked like a person asks it: one {@link SearchForm} per endpoint, in the order they are tried.
     * Its endpoint "templates" are the start pages (they name the host and the domain family), and it has no result
     * URL of any page: {@link #pageUrl} is null for every page.
     */
    public BrowserSearchEngine(String id, String displayName, List<SearchForm> forms, int resultsPerPage) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("engine id must not be empty");
        }
        if (forms == null || forms.isEmpty()) {
            throw new IllegalArgumentException("engine " + id + " has no search form");
        }
        this.id = id.trim();
        this.displayName = displayName == null || displayName.trim().isEmpty()
                ? this.id : displayName.trim();
        List<String> homes = new ArrayList<String>();
        for (SearchForm form : forms) {
            homes.add(form.getHomeUrl());
        }
        this.endpointTemplates = Collections.unmodifiableList(homes);
        this.nextPageTemplates = Collections.<String>emptyList();
        this.resultsPerPage = resultsPerPage > 0 ? resultsPerPage : 10;
        this.forms = Collections.unmodifiableList(new ArrayList<SearchForm>(forms));
    }

    /** The search form of the endpoint at {@code endpointIndex}, or null when that endpoint is a URL template. */
    public SearchForm formOf(int endpointIndex) {
        return endpointIndex >= 0 && endpointIndex < forms.size() ? forms.get(endpointIndex) : null;
    }

    /** True when this engine is asked through its start pages' search fields (never by a result URL). */
    public boolean usesSearchForms() {
        return !forms.isEmpty();
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** The engine's URL templates ({@code {query}} placeholder), in the order they are tried. */
    public List<String> getEndpointTemplates() {
        return endpointTemplates;
    }

    /**
     * The URL of result page {@code page} (1-based) for the endpoint at {@code endpointIndex}, with the
     * ALREADY-ENCODED query substituted — or {@code null} when this endpoint cannot deliver that page
     * (no pagination template). Page 1 is always the plain endpoint template. Always null for an engine that
     * {@link #usesSearchForms() uses search forms}.
     */
    public String pageUrl(int endpointIndex, String encodedQuery, int page) {
        if (usesSearchForms()) {
            return null; // asked through its search field; a result URL is never built
        }
        if (endpointIndex < 0 || endpointIndex >= endpointTemplates.size()) {
            return null;
        }
        if (page <= 1) {
            return endpointTemplates.get(endpointIndex).replace("{query}", encodedQuery);
        }
        if (endpointIndex >= nextPageTemplates.size()
                || nextPageTemplates.get(endpointIndex).trim().isEmpty()) {
            return null;
        }
        int offset0 = (page - 1) * resultsPerPage;
        return nextPageTemplates.get(endpointIndex)
                .replace("{query}", encodedQuery)
                .replace("{page}", String.valueOf(page))
                .replace("{offset0}", String.valueOf(offset0))
                .replace("{offset1}", String.valueOf(offset0 + 1));
    }

    @Override
    public String toString() {
        return displayName + endpointTemplates;
    }
}
