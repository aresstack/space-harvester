package com.aresstack.spaceharvester.source.ai;

import com.aresstack.enterpriseai.astu.acropolis.image.ImageAnalysis;
import com.aresstack.enterpriseai.astu.acropolis.image.ImageAnalysisCache;
import com.aresstack.enterpriseai.astu.acropolis.image.SourceImageAnalysisUseCase;
import com.aresstack.enterpriseai.astu.acropolis.navigation.ChatSectionLabeler;
import com.aresstack.enterpriseai.astu.chat.ChatFinishReason;
import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatToolCall;
import com.aresstack.enterpriseai.astu.chat.ChatToolDefinition;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.astu.chat.ChatUsage;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelCategory;
import com.aresstack.enterpriseai.astu.modelcatalog.ModelDescriptor;
import com.aresstack.enterpriseai.chat.api.ChatCompletionException;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatErrorKind;
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
import com.aresstack.enterpriseai.model.kipitz.KipitzSpeechAdapter;
import com.aresstack.enterpriseai.speech.api.SpeechSynthesisPort;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiChat;
import com.aresstack.spaceharvester.ai.AiChatException;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.jobs.Foreground;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
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
        AiChat direct = streaming(chatPort(settings.chatModel(), timeoutMillis()));
        if (settings.provider() != AiProvider.OLLAMA) {
            return direct;
        }
        // Ollama lädt ein Modell oft länger, als die Lesefrist einer Anfrage erlaubt: erst warten, bis es läuft
        // ({@link #awaitModel}), dann fragen. Das Warten läuft abseits des Aufrufers. Die erste Anfrage nach einem
        // Laden darf so lange dauern wie das Laden: Das erste Token nach einem frischen Laden kam einmal erst nach
        // mehr als 30 s.
        AiChat afterLoad = streaming(chatPort(settings.chatModel(),
                (int) Math.min(Integer.MAX_VALUE, settings.loadTimeout().toMillis())));
        return (request, listener) -> {
            java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicBoolean loaded = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.atomic.AtomicReference<AiChat.Task> running =
                    new java.util.concurrent.atomic.AtomicReference<>();
            MODEL_LOADERS.execute(() -> {
                try {
                    awaitModel(request.model(), () -> {
                        loaded.set(true);
                        listener.loading();
                    }, cancelled::get);
                } catch (AiServiceException failed) {
                    listener.failed(new AiChatException(AiChatException.Kind.TRANSPORT, 0, failed.getMessage(), failed));
                    return;
                }
                if (cancelled.get()) {
                    listener.cancelled();
                    return;
                }
                running.set((loaded.get() ? afterLoad : direct).stream(request, listener));
                if (cancelled.get()) {
                    running.get().cancel();
                }
            });
            return () -> {
                cancelled.set(true);
                AiChat.Task task = running.get();
                if (task != null) {
                    task.cancel();
                }
            };
        };
    }

    /**
     * Eine Anfrage, gestreamt ({@code "stream": true}) und bis zum Ende abgewartet. Alle Modellaufrufe streamen: Bei
     * einer langen Antwort ohne Streaming fließen bis zum Schluss keine Bytes, und ein Proxy oder Gateway bricht die
     * Verbindung ab (HTTP 504, Angelo 22.11.2026). Die Antwort trägt den vollen Text und den {@code finish_reason}.
     */
    static ChatResponse streamed(ChatCompletionPort adapter, ChatRequest request) {
        ChatRequest current = request;
        while (true) {
            try {
                return streamedOnce(adapter, current);
            } catch (ChatCompletionException e) {
                ChatOptions options = current.options();
                String effort = options == null ? null : options.reasoningEffort();
                // Lehnt der Dienst das eingestellte Nachdenken technisch ab (HTTP 400), weicht die Anfrage aus:
                // „none“ zum kleinsten Nachdenken, sonst ohne Angabe (Angelo 22.11.2026).
                if (!rejection(e) || effort == null) {
                    throw e;
                }
                current = new ChatRequest(current.messages(),
                        options.toBuilder().reasoningEffort(fallbackEffort(effort)).build(), current.tools());
            }
        }
    }

    private static ChatResponse streamedOnce(ChatCompletionPort adapter, ChatRequest request) {
        CompletableFuture<ChatResponse> done = new CompletableFuture<>();
        ChatTask task = adapter.stream(request, new ChatStreamListener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onDelta(String text) {
            }

            @Override
            public void onComplete(ChatResponse response) {
                done.complete(response);
            }

            @Override
            public void onError(ChatCompletionException error) {
                done.completeExceptionally(error);
            }

            @Override
            public void onCancelled() {
                done.completeExceptionally(new ChatCompletionException(ChatErrorKind.TRANSPORT, "stream cancelled"));
            }
        });
        try {
            return done.get();
        } catch (InterruptedException e) {
            task.cancel();
            Thread.currentThread().interrupt();
            throw new ChatCompletionException(ChatErrorKind.TRANSPORT, "interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ChatCompletionException failure) {
                throw failure;
            }
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    /** Der Chat über {@code adapter}: Nachrichten, Optionen und Rückmeldungen wie im Enterprise AI Client. */
    private static AiChat streaming(ChatCompletionPort adapter) {
        return (request, listener) -> {
            List<ChatMessage> messages = new ArrayList<>();
            for (AiChat.Message message : request.messages()) {
                messages.add(switch (message.role()) {
                    case SYSTEM -> ChatMessage.system(message.text());
                    case USER -> ChatMessage.user(message.text());
                    case ASSISTANT -> message.toolCalls().isEmpty() ? ChatMessage.assistant(message.text())
                            : ChatMessage.assistantToolCalls(message.text(), message.toolCalls().stream()
                                    .map(call -> new ChatToolCall(call.id(), call.name(), call.argumentsJson()))
                                    .toList());
                    case TOOL -> ChatMessage.tool(message.toolCallId(), message.text());
                });
            }
            ChatOptions.Builder options = ChatOptions.builder().model(request.model());
            if (request.reasoningEffort() != null) {
                options.reasoningEffort(request.reasoningEffort());
            }
            if (request.temperature() != null) {
                options.temperature(request.temperature());
            }
            if (request.maxTokens() != null) {
                options.maxTokens(request.maxTokens());
            }
            List<ChatToolDefinition> tools = request.tools().stream()
                    .map(tool -> new ChatToolDefinition(tool.name(), tool.description(), tool.parametersSchemaJson()))
                    .toList();
            StringBuilder received = new StringBuilder();
            ChatTask task = adapter.stream(new ChatRequest(messages, options.build(), tools), new ChatStreamListener() {
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
                    if (response != null && response.message() != null && response.message().hasToolCalls()) {
                        listener.toolCalls(full, response.message().toolCalls().stream()
                                .map(call -> new AiChat.ToolCall(call.id(), call.name(), call.argumentsJson()))
                                .toList());
                        return;
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
        // Die Antwort ist eine kurze JSON-Liste; das Ausgabelimit ist die eingestellte Tokenlänge des Text-Modells.
        int maxTokens = settings.chatMaxTokens();
        ChatOptions options = ChatOptions.builder().temperature(0.0).maxTokens(maxTokens).build();
        return (instructions, question) -> {
            try {
                return complete(streamed(adapter, new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(question)), options)),
                        maxTokens, true);
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        };
    }

    /**
     * Das Chat-Modell als Ausschreiber von Akronymen ({@code POST /chat/completions}, #122) über den unveränderten
     * {@code OpenAiCompatibleChatAdapter}, mit eigener Frist ({@code ai.expansion-timeout}, {@link AiServiceSettings.Timeouts#expansionTimeout()}). An den Dienst gehen nur die
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
        int maxTokens = settings.chatMaxTokens();
        // Nachdenken der Analyseschritte (ai.step-thinking, Standard aus): genau der eingestellte Wert geht mit, bei
        // Ollama als think, sonst als reasoning_effort; lehnt der Dienst ihn ab, weicht streamed() aus.
        String thinking = settings.stepThinking();
        ChatOptions.Builder builder = ChatOptions.builder().temperature(0.0).maxTokens(maxTokens);
        builder.reasoningEffort(effort(thinking));
        ChatOptions options = builder.build();
        return (instructions, input) -> {
            ChatCompletionPort adapter = chatPort(settings.chatModel(),
                    awaitedTimeout(settings.chatModel(), (int) settings.timeouts().expansionTimeout().toMillis()));
            try {
                return complete(streamed(adapter, new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(input)), options)), maxTokens, true);
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        };
    }

    /** Nur für Echttests kleiner: so lässt sich das Abschneiden am Ausgabelimit gezielt hervorrufen. */
    static volatile int summaryMaxTokensForTest;

    /**
     * Wie {@link #termCompletions()} für die Update-Zusammenfassung (ADR 0030, update-summary-v2): Tokenlänge und
     * Nachdenken kommen aus den Einstellungen, mit denen dieser Zugang gebaut ist; der Aufrufer reicht
     * {@link AiServiceSettings#summary()} (Standard 32768 Tokens, Nachdenken aus; Angelo 22.11.2026: mit Nachdenken lief
     * die Antwort bei 32768 Tokens ins Limit). Frist mindestens {@code ai.summary-timeout} (Vorgabe 5 Minuten, oder {@code ai.timeout},
     * wenn länger) statt der 90 Sekunden der Akronymauflösung.
     */
    public BinaryOperator<String> summaryCompletions() {
        if (!settings.chat()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Chat-Modell fehlt");
        }
        int maxTokens = summaryMaxTokensForTest > 0 ? summaryMaxTokensForTest : settings.chatMaxTokens();
        String thinking = settings.stepThinking();
        ChatOptions.Builder builder = ChatOptions.builder().temperature(0.0).maxTokens(maxTokens);
        builder.reasoningEffort(effort(thinking));
        ChatOptions options = builder.build();
        int timeout = (int) Math.min(Integer.MAX_VALUE, Math.max(settings.timeouts().summaryTimeout().toMillis(), timeoutMillis()));
        return (instructions, input) -> {
            ChatCompletionPort adapter = chatPort(settings.chatModel(), awaitedTimeout(settings.chatModel(), timeout));
            try {
                return complete(streamed(adapter, new ChatRequest(
                        List.of(ChatMessage.system(instructions), ChatMessage.user(input)), options)), maxTokens, true);
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        };
    }

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
        ChatCompletionPort adapter = chatPort(settings.chatModel(), (int) settings.timeouts().labelTimeout().toMillis());
        AtomicBoolean failed = new AtomicBoolean();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                if (failed.get()) {
                    // Nach dem ersten Fehler kein weiteres Paket ans Netz: jedes wartete sonst bis zur Frist.
                    throw new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Chat-Modell nicht erreichbar");
                }
                try {
                    return streamed(adapter, request);
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
                    return streamed(adapter, new ChatRequest(request, options)).content();
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
     * Ob {@code model} geladen ist und sofort antwortet: bei Ollama laut {@code GET /api/ps}, wie AskAI „läuft“ zeigt;
     * andere Dienste halten ihre Modelle selbst bereit.
     *
     * @throws AiServiceException wenn der Ollama-Server nicht erreichbar ist
     */
    public boolean modelLoaded(String model) {
        if (settings.provider() != AiProvider.OLLAMA || model == null) {
            return true;
        }
        Set<String> loaded = new OllamaModelCatalog(ollama(timeoutMillis())).loaded();
        return loaded.contains(model) || !model.contains(":") && loaded.contains(model + ":latest");
    }

    /** Ob das Vision-Modell geladen ist ({@link #modelLoaded}). */
    public boolean visionLoaded() {
        return !settings.vision() || modelLoaded(settings.visionModel());
    }

    /**
     * Wartet, bis {@code model} bei einem Ollama-Server läuft, bevor ein Prompt hinausgeht, wie AskAI: Läuft es nicht,
     * meldet {@code onLoading} das einmal, das Laden wird einmal angestoßen ({@code POST /api/generate} läuft im
     * Hintergrund weiter, auch wenn mehrere Aufrufer zugleich warten), und {@code GET /api/ps} wird im eingestellten
     * Abstand ({@code load-poll-interval}) gefragt, bis es läuft, höchstens die Ladefrist ({@code load-timeout}).
     * Für andere Dienste nichts.
     *
     * @param cancelled bricht das Warten ab (dann kehrt die Methode zurück, ohne zu werfen)
     * @throws AiServiceException wenn das Laden scheitert oder die Ladefrist abläuft
     */
    public void awaitModel(String model, Runnable onLoading, java.util.function.BooleanSupplier cancelled) {
        if (settings.provider() != AiProvider.OLLAMA || model == null || modelLoaded(model)) {
            return;
        }
        onLoading.run();
        String key = settings.ollamaServer() + "\n" + model;
        java.util.concurrent.CompletableFuture<Void> request = MODEL_LOADS.compute(key, (ignored, running) ->
                running != null && !running.isDone() ? running
                        : java.util.concurrent.CompletableFuture.runAsync(() -> loadModel(model), MODEL_LOADERS));
        long deadline = System.nanoTime() + settings.loadTimeout().toNanos();
        while (!cancelled.getAsBoolean() && !modelLoaded(model)) {
            if (request.isDone()) {
                try {
                    request.join();
                } catch (java.util.concurrent.CompletionException failed) {
                    throw failed.getCause() instanceof AiServiceException cause ? cause
                            : new AiServiceException(AiServiceException.Reason.UNREACHABLE, "Modell nicht geladen");
                }
                // Geladen, auch wenn der Server es unter anderem Namen führt.
                return;
            }
            if (System.nanoTime() - deadline > 0) {
                throw new AiServiceException(AiServiceException.Reason.TIMEOUT,
                        "Modell nicht innerhalb der Ladefrist geladen");
            }
            try {
                Thread.sleep(settings.loadPollInterval().toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Der Anstoß zum Laden eines Modells eines Ollama-Servers: kehrt erst zurück, wenn der Server es geladen hat,
     * höchstens nach der Ladefrist ({@code load-timeout}).
     */
    private void loadModel(String model) {
        int millis = (int) Math.min(Integer.MAX_VALUE, settings.loadTimeout().toMillis());
        new OllamaModelCatalog(ollama(millis)).load(model, settings.contextTokens());
    }

    /** Laufende Anstöße zum Laden je Server und Modell: jedes Modell wird nur einmal zugleich angestoßen. */
    private static final java.util.concurrent.ConcurrentHashMap<String,
            java.util.concurrent.CompletableFuture<Void>> MODEL_LOADS =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Die Threads, auf denen die Anstöße laufen; sie warten nur auf den Server. */
    private static final java.util.concurrent.ExecutorService MODEL_LOADERS =
            java.util.concurrent.Executors.newCachedThreadPool(work -> {
                Thread thread = new Thread(work, "ai-model-load");
                thread.setDaemon(true);
                return thread;
            });

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
            ChatCompletionPort adapter = visionPort(awaitedTimeout(settings.visionModel(), timeoutMillis()));
            try {
                return visionAnswer(streamed(adapter, new ChatRequest(List.of(ChatMessage.userWithImages(instruction,
                        List.of(new com.aresstack.enterpriseai.astu.chat.ChatImage(mediaType, image)))),
                        visionOptions())), settings.contextTokens(), settings.visionMaxTokens());
            } catch (ChatCompletionException e) {
                throw chatFailure(e);
            }
        }
        if (!settings.vision()) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "KI-Dienst oder Vision-Modell fehlt");
        }
        ChatCompletionPort adapter = visionPort(awaitedTimeout(settings.visionModel(), timeoutMillis()));
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
     * Die Sprachausgabe des Dienstes ({@code POST /audio/speech}, OpenAI-kompatibel, WAV) über den unveränderten
     * {@code KipitzSpeechAdapter} des Enterprise AI Client, mit Route, TLS und Schlüssel wie die übrigen Aufrufe. Ollama
     * hat keine Sprachausgabe.
     *
     * @throws AiServiceException {@code NOT_CONFIGURED} ohne Dienst oder bei Ollama
     */
    public SpeechSynthesisPort speech() {
        if (!settings.configured() || settings.provider() == AiProvider.OLLAMA) {
            throw new AiServiceException(AiServiceException.Reason.NOT_CONFIGURED, "Keine Sprachausgabe eingerichtet");
        }
        return new KipitzSpeechAdapter(catalogConfig());
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
            models.add(new AiModel(descriptor.modelId(), descriptor.displayName(), categories,
                    descriptor.contextLength()));
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
     * Der Text einer Bild-Antwort. Hat das Modell am Limit aufgehört ({@code done_reason = length}), etwa weil
     * Nachdenken und Prompt die Kontextlänge füllten, ist das kein Ergebnis: Die Meldung nennt die Token, damit der
     * Auftrag den echten Grund zeigt statt „leer“.
     *
     * @throws AiServiceException {@code INVALID_RESPONSE} bei {@code done_reason = length}
     */
    static String visionAnswer(ChatResponse response, int contextTokens, int maxTokens) {
        if (response.finishReason() == ChatFinishReason.LENGTH) {
            ChatUsage usage = response.usage();
            String tokens = usage != null && usage.isReported()
                    ? usage.promptTokens() + " Token Prompt + " + usage.completionTokens() + " Token Antwort, " : "";
            throw new AiServiceException(AiServiceException.Reason.INVALID_RESPONSE, "Vision-Modell: Antwort am Limit "
                    + "abgeschnitten (" + tokens + "Tokenlänge " + maxTokens + ", Kontextlänge " + contextTokens + "; "
                    + AiServiceSettings.PREFIX + AiServiceSettings.VISION_MAX_TOKENS + " oder "
                    + AiServiceSettings.PREFIX + AiServiceSettings.CONTEXT_TOKENS + " erhöhen)");
        }
        return response.content();
    }

    /** Optionen eines eigenen Bild-Prompts: Nachdenken des Vision-Modells ({@code ai.vision-thinking}). */
    private ChatOptions visionOptions() {
        String thinking = settings.visionThinkingLevel();
        ChatOptions.Builder builder = ChatOptions.builder().model(settings.visionModel());
        builder.reasoningEffort(effort(thinking));
        return builder.build();
    }

    /**
     * Die Verbindung zum Vision-Modell für Bildbeschreibungen und Folienkommentare: Bei Ollama mit demselben Nachdenken
     * wie die Analyseschritte ({@code ai.step-thinking}, Standard aus, wie {@link #termCompletions()}). Ein denkendes
     * Modell (gemma4) schrieb sonst je Folie Tausende Zeichen Nachdenken, bis die Antwort ausblieb (Angelo 20.11.2026:
     * Folie 00:05:00 scheiterte in „Folien beschreiben“ und „Folienkommentare“).
     */
    private ChatCompletionPort visionPort(int timeoutMillis) {
        if (settings.provider() == AiProvider.OLLAMA) {
            return limited(new OllamaChatAdapter(ollama(timeoutMillis), settings.visionModel(),
                    settings.contextTokens(), settings.visionThinkingLevel()), settings.visionMaxTokens());
        }
        return limited(new OpenAiCompatibleChatAdapter(chatConfig(settings.visionModel(), timeoutMillis)),
                settings.visionMaxTokens(), settings.visionThinkingLevel());
    }

    /**
     * Der Chat mit {@code model}: bei Ollama über dessen eigene Schnittstelle mit der Kontextlänge
     * ({@link OllamaChatAdapter}), sonst über den unveränderten {@code OpenAiCompatibleChatAdapter}.
     */
    private ChatCompletionPort chatPort(String model, int timeoutMillis) {
        if (settings.provider() == AiProvider.OLLAMA) {
            return limited(new OllamaChatAdapter(ollama(timeoutMillis), model, settings.contextTokens(),
                    settings.stepThinking()), settings.chatMaxTokens());
        }
        return limited(new OpenAiCompatibleChatAdapter(chatConfig(model, timeoutMillis)), settings.chatMaxTokens(),
                settings.stepThinking());
    }

    /**
     * Die Verbindung {@code port}, die bei jeder Anfrage ohne eigenes Ausgabelimit {@code maxTokens} mitschickt
     * ({@code max_tokens}, bei Ollama {@code options.num_predict}; Angelo 21.11.2026: „Du musst da bei der Anfrage
     * eine höhere Tokenlänge mitliefern“). Ohne Limit setzt ein Dienst sein eigenes, oft zu kurzes, und lange
     * Antworten wie SVG-Schaubilder brechen ab. Ein Schritt mit eigenem Limit behält es.
     */
    static ChatCompletionPort limited(ChatCompletionPort port, int maxTokens) {
        return limited(port, maxTokens, null);
    }

    /**
     * Wie {@link #limited(ChatCompletionPort, int)}, dazu das Nachdenken (Angelo 22.11.2026: „überall das Nachdenken
     * einstellbar, sonst nimmt er irgendwas Standardmäßiges“): Eine Anfrage ohne eigenes {@code reasoningEffort}
     * bekommt {@code thinking}. Legt ein Aufruf das Nachdenken aus gutem Grund selbst fest, bleibt es. Für einen
     * OpenAI-kompatiblen Dienst; auch {@code none} geht mit (ohne Angabe dächte etwa gpt-oss mit seiner Vorgabe bis ans
     * Ausgabelimit). Lehnt der Dienst den Wert ab, weicht die Anfrage aus ({@link #fallbackEffort}); bei Ollama
     * schickt {@link OllamaChatAdapter} {@code think: false}.
     */
    static ChatCompletionPort limited(ChatCompletionPort port, int maxTokens, String thinking) {
        // Angelo 22.11.2026: genau der eingestellte Wert geht mit, auch „none“. Nur wenn der Dienst ihn technisch
        // ablehnt, weicht die Anfrage aus ({@link #fallbackEffort}).
        return new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                // Vorrang der offenen Ansicht (Angelo 24.11.2026): Anfragen gewöhnlicher Aufträge warten, solange
                // sie arbeitet.
                try (Foreground.Hold hold = Foreground.aiCall()) {
                    return completeLimited(request);
                }
            }

            private ChatResponse completeLimited(ChatRequest request) {
                String effort = openAiEffort(thinking);
                while (true) {
                    ChatRequest limited = withLimit(request, maxTokens, effort);
                    try {
                        return port.complete(limited);
                    } catch (ChatCompletionException e) {
                        if (effort == null || !rejectedEffort(e, request, limited)) {
                            throw e;
                        }
                        effort = fallbackEffort(effort);
                    }
                }
            }

            @Override
            public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
                // Wie complete: der Griff hält, bis der Strom endet, gleich wie.
                Foreground.Hold hold = Foreground.aiCall();
                ChatStreamListener releasing = new ChatStreamListener() {
                    @Override
                    public void onStart() {
                        listener.onStart();
                    }

                    @Override
                    public void onDelta(String text) {
                        listener.onDelta(text);
                    }

                    @Override
                    public void onComplete(ChatResponse response) {
                        hold.close();
                        listener.onComplete(response);
                    }

                    @Override
                    public void onError(ChatCompletionException error) {
                        hold.close();
                        listener.onError(error);
                    }

                    @Override
                    public void onCancelled() {
                        hold.close();
                        listener.onCancelled();
                    }
                };
                Handoff handoff = new Handoff();
                try {
                    start(request, releasing, openAiEffort(thinking), handoff);
                } catch (RuntimeException e) {
                    hold.close();
                    throw e;
                }
                return handoff;
            }

            /**
             * Startet einen Versuch. Der Dienst darf schon während {@code port.stream} zurückrufen; ein Ausweichversuch
             * aus {@code onError} hat dann die höhere Nummer, und der ältere Griff überschreibt ihn nicht. Ein Abbruch
             * zwischen zwei Versuchen gilt auch für den nächsten.
             */
            private void start(ChatRequest request, ChatStreamListener listener, String effort, Handoff handoff) {
                int attempt = handoff.next();
                if (handoff.cancelled()) {
                    handoff.finish();
                    listener.onCancelled();
                    return;
                }
                ChatRequest limited = withLimit(request, maxTokens, effort);
                java.util.concurrent.atomic.AtomicBoolean received = new java.util.concurrent.atomic.AtomicBoolean();
                ChatTask task = port.stream(limited, new ChatStreamListener() {
                    @Override
                    public void onStart() {
                        listener.onStart();
                    }

                    @Override
                    public void onDelta(String text) {
                        received.set(true);
                        listener.onDelta(text);
                    }

                    @Override
                    public void onComplete(ChatResponse response) {
                        handoff.finish();
                        listener.onComplete(response);
                    }

                    @Override
                    public void onError(ChatCompletionException error) {
                        if (effort != null && !received.get() && rejectedEffort(error, request, limited)) {
                            start(request, listener, fallbackEffort(effort), handoff);
                            return;
                        }
                        handoff.finish();
                        listener.onError(error);
                    }

                    @Override
                    public void onCancelled() {
                        handoff.finish();
                        listener.onCancelled();
                    }
                });
                handoff.install(attempt, task);
            }
        };
    }

    /** Der Griff einer Anfrage über mehrere Versuche: immer der jüngste, Abbruch auch für spätere. */
    private static final class Handoff implements ChatTask {
        private int attempts;
        private int installed = -1;
        private ChatTask current;
        private boolean cancelled;
        private boolean finished;

        synchronized int next() {
            return ++attempts;
        }

        synchronized boolean cancelled() {
            return cancelled;
        }

        synchronized void finish() {
            finished = true;
        }

        void install(int attempt, ChatTask task) {
            boolean cancel;
            synchronized (this) {
                if (attempt < installed) {
                    return;
                }
                installed = attempt;
                current = task;
                cancel = cancelled;
            }
            if (cancel) {
                task.cancel();
            }
        }

        @Override
        public void cancel() {
            ChatTask task;
            synchronized (this) {
                cancelled = true;
                task = current;
            }
            if (task != null) {
                task.cancel();
            }
        }

        @Override
        public synchronized boolean isDone() {
            return finished;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }
    }

    /**
     * Ausweichwert, wenn der Dienst den gesendeten {@code reasoning_effort} technisch ablehnt: ohne Angabe.
     */
    static String fallbackEffort(String effort) {
        return null;
    }

    /**
     * Das Nachdenken für einen OpenAI-kompatiblen Dienst, wie im Enterprise AI Client, in 3d4j und askai: dort gibt es
     * nur {@code low}, {@code medium} und {@code high}; „aus“ schickt keinen {@code reasoning_effort} (Angelo
     * 22.11.2026: „none“ ist dort kein zulässiger Wert). Bei Ollama geht „aus“ als {@code think: false}.
     */
    static String openAiEffort(String thinking) {
        return thinking == null || "none".equals(thinking) ? null : thinking;
    }

    /** Das Nachdenken für den gewählten Anbieter ({@link #openAiEffort} bzw. bei Ollama unverändert). */
    private String effort(String thinking) {
        return settings.provider() == AiProvider.OLLAMA ? thinking : openAiEffort(thinking);
    }


    /**
     * Ob der Dienst die Anfrage abgelehnt hat: HTTP 4xx oder eine Fehlermeldung im Ereignisstrom (HTTP 200 mit
     * {@code error}, so melden etwa Gateways einen nicht unterstützten Wert wie {@code reasoning_effort: none}). Ein
     * echter Ausfall (HTTP 5xx, Verbindung) ist keine Ablehnung.
     */
    static boolean rejection(ChatCompletionException e) {
        return e.kind() == ChatErrorKind.INVALID_REQUEST
                || e.kind() == ChatErrorKind.PROVIDER_ERROR && e.statusCode() < 500;
    }

    /** Ob der Dienst die Anfrage nur wegen des hier ergänzten {@code reasoning_effort} abgelehnt hat. */
    private static boolean rejectedEffort(ChatCompletionException e, ChatRequest original, ChatRequest sent) {
        boolean added = sent.options() != null && sent.options().reasoningEffort() != null
                && (original.options() == null || original.options().reasoningEffort() == null);
        return added && rejection(e);
    }

    private static ChatRequest withLimit(ChatRequest request, int maxTokens, String effort) {
        ChatOptions options = request.options() == null ? ChatOptions.defaults() : request.options();
        boolean limit = options.maxTokens() == null;
        boolean think = effort != null && options.reasoningEffort() == null;
        if (!limit && !think) {
            return request;
        }
        ChatOptions.Builder builder = options.toBuilder();
        if (limit) {
            builder.maxTokens(maxTokens);
        }
        if (think) {
            builder.reasoningEffort(effort);
        }
        return new ChatRequest(request.messages(), builder.build(), request.tools());
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

    /**
     * Die Lesefrist einer Anfrage an {@code model}, die nicht gestreamt wird (Bildbeschreibung, Textschritte): Bei
     * Ollama wartet sie erst, bis das Modell läuft ({@link #awaitModel}), und darf dann so lange dauern wie das Laden
     * ({@code load-timeout}): Ein lokaler Server bearbeitet Anfragen nacheinander, und das erste Token nach einem
     * frischen Laden kam schon nach mehr als 30 s (Angelo 20.11.2026: „Folien beschreiben“ scheiterte an der ersten
     * Folie). Andere Dienste: {@code normalMillis}.
     *
     * @throws AiServiceException {@code TIMEOUT}, wenn die Ladefrist abläuft
     */
    private int awaitedTimeout(String model, int normalMillis) {
        if (settings.provider() != AiProvider.OLLAMA || model == null) {
            return normalMillis;
        }
        try {
            awaitModel(model, () -> { }, () -> false);
        } catch (AiServiceException e) {
            if (e.reason() == AiServiceException.Reason.TIMEOUT) {
                throw e;
            }
            // Ohne Auskunft über laufende Modelle (etwa ein Vermittler ohne /api/ps) fragt die Anfrage selbst.
        }
        return (int) Math.max(normalMillis, Math.min(Integer.MAX_VALUE, settings.loadTimeout().toMillis()));
    }

    private int timeoutMillis() {
        return (int) Math.min(Integer.MAX_VALUE, settings.timeout().toMillis());
    }

    private static List<float[]> vectors(java.util.function.Supplier<EmbeddingBatch> call) {
        EmbeddingBatch batch;
        // Vorrang der offenen Ansicht: Embeddings gewöhnlicher Aufträge warten, solange sie arbeitet.
        try (Foreground.Hold hold = Foreground.aiCall()) {
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
        // Die Ursache bleibt in der Kette (Angelo 22.11.2026: „Was soll Unavailable bedeuten?“): Die Diagnose nennt so
        // die Netz-Ausnahme (etwa ConnectException) oder den HTTP-Status aus der Meldung des Adapters. Ihre Texte
        // erscheinen nur als Befunde, nie wörtlich (FailureDiagnosis).
        return new AiServiceException(reason, "Embedding-Dienst: " + e.kind(), e);
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
            throw new com.aresstack.spaceharvester.ai.AiOutputTruncatedException(TRUNCATED + maxTokens + ")"
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
                + (e.statusCode() > 0 ? " (HTTP " + e.statusCode() + ")"
                : e.kind() == ChatErrorKind.PROVIDER_ERROR ? " (Fehlermeldung im Antwortstrom)" : ""));
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
