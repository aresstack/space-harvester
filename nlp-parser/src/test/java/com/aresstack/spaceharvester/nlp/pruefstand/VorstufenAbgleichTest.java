package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Zahlen;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
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
 * Wortabgleich des Core gegen die gepinnten Vorstufen (ADR 0032, FL3): jede zusammengesetzte Variante jedes
 * Textsatzes Abschnitt für Abschnitt durch {@code SentenceSimplifier.parse}, dazu die Zahlen des Prüfstands, wenn die
 * vom Core verworfenen Zeilen fehlen (die Zahl für den Betrieb). {@code abgleich.tsv} hält beides genau fest, in beide
 * Richtungen wie {@code stand.tsv}; die nicht durchgelassenen Zeilen stehen in
 * {@code build/reports/pruefstand/abgleich.md}.
 */
class VorstufenAbgleichTest {

    @Test
    void abgleichUndBetriebszahlenHalten() throws IOException {
        Map<String, int[]> soll = soll();
        List<String> abweichungen = new ArrayList<>();
        StringBuilder bericht = new StringBuilder("# Wortabgleich der Vorstufe gegen die gepinnten Vorstufen\n\n"
                + "| Textsatz | Variante | Zeilen | verworfen | ergänzt | unvollständig | verloren | vollständig "
                + "richtig | streng | falsch trotz PLAUSIBLE |\n|---|---|---|---|---|---|---|---|---|---|\n");
        List<String> abgelehnt = new ArrayList<>();
        int geprueft = 0;
        for (Textsatz satz : Textsatz.alle()) {
            for (Variante variante : satz.varianten()) {
                if (!variante.zusammengesetzt()) {
                    continue;
                }
                VorstufenAbgleich.Ergebnis gesamt = new VorstufenAbgleich.Ergebnis();
                Zahlen betrieb = new Zahlen();
                for (Text text : satz.texte()) {
                    VorstufenAbgleich.Ergebnis e = VorstufenAbgleich.abgleichen(satz, variante, text);
                    if (e == null) {
                        betrieb.add(Bewertung.bewerten(satz, variante, text));
                        continue;
                    }
                    gesamt.add(e);
                    betrieb.add(Bewertung.bewerten(satz, variante, text, e.durchgelassen::contains));
                }
                int[] ist = {gesamt.zeilen, gesamt.verworfen, gesamt.ergaenzt, gesamt.unvollstaendig, gesamt.verloren,
                    betrieb.richtig, betrieb.streng, betrieb.plausibelFalsch};
                String key = satz.name() + "\t" + variante.name();
                bericht.append("| ").append(satz.name()).append(" | ").append(variante.name());
                for (int value : ist) {
                    bericht.append(" | ").append(value);
                }
                bericht.append(" |\n");
                abgelehnt.addAll(gesamt.abgelehnt.stream().map(a -> variante.name() + ": " + a).toList());
                int[] erwartet = soll.get(key);
                if (erwartet == null) {
                    abweichungen.add("kein Eintrag für " + key.replace('\t', '/') + " in abgleich.tsv: "
                            + java.util.Arrays.toString(ist));
                    continue;
                }
                geprueft++;
                if (!java.util.Arrays.equals(erwartet, ist)) {
                    abweichungen.add(key.replace('\t', '/') + ": soll " + java.util.Arrays.toString(erwartet)
                            + ", ist " + java.util.Arrays.toString(ist));
                }
            }
        }
        bericht.append("\n## Nicht durchgelassene Zeilen\n\n");
        abgelehnt.forEach(a -> bericht.append("- ").append(a).append('\n'));
        Path report = Path.of("build", "reports", "pruefstand", "abgleich.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.toString(), StandardCharsets.UTF_8);
        assertTrue(abweichungen.isEmpty(), "Abgleich weicht von abgleich.tsv ab (schlechter = Rückschritt, besser = "
                + "anheben):\n" + String.join("\n", abweichungen));
        assertEquals(soll.size(), geprueft, "jede Zeile in abgleich.tsv gehört zu einer Variante");
    }

    private static Map<String, int[]> soll() {
        Map<String, int[]> soll = new LinkedHashMap<>();
        for (String[] row : Textsatz.tsv(Textsatz.resource(Textsatz.BASIS + "abgleich.tsv"))) {
            int[] values = new int[row.length - 2];
            for (int i = 2; i < row.length; i++) {
                values[i - 2] = Integer.parseInt(row[i].trim());
            }
            soll.put(row[0] + "\t" + row[1], values);
        }
        return soll;
    }
}
