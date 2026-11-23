package com.aresstack.enterpriseai.proasteion.application.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One browser profile's access policy and search URLs (issue #141); the {@link BrowserProfile#EXTERNAL} profile holds
 * the {@link BrowserProfile#INTERNAL} one ({@link #profile}). Global access policy of the chat browsers (issue #141) as configured: {@value #MODE_KEY} ({@value #MODE_BLOCK}
 * by default, or {@value #MODE_ALLOW}), {@value #ALLOW_KEY} and {@value #BLOCK_KEY} as comma separated host rules.
 * Kept free of browser port types so the settings UI can read it; the browser layer turns it into its policy.
 */
public final class BrowserAccessConfig {

    public static final String MODE_KEY = "browser.access.mode";
    public static final String ALLOW_KEY = "browser.access.allow";
    public static final String BLOCK_KEY = "browser.access.block";
    public static final String MODE_BLOCK = "block";
    public static final String MODE_ALLOW = "allow";
    public static final String SEPARATOR = ",";
    /** Stored for a deliberately empty search URL list (an empty value means the default). */
    public static final String NONE = "-";
    /** Placeholder of the search words in a search URL template. */
    public static final String QUERY = "{query}";

    private final boolean allowByDefault;
    private final List<String> allow;
    private final List<String> block;
    private final List<String> searchUrls;
    private final BrowserAccessConfig internal;

    public BrowserAccessConfig(boolean allowByDefault, List<String> allow, List<String> block) {
        this(allowByDefault, allow, block, Collections.<String>emptyList(), null);
    }

    public BrowserAccessConfig(boolean allowByDefault, List<String> allow, List<String> block,
                               List<String> searchUrls, BrowserAccessConfig internal) {
        this.allowByDefault = allowByDefault;
        this.allow = clean(allow);
        this.block = clean(block);
        this.searchUrls = clean(searchUrls);
        this.internal = internal == null ? null : internal.internal == null ? internal
                : new BrowserAccessConfig(internal.allowByDefault, internal.allow, internal.block,
                internal.searchUrls, null);
    }

    public static BrowserAccessConfig defaults() {
        return new BrowserAccessConfig(false, Collections.<String>emptyList(), Collections.<String>emptyList());
    }

    /** Defaults of the internal profile: block by default, no rules, no search URLs. */
    public static BrowserAccessConfig internalDefaults() {
        return new BrowserAccessConfig(false, Collections.<String>emptyList(), Collections.<String>emptyList(),
                Collections.<String>emptyList(), null);
    }

    /** This (external) configuration with {@code internal} as the internal profile. */
    public BrowserAccessConfig withInternal(BrowserAccessConfig internal) {
        return new BrowserAccessConfig(allowByDefault, allow, block, searchUrls, internal);
    }

    /** The configuration of {@code profile}; called on the external configuration. */
    public BrowserAccessConfig profile(BrowserProfile profile) {
        if (profile == BrowserProfile.INTERNAL) {
            return internal == null ? internalDefaults() : internal;
        }
        return internal == null ? this : new BrowserAccessConfig(allowByDefault, allow, block, searchUrls, null);
    }

    /** Splits stored search URL templates (blanks or line breaks); {@link #NONE} gives none. */
    public static List<String> splitUrls(String value) {
        List<String> urls = new ArrayList<String>();
        if (value != null && !NONE.equals(value.trim())) {
            for (String part : value.split("\\s+")) {
                if (!part.trim().isEmpty() && !urls.contains(part.trim())) {
                    urls.add(part.trim());
                }
            }
        }
        return urls;
    }

    /** Joins search URL templates for storing; an empty list is stored as {@link #NONE}. */
    public static String joinUrls(List<String> urls) {
        StringBuilder value = new StringBuilder();
        if (urls != null) {
            for (String url : urls) {
                if (url != null && !url.trim().isEmpty()) {
                    if (value.length() > 0) {
                        value.append(' ');
                    }
                    value.append(url.trim());
                }
            }
        }
        return value.length() == 0 ? NONE : value.toString();
    }

    /** Is {@code value} the "allow by default" mode? Anything else means block by default. */
    public static boolean isAllowMode(String value) {
        return value != null && MODE_ALLOW.equals(value.trim().toLowerCase(Locale.ROOT));
    }

    /** Splits a stored comma separated rule list (also accepts line breaks). */
    public static List<String> split(String value) {
        List<String> rules = new ArrayList<String>();
        if (value != null) {
            for (String part : value.split("[,\\r\\n]")) {
                if (!part.trim().isEmpty()) {
                    rules.add(part.trim());
                }
            }
        }
        return rules;
    }

    /** Joins rules into the stored comma separated value. */
    public static String join(List<String> rules) {
        StringBuilder value = new StringBuilder();
        for (String rule : rules) {
            if (rule != null && !rule.trim().isEmpty()) {
                if (value.length() > 0) {
                    value.append(SEPARATOR);
                }
                value.append(rule.trim());
            }
        }
        return value.toString();
    }

    private static List<String> clean(List<String> rules) {
        List<String> cleaned = new ArrayList<String>();
        if (rules != null) {
            for (String rule : rules) {
                String trimmed = rule == null ? "" : rule.trim();
                if (!trimmed.isEmpty() && !cleaned.contains(trimmed)) {
                    cleaned.add(trimmed);
                }
            }
        }
        return Collections.unmodifiableList(cleaned);
    }

    /** {@code true}: unknown hosts are allowed; {@code false} (default): they need the user's approval. */
    public boolean allowByDefault() {
        return allowByDefault;
    }

    /** Whitelist rules as entered. */
    public List<String> allow() {
        return allow;
    }

    /** Blacklist rules as entered. */
    public List<String> block() {
        return block;
    }

    /** Search URL templates with {@link #QUERY}, in order. */
    public List<String> searchUrls() {
        return searchUrls;
    }

    @Override
    public String toString() {
        return "BrowserAccessConfig[" + (allowByDefault ? MODE_ALLOW : MODE_BLOCK) + ", allow=" + allow
                + ", block=" + block + ", search=" + searchUrls + (internal == null ? "" : ", internal=" + internal)
                + "]";
    }
}
