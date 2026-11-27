package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

/**
 * The deterministic readiness verdict: trust the DOM signals (challenge / consent) first, then treat a page
 * with at least {@code minReadableChars} of body text as readable, everything else as unreadable. Cheap and
 * model-free; misses obstructions the SERP-tuned selectors do not match (that is what
 * {@link ModelPageReadinessJudge} adds).
 */
public final class HeuristicPageReadinessJudge implements PageReadinessJudge {

    private final int minReadableChars;

    public HeuristicPageReadinessJudge(int minReadableChars) {
        this.minReadableChars = minReadableChars;
    }

    @Override
    public Verdict judge(BrowserPageReadiness probe) {
        // A TERMINAL block wins over any challenge/consent DOM flag: a Cloudflare 1020 page often also trips a
        // challenge marker, but there is nothing to solve — it must NOT wait for the user and must NOT be read.
        if (AccessBlockSignals.isBlocked(probe)) {
            return Verdict.ACCESS_BLOCKED;
        }
        // A challenge only blocks when it is actually VISIBLE: a present-but-hidden artifact (e.g. a contact-form
        // recaptcha on an otherwise readable article — the reactree false positive) must NOT force a user-wait.
        if (probe.challengeVisible) {
            return Verdict.INTERACTIVE_CHALLENGE;
        }
        if (probe.consentPresent) {
            return Verdict.CONSENT_REQUIRED;
        }
        // An error page ("404 - Seite nicht gefunden", "temporarily unavailable") has navigation text and a
        // sorry note, often more than the threshold: its words decide, never its length.
        if (ErrorPageSignals.isErrorPage(probe)) {
            return Verdict.UNREADABLE;
        }
        return probe.textLength >= minReadableChars ? Verdict.READABLE : Verdict.UNREADABLE;
    }
}
