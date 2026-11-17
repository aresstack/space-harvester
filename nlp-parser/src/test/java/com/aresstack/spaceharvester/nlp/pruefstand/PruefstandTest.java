package com.aresstack.spaceharvester.nlp.pruefstand;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Zahlen;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prüfstand der Aussagen-Erkennung (ADR 0032, FL0) auf den gepinnten Analysen: bewertet jede Variante jedes
 * Textsatzes gegen die Handvorgabe, schreibt den Bericht nach {@code build/reports/pruefstand/bericht.md} und hält
 * den Stand aus {@code stand.tsv} genau fest: „vollständig richtig“, „streng“ und „falsch trotz PLAUSIBLE“ müssen
 * stimmen. Wird es schlechter, ist das ein Rückschritt; wird es besser, hebt derselbe PR die Werte in
 * {@code stand.tsv} an (sonst bliebe eine alte Grenze stehen und ein späterer Rückschritt fiele nicht auf).
 */
class PruefstandTest {

    @Test
    void standHaeltOderWirdBesser() throws IOException {
        Bericht bericht = new Bericht();
        List<String> verstoesse = new ArrayList<>();
        List<String> besser = new ArrayList<>();
        Map<String, int[]> stand = stand();
        int geprueft = 0;
        for (Textsatz satz : Textsatz.alle()) {
            Map<String, Zahlen> zahlen = bericht.textsatz(satz);
            for (Map.Entry<String, Zahlen> entry : zahlen.entrySet()) {
                String key = satz.name() + "\t" + entry.getKey();
                int[] soll = stand.get(key);
                Zahlen ist = entry.getValue();
                if (soll == null) {
                    verstoesse.add("kein Stand für " + key.replace('\t', '/') + " in stand.tsv");
                    continue;
                }
                geprueft++;
                vergleiche(key, "vollständig richtig", ist.richtig, soll[0], true, verstoesse, besser);
                vergleiche(key, "streng", ist.streng, soll[1], true, verstoesse, besser);
                vergleiche(key, "falsch trotz PLAUSIBLE", ist.plausibelFalsch, soll[2], false, verstoesse, besser);
            }
        }
        Path report = Path.of("build", "reports", "pruefstand", "bericht.md");
        Files.createDirectories(report.getParent());
        Files.writeString(report, bericht.text(), StandardCharsets.UTF_8);
        System.out.println(bericht.kurz());
        assertTrue(verstoesse.isEmpty(), "Prüfstand schlechter als stand.tsv:\n" + String.join("\n", verstoesse)
                + "\n\n" + bericht.kurz());
        assertTrue(besser.isEmpty(), "Prüfstand besser als stand.tsv, bitte anheben:\n" + String.join("\n", besser)
                + "\n\n" + bericht.kurz());
        assertEquals(stand.size(), geprueft, "jede Zeile in stand.tsv gehört zu einer Variante");
    }

    @Test
    void vorgabeIstVollstaendig() {
        for (Textsatz satz : Textsatz.alle()) {
            for (Textsatz.Text text : satz.texte()) {
                assertTrue(!satz.vorgabe().fuer(text.name()).isEmpty(), satz.name() + ": keine Vorgabe für " + text);
            }
            assertTrue(satz.vorgabe().erwartungen().stream().allMatch(e -> satz.texte().stream()
                    .anyMatch(t -> t.name().equals(e.text()))), satz.name() + ": Vorgabe für unbekannten Text");
        }
    }

    @Test
    void verbabgleichNachStamm() {
        assertTrue(Bewertung.verbPasst("abrufen", "abrufen"));
        assertTrue(Bewertung.verbPasst("abrufen", "abruft"));
        assertTrue(Bewertung.verbPasst("vorliegen", "vorliegt"));
        assertTrue(!Bewertung.verbPasst("verwerfen", "verwarfn"));
        assertTrue(!Bewertung.verbPasst("stellen", "feststellen"));
        assertTrue(!Bewertung.verbPasst("prüfen", null));
    }

    private static void vergleiche(String key, String kennzahl, int ist, int soll, boolean hoeherBesser,
                                   List<String> verstoesse, List<String> besser) {
        String name = key.replace('\t', '/') + " " + kennzahl + ": " + ist + " (Stand " + soll + ")";
        if (hoeherBesser ? ist < soll : ist > soll) {
            verstoesse.add(name);
        } else if (ist != soll) {
            besser.add(name);
        }
    }

    /** {@code stand.tsv}: Textsatz, Variante, richtig (min), streng (min), falsch trotz PLAUSIBLE (max). */
    private static Map<String, int[]> stand() {
        Map<String, int[]> result = new java.util.LinkedHashMap<>();
        for (String line : Textsatz.resource(Textsatz.BASIS + "stand.tsv").split("\r?\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            result.put(f[0] + "\t" + f[1], new int[] {Integer.parseInt(f[2]), Integer.parseInt(f[3]),
                    Integer.parseInt(f[4])});
        }
        return result;
    }
}
