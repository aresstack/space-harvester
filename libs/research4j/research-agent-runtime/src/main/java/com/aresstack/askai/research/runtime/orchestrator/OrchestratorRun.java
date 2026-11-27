package com.aresstack.askai.research.runtime.orchestrator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The persisted record of one orchestrated research run: what the user approved (the envelope), what has been
 * spent against it, where the run stands and what waits for the user. It lives on the {@link TaskBoard} next
 * to the tasks, so a run interrupted by a closed session continues from here (ADR 0001, 4.4: budgets are
 * accounted across the interruption).
 *
 * <p>Immutable; every change returns a new record via {@link #toBuilder()}. The run never owns a phase: even
 * {@link State#CLOSED} only says "the orchestrator and the measurements agree the research is sufficient";
 * moving on to the next phase stays the user's action (RA-P6).</p>
 */
public final class OrchestratorRun {

    /** Where a run stands. Only the USER moves a run out of a waiting state. */
    public enum State {
        /** The orchestrator proposed a plan; nothing runs until the user approves it. */
        PLAN_PROPOSED("plan-proposed"),
        /** Inside the approved plan and budget the orchestrator iterates on its own. */
        RUNNING("running"),
        /** A re-plan beyond the approved scope or budget, or the round limit: the user decides. */
        AWAITING_APPROVAL("awaiting-approval"),
        /** The orchestrator asked the user a question. */
        AWAITING_INPUT("awaiting-input"),
        /** Sufficient by the orchestrator's verdict AND the measurements; the user decides what follows. */
        CLOSED("closed"),
        /** Stopped by the user (plan rejected, run stopped). */
        STOPPED("stopped"),
        /** The orchestrator could not produce a usable decision; the reason is recorded. */
        FAILED("failed");

        private final String wireName;

        State(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        public static State fromWireName(String value) {
            for (State state : values()) {
                if (state.wireName.equalsIgnoreCase(value) || state.name().equalsIgnoreCase(value)) {
                    return state;
                }
            }
            throw new IllegalArgumentException("unknown run state: " + value);
        }

        /** A run in a final state never continues; a new question starts a new run. */
        public boolean isFinal() {
            return this == CLOSED || this == STOPPED || this == FAILED;
        }

        /** The user is expected to act. */
        public boolean isWaitingForUser() {
            return this == PLAN_PROPOSED || this == AWAITING_APPROVAL || this == AWAITING_INPUT;
        }
    }

    private final String runId;
    private final String question;
    private final State state;
    private final long createdAtMillis;
    private final long updatedAtMillis;
    private final List<ResearchOutline.Section> sections;
    private final String planRationale;
    private final List<PlannedTask> plan;
    private final List<PlannedTask> pending;
    private final String waitingReason;
    private final int maxRounds;
    private final int maxTasks;
    private final long tokenBudget;
    private final long timeBudgetMillis;
    private final int roundsUsed;
    private final int tasksSubmitted;
    private final long tokensUsed;
    private final long timeUsedMillis;
    private final Set<String> approvedSections;
    private final Set<String> approvedRoles;
    private final List<RoundDecision.Finding> openGaps;
    private final List<RoundDecision.Finding> contradictions;
    private final String lastNote;
    private final Set<String> evaluatedTasks;
    private final List<String> userNotes;
    private final List<String> journal;

    private OrchestratorRun(Builder b) {
        if (b.runId == null || b.runId.trim().isEmpty()) {
            throw new IllegalArgumentException("runId must not be empty");
        }
        if (b.state == null) {
            throw new IllegalArgumentException("run " + b.runId + ": state is required");
        }
        this.runId = b.runId.trim();
        this.question = b.question == null ? "" : b.question;
        this.state = b.state;
        this.createdAtMillis = b.createdAtMillis;
        this.updatedAtMillis = b.updatedAtMillis;
        this.sections = copy(b.sections);
        this.planRationale = b.planRationale == null ? "" : b.planRationale;
        this.plan = copy(b.plan);
        this.pending = copy(b.pending);
        this.waitingReason = b.waitingReason == null ? "" : b.waitingReason;
        this.maxRounds = b.maxRounds;
        this.maxTasks = b.maxTasks;
        this.tokenBudget = b.tokenBudget;
        this.timeBudgetMillis = b.timeBudgetMillis;
        this.roundsUsed = b.roundsUsed;
        this.tasksSubmitted = b.tasksSubmitted;
        this.tokensUsed = b.tokensUsed;
        this.timeUsedMillis = b.timeUsedMillis;
        this.approvedSections = Collections.unmodifiableSet(new LinkedHashSet<String>(b.approvedSections));
        this.approvedRoles = Collections.unmodifiableSet(new LinkedHashSet<String>(b.approvedRoles));
        this.openGaps = copy(b.openGaps);
        this.contradictions = copy(b.contradictions);
        this.lastNote = b.lastNote == null ? "" : b.lastNote;
        this.evaluatedTasks = Collections.unmodifiableSet(new LinkedHashSet<String>(b.evaluatedTasks));
        this.userNotes = copy(b.userNotes);
        this.journal = copy(b.journal);
    }

    public static Builder builder(String runId, String question, long createdAtMillis) {
        Builder b = new Builder();
        b.runId = runId;
        b.question = question;
        b.createdAtMillis = createdAtMillis;
        b.updatedAtMillis = createdAtMillis;
        return b;
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.runId = runId;
        b.question = question;
        b.state = state;
        b.createdAtMillis = createdAtMillis;
        b.updatedAtMillis = updatedAtMillis;
        b.sections = new ArrayList<ResearchOutline.Section>(sections);
        b.planRationale = planRationale;
        b.plan = new ArrayList<PlannedTask>(plan);
        b.pending = new ArrayList<PlannedTask>(pending);
        b.waitingReason = waitingReason;
        b.maxRounds = maxRounds;
        b.maxTasks = maxTasks;
        b.tokenBudget = tokenBudget;
        b.timeBudgetMillis = timeBudgetMillis;
        b.roundsUsed = roundsUsed;
        b.tasksSubmitted = tasksSubmitted;
        b.tokensUsed = tokensUsed;
        b.timeUsedMillis = timeUsedMillis;
        b.approvedSections = new LinkedHashSet<String>(approvedSections);
        b.approvedRoles = new LinkedHashSet<String>(approvedRoles);
        b.openGaps = new ArrayList<RoundDecision.Finding>(openGaps);
        b.contradictions = new ArrayList<RoundDecision.Finding>(contradictions);
        b.lastNote = lastNote;
        b.evaluatedTasks = new LinkedHashSet<String>(evaluatedTasks);
        b.userNotes = new ArrayList<String>(userNotes);
        b.journal = new ArrayList<String>(journal);
        return b;
    }

    /** The task id of a planned package of this run: {@code <runId>.<plannedId>}. */
    public String taskIdOf(String plannedId) {
        return runId + "." + plannedId;
    }

    /** Whether a persisted task belongs to this run. */
    public boolean owns(String taskId) {
        return taskId != null && taskId.startsWith(runId + ".");
    }

    /** The planned id of one of this run's task ids. */
    public String plannedIdOf(String taskId) {
        return owns(taskId) ? taskId.substring(runId.length() + 1) : taskId;
    }

    public ResearchOutline outline() {
        return new ResearchOutline(question, sections);
    }

    public int remainingRounds() {
        return Math.max(0, maxRounds - roundsUsed);
    }

    public int remainingTasks() {
        return Math.max(0, maxTasks - tasksSubmitted);
    }

    public boolean isTokenBudgetSpent() {
        return tokenBudget > 0 && tokensUsed >= tokenBudget;
    }

    public boolean isTimeBudgetSpent() {
        return timeBudgetMillis > 0 && timeUsedMillis >= timeBudgetMillis;
    }

    /** Whether the time budget is spent once {@code now - since} more wall time is booked. */
    public boolean isTimeBudgetSpentAt(long now, long since) {
        return timeBudgetMillis > 0 && timeUsedMillis + Math.max(0L, now - since) >= timeBudgetMillis;
    }

    public String getRunId() {
        return runId;
    }

    public String getQuestion() {
        return question;
    }

    public State getState() {
        return state;
    }

    public long getCreatedAtMillis() {
        return createdAtMillis;
    }

    public long getUpdatedAtMillis() {
        return updatedAtMillis;
    }

    public List<ResearchOutline.Section> getSections() {
        return sections;
    }

    public String getPlanRationale() {
        return planRationale;
    }

    /** The plan as proposed (and, once running, as approved). */
    public List<PlannedTask> getPlan() {
        return plan;
    }

    /** Follow-up packages waiting for the user's approval; empty unless {@link State#AWAITING_APPROVAL}. */
    public List<PlannedTask> getPending() {
        return pending;
    }

    /** Why the run waits for the user (overrun, scope change, round limit, the orchestrator's question). */
    public String getWaitingReason() {
        return waitingReason;
    }

    public int getMaxRounds() {
        return maxRounds;
    }

    public int getMaxTasks() {
        return maxTasks;
    }

    /** 0 = not limited. */
    public long getTokenBudget() {
        return tokenBudget;
    }

    /** 0 = not limited. */
    public long getTimeBudgetMillis() {
        return timeBudgetMillis;
    }

    public int getRoundsUsed() {
        return roundsUsed;
    }

    public int getTasksSubmitted() {
        return tasksSubmitted;
    }

    public long getTokensUsed() {
        return tokensUsed;
    }

    public long getTimeUsedMillis() {
        return timeUsedMillis;
    }

    /** The outline sections the approved plan covers: the approved scope of the run. */
    public Set<String> getApprovedSections() {
        return approvedSections;
    }

    /** The worker roles the approved plan uses. */
    public Set<String> getApprovedRoles() {
        return approvedRoles;
    }

    public List<RoundDecision.Finding> getOpenGaps() {
        return openGaps;
    }

    public List<RoundDecision.Finding> getContradictions() {
        return contradictions;
    }

    /** The orchestrator's latest quality note (shown to the user, never parsed). */
    public String getLastNote() {
        return lastNote;
    }

    /** Planned ids of the packages the orchestrator has already evaluated. */
    public Set<String> getEvaluatedTasks() {
        return evaluatedTasks;
    }

    /** The user's answers to the orchestrator's questions, oldest first (context for the next round). */
    public List<String> getUserNotes() {
        return userNotes;
    }

    /** What happened, oldest first, one line each (user-readable, English). */
    public List<String> getJournal() {
        return journal;
    }

    private static <T> List<T> copy(List<T> values) {
        return values == null || values.isEmpty() ? Collections.<T>emptyList()
                : Collections.unmodifiableList(new ArrayList<T>(values));
    }

    /** Builds a run record. */
    public static final class Builder {
        private String runId;
        private String question;
        private State state;
        private long createdAtMillis;
        private long updatedAtMillis;
        private List<ResearchOutline.Section> sections = new ArrayList<ResearchOutline.Section>();
        private String planRationale;
        private List<PlannedTask> plan = new ArrayList<PlannedTask>();
        private List<PlannedTask> pending = new ArrayList<PlannedTask>();
        private String waitingReason;
        private int maxRounds;
        private int maxTasks;
        private long tokenBudget;
        private long timeBudgetMillis;
        private int roundsUsed;
        private int tasksSubmitted;
        private long tokensUsed;
        private long timeUsedMillis;
        private Set<String> approvedSections = new LinkedHashSet<String>();
        private Set<String> approvedRoles = new LinkedHashSet<String>();
        private List<RoundDecision.Finding> openGaps = new ArrayList<RoundDecision.Finding>();
        private List<RoundDecision.Finding> contradictions = new ArrayList<RoundDecision.Finding>();
        private String lastNote;
        private Set<String> evaluatedTasks = new LinkedHashSet<String>();
        private List<String> userNotes = new ArrayList<String>();
        private List<String> journal = new ArrayList<String>();

        private Builder() {
        }

        public Builder state(State state) {
            this.state = state;
            return this;
        }

        public Builder updatedAt(long millis) {
            this.updatedAtMillis = Math.max(this.updatedAtMillis, millis);
            return this;
        }

        public Builder sections(List<ResearchOutline.Section> sections) {
            this.sections = new ArrayList<ResearchOutline.Section>(sections);
            return this;
        }

        public Builder plan(String rationale, List<PlannedTask> plan) {
            this.planRationale = rationale;
            this.plan = new ArrayList<PlannedTask>(plan);
            return this;
        }

        public Builder pending(List<PlannedTask> pending, String reason) {
            this.pending = new ArrayList<PlannedTask>(pending);
            this.waitingReason = reason;
            return this;
        }

        public Builder waitingReason(String reason) {
            this.waitingReason = reason;
            return this;
        }

        public Builder envelope(int maxRounds, int maxTasks, long tokenBudget, long timeBudgetMillis) {
            this.maxRounds = maxRounds;
            this.maxTasks = maxTasks;
            this.tokenBudget = tokenBudget;
            this.timeBudgetMillis = timeBudgetMillis;
            return this;
        }

        public Builder spent(int roundsUsed, int tasksSubmitted, long tokensUsed, long timeUsedMillis) {
            this.roundsUsed = roundsUsed;
            this.tasksSubmitted = tasksSubmitted;
            this.tokensUsed = tokensUsed;
            this.timeUsedMillis = timeUsedMillis;
            return this;
        }

        public Builder addTokens(long tokens) {
            this.tokensUsed += Math.max(0L, tokens);
            return this;
        }

        public Builder addTime(long millis) {
            this.timeUsedMillis += Math.max(0L, millis);
            return this;
        }

        public Builder approved(Set<String> sections, Set<String> roles) {
            this.approvedSections = new LinkedHashSet<String>(sections);
            this.approvedRoles = new LinkedHashSet<String>(roles);
            return this;
        }

        public Builder findings(List<RoundDecision.Finding> gaps, List<RoundDecision.Finding> contradictions,
                                String note) {
            this.openGaps = new ArrayList<RoundDecision.Finding>(gaps);
            this.contradictions = new ArrayList<RoundDecision.Finding>(contradictions);
            this.lastNote = note;
            return this;
        }

        public Builder evaluated(java.util.Collection<String> plannedIds) {
            this.evaluatedTasks.addAll(plannedIds);
            return this;
        }

        public Builder userNote(String note) {
            if (note != null && !note.trim().isEmpty()) {
                this.userNotes.add(note.trim());
            }
            return this;
        }

        Builder userNotesAll(List<String> notes) {
            this.userNotes = new ArrayList<String>(notes);
            return this;
        }

        public Builder journal(String line) {
            if (line != null && !line.trim().isEmpty()) {
                this.journal.add(line.trim());
            }
            return this;
        }

        Builder journalAll(List<String> lines) {
            this.journal = new ArrayList<String>(lines);
            return this;
        }

        public OrchestratorRun build() {
            return new OrchestratorRun(this);
        }
    }
}
