package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

final class VideoTrackModeTest {

    @Test
    void disablesVideoWithoutAlteringAudioOrPlaybackStateAndRestoresTheSelectedTrack() {
        FakeVideoTracks tracks = new FakeVideoTracks(7, Arrays.asList(7, 9));
        VideoTrackMode mode = new VideoTrackMode(tracks);

        assertThat(mode.apply(true)).isTrue();
        assertThat(tracks.selectedTrack()).isEqualTo(-1);
        assertThat(tracks.selectionCount).isEqualTo(1);

        assertThat(mode.apply(true)).isTrue();
        assertThat(tracks.selectionCount).isEqualTo(1);

        assertThat(mode.apply(false)).isTrue();
        assertThat(tracks.selectedTrack()).isEqualTo(7);
        assertThat(tracks.selectionCount).isEqualTo(2);
    }

    @Test
    void restoresFirstAvailableTrackWhenThePreviousTrackDisappears() {
        FakeVideoTracks tracks = new FakeVideoTracks(7, Arrays.asList(7, 9));
        VideoTrackMode mode = new VideoTrackMode(tracks);
        assertThat(mode.apply(true)).isTrue();

        tracks.available = Collections.singletonList(9);

        assertThat(mode.apply(false)).isTrue();
        assertThat(tracks.selectedTrack()).isEqualTo(9);
    }

    @Test
    void keepsAudioOnlyModeUnchangedWhenDisablingVideoFails() {
        FakeVideoTracks tracks = new FakeVideoTracks(7, Collections.singletonList(7));
        tracks.allowChange = false;
        VideoTrackMode mode = new VideoTrackMode(tracks);

        assertThat(mode.apply(true)).isFalse();
        assertThat(tracks.selectedTrack()).isEqualTo(7);
    }

    @Test
    void reportsThatVideoCannotBeRestoredWhenNoVideoTrackExists() {
        FakeVideoTracks tracks = new FakeVideoTracks(-1, Collections.emptyList());

        assertThat(new VideoTrackMode(tracks).apply(false)).isFalse();
    }

    private static final class FakeVideoTracks implements VideoTrackMode.VideoTracks {
        private int selected;
        private List<Integer> available;
        private int selectionCount;
        private boolean allowChange = true;

        private FakeVideoTracks(int selected, List<Integer> available) {
            this.selected = selected;
            this.available = available;
        }

        @Override
        public int selectedTrack() {
            return selected;
        }

        @Override
        public List<Integer> availableTracks() {
            return available;
        }

        @Override
        public boolean selectTrack(int trackId) {
            selectionCount++;
            if (!allowChange) {
                return false;
            }
            selected = trackId;
            return true;
        }
    }
}
