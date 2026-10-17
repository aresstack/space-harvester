package com.aresstack.spaceharvester.demo;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Großer Space über echtes HTTP: mehrere tausend Seiten in drei Ebenen und mehreren Wurzeln werden über alle Cursor
 * vollständig und ohne Doppelte gelistet. Jeder Stapel lädt bei der Quelle nur seinen Ausschnitt nach, statt den Space
 * je Cursor erneut von vorn zu lesen; Speicher und Anfragen wachsen also mit dem Stapel, nicht mit dem Space.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class LargeSpaceMetadataTest {

    private static final int ROOTS = 5;
    private static final int CHILDREN = 10;
    private static final int GRANDCHILDREN = 100;
    private static final int PAGES = ROOTS * (1 + CHILDREN * (1 + GRANDCHILDREN));
    private static final int LIMIT = 100;

    private static final FakeConfluence SPACE = new FakeConfluence();
    private static final Map<String, String> PARENTS = new HashMap<>();
    private static final DemoConfluence SERVER;

    static {
        SPACE.space("BIG", "Großer Space");
        for (int r = 0; r < ROOTS; r++) {
            String root = page("r" + r, null);
            for (int c = 0; c < CHILDREN; c++) {
                String child = page("r" + r + "c" + c, root);
                for (int g = 0; g < GRANDCHILDREN; g++) {
                    page("r" + r + "c" + c + "g" + g, child);
                }
            }
        }
        SPACE.homepage("BIG", "1");
        try {
            SERVER = DemoConfluence.start(SPACE);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String page(String name, String parentId) {
        String id = String.valueOf(PARENTS.size() + 1);
        SPACE.page(id, "Seite " + name, "BIG", "<p>" + name + "</p>");
        if (parentId != null) {
            SPACE.child(parentId, id);
        }
        PARENTS.put(id, parentId);
        return id;
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        TestClient.register(registry, "big");
        registry.add("space-harvester.sources[0].id", () -> "big");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
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
    void listsEveryPageOfALargeSpaceExactlyOnceInBoundedBatches() throws Exception {
        Map<String, String> listed = new HashMap<>();
        String cursor = "";
        int batches = 0;
        do {
            JsonNode body = metadata(cursor);
            JsonNode pages = body.get("pages");
            assertThat(pages.size()).isLessThanOrEqualTo(LIMIT);
            for (JsonNode page : pages) {
                String parent = page.get("parentPageId").isNull() ? null : page.get("parentPageId").asString();
                assertThat(listed.put(page.get("pageId").asString(), parent))
                        .as("Seite %s doppelt", page.get("pageId").asString()).isNull();
            }
            cursor = body.get("nextCursor").isNull() ? "" : body.get("nextCursor").asString();
            assertThat(body.get("complete").asBoolean()).isEqualTo(cursor.isEmpty());
            batches++;
        } while (!cursor.isEmpty());

        assertThat(PAGES).isEqualTo(5055);
        assertThat(listed).hasSize(PAGES).isEqualTo(PARENTS);
        assertThat(batches).isLessThanOrEqualTo(PAGES / LIMIT + 2);

        // Jeder Stapel liest höchstens zwei Ausschnitte der Quelle, und keiner beginnt nach dem ersten wieder vorn.
        List<URI> listings = SPACE.requests().stream()
                .filter(uri -> uri.getPath().endsWith("/rest/api/content") && uri.getQuery().contains("spaceKey=BIG"))
                .toList();
        assertThat(listings.size()).isLessThanOrEqualTo(2 * batches);
        assertThat(listings.stream().filter(uri -> uri.getQuery().matches("(.*&)?start=0(&.*)?")))
                .hasSizeLessThanOrEqualTo(1);
    }

    private JsonNode metadata(String cursor) throws IOException, InterruptedException {
        String path = "/api/v1/sources/big/spaces/BIG/pages/metadata?limit=" + LIMIT
                + (cursor.isEmpty() ? "" : "&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8));
        HttpResponse<String> response = http.send(TestClient.authenticated(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + environment.getProperty("local.server.port") + path)))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return json.readTree(response.body());
    }
}
