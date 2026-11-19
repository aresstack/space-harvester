package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.assets.Assets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class VideoFrameGrabberTest {

    @Test
    void everySlideBeginsAtItsChangeAndTheFirstAtZero() {
        assertThat(VideoFrameGrabber.slides(List.of(12_000L, 40_000L), 10)).containsExactly(0L, 12_000L, 40_000L);
        assertThat(VideoFrameGrabber.slides(List.of(12_000L, 40_000L), 2)).containsExactly(0L, 12_000L);
        assertThat(VideoFrameGrabber.slides(List.of(), 10)).containsExactly(0L);
    }

    /** Local check against a real BSCW recording downloaded by the frame-grab probe; skipped elsewhere. */
    @Test
    void grabsTheSlidesOfARealRecordingWithoutAWindow() throws Exception {
        Path video = Path.of("..", "build", "video-debug", "video-2.mp4");
        Assumptions.assumeTrue(Files.isRegularFile(video) && VlcRuntimeLocator.configure());
        long size = Files.size(video);
        AtomicInteger reads = new AtomicInteger();
        MediaStreamBridge.ChunkSource reader = (offset, count) -> {
            reads.incrementAndGet();
            try (RandomAccessFile file = new RandomAccessFile(video.toFile(), "r")) {
                byte[] data = new byte[(int) Math.min(count, size - offset)];
                file.seek(offset);
                file.readFully(data);
                return data;
            }
        };
        AtomicInteger progress = new AtomicInteger();

        List<Assets.VideoFrame> frames = VideoFrameGrabber.grab(reader, size, "video/mp4", 6,
                (done, total) -> progress.set(done), () -> false);

        Files.createDirectories(video.resolveSibling("grabbed"));
        for (Assets.VideoFrame frame : frames) {
            Files.write(video.resolveSibling("grabbed").resolve(frame.millis() + ".png"), frame.png());
        }
        assertThat(frames).hasSize(6);
        assertThat(progress).hasValue(6);
        assertThat(frames).extracting(Assets.VideoFrame::millis).as("named after the slide, grabbed 2 s later")
                .startsWith(0L);
        assertThat(frames.stream().map(frame -> Assets.sha256(frame.png())).distinct()).as("every slide its own frame")
                .hasSize(6);
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(frames.get(0).png()));
        assertThat(first.getWidth()).isEqualTo(1920);
        assertThat(first.getHeight()).as("the recording is 1920 x 1090").isEqualTo(1090);
    }
}
