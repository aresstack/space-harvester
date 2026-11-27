package com.aresstack.askai.research.runtime.service;

import java.util.HashMap;
import java.util.Map;

/**
 * Runtime-side PARSER of the host→runtime service-command envelope ({@code #RSC1#}), the sibling of the
 * runtime→host run wire ({@code #RSX1#}). A service command is a typed control instruction carried over the
 * prompt channel (the only host→agent channel) — never a chat turn: {@code ResearchAgentSession} dispatches it
 * before any model/TeamAgent/state logic. Encoded {@code key=value} with URL-encoded free-text values, so no
 * field ever contains a space. The ui-plugin's encoder of the same name is the ONLY producer; a round-trip
 * test pins the format across the process boundary.
 *
 * <pre>#RSC1# manual_search request_id=&lt;uuid&gt; query=&lt;urlenc&gt; [ranking=bm25|reranker visit_limit=N source_cap=N]
 *        [team=1 node=&lt;urlenc&gt; book=&lt;urlenc&gt; follow=N page_chars=N answer_tokens=N workers=N searches=N scope=<urlenc>]
 * #RSC1# set_language language=de|en
 * #RSC1# set_scope scope=&lt;urlenc&gt;
 * #RSC1# review_sources request_id=&lt;uuid&gt; captured_through=&lt;epoch-millis&gt;
 * #RSC1# generate_probes request_id=&lt;uuid&gt; req=&lt;urlenc-json&gt;
 * #RSC1# plan_queries request_id=&lt;uuid&gt; req=&lt;urlenc-json&gt;</pre>
 */
public final class ResearchServiceCommandWire {

    /** Envelope marker; a prompt starting with this is a machine control command, never a chat message. */
    public static final String MARKER = "#RSC1# ";

    private ResearchServiceCommandWire() {
    }

    public static boolean isServiceCommand(String text) {
        return text != null && text.startsWith(MARKER);
    }

    /** Parse a control envelope into a typed command, or {@code null} when it is not a service command. */
    public static ResearchServiceCommand parse(String text) {
        if (!isServiceCommand(text)) {
            return null;
        }
        String rest = text.substring(MARKER.length());
        int space = rest.indexOf(' ');
        String type = space < 0 ? rest : rest.substring(0, space);
        Map<String, String> fields = new HashMap<String, String>();
        if (space >= 0) {
            for (String token : rest.substring(space + 1).split(" ")) {
                int eq = token.indexOf('=');
                if (eq > 0) {
                    fields.put(token.substring(0, eq), token.substring(eq + 1));
                }
            }
        }
        return new ResearchServiceCommand(type, fields.get("request_id"), decode(fields.get("query")),
                fields.get("language"), decode(fields.get("scope")),
                parseLong(fields.get("captured_through")), decode(fields.get("req")),
                fields.get("ranking"), (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("visit_limit"))),
                (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("source_cap"))))
                .withTeam("1".equals(fields.get("team")), decode(fields.get("node")), decode(fields.get("book")),
                        (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("follow"))),
                        (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("page_chars"))),
                        (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("answer_tokens"))))
                .withTeamLanes((int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("workers"))),
                        (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("searches"))))
                .withSourcePreference(list(decode(fields.get("prefer"))), list(decode(fields.get("demote"))),
                        (int) Math.min(Integer.MAX_VALUE, parseLong(fields.get("prefer_shift"))));
    }

    /** A comma-joined list field; empty when absent. */
    private static java.util.List<String> list(String joined) {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (joined != null) {
            for (String part : joined.split(",")) {
                if (!part.trim().isEmpty()) {
                    out.add(part.trim());
                }
            }
        }
        return out;
    }

    /** An absent or malformed pin means "no bound" — a review still reads, it just reads everything. */
    private static long parseLong(String value) {
        try {
            return value == null || value.trim().isEmpty() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0L;
        }
    }

    private static String decode(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            return java.net.URLDecoder.decode(value, "UTF-8");
        } catch (Exception malformed) {
            return value; // degrade to the raw token, never crash the dispatch
        }
    }
}
