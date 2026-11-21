package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Asset;
import com.aresstack.spaceharvester.application.assets.AssetKind;
import com.aresstack.spaceharvester.application.assets.Assets;
import com.aresstack.spaceharvester.application.assets.DocumentExtractions;
import com.aresstack.spaceharvester.application.assets.DocumentInventory;
import com.aresstack.spaceharvester.application.assets.DocumentKey;
import com.aresstack.spaceharvester.application.assets.ExtractionKey;
import com.aresstack.spaceharvester.application.assets.TextSection;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionWriter;
import com.aresstack.spaceharvester.application.background.BackgroundGroup;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.enrichment.AcronymProcessor;
import com.aresstack.spaceharvester.application.enrichment.DocumentEnrichments;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentOutput;
import com.aresstack.spaceharvester.application.enrichment.EnrichmentProcessor;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.InMemoryEnrichmentStore;
import com.aresstack.spaceharvester.application.enrichment.OutlineProcessor;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.enrichment.language.TermProcessor;
import com.aresstack.spaceharvester.application.terms.TermPayload;
import com.aresstack.spaceharvester.application.jobs.InMemoryJobStore;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobTask;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.jobs.Jobs;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.sources.PageAttachmentList;
import com.aresstack.spaceharvester.application.sources.PageAttachments;
import com.aresstack.spaceharvester.persistence.files.FileSystemAssetStore;
import com.aresstack.spaceharvester.persistence.files.FileSystemExtractionStore;
import com.aresstack.spaceharvester.testcorpus.Archives;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Analysen je Anhang und enthaltener Datei (ADR 0026, AP9a): je abgelegtem Abschnitt, nur Prozessoren für Dokumente,
 * nur mit dem Text des Dokuments, aktuelle Ergebnisse bleiben, Weggefallenes fällt erst nach dem Lauf weg.
 */
class DocumentEnrichmentsTest {

    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");
    private static final PageKey PAGE = new PageKey("wiki", "101");

    @TempDir
    Path directory;

    private FileSystemAssetStore store;
    private DocumentExtractions extractions;
    private InMemoryEnrichmentStore results;
    private Recording recording;
    private Recording pageOnly;
    private boolean released;
    private Counting reads;
    private final List<Asset> inventory = new ArrayList<>();

    @BeforeEach
    void setUp() {
        store = new FileSystemAssetStore(directory.resolve("assets"));
        reads = new Counting(directory.resolve("extractions"));
        extractions = new DocumentExtractions(new TikaDocumentStructure(), reads, ExtractionLimits.DEFAULTS);
        results = new InMemoryEnrichmentStore();
        recording = new Recording("recording", true);
        pageOnly = new Recording("page-only", false);
        released = true;
    }

    private DocumentEnrichments documents(boolean enabled) {
        return documents(enabled, null);
    }

    private DocumentEnrichments documents(boolean enabled, Jobs jobs) {
        return documents(enabled, jobs, List.of(new OutlineProcessor(), recording, pageOnly));
    }

    private DocumentEnrichments documents(boolean enabled, Jobs jobs, List<EnrichmentProcessor> processors) {
        Enrichments enrichments = new Enrichments(snapshot -> Optional.empty(), processors, results,
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = new DocumentEnrichments(enrichments, jobs, () -> enabled);
        documents.assets(new DocumentInventory() {
            @Override
            public Optional<DocumentExtractions> documentExtractions() {
                return Optional.of(extractions);
            }

            @Override
            public List<Asset> indexedDocuments(String sourceId, Collection<String> pageIds) {
                return inventory.stream().filter(asset -> pageIds.contains(asset.pageId())).toList();
            }
        });
        return documents;
    }

    @Test
    void eachEnclosedFileGetsItsResultsFromItsOwnTextOnly() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil mit Text.", "zwei.txt",
                "Zweiter Teil mit Text."));

        documents(true).indexed(list(), asset, extractions);

        DocumentKey attachment = DocumentKey.attachment(PAGE, "a1");
        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(found).extracting(Enrichment::processorId).containsOnly("outline", "recording");
        assertThat(found).allSatisfy(result -> {
            assertThat(result.document().isEmbedded()).isTrue();
            assertThat(result.document().attachmentId()).contains("a1");
            assertThat(result.section()).isZero();
            assertThat(result.revision()).startsWith("sha256:");
        });
        assertThat(found.stream().map(Enrichment::document).distinct()).hasSize(2);
        assertThat(results.find(attachment)).as("der Behälter hat keinen eigenen Text").isEmpty();
        assertThat(recording.inputs).containsExactlyInAnyOrder("Erster Teil mit Text.", "Zweiter Teil mit Text.");
        assertThat(recording.inputs).noneMatch(input -> input.contains("teile.zip") || input.contains("eins.txt"));
        assertThat(pageOnly.inputs).isEmpty();
        assertThat(results.find(PAGE)).as("die Seite selbst bleibt unberührt").isEmpty();
    }

    @Test
    void currentResultsAreNotComputedAgain() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));
        DocumentEnrichments documents = documents(true);

        documents.indexed(list(), asset, extractions);
        documents.indexed(list(), asset, extractions);

        assertThat(recording.inputs).hasSize(1);
    }

    @Test
    void termsAndAcronymsOfEachDocumentAreComputedOnceAndStayWhileCurrent() {
        AtomicInteger runs = new AtomicInteger();
        TermProcessor terms = new TermProcessor() {
            @Override
            public EnrichmentOutput process(PageText text) {
                runs.incrementAndGet();
                return super.process(text);
            }
        };
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Der RSL (Rückmelde-Schlüssel-Liste) im Teil."));
        DocumentEnrichments documents = documents(true, null, List.of(terms, new AcronymProcessor()));

        documents.indexed(list(), asset, extractions);
        documents.indexed(list(), asset, extractions);

        assertThat(runs).as("ein zweiter Lauf rechnet nichts").hasValue(1);
        List<Enrichment> found = results.findDocuments(PAGE);
        assertThat(found).extracting(Enrichment::processorId).containsExactlyInAnyOrder("terms", "acronyms");
        assertThat(found).allSatisfy(result -> {
            assertThat(result.document().isEmbedded()).isTrue();
            assertThat(result.extraction()).as("der Lauf ist durch").isNotNull();
        });
        assertThat(TermPayload.postings(found.stream().filter(result -> result.processorId().equals("terms"))
                .findFirst().orElseThrow())).anySatisfy(posting -> {
                    assertThat(posting.normalized()).isEqualTo("rsl");
                    assertThat(posting.ofDocument()).isTrue();
                });
    }

    @Test
    void switchedOffNothingNewIsCreatedAndExistingResultsStay() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));
        documents(true).indexed(list(), asset, extractions);
        Asset reread = stored("teile.zip", Map.of("eins.txt", "Erster Teil, geändert."));

        documents(false).indexed(list(), reread, extractions);

        assertThat(recording.inputs).containsExactly("Erster Teil.");
        assertThat(results.findDocuments(PAGE)).hasSize(2);
    }

    @Test
    void withoutReleaseOfThePageNothingIsComputed() {
        released = false;
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));

        documents(true).indexed(list(), asset, extractions);

        assertThat(recording.inputs).isEmpty();
        assertThat(results.findDocuments(PAGE)).extracting(Enrichment::processorId).containsOnly("outline");
    }

    @Test
    void partsThatAreGoneAfterRereadingLoseTheirResultsAndRemovedAttachmentsLoseAll() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil.", "zwei.txt", "Zweiter Teil."));
        DocumentEnrichments documents = documents(true);
        documents.indexed(list(), asset, extractions);
        assertThat(results.findDocuments(PAGE).stream().map(Enrichment::document).distinct()).hasSize(2);

        Asset reread = stored("teile.zip", Map.of("eins.txt", "Erster Teil, neu."));
        documents.indexed(list(), reread, extractions);

        assertThat(results.findDocuments(PAGE).stream().map(Enrichment::document).distinct()).hasSize(1);
        assertThat(results.findDocuments(PAGE)).allSatisfy(result -> assertThat(result.revision())
                .isNotEqualTo(asset.sha256()));

        documents.retained(PAGE, Set.of("a2"));
        assertThat(results.findDocuments(PAGE)).isEmpty();
    }

    @Test
    void asBackgroundJobTheAnalysesRunVisiblyAsOnePartOfTheirSpace() {
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = hosted(true, jobs, new BackgroundJobs(jobs, "app"));
        Asset asset = inStock(stored("teile.zip", Map.of("eins.txt", "Erster Teil mit Text.")));

        documents.indexed(list(), asset, extractions);

        assertThat(jobs.list(null, JobType.ENRICHMENT)).as("kein unsichtbarer Auftrag ohne Auftraggeber").isEmpty();
        assertThat(jobs.list("app", JobType.BACKGROUND)).singleElement().satisfies(part -> {
            assertThat(part.parameter(BackgroundJobs.KIND)).isEqualTo(DocumentEnrichments.BACKGROUND_KIND);
            assertThat(part.parameter(BackgroundJobs.TITLE)).isEqualTo(DocumentEnrichments.BACKGROUND_TITLE);
            assertThat(part.sourceId()).isEqualTo("wiki");
            assertThat(part.partitionKey()).isEqualTo("DEV");
            assertThat(part.state()).isEqualTo(JobState.COMPLETED);
        });
        assertThat(recording.inputs).containsExactly("Erster Teil mit Text.");
    }

    @Test
    void aWaitingBackgroundPartTakesLaterReportsOfItsSpaceAlong() {
        Queue<Runnable> held = new ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = hosted(true, jobs, new BackgroundJobs(jobs, "app"));
        Asset first = inStock(stored("teile.zip", Map.of("eins.txt", "Erster Teil.")));

        documents.indexed(list(), first, extractions);
        documents.indexed(list(), first, extractions);

        assertThat(jobs.list("app", JobType.BACKGROUND)).as("ein Teil je Space, solange er wartet oder läuft")
                .hasSize(1);
        runAll(held);
        assertThat(recording.inputs).containsExactly("Erster Teil.");
    }

    @Test
    void aReportWhileThePartComputesGetsAnotherPassAndNoSecondPart() {
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = hosted(true, jobs, new BackgroundJobs(jobs, "app"));
        Asset first = inStock(stored("a1", "eins.zip", Map.of("eins.txt", "Erster Teil.")));
        Asset second = stored("a2", "zwei.zip", Map.of("zwei.txt", "Zweiter Teil."));
        AtomicBoolean once = new AtomicBoolean();
        // Während der Teil den ersten Anhang rechnet, legt ein Abruf den zweiten ab.
        recording.onInput = () -> {
            if (once.compareAndSet(false, true)) {
                inStock(second);
                documents.indexed(list(), second, extractions);
            }
        };

        documents.indexed(list(), first, extractions);

        assertThat(jobs.list("app", JobType.BACKGROUND)).as("höchstens ein offener Teil je Space").singleElement()
                .extracting(JobStatus::state).isEqualTo(JobState.COMPLETED);
        assertThat(recording.inputs).containsExactly("Erster Teil.", "Zweiter Teil.");
    }

    @Test
    void aPausedPartHoldsNewReportsBackAndComputesThemWhenResumed() {
        // Ein Platz: der Teil wartet und lässt sich anhalten, bevor er läuft.
        Jobs jobs = new Jobs(new InMemoryJobStore(), Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        BackgroundJobs background = new BackgroundJobs(jobs, "app");
        DocumentEnrichments documents = hosted(true, jobs, background);
        Asset asset = inStock(stored("teile.zip", Map.of("eins.txt", "Erster Teil.")));
        documents.indexed(list(), asset, extractions);
        BackgroundGroup group = background.groups().getFirst();

        background.pause(group.groupId());
        documents.indexed(list(), asset, extractions);

        assertThat(background.groups()).as("der angehaltene Teil hält den neuen Anlass zurück").singleElement()
                .satisfies(paused -> assertThat(paused.paused()).isTrue());
        assertThat(recording.inputs).as("angehalten heißt: nichts rechnet").isEmpty();

        jobs.parallelPreparations(2);
        background.resume(group.groupId());

        assertThat(background.group(group.groupId()).orElseThrow().completed()).isTrue();
        assertThat(recording.inputs).containsExactly("Erster Teil.");
    }

    @Test
    void afterARestartAnInterruptedPartResumesWithItsWorkFromTheStock() {
        InMemoryJobStore stored = new InMemoryJobStore();
        Jobs first = new Jobs(stored, Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Asset asset = inStock(stored("teile.zip", Map.of("eins.txt", "Erster Teil.")));
        hosted(true, first, new BackgroundJobs(first, "app")).indexed(list(), asset, extractions);
        // Das Beenden unterbricht den wartenden Teil; die Meldung selbst liegt nirgends mehr.
        first.close();

        Jobs restarted = new Jobs(stored, Runnable::run, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        BackgroundJobs background = new BackgroundJobs(restarted, "app");
        hosted(true, restarted, background);

        assertThat(background.resumeInterrupted()).singleElement().satisfies(part ->
                assertThat(restarted.status(part.id()).state()).isEqualTo(JobState.COMPLETED));
        assertThat(recording.inputs).containsExactly("Erster Teil.");
    }

    @Test
    void switchedOffTheInterruptedPartStaysAfterARestart() {
        InMemoryJobStore stored = new InMemoryJobStore();
        Jobs first = new Jobs(stored, Runnable::run, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Asset asset = inStock(stored("teile.zip", Map.of("eins.txt", "Erster Teil.")));
        hosted(true, first, new BackgroundJobs(first, "app")).indexed(list(), asset, extractions);
        first.close();

        Jobs restarted = new Jobs(stored, Runnable::run, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        BackgroundJobs background = new BackgroundJobs(restarted, "app");
        hosted(false, restarted, background);

        assertThat(background.resumeInterrupted()).isEmpty();
        assertThat(recording.inputs).isEmpty();
    }

    @Test
    void allAttachmentsOfASyncMakeOneGroupThatStartsWhenTheSyncEnds() throws InterruptedException {
        Queue<Runnable> held = new ConcurrentLinkedQueue<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(4, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = hosted(true, jobs, new BackgroundJobs(jobs, "app"));
        // Der Abgleich des Space läuft und legt nacheinander 50 Anhänge ab.
        jobs.submit(JobType.SYNC, "wiki", "DEV", null, JobTask.blocking(context -> { }));
        List<Asset> assets = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            assets.add(inStock(stored("a" + i, "teil" + i + ".zip", Map.of("teil.txt", "Teil " + i + "."))));
        }

        assets.forEach(asset -> documents.indexed(list(), asset, extractions));

        assertThat(jobs.list("app", JobType.BACKGROUND)).as("solange der Abgleich läuft, kein Teil").isEmpty();
        // Das Ende des Abgleichs kommt als Auftragsmeldung auf dem Zustell-Thread und startet den Teil.
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline) && jobs.list("app", JobType.BACKGROUND).stream()
                .noneMatch(part -> part.state() == JobState.COMPLETED)) {
            runAll(held);
            Thread.sleep(20);
        }
        assertThat(jobs.list("app", JobType.BACKGROUND)).as("eine Gruppe für den ganzen Abgleich").singleElement()
                .extracting(JobStatus::state).isEqualTo(JobState.COMPLETED);
        assertThat(recording.inputs).hasSize(50);
    }

    @Test
    void aJobCancelledBeforeItStartsDoesNotHoldUpLaterReports() {
        List<Runnable> held = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        // Belegt den einzigen Platz: der Auftrag der Anhänge wartet.
        jobs.submit(JobType.EXPORT, "wiki", "DEV", null, JobTask.blocking(context -> { }));
        DocumentEnrichments documents = documents(true, jobs);
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));

        documents.indexed(list(), asset, extractions);
        List<JobStatus> queued = jobs.list(null, JobType.ENRICHMENT);
        assertThat(queued).singleElement().extracting(JobStatus::state).isEqualTo(JobState.QUEUED);
        jobs.cancel(queued.getFirst().id());

        documents.indexed(list(), asset, extractions);

        assertThat(jobs.list(null, JobType.ENRICHMENT)).extracting(JobStatus::state)
                .containsExactlyInAnyOrder(JobState.CANCELLED, JobState.QUEUED);
    }

    @Test
    void aQueuedJobStartedAfterSwitchingOffComputesNothing() {
        List<Runnable> held = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        java.util.concurrent.atomic.AtomicBoolean on = new java.util.concurrent.atomic.AtomicBoolean(true);
        Enrichments enrichments = new Enrichments(snapshot -> Optional.empty(),
                List.of(new OutlineProcessor(), recording, pageOnly), results, Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = new DocumentEnrichments(enrichments, jobs, on::get);
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));
        documents.indexed(list(), asset, extractions);

        on.set(false);
        new ArrayList<>(held).forEach(Runnable::run);

        assertThat(recording.inputs).isEmpty();
        assertThat(results.findDocuments(PAGE)).isEmpty();
    }

    @Test
    void anAttachmentRemovedWhileQueuedIsNotComputedLater() {
        List<Runnable> held = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        DocumentEnrichments documents = documents(true, jobs);
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));
        documents.indexed(list(), asset, extractions);

        documents.retained(PAGE, Set.of());
        new ArrayList<>(held).forEach(Runnable::run);

        assertThat(recording.inputs).isEmpty();
        assertThat(results.findDocuments(PAGE)).isEmpty();
    }

    @Test
    void theRunAfterASyncBuildsMissingResultsWithoutAnEventAndASecondRunReadsNoSection() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil.", "zwei.txt", "Zweiter Teil."));
        inventory.add(asset);
        DocumentEnrichments documents = documents(true);

        documents.catchUp("wiki", "DEV", List.of("101"), null);

        assertThat(recording.inputs).containsExactlyInAnyOrder("Erster Teil.", "Zweiter Teil.");
        assertThat(results.findDocuments(PAGE)).hasSize(4);
        reads.sections.set(0);

        documents.catchUp("wiki", "DEV", List.of("101"), null);

        assertThat(recording.inputs).hasSize(2);
        assertThat(reads.sections).as("aktuell, am Manifest erkannt").hasValue(0);
        documents.catchUp("wiki", "DEV", List.of("102"), null);
        assertThat(recording.inputs).as("andere Seiten haben keine Anhänge").hasSize(2);
    }

    @Test
    void aNewExtractionWithTheSameBlocksComputesNothingAndOnlyRecordsItself() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil."));
        inventory.add(asset);
        DocumentEnrichments documents = documents(true);
        documents.catchUp("wiki", "DEV", List.of("101"), null);
        // Wie nach einer neuen Extraktorversion: der Stand nennt eine andere Extraktion.
        for (Enrichment result : results.findDocuments(PAGE)) {
            results.save(result.withBasis(result.revision(), "ältere Extraktion"));
        }

        documents.catchUp("wiki", "DEV", List.of("101"), null);

        assertThat(recording.inputs).as("kein neuer Aufruf").hasSize(1);
        assertThat(results.findDocuments(PAGE)).allSatisfy(result -> assertThat(result.extraction())
                .isNotEqualTo("ältere Extraktion"));
        reads.sections.set(0);
        documents.catchUp("wiki", "DEV", List.of("101"), null);
        assertThat(reads.sections).as("danach wieder am Manifest erkannt").hasValue(0);
    }

    @Test
    void changedBlocksComputeExactlyThatDocument() {
        Asset asset = stored("teile.zip", Map.of("eins.txt", "Erster Teil.", "zwei.txt", "Zweiter Teil."));
        DocumentEnrichments documents = documents(true);
        documents.indexed(list(), asset, extractions);

        Asset reread = stored("teile.zip", Map.of("eins.txt", "Erster Teil, geändert.", "zwei.txt",
                "Zweiter Teil."));
        documents.indexed(list(), reread, extractions);

        assertThat(recording.inputs).containsExactlyInAnyOrder("Erster Teil.", "Zweiter Teil.",
                "Erster Teil, geändert.");
    }

    @Test
    void aRunCancelledInsideADocumentIsCaughtUpLater() {
        reads.sectionChars = 30;
        List<Runnable> held = new ArrayList<>();
        Jobs jobs = new Jobs(new InMemoryJobStore(), held::add, new JobLimits(1, 5, Duration.ofHours(1)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        Asset asset = stored("teile.zip", Map.of("eins.html",
                "<html><body><p>Erster Absatz mit Text.</p><p>Zweiter Absatz mit Text.</p></body></html>"));
        // Abbruch, sobald der erste Abschnitt gerechnet ist: der zweite bleibt offen.
        recording.onInput = () -> jobs.cancel(jobs.list(null, JobType.ENRICHMENT).getFirst().id());
        documents(true, jobs).indexed(list(), asset, extractions);
        new ArrayList<>(held).forEach(Runnable::run);
        assertThat(recording.inputs).hasSize(1);
        recording.onInput = () -> { };

        documents(true).indexed(list(), asset, extractions);

        assertThat(recording.inputs).as("nur der offene Abschnitt kommt dazu").hasSize(2).doesNotHaveDuplicates();
        assertThat(results.findDocuments(PAGE)).filteredOn(result -> result.processorId().equals("recording"))
                .extracting(Enrichment::section).containsExactlyInAnyOrder(0, 1);
        documents(true).indexed(list(), asset, extractions);
        assertThat(recording.inputs).as("danach aktuell").hasSize(2);
    }

    @Test
    void aRereadWithWiderLimitsUnderTheSameKeyComputesTheNewText() {
        String text = "Anfang " + "Lang ".repeat(100) + "Schleusenkammer";
        DocumentExtractions tight = new DocumentExtractions(new TikaDocumentStructure(), reads, limits(60));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("lang.txt", text.getBytes(StandardCharsets.UTF_8));
        byte[] zip = Archives.zip(entries);
        Asset asset = new Asset("wiki", "101", "a1", "1", "lang.zip", Archives.ZIP, AssetKind.OTHER, zip.length,
                Assets.sha256(zip), NOW);
        store.put(asset, zip);
        tight.text(asset, store, NOW);
        DocumentEnrichments documents = documents(true);
        documents.indexed(list(), asset, tight);
        assertThat(recording.inputs).singleElement().satisfies(input -> assertThat(input)
                .doesNotContain("Schleusenkammer"));

        // Gleicher Extraktor, gleiche Version, weitere Grenzen: die Abschnitte werden unter demselben Schlüssel ersetzt.
        extractions.reread(asset, store, NOW.plusSeconds(1));
        documents.indexed(list(), asset, extractions);

        assertThat(recording.inputs).hasSize(2).last().satisfies(input -> assertThat(input)
                .contains("Schleusenkammer"));
    }

    private static ExtractionLimits limits(long maxChars) {
        ExtractionLimits d = ExtractionLimits.DEFAULTS;
        return new ExtractionLimits(d.maxFileBytes(), maxChars, d.unpackRatio(), d.unpackRatioFromBytes(),
                d.textRatio(), d.textRatioFromChars(), d.maxDepth(), d.maxEmbeddedFiles(), d.idleTimeout(),
                d.totalTimeout(), d.tempSpaceReserve(), d.parallel(), d.memoryLimits());
    }

    /** Mit Hintergrundaufträgen; Meldungen ohne Bündelung, die Seiten des Space sind die eine Testseite. */
    private DocumentEnrichments hosted(boolean enabled, Jobs jobs, BackgroundJobs background) {
        DocumentEnrichments documents = documents(enabled, jobs);
        documents.background(background, (sourceId, spaceKey) -> List.of(PAGE.pageId()), Duration.ZERO);
        return documents;
    }

    /** Legt den Anhang in den Bestand, aus dem ein Hintergrundteil seine Arbeit bestimmt. */
    private Asset inStock(Asset asset) {
        inventory.add(asset);
        return asset;
    }

    private static void runAll(Queue<Runnable> held) {
        Runnable next;
        while ((next = held.poll()) != null) {
            next.run();
        }
    }

    private Asset stored(String title, Map<String, String> files) {
        return stored("a1", title, files);
    }

    private Asset stored(String attachmentId, String title, Map<String, String> files) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        files.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .forEach(entry -> entries.put(entry.getKey(), entry.getValue().getBytes(StandardCharsets.UTF_8)));
        byte[] zip = Archives.zip(entries);
        Asset asset = new Asset("wiki", "101", attachmentId, "1", title, Archives.ZIP, AssetKind.OTHER, zip.length,
                Assets.sha256(zip), NOW);
        store.put(asset, zip);
        extractions.text(asset, store, NOW);
        return asset;
    }

    private static PageAttachmentList list() {
        return new PageAttachmentList("wiki", "DEV", new PageAttachments("101", List.of(), List.of(),
                "application/json", "test", List.of(), NOW), NOW);
    }

    /** Merkt sich jede Eingabe; bearbeitet nur freigegebene Seiten, wie ein Prozessor mit KI-Dienst. */
    private final class Recording implements EnrichmentProcessor {
        private final String id;
        private final boolean documents;
        private final List<String> inputs = new ArrayList<>();
        private Runnable onInput = () -> { };

        Recording(String id, boolean documents) {
            this.id = id;
            this.documents = documents;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String version() {
            return "1";
        }

        @Override
        public boolean documents() {
            return documents;
        }

        @Override
        public boolean appliesTo(PageKey key) {
            return released;
        }

        @Override
        public EnrichmentOutput process(PageText text) {
            inputs.add(text.text());
            onInput.run();
            return EnrichmentOutput.of("{}");
        }
    }

    /** Zählt gelesene Abschnitte, des Dokuments und seiner Teile. */
    private static final class Counting extends FileSystemExtractionStore {
        private final AtomicInteger sections = new AtomicInteger();
        private int sectionChars = DEFAULT_SECTION_CHARS;

        Counting(Path root) {
            super(root);
        }

        @Override
        public ExtractionWriter writer(ExtractionKey key) {
            return writer(key, sectionChars);
        }

        @Override
        public Stream<TextSection> sections(ExtractionKey key) {
            return super.sections(key).peek(section -> sections.incrementAndGet());
        }

        @Override
        public Optional<PartSections> partSections(ExtractionKey key) {
            return super.partSections(key).map(parts -> new PartSections() {
                @Override
                public Set<String> keys() {
                    return parts.keys();
                }

                @Override
                public Stream<TextSection> sections(String partKey) {
                    return parts.sections(partKey).peek(section -> sections.incrementAndGet());
                }
            });
        }
    }
}
