package com.aresstack.spaceharvester.app.event;

/**
 * Eine Statusmeldung für die Statuszeile, die nach {@code durationMs} wieder verschwindet; übernommen aus MainframeMate
 * ({@code de.bund.zrb.event.StatusMessageEvent}).
 */
public final class StatusMessageEvent implements ApplicationEvent<StatusMessageEvent.Payload> {

    public static final class Payload {
        private final String text;
        private final int durationMs;
        private final Severity severity; // darf null sein
        private final boolean closable;
        private final String replaces; // darf null sein

        public Payload(String text, int durationMs, Severity severity) {
            this(text, durationMs, severity, false, null);
        }

        public Payload(String text, int durationMs, Severity severity, boolean closable) {
            this(text, durationMs, severity, closable, null);
        }

        Payload(String text, int durationMs, Severity severity, boolean closable, String replaces) {
            this.text = text;
            this.durationMs = durationMs <= 0 ? 3000 : durationMs;
            this.severity = severity;
            this.closable = closable;
            this.replaces = replaces;
        }

        public String getText() {
            return text;
        }

        public int getDurationMs() {
            return durationMs;
        }

        public Severity getSeverity() {
            return severity;
        }

        /** Ob die Meldung stehen bleibt, bis der Benutzer sie mit ✕ wegklickt (etwa das Ergebnis eines Exports). */
        public boolean isClosable() {
            return closable;
        }

        /** Wenn gesetzt, gilt die Meldung nur, solange die Statuszeile gerade diesen Text zeigt (eigene Meldung beenden). */
        public String getReplaces() {
            return replaces;
        }
    }

    private final Payload payload;

    /** Leert die Statuszeile nur, wenn sie gerade {@code shown} zeigt: eine Ansicht beendet ihre eigene Meldung. */
    public static StatusMessageEvent clear(String shown) {
        return new StatusMessageEvent(new Payload("", Integer.MAX_VALUE, Severity.INFO, false, shown));
    }

    private StatusMessageEvent(Payload payload) {
        this.payload = payload;
    }

    public StatusMessageEvent(String text) {
        this(text, 3000, null);
    }

    public StatusMessageEvent(String text, int durationMs) {
        this(text, durationMs, null);
    }

    public StatusMessageEvent(String text, int durationMs, Severity severity) {
        this(text, durationMs, severity, false);
    }

    /** @param closable die Meldung bleibt stehen, bis sie der Benutzer wegklickt ({@code durationMs} zählt dann nicht) */
    public StatusMessageEvent(String text, int durationMs, Severity severity, boolean closable) {
        this.payload = new Payload(text, closable ? Integer.MAX_VALUE : durationMs, severity, closable);
    }

    @Override
    public Payload getPayload() {
        return payload;
    }
}
