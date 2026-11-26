package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.CobolProgramEnrichment;
import com.aresstack.spaceharvester.app.runtime.program.StoredCobolSource;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.mainframe.cobol.CobolAnalysis;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.table.DefaultTableModel;

/** Bind document-specific tabs to persisted parser facts. */
final class ProgramAnalysisTabs {
    private final PillTabs tabs;
    private final List<String> textTabs;
    private final Map<String, Section> sections = new LinkedHashMap<>();
    private final ControlFlowView controlFlow = new ControlFlowView();

    ProgramAnalysisTabs(PillTabs tabs) {
        this.tabs = tabs;
        textTabs = tabs.names();
        register("Programmstruktur");
        register("Datenstrukturen");
        register("Copybooks");
        register("Aufrufe");
        register("Diagnosen");
        tabs.tab("Kontrollfluss", controlFlow);
        reset();
    }

    private void register(String name) {
        Section section = new Section();
        sections.put(name, section);
        tabs.tab(name, section.panel);
    }

    void reset() {
        pending();
        tabs.showOnly(textTabs);
    }

    void pending() {
        sections.values().forEach(section -> section.replace(List.of(), "Analyse wird geladen …"));
        controlFlow.show(null, "Analyse wird geladen …");
    }

    boolean show(PageSnapshot snapshot, List<Enrichments.View> views) {
        if (StoredCobolSource.read(snapshot).isEmpty()) {
            reset();
            return false;
        }
        String message = "Noch keine aktuelle Programmanalyse im Bestand. Wissensquelle indexieren.";
        var view = views.stream().filter(item -> CobolProgramEnrichment.ID.equals(item.enrichment().processorId()))
                .findFirst().orElse(null);
        CobolAnalysis analysis = null;
        if (view != null && view.freshness() == Enrichment.Freshness.CURRENT) {
            if (view.enrichment().status() == Enrichment.Status.OK) {
                try {
                    var artifact = CobolProgramEnrichment.read(view.enrichment().payloadJson());
                    if (snapshot.payload().rawSha256().equals(artifact.rawSha256())) {
                        analysis = artifact.analysis();
                        message = analysis.isPartial()
                                ? "Teilanalyse · Diagnosen und Copybook-Auflösung beachten."
                                : "Statische Analyse · keine Laufzeitaussage";
                    }
                } catch (RuntimeException invalid) {
                    message = "Gespeicherte Programmanalyse nicht lesbar.";
                }
            } else {
                message = "Programmanalyse fehlgeschlagen.";
            }
        }
        render(analysis, message);
        var names = new ArrayList<>(sections.keySet());
        names.add("Kontrollfluss");
        tabs.showOnly(names);
        return true;
    }

    private void render(CobolAnalysis analysis, String message) {
        sections.values().forEach(section -> section.replace(List.of(), message));
        controlFlow.show(analysis, message);
        if (analysis == null) return;
        List<Object[]> structure = new ArrayList<>();
        List<Object[]> data = new ArrayList<>();
        List<Object[]> copies = new ArrayList<>();
        List<Object[]> calls = new ArrayList<>();
        List<Object[]> diagnostics = new ArrayList<>();
        for (var element : analysis.elements()) {
            Object[] row = {element.kind(), element.name(), element.span().start().source(),
                    element.span().start().line(), element.attributes()};
            switch (element.kind()) {
                case DATA_ITEM, LEVEL_01, LEVEL_77, LEVEL_88, FILE_DESCRIPTOR -> data.add(row);
                case COPY_STMT -> copies.add(row);
                case CALL_STMT -> { }
                default -> structure.add(row);
            }
        }
        for (var call : analysis.calls()) {
            calls.add(new Object[]{call.targetKind(), call.target(), call.span().start().source(),
                    call.span().start().line(), call.text()});
        }
        for (var diagnostic : analysis.diagnostics()) {
            diagnostics.add(new Object[]{diagnostic.kind(), diagnostic.message(), diagnostic.position().source(),
                    diagnostic.position().line(), ""});
        }
        sections.get("Programmstruktur").replace(structure, message);
        sections.get("Datenstrukturen").replace(data, message);
        sections.get("Copybooks").replace(copies, message);
        sections.get("Aufrufe").replace(calls, message);
        sections.get("Diagnosen").replace(diagnostics, message);
    }

    private static final class Section {
        private static final Object[] COLUMNS = {"Art", "Name", "Quelle", "Zeile", "Details"};
        private final JPanel panel = new JPanel(new BorderLayout(0, 6));
        private final JLabel status = new JLabel();
        private final DefaultTableModel model = new DefaultTableModel(COLUMNS, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };

        private Section() {
            panel.setOpaque(false);
            panel.add(status, BorderLayout.NORTH);
            panel.add(Comic.scroll(Comic.table(new JTable(model))), BorderLayout.CENTER);
        }

        private void replace(List<Object[]> rows, String message) {
            model.setRowCount(0);
            rows.forEach(model::addRow);
            status.setText(message);
        }
    }
}
