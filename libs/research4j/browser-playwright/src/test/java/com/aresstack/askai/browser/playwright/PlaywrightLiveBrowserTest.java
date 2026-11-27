package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.BrowserSession;
import com.aresstack.askai.browser.WebSearchResult;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

/**
 * THE productive acceptance for 36B: a REAL locally installed Chromium-channel browser, driven through the
 * official Playwright API on playwright4j's GraalJS driver, against a local test server whose content and
 * links exist ONLY after JavaScript execution — a static HTTP fetch cannot pass this test. Covers: dynamic
 * rendering, JS-created links via web_links semantics, follow, back, redirect handling, search via a local
 * provider page, and controlled shutdown.
 *
 * <p>ENVIRONMENT-GATED, not silently green: when the capability probe is not READY (no Chrome/Edge
 * installed), the test SKIPS with the probe status as the message. It never downloads a browser.</p>
 */
public class PlaywrightLiveBrowserTest {

    private static final String START_PAGE = "<!doctype html><html><head><title>JS Start</title></head>"
            + "<body><div id='content'></div><script>"
            + "document.getElementById('content').textContent='Rendered by JavaScript';"
            + "var a=document.createElement('a');a.href='/second';"
            + "a.textContent='go to second page';document.body.appendChild(a);"
            + "</script></body></html>";

    private static final String SECOND_PAGE = "<!doctype html><html><head><title>Second</title></head>"
            + "<body><div id='c'></div><script>"
            + "document.getElementById('c').textContent='Second page rendered';"
            + "</script></body></html>";

    /** The SERP lives on its OWN server; the organic result links to the content server (%TARGET%). */
    /**
     * A structurally VALID artificial SERP: navigation bar plus a repeated result list (three
     * similar blocks with heading link + explanatory snippet). The THIRD block is injected via
     * JavaScript — the proof the capture reads the RENDERED DOM, not the raw HTML.
     */
    private static final String SEARCH_PAGE = "<!doctype html><html><head><title>Find</title></head>"
            + "<body><nav><a href='/videos?q=x'>Videos</a><a href='/settings'>Settings</a></nav>"
            + "<main><ul id='r'>"
            // The inline <style> mirrors Bing's result markup: its CSS text must NEVER become part of a
            // captured snippet (raw textContent would include it — the live mojibake-adjacent bug).
            + "<li><style>.rs_lnkclmp{color:red;text-decoration:underline}</style>"
            + "<h2><a href='%TARGET%'>JS Start (result)</a></h2>"
            + "<p>Explanatory snippet describing the start page target.</p></li>"
            + "<li><h2><a href='%TARGET2%'>Second page (result)</a></h2>"
            + "<p>Explanatory snippet describing the second page target.</p></li>"
            + "</ul></main><script>"
            + "var li=document.createElement('li');"
            + "li.innerHTML=\"<h2><a href='%TARGET3%'>Third rendered (result)</a></h2>\""
            + "  + '<p>Explanatory snippet describing the third rendered target.</p>';"
            + "document.getElementById('r').appendChild(li);"
            + "</script></body></html>";

    @Test
    public void realBrowserRendersJavaScriptFollowsLinksAndShutsDownCleanly() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        String executable = System.getenv("ASKAI_TEST_BROWSER_EXECUTABLE");
        if (executable != null && !executable.isEmpty()) {
            BrowserExecutables.use(channel, executable);
        }
        PlaywrightReadiness readiness = new PlaywrightCapabilityProbe().probe(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness.render(), readiness.isReady());

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", page(START_PAGE));
        server.createContext("/second", page(SECOND_PAGE));
        server.createContext("/redirect", new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                exchange.getResponseHeaders().add("Location", "/second");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();

        // The search engine lives on its OWN server; its organic result links to the content server.
        HttpServer searchServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        searchServer.createContext("/find", page(SEARCH_PAGE
                .replace("%TARGET%", base + "/start")
                .replace("%TARGET2%", base + "/second")
                .replace("%TARGET3%", base + "/start?v=3")));
        searchServer.start();
        String searchBase = "http://127.0.0.1:" + searchServer.getAddress().getPort();

        BrowserSession session = PlaywrightSessionFactory.create(channel, true, true,
                searchBase + "/find?q={query}", BrowserLimits.defaults(),
                com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults.create());
        if (session instanceof PlaywrightBrowserSession) {
            // Hermetic: never fall through to a real public search engine, and key domain families by
            // host:port so the two local servers act as distinct domains (production semantics untouched).
            ((PlaywrightBrowserSession) session).setDomainKeyResolver(
                    new com.aresstack.askai.browser.domain.HostPortDomainKeyResolver());
        }
        try {
            assertEquals(BrowserBackendKind.PLAYWRIGHT_SIDECAR, session.getBackendKind());

            // 1) JavaScript-rendered content — the proof this is a browser, not an HTTP fetch.
            BrowserPageSnapshot start = session.open(base + "/start");
            assertEquals("JS Start", start.getTitle());
            assertTrue("dynamic text must be visible: " + start.getText(),
                    start.getText().contains("Rendered by JavaScript"));

            // 2) The link exists only in the JS-built DOM.
            List<BrowserLink> links = session.links();
            assertEquals(1, links.size());
            assertEquals("go to second page", links.get(0).getText());

            // 3) Follow → dynamically rendered second page.
            BrowserPageSnapshot second = session.follow(links.get(0).getId());
            assertTrue(second.getUrl().endsWith("/second"));
            assertTrue(second.getText().contains("Second page rendered"));

            // 4) Back → the start page is current again (still rendered).
            BrowserPageSnapshot backAtStart = session.back();
            assertTrue(backAtStart.getUrl().endsWith("/start"));
            assertTrue(backAtStart.getText().contains("Rendered by JavaScript"));

            // 5) Redirects: the snapshot reports the FINAL url (the policy check ran against it).
            BrowserPageSnapshot redirected = session.open(base + "/redirect");
            assertTrue("snapshot must carry the post-redirect url: " + redirected.getUrl(),
                    redirected.getUrl().endsWith("/second"));

            // 6) Search = navigation to the local provider; the mechanical analysis reads the
            // RENDERED repeated result blocks (the third block exists only in the JS-built DOM)
            // and delivers title + snippet per block.
            WebSearchResult found = session.search("anything");
            assertEquals(3, found.getItems().size());
            assertEquals("JS Start (result)", found.getItems().get(0).getTitle());
            assertTrue("snippet must come from the block's explanatory text: "
                            + found.getItems().get(0).getSnippet(),
                    found.getItems().get(0).getSnippet().contains("start page target"));
            assertFalse("a result block's inline <style> must never leak into the snippet: "
                            + found.getItems().get(0).getSnippet(),
                    found.getItems().get(0).getSnippet().contains("rs_lnkclmp"));
            assertEquals("Third rendered (result)", found.getItems().get(2).getTitle());

            // 7) Scheme gate stays active on the live backend.
            try {
                session.open("file:///C:/Windows/win.ini");
                fail("file: must be blocked");
            } catch (BrowserException expected) {
                assertTrue(expected.getMessage().contains("scheme"));
            }
        } finally {
            // 8) Controlled, idempotent shutdown: browser + GraalJS driver child end with the session.
            session.close();
            session.close();
            server.stop(0);
            searchServer.stop(0);
        }
    }

    private static HttpHandler page(final String html) {
        return new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
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
