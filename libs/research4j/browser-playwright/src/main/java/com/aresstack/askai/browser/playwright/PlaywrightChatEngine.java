package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.playwright.camoufox.CamoufoxLaunch;
import com.aresstack.enterpriseai.browser.api.BrowserException;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserEngine;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The engine of the chat browser tools (browser-tools) on Playwright: one visible browser per chat profile folder,
 * started as a persistent context, so sign-ins and cookies stay with the chat and never travel between chats.
 * Channels: {@code firefox} (the installed Firefox over WebDriver BiDi, as research4j#1 plans it), {@code camoufox}
 * (the managed Firefox build, downloaded on its first start), {@code msedge} and {@code chrome} (installed browsers). Which channel, how long a start and a navigation may
 * take, all come from the host; nothing here is fixed.
 */
public final class PlaywrightChatEngine implements BrowserEngine {

    private final String channel;
    private final boolean headless;
    private final long startTimeoutMillis;
    private final long navigationTimeoutMillis;
    private final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
    /** Dev/test only: one local search engine URL with {@code {query}}, host:port domain families; null in production. */
    private String testSearchUrlTemplate;

    /**
     * @param channel                 {@code firefox}, {@code camoufox}, {@code msedge} or {@code chrome}
     * @param headless                run without a window (tests only; the chat browser is visible)
     * @param startTimeoutMillis      how long the browser may take to start
     * @param navigationTimeoutMillis how long one navigation may take
     */
    public PlaywrightChatEngine(String channel, boolean headless, long startTimeoutMillis,
                                long navigationTimeoutMillis) {
        this(channel, headless, startTimeoutMillis, navigationTimeoutMillis, null);
    }

    /**
     * @param searchSettings the search engines and SERP handling of {@code browser_search} ({@code null} = the
     *                       central defaults, {@link com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults})
     */
    public PlaywrightChatEngine(String channel, boolean headless, long startTimeoutMillis,
                                long navigationTimeoutMillis,
                                com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings) {
        this.searchSettings = searchSettings == null
                ? com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create() : searchSettings;
        this.channel = PlaywrightSessionFactory.normalizeChannel(channel);
        this.headless = headless;
        this.startTimeoutMillis = startTimeoutMillis;
        this.navigationTimeoutMillis = navigationTimeoutMillis;
    }

    /** Dev/test only: search with one local engine template and key domains by host:port. */
    PlaywrightChatEngine searchingLocally(String searchUrlTemplate) {
        this.testSearchUrlTemplate = searchUrlTemplate;
        return this;
    }

    /** The normalized channel this engine starts. */
    public String channel() {
        return channel;
    }

    /**
     * Whether the browser of {@code channel} can start here: {@code READY …} or the reason why not, e.g.
     * {@code BROWSER_NOT_INSTALLED: …}. Camoufox counts as ready before its download.
     */
    public static String readiness(String channel) {
        return new PlaywrightCapabilityProbe().probe(PlaywrightSessionFactory.normalizeChannel(channel)).render();
    }

    @Override
    public BrowserInstance start(Path profileDirectory) throws BrowserException {
        if (profileDirectory == null) {
            throw new BrowserException(BrowserException.Kind.START_FAILED, "Kein Profilordner");
        }
        Playwright playwright = null;
        try {
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));
            BrowserContext context;
            if (CamoufoxProvisioning.CHANNEL.equals(channel)) {
                Path executable = CamoufoxProvisioning.ensureInstalled();
                CamoufoxLaunch launch = CamoufoxLaunch.builtIn(executable, CamoufoxProvisioning.browserRoot(),
                        Locale.getDefault());
                context = playwright.firefox().launchPersistentContext(profileDirectory,
                        Playwright4jDriver.camoufoxOptions(executable, launch, headless)
                                .setTimeout(startTimeoutMillis));
            } else if (BrowserExecutables.FIREFOX.equals(channel)) {
                // The installed Firefox over WebDriver BiDi, on the chat's own profile folder (never the user's).
                context = playwright.firefox().launchPersistentContext(profileDirectory,
                        new BrowserType.LaunchPersistentContextOptions()
                                .setChannel(Playwright4jDriver.FIREFOX_BIDI_CHANNEL)
                                .setExecutablePath(Playwright4jDriver.localFirefoxExecutable())
                                .setHeadless(headless)
                                .setAcceptDownloads(false)
                                .setViewportSize(null)
                                .setFirefoxUserPrefs(FirefoxSessionPrefs.apply(
                                        new java.util.LinkedHashMap<String, Object>()))
                                .setTimeout(startTimeoutMillis));
            } else {
                BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                        .setChannel(channel)
                        .setHeadless(headless)
                        .setAcceptDownloads(false)
                        .setViewportSize(null)
                        .setTimeout(startTimeoutMillis);
                String chosen = BrowserExecutables.configured(channel);
                if (!chosen.isEmpty()) {
                    options.setExecutablePath(Paths.get(chosen));
                }
                context = playwright.chromium().launchPersistentContext(profileDirectory, options);
            }
            context.setDefaultNavigationTimeout(navigationTimeoutMillis);
            context.setDefaultTimeout(navigationTimeoutMillis);
            return new Instance(playwright, context, navigationTimeoutMillis, searchSettings,
                    CamoufoxProvisioning.CHANNEL.equals(channel), testSearchUrlTemplate);
        } catch (PlaywrightException | java.io.IOException | IllegalStateException e) {
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (RuntimeException ignored) {
                    // Nothing left to close.
                }
            }
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage().split("\n")[0];
            throw new BrowserException(BrowserException.Kind.START_FAILED,
                    "Browser (" + channel + ") konnte nicht gestartet werden: " + message, e);
        }
    }

    /** One running browser on one profile folder. */
    static final class Instance implements BrowserInstance {
        private final Playwright playwright;
        private final BrowserContext context;
        private final long navigationTimeoutMillis;
        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings;
        final boolean camoufox;
        final String testSearchUrlTemplate;
        private volatile boolean closed;

        Instance(Playwright playwright, BrowserContext context, long navigationTimeoutMillis,
                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings searchSettings, boolean camoufox,
                 String testSearchUrlTemplate) {
            this.testSearchUrlTemplate = testSearchUrlTemplate;
            this.searchSettings = searchSettings;
            this.camoufox = camoufox;
            this.playwright = playwright;
            this.context = context;
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
            try {
                context.close();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // Already gone.
            }
        }
    }
}
