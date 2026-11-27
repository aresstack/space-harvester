package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.WebSearchResult;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Creates the Playwright-backed {@link BrowserSession} for the browser runtime. Runs the structured capability probe
 * first ({@link PlaywrightCapabilityProbe}); anything but READY yields an {@link UnavailableSession} whose
 * tool errors carry the SPECIFIC status (INCOMPATIBLE_DRIVER / DRIVER_BUNDLE_NOT_FOUND / BROWSER_NOT_INSTALLED
 * / BROWSER_START_FAILED) — never a blanket NOT_INSTALLED and never a silent STATIC_HTTP fallback. A launch
 * failure after a READY probe is reported as BROWSER_START_FAILED. The only download is the managed Camoufox
 * (channel {@code camoufox}, the default), installed once by {@link CamoufoxProvisioning} during the launch.
 */
final class PlaywrightSessionFactory {

    private PlaywrightSessionFactory() {
    }

    static BrowserSession create(String channel, boolean headless, boolean allowPrivateNetworks,
                                 String searchUrlTemplate, BrowserLimits limits,
                                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings) {
        return create(channel, headless, allowPrivateNetworks, searchUrlTemplate, limits, settings, null);
    }

    /** @param profileKey the chat whose persistent browser profile to use; null = a temporary profile */
    static BrowserSession create(String channel, boolean headless, boolean allowPrivateNetworks,
                                 String searchUrlTemplate, BrowserLimits limits,
                                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings,
                                 String profileKey) {
        return create(channel, headless, allowPrivateNetworks ? UrlSafetyPolicy.allowingPrivateNetworks()
                : UrlSafetyPolicy.strict(), !allowPrivateNetworks, searchUrlTemplate, limits, settings, profileKey);
    }

    /**
     * @param policy                the navigation policy (the host's gate or the public-host rule)
     * @param filterPrivateTargets  whether the in-page filter against literal private addresses runs
     */
    static BrowserSession create(String channel, boolean headless, UrlSafetyPolicy policy,
                                 boolean filterPrivateTargets, String searchUrlTemplate, BrowserLimits limits,
                                 com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings,
                                 String profileKey) {
        String normalizedChannel = normalizeChannel(channel);
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(normalizedChannel);
        System.err.println("[browser-mcp] playwright readiness: " + readiness.render());
        if (!readiness.isReady()) {
            return new UnavailableSession(readiness.render());
        }
        try {
            Playwright4jDriver driver = Playwright4jDriver.launch(normalizedChannel, headless,
                    settings.navigation.navigationCommitTimeoutMillis, profileKey,
                    filterPrivateTargets ? new PrivateTargetRequestFilter() : null,
                    settings.consent, settings.captcha);
            driver.setRenderedCapture(new RenderedPageDocumentCapture(settings.analysis));
            return new PlaywrightBrowserSession(driver, policy, limits, searchUrlTemplate, null,
                    settings);
        } catch (BrowserException ex) {
            PlaywrightReadiness failed = new PlaywrightReadiness(
                    PlaywrightReadiness.Status.BROWSER_START_FAILED, ex.getMessage());
            System.err.println("[browser-mcp] playwright readiness: " + failed.render());
            return new UnavailableSession(failed.render());
        }
    }

    /** camoufox (the default, also for a missing value), firefox, chrome or msedge. */
    static String normalizeChannel(String channel) {
        if (BrowserExecutables.FIREFOX.equalsIgnoreCase(channel)) {
            return BrowserExecutables.FIREFOX;
        }
        if ("msedge".equalsIgnoreCase(channel)) {
            return "msedge";
        }
        if ("chrome".equalsIgnoreCase(channel)) {
            return "chrome";
        }
        return CamoufoxProvisioning.CHANNEL;
    }

    /**
     * Cheap best-effort in-browser request filter against obviously private targets (literal loopback /
     * RFC1918 / link-local / cloud-metadata addresses). It cannot resolve DNS per request without stalling
     * the page; the AUTHORITATIVE gate stays the resolving post-redirect URL-policy check in the session.
     */
    static final class PrivateTargetRequestFilter implements Predicate<String> {
        public boolean test(String url) {
            String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
            int schemeEnd = lower.indexOf("://");
            if (schemeEnd < 0) {
                return true; // data:/blob: in-page resources — no network target to protect.
            }
            String rest = lower.substring(schemeEnd + 3);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String host = hostPort.startsWith("[") ? hostPort : (hostPort.contains(":")
                    ? hostPort.substring(0, hostPort.indexOf(':')) : hostPort);
            if (host.equals("localhost") || host.startsWith("127.") || host.equals("[::1]")
                    || host.equals("0.0.0.0") || host.startsWith("10.")
                    || host.startsWith("192.168.") || host.startsWith("169.254.")) {
                return false;
            }
            if (host.startsWith("172.")) {
                String[] parts = host.split("\\.");
                if (parts.length > 1) {
                    try {
                        int second = Integer.parseInt(parts[1]);
                        if (second >= 16 && second <= 31) {
                            return false;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            return true;
        }
    }

    /** Every call fails with the same structured readiness reason; close() is a no-op. */
    static final class UnavailableSession implements BrowserSession {
        private final String reason;
        private final String readinessLine;

        UnavailableSession(String reason) {
            this.readinessLine = reason;
            this.reason = "Playwright backend unavailable — " + reason
                    + " (no automatic fallback to STATIC_HTTP; see problems.md MCP-P005)";
        }

        /** The structured readiness line this session stands in for (e.g. BROWSER_NOT_INSTALLED: …). */
        String readinessLine() {
            return readinessLine;
        }

        public BrowserBackendKind getBackendKind() {
            return BrowserBackendKind.PLAYWRIGHT_SIDECAR;
        }

        public WebSearchResult search(String query) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot open(String url) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot currentPage() throws BrowserException {
            throw new BrowserException(reason);
        }

        public List<BrowserLink> links() throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot follow(String linkId) throws BrowserException {
            throw new BrowserException(reason);
        }

        public BrowserPageSnapshot back() throws BrowserException {
            throw new BrowserException(reason);
        }

        public void close() {
        }
    }
}
