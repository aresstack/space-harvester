package com.aresstack.spaceharvester.app.ui;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
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
        GridBagConstraints left = new GridBagConstraints();
        left.gridx = 0;
        left.gridy = row;
        left.anchor = GridBagConstraints.NORTHWEST;
        left.insets = new Insets(2, 0, 2, 14);
        JLabel name = new JLabel(label);
        name.setFont(Comic.semiBold(12.5f));
        name.setForeground(Comic.TEXT);
        super.add(name, left);

        GridBagConstraints right = new GridBagConstraints();
        right.gridx = 1;
        right.gridy = row;
        right.weightx = 1;
        right.fill = GridBagConstraints.HORIZONTAL;
        right.anchor = GridBagConstraints.NORTHWEST;
        right.insets = new Insets(2, 0, 2, 0);
        JTextField text = new JTextField(value == null || value.isEmpty() ? "–" : value);
        text.setEditable(false);
        text.setBorder(null);
        text.setOpaque(false);
        text.setFont(Comic.regular(12.5f));
        text.setForeground(color);
        text.setCaretPosition(0);
        super.add(text, right);
        if (action != null) {
            GridBagConstraints trailing = new GridBagConstraints();
            trailing.gridx = 2;
            trailing.gridy = row;
            trailing.anchor = GridBagConstraints.NORTHEAST;
            trailing.insets = new Insets(0, 6, 0, 0);
            super.add(action, trailing);
        }
        row++;
        return this;
    }

    public void reset() {
        removeAll();
        row = 0;
        revalidate();
        repaint();
    }
}
