package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The runtime only lets WELL-FORMED proposals cross the process boundary: a malformed operation is dropped
 * here, with a reason in the technical trace, instead of travelling on and being refused invisibly later.
 */
public class ScopeUpdateDocumentTest {

    private static ScopingAssistantOutputParser.Result parse(String scopeFields) {
        return ScopingAssistantOutputParser.parse("{\"assistantMessage\":\"ok\","
                + "\"researchBriefMarkdown\":\"# brief\","
                + "\"searchSuggestions\":[{\"query\":\"q\",\"purpose\":\"p\",\"priority\":1}]"
                + (scopeFields.isEmpty() ? "" : "," + scopeFields) + "}");
    }

    @Test
    public void aScopingTurnCarriesItsProposedOperationsAsNeutralJson() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addFacet\",\"facetId\":\"health\","
                        + "\"label\":\"Gesundheit\",\"rationale\":\"vom Nutzer genannt\"}]}");

        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        String json = document.toJson();
        assertTrue(json, json.contains("\"kind\":\"addFacet\""));
        assertTrue(json, json.contains("\"facetId\":\"health\""));
        assertTrue(json, json.contains("\"label\":\"Gesundheit\""));
        assertTrue(document.isValid());
    }

    @Test
    public void aTurnWithoutScopeChangesCarriesNoDocumentAtAll() {
        ScopingAssistantOutputParser.Result result = parse("");

        assertTrue(result.getError(), result.isOk());
        assertEquals("no wire line is sent for a purely conversational turn",
                null, result.getOutput().getScopeUpdate());
    }

    /**
     * ONE malformed element invalidates the whole scope update — never a part of it. But the scope block is
     * OPTIONAL, so it must not cost the user their answer: a failed turn is not committed to history, so
     * killing the turn also erased the message the user had just written and the next turn started with
     * "I need a topic first". The turn survives, the update is dropped whole, and the host reports it.
     */
    @Test
    public void oneMalformedElementDropsTheWholeUpdateButKeepsTheConversation() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":["
                        + "{\"kind\":\"addFacet\",\"facetId\":\"health\",\"label\":\"Gesundheit\"},"
                        + "{\"kind\":\"setFacetEmphasis\"}]}");

        assertTrue("the answer itself is usable", result.isOk());
        assertEquals("ok", result.getOutput().getAssistantMessage());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertFalse("the update as a whole is invalid", document.isValid());
        assertTrue(document.describeViolations(),
                document.describeViolations().contains("setFacetEmphasis"));
        assertFalse("nothing of it may be applied — not even the valid operation",
                document.isValid());
    }

    /**
     * The live nag "Der Rechercheumfang wurde NICHT aktualisiert (addFacet without 'facetId')" on every
     * turn: the model gave labels but no ids. An id is a MACHINE concern — when a facet operation carries
     * a label, the id is derived from it deterministically (same label, same id), so the update applies
     * instead of being rejected over bookkeeping. Neither id nor label stays an honest violation.
     */
    @Test
    public void aMissingFacetIdIsDerivedFromTheLabelInsteadOfRejectingTheUpdate() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":["
                        + "{\"kind\":\"addFacet\",\"label\":\"Neue Antriebstechnologien\"},"
                        + "{\"kind\":\"confirmFacet\",\"label\":\"Neue Antriebstechnologien\"}]}");

        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertTrue("the update applies — ids are the machine's job: " + document.describeViolations(),
                document.isValid());
        String json = document.toJson();
        assertTrue(json, json.contains("\"facetId\":\"neue-antriebstechnologien\""));
        // Deterministic: BOTH operations reference the SAME derived id.
        int first = json.indexOf("\"facetId\":\"neue-antriebstechnologien\"");
        assertTrue("confirm references the same facet as add",
                json.indexOf("\"facetId\":\"neue-antriebstechnologien\"", first + 1) > first);
    }

    /**
     * The mirror of the label→id derivation (live-gate 2): the grammar now forces facetId on every
     * operation, so an addFacet may arrive id-only — the label falls back to the id instead of the
     * update dying over the HUMAN half of the same bookkeeping field.
     */
    @Test
    public void anIdOnlyAddFacetGetsItsLabelFromTheIdInsteadOfRejectingTheUpdate() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addFacet\","
                        + "\"facetId\":\"rtos-grundlagen_einsteiger\"}]}");
        assertTrue(result.getError(), result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertTrue("the update applies: " + document.describeViolations(), document.isValid());
        // Live-gate 3: raw ids leaked into the UI as labels — the emergency label at least
        // reads like words. A REAL label stays the contract's demand, this is the floor.
        assertTrue(document.toJson(),
                document.toJson().contains("\"label\":\"Rtos Grundlagen Einsteiger\""));
    }

    /**
     * Live-gate 3's smoking gun: minLength alone accepted a 100-char prompt placeholder ("esp-
     * idf_exclusion_placeholder_if_exists_or_recreate_logic_needed_…") and even JSON fragments
     * ("freertos-kernkonzepte,label:…") as CANONICAL facet identity. A facetId is a short
     * technical id or the operation is rejected whole.
     */
    @Test
    public void proseOrJsonFragmentsAreNotAFacetIdentity() {
        ScopingAssistantOutputParser.Result placeholder = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\",\"facetId\":"
                        + "\"esp-idf_exclusion_placeholder_if_exists_or_recreate_logic_needed_"
                        + "for_future_turns_based_on_context_error_correction\"}]}");
        assertTrue(placeholder.isOk());
        ScopeUpdateDocument tooLong = placeholder.getOutput().getScopeUpdate();
        assertFalse("a 100-char placeholder is no identity", tooLong.isValid());
        assertTrue(tooLong.describeViolations(),
                tooLong.describeViolations().contains("not a technical id"));

        ScopingAssistantOutputParser.Result fragment = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addFacet\",\"facetId\":"
                        + "\"freertos-kernkonzepte,label:freertos-kernkonzepte\"}]}");
        assertTrue(fragment.isOk());
        assertFalse("leaked JSON fields are no identity",
                fragment.getOutput().getScopeUpdate().isValid());

        // The padding facetId on NON-facet operations stays protocol-only: the host ignores it,
        // so its shape never blocks the fachliche operation.
        ScopingAssistantOutputParser.Result padding = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addExclusion\","
                        + "\"facetId\":\"whatever, even: this\",\"value\":\"Kaufberatung\"}]}");
        assertTrue(padding.isOk());
        assertTrue(padding.getOutput().getScopeUpdate().isValid());
    }

    /**
     * GPT's completeness demand after live-gate 3: kind+facetId alone is NOT the contract —
     * every kind's own required fields must stay enforced (addExclusion without value slipped
     * through the grammar and died only here). setDeliverable is the one kind whose fields are
     * all optional (every part has a documented default).
     */
    @Test
    public void everyOperationKindEnforcesItsOwnRequiredFields() {
        String[] kinds = {"setMission", "addFacet", "confirmFacet", "excludeFacet",
                "setFacetEmphasis", "setCrossCuttingEmphasis", "addDomain", "addContext",
                "addPerspective", "addConstraint", "addExclusion", "addTerminology",
                "setGeographicScope", "setTemporalScope", "addUnresolvedIssue", "resolveIssue",
                "setDeliverable"};
        for (String kind : kinds) {
            ScopingAssistantOutputParser.Result result = parse(
                    "\"scopePatch\":{\"operations\":[{\"kind\":\"" + kind + "\"}]}");
            assertTrue(kind, result.isOk());
            boolean valid = result.getOutput().getScopeUpdate().isValid();
            if ("setDeliverable".equals(kind)) {
                assertTrue("setDeliverable has only optional fields", valid);
            } else {
                assertFalse(kind + " must reject a bare operation", valid);
            }
        }
    }

    @Test
    public void aFacetOperationWithNeitherIdNorLabelStaysAViolation() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"addFacet\",\"rationale\":\"?\"}]}");
        assertTrue(result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertFalse("nothing to derive from — the whole update is honestly invalid", document.isValid());
        assertTrue(document.describeViolations(), document.describeViolations().contains("facetId"));
    }

    @Test
    public void theLabelSlugIsStableAsciiAndUmlautAware() {
        assertEquals("neue-antriebstechnologien", ScopeUpdateDocument.slugOf("Neue Antriebstechnologien"));
        assertEquals("kosten-nutzen", ScopeUpdateDocument.slugOf("  Kosten & Nutzen!  "));
        assertEquals("strassenzulassung-fuer-e-motorraeder",
                ScopeUpdateDocument.slugOf("Straßenzulassung für E-Motorräder"));
        assertEquals("", ScopeUpdateDocument.slugOf("!!!"));
    }

    @Test
    public void anUnknownOperationKindInvalidatesTheUpdateOnly() {
        ScopingAssistantOutputParser.Result unknownKind = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"invent\",\"facetId\":\"x\"}]}");
        assertTrue(unknownKind.isOk());
        assertFalse(unknownKind.getOutput().getScopeUpdate().isValid());
        assertTrue(unknownKind.getOutput().getScopeUpdate().describeViolations().contains("invent"));
    }

    /**
     * SEPARATE ERROR DOMAINS (the live-gate lesson): suggestions and issues are advisory metadata.
     * In the failed acceptance run, two labelless orientationSuggestions rejected the WHOLE turn's
     * scope update — the excludeFacet the user had just decided never reached the Weidezaun, and
     * check-scope reported "coverage 0/0". A broken advisory is dropped and traced; the fachliche
     * operations of the same turn still apply.
     */
    @Test
    public void aBrokenAdvisoryIsDroppedAndTracedButNeverPoisonsTheOperations() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\","
                        + "\"label\":\"ESP-IDF\",\"rationale\":\"explicit user exclusion\"}]},"
                        + "\"orientationSuggestions\":[{\"query\":\"only a query\"},"
                        + "{\"label\":\"\",\"query\":\"\"}],"
                        + "\"unresolvedIssues\":[{\"description\":\"no issueId\"}]");
        assertTrue(result.isOk());
        ScopeUpdateDocument document = result.getOutput().getScopeUpdate();
        assertTrue("the exclusion the user decided still applies: " + document.describeViolations(),
                document.isValid());
        assertTrue(document.toJson(), document.toJson().contains("\"kind\":\"excludeFacet\""));
        assertEquals("each drop is traced, never silent", 3, document.getDroppedAdvisories().size());
        // Issues are validated before suggestions — the trace follows the document order.
        assertTrue(document.getDroppedAdvisories().get(0).contains("issueId"));
        assertTrue(document.getDroppedAdvisories().get(1).contains("label"));
        assertTrue(document.getDroppedAdvisories().get(2).contains("label"));
        // The broken advisories themselves do NOT travel on the wire.
        assertFalse(document.toJson().contains("only a query"));
        assertFalse(document.toJson().contains("no issueId"));
    }

    @Test
    public void issuesAndSuggestionsKeepTheirFieldsIncludingTheLabelQuerySplit() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"unresolvedIssues\":[{\"issueId\":\"taxonomy\",\"description\":\"Ragout oder Frikassee?\","
                        + "\"significance\":\"CRITICAL\"}],"
                        + "\"orientationSuggestions\":[{\"label\":\"Tradition kurz prüfen\","
                        + "\"query\":\"turkey ragout culinary history\",\"rationale\":\"unklar\"}]");

        String json = result.getOutput().getScopeUpdate().toJson();
        assertTrue(json, json.contains("\"issueId\":\"taxonomy\""));
        assertTrue(json, json.contains("\"significance\":\"CRITICAL\""));
        assertTrue(json, json.contains("\"label\":\"Tradition kurz prüfen\""));
        assertTrue(json, json.contains("\"query\":\"turkey ragout culinary history\""));
    }

    @Test
    public void textWithQuotesAndNewlinesStaysValidJsonOnTheWire() {
        ScopingAssistantOutputParser.Result result = parse(
                "\"scopePatch\":{\"operations\":[{\"kind\":\"setMission\","
                        + "\"mission\":\"Er sagte \\\"Ragout\\\"\\nund meinte Frikassee\"}]}");

        String json = result.getOutput().getScopeUpdate().toJson();
        assertTrue(json, json.contains("\\\"Ragout\\\""));
        assertTrue(json, json.contains("\\n"));
    }
}
