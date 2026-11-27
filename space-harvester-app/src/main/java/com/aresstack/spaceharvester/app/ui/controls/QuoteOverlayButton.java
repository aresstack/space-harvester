package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPainter;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;

/**
 * Das kleine schwebende „Zitieren“ über einer Markierung in einer Chat-Antwort (wie in gängigen Chat-Anwendungen):
 * eine dunkle Pille mit Anführungszeichen-Glyphe. Sie nimmt dem Text nie den Fokus, damit die Markierung bis zum Klick
 * sichtbar bleibt. Aus dem Enterprise AI Client ({@code exedra.ui.chat.QuoteOverlayButton}) übernommen.
 */
public final class QuoteOverlayButton extends JButton {

    public static final String LABEL = "Zitieren";
    public static final String TOOLTIP = "Markierten Text als Zitat an die nächste Nachricht anhängen";
    private static final int RADIUS = 12;

    public QuoteOverlayButton() {
        super(LABEL, new QuoteIcon());
        setName("transcript.quoteOverlay");
        setToolTipText(TOOLTIP);
        getAccessibleContext().setAccessibleName(LABEL);
        setForeground(ResearchUiPalette.TEXT_PRIMARY);
        setIconTextGap(5);
        setFocusable(false);
        setRequestFocusEnabled(false);
        setFocusPainted(false);
        setBorderPainted(false);
        setContentAreaFilled(false);
        setRolloverEnabled(true);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 12));
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Font base = getFont();
        if (base != null) {
            setFont(base.deriveFont(Font.BOLD, Math.max(11f, base.getSize2D() - 1f)));
        }
        setSize(getPreferredSize());
    }

    /** Abstand zur Markierung und zum Rand der Fläche. */
    private static final int GAP = 6;

    /**
     * Zeigt den Knopf mittig über der Markierung {@code area} (in Koordinaten von {@code anchor}) auf der Fläche
     * {@code host}, in der er liegt; passt es oben nicht hin, darunter. Er bleibt immer ganz in der Fläche, auch wenn
     * die Markierung über die ganze Höhe reicht.
     */
    public void place(java.awt.Component anchor, java.awt.Rectangle area, java.awt.Container host) {
        java.awt.Dimension size = getPreferredSize();
        java.awt.Rectangle at = javax.swing.SwingUtilities.convertRectangle(anchor, area, host);
        int x = at.x + at.width / 2 - size.width / 2;
        x = Math.max(GAP, Math.min(x, host.getWidth() - size.width - GAP));
        int y = at.y - size.height - GAP;
        if (y < GAP) {
            y = at.y + at.height + GAP;
        }
        y = Math.max(GAP, Math.min(y, host.getHeight() - size.height - GAP));
        setBounds(x, y, size.width, size.height);
        setVisible(true);
        host.repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g2 = ResearchUiPainter.prepare(graphics);
        try {
            boolean pressed = getModel().isArmed() && getModel().isPressed();
            Color fill = pressed ? ResearchUiPalette.ACCENT_BLUE
                    : getModel().isRollover() ? ResearchUiPalette.SECONDARY_HOVER
                    : ResearchUiPalette.SECONDARY_SURFACE;
            ResearchUiPainter.fillRound(g2, 0, 0, getWidth(), getHeight(), RADIUS, fill);
            ResearchUiPainter.strokeRound(g2, 0, 0, getWidth(), getHeight(), RADIUS, ResearchUiPalette.BORDER_DARK);
        } finally {
            g2.dispose();
        }
        super.paintComponent(graphics);
    }
}
