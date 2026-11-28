package com.aresstack.spaceharvester.app.ui.proving;

import javax.swing.JTable;

/** Tabellen der Editoren: was getippt ist, wird gespeichert, auch ohne Enter. */
final class Tables {

    private Tables() {
    }

    /** Speichert eine offene Eingabe in die bisherige Zeile (vor dem Wechsel zu anderem Inhalt); ungültig: verwirft. */
    static void commit(JTable table) {
        if (table.isEditing() && !table.getCellEditor().stopCellEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
    }

    /** Eine Eingabe endet beim Verlassen der Tabelle mit Speichern, nicht erst mit Enter. */
    static JTable commitOnFocusLost(JTable table) {
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        return table;
    }
}
