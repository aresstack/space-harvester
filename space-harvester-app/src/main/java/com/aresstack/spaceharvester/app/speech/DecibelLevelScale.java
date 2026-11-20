package com.aresstack.spaceharvester.app.speech;

/**
 * Die logarithmische Pegelskala (dB) aus askai-java8 (arch, {@code DecibelLevelScale}) für Pegelbalken, Wellenform
 * und Signalschwelle: 0 % = -60 dB, 100 % = Vollausschlag. Linear wäre normale Sprache neben einem Klopfen aufs
 * Mikrofon kaum zu sehen.
 */
final class DecibelLevelScale {

    /** Untergrenze der Anzeige: -60 dB (bezogen auf 16 Bit Vollausschlag) = 0 %. */
    static final double FLOOR_DB = 60.0;

    private DecibelLevelScale() {
    }

    /** Prozent (0–100) für einen 16-Bit-Spitzenbetrag. */
    static int percentFromPeak(int peakMagnitude) {
        if (peakMagnitude <= 0) {
            return 0;
        }
        double db = 20.0 * Math.log10(Math.min(peakMagnitude, Short.MAX_VALUE) / (double) Short.MAX_VALUE);
        double percent = (db + FLOOR_DB) * (100.0 / FLOOR_DB);
        return (int) Math.max(0, Math.min(100, Math.round(percent)));
    }
}
