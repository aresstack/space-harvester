package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Tests only: the smallest possible {@link BrowserLauncher} for the live tests of the research functions. It starts
 * the browser named by {@value #CHANNEL_ENV} ({@code firefox}, {@code chrome}, {@code msedge} or {@code chromium},
 * default {@code chrome}) from {@value #EXECUTABLE_ENV} (for a Chromium channel optional: Playwright then finds the
 * installed browser of that channel). Deliberately WITHOUT profile management, preferences, workarounds or add-on
 * loading: all of that is the host's browser runtime, and its real start is tested there. Camoufox is not started
 * here.
 */
public final class TestBrowserLauncher implements BrowserLauncher {

    public static final String CHANNEL_ENV = "ASKAI_TEST_BROWSER_CHANNEL";
    public static final String EXECUTABLE_ENV = "ASKAI_TEST_BROWSER_EXECUTABLE";
    /** Playwright's channel for a Firefox driven over WebDriver BiDi. */
    private static final String FIREFOX_BIDI_CHANNEL = "moz-firefox";

    private final String channel;
    private final String executable;

    public TestBrowserLauncher(String channel, String executable) {
        this.channel = channel == null || channel.trim().isEmpty() ? "chrome" : channel.trim().toLowerCase();
        this.executable = executable == null || executable.trim().isEmpty() ? null : executable.trim();
    }

    /** The browser of {@value #CHANNEL_ENV} and {@value #EXECUTABLE_ENV}. */
    public static TestBrowserLauncher fromEnvironment() {
        return new TestBrowserLauncher(System.getenv(CHANNEL_ENV), System.getenv(EXECUTABLE_ENV));
    }

    /** The channel this launcher starts. */
    public String channel() {
        return channel;
    }

    private boolean firefox() {
        return "firefox".equals(channel);
    }

    @Override
    public PlaywrightReadiness readiness() {
        if ("camoufox".equals(channel)) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED,
                    "Camoufox starts only through the host's browser runtime");
        }
        if (executable != null && !Files.isExecutable(Paths.get(executable))) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED,
                    "not executable: " + executable);
        }
        if (executable == null && firefox()) {
            return new PlaywrightReadiness(PlaywrightReadiness.Status.BROWSER_NOT_INSTALLED,
                    "set " + EXECUTABLE_ENV + " to the installed Firefox");
        }
        return new PlaywrightReadiness(PlaywrightReadiness.Status.READY, "channel=" + channel
                + " executable=" + (executable == null ? "installed " + channel : executable));
    }

    @Override
    public LaunchedBrowser persistent(String profileKey, boolean headless, AddonPackager addon)
            throws BrowserException {
        Playwright playwright = create();
        Path profile = null;
        try {
            profile = Files.createTempDirectory("research4j-test-profile-");
            BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(headless).setAcceptDownloads(false);
            if (firefox()) {
                options.setChannel(FIREFOX_BIDI_CHANNEL);
            } else if (executable == null) {
                options.setChannel(channel);
            }
            if (executable != null) {
                options.setExecutablePath(Paths.get(executable));
            }
            BrowserContext context = (firefox() ? playwright.firefox() : playwright.chromium())
                    .launchPersistentContext(profile, options);
            return new Launched(playwright, context.browser(), context, profile, addon != null);
        } catch (IOException | PlaywrightException failed) {
            playwright.close();
            delete(profile);
            throw new BrowserException("Test browser did not start: " + failed.getMessage());
        }
    }

    @Override
    public LaunchedBrowser ephemeral(boolean headless, AddonPackager addon) throws BrowserException {
        Playwright playwright = create();
        try {
            BrowserType.LaunchOptions options = new BrowserType.LaunchOptions().setHeadless(headless);
            if (firefox()) {
                options.setChannel(FIREFOX_BIDI_CHANNEL);
            } else if (executable == null) {
                options.setChannel(channel);
            }
            if (executable != null) {
                options.setExecutablePath(Paths.get(executable));
            }
            Browser browser = (firefox() ? playwright.firefox() : playwright.chromium()).launch(options);
            return new Launched(playwright, browser, null, null, addon != null);
        } catch (PlaywrightException failed) {
            playwright.close();
            throw new BrowserException("Test browser did not start: " + failed.getMessage());
        }
    }

    private static Playwright create() {
        Map<String, String> env = new LinkedHashMap<String, String>();
        env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
        env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
        return Playwright.create(new Playwright.CreateOptions().setEnv(env));
    }

    private static void delete(Path directory) {
        if (directory == null) {
            return;
        }
        try (Stream<Path> files = Files.walk(directory)) {
            files.sorted(Comparator.reverseOrder()).forEach(file -> file.toFile().delete());
        } catch (IOException | RuntimeException ignored) {
            // a file the browser still holds stays in the temp directory
        }
    }

    private static final class Launched implements LaunchedBrowser {
        private final Playwright playwright;
        private final Browser browser;
        private final BrowserContext context;
        private final Path profile;
        private final boolean addonAsked;
        private boolean closed;

        Launched(Playwright playwright, Browser browser, BrowserContext context, Path profile, boolean addonAsked) {
            this.playwright = playwright;
            this.browser = browser;
            this.context = context;
            this.profile = profile;
            this.addonAsked = addonAsked;
        }

        public Playwright playwright() {
            return playwright;
        }

        public Browser browser() {
            return browser;
        }

        public BrowserContext context() {
            return context;
        }

        public boolean camoufox() {
            return false;
        }

        public boolean chromiumExtension() {
            return false;
        }

        public boolean addonLoaded() {
            return false;
        }

        public String addonFailure() {
            return addonAsked ? "the test launcher loads no add-on" : null;
        }

        public synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                if (context != null) {
                    context.close();
                }
                if (browser != null) {
                    browser.close();
                }
            } catch (RuntimeException ignored) {
                // already gone
            }
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            delete(profile);
        }

        public void abandon() {
            try {
                playwright.close();
            } catch (RuntimeException ignored) {
                // already gone
            }
            delete(profile);
        }
    }
}
