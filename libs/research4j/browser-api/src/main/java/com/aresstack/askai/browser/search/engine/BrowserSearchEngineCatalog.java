package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The engines the product knows how to drive through a browser. Ids are stable — they are persisted. */
public final class BrowserSearchEngineCatalog {

    /**
     * Google runs in AskAI's own headless SEARCH browser (serp4j-playwright-google), not in the research browser
     * that opens DuckDuckGo/Bing result pages; see {@link #runsInSearchBrowser(String)}.
     */
    public static final String GOOGLE = "google";
    public static final String DUCKDUCKGO = "duckduckgo";
    public static final String BING = "bing";
    /**
     * Startpage (Netherlands) answers with Google's results, but its hit links point straight at the target page:
     * no redirect wrapper to resolve, so no extra request to Google per opened hit.
     */
    public static final String STARTPAGE = "startpage";
    public static final String YAHOO = "yahoo";
    public static final String YAHOO_JAPAN = "yahoo-japan";
    public static final String SEZNAM = "seznam";
    public static final String YANDEX = "yandex";
    public static final String NAVER = "naver";
    public static final String COCCOC = "coccoc";
    /**
     * Baidu (China). Its hit links are opaque {@code baidu.com/link?url=} wrappers; the target is taken offline from the
     * result block's {@code mu} attribute when the page is captured, never by asking Baidu.
     */
    public static final String BAIDU = "baidu";
    /**
     * The engine a dev/test {@code --search-url=} override installs. It is not part of the catalog: it
     * exists only when someone hands the sidecar an explicit template, and it then REPLACES the
     * configured engines rather than joining them.
     */
    public static final String CUSTOM = "custom";

    /**
     * Every catalog engine is asked the way a person asks it, as Argos does (issue #22): its regular start page, a
     * real click into the search field, the query typed key by key, a real Enter; deeper result pages by a real click
     * on the engine's own "next" control. Selectors are alternatives for layout changes, tried in order.
     */
    private static final List<BrowserSearchEngine> ENGINES = Collections.unmodifiableList(
            new ArrayList<BrowserSearchEngine>(Arrays.asList(
                    new BrowserSearchEngine(GOOGLE, "Google", Arrays.asList(
                            new BrowserSearchEngine.SearchForm("https://www.google.com/",
                                    Arrays.asList("textarea[name=q]", "input[name=q]"),
                                    Arrays.asList("#pnnext", "a[aria-label='Next page']",
                                            "a[aria-label='Nächste Seite']"))),
                            10),
                    new BrowserSearchEngine(DUCKDUCKGO, "DuckDuckGo", Arrays.asList(
                            // Server-rendered start pages of the SAME provider: the plain HTML one first,
                            // the lite one as its own fallback. One provider to the user.
                            new BrowserSearchEngine.SearchForm("https://html.duckduckgo.com/html/",
                                    Arrays.asList("input[name=q]"),
                                    Arrays.asList(".nav-link input[type=submit]:not([value*='Prev' i])"
                                            + ":not([value*='Vorherig' i]):not([value*='Zurück' i])")),
                            new BrowserSearchEngine.SearchForm("https://lite.duckduckgo.com/lite/",
                                    Arrays.asList("input[name=q]"),
                                    Arrays.asList("input.navbutton[value*='Next' i]",
                                            "input.navbutton[value*='Nächste' i]",
                                            "input.navbutton[value*='Weiter' i]"))),
                            30),
                    new BrowserSearchEngine(BING, "Bing", Arrays.asList(
                            new BrowserSearchEngine.SearchForm("https://www.bing.com/",
                                    Arrays.asList("#sb_form_q", "textarea[name=q]", "input[name=q]"),
                                    Arrays.asList("a.sb_pagN", "a[aria-label='Next page']",
                                            "a[aria-label='Nächste Seite']"))),
                            10),
                    new BrowserSearchEngine(STARTPAGE, "Startpage", Arrays.asList(
                            // Start page with one search field (id q, name query); deeper result pages through
                            // the pagination form's "next" button. Selectors are layout alternatives, tried in order.
                            new BrowserSearchEngine.SearchForm("https://www.startpage.com/",
                                    Arrays.asList("input#q", "input[name=query]", "form#search input[type=search]",
                                            "input[type=search]"),
                                    Arrays.asList("form.pagination__form button.next",
                                            "button.pagination__next-prev-button.next",
                                            "a.pagination__next-prev-button.next",
                                            "button[aria-label='Next']", "button[aria-label='Weiter']",
                                            "button[aria-label='Nächste']"))),
                            10),
                    new BrowserSearchEngine(YAHOO, "Yahoo Search", Arrays.asList(
                            // search.yahoo.com: one search field (id yschsp, name p); deeper pages through the
                            // pagination "next" link. Result links are r.search.yahoo.com wrappers whose target is
                            // in the RU= path segment and is decoded without a request (SearchRedirectResolver).
                            // An EU consent page (guce/consent.yahoo.com) is handled by the general consent step.
                            new BrowserSearchEngine.SearchForm("https://search.yahoo.com/",
                                    Arrays.asList("input#yschsp", "input[name=p]", "input[type=search]"),
                                    Arrays.asList("a.next", "a[aria-label='Next']", "a[aria-label='Weiter']",
                                            "a[aria-label='Nächste']"))),
                            7), // Yahoo shows seven organic results per page
                    new BrowserSearchEngine(YAHOO_JAPAN, "Yahoo! JAPAN", Arrays.asList(
                            // search.yahoo.co.jp: search field name p; next page through the "次へ" link.
                            new BrowserSearchEngine.SearchForm("https://search.yahoo.co.jp/",
                                    Arrays.asList("input[name=p]", "input[type=search]"),
                                    Arrays.asList("a.Pagenation__next", "a:has-text('次へ')"))),
                            10),
                    new BrowserSearchEngine(SEZNAM, "Seznam", Arrays.asList(
                            // search.seznam.cz: search field name q; next page through "Další" (next).
                            new BrowserSearchEngine.SearchForm("https://search.seznam.cz/",
                                    Arrays.asList("input[name=q]", "input[type=search]"),
                                    Arrays.asList("a[rel=next]", "a:has-text('Další')"))),
                            10),
                    new BrowserSearchEngine(YANDEX, "Yandex", Arrays.asList(
                            // yandex.com: search field name text; next page through the pager's "next" item.
                            new BrowserSearchEngine.SearchForm("https://yandex.com/",
                                    Arrays.asList("input[name=text]", "textarea[name=text]", "input[type=search]"),
                                    Arrays.asList("a.Pager-Item_type_next", "a.pager__item_kind_next",
                                            "a[aria-label='Next page']", "a:has-text('дальше')"))),
                            10),
                    new BrowserSearchEngine(NAVER, "Naver", Arrays.asList(
                            // www.naver.com: search field id query; next page through the pager's "다음" (next).
                            new BrowserSearchEngine.SearchForm("https://www.naver.com/",
                                    Arrays.asList("input#query", "input[name=query]", "input[type=search]"),
                                    Arrays.asList("a.btn_next", "a.pg_next", "a:has-text('다음')"))),
                            10),
                    new BrowserSearchEngine(COCCOC, "Cốc Cốc", Arrays.asList(
                            // coccoc.com/search: search field name query; next page through "Trang sau" (next).
                            new BrowserSearchEngine.SearchForm("https://coccoc.com/search",
                                    Arrays.asList("input[name=query]", "input[type=search]"),
                                    Arrays.asList("a[rel=next]", "a:has-text('Trang sau')"))),
                            10),
                    new BrowserSearchEngine(BAIDU, "Baidu", Arrays.asList(
                            // www.baidu.com: search field id kw (name wd); next page through "下一页" (next page).
                            new BrowserSearchEngine.SearchForm("https://www.baidu.com/",
                                    Arrays.asList("input#kw", "input[name=wd]", "textarea#chat-textarea",
                                            "input[type=search]"),
                                    Arrays.asList("a.n:has-text('下一页')", "a:has-text('下一页')"))),
                            10))));

    private BrowserSearchEngineCatalog() {
    }

    /**
     * Whether the engine is searched in AskAI's separate search browser (Google, through Serp4J) instead of by the
     * research browser's page-engine search. The research browser skips such engines.
     */
    public static boolean runsInSearchBrowser(String id) {
        return GOOGLE.equals(id == null ? null : id.trim());
    }

    /** All known engines in catalog order (NOT the execution order — that is the user's). */
    public static List<BrowserSearchEngine> engines() {
        return ENGINES;
    }

    /** @return the engine with this id, or {@code null} when the id is unknown (e.g. an older config). */
    public static BrowserSearchEngine byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim();
        for (BrowserSearchEngine engine : ENGINES) {
            if (engine.getId().equals(wanted)) {
                return engine;
            }
        }
        return null;
    }

    /** The single-endpoint engine an explicit {@code --search-url} template stands for. */
    public static BrowserSearchEngine custom(String urlTemplate) {
        return new BrowserSearchEngine(CUSTOM, "Custom", Collections.singletonList(urlTemplate));
    }
}
