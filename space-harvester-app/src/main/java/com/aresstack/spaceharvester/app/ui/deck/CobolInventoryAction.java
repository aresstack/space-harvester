package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.deck.ftp.CobolAnalysisArea;
import com.aresstack.spaceharvester.deck.ftp.CobolInventory;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.SwingUtilities;

/** Run the explicit inventory outside the EDT and retain its current-session result. */
final class CobolInventoryAction {
    private final FtpDatasetsView view;
    private final IconButton button = new IconButton(Glyph.REFRESH, "COBOL-Bestand inventarisieren");
    private AtomicBoolean running;
    private boolean obsolete;
    private CobolInventory.Result last;

    CobolInventoryAction(FtpDatasetsView view) {
        this.view = view;
        button.addActionListener(event -> activate());
    }

    IconButton button() {
        return button;
    }

    Optional<CobolInventory.Result> result() {
        return Optional.ofNullable(last);
    }

    void invalidate() {
        last = null;
        obsolete = true;
        if (running != null) {
            running.set(true);
        }
    }

    void refresh() {
        button.setVisible(view.inventorySession().mvs());
        boolean available = area().isPresent();
        button.setEnabled(running != null || available);
        button.marked(running != null);
        button.setToolTipText(running != null ? "COBOL-Inventur abbrechen"
                : !available ? "Zuerst COBOL-Home konfigurieren"
                : last == null ? "COBOL-Bestand inventarisieren" : summary(last) + " – erneut inventarisieren");
    }

    private Optional<CobolAnalysisArea> area() {
        try {
            return view.analysisArea();
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    private void activate() {
        if (running != null) {
            running.set(true);
            view.inventoryStatus("COBOL-Inventur wird abgebrochen …", false);
            return;
        }
        area().ifPresent(this::start);
    }

    private void start(CobolAnalysisArea area) {
        var service = CobolInventory.forSession(view.inventorySession());
        var token = new AtomicBoolean();
        running = token;
        obsolete = false;
        last = null;
        refresh();
        view.inventoryStatus("COBOL-Inventur: " + area.absoluteHome(), false);
        Thread.ofVirtual().name("cobol-inventory").start(() -> {
            try {
                var result = service.scan(area, token::get, progress -> SwingUtilities.invokeLater(() -> {
                    if (running == token && !obsolete && !token.get()) {
                        view.inventoryStatus("COBOL-Inventur · " + progress.phase() + " · " + progress.dataset()
                                + " · " + progress.completed() + "/" + progress.total(), false);
                    }
                }));
                SwingUtilities.invokeLater(() -> complete(token, result, null));
            } catch (RuntimeException error) {
                SwingUtilities.invokeLater(() -> complete(token, null, error));
            }
        });
    }

    private void complete(AtomicBoolean token, CobolInventory.Result result, RuntimeException error) {
        if (running != token) {
            return;
        }
        running = null;
        if (obsolete) {
            refresh();
            return;
        }
        last = result;
        refresh();
        if (error != null) {
            view.inventoryStatus("COBOL-Inventur fehlgeschlagen: " + error.getMessage(), true);
            return;
        }
        view.inventoryStatus(summary(result), !result.failures().isEmpty());
        if (view.isShowing()) {
            showResult(result);
        }
    }

    private void showResult(CobolInventory.Result result) {
        StringBuilder report = new StringBuilder(summary(result)).append('\n')
                .append("Home: ").append(result.area().absoluteHome()).append("\n\n");
        result.failures().forEach((path, error) -> report.append("Fehler: ").append(path).append(" – ")
                .append(error).append('\n'));
        result.excludedDatasets().forEach(path -> report.append("Kein PDS: ").append(path).append('\n'));
        report.append("\nMember (noch nicht semantisch klassifiziert):\n");
        result.members().stream().limit(200).forEach(member -> report.append(member.path())
                .append(member.copybookCandidate() ? " [SYSLIB]" : "").append('\n'));
        if (result.members().size() > 200) {
            report.append("… weitere ").append(result.members().size() - 200).append(" Member");
        }
        var text = new javax.swing.JTextArea(report.toString(), 22, 60);
        text.setEditable(false);
        text.setCaretPosition(0);
        Dialogs.view(view, "COBOL-Bestandsinventur", Comic.scroll(text));
    }

    private static String summary(CobolInventory.Result result) {
        long programs = result.members().stream().filter(CobolInventory.Member::programCandidate).count();
        long copybooks = result.members().stream().filter(CobolInventory.Member::copybookCandidate).count();
        String state = result.cancelled() ? "abgebrochen" : result.complete() ? "abgeschlossen" : "unvollständig";
        return "COBOL-Inventur " + state + " · " + programs + " Programm-Kandidaten · " + copybooks
                + " SYSLIB-Member · " + result.failures().size() + " Fehler";
    }
}
