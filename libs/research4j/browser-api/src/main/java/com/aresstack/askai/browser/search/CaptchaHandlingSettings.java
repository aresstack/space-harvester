package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * Cooperative MANUAL challenge handling. Hard invariants that are deliberately NOT fields here:
 * a CAPTCHA has no business timeout, never triggers an automatic retry of the same domain family,
 * and is NEVER solved automatically; cancel and shutdown always stay possible. Only the probe
 * cadence and the attention/UX behaviour are configurable.
 */
public final class CaptchaHandlingSettings {

    public final boolean enabled;
    /** DOM markers of a challenge page. */
    public final List<String> challengeSelectors;
    /** Lower-cased visible texts that mark a manual challenge page. */
    public final List<String> challengeTexts;
    /** Cadence of the non-reloading, non-focusing "is it still there" probe. */
    public final int challengeProbeIntervalMillis;
    /** Bring the parked challenge tab to the user's attention exactly once on first detection. */
    public final boolean focusTabOnFirstDetection;
    public final boolean playAttentionSound;
    /** Emit a typed attention event to the research UI on first detection. */
    public final boolean emitAttentionEvent;
    /** Lock the whole registrable-domain family while its challenge is pending. */
    public final boolean blockDomainFamily;
    /** Keep the challenge tab open (parked) for the user instead of closing it. */
    public final boolean retainChallengeTab;
    /**
     * The uniform choice for how the acquisition path reacts to a manual challenge: WAIT for the user to
     * solve it (true, the current behaviour) or SKIP the blocked page and move on (false), leaving its source
     * parked with an empty full text. Applies the same way everywhere (search and concrete-page visits).
     */
    public final boolean waitForUser;

    public CaptchaHandlingSettings(boolean enabled, List<String> challengeSelectors,
                                   List<String> challengeTexts, int challengeProbeIntervalMillis,
                                   boolean focusTabOnFirstDetection, boolean playAttentionSound,
                                   boolean emitAttentionEvent, boolean blockDomainFamily,
                                   boolean retainChallengeTab, boolean waitForUser) {
        this.enabled = enabled;
        this.challengeSelectors = Collections.unmodifiableList(challengeSelectors);
        this.challengeTexts = Collections.unmodifiableList(challengeTexts);
        this.challengeProbeIntervalMillis = challengeProbeIntervalMillis;
        this.focusTabOnFirstDetection = focusTabOnFirstDetection;
        this.playAttentionSound = playAttentionSound;
        this.emitAttentionEvent = emitAttentionEvent;
        this.blockDomainFamily = blockDomainFamily;
        this.retainChallengeTab = retainChallengeTab;
        this.waitForUser = waitForUser;
    }
}
