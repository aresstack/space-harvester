package com.aresstack.spaceharvester.document;

import org.apache.poi.sl.usermodel.PictureData;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFNotes;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextShape;

import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;

/**
 * Schreibt eine PowerPoint-Datei (PPTX) aus Folienbildern: je Folie ein Bild, eingepasst und mittig auf einer Folie im
 * Seitenverhältnis des ersten Bilds (sonst 16:9), und der Vortragstext als Notiz der Folie („Notizen“ in PowerPoint).
 * Ohne Swing und ohne Netz.
 */
public final class PptxExportWriter {

    /** Breite der Folie in Punkt (1280 px bei 96 dpi), die Höhe folgt dem Seitenverhältnis. */
    static final int SLIDE_WIDTH = 960;

    /**
     * Eine Folie.
     *
     * @param image PNG oder JPEG des Folienbilds
     * @param title Titel für die Gliederung (etwa die Position im Video), oder {@code null}
     * @param notes Vortragstext für die Notizen, oder {@code null}/leer ohne Notiz
     */
    public record Slide(byte[] image, String title, String notes) {

        public Slide {
            Objects.requireNonNull(image, "image");
            if (image.length == 0) {
                throw new IllegalArgumentException("Folie ohne Bild");
            }
        }
    }

    /**
     * Schreibt die Folien nach {@code out}; {@code out} bleibt offen.
     *
     * @throws IOException wenn ein Bild unlesbar ist oder das Schreiben scheitert
     */
    public void write(List<Slide> slides, OutputStream out) throws IOException {
        Objects.requireNonNull(slides, "slides");
        Objects.requireNonNull(out, "out");
        if (slides.isEmpty()) {
            throw new IllegalArgumentException("Keine Folien");
        }
        try (XMLSlideShow show = new XMLSlideShow()) {
            Dimension size = slideSize(slides.get(0).image());
            show.setPageSize(size);
            for (Slide slide : slides) {
                add(show, size, slide);
            }
            show.write(out);
        }
    }

    private static void add(XMLSlideShow show, Dimension size, Slide content) throws IOException {
        XSLFSlide slide = show.createSlide();
        PictureData.PictureType type = pictureType(content.image());
        XSLFPictureData data = show.addPicture(content.image(), type);
        XSLFPictureShape picture = slide.createPicture(data);
        Dimension image = imageSize(content.image());
        double scale = Math.min(size.getWidth() / image.width, size.getHeight() / image.height);
        double width = image.width * scale;
        double height = image.height * scale;
        picture.setAnchor(new Rectangle2D.Double((size.getWidth() - width) / 2, (size.getHeight() - height) / 2,
                width, height));
        if (content.title() != null && !content.title().isBlank()) {
            picture.setName(content.title().strip());
        }
        if (content.notes() != null && !content.notes().isBlank()) {
            XSLFNotes notes = show.getNotesSlide(slide);
            for (XSLFTextShape placeholder : notes.getPlaceholders()) {
                if (placeholder.getTextType() == org.apache.poi.sl.usermodel.Placeholder.BODY) {
                    placeholder.setText(content.notes().strip());
                    break;
                }
            }
        }
    }

    /** Das Seitenverhältnis des ersten Bilds bei fester Breite; ein unlesbares Maß ergibt 16:9. */
    private static Dimension slideSize(byte[] first) throws IOException {
        Dimension image = imageSize(first);
        int height = (int) Math.round(SLIDE_WIDTH * (double) image.height / image.width);
        return new Dimension(SLIDE_WIDTH, Math.max(1, height));
    }

    private static Dimension imageSize(byte[] bytes) throws IOException {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        if (image == null || image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IOException("Folienbild ist kein lesbares Bild");
        }
        return new Dimension(image.getWidth(), image.getHeight());
    }

    private static PictureData.PictureType pictureType(byte[] bytes) {
        // JPEG beginnt mit FF D8, alles andere wird als PNG eingetragen (die Standbilder der Marken sind PNG).
        return bytes.length > 1 && (bytes[0] & 0xff) == 0xff && (bytes[1] & 0xff) == 0xd8
                ? PictureData.PictureType.JPEG : PictureData.PictureType.PNG;
    }
}
