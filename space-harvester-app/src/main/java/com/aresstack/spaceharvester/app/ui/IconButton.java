package com.aresstack.spaceharvester.app.ui;

import javax.swing.Icon;
import javax.swing.JButton;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

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
    /** Ob der Fokus über die Tastatur kam; nur dann zeigt der Kreis ihn. */
    private boolean keyboardFocus;

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
        addFocusListener(new FocusListener() {
            @Override
            public void focusGained(FocusEvent e) {
                keyboardFocus = switch (e.getCause()) {
                    case TRAVERSAL, TRAVERSAL_FORWARD, TRAVERSAL_BACKWARD, TRAVERSAL_UP, TRAVERSAL_DOWN -> true;
                    default -> false;
                };
                repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                keyboardFocus = false;
                repaint();
            }
        });
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                keyboardFocus = false;
            }
        });
    }

    /** Ob der Kreis den Fokus zeigen würde, sobald der Knopf ihn hat (für Tests). */
    boolean showsKeyboardFocus() {
        return keyboardFocus;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || (keyboardFocus && hasFocus()))) {
            RoundHover.paint(graphics, 0, 0, getWidth(), getHeight(), getModel().isPressed());
        }
        super.paintComponent(graphics);
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
