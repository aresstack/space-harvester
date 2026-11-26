package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch;
import com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.*;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Future;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Search stored program facts and navigate only to their exact source revision. */
public final class StoredProgramSearchDialog {
    private static final int PAGE_SIZE = 300;
    private record Choice<T>(T value, String label) {
        @Override public String toString() { return label; }
    }

    private final Snapshot snapshot;
    private final JComboBox<Mode> modes = new JComboBox<>(Mode.values());
    private final JComboBox<Choice<Language>> languages = new JComboBox<>();
    private final JComboBox<Choice<String>> sources = new JComboBox<>();
    private final JTextField query = new JTextField(26);
    private final Results rows = new Results();
    private final JTable table = Comic.table(new JTable(rows));
    private final JLabel count = new JLabel();
    private final JButton previous = new JButton("Zurück");
    private final JButton next = new JButton("Weitere Treffer");
    private final JButton root = new JButton("Als Startprogramm verwenden");
    private final JCheckBox openCandidate = new JCheckBox("Mögliches Ziel öffnen");
    private long offset;
    private int generation;
    private Future<?> running;

    public StoredProgramSearchDialog(Snapshot snapshot, List<String> sourceIds, String initialSource) {
        this.snapshot = snapshot;
        languages.addItem(new Choice<>(null, "Alle Sprachen"));
        for (var language : Language.values()) languages.addItem(new Choice<>(language, language.name()));
        sources.addItem(new Choice<>(null, "Alle Quellen"));
        for (String id : sourceIds) {
            var choice = new Choice<>(id, id);
            sources.addItem(choice);
            if (id.equals(initialSource)) sources.setSelectedItem(choice);
        }
        modes.setRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Mode mode) setText(label(mode));
                return this;
            }
        });
    }

    public Optional<Fact> show(Component parent) {
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var input = new JPanel(new BorderLayout(6, 0));
        var filters = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        filters.add(modes);
        filters.add(languages);
        filters.add(sources);
        input.add(filters, BorderLayout.WEST);
        input.add(query, BorderLayout.CENTER);
        var find = new JButton("Suchen");
        find.addActionListener(event -> firstPage());
        input.add(find, BorderLayout.EAST);
        var header = new JPanel(new BorderLayout(0, 4));
        var coverage = new JTextArea(String.join("\n", snapshot.coverage()));
        coverage.setEditable(false);
        coverage.setLineWrap(true);
        coverage.setWrapStyleWord(true);
        var coverageScroll = Comic.scroll(coverage);
        coverageScroll.setPreferredSize(new Dimension(900, 75));
        header.add(coverageScroll, BorderLayout.NORTH);
        header.add(input, BorderLayout.SOUTH);
        var footer = new JPanel(new BorderLayout(6, 0));
        footer.add(previous, BorderLayout.WEST);
        footer.add(count, BorderLayout.CENTER);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        actions.add(root);
        actions.add(openCandidate);
        actions.add(next);
        footer.add(actions, BorderLayout.EAST);
        var body = new JPanel(new BorderLayout(0, 8));
        body.add(header, BorderLayout.NORTH);
        var scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(1040, 350));
        body.add(scroll, BorderLayout.CENTER);
        body.add(footer, BorderLayout.SOUTH);
        bind();
        refresh();
        query.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && query.isShowing()) {
                SwingUtilities.invokeLater(query::requestFocusInWindow);
            }
        });
        boolean confirmed = Dialogs.formOverlay(parent, "Programmsuche im Wissensbestand", body, "Fundstelle öffnen");
        generation++;
        if (running != null) running.cancel(true);
        var match = selected();
        return !confirmed || match == null ? Optional.empty()
                : Optional.of(openCandidate.isSelected() && match.candidate() != null ? match.candidate() : match.fact());
    }

    private void bind() {
        root.setEnabled(false);
        openCandidate.setEnabled(false);
        table.getSelectionModel().addListSelectionListener(event -> {
            var match = selected();
            root.setEnabled(match != null && match.rootSelector() != null);
            openCandidate.setEnabled(match != null && match.candidate() != null);
            if (!openCandidate.isEnabled()) openCandidate.setSelected(false);
        });
        root.addActionListener(event -> {
            var match = selected();
            if (match != null && match.rootSelector() != null) {
                query.setText(match.rootSelector());
                firstPage();
            }
        });
        query.addActionListener(event -> firstPage());
        modes.addActionListener(event -> firstPage());
        languages.addActionListener(event -> firstPage());
        sources.addActionListener(event -> firstPage());
        previous.addActionListener(event -> { offset = Math.max(0, offset - PAGE_SIZE); refresh(); });
        next.addActionListener(event -> { offset += PAGE_SIZE; refresh(); });
    }

    private Match selected() {
        int index = table.getSelectedRow();
        return index < 0 ? null : rows.at(table.convertRowIndexToModel(index));
    }

    private void firstPage() { offset = 0; refresh(); }

    private void refresh() {
        if (running != null) running.cancel(true);
        int current = ++generation;
        long start = offset;
        String text = query.getText();
        Mode mode = (Mode) modes.getSelectedItem();
        var language = languages.getItemAt(languages.getSelectedIndex()).value();
        var source = sources.getItemAt(sources.getSelectedIndex()).value();
        rows.replace(List.of());
        previous.setEnabled(false);
        next.setEnabled(false);
        if ((mode == Mode.COBOL_CALLERS_HIERARCHY || mode == Mode.COBOL_CALLEES_HIERARCHY)
                && (source == null || !snapshot.cobol().containsKey(source))) {
            count.setText("Für die COBOL-Hierarchie eine FTP-Quelle auswählen.");
            return;
        }
        count.setText("Suche läuft …");
        running = Background.start(() -> snapshot.search(mode, language, source, text, PAGE_SIZE, start), result -> {
            if (current != generation) return;
            rows.replace(result.hits());
            long end = Math.min(start + PAGE_SIZE, result.total());
            count.setText((result.hits().isEmpty() ? 0 : start + 1) + "–" + end + " von " + result.total()
                    + (mode == Mode.CANDIDATES ? " möglichen Beziehungen (nicht aufgelöst)" : " Fundstellen"));
            previous.setEnabled(start > 0);
            next.setEnabled(end < result.total());
        }, error -> { if (current == generation) count.setText("Suche fehlgeschlagen."); });
    }

    private static String label(Mode mode) {
        return switch (mode) {
            case ALL -> "Alles";
            case OBJECTS -> "Objekte / Programme";
            case COPYBOOKS -> "Copybooks / Copycodes";
            case COPY_USAGE -> "Copybook- / Copycode-Verwendungen";
            case DATA -> "Datenfelder";
            case CALLERS -> "Aufrufer (statisch)";
            case CALLEES -> "Aufgerufene Objekte";
            case DEPENDENCIES -> "Abhängigkeiten";
            case RESOURCES -> "Datasets / Datenzugriffe";
            case CANDIDATES -> "Mögliche Zielobjekte";
            case COBOL_CALLERS_HIERARCHY -> "COBOL-Aufrufhierarchie eingehend";
            case COBOL_CALLEES_HIERARCHY -> "COBOL-Aufrufhierarchie ausgehend";
        };
    }

    private static final class Results extends AbstractTableModel {
        private final String[] columns = {"Sprache", "Art", "Name / Ziel", "Objekt", "Quelle", "Fundstelle", "Zeile", "Details", "Mögliches Ziel"};
        private List<Match> hits = List.of();
        void replace(List<Match> next) { hits = next; fireTableDataChanged(); }
        Match at(int row) { return hits.get(row); }
        @Override public int getRowCount() { return hits.size(); }
        @Override public int getColumnCount() { return columns.length; }
        @Override public String getColumnName(int col) { return columns[col]; }
        @Override public Object getValueAt(int row, int col) {
            var match = hits.get(row);
            var fact = match.fact();
            return switch (col) {
                case 0 -> fact.language();
                case 1 -> fact.kind();
                case 2 -> fact.name();
                case 3 -> fact.owner();
                case 4 -> fact.location().key().sourceId();
                case 5 -> fact.location().path();
                case 6 -> fact.location().line() > 0 ? fact.location().line() : "–";
                case 7 -> (fact.partial() ? "Teilanalyse · " : "") + fact.detail();
                default -> match.candidate() == null ? "" : match.candidate().location().key().sourceId()
                        + " / " + match.candidate().location().path() + ":" + match.candidate().location().line();
            };
        }
    }
}
