package com.aresstack.spaceharvester.deck.terminal.openterm;

import com.aresstack.spaceharvester.deck.terminal.TerminalTunnel;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Brücke für OpenTerm: OpenTerm baut seine Verbindung selbst mit {@code new Socket()} auf und kennt keinen Proxy.
 * Für „Proxy verwenden“ verbindet es sich deshalb mit dieser Brücke auf {@code 127.0.0.1}, die genau eine Verbindung
 * annimmt und deren Bytes unverändert durch den {@link TerminalTunnel} zum Host reicht. TLS handelt OpenTerm wie
 * bisher Ende zu Ende mit dem Host aus; die Brücke sieht nur verschlüsselte Bytes. Schließt eine Seite, schließt die
 * Brücke beide.
 */
final class LoopbackRelay implements Closeable {

    /** So lange wartet die Brücke auf OpenTerm. */
    static final int ACCEPT_TIMEOUT_MILLIS = 30_000;

    private final ServerSocket server;
    private final AtomicReference<Socket> local = new AtomicReference<>();
    private final AtomicReference<Socket> remote = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    private LoopbackRelay(ServerSocket server) {
        this.server = server;
    }

    /**
     * Öffnet die Brücke; der Tunnel zum Host entsteht erst, wenn OpenTerm sich verbindet.
     *
     * @param failed bekommt den Grund, wenn der Tunnel nicht zustande kommt (OpenTerm sieht dann nur „geschlossen“)
     */
    static LoopbackRelay open(TerminalTunnel tunnel, String host, int port, int timeoutMillis,
                              java.util.function.Consumer<IOException> failed) throws IOException {
        ServerSocket server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 1);
        server.setSoTimeout(ACCEPT_TIMEOUT_MILLIS);
        LoopbackRelay relay = new LoopbackRelay(server);
        Thread.ofVirtual().name("terminal-relay-" + server.getLocalPort()).start(() -> {
            try {
                relay.run(tunnel, host, port, timeoutMillis);
            } catch (IOException e) {
                if (!relay.closed.get()) {
                    failed.accept(e);
                }
                relay.close();
            }
        });
        return relay;
    }

    int port() {
        return server.getLocalPort();
    }

    private void run(TerminalTunnel tunnel, String host, int port, int timeoutMillis) throws IOException {
        Socket accepted;
        try {
            accepted = server.accept();
        } finally {
            server.close();
        }
        local.set(accepted);
        if (!accepted.getInetAddress().isLoopbackAddress() || closed.get()) {
            close();
            return;
        }
        Socket target = tunnel.open(host, port, timeoutMillis);
        remote.set(target);
        if (closed.get()) {
            close();
            return;
        }
        Thread.ofVirtual().name("terminal-relay-up").start(() -> pump(accepted, target));
        pump(target, accepted);
    }

    private void pump(Socket from, Socket to) {
        byte[] buffer = new byte[8192];
        try (InputStream in = from.getInputStream()) {
            OutputStream out = to.getOutputStream();
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Eine Seite ist zu; die andere geht mit.
        } finally {
            close();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        quietly(server);
        quietly(local.get());
        quietly(remote.get());
    }

    private static void quietly(Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // schon zu
            }
        }
    }
}
