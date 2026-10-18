package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetDamagedException;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.ExportNotConvertibleException;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.jobs.UnknownJobException;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.output.convert.MarkdownConverter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Einmal beschaffen, beliebig oft konvertieren: Dateien aus dem Datenbestand, ohne Cache und Quelle. */
class ExportConversionsTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter(),
            new MarkdownConverter()), ExportConverters.DEFAULT_MAX_BYTES);

    @TempDir
    Path data;
    @TempDir
    Path downloads;

    private FileSystemExportArtifactWriter writer() {
        return new FileSystemExportArtifactWriter(data.resolve("exports"));
    }

    private String exportOnce(FileSystemExportArtifactWriter writer) {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null),
                page("confluence", "DEV", "101", "100"), page("confluence", "DEV", "200", null)));
        JobStatus job = new Exports(jobs, cache, writer, converters, clock).start("confluence", "DEV",
                "client-a", List.of("json"));
        return job.id();
    }

    @Test
    void aFinishedExportCanBeConvertedAgainWithoutTheCache() throws IOException {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        // Ab jetzt ist der Cache leer: alles kommt aus dem Datenbestand.
        cache.publish("confluence", "DEV", confirmed("g2"), List.of());
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        ExportDatasetInfo info = conversions.dataset(exportId, "client-a").orElseThrow();
        ExportConversions.ConvertedFile markdown = conversions.convert(exportId, "client-a", "markdown", downloads);
        ExportConversions.ConvertedFile again = conversions.convert(exportId, "client-a", "markdown", downloads);

        assertThat(info.pages()).isEqualTo(3);
        assertThat(info.spaceKey()).isEqualTo("DEV");
        assertThat(info.complete()).isTrue();
        assertThat(info.images()).isEqualTo(ExportDatasetInfo.Images.NOT_COLLECTED);
        assertThat(info.formats()).containsExactly("json", "markdown");
        assertThat(markdown.path().getParent()).isEqualTo(downloads);
        assertThat(markdown.path().getFileName().toString()).startsWith("DEV-").endsWith(".md");
        String text = Files.readString(markdown.path());
        // Baumreihenfolge: 100, darunter 101, dann 200.
        assertThat(text.indexOf("100")).isLessThan(text.indexOf("101"));
        assertThat(text.indexOf("101")).isLessThan(text.indexOf("200"));
        // Nie überschreiben: die zweite Datei bekommt einen Zähler, keine Reste bleiben liegen.
        assertThat(again.path()).isNotEqualTo(markdown.path());
        assertThat(again.sha256()).isEqualTo(markdown.sha256());
        try (Stream<Path> files = Files.list(downloads)) {
            assertThat(files.map(path -> path.getFileName().toString())).hasSize(2)
                    .noneMatch(name -> name.endsWith(".part"));
        }
    }

    @Test
    void theJsonConversionMatchesTheOneMadeDuringTheExport() throws IOException {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        conversions.convert(exportId, "client-a", "json", out);

        byte[] original;
        try (var in = writer.open(exportId, "pages.json").orElseThrow()) {
            original = in.readAllBytes();
        }
        assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo(new String(original, StandardCharsets.UTF_8));
    }

    @Test
    void aForeignExportOrAnUnknownFormatIsRefused() {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        assertThatThrownBy(() -> conversions.convert(exportId, "client-b", "markdown", downloads))
                .isInstanceOf(UnknownJobException.class);
        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "odt", downloads))
                .isInstanceOf(InvalidQueryException.class);
    }

    @Test
    void aTamperedPayloadIsADamagedDatasetNotADifferentFile() throws IOException {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        try (Stream<Path> raw = Files.list(data.resolve("exports").resolve(exportId).resolve("raw"))) {
            Files.writeString(raw.findFirst().orElseThrow(), "{\"verändert\":true}");
        }
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "markdown", downloads))
                .isInstanceOf(ExportDatasetDamagedException.class).hasMessageNotContaining("100");
        try (Stream<Path> files = Files.list(downloads)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void withoutAPublishedDatasetNothingIsConvertible() {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        writer.delete(exportId);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        assertThat(conversions.dataset(exportId, "client-a")).isEmpty();
        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "markdown", downloads))
                .isInstanceOf(ExportNotConvertibleException.class);
    }
}
