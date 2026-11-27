package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGeneration;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.runtime.service.ResearchServiceCommand;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Pins the probe-generation wire ACROSS the process boundary, the same way the service-command and
 * run wires are pinned: the HOST encoders (ui-plugin classes on this test classpath) against the
 * RUNTIME parser, and the RUNTIME result renderer against the HOST Gson decoder. If either side
 * drifts, this test fails — not a live session.
 */
public class ProbeGenerationWireRoundTripTest {

    @Test
    public void aHostRequestSurvivesTheEnvelopeAndTheJsonIntoTypedRuntimeObjects() {
        ProbeGenerationRequest request = new ProbeGenerationRequest(
                "Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?",
                Arrays.asList("Arbeitsschutz", "Bauwesen"),
                Arrays.asList("Baustellen in Deutschland"),
                Arrays.asList("Sensorhelme", "Hypothetische Drohnenwartung"),
                Arrays.asList(
                        new ScopeAnchor("anchor-helme", "f1", "Schutzhelme mit Sensorik",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-fitness", "f2", "Consumer-Fitness Armbänder",
                                ScopeAnchor.Membership.OUT),
                        new ScopeAnchor("anchor-guess", "f3", "Hypothetische Drohnenwartung",
                                ScopeAnchor.Membership.PROVISIONAL)),
                50);
        String requestJson = com.aresstack.askai.research.scope.ProbeGenerationWireCodec
                .encodeRequest(request, 0.7d, 4096, 2);
        String envelope = com.aresstack.askai.research.search.ResearchServiceCommandWire
                .generateProbes("req-1", requestJson);

        ResearchServiceCommand command =
                com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire
                        .parse(envelope);
        assertEquals(ResearchServiceCommand.TYPE_GENERATE_PROBES, command.getType());
        assertEquals("req-1", command.getRequestId());

        ProbeGenerationWire.ParsedRequest parsed =
                ProbeGenerationWire.parseRequest(command.getRequest());
        assertEquals(request.getMission(), parsed.request.getMission());
        assertEquals(request.getDomains(), parsed.request.getDomains());
        assertEquals(request.getKnownFacetLabels(), parsed.request.getKnownFacetLabels());
        assertEquals(50, parsed.request.getTargetCount());
        assertEquals(3, parsed.request.getAnchors().size());
        ScopeAnchor guess = parsed.request.getAnchors().get(2);
        assertEquals("anchor-guess", guess.getAnchorId());
        assertEquals("the PROVISIONAL role survives the boundary — the generator depends on it",
                ScopeAnchor.Membership.PROVISIONAL, guess.getMembership());
        assertEquals("Hypothetische Drohnenwartung", guess.getSemanticText());
        assertEquals(0.7d, parsed.settings.temperature, 1e-9);
        assertEquals(4096, parsed.settings.maxOutputTokens);
        assertEquals(2, parsed.settings.controlsPerAnchor);
    }

    @Test
    public void aRuntimeSuccessSurvivesIntoTheHostTypedResultWithItsIds() {
        ProbeGenerationResult runtimeResult = ProbeGenerationResult.ok(new ProbeGeneration(
                Arrays.asList(new ScopeProbe("probe-0001", "Exoskelette \"aktiv\" für Lager"),
                        new ScopeProbe("probe-0002", "Alleinarbeiterschutz\nmit GPS")),
                Arrays.asList(new ScopeCalibrationProbe("control-0001", "anchor-helme",
                        "Bauhelm mit Stoßsensor")),
                50), "over target count, dropped: x");

        String payload = ProbeGenerationWire.renderResult(runtimeResult);
        ProbeGenerationResult hostResult = com.aresstack.askai.research.scope
                .ProbeGenerationWireCodec.decodeResult(payload);

        assertTrue(hostResult.isOk());
        assertEquals("over target count, dropped: x", hostResult.getMessage());
        assertEquals(50, hostResult.getGeneration().getRequestedBroadCount());
        assertEquals(2, hostResult.getGeneration().getAcceptedBroadCount());
        assertEquals("the runtime-assigned identity is THE identity — the host never re-mints",
                "probe-0001", hostResult.getGeneration().getBroadProbes().get(0).getProbeId());
        assertEquals("Exoskelette \"aktiv\" für Lager",
                hostResult.getGeneration().getBroadProbes().get(0).getSemanticText());
        assertEquals("Alleinarbeiterschutz\nmit GPS",
                hostResult.getGeneration().getBroadProbes().get(1).getSemanticText());
        assertEquals("anchor-helme", hostResult.getGeneration().getCalibrationProbes()
                .get(0).getParentAnchorId());
        assertEquals("2 of 50 requested stays visibly incomplete after the boundary",
                false, hostResult.getGeneration().broadSampleComplete());
    }

    @Test
    public void aRuntimeFailureStaysTypedAcrossTheBoundary() {
        String payload = ProbeGenerationWire.renderResult(ProbeGenerationResult.failure(
                ProbeGenerationResult.Status.TIMEOUT, "main-model call timed out after 300000ms"));

        ProbeGenerationResult hostResult = com.aresstack.askai.research.scope
                .ProbeGenerationWireCodec.decodeResult(payload);

        assertEquals(ProbeGenerationResult.Status.TIMEOUT, hostResult.getStatus());
        assertEquals("main-model call timed out after 300000ms", hostResult.getMessage());
    }

    @Test
    public void aMalformedPayloadBecomesATypedInvalidResponseOnTheHost() {
        ProbeGenerationResult hostResult = com.aresstack.askai.research.scope
                .ProbeGenerationWireCodec.decodeResult("not json at all");
        assertEquals(ProbeGenerationResult.Status.INVALID_RESPONSE, hostResult.getStatus());
        assertTrue(hostResult.getMessage().contains("malformed wire payload"));
    }

    @Test
    public void theRunWireLineCarriesThePayloadUrlEncodedAndCorrelated() {
        String line = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .probeGeneration("req-9", "{\"status\":\"OK\",\"message\":\"a b\"}");
        assertTrue(com.aresstack.askai.research.agentwire.ResearchRunWire.isWireLine(line));
        assertEquals(com.aresstack.askai.research.agentwire.ResearchRunWire.TYPE_PROBES,
                com.aresstack.askai.research.agentwire.ResearchRunWire.typeOf(line));
        java.util.Map<String, String> fields =
                com.aresstack.askai.research.agentwire.ResearchRunWire.fields(line);
        assertEquals("req-9", fields.get("request_id"));
        assertEquals("{\"status\":\"OK\",\"message\":\"a b\"}",
                com.aresstack.askai.research.agentwire.ResearchRunWire.decodedField(fields, "payload"));
    }
}
