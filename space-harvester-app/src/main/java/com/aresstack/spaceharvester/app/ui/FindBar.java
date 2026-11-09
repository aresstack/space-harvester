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
 * Suche im gerade sichtbaren Bereich wie im Webbrowser, direkt über der Statuszeile und nur so breit wie die
 * Arbeitsfläche (nicht über dem Explorer). Ablauf und Bedienung aus der Suchleiste des Lesebereichs im Enterprise AI
 * Client ({@code ReaderToolbar}): Feld, „aktueller Treffer / Gesamtzahl“, vorheriger, nächster Treffer. Enter springt
 * zum nächsten Treffer (bei neuem Text: neue Suche), Shift+Enter zum vorigen, Esc entfernt die Markierungen.
 * Gesucht und markiert wird mit {@link ReaderFind} unterhalb der Wurzel, die {@code area} gerade liefert.
 */
final class FindBar extends JPanel {

    static final String PLACEHOLDER = "In dieser Ansicht suchen … (Strg+F)";
    private static final int BUTTON_SIZE = 22;

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

    FindBar(Supplier<JComponent> area) {
        super(new BorderLayout(6, 0));
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
        bind(field, KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "find.clear", this::clear);
        previousButton = small(ComposerIcons.chevronUp(), "Vorheriger Treffer (Shift+Enter)");
        previousButton.addActionListener(event -> previous());
        nextButton = small(ComposerIcons.chevronDown(), "Nächster Treffer (Enter)");
        nextButton.addActionListener(event -> next());
        JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        controls.setOpaque(false);
        controls.add(counter);
        controls.add(previousButton);
        controls.add(nextButton);
        JLabel icon = new JLabel(ComposerIcons.magnifier());
        add(icon, BorderLayout.WEST);
        add(field, BorderLayout.CENTER);
        add(controls, BorderLayout.EAST);
        updateCounter();
    }

    /** Strg+F: Fokus ins Feld, Text markiert. */
    void focus() {
        field.requestFocusInWindow();
        field.selectAll();
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
        if (!field.getText().trim().isEmpty()) {
            step(true);
        }
    }

    /** Sucht {@code query}, als hätte man es eingetippt und Enter gedrückt; liefert die Zahl der Treffer. */
    int search(String query) {
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
