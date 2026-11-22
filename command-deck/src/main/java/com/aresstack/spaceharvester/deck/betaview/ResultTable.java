package com.aresstack.spaceharvester.deck.betaview;

import java.util.List;

/**
 * Die Trefferliste einer Suche: Spalten so, wie der Server sie überschreibt, und je Zeile die Zellen samt der Aktion,
 * die das Dokument öffnet.
 *
 * @param columns Spaltenüberschriften
 * @param rows    Zeilen
 */
public record ResultTable(List<String> columns, List<Row> rows) {

    public ResultTable {
        columns = List.copyOf(columns);
        rows = List.copyOf(rows);
    }

    public static ResultTable empty() {
        return new ResultTable(List.of(), List.of());
    }

    /**
     * Eine Trefferzeile.
     *
     * @param cells  Zellen in der Reihenfolge der Spalten
     * @param action relative Aktion zum Öffnen, etwa {@code open.action?index=0&source=resulttable}; leer, wenn die
     *               Zeile keinen Verweis hat
     */
    public record Row(List<String> cells, String action) {

        public Row {
            cells = List.copyOf(cells);
            action = action == null ? "" : action;
        }

        /** Die Zelle {@code index} oder leer. */
        public String cell(int index) {
            return index >= 0 && index < cells.size() ? cells.get(index) : "";
        }

        public boolean openable() {
            return !action.isEmpty();
        }
    }
}
