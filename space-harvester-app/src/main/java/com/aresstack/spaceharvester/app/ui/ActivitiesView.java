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
import com.aresstack.spaceharvester.application.jobs.JobError;
import com.aresstack.spaceharvester.application.jobs.PauseReason;
import com.aresstack.spaceharvester.app.ui.controls.HelpButton;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.application.background.BackgroundJobs;
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
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

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
    /** Plätze „Vorbereitung“ beim letzten Abruf, für den Grund eines wartenden Auftrags ({@link WaitReason}). */
    private int preparationsNow = 1;
    /** Ein Knopf für alle Aufträge (Angelo 10.11.2026): löscht alles, laufende werden vorher abgebrochen. */
    final IconButton deleteFinished = new IconButton(Glyph.BROOM, DELETE_FINISHED);
    /**
     * Rechts neben dem Besen das Dreieck der Leseansicht (Angelo 17.11.2026); im Menü „Alle Aufträge löschen“, das
     * laufende vorher abbricht, damit nichts unsichtbar weiterläuft.
     */
    final IconButton moreJobs = new IconButton(new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(),
            MORE_JOB_ACTIONS);
    static final String DELETE_FINISHED = "Abgeschlossene Aufträge löschen";
    static final String PARALLEL_PREPARATIONS = "Parallele Vorbereitungen";
    static final String PARALLEL_DOWNLOADS = "Parallele Downloads";
    static final String CONNECTIONS = "Verbindungen";
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
    /** Liste oben, Details unten; die Details stehen nur, solange ein Auftrag gewählt ist. */
    private final javax.swing.JSplitPane split;
    /**
     * Höhe des Detailbereichs unter dem Trenner (Angelo 27.11.2026: „merkt sich die Position nicht dauerhaft“): gilt
     * wieder, wenn die Details sich das nächste Mal öffnen, und bleibt im Application State über einen Neustart hinweg
     * ({@link #DETAIL_HEIGHT_KEY}). Gemerkt wird die Höhe der Details, nicht die Lage des Trenners: So behalten sie ihre
     * Größe, auch wenn die Seitenleiste höher oder niedriger wird.
     */
    private int detailHeight = DEFAULT_DETAIL_HEIGHT;
    /** Schlüssel der Höhe des Detailbereichs im Application State ({@link WindowSizes}). */
    static final String DETAIL_HEIGHT_KEY = "activities.details.height";
    /** Höhe des Detailbereichs, solange keine gemerkt ist. */
    static final int DEFAULT_DETAIL_HEIGHT = 320;
    /** Kleinste Höhe des Detailbereichs, die als gezogene Höhe gilt. */
    static final int MIN_DETAIL_HEIGHT = 80;
    /** Kleinste Höhe der Liste über den Details, damit ein paar Zeilen sichtbar bleiben. */
    static final int MIN_LIST_HEIGHT = 90;
    /** Ob die Höhe des Detailbereichs im Application State bleibt; Tests merken sich nichts. */
    private final boolean rememberDetails;
    /** Ob die Liste fehlerfrei fertige Aufträge selbst löscht ({@link #scheduleCleanup}); Tests nie. */
    private final boolean housekeeping;
    private static final System.Logger LOG = System.getLogger(ActivitiesView.class.getName());
    /** Während die Ansicht den Trenner selbst setzt, ist das keine Wahl des Benutzers. */
    private boolean placingDivider;
    /** Schreibt die gezogene Höhe erst, wenn der Trenner kurz ruht, nicht bei jedem Schritt des Ziehens. */
    private final Timer storeDetailHeight = new Timer(400, e -> WindowSizes.rememberInt(DETAIL_HEIGHT_KEY,
            detailHeight));
    /**
     * Reiter über der Liste (Angelo 21.11.2026, Form vom UX-Manager): „Aufträge“ zeigt die Aufträge des Benutzers,
     * „Hintergrund“ die Hintergrundaufträge ({@link JobType#background()}); jeder Auftrag steht in genau einem.
     */
    private JComponent tools;
    /** „Aufträge“ / „Hintergrund“ als Wippe, mittig in der Werkzeugleiste unten ({@link #tools()}). */
    final com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle tabs =
            new com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle();
    static final String JOBS_TAB = "Aufträge";
    static final String BACKGROUND_TAB = "Hintergrund";
    static final String JOBS_TIP = "Aufträge, die du gestartet hast";
    /** Sehr heller Petrol-Ton hinter der Zeile einer Hintergrundgruppe. */
    static final java.awt.Color BACKGROUND_TONE = mix(Comic.PETROL, AppColors.of("app.ActivitiesView.backgroundTone"), 0.88);
    /** Breite des Petrol-Streifens am linken Rand jeder Hintergrundzeile. */
    static final int BACKGROUND_STRIPE = 4;
    /** Kennung der Zeile einer Hintergrundgruppe, die kein eigener Auftrag ist. */
    static final String GROUP_PREFIX = "background-group:";
    /**
     * Zeile „Abgleich der Quelle“ (Angelo 22.11.2026): je Sammlung zusammen gestarteter Abgleiche
     * ({@link SpaceSyncJobs#GROUP}) eine aufklappbare Zeile über ihren Spaces, vom ersten Space an. Wie eine
     * Hintergrundgruppe kein gespeicherter Auftrag.
     */
    static final String SYNC_GROUP_PREFIX = "sync-group:";
    private static final String TAB_PREFERENCE = "activities.tab";
    /** Hilfetext hinter dem ?-Knopf der Auftragsliste. */
    static final String HELP = "Abgleiche startest du unter Wissensquellen verwalten, Downloads im Explorer links "
            + "(Rechtsklick oder Download-Symbol). Beendete Aufträge bleiben sichtbar, bis die Aufbewahrungsfrist endet "
            + "oder sie gelöscht werden.\n\nIm Reiter „Aufträge“ steht, was du gestartet hast. Im Reiter „Hintergrund“ "
            + "steht, was die App von sich aus anstößt, etwa fehlende Nachbarseiten laden: je Anstoß eine Gruppe mit "
            + "einem Unterauftrag je Space. Hintergrundaufträge haben Nachrang, laufen nach einem Neustart von selbst "
            + "weiter und lassen sich anhalten, fortsetzen und abbrechen. Die Knöpfe „Alle …“, der Besen und „Alle "
            + "löschen“ wirken nur auf den gewählten Reiter.";
    static final String BACKGROUND_TIP = "Hintergrundaufträge: was die App von sich aus anstößt, etwa fehlende "
            + "Seiten nachladen oder Bilder beschreiben; nachrangig, nach einem Neustart fortgesetzt";
    /**
     * Unter dieser Breite ist die Liste schmal (Seitenleiste): „Art“ zeigt nur ihr Symbol (Angelo 22.11.2026), der Name
     * steht dann im Tooltip; „Space“ bleibt schmaler stehen (Angelo 23.11.2026).
     */
    static final int NARROW_BELOW = 640;
    /** Breite der Spalte „Space“ in der schmalen Liste: höchstens, mindestens. */
    static final int NARROW_SPACE_MAX = 120;
    static final int NARROW_SPACE_MIN = 48;
    /** Breite der Spalte „Art“ in der schmalen Liste: Aufklapp-Pfeil und Symbol. */
    static final int ART_ICON_WIDTH = 50;
    /** Alle zuletzt gelesenen Aufträge, beide Reiter. */
    private List<JobStatus> lastJobs = List.of();
    /** Ablage des zuletzt gewählten Reiters, oder {@code null}. */
    private final java.util.prefs.Preferences preferences;
    /** Beim nächsten Zeichnen nach oben: anderer Auftrag gewählt oder Codefeld neu erschienen. */
    private boolean toTop;

    public ActivitiesView(AppContext context) {
        this(context, userPreferences(), true);
    }

    /**
     * @param preferences wo der zuletzt gewählte Reiter bleibt; {@code null} merkt sich nichts (Tests schreiben so nie
     *                    in die Ablage des Benutzers)
     */
    ActivitiesView(AppContext context, java.util.prefs.Preferences preferences) {
        this(context, preferences, false);
    }

    /**
     * @param live die App selbst: merkt sich die Höhe der Details im Application State und räumt fehlerfrei fertige
     *             Aufträge auf; Tests nie
     */
    private ActivitiesView(AppContext context, java.util.prefs.Preferences preferences, boolean live) {
        this.context = context;
        this.preferences = preferences;
        this.rememberDetails = live;
        this.housekeeping = live;
        if (live) {
            // Eine neue Frist gilt sofort, auch für schon fertige Aufträge.
            AppTimeouts.onSave(() -> javax.swing.SwingUtilities.invokeLater(this::cleanupDue));
        }
        if (rememberDetails) {
            detailHeight = Math.max(MIN_DETAIL_HEIGHT, WindowSizes.recallInt(DETAIL_HEIGHT_KEY, DEFAULT_DETAIL_HEIGHT));
        }
        storeDetailHeight.setRepeats(false);
        this.signIn = new SignInSlot(context, requests -> selectedId == null
                ? requests.pending().stream().findFirst() : requests.waiting(selectedId));
        root.setBackground(Comic.SURFACE);
        table.getColumnModel().getColumn(JobsModel.TYPE).setCellRenderer(new TypeRenderer());
        // Der Titel braucht den meisten Platz (UX 21.11.2026); voller Titel im Tooltip.
        table.getColumnModel().getColumn(JobsModel.TYPE).setPreferredWidth(220);
        table.getColumnModel().getColumn(JobsModel.SOURCE).setCellRenderer(new WhereRenderer());
        table.getColumnModel().getColumn(JobsModel.SOURCE).setPreferredWidth(170);
        table.getColumnModel().getColumn(JobsModel.SPACE).setCellRenderer(new WhereRenderer());
        table.getColumnModel().getColumn(JobsModel.SPACE).setPreferredWidth(170);

        // Ein Klick auf die Art eines Exports klappt seine Bilder und Anhänge auf oder zu (Angelo 11.11.2026).
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                int row = table.rowAtPoint(event.getPoint());
                if (row < 0 && javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    deselect();
                    return;
                }
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
        RowActions.install(table, JobsModel.CONTROLS, CONTROL_SLOTS, this::controlActions);
        RowActions.install(table, JobsModel.ACTIONS, SLOTS, this::rowActions);
        JPanel list = new JPanel(new BorderLayout(0, 6));
        list.setBackground(Comic.SURFACE);
        list.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        HelpButton hint = new HelpButton(24, "Aufträge", HELP);
        // Nur Symbole wie der Umschalter der Wissensquellen gegenüber (Angelo 27.11.2026); der Name steht im Tooltip.
        tabs.segment(JOBS_TAB, SwitchIcons.jobs()).segment(BACKGROUND_TAB, SwitchIcons.background());
        tabs.button(JOBS_TAB).setToolTipText(JOBS_TIP);
        tabs.button(BACKGROUND_TAB).setToolTipText(BACKGROUND_TIP);
        String remembered = rememberedTab();
        if (BACKGROUND_TAB.equals(remembered)) {
            tabs.select(BACKGROUND_TAB);
        }
        tabs.onSelect(this::tabChanged);
        // Die Wippe „Aufträge / Hintergrund“ ist zugleich die Überschrift der Liste und steht links in der
        // Kopfzeile, immer einzeilig (Angelo 22.11.2026). GridBagLayout hält sie in ihrer eigenen Höhe mittig.
        JPanel listTitle = new JPanel(new java.awt.GridBagLayout());
        listTitle.setOpaque(false);
        switchSlot.setOpaque(false);
        listTitle.add(switchSlot);
        // Die drei Grenzen der Parallelität stehen im Menü hinter dem Dreieck (Angelo 22.11.2026), damit die
        // Kopfzeile auch in der Seitenleiste Platz hat.
        for (ParallelChoice choice : List.of(preparations, downloads, connections)) {
            choice.ask = current -> Dialogs.askNumber(root, "Benutzerdefiniert", choice.box().getToolTipText(),
                    choice.min(), choice.max(), current);
        }
        expandAll.addActionListener(e -> setAllGroups(false));
        collapseAll.addActionListener(e -> setAllGroups(true));
        enlarge.addActionListener(e -> enlargeAction.run());
        JPanel listTools = Comic.row(deleteFinished, enlarge, moreJobs);
        // „Alle anhalten“ und „Alle abbrechen“ sind Aktionen und stehen mittig in der Werkzeugleiste unten
        // (tools(), Angelo 22.11.2026); oben bleibt Platz für die Wippe.
        pauseAll.addActionListener(e -> pauseOrResumeAll());
        cancelAll.addActionListener(e -> cancelAll());
        pauseAll.setPreferredSize(widest(pauseAll, PAUSE_ALL, RESUME_ALL));
        JPanel global = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 6, 0));
        global.setOpaque(false);
        global.add(pauseAll);
        global.add(cancelAll);
        // Auf- und Zuklappen stehen gleich rechts der Lupe, die beiden Aktionen mittig in der ganzen Leiste.
        JPanel folding = Comic.row(expandAll, collapseAll);
        // Die Hilfe steht unten rechts in der Ecke der Leiste, nicht mehr in der Kopfzeile (Angelo 27.11.2026).
        // Rechts in der Leiste der Umschalter „Aufträge / Hintergrund“ (Angelo 27.11.2026: aus der Kopfzeile nach
        // unten, rechtsbündig), dahinter in der Ecke die Hilfe. Wird es eng, weicht nur die Hilfe.
        JPanel tabsHolder = new JPanel(new java.awt.GridBagLayout());
        tabsHolder.setOpaque(false);
        tabsHolder.add(tabs);
        ToolsLayout toolsLayout = new ToolsLayout(folding, global, tabsHolder, hint);
        // Reicht die Breite auch ohne Hilfe nicht (Leiste an ihrer Mindestbreite), stehen Auf- und Zuklappen im Menü
        // hinter dem Dreieck, wie der Besen der Kopfzeile.
        toolsLayout.onTight(tight -> foldingInMenu = tight);
        JPanel globalRow = new JPanel(toolsLayout);
        globalRow.setOpaque(false);
        globalRow.putClientProperty(View.TOOLS_FILL, Boolean.TRUE);
        globalRow.add(folding);
        globalRow.add(global);
        globalRow.add(tabsHolder);
        globalRow.add(hint);
        tools = globalRow;
        // Dieselbe Kopfleiste wie überall (HeadBar): gleiche Höhe, Linie darunter (Angelo 22.11. und 27.11.2026).
        JPanel listHead = new com.aresstack.spaceharvester.app.ui.controls.HeadBar(null, 10, 6);
        // Reicht die Breite nicht (Leiste an ihrer Mindestbreite), weicht der Besen; „Fertige löschen“ steht
        // dann im Menü hinter dem Dreieck.
        int fullTools = listTools.getPreferredSize().width;
        deleteFinished.setVisible(false);
        int saved = fullTools - listTools.getPreferredSize().width;
        deleteFinished.setVisible(true);
        listHead.setLayout(new HeadLayout(listTitle, listTools, saved,
                tight -> deleteFinished.setVisible(!tight)));
        listHead.add(listTitle);
        listHead.add(listTools);
        updateGlobalButtons(List.of());
        model.addTableModelListener(e -> layoutBands());
        // Schmal (Seitenleiste) stehen die Aktionen der gewählten Zeile in der Fußzeile neben der Lupe statt in
        // jeder Zeile, ohne zweite Kopfzeile (Angelo 22.11.2026); „Alle anhalten“ und „Alle abbrechen“ rücken dann
        // ins Menü hinter dem Dreieck. Die Knöpfe stehen immer da, ohne Auswahl blass, damit nichts springt.
        selectedRow.setOpaque(false);
        for (int i = 0; i < SELECTED_SLOTS; i++) {
            int slot = i;
            IconButton button = new IconButton(idleSelectedIcon(i), NO_JOB_SELECTED);
            button.setName("activities.selected." + i);
            button.addActionListener(e -> selectedRuns.get(slot).run());
            selectedButtons.add(button);
            selectedRuns.add(() -> { });
            selectedRow.add(button);
        }
        this.global = global;
        model.addTableModelListener(e -> javax.swing.SwingUtilities.invokeLater(this::updateSelectedActions));
        table.getSelectionModel().addListSelectionListener(e -> updateSelectedActions());
        updateSelectedActions();
        list.add(listHead, BorderLayout.NORTH);
        javax.swing.JScrollPane listScroll = Comic.scroll(table);
        // Ein Klick in die leere Fläche unter den Zeilen hebt die Auswahl auf (Angelo 22.11.2026).
        listScroll.getViewport().addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    deselect();
                }
            }
        });
        JPanel listBody = new JPanel(new BorderLayout());
        listBody.setOpaque(false);
        listBody.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 12));
        listBody.add(listScroll, BorderLayout.CENTER);
        list.add(listBody, BorderLayout.CENTER);

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
        // Codefeld und Meldung erscheinen auch ohne gewählten Auftrag (Anmeldung auf Abruf, „Nicht übernommen“):
        // Der Detailbereich öffnet sich dann für sie und schließt sich mit ihnen wieder.
        java.awt.event.ComponentAdapter opens = new java.awt.event.ComponentAdapter() {
            @Override
            public void componentShown(java.awt.event.ComponentEvent event) {
                syncDetails();
            }

            @Override
            public void componentHidden(java.awt.event.ComponentEvent event) {
                syncDetails();
            }
        };
        signIn.addComponentListener(opens);
        feedback.addComponentListener(opens);

        // Eine Kachel über die volle Breite; Ergebnis und Ausgabe stehen unter Inhalte › Export.
        JComponent tiles = stack(Comic.plate("Auftrag", Comic.PETROL, facts));
        JPanel details = new JPanel(new BorderLayout(0, 8));
        details.setOpaque(false);
        details.setBorder(BorderFactory.createEmptyBorder(4, 12, 12, 12));
        details.add(head, BorderLayout.NORTH);
        details.add(tiles, BorderLayout.CENTER);
        this.details = details;

        detailScroll = Comic.column(details);
        split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, list, detailScroll);
        split.setBackground(Comic.SURFACE);
        // Derselbe Trenner wie neben der Wissensquellen-Liste, nur liegend.
        ComicDivider.install(split, Comic.PALETTE);
        // Wächst oder schrumpft die Seitenleiste, ändert sich die Liste; die Details behalten ihre Höhe.
        split.setResizeWeight(1.0);
        split.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> dividerMoved());
        // Ohne gewählten Auftrag gibt es keinen Detailbereich: Er öffnet sich erst mit einer Auswahl.
        showDetails(false);
        split.setContinuousLayout(true);
        root.add(split, BorderLayout.CENTER);
        // Die Aufträge stehen in der rechten Seitenleiste (Angelo 22.11.2026) und bringen ihre Leiste mit der Lupe
        // selbst mit: Suche in der Liste, Auf- und Zuklappen, „Alle anhalten“ und „Alle abbrechen“.
        findBar = new FindBar(() -> split);
        findBar.tools(tools);
        root.add(findBar, BorderLayout.SOUTH);
        root.setMinimumSize(new java.awt.Dimension(
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH, 0));
        root.setPreferredSize(new java.awt.Dimension(
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar.DEFAULT_WIDTH, 0));

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

    // --- Seitenleiste -------------------------------------------------------------------------------------------

    static final String NO_JOB_SELECTED = "Erst einen Auftrag in der Liste wählen";
    /** Diagnose, Inhalte und die vier Zustandsplätze. */
    static final int SELECTED_SLOTS = 2 + Slot.values().length;
    /** Die Leiste mit Lupe unter der Liste. */
    private final FindBar findBar;
    /** Mitte der Fußzeile: breit „Alle anhalten“ und „Alle abbrechen“, schmal die Aktionen der gewählten Zeile. */
    private final JPanel global;
    private final JPanel selectedRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
    private final List<IconButton> selectedButtons = new ArrayList<>();
    private final List<Runnable> selectedRuns = new ArrayList<>();
    /** „Groß anzeigen“ neben dem Dreieck; was es tut, bestimmt das Hauptfenster. */
    final IconButton enlarge = new IconButton(
            com.aresstack.spaceharvester.app.ui.content.ChatSidebar.enlargeIcon(false),
            com.aresstack.spaceharvester.app.ui.content.ChatSidebar.ENLARGE);
    private final JPanel switchSlot = new JPanel(new java.awt.GridBagLayout());
    /** Ob Auf- und Zuklappen gerade im Menü hinter dem Dreieck stehen, weil die Fußleiste zu schmal ist. */
    private boolean foldingInMenu;

    /** Platz für den Umschalter der rechten Leiste (Chat | Analysen | Aufträge); das Hauptfenster stellt ihn hinein. */
    JComponent switchSlot() {
        return switchSlot;
    }

    private Runnable enlargeAction = () -> { };
    private Runnable collapseAction = () -> { };

    /** Was „Groß anzeigen“ und „Seitenleiste einklappen“ tun; das bestimmt das Hauptfenster. */
    void sidebar(Runnable enlargeAction, Runnable collapseAction) {
        this.enlargeAction = Objects.requireNonNull(enlargeAction, "enlargeAction");
        this.collapseAction = Objects.requireNonNull(collapseAction, "collapseAction");
    }

    /** Zeigt am Knopf, ob die Leiste gerade groß ist: Symbol und Tooltip wechseln. */
    void large(boolean large) {
        enlarge.setIcon(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.enlargeIcon(large));
        String tip = large ? com.aresstack.spaceharvester.app.ui.content.ChatSidebar.SHRINK
                : com.aresstack.spaceharvester.app.ui.content.ChatSidebar.ENLARGE;
        enlarge.setToolTipText(tip);
        enlarge.getAccessibleContext().setAccessibleName(tip);
    }

    /** Zugeklappt ist der Inhalt unsichtbar, damit die Tabulatortaste nicht in verdeckte Felder läuft. */
    void sidebarShown(boolean shown) {
        for (Component child : root.getComponents()) {
            child.setVisible(shown);
        }
    }

    FindBar findBar() {
        return findBar;
    }

    /** Die Aktionen der gewählten Zeile in der Fußzeile der schmalen Liste (für Tests). */
    JComponent selectedRow() {
        return selectedRow;
    }

    List<IconButton> selectedButtons() {
        return selectedButtons;
    }

    private static javax.swing.Icon idleSelectedIcon(int slot) {
        return switch (slot) {
            case 0 -> DIAGNOSIS;
            case 1 -> CONTENT;
            case 2 -> RETRY_FAILED;
            case 3 -> RESUME;
            case 4 -> FORCE_REFRESH;
            default -> Glyph.TRASH;
        };
    }

    /** Legt auf die Knöpfe der Fußzeile, was die gewählte Zeile anbietet; ohne Auswahl sind sie blass. */
    private void updateSelectedActions() {
        int row = table.getSelectedRow();
        showSelectedActions(row < 0 || row >= model.rows.size() ? -1 : table.convertRowIndexToModel(row));
    }

    /** Legt die Aktionen der Zeile {@code index} auf die Knöpfe der Fußzeile; {@code -1} sperrt sie. */
    void showSelectedActions(int index) {
        List<RowActions.Action> actions = index < 0 ? List.of() : fullRowActions(index);
        int first = PROGRESS_SLOTS + 1;
        for (int i = 0; i < SELECTED_SLOTS; i++) {
            IconButton button = selectedButtons.get(i);
            RowActions.Action action = first + i < actions.size() ? actions.get(first + i) : null;
            javax.swing.Icon icon = action == null ? idleSelectedIcon(i) : action.icon();
            boolean enabled = action != null && action.enabled();
            button.setIcon(enabled ? icon : new Faded(icon));
            button.setDisabledIcon(new Faded(icon));
            button.setEnabled(enabled);
            String tip = action == null ? NO_JOB_SELECTED : action.tooltip();
            button.setToolTipText(tip);
            button.getAccessibleContext().setAccessibleName(tip);
            selectedRuns.set(i, enabled ? action.run() : () -> { });
        }
    }

    /** Ein Symbol blass, wie die gesperrten Plätze der Zeilen ({@link RowActions}). */
    private record Faded(javax.swing.Icon icon) implements javax.swing.Icon {
        @Override
        public void paintIcon(Component c, java.awt.Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setComposite(java.awt.AlphaComposite.getInstance(java.awt.AlphaComposite.SRC_OVER, 0.3f));
                g.setColor(Comic.INK);
                icon.paintIcon(c, g, x, y);
            } finally {
                g.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return icon.getIconWidth();
        }

        @Override
        public int getIconHeight() {
            return icon.getIconHeight();
        }
    }

    /** Die Wippe „Aufträge / Hintergrund“ für die Mitte der Werkzeugleiste. */
    @Override
    public JComponent tools() {
        return tools;
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

    static final String MODEL_NOT_RELEASED_LABEL = "Ohne KI-Freigabe";
    static final String MODEL_NO_MODEL_LABEL = "Ohne Modell";
    static final String MODEL_COSTS_LABEL = "KI-Analysator";

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
        // KI-Analysator (Fahrplan A2/A3): je Grund eine gelbe Zahl, die Kosten nur hier im Overlay des Auftrags.
        if (code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.MODEL_NOT_RELEASED)) {
            return new Note(MODEL_NOT_RELEASED_LABEL, message, true);
        }
        if (code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.MODEL_NO_MODEL)) {
            return new Note(MODEL_NO_MODEL_LABEL, message, true);
        }
        if (code.equals(com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.MODEL_COSTS)) {
            return new Note(MODEL_COSTS_LABEL, message, false);
        }
        if (code.startsWith(com.aresstack.spaceharvester.application.glossary.GlossaryClassification.MODEL_SKIPPED
                + ":") || code.startsWith(com.aresstack.spaceharvester.application.glossary.GlossaryClassification
                .ANALYSIS_MISSING + ":")) {
            return new Note(SKIPPED_LABEL, message, true);
        }
        // Fahrplan B1: ohne Vision-Modell, mit Einmalcode oder an der Obergrenze bewusst ausgelassen (gelb).
        if (code.startsWith("IMAGES_SKIPPED:")
                || code.equals(com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions
                .LIMIT_REACHED)) {
            return new Note(IMAGES_LABEL, message, true);
        }
        // Ein ausgefallenes Vision-Modell ist ein Fehler, kein bewusstes Auslassen.
        if (code.equals(com.aresstack.spaceharvester.application.images.AutomaticImageDescriptions.MODEL_FAILED)) {
            return new Note(IMAGES_LABEL, message, false);
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
        Background.run(() -> read(runtime.workspace(), id), data -> {
            readDone();
            @SuppressWarnings("unchecked")
            List<JobStatus> jobs = (List<JobStatus>) data[0];
            for (JobType type : new JobType[] {JobType.ASSETS, JobType.ATTACHMENTS}) {
                averages.put(type, runtime.workspace().averageDownload(type));
            }
            parallelNow = runtime.workspace().parallelDownloads();
            preparationsNow = runtime.workspace().parallelPreparations();
            // Gesamtstand aller Aufträge für die Statuszeile, Balken hinter laufenden Exporten (Angelo 11.11.2026).
            Instant now = Instant.now();
            overall.observe(jobs, now, averages::get);
            groupEstimates = groupEstimates(jobs, now);
            lastJobs = List.copyOf(jobs);
            scheduleCleanup(runtime);
            followSelectedTab(id);
            model.set(visible(jobs, backgroundTab()));
            updateBackgroundChip(jobs);
            context.events().publish(overall.event(jobs, now, averages::get, parallelNow));
            updateGlobalButtons(tabJobs(jobs, backgroundTab()));
            deleteFinished.setEnabled(tabJobs(jobs, backgroundTab()).stream().anyMatch(ActivitiesView::finished));
            downloads.refresh(runtime.workspace().parallelDownloads());
            preparations.refresh(runtime.workspace().parallelPreparations());
            connections.refresh(runtime.workspace().downloadConnections());
            // Ein Abruf für einen inzwischen abgewählten Auftrag zeigt nichts mehr an. Das Detail steht vor dem
            // Markieren der Zeile fest, damit deren Auswahlmeldung keinen weiteren Abruf auslöst.
            boolean shown = java.util.Objects.equals(id, selectedId);
            if (shown) {
                detail = id == null ? null : synthetic(id) ? groupRow(id) : data[1];
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
            // Der Detailbereich öffnet sich sofort mit der Auswahl, nicht erst, wenn der Auftrag gelesen ist.
            render();
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

    /** Hebt die Auswahl auf; der Detailbereich schließt sich. */
    private void deselect() {
        if (selectedId == null && table.getSelectedRow() < 0) {
            return;
        }
        selectedId = null;
        detail = null;
        table.clearSelection();
        // Die Meldung gehörte zum abgewählten Auftrag; sie hielte den Detailbereich sonst offen.
        feedback.clear();
        render();
        // Ein fälliger fertiger Auftrag blieb nur, solange er gewählt war.
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            scheduleCleanup(runtime);
        }
    }

    /** Der Detailbereich ist offen, solange er etwas zeigt: einen Auftrag, das Codefeld oder eine Meldung. */
    private void syncDetails() {
        boolean job = selectedId != null || currentStatus().isPresent();
        // Ohne Auftrag steht dort nur das Codefeld oder die Meldung, kein „Kein Auftrag gewählt“.
        title.setVisible(job);
        showDetails(job || signIn.isVisible() || feedback.isVisible());
    }

    /**
     * Öffnet oder schließt den Detailbereich unter der Liste. Geschlossen gehört die ganze Fläche der Liste, der
     * Trenner verschwindet; geöffnet steht er wieder dort, wo er zuletzt stand, und die gewählte Zeile bleibt sichtbar.
     */
    private void showDetails(boolean show) {
        if (detailScroll.isVisible() == show) {
            return;
        }
        detailScroll.setVisible(show);
        split.setDividerSize(show ? ComicDivider.THICKNESS : 0);
        if (show) {
            placeDivider();
        }
        split.revalidate();
        split.repaint();
        if (show) {
            int row = table.getSelectedRow();
            if (row >= 0) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (row < table.getRowCount()) {
                        table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    }
                });
            }
        }
    }

    /** Setzt den Trenner so, dass die Details ihre gemerkte Höhe haben; der Liste bleiben ein paar Zeilen. */
    private void placeDivider() {
        int room = split.getHeight() - split.getDividerSize();
        if (room <= 0) {
            // Noch nicht ausgelegt: Sobald die Seitenleiste ihre Höhe hat, kommt der Trenner an seinen Platz.
            split.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (split.getHeight() > 0) {
                        split.removeComponentListener(this);
                        if (detailScroll.isVisible()) {
                            placeDivider();
                        }
                    }
                }
            });
            return;
        }
        placingDivider = true;
        try {
            split.setDividerLocation(Math.max(Math.min(MIN_LIST_HEIGHT, room), room - detailHeight));
        } finally {
            placingDivider = false;
        }
    }

    /** Hat der Benutzer den Trenner gezogen, gilt die neue Höhe der Details, auch nach einem Neustart. */
    private void dividerMoved() {
        if (placingDivider || !detailScroll.isVisible() || split.getHeight() <= 0) {
            return;
        }
        int height = split.getHeight() - split.getDividerSize() - split.getDividerLocation();
        if (height < MIN_DETAIL_HEIGHT || height == detailHeight) {
            return;
        }
        detailHeight = height;
        if (rememberDetails) {
            storeDetailHeight.restart();
        }
    }

    /** Die Höhe, die der Detailbereich beim nächsten Öffnen bekommt (für Tests). */
    int detailHeight() {
        return detailHeight;
    }

    /** Ob der Detailbereich offen ist (für Tests). */
    boolean detailsShown() {
        return detailScroll.isVisible();
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
        syncDetails();
        if (current.isEmpty()) {
            title.setText(selectedId != null ? "Auftrag wird geladen …"
                    : model.rows.isEmpty() ? "Noch keine Aufträge" : "Kein Auftrag gewählt");
            details.revalidate();
            details.repaint();
            updateButtons();
            return;
        }
        JobStatus status = current.get();
        // Die Klammer heißt, was sie tut; ihre Quellen stehen darunter unter „Quellen“.
        title.setText(backgroundGroup(status) ? Texts.type(status) : Texts.type(status) + " " + where(status));
        JobProgress p = status.progress();
        Counters counters = Counters.of(Texts.kind(status));
        boolean waiting = waitingForSignIn(status.id());
        facts.add("Zustand", waiting ? SIGN_IN : Texts.state(status),
                        waiting ? Comic.ORANGE : Comic.tone(Texts.tone(status)))
                .add(backgroundGroup(status) ? "Quellen" : "Quelle", sourceName(status))
                .add(com.aresstack.spaceharvester.application.jobs.JobArea.of(status) == null ? "Space" : "Bereich",
                        area(status).isEmpty() ? null : area(status))
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
        if (com.aresstack.spaceharvester.application.background.FillJobs.KIND.equals(
                status.parameter(BackgroundJobs.KIND))) {
            // Fundstelle 12: Die Hintergrundfüllung zeigt auch, wie viele Seiten im Space noch warten.
            // Beendete und angehaltene Teile laden nichts: 0 (Abnahme #1087).
            boolean active = status.state() == JobState.RUNNING || status.state() == JobState.QUEUED;
            facts.add("Wartend", String.valueOf(active ? Math.max(0, p.discovered() - p.processed()) : 0));
        }
        if (skipped(status) > 0) {
            facts.add("Bewusst ausgelassen", String.valueOf(skipped(status)));
        }
        for (Note note : stageNotes) {
            facts.add(note.label(), note.text(), note.skipped() ? Comic.ORANGE : Comic.INK);
        }
        facts.add("Auftrags-ID", status.id());
        outputShown = outputDirectory(status,
                context.exportDirectory());
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
            renderTerminal(status);
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
            renderTerminal(status);
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

    private void renderTerminal(JobStatus status) {
        switch (status.state()) {
            case COMPLETED -> {
                // Kein Band „… fertig“ (Angelo 27.11.2026): Zustand und Zahlen stehen schon im Balken der Zeile.
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
            case PODCAST_EXPORT -> {
                String file = status.parameters().get(
                        com.aresstack.spaceharvester.app.podcast.PodcastExport.FILE_PARAMETER);
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

    /** Ob die Zeile die Klammer über Hintergrundteilen ist ({@value #GROUP_PREFIX}…). */
    static boolean backgroundGroup(JobStatus status) {
        return status.id() != null && status.id().startsWith(GROUP_PREFIX);
    }

    /** Quelle / Space eines Auftrags, bei einem Seiten-Download dazu „ab“ seiner Startseite. */
    static String where(JobStatus status) {
        if (status.type() == JobType.SEARCH_QUALITY) {
            // Nur der Name des Testbestands, nie eine Frage (Q1).
            String suite = com.aresstack.spaceharvester.application.quality.SearchQualityRuns.suiteName(status);
            return suite.isEmpty() ? "Testbestand" : "Testbestand „" + suite + "“";
        }
        String area = area(status);
        String where = area.isEmpty() ? status.sourceId() : status.sourceId() + " / " + area;
        if (status.rootPageId() == null) {
            return where;
        }
        // D4.2: „Fachbegriffe ermitteln“ über eine Seite samt Unterseiten.
        return status.type() == JobType.GLOSSARY ? where + " · „" + startPage(status) + "“ samt Unterseiten"
                : where + " ab „" + startPage(status) + "“";
    }

    /**
     * Spalte „Quelle“: die Wissensquelle des Auftrags, bei der Suchqualität der Testbestand. Die Klammer über
     * Hintergrundteilen sagt, was sie tut (Angelo 24.11.2026: „in der Klammer muss stehen, was es ist“); ihre Quellen
     * stehen in den Details.
     */
    static String source(JobStatus status) {
        if (status.type() == JobType.SEARCH_QUALITY) {
            return where(status);
        }
        if (backgroundGroup(status)) {
            return Texts.type(status);
        }
        return status.sourceId() == null ? "" : sourceName(status);
    }

    /**
     * Die Quelle in Worten: „*“ (alle Quellen) heißt bei der Buchfunktion „Favoriten“, ihr Ordner steht unter
     * „Bereich“ (Angelo 27.11.2026: Quelle „*“, Space „–“ sagte nichts); sonst die Kennung der Quelle.
     */
    static String sourceName(JobStatus status) {
        String id = status.sourceId();
        if (!com.aresstack.spaceharvester.application.jobs.Jobs.ALL_SOURCES.equals(id)) {
            return id;
        }
        if (status.type() == JobType.PODCAST_EXPORT) {
            return "Podcast";
        }
        return status.type() == JobType.BOOK || status.type() == JobType.RESEARCH ? "Favoriten" : "alle Quellen";
    }

    /** Spalte „Space“: der Space und, wenn der Auftrag nur einen Teil betrifft, seine Startseite; sonst leer. */
    static String space(JobStatus status) {
        if (status.type() == JobType.SEARCH_QUALITY) {
            return "";
        }
        String space = area(status);
        if (status.rootPageId() == null) {
            return space;
        }
        String page = status.type() == JobType.GLOSSARY ? "„" + startPage(status) + "“ samt Unterseiten"
                : "ab „" + startPage(status) + "“";
        return space.isEmpty() ? page : space + (status.type() == JobType.GLOSSARY ? " · " : " ") + page;
    }

    /**
     * Der Space eines Auftrags oder, wenn er keinen als Partition trägt, sein Bereich in Worten ({@link com.aresstack.spaceharvester.application.jobs.JobArea}, Angelo
     * 27.11.2026: die Spalte „Space“ war bei „Zusammenfassen“ und Podcast immer leer); sonst leer.
     */
    static String area(JobStatus status) {
        String described = com.aresstack.spaceharvester.application.jobs.JobArea.of(status);
        if (described != null && status.rootPageId() == null) {
            return described;
        }
        return status.partitionKey() == null || status.partitionKey().isBlank() ? "" : status.partitionKey();
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
        again.setVisible(current.map(s -> s.type() == JobType.SYNC && !groupRow(s)).orElse(false));
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
     * Fortschrittsbalken über {@link #PROGRESS_SLOTS} Plätze, ein freier Platz als Abstand, Diagnose, Inhalte, dann
     * Stop ⇄ Neu erzwingen. Wiederholen, Anhalten ⇄ Fortsetzen und Löschen stehen in eigener Spalte links vor dem Balken
     * ({@link #ROW_CONTROLS}). Was ein Zustand nicht anbietet, steht blass da; jeder Platz ist immer belegt.
     */
    static final int SLOTS = PROGRESS_SLOTS + 1 + 2 + 1;
    /** Die Zustandsknöpfe links neben dem Balken, in jeder Zeile, breit wie schmal. */
    static final List<Slot> ROW_CONTROLS = List.of(Slot.RETRY, Slot.PLAY_PAUSE, Slot.DELETE);
    /** Plätze der Spalte {@link JobsModel#CONTROLS}. */
    static final int CONTROL_SLOTS = 3;

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
        List<RowActions.Action> full = fullRowActions(index);
        if (full.size() <= PROGRESS_SLOTS) {
            return full;
        }
        // Schmal (Seitenleiste) bleibt hinter den Knöpfen nur der Balken; Diagnose, Inhalte und Stop stehen in der
        // Fußzeile.
        if (narrow) {
            return full.subList(0, PROGRESS_SLOTS);
        }
        // Breit: Balken, Abstand, Diagnose, Inhalte, Stop ⇄ Neu erzwingen; die übrigen Zustandsknöpfe stehen schon
        // links neben dem Balken ({@link #controlActions}).
        List<RowActions.Action> wide = new ArrayList<>(full.subList(0, PROGRESS_SLOTS + 3));
        wide.add(full.get(PROGRESS_SLOTS + 3 + Slot.STOP_FORCE.ordinal()));
        return wide;
    }

    /**
     * Die Knöpfe jeder Zeile links neben dem Balken (Angelo 27.11.2026: „brauche diese 3 Buttons Retry, Play und Auftrag
     * löschen bereits in der Zeile … links neben dem Fortschrittsbalken als eigene Spalte“): auch in der schmalen
     * Seitenleiste erreichbar, ohne die Zeile erst zu wählen. Belegt wie die Zustandsplätze ({@link #stateActions}).
     */
    private List<RowActions.Action> controlActions(int index) {
        if (index < 0 || index >= model.rows.size()) {
            return List.of();
        }
        return controls(stateActions(model.rows.get(index)));
    }

    /** Aus den Zustandsplätzen einer Zeile die {@link #ROW_CONTROLS}, in dieser Reihenfolge. */
    static List<RowActions.Action> controls(List<RowActions.Action> stateActions) {
        List<RowActions.Action> controls = new ArrayList<>(ROW_CONTROLS.size());
        for (Slot slot : ROW_CONTROLS) {
            controls.add(stateActions.get(slot.ordinal()));
        }
        return controls;
    }

    private List<RowActions.Action> fullRowActions(int index) {
        if (index < 0 || index >= model.rows.size()) {
            return List.of();
        }
        JobStatus job = model.rows.get(index);
        List<RowActions.Action> actions = new ArrayList<>();
        actions.addAll(progressSlots(job, remaining(job, averages.get(job.type()), parallelNow),
                waitingForSignIn(job.id()) ? SIGN_IN : stateText(job), groupRow(job) ? null
                        : WaitReason.of(job, lastJobs, preparationsNow).orElse(null)));
        actions.add(RowActions.EMPTY);
        boolean failed = job.state() == JobState.FAILED;
        boolean group = groupRow(job);
        actions.add(new RowActions.Action(DIAGNOSIS, group ? "Diagnose je Unterauftrag (aufklappen)"
                : failed ? "Fehler diagnostizieren" : "Diagnose ansehen", !group,
                () -> context.navigator().showDiagnosis(job.id())));
        boolean space = !group && job.sourceId() != null && job.partitionKey() != null;
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
        Counters counters = Counters.of(Texts.kind(job));
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
     * Der Balken der Liste (Angelo 22.11.2026): Er ersetzt die Spalten „Zustand“, „Fortschritt“ und „Angelegt“. In
     * seiner Mitte steht „erledigt/gefunden“, solange nichts gefunden ist der Zustand; im Tooltip der Zustand, die
     * Zahlen, die Restzeit und wann der Auftrag angelegt wurde.
     *
     * @param state der Zustand in Worten, etwa „Läuft“ oder „Fertig, fehlerfrei“
     */
    static List<RowActions.Action> progressSlots(JobStatus job, java.time.Duration remaining, String state) {
        return progressSlots(job, remaining, state, null);
    }

    /**
     * Wie {@link #progressSlots(JobStatus, java.time.Duration, String)}, bei einem wartenden Auftrag mit dem Grund im
     * Tooltip ({@link WaitReason}, Angelo 23.11.2026: „man weiß nicht wirklich warum“).
     *
     * @param waiting worauf der Auftrag wartet, oder {@code null}
     */
    static List<RowActions.Action> progressSlots(JobStatus job, java.time.Duration remaining, String state,
                                                 String waiting) {
        List<RowActions.Action> plain = progressSlots(job, remaining);
        JobProgress p = job.progress();
        boolean counted = p.discovered() > 0;
        String label = barLabel(job, state);
        String tip = state + (waiting == null || waiting.isBlank() ? "" : ": " + waiting)
                + (counted ? " · " + plain.get(0).tooltip() : "") + " · angelegt " + Texts.time(job.createdAt());
        List<RowActions.Action> slots = new ArrayList<>(plain);
        slots.set(0, new RowActions.Action(new ProgressIcon(PROGRESS_SLOTS * RowActions.SLOT - 8, p.discovered(),
                p.processed(), p.failed(), skipped(job), label, counted ? null : Comic.tone(Texts.tone(job))), tip,
                true, () -> { }));
        return slots;
    }

    /**
     * Die Schrift in der Mitte des Balkens, für alle Arten gleich (Angelo 23.11.2026: „bei der Höchstzahl stehen
     * bleiben, dann aber nicht fertig anzeigen … komplett uneinheitlich“): solange er offen ist „erledigt/gefunden“,
     * beendet der Zustand davor, etwa „Fertig · 1034/1034“; ohne Gefundenes der Zustand allein, etwa „Fertig,
     * fehlerfrei“. Passt beides nicht in den Balken, bleibt der Zustand ({@link ProgressIcon#fit}).
     */
    static String barLabel(JobStatus job, String state) {
        JobProgress p = job.progress();
        if (p.discovered() <= 0) {
            return state;
        }
        String counts = done(job) + "/" + p.discovered();
        return job.state().terminal() ? Texts.state(job.state()) + LABEL_SEPARATOR + counts : counts;
    }

    /** Trennt im Balken Zustand und Zahlen eines beendeten Auftrags. */
    static final String LABEL_SEPARATOR = " · ";

    /** Der Zustand für den Tooltip des Balkens; ein Lauf ohne Fehlschlag heißt „Fertig, fehlerfrei“. */
    static String stateText(JobStatus job) {
        String state = Texts.state(job);
        return job.state() == JobState.COMPLETED && job.progress().failed() == 0
                && state.equals(Texts.state(JobState.COMPLETED)) ? state + ", fehlerfrei" : state;
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
    record ProgressIcon(int width, long total, long done, long failed, long skipped, String label,
                        java.awt.Color ink) implements javax.swing.Icon {

        /** @param label Schrift in der Mitte; dunkel auf der leeren Spur, hell auf der Füllung */
        ProgressIcon(int width, long total, long done, long failed, long skipped, String label) {
            this(width, total, done, failed, skipped, label, null);
        }

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
                    String label = fit(this.label, fm::stringWidth, width - 6);
                    int tx = x + (width - fm.stringWidth(label)) / 2;
                    int ty = y + (h - fm.getHeight()) / 2 + fm.getAscent();
                    java.awt.Shape clip = g.getClip();
                    g.clipRect(x, y, filled, h);
                    g.setColor(AppColors.of("app.ActivitiesView.paintIcon"));
                    g.drawString(label, tx, ty);
                    g.setClip(clip);
                    g.clipRect(x + filled, y, width - filled, h);
                    g.setColor(ink == null ? Comic.INK : ink);
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

        /**
         * Was in den Balken passt: die ganze Schrift oder, bei „Zustand · Zahlen“, der Zustand allein (die Zahlen
         * stehen im Tooltip); sonst die Zahlen.
         */
        static String fit(String label, java.util.function.ToIntFunction<String> measure, int room) {
            if (label == null || measure.applyAsInt(label) <= room) {
                return label;
            }
            int cut = label.indexOf(LABEL_SEPARATOR);
            return cut < 0 ? label : label.substring(0, cut);
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
            case RETRY_FAILED -> job.type() == JobType.BACKGROUND
                    ? "Nur Fehler neu versuchen · das Gescheiterte noch einmal, im selben Eintrag"
                    : "Nur Fehler neu versuchen · die gescheiterten Seiten gezielt nachladen, "
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
                if (groupRow(job)) {
                    if (!Dialogs.confirm(root, groupKind(job) + " abbrechen?", groupName(job)
                            + " wirklich abbrechen? Bereits Geladenes bleibt erhalten.")) {
                        return;
                    }
                } else if (!Dialogs.confirm(root, "Auftrag abbrechen?", Texts.type(job) + " von "
                        + job.partitionKey() + " wirklich abbrechen? Bereits Gelesenes bleibt unbestätigt.")) {
                    return;
                }
            }
            case DELETE -> {
                if (groupRow(job)) {
                    if (!Dialogs.confirm(root, groupKind(job) + " löschen?", groupName(job) + " wirklich löschen?"
                            + (job.state().terminal() ? "" : " Laufende Teilaufträge werden dafür abgebrochen.")
                            + " Die Teilaufträge und ihre Diagnoseereignisse werden dauerhaft gelöscht. " + KEPT)) {
                        return;
                    }
                } else if (!Dialogs.confirm(root, "Auftrag löschen?", Texts.type(job) + " von " + job.partitionKey()
                        + " wirklich löschen?" + (job.state().terminal() ? "" : " Er wird dafür abgebrochen.")
                        + " Der Auftrag und seine Diagnoseereignisse werden dauerhaft gelöscht. " + KEPT)) {
                    return;
                }
            }
            default -> { }
        }
        if (groupRow(job)) {
            boolean sync = syncGroupRow(job);
            String groupId = job.id().substring((sync ? SYNC_GROUP_PREFIX : GROUP_PREFIX).length());
            Background.run(() -> sync ? runtime.workspace().performSyncGroupAction(groupId, action)
                    : runtime.workspace().performBackgroundGroupAction(groupId, action), outcome -> {
                if (action == JobAction.DELETE && job.id().equals(selectedId)) {
                    selectedId = null;
                    detail = null;
                    table.clearSelection();
                    render();
                }
                if (outcome.failed() > 0) {
                    feedback.warning(Texts.count(outcome.changed(), "Unterauftrag", "Unteraufträge") + " erreicht, "
                            + outcome.failed() + " nicht", outcome.problem() == null ? null
                            : outcome.problem() instanceof com.aresstack.spaceharvester.application.background
                            .BackgroundPartOpenException ? "Läuft schon. " + com.aresstack.spaceharvester.application
                            .background.BackgroundPartOpenException.REASON : outcome.problem().getMessage());
                }
                poll();
            }, feedback::problem);
            return;
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
        }, error -> {
            if (error instanceof com.aresstack.spaceharvester.application.background.NothingToRetryException) {
                // Kein Fehler: Das Gescheiterte ist schon wiederholt oder weggefallen.
                feedback.warning("Nichts zu wiederholen",
                        "Für diesen Auftrag ist nichts Gescheitertes mehr offen.");
            } else if (error instanceof com.aresstack.spaceharvester.application.background.BackgroundPartOpenException) {
                // Abnahme #1052: Ein anderer Teil für diesen Space erledigt die Arbeit mit, das ist kein Fehler.
                feedback.warning("Läuft schon",
                        com.aresstack.spaceharvester.application.background.BackgroundPartOpenException.REASON);
            } else {
                feedback.problem(error);
            }
        });
    }

    /** Weckt das Aufräumen der Liste, wenn der nächste fehlerfrei fertige Auftrag fällig ist; einmalig, kein Takt. */
    private final Timer cleanup = new Timer(0, e -> cleanupDue());
    /** Fällige Aufträge, die das Aufräumen wegen eines Vermerks mit Auslassung behält ({@link #withoutOmissions}). */
    private final Set<String> withOmissions = new HashSet<>();

    private void cleanupDue() {
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            scheduleCleanup(runtime);
        }
    }

    /**
     * Räumt die Auftragsliste auf (Angelo 27.11.2026: „jeder Auftrag, der erfolgreich durchgelaufen ist, also ohne
     * irgendwelche Mängel, entfernt sich automatisch nach 5 Minuten“): Fällige löscht derselbe Weg wie der Besen
     * ({@code Workspace#deleteJobs}), ohne Rückfrage; für den nächsten fälligen steht ein einmaliger Wecker. Die Frist
     * steht unter Einstellungen › Allgemein ({@link AppTimeouts.Value#FINISHED_JOBS}), 0 schaltet es ab.
     */
    private void scheduleCleanup(AppRuntime runtime) {
        cleanup.stop();
        if (!housekeeping) {
            return;
        }
        java.time.Duration keep = AppTimeouts.shared().get(AppTimeouts.Value.FINISHED_JOBS);
        if (keep.isZero()) {
            return;
        }
        Instant now = Instant.now();
        List<String> due = new ArrayList<>();
        Instant next = null;
        for (JobStatus job : expendable(lastJobs, selectedId)) {
            if (withOmissions.contains(job.id())) {
                continue;
            }
            Instant at = job.finishedAt().plus(keep);
            if (!at.isAfter(now)) {
                due.add(job.id());
            } else if (next == null || at.isBefore(next)) {
                next = at;
            }
        }
        if (!due.isEmpty()) {
            Background.run(() -> {
                List<String> clean = withoutOmissions(runtime.workspace(), due);
                runtime.workspace().deleteJobs(clean);
                return clean;
            }, clean -> {
                // Mit Auslassung bleibt ein Auftrag; das Aufräumen fragt ihn nicht wieder.
                due.stream().filter(id -> !clean.contains(id)).forEach(withOmissions::add);
                poll();
            },
                    error -> LOG.log(System.Logger.Level.WARNING, "Aufräumen der Auftragsliste gescheitert ({0})",
                            error.getClass().getName()));
            return;
        }
        if (next != null) {
            long millis = java.time.Duration.between(now, next).toMillis() + 1;
            cleanup.setInitialDelay((int) Math.min(Integer.MAX_VALUE, Math.max(1, millis)));
            cleanup.setRepeats(false);
            cleanup.start();
        }
    }

    /**
     * Die Aufträge, die das Aufräumen löschen darf: fertig ohne Fehlschlag, ohne Unvollständiges und ohne bewusst
     * Ausgelassenes. Es bleiben Downloads (ihr Datenbestand verschwindet mit dem Auftrag, {@link #KEPT}), der gewählte
     * Auftrag, solange seine Details offen sind, und Vorgänger, auf die ein offener Auftrag noch wartet.
     */
    static List<JobStatus> expendable(List<JobStatus> jobs, String selectedId) {
        Set<String> awaited = new HashSet<>();
        for (JobStatus job : jobs) {
            if (job.state().terminal()) {
                continue;
            }
            // Beide Arten, auf Vorgänger zu warten: Hintergrundteile ({@code Jobs.AFTER}) und Folgeaufträge
            // ({@code Jobs.AWAITS}); das Löschen eines solchen Vorgängers löschte den wartenden Folgeauftrag mit.
            String after = job.parameter(com.aresstack.spaceharvester.application.jobs.Jobs.AFTER);
            if (after != null) {
                awaited.addAll(java.util.Arrays.asList(after.split(",")));
            }
            String awaits = job.parameter(com.aresstack.spaceharvester.application.jobs.Jobs.AWAITS);
            if (awaits != null) {
                awaited.add(awaits);
            }
        }
        Set<String> shown = selected(jobs, selectedId);
        return jobs.stream()
                .filter(job -> job.id() != null && !synthetic(job.id()) && !shown.contains(job.id()))
                .filter(job -> job.state() == JobState.COMPLETED && job.finishedAt() != null)
                .filter(job -> job.progress().failed() == 0 && !job.incomplete() && skipped(job) == 0)
                .filter(job -> job.type() != JobType.EXPORT && !awaited.contains(job.id()))
                .toList();
    }

    /**
     * Die Aufträge, deren Details gerade offen sind: der gewählte oder, bei einer gewählten Gruppenzeile, alle Teile
     * dieser Gruppe; sie bleiben, bis die Auswahl wechselt.
     */
    static Set<String> selected(List<JobStatus> jobs, String selectedId) {
        if (selectedId == null) {
            return Set.of();
        }
        if (!synthetic(selectedId)) {
            return Set.of(selectedId);
        }
        boolean sync = selectedId.startsWith(SYNC_GROUP_PREFIX);
        String group = selectedId.substring((sync ? SYNC_GROUP_PREFIX : GROUP_PREFIX).length());
        String key = sync ? SpaceSyncJobs.GROUP : BackgroundJobs.GROUP;
        Set<String> members = new HashSet<>();
        for (JobStatus job : jobs) {
            if (group.equals(job.parameter(key))) {
                members.add(job.id());
            }
        }
        return members;
    }

    /**
     * Ohne Aufträge, die einen Schritt bewusst ausgelassen haben (Vermerk mit Auslassung, etwa „Fachbegriffe
     * ermitteln“ ohne Modell): Das ist ein Mangel, ihre Diagnose bleibt. Liest die Ereignisse nur der fälligen.
     */
    private static List<String> withoutOmissions(Workspace workspace, List<String> due) {
        List<String> kept = new ArrayList<>();
        for (String id : due) {
            try {
                if (notes(workspace.events(id)).stream().noneMatch(Note::skipped)) {
                    kept.add(id);
                }
            } catch (RuntimeException gone) {
                // Inzwischen gelöscht: nichts zu tun.
            }
        }
        return kept;
    }

    /** Für Tests und Bilder: zeigt diese Aufträge, als kämen sie vom Core. */
    void showJobsForTest(List<JobStatus> jobs) {
        lastJobs = List.copyOf(jobs);
        model.set(visible(lastJobs, backgroundTab()));
        updateBackgroundChip(lastJobs);
        updateGlobalButtons(tabJobs(lastJobs, backgroundTab()));
        reselect();
    }

    /** Für Tests: die sichtbaren Zeilen der Liste. */
    List<JobStatus> rowsForTest() {
        return model.visible();
    }

    /** Ob der Reiter „Hintergrund“ gewählt ist. */
    boolean backgroundTab() {
        return BACKGROUND_TAB.equals(tabs.selected());
    }

    /** Reiterwechsel: die Liste zeigt die Aufträge des Reiters, die Auswahl gilt nur, wenn sie dort steht. */
    private void tabChanged(String tab) {
        rememberTab(tab);
        boolean background = BACKGROUND_TAB.equals(tab);
        model.set(visible(lastJobs, background));
        updateGlobalButtons(tabJobs(lastJobs, background));
        if (selectedId != null && model.rows.stream().noneMatch(row -> row.id().equals(selectedId))) {
            selectedId = null;
            detail = null;
            table.clearSelection();
            render();
        }
        reselect();
    }

    /** Steht der gewählte Auftrag (etwa nach {@link #show}) im anderen Reiter, wechselt die Ansicht dorthin. */
    private void followSelectedTab(String id) {
        if (id == null || !Objects.equals(id, selectedId)) {
            return;
        }
        boolean background = id.startsWith(GROUP_PREFIX) || lastJobs.stream()
                .anyMatch(job -> job.id().equals(id) && inBackground(job));
        boolean known = background || lastJobs.stream().anyMatch(job -> job.id().equals(id));
        if (known && background != backgroundTab()) {
            tabs.select(background ? BACKGROUND_TAB : JOBS_TAB);
        }
    }

    /** Zahl der laufenden Hintergrundaufträge am Reiter; ohne Laufendes kein Chip. */
    private void updateBackgroundChip(List<JobStatus> jobs) {
        // Nach Lebenszyklus: was gerade anhält oder abbricht, läuft nicht mehr.
        int running = (int) jobs.stream().filter(job -> inBackground(job)
                && job.state() == JobState.RUNNING && !job.cancelRequested()).count();
        tabs.count(BACKGROUND_TAB, running, Comic.PETROL, Texts.count(running, "Hintergrundauftrag läuft",
                "Hintergrundaufträge laufen"), BACKGROUND_TIP);
    }

    /** Ein Hinweistext der Kopfknöpfe, im Reiter „Hintergrund“ auf Hintergrundaufträge bezogen. */
    private String forTab(String tip) {
        return backgroundTab() ? tip.replaceFirst("Aufträge", "Hintergrundaufträge") : tip;
    }

    /** Die Aufträge eines Reiters ohne Gruppenzeilen. */
    static List<JobStatus> tabJobs(List<JobStatus> jobs, boolean background) {
        return jobs.stream().filter(job -> inBackground(job) == background).toList();
    }

    /** Ob ein Auftrag im Reiter „Hintergrund“ steht ({@link Workspace#inBackgroundTab}). */
    static boolean inBackground(JobStatus job) {
        return Workspace.inBackgroundTab(job);
    }

    /**
     * Was ein Reiter zeigt: „Aufträge“ alle übrigen, „Hintergrund“ je Gruppe mit mehreren Teilen eine Zeile
     * ({@link #groupRows}) mit ihren Unteraufträgen darunter. Ein Teil allein steht ohne Klammer (Angelo 22.11.2026:
     * „Hintergrundfüllung“ aufgeklappt wieder nur „Hintergrundfüllung“). Neueste zuerst, die Unteraufträge folgen.
     */
    static List<JobStatus> visible(List<JobStatus> jobs, boolean background) {
        List<JobStatus> own = tabJobs(jobs, background);
        if (!background) {
            return withSyncGroups(own);
        }
        List<JobStatus> rows = groupRows(own);
        Set<String> grouped = new java.util.HashSet<>();
        rows.forEach(row -> grouped.add(row.parameter(BackgroundJobs.GROUP)));
        List<JobStatus> top = new ArrayList<>(rows);
        List<JobStatus> members = new ArrayList<>();
        for (JobStatus job : own) {
            String group = job.parameter(BackgroundJobs.GROUP);
            (group != null && grouped.contains(group) ? members : top).add(job);
        }
        top.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        List<JobStatus> result = new ArrayList<>(top);
        result.addAll(members);
        return result;
    }

    /** Ob die Zeile eine Gruppe ist (Hintergrund oder „Abgleich der Quelle“), kein eigener Auftrag. */
    static boolean groupRow(JobStatus job) {
        return synthetic(job.id());
    }

    /** Ob die Zeile „Abgleich der Quelle“ ist ({@value #SYNC_GROUP_PREFIX}…). */
    static boolean syncGroupRow(JobStatus job) {
        return job.id().startsWith(SYNC_GROUP_PREFIX);
    }

    /** Ob die ID zu einer Gruppenzeile gehört; sie geht nie an den Core. */
    static boolean synthetic(String id) {
        return id.startsWith(GROUP_PREFIX) || id.startsWith(SYNC_GROUP_PREFIX);
    }

    /** Wie Rückfragen eine Gruppe nennen. */
    private static String groupKind(JobStatus row) {
        return syncGroupRow(row) ? "Abgleich" : "Hintergrundauftrag";
    }

    /** „Nachbarseiten laden“ mit 3 Teilaufträgen: Name und Größe einer Gruppe für Rückfragen. */
    private String groupName(JobStatus row) {
        boolean sync = syncGroupRow(row);
        String groupId = row.id().substring((sync ? SYNC_GROUP_PREFIX : GROUP_PREFIX).length());
        String key = sync ? SpaceSyncJobs.GROUP : BackgroundJobs.GROUP;
        long parts = lastJobs.stream().filter(job -> groupId.equals(job.parameter(key))).count();
        return "„" + Texts.type(row) + "“ mit " + Texts.count(parts, "Teilauftrag", "Teilaufträgen");
    }

    /** Die Gruppenzeile aus der zuletzt gelesenen Liste, oder {@code null}. */
    private JobStatus groupRow(String id) {
        List<JobStatus> rows = new ArrayList<>(groupRows(tabJobs(lastJobs, true)));
        rows.addAll(syncGroupRows(tabJobs(lastJobs, false)));
        return rows.stream().filter(row -> row.id().equals(id)).findFirst().orElse(null);
    }

    /**
     * Die Aufträge mit je einer Zeile „Abgleich der Quelle“ vor dem ersten Abgleich ihrer Sammlung
     * ({@link SpaceSyncJobs#GROUP}). Die Zeile steht da, sobald der erste Space gestartet ist, nicht erst, wenn alle
     * laufen; ihre Abgleiche sortiert {@link #grouped} darunter.
     */
    static List<JobStatus> withSyncGroups(List<JobStatus> jobs) {
        Map<String, JobStatus> rows = new HashMap<>();
        for (JobStatus row : syncGroupRows(jobs)) {
            rows.put(row.id(), row);
        }
        if (rows.isEmpty()) {
            return jobs;
        }
        List<JobStatus> result = new ArrayList<>(jobs.size() + rows.size());
        java.util.Set<String> placed = new java.util.HashSet<>();
        for (JobStatus job : jobs) {
            String group = job.type() == JobType.SYNC ? job.parameter(SpaceSyncJobs.GROUP) : null;
            if (group != null && placed.add(SYNC_GROUP_PREFIX + group)) {
                result.add(rows.get(SYNC_GROUP_PREFIX + group));
            }
            result.add(job);
        }
        return result;
    }

    /**
     * Je Sammlung zusammen gestarteter Abgleiche eine Zeile, gezählt wie eine Hintergrundgruppe ({@link #groupStatus});
     * auch für einen einzigen Space, der schon läuft, während die übrigen noch starten.
     */
    static List<JobStatus> syncGroupRows(List<JobStatus> jobs) {
        Map<String, List<JobStatus>> byGroup = new java.util.LinkedHashMap<>();
        for (JobStatus job : jobs) {
            String group = job.type() == JobType.SYNC && !groupRow(job) ? job.parameter(SpaceSyncJobs.GROUP) : null;
            if (group != null) {
                byGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(job);
            }
        }
        List<JobStatus> rows = new ArrayList<>();
        byGroup.forEach((group, parts) -> rows.add(groupStatus(SYNC_GROUP_PREFIX + group, JobType.SYNC,
                new String[] {SpaceSyncJobs.GROUP, JobLifecycleState.SYNC_MODE}, parts)));
        return rows;
    }

    /**
     * Je Hintergrundgruppe mit mehr als einem Teil eine Zeile (ADR 0025 Nachtrag): die Gruppe ist kein Auftrag, ihr
     * Zustand folgt aus den Unteraufträgen (läuft, wenn einer läuft; wartet, wenn einer wartet; sonst angehalten,
     * gescheitert, abgebrochen, fertig), die Zähler sind ihre Summe. Neueste Gruppe zuerst. Eine Gruppe aus nur einem
     * Teil bekommt keine Zeile: Die Klammer wiederholte nur ihn (Angelo 22.11.2026).
     */
    static List<JobStatus> groupRows(List<JobStatus> background) {
        Map<String, List<JobStatus>> byGroup = new java.util.LinkedHashMap<>();
        for (JobStatus job : background) {
            String group = job.parameter(BackgroundJobs.GROUP);
            if (group != null) {
                byGroup.computeIfAbsent(group, key -> new ArrayList<>()).add(job);
            }
        }
        List<JobStatus> rows = new ArrayList<>();
        byGroup.forEach((group, parts) -> {
            if (parts.size() > 1) {
                rows.add(groupStatus(GROUP_PREFIX + group, JobType.BACKGROUND,
                        new String[] {BackgroundJobs.GROUP, BackgroundJobs.TITLE, BackgroundJobs.KIND}, parts));
            }
        });
        rows.sort(Comparator.comparing(JobStatus::createdAt).reversed());
        return rows;
    }

    private static JobStatus groupStatus(String id, JobType type, String[] keys, List<JobStatus> parts) {
        JobState state = JobState.COMPLETED;
        for (JobState candidate : new JobState[] {JobState.RUNNING, JobState.QUEUED, JobState.PAUSED,
                JobState.FAILED, JobState.CANCELLED}) {
            if (parts.stream().anyMatch(part -> part.state() == candidate)) {
                state = candidate;
                break;
            }
        }
        long discovered = 0;
        long processed = 0;
        long written = 0;
        long failed = 0;
        for (JobStatus part : parts) {
            discovered += part.progress().discovered();
            processed += part.progress().processed();
            written += part.progress().written();
            failed += part.progress().failed();
        }
        JobStatus first = parts.stream().min(Comparator.comparing(JobStatus::createdAt)).orElseThrow();
        java.util.Set<String> sources = new java.util.TreeSet<>();
        parts.forEach(part -> sources.add(part.sourceId()));
        Instant started = parts.stream().map(JobStatus::startedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        Instant finished = state.terminal() ? parts.stream().map(JobStatus::finishedAt).filter(Objects::nonNull)
                .max(Comparator.naturalOrder()).orElse(null) : null;
        Map<String, String> parameters = new HashMap<>();
        for (String key : keys) {
            String value = first.parameter(key);
            if (value != null) {
                parameters.put(key, value);
            }
        }
        // Ein echter Fehler geht vor „Unterbrochen“: der zeigt, dass Fortsetzen allein nicht reicht.
        JobError error = state == JobState.FAILED ? parts.stream().map(JobStatus::error).filter(Objects::nonNull)
                .min(Comparator.comparing(e -> JobLifecycleState.INTERRUPTED.equals(e.code()))).orElse(null) : null;
        // Angefordertes Anhalten oder Abbrechen gilt für die Gruppe, wenn es für jeden noch tätigen Teil gilt.
        List<JobStatus> active = parts.stream().filter(part -> part.state() == JobState.RUNNING
                || part.state() == JobState.QUEUED).toList();
        boolean cancelRequested = !active.isEmpty() && active.stream().allMatch(JobStatus::cancelRequested);
        boolean pauseRequested = cancelRequested && active.stream().allMatch(JobStatus::pauseRequested);
        // Angehalten: ein Grund des Systems (Verbindung, Anmeldung) geht vor dem des Benutzers.
        PauseReason pauseReason = state != JobState.PAUSED ? null : parts.stream()
                .filter(part -> part.state() == JobState.PAUSED).map(JobStatus::pauseReason).filter(Objects::nonNull)
                .min(Comparator.comparing(reason -> reason == PauseReason.USER)).orElse(PauseReason.USER);
        boolean incomplete = parts.stream().anyMatch(part -> part.incomplete() || part.progress().failed() > 0);
        return new JobStatus(id, type, String.join(", ", sources), null,
                first.owner(), state, new JobProgress(discovered, processed, written, failed), error,
                cancelRequested, first.createdAt(), started, finished, null, null, parameters, incomplete,
                pauseRequested, pauseReason);
    }

    /** Mischt zwei Farben; {@code weight} ist der Anteil von {@code b}. */
    static java.awt.Color mix(java.awt.Color a, java.awt.Color b, double weight) {
        return new java.awt.Color((int) Math.round(a.getRed() * (1 - weight) + b.getRed() * weight),
                (int) Math.round(a.getGreen() * (1 - weight) + b.getGreen() * weight),
                (int) Math.round(a.getBlue() * (1 - weight) + b.getBlue() * weight));
    }

    private static java.util.prefs.Preferences userPreferences() {
        try {
            return java.util.prefs.Preferences.userNodeForPackage(ActivitiesView.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Der zuletzt gewählte Reiter dieses Benutzers; ohne Ablage „Aufträge“. */
    private String rememberedTab() {
        try {
            return preferences == null ? JOBS_TAB : preferences.get(TAB_PREFERENCE, JOBS_TAB);
        } catch (RuntimeException e) {
            return JOBS_TAB;
        }
    }

    private void rememberTab(String tab) {
        try {
            if (preferences != null) {
                preferences.put(TAB_PREFERENCE, tab);
            }
        } catch (RuntimeException e) {
            // Nur eine Bequemlichkeit: ohne Ablage beginnt die Ansicht beim nächsten Start mit „Aufträge“.
        }
    }

    /** Für Tests. */
    Feedback feedbackForTest() {
        return feedback;
    }

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
     * fehlten. Alle anderen Arten zählen verarbeitete Seiten ohne die gescheiterten (Angelo 22.11.2026: ein Fehler
     * soll nicht als „398/398“ erscheinen); die stehen dahinter.
     */
    static String progressText(JobStatus status) {
        JobProgress p = status.progress();
        // Gescheitertes zählt nicht als erledigt, aber nur, wo beide Zähler dieselbe Einheit zählen (Codex #1103): Eine
        // Anreicherung zählt Abschnitte als erledigt, Fehlschläge aber je Prozessor.
        long skipped = skipped(status);
        return done(status) + "/" + p.discovered() + (skipped > 0 ? " · " + skipped + " ausgelassen" : "")
                + (p.failed() > 0 ? " · " + p.failed() + " Fehler" : "");
    }

    /** Wie viel erledigt ist, in der Einheit von „gefunden“ ({@link #progressText}). */
    static long done(JobStatus status) {
        JobProgress p = status.progress();
        boolean fetching = status.type() == JobType.EXPORT && !status.state().terminal() && p.written() == 0;
        // Gescheitertes zählt nicht als erledigt, aber nur, wo beide Zähler dieselbe Einheit zählen (Codex #1103): Eine
        // Anreicherung zählt Abschnitte als erledigt, Fehlschläge aber je Prozessor.
        boolean sameUnit = status.type() != JobType.EXPORT && status.type() != JobType.ENRICHMENT;
        return status.type() == JobType.EXPORT && !fetching ? p.written()
                : Math.max(0, p.processed() - (sameUnit ? p.failed() : 0));
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
                case UPDATE_PODCAST -> new Counters("Themen", "Gesprochen", "Fertig", "Abschnitte ohne Audio");
                case READING_PREFETCH -> new Counters("Seiten", "Erledigt", "Vorgeladen", "Fehlgeschlagen");
                case DOCUMENT_ANALYSIS -> new Counters("Dokumente", "Gelesen", "Analysiert", "Fehlgeschlagen");
                case RESEARCH -> new Counters("Schritte", "Erledigt", "Im Korb", "Fehlgeschlagen");
                case BOOK -> new Counters("Schritte", "Erledigt", "Abschnitte", "Fehlgeschlagen");
                case PODCAST_EXPORT -> new Counters("Abschnitte", "Gelesen", "In der Datei", "Fehlgeschlagen");
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
     * „Alle Aufträge löschen“, Trenner und die drei Grenzen der Parallelität als Untermenüs
     * ({@link ParallelChoice#menu}).
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
        if (!deleteFinished.isVisible()) {
            javax.swing.JMenuItem finished = new javax.swing.JMenuItem(DELETE_FINISHED, Glyph.BROOM);
            finished.setDisabledIcon(Glyph.BROOM);
            finished.setFont(Comic.regular(13f));
            finished.setEnabled(deleteFinished.isEnabled());
            finished.addActionListener(e -> deleteFinished());
            menu.add(finished);
        }
        javax.swing.JMenuItem all = new javax.swing.JMenuItem(DELETE_ALL, Glyph.TRASH);
        all.setDisabledIcon(Glyph.TRASH);
        all.setFont(Comic.regular(13f));
        all.setToolTipText(forTab("Alle Aufträge samt Diagnose löschen; laufende werden vorher abgebrochen"));
        all.setEnabled(!model.rows.isEmpty());
        all.addActionListener(e -> deleteAll());
        menu.add(all);
        menu.addSeparator();
        menu.add(preparations.menu(PARALLEL_PREPARATIONS));
        menu.add(downloads.menu(PARALLEL_DOWNLOADS));
        menu.add(connections.menu(CONNECTIONS));
        if (foldingInMenu) {
            menu.addSeparator();
            for (IconButton button : List.of(expandAll, collapseAll)) {
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(button.getToolTipText(), button.getIcon());
                item.setFont(Comic.regular(13f));
                item.addActionListener(e -> button.doClick(0));
                menu.add(item);
            }
        }
        if (narrow) {
            menu.addSeparator();
            for (javax.swing.AbstractButton button : List.of(pauseAll, cancelAll)) {
                javax.swing.JMenuItem item = new javax.swing.JMenuItem(button.getText());
                item.setFont(Comic.regular(13f));
                item.setEnabled(button.isEnabled());
                item.setToolTipText(button.getToolTipText());
                item.addActionListener(e -> button.doClick(0));
                menu.add(item);
            }
        }
        menu.addSeparator();
        javax.swing.JMenuItem collapse = new javax.swing.JMenuItem(
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar.COLLAPSE,
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar.collapseIcon());
        collapse.setFont(Comic.regular(13f));
        collapse.addActionListener(e -> collapseAction.run());
        menu.add(collapse);
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
        // Nur echte Aufträge des Reiters: Gruppenzeilen sind bloß Darstellung und zählen nicht mit.
        List<JobStatus> own = tabJobs(lastJobs, backgroundTab());
        // Ein offenes Löschen der Daten einer Quelle (gescheitert, wiederholt sich) bleibt: sonst fiele die Sperre und
        // die alten Daten blieben liegen (Fundstelle 22).
        List<String> withdrawing = runtime == null ? List.of() : own.stream().filter(ActivitiesView::finished)
                .filter(job -> openWithdrawal(runtime, job)).map(JobStatus::id).toList();
        List<String> confirmed = own.stream().filter(ActivitiesView::finished)
                .filter(job -> !withdrawing.contains(job.id())).map(JobStatus::id).toList();
        if (runtime != null && confirmed.isEmpty() && !withdrawing.isEmpty()) {
            feedback.warning(WITHDRAWAL_KEPT, WITHDRAWAL_KEPT_TEXT);
            return;
        }
        if (runtime == null || confirmed.isEmpty()) {
            return;
        }
        long kept = own.size() - confirmed.size();
        if (!Dialogs.confirm(root, "Abgeschlossene Aufträge löschen?", Texts.count(confirmed.size(),
                "abgeschlossenen Auftrag", "abgeschlossene Aufträge") + " wirklich löschen? Sie (fertig, gescheitert, "
                + "abgebrochen) und ihre Diagnoseereignisse werden dauerhaft gelöscht."
                + (kept == 0 ? "" : " " + Texts.count(kept, "anderer Auftrag bleibt", "andere Aufträge bleiben") + ".")
                + " " + KEPT)) {
            return;
        }
        boolean selectedGone = selectedId != null && (confirmed.contains(selectedId)
                || synthetic(selectedId));
        Background.run(() -> runtime.workspace().deleteJobs(confirmed), deleted -> {
            if (selectedGone) {
                selectedId = null;
                detail = null;
                table.clearSelection();
                render();
            }
            if (withdrawing.isEmpty()) {
                feedback.success(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht");
            } else {
                feedback.warning(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht; " + WITHDRAWAL_KEPT,
                        WITHDRAWAL_KEPT_TEXT);
            }
            poll();
        }, feedback::problem);
    }

    /** Gelb, wenn der Besen ein offenes Löschen der Daten einer Quelle stehen lässt (Fundstelle 22). */
    static final String WITHDRAWAL_KEPT = "Löschen der Quelle läuft noch";
    static final String WITHDRAWAL_KEPT_TEXT = "Das Löschen der alten Daten einer Quelle bleibt in der Liste, bis es "
            + "gelingt oder abgebrochen wird; gescheitert wiederholt es sich von selbst.";

    private static boolean openWithdrawal(AppRuntime runtime, JobStatus job) {
        try {
            return runtime.workspace().openWithdrawal(job);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * „Alle Aufträge löschen“ im Menü neben dem Besen: löscht alle Aufträge in jedem Zustand; laufende und wartende
     * bricht der Core dafür ab ({@code Workspace#deleteAllJobs}), damit kein Auftrag unsichtbar weiterläuft. Laufen
     * welche, warnt der Dialog wie beim Beenden der App.
     */
    void deleteAll() {
        AppRuntime runtime = context.runtime();
        // Genau die echten Aufträge des Reiters, die der Dialog zählt, werden gelöscht; der andere Reiter bleibt.
        List<JobStatus> all = tabJobs(lastJobs, backgroundTab());
        // Wie beim Besen: Ein offenes Löschen der Daten einer Quelle bleibt (Fundstelle 22, Abnahme #1090).
        List<String> withdrawing = runtime == null ? List.of() : all.stream()
                .filter(job -> openWithdrawal(runtime, job)).map(JobStatus::id).toList();
        List<JobStatus> own = all.stream().filter(job -> !withdrawing.contains(job.id())).toList();
        List<String> ids = own.stream().map(JobStatus::id).toList();
        int count = own.size();
        if (runtime != null && count == 0 && !withdrawing.isEmpty()) {
            feedback.warning(WITHDRAWAL_KEPT, WITHDRAWAL_KEPT_TEXT);
            return;
        }
        if (runtime == null || count == 0) {
            return;
        }
        long running = own.stream().filter(job -> !job.state().terminal()).count();
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
        Background.run(() -> runtime.workspace().deleteAllJobs(ids), deleted -> {
            selectedId = null;
            detail = null;
            table.clearSelection();
            render();
            if (withdrawing.isEmpty()) {
                feedback.success(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht");
            } else {
                feedback.warning(Texts.count(deleted, "Auftrag", "Aufträge") + " gelöscht; " + WITHDRAWAL_KEPT,
                        WITHDRAWAL_KEPT_TEXT);
            }
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
        String pauseTip = forTab(PAUSE_ALL_TIP);
        pauseAll.setToolTipText(resume ? forTab(RESUME_ALL_TIP) : globalPause == GlobalPause.PAUSE ? pauseTip
                : pauseTip + " · gerade wartet oder läuft nichts, das sich anhalten lässt");
        pauseAll.setEnabled(globalPause != GlobalPause.NONE && !busyAll);
        boolean cancellable = !AllJobs.toCancel(jobs).isEmpty();
        String cancelTip = forTab(CANCEL_ALL_TIP);
        cancelAll.setToolTipText(cancellable ? cancelTip : cancelTip + " · gerade wartet und läuft nichts");
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

    /** Symbol und Text von „Alle anhalten“ bzw. „Alle fortsetzen“. */
    private void labelGlobalButtons() {
        boolean resume = globalPause == GlobalPause.RESUME;
        String pauseName = resume ? RESUME_ALL : PAUSE_ALL;
        pauseAll.setIcon(resume ? RESUME : PAUSE);
        if (!Objects.equals(pauseAll.getText(), pauseName)) {
            pauseAll.setText(pauseName);
        }
        pauseAll.getAccessibleContext().setAccessibleName(pauseName);
        cancelAll.getAccessibleContext().setAccessibleName(CANCEL_ALL);
    }

    /**
     * Die Werkzeuge der Leiste unten: {@code west} links (gleich rechts der Lupe), {@code center} mittig zur ganzen
     * Leiste, nicht nur zum Platz rechts der Lupe; wird es eng, rückt die Mitte neben die linke Gruppe.
     */
    static final class ToolsLayout implements java.awt.LayoutManager {

        static final int GAP = 12;

        private final Component west;
        private final Component center;
        /** Steht rechts und bleibt immer stehen (etwa ein Umschalter), oder {@code null}. */
        private final Component fixed;
        /** Steht rechtsbündig am Ende der Zeile, also unten rechts in der Ecke der Leiste; weicht, wenn es eng wird. */
        private final Component east;

        ToolsLayout(Component west, Component center, Component east) {
            this(west, center, null, east);
        }

        ToolsLayout(Component west, Component center, Component fixed, Component east) {
            this.west = west;
            this.center = center;
            this.fixed = fixed;
            this.east = east;
        }

        private Consumer<Boolean> tight = now -> { };
        private boolean isTight;

        /** Meldet, wenn der linke Teil weichen muss, weil die Breite auch ohne Hilfe nicht reicht, und wenn er zurückkommt. */
        void onTight(Consumer<Boolean> tight) {
            this.tight = tight;
        }

        private int fixedWidth() {
            return fixed == null || !fixed.isVisible() ? 0 : fixed.getPreferredSize().width + GAP / 2;
        }

        @Override
        public void layoutContainer(Container parent) {
            int height = parent.getHeight();
            java.awt.Dimension westSize = west.getPreferredSize();
            java.awt.Dimension centerSize = center.getPreferredSize();
            // Ein fester Teil rechts (der Umschalter) geht vor: Passt er nicht mehr neben die Aktionen, weicht der
            // linke Teil ganz und sein Besitzer bietet ihn anderswo an.
            boolean now = fixedWidth() > 0
                    && westSize.width + GAP + centerSize.width + GAP + fixedWidth() > parent.getWidth();
            if (now != isTight) {
                isTight = now;
                tight.accept(now);
            }
            if (now) {
                westSize = new java.awt.Dimension(-GAP, westSize.height);
                west.setBounds(0, 0, 0, 0);
            } else {
                west.setBounds(0, (height - westSize.height) / 2, westSize.width, westSize.height);
            }
            // Die Leiste ist der nächste Vorfahr mit eigener Breite über die ganze Zeile (FindBar); ohne sie mittig
            // im eigenen Platz.
            Container bar = javax.swing.SwingUtilities.getAncestorOfClass(FindBar.class, parent);
            int middle = bar == null ? parent.getWidth() / 2
                    : javax.swing.SwingUtilities.convertPoint(bar, bar.getWidth() / 2, 0, parent).x;
            // Reicht die Breite nicht für alles (Leiste an ihrer Mindestbreite), weicht die Hilfe wie zuvor in der
            // Kopfzeile; die Aktionen und der Umschalter behalten ihren Platz.
            java.awt.Dimension eastSize = east.getPreferredSize();
            int fixedWidth = fixedWidth();
            boolean fits = westSize.width + GAP + centerSize.width + GAP + fixedWidth + eastSize.width
                    <= parent.getWidth();
            int right = fits ? parent.getWidth() - eastSize.width : parent.getWidth();
            east.setBounds(right, (height - eastSize.height) / 2, fits ? eastSize.width : 0, eastSize.height);
            if (fixedWidth > 0) {
                java.awt.Dimension fixedSize = fixed.getPreferredSize();
                right -= fits ? fixedWidth : fixedSize.width;
                fixed.setBounds(right, (height - fixedSize.height) / 2, fixedSize.width, fixedSize.height);
            }
            int room = fits || fixedWidth > 0 ? right - GAP : right;
            int x = Math.max(westSize.width + GAP, middle - centerSize.width / 2);
            x = Math.max(westSize.width + GAP, Math.min(x, room - centerSize.width));
            center.setBounds(x, (height - centerSize.height) / 2,
                    Math.max(0, Math.min(centerSize.width, room - x)), centerSize.height);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(Container parent) {
            java.awt.Dimension westSize = west.getPreferredSize();
            java.awt.Dimension centerSize = center.getPreferredSize();
            java.awt.Dimension eastSize = east.getPreferredSize();
            return new java.awt.Dimension(westSize.width + GAP + centerSize.width + GAP + fixedWidth()
                    + eastSize.width, Math.max(Math.max(westSize.height, centerSize.height), eastSize.height));
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(Container parent) {
            return preferredLayoutSize(parent);
        }

        @Override
        public void addLayoutComponent(String name, Component component) {
        }

        @Override
        public void removeLayoutComponent(Component component) {
        }
    }

    /**
     * Die Kopfzeile der Liste: links die Wippe in ihrer vollen Breite, rechts die Werkzeuge, immer in einer Zeile.
     * Reicht die Breite nicht für beide, meldet die Zeile „schmal“; der Aufrufer nimmt dann Beiwerk aus der rechten
     * Gruppe (die Beschriftungen der Auswahlfelder), das {@code saved} Pixel spart.
     */
    static final class HeadLayout implements java.awt.LayoutManager {

        /** Mindestabstand zwischen Wippe und Werkzeugen. */
        static final int GAP = 8;

        private final Component west;
        private final Component east;
        private final int saved;
        private final Consumer<Boolean> narrow;
        private boolean isNarrow;

        /**
         * @param saved  um wie viel die rechte Gruppe schmaler wird, sobald {@code narrow} gemeldet ist
         * @param narrow nimmt das Beiwerk der rechten Gruppe weg ({@code true}) oder zeigt es wieder
         */
        HeadLayout(Component west, Component east, int saved, Consumer<Boolean> narrow) {
            this.west = west;
            this.east = east;
            this.saved = saved;
            this.narrow = narrow;
        }

        @Override
        public void layoutContainer(Container parent) {
            java.awt.Insets in = parent.getInsets();
            int left = in.left;
            int right = parent.getWidth() - in.right;
            int top = in.top;
            int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
            int westWidth = west.getPreferredSize().width;
            // Gemessen wird immer gegen die volle rechte Gruppe, sonst kippte die Zeile an der Grenze hin und her.
            int eastFull = east.getPreferredSize().width + (isNarrow ? saved : 0);
            boolean now = right - left < westWidth + GAP + eastFull;
            if (now != isNarrow) {
                isNarrow = now;
                narrow.accept(now);
            }
            // Die Werkzeuge rechts bleiben immer ganz erreichbar; wird es selbst ohne Beiwerk zu eng, gibt die Wippe
            // die fehlenden Pixel her.
            int eastWidth = Math.min(east.getPreferredSize().width, Math.max(0, right - left));
            int eastHeight = Math.min(height, east.getPreferredSize().height);
            west.setBounds(left, top, Math.max(0, Math.min(westWidth, right - left - GAP - eastWidth)), height);
            east.setBounds(right - eastWidth, top + (height - eastHeight) / 2, eastWidth, eastHeight);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(Container parent) {
            return size(parent, west.getPreferredSize(), east.getPreferredSize());
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(Container parent) {
            return size(parent, west.getPreferredSize(), new java.awt.Dimension(0, east.getMinimumSize().height));
        }

        private java.awt.Dimension size(Container parent, java.awt.Dimension westSize, java.awt.Dimension eastSize) {
            java.awt.Insets in = parent.getInsets();
            return new java.awt.Dimension(in.left + in.right + westSize.width + GAP + eastSize.width,
                    in.top + in.bottom + Math.max(westSize.height, eastSize.height));
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
        boolean background = backgroundTab();
        runAll(() -> resume ? runtime.workspace().resumeAllJobs(background)
                : runtime.workspace().pauseAllJobs(background), resume ? "fortgesetzt" : "angehalten");
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
        boolean background = backgroundTab();
        int count = AllJobs.toCancel(tabJobs(lastJobs, background)).size();
        String which = background ? "Hintergrundaufträge" : "Aufträge";
        if (count == 0 || !Dialogs.confirm(root, "Alle " + which + " abbrechen?", "Wirklich alle wartenden und "
                + "laufenden " + which + " abbrechen? Das betrifft " + Texts.count(count, "Auftrag", "Aufträge")
                + "; bereits Gelesenes bleibt unbestätigt. Angehaltene und beendete Aufträge bleiben, wie sie sind.")) {
            return;
        }
        runAll(() -> runtime.workspace().cancelAllJobs(background), "abgebrochen");
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

    /**
     * Was ein Abruf der Liste liest: alle Aufträge, das Detail und die Ereignisse des gewählten. Eine Gruppenzeile
     * ({@value #GROUP_PREFIX}…) ist kein gespeicherter Auftrag: ihre ID geht nie an den Core, ihr Detail entsteht aus
     * der Liste ({@link #groupRow(String)}).
     *
     * @return Aufträge, Detail, aktuelle Phase, Vermerke
     */
    public static Object[] read(Workspace workspace, String id) {
        List<JobStatus> jobs = workspace.jobs();
        Object current = null;
        String currentPhase = null;
        boolean real = id != null && !synthetic(id);
        if (real) {
            JobStatus status = workspace.job(id);
            if (!status.state().terminal()) {
                currentPhase = workspace.events(id).stream().map(JobEvent::phase)
                        .filter(Objects::nonNull).reduce((first, second) -> second).orElse(null);
            }
            current = status.type() == JobType.SYNC ? workspace.syncStatus(id) : status;
            if (status.type() == JobType.EXPORT && status.state() == JobState.COMPLETED) {
                current = new ExportDetail(status, workspace.exportArtifact(id).orElse(null));
            }
        }
        return new Object[] {jobs, current, currentPhase, notes(real ? workspace.events(id) : List.of())};
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
        if (syncGroupRow(job)) {
            return "sync:" + job.id().substring(SYNC_GROUP_PREFIX.length());
        }
        if (groupRow(job)) {
            return "background:" + job.id().substring(GROUP_PREFIX.length());
        }
        if (job.type() == JobType.EXPORT) {
            return "export:" + job.id();
        }
        if (job.type() == JobType.UPDATE_SUMMARY && job.parameter(com.aresstack.spaceharvester.application.changelog.ChangeLog.PARENT_PARAMETER) == null) {
            // „Zusammenfassen“ ist Oberauftrag seiner Teile und Zusammenführungen (Angelo 22.11.2026).
            return "summary:" + job.id();
        }
        String run = job.parameter(com.aresstack.spaceharvester.application.presentation.VideoPresentations
                .RUN_PARAMETER);
        if (job.type() == JobType.PRESENTATION_EXPORT && run != null) {
            return "presentation:" + run;
        }
        // Jeder andere Auftrag kann Klammer seiner Unteraufträge sein (ChildJobs, etwa „Buch schreiben“ je Quelle).
        return job.parameter(com.aresstack.spaceharvester.application.jobs.ChildJobs.PARENT_PARAMETER) == null
                ? "child:" + job.id() : null;
    }

    /** Zu welchem Oberschritt ein Auftrag gehört; {@code null}, wenn er eigenständig ist. */
    private static String memberKey(JobStatus job) {
        String childOf = job.parameter(com.aresstack.spaceharvester.application.jobs.ChildJobs.PARENT_PARAMETER);
        if (childOf != null) {
            return "child:" + childOf;
        }
        if (job.type() == JobType.SYNC && !groupRow(job)) {
            String group = job.parameter(SpaceSyncJobs.GROUP);
            return group == null ? null : "sync:" + group;
        }
        if (job.type() == JobType.BACKGROUND && !groupRow(job)) {
            String group = job.parameter(BackgroundJobs.GROUP);
            return group == null ? null : "background:" + group;
        }
        if (job.type() == JobType.UPDATE_SUMMARY) {
            String parent = job.parameter(com.aresstack.spaceharvester.application.changelog.ChangeLog.PARENT_PARAMETER);
            return parent == null ? null : "summary:" + parent;
        }
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

        static final int TYPE = 0;
        static final int SOURCE = 1;
        static final int SPACE = 2;
        /** Wiederholen, Anhalten ⇄ Fortsetzen und Löschen, links neben dem Balken ({@link #ROW_CONTROLS}). */
        static final int CONTROLS = 3;
        static final int ACTIONS = 4;
        private static final String[] COLUMNS = {"Art", "Quelle", "Space", "", ""};
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
                case TYPE -> Texts.type(status);
                case SOURCE -> source(status);
                case SPACE -> space(status);
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

        /** Beim Anordnen: In der schmalen Liste zeigt „Art“ nur ihr Symbol, „Space“ wird schmaler. */
        @Override
        public void doLayout() {
            if (getWidth() > 0) {
                narrow(getWidth() < NARROW_BELOW);
            }
            super.doLayout();
        }

        @Override
        public Component prepareRenderer(TableCellRenderer renderer, int row, int column) {
            Component cell = super.prepareRenderer(renderer, row, column);
            if (cell instanceof JComponent component) {
                // Derselbe Renderer zeichnet alle Zeilen: für jede Zelle neu festlegen.
                // Ein Suchtreffer (Leiste unten) bleibt deckend, sonst verschwände seine Markierung im Band.
                component.setOpaque(ReaderFind.marked(this, row, column)
                        || (bands == null || bandAt(row) == null) && !toned(row));
            }
            return cell;
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(getBackground());
                g.fillRect(0, 0, getWidth(), getHeight());
                paintTones(g);
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
            Graphics2D stripes = (Graphics2D) graphics.create();
            try {
                paintStripes(stripes);
            } finally {
                stripes.dispose();
            }
        }

        /** Heller Petrol-Ton hinter jeder Gruppenzeile im Reiter „Hintergrund“, gewählt die Auswahlfarbe. */
        private void paintTones(Graphics2D g) {
            for (int row = 0; row < getRowCount(); row++) {
                if (toned(row)) {
                    Rectangle cell = getCellRect(row, 0, true);
                    g.setColor(isRowSelected(row) ? getSelectionBackground() : BACKGROUND_TONE);
                    g.fillRect(0, cell.y, getWidth(), cell.height);
                }
            }
        }

        /** Schmaler Petrol-Streifen am linken Rand jeder Hintergrundzeile (UX 21.11.2026). */
        private void paintStripes(Graphics2D g) {
            g.setColor(Comic.PETROL);
            for (int row = 0; row < getRowCount() && row < model.rows.size(); row++) {
                if (inBackground(model.rows.get(row))) {
                    Rectangle cell = getCellRect(row, 0, true);
                    g.fillRect(0, cell.y, BACKGROUND_STRIPE, cell.height);
                }
            }
        }

        /** Ob die Zeile eine Hintergrundgruppe ist, deren Ton die Tabelle selbst zeichnet. */
        private boolean toned(int row) {
            return row >= 0 && row < model.rows.size() && groupRow(model.rows.get(row))
                    && (bands == null || bandAt(row) == null);
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
            // Davor das Symbol der Art; in der schmalen Liste steht es allein, der Name im Tooltip (Angelo 22.11.2026).
            setIcon(job == null ? null : new PairIcon(groupIcon(model.group(job), model.collapsed(job),
                    model.child(job)), typeIcon(job.type())));
            setIconTextGap(6);
            if (narrow) {
                setText("");
            }
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
            boolean background = job != null && groupRow(job);
            String full = value == null ? null : value.toString();
            setToolTipText(job != null && model.group(job) ? full + " · " + (model.collapsed(job)
                    ? (background ? "Unteraufträge je Space zeigen" : "Bilder und Anhänge zeigen")
                    : (background ? "Unteraufträge ausblenden" : "Bilder und Anhänge ausblenden")) : full);
            return this;
        }
    }

    /** „Quelle“ und „Space“: im Tooltip stehen immer beide, auch wenn die Zelle kürzt oder „Space“ gewichen ist. */
    private final class WhereRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            JobStatus job = row < model.rows.size() ? model.rows.get(row) : null;
            setToolTipText(job == null ? null : where(job));
            return this;
        }
    }

    /** Ob die Liste gerade schmal ist ({@link #NARROW_BELOW}). */
    private boolean narrow;

    /** Stellt die Spalten für die schmale bzw. breite Liste; nur beim Wechsel. */
    private void narrow(boolean value) {
        if (value == narrow && table.getColumnModel().getColumn(JobsModel.TYPE).getMaxWidth() > 0) {
            return;
        }
        narrow = value;
        javax.swing.table.TableColumn type = table.getColumnModel().getColumn(JobsModel.TYPE);
        type.setMinWidth(value ? ART_ICON_WIDTH : 15);
        type.setMaxWidth(value ? ART_ICON_WIDTH : Integer.MAX_VALUE);
        type.setPreferredWidth(value ? ART_ICON_WIDTH : 220);
        // Angelo 23.11.2026: Auch schmal steht der Space in eigener Spalte; sonst hieße jede Zeile nur nach der Quelle.
        javax.swing.table.TableColumn space = table.getColumnModel().getColumn(JobsModel.SPACE);
        space.setMinWidth(value ? NARROW_SPACE_MIN : 15);
        space.setMaxWidth(value ? NARROW_SPACE_MAX : Integer.MAX_VALUE);
        space.setPreferredWidth(value ? NARROW_SPACE_MAX : 170);
        javax.swing.table.TableColumn actions = table.getColumnModel().getColumn(JobsModel.ACTIONS);
        int width = (value ? PROGRESS_SLOTS : SLOTS) * RowActions.SLOT + 8;
        actions.setMinWidth(0);
        actions.setMaxWidth(width);
        actions.setMinWidth(width);
        actions.setPreferredWidth(width);
        global.removeAll();
        if (value) {
            global.add(selectedRow);
        } else {
            global.add(pauseAll);
            global.add(cancelAll);
        }
        global.revalidate();
        root.repaint();
    }

    /** Für Tests: ob die Liste schmal dargestellt wird. */
    boolean narrow() {
        return narrow;
    }

    /**
     * Das Symbol einer Auftragsart; verwandte Arten teilen sich eines (Laden, Export, Analyse, Video), der genaue
     * Name steht daneben bzw. im Tooltip.
     */
    static javax.swing.Icon typeIcon(JobType type) {
        return switch (type) {
            case SYNC -> Glyph.SYNC;
            case HARVEST, IMPORT, REREAD, READING_PREFETCH -> ComposerIcons.download();
            case EXPORT, CONVERSION, METADATA_EXPORT, ANALYTICS_EXPORT, PRESENTATION_EXPORT, PODCAST_EXPORT ->
                    ComposerIcons.export();
            case KNOWLEDGE_GRAPH_EXPORT -> Glyph.GRAPH;
            case ASSETS -> ComposerIcons.picture();
            case ATTACHMENTS -> ComposerIcons.paperclip();
            case ENRICHMENT, STATEMENT_ANALYSIS, STATEMENT_CHECK, DOCUMENT_ANALYSIS, RESEARCH, BOOK -> Glyph.SPARKLE;
            case GLOSSARY -> ComposerIcons.openBook();
            case SEARCH_QUALITY -> ComposerIcons.magnifier();
            case UPDATE_SUMMARY -> Glyph.SUMMARIZE;
            case UPDATE_PODCAST -> ComposerIcons.microphone();
            case VIDEO_TRANSCRIPT, VIDEO_SLIDES, TRANSCRIPT_CORRECTION, SLIDE_NOTES -> ComposerIcons.play();
            case PURGE -> Glyph.TRASH;
            case BACKGROUND -> ComposerIcons.gear();
        };
    }

    /** Zwei Symbole nebeneinander auf festen Plätzen: Aufklapp-Pfeil bzw. Winkel (oder frei) und die Art. */
    private record PairIcon(javax.swing.Icon first, javax.swing.Icon second) implements javax.swing.Icon {
        private static final int SLOT = 16;
        private static final int GAP = 4;

        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            if (first != null) {
                first.paintIcon(c, g, x + (SLOT - first.getIconWidth()) / 2, y + (SLOT - first.getIconHeight()) / 2);
            }
            second.paintIcon(c, g, x + SLOT + GAP + (SLOT - second.getIconWidth()) / 2,
                    y + (SLOT - second.getIconHeight()) / 2);
        }

        @Override
        public int getIconWidth() {
            return 2 * SLOT + GAP;
        }

        @Override
        public int getIconHeight() {
            return SLOT;
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

    /** Ob der Auftrag auf eine Anmeldung wartet (ADR 0018). */
    boolean waitingForSignIn(String jobId) {
        AppRuntime runtime = context.runtime();
        return runtime != null && runtime.workspace().signIns().waiting(jobId).isPresent();
    }
}
