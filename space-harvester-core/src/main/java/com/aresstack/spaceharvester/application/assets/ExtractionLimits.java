package com.aresstack.spaceharvester.application.assets;

import java.time.Duration;
import java.util.Objects;

/**
 * Schutzgrenzen beim Lesen (ADR 0026, Punkt 8). Größe allein schließt nichts aus; die Grenzen schützen den Dienst und
 * sollen keine normale Datei treffen. {@code 0} (bei Zeiten {@link Duration#ZERO}) heißt: keine Grenze. Geprüft wird
 * vor dem Öffnen (Tiefe, Anzahl) und während des Lesens, bevor Inhalt in den Speicher oder in die Ablage geht. Erreicht
 * ein Teil seine Grenze, endet nur dieser Teil.
 *
 * @param maxFileBytes         Größe je Datei, nur vom Betreiber gesetzt ({@link StatusReason#TOO_LARGE})
 * @param maxChars             Zeichen je Dokument
 * @param unpackRatio          ausgepackt zu gepackt je Behälter ({@link StatusReason#UNPACKED_TOO_LARGE})
 * @param unpackRatioFromBytes geprüft erst ab so vielen ausgepackten Bytes
 * @param textRatio            Zeichen zu Dateigröße je Dokument ({@link StatusReason#TEXT_RATIO_EXCEEDED})
 * @param textRatioFromChars   geprüft erst ab so vielen Zeichen
 * @param maxDepth             Verschachtelungstiefe ({@link StatusReason#TOO_DEEP})
 * @param maxEmbeddedFiles     enthaltene Dateien je äußerem Behälter ({@link StatusReason#TOO_MANY_FILES})
 * @param idleTimeout          Laufzeit ohne Fortschritt je Dokument ({@link StatusReason#TIMEOUT})
 * @param totalTimeout         Laufzeit insgesamt je Dokument ({@link StatusReason#TIMEOUT})
 * @param tempSpaceReserve     freier Platz für temporäre Dateien ({@link StatusReason#TEMP_SPACE_LOW})
 * @param parallel             gleichzeitige Läufe, mindestens 1
 */
public record ExtractionLimits(long maxFileBytes, long maxChars, long unpackRatio, long unpackRatioFromBytes,
                               long textRatio, long textRatioFromChars, int maxDepth, int maxEmbeddedFiles,
                               Duration idleTimeout, Duration totalTimeout, long tempSpaceReserve, int parallel) {

    private static final long MIB = 1024L * 1024;

    /** Die vorgeschlagenen Standardwerte der ADR. */
    public static final ExtractionLimits DEFAULTS = new ExtractionLimits(0, 0, 100, 100 * MIB, 100, 1_000_000, 10,
            10_000, Duration.ofSeconds(30), Duration.ofMinutes(30), 1024 * MIB, 2);

    public ExtractionLimits {
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        Objects.requireNonNull(totalTimeout, "totalTimeout");
        if (maxFileBytes < 0 || maxChars < 0 || unpackRatio < 0 || unpackRatioFromBytes < 0 || textRatio < 0
                || textRatioFromChars < 0 || maxDepth < 0 || maxEmbeddedFiles < 0 || tempSpaceReserve < 0
                || idleTimeout.isNegative() || totalTimeout.isNegative()) {
            throw new IllegalArgumentException("Grenzen dürfen nicht negativ sein (0 = keine Grenze)");
        }
        if (parallel < 1) {
            throw new IllegalArgumentException("Mindestens ein Lauf");
        }
    }

    /** Ob ein Wert die Grenze überschreitet; {@code 0} als Grenze heißt nie. */
    public static boolean exceeds(long value, long limit) {
        return limit > 0 && value > limit;
    }
}
