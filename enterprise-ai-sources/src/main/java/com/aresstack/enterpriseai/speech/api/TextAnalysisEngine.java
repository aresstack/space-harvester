package com.aresstack.enterpriseai.speech.api;

import java.util.List;

/**
 * Satz- und Spracherkennung für das Vorlesen (askai-java8 arch {@code ChatTextAnalysisService.Engine}): die geladenen
 * Modelle einer Engine. Fehlt ein Modell, wird nichts erfunden: ohne Spracherkennungsmodell liefert
 * {@link #detectLanguage} immer {@code ""}, ohne Satzmodell einer Sprache schneidet {@link #sentences} einfach an
 * Satzzeichen.
 */
public interface TextAnalysisEngine {

    /** Ob ein Spracherkennungsmodell geladen ist. */
    boolean detectsLanguages();

    /**
     * @return ISO-639-1 ({@code "de"} oder {@code "en"}) oder {@code ""}, wenn unbekannt, zu kurz oder kein
     * Spracherkennungsmodell geladen ist
     */
    String detectLanguage(String sentence);

    /** Die Sätze eines Absatzes in Lesereihenfolge (nie {@code null}). */
    List<String> sentences(String paragraph, String languageCode);

    /** Whether a tokenizer model for the language ({@code de}, {@code en}) is loaded. */
    default boolean tokenizes(String languageCode) {
        return false;
    }

    /**
     * The tokens of one sentence by the tokenizer model of the language.
     *
     * @return the tokens, or an empty list when no tokenizer model for the language is loaded
     */
    default List<String> tokens(String sentence, String languageCode) {
        return java.util.Collections.emptyList();
    }

    /** Whether a part-of-speech model for the language ({@code de}, {@code en}) is loaded. */
    default boolean tagsWordClasses(String languageCode) {
        return false;
    }

    /**
     * Tag the tokens of one sentence.
     *
     * @param tokens       the tokens in order, punctuation as tokens of its own
     * @param languageCode {@code de} or {@code en}
     * @return one tagged token per token, or an empty list when no part-of-speech model for the language is loaded
     */
    default List<TaggedToken> tag(List<String> tokens, String languageCode) {
        return java.util.Collections.emptyList();
    }

    /** Gibt die Modelle frei. */
    void close();
}
