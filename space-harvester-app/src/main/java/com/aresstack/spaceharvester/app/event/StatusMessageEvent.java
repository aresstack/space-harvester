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

        public Payload(String text, int durationMs, Severity severity) {
            this(text, durationMs, severity, false);
        }

        public Payload(String text, int durationMs, Severity severity, boolean closable) {
            this.text = text;
            this.durationMs = durationMs <= 0 ? 3000 : durationMs;
            this.severity = severity;
            this.closable = closable;
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
    }

    private final Payload payload;

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
