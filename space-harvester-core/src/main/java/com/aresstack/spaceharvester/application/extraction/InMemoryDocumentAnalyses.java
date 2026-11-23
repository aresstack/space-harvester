package com.aresstack.spaceharvester.application.extraction;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Analyseergebnisse im Arbeitsspeicher, je Dokumentstand ({@link DocumentRef} samt Version). Keine Datenbank; nach
 * einem Neustart ist alles weg.
 *
 * <p>Ein neues Ergebnis überschreibt ein vorhandenes nie stillschweigend: {@link #record} führt es Feld für Feld mit dem
 * vorhandenen zusammen. Felder gelten als dasselbe Feld, wenn Sachverhalt, Abschnitt, Schlüssel, Aussagegruppe und Erläuterung gleich sind (so bleiben
 * eine beantragte und eine genehmigte Verlängerung getrennt). Gleicher Wert: das vorhandene bleibt. Anderer Wert: beide
 * bleiben stehen und werden {@link FieldStatus#CONFLICT}. „Nicht gefunden“ weicht einem gefundenen Wert und verdrängt
 * nie einen. Thread-sicher.
 */
public final class InMemoryDocumentAnalyses implements DocumentAnalysisProvider {

    private final Map<DocumentRef, DocumentAnalysis> results = new HashMap<>();

    @Override
    public synchronized DocumentAnalysis result(DocumentRef document) {
        Objects.requireNonNull(document, "document");
        DocumentAnalysis found = results.get(document);
        return found == null ? DocumentAnalysis.notAnalyzed(document) : found;
    }

    /** Legt das Ergebnis ab oder führt es mit dem vorhandenen zusammen und liefert, was jetzt gilt. */
    public synchronized DocumentAnalysis record(DocumentAnalysis incoming) {
        Objects.requireNonNull(incoming, "incoming");
        DocumentAnalysis merged = merge(results.get(incoming.document()), incoming);
        results.put(incoming.document(), merged);
        return merged;
    }

    /** Vergisst alle Ergebnisse des Dokumentstands. */
    public synchronized void forget(DocumentRef document) {
        results.remove(document);
    }

    static DocumentAnalysis merge(DocumentAnalysis existing, DocumentAnalysis incoming) {
        if (existing == null || existing.sections().isEmpty()) {
            return incoming;
        }
        Map<List<String>, List<ExtractedField>> sections = new LinkedHashMap<>();
        for (AnalysisSection section : existing.sections()) {
            sections.computeIfAbsent(List.of(section.matter(), section.title()), k -> new ArrayList<>())
                    .addAll(section.fields());
        }
        for (AnalysisSection section : incoming.sections()) {
            List<ExtractedField> fields = sections.computeIfAbsent(List.of(section.matter(), section.title()),
                    k -> new ArrayList<>());
            for (ExtractedField field : section.fields()) {
                add(fields, field);
            }
        }
        List<AnalysisSection> out = new ArrayList<>();
        sections.forEach((k, fields) -> out.add(new AnalysisSection(k.get(0), k.get(1), fields)));
        return new DocumentAnalysis(incoming.document(), incoming.state(), out, incoming.failure(),
                existing.sample() || incoming.sample(), union(existing.notes(), incoming.notes()),
                union(existing.processors(), incoming.processors()));
    }

    private static <T> List<T> union(List<T> first, List<T> second) {
        LinkedHashSet<T> all = new LinkedHashSet<>(first);
        all.addAll(second);
        return List.copyOf(all);
    }

    private static void add(List<ExtractedField> fields, ExtractedField field) {
        boolean seen = fields.stream().anyMatch(old -> sameField(old, field));
        if (seen && field.status() == FieldStatus.NOT_FOUND) {
            return;
        }
        fields.removeIf(old -> sameField(old, field) && old.status() == FieldStatus.NOT_FOUND);
        List<Integer> same = new ArrayList<>();
        for (int i = 0; i < fields.size(); i++) {
            if (sameField(fields.get(i), field)) {
                if (fields.get(i).value().equals(field.value())) {
                    return;
                }
                same.add(i);
            }
        }
        if (same.isEmpty()) {
            fields.add(field);
            return;
        }
        for (int at : same) {
            fields.set(at, fields.get(at).withStatus(FieldStatus.CONFLICT));
        }
        fields.add(field.withStatus(FieldStatus.CONFLICT));
    }

    private static boolean sameField(ExtractedField a, ExtractedField b) {
        return a.key().equals(b.key()) && a.note().equals(b.note()) && a.group().equals(b.group());
    }
}
