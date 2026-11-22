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

    /** Die Ablage hinter einer gelben Meldung bleibt grau (derselbe Weg im Hauptfenster und im Bildschirmfoto-Test). */
    @Test
    void theStoreBehindAYellowNoteStaysPlain() {
        com.aresstack.spaceharvester.app.ui.controls.StatusNote note = new com.aresstack.spaceharvester.app.ui
                .controls.StatusNote("Wissensgraph: nichts zu rechnen", com.aresstack.spaceharvester.app.ui.controls
                .StatusNote.Tone.WARNING, null, null);

        var shown = PageDetailPanel.withStore("Graph", note, AnalyzerChoice.MODEL);

        assertThat(shown.text()).isEqualTo("Wissensgraph: nichts zu rechnen · Ablage: " + AnalyzerNames.MODEL);
        assertThat(shown.toneOf("Ablage: " + AnalyzerNames.MODEL))
                .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.PLAIN);
        assertThat(shown.toneOf("Wissensgraph: nichts zu rechnen"))
                .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.WARNING);
    }
}
