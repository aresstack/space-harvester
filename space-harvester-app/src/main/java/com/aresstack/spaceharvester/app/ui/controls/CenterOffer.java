package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.OverlayLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.util.Objects;

/**
 * Ein Angebot mitten in einer leeren Fläche (Angelo 28.11.2026): Fehlt einer Ansicht die Voraussetzung, etwa die
 * Analyse, steht in ihrer Mitte ein zurückhaltender grauer Knopf, der sie anstößt. So sieht man ohne Suchen, was zu
 * tun ist. Die Ansicht selbst bleibt unverändert darunter und bedienbar; nur der Knopf fängt Mausklicks.
 */
public final class CenterOffer extends JPanel {

    /** Rundung des Knopfs. */
    static final int ARC = 18;
    static final int PAD_X = 18;
    static final int PAD_Y = 9;
    static final float FONT_SIZE = 14f;
    /** Fläche des Knopfs in Ruhe und unter der Maus: hell und grau, damit er im Hintergrund bleibt. */
    static final Color REST = new Color(0xECEDEF);
    static final Color HOVER = new Color(0xDDDFE3);

    private final JButton button = new Pill();
    private final JPanel layer = new JPanel(new GridBagLayout()) {
        @Override
        public boolean contains(int x, int y) {
            // Nur der Knopf gehört der Maus; alles daneben reicht die Ansicht darunter durch.
            return button.isVisible() && button.getBounds().contains(x, y);
        }
    };

    /**
     * @param content die Ansicht, in deren Mitte das Angebot steht
     * @param label   Beschriftung des Knopfs, etwa „Jetzt analysieren“
     * @param tooltip was der Knopf tut
     */
    public CenterOffer(JComponent content, String label, String tooltip) {
        Objects.requireNonNull(content, "content");
        setLayout(new OverlayLayout(this));
        setOpaque(false);
        button.setText(Objects.requireNonNull(label, "label"));
        button.setToolTipText(tooltip);
        button.getAccessibleContext().setAccessibleDescription(tooltip);
        layer.setOpaque(false);
        layer.add(button);
        layer.setVisible(false);
        add(layer);
        add(content);
    }

    /** Was der Knopf tut. */
    public CenterOffer onAccept(Runnable action) {
        Objects.requireNonNull(action, "action");
        button.addActionListener(e -> action.run());
        return this;
    }

    /** Zeigt oder versteckt das Angebot. */
    public void offer(boolean shown) {
        if (layer.isVisible() != shown) {
            layer.setVisible(shown);
            repaint();
        }
    }

    public boolean offered() {
        return layer.isVisible();
    }

    public JButton button() {
        return button;
    }

    @Override
    public boolean isOptimizedDrawingEnabled() {
        // Die Lagen überdecken sich.
        return false;
    }

    /** Der graue, runde Knopf: Fläche hell, Schrift gedämpft, unter der Maus etwas dunkler. */
    private static final class Pill extends JButton {

        Pill() {
            setFont(Comic.regular(FONT_SIZE));
            setForeground(Comic.MUTED);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getModel().addChangeListener(e -> setForeground(getModel().isRollover() ? Comic.INK : Comic.MUTED));
            setBorder(javax.swing.BorderFactory.createEmptyBorder(PAD_Y, PAD_X, PAD_Y, PAD_X));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            boolean hover = getModel().isRollover() || isFocusOwner();
            g2.setColor(hover ? HOVER : REST);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.setColor(Comic.LINE);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, ARC, ARC);
            g2.dispose();
            super.paintComponent(g);
        }
    }
}
