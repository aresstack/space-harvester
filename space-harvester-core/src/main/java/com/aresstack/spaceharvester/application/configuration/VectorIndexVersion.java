package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.util.Objects;
import java.util.Optional;

/**
 * Wozu die gespeicherten Vektoren passen: Embedding-Modell und Dimension. Wechselt eines davon, sind alte Vektoren mit
 * neuen nicht vergleichbar; die Anreicherung {@code embedding} trägt beides schon in ihrer Version und berechnet
 * fehlende oder veraltete Vektoren im Hintergrund aus dem Cache neu, ohne Inhalte erneut von der Quelle zu laden.
 *
 * @param model      Embedding-Modell
 * @param dimensions Länge der Vektoren
 */
public record VectorIndexVersion(String model, int dimensions) {

    public VectorIndexVersion {
        Objects.requireNonNull(model, "model");
        if (dimensions < 1) {
            throw new IllegalArgumentException("dimensions muss positiv sein");
        }
    }

    /** Die Version zu den Einstellungen oder leer, wenn keine Embeddings eingestellt sind. */
    public static Optional<VectorIndexVersion> of(AiServiceSettings ai) {
        return ai.embeddings() ? Optional.of(new VectorIndexVersion(ai.embeddingModel(), ai.embeddingDimensions()))
                : Optional.empty();
    }

    /** Kurzform wie {@code text-embedding-3-small+1536}. */
    public String id() {
        return model + "+" + dimensions;
    }
}
