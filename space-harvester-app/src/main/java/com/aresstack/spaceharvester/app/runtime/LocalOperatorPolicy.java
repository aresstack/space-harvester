package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.access.AccessContext;
import com.aresstack.spaceharvester.application.access.AccessDecision;
import com.aresstack.spaceharvester.application.access.AccessKind;
import com.aresstack.spaceharvester.application.access.AccessPolicy;
import com.aresstack.spaceharvester.application.access.ResourceScope;

import java.util.Objects;
import java.util.Set;

/**
 * Rechte der Desktop-App: Wer die App bedient, hat die Quellen mit seinen eigenen Zugangsdaten eingerichtet und darf
 * alles lesen, abgleichen und exportieren, was diese Zugangsdaten bei der Quelle sehen. Freigegeben ist nur der
 * Aufrufer der App selbst und nur für konfigurierte Quellen; jeder andere Kontext wird verweigert. Die Prüfung läuft
 * wie im Dienst über den {@link com.aresstack.spaceharvester.application.access.AccessGuard} der Anwendungsfälle;
 * welche Seiten die Quelle zeigt, entscheidet weiterhin die Quelle mit den Zugangsdaten der Quelle.
 */
public final class LocalOperatorPolicy implements AccessPolicy {

    private final AccessContext operator;
    private final Set<String> sourceIds;

    public LocalOperatorPolicy(AccessContext operator, Set<String> sourceIds) {
        this.operator = Objects.requireNonNull(operator, "operator");
        this.sourceIds = Set.copyOf(sourceIds);
    }

    @Override
    public AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind) {
        return operator.equals(context) && sourceIds.contains(scope.sourceId())
                ? AccessDecision.ALLOW : AccessDecision.DENY;
    }
}
