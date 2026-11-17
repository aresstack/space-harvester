package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.nlp.extraction.Argument;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Role;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.VorstufenSatz;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Beteiligter;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Erwartung;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Lesart;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Rolle;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.syntax.Sentence;
import com.aresstack.spaceharvester.nlp.syntax.Token;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bewertet die Aussagen einer Variante gegen die Handvorgabe (ADR 0032, FL0). „Vollständig richtig“ heißt: Verb
 * gefunden und Handelnder, Betroffenes, Empfänger stimmen genau (nichts fehlt, nichts Falsches; Umstände, Zeit und
 * Mittel zählen nicht). Ein Verb gilt als gefunden bei gleicher Grundform oder gleichem Stamm ({@code abruft} für
 * {@code abrufen}); „Grundform richtig“ zählt nur die gleiche Grundform. „Falsch trotz PLAUSIBLE“ sind Aussagen der
 * Stufe PLAUSIBLE, die keiner Erwartung desselben Originalsatzes vollständig entsprechen.
 *
 * <p>Für Vorstufen zusätzlich: Beteiligte, deren Kopf nicht im Originalsatz steht (Ergänzung), getrennt nach „aus
 * Überschrift/Kennung des Abschnitts“, „aus einem früheren Satz des Abschnitts“ und „sonst“.
 */
final class Bewertung {

    private static final Map<Role, Rolle> ROLLEN = Map.of(Role.ACTOR, Rolle.HANDELNDER, Role.AFFECTED_OBJECT,
            Rolle.BETROFFENES, Role.RECIPIENT, Rolle.EMPFAENGER);

    private Bewertung() {
    }

    /** Zahlen eines Textes in einer Variante. */
    static final class Zahlen {
        int erwartet;
        int verbGefunden;
        int grundformRichtig;
        int richtig;
        int streng;
        int plausibel;
        int plausibelFalsch;
        int ergaenztKennung;
        int ergaenztAbschnitt;
        int ergaenztSonst;
        int merkmaleVorgegeben;
        /** Je Merkmal (Verneinung, Verbindlichkeit, Zeitbezug), nur Erwartungen mit gefundenem Verb. */
        final int[] merkmalVorgegeben = new int[3];
        final int[] merkmalRichtig = new int[3];
        final int[] merkmalOhne = new int[3];
        final int[] merkmalFaelschlich = new int[3];
        final List<String> fehler = new ArrayList<>();

        void add(Zahlen other) {
            erwartet += other.erwartet;
            verbGefunden += other.verbGefunden;
            grundformRichtig += other.grundformRichtig;
            richtig += other.richtig;
            streng += other.streng;
            plausibel += other.plausibel;
            plausibelFalsch += other.plausibelFalsch;
            ergaenztKennung += other.ergaenztKennung;
            ergaenztAbschnitt += other.ergaenztAbschnitt;
            ergaenztSonst += other.ergaenztSonst;
            merkmaleVorgegeben += other.merkmaleVorgegeben;
            for (int i = 0; i < 3; i++) {
                merkmalVorgegeben[i] += other.merkmalVorgegeben[i];
                merkmalRichtig[i] += other.merkmalRichtig[i];
                merkmalOhne[i] += other.merkmalOhne[i];
                merkmalFaelschlich[i] += other.merkmalFaelschlich[i];
            }
            fehler.addAll(other.fehler);
        }

        int merkmaleRichtig() {
            return merkmalRichtig[0] + merkmalRichtig[1] + merkmalRichtig[2];
        }

        int merkmaleFaelschlich() {
            return merkmalFaelschlich[0] + merkmalFaelschlich[1] + merkmalFaelschlich[2];
        }

        int fehlt() {
            return erwartet - verbGefunden;
        }
    }

    /** Eine erzeugte Aussage mit Originalsatz und den Namen ihrer Beteiligten. */
    private record Befund(Statement statement, int satz, String lemma, StatementQuality.Level stufe,
                          Map<Rolle, List<Set<String>>> rollen, Set<String> alleNamen, String kurz,
                          StatementFeatures.Features merkmale) {
    }

    static Zahlen bewerten(Textsatz satz, Variante variante, Text text) {
        ParsedDocument original = satz.analyse(satz.varianten().get(0), text);
        ParsedDocument document = satz.analyse(variante, text);
        List<VorstufenSatz> vorstufe = satz.vorstufe(variante, text);
        if (vorstufe != null && vorstufe.size() != document.sentences().size()) {
            throw new IllegalStateException(satz.name() + "/" + variante.name() + "/" + text.name() + ": "
                    + vorstufe.size() + " Vorstufen-Zeilen, aber " + document.sentences().size() + " Sätze im Pin");
        }
        ObjectCentricAnalysis analysis = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(document);
        Map<String, Mention> mentions = StatementQuality.mentions(analysis);
        List<Befund> befunde = new ArrayList<>();
        for (Statement statement : analysis.statements()) {
            int origin = vorstufe == null ? statement.sentence() + 1 : vorstufe.get(statement.sentence()).originalsatz();
            befunde.add(befund(analysis, mentions, statement, origin));
        }

        Zahlen zahlen = new Zahlen();
        String prefix = variante.name() + " " + text.name();
        for (Erwartung erwartung : satz.vorgabe().fuer(text.name())) {
            zahlen.erwartet++;
            if (erwartung.lesarten().stream().anyMatch(l -> l.verneint() || !"-".equals(l.verbindlichkeit())
                    || !"GILT".equals(l.zeitbezug()))) {
                zahlen.merkmaleVorgegeben++;
            }
            boolean verb = false;
            boolean grundform = false;
            boolean richtig = false;
            boolean streng = false;
            boolean[] merkmal = new boolean[3];
            List<String> merkmalIst = new ArrayList<>();
            List<String> gesehen = new ArrayList<>();
            for (Lesart lesart : erwartung.lesarten()) {
                for (Befund befund : befunde) {
                    if (befund.satz() != erwartung.satz() || !verbPasst(lesart.verb(), befund.lemma())) {
                        continue;
                    }
                    verb = true;
                    grundform |= lesart.verb().equals(befund.lemma());
                    richtig |= rollenPassen(lesart, befund, false);
                    streng |= rollenPassen(lesart, befund, true);
                    // Merkmale an jeder Aussage mit passendem Verb, unabhängig von den Rollen
                    StatementFeatures.Features m = befund.merkmale();
                    merkmal[0] |= m.negated() == lesart.verneint();
                    merkmal[1] |= verbindlichkeit(m).equals(lesart.verbindlichkeit());
                    merkmal[2] |= m.temporality().name().equals(lesart.zeitbezug());
                    merkmalIst.add((m.negated() ? "verneint " : "") + verbindlichkeit(m) + " " + m.temporality());
                    gesehen.add(befund.kurz());
                }
            }
            zahlen.verbGefunden += verb ? 1 : 0;
            zahlen.grundformRichtig += grundform ? 1 : 0;
            zahlen.richtig += richtig ? 1 : 0;
            zahlen.streng += streng ? 1 : 0;
            if (verb) {
                Lesart erste = erwartung.lesarten().get(0);
                boolean[] vorgegeben = {erste.verneint(), !"-".equals(erste.verbindlichkeit()),
                    !"GILT".equals(erste.zeitbezug())};
                for (int i = 0; i < 3; i++) {
                    if (vorgegeben[i]) {
                        zahlen.merkmalVorgegeben[i]++;
                        zahlen.merkmalRichtig[i] += merkmal[i] ? 1 : 0;
                    } else {
                        zahlen.merkmalOhne[i]++;
                        zahlen.merkmalFaelschlich[i] += merkmal[i] ? 0 : 1;
                    }
                }
                if (!(merkmal[0] && merkmal[1] && merkmal[2])) {
                    zahlen.fehler.add(prefix + " " + erwartung.id() + " (" + erste.verb() + "): Merkmale ist "
                            + String.join(" / ", merkmalIst) + ", soll " + (erste.verneint() ? "verneint " : "")
                            + erste.verbindlichkeit() + " " + erste.zeitbezug());
                }
            }
            if (!richtig) {
                zahlen.fehler.add(prefix + " " + erwartung.id() + " (Satz " + erwartung.satz() + ", "
                        + erwartung.lesarten().get(0).verb() + "): "
                        + (verb ? "Beteiligte " + String.join(" / ", gesehen) : "Verb fehlt"));
            } else if (!streng) {
                zahlen.fehler.add(prefix + " " + erwartung.id() + ": nur nicht-streng richtig (ergänzt) "
                        + String.join(" / ", gesehen));
            }
        }

        for (Befund befund : befunde) {
            if (befund.stufe() != StatementQuality.Level.PLAUSIBLE) {
                continue;
            }
            zahlen.plausibel++;
            boolean passt = satz.vorgabe().fuer(text.name()).stream()
                    .filter(e -> e.satz() == befund.satz())
                    .flatMap(e -> e.lesarten().stream())
                    .anyMatch(l -> verbPasst(l.verb(), befund.lemma()) && rollenPassen(l, befund, false));
            if (!passt) {
                zahlen.plausibelFalsch++;
                zahlen.fehler.add(prefix + " PLAUSIBLE, aber falsch (Satz " + befund.satz() + "): " + befund.kurz());
            }
        }

        if (vorstufe != null) {
            ergaenzungen(original, analysis, mentions, befunde, zahlen, prefix);
        }
        return zahlen;
    }

    // ---- Abgleich ----------------------------------------------------------------------------------------------

    /** Gleiche Grundform oder gleicher Stamm ({@code abruft} zu {@code abrufen}, {@code vorliegt} zu {@code vorliegen}). */
    static boolean verbPasst(String erwartet, String lemma) {
        if (lemma == null) {
            return false;
        }
        if (erwartet.equals(lemma)) {
            return true;
        }
        String stamm = erwartet.endsWith("en") ? erwartet.substring(0, erwartet.length() - 2)
                : erwartet.endsWith("n") ? erwartet.substring(0, erwartet.length() - 1) : erwartet;
        return stamm.length() >= 3 && lemma.startsWith(stamm) && lemma.length() - stamm.length() <= 2;
    }

    private static boolean rollenPassen(Lesart lesart, Befund befund, boolean streng) {
        for (Rolle rolle : Rolle.values()) {
            List<Set<String>> tatsaechlich = new ArrayList<>(befund.rollen().getOrDefault(rolle, List.of()));
            for (Beteiligter erwartet : lesart.rollen().get(rolle)) {
                int treffer = -1;
                for (int i = 0; i < tatsaechlich.size() && treffer < 0; i++) {
                    if (tatsaechlich.get(i).stream().anyMatch(erwartet::passt)) {
                        treffer = i;
                    }
                }
                if (treffer >= 0) {
                    tatsaechlich.remove(treffer);
                    if (streng && erwartet.vertretbar()) {
                        return false;
                    }
                } else if (!erwartet.vertretbar()
                        && erwartet.auchAlsErgaenzung().stream().noneMatch(befund.alleNamen()::contains)) {
                    return false;
                }
            }
            if (!tatsaechlich.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static Befund befund(ObjectCentricAnalysis analysis, Map<String, Mention> mentions, Statement statement,
                                 int satz) {
        Map<Rolle, List<Set<String>>> rollen = new EnumMap<>(Rolle.class);
        Set<String> alle = new HashSet<>();
        StringBuilder kurz = new StringBuilder();
        String lemma = statement.predicate().lemma();
        if (lemma == null) {
            lemma = analysis.document().sentences().get(statement.sentence()).token(statement.predicate().verb())
                    .form();
        }
        lemma = lemma.toLowerCase(Locale.ROOT);
        kurz.append(lemma).append('(');
        for (Argument argument : statement.arguments()) {
            Set<String> namen = namen(mentions.get(argument.mention()));
            alle.addAll(namen);
            Rolle rolle = ROLLEN.get(argument.role());
            if (rolle != null) {
                rollen.computeIfAbsent(rolle, r -> new ArrayList<>()).add(namen);
                kurz.append(kurz.charAt(kurz.length() - 1) == '(' ? "" : ", ").append(rolle.name().charAt(0))
                        .append('=').append(mentions.get(argument.mention()).headForm());
            }
        }
        kurz.append(')');
        StatementQuality.Level stufe = StatementQuality.assess(mentions, analysis, statement).level();
        kurz.append(' ').append(stufe);
        return new Befund(statement, satz, lemma, stufe, rollen, alle, kurz.toString(),
                StatementFeatures.assess(mentions, analysis, statement));
    }

    private static String verbindlichkeit(StatementFeatures.Features merkmale) {
        return merkmale.modality() == StatementFeatures.Modality.NONE ? "-" : merkmale.modality().name();
    }

    /** Vergleichsnamen eines Beteiligten: Form und Grundform des Kopfs in Kleinschreibung. */
    private static Set<String> namen(Mention mention) {
        Set<String> namen = new LinkedHashSet<>();
        if (mention != null && mention.kind() == Mention.Kind.CLAUSE) {
            // Teilsatz als Beteiligter: „§“ + Grundform des Kopfs (Schreibweise der Vorgabe)
            namen.add("§" + (mention.lemma() != null ? mention.lemma() : mention.headForm()).toLowerCase(Locale.ROOT));
            return namen;
        }
        if (mention != null) {
            namen.add(mention.headForm().toLowerCase(Locale.ROOT));
            if (mention.lemma() != null) {
                namen.add(mention.lemma().toLowerCase(Locale.ROOT));
            }
        }
        return namen;
    }

    // ---- Ergänzungen der Vorstufe --------------------------------------------------------------------------------

    private static void ergaenzungen(ParsedDocument original, ObjectCentricAnalysis analysis,
                                     Map<String, Mention> mentions, List<Befund> befunde, Zahlen zahlen,
                                     String prefix) {
        Set<String> gezaehlt = new HashSet<>();
        for (Befund befund : befunde) {
            for (Argument argument : befund.statement().arguments()) {
                if (!ROLLEN.containsKey(argument.role()) || !gezaehlt.add(argument.mention())) {
                    continue;
                }
                Mention mention = mentions.get(argument.mention());
                Set<String> namen = namen(mention);
                int index = befund.satz() - 1;
                if (index < 0 || index >= original.sentences().size()) {
                    continue;
                }
                if (enthaelt(woerter(original.sentences().get(index)), namen)) {
                    continue;
                }
                int start = abschnittsbeginn(original, index);
                Set<String> kennung = new HashSet<>();
                Set<String> frueher = new HashSet<>();
                for (int j = start; j <= index; j++) {
                    Sentence sentence = original.sentences().get(j);
                    if (ueberschrift(sentence)) {
                        kennung.addAll(woerter(sentence));
                    } else {
                        kennung.addAll(kennung(sentence));
                        if (j < index) {
                            frueher.addAll(woerter(sentence));
                        }
                    }
                }
                String art;
                if (enthaelt(kennung, namen)) {
                    zahlen.ergaenztKennung++;
                    art = "aus Überschrift/Kennung";
                } else if (enthaelt(frueher, namen)) {
                    zahlen.ergaenztAbschnitt++;
                    art = "aus früherem Satz des Abschnitts";
                } else {
                    zahlen.ergaenztSonst++;
                    art = "sonst";
                }
                zahlen.fehler.add(prefix + " ergänzt " + art + " (Satz " + befund.satz() + "): "
                        + mention.headForm() + " in " + befund.kurz());
            }
        }
    }

    private static boolean enthaelt(Set<String> woerter, Set<String> namen) {
        return namen.stream().anyMatch(woerter::contains);
    }

    private static Set<String> woerter(Sentence sentence) {
        Set<String> result = new HashSet<>();
        for (Token token : sentence.tokens()) {
            result.add(token.form().toLowerCase(Locale.ROOT));
            if (token.lemma() != null) {
                result.add(token.lemma().toLowerCase(Locale.ROOT));
            }
        }
        return result;
    }

    /** Überschrift: Zeile ohne Satzzeichen am Ende, kein Stichpunkt ({@code Antragsprüfung}, {@code Prüfschritte:}). */
    private static boolean ueberschrift(Sentence sentence) {
        String text = sentence.text().strip();
        return !text.isEmpty() && !text.startsWith("-") && ".!?".indexOf(text.charAt(text.length() - 1)) < 0;
    }

    /** Kennung am Zeilenanfang ({@code Zuständigkeit:}): Wörter vor dem ersten Doppelpunkt unter den ersten vier Tokens. */
    private static Set<String> kennung(Sentence sentence) {
        Set<String> result = new HashSet<>();
        List<Token> tokens = sentence.tokens();
        for (int i = 0; i < Math.min(4, tokens.size()); i++) {
            if (":".equals(tokens.get(i).form())) {
                for (int j = 0; j < i; j++) {
                    result.add(tokens.get(j).form().toLowerCase(Locale.ROOT));
                    if (tokens.get(j).lemma() != null) {
                        result.add(tokens.get(j).lemma().toLowerCase(Locale.ROOT));
                    }
                }
                break;
            }
        }
        return result;
    }

    /** Beginn des Abschnitts: letzte Überschrift ohne Doppelpunkt am Ende bis einschließlich {@code index}, sonst 0. */
    private static int abschnittsbeginn(ParsedDocument original, int index) {
        for (int j = index; j >= 0; j--) {
            Sentence sentence = original.sentences().get(j);
            if (ueberschrift(sentence) && !sentence.text().strip().endsWith(":")) {
                return j;
            }
        }
        return 0;
    }
}
