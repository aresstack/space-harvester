package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.playwright.BrowserLauncher;
import com.aresstack.askai.browser.playwright.ChatBrowserSearch;
import com.aresstack.askai.browser.playwright.SearchBrowser;
import com.aresstack.askai.browser.playwright.SearchUrlMemory;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.browser.search.SearchResultCandidate;
import com.aresstack.askai.browser.search.analysis.UnavailableStructuredInferencePort;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineCatalog;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.inference.CancellationSignal;
import com.aresstack.askai.research.runtime.loop.ToolInvoker;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.WebSearchPort;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * The web search of AskAI's research for the chat browsers: the "Browser (SERP)" search
 * ({@link BrowserEnginesSearchStrategy}: priority groups, enough usable results) with the engines asked in tabs of the
 * chat's own browser (Angelo 28.11.2026: one browser, the search as a tab; the research browser's engine search,
 * {@link LegacyBrowserSearchStrategy} over {@link ChatBrowserSearch}). The search tabs close again after the search,
 * the chat's tabs stay as they are. Google alone still runs in AskAI's search browser ({@link SearchBrowser}, Serp4J).
 *
 * <p>The host gives each chat its engine table ({@code selections}), for example one per browser profile. A row may
 * name a search page of the host's own ({@link BrowserSearchEngineCatalog#isSearchPage}), such as an intranet portal.
 * The browser policy of the chat ({@code refused}) leaves out every engine whose pages it refuses and every hit it
 * refuses. One search at a time; the engines are searched with the chat's table (pages and pause per row). The result
 * URLs the search pages' own fields led to are kept here, across the chat browsers that end with each answer.</p>
 */
public final class SearchBrowserWebSearch implements WebSearchPort, AutoCloseable {

    /** The placeholders of an endpoint template ({query}, {page}, ...), dropped before its address is checked. */
    private static final Pattern ENDPOINT_PLACEHOLDER = Pattern.compile("\\{[^}]*}");

    private final BrowserLauncher launcher;
    private final BooleanSupplier headless;
    private final Supplier<LegacyBrowserSearchSettings> settings;
    private final Function<ChatConversationId, BrowserSearchEngineSelection> selections;
    private final GoogleSearchForEngineTable google;
    /** One search at a time. */
    private final Object searching = new Object();
    /**
     * Guards Google's search browser only, never held while a search waits: the search's parallel lanes start and
     * use it from worker threads.
     */
    private final Object browsers = new Object();
    private SearchBrowser searchBrowser;
    /** The search pages' result URLs, kept across the chats' browsers. */
    private final SearchUrlMemory searchUrls = new SearchUrlMemory();
    private volatile boolean closed;

    /**
     * @param launcher   the host's browser runtime Google's search browser starts from (throw-away profile)
     * @param headless   whether Google's search browser runs without a window (read at each start)
     * @param settings   the browser search settings (consent, captcha, extraction, enough results, language)
     * @param selections the engine table of a chat; an empty table searches nothing
     */
    public SearchBrowserWebSearch(BrowserLauncher launcher, BooleanSupplier headless,
                                  Supplier<LegacyBrowserSearchSettings> settings,
                                  Function<ChatConversationId, BrowserSearchEngineSelection> selections) {
        if (launcher == null || headless == null || settings == null || selections == null) {
            throw new IllegalArgumentException("launcher, headless, settings and selections must not be null");
        }
        this.launcher = launcher;
        this.headless = headless;
        this.settings = settings;
        this.selections = selections;
        this.google = new GoogleSearchForEngineTable((resultPages, delayMillis) -> GoogleSearchBrowserStrategy
                .create(searchBrowser(), Locale.getDefault(), null, resultPages, delayMillis));
    }

    @Override
    public String search(ChatConversationId chat, BrowserInstance browser, String query, List<String> engines,
                         Predicate<String> refused) throws BrowserException {
        synchronized (searching) {
            return searchNow(chat, browser, query, engines, refused);
        }
    }

    private String searchNow(ChatConversationId chat, BrowserInstance browser, String query, List<String> engines,
                             Predicate<String> refused) throws BrowserException {
        if (closed) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Die Websuche ist beendet");
        }
        BrowserSearchEngineSelection table = selections.apply(chat);
        LegacyBrowserSearchSettings current = withTable(settings.get(), table);
        BrowserSearchEngineSelection selection = admitted(table, engines, refused);
        ToolInvoker tabs = tabs(browser, current);
        pollParkedChallenge(tabs);
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(selection,
                current.navigation.minimumUsableResults, google.forSelection(selection),
                LegacyBrowserSearchStrategyFactory.createDefault(tabs, current, System::currentTimeMillis,
                        new UnavailableStructuredInferencePort()));
        if (!strategy.hasSteps()) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Für diesen Chat ist keine Suchmaschine eingestellt (Einstellungen → Browser)");
        }
        InitialSearchResult result;
        try {
            result = strategy.search(new InitialSearchRequest(query, current.navigation.searchResultLimit,
                    current.navigation.language, current.navigation.country), CancellationSignal.NONE, () -> true);
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException e) {
            throw new BrowserException(BrowserException.Kind.FAILED, "Websuche fehlgeschlagen: " + e.getMessage(), e);
        }
        return render(result, refused);
    }

    /**
     * {@code base} with the chat's engine table, so the page-engine browser takes pages and pause from its rows
     * ({@code null}: {@code base} as it is).
     */
    static LegacyBrowserSearchSettings withTable(LegacyBrowserSearchSettings base,
                                                 BrowserSearchEngineSelection table) {
        if (table == null) {
            return base;
        }
        Map<String, String> values = LegacyBrowserSearchSettingsCodec.toValues(base);
        values.put("navigation.engines", table.encodeEntries());
        values.put("navigation.engineAcquisitionMode", table.getMode().name());
        return LegacyBrowserSearchSettingsCodec.fromValues(values).settings;
    }

    /**
     * Asks the chat's browser about a parked challenge before the next search: a solved or closed one frees its engine
     * again (the search itself never asks).
     */
    private static void pollParkedChallenge(ToolInvoker tabs) {
        try {
            tabs.call("web_challenge_status", Collections.<String, Object>emptyMap());
        } catch (ToolInvoker.ToolFailure | ToolInvoker.EndpointUnavailable | RuntimeException e) {
            // Nothing else depends on the poll; the search reports its own failures.
        }
    }

    /**
     * The rows of {@code selection} the search may use: those named in {@code engines} ({@code null}: all), without
     * the engines whose pages {@code refused} refuses. An engine is refused when one of its endpoints is.
     */
    static BrowserSearchEngineSelection admitted(BrowserSearchEngineSelection selection, List<String> engines,
                                                 Predicate<String> refused) throws BrowserException {
        List<BrowserSearchEngineSelection.Entry> kept = new ArrayList<BrowserSearchEngineSelection.Entry>();
        List<String> known = new ArrayList<String>();
        List<String> skipped = new ArrayList<String>();
        if (selection != null) {
            for (BrowserSearchEngineSelection.Entry entry : selection.getEntries()) {
                if (!entry.isEnabled() || BrowserSearchEngineCatalog.byId(entry.getEngineId()) == null) {
                    continue;
                }
                known.add(entry.getEngineId());
                if (engines != null && !engines.contains(entry.getEngineId())) {
                    continue;
                }
                if (refusesEngine(entry.getEngineId(), refused)) {
                    skipped.add(entry.getEngineId());
                } else {
                    kept.add(entry);
                }
            }
        }
        if (engines != null) {
            for (String engine : engines) {
                if (!known.contains(engine)) {
                    throw new BrowserException(BrowserException.Kind.INVALID_REQUEST, "Unbekannte Suchmaschine: \""
                            + engine + "\" (eingestellt: " + String.join(", ", known) + ")");
                }
            }
        }
        if (kept.isEmpty() && !skipped.isEmpty()) {
            throw new BrowserException(BrowserException.Kind.BLOCKED, "Die Browser-Richtlinie sperrt alle "
                    + "Suchmaschinen dieser Suche (" + String.join(", ", skipped) + "; Einstellungen → Browser).");
        }
        return new BrowserSearchEngineSelection(kept,
                selection == null ? null : selection.getMode());
    }

    private static boolean refusesEngine(String engineId, Predicate<String> refused) {
        for (String endpoint : BrowserSearchEngineCatalog.byId(engineId).getEndpointTemplates()) {
            if (refused.test(ENDPOINT_PLACEHOLDER.matcher(endpoint).replaceAll(""))) {
                return true;
            }
        }
        return false;
    }

    /** The hits as text (refused ones left out), then a pending challenge, as the chat's search tool answers. */
    static String render(InitialSearchResult result, Predicate<String> refused) {
        StringBuilder text = new StringBuilder();
        int number = 0;
        for (SearchResultCandidate candidate : result.candidates) {
            String url = candidate.resolvedTargetUrl != null && !candidate.resolvedTargetUrl.isEmpty()
                    ? candidate.resolvedTargetUrl : candidate.rawSearchHref;
            if (url == null || url.isEmpty() || refused.test(url)) {
                continue;
            }
            number++;
            text.append('r').append(number).append(": ").append(candidate.title == null ? "" : candidate.title)
                    .append(" — ").append(url).append('\n');
            if (candidate.snippet != null && !candidate.snippet.isEmpty()) {
                text.append("   ").append(candidate.snippet).append('\n');
            }
        }
        for (com.aresstack.askai.browser.search.repair.SearchChallengeState challenge : result.challenges) {
            text.append("CHALLENGE: ").append(challenge.family).append(' ').append(challenge.url).append('\n');
        }
        if (text.length() > 0) {
            return text.toString();
        }
        if (result.status == InitialSearchStatus.TECHNICAL_PROBLEM && !result.diagnostics.isEmpty()) {
            return "Keine Treffer: " + String.join("; ", result.diagnostics);
        }
        return "Keine Treffer.";
    }

    /** AskAI's search browser (Google), created on first use. */
    private SearchBrowser searchBrowser() {
        synchronized (browsers) {
            if (searchBrowser == null) {
                searchBrowser = new SearchBrowser(launcher, headless);
            }
            return searchBrowser;
        }
    }

    /** The engines' tabs in the chat's {@code browser}, searching with {@code current} (the chat's engine table). */
    private ToolInvoker tabs(BrowserInstance browser, LegacyBrowserSearchSettings current) throws BrowserException {
        ChatBrowserSearch search;
        try {
            search = ChatBrowserSearch.in(browser, current, searchUrls);
        } catch (com.aresstack.askai.browser.BrowserException e) {
            throw new BrowserException(BrowserException.Kind.FAILED, "Websuche fehlgeschlagen: " + e.getMessage(), e);
        }
        if (search == null) {
            throw new BrowserException(BrowserException.Kind.INVALID_REQUEST,
                    "Der Browser dieses Chats kann keine Suchmaschine bedienen");
        }
        final ChatBrowserSearch inChat = search;
        return new ToolInvoker() {
            @Override
            public String call(String toolName, Map<String, Object> args) throws ToolFailure {
                try {
                    return inChat.call(toolName, args);
                } catch (com.aresstack.askai.browser.BrowserException e) {
                    throw new ToolFailure(e.getMessage());
                }
            }

            @Override
            public List<String> toolNames() {
                return ChatBrowserSearch.toolNames();
            }
        };
    }

    @Override
    public void close() {
        closed = true;
        synchronized (browsers) {
            if (searchBrowser != null) {
                searchBrowser.close();
                searchBrowser = null;
            }
        }
    }
}
