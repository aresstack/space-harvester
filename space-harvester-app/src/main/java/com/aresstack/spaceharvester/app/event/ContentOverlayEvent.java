package com.aresstack.spaceharvester.app.event;

import javax.swing.JComponent;
import java.util.Objects;

/**
 * Eine Ansicht legt eine Fläche über den Inhaltsbereich des Hauptfensters oder nimmt sie wieder weg (Angelo
 * 22.11.2026: Der Podcast zeigt zu jedem Thema ein Bild im Inhaltsbereich). Das Hauptfenster zeigt höchstens eine;
 * {@link #hide} nimmt nur die Fläche weg, die gerade liegt. Der Inhalt darunter bleibt, wie er ist.
 */
public final class ContentOverlayEvent implements ApplicationEvent<ContentOverlayEvent.Payload> {

    /** {@code overlay} zeigen ({@code show}) oder wegnehmen. */
    public record Payload(JComponent overlay, boolean show) {
        public Payload {
            Objects.requireNonNull(overlay, "overlay");
        }
    }

    private final Payload payload;

    private ContentOverlayEvent(Payload payload) {
        this.payload = payload;
    }

    public static ContentOverlayEvent show(JComponent overlay) {
        return new ContentOverlayEvent(new Payload(overlay, true));
    }

    public static ContentOverlayEvent hide(JComponent overlay) {
        return new ContentOverlayEvent(new Payload(overlay, false));
    }

    @Override
    public Payload getPayload() {
        return payload;
    }
}
