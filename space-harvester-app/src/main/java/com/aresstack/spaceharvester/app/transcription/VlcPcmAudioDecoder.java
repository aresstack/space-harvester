package com.aresstack.spaceharvester.app.transcription;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Convert the virtual audio-only MP4 through libVLC's stream output into raw 16-kHz mono S16LE PCM and hand it on
 * while the conversion is still running. Unlike an audio callback, which libVLC paces to playback speed (one second
 * of audio per second), the conversion runs as fast as the source delivers (Angelo, 19.11.2026: "lädt nur im
 * Sekundentakt"). Never open the original video address or persist its video payload.
 */
public final class VlcPcmAudioDecoder implements AudioPcmDecoder {

    static final int SAMPLE_RATE = 16_000;
    static final int BYTES_PER_SECOND = SAMPLE_RATE * 2;
    private static final int READ_BYTES = 64 * 1024;
    /**
     * VLC's resampler for 48 kHz to 16 kHz. The default one rounds down per AAC frame (341 instead of 341.33 samples)
     * and lost 1/1024 of the audio: over 42 minutes the PCM was 2.48 s short and every timestamp drifted, a resumed
     * transcription started up to half a second off. Speex keeps the length (measured: 21 ms short, one frame of
     * encoder delay, without drift).
     */
    static final String RESAMPLER = "speex_resampler";

    @Override
    public void decode(URI audioSource, BooleanSupplier cancelled, PcmSink sink,
                       LongConsumer onDecodedSeconds) throws Exception {
        Path raw = Files.createTempFile("space-harvester-whisper-", ".s16");
        try {
            convert(audioSource, raw, cancelled, sink, onDecodedSeconds);
        } finally {
            Files.deleteIfExists(raw);
        }
    }

    /** The stream-output chain: drop video, resample to Whisper's input, write headerless PCM to {@code target}. */
    static String streamOutput(Path target) {
        return ":sout=#transcode{vcodec=none,acodec=s16l,channels=1,samplerate=" + SAMPLE_RATE
                + "}:std{access=file,mux=raw,dst=" + quote(target.toAbsolutePath().toString()) + "}";
    }

    /**
     * Quote a value for a VLC option chain: single quotes keep backslashes, spaces, commas and braces of a Windows
     * temporary path literal; a path that contains a single quote is double-quoted with escaped {@code \} and
     * {@code "}.
     */
    static String quote(String value) {
        if (value.indexOf('\'') < 0) {
            return "'" + value + "'";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static void convert(URI audioSource, Path raw, BooleanSupplier cancelled, PcmSink sink,
                                LongConsumer onDecodedSeconds) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        MediaPlayerFactory factory = new MediaPlayerFactory("--quiet", "--audio-resampler=" + RESAMPLER);
        try {
            MediaPlayer player = factory.mediaPlayers().newMediaPlayer();
            try {
                player.events().addMediaPlayerEventListener(new MediaPlayerEventAdapter() {
                    @Override
                    public void finished(MediaPlayer mediaPlayer) {
                        completed.countDown();
                    }

                    @Override
                    public void error(MediaPlayer mediaPlayer) {
                        failure.compareAndSet(null, new IOException("VLC could not decode the audio stream"));
                        completed.countDown();
                    }
                });
                if (!player.media().play(audioSource.toASCIIString(), streamOutput(raw),
                        ":no-sout-video", ":no-video")) {
                    throw new IOException("VLC rejected the audio-only MP4");
                }
                try (RandomAccessFile input = new RandomAccessFile(raw.toFile(), "r")) {
                    long delivered = forward(input, 0, sink, completed, failure, cancelled, onDecodedSeconds);
                    // Stopping closes the output file; whatever VLC flushed on the way is still handed on.
                    player.controls().stop();
                    delivered = drain(input, delivered, sink);
                    Throwable cause = failure.get();
                    if (cause != null) {
                        throw new IOException("Audio decoding failed", cause);
                    }
                    if (delivered == 0) {
                        throw new IOException("VLC returned no decoded audio samples");
                    }
                    onDecodedSeconds.accept(delivered / BYTES_PER_SECOND);
                }
            } finally {
                player.controls().stop();
                player.release();
            }
        } finally {
            factory.release();
        }
    }

    /** Hand on new PCM while VLC converts; returns the bytes delivered when VLC finished. */
    private static long forward(RandomAccessFile input, long delivered, PcmSink sink, CountDownLatch completed,
                                AtomicReference<Throwable> failure, BooleanSupplier cancelled,
                                LongConsumer onDecodedSeconds) throws Exception {
        long lastProgress = System.nanoTime();
        long lastReported = -1;
        // Ohne Ton höchstens so lange (Einstellungen › Allgemein › Fristen der App).
        java.time.Duration silence = com.aresstack.spaceharvester.app.ui.AppTimeouts.shared()
                .get(com.aresstack.spaceharvester.app.ui.AppTimeouts.Value.AUDIO_SILENCE);
        while (!completed.await(100, TimeUnit.MILLISECONDS)) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Audio decoding cancelled");
            }
            long before = delivered;
            delivered = drain(input, delivered, sink);
            long now = System.nanoTime();
            if (delivered > before) {
                lastProgress = now;
            } else if (now - lastProgress > silence.toNanos()) {
                throw new IOException("VLC did not deliver audio samples for " + silence.toSeconds() + " seconds");
            }
            long seconds = delivered / BYTES_PER_SECOND;
            if (seconds != lastReported) {
                onDecodedSeconds.accept(seconds);
                lastReported = seconds;
            }
            if (failure.get() != null) {
                break;
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Audio decoding cancelled");
        }
        return delivered;
    }

    /** Forward whole samples from {@code delivered} up to the current end of the growing file. */
    static long drain(RandomAccessFile input, long delivered, PcmSink sink) throws IOException {
        byte[] buffer = new byte[READ_BYTES];
        while (true) {
            long available = input.length() - delivered;
            int count = (int) Math.min(buffer.length, available & ~1L);
            if (count <= 0) {
                return delivered;
            }
            input.seek(delivered);
            input.readFully(buffer, 0, count);
            sink.write(buffer, 0, count);
            delivered += count;
        }
    }
}
