package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.mainframe.cobol.CobolSearch;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;
import javax.swing.table.AbstractTableModel;

/** Keep acquisition explicit and use the same immutable search snapshot for humans and tools. */
final class CobolSearchAction {
    private final FtpDatasetsView view;
    private final IconButton build = new IconButton(Glyph.GRAPH_PLUS, "Inventarisierten COBOL-Bestand analysieren");
    private final IconButton search = new IconButton(Glyph.OVERVIEW, "COBOL-Suche (zweimal Umschalt)");
    private volatile CobolSearchAcquisition.Snapshot snapshot;
    private volatile String principal = "";
    private volatile com.aresstack.spaceharvester.deck.ftp.CobolAnalysisArea area;
    private volatile boolean aiReleased;
    private AtomicBoolean running;
    private boolean obsolete;

    CobolSearchAction(FtpDatasetsView view) {
        this.view = view;
        build.addActionListener(event -> buildOrCancel());
        search.addActionListener(event -> showSearch());
    }

    IconButton buildButton() { return build; }
    IconButton searchButton() { return search; }

    void invalidate() {
        snapshot = null;
        aiReleased = false;
        obsolete = true;
        if (running != null) running.set(true);
    }

    Optional<CobolSearchAcquisition.Snapshot> snapshot() {
        var current = snapshot;
        return current != null && view.inventorySession().state() == ConnectionState.CONNECTED
                && principal.equals(view.inventorySession().user())
                && view.analysisArea().filter(value -> value.equals(area)).isPresent()
                ? Optional.of(current) : Optional.empty();
    }

    Optional<com.aresstack.spaceharvester.app.chat.CobolSearchTools.Snapshot> toolSnapshot() {
        if (!aiReleased) return Optional.empty();
        return snapshot().map(current -> new com.aresstack.spaceharvester.app.chat.CobolSearchTools.Snapshot(
                current.search(), current.coverage()));
    }

    void refresh() {
        boolean mvs = view.inventorySession().mvs();
        build.setVisible(mvs);
        search.setVisible(mvs);
        boolean connected = view.inventorySession().state() == ConnectionState.CONNECTED;
        build.setEnabled(running != null || connected);
        build.marked(running != null);
        build.setToolTipText(running == null ? "Inventarisierten COBOL-Bestand analysieren" : "COBOL-Analyse abbrechen");
        search.setEnabled(connected && snapshot().isPresent());
    }

    private void buildOrCancel() {
        if (running != null) {
            running.set(true);
            view.inventoryStatus("COBOL-Analyse wird abgebrochen …", false);
            return;
        }
        var inventory = view.inventoryAction().result().orElse(null);
        if (inventory == null || !inventory.complete()
                || view.analysisArea().filter(inventory.area()::equals).isEmpty()) {
            Dialogs.view(view, "COBOL-Analyse", Comic.note("Zuerst den aktuellen COBOL-Bestand vollständig inventarisieren."));
            return;
        }
        Boolean release = Dialogs.confirm(view, "COBOL-Bestand analysieren",
                "Quelltexte aus " + inventory.area().absoluteHome() + " und zugehörigen SYSLIBs lesen? "
                + "Je Lauf werden höchstens " + CobolSearchAcquisition.MAX_PROGRAMS
                + " Programm-Kandidaten analysiert. Die Suchfakten bleiben nur in dieser Sitzung.",
                "Suchfakten auch für die KI im FTP-Chat freigeben", "Analysieren");
        if (release == null || view.analysisArea().filter(inventory.area()::equals).isEmpty()) {
            return;
        }
        AtomicBoolean token = new AtomicBoolean();
        running = token;
        obsolete = false;
        snapshot = null;
        principal = view.inventorySession().user();
        area = inventory.area();
        aiReleased = release;
        String expectedUser = principal;
        DocumentStore store = view.analysisStore();
        refresh();
        view.inventoryStatus("COBOL-Analyse startet …", false);
        Thread.ofVirtual().name("cobol-search-acquisition").start(() -> {
            try {
                var result = new CobolSearchAcquisition(store).build(inventory,
                        () -> token.get() || view.inventorySession().state() != ConnectionState.CONNECTED
                                || !expectedUser.equals(view.inventorySession().user())
                                || view.analysisArea().filter(inventory.area()::equals).isEmpty(),
                        message -> SwingUtilities.invokeLater(() -> {
                            if (running == token && !token.get()) view.inventoryStatus(message, false);
                        }));
                SwingUtilities.invokeLater(() -> complete(token, result, null));
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(() -> complete(token, null, error));
            }
        });
    }

    private void complete(AtomicBoolean token, CobolSearchAcquisition.Snapshot result, RuntimeException error) {
        if (running != token) return;
        running = null;
        if (obsolete || view.analysisArea().filter(value -> value.equals(area)).isEmpty()) {
            refresh();
            return;
        }
        if (token.get()) {
            view.inventoryStatus("COBOL-Analyse abgebrochen", false);
        } else if (error != null) {
            view.inventoryStatus("COBOL-Analyse fehlgeschlagen: " + error.getMessage(), true);
        } else {
            snapshot = result;
            view.inventoryStatus(result.coverage(), result.failed() > 0 || result.partial() > 0 || result.limited());
        }
        refresh();
    }

    void showSearch() {
        var current = snapshot().orElse(null);
        if (current == null) {
            view.inventoryStatus("Zuerst den inventarisierten COBOL-Bestand analysieren.", false);
            return;
        }
        var modes = new JComboBox<>(CobolSearch.Mode.values());
        modes.setRenderer(new DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                    boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof CobolSearch.Mode mode) setText(switch (mode) {
                    case ALL -> "Alles";
                    case PROGRAM -> "Programme";
                    case COPYBOOK -> "Copybooks / SYSLIB-Member";
                    case COPY_USAGE -> "Copybook-Verwendungen";
                    case DATA -> "Datenfelder";
                    case CALLERS -> "Aufrufer (statisch)";
                    case CALLEES -> "Aufgerufene Programme";
                    case CALLERS_HIERARCHY -> "Aufrufhierarchie (eingehend)";
                    case CALLEES_HIERARCHY -> "Aufrufhierarchie (ausgehend)";
                });
                return this;
            }
        });
        var query = new JTextField(26);
        var rows = new Results();
        var table = Comic.table(new JTable(rows));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        var count = new JLabel();
        var previous = new JButton("Zurück");
        var next = new JButton("Weitere Treffer");
        var page = new JPanel(new BorderLayout(8, 0));
        page.add(previous, BorderLayout.WEST);
        page.add(count, BorderLayout.CENTER);
        var pageActions = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        var root = new JButton("Als Startprogramm verwenden");
        root.setEnabled(false);
        pageActions.add(root);
        pageActions.add(next);
        page.add(pageActions, BorderLayout.EAST);
        int[] offset = {0};
        var input = new JPanel(new BorderLayout(8, 0));
        input.add(modes, BorderLayout.WEST);
        input.add(query, BorderLayout.CENTER);
        var find = new JButton("Suchen");
        query.setToolTipText("Für die Hierarchie: exakte PROGRAM-ID oder Member; bei mehreren Treffern ROOT_SELECTOR verwenden.");
        input.add(find, BorderLayout.EAST);
        var body = new JPanel(new BorderLayout(0, 8));
        var header = new JPanel(new BorderLayout(0, 4));
        header.add(Comic.note(current.coverage()), BorderLayout.NORTH);
        header.add(input, BorderLayout.SOUTH);
        body.add(header, BorderLayout.NORTH);
        var scroll = Comic.scroll(table);
        scroll.setPreferredSize(new Dimension(760, 320));
        body.add(scroll, BorderLayout.CENTER);
        body.add(page, BorderLayout.SOUTH);
        Runnable refresh = () -> {
            var result = current.search().search((CobolSearch.Mode) modes.getSelectedItem(), query.getText(),
                    300, offset[0]);
            rows.replace(result.hits());
            int start = result.hits().isEmpty() ? 0 : offset[0] + 1;
            int end = offset[0] + result.hits().size();
            count.setText(start + "–" + end + " von " + result.total() + " Fundstellen");
            previous.setEnabled(offset[0] > 0);
            next.setEnabled(end < result.total());
        };
        Runnable firstPage = () -> {
            offset[0] = 0;
            refresh.run();
        };
        table.getSelectionModel().addListSelectionListener(event -> {
            int selected = table.getSelectedRow();
            root.setEnabled(selected >= 0 && rows.at(table.convertRowIndexToModel(selected))
                    .attributes().containsKey("ROOT_SELECTOR"));
        });
        root.addActionListener(event -> {
            int selected = table.getSelectedRow();
            if (selected < 0) return;
            String selector = rows.at(table.convertRowIndexToModel(selected)).attributes().get("ROOT_SELECTOR");
            if (selector != null) {
                query.setText(selector);
                firstPage.run();
            }
        });
        find.addActionListener(event -> firstPage.run());
        query.addActionListener(event -> firstPage.run());
        modes.addActionListener(event -> firstPage.run());
        previous.addActionListener(event -> {
            offset[0] = Math.max(0, offset[0] - 300);
            refresh.run();
        });
        next.addActionListener(event -> {
            offset[0] += 300;
            refresh.run();
        });
        refresh.run();
        if (Dialogs.formOverlay(view, "COBOL-Suche", body, "Fundstelle öffnen")
                && snapshot().orElse(null) == current && table.getSelectedRow() >= 0) {
            CobolSearch.Fact fact = rows.at(table.convertRowIndexToModel(table.getSelectedRow()));
            view.openAnalysisHit(fact);
        }
    }

    private static String hierarchyDetails(CobolSearch.Fact fact) {
        String status = fact.attributes().get("HIERARCHY_STATUS");
        if (status == null) return fact.attributes().toString();
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
        String selector = fact.attributes().get("ROOT_SELECTOR");
        if (selector != null) return label + " · " + selector;
        return label + " · " + fact.attributes().getOrDefault("CALLER_CANDIDATES", "")
                + " → " + fact.attributes().getOrDefault("TARGET_CANDIDATES", "")
                + (fact.attributes().containsKey("ALREADY_VISITED") ? " · Bereits erreicht" : "");
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
            var f = hits.get(row);
            return switch (col) {
                case 0 -> f.attributes().containsKey("HIERARCHY_DEPTH")
                        ? "Ebene " + f.attributes().get("HIERARCHY_DEPTH") + " · " + f.kind() : f.kind();
                case 1 -> f.name();
                case 2 -> f.program();
                case 3 -> f.source();
                case 4 -> f.line() > 0 ? f.line() : "–";
                default -> (f.partial() ? "Teilanalyse · " : "") + hierarchyDetails(f);
            };
        }
    }
}
