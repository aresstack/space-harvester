package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.KeyboardFocusRing;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;

/**
 * Ein runder Knopf nur mit Symbol; der Text steht im Tooltip und als zugänglicher Name (Bildschirmleser, Tastatur:
 * Tab und Leertaste). Durchsichtig in Ruhe, beim Darüberfahren der Kreis aus {@link RoundHover}.
 *
 * <p>Den Fokus zeigt der Kreis nur, wenn er über die Tastatur kam (Tab, Umschalt+Tab). Nach einem Mausklick und wenn
 * ein Menü den Fokus beim Schließen zurückgibt, bleibt der Knopf durchsichtig; sonst blieb ⋯ nach dem Menü gelb.
 */
public final class IconButton extends JButton {

    private static final int SIZE = RoundHover.SIZE;
    /** Kantenlänge in engen Zeilen wie der Statuszeile ({@link #compact()}). */
    static final int COMPACT_SIZE = 22;
    private int size = SIZE;
    /** Kleiner Punkt oben rechts: eine Option hinter dem Knopf weicht vom Standard ab. */
    private boolean marked;
    /** Den Fokus zeigt der Kreis nur, wenn er über die Tastatur kam. */
    private final KeyboardFocusRing focusRing;

    public IconButton(Icon icon, String tooltip) {
        super(icon);
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(true);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(Comic.INK);
        focusRing = KeyboardFocusRing.install(this);
    }

    /** Ob der Kreis den Fokus zeigen würde, sobald der Knopf ihn hat (für Tests). */
    boolean showsKeyboardFocus() {
        return focusRing.fromKeyboard();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || focusRing.shown())) {
            RoundHover.paint(graphics, 0, 0, getWidth(), getHeight(), getModel().isPressed());
        }
        super.paintComponent(graphics);
        if (marked) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                int d = Math.max(6, getWidth() / 5);
                g2.setColor(Comic.PETROL);
                g2.fillOval(getWidth() - d - 2, 2, d, d);
            } finally {
                g2.dispose();
            }
        }
    }

    /** Zeigt oder entfernt den Punkt für „eine Option weicht vom Standard ab“. */
    public IconButton marked(boolean value) {
        marked = value;
        repaint();
        return this;
    }

    /** Ob der Punkt gezeigt wird. */
    public boolean marked() {
        return marked;
    }

    /** Kleiner Knopf für enge Zeilen (Statuszeile): Die Zeile behält ihre Höhe. */
    public IconButton compact() {
        size = COMPACT_SIZE;
        setMargin(new java.awt.Insets(0, 0, 0, 0));
        revalidate();
        return this;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(size, size);
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
