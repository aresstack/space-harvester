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
        execute(audioOnlySource, 0, 0, modelDirectory, cancelled, onSegment, status);
    }

    /**
     * Like {@link #execute(URI, Path, BooleanSupplier, Consumer, TranscriptionStatusListener)}, but resuming at
     * {@code resumeMillis}, where a stopped transcription ended. The audio of the source begins at
     * {@code sourceStartMillis} (at or before the resume point, a chunk start); the decoded PCM up to the resume point
     * is dropped sample for sample, and every window carries its time in the whole video.
     */
    public void execute(URI audioOnlySource, long sourceStartMillis, long resumeMillis, Path modelDirectory,
                        BooleanSupplier cancelled, Consumer<TranscriptSegment> onSegment,
                        TranscriptionStatusListener status) throws Exception {
        Objects.requireNonNull(audioOnlySource, "audioOnlySource");
        if (sourceStartMillis < 0 || resumeMillis < sourceStartMillis) {
            throw new IllegalArgumentException("The source must start at or before the resume point");
        }
        // 16 kHz mono S16LE: 32 bytes per millisecond.
        long skipBytes = (resumeMillis - sourceStartMillis) * 32;
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
                    decoder.decode(audioOnlySource, stopped, skipping(spool, skipBytes),
                            seconds -> status.onDecodedSeconds(sourceStartMillis / 1000 + seconds));
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
                transcriber.transcribe(spool.reader(stopped), modelDirectory, stopped,
                        segment -> onSegment.accept(segment.shifted(resumeMillis)));
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

    /** {@code sink} without the first {@code bytes} bytes of PCM: the audio between the chunk start and the resume point. */
    static PcmSink skipping(PcmSink sink, long bytes) {
        if (bytes <= 0) {
            return sink;
        }
        long[] left = {bytes};
        return (samples, offset, length) -> {
            int drop = (int) Math.min(length, left[0]);
            left[0] -= drop;
            if (length > drop) {
                sink.write(samples, offset + drop, length - drop);
            }
        };
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
