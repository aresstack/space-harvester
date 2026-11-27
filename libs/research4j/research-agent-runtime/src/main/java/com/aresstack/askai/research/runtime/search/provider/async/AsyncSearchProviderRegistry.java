package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderAvailability;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderConfigurationException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderDescriptor;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderImplementationStatus;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderNotImplementedException;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRegistry;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderRequest;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderResult;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The productive async {@link SearchProviderRegistry}: it fronts a live {@link AsyncSearchGeneration} for
 * the migrated providers (BRAVE / BRIGHT_DATA / DATA_FOR_SEO / SERP_API) and supports hot {@link #reload()} without
 * ever tearing an AsyncHttpClient out from under an in-flight search.
 *
 * <p>Lifecycle rules (all under one monitor):</p>
 * <ul>
 *   <li>A search leases the CURRENT generation for the duration of its call; the returned
 *       {@link SearchProvider} is a stable facade that re-resolves the current generation on every call, so
 *       a search started after a reload uses the new generation and one started before it keeps the old.</li>
 *   <li>{@link #reload()} builds the whole candidate generation FIRST; only on success does it atomically
 *       swap and RETIRE the old generation, which is closed once its last lease is released. A global build
 *       failure throws and leaves the current generation active (the candidate, if any, closes itself).</li>
 *   <li>{@link #close()} is idempotent; it retires the current generation (closed when its leases drain)
 *       and blocks any further search or reload with {@link AsyncSearchRegistryClosedException}. In-flight
 *       searches finish; there is no use-after-close.</li>
 * </ul>
 *
 * <p>The registry holds only provider facades and ids — never secrets or configuration DTOs — so nothing
 * sensitive can surface through {@link #toString()}, diagnostics or exceptions.</p>
 */
public final class AsyncSearchProviderRegistry implements SearchProviderRegistry, AutoCloseable {

    /** The provider ids served by the async module; every other catalogued id stays NOT_IMPLEMENTED here. */
    private static final Set<SearchProviderId> MIGRATED = EnumSet.of(
            SearchProviderId.BRAVE_SEARCH_API, SearchProviderId.BRIGHT_DATA, SearchProviderId.DATA_FOR_SEO,
            SearchProviderId.SERP_API);

    private final AsyncSearchGenerationFactory factory;
    private final Object lock = new Object();
    private Generation current;   // guarded by lock
    private boolean closed;       // guarded by lock

    /** Opens the initial generation eagerly; a global open failure propagates to the caller. */
    public AsyncSearchProviderRegistry(AsyncSearchGenerationFactory factory) {
        if (factory == null) {
            throw new IllegalArgumentException("factory must not be null");
        }
        this.factory = factory;
        this.current = new Generation(factory.open());
    }

    @Override
    public SearchProvider requireImplementedProvider(SearchProviderId providerId) {
        if (providerId == null) {
            throw new IllegalArgumentException("providerId must not be null");
        }
        if (!MIGRATED.contains(providerId)) {
            throw new SearchProviderNotImplementedException(providerId);
        }
        return new LeasedSearchProvider(providerId);
    }

    @Override
    public List<SearchProviderDescriptor> getDescriptors() {
        Set<SearchProviderId> available;
        synchronized (lock) {
            available = closed
                    ? EnumSet.<SearchProviderId>noneOf(SearchProviderId.class)
                    : current.content.availableProviderIds();
        }
        List<SearchProviderDescriptor> descriptors = new ArrayList<SearchProviderDescriptor>();
        for (SearchProviderId id : SearchProviderId.values()) {
            boolean implemented = MIGRATED.contains(id) && available.contains(id);
            descriptors.add(new SearchProviderDescriptor(id, id.name(),
                    implemented ? SearchProviderImplementationStatus.IMPLEMENTED
                            : SearchProviderImplementationStatus.NOT_IMPLEMENTED));
        }
        return descriptors;
    }

    /** Typed outcome of a best-effort provider reload (the host maps the no-agent case separately). */
    public enum ProviderReloadOutcome {
        RELOADED,
        RELOAD_FAILED_LAST_GOOD_RETAINED
    }

    /**
     * Best-effort reload for the host's {@code provider/reload} command: attempts {@link #reload()} and
     * reports {@link ProviderReloadOutcome#RELOADED} on success or
     * {@link ProviderReloadOutcome#RELOAD_FAILED_LAST_GOOD_RETAINED} when a global build failure left the
     * previous generation active. A closed registry is a hard lifecycle error and still throws.
     */
    public ProviderReloadOutcome reloadProviders() {
        try {
            reload();
            return ProviderReloadOutcome.RELOADED;
        } catch (AsyncSearchRegistryClosedException closed) {
            throw closed;
        } catch (RuntimeException buildFailure) {
            return ProviderReloadOutcome.RELOAD_FAILED_LAST_GOOD_RETAINED;
        }
    }

    /**
     * Build a new generation and swap it in atomically. A global build failure propagates and leaves the
     * current generation active; the old generation is retired and closed after its last in-flight lease.
     */
    public void reload() {
        synchronized (lock) {
            if (closed) {
                throw new AsyncSearchRegistryClosedException();
            }
        }
        AsyncSearchGeneration candidate = factory.open(); // outside the lock; a global failure just throws
        AsyncSearchGeneration toClose = null;
        try {
            synchronized (lock) {
                if (closed) {
                    throw new AsyncSearchRegistryClosedException();
                }
                Generation old = current;
                current = new Generation(candidate);
                candidate = null; // ownership transferred to the registry
                old.retired = true;
                if (old.leases == 0 && !old.closed) {
                    old.closed = true;
                    toClose = old.content;
                }
            }
        } finally {
            if (candidate != null) {
                closeQuietly(candidate); // swap did not happen (closed mid-reload): discard the candidate
            }
        }
        closeQuietly(toClose);
    }

    @Override
    public void close() {
        AsyncSearchGeneration toClose = null;
        synchronized (lock) {
            if (closed) {
                return; // idempotent
            }
            closed = true;
            current.retired = true;
            if (current.leases == 0 && !current.closed) {
                current.closed = true;
                toClose = current.content;
            }
        }
        closeQuietly(toClose);
    }

    @Override
    public String toString() {
        synchronized (lock) {
            return "AsyncSearchProviderRegistry[closed=" + closed + "]";
        }
    }

    // ------------------------------------------------------------------ leasing

    private Generation acquire() {
        synchronized (lock) {
            if (closed) {
                throw new AsyncSearchRegistryClosedException();
            }
            current.leases++;
            return current;
        }
    }

    private void release(Generation generation) {
        AsyncSearchGeneration toClose = null;
        synchronized (lock) {
            generation.leases--;
            if (generation.retired && generation.leases == 0 && !generation.closed) {
                generation.closed = true;
                toClose = generation.content;
            }
        }
        closeQuietly(toClose);
    }

    private boolean isAvailable(SearchProviderId providerId) {
        synchronized (lock) {
            return !closed && current.content.provider(providerId) != null;
        }
    }

    private static void closeQuietly(AsyncSearchGeneration generation) {
        if (generation == null) {
            return;
        }
        try {
            generation.close();
        } catch (RuntimeException ignored) {
            // a best-effort resource release must never mask the caller's outcome
        }
    }

    /** A live generation with its lease bookkeeping (all mutable fields guarded by the registry lock). */
    private static final class Generation {
        private final AsyncSearchGeneration content;
        private int leases;
        private boolean retired;
        private boolean closed;

        Generation(AsyncSearchGeneration content) {
            this.content = content;
        }
    }

    /** Stable per-id facade: re-resolves and leases the current generation on every search. */
    private final class LeasedSearchProvider implements SearchProvider {
        private final SearchProviderId providerId;

        LeasedSearchProvider(SearchProviderId providerId) {
            this.providerId = providerId;
        }

        @Override
        public SearchProviderId getProviderId() {
            return providerId;
        }

        @Override
        public SearchProviderAvailability getAvailability() {
            return isAvailable(providerId)
                    ? SearchProviderAvailability.AVAILABLE
                    : SearchProviderAvailability.NOT_CONFIGURED;
        }

        @Override
        public SearchProviderResult search(SearchProviderRequest request) {
            Generation generation = acquire();
            try {
                SearchProvider delegate = generation.content.provider(providerId);
                if (delegate == null) {
                    throw new SearchProviderConfigurationException(providerId,
                            "Provider is not active in the current configuration");
                }
                return delegate.search(request);
            } finally {
                release(generation);
            }
        }
    }
}
