package com.aresstack.spaceharvester.deck.ftp.jes;

import java.util.Objects;

/**
 * Wie die DD-Namen der Spool-Dateien ermittelt werden, die Einstellung „DD-Name Erkennung“ aus MainframeMate.
 *
 * @param mode             Verfahren
 * @param connections      parallele Verbindungen für das Nachladen einzeln (1–10)
 * @param backgroundProbe  im Verfahren FAST die Namen danach einzeln nachladen und berichtigen
 */
public record SpoolNaming(Mode mode, int connections, boolean backgroundProbe) {

    /** Verfahren der DD-Namen. */
    public enum Mode {
        /** Liste des Hosts; ohne Liste die Gesamtausgabe in Abschnitte zerlegt, Namen aus dem Inhalt. */
        FAST("Schnell (Gesamtausgabe)"),
        /** Liste des Hosts; ohne Liste jede Spool-Datei einzeln abrufen, Name aus Antwort oder Inhalt. */
        PROBE("Einzeln abrufen"),
        /** Sofort die Gesamtausgabe mit SPOOL#n, die Namen werden im Hintergrund nachgeladen. */
        OFF("Aus (SPOOL#n, Namen nachladen)");

        private final String label;

        Mode(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public static final SpoolNaming DEFAULT = new SpoolNaming(Mode.FAST, 1, false);

    public SpoolNaming {
        mode = Objects.requireNonNullElse(mode, Mode.FAST);
        if (connections < 1 || connections > 10) {
            throw new IllegalArgumentException("Parallele Verbindungen müssen zwischen 1 und 10 liegen, nicht "
                    + connections);
        }
    }

    /** Ob nach dem Laden die Namen einzeln nachgeladen werden. */
    public boolean probeAfterwards() {
        return mode == Mode.OFF || (mode == Mode.FAST && backgroundProbe);
    }
}
