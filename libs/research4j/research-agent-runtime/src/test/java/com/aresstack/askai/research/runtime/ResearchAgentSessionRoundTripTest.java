package com.aresstack.askai.research.runtime;

import com.aresstack.askai.mcp.api.McpToolCall;
import com.aresstack.askai.mcp.api.McpToolContribution;
import com.aresstack.askai.mcp.api.McpToolHandler;
import com.aresstack.askai.mcp.api.McpToolResult;
import com.aresstack.askai.research.runtime.loop.ContributionToolInvoker;
import org.junit.Test;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * The agent's boundary walk, IN-PROCESS (formerly through a real child process and real MCP): open runs the
 * readiness check against the research lane, the first turn announces readiness and the honest browser
 * absence, mirrors the live research_status, and the session stays usable for a second turn. A lane without
 * research_status fails open() atomically.
 */
public class ResearchAgentSessionRoundTripTest {

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

    private static ContributionToolInvoker researchLane(final AtomicInteger statusCalls) {
        return new ContributionToolInvoker(Collections.singletonList(
                McpToolContribution.of("research_status", "Current research state.", new McpToolHandler() {
                    public McpToolResult invoke(McpToolCall call) {
                        statusCalls.incrementAndGet();
                        return McpToolResult.ok("RESEARCH/running rev=7");
                    }
                })));
    }

    @Test
    public void fullBoundaryWalkInProcess() {
        final AtomicInteger statusCalls = new AtomicInteger();
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("ASKAI_SESSION_ID", "itest-1");
        env.put("ASKAI_PROJECT_ID", "p1");
        ResearchAgentSession session = new ResearchAgentSession(ResearchAgentEnvironment.from(env),
                researchLane(statusCalls), null, null);
        try {
            session.open();
            assertTrue("open() must call research_status", statusCalls.get() >= 1);

            // First turn: readiness + honest browser absence + the live research_status mirrored as lines.
            Collecting first = new Collecting();
            AgentTurnOutcome outcome = session.prompt("investigate pf4j", first);
            assertEquals(AgentTurnOutcome.END_TURN, outcome);
            assertTrue("readiness announced", first.saw("RESEARCH_MCP_READY"));
            assertTrue("browser absence visible", first.saw("BROWSER_NOT_AVAILABLE"));
            assertTrue("live research_status mirrored", first.saw("RESEARCH/running rev=7"));
            assertTrue("second research_status call", statusCalls.get() >= 2);

            // The session stays usable for a second turn.
            Collecting second = new Collecting();
            assertEquals(AgentTurnOutcome.END_TURN, session.prompt("next step", second));
            assertTrue("every turn re-reads the host state", statusCalls.get() >= 3);
        } finally {
            session.close();
        }
    }

    @Test
    public void aLaneWithoutResearchStatusFailsOpenAtomically() {
        ResearchAgentSession session = new ResearchAgentSession(
                ResearchAgentEnvironment.from(Collections.<String, String>emptyMap()),
                new ContributionToolInvoker(Collections.<McpToolContribution>emptyList()), null, null);
        try {
            session.open();
            fail("open() must fail when the research lane offers no research_status");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("research_status"));
        } finally {
            session.close();
        }
    }
}
