package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

/**
 * Metadaten einer einzelnen Seite über die kanonische Route ohne Space-Key, über echtes HTTP gegen eine
 * Fake-Confluence: dieselben Werte wie in der Liste des Space, ohne Inhalt, auch nach Verschieben der Seite.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SinglePageMetadataApiTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("OPS", "Betrieb");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.page("101", "Architektur", "DEV", "<p>A</p>").labels("b", "a");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("102", "Wandert", "DEV", "<p>W</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.connectTimeoutMillis", () -> "2000");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void singlePageHasTheSameMetadataAndHashesAsTheSpaceListingWithoutContent() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/101/metadata");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("sourceId").asString()).isEqualTo("wiki");
        assertThat(body.get("origin").asString()).isEqualTo("live");
        assertThat(body.get("retrievedAt").isNull()).isFalse();
        assertThat(body.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");
        assertThat(body.get("attributesHashSchema").asString()).isEqualTo("page-attributes/v1");
        JsonNode page = body.get("page");
        assertThat(page.get("pageId").asString()).isEqualTo("101");
        assertThat(page.get("title").asString()).isEqualTo("Architektur");
        assertThat(page.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(page.get("parentPageId").asString()).isEqualTo("100");
        assertThat(page.get("attributes").get("confluence.labels").asString()).isEqualTo("a,b");
        assertThat(page.get("attributes").get("confluence.createdAt").asString()).isNotEmpty();
        assertThat(page.get("attributes").get("confluence.createdBy").asString()).isEqualTo("autorin");
        assertThat(page.toString()).doesNotContain("<p>");

        JsonNode listed = listed("DEV", "101");
        assertThat(page).isEqualTo(listed);
        for (URI request : CONFLUENCE.requests()) {
            assertThat(request.toString()).doesNotContain("body.");
        }
    }

    @Test
    void movedPageKeepsItsPageIdAndShowsItsNewSpace() throws Exception {
        String before = json.readTree(get("/api/v1/sources/wiki/pages/102/metadata").body())
                .get("page").get("metadataHash").asString();
        CONFLUENCE.move("102", "OPS");

        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/102/metadata");

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode page = json.readTree(response.body()).get("page");
        assertThat(page.get("pageId").asString()).isEqualTo("102");
        assertThat(page.get("spaceKey").asString()).isEqualTo("OPS");
        assertThat(page.get("parentPageId").isNull()).isTrue();
        assertThat(page.get("metadataHash").asString()).isNotEqualTo(before);
        assertThat(page).isEqualTo(listed("OPS", "102"));
    }

    @Test
    void unknownPageIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/999/metadata");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("problems/source-object-not-found");
    }

    @Test
    void pageIdOfAnUnknownFormIsRejected() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/pages/abc/metadata");

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("problems/source-unsupported").doesNotContain("Keine Seiten-ID");
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/nope/pages/101/metadata");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("problems/source-not-found");
    }

    private JsonNode listed(String spaceKey, String pageId) throws Exception {
        for (JsonNode page : json.readTree(get("/api/v1/sources/wiki/spaces/" + spaceKey + "/pages/metadata")
                .body()).get("pages")) {
            if (page.get("pageId").asString().equals(pageId)) {
                return page;
            }
        }
        throw new AssertionError("Seite " + pageId + " fehlt in der Liste von " + spaceKey);
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
