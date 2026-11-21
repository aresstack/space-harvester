package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.event.JobProgressEvent;
import com.aresstack.spaceharvester.app.runtime.AllJobs;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.export.ExportArtifact;
import com.aresstack.spaceharvester.application.export.ExportDatasetInfo;
import com.aresstack.spaceharvester.application.export.ExportAssetCollection;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.jobs.JobAction;
import com.aresstack.spaceharvester.application.jobs.JobLifecycleState;
import com.aresstack.spaceharvester.application.jobs.JobLimits;
import com.aresstack.spaceharvester.application.jobs.JobProgress;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.signin.SignInRequest;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.sync.SpaceSyncJobs;
import com.aresstack.spaceharvester.application.sync.Inventory;
import com.aresstack.spaceharvester.application.sync.SyncSummary;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Aufträge: der Download-Manager dieser App (Angelo, 13.11.2026). Abgleiche, Downloads und Folgeaufträge mit
 * Fortschritt, Teilfehlern und Abbruch. Oben die Auftragsliste über die volle Breite, je Zeile rechtsbündig die
 * Symbolaktionen ({@link RowActions}): ein Fortschrittsbalken, Diagnose, Inhalte (öffnet Inhalte › Export für den
 * Space bzw. die Startseite des Auftrags), danach genau die Aktionen, die der Zustand des Auftrags im Core anbietet
 * ({@link JobLifecycleState#actions()}: Jetzt starten, Anhalten, Fortsetzen, Abbrechen, Löschen; Anhalten und
 * Fortsetzen nie zugleich). Darunter die Kachel „Auftrag“ mit allen Zahlen des Laufs. Format-Knöpfe, erzeugte Dateien
 * und Ausgabeordner stehen unter Inhalte › Export ({@link com.aresstack.spaceharvester.app.ui.content.ExportPanel}),
 * nicht mehr hier. Mittig in der Kopfzeile stehen „Alle
 * anhalten“ (wird „Alle fortsetzen“, sobald alles angehalten ist) und das rote „Alle abbrechen“ für alle Aufträge
 * zugleich (Angelo 11.11.2026). Der Gesamtstand aller Aufträge steht im Balken der Statuszeile ({@link OverallProgress});
 * hinter jedem laufenden Export liegt derselbe hellblaue Balken, aufgeklappt auch hinter seinen Bildern und Anhängen.
 * Ein Lauf heißt nur dann erfolgreich, wenn er vollständig ist; ein Abgleich, der keine Generation bestätigen konnte,
 * erscheint als Warnung mit Grund.
 */
public final class ActivitiesView implements View {

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JobsModel model = new JobsModel();
    private final JTable table = Comic.table(new JobsTable(model));
    private final JLabel title = new JLabel("Kein Auftrag gewählt");
    private final Facts facts = new Facts();
    /** Rechts neben „Ausgabeordner“: öffnet den Ordner, in den der gewählte Auftrag schreibt. */
    final ComposerButton openOutput = Comic.button("Ordner öffnen", "Den Ausgabeordner im Dateimanager anzeigen");
    /** Der Ausgabeordner des gezeigten Auftrags; {@code null}: er schreibt keine Dateien. */
    private java.nio.file.Path outputShown;
    private final Feedback feedback = new Feedback();
    private final ComposerButton again = Comic.button("Erneut abgleichen", "Denselben Space erneut abgleichen");
    /** Die Einmalcode-Abfrage des gewählten Auftrags, ohne Auswahl die älteste offene (ADR 0018). */
    final SignInSlot signIn;
    private final JComponent details;
    /** Globaler Durchschnitt je geladener Datei für Bilder und Anhänge (Core), beim Abruf aufgefrischt. */
    private final Map<JobType, java.time.Duration> averages = new java.util.EnumMap<>(JobType.class);
    /** Zuletzt gelesene Zahl paralleler Downloads; bei 0 keine Restzeit. */
    private int parallelNow = JobLimits.DEFAULT_PARALLEL_DOWNLOADS;
    /** Ein Knopf für alle Aufträge (Angelo 10.11.2026): löscht alles, laufende werden vorher abgebrochen. */
    final IconButton deleteFinished = new IconButton(Glyph.BROOM, DELETE_FINISHED);
    /**
     * Rechts neben dem Besen das Dreieck der Leseansicht (Angelo 17.11.2026); im Menü „Alle Aufträge löschen“, das
     * laufende vorher abbricht, damit nichts unsichtbar weiterläuft.
     */
    final IconButton moreJobs = new IconButton(new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(),
            MORE_JOB_ACTIONS);
    static final String DELETE_FINISHED = "Abgeschlossene Aufträge löschen";
    static final String DELETE_ALL = "Alle Aufträge löschen";
    static final String MORE_JOB_ACTIONS = "Weitere Aktionen zu den Aufträgen";
    /** Links neben den Auswahlen (Angelo 11.11.2026): alle Bilder- und Anhänge-Unterzeilen auf- bzw. zuklappen. */
    final IconButton expandAll = new IconButton(Glyph.EXPAND_ALL, "Alle Unterschritte aufklappen (Bilder, Anhänge, Schritte von „In PPTX umwandeln“)");
    final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alle Unterschritte zuklappen");
    /**
     * Links vom Besen (Angelo 11.11.2026): wie viele Bilder- und Anhängeaufträge gleichzeitig laden; 0 bereitet nur vor.
     * Gilt sofort und bleibt in der Konfigurationsdatei ({@code jobs.parallel-downloads}).
     */
    final ParallelChoice downloads = new ParallelChoice("Parallele Downloads: wie viele Bilder- und Anhängeaufträge "
            + "gleichzeitig laden (0 bis " + JobLimits.MAX_PARALLEL_DOWNLOADS + "). Bei 0 wird nur vorbereitet, "
            + "nichts heruntergeladen.", 0, JobLimits.MAX_PARALLEL_DOWNLOADS, List.of(0, 1, 2, 4, 8, 16, 32, 64),
            JobLimits.DEFAULT_PARALLEL_DOWNLOADS, value -> parallel(), this::invalidParallel);
    /**
     * Rechts neben „Downloads“ (Angelo 11.11.2026): Verbindungen aller ladenden Bilder- und Anhängeaufträge zusammen
     * ({@code jobs.download-connections}); Texte laden weiter über eine Verbindung, 0 lädt keine Bilder und Anhänge.
     */
    final ParallelChoice connections = new ParallelChoice("Verbindungen: wie viele Verbindungen alle Bilder- und "
            + "Anhängedownloads zusammen nutzen (0 bis " + JobLimits.MAX_DOWNLOAD_CONNECTIONS + "). Texte laden "
            + "weiter über eine Verbindung. Bei 0 werden keine Bilder und Anhänge geladen; die Aufträge warten.",
            0, JobLimits.MAX_DOWNLOAD_CONNECTIONS, List.of(0, 1, 2, 4, 8, 16, 32, 64),
            JobLimits.DEFAULT_DOWNLOAD_CONNECTIONS, value -> parallel(), this::invalidParallel);
    /** Wie viele Bilder- und Anhängeaufträge gleichzeitig vorbereiten ({@code jobs.parallel-preparations}). */
    final ParallelChoice preparations = new ParallelChoice("Parallele Vorbereitungen: wie viele Bilder- und "
            + "Anhängeaufträge gleichzeitig ihre Verweise zählen (1 bis " + JobLimits.MAX_PARALLEL_PREPARATIONS + ").",
            1, JobLimits.MAX_PARALLEL_PREPARATIONS, List.of(1, 2, 4, 8, 16), JobLimits.DEFAULT_PARALLEL_PREPARATIONS,
            value -> parallel(), this::invalidParallel);
    /**
     * Anmeldung bei den Auftragsmeldungen des Core (ADR 0027, Angelo 13.11.2026: kein Zeitgeber): jede Änderung eines
     * Auftrags, jede Löschung und jedes Diagnoseereignis liest die Liste einmal neu, gebündelt auf dem EDT.
     */
    private Subscription subscription;
    private AppRuntime subscribed;
    private boolean refreshPending;
    /** Eine Abfrage läuft; Meldungen währenddessen führen danach zu genau einer weiteren. */
    private boolean reading;
    private boolean readAgain;
    private String selectedId;
    private Object detail;
    /**
     * Mittig in der Kopfzeile (Angelo 11.11.2026): hält alle wartenden und laufenden Aufträge an; ist alles
     * angehalten, wird daraus „Alle fortsetzen“ mit Play-Symbol.
     */
    final ComposerButton pauseAll = Comic.button(ComposerIcons.pause(), PAUSE_ALL, PAUSE_ALL_TIP);
    /**
     * Daneben, rot wie zuvor „Abbrechen“ in der Statuszeile, mit dem Stopp-Symbol der Zeilen: bricht alle wartenden und
     * laufenden Aufträge ab.
     */
    final ComposerButton cancelAll = Comic.danger(ComposerIcons.stop(), CANCEL_ALL, CANCEL_ALL_TIP);
    static final String PAUSE_ALL = "Alle anhalten";
    static final String RESUME_ALL = "Alle fortsetzen";
    static final String CANCEL_ALL = "Alle abbrechen";
    static final String PAUSE_ALL_TIP = "Alle wartenden und laufenden Aufträge anhalten · Geladenes bleibt, später "
            + "fortsetzen";
    static final String RESUME_ALL_TIP = "Alle angehaltenen Aufträge fortsetzen · holt nur, was noch fehlt; fertige "
            + "bleiben, wie sie sind";
    static final String CANCEL_ALL_TIP = "Alle wartenden und laufenden Aufträge abbrechen · Bereits Gelesenes bleibt "
            + "unbestätigt";
    /** Was „Alle anhalten“ gerade tut. */
    private GlobalPause globalPause = GlobalPause.NONE;
    /** Eine Aktion für alle läuft; beide Knöpfe warten, bis sie fertig ist. */
    private boolean busyAll;
    /** Breite der beiden Knöpfe mit Text; „Alle anhalten“ so breit wie „Alle fortsetzen“, damit nichts springt. */
    private final java.awt.Dimension pauseAllFull;
    private final java.awt.Dimension cancelAllFull;
    /** Zu schmal für die Texte: beide Knöpfe nur mit Symbol, der Name steht im Tooltip und für Bildschirmleser. */
    private boolean compactHead;
    static final java.awt.Dimension COMPACT_BUTTON = new java.awt.Dimension(30, 28);
    /** Gesamtstand aller Aufträge für die Statuszeile und die Balken hinter laufenden Exporten. */
    private final OverallProgress overall = new OverallProgress();
    /** Geschätzter Stand je Export-Gruppe, in der gerade etwas läuft (Export-ID). */
    private Map<String, OverallProgress.Estimate> groupEstimates = Map.of();
    /** Die Balken hinter laufenden Export-Gruppen, in Zeilen der Liste. */
    private List<Band> bands = List.of();
    /** Bewegt den Balken einer Gruppe ohne messbaren Fortschritt hin und her, wie die Statuszeile. */
    private final Timer sweep = new Timer(40, e -> sweepBands());
    private int sweepOffset;
    /** Jüngste Phase des gewählten Auftrags aus seiner Diagnose, solange er läuft; sonst {@code null}. */
    private String phase;
    /** Angaben aus Ereignissen mit Code am gewählten Auftrag ({@link #notes(List)}). */
    private List<Note> stageNotes = List.of();
    /** Bildlauf des Detailbereichs unter der Liste. */
    final javax.swing.JScrollPane detailScroll;
    /** Beim nächsten Zeichnen nach oben: anderer Auftrag gewählt oder Codefeld neu erschienen. */
    private boolean toTop;

    public ActivitiesView(AppContext context) {
        this.context = context;
        this.signIn = new SignInSlot(context, requests -> selectedId == null
                ? requests.pending().stream().findFirst() : requests.waiting(selectedId));
        root.setBackground(Comic.SURFACE);
        table.getColumnModel().getColumn(2).setCellRenderer(new StateRenderer());
        table.getColumnModel().getColumn(0).setCellRenderer(new TypeRenderer());
        // Ein Klick auf die Art eines Exports klappt seine Bilder und Anhänge auf oder zu (Angelo 11.11.2026).
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && table.columnAtPoint(event.getPoint()) == table.convertColumnIndexToView(0)
                        && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    toggleGroup(row);
                }
                // Ein Klick auf die schon markierte Zeile meldet keine neue Auswahl: Fehlt ihr Detail noch (Abruf
                // gescheitert), liest ihn der Klick nach, statt „Kein Auftrag gewählt“ stehen zu lassen.
                if (row >= 0 && row < model.rows.size() && detail == null
                        && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    select(model.rows.get(row).id());
                }
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                select(model.rows.get(table.getSelectedRow()).id());
            }
        });
        RowActions.install(table, JobsModel.ACTIONS, SLOTS, this::rowActions);
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setBackground(Comic.SURFACE);
        list.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 12));
        JLabel hint = new JLabel(ComposerIcons.info());
        hint.setToolTipText("<html>Abgleiche startest du unter Wissensquellen verwalten, Downloads im Explorer links "
                + "(Rechtsklick oder Download-Symbol).<br>Beendete Aufträge bleiben sichtbar, bis die Aufbewahrungsfrist "
                + "endet oder sie gelöscht werden.</html>");
        JPanel listTitle = Comic.row(Comic.caption("Aufträge dieser App"), hint);
        JLabel downloadsLabel = new JLabel("Downloads");
        downloadsLabel.setToolTipText(downloads.box().getToolTipText());
        JLabel preparationsLabel = new JLabel("Vorbereitung");
        preparationsLabel.setToolTipText(preparations.box().getToolTipText());
        JLabel connectionsLabel = new JLabel("Verbindungen");
        connectionsLabel.setToolTipText(connections.box().getToolTipText());
        connectionsLabel.setLabelFor(connections.box());
        for (JLabel label : List.of(downloadsLabel, preparationsLabel, connectionsLabel)) {
            label.setFont(Comic.regular(12f));
            label.setForeground(Comic.INK);
        }
        downloadsLabel.setLabelFor(downloads.box());
        preparationsLabel.setLabelFor(preparations.box());
        expandAll.addActionListener(e -> setAllGroups(false));
        collapseAll.addActionListener(e -> setAllGroups(true));
        JPanel listTools = Comic.row(expandAll, collapseAll, preparationsLabel, preparations.box(), downloadsLabel,
                downloads.box(), connectionsLabel, connections.box(), deleteFinished, moreJobs);
        // Mittig in der Kopfzeile, für alle Aufträge zugleich (Angelo 11.11.2026).
        pauseAll.addActionListener(e -> pauseOrResumeAll());
        cancelAll.addActionListener(e -> cancelAll());
        pauseAllFull = widest(pauseAll, PAUSE_ALL, RESUME_ALL);
        cancelAllFull = cancelAll.getPreferredSize();
        pauseAll.setPreferredSize(pauseAllFull);
        cancelAll.setPreferredSize(cancelAllFull);
        JPanel global = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 6, 0));
        global.setOpaque(false);
        global.add(pauseAll);
        global.add(cancelAll);
        JPanel listHead = new JPanel();
        listHead.setLayout(new HeadLayout(listTitle, global, listTools, global.getPreferredSize().width,
                this::compactHead));
        listHead.setOpaque(false);
        listHead.add(listTitle);
        listHead.add(global);
        listHead.add(listTools);
        updateGlobalButtons(List.of());
        model.addTableModelListener(e -> layoutBands());
        list.add(listHead, BorderLayout.NORTH);
        list.add(Comic.scroll(table), BorderLayout.CENTER);

        title.setFont(Comic.semiBold(17f));
        title.setForeground(Comic.INK);
        again.addActionListener(e -> restart());
        deleteFinished.addActionListener(e -> deleteFinished());
        openOutput.addActionListener(e -> SettingsView.open(outputShown, feedback));
        moreJobs.addActionListener(e -> showMoreJobsMenu());

        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        JPanel titleRow = new JPanel(new BorderLayout(8, 0));
        titleRow.setOpaque(false);
        titleRow.add(title, BorderLayout.CENTER);
        titleRow.add(Comic.row(again), BorderLayout.EAST);
        head.add(titleRow, BorderLayout.NORTH);
        head.add(signIn, BorderLayout.CENTER);
        head.add(feedback, BorderLayout.SOUTH);

        // Eine Kachel über die volle Breite; Ergebnis und Ausgabe stehen unter Inhalte › Export.
        JComponent tiles = stack(Comic.plate("Auftrag", Comic.PETROL, facts));
        JPanel details = new JPanel(new BorderLayout(0, 8));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        details.add(head, BorderLayout.NORTH);
        details.add(tiles, BorderLayout.CENTER);
        this.details = details;

        detailScroll = Comic.column(details);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, list,
                detailScroll);
        split.setBackground(Comic.SURFACE);
        // Derselbe Trenner wie neben der Wissensquellen-Liste, nur liegend.
        ComicDivider.install(split, Comic.PALETTE);
        split.setResizeWeight(0.4);
        split.setDividerLocation(260);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);

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

    /**
     * Keine eigene Aktionszeile: der Gesamtstand der Aufträge geht als {@link JobProgressEvent} an die Statuszeile,
     * Anhalten und Abbrechen für alle stehen in der Kopfzeile der Liste.
     */
    @Override
    public JComponent actions() {
        return null;
    }

    @Override
    public void shown() {
        poll();
    }

    /**
     * Fragt die Aufträge wieder ab, ohne die Auswahl zu ändern oder die Ansicht zu wechseln, etwa nach einem Start aus
     * einem anderen Bereich („Fachbegriffe ermitteln“): der Balken der Statusleiste nimmt den neuen Auftrag in seinen
     * Gesamtstand auf.
     */
    public void follow() {
        poll();
    }

    /** Zeigt einen Auftrag (z. B. direkt nach dem Start). */
    public void show(String jobId) {
        toTop = toTop || !jobId.equals(selectedId);
        selectedId = jobId;
        poll();
    }

    /**
     * Zeigt das Codefeld dieser Anforderung (Klick auf den Hinweis in der Statuszeile): den ersten wartenden Auftrag,
     * ohne wartenden Auftrag (Lesen auf Abruf) keinen, dann steht die erste offene Anforderung oben.
     */
    public void showSignIn(SignInRequest request) {
        if (request.jobIds().isEmpty()) {
            selectedId = null;
            detail = null;
            table.clearSelection();
            render();
            poll();
        } else {
            show(request.jobIds().get(0));
        }
        signIn.refresh();
        signIn.focusCode();
    }

    /** Meldet sich einmal je Laufzeit an; eine neue Laufzeit meldet neu an, ohne Laufzeit wird abgemeldet. */
    private void subscribe() {
        AppRuntime runtime = context.runtime();
        if (runtime == subscribed) {
            return;
        }
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        subscribed = runtime;
        if (runtime == null) {
            return;
        }
        subscription = runtime.workspace().subscribeJobs(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                scheduleRefresh();
            }

            @Override
            public void removed(JobStatus last) {
                scheduleRefresh();
            }

            @Override
            public void event(JobEvent event) {
                // Die Phase des gewählten Auftrags kommt aus seiner Diagnose.
                scheduleRefresh();
            }
        });
    }

    /** Vom Zustell-Thread des Core: einmal auf dem EDT lesen, auch bei vielen Meldungen hintereinander. */
    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                refreshPending = false;
            }
            poll();
        });
    }

    static final String STAGE_LABEL = "Vorstufe";
    /** Bezeichnung der Angaben am Auftrag „Aussagen prüfen“ (ADR 0032, FL4). */
    static final String CHECK_LABEL = "Prüfschritt";

    /** Bezeichnung der Ergebniszeile der Regelphase von „Fachbegriffe ermitteln“ (ADR 0032, FL5b). */
    static final String RESULT_LABEL = "Ergebnis";
    /** Bezeichnung einer bewusst ausgelassenen Phase; gelb, nie rot. */
    static final String SKIPPED_LABEL = "Bewusst ausgelassen";
    /** Angabe am Auftrag „Bilder beschreiben“ (Fahrplan B1): bewusst ausgelassen oder Obergrenze erreicht, gelb. */
    static final String IMAGES_LABEL = "Bilder";

    /** Eine Angabe am Auftrag aus einem Ereignis mit Code; {@code skipped} zeigt sie gelb. */
    record Note(String label, String text, boolean skipped) {
    }

    /**
     * Die Zahlen der Vorstufe „Sätze vereinfachen“ einer Aussagenanalyse für die Angaben am Auftrag (ADR 0032, FL3):
     * Seiten ohne KI-Freigabe und, getrennt davon, Seiten ohne eingestelltes Modell, jeweils ohne Vorstufe analysiert.
     * Der Core meldet sie als Ereignisse mit Code; je Code zählt das jüngste (nach Fortsetzen). Ebenso beim Auftrag
     * „Aussagen prüfen“ (FL4) die Bilanz des Prüfschritts und die Seiten ohne KI-Freigabe.
     */
    static List<String> stageNotes(List<JobEvent> events) {
        return notes(events).stream().map(Note::text).toList();
    }

    /**
     * Alle Angaben am Auftrag aus Ereignissen mit Code, je Code die jüngste: Vorstufe und Prüfschritt der
     * Aussagenanalyse, bei „Fachbegriffe ermitteln“ die ausgelassene Modellphase (gelb) und die Ergebniszeile der
     * Regelphase. Den Text deutet die App nicht.
     */
    static List<Note> notes(List<JobEvent> events) {
        java.util.Map<String, Note> notes = new java.util.LinkedHashMap<>();
        for (JobEvent event : events) {
            Note note = note(event.code(), event.message());
            if (note != null) {
                notes.put(event.code(), note);
            }
        }
        return List.copyOf(notes.values());
    }

    private static Note note(String code, String message) {
        if (code == null) {
            return null;
        }
        if (code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.STAGE_NOT_RELEASED)
                || code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.STAGE_NO_MODEL)) {
            return new Note(STAGE_LABEL, message, false);
        }
        if (code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.CHECK_COSTS)
                || code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs
                .CHECK_NOT_RELEASED)) {
            return new Note(CHECK_LABEL, message, false);
        }
        if (code.startsWith(com.aresstack.spaceharvester.application.glossary.GlossaryClassification.MODEL_SKIPPED
                + ":")) {
            return new Note(SKIPPED_LABEL, message, true);
        }
        // Fahrplan B1: ohne Vision-Modell, mit Einmalcode oder an der Obergrenze bewusst ausgelassen (gelb).
        if (code.startsWith("IMAGES_SKIPPED:")
                || code.equals(com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions
                .LIMIT_REACHED)) {
            return new Note(IMAGES_LABEL, message, true);
        }
        if (code.equals(com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions.DONE)) {
            return new Note(RESULT_LABEL, message, false);
        }
        if (code.equals(com.aresstack.spaceharvester.application.glossary.GlossaryClassification.RULES_DONE)) {
            return new Note(RESULT_LABEL, message, false);
        }
        return null;
    }

    /** Liest Aufträge und den gewählten Auftrag einmal; danach lesen nur die Meldungen des Core erneut. */
    private void poll() {
        subscribe();
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            model.set(List.of());
            return;
        }
        if (reading) {
            readAgain = true;
            return;
        }
        reading = true;
        String id = selectedId;
        Background.run(() -> {
            List<JobStatus> jobs = runtime.workspace().jobs();
            Object current = null;
            String currentPhase = null;
            if (id != null) {
                JobStatus status = runtime.workspace().job(id);
                if (!status.state().terminal()) {
                    currentPhase = runtime.workspace().events(id).stream().map(JobEvent::phase)
                            .filter(Objects::nonNull).reduce((first, second) -> second).orElse(null);
                }
                current = status.type() == JobType.SYNC ? runtime.workspace().syncStatus(id) : status;
                if (status.type() == JobType.EXPORT && status.state() == JobState.COMPLETED) {
                    current = new ExportDetail(status, runtime.workspace().exportArtifact(id).orElse(null));
                }
            }
            return new Object[] {jobs, current, currentPhase, notes(id == null ? List.of()
                    : runtime.workspace().events(id))};
        }, data -> {
            readDone();
            @SuppressWarnings("unchecked")
            List<JobStatus> jobs = (List<JobStatus>) data[0];
            for (JobType type : new JobType[] {JobType.ASSETS, JobType.ATTACHMENTS}) {
                averages.put(type, runtime.workspace().averageDownload(type));
            }
            parallelNow = runtime.workspace().parallelDownloads();
            // Gesamtstand aller Aufträge für die Statuszeile, Balken hinter laufenden Exporten (Angelo 11.11.2026).
            Instant now = Instant.now();
            overall.observe(jobs, now, averages::get);
            groupEstimates = groupEstimates(jobs, now);
            model.set(jobs);
            context.events().publish(overall.event(jobs, now, averages::get, parallelNow));
            updateGlobalButtons(jobs);
            deleteFinished.setEnabled(jobs.stream().anyMatch(ActivitiesView::finished));
            downloads.refresh(runtime.workspace().parallelDownloads());
            preparations.refresh(runtime.workspace().parallelPreparations());
            connections.refresh(runtime.workspace().downloadConnections());
            // Ein Abruf für einen inzwischen abgewählten Auftrag zeigt nichts mehr an. Das Detail steht vor dem
            // Markieren der Zeile fest, damit deren Auswahlmeldung keinen weiteren Abruf auslöst.
            boolean shown = java.util.Objects.equals(id, selectedId);
            if (shown) {
                detail = id == null ? null : data[1];
                phase = (String) data[2];
                @SuppressWarnings("unchecked")
                List<Note> notes = (List<Note>) data[3];
                stageNotes = id == null ? List.of() : notes;
            }
            reselect();
            if (shown) {
                render();
            }
        }, error -> {
            readDone();
            // Ließ sich der gewählte Auftrag nicht lesen, bleibt keine Zeile markiert, deren Detail fehlt: Ein
            // erneuter Klick auf sie wählt sie wieder und liest nach.
            if (id != null && id.equals(selectedId) && detail == null) {
                selectedId = null;
                table.clearSelection();
                render();
            }
            feedback.problem(error);
        });
    }

    /** Nach einer Abfrage: kam währenddessen eine Meldung, einmal nachlesen. */
    private void readDone() {
        reading = false;
        if (readAgain) {
            readAgain = false;
            SwingUtilities.invokeLater(this::poll);
        }
    }

    /** Wählt einen Auftrag; derselbe Auftrag ohne gelesenes Detail wird erneut gelesen. */
    private void select(String id) {
        if (id.equals(selectedId) && detail != null) {
            return;
        }
        if (!id.equals(selectedId)) {
            selectedId = id;
            detail = null;
            stageNotes = List.of();
            toTop = true;
            details.revalidate();
            details.repaint();
            feedback.clear();
        }
        poll();
    }

    /** Klappt alle Gruppen zu ({@code collapse}) oder auf; die Auswahl bleibt beim selben Auftrag, sofern sichtbar. */
    void setAllGroups(boolean collapse) {
        model.setAll(collapse);
        reselect();
    }

    /** Klappt die Gruppe in der Zeile {@code row} auf oder zu; die Auswahl bleibt beim selben Auftrag. */
    void toggleGroup(int row) {
        if (row < 0 || row >= model.rows.size() || !model.group(model.rows.get(row))) {
            return;
        }
        model.toggle(model.rows.get(row));
        reselect();
    }

    /**
     * Markiert die Zeile des gewählten Auftrags; ist er nicht sichtbar (zugeklappt, noch nicht gelistet), bleibt keine
     * andere Zeile markiert, deren Klick sonst keine Auswahl meldet.
     */
    private void reselect() {
        for (int i = 0; i < model.rows.size(); i++) {
            if (model.rows.get(i).id().equals(selectedId)) {
                if (table.getSelectedRow() != i) {
                    table.getSelectionModel().setSelectionInterval(i, i);
                }
                return;
            }
        }
        if (table.getSelectedRow() >= 0) {
            table.clearSelection();
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

    /**
     * Zeichnet den Detailbereich neu, ohne dass er springt (Angelo 10.11.2026): Die Bildlaufposition bleibt (die
     * Hinweistexte ziehen sie nicht mehr zu sich, {@link Comic#noAutoScroll}), außer nach einem Auftragswechsel oder
     * wenn das Codefeld neu erscheint; dann steht der Bereich oben, das Codefeld also sichtbar, ohne zu scrollen.
     */
    private void render() {
        boolean codeBefore = signIn.isVisible();
        // Nur zeichnen, was sich geändert hat (Angelo 11.11.2026): Ein angehaltener Auftrag liefert jede Sekunde
        // denselben Stand; neu gezeichnet zitterte der Bereich und die Bildlaufposition sprang.
        Object shown = drawnState();
        if (!toTop && shown.equals(drawn)) {
            signIn.refresh();
            if (codeBefore || !signIn.isVisible()) {
                return;
            }
        }
        drawn = shown;
        draw();
        if (toTop || (!codeBefore && signIn.isVisible())) {
            toTop = false;
            javax.swing.SwingUtilities.invokeLater(() ->
                    detailScroll.getViewport().setViewPosition(new java.awt.Point(0, 0)));
        }
    }

    /** Was der Detailbereich zuletzt gezeigt hat ({@link #drawnState}). */
    private Object drawn;

    /**
     * Alles, wovon {@link #draw()} abhängt: auch die Dauer eines laufenden Auftrags.
     */
    private Object drawnState() {
        Optional<JobStatus> current = currentStatus();
        return java.util.Arrays.asList(selectedId, detail, phase, model.rows.isEmpty(),
                current.map(s -> waitingForSignIn(s.id())).orElse(false),
                current.map(s -> Texts.duration(s.startedAt(), s.finishedAt())).orElse(null));
    }

    private void draw() {
        signIn.refresh();
        Optional<JobStatus> current = currentStatus();
        facts.reset();
        if (current.isEmpty()) {
            title.setText(model.rows.isEmpty() ? "Noch keine Aufträge" : "Kein Auftrag gewählt");
            details.revalidate();
            details.repaint();
            updateButtons();
            return;
        }
        JobStatus status = current.get();
        title.setText(Texts.type(status) + " " + where(status));
        JobProgress p = status.progress();
        Counters counters = Counters.of(status.type());
        boolean waiting = waitingForSignIn(status.id());
        facts.add("Zustand", waiting ? SIGN_IN : Texts.state(status),
                        waiting ? Comic.ORANGE : Comic.tone(Texts.tone(status)))
                .add("Quelle", status.sourceId())
                .add("Space", status.partitionKey())
                .add("Startseite", startPage(status))
                .add("Angelegt", Texts.time(status.createdAt()))
                .add("Gestartet", Texts.time(status.startedAt()))
                .add("Beendet", Texts.time(status.finishedAt()))
                .add("Dauer", Texts.duration(status.startedAt(), status.finishedAt()))
                .add("Phase", status.state() == JobState.RUNNING && phase != null ? phase : "–")
                .add(counters.discovered(), String.valueOf(p.discovered()))
                .add(counters.processed(), String.valueOf(p.processed()))
                .add(counters.written(), String.valueOf(p.written()))
                .add(counters.failed(), String.valueOf(p.failed()), p.failed() > 0 ? Comic.RED : Comic.INK);
        if (skipped(status) > 0) {
            facts.add("Bewusst ausgelassen", String.valueOf(skipped(status)));
        }
        for (Note note : stageNotes) {
            facts.add(note.label(), note.text(), note.skipped() ? Comic.ORANGE : Comic.INK);
        }
        facts.add("Auftrags-ID", status.id());
        outputShown = outputDirectory(status,
                context.settings() == null ? null : context.settings().outputDirectory());
        if (outputShown != null) {
            facts.add(OUTPUT_LABEL, outputShown.toString(), Comic.INK, openOutput);
        }
        if (status.error() != null) {
            facts.add("Fehler", status.error().code() + ": " + status.error().message(), Comic.RED);
        }
        if (detail instanceof SpaceSyncJobs.SyncJob sync && sync.summary() != null) {
            renderSync(status, sync.summary());
        } else if (detail instanceof ExportDetail export) {
            renderExport(export);
        } else if (!status.state().terminal()) {
            feedback.working(Texts.type(status) + " läuft …");
        } else {
            renderTerminal(status, true);
        }
        details.revalidate();
        details.repaint();
        updateButtons();
        root.revalidate();
    }

    /**
     * Das Ergebnis eines Abgleichs steht unter den Zahlen des Laufs in derselben Kachel. „Fehlend“ sind nur neue Seiten,
     * deren Abruf scheiterte; Seiten, die der Lauf bewusst nur katalogisiert, zählen unter „Nur im Katalog“.
     */
    private void renderSync(JobStatus status, SyncSummary summary) {
        List<String> missing = summary.missingPageIds().stream().filter(summary.failedPageIds()::contains).toList();
        facts.add("Gelistet", String.valueOf(summary.listed()))
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
                .add("Fehlend", String.valueOf(missing.size()), missing.isEmpty() ? Comic.INK : Comic.RED)
                .add("Abfrage", inventory(summary.inventory()))
                .add("Vollständig", summary.complete() ? "ja" : "nein", summary.complete() ? Comic.GREEN : Comic.ORANGE)
                .add("Generation", summary.generation() == null ? "keine bestätigt" : String.valueOf(summary.generation()))
                .add("Space-Fingerabdruck", summary.spaceFingerprint());
        if (!summary.failedPageIds().isEmpty()) {
            facts.add("Fehlgeschlagene Seiten", String.join(", ", summary.failedPageIds()), Comic.RED);
        }
        if (!missing.isEmpty()) {
            facts.add("Fehlende Seiten", String.join(", ", missing), Comic.RED);
        }
        boolean confirmed = summary.generation() != null && summary.notConfirmed() == null;
        if (!status.state().terminal()) {
            feedback.working("Abgleich läuft …");
        } else if (status.state() == JobState.COMPLETED && confirmed && !summary.failedPageIds().isEmpty()) {
            feedback.warning("Generation " + summary.generation() + " bestätigt, "
                    + Texts.count(summary.failedPageIds().size(), "Seite", "Seiten") + " fehlgeschlagen",
                    "Sie bleiben veraltet bzw. fehlen; „Nur Fehler neu versuchen“ holt sie nach.");
        } else if (status.state() == JobState.COMPLETED && confirmed && summary.complete()) {
            feedback.success("Abgleich vollständig, Generation " + summary.generation() + " bestätigt");
        } else if (status.state() == JobState.COMPLETED && confirmed) {
            // Bestätigt, aber nicht jede Seite mit Inhalt: Der Lauf hat Seiten bewusst nur katalogisiert.
            feedback.info("Generation " + summary.generation() + " bestätigt",
                    Texts.count(summary.notLoaded(), "Seite", "Seiten") + " nur im Katalog: geladen werden sie beim "
                            + "Öffnen, von der Suche oder über „Indexieren“ im Baum.");
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
        if (artifact.complete()) {
            feedback.success("Download vollständig · Ausgabe unter Inhalte › Export");
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
                    feedback.success(Texts.type(status) + " fertig");
                }
            }
            case CANCELLED -> feedback.warning("Abgebrochen", "Teilergebnisse bleiben unbestätigt.");
            case PAUSED -> feedback.info("Angehalten", "Geladenes bleibt; „Fortsetzen“ holt nur, was noch fehlt.");
            case FAILED -> feedback.warning("Fehlgeschlagen", status.error() == null ? null
                    : status.error().code() + ": " + status.error().message());
            default -> feedback.working(Texts.state(status.state()));
        }
    }

    static final String OUTPUT_LABEL = "Ausgabeordner";

    /**
     * Wohin ein Auftrag schreibt (Angelo 20.11.2026): „PPTX erzeugen“ in den Ordner seiner Datei, „Datei erzeugen“,
     * Metadaten- und Analytics-Export in den Ordner, den sie beim Start bekamen, ein Download in den Ausgabeordner der
     * Einstellungen; andere Aufträge schreiben keine Dateien ({@code null}).
     */
    static java.nio.file.Path outputDirectory(JobStatus status, java.nio.file.Path exportDirectory) {
        return switch (status.type()) {
            case PRESENTATION_EXPORT -> {
                String file = status.parameters().get(
                        com.aresstack.spaceharvester.application.presentation.VideoPresentations.FILE_PARAMETER);
                yield file == null ? null : java.nio.file.Path.of(file).toAbsolutePath().getParent();
            }
            case CONVERSION -> parameterDirectory(status,
                    com.aresstack.spaceharvester.application.export.ExportConversions.DIRECTORY, exportDirectory);
            case METADATA_EXPORT, ANALYTICS_EXPORT -> parameterDirectory(status,
                    com.aresstack.spaceharvester.application.metadata.SourceQueryExports.DIRECTORY, exportDirectory);
            case EXPORT -> exportDirectory;
            // Ergebnis und Bericht der Suchqualität liegen im Unterordner „Suchqualität“ des Ausgabeordners (Q1).
            case SEARCH_QUALITY -> {
                String file = status.parameters().get(
                        com.aresstack.spaceharvester.application.quality.SearchQualityRuns.RESULT_PARAMETER);
                yield file == null ? null : java.nio.file.Path.of(file).toAbsolutePath().getParent();
            }
            default -> null;
        };
    }

    private static java.nio.file.Path parameterDirectory(JobStatus status, String parameter,
                                                         java.nio.file.Path fallback) {
        String directory = status.parameters().get(parameter);
        if (directory == null || directory.isBlank()) {
            return fallback;
        }
        try {
            return java.nio.file.Path.of(directory).toAbsolutePath();
        } catch (java.nio.file.InvalidPathException e) {
            return fallback;
        }
    }

    /** Quelle / Space eines Auftrags, bei einem Seiten-Download dazu „ab“ seiner Startseite. */
    static String where(JobStatus status) {
        if (status.type() == JobType.SEARCH_QUALITY) {
            // Nur der Name des Testbestands, nie eine Frage (Q1).
            String suite = com.aresstack.spaceharvester.application.quality.SearchQualityRuns.suiteName(status);
            return suite.isEmpty() ? "Testbestand" : "Testbestand „" + suite + "“";
        }
        String where = status.partitionKey() == null || status.partitionKey().isBlank()
                ? status.sourceId() : status.sourceId() + " / " + status.partitionKey();
        return status.rootPageId() == null ? where : where + " ab „" + startPage(status) + "“";
    }

    /** Titel der Startseite, solange der Auftrag sie noch nicht gelesen hat ihre ID; „–“ für ganze Spaces. */
    static String startPage(JobStatus status) {
        if (status.rootPageId() == null) {
            return "–";
        }
        return status.rootTitle() == null ? "Seite " + status.rootPageId() : status.rootTitle();
    }

    private void updateButtons() {
        Optional<JobStatus> current = currentStatus();
        boolean running = current.map(s -> !s.state().terminal()).orElse(false);
        again.setVisible(current.map(s -> s.type() == JobType.SYNC).orElse(false));
        again.setEnabled(!running);
    }

    /** Plätze des Fortschrittsbalkens je Zeile (früher die fünf Format-Symbole, jetzt unter Inhalte › Export). */
    static final int PROGRESS_SLOTS = 5;
    private static final javax.swing.Icon DIAGNOSIS = Glyph.PULSE;
    private static final javax.swing.Icon CONTENT = ComposerIcons.openBook();

    private static final javax.swing.Icon START_NOW = ComposerIcons.chevronRight();
    private static final javax.swing.Icon PAUSE = ComposerIcons.pause();
    private static final javax.swing.Icon RESUME = ComposerIcons.play();
    private static final javax.swing.Icon CANCEL = ComposerIcons.stop();
    private static final javax.swing.Icon RETRY_FAILED = ComposerIcons.refresh();
    private static final javax.swing.Icon FORCE_REFRESH = ComposerIcons.download();
    /**
     * Feste Plätze je Zeile (Angelo 10.11.2026), damit Aktionen in jeder Zeile untereinander stehen: der
     * Fortschrittsbalken über {@link #PROGRESS_SLOTS} Plätze, ein freier Platz als Abstand, Diagnose, Inhalte, dann die
     * Zustandsplätze {@link Slot}. Was ein Zustand nicht anbietet, steht blass da; jeder Platz ist immer belegt.
     */
    static final int SLOTS = PROGRESS_SLOTS + 1 + 2 + Slot.values().length;

    /**
     * Die Zustandsplätze rechts, von links nach rechts, alle immer sichtbar (Angelo 10.11.2026): Ein Platz wechselt
     * sein Symbol, statt zu verschwinden; was der Zustand nicht erlaubt, ist blass.
     */
    enum Slot {
        /** „Nur Fehler neu versuchen“ (↻). */
        RETRY,
        /** Play (Fortsetzen, Jetzt starten) ⇄ Pause. */
        PLAY_PAUSE,
        /** Stop, solange der Auftrag läuft oder wartet ⇄ „Neu erzwingen“, wenn er steht. */
        STOP_FORCE,
        /** Löschen, in jedem Zustand. */
        DELETE
    }

    /**
     * Welche Zustandsaktion auf einem Platz liegt, allein aus {@link JobLifecycleState#actions()} (State Object, ADR
     * 0025). {@code null}, wenn der Zustand dort nichts anbietet; dann steht das Symbol blass da ({@link #idleIcon}).
     */
    static JobAction actionFor(Slot slot, JobLifecycleState state) {
        return switch (slot) {
            case RETRY -> state.allows(JobAction.RETRY_FAILED) ? JobAction.RETRY_FAILED : null;
            case PLAY_PAUSE -> state.allows(JobAction.PAUSE) ? JobAction.PAUSE
                    : state.allows(JobAction.RESUME) ? JobAction.RESUME
                    : state.allows(JobAction.START_NOW) ? JobAction.START_NOW : null;
            case STOP_FORCE -> state.allows(JobAction.CANCEL) ? JobAction.CANCEL
                    : state.allows(JobAction.FORCE_REFRESH) ? JobAction.FORCE_REFRESH : null;
            case DELETE -> JobAction.DELETE;
        };
    }

    /** Das blasse Symbol eines Platzes, auf dem der Zustand nichts anbietet. */
    static javax.swing.Icon idleIcon(Slot slot, JobLifecycleState state) {
        return switch (slot) {
            case RETRY -> RETRY_FAILED;
            case PLAY_PAUSE -> RESUME;
            // Steht der Auftrag, gehört der Platz zu „Neu erzwingen“, sonst zu Stop.
            case STOP_FORCE -> state instanceof JobLifecycleState.Stopping ? CANCEL : FORCE_REFRESH;
            case DELETE -> Glyph.TRASH;
        };
    }

    /** Tooltip eines blassen Platzes: was dort sonst geht und warum nicht. */
    static String idleTip(Slot slot, JobStatus job) {
        String why = " · in diesem Zustand nicht möglich (" + Texts.state(job) + ")";
        return switch (slot) {
            case RETRY -> "Nur Fehler neu versuchen" + why;
            case PLAY_PAUSE -> "Fortsetzen" + why;
            case STOP_FORCE -> "Abbrechen bzw. neu erzwingen" + why;
            case DELETE -> "Auftrag löschen";
        };
    }

    /**
     * Die Aktionen einer Zeile auf ihren festen Plätzen ({@link #SLOTS}): fünf Formate samt Analyse, Abstand, Diagnose,
     * Inhalte, dann die Zustandsplätze. Die Belegung kommt allein vom Zustand im Core ({@link #actionFor}).
     */
    private List<RowActions.Action> rowActions(int index) {
        if (index < 0 || index >= model.rows.size()) {
            return List.of();
        }
        JobStatus job = model.rows.get(index);
        List<RowActions.Action> actions = new ArrayList<>();
        actions.addAll(progressSlots(job, remaining(job, averages.get(job.type()), parallelNow)));
        actions.add(RowActions.EMPTY);
        boolean failed = job.state() == JobState.FAILED;
        actions.add(new RowActions.Action(DIAGNOSIS, failed ? "Fehler diagnostizieren" : "Diagnose ansehen", true,
                () -> context.navigator().showDiagnosis(job.id())));
        boolean space = job.sourceId() != null && job.partitionKey() != null;
        // Öffnet Inhalte › Export für den Space bzw. die Startseite des Auftrags (Angelo, 13.11.2026).
        actions.add(new RowActions.Action(CONTENT, space ? "Inhalte öffnen · Stand und Ausgabe unter Export"
                : "Inhalte öffnen · kein Space", space,
                () -> context.navigator().openExport(job.sourceId(), job.partitionKey(), job.rootPageId())));
        actions.addAll(stateActions(job));
        return actions;
    }

    /**
     * Ob die Zeile ein Bilder- oder Anhängeauftrag ist: nur sie haben eine Restzeit und hängen an ihrem Export. Den
     * Fortschrittsbalken zeigen seit 13.11.2026 alle Zeilen (die Formate stehen unter Inhalte › Export).
     */
    static boolean showsProgress(JobStatus job) {
        return job.type() == JobType.ASSETS || job.type() == JobType.ATTACHMENTS;
    }

    /**
     * Geschätzte Restzeit eines Bilder- oder Anhängeauftrags (Angelo 11.11.2026): globaler Durchschnitt je tatsächlich
     * geladener Datei (Core, getrennt für Bilder und Anhänge, mit Startwert, über Neustarts gemerkt) mal der noch
     * offenen Elemente. Ein Auftrag lädt seine Dateien nacheinander; parallel laufen nur mehrere Aufträge. Keine
     * Schätzung, wenn nichts offen ist, der Auftrag beendet ist oder Downloads abgeschaltet sind (0).
     */
    static java.time.Duration remaining(JobStatus job, java.time.Duration average, int parallelDownloads) {
        if (average == null || parallelDownloads <= 0 || !showsProgress(job) || job.state().terminal()) {
            return null;
        }
        long open = job.progress().discovered() - job.progress().processed();
        return open <= 0 ? null : average.multipliedBy(open);
    }

    /** Kurz genug für die Mitte des Balkens: „< 1 min“, „≈ 12 min“, „≈ 5 h 3 min“. */
    static String remainingText(java.time.Duration remaining) {
        long seconds = remaining.getSeconds();
        if (seconds < 60) {
            return "< 1 min";
        }
        long minutes = (seconds + 59) / 60;
        return minutes < 60 ? "≈ " + minutes + " min" : "≈ " + minutes / 60 + " h " + minutes % 60 + " min";
    }

    /** Der Fortschrittsbalken über die fünf Formatplätze: im ersten Platz, die übrigen frei. */
    static List<RowActions.Action> progressSlots(JobStatus job) {
        return progressSlots(job, null);
    }

    /** Wie {@link #progressSlots(JobStatus)}, mit geschätzter Restzeit in der Mitte des Balkens, sofern bekannt. */
    static List<RowActions.Action> progressSlots(JobStatus job, java.time.Duration remaining) {
        JobProgress p = job.progress();
        String eta = remaining == null ? null : remainingText(remaining);
        Counters counters = Counters.of(job.type());
        long skipped = skipped(job);
        String tip = p.discovered() == 0 ? "Noch nichts gefunden"
                : p.processed() + " von " + p.discovered() + " " + counters.processed().toLowerCase(java.util.Locale.GERMAN)
                        + (skipped > 0 ? " · " + skipped + " bewusst ausgelassen" : "")
                        + (p.failed() > 0 ? " · " + p.failed() + " " + counters.failed().toLowerCase(java.util.Locale.GERMAN) : "")
                        + (eta == null ? "" : " · noch " + eta.replace("≈ ", "etwa ").replace("< 1 min", "unter einer Minute"));
        List<RowActions.Action> slots = new ArrayList<>();
        slots.add(new RowActions.Action(new ProgressIcon(PROGRESS_SLOTS * RowActions.SLOT - 8, p.discovered(),
                p.processed(), p.failed(), skipped, eta), tip, true, () -> { }));
        for (int i = 1; i < PROGRESS_SLOTS; i++) {
            slots.add(RowActions.EMPTY);
        }
        return slots;
    }

    /**
     * Schmaler Balken: gefüllt nach Anteil des Erledigten, Gescheitertes rot am Ende, in der Mitte die geschätzte
     * Restzeit ({@code label}, sonst leer). Die Schrift ist auf der Füllung hell, auf der leeren Spur dunkel.
     */
    /**
     * Der Balken einer Zeile: Petrol für Abgelegtes, davor Gelb für bewusst Ausgelassenes (Diagramm-Vorschauen, nach
     * Einstellung ausgelassene Anhänge), am Ende Rot nur für Fehler und fehlenden Zugriff (Angelo, 13.11.2026: „sonst
     * denkt der User, alles, was rot ist, ist ein Fehler“).
     */
    record ProgressIcon(int width, long total, long done, long failed, long skipped, String label)
            implements javax.swing.Icon {

        ProgressIcon(int width, long total, long done, long failed) {
            this(width, total, done, failed, 0, null);
        }

        ProgressIcon(int width, long total, long done, long failed, String label) {
            this(width, total, done, failed, 0, label);
        }

        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                int h = getIconHeight();
                g.setColor(Comic.LINE);
                g.fillRoundRect(x, y, width, h, h, h);
                int filled = 0;
                if (total > 0) {
                    filled = (int) Math.round(width * Math.min(1.0, (double) done / total));
                    int bad = Math.min(filled, (int) Math.round(width * Math.min(1.0, (double) failed / total)));
                    int left = Math.min(filled - bad, (int) Math.round(width * Math.min(1.0,
                            (double) skipped / total)));
                    g.setColor(Comic.PETROL);
                    g.fillRoundRect(x, y, filled, h, h, h);
                    if (left > 0) {
                        g.setColor(Comic.YELLOW);
                        g.fillRoundRect(x + filled - bad - left, y, left, h, h, h);
                    }
                    if (bad > 0) {
                        g.setColor(Comic.RED);
                        g.fillRoundRect(x + filled - bad, y, bad, h, h, h);
                    }
                }
                if (label != null) {
                    g.setFont(Comic.semiBold(10f));
                    java.awt.FontMetrics fm = g.getFontMetrics();
                    int tx = x + (width - fm.stringWidth(label)) / 2;
                    int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
                    java.awt.Shape clip = g.getClip();
                    g.clipRect(x, y, filled, h);
                    g.setColor(java.awt.Color.WHITE);
                    g.drawString(label, tx, ty);
                    g.setClip(clip);
                    g.clipRect(x + filled, y, width - filled, h);
                    g.setColor(Comic.INK);
                    g.drawString(label, tx, ty);
                }
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return 14;
        }
    }

    /** Die Zustandsplätze einer Zeile; für Tests ohne Tabelle. */
    List<RowActions.Action> stateActions(JobStatus job) {
        return stateActions(job, action -> perform(job, action));
    }

    /** Die Zustandsplätze einer Zeile, immer {@link Slot#values()} viele, an festen Plätzen. */
    static List<RowActions.Action> stateActions(JobStatus job, Consumer<JobAction> perform) {
        JobLifecycleState state = job.lifecycle();
        List<RowActions.Action> actions = new ArrayList<>();
        for (Slot slot : Slot.values()) {
            JobAction action = actionFor(slot, state);
            actions.add(action != null
                    ? new RowActions.Action(actionIcon(action), actionTip(action, job), true, () -> perform.accept(action))
                    : new RowActions.Action(idleIcon(slot, state), idleTip(slot, job), false, () -> { }));
        }
        return actions;
    }

    static javax.swing.Icon actionIcon(JobAction action) {
        return switch (action) {
            case START_NOW -> START_NOW;
            case PAUSE -> PAUSE;
            case RESUME -> RESUME;
            case CANCEL -> CANCEL;
            case RETRY_FAILED -> RETRY_FAILED;
            case FORCE_REFRESH -> FORCE_REFRESH;
            case DELETE -> Glyph.TRASH;
        };
    }

    /**
     * Der Tooltip einer Zustandsaktion. Hat ein Download Dateien wegen fehlender Anmeldung (Einmalcode) ausgelassen,
     * sagt „Fortsetzen“ das dazu, denn genau dann kommt die Abfrage wieder.
     */
    static String actionTip(JobAction action, JobStatus job) {
        return actionTip(action, job, null);
    }

    static String actionTip(JobAction action, JobStatus job, ExportDatasetInfo dataset) {
        return switch (action) {
            case START_NOW -> "Jetzt starten · vor die anderen wartenden Aufträge";
            case PAUSE -> "Anhalten · Geladenes bleibt, später fortsetzen";
            case RESUME -> {
                int signIn = dataset == null ? 0 : dataset.media().signInRequired();
                yield signIn > 0 ? "Fortsetzen · " + Texts.count(signIn, "Datei fehlt", "Dateien fehlen")
                        + " wegen fehlender Anmeldung; die Einmalcode-Abfrage kommt dann wieder"
                        : "Fortsetzen · holt nur, was noch fehlt";
            }
            case CANCEL -> job.pauseRequested() ? "Abbrechen · statt nur anzuhalten" : "Abbrechen";
            case RETRY_FAILED -> "Nur Fehler neu versuchen · die gescheiterten Seiten gezielt nachladen, "
                    + "ohne neue Inventur, im selben Eintrag";
            case FORCE_REFRESH -> "Neu erzwingen · alles neu von der Quelle abrufen, auch Unverändertes; "
                    + "der bisherige Stand bleibt, bis der neue fertig ist";
            case DELETE -> "Auftrag löschen";
        };
    }

    /**
     * Führt eine Zustandsaktion aus (Symbol in der Zeile): Abbrechen und Löschen nach Rückfrage, alles über den einen
     * Einstieg des Core, der die Aktion am Zustand prüft. Fortsetzen bleibt im selben Eintrag, auch nach einem
     * Neustart.
     */
    void perform(JobStatus job, JobAction action) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        if (!job.lifecycle().allows(action)) {
            return;
        }
        switch (action) {
            case CANCEL -> {
                if (!Dialogs.confirm(root, "Auftrag abbrechen?", Texts.type(job) + " von "
                        + job.partitionKey() + " wirklich abbrechen? Bereits Gelesenes bleibt unbestätigt.")) {
                    return;
                }
            }
            case DELETE -> {
                if (!Dialogs.confirm(root, "Auftrag löschen?", Texts.type(job) + " von " + job.partitionKey()
                        + " wirklich löschen?" + (job.state().terminal() ? "" : " Er wird dafür abgebrochen.")
                        + " Der Auftrag und seine Diagnoseereignisse werden dauerhaft gelöscht. " + KEPT)) {
                    return;
                }
            }
            default -> { }
        }
        Background.run(() -> runtime.workspace().performJobAction(job.id(), action), after -> {
            if (action == JobAction.DELETE) {
                if (job.id().equals(selectedId)) {
                    selectedId = null;
                    detail = null;
                    table.clearSelection();
                    render();
                }
                feedback.success("Auftrag gelöscht");
            } else if (action == JobAction.RESUME || action == JobAction.RETRY_FAILED
                    || action == JobAction.FORCE_REFRESH) {
                show(after.id());
            }
            poll();
        }, feedback::problem);
    }

    /** Für Tests. */
    /** Für Tests und Proben: die Überschrift des Detailbereichs. */
    String detailTitleForTest() {
        return title.getText();
    }

    JTable table() {
        return table;
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
            if (type == JobType.PURGE) {
                return new Step((phase == null ? "Ablagen leeren" : phase) + " · " + p.processed() + " von " + total
                        + " Ablagen", p.processed(), total);
            }
            if (type == JobType.ASSETS) {
                return new Step("Bilder beschaffen · " + p.processed() + " von " + total + " Bildern geprüft, "
                        + p.written() + " abgelegt", p.processed(), total);
            }
            if (type == JobType.ATTACHMENTS) {
                return new Step("Anhänge beschaffen · " + p.processed() + " von " + total + " Anhängen geprüft, "
                        + p.written() + " abgelegt", p.processed(), total);
            }
            if (type == JobType.GLOSSARY) {
                // Erst Fundstellen sammeln (ohne Ziel), dann Begriffe beim Modell beurteilen lassen.
                String step = phase == null ? "Fachbegriffe ermitteln" : phase;
                if (total == 0) {
                    return new Step(step, 0, 0);
                }
                return new Step(step + " · " + p.processed() + " von " + total + " Begriffen beurteilt, "
                        + Texts.count(p.written(), "Eintrag", "Einträge"), p.processed(), total);
            }
            if (type == JobType.STATEMENT_ANALYSIS) {
                long skipped = p.processed() - p.written() - p.failed();
                return new Step((phase == null ? "Aussagenanalyse" : phase) + " · " + p.processed() + " von " + total
                        + " Seiten, " + p.written() + " analysiert, " + skipped + " übersprungen",
                        p.processed(), total);
            }
            if (type == JobType.STATEMENT_CHECK) {
                // Wie die Analyse: Seiten ohne aktuelle Analyse oder ohne KI-Freigabe zählen als übersprungen.
                long skipped = p.processed() - p.written() - p.failed();
                return new Step((phase == null ? "Aussagen prüfen" : phase) + " · " + p.processed() + " von " + total
                        + " Seiten, " + p.written() + " geprüft, " + skipped + " übersprungen",
                        p.processed(), total);
            }
            if (type == JobType.VIDEO_TRANSCRIPT && total > 1) {
                // Sekunden Video: wie weit das Transkript reicht, von der Länge des Videos (Angelo 20.11.2026).
                return new Step((phase == null ? Texts.type(type) : phase) + " · "
                        + com.aresstack.spaceharvester.application.presentation.TranscriptSections.clock(
                                p.processed() * 1000) + " von "
                        + com.aresstack.spaceharvester.application.presentation.TranscriptSections.clock(total * 1000),
                        p.processed(), total);
            }
            if (type == JobType.SEARCH_QUALITY && total > 0) {
                return new Step((phase == null ? Texts.type(type) : phase) + " · " + p.processed() + " von " + total
                        + " Fragen" + (p.failed() > 0 ? ", " + p.failed() + " gescheitert" : ""), p.processed(), total);
            }
            if (type.presentation()) {
                // „In PPTX umwandeln“: Folien, Transkriptpakete oder das eine Transkript.
                String unit = type == JobType.TRANSCRIPT_CORRECTION ? " Abschnitten"
                        : type == JobType.VIDEO_TRANSCRIPT ? " Transkript" : " Folien";
                return new Step((phase == null ? Texts.type(type) : phase) + " · " + p.processed() + " von " + total
                        + unit + (p.failed() > 0 ? ", " + p.failed() + " fehlgeschlagen" : ""), p.processed(), total);
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

    /**
     * Die Spalte „Fortschritt“: derselbe Zähler, den Statuszeile und Kachel „Auftrag“ zeigen. Ein Download zählt beim
     * Abrufen die geladenen oder geprüften Seiten und erst beim Exportieren die exportierten (vorher stünde dort
     * 0 von N, obwohl die Statuszeile längst hochzählt); die Zeile kennt keine Phase, aber exportiert ist erst, wenn
     * das Abrufen durch ist. Ein beendeter Download zeigt immer die exportierten Seiten, auch 0 von N, wenn alle
     * fehlten. Alle anderen Arten zählen verarbeitete Seiten.
     */
    static String progressText(JobStatus status) {
        JobProgress p = status.progress();
        boolean fetching = status.type() == JobType.EXPORT && !status.state().terminal() && p.written() == 0;
        long done = status.type() == JobType.EXPORT && !fetching ? p.written() : p.processed();
        long skipped = skipped(status);
        return done + "/" + p.discovered() + (skipped > 0 ? " · " + skipped + " ausgelassen" : "")
                + (p.failed() > 0 ? " · " + p.failed() + " Fehler" : "");
    }

    /**
     * Bewusst Ausgelassenes eines Bilder- oder Anhänge-Auftrags: geprüft, aber weder abgelegt noch fehlgeschlagen
     * (Diagramm-Vorschauen zu gezeichneten draw.io-Quellen, Anhänge außerhalb der Export-Einstellungen). Andere Arten
     * zählen so nicht; dort bleibt es 0.
     */
    static long skipped(JobStatus status) {
        if (status.type() != JobType.ASSETS && status.type() != JobType.ATTACHMENTS) {
            return 0;
        }
        JobProgress p = status.progress();
        return Math.max(0, p.processed() - p.written() - p.failed());
    }

    /** Was die vier Zähler je Auftragsart bedeuten. */
    record Counters(String discovered, String processed, String written, String failed) {

        static Counters of(JobType type) {
            return switch (type) {
                case EXPORT -> new Counters("Seiten (Inventur)", "Geladen oder geprüft", "Exportiert", "Fehlend");
                case ENRICHMENT -> new Counters("Schritte (Seiten × Prozessoren)", "Erledigt", "Neu berechnet",
                        "Fehlgeschlagen");
                case ASSETS -> new Counters("Bildverweise", "Geprüft", "Abgelegt", "Fehlend");
                case ATTACHMENTS -> new Counters("Anhänge", "Geprüft", "Abgelegt", "Fehlend");
                case STATEMENT_ANALYSIS -> new Counters("Seiten", "Erledigt", "Analysiert", "Fehlgeschlagen");
                case STATEMENT_CHECK -> new Counters("Seiten", "Erledigt", "Geprüft", "Fehlgeschlagen");
                case PURGE -> new Counters("Ablagen", "Bearbeitet", "Geleert", "Fehlgeschlagen");
                case KNOWLEDGE_GRAPH_EXPORT -> new Counters("Seiten mit Analyse", "Gelesen", "Exportiert",
                        "Fehlgeschlagen");
                case GLOSSARY -> new Counters("Begriffe für das Modell", "Beurteilt", "Einträge vorgeschlagen",
                        "Fehlgeschlagen");
                case IMPORT -> new Counters("Seiten im Paket", "Bearbeitet", "Übernommen", "Fehlgeschlagen");
                case REREAD -> new Counters("Dokumente", "Geprüft", "Neu gelesen", "Fehlgeschlagen");
                case METADATA_EXPORT, ANALYTICS_EXPORT -> new Counters("Seiten", "Abgefragt", "Mit Daten", "Ohne Antwort");
                case VIDEO_TRANSCRIPT -> new Counters("Sekunden Video", "Transkribiert", "Abgelegt", "Fehlgeschlagen");
                case VIDEO_SLIDES -> new Counters("Folien", "Bearbeitet", "Beschrieben", "Fehlgeschlagen");
                case TRANSCRIPT_CORRECTION -> new Counters("Transkriptpakete", "Bearbeitet", "Korrigiert",
                        "Unkorrigiert");
                case SLIDE_NOTES -> new Counters("Folien", "Bearbeitet", "Kommentiert", "Fehlgeschlagen");
                case PRESENTATION_EXPORT -> new Counters("Folien", "Bearbeitet", "In der Datei", "Ohne Standbild");
                case SEARCH_QUALITY -> new Counters("Fragen", "Gestellt", "Im Chat-Kontext gefunden",
                        "Suche gescheitert");
                default -> new Counters("Gefunden", "Verarbeitet", "Geschrieben", "Fehlgeschlagen");
            };
        }
    }

    /** Zustand eines Auftrags, der auf einen Einmalcode wartet ({@code ExportAssetCollection.SIGN_IN_PHASE}). */
    static final String SIGN_IN = ExportAssetCollection.SIGN_IN_PHASE;
    static final String KEPT = "Dateien im Ausgabeordner und der Seitenbestand bleiben erhalten; der JSON-Datenbestand "
            + "des Downloads verschwindet mit dem Auftrag.";

    /**
     * Übernimmt die drei Grenzen im Core und schreibt sie in die Konfigurationsdatei, damit sie einen Neustart
     * überstehen; beides im Hintergrund ({@link AppContext#parallel}).
     */
    void parallel() {
        if (context.runtime() == null) {
            return;
        }
        int prepare = preparations.value();
        int load = downloads.value();
        int lines = connections.value();
        context.parallel(prepare, load, lines, () -> {
            feedback.success(parallelMessage(prepare, load, lines));
            poll();
        }, feedback::problem);
    }

    /** Die Rückmeldung nach dem Übernehmen der drei Grenzen. */
    static String parallelMessage(int prepare, int load, int lines) {
        if (load == 0) {
            return "Downloads angehalten: Bilder und Anhänge werden nur vorbereitet";
        }
        if (lines == 0) {
            return "Keine Verbindungen: Bilder und Anhänge warten, Texte laden weiter";
        }
        return "Gleichzeitig " + Texts.count(load, "Download", "Downloads") + " mit zusammen "
                + Texts.count(lines, "Verbindung", "Verbindungen") + ", "
                + Texts.count(prepare, "Vorbereitung", "Vorbereitungen");
    }

    private void invalidParallel(String message) {
        feedback.warning("Nicht übernommen", message);
    }

    /** Klappt das Menü am Dreieck auf: rechtsbündig unter dem ▼, wie die übrigen Menüs unter ihrem Knopf. */
    void showMoreJobsMenu() {
        javax.swing.JPopupMenu menu = com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                moreJobsMenu(), moreJobs);
        menu.show(moreJobs, moreJobs.getWidth() - menu.getPreferredSize().width, moreJobs.getHeight());
    }

    /**
     * Das Menü hinter dem Dreieck neben dem Besen: oben „Suchqualität messen …“ (Angelo 21.11.2026, Q1b), Trenner, dann
     * „Alle Aufträge löschen“.
     */
    javax.swing.JPopupMenu moreJobsMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem quality = new javax.swing.JMenuItem(SearchQualityStart.MENU, Glyph.CROSSHAIR);
        // Glyph zeichnet sich gesperrt selbst grau; ohne eigenes Sperr-Symbol ließe Swing nur die Lücke stehen.
        quality.setDisabledIcon(Glyph.CROSSHAIR);
        quality.setFont(Comic.regular(13f));
        quality.setToolTipText(SearchQualityStart.MENU_TIP);
        quality.setEnabled(context.runtime() != null);
        quality.addActionListener(e -> SearchQualityStart.open(root, context, started -> show(started.id()),
                feedback::problem));
        menu.add(quality);
        menu.addSeparator();
        javax.swing.JMenuItem all = new javax.swing.JMenuItem(DELETE_ALL, Glyph.TRASH);
        all.setDisabledIcon(Glyph.TRASH);
        all.setFont(Comic.regular(13f));
        all.setToolTipText("Alle Aufträge samt Diagnose löschen; laufende werden vorher abgebrochen");
        all.setEnabled(!model.rows.isEmpty());
        all.addActionListener(e -> deleteAll());
        menu.add(all);
        return menu;
    }

    /** Abgeschlossen: fertig, gescheitert oder abgebrochen; ein angehaltener lässt sich fortsetzen und bleibt. */
    static boolean finished(JobStatus job) {
        return job.state() == JobState.COMPLETED || job.state() == JobState.FAILED
                || job.state() == JobState.CANCELLED;
    }

    /**
     * Der Besen (Angelo 17.11.2026, wie früher): löscht nach „Wirklich … löschen?“ (Ja/Nein) genau die abgeschlossenen
     * Aufträge, die der Dialog nennt; laufende, wartende und angehaltene bleiben.
     */
    void deleteFinished() {
        AppRuntime runtime = context.runtime();
        List<String> confirmed = model.rows.stream().filter(ActivitiesView::finished).map(JobStatus::id).toList();
        if (runtime == null || confirmed.isEmpty()) {
            return;
        }
        long kept = model.rows.size() - confirmed.size();
        if (!Dialogs.confirm(root, "Abgeschlossene Aufträge löschen?", Texts.count(confirmed.size(),
                "abgeschlossenen Auftrag", "abgeschlossene Aufträge") + " wirklich löschen? Sie (fertig, gescheitert, "
                + "abgebrochen) und ihre Diagnoseereignisse werden dauerhaft gelöscht."
                + (kept == 0 ? "" : " " + Texts.count(kept, "anderer Auftrag bleibt", "andere Aufträge bleiben") + ".")
                + " " + KEPT)) {
            return;
        }
        boolean selectedGone = selectedId != null && confirmed.contains(selectedId);
        Background.run(() -> runtime.workspace().deleteJobs(confirmed), deleted -> {
            if (selectedGone) {
                selectedId = null;
                detail = null;
                table.clearSelection();
                render();
            }
            feedback.success(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht");
            poll();
        }, feedback::problem);
    }

    /**
     * „Alle Aufträge löschen“ im Menü neben dem Besen: löscht alle Aufträge in jedem Zustand; laufende und wartende
     * bricht der Core dafür ab ({@code Workspace#deleteAllJobs}), damit kein Auftrag unsichtbar weiterläuft. Laufen
     * welche, warnt der Dialog wie beim Beenden der App.
     */
    void deleteAll() {
        AppRuntime runtime = context.runtime();
        int count = model.rows.size();
        if (runtime == null || count == 0) {
            return;
        }
        long running = model.rows.stream().filter(job -> !job.state().terminal()).count();
        String question = running == 0
                ? "Wirklich alle Aufträge löschen? " + Texts.count(count, "Auftrag", "Aufträge")
                        + " samt Diagnoseereignissen werden dauerhaft gelöscht. " + KEPT
                : "Wirklich alle Aufträge abbrechen und löschen? " + Texts.count(running, "Auftrag läuft",
                        "Aufträge laufen") + " noch; sie werden vorher abgebrochen, ihr Ergebnis bleibt unbestätigt. "
                        + "Insgesamt werden " + Texts.count(count, "Auftrag", "Aufträge")
                        + " samt Diagnoseereignissen dauerhaft gelöscht. " + KEPT;
        if (!Dialogs.confirm(root, running == 0 ? "Alle Aufträge löschen?" : "Alle Aufträge abbrechen und löschen?",
                question)) {
            return;
        }
        Background.run(() -> runtime.workspace().deleteAllJobs(), deleted -> {
            selectedId = null;
            detail = null;
            table.clearSelection();
            render();
            feedback.success(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht");
            poll();
        }, feedback::problem);
    }

    /** Was „Alle anhalten“ in der Kopfzeile gerade tut (Angelo 11.11.2026). */
    enum GlobalPause {
        /** Etwas wartet oder läuft und lässt sich anhalten: „Alle anhalten“. */
        PAUSE,
        /** Nichts lässt sich mehr anhalten, aber etwas ist angehalten: „Alle fortsetzen“ mit Play-Symbol. */
        RESUME,
        /** Nichts anzuhalten und nichts fortzusetzen: „Alle anhalten“, blass. */
        NONE;

        static GlobalPause of(List<JobStatus> jobs) {
            if (!AllJobs.toPause(jobs).isEmpty()) {
                return PAUSE;
            }
            return AllJobs.toResume(jobs).isEmpty() ? NONE : RESUME;
        }
    }

    /** Stellt die beiden Knöpfe der Kopfzeile auf den Stand aller Aufträge. */
    void updateGlobalButtons(List<JobStatus> jobs) {
        globalPause = GlobalPause.of(jobs);
        boolean resume = globalPause == GlobalPause.RESUME;
        labelGlobalButtons();
        pauseAll.setToolTipText(resume ? RESUME_ALL_TIP : globalPause == GlobalPause.PAUSE ? PAUSE_ALL_TIP
                : PAUSE_ALL_TIP + " · gerade wartet oder läuft nichts, das sich anhalten lässt");
        pauseAll.setEnabled(globalPause != GlobalPause.NONE && !busyAll);
        boolean cancellable = !AllJobs.toCancel(jobs).isEmpty();
        cancelAll.setToolTipText(cancellable ? CANCEL_ALL_TIP : CANCEL_ALL_TIP + " · gerade wartet und läuft nichts");
        cancelAll.setEnabled(cancellable && !busyAll);
    }

    /** Für Tests: was „Alle anhalten“ gerade tut. */
    GlobalPause globalPause() {
        return globalPause;
    }

    /** Die Größe eines Knopfs, der seinen Text wechselt, für den breitesten Text, damit die Kopfzeile nicht springt. */
    private static java.awt.Dimension widest(ComposerButton button, String... texts) {
        String shown = button.getText();
        java.awt.Dimension widest = button.getPreferredSize();
        for (String text : texts) {
            button.setText(text);
            java.awt.Dimension size = button.getPreferredSize();
            widest = new java.awt.Dimension(Math.max(widest.width, size.width), Math.max(widest.height, size.height));
        }
        button.setText(shown);
        return widest;
    }

    /** Schaltet die schmale Kopfzeile ein oder aus ({@link HeadLayout}). */
    private void compactHead(boolean compact) {
        if (compact != compactHead) {
            compactHead = compact;
            labelGlobalButtons();
        }
    }

    /** Symbol, Text und Größe der beiden Knöpfe: mit Text oder, wenn die Zeile zu schmal ist, nur mit Symbol. */
    private void labelGlobalButtons() {
        boolean resume = globalPause == GlobalPause.RESUME;
        String pauseName = resume ? RESUME_ALL : PAUSE_ALL;
        pauseAll.setIcon(resume ? RESUME : PAUSE);
        label(pauseAll, pauseName, pauseAllFull);
        label(cancelAll, CANCEL_ALL, cancelAllFull);
    }

    private void label(ComposerButton button, String name, java.awt.Dimension full) {
        String text = compactHead ? null : name;
        if (!Objects.equals(button.getText(), text)) {
            button.setText(text);
        }
        button.getAccessibleContext().setAccessibleName(name);
        java.awt.Dimension size = compactHead ? COMPACT_BUTTON : full;
        if (!size.equals(button.getPreferredSize())) {
            button.setPreferredSize(size);
            button.revalidate();
        }
    }

    /** Für Tests: ob die Knöpfe der Kopfzeile nur ihr Symbol zeigen. */
    boolean compactHead() {
        return compactHead;
    }

    /**
     * Kopfzeile der Liste (Angelo 11.11.2026): links der Titel, rechts Auf- und Zuklappen, Plätze und Aufräumen, in der
     * Mitte „Alle anhalten“ und „Alle abbrechen“, mittig über der ganzen Zeile. Reicht der Platz dafür nicht, rücken
     * die Knöpfe zwischen Titel und rechte Gruppe; ist es auch dort zu eng für ihre Texte, zeigen sie nur ihr Symbol,
     * und zuletzt gibt der Titel Platz ab, damit beide Knöpfe ganz sichtbar bleiben.
     */
    static final class HeadLayout implements java.awt.LayoutManager {

        /** Mindestabstand der Mitte zum Titel und zur rechten Gruppe, zusätzlich zu deren eigenen Rändern. */
        static final int GAP = 2;

        private final Component west;
        private final Component center;
        private final Component east;
        private final int fullWidth;
        private final Consumer<Boolean> compact;

        /**
         * @param fullWidth Breite der Mitte mit Texten
         * @param compact   schaltet die Mitte auf nur Symbole ({@code true}) oder zurück auf Texte
         */
        HeadLayout(Component west, Component center, Component east, int fullWidth, Consumer<Boolean> compact) {
            this.west = west;
            this.center = center;
            this.east = east;
            this.fullWidth = fullWidth;
            this.compact = compact;
        }

        @Override
        public void layoutContainer(Container parent) {
            java.awt.Insets in = parent.getInsets();
            int left = in.left;
            int right = parent.getWidth() - in.right;
            int top = in.top;
            int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
            int westWidth = west.getPreferredSize().width;
            int eastWidth = east.getPreferredSize().width;
            int space = right - left - westWidth - eastWidth - 2 * GAP;
            compact.accept(space < fullWidth);
            int width = center.getPreferredSize().width;
            if (space < width) {
                westWidth = Math.max(0, westWidth - (width - space));
            }
            west.setBounds(left, top, westWidth, height);
            east.setBounds(Math.max(left, right - eastWidth), top, eastWidth, height);
            int from = left + westWidth + GAP;
            int to = right - eastWidth - GAP;
            int x = Math.max(from, Math.min(to - width, left + (right - left - width) / 2));
            center.setBounds(x, top, Math.max(0, Math.min(width, to - x)), height);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(Container parent) {
            return size(parent, west.getPreferredSize(), fullWidth, east.getPreferredSize());
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(Container parent) {
            return size(parent, west.getMinimumSize(), 0, east.getMinimumSize());
        }

        private java.awt.Dimension size(Container parent, java.awt.Dimension westSize, int centerWidth,
                                        java.awt.Dimension eastSize) {
            java.awt.Insets in = parent.getInsets();
            int height = Math.max(Math.max(westSize.height, eastSize.height), center.getPreferredSize().height);
            return new java.awt.Dimension(in.left + in.right + westSize.width + centerWidth + eastSize.width + 2 * GAP,
                    in.top + in.bottom + height);
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    /**
     * „Alle anhalten“ bzw. „Alle fortsetzen“ (Angelo 11.11.2026), ohne Rückfrage, denn beides lässt sich umkehren.
     * Anhalten erfasst auch die wartenden, damit keiner nachrückt; fertige Aufträge startet Fortsetzen nicht neu.
     */
    void pauseOrResumeAll() {
        AppRuntime runtime = context.runtime();
        GlobalPause mode = globalPause;
        if (runtime == null || busyAll || mode == GlobalPause.NONE) {
            return;
        }
        boolean resume = mode == GlobalPause.RESUME;
        runAll(() -> resume ? runtime.workspace().resumeAllJobs() : runtime.workspace().pauseAllJobs(),
                resume ? "fortgesetzt" : "angehalten");
    }

    /**
     * „Alle abbrechen“ nach „Wirklich alle … abbrechen?“: alle wartenden und laufenden Aufträge, wartende zuerst;
     * angehaltene und beendete bleiben, wie sie sind.
     */
    void cancelAll() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || busyAll) {
            return;
        }
        int count = AllJobs.toCancel(model.all).size();
        if (count == 0 || !Dialogs.confirm(root, "Alle Aufträge abbrechen?", "Wirklich alle wartenden und laufenden "
                + "Aufträge abbrechen? Das betrifft " + Texts.count(count, "Auftrag", "Aufträge") + "; bereits "
                + "Gelesenes bleibt unbestätigt. Angehaltene und beendete Aufträge bleiben, wie sie sind.")) {
            return;
        }
        runAll(() -> runtime.workspace().cancelAllJobs(), "abgebrochen");
    }

    /** Führt eine Aktion für alle im Hintergrund aus; solange warten beide Knöpfe. */
    private void runAll(java.util.concurrent.Callable<AllJobs.Outcome> work, String done) {
        busyAll = true;
        pauseAll.setEnabled(false);
        cancelAll.setEnabled(false);
        Background.run(work, outcome -> {
            busyAll = false;
            String changed = Texts.count(outcome.changed(), "Auftrag", "Aufträge") + " " + done;
            if (outcome.failed() > 0) {
                feedback.warning(changed + ", " + outcome.failed() + " nicht",
                        outcome.problem() == null ? null : outcome.problem().getMessage());
            } else if (outcome.changed() > 0) {
                feedback.success(changed);
            }
            poll();
        }, error -> {
            busyAll = false;
            feedback.problem(error);
            poll();
        });
    }

    /**
     * Geschätzter Stand je Export-Gruppe (der Export mit seinen Bildern und Anhängen), in der gerade etwas läuft; eine
     * Gruppe, die nur wartet, angehalten oder fertig ist, bekommt keinen Balken.
     */
    private Map<String, OverallProgress.Estimate> groupEstimates(List<JobStatus> jobs, Instant now) {
        Map<String, List<JobStatus>> children = children(jobs);
        Map<String, OverallProgress.Estimate> estimates = new HashMap<>();
        for (JobStatus job : jobs) {
            if (job.type() != JobType.EXPORT) {
                continue;
            }
            List<JobStatus> group = new ArrayList<>();
            group.add(job);
            group.addAll(children.getOrDefault(job.id(), List.of()));
            if (OverallProgress.running(group)) {
                estimates.put(job.id(), overall.estimate(group, now, averages::get));
            }
        }
        return estimates;
    }

    /** Legt die Balken auf die Zeilen der Liste, nach jedem Abruf und jedem Auf- oder Zuklappen. */
    private void layoutBands() {
        bands = bands(model.rows, model::child, groupEstimates);
        if (bands.stream().anyMatch(Band::indeterminate)) {
            sweep.start();
        } else {
            sweep.stop();
        }
        table.repaint();
    }

    /** Für Tests: die Balken hinter den Zeilen. */
    List<Band> bands() {
        return bands;
    }

    /** Ein Balken hinter den Zeilen {@code first} bis {@code last} einer Export-Gruppe, in der gerade etwas läuft. */
    record Band(int first, int last, double fraction, boolean indeterminate) {
        boolean covers(int row) {
            return row >= first && row <= last;
        }
    }

    /**
     * Die Balken hinter den Export-Gruppen mit Schätzung (Angelo 11.11.2026): zugeklappt nur hinter der Zeile des
     * Exports, aufgeklappt auch hinter seinen Bildern und Anhängen direkt darunter.
     */
    static List<Band> bands(List<JobStatus> rows, java.util.function.Predicate<JobStatus> child,
                            Map<String, OverallProgress.Estimate> estimates) {
        List<Band> result = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            OverallProgress.Estimate estimate = estimates.get(rows.get(i).id());
            if (estimate == null || child.test(rows.get(i))) {
                continue;
            }
            int last = i;
            while (last + 1 < rows.size() && child.test(rows.get(last + 1))) {
                last++;
            }
            result.add(new Band(i, last, estimate.fraction(), estimate.indeterminate()));
        }
        return List.copyOf(result);
    }

    private Band bandAt(int row) {
        for (Band band : bands) {
            if (band.covers(row)) {
                return band;
            }
        }
        return null;
    }

    /** Ein Schritt des wandernden Balkens einer Gruppe ohne messbaren Fortschritt. */
    private void sweepBands() {
        int width = table.getWidth();
        sweepOffset = (sweepOffset + 6) % Math.max(1, width + width / 4);
        if (table.isShowing()) {
            table.repaint();
        }
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

    private record ExportDetail(JobStatus status, ExportArtifact artifact) {
    }

    /**
     * Die Aufträge in Anzeigereihenfolge (Angelo 11.11.2026): „Bilder beschaffen“ und „Anhänge beschaffen“ stehen als
     * Unterzeilen direkt unter ihrem Export (Verknüpfung {@link ExportAssetCollection#EXPORT_ID}), Bilder vor Anhängen.
     * Eine Gruppe ist aufgeklappt, bis sie in {@code collapsed} steht; dann zeigt sie nur den Export. Folgeaufträge,
     * deren Export nicht mehr in der Liste steht, bleiben eigenständige Zeilen.
     */
    static List<JobStatus> grouped(List<JobStatus> jobs, Set<String> collapsed) {
        Map<String, List<JobStatus>> children = children(jobs);
        List<JobStatus> result = new ArrayList<>(jobs.size());
        for (JobStatus job : jobs) {
            if (parentOf(job, children) != null) {
                continue;
            }
            result.add(job);
            if (!collapsed.contains(job.id())) {
                result.addAll(children.getOrDefault(job.id(), List.of()));
            }
        }
        return result;
    }

    /**
     * Unterzeilen je Oberschritt, nur für Oberschritte, die selbst in {@code jobs} stehen: unter einem Export seine
     * Bilder und Anhänge (Verknüpfung {@link ExportAssetCollection#EXPORT_ID}); unter „PPTX erzeugen“ die übrigen vier
     * Schritte desselben Laufs von „In PPTX umwandeln“ (Angelo 20.11.2026, {@code VideoPresentations.RUN_PARAMETER}) in
     * ihrer Reihenfolge.
     */
    static Map<String, List<JobStatus>> children(List<JobStatus> jobs) {
        Map<String, String> parents = new HashMap<>();
        for (JobStatus job : jobs) {
            String group = groupKey(job);
            if (group != null) {
                parents.put(group, job.id());
            }
        }
        Map<String, List<JobStatus>> children = new HashMap<>();
        for (JobStatus job : jobs) {
            String member = memberKey(job);
            String parent = member == null ? null : parents.get(member);
            if (parent != null) {
                children.computeIfAbsent(parent, id -> new ArrayList<>()).add(job);
            }
        }
        for (List<JobStatus> list : children.values()) {
            list.sort(Comparator.comparingInt(ActivitiesView::stepOrder));
        }
        return children;
    }

    /** Unter welchem Schlüssel ein Oberschritt seine Unterzeilen sammelt; {@code null}, wenn er keiner ist. */
    private static String groupKey(JobStatus job) {
        if (job.type() == JobType.EXPORT) {
            return "export:" + job.id();
        }
        String run = job.parameter(com.aresstack.spaceharvester.application.presentation.VideoPresentations
                .RUN_PARAMETER);
        return job.type() == JobType.PRESENTATION_EXPORT && run != null ? "presentation:" + run : null;
    }

    /** Zu welchem Oberschritt ein Auftrag gehört; {@code null}, wenn er eigenständig ist. */
    private static String memberKey(JobStatus job) {
        if (showsProgress(job)) {
            String exportId = job.parameter(ExportAssetCollection.EXPORT_ID);
            return exportId == null ? null : "export:" + exportId;
        }
        String run = job.parameter(com.aresstack.spaceharvester.application.presentation.VideoPresentations
                .RUN_PARAMETER);
        return job.type().presentation() && job.type() != JobType.PRESENTATION_EXPORT && run != null
                ? "presentation:" + run : null;
    }

    /** Reihenfolge der Unterzeilen: Bilder vor Anhängen, die Schritte von „In PPTX umwandeln“ wie sie laufen. */
    private static int stepOrder(JobStatus job) {
        return switch (job.type()) {
            case VIDEO_TRANSCRIPT -> 0;
            case VIDEO_SLIDES -> 1;
            case TRANSCRIPT_CORRECTION -> 2;
            case SLIDE_NOTES -> 3;
            case ATTACHMENTS -> 5;
            default -> 4;
        };
    }

    private static String parentOf(JobStatus job, Map<String, List<JobStatus>> children) {
        for (Map.Entry<String, List<JobStatus>> group : children.entrySet()) {
            for (JobStatus child : group.getValue()) {
                if (child.id().equals(job.id())) {
                    return group.getKey();
                }
            }
        }
        return null;
    }

    static final class JobsModel extends AbstractTableModel {

        static final int ACTIONS = 5;
        private static final String[] COLUMNS = {"Art", "Quelle / Space", "Zustand", "Fortschritt", "Angelegt", ""};
        private List<JobStatus> rows = new ArrayList<>();
        private List<JobStatus> all = List.of();
        private Map<String, List<JobStatus>> children = Map.of();
        /** Zugeklappte Exporte; neue Gruppen sind aufgeklappt. */
        private final Set<String> collapsed = new HashSet<>();

        void set(List<JobStatus> jobs) {
            all = List.copyOf(jobs);
            children = children(all);
            collapsed.retainAll(children.keySet());
            rows = grouped(all, collapsed);
            fireTableDataChanged();
        }

        /** Ob der Export Unterzeilen hat. */
        boolean group(JobStatus job) {
            return children.containsKey(job.id());
        }

        /** Ob die Zeile eine Unterzeile eines Exports ist. */
        boolean child(JobStatus job) {
            return parentOf(job, children) != null;
        }

        boolean collapsed(JobStatus job) {
            return collapsed.contains(job.id());
        }

        /** Klappt alle Gruppen zu oder auf. */
        void setAll(boolean collapse) {
            collapsed.clear();
            if (collapse) {
                collapsed.addAll(children.keySet());
            }
            rows = grouped(all, collapsed);
            fireTableDataChanged();
        }

        /** Für Tests: die sichtbaren Zeilen. */
        List<JobStatus> visible() {
            return rows;
        }

        /** Klappt die Gruppe des Exports auf oder zu. */
        void toggle(JobStatus job) {
            if (!group(job)) {
                return;
            }
            if (!collapsed.remove(job.id())) {
                collapsed.add(job.id());
            }
            rows = grouped(all, collapsed);
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
                case 0 -> Texts.type(status);
                case 1 -> where(status);
                case 2 -> status;
                case 3 -> progressText(status);
                case 4 -> Texts.time(status.createdAt());
                default -> "";
            };
        }
    }

    /**
     * Die Auftragsliste. Hinter einer Export-Gruppe, in der gerade etwas läuft, liegt derselbe hellblaue Balken wie in
     * der Statuszeile ({@link StatusLine#FILL}) und läuft von ganz links bis ganz rechts durch die Zeile, aufgeklappt
     * durch alle Zeilen der Gruppe (Angelo 11.11.2026). Die Zellen dieser Zeilen sind dafür durchsichtig; die Tabelle
     * zeichnet ihren Hintergrund, auch die gelbe Auswahl, selbst darunter.
     */
    private final class JobsTable extends JTable {

        JobsTable(JobsModel model) {
            super(model);
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component cell = super.prepareRenderer(renderer, row, column);
            if (cell instanceof JComponent component) {
                // Derselbe Renderer zeichnet alle Zeilen: für jede Zelle neu festlegen.
                component.setOpaque(bands == null || bandAt(row) == null);
            }
            return cell;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                paintBands(g);
            } finally {
                g.dispose();
            }
            // Gitter und Zellen darüber; den Hintergrund hat die Tabelle schon gezeichnet.
            Graphics cells = graphics.create();
            try {
                ui.paint(cells, this);
            } finally {
                cells.dispose();
            }
        }

        private void paintBands(Graphics2D g) {
            if (bands == null) {
                return;
            }
            int width = getWidth();
            for (Band band : bands) {
                if (band.last() >= getRowCount()) {
                    continue;
                }
                Rectangle top = getCellRect(band.first(), 0, true);
                Rectangle bottom = getCellRect(band.last(), 0, true);
                for (int row = band.first(); row <= band.last(); row++) {
                    if (isRowSelected(row)) {
                        Rectangle cell = getCellRect(row, 0, true);
                        g.setColor(getSelectionBackground());
                        g.fillRect(0, cell.y, width, cell.height);
                    }
                }
                int height = bottom.y + bottom.height - top.y;
                g.setColor(StatusLine.FILL);
                if (band.indeterminate()) {
                    int block = Math.max(40, width / 4);
                    g.fillRect(sweepOffset - block, top.y, block, height);
                } else {
                    g.fillRect(0, top.y, (int) Math.round(width * band.fraction()), height);
                }
            }
        }
    }

    /**
     * Zustand je Zeile nach dem Zustandsobjekt des Core ({@link Texts#state(JobStatus)}); ein Auftrag, der auf einen
     * Einmalcode wartet, sagt es statt „Läuft“.
     */
    private final class TypeRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JobStatus job = row < model.rows.size() ? model.rows.get(row) : null;
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            // Gezeichnete Symbole statt ▸/▾/└: die Swing-Schrift zeigt diese Zeichen als Kästchen (Angelo 11.11.2026).
            setIcon(job == null ? null : groupIcon(model.group(job), model.collapsed(job), model.child(job)));
            setIconTextGap(4);
            setBorder(BorderFactory.createEmptyBorder(0, job != null && model.child(job) ? 18 : 4, 0, 4));
            setToolTipText(job != null && model.group(job)
                    ? (model.collapsed(job) ? "Bilder und Anhänge zeigen" : "Bilder und Anhänge ausblenden") : null);
            return this;
        }
    }

    /** Pfeil rechts (zugeklappt) bzw. unten (aufgeklappt) für einen Export mit Unterzeilen, Winkel für Unterzeilen. */
    static javax.swing.Icon groupIcon(boolean group, boolean collapsed, boolean child) {
        return group ? (collapsed ? Glyph.EXPAND : Glyph.COLLAPSE) : child ? BRANCH : null;
    }

    /** Winkel einer Unterzeile (wie └), gezeichnet. */
    static final javax.swing.Icon BRANCH = new javax.swing.Icon() {
        @Override
        public void paintIcon(Component c, java.awt.Graphics graphics, int x, int y) {
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(Comic.MUTED);
                g.setStroke(new java.awt.BasicStroke(1.4f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g.draw(new java.awt.geom.Line2D.Double(x + 5, y + 2, x + 5, y + 9));
                g.draw(new java.awt.geom.Line2D.Double(x + 5, y + 9, x + 13, y + 9));
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    };

    private final class StateRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            JobStatus status = (JobStatus) value;
            boolean waiting = waitingForSignIn(status.id());
            super.getTableCellRendererComponent(table, waiting ? SIGN_IN : Texts.state(status), selected, focus, row,
                    column);
            setForeground(selected ? Comic.INK : waiting ? Comic.ORANGE : Comic.tone(Texts.tone(status)));
            return this;
        }
    }

    /** Ob der Auftrag auf eine Anmeldung wartet (ADR 0018). */
    boolean waitingForSignIn(String jobId) {
        AppRuntime runtime = context.runtime();
        return runtime != null && runtime.workspace().signIns().waiting(jobId).isPresent();
    }
}
