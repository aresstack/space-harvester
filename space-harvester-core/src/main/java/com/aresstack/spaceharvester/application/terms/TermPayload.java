package com.aresstack.spaceharvester.application.terms;

import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Ergebnisformat des Prozessors {@value #PROCESSOR} ({@code TermProcessor}):
 * {@code {"terms":[{"term","normalized","kind","count","expansion","section","occurrences":[start,end,…]}]}}.
 * {@code occurrences} ist eine flache Liste aus Paaren Anfang/Ende in Textreihenfolge, damit Seiten mit vielen
 * Fundstellen klein bleiben; {@code count} zählt alle Vorkommen, auch gekürzte.
 */
public final class TermPayload {

    /** Kennung des Prozessors. */
    public static final String PROCESSOR = "terms";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private TermPayload() {
    }

    /**
     * Ein Begriff einer Seite.
     *
     * @param term        Wortlaut der ersten Fundstelle
     * @param normalized  Suchwert ({@link TermText#normalize})
     * @param kind        Art
     * @param count       Zahl aller Vorkommen auf der Seite
     * @param expansion   nur bei Akronymen: Ausschreibung in Klammern direkt daneben, sonst {@code null}
     * @param section     Überschrift des Abschnitts der ersten Fundstelle oder {@code null}
     * @param occurrences Fundstellen in Textreihenfolge
     */
    public record Entry(String term, String normalized, TermKind kind, int count, String expansion, String section,
                        List<TermOccurrence> occurrences) {

        public Entry {
            Objects.requireNonNull(term, "term");
            Objects.requireNonNull(normalized, "normalized");
            Objects.requireNonNull(kind, "kind");
            occurrences = List.copyOf(occurrences);
        }
    }

    /**
     * Liest ein Ergebnis.
     *
     * @throws IllegalArgumentException wenn es nicht diesem Format entspricht
     */
    public static List<Entry> parse(String payloadJson) {
        JsonNode entries;
        try {
            entries = JSON.readTree(payloadJson).path("terms");
        } catch (JacksonException e) {
            throw new IllegalArgumentException("Ergebnis nicht lesbar", e);
        }
        if (!entries.isArray()) {
            throw new IllegalArgumentException("terms fehlt");
        }
        List<Entry> parsed = new ArrayList<>(entries.size());
        for (JsonNode entry : entries) {
            JsonNode flat = entry.path("occurrences");
            List<TermOccurrence> occurrences = new ArrayList<>(flat.size() / 2);
            for (int i = 0; i + 1 < flat.size(); i += 2) {
                occurrences.add(new TermOccurrence(flat.get(i).asInt(), flat.get(i + 1).asInt()));
            }
            parsed.add(new Entry(text(entry, "term"), text(entry, "normalized"), TermKind.of(text(entry, "kind")),
                    entry.path("count").asInt(occurrences.size()), textOrNull(entry.path("expansion")),
                    textOrNull(entry.path("section")), occurrences));
        }
        return parsed;
    }

    /**
     * Die Indexeinträge eines gespeicherten Ergebnisses: einer je Begriff, für ein fehlgeschlagenes, fremdes oder
     * unlesbares Ergebnis keiner. So hat eine Seite nach einem Fehlschlag keine als aktuell geltenden Fundstellen mehr.
     */
    public static List<TermPosting> postings(Enrichment enrichment) {
        if (!PROCESSOR.equals(enrichment.processorId()) || enrichment.status() != Enrichment.Status.OK) {
            return List.of();
        }
        List<Entry> entries;
        try {
            entries = parse(enrichment.payloadJson());
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        List<TermPosting> postings = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            if (!TermText.usable(entry.normalized())) {
                continue;
            }
            postings.add(new TermPosting(enrichment.key(), entry.kind(), entry.term(), entry.normalized(),
                    entry.count(), entry.expansion(), entry.section(), entry.occurrences(), enrichment.revision(),
                    enrichment.inputSha256(), enrichment.processorVersion(),
                    TermIndex.sortKey(entry.normalized(), entry.kind(), enrichment.key())));
        }
        return postings;
    }

    private static String text(JsonNode entry, String field) {
        JsonNode node = entry.path(field);
        if (!node.isString()) {
            throw new IllegalArgumentException(field + " fehlt");
        }
        return node.asString();
    }

    private static String textOrNull(JsonNode node) {
        return node.isString() ? node.asString() : null;
    }
}
