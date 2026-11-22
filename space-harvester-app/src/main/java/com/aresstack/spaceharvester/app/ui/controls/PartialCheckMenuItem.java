package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.Icon;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicCheckBoxMenuItemUI;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Menüpunkt mit Häkchen und drittem Zustand „teilweise gewählt“ (Angelo, 22.11.2026: eine Wissensquelle, von der
 * links nur einzelne Spaces gewählt sind). Teilweise zeigt das Kästchen gefüllt statt mit Haken; das Modell bleibt
 * gewählt, damit Tastatur und Bildschirmleser es als an erkennen. Den Zustand setzt der Besitzer mit
 * {@link #partial(JComponent, boolean)} am Menüpunkt oder an dem Häkchen, dessen Modell er teilt. Nur auf dem EDT.
 */
public final class PartialCheckMenuItem extends JCheckBoxMenuItem {

    /** Client-Property: {@code Boolean.TRUE}, wenn teilweise gewählt. */
    public static final String PARTIAL = "PartialCheck.partial";

    public PartialCheckMenuItem(String text) {
        super(text);
    }

    /** Setzt „teilweise“ an einer Komponente; Menüpunkte, die ihr folgen, zeichnen neu. */
    public static void partial(JComponent component, boolean partial) {
        component.putClientProperty(PARTIAL, partial ? Boolean.TRUE : null);
        component.repaint();
    }

    /** Ob eine Komponente als teilweise gewählt markiert ist. */
    public static boolean partial(Component component) {
        return component instanceof JComponent c && Boolean.TRUE.equals(c.getClientProperty(PARTIAL));
    }

    @Override
    public void updateUI() {
        setUI(new BasicCheckBoxMenuItemUI() {
            @Override
            protected void installDefaults() {
                super.installDefaults();
                checkIcon = new PartialIcon(checkIcon);
            }
        });
    }

    /** Das Kästchen des Look and Feel; teilweise mit gefülltem Quadrat darin. */
    static final class PartialIcon implements Icon {

        private final Icon base;

        PartialIcon(Icon base) {
            this.base = base;
        }

        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            if (!partial(c)) {
                if (base != null) {
                    base.paintIcon(c, g, x, y);
                }
                return;
            }
            int w = getIconWidth();
            int h = getIconHeight();
            int size = Math.min(w, h);
            int left = x + (w - size) / 2;
            int top = y + (h - size) / 2;
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.setColor(c.isEnabled() ? Comic.INK : Comic.MUTED);
                g2.drawRect(left, top, size - 1, size - 1);
                int inset = Math.max(2, size / 4);
                g2.fillRect(left + inset, top + inset, size - 2 * inset, size - 2 * inset);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return base == null ? 13 : base.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return base == null ? 13 : base.getIconHeight();
        }
    }
}
