package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.RoundHover;

import javax.swing.AbstractAction;
import javax.swing.BoxLayout;
import javax.swing.Box;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.Ellipse2D;
import java.util.Objects;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Prüfen von Hand mit zwei gezeichneten Icon-Knöpfen nebeneinander, ✓ vor ✕ (UX W3): offen, bestätigt oder
 * verworfen. Ein Klick auf einen Knopf setzt seinen Wert, ein Klick auf den gesetzten Knopf nimmt ihn zurück (offen).
 * Der Knopf schaltet sofort um und meldet das dem {@link Listener}; scheitert das Speichern, setzt der Besitzer den
 * alten Zustand mit {@link #state(State)} zurück. Keine Fachlogik: nur Zustand, gesperrt mit Grund und Rückmeldung,
 * damit auch andere Listen (etwa die Fachbegriffe) ihn nutzen können; die Wörter der Tooltips kommen aus
 * {@link Texts}.
 *
 * <p>Ungesetzt sind beide Knöpfe durchsichtig mit dünnem Rand und einem Zeichen in Tuschefarbe; gesetzt ist ✓ grün gefüllt und
 * ✕ dunkel gefüllt (Tusche, kein Rot), das Zeichen weiß. Gesperrt sind Rand und Zeichen grau, der Tooltip nennt den
 * Grund. Tab erreicht beide Knöpfe, Leertaste und Enter schalten; kam der Fokus über die Tastatur, steht ein gelber
 * Ring um den Knopf. Größe wie die Icon-Knöpfe der Reiterzeile ({@link RoundHover#SIZE}). Nur auf dem EDT.
 */
public final class ReviewToggle extends JPanel {

    /** Zustand der Prüfung. */
    public enum State { OPEN, CONFIRMED, REJECTED }

    /** Meldet einen Klick, nachdem der Knopf umgeschaltet hat (nie bei {@link #state(State)}). */
    @FunctionalInterface
    public interface Listener {
        void changed(State from, State to);
    }

    /**
     * Tooltips und zugängliche Namen der Knöpfe.
     *
     * @param confirm         ✓ ungesetzt, etwa „Aussage bestätigen“
     * @param reject          ✕ ungesetzt, etwa „Aussage verwerfen“
     * @param withdrawConfirm ✓ gesetzt, etwa „Bestätigung zurücknehmen“
     * @param withdrawReject  ✕ gesetzt, etwa „Verwerfen zurücknehmen“
     */
    public record Texts(String confirm, String reject, String withdrawConfirm, String withdrawReject) {

        /** Für Aussagen (Overlay „Belege“, UX W3). */
        public static final Texts STATEMENT = new Texts("Aussage bestätigen", "Aussage verwerfen",
                "Bestätigung zurücknehmen", "Verwerfen zurücknehmen");

        public Texts {
            Objects.requireNonNull(confirm, "confirm");
            Objects.requireNonNull(reject, "reject");
            Objects.requireNonNull(withdrawConfirm, "withdrawConfirm");
            Objects.requireNonNull(withdrawReject, "withdrawReject");
        }
    }

    /** Kantenlänge eines Knopfs: wie die Icon-Knöpfe der Reiterzeile. */
    public static final int SIZE = RoundHover.SIZE;
    /** Abstand zwischen ✓ und ✕. */
    static final int GAP = 4;
    /** Grün eines gesetzten ✓: dunkel genug für das weiße Zeichen. */
    static final Color CONFIRMED_FILL = Comic.STANDING_CURRENT;
    /** Dunkel eines gesetzten ✕: die Tuschefarbe, bewusst kein Rot. */
    static final Color REJECTED_FILL = Comic.INK;
    /** Gesperrt und gesetzt: gedämpft gefüllt. */
    static final Color LOCKED_FILL = Comic.MUTED;
    /** Malt die Zeichen in einer gewählten Farbe ({@link Glyph} nimmt die Vordergrundfarbe der Komponente). */
    private static final JLabel GLYPH_HOST = new JLabel();

    private final Texts texts;
    private final Knob confirm;
    private final Knob reject;
    private State state = State.OPEN;
    private String reason;
    private Listener listener = (from, to) -> { };

    /** Mit den Wörtern für Aussagen ({@link Texts#STATEMENT}). */
    public ReviewToggle() {
        this(Texts.STATEMENT);
    }

    public ReviewToggle(Texts texts) {
        this.texts = Objects.requireNonNull(texts, "texts");
        setOpaque(false);
        setLayout(new BoxLayout(this, BoxLayout.X_AXIS));
        confirm = new Knob(Glyph.ACCEPT, State.CONFIRMED);
        reject = new Knob(Glyph.CANCEL, State.REJECTED);
        add(confirm);
        add(Box.createHorizontalStrut(GAP));
        add(reject);
        describe();
    }

    /** {@code listener} erfährt jeden Klick, der den Zustand ändert. */
    public ReviewToggle onChange(Listener listener) {
        this.listener = Objects.requireNonNull(listener, "listener");
        return this;
    }

    public State state() {
        return state;
    }

    /** Setzt den Zustand ohne Meldung (etwa nach dem Lesen oder zurück nach einem Fehler). */
    public void state(State value) {
        state = Objects.requireNonNull(value, "value");
        describe();
        repaint();
    }

    /** Sperrt beide Knöpfe; {@code why} steht als Tooltip daran. {@code null} gibt sie frei. */
    public void locked(String why) {
        reason = why;
        confirm.setEnabled(why == null);
        reject.setEnabled(why == null);
        describe();
        repaint();
    }

    /** Der Grund der Sperre, {@code null} wenn frei. */
    public String lockReason() {
        return reason;
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        locked(enabled ? null : reason == null ? "" : reason);
    }

    /** Wie ein Klick auf ✓ (auch für Tests). */
    public void clickConfirm() {
        confirm.doClick(0);
    }

    /** Wie ein Klick auf ✕ (auch für Tests). */
    public void clickReject() {
        reject.doClick(0);
    }

    /** Der Knopf ✓, etwa für Tastatur- und Bildschirmlesertests. */
    /** Ob einer der Knöpfe den Fokus über die Tastatur bekam (dann trägt er den gelben Ring). */
    public boolean keyboardFocused() {
        return confirm.focusRing.fromKeyboard() || reject.focusRing.fromKeyboard();
    }

    public JButton confirmButton() {
        return confirm;
    }

    /** Der Knopf ✕. */
    public JButton rejectButton() {
        return reject;
    }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(2 * SIZE + GAP, SIZE);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    @Override
    public Dimension getMaximumSize() {
        return getPreferredSize();
    }

    private void clicked(State value) {
        if (reason != null) {
            return;
        }
        State from = state;
        State to = from == value ? State.OPEN : value;
        state(to);
        listener.changed(from, to);
    }

    private void describe() {
        confirm.describe(state == State.CONFIRMED ? texts.withdrawConfirm() : texts.confirm());
        reject.describe(state == State.REJECTED ? texts.withdrawReject() : texts.reject());
    }

    /** Ein runder Knopf mit gezeichnetem Zeichen; gesetzt gefüllt. */
    private final class Knob extends JButton {

        private final Glyph glyph;
        private final State value;
        private final KeyboardFocusRing focusRing;

        Knob(Glyph glyph, State value) {
            this.glyph = glyph;
            this.value = value;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setFocusable(true);
            setForeground(Comic.INK);
            focusRing = KeyboardFocusRing.install(this);
            addActionListener(e -> clicked(value));
            // Leertaste schaltet wie bei jedem Knopf; Enter ebenso (UX W3).
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "review-toggle");
            getActionMap().put("review-toggle", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    doClick(0);
                }
            });
        }

        void describe(String action) {
            setToolTipText(reason != null && !reason.isEmpty() ? reason : action);
            getAccessibleContext().setAccessibleName(action);
            getAccessibleContext().setAccessibleDescription(reason == null || reason.isEmpty() ? null : reason);
        }

        boolean set() {
            return state == value;
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

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
                boolean enabled = isEnabled();
                double size = Math.min(getWidth(), getHeight()) - 3;
                double x = (getWidth() - size) / 2.0;
                double y = (getHeight() - size) / 2.0;
                Ellipse2D circle = new Ellipse2D.Double(x, y, size, size);
                Color mark;
                if (set()) {
                    g.setColor(!enabled ? LOCKED_FILL : value == State.CONFIRMED ? CONFIRMED_FILL : REJECTED_FILL);
                    g.fill(circle);
                    mark = AppColors.of("app.ReviewToggle.paintComponent");
                } else {
                    // Ungesetzt durchsichtig, nur der dünne Rand; unter der Maus hell gelb wie die Pillen.
                    if (enabled && (getModel().isRollover() || getModel().isPressed())) {
                        g.setColor(Comic.NOTICE);
                        g.fill(circle);
                    }
                    g.setColor(enabled ? Comic.INK : Comic.LINE);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(circle);
                    mark = enabled ? Comic.INK : Comic.LINE;
                }
                if (enabled && focusRing.shown()) {
                    g.setColor(Comic.YELLOW);
                    g.setStroke(new BasicStroke(2f));
                    g.draw(new Ellipse2D.Double(x - 0.5, y - 0.5, size + 1, size + 1));
                }
                GLYPH_HOST.setForeground(mark);
                glyph.paintIcon(GLYPH_HOST, g, (getWidth() - glyph.getIconWidth()) / 2,
                        (getHeight() - glyph.getIconHeight()) / 2);
            } finally {
                g.dispose();
            }
        }
    }
}
