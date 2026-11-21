package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import org.junit.jupiter.api.Test;

import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/** Das Ablage-Feld der Reiterzeile: geschlossen knapp, Liste und Tooltip mit vollen Namen (P1: 960 px einzeilig). */
class AnalyzerStoreFieldTest {

    @Test
    void closedItShowsTheCompactNameAndTheListAndTooltipTheFullOnes() throws Exception {
        AtomicReference<List<String>> seen = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            AnalyzerStoreField field = new AnalyzerStoreField();
            List<String> out = new ArrayList<>();
            List<AnalyzerChoice> chosen = new ArrayList<>();
            field.onChange(chosen::add);
            field.show(AnalyzerChoice.MODEL);
            JList<String> list = new JList<>();
            out.add(text(field.getRenderer().getListCellRendererComponent(list, field.getItemAt(1), -1, false, false)));
            out.add(text(field.getRenderer().getListCellRendererComponent(list, field.getItemAt(1), 1, false, false)));
            out.add(field.getToolTipText());
            out.add(String.valueOf(chosen.size()));
            field.setSelectedIndex(0);
            out.add(text(field.getRenderer().getListCellRendererComponent(list, field.getItemAt(0), -1, false, false)));
            out.add(field.getToolTipText());
            out.add(String.valueOf(chosen));
            seen.set(out);
        });

        assertThat(seen.get()).containsExactly("KI", "KI (Erprobung)",
                "Sprachmodell (KI, Erprobung): " + AnalyzerNames.STORE_TOOLTIP, "0", "Parser",
                "MaltParser: " + AnalyzerNames.STORE_TOOLTIP, "[PARSER]");
    }

    private static String text(java.awt.Component component) {
        return component instanceof JLabel label ? label.getText() : String.valueOf(component);
    }
}
