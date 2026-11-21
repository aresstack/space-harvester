package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode;
import java.util.ArrayList;
import java.util.List;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Der Abschnitt „Aus Unterseiten“ (Fahrplan P3): Herkunft ganz rechts, Klick öffnet Unterseite oder Ziel. */
class SubpageSectionTest {

    @Test
    void theOriginIsTheLastColumnAndOpensTheSubpage() throws Exception {
        List<String> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            SubpageSection section = new SubpageSection("Bilder", 5, "Bild", "Beschreibung");
            section.onOpen(opened::add);
            assertThat(section.shown()).as("Standard aus").isFalse();
            section.loading();
            assertThat(section.shown()).isTrue();
            PageNode child = new PageNode("11", "Zahlungen", "DEV", null);
            PageNode second = new PageNode("12", "Belege", "DEV", null);
            PageNode third = new PageNode("13", "Kasse", "DEV", null);
            section.rows(List.of(new SubpageSection.Row(List.of("Kasse.png", "Ein Kassenbon"), child, List.of(), "11"),
                    new SubpageSection.Row(List.of("ZV", "Zahlungsverkehr"), child, List.of(second, third), null)),
                    true);
            assertThat(section.count()).isEqualTo(2);
            assertThat(section.table().getColumnName(2)).isEqualTo(SubpageSection.ORIGIN);
            // UX: Der Link nennt nur die Unterseite; „+2“ steht dahinter als gedämpfter Chip, Klick zeigt die übrigen.
            assertThat(section.table().getValueAt(1, 2)).isEqualTo("Zahlungen");
            assertThat(section.others(1)).containsExactly("Belege", "Kasse");
            assertThat(section.othersMenu(1).getComponentCount()).isEqualTo(2);
            assertThat(SubpageSection.moreTip(1)).isEqualTo("auch auf 1 weiteren Unterseite");
            assertThat(SubpageSection.moreTip(2)).isEqualTo("auch auf 2 weiteren Unterseiten");
            assertThat(section.copyText()).isEqualTo(TermsTab.BELOW + "\nKasse.png\tEin Kassenbon\tZahlungen\n"
                    + "ZV\tZahlungsverkehr\tZahlungen (+2)");
            section.open(1, 0);
            section.open(0, 2);
            section.openOther(1, 1);
            section.off();
            assertThat(section.shown()).isFalse();
        });
        assertThat(opened).containsExactly("11", "11", "13");
    }
}
