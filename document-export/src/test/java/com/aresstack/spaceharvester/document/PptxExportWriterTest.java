package com.aresstack.spaceharvester.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import javax.imageio.ImageIO;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.junit.jupiter.api.Test;

/** Folienbilder mit Vortragstext als Notizen. */
class PptxExportWriterTest {

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

    private static XMLSlideShow write(List<PptxExportWriter.Slide> slides) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new PptxExportWriter().write(slides, out);
        return new XMLSlideShow(new ByteArrayInputStream(out.toByteArray()));
    }

    private static String notes(XMLSlideShow show, XSLFSlide slide) {
        XSLFNotes notes = slide.getNotes();
        if (notes == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        for (XSLFTextShape placeholder : notes.getPlaceholders()) {
            if (placeholder.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                text.append(placeholder.getText());
            }
        }
        return text.toString();
    }

    @Test
    void eachSlideCarriesItsPictureAndItsNotes() throws IOException {
        try (XMLSlideShow show = write(List.of(
                new PptxExportWriter.Slide(png(1280, 720, Color.BLUE), "00:00:05", "Begrüßung und Ziel des Vortrags."),
                new PptxExportWriter.Slide(png(1280, 720, Color.RED), "00:03:12", "Architektur:\nDrei Schichten.")))) {
            assertThat(show.getSlides()).hasSize(2);
            assertThat(show.getPageSize().width).isEqualTo(PptxExportWriter.SLIDE_WIDTH);
            assertThat(show.getPageSize().height).isEqualTo(540);
            XSLFSlide first = show.getSlides().get(0);
            assertThat(first.getShapes()).singleElement().isInstanceOf(XSLFPictureShape.class);
            XSLFPictureShape picture = (XSLFPictureShape) first.getShapes().get(0);
            assertThat(picture.getAnchor().getWidth()).isEqualTo(960.0);
            assertThat(picture.getAnchor().getHeight()).isEqualTo(540.0);
            assertThat(notes(show, first)).isEqualTo("Begrüßung und Ziel des Vortrags.");
            assertThat(notes(show, show.getSlides().get(1))).contains("Drei Schichten.");
        }
    }

    @Test
    void aPictureOfAnotherShapeIsFittedAndCentred() throws IOException {
        try (XMLSlideShow show = write(List.of(
                new PptxExportWriter.Slide(png(1600, 900, Color.WHITE), null, null),
                new PptxExportWriter.Slide(png(400, 400, Color.GREEN), null, "  ")))) {
            XSLFPictureShape square = (XSLFPictureShape) show.getSlides().get(1).getShapes().get(0);
            assertThat(square.getAnchor().getHeight()).isEqualTo(540.0);
            assertThat(square.getAnchor().getWidth()).isEqualTo(540.0);
            assertThat(square.getAnchor().getX()).isEqualTo(210.0);
            assertThat(show.getSlides().get(1).getNotes()).isNull();
        }
    }

    @Test
    void withoutSlidesOrWithAnUnreadablePictureNothingIsWritten() {
        assertThatThrownBy(() -> new PptxExportWriter().write(List.of(), new ByteArrayOutputStream()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> write(List.of(new PptxExportWriter.Slide(new byte[] {1, 2, 3}, null, null))))
                .isInstanceOf(IOException.class);
    }
}
