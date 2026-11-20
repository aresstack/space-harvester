package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.acropolis.image.ImageAnalysis;
import com.aresstack.enterpriseai.astu.acropolis.image.ImageAnalysisCache;
import com.aresstack.enterpriseai.astu.acropolis.image.SourceImageAnalysisUseCase;
import com.aresstack.enterpriseai.astu.acropolis.navigation.ChatSectionLabeler;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelCategory;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
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
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.enrichment.statements.StatementChat;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.application.query.progressive.QueryVariantModel;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.source.ConfiguredSourceCatalog;
import com.aresstack.spaceharvester.source.TrustBundles;
import java.net.SocketTimeoutException;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BinaryOperator;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Der externe KI-Dienst über die unveränderten Adapter des Enterprise AI Client (ADR 0011):
 * {@code OpenAiCompatibleEmbeddingAdapter} für {@code POST /embeddings}, {@code KipitzModelCatalogAdapter} für
 * {@code GET /models}, {@code KipitzRerankAdapter} für {@code POST /rerank} und {@code OpenAiCompatibleChatAdapter} für
 * {@code POST /chat/completions} (ADR 0017, Bildbeschreibung ADR 0021). Alle verbinden über dieselbe Proxy-Route je Ziel wie die Quellen ({@link HttpRoutePort}: PAC,
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
        // Modell@Fingerabdruck des Endpunkts wie bei Navigation und Satzanalyse (#403): Ein anderer Dienst mit demselben
        // Modellnamen kann andere Vektoren liefern; der Wechsel macht die Embeddings veraltet. Nie der Schlüssel.
        String modelId = settings.embeddingModel() + "@"
                + ChatNavigationLabeler.fingerprint(String.valueOf(settings.baseUrl()));
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
     * Das Chat-Modell als Gesprächspartner der App ({@code POST /chat/completions}, streamend) über den unveränderten
     * {@code OpenAiCompatibleChatAdapter}: derselbe Port, den der Enterprise AI Client für seinen Chat nutzt, hinter
     * der eigenen SPI {@link AiChat}, damit die Kopie aus enterprise-ai-sources hier im Quellenadapter bleibt. Der
     * Chat selbst gehört nicht in den Core (ADR 0012); hier gibt es nur die Verbindung mit Route, TLS, Token und
     * Fristen wie bei allen anderen Aufrufen. Was an den Dienst geht, entscheidet der Aufrufer (ADR 0028).
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public AiChat chat() {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        ChatCompletionPort adapter = chatPort(settings.chatModel(), timeoutMillis());
        return (request, listener) -> {
            List<ChatMessage> messages = new ArrayList<>();
            for (AiChat.Message message : request.messages()) {
                messages.add(switch (message.role()) {
                    case SYSTEM -> ChatMessage.system(message.text());
                    case USER -> ChatMessage.user(message.text());
                    case ASSISTANT -> ChatMessage.assistant(message.text());
                });
            }
            ChatOptions.Builder options = ChatOptions.builder().model(request.model());
            if (request.reasoningEffort() != null) {
                options.reasoningEffort(request.reasoningEffort());
            }
            StringBuilder received = new StringBuilder();
            ChatTask task = adapter.stream(new ChatRequest(messages, options.build()), new ChatStreamListener() {
                @Override
                public void onStart() {
                }

                @Override
                public void onDelta(String text) {
                    synchronized (received) {
                        received.append(text);
                    }
                    listener.delta(text);
                }

                @Override
                public void onComplete(ChatResponse response) {
                    String full;
                    synchronized (received) {
                        full = received.length() > 0 || response == null || response.content() == null
                                ? received.toString() : response.content();
                    }
                    listener.completed(full);
                }

                @Override
                public void onError(ChatCompletionException error) {
                    listener.failed(new AiChatException(AiChatException.Kind.valueOf(error.kind().name()),
                            error.statusCode(), error.getMessage(), error));
                }

                @Override
                public void onCancelled() {
                    listener.cancelled();
                }
            });
            return task::cancel;
        };
    }

    /** Obergrenze der Antwort des Chat-Modells für Suchvarianten in Tokens. */
    static final int MAX_VARIANT_TOKENS = 1024;

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
        ChatCompletionPort adapter = chatPort(settings.chatModel(), timeoutMillis());
        // Die Antwort ist eine kurze JSON-Liste; die Obergrenze hält auch einen fehlerhaften Dienst kurz.
        ChatOptions options = ChatOptions.builder().temperature(0.0).maxTokens(MAX_VARIANT_TOKENS).build();
        return (instructions, question) -> {
            try {
                return complete(adapter.complete(new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(question)), options)),
                        MAX_VARIANT_TOKENS, false);
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        };
    }

    /** Eigene Frist je Anfrage der Akronymauflösung, unabhängig von {@code ai.timeout} (#122). */
    public static final Duration EXPANSION_TIMEOUT = Duration.ofSeconds(90);

    /**
     * Das Chat-Modell als Ausschreiber von Akronymen ({@code POST /chat/completions}, #122) über den unveränderten
     * {@code OpenAiCompatibleChatAdapter}, mit eigener Frist ({@link #EXPANSION_TIMEOUT}). An den Dienst gehen nur die
     * kurzen Fundstellen, die der Auftrag freigegebener Quellen zusammenstellt; geprüft wird die Antwort dort. Scheitert
     * ein Aufruf, meldet das Modell {@link AiServiceException} ohne Text, Antwort oder Key.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public BinaryOperator<String> acronymExpansions() {
        return termCompletions();
    }

    /**
     * Dasselbe Chat-Modell für die Bedeutungsprüfung der Synonymerkennung (#336): Anweisung und JSON-Eingabe hinein,
     * der Text der Antwort heraus; geprüft wird die Antwort in {@code SynonymDiscovery}. Frist wie
     * {@link #acronymExpansions()}; das Ausgabelimit ist {@code ai.chat-max-tokens} (Standard 8192) und geht als
     * {@code max_tokens} mit. Endet die Antwort an diesem Limit ({@code finish_reason = length}), ist ihr JSON
     * abgeschnitten: Das Modell meldet {@code INVALID_RESPONSE} mit dem Hinweis auf die Einstellung, statt den
     * Rest als vollständige Antwort weiterzugeben.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public BinaryOperator<String> termCompletions() {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        ChatCompletionPort adapter = chatPort(settings.chatModel(), (int) EXPANSION_TIMEOUT.toMillis());
        int maxTokens = settings.chatMaxTokens();
        ChatOptions options = ChatOptions.builder().temperature(0.0).maxTokens(maxTokens).build();
        return (instructions, input) -> {
            try {
                return complete(adapter.complete(new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(input)), options)), maxTokens, true);
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        };
    }

    /** Eigene Frist je Anfrage der Abschnittsbenennung, unabhängig von {@code ai.timeout} (#181). */
    public static final Duration LABEL_TIMEOUT = Duration.ofSeconds(60);

    /**
     * Benennt Themenabschnitte der Dokumentnavigation mit dem Chat-Modell ({@code POST /chat/completions}, #181,
     * ADR 0020) über die unveränderte {@code ChatSectionLabeler} des Enterprise AI Client: dieselbe Anweisung, je 40
     * Abschnitte eine Anfrage, höchstens 700 Zeichen je Abschnitt. Scheitert eine Anfrage, gehen die folgenden Pakete nicht
     * mehr ans Netz; sie und das gescheiterte behalten die Bezeichnungen der Analyse, und das Ergebnis ist
     * unvollständig. Weder Text noch Antwort werden geloggt oder in
     * eine Meldung übernommen.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public NavigationLabelModel.Labels sectionLabels(String documentTitle, List<String> sectionTexts) {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        ChatCompletionPort adapter = chatPort(settings.chatModel(), (int) LABEL_TIMEOUT.toMillis());
        AtomicBoolean failed = new AtomicBoolean();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                if (failed.get()) {
                    // Nach dem ersten Fehler kein weiteres Paket ans Netz: jedes wartete sonst bis zur Frist.
                    throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Chat-Modell nicht erreichbar");
                }
                try {
                    return adapter.complete(request);
                } catch (RuntimeException e) {
                    failed.set(true);
                    throw e;
                }
            }

            @Override
            public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
                throw new UnsupportedOperationException("Abschnittsbenennung streamt nicht");
            }
        };
        String model = settings.chatModel();
        List<String> labels = new ChatSectionLabeler(port, () -> model).labels(documentTitle, sectionTexts);
        return new NavigationLabelModel.Labels(labels, !failed.get());
    }

    /**
     * Das Chat-Modell der Satzanalyse ({@code POST /chat/completions}, Prozessor {@code statements}, #108) über den
     * unveränderten {@code OpenAiCompatibleChatAdapter}, wie {@code ChatStatementExtractor} im Enterprise AI Client:
     * nur das Modell gesetzt, keine Token-Grenze, keine Sampling-Optionen. Hier geht Seitentext an den Dienst; das
     * dürfen nur Quellen in {@code external-processing-sources}, was der Prozessor je Seite prüft. Scheitert ein
     * Aufruf, meldet das Modell {@link AiServiceException} ohne Nachrichten, Antwort oder Key.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Chat-Modell
     */
    public StatementChat statementChat() {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        ChatCompletionPort adapter = chatPort(settings.chatModel(), timeoutMillis());
        String model = settings.chatModel();
        String endpoint = ChatNavigationLabeler.fingerprint(String.valueOf(settings.baseUrl()));
        ChatOptions options = ChatOptions.builder().model(model).build();
        return new StatementChat() {
            @Override
            public String model() {
                return model;
            }

            @Override
            public String endpoint() {
                return endpoint;
            }

            @Override
            public String complete(List<Message> messages) {
                List<ChatMessage> request = new ArrayList<>(messages.size());
                for (Message message : messages) {
                    request.add(switch (message.role()) {
                        case SYSTEM -> ChatMessage.system(message.content());
                        case USER -> ChatMessage.user(message.content());
                        case ASSISTANT -> ChatMessage.assistant(message.content());
                    });
                }
                try {
                    return adapter.complete(new ChatRequest(request, options)).content();
                } catch (ChatCompletionException e) {
                    throw chatFailure(e);
                }
            }
        };
    }

    /**
     * Beschreibt ein Bild mit dem Vision-Modell ({@code POST /chat/completions} mit Bild, ADR 0021) über die
     * unveränderte {@code SourceImageAnalysisUseCase} des Enterprise AI Client: derselbe Prompt, dieselbe Anfrage (nur
     * das Modell gesetzt). Gespeichert wird hier nichts; das übernimmt die Asset-Ablage je SHA-256 und Modell.
     * Scheitert der Aufruf, meldet er {@link AiServiceException} ohne Bild, Antwort oder Key.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder Vision-Modell
     */
    public String describeImage(byte[] image, String mediaType) {
        return describeImage(image, mediaType, null);
    }

    /**
     * Ob das Vision-Modell geladen ist und sofort antwortet: bei Ollama laut {@code GET /api/ps}, wie AskAI „läuft“
     * zeigt; andere Dienste halten ihre Modelle selbst bereit.
     *
     * @throws AiServiceException wenn der Ollama-Server nicht erreichbar ist
     */
    public boolean visionLoaded() {
        if (settings.provider() != AiProvider.OLLAMA || !settings.vision()) {
            return true;
        }
        Set<String> loaded = new OllamaModelCatalog(ollama(timeoutMillis())).loaded();
        String model = settings.visionModel();
        return loaded.contains(model) || !model.contains(":") && loaded.contains(model + ":latest");
    }

    /**
     * Lädt das Vision-Modell eines Ollama-Servers und wartet darauf, höchstens die eingestellte Ladefrist
     * ({@code load-timeout}). Für andere Dienste nichts.
     *
     * @throws AiServiceException wenn der Server es nicht laden kann oder die Ladefrist abläuft
     */
    public void loadVision() {
        if (settings.provider() != AiProvider.OLLAMA || !settings.vision()) {
            return;
        }
        int millis = (int) Math.min(Integer.MAX_VALUE, settings.loadTimeout().toMillis());
        new OllamaModelCatalog(ollama(millis)).load(settings.visionModel(), settings.contextTokens());
    }

    /**
     * Wie {@link #describeImage(byte[], String)} mit eigenem Prompt eines Beschreibbaren (etwa einer Marke eines
     * Videos): Bild und Prompt gehen als eine Nachricht an das Vision-Modell. {@code null} ist der Prompt der
     * Bildbeschreibung des Enterprise AI Client.
     */
    public String describeImage(byte[] image, String mediaType, String instruction) {
        if (instruction != null) {
            if (!settings.vision()) {
                throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED,
                        "KI-Dienst oder Vision-Modell fehlt");
            }
            ChatCompletionPort adapter = chatPort(settings.visionModel(), timeoutMillis());
            try {
                return adapter.complete(new ChatRequest(List.of(ChatMessage.userWithImages(instruction,
                        List.of(new com.aresstack.enterpriseai.astu.chat.ChatImage(mediaType, image)))),
                        ChatOptions.builder().model(settings.visionModel()).build())).content();
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        }
        if (!settings.vision()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Vision-Modell fehlt");
        }
        ChatCompletionPort adapter = chatPort(settings.visionModel(), timeoutMillis());
        String model = settings.visionModel();
        SourceImageAnalysisUseCase useCase = new SourceImageAnalysisUseCase(List::of, adapter, () -> null,
                () -> model, NO_CACHE);
        try {
            ImageAnalysis analysis = useCase.describe("image", "", "", mediaType, image);
            return analysis == null ? null : analysis.description();
        } catch (ChatCompletionException e) {
            throw chatFailure(e);
        }
    }

    /** Kein Zwischenspeicher im Anwendungsfall: Die Asset-Ablage hält die Beschreibungen je Bildbytes und Modell. */
    private static final ImageAnalysisCache NO_CACHE = new ImageAnalysisCache() {
        @Override
        public ImageAnalysis get(String cacheKey) {
            return null;
        }

        @Override
        public void put(String cacheKey, ImageAnalysis analysis) {
            // nichts
        }
    };

    /** Versuche für {@code GET /models}, bei nicht erreichbarem Dienst; Abstand 0,5 s, 1 s, 2 s … */
    static final int CATALOG_ATTEMPTS = 4;
    static final long CATALOG_BACKOFF_MILLIS = 500;

    /**
     * Die Modelle des Dienstes ({@code GET /models}).
     *
     * @throws AiServiceException ohne Dienst oder wenn er nicht wie erwartet antwortet
     */
    public List<AiModel> models() {
        if (!settings.configured()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Kein KI-Dienst eingerichtet");
        }
        if (settings.provider() == AiProvider.OLLAMA) {
            // Ollama meldet die Fähigkeiten seiner Modelle nur über die eigene Schnittstelle, nicht unter /v1/models.
            return new OllamaModelCatalog(ollama(timeoutMillis())).models();
        }
        List<ModelDescriptor> descriptors = null;
        // Ein kurz nicht erreichbarer Dienst (Neustart, 5xx) wird mit wachsendem Abstand erneut gefragt; Ablehnung,
        // Frist und unlesbare Antworten nicht, sie ändern sich durch Warten nicht.
        for (int attempt = 0; descriptors == null; attempt++) {
            try {
                descriptors = new KipitzModelCatalogAdapter(catalogConfig()).models();
            } catch (ModelCatalogException e) {
                AiServiceException failure = catalogFailure(e, settings.apiKey() != null);
                if (failure.reason() != AiServiceException.Reason.UNREACHABLE || attempt + 1 >= CATALOG_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(CATALOG_BACKOFF_MILLIS << attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw failure;
                }
            }
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
        KipitzModelCatalogConfig.Builder config = KipitzModelCatalogConfig.builder(settings.apiBaseUrl())
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

    /**
     * Der Chat mit {@code model}: bei Ollama über dessen eigene Schnittstelle mit der Kontextlänge
     * ({@link OllamaChatAdapter}), sonst über den unveränderten {@code OpenAiCompatibleChatAdapter}.
     */
    private ChatCompletionPort chatPort(String model, int timeoutMillis) {
        if (settings.provider() == AiProvider.OLLAMA) {
            return new OllamaChatAdapter(ollama(timeoutMillis), model, settings.contextTokens());
        }
        return new OpenAiCompatibleChatAdapter(chatConfig(model, timeoutMillis));
    }

    /** Verbindungen zum Ollama-Server mit Route, TLS und Frist wie die übrigen Aufrufe. */
    private OllamaConnections ollama(int timeoutMillis) {
        return new OllamaConnections(settings.ollamaServer(), routes, tls, userAgent, timeoutMillis);
    }

    /** Verbindung zum Chat-Modell: Route, TLS, Token und Fristen wie {@link #catalogConfig()}. */

    private OpenAiCompatibleChatConfig chatConfig(String model, int timeoutMillis) {
        OpenAiCompatibleChatConfig.Builder config = OpenAiCompatibleChatConfig
                .builder(settings.apiBaseUrl(), model)
                .routes(routes)
                .userAgent(userAgent)
                .connectTimeoutMillis(timeoutMillis)
                .readTimeoutMillis(timeoutMillis);
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
                .builder(settings.apiBaseUrl().toString(), model, dimension)
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
            throw new EmbeddingUnavailableException("Embedding-Dienst: " + e.kind(), serviceFailure(e));
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
    /** Meldung einer am Ausgabelimit abgeschnittenen Antwort; der Text der Antwort steht nie darin. */
    static final String TRUNCATED = "Chat-Dienst: Antwort am Ausgabelimit abgeschnitten (finish_reason=length, "
            + "max_tokens=";
    static final String TRUNCATED_ADVICE = "; Ausgabelimit unter Einstellungen › KI erhöhen ("
            + AiServiceSettings.PREFIX + AiServiceSettings.CHAT_MAX_TOKENS + ")";

    /**
     * Der Text einer vollständigen Antwort. Hat der Dienst sie am Ausgabelimit beendet, ist sie unvollständig und
     * kein Ergebnis: ein abgeschnittenes JSON sähe sonst wie eine fertige Antwort aus.
     *
     * @param configurable ob das Limit die Einstellung {@code ai.chat-max-tokens} ist (dann nennt die Meldung sie)
     * @throws AiServiceException {@code INVALID_RESPONSE} bei {@code finish_reason = length}
     */
    static String complete(ChatResponse response, int maxTokens, boolean configurable) {
        if (response.finishReason() == ChatFinishReason.LENGTH) {
            throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, TRUNCATED + maxTokens + ")"
                    + (configurable ? TRUNCATED_ADVICE : ""));
        }
        return response.content();
    }

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

    /**
     * @param withKey ob ein API-Key mitging; ohne ihn ist jede Abweisung (4xx) oder eine unlesbare Antwort (etwa die
     *                Anmeldeseite eines Gateways) eine fehlende Anmeldung, keine unerwartete Antwort
     */
    static AiServiceException catalogFailure(ModelCatalogException e, boolean withKey) {
        String message = String.valueOf(e.getMessage());
        AiServiceException.Reason reason;
        if (message.contains("HTTP 401") || message.contains("HTTP 403")
                || !withKey && (message.matches(".*HTTP 4\\d\\d.*") || message.contains("nicht lesbar"))) {
            reason = AiServiceException.Reason.UNAUTHORIZED;
        } else if (message.matches(".*HTTP 5\\d\\d.*")) {
            reason = AiServiceException.Reason.UNREACHABLE;
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
