package com.aresstack.askai.research.search;

/**
 * Host-side ENCODER of the host→runtime service-command envelope ({@code #RSC1#}) — the sibling of the
 * runtime→host run wire ({@code #RSX1#}). A service command is a typed control instruction carried over the
 * prompt channel (the only host→agent channel) but is NEVER a chat turn: the runtime dispatches it before
 * any model/TeamAgent/state logic. Encoded {@code key=value} with URL-encoded free-text values, so no field
 * ever contains a space. The runtime's parser of the same name is the ONLY consumer.
 *
 * <pre>#RSC1# manual_search request_id=&lt;uuid&gt; query=&lt;urlenc&gt; [ranking=bm25|reranker visit_limit=N source_cap=N]
 *        [team=1 node=&lt;urlenc&gt; book=&lt;urlenc&gt; follow=N page_chars=N answer_tokens=N workers=N searches=N scope=<urlenc>]
 * #RSC1# set_language language=de|en
 * #RSC1# review_sources request_id=&lt;uuid&gt;</pre>
 */
public final class ResearchServiceCommandWire {

    /** Envelope marker; a prompt starting with this is a machine control command, never a chat message. */
    public static final String MARKER = "#RSC1# ";

    private ResearchServiceCommandWire() {
    }

    /**
     * Encode a user-triggered web search command. The query travels URL-encoded (never contains a space);
     * the language snapshot ("en"/"de") is AUTHORITATIVE for this search and re-synchronises the runtime's
     * session language on arrival.
     */
    public static String manualSearch(String requestId, String query, String languageCode) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "query", query);
        if (languageCode != null && !languageCode.isEmpty()) {
            sb.append(" language=").append("de".equalsIgnoreCase(languageCode) ? "de" : "en");
        }
        return sb.toString();
    }

    /**
     * Plan PR 14: a manual search with the ranking choice, the visit limit (top N of the ranked hits) and the
     * per-query source cap of a planned query. {@code ranking} is {@code bm25} or {@code reranker}; an empty ranking
     * or a limit of 0 is left out, so the runtime keeps the typed-search defaults for it.
     */
    public static String manualSearch(String requestId, String query, String languageCode, String ranking,
                                      int visitLimit, int sourceCap) {
        StringBuilder sb = new StringBuilder(manualSearch(requestId, query, languageCode));
        if (ranking != null && !ranking.trim().isEmpty()) {
            sb.append(" ranking=").append("reranker".equalsIgnoreCase(ranking.trim()) ? "reranker" : "bm25");
        }
        if (visitLimit > 0) {
            sb.append(" visit_limit=").append(visitLimit);
        }
        if (sourceCap > 0) {
            sb.append(" source_cap=").append(sourceCap);
        }
        return sb.toString();
    }

    /**
     * Plan T5: a planned query run by the research team. Same search as {@link #manualSearch(String, String, String,
     * String, int, int)} through the one web search tool; {@code team=1} tells the runtime to read its pages in a
     * worker browser of their own, beside the other team queries, and {@code node}/{@code book} are the brief of the
     * page worker.
     */
    public static String teamSearch(String requestId, String query, String languageCode, String ranking,
                                    int visitLimit, int sourceCap, String nodeLabel, String bookTitle,
                                    int followLinks, int pageCharacters, int answerTokens, int workers,
                                    int searches, String area) {
        StringBuilder sb = new StringBuilder(manualSearch(requestId, query, languageCode, ranking, visitLimit,
                sourceCap));
        sb.append(" team=1");
        appendEncoded(sb, "node", nodeLabel);
        appendEncoded(sb, "book", bookTitle);
        sb.append(" follow=").append(Math.max(0, followLinks)); // 0 = the worker opens no further link
        if (pageCharacters > 0) {
            sb.append(" page_chars=").append(pageCharacters);
        }
        if (answerTokens > 0) {
            sb.append(" answer_tokens=").append(answerTokens);
        }
        sb.append(" workers=").append(Math.max(1, workers)).append(" searches=").append(Math.max(0, searches));
        appendEncoded(sb, "scope", area); // the Weidezaun: the page workers' new queries stay inside it
        return sb.toString();
    }

    /**
     * The mission's source preference appended to a search envelope: {@code prefer} (preferred hosts and host
     * suffixes), {@code demote} (URL parts of vendor pages), both comma-joined and URL-encoded, and
     * {@code prefer_shift} (the places they move). Nothing is appended without a shift or without any host or part.
     */
    public static String withSourcePreference(String envelope, java.util.List<String> preferredHosts,
                                              java.util.List<String> demotedMarkers, int shift) {
        boolean hosts = preferredHosts != null && !preferredHosts.isEmpty();
        boolean parts = demotedMarkers != null && !demotedMarkers.isEmpty();
        if (envelope == null || shift <= 0 || (!hosts && !parts)) {
            return envelope;
        }
        StringBuilder sb = new StringBuilder(envelope);
        if (hosts) {
            appendEncoded(sb, "prefer", join(preferredHosts));
        }
        if (parts) {
            appendEncoded(sb, "demote", join(demotedMarkers));
        }
        sb.append(" prefer_shift=").append(shift);
        return sb.toString();
    }

    private static String join(java.util.List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(value.trim().replace(",", ""));
        }
        return sb.toString();
    }

    /**
     * Encode a live working-language switch (best-effort sync for the next TeamAgent turn; the language
     * snapshot on operation requests stays authoritative). Codes are single tokens, no encoding needed.
     */
    public static String setLanguage(String languageCode) {
        return MARKER + "set_language language="
                + ("de".equalsIgnoreCase(languageCode) ? "de" : "en");
    }

    /**
     * Encode the AUTHORITATIVE scope projection for the next turn. The host owns the scope draft; sending
     * it as context is what keeps the model from rebuilding it out of the chat history (and losing
     * decisions in the process). Like set_language this is not a chat turn and changes no state.
     */
    public static String setScope(String renderedScope) {
        StringBuilder sb = new StringBuilder(MARKER).append("set_scope");
        appendEncoded(sb, "scope", renderedScope);
        return sb.toString();
    }

    /**
     * Encode the EXPLICIT post-search review action (issue #29): the user pressed "Neue Quellen auswerten".
     * The runtime brackets the review with {@code manual_search_review started/finished} events carrying
     * this request id — a search itself never triggers the review implicitly anymore.
     */
    public static String reviewSources(String requestId, long capturedThrough) {
        // The pin travels WITH the request: the review must read exactly the material the host will
        // record as reviewed, not whatever has arrived by the time the model answers.
        return MARKER + "review_sources request_id=" + (requestId == null ? "" : requestId)
                + " captured_through=" + Math.max(0L, capturedThrough);
    }

    /**
     * Z3b-3: encode a probe-generation request (the JSON payload built by the scope-side codec).
     * An INTERNAL host->runtime seam: one model call in the runtime, one typed {@code #RSX1#
     * probes} answer back on this request id — no MCP tool, no chat turn, no state change.
     */
    public static String generateProbes(String requestId, String requestJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("generate_probes")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "req", requestJson);
        return sb.toString();
    }

    /**
     * Plan PR 13: encode a concept query plan request (the JSON built by {@code QueryPlanWireCodec}). Internal
     * seam like generate_probes: the runtime plans every node, one typed {@code #RSX1# query_plan} answer comes
     * back on this request id — no chat turn, no state change.
     */
    public static String planQueries(String requestId, String requestJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("plan_queries")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "req", requestJson);
        return sb.toString();
    }

    /**
     * Z4b: encode an advice-chooser request (the finished offers as JSON). Internal seam like
     * generate_probes: one model call in the runtime, one typed {@code #RSX1# advice} answer back
     * on this request id — no chat turn, no state change.
     */
    public static String chooseAdvice(String requestId, String requestJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("choose_advice")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "req", requestJson);
        return sb.toString();
    }

    /**
     * ADR 0001 lane F: an orchestrator command of the USER ({@code orchestrator_start}, {@code _approve},
     * {@code _stop}, {@code _answer}); {@code text} is the question or the answer, empty otherwise. Like every
     * service command it never transitions the host state machine.
     */
    public static String orchestrator(String type, String text) {
        StringBuilder sb = new StringBuilder(MARKER).append(type);
        appendEncoded(sb, "query", text);
        return sb.toString();
    }

    private static void appendEncoded(StringBuilder sb, String key, String value) {
        if (value == null || value.isEmpty()) {
            return;
        }
        try {
            sb.append(' ').append(key).append('=')
                    .append(java.net.URLEncoder.encode(value, "UTF-8"));
        } catch (java.io.UnsupportedEncodingException ex) {
            // UTF-8 is guaranteed on every JVM; the field is simply omitted if it were missing.
        }
    }
}
