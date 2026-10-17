package com.aresstack.spaceharvester.application.enrichment.embedding;

import java.util.List;

/**
 * Port zu einem Embedding-Modell, lokal (z. B. ONNX im Prozess) oder entfernt (HTTP über den Netzwerk-Stack mit PAC,
 * Proxy und mTLS). Umsetzungen sind deterministisch für dasselbe Modell und denselben Text, senden nur die übergebenen
 * Texte und protokollieren sie nie.
 */
public interface EmbeddingProvider {

    /**
     * Kennung des Modells samt Version, z. B. {@code e5-small-v2@2024-01}; ändert sie sich, veralten alle Vektoren.
     * Nur {@code [A-Za-z0-9._@/:-]}, höchstens 100 Zeichen.
     */
    String modelId();

    /** Länge jedes Vektors, fest je Modell. */
    int dimensions();

    /**
     * Bettet Texte ein, in derselben Reihenfolge.
     *
     * @return je Text ein Vektor mit {@link #dimensions()} endlichen Werten
     * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar oder überlastet ist
     */
    List<float[]> embed(List<String> texts);
}
