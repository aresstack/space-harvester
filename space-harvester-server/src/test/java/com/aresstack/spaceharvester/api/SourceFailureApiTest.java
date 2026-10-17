package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * Fehlerfälle der Quelle über echtes HTTP (#13): Wiederholungen bei Netzfehlern, 5xx und 429, keine bei 401/403/404,
 * gesperrte Seiten wie unbekannte, gekürzte Labels sichtbar, geänderte Seiten mit neuem Stand. Die Antworten nennen
 * nie Meldungen der Quelle.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SourceFailureApiTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final int CLOSED_PORT;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.page("101", "Gesperrt", "DEV", "<p>geheim</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("102", "Viele Labels", "DEV", "<p>L</p>").labels("a", "b").moreLabels();
        CONFLUENCE.child("100", "102");
        CONFLUENCE.page("103", "Ändert sich", "DEV", "<p>1</p>").version(1, "2026-10-01T10:00:00.000Z");
        CONFLUENCE.child("100", "103");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.restrict("101");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
            try (ServerSocket socket = new ServerSocket(0)) {
                CLOSED_PORT = socket.getLocalPort();
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "wiki", "einmal", "tot");
        source(registry, 0, "wiki", SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        source(registry, 1, "einmal", SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[1].options.maxRetries", () -> "0");
        source(registry, 2, "tot", "http://127.0.0.1:" + CLOSED_PORT + "/wiki");
        registry.add("space-harvester.sources[2].options.retryBackoffMillis", () -> "1");
    }

    private static void source(DynamicPropertyRegistry registry, int index, String id, String baseUrl) {
        String prefix = "space-harvester.sources[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "type", () -> "confluence");
        registry.add(prefix + "base-url", () -> baseUrl);
        registry.add(prefix + "options.connectTimeoutMillis", () -> "2000");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void shortOutageIsRetriedWithTheDefaultOfTwoRetries() throws Exception {
        int before = CONFLUENCE.requestCount("/content/100");
        CONFLUENCE.failNext(503, 2);

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(CONFLUENCE.requestCount("/content/100") - before).isEqualTo(3);
    }

    @Test
    void lastingOutageIsUnavailableAfterTheRetries() throws Exception {
        CONFLUENCE.failNext(502, 3);

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("problems/source-unavailable").doesNotContain("502")
                .doesNotContain("Wiederholungen");
    }

    @Test
    void throttlingStaysRecognisableAs429() throws Exception {
        CONFLUENCE.failNext(429, 3);

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(429);
        assertThat(response.body()).contains("problems/source-rate-limited").contains("\"sourceId\":\"wiki\"");
    }

    @Test
    void withoutRetriesTheFirstFailureCounts() throws Exception {
        CONFLUENCE.failNext(503, 1);

        HttpResponse<String> response = get("/api/v1/sources/einmal/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(get("/api/v1/sources/einmal/pages/100/metadata").statusCode()).isEqualTo(200);
    }

    @Test
    void unreachableSourceIsUnavailable() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/tot/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("problems/source-unavailable").doesNotContain("127.0.0.1")
                .doesNotContain(String.valueOf(CLOSED_PORT));
    }

    @Test
    void rejectedLoginIsNotRetried() throws Exception {
        int before = CONFLUENCE.requestCount("/content/100");
        CONFLUENCE.failNext(401, 1);

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/100/metadata");

        assertThat(response.statusCode()).isEqualTo(502);
        assertThat(response.body()).contains("problems/source-access-denied");
        assertThat(CONFLUENCE.requestCount("/content/100") - before).isEqualTo(1);
    }

    @Test
    void restrictedPageLooksLikeAnUnknownOneAndIsMissingFromTheListing() throws Exception {
        HttpResponse<String> restricted = get("/api/v1/sources/wiki/pages/101/metadata");
        HttpResponse<String> unknown = get("/api/v1/sources/wiki/pages/999/metadata");

        assertThat(restricted.statusCode()).isEqualTo(404);
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(restricted.body()).contains("problems/source-object-not-found").doesNotContain("Gesperrt");
        assertThat(json.readTree(restricted.body()).get("detail"))
                .isEqualTo(json.readTree(unknown.body()).get("detail"));
        assertThat(get("/api/v1/sources/wiki/spaces/DEV/pages/metadata").body()).doesNotContain("\"101\"");
    }

    @Test
    void truncatedLabelsAreMarkedIncomplete() throws Exception {
        JsonNode page = json.readTree(get("/api/v1/sources/wiki/pages/102/metadata").body()).get("page");
        JsonNode complete = json.readTree(get("/api/v1/sources/wiki/pages/100/metadata").body()).get("page");

        assertThat(page.get("attributes").get("confluence.labels.complete").asString()).isEqualTo("false");
        assertThat(complete.get("attributes").has("confluence.labels.complete")).isFalse();
    }

    @Test
    void changedPageShowsItsNewVersionAndHash() throws Exception {
        JsonNode before = json.readTree(get("/api/v1/sources/wiki/pages/103/metadata").body()).get("page");
        CONFLUENCE.page("103", "Ändert sich", "DEV", "<p>2</p>").version(2, "2026-10-02T10:00:00.000Z");
        CONFLUENCE.child("100", "103");

        JsonNode after = json.readTree(get("/api/v1/sources/wiki/pages/103/metadata").body()).get("page");

        assertThat(before.get("version").asString()).isEqualTo("1");
        assertThat(after.get("version").asString()).isEqualTo("2");
        assertThat(after.get("metadataHash").asString()).isNotEqualTo(before.get("metadataHash").asString());
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)))
                        .GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
