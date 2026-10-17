package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Asset-Ernte über echtes HTTP gegen die Fake-Confluence (#19): Anhänge getrennt vom Seiten-JSON laden, je Version
 * einmal übertragen, draw.io vor Vorschaubild, Inhalte nur als Download und nur mit Freigabe.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(AssetsApiTest.Describer.class)
class AssetsApiTest {

    /** Ein Bildbeschreiber ohne Modell: nennt nur die Größe. */
    @TestConfiguration
    static class Describer {

        @Bean
        ImageDescriber imageDescriber() {
            return new ImageDescriber() {
                @Override
                public String id() {
                    return "test";
                }

                @Override
                public String modelVersion() {
                    return "groesse-1";
                }

                @Override
                public String describe(byte[] image, String mediaType) {
                    return mediaType + " mit " + image.length + " Bytes";
                }
            };
        }
    }

    private static final String READER_TOKEN = "asset-reader-token-77a1";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final Path STORE;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.page("101", "Diagramme", "DEV", "<p>D</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("102", "Groß", "DEV", "<p>G</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.page("103", "Gesperrt", "DEV", "<p>X</p>");
        CONFLUENCE.child("100", "103");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.attachment("att1", "101", "ablauf", "application/vnd.jgraph.mxfile", "<mxfile>ablauf</mxfile>");
        CONFLUENCE.attachment("att2", "101", "ablauf.png", "image/png", "PNG-Vorschau");
        CONFLUENCE.attachment("att3", "101", "logo.svg", "image/svg+xml", "<svg><script>x</script></svg>");
        CONFLUENCE.attachment("att4", "101", "foto.jpg", "image/jpeg", "JPEG");
        CONFLUENCE.attachment("att5", "102", "riesig.pdf", "application/pdf", "x".repeat(300));
        CONFLUENCE.attachment("att6", "103", "geheim.txt", "text/plain", "geheim");
        CONFLUENCE.restrict("103");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
            STORE = Files.createTempDirectory("assets");
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
        registry.add(reader + "id", () -> "nur-metadaten");
        registry.add(reader + "token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add(reader + "grants[0].source", () -> "wiki");
        registry.add(reader + "grants[0].spaces[0]", () -> "*");
        registry.add(reader + "grants[0].access", () -> "metadata");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.assets.directory", STORE::toString);
        registry.add("space-harvester.assets.max-size", () -> "200B");
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void overviewNamesKindsAndDiagramPreviewsWithoutDownloading() throws Exception {
        int before = CONFLUENCE.requestCount("/download/");

        HttpResponse<String> response = send("GET", "/api/v1/sources/wiki/pages/101/assets", TestClient.TOKEN);

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode assets = json.readTree(response.body()).get("assets");
        assertThat(assets).hasSize(4);
        assertThat(assets.get(0).get("kind").asString()).isEqualTo("DIAGRAM");
        assertThat(assets.get(1).get("kind").asString()).isEqualTo("IMAGE");
        assertThat(assets.get(1).get("previewOf").asString()).isEqualTo("att1");
        assertThat(assets.get(2).get("kind").asString()).isEqualTo("VECTOR");
        assertThat(assets.get(3).get("previewOf").isNull()).isTrue();
        assertThat(assets.get(0).get("contentUrl").asString()).isEqualTo("assets/att1/content");
        assertThat(CONFLUENCE.requestCount("/download/")).isEqualTo(before);
    }

    @Test
    void harvestStoresEachVersionOnceAndReportsTooLargeFiles() throws Exception {
        JsonNode first = json.readTree(send("POST", "/api/v1/sources/wiki/pages/101/assets/harvest",
                TestClient.TOKEN).body());
        int downloads = CONFLUENCE.requestCount("/download/");
        JsonNode second = json.readTree(send("POST", "/api/v1/sources/wiki/pages/101/assets/harvest",
                TestClient.TOKEN).body());

        assertThat(first.get("complete").asBoolean()).isTrue();
        assertThat(first.get("stored").asLong() + first.get("reused").asLong()).isEqualTo(4);
        assertThat(second.get("reused").asLong()).isEqualTo(4);
        assertThat(CONFLUENCE.requestCount("/download/")).isEqualTo(downloads);
        JsonNode overview = json.readTree(send("GET", "/api/v1/sources/wiki/pages/101/assets",
                TestClient.TOKEN).body()).get("assets");
        assertThat(overview.get(0).get("stored").get("sha256").asString()).startsWith("sha256:");
        assertThat(overview.get(0).get("stored").get("version").asString()).isEqualTo("1");

        JsonNode large = json.readTree(send("POST", "/api/v1/sources/wiki/pages/102/assets/harvest",
                TestClient.TOKEN).body());
        assertThat(large.get("complete").asBoolean()).isFalse();
        assertThat(large.get("assets").get(0).get("status").asString()).isEqualTo("FAILED");
        assertThat(large.get("assets").get(0).get("code").asString()).isEqualTo("asset-too-large");
        assertThat(CONFLUENCE.requestCount("/download/attachments/102/")).isZero();
    }

    @Test
    void contentIsTheOriginalAsDownloadWithEtag() throws Exception {
        HttpResponse<String> svg = send("GET", "/api/v1/sources/wiki/pages/101/assets/att3/content",
                TestClient.TOKEN);

        assertThat(svg.statusCode()).isEqualTo(200);
        assertThat(svg.body()).isEqualTo("<svg><script>x</script></svg>");
        assertThat(svg.headers().firstValue("Content-Type")).hasValueSatisfying(v -> assertThat(v)
                .startsWith("image/svg+xml"));
        assertThat(svg.headers().firstValue("Content-Disposition")).hasValueSatisfying(v -> assertThat(v)
                .startsWith("attachment").contains("logo.svg"));
        assertThat(svg.headers().firstValue("Content-Security-Policy")).hasValue("sandbox; default-src 'none'");
        assertThat(svg.headers().firstValue("X-Content-Type-Options")).hasValue("nosniff");
        String etag = svg.headers().firstValue("ETag").orElseThrow();
        assertThat(etag).startsWith("\"sha256:");

        int downloads = CONFLUENCE.requestCount("/download/");
        HttpResponse<String> again = http.send(request("GET", "/api/v1/sources/wiki/pages/101/assets/att3/content",
                TestClient.TOKEN).header("If-None-Match", etag).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
        assertThat(CONFLUENCE.requestCount("/download/")).isEqualTo(downloads);
    }

    @Test
    void onlyAttachmentsOfTheVisiblePageAreServed() throws Exception {
        assertThat(send("GET", "/api/v1/sources/wiki/pages/101/assets/att5/content", TestClient.TOKEN)
                .statusCode()).isEqualTo(404);
        HttpResponse<String> restricted = send("GET", "/api/v1/sources/wiki/pages/103/assets/att6/content",
                TestClient.TOKEN);
        assertThat(restricted.statusCode()).isEqualTo(404);
        assertThat(restricted.body()).doesNotContain("geheim");
        HttpResponse<String> large = send("GET", "/api/v1/sources/wiki/pages/102/assets/att5/content",
                TestClient.TOKEN);
        assertThat(large.statusCode()).isEqualTo(502);
        assertThat(large.body()).contains("problems/asset-too-large").doesNotContain("xxxx");
    }

    @Test
    void metadataGrantSeesTheListButNeitherContentNorHarvest() throws Exception {
        assertThat(send("GET", "/api/v1/sources/wiki/pages/101/assets", READER_TOKEN).statusCode()).isEqualTo(200);

        HttpResponse<String> content = send("GET", "/api/v1/sources/wiki/pages/101/assets/att4/content",
                READER_TOKEN);
        HttpResponse<String> harvest = send("POST", "/api/v1/sources/wiki/pages/101/assets/harvest", READER_TOKEN);

        assertThat(content.statusCode()).isIn(403, 404);
        assertThat(content.body()).doesNotContain("JPEG");
        assertThat(harvest.statusCode()).isIn(403, 404);
    }

    @Test
    void imagesAreDescribedOnceAndMarkedAsMachineGenerated() throws Exception {
        assertThat(send("GET", "/api/v1/sources/wiki/pages/101/assets/att2/description", TestClient.TOKEN)
                .statusCode()).isEqualTo(404);

        JsonNode first = json.readTree(send("POST", "/api/v1/sources/wiki/pages/101/assets/att2/description",
                TestClient.TOKEN).body());
        JsonNode again = json.readTree(send("POST", "/api/v1/sources/wiki/pages/101/assets/att2/description",
                TestClient.TOKEN).body());
        HttpResponse<String> read = send("GET", "/api/v1/sources/wiki/pages/101/assets/att2/description",
                TestClient.TOKEN);

        assertThat(first.get("generated").asBoolean()).isTrue();
        assertThat(first.get("provenance").asString()).isEqualTo("machine-generated");
        assertThat(first.get("modelVersion").asString()).isEqualTo("groesse-1");
        assertThat(first.get("text").asString()).isEqualTo("image/png mit 12 Bytes");
        assertThat(again.get("generated").asBoolean()).isFalse();
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(json.readTree(read.body()).get("imageSha256").asString())
                .isEqualTo(first.get("imageSha256").asString());
        HttpResponse<String> diagram = send("POST", "/api/v1/sources/wiki/pages/101/assets/att1/description",
                TestClient.TOKEN);
        assertThat(diagram.statusCode()).isEqualTo(400);
        assertThat(diagram.body()).contains("problems/asset-not-an-image");
        assertThat(send("POST", "/api/v1/sources/wiki/pages/101/assets/att2/description", READER_TOKEN)
                .statusCode()).isIn(403, 404);
        assertThat(send("GET", "/api/v1/sources/wiki/pages/101/assets/att2/description", READER_TOKEN)
                .statusCode()).isIn(403, 404);
    }

    private HttpResponse<String> send(String method, String path, String token) throws Exception {
        return http.send(request(method, path, token).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String method, String path, String token) {
        String port = environment.getRequiredProperty("local.server.port");
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token)
                .method(method, HttpRequest.BodyPublishers.noBody());
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
