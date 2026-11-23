package com.aresstack.spaceharvester.deck.terminal;

import java.nio.charset.Charset;
import java.util.Objects;

/**
 * Wohin eine TN3270-Sitzung geht. Enthält bewusst keine Zugangsdaten: Benutzer und Kennwort tippt man im Terminal,
 * gemeinsame Credentials werden später perspektivunabhängig angebunden (#1141).
 *
 * @param host             Rechnername oder Adresse
 * @param port             TCP-Port, meist 23 (ohne TLS) oder 992 (mit TLS)
 * @param model            Bildschirmmodell
 * @param tls              verschlüsselt verbinden
 * @param keepAliveSeconds Abstand der Keep-Alive-Pakete, 0 für keine
 * @param codePage         EBCDIC-Codepage, etwa {@code Cp273} (deutsch) oder {@code Cp037} (US)
 * @param viaProxy         über den Proxy der App tunneln (HTTP CONNECT); aus = direkt wie bisher
 */
public record TerminalProfile(String host, int port, TerminalModel model, boolean tls, int keepAliveSeconds,
                              String codePage, boolean viaProxy) {

    public static final int DEFAULT_PORT = 23;
    public static final int DEFAULT_TLS_PORT = 992;
    /** Deutsche Hosts, wie in MainframeMate voreingestellt. */
    public static final String DEFAULT_CODE_PAGE = "Cp273";

    public TerminalProfile {
        host = host == null ? "" : host.trim();
        if (host.isEmpty()) {
            throw new IllegalArgumentException("Host fehlt");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port muss zwischen 1 und 65535 liegen, nicht " + port);
        }
        Objects.requireNonNull(model, "model");
        if (keepAliveSeconds < 0) {
            throw new IllegalArgumentException("Keep-Alive darf nicht negativ sein");
        }
        codePage = codePage == null || codePage.isBlank() ? DEFAULT_CODE_PAGE : codePage.trim();
        if (!Charset.isSupported(codePage)) {
            throw new IllegalArgumentException("Unbekannte Codepage: " + codePage);
        }
    }

    /** Direkt, ohne Proxy (das bisherige Verhalten). */
    public TerminalProfile(String host, int port, TerminalModel model, boolean tls, int keepAliveSeconds,
                           String codePage) {
        this(host, port, model, tls, keepAliveSeconds, codePage, false);
    }

    /** Ein Profil mit den üblichen Vorgaben: Modell 2, ohne TLS, ohne Keep-Alive, Cp273. */
    public static TerminalProfile of(String host, int port) {
        return new TerminalProfile(host, port, TerminalModel.MODEL_2, false, 0, DEFAULT_CODE_PAGE);
    }

    /** Kurzname für Reiter und Statuszeile: {@code host:port}. */
    public String label() {
        return host + ":" + port;
    }
}
