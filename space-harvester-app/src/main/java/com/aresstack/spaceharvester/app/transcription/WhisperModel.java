package com.aresstack.spaceharvester.app.transcription;

import java.util.Locale;
import java.util.Map;

/**
 * Die wählbaren Whisper-Modelle (Einstellungen › Modelle, {@code space-harvester-app.whisper.model}): die
 * mehrsprachigen Hugging-Face-Checkpoints von OpenAI, vom kleinsten zum genauesten. Kein Modell liegt im Programm;
 * das gewählte wird einmal von Hugging Face in den Benutzerordner geladen ({@link WhisperModelInstaller}).
 * {@link #TINY} ist die Vorgabe und auf eine Revision samt Prüfsummen festgelegt, die größeren Modelle prüft der
 * Installer gegen die Prüfsummen, die Hugging Face zur Revision nennt.
 */
public enum WhisperModel {
    TINY("tiny", "Tiny", 150, "169d4a4341b33bc18d8881c4b69c2e104e1cc0af", Map.of(
            "config.json", "ffdccec4f3211f4c63310f2b7098f309fe70f3952cedc5e4d11e43f5b2379b98",
            "model.safetensors", "7ebd0e69e78190ffe1438491fa05cc1f5c1aa3a4c4db3bc1723adbb551ea2395",
            "tokenizer.json", "27fc476bfe7f17299480be2273fc0608e4d5a99aba2ab5dec5374b4482d1a566",
            "vocab.json", "8f680bba319e01a653d2e8a5dbc17a9157179e0576e6ce74ce0c06356c6e24f9")),
    BASE("base", "Base", 290, null, Map.of()),
    SMALL("small", "Small", 970, null, Map.of()),
    MEDIUM("medium", "Medium", 3060, null, Map.of()),
    LARGE_V3_TURBO("large-v3-turbo", "Large v3 Turbo", 1620, null, Map.of()),
    LARGE_V3("large-v3", "Large v3", 3090, null, Map.of());

    /** Schlüssel in der Konfigurationsdatei, nur die App. */
    public static final String KEY = "space-harvester-app.whisper.model";
    public static final WhisperModel DEFAULT = TINY;

    private final String id;
    private final String label;
    private final int megabytes;
    private final String revision;
    private final Map<String, String> pinned;

    WhisperModel(String id, String label, int megabytes, String revision, Map<String, String> pinned) {
        this.id = id;
        this.label = label;
        this.megabytes = megabytes;
        this.revision = revision;
        this.pinned = pinned;
    }

    /** Wert in der Konfigurationsdatei und Teil des Ordnernamens, etwa {@code large-v3-turbo}. */
    public String id() {
        return id;
    }

    /** Name für die Oberfläche, etwa {@code Tiny}. */
    public String label() {
        return label;
    }

    /** Ungefähre Größe des Downloads in Megabyte. */
    public int megabytes() {
        return megabytes;
    }

    /** Name samt Größe für Auswahl und Rückfrage, etwa {@code Small (ca. 970 MB)}. */
    public String described() {
        return label + " (ca. " + (megabytes >= 1000
                ? String.format(Locale.GERMANY, "%.1f GB", megabytes / 1000d) : megabytes + " MB") + ")";
    }

    /** Das Repository bei Hugging Face, etwa {@code openai/whisper-tiny}. */
    public String repository() {
        return "openai/whisper-" + id;
    }

    /** Die festgelegte Revision, {@code null}: die aktuelle von {@code main}. */
    String revision() {
        return revision;
    }

    /** Festgelegte SHA-256 je Datei; leer: die Prüfsummen nennt Hugging Face. */
    Map<String, String> pinned() {
        return pinned;
    }

    /** Das Modell zum Wert der Konfigurationsdatei; {@code null} bei einem unbekannten Wert. */
    public static WhisperModel of(String id) {
        if (id != null) {
            for (WhisperModel model : values()) {
                if (model.id.equalsIgnoreCase(id.strip())) {
                    return model;
                }
            }
        }
        return null;
    }
}
