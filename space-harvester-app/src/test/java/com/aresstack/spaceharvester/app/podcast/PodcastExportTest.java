package com.aresstack.spaceharvester.app.podcast;

import com.aresstack.spaceharvester.application.jobs.JobCancelledException;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.podcast.Podcast;
import org.jcodec.api.FrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.scale.AWTUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * „Als WAV exportieren …“ und „Als Video exportieren …“ (Angelo 27.11.2026): alle Abschnitte hintereinander in einem
 * Format, das Video mit dem Bild jedes Abschnitts zur rechten Zeit. Die Abschnitte sind erfundene Töne.
 */
class PodcastExportTest {

    @TempDir
    Path dir;

    @Test
    void wavHoldsAllPartsInTheFormatOfTheFirst() throws Throwable {
        Podcast podcast = podcast(
                part(0, "Neue Ablage", tone("a.wav", 24000, 2.0), null),
                // Ein Abschnitt aus einem anderen Sprachmodell: andere Abtastrate, wird umgerechnet.
                part(1, "Kurzmeldungen", tone("b.wav", 16000, 1.5), null),
                part(2, "Ausblick", tone("c.wav", 24000, 1.0), null));
        Counting context = new Counting();
        Path target = dir.resolve("out/Podcast Test.wav");

        run(PodcastExport.task(podcast, target, PodcastExport.Format.WAV, null), context);

        AudioFileFormat written = AudioSystem.getAudioFileFormat(target.toFile());
        assertThat(written.getType()).isEqualTo(AudioFileFormat.Type.WAVE);
        AudioFormat format = written.getFormat();
        assertThat(format.getSampleRate()).isEqualTo(24000f);
        assertThat(format.getSampleSizeInBits()).isEqualTo(16);
        assertThat(format.getChannels()).isEqualTo(1);
        assertThat(written.getFrameLength() / 24000.0).isCloseTo(4.5, org.assertj.core.data.Offset.offset(0.01));
        assertThat(context.discovered.get()).isEqualTo(3);
        assertThat(context.processed.get()).isEqualTo(3);
        assertThat(Files.exists(dir.resolve("out/Podcast Test.wav.part"))).isFalse();
    }

    @Test
    void cancelledExportLeavesNoFile() throws Exception {
        Podcast podcast = podcast(part(0, "Eins", tone("a.wav", 24000, 0.5), null),
                part(1, "Zwei", tone("b.wav", 24000, 0.5), null));
        Path target = dir.resolve("Podcast.wav");
        Counting context = new Counting();
        context.cancelAfter = 1;

        assertThatThrownBy(() -> run(PodcastExport.task(podcast, target, PodcastExport.Format.WAV, null), context))
                .isInstanceOf(JobCancelledException.class);

        assertThat(Files.exists(target)).isFalse();
        assertThat(Files.exists(dir.resolve("Podcast.wav.part"))).isFalse();
    }

    @Test
    void unfinishedPodcastIsNotExported() throws Exception {
        Podcast podcast = podcast(part(0, "Eins", tone("a.wav", 24000, 0.5), null),
                new Podcast.Part(1, -1, "Zwei", null, null));

        assertThat(PodcastExport.exportable(podcast)).isFalse();
        assertThatThrownBy(() -> run(PodcastExport.task(podcast, dir.resolve("x.wav"), PodcastExport.Format.WAV,
                null), new Counting())).isInstanceOf(JobFailedException.class);
        assertThat(Files.exists(dir.resolve("x.wav"))).isFalse();
    }

    @Test
    void videoShowsThePictureOfEachPartWhileItPlays() throws Throwable {
        Podcast podcast = podcast(
                part(0, "Rot", tone("a.wav", 24000, 2.0), picture("rot.png", Color.RED)),
                part(1, "Ohne Bild", tone("b.wav", 16000, 1.5), null),
                part(2, "Blau", tone("c.wav", 24000, 1.0), picture("blau.png", Color.BLUE)));
        Path target = dir.resolve("Podcast.mp4");
        Counting context = new Counting();

        run(PodcastExport.task(podcast, target, PodcastExport.Format.VIDEO, new PodcastVideo.Options(160, 90, 4)),
                context);

        try (SeekableByteChannel channel = NIOUtils.readableChannel(target.toFile())) {
            MP4Demuxer demuxer = MP4Demuxer.createMP4Demuxer(channel);
            assertThat(demuxer.getVideoTracks()).hasSize(1);
            assertThat(demuxer.getAudioTracks()).hasSize(1);
            assertThat(demuxer.getVideoTracks().get(0).getMeta().getTotalFrames()).isEqualTo(18);
            assertThat(demuxer.getAudioTracks().get(0).getMeta().getTotalDuration())
                    .isCloseTo(4.5, org.assertj.core.data.Offset.offset(0.01));
        }
        assertThat(center(target, 1.0)).satisfies(color -> assertThat(isRed(color)).isTrue());
        assertThat(center(target, 2.75)).satisfies(color -> {
            assertThat(isRed(color)).isFalse();
            assertThat(isBlue(color)).isFalse();
        });
        assertThat(center(target, 4.0)).satisfies(color -> assertThat(isBlue(color)).isTrue());
        assertThat(context.processed.get()).isEqualTo(3);
    }

    @Test
    void videoSizeIsEvenForTheEncoder() {
        PodcastVideo.Options options = new PodcastVideo.Options(1281, 721, 1);
        assertThat(options.width()).isEqualTo(1280);
        assertThat(options.height()).isEqualTo(720);
    }

    @Test
    void formatComesFromTheParameter() {
        assertThat(PodcastExport.Format.parse("VIDEO")).isEqualTo(PodcastExport.Format.VIDEO);
        assertThat(PodcastExport.Format.parse(null)).isEqualTo(PodcastExport.Format.WAV);
        assertThat(PodcastExport.Format.VIDEO.extension()).isEqualTo("mp4");
    }

    // --- Hilfen -------------------------------------------------------------------------------------------------

    /** Führt den Auftrag aus wie {@code Jobs}, mit seiner eigenen Ausnahme statt der Hülle. */
    private static void run(com.aresstack.spaceharvester.application.jobs.JobTask task, JobContext context)
            throws Throwable {
        try {
            task.start(context).toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException e) {
            throw e.getCause();
        }
    }

    private static Color center(Path video, double second) throws Exception {
        try (SeekableByteChannel channel = NIOUtils.readableChannel(video.toFile())) {
            FrameGrab grab = FrameGrab.createFrameGrab(channel);
            grab.seekToSecondPrecise(second);
            BufferedImage frame = AWTUtil.toBufferedImage(grab.getNativeFrame());
            return new Color(frame.getRGB(frame.getWidth() / 2, frame.getHeight() / 2));
        }
    }

    private static boolean isRed(Color color) {
        return color.getRed() > 180 && color.getGreen() < 80 && color.getBlue() < 80;
    }

    private static boolean isBlue(Color color) {
        return color.getBlue() > 180 && color.getRed() < 80 && color.getGreen() < 80;
    }

    private static Podcast podcast(Podcast.Part... parts) {
        return new Podcast("test", "Erfundene Folge", List.of(parts), false);
    }

    private static Podcast.Part part(int topic, String title, Path audio, Podcast.Picture picture) {
        return new Podcast.Part(topic, 0, title, title, audio, picture);
    }

    private Podcast.Picture picture(String name, Color color) throws Exception {
        BufferedImage image = new BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, 320, 180);
        g.dispose();
        Path file = dir.resolve(name);
        ImageIO.write(image, "png", file.toFile());
        return new Podcast.Picture(file, "image/png", "");
    }

    private Path tone(String name, int rate, double seconds) throws Exception {
        int frames = (int) Math.round(rate * seconds);
        ByteBuffer pcm = ByteBuffer.allocate(frames * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            pcm.putShort((short) (4000 * Math.sin(2 * Math.PI * 440 * i / rate)));
        }
        AudioFormat format = new AudioFormat(rate, 16, 1, true, false);
        Path file = dir.resolve(name);
        try (AudioInputStream in = new AudioInputStream(new ByteArrayInputStream(pcm.array()), format, frames)) {
            AudioSystem.write(in, AudioFileFormat.Type.WAVE, file.toFile());
        }
        return file;
    }

    static final class Counting implements JobContext {
        final AtomicLong discovered = new AtomicLong();
        final AtomicLong processed = new AtomicLong();
        final AtomicInteger checkpoints = new AtomicInteger();
        int cancelAfter = -1;

        @Override
        public String jobId() {
            return "podcast-export";
        }

        @Override
        public void checkpoint() {
            if (cancelAfter >= 0 && checkpoints.incrementAndGet() > cancelAfter) {
                throw new JobCancelledException(jobId());
            }
        }

        @Override
        public boolean cancelRequested() {
            return false;
        }

        @Override
        public void discovered(long pages) {
            discovered.addAndGet(pages);
        }

        @Override
        public void processed(long pages) {
            processed.addAndGet(pages);
        }

        @Override
        public void written(long pages) {
        }

        @Override
        public void failed(long pages) {
        }
    }
}
