package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The suggestion guidance is a USER SETTING ("Immer Suchvorschläge anbieten", default OFF), not a fixed
 * prompt: the default keeps the long-standing behaviour (a suggestion only when a lookup genuinely helps —
 * zero is a normal turn), the checkbox switches to the orientation variant (a broad first question always
 * comes with 3-5 direction-opening tags accompanying the clarifying question). Both variants forbid filler
 * queries.
 */
public class ScopingSuggestionGuidanceTest {

    @Test
    public void theDefaultKeepsTheLongStandingOnlyWhenHelpfulBehaviour() {
        String prompt = TeamAgentPlaybook.scopingSystemPrompt(false);
        assertTrue("zero suggestions stays a normal turn by default",
                prompt.contains("Zero suggestions is a perfectly normal turn"));
        assertFalse("the orientation mandate is NOT part of the default",
                prompt.contains("ORIENTATION MAP"));
        // The no-arg prompt IS the default variant (used by ResearchTeamAgent's default registry).
        assertTrue(TeamAgentPlaybook.scopingSystemPrompt()
                .contains("Zero suggestions is a perfectly normal turn"));
    }

    @Test
    public void theCheckboxVariantMandatesOrientationSuggestionsOnBroadTurns() {
        String prompt = TeamAgentPlaybook.scopingSystemPrompt(true);
        assertTrue("suggestions are framed as orientation", prompt.contains("ORIENTATION MAP"));
        assertTrue("a broad first turn always offers direction-opening suggestions",
                prompt.contains("ALWAYS offer"));
        assertTrue("suggestions accompany the clarifying question",
                prompt.contains("ACCOMPANY your clarifying question"));
        assertTrue("breadth must never suppress suggestions",
                prompt.contains("NEVER withhold suggestions"));
        assertFalse("the zero-is-normal default is replaced in this variant",
                prompt.contains("Zero suggestions is a perfectly normal turn"));
    }

    @Test
    public void bothVariantsForbidFillerQueries() {
        assertTrue(TeamAgentPlaybook.scopingSystemPrompt(false)
                .contains("never invent one to fill the field"));
        assertTrue(TeamAgentPlaybook.scopingSystemPrompt(true)
                .contains("never invent filler queries"));
        assertTrue("queries stay short sub-aspect searches, not the whole question",
                TeamAgentPlaybook.scopingSystemPrompt(true)
                        .contains("do not just copy the whole question"));
    }

    /**
     * The user owns breadth: a broad topic is decomposed into provisional facets (the future
     * chapters), never rejected as "too broad", never bent into an angle (comparison etc.) the user
     * did not ask for — and even an unusual scope is the user's to keep. Both variants carry this.
     */
    @Test
    public void bothVariantsMandateDecompositionInsteadOfBreadthGatekeeping() {
        for (boolean checkbox : new boolean[]{false, true}) {
            String prompt = TeamAgentPlaybook.scopingSystemPrompt(checkbox);
            assertTrue("breadth is never a defect", prompt.contains("BREADTH IS NEVER A DEFECT"));
            assertTrue("the standard move is decomposition into facets",
                    prompt.contains("DECOMPOSITION, not restriction"));
            assertTrue("facets are the user's future chapters",
                    prompt.contains("THE USER picks the chapters"));
            assertTrue("no silent re-angling into a comparison",
                    prompt.contains("angle the user did not ask for"));
            assertTrue("every scope the user insists on is legitimate",
                    prompt.contains("Every scope the user insists on is legitimate"));
        }
    }

    @Test
    public void theRegistryThreadsTheSettingIntoTheScopingProfile() {
        assertTrue(PhaseAssistantProfileRegistry.defaults(true)
                .forPhase(PhaseAssistantProfileRegistry.SCOPING_PHASE_ID)
                .getSystemPrompt().contains("ORIENTATION MAP"));
        assertFalse("the no-arg default registry keeps the long-standing behaviour",
                PhaseAssistantProfileRegistry.defaults()
                        .forPhase(PhaseAssistantProfileRegistry.SCOPING_PHASE_ID)
                        .getSystemPrompt().contains("ORIENTATION MAP"));
    }
}
