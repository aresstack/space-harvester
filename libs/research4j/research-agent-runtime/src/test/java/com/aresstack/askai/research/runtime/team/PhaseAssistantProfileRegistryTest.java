package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

/** The active phase selects a phase-specific assistant profile, with a neutral fallback for the rest. */
public class PhaseAssistantProfileRegistryTest {

    @Test
    public void scopingHasItsOwnLeastPrivilegeProfile() {
        PhaseAssistantProfile scoping = PhaseAssistantProfileRegistry.defaults().forPhase("scoping");

        assertEquals("scoping", scoping.getPhaseId());
        assertTrue(scoping.getSystemPrompt().contains("sharpen their research scope"));
        assertEquals("research-brief", scoping.getWritableArtifactId());
        assertTrue("least privilege: the scoping assistant has no tools yet",
                scoping.getAllowedCapabilities().isEmpty());
        assertEquals(PhaseContextPolicy.OWN_PHASE_CHAT_AND_LATEST_ARTIFACTS, scoping.getContextPolicy());
    }

    /**
     * Scoping must CONVERGE. In the live gate the assistant ended nearly every turn with "which of these
     * three directions first?" — even when the user explicitly asked for a summary to decide on. That turns
     * a conversation into a form, so the prompt now names the convergence signals, demands a consolidated
     * scope instead of another option menu, and forbids the repeated branching formula.
     */
    @Test
    public void theScopingAssistantMustRecognizeConvergenceInsteadOfBranchingForever() {
        String prompt = PhaseAssistantProfileRegistry.defaults().forPhase("scoping").getSystemPrompt();

        assertTrue(prompt.contains("CONVERGENCE SIGNALS"));
        assertTrue("a converged scope is delivered as a whole, not as new alternatives",
                prompt.contains("CONSOLIDATED SCOPE"));
        assertTrue("the repeated branching formula is explicitly ruled out",
                prompt.contains("Do NOT end every turn the same way"));
        assertTrue("already decided or excluded options must not come back",
                prompt.contains("Never re-offer an option the user already chose or explicitly ruled out"));
        assertTrue("convergence never turns the assistant into a gatekeeper",
                prompt.contains("neither\nask for permission nor claim to start anything")
                        || prompt.contains("neither ask for permission nor claim to start anything"));
    }

    @Test
    public void phaseLookupIsCaseInsensitive() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        assertEquals(registry.forPhase("scoping").getSystemPrompt(),
                registry.forPhase("SCOPING").getSystemPrompt());
    }

    @Test
    public void unknownAndNullPhasesGetTheNeutralFallback() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        String scopingPrompt = registry.forPhase("scoping").getSystemPrompt();

        PhaseAssistantProfile fallback = registry.forPhase("outline");
        assertTrue(fallback.getSystemPrompt().contains("working alongside the user within the current "
                + "research phase"));
        assertNotEquals(scopingPrompt, fallback.getSystemPrompt());
        assertEquals(fallback.getSystemPrompt(), registry.forPhase(null).getSystemPrompt());
    }

    @Test
    public void everyProfilePromptKeepsTheSameOutputContract() {
        PhaseAssistantProfileRegistry registry = PhaseAssistantProfileRegistry.defaults();
        assertTrue(registry.forPhase("scoping").getSystemPrompt().contains("\"assistantMessage\": string"));
        assertTrue(registry.forPhase("outline").getSystemPrompt().contains("\"assistantMessage\": string"));
        assertFalse("no workflow machinery leaks into a phase prompt",
                registry.forPhase("scoping").getSystemPrompt().contains("readyForBrief"));
    }
}
