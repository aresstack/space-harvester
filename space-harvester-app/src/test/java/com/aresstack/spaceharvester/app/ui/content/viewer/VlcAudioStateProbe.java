package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

/**
 * Manual probe, not a regression test: plays a local video without picture and prints what VLC reports about mute
 * and volume over time, and whether unmuting takes effect. Runs only with {@code SPACEHARVESTER_PROBE_VLC_AUDIO}
 * pointing at the video.
 */
class VlcAudioStateProbe {

    @Test
    void probe() throws Exception {
        String file = System.getenv("SPACEHARVESTER_PROBE_VLC_AUDIO");
        if (file == null) {
            return;
        }
        System.out.println("PROBE vlc " + VlcRuntimeLocator.configure());
        MediaPlayerFactory factory = new MediaPlayerFactory("--quiet", "--no-video");
        MediaPlayer player = factory.mediaPlayers().newMediaPlayer();
        long start = System.currentTimeMillis();
        player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
            @Override
            public void playing(MediaPlayer mediaPlayer) {
                System.out.println("PROBE event playing after " + (System.currentTimeMillis() - start) + " ms");
            }

            @Override
            public void muted(MediaPlayer mediaPlayer, boolean muted) {
                System.out.println("PROBE event muted=" + muted + " after " + (System.currentTimeMillis() - start)
                        + " ms");
            }

            @Override
            public void volumeChanged(MediaPlayer mediaPlayer, float volume) {
                System.out.println("PROBE event volume=" + volume + " after " + (System.currentTimeMillis() - start)
                        + " ms");
            }

            @Override
            public void audioDeviceChanged(MediaPlayer mediaPlayer, String audioDevice) {
                System.out.println("PROBE event device=" + audioDevice);
            }
        });
        System.out.println("PROBE before play: isMute=" + player.audio().isMute() + " volume=" + player.audio().volume());
        player.media().play(file);
        for (int i = 0; i < 8; i++) {
            Thread.sleep(500);
            System.out.println("PROBE t=" + (System.currentTimeMillis() - start) + " isMute=" + player.audio().isMute()
                    + " volume=" + player.audio().volume());
        }
        player.audio().setMute(false);
        System.out.println("PROBE setMute(false) called");
        player.audio().setVolume(100);
        Thread.sleep(1000);
        System.out.println("PROBE after unmute: isMute=" + player.audio().isMute() + " volume="
                + player.audio().volume());
        player.controls().stop();
        player.release();
        factory.release();
    }
}
