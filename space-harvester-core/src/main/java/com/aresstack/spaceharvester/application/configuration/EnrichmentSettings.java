package com.aresstack.spaceharvester.application.configuration;

/**
 * Einstellungen der Analysen je Anhang und enthaltener Datei (ADR 0026, AP9a) unter
 * {@code space-harvester.enrichment.documents.*}; Server und App lesen dieselben Schlüssel, wirksam beim nächsten Lauf.
 *
 * @param documents                 ob Anhänge und enthaltene Dateien eigene Analysen bekommen; abgeschaltet entsteht
 *                                  nichts Neues, vorhandene Stände bleiben lesbar
 * @param documentEmbeddingMaxChars höchstens so viele Zeichen eines Dokuments gehen an das Embedding-Modell, 0 ohne
 *                                  Grenze; eine Kürzung steht am Analysestand, Absätze und Navigation gelten ohne
 *                                  Grenze
 */
public record EnrichmentSettings(boolean documents, long documentEmbeddingMaxChars) {

    public static final String DOCUMENTS_ENABLED = "enrichment.documents.enabled";
    public static final String DOCUMENTS_EMBEDDING_MAX_CHARS = "enrichment.documents.embedding-max-chars";
    public static final long DEFAULT_EMBEDDING_MAX_CHARS = 2_000_000;

    public static final EnrichmentSettings DEFAULT = new EnrichmentSettings(true, DEFAULT_EMBEDDING_MAX_CHARS);

    public EnrichmentSettings {
        if (documentEmbeddingMaxChars < 0) {
            throw new IllegalArgumentException("Die Zeichengrenze darf nicht negativ sein (0 = ohne Grenze)");
        }
    }
}
