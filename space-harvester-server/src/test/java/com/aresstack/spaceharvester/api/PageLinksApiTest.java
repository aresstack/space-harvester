package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.persistence.lucene.LucenePageCache;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.function.Predicate;
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
 * Verzeichnis der Verweise im Dienst (Paket V1) über echtes HTTP: die Rückrichtung in {@code /links} mit Stand und die
 * Verweiskanten des Strukturgraphen. Nur erfundene Daten.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageLinksApiTest {

    /** Unter build/, nicht gelöscht: der Spring-Kontext schließt seine Indizes erst am Ende der Testläufe. */
    private static final Path CACHE = Path.of("build", "page-links-api-test", java.util.UUID.randomUUID().toString());
    private static final String START = "<p><ac:link><ri:page ri:content-title=\"Ziel\" /></ac:link></p>";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", START);
        CONFLUENCE.page("101", "Ziel", "DEV", "<p>Z</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.homepage("DEV", "100");
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.cache.directory", () -> CACHE.toAbsolutePath().toString());
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Autowired
    private LucenePageCache cache;

    @Test
    void theBackwardDirectionAndTheLinkEdgesComeFromTheStoredPages() throws Exception {
        cache.save(storage("100", "Start", START));
        cache.save(storage("101", "Ziel", "<p>Z</p>"));

        // Das Verzeichnis erfasst auf seinem eigenen Thread; die Antwort folgt, sobald es den Commit gesehen hat.
        JsonNode links = await("/api/v1/sources/wiki/pages/101/links",
                body -> body.get("incoming").size() == 1 && body.get("indexedPages").asInt() == 2);
        assertThat(links.get("incoming").get(0).get("pageId").asString()).isEqualTo("100");
        assertThat(links.get("incoming").get(0).get("title").asString()).isEqualTo("Start");
        assertThat(links.get("incomingSupported").asBoolean()).isFalse();
        assertThat(links.get("knownPages").asInt()).isEqualTo(2);

        JsonNode target = await("/api/v1/sources/wiki/pages/101/connections?kind=linked-from",
                body -> body.get("edges").size() == 1);
        assertThat(target.get("edges").get(0).get("target").get("pageId").asString()).isEqualTo("100");
        assertThat(target.get("coverage").get("linked-from").asString()).isNotEqualTo("unavailable");

        JsonNode origin = await("/api/v1/sources/wiki/pages/100/connections?kind=links-to",
                body -> body.get("edges").size() == 1);
        assertThat(origin.get("edges").get(0).get("target").get("pageId").asString()).isEqualTo("101");
        assertThat(origin.get("coverage").get("links-to").asString()).isNotEqualTo("unavailable");
    }

    private static PageSnapshot storage(String pageId, String title, String html) {
        PageMetadata metadata = new PageMetadata(pageId, "confluence:wiki.example.org/page/" + pageId, title, "DEV",
                "101".equals(pageId) ? "100" : null, "1", Instant.parse("2026-11-01T08:00:00Z"),
                URI.create("https://wiki.example.org/" + pageId), PageMetadata.sorted(Map.of()));
        String body = "{\"body\":{\"storage\":{\"value\":\"" + html.replace("\"", "\\\"") + "\"}}}";
        return PageSnapshot.of("wiki", metadata, new NativePayload(body.getBytes(StandardCharsets.UTF_8),
                "application/json", "confluence-rest-content+json"), Instant.parse("2026-11-01T08:00:00Z"));
    }

    /** Wiederholt die Anfrage, bis die Antwort passt; höchstens zehn Sekunden. */
    private JsonNode await(String path, Predicate<JsonNode> ready) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        JsonNode body = null;
        while (System.nanoTime() < end) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                    + environment.getProperty("local.server.port") + path))
                    .header("Authorization", "Bearer " + TestClient.TOKEN).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            body = json.readTree(response.body());
            if (ready.test(body)) {
                return body;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Keine passende Antwort auf " + path + ": " + body);
    }
}
