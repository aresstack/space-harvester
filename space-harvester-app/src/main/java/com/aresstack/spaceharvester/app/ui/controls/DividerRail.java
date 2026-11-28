package com.aresstack.spaceharvester.app.ui.controls;

import java.awt.Component;
import java.awt.Container;
import java.awt.Point;
import java.awt.dnd.DragSource;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyBoundsAdapter;
import java.awt.event.HierarchyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.AbstractButton;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * Macht eine Knopfleiste zum Griff eines Trenners (Angelo 28.11.2026): Der Trenner ({@link ComicDivider}) bleibt
 * derselbe wie überall, mit seiner Linie von oben bis unten; nur an der Stelle des kleinen gelben Griffs, in der
 * Mitte, sitzt die senkrechte Knopfleiste (Angelo 28.11.2026: nur der Griff wird ersetzt). Hinter der Leiste ist die
 * Linie unterbrochen, damit sie durch die durchscheinende Leiste nicht zu sehen ist. An der Leiste lässt er sich ziehen wie an der Linie darüber und darunter; ein Klick auf einen Knopf
 * schaltet um oder klappt zu. Ohne sichtbaren Trenner (Leiste zugeklappt) steht dieselbe Knopfleiste am Fensterrand
 * ({@link EdgeOverlay}) und öffnet von dort.
 */
public final class DividerRail {

    private final JSplitPane pane;
    private final JComponent rail;
    private final EdgeOverlay overlay;
    private boolean active = true;

    private DividerRail(JSplitPane pane, JComponent rail, EdgeOverlay overlay, JComponent slot) {
        this.pane = pane;
        this.rail = rail;
        this.overlay = overlay;
        overlay.anchor(slot, this::center);
        Drag drag = new Drag();
        listen(rail, drag);
        for (Component part : rail.getComponents()) {
            listen(part, drag);
        }
        Component divider = divider();
        if (divider != null) {
            divider.addComponentListener(new ComponentAdapter() {
                @Override
                public void componentMoved(ComponentEvent event) {
                    follow();
                }

                @Override
                public void componentResized(ComponentEvent event) {
                    follow();
                }
            });
            divider.addHierarchyBoundsListener(new HierarchyBoundsAdapter() {
                @Override
                public void ancestorMoved(HierarchyEvent event) {
                    follow();
                }

                @Override
                public void ancestorResized(HierarchyEvent event) {
                    follow();
                }
            });
        }
        pane.addPropertyChangeListener(JSplitPane.DIVIDER_SIZE_PROPERTY, event -> follow());
        ComicDivider.grip(pane, false);
        gap();
    }

    /** Die Linie des Trenners ist hinter der Knopfleiste unterbrochen, damit sie nicht durchscheint. */
    private void gap() {
        ComicDivider.gap(pane, active ? rail.getPreferredSize().height : 0);
    }

    /**
     * Setzt {@code rail} als Griff auf den Trenner von {@code pane}; {@code slot} ist der Platz der Fläche
     * {@code overlay}, auf dem die Leiste steht.
     */
    public static DividerRail install(JSplitPane pane, JComponent rail, EdgeOverlay overlay, JComponent slot) {
        return new DividerRail(pane, rail, overlay, slot);
    }

    /**
     * Ob die Knopfleiste gerade der Griff ist. Abgeschaltet (etwa in einer Perspektive, deren Leiste keinen
     * Umschalter hat) zeigt der Trenner wieder seinen gelben Griff.
     */
    public void active(boolean active) {
        if (this.active != active) {
            this.active = active;
            ComicDivider.grip(pane, !active);
            gap();
            follow();
        }
    }

    public boolean active() {
        return active;
    }

    /** Ob die Leiste gerade auf einem sichtbaren Trenner sitzt (sonst steht sie am Fensterrand). */
    public boolean onDivider() {
        return center() != null;
    }

    private Component divider() {
        return pane.getUI() instanceof BasicSplitPaneUI basic ? basic.getDivider() : null;
    }

    /**
     * Die Mitte des Trenners in der Fläche {@code overlay}, also die Stelle seines gelben Griffs; {@code null} ohne
     * sichtbaren Trenner.
     */
    private Point center() {
        Component divider = divider();
        if (!active || divider == null || pane.getDividerSize() <= 0
                || !SwingUtilities.isDescendingFrom(divider, overlay)) {
            return null;
        }
        for (Component part = divider; part != overlay; part = part.getParent()) {
            if (!part.isVisible()) {
                return null;
            }
        }
        Container parent = divider.getParent();
        return SwingUtilities.convertPoint(parent, new Point(divider.getX() + divider.getWidth() / 2,
                divider.getY() + divider.getHeight() / 2), overlay);
    }

    private void follow() {
        gap();
        overlay.revalidate();
        overlay.repaint();
    }

    private static void listen(Component part, Drag drag) {
        part.addMouseListener(drag);
        part.addMouseMotionListener(drag);
    }

    /**
     * Ziehen an der Knopfleiste verschiebt den Trenner. Erst ab der Ziehschwelle des Systems gilt die Bewegung als
     * Ziehen; darunter bleibt es ein Klick auf den Knopf. Ein begonnenes Ziehen löst den Knopf nicht mehr aus.
     */
    private final class Drag extends MouseAdapter {
        private static final int IDLE = Integer.MIN_VALUE;
        private int startX = IDLE;
        private int startLocation;
        private boolean dragging;

        @Override
        public void mousePressed(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event) && onDivider()) {
                startX = event.getXOnScreen();
                startLocation = pane.getDividerLocation();
                dragging = false;
            }
        }

        @Override
        public void mouseDragged(MouseEvent event) {
            if (startX == IDLE) {
                return;
            }
            int moved = event.getXOnScreen() - startX;
            if (!dragging) {
                if (Math.abs(moved) <= DragSource.getDragThreshold()) {
                    return;
                }
                dragging = true;
                if (event.getComponent() instanceof AbstractButton button) {
                    button.getModel().setArmed(false);
                    button.getModel().setPressed(false);
                }
            }
            // Wie beim Ziehen an der Linie: nie über die kleinste Breite einer der beiden Seiten hinaus.
            int low = pane.getMinimumDividerLocation();
            int high = Math.max(low, pane.getMaximumDividerLocation());
            pane.setDividerLocation(Math.max(low, Math.min(high, startLocation + moved)));
        }

        @Override
        public void mouseReleased(MouseEvent event) {
            startX = IDLE;
            dragging = false;
        }
    }
}
