package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import java.io.ByteArrayOutputStream;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.source.LocalFilesTextProjector;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OriginalContentViewerTest {

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
    void localPdfUsesTheSharedOriginalViewerAndRetainsTextAfterReopeningFromCache() throws Exception {
        Path directory = Files.createDirectories(home.resolve("documents"));
        try (PDDocument document = new PDDocument()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 700);
                content.showText("Lokaler PDF Beleg");
                content.endText();
            }
            document.save(directory.resolve("Beleg.pdf").toFile());
        }
        Files.writeString(directory.resolve("Tabelle.csv"), "name,value\nNord,12\n");
        try (XWPFDocument document = new XWPFDocument();
                java.io.OutputStream output = Files.newOutputStream(directory.resolve("Bericht.docx"))) {
            document.createParagraph().createRun().setText("Lokaler Word Beleg");
            document.write(output);
        }
        PageDetailPanel panel = panel("""
                space-harvester:
                  sources:
                    - id: docs
                      type: files
                      options:
                        directory: %s
                """.formatted(directory.toString().replace('\\', '/')));
        List<HarvestSource.PageNode> nodes = context.runtime().workspace().children("docs", "docs", null, null).pages();
        String id = nodes.stream().filter(node -> node.title().equals("Beleg.pdf")).findFirst().orElseThrow().pageId();
        String csvId = nodes.stream().filter(node -> node.title().equals("Tabelle.csv")).findFirst().orElseThrow().pageId();
        String wordId = nodes.stream().filter(node -> node.title().equals("Bericht.docx")).findFirst().orElseThrow().pageId();
        onEdt(() -> {
            panel.tabs().select("Leseansicht");
            panel.load("docs", id, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().pageCount() == 1
                && panel.originalViewer().showsPicture()));
        PageSnapshot snapshot = context.runtime().workspace().cachedPage("docs", id).orElseThrow().snapshot();
        assertThat(snapshot.metadata().attributes()).containsEntry(PageMetadata.RESOURCE_KIND, PageMetadata.FILE_KIND);
        assertThat(new LocalFilesTextProjector().project(snapshot).orElseThrow().text()).contains("Lokaler PDF Beleg");
        onEdt(() -> {
            panel.load("docs", csvId, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().isShowingSpreadsheet()));
        onEdt(() -> {
            panel.load("docs", wordId, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().documentImages() >= 0));
        assertThat(onEdt(() -> panel.originalViewer().textContent())).contains("Lokaler Word Beleg");
        onEdt(() -> {
            panel.load("docs", id, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().pageCount() == 1
                && panel.originalViewer().showsPicture()));
    }

    @Test
    void localReferenceShowsTheOriginalInsteadOfTheExtractedPreview() throws Exception {
        Path directory = Files.createDirectories(home.resolve("documents"));
        Files.writeString(directory.resolve("Original.txt"), "Original text distinct from the preview");
        PageDetailPanel panel = panel("""
                space-harvester:
                  sources:
                    - id: docs
                      type: files
                      options:
                        directory: %s
                """.formatted(directory.toString().replace('\\', '/')));
        String id = context.runtime().workspace().children("docs", "docs", null, null).pages().stream()
                .filter(node -> node.title().equals("Original.txt")).findFirst().orElseThrow().pageId();
        onEdt(() -> {
            panel.tabs().select("Leseansicht");
            panel.load("docs", id, false);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().isShowingText()));
        PageMetadata metadata = context.runtime().workspace().cachedPage("docs", id).orElseThrow().snapshot().metadata();
        AttachmentViewerPanel previous = onEdt(panel::originalViewer);
        NativePayload reference = new com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference(
                null, "text/plain", null,
                com.aresstack.spaceharvester.application.assets.ExtractionStatus.OK, null,
                "Short preview", true).payload();
        onEdt(() -> {
            panel.showOriginalContent(metadata, reference);
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer() != previous
                && panel.originalViewer().isShowingText()));
        assertThat(onEdt(() -> panel.originalViewer().textContent()))
                .isEqualTo("Original text distinct from the preview");
    }

    @Test
    void unsupportedLocalReferenceExplainsStorageInsteadOfClaimingTheFileIsTooLarge() throws Exception {
        PageDetailPanel panel = panel();
        onEdt(() -> {
            panel.showOriginalContent(metadata("projekt.bin", PageMetadata.FILE_KIND),
                    com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference.unsupported().payload());
            return null;
        });
        assertThat(onEdt(() -> panel.originalViewer().statusText())).contains("Original verbleibt im lokalen Ordner")
                .doesNotContain("zu groß für den Cache");
    }

    @Test
    void cachedOriginalWithoutResourceKindStillUsesTheSharedViewer() throws Exception {
        PageDetailPanel panel = panel();
        PageMetadata legacy = new PageMetadata("node-id", "source:node-id", "Tabelle.csv", "space", null,
                "1", Instant.now(), null, PageMetadata.sorted(Map.of()));
        onEdt(() -> {
            panel.showOriginalContent(legacy, new NativePayload("name,value\nNord,12\n".getBytes(StandardCharsets.UTF_8),
                    "text/csv", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null && panel.originalViewer().isShowingSpreadsheet()));
    }

    @Test
    void originalTabShowsSourceFileBytesAndKeepsTextAsSeparateTab() throws Exception {
        PageDetailPanel panel = panel();
        byte[] original = "Originalinhalt mit Formatierung".getBytes(StandardCharsets.UTF_8);

        onEdt(() -> {
            panel.showOriginalContent(metadata("bericht.txt", PageMetadata.FILE_KIND),
                    new NativePayload(original, "text/plain", "file"));
            return null;
        });

        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingText()));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(viewer::textContent)).isEqualTo("Originalinhalt mit Formatierung");
        onEdt(() -> {
            panel.tabs().select("Podcast-Ansicht");
            assertThat(panel.selectedTab()).isEqualTo("Podcast-Ansicht");
            assertThat(panel.footTools().getComponentCount()).isZero();
            panel.tabs().select("Leseansicht");
            assertThat(panel.originalViewer()).isSameAs(viewer);
            // Speichern, Öffnen und „Typ · Größe“ stehen in der Leiste mit der Lupe, nicht mehr in der Vorschau.
            assertThat(panel.footTools().getComponent(0)).isSameAs(viewer.footer());
            assertThat(viewer.footer().getParent()).isSameAs(panel.footTools());
            return null;
        });
    }

    @Test
    void unsupportedFilesRemainDownloadableAndFoldersUseTheNormalReader() throws Exception {
        PageDetailPanel panel = panel();
        onEdt(() -> {
            panel.showOriginalContent(metadata("projekt.bin", PageMetadata.FILE_KIND),
                    new NativePayload(new byte[] {1, 2, 3}, "application/octet-stream", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().statusText().contains(
                        AttachmentViewerPanel.NO_PREVIEW_TEXT)));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(viewer::fileInfoText)).contains("BIN", "3 B");
        assertThat(onEdt(viewer::isShowingText)).isFalse();
        onEdt(() -> {
            panel.showOriginalContent(metadata("Projektordner", PageMetadata.FOLDER_KIND),
                    new NativePayload(new byte[0], "text/plain", "bscw-folder"));
            assertThat(panel.originalViewer()).isNull();
            return null;
        });
    }

    @Test
    void rendersPngEvenWhenWebDavReportsAnIncorrectMediaType() throws Exception {
        PageDetailPanel panel = panel();
        byte[] original = png();
        onEdt(() -> {
            panel.showOriginalContent(metadata("Diagramm", PageMetadata.FILE_KIND),
                    new NativePayload(original, "application/octet-stream", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().showsPicture()));
        AttachmentViewerPanel viewer = onEdt(panel::originalViewer);
        assertThat(onEdt(() -> viewer.attachment().kind().name())).isEqualTo("IMAGE");
        assertThat(onEdt(viewer::fileInfoText)).contains("PNG");
    }

    @Test
    void rendersCsvAsSpreadsheetAndKeepsBinaryPayloadOutOfTechnicalText() throws Exception {
        PageDetailPanel panel = panel();
        String csv = "name,value\nNorth,12\nSouth,15\n";
        onEdt(() -> {
            panel.showOriginalContent(metadata("werte.csv", PageMetadata.FILE_KIND),
                    new NativePayload(csv.getBytes(StandardCharsets.UTF_8), "text/csv", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().isShowingSpreadsheet()));
        assertThat(onEdt(() -> panel.originalViewer().attachment().kind().name()))
                .isEqualTo("SPREADSHEET");
        assertThat(onEdt(() -> panel.originalViewer().isShowingText())).isFalse();

        byte[] binary = png();
        NativePayload payload = new NativePayload(binary, "image/png", "file");
        assertThat(Payloads.display(payload)).contains("Binärinhalt", "image/png")
                .doesNotContain("\uFFFD");
        assertThat(Payloads.isText(payload)).isFalse();
    }

    @Test
    void imageViewerExplainsWhenTheSourceReturnedHtmlInsteadOfImageBytes() throws Exception {
        PageDetailPanel panel = panel();
        byte[] loginPage = "<html><body>Sign in</body></html>".getBytes(StandardCharsets.UTF_8);
        onEdt(() -> {
            panel.showOriginalContent(metadata("zeichnung.png", PageMetadata.FILE_KIND),
                    new NativePayload(loginPage, "image/png", "file"));
            return null;
        });
        await(() -> onEdt(() -> panel.originalViewer() != null
                && panel.originalViewer().statusText().contains("HTML statt Bilddaten")));
        assertThat(onEdt(() -> panel.originalViewer().statusText()))
                .contains("Anmeldung oder Proxy-Antwort")
                .doesNotContain("Sign in");
    }

    private static byte[] png() throws Exception {
        BufferedImage image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        image.setRGB(1, 2, 0x13579B);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(image, "png", output)).isTrue();
        return output.toByteArray();
    }

    private PageDetailPanel panel() throws Exception {
        return panel("");
    }

    private PageDetailPanel panel(String sources) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n" + sources);
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        return onEdt(() -> new PageDetailPanel(context));
    }

    private static PageMetadata metadata(String title, String kind) {
        return new PageMetadata("node-id", "source:node-id", title, "space", "parent-id",
                "1", Instant.now(), null,
                PageMetadata.sorted(Map.of(PageMetadata.RESOURCE_KIND, kind)));
    }

    private static void await(Check condition) throws Exception {
        for (int attempt = 0; attempt < 150; attempt++) {
            if (condition.holds()) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Reader did not reach the expected state");
    }

    private static <T> T onEdt(Work<T> action) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.run());
            } catch (Exception error) {
                failure.set(error);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }

    @FunctionalInterface
    private interface Work<T> {
        T run() throws Exception;
    }

    @FunctionalInterface
    private interface Check {
        boolean holds() throws Exception;
    }
}
