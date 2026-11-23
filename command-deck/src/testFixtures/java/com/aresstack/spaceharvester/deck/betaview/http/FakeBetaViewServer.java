package com.aresstack.spaceharvester.deck.betaview.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Ein Schein-BetaView-Server für Tests und Bildschirmfotos: spricht dasselbe Struts-Protokoll wie der echte Server,
 * soweit MainframeMate es nutzt (Anmeldung mit Formular-Token und Weiterleitung, CSRF-Token aus
 * {@code getAppContext.action}, Suche über {@code select}/{@code result}/{@code showResult}, Dokumentbrowser mit
 * Reitern, Seiten als JSON, Download-Dialog, Lesezeichen, gespeicherte Suchen). Alle Daten sind erfunden. Prüft
 * Sitzungs-Cookie, CSRF-Token und Formular-Token wie der echte Server und leitet sonst zur Anmeldung um.
 */
public final class FakeBetaViewServer implements AutoCloseable {

    public static final String USER = "pilot";
    public static final String PASSWORD = "sternenstaub";
    public static final String FAVORITE = "F001";
    public static final String CONTEXT = "/betaview/";

    /** Ein erfundenes Dokument. */
    public record Doc(String docId, String title, String timestamp, String jobName, String form, List<String> pages) {
    }

    private static final List<Doc> DOCS = List.of(
            new Doc("A1B2C3D4", "Tagesabschluss Ladelauf", "21.11.2026 06:12", "LADEJOB1", "PROD",
                    List.of(page("TAGESABSCHLUSS LADELAUF", 1, "SAETZE GELESEN      12.480", "SAETZE GELADEN      12.473",
                                    "ABGEWIESEN               7"),
                            page("TAGESABSCHLUSS LADELAUF", 2, "ABWEISUNGSGRUENDE", "  PRUEFZIFFER FALSCH     4",
                                    "  DATUM UNGUELTIG        3"))),
            new Doc("B2C3D4E5", "Protokoll Entladelauf", "21.11.2026 06:40", "ENTLADE1", "PROD",
                    List.of(page("PROTOKOLL ENTLADELAUF", 1, "DATEIEN ERZEUGT          3", "SAETZE GESCHRIEBEN  8.112",
                            "ENDE OHNE FEHLER"))),
            new Doc("C3D4E5F6", "Statistik Monatslauf", "20.11.2026 22:05", "STATJOB1", "TEST",
                    List.of(page("STATISTIK MONATSLAUF", 1, "BELEGE NOVEMBER     41.207", "DAVON KORRIGIERT       118"),
                            page("STATISTIK MONATSLAUF", 2, "VERTEILUNG NACH TAGEN", "  01.11.  1.402", "  02.11.  1.388"),
                            page("STATISTIK MONATSLAUF", 3, "ENDE DER STATISTIK"))));

    private final HttpServer server;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger counter = new AtomicInteger();
    private volatile Map<String, String> lastDownload = Map.of();

    private static final class Session {
        boolean loggedIn;
        String csrf;
        final Set<String> strutsTokens = ConcurrentHashMap.newKeySet();
        /** Die Tokens der zuletzt gezeichneten Liste gespeicherter Suchen; jede neue Liste ersetzt sie. */
        final Set<String> listTokens = ConcurrentHashMap.newKeySet();
        /** Meldung für den nächsten Sitzungsstatus. */
        volatile String message;
        List<Doc> lastResult = List.of();
        Map<String, String> lastFilter = Map.of();
        final List<Doc> openDocs = new ArrayList<>();
        Doc current;
        int currentPage = 1;
        final List<String[]> bookmarks = new ArrayList<>();
        final List<String[]> savedSearches = new ArrayList<>();
    }

    public FakeBetaViewServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(CONTEXT, this::handle);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.start();
    }

    /** Basisadresse, etwa {@code http://127.0.0.1:12345/betaview/}. */
    public URI address() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + CONTEXT);
    }

    /** Alle Anfragen bisher als {@code METHODE aktion}, ohne Query. */
    public List<String> requests() {
        return List.copyOf(requests);
    }

    /** Das Formular des letzten Downloads. */
    public Map<String, String> lastDownload() {
        return lastDownload;
    }

    public static List<Doc> documents() {
        return DOCS;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static String page(String title, int number, String... lines) {
        StringBuilder text = new StringBuilder("1" + title + "                              SEITE " + number);
        for (String line : lines) {
            text.append('\n').append(' ').append(line);
        }
        return text.toString();
    }

    // ---------------------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String path = exchange.getRequestURI().getPath();
            String action = path.substring(path.lastIndexOf('/') + 1);
            String method = exchange.getRequestMethod();
            requests.add(method + " " + action);
            Map<String, String> query = parse(exchange.getRequestURI().getRawQuery());
            Map<String, String> form = "POST".equals(method)
                    ? parse(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                    : Map.of();
            Session session = session(exchange);
            switch (action) {
                case "login.action" -> login(exchange, session, method, form);
                case "welcome.action" -> html(exchange, "<html><body><h1>Willkommen bei BetaView</h1></body></html>");
                case "getAppContext.action" -> {
                    if (!session.loggedIn) {
                        redirect(exchange, CONTEXT + "login.action");
                    } else {
                        send(exchange, 200, "application/json", "{\"user\":\"" + USER + "\",\"csrfToken\":{\"field\":"
                                + "\"csrfToken\",\"name\":\"csrfToken\",\"value\":\"" + session.csrf + "\"}}");
                    }
                }
                default -> {
                    if (!session.loggedIn || !csrfOk(session, query, form)) {
                        redirect(exchange, CONTEXT + "login.action");
                        return;
                    }
                    work(exchange, session, action, query, form);
                }
            }
        }
    }

    private void login(HttpExchange exchange, Session session, String method, Map<String, String> form)
            throws IOException {
        if ("GET".equals(method)) {
            html(exchange, loginForm(session));
            return;
        }
        if (!strutsOk(session, form) || !USER.equals(form.get("userName"))
                || !PASSWORD.equals(form.get("password"))) {
            html(exchange, loginForm(session));
            return;
        }
        session.loggedIn = true;
        session.csrf = "csrf-" + UUID.randomUUID();
        redirect(exchange, CONTEXT + "welcome.action");
    }

    private void work(HttpExchange exchange, Session session, String action, Map<String, String> query,
                      Map<String, String> form) throws IOException {
        switch (action) {
            case "select.action" -> {
                if (!FAVORITE.equals(query.get("favoriteID"))) {
                    html(exchange, "<html><body>Favorit unbekannt</body></html>");
                } else {
                    html(exchange, "<html><body><form action=\"result.action\">" + tokenFields(session)
                            + "</form></body></html>");
                }
            }
            case "result.action" -> {
                if (!strutsOk(session, form)) {
                    html(exchange, "<html><body>Ungültiges Token</body></html>");
                    return;
                }
                session.lastFilter = form;
                session.lastResult = filter(form);
                redirect(exchange, CONTEXT + "showResult.action");
            }
            case "showResult.action" -> html(exchange, resultPage(session.lastResult));
            case "executeSavedSelection.action" -> {
                // Wie der echte Server: veraltetes Token oder unvollständiges Formular → HTTP 400.
                if (!strutsOk(session, form) || !"ja".equals(form.get("ssParaMode"))) {
                    send(exchange, 400, "text/html;charset=UTF-8", "<html><body>Bad Request</body></html>");
                    return;
                }
                int id = Integer.parseInt(query.getOrDefault("ssID", "0"));
                String[] saved = session.savedSearches.get(id - 1);
                if (BROKEN_LAYOUT.equals(saved[0])) {
                    // So antwortet der echte Server, wenn das Layout der Suche fehlt (auch im Browser).
                    send(exchange, 400, "application/json; charset=UTF-8", "{ \"errors\":[\"Das Layout existiert "
                            + "nicht (in Ihrer Sprache). Bitte l&ouml;schen Sie die gespeicherte Suche. \"]}");
                    return;
                }
                session.lastResult = filter(parse(saved[2]));
                html(exchange, resultPage(session.lastResult));
            }
            case "open.action" -> {
                int index = Integer.parseInt(query.getOrDefault("index", "0"));
                openDoc(exchange, session, session.lastResult.get(index));
            }
            case "opendocumentlink.action" -> {
                if (OFFLINE_DOC.equals(query.get("docid"))) {
                    // Wie der echte Server: Ablehnung ohne Text, der Grund steht danach im Sitzungsstatus.
                    session.message = "Das Dokument ist offline.";
                    send(exchange, 400, "text/html;charset=UTF-8", "");
                    return;
                }
                openDoc(exchange, session, byId(query.get("docid")));
            }
            case "getSessionStatus.action" -> {
                String message = session.message;
                session.message = null;
                send(exchange, message == null ? 200 : 400, "application/json; charset=UTF-8",
                        "{\"messages\":[" + (message == null ? "" : "{\"key\":\"x\",\"text\":\"" + message
                                + "\",\"type\":\"error\"}") + "],\"successful\":" + (message == null) + "}");
            }
            case "document.viewer.navigation.action" -> {
                session.currentPage = Integer.parseInt(query.getOrDefault("directItem", "1"));
                send(exchange, 200, "application/json", "{\"ok\":true}");
            }
            case "document.page.get.action" -> {
                int index = Integer.parseInt(form.getOrDefault("pageIndex", "0"));
                String text = session.current.pages().get(index);
                StringBuilder json = new StringBuilder("{\"page\":" + (index + 1) + ",\"text\":[");
                String[] lines = text.split("\n");
                for (int i = 0; i < lines.length; i++) {
                    json.append(i == 0 ? "" : ",").append('"').append(lines[i].replace("\"", "\\\"")).append('"');
                }
                send(exchange, 200, "application/json", json.append("]}").toString());
            }
            case "downloadDialog.action" -> html(exchange, "<html><body><form>" + tokenFields(session)
                    + hidden("function", "download") + hidden("pages", String.valueOf(session.current.pages().size()))
                    + hidden("csvEnabled", "true") + hidden("docid", session.current.docId())
                    + hidden("doctype", "txt") + "</form></body></html>");
            case "downloadWithOptions.action" -> {
                if (!strutsOk(session, form)) {
                    html(exchange, "<html><body>Ungültiges Token</body></html>");
                    return;
                }
                lastDownload = Map.copyOf(form);
                List<String> pages = session.current.pages();
                String body = switch (form.getOrDefault("printStyle", "")) {
                    case "printCurrentPage" -> pages.get(session.currentPage - 1);
                    case "printRange" -> String.join("\n", pages.subList(
                            Integer.parseInt(form.get("rangeBegin")) - 1, Integer.parseInt(form.get("rangeEnd"))));
                    default -> String.join("\n", pages);
                };
                send(exchange, 200, "CSV".equals(form.get("downloadFormat")) ? "text/csv" : "text/plain", body);
            }
            case "docbrowser.documentproperties.get.action" -> html(exchange, "<html><body>"
                    + row("Titel", session.current.title()) + row("Erstellt", session.current.timestamp())
                    + row("Jobname", session.current.jobName()) + row("Form", session.current.form())
                    + row("Seiten", String.valueOf(session.current.pages().size())) + "</body></html>");
            case "docbrowser.bookmark.get.action" -> html(exchange, "<html><body><form>" + tokenFields(session)
                    + "<input id=\"we_id_bookmark_name\" name=\"bookmark_name\" value=\"" + session.current.title()
                    + "\"><input id=\"we_id_bookmark_desc\" name=\"bookmark_desc\" value=\""
                    + session.current.timestamp() + "\">" + hidden("indexDocResultList", "-1")
                    + hidden("source", "docbrowser") + "</form></body></html>");
            case "bookmarks.add.action" -> {
                if (!strutsOk(session, form)) {
                    html(exchange, "<html><body>Ungültiges Token</body></html>");
                    return;
                }
                session.bookmarks.add(new String[] {form.get("bookmark_name"), form.get("bookmark_desc"),
                        session.current.docId()});
                html(exchange, "<html><body>ok</body></html>");
            }
            case "bookmarks.list.action" -> {
                StringBuilder html = new StringBuilder("<html><body>");
                for (int i = 0; i < session.bookmarks.size(); i++) {
                    String[] b = session.bookmarks.get(i);
                    html.append("<div class=\"el_wall_btn\"><div name=\"bookmark_name\">").append(b[0])
                            .append("</div><div name=\"bookmark_desc\">").append(b[1])
                            .append("</div><a class=\"we_id_bookmark_link\" href=\"opendocumentlink.action?docid=")
                            .append(b[2]).append("&amp;favid=").append(FAVORITE).append("\">öffnen</a>")
                            .append("<a id=\"we_id_bookmark_edit_").append(i + 1)
                            .append("\" href=\"javascript:bwe.bookmarks.editByID(").append(i + 1)
                            .append(")\">bearbeiten</a></div>");
                }
                html(exchange, html.append("</body></html>").toString());
            }
            case "savedSelection.fetchAddDialog.action" -> html(exchange, "<html><body><form>" + tokenFields(session)
                    + "</form></body></html>");
            case "savedSelection.add.action" -> {
                if (!strutsOk(session, form)) {
                    html(exchange, "<html><body>Ungültiges Token</body></html>");
                    return;
                }
                session.savedSearches.add(new String[] {form.get("selection_name"), form.get("selection_desc"),
                        encode(session.lastFilter)});
                html(exchange, "<html><body>ok</body></html>");
            }
            case "savedSelection.list.action" -> {
                session.strutsTokens.removeAll(session.listTokens);
                session.listTokens.clear();
                StringBuilder html = new StringBuilder("<html><body>");
                for (int i = 0; i < session.savedSearches.size(); i++) {
                    String[] s = session.savedSearches.get(i);
                    html.append("<div class=\"el_wall_btn we-saved-selection\"><div name=\"we_id_selection_name\">")
                            .append(s[0]).append("</div><div name=\"selection_desc\">").append(s[1])
                            .append("</div><form action=\"/betaview/executeSavedSelection.action?ssID=").append(i + 1)
                            .append("\">").append(listTokenFields(session)).append(hidden("ssIDForm",
                                    String.valueOf(i + 1))).append(hidden("ssParaMode", "ja"))
                            .append("<input type=\"submit\" name=\"go\" value=\"Ausführen\"></form></div>");
                }
                html(exchange, html.append("</body></html>").toString());
            }
            case "getDBBookmarksJSON.json.action" -> {
                StringBuilder json = new StringBuilder("{\"dbbookmarks\":[");
                for (int i = 0; i < session.openDocs.size(); i++) {
                    Doc doc = session.openDocs.get(i);
                    json.append(i == 0 ? "" : ",").append("{\"fav\":\"<docbrowsertab>\",\"link\":\"docid=")
                            .append(doc.docId()).append("&favid=").append(FAVORITE).append("\",\"linkID\":\"")
                            .append(linkId(doc)).append("\",\"name\":\"").append(doc.title())
                            .append("\",\"description\":\"").append(doc.timestamp()).append("\"}");
                }
                send(exchange, 200, "application/json", json.append("]}").toString());
            }
            case "closeSingleDocument.action" -> {
                session.openDocs.removeIf(doc -> linkId(doc).equals(query.get("linkID")));
                if (session.current != null && !session.openDocs.contains(session.current)) {
                    session.current = null;
                }
                html(exchange, "<html><body>ok</body></html>");
            }
            case "closeAllDocuments.action" -> {
                session.openDocs.clear();
                session.current = null;
                html(exchange, "<html><body>ok</body></html>");
            }
            case "document.notes.list.action" -> html(exchange, session.current.title().startsWith("Tagesabschluss")
                    ? "<html><body><div class=\"we_note_text\">Bitte bis Freitag prüfen</div></body></html>"
                    : "<html><body><div id=\"we_id_doc_notes_container_content\" data-counttotal=\"0\"></div>"
                    + "</body></html>");
            case "holdhide.list.action" -> send(exchange, 200, "application/json", "{\"holdhide\":[]}");
            case "document.search.action" -> {
                String needle = form.getOrDefault("searchString", "");
                StringBuilder hits = new StringBuilder("<html><body><ul>");
                List<String> pages = session.current.pages();
                for (int i = 0; i < pages.size(); i++) {
                    for (String line : pages.get(i).split("\n")) {
                        if (line.toLowerCase(java.util.Locale.ROOT).contains(needle.toLowerCase(java.util.Locale.ROOT))) {
                            hits.append("<li>Seite ").append(i + 1).append(": ").append(line.trim()).append("</li>");
                        }
                    }
                }
                html(exchange, hits.append("</ul></body></html>").toString());
            }
            case "printDialog.action" -> html(exchange, "<html><body><div class=\"modal-body\">Drucker: Etage 3, "
                    + session.current.pages().size() + " Seiten</div></body></html>");
            default -> send(exchange, 404, "text/plain", "unbekannt: " + action);
        }
    }

    private void openDoc(HttpExchange exchange, Session session, Doc doc) throws IOException {
        if (!session.openDocs.contains(doc)) {
            session.openDocs.add(doc);
        }
        session.current = doc;
        session.currentPage = 1;
        StringBuilder html = new StringBuilder("<html><body><ul id=\"we_id_docbrowser_tabs\">");
        for (Doc open : session.openDocs) {
            html.append("<li").append(open == doc ? " class=\"active\"" : "").append("><div class=\"el_tab_content\">")
                    .append("<a href=\"opendocumentlink.action?docid=").append(open.docId()).append("&amp;favid=")
                    .append(FAVORITE).append("\"><span class=\"el_tab_element\">").append(open.timestamp())
                    .append("</span><span class=\"el_tab_element\">").append(open.title()).append("</span></a>")
                    .append("<button class=\"el_tab_close\" id=\"we_id_close_btn_").append(linkId(open))
                    .append("\" onclick=\"bwe.ajax('closeSingleDocument.action?linkID=").append(linkId(open))
                    .append("')\">x</button></div></li>");
        }
        html.append("</ul><input type=\"hidden\" id=\"we-documentid\" value=\"").append(doc.docId()).append("\">")
                .append("<input type=\"hidden\" id=\"numberPages\" value=\"").append(doc.pages().size()).append("\">")
                .append("<div id=\"we_id_docprops_headline\">").append(doc.title()).append("</div>")
                .append("<div class=\"el_panel_info\"><div class=\"row\"><span class=\"el_label_desc\">Jobname</span>")
                .append("<span class=\"el_label_value\">").append(doc.jobName()).append("</span></div>")
                .append("<div class=\"row\"><span class=\"el_label_desc\">Erstellt</span><span class=\"el_label_value\">")
                .append(doc.timestamp()).append("</span></div></div>")
                .append("<form id=\"we_id_doc_navigation_form\">").append(tokenFields(session))
                .append("</form></body></html>");
        html(exchange, html.toString());
    }

    private static String linkId(Doc doc) {
        return "L" + doc.docId();
    }

    private static Doc byId(String docId) {
        return DOCS.stream().filter(doc -> doc.docId().equals(docId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unbekanntes Dokument " + docId));
    }

    private static List<Doc> filter(Map<String, String> form) {
        String job = form.getOrDefault("JOBNAME", "*");
        String formValue = form.getOrDefault("FORM", "*");
        return DOCS.stream()
                .filter(doc -> "*".equals(job) || doc.jobName().startsWith(job.replace("*", "")))
                .filter(doc -> "*".equals(formValue) || doc.form().equals(formValue))
                .toList();
    }

    private static String resultPage(List<Doc> docs) {
        StringBuilder html = new StringBuilder("<html><body><table id=\"resulttable\"><thead><tr><th>Erstellt</th>"
                + "<th>Titel</th><th>Jobname</th><th>Form</th><th>Seiten</th></tr></thead><tbody>");
        for (int i = 0; i < docs.size(); i++) {
            Doc doc = docs.get(i);
            html.append("<tr><td><a href=\"#\" onclick=\"javascript:bwe.result.openDocument('open.action?index=")
                    .append(i).append("&amp;source=resulttable');\">").append(doc.timestamp()).append("</a></td><td>")
                    .append(doc.title()).append("</td><td>").append(doc.jobName()).append("</td><td>")
                    .append(doc.form()).append("</td><td>").append(doc.pages().size()).append("</td></tr>");
        }
        return html.append("</tbody></table></body></html>").toString();
    }

    private String loginForm(Session session) {
        return "<html><body><form action=\"login.action\" method=\"post\">" + tokenFields(session)
                + hidden("newpasswordmode", "false") + hidden("favoriteID", "")
                + "<input name=\"userName\"><input type=\"password\" name=\"password\"></form></body></html>";
    }

    private String tokenFields(Session session) {
        String token = "T" + counter.incrementAndGet();
        session.strutsTokens.add(token);
        return hidden("struts.token.name", "token") + hidden("token", token);
    }

    private String listTokenFields(Session session) {
        String fields = tokenFields(session);
        session.listTokens.add("T" + counter.get());
        return fields;
    }

    /** Kennung eines gemerkten Dokuments, das auf dem Server offline ist. */
    public static final String OFFLINE_DOC = "OFFLINE1";

    /** Name einer gespeicherten Suche, deren Layout auf dem Server fehlt. */
    public static final String BROKEN_LAYOUT = "Altes Layout";

    /** Ein Struts-Token gilt genau einmal. */
    private static boolean strutsOk(Session session, Map<String, String> form) {
        String name = form.get("struts.token.name");
        return name != null && session.strutsTokens.remove(form.getOrDefault(name, ""));
    }

    private static boolean csrfOk(Session session, Map<String, String> query, Map<String, String> form) {
        if (session.csrf.equals(query.get("csrfToken")) || session.csrf.equals(form.get("csrfToken"))) {
            return true;
        }
        // Formulare mit Struts-Token schicken kein CSRF-Token mit (wie im Browser).
        return form.containsKey("struts.token.name");
    }

    private static String hidden(String name, String value) {
        return "<input type=\"hidden\" name=\"" + name + "\" value=\"" + value + "\">";
    }

    private static String row(String label, String value) {
        return "<div class=\"row\"><span class=\"el_label_desc\">" + label + "</span><span class=\"el_label_value\">"
                + value + "</span></div>";
    }

    private Session session(HttpExchange exchange) {
        String cookie = exchange.getRequestHeaders().getFirst("Cookie");
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                String[] pair = part.trim().split("=", 2);
                if (pair.length == 2 && pair[0].equals("JSESSIONID") && sessions.containsKey(pair[1])) {
                    return sessions.get(pair[1]);
                }
            }
        }
        String id = UUID.randomUUID().toString();
        Session created = new Session();
        sessions.put(id, created);
        exchange.getResponseHeaders().add("Set-Cookie", "JSESSIONID=" + id + "; Path=/betaview; HttpOnly");
        return created;
    }

    private static Map<String, String> parse(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            if (pair.isEmpty()) {
                continue;
            }
            String[] parts = pair.split("=", 2);
            result.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8),
                    parts.length > 1 ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8) : "");
        }
        return result;
    }

    private static String encode(Map<String, String> form) {
        StringBuilder out = new StringBuilder();
        form.forEach((key, value) -> out.append(out.length() == 0 ? "" : "&")
                .append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)).append('=')
                .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8)));
        return out.toString();
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().add("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    private static void html(HttpExchange exchange, String html) throws IOException {
        send(exchange, 200, "text/html;charset=UTF-8", html);
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        }
    }
}
