package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

/** Folienbilder mit Vortragstext als PDF: je Folie eine Seite, der Text unter dem Bild. */
class PdfSlidesWriterTest {

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(color);
        graphics.fillRect(0, 0, width, height);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static PDDocument write(List<PptxExportWriter.Slide> slides) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PdfSlidesWriter().write(slides, out);
        return Loader.loadPDF(out.toByteArray());
    }

    @Test
    void eachSlideIsAPageWithItsPictureAndItsTextBelow() throws IOException {
        try (PDDocument document = write(List.of(
                new PptxExportWriter.Slide(png(1280, 720, Color.BLUE), "00:00:05", "Begrüßung und Ziel des Vortrags."),
                new PptxExportWriter.Slide(png(1280, 720, Color.RED), "00:03:12", "Architektur:\nDrei Schichten – "
                        + "„Größe“ zählt. 🙂"),
                new PptxExportWriter.Slide(png(1280, 720, Color.GREEN), null, null)))) {
            assertThat(document.getNumberOfPages()).isEqualTo(3);
            assertThat(document.getPage(0).getMediaBox().getWidth()).isEqualTo(PdfSlidesWriter.PAGE_WIDTH);
            assertThat(document.getPage(0).getMediaBox().getHeight()).as("Bild plus Textblock").isGreaterThan(540f);
            assertThat(document.getPage(2).getMediaBox().getHeight()).as("ohne Text nur das Bild").isEqualTo(540f);
            assertThat(document.getPage(0).getResources().getXObjectNames()).hasSize(1);
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setStartPage(1);
            stripper.setEndPage(1);
            assertThat(stripper.getText(document)).contains("00:00:05", "Begrüßung und Ziel des Vortrags.");
            stripper.setStartPage(2);
            stripper.setEndPage(2);
            assertThat(stripper.getText(document)).contains("Architektur:", "Drei Schichten – „Größe“ zählt. ?");
        }
    }

    @Test
    void aVeryLongTextContinuesOnFollowingPagesInsteadOfBeingCut() throws IOException {
        String sentence = "Dieser Satz steht sehr oft im Vortragstext und füllt mehr als eine Seite. ";
        try (PDDocument document = write(List.of(new PptxExportWriter.Slide(png(1280, 720, Color.BLUE), "00:00:05",
                sentence.repeat(120) + "ENDE")))) {
            assertThat(document.getNumberOfPages()).isGreaterThan(1);
            for (int i = 0; i < document.getNumberOfPages(); i++) {
                assertThat(document.getPage(i).getMediaBox().getHeight())
                        .isLessThanOrEqualTo(PdfSlidesWriter.MAX_PAGE_HEIGHT + 1f);
            }
            assertThat(new PDFTextStripper().getText(document)).contains("ENDE");
        }
    }

    @Test
    void noSlidesOrAnUnreadablePictureAreRefused() {
        assertThatThrownBy(() -> new PdfSlidesWriter().write(List.of(), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PdfSlidesWriter().write(List.of(new PptxExportWriter.Slide(new byte[] {1, 2, 3},
                null, null)), new ByteArrayOutputStream())).isInstanceOf(IOException.class);
    }
}
