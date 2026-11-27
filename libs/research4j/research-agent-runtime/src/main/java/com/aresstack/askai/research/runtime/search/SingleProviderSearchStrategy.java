package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.browser.search.SearchResultSiteLink;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The API strategy that uses exactly ONE configured {@link SearchProvider}. It turns the neutral request
 * into a {@link SearchProviderRequest} for the configured {@link SearchEngine}, calls the provider once,
 * normalizes each organic hit's URL and maps it onto the same neutral {@link SearchResultCandidate} the
 * existing reranker consumes — so from the returned {@link InitialSearchResult} onward the loop is identical
 * to the browser path. It never falls back to another provider or to the browser: a provider failure
 * propagates as a typed provider exception (surfaced visibly by the loop).
 *
 * <p>API results are DIRECT target URLs, so there are no transit provider hosts and no challenges — those
 * lists are always empty here.</p>
 */
public final class SingleProviderSearchStrategy implements SearchStrategy {

    private final SearchProvider provider;
    private final SearchEngine searchEngine;
    private final int resultPages;
    private final int pageSize;
    private final long delayMillis;

    public SingleProviderSearchStrategy(SearchProvider provider, SearchEngine searchEngine) {
        this(provider, searchEngine, 1, 10, 0L);
    }

    /**
     * @param resultPages how many result pages one search fetches (page n asks for offset n * pageSize)
     * @param pageSize    results per provider page (Google: about ten)
     * @param delayMillis pause before every page after the first (0 = none)
     */
    public SingleProviderSearchStrategy(SearchProvider provider, SearchEngine searchEngine, int resultPages,
                                        int pageSize, long delayMillis) {
        if (provider == null) {
            throw new IllegalArgumentException("provider must not be null");
        }
        if (searchEngine == null) {
            throw new IllegalArgumentException("searchEngine must not be null");
        }
        this.provider = provider;
        this.searchEngine = searchEngine;
        this.resultPages = Math.max(1, resultPages);
        this.pageSize = Math.max(1, pageSize);
        this.delayMillis = Math.max(0L, delayMillis);
    }

    /** Result pages one search fetches (always >= 1). */
    public int getResultPages() {
        return resultPages;
    }

    @Override
    public InitialSearchResult search(InitialSearchRequest request, CancellationSignal cancellation,
                                      SearchBudgetGate budget) {
        // A provider call is a budgeted external call; honour cancellation and the central budget first.
        if (cancellation != null && cancellation.isCancelled()) {
            return InitialSearchResult.empty(
                    Collections.singletonList("initial search cancelled before the provider call"));
        }
        if (!budget.beforeToolCall()) {
            return InitialSearchResult.empty(
                    Collections.singletonList("initial search budget exhausted before the provider call"));
        }

        List<SearchResultCandidate> candidates = new ArrayList<SearchResultCandidate>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        List<String> diagnostics = new ArrayList<String>();
        List<String> unresolved = new ArrayList<String>();
        for (int page = 0; page < resultPages; page++) {
            if (page > 0) {
                if (cancellation != null && cancellation.isCancelled()) {
                    diagnostics.add("result page " + (page + 1) + " skipped: search cancelled");
                    break;
                }
                if (!pause(cancellation)) {
                    break;
                }
                if (!budget.beforeToolCall()) {
                    diagnostics.add("result page " + (page + 1) + " skipped: search budget exhausted");
                    break;
                }
            }
            SearchProviderRequest providerRequest = new SearchProviderRequest(request.getQuery(), searchEngine,
                    request.getRequestedResultCount(), request.getLanguage(), request.getCountry(),
                    page * pageSize);
            SearchProviderResult providerResult;
            try {
                providerResult = provider.search(providerRequest);
            } catch (RuntimeException failure) {
                if (page == 0) {
                    throw failure;
                }
                // A later page failing keeps what the earlier pages delivered.
                diagnostics.add("result page " + (page + 1) + " failed: " + failure.getMessage());
                break;
            }
            int added = 0;
            for (SearchHit hit : providerResult.getHits()) {
                if (!seen.add(hit.getUrl())) {
                    continue;
                }
                candidates.add(toCandidate(hit, page));
                added++;
            }
            diagnostics.add(
                    "provider " + providerResult.getProviderId() + " / " + providerResult.getSearchEngine()
                            + (resultPages > 1 ? " page " + (page + 1) : "")
                            + " returned " + added + " organic candidate(s)"
                            + (providerResult.getRawResponse().isEmpty() ? ""
                            : ", raw response " + providerResult.getRawResponse().length() + " chars"));
            unresolved.addAll(providerResult.getUnresolvedRedirects());
            if (added == 0) {
                break; // no further results: the engine has nothing more for this query
            }
        }
        if (!unresolved.isEmpty()) {
            diagnostics.add(unresolved.size() + " of them with unresolved redirect: kept as candidates, not"
                    + " verified targets until the browser lands on their page");
            for (String line : unresolved) {
                diagnostics.add("unresolved redirect: " + line);
            }
        }
        return new InitialSearchResult(candidates, Collections.<String>emptyList(),
                Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                diagnostics);
    }

    /**
     * Map a provider hit onto the neutral candidate: the normalized URL is the verified target that gets navigated,
     * the raw provider URL is kept as provenance, and DOM-only fields are empty with full confidence (an API hit is a
     * certain, structured result, not an inferred SERP block). askai #41: an UNRESOLVED hit has NO verified target;
     * it stays a candidate with its provider URL in {@code rawSearchHref}, which the browser navigates later.
     */
    private boolean pause(CancellationSignal cancellation) {
        long remaining = delayMillis;
        while (remaining > 0) {
            if (cancellation != null && cancellation.isCancelled()) {
                return false;
            }
            long step = Math.min(remaining, 100L);
            try {
                Thread.sleep(step);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
            remaining -= step;
        }
        return true;
    }

    private static SearchResultCandidate toCandidate(SearchHit hit, int page) {
        boolean unresolved = hit.getUrlResolution() == UrlResolutionStatus.UNRESOLVED;
        String target = unresolved ? "" : SearchUrlNormalizer.normalize(hit.getUrl());
        String original = !hit.getOriginalUrl().isEmpty() ? hit.getOriginalUrl() : unresolved ? hit.getUrl() : "";
        return new SearchResultCandidate(
                hit.getProviderId() + "#" + (page > 0 ? (page + 1) + "." : "") + hit.getRank(),
                "",
                target,
                hit.getUrl(),
                hit.getTitle(),
                hit.getSnippet(),
                hit.getDomain(),
                hit.getRank(),
                "",
                "",
                1.0,
                1.0,
                Collections.<SearchResultSiteLink>emptyList(),
                "",
                hit.getUrlResolution(),
                original);
    }
}
