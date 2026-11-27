package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import java.awt.FlowLayout;
import java.text.NumberFormat;
import java.util.Locale;

/**
 * Eine Länge als Zahl mit Einheit wie {@link DurationField}: Wörter (Standard) oder DIN-A4-Seiten. Intern zählen immer
 * Wörter ({@link #words()}); bei Seiten rechnet das Feld mit den Wörtern je Seite um, die daneben einstellbar sind, und
 * sagt grau dahinter, wie vielen Wörtern das entspricht (Angelo 27.11.2026). 0 heißt „keine Vorgabe“.
 */
public class LengthField extends JPanel {

    /** Die Einheiten in der Reihenfolge der Auswahlliste. */
    public enum Unit {
        WORDS("Wörter"), PAGES("DIN-A4-Seiten");

        private final String label;

        Unit(String label) {
            this.label = label;
        }

        @Override
        public String toString() {
            return label;
        }

        /** Die Einheit zu ihrem Namen; Wörter, wenn der Name leer oder unbekannt ist. */
        public static Unit parse(String name) {
            for (Unit u : values()) {
                if (u.name().equals(name)) {
                    return u;
                }
            }
            return WORDS;
        }
    }

    private final JSpinner amount;
    private final JComboBox<Unit> unit = Comic.combo();
    private final JLabel perPageLabel = new JLabel("je Seite");
    private final JSpinner perPage;
    private final JLabel equals = new JLabel();

    /**
     * @param wordsPerPage Wörter je DIN-A4-Seite, mit denen die Seiten umgerechnet werden (änderbar im Feld)
     */
    public LengthField(int wordsPerPage) {
        super(new FlowLayout(FlowLayout.LEFT, 6, 0));
        setOpaque(false);
        amount = spinner(0, 5);
        perPage = spinner(Math.max(1, wordsPerPage), 4);
        ((SpinnerNumberModel) perPage.getModel()).setMinimum(1);
        for (Unit u : Unit.values()) {
            unit.addItem(u);
        }
        perPageLabel.setFont(Comic.regular(12.5f));
        equals.setFont(Comic.regular(12.5f));
        equals.setForeground(Comic.MUTED);
        add(amount);
        add(unit);
        add(perPageLabel);
        add(perPage);
        add(equals);
        unit.addActionListener(e -> update());
        amount.addChangeListener(e -> update());
        perPage.addChangeListener(e -> update());
        update();
    }

    private static JSpinner spinner(int value, int columns) {
        JSpinner spinner = new JSpinner(new SpinnerNumberModel(value, 0, Integer.MAX_VALUE, 1));
        spinner.setFont(Comic.regular(13f));
        if (spinner.getEditor() instanceof JSpinner.DefaultEditor editor) {
            Comic.style(editor.getTextField());
            editor.getTextField().setColumns(columns);
            editor.getTextField().setBorder(javax.swing.BorderFactory.createEmptyBorder(2, 6, 2, 4));
        }
        spinner.setBorder(Comic.fieldBorder());
        return spinner;
    }

    private void update() {
        boolean pages = unit() == Unit.PAGES;
        perPageLabel.setVisible(pages);
        perPage.setVisible(pages);
        int words = words();
        equals.setText(amount() == 0 ? "keine Vorgabe: so lang, wie die Belege tragen"
                : pages ? "entspricht " + NumberFormat.getIntegerInstance(Locale.GERMANY).format(words) + " Wörtern"
                : "");
        revalidate();
    }

    /** Die eingegebene Zahl in der gewählten Einheit. */
    public int amount() {
        return ((Number) amount.getValue()).intValue();
    }

    public Unit unit() {
        return (Unit) unit.getSelectedItem();
    }

    /** Die Wörter je Seite, mit denen umgerechnet wird. */
    public int wordsPerPage() {
        return ((Number) perPage.getValue()).intValue();
    }

    /** Die Länge in Wörtern; 0 = keine Vorgabe. */
    public int words() {
        long words = (long) amount() * (unit() == Unit.PAGES ? wordsPerPage() : 1);
        return (int) Math.min(Integer.MAX_VALUE, words);
    }

    /** Setzt Zahl und Einheit; {@code null} heißt Wörter. */
    public void value(int amount, Unit unit) {
        this.unit.setSelectedItem(unit == null ? Unit.WORDS : unit);
        this.amount.setValue(Math.max(0, amount));
        update();
    }

    /** Ein Hinweistext ohne Umrechnung für Tests. */
    String equalsText() {
        return equals.getText();
    }
}
