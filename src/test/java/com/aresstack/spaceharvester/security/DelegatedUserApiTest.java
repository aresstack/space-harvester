package com.aresstack.spaceharvester.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccess;
import com.aresstack.spaceharvester.application.access.DelegationUnavailableException;
import com.aresstack.spaceharvester.application.access.ResourceScope;
import com.aresstack.spaceharvester.application.access.UserCredential;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Durchgereichte Endnutzer-Anmeldung (#24, Teil C) gegen eine Fake-Confluence und einen Fake für die Prüfung mit dem
 * Token des Endnutzers: Er sieht nur, was die Quelle ihm zeigt und der Client freigegeben hat; ohne Freigabe zur
 * Durchreichung, bei abgelehntem Token oder unklarer Quelle endet die Anfrage, nie mit den Rechten des Clients.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class DelegatedUserApiTest {

    private static final String ASSISTANT_TOKEN = "assistant-token-0c41d7";
    private static final String PLAIN_TOKEN = "plain-token-93ab5e";
    private static final String ALICE_TOKEN = "alice-confluence-token-6e2f";
    private static final String STRANGER_TOKEN = "stranger-confluence-token-1d8a";
    private static final String OUTAGE_TOKEN = "outage-confluence-token-44c0";

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("HR", "Personal");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        CONFLUENCE.page("101", "Übernahmepläne", "DEV", "<p>eingeschränkt</p>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.page("200", "Gehälter 2026", "HR", "<p>vertraulich</p>");
        CONFLUENCE.homepage("HR", "200");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    /** Was Alice laut Confluence sehen darf: Space DEV, darin nur die Startseite. */
    static final Set<ResourceScope> ALICE_SEES = Set.of(ResourceScope.space("wiki", "DEV"),
            ResourceScope.page("wiki", "DEV", "100"), ResourceScope.page("wiki", null, "100"));
    static final List<String> TOKENS_SEEN = new CopyOnWriteArrayList<>();

    @TestConfiguration
    static class FakeUserDirectory {

        @Bean
        DelegatedSourceAccess wikiUsers() {
            return new DelegatedSourceAccess() {
                @Override
                public String sourceId() {
                    return "wiki";
                }

                @Override
                public Optional<String> identify(UserCredential credential) {
                    TOKENS_SEEN.add(credential.token());
                    if (credential.token().equals(OUTAGE_TOKEN)) {
                        throw new DelegationUnavailableException("wiki");
                    }
                    return Optional.ofNullable(Map.of(ALICE_TOKEN, "alice").get(credential.token()));
                }

                @Override
                public AccessDecision canSee(UserCredential credential, ResourceScope scope) {
                    return credential.token().equals(ALICE_TOKEN) && ALICE_SEES.contains(scope)
                            ? AccessDecision.ALLOW : AccessDecision.DENY;
                }
            };
        }
    }

    @DynamicPropertySource
    static void configure(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        String assistant = "space-harvester.security.clients[0].";
        registry.add(assistant + "id", () -> "assistant");
        registry.add(assistant + "token-sha256", () -> ClientTokens.sha256Hex(ASSISTANT_TOKEN));
        registry.add(assistant + "grants[0].source", () -> "wiki");
        registry.add(assistant + "grants[0].spaces[0]", () -> "*");
        registry.add(assistant + "grants[0].access", () -> "metadata,content");
        registry.add(assistant + "delegation[0]", () -> "wiki");
        String plain = "space-harvester.security.clients[1].";
        registry.add(plain + "id", () -> "plain");
        registry.add(plain + "token-sha256", () -> ClientTokens.sha256Hex(PLAIN_TOKEN));
        registry.add(plain + "grants[0].source", () -> "wiki");
        registry.add(plain + "grants[0].spaces[0]", () -> "*");
        registry.add(plain + "grants[0].access", () -> "metadata");
    }

    @AfterAll
    static void stopServer() {
        SERVER.close();
    }

    @Autowired
    private Environment environment;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void endUserSeesOnlyWhatTheSourceShowsHim() throws Exception {
        HttpResponse<String> spaces = get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, "wiki", ALICE_TOKEN);
        assertThat(spaces.statusCode()).isEqualTo(200);
        assertThat(spaces.body()).contains("\"key\":\"DEV\"").doesNotContain("HR", "Personal");

        HttpResponse<String> pages = get("/api/v1/sources/wiki/spaces/DEV/pages/metadata", ASSISTANT_TOKEN, "wiki",
                ALICE_TOKEN);
        assertThat(pages.statusCode()).isEqualTo(200);
        assertThat(pages.body()).contains("\"title\":\"Start\"").doesNotContain("Übernahmepläne", "\"101\"");

        assertThat(get("/api/v1/sources/wiki/spaces/HR/pages/metadata", ASSISTANT_TOKEN, "wiki", ALICE_TOKEN)
                .statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/pages/100/metadata", ASSISTANT_TOKEN, "wiki", ALICE_TOKEN).body())
                .contains("\"title\":\"Start\"");
    }

    @Test
    void restrictedPageLooksExactlyLikeAMissingPage() throws Exception {
        HttpResponse<String> hidden = get("/api/v1/sources/wiki/pages/101/metadata", ASSISTANT_TOKEN, "wiki",
                ALICE_TOKEN);
        HttpResponse<String> missing = get("/api/v1/sources/wiki/pages/999/metadata", ASSISTANT_TOKEN, "wiki",
                ALICE_TOKEN);

        assertThat(hidden.statusCode()).isEqualTo(404).isEqualTo(missing.statusCode());
        assertThat(hidden.body()).doesNotContain("Übernahmepläne")
                .isEqualTo(missing.body().replace("/999/", "/101/"));
    }

    @Test
    void withoutDelegationTheClientKeepsItsOwnRelease() throws Exception {
        assertThat(get("/api/v1/sources/wiki/spaces/DEV/pages/metadata", ASSISTANT_TOKEN, null, null).body())
                .contains("Übernahmepläne");
    }

    @Test
    void clientWithoutDelegationReleaseIsStoppedInsteadOfFallingBackToItsOwnRights() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces", PLAIN_TOKEN, "wiki", ALICE_TOKEN);

        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("problems/delegation-not-allowed").doesNotContain("DEV");
        assertThat(TOKENS_SEEN).doesNotContain(PLAIN_TOKEN);
    }

    @Test
    void incompleteOrForeignDelegationIsStopped() throws Exception {
        assertThat(get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, "other", ALICE_TOKEN).statusCode())
                .isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, "wiki", null).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, null, ALICE_TOKEN).statusCode())
                .isEqualTo(403);
    }

    @Test
    void endUserTokenRejectedBySourceIsUnauthenticated(CapturedOutput output) throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, "wiki", STRANGER_TOKEN);

        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.body()).contains("problems/delegated-token-rejected").doesNotContain(STRANGER_TOKEN);
        assertThat(output).doesNotContain(STRANGER_TOKEN, ASSISTANT_TOKEN);
    }

    @Test
    void unclearSourceAnswerFailsClosed() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/wiki/spaces", ASSISTANT_TOKEN, "wiki", OUTAGE_TOKEN);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("problems/delegation-unavailable").doesNotContain("DEV");
    }

    @Test
    void endUserTokenNeverReachesConfluenceAsHarvestCredentialOrTheLogs(CapturedOutput output) throws Exception {
        int before = CONFLUENCE.requestHeaders().size();
        get("/api/v1/sources/wiki/spaces/DEV/pages/metadata", ASSISTANT_TOKEN, "wiki", ALICE_TOKEN);

        assertThat(CONFLUENCE.requestHeaders().subList(before, CONFLUENCE.requestHeaders().size()).toString())
                .doesNotContain(ALICE_TOKEN);
        assertThat(output).doesNotContain(ALICE_TOKEN, ASSISTANT_TOKEN);
    }

    private HttpResponse<String> get(String path, String token, String delegatedSource, String userToken)
            throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET()
                .header("Authorization", "Bearer " + token);
        if (delegatedSource != null) {
            request.header("X-Delegated-Source", delegatedSource);
        }
        if (userToken != null) {
            request.header("X-Delegated-Authorization", "Bearer " + userToken);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
