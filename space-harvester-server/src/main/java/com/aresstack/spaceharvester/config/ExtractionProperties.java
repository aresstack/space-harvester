package com.aresstack.spaceharvester.config;

import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Schutzgrenzen beim Lesen von Dokumenten unter {@code space-harvester.assets.extraction} (ADR 0026, Punkt 8); was
 * fehlt, kommt aus {@link ExtractionLimits#DEFAULTS}. {@code 0} heißt ohne Grenze. Die Grenzen für enthaltene Dateien
 * (Tiefe, Anzahl, Auspack-Verhältnis) kommen mit deren Umsetzung (AP7) dazu; bis dahin gelten die Standards.
 *
 * @param maxFileSize        Größe je Datei ({@code too-large}, Standard 0)
 * @param maxChars           Zeichen je Dokument (Standard 0)
 * @param textRatio          Zeichen zu Dateigröße (Standard 100)
 * @param textRatioFromChars geprüft ab so vielen Zeichen (Standard 1 000 000)
 * @param idleTimeout        Laufzeit ohne Fortschritt (Standard 30s)
 * @param totalTimeout       Laufzeit insgesamt (Standard 30m)
 * @param tempSpaceReserve   freier Platz für temporäre Dateien (Standard 1GB)
 * @param parallel           gleichzeitige Läufe (Standard 2)
 * @param memoryLimits       Schranke {@code memory-limit} je Medientyp, über den Standards
 */
@ConfigurationProperties("space-harvester.assets.extraction")
public record ExtractionProperties(DataSize maxFileSize, Long maxChars, Long textRatio, Long textRatioFromChars,
        Duration idleTimeout, Duration totalTimeout, DataSize tempSpaceReserve, Integer parallel,
        List<MemoryLimit> memoryLimits) {

    /**
     * Ein Eintrag der Schranke {@code memory-limit}.
     *
     * @param type  Medientyp oder {@code *} für alle übrigen
     * @param limit Schranke, {@code 0} ohne Schranke
     */
    public record MemoryLimit(String type, DataSize limit) {
    }

    /** @throws IllegalArgumentException bei negativen Werten oder einem Eintrag ohne Typ oder Schranke */
    public ExtractionLimits toLimits() {
        ExtractionLimits d = ExtractionLimits.DEFAULTS;
        Map<String, Long> memory = new LinkedHashMap<>();
        for (MemoryLimit entry : memoryLimits == null ? List.<MemoryLimit>of() : memoryLimits) {
            if (entry == null || entry.type() == null || entry.type().isBlank() || entry.limit() == null) {
                throw new IllegalArgumentException(
                        "space-harvester.assets.extraction.memory-limits braucht type und limit");
            }
            memory.put(entry.type(), entry.limit().toBytes());
        }
        return new ExtractionLimits(
                maxFileSize == null ? d.maxFileBytes() : maxFileSize.toBytes(),
                maxChars == null ? d.maxChars() : maxChars,
                d.unpackRatio(),
                d.unpackRatioFromBytes(),
                textRatio == null ? d.textRatio() : textRatio,
                textRatioFromChars == null ? d.textRatioFromChars() : textRatioFromChars,
                d.maxDepth(),
                d.maxEmbeddedFiles(),
                idleTimeout == null ? d.idleTimeout() : idleTimeout,
                totalTimeout == null ? d.totalTimeout() : totalTimeout,
                tempSpaceReserve == null ? d.tempSpaceReserve() : tempSpaceReserve.toBytes(),
                parallel == null ? d.parallel() : parallel,
                ExtractionLimits.memoryLimits(memory));
    }
}
