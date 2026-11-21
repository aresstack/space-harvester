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

/** Strukturgraph (Paket G1) über echtes HTTP: Kanten mit Grundlage und Herkunft, Kennzeichen, Rechte, Fehler. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageConnectionsApiTest {

    private static final String METADATA_ONLY_TOKEN = "metadata-only-token-g1a7";

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
    void fill() {
        index.clear();
        index.publish("wiki", new SearchIndexState("i1", Instant.parse("2026-11-21T03:00:00Z")), List.of(
                entry(page("wiki", "DEV", "100", null), confirmed("g1")),
                entry(page("wiki", "DEV", "101", "100"), confirmed("g1")),
                entry(page("wiki", "DEV", "102", "101"), confirmed("g1"))));
    }

    @Test
    void hierarchyEdgesAreExplicitAndMissingFoundationsAreFlagged() throws Exception {
        HttpResponse<String> response = get(TestClient.TOKEN, "101", "");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("seed").get("title").asString()).isEqualTo("Seite 101");
        assertThat(body.get("edges").valueStream().map(edge -> edge.get("kind").asString() + ":"
                + edge.get("target").get("pageId").asString() + ":" + edge.get("basis").asString() + ":"
                + edge.get("provenance").get("type").asString()))
                .containsExactly("parent:100:explicit:hierarchy", "child:102:explicit:hierarchy");
        assertThat(body.get("edges").get(0).get("fromPageId").asString()).isEqualTo("101");
        assertThat(body.get("edges").get(0).get("provenance").has("similarity")).isFalse();
        assertThat(body.get("coverage").get("parent").asString()).isEqualTo("complete");
        assertThat(body.get("coverage").get("links-to").asString()).isEqualTo("unavailable");
        assertThat(body.get("complete").asBoolean()).isFalse();
        assertThat(body.get("pagesConsidered").asInt()).isEqualTo(3);
    }

    @Test
    void kindsAndLimitsNarrowTheAnswer() throws Exception {
        JsonNode body = json.readTree(get(TestClient.TOKEN, "101", "kind=child&limit=1").body());

        assertThat(body.get("edges").valueStream().map(edge -> edge.get("kind").asString()))
                .containsExactly("child");
        assertThat(body.get("coverage").size()).isEqualTo(1);
        assertThat(body.get("complete").asBoolean()).isTrue();
    }

    @Test
    void withoutContentAccessTheSeedIsNotInTheCacheAndNothingLeaks() throws Exception {
        HttpResponse<String> hidden = get(METADATA_ONLY_TOKEN, "101", "");

        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).doesNotContain("Seite 100").doesNotContain("Seite 102");
        assertThat(get(TestClient.TOKEN, "101", "kind=statement").statusCode()).isEqualTo(400);
        assertThat(get(TestClient.TOKEN, "101", "limit=0").statusCode()).isEqualTo(400);
        assertThat(get(TestClient.TOKEN, "999", "").statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String token, String pageId, String parameters) throws Exception {
        URI uri = URI.create("http://localhost:" + environment.getProperty("local.server.port")
                + "/api/v1/sources/wiki/pages/" + pageId + "/connections?" + parameters);
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
