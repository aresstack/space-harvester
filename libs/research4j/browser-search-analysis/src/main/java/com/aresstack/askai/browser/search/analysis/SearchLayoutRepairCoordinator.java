package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutProfileStore;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairRequest;
import com.aresstack.askai.browser.search.repair.SearchLayoutRepairSubmission;

import java.util.ArrayList;
import java.util.List;

/**
 * The MODEL-USING research-runtime half of the repair bridge. Given a bounded
 * {@link SearchLayoutRepairRequest} from the model-free sidecar, it FIRST tries a stored layout
 * profile (no model call), and only otherwise drives the {@link StructuredInferencePort} through the
 * validating/repairing resolver — behind the neutral {@link InferenceBudgetGate} and injectable
 * {@link RetryDelay}. A success yields a {@link SearchLayoutRepairSubmission} the runtime sends back to
 * {@code web_search_apply_layout}; on AI-disabled/unavailable/validation-failure it gives up so the
 * runtime tries the next repair request or engine. It never touches a document — it reasons only over
 * the artifact — and it never fabricates candidates.
 */
public final class SearchLayoutRepairCoordinator {

    private final LegacyBrowserSearchSettings settings;
    private final AiSearchPageLayoutResolver resolver;
    private final SearchPageLayoutProfileService profileService;
    private final SearchPageLayoutProfileStore profileStore;

    public SearchLayoutRepairCoordinator(LegacyBrowserSearchSettings settings,
                                         StructuredInferencePort port, InferenceBudgetGate budgetGate,
                                         RetryDelay retryDelay,
                                         SearchPageLayoutProfileStore profileStore) {
        this.settings = settings;
        this.resolver = new AiSearchPageLayoutResolver(port, settings.extraction, budgetGate,
                retryDelay);
        this.profileService = new SearchPageLayoutProfileService(settings.extraction);
        this.profileStore = profileStore;
    }

    public SearchLayoutRepairCoordination coordinate(SearchLayoutRepairRequest request,
                                                     CancellationSignal cancellationSignal,
                                                     long nowEpochMillis) {
        SearchPageAnalysisArtifact artifact = request.artifact;
        List<String> diagnostics = new ArrayList<String>();

        if (profileStore != null) {
            ValidatedSearchPageLayoutDecision fromProfile =
                    profileService.resolveFromProfiles(artifact, profileStore, nowEpochMillis);
            if (fromProfile != null) {
                diagnostics.add("layout profile hit — resolved without a model call");
                return SearchLayoutRepairCoordination.submit(submission(request, fromProfile), true,
                        null, diagnostics);
            }
            diagnostics.add("no compatible layout profile");
        }

        SearchPageLayoutResolverResult ai = resolver.resolve(new SearchPageLayoutResolutionRequest(
                artifact, settings.aiLayoutResolver, settings.diagnostics, cancellationSignal));
        diagnostics.add("AI layout resolver: " + ai.outcome + " after " + ai.attempts.size()
                + " attempt(s)");
        // D2 diagnostics: every non-accepted attempt names its CONCRETE violations (kind + message,
        // already human-readable) — the raw response only ever appears when the attempt carries one,
        // which the resolver gates behind storeRawModelResponses. Pure diagnostics, no behaviour.
        for (SearchPageAnalysisAttempt attempt : ai.attempts) {
            if (attempt.accepted && attempt.violations.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder("AI attempt ").append(attempt.attemptNumber)
                    .append(": inference=").append(attempt.inferenceStatus)
                    .append(" parsed=").append(attempt.parsed)
                    .append(" accepted=").append(attempt.accepted);
            if (!attempt.violations.isEmpty()) {
                line.append(" violations=").append(attempt.violations);
            }
            if (!attempt.rawResponse.isEmpty()) {
                line.append(" raw=").append(attempt.rawResponse);
            }
            diagnostics.add(line.toString());
        }
        if (!ai.isResolved()) {
            return SearchLayoutRepairCoordination.giveUp(ai, diagnostics);
        }
        if (profileStore != null) {
            profileStore.saveValidated(
                    profileService.buildProfile(artifact, ai.validatedDecision, nowEpochMillis));
            diagnostics.add("stored validated layout as a structural profile");
        }
        return SearchLayoutRepairCoordination.submit(submission(request, ai.validatedDecision), false,
                ai, diagnostics);
    }

    private static SearchLayoutRepairSubmission submission(SearchLayoutRepairRequest request,
                                                           ValidatedSearchPageLayoutDecision decision) {
        // Every binding value comes from the TRUSTED request context, never from the model.
        String analysisId = request.artifact == null ? "" : request.artifact.analysisId;
        String settingsDigest = request.artifact == null ? "" : request.artifact.settingsDigest;
        return new SearchLayoutRepairSubmission(request.attemptId, analysisId, request.snapshotId,
                request.snapshotGeneration, request.documentFingerprint,
                request.layoutStructureFingerprint, settingsDigest, decision);
    }
}
