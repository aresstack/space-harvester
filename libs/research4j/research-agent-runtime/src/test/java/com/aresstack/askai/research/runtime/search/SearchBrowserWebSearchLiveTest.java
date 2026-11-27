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

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
