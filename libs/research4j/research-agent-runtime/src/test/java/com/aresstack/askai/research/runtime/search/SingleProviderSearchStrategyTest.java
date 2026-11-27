package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The API strategy maps provider hits onto the neutral candidate the reranker consumes: the URL is
 * normalized for navigation while the raw provider URL is kept as provenance, and there are no transit
 * hosts/challenges. Budget and cancellation are honoured before the provider is ever called.
 */
public class SingleProviderSearchStrategyTest {

    private static final class FakeProvider implements SearchProvider {
        SearchProviderRequest received;
        final List<SearchHit> hits = new ArrayList<SearchHit>();

        public SearchProviderId getProviderId() {
            return SearchProviderId.DATA_FOR_SEO;
        }

        public SearchProviderResult search(SearchProviderRequest request) {
            this.received = request;
            return new SearchProviderResult(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, hits);
        }

        public SearchProviderAvailability getAvailability() {
            return SearchProviderAvailability.AVAILABLE;
        }
    }

    private static final SearchBudgetGate ALLOW = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return true;
        }
    };
    private static final SearchBudgetGate DENY = new SearchBudgetGate() {
        public boolean beforeToolCall() {
            return false;
        }
    };

    @Test
    public void mapsHitsToNormalizedCandidatesWithNoTransitOrChallenges() {
        FakeProvider provider = new FakeProvider();
        provider.hits.add(new SearchHit(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, "wearables", 1,
                "https://shop.example/de/p.html?srsltid=Afm&page=2", "Shop", "buy wearables",
                "shop.example", 3, "2026-07-01 00:00:00 +00:00"));

        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("wearables", 10, "de", "de"), CancellationSignal.NONE,
                        ALLOW);

        assertEquals("the neutral request reaches the provider with the configured engine",
                SearchEngine.GOOGLE, provider.received.getSearchEngine());
        assertEquals("wearables", provider.received.getQuery());
        assertTrue("API results carry no search-engine transit host", result.providerHosts.isEmpty());
        assertTrue("API results carry no challenges", result.challenges.isEmpty());

        assertEquals(1, result.candidates.size());
        SearchResultCandidate candidate = result.candidates.get(0);
        assertEquals("navigated URL is normalized (srsltid stripped, page kept)",
                "https://shop.example/de/p.html?page=2", candidate.resolvedTargetUrl);
        assertEquals("raw provider URL kept as provenance",
                "https://shop.example/de/p.html?srsltid=Afm&page=2", candidate.rawSearchHref);
        assertEquals("Shop", candidate.title);
        assertEquals("buy wearables", candidate.snippet);
        assertEquals("shop.example", candidate.displayedDomain);
        assertEquals("organic rank is preserved", 1, candidate.originalRank);
    }

    @Test
    public void budgetDenialReturnsEmptyWithoutCallingTheProvider() {
        FakeProvider provider = new FakeProvider();
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("q", 10, null, null), CancellationSignal.NONE, DENY);
        assertTrue(result.candidates.isEmpty());
        assertEquals("provider must not be called when the budget is exhausted", null, provider.received);
    }

    @Test
    public void cancellationReturnsEmptyWithoutCallingTheProvider() {
        FakeProvider provider = new FakeProvider();
        CancellationSignal cancelled = new CancellationSignal() {
            public boolean isCancelled() {
                return true;
            }
        };
        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("q", 10, null, null), cancelled, ALLOW);
        assertTrue(result.candidates.isEmpty());
        assertEquals("provider must not be called after cancellation", null, provider.received);
    }

    @Test
    public void anUnresolvedRedirectStaysACandidateWithoutAVerifiedTarget() {
        // askai #41 (Angelo 02.11.): hit, URL resolution and source acceptance are separate facts.
        FakeProvider provider = new FakeProvider();
        provider.hits.add(new SearchHit(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, "pf4j", 1,
                "https://www.google.com/goto?url=CAESAAAA", "PF4J", "plugins", "", 1, null,
                UrlResolutionStatus.UNRESOLVED, "", "not_a_redirect"));
        provider.hits.add(new SearchHit(SearchProviderId.DATA_FOR_SEO, SearchEngine.GOOGLE, "pf4j", 2,
                "https://pf4j.org/doc", "Docs", "", "pf4j.org", 2, null,
                UrlResolutionStatus.RESOLVED, "https://www.google.com/goto?url=CAESBBBB", ""));

        InitialSearchResult result = new SingleProviderSearchStrategy(provider, SearchEngine.GOOGLE)
                .search(new InitialSearchRequest("pf4j", 10, "de", "de"), CancellationSignal.NONE, ALLOW);

        assertEquals("both hits stay candidates", 2, result.candidates.size());
        SearchResultCandidate unresolved = result.candidates.get(0);
        assertEquals(UrlResolutionStatus.UNRESOLVED, unresolved.urlResolution);
        assertEquals("no verified target address", "", unresolved.resolvedTargetUrl);
        assertFalse(unresolved.hasVerifiedTarget());
        assertEquals("the provider URL is kept", "https://www.google.com/goto?url=CAESAAAA",
                unresolved.rawSearchHref);
        assertEquals("https://www.google.com/goto?url=CAESAAAA", unresolved.originalUrl);
        assertEquals("the browser can still navigate it", "https://www.google.com/goto?url=CAESAAAA",
                unresolved.navigationUrl());
        assertEquals("", unresolved.displayedDomain);

        SearchResultCandidate resolved = result.candidates.get(1);
        assertEquals(UrlResolutionStatus.RESOLVED, resolved.urlResolution);
        assertTrue(resolved.hasVerifiedTarget());
        assertEquals("https://pf4j.org/doc", resolved.resolvedTargetUrl);
        assertEquals("https://www.google.com/goto?url=CAESBBBB", resolved.originalUrl);
        assertEquals("https://pf4j.org/doc", resolved.navigationUrl());
    }

    @Test
    public void theCandidatePoolKeepsAnUnresolvedHitAsUnverifiedCandidate() {
        SearchResultCandidate unresolved = new SearchResultCandidate("p#1", "", "",
                "https://www.google.com/goto?url=CAESAAAA", "PF4J", "", "", 1, "", "", 1.0, 1.0,
                java.util.Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList(), "",
                UrlResolutionStatus.UNRESOLVED, "https://www.google.com/goto?url=CAESAAAA");
        SearchResultCandidate direct = new SearchResultCandidate("p#2", "", "https://pf4j.org", "https://pf4j.org",
                "PF4J", "", "pf4j.org", 2, "", "", 1.0, 1.0,
                java.util.Collections.<com.aresstack.askai.browser.search.SearchResultSiteLink>emptyList());
        CandidatePool pool = new CandidatePool();

        assertEquals(2, pool.add(java.util.Arrays.asList(unresolved, direct), "google", 1));

        assertFalse(pool.candidates().get(0).isTargetVerified());
        assertEquals("https://www.google.com/goto?url=CAESAAAA", pool.candidates().get(0).getNormalizedUrl());
        assertTrue(pool.candidates().get(1).isTargetVerified());
    }
}
