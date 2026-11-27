package com.aresstack.askai.research.runtime.acquire;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Pure, stateless text mechanics of the deterministic web-acquisition path: query-term derivation, content
 * relevance, tool-result field/URL/title parsing, canonicalization, host/family-free helpers and status-line
 * stripping. Extracted verbatim from {@code ResearchLoop} (which keeps thin delegators) as the first, lowest-
 * risk step of the acquisition-service extraction — no behavior change, no orchestration, no state, no I/O.
 */
public final class WebAcquisitionText {

    private WebAcquisitionText() {
    }

    public static Set<String> queryTerms(String task) {
        Set<String> terms = new HashSet<String>();
        // Unicode letters/digits: "hühner" is ONE term, never "h"+"hner". These terms are for
        // RELEVANCE MATCHING only — the search engine always receives the user's original text.
        for (String word : (task == null ? "" : task).toLowerCase(Locale.ROOT)
                .split("[^\\p{L}\\p{Nd}]+")) {
            if (word.length() >= 3) {
                terms.add(word);
            }
        }
        return terms;
    }

    public static boolean matches(String lowerText, Set<String> terms) {
        for (String term : terms) {
            if (lowerText.contains(term)) {
                return true;
            }
        }
        return false;
    }

    public static String field(String result, String key) {
        for (String token : result.split("[\\s\\n]+")) {
            if (token.startsWith(key + "=")) {
                return token.substring(key.length() + 1).replace("\"", "");
            }
        }
        // title="a b c" spans tokens; handle quoted form.
        int i = result.indexOf(key + "=\"");
        if (i >= 0) {
            int end = result.indexOf('"', i + key.length() + 2);
            if (end > 0) {
                return result.substring(i + key.length() + 2, end);
            }
        }
        return null;
    }

    public static List<String> extractUrls(String text) {
        List<String> urls = new ArrayList<String>();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https?://[^\\s\"]+").matcher(text == null ? "" : text);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls;
    }

    public static String lastUrl(String line) {
        List<String> urls = extractUrls(line);
        return urls.isEmpty() ? null : urls.get(urls.size() - 1);
    }

    /**
     * The words a page uses to point somewhere, from one {@code web_links} line
     * ({@code <id>: <text> — <url>}). The URL is deliberately NOT part of it: a link is judged by
     * what it says it leads to, and an address that happens to spell a query word says nothing.
     *
     * @return the anchor text, or {@code ""} when the line carries none
     */
    public static String anchorTextOf(String line) {
        if (line == null) {
            return "";
        }
        String rest = line;
        String url = lastUrl(rest);
        if (url != null) {
            int at = rest.lastIndexOf(url);
            if (at > 0) {
                rest = rest.substring(0, at);
            }
        }
        int colon = rest.indexOf(':');
        if (colon >= 0 && colon + 1 < rest.length()) {
            rest = rest.substring(colon + 1);
        }
        // Drop the separator the renderer puts between text and address, whichever dash it used.
        rest = rest.replace('—', ' ').replace('–', ' ');
        return rest.trim();
    }

    public static String canonicalish(String url) {
        String u = url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
        int frag = u.indexOf('#');
        if (frag >= 0) {
            u = u.substring(0, frag);
        }
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }

    /**
     * The FINAL post-navigation URL out of a {@code web_open} result. Both known result shapes start with
     * "URL: &lt;url&gt;" — the bridge appends {@code title="…" capture_id=…} on the same line, the raw browser runtime
     * puts TITLE on the next line; in both cases the URL is the token right after the prefix.
     */
    /**
     * The page text without its header lines ({@code URL: ...}, {@code TITLE: ...}, {@code (truncated)}): what a
     * reader of the page sees.
     */
    public static String bodyOf(String page) {
        if (page == null) {
            return "";
        }
        String[] lines = page.replace("\r", "").split("\n", -1);
        int start = 0;
        while (start < lines.length) {
            String line = lines[start].trim();
            if (line.startsWith("URL:") || line.startsWith("TITLE:") || line.equals("(truncated)")) {
                start++;
            } else {
                break;
            }
        }
        StringBuilder body = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            body.append(i > start ? "\n" : "").append(lines[i]);
        }
        return body.toString().trim();
    }

    public static String finalUrlOf(String page) {
        if (page == null || !page.startsWith("URL: ")) {
            return null;
        }
        String rest = page.substring("URL: ".length());
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r') {
                end = i;
                break;
            }
        }
        String url = rest.substring(0, end).trim();
        return url.isEmpty() ? null : url;
    }

    /**
     * The page title out of a {@code web_open} result. The bridge appends {@code title="…"} on the URL line
     * (parsed as the full quoted value, not just the first word), the raw browser runtime reports a "TITLE: …" line.
     */
    public static String titleOf(String page) {
        if (page == null) {
            return "";
        }
        int i = page.indexOf("title=\"");
        if (i >= 0) {
            int end = page.indexOf('"', i + "title=\"".length());
            if (end > 0) {
                return page.substring(i + "title=\"".length(), end);
            }
        }
        for (String line : page.split("\n")) {
            if (line.startsWith("TITLE: ")) {
                return line.substring("TITLE: ".length()).trim();
            }
        }
        return "";
    }

    /** Remove typed status lines (PROVIDER/CHALLENGE/RESOLVED/NONE) so their URLs never enter the frontier. */
    public static String stripStatusLines(String results) {
        StringBuilder sb = new StringBuilder();
        for (String line : (results == null ? "" : results).split("\n")) {
            if (line.startsWith("PROVIDER: ") || line.startsWith("CHALLENGE: ")
                    || line.startsWith("RESOLVED: ") || line.startsWith("ATTEMPT: ")
                    || line.equals("NONE")) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    /** All {@code PROVIDER: <host>} lines of a {@code web_search} result (fallback engines add more). */
    public static List<String> providerHostsOf(String results) {
        List<String> hosts = new ArrayList<String>();
        for (String line : (results == null ? "" : results).split("\n")) {
            if (line.startsWith("PROVIDER: ")) {
                hosts.add(line.substring("PROVIDER: ".length()).trim().toLowerCase(Locale.ROOT));
            }
        }
        return hosts;
    }

    public static String hostOf(String url) {
        int i = url.indexOf("://");
        if (i < 0) {
            return "";
        }
        String rest = url.substring(i + 3);
        int slash = rest.indexOf('/');
        return (slash < 0 ? rest : rest.substring(0, slash)).toLowerCase(Locale.ROOT);
    }

    public static String join(Set<String> terms) {
        StringBuilder sb = new StringBuilder();
        for (String t : terms) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }
}
