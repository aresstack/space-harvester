package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/**
 * DER Reiter einer Sitzung oder eines Dokuments, überall gleich (Angelo, 23.11.2026): Symbol, Beschriftung und rechts
 * im Reiter das Kreuz. Die Beschriftung ist immer ganz zu sehen; der Reiter ist um das Kreuz breiter, nie wird der
 * Text dafür gekürzt oder überdeckt. Das Kreuz und ein Mittelklick auf den Reiter rufen {@code close}: in EINEM
 * Schritt trennen bzw. abmelden und schließen.
 *
 * <p>Das Kreuz ist gezeichnet (nicht das Zeichen „✕“, das nicht jede Schrift hat) und nimmt die Schriftfarbe des
 * Reiters an: weiß auf dem gewählten, dunklen Reiter, dunkel auf dem nicht gewählten.
 */
public final class ClosableTab extends ComposerToggleButton {

    /** Kantenlänge des Kreuzes samt Hover-Kreis. */
    static final int CROSS = 16;
    /** Abstand zwischen Beschriftung und Kreuz. */
    static final int GAP = 4;
    /** Abstand des Kreuzes zum rechten Rand des Reiters. */
    static final int EDGE = 6;

    /** Mindestmaße wie die übrigen Knöpfe der Familie. */
    private static final int MIN_HEIGHT = 28;
    private static final int MIN_WIDTH = 30;

    private final JButton cross;

    /**
     * @param icon         Symbol vor der Beschriftung (Zustandspunkt, Dateisymbol), {@code null} ohne
     * @param text         Beschriftung, immer ganz sichtbar
     * @param tooltip      Tooltip des Reiters
     * @param closeTooltip Tooltip und zugänglicher Name des Kreuzes, etwa „Trennen und schließen“
     * @param close        was Kreuz und Mittelklick tun
     */
    public ClosableTab(Icon icon, String text, String tooltip, String closeTooltip, Runnable close) {
        super(icon, text, tooltip);
        setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        getAccessibleContext().setAccessibleName(text);
        cross = new Cross(closeTooltip);
        cross.addActionListener(e -> close.run());
        setLayout(null);
        add(cross);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    close.run();
                }
            }
        });
    }

    /** Das Kreuz im Reiter (für Tests und Tastatur). */
    public JButton cross() {
        return cross;
    }

    /** Rechts bleibt Platz für das Kreuz: So rechnet die Wunschbreite es ein, und der Text endet davor. */
    @Override
    public Insets getInsets() {
        Insets insets = super.getInsets();
        insets.right += GAP + CROSS + EDGE - Math.min(insets.right, EDGE);
        return insets;
    }

    @Override
    public Insets getInsets(Insets reuse) {
        Insets own = getInsets();
        if (reuse == null) {
            return own;
        }
        reuse.set(own.top, own.left, own.bottom, own.right);
        return reuse;
    }

    /**
     * Die Wunschgröße aus Symbol, Beschriftung und Rändern (samt Platz für das Kreuz). Swing rechnet sie für einen
     * Knopf mit Kindern nicht mehr selbst aus; ohne das hier schrumpfte der Reiter auf die Mindestbreite und die
     * Beschriftung zu „…“.
     */
    @Override
    public Dimension getPreferredSize() {
        if (isPreferredSizeSet()) {
            return super.getPreferredSize();
        }
        java.awt.FontMetrics metrics = getFontMetrics(getFont());
        String text = getText() == null ? "" : getText();
        Icon icon = getIcon();
        int width = metrics.stringWidth(text);
        int height = metrics.getHeight();
        if (icon != null) {
            width += icon.getIconWidth() + (text.isEmpty() ? 0 : getIconTextGap());
            height = Math.max(height, icon.getIconHeight());
        }
        Insets insets = getInsets();
        // Zwei Pixel Luft: Die Textbreite rundet je nach Schriftglättung, der Text darf nie zu „…“ werden.
        return new Dimension(Math.max(MIN_WIDTH, width + insets.left + insets.right + 2),
                Math.max(MIN_HEIGHT, height + insets.top + insets.bottom));
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public void doLayout() {
        cross.setBounds(getWidth() - EDGE - CROSS, (getHeight() - CROSS) / 2, CROSS, CROSS);
    }

    /** Gezeichnetes Kreuz in der Schriftfarbe des Reiters; beim Darüberfahren ein Kreis dahinter. */
    private final class Cross extends JButton {

        Cross(String tooltip) {
            setToolTipText(tooltip);
            getAccessibleContext().setAccessibleName(tooltip);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setRequestFocusEnabled(false);
            setMargin(new Insets(0, 0, 0, 0));
            Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(CROSS, CROSS);
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                Color ink = ClosableTab.this.getForeground();
                if (getModel().isRollover() || getModel().isPressed() || isFocusOwner()) {
                    g2.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(),
                            getModel().isPressed() ? 90 : 55));
                    g2.fillOval(0, 0, getWidth(), getHeight());
                }
                g2.setColor(ink);
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                int inset = 5;
                g2.drawLine(inset, inset, getWidth() - inset, getHeight() - inset);
                g2.drawLine(getWidth() - inset, inset, inset, getHeight() - inset);
            } finally {
                g2.dispose();
            }
        }
    }
}
