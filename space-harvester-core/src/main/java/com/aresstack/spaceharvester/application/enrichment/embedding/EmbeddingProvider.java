package com.aresstack.spaceharvester.application.enrichment.embedding;

import java.util.List;

/**
 * Port zu einem Embedding-Modell. Die Umsetzung ist der externe KI-Dienst ({@code source.ai.RemoteAi}, ADR 0011)
 * über den Netzwerk-Stack mit PAC und Proxy; ein Modell im Prozess gibt es nicht mehr. Umsetzungen sind
 * deterministisch für dasselbe Modell und denselben Text, senden nur die übergebenen Texte und protokollieren sie nie.
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

    /**
     * Bettet Suchanfragen ein, in derselben Reihenfolge. Dienste, die Anfragen anders als Dokumente behandeln,
     * überschreiben das; sonst wie {@link #embed}.
     *
     * @return je Text ein Vektor mit {@link #dimensions()} endlichen Werten
     * @throws EmbeddingUnavailableException wenn das Modell nicht erreichbar oder überlastet ist
     */
    default List<float[]> embedQueries(List<String> texts) {
        return embed(texts);
    }
}
