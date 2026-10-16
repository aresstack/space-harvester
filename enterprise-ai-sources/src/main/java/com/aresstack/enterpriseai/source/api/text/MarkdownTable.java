package com.aresstack.enterpriseai.source.api.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the canonical text of a source table as a GitHub-flavoured Markdown table (issue #121), shared by the HTML
 * based source adapters so that the reader and the Markdown, Word and PDF exports all see a real table.
 *
 * <p>Fill it row by row with cells in source order, including empty cells and their {@code colspan}/{@code rowspan}.
 * The table lays them out on a grid: a spanned cell repeats its text in every position it covers, so no value slips
 * into a neighbouring column. The first row is the header row (GFM needs one). Pipes in cell text are escaped and
 * whitespace collapses to single spaces; rows without any text are dropped.
 */
public final class MarkdownTable {

    /** Largest {@code colspan} HTML allows; larger values count as this. */
    public static final int MAX_COLSPAN = 1000;

    private final List<List<Cell>> rows = new ArrayList<List<Cell>>();

    /** Start a new row; following {@link #cell} calls fill it. */
    public MarkdownTable row() {
        rows.add(new ArrayList<Cell>());
        return this;
    }

    /** Add a cell spanning one column and one row to the current row. */
    public MarkdownTable cell(String text) {
        return cell(text, 1, 1);
    }

    /**
     * Add a cell to the current row.
     *
     * @param text    visible text of the cell, may be empty
     * @param colspan columns the cell covers (below 1 counts as 1, above {@link #MAX_COLSPAN} as that)
     * @param rowspan rows the cell covers; {@code 0} means all remaining rows as in HTML, below 0 counts as 1, and
     *                a span never reaches past the last row of the table
     */
    public MarkdownTable cell(String text, int colspan, int rowspan) {
        if (rows.isEmpty()) {
            row();
        }
        rows.get(rows.size() - 1).add(new Cell(text, Math.min(MAX_COLSPAN, Math.max(1, colspan)),
                rowspan < 0 ? 1 : rowspan));
        return this;
    }

    /** The table laid out on its grid: every row has the same number of cells, empty positions are {@code ""}. */
    public List<List<String>> grid() {
        Map<Long, String> taken = new HashMap<Long, String>();
        int width = 0;
        for (int row = 0; row < rows.size(); row++) {
            int column = 0;
            for (Cell cell : rows.get(row)) {
                while (taken.containsKey(key(row, column))) {
                    column++;
                }
                int lastRow = cell.rowspan == 0 ? rows.size() : Math.min(rows.size(), row + cell.rowspan);
                for (int r = row; r < lastRow; r++) {
                    for (int c = column; c < column + cell.colspan; c++) {
                        taken.put(key(r, c), cell.text);
                    }
                }
                column += cell.colspan;
            }
            width = Math.max(width, column);
        }
        int height = rows.size();
        for (Long key : taken.keySet()) {
            height = Math.max(height, (int) (key >> 32) + 1);
            width = Math.max(width, (int) (key & 0xffffffffL) + 1);
        }
        List<List<String>> grid = new ArrayList<List<String>>();
        for (int row = 0; row < height; row++) {
            List<String> cells = new ArrayList<String>(width);
            boolean any = false;
            for (int column = 0; column < width; column++) {
                String text = taken.get(key(row, column));
                cells.add(text == null ? "" : text);
                any |= text != null && !text.isEmpty();
            }
            if (any) {
                grid.add(Collections.unmodifiableList(cells));
            }
        }
        return Collections.unmodifiableList(grid);
    }

    /** The GFM table: header row, separator row, data rows; empty when the table has no text. */
    public String toMarkdown() {
        List<List<String>> grid = grid();
        if (grid.isEmpty()) {
            return "";
        }
        int width = grid.get(0).size();
        StringBuilder out = new StringBuilder();
        line(out, grid.get(0));
        out.append('|');
        for (int column = 0; column < width; column++) {
            out.append(" --- |");
        }
        out.append('\n');
        for (int row = 1; row < grid.size(); row++) {
            line(out, grid.get(row));
        }
        return out.toString();
    }

    /** One row as plain text for structural blocks: cells joined by {@code " | "}, empty cells kept in place. */
    public static String rowText(List<String> cells) {
        StringBuilder line = new StringBuilder();
        for (int column = 0; column < cells.size(); column++) {
            if (column > 0) {
                line.append(" | ");
            }
            line.append(cells.get(column));
        }
        return line.toString().trim();
    }

    private static void line(StringBuilder out, List<String> cells) {
        out.append('|');
        for (String cell : cells) {
            out.append(' ').append(escape(cell)).append(cell.isEmpty() ? "|" : " |");
        }
        out.append('\n');
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("|", "\\|");
    }

    private static long key(int row, int column) {
        return ((long) row << 32) | (column & 0xffffffffL);
    }

    private static final class Cell {
        final String text;
        final int colspan;
        final int rowspan;

        Cell(String text, int colspan, int rowspan) {
            this.text = text == null ? "" : text.replaceAll("[\\s\\u00A0]+", " ").trim();
            this.colspan = colspan;
            this.rowspan = rowspan;
        }
    }
}
