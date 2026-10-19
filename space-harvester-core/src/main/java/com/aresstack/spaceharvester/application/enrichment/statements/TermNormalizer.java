package com.aresstack.spaceharvester.application.enrichment.statements;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;

/**
 * Die einzige Stelle, an der Begriffe einer Aussage zu Suchwerten werden (#108, unverändert aus dem Enterprise AI
 * Client). Ein Suchwert dient dem Finden, nie als Identität: gleiche Werte auf zwei Seiten sind zwei Erwähnungen.
 * Versioniert mit {@link #VERSION}; das mit jedem Wert gespeicherte Verfahren sagt, ob er aus einer vorgeschlagenen
 * Grundform (ungeprüft) oder aus dem Wortlaut stammt.
 *
 * <p>Regeln (Version 1): Unicode NFC, Kleinbuchstaben (ohne Locale), innerer Leerraum zusammengefasst, Anführungszeichen
 * und Satzzeichen am Rand entfernt. Aus dem Wortlaut fallen zusätzlich führende Artikel weg („den eingereichten Beleg“
 * wird „eingereichten beleg“); keine Flexion wird rückgängig gemacht, weil es keinen geprüften deutschen Lemmatisierer
 * gibt.
 */
public final class TermNormalizer {

    /** Version der Regeln; Teil jedes Verfahrensnamens. */
    public static final String VERSION = "1";
    /** Verfahren eines Werts aus einer vom Modell vorgeschlagenen Grundform (ungeprüft). */
    public static final String FROM_LEMMA_CANDIDATE = "lemma-candidate/nfc-lower/" + VERSION;
    /** Verfahren eines Werts aus dem Wortlaut ohne Artikel. */
    public static final String FROM_SURFACE = "surface-without-determiner/nfc-lower/" + VERSION;
    /** Status jeder vorgeschlagenen Grundform: keine morphologische Prüfung bestätigt sie. */
    public static final String LEMMA_STATUS = "PROPOSED_UNVERIFIED";

    private static final Set<String> DETERMINERS = Set.of("der", "die", "das", "den", "dem", "des", "ein", "eine",
            "einen", "einem", "einer", "eines", "the", "a", "an");
    private static final String EDGE = "\"'„“”‚‘’«»()[]{}.,;:!?";

    private TermNormalizer() {
    }

    /** Der Suchwert einer vorgeschlagenen Grundform. */
    public static String fromLemma(String lemma) {
        return basic(lemma);
    }

    /** Der Suchwert eines Wortlauts: ohne führende Artikel. */
    public static String fromSurface(String surface) {
        String value = basic(surface);
        String[] words = value.split(" ");
        int first = 0;
        while (first < words.length - 1 && DETERMINERS.contains(words[first])) {
            first++;
        }
        StringBuilder rest = new StringBuilder();
        for (int i = first; i < words.length; i++) {
            if (!rest.isEmpty()) {
                rest.append(' ');
            }
            rest.append(words[i]);
        }
        return rest.toString();
    }

    private static String basic(String text) {
        if (text == null) {
            return "";
        }
        String value = Normalizer.normalize(text, Normalizer.Form.NFC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").trim();
        int start = 0;
        int end = value.length();
        while (start < end && EDGE.indexOf(value.charAt(start)) >= 0) {
            start++;
        }
        while (end > start && EDGE.indexOf(value.charAt(end - 1)) >= 0) {
            end--;
        }
        return value.substring(start, end).trim();
    }
}
