package com.aresstack.spaceharvester.nlp.extraction;

import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ergänzende Rollen derselben Aussage aus weiteren Kanten des Prädikats:
 * <ul>
 *   <li>{@code obl:agent} (von-/durch-Gruppe im Passiv) → {@link Role#ACTOR}.</li>
 *   <li>{@code iobj} und {@code obl:arg} ohne Präposition → {@link Role#RECIPIENT}; ist kein Dativ bestätigt
 *       (Kasus unbekannt oder ohne Dativ), bleibt die Rolle als unsicher markiert.</li>
 *   <li>{@code obl:arg} mit Präposition → {@link Role#PREPOSITIONAL_OBJECT}.</li>
 *   <li>{@code obl}: Zeitwörter ({@code Montag}, {@code Woche}) → {@link Role#TIME}, {@code mit}/{@code mittels}/
 *       {@code per} → {@link Role#INSTRUMENT}, sonst {@link Role#CIRCUMSTANCE}; nachrangig.</li>
 * </ul>
 * Die Unterscheidung Dativargument gegen Zeitangabe folgt der Kante ({@code obl:arg} gegen {@code obl}), nicht
 * dem Kasus allein: {@code am Montag} steht auch im Dativ.
 */
final class SupplementaryRoles {

    static final Set<String> TIME_WORDS = Set.of(
            "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonnabend", "sonntag",
            "wochenende", "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september",
            "oktober", "november", "dezember", "tag", "woche", "monat", "jahr", "quartal", "halbjahr", "uhr",
            "morgen", "vormittag", "mittag", "nachmittag", "abend", "nacht", "stunde", "minute", "zeitpunkt",
            "datum", "frist", "werktag", "feiertag");
    private static final Set<String> INSTRUMENT_PREPOSITIONS = Set.of("mit", "mittels", "per");

    private SupplementaryRoles() {
    }

    static Statement add(ParsedDocument document, Mentions mentions, Statement statement) {
        Sentence sentence = document.sentences().get(statement.sentence());
        List<Argument> arguments = new ArrayList<>(statement.arguments());
        Set<Finding> findings = EnumSet.noneOf(Finding.class);
        findings.addAll(statement.findings());
        for (Token dependent : sentence.dependents(statement.predicate().verb())) {
            String relation = dependent.deprel();
            if (relation == null || !"obl".equals(dependent.baseRelation()) && !"iobj".equals(relation)) {
                continue;
            }
            Mention mention = mentions.at(document, sentence, dependent);
            String marker = Markers.of(sentence, dependent);
            if ("obl:agent".equals(relation)) {
                arguments.add(new Argument(Role.ACTOR, mention.id(), relation, marker, Priority.SUPPLEMENTARY,
                        Basis.PARSER, true));
            } else if ("iobj".equals(relation) || "obl:arg".equals(relation) && marker == null) {
                boolean dative = mention.cases().contains(CaseHints.DAT);
                if (!dative) {
                    findings.add(Finding.UNCONFIRMED_DATIVE);
                }
                arguments.add(new Argument(Role.RECIPIENT, mention.id(), relation, null, Priority.SUPPLEMENTARY,
                        Basis.PARSER, dative));
            } else if ("obl:arg".equals(relation)) {
                arguments.add(new Argument(Role.PREPOSITIONAL_OBJECT, mention.id(), relation, marker,
                        Priority.SUPPLEMENTARY, Basis.PARSER, true));
            } else {
                arguments.add(circumstance(mention, relation, marker));
            }
        }
        return statement.with(arguments, PrimaryRelationExtraction.withPronouns(mentions, arguments, findings));
    }

    private static Argument circumstance(Mention mention, String relation, String marker) {
        String word = (mention.lemma() != null ? mention.lemma() : mention.headForm()).toLowerCase(Locale.ROOT);
        if (TIME_WORDS.contains(word)) {
            return new Argument(Role.TIME, mention.id(), relation, marker, Priority.CONTEXT, Basis.LEXICON, true);
        }
        if (marker != null && INSTRUMENT_PREPOSITIONS.contains(marker.toLowerCase(Locale.ROOT))) {
            return new Argument(Role.INSTRUMENT, mention.id(), relation, marker, Priority.CONTEXT, Basis.LEXICON,
                    true);
        }
        return new Argument(Role.CIRCUMSTANCE, mention.id(), relation, marker, Priority.CONTEXT, Basis.PARSER, true);
    }
}
