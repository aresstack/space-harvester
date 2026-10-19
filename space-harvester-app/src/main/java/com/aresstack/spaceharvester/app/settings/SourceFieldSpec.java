package com.aresstack.spaceharvester.app.settings;

import java.util.Objects;

/**
 * Ein Eingabefeld eines Quellentyps, wie es das Quellenformular zeigt: Schlüssel unter {@code options}, Beschriftung,
 * Hinweis, Art, Pflicht, „Erweitert“ und Vorgabe für neue Quellen. Wie {@code SourceSettingField} im Enterprise AI
 * Client; der Core liefert die Felder künftig je Typ ({@code SourceType.fields()}), bis dahin {@link SourceFields}.
 * Keine Geheimnisse: Zugangsdaten sind höchstens ein Verweis ({@link Kind#SECRET_REF}).
 *
 * @param key          Schlüssel unter {@code options}, z. B. {@code host}
 * @param label        Beschriftung im Formular
 * @param hint         Erklärung unter dem Feld; leer, wenn keine
 * @param kind         wie das Feld eingegeben wird
 * @param required     Pflichtangabe
 * @param advanced     erst unter „Erweitert“ sichtbar
 * @param defaultValue Vorgabe für neue Quellen; leer, wenn keine
 */
public record SourceFieldSpec(String key, String label, String hint, Kind kind, boolean required, boolean advanced,
                              String defaultValue) {

    /** Wie das Feld eingegeben wird. */
    public enum Kind {
        /** Einzeiliger Text. */
        TEXT,
        /** Ganze Zahl als Text. */
        NUMBER,
        /** Schalter; Wert {@code true} oder {@code false}. */
        FLAG,
        /** Verzeichnispfad mit Auswahlknopf. */
        DIRECTORY,
        /** Verweis auf Zugangsdaten beim Anbieter des Programms (etwa {@code keepass:jes}), nie das Geheimnis selbst. */
        SECRET_REF,
        /**
         * Alias eines Client-Zertifikats aus dem Windows-Zertifikatsspeicher mit Auswahl; dazu gehört der Schalter
         * {@link #certificateUseKey()} („Zertifikat verwenden“, {@code false} = ohne Zertifikat).
         */
        CLIENT_CERTIFICATE
    }

    public SourceFieldSpec {
        if (key == null || key.isBlank() || label == null || label.isBlank()) {
            throw new IllegalArgumentException("key und label müssen gesetzt sein");
        }
        Objects.requireNonNull(kind, "kind");
        key = key.strip();
        label = label.strip();
        hint = hint == null ? "" : hint.strip();
        defaultValue = defaultValue == null ? "" : defaultValue.strip();
    }

    /**
     * Schlüssel des Schalters „Zertifikat verwenden“: aus {@code clientCertificate.alias} wird
     * {@code clientCertificate.use}.
     */
    public String certificateUseKey() {
        int dot = key.lastIndexOf('.');
        return (dot < 0 ? key : key.substring(0, dot)) + ".use";
    }
}
