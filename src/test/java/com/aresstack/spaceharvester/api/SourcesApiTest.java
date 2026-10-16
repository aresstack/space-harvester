package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluenceServer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Quellen aus der YAML-Konfiguration über die REST-API: Spaces einer Fake-Confluence über echtes HTTP, Basic und PAT,
 * und die Unterscheidung von unbekannter Quelle, verweigertem Zugriff und nicht erreichbarer Quelle.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class SourcesApiTest {

    private static final String PASSWORD = "geheimes-passwort-4711";
    private static final String TOKEN = "pat-token-0815";

    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    private static final FakeConfluenceServer SERVER;
    private static final HttpServer DENYING;
    private static final int CLOSED_PORT;

    static {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.space("OPS", "Betrieb");
        CONFLUENCE.space("ARCH", "Architektur");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>");
        CONFLUENCE.homepage("DEV", "100");
        try {
            SERVER = new FakeConfluenceServer(CONFLUENCE);
            DENYING = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
            DENYING.createContext("/", exchange -> {
                exchange.sendResponseHeaders(401, -1);
                exchange.close();
            });
            DENYING.start();
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
                CLOSED_PORT = socket.getLocalPort();
            }
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void sources(DynamicPropertyRegistry registry) {
        source(registry, 0, "basic-wiki", SERVER.baseUrl().toString(),
                Map.of("type", "basic", "username", "harvester", "password", PASSWORD));
        source(registry, 1, "pat-wiki", SERVER.baseUrl().toString(), Map.of("type", "pat", "token", TOKEN));
        source(registry, 2, "denying-wiki", "http://127.0.0.1:" + DENYING.getAddress().getPort() + "/wiki",
                Map.of("type", "basic", "username", "harvester", "password", PASSWORD));
        source(registry, 3, "offline-wiki", "http://127.0.0.1:" + CLOSED_PORT + "/wiki", Map.of());
    }

    private static void source(DynamicPropertyRegistry registry, int index, String id, String baseUrl,
                               Map<String, String> auth) {
        String prefix = "space-harvester.sources[" + index + "].";
        registry.add(prefix + "id", () -> id);
        registry.add(prefix + "type", () -> "confluence");
        registry.add(prefix + "base-url", () -> baseUrl);
        // Die Fake-Confluence spricht nur http; mit Zugangsdaten erlaubt der Adapter das nur ausdrücklich.
        registry.add(prefix + "options.allowInsecureHttp", () -> "true");
        registry.add(prefix + "options.connectTimeoutMillis", () -> "2000");
        auth.forEach((key, value) -> registry.add(prefix + "auth." + key, () -> value));
    }

    @AfterAll
    static void stopServers() {
        SERVER.close();
        DENYING.stop(0);
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void listsConfiguredSourcesWithoutConnectionData() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"id\":\"basic-wiki\"", "\"id\":\"pat-wiki\"", "\"type\":\"confluence\"")
                .doesNotContain(PASSWORD, TOKEN, "harvester", "127.0.0.1");
    }

    @Test
    void listsAllSpacesOfASourceWithBasicAuth() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/basic-wiki/spaces");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body())
                .contains("\"sourceId\":\"basic-wiki\"")
                .contains("\"key\":\"DEV\"", "\"name\":\"Entwicklung\"", "\"key\":\"OPS\"", "\"key\":\"ARCH\"")
                .contains("\"homeResourceId\":\"confluence:");
        assertThat(lastAuthorization()).startsWith("Basic ");
    }

    @Test
    void sendsAPersonalAccessTokenAsBearer() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/pat-wiki/spaces");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(lastAuthorization()).isEqualTo("Bearer " + TOKEN);
    }

    @Test
    void unknownSourceIsNotFound() throws Exception {
        HttpResponse<String> response = get("/api/v1/sources/nope/spaces");

        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("problems/source-not-found", "\"sourceId\":\"nope\"");
    }

    @Test
    void deniedAccessIsDistinguishedFromAnUnreachableSource(CapturedOutput output) throws Exception {
        HttpResponse<String> denied = get("/api/v1/sources/denying-wiki/spaces");
        HttpResponse<String> offline = get("/api/v1/sources/offline-wiki/spaces");

        assertThat(denied.statusCode()).isEqualTo(502);
        assertThat(denied.body()).contains("problems/source-access-denied").doesNotContain(PASSWORD);
        assertThat(offline.statusCode()).isEqualTo(503);
        assertThat(offline.body()).contains("problems/source-unavailable",
                "\"detail\":\"Die Quelle ist derzeit nicht erreichbar.\"");
        // Transportmeldungen (Host, Port, Pfad) bleiben intern.
        assertThat(denied.body() + offline.body()).doesNotContain("127.0.0.1", "/wiki", "Exception");
        assertThat(output.getAll()).doesNotContain(PASSWORD, TOKEN);
    }

    private String lastAuthorization() {
        var headers = CONFLUENCE.requestHeaders();
        Map<String, String> last = headers.get(headers.size() - 1);
        return last.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Authorization"))
                .map(Map.Entry::getValue).findFirst().orElse("");
    }

    private HttpResponse<String> get(String path) throws Exception {
        String port = environment.getRequiredProperty("local.server.port");
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
