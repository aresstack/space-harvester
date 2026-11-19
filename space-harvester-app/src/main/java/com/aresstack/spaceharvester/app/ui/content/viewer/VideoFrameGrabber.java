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
    /** So viele Bilder Wiedergabe nach einem Sprung, bevor eines als Standbild gilt (bei 25 fps etwa 0,4 s). */
    private static final int FRAMES_AFTER_SEEK = 10;
    /** So weit darf die Position von VLC beim gelieferten Bild vor der angesprungenen Stelle liegen. */
    private static final long EARLY_TOLERANCE_MILLIS = 500;
    /** So weit darf die Position von VLC beim gelieferten Bild nach der angesprungenen Stelle liegen. */
    private static final long POSITION_TOLERANCE_MILLIS = 1500;
    /** Liefert VLC nach dem ersten neuen Bild weiter Bilder, gilt nach dieser Zeit das jüngste. */
    private static final long SETTLE_LIMIT_MILLIS = 3000;

    /** Fortschritt: {@code done} von {@code total} Standbildern. */
    public interface Progress {
        void frames(int done, int total);
    }

    private VideoFrameGrabber() {
    }

    /** Nimmt jedes Standbild entgegen, sobald es gezogen ist. */
    public interface FrameSink {
        void frame(Assets.VideoFrame frame) throws IOException;
    }

    /** Der Beginn jeder Folie: die erste ab 0, dann jede erkannte; höchstens {@code limit}. */
    public static List<Long> slides(List<Long> changes, int limit) {
        List<Long> slides = new ArrayList<>();
        slides.add(0L);
        for (long change : changes) {
            if (slides.size() >= limit) {
                break;
            }
            slides.add(change);
        }
        return slides;
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
        List<Assets.VideoFrame> frames = new ArrayList<>();
        grab(reader, size, mediaType, slides(SlideChanges.load(reader, size), limit), frames::add, progress,
                cancelled);
        return frames;
    }

    /**
     * Springt jede Folie {@link #SETTLE_MILLIS} nach ihrem Beginn an und gibt ihr Standbild sofort an {@code sink};
     * das Standbild trägt den Beginn der Folie. Läuft im aufrufenden Thread, nie auf dem EDT.
     *
     * @param slides Beginn der Folien, deren Standbild gebraucht wird
     * @throws IOException           wenn VLC fehlt oder das Video nicht spielt
     * @throws CancellationException wenn {@code cancelled} zutrifft
     */
    public static void grab(MediaStreamBridge.ChunkSource reader, long size, String mediaType, List<Long> slides,
                            FrameSink sink, Progress progress, BooleanSupplier cancelled) throws IOException {
        if (slides.isEmpty()) {
            return;
        }
        try (Session session = Session.open(reader, size, mediaType, cancelled)) {
            for (int index = 0; index < slides.size(); index++) {
                Assets.VideoFrame frame = session.frame(slides.get(index));
                if (frame != null) {
                    sink.frame(frame);
                }
                progress.frames(index + 1, slides.size());
            }
        }
    }

    /**
     * Ein offener VLC-Player ohne Fenster für Standbilder eines Videos: springt nacheinander beliebige Folien an, ohne
     * für jedes Bild neu zu starten. Nicht threadsicher; nie auf dem EDT.
     */
    public static final class Session implements AutoCloseable {

        private final MediaPlayerFactory factory;
        private final MediaStreamBridge bridge;
        private final EmbeddedMediaPlayer player;
        private final Surface surface = new Surface();
        private final BooleanSupplier cancelled;

        private Session(MediaPlayerFactory factory, MediaStreamBridge bridge, EmbeddedMediaPlayer player,
                        BooleanSupplier cancelled) {
            this.factory = factory;
            this.bridge = bridge;
            this.player = player;
            this.cancelled = cancelled;
        }

        /**
         * Startet das Video angehalten.
         *
         * @throws IOException           wenn VLC fehlt oder das Video nicht spielt
         * @throws CancellationException wenn {@code cancelled} zutrifft
         */
        public static Session open(MediaStreamBridge.ChunkSource reader, long size, String mediaType,
                                   BooleanSupplier cancelled) throws IOException {
            if (!VlcRuntimeLocator.configure()) {
                throw new IOException("VLC ist nicht verfügbar");
            }
            MediaPlayerFactory factory = new MediaPlayerFactory("--intf=dummy", "--no-audio", "--quiet",
                    "--no-video-title-show");
            MediaStreamBridge bridge = null;
            Session session = null;
            try {
                bridge = new MediaStreamBridge(reader, size, mediaType);
                session = new Session(factory, bridge, factory.mediaPlayers().newEmbeddedMediaPlayer(), cancelled);
                session.start();
                return session;
            } catch (IOException | RuntimeException failure) {
                if (session != null) {
                    session.close();
                } else {
                    if (bridge != null) {
                        bridge.close();
                    }
                    factory.release();
                }
                throw failure;
            }
        }

        private void start() throws IOException {
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
        }

        /**
         * Das Standbild der Folie, die an {@code slide} beginnt, {@link #SETTLE_MILLIS} danach; {@code null}, wenn VLC
         * dort kein Bild liefert.
         */
        public Assets.VideoFrame frame(long slide) throws IOException {
            check(cancelled);
            long position = slide + SETTLE_MILLIS;
            long seen = surface.count();
            // Angehalten springt VLC nicht zuverlässig weiter: springen, abspielen, das erste Bild an der Stelle
            // nehmen, wieder anhalten. Die Folie steht dort noch eine Weile, ein Bild später ist gleich.
            player.controls().setTime(position);
            player.controls().setPause(false);
            // VLC meldet die neue Position schon vor dem ersten Bild von dort; Bilder aus der Zeit vor dem Sprung sind
            // noch unterwegs. Erst nach einigen Bildern Wiedergabe gilt eines.
            BufferedImage image = null;
            long frameUntil = System.currentTimeMillis() + FRAME_TIMEOUT_MILLIS;
            int played = 0;
            try {
                while (true) {
                    BufferedImage candidate = surface.awaitNew(seen, frameUntil, cancelled);
                    if (candidate == null) {
                        break;
                    }
                    image = candidate;
                    seen = surface.count();
                    long time = player.status().time();
                    if (++played >= FRAMES_AFTER_SEEK && time >= position - EARLY_TOLERANCE_MILLIS
                            && time <= position + POSITION_TOLERANCE_MILLIS) {
                        break;
                    }
                }
            } finally {
                player.controls().setPause(true);
            }
            return image == null ? null : new Assets.VideoFrame(slide, png(image));
        }

        @Override
        public void close() {
            try {
                player.controls().stop();
                player.release();
            } finally {
                try {
                    bridge.close();
                } finally {
                    factory.release();
                }
            }
        }
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

        /** Das nächste Bild nach {@code before}, sobald es da ist; {@code null}, wenn bis {@code until} keines kommt. */
        synchronized BufferedImage awaitNew(long before, long until, BooleanSupplier cancelled) {
            while (count == before) {
                check(cancelled);
                long left = until - System.currentTimeMillis();
                if (left <= 0) {
                    return null;
                }
                try {
                    wait(Math.min(left, 100));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Standbilder abgebrochen");
                }
            }
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            image.setRGB(0, 0, width, height, pixels, 0, width);
            return image;
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
