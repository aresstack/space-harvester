package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class GrowingPcmAudioSourceTest {

    @Test
    void growsWithAppendedPcmAndReadsWindowsAtAbsolutePositions() throws Exception {
        try (GrowingPcmAudioSource source = new GrowingPcmAudioSource(() -> false)) {
            assertThat(source.sampleCount()).isZero();
            source.append(new byte[]{0, 0, -1, 127, 0}, 0, 5);
            assertThat(source.sampleCount()).isEqualTo(2);
            source.append(new byte[]{-128, -1, -1}, 0, 3);
            assertThat(source.sampleCount()).isEqualTo(4);
            assertThat(source.readSamples(1, 2)).containsExactly(32767f / 32768f, -1f);
            assertThat(source.readSamples(3, 1)).containsExactly(-1f / 32768f);
        }
    }

    @Test
    void stopsReadingOnceTheTranscriptionIsCancelled() throws Exception {
        AtomicBoolean cancelled = new AtomicBoolean();
        try (GrowingPcmAudioSource source = new GrowingPcmAudioSource(cancelled::get)) {
            source.append(new byte[8], 0, 8);
            source.readSamples(0, 4);
            cancelled.set(true);
            assertThatThrownBy(() -> source.readSamples(0, 4)).isInstanceOf(CancellationException.class);
        }
    }
}
