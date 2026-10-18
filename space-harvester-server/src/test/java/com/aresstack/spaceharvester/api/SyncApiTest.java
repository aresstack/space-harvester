package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sync.InMemorySyncStore;
import com.aresstack.spaceharvester.application.sync.PageContentFetcher;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Sync-API über echtes HTTP mit Fake-Speicher und Fake-Inhaltsabruf. Die Quelle ist nicht erreichbar, jeder Lauf
 * endet deshalb ohne bestätigte Generation; das genügt für Freigaben, Sichtbarkeit und Ausgang.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SyncApiTest {

    private static final String SYNC_TOKEN = "sync-client-token-51ab";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        // Zweiter Client mit sync nur auf DEV.
        registry.add("space-harvester.security.clients[1].id", () -> "syncer");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(SYNC_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata,sync");
    }

    @TestConfiguration
    static class Sync {

        @Bean
        InMemorySyncStore syncStore() {
            return new InMemorySyncStore();
        }

        /** Ersetzt den echten Abruf aus der Quelle ({@code SourceSnapshots}). */
        @Bean
        @Primary
        PageContentFetcher contentFetcher() {
            return (sourceId, pageId) -> {
                throw new SourceAccessException(sourceId, SourceAccessException.Reason.UNAVAILABLE, "nicht erreichbar",
                        null);
            };
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void aSyncRunsAsAJobVisibleOnlyToItsClientAndFailsWithoutAConfirmedGeneration() throws Exception {
        HttpResponse<String> started = post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs");

        assertThat(started.statusCode()).isEqualTo(202);
        JsonNode accepted = json.readTree(started.body());
        String id = accepted.get("id").asString();
        assertThat(started.headers().firstValue("Location")).contains("/api/v1/syncs/" + id);
        assertThat(accepted.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(accepted.get("state").asString()).isIn("queued", "running");

        JsonNode done = awaitFinished(id);
        assertThat(done.get("state").asString()).isEqualTo("failed");
        assertThat(done.get("error").get("code").asString()).isEqualTo("SYNC_INCOMPLETE");
        JsonNode summary = done.get("summary");
        assertThat(summary.get("inventory").asString()).isEqualTo("incomplete");
        assertThat(summary.get("generation").isNull()).isTrue();
        assertThat(summary.get("notConfirmed").asString()).isEqualTo("inventory-incomplete");
        assertThat(done.get("error").get("message").asString()).doesNotContain("127.0.0.1");
        assertThat(summary.get("failed").asInt()).isZero();
        assertThat(summary.get("failedPageIds").isArray()).isTrue();

        HttpResponse<String> foreign = send(TestClient.TOKEN, "GET", "/api/v1/syncs/" + id);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).contains("sync-not-found");
        assertThat(send(TestClient.TOKEN, "POST", "/api/v1/syncs/" + id + "/cancel").statusCode()).isEqualTo(404);

        HttpResponse<String> cancelled = post(SYNC_TOKEN, "/api/v1/syncs/" + id + "/cancel");
        assertThat(cancelled.statusCode()).isEqualTo(200);
        assertThat(json.readTree(cancelled.body()).get("state").asString()).isEqualTo("failed");
    }

    @Test
    void catchingUpWithoutAConfirmedGenerationOnlySetsTheStartingPoint() throws Exception {
        HttpResponse<String> started = post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs?listing=changes");

        assertThat(started.statusCode()).isEqualTo(202);
        JsonNode done = awaitFinished(json.readTree(started.body()).get("id").asString());
        assertThat(done.get("state").asString()).isEqualTo("completed");
        assertThat(done.get("summary").get("inventory").asString()).isEqualTo("changes");
        assertThat(done.get("summary").get("generation").asLong()).isPositive();
    }

    @Test
    void theListingModeAndSinceAreChecked() throws Exception {
        assertThat(post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs?listing=full").statusCode())
                .isEqualTo(400);
        assertThat(post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs?mode=everything").statusCode())
                .isEqualTo(400);
        assertThat(post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs?mode=full&loadUnknown=true")
                .statusCode()).isEqualTo(400);
        HttpResponse<String> sinceWithoutChanges = post(SYNC_TOKEN,
                "/api/v1/sources/wiki/spaces/DEV/syncs?since=2026-10-18T10:00:00Z");
        assertThat(sinceWithoutChanges.statusCode()).isEqualTo(400);
    }

    @Test
    void theCatalogOfASpaceNeedsTheMetadataGrantAndNoSource() throws Exception {
        HttpResponse<String> catalog = send(SYNC_TOKEN, "GET", "/api/v1/sources/wiki/spaces/DEV/catalog");
        assertThat(catalog.statusCode()).as(catalog.body()).isEqualTo(200);
        JsonNode body = json.readTree(catalog.body());
        // Ohne Inhaltsabruf bleibt der Katalog leer; ein Nachholen anderer Tests setzt höchstens den Ausgangspunkt.
        assertThat(body.get("state").asString()).isIn("unknown", "cataloged");
        assertThat(body.get("knownPages").asInt()).isZero();
        assertThat(send(SYNC_TOKEN, "GET", "/api/v1/sources/wiki/spaces/OPS/catalog").statusCode()).isEqualTo(403);
    }

    @Test
    void startingNeedsTheSyncGrantForThatSpace() throws Exception {
        assertThat(post(TestClient.TOKEN, "/api/v1/sources/wiki/spaces/DEV/syncs").statusCode()).isEqualTo(403);
        assertThat(post(SYNC_TOKEN, "/api/v1/sources/wiki/spaces/OPS/syncs").statusCode()).isEqualTo(403);
        assertThat(post(SYNC_TOKEN, "/api/v1/sources/other/spaces/DEV/syncs").statusCode()).isEqualTo(403);
        assertThat(send(SYNC_TOKEN, "GET", "/api/v1/syncs/unbekannt").statusCode()).isEqualTo(404);
        assertThat(http.send(HttpRequest.newBuilder(uri("/api/v1/sources/wiki/spaces/DEV/syncs"))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString())
                .statusCode()).isEqualTo(401);
    }

    private JsonNode awaitFinished(String id) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (true) {
            HttpResponse<String> response = send(SYNC_TOKEN, "GET", "/api/v1/syncs/" + id);
            assertThat(response.statusCode()).isEqualTo(200);
            JsonNode body = json.readTree(response.body());
            String state = body.get("state").asString();
            if (!state.equals("queued") && !state.equals("running")) {
                return body;
            }
            assertThat(Instant.now()).isBefore(deadline);
            Thread.sleep(20);
        }
    }

    private HttpResponse<String> post(String token, String path) throws Exception {
        return send(token, "POST", path);
    }

    private HttpResponse<String> send(String token, String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
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
