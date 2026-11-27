package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import java.time.Instant;
import java.util.Map;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Reiter „Export“ (Angelo, 13.11.2026): Die Auswahl links bestimmt Stand und Ausgabe; ein fertiger Download
 * dieser Auswahl bietet seine Formate an, ein Klick legt die Datei aus dem vorhandenen Datenbestand direkt im
 * Ausgabeordner ab, ohne Dateidialog und ohne technische Dateien. Aufträge hat keine Formate mehr.
 */
class ExportPanelTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    /** Eine Quelle „handbuch“ aus einem Ordner mit einer Seite; die Laufzeit läuft, der Ausgabeordner ist {@code out}. */
    private Path startHandbuch() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n\nText.\n");
        Path out = home.resolve("Ausgabe");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                  output-directory: "%s"
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), out, files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        // Die App prüft beim Start keine Verbindung mehr (Angelo 24.11.2026: nichts ohne Klick); hier wie ein Klick auf
        // „Verbindung prüfen …“ an jeder Karte.
        SwingUtilities.invokeAndWait(() -> context.runtime().workspace().sources()
                .forEach(source -> context.checkConnection(source.id(), () -> { })));
        await(() -> context.connection("handbuch").state() == AppContext.Connection.State.CONNECTED);
        return out;
    }

    /**
     * Angelo 16.11.2026: Ein Format-Knopf ohne Bestand lädt erst die Seiten, danach entsteht die Datei als eigener
     * Auftrag „Datei erzeugen“ mit Status unter Aufträge; die Ansicht erfährt das Ende per Auftragsmeldung.
     */
    @Test
    void aFormatButtonWithoutHoldingsLoadsThePagesFirstAndThenProducesTheFileAsAJob() throws Exception {
        Path out = startHandbuch();
        Workspace workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        ExportPanel[] panel = new ExportPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            assertThat(panel[0].exportBlocked("pdf")).as("ohne Auswahl").isEqualTo(ExportPanel.NO_SELECTION);
            panel[0].selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
        });
        await(() -> panel[0].showsSelection() && !panel[0].files.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].exportBlocked("pdf")).as("ohne Bestand erlaubt: lädt erst").isNull();
            assertThat(panel[0].shownDataset()).isNull();
            panel[0].output("pdf");
            assertThat(panel[0].exportBlocked("pdf")).contains("wird gerade erzeugt");
        });
        await(() -> Files.isDirectory(out) && list(out).stream().anyMatch(p -> p.toString().endsWith(".pdf")));
        List<JobStatus> jobs = workspace.jobs();
        assertThat(jobs).as("Daten-Auftrag von der Quelle und Auftrag „Datei erzeugen“")
                .anyMatch(job -> job.type() == JobType.EXPORT && space.equals(job.partitionKey()))
                .anyMatch(job -> job.type() == JobType.CONVERSION && space.equals(job.partitionKey())
                        && "pdf".equals(job.parameter(ExportConversions.FORMAT)));
        await(() -> panel[0].exportBlocked("pdf") == null);
        await(() -> panel[0].shownDataset() != null);
        await(() -> panel[0].files.getText().contains(".pdf"));
    }

    /**
     * Architekt 17.11.2026: „Gestartet“ und das Ergebnis eines Exports stehen in der Statuszeile des Hauptfensters,
     * nicht auf der Platte. Die Laufmeldung bleibt, bis das Ergebnis sie ersetzt; das Ergebnis bleibt mit ✕.
     */
    @Test
    void startAndResultOfAnExportGoToTheStatusLineAndOnlyTheResultIsClosable() throws Exception {
        Path out = startHandbuch();
        Workspace workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        List<StatusMessageEvent.Payload> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        context.events().subscribe(StatusMessageEvent.class, event -> messages.add(event.getPayload()));
        ExportPanel[] panel = new ExportPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            panel[0].selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
        });
        await(() -> panel[0].showsSelection() && !panel[0].files.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            panel[0].output("pdf");
            // Architekt: Die Statuszeile gehört dem Hauptfenster; ein Auswahlwechsel beendet weder die Laufmeldung
            // des gestarteten Exports noch sein Ergebnis, das Ergebnis ersetzt „gestartet“ auch bei anderer Auswahl.
            panel[0].selection(null);
        });
        await(() -> messages.stream().anyMatch(m -> m.getText().startsWith("PDF-Dokument wird eingeplant")));
        StatusMessageEvent.Payload started = messages.stream()
                .filter(m -> m.getText().startsWith("PDF-Dokument wird eingeplant")).findFirst().orElseThrow();
        assertThat(started.isClosable()).as("Laufmeldung ohne ✕, das Ergebnis ersetzt sie").isFalse();
        assertThat(started.getDurationMs()).isEqualTo(Integer.MAX_VALUE);
        await(() -> Files.isDirectory(out) && list(out).stream().anyMatch(p -> p.toString().endsWith(".pdf")));
        await(() -> messages.stream().anyMatch(m -> m.getText().contains("gespeichert in")));
        StatusMessageEvent.Payload result = messages.stream()
                .filter(m -> m.getText().contains("gespeichert in")).findFirst().orElseThrow();
        assertThat(result.getText()).contains(".pdf");
        assertThat(result.isClosable()).as("Ergebnis bleibt, bis es der Benutzer wegklickt").isTrue();
        assertThat(result.getDurationMs()).isEqualTo(Integer.MAX_VALUE);
        assertThat(messages).as("der Auswahlwechsel leert die Statuszeile nicht")
                .noneMatch(m -> m.getText().isEmpty() && m.getReplaces() == null);
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].feedbackVisibleForTest())
                .as("die Platte zeigt nur Warnungen und Probleme").isFalse());
    }

    /** Scheitert ein Export, während etwas anderes gewählt ist, steht er orange mit ✕ in der Statuszeile. */
    @Test
    void aFailedExportAtAnotherSelectionIsAnOrangeClosableStatusMessage() throws Exception {
        Path out = startHandbuch();
        Workspace workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        // Der Ausgabeordner ist eine Datei: der Auftrag „Datei erzeugen“ (oder sein Start) scheitert.
        Files.writeString(out, "kein Ordner");
        List<StatusMessageEvent.Payload> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
        context.events().subscribe(StatusMessageEvent.class, event -> messages.add(event.getPayload()));
        ExportPanel[] panel = new ExportPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            panel[0].selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
        });
        await(() -> panel[0].showsSelection() && !panel[0].files.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            panel[0].output("pdf");
            panel[0].selection(null);
        });
        await(() -> messages.stream().anyMatch(m -> m.getSeverity() == com.aresstack.spaceharvester.app.event.Severity.WARN));
        StatusMessageEvent.Payload failed = messages.stream()
                .filter(m -> m.getSeverity() == com.aresstack.spaceharvester.app.event.Severity.WARN).findFirst().orElseThrow();
        assertThat(failed.getText()).startsWith("PDF-Dokument");
        assertThat(failed.isClosable()).as("bleibt mit ✕, verschwindet nicht von selbst").isTrue();
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].feedbackVisibleForTest())
                .as("bei anderer Auswahl keine Platte; der Grund steht unter Aufträge").isFalse());
    }

    @Test
    void aFinishedDownloadIsExportedFromItsDatasetIntoTheOutputDirectory() throws Exception {
        Path out = startHandbuch();
        Workspace workspace = context.runtime().workspace();
        String space = workspace.spaces("handbuch").get(0).key();
        String job = workspace.startExport("handbuch", space, List.of(Downloads.FORMAT)).id();

        ExportPanel[] panel = new ExportPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            // Ohne Auswahl nur der Hinweis; die Quelle zeigt ihre Spaces; erst der Space zeigt Stand und Ausgabe.
            assertThat(panel[0].exportBlocked("pdf")).isEqualTo(ExportPanel.NO_SELECTION);
            panel[0].selection(new ExportPanel.Selection.Node("handbuch", space, null, space));
        });
        // Sobald der Download fertig ist, hängt der Datenbestand an der Auswahl (Auftragsmeldung, kein Zeitgeber).
        await(() -> panel[0].shownDataset() != null && panel[0].shownDataset().exportId().equals(job));
        await(() -> panel[0].exportBlocked("markdown-package") == null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].exportBlocked("pdf")).isNull();
            assertThat(panel[0].files.getText()).startsWith("Noch nichts exportiert");
            panel[0].exportAs(job, "markdown-package");
            assertThat(panel[0].exportBlocked("pdf")).as("eine Datei je Auswahl zugleich")
                    .contains("wird gerade erzeugt");
        });
        await(() -> Files.isDirectory(out) && list(out).stream().anyMatch(p -> p.toString().endsWith(".zip")));
        // Nur die gewünschte Datei, keine technischen Dateien wie pages.json oder tree.json.
        assertThat(list(out)).singleElement().satisfies(p ->
                assertThat(p.getFileName().toString()).startsWith(space).endsWith(".zip"));
        // Die Karte „Ausgabe“ listet die erzeugte Datei.
        await(() -> panel[0].files.getText().contains(".zip"));
        await(() -> panel[0].exportBlocked("pdf") == null);
        // Das JSON-Symbol erzeugt das Paket mit Medien als ZIP.
        assertThat(ExportPanel.FORMATS).contains("json-package").doesNotContain("json");
        SwingUtilities.invokeAndWait(() -> panel[0].exportAs(job, "json-package"));
        await(() -> list(out).stream().filter(p -> p.toString().endsWith(".zip")).count() == 2);
        // „Analyse“ (#359) erzeugt die Analysedaten als eigene JSON-Datei, ohne die übrigen Dateien zu berühren.
        await(() -> panel[0].exportBlocked("analysis") == null);
        SwingUtilities.invokeAndWait(() -> panel[0].exportAs(job, "analysis"));
        await(() -> list(out).stream().anyMatch(p -> p.toString().endsWith(".json")));
        assertThat(list(out)).hasSize(3);
        await(() -> panel[0].exportBlocked("pdf") == null);
        // Die Format-Knöpfe geben aus dem Bestand aus (ADR 0029): der Daten-Auftrag liest nur den Cache; da sich
        // nichts geändert hat, kommt der fertige Download zurück, und „Datei erzeugen“ läuft als eigener Auftrag.
        SwingUtilities.invokeAndWait(() -> {
            panel[0].output("pdf");
            assertThat(panel[0].exportBlocked("pdf")).as("eine Datei je Auswahl zugleich").contains("wird gerade erzeugt");
        });
        await(() -> list(out).stream().anyMatch(p -> p.toString().endsWith(".pdf")));
        await(() -> panel[0].exportBlocked("pdf") == null);
        assertThat(list(out)).hasSize(4);
        assertThat(workspace.jobs()).filteredOn(j -> j.type() == JobType.CONVERSION).hasSize(4)
                .allMatch(j -> j.state() == JobState.COMPLETED);
        // Der Download von der Quelle und die eine Ausgabe aus dem Bestand; keine weitere je Format.
        assertThat(workspace.jobs()).filteredOn(j -> j.type() == JobType.EXPORT).hasSize(2)
                .anyMatch(j -> "true".equals(j.parameter("holdings")));
        // Eine Seite derselben Quelle hat keinen eigenen Download: „Noch nicht geladen“, der Datenbestand des Space
        // gilt nicht für sie; der Knopf bleibt frei und lädt erst (Angelo 16.11.2026).
        SwingUtilities.invokeAndWait(() -> panel[0].selection(new ExportPanel.Selection.Node("handbuch", space, "x",
                "Seite x")));
        await(() -> panel[0].shownDataset() == null);
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].exportBlocked("pdf")).isNull());
    }

    /** Stand = jüngster fertiger Download genau dieser Auswahl; ein Seiten-Download zählt nicht für den Space. */
    @Test
    void aFolderResolvesToItsDirectFavoritesOrWithSubfoldersToAllBelow() {
        // ADR 0034 §6: aus = die direkten Favoriten des Ordners, an = samt Unterordnern rekursiv; je Favorit eine
        // Wurzel wie im Baum (Seite samt Unterseiten, Space oder Quelle), ohne Doppelte.
        com.aresstack.spaceharvester.app.ui.Favorites favorites = new com.aresstack.spaceharvester.app.ui.Favorites(
                () -> null);
        String top = favorites.createFolder("Betrieb", null);
        String below = favorites.createFolder("Notfall", top);
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite page =
                new com.aresstack.spaceharvester.app.ui.Favorites.Favorite("wiki", "OPS", "4711", "Runbook", null,
                        null);
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite space =
                com.aresstack.spaceharvester.app.ui.Favorites.Favorite.space("wiki", "DEV", "Entwicklung", "Wiki");
        favorites.toggle(page, top);
        favorites.toggle(space, below);

        ExportPanel.Selection.Collection direct = ExportPanel.Selection.Collection.of(favorites, top, "Betrieb", false);
        ExportPanel.Selection.Collection all = direct.withSubfolders(favorites, true);

        assertThat(direct.roots(null)).containsExactly(page.root());
        assertThat(direct.withSubfolders()).isFalse();
        // Baumreihenfolge: der Unterordner „Notfall“ steht vor dem später gesetzten Favoriten.
        assertThat(all.roots(null)).containsExactly(space.root(), page.root());
        assertThat(all.withSubfolders()).isTrue();
        assertThat(all.title()).isEqualTo("Betrieb");
        assertThat(all).isNotEqualTo(direct);
    }

    @Test
    void aCollectionIsNeverTheGlobalSelectionAndCoversOnlyItsRoots() {
        // ADR 0034 §3: „global“ heißt All, nie „ohne Quelle“; eine Sammlung gilt sonst als „Alle Wissensquellen“.
        com.aresstack.spaceharvester.application.holdings.Selection.Root page =
                com.aresstack.spaceharvester.application.holdings.Selection.Root.subtree("wiki", "OPS", "4711");
        com.aresstack.spaceharvester.application.holdings.Selection.Root space =
                com.aresstack.spaceharvester.application.holdings.Selection.Root.space("wiki", "DEV");
        ExportPanel.Selection collection = new ExportPanel.Selection.Collection("f1", "Betrieb", List.of(page, space),
                true);

        assertThat(collection.global()).isFalse();
        assertThat(collection.sourceId()).isNull();
        assertThat(ExportPanel.Selection.ALL.global()).isTrue();
        assertThat(ExportPanel.Selection.of(null, null, null, "egal")).isSameAs(ExportPanel.Selection.ALL);
        assertThat(collection.toCore(null).roots()).containsExactly(page, space);
        assertThat(collection.covers(job("a", "OPS", "4711", JobState.COMPLETED, null))).isTrue();
        assertThat(collection.covers(job("b", "DEV", null, JobState.COMPLETED, null))).isTrue();
        assertThat(collection.covers(job("c", "OPS", null, JobState.COMPLETED, null))).isFalse();
        // Die aufgelösten Wurzeln stehen in equals: eine Änderung am Ordner ist eine neue Auswahl.
        assertThat(collection).isNotEqualTo(new ExportPanel.Selection.Collection("f1", "Betrieb", List.of(page), true));
        assertThat(collection.normalized()).isEqualTo(
                new ExportPanel.Selection.Collection("f1", "Umbenannt", List.of(page, space), true).normalized());
        assertThat(new ExportPanel.Selection.Node("wiki", "OPS", null, "Betrieb").toCore(null).roots())
                .containsExactly(com.aresstack.spaceharvester.application.holdings.Selection.Root.space("wiki", "OPS"));
    }

    @Test
    void theStandIsTheNewestCompletedDownloadOfExactlyTheSelection() {
        JobStatus older = job("j1", "OPS", null, JobState.COMPLETED, Instant.parse("2026-11-13T18:00:00Z"));
        JobStatus newer = job("j2", "OPS", null, JobState.COMPLETED, Instant.parse("2026-11-13T19:00:00Z"));
        JobStatus page = job("j3", "OPS", "4711", JobState.COMPLETED, Instant.parse("2026-11-13T19:30:00Z"));
        JobStatus other = job("j4", "DEV", null, JobState.COMPLETED, Instant.parse("2026-11-13T19:40:00Z"));
        JobStatus running = job("j5", "OPS", null, JobState.RUNNING, null);
        List<JobStatus> jobs = List.of(older, newer, page, other, running);
        ExportPanel.Selection space = new ExportPanel.Selection.Node("wiki", "OPS", null, "Betrieb");
        ExportPanel.Selection subtree = new ExportPanel.Selection.Node("wiki", "OPS", "4711", "Seite");
        ExportPanel.Selection none = new ExportPanel.Selection.Node("wiki", "QA", null, "QA");

        assertThat(ExportPanel.newest(jobs, space)).isSameAs(newer);
        assertThat(ExportPanel.running(jobs, space)).isSameAs(running);
        assertThat(ExportPanel.newest(jobs, subtree)).isSameAs(page);
        assertThat(ExportPanel.running(jobs, subtree)).isNull();
        assertThat(ExportPanel.newest(jobs, none)).isNull();
        // Der Stand je Space kommt aus dem Bestand, nicht aus der Auftragsablage (Angelo 17.11.2026: dort stand
        // „Noch nicht geladen“ für längst geladene Spaces, weil alte Downloads aus der Ablage verschwinden).
        SpaceSummary inventoried = new SpaceSummary("wiki", "OPS", new CacheGeneration("g1", CacheState.CONFIRMED,
                Instant.parse("2026-11-13T18:05:00Z"), null), new HoldingCounts(127, 120, 0, 0));
        SpaceSummary complete = new SpaceSummary("wiki", "OPS", new CacheGeneration("g1", CacheState.CONFIRMED,
                Instant.parse("2026-11-13T18:05:00Z"), null), new HoldingCounts(127, 127, 0, 0));
        SpaceSummary single = new SpaceSummary("wiki", "OPS", null, new HoldingCounts(3, 3, 0, 0));
        SpaceSummary empty = new SpaceSummary("wiki", "OPS", null, HoldingCounts.ZERO);
        assertThat(ExportPanel.standText(null, null)).isEqualTo(ExportPanel.NOT_LOADED);
        assertThat(ExportPanel.standText(empty, null)).isEqualTo(ExportPanel.NOT_LOADED);
        assertThat(ExportPanel.standText(inventoried, null)).startsWith("120 von 127 Seiten lokal · Stand vom ");
        assertThat(ExportPanel.standText(single, null)).isEqualTo("3 von 3 Seiten lokal · " + ExportPanel.INCOMPLETE);
        assertThat(ExportPanel.standText(inventoried, running)).startsWith("Download ").contains("von 100 Seiten");
        assertThat(ExportPanel.standTone(complete, null)).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.GREEN);
        assertThat(ExportPanel.standTone(inventoried, null)).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.ORANGE);
        assertThat(ExportPanel.standTone(null, null)).isEqualTo(com.aresstack.spaceharvester.app.ui.Comic.MUTED);
    }

    private static JobStatus job(String id, String space, String rootPageId, JobState state, Instant finished) {
        return new JobStatus(id, JobType.EXPORT, "wiki", space, "local", state, new JobProgress(100, 100, 90, 0),
                null, false, Instant.parse("2026-11-13T17:00:00Z"), Instant.parse("2026-11-13T17:00:00Z"), finished,
                rootPageId, null, Map.of(), false, false, null);
    }

    @Test
    void theCardCountsStoredMissingSignInAndDeliberatelySkippedMediaInPlainWords() {
        // Bewusst Ausgelassenes getrennt nach Grund (Angelo 13.11.2026): Diagramm-Vorschauen und nach Einstellung.
        ExportDatasetInfo.Media media = new ExportDatasetInfo.Media(7, 2, 1, 3, 4);
        assertThat(ExportPanel.media(media)).isEqualTo("7 gesichert · 2 fehlen · 1 Anmeldung erforderlich · "
                + "3 Diagramm-Vorschauen nicht verwendet (aus Quelle gezeichnet) · 4 nach Einstellung ausgelassen");
        assertThat(ExportPanel.media(new ExportDatasetInfo.Media(1, 1, 0, 0))).isEqualTo("1 gesichert · 1 fehlt");
        assertThat(ExportPanel.media(new ExportDatasetInfo.Media(0, 0, 0, 1)))
                .isEqualTo("1 Diagramm-Vorschau nicht verwendet (aus Quelle gezeichnet)");
        assertThat(media.total()).isEqualTo(17);
        assertThat(media.skipped()).isEqualTo(7);
        assertThat(ExportPanel.cardImages(ExportDatasetInfo.Images.INCOMPLETE, media))
                .startsWith("Bilder: unvollständig").endsWith("\nMedien: " + ExportPanel.media(media));
        assertThat(ExportPanel.cardImages(ExportDatasetInfo.Images.NOT_COLLECTED, ExportDatasetInfo.Media.NONE))
                .isEqualTo(ExportPanel.cardImages(ExportDatasetInfo.Images.NOT_COLLECTED));
        assertThat(ExportPanel.cardImages(ExportDatasetInfo.Images.COLLECTING))
                .startsWith("Bilder: werden beschafft").contains("noch ohne Bilder");
        assertThat(ExportPanel.cardImages(ExportDatasetInfo.Images.COMPLETE)).isEqualTo("Bilder: vollständig");
        assertThat(ExportPanel.cardImages(ExportDatasetInfo.Images.INCOMPLETE)).startsWith("Bilder: unvollständig");
    }

    @Test
    void theSourceExportsAreTwoMoreButtonsThatReadTheSourceInsteadOfTheHoldings() {
        // Angelo 16.11.2026: offizielle Confluence-Metadaten und Analytics als eigene Unterpunkte, getrennt vom
        // Analyse-Export (eigene Analysedaten) und von den Format-Knöpfen (Ausgabe aus dem Bestand).
        assertThat(ExportPanel.SOURCE_EXPORTS).containsExactly("metadata", "analytics");
        assertThat(ExportPanel.FORMATS).doesNotContainAnyElementsOf(ExportPanel.SOURCE_EXPORTS);
        assertThat(ExportPanel.sourceTitle("metadata")).isEqualTo("Confluence-Metadaten");
        assertThat(ExportPanel.sourceTitle("analytics")).isEqualTo("Analytics");
        assertThat(ExportPanel.sourceExport(JobType.METADATA_EXPORT)).isTrue();
        assertThat(ExportPanel.sourceExport(JobType.ANALYTICS_EXPORT)).isTrue();
        assertThat(ExportPanel.sourceExport(JobType.EXPORT)).isFalse();
        assertThat(ExportPanel.affects(new JobStatus("m", JobType.ANALYTICS_EXPORT, "wiki", "OPS", "local",
                JobState.RUNNING, new JobProgress(3, 1, 1, 0), null, false, Instant.EPOCH, Instant.EPOCH, null, null,
                null, Map.of(), false, false, null))).isTrue();
    }

    @Test
    void analysisIsExactlyOneMoreButtonAfterTheFormats() {
        assertThat(ExportPanel.FORMATS).containsExactly("pdf", "docx", "markdown-package", "json-package", "analysis");
        assertThat(ExportPanel.Format.title("analysis")).isEqualTo("Analyse");
    }

    @Test
    void formatsAppearInTheOrderOfTheSketchWithReadableNames() {
        assertThat(ExportPanel.Format.ordered(List.of("docx", "json", "markdown-package", "markdown", "pdf",
                        "epub")))
                .extracting(ExportPanel.Format::title)
                .containsExactly("PDF-Dokument", "Word-Dokument", "Markdown", "Markdown mit Medien", "JSON", "EPUB");
    }

    @Test
    void noticesReadAsPlainWordsAndNameWhatWasLeftOut() {
        ExportConversions.ConvertedFile file = new ExportConversions.ConvertedFile("e1", "docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", Path.of("A.docx"), 10, "00",
                List.of(new ConversionNotice("101", ConversionNotice.Kind.MISSING_IMAGE, "plan.png", "forbidden"),
                        new ConversionNotice("102", ConversionNotice.Kind.MISSING_DIAGRAM, "mermaid", "render-failed"),
                        new ConversionNotice("103", ConversionNotice.Kind.MISSING_ATTACHMENT, "liste.xlsx", null)),
                5);

        assertThat(ExportPanel.notices(file)).isEqualTo("""
                Seite 101: fehlendes Bild „plan.png“ (keine Berechtigung)
                Seite 102: fehlendes Diagramm „mermaid“ (Darstellung fehlgeschlagen)
                Seite 103: fehlender Anhang „liste.xlsx“
                … und 2 weitere Hinweise""");
    }

    /**
     * ADR 0034 (3c): Ein Favoriten-Ordner exportiert wie eine Quelle, je Favorit eine Zeile und eine Wurzel (eine Quelle
     * darin je Space); ein veralteter Favorit fällt still weg, ein leerer Ordner sperrt die Format-Knöpfe.
     */
    @Test
    void aFavoritesFolderExportsOneRowAndRootPerFavoriteLikeASource() throws Exception {
        startTwo();
        ExportPanel[] panel = new ExportPanel[1];
        com.aresstack.spaceharvester.application.holdings.Selection.Root anhang =
                com.aresstack.spaceharvester.application.holdings.Selection.Root.source("anhang");
        com.aresstack.spaceharvester.application.holdings.Selection.Root gone =
                com.aresstack.spaceharvester.application.holdings.Selection.Root.source("gibt-es-nicht");
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            panel[0].selection(new ExportPanel.Selection.Collection("f1", "Betrieb", List.of(anhang, gone), true));
        });
        await(() -> panel[0].showsSelection() && !panel[0].spaces.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].coreSelectionForTest().roots()).as("je Space der Favoriten-Quelle eine Wurzel")
                    .hasSize(1).allMatch(root -> root.sourceId().equals("anhang") && root.spaceKey() != null);
            assertThat(panel[0].exportBlocked("pdf")).as("Format-Knöpfe frei wie bei einer Quelle").isNull();
            panel[0].selection(new ExportPanel.Selection.Collection("f2", "Leer", List.of(), true));
        });
        await(() -> texts(panel[0].spaces).contains(ExportPanel.NO_FAVORITES));
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].exportBlocked("pdf"))
                .isEqualTo(ExportPanel.NO_FAVORITES));
        assertThat(ExportPanel.perPart(new ExportPanel.Selection.Collection("f1", null, List.of(), true)))
                .isEqualTo("je Favorit");
    }

    /**
     * Angelo 17.11.2026: Auf der Quelle und bei „Wissensquellen“ ist der Export echt wie der Knopf in der Seitenleiste:
     * je Space ein eigener Auftrag und eine getrennte Datei, die Dateiliste nennt sie je Space. Nur „Wissensquellen“
     * fragt vorher zurück (Architekt 17.11.2026); ein Nein startet nichts.
     */
    @Test
    void theRootStartsOneJobPerSpaceWithSeparateFilesAndOnlyAllSourcesAskFirst() throws Exception {
        Path out = startTwo();
        Workspace workspace = context.runtime().workspace();
        ExportPanel[] panel = new ExportPanel[1];
        List<String> asked = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            panel[0].confirmAll((title, question) -> {
                asked.add(question);
                return !question.contains("Word");
            });
            // Eine Quelle, deren Space nie geladen wurde.
            panel[0].selection(new ExportPanel.Selection.Node("anhang", null, null, "Anhang"));
        });
        await(() -> panel[0].showsSelection() && !panel[0].spaces.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].coreSelectionForTest().roots()).as("je Space der Quelle eine Wurzel, auch ohne Bestand")
                    .hasSize(1).allMatch(root -> root.sourceId().equals("anhang") && root.spaceKey() != null);
            panel[0].selection(ExportPanel.Selection.ALL);
        });
        await(() -> panel[0].showsSelection() && !panel[0].spaces.isVisible());
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].coreSelectionForTest().roots()).as("Analytics, Metadaten und Formate je Space")
                    .hasSize(2).extracting(root -> root.sourceId()).containsExactlyInAnyOrder("handbuch", "anhang");
            assertThat(panel[0].exportBlocked("pdf")).as("Format-Knöpfe frei wie beim Space").isNull();
            assertThat(panel[0].sourceBlocked("analytics")).as("Dateiquellen ohne REST")
                    .isEqualTo(ExportPanel.ONLY_CONFLUENCE);
            panel[0].output("docx");
            assertThat(asked).hasSize(1).first().asString().contains("Word-Dokument für alle Wissensquellen")
                    .contains("jeden ihrer 2 Spaces").contains("je Space entsteht eine eigene Datei");
            // Hintergrundaufträge des Starts (Verweise, Analysen nach Update) zählen nicht.
            assertThat(workspace.jobs()).filteredOn(job -> !job.type().background()).as("Nein startet nichts")
                    .isEmpty();
            panel[0].output("pdf");
            assertThat(panel[0].exportBlocked("pdf")).contains(ExportPanel.IN_PROGRESS);
        });
        await(() -> Files.isDirectory(out) && list(out).stream().filter(p -> p.toString().endsWith(".pdf")).count() == 2);
        await(() -> workspace.jobs().stream().filter(job -> job.type() == JobType.CONVERSION)
                .allMatch(job -> job.state() == JobState.COMPLETED));
        assertThat(workspace.jobs()).filteredOn(job -> job.type() == JobType.CONVERSION).hasSize(2)
                .extracting(JobStatus::sourceId).containsExactlyInAnyOrder("handbuch", "anhang");
        await(() -> panel[0].exportBlocked("pdf") == null);
        await(() -> texts(panel[0].spaces).stream().filter(text -> text.contains(".pdf")).count() == 2);
        assertThat(texts(panel[0].spaces)).as("je Space eine Zeile mit Quellentitel")
                .anyMatch(text -> text.startsWith("Handbuch › ")).anyMatch(text -> text.startsWith("Anhang › "));

        // Eine einzelne Quelle startet ohne Rückfrage, nur für ihre Spaces.
        asked.clear();
        SwingUtilities.invokeAndWait(() -> panel[0].selection(new ExportPanel.Selection.Node("anhang", null, null, "Anhang")));
        await(() -> texts(panel[0].spaces).stream().filter(text -> text.contains(".pdf")).count() == 1);
        SwingUtilities.invokeAndWait(() -> panel[0].output("analysis"));
        await(() -> workspace.jobs().stream().filter(job -> job.type() == JobType.CONVERSION
                && "analysis".equals(job.parameter(ExportConversions.FORMAT))).count() == 1);
        assertThat(asked).isEmpty();
        assertThat(workspace.jobs()).filteredOn(job -> job.type() == JobType.CONVERSION
                && "analysis".equals(job.parameter(ExportConversions.FORMAT))).extracting(JobStatus::sourceId)
                .containsExactly("anhang");
    }

    /** Zwei Quellen „handbuch“ und „anhang“ aus je einem Ordner mit einer Seite; Dateiquellen haben je einen Space. */
    private Path startTwo() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n\nText.\n");
        Path more = Files.createDirectories(home.resolve("anhang"));
        Files.writeString(more.resolve("anhang.md"), "# Anhang\n\nMehr Text.\n");
        Path out = home.resolve("Ausgabe");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                  output-directory: "%s"
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                    - id: anhang
                      type: files
                      title: Anhang
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), out, files, more));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        // Die App prüft beim Start keine Verbindung mehr (Angelo 24.11.2026: nichts ohne Klick); hier wie ein Klick auf
        // „Verbindung prüfen …“ an jeder Karte.
        SwingUtilities.invokeAndWait(() -> context.runtime().workspace().sources()
                .forEach(source -> context.checkConnection(source.id(), () -> { })));
        await(() -> context.connection("handbuch").state() == AppContext.Connection.State.CONNECTED
                && context.connection("anhang").state() == AppContext.Connection.State.CONNECTED);
        return out;
    }

    /** Die Texte der Werte in einer Faktenliste, Zeile für Zeile (Beschriftungen zuerst). */
    private static List<String> texts(java.awt.Container container) {
        List<String> texts = new java.util.ArrayList<>();
        for (java.awt.Component component : container.getComponents()) {
            if (!component.isVisible()) {
                continue;
            }
            if (component instanceof javax.swing.JLabel label) {
                texts.add(label.getText());
            } else if (component instanceof javax.swing.text.JTextComponent area) {
                texts.add(area.getText());
            } else if (component instanceof java.awt.Container inner) {
                texts.addAll(texts(inner));
            }
        }
        return texts;
    }

    private static List<Path> list(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            return files.toList();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    private static void await(BooleanSupplier condition) throws Exception {
        for (int i = 0; i < 600; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = condition.getAsBoolean());
            if (done[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Bedingung nicht erreicht");
    }
}
