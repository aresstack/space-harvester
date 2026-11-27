package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.browser.search.UrlResolutionStatus;
import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchHit;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAuthenticationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRateLimitException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResponseException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderTemporaryException;

import com.aresstack.askai.research.search.api.WebSearchHit;
import com.aresstack.askai.research.search.api.WebSearchException;
import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.api.WebSearchRequest;
import com.aresstack.askai.research.search.api.WebSearchResult;
import com.aresstack.askai.research.search.http.HttpResponseException;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Adapter contract only: the thin bridge from the async module to the runtime seam is driven by a fake
 * {@link WebSearchProvider} (no HTTP). Provider-level HTTP/auth/JSON contracts live in the
 * {@code web-search-providers} module tests and are deliberately NOT repeated here.
 */
public final class AsyncModuleSearchProviderTest {

    private static final SearchProviderId RUNTIME_ID = SearchProviderId.BRAVE_SEARCH_API;
    private static final com.aresstack.askai.research.search.api.SearchProviderId MODULE_ID =
            com.aresstack.askai.research.search.api.SearchProviderId.BRAVE;
    private static final com.aresstack.askai.research.search.api.SearchEngine MODULE_ENGINE =
            com.aresstack.askai.research.search.api.SearchEngine.BRAVE;

    private SearchProviderRequest request() {
        return new SearchProviderRequest("wearable research", SearchEngine.BRAVE, 20, "de", "DE");
    }

    private AsyncModuleSearchProvider adapter(FakeProvider fake, long timeoutMillis) {
        return new AsyncModuleSearchProvider(RUNTIME_ID, SearchEngine.BRAVE, fake, timeoutMillis);
    }

    @Test
    public void mapsHitsPreservingTitleUrlSnippetRankAndDerivesDomain() {
        WebSearchHit hit = new WebSearchHit(MODULE_ID, MODULE_ENGINE, 3,
                "PF4J plugin framework", "https://pf4j.org/docs/plugins", "A lightweight plugin framework.");
        FakeProvider fake = FakeProvider.completedWith(new WebSearchResult(MODULE_ID, MODULE_ENGINE,
                Collections.singletonList(hit), "{}"));

        SearchProviderResult result = adapter(fake, 5_000).search(request());

        assertEquals(RUNTIME_ID, result.getProviderId());
        assertEquals(SearchEngine.BRAVE, result.getSearchEngine());
        assertEquals(1, result.getHits().size());
        SearchHit mapped = result.getHits().get(0);
        assertEquals("PF4J plugin framework", mapped.getTitle());
        assertEquals("https://pf4j.org/docs/plugins", mapped.getUrl());
        assertEquals("A lightweight plugin framework.", mapped.getSnippet());
        assertEquals(3, mapped.getRank());
        assertEquals("pf4j.org", mapped.getDomain());
        assertEquals(RUNTIME_ID, mapped.getProviderId());
        assertEquals(SearchEngine.BRAVE, mapped.getSearchEngine());
    }

    @Test
    public void unresolvedGotoRedirectsStayCandidatesWithTheirStatusAndProviderUrl() {
        // askai #41 (Angelo 02.11.): a valid hit is never dropped because its redirect is unresolved.
        WebSearchHit unresolved = new WebSearchHit(MODULE_ID, MODULE_ENGINE, 1, "Elastic Search API",
                "https://www.google.com/goto?url=CAESAAAA", "", com.aresstack.askai.research.search.api.UrlResolution.UNRESOLVED,
                null, "timeout");
        WebSearchHit direct = new WebSearchHit(MODULE_ID, MODULE_ENGINE, 2,
                "PF4J", "https://pf4j.org/", "");
        FakeProvider fake = FakeProvider.completedWith(new WebSearchResult(MODULE_ID, MODULE_ENGINE,
                java.util.Arrays.asList(unresolved, direct), "{}"));

        SearchProviderResult result = adapter(fake, 5_000).search(request());

        assertEquals("no hit is removed", 2, result.getHits().size());
        SearchHit kept = result.getHits().get(0);
        assertEquals(UrlResolutionStatus.UNRESOLVED, kept.getUrlResolution());
        assertEquals("https://www.google.com/goto?url=CAESAAAA", kept.getUrl()); // the provider URL is kept
        assertEquals("never classified by the redirect host", "", kept.getDomain());
        assertEquals("timeout", kept.getResolutionDetail());
        assertEquals(UrlResolutionStatus.DIRECT, result.getHits().get(1).getUrlResolution());
        assertEquals("pf4j.org", result.getHits().get(1).getDomain());
        assertEquals(1, result.getUnresolvedRedirects().size());
        assertTrue(result.getUnresolvedRedirects().get(0).contains("timeout"));
        assertTrue(result.getUnresolvedRedirects().get(0).contains("kept as candidate"));
    }

    @Test
    public void aResolvedRedirectKeepsItsOriginalProviderUrl() {
        WebSearchHit resolved = new WebSearchHit(MODULE_ID, MODULE_ENGINE, 1, "PF4J", "https://pf4j.org/", "",
                com.aresstack.askai.research.search.api.UrlResolution.RESOLVED,
                "https://www.google.com/goto?url=CAESBBBB", null);
        FakeProvider fake = FakeProvider.completedWith(new WebSearchResult(MODULE_ID, MODULE_ENGINE,
                java.util.Collections.singletonList(resolved), "{}"));

        SearchHit hit = adapter(fake, 5_000).search(request()).getHits().get(0);

        assertEquals(UrlResolutionStatus.RESOLVED, hit.getUrlResolution());
        assertEquals("https://pf4j.org/", hit.getUrl());
        assertEquals("https://www.google.com/goto?url=CAESBBBB", hit.getOriginalUrl());
        assertEquals("pf4j.org", hit.getDomain());
    }

    @Test
    public void http401IsAuthentication() {
        assertTranslates(new HttpResponseException(401, "denied"), SearchProviderAuthenticationException.class);
        assertTranslates(new HttpResponseException(403, "denied"), SearchProviderAuthenticationException.class);
    }

    @Test
    public void http429IsRateLimit() {
        assertTranslates(new HttpResponseException(429, "slow down"), SearchProviderRateLimitException.class);
    }

    @Test
    public void http5xxIsTemporary() {
        assertTranslates(new HttpResponseException(503, "unavailable"), SearchProviderTemporaryException.class);
    }

    @Test
    public void otherHttp4xxIsResponse() {
        assertTranslates(new HttpResponseException(400, "bad"), SearchProviderResponseException.class);
    }

    @Test
    public void webSearchExceptionIsResponse() {
        assertTranslates(new WebSearchException("unparseable body"), SearchProviderResponseException.class);
    }

    @Test
    public void illegalArgumentIsConfiguration() {
        assertTranslates(new IllegalArgumentException("apiKey missing"), SearchProviderConfigurationException.class);
    }

    @Test
    public void synchronousProviderFailureIsTranslated() {
        FakeProvider fake = FakeProvider.throwingSynchronously(new IllegalArgumentException("bad config"));
        try {
            adapter(fake, 5_000).search(request());
            fail("expected a configuration exception");
        } catch (SearchProviderConfigurationException expected) {
            assertFalse("no secret text leaks", expected.getMessage().contains("bad config"));
        }
    }

    @Test
    public void timeoutCancelsTheFutureAndReportsTemporary() {
        FakeProvider fake = FakeProvider.neverCompletes();
        try {
            adapter(fake, 50).search(request());
            fail("expected a timeout");
        } catch (SearchProviderTemporaryException expected) {
            assertTrue(expected.getMessage().contains("timed out"));
        }
        assertTrue("the pending future is cancelled on timeout", fake.lastFuture().isCancelled());
    }

    @Test
    public void interruptionCancelsTheFutureAndRestoresTheFlag() {
        FakeProvider fake = FakeProvider.neverCompletes();
        Thread.currentThread().interrupt(); // simulate a cancelled worker
        try {
            adapter(fake, 5_000).search(request());
            fail("expected cancellation to surface");
        } catch (SearchProviderTemporaryException expected) {
            assertTrue("interrupt flag is restored", Thread.interrupted()); // clears it for later tests
        }
        assertTrue("the pending future is cancelled on interruption", fake.lastFuture().isCancelled());
    }

    private void assertTranslates(Throwable moduleFailure, Class<?> expectedRuntimeException) {
        CompletableFuture<WebSearchResult> failed = new CompletableFuture<WebSearchResult>();
        failed.completeExceptionally(moduleFailure);
        try {
            adapter(FakeProvider.completedWith(failed), 5_000).search(request());
            fail("expected " + expectedRuntimeException.getSimpleName());
        } catch (RuntimeException actual) {
            assertTrue("expected " + expectedRuntimeException.getSimpleName() + " but got "
                    + actual.getClass().getSimpleName(), expectedRuntimeException.isInstance(actual));
        }
    }

    /** A controllable {@link WebSearchProvider}: returns a supplied future, or throws before returning one. */
    private static final class FakeProvider implements WebSearchProvider {
        private final CompletableFuture<WebSearchResult> future;
        private final RuntimeException synchronousFailure;
        private CompletableFuture<WebSearchResult> lastFuture;
        private boolean closed;

        private FakeProvider(CompletableFuture<WebSearchResult> future, RuntimeException synchronousFailure) {
            this.future = future;
            this.synchronousFailure = synchronousFailure;
        }

        static FakeProvider completedWith(WebSearchResult result) {
            return new FakeProvider(CompletableFuture.completedFuture(result), null);
        }

        static FakeProvider completedWith(CompletableFuture<WebSearchResult> future) {
            return new FakeProvider(future, null);
        }

        static FakeProvider neverCompletes() {
            return new FakeProvider(new CompletableFuture<WebSearchResult>(), null);
        }

        static FakeProvider throwingSynchronously(RuntimeException failure) {
            return new FakeProvider(null, failure);
        }

        CompletableFuture<WebSearchResult> lastFuture() {
            return lastFuture;
        }

        @Override
        public com.aresstack.askai.research.search.api.SearchProviderId getProviderId() {
            return MODULE_ID;
        }

        @Override
        public boolean supports(com.aresstack.askai.research.search.api.SearchEngine searchEngine) {
            return searchEngine == MODULE_ENGINE;
        }

        @Override
        public CompletableFuture<WebSearchResult> search(WebSearchRequest request) {
            if (synchronousFailure != null) {
                throw synchronousFailure;
            }
            lastFuture = future;
            return future;
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    // Keeps the imports meaningful when the compiler prunes unused ones during refactors.
    @SuppressWarnings("unused")
    private static List<WebSearchHit> hits(WebSearchHit... values) {
        return Arrays.asList(values);
    }
}
