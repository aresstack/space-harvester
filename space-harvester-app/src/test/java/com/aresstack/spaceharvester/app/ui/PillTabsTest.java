package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.controls.WrapLayout;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/**
 * Codex zu #566: Mit Aktionen rechts in der Reiterzeile blieb bei schmalem Fenster (Explorer offen, 650 px) nur Platz
 * für eine Pille; die übrigen brachen um und wurden abgeschnitten. Die Zeile wächst jetzt mit.
 */
class PillTabsTest {

    @Test
    void wrappedTabsStayVisibleNextToTheActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PillTabs tabs = new PillTabs();
            tabs.tab("Analysen", new JPanel()).tab("Analyse", new JPanel()).tab("Originaldaten", new JPanel());
            tabs.actions(Comic.button("Aktualität prüfen", null), Comic.button("Original speichern …", null),
                    Comic.button("Neu berechnen", null));
            tabs.setSize(650, 400);
            tabs.addNotify();
            tabs.validate();
            JPanel bar = SourceFormLayoutTest.find(tabs, JPanel.class).stream()
                    .filter(panel -> panel.getLayout() instanceof WrapLayout).findFirst().orElseThrow();
            // Das erste Layout kannte die Breite der Leiste noch nicht; sie verlangt (revalidate) ein zweites, das
            // Swing im Fenster nachholt – hier von Hand, ohne Fenster.
            bar.invalidate();
            tabs.validate();

            int lowest = 0;
            for (java.awt.Component pill : bar.getComponents()) {
                lowest = Math.max(lowest, pill.getY() + pill.getHeight());
            }
            assertThat(lowest).as("die unterste Pille endet innerhalb der Leiste").isLessThanOrEqualTo(bar.getHeight());
            assertThat(bar.getHeight()).as("mehr als eine Zeile").isGreaterThan(40);
        });
    }

    @Test
    void aSecondClickOnTheShownTabGoesToItsReceiverAndOtherClicksSelect() throws Exception {
        // Angelo 27.11.2026: Der zweite Klick auf den gewählten, dunkel gezeigten Reiter wählt ihn ab (der Besitzer
        // entscheidet, was dann kommt). Zeigt die Leiste ihre Auswahl nicht, wählt der Klick den Reiter wieder.
        SwingUtilities.invokeAndWait(() -> {
            PillTabs tabs = new PillTabs();
            tabs.tab("Eins", new JPanel()).tab("Zwei", new JPanel());
            java.util.List<String> events = new java.util.ArrayList<>();
            tabs.alsoOnClick(name -> events.add("klick " + name));
            tabs.onAgain(name -> events.add("nochmal " + name));
            java.util.List<javax.swing.AbstractButton> buttons = SourceFormLayoutTest.find(tabs,
                    javax.swing.AbstractButton.class);

            buttons.get(1).doClick();
            buttons.get(1).doClick();
            tabs.select("Eins");
            tabs.showSelection(false);
            buttons.get(0).doClick();

            assertThat(events).containsExactly("klick Zwei", "nochmal Zwei", "klick Eins");
            assertThat(tabs.selected()).isEqualTo("Eins");
        });
    }
}
