package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class SpreadsheetPreviewDataTest {

    @Test
    void readsMultipleXlsxSheetsWithTextAndFormattedNumbers() throws Exception {
        try (Workbook workbook = new XSSFWorkbook()) {
            workbook.createSheet("Übersicht").createRow(0).createCell(0).setCellValue("Kosten");
            workbook.getSheetAt(0).createRow(1).createCell(0).setCellValue(1234.5);
            workbook.createSheet("Analyse").createRow(0).createCell(0).setCellValue("Risiko");
            SpreadsheetPreviewData data = SpreadsheetPreviewData.read("bericht.xlsx",
                    "application/octet-stream", bytes(workbook));
            assertThat(data.tabs).hasSize(2);
            assertThat(data.tabs.get(0).name).isEqualTo("Übersicht");
            assertThat(data.tabs.get(0).rows.get(0)[0]).isEqualTo("Kosten");
            assertThat(data.tabs.get(0).rows.get(1)[0]).contains("1234");
            assertThat(data.tabs.get(1).rows.get(0)[0]).isEqualTo("Risiko");
        }
    }

    @Test
    void readsLegacyXlsWithoutAnOnlinePreview() throws Exception {
        try (Workbook workbook = new HSSFWorkbook()) {
            workbook.createSheet("HaVdB").createRow(0).createCell(0).setCellValue("Planung");
            SpreadsheetPreviewData data = SpreadsheetPreviewData.read("plan.xls",
                    "application/vnd.ms-excel", bytes(workbook));
            assertThat(data.tabs.get(0).rows.get(0)[0]).isEqualTo("Planung");
        }
    }

    @Test
    void csvUsesTheCorrectDelimiterAndHonorsQuotedLineBreaks() throws Exception {
        String text = "Name;Bemerkung;Kosten\r\n"
                + "\"HaVdB\";\"erste; zweite Zeile\";123,45\r\n"
                + "\"A\";\"Text mit\nZeilenumbruch und \"\"Anführungszeichen\"\"\";42\r\n";
        SpreadsheetPreviewData data = SpreadsheetPreviewData.read("export.CSV",
                "application/vnd.ms-excel", text.getBytes(StandardCharsets.UTF_8));
        assertThat(data.tabs.get(0).rows).hasSize(3);
        assertThat(data.tabs.get(0).rows.get(1)).containsExactly("HaVdB", "erste; zweite Zeile", "123,45");
        assertThat(data.tabs.get(0).rows.get(2)[1]).contains("Zeilenumbruch", "\"Anführungszeichen\"");
    }

    @Test
    void rendersReadOnlyTableCellsDirectlyFromTheParsedPreview() throws Exception {
        SpreadsheetPreviewData data = SpreadsheetPreviewData.read("results.csv", "text/csv",
                "Titel;Wert\nHaVdB;123".getBytes(StandardCharsets.UTF_8));
        SpreadsheetPreviewPanel panel = new SpreadsheetPreviewPanel(data);
        javax.swing.JPanel sheet = (javax.swing.JPanel) panel.getComponent(0);
        javax.swing.JScrollPane scroll = (javax.swing.JScrollPane)
                ((java.awt.BorderLayout) sheet.getLayout()).getLayoutComponent(java.awt.BorderLayout.CENTER);
        javax.swing.JTable table = (javax.swing.JTable) scroll.getViewport().getView();
        assertThat(table.getValueAt(1, 0)).isEqualTo(2);
        assertThat(table.getValueAt(1, 1)).isEqualTo("HaVdB");
        assertThat(table.getValueAt(1, 2)).isEqualTo("123");
        assertThat(table.getModel().isCellEditable(1, 1)).isFalse();
    }

    @Test
    void tsvShowsDistinctColumnsAndBscwExcelMimeDoesNotMaskCsv() throws Exception {
        SpreadsheetPreviewData data = SpreadsheetPreviewData.read("werte.tsv", "text/plain",
                "A\tB\n1\t2".getBytes(StandardCharsets.UTF_8));
        assertThat(data.tabs.get(0).columns).isEqualTo(2);
        assertThat(data.tabs.get(0).rows.get(1)).containsExactly("1", "2");
        assertThat(ReaderAttachmentKind.of("application/vnd.ms-excel", "export.csv"))
                .isEqualTo(ReaderAttachmentKind.SPREADSHEET);
        assertThat(ReaderAttachmentKind.of("application/octet-stream", "bericht.xlsx"))
                .isEqualTo(ReaderAttachmentKind.SPREADSHEET);
    }

    @Test
    void largeCsvIsTruncatedInTheViewerButNotInItsOriginalBytes() throws Exception {
        StringBuilder csv = new StringBuilder("Index;Wert\n");
        for (int i = 0; i < SpreadsheetPreviewData.MAX_ROWS + 25; i++) {
            csv.append(i).append(';').append("Eintrag").append(i).append('\n');
        }
        byte[] original = csv.toString().getBytes(StandardCharsets.UTF_8);
        SpreadsheetPreviewData data = SpreadsheetPreviewData.read("large.csv", "text/csv", original);
        assertThat(data.tabs.get(0).rows).hasSize(SpreadsheetPreviewData.MAX_ROWS);
        assertThat(data.tabs.get(0).truncated).isTrue();
        assertThat(original.length).isGreaterThan(SpreadsheetPreviewData.MAX_ROWS);
    }

    @Test
    void rejectsEmptyFilesWithoutPretendingTheyContainAnEmptyTable() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> SpreadsheetPreviewData.read("empty.xlsx", null, new byte[0]))
                .isInstanceOf(java.io.IOException.class).hasMessageContaining("0 Byte");
    }

    private static byte[] bytes(Workbook workbook) throws Exception {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            workbook.write(out);
            return out.toByteArray();
        }
    }
}
