package com.aresstack.spaceharvester.app.ui.controls;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;

import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.FlowLayout;

/**
 * Ein Hinweis mit Knöpfen darunter, für Stellen, an denen etwas nicht geht und der Benutzer es an Ort und Stelle
 * erlauben kann (Reiter „Bilder“: Quelle nicht freigegeben → „Nur diese Seite einmalig beschreiben“ /
 * „Diese Quelle freigeben“). Der Text bricht um, die Knöpfe sind die flachen Knöpfe der App ({@link Comic#button}),
 * links unter dem Text, in der Reihenfolge, in der sie übergeben werden.
 */
public final class HintActions extends JPanel {

    private final WrappedText text;
    private final JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    public HintActions(String hint, ComposerButton... actions) {
        super(new BorderLayout(0, 8));
        setOpaque(false);
        text = Comic.wrapped(hint, Comic.MUTED, 13f);
        buttons.setOpaque(false);
        for (ComposerButton action : actions) {
            buttons.add(action);
        }
        add(text, BorderLayout.NORTH);
        add(buttons, BorderLayout.CENTER);
    }

    public void text(String hint) {
        text.setText(hint);
    }

    public String text() {
        return text.getText();
    }
}
