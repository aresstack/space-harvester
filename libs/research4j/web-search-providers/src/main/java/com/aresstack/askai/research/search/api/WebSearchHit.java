package com.aresstack.askai.research.search.api;

public final class WebSearchHit {

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final int rank;
    private final String title;
    private final String url;
    private final String snippet;
    private final UrlResolution urlResolution;
    private final String originalUrl;
    private final String resolutionDetail;

    public WebSearchHit(
            SearchProviderId providerId,
            SearchEngine searchEngine,
            int rank,
            String title,
            String url,
            String snippet) {
        this(providerId, searchEngine, rank, title, url, snippet,
                SearchRedirectUrls.isOpaqueRedirect(url) ? UrlResolution.UNRESOLVED : UrlResolution.DIRECT,
                null, SearchRedirectUrls.isOpaqueRedirect(url) ? "not_attempted" : null);
    }

    /**
     * @param urlResolution    whether {@code url} is usable as a source address; a URL that is still an opaque
     *                         redirect is always {@link UrlResolution#UNRESOLVED}, whatever is passed
     * @param originalUrl      the provider URL before resolution, or {@code null}
     * @param resolutionDetail failure reason for unresolved hits (diagnostics only), or {@code null}
     */
    public WebSearchHit(
            SearchProviderId providerId,
            SearchEngine searchEngine,
            int rank,
            String title,
            String url,
            String snippet,
            UrlResolution urlResolution,
            String originalUrl,
            String resolutionDetail) {

        this.providerId = requireNonNull(providerId, "providerId");
        this.searchEngine = requireNonNull(searchEngine, "searchEngine");
        this.rank = requirePositive(rank, "rank");
        this.title = requireText(title, "title");
        this.url = requireText(url, "url");
        this.snippet = snippet == null ? "" : snippet;
        UrlResolution resolution = urlResolution == null ? UrlResolution.DIRECT : urlResolution;
        if (SearchRedirectUrls.isOpaqueRedirect(url)) {
            resolution = UrlResolution.UNRESOLVED; // a /goto URL is never a verified target
        }
        this.urlResolution = resolution;
        this.originalUrl = originalUrl == null || originalUrl.trim().isEmpty() ? url : originalUrl;
        this.resolutionDetail = resolutionDetail == null ? "" : resolutionDetail;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public SearchEngine getSearchEngine() {
        return searchEngine;
    }

    public int getRank() {
        return rank;
    }

    public String getTitle() {
        return title;
    }

    public String getUrl() {
        return url;
    }

    public String getSnippet() {
        return snippet;
    }

    public UrlResolution getUrlResolution() {
        return urlResolution;
    }

    /** The URL as delivered by the provider (equals {@link #getUrl()} unless a redirect was resolved). */
    public String getOriginalUrl() {
        return originalUrl;
    }

    /** Why an unresolved redirect could not be resolved, or an empty string. */
    public String getResolutionDetail() {
        return resolutionDetail;
    }

    private static <T> T requireNonNull(
            T value,
            String propertyName) {

        if (value == null) {
            throw new IllegalArgumentException(
                    propertyName + " must not be null");
        }
        return value;
    }

    private static String requireText(
            String value,
            String propertyName) {

        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(
                    propertyName + " must not be empty");
        }
        return value;
    }

    private static int requirePositive(
            int value,
            String propertyName) {

        if (value <= 0) {
            throw new IllegalArgumentException(
                    propertyName + " must be positive");
        }
        return value;
    }
}
