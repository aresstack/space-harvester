package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.transcription.WhisperModel;
import com.aresstack.spaceharvester.app.transcription.WhisperModelLocation;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Angelo 20.11.2026: Das Original-Transkript bekommt eine Auswahl je Whisper-Modell. Ein Modell mit Transkript zeigt
 * und wählt es, eines ohne zeigt die leere Ansicht, und das Mikrofon transkribiert mit dem gewählten Modell.
 */
final class VideoTranscriptOriginalsTest {

    @Test
    void theChoiceListsEveryModelMarksStoredOnesAndLoadsOnlyThose() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            List<String> loaded = new ArrayList<>();
            panel.onOriginal(loaded::add);
            assertThat(panel.whisper()).as("ohne Transkript das eingestellte Modell")
                    .isEqualTo(WhisperModelLocation.selected().id());

            panel.showOriginals(source, List.of("", "small"), "small");
            panel.showStored(source, "[00:00:00 – 00:00:05] Zahlbuch");
            assertThat(panel.originalChoice().getItemCount()).isEqualTo(WhisperModel.values().length + 1);
            assertThat(panel.originalChoice().getItemAt(0)).hasToString("Älteres Transkript ✓");
            assertThat(panel.originalChoice().getSelectedItem()).hasToString("Whisper Small ✓");
            assertThat(panel.transcriptionModel()).isEqualTo(WhisperModel.SMALL);
            assertThat(loaded).as("Füllen lädt nichts").isEmpty();

            panel.originalChoice().setSelectedItem(new VideoTranscriptionPanel.Original("", true));
            assertThat(loaded).containsExactly("");
            assertThat(panel.transcriptionModel()).as("älteres: das eingestellte Modell")
                    .isEqualTo(WhisperModelLocation.selected());

            panel.originalChoice().setSelectedItem(new VideoTranscriptionPanel.Original("large-v3", false));
            assertThat(loaded).as("ohne Transkript nichts zu laden").containsExactly("");
            assertThat(panel.shownText()).isEqualTo(VideoTranscriptionPanel.START_TEXT);
            assertThat(panel.copyText()).isNull();
            assertThat(panel.transcriptionModel()).isEqualTo(WhisperModel.LARGE_V3);
        });
    }

    @Test
    void aBackgroundJobSwitchesTheChoiceToTheOriginalItWrites() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            panel.showOriginals(source, List.of("tiny"), "tiny");
            panel.showStored(source, "[00:00:00 – 00:00:05] Zabak");

            panel.followStored(source, "base", "[00:00:00 – 00:00:05] Zahlbuch", true);

            assertThat(panel.originalChoice().getSelectedItem()).hasToString("Whisper Base ✓");
            assertThat(panel.shownText()).isEqualTo("[00:00:00 – 00:00:05] Zahlbuch");
            assertThat(panel.originalChoice().isEnabled()).as("während der Auftrag schreibt").isFalse();
        });
    }

    @Test
    void whileAnotherOriginalLoadsNothingOfThePreviousOneCanBeContinuedCorrectedOrDeleted() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            panel.onOriginal(whisper -> { });
            panel.showOriginals(source, List.of("tiny", "small"), "tiny");
            panel.showStored(source, "[00:00:00 – 00:00:05] Zabak");
            assertThat(panel.transcriptActions()).allSatisfy(button -> assertThat(button.isEnabled()).isTrue());

            panel.originalChoice().setSelectedItem(new VideoTranscriptionPanel.Original("small", true));

            assertThat(panel.storedLoadingShown()).isTrue();
            assertThat(panel.transcriptActions()).as("Mikrofon, Korrigieren, Löschen warten auf das Original")
                    .allSatisfy(button -> assertThat(button.isEnabled()).isFalse());
            assertThat(panel.microphone().isEnabled()).as("setzt nichts vom vorigen Original fort").isFalse();
            assertThat(panel.copyText()).as("vom vorigen Original bleibt nichts").isNull();

            panel.showStored(source, "[00:00:00 – 00:00:05] Zahlbuch");

            assertThat(panel.transcriptActions()).allSatisfy(button -> assertThat(button.isEnabled()).isTrue());
            assertThat(panel.microphone().isEnabled()).isTrue();
            assertThat(panel.copyText()).isEqualTo("[00:00:00 – 00:00:05] Zahlbuch");
        });
    }

    @Test
    void anOriginalAJobCreatesForTheChosenModelIsMarkedAsStored() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTranscriptionPanel panel = new VideoTranscriptionPanel(null);
            VideoTranscriptSource source = new VideoTranscriptSource(10, (offset, length) -> new byte[0]);
            panel.setVideoSource(source);
            panel.showOriginals(source, List.of(), null);
            String chosen = panel.whisper();

            panel.followStored(source, chosen, "[00:00:00 – 00:00:05] Zahlbuch", false);

            assertThat(((VideoTranscriptionPanel.Original) panel.originalChoice().getSelectedItem()).stored()).isTrue();
        });
    }
}
