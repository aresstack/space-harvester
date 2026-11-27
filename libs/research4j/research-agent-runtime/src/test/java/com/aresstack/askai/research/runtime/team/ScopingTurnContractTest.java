package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The agreed S2 turn contract, characterised on the two live messages that broke:
 *
 * <pre>
 * assistantMessage        REQUIRED
 * scopePatch              OPTIONAL / may be empty
 * unresolvedIssues        OPTIONAL / may be empty
 * orientationSuggestions  OPTIONAL / may be zero
 * researchBriefMarkdown   OPTIONAL - a projection of the scope, not the truth of it
 * searchSuggestions       OPTIONAL - never produced just to satisfy a parser
 * </pre>
 */
public class ScopingTurnContractTest {

    /** "Thema: Bibliotheken." produced NO assistant answer at all, because a bare reply was contract-invalid. */
    @Test
    public void aBareQuestionBackToTheUserIsACompleteTurn() {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Bibliotheken ist noch sehr breit. Geht es dir eher um "
                        + "Bibliotheken als Institution, um Architektur oder um Nutzung?\"}");

        assertTrue(result.getError(), result.isOk());
        assertTrue(result.getOutput().getAssistantMessage().contains("Bibliotheken"));
        assertEquals("no brief this turn is fine", "", result.getOutput().getResearchBriefMarkdown());
        assertTrue("no invented search", result.getOutput().getSearchSuggestions().isEmpty());
        assertEquals("nothing about the scope was proposed", null, result.getOutput().getScopeUpdate());
    }

    /** The same turn may set a minimal scope without proposing any search at all. */
    @Test
    public void aTurnMaySetAMinimalScopeWithZeroSuggestions() {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Verstanden, ich halte Bibliotheken als Thema fest.\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"setMission\","
                        + "\"mission\":\"Bibliotheken\"}]},"
                        + "\"searchSuggestions\":[]}");

        assertTrue(result.getError(), result.isOk());
        assertTrue(result.getOutput().getSearchSuggestions().isEmpty());
        ScopeUpdateDocument update = result.getOutput().getScopeUpdate();
        assertTrue(update.isValid());
        assertTrue(update.toJson(), update.toJson().contains("\"mission\":\"Bibliotheken\""));
    }

    /** The live message that answered "I could not form a clear answer just now" three times. */
    @Test
    public void theWearablesTurnIsAcceptedWithItsScopePatchAndWithoutAnySearch() {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Ich verstehe: Wearables im Baugewerbe, Arbeitssicherheit und "
                        + "Gesundheit beide wichtig, Kaufberatung draußen.\","
                        + "\"scopePatch\":{\"operations\":["
                        + "{\"kind\":\"setMission\",\"mission\":\"Wearables im Baugewerbe\"},"
                        + "{\"kind\":\"addFacet\",\"facetId\":\"worker-safety\","
                        + "\"label\":\"Arbeitssicherheit\"},"
                        + "{\"kind\":\"addFacet\",\"facetId\":\"occupational-health\","
                        + "\"label\":\"Gesundheit\"},"
                        + "{\"kind\":\"addExclusion\",\"value\":\"Kaufberatung\"}]},"
                        + "\"unresolvedIssues\":[{\"issueId\":\"target-groups\","
                        + "\"description\":\"Welche Zielgruppen im Baugewerbe?\","
                        + "\"significance\":\"SIGNIFICANT\"}]}");

        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument update = result.getOutput().getScopeUpdate();
        assertTrue(update.describeViolations(), update.isValid());
        assertTrue(update.toJson().contains("worker-safety"));
        assertTrue(update.toJson().contains("occupational-health"));
        assertTrue(update.toJson().contains("target-groups"));
        assertTrue("no search was needed for this turn",
                result.getOutput().getSearchSuggestions().isEmpty());
    }

    /** What still IS a violation: a suggestion that is not a suggestion. */
    @Test
    public void aBlankSearchQueryIsStillRejected() {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Hi.\",\"searchSuggestions\":[{\"query\":\"   \",\"priority\":1}]}");

        assertFalse(result.isOk());
        assertTrue(result.getError(), result.getError().contains("blank query"));
    }

    /** And the visible answer itself remains mandatory — a turn nobody can read is not a turn. */
    @Test
    public void anAnswerWithoutAVisibleMessageIsStillRejected() {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(
                "{\"researchBriefMarkdown\":\"# Brief\"}");

        assertFalse(result.isOk());
        assertTrue(result.getError(), result.getError().contains("assistantMessage"));
    }
}
