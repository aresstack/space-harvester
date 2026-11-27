package com.aresstack.askai.research.runtime.acquire;

import com.aresstack.askai.browser.BrowserPageReadiness;

/**
 * Decides, from a page's readability {@link BrowserPageReadiness probe}, whether the concrete page can be read
 * now or what is in the way. This is the seam the two-step visit ({@code WebSearchApplicationService
 * .openWithReadiness}) consults instead of a hard-coded rule, so the deterministic
 * {@link HeuristicPageReadinessJudge} can be swapped for a model-backed {@link ModelPageReadinessJudge} that
 * recognises, for example, a "verify you are human" wall the DOM selectors did not match.
 */
public interface PageReadinessJudge {

    enum Verdict {
        /** Real content — read it. Acceptance may happen ONLY for this verdict. */
        READABLE,
        /** A cookie/consent wall blocks the content — dismiss it (auto first, then ask the user). */
        CONSENT_REQUIRED,
        /** A SOLVABLE human-verification / challenge page — wait for the user (or skip when configured). */
        INTERACTIVE_CHALLENGE,
        /**
         * A TERMINAL access block (e.g. Cloudflare "Access denied / Error 1020", geo/IP block): nothing to
         * click or solve — never wait for the user, never accept; skip and mark the domain blocked for the run.
         */
        ACCESS_BLOCKED,
        /** Empty / error / paywalled / otherwise not usable — leave it parked. */
        UNREADABLE
    }

    Verdict judge(BrowserPageReadiness probe);
}
