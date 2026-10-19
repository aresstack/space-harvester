package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Bildanalyse über echtes HTTP (ADR 0021): ohne Vision-Modell liest der Reiter einen klaren Grund, Beschreiben antwortet
 * mit 503, und nichts geht an einen KI-Dienst oder lädt Bilder.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ImagesApiTest {

    private static final String READER_TOKEN = "image-reader-token-3c9e";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start mit Bild</p>");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.attachment("att1", "100", "foto.png", "image/png", "PNG");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        String client = "space-harvester.security.clients[0].";
        registry.add(client + "id", () -> TestClient.ID);
        registry.add(client + "token-sha256", () -> TestClient.TOKEN_SHA256);
        registry.add(client + "grants[0].source", () -> "wiki");
        registry.add(client + "grants[0].spaces[0]", () -> "*");
        registry.add(client + "grants[0].access", () -> "metadata,content,sync");
        String reader = "space-harvester.security.clients[1].";
        registry.add(reader + "id", () -> "nur-inhalt");
        registry.add(reader + "token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add(reader + "grants[0].source", () -> "wiki");
        registry.add(reader + "grants[0].spaces[0]", () -> "*");
        registry.add(reader + "grants[0].access", () -> "metadata,content");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void withoutVisionModelTheTabNamesTheReasonAndNothingIsLoaded() throws Exception {
        int before = CONFLUENCE.requestCount("/download/");

        HttpResponse<String> response = send("GET", "/api/v1/sources/wiki/pages/100/images", TestClient.TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("state").asString()).isEqualTo("missing");
        assertThat(body.get("reason").asString()).isEqualTo("vision-model-missing");
        assertThat(body.get("images")).isEmpty();
        assertThat(CONFLUENCE.requestCount("/download/")).isEqualTo(before);
    }

    @Test
    void describingWithoutVisionModelIsUnavailable() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/sources/wiki/pages/100/images", TestClient.TOKEN);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("image-description-unavailable");
    }

    @Test
    void describingNeedsTheSyncGrant() throws Exception {
        HttpResponse<String> response = send("POST", "/api/v1/sources/wiki/pages/100/images", READER_TOKEN);

        assertThat(response.statusCode()).isEqualTo(403);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
