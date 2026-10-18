package com.aresstack.spaceharvester.application.configuration;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import java.util.Objects;
import java.util.Optional;

/**
 * Ein bevorstehender Wechsel von {@link #previous} zu {@link #next}, wie ihn ein {@link ConfigurationListener}
 * vorbereitet. Die Fragen wie {@link #embeddingChanged()} helfen einem Beobachter, nur das neu zu bauen, was er braucht.
 *
 * @param previous die bisher wirksame Konfiguration
 * @param next     die neue, schon geprüfte Konfiguration; ihr Speicherort ist der bisherige
 * @param version  die Version, die {@link #next} beim Aktivieren bekommt
 */
public record ConfigurationChange(HarvesterConfiguration previous, HarvesterConfiguration next, long version) {

    public ConfigurationChange {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(next, "next");
    }

    /** Ob sich die Verbindung zum KI-Dienst ändert (Adresse, API-Key, Frist, Vertrauen, Freigaben). */
    public boolean aiServiceChanged() {
        AiServiceSettings a = previous.ai();
        AiServiceSettings b = next.ai();
        return !Objects.equals(a.baseUrl(), b.baseUrl()) || !Objects.equals(a.apiKey(), b.apiKey())
                || !Objects.equals(a.timeout(), b.timeout()) || !Objects.equals(a.trustBundle(), b.trustBundle())
                || !Objects.equals(a.externalProcessingSources(), b.externalProcessingSources())
                || !previous.network().equals(next.network());
    }

    /** Ob ein Embedding-Client neu gebaut werden muss: Dienst oder Modell. */
    public boolean embeddingChanged() {
        return aiServiceChanged() || !Objects.equals(previous.ai().embeddingModel(), next.ai().embeddingModel())
                || !Objects.equals(previous.ai().embeddingDimensions(), next.ai().embeddingDimensions());
    }

    /** Ob ein Reranker neu gebaut werden muss: Dienst, Modell oder Option. */
    public boolean rerankerChanged() {
        return aiServiceChanged() || !Objects.equals(previous.ai().rerankModel(), next.ai().rerankModel())
                || previous.reranking() != next.reranking();
    }

    /**
     * Wechsel der Vektorversion: Modell oder Dimension anders, Embeddings ein- oder ausgeschaltet. Strang Q hängt hier
     * die Neuberechnung im Hintergrund an; leer, wenn die Vektoren gültig bleiben.
     */
    public Optional<VectorIndexChange> vectorIndex() {
        Optional<VectorIndexVersion> from = VectorIndexVersion.of(previous.ai());
        Optional<VectorIndexVersion> to = VectorIndexVersion.of(next.ai());
        return from.equals(to) ? Optional.empty() : Optional.of(new VectorIndexChange(from, to));
    }

    public boolean searchChanged() {
        return !previous.search().equals(next.search()) || previous.semanticSearch() != next.semanticSearch()
                || previous.reranking() != next.reranking();
    }

    public boolean syncChanged() {
        return !previous.sync().equals(next.sync());
    }

    public boolean networkChanged() {
        return !previous.network().equals(next.network());
    }

    /**
     * Von welcher zu welcher Vektorversion; leer heißt ohne Embeddings.
     */
    public record VectorIndexChange(Optional<VectorIndexVersion> from, Optional<VectorIndexVersion> to) {
    }
}
