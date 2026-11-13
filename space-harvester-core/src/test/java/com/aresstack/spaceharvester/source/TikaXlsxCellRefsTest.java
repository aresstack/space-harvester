package com.aresstack.spaceharvester.source;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.application.assets.Block;
import com.aresstack.spaceharvester.application.assets.ExtractionLimits;
import com.aresstack.spaceharvester.application.assets.ExtractionStatus;
import com.aresstack.spaceharvester.application.assets.Location;
import com.aresstack.spaceharvester.application.assets.StructuredDocument;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.GregorianCalendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.SheetVisibility;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCell;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFComment;
import org.apache.poi.xssf.usermodel.XSSFFormulaEvaluator;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Zellbezüge aus dem Original bei {@code .xlsx}/{@code .xlsm} (ADR 0026, AP10; Architekt 13.11.2026): Zeilennummer nur
 * aus {@code r} oder der Folge im Blatt-XML, nie aus der Ausgabe gezählt.
 */
class TikaXlsxCellRefsTest {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    @TempDir
    Path directory;

    @Test
    void cellsGetTheirReferenceAcrossGapsAndFromAFirstRowOtherThanOne() throws IOException {
        Path xlsx = directory.resolve("luecken.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            XSSFSheet kosten = workbook.createSheet("Kosten");
            kosten.createRow(2).createCell(1).setCellValue("B3-Wert");
            kosten.createRow(16).createCell(3).setCellValue("D17-Wert");
            kosten.createRow(1017).createCell(26).setCellValue("AA1018-Wert");
            workbook.createSheet("Personen").createRow(0).createCell(0).setCellValue("Leitstelle");
            workbook.write(out);
        }

        Map<String, String> refs = refs(extract(xlsx, XLSX));

        assertThat(refs).containsEntry("B3-Wert", "B3").containsEntry("D17-Wert", "D17")
                .as("Lücke von 1.000 Zeilen, Spalte AA").containsEntry("AA1018-Wert", "AA1018")
                .containsEntry("Leitstelle", "A1");
    }

    @Test
    void formulasDatesMergedCellsHiddenSheetsAndCommentsKeepTheirCell() throws IOException {
        Path xlsx = directory.resolve("arten.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            XSSFSheet sheet = workbook.createSheet("Arten");
            var first = sheet.createRow(0);
            first.createCell(0).setCellValue(2);
            first.createCell(1).setCellValue(3);
            first.createCell(2).setCellFormula("A1*B1");
            new XSSFFormulaEvaluator(workbook).evaluateAll();
            XSSFCellStyle date = workbook.createCellStyle();
            date.setDataFormat(workbook.createDataFormat().getFormat("dd.mm.yyyy"));
            XSSFCell day = sheet.createRow(1).createCell(1);
            day.setCellValue(new GregorianCalendar(2026, 10, 13));
            day.setCellStyle(date);
            sheet.createRow(2).createCell(0).setCellValue("VERBUNDEN");
            sheet.addMergedRegion(new CellRangeAddress(2, 3, 0, 2));
            sheet.createRow(4).createCell(3).setCellFormula("SUM(A1:B1)"); // ohne Neuberechnung: kein Wert
            XSSFCell commented = sheet.createRow(5).createCell(1);
            commented.setCellValue("MITKOMMENTAR");
            XSSFComment comment = sheet.createDrawingPatriarch().createCellComment(new XSSFClientAnchor());
            comment.setString("KOMMENTARTEXT");
            commented.setCellComment(comment);
            sheet.createRow(6).createCell(2).setCellValue("NACH-FORMEL");
            workbook.createSheet("Versteckt").createRow(3).createCell(4).setCellValue("GEHEIMBLATT");
            workbook.setSheetHidden(1, true);
            workbook.createSheet("SehrVersteckt").createRow(0).createCell(1).setCellValue("SEHRGEHEIM");
            workbook.setSheetVisibility(2, SheetVisibility.VERY_HIDDEN);
            workbook.write(out);
        }

        TikaDocumentStructureTest.Recording sink = extract(xlsx, XLSX);
        List<Block.Cell> cells = cells(sink);

        assertThat(cell(cells, "6").cellRef()).as("gespeicherter Formelwert").contains("C1");
        assertThat(cells).filteredOn(cell -> cell.text().contains("2026")).singleElement()
                .satisfies(cell -> assertThat(cell.cellRef()).contains("B2"));
        assertThat(cell(cells, "VERBUNDEN").cellRef()).as("verbundene Zelle oben links").contains("A3");
        assertThat(cells).filteredOn(cell -> cell.text().contains("MITKOMMENTAR")).singleElement()
                .as("Kommentar steht im Text seiner Zelle").satisfies(cell -> {
                    assertThat(cell.text()).contains("KOMMENTARTEXT");
                    assertThat(cell.cellRef()).contains("B6");
                });
        assertThat(cell(cells, "NACH-FORMEL").cellRef()).as("Formel ohne Wert verschiebt nichts").contains("C7");
        assertThat(cell(cells, "GEHEIMBLATT").cellRef()).contains("E4");
        assertThat(cell(cells, "SEHRGEHEIM").cellRef()).contains("B1");
        assertThat(cells).filteredOn(cell -> cell.text().isEmpty())
                .allSatisfy(cell -> assertThat(cell.cellRef()).isPresent());
        assertThat(sink.text()).as("Bezug nur am Block, nicht im Text").doesNotContain("C7").doesNotContain("E4");
    }

    @Test
    void aCellBehindABlockBoundaryKeepsItsReference() throws IOException {
        Path xlsx = directory.resolve("lang.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            XSSFSheet sheet = workbook.createSheet("Lang");
            for (int i = 0; i < 200; i++) {
                var row = sheet.createRow(10 + 2 * i);
                row.createCell(0).setCellValue("Zeile " + i);
                row.createCell(2).setCellValue("x".repeat(20));
            }
            workbook.write(out);
        }
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();

        new TikaDocumentStructure(1, 500).extract(xlsx, "lang.xlsx", XLSX, ExtractionLimits.DEFAULTS, sink);

        List<Block.Table> tables = tables(sink);
        assertThat(tables).hasSizeGreaterThan(1);
        assertThat(tables.get(tables.size() - 1).continued()).isTrue();
        Block.Cell last = cell(cells(sink), "Zeile 199");
        assertThat(last.cellRef()).contains("A409");
        assertThat(tables.get(tables.size() - 1).cells()).allSatisfy(cell -> assertThat(cell.cellRef()).isPresent());
    }

    @Test
    void aSheetWhoseRowsCannotBeMatchedLosesReferencesButTheNextSheetHasThem() throws IOException {
        Path written = directory.resolve("roh.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(written)) {
            XSSFSheet first = workbook.createSheet("Erstes");
            first.createRow(4).createCell(0).setCellValue("FUENF");
            first.createRow(6).createCell(0).setCellValue("SIEBEN");
            workbook.createSheet("Zweites").createRow(1).createCell(1).setCellValue("ZWEITES-B2");
            workbook.write(out);
        }
        // Zeilen rückwärts nummeriert: ab der zweiten Zeile im ersten Blatt nicht eindeutig.
        Path xlsx = rewrite(written, directory.resolve("rueckwaerts.xlsx"), "xl/worksheets/sheet1.xml",
                xml -> xml.replace("r=\"7\"", "r=\"3\"").replace("r=\"A7\"", "r=\"A3\""));

        TikaDocumentStructureTest.Recording sink = extract(xlsx, XLSX);
        List<Block.Cell> cells = cells(sink);

        assertThat(cell(cells, "FUENF").cellRef()).as("vor dem Ausfall belegt").contains("A5");
        assertThat(cell(cells, "SIEBEN").cellRef()).isEmpty();
        assertThat(cell(cells, "ZWEITES-B2").cellRef()).contains("B2");
    }

    @Test
    void macroEnabledWorkbooksAreMatchedButLegacyExcelIsNot() throws IOException {
        Path xls = directory.resolve("alt.xls");
        try (HSSFWorkbook workbook = new HSSFWorkbook(); OutputStream out = Files.newOutputStream(xls)) {
            workbook.createSheet("Alt").createRow(3).createCell(2).setCellValue("ALTWERT");
            workbook.write(out);
        }
        Path xlsx = directory.resolve("makro.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook(); OutputStream out = Files.newOutputStream(xlsx)) {
            workbook.createSheet("Makro").createRow(3).createCell(2).setCellValue("MAKROWERT");
            workbook.write(out);
        }
        // Gleiches Paket mit dem Inhaltstyp einer Mappe mit Makros.
        Path xlsm = rewrite(xlsx, directory.resolve("makro.xlsm"), "[Content_Types].xml", xml -> xml.replace(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml",
                "application/vnd.ms-excel.sheet.macroEnabled.main+xml"));

        assertThat(cell(cells(extract(xls, "application/vnd.ms-excel")), "ALTWERT").cellRef()).isEmpty();
        TikaDocumentStructureTest.Recording macro = extract(xlsm, "application/vnd.ms-excel.sheet.macroenabled.12");
        assertThat(cell(cells(macro), "MAKROWERT").cellRef()).contains("C4");
    }

    @Test
    void referencesAreWrittenAsColumnLettersAndRow() {
        assertThat(XlsxCellRefs.ref(0, 1)).isEqualTo("A1");
        assertThat(XlsxCellRefs.ref(25, 7)).isEqualTo("Z7");
        assertThat(XlsxCellRefs.ref(26, 1048576)).isEqualTo("AA1048576");
        assertThat(XlsxCellRefs.ref(16383, 3)).isEqualTo("XFD3");
        assertThat(XlsxCellRefs.column("XFD3", 3)).isEqualTo(16383);
        assertThat(XlsxCellRefs.column("AA12", 12)).isEqualTo(26);
    }

    private TikaDocumentStructureTest.Recording extract(Path file, String type) throws IOException {
        TikaDocumentStructureTest.Recording sink = new TikaDocumentStructureTest.Recording();
        StructuredDocument result = new TikaDocumentStructure().extract(file, file.getFileName().toString(), type,
                ExtractionLimits.DEFAULTS, sink);
        assertThat(result.outcome().status()).isEqualTo(ExtractionStatus.OK);
        return sink;
    }

    private static List<Block.Table> tables(TikaDocumentStructureTest.Recording sink) {
        return sink.blocks.stream().filter(Block.Table.class::isInstance).map(Block.Table.class::cast).toList();
    }

    private static List<Block.Cell> cells(TikaDocumentStructureTest.Recording sink) {
        return tables(sink).stream().flatMap(table -> table.cells().stream()).toList();
    }

    private static Block.Cell cell(List<Block.Cell> cells, String text) {
        return cells.stream().filter(cell -> cell.text().equals(text)).findFirst()
                .orElseThrow(() -> new AssertionError("keine Zelle " + text));
    }

    private static Map<String, String> refs(TikaDocumentStructureTest.Recording sink) {
        Map<String, String> refs = new LinkedHashMap<>();
        for (Block.Cell cell : cells(sink)) {
            if (!cell.text().isEmpty()) {
                refs.put(cell.text(), cell.cellRef().orElse(null));
            }
        }
        return refs;
    }

    private static Path rewrite(Path source, Path target, String part, UnaryOperator<String> change)
            throws IOException {
        try (ZipFile zip = new ZipFile(source.toFile());
                ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(target))) {
            for (ZipEntry entry : java.util.Collections.list(zip.entries())) {
                out.putNextEntry(new ZipEntry(entry.getName()));
                try (InputStream in = zip.getInputStream(entry)) {
                    byte[] bytes = in.readAllBytes();
                    if (entry.getName().equals(part)) {
                        bytes = change.apply(new String(bytes, StandardCharsets.UTF_8))
                                .getBytes(StandardCharsets.UTF_8);
                    }
                    out.write(bytes);
                }
                out.closeEntry();
            }
        }
        return target;
    }
}
