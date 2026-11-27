package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAdviceCandidate;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.AdviceDecision;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.CandidateOffer;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceRequest;
import com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommand;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the advice-chooser wire ACROSS the process boundary like every other wire pair: host
 * encoders against the runtime parser, runtime result renderer against the host Gson decoder.
 */
public class ScopeAdviceWireRoundTripTest {

    @Test
    public void aHostOfferSetSurvivesIntoTypedRuntimeObjects() {
        ChoiceRequest request = new ChoiceRequest(
                "Welche Wearables sind für den Arbeitsschutz relevant?",
                Arrays.asList(
                        new CandidateOffer("pending-prov-exo",
                                ScopeAdviceCandidate.Reason.RESOLVE_PENDING,
                                "Exoskelette \"aktiv\"",
                                "bereits angesprochen über: Exoskelett-Hypothese"),
                        new CandidateOffer("unexplored-probe-0007",
                                ScopeAdviceCandidate.Reason.CHECK_UNEXPLORED,
                                "Alleinarbeiterschutz", "")),
                Arrays.asList("\"private Fitness\" bleibt bewusst ausgeschlossen"));
        String requestJson = com.aresstack.askai.research.scope.ScopeAdviceWireCodec
                .encodeRequest(request, 0.4d, 1024);
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire
                .chooseAdvice("req-2", requestJson);

        ResearchServiceCommand command =
                com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire
                        .parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_CHOOSE_ADVICE, command.getType());
        assertEquals("req-2", command.getRequestId());

        ScopeAdviceWire.ParsedRequest parsed = ScopeAdviceWire.parseRequest(command.getRequest());
        assertEquals(request.getMission(), parsed.request.getMission());
        assertEquals(2, parsed.request.getCandidates().size());
        CandidateOffer first = parsed.request.getCandidates().get(0);
        assertEquals("pending-prov-exo", first.getCandidateId());
        assertEquals(ScopeAdviceCandidate.Reason.RESOLVE_PENDING, first.getReason());
        assertEquals("Exoskelette \"aktiv\"", first.getTopicText());
        assertEquals("bereits angesprochen über: Exoskelett-Hypothese", first.getContextNote());
        assertEquals(1, parsed.request.getDriftGuardNotes().size());
        assertTrue(parsed.request.offersCandidate("unexplored-probe-0007"));
        assertEquals(0.4d, parsed.settings.temperature, 1e-9);
        assertEquals(1024, parsed.settings.maxOutputTokens);
    }

    @Test
    public void anAskDecisionSurvivesIntoTheHostTypedResult() {
        String payload = ScopeAdviceWire.renderResult(ChoiceResult.ok(AdviceDecision.ask(
                "pending-prov-exo", "Sollen Exoskelette\nnun aufgenommen werden?")));

        ChoiceResult hostResult = com.aresstack.askai.research.scope.ScopeAdviceWireCodec
                .decodeResult(payload);

        assertTrue(hostResult.isOk());
        assertEquals(AdviceDecision.Decision.ASK, hostResult.getDecision().getDecision());
        assertEquals("pending-prov-exo", hostResult.getDecision().getCandidateId());
        assertEquals("Sollen Exoskelette\nnun aufgenommen werden?",
                hostResult.getDecision().getAssistantMessage());
    }

    @Test
    public void aNoneDecisionAndATypedFailureSurviveTheBoundary() {
        ChoiceResult none = com.aresstack.askai.research.scope.ScopeAdviceWireCodec.decodeResult(
                ScopeAdviceWire.renderResult(ChoiceResult.ok(
                        AdviceDecision.none("Diesmal nichts Neues."))));
        assertTrue(none.isOk());
        assertEquals(AdviceDecision.Decision.NONE, none.getDecision().getDecision());

        ChoiceResult failure = com.aresstack.askai.research.scope.ScopeAdviceWireCodec
                .decodeResult(ScopeAdviceWire.renderResult(ChoiceResult.failure(
                        ChoiceResult.Status.INVALID_RESPONSE, "model chose an unknown id")));
        assertEquals(ChoiceResult.Status.INVALID_RESPONSE, failure.getStatus());
        assertEquals("model chose an unknown id", failure.getMessage());
    }

    @Test
    public void theRunWireLineCarriesThePayloadCorrelated() {
        String line = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .adviceDecision("req-7", "{\"status\":\"OK\",\"decision\":\"NONE\"}");
        assertEquals(com.aresstack.askai.research.agentwire.ResearchRunWire.TYPE_ADVICE,
                com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        java.util.Map<String, String> fields =
                com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("req-7", fields.get("request_id"));
        assertEquals("{\"status\":\"OK\",\"decision\":\"NONE\"}",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(fields, "payload"));
    }
}
