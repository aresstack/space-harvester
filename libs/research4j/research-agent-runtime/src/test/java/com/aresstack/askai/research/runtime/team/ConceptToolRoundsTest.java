package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * The tool-round loop with change RECEIPTS: every feedback lists APPLIED_ACTIONS and
 * REJECTED_ACTIONS (a lone boolean once let one applied add be claimed as four) and grounds
 * them in the persisted CURRENT_CONCEPT after any mutation attempt. A read is a working step,
 * only rejections count against the separate repair budget, outcomes land in the trace
 * (APPLIED revision / REJECTED code), budgets end in one wrap-up, a dead endpoint keeps the
 * last good answer.
 */
public class ConceptToolRoundsTest {

    private static final class ScriptedTurns implements ConceptToolRounds.FollowUpTurn {
        final List<TeamAgentResult> script = new ArrayList<TeamAgentResult>();
        final List<String> feedbackSeen = new ArrayList<String>();
        /** The action type each follow-up's grammar was restricted to (null = free). */
        final List<String> requiredTypes = new ArrayList<String>();

        public TeamAgentResult run(String feedbackInstruction) {
            return run(feedbackInstruction, null);
        }

        public TeamAgentResult run(String feedbackInstruction, String requiredActionType) {
            requiredTypes.add(requiredActionType);
            feedbackSeen.add(feedbackInstruction);
            if (script.isEmpty()) {
                throw new AssertionError("more follow-up turns requested than scripted");
            }
            return script.remove(0);
        }
    }

    private static final class ScriptedTool implements ConceptToolRounds.ConceptTool {
        final Map<String, Object> byDescription = new HashMap<String, Object>();
        final List<String> calls = new ArrayList<String>();

        ScriptedTool() {
            // The loop grounds receipts by re-reading the whole concept after mutations.
            byDescription.put("read path=[]", "{\"concept\":[]}");
        }

        public String call(ConceptAction action)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable {
            calls.add(action.describe());
            Object scripted = byDescription.get(action.describe());
            if (scripted instanceof ToolInvoker.ToolFailure) {
                throw (ToolInvoker.ToolFailure) scripted;
            }
            if (scripted instanceof ToolInvoker.EndpointUnavailable) {
                throw (ToolInvoker.EndpointUnavailable) scripted;
            }
            return String.valueOf(scripted);
        }
    }

    private final List<String> trace = new ArrayList<String>();
    private final ConceptToolRounds.Trace traceSink = new ConceptToolRounds.Trace() {
        public void line(String message) {
            trace.add(message);
        }
    };

    private static TeamAgentResult turn(String message, String conceptActionJson) {
        String json = "{\"assistantMessage\":\"" + message + "\""
                + (conceptActionJson == null ? "" : ",\"conceptAction\":" + conceptActionJson)
                + "}";
        ScopingAssistantOutputParser.Result parsed = ScopingAssistantOutputParser.parse(json);
        assertTrue("fixture must parse: " + parsed.getError(), parsed.isOk());
        return TeamAgentResult.ok(parsed.getOutput(), null);
    }

    @Test
    public void readThenAddThenFinishCarriesReceiptsAndGrounding() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"X\"]", "{\"X\":[]}");
        tool.byDescription.put("add_cards parent=[] names=[\"FreeRTOS\"]",
                "added \"FreeRTOS\" revision=1");
        tool.byDescription.put("read path=[]",
                "{\"concept\":[{\"FreeRTOS\":[]}]}");
        turns.script.add(turn("lege an",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"FreeRTOS\"]}"));
        turns.script.add(turn("Angelegt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich sehe nach", "{\"type\":\"read\",\"path\":[\"X\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("Angelegt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        String readFeedback = turns.feedbackSeen.get(0);
        assertTrue(readFeedback.startsWith("ARTIFACT_STATE"));
        assertTrue("nothing applied yet", readFeedback.contains("APPLIED_ACTIONS\n- (none)"));
        String addFeedback = turns.feedbackSeen.get(1);
        assertTrue("the receipt names the ONE applied action",
                addFeedback.contains("APPLIED_ACTIONS\n- add_cards parent=[] names=[\"FreeRTOS\"] "
                        + "(revision 1)"));
        assertTrue(addFeedback.contains("REJECTED_ACTIONS\n- (none)"));
        assertTrue("the receipts are grounded in the persisted concept",
                addFeedback.contains("CURRENT_CONCEPT\n{\"concept\":[{\"FreeRTOS\":[]}]}"));
        assertTrue(addFeedback.contains("Only claim changes listed under APPLIED_ACTIONS"));
        assertTrue("the trace shows the OUTCOME, not just the attempt",
                trace.contains("round 2 -> APPLIED revision=1"));
    }

    /** The live exclusion bug: intermediate rounds must hand their scopePatch to the sink. */
    @Test
    public void anIntermediateRoundsScopePatchReachesTheSinkInsteadOfVanishing() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Arduino\"]", "added \"Arduino\" revision=1");
        turns.script.add(turn("fertig", null));
        final List<ScopingAssistantOutput> intermediates = new ArrayList<ScopingAssistantOutput>();

        // ONE answer, BOTH channels: conceptAction (add Arduino) AND scopePatch (exclude ESP-IDF).
        TeamAgentResult initial = turn("nur Arduino",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"Arduino\"]}");
        ScopingAssistantOutputParser.Result withPatch = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"nur Arduino\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"addExclusion\","
                        + "\"value\":\"ESP-IDF\"}]},"
                        + "\"conceptAction\":{\"type\":\"add_cards\",\"parent\":[],"
                        + "\"names\":[\"Arduino\"]}}");
        assertTrue(withPatch.isOk());
        initial = TeamAgentResult.ok(withPatch.getOutput(), null);

        ConceptToolRounds.run(initial, turns, tool, 4, 2, false,
                new ConceptToolRounds.IntermediateSink() {
                    public void intermediate(ScopingAssistantOutput output) {
                        intermediates.add(output);
                    }
                }, traceSink);

        assertEquals("the consumed round reached the sink", 1, intermediates.size());
        assertTrue("its scope update survives for emission",
                intermediates.get(0).getScopeUpdate() != null
                        && intermediates.get(0).getScopeUpdate().isValid());
    }

    @Test
    public void aRejectedAddIsAReceiptARepairAndATraceOutcome() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[\"FreeRTOS\",\"ESP32\"] names=[\"Grundlagen\"]",
                new ToolInvoker.ToolFailure(
                        "TARGET_NODE_NOT_FOUND\nConcept node \"ESP32\" does not exist."));
        turns.script.add(turn("verstanden", null));

        ConceptToolRounds.run(
                turn("try", "{\"type\":\"add_cards\",\"parent\":[\"FreeRTOS\",\"ESP32\"],"
                        + "\"names\":[\"Grundlagen\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        String feedback = turns.feedbackSeen.get(0);
        assertTrue(feedback.contains("REJECTED_ACTIONS\n- add_cards parent=[\"FreeRTOS\","
                + "\"ESP32\"] names=[\"Grundlagen\"] — TARGET_NODE_NOT_FOUND"));
        assertTrue(feedback.contains("APPLIED_ACTIONS\n- (none)"));
        assertTrue("even a rejection grounds the receipts in the persisted (unchanged) state",
                feedback.contains("CURRENT_CONCEPT"));
        // Slice-2 gate observability fix: the trace carries the WHOLE flattened reason — the
        // log once showed only the error code while the teaching text reached the model alone.
        assertTrue(trace.contains("round 1 -> REJECTED TARGET_NODE_NOT_FOUND Concept node "
                + "\"ESP32\" does not exist."));
    }

    @Test
    public void rejectionsExhaustTheRepairBudgetSeparately() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"X\"]",
                new ToolInvoker.ToolFailure("BRANCH_GRAFT_FAILED\nboom"));
        turns.script.add(turn("retry", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"X\"]}"));
        turns.script.add(turn("aufgeben", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("try", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"X\"]}"),
                turns, tool, 10, 1, false, null, traceSink);

        assertEquals("aufgeben",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the second rejection exhausts the repair budget (work budget untouched)",
                turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertTrue(turns.feedbackSeen.get(1).contains(
                "REJECTED_ACTIONS\n- add_cards parent=[] names=[\"X\"] — BRANCH_GRAFT_FAILED boom\n"
                        + "- add_cards parent=[] names=[\"X\"] — BRANCH_GRAFT_FAILED boom"));
    }

    /**
     * Safety slice after the slice-2 gate: a destructive action from a legacy transcript (the
     * grammar cannot emit them anymore) is REFUSED before the host — no tool call, honest
     * teaching feedback, and the wrap-up receipt names the refusal.
     */
    @Test
    public void destructiveActionsAreRefusedWithoutEverReachingTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("verstanden", null));
        ConceptToolRounds.run(
                turn("räume auf", "{\"type\":\"remove\",\"path\":[\"Buch\",\"Setup\"]}"),
                turns, tool, 4, 2, false, null, traceSink);
        assertTrue("the host never sees a model remove", tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0)
                .contains("Destructive concept edits (remove/rewrite) are not part of your "
                        + "contract"));
        assertTrue("the refusal is a visible outcome", trace.contains(
                "round 1 -> REFUSED (destructive concept edits are user-owned)"));

        trace.clear();
        ScriptedTurns rewriteTurns = new ScriptedTurns();
        ScriptedTool rewriteTool = new ScriptedTool();
        rewriteTurns.script.add(turn("ok", null));
        ConceptToolRounds.run(
                turn("überarbeite", "{\"type\":\"rewrite\",\"path\":[\"Setup\"],"
                        + "\"leaves\":[\"Arduino\"]}"),
                rewriteTurns, rewriteTool, 4, 2, false, null, traceSink);
        assertTrue(rewriteTool.calls.isEmpty());
        assertTrue(rewriteTurns.feedbackSeen.get(0).contains("manual work in the concept editor"));
    }

    /**
     * Gate 2 of the safety slice: in a read-only-classified turn the WHOLE mutation channel is
     * closed — the model once substituted partial adds for the withdrawn rewrite; in a
     * delete-classified turn even the exclude is refused (a concept deletion is not a scope
     * exclusion). Reads stay possible.
     */
    @Test
    public void aReadOnlyTurnRefusesEveryMutationIncludingSubstituteAdds() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("verstanden", null));
        ConceptToolRounds.run(
                turn("bau um", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"Tasks\"]}"),
                turns, tool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY);
        assertTrue("the substitute add never reaches the host", tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).contains("READ-ONLY for the whole turn"));
        assertTrue(trace.contains("concept mutations READ-ONLY this turn "
                + "(compound restructuring request)"));

        // A DELETE-classified turn is TERMINAL: no tool call (not even an exclude — a delete
        // wish never silently becomes a scope exclusion), no follow-up inference, and the
        // visible answer is the HOST'S deterministic sentence — the gate saw the model claim
        // "Ich habe den Zweig gelöscht" over an unchanged concept.
        trace.clear();
        ScriptedTurns deleteTurns = new ScriptedTurns();
        ScriptedTool deleteTool = new ScriptedTool();
        TeamAgentResult deleteResult = ConceptToolRounds.run(
                turn("lösche", "{\"type\":\"exclude\",\"topic\":\"FreeRTOS Grundlagen\"}"),
                deleteTurns, deleteTool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.DELETE_READ_ONLY);
        assertTrue(deleteTool.calls.isEmpty());
        assertTrue("no follow-up inference — the host owns the close",
                deleteTurns.feedbackSeen.isEmpty());
        assertEquals(TeamAgentPlaybook.deleteWishAnswer(false),
                ((ScopingAssistantOutput) deleteResult.getOutput()).getAssistantMessage());
        assertTrue(trace.contains("delete wish -> deterministic host answer (terminal, model "
                + "narration replaced)"));

        // Reading stays possible in a read-only turn — the model may inspect and discuss.
        ScriptedTurns readTurns = new ScriptedTurns();
        ScriptedTool readTool = new ScriptedTool();
        readTool.byDescription.put("read path=[\"X\"]", "{\"X\":[]}");
        readTurns.script.add(turn("gelesen", null));
        ConceptToolRounds.run(
                turn("zeig mal", "{\"type\":\"read\",\"path\":[\"X\"]}"),
                readTurns, readTool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY);
        assertEquals("read path=[\"X\"]", readTool.calls.get(0));
    }

    /**
     * Gate regression: the broad first turn EXHAUSTS its budget building cards, so the offer
     * nudge (normal-end only) never fired and a wrap-up offer was dropped. Now the wrap-up
     * feedback carries the nudge and a wrap-up OFFER executes despite the budget — tags are
     * display state, not a concept edit.
     */
    @Test
    public void aBudgetExhaustedFirstTurnStillGetsItsOfferViaTheWrapUp() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"A\"]", "added \"A\" revision=1");
        tool.byDescription.put("add_cards parent=[] names=[\"B\"]", "added \"B\" revision=2");
        tool.byDescription.put("offer suggestions=1", "OFFERED 1 tags");
        turns.script.add(turn("weiter", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"B\"]}"));
        turns.script.add(turn("fertig", "{\"type\":\"offer\",\"suggestions\":"
                + "[{\"query\":\"FreeRTOS Grundlagen Tutorial\"}]}"));

        ConceptToolRounds.run(
                turn("start", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\"]}"),
                turns, tool, 2, 2, false, null, traceSink, true,
                ConceptTurnPolicy.Mode.FULL);

        assertTrue("the wrap-up asks for the missing offer",
                turns.feedbackSeen.get(1).contains("SEARCH TAGS MISSING"));
        assertTrue(trace.contains("search tags missing — offer nudge in wrap-up"));
        assertTrue("the wrap-up offer executes despite the exhausted budget",
                tool.calls.contains("offer suggestions=1"));
        assertTrue(trace.contains(
                "wrap-up offer executed (tags are display state, not a concept edit)"));
    }

    /**
     * Gate ruling: the dedicated generator owns moves EXCLUSIVELY — a model-emitted move
     * (legacy transcript / misrouted) is REFUSED before the host, and a MOVE_TRUTH turn
     * without any receipt still closes with the deterministic host sentence.
     */
    @Test
    public void anInLoopMoveIsRefusedAndTheGuardStillClosesHonestly() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("Ich habe die Karte verschoben.", null));
        TeamAgentResult result = ConceptToolRounds.run(
                turn("verschiebe", "{\"type\":\"move\",\"source\":[\"Scheduling\"],"
                        + "\"parent\":[\"FreeRTOS\"]}"),
                turns, tool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.MOVE_TRUTH);
        assertTrue("the host never sees an in-loop move", tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).contains("You never emit a move action"));
        assertTrue(trace.contains("round 1 -> REFUSED (the application executes moves itself "
                + "on an explicit user order)"));
        assertEquals("no receipt, no narration — the host closes",
                TeamAgentPlaybook.moveTruthAnswer(false, null),
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
    }

    /**
     * The dedicated pre-loop move (two-field schema) seeds the truth guard: an APPLIED seed
     * keeps the model's narration, an "unclear" seed closes with the deterministic ask-back.
     */
    @Test
    public void seededMoveReceiptsSteerTheTruthGuard() throws Exception {
        ScriptedTurns moved = new ScriptedTurns();
        TeamAgentResult kept = ConceptToolRounds.run(
                turn("Scheduling liegt jetzt unter FreeRTOS.", "{\"type\":\"none\"}"),
                moved, new ScriptedTool(), 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.MOVE_TRUTH, true, null);
        assertEquals("the pre-executed APPLIED move licenses the narration",
                "Scheduling liegt jetzt unter FreeRTOS.",
                ((ScopingAssistantOutput) kept.getOutput()).getAssistantMessage());

        ScriptedTurns unclear = new ScriptedTurns();
        TeamAgentResult askedBack = ConceptToolRounds.run(
                turn("Ich habe es verschoben.", "{\"type\":\"none\"}"),
                unclear, new ScriptedTool(), 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.MOVE_TRUTH, false, "unclear");
        assertEquals(TeamAgentPlaybook.moveTruthAnswer(false, "unclear"),
                ((ScopingAssistantOutput) askedBack.getOutput()).getAssistantMessage());
        assertTrue(TeamAgentPlaybook.moveTruthAnswer(false, "unclear")
                .contains("name card and target clearly"));
    }

    /** Gate test 13, updated: in a READ-ONLY turn a move never reaches the host either. */
    @Test
    public void aReadOnlyTurnRefusesAMoveLikeEveryMutation() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("ok", null));
        ConceptToolRounds.run(
                turn("bau um", "{\"type\":\"move\",\"source\":[\"Scheduling\"],"
                        + "\"parent\":[]}"),
                turns, tool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY);
        assertTrue("the move never reaches the host", tool.calls.isEmpty());
        assertTrue("the dedicated-path refusal owns in-loop moves in EVERY mode",
                turns.feedbackSeen.get(0).contains("You never emit a move action"));
    }

    /** add_cards slice, test 11: a legacy transcript's single add is read but never executed. */
    @Test
    public void aLegacySingleAddIsRefusedBeforeTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("verstanden", null));
        ConceptToolRounds.run(
                turn("alt", "{\"type\":\"add\",\"parent\":[],\"name\":\"FreeRTOS\"}"),
                turns, tool, 4, 2, false, null, traceSink);
        assertTrue("the host never sees a single add", tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0)
                .contains("Send ONE add_cards action carrying ALL card names"));
        assertTrue(trace.contains("round 1 -> REFUSED (send ONE add_cards with ALL names)"));
    }

    @Test
    public void anInvalidActionNeverReachesTheToolButBecomesAReceipt() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("ok", null));
        ConceptToolRounds.run(
                turn("try", "{\"type\":\"add\",\"parent\":[\"X\"]}"), // name missing
                turns, tool, 4, 2, false, null, traceSink);
        assertTrue(tool.calls.isEmpty());
        assertTrue(turns.feedbackSeen.get(0).contains("REJECTED_ACTIONS\n- (invalid)"));
    }

    @Test
    public void theWorkBudgetEndsWithOneWrapUpTurnAndDropsFurtherActions() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"A\"]", "branch A");
        tool.byDescription.put("read path=[\"B\"]", "branch B");
        turns.script.add(turn("next", "{\"type\":\"read\",\"path\":[\"B\"]}"));
        turns.script.add(turn("wrap-up trotz Verbot", "{\"type\":\"read\",\"path\":[\"C\"]}"));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("start", "{\"type\":\"read\",\"path\":[\"A\"]}"),
                turns, tool, 2, 2, false, null, traceSink);

        assertEquals(2, tool.calls.size()); // reads only — no mutation, no grounding re-read
        assertTrue(turns.feedbackSeen.get(1).contains("TOOL BUDGET EXHAUSTED"));
        assertEquals("wrap-up trotz Verbot",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.get(trace.size() - 1).contains("dropping"));
    }

    @Test
    public void aDeadEndpointKeepsTheLastGoodAnswer() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("read path=[\"A\"]",
                new ToolInvoker.EndpointUnavailable("Connection refused"));
        TeamAgentResult initial = turn("ich schaue nach", "{\"type\":\"read\",\"path\":[\"A\"]}");

        TeamAgentResult result = ConceptToolRounds.run(initial, turns, tool, 4, 2, false, null, traceSink);

        assertEquals(initial, result);
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue(trace.get(trace.size() - 1).contains("unavailable"));
    }

    /**
     * GROUNDING (live-gate 2): a finished turn whose scopePatch failed validation said "Das ist
     * notiert" while the commit was rejected. The broken patch now costs one repair inference —
     * the model sees the violations, the sink still receives the invalid attempt (host-side
     * REJECTED observability), and the corrected answer replaces the false claim.
     */
    @Test
    public void aFinishedTurnWithABrokenScopePatchGetsOneScopeRepairTurn() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        final List<ScopingAssistantOutput> intermediates = new ArrayList<ScopingAssistantOutput>();
        ScopingAssistantOutputParser.Result broken = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"Das ist notiert.\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(broken.isOk());
        ScopingAssistantOutputParser.Result corrected = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"ESP-IDF ist ausgeschlossen.\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\","
                        + "\"facetId\":\"esp-idf\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(corrected.isOk());
        turns.script.add(TeamAgentResult.ok(corrected.getOutput(), null));

        TeamAgentResult result = ConceptToolRounds.run(
                TeamAgentResult.ok(broken.getOutput(), null), turns, new ScriptedTool(),
                4, 2, false,
                new ConceptToolRounds.IntermediateSink() {
                    public void intermediate(ScopingAssistantOutput output) {
                        intermediates.add(output);
                    }
                }, traceSink);

        assertEquals("the corrected answer replaces the false claim", "ESP-IDF ist ausgeschlossen.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        // The REPLACEMENT patch stays authoritative: the repaired output IS the returned final
        // result, and the caller (ResearchAgentSession.emitTeamAgentResult -> emitScopeUpdate)
        // emits exactly the final result's scope update to the host — repair fixes the COMMIT,
        // not just the wording.
        ScopeUpdateDocument replacement =
                ((ScopingAssistantOutput) result.getOutput()).getScopeUpdate();
        assertTrue("the corrected patch rides the final result into the authoritative emission",
                replacement != null && replacement.isValid());
        assertTrue(replacement.toJson(), replacement.toJson().contains("\"facetId\":\"esp-idf\""));
        String feedback = turns.feedbackSeen.get(0);
        assertTrue(feedback.startsWith("SCOPE PATCH REJECTED"));
        assertTrue("the violations travel verbatim", feedback.contains("excludeFacet without 'facetId'"));
        assertTrue("the false claim is forbidden explicitly", feedback.contains("NEVER claim"));
        assertTrue("the repair teaches the per-kind fields, not a vague kind+facetId hint",
                feedback.contains("Required fields per kind"));
        assertEquals("the invalid attempt still reaches the sink (host logs the rejection)",
                1, intermediates.size());
        assertTrue(!intermediates.get(0).getScopeUpdate().isValid());
        assertTrue(trace.toString(), trace.toString().contains("scope patch REJECTED"));
    }

    /**
     * The ONE-command exclusion facade is TERMINAL (gate 5): one command, one effect, turn over.
     * The visible answer is the host's deterministic receipt sentence — no further inference
     * ever gets the chance to contradict the committed blacklist entry ("keine dauerhaften
     * Änderungen" right after EXCLUDED was persisted).
     */
    @Test
    public void anExcludeActionIsTerminalAndAnswersFromTheReceipt() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("exclude topic=\"ESP-IDF\"",
                "{\"result\":\"EXCLUDED\",\"facetId\":\"esp-idf\",\"label\":\"ESP-IDF\","
                        + "\"conceptConflict\":{\"conflictId\":\"conflict-1\","
                        + "\"path\":[\"ESP32 und FreeRTOS Setup\",\"ESP-IDF\"]},"
                        + "\"requiredResponse\":\"INFORM_AND_ASK_REMOVE\","
                        + "\"userMessage\":\"„ESP-IDF“ wurde ausgeschlossen und wird bei der "
                        + "Recherche unterdrückt. Soll der Konzept-Eintrag entfernt werden?\"}");

        TeamAgentResult result = ConceptToolRounds.run(
                turn("schließe aus", "{\"type\":\"exclude\",\"topic\":\"ESP-IDF\"}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("ONE tool call, no grounding re-read, no follow-up inference",
                1, tool.calls.size());
        assertTrue("no model turn after the terminal command", turns.feedbackSeen.isEmpty());
        assertEquals("the visible answer IS the host's receipt sentence",
                "„ESP-IDF“ wurde ausgeschlossen und wird bei der Recherche unterdrückt. "
                        + "Soll der Konzept-Eintrag entfernt werden?",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.toString(), trace.toString().contains("EXCLUDED (terminal)"));
    }

    /**
     * Gate 9b: a card-building turn that ends without exploration tags earns ONE machinery
     * nudge for exactly the offer — and only while the session never offered (the caller's
     * flag). A second refusal is accepted; turns that built nothing are never nudged.
     */
    @Test
    public void aCardBuildingTurnWithoutTagsGetsExactlyOneOfferNudge() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"FreeRTOS\"]", "added \"FreeRTOS\" revision=1");
        tool.byDescription.put("offer suggestions=1", "{\"result\":\"OFFERED\",\"count\":1}");
        turns.script.add(turn("fertig", null)); // ends the turn WITHOUT an offer -> nudge
        turns.script.add(turn("hier sind Vorschläge", "{\"type\":\"offer\",\"suggestions\":["
                + "{\"query\":\"FreeRTOS Grundlagen\"}]}"));
        turns.script.add(turn("Schau sie dir an.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("lege an", "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"FreeRTOS\"]}"),
                turns, tool, 6, 2, false, null, traceSink, true);

        assertTrue("the nudge asks for exactly the missing step",
                turns.feedbackSeen.get(1).startsWith("SEARCH TAGS MISSING"));
        assertTrue("the model then offered", tool.calls.toString().contains("offer suggestions=1"));
        assertEquals("Schau sie dir an.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.toString(), trace.toString().contains("offer nudge turn"));
        assertTrue("an OFFERED receipt means NO grounding turn", !trace.contains(
                "offer nudge unanswered — search-suggestion grounding turn"));
    }

    /**
     * The turn-1 finding: nudge fired, the model answered with PROSE about suggestions but
     * no offer action — the objective condition (no OFFERED receipt) triggers ONE grounding
     * turn so the visible close cannot claim tags that do not exist.
     */
    @Test
    public void anUnansweredOfferNudgeGetsOneSearchSuggestionGroundingTurn() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"FreeRTOS\"]",
                "added \"FreeRTOS\" revision=1");
        turns.script.add(turn("fertig", null)); // no offer -> nudge
        turns.script.add(turn("Wir haben einige erste Suchvorschläge gemacht.", null));
        turns.script.add(turn("Struktur angelegt — Suchvorschläge folgen später.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("lege an", "{\"type\":\"add_cards\",\"parent\":[],"
                        + "\"names\":[\"FreeRTOS\"]}"),
                turns, tool, 6, 2, false, null, traceSink, true);

        assertTrue(trace.contains(
                "offer nudge unanswered — search-suggestion grounding turn"));
        String grounding = turns.feedbackSeen.get(2);
        assertTrue(grounding.startsWith("SEARCH SUGGESTIONS"));
        assertTrue(grounding.contains(
                "No search suggestions were actually offered in this turn."));
        assertTrue(grounding.contains(
                "Do not claim that suggestions were provided or are available."));
        assertEquals("the grounded close is the visible answer",
                "Struktur angelegt — Suchvorschläge folgen später.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertEquals("exactly ONE grounding turn, then the turn ends",
                3, turns.feedbackSeen.size());
    }

    @Test
    public void theOfferNudgeNeverFiresWithoutCardsOrWhenDisabled() throws Exception {
        // Disabled flag (session already offered): a card-building none-turn passes through.
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"X\"]", "added \"X\" revision=1");
        turns.script.add(turn("fertig", null));
        ConceptToolRounds.run(turn("lege an",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"X\"]}"),
                turns, tool, 4, 2, false, null, traceSink, false);
        assertEquals("only the ARTIFACT_STATE feedback of the add — no nudge",
                1, turns.feedbackSeen.size());

        // Enabled flag but the turn built NOTHING: a pure answer stays untouched.
        ScriptedTurns quiet = new ScriptedTurns();
        TeamAgentResult initial = turn("nur eine Antwort", null);
        assertEquals(initial, ConceptToolRounds.run(initial, quiet, new ScriptedTool(),
                4, 2, false, null, traceSink, true));
        assertTrue(quiet.feedbackSeen.isEmpty());
    }

    /** An OFFER is a working step like READ: tool reply as feedback, loop continues, not terminal. */
    @Test
    public void anOfferActionFeedsTheToolReplyBackAndContinues() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("offer suggestions=1", "{\"result\":\"OFFERED\",\"count\":1}");
        turns.script.add(turn("Schau dir gern die Vorschläge an.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich biete an", "{\"type\":\"offer\",\"suggestions\":["
                        + "{\"query\":\"FreeRTOS Grundlagen\"}]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("one tool call, no grounding re-read", 1, tool.calls.size());
        assertTrue("the reply reaches the model as a regular working-step result",
                turns.feedbackSeen.get(0).contains("\"result\":\"OFFERED\""));
        assertEquals("Schau dir gern die Vorschläge an.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.toString(), trace.toString().contains("OFFERED"));
    }

    /** An older host without userMessage in the receipt: the previous result stands (no raw JSON). */
    @Test
    public void aReceiptWithoutAUserMessageKeepsThePreviousAnswer() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("resolve conflict=\"conflict-1\" decision=REMOVE",
                "{\"result\":\"REMOVED\"}");
        TeamAgentResult initial = turn("wird entfernt",
                "{\"type\":\"resolve\",\"conflictId\":\"conflict-1\",\"decision\":\"REMOVE\"}");

        TeamAgentResult result = ConceptToolRounds.run(initial, turns, tool, 4, 2, false,
                null, traceSink);

        assertEquals(initial, result);
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue(trace.toString(), trace.toString().contains("RESOLVED (terminal)"));
    }

    /** A zero repair budget never loops: the broken-patch turn passes through unrepaired. */
    @Test
    public void aBrokenScopePatchWithoutRepairBudgetPassesThroughUntouched() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScopingAssistantOutputParser.Result broken = ScopingAssistantOutputParser.parse(
                "{\"assistantMessage\":\"notiert\","
                        + "\"scopePatch\":{\"operations\":[{\"kind\":\"excludeFacet\"}]},"
                        + "\"conceptAction\":{\"type\":\"none\"}}");
        assertTrue(broken.isOk());
        TeamAgentResult initial = TeamAgentResult.ok(broken.getOutput(), null);
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 0, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
    }

    @Test
    public void aTurnWithoutAnActionPassesThroughUntouched() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        TeamAgentResult initial = turn("nur eine Antwort", null);
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 2, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue("an ABSENT field leaves no NONE trace", trace.isEmpty());
    }

    @Test
    public void anExplicitNoneIsObservableInTheTrace() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        TeamAgentResult initial = turn("nichts zu tun", "{\"type\":\"none\"}");
        assertEquals(initial, ConceptToolRounds.run(initial, turns, new ScriptedTool(),
                4, 2, false, null, traceSink));
        assertTrue(turns.feedbackSeen.isEmpty());
        assertTrue("the model CHOSE none — distinguishable from an absent field",
                trace.contains("concept action: NONE"));
    }

    /**
     * The connector gate's success lie: an EXCLUDE_TRUTH turn whose narration claims the
     * exclusion over an unchanged scope is closed by the host, deterministically.
     */
    @Test
    public void anExcludeTruthTurnWithoutAnExcludedReceiptClosesDeterministically()
            throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();

        TeamAgentResult result = ConceptToolRounds.run(
                turn("Die Karte ist jetzt ausgeschlossen.", "{\"type\":\"none\"}"),
                turns, tool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.EXCLUDE_TRUTH);

        String answer = ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage();
        assertTrue("the host owns the close: " + answer,
                answer.contains("The requested exclusion was NOT applied this turn"));
        assertTrue(trace.contains("exclude-truth guard armed (explicit exclusion order)"));
        assertTrue(trace.toString(), trace.toString().contains(
                "exclude-truth guard -> deterministic host answer"));
    }

    /** scope_probe (AP3): a PROBE is a read-only working step — result feedback, no receipt. */
    @Test
    public void aProbeIsAWorkingStepLikeReadNeverAMutationReceipt() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("probe terms=[\"Priority Inversion\"]",
                "PROBED terms=1\n\"Priority Inversion\" -> LIKELY_IN band=CLEAR "
                        + "nearest=\"Tasks und Scheduling\" authority=CANONICAL_IN");
        turns.script.add(turn("Zusammenfassung.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich messe", "{\"type\":\"probe\",\"terms\":"
                        + "[\"Priority Inversion\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("Zusammenfassung.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("a working step, never a mutation", trace.contains("round 1 -> PROBED"));
        String feedback = turns.feedbackSeen.get(0);
        assertTrue(feedback.contains("LIKELY_IN"));
        assertTrue("the observation is a RESULT, not an APPLIED receipt",
                feedback.contains("CONCEPT TOOL RESULT"));
        assertTrue("no applied action was booked",
                feedback.contains("APPLIED_ACTIONS\n- (none)"));
    }

    /**
     * The AP3 gate's live failure verbatim: probe saw three NOVEL terms and the next round
     * tried to add them. The sensor lock refuses the mutation BEFORE the host — the probe's
     * observation never becomes the model's own decision.
     */
    @Test
    public void aPostProbeMutationIsRefusedBeforeTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("probe terms=[\"Priority Inversion\",\"Wi-Fi Stack\"]",
                "PROBED terms=2\n\"Priority Inversion\" -> NOVEL band=UNANCHORED"
                        + "\n\"Wi-Fi Stack\" -> NOVEL band=UNANCHORED");
        turns.script.add(turn("ich ergänze die Themen",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":"
                        + "[\"Priority Inversion\",\"Wi-Fi Stack\"]}"));
        turns.script.add(turn("Soll Wi-Fi Stack dazugehören?", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich messe", "{\"type\":\"probe\",\"terms\":"
                        + "[\"Priority Inversion\",\"Wi-Fi Stack\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals("the add NEVER reached the host", 1, tool.calls.size());
        assertTrue(tool.calls.get(0).startsWith("probe "));
        assertTrue(trace.contains("post-probe sensor lock armed (observation-only for the "
                + "rest of the turn)"));
        assertTrue(trace.contains("round 2 -> REFUSED (post-probe sensor lock: "
                + "observation-only turn)"));
        assertTrue("the refusal teaches the sensor rule",
                turns.feedbackSeen.get(1).contains("scope_probe is a SENSOR"));
        assertTrue("the receipts book the refusal honestly",
                turns.feedbackSeen.get(1).contains("refused (post-probe sensor lock)"));
        String close = ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage();
        assertTrue("the model's summary stays", close.startsWith(
                "Soll Wi-Fi Stack dazugehören?"));
        assertTrue("the HOST appends the mechanical membership question", close.contains(
                "Should one of the not-yet-anchored areas — \"Priority Inversion\" or "
                        + "\"Wi-Fi Stack\" — belong to the concept?"));
        assertTrue(trace.contains(
                "post-probe host question appended (NOVEL membership)"));
    }

    /** The host question follows the ratified priority mechanically — never model obedience. */
    @Test
    public void theHostAppendsExactlyTheRatifiedFollowUpQuestion() throws Exception {
        // BOUNDARY outranks NOVEL: the one question is the boundary question.
        assertEquals("Grenzfrage: Gehört „SMP Scheduling“ zum Themenrahmen, oder soll es "
                        + "außen bleiben?",
                TeamAgentPlaybook.probeFollowUpQuestion(true,
                        java.util.Arrays.asList("SMP Scheduling"),
                        java.util.Arrays.asList("Neuland")));
        assertEquals("Soll einer der noch nicht verankerten Bereiche — „A“, „B“ oder „C“ — "
                        + "zum Konzept gehören?",
                TeamAgentPlaybook.probeFollowUpQuestion(true,
                        java.util.Collections.<String>emptyList(),
                        java.util.Arrays.asList("A", "B", "C")));
        assertEquals("clear-only asks nothing", null,
                TeamAgentPlaybook.probeFollowUpQuestion(true,
                        java.util.Collections.<String>emptyList(),
                        java.util.Collections.<String>emptyList()));

        // End-to-end: a clear-only probe closes WITHOUT any appended question.
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("probe terms=[\"Tasks\"]",
                "PROBED terms=1\n\"Tasks\" -> LIKELY_IN band=CLEAR authority=CANONICAL_IN");
        turns.script.add(turn("Tasks ist fest verankert.", null));
        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich messe", "{\"type\":\"probe\",\"terms\":[\"Tasks\"]}"),
                turns, tool, 4, 2, false, null, traceSink);
        assertEquals("Tasks ist fest verankert.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("no question line in the trace", !trace.toString().contains(
                "post-probe host question appended"));
    }

    /** Reads stay available after a probe — they ground the summary, they change nothing. */
    @Test
    public void aPostProbeReadStillGroundsTheSummary() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("probe terms=[\"GUI\"]",
                "PROBED terms=1\n\"GUI\" -> LIKELY_OUT band=CLEAR authority=CANONICAL_OUT");
        turns.script.add(turn("ich lese nach", "{\"type\":\"read\",\"path\":[]}"));
        turns.script.add(turn("GUI bleibt draußen.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("ich messe", "{\"type\":\"probe\",\"terms\":[\"GUI\"]}"),
                turns, tool, 4, 2, false, null, traceSink);

        assertEquals(2, tool.calls.size());
        assertEquals("read path=[]", tool.calls.get(1));
        assertTrue(trace.contains("round 2 -> RESULT"));
        assertEquals("GUI bleibt draußen.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the read feedback re-carries the probe directive (fallback form)",
                turns.feedbackSeen.get(1).contains("STILL BINDING — this turn's probe:"));
    }

    /**
     * AP3 retest 2 (clear-only red): reads after the probe displaced the MEANING rules as
     * the last grounding before the narration — the directive is sticky now, every later
     * feedback of the turn carries it verbatim.
     */
    @Test
    public void theProbeMeaningDirectiveStaysStickyThroughLaterReads() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("probe terms=[\"Tasks\",\"Scheduling\"]",
                "PROBED terms=2\n\"Tasks\" -> LIKELY_IN band=CLEAR authority=CANONICAL_IN"
                        + "\n\"Scheduling\" -> LIKELY_IN band=CLEAR authority=CANONICAL_IN"
                        + "\nMEANING — hard rules for your reply:"
                        + "\n- Ask NO question — summarize briefly; the USER decides "
                        + "whether to continue or close."
                        + "\nThis is an OBSERVATION only — nothing was changed.");
        turns.script.add(turn("ich lese", "{\"type\":\"read\",\"path\":[]}"));
        turns.script.add(turn("ich lese noch mal", "{\"type\":\"read\",\"path\":[]}"));
        turns.script.add(turn("Beide Bereiche sind verankert.", null));

        ConceptToolRounds.run(
                turn("ich messe", "{\"type\":\"probe\",\"terms\":"
                        + "[\"Tasks\",\"Scheduling\"]}"),
                turns, tool, 6, 2, false, null, traceSink);

        for (int index = 1; index < turns.feedbackSeen.size(); index++) {
            String feedback = turns.feedbackSeen.get(index);
            assertTrue("feedback " + index + " keeps the directive present",
                    feedback.contains("MEANING — hard rules"));
            assertTrue("the clear-only rule survives every read",
                    feedback.contains("Ask NO question"));
        }
        assertTrue(turns.feedbackSeen.get(1)
                .contains("STILL BINDING — this turn's probe:"));
    }

    /** An observation-only turn never gets the offer nudge — it would demand a refused action. */
    @Test
    public void theOfferNudgeStandsDownAfterAProbe() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"FreeRTOS\"]",
                "added \"FreeRTOS\" revision=1");
        tool.byDescription.put("probe terms=[\"GUI\"]",
                "PROBED terms=1\n\"GUI\" -> LIKELY_OUT band=CLEAR authority=CANONICAL_OUT");
        turns.script.add(turn("ich messe noch",
                "{\"type\":\"probe\",\"terms\":[\"GUI\"]}"));
        turns.script.add(turn("GUI bleibt draußen.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("lege an", "{\"type\":\"add_cards\",\"parent\":[],"
                        + "\"names\":[\"FreeRTOS\"]}"),
                turns, tool, 6, 2, false, null, traceSink, true);

        assertTrue("no nudge in an observation-only turn",
                !trace.toString().contains("offer nudge"));
        assertEquals("GUI bleibt draußen.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
    }

    /** THE derailed first turn, pinned: the identical add_cards never reaches the host again. */
    @Test
    public void anExactRepetitionOfAnExecutedMutationEndsBeforeTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"A\",\"B\",\"C\"]",
                "APPLIED revision=1\nADDED: A\nADDED: B\nADDED: C");
        turns.script.add(turn("nochmal",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\",\"B\",\"C\"]}"));
        turns.script.add(turn("Fertig angelegt.", null));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("lege an",
                        "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\",\"B\",\"C\"]}"),
                turns, tool, 6, 2, false, null, traceSink);

        int hostCalls = 0;
        for (String call : tool.calls) {
            if (call.startsWith("add_cards")) {
                hostCalls++;
            }
        }
        assertEquals("host invocation count stays 1", 1, hostCalls);
        assertTrue(trace.contains("round 2 -> REFUSED (exact repetition of an executed "
                + "mutation)"));
        assertTrue("the refusal is booked honestly", turns.feedbackSeen.get(1)
                .contains("add_cards parent=[] names=[\"A\",\"B\",\"C\"] — already executed "
                        + "this turn"));
        assertEquals("Fertig angelegt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
    }

    /** NO_CHANGE is the third truth: never APPLIED, and it arms the repetition guard too. */
    @Test
    public void aNoChangeAddIsBookedHonestlyAndItsRepetitionIsRefused() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"A\",\"B\"]",
                "NO_CHANGE revision=3\nALREADY_PRESENT: A\nALREADY_PRESENT: B");
        turns.script.add(turn("nochmal",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\",\"B\"]}"));
        turns.script.add(turn("Schon vorhanden.", null));

        ConceptToolRounds.run(
                turn("lege an",
                        "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\",\"B\"]}"),
                turns, tool, 6, 2, false, null, traceSink);

        String receipts = turns.feedbackSeen.get(0);
        assertTrue("the no-op is its own category", receipts.contains(
                "NO_CHANGE_ACTIONS\n- add_cards parent=[] names=[\"A\",\"B\"] — already "
                        + "unchanged"));
        assertTrue("APPLIED stays empty", receipts.contains("APPLIED_ACTIONS\n- (none)"));
        assertTrue("the binding rule names the third truth", receipts.contains(
                "NO_CHANGE_ACTIONS were valid requests whose resulting artifact was already "
                        + "unchanged"));
        assertTrue(trace.contains("round 1 -> NO_CHANGE"));
        int hostCalls = 0;
        for (String call : tool.calls) {
            if (call.startsWith("add_cards")) {
                hostCalls++;
            }
        }
        assertEquals("the repetition after NO_CHANGE never reaches the host", 1, hostCalls);
        assertTrue(trace.contains("round 2 -> REFUSED (exact repetition of an executed "
                + "mutation)"));
    }

    /** Negative pins: reads and probes repeat freely; a DIFFERENT add_cards stays allowed. */
    @Test
    public void theRepetitionGuardNeverTouchesReadsProbesOrDifferentMutations()
            throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"A\"]",
                "APPLIED revision=1\nADDED: A");
        tool.byDescription.put("add_cards parent=[] names=[\"B\"]",
                "APPLIED revision=2\nADDED: B");
        turns.script.add(turn("lese", "{\"type\":\"read\",\"path\":[]}"));
        turns.script.add(turn("lese wieder", "{\"type\":\"read\",\"path\":[]}"));
        turns.script.add(turn("anderes add",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"B\"]}"));
        turns.script.add(turn("Fertig.", null));

        ConceptToolRounds.run(
                turn("lege an",
                        "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"A\"]}"),
                turns, tool, 8, 2, false, null, traceSink);

        int reads = 0;
        int adds = 0;
        for (String call : tool.calls) {
            if (call.startsWith("read")) {
                reads++;
            }
            if (call.startsWith("add_cards")) {
                adds++;
            }
        }
        assertTrue("reads repeat freely", reads >= 2);
        assertEquals("two DIFFERENT adds both reach the host", 2, adds);
        assertTrue("no repetition refusal in this turn", !trace.toString().contains(
                "exact repetition of an executed mutation"));
    }

    /** A committed exclusion is terminal with the receipt answer — the guard never fires. */
    @Test
    public void aCommittedExclusionSatisfiesTheExcludeTruthGuard() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("exclude topic=\"ESP-IDF\"",
                "{\"result\":\"EXCLUDED\",\"userMessage\":\"„ESP-IDF“ wurde ausgeschlossen.\"}");

        TeamAgentResult result = ConceptToolRounds.run(
                turn("schließe aus", "{\"type\":\"exclude\",\"topic\":\"ESP-IDF\"}"),
                turns, tool, 4, 2, false, null, traceSink, false,
                ConceptTurnPolicy.Mode.EXCLUDE_TRUTH);

        assertEquals("„ESP-IDF“ wurde ausgeschlossen.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.toString(), trace.toString().contains("EXCLUDED (terminal)"));
    }

    /**
     * Scoping regression (Meerschweinchen): the visible answer spelled "add_cards: …" while the action
     * channel said none. That is never a finished turn: the loop repairs it, the model sends the action
     * through conceptAction, the host executes it and only the clean final answer reaches the user.
     */
    @Test
    public void anActionSpelledInTheVisibleAnswerIsRepairedNotTreatedAsDone() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung und Sozialverhalten\","
                + "\"Ernährung und Futter\"]", "added 2 cards revision=1");
        turns.script.add(turn("Ich lege die Bereiche an.", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung und Sozialverhalten\",\"Ernährung und Futter\"]}"));
        turns.script.add(turn("Ich habe Haltung und Ernährung angelegt.", "{\"type\":\"none\"}"));

        TeamAgentResult result = ConceptToolRounds.run(
                turn("add_cards:\\n    - Haltung und Sozialverhalten\\n    - Ernährung und Futter\\n"
                        + "    offer: Welche spezifischen Aspekte?", "{\"type\":\"none\"}"),
                turns, tool, 4, 2, true, null, traceSink);

        assertEquals("Ich habe Haltung und Ernährung angelegt.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the repair names the contradiction",
                turns.feedbackSeen.get(0).contains("ACTION WRITTEN AS TEXT"));
        assertTrue("and that nothing was executed", turns.feedbackSeen.get(0).contains("NICHTS wurde ausgeführt"));
        assertTrue("the action then went through the structured channel", tool.calls.contains(
                "add_cards parent=[] names=[\"Haltung und Sozialverhalten\",\"Ernährung und Futter\"]"));
        assertTrue(trace.toString(), trace.toString().contains(
                "visible answer spells the action \"add_cards\" while conceptAction is none — NOT executed"));
        assertTrue(trace.contains("round 1 -> APPLIED revision=1"));
    }

    /** Hasenhaltung regression: a bare "none" as the visible answer is a leak, not a reply. */
    @Test
    public void aBareNoneAsTheVisibleAnswerIsRepaired() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("Worum genau soll es bei der Hasenhaltung gehen?", "{\"type\":\"none\"}"));

        TeamAgentResult result = ConceptToolRounds.run(turn("none", "{\"type\":\"none\"}"),
                turns, tool, 4, 2, true, null, traceSink);

        assertEquals("Worum genau soll es bei der Hasenhaltung gehen?",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertEquals(1, turns.feedbackSeen.size());
    }

    /** Without repair budget the leaked text still never reaches the user, and nothing is claimed. */
    @Test
    public void withoutRepairBudgetTheHostAnswersHonestlyInsteadOfShowingTheLeak() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();

        TeamAgentResult result = ConceptToolRounds.run(
                turn("add_cards:\\n- Haltung", "{\"type\":\"none\"}"), turns, tool, 4, 0, true, null,
                traceSink);

        String visible = ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage();
        assertEquals(TeamAgentPlaybook.conceptActionLeakAnswer(true,
                java.util.Collections.<String>emptyList(), false), visible);
        assertTrue("the host answer says nothing changed", visible.contains("nichts geändert"));
        assertTrue("and never the leaked text", !visible.contains("add_cards"));
        assertTrue("no follow-up inference", turns.feedbackSeen.isEmpty());
        assertTrue("no concept call", tool.calls.isEmpty());
    }

    /** Prompt-contamination guard: an instruction placeholder never becomes a card. */
    @Test
    public void aPlaceholderCopiedFromTheInstructionsIsRefusedBeforeTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        turns.script.add(turn("Welche Bereiche der Hasenhaltung interessieren dich?", "{\"type\":\"none\"}"));

        TeamAgentResult result = ConceptToolRounds.run(turn("lege an",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"<area 1>\",\"<area 2>\"]}"),
                turns, tool, 4, 2, true, null, traceSink);

        assertEquals("Welche Bereiche der Hasenhaltung interessieren dich?",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("the placeholder never reached the host", tool.calls.isEmpty());
        assertTrue(trace.toString(), trace.contains("round 1 -> REFUSED (placeholder copied from the instructions)"));
        assertTrue(turns.feedbackSeen.get(0).contains("a name in <angle brackets>"));
    }

    /** Angle-bracket names the user wrote literally still go through: only invented placeholders are refused. */
    @Test
    public void legitimateAngleBracketNamesAreNoPlaceholders() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"<h1>\",\"std::vector<int>\"]",
                "added 2 cards revision=1");
        turns.script.add(turn("Angelegt.", "{\"type\":\"none\"}"));

        ConceptToolRounds.run(turn("lege an",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"<h1>\",\"std::vector<int>\"]}"),
                turns, tool, 4, 2, true, null, traceSink, false, ConceptTurnPolicy.Mode.FULL, false, null,
                "Lege Karten für <h1> und std::vector<int> an.");

        assertTrue(tool.calls.toString(),
                tool.calls.contains("add_cards parent=[] names=[\"<h1>\",\"std::vector<int>\"]"));
    }

    /** An over-budget wrap-up offer with an instruction placeholder never becomes a search tag. */
    @Test
    public void aWrapUpOfferWithAPlaceholderIsNotExecuted() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\"]", "added revision=1");
        turns.script.add(turn("Fertig.", "{\"type\":\"offer\",\"suggestions\":[{\"query\":"
                + "\"<topic> basics overview\",\"purpose\":\"x\"}]}"));

        ConceptToolRounds.run(turn("lege an", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung\"]}"), turns, tool, 1, 2, true, null, traceSink);

        for (String call : tool.calls) {
            assertTrue("no offer reached the host: " + tool.calls, !call.startsWith("offer"));
        }
        assertTrue(trace.toString(), trace.contains(
                "wrap-up offer REFUSED (placeholder copied from the instructions)"));
    }

    /**
     * Meerschweinchen live gate: gemma invented "<name1>"/"<name2>" (not in any instruction) and the host
     * stored them. A value that is nothing but an angle-bracket token, or "...", is a placeholder unless
     * the user wrote it literally.
     */
    @Test
    public void anInventedPlaceholderLikeName1IsRefusedBeforeTheHost() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]",
                "APPLIED revision=1\nADDED: Haltung\nADDED: Ernährung");
        turns.script.add(turn("Ich lege die Bereiche an.", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung\",\"Ernährung\"]}"));
        turns.script.add(turn("Haltung und Ernährung sind angelegt.", "{\"type\":\"none\"}"));

        ConceptToolRounds.run(turn("lege an",
                "{\"type\":\"add_cards\",\"parent\":[],\"names\":[\"<name1>\",\"<name2>\"]}"),
                turns, tool, 4, 2, true, null, traceSink, false, ConceptTurnPolicy.Mode.FULL, false, null,
                "Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");

        for (String call : tool.calls) {
            assertTrue("a placeholder reached the host: " + tool.calls, !call.contains("<name"));
        }
        assertTrue(tool.calls.contains("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]"));
        assertTrue(trace.contains("round 1 -> REFUSED (placeholder copied from the instructions)"));
    }

    @Test
    public void anEllipsisQueryIsAPlaceholderToo() {
        ConceptAction offer = ScopingAssistantOutputParser.parse("{\"assistantMessage\":\"x\",\"conceptAction\":"
                + "{\"type\":\"offer\",\"suggestions\":[{\"query\":\"...\",\"purpose\":\"...\"}]}}")
                .getOutput().getConceptAction();
        assertTrue(ConceptToolRounds.hasPlaceholder(offer));
        ConceptAction real = ScopingAssistantOutputParser.parse("{\"assistantMessage\":\"x\",\"conceptAction\":"
                + "{\"type\":\"offer\",\"suggestions\":[{\"query\":\"Meerschweinchen Futter\"}]}}")
                .getOutput().getConceptAction();
        assertTrue(!ConceptToolRounds.hasPlaceholder(real));
    }

    /**
     * Meerschweinchen live gate: the first turn only offered searches and ended with an EMPTY concept.
     * One machinery nudge asks for the missing cards; they then go through conceptAction.
     */
    @Test
    public void aFirstTurnThatBuiltNoCardGetsOneCardNudge() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("offer suggestions=1", "OFFERED 1");
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]",
                "APPLIED revision=1\nADDED: Haltung\nADDED: Ernährung");
        turns.script.add(turn("Ich schaue mir das an.", "{\"type\":\"none\"}"));
        turns.script.add(turn("Ich lege die Bereiche an.", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung\",\"Ernährung\"]}"));
        turns.script.add(turn("Haltung und Ernährung stehen jetzt im Konzept.", "{\"type\":\"none\"}"));

        TeamAgentResult result = ConceptToolRounds.run(turn("Gern!",
                "{\"type\":\"offer\",\"suggestions\":[{\"query\":\"Meerschweinchen Haltung\"}]}"),
                turns, tool, 4, 2, true, null, traceSink, true, ConceptTurnPolicy.Mode.FULL, false, null,
                "Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");

        assertEquals("Haltung und Ernährung stehen jetzt im Konzept.",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue(trace.toString(), trace.contains("concept cards missing (concept empty) — card nudge turn"));
        assertTrue(turns.feedbackSeen.get(1).startsWith("CONCEPT CARDS MISSING"));
        assertTrue(tool.calls.contains("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]"));
    }

    @Test
    public void aBareGreetingNeverGetsTheCardNudge() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();

        TeamAgentResult result = ConceptToolRounds.run(turn("Hallo! Worüber möchtest du recherchieren?",
                "{\"type\":\"none\"}"), turns, tool, 4, 2, true, null, traceSink, true,
                ConceptTurnPolicy.Mode.FULL, false, null, "Hallo");

        assertEquals("Hallo! Worüber möchtest du recherchieren?",
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        assertTrue("no nudge, no concept call", tool.calls.isEmpty() && turns.feedbackSeen.isEmpty());
    }

    /** The honest close names exactly the cards the host reported ADDED — never a generic sentence. */
    @Test
    public void theHonestCloseIsBuiltFromTheReceipts() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]",
                "APPLIED revision=1\nADDED: Haltung\nADDED: Ernährung");
        turns.script.add(turn("offer:\\n- Meerschweinchen Futter", "{\"type\":\"none\"}"));

        TeamAgentResult result = ConceptToolRounds.run(turn("lege an", "{\"type\":\"add_cards\","
                + "\"parent\":[],\"names\":[\"Haltung\",\"Ernährung\"]}"), turns, tool, 4, 0, true, null,
                traceSink);

        String visible = ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage();
        assertTrue(visible, visible.startsWith("Ich habe ins Konzept übernommen: Haltung, Ernährung."));
        assertTrue(visible, !visible.contains("nicht ausführen"));
    }

    /**
     * Second live acceptance: free nudges kept answering "none". The card nudge restricts the grammar to
     * add_cards, the offer nudge to offer, the closing grounding turn to none.
     */
    @Test
    public void nudgesRestrictTheGrammarToTheActionTheyAskFor() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\",\"Ernährung\"]",
                "APPLIED revision=1\nADDED: Haltung\nADDED: Ernährung");
        tool.byDescription.put("offer suggestions=1", "OFFERED 1");
        turns.script.add(turn("Ich lege die Bereiche an.", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung\",\"Ernährung\"]}"));
        turns.script.add(turn("Haltung und Ernährung stehen im Konzept.", "{\"type\":\"none\"}"));
        turns.script.add(turn("Hier sind Suchvorschläge.", "{\"type\":\"offer\",\"suggestions\":"
                + "[{\"query\":\"Meerschweinchen Heu Vitamin C\"}]}"));
        turns.script.add(turn("Haltung und Ernährung stehen im Konzept, Suchvorschläge sind bereit.",
                "{\"type\":\"none\"}"));

        ConceptToolRounds.run(turn("Gern!", "{\"type\":\"none\"}"), turns, tool, 4, 2, true, null, traceSink,
                true, ConceptTurnPolicy.Mode.FULL, false, null,
                "Ich möchte mich über artgerechte Haltung und Ernährung von Meerschweinchen informieren.");

        assertEquals(java.util.Arrays.asList("add_cards", null, "offer", null), turns.requiredTypes);
        assertTrue(tool.calls.toString(), tool.calls.contains("offer suggestions=1"));
    }

    /** A malformed or placeholder add_cards/offer is repaired with the grammar of exactly that action. */
    @Test
    public void aRepairOfAMalformedOfferAsksForAnOfferAgain() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("offer suggestions=1", "OFFERED 1");
        turns.script.add(turn("Hier sind Suchvorschläge.", "{\"type\":\"offer\",\"suggestions\":"
                + "[{\"query\":\"Meerschweinchen Gehege\"}]}"));
        turns.script.add(turn("Suchvorschläge sind bereit.", "{\"type\":\"none\"}"));

        ConceptToolRounds.run(turn("x", "{\"type\":\"offer\",\"suggestions\":[]}"), turns, tool, 4, 2,
                true, null, traceSink);

        assertEquals("offer", turns.requiredTypes.get(0));
        assertTrue(trace.contains("follow-up grammar admits only offer"));
        assertTrue(tool.calls.contains("offer suggestions=1"));
    }

    @Test
    public void aLeakedAddCardsIsRepairedWithTheAddCardsGrammar() throws Exception {
        ScriptedTurns turns = new ScriptedTurns();
        ScriptedTool tool = new ScriptedTool();
        tool.byDescription.put("add_cards parent=[] names=[\"Haltung\"]", "APPLIED revision=1\nADDED: Haltung");
        turns.script.add(turn("Ich lege Haltung an.", "{\"type\":\"add_cards\",\"parent\":[],"
                + "\"names\":[\"Haltung\"]}"));
        turns.script.add(turn("Haltung steht im Konzept.", "{\"type\":\"none\"}"));

        ConceptToolRounds.run(turn("add_cards:\n- Haltung", "{\"type\":\"none\"}"), turns, tool, 4, 2, true,
                null, traceSink);

        assertEquals("add_cards", turns.requiredTypes.get(0));
        assertTrue(tool.calls.contains("add_cards parent=[] names=[\"Haltung\"]"));
    }
}
