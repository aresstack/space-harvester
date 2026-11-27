package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Die gemeinsame Kopfleiste aller Reiter einer Seite (Analysen-Unterreiter wie Seitenreiter): EIN Aufbau, damit jede
 * Ansicht ihre Bedienelemente an derselben Stelle hat und keine Knopfzeilen unter dem Inhalt braucht.
 *
 * <pre>
 *   Titel  [Abzeichen]  Hinweis …                [Suche/Filter] [Aktionen …] [Kopieren] [Stand]
 * </pre>
 *
 * Links der Titel (fett), daneben kleine Abzeichen (etwa „Experimentell“) und ein gedämpfter Hinweis; rechts, immer in
 * dieser Reihenfolge: das Such- oder Filterfeld, die Aktionen des Reiters, der Kopieren-Knopf ({@link CopyButton}) und
 * ganz rechts das Abzeichen mit dem Stand („Aktuell“, „Nicht durchgeführt“). Was ein Reiter nicht braucht, lässt er
 * weg; die Reihenfolge der übrigen bleibt.
 *
 * <p>Wird die Leiste schmal (UX W4b), weicht sie in fester Reihenfolge ({@link HeaderLayout}): zuerst wird das
 * Suchfeld schmaler, bis auf etwa {@value #SEARCH_MIN} px, aber nur so weit, wie es links etwas sichtbar hält (für
 * einen Hinweis, der ohnehin wegfiele, gibt es keine Breite her); dann fallen Abzeichen und Hinweis weg; dann kürzt sich der
 * Titel mit „…“, und blieben davon weniger als {@value #TITLE_MIN_CHARS} Zeichen, verschwindet er ganz (er steht ja
 * im Reiter). Aktionen, Kopieren-Knopf, Werkzeuge und Stand behalten immer ihre volle Breite.
 */
public final class TabHeader extends JPanel {

    /** So schmal wird das Suchfeld höchstens, bevor der Titel weicht. */
    static final int SEARCH_MIN = 160;
    /** Weniger Zeichen des Titels als diese (vor dem „…“) zeigt die Leiste nicht, sondern blendet den Titel aus. */
    static final int TITLE_MIN_CHARS = 4;
    static final String ELLIPSIS = "…";
    /** Abstand zwischen linker Seite (Titel) und Suchfeld bzw. Aktionen. */
    private static final int GAP = 12;

    private final TitleLabel title = new TitleLabel();
    /** Titel, Abzeichen und Hinweis; weicht der rechten Seite, statt sie zu überdecken ({@link LeftRow}). */
    private final JPanel left = new JPanel(new LeftRow(8));
    /** Das Suchfeld füllt die Breite, die {@link HeaderLayout} ihm gibt (zwischen {@value #SEARCH_MIN} px und bevorzugt). */
    private final JPanel search = new JPanel(new BorderLayout());
    /** Aktionen, Werkzeuge und Stand: immer in voller Breite. */
    private final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
    private final JLabel status = statusBadge(" ", Comic.MUTED);
    /** Was ein Klick auf den Stand tut, etwa „Veraltet“ neu berechnen; {@code null}: der Stand ist nur Anzeige. */
    private Runnable statusAction;
    private CopyButton copy;

    public TabHeader(String heading) {
        setLayout(new HeaderLayout());
        setOpaque(false);
        title.heading(heading);
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);
        left.setOpaque(false);
        left.add(title);
        add(left);
        search.setOpaque(false);
        add(search);
        right.setOpaque(false);
        for (JPanel part : new JPanel[] {actions, tools}) {
            part.setOpaque(false);
            right.add(part);
        }
        status.setVisible(false);
        status.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (statusAction != null && status.isEnabled()) {
                    statusAction.run();
                }
            }
        });
        right.add(status);
        add(right);
    }

    /** Ändert den Titel. */
    public void title(String heading) {
        title.heading(heading);
        revalidate();
        repaint();
    }

    /** Der Titel, wie er gerade steht (ungekürzt, auch wenn die Leiste ihn kürzt oder ausblendet). */
    public String titleText() {
        return title.heading;
    }

    /** Ein kleines Abzeichen neben dem Titel, etwa „Experimentell“. */
    public TabHeader badge(String text, Color tone) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(11f));
        label.setForeground(tone);
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(1, 6, 1, 6)));
        left.add(label);
        return this;
    }

    /** Ein gedämpfter Hinweis rechts neben Titel und Abzeichen. */
    public TabHeader note(String text) {
        left.add(Comic.muted(text));
        return this;
    }

    /** Das Such- oder Filterfeld des Reiters, rechts vor den Aktionen; eines je Reiter. */
    public TabHeader search(JComponent field) {
        search.removeAll();
        search.add(field);
        return this;
    }

    /** Aktionen des Reiters (Knöpfe), rechts zwischen Suchfeld und Kopieren-Knopf. */
    public TabHeader action(Component... buttons) {
        for (Component button : buttons) {
            actions.add(button);
        }
        return this;
    }

    /** Der Kopieren-Knopf: kopiert {@code text.get()}, den Inhalt des Reiters als Text, in die Zwischenablage. */
    public TabHeader copy(Supplier<String> text) {
        if (copy != null) {
            tools.remove(copy);
        }
        copy = new CopyButton(text);
        tools.add(copy, 0);
        return this;
    }

    /** Weitere Symbolknöpfe ganz rechts, vor dem Stand (etwa Export). */
    public TabHeader tool(Component... buttons) {
        for (Component button : buttons) {
            tools.add(button);
        }
        return this;
    }

    /** Der Stand des Reiters als Abzeichen ganz rechts; {@code null} blendet es aus. */
    public void status(String text, Color tone) {
        status(text, tone, null);
    }

    /** Wie {@link #status(String, Color)}, mit einem Grund im Tooltip (etwa gelb „Ausgelassen“). */
    public void status(String text, Color tone, String tooltip) {
        status(text, tone, tooltip, null);
    }

    /**
     * Wie {@link #status(String, Color, String)}; mit {@code action} ist der Stand zugleich ein Knopf (Handcursor,
     * Klick ruft {@code action}), etwa „Veraltet“, das die Analyse neu berechnet. {@code null} macht ihn zur Anzeige.
     */
    public void status(String text, Color tone, String tooltip, Runnable action) {
        status.setVisible(text != null);
        status.setText(text == null ? " " : text);
        status.setToolTipText(tooltip);
        tone(status, tone == null ? Comic.MUTED : tone);
        statusAction = text == null ? null : action;
        Cursors.set(status, java.awt.Cursor.getPredefinedCursor(statusAction == null ? java.awt.Cursor.DEFAULT_CURSOR
                : java.awt.Cursor.HAND_CURSOR));
    }

    /** Für Tests: ob der Stand gerade ein Knopf ist. */
    public boolean statusClickable() {
        return statusAction != null;
    }

    /** Für Tests: klickt den Stand an, als hätte der Benutzer es getan. */
    public void clickStatus() {
        if (statusAction != null && status.isVisible() && status.isEnabled()) {
            statusAction.run();
        }
    }

    /** Für Tests: der Tooltip des Stands. */
    public String statusTooltip() {
        return status.getToolTipText();
    }

    /** Ein Abzeichen im Stil des Stands: Text in Farbe mit feinem Rahmen derselben Farbe. */
    public static JLabel statusBadge(String text, Color tone) {
        JLabel label = new JLabel(text);
        label.setFont(Comic.semiBold(12f));
        label.setOpaque(true);
        tone(label, tone);
        return label;
    }

    /** Färbt ein Abzeichen aus {@link #badge} um. */
    public static void tone(JLabel badge, Color tone) {
        badge.setForeground(tone);
        badge.setBackground(Comic.SURFACE);
        badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
    }

    /**
     * Der Titel: merkt sich den vollen Wortlaut und meldet dessen Breite als bevorzugte, zeigt aber, was
     * {@link LeftRow} ihm lässt (gekürzt mit „…“, im Tooltip dann der volle Titel).
     */
    static final class TitleLabel extends JLabel {

        private String heading = " ";

        TitleLabel() {
            super(" ");
        }

        void heading(String text) {
            heading = text == null || text.isEmpty() ? " " : text;
            show(heading);
        }

        /** Zeigt {@code text}; nur bei einer Änderung, damit das Layout nicht ohne Not neu anstößt. */
        private void show(String text) {
            if (!text.equals(getText())) {
                setText(text);
            }
            String tip = text.equals(heading) ? null : heading;
            if (!java.util.Objects.equals(tip, getToolTipText())) {
                setToolTipText(tip);
            }
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            if (isPreferredSizeSet()) {
                return size;
            }
            java.awt.Insets insets = getInsets();
            int width = insets.left + insets.right + getFontMetrics(getFont()).stringWidth(heading) + 1;
            return new java.awt.Dimension(width, size.height);
        }

        /**
         * Passt den Titel in {@code width} px ein und liefert die Breite, die er dann braucht: voll, gekürzt mit „…“
         * bei mindestens {@value #TITLE_MIN_CHARS} Zeichen, sonst 0 (ausgeblendet).
         */
        int fit(int width) {
            int full = getPreferredSize().width;
            if (width >= full) {
                show(heading);
                return full;
            }
            java.awt.Insets insets = getInsets();
            java.awt.FontMetrics metrics = getFontMetrics(getFont());
            int room = width - insets.left - insets.right - 1;
            int[] points = heading.codePoints().toArray();
            int keep = points.length - 1;
            String text = null;
            while (keep >= TITLE_MIN_CHARS) {
                String prefix = new String(points, 0, keep).stripTrailing();
                if (prefix.codePointCount(0, prefix.length()) >= TITLE_MIN_CHARS
                        && metrics.stringWidth(prefix + ELLIPSIS) <= room) {
                    text = prefix + ELLIPSIS;
                    break;
                }
                keep--;
            }
            if (text == null) {
                show(heading);
                return 0;
            }
            show(text);
            return Math.min(width, insets.left + insets.right + metrics.stringWidth(text) + 1);
        }
    }

    /**
     * Die Leiste selbst: rechts Aktionen, Werkzeuge und Stand in voller Breite, links davon das Suchfeld, links der
     * Rest für Titel, Abzeichen und Hinweis. Fehlt Platz, wird zuerst das Suchfeld schmaler (bis {@value #SEARCH_MIN}
     * px), erst dann weicht die linke Seite ({@link LeftRow}). Nichts überdeckt sich.
     */
    final class HeaderLayout implements java.awt.LayoutManager {

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        private boolean hasSearch() {
            return search.isVisible() && search.getComponentCount() > 0;
        }

        private int searchMin() {
            return hasSearch() ? Math.min(SEARCH_MIN, search.getPreferredSize().width) : 0;
        }

        private int searchPref() {
            return hasSearch() ? search.getPreferredSize().width : 0;
        }

        /**
         * Breiten der linken Seite, wenn nur die ersten 1, 2, … sichtbaren Teile (Titel, Abzeichen, Hinweis) stehen,
         * gerechnet wie {@link LeftRow#preferredLayoutSize}; die letzte ist die bevorzugte Breite.
         */
        private List<Integer> leftPrefixes() {
            java.awt.Insets insets = left.getInsets();
            int gap = ((LeftRow) left.getLayout()).gap;
            List<Integer> prefixes = new ArrayList<>();
            int width = insets.left + insets.right + gap;
            for (Component child : left.getComponents()) {
                if (child.isVisible()) {
                    width += child.getPreferredSize().width + gap;
                    prefixes.add(width);
                }
            }
            return prefixes;
        }

        private int height(java.awt.Container parent) {
            int height = 0;
            for (Component part : new Component[] {left, search, right}) {
                height = Math.max(height, part.getPreferredSize().height);
            }
            java.awt.Insets insets = parent.getInsets();
            return insets.top + insets.bottom + height;
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int width = left.getPreferredSize().width + GAP + searchPref() + right.getPreferredSize().width;
            return new java.awt.Dimension(insets.left + insets.right + width, height(parent));
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int width = GAP + searchMin() + right.getPreferredSize().width;
            return new java.awt.Dimension(insets.left + insets.right + width, height(parent));
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int x0 = insets.left;
            int x1 = parent.getWidth() - insets.right;
            int top = insets.top;
            int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            // Rechts immer volle Breite, auch wenn sie links hinausragen müsste.
            int rightWidth = right.getPreferredSize().width;
            int rightX = x1 - rightWidth;
            // Senkrecht mittig wie das Suchfeld, damit Knöpfe, Stand und Feld auf einer Linie stehen.
            int rightHeight = Math.min(height, right.getPreferredSize().height);
            right.setBounds(rightX, top + (height - rightHeight) / 2, rightWidth, rightHeight);
            int room = Math.max(0, rightX - x0 - GAP);
            int searchWidth = 0;
            if (hasSearch()) {
                // Das Suchfeld weicht nur so weit, wie es links etwas sichtbar macht: für den längsten Anfang
                // (Titel; Titel und Abzeichen; …), der neben dem schmalsten Suchfeld noch ganz passt. Was ohnehin
                // wegfällt (etwa ein langer Hinweis), kostet das Feld keine Breite.
                // Passt nicht einmal der Titel ganz, bleibt das Suchfeld am Minimum und der Titel kürzt sich.
                List<Integer> prefixes = leftPrefixes();
                int shown = prefixes.isEmpty() ? 0 : prefixes.get(0);
                for (int prefix : prefixes) {
                    if (room - prefix >= searchMin()) {
                        shown = prefix;
                    }
                }
                searchWidth = Math.max(searchMin(), Math.min(searchPref(), room - shown));
                searchWidth = Math.min(searchWidth, Math.max(0, rightX - x0));
            }
            int searchHeight = Math.min(height, search.getPreferredSize().height);
            search.setBounds(rightX - searchWidth, top + (height - searchHeight) / 2, searchWidth, searchHeight);
            int leftWidth = Math.max(0, rightX - searchWidth - GAP - x0);
            left.setBounds(x0, top, leftWidth, height);
        }
    }

    /**
     * Eine Zeile von links wie {@code FlowLayout.LEFT}, die nie über ihre Breite hinausragt: Reicht der Platz nicht,
     * lässt sie zuerst alles hinter dem Titel weg (Abzeichen, Hinweis), kürzt dann den Titel (das erste Element) mit
     * „…“ und blendet ihn aus, sobald weniger als {@value #TITLE_MIN_CHARS} Zeichen blieben ({@link TitleLabel#fit}),
     * statt etwas unter die rechte Seite zu legen.
     */
    static final class LeftRow implements java.awt.LayoutManager {

        private final int gap;

        LeftRow(int gap) {
            this.gap = gap;
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int width = 0;
            int height = 0;
            for (Component child : parent.getComponents()) {
                if (child.isVisible()) {
                    java.awt.Dimension size = child.getPreferredSize();
                    width += gap + size.width;
                    height = Math.max(height, size.height);
                }
            }
            return new java.awt.Dimension(insets.left + insets.right + width + gap,
                    insets.top + insets.bottom + height);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            return new java.awt.Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int right = parent.getWidth() - insets.right;
            // Mittig in der ganzen Höhe der linken Seite, damit der Titel auf der Linie von Suchfeld und Werkzeugen steht.
            int rowHeight = Math.max(preferredLayoutSize(parent).height, parent.getHeight()) - insets.top - insets.bottom;
            int x = insets.left + gap;
            boolean first = true;
            boolean full = false;
            for (Component child : parent.getComponents()) {
                if (!child.isVisible()) {
                    continue;
                }
                java.awt.Dimension size = child.getPreferredSize();
                int width = size.width;
                if (!full && x + width > right) {
                    full = true;
                    // Der Titel bleibt gekürzt stehen; was danach kommt, passt nicht mehr ganz und fällt weg.
                    int room = Math.max(0, right - x);
                    width = !first ? 0 : child instanceof TitleLabel label ? label.fit(room) : room;
                } else if (full) {
                    width = 0;
                } else if (child instanceof TitleLabel label) {
                    width = label.fit(width);
                }
                child.setBounds(x, insets.top + (rowHeight - size.height) / 2, width, width == 0 ? 0 : size.height);
                x += width + gap;
                first = false;
            }
        }
    }

    // ---- für Tests

    public String statusText() {
        return status.isVisible() ? status.getText() : null;
    }

    public CopyButton copyButton() {
        return copy;
    }

    /** Für Tests: der Titel, wie er gerade zu sehen ist; {@code null}, wenn die Leiste ihn ausgeblendet hat. */
    String titleShown() {
        return title.getWidth() <= 0 ? null : title.getText();
    }

    JLabel titleLabel() {
        return title;
    }

    JComponent leftPart() {
        return left;
    }

    JComponent searchPart() {
        return search;
    }

    JComponent rightPart() {
        return right;
    }
}
