package com.aresstack.askai.research.runtime.search.provider.async;

import com.aresstack.askai.research.runtime.search.provider.SearchProvider;
import com.aresstack.askai.research.runtime.search.provider.SearchProviderId;

import java.util.Set;

/**
 * One immutable generation of async search providers — the set of runtime {@link SearchProvider}s built
 * from a single {@code WebSearchProvidersModule} open. A provider whose per-provider configuration was
 * invalid is simply ABSENT ({@link #provider(SearchProviderId)} returns {@code null}); a whole-module
 * failure is signalled by the {@link AsyncSearchGenerationFactory} throwing instead of returning a
 * generation. {@link #close()} releases the underlying AsyncHttpClients and MUST be idempotent.
 */
public interface AsyncSearchGeneration extends AutoCloseable {

    /** The provider for {@code id} in this generation, or {@code null} when it is not available here. */
    SearchProvider provider(SearchProviderId id);

    /** The runtime ids actually available in this generation (never null; may be empty). */
    Set<SearchProviderId> availableProviderIds();

    @Override
    void close();
}
