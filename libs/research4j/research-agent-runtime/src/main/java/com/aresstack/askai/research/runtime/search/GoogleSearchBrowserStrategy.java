package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.playwright.SearchBrowser;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.async.AsyncModuleSearchProvider;
import com.aresstack.askai.research.search.serp4j.Serp4jWebSearchProvider;
import com.aresstack.serp4j.playwright.google.GooglePlaywrightSearchProvider;

import java.util.Locale;

/**
 * The browser-based initial search after askai #31: Google through Serp4J ({@code serp4j-playwright-google}) on
 * AskAI's own search browser, called directly in this JVM. It replaces the old browser SERP path (MCP tools,
 * layout analysis) and runs through the same Serp4J adapter chain as the API providers, so error mapping and
 * candidates behave alike.
 */
public final class GoogleSearchBrowserStrategy {

    /** Upper bound of one Google search including a possible browser (re)start. */
    public static final long SEARCH_TIMEOUT_MILLIS = 90_000L;

    /**
     * Engine spacing waits one Google call can sit through in the search browser: a redirect hop queued ahead of
     * it, the call itself, and its retry after a browser restart. The call's time limit grows by that many delays,
     * so a long Google delay of Search → Engines never times a search out before it ran.
     */
    static final int SPACING_WAITS_PER_CALL = 3;

    /** Google lists about ten organic results per page and ignores {@code num}. */
    public static final int GOOGLE_PAGE_SIZE = 10;

    private GoogleSearchBrowserStrategy() {
    }

    /**
     * @param searchBrowser AskAI's search browser (owned by the caller)
     * @param locale        language and country of the Google result page; empty parts leave Google's default
     */
    public static SearchStrategy create(SearchBrowser searchBrowser, Locale locale) {
        return create(searchBrowser, locale, null);
    }

    /**
     * @param searchUrl another search endpoint than {@code https://www.google.com/search}, or {@code null}; only
     *                  for local test worlds that serve a Google-shaped result page
     */
    public static SearchStrategy create(SearchBrowser searchBrowser, Locale locale, String searchUrl) {
        return create(searchBrowser, locale, searchUrl, 1, 0L);
    }

    /**
     * @param resultPages Google result pages one search fetches in the search browser (page n = {@code start=n*10})
     * @param delayMillis the Google request delay of Search → Engines: the search browser keeps it between EVERY
     *                    request to the engine (each result page, each search, each redirect hop), for all chats
     */
    public static SearchStrategy create(SearchBrowser searchBrowser, Locale locale, String searchUrl,
                                        int resultPages, long delayMillis) {
        // askai #41 (lazy resolution): google.*/goto links stay as Google lists them; no request resolves them during
        // the search. The browser follows such a link when the research pipeline actually opens the hit.
        GooglePlaywrightSearchProvider.Builder google = GooglePlaywrightSearchProvider.builder();
        if (searchUrl != null && !searchUrl.trim().isEmpty()) {
            google.baseUrl(searchUrl.trim());
        }
        if (locale != null && !locale.getLanguage().isEmpty()) {
            google.defaultLanguage(locale.getLanguage());
        }
        if (locale != null && !locale.getCountry().isEmpty()) {
            google.defaultCountry(locale.getCountry());
        }
        searchBrowser.setEngineSpacingMillis(delayMillis);
        Serp4jWebSearchProvider web = new Serp4jWebSearchProvider(
                com.aresstack.askai.research.search.api.SearchProviderId.GOOGLE_BROWSER,
                com.aresstack.askai.research.search.api.SearchEngine.GOOGLE,
                searchBrowser.asSearchProvider(google.build()));
        return new SingleProviderSearchStrategy(new AsyncModuleSearchProvider(SearchProviderId.GOOGLE_BROWSER,
                SearchEngine.GOOGLE, web, callTimeoutMillis(delayMillis)), SearchEngine.GOOGLE,
                resultPages, GOOGLE_PAGE_SIZE, 0L); // the search browser keeps the delay, not each strategy
    }

    /** Time limit of one Google call: {@link #SEARCH_TIMEOUT_MILLIS} plus the spacing waits it may sit through. */
    static long callTimeoutMillis(long delayMillis) {
        return SEARCH_TIMEOUT_MILLIS + SPACING_WAITS_PER_CALL * Math.max(0L, delayMillis);
    }
}
