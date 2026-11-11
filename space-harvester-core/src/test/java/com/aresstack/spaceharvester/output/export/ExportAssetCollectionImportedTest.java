package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.StoredReadingView;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Export eines importierten Space ohne erreichbare Quelle: Bilder, Anhänge und Lese-Ansichten kommen aus dem, was der
 * Import mitgebracht hat ({@link EnrichmentImportedAssets}), statt dass der Auftrag anhält.
 */
class ExportAssetCollectionImportedTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final byte[] PDF = "%PDF-1.4 handbuch".getBytes(StandardCharsets.US_ASCII);
    private static final String STORAGE = "{\"body\":{\"storage\":{\"value\":\"<p>Bild</p>"
            + "<ac:structured-macro ac:name=\\\"include\\\"><ac:parameter ac:name=\\\"\\\"><ac:link>"
            + "<ri:page ri:content-title=\\\"Eingebunden\\\"/></ac:link></ac:parameter></ac:structured-macro>\"}}}";

    private final Clock clock = Clock.fixed(Instant.parse("2026-11-12T00:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(2, 10, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter()),
            ExportConverters.DEFAULT_MAX_BYTES);
    private final InMemoryEnrichmentStore enrichments = new InMemoryEnrichmentStore();
    private final Unreachable source = new Unreachable();

    private final ExportAssetManifest.Entry picture = new ExportAssetManifest.Entry("100",
            ExportAssetManifest.Reference.attachment("att1", "bild.png"), "att1", "1", "bild.png", "image/png",
            PNG.length, Assets.sha256(PNG), ExportAssetManifest.Status.STORED, null);
    private final ExportAssetManifest.Entry foreign = new ExportAssetManifest.Entry("100",
            ExportAssetManifest.Reference.viewImage("https://wiki.example.org/download/attachments/200/fremd.png"),
            "att7", "1", "fremd.png", "image/png", PNG.length, Assets.sha256(PNG),
            ExportAssetManifest.Status.STORED, null);
    private final ExportAssetManifest.Entry handbook = new ExportAssetManifest.Entry("100",
            ExportAssetManifest.Reference.document("att2", "handbuch.pdf"), "att2", "1", "handbuch.pdf",
            "application/pdf", PDF.length, Assets.sha256(PDF), ExportAssetManifest.Status.STORED, null);

    @TempDir
    Path data;

    private FileSystemExportArtifactWriter writer;

    @Test
    void withoutTheSourceImagesAttachmentsAndReadingViewsComeFromTheImport() {
        ExportAssetCollection images = collection(true, true);

        String exportId = export(images);

        JobStatus collected = jobs.list("client-a", JobType.ASSETS).get(0);
        assertThat(collected.state()).as(String.valueOf(collected)).isEqualTo(JobState.COMPLETED);
        assertThat(collected.incomplete()).isFalse();
        assertThat(collected.progress().written()).isEqualTo(2);
        assertThat(jobs.list("client-a", JobType.ATTACHMENTS)).singleElement().satisfies(documents -> {
            assertThat(documents.state()).isEqualTo(JobState.COMPLETED);
            assertThat(documents.progress().written()).isEqualTo(1);
        });
        ExportAssetManifest manifest = writer().assets(exportId).orElseThrow();
        assertThat(manifest.complete()).isTrue();
        assertThat(manifest.forPage("100")).containsExactly(picture, foreign);
        assertThat(manifest.documents()).containsExactly(handbook);
        assertThat(manifest.forPage("101")).isEmpty();
        ExportAssets stored = images.assets(exportId);
        assertThat(stored.content(picture)).hasValue(PNG);
        assertThat(stored.content(foreign)).hasValue(PNG);
        assertThat(stored.content(handbook)).hasValue(PDF);
        assertThat(writer().dataset(exportId).orElseThrow().readingView("100")).hasValueSatisfying(view -> {
            assertThat(view.available()).isTrue();
            assertThat(view.html()).contains("Eingebunden");
        });
        assertThat(source.calls).isPositive();
    }

    @Test
    void withoutFullSourceAccessForeignImagesAndTheReadingViewStayOut() {
        ExportAssetCollection images = collection(false, true);

        String exportId = export(images);

        assertThat(jobs.list("client-a", JobType.ASSETS).get(0).state()).isEqualTo(JobState.COMPLETED);
        ExportAssetManifest manifest = writer().assets(exportId).orElseThrow();
        assertThat(manifest.forPage("100")).extracting(ExportAssetManifest.Entry::status)
                .containsExactly(ExportAssetManifest.Status.STORED, ExportAssetManifest.Status.FORBIDDEN);
        assertThat(writer().dataset(exportId).orElseThrow().readingView("100")).hasValueSatisfying(view ->
                assertThat(view.status()).isEqualTo(ExportReadingView.Status.RESTRICTED));
    }

    @Test
    void aPageWithoutImportedFilesPausesTheJobAsBefore() {
        ExportAssetCollection images = collection(true, false);

        export(images);

        assertThat(jobs.list("client-a", JobType.ASSETS).get(0).state()).isEqualTo(JobState.PAUSED);
    }

    private String export(ExportAssetCollection images) {
        Exports exports = new Exports(jobs, cache, writer(), converters, clock);
        exports.onSubmitted(images::announce);
        exports.onPublished(images::start);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(
                page("confluence", "DEV", "100", null, STORAGE.getBytes(StandardCharsets.UTF_8)),
                page("confluence", "DEV", "101", "100")));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of("json"));
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
        return export.id();
    }

    /** Was der Import hinterlassen hat: Dateien in der Ablage, Einträge je Seite, die Lese-Ansicht von 100. */
    private ExportAssetCollection collection(boolean sourceWide, boolean withPage101) {
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
        FileSystemAssetStore files = new FileSystemAssetStore(data.resolve("assets"));
        Instant now = clock.instant();
        files.put(new Asset("confluence", "100", "att1", "1", "bild.png", "image/png", AssetKind.IMAGE, PNG.length,
                Assets.sha256(PNG), now), PNG);
        files.put(new Asset("confluence", "100", "att7", "1", "fremd.png", "image/png", AssetKind.IMAGE, PNG.length,
                Assets.sha256(PNG), now), PNG);
        files.put(new Asset("confluence", "100", "att2", "1", "handbuch.pdf", "application/pdf",
                AssetKind.of("application/pdf", "handbuch.pdf"), PDF.length, Assets.sha256(PDF), now), PDF);
        EnrichmentImportedAssets imported = new EnrichmentImportedAssets(enrichments, clock);
        imported.save(new PageKey("confluence", "100"), "3",
                new ExportAssetManifest(true, true, List.of(picture, foreign, handbook)));
        if (withPage101) {
            imported.save(new PageKey("confluence", "101"), "3", new ExportAssetManifest(true, true, List.of()));
        }
        ReadingViewStore views = new ReadingViewStore(enrichments);
        views.save(new StoredReadingView(new PageKey("confluence", "100"), "sha", "3",
                "<p>Bild</p><div>Eingebunden</div>", "https://wiki.example.org", "Bild Eingebunden", List.of(), now));
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer(),
                new SourceQueries(catalog), new Assets(catalog, files, 1_000_000, clock),
                (owner, sourceId) -> sourceWide);
        images.imported(imported, views);
        return images;
    }

    private FileSystemExportArtifactWriter writer() {
        if (writer == null) {
            writer = new FileSystemExportArtifactWriter(data.resolve("exports"));
        }
        return writer;
    }

    /** Eine Quelle ohne Netz: jede Anfrage scheitert als nicht erreichbar. */
    private static final class Unreachable implements HarvestSource {

        int calls;

        private SourceAccessException down() {
            calls++;
            return new SourceAccessException(id(), SourceAccessException.Reason.UNAVAILABLE, "kein Netz", null);
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
            throw down();
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            throw down();
        }

        @Override
        public Map<String, PageAttachments> attachmentsOf(Collection<String> pageIds, String spaceKey) {
            throw down();
        }

        @Override
        public PageAttachments attachments(String pageId) {
            throw down();
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            throw down();
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            throw down();
        }

        @Override
        public ReadingPage readingPage(String pageId) {
            throw down();
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            throw down();
        }
    }
}
