package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The EXCHANGEABLE probe source of the sweep — deliberately a port: Z3's biggest open risk is not
 * the cosine math but whether the generator samples the space broadly enough (a hole the generator
 * never names cannot be found by any evaluator). Implementations (prompt v1/v2, small/large model,
 * several independent batches, fixed test lists) compete behind this seam without touching any
 * fence mathematics. A generator must NOT merely paraphrase the known facets.
 */
public interface ScopeProbeGenerator {

    /** What the generator works from — the draft's coarse frame, never the fence geometry. */
    final class ProbeGenerationRequest {
        private final String mission;
        private final List<String> domains;
        private final List<String> contexts;
        /** The labels already on the fence — so the generator can AVOID mere paraphrases. */
        private final List<String> knownFacetLabels;
        /** The posts themselves — the neighborhood controls need their ids and semantic texts. */
        private final List<ScopeAnchor> anchors;
        private final int targetCount;

        public ProbeGenerationRequest(String mission, List<String> domains, List<String> contexts,
                                      List<String> knownFacetLabels, List<ScopeAnchor> anchors,
                                      int targetCount) {
            this.mission = mission == null ? "" : mission.trim();
            this.domains = copy(domains);
            this.contexts = copy(contexts);
            this.knownFacetLabels = copy(knownFacetLabels);
            this.anchors = Collections.unmodifiableList(new ArrayList<ScopeAnchor>(
                    anchors == null ? Collections.<ScopeAnchor>emptyList() : anchors));
            this.targetCount = Math.max(1, targetCount);
        }

        public List<ScopeAnchor> getAnchors() {
            return anchors;
        }

        public String getMission() {
            return mission;
        }

        public List<String> getDomains() {
            return domains;
        }

        public List<String> getContexts() {
            return contexts;
        }

        public List<String> getKnownFacetLabels() {
            return knownFacetLabels;
        }

        public int getTargetCount() {
            return targetCount;
        }

        private static List<String> copy(List<String> values) {
            return Collections.unmodifiableList(new ArrayList<String>(
                    values == null ? Collections.<String>emptyList() : values));
        }
    }

    /**
     * ONE model call, two kinds of output: the BROAD probes hunt holes; the ANCHOR_NEIGHBOR
     * controls (2-3 per NEGOTIATED IN/OUT post: different concrete examples clearly INSIDE that
     * post's region, never mere paraphrases) exist solely to calibrate the known-region floor.
     * PROVISIONAL posts get NO controls — an unconfirmed agent hypothesis must not shift the
     * global measuring stick, and skipping them saves tokens and embeddings. Keeping both kinds
     * in one batch keeps the orientation sweep fast.
     */
    final class ProbeGeneration {
        private final List<ScopeProbe> broadProbes;
        private final List<ScopeCalibrationProbe> calibrationProbes;
        /** What the request explicitly asked for — the denominator of sample sufficiency. */
        private final int requestedBroadCount;

        public ProbeGeneration(List<ScopeProbe> broadProbes,
                               List<ScopeCalibrationProbe> calibrationProbes,
                               int requestedBroadCount) {
            this.broadProbes = Collections.unmodifiableList(new ArrayList<ScopeProbe>(
                    broadProbes == null ? Collections.<ScopeProbe>emptyList() : broadProbes));
            this.calibrationProbes = Collections.unmodifiableList(
                    new ArrayList<ScopeCalibrationProbe>(calibrationProbes == null
                            ? Collections.<ScopeCalibrationProbe>emptyList() : calibrationProbes));
            this.requestedBroadCount = Math.max(1, requestedBroadCount);
        }

        public List<ScopeProbe> getBroadProbes() {
            return broadProbes;
        }

        public List<ScopeCalibrationProbe> getCalibrationProbes() {
            return calibrationProbes;
        }

        public int getRequestedBroadCount() {
            return requestedBroadCount;
        }

        public int getAcceptedBroadCount() {
            return broadProbes.size();
        }

        /**
         * The generator's OWN sample-sufficiency verdict, same epistemic discipline as the
         * calibration's WEAK: a sweep over 3 accepted probes must never be treated like a sweep
         * over the 100 that were asked for — the generator's breadth IS Z3's biggest risk. MVP
         * rule, deterministic against the explicit request (no invented percentage): complete
         * means the accepted count reached the requested target. Read it as
         * requested-count-satisfied, NEVER as "the semantic space was fully sampled" — 50
         * near-identical probes would also score 50/50; real diversity is measurable only after
         * embedding, and no gate is built on it yet. Hole hunting downstream requires
         * BOTH {@code broadSampleComplete()} and the calibration's {@code permitsHoleHunting()};
         * otherwise the normal scoping dialog continues without UNEXPLORED/EXTENSION advisories.
         */
        public boolean broadSampleComplete() {
            return broadProbes.size() >= requestedBroadCount;
        }
    }

    /**
     * The typed outcome of one generation run — the model seam's error semantics
     * (timeout/provider/invalid) must NOT die at this port: an empty generation and a failed model
     * call are different facts, and a sweep must never mistake "the model broke" for "nothing
     * found". Statuses mirror the productive chat seam 1:1; "model unavailable" arrives as a
     * PROVIDER_FAILURE with its reason in the message (the chat seam reports it that way — no
     * status is invented here that no producer can emit).
     */
    final class ProbeGenerationResult {
        public enum Status {
            OK,
            TIMEOUT,
            PROVIDER_FAILURE,
            /** The model answered, but not with the contract (malformed/empty output). */
            INVALID_RESPONSE
        }

        private final Status status;
        /** Present exactly when status == OK. */
        private final ProbeGeneration generation;
        /** Diagnostics: failure reason, or dropped/deduplicated entries on success. */
        private final String message;

        private ProbeGenerationResult(Status status, ProbeGeneration generation, String message) {
            this.status = status;
            this.generation = generation;
            this.message = message == null ? "" : message;
        }

        public static ProbeGenerationResult ok(ProbeGeneration generation, String message) {
            if (generation == null) {
                throw new IllegalArgumentException("an OK result carries a generation");
            }
            return new ProbeGenerationResult(Status.OK, generation, message);
        }

        public static ProbeGenerationResult failure(Status status, String message) {
            if (status == Status.OK) {
                throw new IllegalArgumentException("a failure result must carry a failure status");
            }
            return new ProbeGenerationResult(status, null, message);
        }

        public Status getStatus() {
            return status;
        }

        public boolean isOk() {
            return status == Status.OK;
        }

        /** The generation — null on any non-OK status. */
        public ProbeGeneration getGeneration() {
            return generation;
        }

        public String getMessage() {
            return message;
        }
    }

    /** Many DIVERSE concrete concepts plus the per-anchor neighborhood controls — one model call. */
    ProbeGenerationResult generate(ProbeGenerationRequest request);
}
