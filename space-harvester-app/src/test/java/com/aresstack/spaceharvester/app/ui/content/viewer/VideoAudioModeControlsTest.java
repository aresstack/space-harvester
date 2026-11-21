package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

import static org.assertj.core.api.Assertions.assertThat;

final class VideoAudioModeControlsTest {

    @Test
    void audioButtonExplainsBothModesAndCanBeSwitchedRepeatedly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            VideoTransportBar bar = new VideoTransportBar(position -> { });

            bar.setAudioOnly(false);
            assertThat(bar.audioOnly().getText()).isEqualTo("Nur Audio");
            assertThat(bar.audioOnly().getIcon()).isInstanceOf(VideoAudioModeIcon.class);
            assertThat(bar.audioOnly().getAccessibleContext().getAccessibleName()).isEqualTo("Nur Audio");

            bar.setAudioOnly(true);
            assertThat(bar.audioOnly().getText()).isEqualTo("Video anzeigen");
            assertThat(bar.audioOnly().getToolTipText()).contains("Videobild");
            assertThat(bar.audioOnly().getAccessibleContext().getAccessibleName()).isEqualTo("Video anzeigen");

            bar.setAudioOnly(false);
            assertThat(bar.audioOnly().getText()).isEqualTo("Nur Audio");
        });
    }

    @Test
    void switchingModesStartsTheNewStreamAtTheSavedPosition() {
        org.assertj.core.api.Assertions.assertThat(VideoViewerPanel.startAt(0)).isEmpty();
        org.assertj.core.api.Assertions.assertThat(VideoViewerPanel.startAt(12_651))
                .containsExactly(":start-time=12.651");
    }
}
