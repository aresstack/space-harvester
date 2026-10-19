package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.configuration.ConfigurationListener;
import com.aresstack.spaceharvester.application.configuration.ConfigurationRejectedException;
import com.aresstack.spaceharvester.application.configuration.HarvesterConfiguration;
import com.aresstack.spaceharvester.application.configuration.Swappable;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementProcessor;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.TextSegmentation;
import com.aresstack.spaceharvester.application.passages.PassageChunks;
import com.aresstack.spaceharvester.application.passages.PassageSegmentation;
import com.aresstack.spaceharvester.application.passages.SentenceSegmenter;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.application.ranking.SearchReranker;
import com.aresstack.spaceharvester.application.ranking.SwappableReranker;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.aresstack.spaceharvester.source.ai.RemoteAi;
import com.aresstack.spaceharvester.source.ai.StatementChatModels;
import com.aresstack.spaceharvester.source.ai.ChatNavigationLabeler;
import com.aresstack.spaceharvester.source.ai.VisionImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.beans.factory.annotation.Value;
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
                                          ObjectProvider<BuildProperties> build, LiveConfiguration live,
                                          ObjectProvider<SentenceSegmenter> sentences) {
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
        // #181: je semantischem Absatz ein Embedding, wie im Client mit withPassages (ADR 0020).
        TextSegmentation rules = KnowledgeTextSegmentation.defaults();
        return new EmbeddingProcessor(() -> model.get().provider().orElse(null), properties.embeddingChunkChars(),
                KnowledgeTextSegmentation.of(properties.embeddingChunkTokens(),
                        properties.embeddingChunkOverlapSentences()),
                sourceId -> model.get().ai().mayProcessExternally(sourceId))
                .withPassages(new PassageChunks(new PassageSegmentation(sentences.getIfAvailable(
                        () -> EnrichmentConfiguration.sentences(rules)), rules)));
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

    /**
     * Bildbeschreibung über das Vision-Modell (ADR 0021): folgt {@code ai.vision-model}, {@code base-url} und
     * {@code external-processing-sources} der wirksamen Konfiguration; ohne Modell antworten die Bildrouten mit
     * {@code image-description-unavailable}.
     */
    @Bean
    ImageDescriber visionImageDescriber(ConfiguredSourceCatalog catalog, TrustBundles trust,
                                  ObjectProvider<BuildProperties> build, LiveConfiguration live) {
        return new VisionImageDescriber(() -> live.current().ai(), catalog, trust, userAgent(build));
    }

    /**
     * Benennung der Themenabschnitte der Dokumentnavigation über das Chat-Modell (#181, ADR 0020): folgt
     * {@code ai.chat-model} und {@code external-processing-sources} der wirksamen Konfiguration; ohne Modell gelten die
     * Bezeichnungen der Analyse.
     */
    @Bean
    NavigationLabelModel navigationLabels(ConfiguredSourceCatalog catalog, TrustBundles trust,
                                          ObjectProvider<BuildProperties> build, LiveConfiguration live) {
        return new ChatNavigationLabeler(() -> live.current().ai(), catalog, trust, userAgent(build));
    }

    /** Hintergrund für das Nachladen neuerer Versionen von Relevanztreffern (ADR 0011, Punkt 7). */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnProperty(prefix = "space-harvester.ai", name = "embedding-model")
    ExecutorService relevanceRenewals() {
        return Enrichments.background(100);
    }

    /**
     * Satzanalyse über das Chat-Modell (Prozessor {@code statements}, #108), nur mit
     * {@code space-harvester.enrichment.statements.enabled=true} (Standard aus, wie im Enterprise AI Client). Das
     * Chat-Modell ({@code ai.chat-model}, {@code base-url}) und die Freigabe je Quelle
     * ({@code ai.external-processing-sources}) folgen der wirksamen Konfiguration; ohne Chat-Modell wird der Prozessor
     * übersprungen, und Inhalte nicht freigegebener Quellen gehen nie an den Dienst. {@code verify},
     * {@code max-paragraphs} und {@code max-paragraph-chars} wirken beim Start.
     */
    @Bean
    @ConditionalOnProperty(prefix = "space-harvester.enrichment.statements", name = "enabled", havingValue = "true")
    StatementProcessor statementProcessor(ConfiguredSourceCatalog catalog, TrustBundles trust,
                                          ObjectProvider<BuildProperties> build, LiveConfiguration live,
                                          SentenceSegmenter sentences,
                                          @Value("${space-harvester.enrichment.statements.verify:"
                                                  + StatementSettings.DEFAULT_VERIFY + "}") boolean verify,
                                          @Value("${space-harvester.enrichment.statements.max-paragraphs:"
                                                  + StatementSettings.DEFAULT_MAX_PARAGRAPHS + "}") int maxParagraphs,
                                          @Value("${space-harvester.enrichment.statements.max-paragraph-chars:"
                                                  + StatementSettings.DEFAULT_MAX_PARAGRAPH_CHARS + "}")
                                          int maxParagraphChars) {
        StatementSettings settings = new StatementSettings(true, verify, maxParagraphs, maxParagraphChars);
        StatementChatModels chats = new StatementChatModels(() -> live.current().ai(), catalog, trust,
                userAgent(build));
        return new StatementProcessor(sentences, () -> settings, chats, chats.mayProcessExternally());
    }

    /** Hintergrund der Anreicherungen, die über das Netz rechnen: Embeddings und Satzanalyse. */
    @Bean(destroyMethod = "shutdownNow")
    @ConditionalOnExpression("'${space-harvester.ai.embedding-model:}' != '' "
            + "or '${space-harvester.enrichment.statements.enabled:false}' == 'true'")
    ExecutorService enrichmentBackground() {
        return Enrichments.background(1000);
    }
}
