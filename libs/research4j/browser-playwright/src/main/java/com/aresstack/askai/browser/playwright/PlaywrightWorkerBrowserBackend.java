package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.domain.HostPortDomainKeyResolver;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * The productive {@link WorkerBrowserBackend}: one browser process with a throw-away profile from the host's
 * {@link BrowserLauncher} (its own Playwright instance and driver child), created on the lane's owner thread. Every worker user context is a
 * {@code browser.newContext()} — on the Camoufox/Firefox path Playwright speaks WebDriver BiDi and maps it to
 * {@code browser.createUserContext} (own cookie jar, local storage, cache partition); every tab is a
 * {@code context.newPage()}, i.e. {@code browsingContext.create} with that {@code userContext}. Each tab gets
 * the unchanged session stack of the chat browser: {@link Playwright4jDriver} (scoped to its page),
 * {@link PlaywrightBrowserSession} (URL policy before and after redirects, limits, SERP guards) and the flat
 * {@link BrowserToolSet} including the layout-repair tools.
 *
 * <p>Why a separate process and not more contexts in the chat browser: the chat browser runs on a
 * PERSISTENT context (its profile), whose browser Playwright starts as one unit; and Playwright Java runs one
 * call at a time per Playwright instance. Parallel worker navigation therefore needs several Playwright
 * instances — each one is a worker browser (lane), each lane hosts several user contexts.</p>
 *
 * <p>Worker browsers load the AskAI Research add-on as well where the launcher can load it, with a bridge of their
 * own that only carries page reads (no HUD): the tabs' structured capture and SERP guard detection run in the add-on's isolated
 * content-script world, as in the chat browser (docs/research-browser-addon.md).</p>
 */
final class PlaywrightWorkerBrowserBackend implements WorkerBrowserBackend {

    /** One user context and its tabs. */
    private static final class UserContext {
        final BrowserContext context;
        final List<String> tabs = new ArrayList<String>();

        UserContext(BrowserContext context) {
            this.context = context;
        }
    }

    /** One tab: its page-scoped driver and the tool set over the tab's session. */
    private static final class Tab {
        final String userContextId;
        final Playwright4jDriver driver;
        final BrowserToolSet tools;

        Tab(String userContextId, Playwright4jDriver driver, BrowserToolSet tools) {
            this.userContextId = userContextId;
            this.driver = driver;
            this.tools = tools;
        }
    }

    private final PlaywrightBrowserRuntime.Options options;
    /** The worker browser process from the host's launcher; closed with this backend. */
    private final LaunchedBrowser launched;
    private final Browser browser;
    private final boolean camoufox;
    /**
     * On by default: worker contexts refuse every search engine host (navigation requests aborted, policy refuses
     * before and after redirects, web_search and web_search_prepare refused). Searches go through the central search
     * service and its one search browser. Tests of a local search world may switch it off.
     */
    private volatile boolean refuseSearchEngines = true;
    private final int timeoutMillis;
    /** With the add-on: the page-read bridge of this worker browser, else null. */
    private final AddonBridge readBridge;
    private final Map<String, UserContext> userContexts = new LinkedHashMap<String, UserContext>();
    private final Map<String, Tab> tabs = new LinkedHashMap<String, Tab>();
    private long nextId;
    private volatile boolean closed;

    private PlaywrightWorkerBrowserBackend(PlaywrightBrowserRuntime.Options options, LaunchedBrowser launched,
                                           AddonBridge readBridge) {
        this.readBridge = readBridge;
        this.options = options;
        this.launched = launched;
        this.browser = launched.browser();
        this.camoufox = launched.camoufox();
        this.timeoutMillis = options.settings.navigation.navigationCommitTimeoutMillis;
    }

    static Factory factory(final PlaywrightBrowserRuntime.Options options) {
        return new Factory() {
            public WorkerBrowserBackend launch() throws BrowserException {
                return PlaywrightWorkerBrowserBackend.launch(options);
            }
        };
    }

    /** A worker browser from the host's launcher: a throw-away profile, the add-on for page reads when it loads. */
    static PlaywrightWorkerBrowserBackend launch(PlaywrightBrowserRuntime.Options options) throws BrowserException {
        final AddonBridge bridge = Playwright4jDriver.startAddonBridge("research add-on for a worker browser");
        AddonPackager addon = bridge == null ? null : AddonPackager.research(bridge, false);
        LaunchedBrowser launched;
        try {
            launched = options.launcher.ephemeral(options.headless, addon);
        } catch (BrowserException | RuntimeException failed) {
            if (bridge != null) {
                bridge.close();
            }
            throw new BrowserException("Worker browser start failed: " + firstLine(failed));
        }
        AddonBridge readBridge = bridge;
        if (bridge != null && !launched.addonLoaded()) {
            // The worker browser runs on; its tabs then read pages via page.evaluate.
            System.err.println("[browser-mcp] research add-on not loaded in a worker browser: "
                    + launched.addonFailure());
            bridge.close();
            readBridge = null;
        }
        return new PlaywrightWorkerBrowserBackend(options, launched, readBridge);
    }

    @Override
    public String newUserContext(String storageState) throws BrowserException {
        requireOpen();
        try {
            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions().setAcceptDownloads(false);
            if (camoufox) {
                // As the chat browser: no Playwright emulation, the Camoufox fingerprint owns window and screen.
                contextOptions.setViewportSize(null).setColorScheme(null).setReducedMotion(null)
                        .setForcedColors(null).setContrast(null);
            }
            if (storageState != null && !storageState.trim().isEmpty()) {
                contextOptions.setStorageState(storageState);
            }
            BrowserContext context = browser.newContext(contextOptions);
            context.setDefaultNavigationTimeout(timeoutMillis);
            context.setDefaultTimeout(timeoutMillis);
            final boolean refuseEngines = refuseSearchEngines;
            if (options.filtersPrivateTargets() || refuseEngines) {
                final Predicate<String> allowed = options.filtersPrivateTargets()
                        ? new PlaywrightSessionFactory.PrivateTargetRequestFilter() : url -> true;
                context.route("**/*", route -> {
                    if (refuseEngines && route.request().isNavigationRequest()
                            && com.aresstack.askai.browser.search.engine.SearchEngineHosts.isEngineHost(
                            route.request().url())) {
                        System.err.println("[browser-worker] " + com.aresstack.askai.browser
                                .EngineHostRefusedException.CODE + ": navigation request aborted: "
                                + route.request().url());
                        route.abort();
                    } else if (allowed.test(route.request().url())) {
                        route.resume();
                    } else {
                        route.abort();
                    }
                });
            }
            String id = "uc-" + (++nextId);
            userContexts.put(id, new UserContext(context));
            return id;
        } catch (PlaywrightException ex) {
            throw new BrowserException("Creating the worker user context failed: " + firstLine(ex));
        }
    }

    /** Worker contexts created from now on refuse search engine hosts (see {@link #refuseSearchEngines}). */
    public void setRefuseSearchEngines(boolean refuse) {
        this.refuseSearchEngines = refuse;
    }

    @Override
    public String newBrowsingContext(String userContextId) throws BrowserException {
        requireOpen();
        UserContext userContext = userContexts.get(userContextId);
        if (userContext == null) {
            throw new BrowserException("Unknown or closed user context: " + userContextId);
        }
        try {
            Page page = userContext.context.newPage();
            Playwright4jDriver driver = Playwright4jDriver.inBrowsingContext(browser, userContext.context, page,
                    timeoutMillis, options.settings.consent, options.settings.captcha, camoufox);
            driver.setRenderedCapture(new RenderedPageDocumentCapture(options.settings.analysis));
            driver.readThroughAddon(readBridge);
            // Worker contexts never reach a search engine: workers search with the concept phase's web search tool.
            UrlSafetyPolicy policy = options.urlPolicy();
            if (refuseSearchEngines) {
                policy = policy.refusingSearchEngines(java.util.Collections.<String>emptySet());
            }
            PlaywrightBrowserSession session = new PlaywrightBrowserSession(driver, policy, BrowserLimits.defaults(),
                    options.searchUrlTemplate, null, options.settings);
            if (options.hostPortDomainKeys) {
                session.setDomainKeyResolver(new HostPortDomainKeyResolver());
            }
            SearchLayoutRepairTools repairTools = new SearchLayoutRepairTools(options.settings,
                    new PlaywrightRenderedPageSource(session), new LongSupplier() {
                        public long getAsLong() {
                            return System.currentTimeMillis();
                        }
                    });
            String id = "bc-" + (++nextId);
            tabs.put(id, new Tab(userContextId, driver, new BrowserToolSet(session, repairTools)));
            userContext.tabs.add(id);
            return id;
        } catch (PlaywrightException ex) {
            throw new BrowserException("Opening a browsing context failed: " + firstLine(ex));
        }
    }

    @Override
    public String call(String browsingContextId, String tool, Map<String, Object> arguments)
            throws BrowserException {
        requireOpen();
        Tab tab = tabs.get(browsingContextId);
        if (tab == null) {
            throw new BrowserException("Unknown or closed browsing context: " + browsingContextId);
        }
        return tab.tools.call(tool, arguments);
    }

    @Override
    public String storageState(String userContextId) throws BrowserException {
        requireOpen();
        UserContext userContext = userContexts.get(userContextId);
        if (userContext == null) {
            throw new BrowserException("Unknown or closed user context: " + userContextId);
        }
        try {
            return userContext.context.storageState();
        } catch (PlaywrightException ex) {
            throw new BrowserException("Reading the user context state failed: " + firstLine(ex));
        }
    }

    /** Tests: the page-scoped driver of a tab, or null. */
    Playwright4jDriver driverOf(String browsingContextId) {
        Tab tab = tabs.get(browsingContextId);
        return tab == null ? null : tab.driver;
    }

    /** Tests: the page-read bridge of this worker browser, or null without the add-on. */
    AddonBridge readBridge() {
        return readBridge;
    }

    @Override
    public void closeBrowsingContext(String browsingContextId) {
        Tab tab = tabs.remove(browsingContextId);
        if (tab == null) {
            return;
        }
        UserContext userContext = userContexts.get(tab.userContextId);
        if (userContext != null) {
            userContext.tabs.remove(browsingContextId);
        }
        tab.driver.close();
    }

    @Override
    public void closeUserContext(String userContextId) {
        UserContext userContext = userContexts.remove(userContextId);
        if (userContext == null) {
            return;
        }
        for (String tabId : new ArrayList<String>(userContext.tabs)) {
            Tab tab = tabs.remove(tabId);
            if (tab != null) {
                tab.driver.close();
            }
        }
        try {
            userContext.context.close(); // browser.removeUserContext: cookies and storage are gone
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public boolean pumpEvents(BooleanSupplier wake, long timeoutMillis) {
        if (closed || tabs.isEmpty()) {
            return false;
        }
        // Any open tab drives the ONE connection's message loop; events of every user context dispatch.
        for (Tab tab : tabs.values()) {
            if (tab.driver.pumpEvents(wake, timeoutMillis)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isConnected() {
        return !closed && browser != null && browser.isConnected();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        for (String id : new ArrayList<String>(userContexts.keySet())) {
            closeUserContext(id);
        }
        closed = true;
        launched.close();
        if (readBridge != null) {
            readBridge.close();
        }
    }

    @Override
    public void terminate() {
        if (closed) {
            return;
        }
        closed = true;
        // As the chat browser's emergency path: cut the Playwright connection off the owner thread, which
        // fails the stuck call and ends the driver child (and with it the browser).
        final LaunchedBrowser toCut = launched;
        final AddonBridge bridgeToClose = readBridge;
        Thread cutter = new Thread(new Runnable() {
            public void run() {
                toCut.abandon();
                if (bridgeToClose != null) {
                    bridgeToClose.close();
                }
            }
        }, "worker-browser-terminate");
        cutter.setDaemon(true);
        cutter.start();
    }

    private void requireOpen() throws BrowserException {
        if (closed || browser == null || !browser.isConnected()) {
            throw new BrowserException(WorkerBrowserLane.LOST_MARKER + " — the worker browser is closed.");
        }
    }

    private static String firstLine(Throwable ex) {
        String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline).trim() : message.trim();
    }
}
