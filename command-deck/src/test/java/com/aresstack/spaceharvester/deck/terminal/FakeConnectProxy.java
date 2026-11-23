package com.aresstack.spaceharvester.deck.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Ein Schein-Proxy für Tests: nimmt CONNECT an, merkt sich die Anfrage und reicht danach die Bytes durch. */
public final class FakeConnectProxy implements AutoCloseable {

    private final ServerSocket server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
    private final int status;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    /** @param status Antwort auf CONNECT, etwa 200 oder 407 */
    public FakeConnectProxy(int status) throws IOException {
        this.status = status;
        Thread.ofVirtual().start(this::serve);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Die Kopfzeilen jeder CONNECT-Anfrage. */
    public List<String> requests() {
        return requests;
    }

    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().start(() -> handle(client));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            ByteArrayOutputStream header = new ByteArrayOutputStream();
            while (!header.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
                int next = in.read();
                if (next < 0) {
                    return;
                }
                header.write(next);
            }
            String request = header.toString(StandardCharsets.ISO_8859_1);
            requests.add(request);
            OutputStream out = client.getOutputStream();
            if (status != 200) {
                out.write(("HTTP/1.1 " + status + " Nein\r\nProxy-Authenticate: Basic realm=\"test\"\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                return;
            }
            String target = request.substring("CONNECT ".length(), request.indexOf(' ', "CONNECT ".length()));
            int colon = target.lastIndexOf(':');
            try (Socket host = new Socket(target.substring(0, colon), Integer.parseInt(target.substring(colon + 1)))) {
                out.write("HTTP/1.1 200 Connection established\r\nVia: test\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                Thread.ofVirtual().start(() -> copy(host, client));
                copy(client, host);
            }
        } catch (IOException ignored) {
            // Test vorbei
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // eine Seite zu
        } finally {
            try {
                to.close();
            } catch (IOException ignored) {
                // schon zu
            }
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
