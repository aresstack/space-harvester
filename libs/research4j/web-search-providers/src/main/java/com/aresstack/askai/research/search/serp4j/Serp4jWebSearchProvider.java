package com.aresstack.askai.research.search.serp4j;

import com.aresstack.askai.research.search.api.SearchEngine;
import com.aresstack.askai.research.search.api.SearchProviderId;
import com.aresstack.askai.research.search.api.SearchRedirectUrls;
import com.aresstack.askai.research.search.api.UrlResolution;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.HttpResponseException;
import com.aresstack.serp4j.core.error.SearchProviderException;
import com.aresstack.serp4j.core.model.SearchLocale;
import com.aresstack.serp4j.core.model.SearchRequest;
import com.aresstack.serp4j.core.model.SearchResponse;
import com.aresstack.serp4j.core.model.data.ResultProvenance;
import com.aresstack.serp4j.core.model.data.SearchResultItem;
import com.aresstack.serp4j.core.model.data.SerpPosition;
import com.aresstack.serp4j.core.model.ext.ExtensionKey;
import com.aresstack.serp4j.core.model.ext.RawPayload;
import com.aresstack.serp4j.core.model.ext.RawPayloadPolicy;
import com.aresstack.serp4j.core.model.id.SearchEngineId;
import com.aresstack.serp4j.core.model.metadata.SearchMetadata;
import com.aresstack.serp4j.core.port.SearchContext;
import com.aresstack.serp4j.core.port.SearchProvider;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Adapter from a Serp4J {@link SearchProvider} to AskAI's {@link WebSearchProvider} seam (WP5 migration).
 *
 * <ul>
 *   <li>The configured AskAI engine is passed to Serp4J as {@link SearchEngineId}; the engine a hit is labelled
 *       with comes from the Serp4J response (per item provenance, else response metadata), never from a hard-coded
 *       default. This fixes the old "Bright Data / DataForSEO always GOOGLE" labelling.</li>
 *   <li>Requests ask for {@link RawPayloadPolicy#ALWAYS}; the provider's raw HTTP body is handed on as
 *       {@link WebSearchResult#getRawResponse()} (sensitive headers are already stripped by Serp4J).</li>
 *   <li>Serp4J error categories are translated to the shapes the AskAI runtime already understands
 *       (HTTP-status style for auth/rate limit/unavailable, {@link IllegalArgumentException} for configuration);
 *       the Serp4J exception stays chained as cause.</li>
 *   <li>{@link #close()} closes the Serp4J provider first (running searches end with {@code CANCELLED}) and then the
 *       resources this adapter owns, typically the HTTP transport (ADR-017: the host owns the transport).</li>
 * </ul>
 */
public final class Serp4jWebSearchProvider implements WebSearchProvider {

    /** Serp4J {@code ResultUrlEnricher.ORIGINAL_URL} (serp4j-http-api, Serp4J PR #25); same key, no extra module. */
    static final ExtensionKey<String> ORIGINAL_URL = ExtensionKey.of("serp4j", "original_url", String.class);
    /** Serp4J {@code ResultUrlEnricher.URL_RESOLUTION}: {@code resolved} or the failure reason. */
    static final ExtensionKey<String> URL_RESOLUTION = ExtensionKey.of("serp4j", "url_resolution", String.class);
    static final String RESOLVED = "resolved";

    private final SearchProviderId providerId;
    private final SearchEngine configuredEngine;
    private final SearchProvider delegate;
    private final AutoCloseable[] ownedResources;

    public Serp4jWebSearchProvider(SearchProviderId providerId, SearchEngine configuredEngine,
                                   SearchProvider delegate, AutoCloseable... ownedResources) {
        if (providerId == null) {
            throw new IllegalArgumentException("providerId must not be null");
        }
        if (configuredEngine == null) {
            throw new IllegalArgumentException("configuredEngine must not be null");
        }
        if (delegate == null) {
            throw new IllegalArgumentException("delegate must not be null");
        }
        this.providerId = providerId;
        this.configuredEngine = configuredEngine;
        this.delegate = delegate;
        this.ownedResources = ownedResources == null ? new AutoCloseable[0] : ownedResources.clone();
    }

    @Override
    public SearchProviderId getProviderId() {
        return providerId;
    }

    /** The engine this instance is configured for; Serp4J itself rejects engines it cannot serve. */
    @Override
    public boolean supports(SearchEngine searchEngine) {
        return configuredEngine == searchEngine;
    }

    public SearchEngine getConfiguredEngine() {
        return configuredEngine;
    }

    @Override
    public CompletableFuture<WebSearchResult> search(final WebSearchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        SearchRequest serpRequest = toSerp4j(request);
        CompletableFuture<SearchResponse> pending = delegate.search(serpRequest, SearchContext.defaults());
        final CompletableFuture<WebSearchResult> result = new CompletableFuture<WebSearchResult>();
        pending.whenComplete((response, failure) -> {
            if (failure != null) {
                result.completeExceptionally(translate(failure));
                return;
            }
            try {
                result.complete(toWebSearchResult(request, response));
            } catch (RuntimeException mappingFailure) {
                result.completeExceptionally(
                        new WebSearchException("Serp4J response could not be mapped", mappingFailure));
            }
        });
        // Cancelling the AskAI future (runtime timeout) propagates to the Serp4J future.
        result.whenComplete((ignored, failure) -> {
            if (result.isCancelled()) {
                pending.cancel(true);
            }
        });
        return result;
    }

    SearchRequest toSerp4j(WebSearchRequest request) {
        SearchRequest.Builder builder = SearchRequest.builder(request.getQuery())
                .engine(SearchEngineId.of(configuredEngine.getApiValue()))
                .offset(request.getOffset())
                .limit(request.getMaximumResults())
                .rawPayloadPolicy(RawPayloadPolicy.ALWAYS);
        SearchLocale locale = toLocale(request.getCountryCode(), request.getLanguageCode());
        if (locale != null) {
            builder.locale(locale);
        }
        return builder.build();
    }

    /** Builds a Serp4J locale; values Serp4J does not accept are dropped instead of failing the search. */
    private static SearchLocale toLocale(String country, String language) {
        SearchLocale.Builder builder = SearchLocale.builder();
        boolean any = false;
        if (country != null) {
            try {
                builder.countryCode(country.toUpperCase(Locale.ROOT));
                any = true;
            } catch (IllegalArgumentException unsupported) {
                // e.g. "ALL" or a free-text value from old configurations
            }
        }
        if (language != null) {
            try {
                builder.languageCode(language.toLowerCase(Locale.ROOT));
                any = true;
            } catch (IllegalArgumentException unsupported) {
                // ignored, see above
            }
        }
        return any ? builder.build() : null;
    }

    WebSearchResult toWebSearchResult(WebSearchRequest request, SearchResponse response) {
        Optional<SearchMetadata> metadata = response.getMetadata();
        SearchEngine responseEngine = configuredEngine;
        if (metadata.isPresent() && metadata.get().getEngine().isPresent()) {
            responseEngine = toAskAiEngine(metadata.get().getEngine().get(), configuredEngine);
        }

        // Organic results only: ads and other SERP elements must never become research sources (the runtime
        // treats every hit as an organic candidate). No organic results means an empty hit list.
        List<SearchResultItem> items = response.getData().getOrganicResults();
        List<WebSearchHit> hits = new ArrayList<WebSearchHit>(items.size());
        int index = request.getOffset();
        for (SearchResultItem item : items) {
            index++;
            String url = item.getUrl();
            if (url == null || url.trim().isEmpty()) {
                continue;
            }
            String title = item.getTitle().orElse("");
            if (title.trim().isEmpty()) {
                title = item.getDisplayUrl().orElse(url);
            }
            hits.add(toHit(item, url, title, itemEngine(item, responseEngine), rankOf(item, index)));
        }

        String raw = "";
        if (metadata.isPresent() && metadata.get().getRawPayload().isPresent()) {
            RawPayload payload = metadata.get().getRawPayload().get();
            raw = payload.getBodyAsText();
        }
        return new WebSearchResult(providerId, responseEngine, hits, raw);
    }

    /**
     * Issue #41: hit and navigation target are separate. A hit stays in the list even if its redirect could not be
     * resolved, but is then marked {@link UrlResolution#UNRESOLVED} and never becomes a verified source.
     */
    private WebSearchHit toHit(SearchResultItem item, String url, String title, SearchEngine engine, int rank) {
        Optional<String> status = item.getExtensions().get(URL_RESOLUTION);
        Optional<String> original = item.getExtensions().get(ORIGINAL_URL);
        UrlResolution resolution;
        String detail = null;
        if (status.isPresent()) {
            if (RESOLVED.equals(status.get()) && !SearchRedirectUrls.isOpaqueRedirect(url)) {
                resolution = UrlResolution.RESOLVED;
            } else {
                resolution = UrlResolution.UNRESOLVED;
                detail = status.get();
            }
        } else if (SearchRedirectUrls.isOpaqueRedirect(url)) {
            // Serp4J older than PR #25, resolution disabled, or a provider without resolver.
            resolution = UrlResolution.UNRESOLVED;
            detail = "not_attempted";
        } else {
            resolution = original.isPresent() && !original.get().equals(url)
                    ? UrlResolution.RESOLVED : UrlResolution.DIRECT;
        }
        return new WebSearchHit(providerId, engine, rank, title, url, item.getSnippet().orElse(""),
                resolution, original.orElse(null), detail);
    }

    private static int rankOf(SearchResultItem item, int fallback) {
        Optional<SerpPosition> position = item.getPosition();
        if (position.isPresent()) {
            if (position.get().getRankAbsolute().isPresent() && position.get().getRankAbsolute().getAsInt() > 0) {
                return position.get().getRankAbsolute().getAsInt();
            }
            if (position.get().getRankInGroup().isPresent() && position.get().getRankInGroup().getAsInt() > 0) {
                return position.get().getRankInGroup().getAsInt();
            }
        }
        return Math.max(1, fallback);
    }

    private static SearchEngine itemEngine(SearchResultItem item, SearchEngine fallback) {
        for (ResultProvenance provenance : item.getProvenance()) {
            return toAskAiEngine(provenance.getEngine(), fallback);
        }
        return fallback;
    }

    /** Maps a Serp4J engine code to AskAI's closed enum; unknown codes keep the fallback. */
    static SearchEngine toAskAiEngine(SearchEngineId engine, SearchEngine fallback) {
        if (engine == null) {
            return fallback;
        }
        for (SearchEngine candidate : SearchEngine.values()) {
            if (candidate != SearchEngine.MULTI && candidate.getApiValue().equalsIgnoreCase(engine.getCode())) {
                return candidate;
            }
        }
        return fallback;
    }

    /** Serp4J category → the failure shapes {@code AsyncModuleSearchProvider} translates today. */
    static Throwable translate(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        Optional<SearchProviderException> serp = SearchProviderException.unwrap(cause);
        if (!serp.isPresent()) {
            return cause;
        }
        SearchProviderException e = serp.get();
        Throwable mapped;
        switch (e.getCategory()) {
            case AUTHENTICATION:
                mapped = new HttpResponseException(e.getProviderStatusCode().orElse(401) == 403 ? 403 : 401, "");
                break;
            case RATE_LIMITED:
            case QUOTA_EXHAUSTED:
                mapped = new HttpResponseException(429, "");
                break;
            case UNAVAILABLE:
            case BLOCKED:
                mapped = new HttpResponseException(503, "");
                break;
            case CONFIGURATION:
            case INVALID_REQUEST:
            case UNSUPPORTED:
                mapped = new IllegalArgumentException("Serp4J rejected the request: " + e.getCategory());
                break;
            case RESPONSE_INVALID:
                mapped = new WebSearchException("Serp4J could not read the provider response");
                break;
            case CANCELLED:
                mapped = new CancellationException("Serp4J search was cancelled");
                break;
            default:
                // TIMEOUT, INTERNAL and session categories: retryable/temporary in the runtime.
                return e;
        }
        mapped.initCause(e);
        return mapped;
    }

    @Override
    public void close() {
        RuntimeException first = null;
        try {
            delegate.close();
        } catch (Exception failure) {
            first = failure instanceof RuntimeException ? (RuntimeException) failure : new IllegalStateException(failure);
        }
        for (AutoCloseable resource : ownedResources) {
            if (resource == null) {
                continue;
            }
            try {
                resource.close();
            } catch (Exception failure) {
                if (first == null) {
                    first = failure instanceof RuntimeException ? (RuntimeException) failure
                            : new IllegalStateException(failure);
                }
            }
        }
        if (first != null) {
            throw first;
        }
    }

    @Override
    public String toString() {
        return "Serp4jWebSearchProvider[" + providerId + ", engine=" + configuredEngine + ", " + delegate + "]";
    }
}
