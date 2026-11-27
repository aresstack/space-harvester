package com.aresstack.spaceharvester.app.podcast;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.PictureStage;
import com.aresstack.spaceharvester.application.diagrams.VectorDiagram;
import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.podcast.Podcast;
import org.jcodec.codecs.h264.H264Encoder;
import org.jcodec.common.Codec;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.VideoCodecMeta;
import org.jcodec.common.VideoEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.ColorSpace;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.common.model.Size;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.containers.mp4.muxer.PCMMP4MuxerTrack;
import org.jcodec.scale.AWTUtil;
import org.jcodec.scale.ColorUtil;
import org.jcodec.scale.Transform;

import javax.sound.sampled.AudioFormat;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Der Podcast als Video (Angelo 27.11.2026: „dann wären die Diashows mit drin“): der Ton aller Abschnitte und dazu je
 * Abschnitt sein Bild, solange er läuft, wie auf der Bühne beim Abspielen ({@link PictureStage#paint}); ein Abschnitt
 * ohne Bild zeigt den Titel seines Themas. Kodiert wird in reinem Java mit JCodec, wie die Aufnahme in Ask AI: H.264
 * in MP4, der Ton unkomprimiert (PCM) daneben.
 *
 * <p>Größe und Bildrate stellt der Benutzer ein ({@link Options}); weil sich das Bild nur mit dem Thema ändert, reicht
 * eine kleine Bildrate. An jedem Bildwechsel beginnt ein Schlüsselbild, damit Springen dort sofort das neue Bild zeigt.</p>
 */
public final class PodcastVideo {

    private static final System.Logger LOG = System.getLogger(PodcastVideo.class.getName());
    /** Rand um das Bild, als Anteil der Videohöhe (die Bühne lässt ebenso etwas Luft). */
    static final double MARGIN_SHARE = 1.0 / 36;
    /** Schriftgröße der Bildunterschrift, als Anteil der Videohöhe. */
    static final double CAPTION_SHARE = 1.0 / 32;
    /** Schriftgröße des Thementitels ohne Bild, als Anteil der Videohöhe. */
    static final double TITLE_SHARE = 1.0 / 14;

    /**
     * Größe und Bildrate des Videos.
     *
     * @param width            Breite in Pixeln; H.264 braucht eine gerade Zahl, eine ungerade wird um eins kleiner
     *                         (mindestens 2)
     * @param height           Höhe in Pixeln, ebenso gerade
     * @param framesPerSecond  Bilder je Sekunde
     */
    public record Options(int width, int height, int framesPerSecond) {
        public Options {
            if (width < 1 || height < 1 || framesPerSecond < 1) {
                throw new IllegalArgumentException("Video braucht Breite, Höhe und Bilder je Sekunde ab 1");
            }
            width = Math.max(2, width - width % 2);
            height = Math.max(2, height - height % 2);
        }
    }

    private PodcastVideo() {
    }

    /** Schreibt den Podcast als Video nach {@code target}. */
    public static void write(Podcast podcast, Path target, Options options, JobContext context) throws Exception {
        Objects.requireNonNull(options, "options");
        List<Podcast.Part> parts = PodcastExport.parts(podcast);
        AudioFormat format = PodcastExport.format(parts);
        context.phase(PodcastExport.COMPONENT, "Video schreiben");
        context.discovered(parts.size());
        PodcastExport.replaceWhenDone(target, temporary -> encode(parts, format, temporary, options, context));
    }

    private static void encode(List<Podcast.Part> parts, AudioFormat format, Path temporary, Options options,
                               JobContext context) throws Exception {
        int fps = options.framesPerSecond();
        Files.deleteIfExists(temporary);
        try (SeekableByteChannel channel = NIOUtils.writableChannel(temporary.toFile())) {
            MP4Muxer muxer = MP4Muxer.createMP4MuxerToChannel(channel);
            MuxerTrack video = muxer.addVideoTrack(Codec.H264, VideoCodecMeta.createSimpleVideoCodecMeta(
                    new Size(options.width(), options.height()), ColorSpace.YUV420J));
            PCMMP4MuxerTrack audio = muxer.addPCMAudioTrack(new org.jcodec.common.AudioFormat(
                    Math.round(format.getSampleRate()), 16, format.getChannels(), true, false));
            H264Encoder encoder = H264Encoder.createH264Encoder();
            int keyInterval = encoder.getKeyInterval();
            Transform toYuv = ColorUtil.getTransform(ColorSpace.RGB, ColorSpace.YUV420J);
            ByteBuffer buffer = null;
            long samples = 0;
            long frame = 0;
            Podcast.Picture shownPicture = null;
            String shownTitle = null;
            Picture yuv = null;
            for (Podcast.Part part : parts) {
                context.checkpoint();
                byte[] pcm = PodcastExport.read(part.audio(), format);
                samples += pcm.length / format.getFrameSize();
                long until = Math.round(samples * (double) fps / format.getSampleRate());
                String title = title(part);
                boolean change = yuv == null || !Objects.equals(part.picture(), shownPicture)
                        || part.picture() == null && !Objects.equals(title, shownTitle);
                if (change) {
                    yuv = yuv(frame(part, options), toYuv);
                    int needed = encoder.estimateBufferSize(yuv);
                    if (buffer == null || buffer.capacity() < needed) {
                        buffer = ByteBuffer.allocate(needed);
                    }
                    shownPicture = part.picture();
                    shownTitle = title;
                }
                boolean first = true;
                while (frame < until) {
                    context.checkpoint();
                    // Ein neues Bild beginnt mit einem Schlüsselbild; danach wieder im üblichen Abstand des Kodierers.
                    encoder.setKeyInterval(change && first ? 1 : keyInterval);
                    buffer.clear();
                    VideoEncoder.EncodedFrame encoded = encoder.encodeFrame(yuv, buffer);
                    ByteBuffer data = encoded.getData();
                    ByteBuffer copy = ByteBuffer.allocate(data.remaining());
                    copy.put(data.duplicate()).flip();
                    video.addFrame(Packet.createPacket(copy, frame, fps, 1, frame, encoded.isKeyFrame()
                            ? Packet.FrameType.KEY : Packet.FrameType.INTER, null));
                    frame++;
                    first = false;
                }
                audio.addSamples(ByteBuffer.wrap(pcm));
                context.processed(1);
                context.written(1);
            }
            muxer.finish();
        }
    }

    private static Picture yuv(BufferedImage image, Transform toYuv) {
        Picture rgb = AWTUtil.fromBufferedImage(image, ColorSpace.RGB);
        Picture yuv = Picture.create(rgb.getWidth(), rgb.getHeight(), ColorSpace.YUV420J);
        toYuv.transform(rgb, yuv);
        return yuv;
    }

    /** Der Titel eines Abschnitts, wie die Zeitleiste ihn nennt: Thema, und wenn anders, der Abschnitt dahinter. */
    static String title(Podcast.Part part) {
        String topic = part.topicTitle() == null ? "" : part.topicTitle().strip();
        String segment = part.segmentTitle() == null ? "" : part.segmentTitle().strip();
        return segment.isEmpty() || segment.equals(topic) ? topic : topic + " · " + segment;
    }

    /**
     * Ein Bild des Videos zu einem Abschnitt: sein Bild mittig wie auf der Bühne, darunter die Bildunterschrift; ohne
     * Bild (oder wenn es sich nicht lesen lässt) der Titel des Themas.
     */
    static BufferedImage frame(Podcast.Part part, Options options) {
        int width = options.width();
        int height = options.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Comic.SURFACE);
            g.fillRect(0, 0, width, height);
            PictureStage.Picture picture = load(part.picture());
            int margin = (int) Math.round(height * MARGIN_SHARE);
            if (picture == null) {
                g.setColor(Comic.INK);
                g.setFont(Comic.semiBold((float) (height * TITLE_SHARE)));
                drawCentered(g, title(part), margin, 0, width - 2 * margin, height);
                return image;
            }
            int captionHeight = 0;
            if (!picture.caption().isEmpty()) {
                g.setFont(Comic.regular((float) (height * CAPTION_SHARE)));
                captionHeight = g.getFontMetrics().getHeight() + margin;
            }
            int areaWidth = width - 2 * margin;
            int areaHeight = Math.max(1, height - 2 * margin - captionHeight);
            Graphics2D area = (Graphics2D) g.create(margin, margin, areaWidth, areaHeight);
            PictureStage.paint(area, picture, 1f, areaWidth, areaHeight);
            area.dispose();
            if (captionHeight > 0) {
                java.awt.Rectangle sheet = PictureStage.sheet(picture, areaWidth, areaHeight);
                g.setColor(Comic.MUTED);
                FontMetrics metrics = g.getFontMetrics();
                String caption = fit(picture.caption(), metrics, areaWidth);
                int x = margin + (areaWidth - metrics.stringWidth(caption)) / 2;
                int y = margin + sheet.y + sheet.height + margin / 2 + metrics.getAscent();
                g.drawString(caption, x, y);
            }
            return image;
        } finally {
            g.dispose();
        }
    }

    /** Das Bild eines Abschnitts für die Bühne; {@code null} ohne Bild oder wenn es sich nicht lesen lässt. */
    private static PictureStage.Picture load(Podcast.Picture picture) {
        if (picture == null) {
            return null;
        }
        try {
            return picture(picture);
        } catch (IOException | RuntimeException e) {
            LOG.log(System.Logger.Level.INFO, "Podcast-Bild nicht lesbar, das Video zeigt den Titel ({0})",
                    e.getClass().getName());
            return null;
        }
    }

    /** Liest ein Bild von der Platte: SVG als Schaubild, alles andere als Rasterbild. */
    public static PictureStage.Picture picture(Podcast.Picture picture) throws IOException {
        if (picture.vector()) {
            return new PictureStage.Picture(null, VectorDiagram.of(Files.readAllBytes(picture.file()))
                    .orElseThrow(() -> new IOException("Kein lesbares SVG")), picture.caption());
        }
        BufferedImage image = javax.imageio.ImageIO.read(picture.file().toFile());
        if (image == null) {
            throw new IOException("Kein lesbares Bild");
        }
        return new PictureStage.Picture(image, null, picture.caption());
    }

    /** Schreibt {@code text} in Zeilen umbrochen mittig in das Rechteck. */
    private static void drawCentered(Graphics2D g, String text, int x, int y, int width, int height) {
        FontMetrics metrics = g.getFontMetrics();
        List<String> lines = wrap(text, metrics, width);
        int lineHeight = metrics.getHeight();
        int top = y + (height - lines.size() * lineHeight) / 2 + metrics.getAscent();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            g.drawString(line, x + (width - metrics.stringWidth(line)) / 2, top + i * lineHeight);
        }
    }

    static List<String> wrap(String text, FontMetrics metrics, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : (text == null ? "" : text.strip()).split("\\s+")) {
            String candidate = line.length() == 0 ? word : line + " " + word;
            if (line.length() > 0 && metrics.stringWidth(candidate) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (line.length() > 0) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** Kürzt eine zu lange Zeile mit „…“, wie die Bildunterschrift der Bühne. */
    static String fit(String text, FontMetrics metrics, int width) {
        if (metrics.stringWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && metrics.stringWidth(text.substring(0, end).strip() + ellipsis) > width) {
            end--;
        }
        return text.substring(0, end).strip() + ellipsis;
    }
}
