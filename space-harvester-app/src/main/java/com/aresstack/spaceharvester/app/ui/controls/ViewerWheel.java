package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Point;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Das Mausrad in allen Betrachtern (Bilder, PDF, Folien, Diagramme) nach einer Regel (Angelo, 20.11.2026):
 * <ul>
 *   <li>Rad allein <b>blättert</b> wie die Pfeile ◀ ▶: nächste bzw. vorige Seite, Folie oder Bild. Nur wenn der Inhalt
 *       hineingezoomt ist und in Radrichtung noch Weg hat, verschiebt das Rad erst den Ausschnitt; am Rand blättert es
 *       wieder.</li>
 *   <li><b>Strg</b>+Rad zoomt am Cursor (auf dem Mac auch Cmd).</li>
 *   <li><b>Shift</b>+Rad wechselt die Achse: verschiebt den Ausschnitt waagrecht, blättert nie.</li>
 * </ul>
 * Zwischen zwei Blättern liegt eine kurze Sperre, damit ein Touchpad mit vielen kleinen Radschritten nicht gleich
 * mehrere Seiten weiterspringt. Sie gilt über alle Betrachter hinweg (ein Rad, ein Benutzer), denn das Blättern zum
 * nächsten Anhang baut einen neuen Betrachter mit eigenem {@code ViewerWheel} auf, und die Sperre muss ihn überleben.
 */
public final class ViewerWheel implements MouseWheelListener {

    /** Was ein Betrachter dem Rad anbietet. Die Pfeile dürfen leer sein (Diagramm ohne Seiten). */
    public interface Target {
        /**
         * Verschiebt den Ausschnitt um {@code dx}, {@code dy} Bildschirmpixel (positiv = Inhalt nach rechts/unten).
         *
         * @return ob sich etwas bewegt hat, also noch Weg in dieser Richtung war
         */
        boolean pan(int dx, int dy);

        /** Zoomt um {@code factor} am Punkt {@code at} (Koordinaten der Quelle des Ereignisses). */
        void zoom(double factor, Point at);

        /** Wie der Pfeil ◀. */
        void previous();

        /** Wie der Pfeil ▶. */
        void next();
    }

    /** Pixel je Radschritt beim Verschieben. */
    static final int STEP_PX = 60;
    /** Zoomfaktor je Radschritt. */
    public static final double ZOOM_STEP = 1.15;
    /** Mindestabstand zweier Blätter-Schritte. */
    static final long PAGE_GAP_MS = 250;

    /** Zeitpunkt des letzten Blätterns, über alle Betrachter geteilt (nur auf dem EDT berührt). */
    private static long lastPageAt = Long.MIN_VALUE / 2;

    private final Target target;
    private final LongSupplier clock;

    public ViewerWheel(Target target) {
        this(target, System::currentTimeMillis);
    }

    ViewerWheel(Target target, LongSupplier clock) {
        this.target = Objects.requireNonNull(target, "target");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Vergisst die Blätter-Sperre (für Tests). */
    static void resetPageGap() {
        lastPageAt = Long.MIN_VALUE / 2;
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
        double rotation = event.getPreciseWheelRotation();
        if (rotation == 0) {
            rotation = event.getWheelRotation();
        }
        if (rotation == 0) {
            return;
        }
        event.consume();
        if (event.isControlDown() || event.isMetaDown()) {
            target.zoom(rotation < 0 ? ZOOM_STEP : 1.0 / ZOOM_STEP, event.getPoint());
            return;
        }
        int delta = (int) Math.round(rotation * STEP_PX);
        if (delta == 0) {
            delta = rotation < 0 ? -1 : 1;
        }
        if (event.isShiftDown()) {
            target.pan(-delta, 0);
            return;
        }
        if (target.pan(0, -delta)) {
            return;
        }
        long now = clock.getAsLong();
        if (now - lastPageAt < PAGE_GAP_MS) {
            return;
        }
        lastPageAt = now;
        if (rotation > 0) {
            target.next();
        } else {
            target.previous();
        }
    }

    /**
     * Die neue Lage einer Achse beim Verschieben: der Inhalt der Größe {@code size} darf im Sichtbereich {@code view}
     * nur so weit wandern, dass kein Rand mehr frei wird, als es schon ist; passt er ganz hinein, bleibt er stehen.
     * Ein Ausschnitt, den der Benutzer per Ziehen aus dem Sichtbereich geschoben hat, springt nicht zurück.
     */
    public static double panTo(double current, double wanted, double size, double view) {
        if (size <= view) {
            return current;
        }
        double low = Math.min(view - size, current);
        double high = Math.max(0, current);
        return Math.max(low, Math.min(high, wanted));
    }
}
