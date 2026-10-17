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
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fake-Confluence für die dokumentierten Beispiele (docs/E2E.md) und die Ende-zu-Ende-Tests: feste Testdaten ohne
 * echte Instanz und ohne Zugangsdaten, über echtes HTTP auf 127.0.0.1.
 *
 * <p>Space {@code DEMO} hat zwei Wurzelseiten, verschachtelte Seiten, Makros, ein eingebettetes Bild und ein
 * draw.io-Diagramm als Verweise auf Anhänge sowie Labels; Space {@code OPS} eine einzelne Seite. Eine unbekannte Seite (etwa {@code 9999}) beantwortet sie mit 404.
 * Manuell starten mit {@code ./gradlew demoConfluence} (Port 8090).
 *
 * <p>Für die Durchreichung von Endnutzer-Tokens (#24) kennt sie einen Endnutzer: Mit {@value #USER_TOKEN} als
 * Bearer-Token antwortet {@code /rest/api/user/current} mit {@code anna}, und Anna sieht weder Seite
 * {@value #RESTRICTED_PAGE} noch den Space {@code OPS} (404 wie bei Confluence). Jedes andere Bearer-Token lehnt sie
 * mit 401 ab. Anfragen ohne Bearer-Token (das Ernten mit {@code auth: none}) bleiben unverändert.
 */
public final class DemoConfluence implements AutoCloseable {

    public static final int DEFAULT_PORT = 8090;
    /** Confluence-Token der Demo-Endnutzerin Anna, nur für diese Demo. */
    public static final String USER_TOKEN = "anna-token-nur-lokal";
    static final String RESTRICTED_PAGE = "2001";
    private static final String CONTEXT = FakeConfluence.BASE.getPath();

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
        page(confluence, "1000", "Start", "DEMO", "<p>Willkommen im Demo-Handbuch.</p>").labels("handbuch");
        // Storage-Format wie in Confluence: Makros als ac:structured-macro, Bilder und draw.io-Diagramme nur als
        // Verweis auf Anhänge; die gerenderte Ansicht (body.view) weicht davon ab.
        confluence.page("1001", "Architektur", "DEMO",
                        "<p>Überblick</p><div class=\"confluence-information-macro\"><p>Nur Testdaten.</p></div>"
                                + "<img src=\"/download/attachments/1001/diagramm.png\"/>")
                .storage("<p>Überblick</p>"
                        + "<ac:structured-macro ac:name=\"info\"><ac:rich-text-body><p>Nur Testdaten.</p>"
                        + "</ac:rich-text-body></ac:structured-macro>"
                        + "<ac:image><ri:attachment ri:filename=\"diagramm.png\" /></ac:image>"
                        + "<ac:structured-macro ac:name=\"drawio\"><ac:parameter ac:name=\"diagramName\">"
                        + "architektur</ac:parameter></ac:structured-macro>")
                .version(7, "2026-10-12T10:30:00.000+02:00").labels("architektur", "handbuch");
        page(confluence, "1002", "Schnittstellen", "DEMO",
                "<h2>REST-Schnittstelle</h2><p>Die API (Application Programming Interface) liefert JSON.</p>"
                        + "<ac:structured-macro ac:name=\"code\"><ac:parameter ac:name=\"language\">json</ac:parameter>"
                        + "<ac:plain-text-body><![CDATA[{\"pageId\": \"1002\"}]]></ac:plain-text-body>"
                        + "</ac:structured-macro>");
        page(confluence, "1003", "Betrieb", "DEMO", "<p>Betriebshandbuch</p>");
        page(confluence, "2000", "Archiv", "DEMO", "<p>Alte Seiten</p>");
        page(confluence, "2001", "Altes Konzept", "DEMO", "<p>Abgelöst</p>");
        confluence.child("1000", "1001");
        confluence.child("1001", "1002");
        confluence.child("1000", "1003");
        confluence.child("2000", "2001");
        confluence.homepage("DEMO", "1000");
        confluence.attachment("att1001", "1001", "diagramm.png", "image/png", "kein echtes PNG");
        confluence.attachment("att1002", "1001", "architektur", "application/vnd.jgraph.mxfile",
                "<mxfile><diagram name=\"Architektur\"><mxGraphModel><root><mxCell id=\"0\"/>"
                        + "<mxCell id=\"1\" value=\"Harvester\" vertex=\"1\"/></root></mxGraphModel></diagram></mxfile>");
        confluence.space("OPS", "Betrieb");
        page(confluence, "3000", "Runbook", "OPS", "<p>Bereitschaft</p>");
    }

    /** Eine Seite, deren Storage-Format und gerenderte Ansicht übereinstimmen. */
    public static FakeConfluence.FakePage page(FakeConfluence confluence, String id, String title, String spaceKey,
                                               String storage) {
        return confluence.page(id, title, spaceKey, storage).storage(storage);
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
        ConfluenceHttpResponse response =
                endUser(incoming.getRawPath(), exchange.getRequestHeaders().getFirst("Authorization"));
        if (response == null) {
            try {
                synchronized (confluence) {
                    response = confluence.get(mapped, headers, Integer.MAX_VALUE);
                }
            } catch (IOException e) {
                response = new ConfluenceHttpResponse(500, "text/plain", new byte[0]);
            }
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

    /**
     * Antwort für eine Anfrage mit Bearer-Token eines Endnutzers, soweit sie von den Testdaten abweicht; {@code null}
     * für Anfragen ohne Bearer-Token und für alles, was Anna sehen darf.
     */
    private static ConfluenceHttpResponse endUser(String path, String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return null;
        }
        if (!authorization.equals("Bearer " + USER_TOKEN)) {
            return text(401);
        }
        if (path.equals(CONTEXT + "/rest/api/user/current")) {
            return new ConfluenceHttpResponse(200, "application/json",
                    "{\"type\":\"known\",\"userKey\":\"anna\",\"username\":\"anna\",\"displayName\":\"Anna\"}"
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (path.equals(CONTEXT + "/rest/api/content/" + RESTRICTED_PAGE)
                || path.startsWith(CONTEXT + "/rest/api/content/" + RESTRICTED_PAGE + "/")
                || path.equals(CONTEXT + "/rest/api/space/OPS")) {
            return text(404);
        }
        return null;
    }

    private static ConfluenceHttpResponse text(int status) {
        return new ConfluenceHttpResponse(status, "text/plain", new byte[0]);
    }

    /** Startet die Demo-Fake-Confluence bis zum Beenden des Prozesses; optional mit Port als Argument. */
    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        DemoConfluence demo = start(port);
        System.out.println("Demo-Confluence läuft unter " + demo.baseUrl() + " (Strg+C beendet)");
        Thread.currentThread().join();
    }
}
