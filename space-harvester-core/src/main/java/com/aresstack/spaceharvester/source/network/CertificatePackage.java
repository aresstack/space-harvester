package com.aresstack.spaceharvester.source.network;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Das Zertifikatspaket einer Quelle (Strang ZX): Was ein anderer Rechner braucht, um derselben Quelle zu vertrauen.
 * Ordnet die vom Server gesendete Kette und die Kette des Client-Zertifikats gegen die Vertrauensanker der Quelle ein
 * und schreibt ein ZIP mit Stamm- und Zwischenzertifizierungsstellen, {@code kette.pem}, {@code importieren.cmd} und
 * {@code LIESMICH.txt}.
 *
 * <p>Nur Vertrautes wird importierbar: In die Ordner für Windows-Speicher, in {@code kette.pem} und in das Skript
 * kommen nur Zertifikate, die selbst Anker der Quelle sind oder sich bis zu einem Anker prüfen lassen. Eine Kette, die
 * an keinem Anker endet (etwa eine abgefangene Verbindung), liegt nur zur Ansicht im Paket und nie im Skript. Das
 * Paket enthält nur öffentliche Zertifikate, nie private Schlüssel.
 */
public final class CertificatePackage {

    static final String ROOTS = "1-Stammzertifizierungsstellen/";
    static final String INTERMEDIATES = "2-Zwischenzertifizierungsstellen/";
    static final String SERVER = "3-Server/";
    static final String CLIENT = "4-Client/";

    private static final Pattern COMMON_NAME = Pattern.compile("(?:^|,)\\s*CN=((?:\\\\,|[^,])+)");
    private static final Pattern ILLEGAL = Pattern.compile("[\\\\/:*?\"<>|\\x00-\\x1f]");
    private static final Set<String> RESERVED = Set.of("CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4",
            "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8",
            "LPT9");
    private static final int MAX_NAME = 60;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM.yyyy", Locale.GERMANY);

    /** Rolle eines Zertifikats im Paket. */
    public enum Role { ROOT, INTERMEDIATE, SERVER, CLIENT, UNTRUSTED }

    /** Ein Zertifikat mit seiner Rolle, seiner Herkunft und dem Namen im ZIP. */
    public record Entry(X509Certificate certificate, Role role, boolean sentByServer, boolean inTrustStore,
                        String path) {
    }

    private final String title;
    private final String host;
    private final ZonedDateTime created;
    private final List<String> problems;
    private final List<String> notes;
    private final boolean serverTrusted;
    private final boolean clientTrusted;
    private final boolean serverChainKnown;
    private final List<Entry> entries;

    private CertificatePackage(String title, String host, ZonedDateTime created, List<String> problems,
                               List<String> notes, boolean serverTrusted, boolean clientTrusted,
                               boolean serverChainKnown, List<Entry> entries) {
        this.title = title;
        this.host = host;
        this.created = created;
        this.problems = List.copyOf(problems);
        this.notes = List.copyOf(notes);
        this.serverTrusted = serverTrusted;
        this.clientTrusted = clientTrusted;
        this.serverChainKnown = serverChainKnown;
        this.entries = List.copyOf(entries);
    }

    /**
     * Ordnet ein.
     *
     * @param title       Titel der Quelle, für LIESMICH
     * @param host        Hostname der Quelle
     * @param presented   vom Server gesendete Kette (Server-Reihenfolge); leer, wenn der Handshake nicht so weit kam
     * @param anchors     Vertrauensanker der Quelle samt mitgelieferter Zwischenzertifikate
     * @param client      Kette des Client-Zertifikats (Zertifikat zuerst); leer ohne Client-Zertifikat
     * @param problems    Gründe, warum etwas fehlt (Handshake gescheitert, Speicher nicht lesbar); ohne Geheimnisse
     * @param notes       weitere Hinweise für LIESMICH
     */
    public static CertificatePackage of(String title, String host, List<X509Certificate> presented,
                                        List<X509Certificate> anchors, List<X509Certificate> client,
                                        List<String> problems, List<String> notes, ZonedDateTime created) {
        Objects.requireNonNull(created, "created");
        Set<String> anchorKeys = new HashSet<>();
        for (X509Certificate anchor : anchors) {
            anchorKeys.add(sha256(anchor));
        }
        Set<String> sentKeys = new HashSet<>();
        for (X509Certificate certificate : presented) {
            sentKeys.add(sha256(certificate));
        }
        Map<String, Entry> byKey = new LinkedHashMap<>();
        Names names = new Names();

        boolean serverTrusted = false;
        if (!presented.isEmpty()) {
            List<X509Certificate> path = path(presented.get(0), presented, anchors);
            serverTrusted = reachesAnchor(path, anchorKeys);
            add(byKey, names, path.get(0), Role.SERVER, sentKeys, anchorKeys);
            for (X509Certificate certificate : path.subList(1, path.size())) {
                add(byKey, names, certificate, serverTrusted ? caRole(certificate) : Role.UNTRUSTED, sentKeys,
                        anchorKeys);
            }
            for (X509Certificate certificate : presented) {
                // Vom Server mitgeschickt, aber nicht auf dem Weg: nur zur Ansicht.
                add(byKey, names, certificate, Role.UNTRUSTED, sentKeys, anchorKeys);
            }
        }
        boolean clientTrusted = false;
        if (!client.isEmpty()) {
            List<X509Certificate> path = path(client.get(0), client, anchors);
            clientTrusted = reachesAnchor(path, anchorKeys);
            add(byKey, names, path.get(0), Role.CLIENT, sentKeys, anchorKeys);
            for (X509Certificate certificate : path.subList(1, path.size())) {
                add(byKey, names, certificate, clientTrusted ? caRole(certificate) : Role.UNTRUSTED, sentKeys,
                        anchorKeys);
            }
        }
        return new CertificatePackage(blankToDash(title), blankToDash(host), created, problems, notes, serverTrusted,
                clientTrusted, !presented.isEmpty(), new ArrayList<>(byKey.values()));
    }

    /** Alle Einträge in Paketreihenfolge. */
    public List<Entry> entries() {
        return entries;
    }

    /** Ob die vom Server gesendete Kette an einem Anker der Quelle endet. */
    public boolean serverTrusted() {
        return serverTrusted;
    }

    /** Anzahl importierbarer Stamm- bzw. Zwischenzertifikate. */
    public long count(Role role) {
        return entries.stream().filter(e -> e.role() == role).count();
    }

    /** Schreibt das ZIP neu (eine vorhandene Datei wird ersetzt). */
    public void writeZip(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Entry entry : entries) {
                put(zip, entry.path(), pem(entry.certificate()).getBytes(StandardCharsets.US_ASCII));
            }
            StringBuilder chain = new StringBuilder();
            for (Entry entry : entries) {
                if (entry.role() == Role.ROOT || entry.role() == Role.INTERMEDIATE) {
                    chain.append("# ").append(subject(entry.certificate())).append("\r\n")
                            .append(pem(entry.certificate()));
                }
            }
            if (!chain.isEmpty()) {
                put(zip, "kette.pem", chain.toString().getBytes(StandardCharsets.US_ASCII));
            }
            put(zip, "importieren.cmd", script().getBytes(StandardCharsets.ISO_8859_1));
            put(zip, "LIESMICH.txt", readme().getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Die importierbaren Zertifikate: erst Stamm-, dann Zwischenzertifikate. */
    public List<Entry> importable() {
        List<Entry> result = new ArrayList<>();
        entries.stream().filter(e -> e.role() == Role.ROOT).forEach(result::add);
        entries.stream().filter(e -> e.role() == Role.INTERMEDIATE).forEach(result::add);
        return result;
    }

    /** Anzeigename eines Eintrags im Paket: der Dateiname ohne Ordner und Endung. */
    public static String name(Entry entry) {
        String path = entry.path();
        String file = path.substring(path.lastIndexOf('/') + 1);
        return file.endsWith(".cer") ? file.substring(0, file.length() - 4) : file;
    }

    /** Rolle für Benutzer, kurz. */
    public static String roleLabel(Role role) {
        return switch (role) {
            case ROOT -> "Stammzertifizierungsstelle";
            case INTERMEDIATE -> "Zwischenzertifizierungsstelle";
            case SERVER -> "Serverzertifikat";
            case CLIENT -> "Client-Zertifikat";
            case UNTRUSTED -> "nicht vertraut";
        };
    }

    /**
     * Schreibt nur die QR-Codes der importierbaren Zertifikate als PNG (ein Code je Zertifikat, Inhalt PEM) samt
     * kurzer Anleitung; Zertifikate, deren PEM-Text nicht in einen Code passt, nennt die Anleitung.
     *
     * @return Anzahl geschriebener Codes
     */
    public int writeQrZip(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        int written = 0;
        List<String> tooLarge = new ArrayList<>();
        StringBuilder list = new StringBuilder();
        try (OutputStream out = Files.newOutputStream(file, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
             ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            int index = 1;
            for (Entry entry : importable()) {
                java.util.Optional<byte[]> png = CertificateQrCodes.png(entry.certificate());
                if (png.isEmpty()) {
                    tooLarge.add(name(entry));
                    continue;
                }
                String image = String.format(Locale.ROOT, "%02d %s %s.png", index++,
                        entry.role() == Role.ROOT ? "Stamm" : "Zwischen", name(entry));
                put(zip, image, png.get());
                list.append("- ").append(image).append(" → ").append(entry.role() == Role.ROOT
                        ? "„Vertrauenswürdige Stammzertifizierungsstellen“" : "„Zwischenzertifizierungsstellen“")
                        .append(", SHA-1 ").append(colons(sha1(entry.certificate()))).append("\r\n");
                written++;
            }
            StringBuilder text = new StringBuilder();
            text.append("QR-Codes der Zertifikate von „").append(title).append("“ (").append(host).append(")\r\n")
                    .append("Erstellt am ").append(WHEN.format(created)).append(" vom Space Harvester.\r\n")
                    .append("Je Zertifikat ein Code; sein Inhalt ist das Zertifikat als PEM-Text (kein privater ")
                    .append("Schlüssel). Mit dem Telefon scannen und als .crt speichern, dann unter Android ")
                    .append("„Zertifikat installieren“ (CA-Zertifikat); im Space Harvester unter „Serverzertifikate …“ ")
                    .append("über „Zertifikat aus QR-Bild lesen …“.\r\n\r\n");
            text.append(list.isEmpty() ? "Keine vertrauten Zertifikate mit QR-Code.\r\n" : list);
            if (!tooLarge.isEmpty()) {
                text.append("\r\nZu groß für einen QR-Code (bitte das Archiv nutzen): ")
                        .append(String.join(", ", tooLarge)).append("\r\n");
            }
            if (!serverTrusted && serverChainKnown) {
                text.append("\r\nACHTUNG: Die Kette des Servers endet an keinem vertrauten Stammzertifikat; ")
                        .append("ihre Zertifikate bekommen keinen Code.\r\n");
            }
            for (String problem : problems) {
                text.append("- ").append(problem).append("\r\n");
            }
            put(zip, "LIESMICH.txt", text.toString().getBytes(StandardCharsets.UTF_8));
        }
        return written;
    }

    /** Das Importskript: nur Stamm- und Zwischenzertifikate, Benutzerspeicher, ohne Adminrechte. */
    String script() {
        StringBuilder script = new StringBuilder();
        script.append("@echo off\r\n")
                .append("rem Zertifikate fuer ").append(ascii(title)).append(" in den Windows-Speicher des ")
                .append("angemeldeten Benutzers.\r\n")
                .append("rem Ohne Adminrechte. Windows fragt beim Stammzertifikat einmal nach; mit Ja bestaetigen.\r\n")
                .append("rem Das Skript bitte vorher lesen. Die App fuehrt es nie selbst aus.\r\n")
                .append("cd /d \"%~dp0\"\r\n");
        int commands = 0;
        for (Entry entry : entries) {
            if (entry.role() == Role.ROOT) {
                script.append("certutil -user -addstore Root \"").append(entry.path().replace('/', '\\'))
                        .append("\"\r\n");
                commands++;
            }
        }
        for (Entry entry : entries) {
            if (entry.role() == Role.INTERMEDIATE) {
                script.append("certutil -user -addstore CA \"").append(entry.path().replace('/', '\\'))
                        .append("\"\r\n");
                commands++;
            }
        }
        if (commands == 0) {
            script.append("echo Keine vertrauten Zertifikate zum Importieren; siehe LIESMICH.txt.\r\n");
        }
        script.append("pause\r\n");
        return script.toString();
    }

    /** Die Anleitung: welche Datei in welchen Speicher, Fingerabdrücke, was der Server schickt, was fehlt. */
    String readme() {
        StringBuilder text = new StringBuilder();
        text.append("Zertifikate der Quelle „").append(title).append("“ (").append(host).append(")\r\n")
                .append("Erstellt am ").append(WHEN.format(created)).append(" vom Space Harvester.\r\n")
                .append("Das Paket enthält nur öffentliche Zertifikate, keine privaten Schlüssel.\r\n\r\n");

        text.append("ERGEBNIS\r\n");
        if (!serverChainKnown) {
            text.append("- Die Kette des Servers ist unbekannt: Die Verbindung kam nicht bis zum TLS-Handshake.\r\n");
        } else if (serverTrusted) {
            text.append("- Dieser Rechner vertraut dem Server: Die Kette endet an einem vertrauten Stammzertifikat.\r\n");
            long missing = entries.stream().filter(e -> (e.role() == Role.ROOT || e.role() == Role.INTERMEDIATE)
                    && !e.sentByServer()).count();
            if (missing > 0) {
                text.append("- ").append(missing).append(missing == 1 ? " Zertifikat der Kette schickt" :
                        " Zertifikate der Kette schickt").append(" der Server NICHT mit (unten mit „fehlt beim ")
                        .append("Server“ markiert). Ein Rechner ohne diese Zertifikate meldet „nicht sicher“; ")
                        .append("Browser ergänzen fehlende Glieder unterschiedlich, darum zeigt jeder etwas ")
                        .append("anderes.\r\n");
            }
        } else {
            text.append("- ACHTUNG: Die Kette des Servers endet an KEINEM Zertifikat, dem dieser Rechner vertraut.\r\n")
                    .append("  Ihre Zertifikate liegen nur zur Ansicht unter ").append(SERVER)
                    .append(" und stehen NICHT im Importskript.\r\n")
                    .append("  Bitte nicht importieren, ohne die Fingerabdrücke mit der IT abzugleichen.\r\n");
        }
        for (String problem : problems) {
            text.append("- ").append(problem).append("\r\n");
        }
        text.append("\r\n");

        text.append("IMPORT AUF DEM ANDEREN RECHNER\r\n")
                .append("Einfach: importieren.cmd doppelklicken (Benutzerspeicher, ohne Adminrechte; die Rückfrage ")
                .append("von Windows beim Stammzertifikat mit Ja bestätigen).\r\n")
                .append("Von Hand: Datei doppelklicken › Zertifikat installieren › Aktueller Benutzer › ")
                .append("„Alle Zertifikate in folgendem Speicher speichern“:\r\n")
                .append("- ").append(ROOTS).append(" → „Vertrauenswürdige Stammzertifizierungsstellen“\r\n")
                .append("- ").append(INTERMEDIATES).append(" → „Zwischenzertifizierungsstellen“\r\n")
                .append("- ").append(SERVER).append(" → nicht importieren, nur zum Abgleich\r\n")
                .append("- ").append(CLIENT).append(" → nur zur Ansicht; das Client-Zertifikat braucht seinen ")
                .append("privaten Schlüssel und kommt als .pfx von der ausgebenden Stelle\r\n")
                .append("Java-Programme: kette.pem als CA-Datei (tls.ca-certificates-file) angeben.\r\n")
                .append("Firefox nutzt einen eigenen Speicher: about:config › security.enterprise_roots.enabled = ")
                .append("true, dann übernimmt er die Windows-Stammzertifikate.\r\n\r\n");

        text.append("ZERTIFIKATE\r\n");
        for (Entry entry : entries) {
            X509Certificate certificate = entry.certificate();
            text.append(entry.path()).append("\r\n")
                    .append("  Rolle:      ").append(roleText(entry)).append("\r\n")
                    .append("  Betreff:    ").append(certificate.getSubjectX500Principal().getName()).append("\r\n")
                    .append("  Aussteller: ").append(certificate.getIssuerX500Principal().getName()).append("\r\n")
                    .append("  Gültig:     ").append(day(certificate.getNotBefore().toInstant())).append(" bis ")
                    .append(day(certificate.getNotAfter().toInstant())).append("\r\n")
                    .append("  SHA-1:      ").append(colons(sha1(certificate))).append("\r\n")
                    .append("  SHA-256:    ").append(colons(sha256(certificate))).append("\r\n")
                    .append("  Herkunft:   ").append(origin(entry)).append("\r\n");
        }
        if (entries.isEmpty()) {
            text.append("(keine)\r\n");
        }
        if (!notes.isEmpty()) {
            text.append("\r\nHINWEISE\r\n");
            for (String note : notes) {
                text.append("- ").append(note).append("\r\n");
            }
        }
        return text.toString();
    }

    private String roleText(Entry entry) {
        return switch (entry.role()) {
            case ROOT -> "Stammzertifizierungsstelle (importieren: Vertrauenswürdige Stammzertifizierungsstellen)";
            case INTERMEDIATE -> "Zwischenzertifizierungsstelle (importieren: Zwischenzertifizierungsstellen)";
            case SERVER -> "Serverzertifikat (nicht importieren)";
            case CLIENT -> "Client-Zertifikat, nur öffentlicher Teil" + (clientTrusted ? ""
                    : "; seine Kette endet an keinem vertrauten Stammzertifikat");
            case UNTRUSTED -> "nicht vertraut, nur zur Ansicht (nicht importieren)";
        };
    }

    private static String origin(Entry entry) {
        if (entry.role() == Role.CLIENT) {
            return "Speicher des Client-Zertifikats";
        }
        if (entry.sentByServer() && entry.inTrustStore()) {
            return "vom Server gesendet und im Vertrauen dieses Rechners";
        }
        if (entry.sentByServer()) {
            return "vom Server gesendet";
        }
        if (entry.inTrustStore()) {
            return "nur im Vertrauen dieses Rechners (fehlt beim Server)";
        }
        return "Kette des Client-Zertifikats";
    }

    private static void add(Map<String, Entry> byKey, Names names, X509Certificate certificate, Role role,
                            Set<String> sentKeys, Set<String> anchorKeys) {
        String key = sha256(certificate);
        if (byKey.containsKey(key)) {
            return;
        }
        String folder = switch (role) {
            case ROOT -> ROOTS;
            case INTERMEDIATE -> INTERMEDIATES;
            case SERVER, UNTRUSTED -> SERVER;
            case CLIENT -> CLIENT;
        };
        String path = names.unique(folder, fileName(certificate), key);
        byKey.put(key, new Entry(certificate, role, sentKeys.contains(key), anchorKeys.contains(key), path));
    }

    /**
     * Der Weg vom Zertifikat nach oben: je Glied der Aussteller per Name und Signatur, zuerst unter den Ankern, dann
     * unter den mitgeschickten Zertifikaten; endet am selbstsignierten Zertifikat oder wo kein Aussteller bekannt ist.
     */
    static List<X509Certificate> path(X509Certificate start, List<X509Certificate> sent,
                                      List<X509Certificate> anchors) {
        List<X509Certificate> path = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        X509Certificate current = start;
        while (current != null && seen.add(sha256(current))) {
            path.add(current);
            if (selfSigned(current)) {
                break;
            }
            X509Certificate issuer = issuer(current, anchors);
            current = issuer != null ? issuer : issuer(current, sent);
        }
        return path;
    }

    private static boolean reachesAnchor(List<X509Certificate> path, Set<String> anchorKeys) {
        for (X509Certificate certificate : path.subList(1, path.size())) {
            if (anchorKeys.contains(sha256(certificate))) {
                return true;
            }
        }
        // Auch das erste Glied selbst kann Anker sein (z. B. ein ausdrücklich vertrautes Serverzertifikat).
        return !path.isEmpty() && anchorKeys.contains(sha256(path.get(0)));
    }

    private static X509Certificate issuer(X509Certificate certificate, List<X509Certificate> candidates) {
        for (X509Certificate candidate : candidates) {
            if (candidate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                    && !sha256(candidate).equals(sha256(certificate)) && signs(candidate, certificate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Role caRole(X509Certificate certificate) {
        return selfSigned(certificate) ? Role.ROOT : Role.INTERMEDIATE;
    }

    static boolean selfSigned(X509Certificate certificate) {
        return certificate.getSubjectX500Principal().equals(certificate.getIssuerX500Principal())
                && signs(certificate, certificate);
    }

    private static boolean signs(X509Certificate issuer, X509Certificate subject) {
        try {
            subject.verify(issuer.getPublicKey());
            return true;
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    /** Dateiname aus dem CN (sonst dem ganzen Betreff), für Windows gesäubert, ohne Endung. */
    static String fileName(X509Certificate certificate) {
        String dn = certificate.getSubjectX500Principal().getName();
        Matcher matcher = COMMON_NAME.matcher(dn);
        String name = matcher.find() ? matcher.group(1).replace("\\,", ",") : dn;
        return safe(name);
    }

    /** Säubert einen Namen für Windows: keine Pfadtrenner oder Steuerzeichen, keine reservierten Namen, begrenzt. */
    public static String safe(String name) {
        String cleaned = ILLEGAL.matcher(name == null ? "" : name).replaceAll("_").strip();
        cleaned = cleaned.replaceAll("[. ]+$", "").replaceAll("^\\.+", "");
        if (cleaned.length() > MAX_NAME) {
            cleaned = cleaned.substring(0, MAX_NAME).strip();
        }
        if (cleaned.isEmpty()) {
            cleaned = "Zertifikat";
        }
        String stem = cleaned.contains(".") ? cleaned.substring(0, cleaned.indexOf('.')) : cleaned;
        if (RESERVED.contains(stem.toUpperCase(Locale.ROOT))) {
            cleaned = "_" + cleaned;
        }
        return cleaned;
    }

    /** Vergibt eindeutige Namen je Ordner; gleiche Namen unterscheidet der Anfang des Fingerabdrucks. */
    private static final class Names {
        private final Set<String> used = new HashSet<>();

        String unique(String folder, String name, String sha256) {
            String path = folder + name + ".cer";
            if (used.add(path.toLowerCase(Locale.ROOT))) {
                return path;
            }
            for (int length = 8; length <= sha256.length(); length += 8) {
                path = folder + name + " (" + sha256.substring(0, length) + ").cer";
                if (used.add(path.toLowerCase(Locale.ROOT))) {
                    return path;
                }
            }
            throw new IllegalStateException("Dateiname nicht eindeutig");
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }

    static String pem(X509Certificate certificate) {
        try {
            String base64 = Base64.getMimeEncoder(64, "\r\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(certificate.getEncoded());
            return "-----BEGIN CERTIFICATE-----\r\n" + base64 + "\r\n-----END CERTIFICATE-----\r\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Zertifikat nicht kodierbar", e);
        }
    }

    static String sha256(X509Certificate certificate) {
        return digest("SHA-256", certificate);
    }

    public static String sha1(X509Certificate certificate) {
        return digest("SHA-1", certificate);
    }

    private static String digest(String algorithm, X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(certificate.getEncoded()));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(algorithm + " nicht verfügbar", e);
        }
    }

    public static String colons(String hex) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                out.append(':');
            }
            out.append(hex, i, i + 2);
        }
        return out.toString().toUpperCase(Locale.ROOT);
    }

    private static String subject(X509Certificate certificate) {
        return ascii(certificate.getSubjectX500Principal().getName());
    }

    private String day(java.time.Instant instant) {
        return DAY.format(instant.atZone(zone()));
    }

    private ZoneId zone() {
        return created.getZone();
    }

    private static String ascii(String text) {
        return text.replaceAll("[^\\x20-\\x7e]", "_");
    }

    private static String blankToDash(String text) {
        return text == null || text.isBlank() ? "-" : text;
    }
}
