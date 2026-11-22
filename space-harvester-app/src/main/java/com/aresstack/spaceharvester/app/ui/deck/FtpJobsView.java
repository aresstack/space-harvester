package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.JobOutput;
import com.aresstack.spaceharvester.deck.ftp.jes.JobStatus;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import java.awt.BorderLayout;
import java.awt.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * Unteransicht „JES-Jobs“ des FTP-Bereichs (Jobverwaltung wie in MainframeMate): oben die Filter Besitzer, Jobname und
 * Zustand (gefiltert wird auf dem Host), links die Jobliste mit Rückgabe in Ampelfarben, rechts die Spool-Dateien des
 * gewählten Jobs und ihr Text. Einreichen, Aktualisieren, Spool speichern und Löschen stehen unten. Aktualisiert wird
 * nur auf Wunsch und nach eigenem Einreichen oder Löschen, nie von selbst.
 */
final class FtpJobsView extends JPanel {

    private final FtpSessionView owner;
    private final PlaceholderField ownerField = new PlaceholderField(8, "eigene");
    private final PlaceholderField jobName = new PlaceholderField(10, "*");
    private final JComboBox<JobStatus> status = Comic.combo();
    private final Jobs model = new Jobs();
    private final JTable table = Comic.table(new JTable(model));
    private final DefaultListModel<SpoolFile> spoolModel = new DefaultListModel<>();
    private final JList<SpoolFile> spoolList = new JList<>(spoolModel);
    private final JTextArea spoolText = Comic.code();
    /** Die Spool-Datei, deren Text gerade im Textfeld steht; {@code null} während des Ladens. */
    private SpoolFile loadedSpool;
    private final JLabel spoolTitle = Comic.caption("Spool");
    private final JPanel actions;
    private final ComposerButton submit;
    private final ComposerButton refresh;
    private final ComposerButton save;
    private final ComposerButton delete;
    private JobOutput output;
    private String outputJob;
    private boolean started;
    private boolean connected;
    private boolean refreshWanted;
    /** Was der nächsten Liste im Stand vorangestellt wird (etwa „Job … eingereicht“), damit es nicht verschwindet. */
    private String note = "";

    FtpJobsView(FtpSessionView owner) {
        super(new BorderLayout());
        this.owner = owner;
        setOpaque(false);

        for (JobStatus candidate : JobStatus.values()) {
            status.addItem(candidate);
        }
        status.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof JobStatus jobStatus) {
                    setText(jobStatus.label());
                }
                return this;
            }
        });
        status.addActionListener(e -> {
            if (started) {
                refresh();
            }
        });
        ownerField.setToolTipText("Besitzer, Muster erlaubt (KKR*, * für alle); leer = angemeldeter Benutzer");
        jobName.setToolTipText("Jobname, Muster erlaubt (BUILD*, *)");
        ownerField.addActionListener(e -> refresh());
        jobName.addActionListener(e -> refresh());
        InputFrame nameFrame = new InputFrame(jobName);
        IconButton reload = new IconButton(Glyph.REFRESH, "Jobliste neu abfragen");
        reload.addActionListener(e -> refresh());
        nameFrame.addButton(reload);
        JPanel head = Comic.row(Comic.text("Besitzer"), new InputFrame(ownerField), Comic.text("Jobname"), nameFrame,
                Comic.text("Zustand"), status);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        int[] widths = {90, 90, 80, 80, 50, 110, 50};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.getColumnModel().getColumn(5).setCellRenderer(new ReturnCodeRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                jobSelected();
            }
        });
        table.getAccessibleContext().setAccessibleName("Jobs");
        spoolList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        spoolList.setFont(Comic.regular(13f));
        spoolList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof SpoolFile file) {
                    setText(file.label() + (file.records() > 0 ? "  · " + file.records() + " Z." : ""));
                    setToolTipText("Spool-Datei " + file.id() + (file.stepName().isEmpty() ? ""
                            : ", Schritt " + file.stepName()) + (file.outputClass().isEmpty() ? ""
                            : ", Klasse " + file.outputClass()));
                }
                setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                if (selected) {
                    setBackground(Comic.YELLOW);
                    setForeground(Comic.INK);
                }
                return this;
            }
        });
        spoolList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                spoolSelected();
            }
        });
        spoolList.getAccessibleContext().setAccessibleName("Spool-Dateien");
        spoolText.setEditable(false);
        spoolText.getAccessibleContext().setAccessibleName("Spool-Text");
        JPanel spoolPanel = new JPanel(new BorderLayout(8, 4));
        spoolPanel.setOpaque(false);
        spoolPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        spoolPanel.add(spoolTitle, BorderLayout.NORTH);
        var listScroll = Comic.scroll(spoolList);
        listScroll.setPreferredSize(new java.awt.Dimension(170, 100));
        spoolPanel.add(listScroll, BorderLayout.WEST);
        spoolPanel.add(Comic.scroll(spoolText), BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Comic.scroll(table), spoolPanel);
        ComicDivider.install(split, Comic.PALETTE);
        split.setResizeWeight(0.5);
        split.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        split.setOpaque(false);
        add(head, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        submit = Comic.button("Job einreichen …", "Lokale JCL-Datei an JES übergeben");
        submit.addActionListener(e -> submitFile());
        refresh = Comic.button("Aktualisieren", "Jobliste neu abfragen");
        refresh.addActionListener(e -> refresh());
        save = Comic.button("Spool speichern …", "Text der gewählten Spool-Datei speichern");
        save.addActionListener(e -> saveSpool());
        delete = Comic.danger("Job löschen", "Ausgabe des gewählten Jobs aus dem Spool löschen (mit Rückfrage)");
        delete.addActionListener(e -> deleteJob());
        actions = Comic.row(submit, refresh, save, delete);
        updateActions();
    }

    JPanel actions() {
        return actions;
    }

    void setConnected(boolean value) {
        connected = value;
        updateActions();
    }

    /** Beim ersten Zeigen einmal abfragen. */
    void startOnce() {
        if (!started) {
            started = true;
            refresh();
        } else if (refreshWanted) {
            refresh();
        }
    }

    /** Nach eigenem Einreichen: beim nächsten Zeigen (gleich danach) neu abfragen. */
    void refreshLater(String result) {
        note = result == null ? "" : result;
        refreshWanted = true;
        if (isShowing()) {
            refresh();
        }
    }

    JobFilter filter() {
        return new JobFilter(ownerField.getText(), jobName.getText(), (JobStatus) status.getSelectedItem());
    }

    void refresh() {
        started = true;
        refreshWanted = false;
        FtpSession session = owner.session();
        if (!session.user().isEmpty()) {
            ownerField.setPlaceholder(session.user().toUpperCase(java.util.Locale.ROOT));
        }
        JobFilter filter = filter();
        String keep = selectedJob() == null ? null : selectedJob().jobId();
        String prefix = note.isEmpty() ? "" : note + " · ";
        note = "";
        owner.status(prefix + "Frage Jobs ab …", false);
        owner.run(session.jobs(jes -> jes.jobs(filter)), prefix + "Jobliste nicht geladen", jobs -> {
            model.show(jobs);
            owner.status(prefix + jobs.size() + (jobs.size() == 1 ? " Job" : " Jobs") + " · Besitzer "
                    + filter.ownerOr(session.user()) + ", Name " + filter.jobName() + ", "
                    + filter.status().label(), false);
            int row = keep == null ? -1 : model.indexOf(keep);
            if (row >= 0) {
                table.getSelectionModel().setSelectionInterval(row, row);
            } else {
                clearSpool();
            }
            updateActions();
        });
    }

    private JesJob selectedJob() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.at(table.convertRowIndexToModel(row));
    }

    private void jobSelected() {
        JesJob job = selectedJob();
        updateActions();
        if (job == null) {
            clearSpool();
            return;
        }
        if (job.jobId().equals(outputJob) && output != null) {
            return;
        }
        clearSpool();
        outputJob = job.jobId();
        spoolTitle.setText("Spool · " + job.jobId() + " " + job.jobName());
        owner.run(owner.session().jobs(jes -> JobOutput.load(jes, job.jobId())),
                "Spool von " + job.jobId() + " nicht geladen", loaded -> {
                    if (!job.jobId().equals(outputJob)) {
                        return;
                    }
                    output = loaded;
                    loaded.files().forEach(spoolModel::addElement);
                    if (!spoolModel.isEmpty()) {
                        spoolList.setSelectedIndex(0);
                    } else {
                        spoolText.setText(job.finished() ? "Keine Spool-Dateien." : "Der Job ist noch nicht fertig ("
                                + job.status() + ").");
                    }
                    updateActions();
                });
    }

    private void spoolSelected() {
        SpoolFile file = spoolList.getSelectedValue();
        JobOutput current = output;
        String jobId = outputJob;
        // Den alten Text sofort verwerfen: sonst speichert „Spool speichern“ den Text der vorigen Datei unter neuem Namen.
        loadedSpool = null;
        spoolText.setText("");
        updateActions();
        if (file == null || current == null) {
            return;
        }
        owner.run(owner.session().jobs(jes -> current.text(jes, jobId, file)),
                "Spool-Datei " + file.ddName() + " nicht geladen", text -> {
                    if (file.equals(spoolList.getSelectedValue())) {
                        loadedSpool = file;
                        spoolText.setText(text);
                        spoolText.setCaretPosition(0);
                        updateActions();
                    }
                });
    }

    private void clearSpool() {
        output = null;
        outputJob = null;
        loadedSpool = null;
        spoolModel.clear();
        spoolText.setText("");
        spoolTitle.setText("Spool");
    }

    private void updateActions() {
        JesJob job = selectedJob();
        submit.setEnabled(connected);
        refresh.setEnabled(connected);
        delete.setEnabled(connected && job != null);
        SpoolFile selectedSpool = spoolList.getSelectedValue();
        save.setEnabled(connected && selectedSpool != null && selectedSpool.equals(loadedSpool));
    }

    private void submitFile() {
        JFileChooser chooser = new JFileChooser(SaveChooser.directory().toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        submit(chooser.getSelectedFile().toPath());
    }

    /** Reicht eine lokale JCL-Datei ein (für Tests direkt). */
    void submit(Path jclFile) {
        owner.status("Reiche " + jclFile.getFileName() + " ein …", false);
        owner.run(owner.session().jobsOnce(jes -> jes.submit(Files.readString(jclFile, StandardCharsets.UTF_8))),
                "Einreichen von " + jclFile.getFileName() + " fehlgeschlagen", job -> {
                    note = submitted(job);
                    refresh();
                });
    }

    private void saveSpool() {
        SpoolFile file = spoolList.getSelectedValue();
        if (file == null || outputJob == null || !file.equals(loadedSpool)) {
            return;
        }
        JFileChooser chooser = SaveChooser.create(outputJob + "-" + file.ddName() + ".txt");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            Files.writeString(chooser.getSelectedFile().toPath(), spoolText.getText(), StandardCharsets.UTF_8);
            owner.status("Gespeichert: " + chooser.getSelectedFile(), false);
        } catch (java.io.IOException e) {
            owner.status("Speichern fehlgeschlagen – " + FtpWorkspace.describe(e), true);
        }
    }

    private void deleteJob() {
        JesJob job = selectedJob();
        if (job == null || !Dialogs.confirm(this, "Job löschen", "Ausgabe von " + job.jobId() + " (" + job.jobName()
                + ") wirklich löschen?")) {
            return;
        }
        deleteJob(job);
    }

    void deleteJob(JesJob job) {
        owner.run(owner.session().jobsOnce(jes -> {
            jes.delete(job.jobId());
            return job.jobId();
        }), "Löschen von " + job.jobId() + " fehlgeschlagen", done -> {
            note = "Gelöscht: " + done;
            refresh();
        });
    }

    /** „Job JOB04711 (ORBIT) eingereicht“. */
    static String submitted(com.aresstack.spaceharvester.deck.ftp.jes.SubmittedJob job) {
        return "Job " + job.jobId().orElse("(ohne ID)") + job.jobName().map(name -> " (" + name + ")").orElse("")
                + " eingereicht";
    }

    /** Für Tests. */
    List<JesJob> shownJobs() {
        return List.copyOf(model.jobs);
    }

    JTable table() {
        return table;
    }

    List<SpoolFile> shownSpool() {
        List<SpoolFile> files = new ArrayList<>();
        for (int i = 0; i < spoolModel.size(); i++) {
            files.add(spoolModel.get(i));
        }
        return files;
    }

    JList<SpoolFile> spoolList() {
        return spoolList;
    }

    String spoolTextValue() {
        return spoolText.getText();
    }

    JComboBox<JobStatus> statusBox() {
        return status;
    }

    /** Rückgabe in Ampelfarben: grün gut, rot Fehler, grau solange er läuft. */
    private final class ReturnCodeRenderer extends DefaultTableCellRenderer {
        ReturnCodeRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row,
                                                       int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            JesJob job = model.at(t.convertRowIndexToModel(row));
            if (!selected) {
                setForeground(job.returnCode().isEmpty() ? Comic.MUTED : job.failed() ? Comic.RED : Comic.GREEN);
            }
            return this;
        }
    }

    private static final class Jobs extends AbstractTableModel {

        private static final String[] COLUMNS = {"Job-ID", "Name", "Besitzer", "Zustand", "Klasse", "Rückgabe",
                "Spool"};
        private final List<JesJob> jobs = new ArrayList<>();

        void show(List<JesJob> list) {
            jobs.clear();
            jobs.addAll(list);
            fireTableDataChanged();
        }

        JesJob at(int row) {
            return jobs.get(row);
        }

        int indexOf(String jobId) {
            for (int i = 0; i < jobs.size(); i++) {
                if (jobs.get(i).jobId().equals(jobId)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public int getRowCount() {
            return jobs.size();
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
            JesJob job = jobs.get(row);
            return switch (column) {
                case 0 -> job.jobId();
                case 1 -> job.jobName();
                case 2 -> job.owner();
                case 3 -> state(job.status());
                case 4 -> job.jobClass();
                case 5 -> job.returnCode().orElse(job.finished() ? "" : "läuft noch");
                default -> job.spoolFiles() > 0 ? String.valueOf(job.spoolFiles()) : "";
            };
        }

        private static String state(String status) {
            return switch (status) {
                case "OUTPUT" -> "fertig";
                case "ACTIVE" -> "läuft";
                case "INPUT" -> "wartet";
                case "HELD" -> "angehalten";
                default -> status.toLowerCase(java.util.Locale.ROOT);
            };
        }
    }
}
