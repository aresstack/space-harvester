package com.aresstack.spaceharvester.application.extraction;

import java.util.List;
import java.util.Objects;

/**
 * Das Analyseergebnis zu genau einem Dokumentstand. Ergebnisse sind immer experimentell und nicht fachlich geprüft.
 *
 * @param document Dokumentstand, dem das Ergebnis gehört
 * @param state    Stand der Analyse
 * @param sections Abschnitte; leer, solange nichts vorliegt
 * @param failure  Grund bei {@link AnalysisState#FAILED}, sonst leer
 * @param sample     Beispieldaten für Oberfläche und Tests, keine echte Analyse; wird so angezeigt
 * @param notes      Prüfhinweise zum ganzen Ergebnis (Unvollständigkeit, fehlende KI …)
 * @param processors beteiligte Stufen mit Version, etwa {@code regex/1} oder {@code ai/gemma4:12b/prompt-1}
 */
public record DocumentAnalysis(DocumentRef document, AnalysisState state, List<AnalysisSection> sections,
                               String failure, boolean sample, List<AnalysisNote> notes, List<Processor> processors) {

    public DocumentAnalysis {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(state, "state");
        sections = List.copyOf(sections);
        failure = failure == null ? "" : failure;
        notes = notes == null ? List.of() : List.copyOf(notes);
        processors = processors == null ? List.of() : List.copyOf(processors);
    }

    public DocumentAnalysis(DocumentRef document, AnalysisState state, List<AnalysisSection> sections,
                            String failure, boolean sample) {
        this(document, state, sections, failure, sample, List.of(), List.of());
    }

    public static DocumentAnalysis notAnalyzed(DocumentRef document) {
        return new DocumentAnalysis(document, AnalysisState.NOT_ANALYZED, List.of(), "", false);
    }

    public static DocumentAnalysis inProgress(DocumentRef document) {
        return new DocumentAnalysis(document, AnalysisState.IN_PROGRESS, List.of(), "", false);
    }

    public static DocumentAnalysis failed(DocumentRef document, String reason) {
        return new DocumentAnalysis(document, AnalysisState.FAILED, List.of(), reason, false);
    }

    public static DocumentAnalysis completed(DocumentRef document, List<AnalysisSection> sections) {
        return new DocumentAnalysis(document, AnalysisState.COMPLETED, sections, "", false);
    }

    /** Wie viele Felder alle Abschnitte zusammen haben. */
    public int fieldCount() {
        return sections.stream().mapToInt(section -> section.fields().size()).sum();
    }

    /** Wie viele Felder diesen Stand haben. */
    public long count(FieldStatus status) {
        return sections.stream().flatMap(section -> section.fields().stream())
                .filter(field -> field.status() == status).count();
    }
}
