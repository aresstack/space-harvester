package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Titel- und Metadatensuche über echtes HTTP, gegen den {@link InMemorySearchIndex}. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SearchApiTest {

    private static final String DEV_ONLY_TOKEN = "dev-only-token-5c2e";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        // Zweiter Client nur mit metadata auf DEV.
        registry.add("space-harvester.security.clients[1].id", () -> "dev-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(DEV_ONLY_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
    }

    @TestConfiguration
    static class Index {

        @Bean
        InMemorySearchIndex searchIndex() {
            return new InMemorySearchIndex();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private InMemorySearchIndex index;

    @BeforeEach
    void fillIndex() {
        index.clear();
        List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
        for (String id : new String[]{"101", "102", "103"}) {
            entries.add(entry(page("wiki", "DEV", id, null), confirmed("g1")));
        }
        entries.add(entry(page("wiki", "OPS", "900", null), confirmed("g7")));
        index.publish("wiki", new SearchIndexState("i1", Instant.parse("2026-10-16T20:10:00Z")), entries);
    }

    @Test
    void searchReturnsListFieldsIndexStateAndCacheStatePerHit() throws Exception {
        HttpResponse<String> response = get("/api/v1/search?sourceId=wiki&title=seite&spaceKey=DEV&limit=2"
                + "&sort=pageId");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("origin").asString()).isEqualTo("cache");
        assertThat(body.get("index").get("generation").asString()).isEqualTo("i1");
        assertThat(body.get("index").get("committedAt").asString()).isEqualTo("2026-10-16T20:10:00Z");
        assertThat(body.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");
        JsonNode hit = body.get("hits").get(0);
        assertThat(hit.get("page").get("pageId").asString()).isEqualTo("101");
        assertThat(hit.get("page").get("title").asString()).isEqualTo("Seite 101");
        assertThat(hit.get("page").get("rawSha256").asString()).startsWith("sha256:");
        assertThat(hit.get("page").has("data")).isFalse();
        assertThat(hit.get("cache").get("generation").asString()).isEqualTo("g1");
        assertThat(hit.get("cache").get("state").asString()).isEqualTo("confirmed");
        assertThat(body.get("complete").asBoolean()).isFalse();

        JsonNode next = json.readTree(get("/api/v1/search?sourceId=wiki&title=seite&spaceKey=DEV&limit=2&sort=pageId"
                + "&cursor=" + body.get("nextCursor").asString()).body());
        assertThat(next.get("hits").get(0).get("page").get("pageId").asString()).isEqualTo("103");
        assertThat(next.get("complete").asBoolean()).isTrue();
        assertThat(next.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void invalidParametersAre400() throws Exception {
        assertProblem(get("/api/v1/search?sourceId=wiki&sort=relevance"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&modifiedFrom=gestern"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&limit=101"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&title=a+b+c+d+e+f+g+h+i"), 400, "invalid-request");
        assertThat(get("/api/v1/search").statusCode()).isEqualTo(400);
        String cursor = json.readTree(get("/api/v1/search?sourceId=wiki&limit=1").body()).get("nextCursor")
                .asString();
        assertProblem(get("/api/v1/search?sourceId=wiki&limit=1&title=seite&cursor=" + cursor), 400,
                "invalid-request");
    }

    @Test
    void replacedIndexGenerationGives409() throws Exception {
        String cursor = json.readTree(get("/api/v1/search?sourceId=wiki&limit=1").body()).get("nextCursor")
                .asString();
        index.publish("wiki", new SearchIndexState("i2", Instant.parse("2026-10-16T21:00:00Z")), List.of());

        assertProblem(get("/api/v1/search?sourceId=wiki&limit=1&cursor=" + cursor), 409, "cache-generation-changed");
    }

    @Test
    void hitsAreLimitedToReleasedSpaces() throws Exception {
        JsonNode body = json.readTree(getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceId=wiki&sort=pageId").body());
        assertThat(body.get("hits").valueStream().map(hit -> hit.get("page").get("pageId").asString()))
                .containsExactly("101", "102", "103");
        assertThat(body.toString()).doesNotContain("900").doesNotContain("OPS");

        assertProblem(getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceId=wiki&spaceKey=OPS"), 403, "access-denied");
        assertProblem(get("/api/v1/search?sourceId=nope"), 403, "access-denied");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/v1/search?sourceId=wiki"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("Seite");
    }

    @Test
    void sourceWithoutIndexIsCacheMiss() throws Exception {
        index.clear();

        assertProblem(get("/api/v1/search?sourceId=wiki"), 404, "cache-miss");
    }

    @Test
    void searchIsDocumentedInOpenApi() throws Exception {
        JsonNode search = json.readTree(get("/v3/api-docs").body()).get("paths").get("/api/v1/search").get("get");

        assertThat(search.get("responses").has("409")).isTrue();
        assertThat(search.get("parameters").valueStream().map(parameter -> parameter.get("name").asString()))
                .contains("sourceId", "spaceKey", "title", "modifiedFrom", "modifiedBefore", "sort", "cursor");
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(uri(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAs(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
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
