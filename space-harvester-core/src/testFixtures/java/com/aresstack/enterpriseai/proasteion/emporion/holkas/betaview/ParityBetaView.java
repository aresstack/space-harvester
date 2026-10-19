package com.aresstack.enterpriseai.proasteion.emporion.holkas.betaview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Feste Vergleichsdaten für die BetaView-Quelle (#193): eine kleine BetaView-Weboberfläche im Prozess, nur auf
 * {@code 127.0.0.1}, über den eingebauten HTTP-Server des JDK. Sie spielt den Ablauf der Quelle durch wie der
 * Fake-Server in {@code BetaViewKnowledgeSourceTest} des Enterprise AI Client: Login mit Struts-Token und
 * Sitzungs-Cookie, CSRF-Token aus {@code getAppContext.action}, Ergebnisliste des Favoriten {@link #FAVORITE_ID},
 * Öffnen je Zeile, Download im Originalformat.
 *
 * <ul>
 *   <li>Je Dokument ({@link #DOCUMENT_IDS}) ein Titel aus {@link #TITLES} und ein Satz aus {@link #SENTENCES};
 *       {@link #SEARCH} trifft nur {@link #SEARCH_HIT}.</li>
 *   <li>Die Anmeldung nimmt jeden Benutzer mit nicht leerem Passwort an; echte Zugangsdaten braucht es nicht.</li>
 *   <li>Ohne Sitzungs-Cookie antworten alle Aktionen außer {@code login.action} mit 403.</li>
 * </ul>
 *
 * <p>Java 8, ohne weitere Bibliotheken.
 */
public final class ParityBetaView implements AutoCloseable {

    public static final String BASE_PATH = "/betaview/";
    public static final String FAVORITE_ID = "42";
    public static final String[] DOCUMENT_IDS = {"AB12", "CD34", "EF56"};
    public static final String[] TITLES = {"ZAUF01", "ZAUF02", "NACHT03"};
    public static final String[] DATES = {"01.10.2026", "02.10.2026", "03.10.2026"};
    public static final String[] SENTENCES = {"Satz Alpha in der ersten Liste.", "Satz Bravo in der zweiten Liste.",
            "Satz Charlie zum Nachtlauf in der dritten Liste."};
    public static final String SEARCH = "Nachtlauf";
    public static final String SEARCH_HIT = "EF56";

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String SESSION = "JSESSIONID=P1";
    private static final String CSRF = "C1";
    private static final String TOKEN_FIELDS = "<input type=\"hidden\" name=\"struts.token.name\" value=\"token\">"
            + "<input type=\"hidden\" name=\"token\" value=\"T1\">";
    private static final String LOGIN_PAGE = "<form>" + TOKEN_FIELDS
            + "<input name=\"userName\"><input type=\"password\" name=\"password\"></form>";

    private final HttpServer server;
    /** Das zuletzt geöffnete Dokument, dessen Inhalt der Download liefert. */
    private volatile int opened = -1;

    private ParityBetaView(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext(BASE_PATH, this::handle);
        server.start();
    }

    /** Startet die Oberfläche auf einem freien Port von {@code 127.0.0.1}. */
    public static ParityBetaView start() throws IOException {
        return start(0);
    }

    /** Startet die Oberfläche auf {@code port} von {@code 127.0.0.1}; {@code 0} wählt einen freien Port. */
    public static ParityBetaView start(int port) throws IOException {
        return new ParityBetaView(port);
    }

    /** Adresse für {@code options.baseUrl}, z. B. {@code http://127.0.0.1:4711/betaview/}. */
    public String baseUrl() {
        return "http://127.0.0.1:" + port() + BASE_PATH;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    /** Inhalt je Dokument-ID, in der Reihenfolge von {@link #DOCUMENT_IDS}. */
    public static Map<String, String> documents() {
        Map<String, String> documents = new LinkedHashMap<String, String>();
        for (int i = 0; i < DOCUMENT_IDS.length; i++) {
            documents.put(DOCUMENT_IDS[i], SENTENCES[i] + "\n");
        }
        return Collections.unmodifiableMap(documents);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String action = exchange.getRequestURI().getPath().substring(BASE_PATH.length());
            Map<String, String> query = form(exchange.getRequestURI().getRawQuery());
            boolean post = "POST".equals(exchange.getRequestMethod());
            Map<String, String> body = post ? form(read(exchange.getRequestBody()))
                    : Collections.<String, String>emptyMap();
            if ("login.action".equals(action)) {
                String password = body.get("password");
                if (!post || password == null || password.isEmpty() || !"T1".equals(body.get("token"))) {
                    html(exchange, LOGIN_PAGE);
                } else {
                    exchange.getResponseHeaders().add("Set-Cookie", SESSION + "; Path=/betaview");
                    exchange.getResponseHeaders().add("Location", "welcome.action");
                    send(exchange, 302, null, new byte[0]);
                }
                return;
            }
            String cookie = exchange.getRequestHeaders().getFirst("Cookie");
            if (cookie == null || !cookie.contains(SESSION)) {
                send(exchange, 403, null, new byte[0]);
                return;
            }
            if ("welcome.action".equals(action)) {
                html(exchange, "<html>willkommen</html>");
            } else if ("getAppContext.action".equals(action)) {
                html(exchange, "{\"csrfToken\":{\"field\":\"csrfToken\",\"name\":\"csrfToken\",\"value\":\"" + CSRF
                        + "\"}}");
            } else if ("select.action".equals(action) && FAVORITE_ID.equals(query.get("favoriteID"))) {
                html(exchange, "<form>" + TOKEN_FIELDS + "</form>");
            } else if ("result.action".equals(action)) {
                html(exchange, "ok");
            } else if ("showResult.action".equals(action)) {
                html(exchange, resultTable());
            } else if ("open.action".equals(action) && index(query.get("index")) >= 0) {
                opened = index(query.get("index"));
                html(exchange, tabs(opened));
            } else if ("opendocumentlink.action".equals(action) && byId(query.get("docid")) >= 0
                    && FAVORITE_ID.equals(query.get("favid"))) {
                opened = byId(query.get("docid"));
                html(exchange, tabs(opened));
            } else if ("downloadDialog.action".equals(action)) {
                html(exchange, "<input type=\"hidden\" name=\"pages\" value=\"1\">");
            } else if ("downloadWithOptions.action".equals(action) && post && opened >= 0) {
                send(exchange, 200, "text/plain", (SENTENCES[opened] + "\n").getBytes(UTF_8));
            } else if ("closeAllDocuments.action".equals(action)) {
                opened = -1;
                html(exchange, "");
            } else {
                send(exchange, 404, null, new byte[0]);
            }
        } finally {
            exchange.close();
        }
    }

    private static String resultTable() {
        StringBuilder html = new StringBuilder("<table><thead><tr><th>Datum</th><th>Report</th></tr></thead><tbody>");
        for (int i = 0; i < DOCUMENT_IDS.length; i++) {
            html.append("<tr><td>").append(DATES[i]).append("</td><td><a href=\"#\" onclick=\"bwe.result.openDocument(")
                    .append("'open.action?index=").append(i).append("&amp;source=resulttable');\">").append(TITLES[i])
                    .append("</a></td></tr>");
        }
        return html.append("</tbody></table>").toString();
    }

    private static String tabs(int index) {
        return "<ul id=\"we_id_docbrowser_tabs\"><li class=\"active\"><div class=\"el_tab_content\">"
                + "<a href=\"opendocumentlink.action?docid=" + DOCUMENT_IDS[index] + "&amp;favid=" + FAVORITE_ID
                + "\">x</a><span class=\"el_tab_element\">" + DATES[index] + "</span><span class=\"el_tab_element\">"
                + TITLES[index] + "</span></div></li></ul>";
    }

    private static int index(String value) {
        try {
            int index = Integer.parseInt(value);
            return index >= 0 && index < DOCUMENT_IDS.length ? index : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int byId(String docId) {
        for (int i = 0; i < DOCUMENT_IDS.length; i++) {
            if (DOCUMENT_IDS[i].equals(docId)) {
                return i;
            }
        }
        return -1;
    }

    private static Map<String, String> form(String encoded) throws IOException {
        Map<String, String> fields = new LinkedHashMap<String, String>();
        if (encoded == null || encoded.isEmpty()) {
            return fields;
        }
        for (String pair : encoded.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            String name = URLDecoder.decode(eq < 0 ? pair : pair.substring(0, eq), "UTF-8");
            fields.put(name, eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
        }
        return fields;
    }

    private static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int n;
        while ((n = in.read(buffer)) > 0) {
            out.write(buffer, 0, n);
        }
        return new String(out.toByteArray(), UTF_8);
    }

    private static void html(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "text/html;charset=utf-8", body.getBytes(UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        if (contentType != null) {
            exchange.getResponseHeaders().set("Content-Type", contentType);
        }
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
