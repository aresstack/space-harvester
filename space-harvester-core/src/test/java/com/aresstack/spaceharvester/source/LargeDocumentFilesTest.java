package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.ExtractionStore;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import com.aresstack.spaceharvester.testcorpus.LargeFiles;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Speicher (ADR 0026, Abnahme 5 und 6): große Prüfdateien aus dem Testkorpus werden mit den Standardgrenzen vollständig
 * gelesen. Läuft nur in {@code largeFileTest}, einer eigenen JVM mit 256 MiB Heap; ein Lauf, der an einer Grenze
 * (auch der Zeitgrenze) endet, besteht nicht.
 */
@Tag("large-files")
class LargeDocumentFilesTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    @TempDir
    Path directory;

    private final TikaDocumentStructure structure = new TikaDocumentStructure();

    @Test
    void pdfOver300MiBIsReadCompletely() throws IOException {
        Path pdf = LargeFiles.pdfWithImages(directory.resolve("gross.pdf"), LargeFiles.PDF_OVER_300_MIB_PAGES);
        assertThat(Files.size(pdf)).isGreaterThan(LargeFiles.MIB_300);

        assertComplete(pdf, "application/pdf", LargeFiles.pageMark(1),
                LargeFiles.pageMark(LargeFiles.PDF_OVER_300_MIB_PAGES));
    }

    @Test
    void pdfWithManyTextPagesIsReadCompletely() {
        Path pdf = LargeFiles.pdfWithText(directory.resolve("text.pdf"), LargeFiles.PDF_TEXT_PAGES);

        assertComplete(pdf, "application/pdf", LargeFiles.pageMark(1), LargeFiles.pageMark(LargeFiles.PDF_TEXT_PAGES));
    }

    @Test
    void excelWithOneMillionRowsIsReadCompletely() {
        Path xlsx = LargeFiles.xlsx(directory.resolve("zeilen.xlsx"), LargeFiles.XLSX_ROWS);

        assertComplete(xlsx, XLSX, LargeFiles.rowMark(0), LargeFiles.rowMark(LargeFiles.XLSX_ROWS - 1));
    }

    /**
     * Wie Excel üblich speichert: Jede Marke steht in der Tabelle geteilter Zeichenketten (Architekt 13.11.2026). POI
     * hält diese Tabelle beim Lesen im Speicher; scheitert dieser Fall, braucht {@code .xlsx} die Schranke
     * {@code memory-limit}.
     */
    @Test
    void excelWithOneMillionSharedStringsIsReadCompletely() {
        Path xlsx = sharedStringsXlsx(directory.resolve("geteilt.xlsx"), LargeFiles.XLSX_ROWS);

        assertComplete(xlsx, XLSX, LargeFiles.rowMark(0), LargeFiles.rowMark(LargeFiles.XLSX_ROWS - 1));
    }

    @Test
    void largeWordDocumentIsReadCompletely() {
        int paragraphs = 60_000;
        Path docx = LargeFiles.docx(directory.resolve("lang.docx"), paragraphs, 15);

        assertComplete(docx, DOCX, LargeFiles.paragraphMark(1), LargeFiles.paragraphMark(paragraphs - 1));
    }

    private void assertComplete(Path file, String mediaType, String firstMark, String lastMark) {
        TikaDocumentStructureTest.Counting sink = new TikaDocumentStructureTest.Counting(firstMark, lastMark);

        StructuredDocument result = structure.extract(file, file.getFileName().toString(), mediaType,
                ExtractionLimits.DEFAULTS, sink);

        assertThat(result.outcome().status()).as("Status").isEqualTo(ExtractionStatus.OK);
        assertThat(result.outcome().truncated()).isFalse();
        assertThat(sink.found).containsExactlyInAnyOrder(firstMark, lastMark);
        assertThat(result.chars()).isEqualTo(sink.chars);
        assertThat(sink.largestBlock).isLessThanOrEqualTo(ExtractionStore.DEFAULT_SECTION_CHARS);
    }

    /** Ein Blatt mit {@code rows} Zeilen, Spalte A die Zeilenmarke als geteilte Zeichenkette; ohne POI geschrieben. */
    static Path sharedStringsXlsx(Path target, int rows) {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(target))) {
            Writer out = new BufferedWriter(new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            entry(zip, out, "[Content_Types].xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
                    <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
                    <Default Extension="xml" ContentType="application/xml"/>
                    <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
                    <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
                    <Override PartName="/xl/sharedStrings.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sharedStrings+xml"/>
                    </Types>""");
            entry(zip, out, "_rels/.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
                    </Relationships>""");
            entry(zip, out, "xl/workbook.xml", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
                    <sheets><sheet name="Kosten" sheetId="1" r:id="rId1"/></sheets>
                    </workbook>""");
            entry(zip, out, "xl/_rels/workbook.xml.rels", """
                    <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                    <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
                    <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/sharedStrings" Target="sharedStrings.xml"/>
                    </Relationships>""");
            zip.putNextEntry(new ZipEntry("xl/sharedStrings.xml"));
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<sst xmlns=\""
                    + "http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"" + rows + "\" uniqueCount=\""
                    + rows + "\">");
            for (int y = 0; y < rows; y++) {
                out.write("<si><t>");
                out.write(LargeFiles.rowMark(y));
                out.write(" Antrag Bescheid Frist Nachweis</t></si>");
            }
            out.write("</sst>");
            out.flush();
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            out.write("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<worksheet xmlns=\""
                    + "http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
            for (int y = 0; y < rows; y++) {
                int r = y + 1;
                out.write("<row r=\"" + r + "\"><c r=\"A" + r + "\" t=\"s\"><v>" + y + "</v></c></row>");
            }
            out.write("</sheetData></worksheet>");
            out.flush();
            zip.closeEntry();
            return target;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void entry(ZipOutputStream zip, Writer out, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        out.write(content);
        out.flush();
        zip.closeEntry();
    }
}
