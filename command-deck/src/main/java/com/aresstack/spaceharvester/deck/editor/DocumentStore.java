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

    /** Kennung der Ablage für den Verlauf (lokale Versionen je Ablage und Adresse). */
    default String id() {
        return getClass().getName();
    }

    /** {@code false} für reine Ansichten (z. B. Vorschau eines PDF als Text): dann kein Bearbeiten und Speichern. */
    default boolean writable() {
        return true;
    }

    /** Ob „Speichern unter …“ an eine andere Adresse dieser Ablage geht (in MainframeMate nur lokal). */
    default boolean canSaveAs() {
        return false;
    }

    /**
     * Schreibt {@code text} mit Zeichensatz und Zeilenende von {@code loaded} nach {@code target} (eine vorhandene
     * Datei wird ersetzt; Rückfragen stellt die Oberfläche) und gibt den neuen Stand zurück.
     */
    default EditorDocument saveAs(EditorDocument loaded, String text, String target) throws DocumentException {
        throw new DocumentException("„Speichern unter“ geht hier nicht.");
    }

    /** Return a stable cache scope for sources with identical copybook search paths. */
    default String copybookLookupScope(String location, String library) {
        return location;
    }

    /** Resolve a copybook by the source-specific search rules. */
    default java.util.Optional<EditorDocument> resolveCopybook(String location, String name, String library)
            throws DocumentException {
        if (!library.isBlank()) {
            throw new DocumentException("Keine Zuordnung für COPY-Bibliothek: " + library);
        }
        var candidates = siblings(location, name);
        if (candidates.size() > 1) {
            throw new DocumentException("Mehrdeutiges Copybook: " + name);
        }
        return candidates.isEmpty() ? java.util.Optional.empty() : java.util.Optional.of(load(candidates.getFirst()));
    }

    /**
     * Adressen in dieser Ablage, unter denen ein Objekt namens {@code name} neben {@code location} liegen kann (gleicher
     * Ordner, gleiche Bibliothek, gleiches PDS), für die Diagramm-Ansicht: Aufrufziele der Mindmap auflösen und per
     * Doppelklick öffnen (MainframeMate: Suchreihenfolge der Bibliotheken). Darf blockieren; ohne Treffer leer.
     */
    default java.util.List<String> siblings(String location, String name) throws DocumentException {
        return java.util.List.of();
    }
}
