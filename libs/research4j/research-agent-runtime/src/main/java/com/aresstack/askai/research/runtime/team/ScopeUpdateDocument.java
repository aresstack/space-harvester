package com.aresstack.askai.research.runtime.team;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NEUTRAL scope-update document a scoping turn sends to the host: the proposed operations, the open
 * issues and the proposed orientation lookups — as plain JSON.
 * <p>
 * The runtime deliberately does NOT know the host's domain objects here. The process boundary carries data,
 * not types, so both sides can evolve on their own; the host decodes this into its operations and decides
 * what to do with them.
 * <p>
 * Validation is ALL-OR-NOTHING, exactly like the host's decoding: if a single operation, issue or
 * suggestion is malformed, the whole scope update of this turn is invalid. Dropping only the bad element and
 * forwarding the rest would be worse than useless — the assistant would say it noted an emphasis, the facet
 * would be stored and the emphasis silently missing. A partially applied turn makes the conversation and the
 * stored scope disagree, which is the one failure mode this design exists to prevent.
 */
public final class ScopeUpdateDocument {

    /** Operation kinds the host understands. An unknown kind never reaches the wire. */
    private static final List<String> KNOWN_KINDS = java.util.Arrays.asList(
            "setMission", "addFacet", "confirmFacet", "excludeFacet", "setFacetEmphasis",
            "setCrossCuttingEmphasis", "setDeliverable", "addDomain", "addContext", "addPerspective",
            "addConstraint", "addExclusion", "addTerminology", "setGeographicScope", "setTemporalScope",
            "addUnresolvedIssue", "resolveIssue");

    /** Fields that must be present and non-blank for a given kind. */
    private static final Map<String, List<String>> REQUIRED_FIELDS = requiredFields();

    private final List<Map<String, Object>> operations;
    private final List<Map<String, Object>> issues;
    private final List<Map<String, Object>> suggestions;
    private final List<String> rejections;
    /** Advisory elements (issues/suggestions) that were malformed and DROPPED — traced, never fatal. */
    private final List<String> droppedAdvisories;

    private ScopeUpdateDocument(List<Map<String, Object>> operations, List<Map<String, Object>> issues,
                                List<Map<String, Object>> suggestions, List<String> rejections,
                                List<String> droppedAdvisories) {
        this.operations = operations;
        this.issues = issues;
        this.suggestions = suggestions;
        this.rejections = rejections;
        this.droppedAdvisories = droppedAdvisories;
    }

    /** The malformed ADVISORY elements this turn dropped (observability; empty when none). */
    public List<String> getDroppedAdvisories() {
        return droppedAdvisories;
    }

    /** True when this turn proposes nothing at all — then no wire line is sent. */
    public boolean isEmpty() {
        return operations.isEmpty() && issues.isEmpty() && suggestions.isEmpty();
    }

    /** False when ANY element was malformed — the turn is then a contract violation, not a partial update. */
    public boolean isValid() {
        return rejections.isEmpty();
    }

    /** Why the update is invalid; empty for a valid one. Never a list of silently skipped elements. */
    public List<String> getViolations() {
        return rejections;
    }

    /** One line naming every violation — the reason the whole turn is rejected. */
    public String describeViolations() {
        StringBuilder sb = new StringBuilder();
        for (String violation : rejections) {
            if (sb.length() > 0) {
                sb.append("; ");
            }
            sb.append(violation);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    public static ScopeUpdateDocument from(Object scopePatch, Object unresolvedIssues,
                                           Object orientationSuggestions) {
        List<Map<String, Object>> operations = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> issues = new ArrayList<Map<String, Object>>();
        List<Map<String, Object>> suggestions = new ArrayList<Map<String, Object>>();
        List<String> rejections = new ArrayList<String>();

        Object rawOperations = scopePatch instanceof Map
                ? ((Map<String, Object>) scopePatch).get("operations") : scopePatch;
        for (Map<String, Object> operation : objects(rawOperations)) {
            String kind = text(operation.get("kind"));
            if (!KNOWN_KINDS.contains(kind)) {
                // Multi-action routing finding: a connector model once sent the probe as a
                // scopePatch operation — the rejection teaches the correct channel.
                rejections.add("unknown operation kind '" + kind + "'"
                        + ("probe".equalsIgnoreCase(kind)
                                ? " — scope_probe is a conceptAction: {\"type\":\"probe\","
                                        + "\"terms\":[...]}"
                                : ""));
                continue;
            }
            operation = withDerivedFacetId(kind, operation);
            String missing = firstMissingField(kind, operation);
            if (missing != null) {
                rejections.add(kind + " without '" + missing + "'");
                continue;
            }
            String malformedId = facetIdShapeProblem(kind, operation);
            if (malformedId != null) {
                rejections.add(malformedId);
                continue;
            }
            operations.add(operation);
        }
        // SEPARATE ERROR DOMAINS (live-gate lesson): issues and orientation suggestions are
        // ADVISORY metadata — a malformed one is dropped and traced, it must never poison the
        // fachliche scope commit. Two broken suggestions once rejected a whole turn's
        // excludeFacet, and the Weidezaun starved. Only OPERATIONS stay all-or-nothing among
        // themselves, because a partially applied operation set would make conversation and
        // stored scope disagree.
        List<String> droppedAdvisories = new ArrayList<String>();
        for (Map<String, Object> issue : objects(unresolvedIssues)) {
            if (text(issue.get("issueId")).isEmpty() || text(issue.get("description")).isEmpty()) {
                droppedAdvisories.add("unresolvedIssue without issueId/description");
                continue;
            }
            issues.add(issue);
        }
        for (Map<String, Object> suggestion : objects(orientationSuggestions)) {
            // The label is what the user reads and must exist in its own right: falling back to the query
            // would show an engine query (possibly in another language) as UI text.
            if (text(suggestion.get("label")).isEmpty() || text(suggestion.get("query")).isEmpty()) {
                droppedAdvisories.add("orientationSuggestion without label/query");
                continue;
            }
            suggestions.add(suggestion);
        }
        return new ScopeUpdateDocument(operations, issues, suggestions, rejections,
                droppedAdvisories);
    }

    /** The operations as a JSON array — for the canonical-history codec (lossless round-trip). */
    String operationsJson() {
        return array(operations);
    }

    String issuesJson() {
        return array(issues);
    }

    String suggestionsJson() {
        return array(suggestions);
    }

    /** The canonical JSON the host decodes. */
    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        sb.append("\"operations\":").append(array(operations));
        sb.append(",\"unresolvedIssues\":").append(array(issues));
        sb.append(",\"orientationSuggestions\":").append(array(suggestions));
        return sb.append('}').toString();
    }

    private static Map<String, List<String>> requiredFields() {
        Map<String, List<String>> required = new LinkedHashMap<String, List<String>>();
        required.put("setMission", java.util.Arrays.asList("mission"));
        required.put("addFacet", java.util.Arrays.asList("facetId", "label"));
        required.put("confirmFacet", java.util.Arrays.asList("facetId"));
        required.put("excludeFacet", java.util.Arrays.asList("facetId"));
        required.put("setFacetEmphasis", java.util.Arrays.asList("facetId"));
        required.put("setCrossCuttingEmphasis", java.util.Arrays.asList("dimension"));
        required.put("addDomain", java.util.Arrays.asList("value"));
        required.put("addContext", java.util.Arrays.asList("value"));
        required.put("addPerspective", java.util.Arrays.asList("value"));
        required.put("addConstraint", java.util.Arrays.asList("value"));
        required.put("addExclusion", java.util.Arrays.asList("value"));
        required.put("addTerminology", java.util.Arrays.asList("value"));
        required.put("setGeographicScope", java.util.Arrays.asList("value"));
        required.put("setTemporalScope", java.util.Arrays.asList("value"));
        required.put("addUnresolvedIssue", java.util.Arrays.asList("issueId", "description"));
        required.put("resolveIssue", java.util.Arrays.asList("issueId"));
        return required;
    }

    /** Operations that reference a facet — the ones whose missing id can be derived from a label. */
    private static final List<String> FACET_OPERATIONS = java.util.Arrays.asList(
            "addFacet", "confirmFacet", "excludeFacet", "setFacetEmphasis");

    /**
     * The SHAPE of a domain identity. Live-gate 3 proved {@code minLength} alone lets prompt
     * placeholders and JSON fragments become canonical facets ("esp-idf_exclusion_placeholder_
     * if_exists_or_recreate_logic_…" was persisted and even re-referenced). A facetId is a short
     * technical id — lowercase letters/digits, hyphens/underscores, max 64 — or the operation is
     * rejected honestly.
     */
    private static final java.util.regex.Pattern FACET_ID_SHAPE =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9_-]{0,63}$");

    /**
     * Rejection text for a malformed facet identity, {@code null} when fine. Only FACET operations
     * are checked: on non-facet kinds the grammar-forced facetId is protocol padding the host
     * ignores — padding never becomes domain identity, so its shape does not matter.
     */
    private static String facetIdShapeProblem(String kind, Map<String, Object> operation) {
        if (!FACET_OPERATIONS.contains(kind)) {
            return null;
        }
        String facetId = text(operation.get("facetId"));
        if (facetId.isEmpty() || FACET_ID_SHAPE.matcher(facetId).matches()) {
            return null; // emptiness is firstMissingField's verdict, not a shape problem
        }
        String shown = facetId.length() > 40 ? facetId.substring(0, 40) + "…" : facetId;
        return kind + " facetId '" + shown + "' is not a technical id (want lowercase "
                + "letters/digits/hyphens, max 64 chars, e.g. \"esp-idf\" — never a sentence)";
    }

    /**
     * An id is a MACHINE concern: demanding that the model invent one made small models fail the whole
     * scope update over a bookkeeping field ("addFacet without 'facetId'" on every turn). When a facet
     * operation carries a label but no facetId, the id is DERIVED deterministically from the label — the
     * same label always yields the same id, so a later confirm/exclude by label references the same facet.
     * The MIRROR direction holds too (live-gate 2 lesson): the grammar now forces {@code facetId} on
     * every operation, so an {@code addFacet} may arrive id-only — its label then falls back to the id
     * instead of failing the update over the HUMAN half of the same bookkeeping. This is normalization
     * BEFORE validation, never partial application: an operation with neither id nor label still fails
     * the whole update.
     */
    private static Map<String, Object> withDerivedFacetId(String kind, Map<String, Object> operation) {
        if (!FACET_OPERATIONS.contains(kind)) {
            return operation;
        }
        if (!text(operation.get("facetId")).isEmpty()) {
            if ("addFacet".equals(kind) && text(operation.get("label")).isEmpty()) {
                Map<String, Object> labelled = new LinkedHashMap<String, Object>(operation);
                labelled.put("label", humanize(text(operation.get("facetId"))));
                return labelled;
            }
            return operation;
        }
        String slug = slugOf(text(operation.get("label")));
        if (slug.isEmpty()) {
            return operation; // nothing to derive from — validation reports the missing facetId honestly
        }
        Map<String, Object> derived = new LinkedHashMap<String, Object>(operation);
        derived.put("facetId", slug);
        return derived;
    }

    /**
     * The EMERGENCY label when only an id arrived: "rtos-grundlagen_einsteiger" → "Rtos
     * Grundlagen Einsteiger". Live-gate 3 showed the id-only path is common enough that raw ids
     * were leaking into the UI as labels — at least make them read like words. The real label
     * stays the model's job (the contract demands one on addFacet); this is the floor, not the
     * norm.
     */
    static String humanize(String facetId) {
        StringBuilder sb = new StringBuilder();
        for (String token : facetId.split("[-_]+")) {
            if (token.isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(Character.toUpperCase(token.charAt(0))).append(token.substring(1));
        }
        return sb.length() == 0 ? facetId : sb.toString();
    }

    /** A stable, short, lowercase-ascii id from a human label ("Neue Antriebe" → "neue-antriebe"). */
    static String slugOf(String label) {
        String lower = label.toLowerCase(java.util.Locale.ROOT)
                .replace("ä", "ae").replace("ö", "oe").replace("ü", "ue").replace("ß", "ss");
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < lower.length() && sb.length() < 40; index++) {
            char character = lower.charAt(index);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                sb.append(character);
            } else if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '-') {
                sb.append('-');
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    private static String firstMissingField(String kind, Map<String, Object> operation) {
        List<String> required = REQUIRED_FIELDS.get(kind);
        if (required == null) {
            return null; // setDeliverable carries only optional fields
        }
        for (String field : required) {
            if (text(operation.get(field)).isEmpty()) {
                return field;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object raw) {
        List<Map<String, Object>> objects = new ArrayList<Map<String, Object>>();
        if (!(raw instanceof List)) {
            return objects;
        }
        for (Object element : (List<Object>) raw) {
            if (element instanceof Map) {
                objects.add((Map<String, Object>) element);
            }
        }
        return objects;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String array(List<Map<String, Object>> values) {
        StringBuilder sb = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sb.append(',');
            }
            sb.append(object(values.get(index)));
        }
        return sb.append(']').toString();
    }

    private static String object(Map<String, Object> value) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> entry : value.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':').append(value(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    @SuppressWarnings("unchecked")
    private static String value(Object raw) {
        if (raw == null) {
            return "null";
        }
        if (raw instanceof Boolean || raw instanceof Number) {
            return String.valueOf(raw);
        }
        if (raw instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object element : (List<Object>) raw) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(value(element));
            }
            return sb.append(']').toString();
        }
        if (raw instanceof Map) {
            return object((Map<String, Object>) raw);
        }
        return quote(String.valueOf(raw));
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        sb.append(String.format("\\u%04x", (int) character));
                    } else {
                        sb.append(character);
                    }
                    break;
            }
        }
        return sb.append('"').toString();
    }
}
