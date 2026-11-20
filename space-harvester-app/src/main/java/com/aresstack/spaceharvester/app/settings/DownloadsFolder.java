package com.aresstack.spaceharvester.app.settings;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Der Downloads-Ordner des Benutzers, Vorgabe für jeden Ausgabeordner (Angelo 20.11.2026). Unter Windows der Known
 * Folder „Downloads“ ({@code FOLDERID_Downloads}), wie ihn der Explorer zeigt, auch wenn er umgeleitet ist (etwa auf
 * {@code D:\Downloads} oder OneDrive); gelesen aus {@code HKCU\…\Explorer\User Shell Folders}, Umgebungsvariablen
 * aufgelöst. Unter Linux {@code XDG_DOWNLOAD_DIR} aus {@code ~/.config/user-dirs.dirs}. Sonst und als Rückfall
 * {@code ~/Downloads}. Einmal je Lauf ermittelt.
 */
public final class DownloadsFolder {

    /** {@code FOLDERID_Downloads}. */
    static final String KNOWN_FOLDER_ID = "{374DE290-123F-4565-9164-39C4925E467B}";
    private static final String USER_SHELL_FOLDERS =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\User Shell Folders";
    private static final String SHELL_FOLDERS =
            "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Explorer\\Shell Folders";
    private static final Pattern REG_VALUE = Pattern.compile("^\\s*\\S+\\s+REG_(?:EXPAND_)?SZ\\s+(.+?)\\s*$");
    private static final Pattern WINDOWS_VARIABLE = Pattern.compile("%([^%]+)%");
    private static final Pattern XDG_LINE = Pattern.compile("^\\s*XDG_DOWNLOAD_DIR\\s*=\\s*\"?(.*?)\"?\\s*$");

    private DownloadsFolder() {
    }

    private static final class Holder {
        static final Path LOCATED = locate(System.getProperty("os.name", ""), System.getProperty("user.home"),
                System.getenv(), DownloadsFolder::regQuery);
    }

    /** Der Downloads-Ordner dieses Benutzers; nie {@code null}, muss aber nicht existieren. */
    public static Path current() {
        return Holder.LOCATED;
    }

    /**
     * @param registry liest einen Wert {@code (Schlüssel, Name)} aus der Registry, leer ohne Treffer
     */
    static Path locate(String osName, String userHome, Map<String, String> environment,
                       java.util.function.BiFunction<String, String, Optional<String>> registry) {
        Path fallback = Path.of(userHome, "Downloads");
        String os = osName.toLowerCase(Locale.ROOT);
        try {
            if (os.startsWith("windows")) {
                Optional<Path> known = registry.apply(USER_SHELL_FOLDERS, KNOWN_FOLDER_ID)
                        .map(value -> expandWindows(value, environment, userHome))
                        .or(() -> registry.apply(SHELL_FOLDERS, KNOWN_FOLDER_ID))
                        .filter(value -> !value.isBlank() && !value.contains("%"))
                        .map(Path::of);
                return known.orElse(fallback);
            }
            if (os.contains("linux") || os.contains("bsd")) {
                Path config = Optional.ofNullable(environment.get("XDG_CONFIG_HOME")).filter(v -> !v.isBlank())
                        .map(Path::of).orElse(Path.of(userHome, ".config"));
                return xdg(config.resolve("user-dirs.dirs"), userHome).orElse(fallback);
            }
        } catch (RuntimeException e) {
            // Ungültige Pfade oder Registry nicht lesbar: dann ~/Downloads
        }
        return fallback;
    }

    /** Löst {@code %USERPROFILE%} und andere Variablen auf; unbekannte bleiben stehen (dann gilt der Wert nicht). */
    static String expandWindows(String value, Map<String, String> environment, String userHome) {
        Map<String, String> upper = new java.util.HashMap<>();
        environment.forEach((key, val) -> upper.put(key.toUpperCase(Locale.ROOT), val));
        upper.putIfAbsent("USERPROFILE", userHome);
        Matcher matcher = WINDOWS_VARIABLE.matcher(value);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String replacement = upper.get(matcher.group(1).toUpperCase(Locale.ROOT));
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement == null ? matcher.group()
                    : replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    static Optional<Path> xdg(Path file, String userHome) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            for (String line : Files.readAllLines(file)) {
                Matcher matcher = XDG_LINE.matcher(line);
                if (matcher.matches() && !line.strip().startsWith("#")) {
                    String value = matcher.group(1).replace("$HOME", userHome);
                    // $HOME allein heißt laut xdg-user-dirs: Ordner abgeschaltet
                    return value.isBlank() || Path.of(value).equals(Path.of(userHome)) ? Optional.empty()
                            : Optional.of(Path.of(value));
                }
            }
        } catch (IOException | InvalidPathException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    /** Wert aus {@code reg query}; die Ausgabe hat die Zeile {@code    <Name>    REG_EXPAND_SZ    <Wert>}. */
    static Optional<String> parseRegQuery(List<String> lines) {
        for (String line : lines) {
            Matcher matcher = REG_VALUE.matcher(line);
            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    private static Optional<String> regQuery(String key, String name) {
        try {
            Process process = new ProcessBuilder("reg", "query", key, "/v", name).redirectErrorStream(true).start();
            List<String> lines;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                    Charset.defaultCharset()))) {
                lines = reader.lines().toList();
            }
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return Optional.empty();
            }
            return process.exitValue() == 0 ? parseRegQuery(lines) : Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
