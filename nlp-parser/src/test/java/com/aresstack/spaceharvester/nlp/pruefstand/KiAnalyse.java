package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.application.knowledge.ModelStatementAnalyzer;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.stepmodel.StepModel;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Befund;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Rolle;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Der zweite Analysator (Fahrplan Paket A2) auf dem Prüfstand: derselbe {@link ModelStatementAnalyzer} wie im Betrieb,
 * das Sprachmodell durch die gepinnten Antworten ersetzt. Eingabe sind die Sätze des Pins „heute“, je Zeile einer, damit
 * die Satznummern der Vorgabe gelten; im Betrieb setzt der Satzsegmentierer des Core dieselben Grenzen (OpenNLP).
 */
final class KiAnalyse {

    /** Je Zeile genau ein Satz: die Grenzen kommen aus dem Pin. */
    static final SentenceSegmenter ZEILEN = new SentenceSegmenter() {
        @Override
        public List<String> sentences(String paragraph) {
            return List.of(paragraph);
        }

        @Override
        public String id() {
            return "pruefstand-zeilen";
        }
    };

    private KiAnalyse() {
    }

    /** Der Text, den der Analysator bekommt: die Sätze des Pins „heute“, je Zeile einer. */
    static String eingabe(Textsatz satz, Text text) {
        ParsedDocument heute = satz.analyse(satz.varianten().get(0), text);
        return String.join("\n", heute.sentences().stream().map(Sentence::text).map(String::strip).toList());
    }

    /** Die Aussagen des Analysators mit den gepinnten Antworten als Befunde zum Satz der Vorgabe (ab 1). */
    static List<Befund> befunde(Textsatz satz, Variante variante, Text text) {
        List<String> antworten = satz.kiAntworten(variante, text);
        if (antworten == null) {
            throw new IllegalStateException(satz.name() + "/" + variante.name() + "/" + text.name()
                    + ": keine Modellantworten (.antwort)");
        }
        Deque<String> offen = new ArrayDeque<>(antworten);
        StepModel gepinnt = new StepModel() {
            @Override
            public String version() {
                return "pruefstand:" + variante.name();
            }

            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String complete(String instructions, String input) {
                if (offen.isEmpty()) {
                    throw new IllegalStateException(satz.name() + "/" + variante.name() + "/" + text.name()
                            + ": mehr Aufrufe als gepinnte Antworten");
                }
                return offen.removeFirst();
            }
        };
        ParsedStatements result = new ModelStatementAnalyzer(gepinnt, ZEILEN).analyze("pruefstand",
                eingabe(satz, text), () -> false);
        if (!offen.isEmpty()) {
            throw new IllegalStateException(satz.name() + "/" + variante.name() + "/" + text.name()
                    + ": weniger Aufrufe als gepinnte Antworten");
        }
        return befunde(result);
    }

    static List<Befund> befunde(ParsedStatements result) {
        Map<String, ParsedStatements.Mention> mentions = new HashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention));
        List<Befund> befunde = new ArrayList<>();
        for (ParsedStatements.Statement statement : result.statements()) {
            Map<Rolle, List<Set<String>>> rollen = new EnumMap<>(Rolle.class);
            Set<String> alle = new HashSet<>();
            String lemma = statement.predicateLemma().toLowerCase(Locale.ROOT);
            StringBuilder kurz = new StringBuilder(lemma).append('(');
            for (ParsedStatements.Argument argument : statement.arguments()) {
                Rolle rolle = switch (argument.role()) {
                    case ACTOR, BEARER -> Rolle.HANDELNDER;
                    case AFFECTED_OBJECT -> Rolle.BETROFFENES;
                    case RECIPIENT -> Rolle.EMPFAENGER;
                    default -> null;
                };
                ParsedStatements.Mention mention = mentions.get(argument.mention());
                Set<String> namen = namen(mention);
                alle.addAll(namen);
                if (rolle != null) {
                    rollen.computeIfAbsent(rolle, r -> new ArrayList<>()).add(namen);
                    kurz.append(kurz.charAt(kurz.length() - 1) == '(' ? "" : ", ").append(rolle.name().charAt(0))
                            .append('=').append(mention.text());
                }
            }
            kurz.append(") ").append(StatementQuality.Level.PLAUSIBLE);
            ParsedStatements.Features f = statement.features();
            StatementFeatures.Features merkmale = new StatementFeatures.Features(
                    f.negation() == ParsedStatements.Negation.NEGATED,
                    f.modality() == ParsedStatements.Modality.UNKNOWN ? StatementFeatures.Modality.NONE
                            : StatementFeatures.Modality.valueOf(f.modality().name()),
                    f.temporality() == ParsedStatements.Temporality.UNKNOWN ? StatementFeatures.Temporality.GILT
                            : StatementFeatures.Temporality.valueOf(f.temporality().name()));
            befunde.add(new Befund(null, statement.sentence() + 1, lemma,
                    statement.quality() == ParsedStatements.Quality.PLAUSIBLE ? StatementQuality.Level.PLAUSIBLE
                            : StatementQuality.Level.UNCERTAIN, rollen, alle, kurz.toString(), merkmale));
        }
        return befunde;
    }

    /**
     * Vergleichsnamen wie im Prüfstand: Kopf (letztes Wort) und ganzer Wortlaut in Kleinschreibung; ein Teilsatz als
     * „§“ + Grundform seines Verbs.
     */
    private static Set<String> namen(ParsedStatements.Mention mention) {
        Set<String> namen = new LinkedHashSet<>();
        if (mention == null) {
            return namen;
        }
        if (mention.kind() == ParsedStatements.MentionKind.CLAUSE) {
            namen.add("§" + (mention.lemma() != null ? mention.lemma() : mention.headForm()).toLowerCase(Locale.ROOT));
            return namen;
        }
        namen.add(mention.headForm().toLowerCase(Locale.ROOT));
        namen.add(mention.text().strip().toLowerCase(Locale.ROOT));
        return namen;
    }
}
