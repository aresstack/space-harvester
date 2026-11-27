package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.text.AbstractDocument;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.DocumentFilter;
import java.awt.Dimension;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Eine editierbare Auswahl für eine ganze Zahl in festen Grenzen, etwa „parallele Downloads“ in der Kopfzeile der
 * Aufträge (Angelo 11.11.2026): Vorschläge in der Liste, freie Eingabe nur aus Ziffern. Ein Wert außerhalb der
 * Grenzen wird nicht übernommen; die Auswahl springt auf den gültigen Wert zurück und sagt, warum.
 */
public final class ParallelChoice {

    private final JComboBox<String> box = Comic.combo();
    private final int min;
    private final int max;
    private final IntConsumer apply;
    private final Consumer<String> invalid;
    private final List<Integer> suggestions;
    private int value;
    private boolean updating;
    /** Fragt den eigenen Wert ab (aktueller Wert hinein, neuer oder {@code null} heraus); Tests setzen einen Ersatz. */
    public java.util.function.IntFunction<Integer> ask = current -> null;

    /**
     * @param suggestions Werte in der Liste
     * @param initial     der Wert beim Anlegen
     * @param apply       bekommt jeden neuen, gültigen Wert
     * @param invalid     bekommt die Meldung zu einer ungültigen Eingabe
     */
    public ParallelChoice(String tooltip, int min, int max, List<Integer> suggestions, int initial, IntConsumer apply,
                   Consumer<String> invalid) {
        this.min = min;
        this.max = max;
        this.apply = apply;
        this.invalid = invalid;
        this.value = initial;
        this.suggestions = List.copyOf(suggestions);
        box.setEditable(true);
        box.setToolTipText(tooltip);
        for (int suggestion : suggestions) {
            box.addItem(Integer.toString(suggestion));
        }
        if (box.getEditor().getEditorComponent() instanceof JTextField field) {
            field.setToolTipText(tooltip);
            field.setColumns(2);
            if (field.getDocument() instanceof AbstractDocument document) {
                document.setDocumentFilter(new DigitsOnly());
            }
        }
        Dimension size = new Dimension(64, box.getPreferredSize().height);
        box.setPreferredSize(size);
        box.setMaximumSize(size);
        show(initial);
        box.addActionListener(e -> {
            if (!updating) {
                commit();
            }
        });
    }

    public JComboBox<String> box() {
        return box;
    }

    /** Der zuletzt übernommene Wert. */
    public int value() {
        return value;
    }

    /** Zeigt {@code shown} an, ohne {@code apply} zu rufen; während der Eingabe bleibt die Auswahl unberührt. */
    void refresh(int shown) {
        JComponent editor = (JComponent) box.getEditor().getEditorComponent();
        if (shown != value && !editor.isFocusOwner() && !box.isPopupVisible()) {
            show(shown);
        }
    }

    private void show(int shown) {
        updating = true;
        try {
            value = shown;
            box.setSelectedItem(Integer.toString(shown));
        } finally {
            updating = false;
        }
    }

    public static final String CUSTOM = "Benutzerdefiniert …";
    /** Die Stufen der Grenzen in den Menüs am Dreieck (Chat, Podcast); darunter „Benutzerdefiniert …“. */
    public static final List<Integer> STEPS = List.of(1, 2, 4, 8, 16, 32, 64);
    /** Größte freie Eingabe unter „Benutzerdefiniert …“ (das Feld nimmt vier Ziffern). */
    public static final int CUSTOM_MAX = 9999;

    /** Der in der App gemerkte Wert einer Grenze ({@link AppState}), 1 bis {@link #CUSTOM_MAX}. */
    public static int stored(String key, int fallback) {
        return Math.max(1, Math.min(CUSTOM_MAX, AppState.shared().getInt(key, fallback)));
    }

    /**
     * Eine Grenze, die die App selbst merkt ({@link AppState}, nicht in den Einstellungen), mit {@link #STEPS} und
     * „Benutzerdefiniert …“: dieselbe Form im Chat und beim Podcast (Angelo 24.11.2026).
     *
     * @param tip     was die Grenze begrenzt; steht im Tooltip und in der Abfrage „Benutzerdefiniert …“
     * @param key     Schlüssel im Zustand der App
     * @param invalid bekommt die Meldung zu einer ungültigen Eingabe
     */
    public static ParallelChoice stored(java.awt.Component owner, String tip, String key, int fallback,
                                        Consumer<String> invalid) {
        return stored(owner, tip, key, fallback, STEPS, invalid);
    }

    /**
     * Wie {@link #stored(java.awt.Component, String, String, int, Consumer)}, mit eigenen Vorschlägen statt
     * {@link #STEPS}, etwa für Größe und Bildrate des Podcast-Videos.
     */
    public static ParallelChoice stored(java.awt.Component owner, String tip, String key, int fallback,
                                        List<Integer> suggestions, Consumer<String> invalid) {
        ParallelChoice choice = new ParallelChoice(tip + " (1 bis " + CUSTOM_MAX + ").", 1, CUSTOM_MAX, suggestions,
                stored(key, fallback), value -> AppState.shared().put(key, value), invalid);
        choice.ask = current -> Dialogs.askNumber(owner, "Benutzerdefiniert", tip, 1, CUSTOM_MAX, current);
        return choice;
    }

    /**
     * Die Auswahl als Untermenü (Angelo 22.11.2026: statt der Auswahlfelder in der Kopfzeile): je Vorschlag ein
     * Eintrag, der geltende Wert mit Haken, darunter „Benutzerdefiniert …“ für eine freie Zahl; gilt gerade eine
     * freie Zahl, trägt dieser Eintrag den Haken und nennt sie.
     *
     * @param title was eingestellt wird, etwa „Parallele Downloads“; der geltende Wert steht dahinter
     */
    public javax.swing.JMenu menu(String title) {
        javax.swing.JMenu menu = new javax.swing.JMenu(title + ": " + value);
        menu.setFont(Comic.regular(13f));
        menu.setToolTipText(box.getToolTipText());
        for (int suggestion : suggestions) {
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(Integer.toString(suggestion),
                    suggestion == value);
            item.setFont(Comic.regular(13f));
            item.addActionListener(e -> choose(Integer.toString(suggestion)));
            menu.add(item);
        }
        menu.addSeparator();
        boolean own = !suggestions.contains(value);
        javax.swing.JCheckBoxMenuItem custom = new javax.swing.JCheckBoxMenuItem(own ? CUSTOM + " (" + value + ")"
                : CUSTOM, own);
        custom.setFont(Comic.regular(13f));
        custom.setToolTipText("Eine ganze Zahl von " + min + " bis " + max);
        custom.addActionListener(e -> {
            Integer chosen = ask.apply(value);
            if (chosen != null) {
                choose(Integer.toString(chosen));
            }
        });
        menu.add(custom);
        return menu;
    }

    public int min() {
        return min;
    }

    public int max() {
        return max;
    }

    /** Übernimmt {@code text} wie eine Eingabe im Feld. */
    void choose(String text) {
        updating = true;
        try {
            box.setSelectedItem(text);
        } finally {
            updating = false;
        }
        commit();
    }

    /** Übernimmt die Eingabe oder springt mit Meldung zurück. */
    void commit() {
        Object selected = box.getSelectedItem();
        Integer parsed = parse(selected == null ? "" : selected.toString(), min, max);
        if (parsed == null) {
            show(value);
            invalid.accept("Bitte eine ganze Zahl von " + min + " bis " + max + " eingeben.");
            return;
        }
        boolean changed = parsed != value;
        show(parsed);
        if (changed) {
            apply.accept(parsed);
        }
    }

    /** Die Zahl in {@code text} oder {@code null}, wenn sie fehlt oder außerhalb von {@code min} bis {@code max} liegt. */
    static Integer parse(String text, int min, int max) {
        String trimmed = text.trim();
        if (trimmed.isEmpty() || trimmed.length() > 4 || !trimmed.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int parsed = Integer.parseInt(trimmed);
        return parsed < min || parsed > max ? null : parsed;
    }

    /** Lässt nur Ziffern in das Eingabefeld. */
    private static final class DigitsOnly extends DocumentFilter {
        @Override
        public void insertString(FilterBypass fb, int offset, String text, AttributeSet attr)
                throws BadLocationException {
            if (text != null && text.chars().allMatch(Character::isDigit)) {
                super.insertString(fb, offset, text, attr);
            }
        }

        @Override
        public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs)
                throws BadLocationException {
            if (text == null || text.chars().allMatch(Character::isDigit)) {
                super.replace(fb, offset, length, text, attrs);
            }
        }
    }
}
