package com.aresstack.spaceharvester.api.confluence;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
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
 * Confluence-kompatible Einzelseite über echtes HTTP, gegen den {@link InMemoryPageCache}: dieselben Bytes, die
 * Confluence geliefert hat, und Confluence-Fehlerkörper für alles, was der Cache nicht hält.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConfluenceCompatApiTest {

    /** Abrufform des Harvesters (enterprise-ai-client, {@code ConfluenceKnowledgeSource.SNAPSHOT_FORMAT}). */
    private static final String HARVEST_FORMAT = "confluence-rest-content+json;expand=ancestors,body.storage,history,"
            + "history.lastUpdated,metadata.labels,space,version";
    /** Wie Confluence sie liefert: Reihenfolge, Leerraum und unbekannte Felder bleiben erhalten. */
    private static final byte[] CONFLUENCE_RESPONSE = ("{\"id\":\"101\",\"type\":\"page\",\"status\":\"current\","
            + "\"title\":\"Seite 101\",  \"space\":{\"key\":\"DEV\"},\"body\":{\"storage\":{\"value\":\"<p>ä</p>\","
            + "\"representation\":\"storage\"}},\"x-unknown\":[1.50,true],\"_links\":{\"base\":"
            + "\"https://wiki.example.org\"}}").getBytes(StandardCharsets.UTF_8);
    private static final String BASE = "/api/compat/confluence/wiki/rest/api/content/";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
    }

    @TestConfiguration
    static class Cache {

        @Bean
        InMemoryPageCache pageCache() {
            return new InMemoryPageCache();
        }

        @Bean
        InMemorySearchIndex searchIndex() {
            return new InMemorySearchIndex();
        }
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private InMemoryPageCache cache;

    @Autowired
    private InMemorySearchIndex index;

    @BeforeEach
    void fillCache() {
        cache.clear();
        index.clear();
        cache.publish("wiki", "DEV", confirmed("g1"), List.of(
                snapshot("101", CONFLUENCE_RESPONSE, HARVEST_FORMAT),
                snapshot("102", "{}".getBytes(StandardCharsets.UTF_8), "confluence-blogpost+json")));
        List<PageSnapshot> ops = List.of(snapshot("OPS", "500", null, "Betrieb"), snapshot("OPS", "501", "500", "A"),
                snapshot("OPS", "502", "500", "B"), snapshot("OPS", "503", "500", "C"));
        cache.publish("wiki", "OPS", confirmed("g7"), ops);
        index.publish("wiki", new SearchIndexState("s1", Instant.parse("2026-10-16T20:10:00Z")),
                ops.stream().map(page -> InMemorySearchIndex.entry(page, confirmed("g7"))).toList());
    }

    @Test
    void contentListIsAConfluenceEnvelopeAroundTheStoredResponses() throws Exception {
        HttpResponse<byte[]> response = getBytes("/api/compat/confluence/wiki/rest/api/content?spaceKey=OPS&limit=2"
                + "&expand=body.storage");

        assertThat(response.statusCode()).isEqualTo(200);
        String body = new String(response.body(), StandardCharsets.UTF_8);
        assertThat(body).startsWith("{\"results\":[" + payload("500") + "," + payload("501") + "],");
        JsonNode envelope = json.readTree(body);
        assertThat(envelope.get("start").asInt()).isZero();
        assertThat(envelope.get("limit").asInt()).isEqualTo(2);
        assertThat(envelope.get("size").asInt()).isEqualTo(2);
        JsonNode links = envelope.get("_links");
        assertThat(links.get("base").asString()).endsWith("/api/compat/confluence/wiki");
        assertThat(links.get("context").asString()).isEqualTo("/api/compat/confluence/wiki");
        assertThat(links.get("next").asString()).startsWith("/rest/api/content?")
                .contains("spaceKey=OPS", "start=2", "limit=2", "expand=body.storage");

        JsonNode rest = json.readTree(getBytes("/api/compat/confluence/wiki" + links.get("next").asString()).body());
        assertThat(rest.get("size").asInt()).isEqualTo(2);
        assertThat(rest.get("results").get(0).get("id").asString()).isEqualTo("502");
        assertThat(rest.get("_links").has("next")).isFalse();
    }

    @Test
    void childPagesAndTitleFilter() throws Exception {
        JsonNode children = json.readTree(get(BASE + "500/child/page?start=1").body());
        assertThat(children.get("size").asInt()).isEqualTo(2);
        assertThat(children.get("results").get(0).get("id").asString()).isEqualTo("502");
        assertThat(children.get("_links").get("self").asString()).endsWith("/rest/api/content/500/child/page");

        JsonNode titled = json.readTree(get("/api/compat/confluence/wiki/rest/api/content?spaceKey=OPS&title=B")
                .body());
        assertThat(titled.get("size").asInt()).isEqualTo(1);
        assertThat(titled.get("results").get(0).get("id").asString()).isEqualTo("502");
    }

    @Test
    void listsTheCacheCannotServeCompletelyAreRefused() throws Exception {
        String list = "/api/compat/confluence/wiki/rest/api/content";
        assertConfluenceError(get(list), 400, "spaceKey");
        assertConfluenceError(get(list + "?spaceKey=OPS&type=blogpost"), 400, "type=blogpost");
        assertConfluenceError(get(list + "?spaceKey=OPS&expand=children.page"), 400, "children.page");
        assertConfluenceError(get(list + "?spaceKey=OPS&start=-1"), 400, "start");

        List<PageSnapshot> ops = List.of(snapshot("OPS", "500", null, "Betrieb"));
        CacheGeneration incomplete = new CacheGeneration("g8", CacheState.INCOMPLETE, null, null);
        cache.publish("wiki", "OPS", incomplete, ops);
        index.publish("wiki", new SearchIndexState("s2", Instant.parse("2026-10-16T20:20:00Z")),
                List.of(InMemorySearchIndex.entry(ops.get(0), incomplete)));
        assertConfluenceError(get(list + "?spaceKey=OPS"), 503, "unvollständig");
        assertConfluenceError(get(BASE + "500/child/page"), 503, "unvollständig");
    }

    @Test
    void contentIsTheStoredConfluenceResponseByteForByte() throws Exception {
        for (String query : new String[]{"", "?expand=body.storage,version", "?expand=body,history",
                "?expand=space&status=current", "?expand=history.lastUpdated,ancestors,metadata.labels"}) {
            HttpResponse<byte[]> response = getBytes(BASE + "101" + query);

            assertThat(response.statusCode()).as(query).isEqualTo(200);
            assertThat(response.body()).as(query).isEqualTo(CONFLUENCE_RESPONSE);
            assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                    type -> assertThat(type).startsWith("application/json"));
            assertThat(response.headers().firstValue("X-Cache-Generation")).contains("g1");
            assertThat(response.headers().firstValue("X-Cache-State")).contains("confirmed");
        }
    }

    @Test
    void anUnchangedPayloadGives304() throws Exception {
        String etag = getBytes(BASE + "101").headers().firstValue("ETag").orElseThrow();

        HttpResponse<byte[]> again = http.send(request(BASE + "101").header("If-None-Match", etag).build(),
                HttpResponse.BodyHandlers.ofByteArray());

        assertThat(again.statusCode()).isEqualTo(304);
        assertThat(again.headers().firstValue("X-Cache-Generation")).contains("g1");
    }

    @Test
    void whatTheCacheDoesNotHoldIsRejectedNotInvented() throws Exception {
        assertConfluenceError(get(BASE + "101?expand=body.view"), 400, "body.view");
        assertConfluenceError(get(BASE + "101?expand=version,children.page"), 400, "children.page");
        assertConfluenceError(get(BASE + "101?status=historical"), 400, "status=historical");
        assertConfluenceError(get(BASE + "101?version=2"), 400, "version");
    }

    @Test
    void missingPagesAndOtherFormatsAre404LikeConfluence() throws Exception {
        assertConfluenceError(get(BASE + "4711"), 404, "No content found");
        assertConfluenceError(get(BASE + "102"), 404, "Confluence format");
    }

    @Test
    void aClientWithoutGrantGetsNothing() throws Exception {
        assertConfluenceError(get("/api/compat/confluence/nope/rest/api/content/101"), 403, "Not permitted");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri(BASE + "101")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("Seite 101");
    }

    @Test
    void writingIsNotOffered() throws Exception {
        for (String method : new String[]{"PUT", "POST", "DELETE"}) {
            HttpResponse<String> response = http.send(request(BASE + "101")
                    .method(method, HttpRequest.BodyPublishers.ofString("{}")).header("Content-Type",
                            "application/json").build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(method).isEqualTo(405);
        }
    }

    private void assertConfluenceError(HttpResponse<String> response, int status, String message) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("statusCode").asInt()).isEqualTo(status);
        assertThat(body.get("message").asString()).contains(message);
        assertThat(body.has("reason")).isTrue();
        assertThat(body.get("data").get("successful").asBoolean()).isFalse();
        assertThat(response.body()).doesNotContain("<p>");
    }

    private static PageSnapshot snapshot(String space, String pageId, String parentPageId, String title) {
        return snapshot(space, pageId, parentPageId, title, payload(pageId).getBytes(StandardCharsets.UTF_8),
                HARVEST_FORMAT);
    }

    private static String payload(String pageId) {
        return "{\"id\":\"" + pageId + "\", \"type\":\"page\",\"x\":1.50}";
    }

    private static PageSnapshot snapshot(String pageId, byte[] payload, String nativeFormat) {
        return snapshot("DEV", pageId, null, "Seite " + pageId, payload, nativeFormat);
    }

    private static PageSnapshot snapshot(String space, String pageId, String parentPageId, String title,
                                         byte[] payload, String nativeFormat) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId,
                title, space, parentPageId, "3", Instant.parse("2026-10-15T10:30:00Z"), null,
                PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new NativePayload(payload, "application/json", nativeFormat),
                Instant.parse("2026-10-16T20:00:00Z"));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(request(path).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        return http.send(request(path).build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpRequest.Builder request(String path) {
        return TestClient.authenticated(HttpRequest.newBuilder(uri(path)));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }
}
