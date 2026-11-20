package com.aresstack.spaceharvester.app.ui.content.viewer;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellReference;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parse spreadsheet previews without modifying or exporting their original files. */
final class SpreadsheetPreviewData {

    static final int MAX_ROWS = 5000;
    static final int MAX_COLUMNS = 96;
    static final int MAX_CELL_CHARACTERS = 2048;

    static final class Tab {
        final String name;
        final List<String[]> rows;
        final int columns;
        final boolean truncated;

        Tab(String name, List<String[]> rows, int columns, boolean truncated) {
            this.name = name;
            this.rows = Collections.unmodifiableList(rows);
            this.columns = columns;
            this.truncated = truncated;
        }
    }

    final List<Tab> tabs;

    private SpreadsheetPreviewData(List<Tab> tabs) {
        this.tabs = Collections.unmodifiableList(tabs);
    }

    static boolean supports(String fileName) {
        String extension = ReaderAttachmentKind.extension(fileName);
        return "xls".equals(extension) || "xlsx".equals(extension) || "xlsm".equals(extension)
                || "csv".equals(extension) || "tsv".equals(extension);
    }

    static SpreadsheetPreviewData read(String fileName, String mediaType, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("Die Tabellendatei ist leer (0 Byte)");
        }
        String extension = ReaderAttachmentKind.extension(fileName);
        if ("csv".equals(extension) || "tsv".equals(extension)
                || ("text/csv".equals(ReaderAttachmentKind.baseType(mediaType)))) {
            return readDelimited(fileName, mediaType, bytes);
        }
        return readWorkbook(bytes);
    }

    private static SpreadsheetPreviewData readWorkbook(byte[] bytes) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            DataFormatter formatter = new DataFormatter(Locale.GERMANY);
            formatter.setUseCachedValuesForFormulaCells(true);
            List<Tab> tabs = new ArrayList<>();
            for (int index = 0; index < workbook.getNumberOfSheets(); index++) {
                Sheet sheet = workbook.getSheetAt(index);
                int total = sheet.getLastRowNum() + 1;
                int columns = 0;
                for (int rowIndex = 0; rowIndex < Math.min(total, MAX_ROWS); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    if (row != null) {
                        columns = Math.max(columns, Math.max(0, row.getLastCellNum()));
                    }
                }
                columns = Math.min(MAX_COLUMNS, columns);
                List<String[]> rows = new ArrayList<>();
                for (int rowIndex = 0; rowIndex < Math.min(total, MAX_ROWS); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    String[] values = new String[columns];
                    for (int col = 0; col < columns; col++) {
                        Cell cell = row == null ? null : row.getCell(col);
                        values[col] = cell == null ? "" : truncate(formatter.formatCellValue(cell));
                    }
                    rows.add(values);
                }
                tabs.add(new Tab(sheet.getSheetName(), rows, columns,
                        total > MAX_ROWS || exceedsColumnLimit(sheet, Math.min(total, MAX_ROWS))));
            }
            return new SpreadsheetPreviewData(tabs);
        } catch (IOException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new IOException("Excel-Datei nicht lesbar: " + failure.getMessage(), failure);
        }
    }

    private static boolean exceedsColumnLimit(Sheet sheet, int total) {
        for (int i = 0; i < total; i++) {
            Row row = sheet.getRow(i);
            if (row != null && row.getLastCellNum() > MAX_COLUMNS) {
                return true;
            }
        }
        return false;
    }

    private static SpreadsheetPreviewData readDelimited(String fileName, String mediaType, byte[] bytes) {
        String text = AttachmentViewerPanel.decodeText(bytes, mediaType);
        char delimiter = "tsv".equals(ReaderAttachmentKind.extension(fileName)) ? '\t'
                : detectDelimiter(text);
        List<String[]> rows = new ArrayList<>();
        List<String> fields = new ArrayList<>();
        StringBuilder value = new StringBuilder();
        boolean quoted = false;
        boolean truncated = false;
        boolean limitedColumns = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    append(value, '"');
                    i++;
                } else if (value.length() == 0 || quoted) {
                    quoted = !quoted;
                } else {
                    append(value, c);
                }
            } else if (!quoted && (c == delimiter || c == '\r' || c == '\n')) {
                if (c == delimiter) {
                    if (fields.size() >= MAX_COLUMNS) {
                        limitedColumns = true;
                    }
                    addField(fields, value);
                } else {
                    if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (fields.size() >= MAX_COLUMNS) {
                        limitedColumns = true;
                    }
                    addField(fields, value);
                    rows.add(fields.toArray(new String[0]));
                    fields.clear();
                    if (rows.size() >= MAX_ROWS) {
                        truncated = i + 1 < text.length();
                        break;
                    }
                }
            } else {
                append(value, c);
            }
        }
        if (!truncated && (value.length() > 0 || !fields.isEmpty())) {
            if (fields.size() >= MAX_COLUMNS) {
                limitedColumns = true;
            }
            addField(fields, value);
            if (rows.size() < MAX_ROWS) {
                rows.add(fields.toArray(new String[0]));
            } else {
                truncated = true;
            }
        }
        int columns = 0;
        for (String[] row : rows) {
            columns = Math.max(columns, row.length);
        }
        columns = Math.min(columns, MAX_COLUMNS);
        return new SpreadsheetPreviewData(Collections.singletonList(
                new Tab(fileName == null ? "CSV" : fileName, rows, columns, truncated || limitedColumns)));
    }

    private static void addField(List<String> fields, StringBuilder value) {
        if (fields.size() < MAX_COLUMNS) {
            fields.add(value.toString());
        }
        value.setLength(0);
    }

    private static void append(StringBuilder value, char character) {
        if (value.length() < MAX_CELL_CHARACTERS) {
            value.append(character);
        }
    }

    private static char detectDelimiter(String text) {
        int[] counts = new int[3];
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (!quoted) {
                if (c == '\r' || c == '\n') {
                    break;
                }
                if (c == ';') {
                    counts[0]++;
                } else if (c == ',') {
                    counts[1]++;
                } else if (c == '\t') {
                    counts[2]++;
                }
            }
        }
        return counts[0] >= counts[1] && counts[0] >= counts[2] && counts[0] > 0 ? ';'
                : counts[2] > counts[1] ? '\t' : ',';
    }

    private static String truncate(String value) {
        return value.length() > MAX_CELL_CHARACTERS
                ? value.substring(0, MAX_CELL_CHARACTERS) + " …" : value;
    }

    static String columnName(int index) {
        return CellReference.convertNumToColString(index);
    }
}
