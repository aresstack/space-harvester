package com.aresstack.spaceharvester.application.assets;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Properties;

/**
 * Wie lange eine Datei von der Quelle durchschnittlich zum Laden braucht, getrennt für Bilder und Dateianhänge, über
 * alle Exporte hinweg (Angelo 11.11.2026: Restzeit im Fortschrittsbalken). Gemessen wird nur der Transfer einer
 * tatsächlich geladenen Datei; Treffer aus dem Speicher, Warten auf einen Platz und Seitentexte zählen nicht.
 *
 * <p>Der Durchschnitt beginnt mit einem Startwert, den schon die erste Messung ersetzt; danach mittelt er über die
 * letzten etwa {@value #WINDOW} Dateien, damit er einer langsameren oder schnelleren Verbindung bald folgt. Mit einer
 * Datei bleibt er über Neustarts erhalten (Anwendungszustand, keine Konfiguration); sie wird höchstens alle
 * {@link #SAVE_EVERY} geschrieben und beim Schließen.
 */
public final class DownloadDurations implements AutoCloseable {

    /** Welche Art Datei. */
    public enum Kind {
        /** Bilder: Rasterbilder, Vektorgrafiken, draw.io-Quelldaten und Bilder der Lese-Ansicht. */
        IMAGE,
        /** Dateianhänge: alles andere, etwa PDF oder Office-Dokumente. */
        DOCUMENT;

        /** Die Art eines Anhangs nach Medientyp und Dateiname ({@link AssetKind#of}). */
        public static Kind of(String mediaType, String title) {
            return AssetKind.of(mediaType, title) == AssetKind.OTHER ? DOCUMENT : IMAGE;
        }
    }

    /** Startwert für Bilder, bis die erste Datei geladen ist. */
    public static final Duration DEFAULT_IMAGE = Duration.ofSeconds(1);
    /** Startwert für Dateianhänge, bis der erste geladen ist. */
    public static final Duration DEFAULT_DOCUMENT = Duration.ofSeconds(3);
    /** Über wie viele der letzten Dateien der Durchschnitt etwa mittelt. */
    static final int WINDOW = 50;
    static final Duration SAVE_EVERY = Duration.ofSeconds(10);

    private final Path file;
    private final Clock clock;
    private final Map<Kind, Double> averageMillis = new EnumMap<>(Kind.class);
    private final Map<Kind, Long> counts = new EnumMap<>(Kind.class);
    private Instant savedAt = Instant.MIN;
    private boolean dirty;

    /** Nur im Speicher, etwa im Dienst oder in Tests. */
    public DownloadDurations() {
        this(null, Clock.systemUTC());
    }

    /** @param file wo der Durchschnitt über Neustarts liegt, oder {@code null} für nur im Speicher */
    public DownloadDurations(Path file, Clock clock) {
        this.file = file;
        this.clock = clock;
        averageMillis.put(Kind.IMAGE, (double) DEFAULT_IMAGE.toMillis());
        averageMillis.put(Kind.DOCUMENT, (double) DEFAULT_DOCUMENT.toMillis());
        counts.put(Kind.IMAGE, 0L);
        counts.put(Kind.DOCUMENT, 0L);
        load();
    }

    /** Der aktuelle Durchschnitt; vor der ersten Messung der Startwert. */
    public synchronized Duration average(Kind kind) {
        return Duration.ofMillis(Math.round(averageMillis.get(kind)));
    }

    /** Wie viele geladene Dateien bisher eingeflossen sind. */
    public synchronized long count(Kind kind) {
        return counts.get(kind);
    }

    /** Eine tatsächlich von der Quelle geladene Datei. */
    public void record(Kind kind, Duration took) {
        if (took == null || took.isNegative()) {
            return;
        }
        boolean save;
        synchronized (this) {
            long n = counts.get(kind) + 1;
            double average = averageMillis.get(kind);
            averageMillis.put(kind, average + (took.toMillis() - average) / Math.min(n, WINDOW));
            counts.put(kind, n);
            dirty = true;
            Instant now = clock.instant();
            save = file != null && !now.isBefore(savedAt.plus(SAVE_EVERY));
            if (save) {
                savedAt = now;
            }
        }
        if (save) {
            save();
        }
    }

    /** Schreibt den Stand, wenn sich etwas geändert hat. */
    @Override
    public void close() {
        save();
    }

    private void load() {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            for (Kind kind : Kind.values()) {
                String prefix = kind.name().toLowerCase(java.util.Locale.ROOT);
                String average = properties.getProperty(prefix + ".average-ms");
                String count = properties.getProperty(prefix + ".count");
                if (average != null && count != null) {
                    double millis = Double.parseDouble(average);
                    long n = Long.parseLong(count);
                    if (millis >= 0 && n >= 0) {
                        averageMillis.put(kind, millis);
                        counts.put(kind, n);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Ein unlesbarer Stand kostet nur die Schätzung: es gelten die Startwerte.
            System.getLogger(DownloadDurations.class.getName()).log(System.Logger.Level.INFO,
                    "Download-Dauern nicht gelesen ({0})", e.getClass().getName());
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        Properties properties = new Properties();
        synchronized (this) {
            if (!dirty) {
                return;
            }
            for (Kind kind : Kind.values()) {
                String prefix = kind.name().toLowerCase(java.util.Locale.ROOT);
                properties.setProperty(prefix + ".average-ms", Double.toString(averageMillis.get(kind)));
                properties.setProperty(prefix + ".count", Long.toString(counts.get(kind)));
            }
            dirty = false;
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path part = file.resolveSibling(file.getFileName() + ".part");
            try (OutputStream out = Files.newOutputStream(part)) {
                properties.store(out, "Space Harvester: durchschnittliche Download-Dauer je Datei");
            }
            Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | RuntimeException e) {
            System.getLogger(DownloadDurations.class.getName()).log(System.Logger.Level.INFO,
                    "Download-Dauern nicht geschrieben ({0})", e.getClass().getName());
        }
    }
}
