package com.aresstack.askai.research.runtime.search.provider.async;

/**
 * Builds a fresh {@link AsyncSearchGeneration}. A GLOBAL failure (the module directory, the AES-GCM key
 * store or the whole provider module cannot be opened) is signalled by throwing — the caller then keeps its
 * previous generation and never swaps. A per-provider configuration failure is NOT a global failure: the
 * returned generation simply omits that provider. Implementations must be atomic: if {@code open()} throws
 * after acquiring resources, it cleans them up itself.
 */
public interface AsyncSearchGenerationFactory {

    AsyncSearchGeneration open();
}
