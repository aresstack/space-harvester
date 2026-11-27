package com.aresstack.askai.research.runtime.service;

/**
 * A typed host→runtime SERVICE COMMAND, parsed from the {@code #RSC1#} control envelope. A service command is
 * carried over the prompt channel (the only host→agent channel) but is NOT a chat turn: it is dispatched
 * before any model / TeamAgent / state logic and reaches a plain application service (e.g. the productive
 * search backend). It never transitions the host state machine and is phase-independent.
 */
public final class ResearchServiceCommand {

    /** A user-triggered web search: run the productive SearchStrategy and stream typed result events back. */
    public static final String TYPE_MANUAL_SEARCH = "manual_search";

    /**
     * A live working-language switch: only updates the runtime's {@link SessionResearchLanguage} — no model
     * call, no history entry, no state-machine command, no workflow event.
     */
    public static final String TYPE_SET_LANGUAGE = "set_language";

    /**
     * The EXPLICIT post-search review (issue #29): the user pressed "Neue Quellen auswerten". The TeamAgent
     * reviews the accepted sources; bracketed by manual_search_review started/finished on this request id.
     */
    public static final String TYPE_REVIEW_SOURCES = "review_sources";

    /**
     * The AUTHORITATIVE scope projection for the next turn: the host owns the research scope, the model
     * reads it as context. Without it the model would reconstruct the scope from the chat history, which is
     * exactly how earlier decisions get lost.
     */
    public static final String TYPE_SET_SCOPE = "set_scope";

    /**
     * Z3b-3: generate scope probes on the central main model — ONE model call, typed result back
     * over the run wire. Carried like every service command (never a chat turn, no state change);
     * the host owns the canonical scope and does ALL geometry — this command only produces material.
     */
    public static final String TYPE_GENERATE_PROBES = "generate_probes";

    /**
     * Z4b: choose at most ONE of the host-prepared advice candidates on the central main model —
     * one model call, typed decision back over the run wire. Like generate_probes this produces
     * MATERIAL only: no scope patch, no workflow action, no state change.
     */
    public static final String TYPE_CHOOSE_ADVICE = "choose_advice";

    /**
     * Plan PR 13: plan the search queries of the frozen concept — per node its base query plus model variants on
     * {@code phase.research.planner}; the typed plan goes back as one {@code #RSX1# query_plan} line. Material only:
     * no chat turn, no state change.
     */
    public static final String TYPE_PLAN_QUERIES = "plan_queries";

    /**
     * ADR 0001, lane F: the user starts an orchestrated research run for {@code query} (the orchestrator
     * plans; nothing runs until the user approves the plan).
     */
    public static final String TYPE_ORCHESTRATOR_START = "orchestrator_start";

    /**
     * The user approves: the proposed plan, the pending follow-ups or overrun, or the resumption of an
     * interrupted run. The user's decision, never the orchestrator's.
     */
    public static final String TYPE_ORCHESTRATOR_APPROVE = "orchestrator_approve";

    /** The user rejects the plan or stops the run; open tasks are canceled. */
    public static final String TYPE_ORCHESTRATOR_STOP = "orchestrator_stop";

    /** The user answers the orchestrator's question ({@code query} carries the answer). */
    public static final String TYPE_ORCHESTRATOR_ANSWER = "orchestrator_answer";

    /** Plan PR 14: rank the hits lexically. */
    public static final String RANKING_BM25 = "bm25";
    /** Plan PR 14: rank the hits with the installed cross-encoder; BM25 when it is unavailable. */
    public static final String RANKING_RERANKER = "reranker";

    private final String ranking;
    private final int visitLimit;
    private final int sourceCap;
    /** Plan T5: a planned query of the research team (its pages are read in a worker browser of their own). */
    private final boolean team;
    /** Plan T5: the concept node of a team query; empty otherwise. */
    private final String teamNode;
    /** Plan T5: the book title of a team query; empty otherwise. */
    private final String teamBook;
    /** Plan T4: at most this many links a page worker may pick per page (0 = none). */
    private final int teamFollow;
    /** Plan T4: at most this much page text goes into a page worker's prompt; 0 = the runtime's default. */
    private final int teamPageCharacters;
    /** Plan T4: the longest answer of one page worker turn (tokens); 0 = the runtime's default. */
    private final int teamAnswerTokens;
    /** Plan T4: how many tabs (page workers) read the hits of this team search side by side; 0 = one. */
    private int teamWorkers;
    /** Plan T4: at most this many new search queries a page worker may suggest per page. */
    private int teamSearches;
    /** The mission's preferred hosts and host suffixes (the ranking moves them up); empty = none. */
    private java.util.List<String> preferredHosts = java.util.Collections.emptyList();
    /** The URL parts of vendor pages (the ranking moves them down); empty = none. */
    private java.util.List<String> demotedMarkers = java.util.Collections.emptyList();
    /** The places a preferred hit moves up and a vendor page moves down; 0 = the ranking stays as it is. */
    private int preferenceShift;
    private final String type;
    private final String requestId;
    private final String query;
    private final String language;
    private final String scope;
    /**
     * For a review: the newest capture timestamp the review is about. It bounds WHICH sources this turn
     * reads, so what the model saw and what the host marks reviewed are the same set.
     */
    private final long capturedThrough;
    /** The JSON payload of a {@code generate_probes} command; empty otherwise. */
    private final String request;

    public ResearchServiceCommand(String type, String requestId, String query) {
        this(type, requestId, query, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language) {
        this(type, requestId, query, language, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope) {
        this(type, requestId, query, language, scope, 0L);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope, long capturedThrough) {
        this(type, requestId, query, language, scope, capturedThrough, null);
    }

    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope, long capturedThrough, String request) {
        this(type, requestId, query, language, scope, capturedThrough, request, null, 0, 0);
    }

    /**
     * Plan PR 14: a manual search of a planned query carries its ranking choice ({@code bm25}, {@code reranker};
     * empty = the typed-search default), the visit limit (top N) and the per-query source cap (0 = default).
     */
    public ResearchServiceCommand(String type, String requestId, String query, String language,
                                  String scope, long capturedThrough, String request, String ranking,
                                  int visitLimit, int sourceCap) {
        this(type, requestId, query, language, scope, capturedThrough, request, ranking, visitLimit, sourceCap,
                false, "", "", 0, 0, 0);
    }

    private ResearchServiceCommand(String type, String requestId, String query, String language,
                                   String scope, long capturedThrough, String request, String ranking,
                                   int visitLimit, int sourceCap, boolean team, String teamNode, String teamBook,
                                   int teamFollow, int teamPageCharacters, int teamAnswerTokens) {
        this.team = team;
        this.teamAnswerTokens = Math.max(0, teamAnswerTokens);
        this.teamFollow = Math.max(0, teamFollow);
        this.teamPageCharacters = Math.max(0, teamPageCharacters);
        this.teamNode = teamNode == null ? "" : teamNode.trim();
        this.teamBook = teamBook == null ? "" : teamBook.trim();
        String r = ranking == null ? "" : ranking.trim().toLowerCase(java.util.Locale.ROOT);
        this.ranking = RANKING_RERANKER.equals(r) || RANKING_BM25.equals(r) ? r : "";
        this.visitLimit = Math.max(0, visitLimit);
        this.sourceCap = Math.max(0, sourceCap);
        this.capturedThrough = capturedThrough;
        this.request = request == null ? "" : request;
        this.type = type == null ? "" : type;
        this.requestId = requestId == null ? "" : requestId;
        this.query = query == null ? "" : query;
        this.language = language == null ? "" : language;
        this.scope = scope == null ? "" : scope;
    }

    /** Plan T5: this command as research-team work with the page worker's brief (node and book). */
    public ResearchServiceCommand withTeam(boolean team, String node, String book, int follow, int pageCharacters,
                                           int answerTokens) {
        return new ResearchServiceCommand(type, requestId, query, language, scope, capturedThrough, request,
                ranking, visitLimit, sourceCap, team, node, book, follow, pageCharacters, answerTokens);
    }

    /** Plan T4: this team command with its lane count and the page workers' query suggestions per page. */
    public ResearchServiceCommand withTeamLanes(int workers, int searches) {
        ResearchServiceCommand copy = withTeam(team, teamNode, teamBook, teamFollow, teamPageCharacters,
                teamAnswerTokens);
        copy.teamWorkers = Math.max(0, workers);
        copy.teamSearches = Math.max(0, searches);
        return copy;
    }

    /** This command with the mission's source preference for the ranking of a planned query's hits. */
    public ResearchServiceCommand withSourcePreference(java.util.List<String> hosts, java.util.List<String> demoted,
                                                       int shift) {
        ResearchServiceCommand copy = withTeamLanes(teamWorkers, teamSearches);
        copy.preferredHosts = hosts == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(hosts));
        copy.demotedMarkers = demoted == null ? java.util.Collections.<String>emptyList()
                : java.util.Collections.unmodifiableList(new java.util.ArrayList<String>(demoted));
        copy.preferenceShift = Math.max(0, shift);
        return copy;
    }

    public java.util.List<String> getPreferredHosts() {
        return preferredHosts;
    }

    public java.util.List<String> getDemotedMarkers() {
        return demotedMarkers;
    }

    public int getPreferenceShift() {
        return preferenceShift;
    }

    public int getTeamWorkers() {
        return teamWorkers;
    }

    public int getTeamSearches() {
        return teamSearches;
    }

    /** Plan T4: the longest answer of one page worker turn (tokens); 0 = the runtime's default. */
    public int getTeamAnswerTokens() {
        return teamAnswerTokens;
    }

    /** Plan T4: how many links a page worker may pick per page (0 = none). */
    public int getTeamFollow() {
        return teamFollow;
    }

    /** Plan T4: how much page text a page worker reads; 0 = the runtime's default. */
    public int getTeamPageCharacters() {
        return teamPageCharacters;
    }

    /** Plan T5: whether a planned query runs as research-team work. */
    public boolean isTeam() {
        return team;
    }

    /** Plan T5: the concept node of a team query; empty otherwise. */
    public String getTeamNode() {
        return teamNode;
    }

    /** Plan T5: the book title of a team query; empty otherwise. */
    public String getTeamBook() {
        return teamBook;
    }

    /** The ranking choice of a planned query ({@link #RANKING_BM25}, {@link #RANKING_RERANKER}); empty otherwise. */
    public String getRanking() {
        return ranking;
    }

    /** How many of the ranked hits a planned query visits at most; 0 = the typed-search default. */
    public int getVisitLimit() {
        return visitLimit;
    }

    /** How many sources a planned query accepts at most; 0 = the typed-search default. */
    public int getSourceCap() {
        return sourceCap;
    }

    public String getType() {
        return type;
    }

    /** Correlates the command with its typed result/progress/failure events and with a later cancel. */
    public String getRequestId() {
        return requestId;
    }

    public String getQuery() {
        return query;
    }

    /** The language code ("en"/"de") of a {@code set_language} command; empty when absent. */
    public String getLanguage() {
        return language;
    }

    /** The rendered scope projection of a {@code set_scope} command; empty when absent. */
    public long getCapturedThrough() {
        return capturedThrough;
    }

    public String getScope() {
        return scope;
    }

    /** The JSON payload of a {@code generate_probes} command; empty when absent. */
    public String getRequest() {
        return request;
    }
}
