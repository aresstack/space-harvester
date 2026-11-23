package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/** Formularzeilen der Verbindungsformulare des Command Deck (Terminal, FTP, NDV, E-Akte), überall gleich bündig. */
final class DeckForms {

    private DeckForms() {
    }

    /** Eine Zeile: Bezeichnung links mit fester Breite, Feld rechts über die übrige Breite. */
    static void row(JPanel panel, int y, String label, JComponent field) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = y;
        c.insets = new Insets(3, 0, 3, 10);
        c.anchor = GridBagConstraints.WEST;
        JLabel text = Comic.text(label);
        text.setPreferredSize(new Dimension(110, text.getPreferredSize().height));
        panel.add(text, c);
        c.gridx = 1;
        c.insets = new Insets(3, 0, 3, 0);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        if (!(field instanceof JLabel)) {
            field.setFont(Comic.regular(13f));
        }
        panel.add(field, c);
    }
}
