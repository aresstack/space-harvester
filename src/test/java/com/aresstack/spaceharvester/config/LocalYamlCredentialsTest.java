package com.aresstack.spaceharvester.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Zugangsdaten direkt als Wert in einer lokalen, nicht eingecheckten {@code application-local.yml} (Profil
 * {@code local}) neben Platzhaltern: beide Formen kommen als Basic bzw. Bearer bei der Quelle an.
 */
class LocalYamlCredentialsTest {

    @TempDir
    Path config;

    @Test
    void literalAndPlaceholderCredentialsFromLocalYamlReachTheSource() throws Exception {
        FakeConfluence confluence = new FakeConfluence();
        confluence.space("DEV", "Entwicklung");
        try (FakeConfluenceServer server = new FakeConfluenceServer(confluence)) {
            String base = server.baseUrl().toString();
            Files.writeString(config.resolve("application-local.yml"), """
                    space-harvester:
                      sources:
                        - id: literal-basic
                          type: confluence
                          base-url: %1$s
                          auth:
                            type: basic
                            username: harvester
                            password: "lokales-passwort"
                          options:
                            allowInsecureHttp: true
                        - id: literal-pat
                          type: confluence
                          base-url: %1$s
                          auth:
                            type: pat
                            token: "lokaler-token"
                          options:
                            allowInsecureHttp: true
                        - id: placeholder-pat
                          type: confluence
                          base-url: %1$s
                          auth:
                            type: pat
                            token: ${LOCAL_TEST_TOKEN}
                          options:
                            allowInsecureHttp: true
                    """.formatted(base), StandardCharsets.UTF_8);

            try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                    .run("--server.port=0", "--spring.profiles.active=local",
                            "--spring.config.additional-location=file:" + config.toAbsolutePath() + "/",
                            "--LOCAL_TEST_TOKEN=token-aus-umgebung",
                            "--space-harvester.security.mode=development")) {
                String port = context.getEnvironment().getRequiredProperty("local.server.port");

                assertThat(spacesStatus(port, "literal-basic")).isEqualTo(200);
                assertThat(lastAuthorization(confluence)).isEqualTo("Basic " + Base64.getEncoder()
                        .encodeToString("harvester:lokales-passwort".getBytes(StandardCharsets.UTF_8)));
                assertThat(spacesStatus(port, "literal-pat")).isEqualTo(200);
                assertThat(lastAuthorization(confluence)).isEqualTo("Bearer lokaler-token");
                assertThat(spacesStatus(port, "placeholder-pat")).isEqualTo(200);
                assertThat(lastAuthorization(confluence)).isEqualTo("Bearer token-aus-umgebung");
            }
        }
    }

    private static int spacesStatus(String port, String sourceId) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/sources/" + sourceId + "/spaces")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static String lastAuthorization(FakeConfluence confluence) {
        List<Map<String, String>> headers = confluence.requestHeaders();
        return headers.get(headers.size() - 1).entrySet().stream()
                .filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }
}
