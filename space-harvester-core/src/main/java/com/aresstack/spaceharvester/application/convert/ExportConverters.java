package com.aresstack.spaceharvester.application.convert;

import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Die verfügbaren Converter, nach {@link ExportConverter#format()}, und die Größengrenze je konvertierter Datei.
 *
 * @see ExportConverter
 */
public final class ExportConverters {

    /** Standardgrenze je konvertierter Datei. */
    public static final long DEFAULT_MAX_BYTES = 512L * 1024 * 1024;

    private final Map<String, ExportConverter> byFormat = new LinkedHashMap<>();
    private final long maxBytes;

    public ExportConverters(Collection<? extends ExportConverter> converters, long maxBytes) {
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes muss positiv sein");
        }
        this.maxBytes = maxBytes;
        Set<String> fileNames = new HashSet<>();
        for (ExportConverter converter : converters) {
            Objects.requireNonNull(converter, "converter");
            if (byFormat.putIfAbsent(converter.format(), converter) != null) {
                throw new IllegalArgumentException("Format " + converter.format() + " doppelt registriert");
            }
            if (!fileNames.add(converter.fileName())) {
                throw new IllegalArgumentException("Dateiname " + converter.fileName() + " doppelt registriert");
            }
        }
    }

    /** Keine Converter: Exporte liefern nur das Raw-First-Artefakt. */
    public static ExportConverters none() {
        return new ExportConverters(List.of(), DEFAULT_MAX_BYTES);
    }

    public long maxBytes() {
        return maxBytes;
    }

    public Set<String> formats() {
        return Set.copyOf(byFormat.keySet());
    }

    /**
     * Die Converter zu den angefragten Formaten, in angefragter Reihenfolge und ohne Doppelte.
     *
     * @throws InvalidQueryException bei einem unbekannten Format
     */
    public List<ExportConverter> select(Collection<String> formats) {
        Map<String, ExportConverter> selected = new LinkedHashMap<>();
        for (String format : formats) {
            ExportConverter converter = byFormat.get(format);
            if (converter == null) {
                throw new InvalidQueryException("Unbekanntes Exportformat " + format + "; verfügbar: "
                        + String.join(", ", byFormat.keySet()));
            }
            selected.putIfAbsent(format, converter);
        }
        return List.copyOf(selected.values());
    }
}
