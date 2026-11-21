package com.aresstack.enterpriseai.astu.acropolis.speech;

import java.util.Locale;

/**
 * Einheit, in der vorgelesen wird (askai arch {@code TextToSpeechSettings.Chunking}): je Stück eine Synthese, die
 * Wiedergabe läuft in Lesereihenfolge. Absätze sind der Standard, der beste Kompromiss aus Klang und Wartezeit.
 */
public enum SpeechChunking {

    /** Die ganze Antwort als ein Stück (lange Antworten werden an Satzgrenzen begrenzt). */
    ANSWER,
    /** Ein Stück je Absatz. */
    PARAGRAPHS,
    /** Ein Stück je Satz. */
    SENTENCES;

    /** Liest den Konfigurationswert; Unbekanntes und Leeres ergeben {@code fallback}. */
    public static SpeechChunking parse(String value, SpeechChunking fallback) {
        if (value == null || value.trim().isEmpty()) {
            return fallback;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }
}
