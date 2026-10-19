package com.aresstack.enterpriseai.proasteion.emporion.holkas.ndv.pal.core.api;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * PAL-Protokollmitschnitt. In MainframeMate eine Trace-Datei über die Systemeigenschaft {@code PALTRACE}; hier
 * ohne globalen Dateizustand über {@code java.util.logging} (Stufe FINEST).
 */
public final class PalTrace {
    private static final Logger LOG = Logger.getLogger(PalTrace.class.getName());

    private PalTrace() {
    }

    public static void open(boolean append) throws IOException {
        // Mitschnitt läuft über java.util.logging; keine Datei zu öffnen.
    }

    public static void close() throws IOException {
        // nichts zu schließen
    }

    public static void flush() throws IOException {
        // nichts zu leeren
    }

    public static void header(String transactionName) throws IOException {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest("[" + Thread.currentThread().getId() + "] ------ Transaction '" + transactionName + "' ------");
        }
    }

    public static void buffer(byte[] data, boolean received, String sessionId) throws IOException {
        if (!LOG.isLoggable(Level.FINEST)) {
            return;
        }
        StringBuilder sb = new StringBuilder(received ? "<<===== Pal data from server <<=======" : "=====>> Pal data to server ======>>");
        for (int i = 0; i < data.length; i++) {
            if (i % 30 == 0) {
                sb.append("\r\n").append(String.format("%04d ", i));
            }
            sb.append(String.format("%02X ", data[i] & 0xFF));
        }
        LOG.finest(sb.toString());
    }

    public static void text(String text) throws IOException {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest(text);
        }
    }

    public static void type(String typeName, boolean received) throws IOException {
        if (LOG.isLoggable(Level.FINEST)) {
            LOG.finest((received ? "<<<<< " : ">>>> ") + typeName);
        }
    }
}
