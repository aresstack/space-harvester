package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.runtime.BrowserProfileStore;
import com.aresstack.enterpriseai.browser.runtime.ChatBrowserRuntime;
import com.aresstack.enterpriseai.proasteion.application.agent.BrowserMcpTools;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolCall;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolContribution;
import com.aresstack.enterpriseai.proasteion.katagogion.McpToolResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The fourteen chat tools of browser-tools end to end over the Playwright engine and a local test server:
 * search, navigate, links, open_many. Environment-gated like {@link PlaywrightChatEngineLiveTest}.
 */
public class ChatToolsOverPlaywrightLiveTest {

    @Test
    public void theChatToolsWorkOverThePlaywrightEngine() throws Exception {
        TestBrowserLauncher launcher = TestBrowserLauncher.fromEnvironment();
        String readiness = launcher.readiness().render();
        assumeTrue("SKIPPED (environment-gated live test): " + readiness, readiness.startsWith("READY"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/a", exchange -> respond(exchange,
                "<html><head><title>Seite A</title></head><body><p>Trennungsgeld wird gezahlt.</p>"
                        + "<a href='/b'>Weiter zu B</a></body></html>"));
        server.createContext("/b", exchange -> respond(exchange,
                "<html><head><title>Seite B</title></head><body><p>Umzugskosten.</p></body></html>"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        // The search engine lives on its own server; its organic results link to the content server.
        HttpServer engine = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // A structurally valid artificial SERP like PlaywrightLiveBrowserTest's: navigation plus three similar
        // result blocks (heading link and explanatory snippet).
        engine.createContext("/find", exchange -> respond(exchange, "<!doctype html><html><head><title>Find</title>"
                + "</head><body><nav><a href='/videos?q=x'>Videos</a><a href='/settings'>Settings</a></nav>"
                + "<main><ul id='r'>"
                + "<li><h2><a href='" + base + "/a'>Trennungsgeld Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing when the separation allowance is paid.</p></li>"
                + "<li><h2><a href='" + base + "/b'>Umzugskosten Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing which removal costs are refunded.</p></li>"
                + "<li><h2><a href='" + base + "/a?v=3'>Third Merkblatt (result)</a></h2>"
                + "<p>Explanatory snippet describing the third target of this search.</p></li>"
                + "</ul></main></body></html>"));
        engine.start();
        String engineBase = "http://127.0.0.1:" + engine.getAddress().getPort();
        ChatBrowserRuntime runtime = new ChatBrowserRuntime(new PlaywrightChatEngine(launcher, true, 30000L)
                .searchingLocally(engineBase + "/find?q={query}"),
                new BrowserProfileStore(Files.createTempDirectory("chat-profiles")));
        try {
            Map<String, McpToolContribution> tools = new HashMap<>();
            for (McpToolContribution tool : new BrowserMcpTools(runtime).contributionsFor(
                    new ChatConversationId("chat-1"))) {
                tools.put(tool.getName(), tool);
            }
            assertEquals(14, tools.size());

            String hits = call(tools, BrowserMcpTools.SEARCH, Map.of("query", "Trennungsgeld"));
            assertTrue(hits, hits.contains(base + "/a") && hits.contains("Trennungsgeld Merkblatt"));

            String page = call(tools, BrowserMcpTools.NAVIGATE, Map.of("url", base + "/a"));
            assertTrue(page, page.contains("Trennungsgeld"));
            String links = call(tools, BrowserMcpTools.LINKS, Map.of());
            assertTrue(links, links.contains("Weiter zu B"));
            String many = call(tools, BrowserMcpTools.OPEN_MANY, Map.of("urls", Arrays.asList(base + "/a",
                    base + "/b")));
            assertTrue(many, many.contains("Umzugskosten") && many.contains("Trennungsgeld"));
        } finally {
            runtime.releaseAll();
            server.stop(0);
            engine.stop(0);
        }
    }

    private static String call(Map<String, McpToolContribution> tools, String name, Map<String, Object> arguments)
            throws Exception {
        McpToolResult result = tools.get(name).getHandler().invoke(new McpToolCall(name, arguments));
        assertFalse(name + ": " + result.getText(), result.isError());
        return result.getText();
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
