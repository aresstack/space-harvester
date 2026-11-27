package com.aresstack.askai.research.domain.search;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Turns "how relevant is each hit" (a {@link RelevanceAssessment}) into "which hits do we look at" (a
 * {@link SelectionDecision}) under a policy. It writes nothing: candidates stay immutable, and every call
 * produces a NEW decision, so the same run can be selected from repeatedly and differently.
 * <p>
 * The rule that keeps diversity honest: diversification happens only AMONG SUFFICIENTLY RELEVANT hits. A
 * clearly irrelevant result must never be picked just because it comes from a domain nobody covered yet —
 * "different" is not a substitute for "useful".
 * <p>
 * And the safety rule that predates this class: no relevance, no automatic selection. Nothing may be opened
 * in raw engine order — but the candidates survive, and an explicit user/agent pick still works.
 */
public final class CandidateSelector {

    /**
     * How far below the best hit a candidate may fall and still count as relevant enough to be diversified
     * into the selection. A fraction of the observed score range, so it works with any model's scale.
     */
    private static final double RELEVANCE_FLOOR_RATIO = 0.5d;

    /**
     * How much the scores must differ (relative to their magnitude) before the difference is treated as a
     * statement about relevance at all. Below this the model is not separating the hits, and excluding the
     * lower ones would be reading a distinction into noise.
     */
    private static final double MEANINGFUL_SPREAD_RATIO = 0.2d;

    private CandidateSelector() {
    }

    /** Everything one selection needs; explicit ids matter only for the USER/AGENT policies. */
    public static SelectionDecision select(String selectionId, SearchRun run,
                                           RelevanceAssessment relevance,
                                           SearchStrategyProfile profile, int limit,
                                           List<String> explicitCandidateIds) {
        SearchStrategyProfile.CandidateSelection policy = profile.getCandidateSelection();
        String profileName = profile.getName();
        if (policy == SearchStrategyProfile.CandidateSelection.USER_SELECTED
                || policy == SearchStrategyProfile.CandidateSelection.AGENT_SELECTED) {
            // An explicit pick needs no relevance model: somebody already decided.
            return explicit(selectionId, run, policy, profileName, explicitCandidateIds);
        }
        if (relevance == null || !relevance.isAvailable()) {
            return SelectionDecision.blocked(selectionId, run.getRunId(), policy, profileName,
                    "no relevance assessment ("
                            + (relevance == null ? "none produced" : relevance.getUnavailableReason())
                            + ") — nothing may be opened in raw engine order");
        }
        List<Ranked> ranked = rank(run, relevance);
        if (ranked.isEmpty()) {
            return SelectionDecision.of(selectionId, run.getRunId(), policy, profileName,
                    Collections.<SelectedCandidateRef>emptyList());
        }
        List<Ranked> picked;
        switch (policy) {
            case DIVERSE_RELEVANT:
                picked = diversify(ranked, limit, relevanceFloor(ranked), true);
                break;
            case HYBRID:
                // Rank first, then correct for diversity: the best hit is always in, after that a new
                // domain wins over yet another page from one already covered.
                picked = diversify(ranked, limit, relevanceFloor(ranked), false);
                break;
            case TOP_RANKED:
            default:
                picked = ranked.subList(0, Math.min(limit, ranked.size()));
                break;
        }
        List<SelectedCandidateRef> refs = new ArrayList<SelectedCandidateRef>();
        int ordinal = 0;
        for (Ranked candidate : picked) {
            ordinal++;
            refs.add(new SelectedCandidateRef(candidate.candidateId, ordinal, candidate.reason));
        }
        return SelectionDecision.of(selectionId, run.getRunId(), policy, profileName, refs);
    }

    /** Exactly what was named, in the order it was named; unknown ids are ignored, never invented. */
    private static SelectionDecision explicit(String selectionId, SearchRun run,
                                              SearchStrategyProfile.CandidateSelection policy,
                                              String profileName, List<String> explicitCandidateIds) {
        List<SelectedCandidateRef> refs = new ArrayList<SelectedCandidateRef>();
        Set<String> seen = new LinkedHashSet<String>();
        if (explicitCandidateIds != null) {
            for (String candidateId : explicitCandidateIds) {
                if (candidateId == null || !seen.add(candidateId.trim())
                        || run.candidate(candidateId) == null) {
                    continue;
                }
                refs.add(new SelectedCandidateRef(candidateId, refs.size() + 1,
                        policy == SearchStrategyProfile.CandidateSelection.USER_SELECTED
                                ? "picked by the user" : "picked by the agent"));
            }
        }
        return SelectionDecision.of(selectionId, run.getRunId(), policy, profileName, refs);
    }

    /** The candidates of this run in relevance order; hits the model did not score are left out. */
    private static List<Ranked> rank(SearchRun run, RelevanceAssessment relevance) {
        List<Ranked> ranked = new ArrayList<Ranked>();
        int position = 0;
        for (String candidateId : relevance.rankedCandidateIds()) {
            SearchCandidate candidate = run.candidate(candidateId);
            if (candidate == null) {
                continue;
            }
            position++;
            Double score = relevance.relevanceOf(candidateId);
            ranked.add(new Ranked(candidateId, candidate.getDomain(),
                    score == null ? 0d : score.doubleValue(), "rank " + position));
        }
        return ranked;
    }

    /**
     * The lower bound for "relevant enough": half the observed score range above the worst hit — but only
     * when the model actually SEPARATED the hits.
     * <p>
     * With scores like 0.95…0.91 the model is saying "these are all about equally good"; treating 0.92 as
     * too weak to diversify into would be reading a distinction into noise, and the selection would fall
     * back to three pages from the same site. So a spread that is small relative to the best score means
     * everything passes and diversity decides. A wide spread (0.95…0.10) does carry information, and then
     * the weak tail is excluded no matter how fresh its domain is.
     */
    private static double relevanceFloor(List<Ranked> ranked) {
        double best = ranked.get(0).relevance;
        double worst = best;
        for (Ranked candidate : ranked) {
            worst = Math.min(worst, candidate.relevance);
        }
        double spread = best - worst;
        if (spread <= 0d) {
            return worst;
        }
        double scale = Math.max(Math.abs(best), Math.abs(worst));
        if (scale > 0d && (spread / scale) < MEANINGFUL_SPREAD_RATIO) {
            return worst; // the model did not separate them — do not invent a separation
        }
        return worst + (spread * RELEVANCE_FLOOR_RATIO);
    }

    /**
     * Walk the relevance order and prefer an unseen domain — but only among candidates at or above the
     * floor. When the relevant pool is exhausted before the limit, the selection is SHORTER rather than
     * padded with irrelevant hits.
     *
     * @param strict {@code true} = never go below the floor (DIVERSE_RELEVANT); {@code false} = fill the
     *               remaining slots by pure rank afterwards (HYBRID)
     */
    private static List<Ranked> diversify(List<Ranked> ranked, int limit, double floor, boolean strict) {
        List<Ranked> picked = new ArrayList<Ranked>();
        Set<String> domains = new LinkedHashSet<String>();
        for (Ranked candidate : ranked) {
            if (picked.size() >= limit) {
                break;
            }
            if (candidate.relevance < floor) {
                continue; // not relevant enough — a new domain does not make it useful
            }
            if (domains.add(candidate.domain) || candidate.domain.isEmpty()) {
                picked.add(candidate.withReason(picked.isEmpty()
                        ? candidate.reason : candidate.reason + ", new domain"));
            }
        }
        if (picked.size() < limit) {
            // Fill up with the remaining best hits (repeat domains allowed). Under DIVERSE_RELEVANT the
            // floor still applies; under HYBRID rank alone may fill the rest.
            for (Ranked candidate : ranked) {
                if (picked.size() >= limit) {
                    break;
                }
                if (strict && candidate.relevance < floor) {
                    continue;
                }
                if (!containsCandidate(picked, candidate.candidateId)) {
                    picked.add(candidate);
                }
            }
        }
        return picked;
    }

    private static boolean containsCandidate(List<Ranked> picked, String candidateId) {
        for (Ranked candidate : picked) {
            if (candidate.candidateId.equals(candidateId)) {
                return true;
            }
        }
        return false;
    }

    /** A candidate with the relevance the model gave it — a working value, never persisted. */
    private static final class Ranked {
        private final String candidateId;
        private final String domain;
        private final double relevance;
        private final String reason;

        private Ranked(String candidateId, String domain, double relevance, String reason) {
            this.candidateId = candidateId;
            this.domain = domain == null ? "" : domain;
            this.relevance = relevance;
            this.reason = reason;
        }

        private Ranked withReason(String newReason) {
            return new Ranked(candidateId, domain, relevance, newReason);
        }
    }
}
