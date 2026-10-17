package com.aresstack.spaceharvester.security;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.DelegatedSourceAccess;
import com.aresstack.spaceharvester.application.access.UserCredential;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Meldet Endnutzer an, deren Anmeldung bei einer Quelle ein technischer Client durchreicht (#24, Teil C). Der Client
 * muss für diese Quelle zur Durchreichung freigegeben sein; die Quelle nennt zum Token die Benutzerkennung. Gehalten
 * wird nur die Kennung je Token-Fingerprint, nie das Token; abgelehnte Tokens werden nicht gehalten.
 */
final class DelegatedUsers {

    private final Map<String, Set<String>> sourcesByClient;
    private final Map<String, DelegatedSourceAccess> sources;
    private final Duration identityTtl;
    private final int maxEntries;
    private final Clock clock;
    private final Map<String, Identity> identities = new ConcurrentHashMap<>();

    DelegatedUsers(Map<String, Set<String>> sourcesByClient, Map<String, DelegatedSourceAccess> sources,
                   Duration identityTtl, int maxEntries, Clock clock) {
        this.sourcesByClient = Map.copyOf(sourcesByClient);
        this.sources = Map.copyOf(sources);
        this.identityTtl = identityTtl;
        this.maxEntries = maxEntries;
        this.clock = clock;
    }

    static DelegatedUsers none() {
        return new DelegatedUsers(Map.of(), Map.of(), Duration.ZERO, 0, Clock.systemUTC());
    }

    boolean allows(String clientId, String sourceId) {
        return sourcesByClient.getOrDefault(clientId, Set.of()).contains(sourceId) && sources.containsKey(sourceId);
    }

    /**
     * @return Endnutzer-Kontext oder leer, wenn die Quelle das Token ablehnt
     * @throws IllegalStateException wenn der Client für diese Quelle nicht freigegeben ist (vorher {@link #allows})
     * @throws com.aresstack.spaceharvester.application.access.DelegationUnavailableException wenn die Quelle nicht
     *                                                                                         eindeutig antwortet
     */
    Optional<AccessContext> authenticate(String clientId, UserCredential credential) {
        if (!allows(clientId, credential.sourceId())) {
            throw new IllegalStateException("Client nicht zur Durchreichung freigegeben");
        }
        Instant now = clock.instant();
        Identity hit = identities.get(credential.fingerprint());
        String userId;
        if (hit != null && now.isBefore(hit.expires())) {
            userId = hit.userId();
        } else {
            Optional<String> identified = sources.get(credential.sourceId()).identify(credential)
                    .filter(id -> !id.isBlank());
            if (identified.isEmpty()) {
                identities.remove(credential.fingerprint());
                return Optional.empty();
            }
            userId = identified.get();
            remember(credential.fingerprint(), userId, now);
        }
        return Optional.of(AccessContext.endUser(userId, clientId, credential));
    }

    private void remember(String fingerprint, String userId, Instant now) {
        if (identityTtl.isZero()) {
            return;
        }
        if (identities.size() >= maxEntries) {
            identities.values().removeIf(entry -> !now.isBefore(entry.expires()));
            if (identities.size() >= maxEntries) {
                identities.clear();
            }
        }
        identities.put(fingerprint, new Identity(userId, now.plus(identityTtl)));
    }

    /** Für die Startprüfung: Clients mit Durchreichung und ihre Quellen. */
    static Map<String, Set<String>> sourcesByClient(List<SecurityProperties.Client> clients) {
        Map<String, Set<String>> result = new java.util.LinkedHashMap<>();
        clients.stream().filter(client -> !client.delegation().isEmpty())
                .forEach(client -> result.put(client.id(), Set.copyOf(client.delegation())));
        return result;
    }

    private record Identity(String userId, Instant expires) {
    }
}
