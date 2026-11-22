package com.aresstack.spaceharvester.deck.ndv;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Ein NDV-Server im Speicher für Tests und Bildschirmfotos: Bibliotheken mit Natural-Objekten und Quelltexten,
 * ein Benutzer mit Passwort, Seiten wie beim echten Server. Er kann die Verbindung abreißen lassen
 * ({@link #dropConnections()}), um das Wiederverbinden zu prüfen. Alle Inhalte sind erfunden.
 */
public final class FakeNdvServer implements NdvConnector {

    public static final NdvObjectType PROGRAM = new NdvObjectType(1, "Program", "NSP");
    public static final NdvObjectType SUBPROGRAM = new NdvObjectType(4, "Subprogram", "NSN");
    public static final NdvObjectType MAP = new NdvObjectType(2, "Map", "NSM");
    public static final NdvObjectType LDA = new NdvObjectType(32, "Local Data Area", "NSL");
    public static final NdvObjectType COPYCODE = new NdvObjectType(16, "Copycode", "NSC");
    public static final NdvObjectType DDM = new NdvObjectType(8, "DDM", "NSD");

    private final String user;
    private final String password;
    private final Map<String, List<NdvObject>> libraries = new LinkedHashMap<>();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final List<Connection> connections = new CopyOnWriteArrayList<>();
    private final List<String> writes = new CopyOnWriteArrayList<>();
    private final AtomicInteger connects = new AtomicInteger();
    private volatile int pageSize = 20;
    private volatile String refusal;
    private volatile char[] lastPassword = new char[0];

    public FakeNdvServer(String user, String password) {
        this.user = user;
        this.password = password;
    }

    /** Ein kleiner erfundener Bestand: zwei Bibliotheken mit Programmen, Subprogrammen, Maps, Datenbereichen. */
    public static FakeNdvServer sample() {
        FakeNdvServer server = new FakeNdvServer("PILOT", "geheim");
        server.add("LAGER", "LAGER001", PROGRAM, """
                * Lagerbestand anzeigen (erfundenes Beispiel)
                DEFINE DATA LOCAL USING LAGERL01
                END-DEFINE
                READ ARTIKEL BY NUMMER
                  DISPLAY NUMMER BEZEICHNUNG BESTAND
                END-READ
                END""");
        server.add("LAGER", "LAGER002", PROGRAM, "* Nachbestellung\nCALLNAT 'LAGERN01' #ARTIKEL\nEND");
        server.add("LAGER", "LAGERN01", SUBPROGRAM, "DEFINE DATA PARAMETER\n1 #ARTIKEL (A10)\nEND-DEFINE\nEND");
        server.add("LAGER", "LAGERM01", MAP, "* Eingabemaske Artikel");
        server.add("LAGER", "LAGERL01", LDA, "1 #NUMMER (N8)\n1 #BESTAND (P7)");
        server.add("LAGER", "LAGERC01", COPYCODE, "* Gemeinsame Prüfungen");
        server.add("LAGER", "ARTIKEL", DDM, "DB: 10 FILE: 120 - ARTIKEL");
        server.add("KASSE", "KASSE001", PROGRAM, "* Tagesabschluss\nEND");
        server.add("KASSE", "KASSEN01", SUBPROGRAM, "* Buchung\nEND");
        server.add("SYSTEM", "MENU", PROGRAM, "* Hauptmenü\nEND");
        return server;
    }

    /** Legt ein Objekt mit Quelltext an. */
    public FakeNdvServer add(String library, String name, NdvObjectType type, String source) {
        String lib = library.toUpperCase(Locale.ROOT);
        boolean ddm = type.equals(DDM);
        NdvObject object = new NdvObject(lib, ddm ? "" : name, type, true, source.length(), "PILOT", "2026-11-20 09:15",
                new NdvObject.Location(10, ddm ? 3 : 120, ddm ? name : "", 1, ddm));
        libraries.computeIfAbsent(lib, key -> new ArrayList<>()).add(object);
        sources.put(key(object), source);
        return this;
    }

    public FakeNdvServer pageSize(int size) {
        pageSize = size;
        return this;
    }

    /** Lässt jeden Verbindungsaufbau mit diesem Grund scheitern; {@code null} hebt das auf. */
    public void refuse(String reason) {
        refusal = reason;
    }

    /** Lässt alle offenen Verbindungen abreißen, wie ein Netzausfall. */
    public void dropConnections() {
        connections.forEach(connection -> connection.open = false);
    }

    /** Wie oft verbunden wurde. */
    public int connects() {
        return connects.get();
    }

    /** Offene Verbindungen. */
    public long openConnections() {
        return connections.stream().filter(connection -> connection.open).count();
    }

    /** Gespeicherte Objekte in der Reihenfolge des Speicherns ({@code LIB/NAME}). */
    public List<String> writes() {
        return List.copyOf(writes);
    }

    /** Das zuletzt übergebene Passwort, wie es nach dem Verbinden im Speicher des Aufrufers steht. */
    public char[] lastPassword() {
        return lastPassword;
    }

    public String source(String library, String name) {
        return sources.get(library.toUpperCase(Locale.ROOT) + "/" + name);
    }

    @Override
    public NdvConnection connect(NdvProfile profile, String name, char[] secret) throws IOException {
        connects.incrementAndGet();
        lastPassword = secret;
        if (refusal != null) {
            throw new IOException(refusal);
        }
        if (!user.equalsIgnoreCase(name) || !password.equals(new String(secret))) {
            throw new IOException("NDV-Login fehlgeschlagen: NAT0838 Benutzer oder Passwort falsch");
        }
        Connection connection = new Connection();
        connections.add(connection);
        return connection;
    }

    private static String key(NdvObject object) {
        return object.library() + "/" + object.displayName();
    }

    private static Pattern glob(String filter) {
        String text = filter == null || filter.isBlank() ? "*" : filter;
        return Pattern.compile(Pattern.quote(text).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE);
    }

    private final class Connection implements NdvConnection {

        private volatile boolean open = true;

        private void check() throws IOException {
            if (!open) {
                throw new IOException("Connection reset");
            }
        }

        @Override
        public List<String> libraries(String filter) throws IOException {
            check();
            Pattern pattern = glob(filter);
            return libraries.keySet().stream().filter(name -> pattern.matcher(name).matches()).toList();
        }

        @Override
        public int objects(String library, String filter, Page page) throws IOException {
            check();
            Pattern pattern = glob(filter);
            List<NdvObject> all = libraries.getOrDefault(library, List.of()).stream()
                    .filter(object -> pattern.matcher(object.displayName()).matches()).toList();
            int total = 0;
            for (int from = 0; from < all.size(); from += pageSize) {
                check();
                List<NdvObject> slice = all.subList(from, Math.min(all.size(), from + pageSize));
                total += slice.size();
                if (!page.accept(slice, total)) {
                    return total;
                }
            }
            return total;
        }

        @Override
        public String read(NdvObject object) throws IOException {
            check();
            String source = sources.get(key(object));
            if (source == null) {
                throw new IOException("NAT0082 Objekt " + object.displayName() + " nicht gefunden");
            }
            return source;
        }

        @Override
        public void write(NdvObject object, String source) throws IOException {
            check();
            sources.put(key(object), source);
            writes.add(key(object));
        }

        @Override
        public boolean open() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }

    @Override
    public String toString() {
        return "FakeNdvServer" + libraries.keySet();
    }
}
