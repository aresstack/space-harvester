package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
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

    @Test
    void theHoldingsLineCountsLocalPagesAndNamesAGapInTheResolution() {
        PageSet pages = PageSet.of(Map.of("wiki", List.of("1", "2", "3")));
        assertThat(ExportPanel.holdingsText(new PageSelection(pages, new HoldingCounts(3, 2, 0, 0), List.of(), true)))
                .isEqualTo("2 von 3 Seiten lokal");
        assertThat(ExportPanel.holdingsText(new PageSelection(pages, new HoldingCounts(3, 3, 0, 0), List.of(), false)))
                .isEqualTo("3 von 3 Seiten lokal · " + ExportPanel.INCOMPLETE);
        assertThat(ExportPanel.holdingsText(new PageSelection(PageSet.EMPTY, HoldingCounts.ZERO, List.of(), true)))
                .isEqualTo("keine Seite bekannt");
    }

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
            panel[0].selection(new ExportPanel.Selection("handbuch", space, null, space));
        });
        await(() -> panel[0].files.getText().equals(ExportPanel.NO_HOLDINGS));
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
            panel[0].selection(new ExportPanel.Selection("handbuch", space, null, space));
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
        SwingUtilities.invokeAndWait(() -> panel[0].selection(new ExportPanel.Selection("handbuch", space, "x",
                "Seite x")));
        await(() -> panel[0].shownDataset() == null);
        SwingUtilities.invokeAndWait(() -> assertThat(panel[0].exportBlocked("pdf")).isNull());
    }

    /** Stand = jüngster fertiger Download genau dieser Auswahl; ein Seiten-Download zählt nicht für den Space. */
    @Test
    void theStandIsTheNewestCompletedDownloadOfExactlyTheSelection() {
        JobStatus older = job("j1", "OPS", null, JobState.COMPLETED, Instant.parse("2026-11-13T18:00:00Z"));
        JobStatus newer = job("j2", "OPS", null, JobState.COMPLETED, Instant.parse("2026-11-13T19:00:00Z"));
        JobStatus page = job("j3", "OPS", "4711", JobState.COMPLETED, Instant.parse("2026-11-13T19:30:00Z"));
        JobStatus other = job("j4", "DEV", null, JobState.COMPLETED, Instant.parse("2026-11-13T19:40:00Z"));
        JobStatus running = job("j5", "OPS", null, JobState.RUNNING, null);
        List<JobStatus> jobs = List.of(older, newer, page, other, running);
        ExportPanel.Selection space = new ExportPanel.Selection("wiki", "OPS", null, "Betrieb");
        ExportPanel.Selection subtree = new ExportPanel.Selection("wiki", "OPS", "4711", "Seite");
        ExportPanel.Selection none = new ExportPanel.Selection("wiki", "QA", null, "QA");

        assertThat(ExportPanel.newest(jobs, space)).isSameAs(newer);
        assertThat(ExportPanel.running(jobs, space)).isSameAs(running);
        assertThat(ExportPanel.newest(jobs, subtree)).isSameAs(page);
        assertThat(ExportPanel.running(jobs, subtree)).isNull();
        assertThat(ExportPanel.newest(jobs, none)).isNull();
        assertThat(ExportPanel.standText(null, null)).isEqualTo(ExportPanel.NOT_LOADED);
        assertThat(ExportPanel.standText(newer, null)).startsWith("Geladen ").contains("· 90 Seiten");
        assertThat(ExportPanel.standText(newer, running)).startsWith("Download ").contains("von 100 Seiten");
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
