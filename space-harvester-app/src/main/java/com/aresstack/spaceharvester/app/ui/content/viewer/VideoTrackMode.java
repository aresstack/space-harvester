package com.aresstack.spaceharvester.app.ui.content.viewer;

import java.util.List;

/**
 * Select the video decoder independently of playback, timeline, and audio output.
 * Preserve the selected video track so the picture can be restored without restarting media.
 */
final class VideoTrackMode {

    interface VideoTracks {
        int selectedTrack();

        List<Integer> availableTracks();

        boolean selectTrack(int trackId);
    }

    private final VideoTracks tracks;
    private int previousVideoTrack = -1;

    VideoTrackMode(VideoTracks tracks) {
        this.tracks = tracks;
    }

    boolean apply(boolean audioOnly) {
        return audioOnly ? disableVideo() : restoreVideo();
    }

    private boolean disableVideo() {
        int selected = tracks.selectedTrack();
        if (selected < 0) {
            return true;
        }
        if (!tracks.selectTrack(-1)) {
            return false;
        }
        previousVideoTrack = selected;
        return true;
    }

    private boolean restoreVideo() {
        int current = tracks.selectedTrack();
        if (current >= 0) {
            previousVideoTrack = current;
            return true;
        }
        int target = trackToRestore();
        return target >= 0 && tracks.selectTrack(target);
    }

    private int trackToRestore() {
        List<Integer> available = tracks.availableTracks();
        if (available.contains(previousVideoTrack)) {
            return previousVideoTrack;
        }
        for (Integer track : available) {
            if (track != null && track >= 0) {
                return track;
            }
        }
        return -1;
    }
}
