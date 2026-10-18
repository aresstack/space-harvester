package com.aresstack.enterpriseai.chat.api;

/** Fehlerklassen eines Chat-Providers, unabhängig vom Protokoll. */
public enum ChatErrorKind {
    /** Zugangsdaten fehlen, sind ungültig oder reichen nicht (z. B. HTTP 401/403). */
    AUTHENTICATION,
    /**
     * Die Zugangsdaten konnten vorübergehend nicht beschafft werden (z. B. Passwortmanager kurz nicht erreichbar); sagt
     * nichts über ihre Gültigkeit. Die Ablage hat dafür bereits selbst wiederholt.
     */
    CREDENTIALS_UNAVAILABLE,
    /** Der Provider drosselt (z. B. HTTP 429). */
    RATE_LIMITED,
    /** Der Provider lehnt die Anfrage als ungültig ab (z. B. HTTP 400/404/422). */
    INVALID_REQUEST,
    /** Fehler auf Providerseite (z. B. HTTP 5xx). */
    PROVIDER_ERROR,
    /** Verbindung, Timeout oder Ein-/Ausgabe gescheitert. */
    TRANSPORT,
    /** Die Antwort war nicht im erwarteten Format. */
    PROTOCOL
}
