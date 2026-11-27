package com.aresstack.askai.research.runtime;



import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.aresstack.askai.research.runtime.loop.ToolInvoker;

/**
 * The research agent of ONE session, running IN-PROCESS inside the research plugin (formerly the external
 * Solon ACP agent process). It mirrors host state and NEVER owns a research state machine — the plugin/host is
 * the only transition authority; the agent only calls the host tools it is offered, through the in-process
 * {@link ToolInvoker} lanes (research control, browser bridge, internal service).
 *
 * <p>Readiness is REAL, not configured: {@link #open()} lists the research lane's tools and calls
 * {@code research_status()}; only if that round-trip succeeds does the session open (otherwise the host start
 * fails atomically). The first prompt turn then reports {@code RESEARCH_MCP_READY} (and
 * {@code BROWSER_NOT_AVAILABLE} when no browser lane exists — visible, never fatal, never a silent fallback).
 * Every turn streams its lines — assistant text and typed {@code #RSX1#} wire lines — to the {@link AgentOutput}
 * of the turn; the tools stay tools. Logs go to STDERR only.</p>
 */
public final class ResearchAgentSession {

    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final ResearchAgentEnvironment environment;
    /** The research-control lane (host tools: research_status, source_*, concept_* ...). */
    private final ToolInvoker research;
    /** The browser lane (the host's browser bridge), or null when no browser backend exists. */
    private final ToolInvoker browser;
    /** The internal service lane (manual_source_accept, search_scope_*), or null when absent. */
    private final ToolInvoker service;
    private volatile boolean readinessAnnounced;
    /**
     * The session's hot-swappable main-model transport and the single model-backed conversation engine built
     * on top of it. Both are created at session/new for EVERY session, INDEPENDENT of the browser: the
     * greeting, scoping and outline conversation runs on the central main model while the browser stays
     * STOPPED. When no valid descriptor is configured the transport is an {@code UnavailableMainModelChat}, so
     * the agent surfaces an honest, retryable {@code MODEL_UNAVAILABLE} rather than a static fake dialogue.
     */
    private com.aresstack.askai.research.runtime.team.ReloadableMainModelChat mainModelChat;
    /**
     * One hot-swappable transport per model role (ADR 0001, 4.1), keyed by role id, slot and context size and
     * bound to them: the conversation, the scope sweep's generator and chooser, the readiness judge, and any
     * role a later caller asks for through {@link #roleChats()}. A reload swaps all of them, a cancel aborts
     * all of them.
     */
    private final java.util.Map<String, RoleTransport> roleChats = new java.util.LinkedHashMap<String, RoleTransport>();

    /** A role and the transport built for it. */
    private static final class RoleTransport {
        final com.aresstack.askai.research.runtime.role.ResearchRole role;
        final com.aresstack.askai.research.runtime.team.ReloadableMainModelChat chat;

        RoleTransport(com.aresstack.askai.research.runtime.role.ResearchRole role,
                      com.aresstack.askai.research.runtime.team.ReloadableMainModelChat chat) {
            this.role = role;
            this.chat = chat;
        }
    }
    /**
     * The host's model port (ADR 0001, 3.2): the ONLY way this runtime reaches a model. Null when the host
     * offers none (an older host or a test without models); every model role is then honestly unavailable.
     */
    private final com.aresstack.askai.agent.model.port.ModelPort modelPort;
    /**
     * ADR 0001, lane F: the project's blackboard for the orchestrated run (the plugin's project store), or
     * null when the host hands none (the run then keeps its tasks in memory for the session).
     */
    private volatile com.aresstack.askai.research.runtime.orchestrator.TaskBoard taskBoard;
    /** The orchestrated run of this session, built on first use. */
    private com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession orchestrator;
    private volatile com.aresstack.askai.research.runtime.browser.BrowserUserContextPool browserUserContexts;
    /** The orchestrated run's acquisition on worker user contexts (null without them); cancel ends its waits. */
    private volatile com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition workerAcquisition;
    /** The output of the turn running the orchestrated cycle: the worker's search runs report there. */
    private volatile AgentOutput orchestratorTurn;
    /** The orchestrated run's chats (weakly held: a finished package's chat goes with it), cancelled with the turn. */
    private final java.util.Set<com.aresstack.askai.research.runtime.team.MainModelChat> orchestratorChatsInFlight =
            java.util.Collections.synchronizedSet(java.util.Collections.newSetFromMap(
                    new java.util.WeakHashMap<com.aresstack.askai.research.runtime.team.MainModelChat, Boolean>()));
    /** The work packages' metered layout-repair transports, cancelled with the turn like the chats. */
    private final java.util.Set<com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient>
            orchestratorInferenceInFlight = java.util.Collections.synchronizedSet(
            java.util.Collections.newSetFromMap(new java.util.WeakHashMap<
                    com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient, Boolean>()));
    /** When the LAST user search started — the lower edge of "the new sources" its review reads. */
    private volatile long lastManualSearchStartedAt;
    /** The request id of the LAST manual search — the default scope of /review-sources. */
    private volatile String lastManualSearchRequestId = "";
    private com.aresstack.askai.research.runtime.team.ResearchTeamAgent teamAgent;
    /** Whether the HOST offers the Konzeptpapier tools (detected from the MCP tool list at startup). */
    private boolean conceptToolsAvailable;
    /** Tool-round / repair budgets for the concept loop — the user's settings via env hand-off. */
    /**
     * The answer budget of one orchestrator decision and of a worker answer (planner, verifier, web researcher;
     * the writer has its own setting): the 4,096 these roles always had, now their own value instead of the
     * removed global "Answer budget" setting (plan PR 03).
     */
    static final int WORKER_ANSWER_TOKENS = 4096;

    private int conceptMaxToolRounds =
            com.aresstack.askai.research.runtime.team.ConceptToolRounds.DEFAULT_MAX_TOOL_ROUNDS;
    private int conceptMaxRepairAttempts =
            com.aresstack.askai.research.runtime.team.ConceptToolRounds.DEFAULT_MAX_REPAIR_ATTEMPTS;
    /**
     * The session's live working language (runtime mirror). set_language updates it best-effort; the
     * language snapshot on operation requests (manual_search) is authoritative and re-synchronises it.
     */
    /**
     * Initialised from the LAUNCH environment, not from a control prompt: a set_language envelope travels
     * over the same asynchronous prompt channel as the first turn, so whichever won the race decided the
     * greeting's language — German, English, German, English across otherwise identical sessions. The host
     * knows the language when it starts this process, so it is session context from the first turn on.
     */
    private final com.aresstack.askai.research.runtime.service.SessionResearchLanguage sessionLanguage;
    /** The host's AUTHORITATIVE scope projection for the next turn (set_scope); empty until one arrives. */
    private final com.aresstack.askai.research.runtime.service.SessionScopeFence scopeFence =
            new com.aresstack.askai.research.runtime.service.SessionScopeFence();
    /**
     * The MANDATORY reranker, built ONCE at session/new for a browser session (validated + endpoint
     * readiness-checked there, not mid-prompt). Null only when this session has no browser endpoint.
     */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker reranker;
    /**
     * The initial-search strategy chosen by the session snapshot, built ONCE at session/new (like the
     * reranker) so a running session never switches strategy. Null means "keep the loop's default legacy
     * browser strategy" — the unchanged browser SERP path.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy searchStrategy;
    /**
     * The productive structured-inference port for model-backed SERP layout repair, built ONCE at
     * session/new from the host-published inference descriptor (the central main model). OPTIONAL: when no
     * valid descriptor is present it is the honest {@code UnavailableStructuredInferencePort} fallback.
     */
    private com.aresstack.askai.browser.search.inference.StructuredInferencePort inferencePort;
    /** Hot-reload of the inference descriptor: applied ONLY between turns, last-good retained on failure. */
    private com.aresstack.askai.research.runtime.inference.ModelDescriptorReloadController reloadController;
    private com.aresstack.askai.research.runtime.inference.ModelDescriptorWatcher descriptorWatcher;

    /** Outer bound the adapter waits on a provider future; beyond the client's own request timeout. */
    private static final long SEARCH_PROVIDER_TIMEOUT_MILLIS = 90_000L;
    /**
     * Runtime-scoped async search-provider registry: ONE {@code WebSearchProvidersModule} (and its
     * AsyncHttpClients) for the whole agent process, opened lazily when an API_PROVIDER strategy is first
     * needed and closed once at JVM shutdown. Never one client per research run.
     */
    private com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
            searchProviderRegistry;
    /**
     * Canonical URLs visited across ALL runs of this agent process (one process per session): a
     * CONTINUE_RESEARCH turn gets a fresh budget but never navigates the same target pages again.
     */
    private final java.util.Set<String> visitedAcrossRuns =
            java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<String>());

    /**
     * @param environment the ASKAI_* hand-off (session identity, snapshot paths, settings)
     * @param research    the research-control lane (mandatory)
     * @param browser     the browser lane, or null when no browser backend exists (honestly absent)
     * @param service     the internal service lane, or null when absent
     */
    public ResearchAgentSession(ResearchAgentEnvironment environment, ToolInvoker research,
                                ToolInvoker browser, ToolInvoker service) {
        this(environment, research, browser, service, null);
    }

    /**
     * @param modelPort the host's model port, or null when the host offers none (model roles then report an
     *                  honest {@code MODEL_UNAVAILABLE})
     */
    public ResearchAgentSession(ResearchAgentEnvironment environment, ToolInvoker research,
                                ToolInvoker browser, ToolInvoker service,
                                com.aresstack.askai.agent.model.port.ModelPort modelPort) {
        this.modelPort = modelPort;
        this.environment = environment;
        this.research = research;
        this.browser = browser;
        this.service = service;
        // Initialised from the hand-off, not from a control prompt: the host knows the language when it
        // starts this session, so it is session context from the first turn on.
        this.sessionLanguage = com.aresstack.askai.research.runtime.service.SessionResearchLanguage
                .fromEnvironment(environment.get("ASKAI_RESEARCH_LANGUAGE"));
        System.err.println("[research-agent] " + environment);
    }

    /** The project's blackboard for the orchestrated run; set before the first turn. */
    public void setTaskBoard(com.aresstack.askai.research.runtime.orchestrator.TaskBoard board) {
        this.taskBoard = board;
    }

    /**
     * The chat's worker browser user contexts; set before the first turn. With a pool that has capacity, every
     * search run of the orchestrated run's web research worker browses in its OWN user context (own cookies,
     * storage, sign-in), and the executor's browser capacity is the pool's. Null or without capacity: the
     * workers share the chat browser one after another, bounded by the orchestrator's browser-slot setting.
     */
    public void setBrowserUserContexts(com.aresstack.askai.research.runtime.browser.BrowserUserContextPool pool) {
        this.browserUserContexts = pool;
    }

    private boolean hasBrowser() {
        return browser != null;
    }

    private boolean hasService() {
        return service != null;
    }

    /**
     * Open the session: a REAL readiness check against the research lane (tool names, then research_status),
     * then the model-backed TeamAgent and — for a browser session — the mandatory reranker, the search
     * strategy and the inference port. A failure here fails the session start atomically.
     */
    public void open() {
        boolean hasStatus = false;
        for (String tool : research.toolNames()) {
            if ("research_status".equals(tool)) {
                hasStatus = true;
            }
            // Konzeptpapier capability detection: only a host that OFFERS the concept tools gets
            // the concept prompt block and the tool-round loop — against an older host the
            // scoping behaviour stays exactly as before.
            if ("concept_read".equals(tool)) {
                conceptToolsAvailable = true;
            }
        }
        if (!hasStatus) {
            throw new IllegalStateException("research_status is not offered by the research endpoint");
        }
        String status = researchCall("research_status", Collections.<String, Object>emptyMap());
        System.err.println("[research-agent] research_status ok: " + status);

        // The model-backed TeamAgent is created for EVERY session, BEFORE (and independent of) the browser:
        // the conversation runs on the central main model even while the browser stays STOPPED. It must NOT
        // live under hasBrowser() or the research loop.
        // Each phase runs on its own slot (phase.<id>, plan 5.1); the Concept transport is created up front.
        this.mainModelChat = phaseChat(com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry
                .SCOPING_PHASE_ID);
        for (com.aresstack.askai.research.runtime.role.ResearchRole worker
                : com.aresstack.askai.research.runtime.role.ResearchRoles.workers()) {
            // The layout repair runs over its own structured-inference transport (buildInferencePort).
            if (worker != com.aresstack.askai.research.runtime.role.ResearchRoles.SERP_LAYOUT_REPAIR) {
                roleChat(worker);
            }
        }
        // The assembler reads the session's LIVE working language per turn: a set_language between turns
        // changes the next turn's context, never the history.
        this.teamAgent = new com.aresstack.askai.research.runtime.team.ResearchTeamAgent(
                new com.aresstack.askai.research.runtime.team.PhaseChats() {
                    public com.aresstack.askai.research.runtime.team.MainModelChat chatFor(String phaseId) {
                        return phaseChat(phaseId);
                    }
                },
                com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry.defaults(
                        // Settings checkbox "Immer Suchvorschläge anbieten" (host env hand-off, default off).
                        "true".equalsIgnoreCase(environment.get("ASKAI_SCOPING_ALWAYS_SUGGEST")),
                        conceptToolsAvailable),
                new com.aresstack.askai.research.runtime.team.PhaseContextAssembler(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .CurrentLanguage() {
                            public String displayName() {
                                return sessionLanguage.displayName();
                            }
                        }).withCurrentConcept(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .CurrentConcept() {
                            public String rendered() {
                                // K2e: with concept tools active, EVERY inference sees the
                                // persisted workpiece BEFORE answering — a type=none turn must
                                // describe reality, not the conversation. Best effort: a failed
                                // fetch just omits the block.
                                if (!conceptToolsAvailable) {
                                    return "";
                                }
                                try {
                                    return com.aresstack.askai.research.runtime.team
                                            .TeamAgentPlaybook.currentConceptContext(
                                                    conceptToolCall(wholeConceptRead()),
                                                    "de".equalsIgnoreCase(sessionLanguage.code()));
                                } catch (Exception unavailable) {
                                    return "";
                                }
                            }
                        }).withCurrentScope(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .CurrentScope() {
                            public String rendered() {
                                return scopeFence.rendered();
                            }
                        }).withCurrentConceptSummary(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .CurrentConceptSummary() {
                            public String rendered() {
                                // Plan PR 16: later phases see the frozen concept read-only, never
                                // the live concept with its edit rules. Best effort, like the concept.
                                return hostBlock("concept_summary", "No approved concept.");
                            }
                        }).withCurrentChangeRequest(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .CurrentChangeRequest() {
                            public String forPhase(String phaseId) {
                                // The host answers for its live phase, which is the turn's phase.
                                return hostBlock("phase_change_request", "No change request.");
                            }
                        }).withPhaseArtifact(
                        new com.aresstack.askai.research.runtime.team.PhaseContextAssembler
                                .PhaseArtifact() {
                            public String rendered(String phaseId) {
                                // Plan PR 15: the Sources block (search plan, coverage per node); plan PR 21: the
                                // Outline block (ids, titles, coverage per chapter). Document follows in PR 29.
                                String phase = phaseId == null ? "" : phaseId.trim();
                                if (com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry
                                        .RESEARCH_PHASE_ID.equalsIgnoreCase(phase)) {
                                    return hostBlock("search_plan_overview", "No search plan.");
                                }
                                return "outline".equalsIgnoreCase(phase) ? hostBlock("outline_read", "No outline.") : "";
                            }
                        }));
        // Konzeptpapier loop budgets (host env hand-off): tool rounds ≠ repair attempts — one is the
        // WORK budget, the other the ERROR tolerance; both are settings, never hidden constants.
        conceptMaxToolRounds = envInt("ASKAI_CONCEPT_TOOL_ROUNDS", conceptMaxToolRounds);
        conceptMaxRepairAttempts = envInt("ASKAI_CONCEPT_REPAIR_ATTEMPTS", conceptMaxRepairAttempts);
        System.err.println("[research-agent] TeamAgent ready on " + mainModelChat.modelName()
                + " conceptTools=" + conceptToolsAvailable
                + (conceptToolsAvailable
                        ? " rounds=" + conceptMaxToolRounds + " repairs=" + conceptMaxRepairAttempts
                        : ""));

        // A browser research session REQUIRES the mandatory reranker. Build and readiness-check it here,
        // at session/new — a missing/invalid snapshot or an unreachable endpoint fails the session start
        // atomically, never mid-run and never with a silent raw-order fallback.
        if (hasBrowser()) {
            this.reranker = buildMandatoryReranker();
            // Fix the initial-search strategy for the whole session here (null → unchanged browser path).
            this.searchStrategy = buildSearchStrategy();
            if (this.searchStrategy == null) {
                // Browser (SERP): Google runs via Serp4J in AskAI's own search browser (askai #31), built for the
                // engine table each search reads (browserEngines), so a saved change needs no restart.
                this.googleSearch = new com.aresstack.askai.research.runtime.search.GoogleSearchForEngineTable(
                        (resultPages, delayMillis) -> com.aresstack.askai.research.runtime.search
                                .GoogleSearchBrowserStrategy.create(ensureSearchBrowser(),
                                        java.util.Locale.getDefault(), environment.get("ASKAI_GOOGLE_SEARCH_URL"),
                                        resultPages, delayMillis));
            }
            // OPTIONAL model-backed SERP layout repair over the central main model (honest fallback else).
            this.inferencePort = buildInferencePort();
        }
        // Hot-reload is INDEPENDENT of the browser: a mid-session main-model change must reach the TeamAgent
        // too. Watch the inference descriptor so the switch is picked up at the next turn boundary (never
        // mid-request), keeping the last good config on any failure.
        startInferenceHotReload();
    }

    /**
     * Read + strictly validate the reranker start snapshot, confirm the endpoint is reachable, and build
     * the single {@link com.aresstack.askai.research.runtime.rerank.SearchResultReranker} for this
     * session. Every failure is a hard session/new failure.
     */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker buildMandatoryReranker() {
        if (!environment.hasReranker()) {
            throw new IllegalStateException("ASKAI_RERANKER_CONFIG is required for a browser research "
                    + "session (the local reranker is mandatory; no raw-order fallback).");
        }
        return buildRerankerFromPath(environment.rerankerConfigPath);
    }

    /**
     * Per-language reranker snapshots, built lazily from the host-published per-language files and cached.
     * A search resolves its reranker HERE with its immutable language snapshot: the next search after a
     * language switch uses the new language's reranker, while a running search keeps the instance it was
     * constructed with. When no per-language snapshot is published, the session-start default serves every
     * language (the deliberate initial configuration — both entries are the same selected model today). A
     * published-but-unusable language snapshot FAILS the search visibly, never falls back to another
     * language's model.
     */
    private final java.util.Map<String, com.aresstack.askai.research.runtime.rerank.CandidateReranker>
            rerankerByLanguage = new java.util.HashMap<String,
                    com.aresstack.askai.research.runtime.rerank.CandidateReranker>();

    /** The lexical default scorer; ONE instance is enough (stateless per call). */
    private final com.aresstack.askai.research.runtime.rerank.Bm25CandidateReranker bm25Reranker =
            new com.aresstack.askai.research.runtime.rerank.Bm25CandidateReranker();

    /** "KI-Reranker verwenden" (host setting, per spawn): default OFF → lexical BM25, no model. */
    private boolean useAiReranker() {
        return "true".equalsIgnoreCase(environment.get("ASKAI_USE_AI_RERANKER"));
    }

    private com.aresstack.askai.research.runtime.rerank.CandidateReranker rerankerFor(String languageCode) {
        if (!useAiReranker()) {
            return bm25Reranker; // the user's default: cheap lexical scoring, model only on opt-in
        }
        return crossEncoderFor(languageCode);
    }


    /**
     * Plan PR 14 (D5): the ranking of a planned query, or {@code null} for a typed search (no ranking field).
     * {@code reranker} ranks with the installed cross-encoder of the search language whatever "Use AI reranker"
     * says, and falls back to BM25 per call when it is unavailable; {@code bm25} ranks lexically.
     */
    private com.aresstack.askai.research.runtime.rerank.PlannedQueryReranker plannedRanking(
            com.aresstack.askai.research.runtime.service.ResearchServiceCommand command, String languageCode) {
        if (command.getRanking().isEmpty()) {
            return null;
        }
        if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.RANKING_BM25
                .equals(command.getRanking())) {
            return com.aresstack.askai.research.runtime.rerank.PlannedQueryReranker.keywords(bm25Reranker,
                    command.getVisitLimit()).withSourcePreference(sourcePreference(command));
        }
        com.aresstack.askai.research.runtime.rerank.CandidateReranker crossEncoder;
        try {
            crossEncoder = crossEncoderFor(languageCode);
        } catch (RuntimeException unavailable) {
            System.err.println("[manual-search] planned query: cross-encoder unavailable, ranked by keywords: "
                    + unavailable.getMessage());
            crossEncoder = null;
        }
        return com.aresstack.askai.research.runtime.rerank.PlannedQueryReranker.ranked(crossEncoder, bm25Reranker,
                command.getVisitLimit()).withSourcePreference(sourcePreference(command));
    }

    /** The mission's source preference a planned query carries ({@code NONE} when it carries none). */
    private static com.aresstack.askai.research.runtime.rerank.SourcePreferenceRanking sourcePreference(
            com.aresstack.askai.research.runtime.service.ResearchServiceCommand command) {
        return new com.aresstack.askai.research.runtime.rerank.SourcePreferenceRanking(command.getPreferredHosts(),
                command.getDemotedMarkers(), command.getPreferenceShift());
    }

    /** The installed cross-encoder of a language, independent of "Use AI reranker"; {@code null} when none. */
    private com.aresstack.askai.research.runtime.rerank.CandidateReranker crossEncoderFor(String languageCode) {
        String lang = "de".equalsIgnoreCase(languageCode) ? "de" : "en";
        String path = "de".equals(lang) ? environment.rerankerConfigDePath : environment.rerankerConfigEnPath;
        if (path == null) {
            return reranker;
        }
        synchronized (rerankerByLanguage) {
            com.aresstack.askai.research.runtime.rerank.CandidateReranker cached = rerankerByLanguage.get(lang);
            if (cached == null) {
                cached = path.equals(environment.rerankerConfigPath) && reranker != null
                        ? reranker : buildRerankerFromPath(path);
                rerankerByLanguage.put(lang, cached);
            }
            return cached;
        }
    }

    private com.aresstack.askai.research.runtime.rerank.CandidateReranker buildRerankerFromPath(
            String configPath) {
        com.aresstack.askai.agent.model.reranker.RerankerConfigurationDocument document;
        try {
            document = com.aresstack.askai.research.runtime.rerank.RerankerConfigurationLoader
                    .load(configPath);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("The reranker configuration is unusable ("
                    + configPath + "): " + ex.getMessage(), ex);
        }
        com.aresstack.askai.agent.model.reranker.RerankerEndpointDescriptor descriptor =
                document.descriptor;
        if (!descriptor.hasCapability(
                com.aresstack.askai.agent.model.reranker.RerankerCapability.RERANK)) {
            throw new IllegalStateException("The reranker endpoint does not advertise RERANK.");
        }
        if (descriptor.scoreSemantics
                != com.aresstack.askai.agent.model.reranker.RerankerScoreSemantics.RAW_LOGIT) {
            throw new IllegalStateException("The reranker endpoint must use RAW_LOGIT scores (was "
                    + descriptor.scoreSemantics + ").");
        }
        com.aresstack.askai.research.runtime.rerank.HttpRerankerClient client =
                new com.aresstack.askai.research.runtime.rerank.HttpRerankerClient(descriptor);
        try {
            client.probeReadiness();
        } catch (com.aresstack.askai.research.runtime.rerank.RerankerClientException ex) {
            throw new IllegalStateException("The reranker endpoint is not ready: " + ex.getMessage(),
                    ex);
        }
        com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy policy =
                new com.aresstack.askai.research.runtime.rerank.SearchResultSelectionPolicy(
                        descriptor.selectionConfiguration);
        System.err.println("[research-agent] reranker ready: " + descriptor.modelName);
        return new com.aresstack.askai.research.runtime.rerank.SearchResultReranker(client, policy,
                descriptor.modelName, descriptor.scoreSemantics);
    }

    /**
     * Build one model role's chat transport over the host's model port (ADR 0001, 3.2): the role names its
     * model slot, the platform resolves it (exact, parent, {@code default}; never the chat model). The
     * host-published inference descriptor ({@code ASKAI_INFERENCE_CONFIG}) still gates it: the host writes it
     * from the {@code default} slot, and it carries the user's per-call timeout. An absent or invalid descriptor, or a
     * host without a model port, is NOT fatal and NEVER a static fake dialogue: it yields an
     * {@code UnavailableMainModelChat}, so the very first greeting is an honest, retryable
     * {@code MODEL_UNAVAILABLE}. A later valid descriptor is swapped in via {@link ReloadableMainModelChat}.
     */
    private com.aresstack.askai.research.runtime.team.MainModelChat buildModelChat(
            com.aresstack.askai.research.runtime.role.ResearchRole role) {
        com.aresstack.askai.agent.model.port.ModelSlot slot = role.getSlot();
        if (!environment.hasInference()) {
            System.err.println("[research-agent] no inference descriptor — model slot " + slot + " unavailable");
            // Prefer the actionable reason the host passed; fall back to a self-explanatory default.
            String reason = environment.inferenceUnavailableReason != null
                    ? environment.inferenceUnavailableReason
                    : "No research model is available. Set it under Research settings -> Models, then "
                            + "reopen the research tab.";
            return new com.aresstack.askai.research.runtime.team.UnavailableMainModelChat(reason);
        }
        if (modelPort == null) {
            System.err.println("[research-agent] the host offers no model port — model slot " + slot
                    + " unavailable");
            return new com.aresstack.askai.research.runtime.team.UnavailableMainModelChat(
                    "This AskAI host offers no model port; update AskAI to use the research assistant.");
        }
        try {
            com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument document =
                    com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader
                            .load(environment.inferenceConfigPath);
            return com.aresstack.askai.research.runtime.team.PortMainModelChat.forRole(modelPort, role,
                    callTimeout(document));
        } catch (java.io.IOException ex) {
            System.err.println("[research-agent] main-model descriptor unusable ("
                    + environment.inferenceConfigPath + "): " + ex.getMessage()
                    + " — model slot " + slot + " starts in MODEL_UNAVAILABLE");
            return new com.aresstack.askai.research.runtime.team.UnavailableMainModelChat(
                    "main-model descriptor unusable: " + ex.getMessage());
        }
    }

    /**
     * The transport of {@code role}: created on first use (bound to the role's slot and context size) and
     * then reused, so the reload and the cancel reach it. Transports are keyed by role id, slot and context
     * size, so a role with a context override ({@link com.aresstack.askai.research.runtime.role.ResearchRole
     * #withContextTokens}) gets its own transport instead of silently reusing the default one.
     */
    private com.aresstack.askai.research.runtime.team.ReloadableMainModelChat roleChat(
            com.aresstack.askai.research.runtime.role.ResearchRole role) {
        String key = role.getId() + "@" + role.getSlot().getName() + "#" + role.getContextTokens();
        synchronized (roleChats) {
            RoleTransport transport = roleChats.get(key);
            if (transport == null) {
                transport = new RoleTransport(role,
                        new com.aresstack.askai.research.runtime.team.ReloadableMainModelChat(buildModelChat(role)));
                roleChats.put(key, transport);
            }
            return transport.chat;
        }
    }

    /**
     * The conversation transport of a live phase: the conversation role on slot {@code phase.<id>} (plan 5.1),
     * through the same {@link #roleChats()} factory every role uses, so a chat model change never reaches it.
     */
    private com.aresstack.askai.research.runtime.team.ReloadableMainModelChat phaseChat(String phaseId) {
        return roleChat(com.aresstack.askai.research.runtime.role.ResearchRoles.CONVERSATION.withSlot(
                com.aresstack.askai.research.runtime.inference.ResearchModelSlots.phase(phaseId)));
    }

    /**
     * The session's productive {@link com.aresstack.askai.research.runtime.role.RoleChatFactory}: every role
     * reaches the model only through its own transport over the host's model port, hot-reloaded and cancelled
     * with the session. The seam the orchestrator and the task executor (ADR 0001, 4.2) run their roles on.
     */
    public com.aresstack.askai.research.runtime.role.RoleChatFactory roleChats() {
        return new com.aresstack.askai.research.runtime.role.RoleChatFactory() {
            public com.aresstack.askai.research.runtime.team.MainModelChat chatFor(
                    com.aresstack.askai.research.runtime.role.ResearchRole role) {
                if (role == null) {
                    throw new IllegalArgumentException("role must not be null");
                }
                return roleChat(role);
            }
        };
    }

    private java.util.List<RoleTransport> roleTransports() {
        synchronized (roleChats) {
            return new java.util.ArrayList<RoleTransport>(roleChats.values());
        }
    }

    /** The user's per-call main-model timeout, carried by the descriptor; null = the platform default. */
    private static java.time.Duration callTimeout(
            com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument document) {
        long millis = document.descriptor.timeoutMillis;
        return millis > 0 ? java.time.Duration.ofMillis(millis) : null;
    }

    /**
     * Build the OPTIONAL structured-inference port (model-backed SERP layout repair) over the host's model
     * port. Unlike the mandatory reranker this NEVER fails the session: an absent or invalid descriptor, or a
     * host without a model port, keeps the honest {@code UnavailableStructuredInferencePort}, so a
     * low-confidence SERP simply stays unresolvable rather than fabricating results.
     */
    private com.aresstack.askai.browser.search.inference.StructuredInferencePort buildInferencePort() {
        if (!environment.hasInference() || modelPort == null) {
            System.err.println("[research-agent] no inference descriptor or no model port — SERP layout repair "
                    + "unavailable");
            return new com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort();
        }
        try {
            com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument document =
                    com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader
                            .load(environment.inferenceConfigPath);
            System.err.println("[research-agent] SERP layout repair ready on model slot "
                    + com.aresstack.askai.research.runtime.role.ResearchRoles.SERP_LAYOUT_REPAIR.getSlot());
            return com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient.forRole(modelPort,
                    com.aresstack.askai.research.runtime.role.ResearchRoles.SERP_LAYOUT_REPAIR,
                    callTimeout(document));
        } catch (java.io.IOException ex) {
            System.err.println("[research-agent] inference descriptor unusable ("
                    + environment.inferenceConfigPath + "): " + ex.getMessage()
                    + " — SERP layout repair stays unavailable");
            return new com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort();
        }
    }

    /**
     * Re-read the inference descriptor and rebuild the {@link #inferencePort}. Returns false (keeping the
     * last good port) when the descriptor is absent or invalid — never a fabricated success.
     */
    private boolean reloadInferencePort() {
        if (!environment.hasInference() || modelPort == null) {
            return false;
        }
        try {
            com.aresstack.askai.agent.model.inference.InferenceConfigurationDocument document =
                    com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader
                            .load(environment.inferenceConfigPath);
            // The port resolves the slots per call, so a new central model already applies; the reload swaps
            // the clients for the descriptor's (possibly new) timeout. The swap is a single atomic reference
            // set at this turn boundary: the agent's conversation history, proposed/confirmed scope and
            // pending turn are untouched — only the client underneath changes.
            java.time.Duration timeout = callTimeout(document);
            this.inferencePort = com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient.forRole(
                    modelPort, com.aresstack.askai.research.runtime.role.ResearchRoles.SERP_LAYOUT_REPAIR, timeout);
            for (RoleTransport transport : roleTransports()) {
                transport.chat.swap(com.aresstack.askai.research.runtime.team.PortMainModelChat.forRole(
                        modelPort, transport.role, timeout));
            }
            System.err.println("[research-agent] main model + inference reloaded: " + document.getModel());
            return true;
        } catch (java.io.IOException ex) {
            System.err.println("[research-agent] inference reload failed, keeping the last good config: "
                    + ex.getMessage());
            return false;
        }
    }

    /**
     * Register a {@link com.aresstack.askai.research.runtime.inference.ModelDescriptorWatcher} on the session
     * config directory so a host-rewritten inference descriptor SIGNALS a reload; the switch itself is
     * applied only between turns (see {@link #applyPendingModelReload}). The watcher is closed on the JVM
     * shutdown hook (this agent process is one-per-session).
     */
    private void startInferenceHotReload() {
        if (!environment.hasInference()) {
            return;
        }
        java.io.File descriptorFile = new java.io.File(environment.inferenceConfigPath);
        java.io.File directory = descriptorFile.getParentFile();
        if (directory == null) {
            return;
        }
        this.reloadController = new com.aresstack.askai.research.runtime.inference
                .ModelDescriptorReloadController(
                        new com.aresstack.askai.research.runtime.inference
                                .ModelDescriptorReloadController.Reload() {
                            public boolean reloadNow() {
                                return reloadInferencePort();
                            }
                        });
        try {
            final com.aresstack.askai.research.runtime.inference.ModelDescriptorWatcher watcher =
                    com.aresstack.askai.research.runtime.inference.ModelDescriptorWatcher.start(
                            directory.toPath(),
                            java.util.Collections.singleton(descriptorFile.getName()),
                            new Runnable() {
                                public void run() {
                                    reloadController.signalChange();
                                    System.err.println("[research-agent] inference descriptor changed — "
                                            + com.aresstack.askai.research.runtime.inference
                                                    .ModelReloadOutcome.RELOAD_PENDING_UNTIL_IDLE);
                                }
                            });
            this.descriptorWatcher = watcher;
        } catch (java.io.IOException ex) {
            System.err.println("[research-agent] could not watch the inference descriptor directory: "
                    + ex.getMessage());
            this.reloadController = null;
        }
    }

    /**
     * Apply a pending inference-descriptor reload at a turn boundary (idle). A visible outcome line is sent
     * so a mid-session model switch is traceable in the run log.
     */
    private void applyPendingModelReload(AgentOutput ctx) {
        if (reloadController == null) {
            return;
        }
        com.aresstack.askai.research.runtime.inference.ModelReloadOutcome outcome =
                reloadController.poll(true); // between turns → idle: safe to switch the client bundle
        if (outcome != null) {
            System.err.println("[research-agent] model reload: " + outcome);
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("MODEL_RELOAD: " + outcome));
        }
    }

    /**
     * Read the initial-search strategy snapshot (path named by {@code ASKAI_SEARCH_STRATEGY_CONFIG}) and
     * build the chosen strategy ONCE for this session. Absent config → {@code null} (the loop keeps its
     * unchanged browser strategy). A present-but-invalid snapshot is a hard session/new failure — never a
     * silent fallback to the legacy browser search.
     */
    private com.aresstack.askai.research.runtime.search.SearchStrategy buildSearchStrategy() {
        if (!environment.hasSearchStrategyConfig()) {
            return null;
        }
        String json;
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(
                    java.nio.file.Paths.get(environment.searchStrategyConfigPath));
            json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException ex) {
            throw new IllegalStateException("Cannot read ASKAI_SEARCH_STRATEGY_CONFIG="
                    + environment.searchStrategyConfigPath + ": " + ex.getMessage(), ex);
        }
        com.aresstack.askai.research.runtime.search.SearchStrategyConfiguration config =
                com.aresstack.askai.research.runtime.search.SearchStrategyConfigurationLoader.parse(json);
        // Only an API_PROVIDER selection needs the (runtime-scoped) provider registry; a legacy-browser
        // selection never opens the module or touches the provider files.
        com.aresstack.askai.research.runtime.search.provider.SearchProviderRegistry registry =
                config.getStrategy()
                        == com.aresstack.askai.research.runtime.search.StrategySelection.API_PROVIDER
                        ? ensureSearchProviderRegistry() : null;
        com.aresstack.askai.research.runtime.search.SearchStrategy strategy =
                com.aresstack.askai.research.runtime.search.ResearchSearchStrategyFactory.create(
                        config, registry);
        this.searchProviderLabel = strategy == null ? null : providerLabel(config.getProviderId());
        System.err.println("[research-agent] initial search: " + config.getStrategy()
                + (strategy == null ? " (legacy browser)" : " / " + config.getProviderId() + " / "
                        + config.getEngine()));
        return strategy;
    }

    /** User-facing REST provider name for the search progress ("via DataForSEO", no browser shown). */
    private String searchProviderLabel;
    /** Browser (SERP) path only: Google in the search browser for the engine table of each search, else null. */
    private com.aresstack.askai.research.runtime.search.GoogleSearchForEngineTable googleSearch;

    /**
     * The Browser (SERP) search over {@code pageEngines} (the research browser's engine search): the priority groups
     * of Search → Engines, with the configured "enough results". Every search reads the engine table from the
     * session's search profile, which the host rewrites on each save, so a changed table reaches the next search of a
     * running session. Google runs in the search browser; DuckDuckGo, Bing and the other engines run in the research
     * browser and are combined per run, because that path needs the run's browser.
     */
    private com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy browserEngines(
            com.aresstack.askai.research.runtime.search.SearchStrategy pageEngines) {
        com.aresstack.askai.browser.search.LegacySearchNavigationSettings navigation =
                loadBrowserSearchSettings().navigation;
        return new com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy(
                navigation.engineSelection, navigation.minimumUsableResults,
                googleSearch == null ? null : googleSearch.forSelection(navigation.engineSelection), pageEngines);
    }

    /** The search progress label of the Browser (SERP) search: "Google" when it asks Google only, else none. */
    private static String browserEnginesLabel(
            com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy engines) {
        return engines.isGoogleOnly() ? providerLabel(
                com.aresstack.askai.research.runtime.search.provider.SearchProviderId.GOOGLE_BROWSER) : null;
    }

    /** The SERP layout repair of a worker's search: its metered port, else the session's, else none. */
    private com.aresstack.askai.browser.search.inference.StructuredInferencePort layoutRepairPort(
            com.aresstack.askai.browser.search.inference.StructuredInferencePort metered) {
        if (metered != null) {
            return metered;
        }
        if (inferencePort != null) {
            return inferencePort;
        }
        return new com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort();
    }

    private static String providerLabel(
            com.aresstack.askai.research.runtime.search.provider.SearchProviderId providerId) {
        switch (providerId) {
            case DATA_FOR_SEO: return "DataForSEO";
            case BRAVE_SEARCH_API: return "Brave Search";
            case BRIGHT_DATA: return "Bright Data";
            case SERP_API: return "SerpApi";
            case GOOGLE_BROWSER: return "Google";
            default: return providerId.name();
        }
    }

    /**
     * Lazily open the single runtime-scoped async provider registry and register its idempotent
     * {@code close()} on the JVM shutdown hook IMMEDIATELY, so even a later initialization failure still
     * shuts the AsyncHttpClients down cleanly. Missing provider files are not fatal — the module opens
     * resiliently and a selected-but-unconfigured provider surfaces a typed error at search time.
     */
    private synchronized
            com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
            ensureSearchProviderRegistry() {
        if (searchProviderRegistry == null) {
            final com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry
                    registry = new com.aresstack.askai.research.runtime.search.provider.async
                            .AsyncSearchProviderRegistry(
                                    com.aresstack.askai.research.runtime.search.provider.async
                                            .ModuleAsyncSearchGenerationFactory
                                            .userHome(SEARCH_PROVIDER_TIMEOUT_MILLIS));
            searchProviderRegistry = registry;
        }
        return searchProviderRegistry;
    }

    /** AskAI's headless search browser (askai #31/#48): the JVM's one shared instance, started on the first search. */
    private com.aresstack.askai.browser.playwright.SearchBrowser ensureSearchBrowser() {
        return ResearchRuntimeShared.process().searchBrowser();
    }

    /** The search-browser resolver for engine redirect wrappers; null when the search browser cannot be had. */
    private com.aresstack.askai.research.runtime.acquire.OpaqueRedirectResolver redirectResolver() {
        try {
            return new com.aresstack.askai.research.runtime.search.SearchBrowserRedirectResolver(
                    ensureSearchBrowser());
        } catch (RuntimeException e) {
            System.err.println("[research-agent] no search browser for redirect resolution: " + e.getMessage());
            return null;
        }
    }

    /**
     * Whether a CAPTCHA/consent wall on a page waits for the user: the setting, unless the research browser runs
     * headless ({@code ASKAI_RESEARCH_BROWSER_HEADLESS=true}) — then there is no window to solve anything in, the
     * page is skipped and nobody is asked.
     */
    private boolean challengeWaitForUser() {
        boolean headless = "true".equalsIgnoreCase(
                String.valueOf(environment.get("ASKAI_RESEARCH_BROWSER_HEADLESS")).trim());
        return loadBrowserSearchSettings().captcha.waitForUser && !headless;
    }

    public void cancel() {
        System.err.println("[research-agent] cancel");
        cancelled.set(true);
        for (java.util.concurrent.atomic.AtomicBoolean team : teamRuns) {
            team.set(true); // every running team query stops too (plan T5)
        }
        for (Thread waiting : teamWaiters) {
            waiting.interrupt(); // a team query waiting for a tab gives up now
        }
        // Abort any in-flight model call so a pause/cancel or a session/tab close returns promptly instead of
        // waiting out the full model timeout; the aborted call surfaces as an honest non-OK turn.
        for (RoleTransport transport : roleTransports()) {
            transport.chat.cancelInFlight();
        }
        synchronized (orchestratorChatsInFlight) {
            for (com.aresstack.askai.research.runtime.team.MainModelChat chat : orchestratorChatsInFlight) {
                chat.cancelInFlight();
            }
        }
        synchronized (orchestratorInferenceInFlight) {
            for (com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient client
                    : orchestratorInferenceInFlight) {
                client.cancelInFlight();
            }
        }
        com.aresstack.askai.browser.search.inference.StructuredInferencePort inference = inferencePort;
        if (inference instanceof com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient) {
            ((com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient) inference)
                    .cancelInFlight();
        }
        // Packages waiting for a free worker user context stop now, not after the wait setting.
        com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition workers = workerAcquisition;
        if (workers != null) {
            workers.cancelWaits();
        }
    }

    /**
     * End the session: cancel the turn in flight, stop the inference-descriptor watcher and close the async
     * search-provider clients. In-process, so no JVM exit and no shutdown hook is involved any more.
     */
    public void close() {
        cancel();
        com.aresstack.askai.research.runtime.inference.ModelDescriptorWatcher watcher = descriptorWatcher;
        descriptorWatcher = null;
        if (watcher != null) {
            watcher.close();
        }
        com.aresstack.askai.research.runtime.search.provider.async.AsyncSearchProviderRegistry providers;
        synchronized (this) {
            providers = searchProviderRegistry;
            searchProviderRegistry = null;
        }
        if (providers != null) {
            providers.close();
        }
    }

    /** One research-lane call; failures surface as RuntimeExceptions so the existing best-effort guards apply. */
    private String researchCall(String tool, Map<String, Object> args) {
        try {
            return research.call(tool, args);
        } catch (ToolInvoker.ToolFailure ex) {
            throw new IllegalStateException(ex.getMessage(), ex);
        } catch (ToolInvoker.EndpointUnavailable ex) {
            throw new IllegalStateException("research endpoint unavailable: " + ex.getMessage(), ex);
        }
    }

    /**
     * One turn: a typed service command envelope ({@code #RSC1#}), an autonomous research turn or a normal
     * conversation turn. Every output line goes to {@code ctx}; the outcome is the former ACP stop reason.
     */
    public AgentTurnOutcome prompt(String input, AgentOutput ctx) {
        System.err.println("[research-agent] prompt turn started");
        cancelled.set(false);
        if (!readinessAnnounced) {
            readinessAnnounced = true;
            // Readiness is a technical fact, not conversation: technical details only, never a bubble.
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("RESEARCH_MCP_READY"));
            if (!hasBrowser()) {
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .log("BROWSER_NOT_AVAILABLE"));
            }
        }
        String text = input == null ? "" : input;
        // FIRST branch: a typed SERVICE COMMAND (e.g. a user-triggered web search) arrives on the
        // prompt channel but is NOT a chat turn — dispatch it before ANY readiness/model/TeamAgent/state logic,
        // then return. No applyPendingModelReload, no readStateView, no TeamAgent, no research-state check.
        if (com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire.isServiceCommand(text)) {
            handleServiceCommand(ctx, text);
            return AgentTurnOutcome.END_TURN;
        }
        if (cancelled.get()) {
            return AgentTurnOutcome.CANCELLED;
        }
        // Plan PR 15: only the explicit "research:" prefix starts the autonomous loop, and only while the HOST
        // state machine (mirrored via research_status — this process owns no state) is in research/running.
        // A plain typed line in research/running reaches the Sources assistant like any conversation turn;
        // the search plan queue does the acquisition. Outside research/running "research:" is refused before
        // any browser call, with a pointer to /search.
        boolean explicitResearch = text.startsWith("research:");
        String task = explicitResearch ? text.substring("research:".length()).trim() : "";
        if (explicitResearch && !task.isEmpty()) {
            if (!hostIsInResearchRunning()) {
                ctx.message(researchRefusal(sessionLanguage.code()));
                return AgentTurnOutcome.END_TURN;
            }
            if (!hasBrowser()) {
                // An honest, STRUCTURED refusal: the host renders a readable result card from it.
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .log("BROWSER_NOT_AVAILABLE: cannot run autonomous web research this turn."));
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.outcome(
                        new com.aresstack.askai.research.runtime.loop.ResearchRunOutcome(
                                com.aresstack.askai.research.runtime.loop.ResearchStopReason.MCP_UNAVAILABLE,
                                0, 0, 0, 0, 0, true,
                                com.aresstack.askai.research.runtime.loop.ResearchRunOutcome
                                        .Limitation.NONE,
                                com.aresstack.askai.research.runtime.loop.ResearchRunOutcome
                                        .RecommendedAction.RETRY)));
                return AgentTurnOutcome.END_TURN;
            }
            runResearchLoop(ctx, task);
            return cancelled.get()
                    ? AgentTurnOutcome.CANCELLED
                    : AgentTurnOutcome.END_TURN;
        }
        // Productive conversation: the model-backed TeamAgent LEADS the greeting, scoping and outline dialog.
        // Apply any pending central-model reload at this turn boundary (never mid-request), mirror the live
        // host state, then respond. The host stays the state
        // authority — this only reads research_status and speaks the model's own words.
        applyPendingModelReload(ctx);
        com.aresstack.askai.research.runtime.team.TeamAgentStateView view = readStateView(ctx);
        // No greeting turn: a fresh chat stays empty until the user writes, and the first answer replies to
        // the user's topic directly. The host starts the scoping (SCOPING/NEW → running) on the first message;
        // a host that has not yet done so gets the one-step signal here. An empty turn on a fresh state (an
        // older host's bootstrap) costs no model call.
        boolean freshState = "scoping".equalsIgnoreCase(view.getPhaseId())
                && "new".equalsIgnoreCase(view.getStateId());
        if (freshState && text.trim().isEmpty()) {
            return cancelled.get()
                    ? AgentTurnOutcome.CANCELLED
                    : AgentTurnOutcome.END_TURN;
        }
        if (freshState) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.greeted());
        }
        // The chat turn and its tool rounds hold the history lock as one unit: a review turn waits (PR 17).
        final String turnText = text;
        com.aresstack.askai.research.runtime.team.TeamAgentResult turn = teamAgent.exclusively(
                new java.util.function.Supplier<com.aresstack.askai.research.runtime.team.TeamAgentResult>() {
                    public com.aresstack.askai.research.runtime.team.TeamAgentResult get() {
                        return conceptAwareRespond(ctx, view, turnText);
                    }
                });
        emitTeamAgentResult(ctx, turn, view.getPhaseId());
        runSourcesAction(ctx, turn, view.getPhaseId());
        return cancelled.get()
                ? AgentTurnOutcome.CANCELLED
                : AgentTurnOutcome.END_TURN;
    }

    /**
     * Plan PR 15: the Sources assistant's one optional action ({@code request_research} or {@code blacklist_add})
     * runs on the host only when the phase's action union offers it right now; the host's answer is the visible
     * receipt. It starts no search: a plan entry runs when the user starts the plan.
     */
    private void runSourcesAction(AgentOutput ctx, com.aresstack.askai.research.runtime.team.TeamAgentResult turn,
                                  String phaseId) {
        if (turn == null || turn.getStatus() != com.aresstack.askai.research.runtime.team.TeamAgentResult.Status.OK
                || !(turn.getOutput() instanceof com.aresstack.askai.research.runtime.team.TeamAgentTurn)) {
            return;
        }
        com.aresstack.askai.research.runtime.team.TeamAgentTurn.Action action =
                ((com.aresstack.askai.research.runtime.team.TeamAgentTurn) turn.getOutput()).getAction();
        if (action == null) {
            return;
        }
        java.util.List<String> offered = research == null ? java.util.Collections.<String>emptyList()
                : research.toolNames();
        if (!com.aresstack.askai.research.runtime.team.PhaseActionUnion.forPhase(phaseId, offered)
                .contains(action.getType())) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.log(
                    "sources action dropped: " + action.getType() + " is not offered in phase " + phaseId));
            return;
        }
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        if (com.aresstack.askai.research.runtime.team.TeamAgentTurn.Action.REQUEST_RESEARCH
                .equals(action.getType())) {
            args.put("query", action.getQuery());
            if (!action.getNode().isEmpty()) {
                args.put("node", action.getNode());
            }
        } else {
            args.put("entry", action.getEntry());
        }
        String receipt;
        try {
            receipt = research.call(action.getType(), args);
        } catch (com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure failed) {
            receipt = failed.getMessage();
        } catch (com.aresstack.askai.research.runtime.loop.ToolInvoker.EndpointUnavailable gone) {
            receipt = "The research endpoint is not available.";
        }
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.log(
                "sources action " + action.getType() + " -> " + receipt));
        ctx.message(sourcesActionReceipt(action, receipt, sessionLanguage.code()));
    }

    /** The visible receipt of a Sources action, from the host's answer (never the model's claim). */
    static String sourcesActionReceipt(com.aresstack.askai.research.runtime.team.TeamAgentTurn.Action action,
                                       String hostAnswer, String languageCode) {
        boolean german = "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim());
        boolean added = hostAnswer != null && hostAnswer.startsWith("ADDED");
        boolean research = com.aresstack.askai.research.runtime.team.TeamAgentTurn.Action.REQUEST_RESEARCH
                .equals(action.getType());
        if (added && research) {
            return german
                    ? "Zum Suchplan hinzugefügt: \"" + action.getQuery() + "\". Die Suche läuft, sobald du den "
                            + "Suchplan startest (\"Run search plan\")."
                    : "Added to the search plan: \"" + action.getQuery() + "\". It runs when you start the plan "
                            + "(\"Run search plan\").";
        }
        if (added) {
            return german
                    ? "Auf die Blacklist gesetzt: \"" + action.getEntry() + "\". Künftige Suchen lassen es aus."
                    : "Added to the blacklist: \"" + action.getEntry() + "\". Future searches skip it.";
        }
        String reason = hostAnswer == null ? "" : hostAnswer.replaceFirst("^Tool failed:\\s*", "").trim();
        return german
                ? (research ? "Der Suchplan wurde nicht geändert: " : "Die Blacklist wurde nicht geändert: ") + reason
                : (research ? "The search plan was not changed: " : "The blacklist was not changed: ") + reason;
    }

    /**
     * Dispatch a typed {@code #RSC1#} service command. It is NOT a chat turn and touches no state/phase/model:
     * a user web search runs on the productive {@link com.aresstack.askai.research.runtime.search.SearchStrategy}
     * (phase-independent) and streams typed {@code manual_search_*} events back over the existing run wire.
     * Unknown command types are ignored (never a chat bubble, never an error).
     */
    private void handleServiceCommand(AgentOutput ctx, String envelope) {
        com.aresstack.askai.research.runtime.service.ResearchServiceCommand command =
                com.aresstack.askai.research.runtime.service.ResearchServiceCommandWire.parse(envelope);
        if (command == null) {
            return;
        }
        if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_MANUAL_SEARCH
                .equals(command.getType())) {
            handleManualSearch(ctx, command);
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_SET_LANGUAGE
                .equals(command.getType())) {
            // Pure session-context mutation: the next TeamAgent turn assembles with the new working
            // language. No model call, no history entry, no state change, no event back to the host.
            sessionLanguage.changeFromCode(command.getLanguage());
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_SET_SCOPE
                .equals(command.getType())) {
            // The host's authoritative scope for the NEXT turn — context only, exactly like set_language.
            scopeFence.update(command.getScope());
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_REVIEW_SOURCES
                .equals(command.getType())) {
            handleReviewSources(ctx, command.getRequestId(), command.getCapturedThrough());
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_GENERATE_PROBES
                .equals(command.getType())) {
            handleGenerateProbes(ctx, command.getRequestId(), command.getRequest());
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_CHOOSE_ADVICE
                .equals(command.getType())) {
            handleChooseAdvice(ctx, command.getRequestId(), command.getRequest());
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_PLAN_QUERIES
                .equals(command.getType())) {
            handlePlanQueries(ctx, command.getRequestId(), command.getRequest());
        } else if (command.getType().startsWith("orchestrator_")) {
            handleOrchestratorCommand(ctx, command);
        }
    }

    // ------------------------------------------------------------------ ADR 0001 lane F: orchestrated run

    private com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings orchestratorSettings() {
        return com.aresstack.askai.research.runtime.orchestrator.OrchestratorSettings.from(environment.values());
    }

    /**
     * The user's orchestrator commands (typed service commands, never chat text): start, approve, answer,
     * stop. Approve and answer run the cycle on this turn, so the turn's busy state and cancel cover it;
     * a cancel interrupts the run, which then resumes on the next approval.
     */
    private void handleOrchestratorCommand(AgentOutput ctx,
                                           com.aresstack.askai.research.runtime.service.ResearchServiceCommand command) {
        String type = command.getType();
        if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_ORCHESTRATOR_START.equals(type)) {
            orchestratorStart(ctx, command.getQuery());
            return;
        }
        com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession session = orchestratorSession();
        com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession.Output out = orchestratorOutput(ctx);
        orchestratorTurn = ctx;
        try {
            dispatchOrchestratorCommand(type, command, session, out);
        } finally {
            orchestratorTurn = null;
        }
    }

    private void dispatchOrchestratorCommand(String type,
            com.aresstack.askai.research.runtime.service.ResearchServiceCommand command,
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession session,
            com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession.Output out) {
        if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_ORCHESTRATOR_APPROVE
                .equals(type)) {
            if (!hasBrowser()) {
                out.log("BROWSER_NOT_AVAILABLE: cannot run the orchestrated research.");
                return;
            }
            session.approve(cancelled, out);
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_ORCHESTRATOR_ANSWER
                .equals(type)) {
            session.answer(command.getQuery(), cancelled, out);
        } else if (com.aresstack.askai.research.runtime.service.ResearchServiceCommand.TYPE_ORCHESTRATOR_STOP
                .equals(type)) {
            // A stop arrives on its own turn while the approving turn still runs the cycle: cancel the session's
            // work first (flag, in-flight model calls), so running packages end now instead of browsing on.
            cancel();
            session.stop(out);
        }
    }

    private void orchestratorStart(AgentOutput ctx, String question) {
        if (question == null || question.trim().isEmpty()) {
            return;
        }
        applyPendingModelReload(ctx);
        orchestratorSession().start(question.trim(), orchestratorOutput(ctx));
    }

    private com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession.Output orchestratorOutput(
            final AgentOutput ctx) {
        return new com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession.Output() {
            public void visible(String message) {
                ctx.message(message);
            }

            public void log(String line) {
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.log(line));
            }
        };
    }

    /** Built on first use from the session's lanes, model port, settings and the project's blackboard. */
    private synchronized com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession orchestratorSession() {
        if (orchestrator == null) {
            com.aresstack.askai.research.runtime.orchestrator.TaskBoard board = taskBoard != null ? taskBoard
                    : new com.aresstack.askai.research.runtime.orchestrator.InMemoryTaskBoard();
            final com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget =
                    com.aresstack.askai.research.runtime.loop.EnvironmentRunBudget.from(environment.values());
            // Each search run of a web research worker browses in its own user context of this chat (own
            // cookies, storage, sign-in); without worker browsers the runs share the chat browser.
            final com.aresstack.askai.research.runtime.browser.BrowserUserContextPool pool = browserUserContexts;
            com.aresstack.askai.research.runtime.orchestrator.WebAcquisition acquisition =
                    com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition.over(pool, browser,
                            (browserLane, query, permittedResearch, accepted, maxDurationMillis, tokens,
                             exhausted) -> acquireWith(browserLane, query, permittedResearch, accepted,
                                    maxDurationMillis, tokens, exhausted, budget));
            if (acquisition instanceof com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition) {
                workerAcquisition =
                        (com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition) acquisition;
            }
            orchestrator = new com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession(board,
                    orchestratorSettings(), orchestratorChats(), modelPort,
                    com.aresstack.askai.research.runtime.browser.UserContextWebAcquisition.browserSlots(pool),
                    com.aresstack.askai.research.runtime.orchestrator.OrchestratorSession.workers(
                            acquisition, research, orchestratorSettings(),
                            com.aresstack.askai.research.runtime.orchestrator.WorkerRoleSettings.from(
                                    environment.values()),
                            WORKER_ANSWER_TOKENS),
                    budget.getMinimumAcceptedSources(), budget.getMinimumDistinctHosts(),
                    WORKER_ANSWER_TOKENS,
                    new java.util.function.Supplier<com.aresstack.askai.research.runtime.orchestrator.ResearchOutline>() {
                        public com.aresstack.askai.research.runtime.orchestrator.ResearchOutline get() {
                            return readOutline();
                        }
                    });
        }
        return orchestrator;
    }

    /** One search run of the orchestrated run's web research worker over {@code browserLane}. */
    private com.aresstack.askai.research.runtime.orchestrator.WebAcquisition.Result acquireWith(
            ToolInvoker browserLane, String query, ToolInvoker permittedResearch,
            java.util.function.Consumer<com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                    .AcceptedSource> accepted,
            long maxDurationMillis, java.util.concurrent.atomic.AtomicLong tokens,
            java.util.function.BooleanSupplier exhausted,
            com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget) {
        com.aresstack.askai.research.runtime.loop.ResearchLoop loop =
                buildResearchLoop(orchestratorTurn, browserLane, permittedResearch,
                        boundedBy(budget, maxDurationMillis));
        com.aresstack.askai.browser.search.inference.StructuredInferencePort metered =
                meteredInference(tokens, exhausted);
        if (searchStrategy == null) {
            // Workers search with the concept phase's web search tool, never in their own tabs (those refuse
            // search engines): the configured engines, Google in the search browser and the page engines in
            // the chat browser, one search at a time across all workers and chats.
            com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy engines =
                    browserEngines(com.aresstack.askai.research.runtime.search.LegacyBrowserSearchStrategyFactory
                            .createDefault(browser != null ? browser : browserLane, loadBrowserSearchSettings(),
                                    new java.util.function.LongSupplier() {
                                        public long getAsLong() {
                                            return System.currentTimeMillis();
                                        }
                                    }, layoutRepairPort(metered)));
            loop.setSearchStrategy(engines, browserEnginesLabel(engines));
        }
        loop.setAcceptedSourceObserver(accepted);
        com.aresstack.askai.research.runtime.loop.ResearchStopReason reason = loop.run(query);
        synchronized (visitedAcrossRuns) {
            visitedAcrossRuns.addAll(loop.getProgress().getVisitedCanonicalUrls());
        }
        return new com.aresstack.askai.research.runtime.orchestrator.WebAcquisition.Result(reason,
                "pages " + loop.getProgress().getPagesVisited()
                        + ", hosts " + loop.getProgress().getDistinctHosts().size());
    }

    /**
     * The session's layout-repair transport, metered onto a work package's token account (null when the session
     * has none, or the search strategy is an API provider and the loop needs no inference). Registered so
     * {@link #cancel()} aborts it with the turn.
     */
    private com.aresstack.askai.browser.search.inference.StructuredInferencePort meteredInference(
            java.util.concurrent.atomic.AtomicLong tokens, java.util.function.BooleanSupplier exhausted) {
        if (tokens == null || modelPort == null || inferencePort == null || searchStrategy != null) {
            return null;
        }
        try {
            com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient client =
                    com.aresstack.askai.research.runtime.inference.PortStructuredInferenceClient.forRole(
                            new com.aresstack.askai.research.runtime.orchestrator.MeteredModelPort(modelPort, tokens,
                                    exhausted),
                            com.aresstack.askai.research.runtime.role.ResearchRoles.SERP_LAYOUT_REPAIR,
                            callTimeout(com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader
                                    .load(environment.inferenceConfigPath)));
            orchestratorInferenceInFlight.add(client);
            return client;
        } catch (java.io.IOException unusable) {
            return null;
        }
    }

    /** The search-run budget with its time limit lowered to a work package's remaining time. */
    private static com.aresstack.askai.research.runtime.loop.ResearchRunBudget boundedBy(
            com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget, long maxDurationMillis) {
        if (maxDurationMillis <= 0 || maxDurationMillis >= budget.getMaxDurationMillis()) {
            return budget;
        }
        return new com.aresstack.askai.research.runtime.loop.ResearchRunBudget(budget.getMaxToolCalls(),
                budget.getMaxPagesVisited(), budget.getMaxAcceptedSources(), budget.getMaxConsecutiveErrors(),
                maxDurationMillis, budget.getMinimumAcceptedSources(), budget.getMinimumDistinctHosts());
    }

    /**
     * The orchestrated run's role chats: the host's model port with the descriptor's call timeout. Every chat
     * handed out is registered, so {@link #cancel()} aborts the orchestrator's and the workers' calls in flight
     * like the session's own role transports.
     */
    private com.aresstack.askai.research.runtime.orchestrator.RoleChats orchestratorChats() {
        com.aresstack.askai.research.runtime.orchestrator.RoleChats port;
        if (!environment.hasInference() || modelPort == null) {
            port = com.aresstack.askai.research.runtime.orchestrator.RoleChats.overPort(null, null);
        } else {
            try {
                port = com.aresstack.askai.research.runtime.orchestrator.RoleChats.overPort(modelPort,
                        callTimeout(com.aresstack.askai.research.runtime.inference.InferenceConfigurationLoader
                                .load(environment.inferenceConfigPath)));
            } catch (java.io.IOException unusable) {
                port = com.aresstack.askai.research.runtime.orchestrator.RoleChats.overPort(null, null);
            }
        }
        final com.aresstack.askai.research.runtime.orchestrator.RoleChats source = port;
        return new com.aresstack.askai.research.runtime.orchestrator.RoleChats() {
            public com.aresstack.askai.research.runtime.team.MainModelChat chatFor(
                    com.aresstack.askai.research.runtime.role.ResearchRole role,
                    java.util.concurrent.atomic.AtomicLong tokens) {
                com.aresstack.askai.research.runtime.team.MainModelChat chat = source.chatFor(role, tokens);
                orchestratorChatsInFlight.add(chat);
                return chat;
            }
        };
    }

    /** The approved outline: the first level of the host's concept, or the question alone. */
    private com.aresstack.askai.research.runtime.orchestrator.ResearchOutline readOutline() {
        if (!conceptToolsAvailable) {
            return com.aresstack.askai.research.runtime.orchestrator.ResearchOutline.ofQuestion("");
        }
        try {
            java.util.Map<String, Object> args = new java.util.HashMap<String, Object>();
            args.put("path", "");
            return com.aresstack.askai.research.runtime.orchestrator.ResearchOutline.fromConceptRead("",
                    research.call("concept_read", args));
        } catch (Exception unreadable) {
            System.err.println("[orchestrator] concept unreadable, planning against the question alone: "
                    + unreadable.getMessage());
            return com.aresstack.askai.research.runtime.orchestrator.ResearchOutline.ofQuestion("");
        }
    }

    /**
     * Z4b: choose at most ONE of the host-prepared advice candidates — EXACTLY ONE model call via
     * {@link com.aresstack.askai.research.runtime.scope.MainModelScopeAdviceChooser}, the typed
     * decision back as ONE {@code #RSX1# advice} line. The chooser NEVER sees the raw sweep, never
     * invents candidates, never turns a drift guard into a positive question, and this handler
     * touches no TeamAgent, no history, no state — the user's ANSWER later changes the draft
     * through the normal scoping turn.
     */
    private void handleChooseAdvice(AgentOutput ctx, String requestId, String requestJson) {
        System.err.println("[advice] runtime received requestId=" + requestId
                + " payloadLen=" + requestJson.length());
        com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult result;
        try {
            com.aresstack.askai.research.runtime.scope.ScopeAdviceWire.ParsedRequest parsed =
                    com.aresstack.askai.research.runtime.scope.ScopeAdviceWire
                            .parseRequest(requestJson);
            result = new com.aresstack.askai.research.runtime.scope.MainModelScopeAdviceChooser(
                    roleChat(com.aresstack.askai.research.runtime.role.ResearchRoles.SCOPE_ADVICE_CHOOSER),
                    parsed.settings).choose(parsed.request);
        } catch (IllegalArgumentException malformedRequest) {
            result = com.aresstack.askai.research.domain.scope.ScopeAdviceChooser.ChoiceResult
                    .failure(com.aresstack.askai.research.domain.scope.ScopeAdviceChooser
                                    .ChoiceResult.Status.INVALID_RESPONSE,
                            "malformed advice request: " + malformedRequest.getMessage());
        }
        System.err.println("[advice] runtime answering requestId=" + requestId
                + " status=" + result.getStatus()
                + (result.isOk() ? "" : " message=" + result.getMessage()));
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.adviceDecision(
                requestId,
                com.aresstack.askai.research.runtime.scope.ScopeAdviceWire.renderResult(result)));
    }

    /**
     * Plan PR 13: plan the search queries of the frozen concept on {@code phase.research.planner} — one planner
     * call per node, one repair, then the base query (the planner never throws). A malformed request is answered
     * with an empty plan, so the host never waits into its timeout. Material only: no scope or state change.
     */
    private void handlePlanQueries(AgentOutput ctx, String requestId, String requestJson) {
        com.aresstack.askai.research.domain.plan.ConceptQueryPlan.Result result;
        try {
            result = com.aresstack.askai.research.runtime.orchestrator.QueryPlanner.planConcept(
                    roleChat(com.aresstack.askai.research.runtime.orchestrator.QueryPlanner.CONCEPT_ROLE),
                    com.aresstack.askai.research.runtime.plan.QueryPlanWire.parseRequest(requestJson),
                    cancelled::get);
        } catch (IllegalArgumentException malformedRequest) {
            System.err.println("[query-plan] malformed request requestId=" + requestId + ": "
                    + malformedRequest.getMessage());
            result = new com.aresstack.askai.research.domain.plan.ConceptQueryPlan.Result(null);
        }
        System.err.println("[query-plan] runtime answering requestId=" + requestId
                + " nodes=" + result.getNodes().size());
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.queryPlan(requestId,
                com.aresstack.askai.research.runtime.plan.QueryPlanWire.renderResult(result)));
    }

    /**
     * Z3b-3: generate scope probes on the central main model — EXACTLY ONE model call via the
     * productive {@link com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator},
     * then the typed result (success OR failure) goes back as ONE {@code #RSX1# probes} line. The
     * host owns the canonical scope, the vectors and every geometric verdict; this handler only
     * produces material and NEVER mutates scope or state. A malformed request is answered typed
     * (INVALID_RESPONSE with the parse reason) — the host must never wait into its timeout for a
     * request it broke itself.
     */
    private void handleGenerateProbes(AgentOutput ctx, String requestId, String requestJson) {
        System.err.println("[probe-gen] runtime received requestId=" + requestId
                + " payloadLen=" + requestJson.length());
        com.aresstack.askai.research.domain.scope.ScopeProbeGenerator.ProbeGenerationResult result;
        try {
            com.aresstack.askai.research.runtime.scope.ProbeGenerationWire.ParsedRequest parsed =
                    com.aresstack.askai.research.runtime.scope.ProbeGenerationWire
                            .parseRequest(requestJson);
            result = new com.aresstack.askai.research.runtime.scope.MainModelScopeProbeGenerator(
                    roleChat(com.aresstack.askai.research.runtime.role.ResearchRoles.SCOPE_PROBE_GENERATOR),
                    parsed.settings).generate(parsed.request);
        } catch (IllegalArgumentException malformedRequest) {
            result = com.aresstack.askai.research.domain.scope.ScopeProbeGenerator
                    .ProbeGenerationResult.failure(
                            com.aresstack.askai.research.domain.scope.ScopeProbeGenerator
                                    .ProbeGenerationResult.Status.INVALID_RESPONSE,
                            "malformed generation request: " + malformedRequest.getMessage());
        }
        System.err.println("[probe-gen] runtime answering requestId=" + requestId
                + " status=" + result.getStatus()
                + (result.isOk() ? "" : " message=" + result.getMessage()));
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.probeGeneration(
                requestId,
                com.aresstack.askai.research.runtime.scope.ProbeGenerationWire
                        .renderResult(result)));
    }

    /**
     * The EXPLICIT post-search review (issue #29): runs ONLY on the user's "Neue Quellen auswerten" action,
     * never implicitly after a search. Bracketed by the same started/finished lifecycle as before, so the
     * host shows the cancellable thinking bubble and ALWAYS clears it — even on model failure/cancel.
     */
    private void handleReviewSources(final AgentOutput ctx, String requestId,
                                     long capturedThrough) {
        System.err.println("[manual-search] explicit review started requestId=" + requestId);
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .manualSearchReviewStarted(requestId));
        // A review that throws or is stopped did not review anything — only a turn that says SUCCEEDED lets
        // the host record these sources as seen. The finally guarantees the host is always released.
        com.aresstack.askai.research.domain.search.PostSearchReviewOutcome outcome =
                com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED;
        try {
            outcome = reviewNewSourcesAndRefreshSuggestions(ctx, capturedThrough);
        } finally {
            if (cancelled.get()) {
                outcome = com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.CANCELLED;
            }
            System.err.println("[manual-search] explicit review finished requestId=" + requestId
                    + " outcome=" + outcome);
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .manualSearchReviewFinished(requestId, outcome));
        }
    }

    /**
     * Run a user-triggered web search. It resolves the SAME productive {@code SearchStrategy} the loop uses
     * (an API-provider session strategy, else the default legacy-browser SERP strategy over a fresh browser
     * invoker) — the strategy is no longer trapped inside the loop. Phase-independent: this is a user service,
     * not the agent's MCP tool. NOTE: this slice performs the SERP/provider DISCOVERY only; the browse →
     * capture → source-acceptance pipeline is still trapped in the loop and is extracted in the next slice.
     */
    private void handleManualSearch(final AgentOutput ctx,
                                    com.aresstack.askai.research.runtime.service.ResearchServiceCommand command) {
        final String requestId = command.getRequestId();
        final String query = command.getQuery();
        System.err.println("[manual-search] runtime received requestId=" + requestId
                + " queryLen=" + query.length() + " hasBrowser=" + hasBrowser()
                + " hasService=" + hasService());
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .manualSearchStarted(requestId, query));
        if (query.trim().isEmpty()) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .manualSearchFailed(requestId, "EMPTY_QUERY"));
            return;
        }
        // The request's language snapshot is AUTHORITATIVE: it re-synchronises the session language (a lost
        // set_language heals here) and then fixes THIS search's language for its whole duration. A legacy
        // envelope without a language field changes nothing and keeps the provider default. The full pipeline
        // below applies it to the SERP request via acquisition.setSearchLanguage(...).
        final String searchLanguage;
        if (command.getLanguage().isEmpty()) {
            searchLanguage = null;
        } else {
            sessionLanguage.changeFromCode(command.getLanguage());
            searchLanguage = sessionLanguage.code();
        }
        // The FULL pipeline browses/captures/accepts, so it needs BOTH the browser and the internal service
        // endpoint (manual_source_accept). Missing either → an honest failure, never a silent no-op.
        if (!hasBrowser() || !hasService()) {
            System.err.println("[manual-search] failed stage=wiring reason=SEARCH_UNAVAILABLE requestId="
                    + requestId + " hasBrowser=" + hasBrowser()
                    + " hasService=" + hasService());
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .manualSearchFailed(requestId, "SEARCH_UNAVAILABLE"));
            return;
        }
        // Plan T5: a team query reads its pages in its own worker browser and stops with its own flag, so a later
        // service command of this session (which clears the session flag) never revives a stopped team query.
        java.util.List<com.aresstack.askai.research.runtime.browser.TeamTabs.Tab> teamLeases =
                new java.util.ArrayList<com.aresstack.askai.research.runtime.browser.TeamTabs.Tab>();
        ToolInvoker teamBrowser = null;
        java.util.concurrent.atomic.AtomicBoolean runCancelled = cancelled;
        java.util.List<com.aresstack.askai.research.runtime.acquire.PageWorker> pageWorkers =
                new java.util.ArrayList<com.aresstack.askai.research.runtime.acquire.PageWorker>();
        // the terminal line goes out only after the worker's reports and its tab are handed back, so the plan's
        // next query finds a free tab and every report of this query reaches its still-running plan entry
        String terminal = null;
        try {
            // Resolve the SAME strategy the loop uses: the session API-provider strategy, else the legacy
            // browser default over this browser invoker.
            com.aresstack.askai.research.runtime.search.SearchStrategy strategy;
            String apiLabel;
            if (searchStrategy != null) {
                strategy = searchStrategy;
                apiLabel = searchProviderLabel;
            } else {
                com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy engines = browserEngines(
                        com.aresstack.askai.research.runtime.search.SessionSearchStrategyResolver.resolve(
                        null, true, browser, loadBrowserSearchSettings(), inferencePort,
                        new java.util.function.LongSupplier() {
                            public long getAsLong() {
                                return System.currentTimeMillis();
                            }
                        }));
                strategy = engines.hasSteps() ? engines : null;
                apiLabel = engines.isGoogleOnly() ? providerLabel(
                        com.aresstack.askai.research.runtime.search.provider.SearchProviderId.GOOGLE_BROWSER) : null;
            }
            System.err.println("[manual-search] execute started requestId=" + requestId + " strategy="
                    + (strategy == null ? "unavailable" : strategy.getClass().getSimpleName()));
            // The lower edge of "the new sources": a later review reads what THIS search added, not the
            // whole cumulative corpus again.
            lastManualSearchStartedAt = System.currentTimeMillis();
            lastManualSearchRequestId = requestId == null ? "" : requestId;
            if (strategy == null) {
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .manualSearchFailed(requestId, "SEARCH_UNAVAILABLE"));
                return;
            }
            if (command.isTeam()) {
                // Plan T4: the search goes through the one web search tool (one at a time for every chat and
                // worker); the hits of THIS search are read side by side by the page workers, one tab each in the
                // team's page browser.
                try {
                    teamLeases.addAll(acquireTeamTabs(requestId, Math.max(1, command.getTeamWorkers())));
                } catch (InterruptedException stopped) {
                    ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                            .manualSearchFailed(requestId, "CANCELLED"));
                    return;
                } catch (com.aresstack.askai.research.runtime.browser.BrowserCapacityException busy) {
                    // every tab is held by the running searches and none came free within the wait setting: the
                    // query goes back to the plan and runs when a search ends (not a failure of the engine)
                    System.err.println("[team] no tab for requestId=" + requestId + ": " + busy.getMessage());
                    ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                            .manualSearchFailed(requestId, TEAM_TABS_BUSY));
                    return;
                }
                if (teamLeases.isEmpty()) {
                    ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                            .manualSearchFailed(requestId, NO_WORKER_BROWSER));
                    return;
                }
                runCancelled = new java.util.concurrent.atomic.AtomicBoolean(cancelled.get());
                teamRuns.add(runCancelled);
                teamBrowser = teamLeases.get(0).tools();
                System.err.println("[team] search requestId=" + requestId + ": " + teamLeases.size() + " tabs of user "
                        + "context " + teamLeases.get(0).userContextId() + " read its hits (node \""
                        + command.getTeamNode() + "\")");
            }
            com.aresstack.askai.research.runtime.loop.ResearchRunProgress progress =
                    new com.aresstack.askai.research.runtime.loop.ResearchRunProgress();
            // The reranker for THIS search, resolved with the same immutable language snapshot as the SERP
            // request — the next search after a language switch gets the new language's reranker, a running
            // search keeps this instance (constructor injection below).
            // Plan PR 14: a planned query names its ranking and its visit limit; a typed search keeps the
            // user's "Use AI reranker" setting (Q25).
            final com.aresstack.askai.research.runtime.rerank.PlannedQueryReranker plannedRanking =
                    plannedRanking(command, searchLanguage == null ? sessionLanguage.code() : searchLanguage);
            com.aresstack.askai.research.runtime.rerank.CandidateReranker searchReranker = plannedRanking != null
                    ? plannedRanking
                    : rerankerFor(searchLanguage == null ? sessionLanguage.code() : searchLanguage);
            com.aresstack.askai.research.runtime.loop.ResearchRunBudget searchBudget =
                    com.aresstack.askai.research.runtime.loop.EnvironmentRunBudget.from(environment.values());
            final java.util.concurrent.atomic.AtomicBoolean stopFlag = runCancelled;
            com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService acquisition =
                    new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService(
                            teamBrowser != null ? teamBrowser : browser,
                            searchBudget,
                            progress,
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopClock() {
                                public long currentTimeMillis() {
                                    return System.currentTimeMillis();
                                }

                                public void sleepMillis(long millis) {
                                    try {
                                        Thread.sleep(millis);
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            },
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopListener() {
                                public void status(String message) {
                                    System.err.println("[manual-search] " + message);
                                }

                                public void progress(
                                        com.aresstack.askai.research.runtime.loop.ResearchRunProgress p,
                                        com.aresstack.askai.research.runtime.loop.ResearchRunActivity activity) {
                                    // The FUNNEL, not only its end: what the engines delivered, what was
                                    // assessed, what passed relevance, what was visited, what became an
                                    // accepted SOURCE ("Quellen x/Ziel" — the deterministic end). The
                                    // trailing [[bar:visited/relevant]] marker is machine-readable: the
                                    // host strips it and renders a progress bar in the activity bubble.
                                    ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                            .manualSearchProgress(requestId, "Quellen: "
                                                    + p.getAcceptedSources() + "/"
                                                    + searchBudget.getMaxAcceptedSources()
                                                    + " · Seiten: " + p.getPagesVisited()
                                                    + (p.getLinksSelected() > 0
                                                            ? "/" + p.getLinksSelected() : "")
                                                    + " besucht · Links: "
                                                    + p.getLinksDiscovered() + " gefunden, "
                                                    + p.getLinksAssessed() + " analysiert, "
                                                    + p.getLinksSelected() + " relevant"
                                                    // The SEARCH itself, not only its yield: which
                                                    // engine delivered how many SERP result pages.
                                                    + (p.getSerpSummary().isEmpty() ? ""
                                                            : " · Suche: " + p.getSerpSummary())
                                                    // The bar tracks what actually ENDS the search:
                                                    // accepted sources against the configured target.
                                                    // Pages-vs-links looked "weird": the run rightly
                                                    // stops at the target, long before every relevant
                                                    // link is visited.
                                                    + " [[bar:" + p.getAcceptedSources() + "/"
                                                    + searchBudget.getMaxAcceptedSources() + "]]"
                                                    // The page the remote browser is on RIGHT NOW —
                                                    // the host renders it as the clickable
                                                    // "Durchsuche:" line (opens the DEFAULT browser).
                                                    + (activity != null && activity.getUrl() != null
                                                            && !activity.getUrl().isEmpty()
                                                            ? " [[url:" + activity.getUrl() + "]]"
                                                            : "")));
                                }

                                public void phaseReady(
                                        com.aresstack.askai.research.runtime.loop.ResearchStopReason reason) {
                                    // A user search has no phase-ready signal.
                                }

                                public void attention(String reason, String domainFamily, String url,
                                                      boolean resolved) {
                                    // Emit the TYPED attention event so a manual search gets the SAME visible
                                    // notice + one attention sound the autonomous path does (mapped to
                                    // USER_ATTENTION on the host). Carries CAPTCHA and COOKIE alike; for a
                                    // cookie the url field holds the "click: <control>" hint.
                                    ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                            .attention(reason, domainFamily, url, resolved));
                                }
                            },
                            stopFlag,
                            strategy,
                            apiLabel,
                            searchReranker,
                            new com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver(),
                            new com.aresstack.askai.research.runtime.acquire.ManualSourceAcceptancePort(
                                    service, query, requestId),
                            System.currentTimeMillis(),
                            loadBrowserSearchSettings().captcha.challengeProbeIntervalMillis,
                            new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                    .AcceptedSourceListener() {
                                public com.aresstack.askai.research.runtime.loop.ResearchStopReason onAccepted(
                                        com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                                .AcceptedSource source,
                                        com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                                .ToolBudget toolBudget) {
                                    // A user search records NO agent findings — acceptance already happened
                                    // in the service via the ManualSourceAcceptancePort.
                                    return null;
                                }
                            },
                            // a page worker never waits for a person: nobody watches its worker browser
                            teamBrowser == null && challengeWaitForUser(),
                            loadBrowserSearchSettings().readiness.maximumPageReadinessRetries,
                            loadBrowserSearchSettings().readiness.minimumReadableCharacters);
            if (teamBrowser != null) {
                acquisition.disableHud(); // the overlay and its controls belong to the chat browser
                // Plan T4: one page worker per tab, one model call per opened page, several at the same time
                java.util.List<com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService.Lane> lanes =
                        new java.util.ArrayList<
                                com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService.Lane>();
                for (int lane = 0; lane < teamLeases.size(); lane++) {
                    com.aresstack.askai.research.runtime.acquire.PageWorker worker =
                            newPageWorker(requestId, requestId + "#" + (lane + 1), command);
                    pageWorkers.add(worker);
                    lanes.add(new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService.Lane(
                            teamLeases.get(lane).tools(), relayingJudge(ctx, worker, requestId)));
                }
                acquisition.setPageJudge(relayingJudge(ctx, pageWorkers.get(0), requestId));
                acquisition.setLanes(lanes);
                final String teamRequestId = requestId;
                acquisition.setOpenPagesListener(
                        new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService
                                .OpenPagesListener() {
                            public void openPages(int open) {
                                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                        .manualSearchOpenPages(teamRequestId, open));
                            }
                        });
                acquisition.setSuggestionSink(
                        new com.aresstack.askai.research.runtime.acquire.WebSearchApplicationService.SuggestionSink() {
                            public void suggested(String suggestion, String pageUrl, String rejectedReason) {
                                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                        .manualSearchTeamQuery(teamRequestId, suggestion, pageUrl, rejectedReason));
                            }
                        });
            }
            // Engine redirect wrappers (Google /goto) are resolved in the search browser — the one client that
            // talks to the engine; the research browser never opens an engine address.
            acquisition.setRedirectResolver(redirectResolver());
            // The user search completes DETERMINISTICALLY: the configured accepted-source target
            // (the budget's maxAcceptedSources) and nothing else. No minimum-host heuristics, no
            // relabeled budget stops — every other ending keeps its honest reason.
            // Plan PR 14: a planned query stops at its per-query source cap when that is lower.
            acquisition.setCompletionPolicy(
                    new com.aresstack.askai.research.runtime.acquire.FixedAcceptedSourceCountPolicy(
                            command.getSourceCap() > 0
                                    ? Math.min(command.getSourceCap(), searchBudget.getMaxAcceptedSources())
                                    : searchBudget.getMaxAcceptedSources()));
            // SC1: the manual search runs behind the SAME scope-control gate as the autonomous
            // loop — the internal service endpoint, never an agent tool.
            acquisition.setScopeControl(
                    new com.aresstack.askai.research.runtime.acquire.ServiceSearchScopeControlPort(
                            service));
            // "LLM entscheidet": let the main model classify an ambiguous page (thin text, no DOM signal) so
            // a "verify you are human" wall the SERP selectors missed is treated as a CAPTCHA, not skipped.
            acquisition.setReadinessJudge(
                    new com.aresstack.askai.research.runtime.acquire.ModelPageReadinessJudge(
                            new com.aresstack.askai.research.runtime.acquire.PageReadinessModel() {
                                public String complete(String system, String user) {
                                    com.aresstack.askai.research.runtime.team.MainModelChatResult r =
                                            roleChat(com.aresstack.askai.research.runtime.role
                                                    .ResearchRoles.PAGE_READINESS_JUDGE).complete(
                                                    java.util.Arrays.asList(
                                                    com.aresstack.askai.research.runtime.team.ChatMessage
                                                            .system(system),
                                                    com.aresstack.askai.research.runtime.team.ChatMessage
                                                            .user(user)), 0.0, 24);
                                    return r.isOk() ? r.getText() : "";
                                }
                            },
                            loadBrowserSearchSettings().readiness.minimumReadableCharacters));
            // The authoritative language snapshot flows into the SERP request (dev's language feature),
            // preserved through arch's full browse→capture→accept pipeline.
            acquisition.setSearchLanguage(searchLanguage);
            // The user's text IS the search query — never a term-set reconstruction of it.
            com.aresstack.askai.research.runtime.loop.ResearchStopReason reason =
                    acquisition.execute(query);
            if (runCancelled.get()) {
                terminal = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .manualSearchFailed(requestId, "CANCELLED");
            } else if (com.aresstack.askai.research.runtime.service.ManualSearchOutcome
                    .isTechnicalFailure(reason)) {
                // A TECHNICAL failure (SERP/browser/reranker broke, or the browser MCP endpoint was
                // unreachable) is NOT an honest empty search: report it as a FAILED manual search so the host
                // always runs its TERMINAL path (composer released, problem shown, browser stopped) — never a
                // "completed" 0-hit search that leaves the turn waiting for a post-search review.
                System.err.println("[manual-search] execute completed requestId=" + requestId + " sources="
                        + progress.getAcceptedSources() + " reason=" + reason + " -> TECHNICAL FAILURE (terminal)");
                terminal = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .manualSearchFailed(requestId, reason.name());
            } else {
                System.err.println("[manual-search] execute completed requestId=" + requestId + " sources="
                        + progress.getAcceptedSources() + " reason=" + reason);
                terminal = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .manualSearchCompleted(requestId, progress.getAcceptedSources(), reason.name(),
                                plannedRanking != null && plannedRanking.fellBackToKeywords());
                // Issue #29: the derived AI review no longer runs implicitly here. The host offers an
                // explicit "Neue Quellen auswerten" action that arrives as a review_sources service
                // command (see handleReviewSources) — the search turn ends with 'completed'.
            }
        } catch (Exception failure) {
            // The concrete cause (type + message + cause chain) is the only way anyone can act on a manual
            // search crash — log it fully, never just the class name.
            System.err.println("[manual-search] failed stage=execute requestId=" + requestId
                    + " cause=" + failure.getClass().getName()
                    + (failure.getMessage() == null ? "" : ": " + failure.getMessage()));
            failure.printStackTrace();
            terminal = com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .manualSearchFailed(requestId, runCancelled.get() ? "CANCELLED" : "SEARCH_FAILED");
        } finally {
            for (com.aresstack.askai.research.runtime.acquire.PageWorker worker : pageWorkers) {
                worker.finish(); // the workers' summaries reach the plan before the search's end does
                relayTeamNotes(ctx, worker.taskId(), requestId);
            }
            if (!teamLeases.isEmpty()) {
                teamRuns.remove(runCancelled);
                for (com.aresstack.askai.research.runtime.browser.TeamTabs.Tab tab : teamLeases) {
                    tab.close(); // the tabs go back, also on cancel and failure
                }
            }
            if (terminal != null) {
                ctx.message(terminal);
            }
        }
    }

    /**
     * Plan T4: the page worker of one team query, registered in this chat's team mailbox under the query's
     * requestId. Its model calls go through the page worker role's own transport (slot {@code phase.research.worker},
     * falling back to the Sources model), which the model port runs beside the other workers' calls.
     */
    private com.aresstack.askai.research.runtime.acquire.PageWorker newPageWorker(String requestId, String taskId,
            com.aresstack.askai.research.runtime.service.ResearchServiceCommand command) {
        com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox mailbox = teamMailbox();
        mailbox.registerWorker(taskId);
        final com.aresstack.askai.research.runtime.team.ReloadableMainModelChat chat =
                roleChat(com.aresstack.askai.research.runtime.acquire.PageWorker.ROLE);
        final int answerTokens = command.getTeamAnswerTokens(); // 0 = the model's own limit
        return new com.aresstack.askai.research.runtime.acquire.PageWorker(
                new com.aresstack.askai.research.runtime.acquire.PageWorker.Model() {
                    public String complete(
                            java.util.List<com.aresstack.askai.research.runtime.acquire.PageWorker.Turn> turns) {
                        java.util.List<com.aresstack.askai.research.runtime.team.ChatMessage> messages =
                                new java.util.ArrayList<com.aresstack.askai.research.runtime.team.ChatMessage>();
                        for (com.aresstack.askai.research.runtime.acquire.PageWorker.Turn turn : turns) {
                            messages.add("system".equals(turn.getRole())
                                    ? com.aresstack.askai.research.runtime.team.ChatMessage.system(turn.getText())
                                    : "assistant".equals(turn.getRole())
                                    ? com.aresstack.askai.research.runtime.team.ChatMessage.assistant(turn.getText())
                                    : com.aresstack.askai.research.runtime.team.ChatMessage.user(turn.getText()));
                        }
                        com.aresstack.askai.research.runtime.team.MainModelChatResult result =
                                chat.complete(messages, 0.0, answerTokens);
                        return result.isOk() ? result.getText() : null;
                    }
                },
                mailbox, taskId, command.getTeamNode(), command.getTeamBook(), command.getTeamFollow(),
                command.getTeamPageCharacters() > 0 ? command.getTeamPageCharacters() : Integer.MAX_VALUE,
                command.getTeamSearches(), command.getScope(),
                new com.aresstack.askai.research.runtime.acquire.PageWorker.Log() {
                    public void line(String text) {
                        System.err.println(text);
                    }
                });
    }

    /** The page worker as the funnel's judge; after every page its messages to the orchestrator go to the host. */
    private com.aresstack.askai.research.runtime.acquire.PageJudge relayingJudge(final AgentOutput ctx,
            final com.aresstack.askai.research.runtime.acquire.PageWorker worker, final String requestId) {
        return new com.aresstack.askai.research.runtime.acquire.PageJudge() {
            public Verdict judge(Page page) {
                Verdict verdict = worker.judge(page);
                relayTeamNotes(ctx, worker.taskId(), requestId);
                return verdict;
            }
        };
    }

    /**
     * Plan T4: the orchestrator's mail goes to the host as {@code manual_search_team_note} lines, each correlated
     * with the planned query of the worker that wrote it; the plan (Java) decides what to do with it. A line that
     * is out is delivered.
     */
    private void relayTeamNotes(AgentOutput ctx, String taskId, String requestId) {
        com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox mailbox = teamMailbox();
        // only this worker's own mail: another worker's report must not be relayed after that worker's end
        java.util.List<com.aresstack.askai.research.runtime.team.mailbox.AgentMessage> mail =
                mailbox.drainOrchestratorFrom(taskId, Integer.MAX_VALUE);
        for (com.aresstack.askai.research.runtime.team.mailbox.AgentMessage message : mail) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.manualSearchTeamNote(
                    requestId, message.getSender().key() + ": " + message.getText()));
        }
        mailbox.acknowledge(mail);
    }

    private com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox teamMailbox;

    /** This chat's team mailbox (plan T3), opened on first use; it lives as long as the chat's agent. */
    private synchronized com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox teamMailbox() {
        if (teamMailbox == null) {
            try {
                teamMailbox = com.aresstack.askai.research.runtime.team.mailbox.AgentMailbox.open(
                        "team-" + Integer.toHexString(System.identityHashCode(this)),
                        new com.aresstack.askai.research.runtime.team.mailbox.InMemoryMailboxStore());
            } catch (java.io.IOException impossible) {
                throw new IllegalStateException("the in-memory mailbox store cannot fail", impossible);
            }
        }
        return teamMailbox;
    }

    /** The failure reason of a team query that got no worker browser: the chat has none (the plan goes on alone). */
    static final String NO_WORKER_BROWSER = "NO_WORKER_BROWSER";
    /**
     * The failure reason of a team query that got no tab of the team's page browser within the worker browsers'
     * wait setting: the running searches hold them all. The plan keeps the query pending and runs it when a
     * search ends; the team stays on.
     */
    static final String TEAM_TABS_BUSY = "TEAM_TABS_BUSY";

    /**
     * A tab of its own in the team's page browser for one team query (plan T5): one user context of this chat's
     * worker browsers holds the tabs of all page workers. Empty when the chat has no worker browsers at all, so the
     * query fails honestly with {@link #NO_WORKER_BROWSER} and the plan goes on in the chat browser.
     *
     * @throws com.aresstack.askai.research.runtime.browser.BrowserCapacityException no worker browser and no tab
     *         came free within the wait setting ({@link #TEAM_TABS_BUSY}: the query stays in the plan)
     */
    private java.util.List<com.aresstack.askai.research.runtime.browser.TeamTabs.Tab> acquireTeamTabs(
            String requestId, int max) throws InterruptedException,
            com.aresstack.askai.research.runtime.browser.BrowserCapacityException {
        com.aresstack.askai.research.runtime.browser.TeamTabs tabs = teamTabs();
        if (tabs == null || !tabs.available()) {
            System.err.println("[team] no worker browsers in this chat: requestId=" + requestId);
            return java.util.Collections.emptyList();
        }
        // Stop interrupts the wait for a worker browser or a free tab at once instead of after the wait setting
        teamWaiters.add(Thread.currentThread());
        try {
            return tabs.acquireUpTo(requestId, max);
        } finally {
            teamWaiters.remove(Thread.currentThread());
            Thread.interrupted(); // a Stop that came after the wait must not hit the query's later browser calls
        }
    }

    private com.aresstack.askai.research.runtime.browser.TeamTabs teamTabs;

    /** The team's page browser over this chat's worker browsers, made on first use. */
    private synchronized com.aresstack.askai.research.runtime.browser.TeamTabs teamTabs() {
        com.aresstack.askai.research.runtime.browser.BrowserUserContextPool pool = browserUserContexts;
        if (pool == null) {
            return null;
        }
        if (teamTabs == null) {
            teamTabs = new com.aresstack.askai.research.runtime.browser.TeamTabs(pool);
        }
        return teamTabs;
    }

    /** The threads of team queries waiting for a tab right now; {@link #cancel()} interrupts them. */
    private final java.util.Set<Thread> teamWaiters = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** The stop flags of the team queries running now; {@link #cancel()} sets every one of them. */
    private final java.util.Set<java.util.concurrent.atomic.AtomicBoolean> teamRuns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The TeamAgent REVIEWS the sources it was pointed at. Issue #29: this runs ONLY from the explicit
     * review_sources service command ("Neue Quellen auswerten"), never as an automatic continuation of a
     * search. The instruction is internal — it goes through {@code internalTurn}, so a button press never
     * enters the conversation as a user message.
     * <p>
     * The context is the sources' ACTUAL CONTENT ({@code source_review_context}), not the id/title listing
     * of {@code source_list}: an agent asked to report what we learned, and given only titles, can do
     * nothing but invent. {@code capturedThrough} pins it to the same material the host will record as
     * reviewed.
     */
    private com.aresstack.askai.research.domain.search.PostSearchReviewOutcome
            reviewNewSourcesAndRefreshSuggestions(final AgentOutput ctx, long capturedThrough) {
        if (teamAgent == null) {
            return com.aresstack.askai.research.domain.search.PostSearchReviewOutcome.FAILED;
        }
        final com.aresstack.askai.research.runtime.team.TeamAgentStateView view =
                readStateView(ctx).withSources(readReviewContext(ctx, capturedThrough));
        // Serialised with chat turns (PR 17): the review never interleaves with a turn's tool rounds.
        return teamAgent.exclusively(new java.util.function.Supplier<
                com.aresstack.askai.research.domain.search.PostSearchReviewOutcome>() {
            public com.aresstack.askai.research.domain.search.PostSearchReviewOutcome get() {
                return runPostSearchReview(ctx, view);
            }
        });
    }

    private com.aresstack.askai.research.domain.search.PostSearchReviewOutcome runPostSearchReview(
            final AgentOutput ctx, com.aresstack.askai.research.runtime.team.TeamAgentStateView view) {
        return com.aresstack.askai.research.runtime.team.PostSearchReview.run(view,
                new com.aresstack.askai.research.runtime.team.PostSearchReview.Model() {
                    public com.aresstack.askai.research.runtime.team.TeamAgentResult internalTurn(
                            String instruction,
                            com.aresstack.askai.research.runtime.team.TeamAgentStateView v) {
                        return teamAgent.reviewTurn(instruction, v);
                    }
                },
                new com.aresstack.askai.research.runtime.team.PostSearchReview.Emitter() {
                    public void emitResult(
                            com.aresstack.askai.research.runtime.team.TeamAgentResult result,
                            String phaseId) {
                        emitTeamAgentResult(ctx, result, phaseId);
                    }

                    public void emitVisible(String message) {
                        ctx.message(message);
                    }

                    public void emitLog(String line) {
                        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                .log(line));
                    }
                },
                sessionLanguage.code());
    }

    /**
     * Read the bounded content of the sources this review is about. An empty answer is NOT silently
     * turned into "review the titles instead": the review then honestly has nothing to work with, which
     * the instruction and the material itself make visible to the model.
     */
    private String readReviewContext(AgentOutput ctx, long capturedThrough) {
        try {
            java.util.Map<String, Object> args = new java.util.HashMap<String, Object>();
            args.put("captured_through", String.valueOf(Math.max(0L, capturedThrough)));
            // "The NEW sources" means THIS search's window: without the lower edge every review re-read
            // the whole cumulative corpus and produced the same summary and clusters, search after search.
            args.put("captured_since", String.valueOf(Math.max(0L, lastManualSearchStartedAt)));
            // Request-id scoping: the review reads ONLY what THIS search found — a second search's
            // review no longer mixes in the first search's material via a leaky time window.
            if (!lastManualSearchRequestId.isEmpty()) {
                args.put("search_request_id", lastManualSearchRequestId);
            }
            String context = researchCall("source_review_context", args);
            System.err.println("[manual-search] review context chars="
                    + (context == null ? 0 : context.length()) + " capturedThrough=" + capturedThrough
                    + " capturedSince=" + lastManualSearchStartedAt);
            return context == null ? "" : context;
        } catch (RuntimeException unavailable) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("source_review_context unavailable: " + unavailable.getMessage()));
            System.err.println("[manual-search] review context unavailable: " + unavailable.getMessage());
            return "";
        }
    }

    /**
     * Read the host's authoritative {@code research_status} once for this turn, echo the raw line as a
     * technical log (collapsed diagnostics, never a chat bubble) and parse it into the read-only
     * {@link TeamAgentStateView} the TeamAgent is given. An unreachable endpoint yields a neutral view and an
     * honest log line — never a fabricated state.
     */
    private com.aresstack.askai.research.runtime.team.TeamAgentStateView readStateView(AgentOutput ctx) {
        try {
            String raw = researchCall("research_status", Collections.<String, Object>emptyMap());
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.log("status: " + raw));
            com.aresstack.askai.research.runtime.team.TeamAgentStateView view =
                    com.aresstack.askai.research.runtime.team.ResearchStatusView.parse(raw);
            // Best-effort: give the agent the bounded source overview (plan PR 16: counts per status and the
            // ten newest titles, never every source) so it can reference what a web search just found
            // instead of asking the user to re-describe it. Never fatal.
            try {
                String sources = researchCall("source_overview", Collections.<String, Object>emptyMap());
                if (sources != null && !sources.trim().isEmpty()
                        && !"No sources.".equals(sources.trim())) {
                    view = view.withSources(sources);
                }
            } catch (RuntimeException sourcesUnavailable) {
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .log("source_overview unavailable: " + sourcesUnavailable.getMessage()));
            }
            return view;
        } catch (RuntimeException ex) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("research MCP unavailable: " + ex.getMessage()));
            return com.aresstack.askai.research.runtime.team.ResearchStatusView.empty();
        }
    }

    /**
     * Send one TeamAgent turn to the user: the model's own message on OK (a plain output line the host renders
     * as an assistant bubble), or an honest typed line on MODEL_UNAVAILABLE / UNUSABLE_ANSWER /
     * COMMAND_REJECTED.
     *
     * <p>This emits NOTHING that can move the workflow. The model is prozessual machtlos: it advises, it
     * paraphrases, it keeps the scope sharp — but only an explicit user action (a host-owned button that
     * runs a command through the state machine) may advance a phase. There is deliberately no
     * {@code readyForBrief} flag, no proceed-word analysis and no auto-emitted scope proposal here; the
     * user owns every transition.</p>
     */
    /**
     * The Konzeptpapier TOOL-ROUND loop of one user turn (K2b): while the model's answer carries a
     * {@code conceptAction}, execute it on the host's synchronous MCP lane and run a follow-up
     * inference with the typed feedback (TOOL_RESULT / TOOL_APPLIED / TOOL_REJECTED). Only the
     * FINAL result reaches {@code emitTeamAgentResult}; every loop step leaves a technical log
     * line. Without concept tools (older host) the initial result passes through untouched.
     */
    /**
     * One user turn with concept tools: the machine classifies the intent FIRST — an explicit
     * move order runs the DEDICATED move generation (move-gate ruling: source/parent starved
     * inside the universal grammar) and executes it through the ordinary tool funnel BEFORE
     * the conversational inference, which then already sees the moved tree; the loop receives
     * the authoritative receipt state as its truth-guard seed.
     */
    private com.aresstack.askai.research.runtime.team.TeamAgentResult conceptAwareRespond(
            final AgentOutput ctx,
            final com.aresstack.askai.research.runtime.team.TeamAgentStateView view,
            String text) {
        if (!com.aresstack.askai.research.runtime.team.PhaseActionUnion.conceptEditing(view.getPhaseId())) {
            // Plan PR 16: the concept move path and the concept edit rules are Concept-only. "Move this
            // paragraph to chapter 2" in Document is a plain turn of the Document assistant.
            return teamAgent.respond(text, view);
        }
        if (conceptToolsAvailable
                && com.aresstack.askai.research.runtime.team.ConceptTurnPolicy.modeFor(text)
                        == com.aresstack.askai.research.runtime.team.ConceptTurnPolicy.Mode
                                .MOVE_TRUTH) {
            // TERMINAL (gate ruling): the dedicated path is the turn's ONE authoritative
            // concept decision — no general loop afterwards (two competing paths once
            // produced invalid rounds and a false 'could not execute' close over an APPLIED
            // receipt), and the visible answer derives deterministically from the receipt.
            boolean german = "de".equalsIgnoreCase(sessionLanguage.code());
            conceptLog(ctx, "dedicated move generation (two-field schema)");
            String conceptContext;
            try {
                conceptContext = conceptToolCall(wholeConceptRead());
            } catch (Exception unavailable) {
                conceptContext = "";
            }
            com.aresstack.askai.research.runtime.team.ConceptAction move =
                    com.aresstack.askai.research.runtime.team.MoveActionGenerator.generate(
                            phaseChat(com.aresstack.askai.research.runtime.team.PhaseAssistantProfileRegistry
                                    .SCOPING_PHASE_ID), text, conceptContext);
            String answer;
            if (move == null) {
                conceptLog(ctx, "dedicated move generation -> unclear (no guess, no mutation)");
                answer = com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                        .moveTruthAnswer(german, "unclear");
            } else {
                conceptLog(ctx, "dedicated move: " + move.describe());
                try {
                    String receipt = conceptToolCall(move);
                    if (receipt.startsWith("NO_CHANGE")) {
                        conceptLog(ctx, "dedicated move -> NO_CHANGE (already at target)");
                        answer = com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                                .moveTruthAnswer(german, "already-at-target");
                    } else {
                        conceptLog(ctx, "dedicated move -> APPLIED (terminal)");
                        answer = com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                                .moveAppliedAnswer(german, receipt);
                    }
                } catch (com.aresstack.askai.research.runtime.loop.ToolInvoker
                        .ToolFailure rejected) {
                    String reason = rejected.getMessage() == null ? "rejected"
                            : rejected.getMessage().replace("\r", "").replace('\n', ' ');
                    conceptLog(ctx, "dedicated move -> REJECTED " + reason);
                    // The product sentence — internal markers never leak into the chat.
                    answer = com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                            .moveRejectedAnswer(german, reason);
                } catch (com.aresstack.askai.research.runtime.loop.ToolInvoker
                        .EndpointUnavailable dead) {
                    conceptLog(ctx, "dedicated move lost — endpoint unavailable");
                    answer = com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                            .moveTruthAnswer(german, null);
                }
            }
            com.aresstack.askai.research.runtime.team.TeamAgentResult terminal =
                    com.aresstack.askai.research.runtime.team.ConceptToolRounds
                            .hostAnswer(answer, null);
            if (terminal != null) {
                return terminal;
            }
        }
        return runConceptToolRounds(ctx, teamAgent.respond(text, view), view, text,
                false, null);
    }

    /** One read-only host block for the turn context; "" when the host has none or is unreachable. */
    private String hostBlock(String tool, String noneAnswer) {
        try {
            String text = researchCall(tool, Collections.<String, Object>emptyMap());
            if (text == null || text.trim().isEmpty() || noneAnswer.equals(text.trim())) {
                return "";
            }
            return text.trim();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static void conceptLog(AgentOutput ctx, String message) {
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                .log("concept " + message));
    }

    private com.aresstack.askai.research.runtime.team.TeamAgentResult runConceptToolRounds(
            final AgentOutput ctx,
            com.aresstack.askai.research.runtime.team.TeamAgentResult initial,
            final com.aresstack.askai.research.runtime.team.TeamAgentStateView view,
            String userText, boolean seededMovedReceipt, String seededMoveOutcome) {
        if (!conceptToolsAvailable) {
            teamAgent.completeTurn(initial, "");
            return initial;
        }
        // The applied actions of this turn, for the one-line receipt of its FINAL history entry (D7).
        final java.util.List<String> applied = new java.util.ArrayList<String>();
        com.aresstack.askai.research.runtime.team.TeamAgentResult closed =
                com.aresstack.askai.research.runtime.team.ConceptToolRounds.run(initial,
                new com.aresstack.askai.research.runtime.team.ConceptToolRounds.FollowUpTurn() {
                    public com.aresstack.askai.research.runtime.team.TeamAgentResult run(
                            String feedbackInstruction) {
                        // Machinery instruction, real conversation context — same seam the
                        // review turns use; the instruction never enters the history as user text.
                        return teamAgent.internalTurn(feedbackInstruction, view);
                    }

                    public com.aresstack.askai.research.runtime.team.TeamAgentResult run(
                            String feedbackInstruction, String requiredActionType) {
                        // A nudge/repair that asks for ONE action type gets the grammar of only
                        // that variant — it can neither answer "none" nor narrate the action.
                        return teamAgent.internalTurn(feedbackInstruction, view, requiredActionType);
                    }
                },
                new com.aresstack.askai.research.runtime.team.ConceptToolRounds.ConceptTool() {
                    public String call(com.aresstack.askai.research.runtime.team.ConceptAction action)
                            throws com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure,
                            com.aresstack.askai.research.runtime.loop.ToolInvoker.EndpointUnavailable {
                        String receipt = conceptToolCall(action);
                        if (com.aresstack.askai.research.runtime.team.ConceptToolRounds
                                .isAppliedOutcome(action, receipt)) {
                            applied.add(action.describe());
                        }
                        return receipt;
                    }
                },
                conceptMaxToolRounds, conceptMaxRepairAttempts,
                "de".equalsIgnoreCase(sessionLanguage.code()),
                new com.aresstack.askai.research.runtime.team.ConceptToolRounds.IntermediateSink() {
                    public void intermediate(
                            com.aresstack.askai.research.runtime.team.ScopingAssistantOutput output) {
                        emitScopeUpdate(ctx, view.getPhaseId(), output, false);
                    }
                },
                new com.aresstack.askai.research.runtime.team.ConceptToolRounds.Trace() {
                    public void line(String message) {
                        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                                .log("concept " + message));
                    }
                },
                !searchTagsOffered, // nudge until the session offered its first tags (gate 9b)
                // Machine-side intent separation (safety-slice gate 2): a compound
                // restructuring or delete wish closes the concept mutation channel for the
                // WHOLE turn — capability withdrawal per action was not enough.
                com.aresstack.askai.research.runtime.team.ConceptTurnPolicy.modeFor(userText),
                seededMovedReceipt, seededMoveOutcome,
                // A name the user wrote literally is never taken for a prompt placeholder, and a
                // bare greeting never triggers the first-turn card nudge.
                userText);
        teamAgent.completeTurn(closed, String.join("; ", applied));
        return closed;
    }

    /** Whether THIS session ever offered exploration tags — ends the one-shot offer nudge. */
    private volatile boolean searchTagsOffered;

    /**
     * One synchronous concept tool call over the research lane, with the lane's error mapping:
     * handler errors / our textual error contract → ToolFailure (the
     * diagnostic travels IN the message, back to the model); connect/timeout → EndpointUnavailable.
     */
    private String conceptToolCall(com.aresstack.askai.research.runtime.team.ConceptAction action)
            throws com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure,
            com.aresstack.askai.research.runtime.loop.ToolInvoker.EndpointUnavailable {
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<String, Object>();
        String toolName;
        // Segments travel as a JSON ARRAY string — never slash-joined: '/' in a card name
        // (TCP/IP, C/C++) must stay a character, and a missed parent must never collapse a
        // whole path into one literal root name.
        switch (action.getType()) {
            case READ:
                toolName = "concept_read";
                if (!action.getPath().isEmpty()) {
                    args.put("path_json", segmentsJson(action.getPath()));
                }
                break;
            case ADD:
                // Legacy transcripts only — the loop refuses model ADDs before this funnel. The host's
                // single-card concept_add is gone (plan PR 16): one card travels as a one-name list.
                toolName = "concept_add_cards";
                if (!action.getParent().isEmpty()) {
                    args.put("parent_path_json", segmentsJson(action.getParent()));
                }
                args.put("names_json", segmentsJson(java.util.Collections.singletonList(action.getName())));
                break;
            case ADD_CARDS:
                // The atomic list capture: ALL user-named areas in one call, one revision.
                toolName = "concept_add_cards";
                if (!action.getParent().isEmpty()) {
                    args.put("parent_path_json", segmentsJson(action.getParent()));
                }
                args.put("names_json", action.getNamesJson());
                break;
            case MOVE:
                // One leaf, one existing target, one atomic revision — UUID unchanged.
                toolName = "concept_move_leaf";
                args.put("source_json", segmentsJson(action.getPath()));
                args.put("parent_path_json", segmentsJson(action.getParent()));
                break;
            case EXCLUDE:
                // The ONE-command exclusion facade: the model quotes the user's term, the HOST
                // owns id, facet, blacklist effect and the concept-conflict check.
                toolName = "exclude_topic";
                args.put("topic", action.getTopic());
                break;
            case RESOLVE:
                toolName = "resolve_concept_conflict";
                args.put("conflict_id", action.getConflictId());
                args.put("decision", action.getDecision());
                break;
            case OFFER:
                // Gate 8b: the OPTIONAL in-band suggestions field starved under the grammar —
                // as a COMMAND the model actually offers; the host renders the yellow tags.
                toolName = "offer_searches";
                args.put("suggestions_json", action.getSuggestionsJson());
                break;
            case PROBE:
                // scope_probe (AP3): the read-only closure sensor — one list of terms, the
                // host owns embedding, fence geometry and the stale gate.
                toolName = "scope_probe";
                args.put("terms_json", action.getTermsJson());
                break;
            case RENAME:
                toolName = "concept_rename";
                args.put("path_json", segmentsJson(action.getPath()));
                args.put("name", action.getName());
                break;
            default:
                // Safety slice: destructive edits (REMOVE/REWRITE) left the model contract.
                // ConceptToolRounds refuses them before this funnel — this is defense in depth
                // for any future action type that was not wired deliberately.
                throw new com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure(
                        com.aresstack.askai.research.runtime.team.TeamAgentPlaybook
                                .destructiveEditRefusal());
        }
        try {
            // An error result is the tool's REJECTION and surfaces as a ToolFailure from the lane itself
            // (the diagnostic travels IN the message, back to the model).
            String text = research.call(toolName, args);
            if (text.startsWith("Tool failed:") || text.startsWith("Not allowed")) {
                throw new com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure(text);
            }
            if (action.getType()
                    == com.aresstack.askai.research.runtime.team.ConceptAction.Type.OFFER) {
                searchTagsOffered = true; // the session has its tags — the nudge retires
            }
            return text;
        } catch (com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure ex) {
            throw ex;
        } catch (RuntimeException ex) {
            String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            if (message.contains("Connection") || message.contains("connect")
                    || message.contains("Timeout") || message.contains("timeout")) {
                throw new com.aresstack.askai.research.runtime.loop.ToolInvoker
                        .EndpointUnavailable(message);
            }
            throw new com.aresstack.askai.research.runtime.loop.ToolInvoker.ToolFailure(message);
        }
    }

    /** The whole-concept read action used for the per-inference CURRENT_CONCEPT block. */
    private static com.aresstack.askai.research.runtime.team.ConceptAction wholeConceptRead() {
        return com.aresstack.askai.research.runtime.team.ConceptAction.parse(
                java.util.Collections.singletonMap("type", (Object) "read")).getAction();
    }

    /** Card-name segments as a compact JSON array string (proper escaping, no separators). */
    private static String segmentsJson(java.util.List<String> segments) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < segments.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"');
            String segment = segments.get(i);
            for (int c = 0; c < segment.length(); c++) {
                char ch = segment.charAt(c);
                if (ch == '"' || ch == '\\') {
                    sb.append('\\');
                }
                sb.append(ch);
            }
            sb.append('"');
        }
        return sb.append(']').toString();
    }

    /**
     * Send one output's scope proposal to the host: valid → scopeUpdate; invalid FINAL → the
     * visible rejection; invalid INTERMEDIATE → a technical log line only. Shared by the FINAL
     * result and every INTERMEDIATE concept round, so a turn carrying conceptAction AND
     * scopePatch loses neither — the live "kein ESP-IDF" exclusion was dropped exactly here
     * before. The intermediate/final split is live-gate 3's contradiction fix: an invalid
     * attempt that a repair round supersedes must not tell the USER "Der Rechercheumfang wurde
     * NICHT aktualisiert" right before the repaired patch commits revision N+1 — the rejection
     * of a superseded attempt is diagnostics, not a user-facing outcome.
     */
    private void emitScopeUpdate(AgentOutput ctx, String phaseId,
            com.aresstack.askai.research.runtime.team.ScopingAssistantOutput output,
            boolean finalTurn) {
        com.aresstack.askai.research.runtime.team.ScopeUpdateDocument scopeUpdate =
                output.getScopeUpdate();
        if (scopeUpdate == null) {
            return;
        }
        // Malformed ADVISORY elements are dropped, not fatal — but silently dropping them would
        // hide a model weakness, so each drop leaves a log line.
        for (String dropped : scopeUpdate.getDroppedAdvisories()) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("scope advisory dropped: " + dropped));
        }
        if (scopeUpdate.isValid()) {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .scopeUpdate(phaseId, scopeUpdate.toJson()));
        } else if (finalTurn) {
            // The WHOLE update is dropped — never a part of it — and the host says so visibly.
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .scopeUpdateRejected(phaseId, scopeUpdate.describeViolations()));
        } else {
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .log("scope patch rejected (superseded by a follow-up turn): "
                            + scopeUpdate.describeViolations()));
        }
    }

    /** An integer env hand-off: unset/invalid → the documented default (logged, never fatal). */
    private int envInt(String name, int fallback) {
        String value = environment.get(name);
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed > 0 ? parsed : fallback;
        } catch (NumberFormatException invalid) {
            System.err.println("[research-agent] ignoring invalid " + name + "=" + value.trim());
            return fallback;
        }
    }

    private void emitTeamAgentResult(AgentOutput ctx,
            com.aresstack.askai.research.runtime.team.TeamAgentResult result, String phaseId) {
        // A failure line is as visible as an answer: it follows the session language, not a hard-coded one.
        ctx.message(com.aresstack.askai.research.runtime.team.TeamAgentReply.visible(
                result, sessionLanguage.code()));
        // A scoping turn ALSO publishes a display-only projection (exploration map + search suggestions) for
        // the scoping workspace. It carries no research brief and moves nothing; a non-scoping turn projects
        // nothing (wireLineFor returns null).
        if (result.getStatus() == com.aresstack.askai.research.runtime.team.TeamAgentResult.Status.OK) {
            String projection = com.aresstack.askai.research.runtime.team.ScopingProjectionEncoder
                    .wireLineFor(phaseId, result.getOutput());
            if (projection != null) {
                ctx.message(projection);
            }
            // The research brief (the phase artifact) travels on its OWN wire line so the host persists it on
            // exactly one path (its working copy). Only a scoping output has a brief; other phases emit none.
            // The proposed SCOPE CHANGES travel on their own line: the host applies them to the scope it
            // owns. Display projection (above) and scope update are different concerns.
            if (result.getOutput() instanceof com.aresstack.askai.research.runtime.team
                    .ScopingAssistantOutput) {
                emitScopeUpdate(ctx, phaseId,
                        (com.aresstack.askai.research.runtime.team.ScopingAssistantOutput)
                                result.getOutput(), true);
            }
            String brief = com.aresstack.askai.research.runtime.team.ScopingBriefSource
                    .briefMarkdown(result.getOutput());
            if (brief != null) {
                ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                        .researchBrief(phaseId, brief));
            }
            emitScopingDiagnostics(ctx, phaseId, result.getOutput(), projection != null);
        }
    }

    /**
     * A collapsible TECHNICAL trace of the scoping projection chain for one turn (never a chat bubble, never
     * the prompt or any secret): phase, output class and the field sizes that decide whether a projection was
     * emitted. Lets a GUI run pinpoint the first deviating point (phase / profile / contract / output / emit).
     */
    private void emitScopingDiagnostics(AgentOutput ctx, String phaseId,
            com.aresstack.askai.research.runtime.team.PhaseAssistantOutput output, boolean emitted) {
        StringBuilder sb = new StringBuilder("scopeassist diag phase=").append(phaseId)
                .append(" outputClass=").append(output == null ? "null" : output.getClass().getSimpleName());
        if (output instanceof com.aresstack.askai.research.runtime.team.ScopingAssistantOutput) {
            com.aresstack.askai.research.runtime.team.ScopingAssistantOutput scoping =
                    (com.aresstack.askai.research.runtime.team.ScopingAssistantOutput) output;
            sb.append(" briefLen=").append(scoping.getResearchBriefMarkdown().length())
                    .append(" suggestions=").append(scoping.getSearchSuggestions().size());
        }
        sb.append(" emitted=").append(emitted);
        ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire.log(sb.toString()));
    }

    /** The visible refusal of {@code research:} outside research/running (plan PR 15): no browser call. */
    static String researchRefusal(String languageCode) {
        return "de".equalsIgnoreCase(languageCode == null ? "" : languageCode.trim())
                ? "\"research:\" startet die autonome Recherche nur, während die Quellenphase läuft. "
                        + "Für eine einzelne Websuche nutze /search."
                : "\"research:\" runs the autonomous research only while the Sources phase is running. "
                        + "Use /search for a single web search.";
    }

    /** Mirror the host state: research phase in run state — the only condition for an autonomous turn. */
    private boolean hostIsInResearchRunning() {
        try {
            String status = researchCall("research_status", Collections.<String, Object>emptyMap());
            return status.contains("research/running");
        } catch (RuntimeException ex) {
            return false; // unreachable status → answer as a plain turn, never start a blind run
        }
    }

    /**
     * The 36A loop, verbatim: content-driven, centrally budgeted, PHASE_READY as an EVENT line (the host
     * remains the only state authority — this process never switches phases). Stop reason and progress are
     * reported explicitly as output lines, never only to logs.
     */
    private void runResearchLoop(final AgentOutput ctx, String task) {
        // Turn boundary (idle): apply any pending central-model descriptor reload BEFORE building the loop,
        // so this turn uses the freshly rebuilt inference port; a switch never happens mid-run.
        applyPendingModelReload(ctx);
        {
            final com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget =
                    com.aresstack.askai.research.runtime.loop.EnvironmentRunBudget.from(environment.values());
            com.aresstack.askai.research.runtime.loop.ResearchLoop loop = buildResearchLoop(ctx, browser, research,
                    budget);
            com.aresstack.askai.research.runtime.loop.ResearchStopReason reason = loop.run(task);
            visitedAcrossRuns.addAll(loop.getProgress().getVisitedCanonicalUrls());
            // The STRUCTURED outcome is the only basis for the user-facing result card.
            ctx.message(com.aresstack.askai.research.runtime.loop.ResearchRunWire
                    .outcome(loop.outcome(reason)));
        }
    }

    /**
     * The productive autonomous loop over {@code browserLane} and {@code researchLane}: the single loop's
     * configuration, shared by the orchestrated run's web research worker (on its own worker user context, or on
     * the chat browser without one) so both paths run through the SAME funnel. {@code ctx} receives
     * the loop's status, progress and attention lines; null routes them to the technical log only.
     */
    private com.aresstack.askai.research.runtime.loop.ResearchLoop buildResearchLoop(final AgentOutput turn,
            ToolInvoker browserLane, ToolInvoker researchLane,
            final com.aresstack.askai.research.runtime.loop.ResearchRunBudget budget) {
        final AgentOutput ctx = turn != null ? turn : new AgentOutput() {
            public void message(String line) {
                System.err.println("[research-agent] " + line);
            }
        };
        {
            com.aresstack.askai.research.runtime.loop.ResearchLoop loop =
                    new com.aresstack.askai.research.runtime.loop.ResearchLoop(browserLane, researchLane, budget,
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopClock() {
                                public long currentTimeMillis() {
                                    return System.currentTimeMillis();
                                }

                                public void sleepMillis(long millis) {
                                    try {
                                        Thread.sleep(millis);
                                    } catch (InterruptedException interrupted) {
                                        Thread.currentThread().interrupt();
                                    }
                                }
                            },
                            new com.aresstack.askai.research.runtime.loop.ResearchLoopListener() {
                                public void status(String message) {
                                    // Diagnostics: technical details only — NEVER a chat bubble.
                                    ctx.message(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.log(message));
                                }

                                public void progress(
                                        com.aresstack.askai.research.runtime.loop.ResearchRunProgress p,
                                        com.aresstack.askai.research.runtime.loop.ResearchRunActivity activity) {
                                    // ONE in-place progress card per run, updated structurally.
                                    ctx.message(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.progress(p, budget, activity));
                                }

                                public void phaseReady(
                                        com.aresstack.askai.research.runtime.loop.ResearchStopReason reason) {
                                    // Event only — the HOST decides; carried in the run outcome + log.
                                    ctx.message(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.log("PHASE_READY: " + reason));
                                }

                                public void attention(String reason, String domainFamily, String url,
                                                      boolean resolved) {
                                    // Typed user-attention transition — rendered visibly by the UI.
                                    ctx.message(com.aresstack.askai.research.runtime.loop
                                            .ResearchRunWire.attention(reason, domainFamily, url, resolved));
                                }
                            }, cancelled, loadBrowserSearchSettings());
            // The MANDATORY local reranker was built and readiness-checked at session/new; inject it so
            // every organic candidate is scored before any web_open. (Non-browser turns never reach here.)
            // The loop resolves its scorer exactly like the manual search: the user's "use AI reranker"
            // setting decides between BM25 (default) and the model reranker of the session language.
            if (reranker != null) {
                loop.setReranker(rerankerFor(sessionLanguage.code()));
                loop.setRedirectResolver(redirectResolver());
                loop.setChallengeWaitForUser(challengeWaitForUser());
            }
            // Inject the session's chosen initial-search strategy (null → keep the default browser path).
            // On the API-provider path inference is irrelevant; on the browser path weave the productive
            // structured-inference port into the default SERP strategy for model-backed layout repair.
            if (searchStrategy != null) {
                loop.setSearchStrategy(searchStrategy, searchProviderLabel);
            } else {
                if (inferencePort != null) {
                    loop.setStructuredInferencePort(inferencePort);
                }
                // Browser (SERP): the configured engines around the loop's own research-browser search.
                com.aresstack.askai.research.runtime.search.BrowserEnginesSearchStrategy engines =
                        browserEngines(loop.searchStrategy());
                loop.setSearchStrategy(engines, browserEnginesLabel(engines));
            }
            // SC1: the autonomous loop gets the same scope-control lane (internal service
            // endpoint) — a canonical OUT is filtered identically on both acquisition paths.
            if (hasService()) {
                loop.setScopeControl(
                        new com.aresstack.askai.research.runtime.acquire.ServiceSearchScopeControlPort(
                                service));
            }
            // Continuation semantics: a later run of the same session never re-navigates target pages.
            synchronized (visitedAcrossRuns) {
                loop.excludeVisited(visitedAcrossRuns);
            }
            return loop;
        }
    }

    /**
     * The loop's settings come from the SAME config document the browser runtime receives
     * ({@code ASKAI_BROWSER_SEARCH_CONFIG}); without one, exactly the central defaults apply. A broken
     * document fails the run visibly — settings are never silently substituted.
     */
    private com.aresstack.askai.browser.search.LegacyBrowserSearchSettings loadBrowserSearchSettings() {
        String path = environment.browserSearchConfigPath;
        if (path == null) {
            return com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        }
        try {
            byte[] bytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path));
            com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument document =
                    com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument
                            .parse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec.Decoded decoded =
                    com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec
                            .fromValues(document.values);
            if (!decoded.violations.isEmpty()) {
                throw new IllegalStateException("browser search config has invalid values:\n"
                        + new com.aresstack.askai.browser.search.SettingsValidationResult(
                                decoded.violations).describe());
            }
            return decoded.settings;
        } catch (java.io.IOException ex) {
            throw new IllegalStateException(
                    "Cannot read ASKAI_BROWSER_SEARCH_CONFIG=" + path + ": " + ex.getMessage());
        }
    }
}
