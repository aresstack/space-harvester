package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.check.Corrections;
import com.aresstack.spaceharvester.application.check.StatementCheck;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.Role;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Befund;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Der Prüfschritt im Prüfstand (ADR 0032, FL4): die Aussagen eines Textes so, wie der Auftrag sie dem Modell zeigen
 * würde ({@link StatementCheck.Item}), mit dem Urteil der Vorgabe („richtig“ = einer Erwartung des Originalsatzes
 * vollständig entsprechend, wie bei „falsch trotz PLAUSIBLE“). Grundlage ist die Variante {@value #VARIANTE}
 * (Betrieb „nur wo nötig“ mit der Vorstufe von Hand, also die Aussagen, die der Standard-Auswahl auch Aussagen der
 * Vorstufe gibt); fehlt sie, „heute“.
 */
final class Pruefschritt {

    static final String VARIANTE = "gezielt-hand";

    private Pruefschritt() {
    }

    /**
     * Eine Aussage für den Prüfschritt.
     *
     * @param befund    die Aussage mit Originalsatz (ab 1)
     * @param vorstufe  ob sie aus der Vorstufe stammt
     * @param plausibel ob die Stufe des Parsers PLAUSIBLE ist
     * @param ungueltig ob die Stufe INVALID ist (nie geprüft)
     * @param richtig   Urteil der Vorgabe
     * @param satzText  der Originalsatz
     */
    record Aussage(Befund befund, boolean vorstufe, boolean plausibel, boolean ungueltig, boolean richtig,
                   StatementCheck.Item item, Corrections.View view, String satzText) {

        /** Ob die Aussage zur Auswahl gehört, nach derselben Regel wie im Auftrag ({@link CheckScope#includes}). */
        boolean in(CheckScope scope) {
            if (ungueltig) {
                return false;
            }
            return scope == CheckScope.ALL || !plausibel || vorstufe;
        }

        /** Schlüssel einer Handantwort: Text, Originalsatz, Kurzform der Aussage. */
        String schluessel(String text) {
            return text + "\t" + befund.satz() + "\t" + befund.kurz();
        }
    }

    /** Die Variante, deren Aussagen geprüft werden. */
    static Variante variante(Textsatz satz) {
        return satz.varianten().stream().filter(v -> VARIANTE.equals(v.name())).findFirst()
                .orElse(satz.varianten().get(0));
    }

    /** Die Aussagen eines Textes. */
    static List<Aussage> aussagen(Textsatz satz, Text text) {
        Bewertung.Aufbau aufbau = Bewertung.aufbauen(satz, variante(satz), text, zeile -> true);
        List<Aussage> result = new ArrayList<>();
        for (Befund befund : aufbau.befunde()) {
            String satzText = aufbau.original().sentences().get(befund.satz() - 1).text();
            result.add(new Aussage(befund, aufbau.vorstufe().contains(befund.statement()),
                    befund.stufe() == StatementQuality.Level.PLAUSIBLE,
                    befund.stufe() == StatementQuality.Level.INVALID, Bewertung.richtig(satz, text, befund),
                    item(befund, aufbau.mentions()), view(befund, aufbau.mentions()), satzText));
        }
        return result;
    }

    /** Die Aussage, wie der Auftrag sie dem Modell zeigt ({@code StatementCheck.Item.of}). */
    static StatementCheck.Item item(Befund befund, Map<String, Mention> mentions) {
        List<StatementCheck.Participant> participants = new ArrayList<>();
        for (Argument argument : befund.statement().arguments()) {
            Mention mention = mentions.get(argument.mention());
            if (mention != null) {
                participants.add(new StatementCheck.Participant(ParsedStatements.Role.valueOf(argument.role().name()),
                        argument.marker(), mention.text()));
            }
        }
        boolean state = befund.statement().findings().stream()
                .anyMatch(f -> f.name().equals(ParsedStatements.Statement.STATE_PROPERTY)
                        || f.name().equals(ParsedStatements.Statement.STATE_ROLE_NOUN));
        String predicate = befund.statement().predicate().lemma() != null ? befund.statement().predicate().lemma()
                : befund.lemma();
        return new StatementCheck.Item(predicate, state, participants, features(befund.merkmale()));
    }

    /** Was {@link Corrections#accept} von der Aussage braucht. */
    static Corrections.View view(Befund befund, Map<String, Mention> mentions) {
        Set<String> heads = new HashSet<>();
        List<String> participants = new ArrayList<>();
        for (Argument argument : befund.statement().arguments()) {
            Mention mention = mentions.get(argument.mention());
            if (mention == null) {
                continue;
            }
            participants.add(mention.text());
            if (argument.role() == Role.ACTOR || argument.role() == Role.BEARER) {
                String head = mention.lemma() != null ? mention.lemma() : mention.headForm();
                heads.add(head.toLowerCase(Locale.GERMAN));
            }
        }
        String lemma = befund.statement().predicate().lemma() != null ? befund.statement().predicate().lemma()
                : befund.lemma();
        return new Corrections.View(lemma, befund.merkmale().negated() ? ParsedStatements.Negation.NEGATED
                : ParsedStatements.Negation.AFFIRMED, heads, participants);
    }

    private static ParsedStatements.Features features(StatementFeatures.Features merkmale) {
        return new ParsedStatements.Features(merkmale.negated() ? ParsedStatements.Negation.NEGATED
                : ParsedStatements.Negation.AFFIRMED, ParsedStatements.Modality.valueOf(merkmale.modality().name()),
                ParsedStatements.Temporality.valueOf(merkmale.temporality().name()));
    }
}
