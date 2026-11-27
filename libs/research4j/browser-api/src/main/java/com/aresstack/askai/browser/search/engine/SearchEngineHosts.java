package com.aresstack.askai.browser.search.engine;

import java.util.Locale;

/**
 * The hosts of the web search engines AskAI searches with (Google, Bing, DuckDuckGo, Startpage, Yahoo, Yahoo! JAPAN, Seznam, Yandex, Naver, Cốc Cốc, Baidu). The research browser never
 * navigates to one of them: a search engine is reached only through the search tool (the search browser or an API
 * provider), so one client with one identity talks to the engine. A search hit that still points at an engine
 * host — Google's opaque {@code /goto?url=…} wrapper, an engine vertical, an unresolved redirect — is resolved by
 * the search browser or dropped, never opened as a page.
 */
public final class SearchEngineHosts {

    private SearchEngineHosts() {
    }

    /** Whether the URL or host belongs to a search engine (its result pages, redirect wrappers and verticals). */
    public static boolean isEngineHost(String urlOrHost) {
        String host = hostOf(urlOrHost);
        return isGoogleHost(host) || isBingHost(host) || isDuckDuckGoHost(host) || isStartpageHost(host)
                || isYahooSearchHost(host) || isSeznamSearchHost(host) || isYandexHost(host)
                || isNaverSearchHost(host) || isCocCocHost(host) || isBaiduSearchHost(host);
    }

    /** {@code google.com}, {@code www.google.de}, {@code google.co.uk}, {@code google.com.br}, … */
    public static boolean isGoogleHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        if (h.startsWith("www.")) {
            h = h.substring(4);
        }
        return h.equals("google.com") || h.matches("google\\.(co\\.)?[a-z]{2,3}") || h.matches("google\\.com\\.[a-z]{2}")
                || h.endsWith(".google.com");
    }

    public static boolean isBingHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("bing.com") || h.endsWith(".bing.com");
    }

    public static boolean isDuckDuckGoHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("duckduckgo.com") || h.endsWith(".duckduckgo.com");
    }

    /** {@code startpage.com} and its subdomains (search pages, the "Anonymous View" proxy {@code /av/proxy}). */
    public static boolean isStartpageHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("startpage.com") || h.endsWith(".startpage.com");
    }

    /**
     * Yahoo's and Yahoo! JAPAN's SEARCH hosts only ({@code search.yahoo.com}, {@code r.search.yahoo.com},
     * {@code de.search.yahoo.com}, {@code search.yahoo.co.jp}): the rest of Yahoo ({@code finance.yahoo.com},
     * {@code news.yahoo.co.jp}) are ordinary sites and may be sources.
     */
    public static boolean isYahooSearchHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("search.yahoo.com") || h.endsWith(".search.yahoo.com")
                || h.equals("search.yahoo.co.jp") || h.endsWith(".search.yahoo.co.jp");
    }

    /** {@code search.seznam.cz} and its subdomains; Seznam's news and other sites are ordinary sites. */
    public static boolean isSeznamSearchHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("search.seznam.cz") || h.endsWith(".search.seznam.cz");
    }

    /** Yandex search ({@code yandex.com}, {@code yandex.ru}, {@code yandex.com.tr}, {@code ya.ru}, subdomains). */
    public static boolean isYandexHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.matches("(.+\\.)?yandex\\.(com|ru|by|kz|uz|com\\.tr|com\\.am|com\\.ge|az|fr|eu)")
                || h.equals("ya.ru") || h.endsWith(".ya.ru");
    }

    /**
     * Naver's search ({@code search.naver.com} and its subdomains) and its start page ({@code www.naver.com});
     * Naver blogs, news and cafés ({@code blog.naver.com}, …) are ordinary sites and may be sources.
     */
    public static boolean isNaverSearchHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("search.naver.com") || h.endsWith(".search.naver.com") || h.equals("naver.com")
                || h.equals("www.naver.com");
    }

    /** {@code coccoc.com} and its subdomains (the search engine and its redirect pages). */
    public static boolean isCocCocHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("coccoc.com") || h.endsWith(".coccoc.com");
    }

    /**
     * Baidu's search and start page ({@code www.baidu.com}, {@code baidu.com}, {@code m.baidu.com}) with its result
     * wrappers ({@code /link?url=}); Baidu Baike, Zhidao and Tieba ({@code baike.baidu.com}, …) are ordinary sites.
     */
    public static boolean isBaiduSearchHost(String urlOrHost) {
        String h = hostOf(urlOrHost);
        return h.equals("baidu.com") || h.equals("www.baidu.com") || h.equals("m.baidu.com");
    }

    /** Google's challenge page ({@code /sorry/…}) or consent/sign-in interstitial: nothing a search can read. */
    public static boolean isGoogleObstacleUrl(String url) {
        String u = url == null ? "" : url.toLowerCase(Locale.ROOT);
        if (!isGoogleHost(u)) {
            return false;
        }
        return u.contains("/sorry/") || u.contains("/sorry?") || u.contains("://consent.google.")
                || u.contains("://accounts.google.") || u.contains("/httpservice/retry/enablejs");
    }

    /** The lower-case host of a URL, or the argument itself (lower-cased) when it carries no scheme. */
    static String hostOf(String urlOrHost) {
        if (urlOrHost == null) {
            return "";
        }
        String value = urlOrHost.trim().toLowerCase(Locale.ROOT);
        int scheme = value.indexOf("://");
        if (scheme < 0) {
            int slash = value.indexOf('/');
            return slash < 0 ? value : value.substring(0, slash);
        }
        String rest = value.substring(scheme + 3);
        int end = rest.length();
        for (int i = 0; i < rest.length(); i++) {
            char c = rest.charAt(i);
            if (c == '/' || c == '?' || c == '#') {
                end = i;
                break;
            }
        }
        String authority = rest.substring(0, end);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            authority = authority.substring(at + 1);
        }
        int colon = authority.indexOf(':');
        return colon < 0 ? authority : authority.substring(0, colon);
    }
}
