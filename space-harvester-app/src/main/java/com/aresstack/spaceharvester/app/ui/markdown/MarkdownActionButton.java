package com.aresstack.spaceharvester.app.ui.markdown;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/**
 * Ein kleiner, rahmenloser Symbolknopf im Stil der Composer-Aktionen: kein Text, nur eine Strichglyphe in der
 * umgebenden Vordergrundfarbe, eine weiche abgerundete Hover-/Druck-Hervorhebung, Handcursor und Tooltip
 * (aus askai-java8 {@code MarkdownActionButton}). Für die Kopieraktionen an Codeblöcken und Mermaid-Diagrammen.
 * Wie die Composer-Knöpfe per Tab erreichbar (Leertaste löst aus, die Hervorhebung zeigt den Fokus), ohne dass ein
 * Mausklick dem Editor den Fokus nimmt.
 */
final class MarkdownActionButton extends JButton {

    private static final int SIZE = 26;

    private final Color overlay;

    MarkdownActionButton(Icon icon, String tooltip, Color foreground, final Runnable action) {
        super(icon);
        Color fg = foreground == null ? ResearchUiPalette.LIGHT_CONTROL_TEXT : foreground;
        setForeground(fg);
        this.overlay = new Color(fg.getRed(), fg.getGreen(), fg.getBlue(), 32);
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setFocusable(true);
        setRequestFocusEnabled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setRolloverEnabled(true);
        setOpaque(false);
        setMargin(new Insets(0, 0, 0, 0));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(SIZE, SIZE));
        addActionListener(event -> action.run());
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = (Graphics2D) graphics.create();
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (getModel().isPressed()) {
                g2.setColor(overlay.darker());
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            } else if (getModel().isRollover() || isFocusOwner()) {
                g2.setColor(overlay);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            }
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }

    /** Eine „Kopieren“-Glyphe: zwei überlappende abgerundete Rechtecke in der aktuellen Vordergrundfarbe. */
    static final class CopyIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.getForeground());
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawRoundRect(5, 2, 8, 10, 3, 3);
                g2.drawRoundRect(2, 5, 8, 10, 3, 3);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 17;
        }
    }

    /** Eine „Bild“-Glyphe: ein gerahmtes Rechteck mit Sonne und Berg in der Vordergrundfarbe. */
    static final class ImageIcon implements Icon {
        @Override
        public void paintIcon(Component c, Graphics g, int x, int y) {
            Graphics2D g2 = (Graphics2D) g.create();
            try {
                g2.translate(x, y);
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c.getForeground());
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawRoundRect(1, 3, 14, 11, 3, 3);
                g2.fillOval(4, 5, 3, 3);
                int[] px = {2, 7, 14};
                int[] py = {13, 8, 13};
                g2.fillPolygon(px, py, 3);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }
}
