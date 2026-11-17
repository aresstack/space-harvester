package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.application.check.CheckScope;
import com.aresstack.spaceharvester.application.check.Corrections;
import com.aresstack.spaceharvester.application.check.StatementCheck;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.Mention;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.Statement;
import com.aresstack.spaceharvester.nlp.extraction.StatementQuality;
import com.aresstack.spaceharvester.nlp.pruefstand.Pruefschritt.Aussage;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.syntax.Component;
import com.aresstack.spaceharvester.nlp.syntax.ConllU;
import com.aresstack.spaceharvester.nlp.syntax.DocumentRef;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prüfschritt auf dem Prüfstand (ADR 0032, FL4), ohne Modell. Zwei Teile, genau festgehalten in {@code pruefung.tsv}
 * (in beide Richtungen wie {@code stand.tsv}):
 *
 * <ol>
 *     <li><b>Auswahl</b> je Textsatz, für beide Auswahlen des Auftrags ({@link CheckScope}): geprüfte Aussagen, davon
 *     falsch nach der Vorgabe, davon „falsch trotz PLAUSIBLE“ (die ein Modell überhaupt nur finden kann, wenn die
 *     Auswahl sie zeigt), und unsichere, aber richtige Aussagen (die ein STIMMT bekämen; ob man sie nutzt, entscheiden
 *     FL6/FL7). Das ist eine Eigenschaft der Auswahl, kein Modell ist beteiligt.</li>
 *     <li><b>Handantworten</b> ({@code <Textsatz>/pruefung/antworten-hand.tsv}, nur Entwicklungssatz): von Hand
 *     gepinnte Antworten gehen durch das strenge Lesen des Core ({@link StatementCheck#parse}), Korrekturen durch den
 *     Wortabgleich, dann als gepinnter Parse ({@code pruefung/korrektur/<Text>.conllu}, eine Zeile je Korrektur in
 *     Dateireihenfolge, von {@code PruefstandLiveTest} mit der echten Kette erzeugt) durch {@link Corrections#accept}.
 *     Das belegt nur, dass Lesen, Abgleich und Zusammenführen stimmen, <b>nicht</b> die Güte eines Modells; die
 *     Anweisung v1 ist ungemessen, bis sie mit einem echten Modell läuft.</li>
 * </ol>
 */
class PruefschrittTest {

    private static final List<CheckScope> AUSWAHLEN = List.of(CheckScope.UNCERTAIN_AND_STAGE, CheckScope.ALL);

    /** Zahlen je Textsatz und Auswahl. */
    static final class Zahlen {
        int geprueft;
        int falsch;
        int falschTrotzPlausibel;
        int unsicherRichtig;
        int antworten;
        int gefunden;
        int faelschlichAbgewertet;
        int uebersehen;
        int stimmtUnsicherRichtig;
        int korrekturen;
        int wortabgleich;
        int uebernommen;
        int gueltig;

        int[] werte() {
            return new int[] {geprueft, falsch, falschTrotzPlausibel, unsicherRichtig, antworten, gefunden,
                faelschlichAbgewertet, uebersehen, stimmtUnsicherRichtig, korrekturen, wortabgleich, uebernommen,
                gueltig};
        }
    }

    @Test
    void auswahlUndHandantwortenHalten() throws IOException {
        Map<String, int[]> soll = soll();
        List<String> abweichungen = new ArrayList<>();
        StringBuilder bericht = new StringBuilder("# Prüfschritt auf dem Prüfstand (ADR 0032, FL4)\n\n"
                + "Variante " + Pruefschritt.VARIANTE + ". Handantworten belegen nur Lesen, Abgleich und "
                + "Zusammenführen, nicht die Güte eines Modells; Anweisung v1 ungemessen.\n\n"
                + "| Textsatz | Auswahl | geprüft | falsch | falsch trotz PLAUSIBLE | unsicher richtig | Antworten "
                + "| gefunden | fälschlich abgewertet | übersehen | STIMMT unsicher richtig | Korrekturen "
                + "| Wortabgleich | übernommen | gültig |\n|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder entwurf = new StringBuilder("# Text\tSatz\tAussage\tAntwort (Entwurf aus der Vorgabe)\n");
        List<String> befunde = new ArrayList<>();
        for (Textsatz satz : Textsatz.alle()) {
            Map<String, String> antworten = antworten(satz);
            Map<CheckScope, Zahlen> zahlen = new LinkedHashMap<>();
            AUSWAHLEN.forEach(scope -> zahlen.put(scope, new Zahlen()));
            for (Text text : satz.texte()) {
                List<String> korrekturen = korrekturen(antworten, text.name());
                KorrekturParse parse = korrekturParse(satz, text, korrekturen);
                List<List<Statement>> geparst = parse.zeilen();
                ObjectCentricAnalysis korrekturAnalyse = parse.analyse();
                Map<String, Mention> korrekturMentions = korrekturAnalyse == null ? Map.of()
                        : StatementQuality.mentions(korrekturAnalyse);
                for (Aussage aussage : Pruefschritt.aussagen(satz, text)) {
                    String schluessel = aussage.schluessel(text.name());
                    String antwort = antworten.get(schluessel);
                    if (antwort == null && aussage.in(CheckScope.ALL)) {
                        entwurf.append(schluessel).append('\t').append(aussage.richtig() ? "STIMMT" : "FALSCH")
                                .append("\t# ").append(aussage.satzText()).append('\n');
                    }
                    Bewertet bewertet = antwort == null ? null : bewerten(satz, text, aussage, antwort, korrekturen,
                            geparst, korrekturAnalyse, korrekturMentions, befunde);
                    for (CheckScope scope : AUSWAHLEN) {
                        if (!aussage.in(scope)) {
                            continue;
                        }
                        Zahlen z = zahlen.get(scope);
                        z.geprueft++;
                        z.falsch += aussage.richtig() ? 0 : 1;
                        z.falschTrotzPlausibel += aussage.plausibel() && !aussage.richtig() ? 1 : 0;
                        z.unsicherRichtig += !aussage.plausibel() && aussage.richtig() ? 1 : 0;
                        if (bewertet != null) {
                            bewertet.zaehlen(aussage, z);
                        }
                    }
                }
            }
            for (CheckScope scope : AUSWAHLEN) {
                int[] ist = zahlen.get(scope).werte();
                String key = satz.name() + "\t" + scope.value();
                bericht.append("| ").append(satz.name()).append(" | ").append(scope.value());
                Arrays.stream(ist).forEach(v -> bericht.append(" | ").append(v));
                bericht.append(" |\n");
                int[] erwartet = soll.get(key);
                if (erwartet == null) {
                    abweichungen.add("kein Eintrag für " + key.replace('\t', '/') + " in pruefung.tsv: "
                            + Arrays.toString(ist));
                } else if (!Arrays.equals(erwartet, ist)) {
                    abweichungen.add(key.replace('\t', '/') + ": soll " + Arrays.toString(erwartet) + ", ist "
                            + Arrays.toString(ist));
                }
            }
        }
        bericht.append("\n## Handantworten im Einzelnen\n\n");
        befunde.forEach(b -> bericht.append("- ").append(b).append('\n'));
        Path report = Path.of("build", "reports", "pruefstand", "pruefschritt.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.toString(), StandardCharsets.UTF_8);
        Files.writeString(report.resolveSibling("antworten-entwurf.tsv"), entwurf.toString(), StandardCharsets.UTF_8);
        assertTrue(abweichungen.isEmpty(), "Prüfschritt weicht von pruefung.tsv ab (schlechter = Rückschritt, "
                + "besser = im selben PR anheben; Bericht " + report + "): " + abweichungen);
    }

    /** Ergebnis einer Handantwort. */
    private record Bewertet(ParsedStatements.Verdict urteil, boolean korrektur, boolean wortabgleich,
                            boolean uebernommen, boolean gueltig) {

        void zaehlen(Aussage aussage, Zahlen z) {
            if (urteil == null) {
                return;
            }
            z.antworten++;
            boolean meldet = urteil != ParsedStatements.Verdict.STIMMT;
            boolean senkt = urteil == ParsedStatements.Verdict.FALSCH || uebernommen;
            z.gefunden += !aussage.richtig() && meldet ? 1 : 0;
            z.faelschlichAbgewertet += aussage.richtig() && senkt ? 1 : 0;
            z.uebersehen += !aussage.richtig() && !meldet ? 1 : 0;
            z.stimmtUnsicherRichtig += !aussage.plausibel() && aussage.richtig() && !meldet ? 1 : 0;
            z.korrekturen += korrektur ? 1 : 0;
            z.wortabgleich += wortabgleich ? 1 : 0;
            z.uebernommen += uebernommen ? 1 : 0;
            z.gueltig += gueltig ? 1 : 0;
        }
    }

    private static Bewertet bewerten(Textsatz satz, Text text, Aussage aussage, String antwort,
                                     List<String> korrekturen, List<List<Statement>> geparst,
                                     ObjectCentricAnalysis analyse, Map<String, Mention> mentions,
                                     List<String> befunde) {
        // Antwort wie vom Modell, durch das strenge Lesen des Core
        StatementCheck.Parsed parsed = StatementCheck.parse(aussage.satzText(), 1, "1|" + antwort);
        StatementCheck.Answer answer = parsed.answers().get(1);
        String prefix = text.name() + " Satz " + aussage.befund().satz() + " " + aussage.befund().kurz() + ": ";
        if (answer == null) {
            befunde.add(prefix + "Antwort außerhalb des Formats: " + antwort);
            return new Bewertet(null, false, false, false, false);
        }
        String roh = antwort.startsWith("UNVOLLSTAENDIG|") ? antwort.substring("UNVOLLSTAENDIG|".length()) : null;
        if (roh == null) {
            return new Bewertet(answer.verdict(), false, false, false, false);
        }
        if (answer.correction() == null) {
            befunde.add(prefix + "Korrektur fällt im Wortabgleich: „" + roh + "“");
            return new Bewertet(answer.verdict(), true, false, false, false);
        }
        int zeile = korrekturen.indexOf(roh);
        Statement kandidat = geparst.get(zeile).stream()
                .filter(s -> Corrections.sameVerb(aussage.view().lemma(), lemma(analyse, s))).findFirst().orElse(null);
        Bewertung.Befund kandidatBefund = kandidat == null ? null
                : Bewertung.befundZu(analyse, mentions, kandidat, aussage.befund().satz());
        Corrections.Outcome outcome = Corrections.accept(aussage.view(), kandidatBefund == null ? null
                : Pruefschritt.view(kandidatBefund, mentions), aussage.satzText());
        if (outcome != Corrections.Outcome.ACCEPTED) {
            befunde.add(prefix + "Korrektur nicht übernommen (" + outcome + "): „" + roh + "“");
            return new Bewertet(answer.verdict(), true, true, false, false);
        }
        boolean gueltig = Bewertung.richtig(satz, text, kandidatBefund);
        befunde.add(prefix + "Korrektur übernommen, " + (gueltig ? "gültig" : "entspricht keiner Erwartung") + ": "
                + kandidatBefund.kurz());
        return new Bewertet(answer.verdict(), true, true, true, gueltig);
    }

    private static String lemma(ObjectCentricAnalysis analyse, Statement statement) {
        String lemma = statement.predicate().lemma();
        return lemma != null ? lemma : analyse.document().sentences().get(statement.sentence())
                .token(statement.predicate().verb()).form();
    }

    /** Der gepinnte Parse der Korrekturen eines Textes: Analyse und je Korrektur (Zeile) ihre Aussagen. */
    private record KorrekturParse(ObjectCentricAnalysis analyse, List<List<Statement>> zeilen) {
    }

    private static KorrekturParse korrekturParse(Textsatz satz, Text text, List<String> korrekturen) {
        if (korrekturen.isEmpty()) {
            return new KorrekturParse(null, List.of());
        }
        String pfad = "pruefung/korrektur/" + text.name() + ".conllu";
        ParsedDocument document = ConllU.read(new DocumentRef("pruefstand", satz.name() + "/" + text.name(),
                "korrektur"), Textsatz.resource(Textsatz.BASIS + satz.name() + "/" + pfad),
                List.of(Component.gold("dependencies", pfad)));
        assertEquals(korrekturen.size(), document.sentences().size(), satz.name() + "/" + pfad
                + ": je Korrektur genau ein Satz (Pins neu mit PruefstandLiveTest)");
        ObjectCentricAnalysis analyse = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(document);
        List<List<Statement>> result = new ArrayList<>();
        for (int i = 0; i < korrekturen.size(); i++) {
            int index = i;
            result.add(analyse.statements().stream().filter(s -> s.sentence() == index).toList());
        }
        return new KorrekturParse(analyse, result);
    }

    /** Die Korrekturen eines Textes in Dateireihenfolge (die Eingabe des Korrektur-Pins). */
    static List<String> korrekturen(Map<String, String> antworten, String text) {
        List<String> result = new ArrayList<>();
        antworten.forEach((schluessel, antwort) -> {
            if (schluessel.startsWith(text + "\t") && antwort.startsWith("UNVOLLSTAENDIG|")) {
                String korrektur = antwort.substring("UNVOLLSTAENDIG|".length());
                if (!result.contains(korrektur)) {
                    result.add(korrektur);
                }
            }
        });
        return result;
    }

    /** Handantworten eines Textsatzes: Schlüssel (Text, Satz, Aussage) → Antwortzeile ohne Nummer. */
    static Map<String, String> antworten(Textsatz satz) {
        String content = Textsatz.optional(Textsatz.BASIS + satz.name() + "/pruefung/antworten-hand.tsv");
        Map<String, String> result = new LinkedHashMap<>();
        if (content == null) {
            return result;
        }
        for (String[] row : Textsatz.tsv(content)) {
            result.put(row[0] + "\t" + row[1] + "\t" + row[2], row[3]);
        }
        return result;
    }

    private static Map<String, int[]> soll() {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (String[] row : Textsatz.tsv(Textsatz.resource(Textsatz.BASIS + "pruefung.tsv"))) {
            result.put(row[0] + "\t" + row[1], Arrays.stream(row, 2, row.length).mapToInt(Integer::parseInt)
                    .toArray());
        }
        return result;
    }
}
