package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;
import com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.loop.ResearchStopReason;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.role.RoleToolCatalog;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * The web research worker: one work package = one search run of the session's acquisition funnel for the
 * package's query (ADR 0001, 4.1: "web/literature/technical research workers (acquisition runner + readiness
 * judge)"). Each accepted source becomes a {@code SOURCE_REFERENCE} artifact of the task (project ref = the
 * source id, content = URL and title), the run's stop line a {@code TEXT} artifact. The worker adds nothing to
 * the acquisition: what is searched, opened and accepted is decided by the funnel's gates exactly as in the
 * single autonomous loop.
 */
public final class WebResearchWorker implements TaskWorker {

    /**
     * The role: its model use is the acquisition runner's page readiness judge (the judge slot); its tools on
     * the research-control lane are reading the status and the sources and accepting or parking a source. The
     * requests carry their prompts (the judge's), hence {@code CALLER}.
     */
    public static final ResearchRole ROLE = ResearchRole.builder("web-researcher", ResearchModelSlots.JUDGE)
            .purpose("searches the web for one query through the research funnel and accepts relevant sources")
            .promptFromCaller()
            .tools(RoleToolCatalog.of("research_status", "source_list", "source_accept", "source_park"))
            .output(RoleOutput.text("WebAcquisition result (stop reason + accepted source references)"))
            .thinking(com.aresstack.askai.agent.model.port.ThinkingMode.OFF)
            .build();

    private final WebAcquisition acquisition;
    private final com.aresstack.askai.research.runtime.loop.ToolInvoker research;
    private final int evidenceChars;

    /**
     * @param acquisition the session's funnel
     * @param research    the session's research-control lane (restricted per task to the role's tools)
     */
    public WebResearchWorker(WebAcquisition acquisition,
                             com.aresstack.askai.research.runtime.loop.ToolInvoker research) {
        this(acquisition, research, OrchestratorSettings.DEFAULT_EVIDENCE_CHARS);
    }

    /** @param evidenceChars how much of an accepted page is kept as the evaluation's evidence (a setting) */
    public WebResearchWorker(WebAcquisition acquisition,
                             com.aresstack.askai.research.runtime.loop.ToolInvoker research, int evidenceChars) {
        this.acquisition = acquisition;
        this.research = research;
        this.evidenceChars = Math.max(1, evidenceChars);
    }

    @Override
    public ResearchRole role() {
        return ROLE;
    }

    @Override
    public boolean needsBrowser() {
        return true;
    }

    @Override
    public WorkerResult execute(ResearchTask task, final WorkerContext context) {
        // Lane G: a package that depends on a search planner searches the planned queries instead of its own
        // (SEQUENTIAL inside the package chain); every query is one run of the same funnel.
        List<String> queries = WorkerArtifacts.plannedQueries(context.dependencies());
        if (queries.isEmpty()) {
            if (context.query().isEmpty()) {
                return WorkerResult.failed("the package has no query");
            }
            queries = java.util.Collections.singletonList(context.query());
        }
        final AtomicInteger accepted = new AtomicInteger();
        List<String> lines = new ArrayList<String>();
        WorkerResult outcome = null;
        // The task's time budget bounds every search run while it runs, not only afterwards.
        long timeLimit = task.getBudget().getTimeLimitMillis();
        long attemptStart = System.nanoTime();
        for (int i = 0; i < queries.size(); i++) {
            if (i > 0 && (context.isCancelled() || context.isBudgetExhausted())) {
                lines.add((queries.size() - i) + " planned quer" + (queries.size() - i == 1 ? "y" : "ies")
                        + " not searched (" + (context.isCancelled() ? "run stopped" : "task budget spent") + ")");
                break;
            }
            final String query = queries.get(i);
            int before = accepted.get();
            context.log("search: " + query);
            long elapsed = (System.nanoTime() - attemptStart) / 1_000_000L;
            long remaining = timeLimit <= 0 ? 0L
                    : Math.max(1L, timeLimit - task.getBudget().getTimeUsedMillis() - elapsed);
            WebAcquisition.Result result = acquisition.acquire(query, context.permitted(research),
                    new Consumer<WebSearchApplicationService.AcceptedSource>() {
                        public void accept(WebSearchApplicationService.AcceptedSource source) {
                            if (source.getSourceId() == null || source.getSourceId().isEmpty()) {
                                return;
                            }
                            accepted.incrementAndGet();
                            String title = source.getPageTitle() == null ? "" : source.getPageTitle().trim();
                            context.artifact(TaskArtifact.Kind.SOURCE_REFERENCE,
                                    source.getPageUrl() + (title.isEmpty() ? "" : " " + title), source.getSourceId());
                            context.receipt("source_accept", true, "accepted " + source.getSourceId()
                                    + (source.isDuplicate() ? " (duplicate)" : ""));
                            // The orchestrator has no source access: the passages that match the package's query
                            // are its evidence for gaps, contradictions and quality.
                            String evidence = excerpt(source.getPage(), query, evidenceChars);
                            if (!evidence.isEmpty()) {
                                context.artifact(TaskArtifact.Kind.FINDING, evidence, source.getSourceId());
                            }
                        }
                    }, remaining, context.tokens(), context::isBudgetExhausted);
            ResearchStopReason reason = result.getStopReason();
            String line = (queries.size() > 1 ? "\"" + query + "\": " : "") + "search run stopped: " + reason + ", "
                    + (accepted.get() - before) + " source(s) accepted"
                    + (result.getSummary().isEmpty() ? "" : "; " + result.getSummary());
            context.artifact(TaskArtifact.Kind.TEXT, line, "");
            lines.add(line);
            WorkerResult mapped = map(reason, line);
            if (mapped.getKind() == WorkerResult.Kind.FAILED || mapped.getKind() == WorkerResult.Kind.CANCELED) {
                return mapped; // the configuration or the user ends the package, whatever is left
            }
            if (outcome == null || mapped.getKind() == WorkerResult.Kind.COMPLETED) {
                outcome = mapped;
            }
        }
        if (queries.size() == 1 || outcome == null) {
            return outcome == null ? WorkerResult.canceled("run stopped") : outcome;
        }
        String summary = queries.size() + " planned queries, " + accepted.get() + " source(s) accepted: "
                + String.join("; ", lines);
        return outcome.getKind() == WorkerResult.Kind.COMPLETED ? WorkerResult.completed(summary)
                : WorkerResult.retryable(summary);
    }

    /**
     * The passages of {@code page} that share the most terms with {@code query}, in page order, at most
     * {@code maxChars} characters; the page start when nothing matches.
     */
    static String excerpt(String page, String query, int maxChars) {
        if (page == null || page.trim().isEmpty()) {
            return "";
        }
        java.util.Set<String> terms = new java.util.HashSet<String>();
        for (String term : query.toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (term.length() > 2) {
                terms.add(term);
            }
        }
        String[] passages = page.split("(?<=[.!?])\\s+|\\n+");
        Integer[] order = new Integer[passages.length];
        final int[] score = new int[passages.length];
        for (int i = 0; i < passages.length; i++) {
            order[i] = i;
            for (String word : passages[i].toLowerCase(java.util.Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
                score[i] += terms.contains(word) ? 1 : 0;
            }
        }
        java.util.Arrays.sort(order, (a, b) -> score[b] != score[a] ? score[b] - score[a] : a - b);
        if (order.length == 0 || score[order[0]] == 0) {
            String start = page.trim().replaceAll("\\s+", " ");
            return start.length() <= maxChars ? start : start.substring(0, maxChars);
        }
        java.util.TreeSet<Integer> picked = new java.util.TreeSet<Integer>();
        int length = 0;
        for (Integer i : order) {
            String passage = passages[i].trim();
            if (score[i] == 0 || passage.isEmpty()) {
                break;
            }
            if (length + passage.length() + 1 > maxChars) {
                if (picked.isEmpty()) {
                    picked.add(i);
                }
                continue;
            }
            picked.add(i);
            length += passage.length() + 1;
        }
        StringBuilder sb = new StringBuilder();
        for (Integer i : picked) {
            sb.append(sb.length() == 0 ? "" : " ").append(passages[i].trim().replaceAll("\\s+", " "));
        }
        return sb.length() <= maxChars ? sb.toString() : sb.substring(0, maxChars);
    }

    static WorkerResult map(ResearchStopReason reason, String line) {
        if (reason == null) {
            return WorkerResult.retryable("no stop reason");
        }
        switch (reason) {
            case SUFFICIENT_EVIDENCE:
            case TOOL_BUDGET_EXHAUSTED:
            case PAGE_BUDGET_EXHAUSTED:
            case SOURCE_BUDGET_EXHAUSTED:
            case TIME_BUDGET_EXHAUSTED:
            case ERROR_BUDGET_EXHAUSTED:
            case NO_RELEVANT_PATHS:
            case NO_SEMANTIC_MATCHES:
                // Zero sources is a result too: the orchestrator judges it as a gap.
                return WorkerResult.completed(line);
            case SEARCH_TECHNICAL_PROBLEM:
            case RERANKER_UNAVAILABLE:
            case RERANKER_INVALID_RESPONSE:
            case RERANKER_TIMEOUT:
            case MCP_UNAVAILABLE:
                return WorkerResult.retryable(line);
            case USER_CANCELLED:
                return WorkerResult.canceled(line);
            default:
                // Configuration errors, the scope control down, a changed host state: retrying cannot help.
                return WorkerResult.failed(line);
        }
    }
}
