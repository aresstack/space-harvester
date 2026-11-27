package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchEngine;
import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import com.aresstack.askai.research.search.api.WebSearchProvider;
import com.aresstack.askai.research.search.application.WebSearchProvidersModule;
import com.aresstack.askai.research.search.config.ProviderConfigurationPaths;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Productive {@link AsyncSearchGenerationFactory}: opens {@link WebSearchProvidersModule} RESILIENTLY from
 * the file-based provider configuration and wraps each module provider it yields in an
 * {@link AsyncModuleSearchProvider} keyed by its runtime id. A whole-module failure (directory / key store)
 * propagates from {@code open()}; a single invalid provider is simply absent from the generation.
 *
 * <p>The factory carries only the config paths and the per-request timeout — never a secret.</p>
 */
public final class ModuleAsyncSearchGenerationFactory implements AsyncSearchGenerationFactory {

    private final ProviderConfigurationPaths paths;
    private final long timeoutMillis;

    public ModuleAsyncSearchGenerationFactory(ProviderConfigurationPaths paths, long timeoutMillis) {
        if (paths == null) {
            throw new IllegalArgumentException("paths must not be null");
        }
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        this.paths = paths;
        this.timeoutMillis = timeoutMillis;
    }

    /** Opens the provider module against {@code ${user.home}/agents/research/providers}. */
    public static ModuleAsyncSearchGenerationFactory userHome(long timeoutMillis) {
        return new ModuleAsyncSearchGenerationFactory(ProviderConfigurationPaths.userHome(), timeoutMillis);
    }

    @Override
    public AsyncSearchGeneration open() {
        WebSearchProvidersModule module = WebSearchProvidersModule.openResilient(paths);
        try {
            Map<SearchProviderId, SearchProvider> adapters =
                    new EnumMap<SearchProviderId, SearchProvider>(SearchProviderId.class);
            for (WebSearchProvider provider : module.getProviderRegistry().getAll()) {
                SearchProviderId runtimeId = AsyncProviderMapping.toRuntimeId(provider.getProviderId());
                if (runtimeId == null) {
                    continue; // a module-only provider (e.g. COMPOSITE) has no runtime seam
                }
                SearchEngine engine = AsyncProviderMapping.defaultEngine(runtimeId);
                adapters.put(runtimeId,
                        new AsyncModuleSearchProvider(runtimeId, engine, provider, timeoutMillis));
            }
            return new ModuleGeneration(module, adapters);
        } catch (RuntimeException failure) {
            module.close(); // atomic: never leak an open module if wiring the generation fails
            throw failure;
        }
    }

    /** One generation backed by a single open module; {@link #close()} closes the module exactly once. */
    private static final class ModuleGeneration implements AsyncSearchGeneration {
        private final WebSearchProvidersModule module;
        private final Map<SearchProviderId, SearchProvider> adapters;
        private final AtomicBoolean closed = new AtomicBoolean();

        ModuleGeneration(WebSearchProvidersModule module, Map<SearchProviderId, SearchProvider> adapters) {
            this.module = module;
            this.adapters = adapters;
        }

        @Override
        public SearchProvider provider(SearchProviderId id) {
            return adapters.get(id);
        }

        @Override
        public Set<SearchProviderId> availableProviderIds() {
            return adapters.isEmpty()
                    ? EnumSet.noneOf(SearchProviderId.class)
                    : EnumSet.copyOf(adapters.keySet());
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                module.close();
            }
        }
    }
}
