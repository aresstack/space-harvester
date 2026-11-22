package com.aresstack.spaceharvester.deck.terminal.openterm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Ein winziger TN3270-Host für Tests und Bildschirmfotos: handelt Terminaltyp, EOR und Binärmodus aus und schickt
 * ein festes Bild (geschützte Zeilen, ein Eingabefeld mit Schreibmarke). Was der Client schickt, wird nur
 * mitgeschrieben, nie beantwortet. Keine echten Hostnamen oder Inhalte.
 */
public final class FakeTn3270Host implements AutoCloseable {

    private static final int IAC = 255;
    private static final int SB = 250;
    private static final int SE = 240;
    private static final int WILL = 251;
    private static final int DO = 253;
    private static final int EOR = 239;
    private static final int OPT_BINARY = 0;
    private static final int OPT_TTYPE = 24;
    private static final int OPT_EOR = 25;
    /** Kodierung von 6-Bit-Werten in Puffer- und Attributbytes (3270-Codetabelle). */
    private static final int[] CODES = {
        0x40, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5, 0xC6, 0xC7, 0xC8, 0xC9, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F,
        0x50, 0xD1, 0xD2, 0xD3, 0xD4, 0xD5, 0xD6, 0xD7, 0xD8, 0xD9, 0x5A, 0x5B, 0x5C, 0x5D, 0x5E, 0x5F,
        0x60, 0x61, 0xE2, 0xE3, 0xE4, 0xE5, 0xE6, 0xE7, 0xE8, 0xE9, 0x6A, 0x6B, 0x6C, 0x6D, 0x6E, 0x6F,
        0xF0, 0xF1, 0xF2, 0xF3, 0xF4, 0xF5, 0xF6, 0xF7, 0xF8, 0xF9, 0x7A, 0x7B, 0x7C, 0x7D, 0x7E, 0x7F,
    };

    /** Eine Zeile des Bildes: Position und Text, geschützt und hell oder als Eingabefeld. */
    public record Line(int row, int column, String text, boolean input) {
    }

    private final ServerSocket server;
    private final List<Line> screen;
    private final Charset codePage;
    private final List<Socket> clients = new CopyOnWriteArrayList<>();
    private final ByteArrayOutputStream received = new ByteArrayOutputStream();

    public FakeTn3270Host(List<Line> screen, String codePage) throws IOException {
        this.server = new ServerSocket(0, 5, InetAddress.getLoopbackAddress());
        this.screen = List.copyOf(screen);
        this.codePage = Charset.forName(codePage);
        Thread.ofVirtual().name("fake-tn3270").start(this::serve);
    }

    public int port() {
        return server.getLocalPort();
    }

    /** Alles, was Clients bisher geschickt haben. */
    public byte[] received() {
        synchronized (received) {
            return received.toByteArray();
        }
    }

    private void serve() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                clients.add(client);
                Thread.ofVirtual().start(() -> talk(client));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void talk(Socket client) {
        try (client) {
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            Thread.ofVirtual().start(() -> drain(in));
            out.write(new byte[] {(byte) IAC, (byte) DO, OPT_TTYPE});
            out.flush();
            Thread.sleep(150);
            out.write(new byte[] {(byte) IAC, (byte) SB, OPT_TTYPE, 1, (byte) IAC, (byte) SE});
            out.flush();
            Thread.sleep(150);
            out.write(new byte[] {(byte) IAC, (byte) DO, OPT_EOR, (byte) IAC, (byte) WILL, OPT_EOR,
                (byte) IAC, (byte) DO, OPT_BINARY, (byte) IAC, (byte) WILL, OPT_BINARY});
            out.flush();
            Thread.sleep(250);
            out.write(dataStream());
            out.write(new byte[] {(byte) IAC, (byte) EOR});
            out.flush();
            while (!client.isClosed() && !server.isClosed()) {
                Thread.sleep(100);
            }
        } catch (IOException | InterruptedException e) {
            // Client weg oder Test zu Ende.
        }
    }

    private void drain(InputStream in) {
        byte[] buffer = new byte[1024];
        try {
            int n;
            while ((n = in.read(buffer)) > 0) {
                synchronized (received) {
                    received.write(buffer, 0, n);
                }
            }
        } catch (IOException ignored) {
            // zu
        }
    }

    /** Erase/Write mit Tastatur frei, dann je Zeile Pufferadresse, Feldanfang und Text; Schreibmarke ins Feld. */
    byte[] dataStream() {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        data.write(0xF5);
        data.write(0xC3);
        for (Line line : screen) {
            address(data, line.row() * 80 + line.column());
            data.write(0x1D);
            // Geschützt und hell (0x28) bzw. ungeschützt (0x00), über die Codetabelle.
            data.write(CODES[line.input() ? 0x00 : 0x28]);
            if (line.input()) {
                data.write(0x13);
            }
            byte[] text = line.text().getBytes(codePage);
            for (byte b : text) {
                data.write(b);
                if ((b & 0xFF) == IAC) {
                    data.write(IAC);
                }
            }
            if (line.input()) {
                // Feldende: ein geschütztes Attribut nach dem Eingabebereich.
                address(data, line.row() * 80 + line.column() + 1 + Math.max(8, line.text().length()));
                data.write(0x1D);
                data.write(CODES[0x20]);
            }
        }
        return data.toByteArray();
    }

    private static void address(ByteArrayOutputStream data, int position) {
        data.write(0x11);
        data.write(CODES[(position >> 6) & 0x3F]);
        data.write(CODES[position & 0x3F]);
    }

    @Override
    public void close() throws IOException {
        server.close();
        for (Socket client : clients) {
            client.close();
        }
    }
}
