package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageHeader;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
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

/**
 * Cachegestützte Seitenliste, Baum und native Einzelseite über echtes HTTP, gegen den {@link InMemoryPageCache}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageQueryApiTest {

    /** Kein gültiges UTF-8: die Payload darf nirgends als Text behandelt werden. */
    private static final byte[] NOT_UTF8 = {'{', 0x00, (byte) 0xC3, (byte) 0x28, (byte) 0xFF, '}'};
    private static final String METADATA_TOKEN = "metadata-only-token-7d41";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        registry.add("space-harvester.query.max-tree-nodes", () -> "1000");
        TestClient.register(registry, "wiki");
        // Zweiter Client nur mit metadata auf DEV.
        registry.add("space-harvester.security.clients[1].id", () -> "metadata-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(METADATA_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
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
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(
                page("wiki", "DEV", "100", null),
                page("wiki", "DEV", "101", "100", NOT_UTF8),
                page("wiki", "DEV", "102", "100"),
                page("wiki", "DEV", "103", "101"),
                page("wiki", "DEV", "200", null),
                page("wiki", "DEV", "300", "999")));
    }

    @Test
    void pagesArePagedFromOneGenerationWithoutContent() throws Exception {
        HttpResponse<String> first = get("/api/v1/sources/wiki/spaces/DEV/pages?limit=4");

        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(first.body());
        assertThat(body.get("origin").asString()).isEqualTo("cache");
        assertThat(body.get("cache").get("generation").asString()).isEqualTo("g1");
        assertThat(body.get("cache").get("state").asString()).isEqualTo("confirmed");
        assertThat(body.get("cache").get("syncedAt").asString()).isEqualTo("2026-10-16T20:00:00Z");
        assertThat(body.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");
        assertThat(body.get("complete").asBoolean()).isFalse();
        JsonNode page = body.get("pages").get(1);
        assertThat(page.get("pageId").asString()).isEqualTo("101");
        assertThat(page.get("parentPageId").asString()).isEqualTo("100");
        assertThat(page.get("title").asString()).isEqualTo("Seite 101");
        assertThat(page.get("metadataHash").asString()).startsWith("sha256:");
        assertThat(page.get("rawSha256").asString()).startsWith("sha256:");
        assertThat(page.has("body")).isFalse();

        HttpResponse<String> second = get("/api/v1/sources/wiki/spaces/DEV/pages?limit=4&cursor="
                + URLEncoder.encode(body.get("nextCursor").asString(), StandardCharsets.UTF_8));
        JsonNode rest = json.readTree(second.body());
        assertThat(rest.get("complete").asBoolean()).isTrue();
        assertThat(rest.get("nextCursor").isNull()).isTrue();
        List<String> ids = new ArrayList<>();
        body.get("pages").forEach(p -> ids.add(p.get("pageId").asString()));
        rest.get("pages").forEach(p -> ids.add(p.get("pageId").asString()));
        assertThat(ids).containsExactly("100", "101", "102", "103", "200", "300");
    }

    @Test
    void cursorOfAReplacedGenerationGives409() throws Exception {
        String cursor = json.readTree(get("/api/v1/sources/wiki/spaces/DEV/pages?limit=2").body())
                .get("nextCursor").asString();
        cache.publish("wiki", "DEV", confirmed("g2"), List.of(page("wiki", "DEV", "100", null)));

        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/DEV/pages?limit=2&cursor="
                + URLEncoder.encode(cursor, StandardCharsets.UTF_8));

        assertProblem(response, 409, "cache-generation-changed");
    }

    @Test
    void invalidLimitAndCursorAre400() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/spaces/DEV/pages?limit=0"), 400, "invalid-request");
        assertProblem(get("/api/v1/sources/wiki/spaces/DEV/pages?cursor=abc"), 400, "invalid-request");
    }

    @Test
    void treeHasIdsOnlyByDefaultAndSeveralRoots() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/DEV/tree");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("origin").asString()).isEqualTo("cache");
        assertThat(body.get("cache").get("generation").asString()).isEqualTo("g1");
        assertThat(body.get("nodeCount").asInt()).isEqualTo(6);
        assertThat(body.get("depth").asInt()).isEqualTo(3);
        JsonNode roots = body.get("roots");
        assertThat(roots).hasSize(3);
        assertThat(roots.get(0).get("id").asString()).isEqualTo("100");
        assertThat(roots.get(0).has("title")).isFalse();
        assertThat(roots.get(0).get("children").get(0).get("id").asString()).isEqualTo("101");
        assertThat(roots.get(0).get("children").get(0).get("children").get(0).get("id").asString())
                .isEqualTo("103");
        assertThat(roots.get(2).get("id").asString()).isEqualTo("300");
        assertThat(body.get("anomalies").get(0).get("pageId").asString()).isEqualTo("300");
        assertThat(body.get("anomalies").get(0).get("kind").asString()).isEqualTo("missing-parent");
        assertThat(response.body()).doesNotContain("Seite 1");
    }

    @Test
    void treeWithMetadataHasTitlesButNoContent() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/sources/wiki/spaces/DEV/tree?include=metadata").body());

        JsonNode root = body.get("roots").get(0);
        assertThat(root.get("title").asString()).isEqualTo("Seite 100");
        assertThat(root.get("version").asString()).isEqualTo("3");
        assertThat(root.get("modifiedAt").asString()).isEqualTo("2026-10-15T10:30:00Z");
        assertThat(root.has("body")).isFalse();
    }

    @Test
    void unsupportedIncludeIs400() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/spaces/DEV/tree?include=content"), 400, "invalid-request");
        assertProblem(get("/api/v1/sources/wiki/spaces/DEV/tree?include=bodies"), 400, "invalid-request");
    }

    @Test
    void deepestAllowedTreeIsSerialized() throws Exception {
        List<PageSnapshot> chain = new ArrayList<>();
        for (int i = 0; i < PageQueries.MAX_TREE_DEPTH; i++) {
            chain.add(page("wiki", "DEEP", String.format("c%04d", i), i == 0 ? null : String.format("c%04d", i - 1)));
        }
        cache.publish("wiki", "DEEP", confirmed("g1"), chain);

        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/DEEP/tree");

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertThat(json.readTree(response.body()).get("depth").asInt()).isEqualTo(PageQueries.MAX_TREE_DEPTH);
    }

    @Test
    void tooLargeTreeIs422() throws Exception {
        List<PageSnapshot> pages = new ArrayList<>();
        for (int i = 0; i < 1001; i++) {
            pages.add(page("wiki", "BIG", String.format("n%05d", i), null));
        }
        cache.publish("wiki", "BIG", confirmed("g1"), pages);

        assertProblem(get("/api/v1/sources/wiki/spaces/BIG/tree"), 422, "tree-too-large");
    }

    @Test
    void nativePageIsReturnedByteForByteWithCacheHeaders() throws Exception {
        HttpResponse<byte[]> response = http.send(request("/api/v1/sources/wiki/pages/101").build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(NOT_UTF8);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        String rawSha256 = page("wiki", "DEV", "101", "100", NOT_UTF8).payload().rawSha256();
        assertThat(response.headers().firstValue("ETag")).contains("\"" + rawSha256 + "\"");
        assertThat(response.headers().firstValue("X-Cache-Generation")).contains("g1");
        assertThat(response.headers().firstValue("X-Cache-State")).contains("confirmed");
        assertThat(response.headers().firstValue("X-Page-Origin")).contains("cache");
        assertThat(response.headers().firstValue("X-Page-Refresh")).isEmpty();
        assertThat(response.headers().firstValue("X-Cache-Synced-At")).contains("2026-10-16T20:00:00Z");
        assertThat(response.headers().firstValue("X-Cache-Last-Checked-At")).contains("2026-10-16T20:05:00Z");
        assertThat(response.headers().firstValue("X-Page-Revision")).contains("3");
        assertThat(response.headers().firstValue("X-Native-Format"))
                .contains("confluence-rest-content+json;expand=body.storage");
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-cache").contains("private"));

        HttpResponse<byte[]> again = http.send(request("/api/v1/sources/wiki/pages/101")
                .header("If-None-Match", "\"" + rawSha256 + "\"").build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.body()).isEmpty();
    }

    @Test
    void staleStateIsVisibleNotHidden() throws Exception {
        cache.publish("wiki", "DEV", new CacheGeneration("g3", CacheState.STALE, Instant.parse("2026-10-01T00:00:00Z"),
                null), List.of(page("wiki", "DEV", "100", null)));

        HttpResponse<String> page = get("/api/v1/sources/wiki/pages/100");
        assertThat(page.headers().firstValue("X-Cache-State")).contains("stale");
        assertThat(page.headers().firstValue("X-Cache-Last-Checked-At")).isEmpty();
        JsonNode tree = json.readTree(get("/api/v1/sources/wiki/spaces/DEV/tree").body());
        assertThat(tree.get("cache").get("state").asString()).isEqualTo("stale");
        assertThat(tree.get("cache").get("lastCheckedAt").isNull()).isTrue();
    }

    @Test
    void refreshIsNeverAnsweredFromTheCache() throws Exception {
        // Die Quelle (127.0.0.1:9) ist nicht erreichbar: ein Fehler, nie der Cache-Stand.
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/101?refresh=true");

        assertProblem(response, 503, "source-unavailable");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
        assertThat(response.headers().firstValue("X-Cache-Generation")).isEmpty();
    }

    @Test
    void extendedViewSeparatesNativeDataFromHarvesterMetadata() throws Exception {
        byte[] payload = "{\"id\":\"104\",\"type\":\"page\",\"exact\":1.10,\"big\":12345678901234567890.000000001}"
                .getBytes(StandardCharsets.UTF_8);
        PageSnapshot stored = page("wiki", "DEV", "104", "100", payload);
        cache.publish("wiki", "DEV", confirmed("g2"), List.of(stored));
        // Die Synchronisierung hat neuere Kopfdaten gesehen, aber noch keine neue Payload geladen.
        PageMetadata renamed = new PageMetadata("104", stored.header().metadata().resourceId(), "Neuer Titel", "DEV",
                "100", "4", Instant.parse("2026-10-16T20:30:00Z"), null, PageMetadata.sorted(Map.of()));
        cache.updateHeader("DEV", PageHeader.of("wiki", renamed, Instant.parse("2026-10-16T20:40:00Z")));

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/104?view=extended");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.headers().firstValue("Cache-Control")).hasValueSatisfying(
                value -> assertThat(value).contains("no-cache").contains("private"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(body.get("origin").asString()).isEqualTo("cache");
        assertThat(body.get("cache").get("generation").asString()).isEqualTo("g2");
        assertThat(body.get("cache").get("state").asString()).isEqualTo("confirmed");
        // data ist die native Antwort, ohne Harvester-Felder und ohne Stellenverlust.
        assertThat(body.get("data").propertyNames()).containsExactly("id", "type", "exact", "big");
        assertThat(response.body()).contains("\"exact\":1.10").contains("\"big\":12345678901234567890.000000001");
        JsonNode metadata = body.get("metadata");
        assertThat(metadata.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");
        assertThat(metadata.get("attributesHashSchema").asString()).isEqualTo("page-attributes/v1");
        assertThat(metadata.get("page").get("title").asString()).isEqualTo("Neuer Titel");
        assertThat(metadata.get("page").get("version").asString()).isEqualTo("4");
        assertThat(metadata.get("page").get("rawSha256").asString()).isEqualTo(stored.payload().rawSha256());
        assertThat(metadata.get("payloadRevision").asString()).isEqualTo("3");
        assertThat(metadata.get("payloadRetrievedAt").asString()).isEqualTo("2026-10-16T20:00:00Z");
        assertThat(metadata.get("headerObservedAt").asString()).isEqualTo("2026-10-16T20:40:00Z");
        assertThat(metadata.get("nativeFormat").asString())
                .isEqualTo("confluence-rest-content+json;expand=body.storage");
        assertThat(body.get("enrichments").isArray()).isTrue();
        assertThat(body.get("enrichments").isEmpty()).isTrue();
    }

    @Test
    void extendedViewOfANonJsonPayloadIs422WithoutLeakingIt() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/101?view=extended");

        assertProblem(response, 422, "payload-not-json");
        assertThat(response.body()).doesNotContain("\\u0000").doesNotContain("\"id\"");
        assertThat(get("/api/v1/sources/wiki/pages/101?view=native").statusCode()).isEqualTo(200);
    }

    @Test
    void extendedViewFollowsTheSameRulesAsTheNativeView() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/pages/101?view=html"), 400, "invalid-request");
        assertProblem(get("/api/v1/sources/wiki/pages/100?view=extended&refresh=true"), 503, "source-unavailable");
        assertProblem(get("/api/v1/sources/wiki/pages/4711?view=extended"), 404, "cache-miss");
        HttpResponse<String> denied = getAs(METADATA_TOKEN, "/api/v1/sources/wiki/pages/100?view=extended");
        assertProblem(denied, 403, "access-denied");
        assertThat(denied.body()).doesNotContain("Seite 100");
    }

    @Test
    void missingObjectsAreDistinguishedFromUnknownSources() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/pages/4711"), 404, "cache-miss");
        assertProblem(get("/api/v1/sources/wiki/spaces/OPS/pages"), 404, "cache-miss");
        assertProblem(get("/api/v1/sources/wiki/spaces/OPS/tree"), 404, "cache-miss");
        // Eine nicht konfigurierte Quelle ist für den Client nicht freigegeben, wie jede andere fremde Quelle.
        assertProblem(get("/api/v1/sources/nope/pages/101"), 403, "access-denied");
        assertProblem(get("/api/v1/sources/nope/spaces/DEV/tree"), 403, "access-denied");
    }

    @Test
    void withoutTokenNothingIsDelivered() throws Exception {
        for (String path : new String[]{"/api/v1/sources/wiki/spaces/DEV/pages", "/api/v1/sources/wiki/spaces/DEV/tree",
                "/api/v1/sources/wiki/pages/101"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri(path)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(path).isEqualTo(401);
            assertThat(response.body()).doesNotContain("101").doesNotContain("Seite");
        }
    }

    @Test
    void metadataGrantGivesTreeButNeitherPayloadNorOtherSpaces() throws Exception {
        cache.publish("wiki", "OPS", confirmed("g1"), List.of(page("wiki", "OPS", "900", null)));

        assertThat(getAs(METADATA_TOKEN, "/api/v1/sources/wiki/spaces/DEV/tree").statusCode()).isEqualTo(200);
        assertThat(getAs(METADATA_TOKEN, "/api/v1/sources/wiki/spaces/DEV/pages").statusCode()).isEqualTo(200);
        assertProblem(getAs(METADATA_TOKEN, "/api/v1/sources/wiki/pages/101"), 403, "access-denied");
        assertProblem(getAs(METADATA_TOKEN, "/api/v1/sources/wiki/spaces/OPS/tree"), 403, "access-denied");
        assertProblem(getAs(METADATA_TOKEN, "/api/v1/sources/wiki/spaces/OPS/pages"), 403, "access-denied");
    }

    @Test
    void routesAreDocumentedInOpenApi() throws Exception {
        JsonNode paths = json.readTree(get("/v3/api-docs").body()).get("paths");

        assertThat(paths.has("/api/v1/sources/{sourceId}/spaces/{spaceKey}/pages")).isTrue();
        assertThat(paths.has("/api/v1/sources/{sourceId}/spaces/{spaceKey}/tree")).isTrue();
        assertThat(paths.has("/api/v1/sources/{sourceId}/pages/{pageId}")).isTrue();
        JsonNode page = paths.get("/api/v1/sources/{sourceId}/pages/{pageId}").get("get");
        assertThat(page.get("responses").has("501")).isTrue();
        assertThat(page.get("responses").get("200").get("headers").has("X-Cache-State")).isTrue();
        assertThat(paths.get("/api/v1/sources/{sourceId}/spaces/{spaceKey}/tree").get("get")
                .get("responses").has("422")).isTrue();
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAs(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Als Test-Client mit voller Freigabe auf {@code wiki}. */
    private HttpRequest.Builder request(String path) {
        return TestClient.authenticated(HttpRequest.newBuilder(uri(path)));
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
