package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * A substantive scoping turn must HELP first — brief + at least one search suggestion (no visualization). The
 * reported GUI failure (an interview-only reply that just asks which subtopic the user means, with no support
 * work) must not count as a valid first scoping turn.
 */
public class ScopingPhaseOutputContractTest {

    private final PhaseOutputContract contract = new ScopingPhaseOutputContract();

    @Test
    public void aHelpfulFirstTurnWithBriefAndSuggestionIsValid() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"You want to explore wearables.\","
                        + "\"researchBriefMarkdown\":\"# Research Brief\\n\\nWearables?\","
                        + "\"searchSuggestions\":[{\"query\":\"wearables 2026\",\"priority\":1}]}");
        assertTrue(result.getError(), result.isOk());
        assertTrue(result.getOutput() instanceof ScopingAssistantOutput);
    }

    /**
     * Asking one good question IS scoping. This once had to be rejected because a turn had to carry a
     * brief and a suggestion; that rule made the assistant produce output for the parser instead of for the
     * user, and a plain "Thema: Bibliotheken." could not be answered at all.
     */
    @Test
    public void anInterviewOnlyReplyThatAsksAboutTheDirectionIsValid() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"Wearables is a very broad topic! Are you interested in health, "
                        + "audio, or video?\",\"researchBriefMarkdown\":\"# Research Brief\\n\\nWearables\"}");
        assertTrue(result.getError(), result.isOk());
    }

    @Test
    public void aTurnWithoutSearchSuggestionsIsValid() {
        PhaseParseResult result = contract.parse(
                "{\"assistantMessage\":\"Ok.\",\"researchBriefMarkdown\":\"# Brief\\nX\"}");
        assertTrue(result.getError(), result.isOk());
    }

    /** Only the visible answer is required — everything else this phase may produce is optional. */
    @Test
    public void aBareVisibleAnswerIsAValidScopingTurn() {
        PhaseParseResult result = contract.parse("{\"assistantMessage\":\"Thema verstanden. Geht es dir "
                + "um die Institution oder um die Nutzung?\"}");
        assertTrue(result.getError(), result.isOk());
    }
}
