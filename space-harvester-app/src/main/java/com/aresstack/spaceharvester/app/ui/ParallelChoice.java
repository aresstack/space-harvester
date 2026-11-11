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
final class ParallelChoice {

    private final JComboBox<String> box = Comic.combo();
    private final int min;
    private final int max;
    private final IntConsumer apply;
    private final Consumer<String> invalid;
    private int value;
    private boolean updating;

    /**
     * @param suggestions Werte in der Liste
     * @param initial     der Wert beim Anlegen
     * @param apply       bekommt jeden neuen, gültigen Wert
     * @param invalid     bekommt die Meldung zu einer ungültigen Eingabe
     */
    ParallelChoice(String tooltip, int min, int max, List<Integer> suggestions, int initial, IntConsumer apply,
                   Consumer<String> invalid) {
        this.min = min;
        this.max = max;
        this.apply = apply;
        this.invalid = invalid;
        this.value = initial;
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

    JComboBox<String> box() {
        return box;
    }

    /** Der zuletzt übernommene Wert. */
    int value() {
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
