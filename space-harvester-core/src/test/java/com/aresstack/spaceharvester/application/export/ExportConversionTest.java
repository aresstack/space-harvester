package com.aresstack.spaceharvester.application.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportConverter;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.CachedPage;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.query.PageCacheReader;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Abgeleitete Formate am Ende eines Exports (#30), mit einem Test-Converter statt echter Formate. */
class ExportConversionTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T12:00:00Z"), ZoneOffset.UTC);
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
            clock);
    private final RecordingExportArtifactWriter writer = new RecordingExportArtifactWriter();
    private final InMemoryPageCache cache = new InMemoryPageCache();

    /** Schreibt je Seite eine Zeile „Tiefe Seiten-ID Titel“. */
    private static final class Outline implements ExportConverter {

        @Override
        public String format() {
            return "outline";
        }

        @Override
        public String version() {
            return "outline/1";
        }

        @Override
        public String mediaType() {
            return "text/plain";
        }

        @Override
        public String fileName() {
            return "pages.md";
        }

        @Override
        public void convert(ConversionInput input, OutputStream out) {
            input.forEachPage(page -> {
                try {
                    out.write((page.depth() + " " + page.pageId() + " " + page.header().metadata().title() + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private Exports exports(PageCacheReader reader, long maxBytes) {
        return new Exports(jobs, reader, writer, new ExportConverters(List.of(new Outline()), maxBytes), clock);
    }

    private Exports exports(PageCacheReader reader) {
        return exports(reader, ExportConverters.DEFAULT_MAX_BYTES);
    }

    private void publishTree() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null),
                page("confluence", "DEV", "101", "100"), page("confluence", "DEV", "102", "101"),
                page("confluence", "DEV", "103", "100"), page("confluence", "DEV", "200", null)));
    }

    private Map.Entry<Conversion, byte[]> onlyConversion(JobStatus job) {
        Map<Conversion, byte[]> converted = writer.published.get(job.id()).converted();
        assertThat(converted).hasSize(1);
        return converted.entrySet().iterator().next();
    }

    @Test
    void convertsPagesLinearlyInTreeOrderAndRecordsTheirOrigin() {
        publishTree();

        JobStatus job = exports(cache).start("confluence", "DEV", "client-a", List.of("outline"));

        assertThat(jobs.status(job.id()).state()).isEqualTo(JobState.COMPLETED);
        Map.Entry<Conversion, byte[]> result = onlyConversion(job);
        assertThat(new String(result.getValue(), StandardCharsets.UTF_8).lines().map(l -> l.split(" ", 3))
                .map(parts -> parts[0] + " " + parts[1]).toList())
                .containsExactly("0 100", "1 101", "2 102", "1 103", "0 200");
        Conversion conversion = result.getKey();
        assertThat(conversion.format()).isEqualTo("outline");
        assertThat(conversion.converter()).isEqualTo("outline/1");
        assertThat(conversion.fileName()).isEqualTo("pages.md");
        assertThat(conversion.inputSha256()).startsWith("sha256:");
        // Das Raw-First-Artefakt bleibt wie ohne Format.
        assertThat(writer.published.get(job.id()).pageIds()).containsExactly("100", "101", "102", "103", "200");
        assertThat(writer.published.get(job.id()).summary().complete()).isTrue();
    }

    @Test
    void aRootedExportHashesDifferentlyFromTheWholeSpaceWithTheSameTree() {
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(page("confluence", "DEV", "100", null),
                page("confluence", "DEV", "101", "100")));
        Exports exports = exports(cache);
        JobStatus whole = exports.start("confluence", "DEV", "a", List.of("outline"));
        JobStatus rooted = exports.start("confluence", "DEV", "100", "a", List.of("outline"));

        // Gleicher Baum, aber die Formate beginnen mit der Startseite statt mit dem Space (#186).
        assertThat(writer.published.get(rooted.id()).tree()).isEqualTo(writer.published.get(whole.id()).tree());
        assertThat(onlyConversion(rooted).getKey().inputSha256())
                .isNotEqualTo(onlyConversion(whole).getKey().inputSha256());
    }

    @Test
    void sameInputGivesSameHashAndContentChangedInputAnother() {
        publishTree();
        Exports exports = exports(cache);
        List<String> outline = List.of("outline");
        Map.Entry<Conversion, byte[]> first = onlyConversion(exports.start("confluence", "DEV", "a", outline));
        Map.Entry<Conversion, byte[]> again = onlyConversion(exports.start("confluence", "DEV", "a", outline));

        cache.publish("confluence", "DEV", confirmed("g2"), List.of(page("confluence", "DEV", "100", null),
                page("confluence", "DEV", "101", "100", "{\"id\":\"101\",\"v\":2}".getBytes(StandardCharsets.UTF_8)),
                page("confluence", "DEV", "102", "101"), page("confluence", "DEV", "103", "100"),
                page("confluence", "DEV", "200", null)));
        Map.Entry<Conversion, byte[]> changed = onlyConversion(exports.start("confluence", "DEV", "a", outline));

        assertThat(again.getKey().inputSha256()).isEqualTo(first.getKey().inputSha256());
        assertThat(again.getValue()).isEqualTo(first.getValue());
        assertThat(changed.getKey().inputSha256()).isNotEqualTo(first.getKey().inputSha256());
    }

    @Test
    void withoutFormatNothingIsConverted() {
        publishTree();

        JobStatus job = exports(cache).start("confluence", "DEV", "client-a");

        assertThat(writer.published.get(job.id()).converted()).isEmpty();
    }

    @Test
    void unknownFormatIsRejectedBeforeAJobExists() {
        assertThatThrownBy(() -> exports(cache).start("confluence", "DEV", "client-a", List.of("docx")))
                .isInstanceOf(InvalidQueryException.class).hasMessageContaining("outline");
        assertThat(writer.published).isEmpty();
        assertThat(writer.unpublished()).isEmpty();
    }

    @Test
    void oversizedConversionFailsTheExportInsteadOfTruncating() {
        cache.publish("confluence", "DEV", confirmed("g1"), IntStream.range(0, 50)
                .mapToObj(i -> page("confluence", "DEV", String.format("%03d", i), null)).toList());

        JobStatus job = exports(cache, 100).start("confluence", "DEV", "client-a", List.of("outline"));

        JobStatus status = jobs.status(job.id());
        assertThat(status.state()).isEqualTo(JobState.FAILED);
        assertThat(status.error().code()).isEqualTo("CONVERSION_TOO_LARGE");
        assertThat(writer.published).isEmpty();
        assertThat(writer.discarded).containsExactly(job.id());
    }

    @Test
    void pageChangedBeforeConversionFailsTheExport() {
        publishTree();
        List<String> reads = new ArrayList<>();
        PageCacheReader changing = new ForwardingPageCache(cache) {
            @Override
            public Optional<CachedPage> page(PageKey key) {
                reads.add(key.pageId());
                // Erster Lesedurchgang unverändert, beim Konvertieren ist Seite 103 neuer.
                if (key.pageId().equals("103") && reads.stream().filter("103"::equals).count() > 1) {
                    PageSnapshot newer = InMemoryPageCache.page("confluence", "DEV", "103", "100",
                            "{\"id\":\"103\",\"v\":9}".getBytes(StandardCharsets.UTF_8));
                    return Optional.of(new CachedPage(newer.header(), newer, confirmed("g1")));
                }
                return super.page(key);
            }
        };

        JobStatus job = exports(changing).start("confluence", "DEV", "client-a", List.of("outline"));

        assertThat(jobs.status(job.id()).error().code()).isEqualTo("CHANGED_DURING_EXPORT");
        assertThat(writer.published).isEmpty();
    }
}
