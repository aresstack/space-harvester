package com.aresstack.spaceharvester.application.sources;

import java.util.Objects;

/**
 * Ein Eingabefeld eines Quellentyps unter {@code options}, für Formulare wie den Quellen-Editor des Enterprise AI
 * Client (#187). Beschreibt nur das Feld, nie einen Wert aus dem Tresor.
 *
 * @param key          Schlüssel unter {@code options}, z. B. {@code host}
 * @param label        Beschriftung, z. B. {@code Host}
 * @param hint         Erklärung unter dem Feld, leer ohne
 * @param kind         Art der Eingabe
 * @param required     Pflichtfeld
 * @param advanced     gehört in die erweiterten Einstellungen
 * @param defaultValue Vorgabe, {@code null} ohne
 */
public record SourceField(String key, String label, String hint, Kind kind, boolean required, boolean advanced,
                          String defaultValue) {

    /** Art der Eingabe, wie {@code SourceSettingField.Kind} im Enterprise AI Client. */
    public enum Kind {
        /** Einzeiliger Text. */
        TEXT,
        /** Ganze Zahl als Text. */
        NUMBER,
        /** Schalter; Wert {@code true} oder {@code false}. */
        FLAG,
        /** Verzeichnispfad mit Auswahlknopf. */
        DIRECTORY,
        /** Verweis auf einen Eintrag beim Anbieter der Zugangsdaten, nie das Geheimnis selbst. */
        SECRET_REF,
        /** Alias eines Client-Zertifikats (mTLS). */
        CLIENT_CERTIFICATE
    }

    public SourceField {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(kind, "kind");
        hint = hint == null ? "" : hint;
    }
}
