package com.aresstack.askai.research.runtime.plan;

import com.aresstack.askai.research.domain.plan.ConceptQueryPlan;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.ArrayList;
import java.util.List;

/**
 * Runtime side of the {@code plan_queries} wire (plan PR 13): parses the host's request JSON and renders the plan
 * for the {@code #RSX1# query_plan} line. The host's codec of the same payload ({@code QueryPlanWireCodec} in the
 * ui-plugin) is pinned against this class by a round-trip test in both modules.
 */
public final class QueryPlanWire {

    private QueryPlanWire() {
    }

    /** @throws IllegalArgumentException when the request is not the expected JSON */
    public static ConceptQueryPlan.Request parseRequest(String json) {
        return readRequest(json);
    }

    public static String renderResult(ConceptQueryPlan.Result result) {
        return writeResult(result);
    }

    /** The request JSON (used by tests that drive the runtime alone). */
    public static String renderRequest(ConceptQueryPlan.Request request) {
        return writeRequest(request);
    }

    /** @throws IllegalArgumentException when the payload is not the expected JSON */
    public static ConceptQueryPlan.Result parseResult(String json) {
        return readResult(json);
    }

    static final String LANGUAGE = "language";
    static final String BOOK_TITLE = "book_title";
    static final String MAX = "max_variants_per_node";
    static final String QUERIES_RUN = "queries_run";
    static final String BLACKLIST = "blacklist";
    static final String NODES = "nodes";
    static final String CYCLE = "cycle";
    static final String DOMAINS_FOUND = "domains_found";
    static final String WORKER_NOTES = "worker_notes";
    static final String AREA = "area";
    static final String EVIDENCE = "evidence";
    static final String NEW_TOPICS = "new_topics";
    static final String PREFERRED_SOURCES = "preferred_sources";

    private static JsonArray array(List<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    private static List<String> strings(JsonObject object, String name) {
        List<String> out = new ArrayList<String>();
        JsonElement element = object.get(name);
        if (element != null && element.isJsonArray()) {
            for (JsonElement item : element.getAsJsonArray()) {
                if (item.isJsonPrimitive()) {
                    out.add(item.getAsString());
                }
            }
        }
        return out;
    }

    private static String text(JsonObject object, String name) {
        JsonElement element = object.get(name);
        return element == null || !element.isJsonPrimitive() ? "" : element.getAsString();
    }

    private static JsonObject object(String json) {
        JsonElement root = JsonParser.parseString(json == null ? "" : json);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("not a JSON object");
        }
        return root.getAsJsonObject();
    }

    static String writeRequest(ConceptQueryPlan.Request request) {
        JsonObject root = new JsonObject();
        root.addProperty(LANGUAGE, request.getLanguage());
        root.addProperty(BOOK_TITLE, request.getBookTitle());
        root.addProperty(MAX, request.getMaxVariantsPerNode());
        root.addProperty(CYCLE, request.getCycle());
        root.add(QUERIES_RUN, array(request.getQueriesRun()));
        root.add(BLACKLIST, array(request.getBlacklist()));
        root.addProperty(AREA, request.getArea());
        root.add(NEW_TOPICS, array(request.getNewTopics()));
        root.add(PREFERRED_SOURCES, array(request.getPreferredSources()));
        JsonArray nodes = new JsonArray();
        for (ConceptQueryPlan.Node node : request.getNodes()) {
            JsonObject n = new JsonObject();
            n.addProperty("id", node.getId());
            n.addProperty("label", node.getLabel());
            n.add("path", array(node.getPath()));
            n.addProperty("parent_id", node.getParentId());
            n.addProperty("base_query", node.getBaseQuery());
            n.add(DOMAINS_FOUND, array(node.getFoundDomains()));
            n.add(WORKER_NOTES, array(node.getWorkerNotes()));
            n.add(EVIDENCE, array(node.getEvidence()));
            nodes.add(n);
        }
        root.add(NODES, nodes);
        return root.toString();
    }

    static ConceptQueryPlan.Request readRequest(String json) {
        try {
            JsonObject root = object(json);
            List<ConceptQueryPlan.Node> nodes = new ArrayList<ConceptQueryPlan.Node>();
            JsonElement array = root.get(NODES);
            if (array == null || !array.isJsonArray()) {
                throw new IllegalArgumentException("\"nodes\" must be an array");
            }
            for (JsonElement item : array.getAsJsonArray()) {
                JsonObject n = item.getAsJsonObject();
                nodes.add(new ConceptQueryPlan.Node(text(n, "id"), text(n, "label"), strings(n, "path"),
                        text(n, "parent_id"), text(n, "base_query"), strings(n, DOMAINS_FOUND),
                        strings(n, WORKER_NOTES), strings(n, EVIDENCE)));
            }
            JsonElement max = root.get(MAX);
            JsonElement cycle = root.get(CYCLE);
            return new ConceptQueryPlan.Request(text(root, LANGUAGE), text(root, BOOK_TITLE), nodes,
                    strings(root, QUERIES_RUN), strings(root, BLACKLIST),
                    max == null || !max.isJsonPrimitive() ? 0 : max.getAsInt(),
                    cycle == null || !cycle.isJsonPrimitive() ? 1 : cycle.getAsInt(), text(root, AREA),
                    strings(root, NEW_TOPICS), strings(root, PREFERRED_SOURCES));
        } catch (RuntimeException malformed) {
            throw malformed instanceof IllegalArgumentException ? (IllegalArgumentException) malformed
                    : new IllegalArgumentException(malformed.getMessage(), malformed);
        }
    }

    static String writeResult(ConceptQueryPlan.Result result) {
        JsonObject root = new JsonObject();
        JsonArray nodes = new JsonArray();
        for (ConceptQueryPlan.NodePlan plan : result.getNodes()) {
            JsonObject n = new JsonObject();
            n.addProperty("node_id", plan.getNodeId());
            n.addProperty("base_query", plan.getBaseQuery());
            n.add("variants", array(plan.getVariants()));
            n.addProperty("note", plan.getNote());
            nodes.add(n);
        }
        root.add(NODES, nodes);
        return root.toString();
    }

    static ConceptQueryPlan.Result readResult(String json) {
        try {
            JsonObject root = object(json);
            List<ConceptQueryPlan.NodePlan> plans = new ArrayList<ConceptQueryPlan.NodePlan>();
            JsonElement array = root.get(NODES);
            if (array != null && array.isJsonArray()) {
                for (JsonElement item : array.getAsJsonArray()) {
                    JsonObject n = item.getAsJsonObject();
                    plans.add(new ConceptQueryPlan.NodePlan(text(n, "node_id"), text(n, "base_query"),
                            strings(n, "variants"), text(n, "note")));
                }
            }
            return new ConceptQueryPlan.Result(plans);
        } catch (RuntimeException malformed) {
            throw malformed instanceof IllegalArgumentException ? (IllegalArgumentException) malformed
                    : new IllegalArgumentException(malformed.getMessage(), malformed);
        }
    }
}

