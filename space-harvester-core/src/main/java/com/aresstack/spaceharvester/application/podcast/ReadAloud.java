package com.aresstack.spaceharvester.application.podcast;

import java.io.IOException;

/**
 * Vorlesen eines Texts über die Sprachausgabe des KI-Dienstes (Angelo 23.11.2026: „die funktionierende Logik aus dem
 * Enterprise Client kopieren, weil das nämlich bei längeren Sätzen problematisch wird“). Die Umsetzung (Quellenadapter
 * {@code source.ai}) ist der Vorlese-Ablauf des Enterprise AI Client ({@code ReadAloudService}, unverändert kopiert):
 * Aufbereitung zu sprechbaren Zeichen, Stückelung in Absätze samt Stimmstabilitäts-Fix, mehrere Stücke parallel in
 * Arbeit, Wiederholung je Stück, abgespielt in Sprechreihenfolge über eine {@link Playback} der App.
 *
 * <p>Dieselben Methoden wie der {@code ReadAloudService}; der Core kennt ihn nur über diese Schnittstelle.
 */
public interface ReadAloud {

    /** Wohin die gesprochenen Stücke gehen: die Wiedergabe der App (wie {@code AudioPlayback} im Upstream). */
    interface Playback {

        /** Ein neues Vorlesen beginnt; ein vorheriger Stopp gilt nicht mehr. */
        default void begin() {
        }

        /** Spielt ein WAV-Stück ab; blockiert, bis es in der Ausgabe liegt. */
        void play(byte[] wav) throws IOException;

        /** Das Vorlesen ist durch: ausklingen lassen. */
        void finish();

        /** Abbrechen und den Puffer verwerfen. */
        void stop();

        default void pause() {
        }

        default void resume() {
        }

        /** Sprechgeschwindigkeit, 1 = normal. */
        default void setRate(double rate) {
        }
    }

    /** Rückmeldungen eines Vorlesens. */
    interface Listener {

        /** Ein Stück war nicht zu sprechen; die Meldung nennt nie Text oder Schlüssel. */
        default void failed(String message) {
        }
    }

    /**
     * Liest {@code text} vor und kehrt erst danach zurück (fertig, gestoppt oder gescheitert); nie auf dem EDT. Ein
     * laufendes Vorlesen endet vorher.
     *
     * @return ob mindestens ein Stück erklungen ist (auch wenn danach gestoppt wurde)
     */
    boolean speak(String text, Listener listener);

    /** Beendet das laufende Vorlesen; idempotent. */
    void stop();

    void pause();

    void resume();

    boolean isPaused();

    void setRate(double rate);

    double rate();

    /** Das Sprachmodell, mit dem vorgelesen wird. */
    String model();

    /** Die feste Stimme, {@code null}, wenn der Dienst wählt. */
    String voice();
}
