package com.aresstack.spaceharvester.application.enrichment.language;

import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.JsonText;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Eigenschaften von Begriffen, wie der Text sie selbst nennt, in zwei Formen:
 * <ul>
 *   <li><b>attributiv</b>: ein Adjektiv in der Nominalgruppe ({@code die zuständige Sachbearbeitung}: zuständig);</li>
 *   <li><b>prädikativ</b>: Begriff, Form von sein oder bleiben, optional Verneinung und Adverbien, Adjektiv am
 *       Satz- oder Teilsatzende ({@code Der Dienst ist nicht erreichbar.}), auch in umgekehrter Stellung ({@code Seit
 *       heute ist der Dienst verfügbar.}) und mit {@code und}, {@code oder} oder Komma gereiht.</li>
 * </ul>
 * Sieht die Eigenschaft wie ein Partizip II aus ({@code eingereicht}, {@code aktualisiert}), ist sie ein Zustand
 * ({@code state}), sonst ein Merkmal ({@code attribute}). Attributive Adjektive verlieren ihre Flexionsendung; diese
 * Grundform ist ein ungeprüfter Vorschlag. Jede Eigenschaft gilt nur für ihren Satz, nie als allgemeine Wahrheit.
 *
 * <p>Ergebnis {@code {"properties":[{"subject","subjectNormalized","property","normalized","kind","form","negated",
 * "section","charStart","charEnd"}]}} in Textreihenfolge, höchstens {@value #MAX_PROPERTIES} Einträge.
 */
public class PropertyProcessor implements EnrichmentProcessor {

    static final int MAX_PROPERTIES = 1000;

    @Override
    public String id() {
        return "properties";
    }

    @Override
    public String version() {
        return "1";
    }

    @Override
    public EnrichmentOutput process(PageText text) {
        GermanText analysis = GermanText.analyze(text);
        List<Property> found = new ArrayList<>();
        for (Sentence sentence : analysis.sentences()) {
            for (NounGroup group : sentence.nounGroups()) {
                for (Token adjective : group.adjectives(sentence)) {
                    String base = Terms.adjectiveBase(adjective.text());
                    found.add(new Property(group, adjective.text(), base, "attributive", false,
                            group.charStart(sentence), group.charEnd(sentence)));
                }
            }
            predicative(sentence, found);
        }
        found.sort(Comparator.comparingInt(Property::start).thenComparingInt(Property::end));
        List<String> warnings = new ArrayList<>(analysis.warnings());
        if (found.size() > MAX_PROPERTIES) {
            warnings.add("Nur die ersten " + MAX_PROPERTIES + " von " + found.size() + " Eigenschaften");
        }
        StringBuilder json = new StringBuilder("{\"properties\":[");
        for (int i = 0; i < Math.min(found.size(), MAX_PROPERTIES); i++) {
            Property property = found.get(i);
            String section = analysis.sectionAt(property.start);
            json.append(i == 0 ? "" : ",")
                    .append("{\"subject\":").append(JsonText.quote(property.subject.term()))
                    .append(",\"subjectNormalized\":").append(JsonText.quote(property.subject.normalized()))
                    .append(",\"property\":").append(JsonText.quote(property.surface))
                    .append(",\"normalized\":").append(JsonText.quote(property.normalized))
                    .append(",\"kind\":").append(Terms.participle(property.normalized) ? "\"state\"" : "\"attribute\"")
                    .append(",\"form\":").append(JsonText.quote(property.form))
                    .append(",\"negated\":").append(property.negated)
                    .append(",\"section\":").append(section == null ? "null" : JsonText.quote(section))
                    .append(",\"charStart\":").append(property.start)
                    .append(",\"charEnd\":").append(property.end).append('}');
        }
        return new EnrichmentOutput(json.append("]}").toString(), warnings);
    }

    /** {@code Begriff Kopula … Adjektiv} und {@code Kopula Begriff … Adjektiv}. */
    private static void predicative(Sentence sentence, List<Property> found) {
        List<Token> tokens = sentence.tokens();
        for (int k = 0; k < tokens.size(); k++) {
            if (!tokens.get(k).is(WordClass.AUXILIARY) || !Lexicon.COPULAS.contains(tokens.get(k).lower())) {
                continue;
            }
            NounGroup subject = null;
            int next = k + 1;
            for (NounGroup group : sentence.nounGroups()) {
                if (group.to() == k) {
                    subject = group;
                }
            }
            NounGroup after = sentence.groupAt(k + 1);
            if (subject == null && after != null) {
                subject = after;
                next = after.to();
            }
            if (subject != null) {
                properties(sentence, subject, next, found);
            }
        }
    }

    private static void properties(Sentence sentence, NounGroup subject, int index, List<Property> found) {
        List<Token> tokens = sentence.tokens();
        boolean negated = false;
        int i = index;
        while (i < tokens.size()) {
            Token token = tokens.get(i);
            if (token.is(WordClass.NEGATION)) {
                negated = true;
                i++;
            } else if (token.is(WordClass.ADVERB)) {
                i++;
            } else if (token.is(WordClass.OTHER) && Character.isLowerCase(token.text().charAt(0))
                    && clauseEnd(tokens, i + 1)) {
                found.add(new Property(subject, token.text(), Terms.normalize(token.text()), "predicative", negated,
                        subject.charStart(sentence), token.end()));
                i++;
                if (i < tokens.size() && (tokens.get(i).punctuation(",") || isOneOf(tokens.get(i), "und", "oder"))) {
                    i++;
                } else {
                    return;
                }
            } else {
                return;
            }
        }
    }

    /** Nach Token {@code index - 1} endet der Satz oder Teilsatz. */
    private static boolean clauseEnd(List<Token> tokens, int index) {
        if (index >= tokens.size()) {
            return true;
        }
        Token token = tokens.get(index);
        return token.is(WordClass.PUNCTUATION) && !token.text().equals("-") || isOneOf(token, "und", "oder", "aber");
    }

    private static boolean isOneOf(Token token, String... words) {
        for (String word : words) {
            if (token.lower().equals(word) && token.is(WordClass.CONJUNCTION)) {
                return true;
            }
        }
        return false;
    }

    private record Property(NounGroup subject, String surface, String normalized, String form, boolean negated,
                            int start, int end) {
    }
}
