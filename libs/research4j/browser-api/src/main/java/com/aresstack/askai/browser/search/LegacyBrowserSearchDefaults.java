package com.aresstack.askai.browser.search;

import java.util.Arrays;

/**
 * THE single origin of every default value of the legacy browser search. All values that were
 * previously constants in sidecar/loop classes (SERP guard selectors, readiness timings, engine
 * fallbacks, probe cadences) live here and ONLY here. Components never invent substitute values;
 * a missing configuration is answered by {@link #create()}, never by a local constant.
 */
public final class LegacyBrowserSearchDefaults {

    private LegacyBrowserSearchDefaults() {
    }

    /** One complete settings object that validates cleanly against the default validator. */
    public static LegacyBrowserSearchSettings create() {
        return new LegacyBrowserSearchSettings(navigation(), consent(), captcha(), readiness(),
                analysis(), visualAnalysis(), extraction(), aiLayoutResolver(), reranker(),
                diagnostics(), layoutRepair());
    }

    private static SearchLayoutRepairSettings layoutRepair() {
        return new SearchLayoutRepairSettings(
                16,          // maximumCachedTickets: bounded per-session low-confidence snapshots
                120_000L);   // ticketTtlMillis: a repair ticket is applicable for two minutes
    }

    /**
     * The pause before each further Google request, in milliseconds — the "Delay (s)" of the Google entry under
     * Search → Engines, as the catalog names it
     * ({@link com.aresstack.askai.browser.search.engine.SearchEngineCatalog#GOOGLE_REQUEST_DELAY_MILLIS}).
     */
    public static final int GOOGLE_REQUEST_DELAY_MILLIS =
            com.aresstack.askai.browser.search.engine.SearchEngineCatalog.GOOGLE_REQUEST_DELAY_MILLIS;

    /**
     * The engines a fresh installation searches with, tried in this order until one delivers: Startpage first
     * (Google results with direct target URLs, no pause, validated live on 2026-11-17), DuckDuckGo as the
     * fallback, Google last (in the search browser, paced by {@link #GOOGLE_REQUEST_DELAY_MILLIS}). Bing stays in
     * the list but off — DuckDuckGo's results come from Bing. Google fetches one result page by default.
     */
    public static com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection engineSelection() {
        return new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection(
                Arrays.asList(
                        new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry(
                                com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog
                                        .STARTPAGE, true),
                        new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry(
                                com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog
                                        .DUCKDUCKGO, true),
                        new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry(
                                com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog
                                        .GOOGLE, true, 1, GOOGLE_REQUEST_DELAY_MILLIS),
                        new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.Entry(
                                com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog
                                        .BING, false)),
                com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE);
    }

    /**
     * Usable results after which no further priority group of Search → Engines is searched. One result page of an
     * engine carries about ten organic hits; after the shop, search-page and duplicate filters a group that still
     * leaves five has answered the query, while fewer means the query is worth the next group's engines.
     */
    public static final int MINIMUM_USABLE_RESULTS = 5;

    private static LegacySearchNavigationSettings navigation() {
        return new LegacySearchNavigationSettings(
                engineSelection(),
                4,          // maximumEngineAttempts: engine ENDPOINTS opened per search (room for all 3 engines + DDG Lite)
                20_000,     // navigationCommitTimeoutMillis (previously BrowserLimits.defaults())
                true,       // redirectResolutionEnabled
                2_048,      // maximumRedirectUrlLength
                20,         // searchResultLimit (previously OrganicResultSearchProvider.MAX_RESULTS)
                MINIMUM_USABLE_RESULTS,
                "",         // language: engine default
                "");        // country: engine default
    }

    private static ConsentHandlingSettings consent() {
        return new ConsentHandlingSettings(
                true,
                Arrays.asList(
                        // OneTrust (very common)
                        "#onetrust-accept-btn-handler",
                        // Cookiebot
                        "#CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll",
                        // Quantcast / CMP
                        ".qc-cmp2-summary-buttons button[mode='primary']",
                        // Usercentrics
                        "#uc-btn-accept-banner",
                        // Bing's own consent banner
                        "#bnp_btn_accept",
                        // Generic accept buttons inside cookie/consent containers (still ACCEPT-specific)
                        "[class*='cookie'] button[class*='accept']",
                        "[class*='consent'] button[class*='accept']",
                        "[id*='cookie'] button[id*='accept']",
                        "[id*='consent'] button[id*='accept']",
                        "button[data-action='accept']",
                        "button[data-action='accept-all']",
                        "button[data-action='acceptAll']"),
                Arrays.asList(
                        "accept all", "allow all", "i agree",
                        "alle akzeptieren", "alle annehmen", "alle zulassen", "zustimmen",
                        "akzeptieren"),
                1,      // maximumDismissAttempts per SERP read (current productive behaviour)
                250,    // detectionPollIntervalMillis
                0,      // detectionWindowMillis: single immediate check (current behaviour)
                600,    // postClickSettleMillis (previously in Playwright4jDriver)
                false,  // inspectFrames
                false); // focusBeforeClick
    }

    private static CaptchaHandlingSettings captcha() {
        return new CaptchaHandlingSettings(
                true,
                Arrays.asList(
                        "iframe[src*='captcha']", "iframe[title*='challenge']", "#challenge-form",
                        "[class*='captcha']", "[id*='captcha']",
                        // Bing's "one last step" challenge container
                        "#b_rrsr", "iframe[src*='turnstile']"),
                // Challenge PHRASES only. The bare word "captcha" was dropped: it names a product or a widget
                // ("FriendlyCaptcha" in a footer contact form, articles about CAPTCHAs), not a challenge page,
                // and parked readable pages (INNOQ). Stored profiles still carrying the old shipped list are
                // lifted by LegacyBrowserSearchSettingsCodec.
                Arrays.asList(
                        "noch ein letzter schritt", "one last step", "verify you are human",
                        "unusual traffic", "complete the challenge",
                        "bestätigen sie, dass sie ein mensch sind"),
                1_000,  // challengeProbeIntervalMillis (previously ResearchLoop constant)
                true,   // focusTabOnFirstDetection (bringToFront exactly once)
                false,  // playAttentionSound
                true,   // emitAttentionEvent
                true,   // blockDomainFamily
                true,   // retainChallengeTab
                true);  // waitForUser: wait for the user to solve a challenge (vs. skip and park)
    }

    private static SearchPageReadinessSettings readiness() {
        return new SearchPageReadinessSettings(250, 2, 48, 6_000, 20_000, 8_000,
                3);     // maximumPageReadinessRetries: scan→handle→re-scan attempts before parking
    }

    /**
     * Capture retries after a mid-capture DOM change: 0. Measured on Bing the SERP mutates continuously
     * (lazy images, ads, related searches): the retries never reached a consistent state and cost about
     * 15 s per search (docs/search-performance-analysis.md) — so capture once and flag the change.
     */
    public static final int DEFAULT_CAPTURE_RETRIES = 0;
    /** Settle wait before a capture retry (only used when retries are allowed). */
    public static final int DEFAULT_CAPTURE_SETTLE_MILLIS = 300;

    private static SearchPageAnalysisSettings analysis() {
        return new SearchPageAnalysisSettings(
                Arrays.asList(
                        "no results", "keine ergebnisse", "did not match any", "nichts gefunden",
                        "no matches", "0 results", "keine treffer"),
                24,     // maximumCandidateContainers
                80,     // minimumContainerTextCharacters
                24,     // minimumNonLinkTextCharacters
                3,      // minimumRepeatedSiblingCount
                0.5,    // minimumResultStructuralConfidence
                0.65,   // maximumNavigationLinkDensity
                0.2,    // internalLinkWeight
                1.0,    // externalLinkWeight
                0.6,    // sameHostPenalty
                0.3,    // sameRegistrableDomainPenalty
                0.4,    // subdomainPenalty
                0.2,    // unknownDomainPenalty
                1.2,    // repeatedBlockWeight — repeated sibling blocks are the strongest signal
                0.8,    // nonLinkTextWeight
                1.0,    // titleLinkWeight
                0.9,    // snippetPresenceWeight
                0.6,    // headingLinkWeight
                0.8,    // semanticMainWeight
                1.0,    // navigationRolePenalty
                0.6,    // resultBlockSimilarityThreshold
                1,      // minimumDiscriminatingSignalFamilies
                0.85,   // fullPageAreaRatio
                600,    // textLengthSaturationCharacters
                40,     // maximumContainerDomDepth
                1_200,  // maximumCapturedContainers: Bing page 1 hit the old 600 before its result list ended
                50,     // maximumLinksPerContainer
                3,      // maximumStructureSignatureDepth
                DEFAULT_CAPTURE_RETRIES,       // maximumCaptureRetries
                DEFAULT_CAPTURE_SETTLE_MILLIS, // captureSettleMillis
                5,      // linkHarvestMinimumStructuredCandidates — below this, external links are harvested
                20,     // linkHarvestMaximumCandidates
                // linkHarvestExcludedDomains: Bing's page furniture links microsoft.com legal/consent
                // pages on every SERP — chrome, never results.
                java.util.Arrays.asList("microsoft.com"));
    }

    private static SearchPageVisualAnalysisSettings visualAnalysis() {
        return new SearchPageVisualAnalysisSettings(
                true,
                0.92, 0.08, 0.85, 0.02,
                0.5, 0.45, 0.6, 0.5,
                1.0, 0.5, 0.6, 0.4, 0.3, 0.4, 0.3, 0.8, 0.5,
                12);
    }

    private static SearchResultExtractionSettings extraction() {
        return new SearchResultExtractionSettings(3, 0, 400, 40, 4, 0.5, 0.35);
    }

    private static AiLayoutResolverSettings aiLayoutResolver() {
        return new AiLayoutResolverSettings(
                true,   // productive: consulted ONLY on REPAIR_REQUIRED; without an inference port the
                        // resolver stays a typed AI_UNAVAILABLE — never a fabricated layout
                "central-main-model", // symbolic profile: the runtime's StructuredInferencePort targets
                        // the host-published central main-model descriptor (no registry lookup)
                ReasoningEffort.MEDIUM,
                0.0,
                2_000,
                "You resolve the layout of a search engine result page.\n"
                        + "You are given mechanically detected candidate containers with stable ids.\n"
                        + "Choose ONLY among the given container ids — never invent ids.\n"
                        + "Identify which containers are organic search results and which are\n"
                        + "navigation, advertisement or vertical modules. Answer strictly in the\n"
                        + "requested JSON schema.",
                "Query: {query}\n"
                        + "Page URL: {pageUrl}\n"
                        + "Candidate containers:\n"
                        + "{containerDescriptors}\n"
                        + "\n"
                        + "Classify each container id and name the organic result containers.",
                defaultRetryPolicy());
    }

    private static SearchResultRerankerSettings reranker() {
        return new SearchResultRerankerSettings(
                false,  // disabled until the reranker slice ships; the contract is fixed now
                RerankerImplementationType.LLM,
                "",
                ReasoningEffort.LOW,
                20,     // maximumCandidates
                8,      // maximumSelectedResults
                0.4,    // structuralScoreWeight
                0.5,    // semanticScoreWeight
                0.1,    // originalRankWeight
                "Query: {query}\n"
                        + "Candidates:\n"
                        + "{candidates}\n"
                        + "\n"
                        + "Select and order the at most {maximumSelectedResults} candidates that\n"
                        + "best answer the query. Refer to candidates ONLY by their given ids.",
                defaultRetryPolicy());
    }

    private static AiRetryPolicy defaultRetryPolicy() {
        return new AiRetryPolicy(
                3, 500, 2.0, 8_000,
                true,   // retryOnEmptyResponse
                true,   // retryOnParsingFailure
                true,   // retryOnSchemaViolation
                true,   // retryOnUnknownIds
                true,   // retryOnSemanticValidationFailure: the repair suffix hands the model the
                        // concrete violation (e.g. BLOCK_OUTSIDE_REGION) — exactly the class of
                        // mistake a second attempt can fix; still bounded by maximumAttempts

                true,   // retryOnModelTimeout
                true,   // includePreviousResponse
                true);  // includeValidationErrors
    }

    private static SearchDiagnosticsSettings diagnostics() {
        return new SearchDiagnosticsSettings(
                true,
                true, true, true, true,
                false,  // storeRawModelResponses: may quote page content at length
                true, true, true, true,
                400,        // maximumTextExcerptCharacters
                262_144,    // maximumDiagnosticArtifactBytes (256 KiB)
                false);     // redactUrls
    }
}
