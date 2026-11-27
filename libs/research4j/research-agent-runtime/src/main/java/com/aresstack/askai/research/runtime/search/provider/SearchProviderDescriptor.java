package com.aresstack.askai.research.runtime.search.provider;

/**
 * A catalogue entry: the provider id, a human-readable name and whether it is IMPLEMENTED or
 * NOT_IMPLEMENTED. The settings UI lists every descriptor; NOT_IMPLEMENTED ones are shown but must not be
 * productively selectable. A descriptor never carries a provider instance — unimplemented ids have none.
 */
public final class SearchProviderDescriptor {

    private final SearchProviderId providerId;
    private final String displayName;
    private final SearchProviderImplementationStatus implementationStatus;

    public SearchProviderDescriptor(SearchProviderId providerId, String displayName,
                                    SearchProviderImplementationStatus implementationStatus) {
        this.providerId = providerId;
        this.displayName = displayName;
        this.implementationStatus = implementationStatus;
    }

    public SearchProviderId getProviderId() {
        return providerId;
    }

    public String getDisplayName() {
        return displayName;
    }

    public SearchProviderImplementationStatus getImplementationStatus() {
        return implementationStatus;
    }

    public boolean isImplemented() {
        return implementationStatus == SearchProviderImplementationStatus.IMPLEMENTED;
    }
}
