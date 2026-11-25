package com.aresstack.spaceharvester.app.speech;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import static org.assertj.core.api.Assertions.assertThat;

class DictationPreviewWindowTest {
    @Test
    void capturesOnlyBoundedNewUncommittedSamplesWithoutChangingSource() throws Exception {
        float[] data = new float[30 * 16000];
        data[data.length - 1] = 0.5f;
        WhisperAudioSource source = new WhisperAudioSource() {
            @Override public long sampleCount() { return data.length; }
            @Override public float[] readSamples(long start, int count) {
                return Arrays.copyOfRange(data, (int) start, (int) start + count);
            }
        };
        assertThat(DictationPreviewWindow.capture(source, 29 * 16000L, 0)).isNull();
        assertThat(DictationPreviewWindow.capture(source, 0, 29 * 16000L)).isNull();
        DictationPreviewWindow preview = DictationPreviewWindow.capture(source, 0, 0);
        assertThat(preview.sampleCount()).isEqualTo(12 * 16000L);
        data[data.length - 1] = 0.8f;
        assertThat(preview.readSamples(preview.sampleCount() - 1, 1)).containsExactly(0.5f);
        assertThat(source.sampleCount()).isEqualTo(30 * 16000L);
        assertThat(DictationPreviewWindow.capture(source, 25 * 16000L, 0).sampleCount())
                .isEqualTo(5 * 16000L);
    }
}
