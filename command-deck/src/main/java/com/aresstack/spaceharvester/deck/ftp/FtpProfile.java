package com.aresstack.spaceharvester.deck.ftp;

import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Wohin eine FTP-Verbindung geht und wie sie arbeitet. Benutzer und Passwort stehen nicht darin: Sie kommen aus dem
 * KeePass-Eintrag und werden erst beim Aufbau gelesen (gemeinsam mit Terminal und NDV, #1142). Alle Einstellungen der
 * Bereiche „FTP“ und „JES-Spool“ von MainframeMate gehören hierher, je Verbindung.
 *
 * @param host                  Rechnername oder Adresse
 * @param port                  TCP-Port, meist 21
 * @param encoding              Zeichensatz der Steuerverbindung und der Textübertragung; wie in MainframeMate
 *                              {@code ISO-8859-1}, der Host wandelt EBCDIC selbst
 * @param connectTimeoutSeconds Zeitlimit für den Aufbau (Connect Timeout), 0 für keines
 * @param controlTimeoutSeconds Zeitlimit für Antworten der Steuerverbindung (Control Timeout), 0 für keines
 * @param dataTimeoutSeconds    Zeitlimit für Datenübertragungen (Data Timeout), 0 für keines
 * @param transfer              TYPE, Textformat, STRU, MODE
 * @param retry                 Wiederholung lesender Aufträge
 * @param startHlq              Start-HLQ auf MVS; leer = Anmeldename (MainframeMate „Login-Namen als HLQ verwenden“)
 * @param spoolNaming           DD-Namen der Spool-Dateien
 * @param useProxy              {@code true} (Vorgabe): wie bisher die Proxy-Einstellungen von Java/System; {@code false}:
 *                              immer direkt (MainframeMate {@code useProxy=false})
 */
public record FtpProfile(String host, int port, String encoding, int connectTimeoutSeconds, int controlTimeoutSeconds,
                         int dataTimeoutSeconds, TransferOptions transfer, RetryPolicy retry, String startHlq,
                         SpoolNaming spoolNaming, boolean useProxy, java.util.List<String> syslib) {

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
        if (connectTimeoutSeconds < 0 || controlTimeoutSeconds < 0 || dataTimeoutSeconds < 0) {
            throw new IllegalArgumentException("Zeitlimit darf nicht negativ sein");
        }
        transfer = Objects.requireNonNullElse(transfer, TransferOptions.DEFAULT);
        retry = Objects.requireNonNullElse(retry, RetryPolicy.DEFAULT);
        startHlq = startHlq == null ? "" : startHlq.trim().toUpperCase(java.util.Locale.ROOT);
        spoolNaming = Objects.requireNonNullElse(spoolNaming, SpoolNaming.DEFAULT);
        syslib = normalizeSyslib(syslib);
    }

    public FtpProfile(String host, int port, String encoding, int connectTimeoutSeconds, int controlTimeoutSeconds,
                      int dataTimeoutSeconds, TransferOptions transfer, RetryPolicy retry, String startHlq,
                      SpoolNaming spoolNaming, boolean useProxy) {
        this(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, retry, startHlq, spoolNaming, useProxy, java.util.List.of());
    }

    public FtpProfile withSyslib(java.util.List<String> libraries) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, retry, startHlq, spoolNaming, useProxy, libraries);
    }

    static java.util.List<String> normalizeSyslib(java.util.List<String> libraries) {
        if (libraries == null) {
            return java.util.List.of();
        }
        return libraries.stream().map(MvsPath::unquote).filter(name -> !name.isBlank())
                .map(name -> name.toUpperCase(java.util.Locale.ROOT)).peek(name -> {
                    if (name.length() > 44 || !name.matches("[A-Z@#$][A-Z0-9@#$-]{0,7}(\\.[A-Z@#$][A-Z0-9@#$-]{0,7})*")) {
                        throw new IllegalArgumentException("Ungültige SYSLIB: " + name);
                    }
                }).distinct().toList();
    }

    /** Ein Zeitlimit für alles, sonst die Vorgaben. */
    public FtpProfile(String host, int port, String encoding, int timeoutSeconds) {
        this(host, port, encoding, timeoutSeconds, timeoutSeconds, timeoutSeconds, TransferOptions.DEFAULT,
                RetryPolicy.DEFAULT, "", SpoolNaming.DEFAULT, true);
    }

    /** Ein Profil mit den üblichen Vorgaben: ISO-8859-1, 60 Sekunden Zeitlimit. */
    public static FtpProfile of(String host, int port) {
        return new FtpProfile(host, port, DEFAULT_ENCODING, DEFAULT_TIMEOUT_SECONDS);
    }

    public FtpProfile withTransfer(TransferOptions value) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                value, retry, startHlq, spoolNaming, useProxy, syslib);
    }

    public FtpProfile withRetry(RetryPolicy value) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, value, startHlq, spoolNaming, useProxy, syslib);
    }

    public FtpProfile withStartHlq(String value) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, retry, value, spoolNaming, useProxy, syslib);
    }

    public FtpProfile withSpoolNaming(SpoolNaming value) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, retry, startHlq, value, useProxy, syslib);
    }

    public FtpProfile withUseProxy(boolean value) {
        return new FtpProfile(host, port, encoding, connectTimeoutSeconds, controlTimeoutSeconds, dataTimeoutSeconds,
                transfer, retry, startHlq, spoolNaming, value, syslib);
    }

    public Charset charset() {
        return Charset.forName(encoding);
    }

    /** Kurzname: {@code host} oder {@code host:port}, wenn nicht 21. */
    public String label() {
        return port == DEFAULT_PORT ? host : host + ":" + port;
    }
}