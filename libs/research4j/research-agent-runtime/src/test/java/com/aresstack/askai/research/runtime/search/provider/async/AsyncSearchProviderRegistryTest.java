package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Registry lifecycle only, with an injected {@link AsyncSearchGenerationFactory} stub and fake providers —
 * no HTTP, no real module. Verifies build-then-swap, in-flight leasing, global-vs-single-provider failure,
 * idempotent close, and closed-registry rejection.
 */
public final class AsyncSearchProviderRegistryTest {

    private static SearchProviderRequest request() {
        return new SearchProviderRequest("query", SearchEngine.BRAVE, 10, "de", "DE");
    }

    @Test
    public void successfulReloadRoutesNewRequestsToTheNewGeneration() {
        RecordingProvider first = new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API);
        RecordingProvider second = new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API);
        StubFactory factory = new StubFactory()
                .enqueue(generation(first))
                .enqueue(generation(second));
        AsyncSearchProviderRegistry registry = new AsyncSearchProviderRegistry(factory);

        SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
        brave.search(request());
        assertEquals(1, first.calls.get());

        registry.reload();
        brave.search(request());
        assertEquals("old generation is no longer used", 1, first.calls.get());
        assertEquals("the reloaded generation serves new searches", 1, second.calls.get());
        registry.close();
    }

    @Test
    public void anInFlightSearchKeepsTheOldGenerationOpenUntilItCompletes() throws Exception {
        BlockingProvider blocking = new BlockingProvider(SearchProviderId.BRAVE_SEARCH_API);
        RecordingGeneration oldGen = generation(blocking);
        StubFactory factory = new StubFactory()
                .enqueue(oldGen)
                .enqueue(generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API)));
        AsyncSearchProviderRegistry registry = new AsyncSearchProviderRegistry(factory);

        SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                brave.search(request());
            }
        });
        worker.start();
        assertTrue("search entered the provider", blocking.entered.await(2, TimeUnit.SECONDS));

        registry.reload(); // swap while the old generation is leased
        assertEquals("a leased retired generation must not be closed", 0, oldGen.closeCount.get());

        blocking.proceed.countDown();
        worker.join(2_000);
        assertEquals("the retired generation closes once its last lease is released", 1, oldGen.closeCount.get());
        registry.close();
    }

    @Test
    public void aGlobalReloadFailureKeepsTheCurrentGenerationActive() {
        RecordingProvider live = new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API);
        RecordingGeneration liveGen = generation(live);
        StubFactory factory = new StubFactory()
                .enqueue(liveGen)
                .enqueueFailure(); // the reload's build fails globally
        AsyncSearchProviderRegistry registry = new AsyncSearchProviderRegistry(factory);

        try {
            registry.reload();
            fail("a global build failure must propagate");
        } catch (IllegalStateException expected) {
            // expected
        }
        assertEquals("nothing was closed", 0, liveGen.closeCount.get());
        registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API).search(request());
        assertEquals("the last good generation still serves", 1, live.calls.get());
        registry.close();
    }

    @Test
    public void anInvalidSingleProviderDisablesOnlyThatProvider() {
        // Brave absent (its config was invalid); Bright Data present.
        RecordingProvider brightData = new RecordingProvider(SearchProviderId.BRIGHT_DATA);
        StubFactory factory = new StubFactory().enqueue(generation(brightData));
        AsyncSearchProviderRegistry registry = new AsyncSearchProviderRegistry(factory);

        SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
        assertEquals(SearchProviderAvailability.NOT_CONFIGURED, brave.getAvailability());
        try {
            brave.search(request());
            fail("an unavailable provider must fail typed");
        } catch (SearchProviderConfigurationException expected) {
            assertEquals(SearchProviderId.BRAVE_SEARCH_API, expected.getProviderId());
        }

        SearchProvider live = registry.requireImplementedProvider(SearchProviderId.BRIGHT_DATA);
        assertEquals(SearchProviderAvailability.AVAILABLE, live.getAvailability());
        assertNotNull(live.search(request()));
        assertEquals(1, brightData.calls.get());
        registry.close();
    }

    @Test
    public void closeDuringASearchClosesExactlyOnceAfterItCompletes() throws Exception {
        BlockingProvider blocking = new BlockingProvider(SearchProviderId.BRAVE_SEARCH_API);
        RecordingGeneration gen = generation(blocking);
        AsyncSearchProviderRegistry registry =
                new AsyncSearchProviderRegistry(new StubFactory().enqueue(gen));

        SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
        Thread worker = new Thread(new Runnable() {
            public void run() {
                brave.search(request());
            }
        });
        worker.start();
        assertTrue(blocking.entered.await(2, TimeUnit.SECONDS));

        registry.close();
        assertEquals("a leased generation is not closed under an in-flight search", 0, gen.closeCount.get());

        blocking.proceed.countDown();
        worker.join(2_000);
        assertEquals("closed exactly once after the search finished", 1, gen.closeCount.get());
        registry.close(); // idempotent
        assertEquals(1, gen.closeCount.get());
    }

    @Test
    public void reloadProvidersReportsTypedOutcome() {
        StubFactory factory = new StubFactory()
                .enqueue(generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API)))
                .enqueue(generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API)))
                .enqueueFailure();
        AsyncSearchProviderRegistry registry = new AsyncSearchProviderRegistry(factory);
        assertEquals(AsyncSearchProviderRegistry.ProviderReloadOutcome.RELOADED,
                registry.reloadProviders());
        assertEquals(AsyncSearchProviderRegistry.ProviderReloadOutcome.RELOAD_FAILED_LAST_GOOD_RETAINED,
                registry.reloadProviders());
        // The last-good generation is still usable after a failed reload.
        registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API).search(request());
        registry.close();
    }

    @Test
    public void doubleCloseIsHarmless() {
        RecordingGeneration gen = generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API));
        AsyncSearchProviderRegistry registry =
                new AsyncSearchProviderRegistry(new StubFactory().enqueue(gen));
        registry.close();
        registry.close();
        assertEquals(1, gen.closeCount.get());
    }

    @Test
    public void reloadAndSearchAfterCloseAreTyped() {
        RecordingGeneration gen = generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API));
        AsyncSearchProviderRegistry registry =
                new AsyncSearchProviderRegistry(new StubFactory().enqueue(gen).enqueue(
                        generation(new RecordingProvider(SearchProviderId.BRAVE_SEARCH_API))));
        SearchProvider brave = registry.requireImplementedProvider(SearchProviderId.BRAVE_SEARCH_API);
        registry.close();

        try {
            registry.reload();
            fail("reload after close must throw");
        } catch (AsyncSearchRegistryClosedException expected) {
            // expected
        }
        try {
            brave.search(request());
            fail("search after close must throw");
        } catch (AsyncSearchRegistryClosedException expected) {
            // expected
        }
    }

    // ------------------------------------------------------------------ fakes

    private static RecordingGeneration generation(SearchProvider... providers) {
        Map<SearchProviderId, SearchProvider> byId =
                new EnumMap<SearchProviderId, SearchProvider>(SearchProviderId.class);
        for (SearchProvider provider : providers) {
            byId.put(provider.getProviderId(), provider);
        }
        return new RecordingGeneration(byId);
    }

    private static final class StubFactory implements AsyncSearchGenerationFactory {
        private final Deque<Object> queued = new ArrayDeque<Object>();

        StubFactory enqueue(AsyncSearchGeneration generation) {
            queued.add(generation);
            return this;
        }

        StubFactory enqueueFailure() {
            queued.add(new IllegalStateException("global open failure"));
            return this;
        }

        @Override
        public AsyncSearchGeneration open() {
            Object next = queued.poll();
            if (next instanceof RuntimeException) {
                throw (RuntimeException) next;
            }
            if (next == null) {
                throw new IllegalStateException("no generation queued");
            }
            return (AsyncSearchGeneration) next;
        }
    }

    private static final class RecordingGeneration implements AsyncSearchGeneration {
        private final Map<SearchProviderId, SearchProvider> providers;
        private final AtomicInteger closeCount = new AtomicInteger();

        RecordingGeneration(Map<SearchProviderId, SearchProvider> providers) {
            this.providers = providers;
        }

        @Override
        public SearchProvider provider(SearchProviderId id) {
            return providers.get(id);
        }

        @Override
        public Set<SearchProviderId> availableProviderIds() {
            return providers.isEmpty()
                    ? EnumSet.noneOf(SearchProviderId.class)
                    : EnumSet.copyOf(providers.keySet());
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static class RecordingProvider implements SearchProvider {
        private final SearchProviderId id;
        final AtomicInteger calls = new AtomicInteger();

        RecordingProvider(SearchProviderId id) {
            this.id = id;
        }

        @Override
        public SearchProviderId getProviderId() {
            return id;
        }

        @Override
        public SearchProviderAvailability getAvailability() {
            return SearchProviderAvailability.AVAILABLE;
        }

        @Override
        public SearchProviderResult search(SearchProviderRequest request) {
            calls.incrementAndGet();
            return new SearchProviderResult(id, SearchEngine.BRAVE,
                    Collections.<com.aresstack.askai.research.runtime.search.provider.SearchHit>emptyList());
        }
    }

    private static final class BlockingProvider extends RecordingProvider {
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch proceed = new CountDownLatch(1);

        BlockingProvider(SearchProviderId id) {
            super(id);
        }

        @Override
        public SearchProviderResult search(SearchProviderRequest request) {
            entered.countDown();
            try {
                proceed.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return super.search(request);
        }
    }
}
