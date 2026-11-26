package com.aresstack.spaceharvester.app.speech;

import com.aresstack.windirectml.inference.whisper.WhisperAudioSource;

import java.io.IOException;
import java.util.Arrays;

/** Ein eingefrorener Ausschnitt [start, end) der laufenden Aufnahme; die Aufnahme selbst bleibt unverändert. */
final class AudioWindow implements WhisperAudioSource {
    private final float[] samples;

    private AudioWindow(float[] samples) {
        this.samples = samples;
    }

    static AudioWindow capture(WhisperAudioSource source, long start, long end) throws IOException {
        long from = Math.max(0, start);
        long to = Math.min(end, source.sampleCount());
        if (to <= from) {
            return new AudioWindow(new float[0]);
        }
        return new AudioWindow(source.readSamples(from, Math.toIntExact(to - from)));
    }

    @Override
    public long sampleCount() {
        return samples.length;
    }

    @Override
    public float[] readSamples(long start, int count) {
        if (start < 0 || count < 0 || start > samples.length || count > samples.length - start) {
            throw new IllegalArgumentException("Invalid window range");
        }
        return Arrays.copyOfRange(samples, (int) start, (int) start + count);
    }

    /** Die wachsende Aufnahme ab {@code offset}: für den Wechsel von „live“ zurück auf „erst komplett“. */
    static WhisperAudioSource from(WhisperAudioSource growing, long offset) {
        return new WhisperAudioSource() {
            @Override
            public long sampleCount() {
                return Math.max(0, growing.sampleCount() - offset);
            }

            @Override
            public float[] readSamples(long start, int count) throws IOException {
                return growing.readSamples(offset + start, count);
            }
        };
    }
}
