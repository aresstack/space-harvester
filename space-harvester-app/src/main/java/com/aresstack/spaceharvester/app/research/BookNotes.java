package com.aresstack.spaceharvester.app.research;

import com.aresstack.knowledge4j.scope.Fence;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Was zum Korb-Ordner gehört und mit ihm bleibt: der Weidezaun (Auftrag, Rand, ausdrücklich dazu, ausgeschlossen), die
 * gewünschte Länge und die Wahl des Lesers: gewählte Begriffe (je Begriff die Themenfelder, leer = das
 * ganze Wort), ausdrücklich abgewählte Begriffe und abgewählte Themenfelder. Die Wahl des Lesers schlägt beim nächsten
 * Mal das Sieb. Eine Datei je Ordner im Datenordner der App ({@code app/books/<Ordner>.json}); geschrieben wird nur,
 * wenn der Benutzer „Korb füllen“ oder „Buch schreiben“ startet.
 *
 * Dazu Idee und Stichpunkte des Lesers und seine Fragen an das Buch (Pflichtfragen neben dem Weidezaun). Die
 * Stichpunkte sind Inhalt des Lesers: sie bleiben in dieser Datei und gehen nie in ein Protokoll.
 *
 * @param targetWords gewünschte Länge in Wörtern; 0 = nach den Belegen
 * @param ideas       Idee und Stichpunkte, aus denen die KI auf Klick die Felder füllt
 * @param questions   Fragen an das Buch, eine je Eintrag
 * @param lengthAmount die Länge, wie der Leser sie eingab (0 = wie {@code targetWords} in Wörtern)
 * @param lengthUnit   ihre Einheit als Name der Auswahl im Dialog; leer = Wörter
 */
public record BookNotes(String mission, String edge, List<String> included, List<String> excluded, int targetWords,
                        Map<String, List<String>> chosen, List<String> unchosen, List<String> excludedFields,
                        String ideas, List<String> questions, int lengthAmount,
                        String lengthUnit) {

    /** Fehlende Zahlen in älteren Dateien (z. B. ohne {@code lengthAmount}) gelten als 0 statt als unlesbar. */
    private static final ObjectMapper JSON = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).build();
    static final String FOLDER = "books";

    public BookNotes {
        mission = mission == null ? "" : mission;
        edge = edge == null ? "" : edge;
        included = included == null ? List.of() : List.copyOf(included);
        excluded = excluded == null ? List.of() : List.copyOf(excluded);
        targetWords = Math.max(0, targetWords);
        chosen = chosen == null ? Map.of() : Map.copyOf(chosen);
        unchosen = unchosen == null ? List.of() : List.copyOf(unchosen);
        excludedFields = excludedFields == null ? List.of() : List.copyOf(excludedFields);
        ideas = ideas == null ? "" : ideas;
        questions = questions == null ? List.of() : List.copyOf(questions);
        lengthAmount = Math.max(0, lengthAmount);
        lengthUnit = lengthUnit == null ? "" : lengthUnit;
    }

    /** Ohne Stichpunkte und Fragen (Notizen von vor dem 27.11.2026). */
    public BookNotes(String mission, String edge, List<String> included, List<String> excluded, int targetWords,
                     Map<String, List<String>> chosen, List<String> unchosen, List<String> excludedFields) {
        this(mission, edge, included, excluded, targetWords, chosen, unchosen, excludedFields, null, null, 0, null);
    }

    /** Ohne Wahl des Lesers, nur der Weidezaun. */
    public static BookNotes of(Fence fence, int targetWords) {
        return new BookNotes(fence.getMission(), fence.getEdge(), fence.getIncluded(), fence.getExcluded(), targetWords,
                null, null, null);
    }

    public Fence fence() {
        return new Fence(mission, edge, included, excluded, "", "");
    }

    /** Derselbe Weidezaun mit einer neuen Wahl des Lesers. */
    public BookNotes withChoice(Map<String, List<String>> chosen, List<String> unchosen, List<String> excludedFields) {
        return new BookNotes(mission, edge, included, excluded, targetWords, chosen, unchosen, excludedFields, ideas,
                questions, lengthAmount, lengthUnit);
    }

    /** Ein neuer Weidezaun; die Wahl des Lesers bleibt. */
    public BookNotes withFence(Fence fence, int targetWords) {
        return new BookNotes(fence.getMission(), fence.getEdge(), fence.getIncluded(), fence.getExcluded(), targetWords,
                chosen, unchosen, excludedFields, ideas, questions, lengthAmount, lengthUnit);
    }

    /** Die Länge, wie der Leser sie eingab (Zahl und Einheit); alles andere bleibt. */
    public BookNotes withLength(int amount, String unit) {
        return new BookNotes(mission, edge, included, excluded, targetWords, chosen, unchosen, excludedFields, ideas,
                questions, amount, unit);
    }

    /** Neue Stichpunkte und Fragen; Weidezaun und Wahl des Lesers bleiben. */
    public BookNotes withIdeas(String ideas, List<String> questions) {
        return new BookNotes(mission, edge, included, excluded, targetWords, chosen, unchosen, excludedFields, ideas,
                questions, lengthAmount, lengthUnit);
    }

    /** Die Notizen des Ordners; leer, wenn es keine gibt oder die Datei nicht lesbar ist. */
    public static Optional<BookNotes> load(Path dataDirectory, String folderId) {
        Path file = file(dataDirectory, folderId);
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(JSON.readValue(Files.readString(file, StandardCharsets.UTF_8), BookNotes.class));
        } catch (IOException | RuntimeException unreadable) {
            System.getLogger(BookNotes.class.getName()).log(System.Logger.Level.WARNING,
                    "Notizen zum Buch nicht lesbar ({0})", unreadable.getClass().getSimpleName());
            return Optional.empty();
        }
    }

    /** Schreibt die Notizen des Ordners in einer Änderung (temporäre Datei, dann umbenannt). */
    public void save(Path dataDirectory, String folderId) throws IOException {
        Path file = file(dataDirectory, folderId);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temp, JSON.writeValueAsString(this), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    static Path file(Path dataDirectory, String folderId) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        String name = Objects.requireNonNull(folderId, "folderId").replaceAll("[^A-Za-z0-9._-]", "_");
        return dataDirectory.resolve("app").resolve(FOLDER).resolve(name + ".json");
    }
}
