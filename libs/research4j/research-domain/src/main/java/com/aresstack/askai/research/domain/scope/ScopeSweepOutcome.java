package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The TYPED end of one sweep run. The non-READY states are first-class facts, never an empty
 * result list: "I could not check the fence reliably" (generation failed, sample incomplete,
 * calibration weak, embedding failed, scope moved) must stay distinguishable from "the fence has
 * no holes" — collapsing them is the epistemic error the whole gate chain exists to prevent.
 * READY exists ONLY when generation was OK and complete, the calibration permits hole hunting and
 * the scope revision is still current — and it stays BOUND to its snapshot: READY carries the
 * scope revision and embedding fingerprint it was computed on, because the final staleness check
 * only narrows the race, it cannot close it. Optimistic concurrency works only when the result
 * keeps its version — a later consumer (Z4) re-checks {@code getScopeRevision()} against the
 * current draft before using the readings.
 */
public final class ScopeSweepOutcome {

    public enum Status {
        READY,
        GENERATION_FAILED,
        BROAD_SAMPLE_INCOMPLETE,
        CALIBRATION_WEAK,
        EMBEDDING_FAILED,
        STALE_SCOPE
    }

    private final Status status;
    /** READY + CALIBRATION_WEAK carry the calibration (WEAK keeps its samples explainable). */
    private final ScopeFenceCalibrator.FenceCalibration calibration;
    private final ProbeSweepAnalyzer.ProbeSweepResult sweep;
    private final List<ProbeReading> diverseCandidates;
    /** Z4a: the reason-aware advisory summary — present exactly on READY, bound to (R, E). */
    private final ScopeAdviceSet adviceSet;
    private final ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus;
    private final int requestedBroadCount;
    private final int acceptedBroadCount;
    /** The snapshot this outcome was computed on (READY and STALE_SCOPE). */
    private final long scopeRevision;
    private final String embeddingFingerprint;
    private final long currentRevision;
    private final String diagnostics;

    private ScopeSweepOutcome(Status status,
                              ScopeFenceCalibrator.FenceCalibration calibration,
                              ProbeSweepAnalyzer.ProbeSweepResult sweep,
                              List<ProbeReading> diverseCandidates,
                              ScopeAdviceSet adviceSet,
                              ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus,
                              int requestedBroadCount, int acceptedBroadCount,
                              long scopeRevision, String embeddingFingerprint,
                              long currentRevision, String diagnostics) {
        this.adviceSet = adviceSet;
        this.status = status;
        this.calibration = calibration;
        this.sweep = sweep;
        this.diverseCandidates = diverseCandidates == null
                ? Collections.<ProbeReading>emptyList()
                : Collections.unmodifiableList(new ArrayList<ProbeReading>(diverseCandidates));
        this.generatorStatus = generatorStatus;
        this.requestedBroadCount = requestedBroadCount;
        this.acceptedBroadCount = acceptedBroadCount;
        this.scopeRevision = scopeRevision;
        this.embeddingFingerprint = embeddingFingerprint == null ? "" : embeddingFingerprint;
        this.currentRevision = currentRevision;
        this.diagnostics = diagnostics == null ? "" : diagnostics;
    }

    public static ScopeSweepOutcome ready(long scopeRevision, String embeddingFingerprint,
                                          ScopeFenceCalibrator.FenceCalibration calibration,
                                          ProbeSweepAnalyzer.ProbeSweepResult sweep,
                                          List<ProbeReading> diverseCandidates,
                                          ScopeAdviceSet adviceSet,
                                          String diagnostics) {
        if (calibration == null || sweep == null) {
            throw new IllegalArgumentException("READY carries calibration and sweep");
        }
        if (embeddingFingerprint == null || embeddingFingerprint.trim().isEmpty()) {
            throw new IllegalArgumentException("READY must stay bound to its embedding snapshot");
        }
        if (adviceSet == null || adviceSet.getScopeRevision() != scopeRevision
                || !adviceSet.getEmbeddingFingerprint().equals(embeddingFingerprint.trim())) {
            throw new IllegalArgumentException(
                    "READY carries advice bound to the SAME (revision, fingerprint) snapshot");
        }
        return new ScopeSweepOutcome(Status.READY, calibration, sweep, diverseCandidates,
                adviceSet, null, 0, 0, scopeRevision, embeddingFingerprint, scopeRevision,
                diagnostics);
    }

    public static ScopeSweepOutcome generationFailed(
            ScopeProbeGenerator.ProbeGenerationResult.Status generatorStatus, String diagnostics) {
        return new ScopeSweepOutcome(Status.GENERATION_FAILED, null, null, null, null,
                generatorStatus, 0, 0, 0L, "", 0L, diagnostics);
    }

    public static ScopeSweepOutcome broadSampleIncomplete(int requested, int accepted,
                                                          String diagnostics) {
        return new ScopeSweepOutcome(Status.BROAD_SAMPLE_INCOMPLETE, null, null, null, null,
                null, requested, accepted, 0L, "", 0L, diagnostics);
    }

    public static ScopeSweepOutcome calibrationWeak(
            ScopeFenceCalibrator.FenceCalibration calibration) {
        // The old line printed anchorMissionRelevances.size() as "negotiated" — live-gate 3 read
        // "negotiated 0" over a draft with THREE negotiated posts and hunted the wrong bug. The
        // count is the number of anchor-vs-mission samples, which is zero exactly when the draft
        // has no mission/domains/contexts to measure against — say so.
        int missionSamples = calibration.samples.anchorMissionRelevances.size();
        return new ScopeSweepOutcome(Status.CALIBRATION_WEAK, calibration, null, null, null,
                null, 0, 0, 0L, "", 0L,
                "coverage " + calibration.samples.distinctParentAnchorsCovered + "/"
                        + calibration.samples.eligibleAnchorCount + ", neighbor samples "
                        + calibration.samples.anchorNeighborSimilarities.size()
                        + ", negotiated posts " + calibration.samples.eligibleAnchorCount
                        + ", mission samples " + missionSamples
                        + (missionSamples == 0
                                ? " (no mission/domains/contexts on the draft — setMission feeds"
                                        + " the fence)"
                                : ""));
    }

    public static ScopeSweepOutcome embeddingFailed(String diagnostics) {
        return new ScopeSweepOutcome(Status.EMBEDDING_FAILED, null, null, null, null,
                null, 0, 0, 0L, "", 0L, diagnostics);
    }

    public static ScopeSweepOutcome staleScope(long requestedRevision, long currentRevision) {
        return new ScopeSweepOutcome(Status.STALE_SCOPE, null, null, null, null,
                null, 0, 0, requestedRevision, "", currentRevision,
                "scope moved from " + requestedRevision + " to " + currentRevision);
    }

    public Status getStatus() {
        return status;
    }

    public boolean isReady() {
        return status == Status.READY;
    }

    public ScopeFenceCalibrator.FenceCalibration getCalibration() {
        return calibration;
    }

    public ProbeSweepAnalyzer.ProbeSweepResult getSweep() {
        return sweep;
    }

    public List<ProbeReading> getDiverseCandidates() {
        return diverseCandidates;
    }

    /**
     * Z4a: the reason-aware advisory summary (question candidates grouped by conversational
     * reason + drift guards), present exactly on READY and bound to the same (revision,
     * fingerprint) snapshot. Z4 consumers work from THIS, not from the raw diverse candidates —
     * the reasons are different conversational situations, never one ranked pool.
     */
    public ScopeAdviceSet getAdviceSet() {
        return adviceSet;
    }

    public ScopeProbeGenerator.ProbeGenerationResult.Status getGeneratorStatus() {
        return generatorStatus;
    }

    public int getRequestedBroadCount() {
        return requestedBroadCount;
    }

    public int getAcceptedBroadCount() {
        return acceptedBroadCount;
    }

    /** The draft revision this outcome was computed on (READY; the requested one for STALE). */
    public long getScopeRevision() {
        return scopeRevision;
    }

    /** The embedding snapshot fingerprint a READY outcome is bound to; empty otherwise. */
    public String getEmbeddingFingerprint() {
        return embeddingFingerprint;
    }

    public long getCurrentRevision() {
        return currentRevision;
    }

    public String getDiagnostics() {
        return diagnostics;
    }
}
