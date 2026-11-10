package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.RoundHover;

import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.Popup;
import javax.swing.PopupFactory;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runder Symbolknopf „In die Zwischenablage kopieren“ (zwei überlappende Rechtecke), wie die übrigen Symbolaktionen
 * der App: durchsichtig in Ruhe, Kreis beim Darüberfahren. Den Text liefert ein {@link Supplier} erst beim Klick, so
 * gibt es je Ansicht einen Knopf oben rechts, der immer den gerade gezeigten Inhalt kopiert. Nach dem Klick steht für
 * {@value #FEEDBACK_MILLIS} ms ein Abzeichen „Kopiert“ neben dem Knopf und das Symbol wird zum Haken; kein Dialog.
 */
public final class CopyButton extends JButton {

    public static final String TOOLTIP = "In die Zwischenablage kopieren";
    public static final String COPIED = "Kopiert";
    /** So lange bleibt die Rückmeldung stehen. */
    public static final int FEEDBACK_MILLIS = 1500;

    private final Supplier<String> text;
    private final Consumer<String> clipboard;
    private final Timer reset = new Timer(FEEDBACK_MILLIS, e -> done());
    private Popup badge;
    private boolean copied;

    /** Kopiert {@code text.get()} in die Zwischenablage des Systems. */
    public CopyButton(Supplier<String> text) {
        this(text, CopyButton::systemClipboard);
    }

    /** @param clipboard wohin der Text geht; die App nimmt die Zwischenablage, Tests eine Liste */
    public CopyButton(Supplier<String> text, Consumer<String> clipboard) {
        super(new Glyph());
        this.text = Objects.requireNonNull(text, "text");
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        setToolTipText(TOOLTIP);
        getAccessibleContext().setAccessibleName(TOOLTIP);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(true);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(Comic.INK);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reset.setRepeats(false);
        addActionListener(e -> copy());
    }

    private void copy() {
        String value = text.get();
        if (value == null) {
            return;
        }
        clipboard.accept(value);
        copied = true;
        setToolTipText(COPIED);
        showBadge();
        repaint();
        reset.restart();
    }

    private void done() {
        copied = false;
        setToolTipText(TOOLTIP);
        hideBadge();
        repaint();
    }

    /** Das Abzeichen „Kopiert“ links neben dem Knopf, so lange wie der Haken. */
    private void showBadge() {
        hideBadge();
        if (!isShowing()) {
            return;
        }
        JLabel label = new JLabel(COPIED);
        label.setFont(Comic.semiBold(12f));
        label.setForeground(Comic.GREEN);
        label.setOpaque(true);
        label.setBackground(Comic.SURFACE);
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.GREEN, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        Dimension size = label.getPreferredSize();
        Point origin = getLocationOnScreen();
        int x = origin.x - size.width - 4;
        int y = origin.y + (getHeight() - size.height) / 2;
        try {
            badge = PopupFactory.getSharedInstance().getPopup(this, label, x, y);
            badge.show();
        } catch (RuntimeException e) {
            badge = null;
        }
    }

    private void hideBadge() {
        if (badge != null) {
            badge.hide();
            badge = null;
        }
    }

    @Override
    public void removeNotify() {
        reset.stop();
        hideBadge();
        super.removeNotify();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || hasFocus())) {
            RoundHover.paint(graphics, 0, 0, getWidth(), getHeight(), getModel().isPressed());
        }
        super.paintComponent(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(RoundHover.SIZE, RoundHover.SIZE);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private static void systemClipboard(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    // ---- für Tests

    /** Ob gerade die Rückmeldung „Kopiert“ steht. */
    public boolean copiedShown() {
        return copied;
    }

    /** Zwei überlappende Rechtecke; nach dem Kopieren ein grüner Haken. */
    private static final class Glyph implements Icon {

        private static final int SIZE = 16;

        @Override
        public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                g.translate(x, y);
                g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                if (c instanceof CopyButton button && button.copied) {
                    g.setColor(Comic.GREEN);
                    Path2D tick = new Path2D.Double();
                    tick.moveTo(3, 8.5);
                    tick.lineTo(6.5, 12);
                    tick.lineTo(13, 4.5);
                    g.draw(tick);
                    return;
                }
                Color color = c == null ? Comic.INK : c.isEnabled() ? c.getForeground() : Comic.LINE;
                g.setColor(color);
                // Hinteres Blatt: nur die freien Kanten, das vordere verdeckt den Rest.
                Path2D back = new Path2D.Double();
                back.moveTo(5.5, 10.5);
                back.lineTo(2.5, 10.5);
                back.lineTo(2.5, 2.5);
                back.lineTo(10.5, 2.5);
                back.lineTo(10.5, 5.5);
                g.draw(back);
                g.draw(new RoundRectangle2D.Double(5.5, 5.5, 8, 8, 2, 2));
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return SIZE;
        }

        @Override
        public int getIconHeight() {
            return SIZE;
        }
    }
}
