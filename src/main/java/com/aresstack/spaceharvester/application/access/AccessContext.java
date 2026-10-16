package com.aresstack.spaceharvester.application.access;

import java.util.Objects;

/**
 * Wer anfragt. Wird ausschließlich serverseitig aus der geprüften Anmeldung gebildet, nie aus Anfrageparametern.
 *
 * @param principal stabile Kennung des Aufrufers, bei technischen Clients deren konfigurierte ID; kein Geheimnis
 * @param kind      Art des Aufrufers
 */
public record AccessContext(String principal, PrincipalKind kind) {

    public AccessContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(kind, "kind");
    }

    public static AccessContext technicalClient(String clientId) {
        return new AccessContext(clientId, PrincipalKind.TECHNICAL_CLIENT);
    }

    /** Art des Aufrufers. */
    public enum PrincipalKind {
        /** Konfigurierter technischer Client, berechtigt über die Korpusfreigabe. */
        TECHNICAL_CLIENT,
        /** Lokaler Entwicklungsmodus ohne Anmeldung; nur mit Profil {@code local}. */
        DEVELOPMENT,
        /** Endnutzer mit delegierter Anmeldung (#24, Teil C); bis dahin von keiner Richtlinie freigegeben. */
        END_USER
    }
}
