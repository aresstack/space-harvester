package com.aresstack.spaceharvester.source.ai;

import com.aresstack.spaceharvester.ai.AiChat;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.astu.chat.ChatMessage;
import com.aresstack.enterpriseai.astu.chat.ChatOptions;
import com.aresstack.enterpriseai.astu.chat.ChatRequest;
import com.aresstack.enterpriseai.astu.chat.ChatResponse;
import com.aresstack.enterpriseai.chat.api.ChatCompletionPort;
import com.aresstack.enterpriseai.chat.api.ChatStreamListener;
import com.aresstack.enterpriseai.chat.api.ChatTask;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiProvider;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
import com.aresstack.spaceharvester.application.passages.NavigationLabelModel;
import com.aresstack.spaceharvester.application.ranking.ModelReranker;
import com.aresstack.spaceharvester.source.TrustBundles;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * {@code /models}, {@code /embeddings}, {@code /rerank} und {@code /chat/completions} gegen einen lokalen
 * OpenAI-kompatiblen Dienst.
 */
class RemoteAiTest {

    private static final String KEY = "sk-test-geheim-0815";

    private HttpServer server;
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile boolean rejectNone;
    private volatile String chatAnswer = """
            {"choices":[{"index":0,"message":{"role":"assistant",
              "content":"{\\"queries\\":[{\\"text\\":\\"Nachtlauf Abbruch\\",\\"priority\\":1}]}"}}]}""";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/models", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, """
                    {"object":"list","data":[
                      {"id":"text-embedding-3-small","name":"Embedding klein","capabilities":["embeddings"],
                       "architecture":{"input_modalities":["text"],"output_modalities":["embeddings"]}},
                      {"id":"gpt-x","capabilities":["chat"],"architecture":{"input_modalities":["text"],"output_modalities":["text"]}}
                    ]}""");
        });
        server.createContext("/v1/embeddings", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.6,0.8,0.0]}]}""");
        });
        server.createContext("/v1/rerank", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"results":[{"index":1,"relevance_score":0.9},{"index":0,"relevance_score":0.2}]}""");
        });
        server.createContext("/v1/chat/completions", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(request);
            if (rejectNone && request.contains("reasoning_effort")) {
                // Wie ein Gateway: HTTP 200, die Ablehnung als Fehler im Ereignisstrom.
                byte[] bytes = "data: {\"error\":{\"message\":\"unsupported reasoning_effort\"}}\n\n"
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            if (status == 200 && ChatSse.wanted(request)) {
                byte[] bytes = ChatSse.of(chatAnswer).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
                return;
            }
            respond(exchange, chatAnswer);
        });
        // Ollama: Modelle und Fähigkeiten nur über die eigene Schnittstelle, Chat und Embeddings unter /v1.
        server.createContext("/api/tags", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            respond(exchange, """
                    {"models":[{"name":"gemma4:12b","model":"gemma4:12b"},{"name":"bge-m3:latest","model":"bge-m3:latest"},
                      {"name":"alt:1b","model":"alt:1b"}]}""");
        });
        server.createContext("/api/show", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            respond(exchange, body.contains("gemma4") ? """
                    {"capabilities":["completion","vision","audio","tools","thinking"]}"""
                    : body.contains("bge-m3") ? """
                    {"capabilities":["embedding"]}""" : "{}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void anotherEndpointWithTheSameModelIsAnotherEmbeddingModel() {
        AiServiceSettings other = new AiServiceSettings(URI.create("https://anderer-dienst.example/v1"), KEY, null,
                "text-embedding-3-small", 3, null, null, Set.of("wiki"));

        String here = remote(settings("text-embedding-3-small", 3)).embeddings().modelId();
        String there = remote(other).embeddings().modelId();

        // Vektoren eines anderen Dienstes gelten nicht als vergleichbar; der Schlüssel geht nie in die Kennung ein.
        assertThat(there).isNotEqualTo(here).startsWith("text-embedding-3-small@").doesNotContain(KEY);
        assertThat(here).doesNotContain(KEY);
    }

    @Test
    void embedsOverTheServiceWithBearerAndModel() {
        EmbeddingProvider provider = remote(settings("text-embedding-3-small", 3)).embeddings();

        List<float[]> vectors = provider.embed(List.of("Betrieb der Plattform"));

        assertThat(provider.modelId())
                .isEqualTo("text-embedding-3-small@" + ChatNavigationLabeler.fingerprint(base().toString()));
        assertThat(provider.dimensions()).isEqualTo(3);
        assertThat(vectors).singleElement().satisfies(vector -> assertThat(vector).hasSize(3));
        assertThat(authorizations).containsOnly("Bearer " + KEY);
        assertThat(bodies).singleElement().asString().contains("\"model\":\"text-embedding-3-small\"")
                .contains("Betrieb der Plattform");
    }

    @Test
    void listsModelsWithTheirCategoriesAndMeasuresTheDimension() {
        RemoteAi ai = remote(settings(null, null));

        assertThat(ai.models()).extracting(AiModel::id, AiModel::categories).containsExactly(
                org.assertj.core.groups.Tuple.tuple("text-embedding-3-small", Set.of(AiModel.EMBEDDING)),
                org.assertj.core.groups.Tuple.tuple("gpt-x", Set.of(AiModel.CHAT)));
        assertThat(ai.measureDimension("text-embedding-3-small")).isEqualTo(3);
    }

    @Test
    void failuresNameTheReasonButNeverTheKeyOrTheText() {
        status = 401;
        RemoteAi ai = remote(settings("text-embedding-3-small", 3));

        assertThatThrownBy(ai::models).isInstanceOfSatisfying(AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNAUTHORIZED))
                .hasMessageNotContaining(KEY);
        assertThatThrownBy(() -> ai.embeddings().embed(List.of("vertraulicher Text")))
                .isInstanceOf(EmbeddingUnavailableException.class)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("vertraulich")
                .cause().isInstanceOfSatisfying(AiServiceException.class,
                        e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNAUTHORIZED));
    }

    @Test
    void reranksOverTheServiceWithBearerModelAndDocuments() {
        double[] scores = remote(settings(null, null).withRerankModel("bge-reranker")).reranker()
                .score("Nachtbetrieb", List.of("Urlaub", "Betrieb in der Nacht"));

        assertThat(scores).containsExactly(0.2, 0.9);
        assertThat(authorizations).containsOnly("Bearer " + KEY);
        assertThat(bodies).singleElement().asString().contains("\"model\":\"bge-reranker\"")
                .contains("Nachtbetrieb").contains("Betrieb in der Nacht");
    }

    @Test
    void aFailingRerankerNamesTheReasonButNeverTheKeyOrTheText() {
        status = 500;
        ModelReranker.Scorer scorer = remote(settings(null, null).withRerankModel("bge-reranker")).reranker();

        assertThatThrownBy(() -> scorer.score("vertrauliche Frage", List.of("vertraulicher Text", "zweiter")))
                .isInstanceOf(AiServiceException.class)
                .hasMessageNotContaining(KEY).hasMessageNotContaining("vertraulich");
        assertThatThrownBy(() -> remote(settings(null, null)).reranker()).isInstanceOfSatisfying(
                AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.NOT_CONFIGURED));
    }

    @Test
    void aFailingProxyRouteIsNeverBypassed() {
        RemoteAi ai = new RemoteAi(settings("text-embedding-3-small", 3),
                target -> HttpRoute.unavailable("pac-failed", "PAC nicht ladbar"), TrustBundles.NONE, "test");

        assertThatThrownBy(() -> ai.embeddings().embed(List.of("Text")))
                .isInstanceOf(EmbeddingUnavailableException.class);
        assertThat(bodies).isEmpty();
    }

    @Test
    void asksTheChatModelWithOnlyTheQuestionAndReadsTheAnswer() {
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        String answer = remote(settings).queryVariants().complete("Nur Suchbegriffe.", "Warum bricht der Nachtlauf ab?");

        assertThat(answer).contains("Nachtlauf Abbruch");
        assertThat(authorizations).containsOnly("Bearer " + KEY);
        assertThat(bodies).singleElement().asString().contains("\"model\":\"gpt-x\"")
                .contains("Warum bricht der Nachtlauf ab?").contains("\"role\":\"system\"");
    }

    @Test
    void aFailingChatModelNamesTheStatusButNeverTheKeyOrTheQuestion() {
        status = 401;
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        assertThatThrownBy(() -> remote(settings).queryVariants().complete("Anweisung", "Nachtlauf geheim"))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNAUTHORIZED);
                    assertThat(e.getMessage()).doesNotContain(KEY).doesNotContain("Nachtlauf");
                });
    }

    @Test
    void asksTheChatModelForAcronymExpansionsAndFollowsTheReleasePerSource() {
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null,
                Set.of("wiki"));
        ChatAcronymExpansionModel model = new ChatAcronymExpansionModel(() -> settings, this::remote);

        String answer = model.complete("Schreibe Akronyme aus.", "{\"acronyms\":[]}");

        assertThat(answer).isNotBlank();
        assertThat(bodies).singleElement().asString().contains("\"model\":\"gpt-x\"")
                .contains("Schreibe Akronyme aus.");
        assertThat(model.available()).isTrue();
        assertThat(model.mayProcess("wiki")).isTrue();
        assertThat(model.mayProcess("intern")).isFalse();
        assertThat(model.version()).startsWith(ChatAcronymExpansionModel.ID + ":gpt-x@")
                .doesNotContain(base().getHost());
        assertThat(new ChatAcronymExpansionModel(() -> AiServiceSettings.NONE, this::remote).available()).isFalse();
    }

    /**
     * Angelo 22.11.2026: „aus“ schickt keinen reasoning_effort (wie EAC, 3d4j, askai); lehnt ein Dienst einen
     * eingestellten Wert im Ereignisstrom ab (HTTP 200 mit error), geht die Anfrage ohne ihn, statt als Ausfall
     * wiederholt zu werden.
     */
    @Test
    void offSendsNoReasoningEffortAndARejectionInsideTheStreamGoesWithout() {
        rejectNone = true;
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        remote(settings.withStepThinking("none")).summaryCompletions().apply("Anweisung", "{}");
        String answer = remote(settings.withStepThinking("high")).summaryCompletions().apply("Anweisung", "{}");

        assertThat(answer).isNotBlank();
        assertThat(bodies.get(0)).doesNotContain("reasoning_effort");
        assertThat(bodies.get(1)).contains("\"reasoning_effort\":\"high\"");
        assertThat(bodies.getLast()).doesNotContain("reasoning_effort");
    }

    @Test
    void sendsTheConfiguredOutputLimitAsMaxTokens() {
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        remote(settings).termCompletions().apply("Anweisung", "{}");
        remote(settings.withChatMaxTokens(16384)).termCompletions().apply("Anweisung", "{}");

        assertThat(bodies).hasSize(2);
        assertThat(bodies.get(0)).contains("\"max_tokens\":8192");
        assertThat(bodies.get(1)).contains("\"max_tokens\":16384");
    }

    @Test
    void anAnswerCutAtTheOutputLimitIsReportedAndNeverPassedOnAsComplete() {
        chatAnswer = """
                {"choices":[{"index":0,"finish_reason":"length","message":{"role":"assistant",
                  "content":"{\\"expansions\\":[{\\"acronym\\":\\"geheimes Akro"}}]}""";
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of())
                .withChatMaxTokens(256);

        assertThatThrownBy(() -> remote(settings).termCompletions().apply("Anweisung", "{}"))
                .isInstanceOfSatisfying(AiServiceException.class, e -> {
                    assertThat(e.reason()).isEqualTo(AiServiceException.Reason.INVALID_RESPONSE);
                    assertThat(e.getMessage()).contains("finish_reason=length", "max_tokens=256",
                            "space-harvester.ai.chat-max-tokens").doesNotContain("geheimes").doesNotContain(KEY);
                });
        assertThatThrownBy(() -> remote(settings).queryVariants().complete("Anweisung", "Frage"))
                .isInstanceOfSatisfying(AiServiceException.class, e -> assertThat(e.getMessage())
                        .contains("finish_reason=length", "max_tokens=256", "chat-max-tokens"));
    }

    @Test
    void anAnswerThatEndsRegularlyIsPassedOn() {
        chatAnswer = """
                {"choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"fertig"}}]}""";
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        assertThat(remote(settings).termCompletions().apply("Anweisung", "{}")).isEqualTo("fertig");
    }

    @Test
    void describesAnImageWithTheVisionModelAndThePromptOfTheEnterpriseAiClient() {
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of(),
                null, "qwen-vl");

        String answer = remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png");

        assertThat(answer).isNotBlank();
        assertThat(bodies).singleElement().asString().contains("\"model\":\"qwen-vl\"")
                .contains("Beschreibe dieses Bild sachlich auf Deutsch in höchstens fünf Sätzen")
                .contains("\"type\":\"image_url\"").contains("data:image/png;base64,AQID")
                .doesNotContain("gpt-x");
    }

    @Test
    void listsTheModelsOfAnOllamaServerWithTheirCapabilities() {
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, null, null, null, null, null, Set.of())
                .withProvider(AiProvider.OLLAMA);

        assertThat(remote(settings).models()).extracting(AiModel::id, AiModel::categories).containsExactly(
                org.assertj.core.groups.Tuple.tuple("gemma4:12b", Set.of(AiModel.CHAT, AiModel.VISION)),
                org.assertj.core.groups.Tuple.tuple("bge-m3:latest", Set.of(AiModel.EMBEDDING)),
                // Ältere Server melden keine Fähigkeiten: wie bei anderen Katalogen Chat und Embeddings.
                org.assertj.core.groups.Tuple.tuple("alt:1b", Set.of(AiModel.CHAT, AiModel.EMBEDDING)));
        assertThat(bodies).containsExactly("{\"model\":\"gemma4:12b\"}", "{\"model\":\"bge-m3:latest\"}",
                "{\"model\":\"alt:1b\"}");
        assertThat(authorizations).containsOnly("null");
    }

    @Test
    void chatsAndDescribesImagesOverTheOllamaInterfaceWithTheContextLengthAndEmbedsOverV1() {
        List<String> chats = new CopyOnWriteArrayList<>();
        server.createContext("/api/chat", exchange -> {
            authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"model":"gemma4:12b","message":{"role":"assistant","content":"fertig"},"done":true,\
                    "done_reason":"stop","prompt_eval_count":12,"eval_count":3}""");
        });
        // Mit Schrägstrich am Ende oder der Adresse der OpenAI-Schnittstelle eingetragen: dieselben Pfade.
        for (URI address : List.of(ollama(), URI.create(ollama() + "/"), URI.create(ollama() + "/v1"))) {
            AiServiceSettings settings = new AiServiceSettings(address, null, "gemma4:12b", "bge-m3:latest", 3, null,
                    null, Set.of(), null, "gemma4:12b").withProvider(AiProvider.OLLAMA);
            RemoteAi ai = remote(settings);

            assertThat(ai.termCompletions().apply("Anweisung", "{}")).isEqualTo("fertig");
            assertThat(ai.embeddings().embed(List.of("Betrieb"))).singleElement()
                    .satisfies(vector -> assertThat(vector).hasSize(3));
            assertThat(ai.describeImage(new byte[] {1, 2, 3}, "image/png")).isEqualTo("fertig");
            assertThat(settings.apiBaseUrl()).isEqualTo(URI.create(ollama() + "/v1"));
        }
        assertThat(authorizations).hasSize(9).containsOnly("null");
        assertThat(bodies).as("Embeddings über /v1").hasSize(3).allMatch(body -> body.contains("bge-m3:latest"));
        assertThat(chats).hasSize(6);
        assertThat(chats.get(0)).contains("\"model\":\"gemma4:12b\"", "\"stream\":true", "\"role\":\"system\"",
                "\"content\":\"Anweisung\"", "\"num_ctx\":8192", "\"num_predict\":8192", "\"temperature\":0.0");
        assertThat(chats.get(1)).contains("\"images\":[\"AQID\"]", "\"num_ctx\":8192")
                .doesNotContain("image_url");
    }

    @Test
    void anOllamaAnswerAtTheOutputLimitIsIncompleteAndTheContextLengthIsConfigurable() {
        List<String> chats = new CopyOnWriteArrayList<>();
        server.createContext("/api/chat", exchange -> {
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"{\\"abgeschn"},"done":true,"done_reason":"length"}""");
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "gemma4:12b", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withContextTokens(32768).withChatMaxTokens(100);

        assertThatThrownBy(() -> remote(settings).termCompletions().apply("Anweisung", "{}"))
                .isInstanceOfSatisfying(AiServiceException.class, e -> assertThat(e.reason())
                        .isEqualTo(AiServiceException.Reason.INVALID_RESPONSE))
                .hasMessageContaining("max_tokens=100");
        // Analyseschritte denken standardmäßig nicht nach (ai.step-thinking, Angelo 20.11.2026).
        assertThat(chats).singleElement().asString().contains("\"num_ctx\":32768", "\"num_predict\":100",
                "\"think\":false");
        assertThatThrownBy(() -> settings.withContextTokens(100)).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("context-tokens");
    }

    @Test
    void theThinkingOfAnalysisStepsIsConfigurable() {
        List<String> chats = new CopyOnWriteArrayList<>();
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"gemma4:12b","model":"gemma4:12b"}]}"""));
        server.createContext("/api/chat", exchange -> {
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"korrigiert"},"done":true,"done_reason":"stop"}""");
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "gemma4:12b", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withStepThinking("low");

        assertThat(remote(settings).termCompletions().apply("Anweisung", "Text")).isEqualTo("korrigiert");
        assertThat(chats).singleElement().asString().contains("\"think\":\"low\"");
        assertThatThrownBy(() -> settings.withStepThinking("ganz viel")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("step-thinking");
    }

    @Test
    void imagePromptsDoNotThinkEitherAndATruncatedAnswerSaysWhy() {
        // Folienkommentare und Folienbeschreibungen denken wie die Analyseschritte nicht nach (ai.step-thinking):
        // gemma4 füllte sonst mit 10.000+ Zeichen Nachdenken je Folie fast die ganze Kontextlänge (Angelo 20.11.2026).
        List<String> chats = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> reason = new java.util.concurrent.atomic.AtomicReference<>(
                "stop");
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"gemma4:12b","model":"gemma4:12b"}]}"""));
        server.createContext("/api/chat", exchange -> {
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"%s"},"done":true,"done_reason":"%s",\
                    "prompt_eval_count":3593,"eval_count":4599}""".formatted(
                    "stop".equals(reason.get()) ? "Vortragstext" : "", reason.get()));
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, null, null, null, null, null, Set.of())
                .withVisionModel("gemma4:12b").withProvider(AiProvider.OLLAMA);

        assertThat(remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png", "Schreibe den Vortragstext."))
                .isEqualTo("Vortragstext");
        assertThat(remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png")).isEqualTo("Vortragstext");
        assertThat(chats).hasSize(2).allSatisfy(body -> assertThat(body).contains("\"think\":false"));

        reason.set("length");
        assertThatThrownBy(() -> remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png", "Notizen"))
                .isInstanceOfSatisfying(AiServiceException.class, e -> assertThat(e.reason())
                        .isEqualTo(AiServiceException.Reason.INVALID_RESPONSE))
                .hasMessageContaining("3593 Token Prompt + 4599 Token Antwort")
                .hasMessageContaining("context-tokens");
    }

    @Test
    void everyRequestCarriesTheTokenLengthOfItsModel() {
        // Angelo 21.11.2026: Ohne mitgeschickte Tokenlänge brachen lange Antworten (SVG-Bilder) ab.
        List<String> chats = new CopyOnWriteArrayList<>();
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"text-model","model":"text-model"},{"name":"bild-model","model":"bild-model"}]}"""));
        server.createContext("/api/chat", exchange -> {
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"fertig"},"done":true,"done_reason":"stop"}""");
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "text-model", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withVisionModel("bild-model").withChatMaxTokens(4096)
                .withVisionMaxTokens(2048).withImageGeneration(null, 65536);

        remote(settings).statementChat().complete(List.of(
                new com.aresstack.spaceharvester.application.enrichment.statements.StatementChat.Message(
                        com.aresstack.spaceharvester.application.enrichment.statements.StatementChat.Role.USER,
                        "Satz")));
        remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png", "Beschreibe.");
        remote(settings.imageGeneration()).termCompletions().apply("Zeichne ein SVG", "{}");

        assertThat(chats).hasSize(3);
        assertThat(chats.get(0)).contains("\"num_predict\":4096");
        assertThat(chats.get(1)).contains("\"num_predict\":2048");
        assertThat(chats.get(2)).contains("\"num_predict\":65536", "\"model\":\"text-model\"");
    }

    @Test
    void everyRequestCarriesTheThinkingOfItsModelUnlessTheCallSetsItself() throws Exception {
        // Angelo 22.11.2026: Ohne mitgeschicktes Nachdenken gilt ein unbekannter Standard des Dienstes; legt ein
        // Aufruf das Nachdenken selbst fest, bleibt es.
        List<String> chats = new CopyOnWriteArrayList<>();
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"text-model","model":"text-model"},{"name":"bild-model","model":"bild-model"}]}"""));
        server.createContext("/api/chat", exchange -> {
            chats.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"fertig"},"done":true,"done_reason":"stop"}""");
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "text-model", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withVisionModel("bild-model").withThinking("high", "low");

        remote(settings).statementChat().complete(List.of(
                new com.aresstack.spaceharvester.application.enrichment.statements.StatementChat.Message(
                        com.aresstack.spaceharvester.application.enrichment.statements.StatementChat.Role.USER,
                        "Satz")));
        remote(settings).describeImage(new byte[] {1, 2, 3}, "image/png", "Beschreibe.");
        remote(settings.imageGeneration()).termCompletions().apply("Zeichne ein SVG", "{}");
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        remote(settings).chat().stream(new com.aresstack.spaceharvester.ai.AiChat.Request(
                List.of(com.aresstack.spaceharvester.ai.AiChat.Message.user("Hallo?")), "text-model", "medium"),
                new com.aresstack.spaceharvester.ai.AiChat.Listener() {
                    @Override
                    public void delta(String text) {
                    }

                    @Override
                    public void completed(String text) {
                        done.complete(text);
                    }

                    @Override
                    public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
                        done.completeExceptionally(error);
                    }

                    @Override
                    public void cancelled() {
                        done.cancel(false);
                    }
                });
        done.join();

        assertThat(chats).hasSize(4);
        assertThat(chats.get(0)).contains("\"think\":false");
        assertThat(chats.get(1)).contains("\"think\":\"high\"");
        assertThat(chats.get(2)).contains("\"think\":\"low\"");
        assertThat(chats.get(3)).contains("\"think\":\"medium\"");
    }

    @Test
    void anOpenAiCompatibleServiceGetsTheConfiguredReasoningEffortAndNothingWhenOff() {
        ChatRequest plain = new ChatRequest(List.of(ChatMessage.user("Hallo")), ChatOptions.defaults());
        List<ChatRequest> seen = new CopyOnWriteArrayList<>();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                seen.add(request);
                return null;
            }

            @Override
            public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
                throw new UnsupportedOperationException();
            }
        };

        RemoteAi.limited(port, 1024, "none").complete(plain);
        RemoteAi.limited(port, 1024, "high").complete(plain);
        RemoteAi.limited(port, 1024, "high").complete(new ChatRequest(List.of(ChatMessage.user("Hallo")),
                ChatOptions.builder().reasoningEffort("low").build()));

        // Angelo 22.11.2026: wie EAC, 3d4j und askai: „aus“ schickt keinen reasoning_effort („none“ ist unzulässig).
        assertThat(seen.get(0).options().reasoningEffort()).isNull();
        assertThat(seen.get(1).options().reasoningEffort()).isEqualTo("high");
        assertThat(seen.get(2).options().reasoningEffort()).isEqualTo("low");
        assertThat(seen).allSatisfy(request -> assertThat(request.options().maxTokens()).isEqualTo(1024));
    }

    /** Lehnt der Dienst das Nachdenken technisch ab, geht die Anfrage ohne Angabe. */
    @Test
    void aServiceThatRejectsTheReasoningEffortIsAskedOnceMoreWithoutIt() {
        ChatRequest plain = new ChatRequest(List.of(ChatMessage.user("Hallo")), ChatOptions.defaults());
        List<ChatRequest> seen = new CopyOnWriteArrayList<>();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                seen.add(request);
                if (request.options().reasoningEffort() != null) {
                    throw new com.aresstack.enterpriseai.chat.api.ChatCompletionException(
                            com.aresstack.enterpriseai.chat.api.ChatErrorKind.INVALID_REQUEST, "HTTP 400");
                }
                return null;
            }

            @Override
            public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
                throw new UnsupportedOperationException();
            }
        };

        RemoteAi.limited(port, 1024, "high").complete(plain);

        // Erst genau „high“, abgelehnt: ohne Angabe.
        assertThat(seen).hasSize(2);
        assertThat(seen.get(0).options().reasoningEffort()).isEqualTo("high");
        assertThat(seen.get(1).options().reasoningEffort()).isNull();
    }

    /** Lehnt der Dienst schon während stream() ab, bleibt der Ausweichversuch der Griff, den ein Abbruch trifft. */
    @Test
    void anEarlyRejectionKeepsTheRetryAsTheHandleThatIsCancelled() {
        List<ChatRequest> seen = new CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicBoolean retryCancelled = new java.util.concurrent.atomic.AtomicBoolean();
        ChatCompletionPort port = new ChatCompletionPort() {
            @Override
            public ChatResponse complete(ChatRequest request) {
                throw new UnsupportedOperationException();
            }

            @Override
            public ChatTask stream(ChatRequest request, ChatStreamListener listener) {
                seen.add(request);
                boolean first = seen.size() == 1;
                if (first) {
                    listener.onError(new com.aresstack.enterpriseai.chat.api.ChatCompletionException(
                            com.aresstack.enterpriseai.chat.api.ChatErrorKind.INVALID_REQUEST, "HTTP 400"));
                }
                return new ChatTask() {
                    @Override
                    public void cancel() {
                        if (!first) {
                            retryCancelled.set(true);
                        }
                    }

                    @Override
                    public boolean isDone() {
                        return first;
                    }

                    @Override
                    public boolean isCancelled() {
                        return false;
                    }
                };
            }
        };
        ChatStreamListener quiet = new ChatStreamListener() {
            @Override
            public void onStart() {
            }

            @Override
            public void onDelta(String text) {
            }

            @Override
            public void onComplete(ChatResponse response) {
            }

            @Override
            public void onError(com.aresstack.enterpriseai.chat.api.ChatCompletionException error) {
            }

            @Override
            public void onCancelled() {
            }
        };

        ChatTask task = RemoteAi.limited(port, 1024, "high").stream(
                new ChatRequest(List.of(ChatMessage.user("Hallo")), ChatOptions.defaults()), quiet);

        assertThat(seen).hasSize(2);
        assertThat(seen.get(1).options().reasoningEffort()).isNull();
        assertThat(task.isDone()).as("der Ausweichversuch läuft noch").isFalse();
        task.cancel();
        assertThat(retryCancelled).isTrue();
    }

    @Test
    void streamsTheOllamaChatLineByLine() throws Exception {
        // Ein Ollama-Server meldet die geladenen Modelle; hier läuft das Chat-Modell schon.
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"gemma4:12b","model":"gemma4:12b"}]}"""));
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, """
                    {"message":{"role":"assistant","content":"Hal"},"done":false}
                    {"message":{"role":"assistant","content":"lo"},"done":false}
                    {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                    """);
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "gemma4:12b", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA);
        List<String> deltas = new CopyOnWriteArrayList<>();
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();

        remote(settings).chat().stream(new com.aresstack.spaceharvester.ai.AiChat.Request(
                List.of(com.aresstack.spaceharvester.ai.AiChat.Message.user("Hallo?")), "gemma4:12b", null),
                new com.aresstack.spaceharvester.ai.AiChat.Listener() {
                    @Override
                    public void delta(String text) {
                        deltas.add(text);
                    }

                    @Override
                    public void completed(String text) {
                        done.complete(text);
                    }

                    @Override
                    public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
                        done.completeExceptionally(error);
                    }

                    @Override
                    public void cancelled() {
                        done.cancel(false);
                    }
                });

        assertThat(done.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("Hallo");
        assertThat(deltas).containsExactly("Hal", "lo");
    }

    /**
     * Angelo 28.11.2026: Ohne eigenes Modell in der Zeile „Quellcode“ gilt die Zeile des Text-Modells vollständig,
     * auch ihr Nachdenken – „low“ geht als low, „aus“ schickt kein reasoning_effort (nie „none“ an gpt-oss).
     */
    @Test
    void theSourceCodeRowWithoutOwnModelTakesModelAndThinkingOfTheChat() throws Exception {
        AiServiceSettings chat = new AiServiceSettings(base(), KEY, "gpt-oss", null, null, null, null, Set.of());

        askChat(chat.withStepThinking("low").sourceCode());
        askChat(chat.withStepThinking("none").sourceCode());
        askChat(chat.withStepThinking("none").withSourceCode(new AiServiceSettings.ModelUse("code-modell", null,
                "high")).sourceCode());
        // Angelos Einstellung: eigenes Modell, „denkt wenig“ – genau low geht mit, nie „none“
        askChat(chat.withStepThinking("none").withSourceCode(new AiServiceSettings.ModelUse("openai/gpt-oss-120b",
                8192, "low")).sourceCode());

        assertThat(bodies).hasSize(4);
        assertThat(bodies.get(3)).contains("\"model\":\"openai/gpt-oss-120b\"", "\"reasoning_effort\":\"low\"",
                "\"max_tokens\":8192").doesNotContain("\"none\"");
        assertThat(bodies.get(0)).contains("\"model\":\"gpt-oss\"", "\"reasoning_effort\":\"low\"",
                "\"max_tokens\":" + AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS);
        assertThat(bodies.get(1)).contains("\"model\":\"gpt-oss\"").doesNotContain("reasoning_effort");
        assertThat(bodies.get(2)).contains("\"model\":\"code-modell\"", "\"reasoning_effort\":\"high\"",
                "\"max_tokens\":" + AiServiceSettings.DEFAULT_SOURCE_CODE_MAX_TOKENS);
    }

    private void askChat(AiServiceSettings settings) throws Exception {
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();
        remote(settings).chat().stream(new AiChat.Request(List.of(AiChat.Message.user("Was tut der Block?")),
                settings.chatModel(), null), new AiChat.Listener() {
            @Override
            public void delta(String text) {
            }

            @Override
            public void completed(String text) {
                done.complete(text);
            }

            @Override
            public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
                done.completeExceptionally(error);
            }

            @Override
            public void cancelled() {
                done.cancel(false);
            }
        });
        done.get(10, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Test
    void theChatOffersToolsAndReportsTheCallsOfTheModel() throws Exception {
        // ADR 0031: Die Werkzeuge leben beim Aufrufer; hier gehen nur Definition, Aufruf und Ergebnis über die Leitung.
        // Der Testserver antwortet am Stück (application/json), wie Dienste, die stream=true übergehen.
        chatAnswer = """
                {"choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                  "tool_calls":[{"id":"call_7","type":"function","function":{"name":"seite_lesen","arguments":"{\\"id\\":\\"42\\"}"}}]}}]}""";
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());
        AiChat.Tool tool = new AiChat.Tool("seite_lesen", "Liest eine Seite des Bereichs.",
                "{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}},\"required\":[\"id\"]}");
        List<AiChat.Message> history = List.of(AiChat.Message.user("Was steht auf Seite 41?"),
                AiChat.Message.assistant("", List.of(new AiChat.ToolCall("call_6", "seite_lesen", "{\"id\":\"41\"}"))),
                AiChat.Message.tool("call_6", "Inhalt von 41"));

        java.util.concurrent.CompletableFuture<List<AiChat.ToolCall>> calls = new java.util.concurrent.CompletableFuture<>();
        remote(settings).chat().stream(new AiChat.Request(history, "gpt-x", null, List.of(tool)), new ToolListener(calls));

        assertThat(calls.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .containsExactly(new AiChat.ToolCall("call_7", "seite_lesen", "{\"id\":\"42\"}"));
        assertThat(bodies).singleElement().asString()
                .contains("\"tools\":[{\"type\":\"function\"", "\"name\":\"seite_lesen\"", "\"required\":[\"id\"]")
                .contains("\"tool_calls\":[", "\"id\":\"call_6\"")
                .contains("\"role\":\"tool\"", "\"tool_call_id\":\"call_6\"", "Inhalt von 41");
    }

    @Test
    void theOllamaChatOffersToolsAndNumbersTheCallsOfTheModel() throws Exception {
        server.createContext("/api/ps", exchange -> respond(exchange, """
                {"models":[{"name":"gemma4:12b","model":"gemma4:12b"}]}"""));
        server.createContext("/api/chat", exchange -> {
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"message":{"role":"assistant","content":"","tool_calls":[{"function":{"name":"im_bereich_suchen","arguments":{"text":"Nachtlauf"}}}]},"done":false}
                    {"message":{"role":"assistant","content":""},"done":true,"done_reason":"stop"}
                    """);
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "gemma4:12b", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA);
        AiChat.Tool tool = new AiChat.Tool("im_bereich_suchen", "Sucht im Bereich.", null);
        List<AiChat.Message> history = List.of(AiChat.Message.user("Warum bricht der Nachtlauf ab?"),
                AiChat.Message.assistant("", List.of(new AiChat.ToolCall("call_1", "im_bereich_suchen", "{\"text\":\"Abbruch\"}"))),
                AiChat.Message.tool("call_1", "Treffer"));

        java.util.concurrent.CompletableFuture<List<AiChat.ToolCall>> calls = new java.util.concurrent.CompletableFuture<>();
        remote(settings).chat().stream(new AiChat.Request(history, "gemma4:12b", null, List.of(tool)),
                new ToolListener(calls));

        assertThat(calls.get(10, java.util.concurrent.TimeUnit.SECONDS))
                .containsExactly(new AiChat.ToolCall("call_1", "im_bereich_suchen", "{\"text\":\"Nachtlauf\"}"));
        assertThat(bodies).filteredOn(body -> body.contains("\"messages\"")).singleElement().asString()
                .contains("\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"im_bereich_suchen\"")
                .contains("\"tool_calls\":[{\"function\":{\"name\":\"im_bereich_suchen\",\"arguments\":{\"text\":\"Abbruch\"}}}]")
                .contains("\"role\":\"tool\"", "Treffer");
    }

    /** Erwartet Werkzeugaufrufe; eine fertige Antwort oder ein Fehler lässt den Test scheitern. */
    private record ToolListener(java.util.concurrent.CompletableFuture<List<AiChat.ToolCall>> calls)
            implements AiChat.Listener {

        @Override
        public void delta(String text) {
        }

        @Override
        public void completed(String text) {
            calls.completeExceptionally(new AssertionError("Antwort statt Werkzeugaufruf"));
        }

        @Override
        public void toolCalls(String text, List<AiChat.ToolCall> called) {
            calls.complete(called);
        }

        @Override
        public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
            calls.completeExceptionally(error);
        }

        @Override
        public void cancelled() {
            calls.cancel(false);
        }
    }

    @Test
    void theOllamaChatLoadsTheModelOnceAndWaitsUntilItRuns() throws Exception {
        java.util.concurrent.atomic.AtomicInteger loads = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicBoolean running = new java.util.concurrent.atomic.AtomicBoolean();
        // Erst läuft das Modell nicht; der Anstoß lädt es, danach meldet /api/ps es.
        server.createContext("/api/ps", exchange -> respond(exchange, running.get()
                ? "{\"models\":[{\"name\":\"gemma4:12b\",\"model\":\"gemma4:12b\"}]}" : "{\"models\":[]}"));
        server.createContext("/api/generate", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(body).contains("\"model\":\"gemma4:12b\"", "\"num_ctx\"");
            loads.incrementAndGet();
            running.set(true);
            respond(exchange, "{\"done\":true}");
        });
        server.createContext("/api/chat", exchange -> {
            exchange.getRequestBody().readAllBytes();
            assertThat(running.get()).as("erst fragen, wenn das Modell läuft").isTrue();
            respond(exchange, """
                    {"message":{"role":"assistant","content":"OK"},"done":true,"done_reason":"stop"}
                    """);
        });
        AiServiceSettings settings = new AiServiceSettings(ollama(), null, "gemma4:12b", null, null, null, null,
                Set.of()).withProvider(AiProvider.OLLAMA).withLoadPollInterval(java.time.Duration.ofMillis(10));
        java.util.concurrent.atomic.AtomicInteger loading = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.CompletableFuture<String> done = new java.util.concurrent.CompletableFuture<>();

        remote(settings).chat().stream(new com.aresstack.spaceharvester.ai.AiChat.Request(
                List.of(com.aresstack.spaceharvester.ai.AiChat.Message.user("Hallo?")), "gemma4:12b", null),
                new com.aresstack.spaceharvester.ai.AiChat.Listener() {
                    @Override
                    public void loading() {
                        loading.incrementAndGet();
                    }

                    @Override
                    public void delta(String text) {
                    }

                    @Override
                    public void completed(String text) {
                        done.complete(text);
                    }

                    @Override
                    public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
                        done.completeExceptionally(error);
                    }

                    @Override
                    public void cancelled() {
                        done.cancel(false);
                    }
                });

        assertThat(done.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("OK");
        assertThat(loading.get()).as("„wird geladen“ einmal gemeldet").isEqualTo(1);
        assertThat(loads.get()).as("einmal angestoßen").isEqualTo(1);
    }

    @Test
    void readsTheProviderFromTheProperties() {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("space-harvester.ai.base-url", "http://localhost:11434");
        properties.setProperty("space-harvester.ai.provider", "Ollama");

        AiServiceSettings settings = AiServiceSettings.fromProperties(properties);

        assertThat(settings.provider()).isEqualTo(AiProvider.OLLAMA);
        assertThat(settings.apiBaseUrl()).isEqualTo(URI.create("http://localhost:11434/v1"));
        assertThat(settings.toString()).contains("provider=ollama");
        assertThat(AiServiceSettings.NONE.provider()).isEqualTo(AiProvider.OPENAI_COMPATIBLE);
        properties.setProperty("space-harvester.ai.provider", "lmstudio");
        assertThatThrownBy(() -> AiServiceSettings.fromProperties(properties))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ollama");
    }

    @Test
    void namesTopicSectionsWithTheChatSectionLabelerOfTheEnterpriseAiClient() {
        chatAnswer = """
                {"choices":[{"index":0,"message":{"role":"assistant","content":"1: Belegimport\\n2) Urlaubsantrag"}}]}""";
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        NavigationLabelModel.Labels labels = remote(settings).sectionLabels(null,
                List.of("Belege kommen über den Import. " + "x".repeat(800), "Urlaub beantragt man."));

        assertThat(labels.complete()).isTrue();
        assertThat(labels.labels()).containsExactly("Belegimport", "Urlaubsantrag");
        assertThat(bodies).singleElement().asString().contains("\"model\":\"gpt-x\"")
                .contains("Du benennst Abschnitte eines Dokuments").contains("x".repeat(600))
                .doesNotContain("x".repeat(700));
    }

    @Test
    void aFailingChatModelKeepsTheAnalysisLabelsAndSaysSo() {
        status = 503;
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        NavigationLabelModel.Labels labels = remote(settings).sectionLabels("Titel", List.of("Text eins.", "Zwei."));

        assertThat(labels.complete()).isFalse();
        assertThat(labels.labels()).containsExactly(null, null);
    }

    @Test
    void withoutVisionModelNoImageIsSent() {
        AiServiceSettings settings = new AiServiceSettings(base(), KEY, "gpt-x", null, null, null, null, Set.of());

        assertThatThrownBy(() -> remote(settings).describeImage(new byte[] {1}, "image/png"))
                .isInstanceOfSatisfying(AiServiceException.class,
                        e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.NOT_CONFIGURED));
        assertThat(bodies).isEmpty();
    }

    @Test
    void withoutChatModelThereAreNoVariantsFromTheService() {
        assertThatThrownBy(() -> remote(settings(null, null)).queryVariants()).isInstanceOfSatisfying(
                AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.NOT_CONFIGURED));
        assertThat(authorizations).isEmpty();
    }

    @Test
    void withoutKeyARejectionIsAMissingSignInNotAnUnexpectedAnswer() {
        status = 400;
        AiServiceSettings withoutKey = new AiServiceSettings(base(), null, null, null, null, null, null, Set.of());

        assertThatThrownBy(() -> remote(withoutKey).models()).isInstanceOfSatisfying(AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNAUTHORIZED));
        // Mit Schlüssel bleibt ein 400 eine unerwartete Antwort; 401 und 403 sind immer eine Ablehnung.
        assertThatThrownBy(() -> remote(settings(null, null)).models()).isInstanceOfSatisfying(
                AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.INVALID_RESPONSE));
        status = 401;
        assertThatThrownBy(() -> remote(settings(null, null)).models()).isInstanceOfSatisfying(
                AiServiceException.class,
                e -> {
                    assertThat(e.reason()).isEqualTo(AiServiceException.Reason.UNAUTHORIZED);
                    assertThat(e.getMessage()).doesNotContain(KEY);
                });
    }

    @Test
    void anUnreachableModelListIsAskedAgainWithGrowingPauses() {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        server.removeContext("/v1/models");
        server.createContext("/v1/models", exchange -> {
            // Zwei Mal 503 wie bei einem Neustart des Dienstes, dann die Liste.
            int call = calls.incrementAndGet();
            status = call <= 2 ? 503 : 200;
            respond(exchange, """
                    {"object":"list","data":[{"id":"gpt-x","capabilities":["chat"]}]}""");
        });

        assertThat(remote(settings(null, null)).models()).extracting(AiModel::id).containsExactly("gpt-x");
        assertThat(calls).hasValue(3);

        // Eine Ablehnung wird nicht wiederholt.
        calls.set(0);
        server.removeContext("/v1/models");
        server.createContext("/v1/models", exchange -> {
            calls.incrementAndGet();
            status = 401;
            respond(exchange, "{}");
        });
        assertThatThrownBy(() -> remote(settings(null, null)).models()).isInstanceOf(AiServiceException.class);
        assertThat(calls).hasValue(1);
    }

    @Test
    void withoutServiceOrModelNothingIsSent() {
        assertThatThrownBy(() -> remote(AiServiceSettings.NONE).models()).isInstanceOfSatisfying(
                AiServiceException.class,
                e -> assertThat(e.reason()).isEqualTo(AiServiceException.Reason.NOT_CONFIGURED));
        assertThatThrownBy(() -> remote(settings(null, null)).embeddings()).isInstanceOf(AiServiceException.class);
        assertThat(authorizations).isEmpty();
    }

    private RemoteAi remote(AiServiceSettings settings) {
        return new RemoteAi(settings, HttpRoutePort.direct(), TrustBundles.NONE, "space-harvester-test");
    }

    private AiServiceSettings settings(String model, Integer dimensions) {
        return new AiServiceSettings(base(), KEY, null, model, dimensions, null, null, Set.of("wiki"));
    }

    private URI ollama() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    private URI base() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
    }

    private void respond(com.sun.net.httpserver.HttpExchange exchange, String body) throws IOException {
        byte[] bytes = (status == 200 ? body : "{\"error\":{\"message\":\"nein\"}}").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
