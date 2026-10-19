package com.aresstack.spaceharvester.source.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoute;
import com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort;
import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.ai.AiServiceException;
import com.aresstack.spaceharvester.ai.AiServiceSettings;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingUnavailableException;
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
            bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"choices":[{"index":0,"message":{"role":"assistant",
                      "content":"{\\"queries\\":[{\\"text\\":\\"Nachtlauf Abbruch\\",\\"priority\\":1}]}"}}]}""");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Test
    void embedsOverTheServiceWithBearerAndModel() {
        EmbeddingProvider provider = remote(settings("text-embedding-3-small", 3)).embeddings();

        List<float[]> vectors = provider.embed(List.of("Betrieb der Plattform"));

        assertThat(provider.modelId()).isEqualTo("text-embedding-3-small");
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
                .hasMessageNotContaining(KEY).hasMessageNotContaining("vertraulich");
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
