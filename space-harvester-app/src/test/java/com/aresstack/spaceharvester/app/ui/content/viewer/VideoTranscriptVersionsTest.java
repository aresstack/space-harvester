package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: Das Transkript füllt den Reiter ohne Hinweiszeile; unten in der Mitte der Leiste stehen das
 * Original und die Korrekturen zum Umschalten, der Knopf „Transkript erzeugen“ ist kein Mikrofon mehr.
 */
final class VideoTranscriptVersionsTest {

    @Test
    void theOriginalAndItsCorrectionsCanBeSwitchedInTheToolbar() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            assertThat(panel.getComponentCount()).as("nur das Transkript, keine Hinweiszeile").isEqualTo(1);
            assertThat(panel.toggle().getIcon()).isInstanceOf(TranscribeIcon.class);

            panel.showStored(source, "[00:00:00 – 00:00:05] Zabak");
            panel.showVersions(source, List.of(
                    new VideoTranscriptionPanel.Version("Original", "[00:00:00 – 00:00:05] Zabak"),
                    new VideoTranscriptionPanel.Version("Korrektur gemma4:12b", "[00:00:00 – 00:00:05] ZahlBuch")));
            assertThat(panel.versionButtons()).extracting(button -> button.getText())
                    .containsExactly("Original", "Korrektur gemma4:12b");
            assertThat(panel.shownText()).isEqualTo("[00:00:00 – 00:00:05] Zabak");

            panel.versionButtons().get(1).doClick();
            assertThat(panel.shownText()).isEqualTo("[00:00:00 – 00:00:05] ZahlBuch");
            panel.versionButtons().get(0).doClick();
            assertThat(panel.shownText()).isEqualTo("[00:00:00 – 00:00:05] Zabak");

            panel.showVersions(source, List.of(new VideoTranscriptionPanel.Version("Original", "x")));
            assertThat(panel.versionButtons()).as("ohne Korrektur keine Leiste").isEmpty();
        });
    }
}
