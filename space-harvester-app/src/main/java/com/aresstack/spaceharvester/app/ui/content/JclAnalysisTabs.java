package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Display only current, stored JCL declarations and explicit dependencies. */
final class JclAnalysisTabs {
    private final PillTabs tabs;
    private final View structure = new View("Zeile", "Bis", "Operation", "Name", "Job", "PROC", "Step", "Parameter", "Bedingungen");
    private final View calls = new View("Zeile", "Step", "Art", "Ziel", "Auflösung", "Deklaration");
    private final View datasets = new View("Zeile", "Step", "DD", "Art", "Ziel", "DISP", "Auflösung");
    private final View diagnostics = new View("Zeile", "Hinweis");
    private final List<String> names = List.of("Jobstruktur", "Programm- und Prozeduraufrufe", "Dataset-Abhängigkeiten", "JCL-Hinweise");

    JclAnalysisTabs(PillTabs tabs) {
        this.tabs = tabs;
        tabs.tab(names.get(0), structure.panel);
        tabs.tab(names.get(1), calls.panel);
        tabs.tab(names.get(2), datasets.panel);
        tabs.tab(names.get(3), diagnostics.panel);
    }

    void clear(String message) {
        for (var view : List.of(structure, calls, datasets, diagnostics)) view.clear(message);
    }

    boolean show(PageSnapshot snapshot, List<Enrichments.View> views) {
        if (JclProgramEnrichment.source(snapshot).isEmpty()) {
            clear("");
            return false;
        }
        clear("Noch keine aktuelle JCL-Analyse. Programmanalyse berechnen.");
        var current = views.stream().filter(view -> JclProgramEnrichment.ID.equals(view.enrichment().processorId())
                && view.freshness() == Enrichment.Freshness.CURRENT
                && view.enrichment().status() == Enrichment.Status.OK).findFirst();
        if (current.isPresent()) {
            try {
                var artifact = JclProgramEnrichment.read(current.get().enrichment().payloadJson());
                if (snapshot.payload().rawSha256().equals(artifact.rawSha256())) {
                    var analysis = artifact.analysis();
                    clear(analysis.partial() ? "Statische Teilanalyse · Hinweise beachten" : "Statische Deklarationen · keine Laufzeitaussage");
                    for (var s : analysis.statements()) structure.model.addRow(new Object[]{
                            s.line(), s.endLine(), s.operation(), s.name(), position(s.jobLine()), position(s.procedureLine()),
                            position(s.stepLine()), s.operands(), s.guards()});
                    for (var d : analysis.dependencies()) {
                        switch (d.kind()) {
                            case PROGRAM, PROCEDURE, INCLUDE, PROCEDURE_LIBRARY ->
                                    calls.model.addRow(new Object[]{d.line(), position(d.stepLine()), d.kind(), d.target(),
                                            d.resolution(), position(d.declarationLine())});
                            default -> datasets.model.addRow(new Object[]{d.line(), position(d.stepLine()), d.ddName(),
                                    d.kind(), d.target(), d.disposition(), d.resolution()});
                        }
                    }
                    for (var d : analysis.diagnostics()) diagnostics.model.addRow(new Object[]{d.line(), d.message()});
                }
            } catch (RuntimeException invalid) {
                clear("Gespeicherte JCL-Analyse nicht lesbar.");
            }
        }
        tabs.showOnly(names);
        return true;
    }

    private static Object position(int line) { return line == 0 ? "" : line; }

    private static final class View {
        final JPanel panel = new JPanel(new BorderLayout(0, 6));
        final JLabel status = new JLabel();
        final DefaultTableModel model;

        View(String... columns) {
            model = new DefaultTableModel(columns, 0) {
                @Override public boolean isCellEditable(int row, int column) { return false; }
            };
            panel.setOpaque(false);
            panel.add(status, BorderLayout.NORTH);
            panel.add(Comic.scroll(Comic.table(new JTable(model))), BorderLayout.CENTER);
        }

        void clear(String message) {
            model.setRowCount(0);
            status.setText(message);
        }
    }
}
