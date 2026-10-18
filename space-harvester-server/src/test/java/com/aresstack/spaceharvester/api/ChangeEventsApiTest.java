package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.demo.DemoConfluence;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Webhooks gegen eine Fake-Confluence über echtes HTTP (ADR 0014): signierte Aufrufe ohne Client-Token markieren nur
 * den Katalog, laden keine Inhalte; falsche Signatur, Quelle ohne Geheimnis und unbekannte Quelle bewirken nichts.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ChangeEventsApiTest {

    private static final String SECRET = "webhook-geheimnis-91c2";
    private static final FakeConfluence CONFLUENCE = DemoConfluence.fixture();
    private static final DemoConfluence SERVER;
    private static final Path DATA;

    static {
        try {
            SERVER = DemoConfluence.start(CONFLUENCE);
            DATA = Files.createTempDirectory("change-events");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "demo");
        registry.add("space-harvester.security.clients[0].grants[0].access", () -> "metadata,content,sync");
        registry.add("space-harvester.sources[0].id", () -> "demo");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[1].id", () -> "ohne");
        registry.add("space-harvester.sources[1].type", () -> "confluence");
        registry.add("space-harvester.sources[1].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sync.events", () -> "true");
        registry.add("space-harvester.webhooks.secrets.demo", () -> SECRET);
        registry.add("space-harvester.cache.directory", () -> DATA.resolve("cache").toString());
        registry.add("space-harvester.export.directory", () -> DATA.resolve("exports").toString());
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
    @Order(1)
    void aSignedPageUpdateMarksTheCatalogWithoutLoadingContent() throws Exception {
        HttpResponse<String> started = http.send(TestClient.authenticated(HttpRequest.newBuilder(
                        url("/api/v1/sources/demo/spaces/DEMO/syncs?mode=full")))
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        await(() -> !get(started.headers().firstValue("Location").orElseThrow()).get("state").asString()
                .matches("queued|running"));
        assertThat(catalog().get("stalePages").asInt()).isZero();
        DemoConfluence.page(CONFLUENCE, "1003", "Betrieb", "DEMO", "<p>Betriebshandbuch, neu</p>")
                .version(4, "2026-10-18T11:00:00.000+02:00");
        int contentRequests = CONFLUENCE.requestCount("body.storage");
        assertThat(contentRequests).as("der Abgleich lädt Inhalte mit body.storage").isPositive();

        String body = """
                {"timestamp":1760778000000,"page":{"id":1003,"spaceKey":"DEMO","contentType":"page","version":4}}""";
        assertThat(webhook("demo", body, sign(body, SECRET), "page_updated").statusCode()).isEqualTo(202);

        await(() -> catalog().get("stalePages").asInt() == 1);
        assertThat(CONFLUENCE.requestCount("body.storage")).isEqualTo(contentRequests);
    }

    @Test
    @Order(2)
    void unsignedWronglySignedOrUnconfiguredCallsDoNothing() throws Exception {
        String body = "{\"page\":{\"id\":1002,\"spaceKey\":\"DEMO\",\"version\":9}}";
        int requests = CONFLUENCE.requests().size();

        assertThat(webhook("demo", body, null, "page_updated").statusCode()).isEqualTo(401);
        assertThat(webhook("demo", body, sign(body, "falsch"), "page_updated").statusCode()).isEqualTo(401);
        assertThat(webhook("ohne", body, sign(body, SECRET), "page_updated").statusCode()).isEqualTo(404);
        assertThat(webhook("unbekannt", body, sign(body, SECRET), "page_updated").statusCode()).isEqualTo(404);
        assertThat(webhook("demo", "kein json", sign("kein json", SECRET), "page_updated").statusCode())
                .isEqualTo(400);

        assertThat(CONFLUENCE.requests()).hasSize(requests);
    }

    private HttpResponse<String> webhook(String sourceId, String body, String signature, String event)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(url("/webhooks/v1/sources/" + sourceId + "/events"))
                .header("Content-Type", "application/json")
                .header("X-Event-Key", event)
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (signature != null) {
            request.header("X-Hub-Signature", signature);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sign(String body, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
    }

    private JsonNode catalog() throws Exception {
        return get("/api/v1/sources/demo/spaces/DEMO/catalog");
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = http.send(TestClient.authenticated(HttpRequest.newBuilder(url(path))).GET()
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private URI url(String path) {
        return URI.create(path.startsWith("http") ? path
                : "http://localhost:" + environment.getRequiredProperty("local.server.port") + path);
    }

    private interface Condition {
        boolean holds() throws Exception;
    }

    private static void await(Condition condition) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!condition.holds()) {
            assertThat(Instant.now()).as("Bedingung nicht erfüllt").isBefore(deadline);
            Thread.sleep(50);
        }
    }
}
