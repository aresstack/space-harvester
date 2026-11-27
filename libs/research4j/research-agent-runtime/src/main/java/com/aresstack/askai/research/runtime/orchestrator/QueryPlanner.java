package com.aresstack.askai.research.runtime.orchestrator;

import com.aresstack.askai.agent.model.port.ThinkingMode;
import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.aresstack.askai.research.runtime.inference.ResearchModelSlots;
import com.aresstack.askai.research.runtime.role.ResearchRole;
import com.aresstack.askai.research.runtime.role.RoleOutput;
import com.aresstack.askai.research.runtime.team.ChatMessage;
import com.aresstack.askai.research.runtime.team.MainModelChat;
import com.aresstack.askai.research.runtime.team.MainModelChatResult;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The query planner: prompt, schema and strict reader of search queries. It serves two callers:
 * <ul>
 *     <li>the orchestrated run's {@link SearchPlannerWorker}, which delegates its aspect planning here
 *     ({@link #SYSTEM}, {@link #prompt}, {@link #schema}, {@link #read});</li>
 *     <li>the book pipeline (plan PR 13): {@link #planConcept} writes, per frozen concept node, a few variants of the
 *     node's deterministic base query on {@code phase.research.planner} with thinking off and
 *     {@value #CONCEPT_ANSWER_TOKENS} answer tokens. One repair, then the base query alone. It never throws.</li>
 * </ul>
 */
public final class QueryPlanner {

    static final String SYSTEM =
            "You are the search planner of a research assistant. You get ONE aspect of a research question and "
                    + "write web search queries for it. You never search yourself.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Each query is what a person would type into a web search engine: 3 to 10 words, concrete "
                    + "terms, names, places and years from the aspect; no full sentences, no operators.\n"
                    + "- The queries differ in angle (for example definition, current figures, regulation, "
                    + "criticism), never only in word order.\n"
                    + "- Never repeat a query listed under ALREADY SEARCHED.\n"
                    + "- Stay inside the aspect; do not widen the topic.";

    static final String CONCEPT_SYSTEM =
            "You are the search planner of a book assistant. You get ONE node of the book's concept and its base "
                    + "search query, and write a few other web search queries that find sources for that node. "
                    + "You never search yourself.\n"
                    + "Rules:\n"
                    + "- Answer with the JSON object the schema asks for, nothing else.\n"
                    + "- Write every query in the LANGUAGE given.\n"
                    + "- Each query is what a person would type into a web search engine: 3 to 10 words, concrete "
                    + "terms; no full sentences, no operators.\n"
                    + "- The queries differ from the base query and from each other in angle, never only in word "
                    + "order.\n"
                    + "- Never repeat a query listed under ALREADY SEARCHED and never use a term listed under "
                    + "BLACKLIST.\n"
                    + "- Stay inside the node; do not widen the topic.\n"
                    + "- AREA OF THE BOOK, when given, is the fence of the research: every query stays inside what it "
                    + "includes and never aims at what it excludes.\n"
                    + "- When RESEARCH CYCLE is above 1, the earlier queries did not find enough sources for the "
                    + "node: change the strategy instead of rewording. Name the institutions, authorities, "
                    + "associations, standards bodies, universities, journals or publishers that publish on the "
                    + "node's subject, the kinds of document that carry evidence (study, guideline, regulation, "
                    + "standard, report, statistics, PDF) and concrete sub-aspects, people or years. Never aim at "
                    + "shops, booksellers or the domains listed under DOMAINS FOUND SO FAR: a book sold on three "
                    + "sites is one book and no new knowledge.\n"
                    + "- Use the hints under WHAT THE PAGE WORKERS REPORTED when it is given: the pages the team "
                    + "read, what they lacked and what the workers suggest searching for. Never repeat a page's "
                    + "title as a query.\n"
                    + "- WHAT THE MATERIAL SHOWS, when given, is the analysis of the sources read so far for the node: "
                    + "THIN means little distinct material, REDUNDANT means many pages saying the same, MISSING means "
                    + "nothing yet. Aim the queries at what the material lacks, not at its key terms again, and "
                    + "never repeat a strategy listed as having found nothing.\n"
                    + "- NEW TOPICS IN THE MATERIAL are topics the sources show beyond the concept, inside the area. "
                    + "Write a query for one only when it belongs to this node.\n"
                    + "- PREFERRED SOURCES, when given, are the publishers, domains and kinds of document the mission "
                    + "prefers. Aim at least one query at one of them (its name, its domain with site:, or its kind "
                    + "of document), a different one than the queries already searched; vendor and product pages "
                    + "are no preferred source.";

    /** The answer budget of one concept planner call. */
    public static final int CONCEPT_ANSWER_TOKENS = 256;
    /** How often an invalid concept answer is sent back before the node keeps its base query only. */
    public static final int CONCEPT_REPAIR_ATTEMPTS = 1;

    /** The book pipeline's planner role: the Sources phase planner slot, no tools, thinking off. */
    public static final ResearchRole CONCEPT_ROLE = ResearchRole.builder("concept-query-planner",
                    ResearchModelSlots.PHASE_RESEARCH_PLANNER)
            .purpose("writes search query variants for each node of the frozen concept")
            .systemPrompt(CONCEPT_SYSTEM)
            .output(RoleOutput.schemaPerRequest("QueryPlanner.schema"))
            .thinking(ThinkingMode.OFF)
            .build();

    static final int MAX_QUERY_LENGTH = ConceptQueryPlan.MAX_QUERY_LENGTH;
    private static final double TEMPERATURE = 0.3;

    private QueryPlanner() {
    }

    // ------------------------------------------------------------------ aspect planning (orchestrated run)

    static String prompt(String aspect, String brief, Set<String> searched) {
        StringBuilder sb = new StringBuilder("ASPECT\n").append(aspect).append("\n\n");
        if (!brief.isEmpty()) {
            sb.append("BRIEF\n").append(brief).append("\n\n");
        }
        appendList(sb, "ALREADY SEARCHED", searched, "(nothing yet)");
        return sb.append("\nTASK: write the search queries for the aspect.").toString();
    }

    static String schema(int maxQueries) {
        JsonObject query = new JsonObject();
        query.addProperty("type", "string");
        query.addProperty("minLength", ConceptQueryPlan.MIN_QUERY_LENGTH);
        query.addProperty("maxLength", MAX_QUERY_LENGTH);
        JsonObject queries = new JsonObject();
        queries.addProperty("type", "array");
        queries.add("items", query);
        queries.addProperty("minItems", 1);
        queries.addProperty("maxItems", maxQueries);
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
    static List<String> read(String answer, int maxQueries, Set<String> searched) throws WorkerAnswerException {
        return read(answer, maxQueries, searched, Collections.<String>emptyList());
    }

    /** The strict reader with a blacklist: no accepted query contains a blacklisted term (case-insensitive). */
    static List<String> read(String answer, int maxQueries, Collection<String> searched,
                             Collection<String> blacklist) throws WorkerAnswerException {
        JsonObject root = WorkerAnswerException.object(answer);
        JsonElement element = root.get("queries");
        if (element == null || !element.isJsonArray()) {
            throw new WorkerAnswerException("\"queries\" must be an array of strings");
        }
        Set<String> known = new HashSet<String>();
        for (String query : searched) {
            known.add(WorkerArtifacts.normalize(query));
        }
        Map<String, String> distinct = new LinkedHashMap<String, String>();
        List<String> refused = new ArrayList<String>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw new WorkerAnswerException("every query must be a string");
            }
            String query = item.getAsString().replaceAll("\\s+", " ").trim();
            String key = WorkerArtifacts.normalize(query);
            String banned = blacklisted(key, blacklist);
            if (query.length() < ConceptQueryPlan.MIN_QUERY_LENGTH || query.length() > MAX_QUERY_LENGTH) {
                refused.add("\"" + query + "\" (length)");
            } else if (known.contains(key)) {
                refused.add("\"" + query + "\" (already searched)");
            } else if (banned != null) {
                refused.add("\"" + query + "\" (blacklisted term \"" + banned + "\")");
            } else if (distinct.containsKey(key)) {
                refused.add("\"" + query + "\" (duplicate)");
            } else {
                distinct.put(key, query);
            }
        }
        if (distinct.isEmpty()) {
            throw new WorkerAnswerException("no usable new query" + (refused.isEmpty() ? ""
                    : "; refused: " + String.join(", ", refused)));
        }
        List<String> queries = new ArrayList<String>(distinct.values());
        return queries.size() > maxQueries ? queries.subList(0, maxQueries) : queries;
    }

    private static String blacklisted(String normalizedQuery, Collection<String> blacklist) {
        for (String term : blacklist) {
            String key = WorkerArtifacts.normalize(term);
            if (!key.isEmpty() && normalizedQuery.contains(key)) {
                return term.trim();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ concept planning (book pipeline)

    /**
     * Plans every node of the request: its base query plus at most {@code maxVariantsPerNode} valid variants. An
     * invalid answer is sent back once; a second invalid answer, a failed model call or any error keeps the base
     * query alone and says why in the node's note. Never throws.
     */
    public static ConceptQueryPlan.Result planConcept(MainModelChat chat, ConceptQueryPlan.Request request) {
        return planConcept(chat, request, null);
    }

    /**
     * As {@link #planConcept(MainModelChat, ConceptQueryPlan.Request)}; once {@code cancelled} is set no further
     * model call starts and the remaining nodes keep their base query.
     */
    public static ConceptQueryPlan.Result planConcept(MainModelChat chat, ConceptQueryPlan.Request request,
                                                      java.util.function.BooleanSupplier cancelled) {
        List<ConceptQueryPlan.NodePlan> plans = new ArrayList<ConceptQueryPlan.NodePlan>();
        if (request == null) {
            return new ConceptQueryPlan.Result(plans);
        }
        // Every node's base query is reserved up front, so no variant duplicates a later node's base query.
        Set<String> known = new java.util.LinkedHashSet<String>(request.getQueriesRun());
        for (ConceptQueryPlan.Node node : request.getNodes()) {
            String base = baseQuery(node);
            if (!base.isEmpty()) {
                known.add(base);
            }
        }
        for (ConceptQueryPlan.Node node : request.getNodes()) {
            String base = baseQuery(node);
            List<String> variants = Collections.emptyList();
            String note = "";
            if (isCancelled(cancelled)) {
                note = "planning stopped, base query only";
            } else if (request.getMaxVariantsPerNode() > 0 && chat != null) {
                try {
                    variants = planNode(chat, request, node, base, known, cancelled);
                } catch (WorkerAnswerException invalid) {
                    note = "invalid planner answer, base query only: " + invalid.getMessage();
                } catch (RuntimeException failure) {
                    note = "planner failed, base query only: " + failure;
                }
            }
            known.addAll(variants);
            plans.add(new ConceptQueryPlan.NodePlan(node.getId(), base, variants, note));
        }
        return new ConceptQueryPlan.Result(plans);
    }

    private static List<String> planNode(MainModelChat chat, ConceptQueryPlan.Request request,
                                         ConceptQueryPlan.Node node, String base, Set<String> known,
                                         java.util.function.BooleanSupplier cancelled)
            throws WorkerAnswerException {
        int max = request.getMaxVariantsPerNode();
        List<ChatMessage> messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system(CONCEPT_SYSTEM));
        messages.add(ChatMessage.user(conceptPrompt(request, node, base, known)));
        String schema = schema(max);
        for (int attempt = 0; ; attempt++) {
            MainModelChatResult result = chat.completeJson(messages, TEMPERATURE, CONCEPT_ANSWER_TOKENS, schema);
            if (!result.isOk()) {
                throw new WorkerAnswerException("model call failed (" + result.getStatus() + "): "
                        + result.getDetail());
            }
            try {
                return read(result.getText(), max, known, request.getBlacklist());
            } catch (WorkerAnswerException invalid) {
                if (attempt >= CONCEPT_REPAIR_ATTEMPTS || isCancelled(cancelled)) {
                    throw invalid;
                }
                messages.add(ChatMessage.assistant(result.getText() == null ? "" : result.getText()));
                messages.add(ChatMessage.user("Your answer was rejected: " + invalid.getMessage()
                        + "\nAnswer again with the corrected JSON object only."));
            }
        }
    }

    private static boolean isCancelled(java.util.function.BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    /** The node's base query, or its label when the base query is unusable; empty when neither fits. */
    static String baseQuery(ConceptQueryPlan.Node node) {
        for (String candidate : new String[]{node.getBaseQuery(), node.getLabel()}) {
            String query = candidate.replaceAll("\\s+", " ").trim();
            if (query.length() >= ConceptQueryPlan.MIN_QUERY_LENGTH && query.length() <= MAX_QUERY_LENGTH) {
                return query;
            }
        }
        return "";
    }

    static String conceptPrompt(ConceptQueryPlan.Request request, ConceptQueryPlan.Node node, String base,
                                Collection<String> searched) {
        StringBuilder sb = new StringBuilder("LANGUAGE\n").append(languageName(request.getLanguage()))
                .append("\n\n");
        if (!request.getBookTitle().isEmpty()) {
            sb.append("BOOK\n").append(request.getBookTitle()).append("\n\n");
        }
        if (!request.getArea().isEmpty()) {
            sb.append("AREA OF THE BOOK\n").append(request.getArea()).append("\n\n");
        }
        sb.append("NODE\n").append(node.getLabel()).append('\n');
        if (!node.getPath().isEmpty()) {
            sb.append("PATH\n").append(String.join(" > ", node.getPath())).append('\n');
        }
        sb.append("\nBASE QUERY\n").append(base.isEmpty() ? "(none)" : base).append("\n\n");
        if (request.getCycle() > 1) {
            sb.append("RESEARCH CYCLE\n").append(request.getCycle())
                    .append(" (the queries under ALREADY SEARCHED left this node without enough sources)\n\n");
            appendList(sb, "DOMAINS FOUND SO FAR", node.getFoundDomains(), "(none)");
            sb.append('\n');
            if (!node.getWorkerNotes().isEmpty()) {
                appendList(sb, "WHAT THE PAGE WORKERS REPORTED", node.getWorkerNotes(), "(nothing)");
                sb.append('\n');
            }
        }
        if (!node.getEvidence().isEmpty()) {
            appendList(sb, "WHAT THE MATERIAL SHOWS", node.getEvidence(), "(nothing)");
            sb.append('\n');
        }
        if (!request.getNewTopics().isEmpty()) {
            appendList(sb, "NEW TOPICS IN THE MATERIAL", request.getNewTopics(), "(none)");
            sb.append('\n');
        }
        if (!request.getPreferredSources().isEmpty()) {
            appendList(sb, "PREFERRED SOURCES", request.getPreferredSources(), "(none)");
            sb.append('\n');
        }
        appendList(sb, "ALREADY SEARCHED", searched, "(nothing yet)");
        sb.append('\n');
        appendList(sb, "BLACKLIST", request.getBlacklist(), "(none)");
        sb.append("\nTASK: write at most ").append(request.getMaxVariantsPerNode())
                .append(" other search queries for the node, in ").append(languageName(request.getLanguage()))
                .append('.');
        if (request.getCycle() > 1) {
            sb.append(" Take another angle than the queries already searched: other publishers of evidence, other "
                    + "kinds of document, other sub-aspects.");
        }
        return sb.toString();
    }

    static String languageName(String code) {
        String c = code == null ? "" : code.trim().toLowerCase(Locale.ROOT);
        if (c.startsWith("de")) {
            return "German";
        }
        if (c.isEmpty() || c.startsWith("en")) {
            return "English";
        }
        return new Locale(c).getDisplayLanguage(Locale.ENGLISH);
    }

    private static void appendList(StringBuilder sb, String title, Collection<String> values, String empty) {
        sb.append(title).append('\n');
        if (values.isEmpty()) {
            sb.append(empty).append('\n');
        }
        for (String value : values) {
            sb.append("- ").append(value).append('\n');
        }
    }
}
