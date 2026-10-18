package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Ohne eingerichteten Seitencache und Suchindex startet der Dienst, und Query- und Suchrouten antworten mit 503 statt leer. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class PageQueryWithoutCacheApiTest {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void queryRoutesSayTheCacheIsUnavailable() throws Exception {
        for (String path : new String[]{"/api/v1/sources/wiki/spaces/DEV/pages", "/api/v1/sources/wiki/spaces/DEV/tree",
                "/api/v1/sources/wiki/pages/101", "/api/v1/search?sourceId=wiki"}) {
            HttpResponse<String> response = http.send(TestClient.authenticated(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + environment.getProperty("local.server.port") + path))).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isEqualTo(503);
            // Die Einzelseite braucht den Cache nicht, aber die Live-Prüfung (ADR 0010, Punkt 8): hier fehlt die Quelle.
            String problem = path.endsWith("/pages/101") ? "source-unavailable" : "cache-unavailable";
            assertThat(new ObjectMapper().readTree(response.body()).get("type").asString())
                    .isEqualTo("https://github.com/aresstack/space-harvester/problems/" + problem);
        }
    }
}
