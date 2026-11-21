package com.aresstack.spaceharvester.ai;

/**
 * Die Art des KI-Dienstes ({@code space-harvester.ai.provider}): ein OpenAI-kompatibler Endpunkt wie die Enterprise-API
 * oder ein Ollama-Server wie in AskAI. Ollama beantwortet Chat, Embeddings und Bilder über seine OpenAI-kompatible
 * Schnittstelle unter {@code /v1}; seine Modelle und deren Fähigkeiten ({@code completion}, {@code vision},
 * {@code embedding}) meldet nur die eigene Schnittstelle ({@code /api/tags}, {@code /api/show}).
 */
public enum AiProvider {

    /** OpenAI-kompatibler Dienst; die Basisadresse endet meist auf {@code /v1}. */
    OPENAI_COMPATIBLE("openai-compatible", "OpenAI-kompatibel"),
    /** Ollama-Server; die Basisadresse ist der Server selbst, etwa {@code http://localhost:11434}, ohne API-Key. */
    OLLAMA("ollama", "Ollama");

    /** Adresse eines Ollama-Servers auf diesem Rechner. */
    public static final String OLLAMA_DEFAULT_URL = "http://localhost:11434";

    private final String key;
    private final String displayName;

    AiProvider(String key, String displayName) {
        this.key = key;
        this.displayName = displayName;
    }

    /** Wert in der Konfiguration. */
    public String key() {
        return key;
    }

    /** Anzeigename in der Oberfläche. */
    public String displayName() {
        return displayName;
    }

    /**
     * @return die Art zum Schlüssel (Groß-/Kleinschreibung egal); {@code null} oder leer heißt OpenAI-kompatibel
     * @throws IllegalArgumentException bei einem unbekannten Schlüssel
     */
    public static AiProvider fromKey(String key) {
        if (key == null || key.isBlank()) {
            return OPENAI_COMPATIBLE;
        }
        for (AiProvider provider : values()) {
            if (provider.key.equalsIgnoreCase(key.strip())) {
                return provider;
            }
        }
        throw new IllegalArgumentException(AiServiceSettings.PREFIX + AiServiceSettings.PROVIDER
                + " ist „openai-compatible“ oder „ollama“");
    }
}
