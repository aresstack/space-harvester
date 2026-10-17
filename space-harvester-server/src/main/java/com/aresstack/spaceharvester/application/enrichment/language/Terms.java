package com.aresstack.spaceharvester.application.enrichment.language;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Suchwerte für Begriffe und Eigenschaften. Ein Suchwert dient dem Finden, nie als Identität; Flexion wird nicht
 * zurückgeführt, weil es keinen geprüften Lemmatisierer gibt. Nur attributive Adjektive verlieren ihre
 * Flexionsendung ({@link #adjectiveBase(String)}); das Ergebnis ist ein Vorschlag.
 */
final class Terms {

    /** Adjektive, die nur wie ein Partizip aussehen. */
    private static final Set<String> NOT_PARTICIPLES = Set.of("gesamt", "gerecht", "gering", "bereit", "verschieden",
            "berühmt", "bestimmt", "entfernt", "erlaubt", "beliebt", "bekannt");

    private Terms() {
    }

    /** Unicode NFC, Kleinbuchstaben (unabhängig vom Gebietsschema), Leerraum zu einem Leerzeichen. */
    static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT).replaceAll("\\s+", " ")
                .trim();
    }

    /**
     * Vorgeschlagene Grundform eines attributiven Adjektivs: ohne {@code -em}, {@code -en}, {@code -er}, {@code -es}
     * oder {@code -e}, sofern mindestens drei Buchstaben bleiben ({@code eingereichten} wird {@code eingereicht},
     * {@code neue} wird {@code neu}). Unregelmäßige Formen bleiben falsch ({@code anderer} wird {@code ander}).
     */
    static String adjectiveBase(String word) {
        String lower = normalize(word);
        for (String ending : new String[] {"em", "en", "er", "es"}) {
            if (lower.endsWith(ending) && lower.length() - 2 >= 3) {
                return lower.substring(0, lower.length() - 2);
            }
        }
        if (lower.endsWith("e") && lower.length() - 1 >= 3) {
            return lower.substring(0, lower.length() - 1);
        }
        return lower;
    }

    /** Eine Flexionsendung eines attributiven Adjektivs. */
    static boolean hasAdjectiveEnding(String lower) {
        return lower.length() >= 3 && (lower.endsWith("e") || lower.endsWith("en") || lower.endsWith("er")
                || lower.endsWith("es") || lower.endsWith("em"));
    }

    /**
     * Sieht aus wie ein Partizip II ({@code geprüft}, {@code eingereicht}, {@code geschlossen}, {@code
     * aktualisiert}, {@code veraltet}, {@code bearbeitet}), nach Schreibung allein.
     */
    static boolean participle(String base) {
        if (NOT_PARTICIPLES.contains(base)) {
            return false;
        }
        return base.matches("(?:ab|an|auf|aus|bei|ein|fest|mit|nach|vor|weg|zu|zurück|her|hin|los|um|frei|"
                + "zusammen|weiter)?ge\\p{L}{2,}(?:t|en)")
                || base.matches("(?:be|ver|ent|er|zer|miss)\\p{L}{3,}(?:t|en)") || base.matches("\\p{L}{3,}iert");
    }
}
