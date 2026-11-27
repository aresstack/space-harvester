package com.aresstack.askai.browser.search.engine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus.EXPERIMENTAL;
import static com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus.NOT_IMPLEMENTED;
import static com.aresstack.askai.browser.search.engine.SearchEngineImplementationStatus.SUPPORTED;

/**
 * Every search engine AskAI knows by name, whether or not it can search with it yet: the international engines, the
 * regional ones (with the languages they matter for) and further search services. The user picks from this catalog
 * when adding a row to Search → Engines; a row of an engine that is not {@link SearchEngineCatalogEntry#isSearchable()
 * searchable} is never offered as working.
 * <p>
 * The catalog describes engines; HOW AskAI drives an implemented engine (search form, selectors, paging) stays in
 * {@link BrowserSearchEngineCatalog}, under the same id. An entry is searchable exactly when that driver exists — the
 * test {@code SearchEngineCatalogTest} keeps the two in step, so implementing an engine means adding its driver there
 * and raising its status here.
 * <p>
 * Default languages are starting values for a new row, not a claim that the engine filters by them. Operating status
 * checked 2026-11; entries whose service could not be confirmed say so in their note instead of being removed.
 */
public final class SearchEngineCatalog {

    public static final String GOOGLE = BrowserSearchEngineCatalog.GOOGLE;
    public static final String BING = BrowserSearchEngineCatalog.BING;
    public static final String YAHOO = BrowserSearchEngineCatalog.YAHOO;
    public static final String DUCKDUCKGO = BrowserSearchEngineCatalog.DUCKDUCKGO;
    public static final String BRAVE = "brave";
    public static final String MOJEEK = "mojeek";
    public static final String QWANT = "qwant";
    public static final String ECOSIA = "ecosia";
    public static final String STARTPAGE = BrowserSearchEngineCatalog.STARTPAGE;
    public static final String SWISSCOWS = "swisscows";
    public static final String SEZNAM = BrowserSearchEngineCatalog.SEZNAM;
    public static final String YANDEX = BrowserSearchEngineCatalog.YANDEX;
    public static final String RAMBLER = "rambler";
    public static final String BAIDU = BrowserSearchEngineCatalog.BAIDU;
    public static final String SOGOU = "sogou";
    public static final String SEARCH_360 = "so360";
    public static final String SHENMA = "shenma";
    public static final String NAVER = BrowserSearchEngineCatalog.NAVER;
    public static final String DAUM = "daum";
    public static final String YAHOO_JAPAN = BrowserSearchEngineCatalog.YAHOO_JAPAN;
    public static final String COCCOC = BrowserSearchEngineCatalog.COCCOC;
    public static final String YAANI = "yaani";
    public static final String KAGI = "kagi";
    public static final String YEP = "yep";
    public static final String METAGER = "metager";
    public static final String SEARXNG = "searxng";
    public static final String MARGINALIA = "marginalia";
    public static final String YACY = "yacy";

    /**
     * The pause before each further Google request, in milliseconds: the "Delay (s)" a Google row starts with under
     * Search → Engines. The search browser keeps it between EVERY Google request (searches, result pages, redirect
     * hops, across all chats). Live run 2026-11-09: 13 searches and about 40 redirect hops in 16 minutes with no
     * pause ended in a challenge that blocked the search browser for the rest of the run; 15 s caps the rate at
     * four requests a minute and spreads the bursts of hops that follow a result page.
     */
    public static final int GOOGLE_REQUEST_DELAY_MILLIS = 15_000;

    /** Engines other than Google take requests as they come; the user adds a pause in the table when wanted. */
    private static final int NO_DELAY = 0;

    private static final String NO_ADAPTER = "No browser adapter yet.";
    private static final String ADAPTER_NOT_VALIDATED = "Search form adapter in the research browser; selectors follow "
            + "the engine's public page structure. Not validated against the live engine yet.";

    private static final List<SearchEngineCatalogEntry> ENTRIES = Collections.unmodifiableList(
            new ArrayList<SearchEngineCatalogEntry>(Arrays.asList(
                    new SearchEngineCatalogEntry(GOOGLE, "Google", "USA", "https://www.google.com/", "Own index",
                            langs("de", "en", "fr", "es", "it"), SUPPORTED,
                            "Searched in AskAI's separate search browser (Serp4J); result links are Google redirect "
                                    + "wrappers that are resolved one at a time when a hit is opened.", false,
                            GOOGLE_REQUEST_DELAY_MILLIS),
                    entry(BING, "Bing", "USA", "https://www.bing.com/", "Own index",
                            langs("de", "en", "fr", "es"), SUPPORTED,
                            "Search form adapter in the research browser. Validated live on 2026-11-17."),
                    entry(YAHOO, "Yahoo Search", "USA", "https://search.yahoo.com/", "Mostly Bing",
                            langs("en", "de"), SUPPORTED,
                            "Search form adapter in the research browser; result wrappers (r.search.yahoo.com, RU=) "
                                    + "are decoded without a request. Validated live on 2026-11-17."),
                    entry(DUCKDUCKGO, "DuckDuckGo", "USA", "https://duckduckgo.com/",
                            "Mostly Bing plus own data", langs("de", "en"), SUPPORTED,
                            "Searched through its HTML and Lite start pages in the research browser."),
                    entry(BRAVE, "Brave Search", "USA", "https://search.brave.com/", "Own index",
                            langs("de", "en", "fr"), NOT_IMPLEMENTED,
                            "No browser adapter yet; the Brave Search API is available as a search provider."),
                    entry(MOJEEK, "Mojeek", "United Kingdom", "https://www.mojeek.com/", "Own index",
                            langs("en", "de"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(QWANT, "Qwant", "France", "https://www.qwant.com/", "Mixed (own index and Bing)",
                            langs("fr", "en", "de"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(ECOSIA, "Ecosia", "Germany", "https://www.ecosia.org/",
                            "Bing, Google and a European index", langs("de", "en", "fr"), NOT_IMPLEMENTED,
                            NO_ADAPTER),
                    entry(STARTPAGE, "Startpage", "Netherlands", "https://www.startpage.com/", "Mostly Google",
                            langs("de", "en", "fr"), SUPPORTED,
                            "Search form adapter in the research browser: Google results with direct target URLs "
                                    + "(no redirect wrappers). Validated live on 2026-11-17."),
                    entry(SWISSCOWS, "Swisscows", "Switzerland", "https://swisscows.com/",
                            "Own and partner data", langs("de", "en", "fr"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(SEZNAM, "Seznam", "Czech Republic", "https://search.seznam.cz/", "Own index",
                            langs("cs", "sk"), EXPERIMENTAL, ADAPTER_NOT_VALIDATED),
                    entry(YANDEX, "Yandex", "Russia", "https://yandex.com/", "Own index",
                            langs("ru", "en"), SUPPORTED, "Search form adapter in the research browser. Validated live on 2026-11-17."),
                    entry(RAMBLER, "Rambler", "Russia", "https://www.rambler.ru/", "Yandex-based",
                            langs("ru"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(BAIDU, "Baidu", "China", "https://www.baidu.com/", "Own index",
                            langs("zh-CN"), EXPERIMENTAL,
                            "Search form adapter in the research browser. Its hit links are opaque baidu.com/link?url= "
                                    + "wrappers; the target is read offline from the result block's own mu attribute, "
                                    + "never by a request to Baidu, and a hit without it is dropped. Not validated "
                                    + "against the live engine yet."),
                    entry(SOGOU, "Sogou", "China", "https://www.sogou.com/", "Own and partner data",
                            langs("zh-CN"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(SEARCH_360, "360 Search", "China", "https://www.so.com/", "Own service",
                            langs("zh-CN"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(SHENMA, "Shenma", "China", "https://m.sm.cn/", "Mobile search engine",
                            langs("zh-CN"), NOT_IMPLEMENTED, "No browser adapter yet; a mobile-only search site."),
                    entry(NAVER, "Naver", "South Korea", "https://search.naver.com/", "Own search",
                            langs("ko"), SUPPORTED, "Search form adapter in the research browser. Validated live on 2026-11-17."),
                    entry(DAUM, "Daum", "South Korea", "https://search.daum.net/", "Own and partner data",
                            langs("ko"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(YAHOO_JAPAN, "Yahoo! JAPAN", "Japan", "https://search.yahoo.co.jp/", "Google-based",
                            langs("ja"), EXPERIMENTAL,
                            "Search form adapter in the research browser (Japanese text is typed as Unicode, no "
                                    + "input method needed). Not validated against the live engine yet."),
                    entry(COCCOC, "Cốc Cốc", "Vietnam", "https://coccoc.com/search", "Own index",
                            langs("vi"), EXPERIMENTAL, ADAPTER_NOT_VALIDATED),
                    entry(YAANI, "Yaani", "Turkey", "https://www.yaani.com.tr/", "Own service",
                            langs("tr"), NOT_IMPLEMENTED,
                            "No browser adapter yet; current operation could not be confirmed (checked 2026-11)."),
                    entry(KAGI, "Kagi", "USA", "https://kagi.com/", "Own index plus other sources",
                            langs("en", "de"), NOT_IMPLEMENTED,
                            "No adapter yet; a paid service that needs the user's account."),
                    entry(YEP, "Yep", "USA", "https://yep.com/", "Own index (Ahrefs)",
                            langs("en"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(METAGER, "MetaGer", "Germany", "https://metager.de/", "Meta search",
                            langs("de", "en"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(SEARXNG, "SearXNG", "Open source", "https://docs.searxng.org/",
                            "Self-hostable meta search", langs("de", "en"), NOT_IMPLEMENTED,
                            "No adapter yet; needs the address of a concrete SearXNG instance."),
                    entry(MARGINALIA, "Marginalia Search", "Sweden", "https://search.marginalia.nu/",
                            "Own index of the independent web", langs("en"), NOT_IMPLEMENTED, NO_ADAPTER),
                    entry(YACY, "YaCy", "Germany", "https://yacy.net/", "Decentralised peer-to-peer index",
                            langs("de", "en"), NOT_IMPLEMENTED,
                            "No adapter yet; needs the address of a YaCy peer."))));

    private SearchEngineCatalog() {
    }

    /** All catalog engines in catalog order (not an execution order). */
    public static List<SearchEngineCatalogEntry> entries() {
        return ENTRIES;
    }

    /** @return the entry with this id, or {@code null} for an id the catalog does not know */
    public static SearchEngineCatalogEntry byId(String id) {
        if (id == null) {
            return null;
        }
        String wanted = id.trim();
        for (SearchEngineCatalogEntry entry : ENTRIES) {
            if (entry.getId().equals(wanted)) {
                return entry;
            }
        }
        return null;
    }

    /** The name to show for an id: the catalog name, else the id itself (a row written by a newer build). */
    public static String displayName(String id) {
        SearchEngineCatalogEntry entry = byId(id);
        return entry != null ? entry.getDisplayName() : String.valueOf(id);
    }

    /** The starting languages of a new row of this engine, or an empty list for an unknown id. */
    public static List<String> defaultLanguages(String id) {
        SearchEngineCatalogEntry entry = byId(id);
        return entry != null ? entry.getDefaultLanguages() : Collections.<String>emptyList();
    }

    /**
     * The delay a row of this engine starts with when its configuration names none
     * ({@link SearchEngineCatalogEntry#getDefaultDelayMillis()}); 0 for an engine the catalog does not know.
     */
    public static int defaultDelayMillis(String id) {
        SearchEngineCatalogEntry entry = byId(id);
        return entry != null ? entry.getDefaultDelayMillis() : 0;
    }

    private static SearchEngineCatalogEntry entry(String id, String name, String origin, String homeUrl,
                                                  String index, List<String> languages,
                                                  SearchEngineImplementationStatus status, String note) {
        return new SearchEngineCatalogEntry(id, name, origin, homeUrl, index, languages, status, note, false,
                NO_DELAY);
    }

    private static List<String> langs(String... codes) {
        return Arrays.asList(codes);
    }
}
