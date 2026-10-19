package com.aresstack.spaceharvester.api;

import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import org.springframework.context.annotation.Primary;
import com.aresstack.spaceharvester.EmptyConfluence;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
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

/** Anreicherungen über HTTP: Ausgabe in view=extended, Neuberechnung, Aktualität und Freigaben. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class EnrichmentApiTest {

    private static final String METADATA_TOKEN = "metadata-only-token-51c2";
    private static final String READER_TOKEN = "reader-token-7d10e4";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", EmptyConfluence::baseUrl);
        TestClient.register(registry, "wiki");
        // Neu berechnen verlangt zusätzlich sync.
        registry.add("space-harvester.security.clients[0].grants[1].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[1].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[0].grants[1].access", () -> "sync");
        registry.add("space-harvester.security.clients[2].id", () -> "reader");
        registry.add("space-harvester.security.clients[2].token-sha256", () -> "sha256:" + sha256(READER_TOKEN));
        registry.add("space-harvester.security.clients[2].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[2].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[2].grants[0].access", () -> "metadata,content");
        registry.add("space-harvester.security.clients[1].id", () -> "metadata-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
    }

    @TestConfiguration
    static class Cache {

        @Bean
        @Primary
        PageFreshness cacheOnlyFreshness(SourceQueries queries) {
            return EmptyConfluence.cacheOnly(queries);
        }


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

    @Test
    void resultsAreComputedOnRequestAndShownWithTheExtendedView() throws Exception {
        PageSnapshot stored = publish("501", "<h1>Ziele</h1><p>Die API (Application Programming Interface).</p>");
        assertThat(json.readTree(get("/api/v1/sources/wiki/pages/501?view=extended").body()).get("enrichments")
                .isEmpty()).isTrue();

        HttpResponse<String> recomputed = post("/api/v1/sources/wiki/pages/501/enrichments", TestClient.TOKEN);

        assertThat(recomputed.statusCode()).isEqualTo(200);
        JsonNode run = json.readTree(recomputed.body());
        assertThat(run.get("supported").asBoolean()).isTrue();
        assertThat(run.get("computed").asInt()).isEqualTo(9);
        assertThat(run.get("failed").asInt()).isZero();
        JsonNode enrichments = json.readTree(get("/api/v1/sources/wiki/pages/501?view=extended").body())
                .get("enrichments");
        assertThat(enrichments).extracting(entry -> entry.get("processor").asString())
                .containsExactly("acronyms", "activities", "entities", "navigation", "outline", "passages", "properties",
                        "relations", "terms");
        JsonNode acronyms = enrichments.get(0);
        assertThat(acronyms.get("processor").asString()).isEqualTo("acronyms");
        assertThat(acronyms.get("processorVersion").asString()).isEqualTo("3");
        assertThat(acronyms.get("status").asString()).isEqualTo("ok");
        assertThat(acronyms.get("freshness").asString()).isEqualTo("current");
        assertThat(acronyms.get("inputSha256").asString()).startsWith("sha256:");
        assertThat(acronyms.get("payloadRevision").asString()).isEqualTo("3");
        assertThat(acronyms.get("result").get("acronyms").get(0).get("expansion").asString())
                .isEqualTo("Application Programming Interface");
        assertThat(acronyms.get("result").get("acronyms").get(0).get("section").asString()).isEqualTo("Ziele");
        // Version 2 (#115): bisherige Felder unverändert, dazu jede Fundstelle
        JsonNode api = acronyms.get("result").get("acronyms").get(0);
        assertThat(api.get("charStart").asInt()).isEqualTo(api.get("occurrences").get(0).get("start").asInt());
        assertThat(api.get("occurrences").get(0).get("end").asInt() - api.get("charStart").asInt()).isEqualTo(3);
        assertThat(enrichments.get(2).get("result").get("entities").get(0).get("term").asString())
                .isEqualTo("API");
        assertThat(enrichments.get(4).get("result").get("sections").get(0).get("heading").asString())
                .isEqualTo("Ziele");

        HttpResponse<byte[]> nativeView = http.send(TestClient.authenticated(
                HttpRequest.newBuilder(uri("/api/v1/sources/wiki/pages/501"))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertThat(nativeView.body()).isEqualTo(stored.payload().bytes());
    }

    @Test
    void theDocumentNavigationShowsTheHeadingsWithoutAModel() throws Exception {
        publish("509", "<h1>Handbuch</h1><h2>Installation</h2><p>Schritte.</p><h2>Betrieb</h2><p>Täglich.</p>");

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/509/navigation");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode navigation = json.readTree(response.body());
        assertThat(navigation.get("state").asString()).isEqualTo("current");
        assertThat(navigation.get("analysis").asString()).isEqualTo("headings");
        assertThat(navigation.get("reason").asString()).isEqualTo("no-model");
        assertThat(navigation.get("chapters")).extracting(chapter -> chapter.get("label").asString())
                .containsExactly("Installation", "Betrieb");
        assertThat(navigation.get("chapters").get(0).get("origin").asString()).isEqualTo("heading");
        assertThat(navigation.get("title").asString()).isNotBlank();
        assertThat(navigation.get("inputSha256").asString()).isNotBlank();
        assertThat(navigation.get("textSha256").asString()).isNotBlank()
                .isNotEqualTo(navigation.get("inputSha256").asString());
        assertThat(get("/api/v1/sources/wiki/pages/unbekannt/navigation").statusCode()).isEqualTo(404);
    }

    @Test
    void aPassageIsReadBackByItsStableId() throws Exception {
        publish("510", "<h1>Handbuch</h1><p>Erster Absatz.</p><p>Zweiter Absatz.</p><p>Dritter Absatz.</p>");
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/510/passages");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode passages = json.readTree(response.body()).get("passages");
        assertThat(passages.size()).isPositive();
        String id = passages.get(0).get("id").asString();
        assertThat(id).startsWith("wiki:510@").contains("#p0@");

        HttpResponse<String> lookup = get("/api/v1/passages?id="
                + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8));
        assertThat(lookup.statusCode()).isEqualTo(200);
        JsonNode found = json.readTree(lookup.body());
        assertThat(found.get("match").asString()).isEqualTo("found");
        assertThat(found.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(found.get("pageId").asString()).isEqualTo("510");
        assertThat(found.get("passages").get(0).get("id").asString()).isEqualTo(id);
        assertThat(found.get("passages").size()).isEqualTo(Math.min(2, passages.size()));

        String older = id.replaceFirst("@[0-9a-f]{64}#", "@" + "0".repeat(64) + "#");
        JsonNode moved = json.readTree(get("/api/v1/passages?id="
                + java.net.URLEncoder.encode(older, java.nio.charset.StandardCharsets.UTF_8)).body());
        assertThat(moved.get("match").asString()).isEqualTo("older-version");
        assertThat(moved.get("passages").size()).isEqualTo(passages.size());

        assertProblem(get("/api/v1/passages?id=wiki%3A510"), 400, "invalid-request");
        assertProblem(get("/api/v1/passages?id=" + java.net.URLEncoder.encode(id.replace("wiki:510@", "wiki:unbekannt@"),
                java.nio.charset.StandardCharsets.UTF_8)), 404, "cache-miss");
        // Ohne content-Freigabe wie eine unbekannte Seite: keine 403, die das Bestehen verriete.
        HttpResponse<String> hidden = http.send(HttpRequest.newBuilder(uri("/api/v1/passages?id="
                        + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)))
                .header("Authorization", "Bearer " + METADATA_TOKEN).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(hidden, 404, "cache-miss");
        assertThat(hidden.body()).doesNotContain("Absatz");
    }

    @Test
    void oneProcessorCanBeRecomputedAloneAndUnknownOnesAreRefused() throws Exception {
        publish("502", "<p>Ein Text.</p>");

        JsonNode run = json.readTree(post("/api/v1/sources/wiki/pages/502/enrichments?processor=outline",
                TestClient.TOKEN).body());

        assertThat(run.get("computed").asInt()).isEqualTo(1);
        assertThat(run.get("enrichments")).extracting(entry -> entry.get("processor").asString())
                .containsExactly("outline");
        assertProblem(post("/api/v1/sources/wiki/pages/502/enrichments?processor=nope", TestClient.TOKEN), 400,
                "invalid-request");
    }

    @Test
    void aChangedPageMarksItsResultsStale() throws Exception {
        publish("503", "<p>Alter Text</p>");
        post("/api/v1/sources/wiki/pages/503/enrichments", TestClient.TOKEN);

        publish("503", "<p>Neuer Text</p>");

        JsonNode enrichments = json.readTree(get("/api/v1/sources/wiki/pages/503?view=extended").body())
                .get("enrichments");
        assertThat(enrichments).extracting(entry -> entry.get("freshness").asString())
                .containsExactly("stale", "stale", "stale", "stale", "stale", "stale", "stale", "stale", "stale");
    }

    @Test
    void derivedDataNeedsTheContentGrant() throws Exception {
        publish("504", "<p>Vertraulich: KPI (Key Performance Indicator)</p>");
        post("/api/v1/sources/wiki/pages/504/enrichments", TestClient.TOKEN);

        assertProblem(post("/api/v1/sources/wiki/pages/504/enrichments", METADATA_TOKEN), 403, "access-denied");
        assertProblem(post("/api/v1/sources/wiki/pages/504/enrichments", READER_TOKEN), 403, "access-denied");
        HttpResponse<String> extended = http.send(HttpRequest.newBuilder(
                uri("/api/v1/sources/wiki/pages/504?view=extended"))
                .header("Authorization", "Bearer " + METADATA_TOKEN).build(), HttpResponse.BodyHandlers.ofString());
        assertProblem(extended, 403, "access-denied");
        assertThat(extended.body()).doesNotContain("Key Performance");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(
                uri("/api/v1/sources/wiki/pages/504/enrichments")).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("Key Performance");
    }

    @Test
    void aPageOutsideTheCacheIsACacheMiss() throws Exception {
        publish("505", "<p>x</p>");

        assertProblem(post("/api/v1/sources/wiki/pages/999/enrichments", TestClient.TOKEN), 404, "cache-miss");
    }

    @Test
    void theRouteIsDocumented() throws Exception {
        JsonNode paths = json.readTree(get("/v3/api-docs").body()).get("paths");

        assertThat(paths.get("/api/v1/sources/{sourceId}/pages/{pageId}/enrichments").has("post")).isTrue();
    }

    private PageSnapshot publish(String pageId, String storage) {
        String payload = "{\"id\":\"" + pageId + "\",\"type\":\"page\",\"body\":{\"storage\":{\"value\":\""
                + storage.replace("\"", "\\\"") + "\",\"representation\":\"storage\"}}}";
        PageSnapshot snapshot = page("wiki", "DEV", pageId, null, payload.getBytes(StandardCharsets.UTF_8));
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(snapshot));
        return snapshot;
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(uri(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
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
