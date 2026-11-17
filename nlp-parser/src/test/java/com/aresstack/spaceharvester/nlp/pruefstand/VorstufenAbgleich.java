package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.application.simplification.SentenceSimplifier;
import com.aresstack.spaceharvester.application.simplification.SimplificationSection;
import com.aresstack.spaceharvester.application.simplification.SimplifiedSentence;
import com.aresstack.spaceharvester.nlp.extraction.ExtractionSettings;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricAnalysis;
import com.aresstack.spaceharvester.nlp.extraction.ObjectCentricExtraction;
import com.aresstack.spaceharvester.nlp.extraction.SimplificationSelection;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.VorstufenSatz;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schickt die gepinnten Vorstufen einer zusammengesetzten Variante Abschnitt für Abschnitt durch den Wortabgleich des
 * Core ({@link SentenceSimplifier#parse}), mit den Abschnitten aus {@link SimplificationSelection#sections} und
 * Nummern wie beim Modellaufruf (ab 1 je Abschnitt). So zeigt sich, wie streng der Abgleich gegen echte Vorstufen ist
 * und welche Zeilen im Betrieb fehlen würden (ADR 0032, FL3).
 */
final class VorstufenAbgleich {

    private VorstufenAbgleich() {
    }

    /** Zahlen des Abgleichs und die durchgelassenen Vorstufen-Zeilen (Index ab 0). */
    static final class Ergebnis {
        int zeilen;
        int verworfen;
        int ergaenzt;
        int unvollstaendig;
        int verloren;
        final Set<Integer> durchgelassen = new HashSet<>();
        /** Nicht durchgelassene Zeilen zu gewählten Sätzen, zum Ansehen: Text und Grund. */
        final List<String> abgelehnt = new ArrayList<>();

        void add(Ergebnis other) {
            zeilen += other.zeilen;
            verworfen += other.verworfen;
            ergaenzt += other.ergaenzt;
            unvollstaendig += other.unvollstaendig;
            verloren += other.verloren;
            abgelehnt.addAll(other.abgelehnt);
        }
    }

    /** Abgleich eines Textes in einer zusammengesetzten Variante oder {@code null}, wenn sie den Text nicht umformt. */
    static Ergebnis abgleichen(Textsatz satz, Variante variante, Text text) {
        List<VorstufenSatz> vorstufe = satz.vorstufe(variante, text);
        if (vorstufe == null) {
            return null;
        }
        ParsedDocument original = satz.analyse(satz.varianten().get(0), text);
        ObjectCentricAnalysis heute = new ObjectCentricExtraction(ExtractionSettings.defaults()).analyze(original);
        Map<Integer, Set<SimplificationSelection.Reason>> auswahl = SimplificationSelection.select(original, heute,
                "immer".equals(variante.art()) ? SimplificationSelection.Mode.ALWAYS
                        : SimplificationSelection.Mode.WHERE_NEEDED);
        Ergebnis ergebnis = new Ergebnis();
        for (SimplificationSelection.Section section : SimplificationSelection.sections(original, auswahl)) {
            List<SimplificationSection.Sentence> kontext = new ArrayList<>();
            List<SimplificationSection.Sentence> gewaehlt = new ArrayList<>();
            for (int i = section.first(); i <= section.last(); i++) {
                SimplificationSection.Sentence zeile = new SimplificationSection.Sentence(i,
                        original.sentences().get(i).text());
                if (zeile.text().isBlank()) {
                    continue;
                }
                (section.simplify().contains(i) ? gewaehlt : kontext).add(zeile);
            }
            if (gewaehlt.isEmpty()) {
                continue;
            }
            SimplificationSection abschnitt = new SimplificationSection(kontext, gewaehlt);
            Map<Integer, Integer> nummer = new HashMap<>();
            List<SimplificationSection.Sentence> zeilen = abschnitt.lines();
            for (int n = 0; n < zeilen.size(); n++) {
                nummer.put(zeilen.get(n).index(), n + 1);
            }
            Set<Integer> gewaehlteSaetze = new HashSet<>();
            gewaehlt.forEach(s -> gewaehlteSaetze.add(s.index()));
            // Antwort wie vom Modell: Zeilen zu allen Sätzen des Abschnitts, die zu nicht gewählten überspringt parse
            StringBuilder antwort = new StringBuilder();
            List<Integer> antwortZeilen = new ArrayList<>();
            for (int z = 0; z < vorstufe.size(); z++) {
                VorstufenSatz v = vorstufe.get(z);
                Integer n = nummer.get(v.originalsatz() - 1);
                if (n == null) {
                    continue;
                }
                antwort.append(n).append('|').append(v.kennzeichen()).append('|').append(v.satz()).append('\n');
                if (gewaehlteSaetze.contains(v.originalsatz() - 1)) {
                    ergebnis.zeilen++;
                    antwortZeilen.add(z);
                }
            }
            SentenceSimplifier.Parsed parsed = SentenceSimplifier.parse(abschnitt, antwort.toString());
            ergebnis.verworfen += parsed.rejected();
            ergebnis.ergaenzt += parsed.added();
            ergebnis.unvollstaendig += parsed.incomplete().size();
            ergebnis.verloren += parsed.lost().size();
            // Durchgelassene Sätze den gepinnten Zeilen zuordnen (gleicher Satz, gleicher Text, in Reihenfolge)
            Map<Integer, List<String>> offen = new HashMap<>();
            parsed.sentences().forEach((index, saetze) -> offen.put(index,
                    new ArrayList<>(saetze.stream().map(SimplifiedSentence::text).toList())));
            for (int z : antwortZeilen) {
                VorstufenSatz v = vorstufe.get(z);
                List<String> rest = offen.getOrDefault(v.originalsatz() - 1, List.of());
                String normal = v.satz().replaceAll("\\s+", " ").strip();
                if (rest.remove(normal)) {
                    ergebnis.durchgelassen.add(z);
                } else {
                    ergebnis.abgelehnt.add(satz.name() + "/" + variante.pins() + "/" + text.name() + " Satz "
                            + v.originalsatz() + (parsed.lost().contains(v.originalsatz() - 1)
                            ? " (Verneinung/Modalverb verloren)" : "") + ": „" + v.satz() + "“ aus „"
                            + original.sentences().get(v.originalsatz() - 1).text() + "“");
                }
            }
        }
        return ergebnis;
    }
}
