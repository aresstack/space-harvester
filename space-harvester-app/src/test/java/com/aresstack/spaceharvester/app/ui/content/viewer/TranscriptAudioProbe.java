package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.app.transcription.VlcPcmAudioDecoder;
import org.junit.jupiter.api.Test;

import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Manual probe, not a regression test: the audio chain of „Transkript erzeugen“ on a local MP4, as in the app
 * (audio-only MP4, media bridge, VLC conversion), printing how many PCM bytes arrive when. Runs only with the
 * environment variable {@code SPACEHARVESTER_PROBE_AUDIO} pointing at the MP4.
 */
class TranscriptAudioProbe {

    /**
     * Dieselbe Kette über die Quelle: das N-te Video unter den Lesezeichen ({@code SPACEHARVESTER_PROBE_AUDIO_BOOKMARK=N})
     * mit der Konfiguration {@code probe-video.yml}, wie {@link VideoFramesLiveProbe}; jeder Lesezugriff wird gezählt.
     */
    @Test
    void probeSource() throws Exception {
        String which = System.getenv("SPACEHARVESTER_PROBE_AUDIO_BOOKMARK");
        if (which == null) {
            return;
        }
        Path home = com.aresstack.spaceharvester.app.settings.AppSettingsLoader.defaultHome();
        com.aresstack.spaceharvester.app.ui.AppContext context = new com.aresstack.spaceharvester.app.ui.AppContext(
                new com.aresstack.spaceharvester.app.settings.AppSettingsLoader(
                        com.aresstack.spaceharvester.app.settings.Placeholders.system(), home),
                home.resolve("probe-video.yml"), "probe");
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        javax.swing.SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        started.await(120, java.util.concurrent.TimeUnit.SECONDS);
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
        var workspace = context.runtime().workspace();
        var page = workspace.page(source, pageId, true);
        long size = Long.parseLong(page.header().metadata().attributes().getOrDefault("dav.contentLength", "-1"));
        System.out.println("PROBE page " + page.header().metadata().title() + " size " + size);
        com.aresstack.spaceharvester.application.sources.MediaByteRange remote = workspace.openMediaRange(source,
                pageId);
        long start = System.nanoTime();
        AtomicLong reads = new AtomicLong();
        AtomicLong readBytes = new AtomicLong();
        com.aresstack.spaceharvester.application.sources.MediaByteRange counted = (offset, count) -> {
            long before = System.nanoTime();
            byte[] bytes = remote.read(offset, count);
            long n = reads.incrementAndGet();
            readBytes.addAndGet(bytes.length);
            if (n <= 20 || n % 50 == 0) {
                System.out.printf(Locale.ROOT, "PROBE   read #%d at %d: %d of %d bytes in %.2f s (t=%.1f s)%n", n,
                        offset, bytes.length, count, (System.nanoTime() - before) / 1e9,
                        (System.nanoTime() - start) / 1e9);
            }
            return bytes;
        };
        try (VideoTranscriptSource transcript = new VideoTranscriptSource(size, counted)) {
            java.net.URI audio = transcript.openAudioOnly();
            System.out.printf(Locale.ROOT, "PROBE audio-only ready after %.1f s, %d reads, %d bytes%n",
                    (System.nanoTime() - start) / 1e9, reads.get(), readBytes.get());
            AtomicLong bytes = new AtomicLong();
            long limit = 32_000L * 120;
            try {
                new VlcPcmAudioDecoder().decode(audio, () -> bytes.get() > limit,
                        (data, offset, count) -> bytes.addAndGet(count),
                        seconds -> System.out.printf(Locale.ROOT, "PROBE   %d s decoded after %.1f s, %d reads%n",
                                seconds, (System.nanoTime() - start) / 1e9, reads.get()));
            } catch (Exception e) {
                System.out.println("PROBE decode ended: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
            }
            System.out.printf(Locale.ROOT, "PROBE %d PCM bytes after %.1f s, %d reads, %d bytes read%n", bytes.get(),
                    (System.nanoTime() - start) / 1e9, reads.get(), readBytes.get());
        }
        context.shutdown();
    }

    @Test
    void probe() throws Exception {
        String file = System.getenv("SPACEHARVESTER_PROBE_AUDIO");
        if (file == null) {
            return;
        }
        System.out.println("PROBE vlc " + VlcRuntimeLocator.configure());
        Path video = Path.of(file);
        long size = Files.size(video);
        try (RandomAccessFile in = new RandomAccessFile(video.toFile(), "r")) {
            MediaStreamBridge.ChunkSource source = (offset, count) -> {
                synchronized (in) {
                    byte[] bytes = new byte[(int) Math.min(count, size - offset)];
                    in.seek(offset);
                    in.readFully(bytes);
                    return bytes;
                }
            };
            long start = System.nanoTime();
            Mp4AudioStream audio = Mp4AudioOnlySource.open(source, size);
            System.out.printf(Locale.ROOT, "PROBE audio-only mp4 %d bytes after %.1f s%n", audio.length(),
                    (System.nanoTime() - start) / 1e9);
            try (MediaStreamBridge bridge = new MediaStreamBridge(audio, audio.length(), "video/mp4")) {
                System.out.println("PROBE bridge " + bridge.address());
                AtomicLong bytes = new AtomicLong();
                long limit = 32_000L * 120;
                try {
                    new VlcPcmAudioDecoder().decode(bridge.address(), () -> bytes.get() > limit,
                            (data, offset, count) -> bytes.addAndGet(count),
                            seconds -> System.out.printf(Locale.ROOT, "PROBE   %d s decoded after %.1f s%n",
                                    seconds, (System.nanoTime() - start) / 1e9));
                } catch (Exception e) {
                    System.out.println("PROBE decode ended: " + e + (e.getCause() == null ? "" : " / " + e.getCause()));
                    System.out.println("PROBE bridge failure: " + bridge.lastFailure());
                }
                System.out.printf(Locale.ROOT, "PROBE %d PCM bytes after %.1f s%n", bytes.get(),
                        (System.nanoTime() - start) / 1e9);
            }
        }
    }
}
