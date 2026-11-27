package com.aresstack.askai.research.runtime.team;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** The concept block rides the capability flag — and ONLY the flag (old hosts stay byte-identical). */
public class ScopingConceptPromptTest {

    @Test
    public void theConceptBlockAppearsExactlyWhenTheHostOffersTheTools() {
        String with = TeamAgentPlaybook.scopingSystemPrompt(false, true);
        assertTrue(with.contains("conceptAction"));
        assertTrue(with.contains("WE ARE BUILDING A BOOK"));
        assertTrue(with.contains("THE CONCEPT (conceptAction):"));
        // The MainframeMate lesson, pinned: concrete examples live IN the contract text.
        // add_cards slice: the single add left the ACTIVE contract — the model captures ALL
        // user-named areas as ONE typed list (four one-by-one rounds once cost "Debugging"
        // its place in the budget).
        assertTrue(with.contains("{\"type\":\"add_cards\",\"parent\":[],\"names\":"
                + "[\"<area 1>\","));
        assertFalse("the single add is no longer taught",
                with.contains("{\"type\":\"add\","));
        assertTrue("named areas outrank offers and own ideas",
                with.contains("FIRST one add_cards capturing EVERY area the user explicitly "
                        + "named"));
        // add_cards gate corrections: a plausibly derived parent is LEGAL and created WITH its
        // cards in one atomic call; short unique parent names resolve host-side.
        assertTrue("one plausible parent at most, created in the same call",
                with.contains("the SAME call creates it together with the cards"));
        assertTrue(with.contains("CREATED_PARENT"));
        assertTrue("no deep invented chains",
                with.contains("never any deeper invented hierarchy"));
        assertTrue("short unique parent names are enough",
                with.contains("the application resolves the full path itself"));
        // Gate ruling: the prompt no longer teaches ANY move emission — the application
        // executes moves itself; the model is told exactly that and nothing more.
        assertTrue(with.contains("MOVING CARDS"));
        assertTrue(with.contains("You never emit a move action"));
        assertFalse("no move example baits the model anymore",
                with.contains("{\"type\":\"move\""));
        assertTrue("multi-word terms stay one name",
                with.contains("\"Computer Science\" is one card"));
        assertTrue("claims only per receipt",
                with.contains("SUPPRESSED_BY_SCOPE"));
        assertTrue("segments, never slash paths", with.contains("NAME SEGMENTS"));
        // K2e: explicit user-command examples + read discipline + scope-decisions-into-workpiece.
        assertTrue(with.contains("Map an explicit user command DIRECTLY"));
        assertTrue(with.contains("Do not read unrelated branches"));
        assertTrue(with.contains("Never pretend an exclusion or focus is stored"));
        // Gate 3: id shape spelled out.
        assertTrue(with.contains("NEVER a sentence, a placeholder text or "));
        // Zielbild slice 1: the concept IS the positive working space — facet creation left the
        // model contract entirely (no more mindmap-term duplication as PROVISIONAL facets).
        assertFalse(with.contains("addFacet"));
        assertFalse(with.contains("confirmFacet"));
        assertTrue(with.contains("facet creation is not part of your contract"));
        // Gate 4 KISS: the exclusion is ONE command — the model quotes the user's term, the
        // platform owns ids, facets and the concept-conflict check ("military drill": one
        // command, one effect, one reply, then maybe a NEW command in a LATER turn.)
        assertTrue(with.contains("THE EXCLUSION COMMAND"));
        assertTrue(with.contains("<Z> möchte ich doch nicht behandeln"));
        assertTrue(with.contains("{\"type\": \"exclude\", \"topic\": \"<Z>\"}"));
        assertTrue(with.contains("No id, no scopePatch, no concept edit"));
        assertTrue(with.contains("INFORM_AND_ASK_REMOVE"));
        assertTrue("removal only in a LATER user turn",
                with.contains("You NEVER remove it in the same turn"));
        assertTrue(with.contains("\"decision\": \"REMOVE\""));
        assertTrue(with.contains("KEEP_SUPPRESSED"));
        assertTrue("exclusions left the scopePatch contract",
                with.contains("EXCLUSIONS never go through scopePatch"));
        // Safety slice after the slice-2 gate: rename stays; remove/rewrite left the contract
        // (a natural rewrite wish became a branch-vaporising remove, and "PlatformIO möchte
        // ich nicht behandeln" became a remove instead of the exclusion).
        assertTrue(with.contains("{\"type\": \"rename\", \"path\": [\"<card>\", \"<child card>\"]"));
        assertTrue("a rename is pinned as non-destructive",
                with.contains("A rename is NEVER a deletion"));
        assertFalse("the model cannot be TAUGHT a remove action",
                with.contains("{\"type\":\"remove\""));
        assertFalse(with.contains("\"rewrite\""));
        assertTrue(with.contains("YOU CANNOT DELETE OR REBUILD"));
        // Safety-slice gate 2: an explicit concept-delete order is NOT the same intent as
        // ruling a topic out — the drill separates them, and a delete maps to NO action.
        assertTrue(with.contains("TWO DIFFERENT INTENTS, never merged"));
        assertTrue("out-of-topic wishes stay the exclusion",
                with.contains("is the exclude command"));
        assertTrue("a delete order never silently becomes a scope exclusion",
                with.contains("deleting a card does not rule the topic out"));
        assertTrue("branch surgery is honestly manual until the tree editor",
                with.contains("manual work in the concept editor"));
        assertTrue("no improvised substitute actions",
                with.contains("do NOT improvise a substitute"));
        assertTrue("the partial-add substitute is named explicitly",
                with.contains("no partial adds standing in for a rewrite"));
        // Gate 4: the two identity spaces, pinned with the exact live confusion pair.
        assertTrue(with.contains("TWO IDENTITY SPACES"));
        assertTrue(with.contains("[\"<card name>\"]"));
        assertTrue(with.contains("\"<facet-id>\""));
        assertFalse("the both-channels demand is gone for good",
                with.contains("BOTH artifacts in the SAME answer"));
        // Gate 8b: the in-band suggestions field starved under the grammar — with the action
        // channel the suggestions are a COMMAND, shown with a worked example.
        assertTrue(with.contains("THE SEARCH OFFER COMMAND"));
        assertTrue(with.contains("{\"type\": \"offer\", \"suggestions\": ["));
        assertTrue(with.contains("\"query\": \"<topic> basics overview\""));
        assertTrue(with.contains("they are HOW the user explores"));
        assertTrue(with.contains("leave the searchSuggestions field empty"));
        // Gate 9: the broad first turn skipped the offer — the drill spells out the sequence
        // (add_cards slice: the named-area capture comes FIRST, the offer second).
        assertTrue(with.contains("FIRST-TURN DRILL"));
        assertTrue(with.contains("THEN exactly one offer action"));
        // K4: the concept is the ONE scoping artifact — the legacy brief is neither requested
        // nor emittable with the concept tools; the flagless old-host prompt keeps it.
        assertFalse(with.contains("researchBriefMarkdown"));
        assertTrue(TeamAgentPlaybook.scopingSystemPrompt(false, false)
                .contains("researchBriefMarkdown"));
        assertFalse("the grammar cannot produce the brief field anymore",
                new ScopingPhaseOutputContract(true).outputSchemaJson()
                        .contains("researchBriefMarkdown"));
        // Mission is HOST bookkeeping now — the contract does not even mention setMission.
        assertTrue(with.contains("recorded AUTOMATICALLY from the user's first message"));
        assertFalse("gate 5: the model tried 'setMission without mission' although the host had"
                + " already seeded it", with.contains("setMission"));
        assertTrue(with.contains("\"names\":[\"<subtopic>\"]"));
        assertTrue(with.contains("{\"type\":\"none\"}"));
        assertTrue("no handle field in the model contract", !with.contains("\"handle\""));
        assertTrue("no branch payloads in the model contract", !with.contains("branchJson"));
        String without = TeamAgentPlaybook.scopingSystemPrompt(false, false);
        assertFalse(without.contains("conceptAction"));
        assertEquals("the flagless overload is the old prompt, byte-identical",
                TeamAgentPlaybook.scopingSystemPrompt(false), without);
    }

    @Test
    public void theConceptContractPublishesTheGenerationTimeSchemaOnlyWithTheFlag() {
        String schema = new ScopingPhaseOutputContract(true).outputSchemaJson();
        // Safety slice: the grammar can no longer EMIT a destructive concept edit — the enum
        // carries neither remove nor rewrite (the parser stays tolerant for old transcripts,
        // the loop refuses execution).
        // Gate ruling: the dedicated generator owns the model side of moves EXCLUSIVELY —
        // the universal enum advertises no move (its starving invalid rounds once competed
        // with the pre-executed dedicated path and produced a false close).
        for (String type : new String[] {"none", "read", "add_cards", "exclude", "resolve", "offer", "rename",
                "probe"}) {
            assertTrue(type, schema.contains("\"enum\":[\"" + type + "\"]"));
        }
        // scope_probe (AP3): ONE field, a bounded list of terms — no ids, no thresholds.
        assertTrue(schema.contains("\"terms\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":12"));
        assertFalse(schema.contains("\"move\""));
        assertFalse(schema.contains("\"source\""));
        assertTrue("the names list is grammar-bounded and typed",
                schema.contains("\"names\":{\"type\":\"array\",\"minItems\":1,\"maxItems\":16"));
        assertFalse(schema.contains("\"rewrite\""));
        assertFalse(schema.contains("\"leaves\""));
        assertTrue(schema.contains("\"topic\":{\"type\":\"string\",\"minLength\":1}"));
        assertTrue(schema.contains("\"enum\":[\"REMOVE\",\"KEEP_SUPPRESSED\"]"));
        assertTrue("the action decision is always explicit",
                schema.contains("\"required\":[\"conceptAction\",\"assistantMessage\"]"));
        assertTrue("runaway lists are stopped by the grammar", schema.contains("maxItems"));
        // Scope hardening after the live gates: operations pin their kind AND a non-empty
        // facetId (gate 2: excludeFacet/addFacet arrived without one and the exclusion never
        // reached the Weidezaun); advisory suggestions must carry a NON-EMPTY label+query
        // (gate 1: an empty label once poisoned a whole scope turn).
        // Enum AUDIT (add_cards gate): only flat-expressible kinds are EMITTABLE — every
        // {value}/{dimension}/{issueId} kind was an advertised trap ("addExclusion/
        // addConstraint without 'value'" noise); the runtime validator keeps accepting the
        // full set for host paths and legacy transcripts (see ScopeUpdateDocumentTest).
        assertTrue(schema.contains("\"enum\":[\"setFacetEmphasis\",\"setDeliverable\"]"));
        assertFalse(schema.contains("addExclusion"));
        assertFalse(schema.contains("addConstraint"));
        assertFalse(schema.contains("addUnresolvedIssue"));
        assertFalse(schema.contains("setCrossCuttingEmphasis"));
        // Gate 5 / Zielbild slice 1: mission is host bookkeeping, the concept is the positive
        // working space, exclusion is the one-command action — none of these is emittable.
        assertFalse(schema.contains("setMission"));
        assertFalse(schema.contains("addFacet"));
        assertFalse(schema.contains("confirmFacet"));
        assertFalse(schema.contains("excludeFacet"));
        // Gate 3: minLength alone let prompt placeholders become canonical facets — the id
        // shape is pinned in the grammar (and re-checked by the runtime for engines that
        // ignore 'pattern').
        assertTrue(schema.contains(
                "\"facetId\":{\"type\":\"string\",\"minLength\":1,"
                        + "\"pattern\":\"^[a-z0-9][a-z0-9_-]{0,63}$\"}"));
        assertTrue(schema.contains("\"required\":[\"kind\",\"facetId\"]"));
        assertTrue(schema.contains("\"required\":[\"label\",\"query\"]"));
        assertEquals("without the tools the long-standing schema-free behaviour stays",
                null, new ScopingPhaseOutputContract(false).outputSchemaJson());
        assertEquals(null, new ScopingPhaseOutputContract().outputSchemaJson());
    }

    /**
     * Gate ruling: the UNIVERSAL contract knows no move anymore — the dedicated two-field
     * schema (MoveActionGeneratorTest) is the only delivered move grammar. Proven on the
     * parsed schema, not a substring.
     */
    @Test
    public void theUniversalGrammarKnowsNoMoveAnymore() {
        String schema = new ScopingPhaseOutputContract(true).outputSchemaJson();
        Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(schema);
        assertTrue("the schema itself parses as JSON", parsed instanceof java.util.Map);
        java.util.Map<?, ?> conceptAction = (java.util.Map<?, ?>)
                ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) parsed).get("properties"))
                        .get("conceptAction");
        for (Object variant : (java.util.List<?>) conceptAction.get("anyOf")) {
            java.util.Map<?, ?> properties = (java.util.Map<?, ?>) ((java.util.Map<?, ?>) variant).get("properties");
            assertNull("no source field left in the universal grammar", properties.get("source"));
            assertFalse("move is NOT an emittable type here", String.valueOf(
                    ((java.util.Map<?, ?>) properties.get("type")).get("enum")).contains("move"));
        }
    }

    @Test
    public void theRegistryHandsTheFlagThrough() {
        assertTrue(PhaseAssistantProfileRegistry.defaults(false, true)
                .forPhase("scoping").getSystemPrompt().contains("conceptAction"));
        assertFalse(PhaseAssistantProfileRegistry.defaults(false, false)
                .forPhase("scoping").getSystemPrompt().contains("conceptAction"));
    }

    /**
     * Scoping regression (Hasenhaltung): gemma copied the ESP32/FreeRTOS examples of this prompt into the
     * concept of an unrelated topic. The examples keep their SHAPE (small models need it for the format)
     * but carry no topic any more — only placeholders that the concept loop refuses to execute.
     */
    @Test
    public void theScopingPromptCarriesNoDomainExamplesOnlyPlaceholders() {
        for (boolean offers : new boolean[] {false, true}) {
            for (boolean concept : new boolean[] {false, true}) {
                String prompt = TeamAgentPlaybook.scopingSystemPrompt(offers, concept).toLowerCase(java.util.Locale.ROOT);
                for (String domain : new String[] {"esp32", "esp-idf", "freertos", "arduino", "praxisprojekte"}) {
                    assertFalse(domain + " in prompt(offers=" + offers + ", concept=" + concept + ")",
                            prompt.contains(domain));
                }
            }
        }
        String with = TeamAgentPlaybook.scopingSystemPrompt(true, true);
        assertTrue("the examples are declared as shape only", with.contains("EXAMPLES SHOW THE SHAPE ONLY"));
        assertTrue("the batch shape a small model needs stays",
                with.contains("{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"<area 1>\""));
        assertTrue("a bare topic gets the topic's own areas",
                with.contains("When the user only names a topic, the first cards are the main areas of THAT topic"));
    }

    /**
     * Meerschweinchen live gate: with assistantMessage generated first the small model narrated its action
     * ("add_cards: …") and then answered "none". The grammar (required properties first, in schema order)
     * and the prompt contract now put conceptAction FIRST.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theActionComesBeforeTheVisibleMessage() {
        java.util.Map<String, Object> schema = (java.util.Map<String, Object>)
                com.aresstack.askai.agent.model.reranker.MiniJson.parse(
                        new ScopingPhaseOutputContract(true).outputSchemaJson());
        java.util.List<String> keys = new java.util.ArrayList<String>(
                ((java.util.Map<String, Object>) schema.get("properties")).keySet());
        assertEquals("conceptAction", keys.get(0));
        assertEquals("assistantMessage", keys.get(1));
        assertEquals(java.util.Arrays.asList("conceptAction", "assistantMessage"), schema.get("required"));
        String with = TeamAgentPlaybook.scopingSystemPrompt(true, true);
        assertTrue(with.indexOf("\"conceptAction\": {") < with.indexOf("\"assistantMessage\": string"));
        assertFalse("no placeholder-valued example in the nudges", TeamAgentPlaybook.offerSearchesMissing(true)
                .contains("\"...\""));
    }

    /**
     * Second live acceptance: the flat action object made {"type":"add_cards"} and
     * {"type":"offer","suggestions":[]} grammatical (checked with llama.cpp's json-schema-to-grammar, which
     * Ollama uses). Each action type is now its own variant with its required, non-empty fields.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void everyActionVariantRequiresTheFieldsItsCommandNeeds() {
        java.util.Map<String, Object> schema = (java.util.Map<String, Object>)
                com.aresstack.askai.agent.model.reranker.MiniJson.parse(
                        new ScopingPhaseOutputContract(true).outputSchemaJson());
        java.util.Map<String, Object> action = (java.util.Map<String, Object>)
                ((java.util.Map<String, Object>) schema.get("properties")).get("conceptAction");
        java.util.List<Object> variants = (java.util.List<Object>) action.get("anyOf");
        java.util.Map<String, java.util.List<Object>> required = new java.util.LinkedHashMap<String, java.util.List<Object>>();
        java.util.Map<String, java.util.Map<String, Object>> properties =
                new java.util.LinkedHashMap<String, java.util.Map<String, Object>>();
        for (Object v : variants) {
            java.util.Map<String, Object> variant = (java.util.Map<String, Object>) v;
            java.util.Map<String, Object> props = (java.util.Map<String, Object>) variant.get("properties");
            String type = (String) ((java.util.List<Object>) ((java.util.Map<String, Object>) props.get("type"))
                    .get("enum")).get(0);
            required.put(type, (java.util.List<Object>) variant.get("required"));
            properties.put(type, props);
        }
        assertEquals(java.util.Arrays.asList("none", "read", "add_cards", "exclude", "resolve", "offer", "rename",
                "probe"), new java.util.ArrayList<String>(required.keySet()));
        assertEquals(java.util.Arrays.asList("type", "parent", "names"), required.get("add_cards"));
        assertEquals(java.util.Arrays.asList("type", "suggestions"), required.get("offer"));
        assertEquals(java.util.Arrays.asList("type", "topic"), required.get("exclude"));
        assertEquals(java.util.Arrays.asList("type", "path", "name"), required.get("rename"));
        assertEquals(java.util.Arrays.asList("type", "terms"), required.get("probe"));
        assertEquals(1.0, ((Number) ((java.util.Map<String, Object>) properties.get("add_cards").get("names"))
                .get("minItems")).doubleValue(), 0.0);
        assertEquals(1.0, ((Number) ((java.util.Map<String, Object>) properties.get("offer").get("suggestions"))
                .get("minItems")).doubleValue(), 0.0);
        assertFalse("no move in the model grammar", required.containsKey("move"));
    }

    /** A nudge asking for ONE action gets the grammar of only that variant: no "none", no other action. */
    @Test
    public void aRequiredActionTypeLeavesOnlyThatVariant() {
        String offerOnly = new ScopingPhaseOutputContract(true).outputSchemaJson("offer");
        assertTrue(offerOnly.contains("\"enum\":[\"offer\"]"));
        assertFalse(offerOnly.contains("\"enum\":[\"none\"]"));
        assertFalse(offerOnly.contains("\"anyOf\""));
        assertTrue(new ScopingPhaseOutputContract(true).outputSchemaJson("add_cards")
                .contains("\"required\":[\"type\",\"parent\",\"names\"]"));
        assertEquals(null, new ScopingPhaseOutputContract(false).outputSchemaJson("offer"));
    }
}
