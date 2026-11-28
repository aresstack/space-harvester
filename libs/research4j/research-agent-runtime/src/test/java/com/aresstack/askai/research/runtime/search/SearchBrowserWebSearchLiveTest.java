package com.aresstack.askai.research.runtime.search;

import com.aresstack.askai.browser.playwright.TestBrowserLauncher;
import com.aresstack.askai.browser.search.LegacyBrowserSearchDefaults;
import com.aresstack.askai.browser.search.engine.BrowserSearchEngineSelection;
import com.aresstack.askai.browser.search.engine.EngineAcquisitionMode;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The chat's web search end to end in a real search browser: a search page of the host's own (a URL with
 * {@code {query}}, like an intranet portal) is searched in the search browser, its results come back as hits, and a
 * hit the chat's block list refuses is left out. The browser comes from {@link TestBrowserLauncher} (environment);
 * without one the test is skipped with the reason.
 */
public class SearchBrowserWebSearchLiveTest {

    @Test
    public void aSearchPageOfTheHostsOwnIsSearchedInTheSearchBrowser() throws Exception {
        TestBrowserLauncher launcher = TestBrowserLauncher.fromEnvironment();
        assumeTrue("SKIPPED (environment-gated): " + launcher.readiness().render(),
                launcher.readiness().render().startsWith("READY"));
        HttpServer content = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        content.createContext("/", exchange -> respond(exchange, "<html><body>Inhalt</body></html>"));
        content.start();
        String base = "http://127.0.0.1:" + content.getAddress().getPort();
        // A structurally valid portal result page: navigation plus three similar result blocks.
        HttpServer portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        portal.createContext("/find", exchange -> respond(exchange, "<!doctype html><html><head><title>Find</title>"
                + "</head><body><nav><a href='/videos?q=x'>Videos</a><a href='/settings'>Settings</a></nav>"
                + "<main><ul id='r'>"
                + "<li><h2><a href='" + base + "/a'>Trennungsgeld Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing when the separation allowance is paid.</p></li>"
                + "<li><h2><a href='" + base + "/b'>Umzugskosten Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing which removal costs are refunded.</p></li>"
                + "<li><h2><a href='" + base + "/gesperrt'>Third Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing the third target of this search.</p></li>"
                + "</ul></main></body></html>"));
        portal.start();
        String searchPage = "http://127.0.0.1:" + portal.getAddress().getPort() + "/find?q={query}";
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Collections.singletonList(
                new BrowserSearchEngineSelection.Entry(searchPage, true, 1, 0, 0, Collections.<String>emptyList(),
                        Collections.<String>emptyList())), EngineAcquisitionMode.FIRST_USABLE);
        SearchBrowserWebSearch search = new SearchBrowserWebSearch(launcher, () -> true,
                LegacyBrowserSearchDefaults::create, chat -> selection);
        try {
            String hits = search.search(new ChatConversationId("chat-1"), "Trennungsgeld", null,
                    url -> url.contains("/gesperrt"));

            assertTrue(hits, hits.contains(base + "/a") && hits.contains("Trennungsgeld Merkblatt"));
            assertFalse(hits, hits.contains("/gesperrt"));
        } finally {
            search.close();
            portal.stop(0);
            content.stop(0);
        }
    }

    /**
     * A search page named only by its address: the search browser finds its search field, types the words and reads the
     * results; the result URL it reached is remembered, so the next search opens it directly instead of the page.
     */
    @Test
    public void aSearchPageNamedOnlyByItsAddressIsSearchedThroughItsFieldAndItsResultUrlIsRemembered() throws Exception {
        TestBrowserLauncher launcher = TestBrowserLauncher.fromEnvironment();
        assumeTrue("SKIPPED (environment-gated): " + launcher.readiness().render(),
                launcher.readiness().render().startsWith("READY"));
        HttpServer content = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        content.createContext("/", exchange -> respond(exchange, "<html><body>Inhalt</body></html>"));
        content.start();
        String base = "http://127.0.0.1:" + content.getAddress().getPort();
        java.util.List<String> visited = java.util.Collections.synchronizedList(new java.util.ArrayList<String>());
        HttpServer portal = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        portal.createContext("/", exchange -> {
            visited.add(exchange.getRequestURI().toString());
            if (exchange.getRequestURI().getPath().startsWith("/find")) {
                respond(exchange, "<!doctype html><html><head><title>Treffer</title></head><body>"
                        + "<nav><a href='/'>Start</a><a href='/hilfe'>Hilfe</a></nav><main><ul id='r'>"
                        + "<li><h2><a href='" + base + "/a'>Trennungsgeld Merkblatt (result)</a></h2>"
                        + "<p>Explanatory snippet describing when the separation allowance is paid.</p></li>"
                        + "<li><h2><a href='" + base + "/b'>Umzugskosten Merkblatt (result)</a></h2>"
                        + "<p>Explanatory snippet describing which removal costs are refunded.</p></li>"
                        + "<li><h2><a href='" + base + "/c'>Reisekosten Merkblatt (result)</a></h2>"
                        + "<p>Explanatory snippet describing which travel costs are refunded.</p></li>"
                        + "</ul></main></body></html>");
            } else {
                respond(exchange, "<!doctype html><html><head><title>Portal</title></head><body>"
                        + "<header><form action='/find' method='get'><input type='search' name='text' "
                        + "aria-label='Suche'><button>Suchen</button></form></header>"
                        + "<main><p>Willkommen im Portal.</p></main></body></html>");
            }
        });
        portal.start();
        String page = "http://127.0.0.1:" + portal.getAddress().getPort() + "/";
        BrowserSearchEngineSelection selection = new BrowserSearchEngineSelection(Collections.singletonList(
                new BrowserSearchEngineSelection.Entry(page, true, 1, 0, 0, Collections.<String>emptyList(),
                        Collections.<String>emptyList())), EngineAcquisitionMode.FIRST_USABLE);
        SearchBrowserWebSearch search = new SearchBrowserWebSearch(launcher, () -> true,
                LegacyBrowserSearchDefaults::create, chat -> selection);
        try {
            String first = search.search(new ChatConversationId("chat-1"), "Trennungsgeld", null, url -> false);
            assertTrue(first, first.contains(base + "/a"));
            int startPageVisits = java.util.Collections.frequency(visited, "/");
            assertTrue(visited.toString(), startPageVisits >= 1);

            String second = search.search(new ChatConversationId("chat-1"), "Reisekosten", null, url -> false);
            assertTrue(second, second.contains(base + "/c"));
            assertTrue("remembered result URL, no second visit of the page: " + visited,
                    java.util.Collections.frequency(visited, "/") == startPageVisits
                            && visited.contains("/find?text=Reisekosten"));
        } finally {
            search.close();
            portal.stop(0);
            content.stop(0);
        }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
