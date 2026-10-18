package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
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
 * begrenzten Hintergrund der Anreicherungen und nur für Quellen aus {@code external-processing-sources}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
class AiConfiguration {

    @Bean
    AiServiceSettings aiServiceSettings(AiProperties properties) {
        return properties.toSettings();
    }

    @Bean
    @ConditionalOnProperty(prefix = "space-harvester.ai", name = "embedding-model")
    EmbeddingProcessor embeddingProcessor(AiServiceSettings settings, AiProperties properties,
                                          ConfiguredSourceCatalog catalog, TrustBundles trust,
                                          ObjectProvider<BuildProperties> build) {
        if (!settings.embeddings()) {
            throw new IllegalStateException("space-harvester.ai.embedding-model braucht base-url und "
                    + "embedding-dimensions");
        }
        BuildProperties info = build.getIfAvailable();
        String userAgent = "space-harvester/" + (info != null ? info.getVersion() : "unknown");
        return new EmbeddingProcessor(RemoteAi.of(settings, catalog, trust, userAgent).embeddings(),
                properties.embeddingChunkChars(), settings::mayProcessExternally);
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
