package com.aresstack.spaceharvester.app.ui.content.viewer;

import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.TrackDescription;

import java.util.ArrayList;
import java.util.List;

/** Adapt libVLC video-track selection without exposing VLC to the playback-mode policy. */
final class VlcVideoTracks implements VideoTrackMode.VideoTracks {

    private final MediaPlayer player;

    VlcVideoTracks(MediaPlayer player) {
        this.player = player;
    }

    @Override
    public int selectedTrack() {
        return player.video().track();
    }

    @Override
    public List<Integer> availableTracks() {
        List<Integer> available = new ArrayList<>();
        List<TrackDescription> descriptions = player.video().trackDescriptions();
        if (descriptions == null) {
            return available;
        }
        for (TrackDescription track : descriptions) {
            if (track.id() >= 0) {
                available.add(track.id());
            }
        }
        return available;
    }

    @Override
    public boolean selectTrack(int trackId) {
        return player.video().setTrack(trackId) == 0;
    }
}
