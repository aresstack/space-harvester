package com.aresstack.enterpriseai.ui.comic.control;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.accessibility.AccessibleContext;
import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * The read-aloud Play/Pause orb, ported from askai-java8 {@code arch} ({@code ResearchOutOfScopeSky.SpeakOrb}):
 * a round comic chip in the cloud colours, ▶ while silent, ❚❚ while read-aloud is active — painted, no emoji.
 * The orb only shows the state; the owner flips it on click ({@link #setActive}) and sets the tooltip.
 *
 * <p>Keyboard: focusable with a thin accent ring, Space activates it; a mouse click never takes the focus away
 * from the composer (like the composer buttons).
 */
public final class ReadAloudOrb extends JButton {

    /** Diameter of the orb (arch: {@code orbSize = 36}). */
    public static final int SIZE = 36;

    private boolean active;

    public ReadAloudOrb() {
        setName("chat.readAloudOrb");
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setRolloverEnabled(true);
        setFocusable(true);
        setRequestFocusEnabled(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        updateAccessibleName();
    }

    /** Whether read-aloud is active (shows ❚❚) or silent (shows ▶). */
    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (this.active != active) {
            this.active = active;
            updateAccessibleName();
            repaint();
        }
    }

    private void updateAccessibleName() {
        AccessibleContext context = getAccessibleContext();
        context.setAccessibleName(active ? "Vorlesen pausieren" : "Vorlesen");
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            boolean enabled = isEnabled();
            boolean hovered = enabled && (getModel().isRollover() || getModel().isPressed());
            int width = getWidth();
            int height = getHeight();
            g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_SURFACE : ResearchUiPalette.CLOUD_SURFACE);
            g2.fillOval(1, 1, width - 2, height - 2);
            g2.setColor(hovered ? ResearchUiPalette.CLOUD_HOVER_BORDER : ResearchUiPalette.CLOUD_BORDER);
            g2.setStroke(new BasicStroke(1.3f));
            g2.drawOval(1, 1, width - 3, height - 3);
            if (isFocusOwner()) {
                g2.setColor(ResearchUiPalette.ACCENT_BLUE);
                g2.setStroke(new BasicStroke(1.5f));
                g2.drawOval(3, 3, width - 7, height - 7);
            }
            g2.setColor(enabled ? ResearchUiPalette.CLOUD_TEXT : ResearchUiPalette.CLOUD_BORDER);
            int cx = width / 2;
            int cy = height / 2;
            if (active) {
                g2.fillRect(cx - 6, cy - 7, 4, 14);
                g2.fillRect(cx + 2, cy - 7, 4, 14);
            } else {
                g2.fillPolygon(new int[]{cx - 4, cx - 4, cx + 7}, new int[]{cy - 7, cy + 7, cy}, 3);
            }
        } finally {
            g2.dispose();
        }
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(SIZE, SIZE);
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
