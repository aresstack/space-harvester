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
 * Felder URL-kodiert. Eine unlesbare Datei fehlt in {@link #all()}; dann holt der nächste Abgleich nach.
 */
public final class FileEnrichmentQueue implements EnrichmentQueueStore {

    static final String SUFFIX = ".queue";
    private static final String VERSION = "v1";
    private static final Pattern GROUP = Pattern.compile("[A-Za-z0-9-]{1,100}");
    private static final System.Logger LOG = System.getLogger(FileEnrichmentQueue.class.getName());

    private final Path directory;

    /** @param directory Verzeichnis der Dateien; wird bei Bedarf angelegt */
    public FileEnrichmentQueue(Path directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    @Override
    public synchronized void save(Saved saved) {
        Objects.requireNonNull(saved, "saved");
        Path file = file(saved.groupId());
        Path temp = directory.resolve(saved.groupId() + "." + UUID.randomUUID() + ".tmp");
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
        if (!Files.isDirectory(directory)) {
            return saved;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*" + SUFFIX)) {
            for (Path file : files) {
                String name = file.getFileName().toString();
                String groupId = name.substring(0, name.length() - SUFFIX.length());
                try {
                    saved.add(read(groupId, Files.readAllLines(file, StandardCharsets.UTF_8)));
                } catch (IOException | RuntimeException e) {
                    LOG.log(System.Logger.Level.WARNING, "Warteschlange der KI-Anreicherung {0} unlesbar ({1})",
                            groupId, e.getClass().getName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return saved;
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

    @Override
    public synchronized void removeSource(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        for (Saved saved : all()) {
            if (saved.sourceId().equals(sourceId)) {
                delete(saved.groupId());
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
