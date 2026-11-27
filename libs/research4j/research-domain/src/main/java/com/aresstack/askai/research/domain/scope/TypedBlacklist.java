package com.aresstack.askai.research.domain.scope;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * PR 12: the deterministic term and domain filter over the user's TYPED blacklist entries
 * ({@link ResearchScopeDraft#getExclusions()}). It needs no model, so the blacklist also works
 * without the semantic gate. Facets the agent records ({@code exclude_topic},
 * {@code blacklist_add}) never come here: only the semantic gate applies them.
 * <ul>
 * <li>An entry with a dot and no whitespace is a host name: it blocks that domain and its
 * subdomains, matched against the URL host only.</li>
 * <li>Any other entry is a term: it matches case-insensitively as a whole word (or phrase) in
 * title, snippet or URL.</li>
 * <li>An entry starting with {@code !} followed by a host name is an exemption: that host and its
 * subdomains are never hit by a host entry ({@code !aws.amazon.com} keeps the AWS documentation while
 * {@code amazon.com} blocks the shop). Terms still apply.</li>
 * </ul>
 * Immutable; one instance is one snapshot.
 */
public final class TypedBlacklist {

    private static final TypedBlacklist EMPTY = new TypedBlacklist(Collections.<String>emptyList());

    private final List<String> entries;

    private TypedBlacklist(List<String> entries) {
        this.entries = Collections.unmodifiableList(entries);
    }

    public static TypedBlacklist empty() {
        return EMPTY;
    }

    /** The typed entries in order; blank and duplicate (case-insensitive) entries are dropped. */
    public static TypedBlacklist of(List<String> typedEntries) {
        if (typedEntries == null || typedEntries.isEmpty()) {
            return EMPTY;
        }
        Set<String> seen = new LinkedHashSet<String>();
        List<String> kept = new ArrayList<String>();
        for (String entry : typedEntries) {
            String value = entry == null ? "" : entry.trim();
            if (!value.isEmpty() && seen.add(value.toLowerCase(Locale.ROOT))) {
                kept.add(value);
            }
        }
        return kept.isEmpty() ? EMPTY : new TypedBlacklist(kept);
    }

    /** The typed entries of the draft (never its EXCLUDED facets). */
    public static TypedBlacklist of(ResearchScopeDraft draft) {
        return draft == null ? EMPTY : of(draft.getExclusions());
    }

    public List<String> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** A dot and no whitespace: the entry names a site, not a word. */
    public static boolean isHostEntry(String entry) {
        String value = entry == null ? "" : entry.trim();
        if (value.isEmpty() || value.indexOf('.') < 0) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isWhitespace(value.charAt(index))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The first entry that hits this candidate, or {@code null}. Any argument may be empty or
     * {@code null}; a host entry only ever looks at the URL host.
     */
    public String firstMatch(String title, String snippet, String url) {
        if (entries.isEmpty()) {
            return null;
        }
        String host = hostOf(url);
        String text = (nullToEmpty(title) + "\n" + nullToEmpty(snippet) + "\n" + nullToEmpty(url))
                .toLowerCase(Locale.ROOT);
        boolean exempt = isExemptHost(host);
        for (String entry : entries) {
            if (isExemptEntry(entry)) {
                continue;
            }
            if (isHostEntry(entry)) {
                if (!exempt && hostMatches(host, normalizeHost(entry))) {
                    return entry;
                }
            } else if (containsWord(text, entry.toLowerCase(Locale.ROOT))) {
                return entry;
            }
        }
        return null;
    }

    /** {@code !host}: the host (and its subdomains) is never hit by a host entry. */
    public static boolean isExemptEntry(String entry) {
        String value = entry == null ? "" : entry.trim();
        return value.startsWith("!") && isHostEntry(value.substring(1));
    }

    /** Whether an exemption entry names this host or one of its parents. */
    public boolean isExemptHost(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        for (String entry : entries) {
            if (isExemptEntry(entry) && hostMatches(host, normalizeHost(entry.trim().substring(1)))) {
                return true;
            }
        }
        return false;
    }

    /** {@code true} when any entry hits the candidate. */
    public boolean matches(String title, String snippet, String url) {
        return firstMatch(title, snippet, url) != null;
    }

    static boolean hostMatches(String host, String blocked) {
        if (host.isEmpty() || blocked.isEmpty()) {
            return false;
        }
        return host.equals(blocked) || host.endsWith("." + blocked);
    }

    /** "https://www.Example.org/x" and "www.example.org" both become "example.org". */
    static String normalizeHost(String entry) {
        String value = entry.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        for (char end : new char[] {'/', '?', '#'}) {
            int cut = value.indexOf(end);
            if (cut >= 0) {
                value = value.substring(0, cut);
            }
        }
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        while (value.endsWith(".")) {
            value = value.substring(0, value.length() - 1);
        }
        return value.startsWith("www.") ? value.substring(4) : value;
    }

    static String hostOf(String url) {
        String value = nullToEmpty(url).trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.indexOf("://") < 0) {
            // a bare host or path only counts when it looks like a host
            if (value.indexOf('.') < 0) {
                return "";
            }
        }
        return normalizeHost(value);
    }

    /** Whole-word (or whole-phrase) containment: the neighbours are no letter or digit. */
    static boolean containsWord(String text, String term) {
        if (term.isEmpty()) {
            return false;
        }
        int from = 0;
        while (true) {
            int at = text.indexOf(term, from);
            if (at < 0) {
                return false;
            }
            int end = at + term.length();
            boolean startOk = at == 0 || !Character.isLetterOrDigit(text.charAt(at - 1));
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            from = at + 1;
        }
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String toString() {
        return "TypedBlacklist" + entries;
    }
}
