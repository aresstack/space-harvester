package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
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
 * Die Karte „Exportieren als …“ unter Aufträge: Ein fertiger Download bietet seine Formate an, „Exportieren“ legt die
 * Datei aus dem vorhandenen Datenbestand direkt im Ausgabeordner ab, ohne Dateidialog und ohne technische Dateien.
 */
class ExportCardTest {

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

        ActivitiesView[] view = new ActivitiesView[1];
        SwingUtilities.invokeAndWait(() -> {
            view[0] = new ActivitiesView(context);
            view[0].show(job);
        });
        // Ein Klick auf das Format-Symbol der Zeile exportiert sofort; ohne Formatwahl und ohne Knopf.
        await(() -> view[0].exportBlocked(job, "markdown") == null);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(view[0].exportBlocked(job, "pdf")).isNull();
            view[0].exportAs(job, "markdown");
            assertThat(view[0].exportBlocked(job, "pdf")).as("ein Export je Download zugleich")
                    .contains("wird gerade erzeugt");
        });
        await(() -> Files.isDirectory(out) && list(out).stream().anyMatch(p -> p.toString().endsWith(".md")));
        // Nur die gewünschte Datei, keine technischen Dateien wie pages.json oder tree.json.
        assertThat(list(out)).singleElement().satisfies(p ->
                assertThat(p.getFileName().toString()).startsWith(space).endsWith(".md"));
        // Die Kachel „Exportieren“ listet die erzeugte Datei.
        await(() -> view[0].files.getText().contains(".md"));
        await(() -> view[0].exportBlocked(job, "pdf") == null);
        // Das JSON-Symbol erzeugt das Paket mit Medien als ZIP.
        assertThat(ActivitiesView.FORMATS).contains("json-package").doesNotContain("json");
        SwingUtilities.invokeAndWait(() -> view[0].exportAs(job, "json-package"));
        await(() -> list(out).stream().anyMatch(p -> p.toString().endsWith(".zip")));
        // „Analyse“ (#359) erzeugt die Analysedaten als eigene JSON-Datei, ohne die übrigen Dateien zu berühren.
        await(() -> view[0].exportBlocked(job, "analysis") == null);
        SwingUtilities.invokeAndWait(() -> view[0].exportAs(job, "analysis"));
        await(() -> list(out).stream().anyMatch(p -> p.toString().endsWith(".json")));
        assertThat(list(out)).hasSize(3);
    }

    @Test
    void analysisIsExactlyOneMoreRowButtonAfterTheFormats() {
        assertThat(ActivitiesView.FORMATS).containsExactly("pdf", "docx", "markdown", "json-package", "analysis");
        assertThat(ActivitiesView.Format.title("analysis")).isEqualTo("Analyse");
    }

    @Test
    void formatsAppearInTheOrderOfTheSketchWithReadableNames() {
        assertThat(ActivitiesView.Format.ordered(List.of("docx", "json", "markdown-package", "markdown", "pdf",
                        "epub")))
                .extracting(ActivitiesView.Format::title)
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

        assertThat(ActivitiesView.notices(file)).isEqualTo("""
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
