package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.enterpriseai.speech.api.TextAnalysisEngine;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;

import java.util.List;
import java.util.Objects;

/**
 * Die Satzanalyse des Vorlesens ({@code TextAnalysisService} des Enterprise AI Client) über die Satzgrenzen des Core
 * ({@code OpenNlpSentenceSegmenter}, deutsches OpenNLP-Modell im Klassenpfad). Eine Spracherkennung gibt es nicht: der
 * Podcast ist deutsch und spricht mit einer Stimme je Sprecher.
 */
public final class SentenceAnalysis {

    private SentenceAnalysis() {
    }

    /** Eine Engine über diese Satzgrenzen. */
    public static TextAnalysisEngine engine(SentenceSegmenter sentences) {
        Objects.requireNonNull(sentences, "sentences");
        return new TextAnalysisEngine() {
            @Override
            public boolean detectsLanguages() {
                return false;
            }

            @Override
            public String detectLanguage(String sentence) {
                return "";
            }

            @Override
            public List<String> sentences(String paragraph, String languageCode) {
                return sentences.sentences(paragraph);
            }

            @Override
            public void close() {
            }
        };
    }
}
