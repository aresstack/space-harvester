package com.aresstack.spaceharvester.deck.ftp;

import java.nio.charset.Charset;

/**
 * Wohin eine FTP-Verbindung geht. Benutzer und Passwort stehen nicht darin: Sie kommen aus dem KeePass-Eintrag und
 * werden erst beim Aufbau gelesen (gemeinsam mit Terminal und NDV, #1142).
 *
 * @param host           Rechnername oder Adresse
 * @param port           TCP-Port, meist 21
 * @param encoding       Zeichensatz der Steuerverbindung und der Textübertragung; wie in MainframeMate
 *                       {@code ISO-8859-1}, der Host wandelt EBCDIC selbst
 * @param timeoutSeconds Zeitlimit für Aufbau, Antworten und Daten, 0 für keines
 */
public record FtpProfile(String host, int port, String encoding, int timeoutSeconds) {

    public static final int DEFAULT_PORT = 21;
    public static final String DEFAULT_ENCODING = "ISO-8859-1";
    public static final int DEFAULT_TIMEOUT_SECONDS = 60;

    public FtpProfile {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Host fehlt");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port muss zwischen 1 und 65535 liegen, nicht " + port);
        }
        encoding = encoding == null || encoding.isBlank() ? DEFAULT_ENCODING : encoding.trim();
        if (!Charset.isSupported(encoding)) {
            throw new IllegalArgumentException("Unbekannter Zeichensatz: " + encoding);
        }
        if (timeoutSeconds < 0) {
            throw new IllegalArgumentException("Zeitlimit darf nicht negativ sein");
        }
    }

    /** Ein Profil mit den üblichen Vorgaben: ISO-8859-1, 60 Sekunden Zeitlimit. */
    public static FtpProfile of(String host, int port) {
        return new FtpProfile(host, port, DEFAULT_ENCODING, DEFAULT_TIMEOUT_SECONDS);
    }

    public Charset charset() {
        return Charset.forName(encoding);
    }

    /** Kurzname: {@code host} oder {@code host:port}, wenn nicht 21. */
    public String label() {
        return port == DEFAULT_PORT ? host : host + ":" + port;
    }
}
