package com.aresstack.spaceharvester.deck.editor;

import java.util.Objects;

/**
 * Ein geladener Text, wie ihn der Editor zeigt. {@code hash} ist der Fingerabdruck der Bytes beim Laden; beim
 * Speichern prüft die Ablage damit, ob jemand die Datei inzwischen geändert hat (wie {@code writeIfUnchanged} in
 * MainframeMate).
 *
 * @param location Adresse in der Ablage
 * @param name     Anzeigename (Reiter)
 * @param text       der Inhalt als Text, Zeilenenden immer {@code \n}
 * @param charset    Zeichensatz, mit dem gelesen wurde und wieder geschrieben wird
 * @param lineEnding Zeilenende der Datei ({@code \r\n}, {@code \n} oder {@code \r}), mit dem wieder geschrieben wird
 * @param hash       SHA-256 der gelesenen Bytes (hex)
 */
public record EditorDocument(String location, String name, String text, String charset, String lineEnding,
                             String hash, String writeRestriction) {

    public EditorDocument(String location, String name, String text, String charset, String lineEnding, String hash) {
        this(location, name, text, charset, lineEnding, hash, "");
    }

    public EditorDocument {
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(lineEnding, "lineEnding");
        Objects.requireNonNull(hash, "hash");
        writeRestriction = writeRestriction == null ? "" : writeRestriction;
    }

    /** Die Dateiendung ohne Punkt in Kleinbuchstaben (für die Syntaxhervorhebung), sonst leer. */
    public String extension() {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT) : "";
    }
}
