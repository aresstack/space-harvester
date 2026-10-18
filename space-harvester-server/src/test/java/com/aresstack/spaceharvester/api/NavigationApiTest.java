package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

/** Live-Navigation über echtes HTTP gegen die Fake-Confluence (#177). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class NavigationApiTest {

    private static final String DEV_TOKEN = "navigation-dev-only-token-2b7f";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
        String base = SERVER.baseUrl().toString().replaceAll("/$", "");
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("HR", "Personal");
        CONFLUENCE.page("100", "Start", "DEV", "<p>S</p>");
        CONFLUENCE.page("102", "Zweite", "DEV", "<p>Z</p>");
        CONFLUENCE.page("101", "Erste", "DEV", "<p><a href=\"" + base + "/pages/viewpage.action?pageId=102\">Z</a>"
                + "<a href=\"" + base + "/pages/viewpage.action?pageId=200\">P</a>"
                + "<a href=\"" + base + "/pages/viewpage.action?pageId=101\">selbst</a></p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("103", "Tief", "DEV", "<p>T</p>");
        CONFLUENCE.child("101", "103");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.page("200", "Personal", "HR", "<p>P</p>");
        CONFLUENCE.homepage("HR", "200");
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        client(registry, 0, TestClient.ID, TestClient.TOKEN_SHA256, "*");
        client(registry, 1, "nur-dev", "sha256:" + sha256(DEV_TOKEN), "DEV");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        registry.add("space-harvester.sources[0].options.pageSize", () -> "1");
    }

    private static void client(DynamicPropertyRegistry registry, int index, String id, String sha, String space) {
        String client = "space-harvester.security.clients[" + index + "].";
        registry.add(client + "id", () -> id);
        registry.add(client + "token-sha256", () -> sha);
        registry.add(client + "grants[0].source", () -> "wiki");
        registry.add(client + "grants[0].spaces[0]", () -> space);
        registry.add(client + "grants[0].access", () -> "metadata,content");
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void childrenComeInTheOrderOfConfluenceBatchByBatch() throws Exception {
        JsonNode roots = body("/api/v1/sources/wiki/spaces/DEV/children", DEV_TOKEN);
        assertThat(roots.get("pages").get(0).get("pageId").asString()).isEqualTo("100");
        assertThat(roots.get("pages").get(0).get("hasChildren").asBoolean()).isTrue();

        List<String> ids = new ArrayList<>();
        List<Boolean> children = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode batch = body("/api/v1/sources/wiki/pages/100/children" + (cursor == null ? ""
                    : "?cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)), DEV_TOKEN);
            assertThat(batch.get("spaceKey").asString()).isEqualTo("DEV");
            batch.get("pages").forEach(page -> {
                ids.add(page.get("pageId").asString());
                children.add(page.get("hasChildren").asBoolean());
            });
            cursor = batch.get("nextCursor").isNull() ? null : batch.get("nextCursor").asString();
        } while (cursor != null);
        // Confluence-Reihenfolge, nicht nach ID; Stapel zu je einer Seite.
        assertThat(ids).containsExactly("102", "101");
        assertThat(children).containsExactly(false, true);
    }

    @Test
    void breadcrumbsNameTheSpace() throws Exception {
        JsonNode position = body("/api/v1/sources/wiki/pages/103/ancestors", DEV_TOKEN);

        assertThat(position.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(position.get("spaceName").asString()).isEqualTo("Entwicklung");
        assertThat(position.get("ancestors")).extracting(node -> node.get("pageId").asString())
                .containsExactly("100", "101");
        assertThat(position.get("page").get("title").asString()).isEqualTo("Tief");
    }

    @Test
    void linksShowOnlyPagesTheClientMaySee() throws Exception {
        JsonNode dev = body("/api/v1/sources/wiki/pages/101/links", DEV_TOKEN);
        assertThat(dev.get("outgoing")).extracting(node -> node.get("pageId").asString()).containsExactly("102");
        assertThat(dev.get("incomingSupported").asBoolean()).isFalse();

        JsonNode all = body("/api/v1/sources/wiki/pages/101/links", TestClient.TOKEN);
        assertThat(all.get("outgoing")).extracting(node -> node.get("title").asString())
                .containsExactly("Zweite", "Personal");
    }

    @Test
    void pagesOfOtherSpacesAreUnknown() throws Exception {
        assertThat(get("/api/v1/sources/wiki/pages/200/children", DEV_TOKEN).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sources/wiki/pages/200/ancestors", DEV_TOKEN).statusCode()).isEqualTo(404);
        assertThat(get("/api/v1/sources/wiki/spaces/HR/children", DEV_TOKEN).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/pages/100/children?cursor=fremd", DEV_TOKEN).statusCode())
                .isEqualTo(400);
    }

    @Test
    void pagesAreFoundByTitleOnlyWhereTheClientMaySeeThem() throws Exception {
        CONFLUENCE.searchResults("100", "101", "200", "103");

        JsonNode dev = body("/api/v1/sources/wiki/pages?title=RS", DEV_TOKEN);
        assertThat(dev.get("pages")).extracting(node -> node.get("pageId").asString() + "@"
                + node.get("spaceKey").asString()).containsExactly("101@DEV");
        assertThat(dev.get("truncated").asBoolean()).isFalse();

        JsonNode all = body("/api/v1/sources/wiki/pages?title=rs&limit=1", TestClient.TOKEN);
        assertThat(all.get("pages")).extracting(node -> node.get("title").asString()).containsExactly("Erste");
        assertThat(all.get("truncated").asBoolean()).isTrue();

        JsonNode hr = body("/api/v1/sources/wiki/pages?title=rs&spaceKey=HR&spaceKey=OPS", TestClient.TOKEN);
        assertThat(hr.get("pages")).extracting(node -> node.get("pageId").asString()).containsExactly("200");

        assertThat(get("/api/v1/sources/wiki/pages?title=%20", DEV_TOKEN).statusCode()).isEqualTo(400);
        assertThat(get("/api/v1/sources/wiki/pages?title=rs&limit=0", DEV_TOKEN).statusCode()).isEqualTo(400);
    }

    private JsonNode body(String path, String token) throws Exception {
        HttpResponse<String> response = get(path, token);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
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
