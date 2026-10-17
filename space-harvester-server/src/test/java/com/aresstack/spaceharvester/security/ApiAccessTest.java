package com.aresstack.spaceharvester.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Zugriffsschutz der API gegen eine Fake-Confluence mit zwei Spaces: anonym 401, Freigabe je Space, Verdeckung nicht
 * freigegebener Seiten und keine Tokens in Antworten oder Logs.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class ApiAccessTest {

    private static final String READER_TOKEN = "reader-token-a81f0c";
    private static final String NOBODY_TOKEN = "nobody-token-77d2e4";
    private static final String UNKNOWN_TOKEN = "unknown-token-5be913";

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("HR", "Personal");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.page("200", "Gehälter 2026", "HR", "<p>vertraulich</p>");
        CONFLUENCE.homepage("HR", "200");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.security.clients[0].id", () -> "reader");
        registry.add("space-harvester.security.clients[0].token-sha256", () -> ClientTokens.sha256Hex(READER_TOKEN));
        registry.add("space-harvester.security.clients[0].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[0].spaces[0]", () -> "DEV");
        registry.add("space-harvester.security.clients[0].grants[0].access", () -> "metadata");
        registry.add("space-harvester.security.clients[1].id", () -> "nobody");
        registry.add("space-harvester.security.clients[1].token-sha256",
                () -> "sha256:" + ClientTokens.sha256Hex(NOBODY_TOKEN).toUpperCase());
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/info", "/api/v1/sources", "/api/v1/sources/wiki/spaces",
            "/api/v1/sources/wiki/spaces/DEV/pages/metadata", "/api/v1/sources/wiki/pages/100/metadata",
            "/api/v1/does-not-exist", "/actuator/env"})
    void anonymousRequestsAreRejectedBeforeTheSourceIsAsked(String path) throws Exception {
        int before = CONFLUENCE.requestHeaders().size();

        HttpResponse<String> response = get(path, null);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        assertThat(response.body()).contains("problems/unauthenticated").doesNotContain("Gehälter");
        assertThat(CONFLUENCE.requestHeaders()).hasSize(before);
    }

    @Test
    void unknownTokenIsRejectedAndNeverLogged(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/api/v1/sources", UNKNOWN_TOKEN);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).doesNotContain(UNKNOWN_TOKEN);
        assertThat(output).contains("unbekanntem Bearer-Token").doesNotContain(UNKNOWN_TOKEN);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/health", "/actuator/health/liveness", "/v3/api-docs"})
    void probesAndApiDescriptionArePublic(String path) throws Exception {
        assertThat(get(path, null).statusCode()).isEqualTo(200);
    }

    @Test
    void apiDescriptionDeclaresBearerAuthentication() throws Exception {
        assertThat(get("/v3/api-docs", null).body()).contains("\"bearerAuth\"", "\"scheme\":\"bearer\"");
    }

    @Test
    void readerSeesOnlyItsGrantedSpace() throws Exception {
        assertThat(get("/api/v1/sources", READER_TOKEN).body()).contains("\"id\":\"wiki\"");

        HttpResponse<String> spaces = get("/api/v1/sources/wiki/spaces", READER_TOKEN);
        assertThat(spaces.statusCode()).isEqualTo(200);
        assertThat(spaces.body()).contains("\"key\":\"DEV\"").doesNotContain("HR", "Personal");

        assertThat(get("/api/v1/sources/wiki/spaces/DEV/pages/metadata", READER_TOKEN).statusCode()).isEqualTo(200);
        assertThat(get("/api/v1/sources/wiki/pages/100/metadata", READER_TOKEN).body()).contains("\"title\":\"Start\"");
    }

    @Test
    void ungrantedSpaceIsForbiddenWithoutAskingTheSource() throws Exception {
        int before = CONFLUENCE.requestHeaders().size();

        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces/HR/pages/metadata", READER_TOKEN);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("problems/access-denied").doesNotContain("Gehälter");
        assertThat(CONFLUENCE.requestHeaders()).hasSize(before);
    }

    @Test
    void pageInUngrantedSpaceLooksExactlyLikeAMissingPage() throws Exception {
        HttpResponse<String> hidden = get("/api/v1/sources/wiki/pages/200/metadata", READER_TOKEN);
        HttpResponse<String> missing = get("/api/v1/sources/wiki/pages/999/metadata", READER_TOKEN);

        assertThat(hidden.statusCode()).isEqualTo(404).isEqualTo(missing.statusCode());
        assertThat(hidden.body()).doesNotContain("Gehälter", "HR")
                .isEqualTo(missing.body().replace("/999/", "/200/"));
    }

    @Test
    void clientWithoutGrantsSeesNothing() throws Exception {
        HttpResponse<String> sources = get("/api/v1/sources", NOBODY_TOKEN);
        assertThat(sources.statusCode()).isEqualTo(200);
        assertThat(sources.body()).isEqualTo("[]");

        assertThat(get("/api/v1/sources/wiki/spaces", NOBODY_TOKEN).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/pages/100/metadata", NOBODY_TOKEN).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/info", NOBODY_TOKEN).statusCode()).isEqualTo(200);
    }

    @Test
    void noGeneratedLoginPasswordAndNoTokensInLogs(CapturedOutput output) throws Exception {
        get("/api/v1/sources/wiki/spaces", READER_TOKEN);
        get("/api/v1/sources/wiki/spaces", NOBODY_TOKEN);

        assertThat(output).doesNotContain("generated security password", READER_TOKEN, NOBODY_TOKEN);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
