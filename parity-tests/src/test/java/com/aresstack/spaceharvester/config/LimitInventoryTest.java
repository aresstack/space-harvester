package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Architekturtest zu den Grenzen (Angelo 26.11.2026, ADR 0036 Punkt 12, F36-4): Jede Konstante im Produktionscode, die
 * nach einer Ressourcengrenze heißt ({@code MAX_…}, {@code …_TIMEOUT}, {@code …_BATCH}, {@code …_CHARS} usw.), steht
 * mit ihrem Wert im Verzeichnis von {@code docs/grenzen.md}; eine Zeile ohne Konstante im Code fällt ebenfalls auf.
 * So kommt keine neue Grenze undokumentiert dazu, und eine geänderte Zahl ändert die Dokumentation mit. Der Test
 * verbietet keine Zahl: Ob eine Grenze bleibt, einstellbar wird oder entfällt, steht in der Spalte „Kategorie“.
 *
 * <p>Nicht erfasst werden Zahlen ohne eigene Konstante und die unveränderten Kopien aus dem Enterprise AI Client
 * ({@code enterprise-ai-*}, {@code comic-controls}); beide nennt {@code docs/grenzen.md} gesondert.
 */
class LimitInventoryTest {

    /** Module mit eigenem Code, aus {@code parity-tests/build.gradle} (dort auch Eingaben des Tests). */
    static List<String> modules() {
        String modules = System.getProperty("limits.modules", "");
        assertThat(modules).as("Systemeigenschaft limits.modules aus parity-tests/build.gradle").isNotBlank();
        return List.of(modules.split(","));
    }

    /** Zahlen- und Dauerkonstanten. */
    static final Pattern CONSTANT = Pattern.compile(
            "static\\s+final\\s+(?:int|long|double|float|Duration)\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*([^;]+);");

    /** Namen, die nach einer Grenze klingen. */
    static final Pattern LIMIT_NAME = Pattern.compile("(MAX|LIMIT|CAPACITY|TIMEOUT|BUDGET|DEPTH|BATCH|PAGE_SIZE"
            + "|RETENTION|TTL|PARALLEL|THREADS|QUEUE|ENTRIES|REMEMBER|ROUNDS|ATTEMPTS|_CHARS|_BYTES|_WAIT|CACHE)");

    /** Eine Zeile des Verzeichnisses: {@code | `Klasse.KONSTANTE` | `Wert` | …}. */
    static final Pattern ROW = Pattern.compile("^\\|\\s*`([A-Za-z0-9_$]+\\.[A-Z][A-Z0-9_]*)`\\s*\\|\\s*`([^`]*)`\\s*\\|");

    @Test
    void everyLimitConstantIsListedWithItsValue() throws IOException {
        Path root = root();
        Map<String, String> code = constants(root);
        Map<String, String> documented = documented(root.resolve("docs/grenzen.md"));

        List<String> missing = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        code.forEach((name, value) -> {
            String listed = documented.get(name);
            if (listed == null) {
                missing.add("| `" + name + "` | `" + value + "` | … |");
            } else if (!listed.equals(value)) {
                changed.add(name + ": Code `" + value + "`, Verzeichnis `" + listed + "`");
            }
        });
        List<String> stale = documented.keySet().stream().filter(name -> !code.containsKey(name)).toList();

        assertThat(missing).as("Neue Grenzen ohne Zeile in docs/grenzen.md (Kategorie und Folge ergänzen)").isEmpty();
        assertThat(changed).as("Geänderte Werte, docs/grenzen.md nachziehen").isEmpty();
        assertThat(stale).as("Zeilen in docs/grenzen.md ohne Konstante im Code (entfernen oder umbenennen)").isEmpty();
    }

    /** {@code Klasse.KONSTANTE} → Wert, wie im Code geschrieben (Leerraum zusammengefasst). */
    static Map<String, String> constants(Path root) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String module : modules()) {
            Path sources = root.resolve(module).resolve("src/main/java");
            if (!Files.isDirectory(sources)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(sources)) {
                files.filter(file -> file.toString().endsWith(".java")).forEach(file -> {
                    String type = file.getFileName().toString().replace(".java", "");
                    Matcher m = CONSTANT.matcher(read(file));
                    while (m.find()) {
                        if (LIMIT_NAME.matcher(m.group(1)).find()) {
                            result.put(type + "." + m.group(1), m.group(2).strip().replaceAll("\\s+", " "));
                        }
                    }
                });
            }
        }
        return result;
    }

    static Map<String, String> documented(Path file) throws IOException {
        Map<String, String> result = new TreeMap<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            Matcher m = ROW.matcher(line);
            if (m.find()) {
                result.put(m.group(1), m.group(2));
            }
        }
        return result;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path root() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null && !Files.exists(dir.resolve("settings.gradle"))) {
            dir = dir.getParent();
        }
        assertThat(dir).as("Wurzel des Repositorys").isNotNull();
        return dir;
    }
}
