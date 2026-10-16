package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.ObjectMapper;

/** Ohne eingerichteten Seitencache startet der Dienst, und die Query-Routen antworten mit 503 statt leer. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "space-harvester.sources[0].id=wiki",
        "space-harvester.sources[0].type=confluence",
        "space-harvester.sources[0].base-url=http://127.0.0.1:9"})
class PageQueryWithoutCacheApiTest {

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void queryRoutesSayTheCacheIsUnavailable() throws Exception {
        for (String path : new String[]{"/api/v1/sources/wiki/spaces/DEV/pages", "/api/v1/sources/wiki/spaces/DEV/tree",
                "/api/v1/sources/wiki/pages/101"}) {
            HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + environment.getProperty("local.server.port") + path)).build(),
                    HttpResponse.BodyHandlers.ofString());

            assertThat(response.statusCode()).as(path).isEqualTo(503);
            assertThat(new ObjectMapper().readTree(response.body()).get("type").asString())
                    .isEqualTo("https://github.com/aresstack/space-harvester/problems/cache-unavailable");
        }
    }
}
