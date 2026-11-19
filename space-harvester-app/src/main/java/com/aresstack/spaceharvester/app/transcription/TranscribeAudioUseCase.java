package com.aresstack.spaceharvester.app.transcription;

import java.io.InterruptedIOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Decode only the remote audio stream and recognize it locally while it is still arriving: every thirty-second
 * window is reported as soon as Whisper recognized it (Angelo, 19.11.2026), not after the whole download.
 * Temporary PCM is discarded at the end. Keep indexing and transcript persistence outside this use case.
 */
public final class TranscribeAudioUseCase {

    private final AudioPcmDecoder decoder;
    private final SpeechTranscriber transcriber;

    public TranscribeAudioUseCase(AudioPcmDecoder decoder, SpeechTranscriber transcriber) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.transcriber = Objects.requireNonNull(transcriber, "transcriber");
    }

    public void execute(URI audioOnlySource, Path modelDirectory, BooleanSupplier cancelled,
                        Consumer<TranscriptSegment> onSegment) throws Exception {
        execute(audioOnlySource, modelDirectory, cancelled, onSegment,
                new TranscriptionStatusListener() {
                    @Override
                    public void onDecodedSeconds(long seconds) {
                    }

                    @Override
                    public void onRecognitionStarted() {
                    }
                });
    }

    /**
     * Run the decoder on its own thread and Whisper on the calling thread. A decoder failure still lets Whisper
     * finish the audio that already arrived before the failure is thrown; cancellation stops both at once, and the
     * segments reported so far stay with the caller.
     */
    public void execute(URI audioOnlySource, Path modelDirectory, BooleanSupplier cancelled,
                        Consumer<TranscriptSegment> onSegment, TranscriptionStatusListener status)
            throws Exception {
        Objects.requireNonNull(audioOnlySource, "audioOnlySource");
        Objects.requireNonNull(modelDirectory, "modelDirectory");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(onSegment, "onSegment");
        Objects.requireNonNull(status, "status");
        if (cancelled.getAsBoolean()) {
            return;
        }
        AtomicBoolean stop = new AtomicBoolean();
        BooleanSupplier stopped = () -> stop.get() || cancelled.getAsBoolean();
        AtomicReference<Throwable> decoderFailure = new AtomicReference<>();
        try (PcmSpool spool = new PcmSpool()) {
            Thread decoding = new Thread(() -> {
                try {
                    decoder.decode(audioOnlySource, stopped, spool, status::onDecodedSeconds);
                } catch (Throwable failure) {
                    decoderFailure.set(failure);
                } finally {
                    spool.finish();
                }
            }, "space-harvester-transcription-decoder");
            decoding.setDaemon(true);
            decoding.start();
            try {
                status.onRecognitionStarted();
                transcriber.transcribe(spool.reader(stopped), modelDirectory, stopped, onSegment);
            } catch (InterruptedIOException interrupted) {
                if (!cancelled.getAsBoolean()) {
                    throw interrupted;
                }
                // Cancelled: the segments reported so far stay with the caller.
            } finally {
                stop.set(true);
                awaitDecoder(decoding);
            }
            if (cancelled.getAsBoolean()) {
                return;
            }
            Throwable failure = decoderFailure.get();
            if (failure instanceof InterruptedIOException) {
                return;
            }
            if (failure instanceof Exception exception) {
                throw exception;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }

    /** The decoder notices {@code stop} within a fraction of a second; never leave it writing into a closed spool. */
    private static void awaitDecoder(Thread decoding) {
        boolean interrupted = false;
        while (decoding.isAlive()) {
            try {
                decoding.join(10_000);
                if (decoding.isAlive()) {
                    decoding.interrupt();
                }
            } catch (InterruptedException wakeUp) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
