package com.aresstack.spaceharvester.application.assets;

/**
 * Empfänger einer strukturierten Extraktion (ADR 0026, Punkte 1, 3 und 5): Der äußere Behälter und jede enthaltene
 * Datei sind eigene Dokumente mit eigenem Blockstrom. Der Text einer enthaltenen Datei geht nie in den Strom ihres
 * Behälters.
 *
 * <p>Ablauf: {@link #document()} liefert einmal den Strom des äußeren Dokuments; {@link #part(EmbeddedEntry)} je
 * enthaltener Datei einen eigenen Strom. Der Strom eines Teils wird geschlossen, bevor der nächste Teil beginnt; der
 * Strom des äußeren Dokuments kann dazwischen weiterlaufen (Text einer Mail nach ihren Anhängen) und wird zuletzt
 * geschlossen.
 */
public interface StructureSink {

    /** Der Strom des äußeren Dokuments. */
    BlockSink document();

    /**
     * Der Strom einer enthaltenen Datei. Auch ein Teil ohne Text (defekt, ausgeschlossen) wird gemeldet; sein Strom
     * bleibt dann leer.
     *
     * @param entry Eintrag im Verzeichnis, mit Kennung und vorläufigem Status
     * @return Strom des Teils, der auch seine eigenen Kopfdaten annimmt
     */
    PartSink part(EmbeddedEntry entry);
}
