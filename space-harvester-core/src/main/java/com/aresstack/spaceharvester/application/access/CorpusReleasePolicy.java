package com.aresstack.spaceharvester.application.access;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Korpusfreigabe für technische Clients: Je Client ist konfiguriert, welche Spaces welcher Quellen er mit welcher
 * {@link AccessKind} lesen darf. Alles andere wird verweigert, auch was das Erntekonto lesen kann.
 *
 * <ul>
 *   <li>Quelle: frei, wenn der Client für irgendeinen Space der Quelle diese Art hat.</li>
 *   <li>Space oder Seite mit bekanntem Space: frei, wenn eine Freigabe den Space abdeckt.</li>
 *   <li>Seite ohne bekannten Space: nur frei, wenn eine Freigabe alle Spaces der Quelle abdeckt; sonst
 *       {@link AccessDecision#UNDETERMINED}.</li>
 *   <li>Andere Aufrufer als technische Clients: {@link AccessDecision#DENY}.</li>
 * </ul>
 */
public final class CorpusReleasePolicy implements AccessPolicy {

    private final Map<String, List<Grant>> grantsByClient;

    public CorpusReleasePolicy(Map<String, List<Grant>> grantsByClient) {
        this.grantsByClient = Map.copyOf(grantsByClient);
    }

    @Override
    public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
        if (context.kind() != AccessContext.PrincipalKind.TECHNICAL_CLIENT) {
            return AccessDecision.DENY;
        }
        List<Grant> grants = grantsByClient.getOrDefault(context.principal(), List.of()).stream()
                .filter(grant -> grant.sourceId().equals(scope.sourceId()) && grant.kinds().contains(kind))
                .toList();
        if (grants.isEmpty()) {
            return AccessDecision.DENY;
        }
        if (scope.isSource()) {
            return AccessDecision.ALLOW;
        }
        if (scope.spaceKey() == null) {
            return grants.stream().anyMatch(Grant::allSpaces) ? AccessDecision.ALLOW : AccessDecision.UNDETERMINED;
        }
        return grants.stream().anyMatch(grant -> grant.covers(scope.spaceKey()))
                ? AccessDecision.ALLOW : AccessDecision.DENY;
    }

    /**
     * Eine Freigabe.
     *
     * @param sourceId  Quellen-ID
     * @param allSpaces {@code true} für alle Spaces der Quelle, auch künftige
     * @param spaceKeys freigegebene Space-Keys, wenn nicht {@code allSpaces}; leer gibt keinen Space frei
     * @param kinds     freigegebene Arten
     */
    public record Grant(String sourceId, boolean allSpaces, Set<String> spaceKeys, Set<AccessKind> kinds) {

        public Grant {
            Objects.requireNonNull(sourceId, "sourceId");
            spaceKeys = Set.copyOf(spaceKeys);
            kinds = Set.copyOf(kinds);
        }

        boolean covers(String spaceKey) {
            return allSpaces || spaceKeys.contains(spaceKey);
        }
    }
}
