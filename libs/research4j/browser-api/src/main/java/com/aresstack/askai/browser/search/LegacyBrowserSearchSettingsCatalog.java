package com.aresstack.askai.browser.search;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * UI metadata for every setting: section, label, description, kind and allowed range — the data
 * basis of the generic settings UI (A2d). The catalog is keyed by the SAME canonical codec keys as
 * persistence and validation, so every field automatically shows its default (from
 * {@link LegacyBrowserSearchDefaults} via the codec) and every validator violation finds its field.
 * Hard invariants are not in this catalog because they are not settings.
 */
public final class LegacyBrowserSearchSettingsCatalog {

    public enum Kind {
        BOOLEAN, INTEGER, DECIMAL, TEXT, TEXT_LIST, PROMPT, CHOICE,
        /** An ORDERED list of switchable search engines — order carries meaning, so a text box will not do. */
        ENGINE_LIST
    }

    public static final class Field {
        public final String key;
        public final String section;
        public final Kind kind;
        public final String label;
        public final String description;
        /** Allowed range for INTEGER/DECIMAL (both NaN = unbounded); shown in the UI. */
        public final double min;
        public final double max;
        /** CHOICE values; empty otherwise. */
        public final List<String> choices;
        /** Template variables available in a PROMPT; empty otherwise. */
        public final List<String> templateVariables;

        Field(String key, String section, Kind kind, String label, String description,
              double min, double max, List<String> choices, List<String> templateVariables) {
            this.key = key;
            this.section = section;
            this.kind = kind;
            this.label = label;
            this.description = description;
            this.min = min;
            this.max = max;
            this.choices = choices;
            this.templateVariables = templateVariables;
        }
    }

    public static final String SECTION_ENGINES = "Engines and Navigation";
    public static final String SECTION_CONSENT = "Consent";
    public static final String SECTION_CAPTCHA = "CAPTCHA Cooperation";
    public static final String SECTION_READINESS = "Readiness";
    public static final String SECTION_ANALYSIS = "Mechanical Page Analysis";
    public static final String SECTION_VISUAL = "Visual Page Analysis";
    public static final String SECTION_EXTRACTION = "Result Extraction";
    public static final String SECTION_AI_RESOLVER = "AI Layout Resolver";
    public static final String SECTION_RERANKER = "Reranker";
    public static final String SECTION_RETRY = "Retry Policies";
    public static final String SECTION_DIAGNOSTICS = "Diagnostics";
    public static final String SECTION_LAYOUT_REPAIR = "Layout Repair";

    private LegacyBrowserSearchSettingsCatalog() {
    }

    /** All sections in display order. */
    public static List<String> sections() {
        return Arrays.asList(SECTION_ENGINES, SECTION_CONSENT, SECTION_CAPTCHA, SECTION_READINESS,
                SECTION_ANALYSIS, SECTION_VISUAL, SECTION_EXTRACTION, SECTION_AI_RESOLVER,
                SECTION_RERANKER, SECTION_RETRY, SECTION_DIAGNOSTICS, SECTION_LAYOUT_REPAIR);
    }

    /** All fields in display order; keys match the codec exactly (verified by test). */
    public static List<Field> fields() {
        List<Field> f = new ArrayList<Field>();
        // --- Engines and Navigation
        engineList(f, "navigation.engines", SECTION_ENGINES, "Search engines",
                "One row per engine configuration, any engine of the catalog, the same engine as often as "
                        + "wanted: active, priority (0 runs first; equal priorities form one group), delay in "
                        + "seconds before every further request to that engine, result pages, and the search "
                        + "languages. Engines AskAI cannot search with yet are listed but not offered.");
        integer(f, "navigation.maximumEngineAttempts", SECTION_ENGINES, "Maximum engine attempts",
                "Upper bound of engine endpoints opened per search (one engine may own several).", 1, 10);
        integer(f, "navigation.navigationCommitTimeoutMillis", SECTION_ENGINES,
                "Navigation commit timeout (ms)",
                "Timeout for a navigation to commit in the browser.", 1_000, 120_000);
        choice(f, "navigation.engineAcquisitionMode", SECTION_ENGINES, "Search strategy",
                "Only for a web_search that names no engines (the bot's own search tool): FIRST_USABLE stops at "
                        + "the first engine that delivers, ALL_ENABLED merges all. The research search follows "
                        + "the priorities of the engine table.",
                enumNames(com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.values()));
        bool(f, "navigation.redirectResolutionEnabled", SECTION_ENGINES, "Resolve redirect wrappers",
                "Statically resolve engine redirect wrappers (Bing /ck/, Google /url, DuckDuckGo /l/) "
                        + "before any domain judgement.");
        integer(f, "navigation.maximumRedirectUrlLength", SECTION_ENGINES,
                "Maximum redirect URL length",
                "Links longer than this never enter redirect resolution or results.", 256, 65_536);
        integer(f, "navigation.searchResultLimit", SECTION_ENGINES, "Search result limit",
                "Maximum organic results returned per search.", 1, 100);
        integer(f, "navigation.minimumUsableResults", SECTION_ENGINES, "Enough results",
                "Usable results (distinct, no shop or search page) after which no further priority group is "
                        + "searched; fewer, and the next priority's engines are asked.", 1, 100);
        text(f, "navigation.language", SECTION_ENGINES, "Language",
                "Preferred content language (BCP-47, e.g. de); empty = engine default.");
        text(f, "navigation.country", SECTION_ENGINES, "Country",
                "Preferred region code (e.g. DE); empty = engine default.");
        // --- Consent
        bool(f, "consent.enabled", SECTION_CONSENT, "Enabled",
                "Dismiss cookie/consent banners on search pages. Only unambiguously positive "
                        + "controls are ever clicked — that policy is fixed.");
        list(f, "consent.positiveButtonSelectors", SECTION_CONSENT, "Positive button selectors",
                "CSS selectors of known-positive accept buttons (one per line).");
        list(f, "consent.positiveButtonTexts", SECTION_CONSENT, "Positive button texts",
                "Visible button texts (lower-case, one per line) that are an unambiguous consent.");
        integer(f, "consent.maximumDismissAttempts", SECTION_CONSENT, "Maximum dismiss attempts",
                "Attempts per search page read (stacked banners).", 1, 5);
        integer(f, "consent.detectionPollIntervalMillis", SECTION_CONSENT,
                "Detection poll interval (ms)",
                "Poll cadence while watching for a late-appearing banner.", 50, 5_000);
        integer(f, "consent.detectionWindowMillis", SECTION_CONSENT, "Detection window (ms)",
                "How long to keep watching for a late banner; 0 = single immediate check.",
                0, 30_000);
        integer(f, "consent.postClickSettleMillis", SECTION_CONSENT, "Post-click settle (ms)",
                "Settle time after a successful click before the page is re-read.", 0, 5_000);
        bool(f, "consent.inspectFrames", SECTION_CONSENT, "Inspect frames",
                "Also inspect same-origin iframes for consent controls.");
        bool(f, "consent.focusBeforeClick", SECTION_CONSENT, "Focus before click",
                "Focus the element before clicking (some CMPs ignore unfocused clicks).");
        // --- CAPTCHA
        bool(f, "captcha.enabled", SECTION_CAPTCHA, "Enabled",
                "Detect manual challenges. A CAPTCHA is never solved automatically, has no business "
                        + "timeout and never auto-retries its domain family — these are invariants.");
        list(f, "captcha.challengeSelectors", SECTION_CAPTCHA, "Challenge selectors",
                "DOM markers (CSS selectors, one per line) of a challenge page.");
        list(f, "captcha.challengeTexts", SECTION_CAPTCHA, "Challenge texts",
                "Visible texts (lower-case, one per line) that mark a manual challenge page.");
        integer(f, "captcha.challengeProbeIntervalMillis", SECTION_CAPTCHA,
                "Challenge probe interval (ms)",
                "Cadence of the non-reloading, non-focusing presence probe.", 250, 60_000);
        bool(f, "captcha.focusTabOnFirstDetection", SECTION_CAPTCHA, "Focus tab on first detection",
                "Bring the parked challenge tab to the front exactly once.");
        bool(f, "captcha.playAttentionSound", SECTION_CAPTCHA, "Play attention sound",
                "Play a sound when a challenge is first detected.");
        bool(f, "captcha.emitAttentionEvent", SECTION_CAPTCHA, "Emit attention event",
                "Emit a typed attention event to the research UI on first detection.");
        bool(f, "captcha.blockDomainFamily", SECTION_CAPTCHA, "Block domain family",
                "Lock the whole registrable-domain family while its challenge is pending.");
        bool(f, "captcha.retainChallengeTab", SECTION_CAPTCHA, "Retain challenge tab",
                "Keep the challenge tab open (parked) for the user.");
        bool(f, "captcha.waitForUser", SECTION_CAPTCHA, "Wait for user on challenge",
                "When a CAPTCHA blocks a page, WAIT for the user to solve it. Turn OFF to skip the blocked "
                        + "page instead and leave its source parked (empty full text). Applies uniformly to "
                        + "search and concrete-page visits.");
        // --- Readiness
        integer(f, "readiness.pollIntervalMillis", SECTION_READINESS, "Poll interval (ms)",
                "Probe cadence and settle-window width for content readiness.", 50, 5_000);
        integer(f, "readiness.settlePollCount", SECTION_READINESS, "Settle poll count",
                "Consecutive equal-content polls required to declare the page stable.", 1, 20);
        integer(f, "readiness.minimumReadableCharacters", SECTION_READINESS,
                "Minimum readable characters",
                "Minimum body text size before the stability check begins.", 0, 100_000);
        integer(f, "readiness.contentReadinessTimeoutMillis", SECTION_READINESS,
                "Content readiness timeout (ms)",
                "Per-page deadline for content readiness (distinct from the navigation commit "
                        + "timeout, the MCP await budget and the unlimited CAPTCHA wait).",
                500, 120_000);
        integer(f, "readiness.navigationCommitTimeoutMillis", SECTION_READINESS,
                "Navigation commit timeout (ms)",
                "Timeout for the navigation itself to commit.", 1_000, 120_000);
        integer(f, "readiness.maximumAwaitCallMillis", SECTION_READINESS,
                "Maximum await call (ms)",
                "Upper bound of a single MCP await call; readiness may span calls.", 500, 60_000);
        integer(f, "readiness.maximumPageReadinessRetries", SECTION_READINESS,
                "Maximum page readiness retries",
                "How many scan→handle→re-scan attempts to make a concrete page readable (a cookie banner "
                        + "can follow a CAPTCHA and vice versa) before leaving it parked. 0 reads immediately.",
                0, 20);
        // --- Mechanical analysis
        list(f, "analysis.noResultsTexts", SECTION_ANALYSIS, "No-results texts",
                "Lower-cased page texts (one per line) that mark an explicitly EMPTY result page "
                        + "(NO_ORGANIC_RESULTS) instead of an extraction failure.");
        integer(f, "analysis.maximumCandidateContainers", SECTION_ANALYSIS,
                "Maximum candidate containers",
                "Upper bound of result-container candidates per page.", 1, 200);
        integer(f, "analysis.minimumContainerTextCharacters", SECTION_ANALYSIS,
                "Minimum container text characters",
                "Containers with less text are never result candidates.", 0, 10_000);
        integer(f, "analysis.minimumNonLinkTextCharacters", SECTION_ANALYSIS,
                "Minimum non-link text characters",
                "Pure link lists are navigation, not results.", 0, 10_000);
        integer(f, "analysis.minimumRepeatedSiblingCount", SECTION_ANALYSIS,
                "Minimum repeated siblings",
                "A result list is a repeated structure; fewer similar siblings do not count.", 1, 50);
        decimal(f, "analysis.minimumResultStructuralConfidence", SECTION_ANALYSIS,
                "Minimum structural confidence",
                "Containers below this confidence are never extraction candidates.", 0, 1);
        decimal(f, "analysis.maximumNavigationLinkDensity", SECTION_ANALYSIS,
                "Maximum navigation link density",
                "Containers whose link-text density exceeds this ratio count as navigation.", 0, 1);
        decimal(f, "analysis.internalLinkWeight", SECTION_ANALYSIS, "Internal link weight",
                "Scoring weight of engine-internal links.", 0, Double.NaN);
        decimal(f, "analysis.externalLinkWeight", SECTION_ANALYSIS, "External link weight",
                "Scoring weight of links leaving the engine.", 0, Double.NaN);
        decimal(f, "analysis.sameHostPenalty", SECTION_ANALYSIS, "Same-host penalty",
                "Penalty for links on the engine's own host.", 0, Double.NaN);
        decimal(f, "analysis.sameRegistrableDomainPenalty", SECTION_ANALYSIS,
                "Same-registrable-domain penalty",
                "Penalty for links within the engine's registrable domain.", 0, Double.NaN);
        decimal(f, "analysis.subdomainPenalty", SECTION_ANALYSIS, "Subdomain penalty",
                "Penalty for links to engine subdomains.", 0, Double.NaN);
        decimal(f, "analysis.unknownDomainPenalty", SECTION_ANALYSIS, "Unknown-domain penalty",
                "Penalty for links whose domain kind cannot be judged.", 0, Double.NaN);
        decimal(f, "analysis.repeatedBlockWeight", SECTION_ANALYSIS, "Repeated block weight",
                "Weight of repeated similar sibling blocks — the strongest result signal.",
                0, Double.NaN);
        decimal(f, "analysis.nonLinkTextWeight", SECTION_ANALYSIS, "Non-link text weight",
                "Weight of explanatory (non-linked) text inside a container.", 0, Double.NaN);
        decimal(f, "analysis.titleLinkWeight", SECTION_ANALYSIS, "Title link weight",
                "Weight of one dominant title link per block.", 0, Double.NaN);
        decimal(f, "analysis.snippetPresenceWeight", SECTION_ANALYSIS, "Snippet presence weight",
                "Weight of an explanatory snippet next to the title link.", 0, Double.NaN);
        decimal(f, "analysis.headingLinkWeight", SECTION_ANALYSIS, "Heading link weight",
                "Weight of links sitting inside a heading element.", 0, Double.NaN);
        decimal(f, "analysis.semanticMainWeight", SECTION_ANALYSIS, "Semantic main weight",
                "Weight of main/role=main DOM semantics.", 0, Double.NaN);
        decimal(f, "analysis.navigationRolePenalty", SECTION_ANALYSIS, "Navigation role penalty",
                "Penalty for nav/role=navigation semantics.", 0, Double.NaN);
        decimal(f, "analysis.resultBlockSimilarityThreshold", SECTION_ANALYSIS,
                "Result block similarity threshold",
                "Similarity two sibling structure signatures need to count as the same block shape.",
                0, 1);
        decimal(f, "analysis.fullPageAreaRatio", SECTION_ANALYSIS, "Full-page area ratio",
                "A container covering at least this ratio of the document gets the full-page "
                        + "penalty.", 0, 1);
        integer(f, "analysis.minimumDiscriminatingSignalFamilies", SECTION_ANALYSIS,
                "Minimum discriminating signal families",
                "If fewer signal families discriminate, the analysis is LOW_CONFIDENCE "
                        + "(→ EXTRACTION_FAILED, next engine).", 1, 6);
        integer(f, "analysis.textLengthSaturationCharacters", SECTION_ANALYSIS,
                "Text length saturation (chars)",
                "Text beyond this length adds no further score.", 1, 100_000);
        integer(f, "analysis.maximumContainerDomDepth", SECTION_ANALYSIS,
                "Maximum container DOM depth", "Capture depth bound.", 4, 200);
        integer(f, "analysis.maximumCapturedContainers", SECTION_ANALYSIS,
                "Maximum captured containers", "Capture container bound.", 16, 10_000);
        integer(f, "analysis.maximumLinksPerContainer", SECTION_ANALYSIS,
                "Maximum links per container", "Capture link bound per container.", 1, 1_000);
        integer(f, "analysis.maximumStructureSignatureDepth", SECTION_ANALYSIS,
                "Maximum structure signature depth",
                "Depth bound of the structural shape signature.", 1, 10);
        integer(f, "analysis.maximumCaptureRetries", SECTION_ANALYSIS,
                "Capture retries after a mid-capture DOM change",
                "0 = capture once and flag the inconsistency; each retry is a full capture "
                        + "(seconds on a big result page).", 0, 10);
        integer(f, "analysis.captureSettleMillis", SECTION_ANALYSIS,
                "Settle wait before a capture retry (ms)", "Only used when retries are allowed.",
                0, 10_000);
        integer(f, "analysis.linkHarvestMinimumStructuredCandidates", SECTION_ANALYSIS,
                "Link harvest below N structured candidates",
                "When the structured extraction yields fewer candidates, the page's external links "
                        + "(title + surrounding excerpt) are harvested as candidates and the "
                        + "reranker judges them. 0 disables the harvest.", 0, 100);
        integer(f, "analysis.linkHarvestMaximumCandidates", SECTION_ANALYSIS,
                "Link harvest maximum candidates",
                "Upper bound of total candidates (structured + harvested).", 1, 200);
        list(f, "analysis.linkHarvestExcludedDomains", SECTION_ANALYSIS,
                "Link harvest excluded domains",
                "Links whose host ends in one of these domains are never harvested: the engine "
                        + "owner's legal/consent/footer pages (Bing links microsoft.com on every "
                        + "result page) are page furniture, not results.");
        // --- Visual analysis
        bool(f, "visual.enabled", SECTION_VISUAL, "Enabled",
                "Screenshot-based container detection (takes effect once the visual stage ships).");
        decimal(f, "visual.backgroundSimilarityThreshold", SECTION_VISUAL,
                "Background similarity threshold",
                "Colors closer than this similarity count as the same background.", 0, 1);
        decimal(f, "visual.minimumDistinctBackgroundDistance", SECTION_VISUAL,
                "Minimum distinct background distance",
                "Minimum color distance for a region to count as visually distinct.", 0, 1);
        decimal(f, "visual.maximumDominantColorCoverage", SECTION_VISUAL,
                "Maximum dominant color coverage",
                "A color covering more than this ratio of the page is THE page background.", 0, 1);
        decimal(f, "visual.minimumVisualRegionAreaRatio", SECTION_VISUAL,
                "Minimum region area ratio",
                "Regions smaller than this ratio of the viewport are noise.", 0, 1);
        decimal(f, "visual.centerProbeXRatio", SECTION_VISUAL, "Center probe X",
                "X of the probe rectangle where the primary result column is expected.", 0, 1);
        decimal(f, "visual.centerProbeYRatio", SECTION_VISUAL, "Center probe Y",
                "Y of the probe rectangle.", 0, 1);
        decimal(f, "visual.centerProbeWidthRatio", SECTION_VISUAL, "Center probe width",
                "Width of the probe rectangle.", 0, 1);
        decimal(f, "visual.centerProbeHeightRatio", SECTION_VISUAL, "Center probe height",
                "Height of the probe rectangle.", 0, 1);
        decimal(f, "visual.centerIntersectionWeight", SECTION_VISUAL, "Center intersection weight",
                "Score weight of intersecting the center probe.", 0, Double.NaN);
        decimal(f, "visual.centerDistanceWeight", SECTION_VISUAL, "Center distance weight",
                "Score weight of the distance to the probe center.", 0, Double.NaN);
        decimal(f, "visual.distinctBackgroundWeight", SECTION_VISUAL, "Distinct background weight",
                "Score weight of a visually distinct background.", 0, Double.NaN);
        decimal(f, "visual.borderSeparationWeight", SECTION_VISUAL, "Border separation weight",
                "Score weight of border separation.", 0, Double.NaN);
        decimal(f, "visual.shadowSeparationWeight", SECTION_VISUAL, "Shadow separation weight",
                "Score weight of shadow separation.", 0, Double.NaN);
        decimal(f, "visual.spacingSeparationWeight", SECTION_VISUAL, "Spacing separation weight",
                "Score weight of spacing separation.", 0, Double.NaN);
        decimal(f, "visual.regionContinuityWeight", SECTION_VISUAL, "Region continuity weight",
                "Score weight of vertical region continuity.", 0, Double.NaN);
        decimal(f, "visual.fullPageContainerPenalty", SECTION_VISUAL, "Full-page container penalty",
                "Penalty for containers covering the whole page.", 0, Double.NaN);
        decimal(f, "visual.edgeRegionPenalty", SECTION_VISUAL, "Edge region penalty",
                "Penalty for regions hugging the viewport edges.", 0, Double.NaN);
        integer(f, "visual.maximumVisualContainers", SECTION_VISUAL, "Maximum visual containers",
                "Upper bound of visually detected containers.", 1, 100);
        // --- Extraction
        integer(f, "extraction.minimumTitleCharacters", SECTION_EXTRACTION,
                "Minimum title characters", "Shorter titles are not results.", 1, 500);
        integer(f, "extraction.minimumSnippetCharacters", SECTION_EXTRACTION,
                "Minimum snippet characters", "Must not exceed the maximum.", 0, 10_000);
        integer(f, "extraction.maximumSnippetCharacters", SECTION_EXTRACTION,
                "Maximum snippet characters", "Snippets are trimmed to this length.", 1, 10_000);
        integer(f, "extraction.maximumExtractedCandidates", SECTION_EXTRACTION,
                "Maximum extracted candidates", "Upper bound before reranking.", 1, 500);
        integer(f, "extraction.maximumSiteLinksPerResult", SECTION_EXTRACTION,
                "Maximum site links per result", "Additional deep links kept per result.", 0, 20);
        decimal(f, "extraction.minimumPrimaryLinkConfidence", SECTION_EXTRACTION,
                "Minimum primary-link confidence",
                "Candidates below this confidence are dropped.", 0, 1);
        decimal(f, "extraction.minimumStructuralConfidenceForReranking", SECTION_EXTRACTION,
                "Minimum structural confidence for reranking",
                "Candidates below this never reach the reranker.", 0, 1);
        // --- AI layout resolver
        bool(f, "aiLayoutResolver.enabled", SECTION_AI_RESOLVER, "Enabled",
                "Ask a model to resolve ambiguous SERP layouts. The model only chooses among "
                        + "mechanically found container ids — unknown ids are always rejected.");
        text(f, "aiLayoutResolver.modelProfileId", SECTION_AI_RESOLVER, "Model profile",
                "The model profile to call; required when enabled.");
        choice(f, "aiLayoutResolver.reasoningEffort", SECTION_AI_RESOLVER, "Reasoning effort",
                "Requested reasoning effort; DEFAULT keeps the profile's own setting.",
                enumNames(ReasoningEffort.values()));
        decimal(f, "aiLayoutResolver.temperature", SECTION_AI_RESOLVER, "Temperature",
                "Sampling temperature (0 = deterministic).", 0, 2);
        integer(f, "aiLayoutResolver.maximumOutputTokens", SECTION_AI_RESOLVER,
                "Maximum output tokens", "Hard output budget per call.", 64, 32_768);
        prompt(f, "aiLayoutResolver.systemPromptTemplate", SECTION_AI_RESOLVER,
                "System prompt", "The productive system prompt (a real value, not a placeholder).");
        prompt(f, "aiLayoutResolver.userPromptTemplate", SECTION_AI_RESOLVER,
                "User prompt", "Must contain the required template variables.",
                "{query}", "{pageUrl}", "{containerDescriptors}");
        // --- Reranker
        bool(f, "reranker.enabled", SECTION_RERANKER, "Enabled",
                "Rerank extracted candidates before they become results.");
        choice(f, "reranker.implementationType", SECTION_RERANKER, "Implementation",
                "How the reranker is implemented.", enumNames(RerankerImplementationType.values()));
        text(f, "reranker.modelProfileId", SECTION_RERANKER, "Model profile",
                "Required for model-backed implementations.");
        choice(f, "reranker.reasoningEffort", SECTION_RERANKER, "Reasoning effort",
                "Requested reasoning effort; DEFAULT keeps the profile's own setting.",
                enumNames(ReasoningEffort.values()));
        integer(f, "reranker.maximumCandidates", SECTION_RERANKER, "Maximum candidates",
                "Upper bound of candidates handed to the reranker (>= selected results).", 1, 500);
        integer(f, "reranker.maximumSelectedResults", SECTION_RERANKER, "Maximum selected results",
                "Upper bound of results the reranker may select.", 1, 100);
        decimal(f, "reranker.structuralScoreWeight", SECTION_RERANKER, "Structural score weight",
                "Weight of the mechanical structure score.", 0, Double.NaN);
        decimal(f, "reranker.semanticScoreWeight", SECTION_RERANKER, "Semantic score weight",
                "Weight of the semantic relevance score.", 0, Double.NaN);
        decimal(f, "reranker.originalRankWeight", SECTION_RERANKER, "Original rank weight",
                "Weight of the engine's original ranking.", 0, Double.NaN);
        prompt(f, "reranker.promptTemplate", SECTION_RERANKER, "Prompt",
                "Must contain the required template variables.",
                "{query}", "{candidates}", "{maximumSelectedResults}");
        // --- Retry policies
        retry(f, "aiLayoutResolver.retry.", "AI layout resolver — ");
        retry(f, "reranker.retry.", "Reranker — ");
        // --- Diagnostics
        bool(f, "diagnostics.enabled", SECTION_DIAGNOSTICS, "Enabled",
                "Record pipeline diagnostics for the Technical Details view.");
        bool(f, "diagnostics.storeContainerDescriptors", SECTION_DIAGNOSTICS,
                "Store container descriptors", "Mechanically found containers per page.");
        bool(f, "diagnostics.storeMechanicalScores", SECTION_DIAGNOSTICS,
                "Store mechanical scores", "Structural scoring per container.");
        bool(f, "diagnostics.storeVisualMetadata", SECTION_DIAGNOSTICS,
                "Store visual metadata", "Visual analysis regions and scores.");
        bool(f, "diagnostics.storePromptMetadata", SECTION_DIAGNOSTICS,
                "Store prompt metadata", "Prompt ids, sizes and variables (never secrets).");
        bool(f, "diagnostics.storeRawModelResponses", SECTION_DIAGNOSTICS,
                "Store raw model responses",
                "Off by default — raw responses may quote page content at length.");
        bool(f, "diagnostics.storeValidationFailures", SECTION_DIAGNOSTICS,
                "Store validation failures", "Concrete model-output validation failures.");
        bool(f, "diagnostics.storeRetryHistory", SECTION_DIAGNOSTICS,
                "Store retry history", "Attempt-by-attempt repair history of AI calls.");
        bool(f, "diagnostics.storeExtractedCandidates", SECTION_DIAGNOSTICS,
                "Store extracted candidates", "Candidates before reranking.");
        bool(f, "diagnostics.storeRerankerScores", SECTION_DIAGNOSTICS,
                "Store reranker scores", "Scores and order after reranking.");
        integer(f, "diagnostics.maximumTextExcerptCharacters", SECTION_DIAGNOSTICS,
                "Maximum text excerpt characters", "Excerpt cap per stored text.", 0, 10_000);
        integer(f, "diagnostics.maximumDiagnosticArtifactBytes", SECTION_DIAGNOSTICS,
                "Maximum diagnostic artifact bytes", "Hard cap per stored artifact.",
                1_024, 10_485_760);
        bool(f, "diagnostics.redactUrls", SECTION_DIAGNOSTICS, "Redact URLs",
                "Replace URLs by their registrable domain in stored diagnostics.");

        integer(f, "layoutRepair.maximumCachedTickets", SECTION_LAYOUT_REPAIR,
                "Maximum cached repair tickets",
                "Bounded per-session low-confidence snapshots held for AI layout repair.", 1, 256);
        integer(f, "layoutRepair.ticketTtlMillis", SECTION_LAYOUT_REPAIR, "Repair ticket TTL (ms)",
                "How long a repair ticket stays applicable before it expires.", 1, 3_600_000);
        return Collections.unmodifiableList(f);
    }

    private static void retry(List<Field> f, String prefix, String labelPrefix) {
        integer(f, prefix + "maximumAttempts", SECTION_RETRY, labelPrefix + "maximum attempts",
                "Hard attempt bound (unbounded AI retries are not allowed).", 1, 10);
        integer(f, prefix + "initialBackoffMillis", SECTION_RETRY, labelPrefix + "initial backoff (ms)",
                "Backoff before the first retry.", 0, 60_000);
        decimal(f, prefix + "backoffMultiplier", SECTION_RETRY, labelPrefix + "backoff multiplier",
                "Multiplier per further retry.", 1, 10);
        integer(f, prefix + "maximumBackoffMillis", SECTION_RETRY, labelPrefix + "maximum backoff (ms)",
                "Backoff ceiling (>= initial backoff).", 0, 300_000);
        bool(f, prefix + "retryOnEmptyResponse", SECTION_RETRY, labelPrefix + "retry on empty response",
                "Repair-retry when the model returns nothing.");
        bool(f, prefix + "retryOnParsingFailure", SECTION_RETRY, labelPrefix + "retry on parsing failure",
                "Repair-retry when the response cannot be parsed.");
        bool(f, prefix + "retryOnSchemaViolation", SECTION_RETRY, labelPrefix + "retry on schema violation",
                "Repair-retry when the response violates the schema.");
        bool(f, prefix + "retryOnUnknownIds", SECTION_RETRY, labelPrefix + "retry on unknown ids",
                "Repair-retry when the model references unknown container/candidate ids.");
        bool(f, prefix + "retryOnSemanticValidationFailure", SECTION_RETRY,
                labelPrefix + "retry on semantic validation failure",
                "Repair-retry on semantic validation failures.");
        bool(f, prefix + "retryOnModelTimeout", SECTION_RETRY, labelPrefix + "retry on model timeout",
                "Retry when the model call times out.");
        bool(f, prefix + "includePreviousResponse", SECTION_RETRY,
                labelPrefix + "include previous response",
                "Hand the model its previous (bad) response on the next attempt — retries never "
                        + "blindly repeat the same prompt.");
        bool(f, prefix + "includeValidationErrors", SECTION_RETRY,
                labelPrefix + "include validation errors",
                "Hand the model the concrete validation errors on the next attempt.");
    }

    private static void bool(List<Field> f, String key, String section, String label, String desc) {
        f.add(new Field(key, section, Kind.BOOLEAN, label, desc, Double.NaN, Double.NaN,
                Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    private static void integer(List<Field> f, String key, String section, String label, String desc,
                                double min, double max) {
        f.add(new Field(key, section, Kind.INTEGER, label, desc, min, max,
                Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    private static void decimal(List<Field> f, String key, String section, String label, String desc,
                                double min, double max) {
        f.add(new Field(key, section, Kind.DECIMAL, label, desc, min, max,
                Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    private static void engineList(List<Field> f, String key, String section, String label,
                                   String description) {
        f.add(new Field(key, section, Kind.ENGINE_LIST, label, description, Double.NaN, Double.NaN,
                java.util.Collections.<String>emptyList(), java.util.Collections.<String>emptyList()));
    }

    private static void text(List<Field> f, String key, String section, String label, String desc) {
        f.add(new Field(key, section, Kind.TEXT, label, desc, Double.NaN, Double.NaN,
                Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    private static void list(List<Field> f, String key, String section, String label, String desc) {
        f.add(new Field(key, section, Kind.TEXT_LIST, label, desc, Double.NaN, Double.NaN,
                Collections.<String>emptyList(), Collections.<String>emptyList()));
    }

    private static void prompt(List<Field> f, String key, String section, String label, String desc,
                               String... variables) {
        f.add(new Field(key, section, Kind.PROMPT, label, desc, Double.NaN, Double.NaN,
                Collections.<String>emptyList(), Arrays.asList(variables)));
    }

    private static void choice(List<Field> f, String key, String section, String label, String desc,
                               List<String> choices) {
        f.add(new Field(key, section, Kind.CHOICE, label, desc, Double.NaN, Double.NaN,
                choices, Collections.<String>emptyList()));
    }

    private static List<String> enumNames(Enum<?>[] values) {
        List<String> names = new ArrayList<String>();
        for (Enum<?> value : values) {
            names.add(value.name());
        }
        return names;
    }
}
