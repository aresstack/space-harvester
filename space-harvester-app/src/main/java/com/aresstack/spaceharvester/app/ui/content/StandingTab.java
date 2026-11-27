package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.CountChip;
import com.aresstack.spaceharvester.app.ui.controls.PlaceholderList;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.application.access.AccessDeniedException;
import com.aresstack.spaceharvester.application.standing.LockedPageException;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.jobs.JobType;
import com.aresstack.spaceharvester.application.knowledge.ParsedStatements;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.standing.BearerStates;
import com.aresstack.spaceharvester.application.standing.PageMark;
import com.aresstack.spaceharvester.application.standing.ReadingList;
import com.aresstack.spaceharvester.application.standing.SentenceText;
import com.aresstack.spaceharvester.application.standing.Standing;
import com.aresstack.spaceharvester.application.standing.StandingBasis;
import com.aresstack.spaceharvester.application.standing.StandingDecision;
import com.aresstack.spaceharvester.application.standing.StandingDecisionStore;
import com.aresstack.spaceharvester.application.standing.StandingConflictException;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.standing.StandingReason;
import com.aresstack.spaceharvester.application.standing.StatementConflicts;
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
import java.util.function.Predicate;
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

    /** Die Begriffe der Auswahl, vorn in derselben Reiterzeile wie auf der Seite (BG2a, AT 17.11.2026). */
    static final String TERMS = "Statistik";
    static final String GLOSSARY = "Fachglossar";
    static final String GRAPH = "Graph";
    static final String STATEMENTS = "Aussagen";
    static final String READING = "Zuerst lesen";
    static final String CONFLICTS = "Mögliche Widersprüche";
    static final String REPLACEMENTS = "Ablösungen";
    static final String SITUATION = "Sachlage: ";
    static final String BUSY = "Stand wird gelesen …";
    static final String BY_BEARER_TIP = "Nach Träger bündeln: Zustände (Eigenschaften, Rollen) unter ihrem Träger";
    static final String AS_LIST_TIP = "Als Liste nach Stand zeigen";
    static final String FILTER_TIP = "Filter: Handlungen, Zustände, nur sichere Aussagen";
    static final String STALE_GROUP = "Entscheidungen ohne Aussage";
    static final String OPEN_TEXT = "Im Lesetext zeigen";
    static final String SET_LABEL = "Stand von Hand:";
    static final String PAGE_SET_LABEL = "Seite von Hand:";
    static final String CLEAR_TIP = "Entscheidung von Hand löschen; danach gilt wieder der berechnete Stand";
    static final String HISTORY_TIP = "Protokoll: wer wann welchen Stand gesetzt oder gelöscht hat";
    static final String CLEAR_QUESTION = "Wirklich die Entscheidung von Hand löschen? Danach gilt wieder der "
            + "berechnete Stand.";
    static final String NO_STATEMENTS = "Keine analysierten Aussagen in der Auswahl";
    /**
     * Wie die Analyse startet, als zweiter Satz in der leeren Liste (Angelo 27.11.2026: „ich weiß nicht, wie ich die
     * Analyse starte“): Der Eintrag steht im Rechtsklick-Menü des Baums, von hier aus war er nicht zu finden.
     */
    static final String HOW_TO_ANALYZE = "Die Analyse startet per Rechtsklick auf die Auswahl im Baum links, "
            + "Eintrag „Analysieren“.";
    static final String NO_MATCH = "Keine Treffer";
    static final String STALE_WORD = "veraltet";
    static final String DELETE_ANALYSES_TITLE = "Aussagen-Analysen löschen?";
    static final String DELETE_ANALYSES_TIP = "Aussagen-Analysen löschen";
    /** Im Menü neben dem Papierkorb (Angelo 17.11.2026): auch die KI-Urteile des Prüfschritts löschen. */
    static final String DELETE_EVERYTHING = "Alles löschen";
    static final String DELETE_EVERYTHING_TITLE = "Alles löschen?";
    static final String DELETE_EVERYTHING_TIP = "Aussagen-Analysen samt den KI-Urteilen aus „Aussagen prüfen“ löschen";
    static final String DELETE_EVERYTHING_MISSING = "Noch nicht möglich: Der Core kann die KI-Urteile noch nicht "
            + "löschen";
    static final String MORE_DELETE_TIP = "Weitere Löschaktionen";
    static final String STALE_TIP = "Aussagen aus veralteten Analysen einblenden; sie zählen nirgends mit";
    static final String STALE_HIDE_TIP = "Aussagen aus veralteten Analysen wieder ausblenden";
    static final String NO_CONFLICTS = "Keine möglichen Widersprüche";
    static final String NO_RIGHT_TO_DECIDE = "Keine Berechtigung, den Stand in dieser Quelle zu setzen";
    /** Tooltip an Pillen und Löschen auf einer im Bestand gesperrten Seite (ADR 0014), für Aussage und Seite. */
    static final String LOCKED_PAGE = "Seite ist gesperrt; der Stand lässt sich nicht ändern";
    /** Statuszeile, wenn der Core auf einer inzwischen gesperrten Seite ablehnt. */
    static final String LOCKED_REFUSED = "Stand nicht gesetzt: Die Seite ist gesperrt.";
    static final String NO_RIGHT_TO_HISTORY = "Keine Berechtigung, das Protokoll in dieser Quelle zu lesen";
    static final String NOT_WATCHING = "Stand erneuert sich nicht von selbst (Anmeldung beim Core fehlgeschlagen)";
    static final String HELP = "Jede Aussage der Auswahl links hat einen Stand: „gilt“ (gilt jetzt), „geplant“ (soll "
            + "kommen, gilt noch nicht), „unklar“ (nicht bestimmbar, etwa auf einer Entwurfsseite) oder „überholt“ "
            + "(früher gültig oder verworfen; grau am Ende, nie versteckt).\n\n"
            + "Woher der Stand kommt, in dieser Reihenfolge: eine Entscheidung von Hand gewinnt immer; dann die "
            + "Vorstufe „Sätze vereinfachen“ (KI), wenn ihre Merkmale entscheiden; dann der Zeitbezug des Satzes "
            + "(Präsens, Futur, Präteritum, „nicht mehr“); eine überholte Seite (Label, Titel, Lage) oder ein Entwurf "
            + "zieht nur Aussagen mit, die selbst nichts über die Zeit sagen.\n\n"
            + "Verneinung und Verbindlichkeit („muss nicht“) ändern den Stand nie und stehen deshalb in jeder Zeile. "
            + "„" + StatementProvenance.PROPOSAL + "“ heißt: Der Parser ist sich nicht sicher oder der Prüfschritt "
            + "hat die Aussage verworfen; sie bleibt sichtbar, zählt aber nicht für den Stand der Seite.\n\n"
            + "Ein Klick auf eine Zeile zeigt Grund und Herkunft, ein Doppelklick springt an die Stelle im Lesetext. "
            + "Die Suche findet Handelnde, Träger, Prädikate, die übrigen Beteiligten in Grundform, Seitentitel und "
            + "vereinfachte Sätze, nicht aber Wörter, die nur im Originalsatz stehen. "
            + "Die Zahlen oben zählen alle Aussagen der Auswahl; ein Klick filtert auf den Stand.\n\n"
            + "„Zuerst lesen“ ist eine Empfehlung zur Lesereihenfolge, keine Bewertung der Seiten. Die Summe zählt "
            + "Punkte für geltende sichere Aussagen, häufige Begriffe der Auswahl, Erwähnungen des Themas und jüngste "
            + "Änderungen und zieht Punkte für Überholtes, Entwürfe und veraltete Analysen ab; die Punktwerte kommen "
            + "aus dem Core und stehen je Seite im Overlay an der Summe. Dort lässt sich auch der Stand der ganzen "
            + "Seite von Hand setzen; ein Klick auf den Titel öffnet die Seite, ein Thema im Feld oben rechts (▾ für "
            + "Vorschläge) zeigt nur Seiten, die es erwähnen.\n\n"
            + "Der Papierkorb löscht die Aussagen-Analysen der Auswahl; die KI-Urteile aus „Aussagen prüfen“ bleiben "
            + "stehen und greifen nach einer neuen Analyse wieder, solange Satz, Zusammenhang, Aussage, Modell und "
            + "Anweisung gleich sind. „Alles löschen“ im Menü ▾ daneben löscht auch diese Urteile.\n\n"
            + "„Mögliche Widersprüche“ ist nur ein Hinweis: sichere Aussagen derselben Sachlage, die beide gelten, "
            + "mit gleicher Verbindlichkeit, eine bejaht, eine verneint. Setzt man eine davon von Hand auf "
            + "„überholt“, verschwindet der Hinweis. „Ablösungen“ stehen getrennt darunter und sind kein Konflikt: "
            + "eine geltende und eine überholte Aussage, anders verneint oder mit anderem Handelnden. Nie verglichen "
            + "werden unsichere Aussagen, Nebensätze, Zustandswechsel, Bedingungen und Original und Vorstufe "
            + "desselben Satzes.";
    private static final int ROW_HEIGHT = 46;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY)
            .withZone(ZoneId.systemDefault());

    /** Was die Ansicht vom Core braucht; in Tests ersetzt. */
    interface Actions {
        /** Löst die Auswahl auf und liest die Sicht; nie auf dem EDT. */
        StatusQuoView view(ExportPanel.Selection selection, BooleanSupplier cancelled);

        Subscription subscribeStanding(Runnable listener);

        Subscription subscribeJobs(JobListener listener);

        /** Der Wortlaut zu Aussagen (FL6, {@code StatusQuo#sentences}), je statementId; nie auf dem EDT. */
        Map<String, SentenceText> sentences(java.util.Collection<StatusQuoEntry> entries);

        /** Setzt den Stand von Hand ({@code StatusQuo#decide}); {@code expectedRevision} 0 ohne Entscheidung. */
        StandingDecision decide(PageKey page, String statementId, Standing standing, long expectedRevision);

        /** Löscht eine Entscheidung ({@code StatusQuo#clear}). */
        boolean clear(PageKey page, String statementId, long expectedRevision);

        /** Das Protokoll der Entscheidungen, älteste zuerst. */
        List<StandingDecisionStore.AuditEntry> history(PageKey page, String statementId);

        /** Die Leseempfehlung der Auswahl ({@code ReadingAdvice}), mit Thema oder ({@code null}) ohne; nie auf dem EDT. */
        ReadingList reading(ExportPanel.Selection selection, String topic, BooleanSupplier cancelled);

        /**
         * Löscht die Aussagen-Analysen der Auswahl samt Unterseiten, Anhängen und enthaltenen Dateien; die Seiten
         * bleiben. Liefert die Zahl der gelöschten Analysen; nie auf dem EDT.
         */
        int deleteAnalyses(ExportPanel.Selection selection);

        /** Meldet sich, nachdem Aussagen-Analysen gelöscht wurden (Beobachter, kein Zeitgeber). */
        Subscription subscribeAnalyses(Runnable listener);

        /** Ob der Core auch die KI-Urteile des Prüfschritts löschen kann („Alles löschen“). */
        default boolean canDeleteEverything() {
            return false;
        }

        /**
         * Wie {@link #deleteAnalyses}, dazu die KI-Urteile des Prüfschritts derselben Seiten; nie auf dem EDT.
         */
        default int deleteEverything(ExportPanel.Selection selection) {
            throw new UnsupportedOperationException(DELETE_EVERYTHING_MISSING);
        }

        static Actions of(AppRuntime runtime) {
            if (runtime == null) {
                return null;
            }
            return new Actions() {
                @Override
                public StatusQuoView view(ExportPanel.Selection selection, BooleanSupplier cancelled) {
                    return runtime.workspace().statusQuo(SelectionHoldings.resolve(runtime, selection), cancelled);
                }

                @Override
                public Subscription subscribeStanding(Runnable listener) {
                    return runtime.workspace().subscribeStanding(listener);
                }

                @Override
                public Subscription subscribeJobs(JobListener listener) {
                    return runtime.workspace().subscribeJobs(listener);
                }

                @Override
                public Map<String, SentenceText> sentences(java.util.Collection<StatusQuoEntry> entries) {
                    return runtime.workspace().standingSentences(entries);
                }

                @Override
                public StandingDecision decide(PageKey page, String statementId, Standing standing,
                                               long expectedRevision) {
                    return runtime.workspace().decideStanding(page, statementId, standing, expectedRevision);
                }

                @Override
                public boolean clear(PageKey page, String statementId, long expectedRevision) {
                    return runtime.workspace().clearStanding(page, statementId, expectedRevision);
                }

                @Override
                public List<StandingDecisionStore.AuditEntry> history(PageKey page, String statementId) {
                    return runtime.workspace().standingHistory(page, statementId);
                }

                @Override
                public ReadingList reading(ExportPanel.Selection selection, String topic, BooleanSupplier cancelled) {
                    return runtime.workspace().readingList(SelectionHoldings.resolve(runtime, selection), topic,
                            cancelled);
                }

                @Override
                public int deleteAnalyses(ExportPanel.Selection selection) {
                    // Ohne veraltete Favoriten, sonst bräche die strenge Auflösung den ganzen Ordner (ADR 0034 §4).
                    return runtime.workspace().deleteStatementAnalyses(SelectionHoldings.core(runtime, selection),
                            false, !(selection instanceof ExportPanel.Selection.Collection));
                }

                @Override
                public boolean canDeleteEverything() {
                    return true;
                }

                @Override
                public int deleteEverything(ExportPanel.Selection selection) {
                    // Ohne veraltete Favoriten, sonst bräche die strenge Auflösung den ganzen Ordner (ADR 0034 §4).
                    return runtime.workspace().deleteStatementAnalyses(SelectionHoldings.core(runtime, selection),
                            true, !(selection instanceof ExportPanel.Selection.Collection));
                }

                @Override
                public Subscription subscribeAnalyses(Runnable listener) {
                    return runtime.workspace().subscribeStatementAnalyses(listener);
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

    /**
     * Was die Liste gerade filtert. {@code only == null} zeigt jeden Stand; {@code stale} zeigt auch Aussagen aus
     * veralteten Analysen (Chip „veraltet N“), sonst bleiben sie weg.
     */
    record Filter(Standing only, boolean actions, boolean states, boolean certainOnly, String text, boolean byBearer,
                  boolean stale) {
        static final Filter ALL = new Filter(null, true, true, false, "", false, true);
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
    private final CountChip staleChip = new CountChip(STALE_WORD, Comic.MUTED);
    private final JPopupMenu filterMenu = new JPopupMenu();
    private final JCheckBoxMenuItem showActions = new JCheckBoxMenuItem("Handlungen", true);
    private final JCheckBoxMenuItem showStates = new JCheckBoxMenuItem("Zustände", true);
    private final JCheckBoxMenuItem certainOnly = new JCheckBoxMenuItem("Nur sichere Aussagen", false);
    private final IconButton filter = new IconButton(Glyph.FILTER, FILTER_TIP);
    private final IconButton bearer = new IconButton(Glyph.NEIGHBOURS, BY_BEARER_TIP);
    /** „Aussagen-Analysen löschen“ absteigend ab dem gewählten Knoten (Angelo 17.11.2026); die Seiten bleiben. */
    private final IconButton deleteAnalyses = new IconButton(Glyph.TRASH, DELETE_ANALYSES_TIP);
    /** Rechts neben dem Papierkorb das Dreieck wie neben dem Besen der Aufträge (Angelo 17.11.2026). */
    private final IconButton moreDelete = new IconButton(
            new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(), MORE_DELETE_TIP);
    private Predicate<String> confirmDelete = question -> Dialogs.confirm(this,
            question.startsWith(EVERYTHING_PREFIX) ? DELETE_EVERYTHING_TITLE : DELETE_ANALYSES_TITLE, question);
    private static final String EVERYTHING_PREFIX = "Wirklich alles ";
    private volatile boolean deleting;
    /** Ergebnis des letzten Löschens; steht vor der Zählung in der Statuszeile, bis eine andere Auswahl kommt. */
    private String deletedNotice;
    /**
     * Warum das letzte Setzen scheiterte, wenn danach neu gelesen wird (gesperrte Seite): steht vor der Zählung, bis
     * zum nächsten Setzen oder einer anderen Auswahl, damit das neue Lesen die Meldung nicht still überschreibt.
     */
    private String writeNotice;
    private Subscription analysesWatch = Subscription.NONE;
    private final JTextField search = new JTextField(9);
    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final PlaceholderList<Row> list = new PlaceholderList<>(model);
    private final JScrollPane scroll;
    private final DefaultListModel<Row> conflictModel = new DefaultListModel<>();
    private final PlaceholderList<Row> conflictList = new PlaceholderList<>(conflictModel);
    private StatementConflicts conflicts;
    /** Die Liste, an der das Overlay zuletzt aufging (Aussagen oder Widersprüche). */
    private JComponent popupOwner = list;
    private final BusyOverlay busy;
    private final ReadingFirstView reading;
    private final InfoPopup popup = new InfoPopup();
    private Consumer<String> onStatus = text -> { };
    private Consumer<Target> onJump = target -> { };
    private ExportPanel.Selection selection;
    private StatusQuoView view;
    private boolean byBearer;
    private boolean shown;
    private boolean dirty;
    /**
     * Reiter anderer Ansichten in derselben Zeile (Begriffe vorn, Fachglossar und Graph hinten, BG2c): je Name, wer
     * erfährt, ob er zu sehen ist (Reiter sichtbar und gewählt). Auf ihnen wird der Stand nicht gelesen.
     */
    private final Map<String, Consumer<Boolean>> foreign = new LinkedHashMap<>();
    /** Die Werkzeuge der fremden Reiter oben rechts; zu sehen nur auf ihrem Reiter. */
    private final Map<String, List<JComponent>> foreignTools = new LinkedHashMap<>();
    private int generation;
    private AtomicBoolean cancelled = new AtomicBoolean();
    private Subscription standingWatch = Subscription.NONE;
    private Subscription jobWatch = Subscription.NONE;
    private Actions watched;
    /** Wortlaut je statementId, nur für schon sichtbar gewesene Zeilen; geleert bei jedem neuen Lesen. */
    private final Map<String, SentenceText> sentences = new java.util.HashMap<>();
    /** Schon angefragte statementIds, auch solche ohne Ergebnis (kein Leserecht): nicht noch einmal fragen. */
    private final java.util.Set<String> asked = new java.util.HashSet<>();
    /** Zählt neue Sichten, damit eine späte Antwort zum Wortlaut nicht in die nächste Sicht fällt. */
    private int sentenceGeneration;
    /** Ob eine Anmeldung beim Core fehlschlug; dann sagt die Statuszeile, dass sich nichts von selbst erneuert. */
    private boolean watchFailed;
    private BooleanSupplier confirmClear = () -> Dialogs.confirm(this, "Entscheidung löschen?", CLEAR_QUESTION);

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
                    details(list, row, e.getX(), e.getY());
                }
            }
        });
        conflictList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        conflictList.setFixedCellHeight(ROW_HEIGHT);
        conflictList.setCellRenderer(new Renderer());
        conflictList.setOpaque(false);
        conflictList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = conflictList.locationToIndex(e.getPoint());
                if (index < 0 || !conflictList.getCellBounds(index, index).contains(e.getPoint())) {
                    return;
                }
                conflictList.setSelectedIndex(index);
                Row row = conflictModel.get(index);
                if (e.getClickCount() >= 2 && row instanceof Line line) {
                    popup.setVisible(false);
                    jump(line.entry());
                } else if (e.getClickCount() == 1) {
                    details(conflictList, row, e.getX(), e.getY());
                }
            }
        });
        scroll = Comic.scroll(list);
        // Den Wortlaut nur für die sichtbaren Zeilen holen, beim Rollen nach.
        scroll.getViewport().addChangeListener(e -> fetchSentences(list, model));
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
        // Unabhängig vom Stand-Chip: blendet die Aussagen aus veralteten Analysen zusätzlich ein.
        staleChip.tips(STALE_TIP, STALE_HIDE_TIP);
        staleChip.setVisible(false);
        staleChip.addActionListener(e -> refilter());
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
        counts.add(staleChip);
        reading = new ReadingFirstView(runner, popup);
        reading.pageControls(this::pageControls);
        reading.onStatus(text -> {
            if (READING.equals(tabs.selected())) {
                // Eine Ablehnung des Core (gesperrt) bleibt auch nach dem neuen Lesen vor der Zählung stehen.
                String counted = watchFailed ? text + " · " + NOT_WATCHING : text;
                onStatus.accept(writeNotice == null ? counted : writeNotice + " · " + counted);
            }
        });
        reading.onOpen(page -> onJump.accept(new Target(page.key().sourceId(), page.key().pageId(), -1, -1)));
        JComponent topic = reading.topicField();
        topic.setVisible(false);
        tabs.tab(STATEMENTS, busy);
        tabs.tab(READING, reading);
        JScrollPane conflictScroll = Comic.scroll(conflictList);
        conflictScroll.getViewport().addChangeListener(e -> fetchSentences(conflictList, conflictModel));
        tabs.tab(CONFLICTS, conflictScroll);
        deleteAnalyses.addActionListener(e -> deleteAnalyses());
        moreDelete.addActionListener(e -> com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                moreDeleteMenu(), moreDelete).show(moreDelete, 0, moreDelete.getHeight()));
        tabs.actions(counts, filter, bearer, field, topic, deleteAnalyses, moreDelete);
        // Zahlen, Filter und Suche gehören zu „Aussagen“, das Themenfeld zu „Zuerst lesen“; anderswo stünden sie
        // ohne Wirkung da.
        tabs.onSelect(name -> {
            boolean statements = STATEMENTS.equals(name);
            for (JComponent tool : List.of(counts, filter, bearer, field)) {
                tool.setVisible(statements);
            }
            topic.setVisible(READING.equals(name));
            // Fremde Reiter bringen ihre Werkzeuge selbst mit; Papierkorb und ▾ gehören nur zum Stand.
            deleteAnalyses.setVisible(!foreign.containsKey(name));
            moreDelete.setVisible(!foreign.containsKey(name));
            foreignTools.forEach((tab, tools) -> tools.forEach(tool -> tool.setVisible(tab.equals(name))));
            popup.setVisible(false);
            reading.shown(shown && READING.equals(name));
            tellForeign(name);
            if (foreign.containsKey(name)) {
                onStatus.accept(" ");
                return;
            }
            loadIfShown();
            if (READING.equals(name)) {
                reading.status();
            } else {
                status();
            }
        });
        add(tabs, BorderLayout.CENTER);
    }

    /**
     * Setzt die Begriffe der Auswahl als ersten Reiter „Begriffe“ vor „Aussagen“, in dieselbe Reiterzeile (keine
     * zweite Ebene). {@code onShown} erfährt, ob sie gerade zu sehen sind, damit sie erst dann laden.
     */
    void terms(JComponent view, Consumer<Boolean> onShown) {
        foreign.put(TERMS, Objects.requireNonNull(onShown, "onShown"));
        tabs.first(TERMS, view);
    }

    /**
     * Hängt den Reiter einer anderen Ansicht hinten an dieselbe Reiterzeile (etwa Fachglossar und Graph, BG2c); ihre
     * {@code tools} stehen oben rechts, nur solange er gewählt ist. {@code onShown} erfährt, ob er zu sehen ist.
     */
    void tab(String name, JComponent view, Consumer<Boolean> onShown, JComponent... tools) {
        foreign.put(name, Objects.requireNonNull(onShown, "onShown"));
        foreignTools.put(name, List.of(tools));
        for (JComponent tool : tools) {
            tool.setVisible(name.equals(tabs.selected()));
        }
        tabs.tab(name, view);
        tabs.actions(tools);
    }

    /** Ob ein Reiter des Stands gewählt ist (Aussagen, Zuerst lesen, Mögliche Widersprüche), kein fremder. */
    boolean ownTabSelected() {
        return !foreign.containsKey(tabs.selected());
    }

    /** Sagt jedem fremden Reiter, ob er jetzt zu sehen ist. */
    private void tellForeign(String selected) {
        foreign.forEach((name, onShown) -> onShown.accept(shown && name.equals(selected)));
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
        deletedNotice = null;
        writeNotice = null;
        dirty = true;
        reading.source(selection == null ? null : (topic, stop) -> {
            Actions current = actions.get();
            if (current == null) {
                throw new CancellationException("keine laufende App");
            }
            return current.reading(selection, topic, stop);
        });
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
        reading.shown(shown && READING.equals(tabs.selected()));
        tellForeign(tabs.selected());
        loadIfShown();
    }

    private void loadIfShown() {
        // Auf fremden Reitern wird der Stand nicht gelesen; erst wenn einer seiner Reiter gewählt wird.
        if (shown && dirty && selection != null && !foreign.containsKey(tabs.selected())) {
            load();
        }
    }

    /** Die Einstellungen wurden neu geladen (etwa die Wörter für den Stand): beim nächsten Zeigen neu lesen. */
    void settingsChanged() {
        if (selection != null) {
            changed();
        }
    }

    /** Liest die Sicht neu, etwa nach einer Entscheidung oder dem Ende einer Aussagenanalyse. */
    private void changed() {
        dirty = true;
        reading.changed();
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
        ExportPanel.Selection chosen = selection;
        Filter filter = filter();
        busy.setBusy(true);
        runner.run(() -> {
            StatusQuoView read = current.view(chosen, stop::get);
            StatementConflicts found = StatementConflicts.of(read);
            return new Loaded(read, rows(read, filter), found, conflictRows(found));
        }, loaded -> {
            if (request != generation) {
                return;
            }
            busy.setBusy(false);
            view = loaded.view();
            sentences.clear();
            asked.clear();
            sentenceGeneration++;
            counts(view);
            show(loaded.rows());
            conflicts = loaded.conflicts();
            conflictModel.clear();
            conflictModel.addAll(loaded.conflictRows());
            conflictLabel();
            fetchSentences(conflictList, conflictModel);
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

    private record Loaded(StatusQuoView view, List<Row> rows, StatementConflicts conflicts, List<Row> conflictRows) {
    }

    /**
     * Die Zeilen für „Mögliche Widersprüche“: je Hinweis ein Kopf mit der Sachlage, darunter die bejahten und die
     * verneinten Aussagen. Darunter die Ablösungen als eigene Gruppe mit ihrer Art, nie als Warnung.
     */
    static List<Row> conflictRows(StatementConflicts conflicts) {
        List<Row> rows = new ArrayList<>();
        for (StatementConflicts.PossibleContradiction contradiction : conflicts.contradictions()) {
            List<StatusQuoEntry> all = new ArrayList<>(contradiction.affirmed());
            all.addAll(contradiction.negated());
            String modal = switch (contradiction.modality()) {
                case MUSS -> " · muss";
                case SOLL -> " · soll";
                case KANN -> " · kann";
                case DARF -> " · darf";
                case NONE, UNKNOWN -> "";
            };
            String head = SITUATION + situation(contradiction.situation(), all) + modal + " · bejaht und verneint";
            rows.add(new Group(head, Comic.STANDING_UNCLEAR, all.size()));
            all.forEach(entry -> rows.add(new Line(entry, true)));
        }
        if (!conflicts.replacements().isEmpty()) {
            rows.add(new Group(REPLACEMENTS, Comic.INK, conflicts.replacements().size()));
            for (StatementConflicts.Replacement replacement : conflicts.replacements()) {
                List<StatusQuoEntry> all = new ArrayList<>(replacement.current());
                all.addAll(replacement.outdated());
                rows.add(new Group(replacementKind(replacement.kind()) + ": " + replacement.predicate()
                        + (replacement.object().isEmpty() ? "" : " · " + replacement.object()), Comic.MUTED,
                        all.size()));
                all.forEach(entry -> rows.add(new Line(entry, true)));
            }
        }
        return rows;
    }

    static String replacementKind(StatementConflicts.ReplacementKind kind) {
        return switch (kind) {
            case NEGATION -> "anders verneint";
            case ACTOR -> "anderer Handelnder";
        };
    }

    /**
     * Die Sachlage in Grundformen; der Handelnde in der Schreibweise des ersten Eintrags, wenn dort einer steht.
     */
    private static String situation(com.aresstack.spaceharvester.application.standing.Situation situation,
                                     List<StatusQuoEntry> entries) {
        String subject = situation.subject();
        for (StatusQuoEntry entry : entries) {
            if (entry.subject() != null && !entry.subject().isBlank()) {
                subject = entry.subject();
                break;
            }
        }
        // Die Teile mit „ · “ getrennt, damit Handelnder, Verb und Beteiligte als Teile lesbar bleiben.
        StringBuilder text = new StringBuilder(subject).append(" · ").append(situation.predicate());
        for (com.aresstack.spaceharvester.application.standing.Situation.Participant other : situation.others()) {
            text.append(" · ").append(other.marker() == null ? "" : other.marker() + " ").append(other.key());
        }
        return text.toString();
    }

    /** Erst anmelden, dann lesen (ADR 0027): Entscheidungen und das Ende von „Aussagen analysieren“. */
    private void watch(Actions current) {
        if (current == watched) {
            return;
        }
        standingWatch.close();
        jobWatch.close();
        analysesWatch.close();
        watched = current;
        watchFailed = false;
        try {
            // A local deletion refreshes the view after it completes; avoid racing its own observer.
            analysesWatch = current.subscribeAnalyses(() -> {
                if (!deleting) {
                    SwingUtilities.invokeLater(this::changed);
                }
            });
        } catch (RuntimeException e) {
            analysesWatch = Subscription.NONE;
            watchFailed = true;
        }
        try {
            standingWatch = current.subscribeStanding(() -> SwingUtilities.invokeLater(this::changed));
        } catch (RuntimeException e) {
            standingWatch = Subscription.NONE;
            watchFailed = true;
        }
        try {
            jobWatch = current.subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    if (rereads(status)) {
                        SwingUtilities.invokeLater(StandingTab.this::changed);
                    }
                }
            });
        } catch (RuntimeException e) {
            jobWatch = Subscription.NONE;
            watchFailed = true;
        }
    }

    /**
     * Welche Aufträge die Sicht ändern, wenn sie enden: „Aussagen analysieren“ und „Aussagen prüfen“ (die Urteile
     * ändern die wirksame Stufe, also auch „unsicher“).
     */
    static boolean rereads(JobStatus status) {
        return status.state().terminal()
                && (com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs.isAnalysis(status)
                || status.type() == JobType.STATEMENT_CHECK);
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
                search.getText(), byBearer, staleChip.isSelected());
    }

    private void counts(StatusQuoView view) {
        for (Map.Entry<Standing, CountChip> chip : chips.entrySet()) {
            chip.getValue().count(view == null ? 0 : view.counts().getOrDefault(chip.getKey(), 0));
        }
        int stale = view == null ? 0 : view.staleEntries();
        staleChip.count(stale);
        staleChip.setVisible(stale > 0);
    }

    private void status() {
        if (foreign.containsKey(tabs.selected())) {
            return;
        }
        if (READING.equals(tabs.selected())) {
            reading.status();
            return;
        }
        if (view == null) {
            return;
        }
        if (CONFLICTS.equals(tabs.selected())) {
            int found = conflicts == null ? 0 : conflicts.contradictions().size();
            int replaced = conflicts == null ? 0 : conflicts.replacements().size();
            conflictList.placeholder(NO_CONFLICTS);
            String text = (found == 0 ? NO_CONFLICTS
                    : Texts.count(found, "möglicher Widerspruch", "mögliche Widersprüche"))
                    + " · " + Texts.count(replaced, "Ablösung", "Ablösungen");
            onStatus.accept(noticed(watchFailed ? text + " · " + NOT_WATCHING : text));
            return;
        }
        int shownLines = 0;
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i) instanceof Line) {
                shownLines++;
            }
        }
        int stale = view.staleEntries();
        boolean staleShown = staleChip.isSelected();
        int all = view.entries().size() - (staleShown ? 0 : stale);
        int pages = view.pages().size();
        list.placeholder(view.entries().isEmpty() ? NO_STATEMENTS + ". " + HOW_TO_ANALYZE
                : empty(view.entries().size(), stale, staleShown));
        String text = all == 0 ? empty(view.entries().size(), stale, staleShown)
                : (shownLines == all ? Texts.count(all, "Aussage", "Aussagen")
                : shownLines + " von " + Texts.count(all, "Aussage", "Aussagen"))
                + " auf " + Texts.count(pages, "Seite", "Seiten");
        onStatus.accept(noticed(watchFailed ? text + " · " + NOT_WATCHING : text));
    }

    /** Das Ergebnis des letzten Löschens vor der Zählung, solange dieselbe Auswahl gilt. */
    private String noticed(String text) {
        String withDeleted = deletedNotice == null ? text : deletedNotice + " · " + text;
        return writeNotice == null ? withDeleted : writeNotice + " · " + withDeleted;
    }

    /** Die Rückfrage vor dem Löschen, mit dem Namen des gewählten Knotens. */
    static String deleteQuestion(ExportPanel.Selection selection) {
        return "Wirklich die Aussagen-Analysen " + where(selection) + " löschen? Die Seiten bleiben.";
    }

    /** Die Rückfrage vor „Alles löschen“: Analysen und KI-Urteile. */
    static String deleteEverythingQuestion(ExportPanel.Selection selection) {
        return EVERYTHING_PREFIX + where(selection) + " löschen, also die Aussagen-Analysen und die KI-Urteile aus "
                + "„Aussagen prüfen“? Die Seiten bleiben.";
    }

    /**
     * Wovon gelöscht wird: alle Wissensquellen, ein Knoten samt Unterseiten oder die Favoriten eines Ordners samt
     * Unterseiten (ADR 0034: nur die aufgelösten Seiten, nie eine ganze Quelle).
     */
    private static String where(ExportPanel.Selection selection) {
        return switch (selection) {
            case ExportPanel.Selection.All ignored -> "aller Wissensquellen";
            case ExportPanel.Selection.Node node -> "von „" + node.title() + "“ samt Unterseiten";
            case ExportPanel.Selection.Collection collection -> "der Favoriten in „" + collection.title()
                    + "“ samt Unterseiten";
        };
    }

    /** Das Menü hinter dem Dreieck neben dem Papierkorb. */
    javax.swing.JPopupMenu moreDeleteMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        Actions current = actions.get();
        boolean possible = current != null && current.canDeleteEverything();
        javax.swing.JMenuItem everything = new javax.swing.JMenuItem(DELETE_EVERYTHING, Glyph.TRASH);
        everything.setFont(Comic.regular(13f));
        everything.setToolTipText(possible ? DELETE_EVERYTHING_TIP : DELETE_EVERYTHING_MISSING);
        everything.setEnabled(possible && selection != null && !deleting);
        everything.addActionListener(e -> deleteEverything());
        menu.add(everything);
        return menu;
    }

    /**
     * „Aussagen-Analysen löschen“: nach der Rückfrage im Hintergrund unter dem Ladekreis, absteigend ab dem gewählten
     * Knoten (Wissensquellen-Kopf, Quelle, Space, Ordner). Danach liest der Reiter über den Beobachter neu. Die
     * KI-Urteile des Prüfschritts bleiben.
     */
    void deleteAnalyses() {
        delete(false);
    }

    /** „Alles löschen“ im Menü neben dem Papierkorb: wie {@link #deleteAnalyses}, dazu die KI-Urteile. */
    void deleteEverything() {
        delete(true);
    }

    private void delete(boolean everything) {
        Actions current = actions.get();
        ExportPanel.Selection chosen = selection;
        if (current == null || chosen == null || deleting || everything && !current.canDeleteEverything()
                || !confirmDelete.test(everything ? deleteEverythingQuestion(chosen) : deleteQuestion(chosen))) {
            return;
        }
        watch(current);
        deleting = true;
        deleteAnalyses.setEnabled(false);
        moreDelete.setEnabled(false);
        busy.setText(everything ? "Aussagen-Analysen und KI-Urteile werden gelöscht …"
                : "Aussagen-Analysen werden gelöscht …");
        busy.setBusy(true);
        runner.run(() -> everything ? current.deleteEverything(chosen) : current.deleteAnalyses(chosen), deleted -> {
            deleting = false;
            deleteAnalyses.setEnabled(true);
            moreDelete.setEnabled(true);
            busy.setText(BUSY);
            busy.setBusy(false);
            if (!chosen.equals(selection)) {
                return;
            }
            deletedNotice = deleted == 0
                    ? (chosen.global() ? "Keine Aussagen-Analysen vorhanden"
                    : "Keine Aussagen-Analysen unter „" + chosen.title() + "“")
                    : Texts.count(deleted, "Aussagen-Analyse", "Aussagen-Analysen") + " gelöscht";
            if (everything) {
                deletedNotice += " · KI-Urteile aus „Aussagen prüfen“ gelöscht";
            }
            // Nach erfolgreichem Löschen immer neu lesen, auch wenn der Beobachter sich schon vor dem Ergebnis
            // gemeldet hat; sonst überschreibt der alte Stand die frischen Zahlen.
            if (deleted > 0) {
                changed();
            } else {
                status();
            }
        }, failure -> {
            deleting = false;
            deleteAnalyses.setEnabled(true);
            moreDelete.setEnabled(true);
            busy.setText(BUSY);
            busy.setBusy(false);
            onStatus.accept("Aussagen-Analysen nicht gelöscht: " + failure.getClass().getSimpleName());
        });
    }

    /** Für Tests: statt des Dialogs; bekommt die Frage. */
    void confirmDeleteWith(Predicate<String> confirm) {
        confirmDelete = Objects.requireNonNull(confirm, "confirm");
    }

    IconButton deleteAnalysesButton() {
        return deleteAnalyses;
    }

    IconButton moreDeleteButton() {
        return moreDelete;
    }

    /**
     * Der Satz in der leeren Liste: ganz ohne Analyse, nur veraltete (solange ausgeblendet) oder ohne Treffer.
     */
    static String empty(int entries, int stale, boolean staleShown) {
        if (entries == 0) {
            return NO_STATEMENTS;
        }
        if (!staleShown && entries == stale) {
            return "Keine aktuellen Aussagen. " + (stale == 1 ? "Eine stammt" : stale + " stammen")
                    + " aus veralteten Analysen.";
        }
        return NO_MATCH;
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
        fetchSentences(list, model);
    }

    /** Wie viele Zeilen über und unter dem sichtbaren Ausschnitt der Wortlaut mitgeholt wird. */
    private static final int SENTENCE_MARGIN = 40;

    /**
     * Holt den Originalsatz für die sichtbaren Zeilen (und einen Rand), die ihn noch nicht haben, im Hintergrund; danach
     * zeichnet die Liste neu. Zehntausende Aussagen bleiben so billig.
     */
    /** Holt den Wortlaut für die sichtbaren Zeilen einer Liste (Aussagen oder Widersprüche) samt Rand. */
    private void fetchSentences(JList<Row> list, DefaultListModel<Row> model) {
        Actions current = actions.get();
        if (current == null || model.isEmpty()) {
            return;
        }
        int first = list.getFirstVisibleIndex();
        int last = list.getLastVisibleIndex();
        if (first < 0) {
            first = 0;
            last = Math.min(model.size() - 1, 2 * SENTENCE_MARGIN);
        }
        List<StatusQuoEntry> missing = new ArrayList<>();
        for (int i = Math.max(0, first - SENTENCE_MARGIN); i <= Math.min(model.size() - 1, last + SENTENCE_MARGIN);
             i++) {
            if (model.get(i) instanceof Line line && asked.add(line.entry().statement().id())) {
                missing.add(line.entry());
            }
        }
        if (missing.isEmpty()) {
            return;
        }
        int request = sentenceGeneration;
        runner.run(() -> current.sentences(missing), found -> {
            if (request != sentenceGeneration) {
                return;
            }
            sentences.putAll(found);
            this.list.repaint();
            conflictList.repaint();
        }, failure -> missing.forEach(entry -> asked.remove(entry.statement().id())));
    }

    /** Die Zahl der möglichen Widersprüche an der Pille, ohne Zahl bei null; Ablösungen zählen nicht mit. */
    private void conflictLabel() {
        int found = conflicts == null ? 0 : conflicts.contradictions().size();
        javax.swing.AbstractButton pill = tabs.button(CONFLICTS);
        pill.setText(found == 0 ? CONFLICTS : CONFLICTS + " · " + found);
        pill.revalidate();
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
            staleGroup(view, filter, rows);
            return rows;
        }
        Map<Standing, List<StatusQuoEntry>> groups = new LinkedHashMap<>();
        for (Standing standing : Standing.values()) {
            groups.put(standing, new ArrayList<>());
        }
        for (StatusQuoEntry entry : view.entries()) {
            if (!entry.analysisStale() && keeps(entry, filter)) {
                groups.get(entry.standing().standing()).add(entry);
            }
        }
        groups.forEach((standing, entries) -> {
            if (!entries.isEmpty()) {
                rows.add(new Group(word(standing), tone(standing), entries.size()));
                entries.forEach(entry -> rows.add(new Line(entry, false)));
            }
        });
        staleGroup(view, filter, rows);
        return rows;
    }

    /**
     * Eingeblendete Einträge aus veralteten Analysen als eigene Gruppe „veraltet“ am Ende: Ihr Stand sagt nichts aus,
     * also stehen sie unter keinem Stand und die Gruppen der Stände zählen wie die Chips nur Aktuelles.
     */
    private static void staleGroup(StatusQuoView view, Filter filter, List<Row> rows) {
        List<StatusQuoEntry> stale = view.entries().stream()
                .filter(entry -> entry.analysisStale() && keeps(entry, filter)).toList();
        if (!stale.isEmpty()) {
            rows.add(new Group(STALE_WORD, Comic.MUTED, stale.size()));
            stale.forEach(entry -> rows.add(new Line(entry, false)));
        }
    }

    static boolean keeps(StatusQuoEntry entry, Filter filter) {
        if (entry.analysisStale()) {
            // Nur die Suche wirkt auf sie; Stand-Chips und Filter beziehen sich auf aktuelle Aussagen.
            return filter.stale() && matches(entry, filter);
        }
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
        return matches(entry, filter);
    }

    private static boolean matches(StatusQuoEntry entry, Filter filter) {
        String text = filter.text() == null ? "" : filter.text().strip().toLowerCase(Locale.ROOT);
        return text.isEmpty() || haystack(entry).contains(text);
    }

    /**
     * Worin gesucht wird: Handelnder bzw. Träger, Prädikat, die übrigen Beteiligten (Grundform, Präposition), Seite und
     * vereinfachter Satz. Alles liegt an jedem Eintrag; der nachgeladene Originalsatz zählt bewusst nicht, sonst hinge
     * das Ergebnis vom Rollstand ab.
     */
    static String haystack(StatusQuoEntry entry) {
        StringBuilder all = new StringBuilder();
        if (entry.situation() != null) {
            for (com.aresstack.spaceharvester.application.standing.Situation.Participant other
                    : entry.situation().others()) {
                all.append(other.marker() == null ? "" : other.marker() + " ").append(other.key()).append(' ');
            }
        }
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

    /**
     * Was ein Mensch im Eintrag gesetzt hat: Stand und/oder Prüfung (ADR 0032 W3); ein Eintrag kann nur die Prüfung
     * tragen.
     */
    static String decisionWord(StandingDecision decision) {
        List<String> parts = new ArrayList<>();
        if (decision.standing() != null) {
            parts.add(word(decision.standing()));
        }
        if (decision.review() != null) {
            parts.add(reviewWord(decision.review()));
        }
        return String.join(" · ", parts);
    }

    static String reviewWord(ParsedStatements.Review review) {
        return review == ParsedStatements.Review.REJECTED ? "verworfen" : "bestätigt";
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
     * Die Zeile in Worten: der Originalsatz im Wortlaut (FL6, {@code StatusQuo#sentences}), solange er noch zum
     * Lesetext passt; sonst der vereinfachte Satz der Vorstufe, sonst Handelnder bzw. Träger und Prädikat.
     */
    static String text(StatusQuoEntry entry, SentenceText sentence) {
        if (sentence != null && sentence.original() != null && !sentence.original().isBlank()) {
            return sentence.original().strip().replaceAll("\\s+", " ");
        }
        return text(entry);
    }

    /** Der Ersatz ohne Originalsatz: vereinfachter Satz der Vorstufe, sonst Handelnder bzw. Träger und Prädikat. */
    static String text(StatusQuoEntry entry) {
        String simplified = entry.statement().derivation().simplifiedSentence();
        if (entry.origin() == ParsedStatements.Origin.VORSTUFE && simplified != null && !simplified.isBlank()) {
            return simplified.strip();
        }
        String subject = entry.subject() == null || entry.subject().isBlank() ? "" : entry.subject().strip() + " ";
        return subject + entry.statement().predicateText();
    }

    /** Herkunft in der grauen Zeile unter dem Satz, wenn die Aussage nicht aus dem Originalsatz stammt. */
    static final String FROM_STAGE = "Vorstufe (KI)";
    static final String FROM_MODEL = CheckTexts.ORIGIN_MODEL + " (KI)";

    /** Was unter dem Satz steht: Seite, Herkunft und Kennzeichen, die nicht in der Farbe stecken dürfen. */
    static String meta(StatusQuoEntry entry) {
        List<String> parts = new ArrayList<>();
        parts.add(entry.page().title() == null || entry.page().title().isBlank() ? entry.page().key().pageId()
                : entry.page().title());
        // Nicht aus dem Originalsatz: schon in der Zeile als Wort, nicht erst im Overlay (Auflage AT).
        if (entry.origin() == ParsedStatements.Origin.VORSTUFE) {
            parts.add(FROM_STAGE);
        } else if (entry.origin() == ParsedStatements.Origin.MODELL) {
            parts.add(FROM_MODEL);
        }
        if (entry.kind() == StatementKind.STATE) {
            parts.add("Zustand");
        }
        // A1: Unsicheres trägt das Wort „Vorschlag“ (neutral, nie rot); ein Urteil von Hand geht vor.
        String marker = StatementProvenance.marker(entry.statement());
        if (marker != null) {
            parts.add(marker);
        } else if (entry.uncertain()) {
            parts.add(StatementProvenance.PROPOSAL);
        }
        // Eine Prüfung von Hand zeigt schon der Marker; „von Hand“ meint den Stand.
        if (entry.decision() != null && entry.decision().hasStanding()) {
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

    static String details(StatusQuoEntry entry) {
        return details(entry, null);
    }

    /** Die Details einer Zeile für das Overlay, mit Originalsatz, wenn er schon geholt ist. */
    static String details(StatusQuoEntry entry, SentenceText sentence) {
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
        if (sentence != null && sentence.original() != null && !sentence.original().isBlank()) {
            lines.add("Originalsatz: „" + sentence.original().strip().replaceAll("\\s+", " ") + "“");
        } else if (sentence != null && sentence.stale()) {
            lines.add("Originalsatz: nicht mehr ermittelbar, der Text der Seite hat sich geändert.");
        }
        // A1: Herkunft und Prüfstatus wie im Reiter „Aussagen“, aus derselben Stelle.
        lines.add(StatementProvenance.details(entry.statement(), null, null));
        if (entry.uncertain()) {
            lines.add(StatementProvenance.PROPOSAL + ": zählt nicht für den Stand der Seite.");
        }
        if (entry.analysisStale()) {
            lines.add("Die Analyse passt nicht mehr zum Text der Seite.");
        }
        if (entry.stateChange()) {
            lines.add("Hinweis: Zustandswechsel („wird zuständig“).");
        }
        StandingDecision decision = entry.decision();
        if (decision != null) {
            lines.add("Von Hand: " + decisionWord(decision) + " · " + decided(decision));
        }
        return String.join("\n", lines);
    }

    private static String decided(StandingDecision decision) {
        String who = decision.decidedBy() == null ? "" : decision.decidedBy();
        String when = decision.decidedAt() == null ? "" : WHEN.format(decision.decidedAt());
        return (who + (who.isEmpty() || when.isEmpty() ? "" : ", ") + when).strip();
    }

    private void details(Row row, int x, int y) {
        details(list, row, x, y);
    }

    private void details(JList<Row> owner, Row row, int x, int y) {
        popup.clearLinks();
        popup.controls(null);
        if (row instanceof Line line) {
            StatusQuoEntry entry = line.entry();
            popup.text(entry.page().title() == null ? entry.page().key().pageId() : entry.page().title(),
                    details(entry, sentences.get(entry.statement().id())));
            popup.controls(decisionControls(entry));
            popup.link(OPEN_TEXT, () -> {
                popup.setVisible(false);
                jump(entry);
            });
        } else if (row instanceof Stale stale) {
            StandingDecision decision = stale.decision();
            popup.text(STALE_GROUP, "Von Hand „" + decisionWord(decision) + "“ zu einer Aussage, die es in der "
                    + "aktuellen Analyse nicht mehr gibt (Text geändert oder Vorstufe entzogen).\nSeite: "
                    + pageName(decision.page()) + "\n" + decided(decision));
            popup.controls(staleControls(decision));
        } else {
            return;
        }
        popupOwner = owner;
        if (owner.isShowing()) {
            popup.showAt(owner, x, y);
        }
    }

    /** Der Titel einer Seite, wenn sie in der Sicht vorkommt, sonst ihre ID (Entscheidungen ohne Aussage). */
    private String pageName(PageKey page) {
        StatusQuoView current = view;
        if (current != null) {
            for (com.aresstack.spaceharvester.application.standing.PageStanding standing : current.pages()) {
                if (standing.page().key().equals(page) && standing.page().title() != null
                        && !standing.page().title().isBlank()) {
                    return standing.page().title();
                }
            }
        }
        return page.pageId();
    }

    /**
     * Stand von Hand im Overlay: vier Wortpillen (die gesetzte ist gefüllt), Löschen und Protokoll als Icon-Knöpfe.
     * Keine Standardknöpfe; das Overlay schließt mit Esc.
     */
    private JComponent decisionControls(StatusQuoEntry entry) {
        return decisionControls(entry.page().key(), entry.statement().id(), entry.decision(), SET_LABEL);
    }

    /**
     * Stand der ganzen Seite von Hand (Overlay in „Zuerst lesen“): dieselben Pillen, Löschen und Protokoll wie an der
     * Aussage, mit {@code statementId null}.
     */
    private JComponent pageControls(com.aresstack.spaceharvester.application.standing.PageStanding standing) {
        popupOwner = reading.rowList();
        StandingDecision decision = standing.decision();
        return decisionControls(standing.page().key(), null, decision != null && decision.statementId() == null
                ? decision : null, PAGE_SET_LABEL);
    }

    private JComponent decisionControls(PageKey page, String statementId, StandingDecision decision, String title) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        JLabel label = new JLabel(title);
        label.setFont(Comic.regular(12.5f));
        label.setForeground(Comic.MUTED);
        row.add(label);
        long revision = decision == null ? 0 : decision.revision();
        // Gesperrte Seite: der Stand bleibt sichtbar, ändern lässt er sich nicht (der Core lehnt auch ab).
        boolean locked = locked(page);
        for (Standing standing : Standing.values()) {
            CountChip chip = CountChip.choice(word(standing), tone(standing));
            chip.setSelected(decision != null && decision.standing() == standing);
            if (locked) {
                chip.setEnabled(false);
                chip.setToolTipText(LOCKED_PAGE);
            }
            chip.addActionListener(e -> {
                popup.setVisible(false);
                if (decision != null && decision.standing() == standing) {
                    return;
                }
                write(() -> actions.get().decide(page, statementId, standing, revision),
                        "Stand von Hand gesetzt: „" + word(standing) + "“");
            });
            row.add(chip);
        }
        IconButton clear = new IconButton(Glyph.TRASH, locked ? LOCKED_PAGE : CLEAR_TIP).compact();
        clear.setEnabled(!locked && decision != null && decision.hasStanding());
        clear.addActionListener(e -> clear(page, statementId, revision));
        IconButton history = new IconButton(Glyph.TABLE, HISTORY_TIP).compact();
        history.addActionListener(e -> history(page, statementId));
        row.add(clear);
        row.add(history);
        return row;
    }

    /** Eine Entscheidung ohne Aussage lässt sich nur löschen (neu entscheiden geht an der neuen Aussage). */
    private JComponent staleControls(StandingDecision decision) {
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row.setOpaque(false);
        boolean locked = locked(decision.page());
        IconButton clear = new IconButton(Glyph.TRASH, locked ? LOCKED_PAGE : CLEAR_TIP).compact();
        // Löschen nimmt nur den Stand; eine Prüfung nimmt ihr eigener Schalter zurück.
        clear.setEnabled(!locked && decision.hasStanding());
        clear.addActionListener(e -> clear(decision.page(), decision.statementId(), decision.revision()));
        IconButton history = new IconButton(Glyph.TABLE, HISTORY_TIP).compact();
        history.addActionListener(e -> history(decision.page(), decision.statementId()));
        row.add(clear);
        row.add(history);
        return row;
    }

    /** Ob die Seite laut der gelesenen Sicht im Bestand gesperrt ist. */
    private boolean locked(PageKey page) {
        StatusQuoView current = view;
        return current != null && current.locked(page);
    }

    private void clear(PageKey page, String statementId, long revision) {
        popup.setVisible(false);
        if (!confirmClear.getAsBoolean()) {
            return;
        }
        write(() -> actions.get().clear(page, statementId, revision), "Entscheidung von Hand gelöscht");
    }

    /**
     * Schreibt im Hintergrund. Das neue Lesen stößt der Core über {@code subscribeStanding} an; ist diese Anmeldung
     * fehlgeschlagen, wird nach dem Schreiben selbst neu gelesen. Passt die Revision nicht mehr, wird sofort neu
     * gelesen und die Statuszeile sagt es.
     */
    private void write(Callable<?> work, String done) {
        if (actions.get() == null) {
            return;
        }
        writeNotice = null;
        runner.run(work, result -> {
            onStatus.accept(done);
            if (watchFailed) {
                changed();
            }
        }, failure -> {
            if (failure instanceof StandingConflictException) {
                onStatus.accept("Inzwischen von anderer Seite geändert; der Stand wurde neu gelesen");
                changed();
            } else if (failure instanceof AccessDeniedException) {
                onStatus.accept(NO_RIGHT_TO_DECIDE);
            } else if (failure instanceof LockedPageException) {
                // Inzwischen gesperrt: neu lesen, damit die Schalter sich sperren; die Statuszeile sagt es weiter.
                writeNotice = LOCKED_REFUSED;
                onStatus.accept(LOCKED_REFUSED);
                changed();
            } else {
                onStatus.accept("Stand nicht gesetzt (" + failure.getClass().getSimpleName() + ")");
            }
        });
    }

    /** Das Protokoll im selben Overlay statt der Details. */
    private void history(PageKey page, String statementId) {
        Actions current = actions.get();
        if (current == null) {
            return;
        }
        JComponent owner = popupOwner;
        Point at = popup.isVisible() && owner.isShowing() ? historyAt(owner) : null;
        runner.run(() -> current.history(page, statementId), entries -> {
            popup.clearLinks();
            popup.controls(null);
            popup.text("Protokoll", history(entries));
            if (at != null && owner.isShowing()) {
                popup.showAt(owner, at.x, at.y);
            }
        }, failure -> onStatus.accept(failure instanceof AccessDeniedException ? NO_RIGHT_TO_HISTORY
                : "Protokoll nicht lesbar (" + failure.getClass().getSimpleName() + ")"));
    }

    private Point historyAt(JComponent owner) {
        Point screen = popup.getLocationOnScreen();
        SwingUtilities.convertPointFromScreen(screen, owner);
        return screen;
    }

    static String history(List<StandingDecisionStore.AuditEntry> entries) {
        if (entries.isEmpty()) {
            return "Noch keine Entscheidung von Hand.";
        }
        List<String> lines = new ArrayList<>();
        for (StandingDecisionStore.AuditEntry entry : entries) {
            String what = switch (entry.action()) {
                case StandingDecisionStore.AuditEntry.REMOVE -> entry.standing() == null ? "gelöscht"
                        : "gelöscht (war „" + word(entry.standing()) + "“)";
                case StandingDecisionStore.AuditEntry.REVIEW -> entry.review() == null ? "Prüfung gesetzt"
                        : "„" + reviewWord(entry.review()) + "“ gesetzt";
                case StandingDecisionStore.AuditEntry.UNREVIEW -> entry.review() == null ? "Prüfung zurückgenommen"
                        : "Prüfung zurückgenommen (war „" + reviewWord(entry.review()) + "“)";
                default -> entry.standing() == null ? "gesetzt" : "„" + word(entry.standing()) + "“ gesetzt";
            };
            lines.add(WHEN.format(entry.at()) + " · " + entry.actor() + " · " + what);
        }
        return String.join("\n", lines);
    }

    /** Markiert den ganzen Satz, wenn seine Grenzen bekannt sind, sonst die Stelle der Aussage. */
    private void jump(StatusQuoEntry entry) {
        SentenceText sentence = sentences.get(entry.statement().id());
        boolean whole = sentence != null && !sentence.stale() && sentence.sentenceStart() >= 0
                && sentence.sentenceEnd() > sentence.sentenceStart();
        onJump.accept(new Target(entry.page().key().sourceId(), entry.page().key().pageId(),
                whole ? sentence.sentenceStart() : entry.statement().start(),
                whole ? sentence.sentenceEnd() : entry.statement().end()));
    }

    static String key(StatusQuoEntry entry) {
        return entry.page().key().sourceId() + "/" + entry.page().key().pageId() + "#" + entry.statement().id();
    }

    /** Zeichnet nur, was sichtbar ist; dieselben Komponenten für jede Zeile. */
    private final class Renderer implements ListCellRenderer<Row> {
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
                words = text(entry, sentences.get(entry.statement().id()));
                markText = mark(entry);
                metaText = meta(entry);
                nested = l.nested();
            } else {
                StandingDecision decision = ((Stale) row).decision();
                standing = decision.standing();
                words = "Entscheidung ohne Aussage";
                markText = "";
                metaText = "Seite " + pageName(decision.page()) + " · löschen im Overlay";
            }
            boolean stale = row instanceof Line l && l.entry().analysisStale();
            // Eine veraltete Entscheidung kann nur die Prüfung tragen (ADR 0032 W3): dann ohne Stand.
            Color tone = stale || standing == null ? Comic.MUTED : tone(standing);
            boolean faded = stale || standing == Standing.UEBERHOLT;
            badge.setText(stale ? STALE_WORD : standing == null
                    ? reviewWord(((Stale) row).decision().review()) : word(standing));
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

    CountChip staleChip() {
        return staleChip;
    }

    /** Der Satz, den die leere Aussagenliste zeigt. */
    String placeholder() {
        return list.showsPlaceholder() ? list.placeholder() : null;
    }

    /** Der Satz, den die leere Liste „Mögliche Widersprüche“ zeigt. */
    String conflictPlaceholder() {
        return conflictList.showsPlaceholder() ? conflictList.placeholder() : null;
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

    ReadingFirstView reading() {
        return reading;
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

    List<Row> conflictRows() {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < conflictModel.size(); i++) {
            rows.add(conflictModel.get(i));
        }
        return rows;
    }

    JComponent statementsView() {
        return busy;
    }

    /** Für Tests: die Rückfrage vor dem Löschen. */
    void confirmClear(BooleanSupplier confirm) {
        this.confirmClear = Objects.requireNonNull(confirm, "confirm");
    }

    /** Für Tests: die Wortpille „Stand von Hand“ im offenen Overlay. */
    CountChip decisionChip(Standing standing) {
        return find(popup, CountChip.class, chip -> chip.word().equals(word(standing)));
    }

    /** Für Tests: ein Icon-Knopf im offenen Overlay nach Tooltip. */
    IconButton overlayButton(String tip) {
        return find(popup, IconButton.class, button -> tip.equals(button.getToolTipText()));
    }

    private static <T extends Component> T find(java.awt.Container root, Class<T> type,
                                                java.util.function.Predicate<T> match) {
        for (Component child : root.getComponents()) {
            if (type.isInstance(child) && match.test(type.cast(child))) {
                return type.cast(child);
            }
            if (child instanceof java.awt.Container container) {
                T found = find(container, type, match);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
