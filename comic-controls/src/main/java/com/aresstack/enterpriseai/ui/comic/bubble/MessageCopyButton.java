package com.aresstack.enterpriseai.ui.comic.bubble;

import com.aresstack.enterpriseai.ui.comic.theme.ThemeColor;
import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;

import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.SwingConstants;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der kleine Kopierknopf in der Kopfzeile einer Nachrichtenblase: ein Klick legt die ganze Nachricht (den
 * ursprünglichen Text bzw. Markdown, ohne Kopfzeile, Zeitstempel und Quellenleiste) in die Zwischenablage. Danach
 * zeigt er kurz einen Haken mit „Kopiert“ und kehrt dann zum Klemmbrett zurück. Er nimmt der Blase nie den Fokus,
 * Markieren und Link-Klicks im Text bleiben unberührt. Die Glyphen sind Java2D-Striche, keine Unicode-Zeichen.
 * Gehört zu den Blasen ({@link SpeechBubblePanel#setHeaderAccessory}), damit jede App ihn wiederverwendet.
 */
public final class MessageCopyButton extends JButton {

    public static final String TOOLTIP = "Nachricht kopieren";
    public static final String COPIED = "Kopiert";
    public static final int CONFIRMATION_MILLIS = 1500;

    private final Icon clipboardIcon = new ClipboardIcon();
    private final Icon checkIcon = new CheckIcon();
    private final Color overlay;
    private final Timer reset;

    public MessageCopyButton(Color foreground, final Supplier<String> text, final Consumer<String> clipboard) {
        setIcon(clipboardIcon);
        setForeground(foreground);
        this.overlay = overlay(foreground);
        setName("transcript.copyMessage");
        setToolTipText(TOOLTIP);
        getAccessibleContext().setAccessibleName(TOOLTIP);
        setHorizontalAlignment(SwingConstants.LEFT);
        style(this);
        // Feste Breite für Haken + „Kopiert“: die Bestätigung verschiebt weder Kopfzeile noch Blasenbreite.
        setText(COPIED);
        setIcon(checkIcon);
        Dimension wide = super.getPreferredSize();
        setText("");
        setIcon(clipboardIcon);
        Dimension size = new Dimension(wide.width + 4, Math.max(16, clipboardIcon.getIconHeight() + 4));
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        reset = new Timer(CONFIRMATION_MILLIS, new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                showIdle();
            }
        });
        reset.setRepeats(false);
        addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent event) {
                String value = text.get();
                clipboard.accept(value == null ? "" : value);
                showCopied();
            }
        });
    }

    /** Das gemeinsame Aussehen der kleinen Kopfzeilenknöpfe (auch {@link MessageQrButton}): flach, ohne Fokus-Klau. */
    static void style(JButton button) {
        button.setIconTextGap(3);
        button.setFocusable(true);
        button.setRequestFocusEnabled(false);
        button.setFocusPainted(false);
        button.setBorderPainted(false);
        button.setContentAreaFilled(false);
        button.setRolloverEnabled(true);
        button.setOpaque(false);
        button.setMargin(new Insets(0, 2, 0, 2));
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        Font base = button.getFont();
        if (base != null) {
            button.setFont(base.deriveFont(Font.PLAIN, Math.max(9f, base.getSize2D() - 2f)));
        }
    }

    /** Die Hover-/Druckfläche der Kopfzeilenknöpfe, bündig mit der Seite, an der das Symbol steht. */
    static void paintOverlay(JButton button, Graphics graphics, Color overlay, int width) {
        if (button.getModel().isPressed() || button.getModel().isRollover() || button.isFocusOwner()) {
            Graphics2D g2 = (Graphics2D) graphics.create();
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(button.getModel().isPressed() ? overlay.darker() : overlay);
                int x = button.getHorizontalAlignment() == SwingConstants.RIGHT ? button.getWidth() - width : 0;
                g2.fillRoundRect(x, 0, width, button.getHeight(), 8, 8);
            } finally {
                g2.dispose();
            }
        }
    }

    static Color overlay(Color foreground) {
        return ThemeColor.withAlpha(foreground, 36); // derived live: the hover wash follows a theme switch
    }

    /** Ob gerade die Bestätigung „Kopiert“ steht. */
    public boolean isConfirming() {
        return COPIED.equals(getText());
    }

    public void showIdle() {
        reset.stop();
        setText("");
        setIcon(clipboardIcon);
        setToolTipText(TOOLTIP);
        repaint();
    }

    private void showCopied() {
        setText(COPIED);
        setIcon(checkIcon);
        setToolTipText(COPIED);
        repaint();
        reset.restart();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        paintOverlay(this, graphics, overlay, isConfirming() ? getWidth() : Math.min(getWidth(), getHeight() + 2));
        super.paintComponent(graphics);
    }

    /** Klemmbrett: Brett mit Klammer oben. */
    static final class ClipboardIcon extends StrokeIcon {
        ClipboardIcon() {
            super(12, 13);
        }

        @Override
        protected void paint(Graphics2D g2) {
            g2.drawRoundRect(1, 2, 10, 10, 3, 3);
            g2.drawRoundRect(4, 0, 4, 3, 2, 2);
            g2.drawLine(4, 7, 8, 7);
            g2.drawLine(4, 9, 7, 9);
        }
    }

    /** Haken: kopiert. */
    static final class CheckIcon extends StrokeIcon {
        CheckIcon() {
            super(12, 13);
        }

        @Override
        protected void paint(Graphics2D g2) {
            g2.drawLine(1, 7, 4, 11);
            g2.drawLine(4, 11, 11, 2);
        }
    }
}
