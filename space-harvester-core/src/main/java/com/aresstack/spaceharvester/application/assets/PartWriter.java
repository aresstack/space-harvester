package com.aresstack.spaceharvester.application.assets;

/**
 * Schreibt die Blöcke eines Teils in den Stand seines Behälters (ADR 0026, AP7b): lückenlos ab 0, in eigenen
 * Abschnitten, mit eigenem Kopf. {@link #close()} schließt den Teil ab; gültig wird er erst mit dem
 * {@link ExtractionWriter#commit} des Behälters, zusammen mit allen anderen Teilen.
 */
public interface PartWriter extends PartSink {
}
