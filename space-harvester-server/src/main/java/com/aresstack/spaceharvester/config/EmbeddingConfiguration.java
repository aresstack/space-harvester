package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.embedding.onnx.E5Defaults;
import com.aresstack.spaceharvester.embedding.onnx.OnnxEmbeddingProvider;
import com.aresstack.spaceharvester.embedding.onnx.OnnxEmbeddingSettings;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Embeddings als Anreicherung (#89, ADR 0004). Standard ist {@code space-harvester.embedding.provider=none}: kein
 * Provider, kein Prozessor, keine Änderung. Mit {@code onnx} bettet ein lokales Modell im Prozess ein; fehlen dessen
 * Dateien oder stimmen sie nicht, scheitert nur die Anreicherung {@code embedding}, nie der Abgleich.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(EmbeddingProperties.class)
@ConditionalOnProperty(prefix = "space-harvester.embedding", name = "provider", havingValue = "onnx")
class EmbeddingConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(EmbeddingConfiguration.class);

    @Bean(destroyMethod = "close")
    OnnxEmbeddingProvider onnxEmbeddingProvider(EmbeddingProperties properties) {
        EmbeddingProperties.Onnx onnx = properties.onnx();
        OnnxEmbeddingSettings settings = new OnnxEmbeddingSettings(onnx.resolvedModelFile(),
                onnx.resolvedTokenizerFile(), onnx.modelSha256(), onnx.tokenizerSha256(), onnx.modelId(),
                onnx.dimensions(), onnx.maxTokens(), onnx.batchSize(), onnx.threads(), onnx.maxConcurrent(),
                onnx.acquireTimeout(), onnx.retryAfter(), E5Defaults.PASSAGE_PREFIX, E5Defaults.QUERY_PREFIX,
                E5Defaults.PAD_TOKEN_ID);
        if (!Files.isRegularFile(settings.modelFile()) || !Files.isRegularFile(settings.tokenizerFile())) {
            // Kein Abbruch: Abgleich und Suche laufen ohne Embeddings weiter.
            LOG.warn("Embedding-Modell {}: Modell- oder Tokenizerdatei fehlt; die Anreicherung embedding scheitert, "
                    + "bis sie bereitstehen", settings.modelId());
        }
        return new OnnxEmbeddingProvider(settings);
    }

    @Bean
    EmbeddingProcessor embeddingProcessor(EmbeddingProvider provider, EmbeddingProperties properties) {
        return new EmbeddingProcessor(provider, properties.maxChunkChars());
    }
}
