package com.aresstack.spaceharvester.application.extraction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Die eine Konfliktregel für Extraktionsergebnisse: Ein neues Ergebnis überschreibt ein vorhandenes nie stillschweigend.
 * Für Speicher und Validierung gleichermaßen; nicht kopieren.
 *
 * <ul>
 *   <li>Dasselbe Feld heißt: gleicher Sachverhalt ({@code matterId}), Abschnitt, Schlüssel, Aussagegruppe und Art
 *       ({@link Qualifier}). Eine beantragte und eine genehmigte Verlängerung bleiben so getrennt.</li>
 *   <li>Gleicher Wert: das vorhandene Feld bleibt. Anderer Wert: beide bleiben stehen und werden
 *       {@link FieldStatus#CONFLICT}.</li>
 *   <li>„Nicht gefunden“ weicht einem gefundenen Wert und verdrängt nie einen.</li>
 *   <li>Liefert eine Stufe ({@link Processor}) in einer anderen Version, ersetzen ihre Felder alle Felder ihrer alten
 *       Version (alte Version = veraltet).</li>
 * </ul>
 */
public final class FieldMerge {

    private FieldMerge() {
    }

    /** Führt {@code incoming} mit {@code existing} ({@code null}: keins) zum Ergebnis desselben Dokumentstands zusammen. */
    public static DocumentAnalysis merge(DocumentAnalysis existing, DocumentAnalysis incoming) {
        if (existing == null) {
            return incoming;
        }
        Map<String, String> newer = new LinkedHashMap<>();
        for (Processor processor : incoming.processors()) {
            if (processor.isKnown()) {
                newer.put(processor.id(), processor.version());
            }
        }
        Map<List<String>, List<ExtractedField>> sections = new LinkedHashMap<>();
        Map<List<String>, String> matters = new LinkedHashMap<>();
        for (AnalysisSection section : existing.sections()) {
            List<ExtractedField> fields = sections.computeIfAbsent(key(section), k -> new ArrayList<>());
            matters.putIfAbsent(key(section), section.matter());
            for (ExtractedField field : section.fields()) {
                if (!superseded(field.processor(), newer)) {
                    fields.add(field);
                }
            }
        }
        for (AnalysisSection section : incoming.sections()) {
            List<ExtractedField> fields = sections.computeIfAbsent(key(section), k -> new ArrayList<>());
            matters.put(key(section), section.matter());
            for (ExtractedField field : section.fields()) {
                add(fields, field);
            }
        }
        List<AnalysisSection> out = new ArrayList<>();
        sections.forEach((k, fields) -> {
            if (!fields.isEmpty()) {
                out.add(new AnalysisSection(k.get(0), matters.get(k), k.get(1), fields));
            }
        });
        Set<Processor> processors = new LinkedHashSet<>();
        existing.processors().stream().filter(p -> !superseded(p, newer)).forEach(processors::add);
        processors.addAll(incoming.processors());
        Set<AnalysisNote> notes = new LinkedHashSet<>(existing.notes());
        notes.addAll(incoming.notes());
        return new DocumentAnalysis(incoming.document(), incoming.state(), out, incoming.failure(),
                existing.sample() || incoming.sample(), List.copyOf(notes), List.copyOf(processors));
    }

    /** Fügt ein Feld nach der Konfliktregel in die Felder eines Abschnitts ein. */
    public static void add(List<ExtractedField> fields, ExtractedField field) {
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

    /** Ob zwei Felder desselben Abschnitts dasselbe Feld sind. */
    public static boolean sameField(ExtractedField a, ExtractedField b) {
        return a.key().equals(b.key()) && a.group().equals(b.group()) && a.qualifier() == b.qualifier();
    }

    private static boolean superseded(Processor processor, Map<String, String> newer) {
        String version = newer.get(processor.id());
        return processor.isKnown() && version != null && !version.equals(processor.version());
    }

    private static List<String> key(AnalysisSection section) {
        return List.of(section.matterId(), section.title());
    }
}
