package com.aresstack.enterpriseai.astu.acropolis.speech;

import java.io.IOException;

/** Spielt WAV-Audio ab (Implementierung in der Composition Root, z. B. Java Sound). */
public interface AudioPlayback {

    /**
     * Beginnt einen Vorlesevorgang: ein {@link #stop()} davor gilt nicht mehr. Ein {@link #stop()} danach bleibt bis
     * zum nächsten {@code begin()} wirksam, auch für ein {@link #play}, das erst nach dem Stopp beginnt.
     */
    default void begin() {
    }

    /**
     * Spielt {@code wav} ab und blockiert, bis es an das Ausgabegerät übergeben ist oder {@link #stop()} kommt. Mehrere
     * Stücke hintereinander dürfen nahtlos über dieselbe Ausgabe laufen; {@link #finish()} beendet die Folge.
     */
    void play(byte[] wav) throws IOException;

    /** Wartet, bis die Folge von {@link #play} ausgeklungen ist, und gibt das Ausgabegerät frei. */
    void finish();

    /** Bricht die laufende Wiedergabe sofort ab; ohne Wiedergabe wirkungslos. */
    void stop();

    /**
     * Holds the output at the current position without discarding buffer or sequence: a running {@link #play} blocks
     * until {@link #resume()}. {@link #stop()} and {@link #begin()} end the pause.
     */
    default void pause() {
    }

    /** Continues output held by {@link #pause()} at the same position; no effect otherwise. */
    default void resume() {
    }

    /**
     * Speaking rate of the output ({@link SpeechRate}, 1 = unchanged, pitch preserved); applies from the next output
     * block, so also in the middle of a chunk.
     */
    default void setRate(double rate) {
    }
}
