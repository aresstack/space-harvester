package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.hud.ResearchHudState;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The AskAI Research add-on in a REAL Camoufox (downloaded on first use like in the app): it loads at the start,
 * connects to its bridge although loopback requests of pages are blocked, fetches the HUD state, and the page
 * never sees a HUD element or binding. Without the add-on there is no HUD in the browser, and still nothing in the page.
 *
 * <p>Opt-in, because it needs the Camoufox download: {@code ASKAI_LIVE_CAMOUFOX=1 gradlew :browser-playwright:test
 * --tests '*CamoufoxResearchAddonLiveTest'}. Headed with {@code ASKAI_LIVE_CAMOUFOX_HEADED=1} to see the toolbar
 * button and the sidebar.</p>
 */
public class CamoufoxResearchAddonLiveTest {

    /** The page reports in its title whether it can see anything of the HUD. */
    private static final String PAGE = "<!doctype html><html><head><title>probe</title></head><body><p>AskAI add-on"
            + " probe page</p><script>setInterval(function(){document.title='hud='"
            + "+(document.getElementById('__askai-research-hud')!==null)+';binding='+(typeof window.__askaiHudCommand)"
            + "+';root='+(typeof window.__askaiResearchHudRoot);},100);</script></body></html>";

    /**
     * A small result list whose page counts every call of its OWN getBoundingClientRect / getComputedStyle: a script
     * evaluated in the page's world (page.evaluate) goes through them, a script in the add-on's isolated world does not.
     */
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

    private HttpServer server;
    private String base;
    private Playwright4jDriver driver;

    @Before
    public void start() throws Exception {
        assumeTrue("set ASKAI_LIVE_CAMOUFOX=1 to run against a real Camoufox",
                "1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX")));
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String cookie = String.valueOf(exchange.getRequestHeaders().getFirst("Cookie"));
            if (path.startsWith("/as/")) {
                // A sign-in: this user context's cookie decides what /mine shows.
                exchange.getResponseHeaders().add("Set-Cookie", "user=" + path.substring(4) + "; Path=/");
            }
            String mine = "<!doctype html><html><head><title>mine</title></head><body><ul><li><a href='https://example.org/"
                    + (cookie.contains("user=") ? cookie.substring(cookie.indexOf("user=") + 5).split(";")[0] : "nobody")
                    + "'>my page</a></li></ul></body></html>";
            byte[] body = (path.startsWith("/reads") ? READ_PAGE : path.startsWith("/mine") || path.startsWith("/as/")
                    ? mine : PAGE).getBytes(StandardCharsets.UTF_8);
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
    public void theAddonShowsTheHudAndThePageSeesNothing() throws Exception {
        // Like PrivateTargetRequestFilter: page requests to loopback are aborted, only the test page is allowed.
        driver = launch(url -> !url.startsWith("http://127.0.0.1") || url.startsWith(base));
        AddonBridge bridge = driver.addonBridge();
        assertNotNull("Camoufox gets the add-on", bridge);
        driver.open(base);
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
        System.err.println("[addon-live] renderHud=" + result + " addonVersion=" + bridge.addonVersion()
                + " served=" + bridge.servedVersion() + "/" + bridge.publishedVersion());
        assertEquals("the add-on connected and took the HUD", "rendered-addon", result);
        assertEquals("0.2.0", bridge.addonVersion());
        assertTrue("the add-on fetched the HUD state", bridge.servedVersion() > 0);
        driver.pumpEvents(() -> false, 500L);
        String seen = driver.current().title;
        System.err.println("[addon-live] page sees: " + seen);
        assertEquals("hud=false;binding=undefined;root=undefined", seen);
        // ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS=n keeps the browser open with a running countdown, to look at the
        // toolbar badge and the sidebar (headed) and to try the buttons: clicked commands are printed.
        int hold = Integer.parseInt(System.getenv().getOrDefault("ASKAI_LIVE_CAMOUFOX_HOLD_SECONDS", "0"));
        for (int left = hold; left > 0; left--) {
            driver.renderHud(new ResearchHudState("WAITING_FOR_USER", "Solve the check in this tab", true, left,
                    false, 5, false).render());
            driver.pumpEvents(() -> false, 1000L);
            String commands = driver.pollHudCommands();
            if (!commands.isEmpty()) {
                System.err.println("[addon-live] commands from the add-on: " + commands.replace('\n', ' '));
            }
        }
    }

    @Test
    public void pageReadsRunInTheAddonAndThePageSeesNone() throws Exception {
        driver = launch(url -> !url.startsWith("http://127.0.0.1") || url.startsWith(base));
        assertNotNull("Camoufox gets the add-on", driver.addonBridge());
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
        System.err.println("[addon-live] capture via add-on: containers=" + document.containers.size()
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
    public void withoutTheAddonReadsRunInThePageWorld() throws Exception {
        // The counter-proof for the probe above: page.evaluate does go through the page's functions.
        System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        driver = launch(null);
        driver.open(base + "reads");
        driver.setRenderedCapture(new RenderedPageDocumentCapture(
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create().analysis));
        driver.captureRenderedPage(new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver(), 1L);
        driver.pumpEvents(() -> false, 400L);
        String seen = driver.current().title;
        System.err.println("[addon-live] without add-on page sees: " + seen);
        assertTrue(seen, seen.startsWith("calls=") && !"calls=0".equals(seen));
    }

    @Test
    public void workerTabsReadThroughTheWorkerBrowsersAddon() throws Exception {
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        PlaywrightWorkerBrowserBackend worker = PlaywrightWorkerBrowserBackend.launch(
                new PlaywrightBrowserRuntime.Options(CamoufoxProvisioning.CHANNEL, headless, true, null, null, true),
                null);
        try {
            assertNotNull("the worker browser gets the add-on", worker.readBridge());
            String userContext = worker.newUserContext(null);
            String tab = worker.newBrowsingContext(userContext);
            Playwright4jDriver tabDriver = worker.driverOf(tab);
            tabDriver.open(base + "reads");
            com.aresstack.askai.browser.render.RenderedPageDocument document = tabDriver.captureRenderedPage(
                    new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver(), 1L);
            String marker = tabDriver.challengeMarker();
            tabDriver.pumpEvents(() -> false, 400L);
            String seen = tabDriver.current().title;
            System.err.println("[addon-live] worker tab capture: links=" + document.links.size() + " challenge="
                    + marker + " page sees: " + seen + " addon=" + worker.readBridge().addonVersion());
            assertEquals(3, countExternal(document));
            assertEquals("none", marker);
            assertEquals("calls=0", seen);
        } finally {
            worker.close();
        }
    }

    @Test
    public void tabsWithTheSameUrlAreReadAsTheirOwnTab() throws Exception {
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        PlaywrightWorkerBrowserBackend worker = PlaywrightWorkerBrowserBackend.launch(
                new PlaywrightBrowserRuntime.Options(CamoufoxProvisioning.CHANNEL, headless, true, null, null, true),
                null);
        try {
            Playwright4jDriver alice = worker.driverOf(worker.newBrowsingContext(worker.newUserContext(null)));
            Playwright4jDriver bob = worker.driverOf(worker.newBrowsingContext(worker.newUserContext(null)));
            alice.open(base + "as/alice");
            bob.open(base + "as/bob");
            // Same URL in two user contexts, different content: each read must come from its own tab, whichever
            // tab Firefox considers active or most recently used.
            PlaywrightPageState aliceMine = alice.open(base + "mine");
            PlaywrightPageState bobMine = bob.open(base + "mine");
            PlaywrightPageState aliceAgain = alice.current();
            System.err.println("[addon-live] same URL: alice=" + aliceMine.anchors.get(0).href + " bob="
                    + bobMine.anchors.get(0).href + " alice again=" + aliceAgain.anchors.get(0).href);
            assertEquals("https://example.org/alice", aliceMine.anchors.get(0).href);
            assertEquals("https://example.org/bob", bobMine.anchors.get(0).href);
            assertEquals("https://example.org/alice", aliceAgain.anchors.get(0).href);
        } finally {
            worker.close();
        }
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

    @Test
    public void withoutTheAddonNothingIsPutIntoThePage() throws Exception {
        // Issue #22: Camoufox never falls back to the in-page overlay or the binding; only the log says the HUD is off.
        System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");
        driver = launch(null);
        assertEquals(null, driver.addonBridge());
        driver.open(base);
        assertEquals("addon-missing", driver.renderHud(new ResearchHudState("READABLE", "no add-on", false, -1,
                false).render()));
        driver.pumpEvents(() -> false, 500L);
        String seen = driver.current().title;
        System.err.println("[addon-live] without add-on the page sees: " + seen);
        assertEquals("hud=false;binding=undefined;root=undefined", seen);
    }

    private Playwright4jDriver launch(java.util.function.Predicate<String> requestAllowed) throws Exception {
        boolean headless = !"1".equals(System.getenv("ASKAI_LIVE_CAMOUFOX_HEADED"));
        com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        return Playwright4jDriver.launch(CamoufoxProvisioning.CHANNEL, headless, 60_000, null, requestAllowed,
                settings.consent, settings.captcha);
    }
}
