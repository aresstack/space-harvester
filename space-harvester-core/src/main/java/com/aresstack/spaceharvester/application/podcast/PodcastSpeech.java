package com.aresstack.spaceharvester.application.podcast;

import com.aresstack.spaceharvester.ai.AiServiceSettings;

import java.io.IOException;
import java.util.List;

/**
 * Die Sprachausgabe des KI-Dienstes für den Podcast: welche TTS-Modelle der Dienst anbietet und wie ein Beitrag zu
 * WAV-Stücken wird. Die Umsetzung (Quellenadapter {@code source.ai}) nimmt dafür den Vorlese-Ablauf des Enterprise AI
 * Client samt Stimmstabilitäts-Fix; der Podcast selbst kennt nur diese Schnittstelle. Folgt der wirksamen Konfiguration
 * bei jedem Aufruf.
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

    /**
     * Spricht einen Beitrag mit einer Stimme.
     *
     * @param sentences Satz für Satz statt in Absätzen (Ausweg, wenn längere Stücke scheitern)
     * @return die WAV-Stücke in Sprechreihenfolge
     * @throws IOException wenn der Beitrag nicht zu sprechen war; die Meldung nennt nie Text
     */
    List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException;

    /**
     * Wie viele Anfragen ein einziger {@link #speak}-Aufruf höchstens gleichzeitig an den Dienst stellt; der Podcast
     * spricht nur so viele Abschnitte zugleich, dass zusammen {@code ai.parallel} nicht überschritten wird.
     */
    default int workers() {
        return 1;
    }

    /**
     * Vorlesen mit diesem Modell und dieser Stimme über den Vorlese-Ablauf des Enterprise AI Client, abgespielt über
     * {@code playback} (Leseansicht einer Seite, Angelo 23.11.2026).
     *
     * @param voice die feste Stimme oder {@code null}, dann wählt der Dienst
     * @throws IllegalStateException ohne eingerichtete Sprachausgabe
     */
    default ReadAloud readAloud(String model, String voice, ReadAloud.Playback playback) {
        throw new IllegalStateException("Keine Sprachausgabe eingerichtet");
    }

    /**
     * Was in den Einstellungen für die Sprachausgabe steht (Modell und Stimmen, getrennt für Podcast und Vorlesen);
     * leer heißt automatisch. Folgt der wirksamen Konfiguration bei jedem Aufruf.
     */
    default AiServiceSettings.Speech choice() {
        return AiServiceSettings.Speech.AUTOMATIC;
    }

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
        public List<byte[]> speak(String model, String voice, String text, boolean sentences) throws IOException {
            throw new IOException("Keine Sprachausgabe eingerichtet");
        }
    };
}
