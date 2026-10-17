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
 * <p>
 * Hängt der Parser eine Zeitangabe mit Präposition (außer {@code von}/{@code vom}) als {@code nmod} an den
 * Dativempfänger statt ans Prädikat ({@code dem Vorgesetzten am Montag}, typischer Anbindungsfehler des
 * GSD-Modells), wird sie zusätzlich als
 * unsichere {@link Role#TIME} der Aussage geführt ({@link Finding#TIME_FROM_NOMINAL}); die Parserkante bleibt.
 */
final class SupplementaryRoles {

    static final Set<String> TIME_WORDS = Set.of(
            "montag", "dienstag", "mittwoch", "donnerstag", "freitag", "samstag", "sonnabend", "sonntag",
            "wochenende", "januar", "februar", "märz", "april", "mai", "juni", "juli", "august", "september",
            "oktober", "november", "dezember", "tag", "woche", "monat", "jahr", "quartal", "halbjahr", "uhr",
            "morgen", "vormittag", "mittag", "nachmittag", "abend", "nacht", "stunde", "minute", "zeitpunkt",
            "datum", "frist", "werktag", "feiertag");
    private static final Set<String> INSTRUMENT_PREPOSITIONS = Set.of("mit", "mittels", "per");
    /** {@code vom Montag} bezeichnet Herkunft oder Datum eines Gegenstands, keinen Zeitpunkt der Handlung. */
    private static final Set<String> ORIGIN_PREPOSITIONS = Set.of("von", "vom");

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
        timesAttachedToParticipants(document, sentence, mentions, statement, arguments, findings);
        return statement.with(arguments, PrimaryRelationExtraction.withPronouns(mentions, arguments, findings));
    }

    private static void timesAttachedToParticipants(ParsedDocument document, Sentence sentence, Mentions mentions,
                                                    Statement statement, List<Argument> arguments,
                                                    Set<Finding> findings) {
        for (Token participant : sentence.dependents(statement.predicate().verb())) {
            // Nur am Empfänger: "den Bericht vom Montag" datiert zu Recht den Bericht, eine Zeitangabe am
            // Dativempfänger ist dagegen der bekannte Anbindungsfehler
            boolean recipient = "iobj".equals(participant.deprel())
                    || "obl:arg".equals(participant.deprel()) && Markers.of(sentence, participant) == null;
            if (!recipient) {
                continue;
            }
            for (Token nominal : sentence.dependents(participant.id())) {
                String marker = Markers.of(sentence, nominal);
                if (!"nmod".equals(nominal.deprel()) || marker == null
                        || ORIGIN_PREPOSITIONS.contains(marker.toLowerCase(Locale.ROOT))) {
                    continue;
                }
                Mention mention = mentions.at(document, sentence, nominal);
                String word = (mention.lemma() != null ? mention.lemma() : mention.headForm()).toLowerCase(Locale.ROOT);
                if (TIME_WORDS.contains(word)) {
                    arguments.add(new Argument(Role.TIME, mention.id(), nominal.deprel(), marker, Priority.CONTEXT,
                            Basis.LEXICON, false));
                    findings.add(Finding.TIME_FROM_NOMINAL);
                }
            }
        }
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
