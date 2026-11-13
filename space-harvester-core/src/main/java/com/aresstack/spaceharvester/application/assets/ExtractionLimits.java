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
 * @param memoryLimits         Schranke je Medientyp für Formate, deren Speicherbedarf mit der Datei wachsen kann
 *                             ({@link StatusReason#MEMORY_LIMIT}, Status {@link ExtractionStatus#EXCLUDED}); Schlüssel
 *                             {@code *} gilt für alle nicht genannten Typen, {@code 0} heißt ohne Schranke
 */
public record ExtractionLimits(long maxFileBytes, long maxChars, long unpackRatio, long unpackRatioFromBytes,
                               long textRatio, long textRatioFromChars, int maxDepth, int maxEmbeddedFiles,
                               Duration idleTimeout, Duration totalTimeout, long tempSpaceReserve, int parallel,
                               java.util.Map<String, Long> memoryLimits) {

    private static final long MIB = 1024L * 1024;

    /** Schlüssel in {@link #memoryLimits()} für alle nicht genannten Medientypen. */
    public static final String ANY_TYPE = "*";

    /**
     * Schranke {@code memory-limit} nach Medientyp (ADR 0026, Punkt 8; Architekt 13.11.2026): ohne Schranke nur die
     * Formate, deren Lesen der Test mit 256 MiB Heap als speicherunabhängig nachweist; alle anderen (Mails, Archive,
     * alte Office-Formate, …) behalten die bisherigen 20 MiB, bis ein Nachweis vorliegt.
     */
    public static final java.util.Map<String, Long> DEFAULT_MEMORY_LIMITS = java.util.Map.ofEntries(
            java.util.Map.entry(ANY_TYPE, 20 * MIB),
            java.util.Map.entry("application/pdf", 0L),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.wordprocessingml.document", 0L),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 0L),
            // Mail mit Anhang über 300 MiB bei 256 MiB Heap (AP7a): Anhänge laufen als Strom über eine temporäre Datei.
            java.util.Map.entry("message/rfc822", 0L),
            // AP3e: als Strom gelesen bzw. zählend in temporäre Dateien entpackt, je eine Datei über 300 MiB bei
            // 256 MiB Heap vollständig (LargeDocumentFilesTest). HTML bleibt bei 20 MiB: Tikas JSoupParser baut den
            // ganzen DOM-Baum, bevor er Text ausgibt (HTML über 300 MiB: FAILED/memory-limit bei 256 MiB Heap).
            java.util.Map.entry("text/plain", 0L),
            java.util.Map.entry("text/csv", 0L),
            java.util.Map.entry("application/vnd.openxmlformats-officedocument.presentationml.presentation", 0L),
            java.util.Map.entry("application/zip", 0L),
            java.util.Map.entry("application/x-zip-compressed", 0L),
            java.util.Map.entry("application/gzip", 0L),
            java.util.Map.entry("application/x-gzip", 0L),
            java.util.Map.entry("application/x-tar", 0L));

    /** Die vorgeschlagenen Standardwerte der ADR. */
    public static final ExtractionLimits DEFAULTS = new ExtractionLimits(0, 0, 100, 100 * MIB, 100, 1_000_000, 10,
            10_000, Duration.ofSeconds(30), Duration.ofMinutes(30), 1024 * MIB, 2, DEFAULT_MEMORY_LIMITS);

    public ExtractionLimits {
        memoryLimits = java.util.Map.copyOf(memoryLimits == null ? java.util.Map.of() : memoryLimits);
        if (memoryLimits.values().stream().anyMatch(value -> value == null || value < 0)) {
            throw new IllegalArgumentException("Schranken dürfen nicht negativ sein (0 = keine Schranke)");
        }
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

    /**
     * Die Schranke {@code memory-limit} für einen Medientyp (ohne Parameter, klein geschrieben); {@code 0} heißt ohne
     * Schranke.
     */
    public long memoryLimit(String mediaType) {
        String type = mediaType == null ? "" : mediaType.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT);
        Long limit = memoryLimits.get(type);
        return limit != null ? limit : memoryLimits.getOrDefault(ANY_TYPE, 0L);
    }

    /**
     * Die Standard-Schranken mit den Einträgen der Konfiguration darüber (Medientyp ohne Parameter, klein geschrieben;
     * {@code *} für alle übrigen); so bleiben die nachgewiesenen Formate ohne Schranke, wenn nur {@code *} geändert
     * wird.
     */
    public static java.util.Map<String, Long> memoryLimits(java.util.Map<String, Long> overrides) {
        java.util.Map<String, Long> merged = new java.util.HashMap<>(DEFAULT_MEMORY_LIMITS);
        if (overrides != null) {
            overrides.forEach((type, limit) -> merged.put(
                    type.split(";", 2)[0].strip().toLowerCase(java.util.Locale.ROOT), limit));
        }
        return java.util.Map.copyOf(merged);
    }

    /** Ob ein Wert die Grenze überschreitet; {@code 0} als Grenze heißt nie. */
    public static boolean exceeds(long value, long limit) {
        return limit > 0 && value > limit;
    }
}
