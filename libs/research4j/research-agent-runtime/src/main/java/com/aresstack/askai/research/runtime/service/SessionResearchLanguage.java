package com.aresstack.askai.research.runtime.service;

/**
 * The runtime's mirror of the session's CURRENT working language (stable codes "en"/"de", English default).
 * It is mutable session context, updated by two channels with a fixed authority order: {@code set_language}
 * is the best-effort live synchronisation for the next TeamAgent turn, while the language snapshot carried
 * by an operation request (e.g. {@code manual_search}) is AUTHORITATIVE and re-synchronises this holder —
 * a lost fire-and-forget command therefore heals itself on the next real request.
 */
public final class SessionResearchLanguage {

    private volatile String code = "en";

    /**
     * The session's language as the host handed it over at LAUNCH. This is what makes the very first turn
     * deterministic: an unset/unknown value keeps the English default, exactly as before.
     */
    public static SessionResearchLanguage fromEnvironment(String languageCode) {
        SessionResearchLanguage language = new SessionResearchLanguage();
        language.changeFromCode(languageCode);
        return language;
    }

    /** Normalizes to "en"/"de"; unknown or missing codes fall back to the English default. */
    public void changeFromCode(String value) {
        this.code = "de".equalsIgnoreCase(value) ? "de" : "en";
    }

    public String code() {
        return code;
    }

    /** The human name used in the working-language system instruction. */
    public String displayName() {
        return "de".equals(code) ? "German" : "English";
    }
}
