package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.TerminalConnection;
import com.aresstack.spaceharvester.deck.terminal.TerminalConnector;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.ascert.open.term.core.Host;
import com.ascert.open.term.core.Terminal;
import com.ascert.open.term.core.TerminalFactoryRegistrar;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Adapter zu OpenTerm (TN3270, reines Java), wie in MainframeMate. Legt je Profil ein OpenTerm-{@link Terminal} an;
 * aufgebaut wird die Verbindung erst über {@link OpenTermConnection#connect()}.
 */
public final class OpenTermConnector implements TerminalConnector {

    private static final ReentrantLock FACTORIES = new ReentrantLock();
    private static boolean registered;

    @Override
    public TerminalConnection create(TerminalProfile profile, TerminalConnection.Listener listener) throws Exception {
        registerFactories();
        CodePages.apply(profile.codePage());
        Host host = new Host(profile.host(), profile.port(), profile.model().type(), profile.tls(),
                profile.keepAliveSeconds());
        Terminal terminal = TerminalFactoryRegistrar.createTerminal(host);
        return new OpenTermConnection(terminal, profile, listener);
    }

    private static void registerFactories() throws Exception {
        FACTORIES.lock();
        try {
            if (!registered) {
                TerminalFactoryRegistrar.initTermTypeFactories(null);
                registered = true;
            }
        } finally {
            FACTORIES.unlock();
        }
    }
}
