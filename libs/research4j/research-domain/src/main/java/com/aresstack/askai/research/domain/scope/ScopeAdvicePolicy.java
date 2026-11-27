package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Z4a: the DETERMINISTIC mapping from a trusted sweep to reason-aware advice — pure domain logic,
 * no model, no I/O. It does exactly one thing the raw sweep cannot: it groups readings by their
 * CONVERSATIONAL meaning instead of their geometry score, because 15 readings are not 15
 * questions:
 * <pre>
 *   PENDING          → group by the provisional post → ONE open topic per raised-but-undecided post
 *   BOUNDARY         → group by the (IN, OUT) anchor pair → ONE border question per real border
 *   EXTENSION + IN   → group by the IN post → ONE edge question per accepted region
 *   EXTENSION + OUT  → DRIFT GUARD per OUT post — never a positive question again
 *   UNEXPLORED       → semantic diversity over the probe vectors → ONE candidate per island
 *   KNOWN / EXCLUDED / IRRELEVANT → nothing to talk about
 * </pre>
 * Within a group the representative is the reading with the highest mission relevance (stable
 * first-wins on ties). There is deliberately NO cross-reason priority and NO combined score —
 * which of the resulting few questions is the most consequential is the Z4b chooser's (and
 * ultimately the user's) judgement, not a formula's.
 */
public final class ScopeAdvicePolicy {

    private ScopeAdvicePolicy() {
    }

    /**
     * Derive the advice from a sweep's readings. {@code probeVectorsById} feeds the UNEXPLORED
     * island diversity (the only reason whose grouping is genuinely semantic — the other reasons
     * group over their anchor relations); {@code unexploredDiversity} is the explicit knob set.
     */
    public static ScopeAdviceSet derive(ProbeSweepAnalyzer.ProbeSweepResult sweep,
                                        Map<String, float[]> probeVectorsById,
                                        long scopeRevision, String embeddingFingerprint,
                                        DiverseProbeSelector.Parameters unexploredDiversity) {
        Map<String, Group> pendingByPost = new LinkedHashMap<String, Group>();
        Map<String, Group> boundaryByPair = new LinkedHashMap<String, Group>();
        Map<String, Group> extensionByInPost = new LinkedHashMap<String, Group>();
        Map<String, Group> driftByOutPost = new LinkedHashMap<String, Group>();
        List<ProbeReading> unexplored = new ArrayList<ProbeReading>();

        for (ProbeReading reading : sweep.getReadings()) {
            switch (reading.getCategory()) {
                case PENDING:
                    collect(pendingByPost,
                            reading.getFenceReading().nearestProvisionalAnchorId, reading);
                    break;
                case BOUNDARY:
                    collect(boundaryByPair, reading.getFenceReading().nearestInAnchorId
                            + "|" + reading.getFenceReading().nearestOutAnchorId, reading);
                    break;
                case EXTENSION:
                    if (reading.getFenceRelation() == ProbeReading.FenceRelation.OUT) {
                        // The asymmetric case: the fringe of an EXCLUDED region protects the
                        // exclusion — it never becomes a "soll das auch rein?" question.
                        collect(driftByOutPost,
                                reading.getFenceReading().nearestOutAnchorId, reading);
                    } else {
                        collect(extensionByInPost,
                                reading.getFenceReading().nearestInAnchorId, reading);
                    }
                    break;
                case UNEXPLORED:
                    unexplored.add(reading);
                    break;
                default:
                    // KNOWN, EXCLUDED, IRRELEVANT: nothing to talk about.
            }
        }

        List<ScopeAdviceCandidate> candidates = new ArrayList<ScopeAdviceCandidate>();
        for (Map.Entry<String, Group> entry : pendingByPost.entrySet()) {
            candidates.add(candidateOf("pending-" + entry.getKey(),
                    ScopeAdviceCandidate.Reason.RESOLVE_PENDING, entry.getValue()));
        }
        for (Map.Entry<String, Group> entry : boundaryByPair.entrySet()) {
            candidates.add(candidateOf(
                    "boundary-" + entry.getKey().replace('|', '-'),
                    ScopeAdviceCandidate.Reason.CLARIFY_BOUNDARY, entry.getValue()));
        }
        for (Map.Entry<String, Group> entry : extensionByInPost.entrySet()) {
            candidates.add(candidateOf("extension-" + entry.getKey(),
                    ScopeAdviceCandidate.Reason.CHECK_IN_EXTENSION, entry.getValue()));
        }
        // UNEXPLORED is the one reason whose grouping is genuinely semantic: several wordings of
        // the same missing island must collapse to one candidate, two real islands must survive.
        List<ProbeReading> islands = DiverseProbeSelector.select(
                unexplored, probeVectorsById, unexploredDiversity);
        for (ProbeReading island : islands) {
            candidates.add(new ScopeAdviceCandidate(
                    "unexplored-" + island.getProbe().getProbeId(),
                    ScopeAdviceCandidate.Reason.CHECK_UNEXPLORED,
                    island.getProbe().getSemanticText(),
                    island.getFenceReading().nearestInAnchorId,
                    island.getFenceReading().nearestOutAnchorId,
                    island.getFenceReading().nearestProvisionalAnchorId,
                    island.getMissionRelevance(), island.getKnownSimilarity(),
                    island.getSweepNoveltyRank(),
                    // The compression of this island happened inside the diversity selection;
                    // the surviving representative counts the whole unexplored pool share only
                    // implicitly — 1 keeps the number honest (no invented attribution).
                    1));
        }

        List<ScopeDriftGuard> guards = new ArrayList<ScopeDriftGuard>();
        for (Map.Entry<String, Group> entry : driftByOutPost.entrySet()) {
            ProbeReading representative = entry.getValue().representative;
            guards.add(new ScopeDriftGuard(representative.getProbe().getSemanticText(),
                    entry.getKey(),
                    String.format(Locale.ROOT,
                            "OUT fringe: knownSimilarity=%.3f, sweepNoveltyRank=%d",
                            representative.getKnownSimilarity(),
                            representative.getSweepNoveltyRank()),
                    entry.getValue().size));
        }
        return new ScopeAdviceSet(scopeRevision, embeddingFingerprint, candidates, guards);
    }

    private static ScopeAdviceCandidate candidateOf(String candidateId,
                                                    ScopeAdviceCandidate.Reason reason,
                                                    Group group) {
        ProbeReading reading = group.representative;
        return new ScopeAdviceCandidate(candidateId, reason,
                reading.getProbe().getSemanticText(),
                reading.getFenceReading().nearestInAnchorId,
                reading.getFenceReading().nearestOutAnchorId,
                reading.getFenceReading().nearestProvisionalAnchorId,
                reading.getMissionRelevance(), reading.getKnownSimilarity(),
                reading.getSweepNoveltyRank(), group.size);
    }

    private static void collect(Map<String, Group> groups, String key, ProbeReading reading) {
        String groupKey = key == null || key.trim().isEmpty() ? "(none)" : key.trim();
        Group group = groups.get(groupKey);
        if (group == null) {
            groups.put(groupKey, new Group(reading));
        } else {
            group.add(reading);
        }
    }

    /** One conversational group: the best representative (by mission relevance) + the count. */
    private static final class Group {
        ProbeReading representative;
        int size;

        Group(ProbeReading first) {
            this.representative = first;
            this.size = 1;
        }

        void add(ProbeReading reading) {
            size++;
            if (reading.getMissionRelevance() > representative.getMissionRelevance()) {
                representative = reading; // strictly greater: stable first-wins on ties
            }
        }
    }
}
