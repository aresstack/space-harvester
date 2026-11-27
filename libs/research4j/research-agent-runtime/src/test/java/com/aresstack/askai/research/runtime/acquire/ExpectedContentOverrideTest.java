package com.aresstack.askai.research.runtime.acquire;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Layer 2 semantic safety net (pure decision): a high SERP-anchored content match rescues an AMBIGUOUS verdict
 * (INTERACTIVE_CHALLENGE / UNREADABLE) to READABLE; it never touches a block/consent/already-readable verdict,
 * and a missing anchor or an unavailable embedder leaves the verdict unchanged.
 */
public class ExpectedContentOverrideTest {

    private static final double T = 0.80;
    private static final PageContentSimilarity HIGH = (e, a) -> 0.95;
    private static final PageContentSimilarity LOW = (e, a) -> 0.20;

    private static PageReadinessJudge.Verdict apply(PageReadinessJudge.Verdict base, String expected,
            String actual, PageContentSimilarity sim) {
        return WebSearchApplicationService.withExpectedContent(base, expected, actual, sim, T);
    }

    @Test
    public void aHighMatchRescuesAnAmbiguousVerdict() {
        assertEquals(PageReadinessJudge.Verdict.READABLE,
                apply(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE, "wearable neuro", "wearable neuro article", HIGH));
        assertEquals(PageReadinessJudge.Verdict.READABLE,
                apply(PageReadinessJudge.Verdict.UNREADABLE, "wearable neuro", "wearable neuro article", HIGH));
    }

    @Test
    public void aLowMatchLeavesTheAmbiguousVerdict() {
        assertEquals(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE,
                apply(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE, "wearable neuro", "verify you are human", LOW));
        assertEquals(PageReadinessJudge.Verdict.UNREADABLE,
                apply(PageReadinessJudge.Verdict.UNREADABLE, "wearable neuro", "tiny", LOW));
    }

    @Test
    public void terminalAndReadableVerdictsAreNeverOverridden() {
        for (PageReadinessJudge.Verdict base : new PageReadinessJudge.Verdict[]{
                PageReadinessJudge.Verdict.ACCESS_BLOCKED, PageReadinessJudge.Verdict.CONSENT_REQUIRED,
                PageReadinessJudge.Verdict.READABLE}) {
            assertEquals(base, apply(base, "wearable neuro", "wearable neuro article", HIGH));
        }
    }

    @Test
    public void noAnchorOrNoEmbedderLeavesTheVerdictUnchanged() {
        assertEquals(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE,
                apply(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE, null, "wearable neuro article", HIGH));
        assertEquals(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE,
                apply(PageReadinessJudge.Verdict.INTERACTIVE_CHALLENGE, "wearable neuro", "text", PageContentSimilarity.NONE));
    }
}
