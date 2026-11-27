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
import com.aresstack.spaceharvester.application.reading.ReadingViewStore;
import com.aresstack.spaceharvester.application.reading.StoredReadingView;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
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
    /** Ein leerer Inhalt hat keinen Text, wie ein Video. */
    private static final PageTextProjector PROJECTOR = snapshot -> snapshot.payload().bytes().length == 0
            ? Optional.empty() : Optional.of(new PageText("test/v1",
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
    void imagesOfTheStoredRevisionGiveACurrentResult() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.readingVersion = "3";

        PageImages images = analysis().describe(page("1", "Text"));

        assertThat(images.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(images.warnings()).doesNotContain(PageImageAnalysis.PAGE_CHANGED);
        assertThat(images.describedCount()).isEqualTo(1);
    }

    @Test
    void imagesOfANewerRevisionInTheSourceAreDescribedButStale() {
        // Die Quelle ist schon weiter als der gespeicherte Text: die Bilder gehören womöglich zu einer anderen Fassung.
        source.image("1", "att1", "ablauf.png", PNG);
        source.readingVersion = "4";
        PageImageAnalysis analysis = analysis();

        PageImages images = analysis.describe(page("1", "Text"));

        assertThat(images.state()).isEqualTo(PageImages.State.STALE);
        assertThat(images.warnings()).contains(PageImageAnalysis.PAGE_CHANGED);
        assertThat(images.describedCount()).isEqualTo(1);
        assertThat(analysis.read(page("1", "Text")).state()).isEqualTo(PageImages.State.STALE);

        source.readingVersion = null;
        assertThat(analysis.describe(page("1", "Text")).state()).as("ohne bestätigte Fassung")
                .isEqualTo(PageImages.State.STALE);
        assertThat(describer.calls).as("je Bildbytes und Modell nur einmal").isEqualTo(1);
    }

    @Test
    void aReplacedImageAttachmentMakesTheResultStaleOnceTheReadingViewShowsIt() {
        // Confluence hebt beim Ersetzen eines Anhangs weder Seitenversion noch Text an; erst die neu geladene
        // Lese-Ansicht nennt die neue Version des Anhangs.
        source.image("1", "att1", "ablauf.png", PNG);
        source.readingVersion = "3";
        PageImageAnalysis analysis = analysis();
        analysis.describe(page("1", "Text"));
        ReadingViewStore views = new ReadingViewStore(store);

        views.save(view("1", "1", NOW.plusSeconds(60)));
        assertThat(analysis.read(page("1", "Text")).state()).as("gleiche Version").isEqualTo(PageImages.State.CURRENT);

        views.save(view("1", "2", NOW.minusSeconds(60)));
        assertThat(analysis.read(page("1", "Text")).state()).as("Ansicht älter als das Ergebnis")
                .isEqualTo(PageImages.State.CURRENT);

        views.save(view("1", "2", NOW.plusSeconds(60)));
        PageImages replaced = analysis.read(page("1", "Text"));
        assertThat(replaced.state()).isEqualTo(PageImages.State.STALE);
        assertThat(replaced.reason()).isEqualTo(PageImages.ATTACHMENT_CHANGED);
        assertThat(replaced.describedCount()).isEqualTo(1);
        assertThat(source.loads).as("liest nie von der Quelle").isEqualTo(1);
    }

    @Test
    void anImageThatLeftTheReadingViewMakesTheResultStale() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.readingVersion = "3";
        PageImageAnalysis analysis = analysis();
        analysis.describe(page("1", "Text"));

        new ReadingViewStore(store).save(new StoredReadingView(page("1", "Text").key(), "sha256:" + "a".repeat(64),
                "3", "<p/>", "", "Text", List.of(), NOW.plusSeconds(60)));

        PageImages gone = analysis.read(page("1", "Text"));
        assertThat(gone.state()).isEqualTo(PageImages.State.STALE);
        assertThat(gone.reason()).isEqualTo(PageImages.ATTACHMENT_CHANGED);
    }

    private StoredReadingView view(String pageId, String attachmentVersion, Instant renderedAt) {
        HarvestSource.ViewImage image = source.viewImages(pageId).getFirst();
        return new StoredReadingView(page(pageId, "Text").key(), "sha256:" + "a".repeat(64), "3", "<p/>", "", "Text",
                List.of(new HarvestSource.ViewImage(image.src(), image.title(), image.attachmentId(),
                        image.mediaType(), attachmentVersion)), renderedAt);
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
    void describeOnceSendsOnlyThisPageOfAnUnreleasedSourceAndStoresNoRelease() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", PNG);
        describer.released = false;
        PageImageAnalysis analysis = analysis();

        PageImages once = analysis.describeOnce(page("1", "Text"));

        assertThat(once.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(once.describedCount()).isEqualTo(1);
        assertThat(describer.calls).isEqualTo(1);
        // Die Quelle bleibt gesperrt: die nächste Seite geht weiter nicht an das Modell.
        assertThat(analysis.read(page("2", "Text")).reason()).isEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThatThrownBy(() -> analysis.describe(page("2", "Text")))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_RELEASED));
        assertThat(describer.calls).isEqualTo(1);
    }

    @Test
    void aPageOnTheListOfReleasedPagesIsDescribedAlthoughItsSourceIsNot() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", PNG);
        describer.released = false;
        java.util.Set<PageKey> released = new java.util.HashSet<>(List.of(new PageKey("wiki", "1")));
        Assets assets = assets();
        assets.pageReleases(released::contains);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(analysis.released(new PageKey("wiki", "1"))).isTrue();
        assertThat(analysis.released(new PageKey("wiki", "2"))).isFalse();
        assertThat(analysis.released("wiki")).as("die Quelle bleibt gesperrt").isFalse();
        assertThat(analysis.read(page("1", "Text")).reason()).isNotEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThat(analysis.describe(page("1", "Text")).describedCount()).isEqualTo(1);
        assertThat(analysis.read(page("2", "Text")).reason()).isEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThatThrownBy(() -> analysis.describe(page("2", "Text")))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_RELEASED));
        assertThat(describer.calls).isEqualTo(1);
    }

    @Test
    void withdrawingTheReleaseRemovesTheDescriptionsUnlessAReleasedPageCarriesTheSameImage() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "kopie.png", PNG);
        describer.released = false;
        PageKey one = new PageKey("wiki", "1");
        PageKey two = new PageKey("wiki", "2");
        java.util.Set<PageKey> released = new java.util.HashSet<>(List.of(one, two));
        Assets assets = assets();
        assets.pageReleases(released::contains);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));
        analysis.describe(page("1", "Text"));
        analysis.describe(page("2", "Text"));
        assertThat(describer.calls).isEqualTo(1);

        // Seite 2 verliert die Freigabe; Seite 1 trägt dasselbe Bild und bleibt freigegeben.
        released.remove(two);
        assertThat(analysis.withdraw(List.of(page("1", "Text"), page("2", "Text")), released::contains))
                .as("nur Seite 2").isEqualTo(1);
        assertThat(store.find(two)).isEmpty();
        assertThat(store.find(one)).extracting(e -> e.processorId()).containsExactly(ImagePayload.PROCESSOR);
        assertThat(analysis.describe(page("1", "Text")).describedCount()).isEqualTo(1);
        assertThat(describer.calls).as("die Beschreibung blieb für Seite 1").isEqualTo(1);

        // Jetzt auch Seite 1: Die Beschreibung selbst entfällt, eine neue Freigabe fragt das Modell erneut.
        released.remove(one);
        assertThat(analysis.withdraw(List.of(page("1", "Text")), released::contains)).isEqualTo(1);
        assertThat(store.find(one)).isEmpty();
        released.add(one);
        assertThat(analysis.describe(page("1", "Text")).describedCount()).isEqualTo(1);
        assertThat(describer.calls).isEqualTo(2);
    }

    @Test
    void theAutomaticRunIsOffByDefaultAndPlansNoJob() {
        source.image("1", "att1", "ablauf.png", PNG);
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs, analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(),
                () -> com.aresstack.spaceharvester.application.configuration.ImageSettings.DEFAULT,
                "wiki"::equals);

        assertThat(automatic.automatic("wiki")).isEmpty();
        assertThat(jobs.listAll(com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND)).isEmpty();
        assertThat(describer.calls).isZero();
    }

    @Test
    void theAutomaticRunDescribesOnlyReleasedPagesAsItsOwnJob() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        describer.released = false;
        java.util.Set<PageKey> released = new java.util.HashSet<>(List.of(new PageKey("wiki", "1")));
        Assets assets = assets();
        assets.pageReleases(released::contains);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs, analysis,
                sourceId -> List.of(page("1", "Text"), page("2", "Text")).iterator(),
                () -> new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500),
                "wiki"::equals);

        com.aresstack.spaceharvester.application.background.BackgroundGroup group =
                automatic.automatic("wiki").orElseThrow();

        assertThat(group.kind()).isEqualTo(ImageDescriptionJobs.KIND);
        com.aresstack.spaceharvester.application.jobs.JobStatus job = group.parts().get(0);
        assertThat(jobs.status(job.id()).state())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
        assertThat(job.type()).as("ein Hintergrundauftrag").isEqualTo(
                com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND);
        assertThat(job.parameters()).containsEntry(ImageDescriptionJobs.TRIGGER, ImageDescriptionJobs.AUTOMATIC);
        assertThat(analysis.read(page("1", "Text")).describedCount()).isEqualTo(1);
        assertThat(analysis.read(page("2", "Text")).reason()).as("ohne KI-Freigabe nie an das Modell")
                .isEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThat(describer.calls).isEqualTo(1);
        // Ein zweiter Lauf findet alles beschrieben und fragt das Modell nicht noch einmal.
        automatic.automatic("wiki");
        assertThat(describer.calls).isEqualTo(1);
        // Unbekannte Quellen planen nichts.
        assertThat(automatic.automatic("fremd")).isEmpty();
    }

    @Test
    void withoutAVisionModelTheRunEndsYellowAndNeverRed() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.available = false;
        RecordingContext context = new RecordingContext();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(),
                () -> new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500),
                "wiki"::equals);

        automatic.run("wiki", new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500),
                context);

        assertThat(context.events).extracting(e -> e.code()).contains(AutomaticImageDescriptions.NO_MODEL);
        assertThat(context.events).filteredOn(e -> AutomaticImageDescriptions.NO_MODEL.equals(e.code()))
                .extracting(e -> e.severity())
                .containsOnly(com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.WARNING);
        assertThat(context.failed).isZero();
        assertThat(describer.calls).isZero();
    }

    @Test
    void aFailingVisionModelStopsTheRunAsAnErrorAndTheNextRunTriesAgain() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        describer.fail = true;
        PageImageAnalysis analysis = analysis();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> List.of(page("1", "Text"), page("2", "Text")).iterator(),
                () -> new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500),
                "wiki"::equals);
        RecordingContext context = new RecordingContext();

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki",
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500), context);

        assertThat(outcome.images()).isZero();
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(context.failed).isEqualTo(1);
        int calls = describer.calls;
        assertThat(calls).as("die zweite Seite geht nicht mehr an das ausgefallene Modell").isPositive();
        assertThat(context.events).filteredOn(e -> AutomaticImageDescriptions.MODEL_FAILED.equals(e.code()))
                .singleElement().extracting(e -> e.severity())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.ERROR);

        describer.fail = false;
        AutomaticImageDescriptions.Outcome again = automatic.run("wiki",
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500),
                new RecordingContext());
        assertThat(again.images()).as("der nächste Lauf holt beide Bilder nach").isEqualTo(2);
        assertThat(again.failed()).isZero();
    }

    @Test
    void aPageWithMoreOpenImagesThanTheWholeLimitStillRunsAsTheFirstOfARun() {
        // Seite 1 hat vier Bilder bei einer Grenze von drei, Seite 2 eines; beide Listen sind bekannt.
        for (int i = 1; i <= 4; i++) {
            source.image("1", "a" + i, "a" + i + ".png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, (byte) i});
        }
        source.image("2", "b1", "b1.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 2, 1});
        PageImageAnalysis analysis = analysis();
        List<PageSnapshot> pages = List.of(page("1", "Text"), page("2", "Text"));
        for (PageSnapshot page : pages) {
            new ReadingViewStore(store).save(new StoredReadingView(page.key(), "sha256:lese", "1", "", "", "",
                    source.viewImages(page.key().pageId()), NOW));
        }
        com.aresstack.spaceharvester.application.configuration.ImageSettings three =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 3);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> pages.iterator(), () -> three, "wiki"::equals);
        RecordingContext context = new RecordingContext();

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", three, context);

        assertThat(outcome.images()).as("die große Seite läuft einmal ganz, statt für immer offen zu bleiben")
                .isEqualTo(4);
        assertThat(outcome.open()).as("danach ist die Grenze überzogen, Seite 2 folgt").isEqualTo(1);
        assertThat(context.incomplete).isTrue();
        AutomaticImageDescriptions.Outcome next = automatic.run("wiki", three, new RecordingContext());
        assertThat(next.images()).isEqualTo(1);
        assertThat(next.open()).isZero();
        assertThat(describer.calls).isEqualTo(5);
    }

    @Test
    void aPageWithoutReadingViewThatTurnsOutLargeStopsTheNextPageAlsoWhileItWaitsForASlot() {
        // Ohne gespeicherte Lese-Ansicht zählt Seite 1 vorab als ein Bild, hat aber vier (Codex #1028).
        for (int i = 1; i <= 4; i++) {
            source.image("1", "a" + i, "a" + i + ".png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, (byte) i});
        }
        source.image("2", "b1", "b1.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 2, 1});
        com.aresstack.spaceharvester.application.configuration.ImageSettings three =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 3);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text"), page("2", "Text")).iterator(), () -> three, "wiki"::equals);

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", three, new RecordingContext());

        assertThat(outcome.images()).isEqualTo(4);
        assertThat(outcome.open()).as("Seite 2 wartet auf den nächsten Lauf").isEqualTo(1);
        assertThat(describer.calls).isEqualTo(4);
    }

    @Test
    void cancellingWhileAPageWaitsForASlotStartsNoFurtherPage() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        RecordingContext context = new RecordingContext();
        // Während Seite 1 beim Modell ist, bricht der Benutzer ab; Seite 2 wartet da schon auf den einzigen Platz.
        describer.during = () -> context.cancel = true;
        com.aresstack.spaceharvester.application.configuration.ImageSettings on =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text"), page("2", "Text")).iterator(), () -> on, "wiki"::equals);

        assertThatThrownBy(() -> automatic.run("wiki", on, context))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobCancelledException.class);
        assertThat(describer.calls).as("kein weiterer Aufruf nach dem Abbruch").isEqualTo(1);
    }

    @Test
    void aFailingModelGetsNoFurtherImagesOfTheSamePage() {
        source.image("1", "a1", "a1.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1});
        source.image("1", "a2", "a2.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2});
        source.image("1", "a3", "a3.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3});
        describer.fail = true;
        com.aresstack.spaceharvester.application.configuration.ImageSettings settings =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(), () -> settings, "wiki"::equals);
        RecordingContext context = new RecordingContext();

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", settings, context);

        assertThat(describer.sizes).as("nur das erste Bild, samt Wiederholungen").containsOnly(5);
        assertThat(outcome.failed()).isEqualTo(1);
        assertThat(context.events).extracting(e -> e.code()).contains(AutomaticImageDescriptions.MODEL_FAILED);
    }

    @Test
    void cancellingStopsBetweenTheImagesOfAPage() {
        source.image("1", "a1", "a1.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1});
        source.image("1", "a2", "a2.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2});
        com.aresstack.spaceharvester.application.configuration.ImageSettings settings =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(), () -> settings, "wiki"::equals);
        RecordingContext context = new RecordingContext();
        describer.during = () -> context.cancel = true;

        assertThatThrownBy(() -> automatic.run("wiki", settings, context))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobCancelledException.class);
        assertThat(describer.calls).as("nach dem laufenden Bild kein weiteres").isEqualTo(1);
    }

    @Test
    void imagesThatTheSourceCouldNotDeliverAreRetriedByTheNextRun() {
        source.image("1", "a1", "a1.png", PNG);
        source.unavailable = true;
        com.aresstack.spaceharvester.application.configuration.ImageSettings settings =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        PageImageAnalysis analysis = analysis();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> List.of(page("1", "Text")).iterator(), () -> settings, "wiki"::equals);
        automatic.run("wiki", settings, new RecordingContext());
        assertThat(describer.calls).isZero();

        source.unavailable = false;
        AutomaticImageDescriptions.Outcome again = automatic.run("wiki", settings, new RecordingContext());

        assertThat(again.images()).isEqualTo(1);
        assertThat(analysis.read(page("1", "Text")).describedCount()).isEqualTo(1);
    }

    @Test
    void aWaitingJobDescribesNothingOnceTheSettingIsSwitchedOff() {
        source.image("1", "a1", "a1.png", PNG);
        java.util.concurrent.atomic.AtomicReference<com.aresstack.spaceharvester.application.configuration.ImageSettings>
                settings = new java.util.concurrent.atomic.AtomicReference<>(
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500));
        describer.released = true;
        // Der Auftrag liest die Einstellung erst beim Start: Hier ist sie dann schon aus.
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(), () -> {
                    com.aresstack.spaceharvester.application.configuration.ImageSettings now = settings.get();
                    settings.set(now.withAutomatic(false));
                    return now;
                }, "wiki"::equals);

        automatic.automatic("wiki");

        assertThat(describer.calls).isZero();
    }

    @Test
    void theLimitPerRunLeavesTheRestForTheNextRunAndSaysHowManyAreOpen() {
        // Drei Seiten mit je zwei Bildern; deren Liste ist schon bekannt (gespeicherte Lese-Ansicht).
        for (String pageId : List.of("1", "2", "3")) {
            source.image(pageId, "a" + pageId, "a" + pageId + ".png", new byte[] {(byte) 0x89, 'P', 'N', 'G',
                    (byte) pageId.charAt(0), 1});
            source.image(pageId, "b" + pageId, "b" + pageId + ".png", new byte[] {(byte) 0x89, 'P', 'N', 'G',
                    (byte) pageId.charAt(0), 2});
        }
        PageImageAnalysis analysis = analysis();
        List<PageSnapshot> pages = List.of(page("1", "Text"), page("2", "Text"), page("3", "Text"));
        // Die Lese-Ansicht ablegen, ohne zu beschreiben: Die Liste der Bilder ist damit bekannt.
        for (PageSnapshot page : pages) {
            new ReadingViewStore(store).save(new StoredReadingView(page.key(), "sha256:lese", "1", "", "", "",
                    source.viewImages(page.key().pageId()), NOW));
        }
        RecordingContext context = new RecordingContext();
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> pages.iterator(),
                () -> new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 3),
                "wiki"::equals);

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki",
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 3), context);

        assertThat(outcome.images()).as("höchstens drei, also nur eine Seite mit zwei Bildern").isEqualTo(2);
        assertThat(outcome.open()).isEqualTo(4);
        assertThat(describer.calls).isEqualTo(2);
        assertThat(context.events).filteredOn(e -> AutomaticImageDescriptions.LIMIT_REACHED.equals(e.code()))
                .singleElement().satisfies(e -> {
                    assertThat(e.severity()).isEqualTo(
                            com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.WARNING);
                    assertThat(e.message()).contains("4 Bilder");
                });
        assertThat(context.incomplete).isTrue();

        // Der nächste Lauf holt die nächste Seite nach, ohne Bereits-Beschriebenes erneut zu senden.
        automatic.run("wiki", new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 3),
                new RecordingContext());
        assertThat(describer.calls).isEqualTo(4);
    }

    /**
     * Chat-Anhang (Angelo 27.11.2026): keine Quelle, keine Freigabe, nichts abgelegt; mehrere Bilder zugleich halten
     * die Grenze gleichzeitiger Anfragen ({@code ai.parallel}) ein und warten statt abzulehnen.
     */
    @Test
    void chatAttachmentsWaitForTheSharedLimitOfTheVisionModel() throws Exception {
        java.util.concurrent.atomic.AtomicInteger running = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        List<String> prompts = new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageDescriber vision = new ImageDescriber() {
            @Override
            public String id() {
                return "fake-vision";
            }

            @Override
            public String modelVersion() {
                return "gemma";
            }

            @Override
            public String describe(byte[] image, String mediaType) {
                return describe(image, mediaType, null);
            }

            @Override
            public String describe(byte[] image, String mediaType, String instruction) {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                try {
                    Thread.sleep(40);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                running.decrementAndGet();
                prompts.add(instruction);
                return "Text " + image.length;
            }

            @Override
            public boolean mayDescribe(String sourceId) {
                return false;
            }

            @Override
            public int parallel() {
                return 1;
            }
        };
        SourceCatalog catalog = new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of();
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return Optional.empty();
            }
        };
        Assets assets = new Assets(catalog, new FileSystemAssetStore(directory), 10_000_000, Clock.systemUTC(), vision,
                null);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, new Enrichments(PROJECTOR,
                List.of(new ImageProcessor(() -> vision)), new InMemoryEnrichmentStore(), Clock.systemUTC()),
                Clock.systemUTC());

        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(3);
        try {
            List<java.util.concurrent.Future<String>> answers = new java.util.ArrayList<>();
            for (int i = 1; i <= 3; i++) {
                byte[] image = new byte[i];
                answers.add(pool.submit(() -> analysis.describeAttachment(image, "image/png", "Was steht da?")));
            }
            List<String> texts = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<String> answer : answers) {
                texts.add(answer.get(10, java.util.concurrent.TimeUnit.SECONDS));
            }
            assertThat(texts).containsExactly("Text 1", "Text 2", "Text 3");
        } finally {
            pool.shutdownNow();
        }
        assertThat(peak.get()).isEqualTo(1);
        assertThat(prompts).containsOnly("Was steht da?");
        assertThat(analysis.visionModel()).contains("gemma");
    }

    /**
     * Echttest des automatischen Laufs gegen ein Ollama auf diesem Rechner (nur HTTP-API, erfundene Bilder): zwei
     * freigegebene Seiten mit je einem Bild, eine nicht freigegebene, Obergrenze 1 je Lauf. Läuft nur mit
     * {@code OLLAMA_PROBE_IMAGES} (Basis-URL, etwa {@code http://localhost:11434}) und optional
     * {@code OLLAMA_PROBE_VISION} (Vorgabe {@code gemma4:12b}); in der CI übersprungen.
     */
    @Test
    void theAutomaticRunDescribesRealImagesWithALocalVisionModel() throws Exception {
        String base = System.getenv("OLLAMA_PROBE_IMAGES");
        org.junit.jupiter.api.Assumptions.assumeTrue(base != null && !base.isBlank());
        String model = Optional.ofNullable(System.getenv("OLLAMA_PROBE_VISION")).orElse("gemma4:12b");
        com.aresstack.spaceharvester.source.ai.RemoteAi ai = new com.aresstack.spaceharvester.source.ai.RemoteAi(
                new com.aresstack.spaceharvester.ai.AiServiceSettings(java.net.URI.create(base), null, model, null,
                        null, java.time.Duration.ofMinutes(5), null, java.util.Set.of(), null, model)
                        .withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA),
                com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort.direct(),
                com.aresstack.spaceharvester.source.TrustBundles.NONE, "b1-probe");
        List<String> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageDescriber vision = new ImageDescriber() {
            @Override
            public String id() {
                return "live-vision";
            }

            @Override
            public String modelVersion() {
                return model;
            }

            @Override
            public String describe(byte[] image, String mediaType) {
                String text = ai.describeImage(image, mediaType);
                answers.add(text);
                return text;
            }

            @Override
            public boolean mayDescribe(String sourceId) {
                return false;
            }
        };
        source.image("1", "att1", "spool.png", drawn("SPOOL", java.awt.Color.RED));
        source.image("2", "att2", "nachtlauf.png", drawn("NACHTLAUF", java.awt.Color.BLUE));
        source.image("3", "att3", "geheim.png", drawn("GEHEIM", java.awt.Color.BLACK));
        java.util.Set<PageKey> released = java.util.Set.of(new PageKey("wiki", "1"), new PageKey("wiki", "2"));
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
        Assets assets = new Assets(catalog, new FileSystemAssetStore(directory), 10_000_000, Clock.systemUTC(), vision,
                null);
        assets.pageReleases(released::contains);
        store = new InMemoryEnrichmentStore();
        PageImageAnalysis analysis = new PageImageAnalysis(assets, new Enrichments(PROJECTOR,
                List.of(new ImageProcessor(() -> vision)), store, Clock.systemUTC()), Clock.systemUTC());
        List<PageSnapshot> pages = List.of(page("1", "Text"), page("2", "Text"), page("3", "Text"));
        com.aresstack.spaceharvester.application.configuration.ImageSettings limit =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 1);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> pages.iterator(), () -> limit, "wiki"::equals);

        long start = System.currentTimeMillis();
        RecordingContext first = new RecordingContext();
        AutomaticImageDescriptions.Outcome one = automatic.run("wiki", limit, first);
        RecordingContext second = new RecordingContext();
        AutomaticImageDescriptions.Outcome two = automatic.run("wiki", limit, second);
        RecordingContext third = new RecordingContext();
        AutomaticImageDescriptions.Outcome three = automatic.run("wiki", limit, third);
        long millis = System.currentTimeMillis() - start;

        System.out.println("LIVE model=" + model + " ms=" + millis + " runs=" + one + " / " + two + " / " + three);
        for (int i = 0; i < answers.size(); i++) {
            String answer = answers.get(i);
            System.out.println("LIVE answer" + (i + 1) + " chars=" + answer.length() + " spool="
                    + answer.toUpperCase(java.util.Locale.ROOT).contains("SPOOL") + " nachtlauf="
                    + answer.toUpperCase(java.util.Locale.ROOT).contains("NACHTLAUF") + " geheim="
                    + answer.toUpperCase(java.util.Locale.ROOT).contains("GEHEIM"));
        }
        for (PageSnapshot page : pages) {
            PageImages read = analysis.read(page);
            System.out.println("LIVE page" + page.key().pageId() + " " + read.state() + " " + read.reason() + " "
                    + read.images().stream().map(i -> i.status() + ":" + i.reason()).toList());
        }
        System.out.println("LIVE events1=" + first.events.stream().map(e -> e.severity() + ":" + e.code()).toList()
                + " events2=" + second.events.stream().map(e -> e.severity() + ":" + e.code()).toList()
                + " events3=" + third.events.stream().map(e -> e.severity() + ":" + e.code()).toList());
        assertThat(one.images()).isEqualTo(1);
        assertThat(one.open()).isEqualTo(1);
        assertThat(first.incomplete).isTrue();
        assertThat(two.images()).isEqualTo(1);
        assertThat(two.open()).isZero();
        assertThat(three.images()).as("alles beschrieben, das Modell wird nicht erneut gefragt").isZero();
        assertThat(answers).hasSize(2).allSatisfy(answer -> assertThat(answer).isNotBlank());
        assertThat(analysis.read(pages.get(2)).reason()).isEqualTo(PageImages.SOURCE_NOT_RELEASED);
        assertThat(first.failed + second.failed + third.failed).isZero();
    }

    /**
     * Echttest wie oben, aber mehrere gleichzeitig (Angelo 21.11.2026): vier freigegebene Seiten, {@code ai.parallel}
     * 2, ohne Obergrenze. Es laufen nie mehr als zwei Bilder zugleich beim Modell, und alle vier werden beschrieben.
     */
    @Test
    void theAutomaticRunDescribesRealImagesInParallelWithALocalVisionModel() throws Exception {
        String base = System.getenv("OLLAMA_PROBE_IMAGES");
        org.junit.jupiter.api.Assumptions.assumeTrue(base != null && !base.isBlank());
        String model = Optional.ofNullable(System.getenv("OLLAMA_PROBE_VISION")).orElse("gemma4:12b");
        com.aresstack.spaceharvester.source.ai.RemoteAi ai = new com.aresstack.spaceharvester.source.ai.RemoteAi(
                new com.aresstack.spaceharvester.ai.AiServiceSettings(java.net.URI.create(base), null, model, null,
                        null, java.time.Duration.ofMinutes(5), null, java.util.Set.of(), null, model)
                        .withProvider(com.aresstack.spaceharvester.ai.AiProvider.OLLAMA),
                com.aresstack.enterpriseai.proasteion.platform.network.HttpRoutePort.direct(),
                com.aresstack.spaceharvester.source.TrustBundles.NONE, "b1-probe");
        java.util.concurrent.atomic.AtomicInteger now = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger peak = new java.util.concurrent.atomic.AtomicInteger();
        List<String> answers = new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageDescriber vision = new ImageDescriber() {
            @Override
            public String id() {
                return "live-vision";
            }

            @Override
            public String modelVersion() {
                return model;
            }

            @Override
            public int parallel() {
                return 2;
            }

            @Override
            public String describe(byte[] image, String mediaType) {
                peak.accumulateAndGet(now.incrementAndGet(), Math::max);
                try {
                    String text = ai.describeImage(image, mediaType);
                    answers.add(text);
                    return text;
                } finally {
                    now.decrementAndGet();
                }
            }

            @Override
            public boolean mayDescribe(String sourceId) {
                return false;
            }
        };
        String[] words = {"SPOOL", "NACHTLAUF", "DRUCKER", "ARCHIV"};
        java.awt.Color[] colors = {java.awt.Color.RED, java.awt.Color.BLUE, java.awt.Color.GREEN.darker(),
                java.awt.Color.MAGENTA};
        java.util.Set<PageKey> released = new java.util.HashSet<>();
        List<PageSnapshot> pages = new ArrayList<>();
        for (int i = 0; i < words.length; i++) {
            String id = String.valueOf(i + 1);
            source.image(id, "att" + id, words[i].toLowerCase(java.util.Locale.ROOT) + ".png",
                    drawn(words[i], colors[i]));
            released.add(new PageKey("wiki", id));
            pages.add(page(id, "Text"));
        }
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
        Assets assets = new Assets(catalog, new FileSystemAssetStore(directory), 10_000_000, Clock.systemUTC(), vision,
                null);
        assets.pageReleases(released::contains);
        store = new InMemoryEnrichmentStore();
        PageImageAnalysis analysis = new PageImageAnalysis(assets, new Enrichments(PROJECTOR,
                List.of(new ImageProcessor(() -> vision)), store, Clock.systemUTC()), Clock.systemUTC());
        com.aresstack.spaceharvester.application.configuration.ImageSettings unlimited =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 0);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> pages.iterator(), () -> unlimited, "wiki"::equals);

        long start = System.currentTimeMillis();
        RecordingContext context = new RecordingContext();
        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", unlimited, context);
        long millis = System.currentTimeMillis() - start;

        System.out.println("LIVE-PARALLEL model=" + model + " ms=" + millis + " peak=" + peak.get() + " run="
                + outcome + " events=" + context.events.stream().map(e -> e.severity() + ":" + e.code()).toList());
        for (String answer : answers) {
            String upper = answer.toUpperCase(java.util.Locale.ROOT);
            System.out.println("LIVE-PARALLEL answer chars=" + answer.length() + " words="
                    + java.util.Arrays.stream(words).filter(upper::contains).toList());
        }
        assertThat(outcome.images()).isEqualTo(4);
        assertThat(outcome.failed()).isZero();
        assertThat(peak.get()).as("nie mehr als ai.parallel zugleich, aber mehr als eins").isEqualTo(2);
        assertThat(answers).hasSize(4).allSatisfy(answer -> assertThat(answer).isNotBlank());
    }

    private static byte[] drawn(String word, java.awt.Color color) throws java.io.IOException {
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(480, 240,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = image.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, 480, 240);
        g.setColor(color);
        g.fillOval(20, 40, 160, 160);
        g.setColor(java.awt.Color.BLACK);
        g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 44));
        g.drawString(word, 200, 135);
        g.dispose();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        javax.imageio.ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    @Test
    void theRunCarriesItsOwnerSoTheAppListsIt() {
        source.image("1", "att1", "ablauf.png", PNG);
        com.aresstack.spaceharvester.application.configuration.ImageSettings on =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text")).iterator(), () -> on, "wiki"::equals, "kern");

        assertThat(automatic.automatic("wiki").orElseThrow().parts()).singleElement()
                .satisfies(part -> assertThat(part.owner()).isEqualTo("kern"));
    }

    @Test
    void theRunDescribesSeveralPagesAtOnceUpToTheParallelSetting() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        describer.parallel = 2;
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicBoolean together = new java.util.concurrent.atomic.AtomicBoolean(true);
        describer.during = () -> {
            both.countDown();
            try {
                // Der Reihe nach käme der zweite Aufruf erst nach dem Ende des ersten: dann läuft die Frist ab.
                if (!both.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    together.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        com.aresstack.spaceharvester.application.configuration.ImageSettings on =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text"), page("2", "Text")).iterator(), () -> on, "wiki"::equals);

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", on, new RecordingContext());

        assertThat(together).as("beide Bilder gleichzeitig beim Modell").isTrue();
        assertThat(outcome.pages()).isEqualTo(2);
        assertThat(outcome.images()).isEqualTo(2);
    }

    @Test
    void parallelPagesReserveTheLimitBeforeTheyStartSoTheRunDoesNotOvershoot() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        source.image("3", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        describer.parallel = 3;
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(3);
        describer.during = () -> {
            started.countDown();
            try {
                // Hielte der Lauf die Grenze nicht vorab, liefen hier alle drei Seiten zugleich los.
                started.await(1, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        com.aresstack.spaceharvester.application.configuration.ImageSettings two =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 2);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis(),
                sourceId -> List.of(page("1", "Text"), page("2", "Text"), page("3", "Text")).iterator(), () -> two,
                "wiki"::equals);
        RecordingContext context = new RecordingContext();

        AutomaticImageDescriptions.Outcome outcome = automatic.run("wiki", two, context);

        assertThat(outcome.images()).isEqualTo(2);
        assertThat(outcome.open()).isEqualTo(1);
        assertThat(describer.calls).isEqualTo(2);
        assertThat(context.incomplete).isTrue();
    }

    @Test
    void aRunHasItsOwnContingentAndDoesNotCrowdOutTheUser() throws Exception {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        PageImageAnalysis analysis = analysis();
        java.util.concurrent.CountDownLatch inLane = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        describer.during = () -> {
            if (Thread.currentThread().getName().equals("spur")) {
                inLane.countDown();
                try {
                    release.await(5, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        // Kontingent 1: belegt der Auftrag seinen Platz, bekommt der Benutzer trotzdem sofort einen.
        java.util.concurrent.FutureTask<PageImages> background = new java.util.concurrent.FutureTask<>(
                () -> Assets.inLane("images:job-1", () -> analysis.describeInBackground(page("1", "Text"), entry -> { })));
        Thread lane = new Thread(background, "spur");
        lane.start();
        assertThat(inLane.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        PageImages user = analysis.describe(page("2", "Text"));
        release.countDown();

        assertThat(user.describedCount()).as("kein BUSY für den Benutzer").isEqualTo(1);
        assertThat(background.get(5, java.util.concurrent.TimeUnit.SECONDS).describedCount()).isEqualTo(1);
    }

    @Test
    void theBackgroundRunStepsBackWhileTheUserDescribesImages() throws Exception {
        source.image("1", "att1", "ablauf.png", PNG);
        PageImageAnalysis analysis = analysis();
        com.aresstack.spaceharvester.application.configuration.ImageSettings on =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
        AutomaticImageDescriptions automatic = new AutomaticImageDescriptions(jobs(), analysis,
                sourceId -> List.of(page("1", "Text")).iterator(), () -> on, "wiki"::equals);
        RecordingContext context = new RecordingContext();
        ModelPriority.Interactive user = analysis.priority().interactive();

        java.util.concurrent.CompletableFuture<AutomaticImageDescriptions.Outcome> run =
                java.util.concurrent.CompletableFuture.supplyAsync(() -> automatic.run("wiki", on, context));
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        while (!context.phases.contains(AutomaticImageDescriptions.WAITING_PHASE) && System.nanoTime() < end) {
            Thread.onSpinWait();
        }
        assertThat(context.phases).contains(AutomaticImageDescriptions.WAITING_PHASE);
        assertThat(describer.calls).as("solange der Benutzer beschreibt, nichts aus dem Hintergrund").isZero();

        user.close();

        assertThat(run.get(5, java.util.concurrent.TimeUnit.SECONDS).images()).isEqualTo(1);
    }

    // ---- D2: Bilder beschreiben als Hintergrundart ----

    private static final com.aresstack.spaceharvester.application.configuration.ImageSettings ON =
            new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 500);
    private static final com.aresstack.spaceharvester.application.access.AccessContext USER =
            new com.aresstack.spaceharvester.application.access.AccessContext("nutzer-1",
                    com.aresstack.spaceharvester.application.access.AccessContext.PrincipalKind.DEVELOPMENT);

    /** Eine Seite in Space {@code space} unter {@code parent}. */
    private static PageSnapshot page(String pageId, String space, String parent) {
        com.aresstack.spaceharvester.application.sources.PageMetadata metadata =
                new com.aresstack.spaceharvester.application.sources.PageMetadata(pageId,
                        "confluence:wiki.example.org/page/" + pageId, "Seite " + pageId, space, parent, "3",
                        Instant.parse("2026-10-15T10:30:00Z"),
                        java.net.URI.create("https://wiki.example.org/pages/viewpage.action?pageId=" + pageId),
                        com.aresstack.spaceharvester.application.sources.PageMetadata.sorted(Map.of()));
        return PageSnapshot.of("wiki", metadata, new com.aresstack.spaceharvester.application.snapshot.NativePayload(
                "Text".getBytes(java.nio.charset.StandardCharsets.UTF_8), "application/json", "test-text"),
                com.aresstack.spaceharvester.application.enrichment.EnrichmentTestPages.RETRIEVED);
    }

    private static com.aresstack.spaceharvester.application.jobs.Jobs jobs(
            com.aresstack.spaceharvester.application.jobs.JobStore store, java.util.concurrent.Executor executor) {
        return new com.aresstack.spaceharvester.application.jobs.Jobs(store, executor,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ImageDescriptionJobs images(com.aresstack.spaceharvester.application.jobs.Jobs jobs,
                                        PageImageAnalysis analysis, List<PageSnapshot> pages,
                                        com.aresstack.spaceharvester.application.configuration.ImageSettings settings,
                                        ImageDescriptionJobs.Scope scope) {
        return new ImageDescriptionJobs(new com.aresstack.spaceharvester.application.background.BackgroundJobs(jobs,
                "kern"), jobs, "kern", analysis, sourceId -> pages.iterator(),
                key -> pages.stream().filter(page -> page.key().equals(key)).findFirst(), scope, () -> settings,
                com.aresstack.spaceharvester.application.access.AccessContext.technicalClient("kern"));
    }

    @Test
    void anInterruptedRunContinuesByItselfAfterARestartAndAPausedOneStaysPaused() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "OPS", null));
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore store =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore();
        com.aresstack.spaceharvester.application.jobs.Jobs before = jobs(store, task -> { });
        com.aresstack.spaceharvester.application.background.BackgroundGroup group =
                images(before, analysis(), pages, ON, null).startAutomatic("wiki").orElseThrow();
        assertThat(group.parts()).as("ein Unterauftrag je Space").hasSize(2);
        String paused = group.parts().get(1).id();
        before.pause(paused, "kern");

        // Neustart: dieselbe Ablage, neue Verwaltung.
        com.aresstack.spaceharvester.application.jobs.Jobs after = jobs(store, Runnable::run);
        ImageDescriptionJobs restarted = images(after, analysis(), pages, ON, null);
        assertThat(restarted.background().resumeInterrupted()).hasSize(1);

        assertThat(after.status(group.parts().get(0).id()).state())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
        assertThat(after.status(paused).state()).as("vom Benutzer angehalten bleibt angehalten")
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.PAUSED);
        assertThat(describer.calls).isEqualTo(1);
    }

    @Test
    void aUserRunContinuesAfterARestartWithTheUsersRightsNeverTechnically() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", "1"));
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore store =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore();
        List<com.aresstack.spaceharvester.application.access.AccessContext> callers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageDescriptionJobs.Scope scope = (caller, root) -> {
            callers.add(caller);
            return com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                    List.of(new PageKey("wiki", "1"), new PageKey("wiki", "2")));
        };
        images(jobs(store, task -> { }), analysis(), pages, ON, scope).start(USER, "wiki", "DEV", "1", true)
                .orElseThrow();
        callers.clear();

        com.aresstack.spaceharvester.application.jobs.Jobs after = jobs(store, Runnable::run);
        images(after, analysis(), pages, ON, scope).background().resumeInterrupted();

        assertThat(callers).isNotEmpty().allSatisfy(caller -> {
            assertThat(caller.principal()).isEqualTo("nutzer-1");
            assertThat(caller.kind()).isNotEqualTo(
                    com.aresstack.spaceharvester.application.access.AccessContext.PrincipalKind.TECHNICAL_CLIENT);
        });
        assertThat(describer.calls).isEqualTo(1);
    }

    @Test
    void aUserRunWhoseCallerCannotBeRebuiltWaitsForPlayAndThenRunsWithTheUser() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        com.aresstack.spaceharvester.application.access.AccessContext endUser =
                com.aresstack.spaceharvester.application.access.AccessContext.endUser("nutzer-2", "app",
                        new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore store =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore();
        String id = images(jobs(store, task -> { }), analysis(), pages, ON, null)
                .start(endUser, "wiki", "DEV", null, false).orElseThrow().parts().get(0).id();

        com.aresstack.spaceharvester.application.jobs.Jobs after = jobs(store, Runnable::run);
        ImageDescriptionJobs restarted = images(after, analysis(), pages, ON, null);
        restarted.background().resumeInterrupted();

        com.aresstack.spaceharvester.application.jobs.JobStatus waiting = after.status(id);
        assertThat(waiting.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.PAUSED);
        assertThat(after.events(id)).anySatisfy(event ->
                assertThat(event.code()).isEqualTo(ImageDescriptionJobs.RESUME_BY_HAND));
        assertThat(describer.calls).isZero();

        // Abnahme #1112: ▶ eines anderen Benutzers lehnt der Lauf ab, auch über diesen Weg der App.
        com.aresstack.spaceharvester.application.access.AccessContext other =
                com.aresstack.spaceharvester.application.access.AccessContext.endUser("nutzer-3", "app",
                        new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> restarted.resume(other, id))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobNotResumableException.class)
                .hasMessageContaining("nur der Benutzer");
        assertThat(after.status(id).state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.PAUSED);
        assertThat(describer.calls).isZero();

        restarted.resume(endUser, id);
        assertThat(after.status(id).state())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
        assertThat(describer.calls).isEqualTo(1);
    }

    @Test
    void aPageWithItsSubpagesDescribesTheSubtreeOnly() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        source.image("3", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        // 2 ist Unterseite von 1, 3 eine Schwester von 1.
        List<PageSnapshot> pages = List.of(page("1", "DEV", "0"), page("2", "DEV", "1"), page("3", "DEV", "0"));
        PageImageAnalysis analysis = analysis();
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();

        images(jobs, analysis, pages, ON, null).start(USER, "wiki", "DEV", "1", true).orElseThrow();

        assertThat(analysis.read(pages.get(0)).describedCount()).isEqualTo(1);
        assertThat(analysis.read(pages.get(1)).describedCount()).isEqualTo(1);
        assertThat(analysis.read(pages.get(2)).describedCount()).as("nicht im Teilbaum").isZero();
        assertThat(describer.calls).isEqualTo(2);
    }

    @Test
    void theButtonOfAPageIsAnOrdinaryJobNotABackgroundOne() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();
        List<ImagePayload.Entry> seen = new ArrayList<>();

        PageImages images = images(jobs, analysis(), pages, ON, null).describePage("nutzer", pages.get(0), seen::add);

        assertThat(images.describedCount()).isEqualTo(1);
        assertThat(seen).hasSize(1);
        assertThat(jobs.listAll(com.aresstack.spaceharvester.application.jobs.JobType.BACKGROUND)).isEmpty();
        assertThat(jobs.listAll(com.aresstack.spaceharvester.application.jobs.JobType.ENRICHMENT)).singleElement()
                .satisfies(job -> {
                    assertThat(job.owner()).isEqualTo("nutzer");
                    assertThat(job.parameters()).containsEntry(AutomaticImageDescriptions.TASK_PARAMETER,
                            AutomaticImageDescriptions.TASK);
                    assertThat(job.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
                });
    }

    @Test
    void pagesWithoutReleaseAreYellowForAUserRunAndSilentForTheAutomatic() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        describer.released = false;
        Assets assets = assets();
        assets.pageReleases(new PageKey("wiki", "1")::equals);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));
        // 3 ist ebenfalls nicht freigegeben, hat aber keine Bilder und zählt darum nicht (wie missingNotReleased).
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null), page("3", "DEV", null));
        for (PageSnapshot page : pages) {
            new ReadingViewStore(store).save(new StoredReadingView(page.key(), "sha256:lese", "1", "", "", "",
                    source.viewImages(page.key().pageId()), NOW));
        }
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();
        ImageDescriptionJobs images = images(jobs, analysis, pages, ON, null);

        String user = images.start(USER, "wiki", "DEV", null, false).orElseThrow().parts().get(0).id();
        assertThat(jobs.events(user)).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(ImageDescriptionJobs.NOT_RELEASED);
            assertThat(event.severity()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.WARNING);
            assertThat(event.message()).startsWith("1 Seite ohne KI-Freigabe");
        });
        source.image("1", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        new ReadingViewStore(store).save(new StoredReadingView(pages.get(0).key(), "sha256:lese2", "2", "", "", "",
                source.viewImages("1"), NOW));
        String automatic = images.startAutomatic("wiki").orElseThrow().parts().get(0).id();
        assertThat(jobs.events(automatic))
                .noneSatisfy(event -> assertThat(event.code()).isEqualTo(ImageDescriptionJobs.NOT_RELEASED));
    }

    @Test
    void missingNamesReleasedPagesWithUndescribedImagesForThePlanner() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null), page("3", "DEV", null));
        PageImageAnalysis analysis = analysis();
        for (PageSnapshot page : pages) {
            new ReadingViewStore(store).save(new StoredReadingView(page.key(), "sha256:lese", "1", "", "", "",
                    source.viewImages(page.key().pageId()), NOW));
        }
        ImageDescriptionJobs images = images(jobs(), analysis, pages, ON, null);
        analysis.describe(pages.get(0));
        com.aresstack.spaceharvester.application.snapshot.PageSet all =
                com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                        pages.stream().map(PageSnapshot::key).toList());

        assertThat(images.missing(USER, all).pagesOf("wiki")).as("1 ist beschrieben, 3 hat keine Bilder")
                .containsExactly("2");
        // Wie der Lauf: eine Seite ohne gespeicherte Lese-Ansicht und ein gescheitertes Ergebnis zählen mit.
        PageSnapshot unread = page("4", "DEV", null);
        PageSnapshot failed = page("5", "DEV", null);
        List<PageSnapshot> more = List.of(pages.get(0), pages.get(1), pages.get(2), unread, failed);
        new ReadingViewStore(store).save(new StoredReadingView(failed.key(), "sha256:lese", "1", "", "", "",
                List.of(), NOW));
        store.save(new com.aresstack.spaceharvester.application.enrichment.Enrichment(failed.key(),
                ImagePayload.PROCESSOR, "1", "sha256:alt", failed.revision(),
                com.aresstack.spaceharvester.application.enrichment.Enrichment.Status.FAILED, "{}", List.of(),
                "model-failed", NOW));
        ImageDescriptionJobs wider = images(jobs(), analysis, more, ON, null);
        assertThat(wider.missing(USER, com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                more.stream().map(PageSnapshot::key).toList())).pagesOf("wiki"))
                .containsExactlyInAnyOrder("2", "4", "5");
        describer.available = false;
        assertThat(images.missing(USER, all).isEmpty()).as("ohne Modell nichts").isTrue();
    }

    @Test
    void theImagesOfOnePageAreDescribedAtOnce() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("1", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        describer.parallel = 2;
        java.util.concurrent.CountDownLatch both = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.atomic.AtomicBoolean together = new java.util.concurrent.atomic.AtomicBoolean(true);
        describer.during = () -> {
            both.countDown();
            try {
                if (!both.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    together.set(false);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        AutomaticImageDescriptions.Outcome outcome = images(jobs(), analysis(), pages, ON, null)
                .run("wiki", ON, new RecordingContext());

        assertThat(together).as("beide Bilder derselben Seite zugleich beim Modell").isTrue();
        assertThat(outcome.images()).isEqualTo(2);
    }

    @Test
    void theLimitPerRunHoldsForTheWholeGroupAcrossItsSpaces() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        com.aresstack.spaceharvester.application.configuration.ImageSettings one =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 1);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "OPS", null));
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();

        com.aresstack.spaceharvester.application.background.BackgroundGroup group =
                images(jobs, analysis(), pages, one, null).startAutomatic("wiki").orElseThrow();

        assertThat(group.parts()).hasSize(2);
        assertThat(describer.calls).as("eine Grenze für beide Spaces zusammen").isEqualTo(1);
        assertThat(group.parts().stream().flatMap(part -> jobs.events(part.id()).stream()))
                .anySatisfy(event -> assertThat(event.code()).isEqualTo(AutomaticImageDescriptions.LIMIT_REACHED));
    }

    @Test
    void theButtonKeepsItsPoolUntilEveryImageOfThePageIsHandedIn() {
        // Das erste Bild ist ein sofort übersprungenes Symbol; das zweite muss trotzdem noch einen Platz bekommen.
        source.image("1", "att1", "skizze.svg", "<svg/>".getBytes(StandardCharsets.UTF_8));
        source.image("1", "att2", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));

        PageImages images = images(jobs(), analysis(), pages, ON, null).describePage("nutzer", pages.get(0),
                entry -> { });

        assertThat(images.images()).extracting(ImagePayload.Entry::status)
                .containsExactly(ImageStatus.SKIPPED, ImageStatus.DESCRIBED);
    }

    @Test
    void theButtonGivesEveryImageItsTryAndStoresThePageWhenTheModelFails() {
        source.image("1", "a1", "a1.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1});
        source.image("1", "a2", "a2.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 1, 2});
        describer.fail = true;
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        PageImageAnalysis analysis = analysis();

        PageImages images = images(jobs(), analysis, pages, ON, null).describePage("nutzer", pages.get(0),
                entry -> { });

        assertThat(images.images()).extracting(ImagePayload.Entry::status)
                .containsExactly(ImageStatus.FAILED, ImageStatus.FAILED);
        assertThat(describer.sizes).as("beide Bilder beim Modell").contains(5, 6);
        assertThat(analysis.read(pages.get(0)).images()).as("Seite gespeichert").hasSize(2)
                .allSatisfy(image -> assertThat(image.status()).isEqualTo(ImageStatus.FAILED));
    }

    @Test
    void theButtonWithoutReleaseEndsYellowNotRed() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.released = false;
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        com.aresstack.spaceharvester.application.jobs.Jobs jobs = jobs();

        assertThatThrownBy(() -> images(jobs, analysis(), pages, ON, null).describePage("nutzer", pages.get(0),
                entry -> { })).isInstanceOf(ImageDescriptionException.class);

        com.aresstack.spaceharvester.application.jobs.JobStatus job = jobs.listAll(
                com.aresstack.spaceharvester.application.jobs.JobType.ENRICHMENT).get(0);
        assertThat(job.state()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
        assertThat(jobs.events(job.id())).anySatisfy(event -> {
            assertThat(event.code()).isEqualTo(ImageDescriptionJobs.NOT_RELEASED);
            assertThat(event.severity()).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobEvent.Severity.WARNING);
        });
        assertThat(describer.calls).isZero();
    }

    @Test
    void aCancelledPageGivesItsReservationBackToTheGroup() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        com.aresstack.spaceharvester.application.configuration.ImageSettings one =
                new com.aresstack.spaceharvester.application.configuration.ImageSettings(true, 1);
        ImageDescriptionJobs images = images(jobs(), analysis(), pages, one, null);
        ImageDescriptionJobs.Budget budget = new ImageDescriptionJobs.Budget();
        RecordingContext cancelled = new RecordingContext();
        describer.during = () -> cancelled.cancel = true;

        assertThatThrownBy(() -> images.run(pages.iterator(), page -> true, one, cancelled, budget, false))
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.JobCancelledException.class);
        assertThat(budget.reserved).isZero();

        describer.during = () -> { };
        AutomaticImageDescriptions.Outcome resumed = images.run(pages.iterator(), page -> true, one,
                new RecordingContext(), budget, false);
        assertThat(resumed.images()).as("der fortgesetzte Teil beschreibt weiter").isEqualTo(1);
    }

    @Test
    void aUserRunCreatesPartsOnlyForVisibleSpacesAndChecksEverySpaceWithTheUser() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        source.image("3", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null), page("3", "OPS", null));
        List<com.aresstack.spaceharvester.application.access.AccessContext> callers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        // Der Benutzer sieht nur Seite 1.
        ImageDescriptionJobs.Scope scope = (caller, root) -> {
            callers.add(caller);
            return com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(List.of(new PageKey("wiki", "1")));
        };

        com.aresstack.spaceharvester.application.background.BackgroundGroup group =
                images(jobs(), analysis(), pages, ON, scope).start(USER, "wiki", null, null, false).orElseThrow();

        assertThat(group.parts()).as("kein Teil für OPS").singleElement()
                .satisfies(part -> assertThat(part.partitionKey()).isEqualTo("DEV"));
        assertThat(describer.calls).as("Seite 2 sieht der Benutzer nicht").isEqualTo(1);
        assertThat(callers).hasSizeGreaterThanOrEqualTo(2)
                .allSatisfy(caller -> assertThat(caller.principal()).isEqualTo("nutzer-1"));
    }

    @Test
    void aNewOccasionWaitsBesideARunningAutomaticPartButNotBesideAWaitingOne() throws Exception {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        describer.during = () -> {
            running.countDown();
            try {
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        try {
            com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs
                    .Jobs(new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), threads,
                    new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            ImageDescriptionJobs images = images(jobs, analysis(), pages, ON, null);
            assertThat(images.startAutomatic("wiki")).isPresent();
            assertThat(running.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            assertThat(images.startAutomatic("wiki")).as("neben dem laufenden darf einer warten").isPresent();
            assertThat(images.startAutomatic("wiki")).as("hinter dem wartenden keiner mehr").isEmpty();
        } finally {
            release.countDown();
            threads.shutdown();
            threads.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void theButtonStartsAtOnceWhileABackgroundPartRuns() throws Exception {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "OPS", null));
        java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors.newCachedThreadPool();
        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        describer.parallel = 2;
        // Nur der erste Aufruf (der Hintergrundteil an Seite 1) hängt, bis der Knopf fertig ist.
        describer.during = () -> {
            if (first.getAndSet(false)) {
                running.countDown();
                try {
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
        try {
            com.aresstack.spaceharvester.application.jobs.Jobs jobs = new com.aresstack.spaceharvester.application.jobs
                    .Jobs(new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), threads,
                    new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                    Clock.fixed(NOW, ZoneOffset.UTC));
            ImageDescriptionJobs images = images(jobs, analysis(), List.of(pages.get(0)), ON, null);
            images.startAutomatic("wiki").orElseThrow();
            assertThat(running.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<PageImages> button = threads.submit(() ->
                    images.describePage("nutzer", pages.get(1), entry -> { }));

            assertThat(button.get(10, java.util.concurrent.TimeUnit.SECONDS).describedCount())
                    .as("der Klick wartet nicht hinter dem laufenden Hintergrundteil").isEqualTo(1);
        } finally {
            release.countDown();
            threads.shutdown();
            threads.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    @Test
    void aPlannerPartWithoutOwnerStaysPausedAfterARestart() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null));
        PageImageAnalysis analysis = analysis();
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore store =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore();
        ImageDescriptionJobs before = images(jobs(store, task -> { }), analysis, pages, ON, null);
        String id = before.background().start(com.aresstack.spaceharvester.application.background.BackgroundJobs
                .newGroupId(), ImageDescriptionJobs.KIND, "Bilder beschreiben", List.of(
                new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", "DEV",
                        Map.of(ImageDescriptionJobs.PAGES, "1")))).parts().get(0).id();

        com.aresstack.spaceharvester.application.jobs.Jobs after = jobs(store, Runnable::run);
        images(after, analysis, pages, ON, null).background().resumeInterrupted();

        assertThat(after.status(id).state()).as("nie mit technischen Rechten")
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.PAUSED);
        assertThat(after.events(id)).anySatisfy(event ->
                assertThat(event.code()).isEqualTo(ImageDescriptionJobs.RESUME_BY_HAND));
        assertThat(describer.calls).isZero();
    }

    @Test
    void aPlannerPartRunsItsPagesOnlyWithItsOwnersCurrentRightsAfterARestart() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        source.image("3", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null), page("3", "DEV", null));
        PageImageAnalysis analysis = analysis();
        com.aresstack.spaceharvester.application.jobs.InMemoryJobStore store =
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore();
        List<com.aresstack.spaceharvester.application.access.AccessContext> callers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        // Seit dem Start hat der Eigentümer das Recht an Seite 2 verloren.
        ImageDescriptionJobs.Scope scope = (caller, root) -> {
            callers.add(caller);
            return com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(List.of(new PageKey("wiki", "1"),
                    new PageKey("wiki", "3")));
        };
        ImageDescriptionJobs before = images(jobs(store, task -> { }), analysis, pages, ON, scope);
        String id = before.background().start(com.aresstack.spaceharvester.application.background.BackgroundJobs
                .newGroupId(), ImageDescriptionJobs.KIND, "Bilder beschreiben", List.of(
                new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", "DEV",
                        Map.of(ImageDescriptionJobs.PAGES, "1\n2", ImageDescriptionJobs.PRINCIPAL, "nutzer-1",
                                ImageDescriptionJobs.PRINCIPAL_KIND, "DEVELOPMENT")))).parts().get(0).id();

        com.aresstack.spaceharvester.application.jobs.Jobs after = jobs(store, Runnable::run);
        images(after, analysis, pages, ON, scope).background().resumeInterrupted();

        assertThat(after.status(id).state())
                .isEqualTo(com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED);
        assertThat(callers).isNotEmpty().allSatisfy(caller -> {
            assertThat(caller.principal()).isEqualTo("nutzer-1");
            assertThat(caller.kind()).isEqualTo(
                    com.aresstack.spaceharvester.application.access.AccessContext.PrincipalKind.DEVELOPMENT);
        });
        assertThat(describer.calls).as("nur Seite 1: 2 ohne Recht, 3 nicht in der Liste").isEqualTo(1);
        assertThat(analysis.read(pages.get(0)).describedCount()).isEqualTo(1);
    }

    @Test
    void aWaitingPlannerPartForSomePagesDoesNotSwallowTheAutomaticRunOfTheSpace() {
        source.image("1", "att1", "ablauf.png", PNG);
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null));
        ImageDescriptionJobs images = images(jobs(new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(),
                task -> { }), analysis(), pages, ON, null);
        images.background().start(com.aresstack.spaceharvester.application.background.BackgroundJobs.newGroupId(),
                ImageDescriptionJobs.KIND, "Bilder beschreiben", List.of(
                        new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", "DEV",
                                Map.of(ImageDescriptionJobs.PAGES, "2"))));

        assertThat(images.startAutomatic("wiki")).as("der wartende Teil deckt nur Seite 2 ab").isPresent();
    }

    @Test
    void theServicePlannerRunsImagesWithTheStartingEndUserAndOnlyTheMissingPages() {
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 7});
        source.image("3", "att3", "karte.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        List<PageSnapshot> pages = List.of(page("1", "DEV", null), page("2", "DEV", null), page("3", "DEV", null));
        com.aresstack.spaceharvester.application.access.AccessContext endUser =
                com.aresstack.spaceharvester.application.access.AccessContext.endUser("nutzer-2", "app",
                        new com.aresstack.spaceharvester.application.access.UserCredential("wiki", "erfunden"));
        List<com.aresstack.spaceharvester.application.access.AccessContext> callers =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        ImageDescriptionJobs.Scope scope = (caller, root) -> {
            callers.add(caller);
            return com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(
                    pages.stream().map(PageSnapshot::key).toList());
        };
        ImageDescriptionJobs images = images(jobs(), analysis(), pages, ON, scope);
        com.aresstack.spaceharvester.application.background.services.AnalysisService.Missing visible =
                (caller, set) -> new com.aresstack.spaceharvester.application.background.services.MissingPages(
                        Map.of(new com.aresstack.spaceharvester.application.background.services.MissingPages.Scope(
                                "wiki", "DEV"), set.pagesOf("wiki")), 0);
        com.aresstack.spaceharvester.application.background.services.ServicePlanner planner =
                new com.aresstack.spaceharvester.application.background.services.ServicePlanner(images.background(),
                        visible);
        planner.register(new com.aresstack.spaceharvester.application.background.services.AnalysisService("images",
                "Bilder beschreiben", () -> com.aresstack.spaceharvester.application.background.services
                .ServiceStart.MANUAL, List.of(), ImageDescriptionJobs.KIND, visible, images::plannerParameters));

        com.aresstack.spaceharvester.application.background.BackgroundGroup group = planner.start(endUser, "images",
                com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(List.of(new PageKey("wiki", "1"),
                        new PageKey("wiki", "2")))).group().orElseThrow();

        assertThat(group.parts()).singleElement().satisfies(part -> {
            assertThat(part.parameter(ImageDescriptionJobs.PRINCIPAL)).isEqualTo("nutzer-2");
            assertThat(part.parameter(ImageDescriptionJobs.CALLER)).isNotBlank();
        });
        assertThat(callers).as("der erste Lauf rechnet mit dem Endnutzer, der startete").isNotEmpty()
                .allSatisfy(caller -> assertThat(caller).isEqualTo(endUser));
        assertThat(describer.calls).as("Seite 3 gehört nicht zur Auswahl").isEqualTo(2);
    }

    private static com.aresstack.spaceharvester.application.jobs.Jobs jobs() {
        return new com.aresstack.spaceharvester.application.jobs.Jobs(
                new com.aresstack.spaceharvester.application.jobs.InMemoryJobStore(), Runnable::run,
                new com.aresstack.spaceharvester.application.jobs.JobLimits(2, 10, java.time.Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Hält Ereignisse und Zähler eines Laufs fest, ohne Auftragsverwaltung. */
    private static final class RecordingContext implements com.aresstack.spaceharvester.application.jobs.JobContext {

        final List<com.aresstack.spaceharvester.application.jobs.JobEvent.Draft> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        long failed;
        boolean incomplete;

        @Override
        public String jobId() {
            return "job-1";
        }

        volatile boolean cancel;
        final List<String> phases = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void phase(String component, String phase) {
            phases.add(phase);
        }

        @Override
        public void checkpoint() {
            if (cancel) {
                throw new com.aresstack.spaceharvester.application.jobs.JobCancelledException(jobId());
            }
        }

        @Override
        public boolean cancelRequested() {
            return cancel;
        }

        @Override
        public void discovered(long pages) {
        }

        @Override
        public void processed(long pages) {
        }

        @Override
        public void written(long pages) {
        }

        @Override
        public void failed(long pages) {
            failed += pages;
        }

        @Override
        public void record(com.aresstack.spaceharvester.application.jobs.JobEvent.Draft event) {
            events.add(event);
        }

        @Override
        public void incomplete() {
            incomplete = true;
        }
    }

    @Test
    void aDescribedImageAttachmentStandsInTheFullTextIndexAtOnceButNotAfterDescribeOnce() {
        // B2: Die Beschreibung aus dem Reiter „Bilder“ ersetzt den Eintrag des Bildanhangs (bisher nur Dateiname).
        source.image("1", "att1", "ablauf.png", PNG);
        source.image("2", "att2", "plan.png", new byte[] {(byte) 0x89, 'P', 'N', 'G', 9});
        try (com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex index =
                     com.aresstack.spaceharvester.persistence.lucene.LucenePageTextIndex.open(directory.resolve("text"))) {
            PageKey one = new PageKey("wiki", "1");
            PageKey two = new PageKey("wiki", "2");
            index.putAttachment(new com.aresstack.spaceharvester.application.query.PageTextIndex.AttachmentEntry(one,
                    "DEV", "att1", "ablauf.png", "", "sha256:alt", "image/png"));
            index.putAttachment(new com.aresstack.spaceharvester.application.query.PageTextIndex.AttachmentEntry(two,
                    "DEV", "att2", "plan.png", "", "sha256:alt", "image/png"));
            Assets assets = new Assets(catalog(), new FileSystemAssetStore(directory), 1000,
                    Clock.fixed(NOW, ZoneOffset.UTC), describer, null, index);
            PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));

            analysis.describe(page("1", "Text"));

            assertThat(index.search("Bytes", java.util.Set.of("wiki"),
                    com.aresstack.spaceharvester.application.sources.SearchSpaces.all(), 10))
                    .extracting(com.aresstack.spaceharvester.application.query.PageTextIndex.Match::attachmentId)
                    .containsExactly("att1");

            // „Nur diese Seite einmalig beschreiben“ speichert keine Freigabe, also auch nichts im Index.
            describer.released = false;
            analysis.describeOnce(page("2", "Text"));
            assertThat(index.attachmentInputSha256(two, "att2")).contains("sha256:alt");
        }
    }

    @Test
    void aReleaseWithdrawnWhileTheModelDescribesLeavesNothingBehind() {
        source.image("1", "att1", "ablauf.png", PNG);
        describer.released = false;
        PageKey one = new PageKey("wiki", "1");
        java.util.Set<PageKey> released = new java.util.HashSet<>(List.of(one));
        Assets assets = assets();
        assets.pageReleases(released::contains);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments(), Clock.fixed(NOW, ZoneOffset.UTC));
        // Die Rücknahme (ADR 0028) läuft, während das Modell noch beschreibt, und findet noch nichts.
        describer.during = () -> {
            released.remove(one);
            analysis.withdraw(List.of(page("1", "Text")), released::contains);
        };

        assertThatThrownBy(() -> analysis.describe(page("1", "Text")))
                .isInstanceOfSatisfying(ImageDescriptionException.class,
                        e -> assertThat(e.reason()).isEqualTo(ImageDescriptionException.Reason.NOT_RELEASED));

        assertThat(store.find(one)).isEmpty();
        describer.during = () -> { };
        released.add(one);
        assertThat(analysis.describe(page("1", "Text")).describedCount()).isEqualTo(1);
        assertThat(describer.calls).as("die Beschreibung von eben blieb nicht liegen").isEqualTo(2);
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
    void aFailedModelCallSaysWhetherTheModelTimedOutAnsweredEmptyOrWasUnreachable() {
        assertThat(PageImageAnalysis.failureReason(new com.aresstack.spaceharvester.application.assets
                .ImageDescriptionException(com.aresstack.spaceharvester.application.assets.ImageDescriptionException
                .Reason.FAILED, "Beschreibung fehlgeschlagen", new com.aresstack.spaceharvester.ai.AiServiceException(
                com.aresstack.spaceharvester.ai.AiServiceException.Reason.TIMEOUT, "Frist"))))
                .isEqualTo(PageImageAnalysis.MODEL_TIMEOUT);
        assertThat(PageImageAnalysis.failureReason(new com.aresstack.spaceharvester.application.assets
                .ImageDescriptionException(com.aresstack.spaceharvester.application.assets.ImageDescriptionException
                .Reason.FAILED, "Leere Beschreibung", null))).isEqualTo(PageImageAnalysis.MODEL_EMPTY);
        assertThat(PageImageAnalysis.failureReason(new com.aresstack.spaceharvester.application.assets
                .ImageDescriptionException(com.aresstack.spaceharvester.application.assets.ImageDescriptionException
                .Reason.FAILED, "Beschreibung fehlgeschlagen", new com.aresstack.spaceharvester.ai.AiServiceException(
                com.aresstack.spaceharvester.ai.AiServiceException.Reason.UNREACHABLE, "weg"))))
                .isEqualTo(PageImageAnalysis.MODEL_UNREACHABLE);
        assertThat(PageImageAnalysis.modelFailure(PageImageAnalysis.MODEL_TIMEOUT)).isTrue();
        assertThat(PageImageAnalysis.modelFailure("model-busy")).isFalse();
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
    void theMarksOfAVideoAreVirtualAttachmentsAndDescribedWithTheirOwnPrompt() {
        byte[] second = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 4, 5};
        Assets assets = assets();
        Enrichments enrichments = enrichments();
        PageKey video = new PageKey("wiki", "1");
        List<Long> marks = List.of(2_000L, 301_300L, 3_725_000L);
        com.aresstack.spaceharvester.application.marks.VideoMarks.in(store).save(video, "3", marks);
        com.aresstack.spaceharvester.application.sources.PageAttachmentList none =
                new com.aresstack.spaceharvester.application.sources.PageAttachmentList("wiki", "S",
                        source.attachments("1"), NOW);

        // Unter „Anhänge“: je Marke ein virtueller Anhang der Art Marke, ohne Standbild noch ohne Größe.
        assertThat(assets.withVideoMarks(none, "3", List.of())).as("ohne Marken keine virtuellen Anhänge")
                .isSameAs(none);
        assertThat(assets.withVideoMarks(none, "3", marks).attachments().attachments())
                .extracting(PageAttachment::title, PageAttachment::fileSize).containsExactly(
                        org.assertj.core.groups.Tuple.tuple("00:00:02", null),
                        org.assertj.core.groups.Tuple.tuple("00:05:01", null),
                        org.assertj.core.groups.Tuple.tuple("01:02:05", null));
        assertThat(com.aresstack.spaceharvester.application.assets.AssetKind.of(
                assets.withVideoMarks(none, "3", marks).attachments().attachments().get(0).mediaType(), "00:00:02"))
                .isEqualTo(com.aresstack.spaceharvester.application.assets.AssetKind.MARK);
        String mark = assets.withVideoMarks(none, "3", marks).attachments().attachments().get(1).attachmentId();
        assertThatThrownBy(() -> assets.content(assets.withVideoMarks(none, "3", marks), mark, false))
                .isInstanceOf(com.aresstack.spaceharvester.application.sources.SourceAccessException.class);

        assets.putVideoFrame("wiki", "1", "3", new Assets.VideoFrame(2_000, PNG));
        assets.putVideoFrame("wiki", "1", "3", new Assets.VideoFrame(301_300, second));

        assertThat(assets.missingVideoFrames("wiki", "1", "3", marks)).containsExactly(3_725_000L);
        assertThat(assets.content(assets.withVideoMarks(none, "3", marks), mark, false).content()).isEqualTo(second);
        PageImageAnalysis analysis = new PageImageAnalysis(assets, enrichments, Clock.fixed(NOW, ZoneOffset.UTC));
        // Vor dem Beschreiben dieselbe Liste ohne Text.
        assertThat(analysis.read(page("1", "")).images()).extracting(ImagePayload.Entry::title,
                ImagePayload.Entry::status, ImagePayload.Entry::description).containsExactly(
                org.assertj.core.groups.Tuple.tuple("00:00:02", ImageStatus.PENDING, null),
                org.assertj.core.groups.Tuple.tuple("00:05:01", ImageStatus.PENDING, null),
                org.assertj.core.groups.Tuple.tuple("01:02:05", ImageStatus.PENDING, null));
        PageImages images = analysis.describe(page("1", ""));

        assertThat(images.state()).isEqualTo(PageImages.State.CURRENT);
        assertThat(images.images()).extracting(ImagePayload.Entry::title, ImagePayload.Entry::status)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("00:00:02", ImageStatus.DESCRIBED),
                        org.assertj.core.groups.Tuple.tuple("00:05:01", ImageStatus.DESCRIBED),
                        org.assertj.core.groups.Tuple.tuple("01:02:05", ImageStatus.PENDING));
        assertThat(describer.instructions).as("der Prompt der Marke")
                .containsOnly(com.aresstack.spaceharvester.application.marks.VideoMark.INSTRUCTION);
        assertThat(source.loads).as("die Bytes liegen in der Ablage").isZero();
        // Das dritte Standbild: das Ergebnis gilt nicht mehr.
        assets.putVideoFrame("wiki", "1", "3", new Assets.VideoFrame(3_725_000, PNG));
        assertThat(analysis.read(page("1", "")).state()).isEqualTo(PageImages.State.STALE);
        assertThat(analysis.describe(page("3", "")).reason()).as("weder Text noch Marken")
                .isEqualTo(PageImages.NO_TEXT);
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
        return new Assets(catalog(), new FileSystemAssetStore(directory), 1000, Clock.fixed(NOW, ZoneOffset.UTC),
                describer, null);
    }

    private SourceCatalog catalog() {
        return new SourceCatalog() {
            @Override
            public List<HarvestSource> all() {
                return List.of(source);
            }

            @Override
            public Optional<HarvestSource> find(String sourceId) {
                return "wiki".equals(sourceId) ? Optional.of(source) : Optional.empty();
            }
        };
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
        Runnable during = () -> { };
        /** Gleichzeitige Aufrufe ({@code ai.parallel}); 1 hält die übrigen Tests der Reihe nach. */
        int parallel = 1;

        @Override
        public int parallel() {
            return parallel;
        }

        @Override
        public String id() {
            return "test-vision-v1";
        }

        @Override
        public String modelVersion() {
            return model;
        }

        final List<String> instructions = new ArrayList<>();
        final List<Integer> sizes = new ArrayList<>();

        @Override
        public String describe(byte[] image, String mediaType, String instruction) {
            synchronized (this) {
                instructions.add(instruction);
            }
            return describe(image, mediaType);
        }

        @Override
        public String describe(byte[] image, String mediaType) {
            synchronized (this) {
                calls++;
                sizes.add(image.length);
            }
            during.run();
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
        boolean unavailable;
        /** Fassung der Lese-Ansicht; ohne Lese-Ansicht ({@code UNSUPPORTED}), solange nie gesetzt. */
        String readingVersion;
        boolean reading;

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
        public ReadingPage readingPage(String pageId) {
            if (!reading && readingVersion == null) {
                return HarvestSource.super.readingPage(pageId);
            }
            reading = true;
            return new ReadingPage(readingVersion, "", "", "", viewImages(pageId));
        }

        @Override
        public byte[] viewImage(ViewImage image, long maxBytes) {
            loads++;
            if (unavailable) {
                throw new com.aresstack.spaceharvester.application.sources.SourceAccessException("wiki",
                        com.aresstack.spaceharvester.application.sources.SourceAccessException.Reason.UNAVAILABLE,
                        "Quelle nicht erreichbar", null);
            }
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
