package com.aresstack.spaceharvester.testcorpus;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import javax.imageio.ImageIO;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.util.Units;
import org.apache.poi.xssf.streaming.SXSSFSheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;

/**
 * Große, ganz normale Prüfdateien (ADR 0026, Abnahme 5 und 6), zur Laufzeit in eine Datei geschrieben statt
 * eingecheckt. Der Umfang ist ein Parameter: Die Testgruppe mit kleinem Heap nimmt die Werte unten, normale Tests
 * nehmen wenige Seiten oder Zeilen. Der Inhalt ist erfundener Fülltext aus festem Zufall (Saat {@value #SEED}), also bei
 * gleichem Umfang gleich (Text, Bilder, Größe; ein PDF kann sich in Kennungen von PDFBox unterscheiden). Jede Seite,
 * jeder Absatz und jede Zeile trägt eine Marke, an der ein Test Vollständigkeit prüft.
 *
 * <p>Der Erzeuger hält die Datei nie ganz im Speicher: PDFBox puffert in einer temporären Datei, Excel schreibt über
 * {@link SXSSFWorkbook} (200 Zeilen im Speicher). Word baut POI im Speicher; dafür ist der Umfang hier klein gedacht.
 */
public final class LargeFiles {

    /** Seiten für ein PDF über 300 MiB mit {@link #pdfWithImages}: je Seite ein Rauschbild von rund 1 MiB. */
    public static final int PDF_OVER_300_MIB_PAGES = 310;
    /** Seiten für ein PDF mit rund 12 Mio. Zeichen mit {@link #pdfWithText} (je Seite rund 3 000 Zeichen). */
    public static final int PDF_TEXT_PAGES = 4_000;
    /** Zeilen für das Excel aus Abnahme 5. */
    public static final int XLSX_ROWS = 1_000_000;
    /** 300 MiB, die Schwelle aus Abnahme 5. */
    public static final long MIB_300 = 300L * 1024 * 1024;

    static final long SEED = 4711;
    private static final String[] WORDS = ("Verfahren Antrag Bescheid Frist Zuständigkeit Prüfung Unterlage Nachweis "
            + "Anlage Bearbeitung Übergabe Schnittstelle Datensatz Kennzeichen Meldung Auswertung Abgleich Protokoll "
            + "Freigabe Änderung Größe Maßnahme für und oder mit ohne nach vor über unter zwischen").split(" ");

    private LargeFiles() {
    }

    /** Marke der Seite {@code page} (ab 1) in den PDFs: {@code SEITE-<n>-ENDE} steht als letzte Zeile der Seite. */
    public static String pageMark(int page) {
        return "SEITE-" + page + "-ENDE";
    }

    /** Marke der Zeile {@code row} (ab 0) im Excel, in Spalte A. */
    public static String rowMark(int row) {
        return "ZEILE-" + row;
    }

    /** Marke des Absatzes {@code paragraph} (ab 0) im Word-Dokument, am Ende jedes Fülltext-Absatzes. */
    public static String paragraphMark(int paragraph) {
        return "ABSATZ-" + paragraph;
    }

    /**
     * PDF mit {@code pages} Seiten, je Seite wenig Text (7 Zeilen) und ein Rauschbild von rund 1 MiB, das sich nicht
     * packen lässt. Mit {@link #PDF_OVER_300_MIB_PAGES} wird die Datei größer als {@link #MIB_300}.
     */
    public static Path pdfWithImages(Path target, int pages) {
        return pdf(target, pages, true);
    }

    /** PDF mit {@code pages} Seiten zu je rund 3 000 Zeichen Text, ohne Bilder. */
    public static Path pdfWithText(Path target, int pages) {
        return pdf(target, pages, false);
    }

    private static Path pdf(Path target, int pages, boolean images) {
        requirePositive(pages);
        Random random = new Random(SEED);
        try (PDDocument document = new PDDocument(IOUtils.createTempFileOnlyStreamCache())) {
            PDType1Font font = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int number = 1; number <= pages; number++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(font, 9);
                    content.setLeading(11);
                    content.newLineAtOffset(40, 800);
                    content.showText("Seite " + number + " Abschnitt " + ((number - 1) / 10 + 1));
                    content.newLine();
                    int lines = images ? 6 : 30;
                    for (int line = 0; line < lines; line++) {
                        content.showText(line(random, 100));
                        content.newLine();
                    }
                    content.showText(pageMark(number));
                    content.endText();
                    if (images) {
                        PDImageXObject image = LosslessFactory.createFromImage(document, noise(random, 1000, 360));
                        content.drawImage(image, 40, 300, 500, 180);
                    }
                }
            }
            document.save(target.toFile());
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Word-Dokument mit {@code paragraphs} Absätzen zu rund 100 Zeichen, alle 40 eine Überschrift „Abschnitt n“
     * (Formatvorlage {@code Heading1}), alle 400 eine Tabelle 3 × 3 und insgesamt {@code pictures} Rauschbilder von je
     * rund 1 MiB (Word meldet sie als enthaltene Dateien, ADR 0026 Messung „Inline-Bilder“).
     */
    public static Path docx(Path target, int paragraphs, int pictures) {
        requirePositive(paragraphs);
        Random random = new Random(SEED);
        try (XWPFDocument document = new XWPFDocument()) {
            int every = pictures > 0 ? Math.max(1, paragraphs / pictures) : Integer.MAX_VALUE;
            int placed = 0;
            for (int i = 0; i < paragraphs; i++) {
                XWPFParagraph paragraph = document.createParagraph();
                if (i % 40 == 0) {
                    paragraph.setStyle("Heading1");
                    paragraph.createRun().setText("Abschnitt " + (i / 40 + 1));
                } else {
                    paragraph.createRun().setText(line(random, 100) + " " + paragraphMark(i));
                }
                if (i % 400 == 399) {
                    XWPFTable table = document.createTable(3, 3);
                    for (int y = 0; y < 3; y++) {
                        for (int x = 0; x < 3; x++) {
                            table.getRow(y).getCell(x).setText(line(random, 12));
                        }
                    }
                }
                if (placed < pictures && i % every == every - 1) {
                    ByteArrayOutputStream png = new ByteArrayOutputStream();
                    ImageIO.write(noise(random, 600, 600), "png", png);
                    document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png.toByteArray()),
                            XWPFDocument.PICTURE_TYPE_PNG, "bild" + i + ".png", Units.toEMU(300), Units.toEMU(300));
                    placed++;
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                document.write(out);
            }
            return target;
        } catch (Exception e) {
            throw e instanceof IOException io ? new UncheckedIOException(io) : new IllegalStateException(e);
        }
    }

    /**
     * Excel mit einem Blatt „Kosten“ und {@code rows} Zeilen zu 9 Spalten: Marke {@link #rowMark}, 4 Zahlen, 4 Texte
     * aus 5 000 verschiedenen (geteilte Zeichenketten wie in Excel). Mit {@link #XLSX_ROWS} rund 140 Mio. Zeichen Text.
     */
    public static Path xlsx(Path target, int rows) {
        requirePositive(rows);
        Random random = new Random(SEED);
        String[] pool = new String[5000];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = line(random, 24) + " " + i;
        }
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 200, true, true)) {
            SXSSFSheet sheet = workbook.createSheet("Kosten");
            for (int y = 0; y < rows; y++) {
                var row = sheet.createRow(y);
                row.createCell(0).setCellValue(rowMark(y));
                for (int x = 1; x < 9; x++) {
                    if (x < 5) {
                        row.createCell(x).setCellValue(random.nextInt(1_000_000) / 100.0);
                    } else {
                        row.createCell(x).setCellValue(pool[random.nextInt(pool.length)]);
                    }
                }
            }
            try (OutputStream out = Files.newOutputStream(target)) {
                workbook.write(out);
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String line(Random random, int chars) {
        StringBuilder text = new StringBuilder();
        while (text.length() < chars) {
            text.append(WORDS[random.nextInt(WORDS.length)]).append(' ');
        }
        return text.substring(0, chars).trim();
    }

    private static BufferedImage noise(Random random, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        int[] pixels = new int[width * height];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = random.nextInt(0x1000000);
        }
        image.setRGB(0, 0, width, height, pixels, 0, width);
        return image;
    }

    private static void requirePositive(int value) {
        if (value < 1) {
            throw new IllegalArgumentException("Umfang muss positiv sein");
        }
    }
}
