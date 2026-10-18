package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.modelcatalog.ModelCategory;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.aresstack.enterpriseai.embedding.api.EmbeddingBatch;
import com.aresstack.enterpriseai.embedding.api.EmbeddingException;
import com.aresstack.enterpriseai.embedding.openai.OpenAiCompatibleEmbeddingAdapter;
import com.aresstack.enterpriseai.embedding.openai.OpenAiCompatibleEmbeddingConfiguration;
import com.aresstack.enterpriseai.model.api.ModelCatalogException;
import com.aresstack.enterpriseai.model.api.RerankException;
import com.aresstack.enterpriseai.model.kipitz.KipitzModelCatalogAdapter;
import com.aresstack.enterpriseai.model.kipitz.KipitzModelCatalogConfig;
import com.aresstack.enterpriseai.model.kipitz.KipitzRerankAdapter;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Der externe KI-Dienst über die unveränderten Adapter des Enterprise AI Client (ADR 0011):
 * {@code OpenAiCompatibleEmbeddingAdapter} für {@code POST /embeddings} und {@code KipitzModelCatalogAdapter} für
 * {@code GET /models}. Beide verbinden über dieselbe Proxy-Route je Ziel wie die Quellen ({@link HttpRoutePort}: PAC,
 * Proxy, fail-closed) und über das Vertrauen aus {@code trust-bundle} oder der JVM. Der API-Key geht je Anfrage als
 * Bearer-Token mit und wird nie protokolliert; Meldungen der Adapter nennen nur Statuscodes und Pfade.
 */
public final class RemoteAi {

    private final AiServiceSettings settings;
    private final HttpRoutePort routes;
    private final SSLSocketFactory tls;
    private final String userAgent;

    /**
     * Mit derselben Proxy-Route wie die Quellen des Katalogs.
     *
     * @throws IllegalArgumentException wenn {@code trust-bundle} nicht konfiguriert ist
     */
    public static RemoteAi of(AiServiceSettings settings, ConfiguredSourceCatalog catalog, TrustBundles trustBundles,
                              String userAgent) {
        return new RemoteAi(settings, catalog.routes(), trustBundles, userAgent);
    }

    /**
     * @throws IllegalArgumentException wenn {@code trust-bundle} nicht konfiguriert ist
     */
    public RemoteAi(AiServiceSettings settings, HttpRoutePort routes, TrustBundles trustBundles, String userAgent) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.routes = Objects.requireNonNull(routes, "routes");
        this.userAgent = Objects.requireNonNull(userAgent, "userAgent");
        X509TrustManager trust = settings.trustBundle() == null ? trustBundles.defaultTrust()
                : trustBundles.trustManager(settings.trustBundle());
        this.tls = trust == null ? null : socketFactory(trust);
    }

    /**
     * Der Port zum Embedding-Modell.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst, Modell oder Dimension
     */
    public EmbeddingProvider embeddings() {
        if (!settings.embeddings()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED,
                    "Embedding-Dienst, -Modell oder -Dimension fehlt");
        }
        OpenAiCompatibleEmbeddingAdapter adapter = adapter(settings.embeddingModel(),
                settings.embeddingDimensions());
        String modelId = settings.embeddingModel();
        int dimensions = settings.embeddingDimensions();
        return new EmbeddingProvider() {
            @Override
            public String modelId() {
                return modelId;
            }

            @Override
            public int dimensions() {
                return dimensions;
            }

            @Override
            public List<float[]> embed(List<String> texts) {
                return vectors(() -> adapter.embed(texts));
            }

            @Override
            public List<float[]> embedQueries(List<String> texts) {
                return vectors(() -> adapter.embedQueries(texts));
            }
        };
    }

    /**
     * Der Cross-Encoder des Dienstes ({@code POST /rerank}, #181) über den unveränderten {@code KipitzRerankAdapter}.
     * Scheitert ein Aufruf, meldet der Bewerter {@link AiServiceException} ohne Anfrage- oder Dokumenttext.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Rerank-Modell
     */
    public ModelReranker.Scorer reranker() {
        if (!settings.reranking()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Rerank-Modell fehlt");
        }
        KipitzRerankAdapter adapter = new KipitzRerankAdapter(catalogConfig());
        String model = settings.rerankModel();
        return (query, documents) -> {
            try {
                return adapter.rerank(model, query, documents);
            } catch (RerankException e) {
                // Die Meldung des Adapters nennt nur Status und Ausnahmeklasse, nie Texte oder den Key.
                throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, e.getMessage());
            }
        };
    }

    /**
     * Die Modelle des Dienstes ({@code GET /models}).
     *
     * @throws AiServiceException ohne Dienst oder wenn er nicht wie erwartet antwortet
     */
    public List<AiModel> models() {
        if (!settings.configured()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Kein KI-Dienst eingerichtet");
        }
        List<ModelDescriptor> descriptors;
        try {
            descriptors = new KipitzModelCatalogAdapter(catalogConfig()).models();
        } catch (ModelCatalogException e) {
            throw catalogFailure(e);
        }
        List<AiModel> models = new ArrayList<>(descriptors.size());
        for (ModelDescriptor descriptor : descriptors) {
            Set<String> categories = new LinkedHashSet<>();
            for (ModelCategory category : descriptor.categories()) {
                categories.add(category.key());
            }
            models.add(new AiModel(descriptor.modelId(), descriptor.displayName(), categories));
        }
        return List.copyOf(models);
    }

    /**
     * Misst die Vektordimension eines Embedding-Modells mit einem kurzen, festen Text (die Dimension steht in keinem
     * Modellkatalog).
     *
     * @throws AiServiceException ohne Dienst oder wenn er nicht wie erwartet antwortet
     */
    public int measureDimension(String embeddingModel) {
        if (!settings.configured() || embeddingModel == null || embeddingModel.isBlank()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED,
                    "KI-Dienst oder Embedding-Modell fehlt");
        }
        try {
            return adapter(embeddingModel.strip(), 4096).measureDimension("Space Harvester");
        } catch (EmbeddingException e) {
            throw serviceFailure(e);
        }
    }

    /** Verbindung zum Katalog der Enterprise-API: Route, TLS und Token wie die übrigen Aufrufe. */
    private KipitzModelCatalogConfig catalogConfig() {
        KipitzModelCatalogConfig.Builder config = KipitzModelCatalogConfig.builder(settings.baseUrl())
                .routes(routes)
                .userAgent(userAgent)
                .connectTimeoutMillis(timeoutMillis())
                .readTimeoutMillis(timeoutMillis());
        if (settings.apiKey() != null) {
            String key = settings.apiKey();
            config.bearerToken(() -> key);
        }
        if (tls != null) {
            config.sslSocketFactory(tls);
        }
        return config.build();
    }

    private OpenAiCompatibleEmbeddingAdapter adapter(String model, int dimension) {
        OpenAiCompatibleEmbeddingConfiguration.Builder config = OpenAiCompatibleEmbeddingConfiguration
                .builder(settings.baseUrl().toString(), model, dimension)
                .routes(routes)
                .userAgent(userAgent)
                .connectTimeoutMillis(timeoutMillis())
                .readTimeoutMillis(timeoutMillis());
        if (tls != null) {
            config.sslSocketFactory(tls);
        }
        String key = settings.apiKey();
        return new OpenAiCompatibleEmbeddingAdapter(config.build(), () -> key == null ? null : key.toCharArray());
    }

    private int timeoutMillis() {
        return (int) Math.min(Integer.MAX_VALUE, settings.timeout().toMillis());
    }

    private static List<float[]> vectors(java.util.function.Supplier<EmbeddingBatch> call) {
        EmbeddingBatch batch;
        try {
            batch = call.get();
        } catch (EmbeddingException e) {
            // Nur die Art: Meldungen können den Endpunkt nennen, nie Texte oder den Key.
            throw new EmbeddingUnavailableException("Embedding-Dienst: " + e.kind());
        }
        List<float[]> vectors = new ArrayList<>(batch.size());
        for (int i = 0; i < batch.size(); i++) {
            vectors.add(batch.get(i).values());
        }
        return vectors;
    }

    private static AiServiceException serviceFailure(EmbeddingException e) {
        AiServiceException.Reason reason = switch (e.kind()) {
            case AUTHENTICATION -> AiServiceException.Reason.UNAUTHORIZED;
            case UNAVAILABLE, RATE_LIMITED, PROVIDER_ERROR -> AiServiceException.Reason.UNREACHABLE;
            case REJECTED, INVALID_RESPONSE -> AiServiceException.Reason.INVALID_RESPONSE;
        };
        return new AiServiceException(reason, "Embedding-Dienst: " + e.kind());
    }

    private static AiServiceException catalogFailure(ModelCatalogException e) {
        String message = String.valueOf(e.getMessage());
        AiServiceException.Reason reason;
        if (message.contains("HTTP 401") || message.contains("HTTP 403")) {
            reason = AiServiceException.Reason.UNAUTHORIZED;
        } else if (message.contains("Timeout")) {
            reason = AiServiceException.Reason.TIMEOUT;
        } else if (message.contains("nicht lesbar") || message.contains("HTTP ")) {
            reason = AiServiceException.Reason.INVALID_RESPONSE;
        } else {
            reason = AiServiceException.Reason.UNREACHABLE;
        }
        // Die Meldung des Adapters nennt nur Pfad, Status und Ausnahmeklasse.
        return new AiServiceException(reason, message);
    }

    private static SSLSocketFactory socketFactory(X509TrustManager trust) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {trust}, null);
            return context.getSocketFactory();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS-Kontext für den KI-Dienst nicht verfügbar", e);
        }
    }
}
