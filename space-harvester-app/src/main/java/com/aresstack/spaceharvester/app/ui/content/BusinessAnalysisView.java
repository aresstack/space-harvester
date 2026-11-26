package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.program.CobolBusinessEnrichment;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.application.enrichment.Enrichment;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import java.awt.BorderLayout;
import java.util.ArrayList;
import java.util.List;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/** Present AI hypotheses separately from technical facts; never style them as verified claims. */
final class BusinessAnalysisView extends JPanel {
    private final JLabel status = new JLabel();
    private final DefaultTableModel hypotheses = table("KI-Hypothese (ungeprüft)", "Begründung / Grenzen");
    private final DefaultTableModel evidence = table("Beleg-ID", "Technischer Fakt", "Quelle", "Zeile", "Spalte");
    private final JTable choices = Comic.table(new JTable(hypotheses));
    private List<com.aresstack.spaceharvester.app.runtime.program.CobolBusinessAnalysis.Hypothesis> claims = List.of();

    BusinessAnalysisView() {
        super(new BorderLayout(0, 6));
        setOpaque(false);
        add(status, BorderLayout.NORTH);
        choices.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        choices.getSelectionModel().addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting()) showEvidence();
        });
        var split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Comic.scroll(choices),
                Comic.scroll(Comic.table(new JTable(evidence))));
        split.setResizeWeight(0.5);
        add(split, BorderLayout.CENTER);
        pending();
    }

    void pending() {
        replace(List.of(), "Noch keine aktuelle fachliche KI-Analyse. Nur auf ausdrücklichen Abruf.");
    }

    void show(List<Enrichments.View> views) {
        var view = views.stream().filter(item -> CobolBusinessEnrichment.ID.equals(item.enrichment().processorId()))
                .findFirst().orElse(null);
        if (view == null) {
            pending();
            return;
        }
        if (view.freshness() != Enrichment.Freshness.CURRENT || view.enrichment().status() != Enrichment.Status.OK) {
            replace(List.of(), "Fachliche KI-Analyse nicht mehr aktuell oder nicht verfügbar. Neu berechnen.");
            return;
        }
        try {
            var result = CobolBusinessEnrichment.read(view.enrichment().payloadJson()).result();
            replace(result.hypotheses(), "Modell: " + result.model() + " · " + result.batches()
                    + " Faktenpakete · " + result.evidenceCount() + " Fakten · "
                    + (result.technicalPartial() ? "Technische Teilanalyse · " : "")
                    + "KI-Hypothesen, Belegverweise formal geprüft, fachlich ungeprüft");
        } catch (RuntimeException invalid) {
            replace(List.of(), "Gespeicherte fachliche Analyse nicht lesbar.");
        }
    }

    private void replace(List<com.aresstack.spaceharvester.app.runtime.program.CobolBusinessAnalysis.Hypothesis> result,
                         String message) {
        choices.clearSelection();
        claims = List.copyOf(result);
        hypotheses.setRowCount(0);
        evidence.setRowCount(0);
        for (var claim : claims) hypotheses.addRow(new Object[]{claim.statement(), claim.reason()});
        status.setText(message);
        if (!claims.isEmpty()) choices.setRowSelectionInterval(0, 0);
    }

    private void showEvidence() {
        evidence.setRowCount(0);
        int selected = choices.getSelectedRow();
        if (selected < 0) return;
        int index = choices.convertRowIndexToModel(selected);
        if (index >= claims.size()) return;
        for (var fact : claims.get(index).evidence()) {
            var position = fact.location().start();
            evidence.addRow(new Object[]{fact.id(), fact.kind() + ": " + fact.fact(),
                    position.source(), position.line(), position.column()});
        }
    }

    private static DefaultTableModel table(String... columns) {
        return new DefaultTableModel(columns, 0) {
            @Override public boolean isCellEditable(int row, int column) { return false; }
        };
    }
}
