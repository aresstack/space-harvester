package com.aresstack.enterpriseai.proasteion.emporion.holkas.localfiles;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Paritäts-Ordner für die Dateiquelle (#193, ADR 0012, docs/PARITY.md, „Vergleichstests“): dieselben Dateien für den
 * Enterprise AI Client und den Core, lokal, ohne Netz.
 *
 * <p>Liegt im Paket von {@code LocalFilesSourceProvider} und braucht nur Java 8, damit die Datei unverändert in die
 * Test-Fixtures von {@code source-localfiles} im Enterprise AI Client übernommen werden kann. Im Core liegt sie in
 * den Test-Fixtures von {@code space-harvester-core}, weil {@code enterprise-ai-sources} eine unveränderte Kopie
 * bleibt.</p>
 *
 * <p>Inhalt:</p>
 * <ul>
 *   <li>{@code README.md} im Wurzelordner und Markdown bis Ebene 4: {@code docs/architektur.md},
 *       {@code docs/betrieb/handbuch.md}, {@code docs/betrieb/nacht/ablauf.md}.</li>
 *   <li>Überschriften, Tabelle 3 × 3 mit Kopfzeile, verschachtelte und nummerierte Listen, Codeblock, Links (extern,
 *       relativ auf eine andere Datei, {@code mailto:}).</li>
 *   <li>Reiner Text ({@code notizen.txt}) und CSV ({@code daten/werte.csv}).</li>
 *   <li>Ein Bild ({@link #IMAGE}), das kein Textdokument ist.</li>
 *   <li>Eine Datei, die {@link #EXCLUDE} ausschließt ({@link #EXCLUDED}).</li>
 * </ul>
 */
public final class ParityFiles {

    /** Textdokumente in Pfadreihenfolge (Trenner {@code /}). */
    public static final String[] DOCUMENTS = {"README.md", "daten/werte.csv", "docs/architektur.md",
            "docs/betrieb/handbuch.md", "docs/betrieb/nacht/ablauf.md", "notizen.txt"};
    /** Das Bild ohne Text. */
    public static final String IMAGE = "docs/bilder/logo.png";
    /** Muster für {@code exclude} und die Datei, die es ausschließt. */
    public static final String EXCLUDE = "*.tmp";
    public static final String EXCLUDED = "docs/entwurf.tmp";
    /** Unterordner als Startpunkt im Abgleichsumfang (Scope, nicht {@code options}) und die Textdokumente darunter. */
    public static final String START_POINT = "docs";
    public static final String[] START_POINT_DOCUMENTS = {"docs/architektur.md", "docs/betrieb/handbuch.md",
            "docs/betrieb/nacht/ablauf.md"};
    /** Tiefste Ebene einer Datei ({@code docs/betrieb/nacht/ablauf.md} = 4). */
    public static final int DEPTH = 4;

    /** Sätze, die in jedem Export und jeder Leseansicht vorkommen müssen; je Textdokument mindestens einer. */
    public static final String[] SENTENCES = {
            "Satz Alpha im Readme.",
            "Satz Bravo zur Architektur.",
            "Satz Charlie zum Betrieb.",
            "Satz Delta zum Nachtlauf.",
            "Satz Echo in den Notizen.",
            "Satz Foxtrot in den Werten.",
    };
    /** Satz nur in {@link #EXCLUDED}; darf nirgends ankommen. */
    public static final String EXCLUDED_SENTENCE = "Satz Golf im Entwurf.";

    /** Suchbegriff und das Dokument, das ihn als einziges enthält. */
    public static final String SEARCH = "Nachtlauf";
    public static final String SEARCH_HIT = "docs/betrieb/nacht/ablauf.md";

    /** Bild ohne Text: ein PNG mit 1 × 1 Pixel. */
    public static final byte[] LOGO_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==");

    /** Inhalte je Pfad ({@code String} als UTF-8, sonst Bytes), mit Bild und ausgeschlossener Datei. */
    public static final Map<String, Object> FILES;

    static {
        Map<String, Object> files = new LinkedHashMap<String, Object>();
        files.put("README.md", "# Handbuch\n\nSatz Alpha im Readme.\n\n"
                + "Siehe [Architektur](docs/architektur.md) und [Dokumentation](https://example.org/doku), "
                + "Fragen an [Team](mailto:team@example.org).\n");
        files.put("daten/werte.csv", "Name;Wert\nSatz Foxtrot in den Werten.;1\n");
        files.put("docs/architektur.md", "# Architektur\n\nSatz Bravo zur Architektur.\n\n"
                + "## Komponenten\n\n"
                + "| Name | Rolle | Ort |\n| --- | --- | --- |\n| Core | Bibliothek | JVM |\n"
                + "| Server | REST | Dienst |\n| App | Oberfläche | Desktop |\n\n"
                + "- Quellen\n  - Confluence\n  - Ordner\n- Export\n\n"
                + "1. Beschaffen\n2. Konvertieren\n\n"
                + "```java\nSystem.out.println(\"Parität\");\n```\n");
        files.put("docs/betrieb/handbuch.md", "# Betrieb\n\nSatz Charlie zum Betrieb.\n\n"
                + "Weiter mit dem [Ablauf](nacht/ablauf.md).\n");
        files.put("docs/betrieb/nacht/ablauf.md", "# Ablauf\n\nSatz Delta zum Nachtlauf.\n");
        files.put(IMAGE, LOGO_PNG);
        files.put(EXCLUDED, "Satz Golf im Entwurf.\n");
        files.put("notizen.txt", "Satz Echo in den Notizen.\n");
        FILES = Collections.unmodifiableMap(files);
    }

    private ParityFiles() {
    }

    /** Legt die Dateien unter {@code directory} an und gibt den Ordner zurück; {@code directory} der Quelle. */
    public static Path create(Path directory) {
        try {
            for (Map.Entry<String, Object> file : FILES.entrySet()) {
                Path target = directory.resolve(file.getKey());
                Files.createDirectories(target.getParent());
                Object value = file.getValue();
                Files.write(target, value instanceof byte[] ? (byte[]) value
                        : String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            }
            return directory;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
