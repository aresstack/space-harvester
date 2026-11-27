package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.AdviceDecision;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.CandidateOffer;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult;
import com.aresstack.askai.research.runtime.scope.MainModelScopeAdviceChooser.ChooserSettings;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The narrowly scoped chooser: exactly ONE model call over the finished offers; it may pick AT
 * MOST ONE offered id or NONE; an invented id is a typed INVALID_RESPONSE (never re-bound, never
 * degraded to a silent NONE); drift guards carry no selectable id and can therefore never win;
 * typed model failures survive untouched.
 */
public class MainModelScopeAdviceChooserTest {

    private static final class ScriptedChat implements MainModelChat {
        private final MainModelChatResult result;
        final List<List<ChatMessage>> calls = new ArrayList<List<ChatMessage>>();

        ScriptedChat(MainModelChatResult result) {
            this.result = result;
        }

        @Override
        public MainModelChatResult complete(List<ChatMessage> messages, double temperature,
                                            int maxOutputTokens) {
            calls.add(messages);
            return result;
        }

        @Override
        public String modelName() {
            return "scripted";
        }
    }

    private static ChoiceRequest request() {
        return new ChoiceRequest(
                "Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?",
                Arrays.asList(
                        new CandidateOffer("pending-prov-exo",
                                ScopeAdviceCandidate.Reason.RESOLVE_PENDING,
                                "Exoskelette zur Entlastung",
                                "bereits angesprochen über: Exoskelett-Hypothese"),
                        new CandidateOffer("extension-in-sensorik",
                                ScopeAdviceCandidate.Reason.CHECK_IN_EXTENSION,
                                "AR-Gefahrenvisualisierung",
                                "Rand der bereits aufgenommenen Region: Schutzsensorik")),
                Arrays.asList("\"private Fitness-Optimierung\" bleibt bewusst ausgeschlossen"));
    }

    private static MainModelScopeAdviceChooser chooser(ScriptedChat chat) {
        return new MainModelScopeAdviceChooser(chat, new ChooserSettings(0.4d, 1024));
    }

    @Test
    public void oneCallOneOfferedCandidateOneQuestion() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "{\"decision\":\"ASK\",\"candidateId\":\"pending-prov-exo\","
                        + "\"assistantMessage\":\"Sollen Exoskelette nun aufgenommen werden?\"}"));
        ChoiceResult result = chooser(chat).choose(request());

        assertEquals("one choice = ONE model call", 1, chat.calls.size());
        assertTrue(result.isOk());
        assertEquals(AdviceDecision.Decision.ASK, result.getDecision().getDecision());
        assertEquals("pending-prov-exo", result.getDecision().getCandidateId());
        assertEquals("Sollen Exoskelette nun aufgenommen werden?",
                result.getDecision().getAssistantMessage());
        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue("offers reach the model with their reasons",
                prompt.contains("bereits angesprochen, noch unentschieden"));
        assertTrue("drift guards are visible but marked non-selectable",
                prompt.contains("NICHT wählbar"));
    }

    @Test
    public void noneIsALegitimateAnswerNeverScopeComplete() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "{\"decision\":\"NONE\",\"assistantMessage\":"
                        + "\"Diese Prüfung ergab keinen neuen Klärungspunkt.\"}"));
        ChoiceResult result = chooser(chat).choose(request());

        assertTrue(result.isOk());
        assertEquals(AdviceDecision.Decision.NONE, result.getDecision().getDecision());
        assertEquals("", result.getDecision().getCandidateId());
    }

    /** The model may not invent — an unknown id (incl. any drift-guard text) fails typed. */
    @Test
    public void anInventedCandidateIdIsInvalidResponseNeverSilentlyNone() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "{\"decision\":\"ASK\",\"candidateId\":\"drift-fitness\","
                        + "\"assistantMessage\":\"Soll private Fitness doch rein?\"}"));
        ChoiceResult result = chooser(chat).choose(request());

        assertEquals(ChoiceResult.Status.INVALID_RESPONSE, result.getStatus());
        assertTrue(result.getMessage().contains("never offered"));
    }

    @Test
    public void malformedAnswersAndMissingPiecesAreTypedFailures() {
        assertEquals(ChoiceResult.Status.INVALID_RESPONSE,
                chooser(new ScriptedChat(MainModelChatResult.ok("Gerne! Ich würde fragen...")))
                        .choose(request()).getStatus());
        assertEquals("ASK without candidateId is broken",
                ChoiceResult.Status.INVALID_RESPONSE,
                chooser(new ScriptedChat(MainModelChatResult.ok(
                        "{\"decision\":\"ASK\",\"assistantMessage\":\"Frage?\"}")))
                        .choose(request()).getStatus());
        assertEquals("ASK without a phrased question is broken",
                ChoiceResult.Status.INVALID_RESPONSE,
                chooser(new ScriptedChat(MainModelChatResult.ok(
                        "{\"decision\":\"ASK\",\"candidateId\":\"pending-prov-exo\"}")))
                        .choose(request()).getStatus());
    }

    @Test
    public void typedModelFailuresSurviveUntouched() {
        ScriptedChat timeout = new ScriptedChat(MainModelChatResult.failure(
                MainModelChatResult.Status.TIMEOUT, "read timed out"));
        ChoiceResult result = chooser(timeout).choose(request());
        assertEquals(ChoiceResult.Status.TIMEOUT, result.getStatus());
        assertEquals("read timed out", result.getMessage());
        assertEquals("no retry, no repair call", 1, timeout.calls.size());
    }

    @Test
    public void aMarkdownFencedAnswerIsUnwrappedLocally() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "```json\n{\"decision\":\"NONE\",\"assistantMessage\":\"Nichts offen.\"}\n```"));
        assertTrue(chooser(chat).choose(request()).isOk());
    }
}
