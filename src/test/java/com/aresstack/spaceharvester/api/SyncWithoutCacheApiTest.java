package com.aresstack.spaceharvester.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Ohne Cache-Speicher und Inhaltsabruf startet der Dienst, auch mit konfiguriertem Hintergrundabgleich, und lehnt
 * einen Abgleich mit 503 ab, statt einen Auftrag anzunehmen, der nur scheitern kann.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class SyncWithoutCacheApiTest {

    private static final String SYNC_TOKEN = "sync-client-token-51ab";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("space-harvester.sources[0].id", () -> "wiki");
        registry.add("space-harvester.sources[0].type", () -> "confluence");
        registry.add("space-harvester.sources[0].base-url", () -> "http://127.0.0.1:9");
        registry.add("space-harvester.sync.interval", () -> "PT1H");
        registry.add("space-harvester.sync.spaces[0]", () -> "wiki/DEV");
        registry.add("space-harvester.security.clients[0].id", () -> "syncer");
        registry.add("space-harvester.security.clients[0].token-sha256", () -> "sha256:" + sha256(SYNC_TOKEN));
        registry.add("space-harvester.security.clients[0].grants[0].source", () -> "wiki");
        registry.add("space-harvester.security.clients[0].grants[0].spaces[0]", () -> "*");
        registry.add("space-harvester.security.clients[0].grants[0].access", () -> "sync");
    }

    private final HttpClient http = HttpClient.newHttpClient();

    @Autowired
    private Environment environment;

    @Test
    void startingASyncSaysItIsUnavailable() throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:"
                        + environment.getProperty("local.server.port") + "/api/v1/sources/wiki/spaces/DEV/syncs"))
                .header("Authorization", "Bearer " + SYNC_TOKEN).POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("sync-unavailable");
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
