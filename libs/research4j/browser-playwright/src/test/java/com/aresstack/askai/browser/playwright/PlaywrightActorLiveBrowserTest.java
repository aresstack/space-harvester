package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.UrlSafetyPolicy;
import com.aresstack.askai.browser.hud.ResearchHudState;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * THE regression test for the live freeze: with request interception installed ({@code route("**&#47;*")}),
 * every browser-side request needs a Java callback — and Playwright Java only dispatches callbacks while
 * the owning thread runs a Playwright call. Before the {@link BrowserSessionActor} pump, an IDLE sidecar
 * (research finished or busy with non-browser work) froze every user-driven navigation, swallowed HUD
 * button clicks and left popups open. This test reproduces exactly that shape: a page navigates ITSELF via
 * a timed click / binding call / popup while NO tool command is in flight, and the local server proves the
 * requests still go through.
 *
 * <p>ENVIRONMENT-GATED like {@link PlaywrightLiveBrowserTest}: skips with the probe status when no
 * Chrome/Edge is installed; never downloads a browser.</p>
 */
public class PlaywrightActorLiveBrowserTest {

    /**
     * Page A clicks its own link ~1.5s after load — by then open() has long returned and the sidecar is
     * idle. Without the idle pump the intercepted navigation request is never resumed and /b is never hit.
     */
    private static final String PAGE_A = "<!doctype html><html><head><title>A</title></head>"
            + "<body><a id='go' href='/b'>go to b</a><script>"
            + "setTimeout(function(){document.getElementById('go').click();},1500);"
            + "</script></body></html>";

    /**
     * Page B fires a HUD command at +4s (after the test registered the binding) and opens a popup at +6s —
     * both while the sidecar is idle. The popup URL hitting the server proves its intercepted request was
     * resumed; the drained SKIP proves the exposeBinding event was dispatched.
     */
    private static final String PAGE_B = "<!doctype html><html><head><title>B</title></head>"
            + "<body><p>page b</p><script>"
            + "setTimeout(function(){if(window.__askaiHudCommand){window.__askaiHudCommand('SKIP');}},4000);"
            + "setTimeout(function(){window.open('/popup');},6000);"
            + "</script></body></html>";

    private static final String POPUP_PAGE = "<!doctype html><html><head><title>P</title></head>"
            + "<body><p>popup</p></body></html>";

    @Test
    public void idleSidecarStillDispatchesRoutesBindingsAndPopups() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());
        // This test covers the in-page overlay and its binding, i.e. Chrome/Edge WITHOUT the research extension
        // (switched off or not loadable); the extension path is ChromeResearchExtensionLiveTest.
        System.setProperty(Playwright4jDriver.ADDON_PROPERTY, "false");

        final CountDownLatch bRequested = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a", page(PAGE_A, null));
        server.createContext("/b", page(PAGE_B, bRequested));
        server.createContext("/popup", page(POPUP_PAGE, null));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        // Launch INSIDE the actor factory (creation on the owner thread) with the interception ACTIVE
        // (url -> true): the production freeze mechanism, minus the private-target policy.
        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        final String launchChannel = channel;
        final AtomicReference<Playwright4jDriver> rawDriver =
                new AtomicReference<Playwright4jDriver>();
        BrowserSessionActor actor = BrowserSessionActor.start(new Supplier<BrowserSession>() {
            public BrowserSession get() {
                try {
                    Playwright4jDriver driver = Playwright4jDriver.launch(launchChannel, true,
                            settings.navigation.navigationCommitTimeoutMillis, null,
                            new java.util.function.Predicate<String>() {
                                public boolean test(String url) {
                                    return true; // interception installed, everything allowed
                                }
                            }, settings.consent, settings.captcha);
                    rawDriver.set(driver);
                    return new PlaywrightBrowserSession(driver,
                            UrlSafetyPolicy.allowingPrivateNetworks(), BrowserLimits.defaults(),
                            null, null, settings);
                } catch (BrowserException ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
            }
        });
        try {
            assertTrue(actor.isPlaywrightBacked());

            // 1) Open /a; from here on the test issues NO browser command until the latch fires.
            BrowserPageSnapshot a = actor.open(base + "/a");
            assertEquals("A", a.getTitle());

            // THE live-bug regression: the page's own click navigates while the sidecar is idle.
            assertTrue("idle user navigation must reach the server (route.resume while no tool call "
                    + "is in flight)", bRequested.await(20, TimeUnit.SECONDS));

            // 2) HUD binding while idle: register overlay+binding on /b, then wait for the page's own
            // deferred __askaiHudCommand('SKIP'). pollHudCommands drains a plain Java queue and never
            // pumps Playwright — the dispatch can only come from the idle pump.
            Thread.sleep(500); // let /b finish rendering after the server saw the request
            assertEquals("rendered", actor.renderHud(
                    new ResearchHudState("READABLE", "actor live test", false,
                            ResearchHudState.NO_COUNTDOWN, false).render()));
            String drained = "";
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && !drained.contains("SKIP")) {
                Thread.sleep(300);
                String batch = actor.pollHudCommands();
                if (!batch.isEmpty()) {
                    drained = drained.isEmpty() ? batch : drained + "\n" + batch;
                }
            }
            assertTrue("the HUD click must reach Java while the sidecar is idle, got: '" + drained
                    + "'", drained.contains("SKIP"));

            // 3) Popup while idle: the onPopup event must be dispatched and the close-immediately
            // policy must run. The counter read touches no Playwright state, so this poll cannot pump
            // events itself — the dispatch can only come from the idle pump. (The popup's own request
            // races the immediate close, so the server hit is NOT asserted.)
            long popupDeadline = System.currentTimeMillis() + 20000;
            int popupsClosed = 0;
            while (System.currentTimeMillis() < popupDeadline && popupsClosed == 0) {
                Thread.sleep(500);
                popupsClosed = rawDriver.get().closedPopupCount();
            }
            assertTrue("the popup event must reach the close-immediately handler while the sidecar "
                    + "is idle", popupsClosed >= 1);

            // 4) Ownership: a direct call from the test thread (an "HTTP worker") must be rejected.
            try {
                rawDriver.get().current();
                fail("direct Playwright access from a foreign thread must fail");
            } catch (IllegalStateException expected) {
                assertTrue(expected.getMessage().contains(BrowserSessionActor.OWNER_THREAD_NAME));
            }

            // 5) Parallel MCP-style calls: all serialized onto the owner thread, none rejected.
            final CountDownLatch done = new CountDownLatch(4);
            final AtomicReference<Throwable> failure = new AtomicReference<Throwable>();
            for (int i = 0; i < 4; i++) {
                new Thread(new Runnable() {
                    public void run() {
                        try {
                            actor.currentPage();
                        } catch (Throwable ex) {
                            failure.compareAndSet(null, ex);
                        } finally {
                            done.countDown();
                        }
                    }
                }, "mcp-http-worker").start();
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            if (failure.get() != null) {
                fail("parallel calls must serialize, not fail: " + failure.get());
            }
        } finally {
            // 6) Shutdown on the owner thread; idempotent; further calls fail readably.
            actor.close();
            actor.close();
            try {
                actor.currentPage();
                fail("calls after close must fail");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("closed"));
            }
            server.stop(0);
            System.clearProperty(Playwright4jDriver.ADDON_PROPERTY);
        }
    }

    /**
     * Commit-B regression: navigation waits for DOMCONTENTLOADED, not full 'load'. A page whose
     * subresource hangs FOREVER must still open within the navigation timeout — the document is there,
     * and the session's own readiness machinery judges the rest. With the previous default ('load'),
     * this open() blocked for the whole navigationCommitTimeoutMillis and then failed.
     */
    @Test
    public void openReturnsAfterDomContentLoadedDespiteAForeverHangingSubresource() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        final CountDownLatch releaseHangingResource = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // The hanging handler must not block the server's dispatch of other requests.
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.createContext("/hang", page("<!doctype html><html><head><title>Hang</title></head>"
                + "<body><p>document is here</p><img src='/never'></body></html>", null));
        server.createContext("/never", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                try {
                    releaseHangingResource.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        final com.aresstack.askai.browser.search.LegacyBrowserSearchSettings settings =
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create();
        final String launchChannel = channel;
        BrowserSessionActor actor = BrowserSessionActor.start(new Supplier<BrowserSession>() {
            public BrowserSession get() {
                try {
                    Playwright4jDriver driver = Playwright4jDriver.launch(launchChannel, true,
                            settings.navigation.navigationCommitTimeoutMillis, null, null,
                            settings.consent, settings.captcha);
                    return new PlaywrightBrowserSession(driver,
                            UrlSafetyPolicy.allowingPrivateNetworks(), BrowserLimits.defaults(),
                            null, null, settings);
                } catch (BrowserException ex) {
                    throw new IllegalStateException(ex.getMessage(), ex);
                }
            }
        });
        try {
            long before = System.currentTimeMillis();
            BrowserPageSnapshot snapshot = actor.open(base + "/hang");
            long elapsed = System.currentTimeMillis() - before;
            assertEquals("Hang", snapshot.getTitle());
            assertTrue("the document text must be readable: " + snapshot.getText(),
                    snapshot.getText().contains("document is here"));
            assertTrue("open() must return on DOMContentLoaded, not wait out the navigation timeout "
                    + "(took " + elapsed + "ms)",
                    elapsed < settings.navigation.navigationCommitTimeoutMillis - 2000);
        } finally {
            releaseHangingResource.countDown();
            actor.close();
            server.stop(0);
        }
    }

    private static HttpHandler page(final String html, final CountDownLatch requested) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                if (requested != null) {
                    requested.countDown();
                }
                byte[] body = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, body.length);
                OutputStream out = exchange.getResponseBody();
                out.write(body);
                exchange.close();
            }
        };
    }
}
