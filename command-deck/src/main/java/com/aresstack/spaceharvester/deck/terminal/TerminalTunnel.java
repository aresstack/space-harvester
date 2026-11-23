package com.aresstack.spaceharvester.deck.terminal;

import java.io.IOException;
import java.net.Socket;

/**
 * Öffnet die TCP-Verbindung zum Host, wenn ein Profil {@link TerminalProfile#viaProxy() über den Proxy} gehen soll.
 * Die App liefert die Umsetzung mit ihrer Proxy-Regel; TLS legt das Terminal danach selbst darüber.
 */
@FunctionalInterface
public interface TerminalTunnel {

    /** Ohne Proxy-Regel: „Proxy verwenden“ scheitert verständlich, statt still direkt zu verbinden. */
    TerminalTunnel NONE = (host, port, timeoutMillis) -> {
        throw new IOException("Kein Proxy eingerichtet (Einstellungen › Netzwerk)");
    };

    /**
     * @return eine verbundene Socket-Verbindung, deren Datenstrom beim Host ankommt
     * @throws IOException mit verständlichem Grund (Proxy abgelehnt, keine Route, Zeitüberschreitung)
     */
    Socket open(String host, int port, int timeoutMillis) throws IOException;
}
