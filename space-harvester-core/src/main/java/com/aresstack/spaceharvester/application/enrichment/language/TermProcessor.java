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
        // Dieselben Akronymregeln samt Satzgrenzen wie acronyms.
        return "6;acronyms=" + acronyms.version();
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        String content = text.text();
        List<Mention> mentions = new ArrayList<>();
        Set<Integer> acronymStarts = new HashSet<>();
        for (AcronymProcessor.Mention mention : acronyms.mentions(content)) {
            mentions.add(new Mention(mention.acronym() ? TermKind.ACRONYM : TermKind.IDENTIFIER, mention.term(),
                    mention.normalized(), mention.expansion(), mention.start(), mention.end()));
            acronymStarts.add(mention.start());
        }
        GermanText analysis = GermanText.analyze(text);
        Set<String> confirmed = confirmedNouns(analysis);
        for (Sentence sentence : analysis.sentences()) {
            int firstWord = firstWord(sentence);
            for (NounGroup group : sentence.nounGroups()) {
                TermKind kind = TermKind.of(group.kind());
                int start = group.termStart(sentence);
                int end = group.charEnd(sentence);
                if ((kind == TermKind.ACRONYM || kind == TermKind.IDENTIFIER) && acronymStarts.contains(start)) {
                    // Dieselbe Stelle hat der Akronymprozessor schon, mit Plural-s und Ausschreibung.
                    continue;
                }
                if (kind == TermKind.NOUN && (truncatedCompound(content, end)
                        || group.from() == firstWord && group.termFrom() == firstWord && group.to() == firstWord + 1
                        && notANounAtStart(group.term(), confirmed))) {
                    continue;
                }
                mentions.add(new Mention(kind, group.term(), null, null, start, end));
            }
        }
        Set<String> nounKeys = new HashSet<>();
        for (Mention mention : mentions) {
            if (mention.kind() == TermKind.NOUN) {
                nounKeys.add(TermText.normalize(mention.term()));
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
            // Akronyme mit der Suchform des EAC (U.S.A. und USA gleich), alles andere nach TermText.
            String surface = TermText.normalize(mention.normalized() != null ? mention.normalized()
                    : mention.term());
            String normalized = mention.kind() == TermKind.NOUN ? baseForm(surface, nounKeys) : surface;
            Found entry = found.computeIfAbsent(normalized + '\u0000' + mention.kind().id(),
                    key -> new Found(mention, normalized));
            if (!entry.base && surface.equals(normalized)) {
                // Angezeigt wird die Grundform, sobald sie auf der Seite vorkommt.
                entry.first = mention;
                entry.base = true;
            }
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

    private record Mention(TermKind kind, String term, String normalized, String expansion, int start, int end) {
    }

    private static final class Found {

        Mention first;
        boolean base;
        final String normalized;
        final List<Integer> occurrences = new ArrayList<>();
        int count;
        String expansion;

        Found(Mention first, String normalized) {
            this.first = first;
            this.normalized = normalized;
            this.base = TermText.normalize(first.normalized() != null ? first.normalized() : first.term())
                    .equals(normalized);
        }
    }

    /** Wörter, die keine Nomen sind, auch großgeschrieben am Satz- oder Listenanfang. */
    private static final Set<String> NEVER_NOUNS = Set.of("siehe", "bitte", "beispielsweise", "insbesondere",
            "ggf", "bzw", "zb", "usw", "sowie", "zudem", "ebenso", "jedoch", "allerdings", "außerdem", "daher",
            "dabei", "hierbei", "hierzu", "dazu", "danach", "anschließend", "zunächst", "optional", "neu", "alt");

    private static final java.util.regex.Pattern ADJECTIVE_ENDING = java.util.regex.Pattern.compile(
            ".{2,}(al|ell|ig|isch|lich|bar|iv|los|sam|haft|end)");

    /** Großgeschriebene Einzelwörter, die irgendwo auf der Seite nicht am Satzanfang stehen: sicher Nomen. */
    private static Set<String> confirmedNouns(GermanText analysis) {
        Set<String> confirmed = new HashSet<>();
        for (Sentence sentence : analysis.sentences()) {
            int firstWord = firstWord(sentence);
            for (int i = 0; i < sentence.tokens().size(); i++) {
                Token token = sentence.tokens().get(i);
                if (i != firstWord && token.is(WordClass.NOUN)) {
                    confirmed.add(token.text());
                }
            }
        }
        return confirmed;
    }

    private static int firstWord(Sentence sentence) {
        for (int i = 0; i < sentence.tokens().size(); i++) {
            if (!sentence.tokens().get(i).is(WordClass.PUNCTUATION)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Ein Einzelwort ohne Artikel am Satz- oder Listenanfang ist nur großgeschrieben, weil es vorne steht: kein Nomen,
     * wenn es auf der Seite sonst nie großgeschrieben mitten im Satz vorkommt und wie Verb, Partizip oder Adjektiv
     * aussieht ({@code Erlaubt}, {@code Ermöglicht}, {@code Angeboten}, {@code Zentral}) oder nie ein Nomen ist
     * ({@code Siehe}).
     */
    static boolean notANounAtStart(String word, Set<String> confirmed) {
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        if (NEVER_NOUNS.contains(lower)) {
            return true;
        }
        if (confirmed.contains(word)) {
            return false;
        }
        return Terms.participle(lower) || Verbs.verbLike(lower) || ADJECTIVE_ENDING.matcher(lower).matches();
    }

    /** Vorderglied mit Ergänzungsstrich ({@code Bundes- und Landesrecht}): kein eigener Begriff. */
    private static boolean truncatedCompound(String content, int end) {
        return end < content.length() && content.charAt(end) == '-';
    }

    /**
     * Grundform statt Genitiv-s ({@code Haushalts}, {@code Ansatzes}), nur wenn sie selbst auf der Seite steht; ohne
     * Wörterbuch wird nichts geraten.
     */
    static String baseForm(String normalized, Set<String> nounKeys) {
        if (normalized.length() > 4 && normalized.endsWith("es")
                && nounKeys.contains(normalized.substring(0, normalized.length() - 2))) {
            return normalized.substring(0, normalized.length() - 2);
        }
        if (normalized.length() > 3 && normalized.endsWith("s") && !normalized.endsWith("ss")
                && nounKeys.contains(normalized.substring(0, normalized.length() - 1))) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
