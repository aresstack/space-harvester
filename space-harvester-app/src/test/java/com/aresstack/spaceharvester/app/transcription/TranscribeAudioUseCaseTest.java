package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TranscribeAudioUseCaseTest {

    private static final URI AUDIO = URI.create("http://127.0.0.1:8111/stream/audio-only");
    private static final int WINDOW = 8;

    @TempDir
    Path folder;

    /** Angelo, 19.11.2026: the first text must appear while the rest of the audio is still loading. */
    @Test
    void reportsEachWindowWhileTheDecoderIsStillDelivering() throws Exception {
        CountDownLatch firstSegment = new CountDownLatch(1);
        List<TranscriptSegment> segments = new CopyOnWriteArrayList<>();
        AtomicBoolean decoderWaited = new AtomicBoolean();
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase((source, cancelled, sink, progress) -> {
            assertThat(source).isEqualTo(AUDIO);
            sink.write(new byte[WINDOW], 0, WINDOW);
            // Only continue once the first window was recognized: proves recognition does not wait for the end.
            decoderWaited.set(firstSegment.await(10, TimeUnit.SECONDS));
            sink.write(new byte[WINDOW / 2], 0, WINDOW / 2);
        }, windows());

        useCase.execute(AUDIO, folder, () -> false, segment -> {
            segments.add(segment);
            firstSegment.countDown();
        });

        assertThat(decoderWaited).isTrue();
        assertThat(segments).extracting(TranscriptSegment::text).containsExactly("8", "4");
    }

    @Test
    void cancellingKeepsTheSegmentsSoFarAndStopsTheDecoder() throws Exception {
        AtomicBoolean cancel = new AtomicBoolean();
        AtomicBoolean decoderStopped = new AtomicBoolean();
        List<TranscriptSegment> segments = new CopyOnWriteArrayList<>();
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase((source, cancelled, sink, progress) -> {
            sink.write(new byte[WINDOW], 0, WINDOW);
            while (!cancelled.getAsBoolean()) {
                Thread.sleep(10);
            }
            decoderStopped.set(true);
            throw new java.io.InterruptedIOException("Audio decoding cancelled");
        }, windows());

        useCase.execute(AUDIO, folder, cancel::get, segment -> {
            segments.add(segment);
            cancel.set(true);
        });

        assertThat(segments).extracting(TranscriptSegment::text).containsExactly("8");
        assertThat(decoderStopped).isTrue();
    }

    @Test
    void aDecoderFailureStillTranscribesTheAudioThatArrived() {
        List<TranscriptSegment> segments = new CopyOnWriteArrayList<>();
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase((source, cancelled, sink, progress) -> {
            sink.write(new byte[WINDOW + 2], 0, WINDOW + 2);
            throw new IOException("BSCW hat den Video-Bereich nicht geliefert");
        }, windows());

        assertThatThrownBy(() -> useCase.execute(AUDIO, folder, () -> false, segments::add))
                .isInstanceOf(IOException.class).hasMessageContaining("Video-Bereich");
        assertThat(segments).extracting(TranscriptSegment::text).containsExactly("8", "2");
    }

    @Test
    void aWhisperFailureStopsTheDecoder() {
        AtomicBoolean decoderStopped = new AtomicBoolean();
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase((source, cancelled, sink, progress) -> {
            while (!cancelled.getAsBoolean()) {
                Thread.sleep(10);
            }
            decoderStopped.set(true);
        }, (pcm, model, cancelled, onSegment) -> {
            throw new IOException("Transcription failed");
        });

        assertThatThrownBy(() -> useCase.execute(AUDIO, folder, () -> false, segment -> {}))
                .isInstanceOf(IOException.class).hasMessage("Transcription failed");
        assertThat(decoderStopped).isTrue();
    }

    @Test
    void skipsRemoteAccessWhenTaskIsAlreadyCancelled() throws Exception {
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase(
                (source, cancelled, sink, progress) -> {
                    throw new AssertionError("Decoder must not start");
                },
                (pcm, model, cancelled, onSegment) -> {
                    throw new AssertionError("Transcriber must not start");
                });

        useCase.execute(AUDIO, folder, () -> true, segment -> {});
    }

    /** A stand-in for Whisper: one segment per window of {@link #WINDOW} bytes, its text the bytes it got. */
    private static SpeechTranscriber windows() {
        return (pcm, model, cancelled, onSegment) -> {
            byte[] window = new byte[WINDOW];
            long offset = 0;
            int filled;
            while ((filled = fill(pcm, window)) > 0) {
                onSegment.accept(new TranscriptSegment(offset, Integer.toString(filled)));
                offset += filled;
            }
        };
    }

    private static int fill(InputStream input, byte[] target) throws IOException {
        int total = 0;
        int read;
        while (total < target.length && (read = input.read(target, total, target.length - total)) > 0) {
            total += read;
        }
        return total;
    }
}
