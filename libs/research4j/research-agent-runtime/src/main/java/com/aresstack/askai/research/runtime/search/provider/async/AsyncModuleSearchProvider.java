package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRateLimitException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderTemporaryException;

import com.aresstack.askai.research.search.api.UrlResolution;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.http.HttpResponseException;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bridges the async {@code web-search-providers} module to the runtime's synchronous
 * {@link SearchProvider} seam. It holds a single module {@link WebSearchProvider} for one runtime
 * {@link SearchProviderId}/{@link SearchEngine} and blocks on the returned {@link CompletableFuture} with a
 * configured timeout — on the RESEARCH WORKER thread only (the same thread the old blocking providers ran
 * on); it never touches the Swing EDT, the agent/MCP transport thread, or the AsyncHttpClient's I/O threads.
 *
 * <p>Module hits ({@code rank/title/url/snippet}) map to runtime {@link SearchHit}s with the domain derived
 * from the URL host. Module failures are translated to the runtime's typed exceptions by transport shape,
 * with deliberately secret-free messages (the cause is chained for diagnostics but its text is never copied
 * into a new message).</p>
 */
public final class AsyncModuleSearchProvider implements SearchProvider {

    /** Module {@link WebSearchRequest} caps {@code maximumResults} at 200; clamp to stay inside the range. */
    private static final int MAX_RESULTS = 200;

    private final SearchProviderId providerId;
    private final SearchEngine searchEngine;
    private final WebSearchProvider delegate;
    private final long timeoutMillis;

    public AsyncModuleSearchProvider(SearchProviderId providerId, SearchEngine searchEngine,
                                     WebSearchProvider delegate, long timeoutMillis) {
        if (providerId == null) {
            throw new IllegalArgumentException("providerId must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("searchEngine must not be null");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.providerId = providerId;
        this.searchEngine = searchEngine;
        this.delegate = delegate;
        this.timeoutMillis = timeoutMillis;
    }

    @Override
    public SearchProviderId getProviderId() {
        return providerId;
    }

    @Override
    public SearchProviderAvailability getAvailability() {
        return SearchProviderAvailability.AVAILABLE;
    }

    @Override
    public SearchProviderResult search(SearchProviderRequest request) {
        WebSearchRequest moduleRequest = WebSearchRequest.builder(request.getQuery())
                .countryCode(request.getCountry())
                .languageCode(request.getLanguage())
                .maximumResults(clampResultCount(request.getRequestedResultCount()))
                .offset(request.getOffset())
                .build();

        CompletableFuture<WebSearchResult> future;
        try {
            future = delegate.search(moduleRequest);
        } catch (RuntimeException synchronousFailure) {
            // A provider may reject the request before returning a future (e.g. invalid configuration).
            throw translate(synchronousFailure);
        }

        try {
            WebSearchResult result = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
            return toRuntimeResult(request, result);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new SearchProviderTemporaryException(providerId,
                    "Search timed out after " + timeoutMillis + " ms", timeout);
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new SearchProviderTemporaryException(providerId, "Search was cancelled", interrupted);
        } catch (ExecutionException executionFailure) {
            Throwable cause = executionFailure.getCause() == null
                    ? executionFailure : executionFailure.getCause();
            throw translate(cause);
        }
    }

    private static int clampResultCount(int requested) {
        if (requested < 1) {
            return 1;
        }
        return Math.min(requested, MAX_RESULTS);
    }

    private SearchProviderResult toRuntimeResult(SearchProviderRequest request, WebSearchResult result) {
        // The engine comes from the module result (Serp4J reports it per response and per item); the
        // configured default is only a fallback. Bright Data / DataForSEO are no longer always "GOOGLE".
        SearchEngine resultEngine = AsyncProviderMapping.toRuntimeEngine(result.getSearchEngine(), searchEngine);
        List<SearchHit> hits = new ArrayList<SearchHit>();
        List<String> unresolved = new ArrayList<String>();
        int index = 0;
        for (WebSearchHit hit : result.getHits()) {
            index++;
            int rank = hit.getRank() > 0 ? hit.getRank() : index;
            UrlResolutionStatus resolution = toStatus(hit.getUrlResolution());
            if (resolution == UrlResolutionStatus.UNRESOLVED) {
                // askai #41: an unresolved redirect (Google /goto) is a valid hit and STAYS a candidate; it is
                // only not a verified target. The browser navigates it later; the landing page decides.
                unresolved.add("rank " + rank + " \"" + hit.getTitle() + "\": redirect not resolved ("
                        + (hit.getResolutionDetail().isEmpty() ? "unknown" : hit.getResolutionDetail())
                        + "), kept as candidate");
            }
            SearchEngine hitEngine = AsyncProviderMapping.toRuntimeEngine(hit.getSearchEngine(), resultEngine);
            // An unresolved redirect is never classified by its redirect host (google.com).
            String domain = resolution == UrlResolutionStatus.UNRESOLVED ? "" : domainOf(hit.getUrl());
            hits.add(new SearchHit(providerId, hitEngine, request.getQuery(), rank, hit.getUrl(), hit.getTitle(),
                    hit.getSnippet(), domain, rank, null, resolution,
                    hit.getOriginalUrl() == null ? "" : hit.getOriginalUrl(), hit.getResolutionDetail()));
        }
        return new SearchProviderResult(providerId, resultEngine, hits, result.getRawResponse(), unresolved);
    }

    private static UrlResolutionStatus toStatus(UrlResolution resolution) {
        if (resolution == null) {
            return UrlResolutionStatus.DIRECT;
        }
        switch (resolution) {
            case RESOLVED:
                return UrlResolutionStatus.RESOLVED;
            case UNRESOLVED:
                return UrlResolutionStatus.UNRESOLVED;
            default:
                return UrlResolutionStatus.DIRECT;
        }
    }

    /** The host of {@code url}, or an empty string when it cannot be parsed — never throws. */
    private static String domainOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return host == null ? "" : host;
        } catch (RuntimeException notAUri) {
            return "";
        }
    }

    /**
     * Translate a module failure into the runtime's typed exception by transport shape. Messages are
     * synthesized here and never echo the cause's text, so no secret from a request/response can leak into
     * a runtime error; the original cause is chained for diagnostics only.
     */
    private SearchProviderException translate(Throwable cause) {
        if (cause instanceof SearchProviderException) {
            return (SearchProviderException) cause;
        }
        if (cause instanceof HttpResponseException) {
            int status = ((HttpResponseException) cause).getStatusCode();
            if (status == 401 || status == 403) {
                return new SearchProviderAuthenticationException(providerId,
                        "Authentication failed (HTTP " + status + ")");
            }
            if (status == 429) {
                return new SearchProviderRateLimitException(providerId, "Rate limit exceeded (HTTP 429)");
            }
            if (status >= 500) {
                return new SearchProviderTemporaryException(providerId,
                        "Upstream error (HTTP " + status + ")", cause);
            }
            return new SearchProviderResponseException(providerId, "Unexpected response (HTTP " + status + ")");
        }
        if (cause instanceof IllegalArgumentException) {
            // A provider rejected the request/configuration up front.
            return new SearchProviderConfigurationException(providerId,
                    "Provider is not usable with its current configuration");
        }
        if (cause instanceof WebSearchException || cause instanceof IllegalStateException) {
            // The module could not turn a response into a result (unparseable / unexpected shape).
            return new SearchProviderResponseException(providerId, "Provider returned an unusable response");
        }
        // Network / connection / low-level timeout: retryable.
        return new SearchProviderTemporaryException(providerId, "Search request failed", cause);
    }
}
