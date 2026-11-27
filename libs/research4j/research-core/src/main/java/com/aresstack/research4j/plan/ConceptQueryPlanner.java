package com.aresstack.research4j.plan;

import com.aresstack.knowledge4j.run.CancellationSignal;
import com.aresstack.knowledge4j.scope.Fence;
import com.aresstack.research4j.concept.SearchConcept;
import com.aresstack.research4j.run.ModelAsk;
import com.aresstack.research4j.run.Parallel;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The search planner of the concept (ported from Ask AI {@code QueryPlanner.planConcept}): per concept node its
 * deterministic base query plus a few variants the model writes, inside the fence. In a later cycle the node did not
 * get enough sources yet, and the model is told to change the strategy instead of rewording, with the sites the earlier
 * queries found. An invalid answer goes back as often as {@link Settings#repairAttempts()} allows; then the node keeps
 * its base query and the reason is noted. It never throws for a model problem.
 *
 * <p>Every node of one call sees the same list of queries already searched or reserved, so the plan does not depend
 * on how many nodes are planned at the same time; duplicates between nodes are dropped afterwards in node order.</p>
 */
public final class ConceptQueryPlanner {

    /**
     * How the planner works.
     *
     * @param variantsPerNode queries the model may write per node and cycle; 0 = base queries only
     * @param answerTokens    answer budget of one planner call
     * @param repairAttempts  how often an invalid answer goes back before the node keeps its base query
     * @param temperature     sampling temperature of the planner
     * @param minQueryChars   shortest query accepted
     * @param maxQueryChars   longest query accepted
     * @param missionChars    characters of the mission shown in the prompt
     */
    public record Settings(int variantsPerNode, int answerTokens, int repairAttempts, double temperature,
                           int minQueryChars, int maxQueryChars, int missionChars) {
        public Settings {
            if (variantsPerNode < 0 || answerTokens < 1 || repairAttempts < 0 || temperature < 0
                    || minQueryChars < 1 || maxQueryChars < minQueryChars || missionChars < 1) {
                throw new IllegalArgumentException("invalid planner settings");
            }
        }

        /** The values of Ask AI: 3 variants, 256 answer tokens, one repair, 0.3, queries of 3 to 160 characters. */
        public static Settings defaults() {
            return new Settings(3, 256, 1, 0.3, 3, 160, 1200);
        }
    }

    /** What one node asks for in a cycle. */
    public record NodeRequest(SearchConcept.Node node, List<String> sitesFound) {
        public NodeRequest {
            sitesFound = sitesFound == null ? List.of() : List.copyOf(sitesFound);
        }
    }

    /** The plan of one node: its queries in order and why the model's variants are missing, if they are. */
    public record NodePlan(String nodeId, List<PlannedQuery> queries, String note) {
    }

    static final String SYSTEM = "You are the search planner of a research assistant. You get ONE node of the "
            + "research's concept and its base search query, and write a few other search queries that find sources "
            + "for that node. You never search yourself.\n"
            + "Rules:\n"
            + "- Answer with the JSON object the schema asks for, nothing else.\n"
            + "- Write every query in the LANGUAGE given.\n"
            + "- Each query is what a person would type into a search engine: 3 to 10 words, concrete terms; no full "
            + "sentences, no operators.\n"
            + "- The queries differ from the base query and from each other in angle, never only in word order.\n"
            + "- Never repeat a query listed under ALREADY SEARCHED.\n"
            + "- Stay inside the node; do not widen the topic.\n"
            + "- FENCE is the bound of the research: every query stays inside what it includes and never aims at what "
            + "it excludes.\n"
            + "- When RESEARCH CYCLE is above 1, the earlier queries did not find enough sources for the node: change "
            + "the strategy instead of rewording. Name the institutions, authorities, associations, standards bodies, "
            + "universities, journals or publishers that publish on the node's subject, the kinds of document that "
            + "carry evidence (study, guideline, regulation, standard, report, statistics) and concrete sub-aspects, "
            + "people or years. Never aim at shops, booksellers or the sites listed under SITES FOUND SO FAR.";

    private final ModelAsk model;
    private final Settings settings;
    private final String language;
    private final Fence fence;

    public ConceptQueryPlanner(ModelAsk model, Settings settings, String language, Fence fence) {
        this.model = model;
        this.settings = settings == null ? Settings.defaults() : settings;
        this.language = language == null ? "" : language;
        this.fence = fence == null ? Fence.of("") : fence;
    }

    /**
     * Plans the nodes of one cycle.
     *
     * @param concept  the concept the nodes belong to
     * @param requests the nodes to plan, in concept order
     * @param cycle    the research cycle, from 1
     * @param searched every query run so far (no query repeats one of them)
     * @param parallel model calls at the same time, as the host's capacity allows
     */
    public List<NodePlan> plan(SearchConcept concept, List<NodeRequest> requests, int cycle,
                               Collection<String> searched, int parallel, CancellationSignal cancel) {
        Set<String> known = new LinkedHashSet<>();
        for (String query : searched) {
            known.add(normalize(query));
        }
        Map<String, String> bases = new LinkedHashMap<>();
        for (NodeRequest request : requests) {
            String base = baseQuery(concept, request.node());
            bases.put(request.node().id(), base);
        }
        Set<String> reserved = new LinkedHashSet<>(known);
        for (String base : bases.values()) {
            if (!base.isEmpty()) {
                reserved.add(normalize(base));
            }
        }
        List<ModelAsk.Outcome<List<String>>> answers = Parallel.map(requests, parallel, cancel, request ->
                settings.variantsPerNode() == 0
                        ? new ModelAsk.Outcome<>(List.<String>of(), "")
                        : model.ask(SYSTEM, prompt(concept, request, bases.get(request.node().id()), cycle, reserved),
                        schema(), settings.temperature(), settings.answerTokens(), settings.repairAttempts(),
                        answer -> read(answer, reserved)));
        Set<String> taken = new LinkedHashSet<>(known);
        List<NodePlan> plans = new ArrayList<>();
        for (int i = 0; i < requests.size(); i++) {
            String nodeId = requests.get(i).node().id();
            List<PlannedQuery> queries = new ArrayList<>();
            String base = bases.get(nodeId);
            if (!base.isEmpty() && taken.add(normalize(base))) {
                queries.add(new PlannedQuery(nodeId, base, true));
            }
            ModelAsk.Outcome<List<String>> answer = answers.get(i);
            if (answer.ok()) {
                for (String variant : answer.value()) {
                    if (taken.add(normalize(variant))) {
                        queries.add(new PlannedQuery(nodeId, variant, false));
                    }
                }
            }
            plans.add(new NodePlan(nodeId, List.copyOf(queries),
                    answer.ok() ? "" : "base query only: " + answer.problem()));
        }
        return plans;
    }

    /** The node's base query, or its label when the base query is unusable; empty when neither fits. */
    String baseQuery(SearchConcept concept, SearchConcept.Node node) {
        for (String candidate : new String[]{node.baseQuery(), node.label()}) {
            String query = candidate.replaceAll("\\s+", " ").trim();
            if (fits(query)) {
                return query;
            }
        }
        return "";
    }

    String prompt(SearchConcept concept, NodeRequest request, String base, int cycle, Collection<String> searched) {
        boolean german = language.toLowerCase(Locale.ROOT).startsWith("de");
        StringBuilder sb = new StringBuilder("LANGUAGE\n").append(languageName(language)).append("\n\n");
        sb.append("FENCE\n").append(fence.describe(german, settings.missionChars())).append('\n');
        SearchConcept.Node node = request.node();
        sb.append("NODE\n").append(node.label()).append('\n');
        List<String> path = concept.path(node);
        if (path.size() > 1) {
            sb.append("PATH\n").append(String.join(" > ", path)).append('\n');
        }
        sb.append("\nBASE QUERY\n").append(base.isEmpty() ? "(none)" : base).append("\n\n");
        if (cycle > 1) {
            sb.append("RESEARCH CYCLE\n").append(cycle)
                    .append(" (the queries under ALREADY SEARCHED left this node without enough sources)\n\n");
            list(sb, "SITES FOUND SO FAR", request.sitesFound(), "(none)");
            sb.append('\n');
        }
        list(sb, "ALREADY SEARCHED", searched, "(nothing yet)");
        sb.append("\nTASK: write at most ").append(settings.variantsPerNode())
                .append(" other search queries for the node, in ").append(languageName(language)).append('.');
        if (cycle > 1) {
            sb.append(" Take another angle than the queries already searched: other publishers of evidence, other "
                    + "kinds of document, other sub-aspects.");
        }
        return sb.toString();
    }

    String schema() {
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("minLength", settings.minQueryChars());
        query.addProperty("maxLength", settings.maxQueryChars());
        JsonObject queries = new JsonObject();
        queries.addProperty("type", "array");
        queries.add("items", query);
        queries.addProperty("minItems", 1);
        queries.addProperty("maxItems", settings.variantsPerNode());
        JsonObject props = new JsonObject();
        props.add("queries", queries);
        JsonObject root = new JsonObject();
        root.addProperty("type", "object");
        root.add("properties", props);
        JsonArray required = new JsonArray();
        required.add("queries");
        root.add("required", required);
        root.addProperty("additionalProperties", false);
        return root.toString();
    }

    /** The strict reader: the distinct new queries, or the reason the answer is unusable. */
    List<String> read(JsonObject root, Collection<String> known) throws ModelAsk.Rejected {
        JsonElement element = root.get("queries");
        if (element == null || !element.isJsonArray()) {
            throw new ModelAsk.Rejected("\"queries\" must be an array of strings");
        }
        Map<String, String> distinct = new LinkedHashMap<>();
        List<String> refused = new ArrayList<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new ModelAsk.Rejected("every query must be a string");
            }
            String query = item.getAsString().replaceAll("\\s+", " ").trim();
            String key = normalize(query);
            if (!fits(query)) {
                refused.add("\"" + query + "\" (length)");
            } else if (known.contains(key)) {
                refused.add("\"" + query + "\" (already searched)");
            } else if (distinct.containsKey(key)) {
                refused.add("\"" + query + "\" (duplicate)");
            } else {
                distinct.put(key, query);
            }
        }
        if (distinct.isEmpty()) {
            throw new ModelAsk.Rejected("no usable new query"
                    + (refused.isEmpty() ? "" : "; refused: " + String.join(", ", refused)));
        }
        List<String> queries = new ArrayList<>(distinct.values());
        return queries.size() > settings.variantsPerNode() ? queries.subList(0, settings.variantsPerNode()) : queries;
    }

    private boolean fits(String query) {
        return query.length() >= settings.minQueryChars() && query.length() <= settings.maxQueryChars();
    }

    /** The form two queries are compared in: trimmed, single spaces, lower case. */
    public static String normalize(String query) {
        return query == null ? "" : query.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String languageName(String code) {
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("de")) {
            return "German";
        }
        if (c.isEmpty() || c.startsWith("en")) {
            return "English";
        }
        return Locale.forLanguageTag(c).getDisplayLanguage(Locale.ENGLISH);
    }

    private static void list(StringBuilder sb, String title, Collection<String> values, String empty) {
        sb.append(title).append('\n');
        if (values.isEmpty()) {
            sb.append(empty).append('\n');
        }
        for (String value : values) {
            sb.append("- ").append(value).append('\n');
        }
    }
}
