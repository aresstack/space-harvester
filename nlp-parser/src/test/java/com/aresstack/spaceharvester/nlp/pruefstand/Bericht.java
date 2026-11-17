package com.aresstack.spaceharvester.nlp.pruefstand;

import com.aresstack.spaceharvester.nlp.extraction.SimplificationSelection;
import com.aresstack.spaceharvester.nlp.pruefstand.Bewertung.Zahlen;
import com.aresstack.spaceharvester.nlp.syntax.ParsedDocument;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Text;
import com.aresstack.spaceharvester.nlp.pruefstand.Textsatz.Variante;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Lesbare Tabellen des Prüfstands (Markdown), je Textsatz und Variante, für Testprotokoll und PR-Beschreibung. */
final class Bericht {

    private final StringBuilder out = new StringBuilder();
    private final StringBuilder details = new StringBuilder();
    private final Map<String, Zahlen> gesamt = new LinkedHashMap<>();

    /** Bewertet alle Varianten eines Textsatzes und hängt die Tabellen an. */
    Map<String, Zahlen> textsatz(Textsatz satz) {
        Map<String, Zahlen> result = new LinkedHashMap<>();
        out.append("## Textsatz „").append(satz.name()).append("“ (").append(satz.rolle()).append(")\n\n");
        StringBuilder uebersicht = new StringBuilder();
        uebersicht.append("| Variante | vollständig richtig | streng | Verb gefunden | Grundform richtig "
                + "| falsch trotz PLAUSIBLE | Verb fehlt | ergänzt Ü/K · Abschnitt · sonst "
                + "| Verneinung · Verbindlichkeit · Zeitbezug: richtig (vorgegeben) | fälschlich gesetzt (übrige) |\n");
        uebersicht.append("|---|---|---|---|---|---|---|---|---|---|\n");
        StringBuilder tabellen = new StringBuilder();
        for (Variante variante : satz.varianten()) {
            Zahlen summe = new Zahlen();
            tabellen.append("### ").append(variante.name()).append(" – ").append(variante.beschreibung())
                    .append("\n\n");
            tabellen.append("| Text | Art | erwartet | Verb gefunden | Grundform richtig | vollständig richtig "
                    + "| streng | PLAUSIBLE | davon falsch | ergänzt Ü/K · Abschnitt · sonst |\n");
            tabellen.append("|---|---|---|---|---|---|---|---|---|---|\n");
            for (Text text : satz.texte()) {
                Zahlen zahlen = Bewertung.bewerten(satz, variante, text);
                summe.add(zahlen);
                zeile(tabellen, text.name(), text.art(), zahlen);
            }
            zeile(tabellen, "**gesamt**", "", summe);
            tabellen.append('\n');
            uebersicht.append("| ").append(variante.name()).append(" | ").append(anteil(summe.richtig, summe.erwartet))
                    .append(" | ").append(anteil(summe.streng, summe.erwartet))
                    .append(" | ").append(summe.verbGefunden).append(" | ").append(summe.grundformRichtig)
                    .append(" | ").append(summe.plausibelFalsch).append(" von ").append(summe.plausibel)
                    .append(" | ").append(summe.fehlt())
                    .append(" | ").append(ergaenzt(summe))
                    .append(" | ").append(merkmale(summe.merkmalRichtig, summe.merkmalVorgegeben))
                    .append(" | ").append(merkmale(summe.merkmalFaelschlich, summe.merkmalOhne)).append(" |\n");
            result.put(variante.name(), summe);
            gesamt.put(satz.name() + "/" + variante.name(), summe);
            details.append("### ").append(satz.name()).append(" / ").append(variante.name()).append("\n\n");
            summe.fehler.forEach(f -> details.append("- ").append(f).append('\n'));
            details.append('\n');
        }
        out.append(uebersicht).append('\n');
        out.append(kosten(satz)).append("\n\n");
        Zahlen heute = result.get(Textsatz.HEUTE);
        out.append("Merkmale (Verneinung, Verbindlichkeit, Zeitbezug) gemessen an allen Erwartungen mit gefundenem "
                + "Verb, unabhängig von den Rollen; vom Standard abweichend vorgegeben an ").append(heute.merkmaleVorgegeben)
                .append(" von ").append(heute.erwartet).append(" Aussagen.\n\n");
        out.append(tabellen);
        return result;
    }

    /** Kosten der Vorstufe: gewählte Sätze und Abschnitte mit Modellaufruf, „nur wo nötig“ und „immer“. */
    static String kosten(Textsatz satz) {
        int[] gezielt = new int[3];
        int[] immer = new int[3];
        for (Text text : satz.texte()) {
            ParsedDocument original = satz.analyse(satz.varianten().get(0), text);
            int[] g = Bewertung.kosten(original, SimplificationSelection.Mode.WHERE_NEEDED);
            int[] a = Bewertung.kosten(original, SimplificationSelection.Mode.ALWAYS);
            for (int i = 0; i < 3; i++) {
                gezielt[i] += g[i];
                immer[i] += a[i];
            }
        }
        return "Vorstufe „nur wo nötig“: gewählt " + gezielt[0] + " von " + gezielt[2] + " Sätzen, " + gezielt[1]
                + " Abschnitte mit Modellaufruf; „immer“: " + immer[0] + " von " + immer[2] + " Sätzen, " + immer[1]
                + " Abschnitte.";
    }

    String text() {
        return "# Prüfstand Aussagen (ADR 0032)\n\n" + out + "## Einzelbefunde\n\n" + details;
    }

    /** Nur die Übersicht, ohne Einzelbefunde (Testprotokoll). */
    String kurz() {
        return out.toString();
    }

    private static void zeile(StringBuilder out, String text, String art, Zahlen z) {
        out.append("| ").append(text).append(" | ").append(art).append(" | ").append(z.erwartet)
                .append(" | ").append(z.verbGefunden).append(" | ").append(z.grundformRichtig)
                .append(" | ").append(anteil(z.richtig, z.erwartet)).append(" | ").append(z.streng)
                .append(" | ").append(z.plausibel).append(" | ").append(z.plausibelFalsch)
                .append(" | ").append(ergaenzt(z)).append(" |\n");
    }

    private static String merkmale(int[] wert, int[] von) {
        return wert[0] + "/" + von[0] + " · " + wert[1] + "/" + von[1] + " · " + wert[2] + "/" + von[2];
    }

    private static String ergaenzt(Zahlen z) {
        return z.ergaenztKennung + " · " + z.ergaenztAbschnitt + " · " + z.ergaenztSonst;
    }

    private static String anteil(int wert, int von) {
        return von == 0 ? String.valueOf(wert)
                : String.format(Locale.GERMAN, "%d von %d (%d %%)", wert, von, Math.round(100.0 * wert / von));
    }
}
