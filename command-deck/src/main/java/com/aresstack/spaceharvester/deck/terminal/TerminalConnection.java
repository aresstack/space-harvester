package com.aresstack.spaceharvester.deck.terminal;

import java.io.IOException;
import java.util.Optional;

/**
 * Port: eine Verbindung zu einem Terminal-Host, geliefert von einem {@link TerminalConnector}. Das Fachmodell kennt
 * weder Swing noch die Emulationsbibliothek; die Darstellung holt sich die Oberfläche über {@link #view(Class)}.
 */
public interface TerminalConnection {

    /** Baut die Verbindung auf; blockiert bis dahin. Nie auf dem Event Dispatch Thread aufrufen. */
    void connect() throws IOException;

    /** Trennt; mehrfach aufrufen ist harmlos. */
    void disconnect();

    /** Die Darstellung des Adapters in der gewünschten Art, etwa eine Swing-Komponente. */
    <T> Optional<T> view(Class<T> type);

    /** Rückmeldungen des Adapters an die Sitzung. */
    interface Listener {

        /** Der Host hat die Verbindung bestätigt (erstes Bild). */
        void connected();

        /** Die Verbindung ist zu: vom Host getrennt ({@code reason} null) oder gescheitert (mit Grund). */
        void closed(String reason);
    }
}
