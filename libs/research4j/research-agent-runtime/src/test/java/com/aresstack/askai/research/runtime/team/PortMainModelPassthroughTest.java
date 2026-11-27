package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatRequest;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.agent.model.port.OutputFormat;
import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;

import org.junit.Test;

import java.time.Duration;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The model-backed TeamAgent driven end to end through the host's model port ({@link PortMainModelChat} over
 * a {@link FakeModelPort}). Proves the conversation slot reaches the port with the former call shape, the
 * greeting and a scope proposal come from the model, prior turns are carried in the history, and a disallowed
 * legacy command is silently ignored (the model no longer owns the workflow).
 */
public class PortMainModelPassthroughTest {

    // A non-scoping phase drives the GENERIC contract (proposedCommand + scope). The scoping phase now has its
    // own ScopingAssistantOutput contract, tested separately; this test pins the generic command path.
    private static TeamAgentStateView genericNew() {
        return new TeamAgentStateView("outline", "new", Arrays.asList("START"));
    }

    private static TeamAgentStateView genericRunning() {
        return new TeamAgentStateView("outline", "running", Arrays.asList("SUBMIT_SCOPE", "CANCEL"));
    }

    @Test
    public void teamAgentGreetsProposesScopeAndIgnoresDisallowedCommandsOverThePort() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueMessage("Hi! What would you like to research?");
        port.enqueueCommandProposal("Great, let me confirm the scope.", "SUBMIT_SCOPE",
                "How does pf4j isolate plugins?", Arrays.asList("class isolation", "versioning"));
        ResearchTeamAgent agent = new ResearchTeamAgent(new PortMainModelChat(port,
                ResearchModelSlots.PHASE_SCOPING, Duration.ofSeconds(120)));

        TeamAgentResult greeting = agent.greet(genericNew());
        assertEquals(TeamAgentResult.Status.OK, greeting.getStatus());
        assertTrue(greeting.getTurn().getAssistantMessage().contains("What would you like to research"));

        TeamAgentResult reply = agent.respond("pf4j isolation", genericRunning());
        assertEquals(TeamAgentResult.Status.OK, reply.getStatus());
        assertEquals("SUBMIT_SCOPE", reply.getValidatedCommand());

        // The conversation slot reached the port, with the per-call timeout and no thinking setting (the
        // former client sent none), and the greeting was carried in the second turn's history.
        assertTrue("the port saw the conversation slot", port.sawSlot(ResearchModelSlots.PHASE_SCOPING));
        ChatRequest second = port.requests().get(1);
        assertEquals(Duration.ofSeconds(120), second.getTimeout());
        assertEquals(ThinkingMode.DEFAULT, second.getThinking());
        assertEquals(ModelMessage.Role.SYSTEM, second.getMessages().get(0).getRole());
        assertTrue("the greeting is carried in the follow-up turn's history",
                FakeModelPort.historyContains(second, "What would you like to research"));

        // A legacy command the host does not allow is silently IGNORED now (the assistant no longer
        // owns the workflow): the friendly message is still shown, the command just does not surface,
        // and there is no policing repair round.
        port.enqueueCommandProposal("Happy to keep helping.", "START_RESEARCH", "q", Arrays.asList("a"));
        int callsBefore = port.requests().size();
        TeamAgentResult ignored = agent.respond("go", genericRunning());
        assertEquals(TeamAgentResult.Status.OK, ignored.getStatus());
        assertNull("a disallowed command does not surface", ignored.getValidatedCommand());
        assertEquals("Happy to keep helping.", ignored.getTurn().getAssistantMessage());
        assertEquals("no policing repair — exactly one more model call",
                callsBefore + 1, port.requests().size());
    }

    @Test
    public void jsonCompletionsAskThePortForGenericJsonOrTheGivenSchema() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("{\"a\":1}");
        port.enqueueRaw("{\"b\":2}");
        PortMainModelChat chat = new PortMainModelChat(port, ResearchModelSlots.SCOPE, null);
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));

        assertEquals("{\"a\":1}", chat.completeJson(messages, 0.2, 64, null).getText());
        assertEquals("{\"b\":2}", chat.completeJson(messages, 0.2, 64, "{\"type\":\"object\"}").getText());

        ChatRequest generic = port.requests().get(0);
        assertEquals(OutputFormat.Kind.JSON, generic.getOutputFormat().getKind());
        assertEquals("research calls never let Ollama cut the prompt (PR 17)", Boolean.FALSE,
                generic.getTruncate());
        assertEquals(ResearchModelSlots.SCOPE, generic.getSlot());
        assertEquals(Double.valueOf(0.2), generic.getTemperature());
        assertEquals(64, generic.getMaxOutputTokens());
        assertNull("no caller timeout = the platform default", generic.getTimeout());
        ChatRequest schema = port.requests().get(1);
        assertEquals(OutputFormat.Kind.JSON_SCHEMA, schema.getOutputFormat().getKind());
        assertEquals("{\"type\":\"object\"}", schema.getOutputFormat().getSchemaJson());
        assertEquals("slot scope", chat.modelName());
    }

    @Test
    public void aJsonAnswerCutOffAtItsBudgetFailsAndProseIsFlagged() {
        FakeModelPort port = new FakeModelPort();
        port.reportTelemetry(900, 64, com.aresstack.askai.agent.model.port.ChatResult.FinishReason.LENGTH,
                "gemma4:e2b", "");
        port.enqueueRaw("{\"a\":");
        port.enqueueRaw("Ein langer Text");
        PortMainModelChat chat = new PortMainModelChat(port, ResearchModelSlots.SCOPE, null);
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));

        MainModelChatResult json = chat.completeJson(messages, 0.2, 64, null);
        assertEquals("a cut-off JSON answer never reaches the parser", MainModelChatResult.Status.INVALID_RESPONSE,
                json.getStatus());
        assertTrue(json.getDetail().contains("cut off"));
        MainModelChatResult prose = chat.complete(messages, 0.2, 64);
        assertTrue("prose stays a result", prose.isOk());
        assertTrue("but is flagged", prose.isCutOff());
    }

    @Test
    public void contractRolesSendThinkingOffAndChatRolesLeaveItToTheSlot() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueRaw("readable");
        port.enqueueRaw("Hallo");
        java.util.List<ChatMessage> messages = Arrays.asList(ChatMessage.system("s"), ChatMessage.user("u"));
        PortMainModelChat.forRole(port, com.aresstack.askai.research.runtime.role.ResearchRoles.PAGE_READINESS_JUDGE,
                null).complete(messages, 0.0, 24);
        PortMainModelChat.forRole(port, com.aresstack.askai.research.runtime.role.ResearchRoles.CONVERSATION, null)
                .complete(messages, 0.4, 4096);
        assertEquals(com.aresstack.askai.agent.model.port.ThinkingMode.OFF, port.requests().get(0).getThinking());
        assertEquals("the page judge keeps its budget of 24", 24, port.requests().get(0).getMaxOutputTokens());
        assertEquals(com.aresstack.askai.agent.model.port.ThinkingMode.DEFAULT,
                port.requests().get(1).getThinking());
        for (com.aresstack.askai.research.runtime.role.ResearchRole role
                : com.aresstack.askai.research.runtime.role.ResearchRoles.orchestration()) {
            if (!role.getId().equals("writer")) {
                assertEquals("contract role " + role.getId(), com.aresstack.askai.agent.model.port.ThinkingMode.OFF,
                        role.getThinking());
            }
        }
    }
}
