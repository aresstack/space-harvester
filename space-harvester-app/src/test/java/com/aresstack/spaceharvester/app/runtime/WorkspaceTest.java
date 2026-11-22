package com.aresstack.spaceharvester.app.runtime;

import com.aresstack.spaceharvester.application.configuration.SourceSyncOverride;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aresstack.enterpriseai.proasteion.emporion.holkas.confluence.FakeConfluence;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.ConfigFileEditor;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.settings.SourceDraft;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.ranking.SemanticSearchUnavailableException;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageAttachment;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sync.PageFreshness;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncMode;
import com.aresstack.spaceharvester.application.terms.SynonymDiscovery;
import com.aresstack.spaceharvester.core.SourceManagement;
import com.aresstack.spaceharvester.source.SourceSettingsConfig;
import com.aresstack.spaceharvester.source.tls.TestCertificates;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Die Anwendungsfälle der App gegen eine Fake-Confluence über HTTPS mit Client-Zertifikat (mTLS, PKCS#12) und eigenem
 * Truststore ({@code spring.ssl.bundle.jks}) und gegen lokale Dateien, ohne Oberfläche: Konfiguration laden, Quellen
 * und Spaces, Live-Metadaten, Abgleich, Bestand, Baum, Seite, Analyse, Suche, Export und Auftragsliste. Token und
 * Zertifikatspasswort kommen aus Umgebungsvariablen, gehen nur an die Quelle und tauchen in keiner Ablage der App auf.
 */
class WorkspaceTest {

    private static final String TOKEN = "pat-geheim-7f3a";
    private static final FakeConfluence CONFLUENCE = new FakeConfluence();
    @TempDir
    static Path tls;
    private static TestCertificates certificates;
    private static HttpsFakeConfluenceServer server;

    @BeforeAll
    static void startServer() throws Exception {
        CONFLUENCE.space("DEV", "Entwicklung");
        CONFLUENCE.page("100", "Start", "DEV", "<p>Start</p>").storage("<h1>Start</h1><p>Willkommen</p>");
        CONFLUENCE.page("102", "Betrieb und Wartung", "DEV", "<p>Inhalt</p>").storage("<p>Der Dienst läuft.</p>");
        CONFLUENCE.child("100", "102");
        CONFLUENCE.attachment("att900", "102", "wartung.txt", "text/plain", "Wartungsfenster sonntags");
        CONFLUENCE.homepage("DEV", "100");
        certificates = new TestCertificates(tls);
        server = new HttpsFakeConfluenceServer(CONFLUENCE, certificates.serverContext(), true);
    }

    private static final String MXFILE = "<mxfile><diagram name=\"Ablauf\"><mxGraphModel><root><mxCell id=\"0\"/>"
            + "<mxCell id=\"1\" parent=\"0\"/><mxCell id=\"2\" value=\"Start\" vertex=\"1\" parent=\"1\">"
            + "<mxGeometry x=\"10\" y=\"10\" width=\"80\" height=\"40\" as=\"geometry\"/></mxCell>"
            + "</root></mxGraphModel></diagram></mxfile>";

    @TempDir
    Path home;

    private AppRuntime runtime;
    /** Zusätzlicher Block unter {@code space-harvester:} (etwa {@code ai:}); leer ohne. */
    private String extraConfig = "";

    @AfterAll
    static void stopServer() {
        server.close();
    }

    @AfterEach
    void close() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void harvestsBrowsesSearchesAndExportsAConfluenceSpace() throws Exception {
        Workspace workspace = start().workspace();

        assertThat(workspace.sources()).extracting(HarvestSource::id).containsExactlyInAnyOrder("wiki", "handbuch");
        assertThat(workspace.spaces("wiki")).extracting(Partition::key).containsExactly("DEV");
        assertThat(workspace.liveMetadata("wiki", "DEV", null, 100).entries()).hasSize(2);

        // Der Kreispfeil (SYNC_CACHE) nähme unbekannte Seiten nur in den Katalog auf; Inhalte lädt FULL.
        JobStatus sync = await(workspace, workspace.startSync("wiki", "DEV", SyncMode.FULL));
        assertThat(sync.state()).isEqualTo(JobState.COMPLETED);
        SpaceSyncJobs.SyncJob job = workspace.syncStatus(sync.id());
        assertThat(job.summary().complete()).isTrue();
        assertThat(job.summary().added()).isEqualTo(2);

        assertThat(workspace.cachedPages("wiki", "DEV", null, 100).pages())
                .extracting(page -> page.header().key().pageId()).containsExactlyInAnyOrder("100", "102");
        assertThat(workspace.tree("wiki", "DEV").tree()).isNotNull();
        DeliveredPage page = workspace.page("wiki", "102", false);
        assertThat(new String(page.snapshot().payload().bytes(), StandardCharsets.UTF_8)).contains("Der Dienst läuft.");
        // Anreicherung ist ein eigener Auftrag nach dem Abgleich (#173) und kann hier noch laufen
        Instant enriched = Instant.now().plus(Duration.ofSeconds(30));
        while (workspace.enrichments(page).isEmpty() && Instant.now().isBefore(enriched)) {
            Thread.sleep(50);
        }
        assertThat(workspace.enrichments(page)).isNotEmpty();

        PageSearchQuery query = PageSearchQuery.of("wiki", List.of("DEV"), "wartung", null, null, null, null, null,
                SearchSort.TITLE);
        assertThat(workspace.search(query, null, 10).hits()).extracting(hit -> hit.page().header().key().pageId())
                .containsExactly("102");
        // Ohne Quelle global über alle Quellen (#114); jeder Treffer nennt seine Quelle.
        PageSearchQuery global = PageSearchQuery.of(null, List.of(), "wartung", null, null, null, null, null,
                SearchSort.TITLE);
        assertThat(workspace.search(global, null, 10).hits()).extracting(hit -> hit.page().header().key().toString())
                .containsExactly(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "102")
                        .toString());
        assertThat(workspace.semanticSearch()).isFalse();
        // Ohne KI-Dienst kein Rückfall auf die Titelsuche (ADR 0011).
        assertThatThrownBy(() -> workspace.relevance(query, "wartung", 10))
                .isInstanceOf(SemanticSearchUnavailableException.class);

        JobStatus export = await(workspace, workspace.startExport("wiki", "DEV", List.of("markdown")));
        assertThat(export.state()).isEqualTo(JobState.COMPLETED);
        ExportArtifact artifact = workspace.exportArtifact(export.id()).orElseThrow();
        assertThat(artifact.complete()).isTrue();
        ExportArtifact.File first = artifact.files().get(0);
        try (InputStream in = workspace.exportFile(export.id(), first.name()).orElseThrow()) {
            assertThat(in.readAllBytes()).hasSize((int) first.bytes());
        }

        // Anreicherung und Bilder sind eigene Folgeaufträge und stehen mit in der Liste; Hintergrundaufträge haben
        // ihren eigenen Reiter und werden unten geprüft.
        assertThat(workspace.jobs()).extracting(JobStatus::type).contains(JobType.ENRICHMENT)
                .filteredOn(type -> !type.followUp() && !type.background())
                .containsExactly(JobType.EXPORT, JobType.SYNC);
        // Erst wenn auch die Folgeaufträge fertig sind, liegt alles auf der Platte, was zu durchsuchen ist.
        for (JobStatus listed : workspace.jobs()) {
            assertThat(await(workspace, listed).state().terminal()).isTrue();
        }
        // Die Synonymerkennung nach der Anreicherung läuft sichtbar als Hintergrundauftrag (#1052).
        assertThat(workspace.jobs()).filteredOn(entry -> entry.type().background())
                .extracting(entry -> entry.parameter(BackgroundJobs.TITLE))
                .contains(SynonymDiscovery.BACKGROUND_TITLE);
        assertThat(CONFLUENCE.requestHeaders()).anySatisfy(headers ->
                assertThat(headers.values()).contains("Bearer " + TOKEN));
        assertNoFileContains(home, TOKEN);
        assertNoFileContains(home, TestCertificates.CLIENT_PASSWORD);
    }

    @Test
    void onlyThePageIsDownloadedWithDepthZeroAndWithChildrenWithoutDepth() throws Exception {
        Workspace workspace = start().workspace();

        JobStatus only = await(workspace, workspace.startExport("wiki", "DEV", "100", 0, List.of("json")));
        JobStatus all = await(workspace, workspace.startExport("wiki", "DEV", "100", null, List.of("json")));

        assertThat(only.state()).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.exportDataset(only.id()).orElseThrow().pages()).isEqualTo(1);
        assertThat(workspace.exportDataset(all.id()).orElseThrow().pages()).isEqualTo(2);
    }

    @Test
    void aFreshSourceDeliversPagesLiveWithoutStock() throws Exception {
        Workspace workspace = start().workspace();

        assertThat(workspace.liveMetadata("wiki", "DEV", null, 100).entries()).hasSize(2);
        // Ohne Bestand lädt das Lesen die Seite nach und legt sie ab (ADR 0010); danach kommt sie aus dem Cache.
        DeliveredPage loaded = workspace.page("wiki", "102", false);
        assertThat(new String(loaded.snapshot().payload().bytes(), StandardCharsets.UTF_8))
                .contains("Der Dienst läuft.");
        DeliveredPage again = workspace.page("wiki", "102", false);
        assertThat(again.live()).isFalse();
        // Erneutes Öffnen prüft die Version live und nimmt den gleichen Stand aus dem Cache (ADR 0010, Punkt 8).
        assertThat(again.refresh()).isEqualTo(PageFreshness.Outcome.CURRENT);
    }

    /** „Stand › Zuerst lesen“ (ADR 0032): die Leseempfehlung kommt über den echten Harvester, mit und ohne Thema. */
    @Test
    void theReadingListOfASelectionComesFromTheCore() throws Exception {
        Workspace workspace = start().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
        var selection = workspace.resolveSelection(com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("handbuch", space)));

        var all = workspace.readingList(selection, null, () -> false);
        var topic = workspace.readingList(selection, "Gibtsnichtbegriff", () -> false);

        assertThat(all.topic()).isNull();
        assertThat(all.changesSince()).isNotNull();
        assertThat(topic.topic()).isEqualTo("Gibtsnichtbegriff");
        assertThat(topic.pages()).as("mit Thema nur Seiten, die es erwähnen").isEmpty();
    }

    @Test
    void deletingTheAnalysesOfASelectionKeepsThePagesAndOnlyReportsRealDeletions() throws Exception {
        Workspace workspace = start().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
        java.util.concurrent.atomic.AtomicInteger heard = new java.util.concurrent.atomic.AtomicInteger();
        try (var ignored = workspace.subscribeStatementAnalyses(heard::incrementAndGet)) {
            // Ohne Analysen nichts zu löschen, für einen Space wie für die ganze Quelle; der Beobachter schweigt.
            assertThat(workspace.deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection
                    .of(com.aresstack.spaceharvester.application.holdings.Selection.Root.space("handbuch", space))))
                    .isZero();
            assertThat(workspace.deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection
                    .of(com.aresstack.spaceharvester.application.holdings.Selection.Root.source("handbuch"))))
                    .isZero();
            // „Alles löschen“ nimmt die KI-Urteile derselben Seiten mit; ohne Analysen bleibt es bei null.
            assertThat(workspace.deleteStatementAnalyses(com.aresstack.spaceharvester.application.holdings.Selection
                    .of(com.aresstack.spaceharvester.application.holdings.Selection.Root.source("handbuch")), true))
                    .isZero();
            assertThat(heard).hasValue(0);
        }
        assertThat(workspace.cachedPages("handbuch", space, null, 100).pages()).as("die Seiten bleiben").hasSize(2);

        // Eine Analyse zu einer Seite, die der Bestand nicht mehr kennt: Die ganze Quelle nimmt sie mit (sie zählt in
        // der gemeldeten Zahl des Core), der Space nicht; eine bekannte Seite gehört zu beiden.
        var spaceSelection = com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("handbuch", space));
        var sourceSelection = com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.source("handbuch"));
        var gone = new com.aresstack.spaceharvester.application.snapshot.PageKey("handbuch", "gibt-es-nicht-mehr");
        var known = workspace.cachedPages("handbuch", space, null, 100).pages().get(0).header().key();
        var bySpace = Workspace.analysesOf(spaceSelection, workspace.resolveSelection(spaceSelection));
        var bySource = Workspace.analysesOf(sourceSelection, workspace.resolveSelection(sourceSelection));
        assertThat(bySource.test(gone)).isTrue();
        assertThat(bySpace.test(gone)).isFalse();
        assertThat(bySpace.test(known)).isTrue();
        assertThat(bySource.test(known)).isTrue();
        assertThat(bySource.test(new com.aresstack.spaceharvester.application.snapshot.PageKey("wiki", "100")))
                .isFalse();
    }

    @Test
    void readsLocalFilesWithoutNetwork() throws Exception {
        Workspace workspace = start().workspace();

        String space = workspace.spaces("handbuch").get(0).key();
        assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.cachedPages("handbuch", space, null, 100).pages()).hasSize(2);
    }

    /**
     * Aufträge des Aufrufers der App (hier der Synonymlauf; ebenso Zusammenfassen und Aussagenanalyse) stehen wie alle
     * anderen im Reiter Aufträge und lassen sich dort abbrechen (Angelo: Aufträge sind global sichtbar).
     */
    @Test
    void jobsOfTheCallerAppearInTheListAndCanBeCancelled() throws Exception {
        Workspace workspace = start().workspace();
        JobStatus started = workspace.startSynonymDiscovery(
                new com.aresstack.spaceharvester.application.terms.SynonymScope("handbuch", null), 10);

        assertThat(workspace.jobs()).extracting(JobStatus::id).contains(started.id());
        assertThat(workspace.job(started.id()).owner()).isNotEqualTo(com.aresstack.spaceharvester.core.Harvester.OWNER);
        assertThat(workspace.cancelJob(started.id()).id()).isEqualTo(started.id());
        // Nach dem Abbruch bleibt er sichtbar, bis man ihn löscht.
        assertThat(workspace.jobs()).extracting(JobStatus::id).contains(started.id());
        // Auch ein quellenübergreifender Lauf erscheint.
        JobStatus global = workspace.startSynonymDiscovery(
                com.aresstack.spaceharvester.application.terms.SynonymScope.ALL, 10);
        assertThat(workspace.jobs()).extracting(JobStatus::id).contains(global.id());
    }

    /**
     * Ein laufender Auftrag des Aufrufers kommt per Abo in der Auftragsliste an, endet dort, und die Knöpfe „Alle
     * anhalten“ und „Alle abbrechen“ nehmen ihn mit (AT nach #846).
     */
    @Test
    void aRunningJobOfTheCallerArrivesByTheSubscriptionAndTheGlobalButtonsReachIt() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        // Den Eigentümer der App liefert ein Synonymlauf, der dem Aufrufer gehört.
        JobStatus synonyms = workspace.startSynonymDiscovery(
                new com.aresstack.spaceharvester.application.terms.SynonymScope("handbuch", null), 10);
        String owner = workspace.job(synonyms.id()).owner();
        assertThat(owner).isNotEqualTo(com.aresstack.spaceharvester.core.Harvester.OWNER);

        java.util.concurrent.CountDownLatch running = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.BlockingQueue<JobStatus> changes = new java.util.concurrent.LinkedBlockingQueue<>();
        try (com.aresstack.spaceharvester.application.observe.Subscription subscription = workspace.subscribeJobs(
                new com.aresstack.spaceharvester.application.jobs.JobListener() {
                    @Override
                    public void changed(JobStatus status) {
                        changes.add(status);
                    }
                })) {
            JobStatus job = started.harvester().jobs().submit(JobType.UPDATE_SUMMARY, "handbuch", space, owner,
                    com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
                        running.countDown();
                        long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                        while (!context.cancelRequested() && System.nanoTime() < until) {
                            Thread.sleep(20);
                        }
                        if (context.cancelRequested()) {
                            throw new com.aresstack.spaceharvester.application.jobs.JobCancelledException("summary");
                        }
                    }));
            assertThat(running.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("läuft").isTrue();
            JobStatus seen = workspace.job(job.id());
            assertThat(AllJobs.toPause(workspace.jobs())).as("„Alle anhalten“ nimmt ihn mit")
                    .extracting(JobStatus::id).contains(job.id());
            assertThat(AllJobs.toCancel(workspace.jobs())).extracting(JobStatus::id).contains(job.id());
            assertThat(seen.state()).isEqualTo(JobState.RUNNING);

            assertThat(workspace.cancelAllJobs().changed()).as("„Alle abbrechen“ erreicht ihn").isPositive();

            JobStatus ended = null;
            boolean arrived = false;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (ended == null && System.nanoTime() < until) {
                JobStatus next = changes.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (next != null && next.id().equals(job.id())) {
                    arrived = true;
                    if (next.state().terminal()) {
                        ended = next;
                    }
                }
            }
            assertThat(arrived).as("per Abo gemeldet").isTrue();
            assertThat(ended).as("Ende per Abo gemeldet").isNotNull();
            assertThat(ended.state()).isEqualTo(JobState.CANCELLED);
        }
    }

    @Test
    void jobChangesArriveWithoutAskingAgain() throws Exception {
        Workspace workspace = start().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        java.util.concurrent.BlockingQueue<JobStatus> changes = new java.util.concurrent.LinkedBlockingQueue<>();
        java.util.List<com.aresstack.spaceharvester.application.jobs.JobEvent> events =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        try (com.aresstack.spaceharvester.application.observe.Subscription subscription = workspace.subscribeJobs(
                new com.aresstack.spaceharvester.application.jobs.JobListener() {
                    @Override
                    public void changed(JobStatus status) {
                        changes.add(status);
                    }

                    @Override
                    public void event(com.aresstack.spaceharvester.application.jobs.JobEvent event) {
                        events.add(event);
                    }
                })) {
            JobStatus started = workspace.startSync("handbuch", space);

            JobStatus finished = null;
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
            while (finished == null && System.nanoTime() < until) {
                JobStatus next = changes.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (next != null && next.id().equals(started.id()) && next.state() == JobState.COMPLETED) {
                    finished = next;
                }
            }
            assertThat(finished).as("Ende des Abgleichs gemeldet").isNotNull();
            assertThat(finished.progress().processed()).isPositive();
            assertThat(events).extracting(com.aresstack.spaceharvester.application.jobs.JobEvent::jobId)
                    .contains(started.id());
        }
    }

    /**
     * Die Aufträge „Zusammenfassen“ gehören dem Aufrufer der App, nicht dem Core-Eigentümer der Abgleiche. Über die
     * allgemeinen Wege des Workspace (#846) findet die Leseansicht sie, hört ihr Ende und bricht sie ab (Fehler bis
     * #843: BusyOverlay blieb stehen).
     */
    @Test
    void theSummaryJobIsFoundHeardAndCancelledThroughTheWorkspace() throws Exception {
        java.util.concurrent.CountDownLatch asked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        com.sun.net.httpserver.HttpServer model = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        model.createContext("/", exchange -> {
            asked.countDown();
            try {
                release.await(20, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(500, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        model.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        model.start();
        try {
            extraConfig = """
                      ai:
                        base-url: http://127.0.0.1:%d/v1
                        chat-model: test-chat
                        external-processing-sources:
                          - handbuch
                    """.formatted(model.getAddress().getPort());
            Workspace workspace = start().workspace();
            String space = workspace.spaces("handbuch").get(0).key();
            assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
            // Die Marke setzt der Core nach der Meldung über das Ende der Runde; erst dann beginnt das nächste Update.
            awaitUpdate(workspace, candidate -> candidate.initial() && !candidate.open());
            Files.writeString(home.resolve("handbuch").resolve("betrieb.md"),
                    "# Betrieb\n\nNeustart mit systemctl restart, danach Protokoll prüfen.\n");
            assertThat(await(workspace, workspace.startSync("handbuch", space)).state()).isEqualTo(JobState.COMPLETED);
            com.aresstack.spaceharvester.application.changelog.ChangeUpdate update = awaitUpdate(workspace,
                    candidate -> !candidate.initial() && !candidate.open() && candidate.pages() > 0);
            assertThat(workspace.changeSummariesAvailable()).isTrue();

            java.util.concurrent.BlockingQueue<JobStatus> changes = new java.util.concurrent.LinkedBlockingQueue<>();
            try (com.aresstack.spaceharvester.application.observe.Subscription subscription =
                         workspace.subscribeJobs(new com.aresstack.spaceharvester.application.jobs.JobListener() {
                             @Override
                             public void changed(JobStatus status) {
                                 changes.add(status);
                             }
                         })) {
                JobStatus started = workspace.summarizeUpdate("handbuch", update.id(), null);
                assertThat(workspace.job(started.id()).id()).as("über den allgemeinen Weg gefunden")
                        .isEqualTo(started.id());
                assertThat(asked.await(20, java.util.concurrent.TimeUnit.SECONDS)).as("Modell gefragt").isTrue();

                assertThat(workspace.cancelJob(started.id()).cancelRequested()).isTrue();
                release.countDown();

                JobStatus ended = null;
                long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
                while (ended == null && System.nanoTime() < until) {
                    JobStatus next = changes.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                    if (next != null && next.id().equals(started.id()) && next.state().terminal()) {
                        ended = next;
                    }
                }
                assertThat(ended).as("Ende über subscribeJobs gemeldet").isNotNull();
                // Der Abbruch wirkt nach der laufenden Modellanfrage; scheitert sie danach (hier HTTP 500), zählt der
                // Core das als Abbruch, nicht als Fehler.
                assertThat(ended.state()).isEqualTo(JobState.CANCELLED);
                assertThat(workspace.changeSummary("handbuch", update.id(), null)).isEmpty();
            }
        } finally {
            release.countDown();
            model.stop(0);
        }
    }

    @Test
    void aFormatButtonFirstLoadsWithoutHoldingsAndThenWritesTheFileAsItsOwnJob() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        Path out = home.resolve("ausgabe");
        com.aresstack.spaceharvester.application.holdings.Selection space =
                com.aresstack.spaceharvester.application.holdings.Selection.of(
                        com.aresstack.spaceharvester.application.holdings.Selection.Root.space("wiki", "DEV"));

        List<JobStatus> first = workspace.exportFiles(space, List.of("json"), "markdown", out);

        assertThat(first).extracting(JobStatus::type).containsExactly(JobType.EXPORT, JobType.CONVERSION);
        assertThat(first.get(0).parameter(com.aresstack.spaceharvester.application.export.Exports.HOLDINGS))
                .as("ohne Bestand lädt der erste Auftrag von der Quelle").isNull();
        JobStatus file = await(workspace, first.get(1));
        assertThat(file.state()).as(String.valueOf(file)).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.producedFile(file.id())).hasValueSatisfying(produced ->
                assertThat(produced.path().getParent()).isEqualTo(out));

        // Jetzt liegt der Space im Bestand: die Daten kommen aus ihm.
        List<JobStatus> second = workspace.exportFiles(space, List.of("json"), "markdown", out);
        assertThat(second.get(0).parameter(com.aresstack.spaceharvester.application.export.Exports.HOLDINGS))
                .isEqualTo("true");
        assertThat(await(workspace, second.get(1)).state()).isEqualTo(JobState.COMPLETED);

        // Wer exportieren, aber nicht abgleichen darf, sieht und löscht seine Datei-Aufträge.
        Workspace exportOnly = new Workspace(started.harvester(),
                new com.aresstack.spaceharvester.application.access.AccessGuard((context, scope, kind) ->
                        kind == com.aresstack.spaceharvester.application.access.AccessKind.SYNC
                                ? com.aresstack.spaceharvester.application.access.AccessDecision.DENY
                                : com.aresstack.spaceharvester.application.access.AccessDecision.ALLOW),
                new ActivityLog(home.resolve("nur-export.txt")));
        assertThat(exportOnly.job(file.id()).type()).isEqualTo(JobType.CONVERSION);
        assertThat(exportOnly.jobs()).extracting(JobStatus::id).contains(file.id());
    }

    @Test
    void pagesStoredWithoutTheirImagesAreExportedWithTheImagesFromTheSource() throws Exception {
        Workspace workspace = start().workspace();
        // Ein Abgleich speichert die Seiten, beschafft aber keine Bilder und Anhänge.
        assertThat(await(workspace, workspace.startSync("wiki", "DEV", SyncMode.FULL)).state())
                .isEqualTo(JobState.COMPLETED);

        List<JobStatus> started = workspace.exportFiles(com.aresstack.spaceharvester.application.holdings.Selection.of(
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("wiki", "DEV")),
                List.of("json"), "markdown", home.resolve("ausgabe"));

        assertThat(started.get(0).parameter(com.aresstack.spaceharvester.application.export.Exports.HOLDINGS))
                .as("nicht aus dem Bestand: dort fehlen die Bilder").isNull();
        JobStatus file = await(workspace, started.get(1));
        assertThat(file.state()).as(String.valueOf(file)).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.jobs()).filteredOn(job -> started.get(0).id().equals(
                        job.parameter(com.aresstack.spaceharvester.application.export.ExportAssetCollection.EXPORT_ID))
                        && job.type() == JobType.ASSETS)
                .as("die Bilder des Exports sind vor der Datei fertig").isNotEmpty()
                .allSatisfy(job -> assertThat(job.finishedAt()).isBeforeOrEqualTo(file.startedAt()));
    }

    @Test
    void theDeletionOfAJobOfAnInvisibleSourceIsNotAnnounced() throws Exception {
        AppRuntime started = start();
        Workspace full = started.workspace();
        Workspace onlyWiki = new Workspace(started.harvester(), new com.aresstack.spaceharvester.application.access
                .AccessGuard(new LocalOperatorPolicy(com.aresstack.spaceharvester.application.access.AccessContext
                .technicalClient(com.aresstack.spaceharvester.core.Harvester.OWNER), java.util.Set.of("wiki"))),
                new ActivityLog(home.resolve("nur-wiki.txt")));
        String hidden = await(full, full.startSync("handbuch", full.spaces("handbuch").get(0).key())).id();
        String shown = await(full, full.startSync("wiki", "DEV")).id();
        java.util.concurrent.BlockingQueue<String> removed = new java.util.concurrent.LinkedBlockingQueue<>();
        try (com.aresstack.spaceharvester.application.observe.Subscription subscription = onlyWiki.subscribeJobs(
                new com.aresstack.spaceharvester.application.jobs.JobListener() {
                    @Override
                    public void changed(JobStatus status) {
                    }

                    @Override
                    public void removed(JobStatus last) {
                        removed.add(last.id());
                    }
                })) {
            // Zuerst die unsichtbare: ihre Zustellung käme vor der sichtbaren.
            full.deleteJob(hidden);
            full.deleteJob(shown);

            List<String> seen = new ArrayList<>();
            long until = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            while (!seen.contains(shown) && System.nanoTime() < until) {
                String next = removed.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (next != null) {
                    seen.add(next);
                }
            }
            assertThat(seen).as("Löschung des sichtbaren Auftrags gemeldet").contains(shown);
            assertThat(seen).as("Löschung des unsichtbaren Auftrags").doesNotContain(hidden);
        }
    }

    @Test
    void theHoldingsOfASpaceReportTheirChangeAndCountTheLoadedPages() throws Exception {
        Workspace workspace = start().workspace();
        java.util.concurrent.BlockingQueue<String> changes = new java.util.concurrent.LinkedBlockingQueue<>();
        try (com.aresstack.spaceharvester.application.observe.Subscription subscription = workspace.subscribeHoldings(
                "wiki", "DEV", new com.aresstack.spaceharvester.application.holdings.HoldingsListener() {
                    @Override
                    public void pagesChanged(String sourceId, String spaceKey, java.util.Set<String> pageIds) {
                        changes.add("pages:" + spaceKey);
                    }

                    @Override
                    public void spaceChanged(String sourceId, String spaceKey) {
                        changes.add("space:" + spaceKey);
                    }
                })) {
            assertThat(workspace.holdings("wiki", "DEV").space().inventoried()).isFalse();

            assertThat(await(workspace, workspace.startSync("wiki", "DEV")).state()).isEqualTo(JobState.COMPLETED);

            assertThat(changes.poll(10, java.util.concurrent.TimeUnit.SECONDS)).as("Meldung ohne Abfrage")
                    .isNotNull();
            com.aresstack.spaceharvester.application.holdings.SpaceHoldings holdings = workspace.holdings("wiki",
                    "DEV");
            assertThat(holdings.space().inventoried()).isTrue();
            assertThat(holdings.space().counts().pages()).isPositive().isEqualTo(holdings.pages().size());
            assertThat(workspace.holdingSpaces("wiki")).extracting(
                    com.aresstack.spaceharvester.application.holdings.SpaceSummary::spaceKey).contains("DEV");
        }
    }

    @Test
    void theActivityListSurvivesARestart() throws Exception {
        Workspace first = start().workspace();
        String id = await(first, first.startSync("wiki", "DEV")).id();
        runtime.close();
        runtime = null;

        // Folge- und Hintergrundaufträge (etwa die Abgleiche beim Start) zählen nicht.
        assertThat(start().workspace().jobs()).filteredOn(job -> !job.type().followUp() && !job.type().background())
                .extracting(JobStatus::id).containsExactly(id);
    }

    @Test
    void anExportTheActivityListLostStaysInTheList() throws Exception {
        Workspace first = start().workspace();
        await(first, first.startSync("wiki", "DEV"));
        String export = await(first, first.startExport("wiki", "DEV", List.of("markdown"))).id();
        runtime.close();
        runtime = null;
        // Die gemerkte Liste hat den Export verloren (#678). Bilder und Anhänge stehen als Unterzeilen unter ihm, auch
        // die Export-Knöpfe hängen an ihm: er bleibt in der Liste, solange die Auftragsablage ihn kennt.
        try (java.util.stream.Stream<Path> files = Files.walk(home)) {
            Path log = files.filter(file -> file.getFileName().toString().equals("activities.txt")).findFirst()
                    .orElseThrow();
            Files.writeString(log, "");
        }

        assertThat(start().workspace().jobs()).filteredOn(job -> job.type() == JobType.EXPORT)
                .extracting(JobStatus::id).containsExactly(export);
    }

    @Test
    void theWizardTestsANewSourceBeforeSavingWhileTheAppKeepsRunning() throws Exception {
        Workspace running = start().workspace();
        Path config = home.resolve("space-harvester-app.yml");
        String before = Files.readString(config);
        AppSettingsLoader loader = new AppSettingsLoader(new Placeholders(Map.of(
                "WIKI_TOKEN", TOKEN, "CLIENT_PASSWORD", TestCertificates.CLIENT_PASSWORD)::get), home);
        SourceDraft source = new SourceDraft("neu", SourceDraft.CONFLUENCE, "Neues Wiki", server.baseUrl().toString(),
                null, null, SourceSettingsConfig.AuthType.PAT, null, "NEU_TOKEN",
                SourceSettingsConfig.CertificateStoreType.PKCS12, certificates.client.toString(), "CLIENT_PASSWORD",
                TestCertificates.CLIENT_ALIAS, null, "test");
        ConfigFileEditor.Draft draft = new ConfigFileEditor(loader).draft(config, source);
        assertThat(draft.text()).contains("token: ${NEU_TOKEN}").doesNotContain(TOKEN);

        Path scratch = Files.createTempDirectory(home, "probe");
        assertThatThrownBy(() -> AppRuntime.probe(draft.settings(), "neu", new AppRuntime.SessionSecrets(), "test",
                scratch)).isInstanceOf(IllegalStateException.class).hasMessageContaining("NEU_TOKEN");

        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        secrets.put("neu", AppRuntime.SessionSecrets.login(SourceSettingsConfig.AuthType.PAT, null, "NEU_TOKEN"),
                TOKEN.toCharArray());
        Path second = Files.createTempDirectory(home, "probe");
        SourceManagement.ConnectionCheck check = AppRuntime.probe(draft.settings(), "neu", secrets, "test", second);
        assertThat(check.successful()).as(check.message()).isTrue();
        assertThat(check.partitions()).isEqualTo(1);
        assertThat(check.message()).isEqualTo("Verbunden, 1 Space sichtbar");
        assertThat(second).doesNotExist();
        assertThat(Files.readString(config)).isEqualTo(before);
        assertThat(running.spaces("wiki")).extracting(Partition::key).containsExactly("DEV");
    }

    @Test
    void removingASourceDropsItsCachedPagesInTheCore() throws Exception {
        Workspace workspace = start().workspace();
        assertThat(await(workspace, workspace.startSync("wiki", "DEV")).state()).isEqualTo(JobState.COMPLETED);
        assertThat(workspace.cachedPages("wiki", "DEV", null, 100).pages()).hasSize(2);

        assertThat(workspace.removeSource("wiki")).isTrue();

        assertThat(workspace.sources()).extracting(HarvestSource::id).containsExactly("handbuch");
        assertThat(workspace.removeSource("wiki")).isFalse();
    }

    @Test
    void syncRulesAndAiReleaseTakeEffectWithoutARestartButNewSourcesRestart() throws Exception {
        AppSettings settings = settings(Map.of("WIKI_TOKEN", TOKEN, "CLIENT_PASSWORD",
                TestCertificates.CLIENT_PASSWORD));
        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        runtime = AppRuntime.start(settings, secrets, "test");
        long before = runtime.configurationVersion();

        // Regel je Quelle und KI-Freigabe: neue Version im laufenden Harvester (ADR 0015).
        AppSettings.AiSettings ai = settings.ai();
        AppSettings live = new AppSettings(settings.file(), settings.sources(), settings.proxy(),
                settings.asyncTransport(), settings.trustStores(), settings.storage(), settings.jobLimits(),
                new AppSettings.AiSettings(ai.baseUrl(), ai.chatModel(), ai.embeddingModel(), ai.embeddingDimensions(),
                        ai.timeout(), List.of("handbuch"), ai.rerankModel(), ai.chunkTokens(),
                        ai.chunkOverlapSentences()),
                settings.outputDirectory(), settings.problems(), settings.disabledSources(), settings.tls(),
                settings.cache(), settings.setupCompleted(), Map.of("handbuch", new SourceSyncOverride(null, null,
                null, null, null, null, null, null, java.time.Duration.ofDays(3))), settings.sync());
        assertThat(AppRuntime.liveChange(settings, live)).isTrue();
        AppSettings keePass = new AppSettings(live.file(), live.sources(), live.proxy(), live.asyncTransport(),
                live.trustStores(), live.storage(), live.jobLimits(), live.ai(), live.outputDirectory(),
                live.problems(), live.disabledSources(), live.tls(), live.cache(), live.setupCompleted(),
                live.syncRules(), live.sync(), new com.aresstack.spaceharvester.app.settings.AiAccess(
                com.aresstack.spaceharvester.app.settings.AiAccess.Mode.KEEPASS, "Enterprise AI API"));
        assertThat(AppRuntime.liveChange(keePass, keePass)).as("ein gespeicherter Bezug des API-Keys").isTrue();
        assertThat(runtime.reconfigure(live, secrets, "test")).isEqualTo(AppRuntime.Reconfiguration.APPLIED);
        assertThat(runtime.configurationVersion()).isGreaterThan(before);
        assertThat(runtime.settings()).isSameAs(live);
        assertThat(runtime.lastRejection()).isEmpty();

        // Lehnt der Core ab, bleibt die bisherige Version aktiv; kein Neustart, der genauso scheiterte.
        long accepted = runtime.configurationVersion();
        AppSettings rejected = new AppSettings(live.file(), live.sources(), live.proxy(), live.asyncTransport(),
                live.trustStores(), live.storage(), live.jobLimits(), live.ai(), live.outputDirectory(),
                live.problems(), live.disabledSources(), live.tls(), live.cache(), live.setupCompleted(),
                Map.of("handbuch", new SourceSyncOverride(null, null, null, null, null, null, null, null,
                        java.time.Duration.ofDays(-1))), live.sync());
        assertThat(runtime.reconfigure(rejected, secrets, "test")).isEqualTo(AppRuntime.Reconfiguration.REJECTED);
        assertThat(runtime.configurationVersion()).isEqualTo(accepted);
        assertThat(runtime.settings()).isSameAs(live);
        assertThat(runtime.lastRejection()).singleElement().asString().contains("handbuch");

        // Eine abgeschaltete Quelle, andere Zerlegung oder ein neues Token der Sitzung brauchen einen Neustart.
        AppSettings disabled = new AppSettings(live.file(), live.sources(), live.proxy(), live.asyncTransport(),
                live.trustStores(), live.storage(), live.jobLimits(), live.ai(), live.outputDirectory(),
                live.problems(), java.util.Set.of("handbuch"), live.tls(), live.cache(), live.setupCompleted(),
                live.syncRules(), live.sync());
        assertThat(runtime.reconfigure(disabled, secrets, "test")).isEqualTo(AppRuntime.Reconfiguration.RESTART);
        secrets.put("wiki", "pat", "neues-token".toCharArray());
        assertThat(runtime.reconfigure(live, secrets, "test")).isEqualTo(AppRuntime.Reconfiguration.RESTART);
    }

    @Test
    void aDisabledSourceStaysConfiguredButTheCoreDoesNotKnowIt() throws Exception {
        AppSettings settings = settings(Map.of("WIKI_TOKEN", TOKEN, "CLIENT_PASSWORD",
                TestCertificates.CLIENT_PASSWORD));
        runtime = AppRuntime.start(new AppSettings(settings.file(), settings.sources(), settings.proxy(),
                settings.asyncTransport(), settings.trustStores(), settings.storage(), settings.jobLimits(),
                settings.ai(), settings.outputDirectory(), settings.problems(), java.util.Set.of("handbuch"),
                settings.tls(), settings.cache(), settings.setupCompleted(), settings.syncRules(), settings.sync()),
                new AppRuntime.SessionSecrets(), "test");

        assertThat(runtime.workspace().sources()).extracting(HarvestSource::id).containsExactly("wiki");
        assertThat(runtime.workspace().sourceEnabled("handbuch")).isFalse();
        assertThat(runtime.workspace().sourceEnabled("wiki")).isTrue();
    }

    @Test
    void aBookmarkResolvesToItsPageThroughTheCore() throws Exception {
        Workspace workspace = start().workspace();

        var resolution = workspace.resolveAddress("space-harvester://wiki/page/102");

        assertThat(resolution.matches()).singleElement().satisfies(match -> {
            assertThat(match.sourceId()).isEqualTo("wiki");
            assertThat(match.pageId()).isEqualTo("102");
            assertThat(match.bookmark().value()).startsWith("space-harvester://wiki/page/102");
        });
    }

    @Test
    void theConnectionDiagnosisComesFromTheCoreStageByStage() throws Exception {
        Workspace workspace = start().workspace();

        var diagnosis = workspace.diagnose("wiki").orElseThrow();

        assertThat(diagnosis.steps()).extracting(step -> step.stage())
                .contains(com.aresstack.spaceharvester.application.sources.ConnectionStep.Stage.TLS,
                        com.aresstack.spaceharvester.application.sources.ConnectionStep.Stage.API);
        assertThat(diagnosis.successful()).as(diagnosis.summary()).isTrue();
        assertThat(diagnosis.toString()).doesNotContain(TOKEN);
        assertThat(workspace.diagnose("gibt-es-nicht")).isEmpty();
    }

    @Test
    void theDrawioPreviewOfTheReadingViewIsShownAsTheGraphicOfTheCore() throws Exception {
        // Eigenes Confluence, damit die übrigen Tests ihren Space unverändert sehen: eine Seite mit draw.io-Diagramm
        // und der Vorschau, die das Plugin daneben ablegt.
        FakeConfluence diagrams = new FakeConfluence();
        diagrams.space("DEV", "Entwicklung");
        String html = "<p><img src=\"/wiki/download/attachments/300/ablauf.png?version=1\" alt=\"Ablauf\"></p>";
        diagrams.page("300", "Ablauf", "DEV", html).exportView(html);
        diagrams.attachment("att301", "300", "ablauf.drawio", "application/vnd.jgraph.mxfile", MXFILE);
        diagrams.attachment("att302", "300", "ablauf.png", "image/png", "Vorschau");
        diagrams.homepage("DEV", "300");
        HttpsFakeConfluenceServer shared = server;
        try (HttpsFakeConfluenceServer own = new HttpsFakeConfluenceServer(diagrams, certificates.serverContext(),
                true)) {
            server = own;
            drawsTheDrawioPreview();
        } finally {
            server = shared;
        }
    }

    private void drawsTheDrawioPreview() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();

        var view = workspace.readingView("wiki", "300", false);
        assertThat(view.images()).as(view.toString()).hasSize(1);
        HarvestSource.ViewImage preview = view.images().get(0);

        // Wie Mermaid: die Grafik des Core statt der Vorschau der Quelle.
        byte[] shown = com.aresstack.spaceharvester.app.ui.content.PageDetailPanel.image(started, view, preview);
        assertThat(shown).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        assertThat(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(shown)).getWidth()).isGreaterThan(100);
        assertThat(workspace.viewDiagram(view, preview)).isPresent();
    }

    @Test
    void attachmentsAreListedStoredAndDeliveredByteForByte() throws Exception {
        Workspace workspace = start().workspace();

        assertThat(workspace.attachments("wiki", "102").attachments().attachments())
                .extracting(PageAttachment::attachmentId).containsExactly("att900");
        assertThat(workspace.assets("wiki", "102").entries()).singleElement()
                .satisfies(entry -> assertThat(entry.stored()).isNull());

        AssetHarvest harvest = workspace.harvestAssets("wiki", "102");
        assertThat(harvest.complete()).as(harvest.toString()).isTrue();
        assertThat(harvest.count(AssetHarvest.Status.STORED)).isEqualTo(1);
        assertThat(workspace.assets("wiki", "102").entries()).singleElement()
                .satisfies(entry -> assertThat(entry.stored()).isNotNull());
        assertThat(new String(workspace.assetContent("wiki", "102", "att900").content(), StandardCharsets.UTF_8))
                .isEqualTo("Wartungsfenster sonntags");
        // Als Strom aus der Ablage (#571), byte-genau wie assetContent.
        try (com.aresstack.spaceharvester.application.assets.Assets.AssetStream stream =
                     workspace.assetStream("wiki", "102", "att900")) {
            assertThat(new String(stream.content().readAllBytes(), StandardCharsets.UTF_8))
                    .isEqualTo("Wartungsfenster sonntags");
        }
        assertNoFileContains(home, TOKEN);
    }

    @Test
    void pagesOpenByAddressAndAnalysesCanBeRecomputed() throws Exception {
        Workspace workspace = start().workspace();
        assertThat(await(workspace, workspace.startSync("wiki", "DEV")).state()).isEqualTo(JobState.COMPLETED);
        String address = workspace.page("wiki", "102", false).header().metadata().url().toString();

        assertThat(workspace.pageByUrl("wiki", address, false).header().key().pageId()).isEqualTo("102");
        assertThatThrownBy(() -> workspace.pageByUrl("wiki",
                "https://fremd.example.org/pages/viewpage.action?pageId=102", false))
                .isInstanceOf(CacheMissException.class);
        assertThat(workspace.recomputeEnrichments("wiki", "102", null).supported()).isTrue();
    }

    @Test
    void aRunningSyncCanBeCancelled() throws Exception {
        Workspace workspace = start().workspace();
        // Die Vorprüfung des Space-Keys (Space und erste Seite, limit=1) läuft synchron in startSync; erst die
        // Seitenlisten des Abgleichs hängen.
        server.holdUnless(uri -> uri.getPath().endsWith("/space/DEV") || "1".equals(limit(uri)));
        JobStatus started;
        try {
            started = workspace.startSync("wiki", "DEV");
            assertThat(workspace.cancelSync(started.id()).status().state()).isIn(JobState.CANCELLED, JobState.RUNNING,
                    JobState.QUEUED);
        } finally {
            server.release();
        }
        JobStatus finished = await(workspace, started);
        assertThat(finished.state()).isEqualTo(JobState.CANCELLED);
        assertThat(workspace.jobs()).extracting(JobStatus::id).contains(started.id());
    }

    @Test
    void onlyConfiguredSourcesAreReachable() throws Exception {
        Workspace workspace = start().workspace();

        assertThatThrownBy(() -> workspace.spaces("fremd")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> workspace.startSync("fremd", "DEV")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void aSourceWithoutTokenWaitsForTheSessionSecret() throws Exception {
        AppSettings settings = settings(Map.of("CLIENT_PASSWORD", TestCertificates.CLIENT_PASSWORD));
        runtime = AppRuntime.start(settings, new AppRuntime.SessionSecrets(), "test");
        assertThat(runtime.waitingForSecret()).containsExactly("wiki");
        assertThat(runtime.workspace().sources()).extracting(HarvestSource::id).containsExactly("handbuch");
        runtime.close();

        AppRuntime.SessionSecrets secrets = new AppRuntime.SessionSecrets();
        secrets.put("wiki", AppRuntime.SessionSecrets.login(SourceSettingsConfig.AuthType.PAT, null, "WIKI_TOKEN"),
                TOKEN.toCharArray());
        assertThat(secrets.toString()).doesNotContain(TOKEN);
        runtime = AppRuntime.start(settings, secrets, "test");
        assertThat(runtime.waitingForSecret()).isEmpty();
        assertThat(runtime.workspace().spaces("wiki")).extracting(Partition::key).containsExactly("DEV");
    }

    @Test
    void anInvalidSourceIsLeftOutAndTheOthersStart() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Path config = home.resolve("kaputt.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: ohne-adresse
                      type: confluence
                    - id: handbuch
                      type: files
                      options:
                        directory: %s
                    - id: unbekannt
                      type: sharepoint
                """.formatted(home.resolve("data"), files));
        AppSettings settings = new AppSettingsLoader(new Placeholders(name -> null), home).load(config);

        runtime = AppRuntime.start(settings, new AppRuntime.SessionSecrets(), "test");

        assertThat(runtime.workspace().sources()).extracting(HarvestSource::id).containsExactly("handbuch");
        assertThat(runtime.rejectedSources()).hasSize(2)
                .anySatisfy(p -> assertThat(p).contains("ohne-adresse"))
                .anySatisfy(p -> assertThat(p).contains("unbekannt"));
    }

    /**
     * Hintergrund-Reiter gegen den echten Harvester (Abnahme #1032): eine Gruppenzeile liest ohne Fehler, weil ihre
     * synthetische ID nie an den Core geht, und „Alle löschen“ eines Reiters löscht nur dessen Aufträge.
     */
    @Test
    void aBackgroundGroupRowReadsWithoutTheCoreAndDeletingOneTabKeepsTheOther() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        com.aresstack.spaceharvester.application.background.BackgroundJobs background =
                started.harvester().backgroundJobs();
        background.register(new com.aresstack.spaceharvester.application.background.BackgroundKind("probe",
                part -> com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> { })));
        com.aresstack.spaceharvester.application.background.BackgroundGroup group = background.start("probe",
                "Probe laden", List.of(
                        new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("handbuch",
                                "ALPHA", Map.of()),
                        new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("handbuch",
                                "BETA", Map.of())));
        java.util.concurrent.CountDownLatch finished = new java.util.concurrent.CountDownLatch(1);
        background.whenFinished(group.groupId(), done -> finished.countDown());
        assertThat(finished.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

        Object[] read = com.aresstack.spaceharvester.app.ui.ActivitiesView.read(workspace,
                "background-group:" + group.groupId());
        @SuppressWarnings("unchecked")
        List<JobStatus> jobs = (List<JobStatus>) read[0];
        // Neben der Gruppe stehen auch die Hintergrundaufträge des Starts in der Liste.
        assertThat(jobs).filteredOn(job -> group.groupId().equals(job.parameter(
                com.aresstack.spaceharvester.application.background.BackgroundJobs.GROUP)))
                .extracting(JobStatus::id).containsExactlyInAnyOrderElementsOf(group.jobIds());
        assertThat(read[1]).as("kein Detail aus dem Core für eine Gruppenzeile").isNull();

        JobStatus sync = await(workspace, workspace.startSync("handbuch", workspace.spaces("handbuch").get(0).key()));
        assertThat(workspace.deleteAllJobs(List.of(sync.id()))).isEqualTo(1);
        assertThat(workspace.jobs()).extracting(JobStatus::id).containsAll(group.jobIds())
                .doesNotContain(sync.id());
    }

    /**
     * AT zu #1059: Die Teile der Aussagenanalyse gehören dem Core. Wer lesen, aber nicht abgleichen darf, sieht sie in
     * der Auftragsliste nicht ({@code sync}); das Ergebnis im Reiter „Aussagen“ liest er trotzdem, und verfolgen und
     * abbrechen geht ohne Fehler.
     */
    @Test
    void theOutcomeOfAStatementGroupIsReadWithoutTheRightToSync() throws Exception {
        AppRuntime started = start();
        var harvester = started.harvester();
        String space = "DEV";
        var background = harvester.backgroundJobs();
        var group = background.start(com.aresstack.spaceharvester.application.background.BackgroundJobs.newGroupId(),
                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.KIND, "Aussagen analysieren",
                List.of(new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", space,
                        Map.of(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.SCOPE, "space",
                                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.ANALYZER,
                                "model", // ohne KI-Freigabe lässt er jede Seite bewusst aus
                                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.OWNER,
                                com.aresstack.spaceharvester.application.access.AccessContext.technicalClient(
                                        com.aresstack.spaceharvester.core.Harvester.OWNER).jobOwner()))));
        String partId = group.parts().get(0).id();
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        while (!harvester.jobs().status(partId).state().terminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
        }
        var finished = background.group(group.groupId()).orElseThrow();
        assertThat(finished.finished()).isTrue();
        Workspace readOnly = new Workspace(harvester,
                new com.aresstack.spaceharvester.application.access.AccessGuard((context, scope, kind) ->
                        kind == com.aresstack.spaceharvester.application.access.AccessKind.SYNC
                                ? com.aresstack.spaceharvester.application.access.AccessDecision.DENY
                                : com.aresstack.spaceharvester.application.access.AccessDecision.ALLOW),
                new ActivityLog(home.resolve("nur-lesen.txt")));

        assertThatThrownBy(() -> readOnly.events(partId)).as("so las der Reiter vorher")
                .isInstanceOf(com.aresstack.spaceharvester.application.jobs.UnknownJobException.class);
        long omitted = com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.omitted(
                harvester.jobs().events(partId));
        assertThat(omitted).as("ausgelassene Seiten stehen in den Ereignissen").isPositive();
        assertThat(readOnly.statementGroupOmitted(finished)).as("ohne sync gelesen").isEqualTo(omitted);
        Workspace blind = new Workspace(harvester,
                new com.aresstack.spaceharvester.application.access.AccessGuard((context, scope, kind) ->
                        kind == com.aresstack.spaceharvester.application.access.AccessKind.METADATA
                                ? com.aresstack.spaceharvester.application.access.AccessDecision.DENY
                                : com.aresstack.spaceharvester.application.access.AccessDecision.ALLOW),
                new ActivityLog(home.resolve("blind.txt")));
        assertThat(blind.statementGroupOmitted(finished)).as("ohne metadata nichts").isZero();
        readOnly.cancelStatementGroup(group.groupId()); // fertig: nichts zu tun, kein Fehler

        // Ein wartender Teil (hinter einem blockierten Vorgänger) lässt sich ohne sync abbrechen.
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        background.register(new com.aresstack.spaceharvester.application.background.BackgroundKind("probe",
                part -> com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
                    try {
                        release.await(30, java.util.concurrent.TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                })));
        var probe = background.start(com.aresstack.spaceharvester.application.background.BackgroundJobs.newGroupId(),
                "probe", "Probe", List.of(new com.aresstack.spaceharvester.application.background.BackgroundPart
                        .Draft("wiki", space)));
        var held = background.startSteps(com.aresstack.spaceharvester.application.background.BackgroundJobs.newGroupId(),
                com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.KIND, "Aussagen analysieren",
                List.of(new com.aresstack.spaceharvester.application.background.BackgroundJobs.Step(
                        com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.KIND, "Aussagen",
                        new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", space,
                                Map.of(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.SCOPE,
                                        "space")),
                        List.of(probe.parts().get(0).id()), List.of(), List.of("Probe"))));
        String heldId = held.parts().get(0).id();
        assertThat(harvester.jobs().status(heldId).state().terminal()).isFalse();
        readOnly.cancelStatementGroup(held.groupId());
        release.countDown();
        Instant cancelled = Instant.now().plus(Duration.ofSeconds(30));
        while (!harvester.jobs().status(heldId).state().terminal() && Instant.now().isBefore(cancelled)) {
            Thread.sleep(50);
        }
        assertThat(harvester.jobs().status(heldId).state()).isEqualTo(JobState.CANCELLED);
        java.util.concurrent.CompletableFuture<Boolean> onEdt = new java.util.concurrent.CompletableFuture<>();
        try (var watch = readOnly.watchBackground(group.groupId(),
                seen -> onEdt.complete(javax.swing.SwingUtilities.isEventDispatchThread() && seen.finished()))) {
            assertThat(onEdt.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("jetziger Stand auf dem EDT").isTrue();
        }
    }

    /**
     * W5: „Wissensgraph erzeugen“ über Seiten des Space „DEV“. Die Vorschau startet nichts und zählt je Stufe so viele
     * Teile, wie der Start danach anlegt; der Start legt eine Gruppe der Art „knowledge-graph“ mit dem Abschlussteil je
     * Space an, und sie lässt sich über die Gruppe abbrechen. Ihr Ende kommt über die Meldung des Core.
     */
    @Test
    void theKnowledgeGraphIsPreviewedWithoutStartingAndStartedAsOneBackgroundGroup() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        assertThat(await(workspace, workspace.startSync("wiki", "DEV", SyncMode.FULL)).state())
                .isEqualTo(JobState.COMPLETED);
        var pages = com.aresstack.spaceharvester.application.snapshot.PageSet.of(Map.of("wiki", List.of("100", "102")));
        var background = started.harvester().backgroundJobs();
        int groups = background.groups().size();

        var preview = workspace.previewKnowledgeGraph(pages);

        assertThat(background.groups()).as("die Vorschau startet nichts").hasSize(groups);
        var goal = preview.goal().orElseThrow();
        assertThat(goal.id()).isEqualTo(com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND);
        assertThat(goal.pages()).as("beide Seiten sichtbar").isEqualTo(2);
        assertThat(goal.parts()).as("ein Abschlussteil für den Space").isEqualTo(1);

        var plan = workspace.startKnowledgeGraph(pages);
        var group = plan.group().orElseThrow();
        assertThat(plan.notes()).isEqualTo(preview.notes());
        assertThat(group.kind()).isEqualTo(com.aresstack.spaceharvester.application.knowledge.KnowledgeGraphJobs.KIND);
        for (var stage : preview.stages()) {
            // Die Kennung eines angebundenen Dienstes ist auch seine Art.
            assertThat(group.parts().stream().filter(part -> stage.id().equals(part.parameter(BackgroundJobs.KIND)))
                    .count()).as("Teile der Stufe %s wie in der Vorschau", stage.id()).isEqualTo(stage.parts());
        }

        workspace.performBackgroundGroupAction(group.groupId(),
                com.aresstack.spaceharvester.application.jobs.JobAction.CANCEL);
        java.util.concurrent.CompletableFuture<com.aresstack.spaceharvester.application.background.BackgroundGroup>
                finished = new java.util.concurrent.CompletableFuture<>();
        try (var watch = workspace.whenBackgroundFinished(group.groupId(), finished::complete)) {
            var ended = finished.get(60, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(ended.finished()).isTrue();
            assertThat(workspace.knowledgeGraphNotes(ended)).as("nur Zahlen, keine Seitentitel")
                    .noneMatch(note -> note.contains("Betrieb und Wartung"));
            assertThat(workspace.runningKnowledgeGraph(pages)).as("eine beendete Gruppe läuft nicht mehr").isEmpty();
        }
        assertThat(workspace.knowledgeGraphNotReleased(pages)).as("ohne KI-Modell nichts ohne Freigabe").isZero();
    }

    /**
     * W5 (Auflage 7): Der Kreispfeil hinter einer gescheiterten Stufe setzt nur deren gescheiterte Teile fort, über
     * denselben Weg wie ▶; vom Benutzer abgebrochene Teile, Teile anderer Stufen, fremde Kennungen und fertige Teile
     * bleiben, wie sie sind.
     */
    @Test
    void retryingAStageResumesOnlyItsFailedPartsNeverOnesTheUserCancelled() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        assertThat(await(workspace, workspace.startSync("wiki", "DEV", SyncMode.FULL)).state())
                .isEqualTo(JobState.COMPLETED);
        var pages = com.aresstack.spaceharvester.application.snapshot.PageSet.of(Map.of("wiki", List.of("100", "102")));
        var group = workspace.startKnowledgeGraph(pages).group().orElseThrow();
        workspace.performBackgroundGroupAction(group.groupId(),
                com.aresstack.spaceharvester.application.jobs.JobAction.CANCEL);
        var ended = finished(workspace, group.groupId());
        List<String> cancelled = ended.parts().stream().filter(part -> part.state() == JobState.CANCELLED)
                .map(JobStatus::id).toList();
        assertThat(cancelled).as("sofort abgebrochen: mindestens ein Teil ist abgebrochen").isNotEmpty();
        List<String> asked = new ArrayList<>(cancelled);
        asked.add("unbekannt");
        ended.parts().stream().filter(part -> part.state() == JobState.COMPLETED).findFirst()
                .ifPresent(done -> asked.add(done.id()));

        assertThat(workspace.retryBackgroundParts(group.groupId(), asked).changed())
                .as("vom Benutzer abgebrochene Teile wiederholt der Kreispfeil nie").isZero();
        assertThat(started.harvester().backgroundJobs().group(group.groupId()).orElseThrow().parts())
                .filteredOn(part -> cancelled.contains(part.id()))
                .allSatisfy(part -> assertThat(part.state()).isEqualTo(JobState.CANCELLED));

        // Ein gescheiterter Teil (erfundene Art „Nachtlauf“, die immer scheitert) wird fortgesetzt.
        var background = started.harvester().backgroundJobs();
        java.util.concurrent.atomic.AtomicInteger runs = new java.util.concurrent.atomic.AtomicInteger();
        background.register(new com.aresstack.spaceharvester.application.background.BackgroundKind("nachtlauf",
                part -> com.aresstack.spaceharvester.application.jobs.JobTask.blocking(context -> {
                    runs.incrementAndGet();
                    throw new IllegalStateException("Nachtlauf scheitert");
                })));
        var broken = background.start(BackgroundJobs.newGroupId(), "nachtlauf", "Nachtlauf",
                List.of(new com.aresstack.spaceharvester.application.background.BackgroundPart.Draft("wiki", "DEV")));
        var failed = finished(workspace, broken.groupId());
        assertThat(failed.parts()).singleElement()
                .satisfies(part -> assertThat(part.state()).isEqualTo(JobState.FAILED));
        var outcome = workspace.retryBackgroundParts(broken.groupId(), failed.jobIds());
        assertThat(outcome.changed()).as("der gescheiterte Teil").isEqualTo(1);
        assertThat(outcome.failed()).isZero();
        assertThat(workspace.retryBackgroundParts("keine-gruppe", failed.jobIds()).changed())
                .as("nur Teile dieser Gruppe").isZero();
    }

    /**
     * W5 (Auflage 5): Kennt die Laufzeit eine Gruppe beim Anmelden nicht (etwa nach dem Umschalten der Ablage), sagt die
     * Beobachtung das einmal auf dem EDT, statt still keinen Stand zu melden.
     */
    @Test
    void watchingAGroupThisRuntimeDoesNotKnowSaysSoOnTheEdt() throws Exception {
        Workspace workspace = start().workspace();
        java.util.concurrent.CompletableFuture<Boolean> missing = new java.util.concurrent.CompletableFuture<>();
        try (var watch = workspace.watchBackground("unbekannte-gruppe", group -> missing.complete(false),
                () -> missing.complete(javax.swing.SwingUtilities.isEventDispatchThread()))) {
            assertThat(missing.get(10, java.util.concurrent.TimeUnit.SECONDS)).as("fehlt, gemeldet auf dem EDT")
                    .isTrue();
        }
    }

    /**
     * W5: Die Abdeckung je Space nennt den Space als Ort („in ‚DEV‘: …“, UX 22.11.2026), nicht als unerklärtes
     * Kürzel davor.
     */
    @Test
    void theCoverageNoteNamesTheSpaceAsPlace() throws Exception {
        AppRuntime started = start();
        Workspace workspace = started.workspace();
        assertThat(await(workspace, workspace.startSync("wiki", "DEV", SyncMode.FULL)).state())
                .isEqualTo(JobState.COMPLETED);
        var pages = com.aresstack.spaceharvester.application.snapshot.PageSet.of(Map.of("wiki", List.of("100", "102")));
        var group = workspace.startKnowledgeGraph(pages).group().orElseThrow();
        var ended = finished(workspace, group.groupId());

        List<String> notes = workspace.knowledgeGraphNotes(ended);
        assertThat(notes).as("ohne Analyse fehlt die Abdeckung; der Space steht als Ort davor")
                .anySatisfy(note -> assertThat(note).startsWith("in ‚DEV‘: ").contains("Seiten mit aktueller Analyse"))
                .noneMatch(note -> note.startsWith("DEV: "));
    }

    /** Wartet auf das Ende einer Hintergrundgruppe über die Meldung des Core. */
    private static com.aresstack.spaceharvester.application.background.BackgroundGroup finished(Workspace workspace,
            String groupId) throws Exception {
        java.util.concurrent.CompletableFuture<com.aresstack.spaceharvester.application.background.BackgroundGroup>
                done = new java.util.concurrent.CompletableFuture<>();
        try (var watch = workspace.whenBackgroundFinished(groupId, done::complete)) {
            return done.get(60, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private AppRuntime start() throws Exception {
        runtime = AppRuntime.start(settings(Map.of("WIKI_TOKEN", TOKEN,
                "CLIENT_PASSWORD", TestCertificates.CLIENT_PASSWORD)), new AppRuntime.SessionSecrets(), "test");
        return runtime;
    }

    private AppSettings settings(Map<String, String> env) throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nNeustart mit systemctl.\n");
        Files.writeString(files.resolve("notizen.txt"), "Notizen\n");
        Path config = home.resolve("space-harvester-app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: wiki
                      type: confluence
                      title: Wiki
                      base-url: %s
                      auth:
                        type: pat
                        token: ${WIKI_TOKEN}
                      client-certificate:
                        enabled: true
                        type: pkcs12
                        path: %s
                        alias: %s
                        password: ${CLIENT_PASSWORD}
                      trust-bundle: test
                      options:
                        connectTimeoutMillis: 2000
                    - id: handbuch
                      type: files
                      options:
                        directory: %s
                spring:
                  ssl:
                    bundle:
                      jks:
                        test:
                          truststore:
                            location: file:%s
                            password: %s
                """.formatted(home.resolve("data"), server.baseUrl(), certificates.client, TestCertificates.CLIENT_ALIAS,
                files, certificates.harvesterTrust, TestCertificates.STORE_PASSWORD)
                .replace("space-harvester:\n  sources:", "space-harvester:\n" + extraConfig + "  sources:"));
        AppSettings settings = new AppSettingsLoader(new Placeholders(env::get), home).load(config);
        assertThat(settings.problems()).isEmpty();
        return settings;
    }

    /** Wartet, bis die Updates der Quelle „handbuch“ ein passendes enthalten (die Marke folgt dem Ende per Meldung). */
    private static com.aresstack.spaceharvester.application.changelog.ChangeUpdate awaitUpdate(Workspace workspace,
            java.util.function.Predicate<com.aresstack.spaceharvester.application.changelog.ChangeUpdate> wanted)
            throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        java.util.List<com.aresstack.spaceharvester.application.changelog.ChangeUpdate> all = List.of();
        while (Instant.now().isBefore(deadline)) {
            all = workspace.changeUpdates("handbuch", null);
            java.util.Optional<com.aresstack.spaceharvester.application.changelog.ChangeUpdate> found =
                    all.stream().filter(wanted).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(50);
        }
        throw new AssertionError("kein passendes Update: " + all);
    }

    private static JobStatus await(Workspace workspace, JobStatus started) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JobStatus status = started;
        while (!status.state().terminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            status = workspace.job(started.id());
        }
        return status;
    }

    private static String limit(URI uri) {
        String query = uri.getQuery() == null ? "" : uri.getQuery();
        for (String part : query.split("&")) {
            if (part.startsWith("limit=")) {
                return part.substring("limit=".length());
            }
        }
        return null;
    }

    private static void assertNoFileContains(Path directory, String secret) throws Exception {
        byte[] needle = secret.getBytes(StandardCharsets.UTF_8);
        // Ohne Files.walk: Verschwindet ein temporäres Verzeichnis eines laufenden Auftrags während des Durchlaufs,
        // bricht walk mit NoSuchFileException ab; hier wird es übersprungen.
        List<Path> found = new ArrayList<>();
        Files.walkFileTree(directory, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) {
                    found.add(file);
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }

            @Override
            public java.nio.file.FileVisitResult visitFileFailed(Path file, IOException e) throws IOException {
                if (e instanceof java.nio.file.NoSuchFileException) {
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
                throw e;
            }

            @Override
            public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null && !(e instanceof java.nio.file.NoSuchFileException)) {
                    throw e;
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
        for (Path file : found) {
            byte[] bytes;
            try {
                bytes = Files.readAllBytes(file);
            } catch (java.nio.file.NoSuchFileException e) {
                // Eine temporäre Datei eines laufenden Auftrags (etwa beim Entpacken eines Anhangs) ist schon
                // wieder weg; was bleibt, wird geprüft.
                continue;
            }
            String content = new String(bytes, StandardCharsets.ISO_8859_1);
            assertThat(content).as(file.toString()).doesNotContain(new String(needle, StandardCharsets.ISO_8859_1));
        }
    }
}
