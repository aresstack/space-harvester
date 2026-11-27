package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * The hardened concept contract: paths are SEGMENT ARRAYS, never slash-joined strings — a name
 * is one label ('/' inside it stays a character), a bare string counts as exactly one segment.
 * {@code none}/absent both mean "this turn touches nothing"; malformed actions carry their
 * reason back with an example; the canonical history round-trips the segments.
 */
public class ScopingConceptActionTest {

    private static ScopingAssistantOutput parse(String json) {
        ScopingAssistantOutputParser.Result result = ScopingAssistantOutputParser.parse(json);
        assertTrue("must parse: " + result.getError(), result.isOk());
        return result.getOutput();
    }

    @Test
    public void readAddAndRemoveParseWithSegmentArrays() {
        ConceptAction read = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":[\"FreeRTOS\",\"Kommunikation\"]}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.READ, read.getType());
        assertEquals(Arrays.asList("FreeRTOS", "Kommunikation"), read.getPath());

        ConceptAction add = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent\":[\"FreeRTOS\"],\"name\":\"TCP/IP\"}}")
                .getConceptAction();
        assertEquals(ConceptAction.Type.ADD, add.getType());
        assertEquals(Collections.singletonList("FreeRTOS"), add.getParent());
        assertEquals("a slash inside a NAME is just a character", "TCP/IP", add.getName());

        ConceptAction remove = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":[\"FreeRTOS\",\"Praxis\",\"ESP-IDF\"]}}")
                .getConceptAction();
        assertEquals(Arrays.asList("FreeRTOS", "Praxis", "ESP-IDF"), remove.getPath());
    }

    @Test
    public void aBareStringIsExactlyOneSegmentNeverSplit() {
        ConceptAction read = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"read\",\"path\":\"FreeRTOS/ESP32\"}}").getConceptAction();
        assertEquals("never split on '/' — one segment, whatever it contains",
                Collections.singletonList("FreeRTOS/ESP32"), read.getPath());
    }

    @Test
    public void noneAndAbsentBothMeanNoAction() {
        ScopingAssistantOutput none = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"none\"}}");
        assertNull(none.getConceptAction());
        assertNull(none.getConceptActionError());
        ScopingAssistantOutput absent = parse("{\"assistantMessage\":\"m\"}");
        assertNull(absent.getConceptAction());
        assertTrue("an action-free turn stays byte-identical to before",
                !absent.canonicalJson().contains("conceptAction"));
    }

    @Test
    public void malformedActionsCarryTheirReasonWithASegmentExample() {
        ScopingAssistantOutput missingName = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"add\",\"parent\":[\"FreeRTOS\"]}}");
        assertNull(missingName.getConceptAction());
        assertTrue(missingName.getConceptActionError().contains("requires \"name\""));
        assertTrue("the example teaches segments",
                missingName.getConceptActionError().contains("\"parent\":[\"<card>\"]"));

        ScopingAssistantOutput missingPath = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"remove\"}}");
        assertTrue(missingPath.getConceptActionError().contains("requires \"path\""));

        ScopingAssistantOutput unknownType = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"update\"}}");
        assertTrue(unknownType.getConceptActionError().contains("unknown type"));
    }

    /** The codec's "ALL fields" claim finally holds: the scope proposal round-trips too. */
    @Test
    public void theCanonicalHistoryRoundTripsTheScopePatch() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\","
                + "\"scopePatch\":{\"operations\":[{\"kind\":\"addExclusion\","
                + "\"value\":\"ESP-IDF\"}]},"
                + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(output.getScopeUpdate() != null && output.getScopeUpdate().isValid());
        ScopingAssistantOutput reread = parse(output.canonicalJson());
        assertTrue("the exclusion survives the history round-trip",
                reread.getScopeUpdate() != null && reread.getScopeUpdate().isValid()
                        && reread.getScopeUpdate().toJson().contains("ESP-IDF"));
    }

    /**
     * The ONE-command exclusion facade (live-gate 4): exclude carries ONLY the user's term,
     * resolve carries ONLY the opaque conflictId + decision — no ids, no paths, no choices.
     */
    @Test
    public void excludeAndResolveParseWithoutAnyIdentityDecisions() {
        ConceptAction exclude = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"exclude\",\"topic\":\"ESP-IDF\"}}").getConceptAction();
        assertEquals(ConceptAction.Type.EXCLUDE, exclude.getType());
        assertEquals("ESP-IDF", exclude.getTopic());

        ConceptAction resolve = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"resolve\",\"conflictId\":\"conflict-17\","
                + "\"decision\":\"REMOVE\"}}").getConceptAction();
        assertEquals(ConceptAction.Type.RESOLVE, resolve.getType());
        assertEquals("conflict-17", resolve.getConflictId());
        assertEquals("REMOVE", resolve.getDecision());

        ScopingAssistantOutput missingTopic = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"exclude\"}}");
        assertNull(missingTopic.getConceptAction());
        assertTrue(missingTopic.getConceptActionError().contains("requires \"topic\""));

        ScopingAssistantOutput badDecision = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"resolve\",\"conflictId\":\"c\","
                + "\"decision\":\"DELETE\"}}");
        assertTrue(badDecision.getConceptActionError().contains("KEEP_SUPPRESSED"));
    }

    /** move_leaf: one leaf, one target ([] = root), source may be one unique card name. */
    @Test
    public void moveParsesRoundTripsAndTeachesOnMissingParts() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"move\",\"source\":[\"Scheduling\"],\"parent\":"
                + "[\"FreeRTOS\"]}}");
        ConceptAction action = output.getConceptAction();
        assertEquals(ConceptAction.Type.MOVE, action.getType());
        assertEquals(Collections.singletonList("Scheduling"), action.getPath());
        assertEquals(Collections.singletonList("FreeRTOS"), action.getParent());
        ConceptAction reread = parse(output.canonicalJson()).getConceptAction();
        assertEquals(action.getPath(), reread.getPath());
        assertEquals(action.getParent(), reread.getParent());

        ConceptAction toRoot = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"move\",\"source\":[\"Scheduling\"],\"parent\":[]}}")
                .getConceptAction();
        assertEquals("an explicit [] parent means the top level",
                Collections.<String>emptyList(), toRoot.getParent());

        ScopingAssistantOutput missing = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"move\",\"source\":[\"Scheduling\"]}}");
        assertNull("the parent must be EXPLICIT — an accidental omission must not silently "
                + "move to the root", missing.getConceptAction());
        assertTrue(missing.getConceptActionError().contains("[] = top level"));

        // Small-model tolerance: the rename/add habit ("name", "parent_path") still parses.
        ConceptAction habitual = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"move\",\"name\":\"Scheduling\",\"parent_path\":"
                + "[\"FreeRTOS\"]}}").getConceptAction();
        assertEquals(Collections.singletonList("Scheduling"), habitual.getPath());
        assertEquals(Collections.singletonList("FreeRTOS"), habitual.getParent());
    }

    /** add_cards: ALL user-named areas as ONE typed list — a single card is a 1-element list. */
    @Test
    public void addCardsParsesRoundTripsAndTeachesOnMissingNames() {
        ScopingAssistantOutput output = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"Grundlagen\","
                + "\"Computer Science\",\"Debugging\"]}}");
        ConceptAction action = output.getConceptAction();
        assertEquals(ConceptAction.Type.ADD_CARDS, action.getType());
        assertTrue(action.getParent().isEmpty());
        assertEquals("multi-word names stay ONE name in the typed list",
                "[\"Grundlagen\",\"Computer Science\",\"Debugging\"]", action.getNamesJson());
        ConceptAction reread = parse(output.canonicalJson()).getConceptAction();
        assertEquals(action.getNamesJson(), reread.getNamesJson());

        ScopingAssistantOutput missing = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"add_cards\",\"parent\":[]}}");
        assertNull(missing.getConceptAction());
        assertTrue(missing.getConceptActionError().contains("one-element list"));
    }

    /**
     * rename is in the contract; rewrite is PARSED only as legacy tolerance (safety slice: the
     * grammar cannot emit it anymore and the loop refuses execution — old transcripts must
     * still round-trip instead of crashing the turn).
     */
    @Test
    public void renameAndRewriteParseAndRoundTrip() {
        ConceptAction rename = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"rename\",\"path\":[\"FreeRTOS\",\"Setup\"],"
                + "\"name\":\"ESP32-Entwicklung\"}}").getConceptAction();
        assertEquals(ConceptAction.Type.RENAME, rename.getType());
        assertEquals(Arrays.asList("FreeRTOS", "Setup"), rename.getPath());
        assertEquals("ESP32-Entwicklung", rename.getName());

        ScopingAssistantOutput rewriteOutput = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"rewrite\",\"path\":[\"FreeRTOS\",\"Setup\"],"
                + "\"leaves\":[\"Arduino\",\"Debugging\"]}}");
        ConceptAction rewrite = rewriteOutput.getConceptAction();
        assertEquals(ConceptAction.Type.REWRITE, rewrite.getType());
        assertEquals("[\"Arduino\",\"Debugging\"]", rewrite.getLeavesJson());
        ConceptAction reread = parse(rewriteOutput.canonicalJson()).getConceptAction();
        assertEquals("[\"Arduino\",\"Debugging\"]", reread.getLeavesJson());
        assertEquals(Arrays.asList("FreeRTOS", "Setup"), reread.getPath());

        assertTrue("an empty leaves list is a deliberate clear, not an error",
                parse("{\"assistantMessage\":\"m\",\"conceptAction\":{\"type\":\"rewrite\","
                        + "\"path\":[\"A\"],\"leaves\":[]}}").getConceptAction()
                        .getLeavesJson().equals("[]"));

        ScopingAssistantOutput missing = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"rewrite\",\"path\":[\"A\"]}}");
        assertNull(missing.getConceptAction());
        assertTrue(missing.getConceptActionError().contains("requires \"path\" and \"leaves\""));
    }

    /** Gate 8b: suggestions travel as a COMMAND — the optional in-band field starved. */
    @Test
    public void anOfferActionCarriesItsSuggestionsAsCanonicalJson() {
        ConceptAction offer = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"offer\",\"suggestions\":["
                + "{\"query\":\"FreeRTOS ESP32 Grundlagen Tutorial\",\"purpose\":\"Einstieg\"},"
                + "{\"query\":\"ESP32 Arduino Praxisprojekte\"},"
                + "{\"query\":\"   \"}]}}").getConceptAction();
        assertEquals(ConceptAction.Type.OFFER, offer.getType());
        assertEquals("blank queries are dropped, the rest is canonical",
                "[{\"query\":\"FreeRTOS ESP32 Grundlagen Tutorial\",\"purpose\":\"Einstieg\"},"
                        + "{\"query\":\"ESP32 Arduino Praxisprojekte\"}]",
                offer.getSuggestionsJson());
        assertTrue(offer.describe().startsWith("offer suggestions=2"));

        ScopingAssistantOutput empty = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"offer\"}}");
        assertNull(empty.getConceptAction());
        assertTrue(empty.getConceptActionError().contains("requires \"suggestions\""));
    }

    @Test
    public void theCanonicalHistoryRoundTripsTheOffer() {
        ScopingAssistantOutput offer = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"offer\",\"suggestions\":[{\"query\":\"q1\",\"purpose\":\"p\"}]}}");
        ConceptAction reread = parse(offer.canonicalJson()).getConceptAction();
        assertEquals(ConceptAction.Type.OFFER, reread.getType());
        assertEquals("[{\"query\":\"q1\",\"purpose\":\"p\"}]", reread.getSuggestionsJson());
    }

    @Test
    public void theCanonicalHistoryRoundTripsExcludeAndResolve() {
        ScopingAssistantOutput exclude = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"exclude\",\"topic\":\"ESP-IDF\"}}");
        ConceptAction rereadExclude = parse(exclude.canonicalJson()).getConceptAction();
        assertEquals(ConceptAction.Type.EXCLUDE, rereadExclude.getType());
        assertEquals("ESP-IDF", rereadExclude.getTopic());

        ScopingAssistantOutput resolve = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"resolve\",\"conflictId\":\"conflict-2\","
                + "\"decision\":\"KEEP_SUPPRESSED\"}}");
        ConceptAction rereadResolve = parse(resolve.canonicalJson()).getConceptAction();
        assertEquals("conflict-2", rereadResolve.getConflictId());
        assertEquals("KEEP_SUPPRESSED", rereadResolve.getDecision());
    }

    @Test
    public void theCanonicalHistoryRoundTripsTheSegments() {
        ScopingAssistantOutput add = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"add\",\"parent\":[\"FreeRTOS\",\"Praxis\"],\"name\":\"C/C++\"}}");
        ScopingAssistantOutput reread = parse(add.canonicalJson());
        assertEquals(Arrays.asList("FreeRTOS", "Praxis"), reread.getConceptAction().getParent());
        assertEquals("C/C++", reread.getConceptAction().getName());

        ScopingAssistantOutput remove = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"remove\",\"path\":[\"A\",\"B\"]}}");
        assertEquals(Arrays.asList("A", "B"),
                parse(remove.canonicalJson()).getConceptAction().getPath());
    }

    /** scope_probe (AP3): ONE field with a term list — as simple as add_cards, on purpose. */
    @Test
    public void probeCarriesItsTermsAsOneTypedList() {
        ScopingAssistantOutput probe = parse("{\"assistantMessage\":\"m\",\"conceptAction\":"
                + "{\"type\":\"probe\",\"terms\":[\"Priority Inversion\","
                + "\"SMP Scheduling\"]}}");
        ConceptAction action = probe.getConceptAction();
        assertEquals(ConceptAction.Type.PROBE, action.getType());
        assertEquals("[\"Priority Inversion\",\"SMP Scheduling\"]", action.getTermsJson());
        assertEquals("probe terms=[\"Priority Inversion\",\"SMP Scheduling\"]",
                action.describe());

        ScopingAssistantOutput missing = parse("{\"assistantMessage\":\"m\","
                + "\"conceptAction\":{\"type\":\"probe\"}}");
        assertTrue(missing.getConceptActionError().contains("requires \"terms\""));

        // A history replay must not lose the ONE field the action has.
        ConceptAction reread = parse(probe.canonicalJson()).getConceptAction();
        assertEquals(ConceptAction.Type.PROBE, reread.getType());
        assertEquals("[\"Priority Inversion\",\"SMP Scheduling\"]", reread.getTermsJson());
    }
}
