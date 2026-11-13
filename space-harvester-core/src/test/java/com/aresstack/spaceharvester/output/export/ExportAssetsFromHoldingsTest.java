package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.configuration.ExportSettings;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportRequest;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobNotResumableException;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.signin.SignInRequests;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.application.sources.SourceQueries;
import com.aresstack.spaceharvester.output.convert.JsonArrayConverter;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Bilder, Anhänge und Lese-Ansichten einer Ausgabe aus dem Bestand (ADR 0029): Eine Beschaffung mit Leserecht auf die
 * ganze Quelle legt sie je Seite und Version ab; die Folgeaufträge einer Ausgabe aus dem Bestand lesen nur dort und
 * fragen die Quelle nie.
 */
class ExportAssetsFromHoldingsTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.4 handbuch".getBytes(StandardCharsets.US_ASCII);
    private static final String STORAGE = "{\"body\":{\"storage\":{\"value\":\"<p>Bild</p>"
            + "<ac:structured-macro ac:name=\\\"include\\\"><ac:parameter ac:name=\\\"\\\"><ac:link>"
            + "<ri:page ri:content-title=\\\"Eingebunden\\\"/></ac:link></ac:parameter></ac:structured-macro>\"}}}";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-13T00:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(2, 10, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter()),
            ExportConverters.DEFAULT_MAX_BYTES);
    private final InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();
    private final EnrichmentImportedAssets holdings = new EnrichmentImportedAssets(enrichments, clock);
    private final ReadingViewStore views = new ReadingViewStore(enrichments);
    private final Source source = new Source();

    @TempDir
    Path data;

    private FileSystemExportArtifactWriter writer;
    private ExportAssetCollection images;
    private Exports exports;

    @Test
    void aCollectionFillsTheHoldingsSoThatAnOutputFromThemNeedsNoSource() {
        setUp(true);
        run(exports.start("confluence", "DEV", "client-a", List.of("json")));

        assertThat(holdings.find(new PageKey("confluence", "100"), "3")).hasValueSatisfying(stored -> {
            assertThat(stored.complete()).isTrue();
            assertThat(stored.assets()).extracting(ExportAssetManifest.Entry::title)
                    .containsExactlyInAnyOrder("bild.png", "handbuch.pdf");
            assertThat(stored.assets()).allMatch(ExportAssetManifest.Entry::present);
        });
        assertThat(holdings.find(new PageKey("confluence", "101"), "3")).hasValueSatisfying(stored -> {
            assertThat(stored.complete()).isTrue();
            assertThat(stored.assets()).isEmpty();
        });
        assertThat(views.find(new PageKey("confluence", "100"))).hasValueSatisfying(view -> {
            assertThat(view.version()).isEqualTo("3");
            assertThat(view.html()).contains("Eingebunden");
        });

        source.down = true;
        String exportId = run(exports.start(ExportRequest.fromHoldings("confluence", "DEV", "client-a",
                List.of("json"))));

        assertThat(source.callsWhileDown).isZero();
        JobStatus collected = follow(JobType.ASSETS, exportId);
        assertThat(collected.state()).as(String.valueOf(collected)).isEqualTo(JobState.COMPLETED);
        assertThat(collected.incomplete()).isFalse();
        assertThat(collected.parameter(Exports.HOLDINGS)).isEqualTo("true");
        assertThat(follow(JobType.ATTACHMENTS, exportId).state()).isEqualTo(JobState.COMPLETED);
        ExportAssetManifest manifest = writer.assets(exportId).orElseThrow();
        assertThat(manifest.complete()).isTrue();
        assertThat(manifest.forPage("100")).extracting(ExportAssetManifest.Entry::title).containsExactly("bild.png");
        assertThat(manifest.documents()).extracting(ExportAssetManifest.Entry::title).containsExactly("handbuch.pdf");
        ExportAssets stored = images.assets(exportId);
        assertThat(stored.content(manifest.forPage("100").get(0))).hasValue(PNG);
        assertThat(stored.content(manifest.documents().get(0))).hasValue(PDF);
        assertThat(writer.dataset(exportId).orElseThrow().readingView("100")).hasValueSatisfying(view -> {
            assertThat(view.available()).isTrue();
            assertThat(view.html()).contains("Eingebunden");
        });
    }

    @Test
    void aCollectionWithoutAccessToTheWholeSourceLeavesNothingInTheHoldings() {
        setUp(false);
        run(exports.start("confluence", "DEV", "client-a", List.of("json")));

        assertThat(holdings.find(new PageKey("confluence", "100"), "3")).isEmpty();
        assertThat(views.find(new PageKey("confluence", "100"))).isEmpty();
    }

    @Test
    void anOutputFromTheHoldingsWithoutStoredFilesNamesThePagesAndNeverAsksTheSource() {
        setUp(true);
        source.down = true;

        String exportId = run(exports.start(ExportRequest.fromHoldings("confluence", "DEV", "client-a",
                List.of("json"))));

        assertThat(source.callsWhileDown).isZero();
        JobStatus collected = follow(JobType.ASSETS, exportId);
        // Nicht angehalten wie ein Export mit Quelle: fertig, aber unvollständig, mit der Zahl der Seiten ohne.
        assertThat(collected.state()).as(String.valueOf(collected)).isEqualTo(JobState.COMPLETED);
        assertThat(collected.incomplete()).isTrue();
        assertThat(jobs.events(collected.id(), "client-a")).extracting(JobEvent::code).contains("NOT_IN_HOLDINGS");
        assertThat(writer.assets(exportId).orElseThrow().complete()).isFalse();

        assertThatThrownBy(() -> images.forceRefresh(collected.id(), "client-a"))
                .isInstanceOf(JobNotResumableException.class);
    }

    private void setUp(boolean sourceWide) {
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "confluence".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
        writer = new FileSystemExportArtifactWriter(data.resolve("exports"));
        images = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog),
                new Assets(catalog, new FileSystemAssetStore(data.resolve("assets")), 1_000_000, clock),
                (owner, sourceId) -> sourceWide, SignInRequests.none(), () -> ExportSettings.DEFAULT);
        images.imported(holdings, views);
        exports = new Exports(jobs, cache, writer, converters, clock);
        exports.onSubmitted(images::announce);
        exports.onPublished(images::start);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(
                page("confluence", "DEV", "100", null, STORAGE.getBytes(StandardCharsets.UTF_8)),
                page("confluence", "DEV", "101", "100")));
    }

    private String run(JobStatus export) {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
        return export.id();
    }

    private JobStatus follow(JobType type, String exportId) {
        return jobs.list("client-a", type).stream()
                .filter(job -> exportId.equals(job.parameter(ExportAssetCollection.EXPORT_ID))).findFirst()
                .orElseThrow();
    }

    /** Seite 100 mit einem Bild, einem Handbuch und einer eingebundenen Seite; abschaltbar wie ohne Netz. */
    private static final class Source implements HarvestSource {

        boolean down;
        int callsWhileDown;

        private void reachable() {
            if (down) {
                callsWhileDown++;
                throw new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "kein Netz", null);
            }
        }

        @Override
        public String id() {
            return "confluence";
        }

        @Override
        public String type() {
            return "confluence";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return List.of(new Partition("DEV", "Entwicklung", null));
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            reachable();
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            reachable();
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "3",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public PageAttachments attachments(String pageId) {
            reachable();
            List<PageAttachment> list = "100".equals(pageId)
                    ? List.of(attachment("att1", "bild.png", "image/png", PNG.length),
                    attachment("att2", "handbuch.pdf", "application/pdf", PDF.length))
                    : List.of();
            return new PageAttachments(pageId, list, List.of(), "application/json", "test", List.of(),
                    Instant.parse("2026-11-12T01:00:00Z"));
        }

        private static PageAttachment attachment(String id, String title, String type, long size) {
            return new PageAttachment(id, "confluence:wiki/attachment/" + id, title, type, size, "1", null, null);
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            reachable();
            byte[] content = "att1".equals(attachment.attachmentId()) ? PNG : PDF;
            return new AttachmentFile(attachment, content, Instant.parse("2026-11-12T01:00:00Z"));
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            reachable();
            return List.of();
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            reachable();
            return new ReadingPage("3", "<p>Bild</p><div>Eingebunden</div>", "https://wiki.example.org",
                    "Bild Eingebunden", List.of());
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            reachable();
            return PNG;
        }
    }
}
