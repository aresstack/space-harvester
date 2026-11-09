package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** „Daten bereinigen“ über echtes HTTP: Auftrag mit Fortschritt je Ablage, nur mit manage-configuration. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SourceDataApiTest {

    private static final String ADMIN_TOKEN = "purge-admin-token-5e8a";
    private static final Path CACHE = Path.of("build", "source-data-api-test", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "purge-admin");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(ADMIN_TOKEN));
        registry.add("space-harvester.security.clients[1].manage-configuration", () -> "true");
        registry.add("space-harvester.cache.directory", () -> CACHE.toString());
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void anAdministratorPurgesASourceAsAJobWithProgressPerStore() throws Exception {
        HttpResponse<String> started = send(ADMIN_TOKEN, "DELETE", "/api/v1/sources/wiki/data");

        assertThat(started.statusCode()).isEqualTo(202);
        JsonNode job = json.readTree(started.body());
        assertThat(job.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(job.get("stores")).extracting(JsonNode::asString).contains("Seiten-Cache, Katalog und Abgleichstände",
                "Volltextindex", "Begriffsbeziehungen", "Geprüfte Fakten");
        String location = started.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/sources/wiki/data/purges/" + job.get("id").asString());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode status = job;
        while (!status.get("state").asString().equals("completed") && Instant.now().isBefore(deadline)) {
            HttpResponse<String> polled = send(ADMIN_TOKEN, "GET", location);
            assertThat(polled.statusCode()).isEqualTo(200);
            status = json.readTree(polled.body());
            Thread.sleep(50);
        }
        assertThat(status.get("state").asString()).isEqualTo("completed");
        int stores = status.get("stores").size();
        assertThat(status.get("progress").get("discovered").asLong()).isEqualTo(stores);
        assertThat(status.get("progress").get("written").asLong()).isEqualTo(stores);
        assertThat(status.get("progress").get("failed").asLong()).isZero();
    }

    @Test
    void otherClientsAreRefusedAndUnknownSourcesAreNotFound() throws Exception {
        HttpResponse<String> forbidden = send(TestClient.TOKEN, "DELETE", "/api/v1/sources/wiki/data");
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("access-denied");
        assertThat(send(null, "DELETE", "/api/v1/sources/wiki/data").statusCode()).isEqualTo(401);

        HttpResponse<String> unknown = send(ADMIN_TOKEN, "DELETE", "/api/v1/sources/fehlt/data");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("source-not-found");
        assertThat(send(ADMIN_TOKEN, "GET", "/api/v1/sources/wiki/data/purges/unbekannt").statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> send(String token, String method, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:"
                + environment.getProperty("local.server.port") + path)).method(method,
                HttpRequest.BodyPublishers.noBody());
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
