package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class PcmFileAudioSourceTest {

    @Test
    void readsIndependentWindowsAtAbsoluteSamplePositions(@TempDir Path directory) throws Exception {
        Path pcm = Files.write(directory.resolve("audio.pcm"), new byte[]{0, 0, -1, 127, 0, -128, -1, -1});

        try (PcmFileAudioSource source = new PcmFileAudioSource(pcm, () -> false)) {
            assertThat(source.sampleCount()).isEqualTo(4);
            assertThat(source.readSamples(1, 2)).containsExactly(32767f / 32768f, -1f);
            assertThat(source.readSamples(0, 1)).containsExactly(0f);
        }
    }

    @Test
    void stopsReadingOnceTheTranscriptionIsCancelled(@TempDir Path directory) throws Exception {
        Path pcm = Files.write(directory.resolve("audio.pcm"), new byte[8]);
        AtomicBoolean cancelled = new AtomicBoolean();

        try (PcmFileAudioSource source = new PcmFileAudioSource(pcm, cancelled::get)) {
            source.readSamples(0, 4);
            cancelled.set(true);
            assertThatThrownBy(() -> source.readSamples(0, 4)).isInstanceOf(CancellationException.class);
        }
    }
}
