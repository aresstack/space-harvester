package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class PresentationPagesTest {

    @Test
    void rendersPowerPointSlidesIndividually() throws IOException {
        byte[] original = presentationWithTwoSlides();

        try (PresentationPages pages = PresentationPages.open(original)) {
            assertThat(pages.pageCount()).isEqualTo(2);
            assertThat(pages.maxScale(0)).isGreaterThan(0);
            BufferedImage firstSlide = pages.render(0, 1.5);
            BufferedImage secondSlide = pages.render(1, 1.0);

            assertThat(firstSlide.getWidth()).isGreaterThan(secondSlide.getWidth());
            assertThat(firstSlide.getHeight()).isGreaterThan(secondSlide.getHeight());
        }
    }

    @Test
    void rendersLegacyPowerPointSlides() throws IOException {
        try (org.apache.poi.hslf.usermodel.HSLFSlideShow legacy = new org.apache.poi.hslf.usermodel.HSLFSlideShow();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            legacy.createSlide();
            legacy.createSlide();
            legacy.write(bytes);
            try (PresentationPages pages = PresentationPages.open(bytes.toByteArray())) {
                assertThat(pages.pageCount()).isEqualTo(2);
                assertThat(pages.render(1, 1.0).getWidth()).isGreaterThan(0);
            }
        }
    }

    @Test
    void navigatesPresentationWithTheExistingPageControls() throws Exception {
        byte[] bytes = presentationWithTwoSlides();
        javax.swing.SwingUtilities.invokeAndWait(() -> {
            AttachmentViewerPanel viewer = new AttachmentViewerPanel(
                    new ReaderAttachment("slides", "Projekt.pptx", "application/octet-stream", bytes.length),
                    (attachment, done, failed) -> done.accept(bytes), null, Runnable::run);
            assertThat(viewer.pageCount()).isEqualTo(2);
            assertThat(viewer.infoText()).startsWith("Folie 1 / 2");
            viewer.next();
            assertThat(viewer.page()).isEqualTo(1);
            assertThat(viewer.infoText()).startsWith("Folie 2 / 2");
            viewer.previous();
            assertThat(viewer.page()).isZero();
            viewer.close();
        });
    }

    @Test
    void recognizesPresentationsWithoutConfusingOtherOfficeDocuments() {
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "test.pptx"))
                .isEqualTo(ReaderAttachmentKind.PRESENTATION);
        assertThat(ReaderAttachmentKind.of("application/vnd.ms-powerpoint", "test.ppt"))
                .isEqualTo(ReaderAttachmentKind.PRESENTATION);
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "report.docx"))
                .isEqualTo(ReaderAttachmentKind.OFFICE);
    }

    private static byte[] presentationWithTwoSlides() throws IOException {
        try (XMLSlideShow presentation = new XMLSlideShow();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            XSLFSlide first = presentation.createSlide();
            first.createTextBox().setText("Introduction");
            XSLFSlide second = presentation.createSlide();
            second.createTextBox().setText("Results");
            presentation.write(bytes);
            return bytes.toByteArray();
        }
    }
}
