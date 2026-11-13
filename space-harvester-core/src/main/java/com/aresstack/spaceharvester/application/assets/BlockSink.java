package com.aresstack.spaceharvester.application.assets;

/**
 * Empfänger der Blöcke eines Dokuments (ADR 0026, Punkte 3 und 8): Blöcke kommen als Strom in Lesereihenfolge, nie als
 * Liste im Speicher. Die Ablage in Abschnitten ist ein solcher Empfänger. {@link #close()} beendet den Strom.
 */
public interface BlockSink extends AutoCloseable {

    /**
     * Nimmt den nächsten Block an.
     *
     * @throws java.io.UncheckedIOException wenn der Empfänger nicht schreiben kann; die Extraktion endet dann
     */
    void accept(Block block);

    /** Beendet den Strom dieses Dokuments; danach kommen keine Blöcke mehr. */
    @Override
    default void close() {
    }
}
