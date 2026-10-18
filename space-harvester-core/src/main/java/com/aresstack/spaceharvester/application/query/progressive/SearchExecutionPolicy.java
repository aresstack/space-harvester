package com.aresstack.spaceharvester.application.query.progressive;

import java.time.Duration;
import java.util.Objects;

/**
 * Wie die Varianten laufen (ADR 0017): in Wellen von {@code waveSize}, davon höchstens {@code parallelism} zugleich,
 * jede höchstens {@code variantTimeout} lang, bei einem vorübergehenden Fehler {@code retries} weitere Versuche.
 * Ergebnisse werden in der Reihenfolge der Prioritäten freigegeben; eine Variante, die ihre Frist überschreitet, gilt
 * als gescheitert und gibt die nachrangigen frei, ihre verspäteten Treffer werden dennoch einsortiert.
 */
public record SearchExecutionPolicy(int waveSize, int parallelism, Duration variantTimeout, int retries) {

    public static final int DEFAULT_WAVE_SIZE = 3;
    public static final Duration DEFAULT_VARIANT_TIMEOUT = Duration.ofSeconds(10);
    public static final int DEFAULT_RETRIES = 1;

    public SearchExecutionPolicy {
        if (waveSize < 1 || waveSize > 16) {
            throw new IllegalArgumentException("waveSize muss zwischen 1 und 16 liegen");
        }
        if (parallelism < 1 || parallelism > 16) {
            throw new IllegalArgumentException("parallelism muss zwischen 1 und 16 liegen");
        }
        Objects.requireNonNull(variantTimeout, "variantTimeout");
        if (variantTimeout.isNegative() || variantTimeout.isZero()) {
            throw new IllegalArgumentException("variantTimeout muss positiv sein");
        }
        if (retries < 0 || retries > 3) {
            throw new IllegalArgumentException("retries muss zwischen 0 und 3 liegen");
        }
    }

    /** Drei Varianten je Welle, alle zugleich, 10 s je Variante, ein weiterer Versuch. */
    public static SearchExecutionPolicy defaults() {
        return new SearchExecutionPolicy(DEFAULT_WAVE_SIZE, DEFAULT_WAVE_SIZE, DEFAULT_VARIANT_TIMEOUT,
                DEFAULT_RETRIES);
    }
}
