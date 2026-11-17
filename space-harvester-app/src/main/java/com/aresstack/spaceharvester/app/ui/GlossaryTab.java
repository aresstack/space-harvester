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
 * Der Reiter „Fachglossar“ unter Begriffe (ADR 0032, FL5b): die Glossareinträge zur Auswahl links im Explorer,
 * fertig sortiert, gefiltert und gezählt vom Core ({@code GlossaryOverview}). Oben rechts in der Reiterzeile stehen
 * die Suche mit ✕ und die Icon-Knöpfe „Fachbegriff hinzufügen“ und „Fachbegriffe ermitteln“ ({@link TermsView}); die
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
final class GlossaryTab {

    static final String DETECT = "Fachbegriffe ermitteln";
    static final String ADD = "Fachbegriff hinzufügen …";
    static final String DETECT_TIP = DETECT + ": Begriffe aus den analysierten Seiten der Auswahl links ableiten, "
            + "auch ohne Modell; mit Modell und KI-Freigabe ordnet danach das Modell ein. Läuft als Auftrag";
    static final String ADD_TIP = "Fachbegriff hinzufügen: einen Begriff von Hand anlegen; er gilt sofort als "
            + "bestätigt";
    static final String SEARCH_TIP = "Fachbegriffe suchen: Anfang des Begriffs oder eines seiner Wörter";
    static final String STATUS_FILTER_TIP = "Nach Status filtern: vorgeschlagen, bestätigt oder abgelehnt (Sperrliste)";
    static final String OPEN_AND_CONFIRMED = "vorgeschlagen und bestätigt";
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
    private final TermsView.Directory directory = new TermsView.Directory("Fachglossar", COLUMNS);
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
    GlossaryTab(AppContext context, Supplier<Optional<GlossaryAccess>> access) {
        this.context = context;
        this.access = access;
        directory.show(List.of(), NO_GLOSSARY);
        directory.withoutHead();
        busy.setText(BUSY);
        busy.onCancel(() -> cancelled.set(true));
        JTable table = directory.table();
        GlossaryRenderer renderer = new GlossaryRenderer();
        for (int c = 0; c < ACTIONS_COLUMN; c++) {
            table.getColumnModel().getColumn(c).setCellRenderer(renderer);
        }
        int[] widths = {200, 80, 90, 110, 80, 300};
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
            chip.addActionListener(e -> chooseStatus(chip.isSelected() ? status : null));
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
    JComponent tools() {
        return searchField;
    }

    /** Ein Status zur Zeit; {@code null} hebt den Filter auf (Vorschläge und bestätigte). */
    private void chooseStatus(ReviewStatus chosen) {
        statuses.forEach((status, chip) -> chip.setSelected(status == chosen));
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

    /** Der Filterknopf zeigt den gewählten Status im Tooltip und ist gefüllt, solange einer gewählt ist. */
    private void updateStatusFilter() {
        ReviewStatus chosen = query().status();
        String tip = chosen == null ? STATUS_FILTER_TIP : STATUS_FILTER_TIP + " · gewählt: " + statusWord(chosen);
        statusFilter.setToolTipText(tip);
        statusFilter.getAccessibleContext().setAccessibleName(tip);
    }

    /** Das Filtermenü mit denselben Zahlen wie die Chips; „vorgeschlagen und bestätigt“ hebt den Filter auf. */
    private void showStatusMenu() {
        buildStatusMenu();
        statusMenu.show(statusFilter, 0, statusFilter.getHeight());
    }

    void buildStatusMenu() {
        statusMenu.removeAll();
        ReviewStatus chosen = query().status();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        javax.swing.JRadioButtonMenuItem all = new javax.swing.JRadioButtonMenuItem(OPEN_AND_CONFIRMED,
                chosen == null);
        all.addActionListener(e -> chooseStatus(null));
        group.add(all);
        statusMenu.add(all);
        statuses.forEach((status, chip) -> {
            javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(
                    statusWord(status) + " " + chip.count(), status == chosen);
            item.addActionListener(e -> chooseStatus(status));
            group.add(item);
            statusMenu.add(item);
        });
    }

    /** Die Auswahl links hat sich geändert: volle Sicht, sofort wenn zu sehen, sonst beim nächsten Zeigen. */
    void selection(ExportPanel.Selection chosen) {
        selection = chosen == null ? ExportPanel.Selection.ALL : chosen;
        overview = null;
        reread(Pending.FULL);
    }

    /** Der Bereich wird sichtbar oder der Reiter gewählt: liest, was seit dem letzten Zeigen angefallen ist. */
    void shown() {
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

    /** Wie {@link #shown()}; so ruft {@link TermsView} beim Wechsel auf den Reiter. */
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
        ReviewStatus status = statuses.entrySet().stream().filter(entry -> entry.getValue().isSelected())
                .map(Map.Entry::getKey).findFirst().orElse(null);
        return new GlossaryOverview.Query(q.isEmpty() ? null : q, chosen.isEmpty() ? null : chosen, status);
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
        boolean filtered = query.q() != null || query.status() != null;
        directory.show(rows(result.rows()), filtered || !result.rows().isEmpty() ? NO_MATCH : EMPTY);
        onStatus.accept(status(result));
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

    /** „12 Fachbegriffe · 40 Seiten ausgewertet · 3 veraltet“ für die Statuszeile. */
    static String status(GlossaryAccess.Overview result) {
        return Texts.count(result.rows().size(), "Fachbegriff", "Fachbegriffe") + " · "
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
                new RowActions.Action(Glyph.TRASH, manual ? REMOVE : ONLY_MANUAL, manual,
                        () -> review(REMOVE, entry)));
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
        if (context == null || access.get().isEmpty()) {
            return;
        }
        javax.swing.JDialog dialog = Dialogs.dialog(directory, EDIT);
        JTextArea explanation = explanationArea(entry.explanation() == null ? "" : entry.explanation());
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
     * „Fachbegriffe ermitteln“ für die Auswahl links: eine Quelle oder ein Space (eine Seite zählt mit ihrem Space),
     * „Wissensquellen“ für alle Quellen. Startet auch ohne Modell (Phase „Begriffe ableiten“); je Quelle ein Auftrag
     * im Hintergrund, das Fenster bleibt bedienbar.
     */
    void detect() {
        ExportPanel.Selection chosen = selection;
        detect(chosen.global() ? null : chosen.sourceId(), chosen.global() ? null : chosen.spaceKey());
    }

    void detect(String sourceId, String spaceKey) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty() || !jobs.isEmpty() || starting) {
            return;
        }
        observe(glossary.get());
        starting = true;
        updateDetect();
        notice(DETECT + " wird gestartet …", Severity.INFO);
        Background.run(() -> glossary.get().classify(sourceId, spaceKey), started -> {
            starting = false;
            if (started.isEmpty()) {
                updateDetect();
                return;
            }
            started.forEach(status -> jobs.put(status.id(), status));
            updateDetect();
            notice(STARTED, Severity.INFO);
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

    // ---- Belege

    /** Fenster am Mausklick: Erläuterung, Herkunft und Stand, Belege mit Kontext, Links zur Seite. */
    private void showDetails(Entry entry, int x, int y) {
        Optional<GlossaryAccess> glossary = access.get();
        if (glossary.isEmpty()) {
            return;
        }
        List<RelationEvidence> evidence = entry.evidence().stream().limit(EVIDENCE_SHOWN).toList();
        Background.run(() -> {
            List<String> lines = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            for (RelationEvidence e : evidence) {
                Optional<TermQueries.Snippet> snippet = glossary.get().snippet(e, SNIPPET_CHARS);
                String title = glossary.get().pageTitle(e.sourceId(), e.pageId()).orElse(e.pageId());
                titles.add(title);
                lines.add((evidence.size() == 1 ? "" : (lines.size() + 1) + ". ") + title + ": "
                        + snippet.map(GlossaryTab::snippetText)
                        .orElse("Kontext nicht mehr lesbar (Seite geändert oder nicht im Cache)"));
            }
            return new Details(lines, titles);
        }, details -> {
            if (!directory.table().isShowing()) {
                // Inzwischen ist ein anderer Reiter oder Bereich zu sehen; ein Fenster dort hätte keinen Anker.
                return;
            }
            InfoPopup popup = new InfoPopup().text(entry.term(), detailText(entry, details.lines()));
            for (int i = 0; i < evidence.size(); i++) {
                RelationEvidence e = evidence.get(i);
                // Kurze Linktexte, damit bis zu drei nebeneinander in die Breite des Fensters passen; der Titel
                // steht in der Belegzeile darüber.
                popup.link(evidence.size() == 1 ? "Zur Seite" : "Zu Beleg " + (i + 1),
                        () -> context.navigator().openPage(e.sourceId(), e.pageId()));
            }
            popup.showAt(directory.table(), x, y);
        }, failure -> directory.message(notice("Belege nicht lesbar: " + failure.getMessage(), Severity.ERROR)));
    }

    private record Details(List<String> lines, List<String> titles) {
    }

    static String snippetText(TermQueries.Snippet s) {
        return ((s.truncatedBefore() ? "…" : "") + s.before() + s.match() + s.after()
                + (s.truncatedAfter() ? "…" : "")).replaceAll("\\s+", " ").trim();
    }

    /** Der Text des Fensters: Erläuterung, Geltungsbereich, Herkunft und Stand, Belege. */
    static String detailText(Entry entry, List<String> evidenceLines) {
        StringBuilder text = new StringBuilder();
        if (entry.explanation() != null && !entry.explanation().isBlank()) {
            text.append(entry.explanation().trim()).append("\n\n");
        }
        text.append("Gilt für: ").append(scopeText(entry)).append('\n');
        text.append("Herkunft: ").append(Relation.originText(entry.origin().name()));
        if (entry.generation() != null && !entry.generation().isBlank()) {
            text.append(" (").append(entry.generation()).append(')');
        }
        text.append(" · Stand: ").append(Relation.statusText(entry.status().name()));
        if (entry.reviewedAt() != null) {
            text.append(" am ").append(Texts.time(entry.reviewedAt()));
        }
        text.append('\n');
        if (entry.evidence().isEmpty()) {
            text.append("Keine Belegstellen im Begriffsindex.");
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
        return text.toString();
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

    /** Grau hinterlegt: abgelehnte; die Wichtigkeit mit allen Teilzahlen als Tooltip. Alle Spalten linksbündig. */
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
            if (!selected) {
                setBackground(rejected ? TermsView.REJECTED_BACKGROUND : table.getBackground());
                setForeground(rejected ? Comic.MUTED : table.getForeground());
            }
            int modelColumn = table.convertColumnIndexToModel(column);
            GlossaryAccess.Row line = rowAtView(row);
            setToolTipText(modelColumn == IMPORTANCE_COLUMN && line != null ? importanceTip(line.importance()) : null);
            return this;
        }
    }

    JComponent component() {
        return page;
    }

    IconButton detectButton() {
        return detect;
    }

    IconButton addButton() {
        return add;
    }

    /** Der Stand für die Statuszeile des Hauptfensters (Anzahl, ausgewertete Seiten). */
    void onStatus(java.util.function.Consumer<String> listener) {
        onStatus = listener;
    }

    // ---- für Tests

    TermsView.Directory directory() {
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
