package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
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

/** Export starten, verfolgen, abbrechen und herunterladen über echtes HTTP, gegen den {@link InMemoryPageCache}. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ExportsApiTest {

    private static final String OTHER_TOKEN = "other-client-token-51b0";
    private static final String NO_CONTENT_TOKEN = "no-content-token-9e2d";
    /** Pretty-printed und mit Nicht-ASCII: die Rohdatei muss Byte für Byte gleich bleiben. */
    private static final byte[] PRETTY = "{\n  \"id\" : \"101\",\n  \"title\" : \"Grüße\"\n}\n"
            .getBytes(StandardCharsets.UTF_8);
    private static final Path EXPORTS;

    static {
        try {
            EXPORTS = Files.createTempDirectory("exports-api-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        registry.add("space-harvester.export.directory", EXPORTS::toString);
        TestClient.register(registry, "wiki");
        client(registry, 1, "other", OTHER_TOKEN, "metadata,content,export");
        client(registry, 2, "no-content", NO_CONTENT_TOKEN, "metadata,export");
    }

    private static void client(DynamicPropertyRegistry registry, int index, String id, String token, String access) {
        String prefix = "space-harvester.security.clients[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "token-sha256", () -> "sha256:" + sha256(token.getBytes(StandardCharsets.UTF_8)));
        registry.add(prefix + "grants[0].source", () -> "wiki");
        registry.add(prefix + "grants[0].spaces[0]", () -> "*");
        registry.add(prefix + "grants[0].access", () -> access);
    }

    @TestConfiguration
    static class Cache {

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

    @BeforeEach
    void fillCache() {
        cache.clear();
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(page("wiki", "DEV", "100", null),
                page("wiki", "DEV", "101", "100", PRETTY), page("wiki", "DEV", "200", null)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private HttpResponse<byte[]> send(String method, String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        request = token == null ? TestClient.authenticated(request)
                : request.header("Authorization", "Bearer " + token);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private JsonNode body(HttpResponse<byte[]> response) {
        return json.readTree(response.body());
    }

    private JsonNode awaitFinished(String exportId) throws Exception {
        for (int i = 0; i < 200; i++) {
            JsonNode status = body(send("GET", "/api/v1/exports/" + exportId, null));
            if (List.of("completed", "failed", "cancelled").contains(status.path("state").asString())) {
                return status;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("Export nicht beendet");
    }

    @Test
    void startsTracksAndDownloadsACompleteExport() throws Exception {
        HttpResponse<byte[]> started = send("POST", "/api/v1/sources/wiki/spaces/DEV/exports", null);

        assertThat(started.statusCode()).isEqualTo(202);
        String id = body(started).path("id").asString();
        assertThat(started.headers().firstValue("Location")).contains("/api/v1/exports/" + id);
        assertThat(body(started).has("owner")).isFalse();

        JsonNode done = awaitFinished(id);
        assertThat(done.path("state").asString()).isEqualTo("completed");
        assertThat(done.path("progress").path("written").asLong()).isEqualTo(3);
        assertThat(done.path("artifact").path("complete").asBoolean()).isTrue();
        // Ohne format das Standardformat JSON neben dem Raw-First-Artefakt.
        assertThat(done.path("artifact").path("files")).extracting(file -> file.path("name").asString())
                .containsExactly("pages.ndjson", "tree.json", "pages.json", "manifest.json");

        HttpResponse<byte[]> pages = send("GET", "/api/v1/exports/" + id + "/artifact/pages.ndjson", null);
        assertThat(pages.statusCode()).isEqualTo(200);
        assertThat(pages.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/x-ndjson"));
        assertThat(sha256(pages.body()))
                .isEqualTo(done.path("artifact").path("files").get(0).path("sha256").asString()
                        .substring("sha256:".length()));
        String[] lines = new String(pages.body(), StandardCharsets.UTF_8).split("\n");
        assertThat(lines).hasSize(3);
        JsonNode second = json.readTree(lines[1]);
        String rawFile = second.path("raw").path("file").asString();

        HttpResponse<byte[]> raw = send("GET", "/api/v1/exports/" + id + "/artifact/" + rawFile, null);
        assertThat(raw.statusCode()).isEqualTo(200);
        assertThat(raw.body()).isEqualTo(PRETTY);

        JsonNode tree = body(send("GET", "/api/v1/exports/" + id + "/artifact/tree.json", null));
        assertThat(tree.path("roots")).extracting(node -> node.path("id").asString()).containsExactly("100", "200");
    }

    @Test
    void requestedFormatIsDownloadableNextToTheRawFirstArtifact() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports?format=json", null)).path("id")
                .asString();

        JsonNode done = awaitFinished(id);

        assertThat(done.path("artifact").path("files")).extracting(file -> file.path("name").asString())
                .containsExactly("pages.ndjson", "tree.json", "pages.json", "manifest.json");
        HttpResponse<byte[]> pages = send("GET", "/api/v1/exports/" + id + "/artifact/pages.json", null);
        assertThat(pages.statusCode()).isEqualTo(200);
        assertThat(pages.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(pages.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment"));
        assertThat(pages.headers().firstValue("Content-Security-Policy")).hasValueSatisfying(
                value -> assertThat(value).contains("sandbox"));
        assertThat(pages.headers().firstValue("X-Content-Type-Options")).contains("nosniff");
        assertThat(body(pages)).extracting(page -> page.path("header").path("pageId").asString())
                .containsExactly("100", "101", "200");
        assertThat(body(pages).get(1).path("content")).isEqualTo(json.readTree(PRETTY));
        // Andere Clients bekommen auch die konvertierte Datei nicht.
        assertThat(send("GET", "/api/v1/exports/" + id + "/artifact/pages.json", OTHER_TOKEN).statusCode())
                .isEqualTo(404);
    }

    @Test
    void markdownIsServedAsText() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports?format=markdown&format=json", null))
                .path("id").asString();

        JsonNode done = awaitFinished(id);

        assertThat(done.path("artifact").path("files")).extracting(file -> file.path("name").asString())
                .containsExactly("pages.ndjson", "tree.json", "pages.md", "pages.json", "manifest.json");
        HttpResponse<byte[]> markdown = send("GET", "/api/v1/exports/" + id + "/artifact/pages.md", null);
        assertThat(markdown.statusCode()).isEqualTo(200);
        assertThat(markdown.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/markdown"));
        assertThat(new String(markdown.body(), StandardCharsets.UTF_8)).contains("<a id=\"page-101\"></a>");
    }

    @Test
    void wordAndPdfAreServedWithTheirMediaTypes() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports?format=docx&format=pdf", null))
                .path("id").asString();

        assertThat(awaitFinished(id).path("state").asString()).isEqualTo("completed");
        HttpResponse<byte[]> docx = send("GET", "/api/v1/exports/" + id + "/artifact/pages.docx", null);
        assertThat(docx.headers().firstValue("Content-Type")).contains(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        assertThat(docx.body()).startsWith((byte) 'P', (byte) 'K');
        HttpResponse<byte[]> pdf = send("GET", "/api/v1/exports/" + id + "/artifact/pages.pdf", null);
        assertThat(pdf.headers().firstValue("Content-Type")).contains("application/pdf");
        assertThat(new String(pdf.body(), 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
    }

    @Test
    void aFinishedJsonExportIsConvertedLaterWithoutANewExport() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports", null)).path("id").asString();
        awaitFinished(id);

        JsonNode dataset = body(send("GET", "/api/v1/exports/" + id + "/dataset", null));
        assertThat(dataset.path("pages").asInt()).isEqualTo(3);
        assertThat(dataset.path("spaceKey").asString()).isEqualTo("DEV");
        // Die Bilder beschafft ein eigener Auftrag nach dem Export.
        assertThat(dataset.path("images").asString()).isIn("COLLECTING", "COMPLETE", "INCOMPLETE");
        HttpResponse<byte[]> markdown = send("GET", "/api/v1/exports/" + id + "/conversions/markdown", null);
        assertThat(markdown.statusCode()).isEqualTo(200);
        assertThat(markdown.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/markdown"));
        assertThat(markdown.headers().firstValue("Content-Disposition")).hasValueSatisfying(
                value -> assertThat(value).startsWith("attachment").contains(".md"));
        assertThat(new String(markdown.body(), StandardCharsets.UTF_8)).contains("<a id=\"page-101\"></a>");
        HttpResponse<byte[]> docx = send("GET", "/api/v1/exports/" + id + "/conversions/docx", null);
        assertThat(docx.body()).startsWith((byte) 'P', (byte) 'K');
        // Unbekanntes Format, fremder Client: wie beim Export.
        assertThat(send("GET", "/api/v1/exports/" + id + "/conversions/pptx", null).statusCode()).isEqualTo(400);
        assertThat(send("GET", "/api/v1/exports/" + id + "/conversions/markdown", OTHER_TOKEN).statusCode())
                .isEqualTo(404);
        assertThat(send("GET", "/api/v1/exports/" + id + "/dataset", OTHER_TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void unknownFormatIsRejected() throws Exception {
        HttpResponse<byte[]> response = send("POST", "/api/v1/sources/wiki/spaces/DEV/exports?format=pptx", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(body(response).path("type").asString()).endsWith("/invalid-request");
    }

    @Test
    void foreignExportIsNotFoundForAnotherClient() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports", null)).path("id").asString();
        awaitFinished(id);

        JsonNode unknown = body(send("GET", "/api/v1/exports/does-not-exist", null));
        for (String path : List.of("/api/v1/exports/" + id, "/api/v1/exports/" + id + "/artifact/pages.ndjson")) {
            HttpResponse<byte[]> response = send("GET", path, OTHER_TOKEN);
            assertThat(response.statusCode()).isEqualTo(404);
            // Nicht unterscheidbar von einer unbekannten ID: kein Hinweis, dass es den Export gibt.
            assertThat(body(response).path("type")).isEqualTo(unknown.path("type"));
            assertThat(body(response).path("detail")).isEqualTo(unknown.path("detail"));
        }
        assertThat(unknown.path("type").asString()).endsWith("/job-not-found");
        assertThat(send("POST", "/api/v1/exports/" + id + "/cancel", OTHER_TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void exportNeedsContentAccess() throws Exception {
        HttpResponse<byte[]> response = send("POST", "/api/v1/sources/wiki/spaces/DEV/exports", NO_CONTENT_TOKEN);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(body(response).path("type").asString()).endsWith("/access-denied");
    }

    @Test
    void anonymousCallsAreRejected() throws Exception {
        HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(
                        uri("/api/v1/sources/wiki/spaces/DEV/exports")).POST(HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    @Test
    void uncachedSpaceThatCannotBeReadInFailsWithReason() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/NONE/exports", null)).path("id").asString();

        JsonNode done = awaitFinished(id);

        assertThat(done.path("state").asString()).isEqualTo("failed");
        assertThat(done.path("error").path("code").asString()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(done.path("artifact").isNull() || done.path("artifact").isMissingNode()).isTrue();
        assertThat(send("GET", "/api/v1/exports/" + id + "/artifact/pages.ndjson", null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void eventsExplainAFailedExportOnlyToItsOwner() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/NONE/exports", null)).path("id").asString();
        awaitFinished(id);

        HttpResponse<byte[]> events = send("GET", "/api/v1/exports/" + id + "/events", null);

        assertThat(events.statusCode()).isEqualTo(200);
        JsonNode last = body(events).get(body(events).size() - 1);
        assertThat(last.path("severity").asString()).isEqualTo("error");
        assertThat(last.path("code").asString()).isEqualTo("PROVISIONING_UNAVAILABLE");
        assertThat(last.path("phase").asString()).isEqualTo("Bestand prüfen");
        assertThat(last.path("component").asString()).isEqualTo("Exports");
        assertThat(last.path("detail").asString()).contains("SyncUnavailableException");
        assertThat(body(events)).extracting(event -> event.path("message").asString())
                .startsWith("Auftrag angenommen", "Auftrag gestartet");
        assertThat(send("GET", "/api/v1/exports/" + id + "/events", OTHER_TOKEN).statusCode()).isEqualTo(404);
    }

    @Test
    void theOwnerDeletesEventsAndThenTheExport() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/NONE/exports", null)).path("id").asString();
        awaitFinished(id);
        JsonNode before = body(send("GET", "/api/v1/exports/" + id + "/events", null));
        long first = before.get(0).path("sequence").asLong();

        assertThat(send("DELETE", "/api/v1/exports/" + id + "/events/" + first, OTHER_TOKEN).statusCode())
                .isEqualTo(404);
        assertThat(send("DELETE", "/api/v1/exports/" + id, OTHER_TOKEN).statusCode()).isEqualTo(404);
        assertThat(send("DELETE", "/api/v1/exports/" + id + "/events/" + first, null).statusCode()).isEqualTo(204);
        assertThat(send("DELETE", "/api/v1/exports/" + id + "/events/" + first, null).statusCode()).isEqualTo(404);
        JsonNode after = body(send("GET", "/api/v1/exports/" + id + "/events", null));
        assertThat(after).extracting(event -> event.path("sequence").asLong()).doesNotContain(first)
                .hasSize(before.size() - 1);

        HttpResponse<byte[]> purged = send("DELETE", "/api/v1/exports/" + id + "/events", null);
        assertThat(purged.statusCode()).isEqualTo(200);
        assertThat(body(purged).path("deleted").asInt()).isEqualTo(before.size() - 1);

        assertThat(send("DELETE", "/api/v1/exports/" + id, null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/v1/exports/" + id, null).statusCode()).isEqualTo(404);
    }

    @Test
    void deletingAllFinishedExportsKeepsThoseOfOtherClients() throws Exception {
        String mine = body(send("POST", "/api/v1/sources/wiki/spaces/NONE/exports", null)).path("id").asString();
        awaitFinished(mine);

        HttpResponse<byte[]> deleted = send("DELETE", "/api/v1/exports", OTHER_TOKEN);

        assertThat(deleted.statusCode()).isEqualTo(200);
        assertThat(send("GET", "/api/v1/exports/" + mine, null).statusCode()).isEqualTo(200);
        assertThat(body(send("DELETE", "/api/v1/exports", null)).path("deleted").asInt()).isPositive();
        assertThat(send("GET", "/api/v1/exports/" + mine, null).statusCode()).isEqualTo(404);
    }

    @Test
    void artifactPathsCannotLeaveTheExport() throws Exception {
        String id = body(send("POST", "/api/v1/sources/wiki/spaces/DEV/exports", null)).path("id").asString();
        awaitFinished(id);

        for (String name : List.of("../" + id + "/manifest.json", "raw/../manifest.json", "raw/%2e%2e/x",
                "other.txt")) {
            HttpResponse<byte[]> response = send("GET", "/api/v1/exports/" + id + "/artifact/" + name, null);
            assertThat(response.statusCode()).as(name).isIn(400, 404);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
