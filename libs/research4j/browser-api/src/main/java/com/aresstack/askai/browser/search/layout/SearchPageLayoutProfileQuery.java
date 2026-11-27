package com.aresstack.askai.browser.search.layout;

/**
 * The structural key used to look up a reusable {@link SearchPageLayoutProfile}: engine family,
 * fingerprint pattern, the settings digest that must match, and the region/ancestry structure
 * signatures. It contains no snapshot-local container id.
 */
public final class SearchPageLayoutProfileQuery {

    public final EngineFamily engineFamily;
    public final String documentFingerprintPattern;
    public final String settingsDigest;
    public final String resultRegionStructureSignature;
    public final String ancestrySignature;

    public SearchPageLayoutProfileQuery(EngineFamily engineFamily, String documentFingerprintPattern,
                                        String settingsDigest, String resultRegionStructureSignature,
                                        String ancestrySignature) {
        this.engineFamily = engineFamily == null ? EngineFamily.UNKNOWN : engineFamily;
        this.documentFingerprintPattern =
                documentFingerprintPattern == null ? "" : documentFingerprintPattern;
        this.settingsDigest = settingsDigest == null ? "" : settingsDigest;
        this.resultRegionStructureSignature =
                resultRegionStructureSignature == null ? "" : resultRegionStructureSignature;
        this.ancestrySignature = ancestrySignature == null ? "" : ancestrySignature;
    }

    /** Whether a stored profile is compatible with this query — all structural keys must match. */
    public boolean matches(SearchPageLayoutProfile profile) {
        return profile != null
                && profile.engineFamily == engineFamily
                && profile.documentFingerprintPattern.equals(documentFingerprintPattern)
                && profile.settingsDigest.equals(settingsDigest)
                && profile.resultRegionStructureSignature.equals(resultRegionStructureSignature)
                && profile.ancestrySignature.equals(ancestrySignature);
    }
}
