package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** AT, D6a: Statuszeilen der Reiter, die aus der Aussagen-Ablage lesen, nennen deren Analysator. */
class StoreStatusLineTest {

    @Test
    void readersOfTheStoreNameTheAnalyzerAndOthersStayAsTheyAre() {
        assertThat(PageDetailPanel.withStore("Graph", "50 Knoten, 51 Kanten", AnalyzerChoice.MODEL))
                .isEqualTo("50 Knoten, 51 Kanten · Ablage: " + AnalyzerNames.MODEL);
        assertThat(PageDetailPanel.withStore("Aussagen", "12 Aussagen", AnalyzerChoice.PARSER))
                .isEqualTo("12 Aussagen · Ablage: MaltParser");
        assertThat(PageDetailPanel.withStore(PageDetailPanel.STAND, "3 überholt", AnalyzerChoice.MODEL))
                .endsWith("· Ablage: " + AnalyzerNames.MODEL);
        assertThat(PageDetailPanel.withStore(PageDetailPanel.OVERVIEW_GLOSSARY, "8 Begriffe", AnalyzerChoice.PARSER))
                .endsWith("· Ablage: MaltParser");
        assertThat(PageDetailPanel.withStore("Graph", " ", AnalyzerChoice.MODEL)).isEqualTo(" ");
        assertThat(PageDetailPanel.withStore("Bilder", "4 Bilder", AnalyzerChoice.MODEL)).isEqualTo("4 Bilder");
    }
}
