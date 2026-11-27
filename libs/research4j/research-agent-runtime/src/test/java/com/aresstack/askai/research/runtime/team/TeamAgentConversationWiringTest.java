package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The pure seams that wire the TeamAgent into the runtime's ACP prompt turn: parsing the host status line into
 * a state view, and turning a turn result into the single user-visible line. No process, no HTTP.
 */
public class TeamAgentConversationWiringTest {

    @Test
    public void statusLineParsesPhaseAndStateEvenWhenWrapped() {
        TeamAgentStateView view = ResearchStatusView.parse("RESEARCH/running rev=7");
        assertEquals("RESEARCH", view.getPhaseId());
        assertEquals("running", view.getStateId());

        TeamAgentStateView wrapped = ResearchStatusView.parse("ToolResult{content=scoping/new rev=0}");
        assertEquals("scoping", wrapped.getPhaseId());
        assertEquals("new", wrapped.getStateId());
    }

    @Test
    public void unreadableStatusYieldsANeutralViewNeverAFabricatedState() {
        TeamAgentStateView empty = ResearchStatusView.empty();
        assertEquals("", empty.getPhaseId());
        assertEquals("", empty.getStateId());
        assertTrue(empty.getAllowedCommands().isEmpty());

        assertEquals("", ResearchStatusView.parse(null).getPhaseId());
        assertEquals("", ResearchStatusView.parse("no state here").getPhaseId());
    }

    @Test
    public void statusLineAllowedCommandsAreParsedFromTheCmdsField() {
        TeamAgentStateView view =
                ResearchStatusView.parse("scoping/running rev=3 cmds=SUBMIT_SCOPE,CANCEL,PAUSE");
        assertEquals("scoping", view.getPhaseId());
        assertEquals("running", view.getStateId());
        assertTrue(view.allows("SUBMIT_SCOPE"));
        assertTrue(view.allows("CANCEL"));
        assertTrue(view.allows("PAUSE"));
        // A status line without a cmds field yields no allowed commands (never invents any).
        assertTrue(ResearchStatusView.parse("RESEARCH/running rev=7").getAllowedCommands().isEmpty());
    }

    @Test
    public void explicitAllowedCommandsAreCarriedIntoTheView() {
        TeamAgentStateView view = ResearchStatusView.parse("SCOPING/running rev=2",
                Arrays.asList("SUBMIT_SCOPE", "CANCEL"));
        assertTrue(view.allows("SUBMIT_SCOPE"));
        assertTrue(view.allows("CANCEL"));
    }

    @Test
    public void anOkResultShowsTheModelsOwnMessage() {
        TeamAgentResult ok = TeamAgentResult.ok(TeamAgentTurn.message("Hi! What shall we research?"), null);
        assertEquals("Hi! What shall we research?", TeamAgentReply.visible(ok));
    }

    @Test
    public void failuresShowHonestTypedLinesNeverAFabricatedAnswer() {
        assertTrue(TeamAgentReply.visible(TeamAgentResult.modelUnavailable("timeout"))
                .contains("cannot reach"));
        assertTrue(TeamAgentReply.visible(TeamAgentResult.modelUnavailable("timeout")).contains("timeout"));
        assertTrue(TeamAgentReply.visible(TeamAgentResult.unusableAnswer("bad json"))
                .toLowerCase().contains("rephrase"));
        // A rejected command NEVER surfaces the model's misleading message — only an honest, neutral line.
        String rejected = TeamAgentReply.visible(TeamAgentResult.commandRejected("START_RESEARCH"));
        assertTrue(rejected.contains("not available"));
        assertTrue("the withheld command name must not leak into the user line",
                !rejected.contains("START_RESEARCH"));
    }
}
