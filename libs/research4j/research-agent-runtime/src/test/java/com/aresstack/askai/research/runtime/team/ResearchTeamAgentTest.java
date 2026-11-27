package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The model-backed conversation engine, driven entirely by a scripted fake model (no HTTP, no real model). */
public class ResearchTeamAgentTest {

    private static TeamAgentStateView scoping() {
        return new TeamAgentStateView("scoping", "new", Arrays.asList("START", "SUBMIT_SCOPE"));
    }

    /** A non-scoping phase: it uses the generic DEFAULT output contract (no phase-specific profile yet). */
    private static TeamAgentStateView defaultPhase() {
        return new TeamAgentStateView("outline", "running", Arrays.asList("START", "SUBMIT_SCOPE"));
    }

    /** A valid substantive scoping answer: message + brief + exploration map + one search suggestion. */
    private static String scopingJson(String message, String brief) {
        return "{\"assistantMessage\":\"" + message + "\",\"researchBriefMarkdown\":\"" + brief + "\","                + "\"searchSuggestions\":[{\"query\":\"topic current developments\",\"priority\":1}]}";
    }

    @Test
    public void greetingAsksTheModelAndRecordsExactlyOneAssistantTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Hi! What would you like to find out?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.greet(scoping());
        assertTrue(agent.hasGreeted());
        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertEquals("Hi! What would you like to find out?", result.getTurn().getAssistantMessage());
        // The model saw the system prompt + the greeting bootstrap, and nothing was invented.
        List<ChatMessage> firstCall = model.calls.get(0);
        assertEquals(ChatMessage.Role.SYSTEM, firstCall.get(0).getRole());
        assertTrue(firstCall.get(0).getContent().contains("research assistant"));
        assertTrue(lastUser(firstCall).contains("just started"));
    }

    @Test
    public void greetingIsIdempotentAndNeverReAsksTheModelOrDoubleGreets() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Hi! What would you like to find out?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult first = agent.greet(scoping());
        TeamAgentResult second = agent.greet(scoping());

        assertEquals("a second greet returns the cached greeting", first, second);
        assertEquals("the model is asked to greet exactly once", 1, model.calls.size());
    }

    @Test
    public void aProposedCommandInTheAllowedSetIsValidatedAndSurfaced() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Let me summarize.\",\"proposedCommand\":\"SUBMIT_SCOPE\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult allowed = agent.respond("electric cars", defaultPhase());
        assertEquals(TeamAgentResult.Status.OK, allowed.getStatus());
        assertEquals("SUBMIT_SCOPE", allowed.getValidatedCommand());
        assertEquals("Let me summarize.", allowed.getTurn().getAssistantMessage());
    }

    @Test
    public void aLegacyCommandTheHostDoesNotAllowIsSilentlyIgnoredNeverPolicedOrRejected() {
        FakeModel model = new FakeModel();
        // The assistant is not supposed to emit commands at all anymore. If a legacy proposedCommand the
        // host does not allow still appears, it is simply ignored: the friendly message is shown, no
        // repair nag, no COMMAND_REJECTED, no second model call.
        model.enqueueOk("{\"assistantMessage\":\"Happy to help with that.\","
                + "\"proposedCommand\":\"START_RESEARCH\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("go", defaultPhase());

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertNull("a disallowed command is dropped, not surfaced", result.getValidatedCommand());
        assertEquals("Happy to help with that.", result.getTurn().getAssistantMessage());
        assertEquals("no policing repair — exactly one model call", 1, model.calls.size());
    }

    @Test
    public void theStateBlockCarriesNoProposedOrConfirmedQuestion() {
        // Plan PR 15: the proposed and confirmed question and aspects are gone; the scope travels in the
        // phase-gated blocks only.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Got it.\",\"scope\":{\"question\":\"How do EVs age?\","
                + "\"aspects\":[\"battery\",\"cost\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"Anything else?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.respond("EV ageing", defaultPhase());
        agent.respond("no", defaultPhase());
        // (Context order: phase prompt, current date, working language, state context.)
        String context = model.calls.get(1).get(3).getContent();
        assertFalse(context, context.contains("research question"));
        assertFalse(context, context.contains("not yet confirmed"));
    }

    @Test
    public void aModelTransportFailureIsHonestlyUnavailableAndNeverFabricatesATurn() {
        FakeModel model = new FakeModel();
        model.enqueue(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "down"));
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.greet(scoping());
        assertEquals(TeamAgentResult.Status.MODEL_UNAVAILABLE, result.getStatus());
        assertNull(result.getTurn());
        assertFalse("a failed greeting can be retried", agent.hasGreeted());
    }

    @Test
    public void retryingAPendingTurnDoesNotDuplicateTheUserMessage() {
        FakeModel model = new FakeModel();
        model.enqueue(MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "down"));
        model.enqueueOk("{\"assistantMessage\":\"Recovered.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult failed = agent.respond("electric cars", defaultPhase());
        assertEquals(TeamAgentResult.Status.MODEL_UNAVAILABLE, failed.getStatus());
        assertTrue("the user turn stays pending after a failure", agent.hasPendingTurn());

        TeamAgentResult recovered = agent.retryPendingTurn(defaultPhase());
        assertEquals(TeamAgentResult.Status.OK, recovered.getStatus());
        assertFalse("an OK turn clears the pending turn", agent.hasPendingTurn());

        // The retry re-sent the SAME single user message — the history never doubled it.
        List<ChatMessage> retryCall = model.calls.get(1);
        assertEquals("exactly one 'electric cars' user turn reached the model", 1,
                countUserContent(retryCall, "electric cars"));
    }

    @Test
    public void anUnparseableAnswerGetsOneRepairThenAnHonestError() {
        FakeModel model = new FakeModel();
        model.enqueueOk("I cannot decide.");                 // not JSON
        model.enqueueOk("still no json object here");         // repair also fails
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("hello", scoping());
        assertEquals(TeamAgentResult.Status.UNUSABLE_ANSWER, result.getStatus());
        assertEquals("exactly one bootstrap call + one repair call", 2, model.calls.size());
        // The repair call carries the nudge, never the failed answer again (plan PR 17).
        assertTrue(lastUser(model.calls.get(1)).contains("one JSON object"));
        assertFalse(model.calls.get(1).toString().contains("I cannot decide."));
    }

    @Test
    public void aRepairThatSucceedsYieldsTheParsedTurn() {
        FakeModel model = new FakeModel();
        model.enqueueOk("Sorry, here you go:");                                // unparseable
        model.enqueueOk("{\"assistantMessage\":\"Recovered answer.\"}");       // repair OK
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("hi", defaultPhase());
        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertEquals("Recovered answer.", result.getTurn().getAssistantMessage());
    }

    @Test
    public void theAssistantPromptCarriesNoWorkflowOrCommandMachinery() {
        // The model must be an assistant, not a process controller: its system + state messages must not
        // advertise commands, phases or an output protocol the user could be policed against.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Hi! What would you like to find out?\"}");
        new ResearchTeamAgent(model).greet(scoping());
        String systemAndState = model.calls.get(0).get(0).getContent()
                + "\n" + model.calls.get(0).get(2).getContent()
                + "\n" + model.calls.get(0).get(3).getContent();
        assertFalse("no allowed-command policing", systemAndState.contains("Allowed commands"));
        assertFalse("no command names in the prompt", systemAndState.contains("SUBMIT_SCOPE"));
        assertFalse("no run-state machine framing", systemAndState.contains("run-state"));
    }

    @Test
    public void shortRepliesAccumulateContextAndTheUserIsNeverAskedForACommand() {
        // THE reported UX failure, scripted end to end: wearables -> audio and video -> smartwatches ->
        // keine ahnung. Each short reply is an answer to the last question; the assistant accumulates
        // context, offers defaults when the user does not know, and never talks protocol.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Which kind of wearables?\","
                + "\"understoodFacts\":[\"topic: wearables\"],"
                + "\"scope\":{\"question\":\"wearables\",\"aspects\":[]}}");
        model.enqueueOk("{\"assistantMessage\":\"Got it, focus on audio and video. Which device class?\","
                + "\"understoodFacts\":[\"focus: audio\",\"focus: video\"],"
                + "\"scope\":{\"question\":\"wearables\",\"aspects\":[\"audio\",\"video\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"Okay: smartwatches with audio and video. Which "
                + "criteria matter?\",\"understoodFacts\":[\"device: smartwatches\"],"
                + "\"scope\":{\"question\":\"smartwatches\",\"aspects\":[\"audio\",\"video\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"No problem, I would compare battery, audio, privacy "
                + "and price. I'll take these to start.\","
                + "\"suggestedFacts\":[\"battery\",\"audio quality\",\"privacy\",\"price\"],"
                + "\"scope\":{\"question\":\"smartwatches\",\"aspects\":[\"audio\",\"video\","
                + "\"battery\",\"privacy\"]}}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        assertTrue(agent.respond("wearables", defaultPhase()).isOk());
        assertTrue(agent.respond("audio und video", defaultPhase()).isOk());
        assertTrue(agent.respond("smartwatches", defaultPhase()).isOk());
        TeamAgentResult last = agent.respond("keine ahnung", defaultPhase());

        for (List<ChatMessage> call : model.calls) {
            for (ChatMessage message : call) {
                assertFalse(message.getContent().contains("provide a command"));
            }
        }
        // Context accumulated: by the last turn the model saw its earlier understood facts in history.
        String lastCallText = flatten(model.calls.get(model.calls.size() - 1));
        assertTrue("audio focus carried forward", lastCallText.contains("focus: audio"));
        assertTrue("device carried forward", lastCallText.contains("device: smartwatches"));
        // "keine ahnung" -> defaults offered as SUGGESTIONS; nothing here advances the workflow (no
        // readyForBrief flag exists anymore — only a user button can move a phase).
        assertTrue(last.isOk());
        assertFalse(last.getTurn().getSuggestedFacts().isEmpty());
    }

    @Test
    public void aRepairThatLeaksAFormatApologyIsSuppressedAsUnusableNotShownToTheUser() {
        // The reported GUI failure signature: a first answer that does not parse, then a repair whose
        // assistantMessage apologizes about formatting. That codec meta-talk must NEVER reach the user.
        // Uses the default contract so the apology PARSES (message present) and it is the validator — not a
        // missing field — that suppresses it.
        FakeModel model = new FakeModel();
        model.enqueueOk("Sorry, here you go:");                       // unparseable -> triggers repair
        model.enqueueOk("{\"assistantMessage\":\"I apologize if my previous response was not formatted "
                + "correctly. I am ready to proceed with the structured research.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("ja", defaultPhase());

        assertEquals(TeamAgentResult.Status.UNUSABLE_ANSWER, result.getStatus());
        assertTrue("the user's turn stays pending for a clean retry", agent.hasPendingTurn());
        String visible = TeamAgentReply.visible(result).toLowerCase(java.util.Locale.ROOT);
        assertFalse(visible.contains("json"));
        assertFalse(visible.contains("format"));
        assertFalse(visible.contains("apolog"));
        assertFalse(visible.contains("structured research"));
    }

    @Test
    public void assistantHistoryIsCanonicalStructuredJsonNotSyntheticBrackets() {
        // History must hold exactly one structured turn per assistant message (round-trippable), never the
        // old invented [understood: ...] / [still open: ...] note.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Got it.\",\"understoodFacts\":[\"topic: wearables\"],"
                + "\"openQuestions\":[\"which device class?\"],"
                + "\"scope\":{\"question\":\"wearables\",\"aspects\":[\"audio\"]}}");
        model.enqueueOk("{\"assistantMessage\":\"Anything else?\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.respond("wearables", defaultPhase());
        agent.respond("more", defaultPhase());

        // The second call's history carries the first assistant turn as canonical, re-parseable JSON.
        String recorded = assistantHistory(model.calls.get(1));
        assertFalse("no synthetic markers", recorded.contains("[understood:"));
        assertFalse("no synthetic markers", recorded.contains("[still open:"));
        TeamAgentTurnParser.Result reparsed = TeamAgentTurnParser.parse(recorded);
        assertTrue("the recorded assistant turn parses back", reparsed.isOk());
        assertEquals("Got it.", reparsed.getTurn().getAssistantMessage());
        assertTrue(reparsed.getTurn().getUnderstoodFacts().contains("topic: wearables"));
        assertTrue(reparsed.getTurn().getOpenQuestions().contains("which device class?"));
        assertEquals("wearables", reparsed.getTurn().getQuestion());
    }

    @Test
    public void theActivePhaseSelectsTheAssistantProfileAndSystemPrompt() {
        // Active phase -> its own assistant profile -> its own system prompt. The phase is the host's
        // (state.getPhaseId()); the model never chooses it. A phase without its own profile gets the fallback.
        FakeModel model = new FakeModel();
        model.enqueueOk(scopingJson("Hi from scoping.", "# Brief\\nWearables"));
        model.enqueueOk("{\"assistantMessage\":\"Hi from another phase.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        agent.respond("hello", scoping());
        agent.respond("hello", new TeamAgentStateView("outline", "new", Arrays.<String>asList()));

        String scopingPrompt = model.calls.get(0).get(0).getContent();
        String otherPrompt = model.calls.get(1).get(0).getContent();
        assertTrue("the scoping profile prompt is used in the scoping phase",
                scopingPrompt.contains("sharpen their research scope"));
        assertFalse(scopingPrompt.contains("working alongside the user within the current research phase"));
        assertTrue("a phase without its own profile falls back to the neutral profile",
                otherPrompt.contains("working alongside the user within the current research phase"));
    }

    @Test
    public void scopingPhaseUsesItsOwnContractAndProducesAResearchBrief() {
        // Proof that the MODEL INTERFACE — not just the prompt — is phase-specific: in the scoping phase the
        // output is a ScopingAssistantOutput carrying a research brief, not a generic TeamAgentTurn.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"You want to explore wearables.\","
                + "\"researchBriefMarkdown\":\"# Research Brief\\n\\n## Fragestellung\\n\\nWearables?\","                + "\"searchSuggestions\":[{\"query\":\"wearables 2026\",\"purpose\":\"tech\",\"priority\":1}]}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("wearables", scoping());

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertTrue("scoping yields the scoping output type",
                result.getOutput() instanceof ScopingAssistantOutput);
        assertNull("a scoping output is not a generic turn", result.getTurn());
        ScopingAssistantOutput scopingOutput = (ScopingAssistantOutput) result.getOutput();
        assertTrue(scopingOutput.getResearchBriefMarkdown().contains("Wearables?"));
        assertEquals(1, scopingOutput.getSearchSuggestions().size());
    }

    @Test
    public void aNonScopingPhaseKeepsTheGenericDefaultContract() {
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Outline reply.\",\"understoodFacts\":[\"x\"]}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("go", defaultPhase());

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertTrue("a non-scoping phase yields the generic turn",
                result.getOutput() instanceof TeamAgentTurn);
        assertEquals("Outline reply.", result.getTurn().getAssistantMessage());
    }

    @Test
    public void scopingAdviceIsAdvisoryAndNeverMovesTheWorkflow() {
        // advice=CONTINUE is DATA, not a command: no validated command, no transition — the phase is the
        // user's to move with a button. This pins that a recommendation can never become a hidden gate.
        FakeModel model = new FakeModel();
        model.enqueueOk("{\"assistantMessage\":\"Looks precise enough.\","
                + "\"researchBriefMarkdown\":\"# Brief\\nWearables audio\","                + "\"searchSuggestions\":[{\"query\":\"wearables audio\",\"priority\":1}],"
                + "\"advice\":{\"recommendation\":\"CONTINUE\",\"reason\":\"the question is precise\"}}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("audio", scoping());

        assertEquals(TeamAgentResult.Status.OK, result.getStatus());
        assertNull("advice never becomes a validated command", result.getValidatedCommand());
        ScopingAssistantOutput scopingOutput = (ScopingAssistantOutput) result.getOutput();
        assertEquals(PhaseAdviceRecommendation.CONTINUE, scopingOutput.getAdvice().getRecommendation());
    }

    @Test
    public void aScopingRepairThatCannotProduceAValidBriefFailsCleanlyAndInvisibly() {
        // Repair isolation holds under the scoping contract too: an invalid scoping answer, then an apology,
        // never surfaces meta-talk — it ends as UNUSABLE_ANSWER with a fixed, honest line.
        FakeModel model = new FakeModel();
        model.enqueueOk("here you go:");                                    // unparseable -> repair
        model.enqueueOk("{\"assistantMessage\":\"I apologize, here is the correctly formatted JSON.\"}");
        ResearchTeamAgent agent = new ResearchTeamAgent(model);

        TeamAgentResult result = agent.respond("ja", scoping());

        assertEquals(TeamAgentResult.Status.UNUSABLE_ANSWER, result.getStatus());
        String visible = TeamAgentReply.visible(result).toLowerCase(java.util.Locale.ROOT);
        assertFalse(visible.contains("json"));
        assertFalse(visible.contains("apolog"));
        assertFalse(visible.contains("formatted"));
    }

    /** The content of the last ASSISTANT message in a captured call (the recorded canonical turn). */
    private static String assistantHistory(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ChatMessage.Role.ASSISTANT) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    private static String flatten(List<ChatMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (ChatMessage message : messages) {
            sb.append(message.getContent()).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ fake model

    private static String lastUser(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getRole() == ChatMessage.Role.USER) {
                return messages.get(i).getContent();
            }
        }
        return "";
    }

    private static int countUserContent(List<ChatMessage> messages, String needle) {
        int count = 0;
        for (ChatMessage message : messages) {
            if (message.getRole() == ChatMessage.Role.USER && message.getContent().contains(needle)) {
                count++;
            }
        }
        return count;
    }

    private static final class FakeModel implements MainModelChat {
        final Deque<MainModelChatResult> scripted = new ArrayDeque<MainModelChatResult>();
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();

        /** Enqueue an OK model call whose raw text is {@code rawAnswer} (what the parser then processes). */
        void enqueueOk(String rawAnswer) {
            scripted.add(MainModelChatResult.ok(rawAnswer));
        }

        void enqueue(MainModelChatResult result) {
            scripted.add(result);
        }

        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls.add(new ArrayList<ChatMessage>(messages));
            if (scripted.isEmpty()) {
                return MainModelChatResult.failure(MainModelChatResult.Status.PROVIDER_FAILURE, "no script");
            }
            return scripted.removeFirst();
        }

        public String modelName() {
            return "gemma4:e2b";
        }
    }
}
