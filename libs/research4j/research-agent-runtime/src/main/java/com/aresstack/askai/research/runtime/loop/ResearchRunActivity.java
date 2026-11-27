package com.aresstack.askai.research.runtime.loop;

/**
 * What the loop is doing RIGHT NOW, as structured data: the stable activity token plus the search query,
 * the final post-redirect URL/host and the page title it applies to. This is the neutral progress context
 * that makes the live web browsing understandable in the UI — no pre-formatted human sentences here;
 * localization stays in the plugin.
 */
public final class ResearchRunActivity {

    public static final String SEARCHING = "SEARCHING";
    /** The initial search runs over a REST provider — NO browser is involved in this step. */
    public static final String SEARCHING_API = "SEARCHING_API";
    public static final String READING_PAGE = "READING_PAGE";
    public static final String SOURCE_ACCEPTED = "SOURCE_ACCEPTED";
    public static final String PAGE_SKIPPED = "PAGE_SKIPPED";
    /** Only challenge-bound work is left: the run waits for the user's manual input in the browser. */
    public static final String WAITING_FOR_USER = "WAITING_FOR_USER";

    private final String token;
    private final String searchQuery;
    private final String url;
    private final String host;
    private final String pageTitle;

    private ResearchRunActivity(String token, String searchQuery, String url, String host, String pageTitle) {
        this.token = token == null ? "" : token;
        this.searchQuery = searchQuery == null ? "" : searchQuery;
        this.url = url == null ? "" : url;
        this.host = host == null ? "" : host;
        this.pageTitle = pageTitle == null ? "" : pageTitle;
    }

    /** About to run a web search with exactly this query (the query the user can recognize). */
    public static ResearchRunActivity searching(String query) {
        return new ResearchRunActivity(SEARCHING, query, null, null, null);
    }

    /**
     * About to query a REST search provider (NO browser in this step): the provider label travels in the
     * HOST field, so the UI can say "via DataForSEO" instead of showing a website.
     */
    public static ResearchRunActivity searchingViaApi(String query, String providerLabel) {
        return new ResearchRunActivity(SEARCHING_API, query, null, providerLabel, null);
    }

    /** A page was opened; url/host/title are the FINAL post-redirect values the browser reported. */
    public static ResearchRunActivity readingPage(String url, String host, String pageTitle) {
        return new ResearchRunActivity(READING_PAGE, null, url, host, pageTitle);
    }

    /** The current page was recorded as a source (host/title kept so the UI can show what was taken). */
    public static ResearchRunActivity sourceAccepted(String url, String host, String pageTitle) {
        return new ResearchRunActivity(SOURCE_ACCEPTED, null, url, host, pageTitle);
    }

    /** The current page was checked and found not relevant (host/title kept for the visible history). */
    public static ResearchRunActivity pageSkipped(String url, String host, String pageTitle) {
        return new ResearchRunActivity(PAGE_SKIPPED, null, url, host, pageTitle);
    }

    /** The run only has challenge-bound work left and waits for the user (host = the challenged family). */
    public static ResearchRunActivity waitingForUser(String domainFamily, String url) {
        return new ResearchRunActivity(WAITING_FOR_USER, null, url, domainFamily, null);
    }

    public String getToken() {
        return token;
    }

    public String getSearchQuery() {
        return searchQuery;
    }

    public String getUrl() {
        return url;
    }

    public String getHost() {
        return host;
    }

    public String getPageTitle() {
        return pageTitle;
    }
}
