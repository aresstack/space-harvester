package com.aresstack.spaceharvester.app.ui;

import javax.swing.JLabel;
import javax.swing.JPanel;
import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;

/**
 * Zweispaltige Liste „Bezeichnung – Wert“. Werte sind markierbar (zum Kopieren von IDs und Hashes), aber nicht
 * bearbeitbar.
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
        // Umbrechend statt abgeschnitten: lange Pfade und Adressen bleiben ganz lesbar (Angelo, 09.11.).
        javax.swing.JTextArea text = Comic.wrapped(value == null || value.isEmpty() ? "–" : value, color, 12.5f);
        text.setToolTipText(value == null || value.isEmpty() ? null : value);
        super.add(text, right);
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
