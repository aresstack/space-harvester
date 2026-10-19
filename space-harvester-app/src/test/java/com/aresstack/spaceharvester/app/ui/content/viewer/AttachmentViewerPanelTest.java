package com.aresstack.spaceharvester.app.ui.content.viewer;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Anhang-Viewer (#183) wie im Enterprise AI Client ({@code ReaderAttachmentsTest}, {@code PdfPagesTest}), mit
 * einem Lader statt der Quelle: PDF mit Seiten, Blättern, Zoom, Speichern und Öffnen; Bild, Text und Office über die
 * Vorschau der Quelle; ein unbekannter Typ zeigt Name, Typ, Größe und „keine Vorschau“. Dateinamen erfunden.
 */
class AttachmentViewerPanelTest {

    @TempDir
    Path temp;

    @Test
    void aPdfShowsItsPagesWithZoomSaveAndOpen() throws Exception {
        byte[] pdf = pdf(3);
        FakeLoader loader = new FakeLoader().file("att-pdf", pdf);
        List<File> opened = new ArrayList<>();
        File target = temp.resolve("kopie.pdf").toFile();
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("att-pdf", "Ablauf Beispiel.pdf", "application/pdf", 3100), loader,
                    opened::add, Runnable::run);
            viewer.setSize(700, 460);
            viewer.doLayout();
            assertThat(viewer.fileInfoText()).isEqualTo("PDF · 3 KB");
            assertThat(viewer.pageCount()).isEqualTo(3);
            assertThat(viewer.showsPicture()).isTrue();
            assertThat(viewer.infoText()).startsWith("Seite 1 / 3");
            assertThat(viewer.hasPrevious()).isFalse();
            viewer.next();
            viewer.next();
            assertThat(viewer.page()).isEqualTo(2);
            assertThat(viewer.infoText()).startsWith("Seite 3 / 3");
            assertThat(viewer.hasNext()).isFalse();
            viewer.previous();
            assertThat(viewer.page()).isEqualTo(1);

            int width = viewer.pictureWidth();
            int zoom = viewer.zoomPercent();
            viewer.zoomIn();
            assertThat(viewer.zoomPercent()).isGreaterThan(zoom);
            viewer.sharpenNow();
            assertThat(viewer.renderScale()).isGreaterThan(AttachmentViewerPanel.PDF_BASE_SCALE);
            assertThat(viewer.pictureWidth()).isGreaterThan(width);

            assertThat(viewer.saveTo(target)).isEqualTo(AttachmentViewerPanel.FILE_SAVED_PREFIX + "kopie.pdf");
            assertThat(viewer.openOriginal()).isNull();
            viewer.close();
        });
        assertThat(Files.readAllBytes(target.toPath())).isEqualTo(pdf);
        assertThat(opened).singleElement().satisfies(file -> {
            assertThat(file.getName()).isEqualTo("Ablauf Beispiel.pdf");
            assertThat(Files.readAllBytes(file.toPath())).isEqualTo(pdf);
        });
        assertThat(loader.loads).containsEntry("att-pdf", 1);
    }

    @Test
    void anImageAndATextShowTheirContent() throws Exception {
        byte[] text = "Zeile 1\nÄnderung 2\n".getBytes(StandardCharsets.UTF_8);
        FakeLoader loader = new FakeLoader().file("att-png", png()).file("att-txt", text);
        run(() -> {
            AttachmentViewerPanel image = new AttachmentViewerPanel(
                    new ReaderAttachment("att-png", "Architektur.png", "image/png", 200), loader, null, Runnable::run);
            assertThat(image.showsPicture()).isTrue();
            assertThat(image.isShowingText()).isFalse();

            AttachmentViewerPanel notes = new AttachmentViewerPanel(
                    new ReaderAttachment("att-txt", "notizen.txt", "text/plain;charset=UTF-8", text.length), loader,
                    null, Runnable::run);
            assertThat(notes.isShowingText()).isTrue();
            assertThat(notes.textContent()).isEqualTo("Zeile 1\nÄnderung 2\n");
        });
    }

    @Test
    void anUnknownTypeShowsNameTypeSizeAndNoPreviewWithoutLoading() throws Exception {
        byte[] bin = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12};
        FakeLoader loader = new FakeLoader().file("att-bin", bin);
        List<File> opened = new ArrayList<>();
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("att-bin", "daten.bin", "application/x-unbekannt", bin.length), loader,
                    opened::add, Runnable::run);
            assertThat(viewer.attachment().kind()).isEqualTo(ReaderAttachmentKind.OTHER);
            assertThat(viewer.statusText()).contains("daten.bin").contains("BIN · 12 B")
                    .contains(AttachmentViewerPanel.NO_PREVIEW_TEXT);
            assertThat(viewer.showsPicture()).isFalse();
            assertThat(loader.loads).isEmpty();
            assertThat(viewer.saveButton().isEnabled()).isTrue();
            viewer.openButton().doClick();
        });
        assertThat(opened).singleElement().satisfies(file -> assertThat(file.getName()).isEqualTo("daten.bin"));
    }

    @Test
    void anOfficeFileUsesThePreviewOfTheSourceOrSaysWhyThereIsNone() throws Exception {
        FakeLoader withPreview = new FakeLoader().preview("att-xlsx", png());
        run(() -> {
            AttachmentViewerPanel office = new AttachmentViewerPanel(new ReaderAttachment("att-xlsx", "Planung.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", 84000), withPreview, null,
                    Runnable::run);
            assertThat(office.fileInfoText()).isEqualTo("Excel · 82 KB");
            assertThat(office.showsPicture()).isTrue();
            assertThat(withPreview.loads).as("Vorschau ohne Original").isEmpty();
            assertThat(withPreview.previews).containsExactly("att-xlsx");

            // ohne Vorschauweg der Quelle (Standard des Laders): Hinweis, Speichern und Öffnen bleiben
            AttachmentViewerPanel none = new AttachmentViewerPanel(new ReaderAttachment("att-doc", "Brief.docx",
                    "", -1), new FakeLoader().withoutPreviews(), null, Runnable::run);
            assertThat(none.statusText()).contains(AttachmentViewerPanel.NO_PREVIEW_TEXT)
                    .contains("Die Quelle bietet keine Vorschau an.");
        });
    }

    @Test
    void aFailedLoadShowsTheReason() throws Exception {
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("fehlt", "fehlt.pdf", "application/pdf", -1), new FakeLoader(), null,
                    Runnable::run);
            assertThat(viewer.statusText()).contains("Anhang konnte nicht geladen werden").contains("nicht gefunden");
            assertThat(viewer.saveTo(temp.resolve("x").toFile())).isEqualTo(AttachmentViewerPanel.SAVE_FAILED_PREFIX
                    + AttachmentViewerPanel.NOT_LOADED);
        });
    }

    @Test
    void kindsSizesAndLabelsReadLikeAnEmailAttachmentList() {
        assertThat(ReaderAttachment.size(12)).isEqualTo("12 B");
        assertThat(ReaderAttachment.size(469680)).isEqualTo("459 KB");
        assertThat(ReaderAttachment.size(1572864)).isEqualTo("1,5 MB");
        assertThat(ReaderAttachment.size(-1)).isEmpty();
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "a.PDF")).isEqualTo(ReaderAttachmentKind.PDF);
        assertThat(ReaderAttachmentKind.of("image/svg+xml", "a.svg")).isEqualTo(ReaderAttachmentKind.IMAGE);
        assertThat(ReaderAttachmentKind.of("image/png", null)).isEqualTo(ReaderAttachmentKind.IMAGE);
        assertThat(ReaderAttachmentKind.of("application/json", "a")).isEqualTo(ReaderAttachmentKind.TEXT);
        assertThat(ReaderAttachmentKind.of(null, "build.gradle")).isEqualTo(ReaderAttachmentKind.TEXT);
        assertThat(ReaderAttachmentKind.of(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "a.docx"))
                .isEqualTo(ReaderAttachmentKind.OFFICE);
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "Folien.pptx"))
                .isEqualTo(ReaderAttachmentKind.OFFICE);
        assertThat(ReaderAttachmentKind.of("application/vnd.jgraph.mxfile", "a")).isEqualTo(ReaderAttachmentKind.OTHER);
        assertThat(new ReaderAttachment("k", "Brief.docx", "", -1).summary()).isEqualTo("Word");
        assertThat(AttachmentViewerPanel.safeFileName("a/b.txt")).isEqualTo("a_b.txt");
        assertThat(AttachmentViewerPanel.decodeText(new byte[] {(byte) 0xC4, 13, 10}, "text/plain")).isEqualTo("Ä\r\n");
    }

    @Test
    void pdfPagesNameAPasswordAndStayWithinThePixelLimit() throws Exception {
        try {
            PdfPages.open("kein PDF".getBytes(StandardCharsets.US_ASCII)).close();
            throw new AssertionError("kein PDF");
        } catch (IOException expected) {
            // lesbarer Grund kommt von PDFBox
        }
        try {
            PdfPages.open(protectedPdf()).close();
            throw new AssertionError("Passwort verlangt");
        } catch (IOException e) {
            assertThat(e.getMessage()).isEqualTo("Das PDF ist mit einem Passwort geschützt");
        }
        try (PdfPages pages = PdfPages.open(pdf(1))) {
            assertThat(pages.pageCount()).isEqualTo(1);
            BufferedImage huge = pages.render(0, 1000);
            assertThat((double) huge.getWidth() * huge.getHeight()).isLessThanOrEqualTo(PdfPages.MAX_PIXELS * 1.001);
            assertThat(pages.render(0, 0.0001).getWidth())
                    .isCloseTo((int) Math.round(PDRectangle.A4.getWidth() * 0.1), org.assertj.core.data.Offset.offset(1));
        }
    }

    // ------------------------------------------------------------------ Aufbau

    /** Lädt Originale und Vorschauen aus Tabellen, synchron auf dem EDT; zählt die Abrufe. */
    private static final class FakeLoader implements ReaderAttachmentLoader {
        private final Map<String, byte[]> files = new HashMap<>();
        private final Map<String, byte[]> previewFiles = new HashMap<>();
        final Map<String, Integer> loads = new HashMap<>();
        final List<String> previews = new ArrayList<>();
        private boolean previewsOffered = true;

        FakeLoader file(String key, byte[] bytes) {
            files.put(key, bytes);
            return this;
        }

        FakeLoader preview(String key, byte[] bytes) {
            previewFiles.put(key, bytes);
            return this;
        }

        FakeLoader withoutPreviews() {
            previewsOffered = false;
            return this;
        }

        @Override
        public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
            loads.merge(attachment.key(), 1, Integer::sum);
            byte[] bytes = files.get(attachment.key());
            if (bytes == null) {
                failed.accept("Nicht gefunden: nicht gefunden");
            } else {
                done.accept(bytes);
            }
        }

        @Override
        public void preview(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
            if (!previewsOffered) {
                ReaderAttachmentLoader.super.preview(attachment, done, failed);
                return;
            }
            previews.add(attachment.key());
            byte[] bytes = previewFiles.get(attachment.key());
            if (bytes == null) {
                failed.accept("keine Umwandlung");
            } else {
                done.accept(bytes);
            }
        }
    }

    static byte[] pdf(int pages) throws IOException {
        try (PDDocument document = new PDDocument()) {
            for (int i = 1; i <= pages; i++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 24);
                    content.newLineAtOffset(72, 700);
                    content.showText("Seite " + i);
                    content.endText();
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    private static byte[] protectedPdf() throws IOException {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(PDRectangle.A4));
            StandardProtectionPolicy policy = new StandardProtectionPolicy("besitzer", "nutzer",
                    new AccessPermission());
            policy.setEncryptionKeyLength(128);
            document.protect(policy);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            document.save(out);
            return out.toByteArray();
        }
    }

    static byte[] png() {
        try {
            BufferedImage image = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void run(ThrowingRunnable body) throws Exception {
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    body.run();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
        } catch (InvocationTargetException ex) {
            if (ex.getCause() instanceof Error error) {
                throw error;
            }
            if (ex.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw ex;
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
