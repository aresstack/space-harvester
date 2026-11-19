package com.aresstack.spaceharvester.app.ui.content.viewer;

import com.aresstack.spaceharvester.application.assets.Assets;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;

/**
 * Standbilder eines Videos an seinen Folienwechseln, für die Bildanalyse (Pseudo-Anhänge der Seite): die Wechsel aus
 * dem MP4-Index ({@link SlideChanges}), dann je Wechsel ein Bild kurz danach, wenn die neue Folie steht. VLC spielt
 * dafür ohne Fenster über die {@link MediaStreamBridge} und liefert die Bilder über eine Callback-Fläche in den
 * Speicher; gelesen wird nur, was VLC für die angesprungenen Stellen braucht.
 */
public final class VideoFrameGrabber {

    /** Abstand nach einem Wechsel, bis die neue Folie sicher vollständig zu sehen ist. */
    static final long SETTLE_MILLIS = 2000;
    /** So lange wartet ein Bild höchstens auf VLC (Sprung über BSCW, je 256 KiB etwa 0,7 s). */
    private static final long FRAME_TIMEOUT_MILLIS = 30_000;
    /** Nach dem letzten gelieferten Bild so lange Ruhe, dann gilt es als das angesprungene. */
    private static final long QUIET_MILLIS = 400;
    /** So weit darf die Position von VLC beim gelieferten Bild von der angesprungenen Stelle abweichen. */
    private static final long POSITION_TOLERANCE_MILLIS = 1500;
    /** Liefert VLC nach dem ersten neuen Bild weiter Bilder, gilt nach dieser Zeit das jüngste. */
    private static final long SETTLE_LIMIT_MILLIS = 3000;

    /** Fortschritt: {@code done} von {@code total} Standbildern. */
    public interface Progress {
        void frames(int done, int total);
    }

    private VideoFrameGrabber() {
    }

    /**
     * Die Positionen der Standbilder: die erste Folie und jede erkannte Folie, jeweils {@link #SETTLE_MILLIS} nach
     * ihrem Erscheinen; höchstens {@code limit}.
     */
    static List<Long> positions(List<Long> changes, int limit) {
        List<Long> positions = new ArrayList<>();
        positions.add(SETTLE_MILLIS);
        for (long change : changes) {
            if (positions.size() >= limit) {
                break;
            }
            positions.add(change + SETTLE_MILLIS);
        }
        return positions;
    }

    /**
     * Liest den Index, springt jede Folie an und liefert ihre Standbilder als PNG. Läuft im aufrufenden Thread, nie auf
     * dem EDT; ein Video ohne lesbaren Index hat keine Standbilder.
     *
     * @throws IOException           wenn VLC fehlt oder das Video nicht spielt
     * @throws CancellationException wenn {@code cancelled} zutrifft
     */
    public static List<Assets.VideoFrame> grab(MediaStreamBridge.ChunkSource reader, long size, String mediaType,
                                               int limit, Progress progress, BooleanSupplier cancelled)
            throws IOException {
        return grab(reader, size, mediaType, SlideChanges.load(reader, size), limit, progress, cancelled);
    }

    /** Wie {@link #grab(MediaStreamBridge.ChunkSource, long, String, int, Progress, BooleanSupplier)} mit schon gefundenen Folienwechseln. */
    public static List<Assets.VideoFrame> grab(MediaStreamBridge.ChunkSource reader, long size, String mediaType,
                                               List<Long> changes, int limit, Progress progress,
                                               BooleanSupplier cancelled) throws IOException {
        List<Long> positions = positions(changes, limit);
        if (!VlcRuntimeLocator.configure()) {
            throw new IOException("VLC ist nicht verfügbar");
        }
        Surface surface = new Surface();
        List<Assets.VideoFrame> frames = new ArrayList<>();
        MediaPlayerFactory factory = new MediaPlayerFactory("--intf=dummy", "--no-audio", "--quiet",
                "--no-video-title-show");
        try (MediaStreamBridge bridge = new MediaStreamBridge(reader, size, mediaType)) {
            EmbeddedMediaPlayer player = factory.mediaPlayers().newEmbeddedMediaPlayer();
            try {
                player.videoSurface().set(factory.videoSurfaces().newVideoSurface(surface, surface, true));
                player.media().play(bridge.address().toASCIIString());
                long until = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
                while (surface.count() == 0 && System.currentTimeMillis() < until) {
                    check(cancelled);
                    sleep(50);
                }
                if (surface.count() == 0) {
                    throw new IOException("Das Video spielt nicht");
                }
                // Erst angehalten zeigt VLC nach einem Sprung genau ein Bild; spielend kämen laufend neue.
                for (int attempt = 0; attempt < 50 && player.status().isPlaying(); attempt++) {
                    if (attempt % 10 == 0) {
                        player.controls().setPause(true);
                    }
                    sleep(100);
                }
                sleep(QUIET_MILLIS);
                for (int index = 0; index < positions.size(); index++) {
                    check(cancelled);
                    long position = positions.get(index);
                    long seen = surface.count();
                    player.controls().setTime(position);
                    // Ein Bild aus der Zeit vor dem Sprung kann noch nachkommen: es gilt erst eines an der Stelle.
                    BufferedImage image = null;
                    long frameUntil = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
                    while (System.currentTimeMillis() < frameUntil) {
                        BufferedImage candidate = surface.next(seen, cancelled);
                        if (candidate == null) {
                            break;
                        }
                        image = candidate;
                        seen = surface.count();
                        if (Math.abs(player.status().time() - position) <= POSITION_TOLERANCE_MILLIS) {
                            break;
                        }
                    }
                    if (image != null) {
                        frames.add(new Assets.VideoFrame(position, png(image)));
                    }
                    progress.frames(index + 1, positions.size());
                }
            } finally {
                player.controls().stop();
                player.release();
            }
        } finally {
            factory.release();
        }
        return frames;
    }

    private static byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static void check(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Standbilder abgebrochen");
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Standbilder abgebrochen");
        }
    }

    /** Nimmt die Bilder von VLC entgegen (RV32, ein Pixel je {@code int}) und merkt sich das jüngste. */
    private static final class Surface implements BufferFormatCallback, RenderCallback {

        private int width;
        private int height;
        private int[] pixels;
        private long count;
        private long lastAt;

        @Override
        public synchronized BufferFormat getBufferFormat(int sourceWidth, int sourceHeight) {
            width = sourceWidth;
            height = sourceHeight;
            pixels = new int[sourceWidth * sourceHeight];
            return new RV32BufferFormat(sourceWidth, sourceHeight);
        }

        @Override
        public void allocatedBuffers(ByteBuffer[] buffers) {
        }

        @Override
        public synchronized void display(MediaPlayer mediaPlayer, ByteBuffer[] nativeBuffers,
                                         BufferFormat bufferFormat) {
            nativeBuffers[0].order(ByteOrder.nativeOrder()).asIntBuffer().get(0, pixels, 0, pixels.length);
            count++;
            lastAt = System.currentTimeMillis();
            notifyAll();
        }

        synchronized long count() {
            return count;
        }

        /**
         * Das jüngste Bild nach {@code before}, sobald VLC {@link #QUIET_MILLIS} lang kein weiteres liefert, spätestens
         * {@link #SETTLE_LIMIT_MILLIS} nach dem ersten neuen; {@code null} nach {@link #FRAME_TIMEOUT_MILLIS} ohne
         * neues Bild.
         */
        synchronized BufferedImage next(long before, BooleanSupplier cancelled) {
            long until = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
            long firstAt = 0;
            while (count == before || System.currentTimeMillis() - lastAt < QUIET_MILLIS) {
                check(cancelled);
                long now = System.currentTimeMillis();
                if (count == before && now >= until) {
                    return null;
                }
                if (count != before) {
                    firstAt = firstAt == 0 ? now : firstAt;
                    if (now - firstAt >= SETTLE_LIMIT_MILLIS) {
                        break;
                    }
                }
                try {
                    wait(count == before ? 100 : Math.max(1, QUIET_MILLIS - (now - lastAt)));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Standbilder abgebrochen");
                }
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            return image;
        }
    }
}
