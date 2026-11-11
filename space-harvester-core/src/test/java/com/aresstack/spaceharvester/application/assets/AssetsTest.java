package com.aresstack.spaceharvester.application.assets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.sources.AttachmentFile;
import com.aresstack.spaceharvester.application.sources.AttachmentSink;
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
import com.aresstack.spaceharvester.application.sources.StreamedAttachment;
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
        return assets(maxBytes, imageDescriber, documents, LargeFileGate.defaults());
    }

    private Assets assets(long maxBytes, ImageDescriber imageDescriber, DocumentText documents,
                          LargeFileGate gate) {
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
                imageDescriber, documents, null, gate);
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
    void aSourceThatStartsOverAfterABrokenAttemptStoresOnlyTheLastOne() throws Exception {
        Assets assets = assets(1000);
        source.file("att1", "1", "Bericht");
        source.brokenFirst.put("att1", "Ber");

        Asset stored = assets.harvest(list(attachment("att1", "a.txt", "text/plain", "1"))).entries().get(0).asset();

        byte[] expected = "Bericht".getBytes(StandardCharsets.UTF_8);
        assertThat(stored.size()).isEqualTo(expected.length);
        assertThat(stored.sha256()).isEqualTo(Assets.sha256(expected));
        assertThat(assets.content(list(attachment("att1", "a.txt", "text/plain", "1")), "att1").content())
                .isEqualTo(expected);
        assertThat(java.nio.file.Files.list(directory.resolve("staging")))
                .as("die Zwischendatei ist in die Ablage gewandert").isEmpty();
    }

    @Test
    void aFileAboveTheTextLimitIsStoredWithoutTextAndNeverReadBackForTheHarvest() {
        FakeDocuments documents = new FakeDocuments().type("%PDF", "application/pdf");
        Assets assets = assets(64L * 1024 * 1024, null, documents);
        String big = "%PDF" + "x".repeat((int) Assets.IN_MEMORY_BYTES);
        source.file("att1", "1", big);

        AssetHarvest harvest = assets.harvest(list(attachment("att1", "gross.pdf", "application/octet-stream",
                "1")));

        Asset stored = harvest.entries().get(0).asset();
        assertThat(harvest.entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
        assertThat(stored.size()).isEqualTo(big.length());
        assertThat(stored.mediaType()).as("die Signatur am Anfang reicht").isEqualTo("application/pdf");
        assertThat(stored.sha256()).isEqualTo(Assets.sha256(big.getBytes(StandardCharsets.UTF_8)));
        assertThat(documents.extractions).as("zu groß für die Textextraktion").hasValue(0);
    }

    @Test
    void aFailedDownloadLeavesNoFileBehind() throws Exception {
        Assets assets = assets(1000);
        source.file("att1", "1", "Bericht");
        source.brokenFirst.put("att1", "Ber");
        source.failure("att1", SourceAccessException.Reason.UNAVAILABLE);

        AssetHarvest harvest = assets.harvest(list(attachment("att1", "a.txt", "text/plain", "1")));

        assertThat(harvest.entries().get(0).code()).isEqualTo("source-unavailable");
        assertThat(java.nio.file.Files.list(directory.resolve("staging"))).isEmpty();
    }

    @Test
    void keepStoresTheAttachmentOnceAndLoadsAgainOnlyWhenForced() {
        Assets assets = assets(1000);
        source.file("att1", "1", "Bericht");
        PageAttachmentList list = list(attachment("att1", "a.txt", "text/plain", "1"));

        Asset kept = assets.keep(list, "att1", false);
        Asset again = assets.keep(list, "att1", false);
        Asset forced = assets.keep(list, "att1", true);

        assertThat(again).isEqualTo(kept);
        assertThat(forced.sha256()).isEqualTo(kept.sha256());
        assertThat(source.downloads).as("abgelegt heißt: nicht noch einmal laden, außer erzwungen").isEqualTo(2);
        assertThat(assets.content(list, "att1").content()).isEqualTo("Bericht".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void keepDoesNotReportADamagedStoredFileAsStored() throws Exception {
        Assets assets = assets(1000);
        source.file("att1", "1", "Bericht");
        PageAttachmentList list = list(attachment("att1", "a.txt", "text/plain", "1"));
        Asset kept = assets.keep(list, "att1", false);
        String hex = kept.sha256().substring("sha256:".length());
        java.nio.file.Files.writeString(directory.resolve("blobs").resolve(hex.substring(0, 2)).resolve(hex),
                "Bxricht");

        assertThatThrownBy(() -> assets.keep(list, "att1", false)).isInstanceOf(AssetStoreException.class);
        assertThat(source.downloads).isEqualTo(1);
    }

    @Test
    void aDeclaredSizeAboveTheConfiguredLimitIsRefusedWithoutDownload() {
        Assets assets = assets(3);
        source.file("att1", "1", "vier");
        PageAttachment declared = new PageAttachment("att1", "test:wiki/attachment/att1", "a", "text/plain", 4L, "1",
                null, null);

        assertThat(assets.harvest(list(declared)).entries().get(0).code()).isEqualTo("asset-too-large");
        assertThatThrownBy(() -> assets.content(list(declared), "att1")).isInstanceOf(AssetTooLargeException.class);
        assertThat(source.downloads).as("die angegebene Größe genügt").isZero();
    }

    @Test
    void anEmptyAnswerForAFileWithASizeIsNotStored() {
        Assets assets = assets(100_000);
        source.file("att1", "1", "");
        PageAttachment declared = new PageAttachment("att1", "test:wiki/attachment/att1", "Peilschiff&Seeboden.JPG",
                "image/jpeg", 18L, "1", null, null);

        // Ein Download, der ins Leere ging, gilt nicht als abgelegt und nicht als leere Datei.
        assertThat(assets.harvest(list(declared)).entries().get(0).code()).isEqualTo("source-invalid-response");
        assertThatThrownBy(() -> assets.content(list(declared), "att1"))
                .isInstanceOf(SourceAccessException.class);
    }

    @Test
    void aThirdLargeFileWaitsAtTheGateAndFailsTransientlyWhileTwoAreHeld() throws Exception {
        LargeFileGate gate = new LargeFileGate(4, 2, java.time.Duration.ofMillis(200));
        Assets assets = assets(1000, null, null, gate);
        source.file("att1", "1", "gross-1");
        source.file("att2", "1", "ok");
        PageAttachment unknownSize = attachment("att1", "a.txt", "text/plain", "1");
        PageAttachment small = new PageAttachment("att2", "test:wiki/attachment/att2", "b.txt", "text/plain", 2L,
                "1", null, null);
        CountDownLatch held = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 2; i++) {
                pool.submit(() -> {
                    try (LargeFileGate.Permit permit = gate.enter("wiki", 5L)) {
                        held.countDown();
                        release.await(5, TimeUnit.SECONDS);
                    }
                    return null;
                });
            }
            assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

            // Unbekannte Größe zählt als groß: wartet, scheitert vorübergehend und lädt nichts.
            assertThatThrownBy(() -> assets.content(list(unknownSize), "att1"))
                    .isInstanceOfSatisfying(SourceAccessException.class,
                            e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.RATE_LIMITED));
            assertThat(source.downloads).isZero();
            AssetHarvest harvest = assets.harvest(list(unknownSize));
            assertThat(harvest.entries().get(0).status()).isEqualTo(AssetHarvest.Status.FAILED);
            assertThat(harvest.entries().get(0).code()).isEqualTo("source-rate-limited");
            assertThat(assets.overview(list(unknownSize)).entries().get(0).stored()).isNull();
            // Kleine Dateien laufen an der Schranke vorbei.
            assertThat(assets.content(list(small), "att2").content()).isEqualTo("ok".getBytes(StandardCharsets.UTF_8));
        } finally {
            release.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }

        // Freigegeben: dieselbe Datei wird jetzt abgelegt, nichts ist dauerhaft als defekt gemerkt.
        assertThat(assets.harvest(list(unknownSize)).entries().get(0).status()).isEqualTo(AssetHarvest.Status.STORED);
    }

    /** Ein gestreamter großer Anhang hält seinen Platz, bis der Strom geschlossen ist, auch aus einem anderen Thread. */
    @Test
    void aStreamedLargeFileHoldsItsPlaceUntilItsStreamIsClosedFromAnyThread() throws Exception {
        LargeFileGate gate = new LargeFileGate(4, 1, java.time.Duration.ofMillis(100));
        Assets assets = assets(1000, null, null, gate);
        source.file("att1", "1", "gross-1");
        PageAttachment large = new PageAttachment("att1", "test:wiki/attachment/att1", "a.txt", "text/plain", 7L,
                "1", null, null);
        assets.harvest(list(large));

        Assets.AssetStream stream = assets.stream(list(large), "att1", false, null);
        assertThat(stream.content().readAllBytes()).isEqualTo("gross-1".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> gate.enter("wiki", 5L).close())
                .isInstanceOfSatisfying(SourceAccessException.class,
                        e -> assertThat(e.reason()).isEqualTo(SourceAccessException.Reason.RATE_LIMITED));

        ExecutorService other = Executors.newSingleThreadExecutor();
        try {
            other.submit(() -> {
                stream.close();
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            other.shutdown();
        }
        try (LargeFileGate.Permit permit = gate.enter("wiki", 5L)) {
            assertThat(permit).isNotNull();
        }
        stream.close(); // mehrfach harmlos
        try (LargeFileGate.Permit permit = gate.enter("wiki", 5L)) {
            assertThat(permit).isNotNull();
        }

        // Fehlt die Datei in der Ablage, gilt der Anhang als nicht abgelegt und kommt neu aus der Quelle; danach ist
        // der einzige Platz wieder frei.
        try (java.util.stream.Stream<java.nio.file.Path> blobs = java.nio.file.Files.walk(directory.resolve("blobs"))) {
            for (java.nio.file.Path blob : blobs.filter(java.nio.file.Files::isRegularFile).toList()) {
                java.nio.file.Files.delete(blob);
            }
        }
        try (Assets.AssetStream again = assets.stream(list(large), "att1", false, null)) {
            assertThat(again.content().readAllBytes()).isEqualTo("gross-1".getBytes(StandardCharsets.UTF_8));
        }
        try (LargeFileGate.Permit permit = gate.enter("wiki", 5L)) {
            assertThat(permit).isNotNull();
        }
    }

    @Test
    void nestedUseOfTheGateInOneThreadTakesOnlyOnePlace() {
        LargeFileGate gate = new LargeFileGate(0, 1, java.time.Duration.ZERO);
        try (LargeFileGate.Permit outer = gate.enter("wiki", 10L);
             LargeFileGate.Permit inner = gate.enter("wiki", 10L)) {
            assertThat(gate.large(10L)).isTrue();
        }
        try (LargeFileGate.Permit again = gate.enter("wiki", 10L)) {
            assertThat(gate.large(0L)).isFalse();
        }
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

    @Test
    void theDrawioPreviewOfTheReadingViewBecomesThePngOfTheDiagram() {
        Assets assets = assets(100_000);
        String mxfile = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
                + "</root></mxGraphModel></diagram></mxfile>";
        source.file("att-d", "2", mxfile);
        source.listed.add(attachment("att-d", "ablauf.drawio", "application/vnd.jgraph.mxfile", "2"));
        source.listed.add(attachment("att-p", "ablauf.png", "image/png", "2"));
        // Confluence setzt als Titel die Beschriftung, die Adresse endet auf den Dateinamen der Vorschau.
        HarvestSource.ViewImage preview = new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/101/ablauf.png?version=2", "Ablauf (Übersicht)", null,
                "image/png", null);

        Assets.AssetContent png = assets.viewDiagram("wiki", "101", "7", preview).orElseThrow();

        assertThat(png.asset().mediaType()).isEqualTo("image/png");
        assertThat(png.asset().version()).isEqualTo(Assets.DRAWIO_PNG_RENDITION);
        assertThat(png.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // Einmal gezeichnet und abgelegt: kein zweiter Download der Quelldaten, dieselbe Grafik.
        assertThat(assets.viewDiagram("wiki", "101", "7", preview).orElseThrow().asset().sha256())
                .isEqualTo(png.asset().sha256());
        assertThat(source.downloads).isEqualTo(1);
        // Ein anderes Bild der Ansicht ist keine Vorschau: die App zeigt es selbst.
        assertThat(assets.viewDiagram("wiki", "101", "7", new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/101/foto.png", "Foto", null, "image/png", null)))
                .isEmpty();
    }

    @Test
    void aDrawioDiagramOfAnotherPageBecomesAPngFromTheSvgOfTheSource() {
        Assets assets = assets(100_000);
        // Eingebunden über multiexcerpt-include: das Diagramm hängt an Seite 202, nicht an der gezeigten Seite 101.
        // Die Quelle zeichnet es selbst (REST des Plugins) und liefert SVG wie im Enterprise AI Client.
        String src = "https://wiki.example.org/download/attachments/202/Stages.png?version=14&api=v2"
                + "#drawio=Stages&page=202&revision=14";
        source.views.put(src, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\">"
                + "<rect x=\"0\" y=\"0\" width=\"80\" height=\"40\" fill=\"#3366cc\"/></svg>");
        HarvestSource.ViewImage diagram = new HarvestSource.ViewImage(src, "Stages", null, null, null);

        Assets.AssetContent png = assets.viewDiagram("wiki", "101", "7", diagram).orElseThrow();

        assertThat(png.asset().mediaType()).isEqualTo("image/png");
        assertThat(png.asset().version()).isEqualTo(Assets.SVG_PNG_RENDITION);
        assertThat(png.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // Das SVG liegt danach in der Ablage: kein zweiter Abruf von der Quelle.
        assertThat(assets.viewDiagram("wiki", "101", "7", diagram).orElseThrow().asset().sha256())
                .isEqualTo(png.asset().sha256());
        assertThat(source.viewLoads).isEqualTo(1);
        // Ein gewöhnliches Bild ohne draw.io-Angaben bleibt beim Bildweg der App.
        assertThat(assets.viewDiagram("wiki", "101", "7", new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/202/foto.png", "Foto", null, "image/png", null)))
                .isEmpty();
        assertThat(source.viewLoads).isEqualTo(1);
    }

    @Test
    void aForeignDrawioDiagramDeclaredAsPngIsStillRasterizedFromItsSvg() {
        Assets assets = assets(100_000);
        // export_view nennt am Vorschaubild image/png; geliefert wird trotzdem die gezeichnete SVG-Fassung.
        String src = "https://wiki.example.org/download/attachments/202/Stages.png?version=14&api=v2"
                + "#drawio=Stages&page=202&revision=14";
        source.views.put(src, "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>"
                + "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"80\" height=\"40\">"
                + "<rect x=\"0\" y=\"0\" width=\"80\" height=\"40\" fill=\"#3366cc\"/></svg>");
        HarvestSource.ViewImage diagram = new HarvestSource.ViewImage(src, "Stages", "att-p", "image/png", "3");

        Assets.AssetContent png = assets.viewDiagram("wiki", "101", "7", diagram).orElseThrow();

        assertThat(png.asset().version()).isEqualTo(Assets.SVG_PNG_RENDITION);
        assertThat(png.content()).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // Aus der Ablage ebenso gerastert, ohne neuen Abruf.
        assertThat(assets.viewDiagram("wiki", "101", "7", diagram).orElseThrow().asset().version())
                .isEqualTo(Assets.SVG_PNG_RENDITION);
        assertThat(source.viewLoads).isEqualTo(1);
    }

    @Test
    void theDrawioDiagramOfTheReadingViewIsAvailableAsVectorSvg() {
        Assets assets = assets(100_000);
        String mxfile = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
                + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
                + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
                + "</root></mxGraphModel></diagram></mxfile>";
        source.file("att-d", "2", mxfile);
        source.listed.add(attachment("att-d", "ablauf.drawio", "application/vnd.jgraph.mxfile", "2"));
        HarvestSource.ViewImage preview = new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/101/ablauf.png?version=2", "Ablauf", null,
                "image/png", null);

        Assets.AssetContent svg = assets.viewDiagramSvg("wiki", "101", "7", preview).orElseThrow();

        assertThat(svg.asset().mediaType()).isEqualTo("image/svg+xml");
        assertThat(svg.asset().version()).isEqualTo(Assets.DRAWIO_RENDITION);
        assertThat(new String(svg.content(), StandardCharsets.UTF_8)).contains("<svg");
        assertThat(com.aresstack.spaceharvester.application.diagrams.VectorDiagram.of(svg.content())).isPresent();
        // Einmal gezeichnet: kein zweiter Download der Quelldaten.
        assets.viewDiagramSvg("wiki", "101", "7", preview).orElseThrow();
        assertThat(source.downloads).isEqualTo(1);
        // Ein gewöhnliches Bild ist kein Diagramm.
        assertThat(assets.viewDiagramSvg("wiki", "101", "7", new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/101/foto.png", "Foto", null, "image/png", null)))
                .isEmpty();
    }

    @Test
    void aDrawioDiagramOfAnotherPageIsAvailableAsTheSvgOfTheSource() {
        Assets assets = assets(100_000);
        String src = "https://wiki.example.org/download/attachments/202/Stages.png?version=14&api=v2"
                + "#drawio=Stages&page=202&revision=14";
        String drawn = "<?xml version=\"1.0\"?><svg xmlns=\"http://www.w3.org/2000/svg\" width=\"3288\" "
                + "height=\"1492\"><rect x=\"0\" y=\"0\" width=\"80\" height=\"40\" fill=\"#3366cc\"/></svg>";
        source.views.put(src, drawn);
        HarvestSource.ViewImage diagram = new HarvestSource.ViewImage(src, "Stages", "att-p", "image/png", "3");

        Assets.AssetContent svg = assets.viewDiagramSvg("wiki", "101", "7", diagram).orElseThrow();

        assertThat(new String(svg.content(), StandardCharsets.UTF_8)).isEqualTo(drawn);
        var vector = com.aresstack.spaceharvester.application.diagrams.VectorDiagram.of(svg.content()).orElseThrow();
        assertThat(vector.width()).isEqualTo(3288.0);
        // Gezeichnet wird in jeder Größe, ohne Obergrenze: doppelte Eigengröße für einen Ausschnitt.
        assertThat(vector.raster(6576).getWidth()).isEqualTo(6576);
    }

    @Test
    void anUndrawableDiagramLeavesThePreview() {
        Assets assets = assets(100_000);
        source.file("att-d", "1", "kein xml");
        source.listed.add(attachment("att-d", "ablauf.drawio", "application/vnd.jgraph.mxfile", "1"));

        assertThat(assets.viewDiagram("wiki", "101", "7", new HarvestSource.ViewImage(
                "https://wiki.example.org/download/attachments/101/ablauf.png", "Ablauf", null, "image/png", null)))
                .isEmpty();
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
        final List<PageAttachment> listed = new ArrayList<>();
        final Map<String, String> views = new HashMap<>();
        int viewLoads;

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            viewLoads++;
            String content = views.get(image.src());
            if (content == null) {
                throw new SourceAccessException("wiki", SourceAccessException.Reason.NOT_FOUND, "x", null);
            }
            return content.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public PageAttachments attachments(String pageId) {
            return new PageAttachments(pageId, List.copyOf(listed), List.of(), "application/json", "test", List.of(),
                    NOW);
        }

        void file(String id, String version, String content) {
            files.put(id + "#content", content);
            files.put(id + "#version", version == null ? "" : version);
        }

        void failure(String id, SourceAccessException.Reason reason) {
            files.put(id + "#failure", reason);
        }

        /** Erster, abgebrochener Versuch einer strömenden Quelle; der Neuversuch beginnt von vorn. */
        final Map<String, String> brokenFirst = new HashMap<>();

        @Override
        public StreamedAttachment streamAttachment(PageAttachment attachment, AttachmentSink sink) {
            String broken = brokenFirst.get(attachment.attachmentId());
            if (broken == null) {
                return HarvestSource.super.streamAttachment(attachment, sink);
            }
            try {
                sink.write(broken.getBytes(StandardCharsets.UTF_8));
                sink.restart();
                AttachmentFile file = attachmentFile(attachment, sink.maxBytes());
                sink.write(file.content());
                return new StreamedAttachment(file.attachment(), file.retrievedAt());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
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
