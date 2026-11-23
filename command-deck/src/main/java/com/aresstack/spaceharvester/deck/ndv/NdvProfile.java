package com.aresstack.spaceharvester.deck.ndv;

/**
 * Wohin eine NDV-Sitzung geht (Natural Development Server). Ohne Zugangsdaten: Benutzer und Passwort kommen erst
 * beim Verbinden aus dem KeePass-Eintrag ({@link NdvSessions.LoginSource}).
 *
 * @param host Rechnername oder Adresse, aus dem URL-Feld des KeePass-Eintrags
 * @param port TCP-Port des NDV-Servers, Vorgabe {@value #DEFAULT_PORT} (der Port des Eintrags gilt nur fürs Terminal)
 * @param defaultLibrary Standard-Bibliothek wie in MainframeMate: nach dem Verbinden angemeldet und geöffnet; leer
 *                       für keine
 */
public record NdvProfile(String host, int port, String defaultLibrary) {

    /** Standardport des NDV-Servers, wie in MainframeMate voreingestellt ({@code Settings.ndvPort}). */
    public static final int DEFAULT_PORT = 8011;

    public NdvProfile(String host, int port) {
        this(host, port, "");
    }

    public NdvProfile {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Host fehlt");
        }
        defaultLibrary = defaultLibrary == null ? "" : defaultLibrary.trim().toUpperCase(java.util.Locale.ROOT);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port muss zwischen 1 und 65535 liegen, nicht " + port);
        }
    }

    /** Kurzname für Reiter und Statuszeile: {@code host:port}. */
    public String label() {
        return host + ":" + port;
    }

    /**
     * Die Bibliothek zu einer Eingabe in der Pfadleiste, wie MainframeMate sie liest: {@code host:port/} wird
     * abgeschnitten, sonst gilt der letzte Teil nach einem {@code /}. Leer heißt Bibliotheksliste.
     */
    public String libraryFromPath(String input) {
        String path = input == null ? "" : input.trim();
        if (path.isEmpty() || path.equals(label())) {
            return "";
        }
        if (path.startsWith(label() + "/")) {
            path = path.substring(label().length() + 1);
        }
        int slash = path.lastIndexOf('/');
        if (slash >= 0) {
            path = path.substring(slash + 1);
        }
        return path.toUpperCase(java.util.Locale.ROOT);
    }
}
