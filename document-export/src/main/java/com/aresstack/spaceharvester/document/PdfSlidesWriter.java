package com.aresstack.spaceharvester.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Schreibt dieselben Folien wie {@link PptxExportWriter} als PDF: je Folie eine Seite, oben das Folienbild über die
 * ganze Breite, darunter Titel (etwa die Position im Video) und der Vortragstext. Die Seite ist so hoch wie Bild und
 * Text zusammen, damit kein Text abgeschnitten wird; ist er länger als eine Seite fassen darf, geht er auf
 * Folgeseiten ohne Bild weiter. Ohne Swing und ohne Netz.
 */
public final class PdfSlidesWriter {

    /** Breite der Seite in Punkt, wie die Folie der PPTX. */
    static final float PAGE_WIDTH = PptxExportWriter.SLIDE_WIDTH;
    static final float MARGIN = 36f;
    static final float TITLE_SIZE = 11f;
    static final float TEXT_SIZE = 14f;
    static final float LEADING = 19f;
    /** Höchste Seitenhöhe; darüber läuft der Text auf einer Folgeseite weiter. */
    static final float MAX_PAGE_HEIGHT = 1400f;

    /**
     * Schreibt die Folien nach {@code out}; {@code out} bleibt offen.
     *
     * @throws IOException wenn ein Bild unlesbar ist oder das Schreiben scheitert
     */
    public void write(List<PptxExportWriter.Slide> slides, OutputStream out) throws IOException {
        Objects.requireNonNull(slides, "slides");
        Objects.requireNonNull(out, "out");
        if (slides.isEmpty()) {
            throw new IllegalArgumentException("Keine Folien");
        }
        try (PDDocument document = new PDDocument()) {
            PDFont regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDFont bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            for (PptxExportWriter.Slide slide : slides) {
                add(document, slide, regular, bold);
            }
            document.save(out);
        }
    }

    private static void add(PDDocument document, PptxExportWriter.Slide slide, PDFont regular, PDFont bold)
            throws IOException {
        PDImageXObject image;
        try {
            image = PDImageXObject.createFromByteArray(document, slide.image(), "slide");
        } catch (IllegalArgumentException e) {
            throw new IOException("Folienbild ist kein lesbares Bild", e);
        }
        if (image.getWidth() < 1 || image.getHeight() < 1) {
            throw new IOException("Folienbild ist kein lesbares Bild");
        }
        float imageHeight = PAGE_WIDTH * image.getHeight() / image.getWidth();
        String title = slide.title() == null ? "" : printable(slide.title().strip(), bold);
        List<String> lines = slide.notes() == null || slide.notes().isBlank() ? List.of()
                : wrap(printable(slide.notes().strip(), regular), regular, TEXT_SIZE, PAGE_WIDTH - 2 * MARGIN);
        boolean text = !title.isEmpty() || !lines.isEmpty();
        float titleHeight = title.isEmpty() ? 0 : LEADING;
        // So viele Zeilen, wie unter das Bild passen; der Rest folgt auf Seiten ohne Bild.
        int first = Math.max(0, Math.min(lines.size(),
                (int) ((MAX_PAGE_HEIGHT - imageHeight - 2 * MARGIN - titleHeight) / LEADING)));
        float height = imageHeight + (text ? 2 * MARGIN + titleHeight + first * LEADING : 0);
        PDPage page = new PDPage(new PDRectangle(PAGE_WIDTH, height));
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            stream.drawImage(image, 0, height - imageHeight, PAGE_WIDTH, imageHeight);
            float baseline = height - imageHeight - MARGIN - TEXT_SIZE;
            if (!title.isEmpty()) {
                stream.setNonStrokingColor(0.45f, 0.45f, 0.45f);
                line(stream, title, bold, TITLE_SIZE, baseline);
                baseline -= LEADING;
            }
            stream.setNonStrokingColor(0f, 0f, 0f);
            for (int i = 0; i < first; i++) {
                line(stream, lines.get(i), regular, TEXT_SIZE, baseline);
                baseline -= LEADING;
            }
        }
        int perPage = (int) ((MAX_PAGE_HEIGHT - 2 * MARGIN) / LEADING);
        for (int from = first; from < lines.size(); from += perPage) {
            int to = Math.min(lines.size(), from + perPage);
            float rest = 2 * MARGIN + (to - from) * LEADING;
            PDPage more = new PDPage(new PDRectangle(PAGE_WIDTH, rest));
            document.addPage(more);
            try (PDPageContentStream stream = new PDPageContentStream(document, more)) {
                float baseline = rest - MARGIN - TEXT_SIZE;
                for (int i = from; i < to; i++) {
                    line(stream, lines.get(i), regular, TEXT_SIZE, baseline);
                    baseline -= LEADING;
                }
            }
        }
    }

    private static void line(PDPageContentStream stream, String text, PDFont font, float size, float baseline)
            throws IOException {
        if (text.isEmpty()) {
            return;
        }
        stream.beginText();
        stream.setFont(font, size);
        stream.newLineAtOffset(MARGIN, baseline);
        stream.showText(text);
        stream.endText();
    }

    /** Bricht an Leerzeichen um; Absätze bleiben, ein überlanges Wort wird in der Zeile geteilt. */
    static List<String> wrap(String text, PDFont font, float size, float width) throws IOException {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (String word : paragraph.strip().split("\\s+")) {
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (width(candidate, font, size) <= width) {
                    line.setLength(0);
                    line.append(candidate);
                    continue;
                }
                if (!line.isEmpty()) {
                    lines.add(line.toString());
                    line.setLength(0);
                }
                String rest = word;
                while (width(rest, font, size) > width && rest.length() > 1) {
                    int cut = rest.length() - 1;
                    while (cut > 1 && width(rest.substring(0, cut), font, size) > width) {
                        cut--;
                    }
                    lines.add(rest.substring(0, cut));
                    rest = rest.substring(cut);
                }
                line.append(rest);
            }
            lines.add(line.toString());
        }
        return lines;
    }

    private static float width(String text, PDFont font, float size) throws IOException {
        return font.getStringWidth(text) / 1000f * size;
    }

    /** Zeichen, die die Schrift nicht kennt (etwa Emoji), werden zu „?“; Tabulatoren und Zeilenenden zu Leerraum. */
    static String printable(String text, PDFont font) {
        StringBuilder result = new StringBuilder(text.length());
        text.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' ').codePoints().forEach(point -> {
            if (point == '\n') {
                result.append('\n');
                return;
            }
            String character = new String(Character.toChars(point));
            try {
                font.encode(character);
                result.append(character);
            } catch (IOException | IllegalArgumentException e) {
                result.append('?');
            }
        });
        return result.toString();
    }
}
