package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.agent.model.port.ChatResult;
import com.aresstack.askai.agent.model.port.ModelMessage;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;

import org.junit.After;
import org.junit.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Plan PR 01: every model call writes exactly one technical line with the phase, role, slot, model label,
 * block sizes, token usage, finish reason, durations and effective {@code num_ctx}, plus the warnings that
 * belong to it. The request itself is unchanged.
 */
public class ModelCallTelemetryTest {

    private static final ResearchRole ROLE = ResearchRole.builder("team-conversation",
                    ResearchModelSlots.PHASE_SCOPING)
            .purpose("test")
            .promptFromCaller()
            .output(RoleOutput.text("test"))
            .contextTokens(8192)
            .build();

    private final List<String> lines = Collections.synchronizedList(new ArrayList<String>());

    @After
    public void restoreSink() {
        ModelCallLog.setSink(null);
    }

    private ResearchTeamAgent agentOver(FakeModelPort port) {
        ModelCallLog.setSink(new ModelCallLog.Sink() {
            @Override
            public void line(String line) {
                lines.add(line);
            }
        });
        return new ResearchTeamAgent(PortMainModelChat.forRole(port, ROLE, Duration.ofSeconds(30)));
    }

    private List<String> callLines() {
        List<String> calls = new ArrayList<String>();
        for (String line : lines) {
            if (line.startsWith("[model-call] phase=")) {
                calls.add(line);
            }
        }
        return calls;
    }

    @Test
    public void aCutAnswerLogsOneLineWithAllFieldsAndAWarningNamingTheRole() {
        FakeModelPort port = new FakeModelPort();
        port.reportTelemetry(7000, 300, ChatResult.FinishReason.LENGTH, "gemma4:e2b", "thinking...");
        port.enqueueMessage("Hello.");
        ResearchTeamAgent agent = agentOver(port);

        TeamAgentResult result = agent.respond("Wie funktioniert pf4j?",
                new TeamAgentStateView("outline", "running", Arrays.asList("CANCEL")));

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        List<String> calls = callLines();
        assertEquals("exactly one line per call: " + lines, 1, calls.size());
        String line = calls.get(0);
        for (String field : Arrays.asList("phase=outline", "role=team-conversation", "slot=phase.scoping",
                "model=gemma4:e2b", "status=OK", "blocks=[profile=", " date=", " language=", " state=",
                " user-message=", "prompt_eval_count=7000", "eval_count=300", "done_reason=length",
                "thinking_chars=11", "duration_ms=1500", "load_ms=200", "prompt_eval_ms=700", "num_ctx=8192",
                "answer_budget=" + ResearchTeamAgent.turnBudget("outline"))) {
            assertTrue("missing " + field + " in " + line, line.contains(field));
        }
        boolean lengthWarning = false;
        boolean contextWarning = false;
        for (String warning : lines) {
            if (warning.contains("WARNING") && warning.contains("role team-conversation")) {
                lengthWarning |= warning.contains("done_reason=length");
                contextWarning |= warning.contains("prompt_eval_count 7000");
            }
        }
        assertTrue("length warning names the role: " + lines, lengthWarning);
        assertTrue("7000 + the 1536 budget exceeds num_ctx 8192: " + lines, contextWarning);
    }

    @Test
    public void blockSizesAddUpToTheCharactersOfTheAssembledMessages() {
        FakeModelPort port = new FakeModelPort();
        port.enqueueMessage("Hi! What would you like to research?");
        port.enqueueMessage("Gerne.");
        ResearchTeamAgent agent = agentOver(port);
        TeamAgentStateView state = new TeamAgentStateView("outline", "running", Arrays.asList("CANCEL"));
        agent.greet(state);
        agent.respond("Grüße „Zitat“ – ß", state);

        List<String> calls = callLines();
        assertEquals(2, calls.size());
        for (int i = 0; i < 2; i++) {
            int sent = 0;
            for (ModelMessage message : port.requests().get(i).getMessages()) {
                sent += message.getContent().length();
            }
            assertTrue(calls.get(i), calls.get(i).contains(" chars=" + sent + " "));
            assertEquals(sent, sumOfBlocks(calls.get(i)));
        }
        assertTrue(calls.get(0).contains("greeting-instruction="));
        assertTrue(calls.get(1).contains("history="));
    }

    @Test
    public void theEstimateWarnsBeforeTheContextCanOverflow() {
        PromptBlockMeter meter = new PromptBlockMeter().add(PromptBlockMeter.HISTORY, 3 * 4000);
        MainModelChatResult ok = MainModelChatResult.ok("x", ModelCallMetrics.builder()
                .role("writer").slot("research.writer").contextTokens(8192).answerBudget(4096).build());
        List<String> out = ModelCallLog.lines("draft", meter, ok);
        assertEquals(2, out.size());
        assertTrue(out.get(1), out.get(1).contains("role writer") && out.get(1).contains("estimate 4000"));
    }

    private static int sumOfBlocks(String line) {
        int start = line.indexOf("blocks=[") + 8;
        String blocks = line.substring(start, line.indexOf(']', start));
        int sum = 0;
        for (String part : blocks.split(" ")) {
            sum += Integer.parseInt(part.substring(part.indexOf('=') + 1));
        }
        return sum;
    }
}
