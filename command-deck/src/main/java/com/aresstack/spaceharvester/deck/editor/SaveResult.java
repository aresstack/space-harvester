package com.aresstack.spaceharvester.deck.editor;

/** Ergebnis eines Speicherversuchs. */
public sealed interface SaveResult {

    /** Gespeichert; {@code document} ist der neue Stand (neuer Fingerabdruck). */
    record Saved(EditorDocument document) implements SaveResult {
    }

    /** Nicht gespeichert, weil die Datei seit dem Laden geändert wurde (oder verschwunden ist). */
    record Conflict(String reason) implements SaveResult {
    }
}
