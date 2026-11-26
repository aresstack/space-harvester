package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.deck.visual.MermaidDiagramPanel;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import com.aresstack.spaceharvester.mainframe.cobol.CobolControlFlow;
import com.aresstack.spaceharvester.mainframe.diagram.CobolControlFlowDiagram;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Display the persisted graph and its limitations without running the parser on the EDT. */
final class ControlFlowView extends JPanel {
    private final JLabel status = new JLabel();
    private final JComboBox<String> programs = new JComboBox<>();
    private final JButton diagram = new JButton("Diagramm öffnen");
    private final DefaultTableModel rows = new DefaultTableModel(
            new Object[]{"Von", "Übergang", "Nach", "Bedingung / Kontext", "Quelle", "Zeile"}, 0) {
        @Override public boolean isCellEditable(int row, int column) { return false; }
    };
    private CobolControlFlow flow = CobolControlFlow.EMPTY;

    ControlFlowView() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        var actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        actions.setOpaque(false);
        actions.add(programs);
        actions.add(diagram);
        var header = new JPanel(new BorderLayout());
        header.setOpaque(false);
        header.add(status, BorderLayout.NORTH);
        header.add(actions, BorderLayout.SOUTH);
        add(header, BorderLayout.NORTH);
        add(Comic.scroll(Comic.table(new JTable(rows))), BorderLayout.CENTER);
        programs.addActionListener(event -> render());
        diagram.addActionListener(event -> openDiagram());
        show(null, "");
    }

    void show(CobolAnalysis analysis, String message) {
        flow = analysis == null ? CobolControlFlow.EMPTY : analysis.controlFlow();
        programs.removeAllItems();
        for (var procedure : flow.procedures()) programs.addItem(procedure.name());
        String limitations = flow.partial() ? " · Kontrollfluss unvollständig (" + flow.limitations().size() + " Hinweise)" : "";
        status.setText(message + limitations);
        status.setToolTipText(flow.limitations().stream().map(CobolControlFlow.Limitation::message)
                .collect(Collectors.joining(" | ")));
        render();
    }

    private CobolControlFlow.Procedure selected() {
        int index = programs.getSelectedIndex();
        return index < 0 || index >= flow.procedures().size() ? null : flow.procedures().get(index);
    }

    private void render() {
        rows.setRowCount(0);
        var graph = selected();
        diagram.setEnabled(graph != null);
        if (graph == null) {
            for (var limitation : flow.limitations()) rows.addRow(new Object[]{"", "Unbekannt", "",
                    limitation.message(), limitation.position().source(), limitation.position().line()});
            return;
        }
        Map<String, CobolControlFlow.Node> nodes = graph.nodes().stream()
                .collect(Collectors.toMap(CobolControlFlow.Node::id, Function.identity()));
        for (var edge : graph.edges()) {
            var from = nodes.get(edge.from());
            var to = nodes.get(edge.to());
            rows.addRow(new Object[]{from.id() + " " + from.label(), label(edge.kind()), to.id() + " " + to.label(),
                    edge.condition() + (edge.callSite().isEmpty() ? "" : " [" + edge.callSite() + "]"),
                    from.span().start().source(), from.span().start().line()});
        }
        for (var limitation : flow.limitations()) rows.addRow(new Object[]{"", "Hinweis", "",
                limitation.message(), limitation.position().source(), limitation.position().line()});
    }

    private void openDiagram() {
        var graph = selected();
        if (graph == null) return;
        var panel = new MermaidDiagramPanel(false);
        panel.setMermaidSource(CobolControlFlowDiagram.render(graph));
        Dialogs.viewSized(this, "Kontrollfluss – " + graph.name(), panel, "cobol-control-flow", 0.85);
    }

    private static String label(CobolControlFlow.EdgeKind kind) {
        return switch (kind) {
            case NEXT -> "Weiter";
            case TRUE -> "Wahr";
            case FALSE -> "Falsch";
            case WHEN -> "WHEN";
            case OTHERWISE -> "Sonst";
            case LOOP_BODY -> "Schleifenrumpf";
            case LOOP_EXIT -> "Schleifenende";
            case GOTO -> "Sprung";
            case DEPENDING_TARGET -> "Bedingter Sprung";
            case NEXT_SENTENCE -> "Nächster Satz";
            case TERMINATE -> "Programmende";
            case CALL_RETURN -> "CALL-Rückkehr";
            case PERFORM_CALL -> "PERFORM-Aufruf";
            case PERFORM_RETURN -> "PERFORM-Rückkehr";
            case FALLTHROUGH -> "Fortsetzung ohne Rücksprung";
            case UNKNOWN -> "Unbekannt";
        };
    }
}
