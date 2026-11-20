package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: In der Transkriptansicht stehen neben dem Mikrofon „Korrigieren“, „Löschen“ (Transkript samt
 * Korrekturen) und der Kopierknopf der übrigen Ansichten; die ersten beiden nur mit gespeichertem Transkript.
 */
final class VideoTranscriptActionsTest {

    @Test
    void correctDeleteAndCopyWorkOnTheStoredTranscript() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            List<String> clicked = new ArrayList<>();
            panel.onCorrect(() -> clicked.add("korrigieren"));
            panel.onDelete(() -> clicked.add("löschen"));
            List<JButton> actions = panel.transcriptActions();
            assertThat(actions).as("ohne Transkript").noneMatch(JButton::isEnabled);
            assertThat(panel.copyText()).isNull();

            panel.showStored(source, "[00:00:00 – 00:00:05] Zabak");
            panel.showVersions(source, List.of(
                    new VideoTranscriptionPanel.Version("Original", "[00:00:00 – 00:00:05] Zabak"),
                    new VideoTranscriptionPanel.Version("Korrektur gemma4:12b", "[00:00:00 – 00:00:05] ZahlBuch")));
            assertThat(actions).allMatch(JButton::isEnabled);
            assertThat(actions).extracting(JButton::getToolTipText).containsExactly(
                    VideoTranscriptionPanel.CORRECT_TIP, VideoTranscriptionPanel.DELETE_TIP,
                    com.aresstack.spaceharvester.app.ui.controls.CopyButton.TOOLTIP);
            panel.versionButtons().get(1).doClick();
            assertThat(panel.copyText()).as("kopiert die gezeigte Fassung").isEqualTo("[00:00:00 – 00:00:05] ZahlBuch");
            actions.get(0).doClick();
            actions.get(1).doClick();
            assertThat(clicked).containsExactly("korrigieren", "löschen");

            panel.cleared(source);
            assertThat(actions).as("nach dem Löschen").noneMatch(JButton::isEnabled);
            assertThat(panel.versionButtons()).isEmpty();
            assertThat(panel.toggle().getToolTipText()).isEqualTo(VideoTranscriptionPanel.START_TIP);
        });
    }
}
