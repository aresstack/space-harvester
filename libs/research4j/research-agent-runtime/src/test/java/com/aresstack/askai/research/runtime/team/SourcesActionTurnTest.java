package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** Plan PR 15: the Sources assistant's optional action travels in the generic turn and round-trips. */
public class SourcesActionTurnTest {

    @Test
    public void aRequestResearchActionParsesAndRoundTrips() {
        TeamAgentTurnParser.Result parsed = TeamAgentTurnParser.parse("{\"assistantMessage\":\"I add it to the plan.\","
                + "\"action\":{\"type\":\"request_research\",\"query\":\"JPMS layers\",\"node\":\"Module system\"}}");
        assertTrue(parsed.isOk());
        TeamAgentTurn.Action action = parsed.getTurn().getAction();
        assertEquals("request_research", action.getType());
        assertEquals("JPMS layers", action.getQuery());
        assertEquals("Module system", action.getNode());

        TeamAgentTurn again = TeamAgentTurnParser.parse(parsed.getTurn().canonicalJson()).getTurn();
        assertEquals("JPMS layers", again.getAction().getQuery());
        assertEquals("Module system", again.getAction().getNode());
    }

    @Test
    public void aBlacklistActionNeedsItsEntryAndUnknownTypesAreDropped() {
        assertEquals("OSGi", TeamAgentTurnParser.parse("{\"assistantMessage\":\"ok\",\"action\":{\"type\":"
                + "\"blacklist_add\",\"entry\":\"OSGi\"}}").getTurn().getAction().getEntry());
        assertNull(TeamAgentTurnParser.parse("{\"assistantMessage\":\"ok\",\"action\":{\"type\":"
                + "\"blacklist_add\"}}").getTurn().getAction());
        assertNull(TeamAgentTurnParser.parse("{\"assistantMessage\":\"ok\",\"action\":{\"type\":"
                + "\"source_accept\",\"query\":\"x\"}}").getTurn().getAction());
        assertNull(TeamAgentTurnParser.parse("{\"assistantMessage\":\"ok\"}").getTurn().getAction());
    }

    @Test
    public void theSourcesProfileDescribesBothActionsAndTheUnionOffersOnlyThem() {
        String prompt = TeamAgentPlaybook.researchSystemPrompt();
        assertTrue(prompt.contains("request_research"));
        assertTrue(prompt.contains("blacklist_add"));
        assertEquals(Arrays.asList("request_research", "blacklist_add"), PhaseActionUnion.forPhase("research",
                Arrays.asList("research_status", "source_accept", "blacklist_add", "request_research")));
    }
}
