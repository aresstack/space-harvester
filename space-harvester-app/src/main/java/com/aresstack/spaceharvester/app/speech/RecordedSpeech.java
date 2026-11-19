package com.aresstack.spaceharvester.app.speech;

/** Eine Aufnahme für die Spracheingabe: Abtastwerte in [-1, 1], mono, {@value #SAMPLE_RATE} Hz. Unveränderlich. */
public final class RecordedSpeech {

    /** Abtastrate aller Aufnahmen. */
    public static final int SAMPLE_RATE = 16000;

    private final float[] samples;

    private RecordedSpeech(float[] samples) {
        this.samples = samples;
    }

    /** @param samples mono, {@value #SAMPLE_RATE} Hz, Werte in [-1, 1]; wird kopiert */
    public static RecordedSpeech of(float[] samples) {
        if (samples == null) {
            throw new IllegalArgumentException("samples must not be null");
        }
        return new RecordedSpeech(samples.clone());
    }

    /** Kopie der Abtastwerte. */
    public float[] samples() {
        return samples.clone();
    }

    public int length() {
        return samples.length;
    }

    /** Dauer in Millisekunden. */
    public long durationMillis() {
        return samples.length * 1000L / SAMPLE_RATE;
    }
}
