package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Pressing a button is not writing a sentence. The review instruction is machinery — it must never end up
 * in the conversation as something the user said, because every later turn is assembled on top of that
 * history and would be building on a fiction.
 */
public class InternalTurnIsNotAUserMessageTest {

    /** A model that answers usably and records the messages it was given. */
    private static final class RecordingModel implements MainModelChat {
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();

        public String modelName() {
            return "recording";
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maximumOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            return MainModelChatResult.ok("{\"assistantMessage\":\"Hier ist die Auswertung.\"}");
        }
    }

    private static TeamAgentStateView view() {
        return new TeamAgentStateView("research", "running", Collections.<String>emptyList());
    }

    @Test
    public void theInstructionNeverEntersTheConversationHistory() {
        RecordingModel model = new RecordingModel();
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.internalTurn(TeamAgentPlaybook.sourceSummaryInstruction(), view());
        assertTrue(result.isOk());

        // A second turn shows what the FIRST one left behind: its messages are the accumulated history.
        agent.internalTurn(TeamAgentPlaybook.sourceSummaryInstruction(), view());
        List<ChatMessage> secondCall = model.calls.get(1);
        int instructionsInHistory = 0;
        for (int i = 0; i < secondCall.size() - 1; i++) { // the last message IS this turn's instruction
            if (secondCall.get(i).getContent().contains("A web search just finished")) {
                instructionsInHistory++;
            }
        }
        assertEquals("the internal instruction is not remembered as a user message",
                0, instructionsInHistory);
    }

    @Test
    public void theAnswerItselfStaysPartOfTheConversation() {
        RecordingModel model = new RecordingModel();
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.internalTurn(TeamAgentPlaybook.sourceSummaryInstruction(), view());
        agent.internalTurn(TeamAgentPlaybook.sourceSummaryInstruction(), view());

        boolean answerRemembered = false;
        for (ChatMessage message : model.calls.get(1)) {
            answerRemembered |= message.getContent().contains("Hier ist die Auswertung.");
        }
        assertTrue("the user saw this answer, so the next turn must know it exists", answerRemembered);
    }

    /** An internal turn must not consume or invent the retry state of a real user message. */
    @Test
    public void anInternalTurnLeavesNoPendingUserTurnBehind() {
        RecordingModel model = new RecordingModel();
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.internalTurn(TeamAgentPlaybook.sourceSummaryInstruction(), view());

        assertFalse(agent.hasPendingTurn());
    }
}
