package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.playwright.BrowserLauncher;
import com.aresstack.askai.browser.playwright.PlaywrightBrowserRuntime;
import com.aresstack.askai.browser.playwright.SearchBrowser;
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
 * The web search of AskAI's research for the chat browsers, in browsers of its own: the "Browser (SERP)" search
 * ({@link BrowserEnginesSearchStrategy}: priority groups, enough usable results) with Google in AskAI's search browser
 * ({@link SearchBrowser}, Serp4J) and the other engines in a search browser of their own (the research browser's
 * engine search, {@link LegacyBrowserSearchStrategy} over a {@link PlaywrightBrowserRuntime}). The chats' browsers
 * only open the hits afterwards.
 *
 * <p>The host gives each chat its engine table ({@code selections}), for example one per browser profile. A row may
 * name a search page of the host's own ({@link BrowserSearchEngineCatalog#isSearchPage}), such as an intranet portal.
 * The browser policy of the chat ({@code refused}) leaves out every engine whose pages it refuses and every hit it
 * refuses. One search at a time; both browsers start on first use and again after they ended. The page-engine browser
 * searches with the chat's table (pages and pause per row) and starts anew when that table changed.</p>
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
     * Guards the two browsers only, never held while a search waits: the search's parallel lanes start and use the
     * browsers from worker threads.
     */
    private final Object browsers = new Object();
    private SearchBrowser searchBrowser;
    private PlaywrightBrowserRuntime pages;
    /** The settings the page-engine browser runs with ({@link LegacyBrowserSearchSettingsCodec#digest}). */
    private String pagesSettings;
    private volatile boolean closed;

    /**
     * @param launcher   the host's browser runtime both search browsers start from (throw-away profiles)
     * @param headless   whether the search browsers run without a window (read at each start)
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
    public String search(ChatConversationId chat, String query, List<String> engines,
                         Predicate<String> refused) throws BrowserException {
        synchronized (searching) {
            return searchNow(chat, query, engines, refused);
        }
    }

    private String searchNow(ChatConversationId chat, String query, List<String> engines,
                             Predicate<String> refused) throws BrowserException {
        if (closed) {
            throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Die Websuche ist beendet");
        }
        BrowserSearchEngineSelection table = selections.apply(chat);
        LegacyBrowserSearchSettings current = withTable(settings.get(), table);
        BrowserSearchEngineSelection selection = admitted(table, engines, refused);
        pollParkedChallenge();
        BrowserEnginesSearchStrategy strategy = new BrowserEnginesSearchStrategy(selection,
                current.navigation.minimumUsableResults, google.forSelection(selection),
                LegacyBrowserSearchStrategyFactory.createDefault(pages(current), current, System::currentTimeMillis,
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
     * Asks the page-engine browser about a parked challenge before the next search: a solved or closed one frees its
     * engine again (the search itself never asks).
     */
    private void pollParkedChallenge() {
        PlaywrightBrowserRuntime running;
        synchronized (browsers) {
            running = pages;
        }
        if (running == null || !running.isAlive()) {
            return;
        }
        try {
            running.call("web_challenge_status", Collections.<String, Object>emptyMap());
        } catch (com.aresstack.askai.browser.BrowserException | RuntimeException e) {
            // The search starts the browser anew when it is gone; nothing else depends on the poll.
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

    /** The search browser of the other engines (the research browser's engine search), started when needed. */
    private ToolInvoker pages(LegacyBrowserSearchSettings current) {
        return new ToolInvoker() {
            @Override
            public String call(String toolName, Map<String, Object> args)
                    throws ToolFailure, EndpointUnavailable {
                PlaywrightBrowserRuntime runtime = runtime(current);
                try {
                    return runtime.call(toolName, args);
                } catch (com.aresstack.askai.browser.BrowserException e) {
                    throw new ToolFailure(e.getMessage());
                }
            }

            @Override
            public List<String> toolNames() {
                return PlaywrightBrowserRuntime.toolNames();
            }
        };
    }

    private PlaywrightBrowserRuntime runtime(LegacyBrowserSearchSettings current)
            throws ToolInvoker.EndpointUnavailable {
        synchronized (browsers) {
            String wanted = LegacyBrowserSearchSettingsCodec.digest(current);
            if (pages != null && pages.isAlive() && !wanted.equals(pagesSettings)) {
                pages.close(); // another table (profile or edited row): start with its pages and pauses
                pages = null;
            }
            if (pages == null || !pages.isAlive()) {
                try {
                    // Private networks allowed: the host's own search pages may be intranet portals. Which
                    // addresses a chat may see stays with its browser policy (refused engines and hits).
                    pages = PlaywrightBrowserRuntime.start(new PlaywrightBrowserRuntime.Options(launcher,
                            headless.getAsBoolean(), true, null, current, false));
                    pagesSettings = wanted;
                } catch (PlaywrightBrowserRuntime.StartFailure failed) {
                    throw new ToolInvoker.EndpointUnavailable("Suchbrowser startet nicht: " + failed.readiness());
                }
            }
            return pages;
        }
    }

    @Override
    public void close() {
        closed = true;
        synchronized (browsers) {
            if (pages != null) {
                pages.close();
                pages = null;
            }
            if (searchBrowser != null) {
                searchBrowser.close();
                searchBrowser = null;
            }
        }
    }
}
