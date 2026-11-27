package com.aresstack.askai.research.runtime;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.runtime.loop.ContributionToolInvoker;
import com.aresstack.askai.research.runtime.team.TeamAgentTurn;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 15: "research:" only in research/running; a typed line never starts the research loop by itself. */
public class SourcesConversationRoutingTest {

    private static final class Collecting implements AgentOutput {
        final List<String> lines = new CopyOnWriteArrayList<String>();

        public void message(String text) {
            lines.add(text);
        }

        boolean saw(String fragment) {
            for (String line : lines) {
                if (line.contains(fragment)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static ResearchAgentSession session(final String status) {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_SESSION_ID", "itest-15");
        env.put("ASKAI_PROJECT_ID", "p1");
        return new ResearchAgentSession(ResearchAgentEnvironment.from(env),
                new ContributionToolInvoker(Collections.singletonList(
                        McpToolContribution.of("research_status", "Current research state.", new McpToolHandler() {
                            public McpToolResult invoke(McpToolCall call) {
                                return McpToolResult.ok(status);
                            }
                        }))), null, null);
    }

    @Test
    public void researchPrefixOutsideResearchRunningIsRefusedBeforeAnyBrowserCall() {
        ResearchAgentSession session = session("outline/running rev=3");
        try {
            session.open();
            Collecting out = new Collecting();
            assertEquals(AgentTurnOutcome.END_TURN, session.prompt("research: class loaders", out));
            assertTrue("the refusal points to /search", out.saw("/search"));
            assertFalse("no research run outcome", out.saw("#RSX1# outcome"));
            assertFalse("no browser refusal of a started run",
                    out.saw("cannot run autonomous web research this turn"));
        } finally {
            session.close();
        }
    }

    @Test
    public void researchPrefixInResearchRunningStillReachesTheLoop() {
        ResearchAgentSession session = session("research/running rev=3");
        try {
            session.open();
            Collecting out = new Collecting();
            session.prompt("research: class loaders", out);
            assertTrue("the loop path ran (no browser here: its honest refusal)",
                    out.saw("cannot run autonomous web research this turn"));
        } finally {
            session.close();
        }
    }

    @Test
    public void aTypedLineInResearchRunningStartsNoAcquisition() {
        ResearchAgentSession session = session("research/running rev=3");
        try {
            session.open();
            Collecting out = new Collecting();
            assertEquals(AgentTurnOutcome.END_TURN, session.prompt("which parts are still thin?", out));
            assertFalse("no research loop", out.saw("cannot run autonomous web research this turn"));
            assertFalse("no run outcome", out.saw("#RSX1# outcome"));
        } finally {
            session.close();
        }
    }

    @Test
    public void theReceiptComesFromTheHostAnswer() {
        TeamAgentTurn.Action search = TeamAgentTurn.Action.requestResearch("JPMS layers", "Module system");
        assertTrue(ResearchAgentSession.sourcesActionReceipt(search, "ADDED plan entry e1 \"JPMS layers\"", "en")
                .startsWith("Added to the search plan: \"JPMS layers\""));
        assertTrue(ResearchAgentSession.sourcesActionReceipt(search, "Tool failed: there is no search plan yet.",
                "en").equals("The search plan was not changed: there is no search plan yet."));
        TeamAgentTurn.Action blacklist = TeamAgentTurn.Action.blacklistAdd("OSGi");
        assertTrue(ResearchAgentSession.sourcesActionReceipt(blacklist, "ADDED blacklist entry \"OSGi\"", "de")
                .startsWith("Auf die Blacklist gesetzt"));
    }
}
