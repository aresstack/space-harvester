package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.aresstack.spaceharvester.TestClient;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

/** Lese-Ansicht über echtes HTTP gegen die Fake-Confluence (#176). */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ReadingViewApiTest {

    private static final String DEV_TOKEN = "reading-dev-only-token-5c2e";
    private static final String METADATA_TOKEN = "reading-metadata-token-91b0";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Willkommen</p><ac:placeholder>Jira</ac:placeholder>")
                .exportView("<p>Willkommen</p><table><tr><td>DEV-1 Offen</td></tr></table>")
                .storage("<p>Willkommen</p>");
        CONFLUENCE.page("101", "Sammlung", "DEV", "<p>Eingebunden</p>")
                .storage("<ac:structured-macro ac:name=\"include\"><ac:parameter ac:name=\"\">"
                        + "<ac:link><ri:page ri:content-title=\"Geheim\" ri:space-key=\"HR\"/></ac:link>"
                        + "</ac:parameter></ac:structured-macro>");
        CONFLUENCE.child("100", "101");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        client(registry, 0, TestClient.ID, TestClient.TOKEN_SHA256, "*", "metadata,content,sync");
        client(registry, 1, "nur-dev", "sha256:" + sha256(DEV_TOKEN), "DEV", "metadata,content");
        client(registry, 2, "nur-metadaten", "sha256:" + sha256(METADATA_TOKEN), "*", "metadata");
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> SERVER.baseUrl().toString());
        registry.add("space-harvester.sources[0].options.retryBackoffMillis", () -> "1");
    }

    private static void client(DynamicPropertyRegistry registry, int index, String id, String sha, String space,
                               String access) {
        String client = "space-harvester.security.clients[" + index + "].";
        registry.add(client + "id", () -> id);
        registry.add(client + "token-sha256", () -> sha);
        registry.add(client + "grants[0].source", () -> "wiki");
        registry.add(client + "grants[0].spaces[0]", () -> space);
        registry.add(client + "grants[0].access", () -> access);
    }

    @AfterAll
    static void stopServer() throws IOException {
        SERVER.close();
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private Environment environment;

    @Test
    void theRenderedViewComesWithTheCheckedVersionAndIsThenStored() throws Exception {
        HttpResponse<String> first = get("/api/v1/sources/wiki/pages/100/reading-view", DEV_TOKEN);

        assertThat(first.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(first.body());
        assertThat(body.get("status").asString()).isEqualTo("available");
        assertThat(body.get("spaceKey").asString()).isEqualTo("DEV");
        assertThat(body.get("title").asString()).isEqualTo("Start");
        // export_view: das Jira-Makro ist ausgeführt, nicht als Platzhalter.
        assertThat(body.get("html").asString()).contains("DEV-1 Offen").doesNotContain("placeholder");
        assertThat(body.get("text").asString()).contains("Willkommen");
        assertThat(body.get("version").asString()).isNotEmpty();
        assertThat(body.get("foreignContent").asBoolean()).isFalse();
        assertThat(body.get("stored").asBoolean()).isFalse();

        int before = CONFLUENCE.requestCount("export_view");
        JsonNode again = json.readTree(get("/api/v1/sources/wiki/pages/100/reading-view", DEV_TOKEN).body());
        assertThat(again.get("stored").asBoolean()).isTrue();
        assertThat(again.get("html").asString()).isEqualTo(body.get("html").asString());
        assertThat(CONFLUENCE.requestCount("export_view")).isEqualTo(before);
    }

    @Test
    void includedPagesAreOnlyRenderedForClientsWithTheWholeSource() throws Exception {
        JsonNode restricted = json.readTree(get("/api/v1/sources/wiki/pages/101/reading-view", DEV_TOKEN).body());
        assertThat(restricted.get("status").asString()).isEqualTo("restricted");
        assertThat(restricted.get("html").asString()).isEmpty();
        assertThat(restricted.get("foreignMacros").get(0).asString()).isEqualTo("include");

        JsonNode wide = json.readTree(get("/api/v1/sources/wiki/pages/101/reading-view", TestClient.TOKEN).body());
        assertThat(wide.get("status").asString()).isEqualTo("available");
        assertThat(wide.get("html").asString()).contains("Eingebunden");
    }

    @Test
    void withoutContentAccessThereIsNoView() throws Exception {
        assertThat(get("/api/v1/sources/wiki/pages/100/reading-view", METADATA_TOKEN).statusCode()).isEqualTo(403);
        assertThat(get("/api/v1/sources/wiki/pages/100/reading-view", "falsch").statusCode()).isEqualTo(401);
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
