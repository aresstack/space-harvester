package com.aresstack.spaceharvester.app.ui.markdown;

import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Point;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;

/** Route vertical wheel input to the transcript and Shift+wheel to the horizontal block scrollbar. */
final class NestedScrollWheelRouter implements MouseWheelListener {

    private final JScrollPane scrollPane;
    private double pendingRotation;

    private NestedScrollWheelRouter(JScrollPane scrollPane) {
        this.scrollPane = scrollPane;
    }

    /** Ersetzt das eingebaute Mausrad von {@code scrollPane} durch das Scroll-Chaining. */
    static void install(JScrollPane scrollPane) {
        scrollPane.setWheelScrollingEnabled(false);
        scrollPane.addMouseWheelListener(new NestedScrollWheelRouter(scrollPane));
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent event) {
        double rotation = event.getPreciseWheelRotation();
        if (rotation == 0) {
            event.consume();
            return;
        }
        JScrollBar bar = barFor(event);
        if (canScroll(bar, rotation)) {
            scroll(bar, event, rotation);
            event.consume();
            return;
        }
        pendingRotation = 0;
        forwardToParent(event);
    }

    /** Route ordinary wheel input through full-height blocks to the surrounding transcript. */
    private JScrollBar barFor(MouseWheelEvent event) {
        return event.isShiftDown() ? scrollPane.getHorizontalScrollBar() : null;
    }

    static boolean canScroll(JScrollBar bar, double rotation) {
        if (bar == null || !bar.isVisible()) {
            return false;
        }
        int value = bar.getValue();
        if (rotation < 0) {
            return value > bar.getMinimum();
        }
        return value < bar.getMaximum() - bar.getVisibleAmount();
    }

    private void scroll(JScrollBar bar, MouseWheelEvent event, double rotation) {
        int direction = rotation < 0 ? -1 : 1;
        int increment = event.getScrollType() == MouseWheelEvent.WHEEL_BLOCK_SCROLL
                ? bar.getBlockIncrement(direction)
                : bar.getUnitIncrement(direction) * event.getScrollAmount();
        // Accumulate fractional touchpad rotations so slow swipes still move the content.
        pendingRotation += rotation * Math.max(1, increment);
        int delta = (int) pendingRotation;
        pendingRotation -= delta;
        if (delta != 0) {
            bar.setValue(bar.getValue() + delta);
        }
    }

    private void forwardToParent(MouseWheelEvent event) {
        JScrollPane parent = ancestorScrollPane(scrollPane.getParent());
        if (parent == null) {
            return;
        }
        Point point = SwingUtilities.convertPoint(scrollPane, event.getPoint(), parent);
        MouseWheelEvent forwarded = new MouseWheelEvent(parent, event.getID(), event.getWhen(),
                event.getModifiersEx(), point.x, point.y, event.getXOnScreen(),
                event.getYOnScreen(), event.getClickCount(), event.isPopupTrigger(), event.getScrollType(),
                event.getScrollAmount(), event.getWheelRotation(), event.getPreciseWheelRotation());
        parent.dispatchEvent(forwarded);
        event.consume();
    }

    private static JScrollPane ancestorScrollPane(Component component) {
        Component current = component;
        while (current != null) {
            if (current instanceof JScrollPane) {
                return (JScrollPane) current;
            }
            current = current.getParent();
        }
        return null;
    }
}
