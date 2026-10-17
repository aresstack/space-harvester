package com.aresstack.spaceharvester.app.settings;

import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Trägt eine neue Quelle in die Konfigurationsdatei ein, ohne den Rest der Datei anzufassen: Kommentare, Reihenfolge
 * und Platzhalter bleiben, der Eintrag kommt ans Ende von {@code space-harvester.sources}. Vor dem Schreiben wird der
 * neue Text wie beim Start gelesen; scheitert das, bleibt die Datei unverändert. Die vorige Fassung liegt danach als
 * {@code <datei>.bak} daneben.
 */
public final class ConfigFileEditor {

    private static final Pattern ROOT = Pattern.compile("space-harvester:\\s*(#.*)?");
    private static final Pattern SOURCES = Pattern.compile("( +)sources:\\s*(\\[\\s*])?\\s*(#.*)?");
    private static final String HEADER = """
            # Space Harvester Desktop – Konfiguration (angelegt vom Quellen-Assistenten)
            # Geheimnisse nie hier eintragen, sondern als Umgebungsvariable referenzieren: ${NAME}.
            """;

    private final AppSettingsLoader loader;

    public ConfigFileEditor(AppSettingsLoader loader) {
        this.loader = loader;
    }

    /** Ergebnis eines Entwurfs: der neue Dateitext und wie die App ihn liest. */
    public record Draft(String text, AppSettings settings) {
    }

    /**
     * Der Dateitext mit der neuen Quelle, gelesen wie beim Start.
     *
     * @throws IllegalArgumentException wenn die Eingaben unvollständig sind, die ID schon vergeben ist oder die Datei
     *                                  so aufgebaut ist, dass der Eintrag nicht sicher eingefügt werden kann
     */
    public Draft draft(Path file, SourceDraft source) throws IOException {
        List<String> problems = source.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String current = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
        if (configuredIds(current).contains(source.id())) {
            throw new IllegalArgumentException("Eine Quelle mit der ID " + source.id() + " gibt es schon.");
        }
        String text = insert(current, source);
        AppSettings settings = loader.read(file, text);
        boolean present = settings.sources().stream().anyMatch(entry -> entry.id().equals(source.id()));
        if (!present) {
            List<String> mine = settings.problems().stream().filter(p -> p.contains(source.id())).toList();
            throw new IllegalArgumentException(mine.isEmpty()
                    ? "Die Quelle ließ sich nicht in die Konfigurationsdatei einfügen; bitte die Datei von Hand ergänzen."
                    : String.join(" ", mine));
        }
        return new Draft(text, settings);
    }

    /** Schreibt den Entwurf; die bisherige Datei bleibt als {@code .bak} erhalten. */
    public void save(Path file, Draft draft) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        if (Files.isRegularFile(target)) {
            Files.copy(target, target.resolveSibling(target.getFileName() + ".bak"),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        Path temp = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, draft.text(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Fügt den Eintrag am Ende von {@code space-harvester.sources} ein; legt fehlende Schlüssel an. */
    static String insert(String text, SourceDraft source) {
        if (text.isBlank()) {
            return HEADER + "\nspace-harvester:\n  sources:\n" + source.yaml("    ");
        }
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int root = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (ROOT.matcher(lines.get(i)).matches()) {
                root = i;
                break;
            }
            if (lines.get(i).startsWith("space-harvester:")) {
                throw unsupported("space-harvester steht in einer Zeile");
            }
        }
        if (root < 0) {
            lines.add("");
            lines.add("space-harvester:");
            lines.add("  sources:");
            add(lines, lines.size(), source.yaml("    "));
            return String.join(newline, lines) + newline;
        }
        int end = root + 1;
        int childIndent = -1;
        int sources = -1;
        for (; end < lines.size(); end++) {
            String line = lines.get(end);
            if (blankOrComment(line)) {
                continue;
            }
            int indent = indent(line);
            if (indent == 0) {
                break;
            }
            if (childIndent < 0) {
                childIndent = indent;
            }
            if (indent == childIndent) {
                Matcher matcher = SOURCES.matcher(line);
                if (matcher.matches()) {
                    sources = end;
                    if (matcher.group(2) != null) {
                        lines.set(end, " ".repeat(indent) + "sources:");
                    }
                } else if (line.strip().startsWith("sources:")) {
                    throw unsupported("sources steht in einer Zeile");
                }
            }
        }
        if (childIndent < 0) {
            childIndent = 2;
        }
        String itemIndent = " ".repeat(childIndent + 2);
        if (sources < 0) {
            lines.add(root + 1, " ".repeat(childIndent) + "sources:");
            add(lines, root + 2, source.yaml(itemIndent));
            return String.join(newline, lines) + newline;
        }
        int last = sources;
        for (int i = sources + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (blankOrComment(line)) {
                continue;
            }
            int indent = indent(line);
            boolean item = indent > childIndent || (indent == childIndent && line.strip().startsWith("- "));
            if (!item) {
                break;
            }
            if (last == sources) {
                itemIndent = " ".repeat(indent);
            }
            last = i;
        }
        // Auskommentierte Zeilen direkt am Ende der Liste (Beispiele, Optionen des letzten Eintrags) gehören noch dazu.
        while (last + 1 < lines.size() && lines.get(last + 1).strip().startsWith("#")
                && indent(lines.get(last + 1)) > childIndent) {
            last++;
        }
        add(lines, last + 1, source.yaml(itemIndent));
        return String.join(newline, lines) + newline;
    }

    /** IDs aller Einträge unter {@code space-harvester.sources}, auch solcher, die die App wegen Fehlern auslässt. */
    static List<String> configuredIds(String text) {
        List<String> ids = new ArrayList<>();
        if (text.isBlank()) {
            return ids;
        }
        Object document;
        try {
            document = AppSettingsLoader.yaml().load(text);
        } catch (YAMLException e) {
            throw new IllegalArgumentException("Die Konfigurationsdatei ist kein gültiges YAML; bitte zuerst die Datei "
                    + "korrigieren (Einstellungen › Datei öffnen).");
        }
        if (document instanceof Map<?, ?> map
                && AppSettingsLoader.get(map, AppSettingsLoader.ROOT) instanceof Map<?, ?> root
                && AppSettingsLoader.get(root, "sources") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> source && AppSettingsLoader.get(source, "id") != null) {
                    ids.add(String.valueOf(AppSettingsLoader.get(source, "id")).strip());
                }
            }
        }
        return ids;
    }

    private static void add(List<String> lines, int at, String block) {
        lines.addAll(at, List.of(block.split("\n")));
    }

    private static boolean blankOrComment(String line) {
        String stripped = line.strip();
        return stripped.isEmpty() || stripped.startsWith("#");
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static IllegalArgumentException unsupported(String what) {
        return new IllegalArgumentException("Die Konfigurationsdatei lässt sich nicht automatisch ergänzen (" + what
                + "). Die Quelle bitte von Hand eintragen; die Vorschau zeigt den Eintrag.");
    }
}
