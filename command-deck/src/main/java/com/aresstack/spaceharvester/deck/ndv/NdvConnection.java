package com.aresstack.spaceharvester.deck.ndv;

import java.io.IOException;
import java.util.List;

/**
 * Port: eine angemeldete Verbindung zu einem NDV-Server. Nicht threadsicher (eine TCP-Verbindung, ein Gespräch nach
 * dem anderen); {@link NdvSession} reiht alle Aufrufe hintereinander. Alle Aufrufe blockieren.
 */
public interface NdvConnection {

    /** Bibliotheken, deren Name zum Muster passt ({@code *} für alle, {@code AB*} für alle mit AB). */
    List<String> libraries(String filter) throws IOException;

    /**
     * Objekte einer Bibliothek, Seite für Seite, so wie der Server sie schickt. Gibt der Empfänger {@code false}
     * zurück, hört die Auflistung auf (Abbrechen; was schon kam, bleibt).
     *
     * @return wie viele Objekte gemeldet wurden
     */
    int objects(String library, String filter, Page page) throws IOException;

    /** Quelltext eines Objekts, Zeilen mit {@code \n} getrennt. */
    String read(NdvObject object) throws IOException;

    /** Speichert den Quelltext eines Objekts auf dem Server. */
    void write(NdvObject object, String source) throws IOException;

    /** Ob die Verbindung noch steht. */
    boolean open();

    /** Trennt; mehrfach aufrufen ist harmlos. */
    void close();

    /** Empfänger einer Seite der Objektliste. */
    @FunctionalInterface
    interface Page {

        /** @return {@code false}, um aufzuhören */
        boolean accept(List<NdvObject> objects, int totalSoFar);
    }
}
