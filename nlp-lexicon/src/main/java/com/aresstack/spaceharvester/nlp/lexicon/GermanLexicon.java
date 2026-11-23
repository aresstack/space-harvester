package com.aresstack.spaceharvester.nlp.lexicon;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Deutsche Wortlisten für Verneinung und Modalverben (ADR 0032 FL2, ADR 0035 O4), an einer Stelle: Die Satzmerkmale des
 * Parsers ({@code StatementFeatures}) und die Fristsätze der Regex-Stufe im Core lesen dieselben Listen. Nur
 * allgemeines Deutsch, keine Fachwörter; Vergleich in Kleinschreibung.
 */
public final class GermanLexicon {

    /** Ein Modalverb nach seiner Grundform. */
    public enum Modal {
        MUESSEN,
        SOLLEN,
        KOENNEN,
        DUERFEN
    }

    private static final Set<String> NEGATIONS = Set.of("nicht", "nie", "niemals", "nimmer");
    private static final Set<String> NEGATING_DETERMINERS = Set.of("kein", "keine", "keinen", "keinem", "keiner",
            "keines");
    private static final Map<String, Modal> INFINITIVES = Map.of("müssen", Modal.MUESSEN, "sollen", Modal.SOLLEN,
            "können", Modal.KOENNEN, "dürfen", Modal.DUERFEN);
    /** Formen der Modalverben samt Konjunktiv II, falls die Grundform fehlt oder abweicht. */
    private static final Map<String, Modal> FORMS = forms();

    private GermanLexicon() {
    }

    /** nicht, nie, niemals, nimmer. */
    public static boolean isNegation(String word) {
        return NEGATIONS.contains(lower(word));
    }

    /** kein, keine, keinen, keinem, keiner, keines. */
    public static boolean isNegatingDeterminer(String word) {
        return NEGATING_DETERMINERS.contains(lower(word));
    }

    /** Das Modalverb zu einer Grundform (müssen, sollen, können, dürfen), sonst leer. */
    public static Optional<Modal> modalOfLemma(String lemma) {
        return Optional.ofNullable(INFINITIVES.get(lower(lemma)));
    }

    /** Das Modalverb zu einer gebeugten Form („muss“, „sollte“, „könnten“ …), sonst leer. */
    public static Optional<Modal> modalOfForm(String form) {
        return Optional.ofNullable(FORMS.get(lower(form)));
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private static Map<String, Modal> forms() {
        Map<String, Modal> forms = new HashMap<>();
        for (String form : List.of("muss", "musst", "müssen", "müsst", "musste", "mussten", "müsste", "müssten")) {
            forms.put(form, Modal.MUESSEN);
        }
        for (String form : List.of("soll", "sollst", "sollen", "sollt", "sollte", "sollten")) {
            forms.put(form, Modal.SOLLEN);
        }
        for (String form : List.of("kann", "kannst", "können", "könnt", "konnte", "konnten", "könnte", "könnten")) {
            forms.put(form, Modal.KOENNEN);
        }
        for (String form : List.of("darf", "darfst", "dürfen", "dürft", "durfte", "durften", "dürfte", "dürften")) {
            forms.put(form, Modal.DUERFEN);
        }
        return Map.copyOf(forms);
    }
}
