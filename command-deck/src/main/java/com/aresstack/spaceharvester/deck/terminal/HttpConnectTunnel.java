package com.aresstack.spaceharvester.deck.terminal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

/**
 * Ein Tunnel durch einen HTTP-Proxy mit {@code CONNECT host:port} (wie ein Browser für HTTPS). Mit Benutzername und
 * Kennwort meldet er sich per Basic an. Das Kennwort liegt nur als {@code char[]} bzw. {@code byte[]} vor, wird nach
 * dem Senden überschrieben und nie protokolliert.
 */
public final class HttpConnectTunnel {

    /** Längste Antwort des Proxys auf CONNECT (Statuszeile und Kopfzeilen). */
    static final int MAX_HEADER = 16 * 1024;

    private HttpConnectTunnel() {
    }

    /**
     * @param password Kennwort am Proxy oder {@code null}; wird nicht verändert
     */
    public static Socket open(String proxyHost, int proxyPort, String user, char[] password, String host, int port,
                              int timeoutMillis) throws IOException {
        Socket socket = new Socket(java.net.Proxy.NO_PROXY);
        try {
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            OutputStream out = socket.getOutputStream();
            String target = authority(host) + ":" + port;
            out.write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            if (user != null && !user.isBlank() && password != null) {
                writeBasic(out, user, password);
            }
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            int status = readStatus(socket.getInputStream());
            if (status / 100 != 2) {
                throw new IOException(switch (status) {
                    case 407 -> "Der Proxy verlangt eine Anmeldung (HTTP 407)";
                    case 403 -> "Der Proxy lässt " + target + " nicht zu (HTTP 403)";
                    default -> "Der Proxy hat den Tunnel zu " + target + " abgelehnt (HTTP " + status + ")";
                });
            }
            socket.setSoTimeout(0);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    /** IPv6-Adressen in eckigen Klammern, genau einmal. */
    static String authority(String host) {
        String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        return bare.indexOf(':') >= 0 ? "[" + bare + "]" : bare;
    }

    private static void writeBasic(OutputStream out, String user, char[] password) throws IOException {
        byte[] name = (user + ":").getBytes(StandardCharsets.UTF_8);
        ByteBuffer secret = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] plain = new byte[name.length + secret.remaining()];
        System.arraycopy(name, 0, plain, 0, name.length);
        secret.get(plain, name.length, secret.remaining());
        byte[] encoded = Base64.getEncoder().encode(plain);
        try {
            out.write("Proxy-Authorization: Basic ".getBytes(StandardCharsets.US_ASCII));
            out.write(encoded);
            out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        } finally {
            Arrays.fill(plain, (byte) 0);
            Arrays.fill(encoded, (byte) 0);
            if (secret.hasArray()) {
                Arrays.fill(secret.array(), (byte) 0);
            }
        }
    }

    /** Liest Statuszeile und Kopfzeilen bis zur Leerzeile, aber kein Byte des Tunnels danach. */
    static int readStatus(InputStream in) throws IOException {
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("Der Proxy hat die Verbindung ohne Antwort geschlossen");
            }
            header.write(next);
            if (header.size() > MAX_HEADER) {
                throw new IOException("Die Antwort des Proxys ist zu lang");
            }
            matched = (next == '\r' && (matched == 0 || matched == 2)) || (next == '\n' && (matched == 1
                    || matched == 3)) ? matched + 1 : next == '\r' ? 1 : 0;
        }
        String first = header.toString(StandardCharsets.ISO_8859_1).lines().findFirst().orElse("");
        String[] parts = first.split(" ");
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new IOException("Unerwartete Antwort des Proxys");
        }
        try {
            return Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new IOException("Unerwartete Antwort des Proxys");
        }
    }
}
