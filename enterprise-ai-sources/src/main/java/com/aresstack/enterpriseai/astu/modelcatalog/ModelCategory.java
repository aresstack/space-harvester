package com.aresstack.enterpriseai.astu.modelcatalog;

/**
 * Die Funktion, für die ein Modell gewählt wird. Je Kategorie gibt es genau eine Auswahl
 * ({@link ModelSelections}); welche Modelle in eine Kategorie fallen, entscheidet
 * {@link ModelClassification} allein aus den gemeldeten Metadaten.
 */
public enum ModelCategory {

    CHAT("chat", "Chat"),
    EMBEDDING("embedding", "Embeddings"),
    RERANK("rerank", "Reranking"),
    TTS("tts", "Sprachausgabe (TTS)"),
    STT("stt", "Spracheingabe (STT)"),
    VISION("vision", "Bildverständnis"),
    DOCUMENT_OCR("documentOcr", "Dokumente / OCR"),
    IMAGE_EMBEDDING("imageEmbedding", "Bild-Embeddings");

    private final String key;
    private final String displayName;

    ModelCategory(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /** Stabiler Schlüssel für die Konfiguration ({@code model.<key>}). */
    public String key() {
        return key;
    }

    /** Anzeigename in der Oberfläche. */
    public String displayName() {
        return displayName;
    }

    /** @return die Kategorie zum Schlüssel oder {@code null} */
    public static ModelCategory fromKey(String key) {
        for (ModelCategory category : values()) {
            if (category.key.equals(key)) {
                return category;
            }
        }
        return null;
    }
}
