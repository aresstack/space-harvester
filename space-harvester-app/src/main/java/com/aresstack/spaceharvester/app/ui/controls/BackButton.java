package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.util.List;
import java.util.Objects;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Zurück wie im Browser (Angelo, 24.11.2026): ein runder, plastisch hervorstehender Knopf mit Pfeil – das eine
 * Zurück jeder Ansicht und jeder Perspektive. Ein Klick geht einen Schritt zurück. Gedrückt halten (wie in Edge) oder
 * Rechtsklick öffnet den Verlauf mit Namen: oben die Vorwärts-Schritte (der nächste direkt über der Trennlinie),
 * darunter die Rückwärts-Schritte (der vorige zuoberst); ein Eintrag springt beliebig weit. Vor gibt es als
 * Maustaste ({@link MouseNavigation}) und im Verlauf, nicht als zweiten Knopf.
 *
 * <p>Den Verlauf liefert die Ansicht über {@link Navigator}; der Knopf merkt sich selbst nichts.
 */
public final class BackButton extends JButton {

    /** Durchmesser des Knopfs. */
    public static final int DIAMETER = 28;
    /** So lange gedrückt halten, bis der Verlauf aufgeht (wie in Edge). */
    public static final int HOLD_MS = HoldGesture.HOLD_MS;
    /** So viele Einträge je Richtung zeigt der Verlauf höchstens. */
    public static final int MAX_ENTRIES = 15;
    /** Platz unter dem Knopf für den Schatten. */
    private static final int SHADOW = 2;
    private static final Color TOP = AppColors.of("comic.buttonPlate");
    private static final Color BOTTOM = AppColors.of("app.BackButton.bottom");
    private static final Color SHADOW_COLOR = AppColors.of("app.BackButton.shadowColor");
    private static final Color DISABLED_FILL = AppColors.of("app.BackButton.disabledFill");
    private static final float ARROW_STROKE = 2.2f;
    private static final float RIM_STROKE = 1.6f;

    /**
     * Der Verlauf einer Ansicht, je Richtung der nächste Schritt zuerst.
     *
     * @param forward Namen der Vorwärts-Schritte, der nächste zuerst
     * @param back    Namen der Rückwärts-Schritte, der vorige zuerst
     */
    public record History(List<String> forward, List<String> back) {
        public static final History EMPTY = new History(List.of(), List.of());

        public History {
            forward = List.copyOf(forward);
            back = List.copyOf(back);
        }
    }

    /** Was der Knopf von der Ansicht braucht. */
    public interface Navigator {
        /** Der Verlauf, wie er gerade ist. */
        History history();

        /** Springt {@code steps} Schritte: negativ zurück, positiv vor. */
        void go(int steps);

        /** Ein Navigator aus Verlauf und Sprung, etwa für die Bereiche des Command Deck. */
        static Navigator of(java.util.function.Supplier<History> history, java.util.function.IntConsumer go) {
            Objects.requireNonNull(history, "history");
            Objects.requireNonNull(go, "go");
            return new Navigator() {
                @Override
                public History history() {
                    return history.get();
                }

                @Override
                public void go(int steps) {
                    go.accept(steps);
                }
            };
        }
    }

    private Navigator navigator;
    private final HoldGesture hold;
    private JPopupMenu menu;

    public BackButton(String tooltip) {
        setToolTipText(tooltip + " – gedrückt halten: Verlauf");
        getAccessibleContext().setAccessibleName(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        hold = HoldGesture.install(this, this::showHistory);
        addActionListener(e -> {
            if (navigator != null && !navigator.history().back().isEmpty()) {
                navigator.go(-1);
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showHistory();
                }
            }
        });
        refresh();
    }

    /** Verbindet den Knopf mit dem Verlauf einer Ansicht und stellt ihn auf deren Stand. */
    public BackButton navigator(Navigator value) {
        navigator = Objects.requireNonNull(value, "navigator");
        refresh();
        return this;
    }

    /** Nach jedem Schritt der Ansicht: an, sobald es zurück oder vor etwas gibt. */
    public void refresh() {
        History history = navigator == null ? History.EMPTY : navigator.history();
        setEnabled(!history.back().isEmpty() || !history.forward().isEmpty());
        repaint();
    }

    /** Ob ein Klick zurückgehen würde (sonst ist nur Vorwärts im Verlauf). */
    boolean canGoBack() {
        return navigator != null && !navigator.history().back().isEmpty();
    }

    /** Für Tests: der Verlauf als Menü, wie Gedrückthalten ihn zeigt. */
    JPopupMenu historyMenu() {
        History history = navigator == null ? History.EMPTY : navigator.history();
        JPopupMenu popup = new JPopupMenu();
        List<String> forward = history.forward();
        int forwardCount = Math.min(MAX_ENTRIES, forward.size());
        for (int i = forwardCount - 1; i >= 0; i--) {
            popup.add(entry(forward.get(i), i + 1));
        }
        List<String> back = history.back();
        int backCount = Math.min(MAX_ENTRIES, back.size());
        if (forwardCount > 0 && backCount > 0) {
            popup.addSeparator();
        }
        for (int i = 0; i < backCount; i++) {
            popup.add(entry(back.get(i), -(i + 1)));
        }
        return popup;
    }

    private JMenuItem entry(String name, int steps) {
        JMenuItem item = new JMenuItem(name == null || name.isBlank() ? "(ohne Titel)" : name);
        item.addActionListener(e -> navigator.go(steps));
        return item;
    }

    private void showHistory() {
        hold.cancel();
        if (!isEnabled() || navigator == null || !isShowing()) {
            return;
        }
        // Der Druck wird zum Menü, nicht zum Klick: Loslassen geht dann nicht noch einen Schritt zurück.
        getModel().setArmed(false);
        getModel().setPressed(false);
        menu = historyMenu();
        if (menu.getComponentCount() > 0) {
            menu.show(this, 0, getHeight());
        }
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            boolean enabled = isEnabled();
            boolean pressed = enabled && getModel().isArmed() && getModel().isPressed();
            boolean hover = enabled && getModel().isRollover();
            double d = Math.min(getWidth(), getHeight() - SHADOW) - 1;
            double x = (getWidth() - d) / 2.0;
            double y = pressed ? SHADOW / 2.0 : 0;
            Ellipse2D circle = new Ellipse2D.Double(x, y, d, d);
            if (enabled && !pressed) {
                g.setColor(SHADOW_COLOR);
                g.fill(new Ellipse2D.Double(x, y + SHADOW, d, d));
            }
            if (enabled) {
                // Gewölbt: oben hell, unten dunkler; gedrückt umgekehrt, wie eingedellt.
                g.setPaint(pressed
                        ? new GradientPaint(0, (float) y, BOTTOM, 0, (float) (y + d), TOP)
                        : new GradientPaint(0, (float) y, TOP, 0, (float) (y + d), BOTTOM));
            } else {
                g.setColor(DISABLED_FILL);
            }
            g.fill(circle);
            g.setStroke(new BasicStroke(hover ? RIM_STROKE : 1f));
            g.setColor(hover ? Comic.YELLOW : Comic.LINE);
            g.draw(circle);
            paintArrow(g, x + d / 2, y + d / 2, d, enabled && canGoBack() ? Comic.INK : Comic.MUTED);
        } finally {
            g.dispose();
        }
    }

    private static void paintArrow(Graphics2D g, double cx, double cy, double d, Color color) {
        double half = d * 0.22;
        double head = d * 0.17;
        Path2D arrow = new Path2D.Double();
        arrow.moveTo(cx + half, cy);
        arrow.lineTo(cx - half, cy);
        arrow.moveTo(cx - half + head, cy - head);
        arrow.lineTo(cx - half, cy);
        arrow.lineTo(cx - half + head, cy + head);
        g.setColor(color);
        g.setStroke(new BasicStroke(ARROW_STROKE, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(arrow);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(DIAMETER, DIAMETER + SHADOW);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }
}
