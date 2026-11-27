package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * The orchestrated research run: the iterative cycle of ADR 0001, 4.2 inside its deterministic ENVELOPE.
 *
 * <pre>
 * start(question)  → orchestrator plans          → PLAN_PROPOSED     (nothing runs yet)
 * approve()        → plan submitted, envelope set → RUNNING
 *   round: executor runs ready tasks → evidence measured → orchestrator evaluates
 *     close + measurements sufficient                 → CLOSED   (the next phase is the user's decision)
 *     continue, follow-ups inside plan and budget     → submitted, next round
 *     follow-ups outside the approved scope or budget → AWAITING_APPROVAL (pending until approve())
 *     round limit or run budget reached               → AWAITING_APPROVAL
 *     ask-user                                         → AWAITING_INPUT   (until answer())
 * stop()           → open tasks canceled          → STOPPED
 * </pre>
 *
 * <p>The envelope: the user approves the plan and the budget (settings); inside them the orchestrator iterates
 * on its own; a re-plan that names a section or role outside the approved plan, or exceeds the package count,
 * the rounds, the tokens or the time, goes back to the user. Close needs the orchestrator's verdict AND the
 * {@link EvidenceMeter}'s sufficiency; the model's verdict never replaces the measurements. Nothing here sends
 * a phase command: phase transitions stay with the user and the host state machine (RA-P6).</p>
 *
 * <p>Every step is persisted on the {@link TaskBoard}; a run interrupted mid-round continues with
 * {@link #approve} (which resumes a {@code RUNNING} run) from what the store holds.</p>
 */
public final class OrchestratedResearchRun {

    /** What the user sees and what the technical log records. */
    public interface Listener {
        /** A message for the user (English static texts plus the orchestrator's notes). */
        void visible(String message);

        /** A technical log line. */
        void log(String line);
    }

    private final TaskBoard board;
    /** True while an approval or an answer runs the cycle; the user may only stop meanwhile. */
    private final AtomicBoolean executing = new AtomicBoolean();
    private final TaskExecutor executor;
    private final ResearchOrchestrator orchestrator;
    private final AtomicLong orchestratorTokens;
    private final EvidenceMeter meter;
    private final OrchestratorSettings settings;
    private final LongSupplier clock;
    private final Listener listener;
    private final Object lock = new Object();

    /**
     * @param orchestratorTokens the counter the orchestrator's chat books its tokens on (see {@link RoleChats})
     */
    public OrchestratedResearchRun(TaskBoard board, TaskExecutor executor, ResearchOrchestrator orchestrator,
                                   AtomicLong orchestratorTokens, EvidenceMeter meter,
                                   OrchestratorSettings settings, LongSupplier clock, Listener listener) {
        this.board = board;
        this.executor = executor;
        this.orchestrator = orchestrator;
        this.orchestratorTokens = orchestratorTokens;
        this.meter = meter;
        this.settings = settings;
        this.clock = clock;
        this.listener = listener;
    }

    /** The project's current run, or null. */
    public OrchestratorRun current() {
        synchronized (lock) {
            return board.currentRun();
        }
    }

    // ---- user actions ------------------------------------------------------------------------------------

    /**
     * A new research question: an unfinished earlier run is stopped (its open tasks canceled), the
     * orchestrator plans, and the plan waits for the user's approval.
     */
    public OrchestratorRun start(String question, ResearchOutline outline) {
        OrchestratorRun previous = current();
        if (previous != null && !previous.getState().isFinal()) {
            finish(previous, OrchestratorRun.State.STOPPED, "Stopped: superseded by a new research question.");
            executor.cancelOpen(previous, "superseded by a new research question");
        }
        long now = clock.getAsLong();
        OrchestratorRun run = OrchestratorRun.builder("run-" + now, question, now)
                .state(OrchestratorRun.State.PLAN_PROPOSED)
                .sections(outline.getSections())
                .envelope(settings.getMaxRounds(), settings.getMaxTasks(), settings.getTokenBudget(),
                        settings.getTimeBudgetMinutes() * 60_000L)
                .journal("Run started for: " + question)
                .build();
        save(run);
        int planCap = Math.min(settings.getMaxTasks(),
                Math.max(settings.getMaxTasksPerRound(), outline.getSections().size()));
        long tokensBefore = orchestratorTokens.get();
        long planStart = clock.getAsLong();
        RunPlan plan;
        try {
            plan = orchestrator.plan(run, roleInfos(), planCap);
        } catch (ResearchOrchestrator.UnavailableException unavailable) {
            run = run.toBuilder().addTokens(orchestratorTokens.get() - tokensBefore)
                    .addTime(clock.getAsLong() - planStart).updatedAt(clock.getAsLong())
                    .state(OrchestratorRun.State.FAILED).waitingReason(unavailable.getMessage())
                    .journal("Planning failed: " + unavailable.getMessage()).build();
            if (!save(run)) {
                return current();
            }
            listener.visible("The research plan could not be made: " + unavailable.getMessage()
                    + " Ask again to retry.");
            return run;
        }
        run = run.toBuilder().addTokens(orchestratorTokens.get() - tokensBefore)
                .addTime(clock.getAsLong() - planStart).updatedAt(clock.getAsLong())
                .plan(plan.getRationale(), plan.getTasks())
                .waitingReason("The plan needs your approval.")
                .journal("Plan proposed with " + plan.getTasks().size() + " work package(s).")
                .build();
        if (!save(run)) {
            return current(); // stopped or superseded while planning
        }
        listener.visible(describePlan(run));
        return run;
    }

    /**
     * The user's approval. On a proposed plan: the plan and the budget are approved and the run starts. On a
     * run waiting for approval: the pending follow-ups (and the extra rounds or budget they need) are
     * approved and the run continues. On a run left {@code running} by an interruption: it resumes.
     */
    public OrchestratorRun approve(AtomicBoolean cancelled) {
        if (!executing.compareAndSet(false, true)) {
            return alreadyExecuting();
        }
        try {
            return approveExclusively(cancelled);
        } finally {
            executing.set(false);
        }
    }

    private OrchestratorRun approveExclusively(AtomicBoolean cancelled) {
        OrchestratorRun run = current();
        if (run == null || run.getState().isFinal() || run.getState() == OrchestratorRun.State.AWAITING_INPUT) {
            return run;
        }
        long now = clock.getAsLong();
        if (run.getState() == OrchestratorRun.State.PLAN_PROPOSED) {
            Set<String> sections = new LinkedHashSet<String>();
            Set<String> roles = new LinkedHashSet<String>();
            collectScope(run.getPlan(), sections, roles);
            executor.submit(run, run.getPlan(), settings);
            run = run.toBuilder().state(OrchestratorRun.State.RUNNING).updatedAt(now).waitingReason("")
                    .approved(sections, roles)
                    .spent(run.getRoundsUsed(), run.getPlan().size(), run.getTokensUsed(), run.getTimeUsedMillis())
                    .journal("Plan approved by the user.").build();
            save(run);
            listener.visible("Plan approved. Research is running: " + run.getPlan().size()
                    + " work package(s), at most " + run.getMaxRounds() + " evaluation round(s).");
        } else if (run.getState() == OrchestratorRun.State.AWAITING_APPROVAL) {
            run = extend(run, now);
            save(run);
            listener.visible("Approved. Research continues.");
        } else {
            int requeued = executor.resume(run);
            if (requeued > 0) {
                run = run.toBuilder().updatedAt(now)
                        .journal("Resumed after an interruption: " + requeued + " package(s) re-queued.").build();
                save(run);
            }
        }
        return continueRun(cancelled);
    }

    /** The user's answer to the orchestrator's question; the run continues with it as context. */
    public OrchestratorRun answer(String answer, AtomicBoolean cancelled) {
        if (!executing.compareAndSet(false, true)) {
            return alreadyExecuting();
        }
        try {
            return answerExclusively(answer, cancelled);
        } finally {
            executing.set(false);
        }
    }

    /**
     * A second approval or answer while the cycle runs (a double click, Continue on a live run) would resume
     * packages the running cycle still executes and start a second loop on the same record: refused.
     */
    private OrchestratorRun alreadyExecuting() {
        listener.visible("The research run is already executing. Wait for it, or stop it.");
        return current();
    }

    private OrchestratorRun answerExclusively(String answer, AtomicBoolean cancelled) {
        OrchestratorRun run = current();
        if (run == null || run.getState() != OrchestratorRun.State.AWAITING_INPUT) {
            return run;
        }
        executor.answer(run, answer);
        run = run.toBuilder().state(OrchestratorRun.State.RUNNING).updatedAt(clock.getAsLong())
                .waitingReason("").userNote(answer).journal("The user answered: " + answer).build();
        save(run);
        return continueRun(cancelled);
    }

    /** The user rejects the plan or stops the run: open tasks are canceled, nothing is lost. */
    public OrchestratorRun stop() {
        OrchestratorRun run = current();
        if (run == null || run.getState().isFinal()) {
            return run;
        }
        executor.cancelOpen(run, "stopped by the user");
        run = finish(run, OrchestratorRun.State.STOPPED, run.getState() == OrchestratorRun.State.PLAN_PROPOSED
                ? "Plan rejected by the user." : "Stopped by the user.");
        listener.visible("The orchestrated research run was stopped. Accepted sources stay in the project.");
        return run;
    }

    // ---- the cycle ---------------------------------------------------------------------------------------

    private OrchestratorRun continueRun(AtomicBoolean cancelled) {
        while (true) {
            final OrchestratorRun run = current();
            if (run == null || run.getState() != OrchestratorRun.State.RUNNING) {
                return run;
            }
            long roundStart = clock.getAsLong();
            TaskExecutor.Stop stop = executor.runReady(run, cancelled, () -> {
                OrchestratorRun live = current();
                return !live.isTokenBudgetSpent() && !live.isTimeBudgetSpentAt(clock.getAsLong(), roundStart);
            });
            OrchestratorRun after = current().toBuilder().addTime(clock.getAsLong() - roundStart)
                    .updatedAt(clock.getAsLong()).build();
            save(after);
            if (after.getState() != OrchestratorRun.State.RUNNING) {
                return after; // stopped by the user while the packages ran
            }
            if (stop == TaskExecutor.Stop.CANCELLED || cancelled.get()) {
                // Interrupted (the user cancelled the turn or the session closes): the run stays RUNNING and
                // resumes on the next approval; open tasks are re-queued then.
                save(after.toBuilder().journal("Interrupted; continue to resume.").build());
                return current();
            }
            if (after.isTokenBudgetSpent() || after.isTimeBudgetSpent()) {
                return waitFor(after, new ArrayList<PlannedTask>(), "The approved "
                        + (after.isTokenBudgetSpent() ? "token" : "time") + " budget is spent.");
            }
            if (after.remainingRounds() == 0) {
                return waitFor(after, new ArrayList<PlannedTask>(), "The approved number of rounds ("
                        + after.getMaxRounds() + ") is used up.");
            }
            if (!round(after)) {
                return current();
            }
        }
    }

    /** One evaluation round; true when the run should keep cycling. */
    private boolean round(OrchestratorRun run) {
        List<ResearchTask> tasks = board.tasks();
        EvidenceSnapshot evidence = meter.measure(run, tasks);
        List<String> evaluated = new ArrayList<String>();
        boolean open = false;
        for (ResearchTask task : tasks) {
            if (!run.owns(task.getTaskId())) {
                continue;
            }
            String id = run.plannedIdOf(task.getTaskId());
            if (task.isTerminal() && !run.getEvaluatedTasks().contains(id)) {
                evaluated.add(id);
            }
            open |= !task.isTerminal();
        }
        int maxFollowUps = Math.min(settings.getMaxTasksPerRound(), Math.max(1, run.remainingTasks()));
        long tokensBefore = orchestratorTokens.get();
        long decisionStart = clock.getAsLong();
        RoundDecision decision;
        try {
            decision = orchestrator.evaluate(run, tasks, evidence, roleInfos(), evaluated, maxFollowUps);
        } catch (ResearchOrchestrator.UnavailableException unavailable) {
            if (!save(run.toBuilder().addTokens(orchestratorTokens.get() - tokensBefore)
                    .addTime(clock.getAsLong() - decisionStart).build())) {
                return false;
            }
            waitFor(current(), new ArrayList<PlannedTask>(), "The orchestrator could not decide ("
                    + unavailable.getMessage() + "). Approve to retry, or stop the run.");
            return false;
        }
        long now = clock.getAsLong();
        OrchestratorRun.Builder next = run.toBuilder()
                .addTokens(orchestratorTokens.get() - tokensBefore)
                .spent(run.getRoundsUsed() + 1, run.getTasksSubmitted(), run.getTokensUsed()
                        + (orchestratorTokens.get() - tokensBefore),
                        run.getTimeUsedMillis() + Math.max(0L, now - decisionStart))
                .evaluated(evaluated)
                .findings(decision.getGaps(), decision.getContradictions(), decision.getQualityNote())
                .updatedAt(now)
                .journal(roundSummary(run.getRoundsUsed() + 1, decision, evidence));
        run = next.build();
        if (!save(run)) {
            return false; // the user stopped the run while the orchestrator decided
        }
        listener.log("orchestrator round " + run.getRoundsUsed() + ": " + decision.getAction().wireName()
                + ", " + decision.getFollowUps().size() + " follow-up(s); measurements "
                + (evidence.isSufficient() ? "sufficient" : "not sufficient (" + evidence.insufficiencyReason()
                + ")"));
        switch (decision.getAction()) {
            case CLOSE:
                if (evidence.isSufficient()) {
                    if (finish(run, OrchestratorRun.State.CLOSED,
                            "Closed: sufficient by the orchestrator's verdict and the measurements.").getState()
                            != OrchestratorRun.State.CLOSED) {
                        return false;
                    }
                    listener.visible("The research looks sufficient: " + evidence.getDistinctSources()
                            + " sources on " + evidence.getDistinctHosts() + " hosts, every section covered. "
                            + (decision.getQualityNote().isEmpty() ? "" : decision.getQualityNote() + " ")
                            + "Moving on to the next phase is your decision.");
                    return false;
                }
                if (open) {
                    // Packages are still running: the measurements may change once they finish.
                    save(run.toBuilder().journal("Close proposed, but packages are still open.").build());
                    return true;
                }
                waitFor(run, new ArrayList<PlannedTask>(), "The orchestrator proposes to close, but the "
                        + "measurements are not sufficient (" + evidence.insufficiencyReason() + "). Approve "
                        + "another round, or stop the run.");
                return false;
            case ASK_USER:
                save(run.toBuilder().state(OrchestratorRun.State.AWAITING_INPUT).waitingReason(decision.getQuestion())
                        .journal("The orchestrator asks: " + decision.getQuestion()).build());
                listener.visible("The research needs your input: " + decision.getQuestion());
                return false;
            default:
                return continueWith(run, decision.getFollowUps(), open, evidence);
        }
    }

    private boolean continueWith(OrchestratorRun run, List<PlannedTask> followUps, boolean open,
                                 EvidenceSnapshot evidence) {
        if (followUps.isEmpty()) {
            if (open) {
                return true;
            }
            waitFor(run, followUps, evidence.isSufficient()
                    ? "The orchestrator proposed nothing further; the measurements are sufficient. Approve "
                    + "another round, or stop the run."
                    : "The orchestrator proposed nothing further, but the measurements are not sufficient ("
                    + evidence.insufficiencyReason() + "). Approve another round, or stop the run.");
            return false;
        }
        List<String> outside = new ArrayList<String>();
        for (PlannedTask task : followUps) {
            for (String section : task.getSections()) {
                if (!run.getApprovedSections().contains(section)) {
                    outside.add("section " + run.outline().titleOf(section));
                }
            }
            if (!run.getApprovedRoles().contains(task.getRole())) {
                outside.add("role " + task.getRole());
            }
        }
        if (!outside.isEmpty()) {
            waitFor(run, followUps, "The orchestrator wants to go beyond the approved plan ("
                    + String.join(", ", new LinkedHashSet<String>(outside)) + ").");
            return false;
        }
        if (followUps.size() > run.remainingTasks()) {
            waitFor(run, followUps, "The orchestrator wants " + followUps.size() + " more work package(s); "
                    + run.remainingTasks() + " remain in the approved budget.");
            return false;
        }
        if (!stillRunning(run)) {
            return false;
        }
        executor.submit(run, followUps, settings);
        save(run.toBuilder().spent(run.getRoundsUsed(), run.getTasksSubmitted() + followUps.size(),
                        run.getTokensUsed(), run.getTimeUsedMillis())
                .journal("Follow-ups submitted: " + ids(followUps) + ".").build());
        return true;
    }

    // ---- envelope helpers --------------------------------------------------------------------------------

    /** Approving a waiting run: pending follow-ups join the scope; spent limits get one more increment. */
    private OrchestratorRun extend(OrchestratorRun run, long now) {
        Set<String> sections = new LinkedHashSet<String>(run.getApprovedSections());
        Set<String> roles = new LinkedHashSet<String>(run.getApprovedRoles());
        collectScope(run.getPending(), sections, roles);
        int maxRounds = run.getMaxRounds();
        if (run.remainingRounds() == 0) {
            maxRounds = run.getRoundsUsed() + settings.getExtensionRounds();
        }
        int maxTasks = Math.max(run.getMaxTasks(), run.getTasksSubmitted() + run.getPending().size());
        if (run.getPending().isEmpty() && run.remainingTasks() == 0) {
            maxTasks = run.getTasksSubmitted() + settings.getExtensionTasks();
        }
        long tokenBudget = run.getTokenBudget();
        if (run.isTokenBudgetSpent()) {
            tokenBudget = run.getTokensUsed() + Math.max(1L, settings.getTokenBudget());
        }
        long timeBudget = run.getTimeBudgetMillis();
        if (run.isTimeBudgetSpent()) {
            timeBudget = run.getTimeUsedMillis() + Math.max(60_000L, settings.getTimeBudgetMinutes() * 60_000L);
        }
        List<PlannedTask> pending = run.getPending();
        if (!pending.isEmpty()) {
            executor.submit(run, pending, settings);
        }
        return run.toBuilder().state(OrchestratorRun.State.RUNNING).updatedAt(now)
                .envelope(maxRounds, maxTasks, tokenBudget, timeBudget)
                .approved(sections, roles)
                .spent(run.getRoundsUsed(), run.getTasksSubmitted() + pending.size(), run.getTokensUsed(),
                        run.getTimeUsedMillis())
                .pending(new ArrayList<PlannedTask>(), "")
                .journal("Approved by the user" + (pending.isEmpty() ? "" : ": " + ids(pending)) + ".")
                .build();
    }

    private OrchestratorRun waitFor(OrchestratorRun run, List<PlannedTask> pending, String reason) {
        OrchestratorRun waiting = run.toBuilder().state(OrchestratorRun.State.AWAITING_APPROVAL)
                .updatedAt(clock.getAsLong()).pending(pending, reason).journal("Waiting for approval: " + reason)
                .build();
        if (!save(waiting)) {
            return current();
        }
        StringBuilder message = new StringBuilder("The research run needs your approval. ").append(reason);
        for (PlannedTask task : pending) {
            message.append("\n- ").append(task.getId()).append(" (").append(task.getRole()).append("): ")
                    .append(task.getQuery());
        }
        listener.visible(message.toString());
        return waiting;
    }

    private OrchestratorRun finish(OrchestratorRun run, OrchestratorRun.State state, String line) {
        OrchestratorRun finished = run.toBuilder().state(state).updatedAt(clock.getAsLong())
                .pending(new ArrayList<PlannedTask>(), "").journal(line).build();
        return save(finished) ? finished : current();
    }

    private static void collectScope(List<PlannedTask> tasks, Set<String> sections, Set<String> roles) {
        for (PlannedTask task : tasks) {
            sections.addAll(task.getSections());
            roles.add(task.getRole());
        }
    }

    private List<ResearchOrchestrator.WorkerRoleInfo> roleInfos() {
        List<ResearchOrchestrator.WorkerRoleInfo> infos = new ArrayList<ResearchOrchestrator.WorkerRoleInfo>();
        for (String role : executor.roles()) {
            infos.add(ResearchOrchestrator.WorkerRoleInfo.of(executor.workerOf(role).role()));
        }
        return infos;
    }

    /**
     * Persists {@code run} unless the user overtook it meanwhile: a stop (or a close) made the stored record
     * final, or a newer run superseded it. A decision computed from an older snapshot, e.g. an evaluation
     * that returned after the user pressed Stop, never overwrites that; the caller ends its cycle instead.
     *
     * @return false when the write was refused
     */
    private boolean save(OrchestratorRun run) {
        synchronized (lock) {
            OrchestratorRun stored = board.currentRun();
            if (stored != null && stored.getRunId().equals(run.getRunId())) {
                if (stored.getState().isFinal()) {
                    return false;
                }
            } else if (stored != null && stored.getCreatedAtMillis() > run.getCreatedAtMillis()) {
                return false;
            }
            board.saveRun(run);
            return true;
        }
    }

    /** True while {@code run} is still the stored, running record (no stop and no new run since). */
    private boolean stillRunning(OrchestratorRun run) {
        OrchestratorRun stored = current();
        return stored != null && stored.getRunId().equals(run.getRunId())
                && stored.getState() == OrchestratorRun.State.RUNNING;
    }

    /** Books tokens a task spent on the current run (called from worker threads). */
    public void bookTaskTokens(long tokens) {
        if (tokens <= 0) {
            return;
        }
        synchronized (lock) {
            OrchestratorRun run = board.currentRun();
            if (run != null) {
                board.saveRun(run.toBuilder().addTokens(tokens).build());
            }
        }
    }

    private static String ids(List<PlannedTask> tasks) {
        List<String> ids = new ArrayList<String>();
        for (PlannedTask task : tasks) {
            ids.add(task.getId());
        }
        return String.join(", ", ids);
    }

    private static String roundSummary(int round, RoundDecision decision, EvidenceSnapshot evidence) {
        StringBuilder sb = new StringBuilder("Round ").append(round).append(": ")
                .append(decision.getAction().wireName());
        for (RoundDecision.Evaluation evaluation : decision.getEvaluations()) {
            sb.append("; ").append(evaluation.getTaskId()).append(' ').append(evaluation.getVerdict().wireName());
        }
        sb.append("; ").append(decision.getGaps().size()).append(" gap(s), ")
                .append(decision.getContradictions().size()).append(" contradiction(s); measured ")
                .append(evidence.getDistinctSources()).append(" sources on ").append(evidence.getDistinctHosts())
                .append(" hosts");
        return sb.toString();
    }

    /** The plan as the user reads it before approving. */
    static String describePlan(OrchestratorRun run) {
        StringBuilder sb = new StringBuilder("Research plan for approval (")
                .append(run.getPlan().size()).append(" work package(s)):");
        for (PlannedTask task : run.getPlan()) {
            sb.append("\n- ").append(task.getId()).append(" (").append(task.getRole()).append("): ")
                    .append(task.getQuery());
            List<String> titles = new ArrayList<String>();
            for (String section : task.getSections()) {
                titles.add(run.outline().titleOf(section));
            }
            sb.append(" [").append(String.join(", ", titles)).append(']');
            if (!task.getDependsOn().isEmpty()) {
                sb.append(" after ").append(String.join(", ", task.getDependsOn()));
            }
        }
        if (!run.getPlanRationale().isEmpty()) {
            sb.append("\nWhy: ").append(run.getPlanRationale());
        }
        sb.append("\nBudget: at most ").append(run.getMaxRounds()).append(" round(s), ")
                .append(run.getMaxTasks()).append(" work package(s)");
        if (run.getTokenBudget() > 0) {
            sb.append(", ").append(run.getTokenBudget()).append(" tokens");
        }
        if (run.getTimeBudgetMillis() > 0) {
            sb.append(", ").append(run.getTimeBudgetMillis() / 60_000L).append(" minutes");
        }
        sb.append(". Approve the plan to start, or reject it.");
        return sb.toString();
    }
}
