package com.aresstack.enterpriseai.ui.comic.bubble;

import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;

/**
 * Der QR-Knopf in der Kopfzeile einer Nachrichtenblase, im Aussehen des {@link MessageCopyButton}: ein Klick ruft die
 * übergebene Aktion auf (z. B. die Nachricht als QR-Code zeigen, um sie mit dem Telefon zu lesen). Das Symbol sind
 * drei Suchmuster-Quadrate plus Datenpunkte, auch klein als QR-Code erkennbar.
 */
public final class MessageQrButton extends JButton {

    public static final String TOOLTIP = "Nachricht als QR-Code zeigen";

    private final Color overlay;

    public MessageQrButton(Color foreground, final Runnable action) {
        setIcon(new QrIcon());
        setForeground(foreground);
        this.overlay = MessageCopyButton.overlay(foreground);
        setName("transcript.qrMessage");
        setToolTipText(TOOLTIP);
        getAccessibleContext().setAccessibleName(TOOLTIP);
        MessageCopyButton.style(this);
        Dimension size = new Dimension(getIcon().getIconWidth() + 6, Math.max(16, getIcon().getIconHeight() + 4));
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                action.run();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        MessageCopyButton.paintOverlay(this, graphics, overlay, getWidth());
        super.paintComponent(graphics);
    }

    /** QR-Code: drei Suchmuster in den Ecken, rechts unten Datenpunkte. */
    static final class QrIcon extends StrokeIcon {
        QrIcon() {
            super(13, 13);
        }

        @Override
        protected void paint(Graphics2D g2) {
            finder(g2, 0, 0);
            finder(g2, 8, 0);
            finder(g2, 0, 8);
            g2.fillRect(8, 8, 2, 2);
            g2.fillRect(11, 8, 2, 2);
            g2.fillRect(8, 11, 2, 2);
            g2.fillRect(11, 11, 2, 2);
        }

        private static void finder(Graphics2D g2, int x, int y) {
            g2.drawRect(x, y, 4, 4);
        }
    }
}
