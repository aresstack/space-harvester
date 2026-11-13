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
 * nehmen wenige Seiten oder Zeilen. Der Inhalt ist erfundener Fülltext aus festem Zufall (Saat {@value #SEED}), also
 * bei gleichem Umfang gleich (Text, Bilder, Größe; ein PDF kann sich in Kennungen von PDFBox unterscheiden). Jede
 * Seite, jeder Absatz und jede Zeile trägt eine Marke, an der ein Test Vollständigkeit prüft.
 *
 * <p>Der Erzeuger hält die Datei nie ganz im Speicher: PDFBox puffert in einer temporären Datei, Excel schreibt über
 * {@link SXSSFWorkbook} (200 Zeilen im Speicher, Zeichenketten inline). Word baut POI im Speicher; dafür ist der
 * Umfang hier klein gedacht.
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
     * aus 5 000 verschiedenen, als Inline-Zeichenketten (eine Tabelle geteilter Zeichenketten hielte die Million Marken
     * beim Erzeugen im Speicher). Mit {@link #XLSX_ROWS} rund 140 Mio. Zeichen Text.
     */
    public static Path xlsx(Path target, int rows) {
        requirePositive(rows);
        Random random = new Random(SEED);
        String[] pool = new String[5000];
        for (int i = 0; i < pool.length; i++) {
            pool[i] = line(random, 24) + " " + i;
        }
        try (SXSSFWorkbook workbook = new SXSSFWorkbook(null, 200, true, false)) {
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

    /** Erste Zeile der Text- und CSV-Prüfdateien. */
    public static final String FIRST_MARK = "DATEI-ANFANG";
    /** Letzte Zeile der Text- und CSV-Prüfdateien. */
    public static final String LAST_MARK = "DATEI-ENDE";
    /** Etwas über {@link #MIB_300}, damit auch eine Hülle (ZIP, TAR, GZIP) darüber liegt. */
    public static final long BYTES_OVER_300_MIB = MIB_300 + 10L * 1024 * 1024;
    /** Folien für ein .pptx über 300 MiB mit {@link #pptx}: je Folie ein Rauschbild von rund 1 MiB. */
    public static final int PPTX_OVER_300_MIB_SLIDES = 310;

    /** Marke der Folie {@code slide} (ab 1) im .pptx, letzte Zeile der Folie. */
    public static String slideMark(int slide) {
        return "FOLIE-" + slide + "-ENDE";
    }

    /** Textdatei mit Zeilen zu rund 100 Zeichen bis mindestens {@code bytes} Bytes, erste und letzte Zeile Marken. */
    public static Path text(Path target, long bytes) {
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
            Random random = new Random(SEED);
            write(out, FIRST_MARK + "\n");
            long written = FIRST_MARK.length() + 1;
            while (written < bytes) {
                written += write(out, line(random, 100) + "\n");
            }
            write(out, LAST_MARK + "\n");
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** CSV mit Kopfzeile und vier Spalten bis mindestens {@code bytes} Bytes; erste und letzte Datenzeile Marken. */
    public static Path csv(Path target, long bytes) {
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
            Random random = new Random(SEED);
            long written = write(out, "Kennung,Betrag,Status,Bemerkung\n");
            written += write(out, FIRST_MARK + ",0,offen,Beginn\n");
            long row = 1;
            while (written < bytes) {
                written += write(out, "Z" + row++ + "," + random.nextInt(1_000_000) / 100.0 + ","
                        + line(random, 12) + "," + line(random, 60) + "\n");
            }
            write(out, LAST_MARK + ",0,erledigt,Ende\n");
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** ZIP mit der Datei {@code file} als einzigem Eintrag {@code name}, ungepackt, damit die Hülle so groß ist. */
    public static Path zip(Path target, Path file, String name) {
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16))) {
            zip.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            zip.putNextEntry(new java.util.zip.ZipEntry(name));
            Files.copy(file, zip);
            zip.closeEntry();
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** GZIP der Datei {@code file}, ungepackt, damit die Hülle so groß ist. */
    public static Path gzip(Path target, Path file) {
        try (OutputStream out = new java.util.zip.GZIPOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16), 1 << 16) {
            {
                def.setLevel(java.util.zip.Deflater.NO_COMPRESSION);
            }
        }) {
            Files.copy(file, out);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** TAR (ustar) mit der Datei {@code file} als einzigem Eintrag {@code name}. */
    public static Path tar(Path target, Path file, String name) {
        try (OutputStream out = new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16)) {
            long size = Files.size(file);
            byte[] header = new byte[512];
            put(header, 0, name, 100);
            put(header, 100, "0000644", 8);
            put(header, 108, "0000000", 8);
            put(header, 116, "0000000", 8);
            put(header, 124, String.format("%011o", size), 12);
            put(header, 136, String.format("%011o", 1_700_000_000L), 12);
            java.util.Arrays.fill(header, 148, 156, (byte) ' ');
            header[156] = '0';
            put(header, 257, "ustar", 6);
            header[263] = '0';
            header[264] = '0';
            long sum = 0;
            for (byte b : header) {
                sum += b & 0xff;
            }
            put(header, 148, String.format("%06o", sum), 7);
            out.write(header);
            Files.copy(file, out);
            int pad = (int) ((512 - size % 512) % 512);
            out.write(new byte[pad + 1024]);
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * PowerPoint (.pptx) mit {@code slides} Folien, je Folie wenig Text und ein Rauschbild von rund 1 MiB; ohne POI
     * geschrieben, damit nie alle Bilder zugleich im Speicher liegen. Letzte Zeile jeder Folie {@link #slideMark}.
     */
    public static Path pptx(Path target, int slides) {
        requirePositive(slides);
        Random random = new Random(SEED);
        String p = "http://schemas.openxmlformats.org/presentationml/2006/main";
        String a = "http://schemas.openxmlformats.org/drawingml/2006/main";
        String r = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
        String rel = "http://schemas.openxmlformats.org/package/2006/relationships";
        String type = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/";
        String head = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n";
        String tree = "<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id=\"1\" name=\"\"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>"
                + "<p:grpSpPr/>";
        try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                new java.io.BufferedOutputStream(Files.newOutputStream(target), 1 << 16))) {
            StringBuilder types = new StringBuilder(head).append("<Types xmlns=\"http://schemas.openxmlformats.org/"
                    + "package/2006/content-types\"><Default Extension=\"rels\" ContentType=\"application/vnd."
                    + "openxmlformats-package.relationships+xml\"/><Default Extension=\"xml\" ContentType=\""
                    + "application/xml\"/><Default Extension=\"png\" ContentType=\"image/png\"/>"
                    + "<Override PartName=\"/ppt/presentation.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.presentationml.presentation.main+xml\"/><Override PartName=\"/ppt/slideMasters/"
                    + "slideMaster1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml."
                    + "slideMaster+xml\"/><Override PartName=\"/ppt/slideLayouts/slideLayout1.xml\" ContentType=\""
                    + "application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml\"/><Override "
                    + "PartName=\"/ppt/theme/theme1.xml\" ContentType=\"application/vnd.openxmlformats-"
                    + "officedocument.theme+xml\"/>");
            StringBuilder ids = new StringBuilder();
            StringBuilder rels = new StringBuilder(head).append("<Relationships xmlns=\"").append(rel).append("\">")
                    .append("<Relationship Id=\"rId1\" Type=\"").append(type).append("slideMaster\" Target=\""
                            + "slideMasters/slideMaster1.xml\"/><Relationship Id=\"rId2\" Type=\"").append(type)
                    .append("theme\" Target=\"theme/theme1.xml\"/>");
            for (int n = 1; n <= slides; n++) {
                types.append("<Override PartName=\"/ppt/slides/slide").append(n).append(".xml\" ContentType=\""
                        + "application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>");
                ids.append("<p:sldId id=\"").append(255 + n).append("\" r:id=\"rId").append(n + 2).append("\"/>");
                rels.append("<Relationship Id=\"rId").append(n + 2).append("\" Type=\"").append(type)
                        .append("slide\" Target=\"slides/slide").append(n).append(".xml\"/>");
            }
            entry(zip, "[Content_Types].xml", types.append("</Types>").toString());
            entry(zip, "_rels/.rels", head + "<Relationships xmlns=\"" + rel + "\"><Relationship Id=\"rId1\" Type=\""
                    + type + "officeDocument\" Target=\"ppt/presentation.xml\"/></Relationships>");
            entry(zip, "ppt/presentation.xml", head + "<p:presentation xmlns:a=\"" + a + "\" xmlns:r=\"" + r
                    + "\" xmlns:p=\"" + p + "\"><p:sldMasterIdLst><p:sldMasterId id=\"2147483648\" r:id=\"rId1\"/>"
                    + "</p:sldMasterIdLst><p:sldIdLst>" + ids + "</p:sldIdLst><p:sldSz cx=\"9144000\" cy=\"6858000\"/>"
                    + "<p:notesSz cx=\"6858000\" cy=\"9144000\"/></p:presentation>");
            entry(zip, "ppt/_rels/presentation.xml.rels", rels.append("</Relationships>").toString());
            entry(zip, "ppt/slideMasters/slideMaster1.xml", head + "<p:sldMaster xmlns:a=\"" + a + "\" xmlns:r=\""
                    + r + "\" xmlns:p=\"" + p + "\">" + tree + "</p:spTree></p:cSld><p:clrMap bg1=\"lt1\" tx1=\"dk1\" "
                    + "bg2=\"lt2\" tx2=\"dk2\" accent1=\"accent1\" accent2=\"accent2\" accent3=\"accent3\" accent4=\""
                    + "accent4\" accent5=\"accent5\" accent6=\"accent6\" hlink=\"hlink\" folHlink=\"folHlink\"/>"
                    + "<p:sldLayoutIdLst><p:sldLayoutId id=\"2147483649\" r:id=\"rId1\"/></p:sldLayoutIdLst>"
                    + "</p:sldMaster>");
            entry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", head + "<Relationships xmlns=\"" + rel
                    + "\"><Relationship Id=\"rId1\" Type=\"" + type + "slideLayout\" Target=\"../slideLayouts/"
                    + "slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"" + type + "theme\" Target=\"../theme/"
                    + "theme1.xml\"/></Relationships>");
            entry(zip, "ppt/slideLayouts/slideLayout1.xml", head + "<p:sldLayout xmlns:a=\"" + a + "\" xmlns:r=\""
                    + r + "\" xmlns:p=\"" + p + "\">" + tree + "</p:spTree></p:cSld></p:sldLayout>");
            entry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", head + "<Relationships xmlns=\"" + rel
                    + "\"><Relationship Id=\"rId1\" Type=\"" + type + "slideMaster\" Target=\"../slideMasters/"
                    + "slideMaster1.xml\"/></Relationships>");
            entry(zip, "ppt/theme/theme1.xml", head + "<a:theme xmlns:a=\"" + a + "\" name=\"Leer\"><a:themeElements>"
                    + "<a:clrScheme name=\"Leer\"><a:dk1><a:srgbClr val=\"000000\"/></a:dk1><a:lt1><a:srgbClr val=\""
                    + "FFFFFF\"/></a:lt1><a:dk2><a:srgbClr val=\"000000\"/></a:dk2><a:lt2><a:srgbClr val=\"FFFFFF\"/>"
                    + "</a:lt2><a:accent1><a:srgbClr val=\"000000\"/></a:accent1><a:accent2><a:srgbClr val=\"000000\"/>"
                    + "</a:accent2><a:accent3><a:srgbClr val=\"000000\"/></a:accent3><a:accent4><a:srgbClr val=\""
                    + "000000\"/></a:accent4><a:accent5><a:srgbClr val=\"000000\"/></a:accent5><a:accent6><a:srgbClr "
                    + "val=\"000000\"/></a:accent6><a:hlink><a:srgbClr val=\"0000FF\"/></a:hlink><a:folHlink>"
                    + "<a:srgbClr val=\"800080\"/></a:folHlink></a:clrScheme><a:fontScheme name=\"Leer\"><a:majorFont>"
                    + "<a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:majorFont><a:minorFont>"
                    + "<a:latin typeface=\"Arial\"/><a:ea typeface=\"\"/><a:cs typeface=\"\"/></a:minorFont>"
                    + "</a:fontScheme><a:fmtScheme name=\"Leer\"><a:fillStyleLst/><a:lnStyleLst/><a:effectStyleLst/>"
                    + "<a:bgFillStyleLst/></a:fmtScheme></a:themeElements></a:theme>");
            for (int n = 1; n <= slides; n++) {
                StringBuilder text = new StringBuilder();
                text.append("<a:p><a:r><a:t>Folie ").append(n).append("</a:t></a:r></a:p>");
                for (int line = 0; line < 6; line++) {
                    text.append("<a:p><a:r><a:t>").append(line(random, 100)).append("</a:t></a:r></a:p>");
                }
                text.append("<a:p><a:r><a:t>").append(slideMark(n)).append("</a:t></a:r></a:p>");
                entry(zip, "ppt/slides/slide" + n + ".xml", head + "<p:sld xmlns:a=\"" + a + "\" xmlns:r=\"" + r
                        + "\" xmlns:p=\"" + p + "\">" + tree + "<p:sp><p:nvSpPr><p:cNvPr id=\"2\" name=\"Text\"/>"
                        + "<p:cNvSpPr txBox=\"1\"/><p:nvPr/></p:nvSpPr><p:spPr><a:xfrm><a:off x=\"457200\" y=\"457200\"/>"
                        + "<a:ext cx=\"8229600\" cy=\"2286000\"/></a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/>"
                        + "</a:prstGeom></p:spPr><p:txBody><a:bodyPr/><a:lstStyle/>" + text + "</p:txBody></p:sp>"
                        + "<p:pic><p:nvPicPr><p:cNvPr id=\"3\" name=\"Bild\"/><p:cNvPicPr/><p:nvPr/></p:nvPicPr>"
                        + "<p:blipFill><a:blip r:embed=\"rId2\"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>"
                        + "<p:spPr><a:xfrm><a:off x=\"457200\" y=\"3200400\"/><a:ext cx=\"8229600\" cy=\"2971800\"/>"
                        + "</a:xfrm><a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></p:spPr></p:pic>"
                        + "</p:spTree></p:cSld></p:sld>");
                entry(zip, "ppt/slides/_rels/slide" + n + ".xml.rels", head + "<Relationships xmlns=\"" + rel
                        + "\"><Relationship Id=\"rId1\" Type=\"" + type + "slideLayout\" Target=\"../slideLayouts/"
                        + "slideLayout1.xml\"/><Relationship Id=\"rId2\" Type=\"" + type + "image\" Target=\"../media/"
                        + "bild" + n + ".png\"/></Relationships>");
                zip.putNextEntry(new java.util.zip.ZipEntry("ppt/media/bild" + n + ".png"));
                ImageIO.write(noise(random, 1000, 360), "png", zip);
                zip.closeEntry();
            }
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void entry(java.util.zip.ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new java.util.zip.ZipEntry(name));
        zip.write(content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static int write(OutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        out.write(bytes);
        return bytes.length;
    }

    private static void put(byte[] header, int offset, String value, int length) {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, offset, Math.min(bytes.length, length - 1));
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
