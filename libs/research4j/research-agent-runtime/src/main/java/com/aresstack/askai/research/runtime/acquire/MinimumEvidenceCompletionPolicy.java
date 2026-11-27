package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;

/**
 * LEGACY completion semantics of the AUTONOMOUS research loop, extracted verbatim from the former
 * {@code sufficientOr}: an exhaustion/traversal ending is relabeled SUFFICIENT_EVIDENCE when the run holds
 * at least {@code minimumAcceptedSources} sources on {@code minimumDistinctHosts} hosts. It never completes
 * a run on its own ({@link #isComplete} is false; the hard {@code maxAcceptedSources} stop stays a budget
 * clause), exactly as before.
 *
 * <p>Kept ONLY so the autonomous loop's behaviour (PHASE_READY flow, run-outcome cards, E2E gates) is
 * bit-for-bit unchanged by the completion-policy extraction. The manual search uses
 * {@link FixedAcceptedSourceCountPolicy}. Retiring this relabeling from the autonomous loop is its own
 * slice with its own gates — not a side effect of this one.</p>
 */
public final class MinimumEvidenceCompletionPolicy implements SearchCompletionPolicy {

    private final ResearchRunBudget budget;

    public MinimumEvidenceCompletionPolicy(ResearchRunBudget budget) {
        this.budget = budget;
    }

    public boolean isComplete(ResearchRunProgress progress) {
        return false; // historical behaviour: only budgets/traversal end the run
    }

    @Override
    public ResearchStopReason labelExhaustion(ResearchStopReason fallback,
                                              ResearchRunProgress progress) {
        boolean sufficient = progress.getAcceptedSources() >= budget.getMinimumAcceptedSources()
                && progress.getDistinctHosts().size() >= budget.getMinimumDistinctHosts();
        return sufficient ? ResearchStopReason.SUFFICIENT_EVIDENCE : fallback;
    }
}
