package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static com.aresstack.spaceharvester.application.query.InMemorySearchIndex.entry;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemorySearchIndex;
import com.aresstack.spaceharvester.application.query.SearchIndexState;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
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

/** Titel- und Metadatensuche über echtes HTTP, gegen den {@link InMemorySearchIndex}. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SearchApiTest {

    private static final String DEV_ONLY_TOKEN = "dev-only-token-5c2e";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        // Zweite Quelle mit denselben Seiten-IDs und Spaces für die globale Suche (#114).
        registry.add("space-harvester.sources[1].id", () -> "wiki2");
        registry.add("space-harvester.sources[1].type", () -> "confluence");
        registry.add("space-harvester.sources[1].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki", "wiki2");
        // Zweiter Client nur mit metadata auf DEV.
        registry.add("space-harvester.security.clients[1].id", () -> "dev-only");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(DEV_ONLY_TOKEN));
        registry.add("space-harvester.security.clients[1].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[1].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[1].grants[0].access", () -> "metadata");
    }

    @TestConfiguration
    static class Index {

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
    private InMemorySearchIndex index;

    @BeforeEach
    void fillIndex() {
        index.clear();
        List<InMemorySearchIndex.Entry> entries = new ArrayList<>();
        for (String id : new String[]{"101", "102", "103"}) {
            entries.add(entry(page("wiki", "DEV", id, null), confirmed("g1")));
        }
        entries.add(entry(page("wiki", "OPS", "900", null), confirmed("g7")));
        index.publish("wiki", new SearchIndexState("i1", Instant.parse("2026-10-16T20:10:00Z")), entries);
        index.publish("wiki2", new SearchIndexState("j1", Instant.parse("2026-10-16T20:20:00Z")), List.of(
                entry(page("wiki2", "DEV", "101", null), confirmed("h1")),
                entry(page("wiki2", "OPS", "104", null), confirmed("h1"))));
    }

    @Test
    void searchReturnsListFieldsIndexStateAndCacheStatePerHit() throws Exception {
        HttpResponse<String> response = get("/api/v1/search?sourceId=wiki&title=seite&spaceKey=DEV&limit=2"
                + "&sort=pageId");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("origin").asString()).isEqualTo("cache");
        assertThat(body.get("index").get("generation").asString()).isEqualTo("i1");
        assertThat(body.get("index").get("committedAt").asString()).isEqualTo("2026-10-16T20:10:00Z");
        assertThat(body.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");
        JsonNode hit = body.get("hits").get(0);
        assertThat(hit.get("page").get("pageId").asString()).isEqualTo("101");
        assertThat(hit.get("page").get("title").asString()).isEqualTo("Seite 101");
        assertThat(hit.get("page").get("rawSha256").asString()).startsWith("sha256:");
        assertThat(hit.get("page").has("data")).isFalse();
        assertThat(hit.get("cache").get("generation").asString()).isEqualTo("g1");
        assertThat(hit.get("cache").get("state").asString()).isEqualTo("confirmed");
        assertThat(body.get("complete").asBoolean()).isFalse();

        JsonNode next = json.readTree(get("/api/v1/search?sourceId=wiki&title=seite&spaceKey=DEV&limit=2&sort=pageId"
                + "&cursor=" + body.get("nextCursor").asString()).body());
        assertThat(next.get("hits").get(0).get("page").get("pageId").asString()).isEqualTo("103");
        assertThat(next.get("complete").asBoolean()).isTrue();
        assertThat(next.get("nextCursor").isNull()).isTrue();
    }

    @Test
    void keywordSearchNamesUnreachableSourcesInsteadOfFailing() throws Exception {
        HttpResponse<String> response = get("/api/v1/search?text=Nachtlauf&spaceKey=DEV");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("scope").asString()).isEqualTo("global");
        assertThat(body.get("origin").asString()).isEqualTo("source");
        assertThat(body.get("hits")).isEmpty();
        assertThat(body.get("sources")).extracting(source -> source.get("sourceId").asString() + ":"
                + source.get("status").asString()).containsExactlyInAnyOrder("wiki:unavailable", "wiki2:unavailable");
        assertThat(body.get("complete").asBoolean()).isFalse();
        assertProblem(get("/api/v1/search?text=Nachtlauf&title=seite"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?text=Nachtlauf&sort=title"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?text=+"), 400, "invalid-request");
    }

    @Test
    void invalidParametersAre400() throws Exception {
        assertProblem(get("/api/v1/search?sourceId=wiki&sort=relevance"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&modifiedFrom=gestern"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&limit=101"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&title=a+b+c+d+e+f+g+h+i"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?limit=0"), 400, "invalid-request");
        String cursor = json.readTree(get("/api/v1/search?sourceId=wiki&limit=1").body()).get("nextCursor")
                .asString();
        assertProblem(get("/api/v1/search?sourceId=wiki&limit=1&title=seite&cursor=" + cursor), 400,
                "invalid-request");
    }

    @Test
    void relevanceWithoutAModelKeepsTitleHitsInOneBatch() throws Exception {
        HttpResponse<String> response = get("/api/v1/search?sourceId=wiki&title=seite&sort=relevance&limit=2");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("reranker").asString()).isEqualTo("none");
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("nextCursor").isNull()).isTrue();
        assertThat(body.get("hits").valueStream().map(hit -> hit.get("page").get("pageId").asString()))
                .containsExactly("101", "102");
        assertThat(body.get("hits").get(0).has("relevance")).isFalse();

        assertProblem(get("/api/v1/search?sourceId=wiki&sort=relevance&q=Betrieb"), 503,
                "semantic-search-unavailable");
        assertProblem(get("/api/v1/search?sourceId=wiki&q=Betrieb"), 400, "invalid-request");
        assertProblem(get("/api/v1/search?sourceId=wiki&title=seite&sort=relevance&cursor=x"), 400,
                "invalid-request");
    }

    @Test
    void theOtherSortsKeepTheirResponseShape() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/search?sourceId=wiki&title=seite").body());

        assertThat(body.has("reranker")).isFalse();
        assertThat(body.get("hits").get(0).has("relevance")).isFalse();
    }

    @Test
    void replacedIndexGenerationGives409() throws Exception {
        String cursor = json.readTree(get("/api/v1/search?sourceId=wiki&limit=1").body()).get("nextCursor")
                .asString();
        index.publish("wiki", new SearchIndexState("i2", Instant.parse("2026-10-16T21:00:00Z")), List.of());

        assertProblem(get("/api/v1/search?sourceId=wiki&limit=1&cursor=" + cursor), 409, "cache-generation-changed");
    }

    @Test
    void hitsAreLimitedToReleasedSpaces() throws Exception {
        JsonNode body = json.readTree(getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceId=wiki&sort=pageId").body());
        assertThat(body.get("hits").valueStream().map(hit -> hit.get("page").get("pageId").asString()))
                .containsExactly("101", "102", "103");
        assertThat(body.toString()).doesNotContain("900").doesNotContain("OPS");

        assertProblem(getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceId=wiki&spaceKey=OPS"), 403, "access-denied");
        assertProblem(get("/api/v1/search?sourceId=nope"), 403, "access-denied");
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(uri("/api/v1/search?sourceId=wiki"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
        assertThat(anonymous.body()).doesNotContain("Seite");
    }

    @Test
    void withoutSourceIdTheSearchIsGlobalSortedAndPagedAcrossSources() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/search?title=seite&sort=pageId&limit=3").body());

        assertThat(body.get("scope").asString()).isEqualTo("global");
        assertThat(body.get("sourceId").isNull()).isTrue();
        assertThat(body.get("index").get("generation").asString()).isNotIn("i1", "j1");
        assertThat(body.get("index").get("committedAt").asString()).isEqualTo("2026-10-16T20:20:00Z");
        List<String> keys = new ArrayList<>(keys(body));
        assertThat(keys).containsExactly("wiki/101", "wiki2/101", "wiki/102");
        assertThat(body.get("hits").get(1).get("cache").get("generation").asString()).isEqualTo("h1");

        JsonNode next = body;
        while (!next.get("complete").asBoolean()) {
            next = json.readTree(get("/api/v1/search?title=seite&sort=pageId&limit=3&cursor="
                    + next.get("nextCursor").asString()).body());
            if (next != body) {
                keys.addAll(keys(next));
            }
        }
        assertThat(keys).containsExactly("wiki/101", "wiki2/101", "wiki/102", "wiki/103", "wiki2/104", "wiki/900");

        // Mit sourceId bleibt die Antwort die einer Quelle, jetzt mit scope und sourceId je Treffer.
        JsonNode single = json.readTree(get("/api/v1/search?sourceId=wiki2&sort=pageId").body());
        assertThat(single.get("scope").asString()).isEqualTo("source");
        assertThat(single.get("sourceId").asString()).isEqualTo("wiki2");
        assertThat(keys(single)).containsExactly("wiki2/101", "wiki2/104");
    }

    @Test
    void aGlobalSearchShowsOnlyReleasedSourcesAndSpacesWithoutRefusing() throws Exception {
        HttpResponse<String> response = getAs(DEV_ONLY_TOKEN, "/api/v1/search?sort=pageId");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(keys(body)).containsExactly("wiki/101", "wiki/102", "wiki/103");
        assertThat(body.toString()).doesNotContain("wiki2").doesNotContain("900").doesNotContain("OPS");

        // spaceKey=OPS global: kein 403, nur nichts Sichtbares.
        HttpResponse<String> ops = getAs(DEV_ONLY_TOKEN, "/api/v1/search?spaceKey=OPS");
        assertThat(ops.statusCode()).isEqualTo(200);
        assertThat(json.readTree(ops.body()).get("hits").isEmpty()).isTrue();
        assertThat(json.readTree(ops.body()).get("index").isNull()).isTrue();
        assertProblem(getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceId=wiki2"), 403, "access-denied");
    }

    @Test
    void sourceIdsRestrictsTheGlobalSearchToTheListedSources() throws Exception {
        JsonNode repeated = json.readTree(get("/api/v1/search?sourceIds=wiki2&sourceIds=unbekannt&sort=pageId").body());
        assertThat(repeated.get("scope").asString()).isEqualTo("global");
        assertThat(repeated.get("sourceId").isNull()).isTrue();
        assertThat(keys(repeated)).containsExactly("wiki2/101", "wiki2/104");
        JsonNode commas = json.readTree(get("/api/v1/search?sourceIds=wiki,wiki2&sort=pageId&limit=100").body());
        assertThat(keys(commas)).hasSize(6);

        // Nicht freigegebene Quellen der Liste fallen still weg, wie bei der globalen Suche.
        HttpResponse<String> hidden = getAs(DEV_ONLY_TOKEN, "/api/v1/search?sourceIds=wiki2");
        assertThat(hidden.statusCode()).isEqualTo(200);
        assertThat(json.readTree(hidden.body()).get("hits").isEmpty()).isTrue();
        assertThat(json.readTree(hidden.body()).get("index").isNull()).isTrue();

        assertProblem(get("/api/v1/search?sourceId=wiki&sourceIds=wiki2"), 400, "invalid-request");
        // Der cursor gehört zur Liste: ohne sie ist es eine andere Suche.
        String cursor = json.readTree(get("/api/v1/search?sourceIds=wiki,wiki2&limit=1").body()).get("nextCursor")
                .asString();
        assertProblem(get("/api/v1/search?limit=1&cursor=" + cursor), 400, "invalid-request");
    }

    @Test
    void aChangedSourceEndsTheGlobalPaginationWith409() throws Exception {
        String cursor = json.readTree(get("/api/v1/search?limit=1").body()).get("nextCursor").asString();
        index.publish("wiki2", new SearchIndexState("j2", Instant.parse("2026-10-16T21:00:00Z")), List.of());

        assertProblem(get("/api/v1/search?limit=1&cursor=" + cursor), 409, "cache-generation-changed");
        assertProblem(get("/api/v1/search?sourceId=wiki&limit=1&cursor=" + cursor), 400, "invalid-request");
    }

    @Test
    void aGlobalSearchWithoutIndexedSourcesIsEmptyNotACacheMiss() throws Exception {
        index.clear();

        HttpResponse<String> response = get("/api/v1/search");
        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("hits").isEmpty()).isTrue();
        assertThat(body.get("complete").asBoolean()).isTrue();
        assertThat(body.get("index").isNull()).isTrue();
    }

    @Test
    void aGlobalRelevanceSearchWithoutAModelKeepsTitleHitsOfAllSources() throws Exception {
        JsonNode body = json.readTree(get("/api/v1/search?title=seite&sort=relevance&limit=10").body());

        assertThat(body.get("scope").asString()).isEqualTo("global");
        assertThat(body.get("reranker").asString()).isEqualTo("none");
        assertThat(keys(body)).contains("wiki/101", "wiki2/101").doesNotHaveDuplicates().hasSize(6);
        assertProblem(get("/api/v1/search?sort=relevance&q=Betrieb"), 503, "semantic-search-unavailable");
    }

    @Test
    void sourceWithoutIndexIsCacheMiss() throws Exception {
        index.clear();

        assertProblem(get("/api/v1/search?sourceId=wiki"), 404, "cache-miss");
    }

    @Test
    void searchIsDocumentedInOpenApi() throws Exception {
        JsonNode search = json.readTree(get("/v3/api-docs").body()).get("paths").get("/api/v1/search").get("get");

        assertThat(search.get("responses").has("409")).isTrue();
        assertThat(search.get("parameters").valueStream().map(parameter -> parameter.get("name").asString()))
                .contains("sourceId", "spaceKey", "title", "modifiedFrom", "modifiedBefore", "sort", "cursor");
    }

    private static List<String> keys(JsonNode body) {
        return body.get("hits").valueStream()
                .map(hit -> hit.get("sourceId").asString() + "/" + hit.get("page").get("pageId").asString()).toList();
    }

    private void assertProblem(HttpResponse<String> response, int status, String type) throws Exception {
        assertThat(response.statusCode()).isEqualTo(status);
        assertThat(response.headers().firstValue("Content-Type")).contains("application/problem+json");
        assertThat(json.readTree(response.body()).get("type").asString())
                .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + type);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(TestClient.authenticated(HttpRequest.newBuilder(uri(path))).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getAs(String token, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(uri(path)).header("Authorization", "Bearer " + token).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
