package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserEngine;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;
import com.microsoft.playwright.BrowserContext;

import java.nio.file.Path;

/**
 * The engine of the chat browser tools (browser-tools) on Playwright: one visible browser per chat profile, started
 * by the host's {@link BrowserLauncher} on a lasting profile of its own, so sign-ins and cookies stay with the chat
 * and never travel between chats. Which browser, its program file, where profiles live and how long a start may take
 * are the launcher's configuration; how long a navigation may take comes from the host; nothing here is fixed.
 */
public final class PlaywrightChatEngine implements BrowserEngine {

    private final BrowserLauncher launcher;
    private final boolean headless;
    private final long navigationTimeoutMillis;
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
    /** Dev/test only: one local search engine URL with {@code {query}}, host:port domain families; null in production. */
    private String testSearchUrlTemplate;

    /**
     * @param launcher                the host's browser runtime
     * @param headless                run without a window (tests only; the chat browser is visible)
     * @param navigationTimeoutMillis how long one navigation may take
     */
    public PlaywrightChatEngine(BrowserLauncher launcher, boolean headless, long navigationTimeoutMillis) {
        this(launcher, headless, navigationTimeoutMillis, null);
    }

    /**
     * @param searchSettings the search engines and SERP handling of {@code browser_search} ({@code null} = the
     *                       central defaults, {@link com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults})
     */
    public PlaywrightChatEngine(BrowserLauncher launcher, boolean headless, long navigationTimeoutMillis,
                                com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings) {
        if (launcher == null) {
            throw new IllegalArgumentException("a browser launcher is required");
        }
        this.searchSettings = searchSettings == null
                ? com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create() : searchSettings;
        this.launcher = launcher;
        this.headless = headless;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    /** Dev/test only: search with one local engine template and key domains by host:port. */
    PlaywrightChatEngine searchingLocally(String searchUrlTemplate) {
        this.testSearchUrlTemplate = searchUrlTemplate;
        return this;
    }

    /**
     * Whether the launcher's browser can start here: {@code READY …} or the reason why not, e.g.
     * {@code BROWSER_NOT_INSTALLED: …}.
     */
    public String readiness() {
        return launcher.readiness().render();
    }

    /**
     * @param profileDirectory the chat's profile as the chat tools name it; the launcher keeps the profile itself and
     *                         uses this only as the chat's key
     */
    @Override
    public BrowserInstance start(Path profileDirectory) throws BrowserException {
        if (profileDirectory == null) {
            throw new BrowserException(BrowserException.Kind.START_FAILED, "Kein Profilordner");
        }
        LaunchedBrowser launched;
        try {
            launched = launcher.persistent(profileDirectory.toAbsolutePath().normalize().toString(), headless, null);
        } catch (com.aresstack.askai.browser.BrowserException | RuntimeException e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
            throw new BrowserException(BrowserException.Kind.START_FAILED,
                    "Browser konnte nicht gestartet werden: " + message, e);
        }
        BrowserContext context = launched.context();
        context.setDefaultNavigationTimeout(navigationTimeoutMillis);
        context.setDefaultTimeout(navigationTimeoutMillis);
        return new Instance(launched, navigationTimeoutMillis, searchSettings, testSearchUrlTemplate);
    }

    /** One running browser on one profile folder. */
    static final class Instance implements BrowserInstance {
        private final LaunchedBrowser launched;
        private final BrowserContext context;
        private final long navigationTimeoutMillis;
        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
        final boolean camoufox;
        final String testSearchUrlTemplate;
        private volatile boolean closed;

        Instance(LaunchedBrowser launched, long navigationTimeoutMillis,
                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings,
                 String testSearchUrlTemplate) {
            this.testSearchUrlTemplate = testSearchUrlTemplate;
            this.searchSettings = searchSettings;
            this.camoufox = launched.camoufox();
            this.launched = launched;
            this.context = launched.context();
            this.navigationTimeoutMillis = navigationTimeoutMillis;
            context.onClose(gone -> closed = true);
        }

        void markClosed() {
            closed = true;
        }

        @Override
        public boolean isAlive() {
            return !closed;
        }

        @Override
        public BrowserConnection connect() throws BrowserException {
            if (closed) {
                throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen");
            }
            return new PlaywrightChatConnection(this, context, navigationTimeoutMillis);
        }

        @Override
        public synchronized void stop() {
            closed = true;
            launched.close();
        }
    }
}
