package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.AiLayoutResolverSettings;
import com.aresstack.askai.browser.search.AiRetryPolicy;
import com.aresstack.askai.browser.search.SearchDiagnosticsSettings;
import com.aresstack.askai.browser.search.SearchResultExtractionSettings;
import com.aresstack.askai.browser.search.inference.InferenceBudgetDecision;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.InferenceBudgetRequest;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.inference.RetryDelayResult;
import com.aresstack.askai.browser.search.inference.StructuredInferencePort;
import com.aresstack.askai.browser.search.inference.StructuredInferenceRequest;
import com.aresstack.askai.browser.search.inference.StructuredInferenceResult;
import com.aresstack.askai.browser.search.inference.StructuredInferenceStatus;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisAttempt;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionDecision;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolver;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutValidationResult;
import com.aresstack.askai.browser.search.layout.ValidatedSearchPageLayoutDecision;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The single production {@link SearchPageLayoutResolver}: it builds the prompt from the bounded
 * artifact, calls the neutral {@link StructuredInferencePort}, parses the answer and — bounded by the
 * {@link AiRetryPolicy} and the cancellation signal — repairs a rejected answer by handing the model
 * its previous response and the concrete problems. It knows NO concrete model library; the adapter is
 * injected. Absent an adapter (or when the port answers UNAVAILABLE) it returns a typed
 * {@code AI_UNAVAILABLE} — never a silent fake success. Every inference (initial and repair) passes
 * a neutral {@link InferenceBudgetGate} first, and repair backoff runs through an injectable
 * {@link RetryDelay} rather than a hardcoded sleep.
 */
public final class AiSearchPageLayoutResolver implements SearchPageLayoutResolver {

    /**
     * The single hard bound on attempts is the A2-validated {@code AiRetryPolicy.maximumAttempts}
     * range (1..10); a valid configuration is never silently reduced below its own maximum.
     */
    static final int MAX_ATTEMPTS_HARD_LIMIT = 10;

    private final StructuredInferencePort port;
    private final SearchPageLayoutPromptFactory promptFactory = new SearchPageLayoutPromptFactory();
    private final SearchPageLayoutDecisionParser parser = new SearchPageLayoutDecisionParser();
    private final SearchPageLayoutDecisionValidator validator;
    private final InferenceBudgetGate budgetGate;
    private final RetryDelay retryDelay;

    public AiSearchPageLayoutResolver(StructuredInferencePort port,
                                      SearchResultExtractionSettings extraction) {
        this(port, extraction, InferenceBudgetGate.ALLOW_ALL, RetryDelay.IMMEDIATE);
    }

    public AiSearchPageLayoutResolver(StructuredInferencePort port,
                                      SearchResultExtractionSettings extraction,
                                      InferenceBudgetGate budgetGate, RetryDelay retryDelay) {
        this.port = port;
        this.validator = new SearchPageLayoutDecisionValidator(extraction);
        this.budgetGate = budgetGate == null ? InferenceBudgetGate.ALLOW_ALL : budgetGate;
        this.retryDelay = retryDelay == null ? RetryDelay.IMMEDIATE : retryDelay;
    }

    public SearchPageLayoutResolverResult resolve(SearchPageLayoutResolutionRequest request) {
        SearchPageAnalysisArtifact artifact = request.artifact;
        AiLayoutResolverSettings ai = request.aiSettings;
        String snapshotId = artifact.snapshotId;

        if (ai == null || !ai.enabled) {
            return result(SearchPageLayoutResolverOutcome.AI_DISABLED, snapshotId, null,
                    Collections.<SearchPageAnalysisAttempt>emptyList(),
                    "AI layout resolver is disabled");
        }
        if (request.cancellationSignal.isCancelled()) {
            return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null,
                    Collections.<SearchPageAnalysisAttempt>emptyList(),
                    "cancelled before first attempt");
        }

        AiRetryPolicy policy = ai.retryPolicy;
        int maxAttempts = clamp(policy.maximumAttempts, 1, MAX_ATTEMPTS_HARD_LIMIT);
        List<SearchPageAnalysisAttempt> attempts = new ArrayList<SearchPageAnalysisAttempt>();
        String previousResponse = "";
        List<String> previousViolations = Collections.emptyList();

        for (int attemptNumber = 1; attemptNumber <= maxAttempts; attemptNumber++) {
            boolean lastAttempt = attemptNumber == maxAttempts;
            if (request.cancellationSignal.isCancelled()) {
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "cancelled before attempt " + attemptNumber);
            }
            if (attemptNumber > 1 && retryDelay.await(backoffMillis(policy, attemptNumber),
                    request.cancellationSignal) == RetryDelayResult.CANCELLED) {
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "cancelled during backoff before attempt " + attemptNumber);
            }

            InferenceBudgetDecision budget = budgetGate.beforeInference(new InferenceBudgetRequest(
                    snapshotId, attemptNumber, attemptNumber > 1, ai.maximumOutputTokens));
            if (budget != InferenceBudgetDecision.ALLOWED) {
                if (budget == InferenceBudgetDecision.CANCELLED) {
                    return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null,
                            attempts, "cancelled by budget gate before attempt " + attemptNumber);
                }
                return result(SearchPageLayoutResolverOutcome.AI_UNAVAILABLE, snapshotId, null,
                        attempts, "inference budget " + budget + " before attempt " + attemptNumber);
            }

            String userPrompt = promptFactory.userPrompt(artifact, ai);
            if (attemptNumber > 1) {
                userPrompt += promptFactory.repairSuffix(artifact, previousResponse,
                        previousViolations, policy.includePreviousResponse,
                        policy.includeValidationErrors);
            }
            StructuredInferenceResult inference = port.execute(new StructuredInferenceRequest(
                    ai.modelProfileId, promptFactory.systemPrompt(ai), userPrompt,
                    ai.maximumOutputTokens, ai.temperature, ai.reasoningEffort, attemptNumber,
                    request.cancellationSignal));

            String rawStored = storedRaw(request.diagnosticsSettings, inference.rawText);

            if (inference.status == StructuredInferenceStatus.CANCELLED) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("inference cancelled"), rawStored));
                return result(SearchPageLayoutResolverOutcome.CANCELLED, snapshotId, null, attempts,
                        "inference cancelled");
            }
            if (inference.status != StructuredInferenceStatus.SUCCESS) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("inference " + inference.status + " " + inference.detail), rawStored));
                if (retryOnStatus(policy, inference.status) && !lastAttempt) {
                    previousResponse = inference.rawText;
                    previousViolations = one("inference " + inference.status);
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.AI_UNAVAILABLE, snapshotId, null,
                        attempts, "inference " + inference.status);
            }

            if (inference.rawText.trim().isEmpty()) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one("empty response"), rawStored));
                if (policy.retryOnEmptyResponse && !lastAttempt) {
                    previousResponse = "";
                    previousViolations = one("empty response");
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                        attempts, "empty response");
            }

            SearchPageLayoutResolutionDecision decision;
            try {
                decision = parser.parse(inference.rawText);
            } catch (MiniJson.JsonParseException parseFailure) {
                attempts.add(attempt(attemptNumber, inference.status, false, false,
                        one(parseFailure.getMessage()), rawStored));
                if (policy.retryOnParsingFailure && !lastAttempt) {
                    previousResponse = inference.rawText;
                    previousViolations = one(parseFailure.getMessage());
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                        attempts, "parse failure: " + parseFailure.getMessage());
            }

            // What the DOM already answers is not asked of the model twice: the direct parent of a
            // named result block becomes its organic region before the decision is judged.
            decision = validator.normalize(decision, artifact);
            SearchPageLayoutValidationResult validation = validator.validate(decision, artifact);
            if (!validation.valid) {
                List<String> violations = validation.messages();
                attempts.add(attempt(attemptNumber, inference.status, true, false, violations,
                        rawStored));
                if (retryOnValidation(policy, validation) && !lastAttempt) {
                    previousResponse = inference.rawText;
                    previousViolations = violations;
                    continue;
                }
                return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                        attempts, "validation failed: " + violations);
            }

            ValidatedSearchPageLayoutDecision validated =
                    validator.toValidatedDecision(decision, artifact);
            attempts.add(attempt(attemptNumber, inference.status, true, true,
                    Collections.<String>emptyList(), rawStored));
            return new SearchPageLayoutResolverResult(SearchPageLayoutResolverOutcome.RESOLVED,
                    snapshotId, decision, validated, attempts, "resolved on attempt " + attemptNumber);
        }

        return result(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, snapshotId, null,
                attempts, "retry budget exhausted");
    }

    /**
     * Whether a rejected decision should be repaired, per the policy's granular flags: unknown-id,
     * schema-shape and semantic-choice violations each have their own switch.
     */
    private static boolean retryOnValidation(AiRetryPolicy policy,
                                             SearchPageLayoutValidationResult validation) {
        if (validation.hasUnknownContainerId() && policy.retryOnUnknownIds) {
            return true;
        }
        if (validation.hasSchemaViolation() && policy.retryOnSchemaViolation) {
            return true;
        }
        return validation.hasSemanticViolation() && policy.retryOnSemanticValidationFailure;
    }

    private static boolean retryOnStatus(AiRetryPolicy policy, StructuredInferenceStatus status) {
        switch (status) {
            case TIMEOUT:
                return policy.retryOnModelTimeout;
            case INVALID_RESPONSE:
                return policy.retryOnParsingFailure;
            case UNAVAILABLE:
            case PROVIDER_FAILURE:
            default:
                return false;
        }
    }

    /** The exponential backoff (capped) for the given retry attempt — waiting is delegated. */
    private static long backoffMillis(AiRetryPolicy policy, int attemptNumber) {
        long backoff = (long) (policy.initialBackoffMillis
                * Math.pow(policy.backoffMultiplier, attemptNumber - 2));
        return Math.max(0, Math.min(backoff, policy.maximumBackoffMillis));
    }

    private String storedRaw(SearchDiagnosticsSettings diagnostics, String rawText) {
        if (diagnostics == null || !diagnostics.storeRawModelResponses) {
            return "";
        }
        int cap = Math.max(0, diagnostics.maximumTextExcerptCharacters);
        return rawText.length() <= cap ? rawText : rawText.substring(0, cap);
    }

    private static SearchPageAnalysisAttempt attempt(int number, StructuredInferenceStatus status,
                                                     boolean parsed, boolean accepted,
                                                     List<String> violations, String rawResponse) {
        return new SearchPageAnalysisAttempt(number, status, parsed, accepted, violations,
                rawResponse);
    }

    private static SearchPageLayoutResolverResult result(SearchPageLayoutResolverOutcome outcome,
                                                         String snapshotId,
                                                         SearchPageLayoutResolutionDecision decision,
                                                         List<SearchPageAnalysisAttempt> attempts,
                                                         String diagnostic) {
        return new SearchPageLayoutResolverResult(outcome, snapshotId, decision, null, attempts,
                diagnostic);
    }

    private static List<String> one(String value) {
        List<String> list = new ArrayList<String>(1);
        list.add(value);
        return list;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
