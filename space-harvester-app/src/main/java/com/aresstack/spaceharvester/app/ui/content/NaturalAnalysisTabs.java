package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.NaturalProgramEnrichment;
import com.aresstack.spaceharvester.app.runtime.program.StoredNaturalSource;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import java.awt.BorderLayout;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Bind the Natural-specific tabs to current persisted object facts. */
final class NaturalAnalysisTabs {
    private final PillTabs tabs;
    private final View structure = new View("Zeile", "Art", "Name", "Übergeordnet (Zeile)", "Details");
    private final View dependencies = new View("Zeile", "Art", "Ziel", "Auflösung", "Deklaration", "Details");
    private final View diagnostics = new View("Zeile", "Hinweis");
    private final List<String> names = List.of("Natural-Struktur", "Natural-Abhängigkeiten", "Natural-Hinweise");

    NaturalAnalysisTabs(PillTabs tabs) {
        this.tabs = tabs;
        tabs.tab(names.get(0), structure.panel);
        tabs.tab(names.get(1), dependencies.panel);
        tabs.tab(names.get(2), diagnostics.panel);
    }

    void clear(String message) {
        for (var view : List.of(structure, dependencies, diagnostics)) view.clear(message);
    }

    boolean show(PageSnapshot snapshot, List<Enrichments.View> views) {
        if (StoredNaturalSource.read(snapshot).isEmpty()) {
            clear("");
            return false;
        }
        clear("Noch keine aktuelle Natural-Analyse im Bestand. Programmanalyse berechnen.");
        var current = views.stream().filter(view -> NaturalProgramEnrichment.ID.equals(view.enrichment().processorId())
                && view.freshness() == Enrichment.Freshness.CURRENT
                && view.enrichment().status() == Enrichment.Status.OK).findFirst();
        if (current.isPresent()) {
            try {
                var artifact = NaturalProgramEnrichment.read(current.get().enrichment().payloadJson());
                if (snapshot.payload().rawSha256().equals(artifact.rawSha256())) {
                    var analysis = artifact.analysis();
                    clear(analysis.library() + "/" + analysis.name() + "." + analysis.extension()
                            + " · Statische Teilanalyse");
                    for (var element : analysis.elements()) structure.model.addRow(new Object[]{
                            position(element.line()), element.kind(), element.name(), position(element.parentLine()), element.attributes()});
                    for (var dependency : analysis.dependencies()) dependencies.model.addRow(new Object[]{
                            position(dependency.line()), dependency.kind(), dependency.target(), label(dependency.resolution()),
                            position(dependency.declarationLine()), dependency.detail()});
                    for (var item : analysis.diagnostics()) diagnostics.model.addRow(new Object[]{
                            position(item.line()), item.message()});
                }
            } catch (RuntimeException invalid) {
                clear("Gespeicherte Natural-Analyse nicht lesbar.");
            }
        }
        tabs.showOnly(names);
        return true;
    }

    private static String label(com.aresstack.spaceharvester.mainframe.parser.NaturalAnalysis.Resolution resolution) {
        return switch (resolution) {
            case LITERAL -> "Explizites Ziel (nicht extern aufgelöst)";
            case DYNAMIC -> "Dynamisches Ziel";
            case LOCAL_DECLARATION -> "Lokale Deklaration";
            case DECLARED_VIEW -> "Deklarierter View";
            case UNRESOLVED -> "Nicht aufgelöst";
        };
    }

    private static Object position(int line) { return line <= 0 ? "" : line; }

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
