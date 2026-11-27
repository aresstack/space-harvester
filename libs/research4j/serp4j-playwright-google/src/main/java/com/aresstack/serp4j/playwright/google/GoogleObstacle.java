package com.aresstack.serp4j.playwright.google;

/** What stands between a request and the Google result page. Serp4J reports these, it never bypasses them. */
public enum GoogleObstacle {
    /** Nothing noticed. */
    NONE,
    /** Redirect to or page of {@code consent.google.com} without results. */
    CONSENT,
    /** Google answers without results and demands JavaScript ({@code /httpservice/retry/enablejs}). */
    JAVASCRIPT_REQUIRED,
    /** CAPTCHA or unusual-traffic page ({@code /sorry/}); never solved. */
    CHALLENGE,
    /** Sign-in demanded ({@code accounts.google.com}). */
    LOGIN
}
