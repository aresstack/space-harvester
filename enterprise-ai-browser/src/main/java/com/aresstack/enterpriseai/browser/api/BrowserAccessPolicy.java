package com.aresstack.enterpriseai.browser.api;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Global, immutable access policy for the chat browsers.
 *
 * <p>Rules: {@code example.org} matches exactly that host; {@code *.example.org} matches any subdomain of
 * example.org but not example.org itself. The block list always wins. Only http and https addresses are
 * checked; other schemes (for example {@code about:blank}) are always allowed.</p>
 */
public final class BrowserAccessPolicy {

    /** What happens to hosts on neither list. */
    public enum Mode {
        /** Unknown hosts are {@link Verdict#UNKNOWN}: the user is asked. */
        BLOCK_BY_DEFAULT,
        /** Unknown hosts are allowed. */
        ALLOW_BY_DEFAULT
    }

    /** Result of {@link #decide(String)}. */
    public enum Verdict {
        ALLOWED,
        BLOCKED,
        UNKNOWN
    }

    private final Mode mode;
    private final List<String> allow;
    private final List<String> block;

    public BrowserAccessPolicy(Mode mode, Collection<String> allow, Collection<String> block) {
        this.mode = mode == null ? Mode.BLOCK_BY_DEFAULT : mode;
        this.allow = normalizeRules(allow);
        this.block = normalizeRules(block);
    }

    /** Block by default with empty lists. */
    public static BrowserAccessPolicy defaults() {
        return new BrowserAccessPolicy(Mode.BLOCK_BY_DEFAULT, Collections.<String>emptyList(),
                Collections.<String>emptyList());
    }

    public Mode mode() {
        return mode;
    }

    /** Normalized, valid allow rules. */
    public List<String> allow() {
        return allow;
    }

    /** Normalized, valid block rules. */
    public List<String> block() {
        return block;
    }

    public Verdict decide(String url) {
        String host = hostOf(url);
        if (host == null) {
            return isChecked(url) ? Verdict.BLOCKED : Verdict.ALLOWED;
        }
        if (matches(block, host)) {
            return Verdict.BLOCKED;
        }
        if (matches(allow, host)) {
            return Verdict.ALLOWED;
        }
        return mode == Mode.ALLOW_BY_DEFAULT ? Verdict.ALLOWED : Verdict.UNKNOWN;
    }

    /** Normalized host of an http(s) address, or null for other schemes and unparsable addresses. */
    public static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null) {
                return null;
            }
            String lower = scheme.toLowerCase(Locale.ROOT);
            if (!"http".equals(lower) && !"https".equals(lower)) {
                return null;
            }
            String host = uri.getHost();
            if (host == null) {
                // Non-ASCII hosts are not parsed by URI; fall back to the raw authority.
                String authority = uri.getRawAuthority();
                if (authority == null) {
                    return null;
                }
                int at = authority.lastIndexOf('@');
                host = at >= 0 ? authority.substring(at + 1) : authority;
                int colon = host.lastIndexOf(':');
                if (colon >= 0 && !host.startsWith("[")) {
                    host = host.substring(0, colon);
                }
            }
            return normalizeHost(host);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Normalizes one rule; null when it is invalid. */
    public static String normalizeRule(String rule) {
        if (rule == null) {
            return null;
        }
        String trimmed = rule.trim();
        if (trimmed.startsWith("*.")) {
            String host = normalizeHost(trimmed.substring(2));
            return host == null ? null : "*." + host;
        }
        return normalizeHost(trimmed);
    }

    private static boolean isChecked(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.trim().toLowerCase(Locale.ROOT);
        return lower.startsWith("http:") || lower.startsWith("https:");
    }

    private static String normalizeHost(String raw) {
        if (raw == null) {
            return null;
        }
        String host = raw.trim();
        if (host.isEmpty()) {
            return null;
        }
        for (int i = 0; i < host.length(); i++) {
            char c = host.charAt(i);
            if (Character.isWhitespace(c) || c == '/' || c == ':' || c == '@' || c == '?' || c == '#'
                    || c == '*' || c == '\\') {
                return null;
            }
        }
        host = host.toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        if (host.isEmpty() || host.startsWith(".") || host.contains("..")) {
            return null;
        }
        // Internationalised hosts must be written in their ASCII (punycode) form; anything else is rejected.
        for (int i = 0; i < host.length(); i++) {
            if (host.charAt(i) > 0x7e) {
                return null;
            }
        }
        return host;
    }

    private static List<String> normalizeRules(Collection<String> rules) {
        Set<String> result = new LinkedHashSet<String>();
        if (rules != null) {
            for (String rule : rules) {
                String normalized = normalizeRule(rule);
                if (normalized != null) {
                    result.add(normalized);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<String>(result));
    }

    private static boolean matches(List<String> rules, String host) {
        for (String rule : rules) {
            if (rule.startsWith("*.")) {
                if (host.endsWith(rule.substring(1))) {
                    return true;
                }
            } else if (rule.equals(host)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "BrowserAccessPolicy{" + mode + ", allow=" + allow + ", block=" + block + "}";
    }
}
