package com.aresstack.askai.browser.search.layout;

import java.util.Collections;
import java.util.List;

/**
 * A validated, reusable layout profile — the STRUCTURE of a recognized SERP layout, never a
 * snapshot-local container id. Container ids are meaningless outside their snapshot, so a profile
 * stores only structure signatures, an engine family, a fingerprint pattern, an ancestry signature
 * and the settings digest that validated it. Reuse requires the engine family and fingerprint/
 * structure signatures to be compatible AND the current containers to re-resolve AND the re-derived
 * selection to re-validate — a stored profile can never resurrect a raw old container id.
 */
public final class SearchPageLayoutProfile {

    public final EngineFamily engineFamily;
    public final String documentFingerprintPattern;
    public final int structureSignatureVersion;
    public final String resultRegionStructureSignature;
    public final List<String> resultBlockStructureSignatures;
    public final String ancestrySignature;
    public final String settingsDigest;
    public final long createdAtEpochMillis;
    public final long lastValidatedAtEpochMillis;
    public final int successfulUseCount;

    public SearchPageLayoutProfile(EngineFamily engineFamily, String documentFingerprintPattern,
                                   int structureSignatureVersion, String resultRegionStructureSignature,
                                   List<String> resultBlockStructureSignatures, String ancestrySignature,
                                   String settingsDigest, long createdAtEpochMillis,
                                   long lastValidatedAtEpochMillis, int successfulUseCount) {
        this.engineFamily = engineFamily == null ? EngineFamily.UNKNOWN : engineFamily;
        this.documentFingerprintPattern = safe(documentFingerprintPattern);
        this.structureSignatureVersion = structureSignatureVersion;
        this.resultRegionStructureSignature = safe(resultRegionStructureSignature);
        this.resultBlockStructureSignatures = resultBlockStructureSignatures == null
                ? Collections.<String>emptyList()
                : Collections.unmodifiableList(resultBlockStructureSignatures);
        this.ancestrySignature = safe(ancestrySignature);
        this.settingsDigest = safe(settingsDigest);
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.lastValidatedAtEpochMillis = lastValidatedAtEpochMillis;
        this.successfulUseCount = successfulUseCount;
    }

    /** A profile revalidated now: bump the use count and the last-validated stamp. */
    public SearchPageLayoutProfile revalidated(long nowEpochMillis) {
        return new SearchPageLayoutProfile(engineFamily, documentFingerprintPattern,
                structureSignatureVersion, resultRegionStructureSignature,
                resultBlockStructureSignatures, ancestrySignature, settingsDigest,
                createdAtEpochMillis, nowEpochMillis, successfulUseCount + 1);
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
