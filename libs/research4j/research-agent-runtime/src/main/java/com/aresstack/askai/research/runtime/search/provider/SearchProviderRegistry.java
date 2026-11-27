package com.aresstack.askai.research.runtime.search.provider;

import java.util.List;

/**
 * The single place that resolves a {@link SearchProviderId} to a productive {@link SearchProvider}. No
 * scattered {@code switch} blocks in UI, agent or research loop may construct providers. An unimplemented id
 * has NO provider object at all: {@link #requireImplementedProvider(SearchProviderId)} throws
 * {@link SearchProviderNotImplementedException} for it rather than returning a stub.
 */
public interface SearchProviderRegistry {

    /**
     * Resolve the productive provider for {@code providerId}.
     *
     * @throws SearchProviderNotImplementedException when the id is catalogued but not yet implemented — no
     *         provider instance is created in that case.
     */
    SearchProvider requireImplementedProvider(SearchProviderId providerId);

    /** Every catalogued provider with its display name and implementation status, for the settings UI. */
    List<SearchProviderDescriptor> getDescriptors();
}
