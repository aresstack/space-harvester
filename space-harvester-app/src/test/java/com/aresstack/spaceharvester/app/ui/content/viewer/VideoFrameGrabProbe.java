package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.sources.MediaByteRange;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Manual probe, not a regression test: download the N-th video bookmark completely (like
 * {@link BscwVideoStreamProbe}, with a copy of the configuration named {@code probe-video.yml}) to
 * {@code build/video-debug/video-N.mp4} and save one VLC snapshot per given position to {@code frames-N/}. Run with a
 * display, {@code -Dspaceharvester.probe.frames=N} and {@code -Dspaceharvester.probe.times=ms,ms,...}; it does nothing
 * without the properties.
 */
class VideoFrameGrabProbe {

    private static final int PART = 256 * 1024;

    @Test
    void grab() throws Exception {
        String which = System.getProperty("spaceharvester.probe.frames");
        String times = System.getProperty("spaceharvester.probe.times");
        if (which == null || times == null) {
            return;
        }
        Path dir = Path.of("..", "build", "video-debug");
        Path video = dir.resolve("video-" + which + ".mp4");
        if (!Files.isRegularFile(video)) {
            download(which, video);
        }
        Path frames = dir.resolve("frames-" + which);
        Files.createDirectories(frames);

        VlcRuntimeLocator.configure();
        MediaPlayerFactory factory = new MediaPlayerFactory("--no-snapshot-preview", "--intf=dummy", "--no-audio");
        EmbeddedMediaPlayerComponent[] player = new EmbeddedMediaPlayerComponent[1];
        JFrame[] frame = new JFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            player[0] = new EmbeddedMediaPlayerComponent(factory, null, null, null, null);
            frame[0] = new JFrame("frame grab");
            frame[0].add(player[0]);
            frame[0].setSize(1280, 760);
            frame[0].setVisible(true);
        });
        var mp = player[0].mediaPlayer();
        mp.media().play(video.toAbsolutePath().toString());
        for (int i = 0; i < 100 && !mp.status().isPlaying(); i++) {
            Thread.sleep(100);
        }
        mp.controls().setPause(true);
        for (String text : times.split(",")) {
            long millis = Long.parseLong(text.strip());
            mp.controls().setTime(millis);
            Thread.sleep(1200);
            Path file = frames.resolve(String.format(Locale.ROOT, "%07d.png", millis));
            boolean saved = mp.snapshots().save(file.toFile(), 0, 0);
            System.out.println("FRAME " + millis + " " + saved + " " + file.toAbsolutePath().normalize());
        }
        SwingUtilities.invokeAndWait(() -> {
            mp.controls().stop();
            player[0].release();
            frame[0].dispose();
        });
        factory.release();
    }

    private static void download(String which, Path video) throws Exception {
        Path home = AppSettingsLoader.defaultHome();
        AppContext context = new AppContext(new AppSettingsLoader(Placeholders.system(), home),
                home.resolve("probe-video.yml"), "probe");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, TimeUnit.SECONDS);
        tools.jackson.databind.JsonNode favorite = null;
        int found = 0;
        for (tools.jackson.databind.JsonNode entry : tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(Files.readString(home.resolve("data").resolve("favorites.json"))).path("favorites")) {
            if (entry.path("title").asText().toLowerCase(Locale.ROOT).endsWith(".mp4")
                    && ++found == Integer.parseInt(which)) {
                favorite = entry;
            }
        }
        String source = favorite.path("sourceId").asText();
        String pageId = favorite.path("pageId").asText();
        SinglePageMetadata metadata = null;
        for (int attempt = 0; attempt < 30 && metadata == null; attempt++) {
            try {
                metadata = context.runtime().workspace().liveMetadata(source, pageId);
            } catch (RuntimeException notYet) {
                Thread.sleep(2000);
            }
        }
        long size = Long.parseLong(metadata.entry().page().attributes().getOrDefault("dav.contentLength", "-1"));
        System.out.println("DOWNLOAD " + metadata.entry().page().title() + " " + size + " bytes");
        MediaByteRange ranges = context.runtime().workspace().openMediaRange(source, pageId);
        Path partial = video.resolveSibling(video.getFileName() + ".part");
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try (RandomAccessFile out = new RandomAccessFile(partial.toFile(), "rw")) {
            out.setLength(size);
            List<Future<?>> parts = new ArrayList<>();
            for (long offset = 0; offset < size; offset += PART) {
                long at = offset;
                int count = (int) Math.min(PART, size - offset);
                parts.add(pool.submit(() -> {
                    byte[] data = ranges.read(at, count);
                    synchronized (out) {
                        out.seek(at);
                        out.write(data);
                    }
                    return null;
                }));
            }
            for (Future<?> part : parts) {
                part.get();
            }
        } finally {
            pool.shutdown();
        }
        Files.move(partial, video);
        context.shutdown();
    }
}
