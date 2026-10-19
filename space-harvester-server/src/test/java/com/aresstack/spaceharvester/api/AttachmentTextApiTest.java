package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.ConfluenceHttpResponse;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
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
 * Text von Anhängen über echtes HTTP (#183): {@code GET …/assets/{attachmentId}/text} mit den Rechten wie beim Inhalt,
 * und Anhangstexte im Volltextindex der Schlagwortsuche als Anhangstreffer der Seite. Nicht lesbare Seiten bleiben
 * unsichtbar, auch über ihre Anhänge.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class AttachmentTextApiTest {

    private static final String READER_TOKEN = "text-reader-token-5d1e";
    private static final String METADATA_TOKEN = "text-metadata-token-a7c3";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final HttpServer SERVER;
    private static final Path STORE;
    private static final Path CACHE;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("SEC", "Sicherheit");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.page("101", "Planung", "DEV", "<p>P</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("102", "Betrieb", "DEV", "<p>B</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.page("103", "Tresorraum", "SEC", "<p>X</p>");
        CONFLUENCE.homepage("SEC", "103");
        CONFLUENCE.attachment("att7", "101", "notizen.txt", "text/plain", "Notizen zur Quartalsplanung");
        CONFLUENCE.attachment("att9", "102", "betrieb.txt", "text/plain", "Wartungsfenster jeden Samstag");
        CONFLUENCE.attachment("att8", "103", "geheim.txt", "text/plain", "Kombination Wartungsfenster Panzerschrank");
        try {
            SERVER = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            SERVER.createContext("/", AttachmentTextApiTest::serve);
            SERVER.start();
            STORE = Files.createTempDirectory("attachment-texts");
            // Unter build/, nicht gelöscht: der Spring-Kontext schließt seine Indizes erst am Ende der Testläufe.
            CACHE = Path.of("build", "attachment-text-api-test", java.util.UUID.randomUUID().toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static void serve(HttpExchange exchange) throws IOException {
        URI incoming = exchange.getRequestURI();
        String query = incoming.getRawQuery();
        URI mapped = URI.create(FakeConfluence.BASE.getScheme() + "://" + FakeConfluence.BASE.getHost()
                + incoming.getRawPath() + (query == null ? "" : "?" + query));
        Map<String, String> headers = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> header : exchange.getRequestHeaders().entrySet()) {
            if (!header.getValue().isEmpty()) {
                headers.put(header.getKey(), header.getValue().getFirst());
            }
        }
        ConfluenceHttpResponse response;
        synchronized (CONFLUENCE) {
            response = CONFLUENCE.get(mapped, headers, Integer.MAX_VALUE);
        }
        byte[] body = response.body();
        if (response.contentType() != null) {
            exchange.getResponseHeaders().add("Content-Type", response.contentType());
        }
        exchange.sendResponseHeaders(response.status(), body.length == 0 ? -1 : body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
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
        registry.add(reader + "id", () -> "nur-lesen-dev");
        registry.add(reader + "token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add(reader + "grants[0].source", () -> "wiki");
        registry.add(reader + "grants[0].spaces[0]", () -> "DEV");
        registry.add(reader + "grants[0].access", () -> "metadata,content");
        String metadata = "space-harvester.security.clients[2].";
        registry.add(metadata + "id", () -> "nur-metadaten");
        registry.add(metadata + "token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add(metadata + "grants[0].source", () -> "wiki");
        registry.add(metadata + "grants[0].spaces[0]", () -> "*");
        registry.add(metadata + "grants[0].access", () -> "metadata");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url",
                () -> "http://127.0.0.1:" + SERVER.getAddress().getPort() + FakeConfluence.BASE.getPath());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.assets.directory", STORE::toString);
        registry.add("space-harvester.cache.directory", () -> CACHE.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.stop(0);
        try (Stream<Path> files = Files.walk(STORE)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void theTextOfADocumentNeedsContentAndIsStoredOnlyWithSync() throws Exception {
        int before = downloads();
        HttpResponse<String> read = send("/api/v1/sources/wiki/pages/101/assets/att7/text", READER_TOKEN);

        assertThat(read.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(read.body());
        assertThat(body.get("attachmentId").asString()).isEqualTo("att7");
        assertThat(body.get("title").asString()).isEqualTo("notizen.txt");
        assertThat(body.get("text").asString()).contains("Quartalsplanung");
        assertThat(body.get("sha256").asString()).startsWith("sha256:");
        assertThat(body.get("truncated").asBoolean()).isFalse();
        // Ohne sync nicht abgelegt: ein zweiter Leser lädt erneut.
        send("/api/v1/sources/wiki/pages/101/assets/att7/text", READER_TOKEN);
        assertThat(downloads()).isEqualTo(before + 2);

        HttpResponse<String> stored = send("/api/v1/sources/wiki/pages/101/assets/att7/text", TestClient.TOKEN);
        HttpResponse<String> again = send("/api/v1/sources/wiki/pages/101/assets/att7/text", READER_TOKEN);

        assertThat(stored.statusCode()).isEqualTo(200);
        assertThat(again.statusCode()).isEqualTo(200);
        assertThat(json.readTree(again.body()).get("text").asString()).contains("Quartalsplanung");
        assertThat(downloads()).isEqualTo(before + 3);
    }

    @Test
    void textNeedsTheContentGrantAndHiddenPagesStayHidden() throws Exception {
        int before = downloads();
        HttpResponse<String> metadataOnly = send("/api/v1/sources/wiki/pages/101/assets/att7/text", METADATA_TOKEN);
        HttpResponse<String> hidden = send("/api/v1/sources/wiki/pages/103/assets/att8/text", READER_TOKEN);
        HttpResponse<String> foreign = send("/api/v1/sources/wiki/pages/101/assets/att8/text", TestClient.TOKEN);
        HttpResponse<String> invalid = send("/api/v1/sources/wiki/pages/101/assets/..%2Fatt7/text", TestClient.TOKEN);

        assertThat(metadataOnly.statusCode()).isIn(403, 404);
        assertThat(metadataOnly.body()).doesNotContain("Quartalsplanung");
        assertThat(hidden.statusCode()).isEqualTo(404);
        assertThat(hidden.body()).doesNotContain("Panzerschrank").doesNotContain("geheim");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(invalid.statusCode()).isIn(400, 404);
        assertThat(downloads()).isEqualTo(before);
    }

    @Test
    void anAttachmentHitNamesItsPageAndHiddenPagesNeverAppear() throws Exception {
        assertThat(send("/api/v1/sources/wiki/pages/102/assets/harvest", TestClient.TOKEN, true).statusCode())
                .isEqualTo(200);
        assertThat(send("/api/v1/sources/wiki/pages/103/assets/harvest", TestClient.TOKEN, true).statusCode())
                .isEqualTo(200);

        JsonNode all = json.readTree(send("/api/v1/search?text=Wartungsfenster", TestClient.TOKEN).body());
        HttpResponse<String> limited = send("/api/v1/search?text=Wartungsfenster", READER_TOKEN);
        HttpResponse<String> secret = send("/api/v1/search?text=Panzerschrank", READER_TOKEN);

        assertThat(all.get("hits").findValuesAsString("pageId")).containsExactlyInAnyOrder("102", "103");
        JsonNode reader = json.readTree(limited.body());
        assertThat(reader.get("hits")).hasSize(1);
        JsonNode hit = reader.get("hits").get(0);
        assertThat(hit.get("pageId").asString()).isEqualTo("102");
        assertThat(hit.get("title").asString()).isEqualTo("Betrieb");
        assertThat(hit.get("matchedIn").asString()).isEqualTo("attachment");
        assertThat(hit.get("attachment").get("attachmentId").asString()).isEqualTo("att9");
        assertThat(hit.get("attachment").get("title").asString()).isEqualTo("betrieb.txt");
        assertThat(hit.get("snippet").asString()).contains("Wartungsfenster");
        // Kennungen wie searchRun sind zufällig und können „103“ enthalten; die Seite prüft daher jedes pageId-Feld.
        assertThat(reader.findValuesAsString("pageId")).doesNotContain("103");
        assertThat(limited.body()).doesNotContain("att8").doesNotContain("geheim").doesNotContain("Tresorraum")
                .doesNotContain("Panzerschrank");
        JsonNode secretHits = json.readTree(secret.body());
        assertThat(secretHits.get("hits")).isEmpty();
        assertThat(secretHits.findValuesAsString("pageId")).doesNotContain("103");
        assertThat(secret.body()).doesNotContain("att8").doesNotContain("geheim");
    }

    private int downloads() {
        synchronized (CONFLUENCE) {
            return CONFLUENCE.requestCount("/download/");
        }
    }

    private HttpResponse<String> send(String path, String token) throws Exception {
        return send(path, token, false);
    }

    private HttpResponse<String> send(String path, String token, boolean post) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token);
        return http.send((post ? request.POST(HttpRequest.BodyPublishers.noBody()) : request.GET()).build(),
                HttpResponse.BodyHandlers.ofString());
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
