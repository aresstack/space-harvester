package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: Läuft „Transkript ziehen“ im Hintergrund, wächst das Transkript in der offenen Ansicht mit; die
 * Ansicht springt dabei nicht, und das Mikrofon startet keinen zweiten Lauf daneben.
 */
final class VideoTranscriptFollowTest {

    private static final String FIRST = "[00:00:00 – 00:00:30] Willkommen.\n\n";
    private static final String SECOND = "[00:00:30 – 00:01:00] Erste Folie.\n\n";

    @Test
    void theOpenViewGrowsWhileTheJobWritesAndDoesNotJump() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);

            panel.followStored(source, FIRST, true);
            assertThat(panel.shownText()).isEqualTo(FIRST);
            assertThat(panel.toggle().isEnabled()).as("kein zweiter Lauf neben dem Auftrag").isFalse();

            panel.followStored(source, FIRST + SECOND, true);
            assertThat(panel.shownText()).isEqualTo(FIRST + SECOND);
            assertThat(panel.transcriptCaret()).as("die Ansicht springt nicht").isZero();

            panel.followStored(source, FIRST + SECOND, false);
            assertThat(panel.toggle().isEnabled()).as("nach dem Auftrag wieder frei").isTrue();

            VideoTranscriptSource other = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.followStored(other, "[00:00:00 – 00:00:05] Anderes Video", true);
            assertThat(panel.shownText()).as("nur das gezeigte Video").isEqualTo(FIRST + SECOND);
        });
    }

    @Test
    void whileACorrectionIsShownTheOriginalStillGrowsForTheWayBack() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            panel.showStored(source, FIRST);
            panel.showVersions(source, java.util.List.of(new VideoTranscriptionPanel.Version("Original", FIRST),
                    new VideoTranscriptionPanel.Version("Korrektur", "[00:00:00 – 00:00:30] Herzlich willkommen.")));
            panel.versionButtons().get(1).doClick();

            panel.followStored(source, FIRST + SECOND, false);
            assertThat(panel.shownText()).as("die Korrektur bleibt stehen")
                    .isEqualTo("[00:00:00 – 00:00:30] Herzlich willkommen.");
            panel.versionButtons().get(0).doClick();
            assertThat(panel.shownText()).isEqualTo(FIRST + SECOND);
        });
    }
}
