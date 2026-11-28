package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.ui.controls.OverflowRow;
import java.util.Map;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JPanel;

/**
 * Die Umschalter eines Bereichs unten in seiner Fußleiste (Angelo 28.11.2026: einheitlich, wie die Reiter der
 * Analysen): je Ansicht ein Symbolknopf, der Name im Tooltip, mittig in der Leiste mit der Lupe ({@link FindBar#tools}).
 * Was in der Breite keinen Platz hat, steht hinter dem Menü am Ende der Reihe ({@link OverflowRow}); die Leiste
 * bleibt so hoch wie jede andere. Welche Ansichten es gerade gibt, sagt {@code tabs().showOnly(...)}.
 */
public final class FootTabs {

    private final PillTabs row = new PillTabs();
    private final JComponent bar;

    /** @param icons alle Ansichten in der Reihenfolge der Zeile, je mit ihrem Symbol */
    public FootTabs(Map<String, Icon> icons) {
        icons.keySet().forEach(name -> row.tab(name, new JPanel()));
        row.icons(icons);
        bar = OverflowRow.around(row.detachBar());
    }

    /** Die Zeile selbst: wählen, abfragen, Ansichten ein- und ausblenden. */
    public PillTabs tabs() {
        return row;
    }

    /** Die Reihe der Symbolknöpfe für die Mitte der Fußleiste. */
    public JComponent bar() {
        return bar;
    }
}
