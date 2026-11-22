package com.aresstack.spaceharvester.persistence.files;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentQueueStore;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * {@link EnrichmentQueueStore} als eine Datei je Gruppe ({@code <Gruppe>.queue}) in einem eigenen Verzeichnis, atomar
 * ersetzt (temporäre Datei, dann umbenannt). Zeilen: {@code v1}, {@code source <Quelle>}, {@code part <Teil>}, dann je
 * Berechnung {@code W} (wartend) oder {@code F} (gescheitert), erzwungen {@code 1}/{@code 0}, Prozessor und Seite;
 * Felder URL-kodiert. Eine unlesbare Datei lässt sich nie wiederherstellen: {@link #all()} löscht sie, dann holt der
 * nächste Abgleich nach. Temporäre Reste eines abgebrochenen Schreibens entfernt der erste Zugriff (Abnahme #1112).
 */
public final class FileEnrichmentQueue implements EnrichmentQueueStore {

    static final String SUFFIX = ".queue";
    static final String TEMP = ".tmp";
    private static final String VERSION = "v1";
    private static final Pattern GROUP = Pattern.compile("[A-Za-z0-9-]{1,100}");
    private static final System.Logger LOG = System.getLogger(FileEnrichmentQueue.class.getName());

    private final Path directory;
    /** Ob die temporären Reste von vorher entfernt sind. */
    private boolean tidied;

    /** @param directory Verzeichnis der Dateien; wird bei Bedarf angelegt */
    public FileEnrichmentQueue(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public synchronized void save(Saved saved) {
        Objects.requireNonNull(saved, "saved");
        Path file = file(saved.groupId());
        Path temp = directory.resolve(saved.groupId() + "." + UUID.randomUUID() + TEMP);
        tidy();
        try {
            Files.createDirectories(directory);
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                writer.write(VERSION);
                writer.newLine();
                writer.write("source " + encode(saved.sourceId()));
                writer.newLine();
                writer.write("part " + encode(saved.partId()));
                writer.newLine();
                for (Entry entry : saved.work()) {
                    write(writer, "W", entry);
                }
                for (Entry entry : saved.failed()) {
                    write(writer, "F", entry);
                }
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            try {
                Files.deleteIfExists(temp);
            } catch (IOException ignored) {
                // Nur ein Rest; der nächste Stand schreibt neu.
            }
        }
    }

    private static void write(BufferedWriter writer, String kind, Entry entry) throws IOException {
        writer.write(kind + " " + (entry.force() ? "1" : "0") + " " + encode(entry.processorId()) + " "
                + encode(entry.pageId()));
        writer.newLine();
    }

    @Override
    public synchronized void delete(String groupId) {
        try {
            Files.deleteIfExists(file(groupId));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public synchronized List<Saved> all() {
        List<Saved> saved = new ArrayList<>();
        tidy();
        if (!Files.isDirectory(directory)) {
            return saved;
        }
        for (Path file : files("*" + SUFFIX)) {
            String name = file.getFileName().toString();
            String groupId = name.substring(0, name.length() - SUFFIX.length());
            List<String> lines = null;
            try {
                lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                saved.add(read(groupId, lines));
            } catch (IOException | RuntimeException e) {
                // Nie wiederherstellbar; sonst hielte die Datei Quellen- und Seitenschlüssel über jedes Bereinigen.
                LOG.log(System.Logger.Level.WARNING, "Warteschlange der KI-Anreicherung der Quelle {0} unlesbar, "
                        + "verworfen ({1})", lines == null ? "?" : sourceOf(lines).orElse("?"),
                        e.getClass().getName());
                deleteQuietly(file);
            }
        }
        return saved;
    }

    /** Die Quelle aus der Kopfzeile, auch wenn der Rumpf kaputt ist. */
    private static java.util.Optional<String> sourceOf(List<String> lines) {
        if (lines.size() < 2 || !lines.get(1).startsWith("source ")) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(decode(lines.get(1).substring("source ".length())));
        } catch (IllegalArgumentException e) {
            return java.util.Optional.empty();
        }
    }

    /** Entfernt temporäre Reste eines abgebrochenen Schreibens, einmal beim ersten Zugriff. */
    private void tidy() {
        if (tidied) {
            return;
        }
        tidied = true;
        removeTemps();
    }

    private void removeTemps() {
        if (!Files.isDirectory(directory)) {
            return;
        }
        for (Path temp : files("*" + TEMP)) {
            deleteQuietly(temp);
        }
    }

    private List<Path> files(String glob) {
        List<Path> found = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, glob)) {
            files.forEach(found::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return found;
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(System.Logger.Level.WARNING, "Rest der Warteschlange nicht gelöscht ({0})",
                    e.getClass().getName());
        }
    }

    private static Saved read(String groupId, List<String> lines) {
        if (lines.size() < 3 || !VERSION.equals(lines.get(0)) || !lines.get(1).startsWith("source ")
                || !lines.get(2).startsWith("part ")) {
            throw new IllegalArgumentException("unbekanntes Format");
        }
        String sourceId = decode(lines.get(1).substring("source ".length()));
        String partId = decode(lines.get(2).substring("part ".length()));
        List<Entry> work = new ArrayList<>();
        List<Entry> failed = new ArrayList<>();
        for (String line : lines.subList(3, lines.size())) {
            if (line.isBlank()) {
                continue;
            }
            String[] fields = line.split(" ");
            if (fields.length != 4 || !(fields[0].equals("W") || fields[0].equals("F"))) {
                throw new IllegalArgumentException("unbekannte Zeile");
            }
            Entry entry = new Entry(decode(fields[2]), decode(fields[3]), "1".equals(fields[1]));
            (fields[0].equals("W") ? work : failed).add(entry);
        }
        return new Saved(groupId, sourceId, partId, work, failed);
    }

    /**
     * Löscht jede Datei der Quelle, auch mit kaputtem Rumpf, solange die Kopfzeile sie nennt, und alle temporären Reste
     * (geschrieben wird nur unter diesem Monitor, also gehört keiner einem laufenden Schreiben).
     */
    @Override
    public synchronized void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        if (!Files.isDirectory(directory)) {
            return;
        }
        removeTemps();
        for (Path file : files("*" + SUFFIX)) {
            List<String> lines = new ArrayList<>(2);
            // Nur die Kopfzeilen, nachsichtig dekodiert: Ein kaputter Rumpf hält die Quelle nicht fest.
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    Files.newInputStream(file), StandardCharsets.UTF_8))) {
                for (int i = 0; i < 2; i++) {
                    String line = reader.readLine();
                    if (line == null) {
                        break;
                    }
                    lines.add(line);
                }
            } catch (IOException | RuntimeException e) {
                // Nicht zu öffnen: Beim nächsten Start verwirft all() sie.
                continue;
            }
            if (sourceOf(lines).filter(sourceId::equals).isPresent()) {
                try {
                    Files.deleteIfExists(file);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }
    }

    private Path file(String groupId) {
        if (!GROUP.matcher(Objects.requireNonNull(groupId, "groupId")).matches()) {
            throw new IllegalArgumentException("Ungültige Gruppe");
        }
        return directory.resolve(groupId + SUFFIX);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }
}
