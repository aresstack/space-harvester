package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.convert.ConversionNotice;
import com.aresstack.spaceharvester.application.export.ExportConversions;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.Inventory;
import com.aresstack.spaceharvester.application.sync.SyncSummary;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Aufträge: Abgleiche und Downloads dieser App mit Fortschritt, Ergebnis, Teilfehlern und Abbruch. Oben die
 * Auftragsliste über die volle Breite, je Zeile rechtsbündig die Symbolaktionen ({@link RowActions}): Exportieren als
 * PDF, Word, Markdown oder JSON (ein Klick erzeugt die Datei sofort), Diagnose, Inhalte, Löschen. Darunter links die
 * Kachel „Auftrag“, rechts „Ergebnis“ und „Exportieren“ (Ausgabeordner, erzeugte Dateien, Hinweise). Der Fortschritt
 * steht im globalen Balken über der Statusleiste ({@link #actions()}), mittig „Abbrechen“, danach der Endzustand. Ein
 * Lauf heißt nur dann erfolgreich, wenn er vollständig ist; ein Abgleich, der keine Generation bestätigen konnte,
 * erscheint als Warnung mit Grund.
 */
public final class ActivitiesView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JobsModel model = new JobsModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JLabel title = new JLabel("Kein Auftrag gewählt");
    /** Der globale Fortschrittsbalken über der Statusleiste; darauf links der Schritt, mittig Abbrechen bzw. der Endzustand. */
    final JProgressBar progress = new JProgressBar();
    private final JPanel progressBar = new JPanel(new BorderLayout());
    final JLabel progressText = Comic.muted(" ");
    /** Mittig im Balken: „Fertig“, „Abgebrochen“ oder „Fehlgeschlagen“, sobald der Auftrag endet. */
    final JLabel endState = new JLabel(" ");
    private final Facts facts = new Facts();
    private final Facts result = new Facts();
    private final Feedback feedback = new Feedback();
    final ComposerButton cancel = Comic.danger("Abbrechen", "Auftrag abbrechen");
    private final ComposerButton again = Comic.button("Erneut abgleichen", "Denselben Space erneut abgleichen");
    /** Kachel „Exportieren“ eines fertigen Downloads: Datenbestand, erzeugte Dateien, Ausgabeordner, Hinweise. */
    final JPanel card = new JPanel(new BorderLayout(0, 6));
    private final JComponent cardPlate;
    private final JComponent details;
    private final JLabel cardTitle = new JLabel();
    private final JLabel cardLine = new JLabel();
    /** „Bilder: …“ mit Hinweis, solange sie noch beschafft werden; bricht um, damit der Hinweis nie abgeschnitten wird. */
    final JTextArea cardImages = Comic.note("");
    /** Die Dateien dieses Downloads im Ausgabeordner und laufende Exporte, eine Zeile je Datei. */
    final JTextArea files = Comic.note("");
    private final JLabel output = new JLabel();
    private final ComposerButton openOutput = Comic.button("Ordner öffnen", "Den Ausgabeordner anzeigen");
    /** Datenbestand, den die Kachel gerade zeigt. */
    private ExportDatasetInfo shownDataset;
    /** Datenbestand je fertigem Download, für die Format-Symbole in der Liste. */
    private final Map<String, ExportDatasetInfo> datasets = new HashMap<>();
    /** Dateien des gewählten Downloads im Ausgabeordner (Name enthält die Export-ID). */
    private List<String> outputFiles = List.of();
    /** Format, das je Download gerade erzeugt wird. */
    private final Map<String, String> convertingFormat = new HashMap<>();
    /** Hinweise der letzten Konvertierung des gezeigten Downloads (fehlende Bilder, Diagramme …), aufklappbar. */
    final ComposerButton toggleNotices = Comic.button("Hinweise anzeigen", "Was in der Datei fehlt oder vereinfacht ist");
    final JTextArea noticeList = Comic.note("");
    /** Letzte erzeugte Datei je Download, für ihre Hinweise. */
    private final Map<String, ExportConversions.ConvertedFile> lastFile = new HashMap<>();
    /** Letzte Rückmeldung von „Exportieren“ je Download; sie bleibt stehen, während die Ansicht auffrischt. */
    private final Map<String, Consumer<Feedback>> converted = new HashMap<>();
    /** Downloads, deren Konvertierung gerade läuft; ihre Format-Symbole bleiben gesperrt. */
    private final Set<String> converting = new HashSet<>();
    final ComposerButton deleteFinished = Comic.button("Abgeschlossene Aufträge löschen …",
            "Alle beendeten Aufträge samt Diagnose löschen; laufende bleiben");
    private final Timer timer = new Timer(1500, e -> poll());
    private String selectedId;
    private Object detail;
    /** Der Auftrag, dem der globale Balken folgt; „Abbrechen“ bricht ihn ab. */
    private JobStatus barJob;
    /** Jüngste Phase des gewählten Auftrags aus seiner Diagnose, solange er läuft; sonst {@code null}. */
    private String phase;

    public ActivitiesView(AppContext context) {
        this.context = context;
        root.setBackground(Comic.SURFACE);
        table.getColumnModel().getColumn(2).setCellRenderer(new StateRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                select(model.rows.get(table.getSelectedRow()).id());
            }
        });
        RowActions.install(table, JobsModel.ACTIONS, 7, this::rowActions);
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setBackground(Comic.SURFACE);
        list.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        JLabel hint = new JLabel(ComposerIcons.info());
        hint.setToolTipText("<html>Abgleiche startest du unter Wissensquellen verwalten, Downloads im Explorer links "
                + "(Rechtsklick oder Download-Symbol).<br>Beendete Aufträge bleiben sichtbar, bis die Aufbewahrungsfrist "
                + "endet oder sie gelöscht werden.</html>");
        JPanel listHead = new JPanel(new BorderLayout(8, 0));
        listHead.setOpaque(false);
        listHead.add(Comic.row(Comic.caption("Aufträge dieser App"), hint), BorderLayout.WEST);
        listHead.add(Comic.row(deleteFinished), BorderLayout.EAST);
        list.add(listHead, BorderLayout.NORTH);
        list.add(Comic.scroll(table), BorderLayout.CENTER);

        title.setFont(Comic.semiBold(17f));
        title.setForeground(Comic.INK);
        progress.setStringPainted(false);
        progress.setForeground(Comic.BLUE);
        progress.setPreferredSize(new java.awt.Dimension(200, 26));
        cancel.addActionListener(e -> cancelSelected());
        again.addActionListener(e -> restart());
        openOutput.addActionListener(e -> {
            if (context.settings() != null) {
                SettingsView.open(context.settings().outputDirectory(), feedback);
            }
        });
        cardTitle.setFont(Comic.semiBold(15f));
        cardTitle.setForeground(Comic.INK);
        cardLine.setFont(Comic.regular(13f));
        cardLine.setForeground(Comic.INK);
        output.setFont(Comic.regular(13f));
        output.setForeground(Comic.INK);
        JPanel cardHead = new JPanel(new BorderLayout(0, 2));
        cardHead.setOpaque(false);
        cardHead.add(cardTitle, BorderLayout.NORTH);
        cardHead.add(cardLine, BorderLayout.CENTER);
        cardHead.add(cardImages, BorderLayout.SOUTH);
        card.setOpaque(false);
        card.add(cardHead, BorderLayout.NORTH);
        toggleNotices.setVisible(false);
        noticeList.setVisible(false);
        toggleNotices.addActionListener(e -> {
            noticeList.setVisible(!noticeList.isVisible());
            renderNotices();
        });
        JPanel produced = new JPanel(new BorderLayout(0, 4));
        produced.setOpaque(false);
        produced.add(files, BorderLayout.NORTH);
        produced.add(Comic.row(toggleNotices), BorderLayout.CENTER);
        produced.add(noticeList, BorderLayout.SOUTH);
        card.add(produced, BorderLayout.CENTER);
        card.add(Comic.row(output, openOutput), BorderLayout.SOUTH);
        deleteFinished.addActionListener(e -> deleteFinished());

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(Comic.row(again), BorderLayout.EAST);
        head.add(titleRow, BorderLayout.NORTH);
        head.add(feedback, BorderLayout.SOUTH);

        cardPlate = Comic.plate("Exportieren", Comic.GREEN, card);
        cardPlate.setVisible(false);
        JPanel tiles = new JPanel(new java.awt.GridLayout(1, 2, 12, 0));
        tiles.setOpaque(false);
        tiles.add(stack(Comic.plate("Auftrag", Comic.PETROL, facts)));
        tiles.add(stack(Comic.plate("Ergebnis", Comic.ORANGE, result), cardPlate));
        JPanel details = new JPanel(new BorderLayout(0, 8));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        details.add(head, BorderLayout.NORTH);
        details.add(tiles, BorderLayout.CENTER);
        this.details = details;

        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, list,
                Comic.column(details));
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(Comic.SURFACE);
        split.setResizeWeight(0.4);
        split.setDividerLocation(260);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);

        // Der globale Balken: links der Schritt, mittig Abbrechen bzw. der Endzustand, beides über dem Balken.
        endState.setFont(Comic.semiBold(13f));
        endState.setForeground(Comic.INK);
        endState.setVisible(false);
        JPanel center = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        center.setOpaque(false);
        center.add(cancel);
        center.add(endState);
        JPanel overlay = new JPanel(new BorderLayout());
        overlay.setOpaque(false);
        overlay.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 10));
        overlay.add(progressText, BorderLayout.WEST);
        overlay.add(center, BorderLayout.CENTER);
        JPanel layered = new JPanel();
        layered.setOpaque(false);
        layered.setLayout(new javax.swing.OverlayLayout(layered));
        overlay.setAlignmentX(0.5f);
        overlay.setAlignmentY(0.5f);
        progress.setAlignmentX(0.5f);
        progress.setAlignmentY(0.5f);
        overlay.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        progress.setMaximumSize(new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        layered.add(overlay);
        layered.add(progress);
        progressBar.setOpaque(false);
        progressBar.add(layered, BorderLayout.CENTER);
        progressText.setText("Kein Auftrag läuft");
        cancel.setVisible(false);
        updateButtons();
        context.onChange(ignored -> poll());
    }

    /** Platten untereinander, oben ausgerichtet; die Kacheln teilen sich einen Bildlauf statt je einen eigenen. */
    private static JComponent stack(JComponent... plates) {
        JPanel stack = new JPanel(new java.awt.GridBagLayout());
        stack.setOpaque(false);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.weightx = 1;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.anchor = java.awt.GridBagConstraints.NORTH;
        c.insets = new java.awt.Insets(0, 0, 12, 0);
        for (JComponent plate : plates) {
            stack.add(plate, c);
        }
        c.weighty = 1;
        stack.add(Box.createVerticalGlue(), c);
        return stack;
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** Der globale Fortschrittsbalken; das Hauptfenster zeigt ihn in jedem Bereich ohne eigene Aktionszeile. */
    @Override
    public JComponent actions() {
        return progressBar;
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
        Set<String> known = Set.copyOf(datasets.keySet());
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        Background.run(() -> {
            List<JobStatus> jobs = runtime.workspace().jobs();
            // Datenbestand je fertigem Download einmal holen, für die Format-Symbole der Liste.
            Map<String, ExportDatasetInfo> found = new HashMap<>();
            for (JobStatus job : jobs) {
                if (job.type() == JobType.EXPORT && job.state() == JobState.COMPLETED && !known.contains(job.id())) {
                    runtime.workspace().exportDataset(job.id()).ifPresent(info -> found.put(job.id(), info));
                }
            }
            Object current = null;
            String currentPhase = null;
            List<String> produced = List.of();
            if (id != null) {
                JobStatus status = runtime.workspace().job(id);
                if (!status.state().terminal()) {
                    currentPhase = runtime.workspace().events(id).stream().map(JobEvent::phase)
                            .filter(Objects::nonNull).reduce((first, second) -> second).orElse(null);
                }
                current = status.type() == JobType.SYNC ? runtime.workspace().syncStatus(id) : status;
                if (status.type() == JobType.EXPORT && status.state() == JobState.COMPLETED) {
                    ExportDatasetInfo dataset = runtime.workspace().exportDataset(id).orElse(null);
                    current = new ExportDetail(status, runtime.workspace().exportArtifact(id).orElse(null), dataset);
                    if (dataset != null) {
                        found.put(id, dataset);
                    }
                    produced = outputFiles(directory, id);
                }
            } else {
                // Ohne Auswahl folgt der globale Balken dem jüngsten laufenden Auftrag.
                current = jobs.stream().filter(j -> !j.state().terminal()).findFirst().orElse(null);
            }
            return new Object[] {jobs, current, currentPhase, found, produced};
        }, data -> {
            @SuppressWarnings("unchecked")
            List<JobStatus> jobs = (List<JobStatus>) data[0];
            @SuppressWarnings("unchecked")
            Map<String, ExportDatasetInfo> found = (Map<String, ExportDatasetInfo>) data[3];
            datasets.putAll(found);
            datasets.keySet().retainAll(jobs.stream().map(JobStatus::id).collect(java.util.stream.Collectors.toSet()));
            model.set(jobs);
            deleteFinished.setEnabled(jobs.stream().anyMatch(j -> j.state().terminal()));
            reselect();
            // Ein Abruf für einen inzwischen abgewählten Auftrag zeigt nichts mehr an.
            if (java.util.Objects.equals(id, selectedId)) {
                @SuppressWarnings("unchecked")
                List<String> produced = (List<String>) data[4];
                outputFiles = produced;
                if (id == null) {
                    detail = null;
                    renderBar(data[1] instanceof JobStatus running ? running : null);
                } else {
                    detail = data[1];
                }
                phase = (String) data[2];
                render();
            }
            boolean active = jobs.stream().anyMatch(j -> !j.state().terminal());
            if (!active && !root.isShowing()) {
                timer.stop();
            }
        }, error -> {
            feedback.problem(error);
            timer.stop();
        });
    }

    /**
     * Dateien dieses Downloads im Ausgabeordner, neueste zuerst: Name {@code <Space>-<erste 8 Zeichen der
     * Export-ID>[-n].<Endung>} wie {@code ExportConversions}; halbfertige {@code .part}-Dateien zählen nicht.
     */
    static List<String> outputFiles(Path directory, String exportId) {
        if (directory == null || !java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        String mark = "-" + exportId.substring(0, Math.min(8, exportId.length()));
        try (java.util.stream.Stream<Path> entries = java.nio.file.Files.list(directory)) {
            return entries.filter(java.nio.file.Files::isRegularFile)
                    .filter(file -> !file.getFileName().toString().startsWith(".")
                            && file.getFileName().toString().contains(mark))
                    .sorted(Comparator.comparing((Path file) -> file.toFile().lastModified()).reversed())
                    .map(file -> file.getFileName().toString()).toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    /**
     * Der globale Balken für diesen Auftrag: während er läuft der Schritt links und mittig „Abbrechen“, danach mittig
     * der Endzustand; ohne Auftrag „Kein Auftrag läuft“.
     */
    private void renderBar(JobStatus status) {
        barJob = status;
        if (status == null) {
            progress.setIndeterminate(false);
            progress.setValue(0);
            progressText.setText("Kein Auftrag läuft");
            cancel.setVisible(false);
            endState.setVisible(false);
            return;
        }
        JobProgress p = status.progress();
        Step step = Step.of(status.type(), phase, p);
        boolean running = !status.state().terminal();
        String subject = Texts.type(status.type()) + " " + status.partitionKey();
        if (status.state() == JobState.RUNNING && step.total() > 0) {
            progress.setIndeterminate(false);
            progress.setMaximum((int) Math.min(Integer.MAX_VALUE, step.total()));
            progress.setValue((int) Math.min(Integer.MAX_VALUE, step.done()));
            progressText.setText(subject + " · " + step.text());
        } else {
            progress.setIndeterminate(running);
            progress.setMaximum(1);
            // Nach dem Ende bleibt die Spur leer, damit der Endzustand mittig gut lesbar ist.
            progress.setValue(0);
            String state = status.state() == JobState.RUNNING && phase != null ? phase : Texts.state(status.state());
            progressText.setText(subject + (running ? " · " + state : ""));
        }
        cancel.setVisible(running);
        cancel.setEnabled(running && !status.cancelRequested());
        cancel.setText(status.cancelRequested() ? "Abbruch angefordert" : "Abbrechen");
        endState.setVisible(!running);
        endState.setText(switch (status.state()) {
            case COMPLETED -> "Fertig";
            case CANCELLED -> "Abgebrochen";
            case FAILED -> "Fehlgeschlagen";
            default -> Texts.state(status.state());
        });
        endState.setForeground(status.state() == JobState.COMPLETED ? Comic.GREEN
                : status.state() == JobState.FAILED ? Comic.RED : Comic.ORANGE);
    }

    private void select(String id) {
        if (!id.equals(selectedId)) {
            selectedId = id;
            detail = null;
            // Die Karte gehört zum bisherigen Auftrag: sofort ausblenden, nicht erst nach dem nächsten Abruf.
            renderCard();
            details.revalidate();
            details.repaint();
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
            renderCard();
            details.revalidate();
            details.repaint();
            updateButtons();
            return;
        }
        JobStatus status = current.get();
        title.setText(Texts.type(status.type()) + " " + status.sourceId() + " / " + status.partitionKey());
        JobProgress p = status.progress();
        renderBar(status);
        Counters counters = Counters.of(status.type());
        facts.add("Zustand", Texts.state(status.state()), Comic.tone(Texts.tone(status.state())))
                .add("Quelle", status.sourceId())
                .add("Space", status.partitionKey())
                .add("Angelegt", Texts.time(status.createdAt()))
                .add("Gestartet", Texts.time(status.startedAt()))
                .add("Beendet", Texts.time(status.finishedAt()))
                .add("Dauer", Texts.duration(status.startedAt(), status.finishedAt()))
                .add("Phase", status.state() == JobState.RUNNING && phase != null ? phase : "–")
                .add(counters.discovered(), String.valueOf(p.discovered()))
                .add(counters.processed(), String.valueOf(p.processed()))
                .add(counters.written(), String.valueOf(p.written()))
                .add(counters.failed(), String.valueOf(p.failed()), p.failed() > 0 ? Comic.RED : Comic.INK)
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
        renderCard();
        details.revalidate();
        details.repaint();
        updateButtons();
        root.revalidate();
    }

    private void renderSync(JobStatus status, SyncSummary summary) {
        result.add("Gelistet", String.valueOf(summary.listed()))
                .add("Neu", String.valueOf(summary.added()))
                .add("Nur im Katalog", String.valueOf(summary.notLoaded()))
                .add("Veraltet", String.valueOf(summary.outdated()))
                .add("Inhalt geändert", String.valueOf(summary.contentChanged()))
                .add("Metadaten geändert", String.valueOf(summary.metadataChanged()))
                .add("Attribute geändert", String.valueOf(summary.attributesChanged()))
                .add("Verschoben", String.valueOf(summary.moved()))
                .add("Unverändert", String.valueOf(summary.unchanged()))
                .add("Entfernt", String.valueOf(summary.removed()))
                .add("Zurückgehalten", String.valueOf(summary.withheld()))
                .add("Endgültig bereinigt", String.valueOf(summary.purged()))
                .add("In anderen Space verschoben", String.valueOf(summary.movedOut()))
                .add("Nicht geprüft", String.valueOf(summary.unverified()))
                .add("Fehlgeschlagen", String.valueOf(summary.failedPageIds().size()),
                        summary.failedPageIds().isEmpty() ? Comic.INK : Comic.RED)
                .add("Fehlend", String.valueOf(summary.missingPageIds().size()),
                        summary.missingPageIds().isEmpty() ? Comic.INK : Comic.RED)
                .add("Abfrage", inventory(summary.inventory()))
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

    /** Wie der Lauf die Seiten der Quelle gelesen hat. */
    static String inventory(Inventory.Status status) {
        return switch (status) {
            case COMPLETE -> "Bestandsinventur, vollständig";
            case INCOMPLETE -> "Bestandsinventur, abgebrochen";
            case INCONSISTENT -> "Bestandsinventur, widersprüchlich";
            case CHANGES -> "Änderungssuche seit dem letzten Lauf";
        };
    }

    private void renderExport(ExportDetail export) {
        ExportArtifact artifact = export.artifact();
        if (artifact == null) {
            feedback.warning("Download fertig, aber kein Datenbestand", "Die Ablage kennt diesen Download nicht mehr.");
            return;
        }
        ExportDatasetInfo dataset = export.dataset();
        if (dataset != null) {
            result.add("Seiten", String.valueOf(dataset.pages()))
                    .add("Datenbestand", "JSON vorhanden")
                    .add("Bilder", images(dataset.images()), dataset.images() == ExportDatasetInfo.Images.INCOMPLETE
                            ? Comic.RED : Comic.INK);
        }
        Consumer<Feedback> conversion = converted.get(export.status().id());
        if (conversion != null) {
            conversion.accept(feedback);
        } else if (artifact.complete()) {
            feedback.success("Download vollständig");
        } else {
            feedback.warning("Export unvollständig", "Einzelne Seiten ließen sich nicht aus der Quelle lesen oder "
                    + "die Quelle war nicht erreichbar und der Export nutzt einen unbestätigten Stand; das Manifest "
                    + "nennt den Grund.");
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

    /** Zeigt die Kachel „Exportieren“, solange der gewählte Download einen Datenbestand hat. */
    private void renderCard() {
        ExportDatasetInfo dataset = detail instanceof ExportDetail export ? export.dataset() : null;
        cardPlate.setVisible(dataset != null);
        if (dataset == null) {
            shownDataset = null;
            return;
        }
        Path directory = context.settings() == null ? null : context.settings().outputDirectory();
        output.setText("Ausgabeordner: " + (directory == null ? "–" : name(directory)));
        output.setToolTipText(directory == null ? null : directory.toString());
        renderFiles(dataset.exportId());
        if (dataset.equals(shownDataset)) {
            return;
        }
        boolean otherDownload = shownDataset == null || !shownDataset.exportId().equals(dataset.exportId());
        shownDataset = dataset;
        if (otherDownload) {
            noticeList.setVisible(false);
        }
        renderNotices();
        cardTitle.setText("Export · " + dataset.spaceKey());
        cardImages.setText(cardImages(dataset.images()));
        cardImages.setForeground(dataset.images() == ExportDatasetInfo.Images.INCOMPLETE ? Comic.RED : Comic.INK);
        cardLine.setText(Texts.count(dataset.pages(), "Seite", "Seiten") + " · JSON-Datenbestand vorhanden"
                + (dataset.complete() ? "" : " · unvollständig"));
    }

    /** Erzeugte Dateien und ein laufender Export des gezeigten Downloads. */
    private void renderFiles(String exportId) {
        StringBuilder text = new StringBuilder();
        String running = convertingFormat.get(exportId);
        if (running != null) {
            text.append(Format.title(running)).append(": wird erzeugt …");
        }
        for (String name : outputFiles) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append(name);
        }
        files.setText(text.isEmpty() ? "Noch nichts exportiert; die Format-Symbole in der Liste erzeugen die Datei."
                : text.toString());
        files.setForeground(text.isEmpty() ? Comic.MUTED : Comic.INK);
    }

    /**
     * Exportiert einen fertigen Download sofort im Format (Symbol in der Liste): die Datei entsteht aus dem
     * vorhandenen Datenbestand im Ausgabeordner, ohne die Quelle erneut zu lesen. Je Download läuft höchstens ein
     * Export; weitere Klicks sind gesperrt, bis er endet.
     */
    void exportAs(String exportId, String format) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || context.settings() == null || !converting.add(exportId)) {
            return;
        }
        Path directory = context.settings().outputDirectory();
        convertingFormat.put(exportId, format);
        select(exportId);
        table.repaint();
        showConverted(exportId, f -> f.working(Format.title(format) + " wird erzeugt …"));
        Background.run(() -> runtime.workspace().convertExport(exportId, format, directory), file -> {
            converting.remove(exportId);
            convertingFormat.remove(exportId);
            table.repaint();
            lastFile.put(exportId, file);
            String notices = file.noticeCount() == 0 ? ""
                    : ", " + Texts.count(file.noticeCount(), "Hinweis", "Hinweise");
            showConverted(exportId, f -> f.success(file.path().getFileName() + " gespeichert in " + name(directory)
                    + notices));
            poll();
        }, error -> {
            converting.remove(exportId);
            convertingFormat.remove(exportId);
            table.repaint();
            showConverted(exportId, f -> f.problem(error));
        });
    }

    /** Merkt die Rückmeldung für ihren Download und zeigt sie nur, wenn die Karte gerade diesen Download zeigt. */
    private void showConverted(String exportId, Consumer<Feedback> show) {
        converted.put(exportId, show);
        if (shownDataset == null || !shownDataset.exportId().equals(exportId)) {
            return;
        }
        renderFiles(exportId);
        noticeList.setVisible(false);
        renderNotices();
        show.accept(feedback);
    }

    /** Knopf und Liste der Hinweise zur zuletzt erzeugten Datei des gezeigten Downloads. */
    private void renderNotices() {
        ExportConversions.ConvertedFile file = shownDataset == null ? null : lastFile.get(shownDataset.exportId());
        boolean any = file != null && file.noticeCount() > 0;
        toggleNotices.setVisible(any);
        if (!any) {
            noticeList.setVisible(false);
            return;
        }
        toggleNotices.setText(noticeList.isVisible() ? "Hinweise ausblenden"
                : Texts.count(file.noticeCount(), "Hinweis", "Hinweise") + " anzeigen");
        noticeList.setText(notices(file));
    }

    /** Die Hinweise in verständlichen Worten, eine Zeile je Hinweis. */
    static String notices(ExportConversions.ConvertedFile file) {
        StringBuilder text = new StringBuilder();
        for (ConversionNotice notice : file.notices()) {
            if (!text.isEmpty()) {
                text.append('\n');
            }
            text.append("Seite ").append(notice.pageId()).append(": ").append(kind(notice.kind()))
                    .append(" „").append(notice.reference()).append('“');
            String reason = reason(notice.reason());
            if (reason != null) {
                text.append(" (").append(reason).append(')');
            }
        }
        int more = file.noticeCount() - file.notices().size();
        if (more > 0) {
            text.append("\n… und ").append(Texts.count(more, "weiterer Hinweis", "weitere Hinweise"));
        }
        return text.toString();
    }

    private static String kind(ConversionNotice.Kind kind) {
        return switch (kind) {
            case MISSING_IMAGE -> "fehlendes Bild";
            case MISSING_DIAGRAM -> "fehlendes Diagramm";
            case MISSING_ATTACHMENT -> "fehlender Anhang";
            case OMITTED_CONTENT -> "ausgelassener Inhalt";
        };
    }

    private static String reason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return switch (reason) {
            case "missing" -> "bei der Quelle nicht gefunden";
            case "forbidden" -> "keine Berechtigung";
            case "too-large" -> "zu groß";
            case "unavailable" -> "Quelle nicht erreichbar";
            case "not-collected" -> "Bilder noch nicht beschafft";
            case "render-failed" -> "Darstellung fehlgeschlagen";
            default -> reason;
        };
    }

    private static String name(Path directory) {
        Path name = directory.getFileName();
        return name == null ? directory.toString() : name.toString();
    }

    /** Zeile der Karte; Exportieren bleibt in jedem Stand möglich. */
    static String cardImages(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "Bilder: nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "Bilder: werden beschafft · Dokumente, die du jetzt exportierst, entstehen noch ohne "
                    + "Bilder";
            case COMPLETE -> "Bilder: vollständig";
            case INCOMPLETE -> "Bilder: unvollständig · fehlende Bilder nennt das Dokument";
        };
    }

    private static String images(ExportDatasetInfo.Images images) {
        return switch (images) {
            case NOT_COLLECTED -> "noch nicht beschafft, Dokumente enthalten nur Verweise";
            case COLLECTING -> "werden beschafft …";
            case COMPLETE -> "vollständig";
            case INCOMPLETE -> "unvollständig, fehlende Bilder nennt das Dokument";
        };
    }

    /** Zielformat mit Anzeigenamen in der Reihenfolge aus Angelos Skizze; unbekannte folgen mit ihrem Kürzel. */
    record Format(String id, String title) {

        private static final List<String> ORDER = List.of("pdf", "docx", "markdown", "json");
        private static final Map<String, String> TITLES = Map.of("pdf", "PDF-Dokument", "docx", "Word-Dokument",
                "markdown", "Markdown", "json", "JSON");

        static String title(String format) {
            return TITLES.getOrDefault(format, format.toUpperCase(Locale.ROOT));
        }

        static List<Format> ordered(List<String> formats) {
            return formats.stream().sorted(Comparator.comparingInt((String f) -> ORDER.contains(f) ? ORDER.indexOf(f)
                    : ORDER.size())).map(f -> new Format(f, TITLES.getOrDefault(f, f.toUpperCase(Locale.ROOT))))
                    .toList();
        }

        @Override
        public String toString() {
            return title;
        }
    }

    private void updateButtons() {
        Optional<JobStatus> current = currentStatus();
        boolean running = current.map(s -> !s.state().terminal()).orElse(false);
        again.setVisible(current.map(s -> s.type() == JobType.SYNC).orElse(false));
        again.setEnabled(!running);
    }

    /** Formate der Symbole in der Liste, in der Reihenfolge aus Angelos Skizze. */
    static final List<String> FORMATS = List.of("pdf", "docx", "markdown", "json");
    private static final Map<String, String> EXPORT_TIPS = Map.of("pdf", "Als PDF exportieren", "docx",
            "Als Word-Dokument exportieren", "markdown", "Als Markdown exportieren", "json", "Als JSON exportieren");
    private static final Map<String, javax.swing.Icon> FORMAT_ICONS = Map.of("pdf", FormatIcon.of("pdf"), "docx",
            FormatIcon.of("docx"), "markdown", FormatIcon.of("markdown"), "json", FormatIcon.of("json"));
    private static final javax.swing.Icon DIAGNOSIS = com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons.compass();
    private static final javax.swing.Icon CONTENT = ComposerIcons.openBook();

    /** Die sieben Aktionen einer Zeile: vier Formate, Diagnose, Inhalte, Löschen. */
    private List<RowActions.Action> rowActions(int index) {
        if (index < 0 || index >= model.rows.size()) {
            return List.of();
        }
        JobStatus job = model.rows.get(index);
        boolean running = !job.state().terminal();
        List<RowActions.Action> actions = new ArrayList<>();
        for (String format : FORMATS) {
            String blocked = exportBlocked(job, format);
            actions.add(new RowActions.Action(FORMAT_ICONS.get(format), blocked == null ? EXPORT_TIPS.get(format)
                    : EXPORT_TIPS.get(format) + " · " + blocked, blocked == null, () -> exportAs(job.id(), format)));
        }
        boolean failed = job.state() == JobState.FAILED;
        actions.add(new RowActions.Action(DIAGNOSIS, failed ? "Fehler diagnostizieren" : "Diagnose ansehen", true,
                () -> context.navigator().showDiagnosis(job.id())));
        boolean space = job.sourceId() != null && job.partitionKey() != null;
        actions.add(new RowActions.Action(CONTENT, space ? "Inhalte öffnen" : "Inhalte öffnen · kein Space", space,
                () -> context.navigator().openSpace(job.sourceId(), job.partitionKey())));
        actions.add(new RowActions.Action(Glyph.TRASH, running ? "Auftrag löschen · läuft noch, erst abbrechen"
                : "Auftrag löschen", !running, () -> deleteJob(job)));
        return actions;
    }

    /** Für Tests. */
    JTable table() {
        return table;
    }

    /** Für Tests: ob das Format-Symbol dieses Auftrags in der Liste anklickbar ist, sonst der Grund. */
    String exportBlocked(String jobId, String format) {
        for (JobStatus job : model.rows) {
            if (job.id().equals(jobId)) {
                return exportBlocked(job, format);
            }
        }
        return "unbekannter Auftrag";
    }

    /** Warum dieses Format für den Auftrag gerade nicht geht, oder {@code null}. */
    private String exportBlocked(JobStatus job, String format) {
        if (job.type() != JobType.EXPORT) {
            return "nur für Downloads";
        }
        if (!job.state().terminal()) {
            return "Download läuft noch";
        }
        if (job.state() != JobState.COMPLETED) {
            return "Download nicht abgeschlossen";
        }
        if (converting.contains(job.id())) {
            return Format.title(convertingFormat.getOrDefault(job.id(), format)) + " wird gerade erzeugt";
        }
        ExportDatasetInfo dataset = datasets.get(job.id());
        if (dataset == null) {
            return "kein Datenbestand vorhanden";
        }
        return dataset.formats().contains(format) ? null : "Format nicht angeboten";
    }

    private void cancelSelected() {
        AppRuntime runtime = context.runtime();
        JobStatus status = barJob;
        if (runtime == null || status == null || status.state().terminal()) {
            return;
        }
        if (!Dialogs.confirm(root, "Auftrag abbrechen?", Texts.type(status.type()) + " von " + status.partitionKey()
                + " abbrechen? Bereits Gelesenes bleibt unbestätigt.", "Abbrechen")) {
            return;
        }
        Background.run(() -> switch (status.type()) {
            case SYNC -> runtime.workspace().cancelSync(status.id()).status();
            case EXPORT -> runtime.workspace().cancelExport(status.id());
            default -> runtime.workspace().cancelJob(status.id());
        }, s -> poll(), feedback::problem);
    }

    /**
     * Fortschritt eines laufenden Auftrags in seiner Phase: beim Abrufen zählen abgerufene oder geprüfte Seiten, beim
     * Exportieren die exportierten; eine Anreicherung zählt Schritte (Seiten × Prozessoren). Phasen ohne Seitenzählung
     * (Inventur, Konvertieren, Ablegen) haben kein Ziel und laufen unbestimmt. Kein Zähler zählt doppelt,
     * das regelt der Kern (docs/DIAGNOSTICS.md).
     */
    record Step(String text, long done, long total) {

        static Step of(JobType type, String phase, JobProgress p) {
            long total = p.discovered();
            if (type == JobType.ENRICHMENT) {
                return new Step((phase == null ? "Anreichern" : phase) + " · " + p.processed() + " von " + total
                        + " Schritten", p.processed(), total);
            }
            if (type == JobType.ASSETS) {
                return new Step("Bilder beschaffen · " + p.processed() + " von " + total + " Bildern geprüft, "
                        + p.written() + " abgelegt", p.processed(), total);
            }
            if (type == JobType.EXPORT && phase != null && phase.startsWith("Seiten exportieren")) {
                return new Step("Seiten exportieren · " + p.written() + " von " + total + " exportiert",
                        p.written(), total);
            }
            if (phase != null && !phase.equals("Einlesen") && !phase.equals("Seiten abrufen")) {
                // Bestand prüfen, Inventur, Konvertieren, Ablegen: keine Seitenzählung, nur die Phase
                return new Step(phase, 0, 0);
            }
            String prefix = phase == null ? "" : phase + " · ";
            return new Step(prefix + p.processed() + " von " + total + " Seiten geladen oder geprüft",
                    p.processed(), total);
        }
    }

    /** Was die vier Zähler je Auftragsart bedeuten. */
    record Counters(String discovered, String processed, String written, String failed) {

        static Counters of(JobType type) {
            return switch (type) {
                case EXPORT -> new Counters("Seiten (Inventur)", "Geladen oder geprüft", "Exportiert", "Fehlend");
                case ENRICHMENT -> new Counters("Schritte (Seiten × Prozessoren)", "Erledigt", "Neu berechnet",
                        "Fehlgeschlagen");
                case ASSETS -> new Counters("Bildverweise", "Geprüft", "Abgelegt", "Fehlend");
                default -> new Counters("Gefunden", "Verarbeitet", "Geschrieben", "Fehlgeschlagen");
            };
        }
    }

    static final String KEPT = "Dateien im Ausgabeordner und der Seitenbestand bleiben erhalten; der JSON-Datenbestand "
            + "des Downloads verschwindet mit dem Auftrag.";

    /** Löscht einen beendeten Auftrag samt Diagnose nach Rückfrage (Symbol in der Liste). */
    private void deleteJob(JobStatus status) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || !status.state().terminal()) {
            return;
        }
        if (!Dialogs.confirm(root, "Auftrag löschen?", Texts.type(status.type()) + " von " + status.partitionKey()
                + " und seine Diagnoseereignisse werden dauerhaft gelöscht. " + KEPT, "Löschen")) {
            return;
        }
        Background.execute(() -> runtime.workspace().deleteJob(status.id()), () -> {
            if (status.id().equals(selectedId)) {
                selectedId = null;
                detail = null;
                table.clearSelection();
                render();
            }
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

    private record ExportDetail(JobStatus status, ExportArtifact artifact, ExportDatasetInfo dataset) {
    }

    private static final class JobsModel extends AbstractTableModel {

        static final int ACTIONS = 5;
        private static final String[] COLUMNS = {"Art", "Quelle / Space", "Zustand", "Fortschritt", "Angelegt", ""};
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
                case 3 -> (status.type() == JobType.EXPORT ? status.progress().written() : status.progress().processed())
                        + "/" + status.progress().discovered()
                        + (status.progress().failed() > 0 ? " · " + status.progress().failed() + " Fehler" : "");
                case 4 -> Texts.time(status.createdAt());
                default -> "";
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
