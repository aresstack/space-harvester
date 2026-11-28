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
    private final JComponent slot;
    private final Drag drag = new Drag();
    private boolean active = true;
    private java.util.function.BooleanSupplier busy = () -> false;

    private DividerRail(JSplitPane pane, JComponent rail, EdgeOverlay overlay, JComponent slot) {
        this.pane = pane;
        this.rail = rail;
        this.overlay = overlay;
        this.slot = slot;
        overlay.anchor(slot, this::center, this::focus);
        listen(rail, drag);
        for (Component part : rail.getComponents()) {
            listen(part, drag);
        }
        // Symbole, die später wieder in die Leiste geschoben werden ({@link PearlString}), ziehen den Trenner ebenso.
        rail.addContainerListener(new java.awt.event.ContainerAdapter() {
            @Override
            public void componentAdded(java.awt.event.ContainerEvent event) {
                listen(event.getChild(), drag);
                follow();
            }

            @Override
            public void componentRemoved(java.awt.event.ContainerEvent event) {
                follow();
            }
        });
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
        int before = focus();
        // Unter der Leiste stehen auf demselben Platz noch einzelne Kugeln: Auch hinter ihnen ruht die Linie.
        int length = Math.max(rail.getPreferredSize().height,
                slot.getComponentCount() == 0 ? 0 : slot.getPreferredSize().height);
        ComicDivider.gap(pane, active ? before : 0, active ? length - before : 0);
        // Verteilt sich der Platz selbst über den Trenner, ruht die Linie hinter jedem seiner Teile.
        ComicDivider.gaps(pane, active && spanning && slot.getComponentCount() > 0
                && slot.getComponent(0) instanceof Beads beads && onDivider() ? beads.rests() : java.util.List.of());
    }

    /** Etwas, das sich selbst über die Länge eines Trenners verteilt und sagt, wo es die Linie verdeckt. */
    public interface Beads {
        /** Je Teil Anfang und Ende entlang des Trenners, von vorn nach hinten. */
        java.util.List<int[]> rests();
    }

    private boolean spanning;

    /**
     * Der Platz erstreckt sich über den ganzen Trenner, und was auf ihm steht, verteilt sich selbst darüber
     * ({@link PearlString}: die Leiste und jede Kugel auf Höhe ihres Bereichs).
     */
    public DividerRail spanning() {
        spanning = true;
        overlay.span(slot, this::line);
        follow();
        return this;
    }

    /** Die Linie des sichtbaren Trenners in der Fläche {@code overlay}; {@code null} ohne sichtbaren Trenner. */
    private java.awt.Rectangle line() {
        Component divider = divider();
        if (center() != null && divider != null) {
            return SwingUtilities.convertRectangle(divider.getParent(), divider.getBounds(), overlay);
        }
        // Zugeklappt gibt es keinen Trenner: Der Platz steht am Fensterrand, aber über dieselbe Länge wie der
        // Trenner, damit jedes Teil auf der Höhe bleibt, auf der es aufgeklappt sitzt (Angelo 28.11.2026).
        if (!active || pane.getParent() == null || !pane.isVisible() || pane.getHeight() <= 0
                || !SwingUtilities.isDescendingFrom(pane, overlay)) {
            return null;
        }
        java.awt.Rectangle along = SwingUtilities.convertRectangle(pane.getParent(), pane.getBounds(), overlay);
        return new java.awt.Rectangle(slot == overlay.left() ? 0 : overlay.getWidth(), along.y, 0, along.height);
    }

    /** Die Lücken der Linie neu bestimmen, nachdem sich etwas auf dem Platz bewegt hat. */
    public void rests() {
        gap();
    }

    /** Die Höhe im Platz, die auf der Mitte des Trenners liegt: die Mitte der Knopfleiste. */
    private int focus() {
        int height = rail.getPreferredSize().height;
        if (height <= 0) {
            height = slot.getComponentCount() == 0 ? 0 : slot.getPreferredSize().height;
        }
        return height / 2;
    }

    /**
     * Solange {@code busy} gilt, zieht die Leiste den Trenner nicht: Jemand anderes trägt gerade eines ihrer Symbole
     * ({@link PearlString}).
     */
    public void yieldWhile(java.util.function.BooleanSupplier busy) {
        this.busy = busy;
    }

    /** Ob gerade am Trenner gezogen wird. */
    public boolean dragging() {
        return drag.dragging;
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
        private int startY;
        private int startLocation;
        private boolean dragging;

        @Override
        public void mousePressed(MouseEvent event) {
            if (SwingUtilities.isLeftMouseButton(event) && onDivider()) {
                startX = event.getXOnScreen();
                startY = event.getYOnScreen();
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
                // Nur eine Bewegung zur Seite zieht den Trenner; nach oben oder unten wird ein Symbol herausgezogen.
                if (Math.abs(moved) <= DragSource.getDragThreshold() || busy.getAsBoolean()
                        || Math.abs(event.getYOnScreen() - startY) > Math.abs(moved)) {
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
