package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein PDF für das Anhang-Overlay: Apache PDFBox zeichnet jede Seite in ein {@link BufferedImage}. Nicht threadsicher;
 * das Overlay öffnet, zeichnet und schließt auf seinem einen Zeichen-Thread.
 *
 * <p>Kopie von {@code PdfPages} aus dem Enterprise AI Client (exedra ui.reader); geändert nur das Öffnen für PDFBox 3
 * ({@code Loader.loadPDF} statt {@code PDDocument.load}, weiterhin nur im Hauptspeicher).</p>
 */
final class PdfPages implements RenderedPages {

    /** Pixel einer gezeichneten Seite höchstens (begrenzt die Schärfe beim starken Zoomen, schützt den Speicher). */
    static final double MAX_PIXELS = 40_000_000d;

    private final PDDocument document;
    private final PDFRenderer renderer;

    private PdfPages(PDDocument document) {
        this.document = document;
        this.renderer = new PDFRenderer(document);
    }

    /** Öffnet das PDF; wirft mit lesbarem Grund (verschlüsselt, beschädigt). */
    static PdfPages open(byte[] pdf) throws IOException {
        try {
            return new PdfPages(Loader.loadPDF(pdf, "", null, null, IOUtils.createMemoryOnlyStreamCache()));
        } catch (InvalidPasswordException e) {
            throw new IOException("Das PDF ist mit einem Passwort geschützt", e);
        }
    }

    public int pageCount() {
        return document.getNumberOfPages();
    }

    /** Die größte Zeichenskala für die Seite innerhalb von {@link #MAX_PIXELS}. */
    public double maxScale(int page) {
        PDRectangle box = document.getPage(page).getCropBox();
        double area = Math.max(1d, box.getWidth() * box.getHeight());
        return Math.sqrt(MAX_PIXELS / area);
    }

    /** Die Seite (0-basiert) mit {@code scale} (1 = 72 dpi). */
    public BufferedImage render(int page, double scale) throws IOException {
        float effective = (float) Math.max(0.1, Math.min(scale, maxScale(page)));
        return renderer.renderImage(page, effective, ImageType.RGB);
    }

    /** Sucht den Auszug Seite für Seite im Text von PDFBox und rechnet die Zeichen in Rechtecke um. */
    @Override
    public Passage find(String excerpt) throws IOException {
        if (excerpt == null || excerpt.isBlank()) {
            return null;
        }
        for (int page = 0; page < document.getNumberOfPages(); page++) {
            PageText text = PageText.read(document, page);
            int[] found = Passages.locate(text.text(), excerpt, -1, -1);
            if (found != null) {
                PDRectangle box = document.getPage(page).getCropBox();
                return new Passage(page, text.boxes(found[0], found[1], box.getWidth(), box.getHeight()));
            }
        }
        return null;
    }

    /** Der Text einer Seite mit der Lage jedes Zeichens ({@code null} für eingefügte Trenner). */
    private static final class PageText extends PDFTextStripper {

        private final StringBuilder text = new StringBuilder();
        private final List<TextPosition> positions = new ArrayList<>();

        private PageText() {
            setSortByPosition(true);
        }

        static PageText read(PDDocument document, int page) throws IOException {
            PageText reader = new PageText();
            reader.setStartPage(page + 1);
            reader.setEndPage(page + 1);
            reader.getText(document);
            return reader;
        }

        String text() {
            return text.toString();
        }

        @Override
        protected void writeString(String string, List<TextPosition> chars) {
            for (TextPosition position : chars) {
                String unicode = position.getUnicode();
                for (int i = 0; i < unicode.length(); i++) {
                    text.append(unicode.charAt(i));
                    positions.add(position);
                }
            }
        }

        @Override
        protected void writeWordSeparator() {
            separator();
        }

        @Override
        protected void writeLineSeparator() {
            separator();
        }

        @Override
        protected void writeParagraphEnd() {
            separator();
        }

        private void separator() {
            text.append(' ');
            positions.add(null);
        }

        /** Je Zeile ein Rechteck um die Zeichen {@code start} bis {@code end}, als Anteil der Seite. */
        List<Rectangle2D> boxes(int start, int end, float width, float height) {
            List<Rectangle2D> boxes = new ArrayList<>();
            Rectangle2D line = null;
            float baseline = Float.NaN;
            for (int i = start; i < end && i < positions.size(); i++) {
                TextPosition at = positions.get(i);
                if (at == null) {
                    continue;
                }
                double h = Math.max(at.getHeightDir(), 1f) * 1.2;
                Rectangle2D box = new Rectangle2D.Double(at.getXDirAdj(), at.getYDirAdj() - h, at.getWidthDirAdj(),
                        h * 1.25);
                if (line != null && Math.abs(at.getYDirAdj() - baseline) < 1.5f) {
                    line.add(box);
                } else {
                    if (line != null) {
                        boxes.add(line);
                    }
                    line = box;
                    baseline = at.getYDirAdj();
                }
            }
            if (line != null) {
                boxes.add(line);
            }
            List<Rectangle2D> share = new ArrayList<>();
            for (Rectangle2D box : boxes) {
                share.add(new Rectangle2D.Double(box.getX() / width, box.getY() / height, box.getWidth() / width,
                        box.getHeight() / height));
            }
            return share;
        }
    }

    @Override
    public void close() throws IOException {
        document.close();
    }
}
