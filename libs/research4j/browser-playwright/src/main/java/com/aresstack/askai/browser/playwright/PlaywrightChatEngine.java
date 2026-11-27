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
        final String profileKey = profileDirectory.toAbsolutePath().normalize().toString();
        final int timeout = (int) Math.min(Integer.MAX_VALUE, navigationTimeoutMillis);
        final Playwright4jDriver[] started = new Playwright4jDriver[1];
        final java.util.concurrent.atomic.AtomicBoolean gone = new java.util.concurrent.atomic.AtomicBoolean();
        BrowserSessionActor actor;
        try {
            // The research driver on its owner thread: Playwright and everything below it are created and used
            // there only; the chat's calls reach it through the actor (PlaywrightChatConnection).
            actor = BrowserSessionActor.start(() -> {
                Playwright4jDriver driver;
                try {
                    driver = Playwright4jDriver.launchForChat(launcher, headless, timeout, profileKey,
                            searchSettings.consent, searchSettings.captcha);
                } catch (com.aresstack.askai.browser.BrowserException e) {
                    throw new IllegalStateException(e.getMessage(), e);
                }
                driver.setRenderedCapture(new RenderedPageDocumentCapture(searchSettings.analysis));
                started[0] = driver;
                driver.browserContext().onClose(context -> gone.set(true));
                PlaywrightBrowserSession session = new PlaywrightBrowserSession(driver,
                        com.aresstack.askai.browser.UrlSafetyPolicy.allowingPrivateNetworks(),
                        com.aresstack.askai.browser.BrowserLimits.defaults(), testSearchUrlTemplate, null,
                        searchSettings);
                if (testSearchUrlTemplate != null) {
                    session.setDomainKeyResolver(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());
                }
                return session;
            });
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            String message = cause.getMessage() == null ? cause.getClass().getSimpleName()
                    : cause.getMessage().split("\n")[0];
            throw new BrowserException(BrowserException.Kind.START_FAILED,
                    "Browser konnte nicht gestartet werden: " + message, e);
        }
        return new Instance(actor, started[0].browserContext(), gone, started[0].camoufox(), navigationTimeoutMillis,
                searchSettings, testSearchUrlTemplate);
    }

    /** One running browser on one profile folder, owned by one thread. */
    static final class Instance implements BrowserInstance {
        final BrowserSessionActor actor;
        private final BrowserContext context;
        private final long navigationTimeoutMillis;
        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
        final boolean camoufox;
        final String testSearchUrlTemplate;
        /** Set when the context closed (window closed, browser gone) or the chat stopped it. */
        private final java.util.concurrent.atomic.AtomicBoolean closed;

        Instance(BrowserSessionActor actor, BrowserContext context, java.util.concurrent.atomic.AtomicBoolean closed,
                 boolean camoufox, long navigationTimeoutMillis,
                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings,
                 String testSearchUrlTemplate) {
            this.actor = actor;
            this.testSearchUrlTemplate = testSearchUrlTemplate;
            this.searchSettings = searchSettings;
            this.camoufox = camoufox;
            this.context = context;
            this.navigationTimeoutMillis = navigationTimeoutMillis;
            this.closed = closed;
        }

        void markClosed() {
            closed.set(true);
        }

        @Override
        public boolean isAlive() {
            return !closed.get();
        }

        @Override
        public BrowserConnection connect() throws BrowserException {
            if (closed.get()) {
                throw new BrowserException(BrowserException.Kind.BROWSER_CLOSED, "Der Browser wurde geschlossen");
            }
            return new PlaywrightChatConnection(this, context, navigationTimeoutMillis);
        }

        @Override
        public void stop() {
            closed.set(true);
            actor.close();
        }
    }
}
