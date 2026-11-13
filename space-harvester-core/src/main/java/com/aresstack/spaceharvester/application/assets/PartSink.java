package com.aresstack.spaceharvester.application.assets;

/**
 * Blockstrom einer enthaltenen Datei (ADR 0026, Punkte 1 und 3). Eine enthaltene Datei ist ein eigenes Dokument und hat
 * eigene Kopfdaten, etwa Betreff, Absender, Empfänger und Datum einer weitergeleiteten Mail. Sie gehen über {@link
 * #head} an den Teil, nie in seine Blöcke und nie in die Kopfdaten des äußeren Dokuments.
 */
public interface PartSink extends BlockSink {

    /** Kopfdaten dieses Teils; höchstens einmal, vor {@link #close()}. */
    default void head(DocumentHead head) {
    }
}
