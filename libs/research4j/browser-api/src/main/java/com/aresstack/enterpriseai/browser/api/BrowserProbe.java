package com.aresstack.enterpriseai.browser.api;

/**
 * What stands between the model and the content of a page: a cookie or consent banner (with the labels of its
 * buttons) and a challenge only the user can solve in the visible browser. {@code challenge} is empty, {@code captcha}
 * (bot check, Cloudflare and similar) or {@code login} (a password field: the model never fills passwords).
 * {@code formErrors} are the error messages a form shows right now (invalid fields, alerts), separated by
 * {@code " | "}, so the model sees why a submit did not go through.
 */
public final class BrowserProbe {

    public static final String CAPTCHA = "captcha";
    public static final String LOGIN = "login";

    private final boolean consent;
    private final String consentOptions;
    private final String challenge;
    private final String formErrors;

    public BrowserProbe(boolean consent, String consentOptions, String challenge) {
        this(consent, consentOptions, challenge, "");
    }

    public BrowserProbe(boolean consent, String consentOptions, String challenge, String formErrors) {
        this.consent = consent;
        this.consentOptions = consentOptions == null ? "" : consentOptions;
        this.challenge = challenge == null ? "" : challenge;
        this.formErrors = formErrors == null ? "" : formErrors;
    }

    public static BrowserProbe clear() {
        return new BrowserProbe(false, "", "");
    }

    public boolean consent() {
        return consent;
    }

    /** The button labels of the consent banner, separated by {@code " | "}. */
    public String consentOptions() {
        return consentOptions;
    }

    public String challenge() {
        return challenge;
    }

    /** The error messages forms on the page show, separated by {@code " | "}, else empty. */
    public String formErrors() {
        return formErrors;
    }

    public boolean hasChallenge() {
        return !challenge.isEmpty();
    }
}
