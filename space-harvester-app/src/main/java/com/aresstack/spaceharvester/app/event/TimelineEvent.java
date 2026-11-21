package com.aresstack.spaceharvester.app.event;

import javax.swing.JComponent;
import java.util.Objects;

/**
 * Eine Ansicht zeigt eine Zeitleiste an der Stelle des Trenners über der Suchleiste (Angelo 21.11.2026: solange der
 * Podcast spielt, wird der Trenner zur Zeitleiste aus dem Video-Viewer) oder nimmt sie wieder weg. Das Hauptfenster
 * zeigt höchstens eine; {@link #hide} nimmt nur die Zeitleiste weg, die gerade steht.
 */
public final class TimelineEvent implements ApplicationEvent<TimelineEvent.Payload> {

    /** {@code timeline} zeigen ({@code show}) oder wegnehmen. */
    public record Payload(JComponent timeline, boolean show) {
        public Payload {
            Objects.requireNonNull(timeline, "timeline");
        }
    }

    private final Payload payload;

    private TimelineEvent(Payload payload) {
        this.payload = payload;
    }

    public static TimelineEvent show(JComponent timeline) {
        return new TimelineEvent(new Payload(timeline, true));
    }

    public static TimelineEvent hide(JComponent timeline) {
        return new TimelineEvent(new Payload(timeline, false));
    }

    @Override
    public Payload getPayload() {
        return payload;
    }
}
