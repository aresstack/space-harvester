package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The SEARCH PLANNER (ADR 0001, 4.1, lane G): turns one aspect of the approved question into a few distinct,
 * concrete search queries. It never searches itself and has no tools; its queries are {@code planned query}
 * artifacts ({@link WorkerArtifacts}) that a web research package depending on it runs through the ONE funnel
 * (SEQUENTIAL inside the package chain: plan, then search). The answer is schema-bound and checked by code:
 * empty, overlong and duplicate queries (also against what the run already searched) are refused and sent back
 * once (a setting) with the reason. Prompt, schema and reader live in {@link QueryPlanner}.
 */
public final class SearchPlannerWorker implements TaskWorker {

    static final String SYSTEM = QueryPlanner.SYSTEM;

    /** The role: the planner slot, no tools, a per-request schema (its maxItems is the planner setting). */
    public static final ResearchRole ROLE = ResearchRole.builder("search-planner", ResearchModelSlots.PLANNER)
            .purpose("writes distinct search queries for one aspect; a web-researcher package that dependsOn it "
                    + "searches exactly those queries (use it for broad or vague aspects)")
            .systemPrompt(SYSTEM)
            .output(RoleOutput.schemaPerRequest("SearchPlannerWorker.schema"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    static final int MAX_QUERY_LENGTH = QueryPlanner.MAX_QUERY_LENGTH;
    private static final double TEMPERATURE = 0.3;

    private final int maxQueries;
    private final int repairAttempts;
    private final int maxOutputTokens;

    /**
     * @param maxQueries      queries one package may propose (a setting)
     * @param repairAttempts  how often an invalid answer is sent back (a setting)
     * @param maxOutputTokens the output budget of one answer (the agent's model-turn budget)
     */
    public SearchPlannerWorker(int maxQueries, int repairAttempts, int maxOutputTokens) {
        this.maxQueries = Math.max(1, maxQueries);
        this.repairAttempts = Math.max(0, repairAttempts);
        this.maxOutputTokens = Math.max(0, maxOutputTokens);
    }

    @Override
    public ResearchRole role() {
        return ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return false;
    }

    @Override
    public WorkerResult execute(ResearchTask task, WorkerContext context) {
        String aspect = context.query();
        if (aspect.isEmpty()) {
            return WorkerResult.failed("the package names no aspect to plan");
        }
        Set<String> searched = WorkerArtifacts.searchedQueries(context.runTasks(), WebResearchWorker.ROLE.getId());
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(SYSTEM));
        messages.add(ChatMessage.user(prompt(aspect, context.brief(), searched)));
        String schema = schema(maxQueries);
        for (int attempt = 0; ; attempt++) {
            if (context.isCancelled()) {
                return WorkerResult.canceled("run stopped");
            }
            MainModelChatResult result = context.chat().completeJson(messages, TEMPERATURE, maxOutputTokens, schema);
            if (!result.isOk()) {
                return WorkerResult.retryable("planner model call failed (" + result.getStatus() + "): "
                        + result.getDetail());
            }
            try {
                List<String> queries = read(result.getText(), maxQueries, searched);
                for (String query : queries) {
                    context.artifact(TaskArtifact.Kind.TEXT, query, WorkerArtifacts.PLANNED_QUERY_REF);
                }
                context.log("planned " + queries.size() + " quer" + (queries.size() == 1 ? "y" : "ies") + ": "
                        + String.join(" | ", queries));
                return WorkerResult.completed(queries.size() + " search quer" + (queries.size() == 1 ? "y" : "ies")
                        + " planned");
            } catch (WorkerAnswerException invalid) {
                if (attempt >= repairAttempts || context.isBudgetExhausted()) {
                    return WorkerResult.failed("invalid search plan: " + invalid.getMessage());
                }
                messages.add(ChatMessage.assistant(result.getText() == null ? "" : result.getText()));
                messages.add(ChatMessage.user("Your answer was rejected: " + invalid.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    static String prompt(String aspect, String brief, Set<String> searched) {
        return QueryPlanner.prompt(aspect, brief, searched);
    }

    static String schema(int maxQueries) {
        return QueryPlanner.schema(maxQueries);
    }

    /** The strict reader: the distinct new queries, or the reason the answer is unusable. */
    static List<String> read(String answer, int maxQueries, Set<String> searched) throws WorkerAnswerException {
        return QueryPlanner.read(answer, maxQueries, searched);
    }
}
