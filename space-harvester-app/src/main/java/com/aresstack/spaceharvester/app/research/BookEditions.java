package com.aresstack.spaceharvester.app.research;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Die Auflagen des Buchs eines Ordners (Architekt nach Angelo 27.11.2026: das Buch ist die erweiterte KI-Zusammenfassung
 * eines Ordnerknotens): je fertigem Lauf eine Auflage mit Nummer, Zeit, Buchtext, Missionsbericht und dem Stand der
 * Quellen (je Seite Kennung und Version), neben den Notizen im Datenordner der App
 * ({@code app/books/<Ordner>.editions/auflage-0001.json}). Eine Auflage wird nie überschrieben. Die Dateien tragen
 * Inhalte; sie gehen nie in ein Protokoll.
 */
public final class BookEditions {

    private static final System.Logger LOG = System.getLogger(BookEditions.class.getName());
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();
    private static final Pattern NAME = Pattern.compile("auflage-(\\d+)\\.json");

    /** Wer von einer neuen Auflage hören will (Beobachter statt Abfragen); bekommt die Ordner-ID. */
    private static final List<java.util.function.Consumer<String>> LISTENERS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    private BookEditions() {
    }

    /** Meldet jede neue Auflage mit ihrer Ordner-ID, bis {@link AutoCloseable#close()}. */
    public static AutoCloseable onAdded(java.util.function.Consumer<String> listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.add(listener);
        return () -> LISTENERS.remove(listener);
    }

    /** Eine Seite, aus der das Buch geschrieben ist; {@code version} {@code null}, wenn die Ablage keine kennt. */
    public record Source(String sourceId, String pageId, String version) {
    }

    /** Eine Auflage. */
    public record Edition(int number, Instant createdAt, String book, String report, List<Source> sources) {

        public Edition {
            book = book == null ? "" : book;
            report = report == null ? "" : report;
            sources = sources == null ? List.of() : List.copyOf(sources);
        }
    }

    /** Der Ordner der Auflagen neben den Notizen. */
    static Path directory(Path dataDirectory, String folderId) {
        Path notes = BookNotes.file(dataDirectory, folderId);
        String name = notes.getFileName().toString();
        return notes.resolveSibling(name.substring(0, name.length() - ".json".length()) + ".editions");
    }

    /**
     * Legt eine neue Auflage an, mit der nächsten freien Nummer; eine vorhandene wird nie überschrieben, auch nicht von
     * einem gleichzeitigen Lauf ({@code CREATE_NEW}).
     */
    public static Edition add(Path dataDirectory, String folderId, Instant createdAt, String book, String report,
                              List<Source> sources) throws IOException {
        Path directory = directory(dataDirectory, folderId);
        Files.createDirectories(directory);
        for (int number = highest(directory) + 1; ; number++) {
            Edition edition = new Edition(number, Objects.requireNonNull(createdAt, "createdAt"), book, report,
                    sources);
            Path target = directory.resolve(String.format("auflage-%04d.json", number));
            try {
                Files.writeString(target, JSON.writeValueAsString(edition), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                for (java.util.function.Consumer<String> listener : LISTENERS) {
                    try {
                        listener.accept(folderId);
                    } catch (RuntimeException ignored) {
                        // Ein Beobachter hält das Ablegen nicht auf.
                    }
                }
                return edition;
            } catch (FileAlreadyExistsException taken) {
                // nächste Nummer
            }
        }
    }

    /** Die neueste lesbare Auflage; leer ohne Buch. */
    public static Optional<Edition> latest(Path dataDirectory, String folderId) {
        if (dataDirectory == null || folderId == null) {
            return Optional.empty();
        }
        Path directory = directory(dataDirectory, folderId);
        for (Path file : files(directory).stream().sorted(Comparator.comparingInt(BookEditions::number).reversed())
                .toList()) {
            try {
                return Optional.of(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), Edition.class));
            } catch (IOException | RuntimeException unreadable) {
                LOG.log(System.Logger.Level.WARNING, "Buchauflage nicht lesbar ({0})",
                        unreadable.getClass().getSimpleName());
            }
        }
        return Optional.empty();
    }

    private static int highest(Path directory) {
        return files(directory).stream().mapToInt(BookEditions::number).max().orElse(0);
    }

    private static List<Path> files(Path directory) {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> listed = Files.list(directory)) {
            return new ArrayList<>(listed.filter(file -> NAME.matcher(file.getFileName().toString()).matches())
                    .toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    private static int number(Path file) {
        Matcher matcher = NAME.matcher(file.getFileName().toString());
        return matcher.matches() ? Integer.parseInt(matcher.group(1)) : 0;
    }
}
