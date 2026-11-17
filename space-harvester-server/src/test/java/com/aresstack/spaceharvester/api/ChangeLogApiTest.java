package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.changelog.ChangeTrigger;
import com.aresstack.spaceharvester.application.changelog.UpdateMark;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Änderungsprotokoll über echtes HTTP (ADR 0030, CL2): Updates, Bericht, rohe Zeilen und Rechte je Seite. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ChangeLogApiTest {

    private static final String DEV_ONLY_TOKEN = "dev-only-change-log-token-61c2";
    /** Unter build/, nicht gelöscht: der Spring-Kontext schließt seine Indizes erst am Ende der Testläufe. */
    private static final Path CACHE = Path.of("build", "change-log-api-test", java.util.UUID.randomUUID().toString());

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "dev-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(DEV_ONLY_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
        registry.add("space-harvester.cache.directory", () -> CACHE.toAbsolutePath().toString());
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private LucenePageCache cache;

    private static PageSnapshot page(String pageId, String spaceKey, String parentId, String title) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki/page/" + pageId, title, spaceKey, parentId,
                "1", Instant.parse("2026-11-01T08:00:00Z"), URI.create("https://wiki.example.org/" + pageId),
                PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload("{}".getBytes(StandardCharsets.UTF_8),
                "application/json", "confluence-content/v1"), Instant.parse("2026-11-01T08:00:00Z"));
    }

    /** Erstbefüllung mit Marke, danach eine neue Seite in DEV und eine in OPS (offenes Update). */
    @BeforeEach
    void fill() {
        cache.removeSource("wiki");
        ChangeTrigger.MANUAL.run(() -> cache.save(page("100", "DEV", null, "Start")));
        Instant first = cache.changeEntries("wiki").getLast().at();
        cache.mark(new UpdateMark("wiki", first, "test-client", List.of("DEV"), 0, true));
        ChangeTrigger.BACKGROUND.run(() -> cache.save(page("101", "DEV", "100", "Betrieb")));
        ChangeTrigger.SCHEDULE.run(() -> cache.save(page("200", "OPS", null, "Geheim")));
    }

    @Test
    void theUpdatesOfASourceAndTheReportOfTheOpenOne() throws Exception {
        HttpResponse<String> updates = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates");
        assertThat(updates.statusCode()).as(updates.body()).isEqualTo(200);
        JsonNode list = json.readTree(updates.body());
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asString()).isEqualTo("open");
        assertThat(list.get(0).get("pages").asInt()).isEqualTo(2);
        assertThat(list.get(1).get("initial").asBoolean()).isTrue();

        HttpResponse<String> report = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open");
        assertThat(report.statusCode()).as(report.body()).isEqualTo(200);
        JsonNode pages = json.readTree(report.body()).get("pages");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).get("title").asString()).isEqualTo("Betrieb");
        assertThat(pages.get(0).get("path").get(0).asString()).isEqualTo("Start");
        assertThat(pages.get(0).get("url").asString()).isEqualTo("https://wiki.example.org/101");

        HttpResponse<String> initial = get(TestClient.TOKEN,
                "/api/v1/sources/wiki/updates/" + list.get(1).get("id").asString());
        assertThat(json.readTree(initial.body()).get("pages")).isEmpty();
    }

    /** Seite 100 als Confluence-Inhalt mit Storage-Text. */
    private static PageSnapshot storage(String version, String html, Instant retrievedAt) {
        PageMetadata metadata = new PageMetadata("100", "confluence:wiki/page/100", "Start", "DEV", null, version,
                Instant.parse("2026-11-01T08:00:00Z"), URI.create("https://wiki.example.org/100"),
                PageMetadata.sorted(Map.of()));
        String body = "{\"body\":{\"storage\":{\"value\":\"" + html + "\"}}}";
        return PageSnapshot.of("wiki", metadata, new NativePayload(body.getBytes(StandardCharsets.UTF_8),
                "application/json", "confluence-rest-content+json"), retrievedAt);
    }

    @Test
    void theTextDiffOfAChangedPageComesOnlyOnRequest() throws Exception {
        ChangeTrigger.MANUAL.run(() -> cache.save(storage("1", "<p>Alt</p>", Instant.parse("2026-11-02T08:00:00Z"))));
        ChangeTrigger.MANUAL.run(() -> cache.save(storage("2", "<p>Neu</p>", Instant.parse("2026-11-03T08:00:00Z"))));

        JsonNode plain = json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open").body());
        JsonNode changed = page(plain, "100");
        assertThat(changed.get("kind").asString()).isEqualTo("CONTENT_CHANGED");
        assertThat(changed.path("diff").isNull() || changed.path("diff").isMissingNode()).isTrue();

        HttpResponse<String> withDiff = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open?diff=true");
        assertThat(withDiff.statusCode()).as(withDiff.body()).isEqualTo(200);
        JsonNode diff = page(json.readTree(withDiff.body()), "100").get("diff");
        assertThat(diff.get("status").asString()).as(withDiff.body()).isEqualTo("AVAILABLE");
        JsonNode paragraph = diff.get("steps").get(0).get("paragraphs").get(0);
        assertThat(paragraph.get("kind").asString()).isEqualTo("CHANGED");
        assertThat(paragraph.get("before").asString()).isEqualTo("Alt");
        assertThat(paragraph.get("after").asString()).isEqualTo("Neu");
    }

    private static JsonNode page(JsonNode report, String pageId) {
        for (JsonNode page : report.get("pages")) {
            if (page.get("pageId").asString().equals(pageId)) {
                return page;
            }
        }
        throw new AssertionError("Seite " + pageId + " fehlt im Bericht");
    }

    @Test
    void theSpaceLevelIsASliceOfTheSameUpdates() throws Exception {
        JsonNode ops = json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates?space=OPS").body());
        JsonNode all = json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates").body());
        assertThat(ops).hasSize(all.size());
        assertThat(ops.get(0).get("id").asString()).isEqualTo("open");
        assertThat(ops.get(0).get("pages").asInt()).isEqualTo(1);

        JsonNode report = json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open?space=DEV")
                .body());
        assertThat(report.get("pages")).hasSize(1);
        assertThat(report.get("pages").get(0).get("pageId").asString()).isEqualTo("101");
    }

    @Test
    void rawRowsAreSearchedAndAPageOutsideTheGrantIsWithheld() throws Exception {
        JsonNode all = json.readTree(get(TestClient.TOKEN, "/api/v1/change-log?sourceId=wiki").body());
        assertThat(all.get("total").asInt()).isEqualTo(3);
        assertThat(all.get("entries").get(0).get("pageId").asString()).isEqualTo("200");

        JsonNode background = json.readTree(get(TestClient.TOKEN,
                "/api/v1/change-log?trigger=background&kind=NEW").body());
        assertThat(background.get("total").asInt()).isEqualTo(1);
        assertThat(background.get("entries").get(0).get("pageId").asString()).isEqualTo("101");

        JsonNode devOnly = json.readTree(get(DEV_ONLY_TOKEN, "/api/v1/change-log").body());
        assertThat(devOnly.get("total").asInt()).isEqualTo(2);
        assertThat(devOnly.findValuesAsString("pageId")).doesNotContain("200");
        JsonNode devUpdates = json.readTree(get(DEV_ONLY_TOKEN, "/api/v1/sources/wiki/updates").body());
        assertThat(devUpdates.get(0).get("pages").asInt()).isEqualTo(1);
        assertThat(get(DEV_ONLY_TOKEN, "/api/v1/sources/wiki/updates/open").body()).doesNotContain("Geheim");
    }

    @Test
    void theUpdateTextComesAsJsonOrMarkdownPerLevelAndPageByPage() throws Exception {
        HttpResponse<String> text = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/text");
        assertThat(text.statusCode()).as(text.body()).isEqualTo(200);
        JsonNode body = json.readTree(text.body());
        assertThat(body.get("format").asString()).isEqualTo("update-text/v1");
        assertThat(body.get("totalPages").asInt()).isEqualTo(2);
        assertThat(body.get("markdown").asString()).startsWith("# Update wiki\n").contains("## Start › Betrieb\n");
        assertThat(body.get("sections")).hasSize(2);

        JsonNode ops = json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/text?space=OPS").body());
        assertThat(ops.get("totalPages").asInt()).isEqualTo(1);
        assertThat(ops.get("markdown").asString()).contains("Space OPS").contains("Geheim");

        JsonNode window = json.readTree(get(TestClient.TOKEN,
                "/api/v1/sources/wiki/updates/open/text?offset=1&limit=1").body());
        assertThat(window.get("offset").asInt()).isEqualTo(1);
        assertThat(window.get("report").get("pages")).hasSize(1);

        HttpResponse<String> markdown = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/v1/sources/wiki/updates/open/text"))
                .header("Authorization", "Bearer " + TestClient.TOKEN).header("Accept", "text/markdown").build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(markdown.statusCode()).as(markdown.body()).isEqualTo(200);
        assertThat(markdown.headers().firstValue("Content-Type").orElse("")).startsWith("text/markdown");
        assertThat(markdown.body()).startsWith("# Update wiki\n");

        assertThat(get(DEV_ONLY_TOKEN, "/api/v1/sources/wiki/updates/open/text").body()).doesNotContain("Geheim");
        assertThat(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/text?limit=0").statusCode())
                .isEqualTo(400);
        assertThat(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/12345/text").statusCode()).isEqualTo(404);
        assertThat(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/text?zone=Mars/Olymp").statusCode())
                .isEqualTo(400);
        assertThat(body.get("markdown").asString()).doesNotContain("Z bis").containsPattern("\\d{2}\\.\\d{2}\\.\\d{4}, \\d{2}:\\d{2}");
        assertThat(json.readTree(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/text?zone=UTC").body())
                .get("markdown").asString()).isNotEqualTo(body.get("markdown").asString());
    }

    @Test
    void unknownUpdatesAndInvalidFiltersAreProblems() throws Exception {
        HttpResponse<String> unknown = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/12345");
        assertThat(unknown.statusCode()).isEqualTo(404);
        assertThat(unknown.body()).contains("update-not-found");
        assertThat(get(TestClient.TOKEN, "/api/v1/sources/nirgends/updates").statusCode()).isEqualTo(404);
        assertThat(get(TestClient.TOKEN, "/api/v1/change-log?kind=VERSCHWUNDEN").statusCode()).isEqualTo(400);
        assertThat(get(TestClient.TOKEN, "/api/v1/change-log?from=gestern").statusCode()).isEqualTo(400);
        assertThat(get(TestClient.TOKEN, "/api/v1/change-log?limit=0").statusCode()).isEqualTo(400);
    }

    @Test
    void withoutAChatModelThereIsNoSummaryAndStartingOneIsAProblem() throws Exception {
        HttpResponse<String> none = get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/summary");
        assertThat(none.statusCode()).as(none.body()).isEqualTo(204);

        HttpResponse<String> start = post(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/summary");
        assertThat(start.statusCode()).as(start.body()).isEqualTo(409);
        assertThat(start.body()).contains("summary-unavailable", "NO_MODEL");
        assertThat(get(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/summary?space=DEV").statusCode())
                .isEqualTo(204);
        assertThat(post(TestClient.TOKEN, "/api/v1/sources/wiki/updates/open/summary?space=DEV").statusCode())
                .isEqualTo(409);
        assertThat(post(TestClient.TOKEN, "/api/v1/sources/wiki/updates/12345/summary").statusCode()).isEqualTo(404);
        assertThat(post(TestClient.TOKEN, "/api/v1/sources/nirgends/updates/open/summary").statusCode())
                .isEqualTo(404);
    }

    private HttpResponse<String> post(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .header("Authorization", "Bearer " + token).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + path))
                .header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
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
