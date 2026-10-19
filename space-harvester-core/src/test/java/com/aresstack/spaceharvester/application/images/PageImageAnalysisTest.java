package com.aresstack.spaceharvester.application.images;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.ImageDescriber;
import com.aresstack.spaceharvester.application.assets.ImageDescriptionException;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.PageTextProjector;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataBatch;
import com.aresstack.spaceharvester.application.sources.Partition;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PageImageAnalysisTest {

    private static final Instant NOW = Instant.parse("2026-10-19T02:00:00Z");
    private static final PageTextProjector PROJECTOR = snapshot -> Optional.of(new PageText("test/v1",
            new String(snapshot.payload().bytes(), StandardCharsets.UTF_8), List.of()));
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 1, 2, 3};

    @TempDir
    Path directory;

    private final FakeSource source = new FakeSource();
    private final FakeDescriber describer = new FakeDescriber();

    @Test
    void anImageUsedOnTwoPagesGoesToTheModelOnce() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att1", "ablauf.png", PNG);
        PageImageAnalysis analysis = analysis();

        PageImages first = analysis.describe(page("1", "Text eins"));
        PageImages second = analysis.describe(page("2", "Text zwei"));

        assertThat(describer.calls).isEqualTo(1);
        assertThat(first.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(first.images()).singleElement().satisfies(image -> {
            assertThat(image.status()).isEqualTo(ImageStatus.DESCRIBED);
            assertThat(image.description()).isEqualTo("Ein Bild mit 11 Bytes");
            assertThat(image.sha256()).startsWith("sha256:");
            assertThat(image.model()).isEqualTo("vision-1");
            assertThat(image.assetId()).isEqualTo("att1");
        });
        assertThat(second.images()).singleElement()
                .satisfies(image -> assertThat(image.status()).isEqualTo(ImageStatus.DESCRIBED));
    }

    @Test
    void theDescriptionSurvivesARebuildOfTheEnrichments() {
        source.image("1", "att1", "ablauf.png", PNG);
        analysis().describe(page("1", "Text"));

        // Neue, leere Anreicherungsablage, dieselbe Asset-Ablage: das Modell wird nicht erneut gefragt.
        PageImages again = analysis().describe(page("1", "Text"));

        assertThat(describer.calls).isEqualTo(1);
        assertThat(again.describedCount()).isEqualTo(1);
    }

    @Test
    void aModelChangeMakesTheResultStaleAndADescribeRenewsIt() {
        source.image("1", "att1", "ablauf.png", PNG);
        PageImageAnalysis analysis = analysis();
        analysis.describe(page("1", "Text"));

        describer.model = "vision-2";

        assertThat(analysis.read(page("1", "Text")).state()).isEqualTo(PageImages.State.STALE);
        PageImages renewed = analysis.describe(page("1", "Text"));
        assertThat(renewed.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(renewed.images().get(0).model()).isEqualTo("vision-2");
        assertThat(describer.calls).isEqualTo(2);
    }

    @Test
    void aChangedTextMakesTheResultStale() {
        source.image("1", "att1", "ablauf.png", PNG);
        PageImageAnalysis analysis = analysis();
        analysis.describe(page("1", "Text"));

        assertThat(analysis.read(page("1", "Anderer Text")).state()).isEqualTo(PageImages.State.STALE);
    }

    @Test
    void readingNeverAsksTheModelOrTheSource() {
        source.image("1", "att1", "ablauf.png", PNG);

        PageImages images = analysis().read(page("1", "Text"));

        assertThat(images.state()).isEqualTo(PageImages.State.MISSING);
        assertThat(images.reason()).isEqualTo(PageImages.NOT_ANALYSED);
        assertThat(describer.calls).isZero();
        assertThat(source.loads).isZero();
    }

    @Test
    void withoutAVisionModelNothingIsDescribed() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.available = false;
        PageImageAnalysis analysis = analysis();

        assertThat(analysis.read(page("1", "Text")).reason()).isEqualTo(PageImages.VISION_MODEL_MISSING);
        assertThatThrownBy(() -> analysis.describe(page("1", "Text")))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_CONFIGURED));
        assertThat(source.loads).isZero();
    }

    @Test
    void imagesOfASourceWithoutReleaseNeverReachTheModel() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.released = false;
        PageImageAnalysis analysis = analysis();

        assertThat(analysis.read(page("1", "Text")).reason()).isEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThatThrownBy(() -> analysis.describe(page("1", "Text")))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_RELEASED));
        assertThat(describer.calls).isZero();
        assertThat(source.loads).isZero();
    }

    @Test
    void diagramPreviewsAndSvgAreSkippedBecauseTheirTextualSourceCounts() {
        source.image("1", null, "ablauf.png", PNG);
        source.image("1", "att2", "skizze.svg", "<svg/>".getBytes(StandardCharsets.UTF_8));
        source.attachments.add(new PageAttachment("att9", "att9", "ablauf.drawio", "application/vnd.jgraph.mxfile",
                10L, "1", NOW, null));

        PageImages images = analysis().describe(page("1", "Text"));

        assertThat(images.images()).extracting(ImagePayload.Entry::status, ImagePayload.Entry::reason)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(ImageStatus.SKIPPED, "diagram-source"),
                        org.assertj.core.groups.Tuple.tuple(ImageStatus.SKIPPED, "vector-source"));
        assertThat(describer.calls).isZero();
    }

    @Test
    void aFailedModelCallIsMarkedAndRetriedLater() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.fail = true;
        PageImageAnalysis analysis = analysis();

        PageImages failed = analysis.describe(page("1", "Text"));

        assertThat(failed.images()).singleElement().satisfies(image -> {
            assertThat(image.status()).isEqualTo(ImageStatus.FAILED);
            assertThat(image.reason()).isEqualTo("model-failed");
            assertThat(image.description()).isNull();
        });
        describer.fail = false;
        assertThat(analysis.describe(page("1", "Text")).describedCount()).isEqualTo(1);
    }

    @Test
    void aPageWithoutImagesIsCurrentAndEmpty() {
        PageImages images = analysis().describe(page("1", "Text"));

        assertThat(images.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(images.images()).isEmpty();
    }

    @Test
    void theResultAppearsAmongTheEnrichmentsOfThePage() {
        source.image("1", "att1", "ablauf.png", PNG);
        Enrichments enrichments = enrichments();
        new PageImageAnalysis(assets(), enrichments, Clock.fixed(NOW, ZoneOffset.UTC)).describe(page("1", "Text"));

        // Analyse-Export (#359) und Leseansicht sehen dasselbe Ergebnis; recompute rechnet es nie neu.
        assertThat(enrichments.view(page("1", "Text"))).extracting(view -> view.enrichment().processorId())
                .containsExactly(ImagePayload.PROCESSOR);
        assertThat(enrichments.recompute(page("1", "Text"), ImagePayload.PROCESSOR).computed()).isZero();
    }

    private InMemoryEnrichmentStore store;

    private Enrichments enrichments() {
        store = new InMemoryEnrichmentStore();
        return new Enrichments(PROJECTOR, List.of(new ImageProcessor(() -> describer)), store,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private PageImageAnalysis analysis() {
        return new PageImageAnalysis(assets(), enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Assets assets() {
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
        return new Assets(catalog, new FileSystemAssetStore(directory), 1000, Clock.fixed(NOW, ZoneOffset.UTC),
                describer, null);
    }

    private static PageSnapshot page(String pageId, String text) {
        return EnrichmentTestPages.text("wiki", pageId, "3", text);
    }

    private static final class FakeDescriber implements ImageDescriber {

        int calls;
        String model = "vision-1";
        boolean available = true;
        boolean released = true;
        boolean fail;

        @Override
        public String id() {
            return "test-vision-v1";
        }

        @Override
        public String modelVersion() {
            return model;
        }

        @Override
        public String describe(byte[] image, String mediaType) {
            calls++;
            if (fail) {
                throw new IllegalStateException("Modell nicht erreichbar");
            }
            return "Ein Bild mit " + image.length + " Bytes";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public boolean mayDescribe(String sourceId) {
            return released;
        }
    }

    private static final class FakeSource implements HarvestSource {

        private final Map<String, List<ViewImage>> images = new HashMap<>();
        private final Map<String, byte[]> bytes = new HashMap<>();
        final List<PageAttachment> attachments = new ArrayList<>();
        int loads;

        void image(String pageId, String attachmentId, String filename, byte[] content) {
            String src = "https://wiki.example.org/download/attachments/" + pageId + "/" + filename;
            String type = filename.endsWith(".svg") ? "image/svg+xml" : "image/png";
            images.computeIfAbsent(pageId, key -> new ArrayList<>())
                    .add(new ViewImage(src, filename, attachmentId, type, attachmentId == null ? null : "1"));
            bytes.put(src, content);
        }

        @Override
        public List<ViewImage> viewImages(String pageId) {
            return List.copyOf(images.getOrDefault(pageId, List.of()));
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            loads++;
            return bytes.get(image.src());
        }

        @Override
        public PageAttachments attachments(String pageId) {
            return new PageAttachments(pageId, List.copyOf(attachments), List.of(), "application/json", "test",
                    List.of(), NOW);
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
