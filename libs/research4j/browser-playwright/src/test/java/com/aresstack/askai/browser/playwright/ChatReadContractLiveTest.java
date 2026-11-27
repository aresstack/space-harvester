package com.aresstack.askai.browser.playwright;

import com.aresstack.askai.browser.BrowserLink;
import com.aresstack.askai.browser.BrowserPageSnapshot;
import com.aresstack.enterpriseai.astu.chat.ChatConversationId;
import com.aresstack.enterpriseai.browser.api.BrowserLinks;
import com.aresstack.enterpriseai.browser.api.BrowserPage;
import com.aresstack.enterpriseai.browser.api.ChatBrowser;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawPage;
import com.aresstack.enterpriseai.browser.runtime.BrowserProfileStore;
import com.aresstack.enterpriseai.browser.runtime.ChatBrowserRuntime;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * Step 5 of the one implementation: the chat's reading (B, {@link PageOperations} behind the chat tools'
 * browser_read, browser_links, browser_follow) and the research session's reading (A, {@link PlaywrightBrowserSession}
 * behind web_read, web_links, web_follow) answer the same for the same tab. Both run on one browser and one owner
 * thread since the chat engine starts the research driver; this test reads one page through both.
 */
public class ChatReadContractLiveTest {

    private static final String PAGE = "<html><head><title>Vertrag</title></head><body>"
            + "<nav><a href='/a'>Erste Seite</a> <a href='/b'>Zweite Seite</a></nav>"
            + "<main><h1>Überschrift</h1><p>Ein Absatz mit   Leerraum.</p>"
            + "<p style='display:none'>Unsichtbar</p>"
            + "<ul><li>Punkt eins</li><li>Punkt zwei</li></ul>"
            + "<a href='/b#teil'>Zweite Seite, Teil</a> <a href='https://example.org/extern'>Extern</a></main>"
            + "</body></html>";

    @Test
    public void chatAndResearchReadTheSameTabAlike() throws Exception {
        TestBrowserLauncher launcher = TestBrowserLauncher.fromEnvironment();
        String readiness = launcher.readiness().render();
        assumeTrue("SKIPPED (environment-gated live test): " + readiness, readiness.startsWith("READY"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/start", exchange -> respond(exchange, PAGE));
        server.createContext("/b", exchange -> respond(exchange,
                "<html><head><title>Zweite</title></head><body><p>Inhalt der zweiten Seite</p></body></html>"));
        server.createContext("/a", exchange -> respond(exchange,
                "<html><head><title>Erste</title></head><body><p>Inhalt der ersten Seite</p></body></html>"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        PlaywrightChatEngine.Instance instance = (PlaywrightChatEngine.Instance)
                new PlaywrightChatEngine(launcher, true, 30000L).start(Files.createTempDirectory("contract-profile"));
        // The chat tools run on this same browser: the runtime's engine hands out the started instance.
        ChatBrowserRuntime runtime = new ChatBrowserRuntime(profile -> instance,
                new BrowserProfileStore(Files.createTempDirectory("contract-profiles")));
        try {
            BrowserConnection chat = instance.connect();
            String tab = chat.tabs().get(0).contextId();
            // The research session opens the page in its tab, the chat's first tab; both then read that tab.
            BrowserPageSnapshot a = instance.actor.call(session -> session.open(base + "/start"));

            // Lesen: the same address, title and visible text.
            RawPage b = chat.read(tab);
            assertEquals(b.url(), a.getUrl());
            assertEquals(b.title(), a.getTitle());
            assertEquals(lines(b.text()), lines(a.getText()));
            assertTrue(b.text(), !b.text().contains("Unsichtbar"));

            // Links: the same targets.
            List<RawLink> bLinks = chat.links(tab);
            List<BrowserLink> aLinks = instance.actor.call(session -> session.links());
            Set<String> bTargets = new LinkedHashSet<>();
            for (RawLink link : bLinks) {
                bTargets.add(link.href());
            }
            Set<String> aTargets = new LinkedHashSet<>();
            for (BrowserLink link : aLinks) {
                aTargets.add(link.getUrl());
            }
            assertEquals(bTargets, aTargets);

            // Folgen: the chat's browser_follow (ChatBrowserRuntime: link snapshot, then navigate) and the research
            // session's web_follow each follow the link to /b from /start and reach the same page.
            ChatBrowser chatTools = runtime.browserOf(new ChatConversationId("contract"));
            chatTools.navigate(null, base + "/start");
            String chatLinkId = null;
            for (com.aresstack.enterpriseai.browser.api.BrowserLink link
                    : chatTools.links(null, BrowserLinks.MAX_LINKS, null).links()) {
                if (link.url().equals(base + "/b")) {
                    chatLinkId = link.id();
                }
            }
            assertTrue("chat link to /b among " + bTargets, chatLinkId != null);
            BrowserPage chatFollowed = chatTools.follow(null, chatLinkId);
            assertEquals(base + "/b", chatFollowed.url());

            instance.actor.call(session -> session.open(base + "/start"));
            String researchLinkId = null;
            for (BrowserLink link : instance.actor.call(session -> session.links())) {
                if (link.getUrl().equals(base + "/b")) {
                    researchLinkId = link.getId();
                }
            }
            assertTrue("research link to /b among " + aTargets, researchLinkId != null);
            final String linkId = researchLinkId;
            BrowserPageSnapshot researchFollowed = instance.actor.call(session -> session.follow(linkId));
            assertEquals(chatFollowed.url(), researchFollowed.getUrl());
            assertEquals(chatFollowed.title(), researchFollowed.getTitle());
            assertEquals(lines(chatFollowed.text()), lines(researchFollowed.getText()));
        } finally {
            runtime.releaseAll();
            instance.stop();
            server.stop(0);
        }
    }

    /** The text as the model reads it: non-empty lines with collapsed white space. */
    private static List<String> lines(String text) {
        return java.util.Arrays.stream(text.split("\n")).map(line -> line.replaceAll("\\s+", " ").trim())
                .filter(line -> !line.isEmpty()).collect(java.util.stream.Collectors.toList());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
