package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.render.RenderedPageDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.SearchPageAnalysisOutcome;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.SearchResultExtractionResult;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisDiagnosticArtifact;
import com.aresstack.askai.browser.search.repair.PreparedWebSearchResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairAttemptId;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairResult;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairStatus;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;
import com.aresstack.askai.browser.search.repair.WebSearchPreparationStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The MODEL-FREE sidecar core of the repair bridge (A4 productive). It never calls a model: it
 * captures nothing itself, but given a rendered page it either extracts organic candidates
 * mechanically (HIGH_CONFIDENCE) or, for a LOW_CONFIDENCE layout, emits a bounded
 * {@link SearchLayoutRepairRequest} and holds the {@code RenderedPageDocument} in a bounded, one-shot
 * {@link SearchLayoutRepairCache}. Later it applies a VALIDATED decision the runtime returns — after
 * re-checking the attempt, snapshot, fingerprint and container ids — through the single A3/A4
 * extraction. Time is injected so the sidecar owns the clock.
 */
public final class WebSearchLayoutRepairService {

    private final LegacyBrowserSearchSettings settings;
    private final SearchPageMechanicalAnalyzer analyzer;
    private final LegacySearchResultExtractor extractor;
    private final SearchPageAnalysisArtifactBuilder artifactBuilder;
    private final SearchPageAnalysisDiagnosticArtifactBuilder diagnosticBuilder;
    private final SearchLayoutRepairCache cache;

    public WebSearchLayoutRepairService(LegacyBrowserSearchSettings settings, int maximumAttempts,
                                        long ttlMillis) {
        this.settings = settings;
        this.analyzer = new SearchPageMechanicalAnalyzer(settings);
        this.extractor = new LegacySearchResultExtractor(settings); // model-free: no resolver
        this.artifactBuilder = new SearchPageAnalysisArtifactBuilder(settings);
        this.diagnosticBuilder = new SearchPageAnalysisDiagnosticArtifactBuilder(settings.diagnostics);
        this.cache = new SearchLayoutRepairCache(maximumAttempts, ttlMillis);
    }

    public SearchLayoutRepairCache cache() {
        return cache;
    }

    /**
     * Prepare one captured engine page. A layout that was READ yields its organic candidates
     * directly; an explicit no-results page yields NO_ORGANIC_RESULTS; anything the mechanics could
     * not read — an ununderstood layout OR an understood one that produced no result block — yields a
     * single bounded repair request and caches the document for later application.
     */
    public PreparedWebSearchResult prepareSingle(RenderedPageDocument document, String query,
                                                 String engineHost, long nowEpochMillis) {
        SearchPageLayoutResolution resolution = analyzer.analyze(document);
        SearchResultExtractionResult extraction = extractor.extract(document, query);

        // Confident about the layout AND able to read it: nothing to repair.
        // Confident about the layout and STILL unable to read a single result block is not an answer,
        // it is a contradiction — the mechanics believed a region and then found nothing in it. That is
        // exactly what the repair is for: a second opinion on WHICH region holds the results. Treating
        // it as terminal meant a page whose results a human could read ended the search technically.
        if (!resolution.lowConfidence
                && extraction.outcome != SearchPageAnalysisOutcome.EXTRACTION_FAILED) {
            return prepared(statusOf(extraction.outcome), attributed(extraction.candidates, engineHost),
                    Collections.<SearchLayoutRepairRequest>emptyList(), extraction.diagnostics);
        }
        if (extraction.outcome == SearchPageAnalysisOutcome.NO_ORGANIC_RESULTS) {
            return prepared(WebSearchPreparationStatus.NO_ORGANIC_RESULTS,
                    Collections.<SearchResultCandidate>emptyList(),
                    Collections.<SearchLayoutRepairRequest>emptyList(), extraction.diagnostics);
        }

        SearchPageAnalysisArtifact artifact = artifactBuilder.build(document, resolution, query);
        SearchPageAnalysisDiagnosticArtifact diagnostics =
                diagnosticBuilder.build(artifact, null, "NONE", "REPAIR_REQUIRED");
        String attemptId = "repair-" + document.snapshotId;
        String layoutFingerprint = layoutStructureFingerprint(artifact);
        SearchLayoutRepairCache.Entry entry = cache.put(attemptId, document, query, engineHost,
                artifact.analysisId, layoutFingerprint, artifact.settingsDigest, nowEpochMillis);
        SearchLayoutRepairRequest request = new SearchLayoutRepairRequest(
                new SearchLayoutRepairAttemptId(attemptId), query, engineHost, artifact.engineFamily,
                document.snapshotId, document.snapshotGeneration,
                document.documentFingerprint == null ? "" : document.documentFingerprint.value,
                layoutFingerprint, artifact, diagnostics, nowEpochMillis, entry.expiresAtEpochMillis);
        List<SearchLayoutRepairRequest> requests = Collections.singletonList(request);
        // The link harvest of this page travels along as the FALLBACK: if every repair fails, the
        // page's external links are still better than ending a search whose results a human could
        // read as "technically failed". The repair keeps priority — it yields real result blocks.
        return prepared(WebSearchPreparationStatus.REPAIR_REQUIRED,
                attributed(extraction.candidates, engineHost), requests, extraction.diagnostics);
    }

    /** Attribute every hit of one page to the engine it came from — provenance, not decoration. */
    private static List<SearchResultCandidate> attributed(List<SearchResultCandidate> candidates,
                                                          String engineHost) {
        if (engineHost == null || engineHost.isEmpty()) {
            return candidates;
        }
        List<SearchResultCandidate> attributed = new ArrayList<SearchResultCandidate>();
        for (SearchResultCandidate candidate : candidates) {
            attributed.add(candidate.attributedTo(engineHost));
        }
        return attributed;
    }

    /** A per-page prepared result — navigation metadata (hosts/attempts/challenges) is added later. */
    private static PreparedWebSearchResult prepared(WebSearchPreparationStatus status,
                                                    List<SearchResultCandidate> candidates,
                                                    List<SearchLayoutRepairRequest> requests,
                                                    List<String> diagnostics) {
        return new PreparedWebSearchResult(status, candidates, requests,
                Collections.<String>emptyList(),
                Collections.<com.aresstack.askai.browser.LegacySearchEngineAttemptResult>emptyList(),
                Collections.<com.aresstack.askai.browser.search.repair.SearchChallengeState>emptyList(),
                diagnostics);
    }

    /**
     * Apply a runtime-validated decision to the cached snapshot. Every guard is re-checked in the
     * sidecar; only then is the EXISTING extraction run. The attempt is consumed exactly once, the
     * moment the extraction is actually attempted.
     */
    public SearchLayoutRepairResult apply(SearchLayoutRepairSubmission submission,
                                          long nowEpochMillis) {
        String attemptId = submission.attemptId.value;
        SearchLayoutRepairCache.Entry entry = cache.find(attemptId);
        if (entry == null) {
            return rejected(SearchLayoutRepairStatus.UNKNOWN_ATTEMPT, "unknown attempt " + attemptId);
        }
        if (cache.isConsumed(entry)) {
            return rejected(SearchLayoutRepairStatus.ALREADY_CONSUMED,
                    "attempt already consumed " + attemptId);
        }
        if (cache.isExpired(entry, nowEpochMillis)) {
            cache.discard(attemptId);
            return rejected(SearchLayoutRepairStatus.EXPIRED_ATTEMPT, "attempt expired " + attemptId);
        }
        if (!entry.document.snapshotId.equals(submission.snapshotId)) {
            return rejected(SearchLayoutRepairStatus.SNAPSHOT_MISMATCH,
                    "snapshot mismatch for " + attemptId);
        }
        String cachedFingerprint = entry.document.documentFingerprint == null
                ? "" : entry.document.documentFingerprint.value;
        if (!cachedFingerprint.equals(submission.documentFingerprint)) {
            return rejected(SearchLayoutRepairStatus.FINGERPRINT_MISMATCH,
                    "fingerprint mismatch for " + attemptId);
        }
        if (!entry.layoutStructureFingerprint.equals(submission.layoutStructureFingerprint)
                || !entry.analysisId.equals(submission.analysisId)
                || !entry.settingsDigest.equals(submission.settingsDigest)
                || entry.document.snapshotGeneration != submission.snapshotGeneration) {
            return rejected(SearchLayoutRepairStatus.INVALID_DECISION,
                    "binding mismatch (analysis/generation/settings/structure) for " + attemptId);
        }
        if (submission.decision == null
                || !entry.document.snapshotId.equals(submission.decision.snapshotId)
                || submission.decision.primaryOrganicContainerId.isEmpty()
                || entry.document.container(submission.decision.primaryOrganicContainerId) == null) {
            return rejected(SearchLayoutRepairStatus.INVALID_DECISION,
                    "invalid or non-resolving decision for " + attemptId);
        }

        SearchResultExtractionResult extraction =
                extractor.extract(entry.document, submission.decision);
        cache.consume(attemptId); // one-shot: consumed whether or not blocks emerged
        SearchLayoutRepairStatus status =
                extraction.outcome == SearchPageAnalysisOutcome.ORGANIC_RESULTS
                        ? SearchLayoutRepairStatus.ORGANIC_RESULTS
                        : SearchLayoutRepairStatus.EXTRACTION_FAILED;
        // A repaired hit is as much "from this engine" as a mechanically extracted one.
        return new SearchLayoutRepairResult(status,
                attributed(extraction.candidates, entry.engineHost), extraction.diagnostics);
    }

    public void discard(SearchLayoutRepairAttemptId attemptId) {
        cache.discard(attemptId.value);
    }

    /** Cleanup on session close / browser recovery. */
    public void clear() {
        cache.clear();
    }

    /**
     * Merge per-engine prepared results in engine order: a direct organic hit wins; otherwise all
     * repair requests are offered in order; otherwise an explicit empty is reported; else FAILED.
     */
    public static PreparedWebSearchResult merge(List<PreparedWebSearchResult> perEngine) {
        return merge(perEngine, com.aresstack.askai.browser.search.engine.EngineAcquisitionMode
                .FIRST_USABLE);
    }

    /**
     * Fold the per-page results into one, the way the user asked for.
     * <p>
     * FIRST_USABLE takes the first ENGINE that delivered — the ones behind it were the safety net.
     * One engine may have delivered SEVERAL result pages (the per-engine page count is a user
     * setting); they are all that engine's answer and are unioned, deduplicated by target URL.
     * ALL_ENABLED takes the UNION of everything that delivered, deduplicated by target URL, keeping
     * each hit's engine provenance: that is the whole point of asking several engines.
     */
    public static PreparedWebSearchResult merge(List<PreparedWebSearchResult> perEngine,
            com.aresstack.askai.browser.search.engine.EngineAcquisitionMode mode) {
        boolean union = mode == com.aresstack.askai.browser.search.engine.EngineAcquisitionMode
                .ALL_ENABLED;
        List<SearchLayoutRepairRequest> repairs = new ArrayList<SearchLayoutRepairRequest>();
        List<String> diagnostics = new ArrayList<String>();
        List<SearchResultCandidate> merged = new ArrayList<SearchResultCandidate>();
        java.util.Set<String> seenTargets = new java.util.LinkedHashSet<String>();
        List<SearchResultCandidate> fallback = new ArrayList<SearchResultCandidate>();
        java.util.Set<String> seenFallbackTargets = new java.util.LinkedHashSet<String>();
        boolean sawNoResults = false;
        String firstDeliveringHost = null;
        for (PreparedWebSearchResult result : perEngine) {
            if (result == null) {
                continue;
            }
            diagnostics.addAll(result.diagnostics);
            if (result.status == WebSearchPreparationStatus.ORGANIC_RESULTS) {
                if (!union) {
                    // FIRST_USABLE: pages of the FIRST delivering engine are unioned; a page from a
                    // different engine means the first one already won.
                    String host = deliveringHostOf(result);
                    if (firstDeliveringHost == null) {
                        firstDeliveringHost = host;
                    } else if (!firstDeliveringHost.equals(host)) {
                        break;
                    }
                }
                for (SearchResultCandidate candidate : result.candidates) {
                    if (seenTargets.add(candidate.navigationUrl())) {
                        merged.add(candidate);
                    }
                }
                continue;
            }
            repairs.addAll(result.repairRequests);
            if (result.status == WebSearchPreparationStatus.REPAIR_REQUIRED) {
                for (SearchResultCandidate candidate : result.candidates) {
                    if (seenFallbackTargets.add(candidate.resolvedTargetUrl)) {
                        fallback.add(candidate);
                    }
                }
            }
            if (result.status == WebSearchPreparationStatus.NO_ORGANIC_RESULTS) {
                sawNoResults = true;
            }
        }
        if (!union && firstDeliveringHost != null) {
            return prepared(WebSearchPreparationStatus.ORGANIC_RESULTS, merged,
                    Collections.<SearchLayoutRepairRequest>emptyList(), diagnostics);
        }
        if (union && !merged.isEmpty()) {
            // Engines that still need a repair do not hold the union back: their tickets travel along
            // and the runtime may add their hits later.
            return prepared(WebSearchPreparationStatus.ORGANIC_RESULTS, merged, repairs, diagnostics);
        }
        if (!repairs.isEmpty()) {
            // REPAIR_REQUIRED candidates are the harvested fallback, used only if every repair fails.
            return prepared(WebSearchPreparationStatus.REPAIR_REQUIRED, fallback, repairs,
                    diagnostics);
        }
        WebSearchPreparationStatus status = sawNoResults
                ? WebSearchPreparationStatus.NO_ORGANIC_RESULTS : WebSearchPreparationStatus.FAILED;
        return prepared(status, Collections.<SearchResultCandidate>emptyList(),
                Collections.<SearchLayoutRepairRequest>emptyList(), diagnostics);
    }

    /** The engine host a delivered page's candidates carry (pages of one engine share it). */
    private static String deliveringHostOf(PreparedWebSearchResult result) {
        String host = result.candidates.isEmpty() ? null : result.candidates.get(0).engineHost;
        return host == null ? "" : host;
    }

    /**
     * A stable, STRUCTURE-only fingerprint of the artifact's candidates (structure + ancestry
     * signatures, order-independent) — binds a repair ticket to the layout shape without any
     * snapshot-local id. Recomputing it on the same cached snapshot always matches.
     */
    public static String layoutStructureFingerprint(SearchPageAnalysisArtifact artifact) {
        java.util.List<String> parts = new ArrayList<String>();
        for (com.aresstack.askai.browser.search.layout.SearchPageContainerCandidate candidate
                : artifact.containerCandidates) {
            parts.add(candidate.structureSignature + "|" + candidate.ancestrySignature);
        }
        java.util.Collections.sort(parts);
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(part).append('\n');
        }
        return sha256Hex(sb.toString());
    }

    private static String sha256Hex(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                int v = b & 0xff;
                if (v < 0x10) {
                    hex.append('0');
                }
                hex.append(Integer.toHexString(v));
            }
            return hex.substring(0, 16);
        } catch (java.security.NoSuchAlgorithmException e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private static WebSearchPreparationStatus statusOf(SearchPageAnalysisOutcome outcome) {
        switch (outcome) {
            case ORGANIC_RESULTS:
                return WebSearchPreparationStatus.ORGANIC_RESULTS;
            case NO_ORGANIC_RESULTS:
                return WebSearchPreparationStatus.NO_ORGANIC_RESULTS;
            case EXTRACTION_FAILED:
            default:
                return WebSearchPreparationStatus.FAILED;
        }
    }

    private static SearchLayoutRepairResult rejected(SearchLayoutRepairStatus status, String reason) {
        return new SearchLayoutRepairResult(status,
                Collections.<SearchResultCandidate>emptyList(), Collections.singletonList(reason));
    }
}
