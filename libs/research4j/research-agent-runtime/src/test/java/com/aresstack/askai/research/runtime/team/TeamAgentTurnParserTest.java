package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The tolerant model-output parser: full/partial JSON becomes a validated turn; junk becomes a typed error. */
public class TeamAgentTurnParserTest {

    @Test
    public void aFullStructuredAnswerParsesEveryField() {
        String raw = "{\"assistantMessage\":\"Here is the outline.\","
                + "\"proposedCommand\":\"PROPOSE_OUTLINE\","
                + "\"scope\":{\"question\":\"How do EV batteries age?\",\"aspects\":[\"chemistry\",\"cycles\"]},"
                + "\"approval\":{\"requested\":true,\"subject\":\"outline\"},"
                + "\"searchQueries\":[\"EV battery degradation\",\"lithium cycle life\"]}";
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse(raw);
        assertTrue(result.getError(), result.isOk());
        TeamAgentTurn turn = result.getTurn();
        assertEquals("Here is the outline.", turn.getAssistantMessage());
        assertEquals("PROPOSE_OUTLINE", turn.getProposedCommand());
        assertEquals("How do EV batteries age?", turn.getQuestion());
        assertEquals(2, turn.getAspects().size());
        assertEquals("chemistry", turn.getAspects().get(0));
        assertTrue(turn.isApprovalRequested());
        assertEquals("outline", turn.getApprovalSubject());
        assertEquals(2, turn.getSearchQueries().size());
    }

    @Test
    public void aMinimalMessageOnlyAnswerIsValidWithNoCommand() {
        TeamAgentTurnParser.Result result =
                TeamAgentTurnParser.parse("{\"assistantMessage\":\"What would you like to find out?\"}");
        assertTrue(result.isOk());
        assertFalse(result.getTurn().hasProposedCommand());
        assertNull(result.getTurn().getProposedCommand());
        assertNull(result.getTurn().getQuestion());
        assertFalse(result.getTurn().isApprovalRequested());
        assertTrue(result.getTurn().getSearchQueries().isEmpty());
    }

    @Test
    public void jsonWrappedInProseOrCodeFencesIsStillExtracted() {
        String raw = "Sure! Here is my reply:\n```json\n"
                + "{\"assistantMessage\":\"Got it.\",\"proposedCommand\":\"SUBMIT_SCOPE\"}\n```\nHope that helps.";
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse(raw);
        assertTrue(result.isOk());
        assertEquals("Got it.", result.getTurn().getAssistantMessage());
        assertEquals("SUBMIT_SCOPE", result.getTurn().getProposedCommand());
    }

    @Test
    public void nestedBracesInStringsDoNotBreakExtraction() {
        String raw = "{\"assistantMessage\":\"Use a JSON body like {\\\"k\\\":1} in your query.\"}";
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse(raw);
        assertTrue(result.isOk());
        assertEquals("Use a JSON body like {\"k\":1} in your query.",
                result.getTurn().getAssistantMessage());
    }

    @Test
    public void aMissingAssistantMessageIsAnError() {
        TeamAgentTurnParser.Result result =
                TeamAgentTurnParser.parse("{\"proposedCommand\":\"PROPOSE_OUTLINE\"}");
        assertFalse(result.isOk());
        assertNull(result.getTurn());
    }

    @Test
    public void nonJsonJunkIsAnError() {
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse("I could not decide.");
        assertFalse(result.isOk());
    }

    @Test
    public void malformedJsonIsAnError() {
        TeamAgentTurnParser.Result result = TeamAgentTurnParser.parse("{\"assistantMessage\": ");
        assertFalse(result.isOk());
    }
}
