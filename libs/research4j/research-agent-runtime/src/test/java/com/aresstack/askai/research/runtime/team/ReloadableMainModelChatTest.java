package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The hot-reload seam: a {@link ReloadableMainModelChat} swap at a turn boundary changes only the transport
 * underneath the {@link ResearchTeamAgent} — the conversation history, proposed/confirmed scope and pending
 * turn survive untouched, and the next turn goes to the new client.
 */
public class ReloadableMainModelChatTest {

    // A non-scoping phase: this test pins the hot-reload seam via the GENERIC scope mechanism (the scoping
    // phase now has its own brief-based contract, covered elsewhere).
    private static TeamAgentStateView scoping() {
        return new TeamAgentStateView("outline", "running", Arrays.asList("SUBMIT_SCOPE"));
    }

    @Test
    public void swapRoutesToTheNewClientAndReportsItsName() {
        ScriptedModel first = new ScriptedModel("model-a");
        ReloadableMainModelChat reloadable = new ReloadableMainModelChat(first);
        assertEquals("model-a", reloadable.modelName());

        ScriptedModel second = new ScriptedModel("model-b");
        reloadable.swap(second);
        assertEquals("model-b", reloadable.modelName());
        reloadable.complete(new ArrayList<ChatMessage>(), 0.4, 128);
        assertEquals("the call went to the new client", 1, second.calls);
        assertEquals("the old client is no longer used", 0, first.calls);
    }

    @Test
    public void aMidSessionSwapKeepsTheTeamAgentHistoryAndScope() {
        ScriptedModel first = new ScriptedModel("model-a");
        first.enqueue("{\"assistantMessage\":\"Hi!\"}");
        first.enqueue("{\"assistantMessage\":\"Got it.\",\"scope\":{\"question\":\"How do EVs age?\","
                + "\"aspects\":[\"battery\"]}}");
        ReloadableMainModelChat reloadable = new ReloadableMainModelChat(first);
        ResearchTeamAgent agent = new ResearchTeamAgent(reloadable);

        agent.greet(scoping());
        agent.respond("EV ageing", scoping());

        // Hot-swap the transport mid-session; the agent keeps its history + scope.
        ScriptedModel second = new ScriptedModel("model-b");
        second.enqueue("{\"assistantMessage\":\"Continuing with your question.\"}");
        reloadable.swap(second);

        agent.respond("anything else?", scoping());
        // The new client received the FULL prior conversation, not a fresh one.
        List<ChatMessage> lastCall = second.lastMessages;
        assertTrue("history carried over", containsUserContent(lastCall, "EV ageing"));
        assertTrue("greeting carried over", containsAssistantContent(lastCall, "Hi!"));
        assertFalse(lastCall.isEmpty());
    }

    private static boolean containsUserContent(List<ChatMessage> messages, String needle) {
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.USER && m.getContent().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAssistantContent(List<ChatMessage> messages, String needle) {
        for (ChatMessage m : messages) {
            if (m.getRole() == ChatMessage.Role.ASSISTANT && m.getContent().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static final class ScriptedModel implements MainModelChat {
        private final String name;
        private final Deque<String> answers = new ArrayDeque<String>();
        int calls;
        List<ChatMessage> lastMessages = new ArrayList<ChatMessage>();

        ScriptedModel(String name) {
            this.name = name;
        }

        void enqueue(String rawAnswer) {
            answers.add(rawAnswer);
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls++;
            lastMessages = new ArrayList<ChatMessage>(messages);
            return answers.isEmpty()
                    ? MainModelChatResult.ok("{\"assistantMessage\":\"ok\"}")
                    : MainModelChatResult.ok(answers.removeFirst());
        }

        public String modelName() {
            return name;
        }
    }
}
