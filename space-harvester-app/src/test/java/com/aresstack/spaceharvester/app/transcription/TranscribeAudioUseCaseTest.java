package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class TranscribeAudioUseCaseTest {

    @TempDir
    Path folder;

    @Test
    void sendsOnlyTheAudioSourceToTheDecoderAndDeletesPcmAfterRecognition() throws Exception {
        Path temporaryPcm = folder.resolve("temporary.pcm");
        Files.write(temporaryPcm, new byte[]{1, 2, 3, 4});
        URI audioOnlyAddress = URI.create("http://127.0.0.1:8111/stream/audio-only");
        List<TranscriptSegment> segments = new ArrayList<>();
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase((source, cancelled) -> {
            assertThat(source).isEqualTo(audioOnlyAddress);
            assertThat(cancelled.getAsBoolean()).isFalse();
            return temporaryPcm;
        }, (pcmFile, modelDirectory, cancelled, onSegment) -> {
            assertThat(Files.readAllBytes(pcmFile)).containsExactly(1, 2, 3, 4);
            onSegment.accept(new TranscriptSegment(30_000, "Beispielsatz"));
        });

        useCase.execute(audioOnlyAddress, folder, () -> false, segments::add);

        assertThat(segments).hasSize(1);
        assertThat(segments.get(0).timeLabel()).isEqualTo("00:00:30");
        assertThat(Files.exists(temporaryPcm)).isFalse();
    }

    @Test
    void deletesTemporaryPcmWhenWhisperFails() throws Exception {
        Path temporaryPcm = folder.resolve("failure.pcm");
        Files.write(temporaryPcm, new byte[]{1, 2});
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase(
                (source, cancelled) -> temporaryPcm,
                (pcmFile, modelDirectory, cancelled, onSegment) -> {
                    throw new IOException("Transcription failed");
                });

        assertThatThrownBy(() -> useCase.execute(
                URI.create("http://127.0.0.1:8111/audio"), folder, () -> false, segment -> {}))
                .isInstanceOf(IOException.class).hasMessage("Transcription failed");
        assertThat(Files.exists(temporaryPcm)).isFalse();
    }

    @Test
    void skipsRemoteAccessWhenTaskIsAlreadyCancelled() throws Exception {
        TranscribeAudioUseCase useCase = new TranscribeAudioUseCase(
                (source, cancelled) -> {
                    throw new AssertionError("Decoder must not start");
                },
                (pcmFile, modelDirectory, cancelled, onSegment) -> {
                    throw new AssertionError("Transcriber must not start");
                });

        useCase.execute(URI.create("http://127.0.0.1:8111/audio"),
                folder, () -> true, segment -> {});
    }
}
