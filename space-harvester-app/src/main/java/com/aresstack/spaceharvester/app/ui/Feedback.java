package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicScrollPane;
import com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.ScrollPaneConstants;
import javax.swing.Scrollable;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseWheelEvent;

/**
 * Rückmeldung einer Ansicht in einer schmalen Platte: läuft, erfolgreich, Warnung oder Problem. Probleme zeigen,
 * was zu tun ist; die technische Zeile klappt nur auf Wunsch auf. Unsichtbar, solange es nichts zu melden gibt.
 *
 * <p>Die Platte ist genau so hoch wie ihr Text und nie höher als {@link #MAX_LINES} Zeilen Text (aufgeklappt
 * {@link #MAX_LINES_OPEN}); längerer Text wird in der Platte gescrollt. Die Höhe rechnet sie selbst aus der Breite,
 * die sie hat oder gleich bekommt, nie aus dem Zustand ihrer Textfelder: So verdrängt sie das Formular darüber auch
 * beim ersten Einblenden nicht.
 */
public final class Feedback extends JPanel {

    /** Höchstens so viele Zeilen Meldungstext; was darüber hinausgeht, wird in der Platte gescrollt. */
    static final int MAX_LINES = 4;
    /** Wie {@link #MAX_LINES}, wenn die technischen Details aufgeklappt sind. */
    static final int MAX_LINES_OPEN = 9;
    private static final int GAP = 4;
    private static final int NARROW = 40;

    private final ComicSectionPanel plate = new ComicSectionPanel(Comic.PALETTE);
    private final JLabel title = new JLabel();
    private final WrappedText message = Comic.wrapped("", Comic.TEXT, 12.5f);
    private final WrappedText technical = Comic.wrapped("", Comic.TEXT, 11f);
    private final Texts texts = new Texts();
    private final ComicScrollPane scroll = new ComicScrollPane(texts,
            ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER, ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER,
            Comic.PALETTE) {
        @Override
        protected void processMouseWheelEvent(MouseWheelEvent e) {
            // Ohne eigenen Scrollbalken gehört das Mausrad der Seite darum (Einstellungen), nicht dieser Platte.
            if (getVerticalScrollBarPolicy() == VERTICAL_SCROLLBAR_NEVER && getParent() != null) {
                getParent().dispatchEvent(SwingUtilities.convertMouseEvent(this, e, getParent()));
                return;
            }
            super.processMouseWheelEvent(e);
        }
    };
    private final ComposerButton details = Comic.button("Technische Details", "Klasse und Meldung der Ausnahme");
    /** Zeigt die Meldungen an dieser Stelle statt hier (ein Statusbereich für mehrere Ansichten, Assistent). */
    private Feedback target;
    /** Nimmt Lauf-, Erfolgs- und Infomeldungen entgegen (Statuszeile); die Platte zeigt dann nur noch Probleme. */
    private java.util.function.BiConsumer<String, Color> statusLine;
    /** Ob die zuletzt gezeigte Meldung in der Statuszeile steht (und {@link #clear()} sie dort beenden muss). */
    private boolean inStatusLine;
    /** Die Höhe, die der Behälter zuletzt als Wunschhöhe bekommen hat. */
    private int reported = -1;
    private int checkedWidth = -1;

    public Feedback() {
        super(null);
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        plate.setLayout(new Stack());
        plate.setBorder(BorderFactory.createEmptyBorder(8, 16, 8, 12));
        title.setFont(Comic.semiBold(13f));
        title.setForeground(Comic.INK);
        technical.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 11));
        technical.setVisible(false);
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        details.addActionListener(e -> {
            technical.setVisible(!technical.isVisible());
            relayout();
        });
        plate.add(title);
        plate.add(scroll);
        plate.add(details);
        add(plate);
        setVisible(false);
    }

    /** Meldung und technische Zeile untereinander; so breit wie der sichtbare Ausschnitt, damit der Text umbricht. */
    private final class Texts extends JPanel implements Scrollable {

        Texts() {
            super(new BorderLayout(0, GAP));
            setOpaque(false);
            add(message, BorderLayout.NORTH);
            add(technical, BorderLayout.CENTER);
        }

        @Override
        public Dimension getPreferredSize() {
            Container viewport = getParent();
            int width = viewport != null && viewport.getWidth() > 0 ? viewport.getWidth()
                    : getWidth() > 0 ? getWidth() : WrappedText.FALLBACK_WIDTH;
            return new Dimension(NARROW, textHeight(width));
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
            return lineHeight();
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
            return Math.max(lineHeight(), visible.height - lineHeight());
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    /**
     * Überschrift, Text und Knopf untereinander. Wunschhöhe und Aufteilung kommen aus derselben Rechnung
     * ({@link #plateHeight}), immer für eine bekannte Breite.
     */
    private final class Stack implements LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(preferredWidth(), plateHeight(parent.getWidth() > 0 ? parent.getWidth()
                    : WrappedText.FALLBACK_WIDTH));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void layoutContainer(Container parent) {
            Insets insets = parent.getInsets();
            int inner = parent.getWidth() - insets.left - insets.right;
            if (inner <= 0) {
                // Noch unsichtbar und ohne Breite ausgelegt: nichts zu verteilen.
                return;
            }
            int y = insets.top;
            int heading = title.getPreferredSize().height;
            title.setBounds(insets.left, y, inner, heading);
            y += heading;
            if (scroll.isVisible()) {
                int needed = textHeight(inner);
                int height = Math.min(needed, maximumTextHeight());
                int policy = needed > height ? ScrollPaneConstants.VERTICAL_SCROLLBAR_ALWAYS
                        : ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER;
                if (scroll.getVerticalScrollBarPolicy() != policy) {
                    scroll.setVerticalScrollBarPolicy(policy);
                }
                y += GAP;
                scroll.setBounds(insets.left, y, inner, height);
                y += height;
            }
            if (details.isVisible()) {
                Dimension button = details.getPreferredSize();
                y += GAP;
                details.setBounds(insets.left, y, Math.min(button.width, inner), button.height);
            }
        }
    }

    /** Die Höhe der Platte, wenn sie {@code width} Pixel breit ist. */
    private int plateHeight(int width) {
        Insets insets = plate.getInsets();
        int inner = Math.max(1, width - insets.left - insets.right);
        int height = insets.top + title.getPreferredSize().height + insets.bottom;
        if (scroll.isVisible()) {
            height += GAP + Math.min(textHeight(inner), maximumTextHeight());
        }
        if (details.isVisible()) {
            height += GAP + details.getPreferredSize().height;
        }
        return height;
    }

    /** Die Höhe von Meldung und (aufgeklappter) technischer Zeile bei {@code width} Pixel Breite, ungekürzt. */
    private int textHeight(int width) {
        int height = message.isVisible() ? message.heightFor(width) : 0;
        if (technical.isVisible()) {
            height += (message.isVisible() ? GAP : 0) + technical.heightFor(width);
        }
        return height;
    }

    private int maximumTextHeight() {
        return (technical.isVisible() ? MAX_LINES_OPEN : MAX_LINES) * lineHeight();
    }

    private int lineHeight() {
        return message.getFontMetrics(message.getFont()).getHeight();
    }

    private int preferredWidth() {
        Insets insets = plate.getInsets();
        int content = Math.max(NARROW, title.getPreferredSize().width);
        if (details.isVisible()) {
            content = Math.max(content, details.getPreferredSize().width);
        }
        return content + insets.left + insets.right;
    }

    /**
     * Die Breite, die diese Platte hat oder beim nächsten Auslegen bekommt: die eigene, sonst die des nächsten
     * Vorfahren mit Breite, abzüglich der Ränder dazwischen. Beim ersten Einblenden hat sie selbst noch keine.
     */
    private int availableWidth() {
        if (getWidth() > 0) {
            return getWidth();
        }
        int lost = 0;
        for (Container ancestor = getParent(); ancestor != null; ancestor = ancestor.getParent()) {
            Insets insets = ancestor.getInsets();
            lost += insets.left + insets.right;
            if (ancestor.getWidth() > 0) {
                return Math.max(1, ancestor.getWidth() - lost);
            }
        }
        return WrappedText.FALLBACK_WIDTH;
    }

    private int heightFor(int width) {
        Insets own = getInsets();
        return plateHeight(width - own.left - own.right) + own.top + own.bottom;
    }

    @Override
    public Dimension getPreferredSize() {
        Insets own = getInsets();
        reported = heightFor(availableWidth());
        return new Dimension(preferredWidth() + own.left + own.right, reported);
    }

    @Override
    public Dimension getMinimumSize() {
        return getPreferredSize();
    }

    /** Nie höher als ihr Inhalt, auch in BoxLayout oder GridBag mit Füllung. */
    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    /** Die Platte sitzt oben und ist so hoch wie ihr Inhalt, auch wenn der Behälter mehr Höhe gibt. */
    @Override
    public void doLayout() {
        Insets own = getInsets();
        int width = getWidth() - own.left - own.right;
        if (width <= 0) {
            // Noch unsichtbar und ohne Breite ausgelegt: Die Platte bekommt ihre Größe beim Einblenden.
            plate.setBounds(own.left, own.top, 0, 0);
            return;
        }
        int height = Math.min(plateHeight(width), getHeight() - own.top - own.bottom);
        plate.setBounds(own.left, own.top, width, Math.max(0, height));
    }

    @Override
    public void setBounds(int x, int y, int width, int height) {
        super.setBounds(x, y, width, height);
        // Andere Breite, anderer Umbruch: braucht die Platte eine andere Höhe, als der Behälter erfahren hat, legt der
        // neu aus. Je Breite nur einmal, sonst liefe das im Kreis.
        if (width > 0 && width != checkedWidth) {
            checkedWidth = width;
            if (isVisible() && heightFor(width) != reported) {
                SwingUtilities.invokeLater(this::revalidate);
            }
        }
    }

    /** Für Tests: die größte Höhe, die diese Platte je einnimmt (Details aufgeklappt, Text über dem Limit). */
    int maximumHeight() {
        Insets own = getInsets();
        Insets insets = plate.getInsets();
        return own.top + own.bottom + insets.top + insets.bottom + title.getPreferredSize().height
                + GAP + MAX_LINES_OPEN * lineHeight() + GAP + details.getPreferredSize().height;
    }

    public void working(String text) {
        show(Comic.BLUE, text, null, null);
    }

    public void success(String text) {
        show(Comic.GREEN, text, null, null);
    }

    public void info(String text, String detail) {
        show(Comic.PETROL, text, detail, null);
    }

    public void warning(String text, String detail) {
        show(Comic.ORANGE, text, detail, null);
    }

    public void problem(Throwable error) {
        Problems.Problem problem = Problems.describe(error);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Wie {@link #problem(Throwable)}, mit dem gescheiterten Schritt in der Überschrift. */
    public void problem(Throwable error, Problems.Step step) {
        Problems.Problem problem = Problems.describe(error, step);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Wie {@link #problem(Throwable, Problems.Step)}; {@code subject} nennt, worum es ging (z. B. den Space). */
    public void problem(Throwable error, Problems.Step step, String subject) {
        Problems.Problem problem = Problems.describe(error, step, subject);
        show(Comic.RED, problem.title(), problem.message() + (problem.hint() == null ? "" : "\n" + problem.hint()),
                problem.technical());
    }

    /** Für Tests: Überschrift und Text der Meldung. */
    public String text() {
        return target != null ? target.text() : title.getText() + "\n" + message.getText();
    }

    public void clear() {
        if (target != null) {
            target.clear();
            return;
        }
        setVisible(false);
        if (statusLine != null && inStatusLine) {
            // Die Laufmeldung („… wird geladen“) steht in der Statuszeile; sie endet mit dem Vorgang.
            inStatusLine = false;
            statusLine.accept("", Comic.MUTED);
        }
    }

    /**
     * Alle Meldungen erscheinen künftig in {@code target}; diese Platte bleibt unsichtbar. So hat eine Ansicht mit
     * mehreren Teilen genau einen Statusbereich, der immer die letzte Meldung zeigt.
     */
    public void forwardTo(Feedback target) {
        this.target = target;
        setVisible(false);
    }

    /**
     * Lauf-, Erfolgs- und Infomeldungen gehen künftig an {@code sink} (Text und Farbe), etwa in die Statuszeile des
     * Hauptfensters; die Platte erscheint nur noch für Warnungen und Probleme. {@link #text()} nennt weiter die
     * letzte Meldung.
     */
    public void statusLine(java.util.function.BiConsumer<String, Color> sink) {
        this.statusLine = sink;
    }

    private void show(Color stripe, String heading, String text, String tech) {
        if (target != null) {
            target.show(stripe, heading, text, tech);
            return;
        }
        if (statusLine != null && (stripe == Comic.BLUE || stripe == Comic.GREEN || stripe == Comic.PETROL)) {
            title.setText(heading);
            message.setText(text == null ? "" : text);
            setVisible(false);
            inStatusLine = true;
            statusLine.accept(text == null || text.isEmpty() ? heading : heading + ": " + text, stripe);
            return;
        }
        if (statusLine != null) {
            // Eine Warnung oder ein Problem löst die Laufmeldung („… wird geladen“) in der Statuszeile ab.
            inStatusLine = false;
            statusLine.accept("", stripe);
        }
        plate.setAccentStripe(stripe);
        title.setText(heading);
        message.setText(text == null ? "" : text);
        message.setVisible(text != null && !text.isEmpty());
        technical.setText(tech == null ? "" : tech);
        technical.setVisible(false);
        details.setVisible(tech != null);
        scroll.getViewport().setViewPosition(new Point(0, 0));
        setVisible(true);
        relayout();
    }

    /** Legt die Platte und ihren Behälter neu aus; der Scrollbereich darin hielte die Neuberechnung sonst bei sich. */
    private void relayout() {
        scroll.setVisible(message.isVisible() || technical.isVisible());
        plate.invalidate();
        revalidate();
        repaint();
        if (getParent() != null) {
            getParent().revalidate();
            getParent().repaint();
        }
    }
}
