package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.util.Objects;
import javax.swing.JComponent;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Abbrechen eines Overlays als runde Fläche mit der Taste „Esc“, wie die Stopp-Fläche des {@link BusyOverlay}: neutral
 * gedämpft, unter der Maus kräftig rot, Handcursor, Tooltip. Ein Klick ruft die Aktion; die Taste Esc bindet das
 * Overlay selbst.
 */
public final class EscCircle extends JComponent {

    /** Durchmesser der Fläche. */
    public static final int DIAMETER = 30;

    private final Runnable action;
    private boolean hover;

    public EscCircle(Runnable action, String tooltip) {
        this.action = Objects.requireNonNull(action, "action");
        setToolTipText(tooltip);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                hover = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent e) {
                hover = false;
                repaint();
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                click();
            }
        });
    }

    /** Wie ein Klick. */
    public void click() {
        action.run();
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(DIAMETER, DIAMETER);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight()) - 1;
            double x = (getWidth() - size) / 2.0;
            double y = (getHeight() - size) / 2.0;
            g.setColor(hover ? BusyOverlay.STOP_LIT : BusyOverlay.STOP_MUTED);
            g.fill(new Ellipse2D.Double(x, y, size, size));
            g.setColor(AppColors.of("bubble.userForeground"));
            g.setFont(Comic.semiBold(10.5f));
            FontMetrics metrics = g.getFontMetrics();
            g.drawString(BusyOverlay.STOP, (getWidth() - metrics.stringWidth(BusyOverlay.STOP)) / 2,
                    (getHeight() - metrics.getHeight()) / 2 + metrics.getAscent());
        } finally {
            g.dispose();
        }
    }
}
