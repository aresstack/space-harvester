package com.aresstack.spaceharvester.application.access;

/**
 * Entscheidet, ob ein Aufrufer etwas lesen oder auslösen darf. Implementierungen dürfen langsam sein (die delegierte
 * Prüfung aus #24 fragt die Quelle mit dem Token des Endnutzers), müssen aber im Zweifel {@link
 * AccessDecision#UNDETERMINED} liefern statt freizugeben. Ein Cache-Treffer, eine gespeicherte Revision oder die
 * Lesbarkeit für das technische Erntekonto sind nie ein Grund für {@link AccessDecision#ALLOW}.
 *
 * <p>Aufrufer nutzen den {@link AccessGuard}, nicht die Richtlinie direkt.
 */
@FunctionalInterface
public interface AccessPolicy {

    AccessDecision decide(AccessContext context, ResourceScope scope, AccessKind kind);
}
