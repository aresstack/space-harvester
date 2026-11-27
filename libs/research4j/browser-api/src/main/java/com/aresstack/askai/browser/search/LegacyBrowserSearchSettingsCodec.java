package com.aresstack.askai.browser.search;

import java.io.UnsupportedEncodingException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Canonical flat key→value form of {@link LegacyBrowserSearchSettings} — ONE encoding for
 * persistence, the sidecar hand-off payload, the profile snapshot and the settings digest.
 * Lists are joined with {@code \n} (selectors and prompts never contain newlines per entry).
 *
 * <p>Decoding never invents values: a missing key falls back to {@link LegacyBrowserSearchDefaults}
 * (the single default origin), a MALFORMED value is reported as a violation (and the default used so
 * the object exists) — callers MUST check {@link Decoded#violations} and treat them as errors, never
 * as silent corrections.</p>
 */
public final class LegacyBrowserSearchSettingsCodec {

    private LegacyBrowserSearchSettingsCodec() {
    }

    /** Result of decoding: the settings plus every malformed-value violation encountered. */
    public static final class Decoded {
        public final LegacyBrowserSearchSettings settings;
        public final List<SettingsValidationResult.Violation> violations;

        Decoded(LegacyBrowserSearchSettings settings,
                List<SettingsValidationResult.Violation> violations) {
            this.settings = settings;
            this.violations = violations;
        }
    }

    public static Map<String, String> toValues(LegacyBrowserSearchSettings s) {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("navigation.engines", s.navigation.engineSelection.encodeEntries());
        m.put("navigation.maximumEngineAttempts", String.valueOf(s.navigation.maximumEngineAttempts));
        m.put("navigation.navigationCommitTimeoutMillis",
                String.valueOf(s.navigation.navigationCommitTimeoutMillis));
        m.put("navigation.engineAcquisitionMode", s.navigation.engineSelection.getMode().name());
        m.put("navigation.redirectResolutionEnabled",
                String.valueOf(s.navigation.redirectResolutionEnabled));
        m.put("navigation.maximumRedirectUrlLength",
                String.valueOf(s.navigation.maximumRedirectUrlLength));
        m.put("navigation.searchResultLimit", String.valueOf(s.navigation.searchResultLimit));
        m.put("navigation.minimumUsableResults", String.valueOf(s.navigation.minimumUsableResults));
        m.put("navigation.language", s.navigation.language);
        m.put("navigation.country", s.navigation.country);

        m.put("consent.enabled", String.valueOf(s.consent.enabled));
        m.put("consent.positiveButtonSelectors", joinList(s.consent.positiveButtonSelectors));
        m.put("consent.positiveButtonTexts", joinList(s.consent.positiveButtonTexts));
        m.put("consent.maximumDismissAttempts", String.valueOf(s.consent.maximumDismissAttempts));
        m.put("consent.detectionPollIntervalMillis",
                String.valueOf(s.consent.detectionPollIntervalMillis));
        m.put("consent.detectionWindowMillis", String.valueOf(s.consent.detectionWindowMillis));
        m.put("consent.postClickSettleMillis", String.valueOf(s.consent.postClickSettleMillis));
        m.put("consent.inspectFrames", String.valueOf(s.consent.inspectFrames));
        m.put("consent.focusBeforeClick", String.valueOf(s.consent.focusBeforeClick));

        m.put("captcha.enabled", String.valueOf(s.captcha.enabled));
        m.put("captcha.challengeSelectors", joinList(s.captcha.challengeSelectors));
        m.put("captcha.challengeTexts", joinList(s.captcha.challengeTexts));
        m.put("captcha.challengeProbeIntervalMillis",
                String.valueOf(s.captcha.challengeProbeIntervalMillis));
        m.put("captcha.focusTabOnFirstDetection",
                String.valueOf(s.captcha.focusTabOnFirstDetection));
        m.put("captcha.playAttentionSound", String.valueOf(s.captcha.playAttentionSound));
        m.put("captcha.emitAttentionEvent", String.valueOf(s.captcha.emitAttentionEvent));
        m.put("captcha.blockDomainFamily", String.valueOf(s.captcha.blockDomainFamily));
        m.put("captcha.retainChallengeTab", String.valueOf(s.captcha.retainChallengeTab));
        m.put("captcha.waitForUser", String.valueOf(s.captcha.waitForUser));

        m.put("readiness.pollIntervalMillis", String.valueOf(s.readiness.pollIntervalMillis));
        m.put("readiness.settlePollCount", String.valueOf(s.readiness.settlePollCount));
        m.put("readiness.minimumReadableCharacters",
                String.valueOf(s.readiness.minimumReadableCharacters));
        m.put("readiness.contentReadinessTimeoutMillis",
                String.valueOf(s.readiness.contentReadinessTimeoutMillis));
        m.put("readiness.navigationCommitTimeoutMillis",
                String.valueOf(s.readiness.navigationCommitTimeoutMillis));
        m.put("readiness.maximumAwaitCallMillis",
                String.valueOf(s.readiness.maximumAwaitCallMillis));
        m.put("readiness.maximumPageReadinessRetries",
                String.valueOf(s.readiness.maximumPageReadinessRetries));

        m.put("analysis.noResultsTexts", joinList(s.analysis.noResultsTexts));
        m.put("analysis.maximumCandidateContainers",
                String.valueOf(s.analysis.maximumCandidateContainers));
        m.put("analysis.minimumContainerTextCharacters",
                String.valueOf(s.analysis.minimumContainerTextCharacters));
        m.put("analysis.minimumNonLinkTextCharacters",
                String.valueOf(s.analysis.minimumNonLinkTextCharacters));
        m.put("analysis.minimumRepeatedSiblingCount",
                String.valueOf(s.analysis.minimumRepeatedSiblingCount));
        m.put("analysis.minimumResultStructuralConfidence",
                String.valueOf(s.analysis.minimumResultStructuralConfidence));
        m.put("analysis.maximumNavigationLinkDensity",
                String.valueOf(s.analysis.maximumNavigationLinkDensity));
        m.put("analysis.internalLinkWeight", String.valueOf(s.analysis.internalLinkWeight));
        m.put("analysis.externalLinkWeight", String.valueOf(s.analysis.externalLinkWeight));
        m.put("analysis.sameHostPenalty", String.valueOf(s.analysis.sameHostPenalty));
        m.put("analysis.sameRegistrableDomainPenalty",
                String.valueOf(s.analysis.sameRegistrableDomainPenalty));
        m.put("analysis.subdomainPenalty", String.valueOf(s.analysis.subdomainPenalty));
        m.put("analysis.unknownDomainPenalty", String.valueOf(s.analysis.unknownDomainPenalty));
        m.put("analysis.repeatedBlockWeight", String.valueOf(s.analysis.repeatedBlockWeight));
        m.put("analysis.nonLinkTextWeight", String.valueOf(s.analysis.nonLinkTextWeight));
        m.put("analysis.titleLinkWeight", String.valueOf(s.analysis.titleLinkWeight));
        m.put("analysis.snippetPresenceWeight", String.valueOf(s.analysis.snippetPresenceWeight));
        m.put("analysis.headingLinkWeight", String.valueOf(s.analysis.headingLinkWeight));
        m.put("analysis.semanticMainWeight", String.valueOf(s.analysis.semanticMainWeight));
        m.put("analysis.navigationRolePenalty", String.valueOf(s.analysis.navigationRolePenalty));
        m.put("analysis.resultBlockSimilarityThreshold",
                String.valueOf(s.analysis.resultBlockSimilarityThreshold));
        m.put("analysis.minimumDiscriminatingSignalFamilies",
                String.valueOf(s.analysis.minimumDiscriminatingSignalFamilies));
        m.put("analysis.fullPageAreaRatio", String.valueOf(s.analysis.fullPageAreaRatio));
        m.put("analysis.textLengthSaturationCharacters",
                String.valueOf(s.analysis.textLengthSaturationCharacters));
        m.put("analysis.maximumContainerDomDepth",
                String.valueOf(s.analysis.maximumContainerDomDepth));
        m.put("analysis.maximumCapturedContainers",
                String.valueOf(s.analysis.maximumCapturedContainers));
        m.put("analysis.maximumLinksPerContainer",
                String.valueOf(s.analysis.maximumLinksPerContainer));
        m.put("analysis.maximumStructureSignatureDepth",
                String.valueOf(s.analysis.maximumStructureSignatureDepth));
        m.put("analysis.maximumCaptureRetries", String.valueOf(s.analysis.maximumCaptureRetries));
        m.put("analysis.captureSettleMillis", String.valueOf(s.analysis.captureSettleMillis));
        m.put("analysis.linkHarvestMinimumStructuredCandidates",
                String.valueOf(s.analysis.linkHarvestMinimumStructuredCandidates));
        m.put("analysis.linkHarvestMaximumCandidates",
                String.valueOf(s.analysis.linkHarvestMaximumCandidates));
        m.put("analysis.linkHarvestExcludedDomains",
                joinList(s.analysis.linkHarvestExcludedDomains));

        m.put("visual.enabled", String.valueOf(s.visualAnalysis.enabled));
        m.put("visual.backgroundSimilarityThreshold",
                String.valueOf(s.visualAnalysis.backgroundSimilarityThreshold));
        m.put("visual.minimumDistinctBackgroundDistance",
                String.valueOf(s.visualAnalysis.minimumDistinctBackgroundDistance));
        m.put("visual.maximumDominantColorCoverage",
                String.valueOf(s.visualAnalysis.maximumDominantColorCoverage));
        m.put("visual.minimumVisualRegionAreaRatio",
                String.valueOf(s.visualAnalysis.minimumVisualRegionAreaRatio));
        m.put("visual.centerProbeXRatio", String.valueOf(s.visualAnalysis.centerProbeXRatio));
        m.put("visual.centerProbeYRatio", String.valueOf(s.visualAnalysis.centerProbeYRatio));
        m.put("visual.centerProbeWidthRatio",
                String.valueOf(s.visualAnalysis.centerProbeWidthRatio));
        m.put("visual.centerProbeHeightRatio",
                String.valueOf(s.visualAnalysis.centerProbeHeightRatio));
        m.put("visual.centerIntersectionWeight",
                String.valueOf(s.visualAnalysis.centerIntersectionWeight));
        m.put("visual.centerDistanceWeight", String.valueOf(s.visualAnalysis.centerDistanceWeight));
        m.put("visual.distinctBackgroundWeight",
                String.valueOf(s.visualAnalysis.distinctBackgroundWeight));
        m.put("visual.borderSeparationWeight",
                String.valueOf(s.visualAnalysis.borderSeparationWeight));
        m.put("visual.shadowSeparationWeight",
                String.valueOf(s.visualAnalysis.shadowSeparationWeight));
        m.put("visual.spacingSeparationWeight",
                String.valueOf(s.visualAnalysis.spacingSeparationWeight));
        m.put("visual.regionContinuityWeight",
                String.valueOf(s.visualAnalysis.regionContinuityWeight));
        m.put("visual.fullPageContainerPenalty",
                String.valueOf(s.visualAnalysis.fullPageContainerPenalty));
        m.put("visual.edgeRegionPenalty", String.valueOf(s.visualAnalysis.edgeRegionPenalty));
        m.put("visual.maximumVisualContainers",
                String.valueOf(s.visualAnalysis.maximumVisualContainers));

        m.put("extraction.minimumTitleCharacters",
                String.valueOf(s.extraction.minimumTitleCharacters));
        m.put("extraction.minimumSnippetCharacters",
                String.valueOf(s.extraction.minimumSnippetCharacters));
        m.put("extraction.maximumSnippetCharacters",
                String.valueOf(s.extraction.maximumSnippetCharacters));
        m.put("extraction.maximumExtractedCandidates",
                String.valueOf(s.extraction.maximumExtractedCandidates));
        m.put("extraction.maximumSiteLinksPerResult",
                String.valueOf(s.extraction.maximumSiteLinksPerResult));
        m.put("extraction.minimumPrimaryLinkConfidence",
                String.valueOf(s.extraction.minimumPrimaryLinkConfidence));
        m.put("extraction.minimumStructuralConfidenceForReranking",
                String.valueOf(s.extraction.minimumStructuralConfidenceForReranking));

        m.put("aiLayoutResolver.enabled", String.valueOf(s.aiLayoutResolver.enabled));
        m.put("aiLayoutResolver.modelProfileId", s.aiLayoutResolver.modelProfileId);
        m.put("aiLayoutResolver.reasoningEffort", s.aiLayoutResolver.reasoningEffort.name());
        m.put("aiLayoutResolver.temperature", String.valueOf(s.aiLayoutResolver.temperature));
        m.put("aiLayoutResolver.maximumOutputTokens",
                String.valueOf(s.aiLayoutResolver.maximumOutputTokens));
        m.put("aiLayoutResolver.systemPromptTemplate", s.aiLayoutResolver.systemPromptTemplate);
        m.put("aiLayoutResolver.userPromptTemplate", s.aiLayoutResolver.userPromptTemplate);
        putRetry(m, "aiLayoutResolver.retry.", s.aiLayoutResolver.retryPolicy);

        m.put("reranker.enabled", String.valueOf(s.reranker.enabled));
        m.put("reranker.implementationType", s.reranker.implementationType.name());
        m.put("reranker.modelProfileId", s.reranker.modelProfileId);
        m.put("reranker.reasoningEffort", s.reranker.reasoningEffort.name());
        m.put("reranker.maximumCandidates", String.valueOf(s.reranker.maximumCandidates));
        m.put("reranker.maximumSelectedResults",
                String.valueOf(s.reranker.maximumSelectedResults));
        m.put("reranker.structuralScoreWeight", String.valueOf(s.reranker.structuralScoreWeight));
        m.put("reranker.semanticScoreWeight", String.valueOf(s.reranker.semanticScoreWeight));
        m.put("reranker.originalRankWeight", String.valueOf(s.reranker.originalRankWeight));
        m.put("reranker.promptTemplate", s.reranker.promptTemplate);
        putRetry(m, "reranker.retry.", s.reranker.retryPolicy);

        m.put("diagnostics.enabled", String.valueOf(s.diagnostics.enabled));
        m.put("diagnostics.storeContainerDescriptors",
                String.valueOf(s.diagnostics.storeContainerDescriptors));
        m.put("diagnostics.storeMechanicalScores",
                String.valueOf(s.diagnostics.storeMechanicalScores));
        m.put("diagnostics.storeVisualMetadata",
                String.valueOf(s.diagnostics.storeVisualMetadata));
        m.put("diagnostics.storePromptMetadata",
                String.valueOf(s.diagnostics.storePromptMetadata));
        m.put("diagnostics.storeRawModelResponses",
                String.valueOf(s.diagnostics.storeRawModelResponses));
        m.put("diagnostics.storeValidationFailures",
                String.valueOf(s.diagnostics.storeValidationFailures));
        m.put("diagnostics.storeRetryHistory", String.valueOf(s.diagnostics.storeRetryHistory));
        m.put("diagnostics.storeExtractedCandidates",
                String.valueOf(s.diagnostics.storeExtractedCandidates));
        m.put("diagnostics.storeRerankerScores",
                String.valueOf(s.diagnostics.storeRerankerScores));
        m.put("diagnostics.maximumTextExcerptCharacters",
                String.valueOf(s.diagnostics.maximumTextExcerptCharacters));
        m.put("diagnostics.maximumDiagnosticArtifactBytes",
                String.valueOf(s.diagnostics.maximumDiagnosticArtifactBytes));
        m.put("diagnostics.redactUrls", String.valueOf(s.diagnostics.redactUrls));

        m.put("layoutRepair.maximumCachedTickets",
                String.valueOf(s.layoutRepair.maximumCachedTickets));
        m.put("layoutRepair.ticketTtlMillis", String.valueOf(s.layoutRepair.ticketTtlMillis));
        return m;
    }

    /**
     * The challenge-text list as it shipped until the bare word "captcha" was removed (the only list ever
     * shipped). A stored value EXACTLY equal to it is the untouched shipped default, not a user choice.
     */
    static final List<String> SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA = java.util.Collections.unmodifiableList(
            Arrays.asList("noch ein letzter schritt", "one last step", "verify you are human",
                    "unusual traffic", "complete the challenge", "captcha",
                    "bestätigen sie, dass sie ein mensch sind"));

    public static Decoded fromValues(Map<String, String> values) {
        Reader r = new Reader(liftStaleShippedDefaults(values), toValues(LegacyBrowserSearchDefaults.create()));
        LegacyBrowserSearchSettings settings = new LegacyBrowserSearchSettings(
                new LegacySearchNavigationSettings(
                        new com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection(
                                com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.parseEntries(
                                        r.string("navigation.engines")),
                                (com.aresstack.askai.browser.search.engine.EngineAcquisitionMode) r.enumValue(
                                        "navigation.engineAcquisitionMode",
                                        com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.class)),
                        r.intValue("navigation.maximumEngineAttempts"),
                        r.intValue("navigation.navigationCommitTimeoutMillis"),
                        r.boolValue("navigation.redirectResolutionEnabled"),
                        r.intValue("navigation.maximumRedirectUrlLength"),
                        r.intValue("navigation.searchResultLimit"),
                        r.intValue("navigation.minimumUsableResults"),
                        r.string("navigation.language"),
                        r.string("navigation.country")),
                new ConsentHandlingSettings(
                        r.boolValue("consent.enabled"),
                        r.list("consent.positiveButtonSelectors"),
                        r.list("consent.positiveButtonTexts"),
                        r.intValue("consent.maximumDismissAttempts"),
                        r.intValue("consent.detectionPollIntervalMillis"),
                        r.intValue("consent.detectionWindowMillis"),
                        r.intValue("consent.postClickSettleMillis"),
                        r.boolValue("consent.inspectFrames"),
                        r.boolValue("consent.focusBeforeClick")),
                new CaptchaHandlingSettings(
                        r.boolValue("captcha.enabled"),
                        r.list("captcha.challengeSelectors"),
                        r.list("captcha.challengeTexts"),
                        r.intValue("captcha.challengeProbeIntervalMillis"),
                        r.boolValue("captcha.focusTabOnFirstDetection"),
                        r.boolValue("captcha.playAttentionSound"),
                        r.boolValue("captcha.emitAttentionEvent"),
                        r.boolValue("captcha.blockDomainFamily"),
                        r.boolValue("captcha.retainChallengeTab"),
                        r.boolValue("captcha.waitForUser")),
                new SearchPageReadinessSettings(
                        r.intValue("readiness.pollIntervalMillis"),
                        r.intValue("readiness.settlePollCount"),
                        r.intValue("readiness.minimumReadableCharacters"),
                        r.intValue("readiness.contentReadinessTimeoutMillis"),
                        r.intValue("readiness.navigationCommitTimeoutMillis"),
                        r.intValue("readiness.maximumAwaitCallMillis"),
                        r.intValue("readiness.maximumPageReadinessRetries")),
                new SearchPageAnalysisSettings(
                        r.list("analysis.noResultsTexts"),
                        r.intValue("analysis.maximumCandidateContainers"),
                        r.intValue("analysis.minimumContainerTextCharacters"),
                        r.intValue("analysis.minimumNonLinkTextCharacters"),
                        r.intValue("analysis.minimumRepeatedSiblingCount"),
                        r.doubleValue("analysis.minimumResultStructuralConfidence"),
                        r.doubleValue("analysis.maximumNavigationLinkDensity"),
                        r.doubleValue("analysis.internalLinkWeight"),
                        r.doubleValue("analysis.externalLinkWeight"),
                        r.doubleValue("analysis.sameHostPenalty"),
                        r.doubleValue("analysis.sameRegistrableDomainPenalty"),
                        r.doubleValue("analysis.subdomainPenalty"),
                        r.doubleValue("analysis.unknownDomainPenalty"),
                        r.doubleValue("analysis.repeatedBlockWeight"),
                        r.doubleValue("analysis.nonLinkTextWeight"),
                        r.doubleValue("analysis.titleLinkWeight"),
                        r.doubleValue("analysis.snippetPresenceWeight"),
                        r.doubleValue("analysis.headingLinkWeight"),
                        r.doubleValue("analysis.semanticMainWeight"),
                        r.doubleValue("analysis.navigationRolePenalty"),
                        r.doubleValue("analysis.resultBlockSimilarityThreshold"),
                        r.intValue("analysis.minimumDiscriminatingSignalFamilies"),
                        r.doubleValue("analysis.fullPageAreaRatio"),
                        r.intValue("analysis.textLengthSaturationCharacters"),
                        r.intValue("analysis.maximumContainerDomDepth"),
                        r.intValue("analysis.maximumCapturedContainers"),
                        r.intValue("analysis.maximumLinksPerContainer"),
                        r.intValue("analysis.maximumStructureSignatureDepth"),
                        r.intValue("analysis.maximumCaptureRetries"),
                        r.intValue("analysis.captureSettleMillis"),
                        r.intValue("analysis.linkHarvestMinimumStructuredCandidates"),
                        r.intValue("analysis.linkHarvestMaximumCandidates"),
                        r.list("analysis.linkHarvestExcludedDomains")),
                new SearchPageVisualAnalysisSettings(
                        r.boolValue("visual.enabled"),
                        r.doubleValue("visual.backgroundSimilarityThreshold"),
                        r.doubleValue("visual.minimumDistinctBackgroundDistance"),
                        r.doubleValue("visual.maximumDominantColorCoverage"),
                        r.doubleValue("visual.minimumVisualRegionAreaRatio"),
                        r.doubleValue("visual.centerProbeXRatio"),
                        r.doubleValue("visual.centerProbeYRatio"),
                        r.doubleValue("visual.centerProbeWidthRatio"),
                        r.doubleValue("visual.centerProbeHeightRatio"),
                        r.doubleValue("visual.centerIntersectionWeight"),
                        r.doubleValue("visual.centerDistanceWeight"),
                        r.doubleValue("visual.distinctBackgroundWeight"),
                        r.doubleValue("visual.borderSeparationWeight"),
                        r.doubleValue("visual.shadowSeparationWeight"),
                        r.doubleValue("visual.spacingSeparationWeight"),
                        r.doubleValue("visual.regionContinuityWeight"),
                        r.doubleValue("visual.fullPageContainerPenalty"),
                        r.doubleValue("visual.edgeRegionPenalty"),
                        r.intValue("visual.maximumVisualContainers")),
                new SearchResultExtractionSettings(
                        r.intValue("extraction.minimumTitleCharacters"),
                        r.intValue("extraction.minimumSnippetCharacters"),
                        r.intValue("extraction.maximumSnippetCharacters"),
                        r.intValue("extraction.maximumExtractedCandidates"),
                        r.intValue("extraction.maximumSiteLinksPerResult"),
                        r.doubleValue("extraction.minimumPrimaryLinkConfidence"),
                        r.doubleValue("extraction.minimumStructuralConfidenceForReranking")),
                new AiLayoutResolverSettings(
                        r.boolValue("aiLayoutResolver.enabled"),
                        r.string("aiLayoutResolver.modelProfileId"),
                        (ReasoningEffort) r.enumValue("aiLayoutResolver.reasoningEffort",
                                ReasoningEffort.class),
                        r.doubleValue("aiLayoutResolver.temperature"),
                        r.intValue("aiLayoutResolver.maximumOutputTokens"),
                        r.string("aiLayoutResolver.systemPromptTemplate"),
                        r.string("aiLayoutResolver.userPromptTemplate"),
                        readRetry(r, "aiLayoutResolver.retry.")),
                new SearchResultRerankerSettings(
                        r.boolValue("reranker.enabled"),
                        (RerankerImplementationType) r.enumValue("reranker.implementationType",
                                RerankerImplementationType.class),
                        r.string("reranker.modelProfileId"),
                        (ReasoningEffort) r.enumValue("reranker.reasoningEffort",
                                ReasoningEffort.class),
                        r.intValue("reranker.maximumCandidates"),
                        r.intValue("reranker.maximumSelectedResults"),
                        r.doubleValue("reranker.structuralScoreWeight"),
                        r.doubleValue("reranker.semanticScoreWeight"),
                        r.doubleValue("reranker.originalRankWeight"),
                        r.string("reranker.promptTemplate"),
                        readRetry(r, "reranker.retry.")),
                new SearchDiagnosticsSettings(
                        r.boolValue("diagnostics.enabled"),
                        r.boolValue("diagnostics.storeContainerDescriptors"),
                        r.boolValue("diagnostics.storeMechanicalScores"),
                        r.boolValue("diagnostics.storeVisualMetadata"),
                        r.boolValue("diagnostics.storePromptMetadata"),
                        r.boolValue("diagnostics.storeRawModelResponses"),
                        r.boolValue("diagnostics.storeValidationFailures"),
                        r.boolValue("diagnostics.storeRetryHistory"),
                        r.boolValue("diagnostics.storeExtractedCandidates"),
                        r.boolValue("diagnostics.storeRerankerScores"),
                        r.intValue("diagnostics.maximumTextExcerptCharacters"),
                        r.intValue("diagnostics.maximumDiagnosticArtifactBytes"),
                        r.boolValue("diagnostics.redactUrls")),
                new SearchLayoutRepairSettings(
                        r.intValue("layoutRepair.maximumCachedTickets"),
                        r.longValue("layoutRepair.ticketTtlMillis")));
        return new Decoded(settings, r.violations);
    }

    /**
     * Lift values that are an UNTOUCHED old shipped default to the current default — never a heuristic: only an
     * exact match of a list that once shipped is replaced; any edited list (an added, removed or reordered entry)
     * is the user's choice and stays exactly as stored. Today this covers {@code captcha.challengeTexts}: the old
     * list carried the bare word "captcha", which parked readable pages naming a CAPTCHA widget.
     */
    static Map<String, String> liftStaleShippedDefaults(Map<String, String> values) {
        values = migrateEngineList(values);
        String stored = values.get("captcha.challengeTexts");
        if (stored == null || !SHIPPED_CHALLENGE_TEXTS_WITH_BARE_CAPTCHA.equals(entries(stored))) {
            return values;
        }
        Map<String, String> lifted = new LinkedHashMap<String, String>(values);
        lifted.put("captcha.challengeTexts", joinList(LegacyBrowserSearchDefaults.create().captcha.challengeTexts));
        return lifted;
    }

    /**
     * The stored engine list in the current table form (priorities, languages): a list from before priorities is
     * migrated with the stored search strategy so it searches exactly as before
     * ({@link com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection#migrate}). Editors read the
     * values through this too, so the table shows what the search will do.
     */
    public static Map<String, String> migrateEngineList(Map<String, String> values) {
        String engines = values == null ? null : values.get("navigation.engines");
        if (engines == null) {
            return values;
        }
        com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode =
                com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.FIRST_USABLE;
        String storedMode = values.get("navigation.engineAcquisitionMode");
        if (storedMode != null && "ALL_ENABLED".equals(storedMode.trim())) {
            mode = com.aresstack.askai.browser.search.engine.EngineAcquisitionMode.ALL_ENABLED;
        }
        String migrated = com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection.migrate(engines, mode);
        if (migrated.equals(engines)) {
            return values;
        }
        Map<String, String> copy = new LinkedHashMap<String, String>(values);
        copy.put("navigation.engines", migrated);
        return copy;
    }

    private static List<String> entries(String raw) {
        List<String> entries = new ArrayList<String>();
        for (String entry : raw.split("\n")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }

    /** SHA-256 over the canonical (sorted) key=value form — stable across JVMs and map orders. */
    public static String digest(LegacyBrowserSearchSettings settings) {
        TreeMap<String, String> sorted = new TreeMap<String, String>(toValues(settings));
        StringBuilder canonical = new StringBuilder();
        for (Map.Entry<String, String> entry : sorted.entrySet()) {
            canonical.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
        }
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(canonical.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        } catch (UnsupportedEncodingException ex) {
            throw new IllegalStateException("UTF-8 unavailable", ex);
        }
    }

    private static void putRetry(Map<String, String> m, String prefix, AiRetryPolicy p) {
        m.put(prefix + "maximumAttempts", String.valueOf(p.maximumAttempts));
        m.put(prefix + "initialBackoffMillis", String.valueOf(p.initialBackoffMillis));
        m.put(prefix + "backoffMultiplier", String.valueOf(p.backoffMultiplier));
        m.put(prefix + "maximumBackoffMillis", String.valueOf(p.maximumBackoffMillis));
        m.put(prefix + "retryOnEmptyResponse", String.valueOf(p.retryOnEmptyResponse));
        m.put(prefix + "retryOnParsingFailure", String.valueOf(p.retryOnParsingFailure));
        m.put(prefix + "retryOnSchemaViolation", String.valueOf(p.retryOnSchemaViolation));
        m.put(prefix + "retryOnUnknownIds", String.valueOf(p.retryOnUnknownIds));
        m.put(prefix + "retryOnSemanticValidationFailure",
                String.valueOf(p.retryOnSemanticValidationFailure));
        m.put(prefix + "retryOnModelTimeout", String.valueOf(p.retryOnModelTimeout));
        m.put(prefix + "includePreviousResponse", String.valueOf(p.includePreviousResponse));
        m.put(prefix + "includeValidationErrors", String.valueOf(p.includeValidationErrors));
    }

    private static AiRetryPolicy readRetry(Reader r, String prefix) {
        return new AiRetryPolicy(
                r.intValue(prefix + "maximumAttempts"),
                r.intValue(prefix + "initialBackoffMillis"),
                r.doubleValue(prefix + "backoffMultiplier"),
                r.intValue(prefix + "maximumBackoffMillis"),
                r.boolValue(prefix + "retryOnEmptyResponse"),
                r.boolValue(prefix + "retryOnParsingFailure"),
                r.boolValue(prefix + "retryOnSchemaViolation"),
                r.boolValue(prefix + "retryOnUnknownIds"),
                r.boolValue(prefix + "retryOnSemanticValidationFailure"),
                r.boolValue(prefix + "retryOnModelTimeout"),
                r.boolValue(prefix + "includePreviousResponse"),
                r.boolValue(prefix + "includeValidationErrors"));
    }

    private static String joinList(List<String> values) {
        StringBuilder sb = new StringBuilder();
        for (String value : values) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(value);
        }
        return sb.toString();
    }

    /** Typed access to the flat map; malformed values are RECORDED, defaults keep the object usable. */
    private static final class Reader {
        final Map<String, String> values;
        final Map<String, String> defaults;
        final List<SettingsValidationResult.Violation> violations =
                new ArrayList<SettingsValidationResult.Violation>();

        Reader(Map<String, String> values, Map<String, String> defaults) {
            this.values = values;
            this.defaults = defaults;
        }

        String string(String key) {
            String value = values.get(key);
            return value != null ? value : defaultFor(key);
        }

        List<String> list(String key) {
            return entries(string(key));
        }

        int intValue(String key) {
            String raw = string(key);
            try {
                return Integer.parseInt(raw.trim());
            } catch (NumberFormatException ex) {
                violations.add(new SettingsValidationResult.Violation(key,
                        "not a whole number: '" + raw + "'"));
                return Integer.parseInt(defaultFor(key));
            }
        }

        long longValue(String key) {
            String raw = string(key);
            try {
                return Long.parseLong(raw.trim());
            } catch (NumberFormatException ex) {
                violations.add(new SettingsValidationResult.Violation(key,
                        "not a whole number: '" + raw + "'"));
                return Long.parseLong(defaultFor(key));
            }
        }

        double doubleValue(String key) {
            String raw = string(key);
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException ex) {
                violations.add(new SettingsValidationResult.Violation(key,
                        "not a number: '" + raw + "'"));
                return Double.parseDouble(defaultFor(key));
            }
        }

        boolean boolValue(String key) {
            String raw = string(key).trim().toLowerCase(Locale.ROOT);
            if ("true".equals(raw) || "false".equals(raw)) {
                return "true".equals(raw);
            }
            violations.add(new SettingsValidationResult.Violation(key,
                    "not true/false: '" + raw + "'"));
            return Boolean.parseBoolean(defaultFor(key));
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        Enum enumValue(String key, Class enumType) {
            String raw = string(key).trim();
            try {
                return Enum.valueOf(enumType, raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                violations.add(new SettingsValidationResult.Violation(key,
                        "unknown value '" + raw + "', expected one of "
                                + Arrays.toString(enumType.getEnumConstants())));
                return Enum.valueOf(enumType, defaultFor(key));
            }
        }

        private String defaultFor(String key) {
            String value = defaults.get(key);
            if (value == null) {
                throw new IllegalArgumentException("Unknown setting key: " + key);
            }
            return value;
        }
    }
}
