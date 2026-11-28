package com.aresstack.spaceharvester.browser.runtime;

import com.aresstack.spaceharvester.browser.runtime.camoufox.CamoufoxInstaller;
import com.aresstack.spaceharvester.browser.runtime.camoufox.CamoufoxLaunch;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Starts the browsers of the web search and owns their lifecycle (ADR 0046; moved from Research4j, which got it from
 * Ask AI): the installed Firefox and Camoufox over WebDriver BiDi, Chrome and Edge over the DevTools protocol, as the
 * host configured ({@link BrowserRuntimeConfig}). Every start hands back the original Playwright objects
 * ({@link RunningBrowser}); there is no abstraction over them.
 *
 * <ul>
 *   <li>{@link #persistent}: a browser on a lasting profile of its own (never the user's own browser profile),
 *   locked while it runs; Firefox and Camoufox get the session prefs ({@link FirefoxSessionPrefs}).</li>
 *   <li>{@link #ephemeral}: a browser with a throw-away profile; the caller creates its contexts.</li>
 *   <li>Camoufox: {@link #camoufoxInstalled()} and {@link #installCamoufox} are separate. A start only uses an
 *   installed Camoufox and never downloads one.</li>
 *   <li>An add-on handed to a start is loaded on the way the browser supports: Camoufox at its start, the installed
 *   Firefox right after the start as a temporary add-on ({@link FirefoxAddon}), Chrome/Edge as an unpacked extension
 *   on a persistent profile. Otherwise the start reports why not ({@link RunningBrowser#addonFailure()}); it is never
 *   dropped silently.</li>
 *   <li>The driver: playwright4j runs the Playwright driver in a child JVM on GraalVM 25. Its JARs come from the host
 *   package ({@link PlaywrightDriverJars}); a long class path goes through a pathing JAR ({@link DriverClasspathJar}).
 *   Playwright never downloads a browser.</li>
 *   <li>A failed start writes its full report to {@code <data>/logs} ({@link BrowserStartFailureLog}) and fails with
 *   one readable line ({@link BrowserStartException}).</li>
 * </ul>
 */
public final class BrowserRuntime {

    private static final System.Logger LOG = System.getLogger(BrowserRuntime.class.getName());
    /** Playwright's channel for a Firefox driven over WebDriver BiDi (installed Firefox and Camoufox). */
    static final String FIREFOX_BIDI_CHANNEL = BrowserChannel.FIREFOX_BIDI_CHANNEL;

    /**
     * One browser launch at a time in this process. A launch is measured against the start limit
     * ({@link BrowserRuntimeConfig#startTimeout}); two launches side by side (the chat's browser and the search browser
     * of one round) share the machine, so each counted the other's work against its own limit and both failed where
     * either alone starts in time. Fair: the starts run in the order they were asked for.
     */
    private static final java.util.concurrent.locks.ReentrantLock LAUNCHING =
            new java.util.concurrent.locks.ReentrantLock(true);
    /** How many launches ran at once at most (the live test checks that it stays one). */
    private static final java.util.concurrent.atomic.AtomicInteger LAUNCHES_RUNNING =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger MOST_LAUNCHES_AT_ONCE =
            new java.util.concurrent.atomic.AtomicInteger();

    private final BrowserRuntimeConfig config;
    private final BrowserProbe probe;

    public BrowserRuntime(BrowserRuntimeConfig config) {
        this(config, new BrowserProbe());
    }

    BrowserRuntime(BrowserRuntimeConfig config, BrowserProbe probe) {
        this.config = config;
        this.probe = probe;
    }

    public BrowserRuntimeConfig config() {
        return config;
    }

    /** Whether the configured browser can start (driver, driver package, browser). Never downloads anything. */
    public Readiness readiness() {
        try {
            prepareDriver();
        } catch (IOException failed) {
            return new Readiness(Readiness.Status.DRIVER_BUNDLE_NOT_FOUND,
                    "driver JARs not unpacked: " + failed.getMessage());
        }
        return probe.probe(config);
    }

    /** Whether the verified Camoufox is installed in the configured folder. */
    public boolean camoufoxInstalled() {
        return CamoufoxProvisioning.inspect(config.camoufoxRoot()) == CamoufoxProvisioning.Found.VERIFIED;
    }

    /** What the Camoufox installation in the configured folder is doing right now (for a progress bar). */
    public CamoufoxInstaller.Status camoufoxStatus() {
        return CamoufoxProvisioning.status(config.camoufoxRoot());
    }

    /**
     * Downloads and verifies Camoufox into the configured folder; only on the user's request (the host asks for the
     * click). Blocks until done and reports progress lines. @return the verified executable
     */
    public Path installCamoufox(Consumer<String> progress) {
        return CamoufoxProvisioning.install(config.camoufoxRoot(), progress == null ? line -> { } : progress);
    }

    /**
     * A browser on the lasting profile of {@code profileKey} ({@code <data>/browser-profiles/<channel>/chats/<hash>}),
     * or on a temporary profile when the key is empty or another browser of the same profile still runs.
     */
    public RunningBrowser persistent(String profileKey, boolean headless, BrowserAddon addon)
            throws BrowserStartException {
        if (profileKey == null || profileKey.isBlank()) {
            return start(true, null, headless, addon, true);
        }
        return start(true, ProfileLease.keyedFolder(config.profilesDirectory(), profileKey), headless, addon, true);
    }

    /**
     * A browser on the lasting profile in a folder the host manages itself ({@code <folder>/<channel>}, so two
     * browsers never share a profile). Deleting the folder deletes the profile.
     */
    public RunningBrowser persistentIn(Path profileFolder, boolean headless, BrowserAddon addon)
            throws BrowserStartException {
        return start(true, profileFolder.toAbsolutePath().normalize(), headless, addon, true);
    }

    /** A browser with a throw-away profile (Playwright deletes it on close); the caller creates its contexts. */
    public RunningBrowser ephemeral(boolean headless, BrowserAddon addon) throws BrowserStartException {
        return start(false, null, headless, addon, true);
    }

    /**
     * As {@link #ephemeral(boolean, BrowserAddon)}; with {@code acceptRepost = false} Firefox and Camoufox do not
     * accept the "resend data" prompt on their own ({@link FirefoxSessionPrefs}), so a test never re-submits a form
     * unasked. Chrome and Edge are not affected.
     */
    public RunningBrowser ephemeral(boolean headless, BrowserAddon addon, boolean acceptRepost)
            throws BrowserStartException {
        return start(false, null, headless, addon, acceptRepost);
    }

    private RunningBrowser start(boolean persistent, Path profileFolder, boolean headless, BrowserAddon addon,
                                 boolean acceptRepost) throws BrowserStartException {
        BrowserChannel channel = config.channel();
        Playwright playwright = null;
        BrowserContext context = null;
        Browser browser = null;
        ProfileLease profile = null;
        Path temporaryFiles = null;
        // Which step was running when it failed: a visible window with a failed start reads very differently from a
        // driver that never came up.
        String stage = "DRIVER_PREPARE";
        long startedNanos = System.nanoTime();
        try {
            prepareDriver();
            Path executable = null;
            CamoufoxLaunch camoufox = null;
            if (channel == BrowserChannel.CAMOUFOX) {
                stage = "CAMOUFOX_CHECK";
                executable = CamoufoxProvisioning.installedExecutable(config.camoufoxRoot());
                // The system language: search engines answer in it (see CamoufoxLaunch.builtIn).
                camoufox = CamoufoxLaunch.builtIn(executable,
                        CamoufoxProvisioning.installationRoot(config.camoufoxRoot()), Locale.getDefault());
            } else if (channel == BrowserChannel.FIREFOX) {
                executable = firefoxExecutable();
            }
            if (persistent) {
                stage = "PROFILE";
                profile = profileFolder == null
                        ? ProfileLease.temporary(config.profilesDirectory(), channel.id(), staleMillis())
                        : ProfileLease.lasting(profileFolder, channel.id(), config.profileReleaseWait().toMillis(),
                        staleMillis());
            }
            stage = "PLAYWRIGHT_CREATE";
            Map<String, String> env = new LinkedHashMap<>();
            env.put("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1");
            env.put("PLAYWRIGHT_SKIP_VALIDATE_HOST_REQUIREMENTS", "1");
            playwright = Playwright.create(new Playwright.CreateOptions().setEnv(env));

            boolean addonLoaded = false;
            String addonFailure = null;
            Path extension = null;
            Path firefoxAddon = null;
            if (addon != null) {
                stage = "ADDON_PREPARE";
                try {
                    if (channel == BrowserChannel.CAMOUFOX) {
                        // Camoufox loads it at startup from CAMOU_CONFIG.addons, like its bundled add-ons.
                        Path parent = persistent ? profile.directory() : (temporaryFiles = temporaryDirectory("addon-"));
                        camoufox = camoufox.withAddon(addon.prepare(parent, false));
                        addonLoaded = true;
                    } else if (channel == BrowserChannel.FIREFOX) {
                        // The installed Firefox takes it after the start, as a temporary add-on over Playwright's
                        // BiDi session (nothing can be preinstalled there); see ADDON_LOAD below.
                        Path parent = persistent ? profile.directory() : (temporaryFiles = temporaryDirectory("addon-"));
                        firefoxAddon = addon.prepare(parent, false);
                    } else if (persistent) {
                        temporaryFiles = temporaryDirectory("extension-");
                        extension = addon.prepare(temporaryFiles, true);
                        ChromeExtension.pinToolbarButton(profile.directory(), addon.chromiumExtensionId());
                    } else {
                        addonFailure = channel.id() + " loads an extension only on a persistent profile (an "
                                + "off-the-record context cannot script tabs)";
                    }
                } catch (IOException | RuntimeException failed) {
                    // The browser still starts, without the add-on; the caller is told why.
                    addonFailure = "add-on not prepared: " + firstLine(failed);
                    extension = null;
                    firefoxAddon = null;
                }
            }

            stage = "BROWSER_LAUNCH_QUEUE";
            LAUNCHING.lockInterruptibly();
            MOST_LAUNCHES_AT_ONCE.accumulateAndGet(LAUNCHES_RUNNING.incrementAndGet(), Math::max);
            try {
                stage = "BROWSER_LAUNCH";
                long launchStarted = System.nanoTime();
                LOG.log(System.Logger.Level.INFO, "browser " + channel.id() + " starting"
                        + (persistent ? " on a lasting profile" : " (ephemeral)"));
                if (persistent) {
                    Path directory = profile.directory();
                    context = switch (channel) {
                        case CAMOUFOX -> playwright.firefox().launchPersistentContext(directory,
                                withStartTimeout(camoufoxPersistentOptions(executable, camoufox, headless)));
                        case FIREFOX -> playwright.firefox().launchPersistentContext(directory,
                                withStartTimeout(firefoxPersistentOptions(executable, headless)));
                        case CHROME, EDGE -> playwright.chromium().launchPersistentContext(directory,
                                withStartTimeout(chromiumPersistentOptions(channel, headless, extension != null)));
                    };
                    browser = context.browser();
                    if (extension != null) {
                        stage = "ADDON_LOAD";
                        try {
                            ChromeExtension.load(browser, extension, addon.chromiumExtensionId());
                            addonLoaded = true;
                        } catch (RuntimeException loadFailed) {
                            addonFailure = "extension not loaded: " + firstLine(loadFailed);
                        }
                    }
                } else {
                    BrowserType.LaunchOptions options = switch (channel) {
                        case CAMOUFOX -> camoufoxLaunchOptions(executable, camoufox, headless, acceptRepost);
                        case FIREFOX -> firefoxLaunchOptions(executable, headless, acceptRepost);
                        case CHROME, EDGE -> chromiumLaunchOptions(channel, headless);
                    };
                    options.setTimeout(startTimeoutMillis());
                    browser = channel.firefoxFamily() ? playwright.firefox().launch(options)
                            : playwright.chromium().launch(options);
                }
                LOG.log(System.Logger.Level.INFO, "browser " + channel.id() + " started after "
                        + millisSince(launchStarted) + " ms");
                if (firefoxAddon != null) {
                    stage = "ADDON_LOAD";
                    long addonStarted = System.nanoTime();
                    LOG.log(System.Logger.Level.INFO, "browser " + channel.id() + " installing its add-on");
                    try {
                        FirefoxAddon.install(context, browser, firefoxAddon);
                        addonLoaded = true;
                        LOG.log(System.Logger.Level.INFO, "browser " + channel.id() + " add-on installed after "
                                + millisSince(addonStarted) + " ms");
                    } catch (RuntimeException loadFailed) {
                        addonFailure = "add-on not loaded: " + firstLine(loadFailed);
                    }
                }
            } finally {
                LAUNCHES_RUNNING.decrementAndGet();
                LAUNCHING.unlock();
            }
            if (addonFailure != null) {
                LOG.log(System.Logger.Level.WARNING, "browser " + channel.id() + " runs without its add-on: "
                        + addonFailure);
            }
            return new RunningBrowser(channel, playwright, browser, context, profile, temporaryFiles, addonLoaded,
                    addonFailure);
        } catch (IOException | RuntimeException | InterruptedException failed) {
            if (failed instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            long elapsedMillis = (System.nanoTime() - startedNanos) / 1_000_000L;
            RunningBrowser.closeQuietly(context, browser, playwright);
            if (profile != null) {
                profile.close();
            }
            ProfileLease.deleteRecursively(temporaryFiles);
            // The one-line message travels to the host and the chat; the FULL exception (Playwright's multi-line
            // message carries the browser log and call log) goes to a persistent file.
            String report = BrowserStartFailureLog.append(config.logDirectory().toFile(), channel.id(), headless,
                    stage, elapsedMillis, failed);
            throw new BrowserStartException("Browser start failed (channel=" + channel.id() + ", stage=" + stage
                    + ", after " + elapsedMillis + " ms): " + firstLine(failed)
                    + (report == null ? "" : " (full report: " + report + ")"), stage, failed);
        }
    }

    /** How many browser launches ran at once at most in this process (one: they run one after another). */
    static int mostLaunchesAtOnce() {
        return MOST_LAUNCHES_AT_ONCE.get();
    }

    /** The driver JARs of the host package, then the pathing JAR for a long class path. */
    private void prepareDriver() throws IOException {
        PlaywrightDriverJars.prepare(config.dataDirectory());
        DriverClasspathJar.install();
    }

    private Path firefoxExecutable() {
        String executable = BrowserExecutables.effective(BrowserChannel.FIREFOX.id(), config.executable());
        if (executable == null) {
            throw new IllegalStateException("No Firefox found; install it or choose its program file");
        }
        return Paths.get(executable);
    }

    private Path temporaryDirectory(String prefix) {
        try {
            Path scratch = config.profilesDirectory().resolve("temporary");
            Files.createDirectories(scratch);
            return Files.createTempDirectory(scratch, prefix);
        } catch (IOException failed) {
            throw new UncheckedIOException(failed);
        }
    }

    private long staleMillis() {
        return config.staleTemporaryAge().toMillis();
    }

    private BrowserType.LaunchPersistentContextOptions withStartTimeout(
            BrowserType.LaunchPersistentContextOptions options) {
        options.setTimeout(startTimeoutMillis());
        return options;
    }

    /**
     * The start limit for Playwright; 0 is Playwright's "no limit". The first start of a browser on a machine can take
     * longer than any fixed wait (measured in CI: 20 s until Firefox listened, 10 s more for the session, the second
     * start under 2 s); a limit there closed a browser in the middle of its start.
     */
    private double startTimeoutMillis() {
        return Math.max(0L, config.startTimeout().toMillis());
    }

    /** The installed Firefox on its own profile: a real window (no viewport emulation), no downloads. */
    static BrowserType.LaunchPersistentContextOptions firefoxPersistentOptions(Path executable, boolean headless) {
        return new BrowserType.LaunchPersistentContextOptions()
                .setChannel(FIREFOX_BIDI_CHANNEL)
                .setExecutablePath(executable)
                .setHeadless(headless)
                .setAcceptDownloads(false)
                .setViewportSize(null)
                .setFirefoxUserPrefs(FirefoxSessionPrefs.apply(new LinkedHashMap<>()));
    }

    static BrowserType.LaunchOptions firefoxLaunchOptions(Path executable, boolean headless) {
        return firefoxLaunchOptions(executable, headless, true);
    }

    static BrowserType.LaunchOptions firefoxLaunchOptions(Path executable, boolean headless, boolean acceptRepost) {
        return new BrowserType.LaunchOptions()
                .setChannel(FIREFOX_BIDI_CHANNEL)
                .setExecutablePath(executable)
                .setHeadless(headless)
                .setFirefoxUserPrefs(FirefoxSessionPrefs.apply(new LinkedHashMap<>(), acceptRepost));
    }

    /**
     * Camoufox as Argos starts it: Firefox over WebDriver BiDi with the verified executable, the fingerprint
     * environment and prefs of the built-in profile, and NO Playwright emulation (viewport, color scheme, motion ...):
     * the fingerprint owns the window and the screen.
     */
    static BrowserType.LaunchPersistentContextOptions camoufoxPersistentOptions(Path executable, CamoufoxLaunch launch,
                                                                               boolean headless) {
        BrowserType.LaunchPersistentContextOptions options = new BrowserType.LaunchPersistentContextOptions()
                .setChannel(FIREFOX_BIDI_CHANNEL)
                .setExecutablePath(executable)
                .setHeadless(headless)
                .setEnv(camoufoxEnvironment(launch))
                .setAcceptDownloads(false)
                .setViewportSize(null)
                .setColorScheme(null)
                .setReducedMotion(null)
                .setForcedColors(null)
                .setContrast(null)
                .setFirefoxUserPrefs(camoufoxPrefs(launch, true));
        if (!launch.args().isEmpty()) {
            options.setArgs(launch.args());
        }
        return options;
    }

    static BrowserType.LaunchOptions camoufoxLaunchOptions(Path executable, CamoufoxLaunch launch, boolean headless) {
        return camoufoxLaunchOptions(executable, launch, headless, true);
    }

    static BrowserType.LaunchOptions camoufoxLaunchOptions(Path executable, CamoufoxLaunch launch, boolean headless,
                                                           boolean acceptRepost) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setChannel(FIREFOX_BIDI_CHANNEL)
                .setExecutablePath(executable)
                .setHeadless(headless)
                .setEnv(camoufoxEnvironment(launch))
                .setFirefoxUserPrefs(camoufoxPrefs(launch, acceptRepost));
        if (!launch.args().isEmpty()) {
            options.setArgs(launch.args());
        }
        return options;
    }

    private static Map<String, String> camoufoxEnvironment(CamoufoxLaunch launch) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(launch.environment());
        return environment;
    }

    private static Map<String, Object> camoufoxPrefs(CamoufoxLaunch launch, boolean acceptRepost) {
        // no bfcache + no blocking "resend data" prompt, on top of the fingerprint prefs
        return FirefoxSessionPrefs.apply(new LinkedHashMap<>(launch.prefs()), acceptRepost);
    }

    private BrowserType.LaunchPersistentContextOptions chromiumPersistentOptions(BrowserChannel channel,
                                                                                 boolean headless,
                                                                                 boolean withExtension) {
        BrowserType.LaunchPersistentContextOptions options = withExtension
                ? ChromeExtension.options(channel.id(), headless)
                : new BrowserType.LaunchPersistentContextOptions()
                .setChannel(channel.id())
                .setHeadless(headless)
                .setAcceptDownloads(false);
        options.setViewportSize(null);
        if (!config.executable().isEmpty()) {
            options.setExecutablePath(Paths.get(config.executable()));
        }
        return options;
    }

    private BrowserType.LaunchOptions chromiumLaunchOptions(BrowserChannel channel, boolean headless) {
        BrowserType.LaunchOptions options = new BrowserType.LaunchOptions()
                .setChannel(channel.id())
                .setHeadless(headless);
        if (!config.executable().isEmpty()) {
            options.setExecutablePath(Paths.get(config.executable()));
        }
        return options;
    }

    static String firstLine(Throwable failure) {
        String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        int newline = message.indexOf('\n');
        return newline > 0 ? message.substring(0, newline) : message;
    }

    private static long millisSince(long nanoTime) {
        return (System.nanoTime() - nanoTime) / 1_000_000L;
    }
}
