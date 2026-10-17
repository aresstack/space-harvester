package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.SyncSummary;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Aufträge: Abgleiche und Exporte dieser App mit Fortschritt, Ergebnis, Teilfehlern und Abbruch. Ein Lauf heißt
 * nur dann erfolgreich, wenn er vollständig ist; ein Abgleich, der keine Generation bestätigen konnte, erscheint als
 * Warnung mit Grund.
 */
public final class ActivitiesView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JobsModel model = new JobsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel title = new JLabel("Kein Auftrag gewählt");
    private final JProgressBar progress = new JProgressBar();
    /** Leiste am unteren Rand mit dem Fortschritt. */
    private final JPanel progressBar = new JPanel(new BorderLayout());
    private final Facts facts = new Facts();
    private final Facts result = new Facts();
    private final Feedback feedback = new Feedback();
    private final ComposerButton cancel = Comic.danger("Abbrechen", "Auftrag abbrechen");
    private final ComposerButton again = Comic.button("Erneut abgleichen", "Denselben Space erneut abgleichen");
    private final ComposerButton files = Comic.button("Dateien speichern …", "Exportdateien in einen Ordner kopieren");
    private final ComposerButton content = Comic.button("Inhalte öffnen", "Seiten dieses Space ansehen");
    private final ComposerButton diagnosis = Comic.button("Diagnose ansehen", "Unter Status: Phasen, Fehlercode, "
            + "HTTP-Status, Proxy-Route, TLS-Befund und Ausnahmekette dieses Auftrags");
    final ComposerButton delete = Comic.button("Löschen …", "Diesen beendeten Auftrag samt Diagnose löschen");
    final ComposerButton deleteFinished = Comic.button("Abgeschlossene Aufträge löschen …",
            "Alle beendeten Aufträge samt Diagnose löschen; laufende bleiben");
    private final Timer timer = new Timer(1500, e -> poll());
    private String selectedId;
    private Object detail;

    public ActivitiesView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        table.getColumnModel().getColumn(2).setCellRenderer(new StateRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                select(model.rows.get(table.getSelectedRow()).id());
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBackground(Comic.SURFACE);
        left.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 8));
        left.add(Comic.caption("Aufträge dieser App"), BorderLayout.NORTH);
        left.add(Comic.scroll(table), BorderLayout.CENTER);
        JPanel leftSouth = new JPanel(new BorderLayout(0, 6));
        leftSouth.setOpaque(false);
        leftSouth.add(Comic.row(deleteFinished), BorderLayout.NORTH);
        leftSouth.add(Comic.note("Abgleiche starten unter Wissensquellen oder Inhalte, Exporte unter Exportieren. "
                + "Beendete Aufträge bleiben sichtbar, bis die Aufbewahrungsfrist endet oder sie gelöscht werden."),
                BorderLayout.SOUTH);
        left.add(leftSouth, BorderLayout.SOUTH);

        title.setFont(Comic.semiBold(17f));
        title.setForeground(Comic.INK);
        progress.setStringPainted(true);
        progress.setFont(Comic.regular(12f));
        progress.setForeground(Comic.BLUE);
        cancel.addActionListener(e -> cancelSelected());
        again.addActionListener(e -> restart());
        files.addActionListener(e -> saveFiles());
        content.addActionListener(e -> currentStatus().ifPresent(s ->
                context.navigator().openSpace(s.sourceId(), s.partitionKey())));
        diagnosis.addActionListener(e -> currentStatus().ifPresent(s -> context.navigator().showDiagnosis(s.id())));
        delete.addActionListener(e -> deleteSelected());
        deleteFinished.addActionListener(e -> deleteFinished());

        JPanel head = new JPanel(new BorderLayout(0, 8));
        head.setOpaque(false);
        head.add(title, BorderLayout.NORTH);
        JPanel south = new JPanel(new BorderLayout(0, 6));
        south.setOpaque(false);
        south.add(Comic.row(diagnosis, cancel, again, files, content, delete), BorderLayout.NORTH);
        south.add(feedback, BorderLayout.SOUTH);
        head.add(south, BorderLayout.SOUTH);

        JPanel right = new JPanel(new BorderLayout());
        right.setBackground(Comic.SURFACE);
        right.add(Comic.column(head, Comic.plate("Auftrag", Comic.PETROL, facts),
                Comic.plate("Ergebnis", Comic.ORANGE, result)), BorderLayout.CENTER);
        // Der Fortschritt klebt am unteren Rand und bleibt sichtbar, während die Details darüber scrollen.
        JPanel bottom = progressBar;
        bottom.setBackground(Comic.SURFACE);
        bottom.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 14, 10, 14)));
        bottom.add(progress, BorderLayout.CENTER);
        right.add(bottom, BorderLayout.SOUTH);
        root.add(new ComicSplitPane(left, right, 320, 700), BorderLayout.CENTER);
        updateButtons();
        context.onChange(ignored -> poll());
    }

    @Override
    public JComponent component() {
        return root;
    }

    @Override
    public void shown() {
        poll();
        timer.start();
    }

    /** Zeigt einen Auftrag (z. B. direkt nach dem Start). */
    public void show(String jobId) {
        selectedId = jobId;
        poll();
        timer.start();
    }

    private void poll() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            model.set(List.of());
            return;
        }
        String id = selectedId;
        Background.run(() -> {
            List<JobStatus> jobs = runtime.workspace().jobs();
            Object current = null;
            if (id != null) {
                JobStatus status = runtime.workspace().job(id);
                current = status.type() == JobType.SYNC ? runtime.workspace().syncStatus(id) : status;
                if (status.type() == JobType.EXPORT && status.state() == JobState.COMPLETED) {
                    current = new ExportDetail(status, runtime.workspace().exportArtifact(id).orElse(null));
                }
            }
            return new Object[] {jobs, current};
        }, data -> {
            @SuppressWarnings("unchecked")
            List<JobStatus> jobs = (List<JobStatus>) data[0];
            model.set(jobs);
            deleteFinished.setEnabled(jobs.stream().anyMatch(j -> j.state().terminal()));
            reselect();
            detail = data[1];
            render();
            boolean active = jobs.stream().anyMatch(j -> !j.state().terminal());
            if (!active && !root.isShowing()) {
                timer.stop();
            }
        }, error -> {
            feedback.problem(error);
            timer.stop();
        });
    }

    private void select(String id) {
        if (!id.equals(selectedId)) {
            selectedId = id;
            detail = null;
            feedback.clear();
            poll();
        }
    }

    private void reselect() {
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).id().equals(selectedId)) {
                if (table.getSelectedRow() != i) {
                    table.getSelectionModel().setSelectionInterval(i, i);
                }
                return;
            }
        }
    }

    private Optional<JobStatus> currentStatus() {
        if (detail instanceof SpaceSyncJobs.SyncJob sync) {
            return Optional.of(sync.status());
        }
        if (detail instanceof ExportDetail export) {
            return Optional.of(export.status());
        }
        return detail instanceof JobStatus status ? Optional.of(status) : Optional.empty();
    }

    private void render() {
        Optional<JobStatus> current = currentStatus();
        facts.reset();
        result.reset();
        if (current.isEmpty()) {
            title.setText(model.rows.isEmpty() ? "Noch keine Aufträge" : "Kein Auftrag gewählt");
            progressBar.setVisible(false);
            updateButtons();
            return;
        }
        JobStatus status = current.get();
        title.setText(Texts.type(status.type()) + " " + status.sourceId() + " / " + status.partitionKey());
        JobProgress p = status.progress();
        progressBar.setVisible(true);
        if (status.state() == JobState.RUNNING && p.discovered() > 0) {
            progress.setIndeterminate(false);
            progress.setMaximum((int) Math.min(Integer.MAX_VALUE, p.discovered()));
            progress.setValue((int) Math.min(Integer.MAX_VALUE, p.processed()));
            progress.setString(p.processed() + " von " + p.discovered() + " Seiten");
        } else {
            progress.setIndeterminate(!status.state().terminal());
            progress.setMaximum(1);
            progress.setValue(status.state().terminal() ? 1 : 0);
            progress.setString(Texts.state(status.state()) + (status.cancelRequested() && !status.state().terminal()
                    ? " · Abbruch angefordert" : ""));
        }
        facts.add("Zustand", Texts.state(status.state()), Comic.tone(Texts.tone(status.state())))
                .add("Quelle", status.sourceId())
                .add("Space", status.partitionKey())
                .add("Angelegt", Texts.time(status.createdAt()))
                .add("Gestartet", Texts.time(status.startedAt()))
                .add("Beendet", Texts.time(status.finishedAt()))
                .add("Dauer", Texts.duration(status.startedAt(), status.finishedAt()))
                .add("Gefunden", String.valueOf(p.discovered()))
                .add("Verarbeitet", String.valueOf(p.processed()))
                .add("Geschrieben", String.valueOf(p.written()))
                .add("Fehlgeschlagen", String.valueOf(p.failed()), p.failed() > 0 ? Comic.RED : Comic.INK)
                .add("Auftrags-ID", status.id());
        if (status.error() != null) {
            facts.add("Fehler", status.error().code() + ": " + status.error().message(), Comic.RED);
        }
        if (detail instanceof SpaceSyncJobs.SyncJob sync && sync.summary() != null) {
            renderSync(status, sync.summary());
        } else if (detail instanceof ExportDetail export) {
            renderExport(export);
        } else if (!status.state().terminal()) {
            feedback.working(Texts.type(status.type()) + " läuft …");
        } else {
            renderTerminal(status, true);
        }
        updateButtons();
        root.revalidate();
    }

    private void renderSync(JobStatus status, SyncSummary summary) {
        result.add("Gelistet", String.valueOf(summary.listed()))
                .add("Neu", String.valueOf(summary.added()))
                .add("Inhalt geändert", String.valueOf(summary.contentChanged()))
                .add("Metadaten geändert", String.valueOf(summary.metadataChanged()))
                .add("Attribute geändert", String.valueOf(summary.attributesChanged()))
                .add("Unverändert", String.valueOf(summary.unchanged()))
                .add("Entfernt", String.valueOf(summary.removed()))
                .add("Verschoben", String.valueOf(summary.movedOut()))
                .add("Nicht geprüft", String.valueOf(summary.unverified()))
                .add("Fehlgeschlagen", String.valueOf(summary.failedPageIds().size()),
                        summary.failedPageIds().isEmpty() ? Comic.INK : Comic.RED)
                .add("Fehlend", String.valueOf(summary.missingPageIds().size()),
                        summary.missingPageIds().isEmpty() ? Comic.INK : Comic.RED)
                .add("Inventar", summary.inventory().name().toLowerCase())
                .add("Vollständig", summary.complete() ? "ja" : "nein", summary.complete() ? Comic.GREEN : Comic.ORANGE)
                .add("Generation", summary.generation() == null ? "keine bestätigt" : String.valueOf(summary.generation()))
                .add("Space-Fingerabdruck", summary.spaceFingerprint());
        if (!summary.failedPageIds().isEmpty()) {
            result.add("Fehlgeschlagene Seiten", String.join(", ", summary.failedPageIds()), Comic.RED);
        }
        if (!summary.missingPageIds().isEmpty()) {
            result.add("Fehlende Seiten", String.join(", ", summary.missingPageIds()), Comic.RED);
        }
        if (!status.state().terminal()) {
            feedback.working("Abgleich läuft …");
        } else if (status.state() == JobState.COMPLETED && summary.complete() && summary.notConfirmed() == null) {
            feedback.success("Abgleich vollständig, Generation " + summary.generation() + " bestätigt");
        } else if (status.state() == JobState.COMPLETED || summary.notConfirmed() != null) {
            feedback.warning("Abgleich nicht bestätigt", summary.notConfirmed() == null
                    ? "Der Lauf war unvollständig; der Bestand gilt nicht als bestätigt."
                    : "Grund: " + summary.notConfirmed());
        } else {
            renderTerminal(status, false);
        }
    }

    private void renderExport(ExportDetail export) {
        ExportArtifact artifact = export.artifact();
        if (artifact == null) {
            feedback.warning("Export fertig, aber kein Artefakt", "Die Exportablage kennt diesen Export nicht mehr.");
            return;
        }
        for (ExportArtifact.File file : artifact.files()) {
            result.add(file.name(), Texts.bytes(file.bytes()) + " · SHA-256 " + Texts.shortHash(file.sha256()));
        }
        if (artifact.complete()) {
            feedback.success("Export vollständig: " + Texts.count(artifact.files().size(), "Datei", "Dateien"));
        } else {
            feedback.warning("Export unvollständig", "Einzelne Seiten ließen sich nicht aus der Quelle lesen; das "
                    + "Manifest nennt sie mit Grund.");
        }
    }

    private void renderTerminal(JobStatus status, boolean plain) {
        switch (status.state()) {
            case COMPLETED -> {
                if (plain) {
                    feedback.success(Texts.type(status.type()) + " fertig");
                }
            }
            case CANCELLED -> feedback.warning("Abgebrochen", "Teilergebnisse bleiben unbestätigt.");
            case FAILED -> feedback.warning("Fehlgeschlagen", status.error() == null ? null
                    : status.error().code() + ": " + status.error().message());
            default -> feedback.working(Texts.state(status.state()));
        }
    }

    private void updateButtons() {
        Optional<JobStatus> current = currentStatus();
        boolean running = current.map(s -> !s.state().terminal()).orElse(false);
        cancel.setEnabled(running && !current.get().cancelRequested());
        again.setVisible(current.map(s -> s.type() == JobType.SYNC).orElse(false));
        again.setEnabled(!running);
        files.setVisible(detail instanceof ExportDetail export && export.artifact() != null);
        content.setEnabled(current.isPresent());
        diagnosis.setEnabled(current.isPresent());
        // Laufende Aufträge erst abbrechen; gelöscht wird nur, was beendet ist.
        delete.setEnabled(current.isPresent() && !running);
        delete.setToolTipText(running ? "Läuft noch: erst abbrechen, dann löschen"
                : "Diesen beendeten Auftrag samt Diagnose löschen");
        // Ein gescheiterter Auftrag führt zuerst zu seiner Diagnose.
        boolean failed = current.map(s -> s.state() == JobState.FAILED).orElse(false);
        diagnosis.setText(failed ? "Fehler diagnostizieren" : "Diagnose ansehen");
    }

    private void cancelSelected() {
        AppRuntime runtime = context.runtime();
        Optional<JobStatus> current = currentStatus();
        if (runtime == null || current.isEmpty()) {
            return;
        }
        JobStatus status = current.get();
        if (!Dialogs.confirm(root, "Auftrag abbrechen?", Texts.type(status.type()) + " von " + status.partitionKey()
                + " abbrechen? Bereits Gelesenes bleibt unbestätigt.", "Abbrechen")) {
            return;
        }
        Background.run(() -> status.type() == JobType.SYNC ? runtime.workspace().cancelSync(status.id()).status()
                : runtime.workspace().cancelExport(status.id()), s -> poll(), feedback::problem);
    }

    static final String KEPT = "Exportdateien, die unter „Dateien speichern …“ abgelegt wurden, und der Seitenbestand "
            + "bleiben erhalten; die interne Kopie des Exports verschwindet mit dem Auftrag.";

    private void deleteSelected() {
        AppRuntime runtime = context.runtime();
        Optional<JobStatus> current = currentStatus();
        if (runtime == null || current.isEmpty() || !current.get().state().terminal()) {
            return;
        }
        JobStatus status = current.get();
        if (!Dialogs.confirm(root, "Auftrag löschen?", Texts.type(status.type()) + " von " + status.partitionKey()
                + " und seine Diagnoseereignisse werden dauerhaft gelöscht. " + KEPT, "Löschen")) {
            return;
        }
        Background.execute(() -> runtime.workspace().deleteJob(status.id()), () -> {
            selectedId = null;
            detail = null;
            table.clearSelection();
            render();
            feedback.success("Auftrag gelöscht");
            poll();
        }, feedback::problem);
    }

    private void deleteFinished() {
        AppRuntime runtime = context.runtime();
        // Genau die Aufträge, die der Dialog nennt; was danach erst endet, bleibt stehen.
        List<String> confirmed = model.rows.stream().filter(j -> j.state().terminal()).map(JobStatus::id).toList();
        long finished = confirmed.size();
        if (runtime == null || finished == 0) {
            return;
        }
        long running = model.rows.size() - finished;
        if (!Dialogs.confirm(root, "Abgeschlossene Aufträge löschen?", Texts.count(finished, "beendeter Auftrag",
                "beendete Aufträge") + " (abgeschlossen, gescheitert, abgebrochen) und ihre Diagnoseereignisse werden "
                + "dauerhaft gelöscht." + (running == 0 ? "" : " " + Texts.count(running, "laufender Auftrag bleibt",
                "laufende Aufträge bleiben") + ".") + " " + KEPT, "Löschen")) {
            return;
        }
        boolean selectedFinished = currentStatus().map(s -> s.state().terminal()).orElse(false);
        Background.run(() -> runtime.workspace().deleteJobs(confirmed), deleted -> {
            if (selectedFinished) {
                selectedId = null;
                detail = null;
                table.clearSelection();
                render();
            }
            feedback.success(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht");
            poll();
        }, feedback::problem);
    }

    private void restart() {
        AppRuntime runtime = context.runtime();
        Optional<JobStatus> current = currentStatus();
        if (runtime == null || current.isEmpty()) {
            return;
        }
        JobStatus status = current.get();
        Background.run(() -> runtime.workspace().startSync(status.sourceId(), status.partitionKey()),
                started -> show(started.id()), feedback::problem);
    }

    private void saveFiles() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || !(detail instanceof ExportDetail export) || export.artifact() == null) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Zielordner für die Exportdateien");
        if (chooser.showSaveDialog(root) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath().resolve(export.status().sourceId() + "-"
                + export.status().partitionKey() + "-" + export.status().id());
        feedback.working("Dateien werden gespeichert …");
        Background.run(() -> {
            Files.createDirectories(target);
            int count = 0;
            for (ExportArtifact.File file : export.artifact().files()) {
                Optional<InputStream> in = runtime.workspace().exportFile(export.status().id(), file.name());
                if (in.isPresent()) {
                    Path out = target.resolve(file.name()).normalize();
                    if (!out.startsWith(target)) {
                        continue;
                    }
                    Files.createDirectories(out.getParent());
                    try (InputStream stream = in.get()) {
                        Files.copy(stream, out, StandardCopyOption.REPLACE_EXISTING);
                    }
                    count++;
                }
            }
            return count;
        }, count -> feedback.success(Texts.count(count, "Datei", "Dateien") + " gespeichert in " + target),
                feedback::problem);
    }

    private record ExportDetail(JobStatus status, ExportArtifact artifact) {
    }

    private static final class JobsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Art", "Quelle / Space", "Zustand", "Fortschritt", "Angelegt"};
        private List<JobStatus> rows = new ArrayList<>();

        void set(List<JobStatus> jobs) {
            rows = new ArrayList<>(jobs);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int column) {
            return COLUMNS[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            JobStatus status = rows.get(row);
            return switch (column) {
                case 0 -> Texts.type(status.type());
                case 1 -> status.sourceId() + " / " + status.partitionKey();
                case 2 -> status.state();
                case 3 -> status.progress().processed() + "/" + status.progress().discovered()
                        + (status.progress().failed() > 0 ? " · " + status.progress().failed() + " Fehler" : "");
                default -> Texts.time(status.createdAt());
            };
        }
    }

    private static final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JobState state = (JobState) value;
            super.getTableCellRendererComponent(table, Texts.state(state), selected, focus, row, column);
            setForeground(selected ? Comic.INK : Comic.tone(Texts.tone(state)));
            return this;
        }
    }
}
