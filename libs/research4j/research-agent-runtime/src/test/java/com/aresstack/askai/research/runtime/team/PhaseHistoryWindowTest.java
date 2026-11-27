package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Plan PR 17: phase-tagged history, the budgeted window, tool-round collapse, overflow guard, serialisation. */
public class PhaseHistoryWindowTest {

    private static final String DATE = "2026-11-08";

    private static ChatMessage user(String phase, String text) {
        return ChatMessage.user(text).tagged(phase, ChatMessage.Kind.USER);
    }

    private static ChatMessage fin(String phase, String text) {
        return ChatMessage.assistant(text).tagged(phase, ChatMessage.Kind.FINAL);
    }

    private static String repeat(char c, int n) {
        char[] chars = new char[n];
        Arrays.fill(chars, c);
        return new String(chars);
    }

    private static TeamAgentStateView view(String phase) {
        return new TeamAgentStateView(phase, "running", Collections.<String>emptyList());
    }

    private static PhaseContextAssembler assembler() {
        return new PhaseContextAssembler(new PhaseContextAssembler.CurrentDate() {
            public String today() {
                return DATE;
            }
        });
    }

    private static String answer(String message) {
        return "{\"assistantMessage\":\"" + message + "\"}";
    }

    // ------------------------------------------------------------------ the window

    @Test
    public void theWindowHoldsOnlyOwnPhaseUserAndFinalEntriesNewestFirstWithinTheBudget() {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        history.add(user("scoping", "concept question"));
        history.add(fin("scoping", "concept answer"));
        history.add(user("research", "old sources question " + repeat('a', 300)));
        history.add(ChatMessage.assistant("tool round").tagged("research", ChatMessage.Kind.TOOL_ROUND));
        history.add(fin("research", "old sources answer " + repeat('b', 300)));
        history.add(user("research", "new question"));
        history.add(fin("research", "new answer"));

        List<ChatMessage> all = HistoryWindow.select(history, "research", 10_000);
        assertEquals(4, all.size());
        assertTrue(all.get(0).getContent().startsWith("old sources question"));
        assertEquals("new answer", all.get(3).getContent());
        for (ChatMessage entry : all) {
            assertEquals("research", entry.getPhase());
            assertFalse(entry.getKind() == ChatMessage.Kind.TOOL_ROUND);
        }

        List<ChatMessage> tight = HistoryWindow.select(history, "research", 20);
        assertEquals("only the newest entries that fit, as a contiguous tail", 2, tight.size());
        assertEquals("new question", tight.get(0).getContent());
        assertTrue(HistoryWindow.select(history, "research", 0).isEmpty());
        assertEquals("'' is Concept", 2, HistoryWindow.select(history, "", 10_000).size());
    }

    @Test
    public void eachPhaseGetsItsOwnWindowBudgetAndNeverSeesAnotherPhasesMessages() {
        List<ChatMessage> history = new ArrayList<ChatMessage>();
        for (int i = 0; i < 30; i++) {
            history.add(user("scoping", "concept " + i + " " + repeat('c', 900)));
            history.add(fin("scoping", "concept answer " + i + " " + repeat('d', 900)));
        }
        for (int i = 0; i < 30; i++) {
            history.add(user("research", "sources " + i + " " + repeat('s', 900)));
            history.add(fin("research", "sources answer " + i + " " + repeat('t', 900)));
        }
        String[] phases = {"scoping", "research", "outline", "draft"};
        for (String phase : phases) {
            PromptBlockMeter meter = new PromptBlockMeter();
            PhaseAssistantProfile profile = PhaseAssistantProfileRegistry.defaults().forPhase(phase);
            List<ChatMessage> messages = assembler().assemble(profile, view(phase), history, meter);
            Integer chars = meter.blocks().get(PromptBlockMeter.HISTORY);
            int tokens = chars == null ? 0 : (chars + 2) / 3;
            assertTrue(phase + " window within its budget", tokens <= HistoryWindow.chatBudget(phase));
            for (ChatMessage message : messages) {
                if (message.getKind() != null) {
                    assertEquals(phase + " sees only its own phase", phase, message.getPhase());
                }
            }
        }
        assertEquals(4000, HistoryWindow.chatBudget("scoping"));
        assertEquals(3000, HistoryWindow.chatBudget("research"));
    }

    // ------------------------------------------------------------------ the agent

    @Test
    public void aFortyTurnConceptConversationStaysWithinBudgetAndKeepsTheLastUserMessage() {
        FakeModel model = new FakeModel(32768);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        for (int turn = 0; turn < 40; turn++) {
            model.enqueueOk("{\"assistantMessage\":\"Antwort " + turn + " " + repeat('x', 1200)
                    + "\",\"researchBriefMarkdown\":\"Brief " + turn + "\","
                    + "\"searchSuggestions\":[{\"query\":\"topic current developments\",\"priority\":1}]}");
            String text = "Nachricht " + turn + " " + repeat('y', 600);
            TeamAgentResult result = agent.respond(text, view("scoping"));
            assertEquals(TeamAgentResult.Status.OK, result.getStatus());
            List<ChatMessage> sent = model.calls.get(turn);
            assertEquals("the last user message is never dropped", text, sent.get(sent.size() - 1).getContent());
            int history = 0;
            for (ChatMessage message : sent.subList(0, sent.size() - 1)) {
                if (message.getKind() != null) {
                    history += HistoryWindow.tokensOf(message);
                }
            }
            assertTrue("turn " + turn + " window " + history, history <= HistoryWindow.CONCEPT_WINDOW_TOKENS);
            int prompt = (PromptBlockMeter.charsOf(sent) + 2) / 3;
            assertTrue("turn " + turn + " fits num_ctx",
                    prompt + ResearchTeamAgent.CONCEPT_TURN_BUDGET + ResearchTeamAgent.CONTEXT_MARGIN_TOKENS <= 32768);
        }
        assertEquals("every message stays stored", 80, agent.historySnapshot().size());
    }

    @Test
    public void anOversizedPromptFailsVisiblyAndSendsNothing() {
        FakeModel model = new FakeModel(8192);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());

        TeamAgentResult result = agent.respond(repeat('z', 30_000), view("research"));

        assertEquals(TeamAgentResult.Status.CONTEXT_TOO_LARGE, result.getStatus());
        assertTrue(result.getDetail().startsWith("Context too large for gemma4:e2b"));
        assertTrue("nothing is sent", model.calls.isEmpty());
        assertTrue("the user's message stays pending for a retry", agent.hasPendingTurn());
        assertTrue(TeamAgentReply.visible(result).contains("Context too large for gemma4:e2b"));
    }

    @Test
    public void theGuardShrinksTheWindowBeforeItFails() {
        FakeModel model = new FakeModel(32768);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        for (int turn = 0; turn < 6; turn++) {
            model.enqueueOk(answer("Antwort " + turn + " " + repeat('x', 3000)));
            agent.respond("Frage " + turn, view("research"));
        }
        int fullWindowCall = PromptBlockMeter.charsOf(model.calls.get(5));
        // A context that holds the fixed blocks and the answer budget but not the full window.
        int fixed = (fullWindowCall + 2) / 3 - HistoryWindow.PHASE_WINDOW_TOKENS;
        model.context = fixed + 1500 + ResearchTeamAgent.PHASE_TURN_BUDGET + ResearchTeamAgent.CONTEXT_MARGIN_TOKENS;
        model.enqueueOk(answer("ok"));

        TeamAgentResult result = agent.respond("letzte Frage", view("research"));

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        List<ChatMessage> sent = model.calls.get(6);
        assertEquals("letzte Frage", sent.get(sent.size() - 1).getContent());
        assertTrue("the window was trimmed", PromptBlockMeter.charsOf(sent) < fullWindowCall);
        assertTrue((PromptBlockMeter.charsOf(sent) + 2) / 3 + ResearchTeamAgent.PHASE_TURN_BUDGET
                + ResearchTeamAgent.CONTEXT_MARGIN_TOKENS <= model.context);
    }

    @Test
    public void toolRoundsCollapseIntoOneFinalEntryWithAReceipt() {
        FakeModel model = new FakeModel(0);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        model.enqueueOk(answer("first"));
        model.enqueueOk(answer("second"));
        model.enqueueOk(answer("third"));
        TeamAgentResult first = agent.respond("baue das Konzept", view("outline"));
        agent.internalTurn("tool feedback 1", view("outline"));
        TeamAgentResult last = agent.internalTurn("tool feedback 2", view("outline"));
        agent.completeTurn(last, "add_card Haltung");

        List<ChatMessage> history = agent.historySnapshot();
        assertEquals(4, history.size());
        assertEquals(ChatMessage.Kind.USER, history.get(0).getKind());
        assertEquals(ChatMessage.Kind.TOOL_ROUND, history.get(1).getKind());
        assertEquals(ChatMessage.Kind.TOOL_ROUND, history.get(2).getKind());
        assertEquals(ChatMessage.Kind.FINAL, history.get(3).getKind());
        assertTrue(history.get(3).getContent().contains("third"));
        assertTrue(history.get(3).getContent().endsWith("[applied: add_card Haltung]"));
        assertTrue(first.isOk());

        model.enqueueOk(answer("next"));
        agent.respond("weiter", view("outline"));
        String sent = model.calls.get(3).toString();
        int window = 0;
        for (ChatMessage message : model.calls.get(3)) {
            if (message.getKind() != null) {
                window++;
                assertFalse(message.getContent().contains("first"));
                assertFalse(message.getContent().contains("second"));
            }
        }
        assertEquals("one USER and one FINAL from the earlier turn", 2, window);
        assertFalse(sent.contains("tool feedback"));
    }

    @Test
    public void aToolRoundAlwaysSeesTheOpenTurnsUserMessageEvenBeyondTheWindow() {
        FakeModel model = new FakeModel(0);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        model.enqueueOk(answer("first"));
        model.enqueueOk(answer("second"));
        String longMessage = "CONSTRAINT " + repeat('w', 12_000); // above the 3,000-token window
        agent.respond(longMessage, view("outline"));
        agent.internalTurn("tool feedback", view("outline"));

        List<ChatMessage> round = model.calls.get(1);
        assertEquals("tool feedback", round.get(round.size() - 1).getContent());
        assertEquals(longMessage, round.get(round.size() - 3).getContent());
        assertTrue(round.get(round.size() - 2).getContent().contains("first"));
    }

    @Test
    public void theParseRepairNoLongerEchoesTheFailedAnswer() {
        FakeModel model = new FakeModel(0);
        ResearchTeamAgent agent = new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        model.enqueueOk("FAILED-ANSWER-" + repeat('q', 2000));
        model.enqueueOk(answer("repaired"));

        TeamAgentResult result = agent.respond("hallo", view("outline"));

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        List<ChatMessage> repair = model.calls.get(1);
        for (ChatMessage message : repair) {
            assertFalse("the failed answer is not sent again", message.getContent().contains("FAILED-ANSWER"));
        }
        assertTrue(repair.get(repair.size() - 1).getContent().startsWith("Your previous answer could not be read"));
        assertEquals("only the original prompt plus one note", model.calls.get(0).size() + 1, repair.size());
    }

    @Test
    public void aReviewTurnWaitsForAChatTurnAndItsToolRounds() throws Exception {
        final FakeModel model = new FakeModel(0);
        final ResearchTeamAgent agent =
                new ResearchTeamAgent(model, PhaseAssistantProfileRegistry.defaults(), assembler());
        model.enqueueOk(answer("chat"));
        model.enqueueOk(answer("round"));
        model.enqueueOk(answer("review"));
        final Thread[] review = new Thread[1];
        agent.exclusively(new java.util.function.Supplier<Void>() {
            public Void get() {
                agent.respond("frage", view("research"));
                review[0] = new Thread(new Runnable() {
                    public void run() {
                        agent.reviewTurn("review the new sources", view("research"));
                    }
                });
                review[0].start();
                try {
                    review[0].join(300);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                assertTrue("the review is blocked while the turn runs", review[0].isAlive());
                agent.internalTurn("tool feedback", view("research"));
                agent.completeTurn(null, "");
                return null;
            }
        });
        review[0].join(5000);
        assertFalse(review[0].isAlive());
        assertEquals(3, model.calls.size());
        assertTrue("the review ran last", model.calls.get(2).get(model.calls.get(2).size() - 1).getContent()
                .contains("review the new sources"));
        List<ChatMessage> history = agent.historySnapshot();
        assertEquals(ChatMessage.Kind.USER, history.get(0).getKind());
        assertEquals(ChatMessage.Kind.TOOL_ROUND, history.get(1).getKind());
        assertEquals(ChatMessage.Kind.FINAL, history.get(2).getKind());
        assertEquals("the review is its own FINAL", ChatMessage.Kind.FINAL, history.get(3).getKind());
    }

    private static final class FakeModel implements MainModelChat {
        final Deque<MainModelChatResult> scripted = new ArrayDeque<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();
        volatile int context;

        FakeModel(int context) {
            this.context = context;
        }

        void enqueueOk(String raw) {
            scripted.add(MainModelChatResult.ok(raw));
        }

        public synchronized MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                                         int maxOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            if (scripted.isEmpty()) {
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "no script");
            }
            return scripted.removeFirst();
        }

        @Override
        public int contextTokens() {
            return context;
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }
}
