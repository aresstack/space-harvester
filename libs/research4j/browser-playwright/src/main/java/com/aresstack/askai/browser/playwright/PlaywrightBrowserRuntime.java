package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.domain.HostPortDomainKeyResolver;
import com.aresstack.askai.browser.search.DefaultLegacyBrowserSearchSettingsValidator;
import com.aresstack.askai.browser.search.LegacyBrowserSearchConfigDocument;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettings;
import com.aresstack.askai.browser.search.LegacyBrowserSearchSettingsCodec;
import com.aresstack.askai.browser.search.SettingsValidationResult;
import com.aresstack.askai.browser.search.analysis.SearchLayoutRepairTools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * The Playwright browser backend running INSIDE the application (formerly the Java-21 browser MCP sidecar
 * process). One instance is one browser generation: the Playwright-backed {@link BrowserSession} behind the
 * single-owner {@link BrowserSessionActor}, the model-free layout-repair tools and the flat {@code web_*}
 * tool set ({@link BrowserToolSet}) the research runtime drives. The host calls {@link #call} with the same
 * tool names and arguments it used to send over MCP and gets the same text results back.
 *
 * <p>Starting runs the structured capability probe and the real browser launch; anything but READY fails
 * {@link #start} with the SPECIFIC readiness line ({@code INCOMPATIBLE_DRIVER} / {@code DRIVER_BUNDLE_NOT_FOUND}
 * / {@code BROWSER_NOT_INSTALLED} / {@code BROWSER_START_FAILED}) — never a silent fallback. The only download
 * is the managed Camoufox (channel {@code camoufox}, the default), installed once during the first launch or
 * ahead of time through {@link #installCamoufox()}.</p>
 */
public final class PlaywrightBrowserRuntime implements AutoCloseable {

    /** How a browser generation is started. */
    public static final class Options {
        final String channel;
        final boolean headless;
        final boolean allowPrivateNetworks;
        final String searchUrlTemplate;
        final LegacyBrowserSearchSettings settings;
        final boolean hostPortDomainKeys;
        final String profileKey;
        final UrlSafetyPolicy.HostGate hostGate;

        /**
         * @param channel              {@code camoufox} (default, also for null), {@code chrome} or {@code msedge}
         * @param headless             run the browser without a window
         * @param allowPrivateNetworks development-only: allow loopback/private targets (local test servers)
         * @param searchUrlTemplate    dev/test only: ONE search engine URL with {@code {query}} replacing the
         *                             configured engine list; null in production
         * @param settings             the typed browser search settings (null = the central defaults)
         * @param hostPortDomainKeys   dev/test only: host:port domain families so local multi-server worlds
         *                             act as distinct domains (production keeps the public-suffix resolver)
         */
        public Options(String channel, boolean headless, boolean allowPrivateNetworks, String searchUrlTemplate,
                       LegacyBrowserSearchSettings settings, boolean hostPortDomainKeys) {
            this(channel, headless, allowPrivateNetworks, searchUrlTemplate, settings, hostPortDomainKeys, null);
        }

        /**
         * @param profileKey the chat this browser belongs to: Camoufox keeps that chat's OWN persistent profile
         *                   (sign-ins never travel between chats); null = a temporary profile, deleted on close
         */
        public Options(String channel, boolean headless, boolean allowPrivateNetworks, String searchUrlTemplate,
                       LegacyBrowserSearchSettings settings, boolean hostPortDomainKeys, String profileKey) {
            this(channel, headless, allowPrivateNetworks, searchUrlTemplate, settings, hostPortDomainKeys, profileKey,
                    null);
        }

        private Options(String channel, boolean headless, boolean allowPrivateNetworks, String searchUrlTemplate,
                        LegacyBrowserSearchSettings settings, boolean hostPortDomainKeys, String profileKey,
                        UrlSafetyPolicy.HostGate hostGate) {
            this.hostGate = hostGate;
            this.channel = channel;
            this.headless = headless;
            this.allowPrivateNetworks = allowPrivateNetworks;
            this.searchUrlTemplate = searchUrlTemplate == null || searchUrlTemplate.trim().isEmpty()
                    ? null : searchUrlTemplate.trim();
            this.settings = settings == null ? LegacyBrowserSearchDefaults.create() : settings;
            this.hostPortDomainKeys = hostPortDomainKeys;
            this.profileKey = profileKey == null || profileKey.trim().isEmpty() ? null : profileKey;
        }

        /**
         * The same options, but the host decides which targets this browser may open (an intranet profile, a
         * question to the user) instead of the public-host rule; {@code null} restores the default. With a gate
         * the browser drops its in-page filter against private addresses, the gate checks every navigation and
         * every final address after a redirect.
         */
        public Options withHostGate(UrlSafetyPolicy.HostGate gate) {
            return new Options(channel, headless, allowPrivateNetworks, searchUrlTemplate, settings,
                    hostPortDomainKeys, profileKey, gate);
        }

        /** The navigation policy of these options. */
        UrlSafetyPolicy urlPolicy() {
            if (hostGate != null) {
                return UrlSafetyPolicy.withHostGate(hostGate);
            }
            return allowPrivateNetworks ? UrlSafetyPolicy.allowingPrivateNetworks() : UrlSafetyPolicy.strict();
        }

        /** Whether the in-page filter against literal private addresses runs (only under the default rule). */
        boolean filtersPrivateTargets() {
            return hostGate == null && !allowPrivateNetworks;
        }
    }

    /** The browser could not be made ready; {@link #readiness()} is the structured status line. */
    public static final class StartFailure extends Exception {
        private final String readiness;

        StartFailure(String readiness, Throwable cause) {
            super("Browser backend unavailable: " + readiness, cause);
            this.readiness = readiness;
        }

        /** e.g. {@code BROWSER_NOT_INSTALLED: No installed browser found for channel 'chrome'. …} */
        public String readiness() {
            return readiness;
        }
    }

    private final BrowserSessionActor session;
    private final SearchLayoutRepairTools repairTools;
    private final BrowserToolSet tools;
    private final String readiness;
    private volatile boolean closed;

    private PlaywrightBrowserRuntime(BrowserSessionActor session, SearchLayoutRepairTools repairTools,
                                     String readiness) {
        this.session = session;
        this.repairTools = repairTools;
        this.tools = new BrowserToolSet(session, repairTools);
        this.readiness = readiness;
    }

    /**
     * Start a browser generation: probe, launch, wire the repair tools. BLOCKS until the browser is up (the
     * first Camoufox start downloads the distribution). Playwright and everything below it are created on
     * the actor's dedicated owner thread; the calling thread only waits.
     */
    public static PlaywrightBrowserRuntime start(final Options options) throws StartFailure {
        final String channel = PlaywrightSessionFactory.normalizeChannel(options.channel);
        final AtomicReference<BrowserSession> created = new AtomicReference<BrowserSession>();
        BrowserSessionActor actor;
        try {
            actor = BrowserSessionActor.start(new Supplier<BrowserSession>() {
                public BrowserSession get() {
                    BrowserSession session = PlaywrightSessionFactory.create(channel, options.headless,
                            options.urlPolicy(), options.filtersPrivateTargets(), options.searchUrlTemplate,
                            BrowserLimits.defaults(), options.settings, options.profileKey);
                    created.set(session);
                    return session;
                }
            });
        } catch (RuntimeException failed) {
            throw new StartFailure(PlaywrightReadiness.Status.BROWSER_START_FAILED.name() + ": "
                    + failed.getMessage(), failed);
        }
        BrowserSession underlying = created.get();
        if (underlying instanceof PlaywrightSessionFactory.UnavailableSession) {
            String line = ((PlaywrightSessionFactory.UnavailableSession) underlying).readinessLine();
            actor.close();
            throw new StartFailure(line, null);
        }
        if (options.hostPortDomainKeys && actor.isPlaywrightBacked()) {
            try {
                actor.onPlaywrightSession(new BrowserSessionActor.PlaywrightSessionTask<Void>() {
                    public Void run(PlaywrightBrowserSession playwrightSession) {
                        playwrightSession.setDomainKeyResolver(new HostPortDomainKeyResolver());
                        return null;
                    }
                });
                System.err.println("[browser] domain-key-mode=host-port (dev/test)");
            } catch (BrowserException failed) {
                actor.close();
                throw new StartFailure(PlaywrightReadiness.Status.BROWSER_START_FAILED.name()
                        + ": domain-key-mode failed: " + failed.getMessage(), failed);
            }
        }
        // A4: the three layout-repair tools, backed by the SAME engine navigation as web_search. The browser
        // stays MODEL-FREE — it only captures/extracts and applies a runtime-validated decision to a cached
        // snapshot. web_search remains the unchanged compatibility surface.
        SearchLayoutRepairTools repairTools = actor.isPlaywrightBacked()
                ? new SearchLayoutRepairTools(options.settings, new PlaywrightRenderedPageSource(actor),
                        new LongSupplier() {
                            public long getAsLong() {
                                return System.currentTimeMillis();
                            }
                        })
                : null;
        // The READY line as the probe renders it (channel + executable), for diagnostics and the capability check.
        String readiness = new PlaywrightCapabilityProbe().probe(channel).render();
        System.err.println("[browser] ready backend=" + actor.getBackendKind() + " " + readiness);
        return new PlaywrightBrowserRuntime(actor, repairTools, readiness);
    }

    /**
     * Run one {@code web_*} tool (data lane; blocks for the whole call — a multi-page SERP prepare included)
     * and return its text result. Tool failures are {@link BrowserException}s with the specific reason.
     */
    public String call(String tool, Map<String, Object> arguments) throws BrowserException {
        if (closed) {
            throw new BrowserException("Browser runtime is closed.");
        }
        return tools.call(tool, arguments);
    }

    /**
     * CONTROL lane (the HUD command poll): answers while a data call blocks the owner thread, because the
     * HUD inbox is drained directly on the caller's thread. Other tools simply take the data lane.
     */
    public String controlCall(String tool, Map<String, Object> arguments) throws BrowserException {
        return call(tool, arguments);
    }

    /** The structured readiness line of this generation, e.g. {@code READY: channel=camoufox executable=…}. */
    public String readiness() {
        return readiness;
    }

    public boolean isAlive() {
        return !closed;
    }

    /** The tool names this runtime serves (the flat {@code web_*} set plus the three repair tools). */
    public static List<String> toolNames() {
        return BrowserToolSet.TOOL_NAMES;
    }

    /** Ordered teardown: clear the repair-ticket cache, then page/context/browser/driver child. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (repairTools != null) {
            repairTools.clear();
        }
        session.close();
    }

    /**
     * EMERGENCY teardown from a foreign thread when a data call is stuck (the host's per-call browser timeout
     * fired): never blocks on the owner thread; the stuck call fails and the driver child ends. {@link #close()}
     * stays the orderly path.
     */
    public void terminate() {
        if (closed) {
            return;
        }
        closed = true;
        session.terminate();
    }

    // ------------------------------------------------------------------ static helpers

    /** Cheap capability probe for a channel (driver, driver bundle, installed browser) — launches nothing. */
    public static String probe(String channel) {
        return new PlaywrightCapabilityProbe().probe(PlaywrightSessionFactory.normalizeChannel(channel)).render();
    }

    /**
     * One-time download/verification of the managed Camoufox (serialized through the install lock); an
     * already verified installation returns immediately. @return 0 when ready, non-zero on failure.
     */
    public static int installCamoufox() {
        return CamoufoxProvisioning.installOnly();
    }

    /**
     * Load the typed settings from a browser-search config document. Without a path the settings are exactly
     * {@code LegacyBrowserSearchDefaults.create()} (the single default origin). An unreadable, malformed or
     * invalid document FAILS with the concrete reason — a broken configuration never silently degrades.
     */
    public static LegacyBrowserSearchSettings loadBrowserConfig(String path) throws IOException {
        if (path == null || path.trim().isEmpty()) {
            return LegacyBrowserSearchDefaults.create();
        }
        byte[] bytes = Files.readAllBytes(Paths.get(path.trim()));
        LegacyBrowserSearchConfigDocument document =
                LegacyBrowserSearchConfigDocument.parse(new String(bytes, StandardCharsets.UTF_8));
        LegacyBrowserSearchSettingsCodec.Decoded decoded =
                LegacyBrowserSearchSettingsCodec.fromValues(document.values);
        if (!decoded.violations.isEmpty()) {
            throw new IOException("browser config has invalid values:\n"
                    + new SettingsValidationResult(decoded.violations).describe());
        }
        SettingsValidationResult validation =
                new DefaultLegacyBrowserSearchSettingsValidator().validate(decoded.settings);
        if (!validation.isValid()) {
            throw new IOException("browser config failed validation:\n" + validation.describe());
        }
        System.err.println("[browser] browser config: revision=" + document.settingsRevision
                + " digest=" + document.settingsDigest);
        return decoded.settings;
    }
}
