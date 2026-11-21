package com.aresstack.enterpriseai.speech.api;

/**
 * Lädt eine {@link TextAnalysisEngine} aus den installierten Modellen (askai-java8 arch
 * {@code ChatTextAnalysisService.EngineLoader}); aufgerufen, wenn das Vorlesen die Analyse zum ersten Mal braucht.
 */
public interface TextAnalysisEngineLoader {

    /** @throws RuntimeException wenn die Modelle nicht lesbar sind (das Vorlesen fällt dann auf Absätze zurück) */
    TextAnalysisEngine load();
}
