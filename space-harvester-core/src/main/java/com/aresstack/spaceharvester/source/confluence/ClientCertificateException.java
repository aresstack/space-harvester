package com.aresstack.spaceharvester.source.confluence;

/**
 * Das Client-Zertifikat einer Quelle mit eingeschaltetem mTLS ist nicht verwendbar (fehlt, abgelaufen, ohne privaten
 * Schlüssel, falscher Alias oder falsches Passwort). Die Meldung nennt nur Alias, Pfad und Ursache, nie Passwort,
 * Schlüssel- oder Zertifikatsinhalt; die Quelle läuft dann nicht ohne mTLS weiter.
 */
public final class ClientCertificateException extends IllegalStateException {

    public ClientCertificateException(String message) {
        super(message);
    }

    public ClientCertificateException(String message, Throwable cause) {
        super(message, cause);
    }
}
