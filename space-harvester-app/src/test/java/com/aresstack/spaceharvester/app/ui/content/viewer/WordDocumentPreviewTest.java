package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class WordDocumentPreviewTest {

    @Test
    void displaysDocxParagraphsWithoutAConfluencePreview() throws Exception {
        byte[] bytes;
        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().setText("HaVdB Labor");
            word.createParagraph().createRun().setText("Betriebsorganisation");
            word.write(output);
            bytes = output.toByteArray();
        }
        assertThat(WordDocumentPreview.read("labor.docx", bytes))
                .contains("HaVdB Labor", "Betriebsorganisation");
    }

    @Test
    void showsTheScreenshotsOfADocumentWithoutText() throws Exception {
        java.awt.image.BufferedImage screenshot = new java.awt.image.BufferedImage(40, 20,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(screenshot, "png", png);
        byte[] bytes;
        try (XWPFDocument word = new XWPFDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            word.createParagraph().createRun().addPicture(new java.io.ByteArrayInputStream(png.toByteArray()),
                    org.apache.poi.xwpf.usermodel.Document.PICTURE_TYPE_PNG, "image1.png",
                    org.apache.poi.util.Units.toEMU(300), org.apache.poi.util.Units.toEMU(150));
            word.write(output);
            bytes = output.toByteArray();
        }
        WordDocumentPreview.Rendered rendered = WordDocumentPreview.render("Bundescloud Stack.docx", bytes);
        assertThat(rendered.images()).hasSize(1);
        // So groß, wie das Dokument das Bild setzt: 300 × 150 Punkt sind 400 × 200 Pixel.
        assertThat(rendered.html()).contains(WordDocumentPreview.IMAGE_BASE + "0", "width=\"400\"",
                "height=\"200\"").doesNotContain(WordDocumentPreview.NO_TEXT);
        assertThat(rendered.missing()).isZero();
    }

    @Test
    void rejectsNonWordFileTypesWithoutTryingToParseTheirBytes() {
        assertThat(WordDocumentPreview.supports("labor.doc")).isTrue();
        assertThat(WordDocumentPreview.supports("labor.docx")).isTrue();
        assertThat(WordDocumentPreview.supports("labor.pptx")).isFalse();
    }
}
