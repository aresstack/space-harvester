package com.aresstack.askai.browser.playwright;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

/**
 * Static resolution of known search-engine redirect wrappers, applied BEFORE any domain classification or
 * link typing — the raw wrapper URL must never be judged as an internal engine link nor used as a
 * relevance signal. Supported formats:
 * <ul>
 * <li>Bing: {@code /ck/a?...&u=a1<base64url(target)>}</li>
 * <li>Google: {@code /url?q=<target>} (also {@code url=} / {@code u=})</li>
 * <li>Google: {@code /goto?url=<opaque token>}: the target is not in the URL, only Google knows it
 * ({@link #isOpaqueWrapper}); its treatment is an {@link OpaqueRedirectStrategy}, shipped as
 * {@link OpaqueRedirectStrategy#DISCARD}: {@code UNRESOLVED}. The wrapper URL itself is never a result link.</li>
 * <li>DuckDuckGo: {@code /l/?uddg=<target>}</li>
 * <li>Yahoo: {@code r.search.yahoo.com/_ylt=…/RV=2/RE=…/RO=10/RU=<url-encoded target>/RK=2/RS=…}</li>
 * <li>Baidu: {@code www.baidu.com/link?url=<opaque token>}: the token is never decoded, but Baidu prints the target
 * itself on the result block ({@link #BAIDU_TARGET_ATTRIBUTE}); the capture hands that value in as the link's
 * target hint, so the wrapper resolves offline without any request to Baidu. Without a hint it is
 * {@code DISCARDED}: nothing resolves it later, so it is never a result.</li>
 * <li>Google: {@code /goto?url=CAES...} — an opaque token, never decoded: always {@code UNRESOLVED}
 * (issue #41). Its HTTP resolution happens in Serp4J for API providers, not in the browser capture.</li>
 * </ul>
 * A wrapper whose target cannot be extracted safely is {@code UNRESOLVED} — the caller discards it in a
 * controlled way instead of misclassifying it by the engine's host.
 */
final class SearchRedirectResolver {

    /**
     * The attribute on Baidu's result block ({@code <div class="result c-container" mu="https://…">}) that carries the
     * hit's real target: part of Baidu's page format, like a selector, not a tunable value.
     */
    static final String BAIDU_TARGET_ATTRIBUTE = "mu";

    /** The outcome of one resolution attempt. */
    enum Status {
        /** The URL is no known redirect wrapper — use it as-is. */
        NOT_A_REDIRECT,
        /** The wrapper's target was extracted; {@link Resolution#getTargetUrl()} carries it. */
        RESOLVED,
        /** A known wrapper whose target could not be extracted — discard, never classify by wrapper host. */
        UNRESOLVED,
        /** A known wrapper that nothing resolves, now or later (Baidu without its block's target): never a result. */
        DISCARDED
    }

    static final class Resolution {
        private final Status status;
        private final String targetUrl;

        /** A pass-through resolution (used when redirect resolution is disabled by settings). */
        static Resolution passThrough(String url) {
            return new Resolution(Status.NOT_A_REDIRECT, url);
        }

        private Resolution(Status status, String targetUrl) {
            this.status = status;
            this.targetUrl = targetUrl == null ? "" : targetUrl;
        }

        Status getStatus() {
            return status;
        }

        String getTargetUrl() {
            return targetUrl;
        }
    }

    private SearchRedirectResolver() {
    }

    /** Resolution with the shipped treatment of opaque wrappers, {@link OpaqueRedirectStrategy#DISCARD}. */
    static Resolution resolve(String url) {
        return resolve(url, OpaqueRedirectStrategy.DISCARD);
    }

    static Resolution resolve(String url, OpaqueRedirectStrategy opaqueWrappers) {
        return resolve(url, opaqueWrappers, "");
    }

    /**
     * Resolution with the target the page itself prints next to the link ({@code targetHint}, the result block's
     * {@link #BAIDU_TARGET_ATTRIBUTE}); only Baidu's wrapper uses it, every other link ignores it.
     */
    static Resolution resolve(String url, String targetHint) {
        return resolve(url, OpaqueRedirectStrategy.DISCARD, targetHint);
    }

    static Resolution resolve(String url, OpaqueRedirectStrategy opaqueWrappers, String targetHint) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (lower.isEmpty() || !lower.startsWith("http")) {
            return new Resolution(Status.NOT_A_REDIRECT, url);
        }
        String host = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver.hostOf(lower);
        String path = pathOf(lower);
        if (host.endsWith("bing.com") && path.startsWith("/ck/")) {
            String encoded = queryParameter(url, "u");
            String target = decodeBingTarget(encoded, url);
            return target == null ? new Resolution(Status.UNRESOLVED, "")
                    : new Resolution(Status.RESOLVED, target);
        }
        if (host.endsWith("google.com") && (path.equals("/url") || path.startsWith("/url?")
                || path.startsWith("/url/"))) {
            String target = firstNonEmpty(queryParameter(url, "q"), queryParameter(url, "url"),
                    queryParameter(url, "u"));
            return target != null && target.startsWith("http")
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        if (isGoogleGoto(host, path, url)) {
            String target = opaqueWrappers.targetOf(url);
            return target != null && (target.startsWith("http://") || target.startsWith("https://"))
                    && !isOpaqueWrapper(target)
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        if (host.endsWith("duckduckgo.com") && path.startsWith("/l/")) {
            String target = queryParameter(url, "uddg");
            return target != null && target.startsWith("http")
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        if (host.equals("r.search.yahoo.com") || host.endsWith(".r.search.yahoo.com")) {
            String target = yahooTarget(url);
            return target != null && (target.startsWith("http://") || target.startsWith("https://"))
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.UNRESOLVED, "");
        }
        if (isBaiduLink(host, path, url)) {
            String target = targetHint == null ? "" : targetHint.trim();
            String lowerTarget = target.toLowerCase(Locale.ROOT);
            return (lowerTarget.startsWith("http://") || lowerTarget.startsWith("https://"))
                    && !isOpaqueWrapper(target)
                    && !com.aresstack.askai.browser.search.engine.SearchEngineHosts.isEngineHost(target)
                    ? new Resolution(Status.RESOLVED, target) : new Resolution(Status.DISCARDED, "");
        }
        return new Resolution(Status.NOT_A_REDIRECT, url);
    }

    /** Baidu's result wrapper {@code (www.|m.)baidu.com/link?url=<token>}. */
    private static boolean isBaiduLink(String host, String path, String url) {
        String token = queryParameter(url, "url");
        return com.aresstack.askai.browser.search.engine.SearchEngineHosts.isBaiduSearchHost(host)
                && (path.equals("/link") || path.startsWith("/link?")) && token != null && !token.isEmpty();
    }

    /** Yahoo's wrapper carries the target URL-encoded in the {@code /RU=…/} path segment. */
    private static String yahooTarget(String url) {
        int start = url.indexOf("/RU=");
        if (start < 0) {
            return null;
        }
        start += "/RU=".length();
        int end = url.indexOf('/', start);
        String encoded = end < 0 ? url.substring(start) : url.substring(start, end);
        try {
            return URLDecoder.decode(encoded, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException | IllegalArgumentException malformed) {
            return null;
        }
    }

    /**
     * Whether the URL is an opaque result wrapper whose target is not in the URL: Google's {@code /goto?url=<token>}
     * (2026 SERPs; never decoded or guessed from the page) or Baidu's {@code /link?url=<token>} (resolved only from the
     * target Baidu prints on the result block).
     */
    static boolean isOpaqueWrapper(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http")) {
            return false;
        }
        String host = com.aresstack.askai.browser.domain.PublicSuffixDomainKeyResolver.hostOf(lower);
        String path = pathOf(lower);
        return isGoogleGoto(host, path, url) || isBaiduLink(host, path, url);
    }

    private static boolean isGoogleGoto(String host, String path, String url) {
        String token = queryParameter(url, "url");
        return (host.equals("google.com") || host.endsWith(".google.com")) && path.equals("/goto")
                && token != null && !token.isEmpty();
    }

    /**
     * Bing encodes the target as {@code a1} + base64url; anything else is unresolved. Its own tabs (Images, Videos,
     * Maps, Shopping, ...) carry a RELATIVE target such as {@code /images/search?q=...}: that is resolved against
     * the wrapper's origin, so the link counts as Bing-internal instead of an unresolved hit that lazy resolution
     * (askai #41) would keep as a result.
     */
    private static String decodeBingTarget(String encoded, String wrapper) {
        if (encoded == null || !encoded.startsWith("a1")) {
            return null;
        }
        try {
            String base64 = encoded.substring(2);
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(base64));
            String target = new String(decoded, StandardCharsets.UTF_8);
            if (target.startsWith("http")) {
                return target;
            }
            if (target.startsWith("/") && !target.startsWith("//")) {
                int scheme = wrapper.indexOf("://");
                int slash = wrapper.indexOf('/', scheme + 3);
                return slash < 0 ? null : wrapper.substring(0, slash) + target;
            }
            return null;
        } catch (RuntimeException notBase64) {
            return null;
        }
    }

    private static String padBase64(String value) {
        int remainder = value.length() % 4;
        if (remainder == 2) {
            return value + "==";
        }
        if (remainder == 3) {
            return value + "=";
        }
        return value;
    }

    /** URL-decoded value of one query parameter, or null. */
    static String queryParameter(String url, String name) {
        int question = url.indexOf('?');
        if (question < 0) {
            return null;
        }
        String query = url.substring(question + 1);
        int fragment = query.indexOf('#');
        if (fragment >= 0) {
            query = query.substring(0, fragment);
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0 && pair.substring(0, eq).equals(name)) {
                try {
                    return URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                } catch (UnsupportedEncodingException | IllegalArgumentException ex) {
                    return null; // an undecodable value is never guessed
                }
            }
        }
        return null;
    }

    private static String pathOf(String url) {
        int scheme = url.indexOf("://");
        if (scheme < 0) {
            return "";
        }
        String rest = url.substring(scheme + 3);
        int slash = rest.indexOf('/');
        return slash < 0 ? "/" : stripQuery(rest.substring(slash));
    }

    private static String stripQuery(String path) {
        int question = path.indexOf('?');
        return question < 0 ? path : path.substring(0, question);
    }

    private static boolean isGoogleHost(String host) {
        String h = host.startsWith("www.") ? host.substring(4) : host;
        return h.equals("google.com") || h.matches("google\\.(co\\.)?[a-z]{2,3}")
                || h.matches("google\\.com\\.[a-z]{2}");
    }

    private static String firstNonEmpty(String... values) {
        for (String value : values) {
            if (value != null && !value.isEmpty()) {
                return value;
            }
        }
        return null;
    }

}
