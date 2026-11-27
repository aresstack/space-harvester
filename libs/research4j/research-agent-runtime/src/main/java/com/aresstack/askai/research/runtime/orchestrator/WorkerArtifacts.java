package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.research.domain.task.ResearchTask;
import com.aresstack.askai.research.domain.task.TaskArtifact;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The BLACKBOARD conventions the lane G worker roles read and write (ADR 0001, 4.3), next to the ones of
 * {@link TaskBriefs} and the {@code SOURCE_REFERENCE} convention of the {@link WebResearchWorker}:
 *
 * <ul>
 *   <li><b>planned query</b>: a {@code TEXT} artifact of the search planner with project ref
 *       {@value #PLANNED_QUERY_REF}, content = one search query. A web research package that depends on a
 *       planner package runs these queries instead of its own.</li>
 *   <li><b>source verdict</b>: a {@code VERDICT} artifact of the source verifier with project ref = the source
 *       id and content {@code <verdict>: <reason>}. {@link Verdict#OFF_TOPIC} and {@link Verdict#UNRELIABLE}
 *       mean rejected: such a source no longer counts as evidence ({@link EvidenceMeter}) and is not written
 *       from.</li>
 *   <li><b>supported claim</b>: a {@code FINDING} artifact of the verifier, project ref = the source id,
 *       content = the claim the source supports.</li>
 *   <li><b>contradiction</b>: a {@code VERDICT} artifact with project ref
 *       {@code contradiction:<section>} and content {@code <description> (sources a, b)}.</li>
 *   <li><b>draft section</b>: a {@code TEXT} artifact of the writer with project ref
 *       {@code draft:<section>}, content = the section's paragraphs with their {@code [source-id]}
 *       citations.</li>
 * </ul>
 */
public final class WorkerArtifacts {

    public static final String PLANNED_QUERY_REF = "planned-query";
    public static final String CONTRADICTION_PREFIX = "contradiction:";
    public static final String DRAFT_PREFIX = "draft:";
    public static final String GAP_PREFIX = "gap:";

    /** The verifier's judgement of one source. */
    public enum Verdict {
        /** The source supports the brief with concrete content. */
        SUPPORTS("supports", false),
        /** The source touches the brief, but only partly or vaguely. */
        PARTIAL("partial", false),
        /** The source is not about the brief (a distractor that passed the gates). */
        OFF_TOPIC("off-topic", true),
        /** The source is about the brief but not trustworthy (spam, contradicts itself, no substance). */
        UNRELIABLE("unreliable", true),
        /** No readable text was captured; nothing can be said. */
        UNREADABLE("unreadable", false);

        private final String wireName;
        private final boolean rejected;

        Verdict(String wireName, boolean rejected) {
            this.wireName = wireName;
            this.rejected = rejected;
        }

        public String wireName() {
            return wireName;
        }

        /** A rejected source does not count as evidence and is not written from. */
        public boolean isRejected() {
            return rejected;
        }

        public static Verdict fromWireName(String value) {
            if (value != null) {
                for (Verdict verdict : values()) {
                    if (verdict.wireName.equals(value.trim().toLowerCase(Locale.ROOT))) {
                        return verdict;
                    }
                }
            }
            return null;
        }

        public static List<String> wireNames() {
            List<String> names = new ArrayList<String>();
            for (Verdict verdict : values()) {
                names.add(verdict.wireName);
            }
            return names;
        }
    }

    /** A source an upstream package accepted: its id, URL and title, as the {@code SOURCE_REFERENCE} holds it. */
    public static final class SourceRef {
        private final String sourceId;
        private final String url;
        private final String title;

        SourceRef(String sourceId, String content) {
            this.sourceId = sourceId;
            String trimmed = content == null ? "" : content.trim();
            int space = trimmed.indexOf(' ');
            this.url = space < 0 ? trimmed : trimmed.substring(0, space);
            this.title = space < 0 ? "" : trimmed.substring(space + 1).trim();
        }

        public String getSourceId() {
            return sourceId;
        }

        public String getUrl() {
            return url;
        }

        public String getTitle() {
            return title;
        }
    }

    private WorkerArtifacts() {
    }

    /** The planned queries of the planner packages among {@code tasks}, distinct, in order. */
    public static List<String> plannedQueries(List<ResearchTask> tasks) {
        Map<String, String> queries = new LinkedHashMap<String, String>();
        for (ResearchTask task : tasks) {
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() == TaskArtifact.Kind.TEXT
                        && PLANNED_QUERY_REF.equals(artifact.getProjectRef())) {
                    String query = artifact.getContent().trim();
                    if (!query.isEmpty()) {
                        queries.putIfAbsent(normalize(query), query);
                    }
                }
            }
        }
        return new ArrayList<String>(queries.values());
    }

    /** The source ids a verifier rejected anywhere among {@code tasks}. */
    public static Set<String> rejectedSources(List<ResearchTask> tasks) {
        Set<String> rejected = new HashSet<String>();
        for (Map.Entry<String, Verdict> verdict : verdicts(tasks).entrySet()) {
            if (verdict.getValue().isRejected()) {
                rejected.add(verdict.getKey());
            }
        }
        return rejected;
    }

    /** The latest verdict per source id among {@code tasks}. */
    public static Map<String, Verdict> verdicts(List<ResearchTask> tasks) {
        Map<String, Verdict> verdicts = new HashMap<String, Verdict>();
        Map<String, Long> at = new HashMap<String, Long>();
        for (ResearchTask task : tasks) {
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() != TaskArtifact.Kind.VERDICT || artifact.getProjectRef().isEmpty()
                        || artifact.getProjectRef().startsWith(CONTRADICTION_PREFIX)
                        || artifact.getProjectRef().startsWith(GAP_PREFIX)) {
                    continue;
                }
                String content = artifact.getContent();
                int colon = content.indexOf(':');
                Verdict verdict = Verdict.fromWireName(colon < 0 ? content : content.substring(0, colon));
                Long previous = at.get(artifact.getProjectRef());
                if (verdict != null && (previous == null || previous <= artifact.getCreatedAtMillis())) {
                    verdicts.put(artifact.getProjectRef(), verdict);
                    at.put(artifact.getProjectRef(), artifact.getCreatedAtMillis());
                }
            }
        }
        return verdicts;
    }

    /**
     * The supported claims a verifier recorded per source id among {@code tasks}. Only the verifier's findings
     * count: the web researcher's findings are page excerpts (the orchestrator's evidence), not verified claims.
     */
    public static Map<String, List<String>> claims(List<ResearchTask> tasks) {
        Map<String, List<String>> claims = new HashMap<String, List<String>>();
        for (ResearchTask task : tasks) {
            if (!SourceVerifierWorker.ROLE.getId().equals(task.getRole())) {
                continue;
            }
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() == TaskArtifact.Kind.FINDING && !artifact.getProjectRef().isEmpty()) {
                    claims.computeIfAbsent(artifact.getProjectRef(), id -> new ArrayList<String>())
                            .add(artifact.getContent());
                }
            }
        }
        return claims;
    }

    /**
     * The sources a worker package works on: the {@code SOURCE_REFERENCE}s of its dependencies, followed
     * through the dependency chain (a writer that depends on a verifier that depends on web research gets the
     * web research's sources). A package without dependencies takes the completed packages of its run that
     * serve one of its sections (all of them when it names no section), so a missing {@code dependsOn} in the
     * orchestrator's plan degrades instead of leaving the worker empty-handed. Distinct by source id, in order.
     */
    public static List<SourceRef> upstreamSources(ResearchTask self, List<ResearchTask> runTasks) {
        Map<String, ResearchTask> byId = new HashMap<String, ResearchTask>();
        for (ResearchTask task : runTasks) {
            byId.put(task.getTaskId(), task);
        }
        List<ResearchTask> upstream = new ArrayList<ResearchTask>();
        if (!self.getDependsOn().isEmpty()) {
            Set<String> seen = new HashSet<String>();
            collect(self.getDependsOn(), byId, seen, upstream);
        } else {
            Set<String> mine = new HashSet<String>(TaskBriefs.sectionsOf(self.getInputs()));
            for (ResearchTask task : runTasks) {
                if (task.getTaskId().equals(self.getTaskId())
                        || task.getState() != com.aresstack.askai.research.domain.task.TaskState.COMPLETED) {
                    continue;
                }
                if (mine.isEmpty() || !Collections.disjoint(mine, TaskBriefs.sectionsOf(task.getInputs()))) {
                    upstream.add(task);
                }
            }
        }
        Map<String, SourceRef> sources = new LinkedHashMap<String, SourceRef>();
        for (ResearchTask task : upstream) {
            for (TaskArtifact artifact : task.getArtifacts()) {
                if (artifact.getKind() == TaskArtifact.Kind.SOURCE_REFERENCE && !artifact.getProjectRef().isEmpty()) {
                    sources.putIfAbsent(artifact.getProjectRef(),
                            new SourceRef(artifact.getProjectRef(), artifact.getContent()));
                }
            }
        }
        return new ArrayList<SourceRef>(sources.values());
    }

    private static void collect(List<String> ids, Map<String, ResearchTask> byId, Set<String> seen,
                                List<ResearchTask> out) {
        for (String id : ids) {
            ResearchTask task = byId.get(id);
            if (task == null || !seen.add(id)) {
                continue;
            }
            out.add(task);
            collect(task.getDependsOn(), byId, seen, out);
        }
    }

    /** The queries the run's packages already searched (own queries of web research packages, planned ones). */
    public static Set<String> searchedQueries(List<ResearchTask> runTasks, String webResearchRole) {
        Set<String> queries = new LinkedHashSet<String>();
        for (ResearchTask task : runTasks) {
            if (webResearchRole.equals(task.getRole())) {
                String query = TaskBriefs.queryOf(task.getInputs());
                if (!query.isEmpty()) {
                    queries.add(query);
                }
            }
        }
        queries.addAll(plannedQueries(runTasks));
        return queries;
    }

    /** Case and whitespace insensitive comparison key of a query. */
    public static String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
