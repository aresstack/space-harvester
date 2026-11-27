package com.aresstack.askai.research.runtime.team;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The TOOL-ROUND loop of a conceptualization turn — deliberately NOT just a repair loop. With the
 * one-JSON-per-inference contract the model cannot read a branch and edit it within the SAME
 * inference, so each tool step is its own round: inference → conceptAction → host tool →
 * feedback → next inference. Three feedback kinds are kept explicit:
 * <ul>
 *   <li>{@code TOOL_RESULT} — a regular read result (a working step, budget: tool rounds);</li>
 *   <li>{@code TOOL_APPLIED} — a mutation committed (working step as well);</li>
 *   <li>{@code TOOL_REJECTED} — a mutation refused with a diagnostic (counts against the
 *       SEPARATE repair budget, so error tolerance never eats the work budget or vice versa).</li>
 * </ul>
 * Both budgets are the user's settings (env hand-off), never hidden constants. When either budget
 * is exhausted the model gets ONE final wrap-up inference that explicitly forbids further actions;
 * an action it emits anyway is dropped with a trace line. An unreachable endpoint aborts the loop
 * and keeps the last good result — never a fabricated turn. The change semantics stay entirely in
 * the host's ConceptBranchService; this class only orchestrates.
 */
public final class ConceptToolRounds {

    /** Documented defaults; the EFFECTIVE values are the user's settings, handed to the process. */
    public static final int DEFAULT_MAX_TOOL_ROUNDS = 4;
    public static final int DEFAULT_MAX_REPAIR_ATTEMPTS = 2;

    /** One follow-up inference within the same user turn (machinery instruction, not user text). */
    public interface FollowUpTurn {
        TeamAgentResult run(String feedbackInstruction);

        /**
         * A machinery turn that asks for exactly ONE action type ("add_cards", "offer", "none"): the
         * generation-time grammar then admits only that variant (second live acceptance: free nudges and
         * repairs kept answering "none" or narrating the action). Hosts without a grammar ignore the type.
         */
        default TeamAgentResult run(String feedbackInstruction, String requiredActionType) {
            return run(feedbackInstruction);
        }
    }

    /** The synchronous host tool call (MCP lane). Returns the tool's text; failures throw. */
    public interface ConceptTool {
        String call(ConceptAction action)
                throws ToolInvoker.ToolFailure, ToolInvoker.EndpointUnavailable;
    }

    /** Technical trace lines (collapsible diagnostics on the host, never a chat bubble). */
    public interface Trace {
        void line(String message);
    }

    /**
     * Receives every INTERMEDIATE output the loop replaces with a follow-up inference. The live
     * exclusion bug: a turn answering "kein ESP-IDF, nur Arduino" carried conceptAction AND
     * scopePatch — the loop kept only the final (usually patch-free) result, so the exclusion
     * silently vanished. The caller emits each consumed round's scope update through this sink.
     */
    public interface IntermediateSink {
        void intermediate(ScopingAssistantOutput output);
    }

    /**
     * The placeholders the instructions themselves use, anywhere inside a value ({@code "<topic> basics"}).
     */
    private static final java.util.regex.Pattern INSTRUCTION_PLACEHOLDER = java.util.regex.Pattern.compile(
            "<(topic|area \\d+|Bereich \\d+|card|child card|card name|subtopic|new name|new parent|term"
                    + "|term \\d+|leaf \\d+|facet-id|direction|first direction|second direction"
                    + "|third direction|core aspect|practical aspect|X|Y|Z)>");
    /**
     * A value that is NOTHING but an angle-bracket token or an ellipsis: {@code <name1>} (the Meerschweinchen
     * live gate — a placeholder the model invented after the instructions' style), {@code <Bereich>},
     * {@code ...}. Such a value is a placeholder unless the user or the current concept carries it literally.
     */
    private static final java.util.regex.Pattern WHOLE_PLACEHOLDER = java.util.regex.Pattern.compile(
            "^\\s*(<[^<>]{1,40}>|\\.{3}|\u2026)\\s*$");

    private ConceptToolRounds() {
    }

    /**
     * Drive the loop starting from the turn's FIRST result. Returns the FINAL result — the only
     * one whose visible message reaches the user; every intermediate answer is a working step.
     */
    /** @param germanFeedback the session's language selector: feedback sentences follow it so
     *  the model is never pushed into switching its reply language. */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace) {
        return run(initial, turn, tool, maxToolRounds, maxRepairAttempts, germanFeedback,
                intermediateSink, trace, false);
    }

    /**
     * @param nudgeOfferWhenMissing gate 9b: the session has never offered exploration tags —
     *  a turn that BUILT concept cards but ends without an offer gets ONE machinery nudge for
     *  exactly the missing step (the FIRST-TURN DRILL prose alone did not move the model).
     */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace,
                                      boolean nudgeOfferWhenMissing) {
        return run(initial, turn, tool, maxToolRounds, maxRepairAttempts, germanFeedback,
                intermediateSink, trace, nudgeOfferWhenMissing, ConceptTurnPolicy.Mode.FULL);
    }

    /**
     * @param mode the turn's machine-classified mutation policy (safety-slice gate 2): in a
     *  read-only turn concept MUTATIONS are refused for the WHOLE turn — capability withdrawal
     *  per action was not enough, the model substituted partial adds for the forbidden rewrite;
     *  a DELETE-classified turn additionally refuses the exclude (a concept deletion is not a
     *  scope exclusion).
     */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace,
                                      boolean nudgeOfferWhenMissing,
                                      ConceptTurnPolicy.Mode mode) {
        return run(initial, turn, tool, maxToolRounds, maxRepairAttempts, germanFeedback,
                intermediateSink, trace, nudgeOfferWhenMissing, mode, false, null);
    }

    /**
     * @param seededMovedReceipt / @param seededMoveOutcome the DEDICATED move generation ran
     *  BEFORE this loop (move-gate ruling: the universal grammar starved source/parent) and
     *  already holds the authoritative receipt state — the truth guard judges the whole turn
     *  including that pre-executed move.
     */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace,
                                      boolean nudgeOfferWhenMissing,
                                      ConceptTurnPolicy.Mode mode,
                                      boolean seededMovedReceipt, String seededMoveOutcome) {
        return run(initial, turn, tool, maxToolRounds, maxRepairAttempts, germanFeedback,
                intermediateSink, trace, nudgeOfferWhenMissing, mode, seededMovedReceipt,
                seededMoveOutcome, null);
    }

    /**
     * @param userText the user's message of this turn (null for machinery turns): a name the user wrote
     *  literally — even one in angle brackets such as {@code <h1>} — is never taken for a placeholder, and a
     *  bare greeting never triggers the card nudge.
     */
    public static TeamAgentResult run(TeamAgentResult initial, FollowUpTurn turn,
                                      ConceptTool tool, int maxToolRounds,
                                      int maxRepairAttempts, boolean germanFeedback,
                                      IntermediateSink intermediateSink, Trace trace,
                                      boolean nudgeOfferWhenMissing,
                                      ConceptTurnPolicy.Mode mode,
                                      boolean seededMovedReceipt, String seededMoveOutcome,
                                      String userText) {
        boolean readOnly = mode == ConceptTurnPolicy.Mode.RESTRUCTURE_READ_ONLY
                || mode == ConceptTurnPolicy.Mode.DELETE_READ_ONLY;
        if (readOnly) {
            trace.line("concept mutations READ-ONLY this turn ("
                    + (mode == ConceptTurnPolicy.Mode.DELETE_READ_ONLY
                            ? "delete wish — manual editor work"
                            : "compound restructuring request") + ")");
        }
        if (mode == ConceptTurnPolicy.Mode.MOVE_TRUTH) {
            trace.line("move-truth guard armed (explicit move order)");
        }
        if (mode == ConceptTurnPolicy.Mode.EXCLUDE_TRUTH) {
            trace.line("exclude-truth guard armed (explicit exclusion order)");
        }
        if (mode == ConceptTurnPolicy.Mode.DELETE_READ_ONLY) {
            // TERMINAL like the exclusion receipt (safety-gate rerun: the refusal held, but the
            // model's free-form close claimed the branch was deleted over an unchanged concept).
            // The host answers deterministically; the turn's other proposals are DROPPED on
            // purpose — a delete turn changes neither the concept nor the scope.
            trace.line("delete wish -> deterministic host answer (terminal, model narration "
                    + "replaced)");
            return syntheticAnswer(TeamAgentPlaybook.deleteWishAnswer(germanFeedback), initial);
        }
        TeamAgentResult result = initial;
        int rounds = 0;
        int repairs = 0;
        // Receipt truth (move_leaf slice): "verschoben" may only close a turn when a MOVED
        // receipt of THIS turn covers it — otherwise the host owns the closing sentence.
        boolean movedReceipt = seededMovedReceipt;
        String moveOutcome = seededMoveOutcome;
        // AP3 gate finding: after a probe the SAME loop kept FULL permission and the model
        // wrote all three NOVEL terms straight into the concept. "Sensor, never author" is
        // MACHINERY now: once a probe ran, the remainder of the turn is structurally
        // observation-only — prompt text alone never held.
        boolean probedThisTurn = false;
        // AP3 retest 2: the MEANING directive sat on the probe receipt, but later READ
        // feedbacks displaced it as the LAST grounding before the narration (clear-only
        // probe ended in "Möchten Sie mit der Recherche beginnen?"). The directive is
        // STICKY for the rest of the turn — turn-local only, never persisted.
        String probeMeaningReminder = null;
        String probeReceiptText = null;
        boolean budgetExhausted = false;
        boolean offeredThisTurn = false;
        boolean offerNudgeSpent = false;
        boolean cardsNudgeSpent = false;
        // The cards the host reported ADDED this turn (receipt lines), for an honest host close.
        java.util.List<String> addedCards = new java.util.ArrayList<String>();
        boolean suggestionGroundingSpent = false;
        // The AUTHORITATIVE change receipts, carried across the rounds: every feedback lists
        // WHICH actions were applied and WHICH were rejected (and why), plus the CURRENT
        // persisted concept after any mutation attempt — a lone boolean once let one applied
        // add be claimed as four.
        long conceptRevision = -1L;
        java.util.List<String> applied = new java.util.ArrayList<String>();
        java.util.List<String> noChange = new java.util.ArrayList<String>();
        java.util.List<String> rejected = new java.util.ArrayList<String>();
        // Fix B (first-turn derail): a mutation that already reached the host THIS turn
        // (APPLIED or NO_CHANGE) is never executed again on an identical signature —
        // turn-local, gone after the close.
        java.util.Set<String> executedMutationSignatures = new java.util.HashSet<String>();
        String currentConcept = null;
        boolean refetchConcept = false;
        while (result != null && result.isOk()
                && result.getOutput() instanceof ScopingAssistantOutput) {
            ScopingAssistantOutput output = (ScopingAssistantOutput) result.getOutput();
            ConceptAction action = output.getConceptAction();
            String actionError = output.getConceptActionError();
            if (action == null && actionError == null) {
                if (output.isConceptActionExplicitNone()) {
                    // Observable: the model CHOSE none — distinguishable from an absent field.
                    trace.line("concept action: NONE");
                }
                // The scoping regression (Meerschweinchen gate): the visible answer SPELLED the
                // action ("add_cards: - Haltung … offer: …") while the action channel said none.
                // Parseable JSON, nothing executed, internal instructions shown to the user — never
                // a finished turn. Repaired like a rejected action (same error budget); without
                // budget the host states honestly that nothing was executed from that text.
                String leak = VisibleAssistantMessageValidator.actionLeakIn(output.getAssistantMessage());
                if (leak != null) {
                    String channel = output.isConceptActionExplicitNone() ? "none" : "absent";
                    rejected.add("(text only) " + leak + " — written into assistantMessage, NOT executed");
                    if (!budgetExhausted && repairs < maxRepairAttempts) {
                        repairs++;
                        trace.line("visible answer spells the action \"" + leak + "\" while conceptAction is "
                                + channel + " — NOT executed, repair turn (repairs=" + repairs + "/"
                                + maxRepairAttempts + ")");
                        if (intermediateSink != null) {
                            if (probedThisTurn && hasCommittableScopePatch(output)) {
                                // The post-probe sensor lock covers this replaced output too.
                                trace.line("post-probe sensor lock: intermediate scope patch suppressed");
                            } else {
                                intermediateSink.intermediate(output);
                            }
                        }
                        result = turn.run(withProbeMeaning(TeamAgentPlaybook.conceptReceipts(
                                conceptRevision, applied, noChange, rejected, currentConcept,
                                germanFeedback) + TeamAgentPlaybook.conceptActionWrittenAsText(leak,
                                germanFeedback), probeMeaningReminder), creatingTypeOf(leak));
                        continue;
                    }
                    trace.line("visible answer spells the action \"" + leak + "\" while conceptAction is "
                            + channel + " — no repair budget left, host answer replaces the text");
                    return syntheticAnswer(TeamAgentPlaybook.conceptActionLeakAnswer(germanFeedback,
                            addedCards, offeredThisTurn), result);
                }
                // GROUNDING (live-gate 2): a finished turn whose scopePatch failed validation
                // would leave the visible answer claiming a change the application refused
                // ("Das ist notiert" over a rejected excludeFacet). A broken patch is repaired
                // like a rejected tool call — same error-tolerance budget, one honest retry.
                ScopeUpdateDocument scopeUpdate = output.getScopeUpdate();
                if (scopeUpdate != null && !scopeUpdate.isValid() && !budgetExhausted
                        && repairs < maxRepairAttempts) {
                    repairs++;
                    trace.line("scope patch REJECTED (" + scopeUpdate.describeViolations()
                            + ") — repair turn (repairs=" + repairs + "/" + maxRepairAttempts + ")");
                    if (intermediateSink != null) {
                        // The invalid attempt still travels to the host — its rejection line is
                        // exactly the observability the technical_log gate asked for.
                        intermediateSink.intermediate(output);
                    }
                    result = turn.run(withProbeMeaning(TeamAgentPlaybook.scopePatchRejected(
                            scopeUpdate.describeViolations(), germanFeedback),
                            probeMeaningReminder));
                    continue;
                }
                // Meerschweinchen live gate: the first substantive turn offered searches and
                // built NO card although the user named the areas. ONE machinery nudge asks for
                // exactly the missing cards — only while the persisted concept is still empty,
                // never on a bare greeting, never in a restricted turn, never over budget.
                if (nudgeOfferWhenMissing && !cardsNudgeSpent && addedCards.isEmpty()
                        && mode == ConceptTurnPolicy.Mode.FULL && !budgetExhausted && !probedThisTurn
                        && isSubstantive(userText) && conceptIsEmpty(tool)) {
                    cardsNudgeSpent = true;
                    trace.line("concept cards missing (concept empty) — card nudge turn");
                    if (intermediateSink != null) {
                        intermediateSink.intermediate(output);
                    }
                    result = turn.run(TeamAgentPlaybook.conceptCardsMissing(germanFeedback), "add_cards");
                    continue;
                }
                // Gate 9b: the session's FIRST substantive card-building turn must not end
                // without exploration tags — ONE machinery nudge asks for exactly the offer.
                // Never spent twice, never on turns that built nothing, never over budget.
                if (nudgeOfferWhenMissing && !offerNudgeSpent && !offeredThisTurn
                        && !applied.isEmpty() && !budgetExhausted && !probedThisTurn) {
                    offerNudgeSpent = true;
                    trace.line("search tags missing — offer nudge turn");
                    if (intermediateSink != null) {
                        intermediateSink.intermediate(output);
                    }
                    result = turn.run(TeamAgentPlaybook.offerSearchesMissing(germanFeedback), "offer");
                    continue;
                }
                if (offerNudgeSpent && !offeredThisTurn && !suggestionGroundingSpent
                        && !budgetExhausted) {
                    // Offer-receipt grounding (the turn-1 finding): the nudge was sent and NO
                    // OFFERED receipt exists in this turn, yet the close once claimed "einige
                    // erste Suchvorschläge gemacht" over zero tags. The OBJECTIVE condition
                    // (nudge fired, receipt absent) replaces any keyword truth guard; with an
                    // OFFERED receipt this block never runs.
                    suggestionGroundingSpent = true;
                    trace.line("offer nudge unanswered — search-suggestion grounding turn");
                    if (intermediateSink != null) {
                        intermediateSink.intermediate(output);
                    }
                    result = turn.run(
                            TeamAgentPlaybook.searchSuggestionsGrounding(germanFeedback), "none");
                    continue;
                }
                // The model finished without a further action — the normal end.
                return closeProbeTurn(withMoveTruth(result, mode, movedReceipt,
                        !applied.isEmpty(), moveOutcome, germanFeedback, trace),
                        probeReceiptText, germanFeedback, trace);
            }
            if (budgetExhausted) {
                if (action != null && action.getType() == ConceptAction.Type.OFFER
                        && !offeredThisTurn && hasPlaceholder(action, userText, currentConcept)) {
                    // The contamination guard covers the over-budget offer as well: an
                    // instruction placeholder never becomes a clickable search tag.
                    trace.line("wrap-up offer REFUSED (placeholder copied from the instructions)");
                    return withoutActionLeak(closeProbeTurn(withMoveTruth(result, mode, movedReceipt,
                            !applied.isEmpty(), moveOutcome, germanFeedback, trace),
                            probeReceiptText, germanFeedback, trace), addedCards, offeredThisTurn,
                            germanFeedback, trace);
                }
                if (action != null && action.getType() == ConceptAction.Type.OFFER
                        && !offeredThisTurn) {
                    // Gate finding: the first broad turn regularly EXHAUSTS the budget building
                    // cards, so the wrap-up's offer landed exactly here and was dropped — tags
                    // are display state, not a concept edit; executing them over budget is safe
                    // and costs no further inference.
                    try {
                        tool.call(action);
                        trace.line("wrap-up offer executed (tags are display state, not a "
                                + "concept edit)");
                    } catch (ToolInvoker.ToolFailure rejectedOffer) {
                        trace.line("wrap-up offer REJECTED "
                                + compactReason(rejectedOffer.getMessage()));
                    } catch (ToolInvoker.EndpointUnavailable dead) {
                        trace.line("wrap-up offer lost — endpoint unavailable");
                    }
                    return withoutActionLeak(closeProbeTurn(withMoveTruth(result, mode, movedReceipt,
                            !applied.isEmpty(), moveOutcome, germanFeedback, trace),
                            probeReceiptText, germanFeedback, trace), addedCards, offeredThisTurn,
                            germanFeedback, trace);
                }
                trace.line("tool budget exhausted — dropping the further conceptAction");
                return withoutActionLeak(closeProbeTurn(withMoveTruth(result, mode, movedReceipt,
                        !applied.isEmpty(), moveOutcome, germanFeedback, trace),
                        probeReceiptText, germanFeedback, trace), addedCards, offeredThisTurn, germanFeedback, trace);
            }
            rounds++;
            String feedback;
            // The one action type the follow-up must answer with (null = free): a repair of a
            // malformed or placeholder add_cards/offer asks for exactly that action again.
            String requiredType = null;
            if (actionError != null) {
                // A malformed action never reaches the host; the reason goes straight back.
                repairs++;
                requiredType = creatingTypeOf(typeNamedIn(actionError));
                rejected.add("(invalid) " + firstLine(actionError));
                trace.line("round " + rounds + ": invalid conceptAction (" + actionError + ")");
                feedback = TeamAgentPlaybook.conceptToolRejected(actionError, germanFeedback);
            } else if (probedThisTurn && action.getType() != ConceptAction.Type.READ
                    && action.getType() != ConceptAction.Type.PROBE) {
                // The post-probe sensor lock: reads may still ground the summary, every
                // mutation and scope command is refused BEFORE the host — the probe's
                // observation must never become the model's own decision in the same turn.
                repairs++;
                rejected.add(action.describe() + " — refused (post-probe sensor lock)");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (post-probe sensor lock: "
                        + "observation-only turn)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        TeamAgentPlaybook.probeSensorLock(), germanFeedback);
            } else if (isMutatingAction(action.getType())
                    && executedMutationSignatures.contains(action.describe())) {
                // Fix B: the derailed first turn re-sent the SAME add_cards until round 4 —
                // an exact repetition of an executed mutation ends before the host.
                repairs++;
                rejected.add(action.describe() + " — already executed this turn");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (exact repetition of an "
                        + "executed mutation)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        "You already executed exactly this action in this turn — do not "
                                + "repeat it. Finish with your final answer, or perform a "
                                + "genuinely different REQUESTED action.", germanFeedback);
            } else if (hasPlaceholder(action, userText, currentConcept)) {
                // Prompt-contamination guard: the instructions show their examples with
                // <placeholders>; a placeholder copied into an action is never the user's topic
                // and never reaches the host.
                repairs++;
                requiredType = creatingTypeOf(action.getType().name().toLowerCase(java.util.Locale.ROOT));
                rejected.add(action.describe() + " — placeholder from the instructions");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (placeholder copied from the "
                        + "instructions)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        TeamAgentPlaybook.placeholderCopied(), germanFeedback);
            } else if (action.getType() == ConceptAction.Type.MOVE) {
                // The dedicated generator owns the model side of moves exclusively (gate
                // ruling: two competing paths produced invalid rounds and a false close) —
                // a legacy/misrouted in-loop move never reaches the host.
                repairs++;
                rejected.add(action.describe() + " — moves run through the dedicated path");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (the application executes moves "
                        + "itself on an explicit user order)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        "You never emit a move action: when the user orders a move, the "
                                + "application executes it itself and reports the receipt. "
                                + "Do not retry.", germanFeedback);
            } else if (action.getType() == ConceptAction.Type.ADD) {
                // add_cards slice: the single add left the active contract — a legacy
                // transcript's "add" is read but never executed (four one-by-one rounds once
                // cost a user-named card its place in the budget).
                repairs++;
                rejected.add(action.describe() + " — single add left the contract");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (send ONE add_cards with ALL "
                        + "names)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        "The single \"add\" action is not part of your contract anymore. Send "
                                + "ONE add_cards action carrying ALL card names as one list — "
                                + "a single card is a one-element list.", germanFeedback);
            } else if (readOnly
                    && (action.getType() == ConceptAction.Type.ADD_CARDS
                            || action.getType() == ConceptAction.Type.MOVE
                            || action.getType() == ConceptAction.Type.RENAME
                            || (mode == ConceptTurnPolicy.Mode.DELETE_READ_ONLY
                                    && action.getType() == ConceptAction.Type.EXCLUDE))) {
                // Gate 2 of the safety slice: in a read-only turn EVERY mutation is refused —
                // the model once replaced the withdrawn rewrite with partial adds and claimed
                // success; a delete wish must not silently become a scope exclusion either.
                repairs++;
                String refusal = mode == ConceptTurnPolicy.Mode.DELETE_READ_ONLY
                        ? TeamAgentPlaybook.deleteReadOnlyRefusal()
                        : TeamAgentPlaybook.restructureReadOnlyRefusal();
                rejected.add(action.describe() + " — refused (read-only turn)");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds + " -> REFUSED (read-only turn: no substitute "
                        + "edits)");
                feedback = TeamAgentPlaybook.conceptToolRejected(refusal, germanFeedback);
            } else if (action.getType() == ConceptAction.Type.REMOVE
                    || action.getType() == ConceptAction.Type.REWRITE) {
                // Safety slice after the slice-2 gate: destructive edits left the model contract
                // (a natural "ESP-IDF raus, Toolchain bleibt" once became a remove that vaporised
                // the branch). The parser stays tolerant for legacy transcripts, but the action
                // NEVER reaches the host — the model gets the honest alternative instead.
                repairs++;
                rejected.add(action.describe() + " — destructive edits left the contract");
                trace.line("round " + rounds + ": " + action.describe());
                trace.line("round " + rounds
                        + " -> REFUSED (destructive concept edits are user-owned)");
                feedback = TeamAgentPlaybook.conceptToolRejected(
                        TeamAgentPlaybook.destructiveEditRefusal(), germanFeedback);
            } else {
                trace.line("round " + rounds + ": " + action.describe());
                try {
                    String text = tool.call(action);
                    if (action.getType() == ConceptAction.Type.EXCLUDE
                            || action.getType() == ConceptAction.Type.RESOLVE) {
                        // TERMINAL (gate 5): one command, one effect, TURN OVER. The visible
                        // answer is the platform's deterministic receipt sentence — a further
                        // inference once contradicted the committed blacklist entry ("keine
                        // dauerhaften Änderungen" right after EXCLUDED was persisted).
                        trace.line("round " + rounds + " -> "
                                + (action.getType() == ConceptAction.Type.EXCLUDE
                                        ? "EXCLUDED" : "RESOLVED") + " (terminal)");
                        if (intermediateSink != null) {
                            // Whatever else the turn proposed (scopePatch, suggestions) is
                            // emitted before its output is replaced by the receipt answer.
                            intermediateSink.intermediate(output);
                        }
                        return receiptResult(text, result);
                    }
                    if (text != null && text.startsWith("NO_CHANGE")) {
                        // Fix A (generalizing the move special case — ONE truth for
                        // NO_CHANGE): the honest idempotent outcome is a receipt, never a
                        // mutation. It was once booked as APPLIED, so every wirkungslose
                        // repetition read as accomplished work.
                        if (action.getType() == ConceptAction.Type.MOVE) {
                            moveOutcome = "already-at-target"; // the truth guard's seed
                        }
                        conceptRevision = revisionIn(text, conceptRevision);
                        noChange.add(action.describe() + " — already unchanged");
                        executedMutationSignatures.add(action.describe());
                        refetchConcept = true; // prove to the model that NOTHING changed
                        trace.line("round " + rounds + " -> NO_CHANGE");
                        feedback = TeamAgentPlaybook.conceptToolResult(text, germanFeedback);
                    } else if (action.getType() == ConceptAction.Type.OFFER) {
                        // A working step like READ: the tags are display state, not the concept —
                        // no revision, no grounding re-read, the loop simply continues.
                        offeredThisTurn = true;
                        trace.line("round " + rounds + " -> OFFERED");
                        feedback = TeamAgentPlaybook.conceptToolResult(text, germanFeedback);
                    } else if (action.getType() == ConceptAction.Type.READ) {
                        trace.line("round " + rounds + " -> RESULT");
                        feedback = TeamAgentPlaybook.conceptToolResult(text, germanFeedback);
                    } else if (action.getType() == ConceptAction.Type.PROBE) {
                        // scope_probe (AP3): a read-only MEASUREMENT — a working step like
                        // READ, never a mutation receipt; the loop continues ONLY so the model
                        // can summarize or ask its one question — the sensor lock above
                        // refuses everything else for the rest of the turn.
                        probedThisTurn = true;
                        probeReceiptText = text;
                        int meaning = text == null ? -1
                                : text.indexOf("MEANING — hard rules");
                        probeMeaningReminder = meaning >= 0 ? text.substring(meaning)
                                : TeamAgentPlaybook.probeSensorLock();
                        trace.line("round " + rounds + " -> PROBED");
                        trace.line("post-probe sensor lock armed (observation-only for the "
                                + "rest of the turn)");
                        feedback = TeamAgentPlaybook.conceptToolResult(text, germanFeedback)
                                + "\n\n" + TeamAgentPlaybook.probeSensorLock();
                    } else {
                        conceptRevision = revisionIn(text, conceptRevision);
                        applied.add(action.describe() + " (revision " + conceptRevision + ")");
                        if (action.getType() == ConceptAction.Type.ADD_CARDS) {
                            addedCards.addAll(receiptNames(text, "ADDED"));
                        }
                        executedMutationSignatures.add(action.describe());
                        refetchConcept = true;
                        if (action.getType() == ConceptAction.Type.MOVE) {
                            movedReceipt = true; // ONLY an APPLIED move licenses "verschoben"
                        }
                        trace.line("round " + rounds + " -> APPLIED revision=" + conceptRevision);
                        feedback = TeamAgentPlaybook.conceptToolApplied(text, germanFeedback);
                    }
                } catch (ToolInvoker.ToolFailure toolRejected) {
                    repairs++;
                    if (action.getType() == ConceptAction.Type.MOVE) {
                        moveOutcome = firstLine(toolRejected.getMessage());
                    }
                    // The WHOLE reason, flattened (slice-2 gate finding: the log showed only
                    // "Error: BRANCH_GRAFT_FAILED" while the teaching bottom-up diagnostic
                    // reached the model alone — the observer must see the same truth).
                    String reason = compactReason(toolRejected.getMessage());
                    rejected.add(action.describe() + " — " + reason);
                    refetchConcept = true; // prove to the model that NOTHING changed
                    trace.line("round " + rounds + " -> REJECTED " + reason);
                    feedback = TeamAgentPlaybook.conceptToolRejected(toolRejected.getMessage(), germanFeedback);
                } catch (ToolInvoker.EndpointUnavailable dead) {
                    // Infrastructure, not the model's fault: keep the last good result, no retry.
                    trace.line("concept endpoint unavailable — keeping the last answer ("
                            + firstLine(dead.getMessage()) + ")");
                    return result;
                }
            }
            if (refetchConcept) {
                // Ground the receipts in the PERSISTED state (best effort — a failed fetch
                // simply omits the block, it never breaks the loop).
                try {
                    currentConcept = tool.call(readAll());
                    refetchConcept = false;
                } catch (ToolInvoker.ToolFailure unavailable) {
                    currentConcept = null;
                } catch (ToolInvoker.EndpointUnavailable unavailable) {
                    currentConcept = null;
                }
            }
            ScopeUpdateDocument roundScope = output.getScopeUpdate();
            if (roundScope != null && !roundScope.isValid()) {
                // The round continues anyway (concept feedback) — the scope rejection rides the
                // SAME feedback for free instead of costing an extra repair inference.
                trace.line("scope patch REJECTED (" + roundScope.describeViolations()
                        + ") — noted in round feedback");
                feedback = feedback + "\n\n" + TeamAgentPlaybook.scopePatchRejected(
                        roundScope.describeViolations(), germanFeedback);
            }
            budgetExhausted = rounds >= maxToolRounds || repairs > maxRepairAttempts;
            if (budgetExhausted) {
                trace.line("budget reached (rounds=" + rounds + "/" + maxToolRounds
                        + " repairs=" + repairs + "/" + maxRepairAttempts + ") — wrap-up turn");
                feedback = feedback + "\n\n" + TeamAgentPlaybook.conceptToolBudgetExhausted(germanFeedback);
                if (nudgeOfferWhenMissing && !offerNudgeSpent && !offeredThisTurn
                        && !applied.isEmpty() && !probedThisTurn) {
                    // Gate finding: broad first turns exhaust the budget on cards, so the
                    // normal-end nudge never ran and the first-turn offer stayed red — the
                    // wrap-up turn asks for the offer too (executed above despite the budget).
                    offerNudgeSpent = true;
                    requiredType = "offer";
                    trace.line("search tags missing — offer nudge in wrap-up");
                    feedback = feedback + "\n\n"
                            + TeamAgentPlaybook.offerSearchesMissing(germanFeedback);
                }
            }
            if (intermediateSink != null) {
                // This output is about to be REPLACED by the follow-up inference — whatever it
                // proposed beyond the concept action (scopePatch!) must not vanish with it.
                // EXCEPT after a probe: the sensor lock covers EVERY scope mutation, and the
                // host applies valid intermediate patches — a post-probe patch is suppressed.
                if (probedThisTurn && hasCommittableScopePatch(output)) {
                    trace.line("post-probe sensor lock: intermediate scope patch suppressed");
                } else {
                    intermediateSink.intermediate(output);
                }
            }
            if (requiredType != null) {
                trace.line("follow-up grammar admits only " + requiredType);
            }
            result = turn.run(withProbeMeaning(TeamAgentPlaybook.conceptReceipts(
                    conceptRevision, applied, noChange, rejected, currentConcept,
                    germanFeedback) + feedback, probeMeaningReminder), requiredType);
        }
        return result;
    }

    /** The creating action ("add_cards", "offer") a leak or rejection names; any other type stays free. */
    static String creatingTypeOf(String actionName) {
        if (actionName == null) {
            return null;
        }
        String lower = actionName.toLowerCase(java.util.Locale.ROOT);
        if (lower.equals("add_cards")) {
            return "add_cards";
        }
        return lower.equals("offer") || lower.equals("searchsuggestions") ? "offer" : null;
    }

    /** The action type a parser diagnostic names ({@code conceptAction type "offer" requires …}). */
    private static String typeNamedIn(String actionError) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("type \"([a-z_]+)\"").matcher(actionError);
        return matcher.find() ? matcher.group(1) : null;
    }

    /**
     * The final result of a terminal EXCLUDE/RESOLVE: a synthetic output whose visible message
     * is the receipt's {@code userMessage} (host-authored, session language) — never model
     * prose. When the receipt carries no such field (older host), the previous result stands
     * unchanged rather than showing raw JSON to the user.
     */
    private static TeamAgentResult receiptResult(String receiptJson, TeamAgentResult fallback) {
        String userMessage = null;
        try {
            Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(receiptJson);
            if (parsed instanceof java.util.Map) {
                Object message = ((java.util.Map<?, ?>) parsed).get("userMessage");
                if (message instanceof String && !((String) message).trim().isEmpty()) {
                    userMessage = ((String) message).trim();
                }
            }
        } catch (RuntimeException notJson) {
            userMessage = null;
        }
        if (userMessage == null) {
            return fallback;
        }
        return syntheticAnswer(userMessage, fallback);
    }

    /**
     * A host-authored visible answer for a TERMINAL turn (the dedicated move path): the exact
     * mechanics every deterministic receipt answer uses. {@code fallback} may be null when the
     * message is host-built plain text (the synthetic parse cannot fail on it).
     */
    public static TeamAgentResult hostAnswer(String userMessage, TeamAgentResult fallback) {
        return syntheticAnswer(userMessage, fallback);
    }

    /** A host-authored visible answer as a regular parsed output (shared receipt mechanics). */
    private static TeamAgentResult syntheticAnswer(String userMessage, TeamAgentResult fallback) {
        StringBuilder json = new StringBuilder("{\"assistantMessage\":\"");
        for (int index = 0; index < userMessage.length(); index++) {
            char character = userMessage.charAt(index);
            if (character == '"' || character == '\\') {
                json.append('\\').append(character);
            } else if (character == '\n') {
                json.append("\\n");
            } else {
                json.append(character);
            }
        }
        json.append("\"}");
        ScopingAssistantOutputParser.Result synthetic =
                ScopingAssistantOutputParser.parse(json.toString());
        return synthetic.isOk() ? TeamAgentResult.ok(synthetic.getOutput(), null) : fallback;
    }

    /** True when any value the action would hand to the host is a placeholder, not a real name. */
    static boolean hasPlaceholder(ConceptAction action) {
        return hasPlaceholder(action, null, null);
    }

    /**
     * @param userText       the user's message (a literal name there is real, never a placeholder)
     * @param currentConcept the persisted concept as last read (an existing card is real)
     */
    static boolean hasPlaceholder(ConceptAction action, String userText, String currentConcept) {
        for (String value : valuesOf(action)) {
            if (INSTRUCTION_PLACEHOLDER.matcher(value).find()) {
                return true;
            }
            if (WHOLE_PLACEHOLDER.matcher(value).matches()) {
                String literal = value.trim();
                boolean real = (userText != null && userText.contains(literal))
                        || (currentConcept != null && currentConcept.contains(literal));
                if (!real) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every name, path segment, topic and query an action carries. */
    @SuppressWarnings("unchecked")
    private static java.util.List<String> valuesOf(ConceptAction action) {
        java.util.List<String> values = new java.util.ArrayList<String>();
        values.addAll(action.getPath());
        values.addAll(action.getParent());
        if (action.getName() != null && action.getType() != ConceptAction.Type.OFFER) {
            values.add(action.getName());
        }
        if (action.getType() == ConceptAction.Type.EXCLUDE && action.getTopic() != null) {
            values.add(action.getTopic());
        }
        String json = action.getType() == ConceptAction.Type.ADD_CARDS ? action.getNamesJson()
                : action.getType() == ConceptAction.Type.OFFER ? action.getSuggestionsJson()
                : action.getType() == ConceptAction.Type.PROBE ? action.getTermsJson() : null;
        if (json != null) {
            try {
                Object parsed = com.aresstack.askai.agent.model.reranker.MiniJson.parse(json);
                if (parsed instanceof java.util.List) {
                    for (Object element : (java.util.List<Object>) parsed) {
                        if (element instanceof java.util.Map) {
                            Object query = ((java.util.Map<String, Object>) element).get("query");
                            if (query != null) {
                                values.add(String.valueOf(query));
                            }
                        } else if (element != null) {
                            values.add(String.valueOf(element));
                        }
                    }
                }
            } catch (RuntimeException notJson) {
                values.add(json);
            }
        }
        return values;
    }

    /** The names of one receipt category ("ADDED: Haltung" lines) in receipt order. */
    static java.util.List<String> receiptNames(String receipt, String category) {
        java.util.List<String> names = new java.util.ArrayList<String>();
        if (receipt != null) {
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("(?m)^" + category + ": (.+)$").matcher(receipt);
            while (matcher.find()) {
                names.add(matcher.group(1).trim());
            }
        }
        return names;
    }

    /** A user message worth building cards from: not empty, not a bare greeting. */
    private static boolean isSubstantive(String userText) {
        if (userText == null) {
            return false;
        }
        String text = userText.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[!.?,]", "").trim();
        return text.length() >= 3 && !text.matches(
                "(hallo|hi|hey|moin|servus|hello|guten (morgen|tag|abend)|danke|ok|okay|ja|nein)( .{0,12})?");
    }

    /** The persisted concept has no card yet (a failed read counts as "not known empty"). */
    private static boolean conceptIsEmpty(ConceptTool tool) {
        try {
            String text = tool.call(readAll());
            if (text == null) {
                return false;
            }
            String flat = text.replaceAll("\\s+", "");
            return flat.contains("\"concept\":[]") || flat.contains("\"concept\":[{}]");
        } catch (ToolInvoker.ToolFailure failed) {
            return false;
        } catch (ToolInvoker.EndpointUnavailable dead) {
            return false;
        }
    }

    /**
     * The last line of defence for a turn that closes without a further inference (budget spent): a visible
     * answer that spells an action as text is replaced by the honest host answer instead of reaching the user.
     */
    private static TeamAgentResult withoutActionLeak(TeamAgentResult result, java.util.List<String> addedCards,
                                                     boolean offered, boolean german, Trace trace) {
        if (result == null || !result.isOk() || !(result.getOutput() instanceof ScopingAssistantOutput)) {
            return result;
        }
        String leak = VisibleAssistantMessageValidator.actionLeakIn(
                ((ScopingAssistantOutput) result.getOutput()).getAssistantMessage());
        if (leak == null) {
            return result;
        }
        trace.line("visible answer spells the action \"" + leak + "\" at the budget close — host answer "
                + "replaces the text");
        return syntheticAnswer(TeamAgentPlaybook.conceptActionLeakAnswer(german, addedCards, offered), result);
    }

    /** The whole-concept read used to ground the receipts in the persisted state. */
    private static ConceptAction readAll() {
        ConceptAction.Parsed parsed = ConceptAction.parse(
                java.util.Collections.singletonMap("type", (Object) "read"));
        return parsed.getAction();
    }

    /** The {@code revision=N} the host reports on an applied call, or the previous value. */
    private static long revisionIn(String toolText, long fallback) {
        if (toolText != null) {
            java.util.regex.Matcher matcher =
                    java.util.regex.Pattern.compile("revision=(\\d+)").matcher(toolText);
            if (matcher.find()) {
                try {
                    return Long.parseLong(matcher.group(1));
                } catch (NumberFormatException overflow) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    /**
     * The move-truth close: a MOVE_TRUTH turn without an APPLIED move receipt never keeps the
     * model's narration — the host states deterministically that nothing changed (optionally
     * why). Grounded in the same synthetic-answer mechanics as every host receipt.
     */
    private static TeamAgentResult withMoveTruth(TeamAgentResult result,
                                                 ConceptTurnPolicy.Mode mode,
                                                 boolean movedReceipt, boolean appliedAny,
                                                 String moveOutcome,
                                                 boolean german, Trace trace) {
        if (mode == ConceptTurnPolicy.Mode.EXCLUDE_TRUTH) {
            // The connector gate's finding: a rejected scopePatch detour ('exclude' as an
            // operation kind) closed with "the card is excluded" over an unchanged scope.
            // A COMMITTED exclusion never reaches this close — the EXCLUDE action returns
            // terminally with the host's receipt answer — so reaching it means the ordered
            // exclusion did not happen, whatever the narration claims.
            trace.line("exclude-truth guard -> deterministic host answer (no EXCLUDED "
                    + "receipt this turn)");
            return syntheticAnswer(
                    TeamAgentPlaybook.excludeTruthAnswer(german, appliedAny), result);
        }
        if (mode != ConceptTurnPolicy.Mode.MOVE_TRUTH || movedReceipt) {
            return result;
        }
        if (appliedAny) {
            // The gate's negation finding, generalized: NEVER state 'nothing changed' over ANY
            // authoritative APPLIED receipt of this turn — the receipts feedback already
            // grounds the model's own narration for what DID happen.
            trace.line("move-truth guard stands down — other mutations were APPLIED this turn");
            return result;
        }
        trace.line("move-truth guard -> deterministic host answer (no MOVED receipt this "
                + "turn)");
        return syntheticAnswer(TeamAgentPlaybook.moveTruthAnswer(german, moveOutcome), result);
    }

    /**
     * Whether a successful tool call changed the concept, for the action receipt of the turn's FINAL history
     * entry (plan D7): READ, PROBE and OFFER are working steps, and a NO_CHANGE receipt changed nothing.
     */
    public static boolean isAppliedOutcome(ConceptAction action, String receipt) {
        if (action == null) {
            return false;
        }
        ConceptAction.Type type = action.getType();
        if (type == ConceptAction.Type.READ || type == ConceptAction.Type.PROBE
                || type == ConceptAction.Type.OFFER) {
            return false;
        }
        return receipt == null || !receipt.startsWith("NO_CHANGE");
    }

    /** The guard's scope (ratified): mutating actions only — READ/PROBE/OFFER repeat freely. */
    private static boolean isMutatingAction(ConceptAction.Type type) {
        return type == ConceptAction.Type.ADD_CARDS || type == ConceptAction.Type.RENAME
                || type == ConceptAction.Type.MOVE || type == ConceptAction.Type.EXCLUDE
                || type == ConceptAction.Type.RESOLVE;
    }

    /** A valid scope patch with real operations — the kind the host would commit. */
    private static boolean hasCommittableScopePatch(ScopingAssistantOutput output) {
        ScopeUpdateDocument patch = output.getScopeUpdate();
        return patch != null && patch.isValid() && !"[]".equals(patch.operationsJson());
    }

    /**
     * The deterministic probe close (AP3 retest 3): the ONE follow-up question no longer
     * depends on the small model's obedience — the host derives it from the receipt's
     * readings (BOUNDARY outranks NOVEL; clear-only asks nothing) and appends it to the
     * summary. A final output still carrying a committable scope patch is stripped here:
     * the sensor lock covers the WHOLE turn, patches included.
     */
    private static TeamAgentResult closeProbeTurn(TeamAgentResult result, String receipt,
                                                  boolean german, Trace trace) {
        if (receipt == null || result == null || !result.isOk()
                || !(result.getOutput() instanceof ScopingAssistantOutput)) {
            return result;
        }
        ScopingAssistantOutput output = (ScopingAssistantOutput) result.getOutput();
        java.util.List<String> boundary = probeTermsWithHint(receipt, "BOUNDARY");
        java.util.List<String> novel = probeTermsWithHint(receipt, "NOVEL");
        String question = TeamAgentPlaybook.probeFollowUpQuestion(german, boundary, novel);
        boolean dropPatch = hasCommittableScopePatch(output);
        if (question == null && !dropPatch) {
            return result;
        }
        if (question != null) {
            trace.line("post-probe host question appended ("
                    + (!boundary.isEmpty() ? "BOUNDARY" : "NOVEL membership") + ")");
        }
        if (dropPatch) {
            trace.line("post-probe sensor lock: final scope patch dropped");
        }
        return syntheticAnswer(output.getAssistantMessage()
                + (question == null ? "" : "\n\n" + question), result);
    }

    /** The receipt's terms carrying one hint, in reading order ('"term" -> HINT'). */
    private static java.util.List<String> probeTermsWithHint(String receipt, String hint) {
        java.util.List<String> terms = new java.util.ArrayList<String>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\"([^\"]+)\" -> " + hint).matcher(receipt);
        while (matcher.find()) {
            terms.add(matcher.group(1));
        }
        return terms;
    }

    /**
     * The sticky probe directive: every later feedback of the SAME turn re-carries the
     * receipt's MEANING rules, so the final narration still sees them after reads — a
     * feedback that already contains them (the probe round itself, a fresh re-probe)
     * travels unchanged.
     */
    private static String withProbeMeaning(String feedback, String meaning) {
        if (meaning == null || feedback.contains("MEANING — hard rules")) {
            return feedback;
        }
        return feedback + "\n\nSTILL BINDING — this turn's probe:\n" + meaning;
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        int newline = text.indexOf('\n');
        return newline < 0 ? text : text.substring(0, newline);
    }

    /** A rejection reason as ONE readable log line: newlines flattened, length capped. */
    private static String compactReason(String text) {
        if (text == null) {
            return "";
        }
        String flat = text.replace("\r", "").replace('\n', ' ').trim();
        return flat.length() <= 220 ? flat : flat.substring(0, 217) + "...";
    }
}
