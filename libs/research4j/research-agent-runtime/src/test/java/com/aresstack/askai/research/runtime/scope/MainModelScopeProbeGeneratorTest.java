package com.aresstack.askai.research.runtime.scope;

import com.aresstack.askai.research.domain.scope.ScopeAnchor;
import com.aresstack.askai.research.domain.scope.ScopeCalibrationProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbe;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationRequest;
import com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult;
import com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator.GeneratorSettings;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The productive generator: exactly ONE main-model call, typed failures survive 1:1, malformed
 * output is INVALID_RESPONSE (no repair loop, no synthetic fallback), identity is assigned
 * locally (the model contributes semantic content only), the dedupe roles stay separate, and a
 * missing control is left missing — the calibration's coverage rule owns that verdict, just as
 * {@code broadSampleComplete()} owns the breadth verdict.
 */
public class MainModelScopeProbeGeneratorTest {

    /** Scripted fake: returns one canned result, records every call. */
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

    private static ProbeGenerationRequest request() {
        return new ProbeGenerationRequest(
                "Welche Wearables sind für den Arbeitsschutz auf Baustellen relevant?",
                Arrays.asList("Arbeitsschutz"), Arrays.asList("Baustelle"),
                Arrays.asList("Sensorhelme", "Hypothetische Drohnenwartung"),
                Arrays.asList(
                        new ScopeAnchor("anchor-helme", "f1", "Schutzhelme mit Sensorik",
                                ScopeAnchor.Membership.IN),
                        new ScopeAnchor("anchor-fitness", "f2", "Consumer-Fitness-Armbänder",
                                ScopeAnchor.Membership.OUT),
                        new ScopeAnchor("anchor-guess", "f3", "Hypothetische Drohnenwartung",
                                ScopeAnchor.Membership.PROVISIONAL)),
                3);
    }

    private static MainModelScopeProbeGenerator generator(ScriptedChat chat) {
        return new MainModelScopeProbeGenerator(chat, new GeneratorSettings(0.7d, 2048, 2));
    }

    private static String okAnswer() {
        return "{\"broadProbes\":["
                + "{\"text\":\"Exoskelette für Lagerarbeiter\"},"
                + "{\"text\":\"Alleinarbeiterschutz\"},"
                + "{\"text\":\"Gaswarnwesten\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Stoßsensor\"},"
                + "{\"parentAnchorId\":\"anchor-fitness\",\"text\":\"Pulsuhr fürs Joggen\"}]}";
    }

    @Test
    public void exactlyOneModelCallAndIdentityIsAssignedLocally() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(okAnswer()));
        ProbeGenerationResult result = generator(chat).generate(request());

        assertEquals("one generation = ONE model call", 1, chat.calls.size());
        assertTrue(result.isOk());
        List<ScopeProbe> broad = result.getGeneration().getBroadProbes();
        assertEquals("ids are OURS, deterministic by order — never the model's",
                "probe-0001", broad.get(0).getProbeId());
        assertEquals("probe-0003", broad.get(2).getProbeId());
        assertEquals("control-0001",
                result.getGeneration().getCalibrationProbes().get(0).getProbeId());
        assertTrue("3 of 3 requested — the breadth verdict is complete",
                result.getGeneration().broadSampleComplete());
    }

    /**
     * PROVISIONAL is not offered as a CALIBRATION anchor — but it is not invisible: as a known
     * facet label it reaches the broad-probe context precisely so the generator does not
     * re-paraphrase the open hypothesis. Two different roles, both pinned here.
     */
    @Test
    public void aProvisionalPostIsNoCalibrationAnchorYetStaysKnownToTheGenerator() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(okAnswer()));
        generator(chat).generate(request());

        String prompt = chat.calls.get(0).get(1).getContent();
        assertTrue(prompt.contains("anchor-helme"));
        assertTrue(prompt.contains("anchor-fitness"));
        assertFalse("the hypothesis is never offered as a calibration anchor",
                prompt.contains("anchor-guess"));
        assertTrue("but its facet label IS known context against re-paraphrasing",
                prompt.contains("Hypothetische Drohnenwartung"));
    }

    @Test
    public void typedModelFailuresSurviveUntouched() {
        ScriptedChat timeout = new ScriptedChat(MainModelChatResult.failure(
                MainModelChatResult.Status.TIMEOUT, "read timed out"));
        ProbeGenerationResult result = generator(timeout).generate(request());
        assertEquals(ProbeGenerationResult.Status.TIMEOUT, result.getStatus());
        assertEquals("read timed out", result.getMessage());
        assertEquals("no retry, no repair call", 1, timeout.calls.size());

        ScriptedChat unavailable = new ScriptedChat(MainModelChatResult.failure(
                MainModelChatResult.Status.PROVIDER_FAILURE, "no main-model descriptor"));
        assertEquals(ProbeGenerationResult.Status.PROVIDER_FAILURE,
                generator(unavailable).generate(request()).getStatus());
    }

    @Test
    public void malformedAnswersAreInvalidResponseNeverAnEmptySweep() {
        assertEquals(ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok("Gerne! Hier sind Ideen: ...")))
                        .generate(request()).getStatus());
        assertEquals("valid JSON of the wrong shape is just as broken",
                ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok("{\"probes\":[]}")))
                        .generate(request()).getStatus());
        assertEquals("zero usable material must not masquerade as a clean 'nothing found'",
                ProbeGenerationResult.Status.INVALID_RESPONSE,
                generator(new ScriptedChat(MainModelChatResult.ok(
                        "{\"broadProbes\":[],\"calibrationProbes\":[]}")))
                        .generate(request()).getStatus());
    }

    @Test
    public void aMarkdownFencedAnswerIsUnwrappedLocallyNotReAsked() {
        ScriptedChat chat = new ScriptedChat(MainModelChatResult.ok(
                "```json\n" + okAnswer() + "\n```"));
        ProbeGenerationResult result = generator(chat).generate(request());
        assertTrue(result.isOk());
        assertEquals(1, chat.calls.size());
    }

    @Test
    public void controlsForUnknownOrProvisionalPostsAreDroppedAndDiagnosed() {
        String answer = "{\"broadProbes\":[{\"text\":\"Exoskelette\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Sensor\"},"
                + "{\"parentAnchorId\":\"anchor-erfunden\",\"text\":\"frei erfunden\"},"
                + "{\"parentAnchorId\":\"anchor-guess\",\"text\":\"Drohneninspektion\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue(result.isOk());
        List<ScopeCalibrationProbe> controls = result.getGeneration().getCalibrationProbes();
        assertEquals("only the control with a real negotiated parent survives", 1, controls.size());
        assertEquals("anchor-helme", controls.get(0).getParentAnchorId());
        assertTrue("the invented referent is diagnosed, never silently re-bound",
                result.getMessage().contains("anchor-erfunden"));
        assertTrue("the hypothesis control is diagnosed too",
                result.getMessage().contains("anchor-guess"));
    }

    /**
     * The dedupe ROLES are separate: the same wording as a broad probe and as a control — or as
     * controls of two DIFFERENT posts — carries different logical relations, and dropping the
     * second would silently un-cover an anchor (faking a WEAK calibration). Only within a role is
     * a normalized duplicate really a duplicate.
     */
    @Test
    public void theSameTextKeepsItsDifferentLogicalRolesAcrossBroadAndControls() {
        String answer = "{\"broadProbes\":["
                + "{\"text\":\"Tragbarer Gefahrenwarner\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Tragbarer Gefahrenwarner\"},"
                + "{\"parentAnchorId\":\"anchor-fitness\",\"text\":\"Tragbarer Gefahrenwarner\"},"
                + "{\"parentAnchorId\":\"anchor-fitness\",\"text\":\"tragbarer   GEFAHRENWARNER\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue(result.isOk());
        assertEquals(1, result.getGeneration().getBroadProbes().size());
        List<ScopeCalibrationProbe> controls = result.getGeneration().getCalibrationProbes();
        assertEquals("same text under TWO different posts = two relations, kept; the true "
                + "(parent,text) duplicate drops", 2, controls.size());
        assertEquals("anchor-helme", controls.get(0).getParentAnchorId());
        assertEquals("anchor-fitness", controls.get(1).getParentAnchorId());
        assertTrue("the real duplicate is diagnosed",
                result.getMessage().contains("duplicate control"));
    }

    @Test
    public void duplicatesAndOverflowAreHandledDeterministically() {
        String answer = "{\"broadProbes\":["
                + "{\"text\":\"Exoskelette für Lagerarbeiter\"},"
                + "{\"text\":\"  EXOSKELETTE   für Lagerarbeiter \"},"
                + "{\"text\":\"Alleinarbeiterschutz\"},"
                + "{\"text\":\"Gaswarnwesten\"},"
                + "{\"text\":\"über dem Limit\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm eins\"},"
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm zwei\"},"
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm drei\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue(result.isOk());
        List<ScopeProbe> broad = result.getGeneration().getBroadProbes();
        assertEquals("first wins; normalized text dupe and over-target drop", 3, broad.size());
        assertEquals("Exoskelette für Lagerarbeiter", broad.get(0).getSemanticText());
        assertEquals("Alleinarbeiterschutz", broad.get(1).getSemanticText());
        assertEquals("Gaswarnwesten", broad.get(2).getSemanticText());
        assertEquals("per-anchor control cap respected",
                2, result.getGeneration().getCalibrationProbes().size());
        assertTrue(result.getMessage().contains("duplicate broad text"));
        assertTrue(result.getMessage().contains("über dem Limit"));
        assertTrue(result.getMessage().contains("over per-anchor control cap"));
    }

    /**
     * The breadth verdict: 1 accepted probe against targetCount=3 is a structurally valid answer —
     * the generation stands, typed OK — but broadSampleComplete() says incomplete, and Z3b-3 must
     * then skip the hole hunt exactly like on a WEAK calibration. Never INVALID_RESPONSE (the
     * model did not break the contract) and never silently "complete".
     */
    @Test
    public void aThinBroadSampleIsHonestlyIncompleteNotInvalidAndNotComplete() {
        String answer = "{\"broadProbes\":[{\"text\":\"Exoskelette\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Sensor\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue("structurally valid — the generation stands", result.isOk());
        assertEquals(3, result.getGeneration().getRequestedBroadCount());
        assertEquals(1, result.getGeneration().getAcceptedBroadCount());
        assertFalse("1 of 3 requested: the sweep over this sample must not claim breadth",
                result.getGeneration().broadSampleComplete());
    }

    /** A missing control stays missing — WEAK coverage is the calibrator's honest verdict. */
    @Test
    public void aMissingControlIsNeverFabricated() {
        String answer = "{\"broadProbes\":[{\"text\":\"Exoskelette\"}],"
                + "\"calibrationProbes\":["
                + "{\"parentAnchorId\":\"anchor-helme\",\"text\":\"Bauhelm mit Sensor\"}]}";
        ProbeGenerationResult result =
                generator(new ScriptedChat(MainModelChatResult.ok(answer))).generate(request());

        assertTrue("the generation stands", result.isOk());
        List<ScopeCalibrationProbe> controls = result.getGeneration().getCalibrationProbes();
        assertEquals(1, controls.size());
        for (ScopeCalibrationProbe control : controls) {
            assertFalse("nothing was invented for the uncovered anchor-fitness",
                    control.getParentAnchorId().equals("anchor-fitness"));
        }
    }
}
