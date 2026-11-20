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

    /**
     * Angelo 20.11.2026: Über der Größengrenze zeigt der Viewer statt der Vorschau den Grund, und „Öffnen“ lädt die
     * Datei direkt auf die Platte und öffnet sie im Standardprogramm, ohne sie in den Speicher zu laden.
     */
    @Test
    void withoutPreviewTheViewerStillOpensTheFileInAnotherProgram() throws Exception {
        byte[] content = "großes Original".getBytes(StandardCharsets.UTF_8);
        List<File> opened = new ArrayList<>();
        List<String> loaded = new ArrayList<>();
        ReaderAttachmentLoader loader = new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment attachment, java.util.function.Consumer<byte[]> done,
                             java.util.function.Consumer<String> failed) {
                loaded.add(attachment.fileName());
                failed.accept("zu groß");
            }

            @Override
            public boolean downloadsToFile() {
                return true;
            }

            @Override
            public void downloadTo(ReaderAttachment attachment, java.nio.file.Path target, Runnable done,
                                   java.util.function.Consumer<String> failed) {
                try {
                    Files.write(target, content);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
                done.run();
            }
        };
        run(() -> {
            AttachmentViewerPanel viewer = AttachmentViewerPanel.withoutPreview(
                    new ReaderAttachment("fig", "Backup.fig", "application/octet-stream", 40_268_060L), loader,
                    opened::add, Runnable::run, "Keine Vorschau: zu groß für den Cache");
            assertThat(viewer.showsPicture()).isFalse();
            assertThat(viewer.statusText()).contains("Backup.fig", "zu groß für den Cache");
            viewer.openButtonForTest().doClick();
            assertThat(loaded).as("nie in den Speicher").isEmpty();
            viewer.close();
        });
        assertThat(opened).singleElement().satisfies(file -> {
            assertThat(file.getName()).isEqualTo("Backup.fig");
            assertThat(Files.readAllBytes(file.toPath())).isEqualTo(content);
        });
    }

    /** Angelo 20.11.2026: Zoom und Ausschnitt bleiben beim Blättern und beim Wechsel aufs nächste Bild. */
    @Test
    void zoomAndSectionStayWhenTurningPagesAndSwitchingImages() throws Exception {
        FakeLoader loader = new FakeLoader().file("att-pdf", pdf(2)).file("a", png()).file("b", png());
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("att-pdf", "Folien.pdf", "application/pdf", 3100), loader, null,
                    Runnable::run);
            viewer.setSize(700, 460);
            viewer.doLayout();
            assertThat(viewer.view()).as("eingepasst").isNull();
            viewer.zoomIn();
            viewer.zoomIn();
            viewer.sharpenNow();
            AttachmentViewerPanel.View zoomed = viewer.view();
            int percent = viewer.zoomPercent();
            viewer.next();
            assertThat(viewer.page()).isEqualTo(1);
            assertThat(viewer.view()).as("Zoom und Ausschnitt wie auf Seite 1").isEqualTo(zoomed);
            viewer.close();

            // Bilder: der Nachbar übernimmt Zoom und Ausschnitt des vorigen Viewers.
            AttachmentViewerPanel[] shown = new AttachmentViewerPanel[1];
            shown[0] = new AttachmentViewerPanel(new ReaderAttachment("a", "a.png", "image/png", 200), loader, null,
                    Runnable::run);
            shown[0].setSize(700, 460);
            shown[0].doLayout();
            shown[0].neighbours(new AttachmentViewerPanel.Neighbours() {
                @Override
                public boolean hasPrevious() {
                    return false;
                }

                @Override
                public boolean hasNext() {
                    return true;
                }

                @Override
                public void previous() {
                }

                @Override
                public void next() {
                    shown[0] = new AttachmentViewerPanel(new ReaderAttachment("b", "b.png", "image/png", 200),
                            loader, null, Runnable::run);
                }
            });
            shown[0].zoomIn();
            AttachmentViewerPanel.View image = shown[0].view();
            shown[0].next();
            assertThat(shown[0].attachment().fileName()).isEqualTo("b.png");
            assertThat(shown[0].view()).isEqualTo(image);
            assertThat(percent).isPositive();
        });
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
    void takingTheViewerOutAndBackInKeepsItAliveUntilClosed() throws Exception {
        FakeLoader loader = new FakeLoader().file("att-pdf", pdf(2));
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("att-pdf", "Ablauf.pdf", "application/pdf", 3100), loader, null,
                    Runnable::run);
            // wie ContentView.arrange() beim Umschalten von Baum und Liste: aus der Hierarchie genommen (headless
            // ruft Swing removeNotify ohne Fenster nicht selbst)
            viewer.removeNotify();
            viewer.next();
            assertThat(viewer.page()).as("Blättern geht weiter").isEqualTo(1);
            assertThat(viewer.infoText()).startsWith("Seite 2 / 2");
            viewer.close();
        });
    }

    @Test
    void aVeryLongTextIsOnlyDecodedAsFarAsTheViewShowsIt() {
        byte[] text = new byte[AttachmentViewerPanel.MAX_TEXT_CHARS * 4 + 10];
        java.util.Arrays.fill(text, (byte) 'a');
        text[AttachmentViewerPanel.MAX_TEXT_CHARS * 4 - 1] = (byte) 0xC3; // „ä“ genau an der Schnittstelle
        text[AttachmentViewerPanel.MAX_TEXT_CHARS * 4] = (byte) 0xA4;
        String shown = AttachmentViewerPanel.decodeText(text, null);
        assertThat(shown).startsWith("aaa").contains("[… gekürzt").doesNotContain("\uFFFD").doesNotContain("Ã");
    }

    @Test
    void aCutAfterAMultibyteCharacterKeepsUtf8() {
        int n = AttachmentViewerPanel.MAX_TEXT_CHARS * 4;
        byte[] text = new byte[n + 10];
        java.util.Arrays.fill(text, (byte) 'a');
        text[0] = (byte) 0xC3; // „ä“ am Anfang: kippte die Erkennung, stünde dort „Ã¤“
        text[1] = (byte) 0xA4;
        // „ä“ endet vor einer ungeraden Schnittstelle, „€“ (3 Bytes) liegt auf ihr
        text[n - 3] = (byte) 0xC3;
        text[n - 2] = (byte) 0xA4;
        text[n - 1] = (byte) 0xE2;
        text[n] = (byte) 0x82;
        text[n + 1] = (byte) 0xAC;
        String shown = AttachmentViewerPanel.decodeText(text, null);
        assertThat(shown).startsWith("äaa").doesNotContain("Ã").contains("[… gekürzt");
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
            // Symbolknöpfe ohne Text (Angelo, 19.11.2026); die Leiste lässt sich in die Werkzeugleiste mit der Lupe
            // umsetzen und füllt dort die Zeile: Knöpfe links, „Typ · Größe“ rechts.
            assertThat(viewer.saveButton().getText()).isNullOrEmpty();
            assertThat(viewer.openButton().getText()).isNullOrEmpty();
            assertThat(viewer.footer().getParent()).isSameAs(viewer);
            assertThat(viewer.detachFooter().getParent()).isNull();
            assertThat(viewer.footer().getClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL))
                    .isEqualTo(Boolean.TRUE);
            assertThat(((java.awt.BorderLayout) viewer.footer().getLayout())
                    .getLayoutComponent(java.awt.BorderLayout.EAST)).isInstanceOfSatisfying(javax.swing.JLabel.class,
                            label -> assertThat(label.getText()).isEqualTo(viewer.fileInfoText()));
            viewer.openButton().doClick();
        });
        assertThat(opened).singleElement().satisfies(file -> assertThat(file.getName()).isEqualTo("daten.bin"));
    }

    @Test
    void wordDocumentDisplaysItsTextWithoutRequiringAWebDavPreview() throws Exception {
        byte[] original;
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("HaVdB Architekturbericht");
            word.write(output);
            original = output.toByteArray();
        }
        FakeLoader loader = new FakeLoader().file("docx", original).withoutPreviews();
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("docx", "Bericht.docx", "application/octet-stream", original.length),
                    loader, null, Runnable::run);
            assertThat(viewer.isShowingText()).isTrue();
            assertThat(viewer.textContent()).contains("HaVdB Architekturbericht");
            assertThat(loader.loads).containsEntry("docx", 1);
            assertThat(loader.previews).isEmpty();
            viewer.close();
        });
    }

    @Test
    void excelShowsLocalSpreadsheetInsteadOfRequiringAnOnlinePreview() throws Exception {
        byte[] bytes;
        try (org.apache.poi.xssf.usermodel.XSSFWorkbook workbook =
                     new org.apache.poi.xssf.usermodel.XSSFWorkbook();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            workbook.createSheet("Plan").createRow(0).createCell(0).setCellValue("HaVdB");
            workbook.write(output);
            bytes = output.toByteArray();
        }
        FakeLoader loader = new FakeLoader().file("att-xlsx", bytes).withoutPreviews();
        run(() -> {
            AttachmentViewerPanel office = new AttachmentViewerPanel(new ReaderAttachment("att-xlsx", "Planung.xlsx",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes.length), loader, null,
                    Runnable::run);
            assertThat(office.fileInfoText()).startsWith("Excel");
            assertThat(office.isShowingSpreadsheet()).isTrue();
            assertThat(loader.loads).containsEntry("att-xlsx", 1);
            assertThat(loader.previews).isEmpty();
            office.close();
        });
    }

    @Test
    void csvWithExcelMimeDisplaysARealTable() throws Exception {
        byte[] bytes = "Name;Status\nHaVdB;Offen".getBytes(StandardCharsets.UTF_8);
        FakeLoader loader = new FakeLoader().file("csv", bytes).withoutPreviews();
        run(() -> {
            AttachmentViewerPanel csv = new AttachmentViewerPanel(new ReaderAttachment("csv", "Plan.csv",
                    "application/vnd.ms-excel", bytes.length), loader, null, Runnable::run);
            assertThat(csv.isShowingSpreadsheet()).isTrue();
            assertThat(loader.loads).containsEntry("csv", 1);
            assertThat(loader.previews).isEmpty();
            csv.close();
        });
    }

    @Test
    void wordWithoutFilenameExtensionUsesWordMimeForLocalPreview() throws Exception {
        byte[] original;
        try (org.apache.poi.xwpf.usermodel.XWPFDocument word = new org.apache.poi.xwpf.usermodel.XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("Architektur Steckbrief");
            word.write(output);
            original = output.toByteArray();
        }
        FakeLoader loader = new FakeLoader().file("word", original).withoutPreviews();
        run(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(new ReaderAttachment(
                    "word", "Entwurf Steckbrief AG Architektur",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", original.length),
                    loader, null, Runnable::run);
            assertThat(viewer.isShowingText()).isTrue();
            assertThat(viewer.textContent()).contains("Architektur Steckbrief");
            viewer.close();
        });
    }

    @Test
    void unavailableWordStillExplainsWhyItCannotBeShown() throws Exception {
        run(() -> {
            AttachmentViewerPanel none = new AttachmentViewerPanel(new ReaderAttachment("att-doc", "Brief.docx",
                    "", -1), new FakeLoader().withoutPreviews(), null, Runnable::run);
            assertThat(none.statusText()).contains("Word-Dokument nicht lesbar").contains("nicht gefunden");
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
                .isEqualTo(ReaderAttachmentKind.PRESENTATION);
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
