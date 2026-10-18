package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProcessor;
import com.aresstack.spaceharvester.application.enrichment.embedding.EmbeddingProvider;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.source.KnowledgeTextSegmentation;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code sort=relevance} über echtes HTTP mit einem Spielzeugmodell (#89): Reihenfolge nach Kosinus, Ähnlichkeit je
 * Treffer und der Negativfall ohne {@code content}-Freigabe.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RelevanceSearchApiTest {

    private static final String METADATA_ONLY_TOKEN = "metadata-only-token-81d0";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "metadata-only");
        registry.add("space-harvester.security.clients[1].token-sha256",
                () -> "sha256:" + sha256(METADATA_ONLY_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
    }

    /** Je Achse ein Stichwort. */
    static final List<String> AXES = List.of("betrieb", "architektur", "urlaub");

    @TestConfiguration
    static class Model {

        @Bean
        InMemorySearchIndex searchIndex() {
            return new InMemorySearchIndex();
        }

        @Bean
        InMemoryEnrichmentStore enrichmentStore() {
            return new InMemoryEnrichmentStore();
        }

        @Bean
        EmbeddingProcessor embeddingProcessor() {
            return new EmbeddingProcessor(new EmbeddingProvider() {
                @Override
                public String modelId() {
                    return "axes";
                }

                @Override
                public int dimensions() {
                    return AXES.size();
                }

                @Override
                public List<float[]> embed(List<String> texts) {
                    return texts.stream().map(RelevanceSearchApiTest::axes).toList();
                }
            }, 1500, KnowledgeTextSegmentation.defaults());
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private InMemorySearchIndex index;

    @Autowired
    private InMemoryEnrichmentStore store;

    @Autowired
    private EmbeddingProcessor processor;

    @BeforeEach
    void fill() {
        index.clear();
        index.publish("wiki", new SearchIndexState("i1", Instant.parse("2026-10-17T08:00:00Z")), List.of(
                entry(page("wiki", "DEV", "101", null), confirmed("g1")),
                entry(page("wiki", "DEV", "102", null), confirmed("g1")),
                entry(page("wiki", "OPS", "900", null), confirmed("g7"))));
        embed("101", "[0,1,0]");
        embed("102", "[0.6,0.8,0]");
        embed("900", "[1,0,0]");
    }

    @Test
    void hitsAreOrderedByCosineAndCarryTheirSimilarity() throws Exception {
        HttpResponse<String> response = get(TestClient.TOKEN, "Wie läuft der Betrieb?");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("reranker").asString()).isEqualTo("embedding-cosine@" + processor.version());
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("hits").valueStream().map(hit -> hit.get("page").get("pageId").asString()))
                .containsExactly("900", "102", "101");
        JsonNode relevance = body.get("hits").get(1).get("relevance");
        assertThat(relevance.get("similarity").asDouble()).isBetween(0.5999, 0.6001);
        assertThat(relevance.get("chunk").get("charStart").asInt()).isZero();
        assertThat(relevance.get("chunk").get("charEnd").asInt()).isEqualTo(10);
        assertThat(relevance.get("freshness").asString()).isEqualTo("current");
    }

    @Test
    void withoutContentAccessTheVectorsFindNothingAndNothingLeaks() throws Exception {
        HttpResponse<String> response = get(METADATA_ONLY_TOKEN, "Wie läuft der Betrieb?");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("hits").isEmpty()).isTrue();
        assertThat(response.body()).doesNotContain("similarity").doesNotContain("900");
    }

    private void embed(String pageId, String vector) {
        store.save(new Enrichment(new PageKey("wiki", pageId), "embedding", processor.version(),
                "sha256:" + "1".repeat(64), "3", Enrichment.Status.OK, "{\"model\":\"axes\",\"dimensions\":3,"
                + "\"chunks\":[{\"charStart\":0,\"charEnd\":10,\"section\":null,\"vector\":" + vector + "}]}",
                List.of(), null, Instant.parse("2026-10-17T07:00:00Z")));
    }

    static float[] axes(String text) {
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        float[] vector = new float[AXES.size()];
        for (int i = 0; i < AXES.size(); i++) {
            vector[i] = lower.contains(AXES.get(i)) ? 1 : 0;
        }
        return vector;
    }

    private HttpResponse<String> get(String token, String q) throws Exception {
        URI uri = URI.create("http://localhost:" + environment.getProperty("local.server.port")
                + "/api/v1/search?sourceId=wiki&sort=relevance&q=" + URLEncoder.encode(q, StandardCharsets.UTF_8));
        return http.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
