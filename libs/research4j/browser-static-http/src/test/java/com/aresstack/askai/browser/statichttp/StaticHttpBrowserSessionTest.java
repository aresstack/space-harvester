package com.aresstack.askai.browser.statichttp;

import com.aresstack.askai.browser.BrowserBackendKind;
import com.aresstack.askai.browser.BrowserException;
import com.aresstack.askai.browser.BrowserLimits;
import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.askai.browser.UrlSafetyPolicy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.junit.After;
import org.junit.Before;
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

/** The STATIC_HTTP backend against a deterministic local HttpServer: open/read/links/follow/back, limits. */
public class StaticHttpBrowserSessionTest {

    private HttpServer server;
    private String base;

    @Before
    public void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        page("/a", "<html><head><title>Page A</title></head><body><script>evil()</script>"
                + "<nav>menu</nav><div class='cookie-banner'>cookies!</div>"
                + "<h1>Alpha</h1><p>Content of A.</p>"
                + "<a href='/b'>to B</a><a href='/c'>to C</a></body></html>");
        page("/b", "<html><head><title>Page B</title></head><body><h1>Beta</h1>"
                + "<p>Content of B.</p><a href='/c'>to C</a></body></html>");
        page("/c", "<html><head><title>Page C</title></head><body><h1>Gamma</h1>"
                + "<p>Content of C.</p></body></html>");
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @After
    public void stopServer() {
        server.stop(0);
    }

    private void page(String path, final String html) {
        server.createContext(path, new HttpHandler() {
            public void handle(HttpExchange exchange) throws IOException {
                byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.sendResponseHeaders(200, bytes.length);
                OutputStream out = exchange.getResponseBody();
                out.write(bytes);
                out.close();
            }
        });
    }

    private StaticHttpBrowserSession session() {
        return new StaticHttpBrowserSession(UrlSafetyPolicy.allowingPrivateNetworks(),
                BrowserLimits.defaults());
    }

    @Test
    public void openReadsCleanedTextAndDeclaresBackendKind() throws Exception {
        StaticHttpBrowserSession s = session();
        assertEquals(BrowserBackendKind.STATIC_HTTP, s.getBackendKind());
        BrowserPageSnapshot snap = s.open(base + "/a");
        assertEquals("Page A", snap.getTitle());
        assertTrue(snap.getText().contains("Content of A."));
        assertFalse("script content must be removed", snap.getText().contains("evil"));
        assertFalse("nav chrome must be removed", snap.getText().contains("menu"));
        assertFalse("cookie chrome must be removed", snap.getText().contains("cookies!"));
        s.close();
    }

    @Test
    public void linksHaveStableIdsFollowAndBackNavigateThreePages() throws Exception {
        StaticHttpBrowserSession s = session();
        s.open(base + "/a");
        List<BrowserLink> links = s.links();
        assertEquals(2, links.size());
        String toB = links.get(0).getId();

        BrowserPageSnapshot b = s.follow(toB);           // page 2
        assertEquals("Page B", b.getTitle());
        BrowserPageSnapshot c = s.follow(s.links().get(0).getId()); // page 3
        assertEquals("Page C", c.getTitle());

        assertEquals("Page B", s.back().getTitle());
        assertEquals("Page A", s.back().getTitle());
        s.close();
    }

    @Test
    public void searchIsHonestlyUnsupported() {
        StaticHttpBrowserSession s = session();
        try {
            s.search("anything");
            fail("STATIC_HTTP must not pretend to search");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("cannot search"));
        }
        s.close();
    }

    @Test
    public void forbiddenSchemeAndUnknownLinkAndClosedSessionAreControlledErrors() throws Exception {
        StaticHttpBrowserSession s = session();
        try {
            s.open("file:///etc/passwd");
            fail("scheme must be blocked");
        } catch (BrowserException expected) {
            // ok
        }
        s.open(base + "/a");
        try {
            s.follow("does-not-exist");
            fail("unknown link id must fail readably");
        } catch (BrowserException expected) {
            assertTrue(expected.getMessage().contains("Unknown link id"));
        }
        s.close();
        try {
            s.currentPage();
            fail("closed session must reject calls");
        } catch (BrowserException expected) {
            // ok
        }
    }

    @Test
    public void textIsTruncatedAtTheLimit() throws Exception {
        StringBuilder big = new StringBuilder("<html><head><title>Big</title></head><body><p>");
        for (int i = 0; i < 5000; i++) {
            big.append("word").append(i).append(' ');
        }
        big.append("</p></body></html>");
        page("/big", big.toString());
        StaticHttpBrowserSession s = new StaticHttpBrowserSession(
                UrlSafetyPolicy.allowingPrivateNetworks(),
                new BrowserLimits(1024 * 1024, 500, 10, 10_000));
        BrowserPageSnapshot snap = s.open(base + "/big");
        assertTrue(snap.isTruncated());
        assertEquals(500, snap.getText().length());
        s.close();
    }
}
