package com.aresstack.spaceharvester.app.transcription;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class WhisperSpeechTranscriberTest {

    @Test
    void decodesLittleEndianPcmToWhisperRange() throws Exception {
        byte[] pcm = {0, 0, -1, 127, 0, -128, -1, -1};

        float[] values = WhisperSpeechTranscriber.decode16BitMono(pcm, pcm.length);

        assertThat(values).containsExactly(0f, 32767f / 32768f, -1f, -1f / 32768f);
    }

    @Test
    void rejectsTruncatedPcmSamples() {
        assertThatThrownBy(() -> WhisperSpeechTranscriber.decode16BitMono(new byte[]{1}, 1))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("Incomplete");
    }
}
