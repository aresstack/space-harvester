package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Ordnet Bearer-Tokens technischen Clients zu. Konfiguriert sind nur SHA-256-Werte; ein vorgelegtes Token wird gehasht
 * und nachgeschlagen, das Token selbst wird weder gespeichert noch geloggt.
 */
final class ClientTokens {

    private static final Pattern SHA256_HEX = Pattern.compile("(sha256:)?[0-9a-fA-F]{64}");

    private final Map<String, String> clientIdByHash;

    private ClientTokens(Map<String, String> clientIdByHash) {
        this.clientIdByHash = Map.copyOf(clientIdByHash);
    }

    /**
     * @throws InvalidSecurityConfigurationException bei fehlendem oder falsch geformtem Hash oder doppeltem Token
     */
    static ClientTokens of(List<SecurityProperties.Client> clients) {
        Map<String, List<SecurityProperties.Client>> byHash = clients.stream()
                .collect(Collectors.groupingBy(client -> normalize(client), Collectors.toList()));
        byHash.forEach((hash, sharing) -> {
            if (sharing.size() > 1) {
                throw new InvalidSecurityConfigurationException("Clients "
                        + sharing.stream().map(SecurityProperties.Client::id).toList()
                        + " verwenden dasselbe Token; jeder Client braucht ein eigenes.");
            }
        });
        return new ClientTokens(byHash.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().getFirst().id())));
    }

    Optional<AccessContext> authenticate(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clientIdByHash.get(sha256Hex(token))).map(AccessContext::technicalClient);
    }

    static String sha256Hex(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 nicht verfügbar", e);
        }
    }

    private static String normalize(SecurityProperties.Client client) {
        String value = client.tokenSha256() == null ? "" : client.tokenSha256().trim();
        if (!SHA256_HEX.matcher(value).matches()) {
            throw new InvalidSecurityConfigurationException("Client " + client.id()
                    + ": token-sha256 muss der SHA-256-Wert des Tokens als 64 Hex-Zeichen sein "
                    + "(optional mit Präfix sha256:), nicht das Token selbst.");
        }
        return value.substring(value.length() - 64).toLowerCase(Locale.ROOT);
    }

}
