package com.aresstack.writing4j.memory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Component 7, record "Entscheidung der Leitung" of the shared data model: what the research lead (component 8)
 * decided in a round, about what, why, and on which counts. Every decision can be read back. Immutable.
 * <p>
 * Field names of the data model: Runde, Ziel (Frage, Thema oder Lauf), Handlung, Grund, Zählwerte. Added, as for
 * every stored record: Modell, Zeit. Added for the search planner (component 3): the strategy of a search the decision
 * asks for, the instruction (Vorgabe) for the planner, and the questions a decision took in ({@code NEUE_FRAGEN}).
 */
public final class LeadDecision {

    /** What the decision is about. */
    public enum Target { FRAGE, THEMA, LAUF }

    /** Handlung. "Beenden" never means "everything answered". */
    public enum Action { WEITER_VERFOLGEN, STRATEGIE_WECHSELN, NEUE_FRAGEN, ZURUECKSTELLEN, BEENDEN }

    private final int round;
    private final Target target;
    private final String targetId;
    private final Action action;
    private final String reason;
    private final Map<String, Integer> counts;
    private final String model;
    private final long timeMillis;
    private final String strategy;
    private final String instruction;
    private final List<String> questionIds;

    /**
     * @param targetId the question or topic id, {@code ""} for the whole run
     * @param counts   the counts the decision rests on, by name ({@code direkt}, {@code hinweise}, ...)
     * @param model    the model that decided, {@code ""} when a guardrail rule did
     */
    public LeadDecision(int round, Target target, String targetId, Action action, String reason,
                        Map<String, Integer> counts, String model, long timeMillis) {
        this(round, target, targetId, action, reason, counts, model, timeMillis, "", "", null);
    }

    /**
     * @param strategy    the strategy of the search the decision asks for (as the lead names it, for example
     *                    {@code andere_wortwahl}), {@code ""} when it asks for none
     * @param instruction the lead's instruction for the search planner (Vorgabe), {@code ""} for none
     * @param questionIds the questions the decision took in ({@code NEUE_FRAGEN}), else empty
     */
    public LeadDecision(int round, Target target, String targetId, Action action, String reason,
                        Map<String, Integer> counts, String model, long timeMillis, String strategy,
                        String instruction, List<String> questionIds) {
        this.round = Math.max(1, round);
        this.target = target == null ? Target.LAUF : target;
        this.targetId = Texts.clean(targetId);
        this.action = action;
        this.reason = Texts.clean(reason);
        this.counts = Collections.unmodifiableMap(new LinkedHashMap<String, Integer>(
                counts == null ? Collections.<String, Integer>emptyMap() : counts));
        this.model = Texts.clean(model);
        this.timeMillis = timeMillis;
        this.strategy = Texts.clean(strategy);
        this.instruction = Texts.clean(instruction);
        this.questionIds = Texts.ids(questionIds);
        if (action == null) {
            throw new IllegalArgumentException("a decision needs an action");
        }
        if (this.target != Target.LAUF && this.targetId.isEmpty()) {
            throw new IllegalArgumentException("a decision about a " + this.target + " needs its id");
        }
    }

    public int getRound() {
        return round;
    }

    public Target getTarget() {
        return target;
    }

    public String getTargetId() {
        return targetId;
    }

    public Action getAction() {
        return action;
    }

    public String getReason() {
        return reason;
    }

    public Map<String, Integer> getCounts() {
        return counts;
    }

    public String getModel() {
        return model;
    }

    public long getTimeMillis() {
        return timeMillis;
    }

    /** The strategy of the search the decision asks for, {@code ""} when none. */
    public String getStrategy() {
        return strategy;
    }

    /** The instruction for the search planner (Vorgabe), {@code ""} when none. */
    public String getInstruction() {
        return instruction;
    }

    /** The questions the decision took in, in order; empty unless it is {@code NEUE_FRAGEN}. */
    public List<String> getQuestionIds() {
        return questionIds;
    }
}
