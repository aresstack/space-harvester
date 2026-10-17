package com.aresstack.spaceharvester.application.enrichment;

import com.aresstack.spaceharvester.application.snapshot.PageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Die Akronyme einer Seite, gelesen aus dem gespeicherten Ergebnis des Prozessors {@code acronyms}
 * ({@link AcronymProcessor}) für genau den Text, der angezeigt wird (#115). Öffentliche Lesefunktion für Oberflächen
 * wie die Desktop-App oder den Enterprise AI Client: Sie erkennt nichts selbst, sondern bildet nur das Ergebnisformat ab
 * und prüft, ob es zum Text passt. Fundstellen gibt es nur bei {@link State#CURRENT}; ein Ergebnis einer anderen
 * Eingabe, Prozessorversion oder Seite wird nie auf einen Text projiziert.
 *
 * @param state     ob und warum Fundstellen vorliegen
 * @param result    das gespeicherte Ergebnis oder {@code null}, wenn es keines gibt
 * @param acronyms  aufsteigend nach Akronym; leer außer bei {@link State#CURRENT}
 * @param warnings  Hinweise des Prozessors, etwa gekürzte Listen
 */
public record PageAcronyms(State state, Enrichment result, List<Acronym> acronyms, List<String> warnings) {

    /** Kennung des Prozessors, dessen Ergebnis gelesen wird. */
    public static final String PROCESSOR = "acronyms";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    public PageAcronyms {
        Objects.requireNonNull(state, "state");
        acronyms = List.copyOf(acronyms);
        warnings = List.copyOf(warnings);
    }

    /** Zustand der Analyse gegenüber dem angezeigten Text. */
    public enum State {
        /** Berechnet aus genau diesem Text mit der aktuellen Prozessorversion; Fundstellen gelten. */
        CURRENT,
        /** Es gibt ein Ergebnis, aber für einen anderen Text oder eine andere Prozessorversion. */
        STALE,
        /** Der letzte Lauf ist fehlgeschlagen. */
        FAILED,
        /** Für die Seite gibt es noch kein Ergebnis oder keinen lesbaren Text. */
        MISSING
    }

    /**
     * Ein Akronym mit allen Fundstellen.
     *
     * @param acronym     Schreibweise ohne Plural-{@code s}
     * @param count       Zahl aller Vorkommen; kann größer sein als {@code occurrences}, wenn der Prozessor kürzte
     * @param expansion   Ausschreibung als reiner Text oder {@code null}, wenn sie auf der Seite nicht steht
     * @param section     Überschrift des Abschnitts der ersten Fundstelle oder {@code null}
     * @param occurrences Fundstellen in Textreihenfolge
     */
    public record Acronym(String acronym, int count, String expansion, String section, List<Occurrence> occurrences) {

        public Acronym {
            Objects.requireNonNull(acronym, "acronym");
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Eine Fundstelle als Zeichenpositionen (UTF-16) in {@link PageText#text()}.
     *
     * @param start erste Position
     * @param end   Position nach dem Wort, einschließlich eines Plural-{@code s}
     */
    public record Occurrence(int start, int end) {
    }

    /**
     * Liest die Akronyme einer Seite.
     *
     * @param key   die angezeigte Seite; Ergebnisse anderer Seiten oder Quellen werden nie verwendet
     * @param views die Anreicherungen dieser Seite ({@link Enrichments#view}) mit Aktualität gegenüber der Payload
     * @param text  der angezeigte Text, aus derselben Payload projiziert, oder {@code null}, wenn es keinen gibt
     */
    public static PageAcronyms read(PageKey key, List<Enrichments.View> views, PageText text) {
        Objects.requireNonNull(key, "key");
        Enrichments.View view = views.stream()
                .filter(candidate -> PROCESSOR.equals(candidate.enrichment().processorId())
                        && key.equals(candidate.enrichment().key()))
                .findFirst().orElse(null);
        if (view == null) {
            return new PageAcronyms(State.MISSING, null, List.of(), List.of());
        }
        Enrichment result = view.enrichment();
        if (text == null) {
            return new PageAcronyms(State.MISSING, result, List.of(), result.warnings());
        }
        if (view.freshness() != Enrichment.Freshness.CURRENT || !result.inputSha256().equals(text.inputSha256())) {
            return new PageAcronyms(State.STALE, result, List.of(), result.warnings());
        }
        if (result.status() == Enrichment.Status.FAILED) {
            return new PageAcronyms(State.FAILED, result, List.of(), result.warnings());
        }
        List<Acronym> acronyms;
        try {
            acronyms = parse(result.payloadJson(), text.text());
        } catch (JacksonException | IllegalArgumentException e) {
            return new PageAcronyms(State.FAILED, result, List.of(), result.warnings());
        }
        return new PageAcronyms(State.CURRENT, result, acronyms, result.warnings());
    }

    /** Alle Fundstellen aller Akronyme in Textreihenfolge. */
    public List<Mark> marks() {
        List<Mark> marks = new ArrayList<>();
        for (Acronym acronym : acronyms) {
            for (Occurrence occurrence : acronym.occurrences()) {
                marks.add(new Mark(occurrence, acronym));
            }
        }
        marks.sort((a, b) -> Integer.compare(a.occurrence().start(), b.occurrence().start()));
        return marks;
    }

    /** Eine Fundstelle mit ihrem Akronym. */
    public record Mark(Occurrence occurrence, Acronym acronym) {
    }

    private static List<Acronym> parse(String payloadJson, String text) {
        JsonNode entries = JSON.readTree(payloadJson).path("acronyms");
        if (!entries.isArray()) {
            throw new IllegalArgumentException("acronyms fehlt");
        }
        List<Acronym> acronyms = new ArrayList<>();
        for (JsonNode entry : entries) {
            String acronym = entry.path("acronym").asString();
            List<Occurrence> occurrences = new ArrayList<>();
            for (JsonNode occurrence : entry.path("occurrences")) {
                int start = occurrence.path("start").asInt(-1);
                int end = occurrence.path("end").asInt(-1);
                // Der Eingabe-Hash stimmt; eine Fundstelle, die nicht auf das Akronym zeigt, wäre ein Fehler des
                // Ergebnisses und wird nicht markiert statt falschen Text zu markieren.
                if (start >= 0 && end > start && end <= text.length() && text.startsWith(acronym, start)) {
                    occurrences.add(new Occurrence(start, end));
                }
            }
            acronyms.add(new Acronym(acronym, entry.path("count").asInt(occurrences.size()),
                    textOrNull(entry.path("expansion")), textOrNull(entry.path("section")), occurrences));
        }
        return acronyms;
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
