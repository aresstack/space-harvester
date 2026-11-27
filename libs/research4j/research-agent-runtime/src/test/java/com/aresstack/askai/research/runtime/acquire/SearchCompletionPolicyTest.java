package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.research.runtime.loop.ResearchRunBudget;
import com.aresstack.askai.research.runtime.loop.ResearchRunProgress;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The completion seam, separated from safety limits and traversal exhaustion. The deterministic baseline
 * ({@link FixedAcceptedSourceCountPolicy}) counts accepted sources against the configured target — no host
 * diversity, no heuristics — and never relabels an exhaustion. The legacy autonomous policy keeps the old
 * {@code sufficientOr} semantics, explicitly and testably.
 */
public class SearchCompletionPolicyTest {

    /** accepted sources spread over {@code hosts} distinct hosts. */
    private static ResearchRunProgress progressWith(int accepted, int hosts) {
        ResearchRunProgress progress = new ResearchRunProgress();
        for (int i = 0; i < hosts; i++) {
            progress.pageVisited("https://host" + i + ".example/p", "host" + i + ".example");
        }
        for (int i = 0; i < accepted; i++) {
            progress.sourceAccepted();
        }
        return progress;
    }

    @Test
    public void fixedCountCompletesExactlyAtTheTarget() {
        FixedAcceptedSourceCountPolicy policy = new FixedAcceptedSourceCountPolicy(3);
        assertFalse("0/3 → weiter", policy.isComplete(progressWith(0, 0)));
        assertFalse("1/3 → weiter", policy.isComplete(progressWith(1, 1)));
        assertFalse("2/3 → weiter", policy.isComplete(progressWith(2, 2)));
        assertTrue("3/3 → fertig", policy.isComplete(progressWith(3, 3)));
        assertTrue("above target stays complete", policy.isComplete(progressWith(4, 1)));
    }

    @Test
    public void fixedCountIgnoresHostDiversityEntirely() {
        FixedAcceptedSourceCountPolicy policy = new FixedAcceptedSourceCountPolicy(3);
        assertTrue("3 Quellen auf EINEM Host → trotzdem Completion",
                policy.isComplete(progressWith(3, 1)));
        assertFalse("2 Quellen auf zehn Hosts → keine Completion",
                policy.isComplete(progressWith(2, 10)));
    }

    @Test
    public void fixedCountNeverRelabelsAnExhaustion() {
        FixedAcceptedSourceCountPolicy policy = new FixedAcceptedSourceCountPolicy(3);
        ResearchRunProgress twoOfThree = progressWith(2, 10); // plenty of hosts — must not matter
        assertEquals("frontier leer bei 2/3 → NO_RELEVANT_PATHS, NICHT SUFFICIENT_EVIDENCE",
                ResearchStopReason.NO_RELEVANT_PATHS,
                policy.labelExhaustion(ResearchStopReason.NO_RELEVANT_PATHS, twoOfThree));
        assertEquals(ResearchStopReason.TOOL_BUDGET_EXHAUSTED,
                policy.labelExhaustion(ResearchStopReason.TOOL_BUDGET_EXHAUSTED, twoOfThree));
        assertEquals(ResearchStopReason.PAGE_BUDGET_EXHAUSTED,
                policy.labelExhaustion(ResearchStopReason.PAGE_BUDGET_EXHAUSTED, twoOfThree));
        assertEquals(ResearchStopReason.TIME_BUDGET_EXHAUSTED,
                policy.labelExhaustion(ResearchStopReason.TIME_BUDGET_EXHAUSTED, twoOfThree));
    }

    @Test
    public void fixedCountRejectsANonsenseTarget() {
        try {
            new FixedAcceptedSourceCountPolicy(0);
            fail("a target of 0 would complete every search before it starts");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("targetAcceptedSources"));
        }
    }

    @Test
    public void legacyMinimumEvidencePolicyKeepsTheOldAutonomousSemantics() {
        // defaults: minimumAcceptedSources=3, minimumDistinctHosts=2 — verbatim the former sufficientOr.
        MinimumEvidenceCompletionPolicy legacy =
                new MinimumEvidenceCompletionPolicy(ResearchRunBudget.defaults());
        assertFalse("the legacy policy never completes a run on its own",
                legacy.isComplete(progressWith(8, 8)));
        assertEquals("minimums met → the old relabeling", ResearchStopReason.SUFFICIENT_EVIDENCE,
                legacy.labelExhaustion(ResearchStopReason.NO_RELEVANT_PATHS, progressWith(3, 2)));
        assertEquals("too few sources → honest fallback", ResearchStopReason.NO_RELEVANT_PATHS,
                legacy.labelExhaustion(ResearchStopReason.NO_RELEVANT_PATHS, progressWith(2, 10)));
        assertEquals("too few hosts → honest fallback", ResearchStopReason.TOOL_BUDGET_EXHAUSTED,
                legacy.labelExhaustion(ResearchStopReason.TOOL_BUDGET_EXHAUSTED, progressWith(5, 1)));
    }
}
