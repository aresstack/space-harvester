package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.standing.BearerStates;
import com.aresstack.spaceharvester.application.standing.PageMark;
import com.aresstack.spaceharvester.application.standing.Standing;
import com.aresstack.spaceharvester.application.standing.StandingBasis;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingReason;
import com.aresstack.spaceharvester.application.standing.StatementKind;
import com.aresstack.spaceharvester.application.standing.StatusQuoEntry;
import com.aresstack.spaceharvester.application.standing.StatusQuoView;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reiter „Stand“ (ADR 0032, FL6): was in der Auswahl links gilt, geplant, unklar oder überholt ist. Stand, Gründe,
 * Sortierung und Zahlen kommen fertig aus dem Core ({@code StatusQuo}); die Ansicht filtert und zeigt nur.
 *
 * <p>Unterreiter „Aussagen“: oben rechts Zählchips je Stand (Klick filtert), Filter (Handlung, Zustand, nur sichere),
 * „nach Träger“ und die Suche mit ✕; darunter die Aussagen nach Stand gruppiert, „überholt“ grau am Ende und nie
 * versteckt. Verneinung und Verbindlichkeit stehen in jeder Zeile. Details am Mausklick als Overlay, Doppelklick
 * springt in den Lesetext. Die Liste baut nur sichtbare Zeilen auf (JList mit fester Zeilenhöhe); gerechnet wird nie
 * auf dem EDT. Nach neuem Lesen bleiben Auswahl, Filter und Rollposition stehen.
 */
final class StandingTab extends JPanel {

    static final String STATEMENTS = "Aussagen";
    static final String BUSY = "Stand wird gelesen …";
    static final String BY_BEARER_TIP = "Nach Träger bündeln: Zustände (Eigenschaften, Rollen) unter ihrem Träger";
    static final String AS_LIST_TIP = "Als Liste nach Stand zeigen";
    static final String FILTER_TIP = "Filter: Handlungen, Zustände, nur sichere Aussagen";
    static final String STALE_GROUP = "Entscheidungen ohne Aussage";
    static final String OPEN_TEXT = "Im Lesetext zeigen";
    static final String HELP = "Jede Aussage der Auswahl links hat einen Stand: „gilt“ (gilt jetzt), „geplant“ (soll "
            + "kommen, gilt noch nicht), „unklar“ (nicht bestimmbar, etwa auf einer Entwurfsseite) oder „überholt“ "
            + "(früher gültig oder verworfen; grau am Ende, nie versteckt).\n\n"
            + "Woher der Stand kommt, in dieser Reihenfolge: eine Entscheidung von Hand gewinnt immer; dann die "
            + "Vorstufe „Sätze vereinfachen“ (KI), wenn ihre Merkmale entscheiden; dann der Zeitbezug des Satzes "
            + "(Präsens, Futur, Präteritum, „nicht mehr“); eine überholte Seite (Label, Titel, Lage) oder ein Entwurf "
            + "zieht nur Aussagen mit, die selbst nichts über die Zeit sagen.\n\n"
            + "Verneinung und Verbindlichkeit („muss nicht“) ändern den Stand nie und stehen deshalb in jeder Zeile. "
            + "„unsicher“ heißt: Der Parser ist sich nicht sicher, die Aussage zählt nicht für den Stand der Seite.\n\n"
            + "Ein Klick auf eine Zeile zeigt Grund und Herkunft, ein Doppelklick springt an die Stelle im Lesetext. "
            + "Die Zahlen oben zählen alle Aussagen der Auswahl; ein Klick filtert auf den Stand.";
    private static final int ROW_HEIGHT = 46;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    /** Was die Ansicht vom Core braucht; in Tests ersetzt. */
    interface Actions {
        /** Löst die Auswahl auf und liest die Sicht; nie auf dem EDT. */
        StatusQuoView view(ExportPanel.Selection selection, BooleanSupplier cancelled);

        Subscription subscribeStanding(Runnable listener);

        Subscription subscribeJobs(JobListener listener);

        static Actions of(AppRuntime runtime) {
            if (runtime == null) {
                return null;
            }
            return new Actions() {
                @Override
                public StatusQuoView view(ExportPanel.Selection selection, BooleanSupplier cancelled) {
                    return runtime.workspace().statusQuo(
                            runtime.workspace().resolveSelection(selection.toCore(runtime)), cancelled);
                }

                @Override
                public Subscription subscribeStanding(Runnable listener) {
                    return runtime.workspace().subscribeStanding(listener);
                }

                @Override
                public Subscription subscribeJobs(JobListener listener) {
                    return runtime.workspace().subscribeJobs(listener);
                }
            };
        }
    }

    /** Führt Arbeit im Hintergrund aus und meldet das Ergebnis auf dem EDT; in Tests sofort. */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Wohin ein Doppelklick oder „Im Lesetext zeigen“ springt: die Seite und die Stelle der Aussage. */
    record Target(String sourceId, String pageId, int start, int end) {
    }

    /** Was die Liste gerade filtert. {@code only == null} zeigt jeden Stand. */
    record Filter(Standing only, boolean actions, boolean states, boolean certainOnly, String text, boolean byBearer) {
        static final Filter ALL = new Filter(null, true, true, false, "", false);
    }

    /** Eine Zeile der Liste: Gruppenkopf, Aussage oder Entscheidung ohne Aussage. */
    sealed interface Row permits Group, Line, Stale {
    }

    record Group(String title, Color tone, int count) implements Row {
    }

    record Line(StatusQuoEntry entry, boolean nested) implements Row {
        String key() {
            return StandingTab.key(entry);
        }
    }

    record Stale(StandingDecision decision) implements Row {
    }

    private final Supplier<Actions> actions;
    private final Runner runner;
    private final PillTabs tabs = new PillTabs();
    private final Map<Standing, CountChip> chips = new EnumMap<>(Standing.class);
    private final JPopupMenu filterMenu = new JPopupMenu();
    private final JCheckBoxMenuItem showActions = new JCheckBoxMenuItem("Handlungen", true);
    private final JCheckBoxMenuItem showStates = new JCheckBoxMenuItem("Zustände", true);
    private final JCheckBoxMenuItem certainOnly = new JCheckBoxMenuItem("Nur sichere Aussagen", false);
    private final IconButton filter = new IconButton(Glyph.FILTER, FILTER_TIP);
    private final IconButton bearer = new IconButton(Glyph.NEIGHBOURS, BY_BEARER_TIP);
    private final JTextField search = new JTextField(9);
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JScrollPane scroll;
    private final BusyOverlay busy;
    private final InfoPopup popup = new InfoPopup();
    private Consumer<String> onStatus = text -> { };
    private Consumer<Target> onJump = target -> { };
    private ExportPanel.Selection selection;
    private StatusQuoView view;
    private boolean byBearer;
    private boolean shown;
    private boolean dirty;
    private int generation;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private Subscription standingWatch = Subscription.NONE;
    private Subscription jobWatch = Subscription.NONE;
    private Actions watched;

    /** @param runtime die laufende App oder {@code null}, solange keine läuft */
    StandingTab(Supplier<AppRuntime> runtime) {
        this(() -> Actions.of(runtime.get()), new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    StandingTab(Supplier<Actions> actions, Runner runner) {
        super(new BorderLayout());
        this.actions = Objects.requireNonNull(actions, "actions");
        this.runner = Objects.requireNonNull(runner, "runner");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(ROW_HEIGHT);
        list.setCellRenderer(new Renderer());
        list.setOpaque(false);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = list.locationToIndex(e.getPoint());
                if (index < 0 || !list.getCellBounds(index, index).contains(e.getPoint())) {
                    return;
                }
                list.setSelectedIndex(index);
                Row row = model.get(index);
                if (e.getClickCount() >= 2 && row instanceof Line line) {
                    popup.setVisible(false);
                    jump(line.entry());
                } else if (e.getClickCount() == 1) {
                    details(row, e.getX(), e.getY());
                }
            }
        });
        scroll = Comic.scroll(list);
        busy = new BusyOverlay(scroll);
        busy.setText(BUSY);
        busy.onCancel(() -> cancelled.set(true));

        for (Standing standing : Standing.values()) {
            CountChip chip = new CountChip(word(standing), tone(standing));
            chip.addActionListener(e -> {
                // Ein Stand zur Zeit: Ein zweiter Klick auf denselben Chip hebt den Filter auf.
                for (CountChip other : chips.values()) {
                    if (other != chip) {
                        other.setSelected(false);
                    }
                }
                refilter();
            });
            chips.put(standing, chip);
        }
        for (JCheckBoxMenuItem box : List.of(showActions, showStates, certainOnly)) {
            if (box == certainOnly) {
                filterMenu.addSeparator();
            }
            box.addActionListener(e -> refilter());
            filterMenu.add(box);
        }
        PopupHover.clearOnClose(filterMenu, filter);
        filter.addActionListener(e -> filterMenu.show(filter, 0, filter.getHeight()));
        bearer.addActionListener(e -> {
            byBearer = !byBearer;
            bearer.setIcon(byBearer ? Glyph.TABLE : Glyph.NEIGHBOURS);
            bearer.setToolTipText(byBearer ? AS_LIST_TIP : BY_BEARER_TIP);
            bearer.getAccessibleContext().setAccessibleName(bearer.getToolTipText());
            refilter();
        });
        search.setToolTipText("Aussagen filtern: Handelnder, Träger, Prädikat oder Seite");
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                refilter();
            }
        });
        InputFrame field = InputFrame.search(search);

        JPanel counts = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        counts.setOpaque(false);
        chips.values().forEach(counts::add);
        tabs.tab(STATEMENTS, busy);
        tabs.actions(counts, filter, bearer, field);
        add(tabs, BorderLayout.CENTER);
    }

    /** Die Statuszeile des Hauptfensters: Umfang der Sicht oder ein Problem. */
    void onStatus(Consumer<String> listener) {
        this.onStatus = Objects.requireNonNull(listener, "listener");
    }

    /** Springt zur Aussage im Lesetext (Seite öffnen, Stelle markieren). */
    void onJump(Consumer<Target> listener) {
        this.onJump = Objects.requireNonNull(listener, "listener");
    }

    /** Die Auswahl links (ADR 0029); {@code null} ohne Auswahl. Gelesen wird erst, wenn der Reiter sichtbar ist. */
    void selection(ExportPanel.Selection selection) {
        if (Objects.equals(selection, this.selection)) {
            return;
        }
        this.selection = selection;
        dirty = true;
        if (selection == null) {
            cancelled.set(true);
            generation++;
            view = null;
            busy.setBusy(false);
            show(List.of());
            counts(null);
            onStatus.accept(" ");
            return;
        }
        loadIfShown();
    }

    /** Ob der Reiter gerade zu sehen ist; erst dann wird gelesen. */
    void shown(boolean shown) {
        this.shown = shown;
        if (!shown) {
            popup.setVisible(false);
        }
        loadIfShown();
    }

    private void loadIfShown() {
        if (shown && dirty && selection != null) {
            load();
        }
    }

    /** Liest die Sicht neu, etwa nach einer Entscheidung oder dem Ende einer Aussagenanalyse. */
    private void changed() {
        dirty = true;
        loadIfShown();
    }

    private void load() {
        Actions current = actions.get();
        if (current == null) {
            return;
        }
        watch(current);
        dirty = false;
        cancelled.set(true);
        AtomicBoolean stop = new AtomicBoolean();
        cancelled = stop;
        int request = ++generation;
        ExportPanel.Selection asked = selection;
        Filter filter = filter();
        busy.setBusy(true);
        runner.run(() -> {
            StatusQuoView read = current.view(asked, stop::get);
            return new Loaded(read, rows(read, filter));
        }, loaded -> {
            if (request != generation) {
                return;
            }
            busy.setBusy(false);
            view = loaded.view();
            counts(view);
            show(loaded.rows());
            status();
        }, failure -> {
            if (request != generation) {
                return;
            }
            busy.setBusy(false);
            if (failure instanceof CancellationException || stop.get()) {
                onStatus.accept("Lesen des Stands abgebrochen");
                dirty = true;
                return;
            }
            onStatus.accept("Stand nicht lesbar (" + failure.getClass().getSimpleName() + ")");
        });
    }

    private record Loaded(StatusQuoView view, List<Row> rows) {
    }

    /** Erst anmelden, dann lesen (ADR 0027): Entscheidungen und das Ende von „Aussagen analysieren“. */
    private void watch(Actions current) {
        if (current == watched) {
            return;
        }
        standingWatch.close();
        jobWatch.close();
        watched = current;
        try {
            standingWatch = current.subscribeStanding(() -> SwingUtilities.invokeLater(this::changed));
        } catch (RuntimeException e) {
            standingWatch = Subscription.NONE;
        }
        try {
            jobWatch = current.subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (status.type() == JobType.STATEMENT_ANALYSIS && status.state().terminal()) {
                        SwingUtilities.invokeLater(StandingTab.this::changed);
                    }
                }
            });
        } catch (RuntimeException e) {
            jobWatch = Subscription.NONE;
        }
    }

    /** Filter, Träger oder Suche geändert: die Zeilen im Hintergrund neu bilden, nichts neu lesen. */
    private void refilter() {
        StatusQuoView current = view;
        if (current == null) {
            return;
        }
        Filter filter = filter();
        int request = ++generation;
        runner.run(() -> rows(current, filter), rows -> {
            if (request == generation) {
                show(rows);
                status();
            }
        }, failure -> { });
    }

    private Filter filter() {
        Standing only = null;
        for (Map.Entry<Standing, CountChip> chip : chips.entrySet()) {
            if (chip.getValue().isSelected()) {
                only = chip.getKey();
            }
        }
        return new Filter(only, showActions.isSelected(), showStates.isSelected(), certainOnly.isSelected(),
                search.getText(), byBearer);
    }

    private void counts(StatusQuoView view) {
        for (Map.Entry<Standing, CountChip> chip : chips.entrySet()) {
            chip.getValue().count(view == null ? 0 : view.counts().getOrDefault(chip.getKey(), 0));
        }
    }

    private void status() {
        if (view == null) {
            return;
        }
        int shownLines = 0;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i) instanceof Line) {
                shownLines++;
            }
        }
        int all = view.entries().size();
        int pages = view.pages().size();
        String text = all == 0 ? "Keine analysierten Aussagen in der Auswahl"
                : (shownLines == all ? Texts.count(all, "Aussage", "Aussagen")
                : shownLines + " von " + Texts.count(all, "Aussage", "Aussagen"))
                + " auf " + Texts.count(pages, "Seite", "Seiten");
        onStatus.accept(text);
    }

    /**
     * Setzt die Zeilen und behält Auswahl und Rollposition: dieselbe Aussage bleibt gewählt, die Ansicht springt nicht.
     */
    private void show(List<Row> rows) {
        Row selected = list.getSelectedValue();
        String key = selected instanceof Line line ? line.key() : null;
        Point position = scroll.getViewport().getViewPosition();
        model.clear();
        model.addAll(rows);
        if (key != null) {
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i) instanceof Line line && line.key().equals(key)) {
                    list.setSelectedIndex(i);
                    break;
                }
            }
        }
        list.revalidate();
        scroll.getViewport().setViewPosition(clamp(position));
        SwingUtilities.invokeLater(() -> scroll.getViewport().setViewPosition(clamp(position)));
    }

    private Point clamp(Point position) {
        int max = Math.max(0, list.getPreferredSize().height - scroll.getViewport().getExtentSize().height);
        return new Point(0, Math.max(0, Math.min(position.y, max)));
    }

    /**
     * Die Zeilen zur Sicht und zum Filter, in der Reihenfolge des Core. Entscheidungen ohne Aussage oben, wenn es
     * welche gibt; „nach Träger“ bündelt die Zustände unter ihrem Träger.
     */
    static List<Row> rows(StatusQuoView view, Filter filter) {
        List<Row> rows = new ArrayList<>();
        if (!view.staleDecisions().isEmpty()) {
            rows.add(new Group(STALE_GROUP, Comic.STANDING_UNCLEAR, view.staleDecisions().size()));
            view.staleDecisions().forEach(decision -> rows.add(new Stale(decision)));
        }
        if (filter.byBearer()) {
            for (BearerStates states : view.states()) {
                List<StatusQuoEntry> kept = states.states().stream().filter(entry -> keeps(entry, filter)).toList();
                if (!kept.isEmpty()) {
                    rows.add(new Group(states.bearer(), Comic.INK, kept.size()));
                    kept.forEach(entry -> rows.add(new Line(entry, true)));
                }
            }
            return rows;
        }
        Map<Standing, List<StatusQuoEntry>> groups = new LinkedHashMap<>();
        for (Standing standing : Standing.values()) {
            groups.put(standing, new ArrayList<>());
        }
        for (StatusQuoEntry entry : view.entries()) {
            if (keeps(entry, filter)) {
                groups.get(entry.standing().standing()).add(entry);
            }
        }
        groups.forEach((standing, entries) -> {
            if (!entries.isEmpty()) {
                rows.add(new Group(word(standing), tone(standing), entries.size()));
                entries.forEach(entry -> rows.add(new Line(entry, false)));
            }
        });
        return rows;
    }

    static boolean keeps(StatusQuoEntry entry, Filter filter) {
        if (filter.only() != null && entry.standing().standing() != filter.only()) {
            return false;
        }
        if (!filter.byBearer()) {
            if (entry.kind() == StatementKind.ACTION && !filter.actions()) {
                return false;
            }
            if (entry.kind() == StatementKind.STATE && !filter.states()) {
                return false;
            }
        }
        if (filter.certainOnly() && entry.uncertain()) {
            return false;
        }
        String text = filter.text() == null ? "" : filter.text().strip().toLowerCase(Locale.ROOT);
        return text.isEmpty() || haystack(entry).contains(text);
    }

    private static String haystack(StatusQuoEntry entry) {
        StringBuilder all = new StringBuilder();
        all.append(entry.subject() == null ? "" : entry.subject()).append(' ')
                .append(entry.statement().predicateText()).append(' ')
                .append(entry.statement().predicateLemma() == null ? "" : entry.statement().predicateLemma())
                .append(' ').append(entry.page().title() == null ? "" : entry.page().title());
        String simplified = entry.statement().derivation().simplifiedSentence();
        if (simplified != null) {
            all.append(' ').append(simplified);
        }
        return all.toString().toLowerCase(Locale.ROOT);
    }

    // ---- Texte (die festen Gründe des Core in Worten der App)

    static String word(Standing standing) {
        return switch (standing) {
            case GILT -> "gilt";
            case GEPLANT -> "geplant";
            case UNKLAR -> "unklar";
            case UEBERHOLT -> "überholt";
        };
    }

    static Color tone(Standing standing) {
        return switch (standing) {
            case GILT -> Comic.STANDING_CURRENT;
            case GEPLANT -> Comic.STANDING_PLANNED;
            case UNKLAR -> Comic.STANDING_UNCLEAR;
            case UEBERHOLT -> Comic.STANDING_OUTDATED;
        };
    }

    /** Verneinung und Verbindlichkeit als Wort („muss nicht“, „nicht“, „kann“); leer ohne beides. */
    static String mark(StatusQuoEntry entry) {
        String modal = switch (entry.modality()) {
            case MUSS -> "muss";
            case SOLL -> "soll";
            case KANN -> "kann";
            case DARF -> "darf";
            case NONE, UNKNOWN -> "";
        };
        boolean negated = entry.negation() == ParsedStatements.Negation.NEGATED;
        return modal.isEmpty() ? negated ? "nicht" : "" : negated ? modal + " nicht" : modal;
    }

    /**
     * Die Zeile in Worten: der vereinfachte Satz der Vorstufe, sonst Handelnder bzw. Träger und Prädikat. Den
     * Originalsatz im Wortlaut liefert der Core künftig getrennt (FL6, {@code StatusQuo#sentences}).
     */
    static String text(StatusQuoEntry entry) {
        String simplified = entry.statement().derivation().simplifiedSentence();
        if (entry.origin() == ParsedStatements.Origin.VORSTUFE && simplified != null && !simplified.isBlank()) {
            return simplified.strip();
        }
        String subject = entry.subject() == null || entry.subject().isBlank() ? "" : entry.subject().strip() + " ";
        return subject + entry.statement().predicateText();
    }

    /** Was unter dem Satz steht: Seite und Kennzeichen, die nicht in der Farbe stecken dürfen. */
    static String meta(StatusQuoEntry entry) {
        List<String> parts = new ArrayList<>();
        parts.add(entry.page().title() == null || entry.page().title().isBlank() ? entry.page().key().pageId()
                : entry.page().title());
        if (entry.kind() == StatementKind.STATE) {
            parts.add("Zustand");
        }
        if (entry.uncertain()) {
            parts.add("unsicher");
        }
        if (entry.analysisStale()) {
            parts.add("Analyse veraltet");
        }
        if (entry.decision() != null) {
            parts.add("von Hand");
        }
        Standing flagged = flagged(entry.stageFlag());
        if (flagged != null && flagged != entry.standing().standing()) {
            parts.add("Kennzeichen der Vorstufe: " + stageFlag(entry.stageFlag()));
        }
        if (entry.stateChange()) {
            parts.add("Zustandswechsel");
        }
        return String.join(" · ", parts);
    }

    private static Standing flagged(ParsedStatements.StageFlag flag) {
        return switch (flag) {
            case FACT, CONDITION -> Standing.GILT;
            case PLANNING -> Standing.GEPLANT;
            case DISCARDED -> Standing.UEBERHOLT;
            case NONE -> null;
        };
    }

    static String stageFlag(ParsedStatements.StageFlag flag) {
        return switch (flag) {
            case FACT -> "Tatsache";
            case CONDITION -> "Bedingung";
            case PLANNING -> "Planung";
            case DISCARDED -> "verworfen";
            case NONE -> "keins";
        };
    }

    static String reason(StandingReason reason) {
        return switch (reason) {
            case DECISION -> "von Hand gesetzt";
            case TEMPORALITY_GILT -> "Der Satz sagt „gilt jetzt“ (oder nichts über die Zeit).";
            case TEMPORALITY_GEPLANT -> "Der Satz sagt „geplant“ (Futur, „soll“ mit Zeitangabe …).";
            case TEMPORALITY_FRUEHER -> "Der Satz sagt „früher“ (Präteritum, „bisher“ …).";
            case TEMPORALITY_VERWORFEN -> "Der Satz sagt „verworfen“ („nicht mehr“, „entfällt“ …).";
            case STAGE_FACT -> "Die Vorstufe (KI) kennzeichnet den Satz als Tatsache.";
            case STAGE_CONDITION -> "Die Vorstufe (KI) kennzeichnet den Satz als Bedingung.";
            case STAGE_PLANNING -> "Die Vorstufe (KI) kennzeichnet den Satz als Planung oder Absicht.";
            case STAGE_DISCARDED -> "Die Vorstufe (KI) kennzeichnet den Satz als verworfen oder früher.";
            case PAGE_OUTDATED -> "Die Seite ist überholt (Label, Titel, Lage oder Entscheidung zur Seite).";
            case PAGE_OUTDATED_PLAN -> "Geplantes auf einer überholten Seite: nicht bestimmbar.";
            case PAGE_DRAFT -> "Die Seite ist ein Entwurf (Label, Titel oder Lage).";
            case PAGE_DECISION -> "Die Seite wurde von Hand als geplant oder unklar eingestuft.";
            case NO_FEATURES -> "Die Aussage hat keine Merkmale (ältere Analyse oder anderer Analysator).";
            case MAJORITY -> "Die meisten sicheren Aussagen der Seite haben diesen Stand.";
            case NO_MAJORITY -> "Gleichstand oder keine sichere Aussage auf der Seite.";
        };
    }

    static String basis(StandingBasis basis) {
        return switch (basis) {
            case DECISION -> "Mensch";
            case PAGE -> "Seite";
            case FEATURES -> "Zeitbezug des Satzes";
            case STAGE_FLAG -> "Vorstufe (KI)";
            case STATEMENTS -> "Aussagen der Seite";
            case NONE -> "keine Merkmale";
        };
    }

    static String pageMark(PageMark mark) {
        String where = switch (mark.where()) {
            case LABEL -> "im Label der Seite";
            case TITLE -> "im Titel der Seite";
            case ANCESTOR_TITLE -> "im Titel der übergeordneten Seite " + mark.pageId();
        };
        return "Wort „" + mark.word() + "“ " + where
                + (mark.kind() == PageMark.Kind.OUTDATED ? " (überholt)" : " (Entwurf)");
    }

    /** Die Details einer Zeile für das Overlay. */
    static String details(StatusQuoEntry entry) {
        List<String> lines = new ArrayList<>();
        String mark = mark(entry);
        lines.add("Stand: " + word(entry.standing().standing()) + (mark.isEmpty() ? "" : ": " + mark + " …"));
        lines.add("Grund: " + reason(entry.standing().reason()));
        lines.add("Woher: " + basis(entry.standing().basis()));
        if (entry.standing().mark() != null) {
            lines.add("Gegriffen hat: " + pageMark(entry.standing().mark()));
        }
        lines.add("Art: " + (entry.kind() == StatementKind.STATE ? "Zustand" : "Handlung")
                + (entry.subject() == null || entry.subject().isBlank() ? ""
                : (entry.kind() == StatementKind.STATE ? " · Träger: " : " · Handelnder: ") + entry.subject()));
        if (entry.origin() == ParsedStatements.Origin.VORSTUFE) {
            lines.add("Herkunft: Vorstufe „Sätze vereinfachen“ (KI) · Kennzeichen: " + stageFlag(entry.stageFlag()));
            String simplified = entry.statement().derivation().simplifiedSentence();
            if (simplified != null && !simplified.isBlank()) {
                lines.add("Vereinfachter Satz: „" + simplified.strip() + "“");
            }
        } else {
            lines.add("Herkunft: Originalsatz");
        }
        if (entry.uncertain()) {
            lines.add("Unsicher: zählt nicht für den Stand der Seite.");
        }
        if (entry.analysisStale()) {
            lines.add("Die Analyse passt nicht mehr zum Text der Seite.");
        }
        if (entry.stateChange()) {
            lines.add("Hinweis: Zustandswechsel („wird zuständig“).");
        }
        StandingDecision decision = entry.decision();
        if (decision != null) {
            lines.add("Von Hand: " + word(decision.standing()) + " · " + decided(decision));
        }
        return String.join("\n", lines);
    }

    private static String decided(StandingDecision decision) {
        String who = decision.decidedBy() == null ? "" : decision.decidedBy();
        String when = decision.decidedAt() == null ? "" : WHEN.format(decision.decidedAt());
        return (who + (who.isEmpty() || when.isEmpty() ? "" : ", ") + when).strip();
    }

    private void details(Row row, int x, int y) {
        popup.clearLinks();
        if (row instanceof Line line) {
            StatusQuoEntry entry = line.entry();
            popup.text(entry.page().title() == null ? entry.page().key().pageId() : entry.page().title(),
                    details(entry));
            popup.link(OPEN_TEXT, () -> {
                popup.setVisible(false);
                jump(entry);
            });
        } else if (row instanceof Stale stale) {
            StandingDecision decision = stale.decision();
            popup.text(STALE_GROUP, "Von Hand „" + word(decision.standing()) + "“ zu einer Aussage, die es in der "
                    + "aktuellen Analyse nicht mehr gibt (Text geändert oder Vorstufe entzogen).\nSeite: "
                    + decision.page().pageId() + "\n" + decided(decision));
        } else {
            return;
        }
        if (list.isShowing()) {
            popup.showAt(list, x, y);
        }
    }

    private void jump(StatusQuoEntry entry) {
        onJump.accept(new Target(entry.page().key().sourceId(), entry.page().key().pageId(),
                entry.statement().start(), entry.statement().end()));
    }

    static String key(StatusQuoEntry entry) {
        return entry.page().key().sourceId() + "/" + entry.page().key().pageId() + "#" + entry.statement().id();
    }

    /** Zeichnet nur, was sichtbar ist; dieselben Komponenten für jede Zeile. */
    private static final class Renderer implements ListCellRenderer<Row> {
        private final JPanel line = new JPanel(new BorderLayout(8, 0));
        private final JLabel badge = new JLabel();
        private final JLabel mark = new JLabel();
        private final JLabel sentence = new JLabel();
        private final JLabel meta = new JLabel();
        private final JPanel text = new JPanel(new BorderLayout());
        private final JPanel head = new JPanel(new BorderLayout(0, 0));
        private final JLabel group = new JLabel();
        private final JPanel groupRow = new JPanel(new BorderLayout());

        Renderer() {
            badge.setFont(Comic.semiBold(12f));
            badge.setHorizontalAlignment(JLabel.CENTER);
            badge.setOpaque(true);
            mark.setFont(Comic.semiBold(13f));
            sentence.setFont(Comic.regular(13f));
            meta.setFont(Comic.regular(11.5f));
            head.setOpaque(false);
            head.add(mark, BorderLayout.WEST);
            head.add(sentence, BorderLayout.CENTER);
            text.setOpaque(false);
            text.add(head, BorderLayout.CENTER);
            text.add(meta, BorderLayout.SOUTH);
            JPanel badgeBox = new JPanel(new java.awt.GridBagLayout());
            badgeBox.setOpaque(false);
            badgeBox.add(badge);
            line.add(badgeBox, BorderLayout.WEST);
            line.add(text, BorderLayout.CENTER);
            group.setFont(Comic.semiBold(13f));
            groupRow.add(group, BorderLayout.SOUTH);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Row> list, Row row, int index,
                                                      boolean selected, boolean focused) {
            if (row instanceof Group g) {
                group.setText(g.title() + " · " + g.count());
                group.setForeground(g.tone());
                groupRow.setOpaque(false);
                groupRow.setBorder(BorderFactory.createCompoundBorder(
                        BorderFactory.createEmptyBorder(0, 4, 2, 4),
                        BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE)));
                return groupRow;
            }
            Standing standing;
            String words;
            String markText;
            String metaText;
            boolean nested = false;
            if (row instanceof Line l) {
                StatusQuoEntry entry = l.entry();
                standing = entry.standing().standing();
                words = text(entry);
                markText = mark(entry);
                metaText = meta(entry);
                nested = l.nested();
            } else {
                StandingDecision decision = ((Stale) row).decision();
                standing = decision.standing();
                words = "Entscheidung ohne Aussage";
                markText = "";
                metaText = "Seite " + decision.page().pageId() + " · neu entscheiden oder löschen";
            }
            Color tone = tone(standing);
            boolean faded = standing == Standing.UEBERHOLT;
            badge.setText(word(standing));
            badge.setForeground(tone);
            badge.setBackground(Comic.SURFACE);
            badge.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(tone, 1, true),
                    BorderFactory.createEmptyBorder(1, 6, 1, 6)));
            badge.setPreferredSize(new java.awt.Dimension(78, 20));
            mark.setText(markText);
            mark.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, markText.isEmpty() ? 0 : 6));
            mark.setForeground(faded ? Comic.STANDING_OUTDATED : Comic.INK);
            sentence.setText(words);
            sentence.setForeground(faded ? Comic.STANDING_OUTDATED : Comic.TEXT);
            meta.setText(metaText);
            meta.setForeground(faded ? Comic.STANDING_OUTDATED : Comic.MUTED);
            line.setOpaque(selected);
            line.setBackground(selected ? Comic.NOTICE : Comic.SURFACE);
            line.setBorder(BorderFactory.createEmptyBorder(3, nested ? 22 : 6, 3, 6));
            return line;
        }
    }

    // ---- für Tests

    List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            rows.add(model.get(i));
        }
        return rows;
    }

    CountChip chip(Standing standing) {
        return chips.get(standing);
    }

    JCheckBoxMenuItem certainOnlyBox() {
        return certainOnly;
    }

    IconButton bearerButton() {
        return bearer;
    }

    JTextField searchField() {
        return search;
    }

    JList<Row> rowList() {
        return list;
    }

    InfoPopup popup() {
        return popup;
    }

    PillTabs tabs() {
        return tabs;
    }

    BusyOverlay busy() {
        return busy;
    }

    /** Wie ein Klick auf Zeile {@code index}. */
    void click(int index) {
        list.setSelectedIndex(index);
        Rectangle bounds = list.getCellBounds(index, index);
        details(model.get(index), bounds == null ? 0 : bounds.x + 4, bounds == null ? 0 : bounds.y + 4);
    }

    /** Wie ein Doppelklick auf Zeile {@code index}. */
    void doubleClick(int index) {
        if (model.get(index) instanceof Line line) {
            jump(line.entry());
        }
    }

    JComponent statementsView() {
        return busy;
    }
}
