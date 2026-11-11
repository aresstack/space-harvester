package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Component;
import java.util.Arrays;
import java.util.List;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;

/**
 * Neu füllen baut nichts neu (Angelo 11.11.2026): Die Zeilen behalten ihre Komponenten, ein umbrechender Wert behält
 * seine Breite und damit seine Höhe; der Detailbereich der Aufträge zittert nicht mehr bei jeder Aktualisierung.
 */
class FactsTest {

    private static final String LONG = "Angehalten – Verbindung unterbrochen, ein Text, der umbrechen muss";

    @Test
    void refillingKeepsTheComponentsAndTheirHeight() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Facts facts = new Facts();
            JPanel frame = new JPanel(new java.awt.BorderLayout());
            frame.add(facts);
            facts.add("Zustand", LONG).add("Quelle", "wiki");
            frame.setSize(220, 400);
            layout(frame);
            List<Component> before = Arrays.asList(facts.getComponents());
            JTextComponent state = values(facts).get(0);
            int height = state.getHeight();
            assertThat(height).as("zweizeilig").isGreaterThan(state.getFont().getSize() * 2);

            facts.reset();
            facts.add("Zustand", LONG).add("Quelle", "wiki");
            layout(frame);

            assertThat(Arrays.asList(facts.getComponents())).containsExactlyElementsOf(before);
            assertThat(values(facts).get(0)).isSameAs(state);
            assertThat(state.getHeight()).as("sofort richtig umbrochen").isEqualTo(height);
        });
    }

    @Test
    void rowsThatAreNotFilledAgainDisappear() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Facts facts = new Facts();
            facts.add("A", "1").add("B", "2").add("C", "3");
            facts.reset();
            facts.add("A", "neu");
            assertThat(values(facts)).extracting(JTextComponent::getText).containsExactly("neu");
            facts.reset();
            facts.add("A", (String) null).add("B", "x").add("C", "y").add("D", "z");
            assertThat(values(facts)).extracting(JTextComponent::getText).containsExactly("–", "x", "y", "z");
        });
    }

    private static List<JTextComponent> values(Facts facts) {
        return Arrays.stream(facts.getComponents()).filter(Component::isVisible)
                .filter(JTextComponent.class::isInstance).map(JTextComponent.class::cast).toList();
    }

    private static void layout(java.awt.Container root) {
        for (int i = 0; i < 3; i++) {
            root.doLayout();
            for (Component c : root.getComponents()) {
                if (c instanceof java.awt.Container child) {
                    child.doLayout();
                }
            }
        }
    }
}
