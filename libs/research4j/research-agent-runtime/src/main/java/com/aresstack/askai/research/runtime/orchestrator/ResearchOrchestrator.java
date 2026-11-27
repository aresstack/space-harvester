package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.domain.task.TaskState;
import com.aresstack.askai.research.domain.task.TaskTransition;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The RESEARCH ORCHESTRATOR (ADR 0001, 4.2): the model role that decomposes the approved question into work
 * packages, evaluates their results, detects gaps and contradictions against the outline, defines follow-up
 * packages, checks the quality and proposes to close. It is the intelligent component, and it only DECIDES:
 * every answer is a schema-bound decision ({@link OrchestratorDecisionCodec}), the
 * {@link OrchestratedResearchRun} and the {@link TaskExecutor} act on it inside the envelope the user approved.
 *
 * <p>Its input is the run's facts, rendered as plain text sections: the question, the outline, the roles it
 * may delegate to, what is left of the approved budget, the deterministic measurements of the
 * {@link EvidenceMeter} and the tasks with their states and artifacts. It never sees a tool, a phase command or
 * a model name; it cannot advance a phase.</p>
 */
public final class ResearchOrchestrator {

    static final String SYSTEM =
            "You are the research orchestrator of a research assistant. You plan and steer the research of ONE "
                    + "question the user has approved. You never search, read or write yourself: you hand work "
                    + "packages to worker roles and judge what they bring back.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Every work package names one worker role, a concrete search query, a short brief and "
                    + "the outline sections it serves (section ids from the OUTLINE list only).\n"
                    + "- Prefer few, distinct packages. One package per uncovered aspect; never two packages "
                    + "with the same query.\n"
                    + "- Use dependsOn only when a package needs the result of another one.\n"
                    + "- Judge on the MEASUREMENTS: a section with no sources is a gap whatever you believe.\n"
                    + "- Choose close only when the measurements show enough sources for every section and "
                    + "you found no open contradiction.\n"
                    + "- Choose ask-user only for a question the user alone can answer (scope, priorities); "
                    + "never to ask for permission.\n"
                    + "- You do not decide about phases, approvals or budgets. The user does.";

    /** The role (ADR 0001, 4.1): the orchestrator slot, no tools, a per-request schema read by the codec. */
    public static final ResearchRole ROLE = ResearchRole.builder("research-orchestrator",
                    ResearchModelSlots.ORCHESTRATOR)
            .purpose("plans, evaluates and re-plans the work packages of an orchestrated research run")
            .systemPrompt(SYSTEM)
            .output(RoleOutput.schemaPerRequest("OrchestratorDecisionCodec"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    /** Low but non-zero: the decisions should be stable, the queries still varied. */
    private static final double TEMPERATURE = 0.2;

    /** The orchestrator could not produce a usable decision (model failure or invalid answers). */
    public static final class UnavailableException extends Exception {
        public UnavailableException(String message) {
            super(message);
        }
    }

    private final MainModelChat chat;
    private final int repairAttempts;
    private final int maxOutputTokens;

    /**
     * @param chat            the orchestrator role's chat (its tokens booked on the run)
     * @param repairAttempts  how often an invalid answer is sent back with the reason (a setting)
     * @param maxOutputTokens the output budget of one decision (the agent's model-turn budget setting)
     */
    public ResearchOrchestrator(MainModelChat chat, int repairAttempts, int maxOutputTokens) {
        this.chat = chat;
        this.repairAttempts = Math.max(0, repairAttempts);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    /** The initial plan for an approved question. */
    public RunPlan plan(OrchestratorRun run, List<WorkerRoleInfo> roles, int maxTasks)
            throws UnavailableException {
        OrchestratorDecisionCodec.Constraints constraints = new OrchestratorDecisionCodec.Constraints(
                names(roles), run.outline().sectionIds(), null, null, maxTasks);
        String user = "TASK: plan the research. Propose at most " + maxTasks + " work packages that together "
                + "cover every outline section.\n\n"
                + question(run) + outline(run) + roles(roles);
        String answer = null;
        String schema = OrchestratorDecisionCodec.planSchema(constraints);
        List<ChatMessage> messages = start(user);
        for (int attempt = 0; ; attempt++) {
            answer = call(messages, schema);
            try {
                return OrchestratorDecisionCodec.readPlan(answer, constraints);
            } catch (OrchestratorDecisionCodec.InvalidDecisionException invalid) {
                if (attempt >= repairAttempts) {
                    throw new UnavailableException("invalid plan: " + invalid.getMessage());
                }
                repair(messages, answer, invalid.getMessage());
            }
        }
    }

    /** The decision after a round, on the run's tasks and the measured evidence. */
    public RoundDecision evaluate(OrchestratorRun run, List<ResearchTask> tasks, EvidenceSnapshot evidence,
                                  List<WorkerRoleInfo> roles, List<String> evaluated, int maxFollowUps)
            throws UnavailableException {
        List<String> known = new ArrayList<String>();
        for (ResearchTask task : tasks) {
            if (run.owns(task.getTaskId())) {
                known.add(run.plannedIdOf(task.getTaskId()));
            }
        }
        OrchestratorDecisionCodec.Constraints constraints = new OrchestratorDecisionCodec.Constraints(
                names(roles), run.outline().sectionIds(), known, evaluated, maxFollowUps);
        String user = "TASK: evaluate round " + (run.getRoundsUsed() + 1) + ". Judge each package listed under "
                + "EVALUATE, name gaps and contradictions per section, check the quality, then choose the action. "
                + (maxFollowUps > 0 ? "Follow-up packages (at most " + maxFollowUps + ") only for real gaps; "
                + "new ids, never one of the existing ids." : "No follow-up packages are possible now.")
                + "\n\n" + question(run) + outline(run) + roles(roles) + budget(run)
                + measurements(evidence) + tasks(run, tasks, evaluated)
                + previousFindings(run);
        String schema = OrchestratorDecisionCodec.roundSchema(constraints);
        List<ChatMessage> messages = start(user);
        for (int attempt = 0; ; attempt++) {
            String answer = call(messages, schema);
            try {
                return OrchestratorDecisionCodec.readRound(answer, constraints);
            } catch (OrchestratorDecisionCodec.InvalidDecisionException invalid) {
                if (attempt >= repairAttempts) {
                    throw new UnavailableException("invalid round decision: " + invalid.getMessage());
                }
                repair(messages, answer, invalid.getMessage());
            }
        }
    }

    private List<ChatMessage> start(String user) {
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM));
        messages.add(ChatMessage.user(user));
        return messages;
    }

    private String call(List<ChatMessage> messages, String schema) throws UnavailableException {
        MainModelChatResult result = chat.completeJson(messages, TEMPERATURE, maxOutputTokens, schema);
        if (!result.isOk()) {
            throw new UnavailableException("orchestrator model call failed (" + result.getStatus() + "): "
                    + result.getDetail());
        }
        return result.getText();
    }

    private static void repair(List<ChatMessage> messages, String answer, String reason) {
        messages.add(ChatMessage.assistant(answer == null ? "" : answer));
        messages.add(ChatMessage.user("Your answer was rejected: " + reason
                + "\nAnswer again with the corrected JSON object only."));
    }

    // ---- context rendering -------------------------------------------------------------------------------

    private static String question(OrchestratorRun run) {
        return "QUESTION\n" + run.getQuestion() + "\n\n";
    }

    private static String outline(OrchestratorRun run) {
        StringBuilder sb = new StringBuilder("OUTLINE (section id: title)\n");
        for (ResearchOutline.Section section : run.outline().getSections()) {
            sb.append("- ").append(section.getId()).append(": ").append(section.getTitle()).append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String roles(List<WorkerRoleInfo> roles) {
        StringBuilder sb = new StringBuilder("WORKER ROLES\n");
        for (WorkerRoleInfo role : roles) {
            sb.append("- ").append(role.getId()).append(": ").append(role.getPurpose()).append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String budget(OrchestratorRun run) {
        return "BUDGET\nrounds used " + run.getRoundsUsed() + " of " + run.getMaxRounds()
                + "; packages used " + run.getTasksSubmitted() + " of " + run.getMaxTasks() + "\n\n";
    }

    private static String measurements(EvidenceSnapshot evidence) {
        StringBuilder sb = new StringBuilder("MEASUREMENTS (deterministic)\n");
        sb.append("sources ").append(evidence.getDistinctSources()).append(" (required ")
                .append(evidence.getMinimumSources()).append("), hosts ").append(evidence.getDistinctHosts())
                .append(" (required ").append(evidence.getMinimumHosts()).append("), open user questions ")
                .append(evidence.getOpenQuestions()).append('\n');
        for (EvidenceSnapshot.SectionCoverage section : evidence.getSections()) {
            sb.append("- ").append(section.getId()).append(": sources ").append(section.getSources())
                    .append(" (required ").append(evidence.getMinimumSourcesPerSection()).append("), hosts ")
                    .append(section.getHosts()).append(", open packages ").append(section.getOpenTasks())
                    .append(", failed packages ").append(section.getFailedTasks()).append('\n');
        }
        sb.append(evidence.isSufficient() ? "verdict of the measurements: sufficient\n"
                : "verdict of the measurements: NOT sufficient (" + evidence.insufficiencyReason() + ")\n");
        return sb.append('\n').toString();
    }

    private static String tasks(OrchestratorRun run, List<ResearchTask> tasks, List<String> evaluated) {
        StringBuilder sb = new StringBuilder("PACKAGES\n");
        for (ResearchTask task : tasks) {
            if (!run.owns(task.getTaskId())) {
                continue;
            }
            String id = run.plannedIdOf(task.getTaskId());
            sb.append(evaluated.contains(id) ? "* EVALUATE " : "- ").append(id).append(" [")
                    .append(task.getRole()).append(", ").append(task.getState().wireName()).append("] query: ")
                    .append(TaskBriefs.queryOf(task.getInputs())).append("; sections: ")
                    .append(TaskBriefs.sectionsOf(task.getInputs())).append('\n');
            int sources = 0;
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() == TaskArtifact.Kind.SOURCE_REFERENCE) {
                    sources++;
                    if (sources <= 8) {
                        sb.append("    source: ").append(oneLine(artifact.getContent(), 200)).append('\n');
                    }
                } else if (artifact.getKind() == TaskArtifact.Kind.FINDING) {
                    // Evidence passages are already bounded by the evidence setting when the worker records them.
                    if (sources <= 8) {
                        sb.append("      evidence: ").append(oneLine(artifact.getContent(), Integer.MAX_VALUE))
                                .append('\n');
                    }
                } else {
                    sb.append("    ").append(artifact.getKind().name().toLowerCase(java.util.Locale.ROOT))
                            .append(": ").append(oneLine(artifact.getContent(), 300)).append('\n');
                }
            }
            if (sources > 8) {
                sb.append("    (").append(sources - 8).append(" more sources)\n");
            }
            if (task.isTerminal() && task.getState() != TaskState.COMPLETED) {
                List<TaskTransition> history = task.getTransitions();
                sb.append("    ended: ").append(oneLine(history.get(history.size() - 1).getReason(), 200))
                        .append('\n');
            }
        }
        return sb.append('\n').toString();
    }

    private static String previousFindings(OrchestratorRun run) {
        StringBuilder sb = new StringBuilder();
        if (!run.getUserNotes().isEmpty()) {
            sb.append("USER ANSWERS\n");
            for (String note : run.getUserNotes()) {
                sb.append("- ").append(note).append('\n');
            }
            sb.append('\n');
        }
        if (run.getOpenGaps().isEmpty() && run.getContradictions().isEmpty()) {
            return sb.toString();
        }
        sb.append("PREVIOUS ROUND\n");
        for (RoundDecision.Finding gap : run.getOpenGaps()) {
            sb.append("- gap ").append(gap.getSection()).append(": ").append(gap.getDescription()).append('\n');
        }
        for (RoundDecision.Finding contradiction : run.getContradictions()) {
            sb.append("- contradiction ").append(contradiction.getSection()).append(": ")
                    .append(contradiction.getDescription()).append('\n');
        }
        return sb.append('\n').toString();
    }

    private static String oneLine(String text, int max) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return flat.length() <= max ? flat : flat.substring(0, max) + "…";
    }

    private static List<String> names(List<WorkerRoleInfo> roles) {
        List<String> names = new ArrayList<String>();
        for (WorkerRoleInfo role : roles) {
            names.add(role.getId());
        }
        return names;
    }

    /** A worker role as the orchestrator sees it: its id and what it is for. */
    public static final class WorkerRoleInfo {
        private final String id;
        private final String purpose;

        public WorkerRoleInfo(String id, String purpose) {
            this.id = id;
            this.purpose = purpose == null ? "" : purpose;
        }

        public static WorkerRoleInfo of(ResearchRole role) {
            return new WorkerRoleInfo(role.getId(), role.getPurpose());
        }

        public String getId() {
            return id;
        }

        public String getPurpose() {
            return purpose;
        }
    }
}
