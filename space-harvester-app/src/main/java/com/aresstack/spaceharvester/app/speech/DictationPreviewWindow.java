package com.aresstack.spaceharvester.app.speech;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;
import java.io.IOException;
import java.util.Arrays;

/** Freeze a bounded preview window; never change or consume the underlying recording. */
final class DictationPreviewWindow implements WhisperAudioSource {
    static final int RATE = 16000;
    static final int MINIMUM = 4 * RATE;
    static final int STEP = 2 * RATE;
    static final int MAXIMUM = 12 * RATE;
    private final float[] samples;

    private DictationPreviewWindow(float[] samples) {
        this.samples = samples;
    }

    static DictationPreviewWindow capture(WhisperAudioSource source, long committed, long previous)
            throws IOException {
        long end = source.sampleCount();
        long available = end - committed;
        if (available < MINIMUM || end - previous < STEP) {
            return null;
        }
        int count = (int) Math.min(MAXIMUM, available);
        return new DictationPreviewWindow(source.readSamples(end - count, count));
    }

    @Override public long sampleCount() {
        return samples.length;
    }

    @Override public float[] readSamples(long start, int count) {
        if (start < 0 || count < 0 || start > samples.length || count > samples.length - start) {
            throw new IllegalArgumentException("Invalid preview range");
        }
        return Arrays.copyOfRange(samples, (int) start, (int) start + count);
    }
}
