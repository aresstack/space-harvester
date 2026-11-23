package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditors;
import com.aresstack.spaceharvester.mainframe.dependency.SystemFunctions;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Einstellungen › Systemfunktionen wie {@code SystemFunctionSettingsPanel} in MainframeMate: bekannte z/OS-Programme
 * (IDCAMS, IEFBR14, SORT …) mit Wikipedia-Artikel. Ein {@code PGM=} auf eines davon steht in der JCL-Hierarchie des
 * Editors blau; ein Doppelklick öffnet den Artikel. Gespeichert im App-Zustand, Vorgabe aus MainframeMate.
 */
final class SystemFunctionsSettingsPanel {

    static final String HEADING = "Systemfunktionen";
    static final String KEY = DocumentEditors.SYSTEM_FUNCTIONS_KEY;
    static final String[] COLUMNS = {"Programm", "Wikipedia (de)", "Wikipedia (en)", "Beschreibung"};

    private final List<String[]> rows = new ArrayList<>();
    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final JPanel panel = new JPanel(new BorderLayout(0, 8));

    SystemFunctionsSettingsPanel() {
        load(DocumentEditors.systemFunctions());
        table.getAccessibleContext().setAccessibleName(HEADING);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(0).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setPreferredWidth(320);
        ComposerButton add = Comic.button("Hinzufügen", "Neue Systemfunktion am Ende anfügen");
        ComposerButton remove = Comic.button("Entfernen", "Markierte Zeilen entfernen");
        ComposerButton defaults = Comic.button("Vorgabe", "Liste wie in MainframeMate wiederherstellen");
        add.addActionListener(e -> {
            stopEditing();
            rows.add(new String[] {"", "", "", ""});
            model.fireTableRowsInserted(rows.size() - 1, rows.size() - 1);
            table.editCellAt(rows.size() - 1, 0);
            table.changeSelection(rows.size() - 1, 0, false, false);
        });
        remove.addActionListener(e -> {
            stopEditing();
            int[] selected = table.getSelectedRows();
            if (selected.length == 0 || !Dialogs.confirm(panel, HEADING,
                    "Wirklich " + selected.length + (selected.length == 1 ? " Systemfunktion" : " Systemfunktionen")
                            + " entfernen?")) {
                return;
            }
            for (int i = selected.length - 1; i >= 0; i--) {
                rows.remove(selected[i]);
            }
            model.fireTableDataChanged();
            store();
        });
        defaults.addActionListener(e -> {
            stopEditing();
            if (Dialogs.confirm(panel, HEADING, "Wirklich auf die Vorgabe zurücksetzen? Eigene Einträge fallen weg.")) {
                load(SystemFunctions.defaults());
                model.fireTableDataChanged();
                store();
            }
        });
        panel.setOpaque(false);
        panel.add(Comic.wrapped("Ein PGM= auf eine dieser Funktionen erscheint in den Beziehungen des Editors blau; "
                + "ein Doppelklick öffnet den Wikipedia-Artikel (deutsch bevorzugt).", Comic.TEXT, 12.5f),
                BorderLayout.NORTH);
        JComponent scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(640, 260));
        panel.add(scroll, BorderLayout.CENTER);
        JPanel buttons = Comic.row(add, remove, defaults);
        buttons.setOpaque(false);
        panel.add(buttons, BorderLayout.SOUTH);
    }

    JComponent component() {
        return panel;
    }

    /** Für Tests. */
    int rowCount() {
        return rows.size();
    }

    void set(int row, int column, String value) {
        model.setValueAt(value, row, column);
    }

    private void load(SystemFunctions functions) {
        rows.clear();
        for (SystemFunctions.Entry entry : functions.entries()) {
            rows.add(new String[] {entry.name(), entry.wikiTitleDe(), entry.wikiTitleEn(), entry.description()});
        }
    }

    private void stopEditing() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private void store() {
        List<SystemFunctions.Entry> entries = new ArrayList<>();
        for (String[] row : rows) {
            entries.add(new SystemFunctions.Entry(row[0], row[1], row[2], row[3]));
        }
        SystemFunctions functions = new SystemFunctions(entries);
        AppState.shared().put(KEY, functions.serialize());
        DocumentEditors.systemFunctions(functions);
    }

    private final class Model extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            return rows.get(row)[column];
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return true;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            String text = value == null ? "" : value.toString().trim();
            rows.get(row)[column] = column == 0 ? text.toUpperCase(java.util.Locale.ROOT) : text;
            fireTableCellUpdated(row, column);
            store();
        }
    }
}
