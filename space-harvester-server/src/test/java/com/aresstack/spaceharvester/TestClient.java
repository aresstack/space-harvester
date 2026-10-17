package com.aresstack.spaceharvester;

import java.net.http.HttpRequest;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * Technischer Test-Client für API-Tests: konfiguriert ihn mit voller Freigabe auf die genannten Quellen und meldet
 * Anfragen mit seinem Bearer-Token an.
 */
public final class TestClient {

    public static final String ID = "test-client";
    public static final String TOKEN = "test-client-token-3f9c2a";
    /** SHA-256 von {@link #TOKEN}. */
    public static final String TOKEN_SHA256 = "sha256:" + sha256(TOKEN);

    private TestClient() {
    }

    /** Ein Client mit {@code metadata}, {@code content} und {@code export} auf allen Spaces der Quellen. */
    public static void register(DynamicPropertyRegistry registry, String... sourceIds) {
        String prefix = "space-harvester.security.clients[0].";
        registry.add(prefix + "id", () -> ID);
        registry.add(prefix + "token-sha256", () -> TOKEN_SHA256);
        for (int i = 0; i < sourceIds.length; i++) {
            String grant = prefix + "grants[" + i + "].";
            String sourceId = sourceIds[i];
            registry.add(grant + "source", () -> sourceId);
            registry.add(grant + "spaces[0]", () -> "*");
            registry.add(grant + "access", () -> "metadata,content,export");
        }
    }

    public static HttpRequest.Builder authenticated(HttpRequest.Builder request) {
        return request.header("Authorization", "Bearer " + TOKEN);
    }

    static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
