package com.aresstack.askai.research.runtime.loop;

/**
 * Agent-side encoder of the research wire: structured run events travel as agent output lines with a
 * reserved machine envelope. The UI-plugin's {@code ResearchRunWire} parser is the ONLY consumer; nothing in
 * this format is meant for (or shown to) humans, and the mapper never interprets human-readable text. The two
 * classes are deliberately small duplicates on either side of the process boundary (the runtime jar and the
 * plugin share no module); a round-trip test pins the format.
 *
 * <pre>
 * #RSX1# progress pages=6 sources=4 hosts=2 tools=12 min_sources=3 min_hosts=2 activity=READING_PAGE \
 *        query=&lt;urlenc&gt; host=pf4j.org title=&lt;urlenc&gt; url=…
 * #RSX1# outcome stop=TOOL_BUDGET_EXHAUSTED pages=10 sources=7 hosts=1 min_sources=3 min_hosts=2 \
 *        recoverable=true limitation=INSUFFICIENT_HOST_DIVERSITY action=CONTINUE_RESEARCH
 * #RSX1# log &lt;free text to end of line&gt;
 * </pre>
 *
 * <p>Values must never contain spaces (the parser splits on them): free-text values (search query, page
 * title) travel URL-encoded and are decoded by the plugin's {@code decodedField}.</p>
 */
public final class ResearchRunWire {

    /** Envelope marker; a MESSAGE starting with this is a machine event, never a chat bubble. */
    public static final String MARKER = "#RSX1# ";

    private ResearchRunWire() {
    }

    /** One in-place progress update with the structured activity context (query/host/title/url). */
    public static String progress(ResearchRunProgress p, ResearchRunBudget budget,
                                  ResearchRunActivity activity) {
        StringBuilder sb = new StringBuilder(MARKER).append("progress")
                .append(" pages=").append(p.getPagesVisited())
                .append(" sources=").append(p.getAcceptedSources())
                .append(" hosts=").append(p.getDistinctHosts().size())
                .append(" tools=").append(p.getToolCalls())
                .append(" min_sources=").append(budget.getMinimumAcceptedSources())
                .append(" min_hosts=").append(budget.getMinimumDistinctHosts())
                .append(" activity=").append(activity == null || activity.getToken().isEmpty()
                        ? "WORKING" : activity.getToken());
        if (activity != null) {
            appendEncoded(sb, "query", activity.getSearchQuery());
            appendEncoded(sb, "host", activity.getHost());
            appendEncoded(sb, "title", activity.getPageTitle());
            if (!activity.getUrl().isEmpty()) {
                sb.append(" url=").append(activity.getUrl()); // URLs never contain spaces; always LAST
            }
        }
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
            // UTF-8 is guaranteed on every JVM; if it were missing, the field is simply omitted.
        }
    }

    /** The terminal outcome of one run — the only basis for the user-facing result card. */
    public static String outcome(ResearchRunOutcome o) {
        return MARKER + "outcome"
                + " stop=" + o.getStopReason()
                + " pages=" + o.getPagesVisited()
                + " sources=" + o.getAcceptedSources()
                + " hosts=" + o.getDistinctHosts()
                + " min_sources=" + o.getMinimumSources()
                + " min_hosts=" + o.getMinimumDistinctHosts()
                + " recoverable=" + o.isRecoverable()
                + " limitation=" + o.getLimitation()
                + " action=" + o.getRecommendedAction();
    }

    /**
     * A user-attention transition: a manual challenge (CAPTCHA) requires the user ({@code REQUIRED}) or was
     * solved ({@code RESOLVED}). Never a failure, never a technical log line — the UI renders it visibly.
     */
    public static String attention(String reason, String domainFamily, String url, boolean resolved) {
        StringBuilder sb = new StringBuilder(MARKER).append("attention")
                .append(" reason=").append(reason == null || reason.isEmpty() ? "UNKNOWN" : reason)
                .append(" domain=").append(domainFamily == null ? "" : domainFamily)
                .append(" state=").append(resolved ? "RESOLVED" : "REQUIRED");
        if (url != null && !url.isEmpty()) {
            sb.append(" url=").append(url); // URLs never contain spaces; always the LAST field
        }
        return sb.toString();
    }

    /** Comma-join a list of URL-encoded values; commas never appear inside a value (they encode to %2C). */
    private static String joinEncoded(java.util.List<String> values) {
        if (values == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (value == null || value.isEmpty()) {
                continue;
            }
            try {
                String encoded = java.net.URLEncoder.encode(value, "UTF-8");
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(encoded);
            } catch (java.io.UnsupportedEncodingException ex) {
                // UTF-8 is guaranteed; a value is simply omitted if it were missing.
            }
        }
        return sb.toString();
    }

    /**
     * A phase-specific ASSISTANT PROJECTION for the scoping workspace: the search suggestions plus the
     * advisory advice. This is display-only action support — it carries NO research brief (the brief has its
     * own persistence route) and NO visualization (a separate visualizer owns that), and it never moves the
     * workflow. Each suggestion's query/purpose travel URL-encoded; suggestions are one field of {@code
     * enc(query)|enc(purpose)|priority} records joined by commas (delimiters never occur inside an encoded
     * value), so an empty purpose never misaligns the list.
     */
    public static String scopingProjection(String phaseId,
                                           java.util.List<ScopingProjectionSuggestion> suggestions,
                                           String adviceRecommendation, String adviceReason) {
        StringBuilder sb = new StringBuilder(MARKER).append("scopeassist");
        appendEncoded(sb, "phase", phaseId);
        if (adviceRecommendation != null && !adviceRecommendation.isEmpty()) {
            sb.append(" advice=").append(adviceRecommendation); // a fixed token (STAY|CONTINUE|NEUTRAL)
        }
        appendEncoded(sb, "advicereason", adviceReason);
        String encodedSuggestions = encodeSuggestions(suggestions);
        if (!encodedSuggestions.isEmpty()) {
            sb.append(" sugg=").append(encodedSuggestions);
        }
        return sb.toString();
    }

    /**
     * The scope UPDATE line: the neutral JSON document of proposed scope changes. Deliberately its own line
     * kind and not part of the display projection - one is what the user sees, the other is what the
     * application applies to the persisted scope.
     */
    public static String scopeUpdate(String phaseId, String documentJson) {
        StringBuilder sb = new StringBuilder(MARKER).append("scopeupdate");
        appendEncoded(sb, "phase", phaseId);
        appendEncoded(sb, "doc", documentJson);
        return sb.toString();
    }

    /**
     * The proposed scope changes were malformed and are dropped AS A WHOLE. Its own line so the host can
     * tell the user that the scope was NOT updated — silence here would let the conversation and the stored
     * scope drift apart, which is the one thing this design must prevent.
     */
    public static String scopeUpdateRejected(String phaseId, String reason) {
        StringBuilder sb = new StringBuilder(MARKER).append("scopeupdate_rejected");
        appendEncoded(sb, "phase", phaseId);
        appendEncoded(sb, "reason", reason);
        return sb.toString();
    }

    private static String encodeSuggestions(java.util.List<ScopingProjectionSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ScopingProjectionSuggestion suggestion : suggestions) {
            if (suggestion == null || suggestion.getQuery().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(encode(suggestion.getQuery())).append('|')
                    .append(encode(suggestion.getPurpose())).append('|')
                    .append(suggestion.getPriority());
        }
        return sb.toString();
    }

    private static String encode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException ex) {
            return "";
        }
    }

    /**
     * The research brief markdown produced this turn — the phase's PRIMARY artifact. Travels over the same
     * research wire so the host persists it on exactly ONE path (its working copy) and shows it in the
     * "Fragestellung" view. The markdown travels URL-encoded (it never contains a space on the wire).
     */
    public static String researchBrief(String phaseId, String briefMarkdown) {
        StringBuilder sb = new StringBuilder(MARKER).append("brief");
        appendEncoded(sb, "phase", phaseId);
        appendEncoded(sb, "content", briefMarkdown);
        return sb.toString();
    }

    /**
     * A one-shot signal that the model-backed greeting was delivered SUCCESSFULLY. The host advances the
     * scope state one step (so the greeting depends only on the state and is never repeated on a restart);
     * a failed greeting emits nothing, so the state stays fresh and the greeting is retried.
     */
    public static String greeted() {
        return MARKER + "greeted";
    }

    /** A technical diagnostic line (collapsible "technical details" only — never a chat bubble). */
    public static String log(String message) {
        return MARKER + "log " + (message == null ? "" : message.replace('\n', ' '));
    }

    // ------------------------------------------------------------------ user-triggered (manual) web search

    /** A user-triggered web search has started; the query travels URL-encoded. Correlated by requestId. */
    public static String manualSearchStarted(String requestId, String query) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search_started")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "query", query);
        return sb.toString();
    }

    /** Optional in-place progress note for a running manual search (URL-encoded), correlated by requestId. */
    public static String manualSearchProgress(String requestId, String note) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search_progress")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "note", note);
        return sb.toString();
    }

    /**
     * Plan T4: a page worker's message to the orchestrator (a finding, a gap, its final summary), URL-encoded and
     * correlated by the requestId of the planned query the worker visits.
     */
    public static String manualSearchTeamNote(String requestId, String note) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search_team_note")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "note", note);
        return sb.toString();
    }

    /** Plan T4: how many pages of a team search are still open (queued or being read). */
    public static String manualSearchOpenPages(String requestId, int open) {
        return MARKER + "manual_search_open_pages request_id=" + (requestId == null ? "" : requestId)
                + " open=" + Math.max(0, open);
    }

    /**
     * Plan T4: a new search query a page worker suggested while reading a hit of this search; {@code rejected} is
     * empty when the Weidezaun and the blacklist let it through, otherwise why it was dropped.
     */
    public static String manualSearchTeamQuery(String requestId, String query, String pageUrl, String rejected) {
        StringBuilder sb = new StringBuilder(MARKER).append("manual_search_team_query")
                .append(" request_id=").append(requestId == null ? "" : requestId);
        appendEncoded(sb, "query", query);
        appendEncoded(sb, "page", pageUrl);
        if (rejected != null && !rejected.isEmpty()) {
            appendEncoded(sb, "rejected", rejected);
        }
        return sb.toString();
    }

    /** A manual search finished: the result count and the strategy status (RESULTS|NO_RESULTS|…). */
    /**
     * Plan PR 14: as {@link #manualSearchCompleted(String, int, String)}; {@code keywordFallback} marks a planned
     * query that was ranked by BM25 because its chosen ranking was unavailable ({@code ranked_by=keywords}).
     */
    public static String manualSearchCompleted(String requestId, int results, String status,
                                               boolean keywordFallback) {
        return manualSearchCompleted(requestId, results, status) + (keywordFallback ? " ranked_by=keywords" : "");
    }

    public static String manualSearchCompleted(String requestId, int results, String status) {
        return MARKER + "manual_search_completed"
                + " request_id=" + (requestId == null ? "" : requestId)
                + " results=" + results
                + " status=" + (status == null || status.isEmpty() ? "UNKNOWN" : status);
    }

    /**
     * The bot's post-search REVIEW phase (skim the new sources, refresh suggestions) begins. The host shows a
     * thinking bubble + a busy (cancellable) composer until the matching finished line arrives, so the review
     * can never leave the UI hung.
     */
    public static String manualSearchReviewStarted(String requestId) {
        return MARKER + "manual_search_review"
                + " request_id=" + (requestId == null ? "" : requestId)
                + " state=started";
    }

    /**
     * The review ended — WITH its outcome. {@code state=finished} alone meant success and model failure
     * looked identical to the host, so a failed review still counted as "these sources have been reviewed"
     * and could never be offered again.
     */
    public static String manualSearchReviewFinished(
            String requestId,
            com.aresstack.askai.research.domain.search.PostSearchReviewOutcome outcome) {
        return MARKER + "manual_search_review"
                + " request_id=" + (requestId == null ? "" : requestId)
                + " state=finished"
                + " outcome=" + (outcome == null
                        ? com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED.token()
                        : outcome.token());
    }

    /** A manual search failed (or was cancelled/unavailable): a token reason, never a fallback to a no-op. */
    /**
     * Z3b-3: the typed answer to a {@code generate_probes} service command. The payload is the
     * probe-generation result JSON (success AND failure — the host decodes the status from it);
     * URL-encoded into one field like every rich payload on this wire.
     */
    public static String probeGeneration(String requestId, String payloadJson) {
        return MARKER + "probes request_id=" + (requestId == null ? "" : requestId)
                + " payload=" + encode(payloadJson);
    }

    /**
     * Z4b: the typed answer to a {@code choose_advice} service command — the decision JSON
     * (success AND failure), URL-encoded into one field, correlated by request id.
     */
    public static String adviceDecision(String requestId, String payloadJson) {
        return MARKER + "advice request_id=" + (requestId == null ? "" : requestId)
                + " payload=" + encode(payloadJson);
    }

    /** Plan PR 13: the typed answer to a {@code plan_queries} service command, correlated by request id. */
    public static String queryPlan(String requestId, String payloadJson) {
        return MARKER + "query_plan request_id=" + (requestId == null ? "" : requestId)
                + " payload=" + encode(payloadJson);
    }

    public static String manualSearchFailed(String requestId, String reason) {
        return MARKER + "manual_search_failed"
                + " request_id=" + (requestId == null ? "" : requestId)
                + " reason=" + (reason == null || reason.isEmpty() ? "UNKNOWN" : reason);
    }
}
