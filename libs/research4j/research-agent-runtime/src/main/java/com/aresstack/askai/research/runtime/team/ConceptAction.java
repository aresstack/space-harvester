package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The model's ONE concept step for one inference — a tiny atomic operation addressed by
 * UNAMBIGUOUS PATH SEGMENTS (K2c hardening): {@code path}/{@code parent} are ARRAYS of card
 * names, never slash-joined strings. The gate showed why: a model that misses a parent turns
 * "FreeRTOS/ESP32/Grundlagen" into a literal root card name, and real-world names (TCP/IP,
 * Client/Server, C/C++) make '/' unusable as an implicit separator. A plain string is accepted
 * as exactly ONE segment — it is never split.
 */
public final class ConceptAction {

    /**
     * READ/ADD/REMOVE work the concept; EXCLUDE and RESOLVE are the ONE-command exclusion facade
     * (live-gate 4 decision): the model quotes the USER'S term, the platform owns ids, facets
     * and the concept-conflict check — one command, one effect, one structured reply. OFFER is
     * the search-suggestion command (gate 8b: the OPTIONAL in-band field starved under the
     * generation grammar — as an ACTION the model actually uses it, and the platform renders
     * the yellow tags).
     */
    public enum Type { READ, ADD, ADD_CARDS, MOVE, REMOVE, EXCLUDE, RESOLVE, OFFER, RENAME,
        REWRITE, PROBE }

    private final Type type;
    private final List<String> path;
    private final List<String> parent;
    private final String name;
    private final String decision;

    private ConceptAction(Type type, List<String> path, List<String> parent, String name) {
        this(type, path, parent, name, null);
    }

    private ConceptAction(Type type, List<String> path, List<String> parent, String name,
                          String decision) {
        this.type = type;
        this.path = path == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(path);
        this.parent = parent == null ? Collections.<String>emptyList()
                : Collections.unmodifiableList(parent);
        this.name = name == null ? "" : name.trim();
        this.decision = decision == null ? "" : decision.trim();
    }

    public Type getType() {
        return type;
    }

    /** For READ/REMOVE: the card-name segments from the concept root (READ: empty = all). */
    public List<String> getPath() {
        return path;
    }

    /** For ADD: the parent card's segments (empty = a new top-level card). */
    public List<String> getParent() {
        return parent;
    }

    /** For ADD: the new card's name — ONE label, never a path. */
    public String getName() {
        return name;
    }

    /** For EXCLUDE: the topic in the USER'S words (the platform derives everything else). */
    public String getTopic() {
        return name;
    }

    /** For RESOLVE: the opaque conflict id the exclude tool reported. */
    public String getConflictId() {
        return name;
    }

    /** For RESOLVE: REMOVE or KEEP_SUPPRESSED — the user's answer to the conflict question. */
    public String getDecision() {
        return decision;
    }

    /** For OFFER: the suggestions as a compact JSON array string {@code [{"query":..,"purpose":..}]}. */
    public String getSuggestionsJson() {
        return name;
    }

    /** For REWRITE: the branch's new leaf names as a compact JSON array string. */
    public String getLeavesJson() {
        return decision;
    }

    /** For ADD_CARDS: the new card names as a compact JSON array string (typed, never split). */
    public String getNamesJson() {
        return decision;
    }

    /** For PROBE: the terms as a compact JSON array string (scope_probe's ONE field). */
    public String getTermsJson() {
        return decision;
    }

    /** A compact trace label ('add parent=["A","B"] name="C"'). */
    public String describe() {
        switch (type) {
            case READ:
                return "read path=" + segmentsLabel(path);
            case ADD:
                return "add parent=" + segmentsLabel(parent) + " name=\"" + name + "\"";
            case ADD_CARDS:
                return "add_cards parent=" + segmentsLabel(parent) + " names=" + decision;
            case MOVE:
                return "move source=" + segmentsLabel(path) + " parent="
                        + segmentsLabel(parent);
            case EXCLUDE:
                return "exclude topic=\"" + name + "\"";
            case RESOLVE:
                return "resolve conflict=\"" + name + "\" decision=" + decision;
            case OFFER:
                return "offer suggestions=" + countJsonObjects(name);
            case PROBE:
                return "probe terms=" + decision;
            case RENAME:
                return "rename path=" + segmentsLabel(path) + " name=\"" + name + "\"";
            case REWRITE:
                return "rewrite path=" + segmentsLabel(path) + " leaves=" + decision;
            default:
                return "remove path=" + segmentsLabel(path);
        }
    }

    private static String segmentsLabel(List<String> segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            sb.append(i > 0 ? "," : "").append('"').append(segments.get(i)).append('"');
        }
        return sb.append(']').toString();
    }

    // ------------------------------------------------------------------ parsing

    /** Either a valid action, or the reason it is invalid (fed back to the model, never dropped). */
    public static final class Parsed {
        private final ConceptAction action;
        private final String error;
        private boolean explicitNone;

        public boolean isExplicitNone() {
            return explicitNone;
        }

        private Parsed(ConceptAction action, String error) {
            this.action = action;
            this.error = error;
        }

        public boolean isPresent() {
            return action != null || error != null;
        }

        public ConceptAction getAction() {
            return action;
        }

        public String getError() {
            return error;
        }

        static Parsed absent() {
            return new Parsed(null, null);
        }

        /** The model EXPLICITLY chose type "none" (observable) — vs. an absent field. */
        static Parsed none() {
            Parsed parsed = new Parsed(null, null);
            parsed.explicitNone = true;
            return parsed;
        }

        static Parsed ok(ConceptAction action) {
            return new Parsed(action, null);
        }

        static Parsed invalid(String error) {
            return new Parsed(null, error);
        }
    }

    /**
     * Parse the optional {@code conceptAction} value. Absent, {@code null} or an explicit
     * {@code type:"none"} → absent. A malformed action carries its reason back to the model with
     * a concrete example. Segment lists accept a JSON array of strings; a bare string counts as
     * ONE segment (never split on '/').
     */
    @SuppressWarnings("unchecked")
    public static Parsed parse(Object value) {
        if (value == null) {
            return Parsed.absent();
        }
        if (!(value instanceof Map)) {
            return Parsed.invalid("conceptAction must be a JSON object with a \"type\" field "
                    + "(none, read, add or remove)");
        }
        Map<String, Object> map = (Map<String, Object>) value;
        String type = asString(map.get("type"));
        if (type == null) {
            return Parsed.absent();
        }
        if ("none".equalsIgnoreCase(type.trim())) {
            return Parsed.none();
        }
        if ("read".equalsIgnoreCase(type)) {
            return Parsed.ok(new ConceptAction(Type.READ,
                    segments(map.get("path"), map.get("parent")), null, null));
        }
        if ("add".equalsIgnoreCase(type)) {
            String name = asString(map.get("name"));
            if (name == null || name.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"add\" requires \"name\" — example: "
                        + "{\"type\":\"add\",\"parent\":[\"<card>\"],"
                        + "\"name\":\"<subtopic>\"}");
            }
            return Parsed.ok(new ConceptAction(Type.ADD, null,
                    segments(map.get("parent"), map.get("parent_path"), map.get("path")), name));
        }
        if ("add_cards".equalsIgnoreCase(type)) {
            Object namesValue = map.get("names");
            List<String> names = namesValue instanceof List
                    ? segments(namesValue) : Collections.<String>emptyList();
            if (names.isEmpty()) {
                return Parsed.invalid("conceptAction type \"add_cards\" requires \"names\" — "
                        + "ALL card names as one array (a single card is a one-element list); "
                        + "example: {\"type\":\"add_cards\",\"parent\":[],\"names\":"
                        + "[\"<area 1>\",\"<area 2>\",\"<area 3>\"]}");
            }
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < names.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendJsonString(json, names.get(index));
            }
            json.append(']');
            return Parsed.ok(new ConceptAction(Type.ADD_CARDS, null,
                    segments(map.get("parent"), map.get("parent_path")), "",
                    json.toString()));
        }
        if ("move".equalsIgnoreCase(type)) {
            List<String> source = segments(map.get("source"), map.get("path"));
            if (source.isEmpty()) {
                // Small-model tolerance: the rename/add habit sends the card as "name".
                String named = asString(map.get("name"));
                if (named != null && !named.trim().isEmpty()) {
                    source = java.util.Collections.singletonList(named.trim());
                }
            }
            boolean hasParent = map.containsKey("parent") || map.containsKey("parent_path");
            if (source.isEmpty() || !hasParent) {
                return Parsed.invalid("conceptAction type \"move\" requires \"source\" (the "
                        + "leaf — one globally unique card name is enough) and \"parent\" "
                        + "([] = top level) — example: {\"type\":\"move\",\"source\":"
                        + "[\"<card>\"],\"parent\":[\"<new parent>\"]}");
            }
            return Parsed.ok(new ConceptAction(Type.MOVE, source,
                    segments(map.get("parent"), map.get("parent_path")), ""));
        }
        if ("remove".equalsIgnoreCase(type)) {
            List<String> path = segments(map.get("path"), map.get("parent"));
            if (path.isEmpty()) {
                return Parsed.invalid("conceptAction type \"remove\" requires \"path\" — "
                        + "example: {\"type\":\"remove\",\"path\":[\"<card>\",\"<child card>\"]}");
            }
            return Parsed.ok(new ConceptAction(Type.REMOVE, path, null, null));
        }
        if ("exclude".equalsIgnoreCase(type)) {
            String topic = asString(map.get("topic"));
            if (topic == null || topic.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"exclude\" requires \"topic\" (the "
                        + "user's words) — example: {\"type\":\"exclude\",\"topic\":\"<term>\"}");
            }
            return Parsed.ok(new ConceptAction(Type.EXCLUDE, null, null, topic));
        }
        if ("rename".equalsIgnoreCase(type)) {
            List<String> path = segments(map.get("path"));
            String newName = asString(map.get("name"));
            if (path.isEmpty() || newName == null || newName.trim().isEmpty()) {
                return Parsed.invalid("conceptAction type \"rename\" requires \"path\" and "
                        + "\"name\" — example: {\"type\":\"rename\",\"path\":[\"<card>\","
                        + "\"<child card>\"],\"name\":\"<new name>\"}");
            }
            return Parsed.ok(new ConceptAction(Type.RENAME, path, null, newName));
        }
        if ("rewrite".equalsIgnoreCase(type)) {
            List<String> path = segments(map.get("path"));
            Object leavesValue = map.get("leaves");
            if (path.isEmpty() || !(leavesValue instanceof List)) {
                return Parsed.invalid("conceptAction type \"rewrite\" requires \"path\" and "
                        + "\"leaves\" (the branch's NEW leaf names; may be []) — example: "
                        + "{\"type\":\"rewrite\",\"path\":[\"<card>\",\"<child card>\"],"
                        + "\"leaves\":[\"<leaf 1>\",\"<leaf 2>\"]}");
            }
            List<String> leaves = segments(leavesValue);
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < leaves.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendJsonString(json, leaves.get(index));
            }
            json.append(']');
            return Parsed.ok(new ConceptAction(Type.REWRITE, path, null, "", json.toString()));
        }
        if ("probe".equalsIgnoreCase(type)) {
            // scope_probe (AP3): ONE field, a list of terms — the host owns everything else.
            Object termsValue = map.get("terms");
            List<String> terms = termsValue instanceof List
                    ? segments(termsValue) : Collections.<String>emptyList();
            if (terms.isEmpty()) {
                return Parsed.invalid("conceptAction type \"probe\" requires \"terms\" — "
                        + "several plausible remaining concepts as ONE array; example: "
                        + "{\"type\":\"probe\",\"terms\":[\"<term 1>\","
                        + "\"<term 2>\"]}");
            }
            StringBuilder json = new StringBuilder("[");
            for (int index = 0; index < terms.size(); index++) {
                if (index > 0) {
                    json.append(',');
                }
                appendJsonString(json, terms.get(index));
            }
            json.append(']');
            return Parsed.ok(new ConceptAction(Type.PROBE, null, null, "", json.toString()));
        }
        if ("offer".equalsIgnoreCase(type)) {
            List<String[]> suggestions = suggestionPairs(map.get("suggestions"));
            if (suggestions.isEmpty()) {
                return Parsed.invalid("conceptAction type \"offer\" requires \"suggestions\" "
                        + "with at least one non-empty query — example: {\"type\":\"offer\","
                        + "\"suggestions\":[{\"query\":\"<topic> basics overview\","
                        + "\"purpose\":\"<direction>\"}]}");
            }
            return Parsed.ok(new ConceptAction(Type.OFFER, null, null,
                    suggestionsJson(suggestions)));
        }
        if ("resolve".equalsIgnoreCase(type)) {
            String conflictId = asString(map.get("conflictId"));
            String decision = asString(map.get("decision"));
            boolean knownDecision = "REMOVE".equalsIgnoreCase(decision == null ? "" : decision.trim())
                    || "KEEP_SUPPRESSED".equalsIgnoreCase(decision == null ? "" : decision.trim());
            if (conflictId == null || conflictId.trim().isEmpty() || !knownDecision) {
                return Parsed.invalid("conceptAction type \"resolve\" requires \"conflictId\" and "
                        + "\"decision\" (REMOVE or KEEP_SUPPRESSED) — example: {\"type\":"
                        + "\"resolve\",\"conflictId\":\"conflict-17\",\"decision\":\"REMOVE\"}");
            }
            return Parsed.ok(new ConceptAction(Type.RESOLVE, null, null, conflictId,
                    decision.trim().toUpperCase(java.util.Locale.ROOT)));
        }
        return Parsed.invalid("conceptAction has unknown type \"" + type
                + "\" — allowed: none, read, add_cards, exclude, resolve, offer, rename, "
                + "probe");
    }

    /** OFFER: {query, purpose} pairs with a non-empty query; malformed entries are dropped. */
    @SuppressWarnings("unchecked")
    private static List<String[]> suggestionPairs(Object value) {
        List<String[]> pairs = new ArrayList<String[]>();
        if (!(value instanceof List)) {
            return pairs;
        }
        for (Object element : (List<Object>) value) {
            if (!(element instanceof Map)) {
                continue;
            }
            Map<String, Object> suggestion = (Map<String, Object>) element;
            String query = asString(suggestion.get("query"));
            if (query == null || query.trim().isEmpty()) {
                continue;
            }
            String purpose = asString(suggestion.get("purpose"));
            pairs.add(new String[] {query.trim(), purpose == null ? "" : purpose.trim()});
        }
        return pairs;
    }

    /** Compact canonical JSON for the wire/history — the tool argument travels as ONE string. */
    private static String suggestionsJson(List<String[]> pairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int index = 0; index < pairs.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append("{\"query\":");
            appendJsonString(sb, pairs.get(index)[0]);
            if (!pairs.get(index)[1].isEmpty()) {
                sb.append(",\"purpose\":");
                appendJsonString(sb, pairs.get(index)[1]);
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    private static void appendJsonString(StringBuilder sb, String value) {
        sb.append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                sb.append('\\').append(character);
            } else if (character == '\n') {
                sb.append("\\n");
            } else {
                sb.append(character);
            }
        }
        sb.append('"');
    }

    /** A cheap object count for the trace label ('offer suggestions=3'). */
    private static int countJsonObjects(String json) {
        int count = 0;
        for (int index = 0; index < json.length(); index++) {
            if (json.charAt(index) == '{') {
                count++;
            }
        }
        return count;
    }

    /** First present value wins; array of strings verbatim, a bare string = ONE segment. */
    private static List<String> segments(Object... candidates) {
        for (Object candidate : candidates) {
            if (candidate instanceof List) {
                List<String> out = new ArrayList<String>();
                for (Object element : (List<Object>) candidate) {
                    if (element instanceof String && !((String) element).trim().isEmpty()) {
                        out.add(((String) element).trim());
                    }
                }
                return out;
            }
            if (candidate instanceof String && !((String) candidate).trim().isEmpty()) {
                List<String> out = new ArrayList<String>();
                out.add(((String) candidate).trim());
                return out;
            }
        }
        return new ArrayList<String>();
    }

    private static String asString(Object value) {
        return value instanceof String ? (String) value : null;
    }
}
