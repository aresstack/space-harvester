package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.function.Supplier;

/**
 * Die Werkzeugleiste direkt über der Statuszeile, nur so breit wie die Arbeitsfläche (nicht über dem Explorer), wie
 * die Zeile unter dem Lesebereich im Enterprise AI Client ({@code ReaderToolbar}): links die Lupe, mittig die
 * Umschalter des Bereichs ({@link #tools}, etwa die Seitenreiter als Symbolknöpfe). Klick auf die Lupe oder Strg+F
 * klappt die Suche nach rechts auf und überdeckt die Umschalter: Feld, „aktueller Treffer / Gesamtzahl“, vorheriger,
 * nächster Treffer. Enter springt zum nächsten Treffer (bei neuem Text: neue Suche), Shift+Enter zum vorigen, Esc
 * entfernt die Markierungen und klappt die Suche zu, ein zweiter Klick auf die Lupe ebenso. Gesucht und markiert wird
 * mit {@link ReaderFind} unterhalb der Wurzel, die {@code area} gerade liefert.
 */
final class FindBar extends JPanel {

    static final String PLACEHOLDER = "In dieser Ansicht suchen … (Strg+F)";
    private static final int BUTTON_SIZE = 22;
    /** Tooltip der Lupe. */
    static final String FIND_TOOLTIP = "In dieser Ansicht suchen (Strg+F)";

    private final Supplier<JComponent> area;
    private final PlaceholderField field = new PlaceholderField(24, PLACEHOLDER);
    private final JLabel counter = Comic.muted("");
    private final ComposerButton previousButton;
    private final ComposerButton nextButton;
    private ReaderFind find;
    /** Der Text, nach dem zuletzt gesucht wurde; {@code null}, solange nichts markiert ist. */
    private String searched;
    /** Die Wurzel, in der {@link #find} sucht. */
    private JComponent findRoot;
    /** Die Lupe links; aufgeklappt hervorgehoben. */
    private final ComposerButton findButton;
    /** Feld mit Zähler und Treffer-Knöpfen; nur aufgeklappt sichtbar. */
    private final JComponent search;
    /** Platz für die Umschalter des Bereichs, mittig. */
    private final JPanel tools = new JPanel(new BorderLayout());
    private boolean open;

    FindBar(Supplier<JComponent> area) {
        super(null);
        setLayout(new RowLayout());
        this.area = area;
        setOpaque(false);
        setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(4, 16, 4, 16)));
        field.getAccessibleContext().setAccessibleName("In dieser Ansicht suchen");
        field.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent event) {
                live();
            }

            @Override
            public void removeUpdate(DocumentEvent event) {
                live();
            }

            @Override
            public void changedUpdate(DocumentEvent event) {
                live();
            }
        });
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "find.next", this::next);
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "find.previous",
                this::previous);
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.close", this::close);
        previousButton = small(ComposerIcons.chevronUp(), "Vorheriger Treffer (Shift+Enter)");
        previousButton.addActionListener(event -> previous());
        nextButton = small(ComposerIcons.chevronDown(), "Nächster Treffer (Enter)");
        nextButton.addActionListener(event -> next());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        controls.setOpaque(false);
        controls.add(counter);
        controls.add(previousButton);
        controls.add(nextButton);
        // Ein Feld mit allem darin (AskAI-ComicFindBar): Text, ✕ bei Text, Zähler, vorheriger/nächster Treffer; die
        // Lupe steht als Knopf links davor.
        com.aresstack.spaceharvester.app.ui.controls.InputFrame frame =
                new com.aresstack.spaceharvester.app.ui.controls.InputFrame(field);
        frame.addClearButton();
        frame.addButton(controls);
        search = frame;
        findButton = ComposerButton.iconButton(ComposerIcons.magnifier(), FIND_TOOLTIP);
        findButton.getAccessibleContext().setAccessibleName("In dieser Ansicht suchen");
        findButton.addActionListener(event -> {
            if (open) {
                close();
            } else {
                focus();
            }
        });
        tools.setOpaque(false);
        add(findButton);
        add(tools);
        add(search);
        expand(false);
        updateCounter();
    }

    /** Strg+F oder Klick auf die Lupe: Suche aufklappen, Fokus ins Feld, Text markiert. */
    void focus() {
        if (!open) {
            expand(true);
            if (!field.getText().trim().isEmpty()) {
                step(true);
            }
        }
        field.requestFocusInWindow();
        field.selectAll();
    }

    /** Esc oder zweiter Klick auf die Lupe: Markierungen weg, Suche zuklappen; der Suchtext bleibt stehen. */
    void close() {
        clear();
        expand(false);
    }

    /** Setzt die Umschalter des gezeigten Bereichs mittig in die Leiste; {@code null} lässt die Mitte leer. */
    void tools(JComponent component) {
        tools.removeAll();
        if (component != null) {
            tools.add(component, BorderLayout.CENTER);
        }
        tools.revalidate();
        tools.repaint();
        revalidate();
        repaint();
    }

    /** Ob die Suche aufgeklappt ist. */
    boolean isOpen() {
        return open;
    }

    ComposerButton findButton() {
        return findButton;
    }

    JComponent toolsArea() {
        return tools;
    }

    JComponent searchArea() {
        return search;
    }

    /** Enter: neuer Text sucht neu, derselbe Text springt zum nächsten Treffer. */
    void next() {
        step(true);
    }

    /** Shift+Enter: neuer Text sucht neu, derselbe Text springt zum vorigen Treffer. */
    void previous() {
        step(false);
    }

    /** Entfernt die Markierungen (Esc, Wechsel des Bereichs); der Suchtext bleibt stehen. */
    void clear() {
        if (find != null) {
            find.clear();
        }
        find = null;
        searched = null;
        updateCounter();
    }

    /** Der Bereich wurde gewechselt: alte Markierungen weg, mit stehendem Text im neuen Bereich neu suchen. */
    void areaChanged() {
        clear();
        if (open && !field.getText().trim().isEmpty()) {
            step(true);
        }
    }

    /** Sucht {@code query}, als hätte man es eingetippt und Enter gedrückt; liefert die Zahl der Treffer. */
    int search(String query) {
        expand(true);
        field.setText(query == null ? "" : query);
        return find == null ? 0 : find.hitCount();
    }

    String counterText() {
        return counter.getText();
    }

    PlaceholderField field() {
        return field;
    }

    ReaderFind find() {
        return find;
    }

    // ------------------------------------------------------------------ intern

    /** Aufgeklappt: Feld statt Umschalter, Lupe hervorgehoben; zugeklappt umgekehrt. */
    private void expand(boolean searchOpen) {
        open = searchOpen;
        search.setVisible(searchOpen);
        tools.setVisible(!searchOpen);
        findButton.setEmphasized(searchOpen);
        revalidate();
        repaint();
    }

    /** Wie im Browser: Tippen sucht sofort. */
    private void live() {
        searched = null;
        step(true);
    }

    private void step(boolean forward) {
        JComponent root = area.get();
        if (root == null) {
            clear();
            return;
        }
        if (find != null && root != findRoot) {
            find.clear();
            find = null;
            searched = null;
        }
        if (find == null) {
            find = new ReaderFind(root);
            findRoot = root;
        }
        if (find.stale()) {
            // Inhalt hat sich geändert (anderer Reiter, neu geladenes Dokument): gemerkte Stellen verwerfen.
            find.clear();
            searched = null;
        }
        String query = field.getText();
        if (query.trim().isEmpty()) {
            find.clear();
            searched = null;
        } else if (query.equals(searched) && find.hitCount() > 0) {
            if (forward) {
                find.next();
            } else {
                find.previous();
            }
        } else {
            find.search(query);
            searched = query;
        }
        updateCounter();
    }

    private void updateCounter() {
        boolean active = searched != null && find != null;
        counter.setText(!active ? "" : find.hitCount() == 0 ? "Keine Treffer"
                : (find.currentIndex() + 1) + " / " + find.hitCount());
        previousButton.setEnabled(active && find.hitCount() > 0);
        nextButton.setEnabled(active && find.hitCount() > 0);
    }

    private static ComposerButton small(javax.swing.Icon icon, String tooltip) {
        ComposerButton button = ComposerButton.iconButton(icon, tooltip);
        button.setPreferredSize(new Dimension(BUTTON_SIZE, BUTTON_SIZE));
        return button;
    }

    /**
     * Lupe links in voller Zeilenhöhe; die Umschalter mittig über die ganze Zeile (nie unter die Lupe); die offene
     * Suche füllt alles rechts der Lupe.
     */
    private final class RowLayout implements java.awt.LayoutManager {
        private static final int GAP = 6;

        @Override
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            Dimension button = findButton.getPreferredSize();
            Dimension middle = tools.getPreferredSize();
            Dimension field = search.getPreferredSize();
            int height = Math.max(button.height, Math.max(middle.height, field.height));
            int width = button.width + GAP + Math.max(middle.width, field.width);
            return new Dimension(width + insets.left + insets.right, height + insets.top + insets.bottom);
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            Dimension preferred = preferredLayoutSize(parent);
            return new Dimension(findButton.getPreferredSize().width + insets.left + insets.right, preferred.height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets insets = parent.getInsets();
            int left = insets.left;
            int right = parent.getWidth() - insets.right;
            int top = insets.top;
            int height = Math.max(0, parent.getHeight() - insets.top - insets.bottom);
            Dimension button = findButton.getPreferredSize();
            findButton.setBounds(left, top + (height - button.height) / 2, button.width, button.height);
            int start = left + button.width + GAP;
            Dimension field = search.getPreferredSize();
            int fieldHeight = Math.min(height, field.height);
            search.setBounds(start, top + (height - fieldHeight) / 2, Math.max(0, right - start), fieldHeight);
            int middle = Math.min(tools.getPreferredSize().width, Math.max(0, right - start));
            int x = Math.max(start, Math.min((parent.getWidth() - middle) / 2, right - middle));
            tools.setBounds(x, top, middle, height);
        }
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable action) {
        component.getInputMap(WHEN_FOCUSED).put(key, name);
        component.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }
}
