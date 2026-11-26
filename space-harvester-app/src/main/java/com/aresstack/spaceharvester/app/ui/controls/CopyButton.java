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
    /** Kantenlänge in engen Zeilen, wie {@code IconButton.compact()}. */
    static final int COMPACT_SIZE = 22;

    private final Supplier<String> text;
    /** Holt den Text selbst, etwa im Hintergrund, und gibt ihn an {@link #deliver}; {@code null} ohne. */
    private final Consumer<Consumer<String>> fetch;
    private final Consumer<String> clipboard;
    private final String tooltip;
    /** Zeilen auf dem vorderen Blatt: Es ist gesprochener Text (Transkript), nicht der gezeigte Inhalt. */
    private final boolean lines;
    private final Timer reset = new Timer(FEEDBACK_MILLIS, e -> done());
    private Popup badge;
    private boolean copied;
    /** Den Fokus zeigt der Kreis nur, wenn er über die Tastatur kam; nach einem Klick nicht. */
    private final KeyboardFocusRing focusRing = KeyboardFocusRing.install(this);
    /** Kantenlänge; {@link #compact()} verkleinert sie für enge Zeilen. */
    private int size = RoundHover.SIZE;
    /** Auf farbigem Grund ({@link #on}): Hover und Haken in der Schriftfarbe statt heller Kreis und Grün. */
    private boolean tinted;

    /** Kopiert {@code text.get()} in die Zwischenablage des Systems. */
    public CopyButton(Supplier<String> text) {
        this(text, CopyButton::systemClipboard);
    }

    /** @param clipboard wohin der Text geht; die App nimmt die Zwischenablage, Tests eine Liste */
    public CopyButton(Supplier<String> text, Consumer<String> clipboard) {
        this(Objects.requireNonNull(text, "text"), null, clipboard, TOOLTIP, false);
    }

    /**
     * Ein Kopierknopf für Text, der erst geholt werden muss (Dateien lesen, nie auf dem EDT): Der Klick ruft
     * {@code fetch} mit einem Abnehmer; wer den Text hat, gibt ihn dem Abnehmer auf dem EDT, dann steht er in der
     * Zwischenablage und der Haken erscheint. {@code null} oder leerer Text kopiert nichts.
     *
     * @param tooltip was kopiert wird, etwa „Transkript kopieren“
     */
    public static CopyButton deferred(String tooltip, Consumer<Consumer<String>> fetch) {
        return deferred(tooltip, fetch, CopyButton::systemClipboard);
    }

    /** @param clipboard wohin der Text geht; die App nimmt die Zwischenablage, Tests eine Liste */
    public static CopyButton deferred(String tooltip, Consumer<Consumer<String>> fetch, Consumer<String> clipboard) {
        return new CopyButton(null, Objects.requireNonNull(fetch, "fetch"), clipboard,
                Objects.requireNonNull(tooltip, "tooltip"), true);
    }

    private CopyButton(Supplier<String> text, Consumer<Consumer<String>> fetch, Consumer<String> clipboard,
                       String tooltip, boolean lines) {
        super(new Glyph());
        this.text = text;
        this.fetch = fetch;
        this.clipboard = Objects.requireNonNull(clipboard, "clipboard");
        this.tooltip = tooltip;
        this.lines = lines;
        setToolTipText(tooltip);
        getAccessibleContext().setAccessibleName(tooltip);
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(true);
        setOpaque(false);
        setRolloverEnabled(true);
        setForeground(Comic.INK);
        Cursors.set(this, Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        reset.setRepeats(false);
        addActionListener(e -> copy());
    }

    /** Für enge Zeilen wie die Knopfreihe einer Chat-Nachricht: so groß wie ein kompakter Symbolknopf. */
    public CopyButton compact() {
        size = COMPACT_SIZE;
        revalidate();
        return this;
    }

    /**
     * Für farbigen Grund, etwa die Blase einer Chat-Nachricht: Symbol und Haken in {@code ink}, beim Darüberfahren
     * eine durchscheinende Fläche in derselben Farbe statt des hellen Kreises, der ein weißes Symbol verschluckte.
     */
    public CopyButton on(Color ink) {
        setForeground(Objects.requireNonNull(ink, "ink"));
        tinted = true;
        repaint();
        return this;
    }

    private void copy() {
        if (fetch != null) {
            fetch.accept(this::deliver);
            return;
        }
        deliver(text.get());
    }

    private void deliver(String value) {
        if (value == null || fetch != null && value.isBlank()) {
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
        setToolTipText(tooltip);
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
        if (isEnabled() && (getModel().isRollover() || getModel().isPressed() || focusRing.shown())) {
            if (tinted) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    Color ink = getForeground();
                    g.setColor(new Color(ink.getRed(), ink.getGreen(), ink.getBlue(),
                            getModel().isPressed() ? 70 : 36));
                    g.fillOval(0, 0, getWidth() - 1, getHeight() - 1);
                } finally {
                    g.dispose();
                }
            } else {
                RoundHover.paint(graphics, 0, 0, getWidth(), getHeight(), getModel().isPressed());
            }
        }
        super.paintComponent(graphics);
    }

    /** Ob der Fokus zuletzt über die Tastatur kam (für Tests). */
    boolean showsKeyboardFocus() {
        return focusRing.fromKeyboard();
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

    private static void systemClipboard(String value) {
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(value), null);
    }

    // ---- für Tests

    /** Ob gerade die Rückmeldung „Kopiert“ steht. */
    public boolean copiedShown() {
        return copied;
    }

    /** Das Kopieren-Symbol allein, für andere Knöpfe mit derselben Bedeutung (etwa Kopieren im Terminal). */
    public static Icon glyph() {
        return new Glyph();
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
                    g.setColor(button.tinted ? button.getForeground() : Comic.GREEN);
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
                if (c instanceof CopyButton button && button.lines) {
                    g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    g.draw(new java.awt.geom.Line2D.Double(7.8, 8.4, 11.2, 8.4));
                    g.draw(new java.awt.geom.Line2D.Double(7.8, 10.6, 10.2, 10.6));
                }
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
