package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.application.sync.Exclusions;
import com.aresstack.spaceharvester.source.LocalFilesSourceAdapter;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import javax.swing.SwingUtilities;

/**
 * Hinweis vor dem Abgleich eines sehr großen lokalen Ordners (Angelo 27./28.11.2026: ein Ordner mit 40 GB lief im
 * Schneckentempo; der Nutzer soll erfahren, dass das Indexieren gefährlich lange dauern kann, verboten oder blockiert
 * wird nichts). Vor jedem Abgleich einer Ordner-Quelle, den ein Klick startet (Indexieren, Abgleich, Initialisierung,
 * Nachholen), zählt die App die Größe des Ordners. Liegt sie über der einstellbaren Hinweisgrenze
 * ({@link AppSettings.Storage#largeFolderBytes}, {@value AppSettingsLoader#LARGE_FOLDER_KEY}, {@code 0} nie), fragt sie
 * „wirklich synchronisieren?“ mit Ja/Nein und nennt die größten Unterordner; Ja startet den Abgleich unverändert, Nein
 * startet ihn nicht. Ausschließen bleibt Sache des Nutzers (Rechtsklick › Ausschließen, {@link Exclusions}).
 *
 * <p>Genannt werden die engsten Unterordner über der Grenze (keiner ihrer Unterordner liegt selbst darüber). Versteckte
 * Ordner, Punkt-Ordner, Muster der Quelle und schon ausgeschlossene Ordner zählen nicht mit, weil der Abgleich sie
 * ohnehin nicht liest.
 */
final class LargeFolders {

    /** So viele große Unterordner nennt der Hinweis; Darstellung, keine Grenze. */
    private static final int SHOWN_FOLDERS = 5;

    /** Ordner, zu denen in dieser Sitzung schon „Ja“ gewählt wurde: nicht bei jedem Abgleich fragen. */
    private static final Set<String> ACCEPTED = ConcurrentHashMap.newKeySet();

    private LargeFolders() {
    }

    /** Ein Unterordner über der Grenze, relativ zur Wurzel der Quelle mit {@code /}. */
    record Folder(String path, long bytes) {
    }

    /**
     * @param totalBytes Größe des geprüften Ordners ohne das, was der Abgleich nicht liest
     * @param large      die engsten Unterordner über der Grenze, größte zuerst
     */
    record Report(long totalBytes, List<Folder> large) {
        Report {
            large = List.copyOf(large);
        }
    }

    /**
     * Fragt vor dem Abgleich nach, wenn nötig; läuft abseits des EDT (zählt Dateien), die Rückfrage selbst auf dem EDT.
     * Quellen anderer Art, eine Grenze von {@code 0} und Ordner ohne Unterordner über der Grenze gehen ohne Frage durch.
     *
     * @param pageId Unterordner, der indexiert wird, oder {@code null} für die ganze Quelle
     * @return {@code false}, wenn der Abgleich nicht starten soll (Abbrechen)
     */
    static boolean confirm(AppContext context, String sourceId, String pageId) {
        if (SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("Ordnergröße nicht auf dem EDT zählen");
        }
        AppSettings settings = context.settings();
        if (settings == null || context.runtime() == null) {
            return true;
        }
        long limit = settings.storage().largeFolderBytes();
        Optional<Path> root = directory(settings, sourceId);
        if (limit <= 0 || root.isEmpty()) {
            return true;
        }
        List<Exclusions.Exclusion> excluded = context.runtime().workspace().exclusions(sourceId);
        if (excluded.stream().anyMatch(exclusion -> exclusion.pageId() == null)) {
            return true;
        }
        Set<String> excludedIds = new java.util.HashSet<>();
        excluded.forEach(exclusion -> excludedIds.add(exclusion.pageId()));
        Path start = pageId == null ? root.get() : folder(root.get(), pageId).orElse(null);
        if (start == null || ACCEPTED.contains(sourceId + "|" + start)) {
            return true;
        }
        SourceSettingsConfig config = config(settings, sourceId).orElseThrow();
        // Dieselben Muster wie die Quelle (LocalFilesSourceAdapter): Einschluss nur, wenn der Dateifilter an ist.
        String include = com.aresstack.spaceharvester.application.sources.LocalFileFilter.enabled(
                config.options().get("include"), config.options().get("includeEnabled"))
                ? config.options().get("include") : null;
        com.aresstack.enterpriseai.source.api.files.FilePatterns patterns =
                new com.aresstack.enterpriseai.source.api.files.FilePatterns(
                        com.aresstack.enterpriseai.source.api.files.FilePatterns.split(include),
                        com.aresstack.enterpriseai.source.api.files.FilePatterns.split(
                                config.options().get("exclude")));
        Report report;
        try {
            report = scan(root.get(), start, limit, relative -> !patterns.entersFolder(relative)
                    || excludedIds.contains(LocalFilesSourceAdapter.folderPageId(Path.of(relative))),
                    patterns::acceptsFile);
        } catch (IOException unreadable) {
            // Der Abgleich meldet einen unlesbaren Ordner selbst, mit Grund; die Rückfrage hält ihn nicht auf.
            return true;
        }
        if (report.totalBytes() <= limit) {
            return true;
        }
        boolean go = ask(config, start, report);
        if (go) {
            ACCEPTED.add(sourceId + "|" + start);
        }
        return go;
    }

    /** Die Rückfrage „wirklich“ mit Ja/Nein auf dem EDT; nur ein Hinweis, Ja startet den Abgleich wie immer. */
    private static boolean ask(SourceSettingsConfig config, Path start, Report report) {
        String name = config.title() != null && !config.title().isBlank() ? config.title()
                : String.valueOf(start.getFileName());
        StringBuilder text = new StringBuilder("„" + name + "“ ist " + Texts.bytes(report.totalBytes())
                + " groß. Das Indexieren kann sehr lange dauern und den Rechner stark belasten.");
        if (!report.large().isEmpty()) {
            text.append("\n\nGroße Unterordner:");
            report.large().stream().limit(SHOWN_FOLDERS).forEach(folder -> text.append("\n• ").append(folder.path())
                    .append(" (").append(Texts.bytes(folder.bytes())).append(")"));
            if (report.large().size() > SHOWN_FOLDERS) {
                text.append("\n• … und ").append(report.large().size() - SHOWN_FOLDERS).append(" weitere");
            }
        }
        text.append("\n\nWer einzelne Unterordner weglassen will, schließt sie im Baum per Rechtsklick › Ausschließen "
                + "aus. Ab welcher Größe dieser Hinweis kommt, steht unter Einstellungen › Allgemein › Ablage.\n\n"
                + "„" + name + "“ wirklich synchronisieren?");
        boolean[] result = {false};
        try {
            SwingUtilities.invokeAndWait(() -> {
                Component parent = KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
                result[0] = Dialogs.confirm(parent, "Großer Ordner", text.toString());
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (InvocationTargetException e) {
            throw e.getCause() instanceof RuntimeException failure ? failure : new IllegalStateException(e.getCause());
        }
        return result[0];
    }

    /**
     * Zählt die Größe von {@code start} und seinen Unterordnern, ohne Verknüpfungen zu folgen.
     *
     * @param root  Wurzel der Quelle; die Pfade im Ergebnis sind relativ dazu
     * @param start der geprüfte Ordner, {@code root} oder ein Unterordner davon
     * @param limit Grenze in Bytes, größer als 0
     * @param skip  relative Ordnerpfade, die der Abgleich nicht liest (Muster, Ausschlüsse)
     */
    static Report scan(Path root, Path start, long limit, Predicate<String> skip) throws IOException {
        return scan(root, start, limit, skip, relative -> true);
    }

    /**
     * Wie {@link #scan(Path, Path, long, Predicate)}; es zählen nur Dateien, die {@code counted} annimmt (Dateimuster
     * der Quelle), denn nur sie liest der Abgleich.
     */
    static Report scan(Path root, Path start, long limit, Predicate<String> skip, Predicate<String> counted)
            throws IOException {
        Objects.requireNonNull(skip, "skip");
        Objects.requireNonNull(counted, "counted");
        if (limit <= 0) {
            throw new IllegalArgumentException("limit muss größer als 0 sein");
        }
        Path base = root.toAbsolutePath().normalize();
        Path first = start.toAbsolutePath().normalize();
        if (!first.startsWith(base)) {
            throw new IllegalArgumentException("Der Ordner liegt nicht in der Quelle");
        }
        Deque<long[]> sizes = new ArrayDeque<>();
        List<Folder> large = new ArrayList<>();
        long[] total = {0};
        Files.walkFileTree(first, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attributes) throws IOException {
                if (!dir.equals(first)) {
                    String relative = relative(base, dir);
                    if (dir.getFileName().toString().startsWith(".") || hidden(dir) || skip.test(relative)) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                }
                // [0] Größe, [1] 1, wenn ein Unterordner schon über der Grenze liegt
                sizes.push(new long[] {0, 0});
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                if (attributes.isRegularFile() && !sizes.isEmpty()
                        && !file.getFileName().toString().startsWith(".") && counted.test(relative(base, file))) {
                    sizes.peek()[0] += attributes.size();
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException failure) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException failure) {
                long[] own = sizes.pop();
                boolean over = own[0] > limit;
                if (dir.equals(first)) {
                    total[0] = own[0];
                    return FileVisitResult.CONTINUE;
                }
                if (over && own[1] == 0) {
                    large.add(new Folder(relative(base, dir), own[0]));
                }
                long[] parent = sizes.peek();
                parent[0] += own[0];
                if (over) {
                    parent[1] = 1;
                }
                return FileVisitResult.CONTINUE;
            }
        });
        large.sort(Comparator.comparingLong(Folder::bytes).reversed().thenComparing(Folder::path));
        return new Report(total[0], large);
    }

    private static boolean hidden(Path dir) {
        try {
            return Files.isHidden(dir) || Files.isSymbolicLink(dir)
                    || !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS);
        } catch (IOException unknown) {
            return false;
        }
    }

    private static String relative(Path base, Path path) {
        return base.relativize(path).toString().replace('\\', '/');
    }

    private static Optional<SourceSettingsConfig> config(AppSettings settings, String sourceId) {
        return settings.sources().stream().map(AppSettings.SourceEntry::config)
                .filter(config -> config.id().equals(sourceId)
                        && LocalFilesSourceAdapter.TYPE.equals(config.type()))
                .findFirst();
    }

    private static Optional<Path> directory(AppSettings settings, String sourceId) {
        return config(settings, sourceId).map(config -> config.options().get("directory"))
                .filter(directory -> !directory.isBlank())
                .map(directory -> Path.of(directory).toAbsolutePath().normalize())
                .filter(Files::isDirectory);
    }

    /** Der Unterordner zu einer Seiten-ID der Ordner-Quelle (relativer Pfad als Base64url). */
    private static Optional<Path> folder(Path root, String pageId) {
        try {
            String relative = new String(java.util.Base64.getUrlDecoder().decode(pageId),
                    java.nio.charset.StandardCharsets.UTF_8);
            Path folder = root.resolve(relative).normalize();
            return folder.startsWith(root) && Files.isDirectory(folder) ? Optional.of(folder) : Optional.empty();
        } catch (IllegalArgumentException notAFolder) {
            return Optional.empty();
        }
    }
}
