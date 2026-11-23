package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.deck.editor.NaturalBlockHighlighter;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.JColorChooser;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * Einstellungen › NDV, wie {@code NdvSettingsPanel} in MainframeMate: die Hintergrundfarben der Natural-Blöcke im
 * Editor. Port und Standard-Bibliothek stehen im Verbindungsformular des NDV-Bereichs, je Server.
 */
final class NdvSettingsPanel {

    static final String HEADING = "Natural-Blockfarben";

    private final SettingDialogs.Rows rows = new SettingDialogs.Rows();

    NdvSettingsPanel() {
        rows.add("DEFINE SUBROUTINE", colorField(NaturalBlockHighlighter.SUBROUTINE_KEY,
                NaturalBlockHighlighter.SUBROUTINE_DEFAULT), "Hintergrund von DEFINE SUBROUTINE bis END-SUBROUTINE.");
        rows.add("DEFINE DATA", colorField(NaturalBlockHighlighter.DEFINE_DATA_KEY,
                NaturalBlockHighlighter.DEFINE_DATA_DEFAULT), "Hintergrund von DEFINE DATA bis END-DEFINE.");
        rows.add("ON ERROR", colorField(NaturalBlockHighlighter.ON_ERROR_KEY, NaturalBlockHighlighter.ON_ERROR_DEFAULT),
                "Hintergrund von ON ERROR bis END-ERROR.");
    }

    JComponent component() {
        return rows;
    }

    private static JComponent colorField(String key, String fallback) {
        JLabel swatch = new JLabel();
        swatch.setOpaque(true);
        swatch.setPreferredSize(new Dimension(56, 22));
        swatch.setBorder(BorderFactory.createLineBorder(Comic.LINE));
        JLabel code = Comic.muted("");
        ComposerButton change = Comic.button("Ändern …", "Farbe wählen");
        ComposerButton reset = Comic.button("Vorgabe", "Farbe wie in MainframeMate (" + fallback + ")");
        Runnable show = () -> {
            Color color = NaturalBlockHighlighter.color(key, fallback);
            swatch.setBackground(color);
            code.setText(NaturalBlockHighlighter.hex(color));
            swatch.getAccessibleContext().setAccessibleName(NaturalBlockHighlighter.hex(color));
        };
        change.addActionListener(e -> {
            Color chosen = JColorChooser.showDialog(change, "Farbe wählen", NaturalBlockHighlighter.color(key, fallback));
            if (chosen != null) {
                NaturalBlockHighlighter.store(key, chosen);
                show.run();
            }
        });
        reset.addActionListener(e -> {
            NaturalBlockHighlighter.store(key, null);
            show.run();
        });
        show.run();
        JPanel row = Comic.row(swatch, code, change, reset);
        row.setOpaque(false);
        return row;
    }
}
