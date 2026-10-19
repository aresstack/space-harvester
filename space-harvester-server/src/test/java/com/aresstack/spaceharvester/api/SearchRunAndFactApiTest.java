package com.aresstack.spaceharvester.api;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.EmptyConfluence;
import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Suchlauf-Protokoll und Fakten über HTTP (#182): Läufe nur für den eigenen Aufrufer und
 * {@code manage-configuration}, Fakten nur lesbarer Seiten, auch in der Suche über alle Seiten.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SearchRunAndFactApiTest {

    private static final String OTHER_TOKEN = "other-token-81ad3e";
    private static final String ADMIN_TOKEN = "admin-token-2b7f90";
    private static final String DEV_TOKEN = "dev-reader-token-6e14c2";
    private static final String METADATA_TOKEN = "metadata-only-token-a93d";
    private static final String SECRET_QUERY = "Geheimprojekt Nachtlauf";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", EmptyConfluence::baseUrl);
        TestClient.register(registry, "wiki");
        // Anreicherungen neu berechnen verlangt zusätzlich sync.
        registry.add("space-harvester.security.clients[0].grants[1].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[1].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[0].grants[1].access", () -> "sync");
        client(registry, 1, "other", OTHER_TOKEN, "*", "metadata,content");
        client(registry, 2, "admin", ADMIN_TOKEN, null, null);
        registry.add("space-harvester.security.clients[2].manage-configuration", () -> "true");
        client(registry, 3, "dev-reader", DEV_TOKEN, "DEV", "metadata,content");
        client(registry, 4, "metadata-only", METADATA_TOKEN, "*", "metadata");
    }

    private static void client(DynamicPropertyRegistry registry, int index, String id, String token, String space,
                               String access) {
        String prefix = "space-harvester.security.clients[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "token-sha256", () -> "sha256:" + sha256(token));
        if (space != null) {
            registry.add(prefix + "grants[0].source", () -> "wiki");
            registry.add(prefix + "grants[0].spaces[0]", () -> space);
            registry.add(prefix + "grants[0].access", () -> access);
        }
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
    void aRunIsReadableByItsCallerAndAdministratorsOnly() throws Exception {
        JsonNode search = json.readTree(get("/api/v1/search?text=" + URLEncoder.encode(SECRET_QUERY,
                StandardCharsets.UTF_8), TestClient.TOKEN).body());
        String id = search.get("searchRun").asString();
        assertThat(id).isNotBlank();

        HttpResponse<String> own = get("/api/v1/search/runs/" + id, TestClient.TOKEN);
        assertThat(own.statusCode()).isEqualTo(200);
        JsonNode run = json.readTree(own.body()).get("run");
        assertThat(run.get("kind").asString()).isEqualTo("keyword");
        assertThat(run.get("query").asString()).isEqualTo(SECRET_QUERY);
        assertThat(run.get("sources")).extracting(source -> source.get("sourceId").asString())
                .containsExactly("wiki");
        assertThat(run.get("stages").has("delivered")).isTrue();
        assertThat(ids(get("/api/v1/search/runs", TestClient.TOKEN))).contains(id);

        // Fremder Lauf: wie bei fremden Aufträgen 404, ohne Hinweis auf Anfrage oder Kennung in der Liste.
        HttpResponse<String> foreign = get("/api/v1/search/runs/" + id, OTHER_TOKEN);
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.body()).doesNotContain("Geheimprojekt");
        HttpResponse<String> foreignList = get("/api/v1/search/runs", OTHER_TOKEN);
        assertThat(foreignList.statusCode()).isEqualTo(200);
        assertThat(json.readTree(foreignList.body()).get("enabled").asBoolean()).isTrue();
        assertThat(ids(foreignList)).doesNotContain(id);
        assertThat(foreignList.body()).doesNotContain("Geheimprojekt");

        // manage-configuration sieht alle Läufe.
        HttpResponse<String> admin = get("/api/v1/search/runs/" + id, ADMIN_TOKEN);
        assertThat(admin.statusCode()).isEqualTo(200);
        assertThat(json.readTree(admin.body()).get("run").get("query").asString()).isEqualTo(SECRET_QUERY);
        assertThat(ids(get("/api/v1/search/runs", ADMIN_TOKEN))).contains(id);
    }

    @Test
    void unknownAndInvalidRunsAreNotFoundAndLimitsAreChecked() throws Exception {
        assertThat(get("/api/v1/search/runs/unbekannt", ADMIN_TOKEN).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/search/runs/..%2Fx", ADMIN_TOKEN).statusCode()).isIn(400, 404);
        assertThat(get("/api/v1/search/runs?limit=0", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/search/runs?limit=501", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/search/runs", null).statusCode()).isEqualTo(401);
    }

    @Test
    void factsOfAPageNeedItsContentAndFactsSearchSkipsUnreadablePages() throws Exception {
        publish("DEV", "701", "<p>Die API (Application Programming Interface) im Betrieb.</p>");
        publish("OPS", "702", "<p>Die API (Application Programming Interface) im Notfall.</p>");
        for (String id : List.of("701", "702")) {
            assertThat(post("/api/v1/sources/wiki/pages/" + id + "/enrichments", TestClient.TOKEN).statusCode())
                    .isEqualTo(200);
        }

        HttpResponse<String> sheet = get("/api/v1/sources/wiki/pages/701/facts?type=ACRONYM_DEFINITION", DEV_TOKEN);
        assertThat(sheet.statusCode()).isEqualTo(200);
        JsonNode definition = json.readTree(sheet.body()).get("facts").get(0);
        assertThat(definition.get("pageId").asString()).isEqualTo("701");
        assertThat(definition.get("freshness").asString()).isEqualTo("current");
        assertThat(definition.get("arguments")).extracting(argument -> argument.get("value").asString())
                .contains("api", "application programming interface");

        // Nicht lesbare Seite: keine Fakten, kein Inhalt.
        HttpResponse<String> hidden = get("/api/v1/sources/wiki/pages/702/facts", DEV_TOKEN);
        assertThat(hidden.statusCode()).isIn(403, 404);
        assertThat(hidden.body()).doesNotContain("Notfall", "\"facts\"", "API");
        HttpResponse<String> metadataOnly = get("/api/v1/sources/wiki/pages/701/facts", METADATA_TOKEN);
        assertThat(metadataOnly.statusCode()).isEqualTo(403);
        assertThat(metadataOnly.body()).doesNotContain("\"facts\"");

        // Suche über alle Seiten: der volle Client sieht beide, DEV nur 701, metadata allein nichts.
        assertThat(factPages("/api/v1/facts?type=ACRONYM_DEFINITION", TestClient.TOKEN))
                .containsExactlyInAnyOrder("701", "702");
        assertThat(factPages("/api/v1/facts?argument=SHORT_FORM:API", DEV_TOKEN)).containsOnly("701").isNotEmpty();
        assertThat(factPages("/api/v1/facts?type=ACRONYM_DEFINITION&spaceKey=OPS", DEV_TOKEN)).isEmpty();
        assertThat(factPages("/api/v1/facts?type=ACRONYM_DEFINITION", METADATA_TOKEN)).isEmpty();
        assertThat(factPages("/api/v1/facts?type=ACRONYM_DEFINITION", ADMIN_TOKEN)).isEmpty();
        HttpResponse<String> walk = get("/api/v1/facts?type=ACRONYM_CANDIDATE&limit=1", DEV_TOKEN);
        JsonNode first = json.readTree(walk.body());
        assertThat(first.get("facts")).hasSize(1);
        String cursor = first.get("nextCursor").isNull() ? null : first.get("nextCursor").asString();
        if (cursor != null) {
            assertThat(factPages("/api/v1/facts?type=ACRONYM_CANDIDATE&limit=1&cursor=" + cursor, DEV_TOKEN))
                    .doesNotContain("702");
        }
    }

    @Test
    void invalidFactQueriesAreRefused() throws Exception {
        assertThat(get("/api/v1/facts", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/facts?type=NOPE", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/facts?argument=ohne-doppelpunkt", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/facts?type=MENTION&cursor=xyz", TestClient.TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/facts?type=MENTION", null).statusCode()).isEqualTo(401);
    }

    @Test
    void theRoutesAreDocumented() throws Exception {
        JsonNode paths = json.readTree(get("/v3/api-docs", TestClient.TOKEN).body()).get("paths");

        assertThat(paths.has("/api/v1/search/runs")).isTrue();
        assertThat(paths.has("/api/v1/search/runs/{id}")).isTrue();
        assertThat(paths.has("/api/v1/facts")).isTrue();
        assertThat(paths.has("/api/v1/sources/{sourceId}/pages/{pageId}/facts")).isTrue();
    }

    private List<String> ids(HttpResponse<String> response) throws Exception {
        List<String> ids = new ArrayList<>();
        json.readTree(response.body()).get("runs").forEach(run -> ids.add(run.get("id").asString()));
        return ids;
    }

    private List<String> factPages(String path, String token) throws Exception {
        HttpResponse<String> response = get(path, token);
        assertThat(response.statusCode()).as(path).isEqualTo(200);
        List<String> pages = new ArrayList<>();
        json.readTree(response.body()).get("facts").forEach(fact -> pages.add(fact.get("pageId").asString()));
        return pages;
    }

    private void publish(String spaceKey, String pageId, String storage) {
        String payload = "{\"id\":\"" + pageId + "\",\"type\":\"page\",\"body\":{\"storage\":{\"value\":\""
                + storage.replace("\"", "\\\"") + "\",\"representation\":\"storage\"}}}";
        PageSnapshot snapshot = page("wiki", spaceKey, pageId, null, payload.getBytes(StandardCharsets.UTF_8));
        cache.publish("wiki", spaceKey, confirmed("g-" + spaceKey), List.of(snapshot));
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
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
