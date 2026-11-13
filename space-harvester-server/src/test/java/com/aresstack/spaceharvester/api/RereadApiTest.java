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
import java.util.List;
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

/** „Neu lesen“ (ADR 0026, Punkt 11) über echtes HTTP: Auftrag mit Fortschritt, nur mit manage-configuration. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RereadApiTest {

    private static final String ADMIN_TOKEN = "reread-admin-token-4e2a";
    private static final Path DATA = Path.of("build", "reread-api-test", UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "reread-admin");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(ADMIN_TOKEN));
        registry.add("space-harvester.security.clients[1].manage-configuration", () -> "true");
        registry.add("space-harvester.assets.directory", () -> DATA.resolve("assets").toString());
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void anAdministratorStartsARereadRestrictedToStatusesAndFollowsIt() throws Exception {
        HttpResponse<String> started = send(ADMIN_TOKEN, "POST",
                "/api/v1/sources/wiki/data/rereads?status=failed,PARTIAL");

        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        JsonNode job = json.readTree(started.body());
        assertThat(job.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(job.get("statuses").valueStream().map(JsonNode::asString).toList())
                .containsExactly("failed", "partial");
        String location = started.headers().firstValue("Location").orElseThrow();
        assertThat(location).isEqualTo("/api/v1/sources/wiki/data/rereads/" + job.get("id").asString());

        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JsonNode status = job;
        while (!List.of("completed", "failed").contains(status.get("state").asString())
                && Instant.now().isBefore(deadline)) {
            HttpResponse<String> polled = send(ADMIN_TOKEN, "GET", location);
            assertThat(polled.statusCode()).isEqualTo(200);
            status = json.readTree(polled.body());
            Thread.sleep(50);
        }
        assertThat(status.get("state").asString()).as(status.toString()).isEqualTo("completed");
        assertThat(status.get("progress").get("discovered").asLong()).as("nichts abgelegt").isZero();

        HttpResponse<String> all = send(ADMIN_TOKEN, "POST", "/api/v1/sources/wiki/data/rereads");
        assertThat(all.statusCode()).isEqualTo(202);
        assertThat(json.readTree(all.body()).get("statuses")).isEmpty();
    }

    @Test
    void unknownStatusesOtherClientsAndUnknownSourcesAreRefused() throws Exception {
        HttpResponse<String> invalid = send(ADMIN_TOKEN, "POST", "/api/v1/sources/wiki/data/rereads?status=bald");
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("invalid-request").doesNotContain("bald");

        assertThat(send(TestClient.TOKEN, "POST", "/api/v1/sources/wiki/data/rereads").statusCode())
                .isEqualTo(403);
        assertThat(send(null, "POST", "/api/v1/sources/wiki/data/rereads").statusCode()).isEqualTo(401);

        HttpResponse<String> unknown = send(ADMIN_TOKEN, "POST", "/api/v1/sources/fehlt/data/rereads");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("source-not-found");
        assertThat(send(ADMIN_TOKEN, "GET", "/api/v1/sources/wiki/data/rereads/unbekannt").statusCode())
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
