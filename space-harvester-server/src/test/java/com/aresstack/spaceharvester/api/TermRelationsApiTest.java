package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.EmptyConfluence;
import com.aresstack.spaceharvester.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Begriffsbeziehungen über HTTP (#120): anlegen, ablehnen, wiederherstellen, Rechte, Ablage außerhalb des Caches. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class TermRelationsApiTest {

    private static final String READER_TOKEN = "relations-reader-token-93ab";
    private static final Path DIRECTORY = temp();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", EmptyConfluence::baseUrl);
        registry.add("space-harvester.term-relations.directory", DIRECTORY::toString);
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[0].grants[1].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[1].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[0].grants[1].access", () -> "sync");
        registry.add("space-harvester.security.clients[1].id", () -> "reader");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata,content");
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void manualRelationIsStoredRejectedAndRestored() throws Exception {
        HttpResponse<String> created = send("POST", "/api/v1/term-relations", "{\"fromTerm\":\"RSL\","
                + "\"toTerm\":\"Rückmeldeschlüssel\",\"type\":\"abbreviation\",\"note\":\"aus dem Fachbereich\"}", null);
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode relation = json.readTree(created.body());
        String id = relation.get("id").asString();
        assertThat(relation.get("origin").asString()).isEqualTo("manual");
        assertThat(relation.get("status").asString()).isEqualTo("confirmed");
        assertThat(relation.get("directed").asBoolean()).isTrue();
        assertThat(Files.readString(DIRECTORY.resolve("relations.json"))).contains(id, "Rückmeldeschlüssel");

        JsonNode list = json.readTree(send("GET", "/api/v1/term-relations?term=rsl", null, READER_TOKEN).body());
        assertThat(list.get("relations")).hasSize(1);

        long revision = relation.get("revision").asLong();
        assertThat(send("POST", "/api/v1/term-relations/" + id + "/reject?revision=" + revision, null, null)
                .statusCode()).isEqualTo(200);
        assertProblem(send("POST", "/api/v1/term-relations/" + id + "/restore?revision=" + revision, null, null),
                409, "relation-conflict");
        assertThat(json.readTree(send("GET", "/api/v1/term-relations?term=rsl", null, null).body())
                .get("relations")).isEmpty();
        assertThat(json.readTree(send("GET", "/api/v1/term-relations?status=rejected", null, null).body())
                .get("relations")).hasSize(1);
        assertThat(json.readTree(send("POST", "/api/v1/term-relations/" + id + "/restore", null, null).body())
                .get("status").asString()).isEqualTo("confirmed");
        JsonNode history = json.readTree(send("GET", "/api/v1/term-relations/" + id + "/history", null, null)
                .body());
        assertThat(history.findValuesAsString("action")).containsExactly("create", "reject", "restore");

        assertThat(send("DELETE", "/api/v1/term-relations/" + id, null, null).statusCode()).isEqualTo(204);
        assertProblem(send("GET", "/api/v1/term-relations/" + id, null, null), 404, "relation-not-found");
    }

    @Test
    void writingNeedsSyncAndInputIsChecked() throws Exception {
        String body = "{\"fromTerm\":\"ZV\",\"toTerm\":\"Zahlungsverkehr\",\"type\":\"abbreviation\"}";
        assertThat(send("POST", "/api/v1/term-relations", body, READER_TOKEN).statusCode()).isEqualTo(403);
        assertProblem(send("POST", "/api/v1/term-relations", "{\"fromTerm\":\"ZV\",\"toTerm\":\"zv\","
                + "\"type\":\"synonym\"}", null), 400, "invalid-request");
        assertProblem(send("POST", "/api/v1/term-relations", "{\"fromTerm\":\"ZV\",\"toTerm\":\"Z\","
                + "\"type\":\"gleich\"}", null), 400, "invalid-request");
        assertProblem(send("POST", "/api/v1/term-relations", body.replace("}", ",\"sourceId\":\"unbekannt\"}"),
                null), 404, "source-not-found");
        assertProblem(send("GET", "/api/v1/term-relations?status=vielleicht", null, null), 400, "invalid-request");
    }

    @Test
    void ruleRunNeedsSyncOnTheSource() throws Exception {
        HttpResponse<String> run = send("POST", "/api/v1/term-relations/rules/acronyms?sourceId=wiki", null, null);
        assertThat(run.statusCode()).isEqualTo(200);
        assertThat(json.readTree(run.body()).has("added")).isTrue();
        assertThat(send("POST", "/api/v1/term-relations/rules/acronyms?sourceId=wiki", null, READER_TOKEN)
                .statusCode()).isEqualTo(403);
        assertProblem(send("POST", "/api/v1/term-relations/rules/acronyms?sourceId=nix", null, null), 404,
                "source-not-found");
    }

    @Test
    void aiRefreshWithoutModelIsUnavailableAndChecksRightsAndKind() throws Exception {
        assertProblem(send("POST", "/api/v1/term-relations/refresh?sourceId=wiki", "{\"kind\":\"acronym\"}",
                null), 503, "ai-unavailable");
        assertProblem(send("POST", "/api/v1/term-relations/refresh?sourceId=wiki", null, null), 503,
                "ai-unavailable");
        assertThat(send("POST", "/api/v1/term-relations/refresh?sourceId=wiki", null, READER_TOKEN).statusCode())
                .isEqualTo(403);
        assertProblem(send("POST", "/api/v1/term-relations/refresh?sourceId=wiki", "{\"kind\":\"noun\"}", null),
                400, "invalid-request");
        assertProblem(send("GET", "/api/v1/term-relations/refresh-jobs/unbekannt", null, null), 404,
                "job-not-found");
    }

    @Test
    void theRoutesAreDocumented() throws Exception {
        JsonNode paths = json.readTree(send("GET", "/v3/api-docs", null, null).body()).get("paths");

        assertThat(paths.has("/api/v1/term-relations")).isTrue();
        assertThat(paths.has("/api/v1/term-relations/{id}/reject")).isTrue();
        assertThat(paths.has("/api/v1/term-relations/refresh")).isTrue();
        assertThat(paths.has("/api/v1/term-relations/refresh-jobs/{jobId}")).isTrue();
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body));
        if (token == null) {
            TestClient.authenticated(request);
        } else {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static Path temp() {
        try {
            return Files.createTempDirectory("term-relations");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static String sha256(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
