package com.aresstack.spaceharvester.app.transcription;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Decode only the remote audio stream, recognize it locally, and discard temporary PCM.
 * Keep indexing and transcript persistence outside this use case.
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
        Objects.requireNonNull(audioOnlySource, "audioOnlySource");
        Objects.requireNonNull(modelDirectory, "modelDirectory");
        Objects.requireNonNull(cancelled, "cancelled");
        Objects.requireNonNull(onSegment, "onSegment");
        if (cancelled.getAsBoolean()) {
            return;
        }
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
        Path pcmFile = decoder.decode(audioOnlySource, cancelled, status::onDecodedSeconds);
        try {
            if (!cancelled.getAsBoolean()) {
                status.onRecognitionStarted();
                transcriber.transcribe(pcmFile, modelDirectory, cancelled, onSegment);
            }
        } finally {
            Files.deleteIfExists(pcmFile);
        }
    }
}
