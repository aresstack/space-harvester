package com.aresstack.enterpriseai.astu.acropolis.speech;

/**
 * Speaking rate of the speech output as a factor: 1 = as fast as the model speaks, smaller = slower. Applied in the
 * playback ({@link AudioPlayback#setRate}, time stretching without pitch change), so it works the same for every TTS
 * model, also for models without a speed parameter of their own.
 */
public final class SpeechRate {

    /** Normal speed. */
    public static final double NORMAL = 1.0;
    /** Slowest selectable rate. */
    public static final double MIN = 0.5;
    /** Fastest selectable rate. */
    public static final double MAX = 2.0;

    private SpeechRate() {
    }

    /** {@code rate} limited to {@link #MIN}..{@link #MAX}; invalid values become {@link #NORMAL}. */
    public static double clamp(double rate) {
        if (Double.isNaN(rate) || Double.isInfinite(rate) || rate <= 0) {
            return NORMAL;
        }
        return Math.max(MIN, Math.min(MAX, rate));
    }
}
