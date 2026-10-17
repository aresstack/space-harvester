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
import java.util.stream.Stream;

/**
 * Trägt Quellen in die Konfigurationsdatei ein, ersetzt oder entfernt sie, ohne den Rest der Datei anzufassen:
 * Kommentare, Reihenfolge und Platzhalter bleiben, ein neuer Eintrag kommt ans Ende von {@code space-harvester.sources},
 * ein geänderter an seine bisherige Stelle. Vor dem Schreiben wird der neue Text wie beim Start gelesen; scheitert das,
 * bleibt die Datei unverändert. Die vorige Fassung liegt danach als {@code <datei>.bak} daneben.
 */
public final class ConfigFileEditor {

    // Schlüssel locker wie beim Laden: space-harvester, space_harvester, spaceHarvester; sources, Sources.
    private static final Pattern ROOT = Pattern.compile("([A-Za-z_-]+):\\s*(#.*)?");
    private static final Pattern ROOT_INLINE = Pattern.compile("([A-Za-z_-]+):\\s*\\S.*");
    private static final Pattern SOURCES = Pattern.compile("( +)([A-Za-z_-]+):\\s*(\\[\\s*])?\\s*(#.*)?");
    private static final Pattern SOURCES_INLINE = Pattern.compile(" *([A-Za-z_-]+):\\s*\\S.*");
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

    /**
     * Der Dateitext, in dem der Eintrag mit der ID von {@code source} an derselben Stelle durch die neuen Angaben
     * ersetzt ist.
     */
    public Draft replace(Path file, SourceDraft source) throws IOException {
        List<String> problems = source.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String current = Files.readString(file, StandardCharsets.UTF_8);
        List<String> before = configuredIds(current);
        Edit edit = locate(current, source.id());
        List<String> lines = edit.lines();
        lines.subList(edit.start(), edit.end()).clear();
        add(lines, edit.start(), source.yaml(edit.indent()));
        String text = String.join(edit.newline(), lines) + edit.newline();
        if (!configuredIds(text).equals(before)) {
            throw unsupported("der Eintrag ließ sich nicht eindeutig abgrenzen");
        }
        AppSettings settings = loader.read(file, text);
        if (settings.sources().stream().noneMatch(entry -> entry.id().equals(source.id()))) {
            List<String> mine = settings.problems().stream().filter(p -> p.contains(source.id())).toList();
            throw new IllegalArgumentException(mine.isEmpty()
                    ? "Die geänderte Quelle ließ sich nicht lesen; bitte die Datei von Hand bearbeiten."
                    : String.join(" ", mine));
        }
        return new Draft(text, settings);
    }

    /** Der Dateitext ohne den Eintrag {@code sourceId}; Kommentare um ihn herum bleiben stehen. */
    public Draft remove(Path file, String sourceId) throws IOException {
        String current = Files.readString(file, StandardCharsets.UTF_8);
        List<String> expected = new ArrayList<>(configuredIds(current));
        expected.remove(sourceId);
        Edit edit = locate(current, sourceId);
        List<String> lines = edit.lines();
        lines.subList(edit.start(), edit.end()).clear();
        String text = String.join(edit.newline(), lines) + edit.newline();
        if (!configuredIds(text).equals(expected)) {
            throw unsupported("der Eintrag ließ sich nicht eindeutig abgrenzen");
        }
        return new Draft(text, loader.read(file, text));
    }

    /**
     * Der Eintrag {@code sourceId} so, wie der Assistent ihn bearbeiten kann.
     *
     * @throws IllegalArgumentException wenn der Eintrag Schlüssel enthält, die der Assistent nicht kennt; sie gingen
     *                                  beim Speichern verloren
     */
    public SourceDraft.Editable editable(Path file, String sourceId) throws IOException {
        Map<?, ?> raw = rawSource(Files.readString(file, StandardCharsets.UTF_8), sourceId);
        return SourceDraft.from(raw);
    }

    /**
     * Ob der Eintrag Passwort, Token oder Zertifikatspasswort im Klartext enthält, auch als Standardwert eines
     * Platzhalters ({@code ${TOKEN:geheim}}) oder in einem abgeschalteten Client-Zertifikat.
     */
    public boolean hasPlaintextSecret(Path file, String sourceId) throws IOException {
        Map<?, ?> raw = rawSource(Files.readString(file, StandardCharsets.UTF_8), sourceId);
        Map<?, ?> auth = AppSettingsLoader.map(AppSettingsLoader.get(raw, "auth"));
        Map<?, ?> certificate = AppSettingsLoader.map(AppSettingsLoader.get(raw, "client-certificate"));
        return Stream.of(AppSettingsLoader.get(auth, "password"), AppSettingsLoader.get(auth, "token"),
                        AppSettingsLoader.get(certificate, "password"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .anyMatch(value -> !SourceDraft.isBarePlaceholder(String.valueOf(value)));
    }

    /** Schreibt den Entwurf; die bisherige Datei bleibt als {@code .bak} erhalten. */
    public void save(Path file, Draft draft) throws IOException {
        save(file, draft, true);
    }

    /**
     * @param backup {@code false}, wenn die bisherige Fassung ein Geheimnis im Klartext enthält: Dann entsteht keine
     *               Sicherung, und eine alte {@code .bak} wird gelöscht, damit das Geheimnis nicht liegen bleibt
     */
    public void save(Path file, Draft draft, boolean backup) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path bak = target.resolveSibling(target.getFileName() + ".bak");
        if (!backup) {
            Files.deleteIfExists(bak);
        } else if (Files.isRegularFile(target)) {
            Files.copy(target, bak, StandardCopyOption.REPLACE_EXISTING);
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
            if (isKey(ROOT, lines.get(i), 1, "spaceharvester")) {
                root = i;
                break;
            }
            if (isKey(ROOT_INLINE, lines.get(i), 1, "spaceharvester")) {
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
                if (matcher.matches() && AppSettingsLoader.normalize(matcher.group(2)).equals("sources")) {
                    sources = end;
                    if (matcher.group(3) != null) {
                        lines.set(end, " ".repeat(indent) + matcher.group(2) + ":");
                    }
                } else if (isKey(SOURCES_INLINE, line, 1, "sources")) {
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

    /** Zeilen eines Eintrags in der Liste {@code sources}: {@code [start, end)} ohne folgende Leer-/Kommentarzeilen. */
    private record Edit(List<String> lines, int start, int end, String indent, String newline) {
    }

    private static Edit locate(String text, String sourceId) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int root = -1;
        for (int i = 0; i < lines.size() && root < 0; i++) {
            if (isKey(ROOT, lines.get(i), 1, "spaceharvester")) {
                root = i;
            }
        }
        int sources = -1;
        int childIndent = -1;
        for (int i = root + 1; root >= 0 && i < lines.size() && sources < 0; i++) {
            String line = lines.get(i);
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
            if (indent == childIndent && isKey(SOURCES, line, 2, "sources")) {
                sources = i;
            }
        }
        if (sources < 0) {
            throw new IllegalArgumentException("Die Quelle " + sourceId + " steht nicht in der Konfigurationsdatei.");
        }
        List<Integer> starts = new ArrayList<>();
        int itemIndent = -1;
        int listEnd = sources + 1;
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
            if (itemIndent < 0) {
                itemIndent = indent;
            }
            if (indent == itemIndent && line.strip().startsWith("- ")) {
                starts.add(i);
            }
            listEnd = i + 1;
        }
        starts.add(listEnd);
        for (int k = 0; k + 1 < starts.size(); k++) {
            int start = starts.get(k);
            int end = starts.get(k + 1);
            while (end > start + 1 && blankOrComment(lines.get(end - 1))) {
                end--;
            }
            StringBuilder block = new StringBuilder();
            for (int i = start; i < end; i++) {
                String line = lines.get(i);
                block.append(line.length() >= itemIndent ? line.substring(itemIndent) : line.strip()).append('\n');
            }
            Object parsed;
            try {
                parsed = AppSettingsLoader.yaml().load(block.toString());
            } catch (YAMLException e) {
                continue;
            }
            if (parsed instanceof List<?> list && list.size() == 1 && list.get(0) instanceof Map<?, ?> entry
                    && sourceId.equals(String.valueOf(AppSettingsLoader.get(entry, "id")).strip())) {
                return new Edit(lines, start, end, " ".repeat(itemIndent), newline);
            }
        }
        throw new IllegalArgumentException("Der Eintrag der Quelle " + sourceId + " ließ sich in der Datei nicht "
                + "abgrenzen; bitte von Hand bearbeiten (Einstellungen › Datei öffnen).");
    }

    private static Map<?, ?> rawSource(String text, String sourceId) {
        Object document = AppSettingsLoader.yaml().load(text);
        if (document instanceof Map<?, ?> map
                && AppSettingsLoader.get(map, AppSettingsLoader.ROOT) instanceof Map<?, ?> root
                && AppSettingsLoader.get(root, "sources") instanceof List<?> list) {
            for (Object entry : list) {
                if (entry instanceof Map<?, ?> source
                        && sourceId.equals(String.valueOf(AppSettingsLoader.get(source, "id")).strip())) {
                    return source;
                }
            }
        }
        throw new IllegalArgumentException("Die Quelle " + sourceId + " steht nicht in der Konfigurationsdatei.");
    }

    private static boolean isKey(Pattern pattern, String line, int group, String normalized) {
        Matcher matcher = pattern.matcher(line);
        return matcher.matches() && AppSettingsLoader.normalize(matcher.group(group)).equals(normalized);
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
