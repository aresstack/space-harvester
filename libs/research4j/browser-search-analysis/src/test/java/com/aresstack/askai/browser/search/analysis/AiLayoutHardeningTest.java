package com.aresstack.askai.browser.search.analysis;

import com.aresstack.askai.browser.search.AiRetryPolicy;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.browser.search.inference.InferenceBudgetDecision;
import com.aresstack.askai.browser.search.inference.InferenceBudgetGate;
import com.aresstack.askai.browser.search.inference.InferenceBudgetRequest;
import com.aresstack.askai.browser.search.inference.RetryDelay;
import com.aresstack.askai.browser.search.inference.RetryDelayResult;
import com.aresstack.askai.browser.search.layout.SearchPageAnalysisArtifact;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolutionRequest;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverOutcome;
import com.aresstack.askai.browser.search.layout.SearchPageLayoutResolverResult;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Hardening: the A2-validated 1..10 attempt range is honoured (a configured 8 is never silently
 * reduced), every inference passes the neutral budget gate first, and repair backoff runs through an
 * injectable RetryDelay instead of a hardcoded sleep.
 */
public class AiLayoutHardeningTest {

    private final LegacyBrowserSearchSettings defaults = LegacyBrowserSearchDefaults.create();

    private SearchPageAnalysisArtifact artifact() {
        return LayoutTestSupport.artifactOf("snap-1-test",
                LayoutTestSupport.candidate("container-root", ""),
                LayoutTestSupport.candidate("container-col", "container-root"));
    }

    private SearchPageLayoutResolutionRequest request(SearchPageAnalysisArtifact artifact,
                                                      int maxAttempts) {
        return new SearchPageLayoutResolutionRequest(artifact,
                LayoutTestSupport.aiSettings(true, "p", LayoutTestSupport.retryPolicy(maxAttempts)),
                defaults.diagnostics, CancellationSignal.NONE);
    }

    @Test
    public void configuredAttemptsUpToTenAreHonoured() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort();
        for (int i = 0; i < 8; i++) {
            port.thenSuccess("nope"); // never parses → keeps retrying
        }
        SearchPageLayoutResolverResult result =
                new AiSearchPageLayoutResolver(port, defaults.extraction).resolve(request(artifact, 8));

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertEquals("8 configured attempts must not be capped at 6", 8, port.callCount());
    }

    @Test
    public void budgetGateBlocksBeforeAnyModelCall() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort().thenSuccess("{}");
        InferenceBudgetGate exhausted = new InferenceBudgetGate() {
            public InferenceBudgetDecision beforeInference(InferenceBudgetRequest request) {
                return InferenceBudgetDecision.BUDGET_EXHAUSTED;
            }
        };
        SearchPageLayoutResolverResult result = new AiSearchPageLayoutResolver(port,
                defaults.extraction, exhausted, RetryDelay.IMMEDIATE).resolve(request(artifact, 3));

        assertEquals(SearchPageLayoutResolverOutcome.AI_UNAVAILABLE, result.outcome);
        assertEquals("budget exhaustion must prevent the model call", 0, port.callCount());
        assertTrue(result.diagnostic.contains("BUDGET_EXHAUSTED"));
    }

    @Test
    public void budgetGateCancellationIsTyped() {
        SearchPageAnalysisArtifact artifact = artifact();
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort().thenSuccess("{}");
        InferenceBudgetGate cancelled = new InferenceBudgetGate() {
            public InferenceBudgetDecision beforeInference(InferenceBudgetRequest request) {
                return InferenceBudgetDecision.CANCELLED;
            }
        };
        SearchPageLayoutResolverResult result = new AiSearchPageLayoutResolver(port,
                defaults.extraction, cancelled, RetryDelay.IMMEDIATE).resolve(request(artifact, 3));

        assertEquals(SearchPageLayoutResolverOutcome.CANCELLED, result.outcome);
        assertEquals(0, port.callCount());
    }

    @Test
    public void repairBackoffRunsThroughTheInjectedDelayNotThreadSleep() {
        SearchPageAnalysisArtifact artifact = artifact();
        // A real (non-zero) backoff policy — the injected delay must intercept it, not Thread.sleep.
        AiRetryPolicy realBackoff = new AiRetryPolicy(3, 5_000, 2.0, 30_000, true, true, true, true,
                true, true, true, true);
        SearchPageLayoutResolutionRequest request = new SearchPageLayoutResolutionRequest(artifact,
                LayoutTestSupport.aiSettings(true, "p", realBackoff), defaults.diagnostics,
                CancellationSignal.NONE);
        ScriptedStructuredInferencePort port = new ScriptedStructuredInferencePort()
                .thenSuccess("nope").thenSuccess("nope").thenSuccess("nope");
        final AtomicInteger waits = new AtomicInteger();
        RetryDelay recording = new RetryDelay() {
            public RetryDelayResult await(long delayMillis, CancellationSignal cancellationSignal) {
                waits.incrementAndGet();
                assertTrue("a real backoff must be requested", delayMillis > 0);
                return RetryDelayResult.COMPLETED; // no actual sleeping in the test
            }
        };
        long start = System.nanoTime();
        SearchPageLayoutResolverResult result = new AiSearchPageLayoutResolver(port,
                defaults.extraction, InferenceBudgetGate.ALLOW_ALL, recording).resolve(request);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(SearchPageLayoutResolverOutcome.VALIDATION_FAILED, result.outcome);
        assertEquals("one backoff wait per retry (attempts 2 and 3)", 2, waits.get());
        assertTrue("must not have actually slept the 5s+ backoff", elapsedMillis < 2_000);
    }
}
