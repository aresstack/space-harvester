package com.aresstack.spaceharvester.demo;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake-Confluence für die dokumentierten Beispiele (docs/E2E.md) und die Ende-zu-Ende-Tests: feste Testdaten ohne
 * echte Instanz und ohne Zugangsdaten, über echtes HTTP auf 127.0.0.1.
 *
 * <p>Space {@code DEMO} hat zwei Wurzelseiten, verschachtelte Seiten, Makros, ein eingebettetes Bild mit Anhang und
 * Labels; Space {@code OPS} eine einzelne Seite. Eine unbekannte Seite (etwa {@code 9999}) beantwortet sie mit 404.
 * Manuell starten mit {@code ./gradlew demoConfluence} (Port 8090).
 */
public final class DemoConfluence implements AutoCloseable {

    public static final int DEFAULT_PORT = 8090;

    private final FakeConfluence confluence;
    private final HttpServer server;

    private DemoConfluence(FakeConfluence confluence, int port) throws IOException {
        this.confluence = confluence;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 0);
        server.createContext("/", this::serve);
        server.start();
    }

    /** Startet die Fake-Confluence mit den Demo-Daten; Port 0 wählt einen freien Port. */
    public static DemoConfluence start(int port) throws IOException {
        return new DemoConfluence(fixture(), port);
    }

    /** Startet eine Fake-Confluence mit eigenen Testdaten. */
    public static DemoConfluence start(FakeConfluence confluence) throws IOException {
        return new DemoConfluence(confluence, 0);
    }

    /** Die Demo-Testdaten. */
    public static FakeConfluence fixture() {
        FakeConfluence confluence = new FakeConfluence();
        demoData(confluence);
        return confluence;
    }

    /** Legt die Demo-Testdaten in einer Fake-Confluence an, etwa um sie danach im Test zu verändern. */
    public static void demoData(FakeConfluence confluence) {
        confluence.space("DEMO", "Demo-Handbuch");
        confluence.page("1000", "Start", "DEMO", "<p>Willkommen im Demo-Handbuch.</p>").labels("handbuch");
        confluence.page("1001", "Architektur", "DEMO",
                "<p>Überblick</p>"
                        + "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Nur Testdaten.</p>"
                        + "</ac:rich-text-body></ac:structured-macro>"
                        + "<ac:image><ri:attachment ri:filename=\"diagramm.png\"/></ac:image>")
                .version(7, "2026-10-12T10:30:00.000+02:00").labels("architektur", "handbuch");
        confluence.page("1002", "Schnittstellen", "DEMO",
                "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">json</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[{\"pageId\": \"1002\"}]]></ac:plain-text-body>"
                        + "</ac:structured-macro>");
        confluence.page("1003", "Betrieb", "DEMO", "<p>Betriebshandbuch</p>");
        confluence.page("2000", "Archiv", "DEMO", "<p>Alte Seiten</p>");
        confluence.page("2001", "Altes Konzept", "DEMO", "<p>Abgelöst</p>");
        confluence.child("1000", "1001");
        confluence.child("1001", "1002");
        confluence.child("1000", "1003");
        confluence.child("2000", "2001");
        confluence.homepage("DEMO", "1000");
        confluence.attachment("att1001", "1001", "diagramm.png", "image/png", "kein echtes PNG");
        confluence.space("OPS", "Betrieb");
        confluence.page("3000", "Runbook", "OPS", "<p>Bereitschaft</p>");
    }

    /** Basis-URL inklusive Kontextpfad, wie sie unter {@code space-harvester.sources[].base-url} steht. */
    public URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + FakeConfluence.BASE.getPath());
    }

    public FakeConfluence confluence() {
        return confluence;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    /** Bildet eine HTTP-Anfrage wie {@code FakeConfluenceServer} auf die feste Basis-URL des Fakes ab. */
    private void serve(HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().get(0));
            }
        }
        ConfluenceHttpResponse response;
        try {
            synchronized (confluence) {
                response = confluence.get(mapped, headers, Integer.MAX_VALUE);
            }
        } catch (IOException e) {
            response = new ConfluenceHttpResponse(500, "text/plain", new byte[0]);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    /** Startet die Demo-Fake-Confluence bis zum Beenden des Prozesses; optional mit Port als Argument. */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        DemoConfluence demo = start(port);
        System.out.println("Demo-Confluence läuft unter " + demo.baseUrl() + " (Strg+C beendet)");
        Thread.currentThread().join();
    }
}
