package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.List;

/**
 * The per-session model-backed research conversation engine. It owns ONE session's conversation state — the
 * system prompt, the running user/assistant history and the scope so far — and turns each user message into a
 * validated {@link TeamAgentTurn} by calling the configured main model ({@link MainModelChat}).
 *
 * <p>It is deliberately transport-agnostic and transport-free so the whole conversation can be driven in a unit
 * test with a scripted fake model. The host's two nested state patterns stay the authority in two ways:</p>
 * <ul>
 *   <li><b>Commands.</b> The engine only PROPOSES a command and pre-validates that proposal against the live
 *       allowed set ({@link TeamAgentStateView}). An out-of-set command triggers ONE bounded repair; if the
 *       model insists, the turn is a {@link TeamAgentResult.Status#COMMAND_REJECTED} whose misleading message
 *       is withheld — never run, never invented, never falsely claimed.</li>
 *   <li><b>Retries.</b> A user message is held as a single pending turn and only committed to history once the
 *       model answers usably, so {@link #retryPendingTurn(TeamAgentStateView)} after a failure never
 *       duplicates the user's message.</li>
 * </ul>
 *
 * <p>Model or transport failures never fabricate a turn; they surface as
 * {@link TeamAgentResult.Status#MODEL_UNAVAILABLE} or {@link TeamAgentResult.Status#UNUSABLE_ANSWER}.</p>
 *
 * <p>History (plan PR 17, D7): every stored entry carries its phase and kind. A prompt carries only the newest
 * own-phase {@code USER}/{@code FINAL} entries within the role's window budget ({@link HistoryWindow}); the tool
 * rounds of a turn collapse into ONE {@code FINAL} entry, which {@link #completeTurn} finally sets to the visible
 * answer plus a one-line action receipt. Before every call an overflow guard checks prompt + answer budget +
 * {@value #CONTEXT_MARGIN_TOKENS} tokens against the model's context: it shrinks the window, and when even an
 * empty window does not fit, the turn fails as {@link TeamAgentResult.Status#CONTEXT_TOO_LARGE} and nothing is
 * sent.</p>
 *
 * <p>Access is serialised per session: every public method locks the agent, and {@link #exclusively} lets the
 * session hold the lock across a chat turn and its tool rounds, so a review turn never interleaves with them.</p>
 */
public final class ResearchTeamAgent {

    /** A calm, deterministic temperature for a consultative planner (varied queries come from the prompt). */
    private static final double TEMPERATURE = 0.4;
    /**
     * The answer budgets per call, taken from the ROLE of the call (plan 4.1 rule 6, table 4.2): a Concept chat
     * turn including its tool rounds gets 4,096; a chat turn of every later phase 1,536; the post-search review
     * 4,096 in Concept (summary and suggestions) and 3,072 in Sources (summary only), both provisional until the
     * measurement PR. Roles on one slot never share one budget. A JSON answer cut off at its budget fails loud.
     */
    public static final int CONCEPT_TURN_BUDGET = 4096;
    public static final int PHASE_TURN_BUDGET = 1536;
    public static final int CONCEPT_REVIEW_BUDGET = 4096;
    public static final int SOURCES_REVIEW_BUDGET = 3072;

    /** The answer budget of a chat turn (and its tool rounds) in a phase. */
    public static int turnBudget(String phaseId) {
        return isConcept(phaseId) ? CONCEPT_TURN_BUDGET : PHASE_TURN_BUDGET;
    }

    /** The answer budget of the post-search review in a phase. */
    public static int reviewBudget(String phaseId) {
        return isConcept(phaseId) ? CONCEPT_REVIEW_BUDGET : SOURCES_REVIEW_BUDGET;
    }

    private static boolean isConcept(String phaseId) {
        return PhaseKeys.isConcept(phaseId);
    }

    /** Safety margin between prompt + answer budget and the model's context (plan 4.1 rule 2). */
    public static final int CONTEXT_MARGIN_TOKENS = 1024;

    private final PhaseChats chats;
    private final PhaseAssistantProfileRegistry profiles;
    private final PhaseContextAssembler contextAssembler;
    private final List<ChatMessage> history = new ArrayList<ChatMessage>();

    private boolean greeted;
    private TeamAgentResult greetingResult;

    /** The user's message for the current turn, held until the model answers usably (retry-safe). */
    private ChatMessage pendingUserTurn;

    /** Index in {@link #history} of the open turn's FINAL entry, which a tool round supersedes; -1 = none. */
    private int turnFinalIndex = -1;

    /** Index of the open chat turn's USER entry: pinned outside the shrinkable window in its tool rounds. */
    private int turnUserIndex = -1;

    public ResearchTeamAgent(MainModelChat model) {
        this(model, PhaseAssistantProfileRegistry.defaults(), new PhaseContextAssembler());
    }

    /** Inject a custom profile registry/assembler — mainly to prove per-phase context selection in tests. */
    public ResearchTeamAgent(MainModelChat model, PhaseAssistantProfileRegistry profiles,
                             PhaseContextAssembler contextAssembler) {
        this(PhaseChats.fixed(requireModel(model)), profiles, contextAssembler);
    }

    /**
     * The productive agent: every call runs on the chat of the LIVE phase ({@code state.getPhaseId()}), so a
     * turn in phase X uses slot {@code phase.X} (plan 5.1), whatever profile the registry falls back to.
     */
    public ResearchTeamAgent(PhaseChats chats, PhaseAssistantProfileRegistry profiles,
                             PhaseContextAssembler contextAssembler) {
        if (chats == null) {
            throw new IllegalArgumentException("chats must not be null");
        }
        if (profiles == null || contextAssembler == null) {
            throw new IllegalArgumentException("profiles and contextAssembler must not be null");
        }
        this.chats = chats;
        this.profiles = profiles;
        this.contextAssembler = contextAssembler;
    }

    private static MainModelChat requireModel(MainModelChat model) {
        if (model == null) {
            throw new IllegalArgumentException("model must not be null");
        }
        return model;
    }

    /** The model label of the Concept chat (the phase the session opens in), for logs. */
    public String modelName() {
        return chats.chatFor(PhaseAssistantProfileRegistry.SCOPING_PHASE_ID).modelName();
    }

    /** The chat of a live phase (the concept tool rounds run on it). */
    public MainModelChat chatFor(String phaseId) {
        return chats.chatFor(phaseId == null ? "" : phaseId);
    }

    /**
     * Run {@code work} while holding this session's history lock: the chat turn and its concept tool rounds
     * run as one unit, so a review turn (or another user turn) waits instead of interleaving its entries.
     */
    public <T> T exclusively(java.util.function.Supplier<T> work) {
        synchronized (this) {
            return work.get();
        }
    }

    /**
     * Close the open chat turn (plan D7): its FINAL entry becomes the answer the user actually saw (a host
     * receipt answer can replace the model's) plus a one-line receipt of the applied actions. No-op when no
     * turn is open or the turn failed.
     */
    public synchronized void completeTurn(TeamAgentResult visible, String receipt) {
        if (turnFinalIndex < 0) {
            return;
        }
        int index = turnFinalIndex;
        turnFinalIndex = -1;
        turnUserIndex = -1;
        if (visible == null || !visible.isOk() || index >= history.size()) {
            return;
        }
        ChatMessage open = history.get(index);
        String line = receipt == null ? "" : receipt.replace('\r', ' ').replace('\n', ' ').trim();
        String content = visible.getOutput().canonicalJson() + (line.isEmpty() ? "" : "\n[applied: " + line + "]");
        history.set(index, ChatMessage.assistant(content).tagged(open.getPhase(), ChatMessage.Kind.FINAL));
    }

    /** A copy of the stored history with phase and kind tags (diagnostics and tests). */
    public synchronized List<ChatMessage> historySnapshot() {
        return new ArrayList<ChatMessage>(history);
    }

    public synchronized boolean hasGreeted() {
        return greeted;
    }

    public synchronized boolean hasPendingTurn() {
        return pendingUserTurn != null;
    }

    /**
     * Produce the opening greeting + first scoping question from the model. Truly idempotent within a session:
     * once a greeting has succeeded it is cached and returned as-is, so a second call neither re-asks the model
     * nor writes a second assistant turn. A greeting that FAILED (MODEL_UNAVAILABLE / UNUSABLE_ANSWER) leaves
     * {@link #hasGreeted()} false, so it can be retried.
     */
    public synchronized TeamAgentResult greet(TeamAgentStateView state) {
        if (greeted) {
            return greetingResult;
        }
        // The greeting is a phase-agnostic bootstrap: there is no topic yet, so no scoping brief can exist.
        // It therefore uses the neutral fallback profile (generic prompt + generic contract), never the
        // scoping business contract that would demand a research brief.
        PhaseAssistantProfile greeting = profiles.fallback();
        int budget = turnBudget(state.getPhaseId());
        Prompt prompt = fit(greeting, state, HistoryWindow.chatBudget(state.getPhaseId()), budget,
                PromptBlockMeter.GREETING_INSTRUCTION, ChatMessage.user(
                        TeamAgentPlaybook.greetingInstruction(contextAssembler.workingLanguageDisplayName())),
                false);
        if (prompt.failure != null) {
            return prompt.failure;
        }
        TeamAgentResult result = runTurn(prompt.messages, prompt.meter, state, greeting.getOutputContract(), null,
                budget);
        if (result.isOk()) {
            greeted = true;
            greetingResult = result;
            turnFinalIndex = recordFinal(state, result.getOutput());
        }
        return result;
    }

    /**
     * Advance the conversation with the user's message. The message becomes the single pending turn and is only
     * committed to history once the model answers usably; a successful answer also folds any scope update into
     * the PROPOSED scope (never the confirmed one).
     */
    public synchronized TeamAgentResult respond(String userText, TeamAgentStateView state) {
        pendingUserTurn = ChatMessage.user(userText == null ? "" : userText.trim());
        return runUserTurn(state);
    }

    /**
     * A turn the user triggered by ACTING, not by writing — pressing "review the new sources". It runs the
     * same model and the same output contract, and it reads the real conversation as its context, but the
     * instruction behind it is machinery: it is not appended to the history as something the user said.
     * <p>
     * It used to go through {@link #respond}, which turns its argument into a user message. On success the
     * internal wording ended up in the conversation as if the user had typed it, and every later turn was
     * assembled on top of that fiction.
     */
    public synchronized TeamAgentResult internalTurn(String instruction, TeamAgentStateView state) {
        return internalTurn(instruction, state, null);
    }

    /**
     * An internal turn that must answer with ONE action type (a nudge or repair asking for exactly that
     * action): the generation-time schema admits only that variant. {@code null} = an ordinary internal turn.
     */
    public synchronized TeamAgentResult internalTurn(String instruction, TeamAgentStateView state, String requiredActionType) {
        TeamAgentResult result = internalTurn(instruction, state, requiredActionType, turnBudget(state.getPhaseId()),
                HistoryWindow.chatBudget(state.getPhaseId()));
        if (result.isOk()) {
            // A tool round of the open turn: its answer supersedes the turn's FINAL, the earlier answer stays
            // stored as a TOOL_ROUND and leaves every later window (D7). Only the instruction stays out.
            if (turnFinalIndex >= 0 && turnFinalIndex < history.size()) {
                ChatMessage previous = history.get(turnFinalIndex);
                history.set(turnFinalIndex, previous.tagged(previous.getPhase(), ChatMessage.Kind.TOOL_ROUND));
            }
            turnFinalIndex = recordFinal(state, result.getOutput());
        }
        return result;
    }

    /** The post-search review: an internal turn with the review's own answer budget and window (plan 4.2). */
    public synchronized TeamAgentResult reviewTurn(String instruction, TeamAgentStateView state) {
        TeamAgentResult result = internalTurn(instruction, state, null, reviewBudget(state.getPhaseId()),
                HistoryWindow.REVIEW_WINDOW_TOKENS);
        if (result.isOk()) {
            // The review is its own turn: its answer is real conversation, never a tool round of a chat turn.
            turnFinalIndex = -1;
            turnUserIndex = -1;
            recordFinal(state, result.getOutput());
        }
        return result;
    }

    private TeamAgentResult internalTurn(String instruction, TeamAgentStateView state, String requiredActionType,
                                         int budget, int windowTokens) {
        PhaseAssistantProfile profile = profiles.forPhase(state.getPhaseId());
        Prompt prompt = fit(profile, state, windowTokens, budget, PromptBlockMeter.TOOL_FEEDBACK,
                ChatMessage.user(instruction == null ? "" : instruction.trim()), turnUserIndex >= 0);
        if (prompt.failure != null) {
            return prompt.failure;
        }
        return runTurn(prompt.messages, prompt.meter, state, profile.getOutputContract(), requiredActionType,
                budget);
    }

    /**
     * Re-run the pending user turn after a failure (MODEL_UNAVAILABLE / UNUSABLE_ANSWER / COMMAND_REJECTED)
     * without re-appending the user's message. Requires a pending turn — an OK turn clears it.
     */
    public synchronized TeamAgentResult retryPendingTurn(TeamAgentStateView state) {
        if (pendingUserTurn == null) {
            throw new IllegalStateException("no pending user turn to retry");
        }
        return runUserTurn(state);
    }

    // ------------------------------------------------------------------ internals

    private TeamAgentResult runUserTurn(TeamAgentStateView state) {
        PhaseAssistantProfile profile = profiles.forPhase(state.getPhaseId());
        int budget = turnBudget(state.getPhaseId());
        turnFinalIndex = -1;
        turnUserIndex = -1;
        // The last user message is never dropped: it is the tail, outside the window the guard may shrink.
        Prompt prompt = fit(profile, state, HistoryWindow.chatBudget(state.getPhaseId()), budget,
                PromptBlockMeter.USER_MESSAGE, pendingUserTurn, false);
        if (prompt.failure != null) {
            return prompt.failure;
        }
        TeamAgentResult result = runTurn(prompt.messages, prompt.meter, state, profile.getOutputContract(), null,
                budget);
        if (result.isOk()) {
            history.add(pendingUserTurn.tagged(PhaseKeys.normalize(state.getPhaseId()), ChatMessage.Kind.USER));
            turnUserIndex = history.size() - 1;
            pendingUserTurn = null;
            turnFinalIndex = recordFinal(state, result.getOutput());
        }
        return result;
    }

    /**
     * The per-turn model context, assembled from the ACTIVE phase's assistant profile (the phase is the host's,
     * read from {@code state.getPhaseId()}), plus the turn's tail message — and fitted into the model's context
     * (plan 4.1 rule 2): prompt + answer budget + margin must fit {@code num_ctx}. The guard shrinks the history
     * window first; when even an empty window does not fit, the call fails visibly and nothing is sent.
     */
    private Prompt fit(PhaseAssistantProfile profile, TeamAgentStateView state, int windowTokens, int budget,
                       String tailBlock, ChatMessage tail, boolean pinOpenTurn) {
        MainModelChat model = chatFor(state.getPhaseId());
        // In a tool round the open turn's user message (and its latest answer) are pinned like the tail: the
        // window and the guard only ever shrink the conversation BEFORE the turn.
        List<ChatMessage> windowSource = history;
        List<ChatMessage> pinned = new ArrayList<ChatMessage>();
        if (pinOpenTurn && turnUserIndex >= 0 && turnUserIndex < history.size()) {
            windowSource = history.subList(0, turnUserIndex);
            pinned.add(history.get(turnUserIndex));
            if (turnFinalIndex > turnUserIndex && turnFinalIndex < history.size()) {
                pinned.add(history.get(turnFinalIndex));
            }
        }
        int context = model.contextTokens();
        int window = windowTokens;
        while (true) {
            PromptBlockMeter meter = new PromptBlockMeter();
            List<ChatMessage> messages = contextAssembler.assemble(profile, state, windowSource, window, meter);
            for (ChatMessage entry : pinned) {
                append(messages, meter, PromptBlockMeter.OPEN_TURN, entry);
            }
            append(messages, meter, tailBlock, tail);
            if (context <= 0) {
                return Prompt.ok(messages, meter);
            }
            int prompt = meter.estimatedTokens();
            int over = prompt + budget + CONTEXT_MARGIN_TOKENS - context;
            if (over <= 0) {
                return Prompt.ok(messages, meter);
            }
            Integer historyChars = meter.blocks().get(PromptBlockMeter.HISTORY);
            int historyTokens = historyChars == null ? 0
                    : (historyChars + PromptBlockMeter.CHARS_PER_TOKEN - 1) / PromptBlockMeter.CHARS_PER_TOKEN;
            if (historyTokens == 0) {
                String detail = "Context too large for " + model.modelName() + ": prompt about " + prompt
                        + " tokens + answer " + budget + " + margin " + CONTEXT_MARGIN_TOKENS + " exceed num_ctx "
                        + context;
                System.err.println("[team-agent] " + detail + " | blocks " + meter.render());
                return Prompt.fail(TeamAgentResult.contextTooLarge(detail));
            }
            window = Math.max(0, historyTokens - over);
        }
    }

    private static void append(List<ChatMessage> messages, PromptBlockMeter meter, String block,
                               ChatMessage message) {
        messages.add(message);
        meter.add(block, message);
    }

    /**
     * The full turn pipeline for the ACTIVE phase's contract: get a parseable, validated output (with one
     * bounded parse repair). A non-OK model call is MODEL_UNAVAILABLE; an answer that stays unparseable — or a
     * repaired answer that leaks meta-talk — after one repair is UNUSABLE_ANSWER. None of these fabricate or
     * surface a misleading turn.
     */
    private TeamAgentResult runTurn(List<ChatMessage> messages, PromptBlockMeter meter, TeamAgentStateView state,
                                    PhaseOutputContract contract, String requiredActionType, int budget) {
        Parsed parsed = callParseWithRepair(messages, meter, state.getPhaseId(), contract, requiredActionType,
                budget);
        if (parsed.failure != null) {
            return parsed.failure;
        }
        // The model is an ASSISTANT, not a process controller. A legacy proposedCommand only exists on the
        // generic turn, and is honored only when the host happens to allow it — otherwise silently ignored
        // (never a COMMAND_REJECTED, never a nagging repair). A phase-specific output carries no command.
        PhaseAssistantOutput output = parsed.output;
        String validatedCommand = null;
        if (output instanceof TeamAgentTurn) {
            TeamAgentTurn turn = (TeamAgentTurn) output;
            if (turn.hasProposedCommand() && state.allows(turn.getProposedCommand())) {
                validatedCommand = turn.getProposedCommand();
            }
        }
        return TeamAgentResult.ok(output, validatedCommand);
    }

    /**
     * ONE model call, schema-constrained when the contract provides a generation-time schema
     * (Ollama structured outputs) — grammar stops a small model from losing count of its braces;
     * without a schema the long-standing plain completion stays byte-identical.
     */
    private MainModelChatResult callModel(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                          PhaseOutputContract contract, String requiredActionType, int budget) {
        String schema = requiredActionType == null ? contract.outputSchemaJson()
                : contract.outputSchemaJson(requiredActionType);
        MainModelChat model = chatFor(phase);
        MainModelChatResult result = schema == null
                ? model.complete(messages, TEMPERATURE, budget)
                : model.completeJson(messages, TEMPERATURE, budget, schema);
        ModelCallLog.log(phase, meter, result);
        return result;
    }

    /** One model call + phase-contract parse, with EXACTLY ONE bounded parse-repair on failure. */
    private Parsed callParseWithRepair(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                       PhaseOutputContract contract, String requiredActionType, int budget) {
        MainModelChatResult call = callModel(messages, meter, phase, contract, requiredActionType, budget);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        PhaseParseResult parsed = contract.parse(call.getText());
        if (parsed.isOk()) {
            return Parsed.ok(parsed.getOutput(), call.getText());
        }
        traceParseFailure("first", parsed.getError(), call.getText());
        // The failed answer is NOT echoed back (plan PR 17, improvement 6): a short note names the parse error.
        List<ChatMessage> repair = new ArrayList<ChatMessage>(messages);
        PromptBlockMeter repairMeter = meter.copy();
        append(repair, repairMeter, PromptBlockMeter.REPAIR_NOTE,
                ChatMessage.user(TeamAgentPlaybook.repairNote(parsed.getError())));
        return callParseOnce(repair, repairMeter, phase, contract, requiredActionType, budget);
    }

    /**
     * Make an UNUSABLE_ANSWER DIAGNOSABLE. The user only ever sees one honest line, which says nothing about
     * WHY the turn failed — so a contract that keeps rejecting simple inputs can only be fixed by guessing,
     * and guessing means weakening the contract until something passes. This records the concrete parse error
     * together with the beginning of the model's actual answer, on STDERR like every other runtime log.
     */
    private static void traceParseFailure(String attempt, String error, String raw) {
        System.err.println("[team-agent] parse failed (" + attempt + "): " + error
                + " | answer=" + excerptOf(raw));
    }

    /** Enough of the answer to recognise its shape (fences, prose, truncation), never the whole turn. */
    private static final int PARSE_FAILURE_EXCERPT_CHARS = 600;

    static String excerptOf(String raw) {
        if (raw == null) {
            return "<none>";
        }
        String flat = raw.replace('\n', '⏎');
        if (flat.length() <= PARSE_FAILURE_EXCERPT_CHARS) {
            return flat;
        }
        // Head AND tail: an output-budget truncation is only visible at the END of the answer (the head
        // of a cut-off JSON looks perfectly healthy) — a head-only excerpt made that failure mode
        // undiagnosable from the trace.
        int half = PARSE_FAILURE_EXCERPT_CHARS / 2;
        return flat.substring(0, half) + " …[" + flat.length() + " chars]… "
                + flat.substring(flat.length() - half);
    }

    /**
     * One model call + parse, with NO further repair (used for the second, bounded repair attempt). A repair
     * is pure infrastructure: if the model uses this turn to apologize or talk about formatting/JSON, that
     * message must never reach the user. So a repaired turn whose visible message leaks codec meta-talk is
     * failed as UNUSABLE_ANSWER — the user then sees the fixed, meta-free typed line, and the pending user
     * turn stays intact for a clean retry.
     */
    private Parsed callParseOnce(List<ChatMessage> messages, PromptBlockMeter meter, String phase,
                                 PhaseOutputContract contract, String requiredActionType, int budget) {
        MainModelChatResult call = callModel(messages, meter, phase, contract, requiredActionType, budget);
        if (!call.isOk()) {
            return Parsed.fail(TeamAgentResult.modelUnavailable(call.getDetail()));
        }
        PhaseParseResult parsed = contract.parse(call.getText());
        if (!parsed.isOk()) {
            traceParseFailure("repair", parsed.getError(), call.getText());
            return Parsed.fail(TeamAgentResult.unusableAnswer(parsed.getError()));
        }
        if (!VisibleAssistantMessageValidator.isCleanBusinessMessage(parsed.getOutput().getAssistantMessage())) {
            traceParseFailure("repair-meta-talk", "visible message is not a business message",
                    parsed.getOutput().getAssistantMessage());
            return Parsed.fail(TeamAgentResult.unusableAnswer("repair produced a non-business message"));
        }
        return Parsed.ok(parsed.getOutput(), call.getText());
    }

    /**
     * Record the assistant turn CANONICALLY so CONTEXT ACCUMULATES across short replies without inventing a
     * third representation: history holds exactly the structured turn the model emitted, serialized by
     * {@link TeamAgentTurnCodec} and re-readable by {@link TeamAgentTurnParser}. The user still only ever sees
     * {@code assistantMessage} (that is what the wire carries); the model gets its own understood/suggested/
     * open facts back verbatim, so a following one-word reply has something to build on.
     */
    private int recordFinal(TeamAgentStateView state, PhaseAssistantOutput output) {
        history.add(ChatMessage.assistant(output.canonicalJson())
                .tagged(PhaseKeys.normalize(state.getPhaseId()), ChatMessage.Kind.FINAL));
        return history.size() - 1;
    }

    /** A fitted prompt OR the guard's visible failure — never both. */
    private static final class Prompt {
        final List<ChatMessage> messages;
        final PromptBlockMeter meter;
        final TeamAgentResult failure;

        private Prompt(List<ChatMessage> messages, PromptBlockMeter meter, TeamAgentResult failure) {
            this.messages = messages;
            this.meter = meter;
            this.failure = failure;
        }

        static Prompt ok(List<ChatMessage> messages, PromptBlockMeter meter) {
            return new Prompt(messages, meter, null);
        }

        static Prompt fail(TeamAgentResult failure) {
            return new Prompt(null, null, failure);
        }
    }

    /** A parsed phase output (with its raw text, for repair context) OR a typed failure — never both. */
    private static final class Parsed {
        final TeamAgentResult failure;
        final PhaseAssistantOutput output;
        final String raw;

        private Parsed(TeamAgentResult failure, PhaseAssistantOutput output, String raw) {
            this.failure = failure;
            this.output = output;
            this.raw = raw;
        }

        static Parsed fail(TeamAgentResult failure) {
            return new Parsed(failure, null, null);
        }

        static Parsed ok(PhaseAssistantOutput output, String raw) {
            return new Parsed(null, output, raw);
        }
    }
}
