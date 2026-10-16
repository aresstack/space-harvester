package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageQueries;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Cachegestützte Seitenliste, Baum und native Einzelseite über echtes HTTP, gegen den {@link InMemoryPageCache}.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "space-harvester.sources[0].id=wiki",
        "space-harvester.sources[0].type=confluence",
        "space-harvester.sources[0].base-url=http://127.0.0.1:9",
        "space-harvester.query.max-tree-nodes=1000"})
class PageQueryApiTest {

    /** Kein gültiges UTF-8: die Payload darf nirgends als Text behandelt werden. */
    private static final byte[] NOT_UTF8 = {'{', 0x00, (byte) 0xC3, (byte) 0x28, (byte) 0xFF, '}'};

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
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/101?refresh=true");

        assertProblem(response, 501, "refresh-unavailable");
        assertThat(response.headers().firstValue("ETag")).isEmpty();
    }

    @Test
    void extendedViewIsNotYetOffered() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/pages/101?view=extended"), 400, "invalid-request");
    }

    @Test
    void missingObjectsAreDistinguishedFromUnknownSources() throws Exception {
        assertProblem(get("/api/v1/sources/wiki/pages/4711"), 404, "cache-miss");
        assertProblem(get("/api/v1/sources/wiki/spaces/OPS/pages"), 404, "cache-miss");
        assertProblem(get("/api/v1/sources/wiki/spaces/OPS/tree"), 404, "cache-miss");
        assertProblem(get("/api/v1/sources/nope/pages/101"), 404, "source-not-found");
        assertProblem(get("/api/v1/sources/nope/spaces/DEV/tree"), 404, "source-not-found");
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

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(
                URI.create("http://localhost:" + environment.getProperty("local.server.port") + path));
    }
}
