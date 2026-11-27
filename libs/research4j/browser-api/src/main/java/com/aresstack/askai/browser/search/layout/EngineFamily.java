package com.aresstack.askai.browser.search.layout;

import java.util.Locale;

/**
 * Coarse search-engine family of a rendered SERP, derived MECHANICALLY from the page host only. It
 * exists so a validated layout profile can be scoped to an engine family (a Google profile must
 * never be reused for a Bing page) and so diagnostics read clearly. It carries no behaviour and no
 * per-engine selectors — the mechanical analysis stays engine-agnostic.
 */
public enum EngineFamily {
    GOOGLE,
    BING,
    DUCKDUCKGO,
    STARTPAGE,
    BRAVE,
    ECOSIA,
    MOJEEK,
    YANDEX,
    /** Yahoo Search and Yahoo! JAPAN (search.yahoo.com, search.yahoo.co.jp). */
    YAHOO,
    SEZNAM,
    NAVER,
    COCCOC,
    BAIDU,
    /** A recognizable general web search engine that is not one of the named families. */
    GENERIC,
    /** Host absent or unrecognizable. */
    UNKNOWN;

    /**
     * Best-effort family from a page URL or bare host — never throws; unknown or empty input yields
     * {@link #UNKNOWN}. Whole-label matching only, so "notbing.example" is not {@link #BING}.
     */
    public static EngineFamily fromUrlOrHost(String urlOrHost) {
        if (urlOrHost == null) {
            return UNKNOWN;
        }
        String host = hostOf(urlOrHost);
        if (host.isEmpty()) {
            return UNKNOWN;
        }
        if (hasLabel(host, "google")) {
            return GOOGLE;
        }
        if (hasLabel(host, "bing")) {
            return BING;
        }
        if (hasLabel(host, "duckduckgo")) {
            return DUCKDUCKGO;
        }
        if (hasLabel(host, "startpage")) {
            return STARTPAGE;
        }
        if (hasLabel(host, "brave")) {
            return BRAVE;
        }
        if (hasLabel(host, "ecosia")) {
            return ECOSIA;
        }
        if (hasLabel(host, "mojeek")) {
            return MOJEEK;
        }
        if (hasLabel(host, "yandex")) {
            return YANDEX;
        }
        if (hasLabel(host, "yahoo")) {
            return YAHOO;
        }
        if (hasLabel(host, "seznam")) {
            return SEZNAM;
        }
        if (hasLabel(host, "naver")) {
            return NAVER;
        }
        if (hasLabel(host, "coccoc")) {
            return COCCOC;
        }
        if (hasLabel(host, "baidu")) {
            return BAIDU;
        }
        return GENERIC;
    }

    private static String hostOf(String urlOrHost) {
        String value = urlOrHost.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme >= 0) {
            value = value.substring(scheme + 3);
        }
        int slash = value.indexOf('/');
        if (slash >= 0) {
            value = value.substring(0, slash);
        }
        int at = value.indexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static boolean hasLabel(String host, String label) {
        for (String part : host.split("\\.")) {
            if (part.equals(label)) {
                return true;
            }
        }
        return false;
    }
}
