package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
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
import java.io.OutputStream;
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
    void notesOfTheConverterComeWithTheFileOncePerKindAndPlace() throws IOException {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        ExportConverter noting = new ExportConverter() {
            @Override
            public String format() {
                return "notiz";
            }

            @Override
            public String version() {
                return "1";
            }

            @Override
            public String mediaType() {
                return "text/plain";
            }

            @Override
            public String fileName() {
                return "notiz.txt";
            }

            @Override
            public void convert(ConversionInput input, OutputStream out) throws IOException {
                input.forEachPage(page -> {
                    input.report(new ConversionNotice(page.header().key().pageId(),
                            ConversionNotice.Kind.MISSING_IMAGE, "a.png", "missing"));
                    input.report(new ConversionNotice(page.header().key().pageId(),
                            ConversionNotice.Kind.MISSING_IMAGE, "a.png", "missing"));
                });
                input.report(new ConversionNotice("100", ConversionNotice.Kind.MISSING_DIAGRAM, "mermaid",
                        "render-failed"));
                out.write('x');
            }
        };
        ExportConversions conversions = new ExportConversions(jobs, writer, new ExportConverters(List.of(
                new JsonArrayConverter(), noting), ExportConverters.DEFAULT_MAX_BYTES));

        ExportConversions.ConvertedFile file = conversions.convert(exportId, "client-a", "notiz", downloads);

        assertThat(file.noticeCount()).isEqualTo(4);
        assertThat(file.notices()).extracting(ConversionNotice::pageId).containsExactly("100", "101", "200", "100");
        assertThat(file.notices().get(3).kind()).isEqualTo(ConversionNotice.Kind.MISSING_DIAGRAM);
        assertThat(conversions.convert(exportId, "client-a", "json", downloads).notices()).isEmpty();
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

        // Die Meldung nennt keine Seiten-ID; die Export-ID (UUID) kann zufällig „100“ enthalten und zählt nicht
        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "markdown", downloads))
                .isInstanceOf(ExportDatasetDamagedException.class)
                .satisfies(e -> assertThat(e.getMessage().replace(exportId, "")).doesNotContain("100"));
        try (Stream<Path> files = Files.list(downloads)) {
            assertThat(files).isEmpty();
        }
    }

    @Test
    void aTreeNamingAPageTwiceOrAMissingFileIsADamagedDataset() throws IOException {
        FileSystemExportArtifactWriter writer = writer();
        String exportId = exportOnce(writer);
        Path tree = data.resolve("exports").resolve(exportId).resolve("tree.json");
        String original = Files.readString(tree);
        // 101 doppelt statt 200: gleiche Zahl an Knoten, aber 200 fehlt.
        Files.writeString(tree, original.replace("\"id\":\"200\"", "\"id\":\"101\""));
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "markdown", downloads))
                .isInstanceOf(ExportDatasetDamagedException.class);
        Files.delete(tree);
        assertThatThrownBy(() -> conversions.convert(exportId, "client-a", "markdown", downloads))
                .isInstanceOf(ExportDatasetDamagedException.class);
    }

    @Test
    void theSpaceNameComesFromThePagesAndOtherwiseIsTheKey() {
        FileSystemExportArtifactWriter writer = writer();
        cache.publish("confluence", "PAR", confirmed("g2"), List.of(page("confluence", "PAR", "300", null,
                "{\"space\":{\"key\":\"PAR\",\"name\":\"Paritätsprüfung\"}}".getBytes(
                        java.nio.charset.StandardCharsets.UTF_8))));
        String named = new Exports(jobs, cache, writer, converters, clock).start("confluence", "PAR", "client-a",
                List.of("json")).id();
        String unnamed = exportOnce(writer);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters);

        assertThat(writer.dataset(named).orElseThrow().spaceName()).isEqualTo("Paritätsprüfung");
        assertThat(writer.dataset(unnamed).orElseThrow().spaceName()).isEqualTo("DEV");
        assertThat(conversions.dataset(named, "client-a")).isPresent();
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
