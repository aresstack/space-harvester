package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;

import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/**
 * Eine bearbeitbare Liste von Hostnamen oder Mustern als Tabelle mit „Hinzufügen“ und „Entfernen“; bearbeitet direkt
 * die {@code List<String>} des Cores, ohne Umweg über eine Textzeile.
 */
final class HostListEditor extends JPanel {

    private final DefaultTableModel model = new DefaultTableModel(new Object[] {"Hostname oder Muster"}, 0);
    final JTable table = Comic.table(new JTable(model));
    final ComposerButton add = Comic.button("Hinzufügen", "Neue Zeile, z. B. intranet.example.org oder *.intern");
    final ComposerButton remove = Comic.button("Entfernen", "Gewählte Zeilen entfernen");

    HostListEditor() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        var scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(420, 110));
        add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.setOpaque(false);
        buttons.add(add);
        buttons.add(remove);
        add(buttons, BorderLayout.SOUTH);
        add.addActionListener(e -> {
            commit();
            model.addRow(new Object[] {""});
            int row = model.getRowCount() - 1;
            table.changeSelection(row, 0, false, false);
            table.editCellAt(row, 0);
            if (table.getEditorComponent() != null) {
                table.getEditorComponent().requestFocusInWindow();
            }
        });
        remove.addActionListener(e -> {
            commit();
            int[] rows = table.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                model.removeRow(table.convertRowIndexToModel(rows[i]));
            }
        });
    }

    void hosts(List<String> hosts) {
        model.setRowCount(0);
        hosts.forEach(host -> model.addRow(new Object[] {host}));
    }

    /** Die Einträge in Reihenfolge; eine gerade bearbeitete Zelle zählt mit, leere Zeilen nicht. */
    List<String> hosts() {
        commit();
        return entries();
    }

    /** Zahl der übernommenen Einträge, ohne eine laufende Bearbeitung zu beenden. */
    int count() {
        return entries().size();
    }

    void onChange(Runnable listener) {
        model.addTableModelListener(e -> listener.run());
    }

    private List<String> entries() {
        List<String> hosts = new ArrayList<>();
        for (int row = 0; row < model.getRowCount(); row++) {
            Object value = model.getValueAt(row, 0);
            if (value != null && !value.toString().isBlank()) {
                hosts.add(value.toString().trim());
            }
        }
        return hosts;
    }

    private void commit() {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }
}
