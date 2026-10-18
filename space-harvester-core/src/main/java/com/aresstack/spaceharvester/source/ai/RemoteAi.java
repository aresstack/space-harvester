package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelCategory;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.openai.OpenAiCompatibleChatAdapter;
import com.aresstack.enterpriseai.chat.openai.OpenAiCompatibleChatConfig;
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
import com.aresstack.spaceharvester.application.query.progressive.QueryVariantModel;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.net.SocketTimeoutException;
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
 * {@code OpenAiCompatibleEmbeddingAdapter} für {@code POST /embeddings}, {@code KipitzModelCatalogAdapter} für
 * {@code GET /models}, {@code KipitzRerankAdapter} für {@code POST /rerank} und {@code OpenAiCompatibleChatAdapter} für
 * {@code POST /chat/completions} (ADR 0017). Alle verbinden über dieselbe Proxy-Route je Ziel wie die Quellen ({@link HttpRoutePort}: PAC,
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
     * Das Chat-Modell als Formulierer von Suchvarianten ({@code POST /chat/completions}, ADR 0017) über den
     * unveränderten {@code OpenAiCompatibleChatAdapter}. An den Dienst geht
     * nur die Frage des Nutzers, nie Inhalt einer Quelle; scheitert ein Aufruf, meldet das Modell
     * {@link AiServiceException} ohne Frage, Antwort oder Key.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public QueryVariantModel queryVariants() {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        OpenAiCompatibleChatAdapter adapter = new OpenAiCompatibleChatAdapter(chatConfig());
        ChatOptions options = ChatOptions.builder().temperature(0.0).build();
        return (instructions, question) -> {
            try {
                return adapter.complete(new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(question)), options)).content();
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
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

    /** Verbindung zum Chat-Modell: Route, TLS, Token und Fristen wie {@link #catalogConfig()}. */
    private OpenAiCompatibleChatConfig chatConfig() {
        OpenAiCompatibleChatConfig.Builder config = OpenAiCompatibleChatConfig
                .builder(settings.baseUrl(), settings.chatModel())
                .routes(routes)
                .userAgent(userAgent)
                .connectTimeoutMillis(timeoutMillis())
                .readTimeoutMillis(timeoutMillis());
        if (settings.apiKey() != null) {
            config.bearerToken(OpenAiCompatibleChatConfig.TokenSource.fixed(settings.apiKey()));
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

    /** Nur Art und Status: Die Meldung des Adapters kann gekürzte Serverantworten enthalten, die die Frage zitieren. */
    private static AiServiceException chatFailure(ChatCompletionException e) {
        AiServiceException.Reason reason = switch (e.kind()) {
            case AUTHENTICATION -> AiServiceException.Reason.UNAUTHORIZED;
            case TRANSPORT -> e.getCause() instanceof SocketTimeoutException ? AiServiceException.Reason.TIMEOUT
                    : AiServiceException.Reason.UNREACHABLE;
            case CREDENTIALS_UNAVAILABLE, RATE_LIMITED, PROVIDER_ERROR -> AiServiceException.Reason.UNREACHABLE;
            case INVALID_REQUEST, PROTOCOL -> AiServiceException.Reason.INVALID_RESPONSE;
        };
        return new AiServiceException(reason, "Chat-Dienst: " + e.kind()
                + (e.statusCode() > 0 ? " (HTTP " + e.statusCode() + ")" : ""));
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
