package com.aresstack.spaceharvester.app.podcast;

import com.aresstack.spaceharvester.application.jobs.JobContext;
import com.aresstack.spaceharvester.application.jobs.JobFailedException;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.podcast.Podcast;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Der fertige Podcast als Datei (Angelo 27.11.2026: „Transkript speichern“ gab es, aber kein WAV und kein Video). Beide
 * Exporte lesen dieselben Abschnitte in Sendereihenfolge, wie die Wiedergabe sie spielt, ohne Pausen dazwischen, in
 * einem gemeinsamen Format: PCM mit 16 Bit, Abtastrate und Kanäle des ersten Abschnitts. Ein Abschnitt aus einem
 * anderen Sprachmodell wird dabei umgerechnet.
 *
 * <p>Geschrieben wird erst in eine Nachbardatei mit {@code .part}; erst wenn alles drin ist, ersetzt sie das Ziel. Ein
 * Abbruch hinterlässt deshalb nie eine halbe Datei unter dem gewählten Namen.</p>
 */
public final class PodcastExport {

    /** Angabe des Auftrags: die gewählte Datei. */
    public static final String FILE_PARAMETER = "podcast.file";
    /** Angabe des Auftrags: das Format ({@link Format#name()}). */
    public static final String FORMAT_PARAMETER = "podcast.format";
    /** Komponente in den Diagnoseereignissen. */
    static final String COMPONENT = "Podcast-Export";

    /** Das Format der Datei. */
    public enum Format {
        /** Der Ton allein als WAV. */
        WAV("wav", "WAV"),
        /** Der Ton mit den Bildern als Diashow, H.264 in MP4. */
        VIDEO("mp4", "Video");

        private final String extension;
        private final String label;

        Format(String extension, String label) {
            this.extension = extension;
            this.label = label;
        }

        /** Dateiendung ohne Punkt. */
        public String extension() {
            return extension;
        }

        /** Für Menschen, etwa in der Auftragsliste: „WAV“ oder „Video“. */
        public String label() {
            return label;
        }

        /** Das Format zu einer Angabe; unbekannt oder fehlend heißt WAV. */
        public static Format parse(String value) {
            if (value != null) {
                for (Format format : values()) {
                    if (format.name().equalsIgnoreCase(value.strip())) {
                        return format;
                    }
                }
            }
            return WAV;
        }
    }

    private PodcastExport() {
    }

    /** Ob jeder Abschnitt des Podcasts sein Audio hat; erst dann lässt er sich als Ganzes exportieren. */
    public static boolean exportable(Podcast podcast) {
        return podcast != null && !podcast.parts().isEmpty()
                && podcast.parts().stream().allMatch(part -> part.audio() != null);
    }

    /**
     * Die Arbeit des Auftrags {@code PODCAST_EXPORT}: der Podcast im Stand beim Klick als WAV oder als Video nach
     * {@code target}.
     */
    public static JobTask task(Podcast podcast, Path target, Format format, PodcastVideo.Options options) {
        Objects.requireNonNull(podcast, "podcast");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(format, "format");
        return JobTask.blocking(context -> {
            if (podcast.title() != null) {
                context.rootTitle(podcast.title());
            }
            if (format == Format.VIDEO) {
                PodcastVideo.write(podcast, target, options, context);
            } else {
                writeWav(podcast, target, context);
            }
        });
    }

    /** Schreibt den Ton des Podcasts als eine WAV-Datei nach {@code target}. */
    public static void writeWav(Podcast podcast, Path target, JobContext context) throws Exception {
        List<Podcast.Part> parts = parts(podcast);
        AudioFormat format = format(parts);
        context.phase(COMPONENT, "WAV schreiben");
        context.discovered(parts.size());
        replaceWhenDone(target, temporary -> {
            try (RandomAccessFile out = new RandomAccessFile(temporary.toFile(), "rw")) {
                out.setLength(0);
                out.write(wavHeader(format, 0));
                long bytes = 0;
                byte[] buffer = new byte[Math.max(format.getFrameSize(), 64 * 1024 / format.getFrameSize()
                        * format.getFrameSize())];
                for (Podcast.Part part : parts) {
                    context.checkpoint();
                    try (AudioInputStream in = open(part.audio(), format)) {
                        int read;
                        while ((read = in.read(buffer)) > 0) {
                            out.write(buffer, 0, read);
                            bytes += read;
                        }
                    }
                    context.processed(1);
                    context.written(1);
                }
                if (bytes > MAX_WAV_DATA) {
                    throw new JobFailedException("WAV_TOO_LONG", "Der Podcast ist für eine WAV-Datei zu lang (mehr als "
                            + "4 GB Ton); bitte als Video exportieren.");
                }
                out.seek(0);
                out.write(wavHeader(format, bytes));
            }
        });
    }

    /** Das Format von WAV-Dateien begrenzt die Daten auf 32 Bit. */
    static final long MAX_WAV_DATA = 0xFFFFFFFFL - 36;

    /** Die Abschnitte des Podcasts in Sendereihenfolge; fehlt einem das Audio, scheitert der Export verständlich. */
    static List<Podcast.Part> parts(Podcast podcast) {
        if (!exportable(podcast)) {
            throw new JobFailedException("PODCAST_INCOMPLETE", "Der Podcast ist noch nicht fertig vertont; bitte "
                    + "warten, bis alle Abschnitte hörbar sind, und dann erneut exportieren.");
        }
        return podcast.parts();
    }

    /** Das gemeinsame Format: PCM mit 16 Bit, Little Endian, Abtastrate und Kanäle des ersten Abschnitts. */
    static AudioFormat format(List<Podcast.Part> parts) throws IOException, UnsupportedAudioFileException {
        AudioFormat first = AudioSystem.getAudioFileFormat(parts.get(0).audio().toFile()).getFormat();
        float rate = first.getSampleRate() > 0 ? first.getSampleRate() : first.getFrameRate();
        int channels = Math.max(1, first.getChannels());
        return new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, rate, 16, channels, channels * 2, rate, false);
    }

    /** Öffnet ein Abschnitts-Audio im gemeinsamen Format; ein anderes Format wird umgerechnet. */
    static AudioInputStream open(Path audio, AudioFormat format) throws IOException, UnsupportedAudioFileException {
        AudioInputStream in = AudioSystem.getAudioInputStream(audio.toFile());
        if (in.getFormat().matches(format)) {
            return in;
        }
        AudioFormat source = in.getFormat();
        if (!AudioFormat.Encoding.PCM_SIGNED.equals(source.getEncoding()) || source.getSampleSizeInBits() != 16) {
            // Erst in PCM mit 16 Bit, dann Rate und Kanäle: Java Sound kann nicht alles in einem Schritt.
            AudioFormat pcm = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, source.getSampleRate(), 16,
                    source.getChannels(), source.getChannels() * 2, source.getSampleRate(), false);
            in = AudioSystem.getAudioInputStream(pcm, in);
        }
        if (!in.getFormat().matches(format)) {
            if (!AudioSystem.isConversionSupported(format, in.getFormat())) {
                in.close();
                throw new JobFailedException("PODCAST_AUDIO_FORMAT", "Ein Abschnitt des Podcasts hat ein Tonformat ("
                        + describe(source) + "), das sich nicht in das der übrigen (" + describe(format)
                        + ") umrechnen lässt.");
            }
            in = AudioSystem.getAudioInputStream(format, in);
        }
        return in;
    }

    private static String describe(AudioFormat format) {
        return String.format(Locale.ROOT, "%.0f Hz, %d Bit, %d Kanäle", format.getSampleRate(),
                format.getSampleSizeInBits(), format.getChannels());
    }

    /** Der Kopf einer WAV-Datei (RIFF, PCM) für {@code dataBytes} Ton. */
    static byte[] wavHeader(AudioFormat format, long dataBytes) {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        int rate = Math.round(format.getSampleRate());
        int channels = format.getChannels();
        int frame = format.getFrameSize();
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt((int) Math.min(0xFFFFFFFFL, 36 + dataBytes));
        header.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short) 1);
        header.putShort((short) channels);
        header.putInt(rate);
        header.putInt(rate * frame);
        header.putShort((short) frame);
        header.putShort((short) format.getSampleSizeInBits());
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt((int) Math.min(0xFFFFFFFFL, dataBytes));
        return header.array();
    }

    /** Schreibt in die Nachbardatei {@code .part} und ersetzt erst danach das Ziel; scheitert es, bleibt nichts liegen. */
    static void replaceWhenDone(Path target, Writer writer) throws Exception {
        Objects.requireNonNull(target, "target");
        Path absolute = target.toAbsolutePath();
        if (absolute.getParent() != null) {
            Files.createDirectories(absolute.getParent());
        }
        Path temporary = absolute.resolveSibling(absolute.getFileName() + ".part");
        boolean done = false;
        try {
            writer.write(temporary);
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING);
            done = true;
        } finally {
            if (!done) {
                Files.deleteIfExists(temporary);
            }
        }
    }

    /** Schreibt eine Datei. */
    @FunctionalInterface
    interface Writer {
        void write(Path temporary) throws Exception;
    }

    /** Der ganze Ton eines Abschnitts im gemeinsamen Format. */
    static byte[] read(Path audio, AudioFormat format) throws IOException, UnsupportedAudioFileException {
        try (AudioInputStream in = open(audio, format)) {
            return in.readAllBytes();
        }
    }
}
