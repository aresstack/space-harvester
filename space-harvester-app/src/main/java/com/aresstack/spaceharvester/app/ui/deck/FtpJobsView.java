package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.CopyButton;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.app.ui.deck.editor.MainframeSyntax;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.NameFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.JesJob;
import com.aresstack.spaceharvester.deck.ftp.jes.JobFilter;
import com.aresstack.spaceharvester.deck.ftp.jes.JobOutput;
import com.aresstack.spaceharvester.deck.ftp.jes.JobStatus;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolFile;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolNaming;
import com.aresstack.spaceharvester.deck.ftp.jes.SpoolText;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import javax.swing.BorderFactory;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

/**
 * Unteransicht „JES-Jobs“ des FTP-Bereichs, Jobverwaltung und Spool-Anzeige wie in MainframeMate
 * ({@code JesJobsConnectionTab}, {@code JobDetailTab}): oben die Filter Besitzer, Jobname und Zustand (gefiltert auf
 * dem Host), je mit lokalem Filter (Werte ankreuzen oder Regex, „Aktiviert“), rechts die lokale Suche über Job-ID und
 * Jobname. Links die Jobliste mit Zustand und Rückgabe in Ampelfarben und Kontextmenü, rechts der gewählte Job: Angaben,
 * „Gesamter Output“, Kopieren, Sprache der Hervorhebung, Suche im Spool mit Treffern, darunter die Spool-Dateien als
 * Tabelle und ihr Text (JESJCL zu einreichbarer JCL bereinigt). Einreichen, Aktualisieren, Spool speichern und Löschen
 * stehen unten. Aktualisiert wird nur auf Wunsch und nach eigenem Einreichen oder Löschen, nie von selbst.
 */
final class FtpJobsView extends JPanel {

    private final FtpSessionView owner;
    private final PlaceholderField ownerField = new PlaceholderField(8, "eigene");
    private final PlaceholderField jobName = new PlaceholderField(10, "*");
    private final JComboBox<JobStatus> status = Comic.combo();
    private final PlaceholderField search = new PlaceholderField(12, "Job-ID oder Name");
    private final NameFilter ownerFilter = new NameFilter();
    private final NameFilter nameFilter = new NameFilter();
    private final IconButton ownerFilterButton = new IconButton(Glyph.FILTER, "Lokaler Besitzer-Filter (Regex + Auswahl)");
    private final IconButton nameFilterButton = new IconButton(Glyph.FILTER, "Lokaler Jobname-Filter (Regex + Auswahl)");
    private final Jobs model = new Jobs();
    private final JTable table = Comic.table(new JTable(model));
    private final Spools spoolModel = new Spools();
    private final JTable spoolTable = Comic.table(new JTable(spoolModel));
    private final RSyntaxTextArea spoolText = new RSyntaxTextArea();
    private final JComboBox<SpoolText.Language> language = Comic.combo();
    private final PlaceholderField find = new PlaceholderField(12, "Suche im Spool (z.B. IEF, ABEND)");
    private final JLabel findCount = Comic.muted("");
    private final JLabel info = Comic.text(" ");
    private final ComposerButton whole;
    private int findFrom;
    /** Die Spool-Datei, deren Text gerade im Textfeld steht; {@code null} während des Ladens. */
    private SpoolFile loadedSpool;
    /** Ob gerade die Gesamtausgabe im Textfeld steht. */
    private boolean wholeShown;
    private final JLabel spoolTitle = Comic.caption("Spool");
    private final JPanel actions;
    private final ComposerButton submit;
    private final ComposerButton refresh;
    private final ComposerButton save;
    private final ComposerButton delete;
    private final List<JesJob> allJobs = new ArrayList<>();
    private JobOutput output;
    private String outputJob;
    private boolean started;
    private boolean connected;
    private boolean refreshWanted;
    /** Was der nächsten Liste im Stand vorangestellt wird (etwa „Job … eingereicht“), damit es nicht verschwindet. */
    private String note = "";
    /** Rückfrage „… wirklich löschen?“; Tests ersetzen sie. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) -> Dialogs.confirm(this, title, question);

    FtpJobsView(FtpSessionView owner) {
        super(new BorderLayout());
        this.owner = owner;
        setOpaque(false);
        MainframeSyntax.register();

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
        status.setToolTipText("Alle, Fertig (OUTPUT), Laufend (ACTIVE), Wartend (INPUT)");
        status.addActionListener(e -> {
            if (started) {
                refresh();
            }
        });
        ownerField.setToolTipText("Besitzer – geht unverändert an den Host (KKR*, IBMUSER, * für alle); leer = angemeldeter "
                + "Benutzer");
        jobName.setToolTipText("Jobname-Muster – geht unverändert an den Host (BUILD*, *)");
        ownerField.addActionListener(e -> refresh());
        jobName.addActionListener(e -> refresh());
        InputFrame ownerFrame = new InputFrame(ownerField);
        ownerFilterButton.addActionListener(e -> filterMenu(ownerFilter, true, "den Besitzer")
                .show(ownerFilterButton, 0, ownerFilterButton.getHeight()));
        ownerFrame.addButton(ownerFilterButton);
        InputFrame nameFrame = new InputFrame(jobName);
        IconButton reload = new IconButton(Glyph.REFRESH, "Jobliste neu abfragen");
        reload.addActionListener(e -> refresh());
        nameFilterButton.addActionListener(e -> filterMenu(nameFilter, false, "den Jobnamen")
                .show(nameFilterButton, 0, nameFilterButton.getHeight()));
        nameFrame.addButton(nameFilterButton);
        nameFrame.addButton(reload);
        ownerFilter.observe(this::applyLocalFilter);
        nameFilter.observe(this::applyLocalFilter);
        InputFrame searchFrame = InputFrame.search(search);
        searchFrame.addClearButton();
        search.setToolTipText("<html>Regex-Filter für Job-ID / Jobname<br>Beispiel: <b>JOB123</b> oder <b>MY.*</b><br>"
                + "<i>(Groß-/Kleinschreibung wird ignoriert)</i></html>");
        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                applyLocalFilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                applyLocalFilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                applyLocalFilter();
            }
        });
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        head.add(Comic.row(Comic.text("Besitzer"), ownerFrame, Comic.text("Jobname"), nameFrame,
                Comic.text("Zustand"), status), BorderLayout.WEST);
        head.add(searchFrame, BorderLayout.EAST);

        int[] widths = {100, 100, 90, 90, 60, 100, 50};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(3).setCellRenderer(new StateRenderer());
        table.getColumnModel().getColumn(5).setCellRenderer(new ReturnCodeRenderer());
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                jobSelected();
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    showSpool();
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                popup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                popup(event);
            }
        });
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "show-spool");
        table.getActionMap().put("show-spool", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                showSpool();
            }
        });
        table.getAccessibleContext().setAccessibleName("Jobs");

        int[] spoolWidths = {28, 80, 70, 56, 46, 50, 56};
        for (int i = 0; i < spoolWidths.length; i++) {
            spoolTable.getColumnModel().getColumn(i).setPreferredWidth(spoolWidths[i]);
        }
        spoolTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        spoolTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                spoolSelected();
            }
        });
        spoolTable.getAccessibleContext().setAccessibleName("Spool-Dateien");

        spoolText.setEditable(false);
        spoolText.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        spoolText.setTabSize(8);
        spoolText.setAntiAliasingEnabled(true);
        spoolText.setHighlightCurrentLine(false);
        spoolText.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        spoolText.getAccessibleContext().setAccessibleName("Spool-Text");
        RTextScrollPane textScroll = new RTextScrollPane(spoolText);
        textScroll.setBorder(BorderFactory.createLineBorder(Comic.LINE));

        for (SpoolText.Language candidate : SpoolText.Language.values()) {
            language.addItem(candidate);
        }
        language.setRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean selected,
                                                          boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof SpoolText.Language lang) {
                    setText(lang.label());
                }
                return this;
            }
        });
        language.setToolTipText("Sprache der Hervorhebung (Auto erkennt JCL, COBOL, Natural)");
        language.addActionListener(e -> highlight());
        whole = Comic.button("Gesamter Output", "Alle Spool-Dateien zusammen laden");
        whole.addActionListener(e -> loadWhole());
        CopyButton copy = new CopyButton(() -> SpoolText.sanitize(spoolText.getText()));
        copy.setToolTipText("Angezeigten Inhalt in die Zwischenablage kopieren");
        InputFrame findFrame = InputFrame.search(find);
        var previous = InputFrame.flatButton("▲", "Vorheriger Treffer (Umschalt+Eingabe)");
        previous.addActionListener(e -> findPrevious());
        var next = InputFrame.flatButton("▼", "Nächster Treffer (Eingabe)");
        next.addActionListener(e -> findNext());
        findFrame.addButton(previous);
        findFrame.addButton(next);
        find.addActionListener(e -> findNext());
        find.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("shift ENTER"), "find-previous");
        find.getActionMap().put("find-previous", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                findPrevious();
            }
        });
        find.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                findFrom = 0;
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                findFrom = 0;
                findCount.setText("");
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                findFrom = 0;
            }
        });
        info.setToolTipText("Angaben zum gewählten Job");

        JPanel detailHead = new JPanel(new BorderLayout(0, 4));
        detailHead.setOpaque(false);
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(spoolTitle, BorderLayout.WEST);
        titleRow.add(info, BorderLayout.CENTER);
        JPanel toolRow = new JPanel(new BorderLayout(8, 0));
        toolRow.setOpaque(false);
        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        left.setOpaque(false);
        left.add(whole);
        left.add(copy);
        left.add(language);
        JPanel right = new JPanel(new BorderLayout(4, 0));
        right.setOpaque(false);
        right.add(findFrame, BorderLayout.CENTER);
        right.add(findCount, BorderLayout.EAST);
        toolRow.add(left, BorderLayout.WEST);
        toolRow.add(right, BorderLayout.CENTER);
        detailHead.add(titleRow, BorderLayout.NORTH);
        detailHead.add(toolRow, BorderLayout.SOUTH);

        var spoolScroll = Comic.scroll(spoolTable);
        spoolScroll.setPreferredSize(new Dimension(380, 130));
        spoolScroll.setMinimumSize(new Dimension(220, 80));
        JSplitPane spoolSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, spoolScroll, textScroll);
        ComicDivider.install(spoolSplit, Comic.PALETTE);
        spoolSplit.setResizeWeight(0.0);
        spoolSplit.setBorder(BorderFactory.createEmptyBorder());
        spoolSplit.setOpaque(false);
        JPanel spoolPanel = new JPanel(new BorderLayout(0, 6));
        spoolPanel.setOpaque(false);
        spoolPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        spoolPanel.add(detailHead, BorderLayout.NORTH);
        spoolPanel.add(spoolSplit, BorderLayout.CENTER);
        var jobScroll = Comic.scroll(table);
        jobScroll.setPreferredSize(new Dimension(400, 170));
        jobScroll.setMinimumSize(new Dimension(200, 90));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, jobScroll, spoolPanel);
        ComicDivider.install(split, Comic.PALETTE);
        split.setResizeWeight(0.3);
        split.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        split.setOpaque(false);
        add(head, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);

        submit = Comic.button("Job einreichen …", "Lokale JCL-Datei an JES übergeben");
        submit.addActionListener(e -> submitFile());
        refresh = Comic.button("Aktualisieren", "Jobliste neu abfragen");
        refresh.addActionListener(e -> refresh());
        save = Comic.button("Spool speichern …", "Angezeigten Spool-Text speichern");
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
            ownerField.setPlaceholder(session.user().toUpperCase(Locale.ROOT));
        }
        JobFilter filter = filter();
        String keep = selectedJob() == null ? null : selectedJob().jobId();
        String prefix = note.isEmpty() ? "" : note + " · ";
        note = "";
        owner.status(prefix + "Suche Jobs …", false);
        refresh.setEnabled(false);
        owner.later(session.jobs(jes -> jes.jobs(filter)), jobs -> {
            allJobs.clear();
            allJobs.addAll(jobs);
            applyLocalFilter();
            owner.status(prefix + jobs.size() + (jobs.size() == 1 ? " Job gefunden" : " Jobs gefunden") + " · Besitzer "
                    + filter.ownerOr(session.user()) + ", Name " + filter.jobName() + ", " + filter.status().label(),
                    false);
            int row = keep == null ? -1 : model.indexOf(keep);
            if (row >= 0) {
                table.getSelectionModel().setSelectionInterval(row, row);
            } else {
                clearSpool();
            }
            updateActions();
        }, error -> {
            owner.status(prefix + "Suche fehlgeschlagen – " + FtpWorkspace.describe(error), true);
            updateActions();
        });
    }

    /** Lokale Filter (Besitzer, Jobname) und Suche über Job-ID/Jobname; rot, wenn ungültig oder nichts passt. */
    private void applyLocalFilter() {
        ownerFilterButton.marked(ownerFilter.active());
        nameFilterButton.marked(nameFilter.active());
        List<JesJob> base = new ArrayList<>();
        for (JesJob job : allJobs) {
            if (ownerFilter.matches(job.owner()) && nameFilter.matches(job.jobName())) {
                base.add(job);
            }
        }
        String regex = search.getText().trim();
        List<JesJob> shown = base;
        boolean alarm = false;
        if (!regex.isEmpty()) {
            try {
                Pattern pattern = Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
                shown = base.stream().filter(job -> pattern.matcher(job.jobId()).find()
                        || pattern.matcher(job.jobName()).find()).toList();
                alarm = shown.isEmpty();
            } catch (PatternSyntaxException invalid) {
                alarm = true;
            }
        }
        String keep = selectedJob() == null ? null : selectedJob().jobId();
        model.show(shown);
        int row = keep == null ? -1 : model.indexOf(keep);
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
        }
        search.setBackground(alarm ? Comic.CHANGE_REMOVED : javax.swing.UIManager.getColor("TextField.background"));
        if (started && !allJobs.isEmpty() && shown.size() != allJobs.size()) {
            owner.status(shown.size() + " / " + allJobs.size() + " Jobs", false);
        }
    }

    /**
     * Menü des lokalen Filters wie MainframeMate: „Aktiviert“, der aktuelle Regex, „Muster bearbeiten …“, „Filter
     * zurücksetzen“ und die Werte der letzten Liste zum Ankreuzen.
     */
    JPopupMenu filterMenu(NameFilter filter, boolean owners, String target) {
        JPopupMenu menu = new JPopupMenu();
        JCheckBoxMenuItem enabled = new JCheckBoxMenuItem("Aktiviert", filter.enabled());
        enabled.addActionListener(e -> filter.enabled(enabled.isSelected()));
        menu.add(enabled);
        JMenuItem current = new JMenuItem(filter.regex().isEmpty() ? "(kein Regex aktiv)" : "Aktuell: " + filter.regex());
        current.setEnabled(false);
        menu.add(current);
        menu.addSeparator();
        JMenuItem edit = new JMenuItem("Muster bearbeiten …");
        edit.addActionListener(e -> {
            PlaceholderField field = new PlaceholderField(24, "Regex, Groß-/Kleinschreibung egal");
            field.setText(filter.regex());
            JPanel body = new JPanel(new BorderLayout(0, 6));
            body.setOpaque(false);
            body.add(Comic.text("Regex (wird auf " + target + " angewendet):"), BorderLayout.NORTH);
            body.add(new InputFrame(field), BorderLayout.CENTER);
            if (Dialogs.formOverlay(this, "Filter-Regex", body, "Übernehmen")) {
                filter.manual(field.getText());
            }
        });
        menu.add(edit);
        JMenuItem clear = new JMenuItem("Filter zurücksetzen");
        clear.setEnabled(!filter.regex().isEmpty() || !filter.selected().isEmpty());
        clear.addActionListener(e -> filter.clear());
        menu.add(clear);
        menu.addSeparator();
        Collection<String> values = values(owners);
        if (values.isEmpty()) {
            JMenuItem empty = new JMenuItem("(keine Einträge verfügbar)");
            empty.setEnabled(false);
            menu.add(empty);
            return menu;
        }
        JMenuItem header = new JMenuItem("Werte ankreuzen:");
        header.setEnabled(false);
        menu.add(header);
        for (String value : values) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(value, filter.selected().contains(value));
            item.addActionListener(e -> filter.toggle(value, item.isSelected()));
            menu.add(item);
        }
        return menu;
    }

    /** Die verschiedenen Besitzer bzw. Jobnamen der letzten Abfrage. */
    private Collection<String> values(boolean owners) {
        TreeSet<String> values = new TreeSet<>();
        for (JesJob job : allJobs) {
            String value = owners ? job.owner() : job.jobName();
            if (!value.isBlank()) {
                values.add(value.trim().toUpperCase(Locale.ROOT));
            }
        }
        return values;
    }

    NameFilter ownerFilter() {
        return ownerFilter;
    }

    NameFilter nameFilter() {
        return nameFilter;
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0) {
            table.getSelectionModel().setSelectionInterval(row, row);
        }
        contextMenu().show(table, event.getX(), event.getY());
    }

    /** Kontextmenü der Jobliste wie MainframeMate. */
    JPopupMenu contextMenu() {
        JPopupMenu menu = new JPopupMenu();
        boolean chosen = selectedJob() != null;
        JMenuItem show = new JMenuItem("Spool anzeigen");
        show.setEnabled(chosen);
        show.addActionListener(e -> showSpool());
        menu.add(show);
        JMenuItem remove = new JMenuItem("Job löschen …");
        remove.setEnabled(chosen && connected);
        remove.addActionListener(e -> deleteJob());
        menu.add(remove);
        menu.addSeparator();
        JMenuItem again = new JMenuItem("Aktualisieren");
        again.setEnabled(connected);
        again.addActionListener(e -> refresh());
        menu.add(again);
        return menu;
    }

    /** „Spool anzeigen“: die erste Spool-Datei wählen und in den Text springen. */
    private void showSpool() {
        if (spoolTable.getRowCount() > 0 && spoolTable.getSelectedRow() < 0) {
            spoolTable.getSelectionModel().setSelectionInterval(0, 0);
        }
        spoolText.requestFocusInWindow();
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
        info.setText(info(job));
        if (job.jobId().equals(outputJob) && output != null) {
            return;
        }
        clearSpool();
        info.setText(info(job));
        outputJob = job.jobId();
        SpoolNaming naming = owner.session().profile().spoolNaming();
        owner.status(naming.mode() == SpoolNaming.Mode.OFF ? "Lade gesamten Output …" : "Lade Spool-Liste …", false);
        owner.run(owner.session().jobs(jes -> JobOutput.load(jes, job.jobId(), naming)),
                "Spool von " + job.jobId() + " nicht geladen", loaded -> {
                    if (!job.jobId().equals(outputJob)) {
                        return;
                    }
                    output = loaded;
                    spoolModel.show(loaded.files());
                    if (spoolModel.getRowCount() > 0) {
                        spoolTable.getSelectionModel().setSelectionInterval(0, 0);
                        owner.status(loaded.files().size() + (loaded.whole().isPresent() ? " Abschnitte aus Output erkannt"
                                : " Spool-Dateien") + (naming.probeAfterwards() ? " – DD-Namen werden nachgeladen …"
                                : ""), false);
                        if (naming.probeAfterwards()) {
                            probeNames(job.jobId(), loaded.files().size());
                        }
                    } else {
                        setText(job.finished() ? "Keine Spool-Dateien." : "Der Job ist noch nicht fertig ("
                                + job.status() + ").", null);
                        owner.status(job.jobId() + " · keine Spool-Dateien", false);
                    }
                    updateActions();
                });
    }

    /** „Job-ID: … · Name: … · Besitzer: … · Zustand: … · RC: … · Klasse: …“ wie die Job-Info in MainframeMate. */
    static String info(JesJob job) {
        StringBuilder text = new StringBuilder("Job-ID: ").append(job.jobId()).append(" · Name: ").append(job.jobName())
                .append(" · Besitzer: ").append(job.owner()).append(" · Zustand: ").append(job.status());
        job.returnCode().ifPresent(code -> text.append(" · RC: ").append(code));
        if (!job.jobClass().isEmpty()) {
            text.append(" · Klasse: ").append(job.jobClass());
        }
        return text.toString();
    }

    /** DD-Namen einzeln nachladen und die Tabelle berichtigen (Verfahren OFF bzw. FAST mit Nachladen). */
    private void probeNames(String jobId, int count) {
        owner.later(owner.session().probeSpoolNames(jobId, count), names -> {
            if (!jobId.equals(outputJob) || output == null) {
                return;
            }
            int selected = spoolTable.getSelectedRow();
            output = output.withNames(names);
            spoolModel.show(output.files());
            if (selected >= 0 && selected < spoolModel.getRowCount()) {
                spoolTable.getSelectionModel().setSelectionInterval(selected, selected);
            }
            long updated = names.values().stream().filter(name -> !name.startsWith("SPOOL#")).count();
            owner.status(updated + "/" + count + " DD-Namen per Probe aktualisiert", false);
        }, error -> owner.status("DD-Namen nicht nachgeladen – " + FtpWorkspace.describe(error), true));
    }

    private void spoolSelected() {
        int row = spoolTable.getSelectedRow();
        SpoolFile file = row < 0 ? null : spoolModel.at(row);
        JobOutput current = output;
        String jobId = outputJob;
        // Den alten Text sofort verwerfen: sonst speichert „Spool speichern“ den Text der vorigen Datei unter neuem Namen.
        loadedSpool = null;
        wholeShown = false;
        if (file == null || current == null) {
            updateActions();
            return;
        }
        setText("Lade " + file.ddName() + " (Spool #" + file.id() + ") …", null);
        updateActions();
        owner.run(owner.session().jobs(jes -> current.text(jes, jobId, file)),
                "Spool-Datei " + file.ddName() + " nicht geladen", text -> {
                    int now = spoolTable.getSelectedRow();
                    if (now >= 0 && spoolModel.at(now).id() == file.id() && jobId.equals(outputJob)) {
                        loadedSpool = file;
                        setText(text, file.ddName());
                        owner.status(file.ddName() + " geladen (" + lines(spoolText.getText()) + " Zeilen)", false);
                        updateActions();
                    }
                });
    }

    /** „Gesamter Output“: alle Spool-Dateien am Stück (aus der Gliederung, sonst vom Host). */
    private void loadWhole() {
        JesJob job = selectedJob();
        if (job == null) {
            return;
        }
        String jobId = job.jobId();
        if (output != null && output.whole().isPresent() && jobId.equals(outputJob)) {
            showWhole(output.whole().get());
            return;
        }
        owner.status("Lade gesamten Output …", false);
        setText("Lade alle Spool-Dateien für " + jobId + " …", null);
        owner.run(owner.session().jobs(jes -> jes.allSpool(jobId)), "Gesamter Output von " + jobId + " nicht geladen",
                text -> {
                    if (jobId.equals(outputJob)) {
                        showWhole(text);
                    }
                });
    }

    private void showWhole(String text) {
        spoolTable.clearSelection();
        setText(text, null);
        loadedSpool = null;
        wholeShown = true;
        owner.status("Gesamter Output angezeigt (" + lines(spoolText.getText()) + " Zeilen)", false);
        updateActions();
    }

    /** Setzt Text wie MainframeMate: Steuerzeichen weg, JESJCL bereinigt, Hervorhebung nach Sprache. */
    private void setText(String raw, String ddName) {
        spoolText.setText(SpoolText.clean(raw));
        spoolText.setCaretPosition(0);
        spoolText.putClientProperty("ddName", ddName);
        findFrom = 0;
        findCount.setText("");
        highlight();
    }

    private void highlight() {
        SpoolText.Language chosen = (SpoolText.Language) language.getSelectedItem();
        SpoolText.Language effective = SpoolText.detect(spoolText.getText(),
                (String) spoolText.getClientProperty("ddName"), chosen);
        String style = switch (effective) {
            case JCL -> SyntaxConstants.SYNTAX_STYLE_PROPERTIES_FILE;
            case NATURAL -> MainframeSyntax.NATURAL;
            default -> SyntaxConstants.SYNTAX_STYLE_NONE;
        };
        spoolText.setSyntaxEditingStyle(style);
        spoolText.setCodeFoldingEnabled(!SyntaxConstants.SYNTAX_STYLE_NONE.equals(style));
        spoolText.putClientProperty("language", effective);
    }

    /** Die erkannte bzw. gewählte Sprache des angezeigten Texts (für Tests). */
    SpoolText.Language languageShown() {
        return (SpoolText.Language) spoolText.getClientProperty("language");
    }

    JComboBox<SpoolText.Language> languageBox() {
        return language;
    }

    /** Nächster Treffer, am Ende wieder von vorn; zählt alle Treffer. */
    void findNext() {
        String query = find.getText();
        String text = spoolText.getText();
        if (query.isEmpty() || text.isEmpty()) {
            return;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int count = SpoolText.count(text, query);
        int at = haystack.indexOf(needle, findFrom);
        if (at < 0 && findFrom > 0) {
            at = haystack.indexOf(needle);
        }
        found(at, needle.length(), count);
        findFrom = at < 0 ? 0 : at + needle.length();
    }

    /** Vorheriger Treffer, am Anfang wieder vom Ende. */
    void findPrevious() {
        String query = find.getText();
        String text = spoolText.getText();
        if (query.isEmpty() || text.isEmpty()) {
            return;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        String needle = query.toLowerCase(Locale.ROOT);
        int count = SpoolText.count(text, query);
        int before = findFrom - needle.length() - 1;
        int at = before < 0 ? -1 : haystack.lastIndexOf(needle, before);
        if (at < 0) {
            at = haystack.lastIndexOf(needle);
        }
        found(at, needle.length(), count);
        findFrom = at < 0 ? 0 : at + needle.length();
    }

    private void found(int at, int length, int count) {
        if (at >= 0) {
            spoolText.setCaretPosition(at);
            spoolText.select(at, at + length);
            findCount.setText(count + " Treffer");
            findCount.setForeground(Comic.MUTED);
        } else {
            findCount.setText("Nicht gefunden");
            findCount.setForeground(Comic.RED);
        }
    }

    void find(String query) {
        find.setText(query);
    }

    String findCountText() {
        return findCount.getText();
    }

    int selectionStart() {
        return spoolText.getSelectionStart();
    }

    private void clearSpool() {
        output = null;
        outputJob = null;
        loadedSpool = null;
        wholeShown = false;
        spoolModel.show(List.of());
        spoolText.setText("");
        spoolTitle.setText("Spool");
        info.setText(" ");
        findCount.setText("");
    }

    private void updateActions() {
        JesJob job = selectedJob();
        submit.setEnabled(connected);
        refresh.setEnabled(connected);
        delete.setEnabled(connected && job != null);
        whole.setEnabled(connected && job != null);
        int row = spoolTable.getSelectedRow();
        SpoolFile selectedSpool = row < 0 ? null : spoolModel.at(row);
        save.setEnabled(connected && (wholeShown || (selectedSpool != null && selectedSpool.equals(loadedSpool))));
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
        int row = spoolTable.getSelectedRow();
        SpoolFile file = row < 0 ? null : spoolModel.at(row);
        if (outputJob == null || (!wholeShown && (file == null || !file.equals(loadedSpool)))) {
            return;
        }
        JFileChooser chooser = SaveChooser.create(outputJob + "-" + (wholeShown ? "gesamt" : file.ddName()) + ".txt");
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
        if (job == null || !confirm.test("Job löschen?", "Job " + job.jobId() + " (" + job.jobName()
                + ") wirklich löschen? Der Spool-Output wird unwiderruflich entfernt.")) {
            return;
        }
        deleteJob(job);
    }

    void deleteJob(JesJob job) {
        owner.status("Lösche " + job.jobId() + " …", false);
        owner.run(owner.session().jobsOnce(jes -> {
            jes.delete(job.jobId());
            return job.jobId();
        }), "Löschen von " + job.jobId() + " fehlgeschlagen – möglicherweise fehlt die Berechtigung", done -> {
            note = done + " gelöscht";
            refresh();
        });
    }

    /** „Job JOB04711 (ORBIT) eingereicht“. */
    static String submitted(com.aresstack.spaceharvester.deck.ftp.jes.SubmittedJob job) {
        return "Job " + job.jobId().orElse("(ohne ID)") + job.jobName().map(name -> " (" + name + ")").orElse("")
                + " eingereicht";
    }

    private static int lines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    /** Für Tests. */
    List<JesJob> shownJobs() {
        return List.copyOf(model.jobs);
    }

    JTable table() {
        return table;
    }

    List<SpoolFile> shownSpool() {
        return List.copyOf(spoolModel.files);
    }

    JTable spoolTable() {
        return spoolTable;
    }

    String spoolTextValue() {
        return spoolText.getText();
    }

    String infoText() {
        return info.getText();
    }

    JComboBox<JobStatus> statusBox() {
        return status;
    }

    void searchFor(String regex) {
        search.setText(regex);
    }

    boolean searchAlarming() {
        return Comic.CHANGE_REMOVED.equals(search.getBackground());
    }

    void showWholeOutput() {
        loadWhole();
    }

    /** Zustand in Farben wie MainframeMate: fertig grün, läuft blau, wartet gelb-braun. */
    private final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus, int row,
                                                       int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            if (!selected) {
                JesJob job = model.at(t.convertRowIndexToModel(row));
                Color color = switch (job.status().toUpperCase(Locale.ROOT)) {
                    case "OUTPUT" -> Comic.GREEN;
                    case "ACTIVE" -> Comic.STANDING_PLANNED;
                    case "INPUT" -> Comic.STANDING_UNCLEAR;
                    default -> Comic.INK;
                };
                setForeground(color);
            }
            return this;
        }
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
                default -> status.toLowerCase(Locale.ROOT);
            };
        }
    }

    /** Spool-Dateien wie die Tabelle in MainframeMate: #, DD-Name, Schritt, Proc, Klasse, Zeilen, Bytes. */
    private static final class Spools extends AbstractTableModel {

        private static final String[] COLUMNS = {"#", "DD-Name", "Schritt", "Proc", "Klasse", "Zeilen", "Bytes"};
        private final List<SpoolFile> files = new ArrayList<>();

        void show(List<SpoolFile> list) {
            files.clear();
            files.addAll(list);
            fireTableDataChanged();
        }

        SpoolFile at(int row) {
            return files.get(row);
        }

        @Override
        public int getRowCount() {
            return files.size();
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
            SpoolFile file = files.get(row);
            return switch (column) {
                case 0 -> file.id();
                case 1 -> file.ddName();
                case 2 -> file.stepName();
                case 3 -> file.procStep();
                case 4 -> file.outputClass();
                case 5 -> file.records() > 0 ? String.valueOf(file.records()) : "";
                default -> file.bytes() > 0 ? String.valueOf(file.bytes()) : "";
            };
        }
    }
}
