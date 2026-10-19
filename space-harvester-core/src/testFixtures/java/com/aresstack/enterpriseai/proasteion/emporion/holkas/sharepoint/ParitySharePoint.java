package com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Feste Vergleichsdaten für die SharePoint-Quelle (#193): eine kleine SharePoint-Site im Prozess, nur auf
 * {@code 127.0.0.1}, über den eingebauten HTTP-Server des JDK, ohne Anmeldung. Sie beantwortet die REST-Aufrufe der
 * Quelle ({@code _api/web/lists}, {@code GetFolderByServerRelativeUrl(…)/Files|Folders},
 * {@code GetFileByServerRelativeUrl(…)} und {@code …/$value}) im Format {@code odata=verbose}, aufgebaut wie die
 * Fake-Site in {@code SharePointKnowledgeSourceTest} des Enterprise AI Client.
 *
 * <ul>
 *   <li>Site {@link #SITE_PATH}; sichtbare Bibliothek {@link #LIBRARY} mit Unterordnern bis {@link #DEPTH} Ebenen,
 *       dazu der Ordner {@code Forms}, den die Quelle überspringt, und ein Bild ohne Text ({@link #IMAGE}).</li>
 *   <li>Versteckte Bibliothek {@code Intern} mit {@link #HIDDEN}, die die Quelle nicht liest.</li>
 *   <li>Je Textdatei ein Satz aus {@link #SENTENCES}; {@link #SEARCH} trifft nur {@link #SEARCH_HIT}.</li>
 *   <li>Alle Dateien tragen Zeit {@code 2026-10-01T08:00:00Z} und Version {@code 3.0}.</li>
 * </ul>
 *
 * <p>Java 8, ohne weitere Bibliotheken.
 */
public final class ParitySharePoint implements AutoCloseable {

    public static final String SITE_PATH = "/sites/parity";
    public static final String LIBRARY = SITE_PATH + "/Dokumente";
    public static final String[] DOCUMENTS = {LIBRARY + "/Lies mich.txt", LIBRARY + "/Betrieb/Handbuch.txt",
            LIBRARY + "/Betrieb/Nacht/Ablauf.txt", LIBRARY + "/Notizen.md"};
    /** Tiefe ({@code maxDepth}), bei der alle {@link #DOCUMENTS} erreicht werden. */
    public static final int DEPTH = 2;
    public static final String[] SENTENCES = {"Satz Alpha in der Bibliothek.", "Satz Bravo im Handbuch.",
            "Satz Charlie zum Nachtlauf im Ablauf.", "Satz Delta in den Notizen."};
    public static final String IMAGE = LIBRARY + "/bild.png";
    public static final String FORMS = LIBRARY + "/Forms/Vorlage.txt";
    public static final String HIDDEN = SITE_PATH + "/Intern/Geheim.txt";
    public static final String HIDDEN_SENTENCE = "Satz Echo in der versteckten Bibliothek.";
    public static final String SEARCH = "Nachtlauf";
    public static final String SEARCH_HIT = LIBRARY + "/Betrieb/Nacht/Ablauf.txt";
    /** Inhalt je serverrelativem Pfad, alle Dateien der Site. */
    public static final Map<String, byte[]> FILES;

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String TIME = "2026-10-01T08:00:00Z";
    private static final Pattern FOLDER = Pattern.compile("GetFolderByServerRelativeUrl\\('(.*)'\\)/(Files|Folders)");
    private static final Pattern FILE = Pattern.compile("GetFileByServerRelativeUrl\\('(.*)'\\)(/\\$value)?");
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4"
            + "nGNgYGD4DwABBAEAwS2OUAAAAABJRU5ErkJggg==";

    static {
        Map<String, byte[]> files = new LinkedHashMap<String, byte[]>();
        for (int i = 0; i < DOCUMENTS.length; i++) {
            files.put(DOCUMENTS[i], (SENTENCES[i] + "\n").getBytes(UTF_8));
        }
        files.put(IMAGE, Base64.getDecoder().decode(PNG));
        files.put(FORMS, "Satz Foxtrot in der Vorlage.\n".getBytes(UTF_8));
        files.put(HIDDEN, (HIDDEN_SENTENCE + "\n").getBytes(UTF_8));
        FILES = Collections.unmodifiableMap(files);
    }

    private final HttpServer server;

    private ParitySharePoint(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext(SITE_PATH + "/_api/web/", this::handle);
        server.start();
    }

    /** Startet die Site auf einem freien Port von {@code 127.0.0.1}. */
    public static ParitySharePoint start() throws IOException {
        return start(0);
    }

    /** Startet die Site auf {@code port} von {@code 127.0.0.1}; {@code 0} wählt einen freien Port. */
    public static ParitySharePoint start(int port) throws IOException {
        return new ParitySharePoint(port);
    }

    /** Adresse der Site für {@code options.siteUrl}, z. B. {@code http://127.0.0.1:4711/sites/parity}. */
    public String siteUrl() {
        return "http://127.0.0.1:" + port() + SITE_PATH;
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String call = exchange.getRequestURI().getPath().substring(SITE_PATH.length() + "/_api/web/".length());
            if (!"GET".equals(exchange.getRequestMethod())) {
                send(exchange, 405, "text/plain", new byte[0]);
            } else if ("lists".equals(call)) {
                json(exchange, "{\"d\":{\"results\":["
                        + "{\"Title\":\"Dokumente\",\"Hidden\":false,\"BaseTemplate\":101,"
                        + "\"RootFolder\":{\"ServerRelativeUrl\":\"" + LIBRARY + "\"}},"
                        + "{\"Title\":\"Intern\",\"Hidden\":true,\"BaseTemplate\":101,"
                        + "\"RootFolder\":{\"ServerRelativeUrl\":\"" + SITE_PATH + "/Intern\"}}]}}");
            } else {
                Matcher folder = FOLDER.matcher(call);
                Matcher file = FILE.matcher(call);
                if (folder.matches()) {
                    folder(exchange, unquote(folder.group(1)), "Files".equals(folder.group(2)));
                } else if (file.matches()) {
                    file(exchange, unquote(file.group(1)), file.group(2) != null);
                } else {
                    send(exchange, 404, "application/json", new byte[0]);
                }
            }
        } finally {
            exchange.close();
        }
    }

    private static void folder(HttpExchange exchange, String path, boolean files) throws IOException {
        String prefix = path + "/";
        TreeSet<String> names = new TreeSet<String>();
        boolean exists = false;
        for (String file : FILES.keySet()) {
            if (!file.startsWith(prefix)) {
                continue;
            }
            exists = true;
            String rest = file.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (files == (slash < 0)) {
                names.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        if (!exists) {
            send(exchange, 404, "application/json", new byte[0]);
            return;
        }
        StringBuilder results = new StringBuilder();
        for (String name : names) {
            if (results.length() > 0) {
                results.append(',');
            }
            results.append(files ? metadata(prefix + name)
                    : "{\"Name\":\"" + name + "\",\"ServerRelativeUrl\":\"" + prefix + name + "\"}");
        }
        json(exchange, "{\"d\":{\"results\":[" + results + "]}}");
    }

    private static void file(HttpExchange exchange, String path, boolean content) throws IOException {
        byte[] bytes = FILES.get(path);
        if (bytes == null) {
            send(exchange, 404, "application/json", new byte[0]);
        } else if (content) {
            send(exchange, 200, "application/octet-stream", bytes);
        } else {
            json(exchange, "{\"d\":" + metadata(path) + "}");
        }
    }

    private static String metadata(String path) {
        return "{\"Name\":\"" + path.substring(path.lastIndexOf('/') + 1) + "\",\"ServerRelativeUrl\":\"" + path
                + "\",\"TimeLastModified\":\"" + TIME + "\",\"Length\":\"" + FILES.get(path).length
                + "\",\"UIVersionLabel\":\"3.0\"}";
    }

    private static String unquote(String odata) {
        return odata.replace("''", "'");
    }

    private static void json(HttpExchange exchange, String body) throws IOException {
        send(exchange, 200, "application/json;odata=verbose;charset=utf-8", body.getBytes(UTF_8));
    }

    private static void send(HttpExchange exchange, int status, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
        if (body.length > 0) {
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        }
    }
}
