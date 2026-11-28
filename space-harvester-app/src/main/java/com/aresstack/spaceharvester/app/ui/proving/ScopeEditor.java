package com.aresstack.spaceharvester.app.ui.proving;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.provingground.model.Check;
import com.aresstack.spaceharvester.provingground.model.Scope;
import com.aresstack.spaceharvester.provingground.model.ScopeEntry;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;

/**
 * Die Hooks einer Ebene (Projekt, Suite, Testfall) als eine Tabelle, wie in WD4J: je Zeile Hook, aktiv, Name, Wert
 * oder Ausdruck ({@code {{...}}}), Prüfung, Erwartet und Beschreibung. Vorher-Zeilen und Vorlagen setzen Variablen,
 * Nachher-Zeilen werten nach den Schritten aus und prüfen. Jede Änderung geht sofort als neuer {@link Scope} hinaus.
 */
final class ScopeEditor extends JPanel {

    static final String ADD_TIP = "Neue Zeile";
    static final String DELETE_TIP = "Zeile löschen";

    private final Rows rows = new Rows();
    private final JTable table = Tables.commitOnFocusLost(Comic.table(new JTable(rows)));
    private final IconButton add = new IconButton(ComposerIcons.plus(12), ADD_TIP).compact();
    private final IconButton delete = new IconButton(Glyph.TRASH, DELETE_TIP).compact();
    private final JComboBox<Hook> hooks = Comic.combo();

    ScopeEditor() {
        super(new BorderLayout(0, 4));
        setOpaque(false);
        JComboBox<String> checks = Comic.combo();
        checks.setEditable(true);
        for (String type : CHECK_TYPES) {
            checks.addItem(type);
        }
        hooks.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focused) {
                return super.getListCellRendererComponent(list, labelOf(value), index, selected, focused);
            }
        });
        table.getColumnModel().getColumn(Rows.HOOK).setCellEditor(new DefaultCellEditor(hooks));
        table.getColumnModel().getColumn(Rows.HOOK).setCellRenderer(new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            protected void setValue(Object value) {
                super.setValue(labelOf(value));
            }
        });
        table.getColumnModel().getColumn(Rows.CHECK).setCellEditor(new DefaultCellEditor(checks));
        table.getColumnModel().getColumn(Rows.ENABLED).setMaxWidth(56);
        add.addActionListener(event -> {
            rows.add();
            int last = rows.getRowCount() - 1;
            table.setRowSelectionInterval(last, last);
        });
        delete.addActionListener(event -> {
            int row = table.getSelectedRow();
            if (table.isEditing()) {
                table.getCellEditor().cancelCellEditing();
            }
            rows.remove(row);
        });
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        tools.setOpaque(false);
        tools.add(add);
        tools.add(delete);
        add(tools, BorderLayout.NORTH);
        add(Comic.scroll(table), BorderLayout.CENTER);
    }

    /**
     * Zeigt die Hooks einer Ebene.
     *
     * @param scope     die Hooks
     * @param caseLevel Testfall: nur Vorher, Vorlage, Nachher (WD4J); sonst alle fünf Hooks
     * @param changed   bekommt nach jeder Änderung den neuen Stand
     */
    void show(Scope scope, boolean caseLevel, Consumer<Scope> changed) {
        // Typed but not confirmed: saved into the shown level before another one is shown (Codex #1770)
        Tables.commit(table);
        hooks.removeAllItems();
        for (Hook hook : Hook.of(caseLevel)) {
            hooks.addItem(hook);
        }
        rows.show(scope, caseLevel, changed);
    }

    Rows rows() {
        return rows;
    }

    /** The name of a hook as shown on this level (Vorher/Vorlage/Nachher on a test case). */
    String labelOf(Object value) {
        return value instanceof Hook hook ? hook.label(rows.caseLevel()) : value == null ? "" : value.toString();
    }

    /** Die Prüfungen aus WD4J; „!“ davor verneint, frei eingeben geht auch. */
    static final List<String> CHECK_TYPES = List.of("", "regex", "fullregex", "contains", "equals", "starts", "ends",
            "range", "len", "!regex", "!contains", "!equals");

    /** Die Hooks mit ihrem Namen in der Tabelle; auf Testfall-Ebene heißen beforeAll und afterEach Vorher/Nachher. */
    enum Hook {
        BEFORE_ALL("Vorher (einmal)", "Vorher"),
        BEFORE_EACH("Vorher (je Testfall)", null),
        TEMPLATE("Vorlage", "Vorlage"),
        AFTER_EACH("Nachher (je Testfall)", "Nachher"),
        AFTER_ALL("Nachher (einmal)", null);

        private final String label;
        private final String caseLabel;

        Hook(String label, String caseLabel) {
            this.label = label;
            this.caseLabel = caseLabel;
        }

        static List<Hook> of(boolean caseLevel) {
            List<Hook> list = new ArrayList<>();
            for (Hook hook : values()) {
                if (!caseLevel || hook.caseLabel != null) {
                    list.add(hook);
                }
            }
            return list;
        }

        String label(boolean onCase) {
            return onCase && caseLabel != null ? caseLabel : label;
        }

        boolean after() {
            return this == AFTER_EACH || this == AFTER_ALL;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Eine Zeile: Hook und Eintrag. */
    record Row(Hook hook, ScopeEntry entry) {
    }

    /** Die Zeilen der Tabelle und ihre Umrechnung von und nach {@link Scope}. */
    static final class Rows extends AbstractTableModel {

        static final int HOOK = 0;
        static final int ENABLED = 1;
        static final int NAME = 2;
        static final int EXPRESSION = 3;
        static final int CHECK = 4;
        static final int EXPECTED = 5;
        static final int DESCRIPTION = 6;
        private static final String[] COLUMNS = {"Hook", "Aktiv", "Name", "Wert / Ausdruck", "Prüfung", "Erwartet",
                "Beschreibung"};

        private final List<Row> rows = new ArrayList<>();
        private boolean caseLevel;

        boolean caseLevel() {
            return caseLevel;
        }
        private Consumer<Scope> changed;

        void show(Scope scope, boolean onCase, Consumer<Scope> listener) {
            rows.clear();
            caseLevel = onCase;
            changed = listener;
            if (scope != null) {
                rows.addAll(rowsOf(scope));
            }
            fireTableDataChanged();
        }

        /** Die Zeilen eines Scope in Hook-Reihenfolge. */
        static List<Row> rowsOf(Scope scope) {
            List<Row> list = new ArrayList<>();
            scope.beforeAll().forEach(entry -> list.add(new Row(Hook.BEFORE_ALL, entry)));
            scope.beforeEach().forEach(entry -> list.add(new Row(Hook.BEFORE_EACH, entry)));
            scope.templates().forEach(entry -> list.add(new Row(Hook.TEMPLATE, entry)));
            scope.afterEach().forEach(entry -> list.add(new Row(Hook.AFTER_EACH, entry)));
            scope.afterAll().forEach(entry -> list.add(new Row(Hook.AFTER_ALL, entry)));
            return list;
        }

        /** Der Scope aus Zeilen; Zeilen ohne Namen und Ausdruck fallen weg, Vorher-Zeilen verlieren ihre Prüfung. */
        static Scope scopeOf(List<Row> rows) {
            List<List<ScopeEntry>> lists = new ArrayList<>();
            for (int i = 0; i < Hook.values().length; i++) {
                lists.add(new ArrayList<>());
            }
            for (Row row : rows) {
                ScopeEntry entry = row.entry();
                if (entry.name().isEmpty() && entry.expression().isBlank()) {
                    continue;
                }
                if (!row.hook().after() && entry.check() != null) {
                    entry = new ScopeEntry(entry.name(), entry.expression(), entry.enabled(), entry.description(),
                            null);
                }
                lists.get(row.hook().ordinal()).add(entry);
            }
            return new Scope(lists.get(0), lists.get(1), lists.get(2), lists.get(3), lists.get(4));
        }

        void add() {
            Hook hook = rows.isEmpty() ? Hook.BEFORE_ALL : rows.get(rows.size() - 1).hook();
            rows.add(new Row(hook, new ScopeEntry("", "", true, "", null)));
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
            return changed != null && (column != CHECK && column != EXPECTED || rows.get(row).hook().after());
        }

        @Override
        public Class<?> getColumnClass(int column) {
            return column == ENABLED ? Boolean.class : Object.class;
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (row < 0 || row >= rows.size()) {
                return;
            }
            Row old = rows.get(row);
            ScopeEntry e = old.entry();
            String text = value == null ? "" : value.toString();
            Hook hook = old.hook();
            ScopeEntry next = switch (column) {
                case HOOK -> {
                    hook = value instanceof Hook chosen ? chosen : hook;
                    yield e;
                }
                case ENABLED -> e.withEnabled(Boolean.TRUE.equals(value));
                case NAME -> new ScopeEntry(text, e.expression(), e.enabled(), e.description(), e.check());
                case EXPRESSION -> new ScopeEntry(e.name(), text, e.enabled(), e.description(), e.check());
                case CHECK -> new ScopeEntry(e.name(), e.expression(), e.enabled(), e.description(),
                        text.isBlank() ? null : new Check(text, e.check() == null ? "" : e.check().expected()));
                case EXPECTED -> new ScopeEntry(e.name(), e.expression(), e.enabled(), e.description(),
                        new Check(e.check() == null ? "equals" : e.check().type(), text));
                case DESCRIPTION -> e.withDescription(text);
                default -> e;
            };
            Row changedRow = new Row(hook, next);
            if (!changedRow.equals(old)) {
                rows.set(row, changedRow);
                fireTableRowsUpdated(row, row);
                publish();
            }
        }

        private void publish() {
            if (changed != null) {
                changed.accept(scopeOf(rows));
            }
        }

        @Override
        public Object getValueAt(int row, int column) {
            Row r = rows.get(row);
            ScopeEntry e = r.entry();
            return switch (column) {
                // the Hook itself, so the combo editor selects it; shown with its label (hookRenderer)
                case HOOK -> r.hook();
                case ENABLED -> e.enabled();
                case NAME -> e.name();
                case EXPRESSION -> e.expression();
                case CHECK -> e.check() == null ? "" : e.check().type();
                case EXPECTED -> e.check() == null ? "" : e.check().expected();
                case DESCRIPTION -> e.description();
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
