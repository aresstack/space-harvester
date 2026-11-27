package com.aresstack.askai.browser.playwright;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The lease rules of the site gate: default permits per site, the hold deadline of a lease, the wait deadline
 * after which a waiter takes the place of the oldest holder, and per-site overrides such as
 * {@code researchgate.net=1@10s} (one operation at a time, at least 10 s between two lease starts).
 * An override {@code example.org} also matches its subdomains; {@code *.example.org} is the same rule.
 */
public final class SiteLeasePolicy {

    /** Known strict sites; editable as text, same syntax as {@link #parseOverrides}. */
    public static final String DEFAULT_OVERRIDES = "researchgate.net=1@10s, sciencedirect.com=1@5s";
    public static final long DEFAULT_HOLD_DEADLINE_MILLIS = 90_000L;
    public static final long DEFAULT_WAIT_DEADLINE_MILLIS = 60_000L;
    /**
     * Default of the setting "Same site gap": spacing between two lease starts on a site without an override. The
     * host applies it with {@link #withDefaultSpacing}; {@link #defaults()} itself has no spacing.
     */
    public static final long DEFAULT_SITE_SPACING_MILLIS = 2_000L;

    /** One override: permits and minimum spacing between two lease starts on that site. */
    public static final class SiteOverride {
        public final String domain;
        public final int permits;
        public final long spacingMillis;

        SiteOverride(String domain, int permits, long spacingMillis) {
            this.domain = domain;
            this.permits = Math.max(1, permits);
            this.spacingMillis = Math.max(0L, spacingMillis);
        }

        boolean matches(String key) {
            return key.equals(domain) || key.endsWith("." + domain);
        }

        @Override
        public String toString() {
            return domain + "=" + permits + (spacingMillis > 0 ? "@" + spacingMillis + "ms" : "");
        }
    }

    public final long holdDeadlineMillis;
    public final long waitDeadlineMillis;
    public final List<SiteOverride> overrides;
    /** Spacing between two lease starts on a site that has no override; 0 = none. */
    public final long defaultSpacingMillis;

    public SiteLeasePolicy(long holdDeadlineMillis, long waitDeadlineMillis, List<SiteOverride> overrides) {
        this(holdDeadlineMillis, waitDeadlineMillis, overrides, 0L);
    }

    public SiteLeasePolicy(long holdDeadlineMillis, long waitDeadlineMillis, List<SiteOverride> overrides,
                           long defaultSpacingMillis) {
        this.defaultSpacingMillis = Math.max(0L, defaultSpacingMillis);
        this.holdDeadlineMillis = Math.max(1L, holdDeadlineMillis);
        this.waitDeadlineMillis = Math.max(1L, waitDeadlineMillis);
        this.overrides = overrides == null ? Collections.<SiteOverride>emptyList()
                : Collections.unmodifiableList(new ArrayList<SiteOverride>(overrides));
    }

    public static SiteLeasePolicy defaults() {
        return new SiteLeasePolicy(DEFAULT_HOLD_DEADLINE_MILLIS, DEFAULT_WAIT_DEADLINE_MILLIS,
                parseOverrides(DEFAULT_OVERRIDES));
    }

    /** This policy with another spacing for sites without an override. */
    public SiteLeasePolicy withDefaultSpacing(long millis) {
        return new SiteLeasePolicy(holdDeadlineMillis, waitDeadlineMillis, overrides, millis);
    }

    /** The most specific override for a site key, or null. */
    SiteOverride overrideFor(String key) {
        SiteOverride best = null;
        for (SiteOverride candidate : overrides) {
            if (candidate.matches(key) && (best == null || candidate.domain.length() > best.domain.length())) {
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Parses {@code domain=permits[@spacing]} entries separated by comma, semicolon or newline; spacing takes
     * {@code ms}, {@code s} (default) or {@code m}. Unreadable entries are rejected with the entry named.
     */
    public static List<SiteOverride> parseOverrides(String text) {
        List<SiteOverride> result = new ArrayList<SiteOverride>();
        if (text == null) {
            return result;
        }
        for (String raw : text.split("[,;\\n]")) {
            String entry = raw.trim().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) {
                continue;
            }
            int eq = entry.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("Site override needs domain=permits: " + raw.trim());
            }
            String domain = entry.substring(0, eq).trim();
            if (domain.startsWith("*.")) {
                domain = domain.substring(2);
            }
            if (domain.startsWith("www.")) {
                domain = domain.substring(4);
            }
            String rule = entry.substring(eq + 1).trim();
            int at = rule.indexOf('@');
            try {
                int permits = Integer.parseInt((at < 0 ? rule : rule.substring(0, at)).trim());
                long spacing = at < 0 ? 0L : parseDuration(rule.substring(at + 1).trim());
                if (domain.isEmpty() || permits < 1) {
                    throw new NumberFormatException();
                }
                result.add(new SiteOverride(domain, permits, spacing));
            } catch (NumberFormatException bad) {
                throw new IllegalArgumentException("Unreadable site override: " + raw.trim());
            }
        }
        return result;
    }

    private static long parseDuration(String text) {
        if (text.endsWith("ms")) {
            return Long.parseLong(text.substring(0, text.length() - 2).trim());
        }
        if (text.endsWith("m")) {
            return Long.parseLong(text.substring(0, text.length() - 1).trim()) * 60_000L;
        }
        if (text.endsWith("s")) {
            text = text.substring(0, text.length() - 1).trim();
        }
        return Long.parseLong(text) * 1000L;
    }

    @Override
    public String toString() {
        return "hold=" + holdDeadlineMillis + "ms wait=" + waitDeadlineMillis + "ms overrides=" + overrides;
    }
}
