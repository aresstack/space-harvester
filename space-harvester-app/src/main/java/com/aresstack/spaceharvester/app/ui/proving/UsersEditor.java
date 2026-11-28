package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.provingground.model.TestUser;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Die Testbenutzer des Projekts: Name (auf den sich Testfälle und Schritte beziehen), KeePass-Eintrag mit
 * Benutzername, Passwort und gegebenenfalls OTP-Geheimnis, Beschreibung, und wer Standard ist. Im Projekt steht nur
 * der Name des Eintrags; gelesen wird er erst im Lauf.
 */
final class UsersEditor extends JPanel {

    static final String ADD_TIP = "Neuer Testbenutzer";
    static final String DELETE_TIP = "Testbenutzer löschen";

    private final Rows rows = new Rows();
    private final JTable table = Comic.table(new JTable(rows));
    private final JComboBox<String> entries = Comic.combo();
    private final IconButton add = new IconButton(ComposerIcons.plus(12), ADD_TIP).compact();
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP).compact();

    UsersEditor() {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        entries.setEditable(true);
        table.getColumnModel().getColumn(Rows.ENTRY).setCellEditor(new DefaultCellEditor(entries));
        table.getColumnModel().getColumn(Rows.DEFAULT).setMaxWidth(72);
        add.addActionListener(event -> {
            rows.add();
            int last = rows.getRowCount() - 1;
            table.setRowSelectionInterval(last, last);
            table.editCellAt(last, Rows.NAME);
        });
        delete.addActionListener(event -> {
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            rows.remove(table.getSelectedRow());
        });
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(add);
        tools.add(delete);
        add(tools, BorderLayout.NORTH);
        add(Comic.scroll(table), BorderLayout.CENTER);
    }

    /**
     * @param users       die Benutzer des Projekts
     * @param defaultUser der Standardbenutzer, oder null
     * @param changed     bekommt nach jeder Änderung Benutzer und Standardbenutzer
     */
    void show(List<TestUser> users, String defaultUser, BiConsumer<List<TestUser>, String> changed) {
        if (table.isEditing()) {
            table.getCellEditor().cancelCellEditing();
        }
        rows.show(users, defaultUser, changed);
    }

    /** Die Einträge aus KeePass als Auswahl in der Spalte „KeePass-Eintrag“ (eintippen geht immer). */
    void entries(List<String> titles) {
        entries.removeAllItems();
        titles.forEach(entries::addItem);
    }

    Rows rows() {
        return rows;
    }

    /** Eine Zeile; der Name darf beim Bearbeiten kurz leer sein. */
    record Row(String name, String vaultEntry, String description, boolean standard) {
    }

    static final class Rows extends AbstractTableModel {

        static final int NAME = 0;
        static final int ENTRY = 1;
        static final int DESCRIPTION = 2;
        static final int DEFAULT = 3;
        private static final String[] COLUMNS = {"Name", "KeePass-Eintrag", "Beschreibung", "Standard"};

        private final List<Row> rows = new ArrayList<>();
        private BiConsumer<List<TestUser>, String> changed;

        void show(List<TestUser> users, String defaultUser, BiConsumer<List<TestUser>, String> listener) {
            rows.clear();
            changed = listener;
            for (TestUser user : users) {
                rows.add(new Row(user.name(), user.vaultEntry(), user.description(), user.name().equals(defaultUser)));
            }
            fireTableDataChanged();
        }

        void add() {
            rows.add(new Row("", "", "", false));
            int index = rows.size() - 1;
            fireTableRowsInserted(index, index);
        }

        void remove(int row) {
            if (row < 0 || row >= rows.size()) {
                return;
            }
            rows.remove(row);
            fireTableRowsDeleted(row, row);
            publish();
        }

        @Override
        public boolean isCellEditable(int row, int column) {
            return changed != null;
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == DEFAULT ? Boolean.class : Object.class;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (row < 0 || row >= rows.size()) {
                return;
            }
            Row old = rows.get(row);
            String text = value == null ? "" : value.toString().strip();
            Row next = switch (column) {
                case NAME -> new Row(text, old.vaultEntry(), old.description(), old.standard());
                case ENTRY -> new Row(old.name(), text, old.description(), old.standard());
                case DESCRIPTION -> new Row(old.name(), old.vaultEntry(), text, old.standard());
                case DEFAULT -> new Row(old.name(), old.vaultEntry(), old.description(), Boolean.TRUE.equals(value));
                default -> old;
            };
            if (next.equals(old)) {
                return;
            }
            if (column == DEFAULT && next.standard()) {
                // nur einer ist Standard
                for (int i = 0; i < rows.size(); i++) {
                    Row other = rows.get(i);
                    if (other.standard()) {
                        rows.set(i, new Row(other.name(), other.vaultEntry(), other.description(), false));
                    }
                }
            }
            rows.set(row, next);
            fireTableDataChanged();
            publish();
        }

        /** Benutzer mit Namen (ohne Namen noch in Arbeit, nicht gespeichert); der Standard, falls er einen Namen hat. */
        private void publish() {
            if (changed == null) {
                return;
            }
            List<TestUser> users = new ArrayList<>();
            String standard = null;
            List<String> seen = new ArrayList<>();
            for (Row row : rows) {
                if (row.name().isBlank() || seen.contains(row.name())) {
                    continue;
                }
                seen.add(row.name());
                users.add(new TestUser(row.name(), row.vaultEntry(), row.description()));
                if (row.standard()) {
                    standard = row.name();
                }
            }
            changed.accept(users, standard);
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row r = rows.get(row);
            return switch (column) {
                case NAME -> r.name();
                case ENTRY -> r.vaultEntry();
                case DESCRIPTION -> r.description();
                case DEFAULT -> r.standard();
                default -> "";
            };
        }

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
    }
}
