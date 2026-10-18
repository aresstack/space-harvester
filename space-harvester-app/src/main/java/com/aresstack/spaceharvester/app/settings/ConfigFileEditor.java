package com.aresstack.spaceharvester.app.settings;

import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import com.aresstack.spaceharvester.source.network.TrustPolicy;
import com.aresstack.spaceharvester.source.network.TrustSettings;
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
import java.util.Set;
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
    private static final Pattern SCALAR_KEY = Pattern.compile("\\s*([A-Za-z_-]+):");
    private static final Pattern ROOT_INLINE = Pattern.compile("([A-Za-z_-]+):\\s*\\S.*");
    private static final Pattern SOURCES = Pattern.compile("( +)([A-Za-z_-]+):\\s*(\\[\\s*])?\\s*(#.*)?");
    private static final Pattern SOURCES_INLINE = Pattern.compile(" *([A-Za-z_-]+):\\s*\\S.*");
    /** Schlüssel (normalisiert wie im Loader), deren Wert ein Geheimnis ist. */
    private static final Set<String> SECRET_KEYS = Set.of("password", "token", "secret", "clientsecret", "passphrase",
            "apikey", "keypassword", "storepassword");
    private static final String HEADER = """
            # Space Harvester Desktop – Konfiguration (angelegt von der App)
            # Geheimnisse nie hier eintragen: Hier steht nur ${NAME}; den Wert legt die App verschlüsselt ab.
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

    /**
     * Ob irgendwo in der Datei ein Passwort, Token oder Geheimnis im Klartext steht (alle Quellen, Vertrauensspeicher,
     * auch nicht ausgewertete Felder). Nur eine Datei ohne Klartext-Geheimnis darf beim Bearbeiten und Entfernen als
     * {@code .bak} liegen bleiben.
     */
    public boolean fileHasPlaintextSecret(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return false;
        }
        return containsPlaintextSecret(AppSettingsLoader.yaml().load(Files.readString(file, StandardCharsets.UTF_8)));
    }

    private static boolean containsPlaintextSecret(Object node) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                Object value = entry.getValue();
                if (SECRET_KEYS.contains(AppSettingsLoader.normalize(String.valueOf(entry.getKey())))
                        && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)
                        && value != null && !String.valueOf(value).isBlank()
                        && !SourceDraft.isBarePlaceholder(String.valueOf(value))) {
                    return true;
                }
                if (containsPlaintextSecret(value)) {
                    return true;
                }
            }
        } else if (node instanceof List<?> list) {
            return list.stream().anyMatch(ConfigFileEditor::containsPlaintextSecret);
        }
        return false;
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

    /**
     * Der Entwurf {@code base} mit neuen Proxy-Einstellungen unter {@code space-harvester.network.proxy}; sie gelten
     * für alle Quellen. Nur der Block {@code proxy:} wird ersetzt (fehlt er, angelegt); Kommentare und alles andere
     * bleiben.
     */
    public Draft withProxy(Path file, Draft base, ProxyDraft proxy) {
        List<String> problems = proxy.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String text = proxy(base.text(), proxy);
        AppSettings settings = loader.read(file, text);
        if (!settings.proxy().equals(proxy.settings())) {
            throw new IllegalArgumentException("Die Proxy-Einstellungen ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester.network.proxy von Hand bearbeiten."
                    + (settings.problems().isEmpty() ? "" : " " + String.join(" ", settings.problems())));
        }
        return new Draft(text, settings);
    }

    /** Ersetzt oder ergänzt {@code space-harvester.network.proxy}. */
    static String proxy(String text, ProxyDraft proxy) {
        return network(text, "proxy", proxy::yaml);
    }

    /**
     * Der Entwurf {@code base} mit neuem Vertrauen für Quellen ohne eigenes {@code trust-bundle} unter
     * {@code space-harvester.network.tls} (#189). Nur dieser Block ändert sich.
     */
    public Draft withTls(Path file, Draft base, TrustSettings tls) {
        List<String> problems = TrustPolicy.problems(tls);
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String text = network(base.text(), "tls", indent -> tls(indent, tls));
        AppSettings settings = loader.read(file, text);
        if (!settings.tls().equals(tls)) {
            throw new IllegalArgumentException("Die Zertifikatseinstellungen ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester.network.tls von Hand bearbeiten."
                    + (settings.problems().isEmpty() ? "" : " " + String.join(" ", settings.problems())));
        }
        return new Draft(text, settings);
    }

    /** Der Block {@code tls:} mit allen Schaltern, damit die Vorgabe des Betriebssystems nicht mehr hineinspielt. */
    static String tls(String indent, TrustSettings tls) {
        String inner = indent + "  ";
        StringBuilder yaml = new StringBuilder(indent).append("tls:\n")
                .append(inner).append("use-jvm-default: ").append(tls.useJvmDefault()).append('\n')
                .append(inner).append("use-windows-root: ").append(tls.useWindowsRoot()).append('\n')
                .append(inner).append("use-windows-ca-stores: ").append(tls.useWindowsCaStores()).append('\n');
        if (tls.caCertificatesFile() != null) {
            yaml.append(inner).append("ca-certificates-file: \"").append(tls.caCertificatesFile().toString()
                    .replace("\\", "\\\\").replace("\"", "\\\"")).append("\"\n");
        }
        return yaml.toString();
    }

    /** Ersetzt oder ergänzt den Block {@code key} unter {@code space-harvester.network}. */
    private static String network(String text, String key, java.util.function.Function<String, String> yaml) {
        return nested(text, "network", key, yaml);
    }

    /** Ersetzt oder ergänzt {@code key} (Block oder Wert) unter {@code space-harvester.<parent>}. */
    private static String nested(String text, String parent, String key,
                                 java.util.function.Function<String, String> yaml) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
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
            lines.add("  " + parent + ":");
            add(lines, lines.size(), yaml.apply("    "));
            return String.join(newline, lines) + newline;
        }
        int rootEnd = blockEnd(lines, root, 0);
        int childIndent = childIndent(lines, root, rootEnd, 2);
        int network = child(lines, root, rootEnd, childIndent, parent);
        if (network < 0) {
            lines.add(root + 1, " ".repeat(childIndent) + parent + ":");
            add(lines, root + 2, yaml.apply(" ".repeat(childIndent * 2)));
            return String.join(newline, lines) + newline;
        }
        int networkEnd = blockEnd(lines, network, childIndent);
        int proxyIndent = childIndent(lines, network, networkEnd, childIndent * 2);
        String normalized = AppSettingsLoader.normalize(key);
        for (int i = network + 1; i < networkEnd; i++) {
            // Ein einzelner Wert wie „max-age: 6h“ wird in seiner Zeile ersetzt, ein Kommentar dahinter entfällt.
            String line = lines.get(i);
            if (!blankOrComment(line) && indent(line) == proxyIndent && isKey(SOURCES_INLINE, line, 1, normalized)) {
                lines.remove(i);
                add(lines, i, yaml.apply(" ".repeat(proxyIndent)));
                return String.join(newline, lines) + newline;
            }
        }
        int block = child(lines, network, networkEnd, proxyIndent, normalized);
        if (block < 0) {
            add(lines, network + 1, yaml.apply(" ".repeat(proxyIndent)));
            return String.join(newline, lines) + newline;
        }
        int end = blockEnd(lines, block, proxyIndent);
        lines.subList(block, end).clear();
        add(lines, block, yaml.apply(" ".repeat(proxyIndent)));
        return String.join(newline, lines) + newline;
    }

    /**
     * Der Entwurf {@code base} mit neuer Cache-Frische ({@code space-harvester.cache.max-age}, {@code version-check})
     * und Sperrfrist ({@code space-harvester.sync.withheld-retention}, ADR 0014); dieselben Schlüssel wie im Server.
     * Nur diese drei Werte ändern sich.
     */
    public Draft withCache(Path file, Draft base, AppSettings.Cache cache) {
        String text = nested(base.text(), "cache", "max-age", indent -> indent + "max-age: " + duration(cache.maxAge())
                + "\n");
        text = nested(text, "cache", "version-check", indent -> indent + "version-check: " + cache.versionCheck()
                + "\n");
        text = nested(text, "sync", "withheld-retention", indent -> indent + "withheld-retention: "
                + duration(cache.withheldRetention()) + "\n");
        AppSettings settings = loader.read(file, text);
        if (!settings.cache().equals(cache)) {
            throw new IllegalArgumentException("Die Cache-Einstellungen ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester.cache und space-harvester.sync von Hand "
                    + "bearbeiten." + (settings.problems().isEmpty() ? "" : " " + String.join(" ", settings.problems())));
        }
        return new Draft(text, settings);
    }

    /**
     * Setzt die Regeln einer Quelle unter {@code space-harvester.sync.sources.<Quelle>}; {@link SourceSyncOverride#NONE}
     * entfernt ihren Eintrag, dann gilt für sie alles global. Die Regeln der anderen Quellen bleiben.
     *
     * @throws IllegalArgumentException wenn die Datei danach etwas anderes liest
     */
    public Draft withSyncRule(Path file, Draft base, String sourceId, SourceSyncOverride rule) {
        AppSettings before = base.settings() != null ? base.settings() : loader.read(file, base.text());
        java.util.Map<String, SourceSyncOverride> rules = new java.util.TreeMap<>(before.syncRules());
        if (rule == null || rule.equals(SourceSyncOverride.NONE)) {
            rules.remove(sourceId);
        } else {
            rules.put(sourceId, rule);
        }
        String text = nested(base.text(), "sync", "sources", indent -> syncRulesYaml(indent, rules));
        AppSettings settings = loader.read(file, text);
        if (!settings.syncRules().equals(rules)) {
            throw new IllegalArgumentException("Die Regeln der Quelle ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester.sync.sources von Hand bearbeiten."
                    + (settings.problems().isEmpty() ? "" : " " + String.join(" ", settings.problems())));
        }
        return new Draft(text, settings);
    }

    private static String syncRulesYaml(String indent, java.util.Map<String, SourceSyncOverride> rules) {
        if (rules.isEmpty()) {
            return "";
        }
        StringBuilder yaml = new StringBuilder(indent).append("sources:\n");
        String inner = indent + "  ";
        String field = inner + "  ";
        rules.forEach((id, rule) -> {
            yaml.append(inner).append('"').append(id.replace("\\", "\\\\").replace("\"", "\\\"")).append("\":\n");
            line(yaml, field, SourceSyncOverride.CHANGE_SEARCH_INTERVAL, rule.changeSearchInterval());
            line(yaml, field, SourceSyncOverride.INVENTORY_INTERVAL, rule.inventoryInterval());
            line(yaml, field, SourceSyncOverride.EVENTS, rule.events());
            line(yaml, field, SourceSyncOverride.CATCH_UP_ON_START, rule.catchUpOnStart());
            line(yaml, field, SourceSyncOverride.BEFORE_SEARCH, rule.beforeSearch());
            line(yaml, field, SourceSyncOverride.MODE, rule.mode() == null ? null : rule.mode().key());
            line(yaml, field, SourceSyncOverride.REFRESH_STRATEGY,
                    rule.refreshStrategy() == null ? null : rule.refreshStrategy().key());
            line(yaml, field, SourceSyncOverride.MAX_AGE, rule.maxAge());
            line(yaml, field, SourceSyncOverride.WITHHELD_RETENTION, rule.withheldRetention());
        });
        return yaml.toString();
    }

    private static void line(StringBuilder yaml, String indent, String key, Object value) {
        if (value != null) {
            yaml.append(indent).append(key).append(": ")
                    .append(value instanceof java.time.Duration d ? duration(d) : value).append('\n');
        }
    }

    /** Eine Dauer so kurz wie möglich: 30d, 6h, 15m, 0s. */
    public static String duration(java.time.Duration duration) {
        long seconds = duration.toSeconds();
        if (seconds != 0 && seconds % 86_400 == 0) {
            return seconds / 86_400 + "d";
        }
        if (seconds != 0 && seconds % 3_600 == 0) {
            return seconds / 3_600 + "h";
        }
        if (seconds != 0 && seconds % 60 == 0) {
            return seconds / 60 + "m";
        }
        return seconds + "s";
    }

    /**
     * Der Entwurf {@code base} mit neuen Einstellungen des KI-Dienstes unter {@code space-harvester.ai}; ein leerer
     * Entwurf entfernt den Block. Nur dieser Block ändert sich; Kommentare und alles andere bleiben.
     */
    public Draft withAi(Path file, Draft base, AiDraft ai) {
        List<String> problems = ai.problems();
        if (!problems.isEmpty()) {
            throw new IllegalArgumentException(String.join(" ", problems));
        }
        String text = ai(base.text(), ai);
        AppSettings settings = loader.read(file, text);
        if (!AiDraft.of(settings.ai()).equals(ai)) {
            throw new IllegalArgumentException("Die KI-Einstellungen ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester.ai von Hand bearbeiten."
                    + (settings.problems().isEmpty() ? "" : " " + String.join(" ", settings.problems())));
        }
        return new Draft(text, settings);
    }

    /** Ersetzt, ergänzt oder entfernt {@code space-harvester.ai}. */
    static String ai(String text, AiDraft ai) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
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
            if (!ai.empty()) {
                lines.add("");
                lines.add("space-harvester:");
                add(lines, lines.size(), ai.yaml("  "));
            }
            return String.join(newline, lines) + newline;
        }
        int rootEnd = blockEnd(lines, root, 0);
        int childIndent = childIndent(lines, root, rootEnd, 2);
        int block = child(lines, root, rootEnd, childIndent, "ai");
        if (block >= 0) {
            lines.subList(block, blockEnd(lines, block, childIndent)).clear();
        }
        if (!ai.empty()) {
            add(lines, block >= 0 ? block : root + 1, ai.yaml(" ".repeat(childIndent)));
        }
        return String.join(newline, lines) + newline;
    }

    /**
     * Setzt {@code space-harvester-app.output-directory}; {@code null} entfernt den Schlüssel, dann gilt Downloads.
     *
     * @throws IllegalArgumentException wenn die Datei danach etwas anderes liest
     */
    public Draft withOutputDirectory(Path file, Draft base, Path directory) {
        String text = outputDirectory(base.text(), directory == null ? null : directory.toAbsolutePath().toString());
        AppSettings settings = loader.read(file, text);
        Path expected = directory == null ? AppSettings.defaultOutputDirectory() : directory.toAbsolutePath();
        if (!expected.equals(settings.outputDirectory())) {
            throw new IllegalArgumentException("Der Ausgabeordner ließ sich nicht sicher in die Konfigurationsdatei "
                    + "schreiben; bitte space-harvester-app.output-directory von Hand bearbeiten.");
        }
        return new Draft(text, settings);
    }

    /**
     * Setzt {@code space-harvester-app.disabled-sources}; leer entfernt den Schlüssel, dann sind alle Quellen an.
     *
     * @throws IllegalArgumentException wenn die Datei danach etwas anderes liest
     */
    public Draft withDisabledSources(Path file, Draft base, Set<String> disabled) {
        String value = disabled.isEmpty() ? null : "disabled-sources: [" + disabled.stream().sorted()
                .map(id -> "\"" + id.replace("\\", "\\\\").replace("\"", "\\\"") + "\"")
                .collect(java.util.stream.Collectors.joining(", ")) + "]";
        String text = appValue(base.text(), "disabledsources", value);
        AppSettings settings = loader.read(file, text);
        if (!settings.disabledSources().equals(Set.copyOf(disabled))) {
            throw new IllegalArgumentException("Die abgeschalteten Quellen ließen sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester-app.disabled-sources von Hand bearbeiten.");
        }
        return new Draft(text, settings);
    }

    /**
     * Setzt {@code space-harvester-app.setup-completed: true}: Der Einrichtungsassistent öffnet sich beim Start nicht
     * mehr, bleibt aber unter Einstellungen erreichbar.
     *
     * @throws IllegalArgumentException wenn die Datei danach etwas anderes liest
     */
    public Draft withSetupCompleted(Path file, Draft base) {
        String text = appValue(base.text(), "setupcompleted", "setup-completed: true");
        AppSettings settings = loader.read(file, text);
        if (!settings.setupCompleted()) {
            throw new IllegalArgumentException("Der Abschluss der Einrichtung ließ sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester-app.setup-completed von Hand setzen.");
        }
        return new Draft(text, settings);
    }

    /**
     * Setzt Bezugsart und KeePass-Eintrag des KI-Keys ({@code space-harvester-app.ai-access},
     * {@code .ai-keepass-entry}); das Geheimnis selbst steht nie in der Datei.
     *
     * @throws IllegalArgumentException wenn die Datei danach etwas anderes liest
     */
    public Draft withAiAccess(Path file, Draft base, AiAccess access) {
        String text = appValue(base.text(), "aiaccess", access.mode() == null ? null
                : "ai-access: " + access.mode().key());
        text = appValue(text, "aikeepassentry", access.entry() == null ? null
                : "ai-keepass-entry: \"" + access.entry().replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
        AppSettings settings = loader.read(file, text);
        if (!settings.aiAccess().equals(access)) {
            throw new IllegalArgumentException("Der Bezug des API-Keys ließ sich nicht sicher in die "
                    + "Konfigurationsdatei schreiben; bitte space-harvester-app.ai-access von Hand setzen.");
        }
        return new Draft(text, settings);
    }

    /** Ersetzt, ergänzt oder entfernt {@code space-harvester-app.output-directory}. */
    static String outputDirectory(String text, String directory) {
        return appValue(text, "outputdirectory", directory == null ? null
                : "output-directory: \"" + directory.replace("\\", "\\\\").replace("\"", "\\\"") + "\"");
    }

    /**
     * Ersetzt, ergänzt oder entfernt einen einzeiligen Schlüssel unter {@code space-harvester-app}.
     *
     * @param key   normalisierter Schlüssel ({@link AppSettingsLoader#normalize})
     * @param value ganze Zeile ohne Einrückung, {@code null} entfernt den Schlüssel
     */
    private static String appValue(String text, String key, String value) {
        List<String> lines = new ArrayList<>(List.of(text.split("\r?\n", -1)));
        String newline = text.contains("\r\n") ? "\r\n" : "\n";
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        int root = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isKey(ROOT, lines.get(i), 1, "spaceharvesterapp")) {
                root = i;
                break;
            }
            if (isKey(ROOT_INLINE, lines.get(i), 1, "spaceharvesterapp")) {
                throw unsupported("space-harvester-app steht in einer Zeile");
            }
        }
        if (root < 0) {
            if (value != null) {
                // Oben in der Datei, wie im Beispiel; der Rest bleibt unverändert.
                lines.addAll(0, List.of("space-harvester-app:", "  " + value, ""));
            }
            return String.join(newline, lines) + newline;
        }
        int rootEnd = blockEnd(lines, root, 0);
        int childIndent = childIndent(lines, root, rootEnd, 2);
        int line = -1;
        for (int i = root + 1; i < rootEnd && line < 0; i++) {
            Matcher found = SCALAR_KEY.matcher(lines.get(i));
            if (!blankOrComment(lines.get(i)) && indent(lines.get(i)) == childIndent && found.lookingAt()
                    && AppSettingsLoader.normalize(found.group(1)).equals(key)) {
                line = i;
            }
        }
        if (line >= 0) {
            lines.subList(line, blockEnd(lines, line, childIndent)).clear();
        }
        if (value != null) {
            lines.add(line >= 0 ? line : root + 1, " ".repeat(childIndent) + value);
        }
        return String.join(newline, lines) + newline;
    }

    /** Hinter der letzten Inhaltszeile des Blocks ab {@code start}; Kommentare danach bleiben außerhalb. */
    private static int blockEnd(List<String> lines, int start, int indent) {
        int last = start;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (blankOrComment(line)) {
                continue;
            }
            if (indent(line) <= indent) {
                break;
            }
            last = i;
        }
        return last + 1;
    }

    private static int childIndent(List<String> lines, int start, int end, int fallback) {
        for (int i = start + 1; i < end; i++) {
            if (!blankOrComment(lines.get(i))) {
                return indent(lines.get(i));
            }
        }
        return fallback;
    }

    /** Zeile des Schlüssels {@code key} mit Einrückung {@code indent} im Block; -1, wenn er fehlt. */
    private static int child(List<String> lines, int start, int end, int indent, String key) {
        for (int i = start + 1; i < end; i++) {
            String line = lines.get(i);
            if (blankOrComment(line) || indent(line) != indent) {
                continue;
            }
            Matcher matcher = SOURCES.matcher(line);
            if (matcher.matches() && AppSettingsLoader.normalize(matcher.group(2)).equals(key)) {
                if (matcher.group(3) != null) {
                    lines.set(i, " ".repeat(indent) + matcher.group(2) + ":");
                }
                return i;
            }
            if (isKey(SOURCES_INLINE, line, 1, key)) {
                throw unsupported(key + " steht in einer Zeile");
            }
        }
        return -1;
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
