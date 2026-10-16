package com.aresstack.enterpriseai.astu.source;

/**
 * Ein Eingabefeld eines Quelltyps, wie es der Dialog „+ Quelle“ zeigt: Schlüssel (ohne {@code source.<n>.}),
 * Beschriftung, Hinweis, Art und Vorgabe. Der Adapter liefert seine Felder selbst ({@link KnowledgeSourceType});
 * die Oberfläche baut daraus das Formular, ohne den Typ zu kennen. Keine Secrets: Zugangsdaten sind höchstens
 * ein Verweis ({@link Kind#SECRET_REF}).
 */
public final class SourceSettingField {

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
        /** Titel eines Eintrags im Tresor (KeePass), nie das Secret selbst. */
        SECRET_REF,
        /**
         * Alias eines Client-Zertifikats (mTLS) aus dem Windows-Zertifikatspeicher mit Auswahl wie in MainframeMate;
         * der private Schlüssel bleibt im Speicher. Dazu gehört der Schalter {@link #certificateUseKey()}
         * („Zertifikat verwenden“, {@code false} = ohne Zertifikat).
         */
        CLIENT_CERTIFICATE
    }

    /**
     * Ob und wie der Wert aus dem Tresoreintrag ({@link Kind#SECRET_REF}) kommen kann: KeePass speichert zu
     * jedem Eintrag eine URL. Ist ein Eintrag gewählt und das Feld leer, gilt die Adresse des Eintrags; ein
     * eingetragener Wert überschreibt sie. Ohne Tresor (manuelle Konfiguration) ist das Feld eine normale Angabe.
     */
    public enum VaultValue {
        /** Nie aus dem Tresor. */
        NONE,
        /** Die URL des Eintrags, unverändert. */
        LOCATION,
        /** Nur der Hostname aus der URL des Eintrags (oder der Wert selbst, wenn er keine URL ist). */
        HOST
    }

    private final String key;
    private final String label;
    private final String hint;
    private final Kind kind;
    private final String defaultValue;
    private final boolean required;
    private final boolean advanced;
    private final VaultValue vaultValue;

    private SourceSettingField(String key, String label, String hint, Kind kind, String defaultValue,
                               boolean required, boolean advanced, VaultValue vaultValue) {
        if (key == null || key.trim().isEmpty() || label == null || label.trim().isEmpty() || kind == null) {
            throw new IllegalArgumentException("key, label and kind must be set");
        }
        this.key = key.trim();
        this.label = label.trim();
        this.hint = hint == null ? "" : hint.trim();
        this.kind = kind;
        this.defaultValue = defaultValue == null ? "" : defaultValue.trim();
        this.required = required;
        this.advanced = advanced;
        this.vaultValue = vaultValue == null ? VaultValue.NONE : vaultValue;
    }

    public static SourceSettingField of(String key, Kind kind, String label, String hint) {
        return new SourceSettingField(key, label, hint, kind, "", false, false, VaultValue.NONE);
    }

    public static SourceSettingField text(String key, String label, String hint) {
        return of(key, Kind.TEXT, label, hint);
    }

    /** Dasselbe Feld als Pflichtangabe. */
    public SourceSettingField required() {
        return new SourceSettingField(key, label, hint, kind, defaultValue, true, advanced, vaultValue);
    }

    /** Dasselbe Feld unter „Erweitert“: nicht nötig für den ersten Lauf, Vorgaben des Adapters genügen. */
    public SourceSettingField advanced() {
        return new SourceSettingField(key, label, hint, kind, defaultValue, required, true, vaultValue);
    }

    /** Dasselbe Feld mit Wert aus der URL des Tresoreintrags, wenn es leer bleibt. */
    public SourceSettingField fromVault(VaultValue value) {
        return new SourceSettingField(key, label, hint, kind, defaultValue, required, advanced, value);
    }

    /** Dasselbe Feld mit Vorgabe für neue Quellen. */
    public SourceSettingField withDefault(String value) {
        return new SourceSettingField(key, label, hint, kind, value, required, advanced, vaultValue);
    }

    public String key() {
        return key;
    }

    public String label() {
        return label;
    }

    /** Tooltip bzw. Erklärung; leer, wenn keine. */
    public String hint() {
        return hint;
    }

    public Kind kind() {
        return kind;
    }

    /** Vorgabe für neue Quellen; leer, wenn keine. */
    public String defaultValue() {
        return defaultValue;
    }

    public boolean isRequired() {
        return required;
    }

    /** {@code true}, wenn das Formular das Feld erst unter „Erweitert“ zeigt. */
    public boolean isAdvanced() {
        return advanced;
    }

    /**
     * Schlüssel des Schalters „Zertifikat verwenden“ zu einem {@link Kind#CLIENT_CERTIFICATE}-Feld: aus
     * {@code clientCertificate.alias} wird {@code clientCertificate.use}.
     */
    public String certificateUseKey() {
        int dot = key.lastIndexOf('.');
        return (dot < 0 ? key : key.substring(0, dot)) + ".use";
    }

    /** Wie der Wert aus dem Tresoreintrag kommt; {@link VaultValue#NONE}, wenn nie. */
    public VaultValue vaultValue() {
        return vaultValue;
    }

    @Override
    public String toString() {
        return "SourceSettingField[" + key + ", " + kind + (required ? ", Pflicht" : "") + "]";
    }
}
