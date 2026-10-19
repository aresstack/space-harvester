package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.terms.TermKind;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.terms.TermText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Begriffe einer Seite mit jeder Fundstelle (#119, ADR 0019), Eingabe des Begriffsindex: Akronyme und technische
 * Kennungen nach den Regeln von {@link AcronymProcessor} über den ganzen Text, dazu die Begriffe aller Nominalgruppen deutscher Sätze
 * ({@link GermanText}, wie {@link EntityProcessor}). Erkennen beide dieselbe Stelle als Akronym oder Kennung, zählt sie
 * einmal, als Fund des Akronymprozessors mit dessen Ausschreibung; zwei echte Vorkommen bleiben zwei, auch bei gleichem Wortlaut.
 * Gruppiert wird nach Art und Suchwert ({@link TermText#normalize}); nichts wird lemmatisiert.
 *
 * <p>Ergebnis im Format {@link TermPayload}, aufsteigend nach Suchwert, dann Art. Über alle Begriffe zusammen werden
 * die ersten {@value #MAX_OCCURRENCES} Fundstellen in Textreihenfolge geschrieben und höchstens {@value #MAX_TERMS}
 * Begriffe; {@code count} zählt weiter alles, eine Warnung nennt die Kürzung. Die Ergebnisse von {@code acronyms} und
 * {@code entities} bleiben unverändert.
 */
public class TermProcessor implements EnrichmentProcessor {

    static final int MAX_OCCURRENCES = 50_000;
    static final int MAX_TERMS = 10_000;

    private final AcronymProcessor acronyms;

    /** Mit {@link AcronymProcessor#AcronymProcessor()}. */
    public TermProcessor() {
        this(new AcronymProcessor());
    }

    /** @param acronyms Akronyme und Kennungen nach den Regeln des Prozessors {@code acronyms} */
    public TermProcessor(AcronymProcessor acronyms) {
        this.acronyms = Objects.requireNonNull(acronyms, "acronyms");
    }

    @Override
    public String id() {
        return TermPayload.PROCESSOR;
    }

    @Override
    public String version() {
        return "3";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        String content = text.text();
        List<Mention> mentions = new ArrayList<>();
        Set<Integer> acronymStarts = new HashSet<>();
        for (AcronymProcessor.Mention mention : acronyms.mentions(content)) {
            mentions.add(new Mention(mention.acronym() ? TermKind.ACRONYM : TermKind.IDENTIFIER, mention.term(),
                    mention.expansion(), mention.start(), mention.end()));
            acronymStarts.add(mention.start());
        }
        GermanText analysis = GermanText.analyze(text);
        for (Sentence sentence : analysis.sentences()) {
            for (NounGroup group : sentence.nounGroups()) {
                TermKind kind = TermKind.of(group.kind());
                int start = group.termStart(sentence);
                if ((kind == TermKind.ACRONYM || kind == TermKind.IDENTIFIER) && acronymStarts.contains(start)) {
                    // Dieselbe Stelle hat der Akronymprozessor schon, mit Plural-s und Ausschreibung.
                    continue;
                }
                mentions.add(new Mention(kind, group.term(), null, start, group.charEnd(sentence)));
            }
        }
        mentions.sort(Comparator.comparingInt(Mention::start).thenComparingInt(Mention::end));

        Map<String, Found> found = new TreeMap<>();
        int retained = 0;
        boolean truncated = false;
        for (Mention mention : mentions) {
            if (!TermText.usable(mention.term())) {
                continue;
            }
            String normalized = TermText.normalize(mention.term());
            Found entry = found.computeIfAbsent(normalized + '\u0000' + mention.kind().id(),
                    key -> new Found(mention, normalized));
            entry.count++;
            if (retained < MAX_OCCURRENCES) {
                entry.occurrences.add(mention.start());
                entry.occurrences.add(mention.end());
                retained++;
            } else {
                truncated = true;
            }
            if (entry.expansion == null) {
                entry.expansion = mention.expansion();
            }
        }

        List<String> warnings = new ArrayList<>(analysis.warnings());
        StringBuilder json = new StringBuilder("{\"terms\":[");
        int written = 0;
        for (Found value : found.values()) {
            if (written == MAX_TERMS) {
                warnings.add("Nur die ersten " + MAX_TERMS + " von " + found.size() + " Begriffen");
                break;
            }
            String section = analysis.sectionAt(value.first.start());
            json.append(written++ == 0 ? "" : ",")
                    .append("{\"term\":").append(JsonText.quote(value.first.term()))
                    .append(",\"normalized\":").append(JsonText.quote(value.normalized))
                    .append(",\"kind\":").append(JsonText.quote(value.first.kind().id()))
                    .append(",\"count\":").append(value.count)
                    .append(",\"expansion\":").append(value.expansion == null ? "null" : JsonText.quote(value.expansion))
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section))
                    .append(",\"occurrences\":[");
            for (int i = 0; i < value.occurrences.size(); i++) {
                json.append(i == 0 ? "" : ",").append(value.occurrences.get(i));
            }
            json.append("]}");
        }
        if (truncated) {
            warnings.add("Fundstellen nur für die ersten " + MAX_OCCURRENCES + " Vorkommen im Text");
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    private record Mention(TermKind kind, String term, String expansion, int start, int end) {
    }

    private static final class Found {

        final Mention first;
        final String normalized;
        final List<Integer> occurrences = new ArrayList<>();
        int count;
        String expansion;

        Found(Mention first, String normalized) {
            this.first = first;
            this.normalized = normalized;
        }
    }
}
