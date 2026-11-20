package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SlideChangesTest {

    @Test
    void findsLargeFramesBetweenKeyFramesAndMergesBursts() {
        // 5 frames per second for 60 s: tiny still frames, key frames every 30 s, one single change at 12 s and a
        // burst of three large frames from 40 s to 41 s that is one change.
        int frames = 300;
        int[] sizes = new int[frames];
        Arrays.fill(sizes, 140);
        sizes[0] = 60_000;
        sizes[150] = 61_000;
        sizes[60] = 45_000;
        sizes[200] = 30_000;
        sizes[202] = 28_000;
        sizes[205] = 33_000;
        sizes[3] = 40_000; // within the first 1.5 s: the first slide itself
        byte[] moov = moov(sizes, 200, new int[]{1, 151});

        assertThat(SlideChanges.detect(moov)).containsExactly(12_000L, 40_000L);
    }

    @Test
    void returnsNoChangesWithoutAVideoTrack() {
        byte[] audioOnly = box("moov", trak("soun", 1000, new int[]{100, 100}, 200, new int[0]));

        assertThat(SlideChanges.detect(audioOnly)).isEmpty();
    }

    @Test
    void nextAndPreviousBehaveLikeTrackButtons() {
        List<Long> changes = List.of(10_000L, 20_000L, 30_000L);

        assertThat(SlideChanges.next(changes, 0)).isEqualTo(10_000);
        assertThat(SlideChanges.next(changes, 10_000)).isEqualTo(20_000);
        assertThat(SlideChanges.next(changes, 30_000)).isEqualTo(-1);
        assertThat(SlideChanges.previous(changes, 25_000)).isEqualTo(20_000);
        assertThat(SlideChanges.previous(changes, 21_000)).isEqualTo(10_000);
        assertThat(SlideChanges.previous(changes, 5_000)).isZero();
        assertThat(VideoSeekBar.slideAt(changes, 25_000)).isEqualTo(3);
        assertThat(VideoSeekBar.slideAt(changes, 0)).isEqualTo(1);
    }

    /** Local check against a real BSCW recording's index, saved by the live probe; skipped elsewhere. */
    @Test
    void findsTheSlideChangesOfARealRecording() throws Exception {
        Path index = Path.of("..", "build", "video-debug", "moov-1.bin");
        Assumptions.assumeTrue(Files.isRegularFile(index));

        List<Long> changes = SlideChanges.detect(Files.readAllBytes(index));

        // 23 large-frame events minus the first slide itself at 1 s.
        assertThat(changes).hasSize(22);
        assertThat(changes).anyMatch(change -> Math.abs(change - 264_400) < 500);
    }

    private static byte[] moov(int[] videoSizes, int deltaMillis, int[] keyFrames) {
        return box("moov", trak("soun", 1000, new int[]{300, 300, 300}, 1000, new int[0]),
                trak("vide", 1000, videoSizes, deltaMillis, keyFrames));
    }

    private static byte[] trak(String handler, int timescale, int[] sizes, int delta, int[] keyFrames) {
        ByteBuffer mdhd = ByteBuffer.allocate(24);
        mdhd.putInt(0).putInt(0).putInt(0).putInt(timescale).putInt(sizes.length * delta).putInt(0);
        ByteBuffer hdlr = ByteBuffer.allocate(24);
        hdlr.putInt(0).putInt(0).put(handler.getBytes(StandardCharsets.ISO_8859_1));
        ByteBuffer stts = ByteBuffer.allocate(16);
        stts.putInt(0).putInt(1).putInt(sizes.length).putInt(delta);
        ByteBuffer stsz = ByteBuffer.allocate(12 + 4 * sizes.length);
        stsz.putInt(0).putInt(0).putInt(sizes.length);
        for (int size : sizes) {
            stsz.putInt(size);
        }
        ByteBuffer stss = ByteBuffer.allocate(8 + 4 * keyFrames.length);
        stss.putInt(0).putInt(keyFrames.length);
        for (int key : keyFrames) {
            stss.putInt(key);
        }
        byte[] stbl = box("stbl", box("stts", stts.array()), box("stsz", stsz.array()), box("stss", stss.array()));
        return box("trak", box("mdia", box("mdhd", mdhd.array()), box("hdlr", hdlr.array()),
                box("minf", stbl)));
    }

    private static byte[] box(String type, byte[]... children) {
        ByteArrayOutputStream content = new ByteArrayOutputStream();
        for (byte[] child : children) {
            content.writeBytes(child);
        }
        ByteBuffer box = ByteBuffer.allocate(8 + content.size());
        box.putInt(8 + content.size()).put(type.getBytes(StandardCharsets.ISO_8859_1)).put(content.toByteArray());
        return box.array();
    }
}
