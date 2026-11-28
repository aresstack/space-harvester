package com.aresstack.spaceharvester.app.ui.content.viewer;

import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Font;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/** Render one or more locally parsed worksheets as read-only Swing tables. */
final class SpreadsheetPreviewPanel extends JPanel {

    private final JTabbedPane tabs = new JTabbedPane();

    SpreadsheetPreviewPanel(SpreadsheetPreviewData data) {
        super(new BorderLayout());
        setOpaque(true);
        setBackground(AppColors.of("comic.fieldBg"));
        if (data.tabs.size() == 1) {
            add(sheet(data.tabs.get(0)), BorderLayout.CENTER);
        } else {
            for (SpreadsheetPreviewData.Tab preview : data.tabs) {
                tabs.addTab(preview.name, sheet(preview));
            }
            add(tabs, BorderLayout.CENTER);
        }
    }

    int numberOfSheets() {
        return tabs.getTabCount() == 0 ? 1 : tabs.getTabCount();
    }

    private static JPanel sheet(SpreadsheetPreviewData.Tab sheet) {
        JPanel content = new JPanel(new BorderLayout());
        SheetTableModel model = new SheetTableModel(sheet);
        JTable table = new JTable(model);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        table.setRowHeight(Math.max(24, table.getRowHeight()));
        table.setCellSelectionEnabled(true);
        table.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        table.getTableHeader().setReorderingAllowed(false);
        table.getColumnModel().getColumn(0).setPreferredWidth(60);
        for (int column = 1; column < model.getColumnCount(); column++) {
            table.getColumnModel().getColumn(column).setPreferredWidth(150);
        }
        JScrollPane scroll = com.aresstack.spaceharvester.app.ui.Comic.comicBars(new JScrollPane(table));
        scroll.setBorder(BorderFactory.createEmptyBorder());
        content.add(scroll, BorderLayout.CENTER);
        if (sheet.truncated) {
            JLabel truncated = new JLabel("Vorschau begrenzt auf " + SpreadsheetPreviewData.MAX_ROWS
                    + " Zeilen und " + SpreadsheetPreviewData.MAX_COLUMNS
                    + " Spalten. Die Originaldatei bleibt vollständig.", SwingConstants.LEFT);
            truncated.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
            content.add(truncated, BorderLayout.SOUTH);
        }
        return content;
    }

    /** Expose the already bounded preview data without duplicating every table cell on the EDT. */
    private static final class SheetTableModel extends AbstractTableModel {
        private final SpreadsheetPreviewData.Tab sheet;

        private SheetTableModel(SpreadsheetPreviewData.Tab sheet) {
            this.sheet = sheet;
        }

        @Override
        public int getRowCount() {
            return sheet.rows.size();
        }

        @Override
        public int getColumnCount() {
            return sheet.columns + 1;
        }

        @Override
        public String getColumnName(int column) {
            return column == 0 ? "#" : SpreadsheetPreviewData.columnName(column - 1);
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == 0 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            if (column == 0) {
                return row + 1;
            }
            String[] values = sheet.rows.get(row);
            return column - 1 < values.length ? values[column - 1] : "";
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return false;
        }
    }
}
