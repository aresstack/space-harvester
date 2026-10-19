package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * Feste Vergleichsdaten für die FTP-Quelle (#193): ein kleiner FTP-Server im Prozess, nur auf {@code 127.0.0.1},
 * anonym, mit Unix-Auflistung und unveränderlichem Inhalt. Damit laufen der Enterprise AI Client und Space Harvester
 * gegen dieselben Dateien, ohne Netzdienst und ohne Zugangsdaten.
 *
 * <ul>
 *   <li>Wurzel {@link #START_POINT}; Unterordner bis {@link #DEPTH} Ebenen tief.</li>
 *   <li>Je Textdatei ein Satz aus {@link #SENTENCES}; {@link #SEARCH} trifft nur {@link #SEARCH_HIT}.</li>
 *   <li>Alle Dateien tragen dieselbe Zeit {@code 1. Okt. 2026}; die Antworten sind in jedem Lauf gleich.</li>
 * </ul>
 *
 * <p>Für die JES-Quelle schaltet {@code SITE FILETYPE=JES} die Sitzung auf Jobs um: {@code LIST} und {@code NLST}
 * liefern die Jobs aus {@link #JOB_IDS} zu {@code JESOWNER} und {@code JESJOBNAME} (mit {@code *}) in der Form von
 * {@code JESINTERFACELEVEL=2}, {@code RETR <Job-ID>.x} die Ausgabe mit Jobkarte und Satz aus {@link #JOB_SENTENCES}.
 *
 * <p>Der Server versteht nur, was die FTP- und die JES-Quelle brauchen: {@code USER}, {@code PASS}, {@code SYST},
 * {@code PASV}, {@code TYPE}, {@code STRU}, {@code MODE}, {@code LIST}, {@code NLST}, {@code RETR}, {@code PWD},
 * {@code CWD}, {@code SITE}, {@code NOOP}, {@code QUIT}. Java 8, ohne weitere Bibliotheken.
 */
public final class ParityFtp implements AutoCloseable {

    /** Startpunkt der Quelle ({@code startPoints}). */
    public static final String START_POINT = "/parity";
    public static final String[] DOCUMENTS = {"/parity/README.txt", "/parity/docs/architektur.txt",
            "/parity/docs/betrieb/handbuch.txt", "/parity/docs/betrieb/nacht/ablauf.txt", "/parity/notizen.txt"};
    /** Tiefe ({@code maxDepth}), bei der alle {@link #DOCUMENTS} erreicht werden. */
    public static final int DEPTH = 3;
    public static final String[] SENTENCES = {"Satz Alpha im Wurzelordner.", "Satz Bravo zur Architektur.",
            "Satz Charlie im Handbuch.", "Satz Delta zum Nachtlauf im Ablauf.", "Satz Echo in den Notizen."};
    public static final String SEARCH = "Nachtlauf";
    public static final String SEARCH_HIT = "/parity/docs/betrieb/nacht/ablauf.txt";
    /** Inhalt je Pfad, in der Reihenfolge von {@link #DOCUMENTS}. */
    public static final Map<String, String> FILES;

    /** Besitzer aller Jobs; die JES-Quelle filtert ohne {@code owner} nach dem angemeldeten Benutzer. */
    public static final String JOB_OWNER = "PARITAET";
    public static final String[] JOB_IDS = {"JOB00001", "JOB00002", "JOB00003"};
    public static final String[] JOB_NAMES = {"ZAUF01", "ZAUF02", "NACHT03"};
    public static final String[] JOB_SENTENCES = {"Satz Alpha im ersten Job.", "Satz Bravo im zweiten Job.",
            "Satz Charlie zum Nachtlauf im dritten Job."};
    public static final String JOB_SEARCH_HIT = "JOB00003";

    private static final Charset ENCODING = Charset.forName("ISO-8859-1");
    private static final String TIME = "Oct 01  2026";

    static {
        Map<String, String> files = new LinkedHashMap<String, String>();
        for (int i = 0; i < DOCUMENTS.length; i++) {
            files.put(DOCUMENTS[i], SENTENCES[i] + "\n");
        }
        FILES = Collections.unmodifiableMap(files);
    }

    private final ServerSocket control;
    private final Thread acceptor;

    private ParityFtp(int port) throws IOException {
        control = new ServerSocket(port, 8, InetAddress.getLoopbackAddress());
        acceptor = new Thread(this::accept, "parity-ftp");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    /** Startet den Server auf einem freien Port von {@code 127.0.0.1}. */
    public static ParityFtp start() throws IOException {
        return start(0);
    }

    /** Startet den Server auf {@code port} von {@code 127.0.0.1}; {@code 0} wählt einen freien Port. */
    public static ParityFtp start(int port) throws IOException {
        return new ParityFtp(port);
    }

    /** {@code 127.0.0.1} für {@code options.host}. */
    public String host() {
        return "127.0.0.1";
    }

    public int port() {
        return control.getLocalPort();
    }

    @Override
    public void close() throws IOException {
        control.close();
    }

    private void accept() {
        while (!control.isClosed()) {
            try {
                final Socket client = control.accept();
                Thread session = new Thread(() -> serve(client), "parity-ftp-session");
                session.setDaemon(true);
                session.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket client) {
        ServerSocket passive = null;
        String cwd = "/";
        boolean jes = false;
        String jobOwner = "*";
        String jobName = "*";
        try (Socket socket = client;
             BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), ENCODING));
             Writer out = new OutputStreamWriter(socket.getOutputStream(), ENCODING)) {
            reply(out, "220 Paritaet");
            String line;
            while ((line = in.readLine()) != null) {
                int space = line.indexOf(' ');
                String command = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
                String argument = space < 0 ? "" : line.substring(space + 1).trim();
                if ("USER".equals(command)) {
                    reply(out, "331 Passwort bitte");
                } else if ("PASS".equals(command)) {
                    reply(out, "230 Angemeldet");
                } else if ("SYST".equals(command)) {
                    reply(out, "215 UNIX Type: L8");
                } else if ("TYPE".equals(command) || "STRU".equals(command) || "MODE".equals(command)
                        || "NOOP".equals(command)) {
                    reply(out, "200 OK");
                } else if ("PWD".equals(command)) {
                    reply(out, "257 \"" + cwd + "\"");
                } else if ("CWD".equals(command)) {
                    String target = absolute(cwd, argument);
                    if (isDirectory(target)) {
                        cwd = target;
                        reply(out, "250 OK");
                    } else {
                        reply(out, "550 Kein Ordner");
                    }
                } else if ("PASV".equals(command)) {
                    if (passive != null) {
                        passive.close();
                    }
                    passive = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
                    int p = passive.getLocalPort();
                    reply(out, "227 Entering Passive Mode (127,0,0,1," + (p >> 8) + "," + (p & 0xff) + ")");
                } else if ("SITE".equals(command)) {
                    String setting = argument.toUpperCase(Locale.ROOT);
                    if (setting.startsWith("FILETYPE=")) {
                        jes = "FILETYPE=JES".equals(setting);
                    } else if (setting.startsWith("JESOWNER=")) {
                        jobOwner = setting.substring("JESOWNER=".length());
                    } else if (setting.startsWith("JESJOBNAME=")) {
                        jobName = setting.substring("JESJOBNAME=".length());
                    }
                    reply(out, "200 SITE OK");
                } else if (jes && ("LIST".equals(command) || "NLST".equals(command))) {
                    StringBuilder body = new StringBuilder();
                    if ("LIST".equals(command)) {
                        body.append("JOBNAME  JOBID    OWNER    STATUS CLASS\r\n");
                    }
                    for (int i = 0; i < JOB_IDS.length; i++) {
                        if (matches(jobOwner, JOB_OWNER) && matches(jobName, JOB_NAMES[i])) {
                            body.append(String.format("%-8s %s %-8s OUTPUT A        RC=0000 1 spool files\r\n",
                                    JOB_NAMES[i], JOB_IDS[i], JOB_OWNER));
                        }
                    }
                    transfer(out, passive, body.toString().getBytes(ENCODING));
                    passive = null;
                } else if (jes && "RETR".equals(command)) {
                    int job = job(argument);
                    if (job < 0) {
                        reply(out, "550 Kein Job");
                        continue;
                    }
                    String output = "//" + JOB_NAMES[job] + " JOB (ACCT),'PARITAET',CLASS=A\r\n"
                            + "//STEP1    EXEC PGM=IEFBR14\r\n" + JOB_SENTENCES[job] + "\r\n";
                    transfer(out, passive, output.getBytes(ENCODING));
                    passive = null;
                } else if ("LIST".equals(command) || "NLST".equals(command)) {
                    String target = absolute(cwd, stripOptions(argument));
                    List<String> lines = "LIST".equals(command) ? list(target) : names(target);
                    if (lines == null) {
                        reply(out, "550 Nicht gefunden");
                        continue;
                    }
                    StringBuilder body = new StringBuilder();
                    for (String entry : lines) {
                        body.append(entry).append("\r\n");
                    }
                    transfer(out, passive, body.toString().getBytes(ENCODING));
                    passive = null;
                } else if ("RETR".equals(command)) {
                    String content = FILES.get(absolute(cwd, argument));
                    if (content == null) {
                        reply(out, "550 Nicht gefunden");
                        continue;
                    }
                    transfer(out, passive, content.replace("\n", "\r\n").getBytes(ENCODING));
                    passive = null;
                } else if ("QUIT".equals(command)) {
                    reply(out, "221 Tschuess");
                    return;
                } else {
                    reply(out, "502 Nicht unterstuetzt");
                }
            }
        } catch (IOException e) {
            // Verbindung beendet.
        } finally {
            if (passive != null) {
                try {
                    passive.close();
                } catch (IOException ignored) {
                    // nichts zu tun
                }
            }
        }
    }

    private static void transfer(Writer out, ServerSocket passive, byte[] data) throws IOException {
        if (passive == null) {
            reply(out, "425 Erst PASV");
            return;
        }
        reply(out, "150 Daten folgen");
        try (ServerSocket server = passive; Socket socket = server.accept()) {
            OutputStream stream = socket.getOutputStream();
            stream.write(data);
            stream.flush();
        }
        reply(out, "226 Fertig");
    }

    private static void reply(Writer out, String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    /** {@code *} passt auf alles, sonst Vergleich ohne Groß- und Kleinschreibung mit {@code *} als Platzhalter. */
    private static boolean matches(String filter, String value) {
        String pattern = "\\Q" + filter.toUpperCase(Locale.ROOT).replace("*", "\\E.*\\Q") + "\\E";
        return value.toUpperCase(Locale.ROOT).matches(pattern);
    }

    /** Index des Jobs zu {@code <Job-ID>.x}, sonst {@code -1}. */
    private static int job(String file) {
        for (int i = 0; i < JOB_IDS.length; i++) {
            if ((JOB_IDS[i] + ".x").equalsIgnoreCase(file)) {
                return i;
            }
        }
        return -1;
    }

    private static String stripOptions(String argument) {
        if (!argument.startsWith("-")) {
            return argument;
        }
        int space = argument.indexOf(' ');
        return space < 0 ? "" : argument.substring(space + 1);
    }

    private static String absolute(String cwd, String path) {
        String joined = path.isEmpty() ? cwd
                : path.startsWith("/") ? path : (cwd.endsWith("/") ? cwd : cwd + "/") + path;
        while (joined.length() > 1 && joined.endsWith("/")) {
            joined = joined.substring(0, joined.length() - 1);
        }
        return joined;
    }

    private static boolean isDirectory(String path) {
        if ("/".equals(path)) {
            return true;
        }
        for (String file : FILES.keySet()) {
            if (file.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Unix-Zeilen der Kinder; eine Datei listet sich selbst; {@code null}, wenn es den Pfad nicht gibt. */
    private static List<String> list(String path) {
        if (FILES.containsKey(path)) {
            return Collections.singletonList(fileLine(name(path), FILES.get(path)));
        }
        if (!isDirectory(path)) {
            return null;
        }
        List<String> lines = new ArrayList<String>();
        for (String child : children(path)) {
            String full = ("/".equals(path) ? "" : path) + "/" + child;
            lines.add(FILES.containsKey(full) ? fileLine(child, FILES.get(full))
                    : "drwxr-xr-x   2 paritaet paritaet     4096 " + TIME + " " + child);
        }
        return lines;
    }

    private static List<String> names(String path) {
        if (FILES.containsKey(path)) {
            return Collections.singletonList(path);
        }
        if (!isDirectory(path)) {
            return null;
        }
        return new ArrayList<String>(children(path));
    }

    private static TreeSet<String> children(String path) {
        String prefix = "/".equals(path) ? "/" : path + "/";
        TreeSet<String> children = new TreeSet<String>();
        for (String file : FILES.keySet()) {
            if (file.startsWith(prefix)) {
                String rest = file.substring(prefix.length());
                int slash = rest.indexOf('/');
                children.add(slash < 0 ? rest : rest.substring(0, slash));
            }
        }
        return children;
    }

    private static String fileLine(String name, String content) {
        int size = content.replace("\n", "\r\n").getBytes(ENCODING).length;
        return String.format("-rw-r--r--   1 paritaet paritaet %8d %s %s", size, TIME, name);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }
}
