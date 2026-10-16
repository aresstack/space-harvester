package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Seitenmetadaten eines Space über echtes HTTP gegen eine Fake-Confluence: vollständig über mehrere Cursor und
 * mehrere Wurzelseiten, ohne Inhalte, mit stabilem {@code metadataHash} und unterscheidbaren Fehlern.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageMetadataApiTest {

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final FakeConfluence FLAKY_CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer FLAKY;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.page("101", "Architektur", "DEV", "<p>A</p>");
        CONFLUENCE.page("102", "Schnittstellen", "DEV", "<p>S</p>");
        CONFLUENCE.page("103", "Betrieb", "DEV", "<p>B</p>");
        // Zweite Wurzelseite außerhalb des Baums der Startseite, mit eigenem Kind.
        CONFLUENCE.page("200", "Archiv", "DEV", "<p>Alt</p>");
        CONFLUENCE.page("201", "Altes Konzept", "DEV", "<p>K</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.child("101", "102");
        CONFLUENCE.child("100", "103");
        CONFLUENCE.child("200", "201");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.space("OPS", "Betrieb");
        CONFLUENCE.page("300", "Runbook", "OPS", "<p>R</p>");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
            FLAKY_CONFLUENCE.space("DEV", "Entwicklung");
            FLAKY_CONFLUENCE.page("1", "Eins", "DEV", "<p>1</p>");
            FLAKY_CONFLUENCE.page("2", "Zwei", "DEV", "<p>2</p>");
            FLAKY_CONFLUENCE.page("3", "Drei", "DEV", "<p>3</p>");
            FLAKY = new FakeConfluenceServer(FLAKY_CONFLUENCE);
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
        registry.add("space-harvester.sources[1].id", () -> "flaky-wiki");
        registry.add("space-harvester.sources[1].type", () -> "confluence");
        registry.add("space-harvester.sources[1].base-url", () -> FLAKY.baseUrl().toString());
        registry.add("space-harvester.sources[1].options.connectTimeoutMillis", () -> "2000");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
        FLAKY.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void listsAllPagesOfASpaceAcrossCursorsAndRootsWithoutContent() throws Exception {
        Map<String, JsonNode> pages = new LinkedHashMap<>();
        String cursor = "";
        int batches = 0;
        JsonNode body;
        do {
            HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/DEV/pages/metadata?limit=2"
                    + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)));
            assertThat(response.statusCode()).isEqualTo(200);
            body = json.readTree(response.body());
            assertThat(body.get("pages").size()).isLessThanOrEqualTo(2);
            body.get("pages").forEach(page -> pages.put(page.get("pageId").asString(), page));
            cursor = body.get("nextCursor").isNull() ? "" : body.get("nextCursor").asString();
            assertThat(body.get("complete").asBoolean()).isEqualTo(cursor.isEmpty());
            batches++;
        } while (!cursor.isEmpty());

        assertThat(batches).isGreaterThanOrEqualTo(3);
        assertThat(pages.keySet()).containsExactlyInAnyOrder("100", "101", "102", "103", "200", "201");
        assertThat(body.get("origin").asString()).isEqualTo("live");
        assertThat(body.get("metadataHashSchema").asString()).isEqualTo("page-metadata/v1");

        JsonNode schnittstellen = pages.get("102");
        assertThat(schnittstellen.get("title").asString()).isEqualTo("Schnittstellen");
        assertThat(schnittstellen.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(schnittstellen.get("parentPageId").asString()).isEqualTo("101");
        assertThat(schnittstellen.get("version").asString()).isNotEmpty();
        assertThat(schnittstellen.get("modifiedAt").isNull()).isFalse();
        assertThat(schnittstellen.get("metadataHash").asString()).matches("sha256:[0-9a-f]{64}");
        assertThat(pages.get("200").get("parentPageId").isNull()).isTrue();
        assertThat(pages.get("201").get("parentPageId").asString()).isEqualTo("200");
        for (URI request : CONFLUENCE.requests()) {
            assertThat(request.toString()).doesNotContain("body.");
        }
    }

    @Test
    void sameMetadataGivesTheSameHashesOnTheNextRun() throws Exception {
        assertThat(hashes()).isEqualTo(hashes());
    }

    @Test
    void unknownSpaceIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/NOPE/pages/metadata");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("problems/source-object-not-found");
    }

    @Test
    void cursorOfAnotherSpaceIsRejected() throws Exception {
        String devCursor = json.readTree(get("/api/v1/sources/wiki/spaces/DEV/pages/metadata?limit=1").body())
                .get("nextCursor").asString();

        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/OPS/pages/metadata?limit=1&cursor="
                + URLEncoder.encode(devCursor, StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("problems/source-unsupported");
    }

    @Test
    void limitOutsideOneToHundredIsRejected() throws Exception {
        for (String limit : List.of("0", "101", "-5")) {
            HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/DEV/pages/metadata?limit=" + limit);

            assertThat(response.statusCode()).as(limit).isEqualTo(400);
            assertThat(response.body()).contains("problems/invalid-request");
        }
    }

    @Test
    void failureWhileLoadingALaterBatchIsAnErrorNotAnEmptyOrCompleteList() throws Exception {
        JsonNode first = json.readTree(get("/api/v1/sources/flaky-wiki/spaces/DEV/pages/metadata?limit=1").body());
        assertThat(first.get("complete").asBoolean()).isFalse();
        FLAKY.close();

        HttpResponse<String> response = get("/api/v1/sources/flaky-wiki/spaces/DEV/pages/metadata?limit=1&cursor="
                + URLEncoder.encode(first.get("nextCursor").asString(), StandardCharsets.UTF_8));

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("problems/source-unavailable").doesNotContain("\"pages\"");
    }

    private List<String> hashes() throws Exception {
        List<String> hashes = new ArrayList<>();
        json.readTree(get("/api/v1/sources/wiki/spaces/DEV/pages/metadata").body()).get("pages")
                .forEach(page -> hashes.add(page.get("pageId").asString() + "=" + page.get("metadataHash").asString()));
        return hashes;
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
