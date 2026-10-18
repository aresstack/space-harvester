package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.TestClient;
import com.aresstack.spaceharvester.application.configuration.ConfigurationManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Laufzeitkonfiguration (ADR 0015) über echtes HTTP: Lesen ohne Geheimnisse, Überlagern einzelner Schlüssel, Ablehnung
 * mit Schlüsseln, Neustart für den Speicherort und Schutz durch {@code manage-configuration}. Tests, die eine neue
 * Version aktivieren, verwerfen danach ihren Kontext, damit jeder bei Version 1 beginnt.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConfigurationApiTest {

    private static final String ADMIN_TOKEN = "config-admin-token-7d21";
    private static final String API_KEY = "sk-geheim-4711-nie-ausgeben";
    private static final String PROXY_PASSWORD = "proxy-geheim-0815";

    /** Wird nie angelegt: ein neuer Speicherort wirkt erst nach einem Neustart. */
    private static final Path OTHER_CACHE = Path.of("build", "nie-angelegt", "cache");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        TestClient.register(registry, "wiki");
        registry.add("space-harvester.security.clients[1].id", () -> "config-admin");
        registry.add("space-harvester.security.clients[1].token-sha256", () -> "sha256:" + sha256(ADMIN_TOKEN));
        registry.add("space-harvester.security.clients[1].manage-configuration", () -> "true");
        registry.add("space-harvester.ai.base-url", () -> "https://ki.example.org/v1?tenant=intern");
        registry.add("space-harvester.ai.api-key", () -> API_KEY);
        registry.add("space-harvester.network.proxy.mode", () -> "manual-proxy");
        registry.add("space-harvester.network.proxy.proxy-host", () -> "proxy.invalid");
        registry.add("space-harvester.network.proxy.proxy-port", () -> "3128");
        registry.add("space-harvester.network.proxy.auth.username", () -> "proxy-user");
        registry.add("space-harvester.network.proxy.auth.password", () -> PROXY_PASSWORD);
        registry.add("space-harvester.sync.sources.wiki.interval", () -> "PT30M");
        registry.add("space-harvester.sync.inventory-interval", () -> "P7D");
    }

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper json = new ObjectMapper();

    @Autowired
    private ConfigurableEnvironment environment;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private ConfigurationManager manager;

    @Test
    void readsTheEffectiveConfigurationWithoutSecrets() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "GET", "", null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).doesNotContain(API_KEY, PROXY_PASSWORD, "tenant=intern");
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("activatedAt").asString()).isNotBlank();
        JsonNode ai = body.get("configuration").get("ai");
        assertThat(ai.get("apiKeySet").asBoolean()).isTrue();
        assertThat(ai.has("apiKey")).isFalse();
        assertThat(ai.get("baseUrl").asString()).isEqualTo("https://ki.example.org/v1");
        JsonNode search = body.get("configuration").get("search");
        assertThat(search.get("semantic").asBoolean()).isFalse();
        assertThat(search.get("rerank").asBoolean()).isTrue();
        assertThat(search.get("prefetchHits").asBoolean()).isTrue();
        JsonNode proxy = body.get("configuration").get("network").get("proxy");
        assertThat(proxy.get("username").asString()).isEqualTo("proxy-user");
        assertThat(proxy.get("passwordSet").asBoolean()).isTrue();
        assertThat(body.get("configuration").get("storage").get("dataDirectory").isNull()).isTrue();
        JsonNode sync = body.get("configuration").get("sync");
        assertThat(sync.get("inventoryInterval").asString()).isEqualTo("PT168H");
        assertThat(sync.get("sources").get("wiki").get("interval").asString()).isEqualTo("PT30M");
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void overlaysKeysAndActivatesVersionTwoKeepingTheApiKey() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "POST", "", Map.of("ai.embedding-model", "x",
                "ai.embedding-dimensions", "768", "sync.interval", "P1D"));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("outcome").asString()).isEqualTo("ACTIVATED");
        assertThat(body.get("version").asLong()).isEqualTo(2);
        assertThat(body.get("restartRequired").asBoolean()).isFalse();

        JsonNode current = json.readTree(send(ADMIN_TOKEN, "GET", "", null).body());
        assertThat(current.get("version").asLong()).isEqualTo(2);
        JsonNode ai = current.get("configuration").get("ai");
        assertThat(ai.get("embeddingModel").asString()).isEqualTo("x");
        assertThat(ai.get("embeddingDimensions").asInt()).isEqualTo(768);
        assertThat(ai.get("apiKeySet").asBoolean()).isTrue();
        assertThat(current.get("configuration").get("sync").get("interval").asString()).isEqualTo("PT24H");
        assertThat(manager.current().configuration().ai().apiKey()).isEqualTo(API_KEY);
        // Proxy und TLS bleiben aus der wirksamen Konfiguration, samt Passwort.
        assertThat(manager.current().configuration().network().proxy().authentication().password())
                .isEqualTo(PROXY_PASSWORD.toCharArray());

        HttpResponse<String> again = send(ADMIN_TOKEN, "POST", "", Map.of("ai.embedding-model", "x"));
        assertThat(json.readTree(again.body()).get("outcome").asString()).isEqualTo("UNCHANGED");
    }

    @Test
    void rejectsAnEmbeddingModelWithoutDimensionsNamingTheKey() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "POST", "", Map.of("ai.embedding-model", "x"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/problem+json"));
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("type").asString()).endsWith("/configuration-rejected");
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(body.get("problems").valueStream().map(p -> p.get("key").asString()))
                .contains("ai.embedding-dimensions");
        assertThat(manager.current().version()).isEqualTo(1);
    }

    @Test
    void rejectsUnknownKeysAndNetwork() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "POST", "", Map.of("ai.unbekannt", "x",
                "network.proxy.mode", "manual-proxy", "space-harvester.sync.interval", "nie"));

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(json.readTree(response.body()).get("problems").valueStream().map(p -> p.get("key").asString()))
                .contains("ai.unbekannt", "network.proxy.mode");
        assertThat(response.body()).doesNotContain(API_KEY);
    }

    @Test
    void aNewCacheDirectoryNeedsARestart() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "POST", "", Map.of("cache.directory",
                OTHER_CACHE.toString()));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("restartRequired").asBoolean()).isTrue();
        assertThat(body.get("restartRequiredKeys").valueStream().map(JsonNode::asString)).contains("cache.directory");
        assertThat(body.get("outcome").asString()).isEqualTo("UNCHANGED");
        assertThat(body.get("version").asLong()).isEqualTo(1);
        assertThat(manager.current().configuration().storage().dataDirectory()).isNull();
    }

    @Test
    void dryRunOnlyValidates() throws Exception {
        HttpResponse<String> response = send(ADMIN_TOKEN, "POST", "?dryRun=true", Map.of("search.semantic", true));

        assertThat(response.statusCode()).isEqualTo(200);
        JsonNode body = json.readTree(response.body());
        assertThat(body.get("outcome").asString()).isEqualTo("VALID");
        assertThat(body.get("dryRun").asBoolean()).isTrue();
        assertThat(manager.current().version()).isEqualTo(1);
        assertThat(send(ADMIN_TOKEN, "POST", "?dryRun=true", Map.of("ai.embedding-model", "x")).statusCode())
                .isEqualTo(400);
    }

    @Test
    void onlyClientsWithManageConfigurationMayReadOrChange() throws Exception {
        assertThat(send(null, "GET", "", null).statusCode()).isEqualTo(401);
        assertThat(send(null, "POST", "", Map.of("search.semantic", true)).statusCode()).isEqualTo(401);
        assertThat(send(TestClient.TOKEN, "GET", "", null).statusCode()).isEqualTo(403);
        HttpResponse<String> forbidden = send(TestClient.TOKEN, "POST", "", Map.of("search.semantic", true));
        assertThat(forbidden.statusCode()).isEqualTo(403);
        assertThat(forbidden.body()).contains("access-denied");
        assertThat(manager.current().version()).isEqualTo(1);
    }

    @Test
    void noRefreshEndpointIsExposed() throws Exception {
        HttpResponse<String> refresh = http.send(HttpRequest.newBuilder(uri("/actuator/refresh"))
                        .header("Authorization", "Bearer " + ADMIN_TOKEN).POST(HttpRequest.BodyPublishers.noBody())
                        .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(refresh.statusCode()).isEqualTo(404);
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void anEnvironmentChangeActivatesTheNewConfiguration() {
        environment.getPropertySources().addFirst(new MapPropertySource("refreshed",
                Map.of("space-harvester.search.semantic", "true", "space-harvester.sync.interval", "6h",
                        "space-harvester.sync.sources[team].mode", "sync-cache")));

        context.publishEvent(new EnvironmentChangeEvent(context, Set.of("space-harvester.search.semantic",
                "space-harvester.sync.interval")));

        assertThat(manager.current().version()).isEqualTo(2);
        assertThat(manager.current().configuration().search().semantic()).isTrue();
        assertThat(manager.current().configuration().ai().apiKey()).isEqualTo(API_KEY);
        assertThat(manager.current().configuration().syncFor("team").mode().key()).isEqualTo("sync-cache");
        assertThat(manager.current().configuration().syncFor("wiki").interval()).hasMinutes(30);

        // Schlüssel außerhalb von space-harvester. lösen nichts aus.
        context.publishEvent(new EnvironmentChangeEvent(context, Set.of("server.port")));
        assertThat(manager.current().version()).isEqualTo(2);
    }

    private HttpResponse<String> send(String token, String method, String query, Map<String, ?> body)
            throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(ConfigurationController.CONFIGURATION + query))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
