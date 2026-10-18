package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.Swappable;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.ranking.SwappableReranker;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embeddings über den externen KI-Dienst (ADR 0011). Ohne {@code space-harvester.ai.embedding-model} gibt es keinen
 * Embedding-Prozessor, und {@code sort=relevance} antwortet mit {@code semantic-search-unavailable}. Mit ihm müssen
 * {@code base-url} und {@code embedding-dimensions} gesetzt sein, sonst startet der Dienst nicht. Eingebettet wird im
 * begrenzten Hintergrund der Anreicherungen und nur für Quellen aus {@code external-processing-sources}; die Freigabe je
 * Quelle, Embedding- und Rerank-Modell folgen der Konfiguration zur Laufzeit ({@link LiveConfiguration}).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    AiServiceSettings aiServiceSettings(AiProperties properties) {
        return properties.toSettings();
    }

    /**
     * Das Embedding-Modell wechselt mit der Konfiguration ({@code ai.embedding-model}, {@code embedding-dimensions},
     * {@code base-url}, ADR 0015); ohne Modell bettet der Prozessor nichts ein und {@code sort=relevance} antwortet mit
     * {@code semantic-search-unavailable}. Die Kette (Prozessor, Hintergrund) entsteht nur, wenn beim Start ein Modell
     * eingestellt ist: Embeddings erstmals einschalten wirkt erst nach einem Neustart.
     */
    @Bean
    @ConditionalOnProperty(prefix = "space-harvester.ai", name = "embedding-model")
    EmbeddingProcessor embeddingProcessor(AiServiceSettings settings, AiProperties properties,
                                          ConfiguredSourceCatalog catalog, TrustBundles trust,
                                          ObjectProvider<BuildProperties> build, LiveConfiguration live) {
        if (!settings.embeddings()) {
            throw new IllegalStateException("space-harvester.ai.embedding-model braucht base-url und "
                    + "embedding-dimensions");
        }
        String userAgent = userAgent(build);
        AiServiceSettings initial = live.current().ai();
        Swappable<EmbeddingGeneration> model = Swappable.of(new EmbeddingGeneration(embeddingModel(initial, catalog,
                trust, userAgent), initial));
        live.listen(change -> {
            if (!change.embeddingChanged()) {
                return ConfigurationListener.Preparation.NOTHING;
            }
            EmbeddingGeneration next;
            try {
                next = new EmbeddingGeneration(embeddingModel(change.next().ai(), catalog, trust, userAgent),
                        change.next().ai());
            } catch (RuntimeException e) {
                throw new ConfigurationRejectedException("ai." + AiServiceSettings.EMBEDDING_MODEL,
                        "Das Embedding-Modell lässt sich nicht einsetzen (" + e.getClass().getSimpleName() + ")");
            }
            return () -> model.swap(next);
        });
        // Freigabe aus denselben Einstellungen wie das Modell: Eine geänderte Freigabe baut ohnehin eine neue
        // Generation, und neu freigegebene Inhalte gehen nie an den Endpunkt einer früheren.
        return new EmbeddingProcessor(() -> model.get().provider().orElse(null), properties.embeddingChunkChars(),
                KnowledgeTextSegmentation.of(properties.embeddingChunkTokens(),
                        properties.embeddingChunkOverlapSentences()),
                sourceId -> model.get().ai().mayProcessExternally(sourceId));
    }

    /** Ein Embedding-Modell (oder keines) mit den Einstellungen, aus denen es entstand, samt Freigabe je Quelle. */
    private record EmbeddingGeneration(Optional<EmbeddingProvider> provider, AiServiceSettings ai) {
    }

    /**
     * Cross-Encoder des Dienstes für {@code sort=relevance} (#181): ordnet das Fenster nach der Kosinusrangfolge um, mit
     * Texten nur aus Quellen in {@code external-processing-sources}. Modell und {@code search.rerank} wechseln zur
     * Laufzeit (ADR 0015); ohne beides bleibt die Reihenfolge.
     */
    @Bean
    SwappableReranker searchReranker(AiServiceSettings settings, ConfiguredSourceCatalog catalog, TrustBundles trust,
                                     ObjectProvider<BuildProperties> build, LiveConfiguration live) {
        if (settings.rerankModel() != null && !settings.reranking()) {
            throw new IllegalStateException("space-harvester.ai.rerank-model braucht base-url");
        }
        String userAgent = userAgent(build);
        SwappableReranker reranker = new SwappableReranker(modelReranker(live.current(), catalog, trust, userAgent));
        live.listen(change -> {
            if (!change.rerankerChanged()) {
                return ConfigurationListener.Preparation.NOTHING;
            }
            SearchReranker next;
            try {
                next = modelReranker(change.next(), catalog, trust, userAgent);
            } catch (RuntimeException e) {
                throw new ConfigurationRejectedException("ai." + AiServiceSettings.RERANK_MODEL,
                        "Das Rerank-Modell lässt sich nicht einsetzen (" + e.getClass().getSimpleName() + ")");
            }
            return () -> reranker.swap(next);
        });
        return reranker;
    }

    /**
     * Das Chat-Modell für die Suchvarianten der progressiven Suche (ADR 0017): folgt {@code ai.chat-model} und
     * {@code base-url} der wirksamen Konfiguration, ohne beides formulieren die Regeln die Varianten.
     */
    @Bean
    QueryVariantModels queryVariantModels(ConfiguredSourceCatalog catalog, TrustBundles trust,
                                          ObjectProvider<BuildProperties> build, LiveConfiguration live) {
        return new QueryVariantModels(() -> live.current().ai(), catalog, trust, userAgent(build));
    }

    /** Das Embedding-Modell des Dienstes oder leer ohne Embeddings. */
    private static Optional<EmbeddingProvider> embeddingModel(AiServiceSettings ai, ConfiguredSourceCatalog catalog,
                                                              TrustBundles trust, String userAgent) {
        if (!ai.embeddings()) {
            return Optional.empty();
        }
        EmbeddingProvider provider = RemoteAi.of(ai, catalog, trust, userAgent).embeddings();
        EmbeddingProcessor.check(provider);
        return Optional.of(provider);
    }

    /**
     * Der Cross-Encoder, wenn die Konfiguration umordnen lässt. Die Freigabe je Quelle stammt aus denselben
     * Einstellungen wie Endpunkt und Modell; eine geänderte Freigabe baut einen neuen Reranker.
     */
    private static SearchReranker modelReranker(HarvesterConfiguration configuration, ConfiguredSourceCatalog catalog,
                                                TrustBundles trust, String userAgent) {
        if (!configuration.reranking()) {
            return SearchReranker.NONE;
        }
        AiServiceSettings ai = configuration.ai();
        return new ModelReranker(ai.rerankModel(), RemoteAi.of(ai, catalog, trust, userAgent).reranker(),
                ai::mayProcessExternally);
    }

    private static String userAgent(ObjectProvider<BuildProperties> build) {
        BuildProperties info = build.getIfAvailable();
        return "space-harvester/" + (info != null ? info.getVersion() : "unknown");
    }

    /** Hintergrund für das Nachladen neuerer Versionen von Relevanztreffern (ADR 0011, Punkt 7). */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "space-harvester.ai", name = "embedding-model")
    ExecutorService relevanceRenewals() {
        return Enrichments.background(100);
    }

    /** Hintergrund der Anreicherungen, die über das Netz rechnen. */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "space-harvester.ai", name = "embedding-model")
    ExecutorService enrichmentBackground() {
        return Enrichments.background(1000);
    }
}
