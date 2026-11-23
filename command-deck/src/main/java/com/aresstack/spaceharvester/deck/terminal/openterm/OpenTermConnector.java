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
    /** Zeitlimit für den Aufbau des Proxy-Tunnels. */
    static final int TUNNEL_TIMEOUT_MILLIS = 20_000;
    private static boolean registered;
    private final com.aresstack.spaceharvester.deck.terminal.TerminalTunnel tunnel;

    /** Ohne Proxy-Regel: Profile mit „Proxy verwenden“ scheitern verständlich. */
    public OpenTermConnector() {
        this(com.aresstack.spaceharvester.deck.terminal.TerminalTunnel.NONE);
    }

    /**
     * @param tunnel Weg durch den Proxy der App für Profile mit {@link TerminalProfile#viaProxy()}
     */
    public OpenTermConnector(com.aresstack.spaceharvester.deck.terminal.TerminalTunnel tunnel) {
        this.tunnel = java.util.Objects.requireNonNull(tunnel, "tunnel");
    }

    @Override
    public TerminalConnection create(TerminalProfile profile, TerminalConnection.Listener listener) throws Exception {
        registerFactories();
        CodePages.acquire(profile.codePage());
        LoopbackRelay relay = null;
        try {
            java.util.concurrent.atomic.AtomicReference<String> problem =
                    new java.util.concurrent.atomic.AtomicReference<>();
            String address = profile.host();
            int port = profile.port();
            if (profile.viaProxy()) {
                // OpenTerm kennt keinen Proxy: Es verbindet sich mit der Brücke, die durch den Tunnel zum Host reicht.
                relay = LoopbackRelay.open(tunnel, profile.host(), profile.port(), TUNNEL_TIMEOUT_MILLIS,
                        failure -> problem.compareAndSet(null, "Über den Proxy: " + failure.getMessage()));
                address = java.net.InetAddress.getLoopbackAddress().getHostAddress();
                port = relay.port();
            }
            Host host = new Host(address, port, profile.model().type(), profile.tls(), profile.keepAliveSeconds());
            Terminal terminal = TerminalFactoryRegistrar.createTerminal(host);
            return new OpenTermConnection(terminal, profile, listener, relay, problem);
        } catch (Exception | Error e) {
            if (relay != null) {
                relay.close();
            }
            CodePages.release();
            throw e;
        }
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
