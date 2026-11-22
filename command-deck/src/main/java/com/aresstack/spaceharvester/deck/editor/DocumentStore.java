package com.aresstack.spaceharvester.deck.editor;

/**
 * Port des gemeinsamen Editors im Command Deck: Jeder Arbeitsbereich (Dateien, später FTP-Datasets, NDV-Quellen)
 * liefert eine eigene Ablage und öffnet damit denselben Editor. Aufrufe dürfen blockieren und laufen nie auf dem EDT.
 */
public interface DocumentStore {

    /** Lädt einen Text; Binärdateien und zu große Dateien lehnt die Ablage mit Grund ab. */
    EditorDocument load(String location) throws DocumentException;

    /**
     * Speichert {@code text} an die Stelle von {@code loaded}. Ohne {@code overwrite} nur, wenn die Datei noch den
     * Fingerabdruck {@code loaded.hash()} hat, sonst {@link SaveResult.Conflict}; mit {@code overwrite} immer.
     */
    SaveResult save(EditorDocument loaded, String text, boolean overwrite) throws DocumentException;
}
