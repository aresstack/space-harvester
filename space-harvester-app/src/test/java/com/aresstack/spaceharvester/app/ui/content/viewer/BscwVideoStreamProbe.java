package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.application.sources.MediaByteRange;
import com.aresstack.spaceharvester.application.sources.SinglePageMetadata;
import org.junit.jupiter.api.Test;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.log.LogLevel;
import uk.co.caprica.vlcj.log.NativeLog;
import uk.co.caprica.vlcj.player.component.EmbeddedMediaPlayerComponent;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Live diagnosis, not a regression test: stream a real video exactly like the reader does (user
 * configuration, KeePass credentials, openMediaRange, MediaStreamBridge, embedded VLC) and record every
 * source range read and VLC's own audio warnings to {@code build/video-debug}. Run it manually with a display,
 * {@code -Dspaceharvester.probe.video=N} for the N-th video bookmark and a copy of the configuration named
 * {@code probe-video.yml} next to it whose {@code space-harvester-app.data-directory} points elsewhere,
 * because a running app locks the stores. It does nothing without the property.
 */
class BscwVideoStreamProbe {
    @Test
    void probe() throws Exception {
        String which = System.getProperty("spaceharvester.probe.video");
        if (which == null) {
            return;
        }
        int seconds = Integer.getInteger("spaceharvester.probe.seconds", 150);
        Path out = Path.of("..", "build", "video-debug", "probe-" + which + ".log");
        Files.createDirectories(out.getParent());
        long t0 = System.nanoTime();
        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(out), true)) {
            java.util.function.Consumer<String> line = text -> {
                String stamped = String.format(Locale.ROOT, "%8.3f %s", (System.nanoTime() - t0) / 1e9, text);
                log.println(stamped);
                System.out.println("PROBE " + stamped);
            };

            Path home = AppSettingsLoader.defaultHome();
            // A copy of the user's configuration with its own data directory: the running app locks the stores.
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
                    line.accept("metadata not yet available: " + notYet.getClass().getSimpleName() + " " + notYet.getMessage());
                    Thread.sleep(2000);
                }
            }
            long size = Long.parseLong(metadata.entry().page().attributes().getOrDefault("dav.contentLength", "-1"));
            line.accept("video " + metadata.entry().page().title() + " size " + size + " bytes");
            MediaByteRange ranges = context.runtime().workspace().openMediaRange(source, pageId);

            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger reads = new AtomicInteger();
            MediaStreamBridge.ChunkSource instrumented = (offset, count) -> {
                int concurrent = inFlight.incrementAndGet();
                long start = System.nanoTime();
                try {
                    return ranges.read(offset, count);
                } finally {
                    inFlight.decrementAndGet();
                    line.accept(String.format(Locale.ROOT, "READ #%d offset %,d (%.1f%%) len %d took %d ms, concurrent %d [%s]",
                            reads.incrementAndGet(), offset, 100.0 * offset / size, count,
                            (System.nanoTime() - start) / 1_000_000, concurrent, Thread.currentThread().getName()));
                }
            };
            MediaStreamBridge bridge = new MediaStreamBridge(instrumented, size, "video/mp4");

            VlcRuntimeLocator.configure();
            MediaPlayerFactory factory = new MediaPlayerFactory("--no-snapshot-preview", "--intf=dummy", "--verbose=2");
            NativeLog nativeLog = factory.application().newLog();
            nativeLog.setLevel(LogLevel.DEBUG);
            nativeLog.addLogListener((level, module, file, lineNumber, name, header, id, message) -> {
                String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
                if (level == LogLevel.ERROR || level == LogLevel.WARNING
                        || text.contains("late") || text.contains("starv") || text.contains("discontinu")
                        || text.contains("drop") || text.contains("underflow") || text.contains("buffering")
                        || text.contains("seek") || text.contains("resampl") || text.contains("deadlock")
                        || text.contains("caching") || text.contains("connection")) {
                    line.accept("VLC " + level + " [" + module + "/" + name + "] " + message);
                }
            });
            EmbeddedMediaPlayerComponent[] player = new EmbeddedMediaPlayerComponent[1];
            JFrame[] frame = new JFrame[1];
            SwingUtilities.invokeAndWait(() -> {
                player[0] = new EmbeddedMediaPlayerComponent(factory, null, null, null, null);
                frame[0] = new JFrame("BSCW video probe");
                frame[0].add(player[0]);
                frame[0].setSize(960, 600);
                frame[0].setVisible(true);
            });
            player[0].mediaPlayer().audio().setMute(true);
            long playStart = System.nanoTime();
            player[0].mediaPlayer().media().play(bridge.address().toASCIIString());
            for (int second = 0; second < seconds; second++) {
                Thread.sleep(1000);
                var mp = player[0].mediaPlayer();
                line.accept(String.format(Locale.ROOT, "TICK wall %.1f s, player %.1f s, state %s, playing %s, in-flight reads %d",
                        (System.nanoTime() - playStart) / 1e9, mp.status().time() / 1000.0, mp.status().state(),
                        mp.status().isPlaying(), inFlight.get()));
            }
            SwingUtilities.invokeAndWait(() -> {
                player[0].mediaPlayer().controls().stop();
                player[0].release();
                frame[0].dispose();
            });
            nativeLog.release();
            factory.release();
            bridge.close();
            context.shutdown();
            line.accept("done, log at " + out.toAbsolutePath().normalize());
        }
    }
}
