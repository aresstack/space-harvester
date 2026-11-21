package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;

import java.util.List;

/**
 * Die Sprachausgabe des KI-Dienstes für den Podcast: welche TTS-Modelle der Dienst anbietet und der Port, über den sie
 * sprechen. Folgt der wirksamen Konfiguration bei jedem Aufruf.
 */
public interface PodcastSpeech {

    /** Ob ein Dienst eingerichtet ist, der Sprachausgabe haben kann (nicht Ollama). */
    boolean available();

    /**
     * Die TTS-Modelle des Dienstes (Kategorie {@code tts} des Modellkatalogs), in der Reihenfolge des Katalogs.
     *
     * @throws RuntimeException wenn der Dienst nicht antwortet; die Meldung nennt nie den Schlüssel
     */
    List<String> models();

    /** Der Port zur Sprachausgabe. */
    SpeechSynthesisPort port();

    /** Keine Sprachausgabe. */
    PodcastSpeech NONE = new PodcastSpeech() {
        @Override
        public boolean available() {
            return false;
        }

        @Override
        public List<String> models() {
            return List.of();
        }

        @Override
        public SpeechSynthesisPort port() {
            throw new IllegalStateException("Keine Sprachausgabe eingerichtet");
        }
    };
}
