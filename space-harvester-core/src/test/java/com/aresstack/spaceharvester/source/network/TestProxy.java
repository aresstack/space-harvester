package com.aresstack.spaceharvester.source.network;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Minimaler HTTP-Forward-Proxy für Tests: leitet Anfragen in Absolute-Form ({@code GET http://host:port/...}) an
 * das Ziel weiter und merkt sich Anfragezeile und Kopfzeilen. {@code CONNECT host:port} öffnet einen Tunnel zum Ziel
 * und leitet die Bytes (TLS) unverändert in beide Richtungen; ist das Ziel nicht erreichbar, antwortet er mit 502.
 * Mit {@link #requireBasic} verlangt er eine Anmeldung und antwortet ohne passende {@code Proxy-Authorization} mit 407.
 */
public final class TestProxy implements AutoCloseable {

    private final ServerSocket server;
    private final List<String> requestLines = new CopyOnWriteArrayList<>();
    private final List<String> headerBlocks = new CopyOnWriteArrayList<>();
    private volatile String expectedAuthorization;

    public TestProxy() throws IOException {
        server = new ServerSocket();
        server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        Thread acceptor = new Thread(this::accept, "test-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public int port() {
        return server.getLocalPort();
    }

    public List<String> requestLines() {
        return requestLines;
    }

    public List<String> headerBlocks() {
        return headerBlocks;
    }

    /** Verlangt ab jetzt BASIC mit diesem Benutzer und Passwort. */
    public TestProxy requireBasic(String user, String password) {
        expectedAuthorization = "Basic " + java.util.Base64.getEncoder()
                .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
        return this;
    }

    private boolean authorized(String head) {
        String expected = expectedAuthorization;
        if (expected == null) {
            return true;
        }
        for (String line : head.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Proxy-Authorization")
                    && line.substring(colon + 1).trim().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private void accept() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread handler = new Thread(() -> handle(client), "test-proxy-conn");
                handler.setDaemon(true);
                handler.start();
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            while (true) {
                String head = readHead(in);
                if (head == null) {
                    return;
                }
                String requestLine = head.substring(0, head.indexOf("\r\n"));
                requestLines.add(requestLine);
                headerBlocks.add(head);
                String[] parts = requestLine.split(" ");
                if (!authorized(head)) {
                    out.write(("HTTP/1.1 407 Proxy Authentication Required\r\n"
                            + "Proxy-Authenticate: Basic realm=\"test-proxy\"\r\nContent-Length: 0\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                    continue;
                }
                if (parts[0].equals("CONNECT")) {
                    tunnel(parts[1], in, out);
                    return;
                }
                java.net.URI target = java.net.URI.create(parts[1]);
                int port = target.getPort() < 0 ? 80 : target.getPort();
                try (Socket upstream = new Socket(target.getHost(), port)) {
                    // Anfrage ohne Keep-Alive und ohne Proxy-Anmeldung weitergeben (wie ein echter Proxy); der Fake-Server
                    // versteht die Absolute-Form.
                    String forwarded = head
                            .replaceAll("(?i)\r\n(proxy-connection|connection|proxy-authorization):[^\r]*", "")
                            .replace("\r\n\r\n", "\r\nConnection: close\r\n\r\n");
                    upstream.getOutputStream().write(forwarded.getBytes(StandardCharsets.ISO_8859_1));
                    upstream.getOutputStream().flush();
                    upstream.getInputStream().transferTo(out);
                }
                return;
            }
        } catch (IOException ignored) {
            // Testproxy: abgebrochene Verbindungen sind hier kein Fehler.
        }
    }

    private static void tunnel(String authority, InputStream in, OutputStream out) throws IOException {
        int colon = authority.lastIndexOf(':');
        Socket upstream;
        try {
            upstream = new Socket(authority.substring(0, colon), Integer.parseInt(authority.substring(colon + 1)));
        } catch (IOException e) {
            out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    .getBytes(StandardCharsets.US_ASCII));
            return;
        }
        try (upstream) {
            out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread back = new Thread(() -> {
                try {
                    upstream.getInputStream().transferTo(out);
                } catch (IOException ignored) {
                    // Tunnel geschlossen.
                }
            }, "test-proxy-tunnel");
            back.setDaemon(true);
            back.start();
            in.transferTo(upstream.getOutputStream());
            back.join(5_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int state = 0;
        int b;
        while ((b = in.read()) >= 0) {
            head.write(b);
            state = switch (state) {
                case 0, 2 -> b == '\r' ? state + 1 : 0;
                case 1 -> b == '\n' ? 2 : 0;
                case 3 -> b == '\n' ? 4 : 0;
                default -> 0;
            };
            if (state == 4) {
                return head.toString(StandardCharsets.ISO_8859_1);
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        server.close();
    }
}
