package com.aresstack.spaceharvester.app.ui.controls;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/**
 * Reicht das Mausrad an die umgebende Scrollfläche weiter, wenn eine innere Scrollfläche (etwa eine Tabelle in einer
 * Einstellungsseite) nichts mehr zu scrollen hat: ohne senkrechten Balken sofort, sonst sobald sie oben bzw. unten
 * angekommen ist. Vorher verschluckte die innere Fläche das Rad, und die Seite blieb stehen, obwohl der Zeiger nur
 * über der Tabelle lag. Die eigene Scrollbewegung der inneren Fläche bleibt unverändert (ihre Listener laufen weiter).
 */
public final class WheelForwarding implements MouseWheelListener {

    private final JScrollPane inner;
    private final MouseWheelListener[] own;

    private WheelForwarding(JScrollPane inner, MouseWheelListener[] own) {
        this.inner = inner;
        this.own = own;
    }

    /** Hängt die Weiterleitung an {@code inner}; idempotent. */
    public static JScrollPane install(JScrollPane inner) {
        for (MouseWheelListener listener : inner.getMouseWheelListeners()) {
            if (listener instanceof WheelForwarding) {
                return inner;
            }
        }
        MouseWheelListener[] own = inner.getMouseWheelListeners();
        for (MouseWheelListener listener : own) {
            inner.removeMouseWheelListener(listener);
        }
        inner.addMouseWheelListener(new WheelForwarding(inner, own));
        return inner;
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
        JScrollPane outer = outer();
        if (outer != null && !canScroll(event)) {
            outer.dispatchEvent(SwingUtilities.convertMouseEvent(inner, event, outer));
            return;
        }
        for (MouseWheelListener listener : own) {
            listener.mouseWheelMoved(event);
        }
    }

    /** Ob die innere Fläche in Richtung des Rads noch Weg hat; waagerechtes Rad (Shift) bleibt bei ihr. */
    private boolean canScroll(MouseWheelEvent event) {
        if (event.isShiftDown()) {
            return true;
        }
        JScrollBar bar = inner.getVerticalScrollBar();
        if (bar == null || !bar.isVisible()) {
            return false;
        }
        int rotation = event.getWheelRotation() != 0 ? event.getWheelRotation()
                : (int) Math.signum(event.getPreciseWheelRotation());
        if (rotation < 0) {
            return bar.getValue() > bar.getMinimum();
        }
        return bar.getValue() + bar.getVisibleAmount() < bar.getMaximum();
    }

    private JScrollPane outer() {
        Component parent = inner.getParent();
        return parent == null ? null : (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, parent);
    }
}
