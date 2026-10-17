package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Begriffe einer Seite: die Kerne aller Nominalgruppen ({@link GermanText}), gruppiert nach normalisiertem Wortlaut,
 * mit Art, Häufigkeit und erster Fundstelle. Pronomen zählen nicht, weil ihr Bezug nicht aufgelöst wird; Plural und
 * Singular bleiben getrennte Begriffe, weil nichts lemmatisiert wird.
 *
 * <p>Ergebnis {@code {"entities":[{"term","normalized","kind","count","section","charStart","charEnd"}]}} mit
 * {@code kind} {@code noun}, {@code acronym} oder {@code identifier}, nach Häufigkeit absteigend, dann nach
 * {@code normalized}; höchstens {@value #MAX_ENTITIES} Einträge.
 */
public class EntityProcessor implements EnrichmentProcessor {

    static final int MAX_ENTITIES = 500;

    @Override
    public String id() {
        return "entities";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        GermanText analysis = GermanText.analyze(text);
        Map<String, Found> found = new LinkedHashMap<>();
        for (Sentence sentence : analysis.sentences()) {
            for (NounGroup group : sentence.nounGroups()) {
                Found entry = found.computeIfAbsent(group.normalized(),
                        key -> new Found(group, group.termStart(sentence), group.charEnd(sentence)));
                entry.count++;
            }
        }
        List<Map.Entry<String, Found>> sorted = new ArrayList<>(found.entrySet());
        sorted.sort(Comparator.<Map.Entry<String, Found>>comparingInt(entry -> -entry.getValue().count)
                .thenComparing(Map.Entry::getKey));
        List<String> warnings = new ArrayList<>(analysis.warnings());
        StringBuilder json = new StringBuilder("{\"entities\":[");
        int written = 0;
        for (Map.Entry<String, Found> entry : sorted) {
            if (written == MAX_ENTITIES) {
                warnings.add("Nur die " + MAX_ENTITIES + " häufigsten von " + sorted.size() + " Begriffen");
                break;
            }
            Found value = entry.getValue();
            String section = analysis.sectionAt(value.start);
            json.append(written++ == 0 ? "" : ",")
                    .append("{\"term\":").append(JsonText.quote(value.group.term()))
                    .append(",\"normalized\":").append(JsonText.quote(entry.getKey()))
                    .append(",\"kind\":").append(JsonText.quote(value.group.kind()))
                    .append(",\"count\":").append(value.count)
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section))
                    .append(",\"charStart\":").append(value.start)
                    .append(",\"charEnd\":").append(value.end).append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    private static final class Found {

        final NounGroup group;
        final int start;
        final int end;
        int count;

        Found(NounGroup group, int start, int end) {
            this.group = group;
            this.start = start;
            this.end = end;
        }
    }
}
