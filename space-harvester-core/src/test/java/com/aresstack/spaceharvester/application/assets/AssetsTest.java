package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentTooLargeException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.sources.SourceCatalog;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetsTest {

    private static final Instant NOW = Instant.parse("2026-10-17T00:00:00Z");

    @TempDir
    Path directory;

    private final FakeSource source = new FakeSource();

    private final CountingDescriber describer = new CountingDescriber();

    private Assets assets(long maxBytes) {
        return assets(maxBytes, null);
    }

    private Assets assets(long maxBytes, ImageDescriber imageDescriber) {
        return assets(maxBytes, imageDescriber, null);
    }

    private Assets assets(long maxBytes, ImageDescriber imageDescriber, DocumentText documents) {
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
        return new Assets(catalog, new FileSystemAssetStore(directory), maxBytes, Clock.fixed(NOW, ZoneOffset.UTC),
                imageDescriber, documents);
    }

    @Test
    void aSignatureInTheContentOverridesAWrongMediaTypeOfTheSource() {
        FakeDocuments documents = new FakeDocuments().type("%PDF", "application/pdf");
        Assets assets = assets(1000, null, documents);
        source.file("att1", "1", "%PDF-Bericht");

        Asset stored = assets.harvest(list(attachment("att1", "scan.png", "image/png", "1"))).entries().get(0)
                .asset();

        assertThat(stored.mediaType()).isEqualTo("application/pdf");
        assertThat(stored.kind()).isEqualTo(AssetKind.OTHER);
        assertThat(assets.content(list(attachment("att1", "scan.png", "image/png", "1")), "att1").asset())
                .isEqualTo(stored);
    }

    @Test
    void textualFormatsKeepTheMediaTypeOfTheSource() {
        // draw.io-Daten sind am Inhalt nur XML; die Angabe der Quelle sagt mehr.
        FakeDocuments documents = new FakeDocuments().type("<mxfile", "application/xml");
        Assets assets = assets(1000, null, documents);
        source.file("att1", "1", "<mxfile/>");

        Asset stored = assets.harvest(list(attachment("att1", "Ablauf", AssetKind.DRAWIO_MEDIA_TYPE, "1")))
                .entries().get(0).asset();

        assertThat(stored.mediaType()).isEqualTo(AssetKind.DRAWIO_MEDIA_TYPE);
        assertThat(stored.kind()).isEqualTo(AssetKind.DIAGRAM);
    }

    @Test
    void theMediaTypeChoiceFollowsTheContentOnlyWhereItIsUnambiguous() {
        assertThat(Assets.chooseMediaType("image/png", "application/pdf")).isEqualTo("application/pdf");
        assertThat(Assets.chooseMediaType(null, "application/pdf")).isEqualTo("application/pdf");
        assertThat(Assets.chooseMediaType("application/octet-stream", "text/plain")).isEqualTo("text/plain");
        assertThat(Assets.chooseMediaType("text/csv; charset=UTF-8", "text/plain"))
                .isEqualTo("text/csv; charset=UTF-8");
        assertThat(Assets.chooseMediaType("application/pdf", "application/pdf")).isEqualTo("application/pdf");
        assertThat(Assets.chooseMediaType("application/vnd.ms-excel", "application/x-tika-msoffice"))
                .isEqualTo("application/vnd.ms-excel");
        assertThat(Assets.chooseMediaType("application/octet-stream", "application/zip"))
                .isEqualTo("application/octet-stream");
        assertThat(Assets.chooseMediaType("image/png", null)).isEqualTo("image/png");
        assertThat(Assets.chooseMediaType("image/svg+xml", "application/xml")).isEqualTo("image/svg+xml");
    }

    @Test
    void documentsGetTheirTextWhenStoredAndKeepItForTheSameBytes() {
        FakeDocuments documents = new FakeDocuments();
        Assets assets = assets(1000, null, documents);
        source.file("att1", "1", "Inhalt des Berichts");
        PageAttachmentList list = list(attachment("att1", "bericht.pdf", "application/pdf", "1"));

        assets.harvest(list);
        AssetText text = assets.text(list, "att1");

        assertThat(text.text()).isEqualTo("Text: Inhalt des Berichts");
        assertThat(text.extractor()).isEqualTo("fake");
        assertThat(text.sha256()).isEqualTo(Assets.sha256("Inhalt des Berichts".getBytes(StandardCharsets.UTF_8)));
        assertThat(documents.extractions).as("einmal beim Ablegen, danach aus der Ablage").hasValue(1);
        assertThat(assets.text(list, "att1")).isEqualTo(text);
        assertThat(documents.extractions).hasValue(1);
    }

    @Test
    void aFailingExtractionDoesNotStopStoringAndIsReportedByReason() {
        FakeDocuments documents = new FakeDocuments().failing(DocumentTextException.Reason.TIMEOUT);
        Assets assets = assets(1000, null, documents);
        source.file("att1", "1", "x");
        PageAttachmentList list = list(attachment("att1", "bericht.pdf", "application/pdf", "1"));

        assertThat(assets.harvest(list).entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
        assertThatThrownBy(() -> assets.text(list, "att1")).isInstanceOf(DocumentTextException.class)
                .extracting(e -> ((DocumentTextException) e).reason())
                .isEqualTo(DocumentTextException.Reason.TIMEOUT);
    }

    @Test
    void imagesHaveNoTextAndWithoutExtractionThereIsNone() {
        source.file("att1", "1", "png");
        PageAttachmentList list = list(attachment("att1", "a.png", "image/png", "1"));

        assertThatThrownBy(() -> assets(1000, null, new FakeDocuments()).text(list, "att1"))
                .extracting(e -> ((DocumentTextException) e).reason())
                .isEqualTo(DocumentTextException.Reason.UNSUPPORTED);
        assertThatThrownBy(() -> assets(1000).text(list, "att1"))
                .extracting(e -> ((DocumentTextException) e).reason())
                .isEqualTo(DocumentTextException.Reason.NOT_CONFIGURED);
    }

    /** Erkennt Typen an einem Präfix und liest „Text: “ plus Inhalt. */
    private static final class FakeDocuments implements DocumentText {

        private final Map<String, String> types = new HashMap<>();
        private DocumentTextException.Reason failure;
        final AtomicInteger extractions = new AtomicInteger();

        FakeDocuments type(String prefix, String mediaType) {
            types.put(prefix, mediaType);
            return this;
        }

        FakeDocuments failing(DocumentTextException.Reason reason) {
            failure = reason;
            return this;
        }

        @Override
        public String id() {
            return "fake";
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public Optional<String> detect(byte[] content) {
            String text = new String(content, StandardCharsets.UTF_8);
            return types.entrySet().stream().filter(type -> text.startsWith(type.getKey())).map(Map.Entry::getValue)
                    .findFirst();
        }

        @Override
        public Extracted extract(String title, String mediaType, byte[] content) {
            extractions.incrementAndGet();
            if (failure != null) {
                throw new DocumentTextException(failure, "Fehler", null);
            }
            return new Extracted("Text: " + new String(content, StandardCharsets.UTF_8), false);
        }
    }

    @Test
    void aNewVersionIsDownloadedAgainTheSameVersionNot() {
        Assets assets = assets(1000);
        source.file("att1", "1", "v1");

        assertThat(assets.harvest(list(attachment("att1", "a.png", "image/png", "1"))).entries().get(0).status())
                .isEqualTo(AssetHarvest.Status.STORED);
        assertThat(assets.harvest(list(attachment("att1", "a.png", "image/png", "1"))).entries().get(0).status())
                .isEqualTo(AssetHarvest.Status.REUSED);
        source.file("att1", "2", "v2");
        AssetHarvest newer = assets.harvest(list(attachment("att1", "a.png", "image/png", "2")));

        assertThat(newer.entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
        assertThat(newer.entries().get(0).asset().version()).isEqualTo("2");
        assertThat(source.downloads).isEqualTo(2);
        assertThat(new String(assets.content(list(attachment("att1", "a.png", "image/png", "2")), "att1")
                .content(), StandardCharsets.UTF_8)).isEqualTo("v2");
        assertThat(source.downloads).isEqualTo(2);
    }

    @Test
    void readingWithoutStoreRightServesButDoesNotStore() {
        Assets assets = assets(1000);
        source.file("att1", "1", "v1");
        PageAttachmentList list = list(attachment("att1", "a.png", "image/png", "1"));

        assertThat(assets.content(list, "att1", false).content()).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(assets.content(list, "att1", false).content()).isEqualTo("v1".getBytes(StandardCharsets.UTF_8));
        assertThat(source.downloads).isEqualTo(2);
        assertThat(assets.harvest(list).entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
    }

    @Test
    void withoutVersionNothingIsTrustedAsCurrent() {
        Assets assets = assets(1000);
        source.file("att1", null, "x");

        assets.harvest(list(attachment("att1", "a.txt", "text/plain", null)));
        AssetHarvest again = assets.harvest(list(attachment("att1", "a.txt", "text/plain", null)));

        assertThat(again.entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
        assertThat(source.downloads).isEqualTo(2);
    }

    @Test
    void throttlingStopsTheRemainingDownloadsAndOtherFailuresDoNot() {
        Assets assets = assets(1000);
        source.failure("att1", SourceAccessException.Reason.NOT_FOUND);
        source.file("att2", "1", "b");
        source.failure("att3", SourceAccessException.Reason.RATE_LIMITED);
        source.file("att4", "1", "d");

        AssetHarvest harvest = assets.harvest(list(attachment("att1", "a", "text/plain", "1"),
                attachment("att2", "b", "text/plain", "1"), attachment("att3", "c", "text/plain", "1"),
                attachment("att4", "d", "text/plain", "1")));

        assertThat(harvest.entries()).extracting(AssetHarvest.Entry::code)
                .containsExactly("source-object-not-found", null, "source-rate-limited", "source-rate-limited");
        assertThat(harvest.complete()).isFalse();
        assertThat(source.downloads).isEqualTo(3);
    }

    @Test
    void tooLargeFilesAreNeitherTruncatedNorStored() {
        Assets assets = assets(3);
        source.file("att1", "1", "vier");

        AssetHarvest harvest = assets.harvest(list(attachment("att1", "a", "text/plain", "1")));

        assertThat(harvest.entries().get(0).code()).isEqualTo("asset-too-large");
        assertThat(source.limits).as("die Grenze reist bis in den Transport").containsOnly(3L);
        assertThatThrownBy(() -> assets.content(list(attachment("att1", "a", "text/plain", "1")), "att1"))
                .isInstanceOf(AssetTooLargeException.class);
        assertThat(assets.overview(list(attachment("att1", "a", "text/plain", "1"))).entries().get(0).stored())
                .isNull();
    }

    @Test
    void attachmentsOfOtherPagesAreNotFound() {
        assertThatThrownBy(() -> assets(10).content(list(attachment("att1", "a", "text/plain", "1")), "att9"))
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.NOT_FOUND));
        assertThat(source.downloads).isZero();
    }

    @Test
    void diagramsComeBeforeImagesAndPreviewsPointToTheirDiagram() {
        assertThat(AssetKind.of("application/vnd.jgraph.mxfile", "ablauf")).isEqualTo(AssetKind.DIAGRAM);
        assertThat(AssetKind.of("application/octet-stream", "Ablauf.drawio")).isEqualTo(AssetKind.DIAGRAM);
        assertThat(AssetKind.of("image/svg+xml; charset=utf-8", "x")).isEqualTo(AssetKind.VECTOR);
        assertThat(AssetKind.of("image/png", "x.png")).isEqualTo(AssetKind.IMAGE);
        assertThat(AssetKind.of(null, "x.pdf")).isEqualTo(AssetKind.OTHER);
        assertThat(AssetKind.DIAGRAM.compareTo(AssetKind.IMAGE)).isNegative();

        Map<String, String> previews = Assets.previews(List.of(
                attachment("att1", "ablauf.drawio", "application/octet-stream", "1"),
                attachment("att2", "ablauf.drawio.png", "image/png", "1"),
                attachment("att3", "alt", "application/vnd.jgraph.mxfile", "1"),
                attachment("att4", "alt.png", "image/png", "1"),
                attachment("att5", "foto.png", "image/png", "1")));

        assertThat(previews).containsExactlyInAnyOrderEntriesOf(Map.of("att2", "att1", "att4", "att3"));
    }

    @Test
    void descriptionsAreCachedPerImageBytesAndModelVersion() {
        Assets assets = assets(1000, describer);
        source.file("att1", "1", "bild-1");
        PageAttachmentList v1 = list(attachment("att1", "a.png", "image/png", "1"));

        assertThatThrownBy(() -> assets.description(v1, "att1")).isInstanceOfSatisfying(
                ImageDescriptionException.class,
                e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_FOUND));
        Assets.DescribedImage first = assets.describe(v1, "att1");
        Assets.DescribedImage again = assets.describe(v1, "att1");

        assertThat(first.generated()).isTrue();
        assertThat(again.generated()).isFalse();
        assertThat(again.description().text()).isEqualTo("Bild mit 6 Bytes");
        assertThat(assets.description(v1, "att1").description()).isEqualTo(first.description());
        assertThat(describer.calls.get()).isEqualTo(1);

        source.file("att1", "2", "bild-zwei");
        Assets.DescribedImage changed = assets.describe(list(attachment("att1", "a.png", "image/png", "2")), "att1");
        assertThat(changed.generated()).isTrue();
        assertThat(changed.description().imageSha256()).isNotEqualTo(first.description().imageSha256());

        describer.version = "2";
        assertThat(assets.describe(list(attachment("att1", "a.png", "image/png", "2")), "att1").generated())
                .isTrue();
        assertThat(describer.calls.get()).isEqualTo(3);
    }

    @Test
    void onlyImagesAreDescribedAndFailuresAreNotCached() {
        source.file("att1", "1", "<mxfile/>");
        source.file("att2", "1", "bild");

        assertThatThrownBy(() -> assets(1000).describe(list(attachment("att2", "a.png", "image/png", "1")), "att2"))
                .isInstanceOfSatisfying(ImageDescriptionException.class, e -> assertThat(e.reason())
                        .isEqualTo(ImageDescriptionException.Reason.NOT_CONFIGURED));
        Assets assets = assets(1000, describer);
        assertThatThrownBy(() -> assets.describe(list(attachment("att1", "ablauf", "application/vnd.jgraph.mxfile",
                "1")), "att1")).isInstanceOfSatisfying(ImageDescriptionException.class,
                e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_AN_IMAGE));
        describer.fail = true;
        assertThatThrownBy(() -> assets.describe(list(attachment("att2", "a.png", "image/png", "1")), "att2"))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.FAILED));
        describer.fail = false;
        assertThat(assets.describe(list(attachment("att2", "a.png", "image/png", "1")), "att2").generated()).isTrue();
    }

    @Test
    void theSameImageIsDescribedOnceEvenWhenAskedConcurrently() throws Exception {
        describer.gate = new CountDownLatch(1);
        Assets assets = assets(1000, describer);
        source.file("att1", "1", "png");
        PageAttachmentList list = list(attachment("att1", "a.png", "image/png", "1"));
        assets.harvest(list);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Assets.DescribedImage>> runs = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                runs.add(pool.submit(() -> assets.describe(list, "att1")));
            }
            assertThat(describer.started.await(5, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(100);
            describer.gate.countDown();

            List<Assets.DescribedImage> results = new ArrayList<>();
            for (Future<Assets.DescribedImage> run : runs) {
                results.add(run.get(5, TimeUnit.SECONDS));
            }
            assertThat(describer.calls.get()).isEqualTo(1);
            assertThat(results).extracting(r -> r.description().text()).containsOnly("Bild mit 3 Bytes");
            assertThat(results).filteredOn(Assets.DescribedImage::generated).hasSize(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void tooManyDifferentDescriptionsAtOnceAreRefusedNotQueued() throws Exception {
        describer.gate = new CountDownLatch(1);
        Assets assets = assets(1000, describer);
        int running = Assets.MAX_CONCURRENT_DESCRIPTIONS;
        List<PageAttachment> images = new ArrayList<>();
        for (int i = 0; i <= running; i++) {
            source.file("att" + i, "1", "bild-" + i);
            images.add(attachment("att" + i, i + ".png", "image/png", "1"));
        }
        PageAttachmentList list = list(images.toArray(PageAttachment[]::new));
        assets.harvest(list);
        describer.started = new CountDownLatch(running);
        ExecutorService pool = Executors.newFixedThreadPool(running);
        try {
            List<Future<Assets.DescribedImage>> runs = new ArrayList<>();
            for (int i = 0; i < running; i++) {
                String id = "att" + i;
                runs.add(pool.submit(() -> assets.describe(list, id)));
            }
            assertThat(describer.started.await(5, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> assets.describe(list, "att" + running))
                    .isInstanceOfSatisfying(ImageDescriptionException.class,
                            e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.BUSY));
            describer.gate.countDown();
            for (Future<Assets.DescribedImage> run : runs) {
                assertThat(run.get(5, TimeUnit.SECONDS).generated()).isTrue();
            }
            assertThat(assets.describe(list, "att" + running).generated()).isTrue();
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class CountingDescriber implements ImageDescriber {

        final AtomicInteger calls = new AtomicInteger();
        volatile String version = "1";
        volatile boolean fail;
        /** Hält jeden Lauf an, bis der Test ihn freigibt; {@code null} lässt sofort durch. */
        volatile CountDownLatch gate;
        volatile CountDownLatch started = new CountDownLatch(1);

        @Override
        public String id() {
            return "test";
        }

        @Override
        public String modelVersion() {
            return "modell-" + version;
        }

        @Override
        public String describe(byte[] image, String mediaType) {
            calls.incrementAndGet();
            started.countDown();
            CountDownLatch waitFor = gate;
            if (waitFor != null) {
                try {
                    waitFor.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (fail) {
                throw new IllegalStateException("Modell weg");
            }
            return "Bild mit " + image.length + " Bytes";
        }
    }

    private static PageAttachment attachment(String id, String title, String mediaType, String version) {
        return new PageAttachment(id, "test:wiki/attachment/" + id, title, mediaType, null, version, null, null);
    }

    private static PageAttachmentList list(PageAttachment... attachments) {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("101", List.of(attachments), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }

    private static final class FakeSource implements HarvestSource {

        private final Map<String, Object> files = new HashMap<>();
        int downloads;
        final List<Long> limits = new ArrayList<>();

        void file(String id, String version, String content) {
            files.put(id + "#content", content);
            files.put(id + "#version", version == null ? "" : version);
        }

        void failure(String id, SourceAccessException.Reason reason) {
            files.put(id + "#failure", reason);
        }

        @Override
        public AttachmentFile attachmentFile(PageAttachment attachment, long maxBytes) {
            downloads++;
            limits.add(maxBytes);
            Object failure = files.get(attachment.attachmentId() + "#failure");
            if (failure != null) {
                throw new SourceAccessException("wiki", (SourceAccessException.Reason) failure, "x", null);
            }
            String version = (String) files.get(attachment.attachmentId() + "#version");
            if (((String) files.get(attachment.attachmentId() + "#content")).length() > maxBytes) {
                throw new AttachmentTooLargeException("wiki", maxBytes);
            }
            return new AttachmentFile(new PageAttachment(attachment.attachmentId(), attachment.resourceId(),
                    attachment.title(), attachment.mediaType(), null, version.isEmpty() ? null : version, null,
                    null), ((String) files.get(attachment.attachmentId() + "#content")).getBytes(StandardCharsets.UTF_8),
                    NOW);
        }

        @Override
        public String id() {
            return "wiki";
        }

        @Override
        public String type() {
            return "test";
        }

        @Override
        public String title() {
            return "Wiki";
        }

        @Override
        public List<Partition> partitions() {
            return new ArrayList<>();
        }

        @Override
        public PageMetadataBatch pageMetadata(String partitionKey, String cursor, int limit) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageMetadata pageMetadata(String pageId) {
            throw new UnsupportedOperationException();
        }
    }
}
