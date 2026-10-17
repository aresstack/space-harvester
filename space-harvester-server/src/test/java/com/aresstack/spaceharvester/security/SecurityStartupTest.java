package com.aresstack.spaceharvester.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.SpaceHarvesterApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** Fehlerhafte Sicherheitskonfiguration verhindert den Start; es gibt keinen offenen Rückfall. */
class SecurityStartupTest {

    private static final String HASH = ClientTokens.sha256Hex("irgendein-token");

    @Test
    void developmentModeRequiresLocalProfile() {
        assertStartFails("space-harvester.security.mode=development ist nur mit aktivem Spring-Profil 'local'",
                "--space-harvester.security.mode=development");
    }

    @Test
    void developmentModeWithLocalProfileIsOpen() throws Exception {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run("--server.port=0", "--spring.profiles.active=local",
                        "--space-harvester.security.mode=development",
                        "--space-harvester.sources[0].id=wiki",
                        "--space-harvester.sources[0].type=confluence",
                        "--space-harvester.sources[0].base-url=https://wiki.example.org")) {
            String port = context.getEnvironment().getRequiredProperty("local.server.port");
            HttpResponse<String> response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(
                    URI.create("http://localhost:" + port + "/api/v1/sources")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body()).contains("\"id\":\"wiki\"");
        }
    }

    @Test
    void plainTokenInsteadOfHashIsRejected() {
        assertStartFails("token-sha256 muss der SHA-256-Wert des Tokens",
                "--space-harvester.security.clients[0].id=etl",
                "--space-harvester.security.clients[0].token-sha256=mein-klartext-token");
    }

    @Test
    void sharedTokenIsRejected() {
        assertStartFails("verwenden dasselbe Token",
                "--space-harvester.security.clients[0].id=a",
                "--space-harvester.security.clients[0].token-sha256=" + HASH,
                "--space-harvester.security.clients[1].id=b",
                "--space-harvester.security.clients[1].token-sha256=sha256:" + HASH.toUpperCase());
    }

    @Test
    void grantForUnconfiguredSourceIsRejected() {
        assertStartFails("Client etl, Freigabe für Quelle wiki: keine Quelle mit dieser ID konfiguriert",
                client("--space-harvester.security.clients[0].grants[0].source=wiki",
                        "--space-harvester.security.clients[0].grants[0].spaces[0]=*",
                        "--space-harvester.security.clients[0].grants[0].access=metadata"));
    }

    @Test
    void grantWithoutSpacesIsRejected() {
        assertStartFails("spaces ist leer", withSource(client(
                "--space-harvester.security.clients[0].grants[0].source=wiki",
                "--space-harvester.security.clients[0].grants[0].access=metadata")));
    }

    @Test
    void wildcardNextToSpaceKeysIsRejected() {
        assertStartFails("\"*\" steht allein", withSource(client(
                "--space-harvester.security.clients[0].grants[0].source=wiki",
                "--space-harvester.security.clients[0].grants[0].spaces=*,DEV",
                "--space-harvester.security.clients[0].grants[0].access=metadata")));
    }

    @Test
    void delegationWithoutDelegatedCheckForTheSourceIsRejected() {
        assertStartFails("Client etl: delegation nennt Quelle other, für die keine delegierte Prüfung verfügbar ist",
                withSource(client("--space-harvester.security.clients[0].delegation[0]=other")));
    }

    private static String[] client(String... grants) {
        List<String> args = new ArrayList<>(List.of("--space-harvester.security.clients[0].id=etl",
                "--space-harvester.security.clients[0].token-sha256=" + HASH));
        args.addAll(List.of(grants));
        return args.toArray(String[]::new);
    }

    private static String[] withSource(String... args) {
        List<String> all = new ArrayList<>(List.of("--space-harvester.sources[0].id=wiki",
                "--space-harvester.sources[0].type=confluence",
                "--space-harvester.sources[0].base-url=https://wiki.example.org"));
        all.addAll(List.of(args));
        return all.toArray(String[]::new);
    }

    private static void assertStartFails(String message, String... args) {
        List<String> all = new ArrayList<>(List.of("--server.port=0"));
        all.addAll(List.of(args));
        assertThatThrownBy(() -> new SpringApplicationBuilder(SpaceHarvesterApplication.class)
                .run(all.toArray(String[]::new)).close())
                .rootCause()
                .isInstanceOf(InvalidSecurityConfigurationException.class)
                .hasMessageContaining(message);
    }
}
