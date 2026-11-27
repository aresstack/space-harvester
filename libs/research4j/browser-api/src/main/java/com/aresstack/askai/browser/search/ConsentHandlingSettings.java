package com.aresstack.askai.browser.search;

import java.util.Collections;
import java.util.List;

/**
 * Consent (cookie banner) dismissal on SERP pages. Only UNAMBIGUOUSLY positive controls are ever
 * clickable: known CMP accept buttons or visible buttons whose text is a positive consent phrase.
 * There is NO "first button in the container" heuristic and none can be configured into existence.
 */
public final class ConsentHandlingSettings {

    public final boolean enabled;
    /** CSS selectors of known-positive accept buttons (OneTrust, Cookiebot, Quantcast, Usercentrics, …). */
    public final List<String> positiveButtonSelectors;
    /** Lower-cased visible button texts that are an unambiguous consent. */
    public final List<String> positiveButtonTexts;
    /** Maximum dismiss attempts per SERP read. */
    public final int maximumDismissAttempts;
    /** Poll cadence while watching for a late-appearing banner. */
    public final int detectionPollIntervalMillis;
    /** How long to keep watching for a late banner after page load; 0 = single immediate check. */
    public final int detectionWindowMillis;
    /** Settle time after a successful click before the page is re-read (banner animation). */
    public final int postClickSettleMillis;
    /** Also inspect same-origin iframes for consent controls. */
    public final boolean inspectFrames;
    /** Focus the element before clicking (some CMPs ignore unfocused clicks). */
    public final boolean focusBeforeClick;

    public ConsentHandlingSettings(boolean enabled, List<String> positiveButtonSelectors,
                                   List<String> positiveButtonTexts, int maximumDismissAttempts,
                                   int detectionPollIntervalMillis, int detectionWindowMillis,
                                   int postClickSettleMillis, boolean inspectFrames,
                                   boolean focusBeforeClick) {
        this.enabled = enabled;
        this.positiveButtonSelectors = Collections.unmodifiableList(positiveButtonSelectors);
        this.positiveButtonTexts = Collections.unmodifiableList(positiveButtonTexts);
        this.maximumDismissAttempts = maximumDismissAttempts;
        this.detectionPollIntervalMillis = detectionPollIntervalMillis;
        this.detectionWindowMillis = detectionWindowMillis;
        this.postClickSettleMillis = postClickSettleMillis;
        this.inspectFrames = inspectFrames;
        this.focusBeforeClick = focusBeforeClick;
    }
}
