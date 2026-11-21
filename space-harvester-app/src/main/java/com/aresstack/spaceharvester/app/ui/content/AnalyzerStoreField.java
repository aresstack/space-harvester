package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;

import javax.swing.JComboBox;
import java.util.function.Consumer;

/**
 * Kompaktes Auswahlfeld der Ablage oben rechts in der Reiterzeile von Aussagen und Graph (Fahrplan A3,
 * UX-Vorgabe): zeigt, aus welcher Ablage (MaltParser oder KI) alle Ansichten lesen, und ändert dieselbe Einstellung wie
 * Einstellungen › Modelle, „Aussagenanalyse“. Setzen von außen meldet nichts. Geschlossen zeigt es nur „Parser“ oder
 * „KI“, damit die sieben Unterreiter bei 960 px in einer Zeile bleiben (P1); Liste und Tooltip nennen die vollen Namen.
 */
public final class AnalyzerStoreField extends JComboBox<String> {

    private Consumer<AnalyzerChoice> onChange = choice -> { };
    private boolean updating;

    public AnalyzerStoreField() {
        addItem(AnalyzerNames.PARSER_SHORT);
        addItem(AnalyzerNames.MODEL_SHORT);
        setFont(Comic.regular(12f));
        ComicComboBoxUI.install(this);
        getAccessibleContext().setAccessibleName("Ablage der Aussagenanalyse");
        javax.swing.ListCellRenderer<? super String> look = getRenderer();
        setRenderer((list, value, index, selected, focus) -> look.getListCellRendererComponent(list,
                index < 0 ? AnalyzerNames.compactName(AnalyzerNames.of(value)) : value, index, selected, focus));
        setPrototypeDisplayValue(AnalyzerNames.PARSER_SHORT);
        tooltip();
        addActionListener(e -> {
            tooltip();
            if (!updating) {
                onChange.accept(choice());
            }
        });
    }

    /** Wer auf eine Wahl des Benutzers hört. */
    public void onChange(Consumer<AnalyzerChoice> listener) {
        this.onChange = listener == null ? choice -> { } : listener;
    }

    /** Zeigt die eingestellte Ablage, ohne {@link #onChange} auszulösen. */
    public void show(AnalyzerChoice choice) {
        updating = true;
        try {
            setSelectedItem(AnalyzerNames.shortName(choice == null ? AnalyzerChoice.PARSER : choice));
        } finally {
            updating = false;
        }
    }

    /** Der Tooltip nennt den vollen Namen der gezeigten Ablage, das geschlossene Feld nur den knappen. */
    private void tooltip() {
        setToolTipText(AnalyzerNames.name(choice()) + ": " + AnalyzerNames.STORE_TOOLTIP);
    }

    /** Die gezeigte Ablage. */
    public AnalyzerChoice choice() {
        return AnalyzerNames.of(getSelectedItem());
    }
}
