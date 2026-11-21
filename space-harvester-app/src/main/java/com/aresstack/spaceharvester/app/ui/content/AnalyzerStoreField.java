package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComicComboBoxUI;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;

import javax.swing.JComboBox;
import java.util.function.Consumer;

/**
 * Kompaktes Auswahlfeld der Ablage oben rechts in der Reiterzeile von Aussagen, Graph und Beziehungen (Fahrplan A3,
 * UX-Vorgabe): zeigt, aus welcher Ablage (MaltParser oder KI) alle Ansichten lesen, und ändert dieselbe Einstellung wie
 * Einstellungen › Modelle, „Aussagenanalyse“. Setzen von außen meldet nichts.
 */
public final class AnalyzerStoreField extends JComboBox<String> {

    private Consumer<AnalyzerChoice> onChange = choice -> { };
    private boolean updating;

    public AnalyzerStoreField() {
        addItem(AnalyzerNames.PARSER_SHORT);
        addItem(AnalyzerNames.MODEL_SHORT);
        setFont(Comic.regular(12f));
        ComicComboBoxUI.install(this);
        setToolTipText(AnalyzerNames.STORE_TOOLTIP);
        getAccessibleContext().setAccessibleName("Ablage der Aussagenanalyse");
        setPrototypeDisplayValue(AnalyzerNames.MODEL_SHORT);
        addActionListener(e -> {
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

    /** Die gezeigte Ablage. */
    public AnalyzerChoice choice() {
        return AnalyzerNames.of(getSelectedItem());
    }
}
