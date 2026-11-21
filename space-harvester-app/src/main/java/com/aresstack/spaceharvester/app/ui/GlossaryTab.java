package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess;
import com.aresstack.spaceharvester.app.runtime.GlossaryAccess.Entry;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.glossary.GlossaryClassification;
import com.aresstack.spaceharvester.application.glossary.GlossaryDerivation;
import com.aresstack.spaceharvester.application.glossary.GlossaryOverview;
import com.aresstack.spaceharvester.application.jobs.JobEvent;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.terms.RelationEvidence;
import com.aresstack.spaceharvester.application.terms.RelationOrigin;
import com.aresstack.spaceharvester.application.terms.ReviewStatus;
import com.aresstack.spaceharvester.application.terms.TermQueries;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Der Reiter „Fachglossar“ unter Analysen ohne Seite (ADR 0032, FL5b): die Glossareinträge zur Auswahl links im Explorer,
 * fertig sortiert, gefiltert und gezählt vom Core ({@code GlossaryOverview}). Oben rechts in der Reiterzeile stehen
 * die Suche mit ✕ und die Icon-Knöpfe „Fachbegriff hinzufügen“ und „Fachbegriffe ermitteln“ (in der Reiterzeile); die
 * sechs Reiter von Begriffe lassen dort keinen Platz für Chips. Darum steht über der Tabelle eine Zeile mit Zählchips
 * je Einordnung (fachlich, Rolle, unklar, Technik; Technik standardmäßig aus) und je Status (vorgeschlagen,
 * bestätigt, abgelehnt = Sperrliste); reicht die Breite nicht, steht der Status in einem Filtermenü. Die Tabelle zeigt Begriff, Einordnung, Wichtigkeit, Status, Herkunft und
 * Erläuterung; Zeilenaktionen als Symbole, Einzelheiten als Fenster am Mausklick.
 *
 * <p>Lesen in zwei Formen: Eine neue Auswahl oder das Ende von „Fachbegriffe ermitteln“, „Aussagen analysieren“ oder
 * „Aussagen prüfen“ holt die volle Sicht (unter dem Wartekreis, abbrechbar); Chips, Suche und jede Meldung des
 * Glossars verfeinern die gehaltene Sicht, ohne die Ableitung neu zu rechnen. Beides kommt über Beobachter des Core
 * (ADR 0027), nie über einen Zeitgeber; ist der Reiter nicht zu sehen, liest er beim nächsten Zeigen. Der Reiter
 * verknüpft, sortiert und zählt nichts selbst. Rückmeldungen gehen in die Statuszeile des Hauptfensters. Nur auf dem
 * EDT.
 */
public final class GlossaryTab {

    static final String DETECT = "Fachbegriffe ermitteln";
    /** Hilfe am ?-Knopf (keine Hinweistexte in der Ansicht); nennt keinen Reiterpfad. */
    public static final String HELP = "Das Fachglossar sammelt die Fachbegriffe der Auswahl links mit Einordnung, "
            + "Wichtigkeit, Status, Herkunft und Erläuterung.\n\n"
            + "„Fachbegriffe ermitteln“ (▶ oben rechts) schlägt Begriffe aus den Seiten vor und ordnet sie nach Regeln "
            + "ein; „Fachbegriff hinzufügen“ (＋) legt einen Begriff von Hand an. Vorschläge bestätigst oder lehnst du "
            + "in ihrer Zeile ab; abgelehnte stehen auf der Sperrliste und kommen nicht wieder.\n\n"
            + "Die Chips filtern nach Einordnung und Status; gefüllt heißt: wird gezeigt. Die Wichtigkeit zählt, auf "
            + "wie vielen Seiten der Auswahl ein Begriff vorkommt; der Tooltip der Spalte nennt die Einzelheiten.";
    static final String ADD = "Fachbegriff hinzufügen …";
    static final String DETECT_TIP = DETECT + ": Begriffe aus den analysierten Seiten der Auswahl links ableiten, "
            + "auch ohne Modell; mit Modell und KI-Freigabe ordnet danach das Modell ein. Läuft als Auftrag";
    static final String ADD_TIP = "Fachbegriff hinzufügen: einen Begriff von Hand anlegen; er gilt sofort als "
            + "bestätigt";
    static final String SEARCH_TIP = "Fachbegriffe suchen: Anfang des Begriffs oder eines seiner Wörter";
    static final String STATUS_FILTER_TIP = "Nach Status filtern: vorgeschlagen, bestätigt oder abgelehnt (Sperrliste)";
    static final String OPEN_AND_CONFIRMED = "Vorgabe: vorgeschlagen und bestätigt";
    static final String CONFIRM = "Bestätigen";
    static final String REJECT = "Ablehnen";
    static final String RESTORE = "Ablehnung zurücknehmen";
    static final String EDIT = "Erläuterung bearbeiten";
    static final String REMOVE = "Entfernen";
    static final String ONLY_MANUAL = "Nur manuelle Einträge lassen sich löschen; einen Vorschlag ablehnen";
    static final String ALREADY_CONFIRMED = "Schon bestätigt";
    static final String NO_GLOSSARY = "Das Fachglossar ist noch nicht verfügbar.";
    static final String RUNNING = "Ein Lauf läuft schon; Fortschritt unter Aufträge und in der Statusleiste";
    static final String STARTED = DETECT + " läuft im Hintergrund; neue Vorschläge erscheinen hier sofort, "
            + "Fortschritt unter Aufträge";
    static final String MODEL_SKIPPED_NO_MODEL = DETECT + ": Begriffe ohne Modell abgeleitet; die Einordnung durch "
            + "das Modell entfällt, weil keines eingestellt ist (Einstellungen › Modelle)";
    static final String MODEL_SKIPPED_NOT_RELEASED = DETECT + ": Begriffe ohne Modell abgeleitet; die Einordnung "
            + "durch das Modell entfällt, weil die Quelle nicht für die KI freigegeben ist";
    static final String EMPTY = "Noch keine Fachbegriffe zur Auswahl. „Fachbegriffe ermitteln“ leitet sie aus den "
            + "analysierten Seiten ab; „Fachbegriff hinzufügen“ legt einen von Hand an.";
    static final String NO_MATCH = "Kein Fachbegriff passt zu Suche und Filter.";
    static final String CONFLICT = "Der Eintrag wurde inzwischen geändert; die Liste ist neu gelesen.";
    static final String BUSY = "Fachbegriffe der Auswahl werden gelesen …";
    static final String STALE = "veraltet";
    static final String NO_IMPORTANCE = "–";
    /** Zeichen Kontext je Beleg im Fenster. */
    static final int SNIPPET_CHARS = 120;
    /** Höchstens so viele Belege zeigt das Fenster, mit Link zur Seite. */
    static final int EVIDENCE_SHOWN = 3;
    static final int NOTICE_MS = 8000;
    static final String[] COLUMNS = {"Begriff", "Einordnung", "Wichtigkeit", "Status", "Herkunft", "Erläuterung",
            ""};
    static final int IMPORTANCE_COLUMN = 2;
    static final int ACTIONS_COLUMN = COLUMNS.length - 1;
    static final int ACTION_COUNT = 4;
    /** Aufträge, deren Ende die Sicht ändert (Wichtigkeit und Vorschläge). */
    static final Set<JobType> REREADS = EnumSet.of(JobType.GLOSSARY, JobType.STATEMENT_ANALYSIS,
            JobType.STATEMENT_CHECK);

    /** Wie neu gelesen wird: nichts, die gehaltene Sicht verfeinern oder die volle Sicht holen. */
    private enum Pending { NONE, REFINE, FULL }

    private final AppContext context;
    private final Supplier<Optional<GlossaryAccess>> access;
    private final TermDirectory directory = new TermDirectory("Fachglossar", COLUMNS);
    private final BusyOverlay busy = new BusyOverlay(directory);
    private final IconButton detect = new IconButton(ComposerIcons.play(), DETECT_TIP);
    private final IconButton add = new IconButton(ComposerIcons.plus(16), ADD_TIP);
    private final com.aresstack.spaceharvester.app.ui.PlaceholderField search =
            new com.aresstack.spaceharvester.app.ui.PlaceholderField(12, "Suchen …");
    private final InputFrame searchField = InputFrame.search(search);
    private final Map<GlossaryDerivation.Category, CountChip> categories =
            new EnumMap<>(GlossaryDerivation.Category.class);
    private final Map<ReviewStatus, CountChip> statuses = new EnumMap<>(ReviewStatus.class);
    /** Die Zeile über der Tabelle: Chips je Einordnung, dann je Status; zu schmal, steht der Status im Filtermenü. */
    private final JPanel chipLine = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel categoryChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final JPanel statusChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final IconButton statusFilter = new IconButton(Glyph.FILTER, STATUS_FILTER_TIP);
    private final javax.swing.JPopupMenu statusMenu = new javax.swing.JPopupMenu();
    private final JPanel page = new JPanel(new java.awt.BorderLayout(0, 8));
    private final Map<String, Entry> byId = new HashMap<>();
    private final List<GlossaryAccess.Row> shown = new ArrayList<>();
    private final Map<String, GlossaryAccess.Row> rowById = new HashMap<>();
    /** Die Auswahl links; ohne Auswahl alle Wissensquellen. */
    private ExportPanel.Selection selection = ExportPanel.Selection.ALL;
    /** Die gehaltene Sicht zum Verfeinern; {@code null} bis zur ersten vollen Sicht der Auswahl. */
    private GlossaryAccess.Overview overview;
    private Pending pending = Pending.FULL;
    private int request;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private String keepSelected;
    /** Meldung unter der Tabelle nach dem nächsten Zeigen, etwa nach einer abgelehnten Änderung. */
    private String afterShow;
    /** Fragt vor dem Entfernen mit „Wirklich …“ und Ja/Nein; Tests ersetzen die Rückfrage. */
    private java.util.function.BiPredicate<String, String> confirm =
            (heading, question) -> Dialogs.confirm(directory, heading, question);
    /** Das offene Overlay, für Tests. */
    private InfoPopup popup;
    /** Die eigenen Aufträge „Fachbegriffe ermitteln“ (je Quelle einer) mit ihrem letzten Stand; leer ohne Lauf. */
    private final Map<String, JobStatus> jobs = new LinkedHashMap<>();
    /** Der Lauf wird gerade gestartet (Aufträge noch ohne Kennung). */
    private boolean starting;
    /** Das beobachtete Glossar; ein neues (neue Laufzeit) ersetzt das alte samt Anmeldungen. */
    private GlossaryAccess observed;
    private Runnable unobserve;
    private Runnable unwatch;
    /** Ob der Reiter zu sehen ist; nur dann liest eine Meldung sofort (Tests ersetzen es). */
    java.util.function.BooleanSupplier showing = directory::isShowing;
    /** Stand des Reiters für die Statuszeile (Anzahl, Auswahl). */
    private java.util.function.Consumer<String> onStatus = text -> { };

    /**
     * @param context Laufzeit für Statuszeile, Auswahl und Navigation; {@code null} zeigt nur das Gerüst (Tests)
     * @param access  das Glossar des Core, leer solange es keinen Unterbau gibt
     */
    /** Die zuletzt gesehenen Regeln der Begriffsableitung, um nur bei einer Änderung neu zu lesen. */
    private com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings rules;

    private com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings rules() {
        return context == null || context.settings() == null ? null : context.settings().terms().glossaryRules();
    }

    public GlossaryTab(AppContext context, Supplier<Optional<GlossaryAccess>> access) {
        this.context = context;
        this.access = access;
        directory.show(List.of(), NO_GLOSSARY);
        directory.withoutHead();
        directory.columnHeads();
        busy.setText(BUSY);
        busy.onCancel(() -> cancelled.set(true));
        JTable table = directory.table();
        GlossaryRenderer renderer = new GlossaryRenderer();
        for (int c = 0; c < ACTIONS_COLUMN; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        int[] widths = {200, 100, 90, 110, 80, 300};
        for (int c = 0; c < widths.length; c++) {
            table.getColumnModel().getColumn(c).setPreferredWidth(widths[c]);
        }
        RowActions.install(table, ACTIONS_COLUMN, ACTION_COUNT, row -> actions(entryAtModel(row)));
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0 && e.getClickCount() == 1 && SwingUtilities.isLeftMouseButton(e)
                        && table.columnAtPoint(e.getPoint()) != table.convertColumnIndexToView(ACTIONS_COLUMN)) {
                    Entry entry = entryAtView(row);
                    if (entry != null) {
                        showDetails(entry, e.getX(), e.getY());
                    }
                }
            }
        });
        for (JPanel group : List.of(chipLine, categoryChips, statusChips)) {
            group.setOpaque(false);
        }
        for (GlossaryDerivation.Category category : List.of(GlossaryDerivation.Category.FACHLICH,
                GlossaryDerivation.Category.ROLLE, GlossaryDerivation.Category.UNKLAR,
                GlossaryDerivation.Category.TECHNISCH)) {
            CountChip chip = new CountChip(categoryWord(category), categoryTone(category));
            chip.setToolTipText(categoryTip(category));
            chip.setSelected(GlossaryOverview.Query.WITHOUT_TECHNICAL.contains(category));
            chip.addActionListener(e -> refine());
            categories.put(category, chip);
            categoryChips.add(chip);
        }
        for (ReviewStatus status : List.of(ReviewStatus.PROPOSED, ReviewStatus.CONFIRMED, ReviewStatus.REJECTED)) {
            CountChip chip = new CountChip(statusWord(status), statusTone(status));
            chip.setToolTipText(statusTip(status));
            chip.setSelected(GlossaryOverview.Query.WITHOUT_REJECTED.contains(status));
            chip.addActionListener(e -> toggleStatus(status, chip.isSelected()));
            statuses.put(status, chip);
            statusChips.add(chip);
        }
        statusFilter.setVisible(false);
        statusFilter.addActionListener(e -> showStatusMenu());
        chipLine.add(categoryChips);
        chipLine.add(new ChipGap());
        chipLine.add(statusChips);
        chipLine.add(statusFilter);
        // Kein Umbruch, kein Rollbalken: Reicht die Breite nicht für beide Gruppen, wandert der Status ins Menü.
        chipLine.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                fitChips();
            }
        });
        if (context != null) {
            // Neue Regeln (Einstellungen › Modelle) ändern die Einordnung: dann einmal die volle Sicht, ohne Zeitgeber.
            rules = rules();
            context.onChange(changed -> {
                com.aresstack.spaceharvester.application.configuration.GlossaryRuleSettings now = rules();
                if (!java.util.Objects.equals(now, rules)) {
                    rules = now;
                    reread(Pending.FULL);
                }
            });
        }
        page.setOpaque(false);
        page.add(chipLine, java.awt.BorderLayout.NORTH);
        page.add(busy, java.awt.BorderLayout.CENTER);
        search.setToolTipText(SEARCH_TIP);
        search.getAccessibleContext().setAccessibleName(SEARCH_TIP);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refine();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refine();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refine();
            }
        });
        detect.setEnabled(false);
        add.setEnabled(false);
        detect.addActionListener(e -> detect());
        add.addActionListener(e -> askNew());
    }

    /** Die Suche für die Reiterzeile; die Knöpfe kommen einzeln ({@link #addButton()}), die Chips stehen im Reiter. */
    public JComponent tools() {
        return searchField;
    }

    /**
     * Status-Chips sind Umschalter wie die Einordnung (gefüllt = wird gezeigt). Ist keiner mehr an, gilt wieder die
     * Vorgabe (vorgeschlagen und bestätigt), sichtbar an den Chips.
     */
    private void toggleStatus(ReviewStatus status, boolean on) {
        statuses.get(status).setSelected(on);
        if (statuses.values().stream().noneMatch(CountChip::isSelected)) {
            defaultStatuses();
        } else {
            refine();
        }
    }

    /** Die Vorgabe der Status: vorgeschlagen und bestätigt, ohne abgelehnte. */
    private void defaultStatuses() {
        statuses.forEach((status, chip) -> chip.setSelected(GlossaryOverview.Query.WITHOUT_REJECTED.contains(status)));
        refine();
    }

    /** Zeigt die Status-Chips, wenn beide Gruppen in die Zeile passen, sonst den Filterknopf mit Menü. */
    void fitChips() {
        int width = chipLine.getWidth();
        if (width <= 0) {
            return;
        }
        int needed = categoryChips.getPreferredSize().width + statusChips.getPreferredSize().width + 9 + 24;
        boolean fits = width >= needed;
        if (statusChips.isVisible() != fits) {
            statusChips.setVisible(fits);
            statusFilter.setVisible(!fits);
            chipLine.revalidate();
            chipLine.repaint();
        }
        updateStatusFilter();
    }

    /** Der Filterknopf nennt die gezeigten Status im Tooltip. */
    private void updateStatusFilter() {
        Set<ReviewStatus> chosen = query().statuses();
        String tip = STATUS_FILTER_TIP + " · gezeigt: " + statuses.keySet().stream().filter(chosen::contains)
                .map(GlossaryTab::statusWord).collect(java.util.stream.Collectors.joining(", "));
        statusFilter.setToolTipText(tip);
        statusFilter.getAccessibleContext().setAccessibleName(tip);
    }

    /** Das Filtermenü mit denselben Zahlen wie die Chips, je Status ein Häkchen, dazu die Vorgabe. */
    private void showStatusMenu() {
        buildStatusMenu();
        statusMenu.show(statusFilter, 0, statusFilter.getHeight());
    }

    void buildStatusMenu() {
        statusMenu.removeAll();
        statuses.forEach((status, chip) -> {
            javax.swing.JCheckBoxMenuItem item = new javax.swing.JCheckBoxMenuItem(
                    statusWord(status) + " " + chip.count(), chip.isSelected());
            item.addActionListener(e -> toggleStatus(status, item.isSelected()));
            statusMenu.add(item);
        });
        statusMenu.addSeparator();
        javax.swing.JMenuItem reset = new javax.swing.JMenuItem(OPEN_AND_CONFIRMED);
        reset.addActionListener(e -> defaultStatuses());
        statusMenu.add(reset);
    }

    /** Die Auswahl links hat sich geändert: volle Sicht, sofort wenn zu sehen, sonst beim nächsten Zeigen. */
    public void selection(ExportPanel.Selection chosen) {
        selection = chosen == null ? ExportPanel.Selection.ALL : chosen;
        overview = null;
        reread(Pending.FULL);
    }

    /** Der Bereich wird sichtbar oder der Reiter gewählt: liest, was seit dem letzten Zeigen angefallen ist. */
    public void shown() {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            unavailable();
            return;
        }
        observe(glossary.get());
        if (overview == null || pending == Pending.FULL) {
            full();
        } else if (pending == Pending.REFINE) {
            refine();
        }
    }

    /** Wie {@link #shown()}. */
    void load() {
        shown();
    }

    private void unavailable() {
        directory.show(List.of(), NO_GLOSSARY);
        add.setEnabled(false);
        detect.setEnabled(false);
    }

    /** Liest neu, wenn der Reiter zu sehen ist; sonst merkt er sich, wie viel beim nächsten Zeigen zu lesen ist. */
    private void reread(Pending needed) {
        if (needed.ordinal() > pending.ordinal()) {
            pending = needed;
        }
        if (showing.getAsBoolean()) {
            shown();
        }
    }

    /** Was Chips und Suche verlangen; ohne gewählte Einordnung alle. */
    GlossaryOverview.Query query() {
        String q = search.getText() == null ? "" : search.getText().strip();
        Set<GlossaryDerivation.Category> chosen = EnumSet.noneOf(GlossaryDerivation.Category.class);
        categories.forEach((category, chip) -> {
            if (chip.isSelected()) {
                chosen.add(category);
            }
        });
        Set<ReviewStatus> shown = EnumSet.noneOf(ReviewStatus.class);
        statuses.forEach((status, chip) -> {
            if (chip.isSelected()) {
                shown.add(status);
            }
        });
        return new GlossaryOverview.Query(q.isEmpty() ? null : q, chosen.isEmpty() ? null : chosen, shown);
    }

    /** Volle Sicht über die Auswahl, unter dem Wartekreis; Abbrechen lässt die bisherige Liste stehen. */
    private void full() {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            unavailable();
            return;
        }
        pending = Pending.NONE;
        ExportPanel.Selection chosen = selection;
        GlossaryOverview.Query query = query();
        AtomicBoolean stop = begin();
        int current = request;
        busy.setBusy(true);
        Background.run(() -> glossary.get().overview(core(chosen), query, stop::get), result -> {
            if (current == request) {
                busy.setBusy(false);
                overview = result;
                show(result, query);
            }
        }, failure -> {
            if (current == request) {
                busy.setBusy(false);
                if (!(failure instanceof CancellationException)) {
                    pending = Pending.FULL;
                    directory.message(notice("Fachglossar nicht lesbar: " + failure.getMessage(), Severity.ERROR));
                }
            }
        });
    }

    /** Verfeinern: dieselbe Auswahl mit anderer Suche, anderem Filter oder nach einer Meldung des Glossars. */
    private void refine() {
        Optional<GlossaryAccess> glossary = access.get();
        GlossaryAccess.Overview held = overview;
        if (glossary.isEmpty() || held == null) {
            reread(Pending.FULL);
            return;
        }
        pending = Pending.NONE;
        GlossaryOverview.Query query = query();
        AtomicBoolean stop = begin();
        int current = request;
        Background.run(() -> glossary.get().refine(held, query, stop::get), result -> {
            if (current == request) {
                overview = result;
                show(result, query);
            }
        }, failure -> {
            if (current == request && !(failure instanceof CancellationException)) {
                pending = Pending.REFINE;
                directory.message(notice("Fachglossar nicht lesbar: " + failure.getMessage(), Severity.ERROR));
            }
        });
    }

    /** Eine neue Anfrage: die vorige wird abgebrochen, ihr Ergebnis verworfen. */
    private AtomicBoolean begin() {
        cancelled.set(true);
        cancelled = new AtomicBoolean();
        request++;
        return cancelled;
    }

    private com.aresstack.spaceharvester.application.holdings.Selection core(ExportPanel.Selection chosen) {
        return context == null || context.runtime() == null ? null : chosen.toCore(context.runtime());
    }

    /** Meldet sich beim Glossar und bei den Aufträgen an; ein neues Glossar (neue Laufzeit) ersetzt das alte. */
    private void observe(GlossaryAccess glossary) {
        if (glossary == observed) {
            return;
        }
        if (unobserve != null) {
            unobserve.run();
        }
        if (unwatch != null) {
            unwatch.run();
        }
        observed = glossary;
        add.setEnabled(true);
        updateDetect();
        unobserve = glossary.observe(() -> SwingUtilities.invokeLater(() -> reread(Pending.REFINE)));
        unwatch = glossary.watchJobs(new JobListener() {
            @Override
            public void changed(JobStatus status) {
                SwingUtilities.invokeLater(() -> jobChanged(status));
            }

            @Override
            public void event(JobEvent event) {
                String text = modelSkipped(event.code());
                if (text != null) {
                    // Bewusst ausgelassen, kein Fehler: gelb in der Statuszeile wie in der Auftragsliste.
                    SwingUtilities.invokeLater(() -> notice(text, Severity.WARN));
                }
            }
        });
    }

    /** Ein Auftrag hat einen neuen Stand: eigene Läufe nachführen, am Ende eines Auftrags die volle Sicht. */
    void jobChanged(JobStatus status) {
        track(status);
        if (status.state().terminal() && REREADS.contains(status.type())) {
            reread(Pending.FULL);
        }
    }

    /** Die Statuszeile nach einer bewusst ausgelassenen Modellphase, in Worten; {@code null} für andere Ereignisse. */
    static String modelSkipped(String code) {
        if (code == null || !code.startsWith(GlossaryClassification.MODEL_SKIPPED)) {
            return null;
        }
        return code.endsWith(":NOT_RELEASED") ? MODEL_SKIPPED_NOT_RELEASED : MODEL_SKIPPED_NO_MODEL;
    }

    private void updateDetect() {
        boolean running = !jobs.isEmpty() || starting;
        detect.setEnabled(observed != null && !running);
        detect.setToolTipText(running ? RUNNING : DETECT_TIP);
        detect.getAccessibleContext().setAccessibleName(detect.getToolTipText());
    }

    /** Zeigt die Sicht: Zahlen an den Chips, Zeilen in der Reihenfolge des Core, die gewählte Zeile bleibt gewählt. */
    void show(GlossaryAccess.Overview result, GlossaryOverview.Query query) {
        categories.forEach((category, chip) -> chip.count(result.byCategory().getOrDefault(category, 0)));
        statuses.forEach((status, chip) -> chip.count(result.byStatus().getOrDefault(status, 0)));
        fitChips();
        Entry current = selectedEntry();
        String select = keepSelected != null ? keepSelected : current == null ? null : current.id();
        keepSelected = null;
        byId.clear();
        rowById.clear();
        shown.clear();
        for (GlossaryAccess.Row row : result.rows()) {
            byId.put(row.entry().id(), row.entry());
            rowById.put(row.entry().id(), row);
            shown.add(row);
        }
        boolean filtered = query.q() != null
                || !query.statuses().equals(GlossaryOverview.Query.WITHOUT_REJECTED);
        directory.show(rows(result.rows()), filtered || !result.rows().isEmpty() ? NO_MATCH : EMPTY);
        onStatus.accept(status(selection.title(), result));
        if (afterShow != null) {
            directory.message(afterShow);
            afterShow = null;
        }
        if (select != null) {
            JTable table = directory.table();
            for (int row = 0; row < table.getRowCount(); row++) {
                Entry entry = entryAtView(row);
                if (entry != null && entry.id().equals(select)) {
                    table.setRowSelectionInterval(row, row);
                    table.scrollRectToVisible(table.getCellRect(row, 0, true));
                    break;
                }
            }
        }
    }

    /** „Datenhaus · 12 Fachbegriffe · 40 Seiten ausgewertet · 3 Analysen veraltet“ für die Statuszeile. */
    static String status(String scope, GlossaryAccess.Overview result) {
        return (scope == null || scope.isBlank() ? "" : scope + " · ")
                + Texts.count(result.rows().size(), "Fachbegriff", "Fachbegriffe") + " · "
                + Texts.count(result.pages(), "Seite", "Seiten") + " ausgewertet"
                + (result.stalePages() == 0 ? "" : " · " + Texts.count(result.stalePages(), "Analyse", "Analysen")
                + " veraltet");
    }

    /** Je Zeile Begriff, Einordnung, Wichtigkeit, Status, Herkunft, Erläuterung, leer; unsichtbar die Kennung. */
    static List<List<String>> rows(List<GlossaryAccess.Row> rows) {
        List<List<String>> cells = new ArrayList<>(rows.size());
        for (GlossaryAccess.Row row : rows) {
            Entry entry = row.entry();
            List<String> line = new ArrayList<>(List.of(entry.term(), categoryWord(row.category()),
                    importance(row.importance()), Relation.statusText(entry.status().name()),
                    Relation.originText(entry.origin().name()),
                    entry.explanation() == null ? "" : entry.explanation(), ""));
            line.add(entry.id());
            cells.add(List.copyOf(line));
        }
        return cells;
    }

    /** „12 Seiten“ (die Seitenstreuung zählt zuerst); „–“ ohne Wichtigkeit in der Auswahl. Alles im Tooltip. */
    static String importance(GlossaryDerivation.Importance importance) {
        return importance == null ? NO_IMPORTANCE : Texts.count(importance.pages(), "Seite", "Seiten");
    }

    /** Die ganze Wichtigkeit als Tooltip der Spalte. */
    static String importanceTip(GlossaryDerivation.Importance importance) {
        if (importance == null) {
            return "Keine Wichtigkeit: der Begriff kommt in den analysierten Seiten der Auswahl nicht vor (oder von "
                    + "Hand angelegt)";
        }
        return "Auf " + Texts.count(importance.pages(), "Seite", "Seiten") + ", "
                + Texts.count(importance.mentions(), "Erwähnung", "Erwähnungen") + ", handelnd "
                + importance.asActor() + "×, betroffen " + importance.asAffected() + "×, als Träger "
                + importance.asBearer() + "×";
    }

    static String categoryWord(GlossaryDerivation.Category category) {
        return switch (category) {
            case FACHLICH -> "fachlich";
            case ROLLE -> "Rolle";
            case UNKLAR -> "unklar";
            case TECHNISCH -> "Technik";
        };
    }

    private static String categoryTip(GlossaryDerivation.Category category) {
        return switch (category) {
            case FACHLICH -> "Fachliche Begriffe zeigen oder ausblenden";
            case ROLLE -> "Rollen und Stellen zeigen oder ausblenden";
            case UNKLAR -> "Noch nicht eingeordnete Begriffe zeigen oder ausblenden";
            case TECHNISCH -> "Technische Begriffe zeigen oder ausblenden (standardmäßig aus)";
        };
    }

    private static Color categoryTone(GlossaryDerivation.Category category) {
        return switch (category) {
            case FACHLICH -> Comic.PETROL;
            case ROLLE -> Comic.BLUE;
            case UNKLAR -> Comic.STANDING_UNCLEAR;
            case TECHNISCH -> Comic.MUTED;
        };
    }

    static String statusWord(ReviewStatus status) {
        return switch (status) {
            case PROPOSED -> "vorgeschlagen";
            case CONFIRMED -> "bestätigt";
            case REJECTED -> "abgelehnt";
        };
    }

    private static String statusTip(ReviewStatus status) {
        return switch (status) {
            case PROPOSED -> "Nur Vorschläge zeigen";
            case CONFIRMED -> "Nur bestätigte zeigen";
            case REJECTED -> "Nur abgelehnte zeigen (Sperrliste)";
        };
    }

    private static Color statusTone(ReviewStatus status) {
        return switch (status) {
            case PROPOSED -> Comic.ORANGE;
            case CONFIRMED -> Comic.STANDING_CURRENT;
            case REJECTED -> Comic.MUTED;
        };
    }

    /** Ein schmaler senkrechter Strich zwischen den Chip-Gruppen. */
    private static final class ChipGap extends JComponent {

        ChipGap() {
            setPreferredSize(new java.awt.Dimension(9, 20));
        }

        @Override
        protected void paintComponent(java.awt.Graphics g) {
            g.setColor(Comic.LINE);
            g.drawLine(4, 3, 4, getHeight() - 3);
        }
    }

    /** Die Symbole einer Zeile: Bestätigen, Ablehnen bzw. Ablehnung zurücknehmen, Erläuterung, Entfernen. */
    List<RowActions.Action> actions(Entry entry) {
        if (entry == null) {
            return List.of();
        }
        boolean confirmed = entry.status() == ReviewStatus.CONFIRMED;
        boolean rejected = entry.status() == ReviewStatus.REJECTED;
        boolean manual = entry.origin() == RelationOrigin.MANUAL;
        return List.of(
                new RowActions.Action(Glyph.ACCEPT, confirmed ? ALREADY_CONFIRMED : CONFIRM, !confirmed,
                        () -> review(CONFIRM, entry)),
                rejected ? new RowActions.Action(Glyph.REFRESH, RESTORE, true, () -> review(RESTORE, entry))
                        : new RowActions.Action(Glyph.CANCEL, REJECT, true, () -> review(REJECT, entry)),
                new RowActions.Action(Glyph.PENCIL, EDIT, true, () -> askExplanation(entry)),
                new RowActions.Action(Glyph.TRASH, manual ? REMOVE : ONLY_MANUAL, manual, () -> {
                    if (confirm.test(REMOVE, removeQuestion(entry))) {
                        review(REMOVE, entry);
                    }
                }));
    }

    static String removeQuestion(Entry entry) {
        return "Wirklich „" + entry.term() + "“ aus dem Fachglossar entfernen?";
    }

    /** Bestätigen, Ablehnen, Zurücknehmen oder Entfernen mit dem gezeigten Stand; der Core prüft Revision und Rechte. */
    void review(String action, Entry entry) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        keepSelected = entry.id();
        Background.run(() -> {
            GlossaryAccess g = glossary.get();
            return switch (action) {
                case CONFIRM -> g.confirm(entry.id(), entry.revision());
                case REJECT -> g.reject(entry.id(), entry.revision());
                case RESTORE -> g.restore(entry.id(), entry.revision());
                default -> g.remove(entry.id(), entry.revision()).orElse(null);
            };
        }, done -> {
            notice(REMOVE.equals(action) && done == null ? "„" + entry.term() + "“ entfernt"
                    : "„" + entry.term() + "“: " + Relation.statusText(done.status().name()), Severity.INFO);
            refine();
        }, failure -> {
            afterShow = notice(failure instanceof GlossaryAccess.ConflictException ? CONFLICT
                    : action + " nicht möglich: " + failure.getMessage(), Severity.ERROR);
            refine();
        });
    }

    /** „Alle Quellen“, „handbuch“ oder „handbuch · DOK“. */
    static String scopeText(Entry entry) {
        if (entry.sourceId() == null) {
            return "Alle Quellen";
        }
        return entry.spaceKey() == null ? entry.sourceId() : entry.sourceId() + " · " + entry.spaceKey();
    }

    // ---- Dialoge

    /** „Fachbegriff hinzufügen …“: Begriff, Geltungsbereich (Quelle, Space), Erläuterung. */
    private void askNew() {
        if (context == null || access.get().isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, ADD);
        JTextField term = Comic.field(24);
        SourcePicker scope = new SourcePicker(context, true, true);
        JTextArea explanation = explanationArea("");
        JLabel problem = Comic.muted(" ");
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Fachbegriff"));
        form.add(term);
        form.add(Comic.caption("Gilt für Quelle"));
        form.add(scope.sourceBox());
        form.add(Comic.caption("Gilt für Space (leer: alle)"));
        form.add(scope.spaceBox());
        form.add(Comic.caption("Erläuterung (optional)"));
        form.add(Comic.scroll(explanation));
        form.add(problem);
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Anlegen", "Den Fachbegriff anlegen; er gilt sofort als bestätigt");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            String invalid = invalidTerm(term.getText());
            if (invalid != null) {
                problem.setText(invalid);
                return;
            }
            dialog.dispose();
            String space = scope.spaceKey();
            create(new GlossaryAccess.NewEntry(term.getText().trim(), scope.sourceId(),
                    space == null || space.isBlank() ? null : space, blankToNull(explanation.getText())));
        });
        Dialogs.show(dialog, ADD, form, cancel, ok);
    }

    /** {@code null}, wenn der Begriff taugt, sonst der Grund in Worten. */
    static String invalidTerm(String term) {
        return term == null || term.isBlank() ? "Den Fachbegriff angeben." : null;
    }

    static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text.trim();
    }

    /** Legt den Eintrag im Core an (manuell, bestätigt) und wählt ihn danach. */
    void create(GlossaryAccess.NewEntry entry) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        Background.run(() -> glossary.get().add(entry), created -> {
            keepSelected = created.id();
            notice("„" + created.term() + "“ angelegt", Severity.INFO);
            refine();
        }, failure -> directory.message(notice("Anlegen nicht möglich: " + failure.getMessage(), Severity.ERROR)));
    }

    /** „Erläuterung bearbeiten“: nur der Text; Begriff und Geltungsbereich bleiben. */
    private void askExplanation(Entry entry) {
        askExplanation(entry, null);
    }

    /**
     * „Erläuterung bearbeiten“; mit {@code draft} liegt der Vorschlag zur Begriffsbestimmung als Entwurf im Feld,
     * gespeichert wird erst mit „Speichern“.
     */
    private void askExplanation(Entry entry, String draft) {
        if (context == null || access.get().isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, EDIT);
        JTextArea explanation = explanationArea(draft != null ? draft
                : entry.explanation() == null ? "" : entry.explanation());
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.caption("Fachbegriff"));
        form.add(Comic.text(entry.term()));
        form.add(Comic.caption("Erläuterung"));
        form.add(Comic.scroll(explanation));
        ComposerButton cancel = Comic.button("Abbrechen", null);
        ComposerButton ok = Comic.primary("Speichern", "Die Erläuterung übernehmen");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> {
            dialog.dispose();
            explain(entry, blankToNull(explanation.getText()));
        });
        Dialogs.show(dialog, EDIT, form, cancel, ok);
    }

    void explain(Entry entry, String text) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        keepSelected = entry.id();
        Background.run(() -> glossary.get().explanation(entry.id(), text, entry.revision()), saved -> {
            notice("Erläuterung zu „" + saved.term() + "“ gespeichert", Severity.INFO);
            refine();
        }, failure -> {
            afterShow = notice(failure instanceof GlossaryAccess.ConflictException ? CONFLICT
                    : "Speichern nicht möglich: " + failure.getMessage(), Severity.ERROR);
            refine();
        });
    }

    private static JTextArea explanationArea(String text) {
        JTextArea area = new JTextArea(text, 4, 30);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        Comic.style(area);
        return area;
    }

    /**
     * „Fachbegriffe ermitteln“ für die Auswahl links: eine Quelle, ein Space oder eine Seite samt Unterseiten (D4.2;
     * der Lauf ergänzt dann nur), „Wissensquellen“ für alle Quellen. Startet auch ohne Modell (Phase „Begriffe
     * ableiten“); je Quelle ein Auftrag im Hintergrund, das Fenster bleibt bedienbar.
     */
    void detect() {
        ExportPanel.Selection chosen = selection;
        if (chosen.global()) {
            detect(null, null, null, null);
        } else {
            detect(chosen.sourceId(), chosen.spaceKey(), chosen.spaceKey() == null ? null : chosen.pageId(),
                    chosen.title());
        }
    }

    void detect(String sourceId, String spaceKey) {
        detect(sourceId, spaceKey, null, null);
    }

    /** „Fachbegriffe ermitteln für „Titel“ samt Unterseiten“ bzw. ohne Seite nur der Name des Laufs. */
    static String detectName(String pageId, String title) {
        return pageId == null ? DETECT
                : DETECT + " für „" + (title == null || title.isBlank() ? "Seite " + pageId : title)
                + "“ samt Unterseiten";
    }

    void detect(String sourceId, String spaceKey, String pageId, String title) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty() || !jobs.isEmpty() || starting) {
            return;
        }
        observe(glossary.get());
        starting = true;
        updateDetect();
        String name = detectName(pageId, title);
        notice(name + " wird gestartet …", Severity.INFO);
        Background.run(() -> glossary.get().classify(sourceId, spaceKey, pageId), started -> {
            starting = false;
            if (started.isEmpty()) {
                updateDetect();
                return;
            }
            started.forEach(status -> jobs.put(status.id(), status));
            updateDetect();
            notice(pageId == null ? STARTED : name + STARTED.substring(DETECT.length()), Severity.INFO);
            if (context != null && context.navigator() != null) {
                context.navigator().followJobs();
            }
            catchUp(glossary.get(), started);
        }, failure -> {
            starting = false;
            updateDetect();
            notice(failure instanceof GlossaryAccess.NotAllowedException ? failure.getMessage()
                    : DETECT + " nicht möglich: " + failure.getMessage(), Severity.ERROR);
        });
    }

    /**
     * Die Anmeldung bei den Aufträgen steht schon ({@link #observe}); einmal den Stand der gestarteten Aufträge lesen,
     * damit zwischen Start und erster Meldung nichts verloren geht.
     */
    private void catchUp(GlossaryAccess glossary, List<JobStatus> started) {
        List<String> ids = started.stream().map(JobStatus::id).toList();
        Map<String, JobStatus> before = new HashMap<>(jobs);
        Background.run(() -> {
            List<JobStatus> states = new ArrayList<>();
            for (String id : ids) {
                states.add(glossary.classification(id));
            }
            return states;
        }, states -> {
            for (JobStatus state : states) {
                // Hat eine Meldung den Stand inzwischen ersetzt, ist sie jünger als das Gelesene.
                if (jobs.get(state.id()) == before.get(state.id())) {
                    track(state);
                }
            }
        }, failure -> finish(failure));
    }

    /** Merkt sich den Stand eines eigenen Laufs; sind alle zu Ende, Rückmeldung in die Statuszeile. */
    void track(JobStatus status) {
        if (!jobs.containsKey(status.id())) {
            return;
        }
        jobs.put(status.id(), status);
        if (jobs.values().stream().allMatch(s -> s.state().terminal())) {
            finish(null);
        }
    }

    private void finish(Throwable failure) {
        List<JobStatus> ended = List.copyOf(jobs.values());
        jobs.clear();
        updateDetect();
        if (failure != null) {
            notice(DETECT + ": Stand nicht lesbar: " + failure.getMessage(), Severity.ERROR);
            return;
        }
        JobState worst = ended.stream().map(JobStatus::state).anyMatch(s -> s == JobState.FAILED)
                ? JobState.FAILED : ended.stream().map(JobStatus::state).anyMatch(s -> s == JobState.CANCELLED)
                ? JobState.CANCELLED : JobState.COMPLETED;
        // Die volle Sicht holt jobChanged: das Ende von „Fachbegriffe ermitteln“ gehört zu REREADS.
        notice(outcomeText(ended), worst == JobState.FAILED ? Severity.ERROR
                : worst == JobState.CANCELLED ? Severity.WARN : Severity.INFO);
    }

    /** Die Rückmeldung am Ende: fehlgeschlagen vor abgebrochen vor abgeschlossen, mit den Summen aller Aufträge. */
    static String outcomeText(List<JobStatus> ended) {
        long processed = ended.stream().mapToLong(s -> s.progress().processed()).sum();
        long writtenCount = ended.stream().mapToLong(s -> s.progress().written()).sum();
        String written = Texts.count(writtenCount, "Eintrag", "Einträge");
        Optional<JobStatus> failed = ended.stream().filter(s -> s.state() == JobState.FAILED).findFirst();
        if (failed.isPresent()) {
            JobStatus status = failed.get();
            return DETECT + " fehlgeschlagen" + (ended.size() > 1 ? " (Quelle " + status.sourceId() + ")" : "")
                    + ": " + (status.error() == null ? "" : status.error().message())
                    + " · Vorgeschlagenes bleibt, Entscheidungen unverändert";
        }
        if (ended.stream().anyMatch(s -> s.state() == JobState.CANCELLED)) {
            return DETECT + " abgebrochen; " + written + " " + (writtenCount == 1 ? "bleibt" : "bleiben")
                    + ", Entscheidungen unverändert";
        }
        return DETECT + " abgeschlossen: " + written + " aus "
                + Texts.count(processed, "geprüften Begriff", "geprüften Begriffen");
    }

    // ---- Overlay

    static final String CATEGORIZE = "Einordnen:";
    static final String DEFINITION = "Vorschlag zur Begriffsbestimmung";
    static final String DEFINITION_LINK = "Zum Beleg der Begriffsbestimmung";
    static final String DRAFT = "Den Vorschlag als Entwurf ins Erläuterungsfeld legen; gespeichert wird erst mit "
            + "„Speichern“";
    static final String UNREADABLE = "Kontext nicht mehr lesbar (Seite geändert oder nicht im Cache)";

    /**
     * Overlay am Mausklick: Erläuterung, Einordnung mit Grund, Herkunft und Stand, Wichtigkeit, Verhalten,
     * Eigenschaften und Ist-Beziehungen aus der gehaltenen Sicht ({@code View#term}, kein neues Rechnen; Vorstufe
     * getrennt), Belege mit Kontext und das Protokoll. Darunter Einordnen als Pillen, der Vorschlag zur
     * Begriffsbestimmung als eigene Zeile und die Aktionen als Icon-Knöpfe. Höchsthöhe mit Rollen im Inneren.
     */
    private void showDetails(Entry entry, int x, int y) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        GlossaryAccess.Row row = rowById.get(entry.id());
        Optional<GlossaryDerivation.Term> term = glossary.get().term(overview, entry.normalized());
        List<RelationEvidence> evidence = entry.evidence().stream().limit(EVIDENCE_SHOWN).toList();
        RelationEvidence definition = row == null ? null : row.definition();
        Background.run(() -> {
            List<String> lines = new ArrayList<>();
            for (RelationEvidence e : evidence) {
                Optional<TermQueries.Snippet> snippet = glossary.get().snippet(e, SNIPPET_CHARS);
                String title = glossary.get().pageTitle(e.sourceId(), e.pageId()).orElse(e.pageId());
                lines.add((evidence.size() == 1 ? "" : (lines.size() + 1) + ". ") + title + ": "
                        + snippet.map(GlossaryTab::snippetText).orElse(UNREADABLE));
            }
            String sentence = definition == null ? null : glossary.get().snippet(definition, SNIPPET_CHARS)
                    .map(GlossaryTab::snippetText).orElse(null);
            List<GlossaryAccess.History> history = glossary.get().history(entry.id());
            return new Details(lines, sentence, history);
        }, details -> {
            if (!directory.table().isShowing()) {
                // Inzwischen ist ein anderer Reiter oder Bereich zu sehen; ein Fenster dort hätte keinen Anker.
                return;
            }
            InfoPopup popup = new InfoPopup().text(entry.term(), overlayText(entry, row, term.orElse(null),
                    details.lines(), details.history()));
            popup.controls(overlayControls(popup, entry, row, details.definition()));
            for (int i = 0; i < evidence.size(); i++) {
                RelationEvidence e = evidence.get(i);
                // Kurze Linktexte, damit bis zu drei nebeneinander in die Breite des Fensters passen.
                popup.link(evidence.size() == 1 ? "Zur Seite" : "Zu Beleg " + (i + 1), () -> open(e));
            }
            if (definition != null) {
                popup.link(DEFINITION_LINK, () -> open(definition));
            }
            this.popup = popup;
            popup.showAt(directory.table(), x, y);
        }, failure -> notice("Einzelheiten nicht lesbar: " + failure.getMessage(), Severity.ERROR));
    }

    private void open(RelationEvidence evidence) {
        if (context != null && context.navigator() != null) {
            context.navigator().openPage(evidence.sourceId(), evidence.pageId());
        }
    }

    private record Details(List<String> lines, String definition, List<GlossaryAccess.History> history) {
    }

    /** Einordnen als Pillen, der Vorschlag zur Begriffsbestimmung als eigene Zeile, darunter die Aktionen. */
    JComponent overlayControls(InfoPopup popup, Entry entry, GlossaryAccess.Row row, String definition) {
        JPanel rows = new JPanel();
        rows.setOpaque(false);
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        JPanel categorize = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        categorize.setOpaque(false);
        categorize.add(Comic.muted(CATEGORIZE));
        GlossaryDerivation.Category current = row == null ? GlossaryDerivation.Category.UNKLAR : row.category();
        for (GlossaryDerivation.Category category : List.of(GlossaryDerivation.Category.FACHLICH,
                GlossaryDerivation.Category.ROLLE, GlossaryDerivation.Category.UNKLAR,
                GlossaryDerivation.Category.TECHNISCH)) {
            CountChip chip = CountChip.choice(categoryWord(category), categoryTone(category));
            chip.setSelected(category == current);
            chip.setToolTipText("Von Hand als „" + categoryWord(category) + "“ einordnen; danach ändert kein Lauf "
                    + "die Einordnung mehr");
            chip.addActionListener(e -> {
                if (category == current) {
                    chip.setSelected(true);
                    return;
                }
                popup.setVisible(false);
                categorize(entry, category);
            });
            categorize.add(chip);
        }
        categorize.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(categorize);
        if (definition != null) {
            JPanel proposal = new JPanel(new java.awt.BorderLayout(6, 0));
            proposal.setOpaque(false);
            proposal.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
            proposal.add(Comic.wrapped(DEFINITION + ": „" + definition + "“", Comic.TEXT, 12f),
                    java.awt.BorderLayout.CENTER);
            IconButton draft = new IconButton(Glyph.PENCIL, DRAFT);
            draft.addActionListener(e -> {
                popup.setVisible(false);
                askExplanation(entry, definition);
            });
            proposal.add(draft, java.awt.BorderLayout.EAST);
            proposal.setAlignmentX(Component.LEFT_ALIGNMENT);
            rows.add(proposal);
        }
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        actions.setOpaque(false);
        actions.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 0, 0, 0));
        for (RowActions.Action action : actions(entry)) {
            IconButton button = new IconButton(action.icon(), action.tooltip());
            button.setEnabled(action.enabled());
            button.addActionListener(e -> {
                popup.setVisible(false);
                action.run().run();
            });
            actions.add(button);
        }
        actions.setAlignmentX(Component.LEFT_ALIGNMENT);
        rows.add(actions);
        return rows;
    }

    /** Ordnet von Hand ein; der Core prüft Revision und Rechte. */
    void categorize(Entry entry, GlossaryDerivation.Category category) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        keepSelected = entry.id();
        Background.run(() -> glossary.get().categorize(entry.id(), category, entry.revision()), done -> {
            notice("„" + entry.term() + "“ eingeordnet als „" + categoryWord(category) + "“", Severity.INFO);
            refine();
        }, failure -> {
            afterShow = notice(failure instanceof GlossaryAccess.ConflictException ? CONFLICT
                    : "Einordnen nicht möglich: " + failure.getMessage(), Severity.ERROR);
            refine();
        });
    }

    /** Der Text des Overlays; jede Angabe in Worten, Belege und Protokoll am Ende. */
    static String overlayText(Entry entry, GlossaryAccess.Row row, GlossaryDerivation.Term term,
                              List<String> evidenceLines, List<GlossaryAccess.History> history) {
        StringBuilder text = new StringBuilder();
        if (entry.explanation() != null && !entry.explanation().isBlank()) {
            text.append(entry.explanation().trim()).append("\n\n");
        }
        if (row != null) {
            text.append("Einordnung: ").append(categorized(row.category(), row.classification())).append('\n');
        }
        text.append("Gilt für: ").append(scopeText(entry)).append('\n');
        text.append("Herkunft: ").append(Relation.originText(entry.origin().name()));
        if (entry.generation() != null && !entry.generation().isBlank()) {
            text.append(" (").append(entry.generation()).append(')');
        }
        text.append('\n');
        text.append("Status: ").append(statusWord(entry.status()));
        if (entry.reviewedAt() != null) {
            text.append(" am ").append(minute(entry.reviewedAt()));
        }
        text.append('\n');
        if (row != null) {
            text.append("Wichtigkeit: ").append(row.importance() == null ? "keine in der Auswahl"
                    : importanceTip(row.importance())).append('\n');
        }
        if (term != null) {
            termLines(term).forEach(line -> text.append(line).append('\n'));
        }
        if (entry.evidence().isEmpty()) {
            text.append("Keine Belegstellen.");
        } else {
            text.append(Texts.count(entry.evidence().size(), "Beleg", "Belege"));
            if (entry.evidenceStale()) {
                text.append(" · Beleg veraltet: die Fundstellen haben sich geändert; der Eintrag bleibt");
            }
            for (String line : evidenceLines) {
                text.append("\n• ").append(line);
            }
            if (entry.evidence().size() > evidenceLines.size()) {
                text.append("\n… und ").append(entry.evidence().size() - evidenceLines.size()).append(" weitere");
            }
        }
        if (history != null && !history.isEmpty()) {
            text.append("\n\nProtokoll:");
            for (GlossaryAccess.History step : history) {
                text.append("\n• ").append(historyLine(step));
            }
        }
        return text.toString();
    }

    /**
     * „unklar · nach Regel (kein Technik- oder Rollenwort)“, „fachlich · von Hand“, „fachlich · von der KI
     * (Rechtsbegriff)“; ohne Einordnung „unklar (noch nicht eingeordnet)“. Eine unbekannte Kennung steht ohne Klammer.
     */
    static String categorized(GlossaryDerivation.Category category,
                              com.aresstack.spaceharvester.application.glossary.GlossaryEntry.Classification
                                      classification) {
        if (classification == null) {
            return categoryWord(GlossaryDerivation.Category.UNKLAR) + " (noch nicht eingeordnet)";
        }
        String reason = classification.reason();
        return categoryWord(category) + " · " + switch (classification.by()) {
            case MANUAL -> "von Hand";
            case AI -> "von der KI" + bracketed(aiReason(reason));
            case RULE -> "nach Regel" + bracketed(ruleReason(category, reason));
        };
    }

    private static String bracketed(String text) {
        return text == null ? "" : " (" + text + ")";
    }

    /** Der Grund der Regel in Worten; {@code null} bei einer unbekannten Kennung (dann ohne Klammer). */
    static String ruleReason(GlossaryDerivation.Category category, String reason) {
        return switch (String.valueOf(reason)) {
            case "TECHNICAL_HEAD" -> "Technikwort am Wortende";
            case "TECHNICAL_SHAPE" -> "Schreibweise eines Bezeichners";
            case "ROLE_HEAD" -> "Rollenwort am Wortende";
            case "ROLE_OF_PERSON" -> "als Rolle einer Person genannt";
            // Nur ein unklarer Begriff hat keinen Grund der Regel; bei Technik oder Rolle nie diesen Satz.
            default -> category == GlossaryDerivation.Category.UNKLAR ? "kein Technik- oder Rollenwort" : null;
        };
    }

    /** Der Grund der KI in Worten; {@code null} bei einer unbekannten Kennung (dann ohne Klammer). */
    static String aiReason(String reason) {
        return switch (String.valueOf(reason)) {
            case "DOMAIN_OBJECT" -> "fachlicher Gegenstand";
            case "DOMAIN_PROCESS" -> "fachlicher Ablauf";
            case "LEGAL_TERM" -> "Rechtsbegriff";
            case "PERSON_ROLE" -> "Rolle einer Person";
            case "ORGANISATION" -> "Stelle oder Organisation";
            case "SYSTEM_COMPONENT" -> "technisches System";
            case "DATA_FORMAT" -> "Datenformat";
            case "GENERIC_WORD" -> "allgemeines Wort";
            case "CONTEXT_UNCLEAR" -> "aus den Belegen nicht zu entscheiden";
            default -> null;
        };
    }

    private static final java.time.format.DateTimeFormatter MINUTE =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    /** „10.11.2026 08:00“ in der Zeitzone des Rechners. */
    static String minute(java.time.Instant at) {
        return MINUTE.format(at.atZone(java.time.ZoneId.systemDefault()));
    }

    /** Wer im Protokoll gehandelt hat: „von der Regel“, „von der KI“ oder „von angelo“, nie interne Kennungen. */
    static String actorText(String actor) {
        if (actor == null || actor.isBlank()) {
            return "";
        }
        if (actor.startsWith("rule:")) {
            return "von der Regel";
        }
        if (actor.startsWith("ai:")) {
            return "von der KI";
        }
        int colon = actor.indexOf(':');
        if (colon < 0) {
            // Ohne Doppelpunkt ist es kein Benutzer (Benutzer: „ART:name[@über]“), sondern eine interne Kennung.
            return "";
        }
        int at = actor.indexOf('@');
        String principal = actor.substring(colon + 1, at > colon ? at : actor.length());
        return principal.isBlank() ? "" : "von " + principal;
    }

    /** Der Protokollschritt, wenn die KI-Freigabe einer Seite endet: ohne Handelnden. */
    static final String UNCLASSIFIED = "Einordnung der KI zurückgenommen (Seite nicht mehr für die KI freigegeben)";

    /** Eine Protokollzeile: „10.11.2026 08:00 · von der Regel vorgeschlagen“. */
    static String historyLine(GlossaryAccess.History step) {
        if ("unclassify".equals(step.action())) {
            return minute(step.at()) + " · " + UNCLASSIFIED;
        }
        String who = actorText(step.actor());
        return minute(step.at()) + " · " + (who.isEmpty() ? "" : who + " ") + actionText(step.action());
    }

    /** Was im Protokoll geschah, in Worten. */
    static String actionText(String action) {
        return switch (String.valueOf(action)) {
            case "create", "add" -> "angelegt";
            case "confirm" -> "bestätigt";
            case "reject" -> "abgelehnt";
            case "restore" -> "Ablehnung zurückgenommen";
            case "explain" -> "Erläuterung geändert";
            case "categorize", "classify" -> "eingeordnet";
            case "remove" -> "entfernt";
            case "propose" -> "vorgeschlagen";
            case "unsupport" -> "nicht mehr gestützt";
            case "withdraw" -> "zurückgezogen";
            case "stale" -> "als „Beleg veraltet“ markiert";
            case "purge" -> "bereinigt";
            default -> "geändert";
        };
    }

    /**
     * Verhalten, Eigenschaften und Ist-Beziehungen aus dem Original, je mit Anzahl; verneinte Verben getrennt. Was nur
     * die Vorstufe (KI) kennt, steht getrennt darunter, denn nur das Original zählt für die Wichtigkeit.
     */
    static List<String> termLines(GlossaryDerivation.Term term) {
        List<String> lines = new ArrayList<>();
        for (ParsedStatementsOrigin origin : ParsedStatementsOrigin.values()) {
            List<String> parts = new ArrayList<>();
            addUses(parts, "Handelt", term.acts(), origin.value, false);
            addUses(parts, "Handelt nicht", term.acts(), origin.value, true);
            addUses(parts, "Ist betroffen", term.undergoes(), origin.value, false);
            addUses(parts, "Ist nicht betroffen", term.undergoes(), origin.value, true);
            addUses(parts, "Eigenschaften", term.properties(), origin.value, false);
            addRelations(parts, true, term.isA(), origin.value);
            addRelations(parts, false, term.instances(), origin.value);
            if (parts.isEmpty()) {
                continue;
            }
            if (origin == ParsedStatementsOrigin.VORSTUFE) {
                lines.add("Aus der Vorstufe (KI), zählt nicht für die Wichtigkeit:");
                parts.forEach(part -> lines.add("   " + part));
            } else {
                lines.addAll(parts);
            }
        }
        return lines;
    }

    /**
     * Ist-Beziehungen je Art: „Ist ein (Begriffsbestimmung)“, „Ist (Rolle)“, „Ist gleich (Gleichsetzung)“; eine
     * unbekannte Art (alte Analyse) ohne Zusatz. Eine Gleichsetzung wird nur gezeigt, sie führt nichts zusammen.
     */
    private static void addRelations(List<String> parts, boolean isA, List<GlossaryDerivation.Use> uses,
                                     com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin origin) {
        Map<String, List<String>> byLabel = new LinkedHashMap<>();
        for (GlossaryDerivation.Use use : uses) {
            if (use.origin() != origin) {
                continue;
            }
            String label = relationLabel(isA, use.relation());
            byLabel.computeIfAbsent(label, key -> new ArrayList<>()).add(use.lemma() + " " + use.count() + "×");
        }
        byLabel.forEach((label, words) -> parts.add(label + ": " + String.join(", ", words)));
    }

    static String relationLabel(boolean isA, GlossaryDerivation.Relation relation) {
        if (relation == null || relation == GlossaryDerivation.Relation.UNBEKANNT) {
            return isA ? "Ist ein" : "Beispiele";
        }
        return switch (relation) {
            case BESTIMMUNG -> isA ? "Ist ein (Begriffsbestimmung)" : "Beispiele (Begriffsbestimmung)";
            case ROLLE -> isA ? "Ist (Rolle)" : "In dieser Rolle";
            case GLEICHSETZUNG -> isA ? "Ist gleich (Gleichsetzung)" : "Gleichgesetzt mit";
            case UNBEKANNT -> isA ? "Ist ein" : "Beispiele";
        };
    }

    private enum ParsedStatementsOrigin {
        ORIGINAL(com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.ORIGINAL),
        VORSTUFE(com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin.VORSTUFE);

        final com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin value;

        ParsedStatementsOrigin(com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin value) {
            this.value = value;
        }
    }

    private static void addUses(List<String> parts, String label, List<GlossaryDerivation.Use> uses,
                                com.aresstack.spaceharvester.application.knowledge.ParsedStatements.Origin origin,
                                boolean negated) {
        List<String> words = uses.stream().filter(use -> use.origin() == origin && use.negated() == negated)
                .map(use -> use.lemma() + " " + use.count() + "×").toList();
        if (!words.isEmpty()) {
            parts.add(label + ": " + String.join(", ", words));
        }
    }

    static String snippetText(TermQueries.Snippet s) {
        return ((s.truncatedBefore() ? "…" : "") + s.before() + s.match() + s.after()
                + (s.truncatedAfter() ? "…" : "")).replaceAll("\\s+", " ").trim();
    }

    // ---- Hilfen

    /** Meldet {@code text} in der Statuszeile des Hauptfensters und gibt ihn zurück (für den Hinweis unter der Liste). */
    private String notice(String text, Severity severity) {
        if (context != null) {
            context.events().publish(new StatusMessageEvent(text, NOTICE_MS, severity));
        }
        return text;
    }

    private Entry entryAtModel(int modelRow) {
        if (modelRow < 0 || modelRow >= directory.table().getModel().getRowCount()) {
            return null;
        }
        List<String> cells = directory.visibleRow(modelRow);
        return cells.size() > COLUMNS.length ? byId.get(cells.get(COLUMNS.length)) : null;
    }

    Entry entryAtView(int viewRow) {
        if (viewRow < 0 || viewRow >= directory.table().getRowCount()) {
            return null;
        }
        return entryAtModel(directory.table().convertRowIndexToModel(viewRow));
    }

    private GlossaryAccess.Row rowAtView(int viewRow) {
        Entry entry = entryAtView(viewRow);
        return entry == null ? null : rowById.get(entry.id());
    }

    private Entry selectedEntry() {
        return entryAtView(directory.table().getSelectedRow());
    }

    /**
     * Grau hinterlegt: abgelehnte; blass: ohne Wichtigkeit in der Auswahl; die Wichtigkeit mit allen Teilzahlen als
     * Tooltip. Alle Spalten linksbündig.
     */
    private final class GlossaryRenderer extends DefaultTableCellRenderer {

        GlossaryRenderer() {
            setHorizontalAlignment(SwingConstants.LEFT);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            Entry entry = entryAtView(row);
            boolean rejected = entry != null && entry.status() == ReviewStatus.REJECTED;
            int modelColumn = table.convertColumnIndexToModel(column);
            GlossaryAccess.Row line = rowAtView(row);
            // Ohne Wichtigkeit in der Auswahl blass hinten (der Core sortiert sie dorthin).
            boolean pale = rejected || line != null && line.importance() == null;
            if (!selected) {
                setBackground(rejected ? TermDirectory.REJECTED_BACKGROUND : table.getBackground());
                setForeground(pale ? Comic.MUTED : table.getForeground());
            }
            setToolTipText(modelColumn == IMPORTANCE_COLUMN && line != null ? importanceTip(line.importance()) : null);
            return this;
        }
    }

    public JComponent component() {
        return page;
    }

    public IconButton detectButton() {
        return detect;
    }

    public IconButton addButton() {
        return add;
    }

    /** Der Stand für die Statuszeile des Hauptfensters (Anzahl, ausgewertete Seiten). */
    public void onStatus(java.util.function.Consumer<String> listener) {
        onStatus = listener;
    }

    // ---- für Tests

    TermDirectory directory() {
        return directory;
    }

    CountChip category(GlossaryDerivation.Category category) {
        return categories.get(category);
    }

    CountChip status(ReviewStatus status) {
        return statuses.get(status);
    }

    com.aresstack.spaceharvester.app.ui.PlaceholderField search() {
        return search;
    }

    BusyOverlay busy() {
        return busy;
    }

    void confirmWith(java.util.function.BiPredicate<String, String> confirm) {
        this.confirm = confirm;
    }

    InfoPopup popup() {
        return popup;
    }

    void click(int viewRow) {
        Entry entry = entryAtView(viewRow);
        if (entry != null) {
            showDetails(entry, 10, 10);
        }
    }

    IconButton statusFilter() {
        return statusFilter;
    }

    javax.swing.JPopupMenu statusMenu() {
        return statusMenu;
    }



    List<String> jobIds() {
        return List.copyOf(jobs.keySet());
    }

    boolean starting() {
        return starting;
    }

    List<Entry> shownEntries() {
        return shown.stream().map(GlossaryAccess.Row::entry).toList();
    }
}
