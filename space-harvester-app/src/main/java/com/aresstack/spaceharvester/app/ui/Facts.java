package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Zweispaltige Liste „Bezeichnung – Wert“. Werte sind markierbar (zum Kopieren von IDs und Hashes), aber nicht
 * bearbeitbar. Eine Zeile kann rechts einen kleinen Knopf tragen ({@link #add(String, String, Color, JComponent)}),
 * etwa zum Löschen des Werts, auf den sie zeigt.
 */
public final class Facts extends JPanel {

    /** Eine Zeile; bleibt über {@link #reset()} hinweg bestehen und bekommt beim nächsten Füllen nur neue Werte. */
    private static final class Row {
        final JLabel name;
        final com.aresstack.spaceharvester.app.ui.controls.WrappedText text;
        JComponent action;

        Row(JLabel name, com.aresstack.spaceharvester.app.ui.controls.WrappedText text) {
            this.name = name;
            this.text = text;
        }
    }

    private final java.util.List<Row> rows = new java.util.ArrayList<>();
    private int row;

    public Facts() {
        super(new GridBagLayout());
        setOpaque(false);
    }

    public Facts add(String label, String value) {
        return add(label, value, Comic.INK);
    }

    public Facts add(String label, String value, Color color) {
        return add(label, value, color, null);
    }

    /** @param action ein Knopf rechts neben dem Wert, z. B. ein {@link IconButton}; {@code null}: keiner */
    public Facts add(String label, String value, Color color, JComponent action) {
        return add(label, value, color, action, null);
    }

    /**
     * Füllt die nächste Zeile. Eine schon vorhandene Zeile behält ihre Komponenten und bekommt nur neuen Text (Angelo
     * 11.11.2026): Ein neu gebautes umbrechendes Textfeld kennt seine Breite noch nicht, bricht erst einzeilig und
     * nach dem Auslegen zweizeilig um, und der Detailbereich zitterte bei jeder Aktualisierung.
     *
     * @param tooltip Erklärung zum Wert; {@code null}: der Wert selbst (für abgeschnittene Pfade)
     */
    public Facts add(String label, String value, Color color, JComponent action, String tooltip) {
        String shown = value == null || value.isEmpty() ? "–" : value;
        String tip = tooltip != null ? tooltip : value == null || value.isEmpty() ? null : value;
        Row line = row < rows.size() ? rows.get(row) : create(label);
        if (!line.name.getText().equals(label)) {
            line.name.setText(label);
        }
        if (!line.text.getText().equals(shown)) {
            line.text.setText(shown);
        }
        if (!color.equals(line.text.getForeground())) {
            line.text.setForeground(color);
        }
        line.text.setToolTipText(tip);
        if (line.action != action) {
            if (line.action != null) {
                remove(line.action);
            }
            line.action = action;
            if (action != null) {
                GridBagConstraints trailing = new GridBagConstraints();
                trailing.gridx = 2;
                trailing.gridy = row;
                trailing.anchor = GridBagConstraints.NORTHEAST;
                trailing.insets = new Insets(0, 6, 0, 0);
                super.add(action, trailing);
            }
        }
        line.name.setVisible(true);
        line.text.setVisible(true);
        if (line.action != null) {
            line.action.setVisible(true);
        }
        row++;
        return this;
    }

    private Row create(String label) {
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = rows.size();
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(2, 0, 2, 14);
        JLabel name = new JLabel(label);
        name.setFont(Comic.semiBold(12.5f));
        name.setForeground(Comic.TEXT);
        super.add(name, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = rows.size();
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.NORTHWEST;
        right.insets = new Insets(2, 0, 2, 0);
        // Umbrechend statt abgeschnitten: lange Pfade und Adressen bleiben ganz lesbar (Angelo, 09.11.).
        com.aresstack.spaceharvester.app.ui.controls.WrappedText text = Comic.wrapped("", Comic.INK, 12.5f);
        text.setFocusable(true); // per Tab erreichbar, markieren und mit Strg+C kopieren wie das Textfeld zuvor
        super.add(text, right);
        Row line = new Row(name, text);
        rows.add(line);
        return line;
    }

    /**
     * Leert die Liste für neues Füllen: Die Zeilen bleiben bestehen und werden nur ausgeblendet; was {@link #add}
     * danach nicht wieder füllt, bleibt unsichtbar und nimmt keinen Platz ein.
     */
    public void reset() {
        for (Row line : rows) {
            line.name.setVisible(false);
            line.text.setVisible(false);
            if (line.action != null) {
                line.action.setVisible(false);
            }
        }
        row = 0;
        revalidate();
        repaint();
    }
}
