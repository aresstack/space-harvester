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
import com.aresstack.spaceharvester.application.jobs.JobProgress;
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

    @Test
    void aFinishedDownloadIsExportedFromItsDatasetIntoTheOutputDirectory() throws Exception {
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
        Workspace workspace = context.runtime().workspace();
        await(() -> context.connection("handbuch").state() == AppContext.Connection.State.CONNECTED);
        String space = workspace.spaces("handbuch").get(0).key();
        String job = workspace.startExport("handbuch", space, List.of(Downloads.FORMAT)).id();

        ExportPanel[] panel = new ExportPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            panel[0] = new ExportPanel(context);
            // Ohne Auswahl nur der Hinweis; die Quelle zeigt ihre Spaces; erst der Space zeigt Stand und Ausgabe.
            assertThat(panel[0].exportBlocked("pdf")).isEqualTo("kein Datenbestand vorhanden");
            panel[0].selection(new ExportPanel.Selection("handbuch", space, null, space));
        });
        // Sobald der Download fertig ist, hängt der Datenbestand an der Auswahl (Auftragsmeldung, kein Zeitgeber).
        await(() -> panel[0].shownDataset() != null && panel[0].shownDataset().exportId().equals(job));
        await(() -> panel[0].exportBlocked("markdown-package") == null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(panel[0].exportBlocked("pdf")).isNull();
            assertThat(panel[0].files.getText()).startsWith("Noch nichts exportiert");
            panel[0].exportAs(job, "markdown-package");
            assertThat(panel[0].exportBlocked("pdf")).as("ein Export je Download zugleich")
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
        // Eine Seite derselben Quelle hat keinen eigenen Download: „Noch nicht geladen“, der Datenbestand des Space
        // gilt nicht für sie.
        SwingUtilities.invokeAndWait(() -> panel[0].selection(new ExportPanel.Selection("handbuch", space, "x",
                "Seite x")));
        await(() -> panel[0].shownDataset() == null);
        SwingUtilities.invokeAndWait(() ->
                assertThat(panel[0].exportBlocked("pdf")).isEqualTo("kein Datenbestand vorhanden"));
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
