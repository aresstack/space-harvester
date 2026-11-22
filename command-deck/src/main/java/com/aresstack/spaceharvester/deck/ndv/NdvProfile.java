package com.aresstack.spaceharvester.deck.ndv;

/**
 * Wohin eine NDV-Sitzung geht (Natural Development Server). Ohne Zugangsdaten: Benutzer und Passwort kommen erst
 * beim Verbinden aus dem KeePass-Eintrag ({@link NdvSessions.LoginSource}).
 *
 * @param host Rechnername oder Adresse, aus dem URL-Feld des KeePass-Eintrags
 * @param port TCP-Port des NDV-Servers, Vorgabe {@value #DEFAULT_PORT} (der Port des Eintrags gilt nur fürs Terminal)
 */
public record NdvProfile(String host, int port) {

    /** Standardport des NDV-Servers, wie in MainframeMate voreingestellt. */
    public static final int DEFAULT_PORT = 2700;

    public NdvProfile {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Host fehlt");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port muss zwischen 1 und 65535 liegen, nicht " + port);
        }
    }

    /** Kurzname für Reiter und Statuszeile: {@code host:port}. */
    public String label() {
        return host + ":" + port;
    }
}
