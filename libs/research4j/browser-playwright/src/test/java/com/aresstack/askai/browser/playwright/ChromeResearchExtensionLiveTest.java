package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Issue #23, stage 4: the AskAI Research extension in a REAL Chrome/Edge chat browser. It is loaded over the DevTools
 * protocol at the start, connects to its bridge although loopback requests of pages are blocked, shows the HUD
 * (the page never sees an element or binding), runs the page reads in the isolated world (the page's hooked
 * functions are never called), and its commands reach Java. Without the extension the in-page overlay stays.
 *
 * <p>Environment-gated like the other Chrome live tests: runs when the browser of {@code ASKAI_TEST_BROWSER_CHANNEL}
 * (default {@code chrome}, or {@code msedge}) is installed, skips otherwise. Headed with
 * {@code ASKAI_LIVE_CHROME_HEADED=1}; {@code ASKAI_LIVE_CHROME_HOLD_SECONDS=n} keeps the browser open with a running
 * countdown to look at the toolbar badge and the side panel (clicked commands are printed).</p>
 */
public class ChromeResearchExtensionLiveTest {

    /** The page reports in its title whether it can see anything of the HUD. */
    private static final String PAGE = "<!doctype html><html><head><title>probe</title></head><body><p>AskAI extension"
            + " probe page</p><script>setInterval(function(){document.title='hud='"
            + "+(document.getElementById('__askai-research-hud')!==null)+';binding='+(typeof window.__askaiHudCommand)"
            + "+';root='+(typeof window.__askaiResearchHudRoot);},100);</script></body></html>";

    /** Counts every call of its OWN getBoundingClientRect / getComputedStyle (see CamoufoxResearchAddonLiveTest). */
    private static final String READ_PAGE = "<!doctype html><html><head><title>reads</title><script>"
            + "window.__calls=0;(function(){var r=Element.prototype.getBoundingClientRect;"
            + "Element.prototype.getBoundingClientRect=function(){window.__calls++;return r.apply(this,arguments);};"
            + "var c=window.getComputedStyle;window.getComputedStyle=function(){window.__calls++;"
            + "return c.apply(window,arguments);};})();"
            + "setInterval(function(){document.title='calls='+window.__calls;},100);</script></head><body>"
            + "<main><ol id='results'>"
            + "<li class='r'><h2><a href='https://example.org/one'>One result</a></h2><p>First snippet text.</p></li>"
            + "<li class='r'><h2><a href='https://example.net/two'>Two result</a></h2><p>Second snippet text.</p></li>"
            + "<li class='r'><h2><a href='https://example.com/three'>Three result</a></h2><p>Third snippet.</p></li>"
            + "</ol></main></body></html>";

    private String channel;
    private HttpServer server;
    private String base;
    private Playwright4jDriver driver;

    @Before
    public void start() throws Exception {
        channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        assumeTrue("the Chrome extension is for chrome/msedge", !CamoufoxProvisioning.CHANNEL.equals(channel));
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = (exchange.getRequestURI().getPath().startsWith("/reads") ? READ_PAGE : PAGE)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @After
    public void stop() {
        if (driver != null) {
            driver.close();
        }
        if (server != null) {
            server.stop(0);
        }
        System.clearProperty(Playwright4jDriver.ADDON_PROPERTY);
    }

    @Test
    public void theExtensionShowsTheHudCommandsReachJavaAndThePageSeesNothing() throws Exception {
        // Like PrivateTargetRequestFilter: page requests to loopback are aborted, only the test page is allowed.
        driver = launch(url -> !url.startsWith("http://127.0.0.1") || url.startsWith(base));
        AddonBridge bridge = driver.addonBridge();
        assertNotNull("the Chrome chat browser gets the extension", bridge);
        driver.open(base);
        String result = connect(bridge);
        System.err.println("[chrome-extension-live] renderHud=" + result + " addonVersion=" + bridge.addonVersion()
                + " served=" + bridge.servedVersion() + "/" + bridge.publishedVersion());
        assertEquals("the extension connected and took the HUD", "rendered-addon", result);
        assertEquals("0.2.0", bridge.addonVersion());
        assertTrue("the extension fetched the HUD state", bridge.servedVersion() > 0);
        driver.pumpEvents(() -> false, 500L);
        String seen = driver.current().title;
        System.err.println("[chrome-extension-live] page sees: " + seen);
        assertEquals("hud=false;binding=undefined;root=undefined", seen);

        // The panel as a tab (the side panel shows the same page; opening it needs a user gesture on the toolbar
        // button): a real click on Skip goes panel -> service worker -> bridge, with Chrome's extension origin.
        com.microsoft.playwright.Page panel = driver.browserContext().newPage();
        try {
            panel.navigate("chrome-extension://" + ResearchAddon.CHROME_ID + "/panel.html");
            panel.locator("#skip").click(new com.microsoft.playwright.Locator.ClickOptions().setTimeout(10_000));
            String commands = "";
            long deadline = System.currentTimeMillis() + 10_000L;
            while (System.currentTimeMillis() < deadline && !commands.contains("SKIP")) {
                driver.pumpEvents(() -> false, 200L);
                commands += driver.pollHudCommands();
            }
            System.err.println("[chrome-extension-live] panel shows: " + panel.locator("#phase").textContent()
                    + " / " + panel.locator("#status").textContent() + "; commands: " + commands.replace('\n', ' '));
            assertTrue("the panel's Skip reached the HUD inbox: '" + commands + "'", commands.contains("SKIP"));
        } finally {
            panel.close();
        }

        int hold = Integer.parseInt(System.getenv().getOrDefault("ASKAI_LIVE_CHROME_HOLD_SECONDS", "0"));
        for (int left = hold; left > 0; left--) {
            driver.renderHud(new ResearchHudState("WAITING_FOR_USER", "Solve the check in this tab", true, left,
                    false, 5, false).render());
            driver.pumpEvents(() -> false, 1000L);
            String clicked = driver.pollHudCommands();
            if (!clicked.isEmpty()) {
                System.err.println("[chrome-extension-live] commands from the extension: " + clicked.replace('\n', ' '));
            }
        }
    }

    @Test
    public void pageReadsRunInTheExtensionAndThePageSeesNone() throws Exception {
        driver = launch(url -> !url.startsWith("http://127.0.0.1") || url.startsWith(base));
        assertNotNull("the Chrome chat browser gets the extension", driver.addonBridge());
        driver.open(base + "reads");
        driver.setRenderedCapture(new RenderedPageDocumentCapture(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create().analysis));
        long started = System.currentTimeMillis();
        com.aresstack.askai.browser.render.RenderedPageDocument document = driver.captureRenderedPage(
                new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver(), 1L);
        long captureMillis = System.currentTimeMillis() - started;
        String marker = driver.challengeMarker();
        String consentCandidate = driver.consentCandidate();
        driver.pumpEvents(() -> false, 400L);
        PlaywrightPageState state = driver.current();
        System.err.println("[chrome-extension-live] capture via extension: containers=" + document.containers.size()
                + " links=" + document.links.size() + " in " + captureMillis + " ms; challenge=" + marker
                + " consent=" + consentCandidate + " anchors=" + state.anchors.size() + " page sees: " + state.title);
        assertEquals("the capture saw the three result links", 3, countExternal(document));
        assertEquals("none", marker);
        assertEquals("none", consentCandidate);
        assertEquals(3, state.anchors.size());
        assertEquals("no read went through the page's own functions", "calls=0", state.title);
        assertEquals(0, driver.addonBridge().openReadCount());
    }

    @Test
    public void withoutTheExtensionTheOverlayAndPageReadsStayInThePage() throws Exception {
        // The fallback for Chrome/Edge without the extension, and the counter-proof for the read probe above.
        System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        driver = launch(null);
        assertNull(driver.addonBridge());
        driver.open(base);
        assertEquals("rendered", driver.renderHud(new ResearchHudState("READABLE", "no extension", false, -1,
                false).render()));
        driver.pumpEvents(() -> false, 500L);
        assertTrue(driver.current().title, driver.current().title.startsWith("hud=true"));
        driver.open(base + "reads");
        driver.setRenderedCapture(new RenderedPageDocumentCapture(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create().analysis));
        driver.captureRenderedPage(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver(), 1L);
        driver.pumpEvents(() -> false, 400L);
        String seen = driver.current().title;
        System.err.println("[chrome-extension-live] without extension page sees: " + seen);
        assertTrue(seen, seen.startsWith("calls=") && !"calls=0".equals(seen));
    }

    @Test
    public void theTemporaryProfileIsGoneAfterClose() throws Exception {
        driver = launch(null);
        assertNotNull(driver.addonBridge());
        Path extension = extensionDirectory();
        assertTrue(Files.isRegularFile(extension.resolve("manifest.json")));
        driver.open(base + "reads");
        driver.current(); // one read: its script file lies in reads/
        try (Stream<Path> reads = Files.list(extension.resolve(AddonBridge.READ_SCRIPT_DIRECTORY))) {
            assertTrue("the read script was written as a file", reads.anyMatch(p -> p.toString().endsWith(".js")));
        }
        driver.close();
        driver = null;
        assertFalse("profile and extension are deleted with the browser", Files.exists(extension.getParent()));
    }

    private String connect(AddonBridge bridge) {
        String result = "";
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            result = driver.renderHud(new ResearchHudState("WAITING_FOR_USER", "Solve the check", true,
                    (int) ((deadline - System.currentTimeMillis()) / 1000L), false).render());
            if ("rendered-addon".equals(result) && bridge.servedVersion() > 0) {
                break;
            }
            driver.pumpEvents(() -> false, 500L);
        }
        return result;
    }

    /** The extension directory, next to the temporary profile. */
    private Path extensionDirectory() {
        assertNotNull("a temporary Chrome root exists", driver.chromeRoot());
        return driver.chromeRoot().resolve(ResearchAddon.DIRECTORY);
    }

    private static int countExternal(com.aresstack.askai.browser.render.RenderedPageDocument document) {
        int count = 0;
        for (com.aresstack.askai.browser.render.RenderedLinkDescriptor link : document.links) {
            if (link.resolvedTargetUrl.startsWith("https://example.")) {
                count++;
            }
        }
        return count;
    }

    private Playwright4jDriver launch(java.util.function.Predicate<String> requestAllowed) throws Exception {
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CHROME_HEADED"));
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        return Playwright4jDriver.launch(channel, headless, 60_000, null, requestAllowed,
                settings.consent, settings.captcha);
    }
}
