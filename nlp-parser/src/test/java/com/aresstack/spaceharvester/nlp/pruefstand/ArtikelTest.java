package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.extraction.StatementFeatures;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Erwartung;
import com.aresstack.spaceharvester.nlp.pruefstand.Vorgabe.Lesart;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Begleiter des Rollennomens (ADR 0032, Merkmal Artikel) gegen die Handvorgabe {@code artikel.tsv} je Textsatz, auf der
 * Variante „heute“ (zusammengesetzte Varianten übernehmen die Merkmale aus dem Original). Gemessen an jeder Aussage mit
 * passendem Verb (Rollennomen) im erwarteten Satz. Genau festgehalten in {@code artikel-stand.tsv}: schlechter =
 * Rückschritt, besser = im selben PR anheben. Der Bericht nennt bei Abweichungen nur die Kennung (Kontrollsatz: nur
 * messen, keine Analyse ansehen).
 */
class ArtikelTest {

    record Zahlen(int vorgegeben, int richtig, int abweichend, int ohneAussage, List<String> abweichungen) {
    }

    @Test
    void artikelHaeltOderWirdBesser() throws IOException {
        Map<String, int[]> stand = stand();
        StringBuilder bericht = new StringBuilder("# Begleiter des Rollennomens (heute)\n\n"
                + "| Textsatz | vorgegeben | richtig | abweichend | ohne Aussage | Kennungen abweichend/ohne Aussage |\n"
                + "|---|---|---|---|---|---|\n");
        List<String> verstoesse = new ArrayList<>();
        int geprueft = 0;
        for (Textsatz satz : Textsatz.alle()) {
            String inhalt = Textsatz.optional(Textsatz.BASIS + satz.name() + "/artikel.tsv");
            if (inhalt == null) {
                continue;
            }
            Zahlen ist = messen(satz, inhalt);
            bericht.append("| ").append(satz.name()).append(" | ").append(ist.vorgegeben()).append(" | ")
                    .append(ist.richtig()).append(" | ").append(ist.abweichend()).append(" | ")
                    .append(ist.ohneAussage()).append(" | ").append(String.join(", ", ist.abweichungen()))
                    .append(" |\n");
            int[] soll = stand.get(satz.name());
            if (soll == null) {
                verstoesse.add("kein Stand für " + satz.name() + " in artikel-stand.tsv");
                continue;
            }
            geprueft++;
            int[] werte = {ist.vorgegeben(), ist.richtig(), ist.abweichend(), ist.ohneAussage()};
            for (int i = 0; i < werte.length; i++) {
                if (werte[i] != soll[i]) {
                    verstoesse.add(satz.name() + ": soll " + java.util.Arrays.toString(soll) + ", ist "
                            + java.util.Arrays.toString(werte) + " (abweichend/ohne Aussage: "
                            + String.join(", ", ist.abweichungen()) + ")");
                    break;
                }
            }
        }
        Path report = Path.of("build", "reports", "pruefstand", "artikel.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.toString(), StandardCharsets.UTF_8);
        assertTrue(verstoesse.isEmpty(), "Artikel weicht von artikel-stand.tsv ab (schlechter = Rückschritt, "
                + "besser = anheben):\n" + String.join("\n", verstoesse));
        assertEquals(stand.size(), geprueft, "jede Zeile in artikel-stand.tsv gehört zu einem Textsatz");
    }

    private static Zahlen messen(Textsatz satz, String inhalt) {
        Map<String, Erwartung> erwartungen = new LinkedHashMap<>();
        satz.vorgabe().erwartungen().forEach(e -> erwartungen.put(e.id(), e));
        Map<String, Bewertung.Aufbau> aufbau = new LinkedHashMap<>();
        int vorgegeben = 0;
        int richtig = 0;
        int abweichend = 0;
        int ohne = 0;
        List<String> abweichungen = new ArrayList<>();
        for (String[] row : Textsatz.tsv(inhalt)) {
            Erwartung erwartung = erwartungen.get(row[0]);
            if (erwartung == null) {
                throw new IllegalStateException(satz.name() + "/artikel.tsv: unbekannte Kennung " + row[0]);
            }
            StatementFeatures.Article soll = StatementFeatures.Article.valueOf(row[1].trim());
            vorgegeben++;
            Textsatz.Text text = satz.texte().stream().filter(t -> t.name().equals(erwartung.text())).findFirst()
                    .orElseThrow();
            Bewertung.Aufbau a = aufbau.computeIfAbsent(text.name(),
                    n -> Bewertung.aufbauen(satz, satz.varianten().get(0), text, zeile -> true));
            boolean gefunden = false;
            boolean passt = false;
            for (Lesart lesart : erwartung.lesarten()) {
                for (Bewertung.Befund befund : a.befunde()) {
                    if (befund.satz() == erwartung.satz() && Bewertung.verbPasst(lesart.verb(), befund.lemma())) {
                        gefunden = true;
                        passt |= befund.merkmale().article() == soll;
                    }
                }
            }
            if (!gefunden) {
                ohne++;
                abweichungen.add(erwartung.id() + " (ohne Aussage)");
            } else if (passt) {
                richtig++;
            } else {
                abweichend++;
                abweichungen.add(erwartung.id());
            }
        }
        return new Zahlen(vorgegeben, richtig, abweichend, ohne, abweichungen);
    }

    /** {@code artikel-stand.tsv}: Textsatz, vorgegeben, richtig, abweichend, ohne Aussage. */
    private static Map<String, int[]> stand() {
        Map<String, int[]> result = new LinkedHashMap<>();
        for (String[] f : Textsatz.tsv(Textsatz.resource(Textsatz.BASIS + "artikel-stand.tsv"))) {
            result.put(f[0], new int[] {Integer.parseInt(f[1]), Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4])});
        }
        return result;
    }
}
