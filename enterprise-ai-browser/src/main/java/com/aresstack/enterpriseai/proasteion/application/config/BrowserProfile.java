package com.aresstack.enterpriseai.proasteion.application.config;

/**
 * The browser profiles of a chat (issue #141): each has its own access mode, whitelist, blacklist and search URLs.
 * {@link #EXTERNAL} is the default and keeps the original {@code browser.access.*} keys, so settings from before the
 * profiles stay in effect for it.
 */
public enum BrowserProfile {

    EXTERNAL("Extern", "browser.access", "browser.search.urls"),
    INTERNAL("Intern", "browser.access.internal", "browser.search.internal.urls");

    private final String label;
    private final String accessPrefix;
    private final String searchKey;

    BrowserProfile(String label, String accessPrefix, String searchKey) {
        this.label = label;
        this.accessPrefix = accessPrefix;
        this.searchKey = searchKey;
    }

    /** {@code INTERNAL} for "internal" (any case), else the default {@code EXTERNAL}. */
    public static BrowserProfile of(String value) {
        return value != null && INTERNAL.name().equalsIgnoreCase(value.trim()) ? INTERNAL : EXTERNAL;
    }

    /** Name shown in the UI and the system prompt. */
    public String label() {
        return label;
    }

    public String modeKey() {
        return accessPrefix + ".mode";
    }

    public String allowKey() {
        return accessPrefix + ".allow";
    }

    public String blockKey() {
        return accessPrefix + ".block";
    }

    /** Search URL templates, separated by blanks; {@value BrowserAccessConfig#NONE} = deliberately none. */
    public String searchKey() {
        return searchKey;
    }
}
