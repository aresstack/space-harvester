package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.StoredCobolSearch;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Search one stored knowledge source without acquiring or analyzing any new program. */
final class StoredCobolSearchDialog {
    private static final int PAGE_SIZE = 300;
    private final StoredCobolSearch.Snapshot snapshot;
    private final JComboBox<CobolSearch.Mode> modes = new JComboBox<>(CobolSearch.Mode.values());
    private final JTextField query = new JTextField(26);
    private final Results rows = new Results();
    private final JTable table = Comic.table(new JTable(rows));
    private final JLabel count = new JLabel();
    private final JButton previous = new JButton("Zurück");
    private final JButton next = new JButton("Weitere Treffer");
    private final JButton root = new JButton("Als Startprogramm verwenden");
    private int offset;
    private int generation;
    private Future<?> running;

    StoredCobolSearchDialog(StoredCobolSearch.Snapshot snapshot) {
        this.snapshot = snapshot;
    }

    Optional<CobolSearch.Fact> show(Component parent) {
        modes.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof CobolSearch.Mode mode) setText(label(mode));
                return this;
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JPanel body = buildBody();
        bindActions();
        refresh();
        boolean confirmed = Dialogs.formOverlay(parent, "COBOL-Suche im Wissensbestand", body, "Fundstelle öffnen");
        generation++;
        if (running != null) running.cancel(true);
        int selected = table.getSelectedRow();
        return confirmed && selected >= 0
                ? Optional.of(rows.at(table.convertRowIndexToModel(selected))) : Optional.empty();
    }

    private JPanel buildBody() {
        var input = new JPanel(new BorderLayout(8, 0));
        input.add(modes, BorderLayout.WEST);
        input.add(query, BorderLayout.CENTER);
        var find = new JButton("Suchen");
        find.addActionListener(event -> firstPage());
        input.add(find, BorderLayout.EAST);
        query.setToolTipText("Für Hierarchien: exakte PROGRAM-ID, Member oder ausgewähltes Startprogramm.");
        var header = new JPanel(new BorderLayout(0, 4));
        header.add(Comic.note(snapshot.coverage()), BorderLayout.NORTH);
        header.add(input, BorderLayout.SOUTH);
        var actions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        actions.add(root);
        actions.add(next);
        var page = new JPanel(new BorderLayout(8, 0));
        page.add(previous, BorderLayout.WEST);
        page.add(count, BorderLayout.CENTER);
        page.add(actions, BorderLayout.EAST);
        var body = new JPanel(new BorderLayout(0, 8));
        body.add(header, BorderLayout.NORTH);
        var scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(900, 350));
        body.add(scroll, BorderLayout.CENTER);
        body.add(page, BorderLayout.SOUTH);
        return body;
    }

    private void bindActions() {
        root.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            int selected = table.getSelectedRow();
            int index = selected < 0 ? -1 : table.convertRowIndexToModel(selected);
            root.setEnabled(index >= 0 && index < rows.getRowCount()
                    && rows.at(index).attributes().containsKey("ROOT_SELECTOR"));
        });
        root.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (selected < 0) return;
            String selector = rows.at(table.convertRowIndexToModel(selected)).attributes().get("ROOT_SELECTOR");
            if (selector != null) {
                query.setText(selector);
                firstPage();
            }
        });
        query.addActionListener(event -> firstPage());
        modes.addActionListener(event -> firstPage());
        previous.addActionListener(event -> {
            offset = Math.max(0, offset - PAGE_SIZE);
            refresh();
        });
        next.addActionListener(event -> {
            offset += PAGE_SIZE;
            refresh();
        });
    }

    private void firstPage() {
        offset = 0;
        refresh();
    }

    private void refresh() {
        if (running != null) running.cancel(true);
        int current = ++generation;
        int start = offset;
        String text = query.getText();
        var mode = (CobolSearch.Mode) modes.getSelectedItem();
        rows.replace(List.of());
        previous.setEnabled(false);
        next.setEnabled(false);
        count.setText("Suche läuft …");
        running = Background.start(() -> snapshot.search().search(mode, text, PAGE_SIZE, start), result -> {
            if (current != generation) return;
            rows.replace(result.hits());
            int first = result.hits().isEmpty() ? 0 : start + 1;
            int end = start + result.hits().size();
            count.setText(first + "–" + end + " von " + result.total() + " Fundstellen");
            previous.setEnabled(start > 0);
            next.setEnabled(end < result.total());
        }, error -> {
            if (current == generation) count.setText("Suche fehlgeschlagen.");
        });
    }

    private static String label(CobolSearch.Mode mode) {
        return switch (mode) {
            case ALL -> "Alles";
            case PROGRAM -> "Programme";
            case COPYBOOK -> "Copybooks / SYSLIB-Member";
            case COPY_USAGE -> "Copybook-Verwendungen";
            case DATA -> "Datenfelder";
            case CALLERS -> "Aufrufer (statisch)";
            case CALLEES -> "Aufgerufene Programme";
            case CALLERS_HIERARCHY -> "Aufrufhierarchie (eingehend)";
            case CALLEES_HIERARCHY -> "Aufrufhierarchie (ausgehend)";
        };
    }

    private static String details(CobolSearch.Fact fact) {
        var attributes = new java.util.LinkedHashMap<>(fact.attributes());
        attributes.keySet().removeAll(List.of("PAGE_ID", "SOURCE_ID", "SOURCE_RAW_SHA256", "PROGRAM_PAGE_ID",
                "PROGRAM_RAW_SHA256"));
        String status = attributes.get("HIERARCHY_STATUS");
        if (status == null) return attributes.toString();
        String label = switch (status) {
            case "ROOT" -> "Startprogramm";
            case "SELECT_ROOT" -> "Startprogramm auswählen";
            case "AMBIGUOUS_CALLER" -> "Aufrufendes Programm nicht eindeutig";
            case "AMBIGUOUS_TARGET" -> "Mehrere mögliche Ziele";
            case "DYNAMIC_TARGET" -> "Dynamisches Ziel";
            case "TARGET_NOT_IN_SNAPSHOT" -> "Ziel nicht im analysierten Bestand";
            case "RESOLVED_IN_SNAPSHOT" -> "Eindeutiger Kandidat im Bestand";
            default -> status;
        };
        String selector = attributes.get("ROOT_SELECTOR");
        if (selector != null) return label + " · " + selector;
        return label + " · " + attributes.getOrDefault("CALLER_CANDIDATES", "") + " → "
                + attributes.getOrDefault("TARGET_CANDIDATES", "")
                + (attributes.containsKey("ALREADY_VISITED") ? " · Bereits erreicht" : "");
    }

    private static final class Results extends AbstractTableModel {
        private final String[] columns = {"Art", "Name", "Programm", "Fundstelle", "Zeile", "Details"};
        private List<CobolSearch.Fact> hits = List.of();
        void replace(List<CobolSearch.Fact> next) {
            hits = next;
            fireTableDataChanged();
        }
        CobolSearch.Fact at(int row) { return hits.get(row); }
        @Override public int getRowCount() { return hits.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }
        @Override public Object getValueAt(int row, int col) {
            var fact = hits.get(row);
            return switch (col) {
                case 0 -> fact.attributes().containsKey("HIERARCHY_DEPTH")
                        ? "Ebene " + fact.attributes().get("HIERARCHY_DEPTH") + " · " + fact.kind() : fact.kind();
                case 1 -> fact.name();
                case 2 -> fact.program();
                case 3 -> fact.source();
                case 4 -> fact.line() > 0 ? fact.line() : "–";
                default -> (fact.partial() ? "Teilanalyse · " : "") + details(fact);
            };
        }
    }
}
