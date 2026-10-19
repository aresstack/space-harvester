package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import org.springframework.context.annotation.Primary;
import com.aresstack.spaceharvester.EmptyConfluence;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

/** Begriffsindex über HTTP (#119): Begriffsliste, Fundstellen, Snippets und Freigaben je Seite. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TermsApiTest {

    private static final String METADATA_TOKEN = "metadata-only-token-51c2";
    private static final String READER_TOKEN = "reader-token-7d10e4";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", EmptyConfluence::baseUrl);
        TestClient.register(registry, "wiki");
        // Neu berechnen verlangt zusätzlich sync.
        registry.add("space-harvester.security.clients[0].grants[1].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[1].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[0].grants[1].access", () -> "sync");
        registry.add("space-harvester.security.clients[2].id", () -> "reader");
        registry.add("space-harvester.security.clients[2].token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add("space-harvester.security.clients[2].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[2].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[2].grants[0].access", () -> "metadata,content");
        registry.add("space-harvester.security.clients[1].id", () -> "metadata-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
    }

    @TestConfiguration
    static class Cache {

        @Bean
        @Primary
        PageFreshness cacheOnlyFreshness(SourceQueries queries) {
            return EmptyConfluence.cacheOnly(queries);
        }


        @Bean
        InMemoryPageCache pageCache() {
            return new InMemoryPageCache();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private InMemoryPageCache cache;

    @Test
    void acronymsAndTheirOccurrencesAreListedForPagesWithContentGrant() throws Exception {
        publish("601", "<p>Der RSL (Rückmelde-Schlüssel-Liste) steuert den Beleg. Ohne RSL kein Beleg.</p>");
        post("/api/v1/sources/wiki/pages/601/enrichments?processor=terms", TestClient.TOKEN);

        JsonNode list = json.readTree(get("/api/v1/terms?kind=acronym").body());
        assertThat(list.get("terms")).singleElement().satisfies(rsl -> {
            assertThat(rsl.get("term").asString()).isEqualTo("RSL");
            assertThat(rsl.get("kind").asString()).isEqualTo("acronym");
            assertThat(rsl.get("occurrences").asInt()).isEqualTo(2);
            assertThat(rsl.get("expansions").get(0).asString()).isEqualTo("Rückmelde-Schlüssel-Liste");
        });
        assertThat(list.get("nextCursor").isNull()).isTrue();

        JsonNode occurrences = json.readTree(get("/api/v1/terms/occurrences?term=rsl&snippetChars=10").body());
        assertThat(occurrences.get("occurrences")).hasSize(2);
        JsonNode first = occurrences.get("occurrences").get(0);
        assertThat(first.get("pageId").asString()).isEqualTo("601");
        assertThat(first.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(first.get("snippet").get("match").asString()).isEqualTo("RSL");

        HttpResponse<String> snippet = get("/api/v1/terms/snippet?sourceId=wiki&pageId=601&inputSha256="
                + first.get("inputSha256").asString() + "&start=" + first.get("start").asInt() + "&end="
                + first.get("end").asInt() + "&chars=5");
        assertThat(snippet.statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/terms/snippet?sourceId=wiki&pageId=601&inputSha256=sha256:alt&start=4&end=7")
                .statusCode()).isEqualTo(404);
        assertThat(json.readTree(get("/api/v1/terms?q=beleg&kind=noun").body()).get("terms").get(0).get("pages")
                .asInt()).isEqualTo(1);
    }

    @Test
    void withoutContentGrantNothingLeaks() throws Exception {
        publish("602", "<p>Vertraulich: KPI (Key Performance Indicator)</p>");
        post("/api/v1/sources/wiki/pages/602/enrichments?processor=terms", TestClient.TOKEN);

        HttpResponse<String> list = send("/api/v1/terms?q=key", METADATA_TOKEN);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(json.readTree(list.body()).get("terms")).isEmpty();
        assertThat(send("/api/v1/terms/occurrences?term=kpi&snippetChars=50", METADATA_TOKEN).body())
                .doesNotContain("Key Performance");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/v1/terms")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    @Test
    void invalidParametersAreRefused() throws Exception {
        assertProblem(get("/api/v1/terms?kind=fachbegriff"), 400, "invalid-request");
        assertProblem(get("/api/v1/terms?limit=0"), 400, "invalid-request");
        assertProblem(get("/api/v1/terms?cursor=kaputt"), 400, "invalid-request");
        assertThat(get("/api/v1/terms/occurrences").statusCode()).isEqualTo(400);
        assertProblem(get("/api/v1/terms?sourceId=unbekannt"), 404, "source-not-found");
    }

    @Test
    void theRoutesAreDocumented() throws Exception {
        JsonNode paths = json.readTree(get("/v3/api-docs").body()).get("paths");

        assertThat(paths.has("/api/v1/terms")).isTrue();
        assertThat(paths.has("/api/v1/terms/occurrences")).isTrue();
        assertThat(paths.has("/api/v1/terms/snippet")).isTrue();
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private PageSnapshot publish(String pageId, String storage) {
        String payload = "{\"id\":\"" + pageId + "\",\"type\":\"page\",\"body\":{\"storage\":{\"value\":\""
                + storage.replace("\"", "\\\"") + "\",\"representation\":\"storage\"}}}";
        PageSnapshot snapshot = page("wiki", "DEV", pageId, null, payload.getBytes(StandardCharsets.UTF_8));
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(snapshot));
        return snapshot;
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(uri(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
