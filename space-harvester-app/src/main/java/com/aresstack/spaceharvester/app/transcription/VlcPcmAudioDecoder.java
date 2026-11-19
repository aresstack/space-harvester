package com.aresstack.spaceharvester.app.transcription;

import com.sun.jna.Pointer;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter;
import uk.co.caprica.vlcj.player.base.callback.AudioCallbackAdapter;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;

/**
 * Decode the virtual audio-only MP4 through libVLC into temporary PCM.
 * Never open the original video address or persist its video payload.
 */
public final class VlcPcmAudioDecoder implements AudioPcmDecoder {

    private static final int SAMPLE_RATE = 16_000;
    private static final int BYTES_PER_SAMPLE = 2;
    private static final long NO_AUDIO_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

    @Override
    public Path decode(URI audioSource, BooleanSupplier cancelled) throws Exception {
        return decode(audioSource, cancelled, seconds -> {});
    }

    @Override
    public Path decode(URI audioSource, BooleanSupplier cancelled,
                       LongConsumer onDecodedSeconds) throws Exception {
        Path pcm = Files.createTempFile("space-harvester-whisper-", ".pcm");
        boolean complete = false;
        try {
            capture(audioSource, pcm, cancelled, onDecodedSeconds);
            complete = true;
            return pcm;
        } finally {
            if (!complete) {
                Files.deleteIfExists(pcm);
            }
        }
    }

    private void capture(URI audioSource, Path destination, BooleanSupplier cancelled,
                         LongConsumer onDecodedSeconds) throws Exception {
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicLong receivedSamples = new AtomicLong();
        AtomicLong lastAudioReceived = new AtomicLong(System.nanoTime());

        try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(destination))) {
            MediaPlayerFactory factory = new MediaPlayerFactory("--quiet");
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
                    player.audio().callback("S16N", SAMPLE_RATE, 1, new AudioCallbackAdapter() {
                        @Override
                        public void play(MediaPlayer mediaPlayer, Pointer samples, int count, long presentationTime) {
                            if (count <= 0 || cancelled.getAsBoolean() || failure.get() != null) {
                                return;
                            }
                            if (count > Integer.MAX_VALUE / BYTES_PER_SAMPLE) {
                                failure.compareAndSet(null, new IOException("Invalid VLC audio sample count"));
                                completed.countDown();
                                return;
                            }
                            try {
                                byte[] data = samples.getByteArray(0, count * BYTES_PER_SAMPLE);
                                writePcm(output, data);
                                receivedSamples.addAndGet(count);
                                lastAudioReceived.set(System.nanoTime());
                            } catch (RuntimeException | IOException exception) {
                                failure.compareAndSet(null, exception);
                                completed.countDown();
                            }
                        }
                    });
                    if (!player.media().play(audioSource.toASCIIString(), ":no-video")) {
                        throw new IOException("VLC rejected the audio-only MP4");
                    }
                    awaitCompletion(completed, failure, receivedSamples, lastAudioReceived,
                            cancelled, onDecodedSeconds);
                } finally {
                    player.controls().stop();
                    player.release();
                }
            } finally {
                factory.release();
            }
        }
    }

    private static void awaitCompletion(CountDownLatch completed,
                                        AtomicReference<Throwable> failure,
                                        AtomicLong receivedSamples,
                                        AtomicLong lastAudioReceived,
                                        BooleanSupplier cancelled,
                                        LongConsumer onDecodedSeconds) throws Exception {
        long lastReported = -1;
        while (!completed.await(250, TimeUnit.MILLISECONDS)) {
            long decodedSeconds = receivedSamples.get() / SAMPLE_RATE;
            if (decodedSeconds != lastReported) {
                onDecodedSeconds.accept(decodedSeconds);
                lastReported = decodedSeconds;
            }
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new InterruptedIOException("Audio decoding cancelled");
            }
            if (System.nanoTime() - lastAudioReceived.get() > NO_AUDIO_TIMEOUT_NANOS) {
                throw new IOException("VLC did not deliver audio samples for 60 seconds");
            }
        }
        if (cancelled.getAsBoolean()) {
            throw new InterruptedIOException("Audio decoding cancelled");
        }
        Throwable cause = failure.get();
        if (cause != null) {
            throw new IOException("Audio decoding failed", cause);
        }
        if (receivedSamples.get() == 0) {
            throw new IOException("VLC returned no decoded audio samples");
        }
        onDecodedSeconds.accept(receivedSamples.get() / SAMPLE_RATE);
    }

    private static void writePcm(OutputStream output, byte[] samples) throws IOException {
        if (ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN) {
            for (int i = 0; i + 1 < samples.length; i += 2) {
                byte first = samples[i];
                samples[i] = samples[i + 1];
                samples[i + 1] = first;
            }
        }
        output.write(samples);
    }
}
