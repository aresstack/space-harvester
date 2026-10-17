package com.aresstack.spaceharvester.application.access;

import java.util.Objects;

/**
 * Wer anfragt. Wird ausschließlich serverseitig aus der geprüften Anmeldung gebildet, nie aus Anfrageparametern.
 *
 * @param principal  stabile Kennung des Aufrufers: bei technischen Clients deren konfigurierte ID, bei Endnutzern die
 *                   Benutzerkennung der Quelle; kein Geheimnis
 * @param kind       Art des Aufrufers
 * @param via        nur bei {@link PrincipalKind#END_USER}: ID des technischen Clients, der die Anmeldung durchreicht
 * @param credential nur bei {@link PrincipalKind#END_USER}: das durchgereichte Token, erscheint nie in
 *                   {@link #toString()}
 */
public record AccessContext(String principal, PrincipalKind kind, String via, UserCredential credential) {

    public AccessContext {
        Objects.requireNonNull(principal, "principal");
        Objects.requireNonNull(kind, "kind");
        if ((kind == PrincipalKind.END_USER) != (via != null && credential != null)) {
            throw new IllegalArgumentException("via und credential gehören genau zu END_USER");
        }
    }

    public AccessContext(String principal, PrincipalKind kind) {
        this(principal, kind, null, null);
    }

    public static AccessContext technicalClient(String clientId) {
        return new AccessContext(clientId, PrincipalKind.TECHNICAL_CLIENT);
    }

    /**
     * Endnutzer, dessen Anmeldung bei der Quelle ein technischer Client durchreicht (#24, Teil C).
     *
     * @param userId   Benutzerkennung, die die Quelle zum Token genannt hat
     * @param clientId durchreichender Client; seine Korpusfreigabe begrenzt den Endnutzer zusätzlich
     */
    public static AccessContext endUser(String userId, String clientId, UserCredential credential) {
        return new AccessContext(userId, PrincipalKind.END_USER, Objects.requireNonNull(clientId, "clientId"),
                Objects.requireNonNull(credential, "credential"));
    }

    @Override
    public String toString() {
        return "AccessContext[" + kind + " " + principal + (via == null ? "" : " via " + via) + "]";
    }

    /** Art des Aufrufers. */
    public enum PrincipalKind {
        /** Konfigurierter technischer Client, berechtigt über die Korpusfreigabe. */
        TECHNICAL_CLIENT,
        /** Lokaler Entwicklungsmodus ohne Anmeldung; nur mit Profil {@code local}. */
        DEVELOPMENT,
        /**
         * Endnutzer mit durchgereichter Anmeldung bei einer Quelle (#24, Teil C); berechtigt nur, was die Quelle ihm
         * selbst zeigt und die Korpusfreigabe des durchreichenden Clients erlaubt.
         */
        END_USER
    }
}
