package com.aresstack.askai.research.agentwire;

import java.util.HashMap;
import java.util.Map;

/**
 * Host-side parser of the research wire: structured run events arrive as agent output lines with a
 * reserved machine envelope (see the runtime's encoder of the same name). A wire line is NEVER rendered as
 * chat text — it is decoded into typed backend events. The format is token-based ({@code key=value}, values
 * without spaces, an optional URL always last), deliberately not free text: the mapper never interprets
 * human-readable sentences.
 */
public final class ResearchRunWire {

    /** Envelope marker; identical to the runtime encoder's constant (pinned by a round-trip test). */
    public static final String MARKER = "#RSX1# ";

    public static final String TYPE_PROGRESS = "progress";
    public static final String TYPE_OUTCOME = "outcome";
    public static final String TYPE_LOG = "log";
    /** A user-attention transition (manual challenge required/resolved) — rendered visibly, never a log. */
    public static final String TYPE_ATTENTION = "attention";
    /** A validated workflow proposal from the TeamAgent (command + scope) — re-validated + executed host-side. */
    /** A one-shot "the greeting was delivered" signal — the host advances the scope state one step. */
    public static final String TYPE_GREETED = "greeted";
    /** A display-only scoping projection (search suggestions + advisory advice). */
    public static final String TYPE_SCOPEASSIST = "scopeassist";
    /** The proposed scope CHANGES as a neutral JSON document (applied to the persisted scope draft). */
    public static final String TYPE_SCOPEUPDATE = "scopeupdate";
    /** The proposed scope changes were malformed and were dropped AS A WHOLE — shown to the user. */
    public static final String TYPE_SCOPEUPDATE_REJECTED = "scopeupdate_rejected";
    /** The research brief markdown (the phase artifact) — persisted to its working copy on one path. */
    public static final String TYPE_BRIEF = "brief";
    /** A user-triggered (manual) web search lifecycle — correlated to its request by {@code request_id}. */
    public static final String TYPE_MANUAL_SEARCH_STARTED = "manual_search_started";
    public static final String TYPE_MANUAL_SEARCH_PROGRESS = "manual_search_progress";
    /** Plan T4: a page worker's message to the orchestrator, correlated with its planned query. */
    public static final String TYPE_MANUAL_SEARCH_TEAM_NOTE = "manual_search_team_note";
    /** Plan T4: how many pages of a team search are still open. */
    public static final String TYPE_MANUAL_SEARCH_OPEN_PAGES = "manual_search_open_pages";
    /** Plan T4: a new search query a page worker suggested, with the fence's verdict. */
    public static final String TYPE_MANUAL_SEARCH_TEAM_QUERY = "manual_search_team_query";
    public static final String TYPE_MANUAL_SEARCH_COMPLETED = "manual_search_completed";
    public static final String TYPE_MANUAL_SEARCH_REVIEW = "manual_search_review";
    public static final String TYPE_MANUAL_SEARCH_FAILED = "manual_search_failed";

    /** Z3b-3: the typed probe-generation answer; payload = result JSON, correlated by request_id. */
    public static final String TYPE_PROBES = "probes";

    /** Z4b: the typed advice-chooser answer; payload = decision JSON, correlated by request_id. */
    public static final String TYPE_ADVICE = "advice";

    /** Plan PR 13: the typed concept query plan; payload = plan JSON, correlated by request_id. */
    public static final String TYPE_QUERY_PLAN = "query_plan";

    private ResearchRunWire() {
    }

    public static boolean isWireLine(String text) {
        return text != null && text.startsWith(MARKER);
    }

    /** @return the event type token of a wire line ({@code progress|outcome|log}), or {@code null}. */
    public static String typeOf(String text) {
        if (!isWireLine(text)) {
            return null;
        }
        String rest = text.substring(MARKER.length());
        int space = rest.indexOf(' ');
        return space < 0 ? rest : rest.substring(0, space);
    }

    /** The free-text payload of a {@code log} line (everything after the type token). */
    public static String logText(String text) {
        String rest = text.substring(MARKER.length());
        int space = rest.indexOf(' ');
        return space < 0 ? "" : rest.substring(space + 1);
    }

    /** All {@code key=value} fields of a progress/outcome line (values contain no spaces). */
    public static Map<String, String> fields(String text) {
        Map<String, String> fields = new HashMap<String, String>();
        for (String token : text.substring(MARKER.length()).split(" ")) {
            int eq = token.indexOf('=');
            if (eq > 0) {
                fields.put(token.substring(0, eq), token.substring(eq + 1));
            }
        }
        return fields;
    }

    /**
     * A free-text field (search query, page title) that traveled URL-encoded so it never contains spaces
     * on the wire; returns the decoded text or {@code ""}.
     */
    public static String decodedField(Map<String, String> fields, String key) {
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception ex) {
            return value; // a malformed encoding degrades to the raw token, never crashes the mapper
        }
    }

    /**
     * A comma-joined list of URL-encoded values (a comma never appears inside a value — it encodes to
     * {@code %2C}). Each element is decoded; a malformed element degrades to its raw token, never crashes.
     */
    public static java.util.List<String> decodedList(Map<String, String> fields, String key) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        String value = fields.get(key);
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String part : value.split(",")) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                out.add(java.net.URLDecoder.decode(part, "UTF-8"));
            } catch (Exception ex) {
                out.add(part);
            }
        }
        return out;
    }

    public static int intField(Map<String, String> fields, String key) {
        try {
            return Integer.parseInt(fields.get(key));
        } catch (RuntimeException ex) {
            return 0;
        }
    }

    /**
     * Decode the {@code sugg} field: comma-joined {@code enc(query)|enc(purpose)|priority} records (delimiters
     * never occur inside a URL-encoded value, so an empty purpose stays aligned). A malformed record is
     * skipped, never crashing the mapper. Each returned array is {@code [query, purpose, priorityString]}.
     */
    public static java.util.List<String[]> decodedSuggestions(Map<String, String> fields) {
        java.util.List<String[]> out = new java.util.ArrayList<String[]>();
        String value = fields.get("sugg");
        if (value == null || value.isEmpty()) {
            return out;
        }
        for (String record : value.split(",")) {
            if (record.isEmpty()) {
                continue;
            }
            String[] parts = record.split("\\|", -1);
            if (parts.length < 3) {
                continue;
            }
            out.add(new String[]{decode(parts[0]), decode(parts[1]), parts[2]});
        }
        return out;
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception ex) {
            return value;
        }
    }
}
