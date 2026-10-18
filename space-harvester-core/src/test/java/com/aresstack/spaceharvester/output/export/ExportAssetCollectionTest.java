package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.Exports;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.query.InMemoryPageCache;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Bilder gehören zum Datenbestand: eigener Auftrag nach dem Export, Manifest, nie erneut von der Quelle. */
class ExportAssetCollectionTest {

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10, 1, 2, 3};
    private static final String STORAGE = "{\"body\":{\"storage\":{\"value\":\"<p>Bild</p>"
            + "<ac:structured-macro ac:name=\\\"mermaid-cloud\\\"><ac:plain-text-body><![CDATA[graph TD; A-->B]]>"
            + "</ac:plain-text-body></ac:structured-macro>\"}}}";

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-18T02:00:00Z"), ZoneOffset.UTC);
    private final Queue<Runnable> queued = new ArrayDeque<>();
    private final Jobs jobs = new Jobs(new InMemoryJobStore(), queued::add, new JobLimits(2, 10, Duration.ofHours(1)),
            clock);
    private final InMemoryPageCache cache = new InMemoryPageCache();
    private final ExportConverters converters = new ExportConverters(List.of(new JsonArrayConverter()),
            ExportConverters.DEFAULT_MAX_BYTES);
    private final Source source = new Source();

    @TempDir
    Path data;

    private void drain() {
        while (!queued.isEmpty()) {
            queued.poll().run();
        }
    }

    @Test
    void afterTheExportItsImagesAreCollectedOnceAndListedInTheManifest() {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(data.resolve("exports"));
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
        Assets assets = new Assets(catalog, new FileSystemAssetStore(data.resolve("assets")), 1000, clock);
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets);
        Exports exports = new Exports(jobs, cache, writer, converters, clock);
        exports.onPublished(images::start);
        ExportConversions conversions = new ExportConversions(jobs, writer, converters, images);
        cache.publish("confluence", "DEV", confirmed("g1"), List.of(
                page("confluence", "DEV", "100", null, STORAGE.getBytes(StandardCharsets.UTF_8)),
                page("confluence", "DEV", "101", "100")));

        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of("json"));
        queued.poll().run();
        // Der Export ist fertig, die Bilder kommen erst danach.
        assertThat(jobs.status(export.id()).state()).isEqualTo(JobState.COMPLETED);
        assertThat(conversions.dataset(export.id(), "client-a").orElseThrow().images())
                .isEqualTo(ExportDatasetInfo.Images.COLLECTING);
        drain();

        JobStatus collection = jobs.list("client-a", JobType.ASSETS).get(0);
        assertThat(collection.state()).isEqualTo(JobState.COMPLETED);
        // 100: Anhang bild.png und ein Bild der Lese-Ansicht, das kein Anhang ist; 101: ein fehlender Anhang.
        assertThat(collection.progress().discovered()).isEqualTo(3);
        assertThat(collection.progress().written()).isEqualTo(2);
        assertThat(collection.progress().failed()).isEqualTo(1);
        ExportDatasetInfo info = conversions.dataset(export.id(), "client-a").orElseThrow();
        assertThat(info.images()).isEqualTo(ExportDatasetInfo.Images.INCOMPLETE);
        ExportAssetManifest manifest = writer.assets(export.id()).orElseThrow();
        assertThat(manifest.complete()).isFalse();
        assertThat(manifest.forPage("100")).extracting(entry -> entry.reference().kind())
                .containsExactly(ExportAssetManifest.Kind.ATTACHMENT, ExportAssetManifest.Kind.VIEW_IMAGE,
                        ExportAssetManifest.Kind.MERMAID);
        assertThat(manifest.forPage("100").get(2).reference().source()).isEqualTo("graph TD; A-->B");
        assertThat(manifest.forPage("101")).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(ExportAssetManifest.Status.MISSING);
            assertThat(entry.stored()).isFalse();
        });
        // Die Bytes kommen aus dem Speicher, nie wieder von der Quelle.
        int downloads = source.downloads;
        ExportAssets stored = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets)
                .assets(export.id());
        assertThat(stored.content(manifest.forPage("100").get(0))).hasValue(PNG);
        assertThat(stored.content(manifest.forPage("100").get(1))).hasValue(PNG);
        assertThat(stored.content(manifest.forPage("101").get(0))).isEmpty();
        assertThat(source.downloads).isEqualTo(downloads);
    }

    /** Seite 100 mit Bild-Anhang und Ansichtsbild, Seite 101 mit einem gelöschten Anhang. */
    private static final class Source implements HarvestSource {

        int downloads;

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
            return new PageMetadataBatch(List.of(), null);
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            return new PageMetadata(pageId, "confluence:wiki/page/" + pageId, "Seite " + pageId, "DEV", null, "3",
                    null, null, PageMetadata.sorted(Map.of()));
        }

        @Override
        public PageAttachments attachments(String pageId) {
            List<PageAttachment> list = new ArrayList<>();
            if (pageId.equals("100")) {
                list.add(new PageAttachment("att1", "confluence:wiki/attachment/att1", "bild.png", "image/png",
                        (long) PNG.length, "1", null, null));
                list.add(new PageAttachment("att2", "confluence:wiki/attachment/att2", "handbuch.pdf",
                        "application/pdf", 10L, "1", null, null));
            } else {
                list.add(new PageAttachment("att9", "confluence:wiki/attachment/att9", "weg.png", "image/png", 5L,
                        "1", null, null));
            }
            return new PageAttachments(pageId, list, List.of(), "application/json", "test", List.of(),
                    Instant.parse("2026-10-18T01:00:00Z"));
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            if (attachment.attachmentId().equals("att9")) {
                throw new SourceAccessException(id(), SourceAccessException.Reason.NOT_FOUND, "gelöscht", null);
            }
            downloads++;
            return new AttachmentFile(attachment, PNG, Instant.parse("2026-10-18T01:00:00Z"));
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            if (!pageId.equals("100")) {
                return List.of();
            }
            return List.of(new ViewImage("https://wiki.example.org/download/attachments/100/bild.png", "bild.png",
                    "att1", "image/png", "1"), new ViewImage("https://wiki.example.org/plugins/servlet/mermaid/1.png",
                    "Diagramm", null, null, null));
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            downloads++;
            return PNG;
        }
    }
}
