package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.PillTabs;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AnalysisTabRowTest {

    @Test
    void oneRowShowsTheTabsOfWhicheverLevelIsShownAndSelectsInIt() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, Icon> icons = new LinkedHashMap<>();
            for (String name : new String[] {"Absätze", "Statistik", "Aussagen", "Zuerst lesen", "Graph"}) {
                icons.put(name, AnalysisTabIcons.terms());
            }
            AnalysisTabRow row = new AnalysisTabRow(icons);
            PillTabs page = new PillTabs().tab("Absätze", new JPanel()).tab("Statistik", new JPanel())
                    .tab("Aussagen", new JPanel()).tab("Graph", new JPanel());
            PillTabs area = new PillTabs().tab("Statistik", new JPanel()).tab("Aussagen", new JPanel())
                    .tab("Zuerst lesen", new JPanel()).tab("Graph", new JPanel());
            row.follow(page);
            row.follow(area);

            row.level(null);
            assertThat(row.bar().isVisible()).isFalse();

            // Die Seite: ihre Reiter in der Reihenfolge der Zeile, ihr gewählter Reiter gewählt.
            row.level(page);
            assertThat(row.bar().isVisible()).isTrue();
            assertThat(row.tabs().names()).containsExactly("Absätze", "Statistik", "Aussagen", "Graph");
            assertThat(row.tabs().selected()).isEqualTo("Absätze");
            // Ein Klick in der Zeile wählt in der Ebene.
            row.tabs().button("Aussagen").doClick();
            assertThat(page.selected()).isEqualTo("Aussagen");
            assertThat(area.selected()).as("die andere Ebene bleibt unberührt").isEqualTo("Statistik");

            // Der obere Knoten: andere Reiter, dieselbe Zeile, dieselben Symbole an derselben Stelle.
            row.level(area);
            assertThat(row.tabs().names()).containsExactly("Statistik", "Aussagen", "Zuerst lesen", "Graph");
            assertThat(row.tabs().selected()).isEqualTo("Statistik");
            // Wählt die Ebene selbst (etwa ein Sprung), zieht die Zeile nach.
            area.select("Zuerst lesen");
            assertThat(row.tabs().selected()).isEqualTo("Zuerst lesen");
            // Die verdeckte Ebene ändert die Zeile nicht.
            page.select("Graph");
            assertThat(row.tabs().selected()).isEqualTo("Zuerst lesen");
            row.level(page);
            assertThat(row.tabs().selected()).isEqualTo("Graph");

            // Ein Reiter, den die Zeile nicht kennt (Abschnitt eines Programms), kommt hinten dazu und behält Text.
            page.tab("Kontrollfluss", new JPanel());
            row.refresh();
            assertThat(row.tabs().names()).containsExactly("Absätze", "Statistik", "Aussagen", "Graph",
                    "Kontrollfluss");
            assertThat(row.tabs().button("Kontrollfluss").getText()).isEqualTo("Kontrollfluss");
            assertThat(row.tabs().button("Graph").getText()).isNull();
        });
    }

    /**
     * Derselbe Reiter einer anderen Ebene (Graph der Seite statt Graph des Knotens) ist eine andere Ansicht: Die
     * Zeile meldet es, damit die Fußleiste Suche und Filter der alten Ansicht räumt.
     */
    @Test
    void aChangeOfTheLevelIsReportedEvenWhenTheSameTabStaysSelected() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Map<String, Icon> icons = new LinkedHashMap<>();
            icons.put("Graph", AnalysisTabIcons.terms());
            icons.put("Aussagen", AnalysisTabIcons.terms());
            AnalysisTabRow row = new AnalysisTabRow(icons);
            PillTabs page = new PillTabs().tab("Graph", new JPanel()).tab("Aussagen", new JPanel());
            PillTabs area = new PillTabs().tab("Graph", new JPanel()).tab("Aussagen", new JPanel());
            row.follow(page);
            row.follow(area);
            row.level(page);
            java.util.concurrent.atomic.AtomicInteger changes = new java.util.concurrent.atomic.AtomicInteger();
            row.onChange(changes::incrementAndGet);

            row.level(area);
            assertThat(row.tabs().selected()).isEqualTo("Graph");
            assertThat(changes.get()).as("andere Ebene, derselbe Reiter").isEqualTo(1);
            row.tabs().button("Aussagen").doClick();
            assertThat(changes.get()).as("anderer Reiter").isGreaterThan(1);
        });
    }
}
