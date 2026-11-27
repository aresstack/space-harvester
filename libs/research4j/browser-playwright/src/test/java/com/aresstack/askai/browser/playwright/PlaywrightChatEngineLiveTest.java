package com.aresstack.askai.browser.playwright;

import com.aresstack.enterpriseai.browser.api.BrowserAction;
import com.aresstack.enterpriseai.browser.api.BrowserProbe;
import com.aresstack.enterpriseai.browser.api.engine.BrowserConnection;
import com.aresstack.enterpriseai.browser.api.engine.BrowserInstance;
import com.aresstack.enterpriseai.browser.api.engine.RawActOutcome;
import com.aresstack.enterpriseai.browser.api.engine.RawControl;
import com.aresstack.enterpriseai.browser.api.engine.RawDownload;
import com.aresstack.enterpriseai.browser.api.engine.RawLink;
import com.aresstack.enterpriseai.browser.api.engine.RawScreenshot;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

/**
 * The chat browser engine against a real browser and a local test server: tabs, load, read, links, controls, real
 * typing and submit, scripted select, consent probe, screenshot, in-page fetch and parallel loads. Environment-gated:
 * channel from {@code ASKAI_TEST_BROWSER_CHANNEL} (default chrome), an executable from
 * {@code ASKAI_TEST_BROWSER_EXECUTABLE} if the channel's browser is not installed at its usual place.
 */
public class PlaywrightChatEngineLiveTest {

    private static final String FORM = "<html><head><title>Formular</title></head><body>"
            + "<div id='cookie-banner' role='dialog'>Wir verwenden Cookies. <button>Alle akzeptieren</button></div>"
            + "<a href='/second'>Zweite Seite</a> <a href='/doc.txt'>Dokument</a>"
            + "<form action='/result' method='get'><label for='q'>Suchbegriff</label>"
            + "<input id='q' name='q' type='text'>"
            + "<select name='farbe'><option>Rot</option><option>Blau</option></select>"
            + "<button type='submit'>Suchen</button></form></body></html>";

    @Test
    public void theChatEngineDrivesARealBrowser() throws Exception {
        String channel = System.getenv().getOrDefault("ASKAI_TEST_BROWSER_CHANNEL", "chrome");
        String executable = System.getenv("ASKAI_TEST_BROWSER_EXECUTABLE");
        if (executable != null && !executable.isEmpty()) {
            BrowserExecutables.use(channel, executable);
        }
        String readiness = PlaywrightChatEngine.readiness(channel);
        assumeTrue("SKIPPED (environment-gated live test): " + readiness, readiness.startsWith("READY"));

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/form", exchange -> respond(exchange, "text/html", FORM));
        server.createContext("/second", exchange -> respond(exchange, "text/html",
                "<html><head><title>Zweite</title></head><body><p>Inhalt der zweiten Seite</p></body></html>"));
        server.createContext("/result", exchange -> respond(exchange, "text/html",
                "<html><head><title>Ergebnis</title></head><body><p>Gesucht: "
                        + exchange.getRequestURI().getQuery() + "</p></body></html>"));
        server.createContext("/doc.txt", exchange -> respond(exchange, "text/plain", "Dokumenttext"));
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        Path profile = Files.createTempDirectory("chat-engine-profile");
        BrowserInstance instance = new PlaywrightChatEngine(channel, true, 60000L, 30000L).start(profile);
        try {
            BrowserConnection connection = instance.connect();
            String tab = connection.tabs().isEmpty() ? connection.createTab() : connection.tabs().get(0).contextId();
            connection.navigate(tab, base + "/form");
            assertEquals("Formular", connection.read(tab).title());

            BrowserProbe probe = connection.probe(tab);
            assertTrue(probe.consent());
            assertTrue(probe.consentOptions().contains("Alle akzeptieren"));

            List<RawLink> links = connection.links(tab);
            assertEquals(base + "/second", links.get(0).href());

            RawDownload document = connection.fetch(tab, base + "/doc.txt", 1024 * 1024);
            assertEquals("Dokumenttext", new String(document.content(), StandardCharsets.UTF_8));

            RawScreenshot shot = connection.screenshot(tab, Collections.emptyMap(), null, 4 * 1024 * 1024);
            assertEquals("image/jpeg", shot.mediaType());

            RawControl field = null;
            RawControl select = null;
            for (RawControl control : connection.controls(tab)) {
                if ("Suchbegriff".equals(control.label())) {
                    field = control;
                }
                if ("select".equals(control.kind())) {
                    select = control;
                }
            }
            assertTrue("search field found", field != null && select != null);
            connection.act(tab, select.ref(), BrowserAction.SELECT, "Blau");
            RawActOutcome submitted = connection.act(tab, field.ref(), BrowserAction.SUBMIT, "Trennungsgeld");
            assertTrue(submitted.navigated());
            String result = connection.read(tab).text();
            assertTrue(result, result.contains("q=Trennungsgeld") && result.contains("farbe=Blau"));

            String second = connection.createTab();
            Map<String, String> loads = new LinkedHashMap<>();
            loads.put(tab, base + "/second");
            loads.put(second, base + "/form");
            assertTrue(connection.navigateAll(loads).isEmpty());
            assertEquals("Zweite", connection.read(tab).title());
            assertEquals("Formular", connection.read(second).title());
            connection.close(second);
            assertEquals(1, connection.tabs().size());
        } finally {
            instance.stop();
            server.stop(0);
        }
        assertFalse(instance.isAlive());
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, String type, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type + "; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
