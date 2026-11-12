package com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Kleiner FTP-Server auf 127.0.0.1 für Tests, wahlweise mit explizitem ({@code AUTH TLS}) oder implizitem FTPS und
 * {@code PROT P} auf der Datenverbindung. Kann gerade so viel, wie die Sitzungen des Adapters brauchen: Anmeldung,
 * {@code PBSZ}/{@code PROT}, {@code SYST}, {@code TYPE}/{@code STRU}/{@code MODE}/{@code SITE}, {@code PASV} und
 * {@code RETR}. Zertifikat: {@code localhost.jks} (CN und SAN localhost, 127.0.0.1).
 */
final class FakeFtpsServer implements Closeable {

    final Map<String, String> files = new LinkedHashMap<String, String>();
    /** Empfangene Befehle, {@code PASS} ohne Wert. */
    final List<String> commands = Collections.synchronizedList(new ArrayList<String>());
    /** Ob die Datenverbindungen verschlüsselt waren. */
    final List<Boolean> protectedData = Collections.synchronizedList(new ArrayList<Boolean>());

    private final boolean implicit;
    private final boolean tlsOffered;
    private final SSLContext ssl;
    private final ServerSocket control;
    private final Thread acceptor;

    /**
     * @param tlsOffered ob {@code AUTH TLS} angenommen wird (sonst 502 wie ein Server ohne FTPS)
     * @param implicit   TLS ab dem ersten Byte
     */
    FakeFtpsServer(boolean tlsOffered, boolean implicit) throws Exception {
        this.tlsOffered = tlsOffered;
        this.implicit = implicit;
        KeyStore store = KeyStore.getInstance("JKS");
        try (InputStream in = FakeFtpsServer.class.getResourceAsStream("localhost.jks")) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "changeit".toCharArray());
        ssl = SSLContext.getInstance("TLS");
        ssl.init(keys.getKeyManagers(), null, null);
        control = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
        acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "fake-ftps");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    int port() {
        return control.getLocalPort();
    }

    private void acceptLoop() {
        while (!control.isClosed()) {
            try (Socket socket = control.accept()) {
                serve(implicit ? serverSide(socket) : socket);
            } catch (IOException e) {
                // Verbindung beendet oder Handshake abgelehnt: nächste annehmen.
            }
        }
    }

    private SSLSocket serverSide(Socket plain) throws IOException {
        SSLSocket socket = (SSLSocket) ssl.getSocketFactory().createSocket(plain, null, plain.getPort(), true);
        socket.setUseClientMode(false);
        socket.startHandshake();
        return socket;
    }

    private void serve(Socket socket) throws IOException {
        BufferedReader in = reader(socket);
        Writer out = writer(socket);
        reply(out, "220 fake ready");
        boolean prot = false;
        ServerSocket data = null;
        String line;
        while ((line = in.readLine()) != null) {
            int space = line.indexOf(' ');
            String command = (space < 0 ? line : line.substring(0, space)).toUpperCase(Locale.ROOT);
            String argument = space < 0 ? "" : line.substring(space + 1);
            commands.add("PASS".equals(command) ? "PASS" : line);
            if ("AUTH".equals(command)) {
                if (!tlsOffered) {
                    reply(out, "502 AUTH not supported");
                    continue;
                }
                reply(out, "234 AUTH TLS OK");
                socket = serverSide(socket);
                in = reader(socket);
                out = writer(socket);
            } else if ("USER".equals(command)) {
                reply(out, "331 password required");
            } else if ("PASS".equals(command)) {
                reply(out, "secret".equals(argument) ? "230 logged in" : "530 login incorrect");
            } else if ("PBSZ".equals(command)) {
                reply(out, "200 PBSZ=0");
            } else if ("PROT".equals(command)) {
                prot = "P".equalsIgnoreCase(argument);
                reply(out, "200 PROT " + argument);
            } else if ("SYST".equals(command)) {
                reply(out, "215 UNIX Type: L8");
            } else if ("TYPE".equals(command) || "STRU".equals(command) || "MODE".equals(command)
                    || "SITE".equals(command)) {
                reply(out, "200 OK");
            } else if ("PASV".equals(command)) {
                data = prot ? ssl.getServerSocketFactory().createServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
                        : new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
                int port = data.getLocalPort();
                reply(out, "227 Entering Passive Mode (127,0,0,1," + (port >> 8) + "," + (port & 0xFF) + ")");
            } else if ("RETR".equals(command)) {
                String content = files.get(argument);
                if (content == null || data == null) {
                    reply(out, "550 not found");
                    continue;
                }
                reply(out, "150 opening data connection");
                try (Socket transfer = data.accept()) {
                    if (transfer instanceof SSLSocket) {
                        ((SSLSocket) transfer).startHandshake();
                    }
                    protectedData.add(transfer instanceof SSLSocket);
                    OutputStream stream = transfer.getOutputStream();
                    stream.write(content.getBytes(StandardCharsets.ISO_8859_1));
                    stream.flush();
                } finally {
                    data.close();
                    data = null;
                }
                reply(out, "226 transfer complete");
            } else if ("QUIT".equals(command)) {
                reply(out, "221 bye");
                return;
            } else {
                reply(out, "502 not implemented");
            }
        }
    }

    private static BufferedReader reader(Socket socket) throws IOException {
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.ISO_8859_1));
    }

    private static Writer writer(Socket socket) throws IOException {
        return new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.ISO_8859_1);
    }

    private static void reply(Writer out, String line) throws IOException {
        out.write(line + "\r\n");
        out.flush();
    }

    @Override
    public void close() throws IOException {
        control.close();
    }
}
