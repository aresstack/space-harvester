package com.aresstack.askai.research.domain.plan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The concept query plan (plan PR 13): for each node of the frozen concept, its deterministic base query plus the
 * variants the planner model wrote. The host asks with a {@link Request} over the {@code plan_queries} service
 * command and gets a {@link Result} back; the queue of PR 14 runs it. Immutable value types, Java 8.
 */
public final class ConceptQueryPlan {

    /** The shortest and longest query the plan accepts (base queries included). */
    public static final int MIN_QUERY_LENGTH = 3;
    public static final int MAX_QUERY_LENGTH = 160;

    private ConceptQueryPlan() {
    }

    /**
     * One concept node: id, label, its path from the root (labels), its parent id and its base query. In a later
     * research cycle it also names the domains whose pages the earlier queries already found for it, so the planner
     * can take another angle.
     */
    public static final class Node {
        private final String id;
        private final String label;
        private final List<String> path;
        private final String parentId;
        private final String baseQuery;
        private final List<String> foundDomains;
        private final List<String> workerNotes;
        private final List<String> evidence;

        public Node(String id, String label, List<String> path, String parentId, String baseQuery) {
            this(id, label, path, parentId, baseQuery, null);
        }

        public Node(String id, String label, List<String> path, String parentId, String baseQuery,
                    List<String> foundDomains) {
            this(id, label, path, parentId, baseQuery, foundDomains, null);
        }

        /**
         * @param workerNotes what the research team's page workers reported to the orchestrator about this node in
         *                    the earlier cycles (findings, gaps, suggested searches); empty without a team
         */
        public Node(String id, String label, List<String> path, String parentId, String baseQuery,
                    List<String> foundDomains, List<String> workerNotes) {
            this(id, label, path, parentId, baseQuery, foundDomains, workerNotes, null);
        }

        /**
         * @param evidence what the evidence analysis of the material read so far says about this node (its
         *                 status, the material and the key terms of what is there, the queries that found nothing);
         *                 empty when no analysis ran
         */
        public Node(String id, String label, List<String> path, String parentId, String baseQuery,
                    List<String> foundDomains, List<String> workerNotes, List<String> evidence) {
            this.evidence = copy(evidence);
            this.workerNotes = copy(workerNotes);
            this.id = text(id);
            this.label = text(label);
            this.path = copy(path);
            this.parentId = text(parentId);
            this.baseQuery = text(baseQuery);
            this.foundDomains = copy(foundDomains);
        }

        public String getId() {
            return id;
        }

        public String getLabel() {
            return label;
        }

        public List<String> getPath() {
            return path;
        }

        /** The parent node id; empty for the root. */
        public String getParentId() {
            return parentId;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        /** The domains of the sources the earlier cycles found for this node (empty in the first cycle). */
        public List<String> getFoundDomains() {
            return foundDomains;
        }

        /** Plan T4: what the page workers reported about this node in the earlier cycles (empty without a team). */
        public List<String> getWorkerNotes() {
            return workerNotes;
        }

        /** The evidence analysis' findings about this node (empty when none ran). */
        public List<String> getEvidence() {
            return evidence;
        }

        /** The same node with the evidence analysis' findings. */
        public Node withEvidence(List<String> findings) {
            return new Node(id, label, path, parentId, baseQuery, foundDomains, workerNotes, findings);
        }
    }

    /** What the host asks for. */
    public static final class Request {
        private final String language;
        private final String bookTitle;
        private final List<Node> nodes;
        private final List<String> queriesRun;
        private final List<String> blacklist;
        private final int maxVariantsPerNode;
        private final int cycle;
        private final String area;
        private final List<String> newTopics;
        private final List<String> preferredSources;

        /**
         * @param language           the language code the queries must be written in ("de", "en")
         * @param bookTitle          the book title of the frozen concept
         * @param nodes              the concept nodes, in concept order
         * @param queriesRun         the queries already run (never planned again)
         * @param blacklist          the blacklist terms (no planned query may contain one)
         * @param maxVariantsPerNode the maximum of model variants per node, besides its base query
         */
        public Request(String language, String bookTitle, List<Node> nodes, List<String> queriesRun,
                       List<String> blacklist, int maxVariantsPerNode) {
            this(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, 1);
        }

        /**
         * @param cycle the research cycle the queries are planned for: 1 is the first plan from the concept; a
         *              later cycle plans only nodes the earlier cycles left uncovered, and asks for another angle
         */
        public Request(String language, String bookTitle, List<Node> nodes, List<String> queriesRun,
                       List<String> blacklist, int maxVariantsPerNode, int cycle) {
            this(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle, "");
        }

        /**
         * @param area the Weidezaun: the rendered scope of the book (what belongs to the area and what it excludes);
         *             every planned query stays inside it. Empty when the host has none.
         */
        public Request(String language, String bookTitle, List<Node> nodes, List<String> queriesRun,
                       List<String> blacklist, int maxVariantsPerNode, int cycle, String area) {
            this(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle, area, null);
        }

        /**
         * @param newTopics the topics the material read so far shows beyond the concept nodes and inside the
         *                  Weidezaun (title and key terms); the planner may write a query for one under the node
         *                  it belongs to. Empty when none.
         */
        public Request(String language, String bookTitle, List<Node> nodes, List<String> queriesRun,
                       List<String> blacklist, int maxVariantsPerNode, int cycle, String area,
                       List<String> newTopics) {
            this(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle, area, newTopics, null);
        }

        /**
         * @param preferredSources the sources the mission prefers (publishers, domains, kinds of document); the
         *                         planner aims queries at them first. Empty when the mission names none.
         */
        public Request(String language, String bookTitle, List<Node> nodes, List<String> queriesRun,
                       List<String> blacklist, int maxVariantsPerNode, int cycle, String area,
                       List<String> newTopics, List<String> preferredSources) {
            this.preferredSources = copy(preferredSources);
            this.newTopics = copy(newTopics);
            this.area = area == null ? "" : area.trim();
            this.language = text(language);
            this.bookTitle = text(bookTitle);
            this.nodes = nodes == null ? Collections.<Node>emptyList()
                    : Collections.unmodifiableList(new ArrayList<Node>(nodes));
            this.queriesRun = copy(queriesRun);
            this.blacklist = copy(blacklist);
            this.maxVariantsPerNode = Math.max(0, maxVariantsPerNode);
            this.cycle = Math.max(1, cycle);
        }

        /** The same request with the book title and blacklist of the frozen concept (the host fills them in). */
        public Request withBook(String title, List<String> terms) {
            return new Request(language, title, nodes, queriesRun, terms, maxVariantsPerNode, cycle, area,
                    newTopics, preferredSources);
        }

        /** The same request with the book's Weidezaun (the host fills it in before sending). */
        public Request withArea(String renderedScope) {
            return new Request(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle,
                    renderedScope, newTopics, preferredSources);
        }

        /** The same request with the new topics of the evidence analysis. */
        public Request withNewTopics(List<String> topics) {
            return new Request(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle, area,
                    topics, preferredSources);
        }

        /** The same request with the sources the mission prefers (the host fills them in before sending). */
        public Request withPreferredSources(List<String> sources) {
            return new Request(language, bookTitle, nodes, queriesRun, blacklist, maxVariantsPerNode, cycle, area,
                    newTopics, sources);
        }

        /** The sources the mission prefers; empty when none. */
        public List<String> getPreferredSources() {
            return preferredSources;
        }

        /** The topics beyond the concept nodes inside the Weidezaun; empty when none. */
        public List<String> getNewTopics() {
            return newTopics;
        }

        /** The Weidezaun of the book; empty when the host sent none. */
        public String getArea() {
            return area;
        }

        public String getLanguage() {
            return language;
        }

        public String getBookTitle() {
            return bookTitle;
        }

        public List<Node> getNodes() {
            return nodes;
        }

        public List<String> getQueriesRun() {
            return queriesRun;
        }

        public List<String> getBlacklist() {
            return blacklist;
        }

        public int getMaxVariantsPerNode() {
            return maxVariantsPerNode;
        }

        /** The research cycle (1 = the first plan). */
        public int getCycle() {
            return cycle;
        }
    }

    /** The plan of one node: its base query, the accepted variants and a note when the planner fell back. */
    public static final class NodePlan {
        private final String nodeId;
        private final String baseQuery;
        private final List<String> variants;
        private final String note;

        public NodePlan(String nodeId, String baseQuery, List<String> variants, String note) {
            this.nodeId = text(nodeId);
            this.baseQuery = text(baseQuery);
            this.variants = copy(variants);
            this.note = text(note);
        }

        public String getNodeId() {
            return nodeId;
        }

        public String getBaseQuery() {
            return baseQuery;
        }

        public List<String> getVariants() {
            return variants;
        }

        /** Why the node has only its base query (invalid answer, model failure); empty otherwise. */
        public String getNote() {
            return note;
        }

        /** The base query (when usable) followed by the variants. */
        public List<String> queries() {
            List<String> all = new ArrayList<String>();
            if (!baseQuery.isEmpty()) {
                all.add(baseQuery);
            }
            all.addAll(variants);
            return all;
        }
    }

    /** The answer: one {@link NodePlan} per requested node, in request order. */
    public static final class Result {
        private final List<NodePlan> nodes;

        public Result(List<NodePlan> nodes) {
            this.nodes = nodes == null ? Collections.<NodePlan>emptyList()
                    : Collections.unmodifiableList(new ArrayList<NodePlan>(nodes));
        }

        public List<NodePlan> getNodes() {
            return nodes;
        }
    }

    private static String text(String value) {
        return value == null ? "" : value.trim();
    }

    private static List<String> copy(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> out = new ArrayList<String>();
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                out.add(value.trim());
            }
        }
        return Collections.unmodifiableList(out);
    }
}
