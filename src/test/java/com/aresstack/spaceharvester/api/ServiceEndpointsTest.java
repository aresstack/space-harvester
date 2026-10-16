package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Startet den Dienst ohne Confluence-Zugang und prüft, dass API, OpenAPI, Swagger UI und Health erreichbar sind.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ServiceEndpointsTest {

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    @Autowired
    private Environment environment;

    @Test
    void serviceInfoIsServedUnderApiV1() throws Exception {
        HttpResponse<String> response = get("/api/v1/info");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(response.body())
                .contains("\"name\":\"space-harvester\"")
                .contains("\"apiVersion\":\"v1\"");
    }

    @Test
    void openApiDocumentDescribesApiV1() throws Exception {
        HttpResponse<String> response = get("/v3/api-docs");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("Space Harvester API")
                .contains("/api/v1/info")
                .doesNotContain("/actuator");
    }

    @Test
    void swaggerUiIsReachableUnderDocumentedEntryUrl() throws Exception {
        HttpResponse<String> response = get("/swagger-ui.html");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("Swagger UI");
    }

    @Test
    void healthIsUp() throws Exception {
        HttpResponse<String> response = get("/actuator/health");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessAndReadinessProbesAreUp() throws Exception {
        for (String probe : new String[] {"/actuator/health/liveness", "/actuator/health/readiness"}) {
            HttpResponse<String> response = get(probe);

            assertThat(response.statusCode()).as(probe).isEqualTo(200);
            assertThat(response.body()).as(probe).contains("\"status\":\"UP\"");
        }
    }

    @Test
    void actuatorInfoReportsBuild() throws Exception {
        HttpResponse<String> response = get("/actuator/info");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"build\"")
                .contains("\"artifact\":\"space-harvester\"");
    }

    @Test
    void unknownApiPathAnswersWithProblemDetails() throws Exception {
        HttpResponse<String> response = get("/api/v1/does-not-exist");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
    }

    private HttpResponse<String> get(String path) throws IOException, InterruptedException {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
