package com.aresstack.spaceharvester.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 * Ende-zu-Ende über die öffentliche API gegen die Demo-Fake-Confluence, mit eingebettetem Lucene-Cache und
 * Exportverzeichnis in temporären Verzeichnissen (#15): Spaces listen → Metadaten sammeln → Space ernten → Liste, Baum
 * und native Seite aus dem Cache → erneut ernten (nur Änderungen) → Export starten, Auftrag abfragen, Artefakt prüfen →
 * ein Lauf mit nicht ladbarer Seite ergibt einen als unvollständig gekennzeichneten Export.
 *
 * <p>Die Schritte bauen aufeinander auf und laufen deshalb in fester Reihenfolge gegen dieselbe Anwendung.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class HarvestEndToEndTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final FakeConfluence CONFLUENCE = DemoConfluence.fixture();
    private static final DemoConfluence SERVER;
    private static final Path DATA;

    static {
        try {
            SERVER = DemoConfluence.start(CONFLUENCE);
            DATA = Files.createTempDirectory("harvest-e2e");
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "demo");
        registry.add("space-harvester.security.clients[0].grants[0].access", () -> "metadata,content,export,sync");
        registry.add("space-harvester.sources[0].id", () -> "demo");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.cache.directory", () -> DATA.resolve("cache").toString());
        registry.add("space-harvester.export.directory", () -> DATA.resolve("exports").toString());
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    /** Metadaten-Hashes aus der Live-Liste, gegen die der Cache geprüft wird. */
    private static final Map<String, String> LIVE_HASHES = new LinkedHashMap<>();

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    @Order(1)
    void listsSpacesAndCollectsLiveMetadata() throws Exception {
        assertThat(get("/api/v1/sources/demo/spaces").get("spaces")).extracting(space -> space.get("key").asString())
                .containsExactlyInAnyOrder("DEMO", "OPS");
        for (JsonNode page : allPages("/api/v1/sources/demo/spaces/DEMO/pages/metadata")) {
            LIVE_HASHES.put(page.get("pageId").asString(), page.get("metadataHash").asString());
        }
        assertThat(LIVE_HASHES.keySet()).containsExactlyInAnyOrder("1000", "1001", "1002", "1003", "2000", "2001");
    }

    @Test
    @Order(2)
    void harvestsTheSpaceIntoTheCache() throws Exception {
        JsonNode summary = sync().get("summary");
        assertThat(summary.get("inventory").asString()).isEqualTo("complete");
        assertThat(summary.get("added").asInt()).isEqualTo(6);
        assertThat(summary.get("complete").asBoolean()).isTrue();
        assertThat(summary.get("spaceFingerprint").asString()).matches("sha256:[0-9a-f]{64}");
    }

    @Test
    @Order(3)
    void servesPagesTreeAndByteExactNativePayloadFromTheCache() throws Exception {
        List<JsonNode> pages = allPages("/api/v1/sources/demo/spaces/DEMO/pages");
        Map<String, String> cached = new LinkedHashMap<>();
        for (JsonNode page : pages) {
            cached.put(page.get("pageId").asString(), page.get("metadataHash").asString());
        }
        assertThat(cached).isEqualTo(LIVE_HASHES);

        JsonNode tree = get("/api/v1/sources/demo/spaces/DEMO/tree");
        assertThat(tree.get("cache").get("state").asString()).isEqualTo("confirmed");
        assertThat(tree.get("nodeCount").asInt()).isEqualTo(6);
        assertThat(tree.get("roots")).extracting(root -> root.get("id").asString()).containsExactly("1000", "2000");
        assertThat(tree.get("anomalies")).isEmpty();

        // Byte-Genauigkeit: dieselben Bytes wie der HTTP-Body der Quelle, ETag = rawSha256 = sha256 dieser Bytes.
        HttpResponse<byte[]> raw = send("GET", "/api/v1/sources/demo/pages/1001", HttpResponse.BodyHandlers.ofByteArray());
        assertThat(raw.statusCode()).isEqualTo(200);
        String format = raw.headers().firstValue("X-Native-Format").orElseThrow();
        String expand = format.substring(format.indexOf("expand=") + "expand=".length());
        byte[] source = http.send(HttpRequest.newBuilder(URI.create(SERVER.baseUrl() + "/rest/api/content/1001?expand="
                + URLEncoder.encode(expand, StandardCharsets.UTF_8))).build(),
                HttpResponse.BodyHandlers.ofByteArray()).body();
        assertThat(raw.body()).isEqualTo(source);
        // Durchgereicht wird das interne Storage-Format; Bild und Diagramm bleiben Verweise auf Anhänge.
        JsonNode storage = json.readTree(raw.body()).get("body").get("storage");
        assertThat(storage.get("representation").asString()).isEqualTo("storage");
        assertThat(storage.get("value").asString())
                .contains("<ri:attachment ri:filename=\"diagramm.png\" />")
                .contains("<ac:structured-macro ac:name=\"drawio\">")
                .doesNotContain("<img", "data:image", "mxGraphModel");
        assertThat(json.readTree(raw.body()).get("body").has("view")).isFalse();
        String sha = "sha256:" + sha256(raw.body());
        assertThat(raw.headers().firstValue("ETag")).hasValue("\"" + sha + "\"");
        JsonNode listed = pages.stream().filter(page -> page.get("pageId").asString().equals("1001")).findFirst()
                .orElseThrow();
        assertThat(listed.get("rawSha256").asString()).isEqualTo(sha);
    }

    @Test
    @Order(4)
    void secondHarvestLoadsOnlyChangedPages() throws Exception {
        DemoConfluence.page(CONFLUENCE, "1003", "Betrieb", "DEMO", "<p>Betriebshandbuch, überarbeitet</p>")
                .version(4, "2026-10-15T09:00:00.000+02:00");
        CONFLUENCE.child("1000", "1003");
        DemoConfluence.page(CONFLUENCE, "1005", "Neue Seite", "DEMO", "<p>Neu</p>");
        CONFLUENCE.child("1000", "1005");
        CONFLUENCE.move("2001", "OPS");
        int before = CONFLUENCE.requests().size();

        JsonNode summary = sync().get("summary");
        assertThat(summary.get("added").asInt()).isEqualTo(1);
        assertThat(summary.get("contentChanged").asInt()).isEqualTo(1);
        assertThat(summary.get("movedOut").asInt()).isEqualTo(1);
        assertThat(summary.get("unchanged").asInt()).isEqualTo(4);
        assertThat(summary.get("complete").asBoolean()).isTrue();

        // Inhalte nur für die neue und die geänderte Seite nachgeladen.
        Set<String> fetched = new HashSet<>();
        for (URI request : CONFLUENCE.requests().subList(before, CONFLUENCE.requests().size())) {
            String path = request.getPath();
            if (path.matches(".*/rest/api/content/\\d+") && request.getQuery() != null
                    && request.getQuery().contains("body.storage")) {
                fetched.add(path.substring(path.lastIndexOf('/') + 1));
            }
        }
        assertThat(fetched).containsExactlyInAnyOrder("1003", "1005");

        JsonNode tree = get("/api/v1/sources/demo/spaces/DEMO/tree");
        assertThat(tree.get("nodeCount").asInt()).isEqualTo(6);
        assertThat(tree.toString()).contains("\"1005\"").doesNotContain("\"2001\"");
    }

    @Test
    @Order(5)
    void exportsTheConfirmedGenerationCompletely() throws Exception {
        JsonNode export = export();
        assertThat(export.get("state").asString()).isEqualTo("completed");
        JsonNode artifact = export.get("artifact");
        assertThat(artifact.get("complete").asBoolean()).isTrue();

        String base = "/api/v1/exports/" + export.get("id").asString() + "/artifact/";
        JsonNode manifest = json.readTree(download(base + "manifest.json"));
        assertThat(manifest.get("complete").asBoolean()).isTrue();

        // Jede Datei mit Größe und Hash aus dem Zustand stimmt mit dem heruntergeladenen Inhalt überein.
        for (JsonNode file : artifact.get("files")) {
            if (file.get("sha256").isNull()) {
                continue;
            }
            byte[] content = download(base + file.get("name").asString());
            assertThat("sha256:" + sha256(content)).as(file.get("name").asString())
                    .isEqualTo(file.get("sha256").asString());
        }

        List<JsonNode> lines = new ArrayList<>();
        for (String line : new String(download(base + "pages.ndjson"), StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(json.readTree(line));
            }
        }
        assertThat(lines).hasSize(6);
        JsonNode tree = json.readTree(download(base + "tree.json"));
        assertThat(tree.toString()).contains("\"1005\"").doesNotContain("\"2001\"");
        assertThat(lines.toString()).contains("\"1005\"");
    }

    @Test
    @Order(6)
    void pageWithoutLoadableContentYieldsAnIncompleteExport() throws Exception {
        // Gelistet, aber der Inhaltsabruf liefert kein gültiges JSON: die Seite bleibt ohne Inhalt.
        DemoConfluence.page(CONFLUENCE, "1006", "Defekt", "DEMO", "<p>Nicht ladbar</p>").rawJson("{kein JSON");
        CONFLUENCE.child("1000", "1006");

        JsonNode sync = syncUntilFinished();
        assertThat(sync.get("summary").get("complete").asBoolean()).isFalse();
        assertThat(get("/api/v1/sources/demo/spaces/DEMO/tree").get("cache").get("state").asString())
                .isEqualTo("incomplete");

        JsonNode export = export();
        assertThat(export.get("artifact").get("complete").asBoolean())
                .as("Export nach unvollständiger Ernte darf nicht vollständig heißen: %s", export).isFalse();
        JsonNode manifest = json.readTree(download("/api/v1/exports/" + export.get("id").asString()
                + "/artifact/manifest.json"));
        assertThat(manifest.get("complete").asBoolean()).isFalse();
    }

    private JsonNode sync() throws Exception {
        JsonNode finished = syncUntilFinished();
        assertThat(finished.get("state").asString()).as(finished.toString()).isEqualTo("completed");
        return finished;
    }

    private JsonNode syncUntilFinished() throws Exception {
        HttpResponse<String> started = send("POST", "/api/v1/sources/demo/spaces/DEMO/syncs",
                HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        return poll(started.headers().firstValue("Location").orElseThrow());
    }

    private JsonNode export() throws Exception {
        HttpResponse<String> started = send("POST", "/api/v1/sources/demo/spaces/DEMO/exports",
                HttpResponse.BodyHandlers.ofString());
        assertThat(started.statusCode()).as(started.body()).isEqualTo(202);
        return poll(started.headers().firstValue("Location").orElseThrow());
    }

    /** Fragt einen Auftrag ab, bis er nicht mehr wartet oder läuft. */
    private JsonNode poll(String location) throws Exception {
        Instant deadline = Instant.now().plus(TIMEOUT);
        while (true) {
            JsonNode status = get(location);
            String state = status.get("state").asString();
            if (!state.equals("queued") && !state.equals("running")) {
                return status;
            }
            assertThat(Instant.now()).as("Auftrag %s endet nicht", location).isBefore(deadline);
            Thread.sleep(50);
        }
    }

    private List<JsonNode> allPages(String path) throws Exception {
        List<JsonNode> pages = new ArrayList<>();
        String cursor = "";
        do {
            JsonNode body = get(path + "?limit=2"
                    + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            body.get("pages").forEach(pages::add);
            cursor = body.get("nextCursor").isNull() ? "" : body.get("nextCursor").asString();
        } while (!cursor.isEmpty());
        return pages;
    }

    private JsonNode get(String path) throws Exception {
        HttpResponse<String> response = send("GET", path, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(path + ": " + response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private byte[] download(String path) throws Exception {
        HttpResponse<byte[]> response = send("GET", path, HttpResponse.BodyHandlers.ofByteArray());
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        return response.body();
    }

    private <T> HttpResponse<T> send(String method, String path, HttpResponse.BodyHandler<T> body)
            throws IOException, InterruptedException {
        HttpRequest request = TestClient.authenticated(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + path)))
                .method(method, HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request, body);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
