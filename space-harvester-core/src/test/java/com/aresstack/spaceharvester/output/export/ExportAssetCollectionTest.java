package com.aresstack.spaceharvester.output.export;

import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.confirmed;
import static com.aresstack.spaceharvester.application.query.InMemoryPageCache.page;
import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.convert.ConversionInput;
import com.aresstack.spaceharvester.application.convert.ExportAssetManifest;
import com.aresstack.spaceharvester.application.convert.ExportAssets;
import com.aresstack.spaceharvester.application.convert.ExportConverters;
import com.aresstack.spaceharvester.application.convert.ExportReadingView;
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
    private static final byte[] MXFILE = ("<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>").getBytes(StandardCharsets.UTF_8);
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
        Assets assets = new Assets(catalog, new FileSystemAssetStore(data.resolve("assets")), 1_000_000, clock);
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets,
                (owner, sourceId) -> true);
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
        // 100: Anhänge bild.png und ablauf.drawio, ein Bild der Lese-Ansicht, das kein Anhang ist; 101: ein fehlender
        // Anhang.
        assertThat(collection.progress().discovered()).isEqualTo(4);
        assertThat(collection.progress().written()).isEqualTo(3);
        assertThat(collection.progress().failed()).isEqualTo(1);
        ExportDatasetInfo info = conversions.dataset(export.id(), "client-a").orElseThrow();
        assertThat(info.images()).isEqualTo(ExportDatasetInfo.Images.INCOMPLETE);
        ExportAssetManifest manifest = writer.assets(export.id()).orElseThrow();
        assertThat(manifest.complete()).isFalse();
        assertThat(manifest.forPage("100")).extracting(entry -> entry.reference().kind())
                .containsExactly(ExportAssetManifest.Kind.ATTACHMENT, ExportAssetManifest.Kind.ATTACHMENT,
                        ExportAssetManifest.Kind.VIEW_IMAGE, ExportAssetManifest.Kind.MERMAID);
        ExportAssetManifest.Entry picture = manifest.forPage("100").get(0);
        ExportAssetManifest.Entry drawio = manifest.forPage("100").get(1);
        ExportAssetManifest.Entry view = manifest.forPage("100").get(2);
        ExportAssetManifest.Entry mermaid = manifest.forPage("100").get(3);
        assertThat(mermaid.reference().source()).isEqualTo("graph TD; A-->B");
        // Diagramme werden beim Beschaffen einmal gezeichnet und mit abgelegt (#190), Fotos nicht.
        assertThat(picture.rendition()).isNull();
        assertThat(drawio.rendition().drawn()).isTrue();
        assertThat(drawio.rendition().mediaType()).isEqualTo("image/svg+xml");
        assertThat(mermaid.rendition().drawn()).isTrue();
        assertThat(mermaid.rendition().mediaType()).isEqualTo("image/png");
        assertThat(manifest.forPage("101")).singleElement().satisfies(entry -> {
            assertThat(entry.status()).isEqualTo(ExportAssetManifest.Status.MISSING);
            assertThat(entry.stored()).isFalse();
        });
        // Die Bytes kommen aus dem Speicher, nie wieder von der Quelle.
        int downloads = source.downloads;
        ExportAssets stored = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets,
                (owner, sourceId) -> false).assets(export.id());
        assertThat(stored.content(picture)).hasValue(PNG);
        assertThat(stored.content(drawio)).hasValue(MXFILE);
        assertThat(stored.content(view)).hasValue(PNG);
        assertThat(stored.content(mermaid)).hasValue("graph TD; A-->B".getBytes(StandardCharsets.UTF_8));
        assertThat(mermaid.stored()).isTrue();
        assertThat(stored.rendition(picture)).isEmpty();
        assertThat(stored.rendition(drawio)).hasValueSatisfying(svg -> assertThat(new String(svg,
                StandardCharsets.UTF_8)).contains("<svg").contains("Start"));
        assertThat(stored.rendition(mermaid)).hasValueSatisfying(png -> assertThat(png).startsWith(PNG[0], PNG[1],
                PNG[2], PNG[3]));
        assertThat(stored.content(manifest.forPage("101").get(0))).isEmpty();
        assertThat(source.downloads).isEqualTo(downloads);

        // Die Lese-Ansicht liegt im Datenbestand, nur zur exportierten Version (#218).
        ConversionInput dataset = writer.dataset(export.id()).orElseThrow();
        assertThat(dataset.readingView("100")).hasValueSatisfying(reading -> {
            assertThat(reading.available()).isTrue();
            assertThat(reading.version()).isEqualTo("3");
            assertThat(reading.html()).contains("Eingebunden");
            assertThat(reading.text()).isEqualTo("Bild Eingebunden");
        });
        assertThat(dataset.readingView("101")).hasValueSatisfying(reading -> {
            assertThat(reading.status()).isEqualTo(ExportReadingView.Status.UNAVAILABLE);
            assertThat(reading.reason()).isEqualTo("page-changed");
            assertThat(reading.text()).isEmpty();
        });
        assertThat(dataset.readingView("999")).isEmpty();
    }

    @Test
    void imagesTheSourceRendersFromOtherPagesNeedTheWholeSource() {
        assertThat(foreignImages(false)).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo(ExportAssetManifest.Status.FORBIDDEN);
            assertThat(entry.reason()).isEqualTo("foreign-content");
        }).hasSize(2);
        assertThat(source.downloads).isZero();
        // Ohne Freigabe der ganzen Quelle bleibt auch die Ansicht ohne Inhalt.
        assertThat(foreignView).hasValueSatisfying(view -> {
            assertThat(view.status()).isEqualTo(ExportReadingView.Status.RESTRICTED);
            assertThat(view.html()).isEmpty();
        });

        assertThat(foreignImages(true)).allSatisfy(entry -> assertThat(entry.stored()).isTrue()).hasSize(2);
        assertThat(foreignView).hasValueSatisfying(view -> assertThat(view.text()).isEqualTo("Geheimer Text"));
    }

    private Optional<ExportReadingView> foreignView;

    /** Seite 102 bindet eine andere Seite ein; ihre Ansicht zeigt einen fremden Anhang und ein gerendertes Bild. */
    private List<ExportAssetManifest.Entry> foreignImages(boolean sourceWide) {
        FileSystemExportArtifactWriter writer = new FileSystemExportArtifactWriter(data.resolve("exports-"
                + sourceWide));
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return Optional.of(source);
            }
        };
        Assets assets = new Assets(catalog, new FileSystemAssetStore(data.resolve("assets-" + sourceWide)), 1000,
                clock);
        ExportAssetCollection images = new ExportAssetCollection(jobs, writer, new SourceQueries(catalog), assets,
                (owner, sourceId) -> sourceWide && owner.equals("client-a"));
        Exports exports = new Exports(jobs, cache, writer, converters, clock);
        exports.onPublished(images::start);
        String include = "{\"body\":{\"storage\":{\"value\":\"<ac:structured-macro ac:name=\\\"include\\\">"
                + "<ac:parameter ac:name=\\\"\\\"><ac:link><ri:page ri:content-title=\\\"Geheim\\\"/></ac:link>"
                + "</ac:parameter></ac:structured-macro>\"}}}";
        cache.publish("confluence", "DEV", confirmed("g-" + sourceWide), List.of(
                page("confluence", "DEV", "102", null, include.getBytes(StandardCharsets.UTF_8))));
        JobStatus export = exports.start("confluence", "DEV", "client-a", List.of("json"));
        drain();
        foreignView = writer.dataset(export.id()).orElseThrow().readingView("102");
        return writer.assets(export.id()).orElseThrow().forPage("102");
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
                list.add(new PageAttachment("att3", "confluence:wiki/attachment/att3", "ablauf.drawio",
                        "application/vnd.jgraph.mxfile", (long) MXFILE.length, "2", null, null));
                list.add(new PageAttachment("att2", "confluence:wiki/attachment/att2", "handbuch.pdf",
                        "application/pdf", 10L, "1", null, null));
            } else if (pageId.equals("101")) {
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
            return new AttachmentFile(attachment, attachment.attachmentId().equals("att3") ? MXFILE : PNG, Instant.parse("2026-10-18T01:00:00Z"));
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            if (pageId.equals("102")) {
                return List.of(new ViewImage("https://wiki.example.org/download/attachments/200/geheim.png",
                        "geheim.png", "att5", "image/png", "1"), new ViewImage(
                        "https://wiki.example.org/plugins/servlet/status/1.png", "Status", null, null, null));
            }
            if (!pageId.equals("100")) {
                return List.of();
            }
            return List.of(new ViewImage("https://wiki.example.org/download/attachments/100/bild.png", "bild.png",
                    "att1", "image/png", "1"), new ViewImage("https://wiki.example.org/plugins/servlet/mermaid/1.png",
                    "Diagramm", null, null, null));
        }

        /** 100: Ansicht mit eingebundenem Inhalt; 101 hat sich seit dem Export geändert. */
        @Override
        public ReadingPage readingPage(String pageId) {
            return switch (pageId) {
                case "100" -> new ReadingPage("3", "<p>Bild</p><div>Eingebunden</div>", "https://wiki.example.org",
                        "Bild Eingebunden", viewImages(pageId));
                case "101" -> new ReadingPage("4", "<p>neu</p>", "", "neu", viewImages(pageId));
                default -> new ReadingPage("3", "<div>Geheimer Text</div>", "", "Geheimer Text", viewImages(pageId));
            };
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            downloads++;
            return PNG;
        }
    }
}
