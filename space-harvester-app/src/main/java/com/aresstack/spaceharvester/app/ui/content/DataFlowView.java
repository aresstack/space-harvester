package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.mainframe.cobol.CobolReachingDefinitions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow;
import com.aresstack.spaceharvester.mainframe.cobol.CobolDataFlow.*;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Show source-mapped data accesses and local value transfers from the stored artifact. */
final class DataFlowView extends JPanel {
    private record Choice(String id, String text) {
        @Override public String toString() { return text; }
    }

    private final JLabel status = new JLabel();
    private final JComboBox<Choice> fields = new JComboBox<>();
    private final DefaultTableModel accesses = table("Anweisung", "Zugriff", "Operand", "Definition", "Quelle", "Zeile", "CFG-Knoten");
    private final DefaultTableModel transfers = table("Anweisung", "Eingangswerte", "Ziel", "Definition des Ziels", "Quelle", "Zeile", "CFG-Knoten");
    private final DefaultTableModel reads = table("Gelesener Operand", "Lesestelle", "CFG-Knoten");
    private final JTable readTable = Comic.table(new JTable(reads));
    private final DefaultTableModel preceding = table("Mögliche Herkunft", "Schreibstelle", "CFG-Knoten");
    private final JLabel precedingStatus = new JLabel("Lesestelle auswählen");
    private final List<ReadSite> visibleReads = new ArrayList<>();
    private CobolAnalysis currentAnalysis;
    private Future<?> queryTask;
    private int queryGeneration;
    private final DefaultTableModel limitations = table("Hinweis", "Quelle", "Zeile");
    private CobolDataFlow flow = CobolDataFlow.EMPTY;
    private Map<String, Symbol> symbols = Map.of();
    private boolean updating;

    DataFlowView() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        JPanel header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(status, BorderLayout.NORTH);
        JPanel selection = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        selection.setOpaque(false);
        selection.add(new JLabel("Datenfeld:"));
        selection.add(fields);
        fields.setPrototypeDisplayValue(new Choice("", "Datenfeld OF DATENGRUPPE (Programm)"));
        header.add(selection, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        var views = new JTabbedPane();
        views.addTab("Zugriffe", Comic.scroll(Comic.table(new JTable(accesses))));
        views.addTab("Wertübertragungen", Comic.scroll(Comic.table(new JTable(transfers))));
        var candidates = new JPanel(new BorderLayout(0, 4));
        candidates.add(precedingStatus, BorderLayout.NORTH);
        candidates.add(Comic.scroll(Comic.table(new JTable(preceding))), BorderLayout.CENTER);
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Comic.scroll(readTable), candidates);
        split.setResizeWeight(0.5);
        readTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        readTable.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && !updating) selectRead();
        });
        views.addTab("Mögliche Schreibstellen", split);
        views.addTab("Hinweise", Comic.scroll(Comic.table(new JTable(limitations))));
        add(views, BorderLayout.CENTER);
        fields.addActionListener(event -> { if (!updating) render(); });
        show(null, "");
    }

    void show(CobolAnalysis analysis, String message) {
        cancelQuery();
        currentAnalysis = analysis;
        flow = analysis == null ? CobolDataFlow.EMPTY : analysis.dataFlow();
        symbols = flow.symbols().stream().collect(Collectors.toMap(Symbol::id, symbol -> symbol, (a, b) -> a, LinkedHashMap::new));
        updating = true;
        try {
            fields.removeAllItems();
            fields.addItem(new Choice("", "Alle Datenfelder"));
            for (var symbol : flow.symbols()) {
                var origin = symbol.definition().start();
                fields.addItem(new Choice(symbol.id(), symbol.qualifiedName() + " (" + symbol.program()
                        + ", " + origin.source() + ":" + origin.line() + ")"));
            }
        } finally {
            updating = false;
        }
        status.setText(message + " · Lokale Wertabhängigkeiten; Schreibstellen auf modellierten Pfaden"
                + (flow.partial() ? " · Unvollständig (" + flow.limitations().size() + " Hinweise)" : ""));
        limitations.setRowCount(0);
        for (var limitation : flow.limitations()) limitations.addRow(new Object[]{
                limitation.message(), limitation.position().source(), limitation.position().line()});
        render();
    }

    private void render() {
        cancelQuery();
        updating = true;
        readTable.clearSelection();
        reads.setRowCount(0);
        visibleReads.clear();
        accesses.setRowCount(0);
        transfers.setRowCount(0);
        preceding.setRowCount(0);
        precedingStatus.setText("Lesestelle auswählen");
        Choice choice = (Choice) fields.getSelectedItem();
        String field = choice == null ? "" : choice.id();
        for (var operation : flow.operations()) {
            for (var access : operation.accesses()) {
                var operand = access.operand();
                if (!matches(field, operand)) continue;
                var position = operand.span().start();
                accesses.addRow(new Object[]{operation.verb(), label(access.kind()), name(operand), definition(operand),
                        position.source(), position.line(), operation.controlNode()});
            }
            for (var transfer : operation.transfers()) {
                var target = transfer.destination();
                if (!matches(field, target) && transfer.inputs().stream().noneMatch(input -> matches(field, input))) continue;
                var position = operation.span().start();
                transfers.addRow(new Object[]{operation.verb(), transfer.inputs().stream().map(this::name)
                        .collect(Collectors.joining(" ; ")), name(target), definition(target),
                        position.source(), position.line(), operation.controlNode()});
            }
        }
        for (var read : flow.readSites()) {
            if (!matches(field, read.operand())) continue;
            var position = read.operand().span().start();
            visibleReads.add(read);
            reads.addRow(new Object[]{name(read.operand()), position.source() + ":" + position.line(), read.controlNode()});
        }
        updating = false;
    }

    private void selectRead() {
        cancelQuery();
        preceding.setRowCount(0);
        int selected = readTable.getSelectedRow();
        if (selected < 0 || currentAnalysis == null) {
            precedingStatus.setText("Lesestelle auswählen");
            return;
        }
        int index = readTable.convertRowIndexToModel(selected);
        if (index >= visibleReads.size()) return;
        var read = visibleReads.get(index);
        var analysis = currentAnalysis;
        int generation = ++queryGeneration;
        precedingStatus.setText("Mögliche Schreibstellen werden ermittelt …");
        queryTask = Background.start(() -> CobolReachingDefinitions.candidates(analysis, read), candidates -> {
            if (generation != queryGeneration) return;
            queryTask = null;
            precedingStatus.setText(candidates.size() + " mögliche Schreibstellen / Zustände auf modellierten Pfaden");
            for (var candidate : candidates) {
                var position = candidate.span().start();
                preceding.addRow(new Object[]{switch (candidate.kind()) {
                    case ENTRY_STATE -> "Zustand bei Programmeintritt";
                    case WRITE -> "Explizite Schreibstelle";
                    case POSSIBLE_WRITE -> "Mögliche Änderung";
                    case UNKNOWN_EFFECT -> "Unbekannte Wirkung";
                }, position.source() + ":" + position.line(), candidate.controlNode()});
            }
        }, error -> {
            if (generation != queryGeneration) return;
            queryTask = null;
            precedingStatus.setText("Schreibstellen nicht ermittelbar");
        });
    }

    private void cancelQuery() {
        queryGeneration++;
        if (queryTask != null) {
            queryTask.cancel(true);
            queryTask = null;
        }
    }

    private boolean matches(String field, Operand operand) {
        return field.isEmpty() || field.equals(operand.symbolId());
    }

    private String name(Operand operand) {
        var symbol = symbols.get(operand.symbolId());
        if (symbol != null) return operand.text() + " [" + symbol.qualifiedName() + "]";
        return switch (operand.kind()) {
            case LITERAL -> operand.text();
            case EXTERNAL -> "Extern: " + operand.text();
            case UNKNOWN -> "Unaufgelöst: " + operand.text();
            case SYMBOL -> operand.text();
        };
    }

    private String definition(Operand operand) {
        var symbol = symbols.get(operand.symbolId());
        if (symbol == null) return "";
        var origin = symbol.definition().start();
        return origin.source() + ":" + origin.line() + ":" + origin.column();
    }

    private static String label(AccessKind kind) {
        return switch (kind) {
            case READ -> "Lesen";
            case WRITE -> "Schreiben";
            case READ_WRITE -> "Lesen und schreiben";
            case ADDRESS_READ -> "Adressierung lesen";
            case POSSIBLE_READ_WRITE -> "Möglicher Lese-/Schreibzugriff";
            case UNKNOWN -> "Unbekannt";
        };
    }

    private static DefaultTableModel table(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
