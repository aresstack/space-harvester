package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.DownloadDurations.Kind;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Globale Download-Dauer je Datei für die Restzeit, getrennt für Bilder und Anhänge (Angelo 11.11.2026). */
class DownloadDurationsTest {

    @TempDir
    Path home;

    @Test
    void theFirstDownloadReplacesTheStartValueAndLaterOnesAreAveragedPerKind() {
        DownloadDurations durations = new DownloadDurations();
        assertThat(durations.average(Kind.IMAGE)).isEqualTo(DownloadDurations.DEFAULT_IMAGE);
        assertThat(durations.average(Kind.DOCUMENT)).isEqualTo(DownloadDurations.DEFAULT_DOCUMENT);

        durations.record(Kind.IMAGE, Duration.ofMillis(200));
        assertThat(durations.average(Kind.IMAGE)).isEqualTo(Duration.ofMillis(200));
        durations.record(Kind.IMAGE, Duration.ofMillis(400));
        assertThat(durations.average(Kind.IMAGE)).isEqualTo(Duration.ofMillis(300));
        // Anhänge bleiben davon unberührt.
        assertThat(durations.average(Kind.DOCUMENT)).isEqualTo(DownloadDurations.DEFAULT_DOCUMENT);
        assertThat(durations.count(Kind.IMAGE)).isEqualTo(2);
    }

    @Test
    void theAverageFollowsAChangedConnectionWithinTheWindow() {
        DownloadDurations durations = new DownloadDurations();
        for (int i = 0; i < 500; i++) {
            durations.record(Kind.DOCUMENT, Duration.ofMillis(100));
        }
        for (int i = 0; i < 3 * DownloadDurations.WINDOW; i++) {
            durations.record(Kind.DOCUMENT, Duration.ofMillis(1100));
        }
        assertThat(durations.average(Kind.DOCUMENT).toMillis()).isGreaterThan(1000);
    }

    @Test
    void theAverageSurvivesARestartAndAnUnreadableFileFallsBackToTheStartValues() throws Exception {
        Path file = home.resolve("state").resolve("download-durations.properties");
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T13:00:00Z"), ZoneOffset.UTC);
        try (DownloadDurations durations = new DownloadDurations(file, clock)) {
            durations.record(Kind.IMAGE, Duration.ofMillis(700));
            durations.record(Kind.DOCUMENT, Duration.ofMillis(2500));
        }

        DownloadDurations again = new DownloadDurations(file, clock);
        assertThat(again.average(Kind.IMAGE)).isEqualTo(Duration.ofMillis(700));
        assertThat(again.average(Kind.DOCUMENT)).isEqualTo(Duration.ofMillis(2500));
        assertThat(again.count(Kind.DOCUMENT)).isEqualTo(1);

        Files.writeString(file, "image.average-ms=kaputt\nimage.count=3\n");
        assertThat(new DownloadDurations(file, clock).average(Kind.IMAGE)).isEqualTo(DownloadDurations.DEFAULT_IMAGE);
    }

    @Test
    void aFailedWriteIsRepeatedOnClose() throws Exception {
        Path blocked = home.resolve("state");
        Files.writeString(blocked, "kein Verzeichnis");
        Path file = blocked.resolve("download-durations.properties");
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T13:00:00Z"), ZoneOffset.UTC);
        DownloadDurations durations = new DownloadDurations(file, clock);
        durations.record(Kind.IMAGE, Duration.ofMillis(700));
        durations.close();
        assertThat(Files.isRegularFile(file)).isFalse();

        Files.delete(blocked);
        durations.close();
        assertThat(new DownloadDurations(file, clock).average(Kind.IMAGE)).isEqualTo(Duration.ofMillis(700));
    }

    @Test
    void documentsAndImagesAreToldApartLikeTheTwoJobs() {
        assertThat(Kind.of("image/png", "bild.png")).isEqualTo(Kind.IMAGE);
        assertThat(Kind.of("application/vnd.jgraph.mxfile", "ablauf")).isEqualTo(Kind.IMAGE);
        assertThat(Kind.of("application/pdf", "handbuch.pdf")).isEqualTo(Kind.DOCUMENT);
    }
}
