package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.controls.InfoPopup;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.TabHeader;
import com.aresstack.spaceharvester.app.ui.controls.WrappedText;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.knowledge.PageGraph;
import com.aresstack.spaceharvester.application.knowledge.PageStatements;
import com.aresstack.spaceharvester.application.knowledge.export.KnowledgeGraphExportUseCase;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.event.HierarchyEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Der Unterreiter „Analysen → Graph“ (#499 Abschnitt F, ADR 0024): fokussierte, begrenzte Teilgraphen des globalen,
 * nur manuell befüllten Knowledge Graph. Einstieg ist die geöffnete Seite, ein gewählter Knoten oder ein Suchbegriff;
 * gezeigt werden die direkten Nachbarn bis zu einer Knoten- und Kantengrenze, weitere Nachbarschaften lädt
 * „Nachbarn erweitern“ (oder Doppelklick) gezielt nach. Nie wird der ganze Graph geladen oder gezeichnet, und nichts
 * hier startet eine Analyse.
 *
 * <p>Dokument (Rechteck), Aussage (abgerundet) und Erwähnung (Ellipse) sind an der Form unterscheidbar, Kanten
 * gerichtet und benannt. Verbindungen zu anderen Seiten sind gestrichelt: gleiche Grundform ist ein unbestätigter
 * Kandidat, keine Identität. Veraltete Seiten erscheinen grau mit „veraltet“. Die Tabelle zeigt dieselben Angaben
 * ohne Farbe und ist per Tastatur bedienbar. Auswahl zeigt Rolle, Herkunft, Sicherheit und Beleg; Knoten der
 * geöffneten Seite springen zum Beleg im Reiter „Text“.
 *
 * <p>Das Export-Symbol rechts startet den JSON-LD-Export vorhandener Analysen als Auftrag; der Umfang (Seite,
 * Unterzweig, Space, Quelle oder gesamter Graph) wird im Menü gewählt, veraltete Analysen nur mit eigenem Haken dort.
 * Der Export stößt nie eine Analyse an.
 *
 * <p>Alle Methoden nur auf dem EDT; Core-Aufrufe laufen im Hintergrund, späte Ergebnisse werden verworfen.
 */
final class KnowledgeGraphTab extends JPanel {

    static final String NOT_ANALYSED = "Diese Seite ist noch nicht analysiert. Unter „Aussagen“ startet "
            + "„Mit MaltParser analysieren“ die Analyse; der Graph enthält nur manuell analysierte Seiten.";
    static final String EMPTY_TERM = "Keine Erwähnung auf analysierten, für dich sichtbaren Seiten.";
    static final String FILTERED = "Die Seite ist analysiert, aber die gewählten Filter blenden alles aus "
            + "(z. B. „Veraltete zeigen“ oder die Knotenarten).";
    static final String EXPORT_TIP = "Graph exportieren (JSON-LD): Umfang wählen; exportiert nur vorhandene "
            + "Analysen, analysiert nichts";
    static final String EXPORT_STARTED = "Graph-Export gestartet – Fortschritt unter Aufträge";
    static final String FILTERED_TERM = "Treffer gibt es, aber die gewählten Filter blenden sie aus "
            + "(z. B. „Nur diese Quelle“ oder „Veraltete zeigen“).";
    /** Abstand zwischen Knoten bei der Anordnung (Weltkoordinaten). */
    private static final double GAP_X = 18;
    private static final double GAP_Y = 18;
    private static final double NODE_HEIGHT = 34;
    /** Alles sehen, nichts zeichnen: prüft nur, ob es überhaupt etwas gäbe. */
    static final String DELETE_ALL_TIP = "Alle Aussagen löschen: gespeicherte Analysen aller Seiten verwerfen";
    static final String FILTER_TIP = "Filter: welche Knoten und Kanten der Graph zeigt";
    static final String TABLE_TIP = "Als Tabelle zeigen";
    static final String GRAPH_TIP = "Als Grafik zeigen";
    private static final GraphNeighbourhood.Options PROBE = new GraphNeighbourhood.Options(1, 0,
            EnumSet.allOf(PageGraph.NodeKind.class), EnumSet.allOf(GraphNeighbourhood.EdgeType.class), true, null,
            null);

    private final Supplier<Actions> actions;
    private final Runner runner;
    private final Canvas canvas = new Canvas();
    private final EdgeTableModel tableModel = new EdgeTableModel();
    private final JTable table = new JTable(tableModel);
    private final JPanel cards = new JPanel(new CardLayout());
    private final TabHeader head = new TabHeader("Wissensgraph");
    /** Details zum angeklickten Knoten oder zur Kante, an der Mausposition; dort auch „Zum Beleg im Text“. */
    private final InfoPopup popup = new InfoPopup();
    private final JTextField search = new JTextField(16);
    private final JPopupMenu filterMenu = new JPopupMenu();
    private final JCheckBoxMenuItem documents = check("Dokumente");
    private final JCheckBoxMenuItem statements = check("Aussagen");
    private final JCheckBoxMenuItem mentions = check("Erwähnungen");
    private final JCheckBoxMenuItem roles = check("Rollen");
    private final JCheckBoxMenuItem nominal = check("Nominale Bezüge");
    private final JCheckBoxMenuItem crossPage = check("Seitenverbindungen");
    /** Unbestätigte Kandidaten „gleiche Grundform?“; anfangs aus, sonst überdecken sie den Graphen (Angelo). */
    private final JCheckBoxMenuItem candidates = check("Gleiche Grundform (Kandidaten)");
    private final JCheckBoxMenuItem stale = check("Veraltete zeigen");
    private final JCheckBoxMenuItem thisSource = check("Nur diese Quelle");
    /** Nur die Auswahl links im Baum (ADR 0029): die Seiten aus {@link #within}; ohne Auswahl ohne Wirkung. */
    private final JCheckBoxMenuItem selectionOnly = check("Nur Auswahl im Baum");
    private PageSet selectionPages;
    private final IconButton pageButton = new IconButton(Glyph.CROSSHAIR, "Diese Seite: Teilgraph der geöffneten Seite");
    private final IconButton expand = new IconButton(Glyph.NEIGHBOURS,
            "Nachbarn erweitern: lädt die direkten Nachbarn des gewählten Knotens dazu (auch Doppelklick)");
    private final IconButton back = new IconButton(Glyph.BACK, "Zurück zur vorigen Ansicht");
    private final IconButton filter = new IconButton(Glyph.FILTER, FILTER_TIP);
    private final IconButton fit = new IconButton(Glyph.FIT, "Einpassen: ganzen Teilgraphen in die Fläche einpassen");
    private final IconButton view = new IconButton(Glyph.TABLE, TABLE_TIP);
    private final IconButton deleteAll = new IconButton(Glyph.TRASH, DELETE_ALL_TIP);
    private final JButton export = Comic.exportButton(EXPORT_TIP);
    /** Rückmeldung zum Export, in der Statuszeile hinter dem Stand des Graphen; leer ohne. */
    private String exportNote = "";
    private String statusText = " ";
    /** Titel und Text der Details zur Auswahl, auch wenn das Fenster gerade nicht offen ist. */
    private String detailTitle = "";
    private String detailText = "";
    /** Warum der Graph leer ist (Suche ohne Treffer, Filter, nicht analysiert); steht mitten in der Fläche. */
    private String emptyHint;

    private final Map<String, GraphNeighbourhood.Node> nodes = new LinkedHashMap<>();
    private final Map<String, GraphNeighbourhood.Edge> edges = new LinkedHashMap<>();
    private final Map<String, Point2D> positions = new LinkedHashMap<>();
    private final Deque<View> history = new ArrayDeque<>();
    private Consumer<List<TermsTab.Span>> onJump = spans -> { };
    private PageKey page;
    /** Space der geöffneten Seite für den Export-Umfang „Space“; {@code null}, wenn unbekannt. */
    private String spaceKey;
    /** Ob ein Export gerade gestartet wird; so lange bleibt das Symbol gesperrt. */
    private boolean exporting;
    /**
     * Ob der Export auch veraltete Analysen mitnimmt. Unabhängig vom Ansichtsfilter „Veraltete zeigen“ und anfangs
     * aus: veraltete nur auf ausdrücklichen Wunsch (KnowledgeGraphExportUseCase).
     */
    private boolean exportStale;
    /**
     * Zählt nur Seitenwechsel, nicht jede Graph-Abfrage wie {@link #generation}: Filter, Suche, Erweitern oder Zurück
     * während des Exportstarts dürfen die Rückmeldung nicht verschlucken.
     */
    private int pageChanges;
    private boolean pageLoaded;
    /** Womit die Ansicht begann (Seite, Knoten oder Begriff); Filterwechsel wiederholen genau diese Abfrage. */
    private Entry entry;
    /** Danach gezielt erweiterte Knoten, in Reihenfolge; Filterwechsel wiederholen auch sie. */
    private final List<String> expansions = new ArrayList<>();
    /** Gewünschte Erweiterungen inklusive noch laufender; sofort fortgeschrieben, damit keine verloren geht. */
    private final List<String> requested = new ArrayList<>();
    /** Ob eine Abfrage läuft; dann ist der Bildschirm nicht auf dem Stand der Filter. */
    private boolean loading;
    private String focus;
    private String selected;
    private GraphNeighbourhood.Edge selectedEdge;
    private boolean truncated;
    /** Weggelassene Knoten des Einstiegs; nach Erweiterungen nicht mehr genau bekannt ({@link #merged}). */
    private int hidden;
    private boolean merged;
    private int generation;
    private boolean tableShown;
    private boolean syncingTable;
    private boolean deleting;
    private boolean dropHistory;
    private Runnable onDeleted = () -> { };
    private BooleanSupplier confirmDelete = () -> Dialogs.confirm(this, "Alle Aussagen löschen?",
            "Wirklich alle Aussagen aller Seiten löschen?");

    /** @param workspace die laufende App oder {@code null}, solange keine läuft */
    KnowledgeGraphTab(Supplier<Workspace> workspace) {
        this(() -> Actions.of(workspace.get()), new Runner() {
            @Override
            public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        });
    }

    KnowledgeGraphTab(Supplier<Actions> actions, Runner runner) {
        super(new BorderLayout(0, 8));
        this.actions = Objects.requireNonNull(actions, "actions");
        this.runner = Objects.requireNonNull(runner, "runner");
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(6, 2, 2, 2));

        thisSource.setSelected(false);
        selectionOnly.setToolTipText("Fokus, Nachbarn und Begriffssuche nur auf der Auswahl links im Explorer "
                + "samt Unterseiten");
        // Anfangs ohne Kandidatenkanten und ohne veraltete Seiten: beides lässt sich im Filtermenü einschalten.
        candidates.setSelected(false);
        stale.setSelected(false);
        candidates.setToolTipText("Kanten zwischen Erwähnungen mit gleicher Grundform – unbestätigt, nur ein Kandidat");
        head.badge("Experimentell", Comic.PETROL)
                .note("nur manuell analysierte Seiten · Parserbefunde, nicht fachlich geprüft");
        search.setToolTipText("Begriff, Grundform oder Wortlaut; Enter sucht");
        search.addActionListener(e -> showTerm(search.getText()));
        head.search(InputFrame.search(search));
        head.action(pageButton, expand, back);
        head.copy(this::copyText);
        head.tool(filter, fit, view, export, deleteAll);
        for (JCheckBoxMenuItem box : List.of(documents, statements, mentions, roles, nominal, crossPage, candidates,
                stale, thisSource, selectionOnly)) {
            box.addActionListener(e -> reloadFocus());
            if (box == stale) {
                filterMenu.addSeparator();
            }
            filterMenu.add(box);
        }

        pageButton.addActionListener(e -> showPage());
        expand.addActionListener(e -> expandSelected());
        back.addActionListener(e -> back());
        filter.addActionListener(e -> filterMenu.show(filter, 0, filter.getHeight()));
        fit.addActionListener(e -> canvas.fit());
        view.addActionListener(e -> toggleView());
        export.addActionListener(e -> exportMenu().show(export, 0, export.getHeight()));
        deleteAll.addActionListener(e -> deleteAllStatements());

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoCreateRowSorter(true);
        table.setToolTipText("Doppelklick oder Leertaste zeigt die Details – dort auch „Zum Beleg im Text“; "
                + "Enter erweitert");
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!syncingTable && !e.getValueIsAdjusting() && table.getSelectedRow() >= 0) {
                Row row = tableModel.rows.get(table.convertRowIndexToModel(table.getSelectedRow()));
                select(row.node(), row.edge());
            }
        });
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0) {
                    showDetails(table, e.getX(), e.getY());
                }
            }
        });
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "expand");
        table.getActionMap().put("expand", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                expandSelected();
            }
        });
        // Tastatur: Leertaste öffnet die Details zur markierten Zeile (wie der Doppelklick).
        table.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(javax.swing.KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "details");
        table.getActionMap().put("details", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                int row = table.getSelectedRow();
                if (row >= 0) {
                    java.awt.Rectangle cell = table.getCellRect(row, 0, true);
                    showDetails(table, cell.x + 24, cell.y + cell.height / 2);
                }
            }
        });
        cards.setOpaque(false);
        cards.add(canvas, "graph");
        cards.add(Comic.scroll(table), "table");

        add(head, BorderLayout.NORTH);
        add(cards, BorderLayout.CENTER);
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing() && !pageLoaded) {
                showPage();
            }
        });
        refresh();
    }

    private Consumer<String> onStatus = text -> { };

    /** {@code listener} bekommt den Stand des Graphen („50 Knoten, 51 Kanten …“) für die Statuszeile. */
    void onStatus(Consumer<String> listener) {
        onStatus = Objects.requireNonNull(listener, "listener");
    }

    private void status(String text) {
        statusText = text;
        pushStatus();
    }

    private void exportNote(String text) {
        exportNote = text;
        pushStatus();
    }

    /** Stand des Graphen und, solange vorhanden, die Rückmeldung zum Export, in einer Zeile. */
    private void pushStatus() {
        String base = statusText == null ? " " : statusText;
        onStatus.accept(exportNote.isBlank() ? base : base.isBlank() ? exportNote : base + " · " + exportNote);
    }

    /** Der Inhalt als Text für die Zwischenablage: die Tabellenzeilen (Knoten und Kanten), tabulatorgetrennt. */
    String copyText() {
        StringBuilder text = new StringBuilder();
        for (int c = 0; c < tableModel.getColumnCount(); c++) {
            text.append(c > 0 ? "\t" : "").append(tableModel.getColumnName(c));
        }
        for (int r = 0; r < tableModel.getRowCount(); r++) {
            text.append('\n');
            for (int c = 0; c < tableModel.getColumnCount(); c++) {
                Object value = tableModel.getValueAt(r, c);
                text.append(c > 0 ? "\t" : "").append(value == null ? "" : value);
            }
        }
        return text.toString();
    }

    /** {@code listener} springt zu Fundstellen im Reiter „Text“ ({@code PageDetailPanel#jumpTo}). */
    void onJump(Consumer<List<TermsTab.Span>> listener) {
        onJump = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Die geöffnete Seite. Geladen wird ihr Teilgraph erst, wenn der Reiter sichtbar ist; beim Seitenwechsel beginnt
     * eine neue Ansicht.
     */
    void page(PageKey key) {
        page(key, null);
    }

    /** Wie {@link #page(PageKey)}, mit dem Space der Seite für den Export-Umfang „Space“. */
    void page(PageKey key, String spaceKey) {
        page = key;
        this.spaceKey = key == null ? null : spaceKey;
        pageChanges++;
        exportNote("");
        pageLoaded = false;
        generation++;
        history.clear();
        clear();
        entry = null;
        expansions.clear();
        requested.clear();
        loading = false;
        if (isShowing() && page != null) {
            showPage();
        } else {
            refresh();
        }
    }

    /**
     * Die gespeicherte Analyse der geöffneten Seite hat sich geändert: sichtbar sofort neu laden, sonst beim nächsten
     * Anzeigen.
     */
    void analysisChanged() {
        if (page == null) {
            return;
        }
        if (isShowing()) {
            if (entry != null) {
                reloadFocus();
            } else {
                showPage();
            }
        } else {
            pageLoaded = false;
            entry = null;
            expansions.clear();
            requested.clear();
        }
    }

    // ---- Laden

    private void showPage() {
        if (page == null) {
            return;
        }
        pageLoaded = true;
        start(new Entry.Page(page));
    }

    private void showTerm(String term) {
        if (term == null || term.isBlank()) {
            return;
        }
        start(new Entry.Term(term.strip()));
    }

    /** Neue Ansicht ab {@code start}; die bisherige kommt in die Historie. */
    private void start(Entry start) {
        load(start, List.of(), true);
    }

    /**
     * Lädt die Nachbarn des gewählten Knotens dazu. Nur wenn die Anzeige auf dem Stand ist (nichts läuft, Filter
     * unverändert), genügt die eine Abfrage; sonst wiederholt der Reiter Einstieg und alle gewünschten Erweiterungen.
     */
    private void expandSelected() {
        String nodeId = selected;
        if (nodeId == null || entry == null) {
            return;
        }
        requested.add(nodeId);
        boolean current = !loading && filters().equals(appliedFilters);
        load(entry, List.copyOf(requested), !current);
    }

    /** Filter geändert: dieselbe Einstiegsabfrage samt aller Erweiterungen mit den neuen Filtern. */
    private void reloadFocus() {
        if (entry != null) {
            load(entry, List.copyOf(requested), true);
        } else if (page != null && pageLoaded) {
            showPage();
        }
    }

    /**
     * Lädt im Hintergrund; späte Ergebnisse werden verworfen. {@code replay}: Einstiegsabfrage und alle
     * {@code expanded} neu (neue Ansicht oder andere Filter); sonst nur die letzte Erweiterung, dazugemischt. Ist eine
     * neue Ansicht leer, unterscheidet eine Probe ohne Filter „nichts da“ von „durch Filter ausgeblendet“.
     */
    private void load(Entry start, List<String> expanded, boolean replay) {
        GraphNeighbourhood.Options options = options();
        Filters filters = filters();
        List<String> steps = List.copyOf(expanded);
        List<String> run = replay || steps.isEmpty() ? steps : List.of(steps.getLast());
        int mine = ++generation;
        loading = true;
        if (replay && start != entry) {
            requested.clear();
            requested.addAll(steps);
        }
        status("Lädt Teilgraph …");
        runner.run(() -> {
            Actions current = actionsOrThrow();
            GraphNeighbourhood.Subgraph base = replay ? start.run(current, options) : null;
            List<GraphNeighbourhood.Subgraph> more = new ArrayList<>();
            for (String nodeId : run) {
                more.add(current.node(nodeId, options));
            }
            String hint = null;
            if (replay && base.nodes().isEmpty() && more.stream().allMatch(sub -> sub.nodes().isEmpty())) {
                boolean exists = !start.run(current, PROBE).nodes().isEmpty();
                hint = start.emptyHint(exists);
            }
            return new Loaded(base, more, hint);
        }, loaded -> {
            if (mine != generation) {
                return;
            }
            loading = false;
            if (dropHistory) {
                // Nach „Alle Aussagen löschen“: frühere Ansichten zeigen gelöschte Analysen, „Zurück“ darf sie nicht
                // wiederherstellen.
                dropHistory = false;
                history.clear();
            } else if (!nodes.isEmpty() || entry != null) {
                history.push(snapshot());
            }
            entry = start;
            expansions.clear();
            this.appliedFilters = filters;
            if (loaded.base() != null) {
                apply(loaded.base(), false);
            }
            for (int i = 0; i < loaded.more().size(); i++) {
                String step = run.get(i);
                // Blenden die Filter den erweiterten Knoten aus, entfällt auch seine Erweiterung.
                if (!nodes.containsKey(step)) {
                    continue;
                }
                expansions.add(step);
                apply(loaded.more().get(i), true);
            }
            if (!replay) {
                expansions.addAll(0, steps.subList(0, steps.size() - run.size()));
            }
            requested.clear();
            requested.addAll(expansions);
            emptyHint = loaded.emptyHint();
            refresh();
            canvas.fit();
        }, error -> {
            if (mine == generation) {
                loading = false;
                // Anzeige bleibt beim letzten Stand: Filter und Erweiterungen wieder passend dazu.
                requested.clear();
                requested.addAll(expansions);
                restore(appliedFilters);
                status("Graph nicht geladen: " + Problems.describe(error).title());
            }
        });
    }

    private Actions actionsOrThrow() {
        Actions current = actions.get();
        if (current == null) {
            throw new IllegalStateException("Keine laufende App");
        }
        return current;
    }

    GraphNeighbourhood.Options options() {
        Set<PageGraph.NodeKind> kinds = EnumSet.noneOf(PageGraph.NodeKind.class);
        if (documents.isSelected()) {
            kinds.add(PageGraph.NodeKind.DOCUMENT);
        }
        if (statements.isSelected()) {
            kinds.add(PageGraph.NodeKind.STATEMENT);
        }
        if (mentions.isSelected()) {
            kinds.add(PageGraph.NodeKind.MENTION);
        }
        Set<GraphNeighbourhood.EdgeType> types = EnumSet.noneOf(GraphNeighbourhood.EdgeType.class);
        for (GraphNeighbourhood.EdgeType type : GraphNeighbourhood.EdgeType.values()) {
            boolean wanted = type == GraphNeighbourhood.EdgeType.CONTAINS
                    || type.role() && roles.isSelected()
                    || type == GraphNeighbourhood.EdgeType.NOMINAL && nominal.isSelected()
                    || (type == GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE ? candidates.isSelected()
                            : type.crossPage() && crossPage.isSelected());
            if (wanted) {
                types.add(type);
            }
        }
        return new GraphNeighbourhood.Options(GraphNeighbourhood.DEFAULT_MAX_NODES,
                GraphNeighbourhood.DEFAULT_MAX_EDGES, kinds, types, stale.isSelected(),
                thisSource.isSelected() && page != null ? page.sourceId() : null, null)
                .within(selectionOnly.isSelected() ? selectionPages : null);
    }

    /**
     * Die Auswahl links im Baum, zu Seiten aufgelöst (ADR 0029, aus {@code Workspace.resolveSelection}); mit
     * „Nur Auswahl im Baum“ grenzt sie Fokus, Nachbarn und Begriffssuche ein. {@code null} ohne Auswahl. Eine andere
     * Menge lädt die Ansicht neu, dieselbe nicht.
     */
    public void within(PageSet pages) {
        boolean same = pages == null ? selectionPages == null
                : selectionPages != null && pages.fingerprint().equals(selectionPages.fingerprint());
        selectionPages = pages;
        selectionOnly.setEnabled(pages != null);
        if (!same && selectionOnly.isSelected()) {
            reloadFocus();
        }
    }

    private Filters appliedFilters;

    /** Die Filter, wie sie die Abfrage tatsächlich anwendet („Nur diese Quelle“ nur mit geöffneter Seite). */
    private Filters filters() {
        return new Filters(documents.isSelected(), statements.isSelected(), mentions.isSelected(),
                roles.isSelected(), nominal.isSelected(), crossPage.isSelected(), candidates.isSelected(),
                stale.isSelected(), thisSource.isSelected() && page != null,
                selectionOnly.isSelected() && selectionPages != null);
    }

    /** Setzt die Filter ohne neues Laden ({@code setSelected} löst keine ActionListener aus). */
    private void restore(Filters filters) {
        if (filters == null) {
            return;
        }
        documents.setSelected(filters.documents());
        statements.setSelected(filters.statements());
        mentions.setSelected(filters.mentions());
        roles.setSelected(filters.roles());
        nominal.setSelected(filters.nominal());
        crossPage.setSelected(filters.crossPage());
        candidates.setSelected(filters.candidates());
        stale.setSelected(filters.stale());
        thisSource.setSelected(filters.thisSource());
        selectionOnly.setSelected(filters.selectionOnly());
    }

    private void apply(GraphNeighbourhood.Subgraph subgraph, boolean merge) {
        if (!merge) {
            clear();
        }
        String anchor = merge ? subgraph.focus() : null;
        Set<String> before = new java.util.HashSet<>(positions.keySet());
        for (GraphNeighbourhood.Node node : subgraph.nodes()) {
            nodes.putIfAbsent(node.id(), node);
        }
        for (GraphNeighbourhood.Edge edge : subgraph.edges()) {
            edges.putIfAbsent(key(edge), edge);
        }
        if (!merge) {
            focus = subgraph.focus();
        }
        truncated = merge ? truncated || subgraph.truncated() : subgraph.truncated();
        // Teilgraphen können dieselben weggelassenen Knoten zählen: nach Erweiterungen nur eine Untergrenze.
        // Teilgraphen zählen unabhängig voneinander: nach einer Erweiterung keine Zahl mehr behaupten.
        hidden = merge ? hidden : subgraph.hidden();
        merged = merge;
        layout(anchor == null ? focus : anchor, subgraph, merge ? before : Set.of());
    }

    /**
     * Ordnet neue Knoten an; vorhandene behalten ihren Platz. Nachbarn des Ankers liegen auf Ringen um ihn, beim
     * Erweitern auf einem Bogen von der Mitte des Bildes weg. Jeder Platz wird nur genommen, wenn er keinen Knoten
     * überdeckt; der Ringabstand richtet sich nach der breitesten Beschriftung.
     */
    private void layout(String anchor, GraphNeighbourhood.Subgraph subgraph, Set<String> before) {
        if (anchor != null && !positions.containsKey(anchor)) {
            if (positions.isEmpty()) {
                positions.put(anchor, new Point2D.Double(0, 0));
            } else {
                // Nie auf einen vorhandenen Knoten legen: freien Platz am Rand suchen.
                place(List.of(anchor), new Point2D.Double(0, 0), -Math.PI / 2, 2 * Math.PI, 2,
                        widest() + GAP_X, NODE_HEIGHT + GAP_Y);
            }
        }
        Point2D center = anchor != null ? positions.computeIfAbsent(anchor, id -> new Point2D.Double(0, 0))
                : new Point2D.Double(0, 0);
        List<String> near = new ArrayList<>();
        List<String> far = new ArrayList<>();
        for (GraphNeighbourhood.Node node : subgraph.nodes()) {
            if (positions.containsKey(node.id())) {
                continue;
            }
            boolean adjacent = anchor != null && subgraph.edges().stream().anyMatch(edge ->
                    edge.from().equals(anchor) && edge.to().equals(node.id())
                            || edge.to().equals(anchor) && edge.from().equals(node.id()));
            (adjacent || anchor == null ? near : far).add(node.id());
        }
        double direction = -Math.PI / 2;
        double span = 2 * Math.PI;
        if (!before.isEmpty()) {
            double cx = 0;
            double cy = 0;
            int count = 0;
            for (String id : before) {
                if (!id.equals(anchor)) {
                    cx += positions.get(id).getX();
                    cy += positions.get(id).getY();
                    count++;
                }
            }
            if (count > 0) {
                double ox = center.getX() - cx / count;
                double oy = center.getY() - cy / count;
                if (Math.hypot(ox, oy) > 1) {
                    direction = Math.atan2(oy, ox);
                    span = Math.PI;
                }
            }
        }
        double unitX = widest() + GAP_X;
        double unitY = NODE_HEIGHT + GAP_Y;
        double reached = place(near, center, direction, span, 2, unitX, unitY);
        place(far, center, direction, span, reached + 1.5, unitX, unitY);
    }

    /** @return der letzte benutzte Ring (in Knotengrößen) */
    private double place(List<String> ids, Point2D center, double direction, double span, double from,
                         double unitX, double unitY) {
        int placed = 0;
        double ring = from;
        boolean full = span >= 2 * Math.PI - 1e-9;
        while (placed < ids.size() && ring < 400) {
            // Schritt 1,5 Knotengrößen je Platz und Ring: Nachbarn überdecken sich in keiner Richtung.
            int slots = Math.max(1, (int) Math.floor(span * ring / 1.5));
            for (int j = 0; j < slots && placed < ids.size(); j++) {
                double angle = full ? direction + 2 * Math.PI * j / slots
                        : direction - span / 2 + span * (j + 0.5) / slots;
                Point2D point = new Point2D.Double(center.getX() + unitX * ring * Math.cos(angle),
                        center.getY() + unitY * ring * Math.sin(angle));
                if (free(point, unitX, unitY)) {
                    positions.put(ids.get(placed++), point);
                }
            }
            if (placed < ids.size()) {
                ring += 1.5;
            }
        }
        return ring;
    }

    private boolean free(Point2D point, double unitX, double unitY) {
        for (Point2D other : positions.values()) {
            if (Math.abs(other.getX() - point.getX()) < unitX && Math.abs(other.getY() - point.getY()) < unitY) {
                return false;
            }
        }
        return true;
    }

    private double widest() {
        double widest = 54;
        for (GraphNeighbourhood.Node node : nodes.values()) {
            widest = Math.max(widest, Canvas.width(node));
        }
        return widest;
    }

    private void back() {
        if (history.isEmpty()) {
            return;
        }
        generation++;
        View previous = history.pop();
        nodes.clear();
        nodes.putAll(previous.nodes());
        edges.clear();
        edges.putAll(previous.edges());
        positions.clear();
        positions.putAll(previous.positions());
        focus = previous.focus();
        truncated = previous.truncated();
        hidden = previous.hidden();
        merged = previous.merged();
        entry = previous.entry();
        expansions.clear();
        expansions.addAll(previous.expansions());
        requested.clear();
        requested.addAll(expansions);
        loading = false;
        appliedFilters = previous.filters();
        restore(previous.filters());
        selected = null;
        selectedEdge = null;
        detail(null, null);
        refresh();
        canvas.fit();
    }

    private View snapshot() {
        return new View(new LinkedHashMap<>(nodes), new LinkedHashMap<>(edges), copy(positions), focus, truncated,
                hidden, merged, entry, List.copyOf(expansions), appliedFilters == null ? filters() : appliedFilters);
    }

    private static Map<String, Point2D> copy(Map<String, Point2D> positions) {
        Map<String, Point2D> copy = new LinkedHashMap<>();
        positions.forEach((id, point) -> copy.put(id, new Point2D.Double(point.getX(), point.getY())));
        return copy;
    }

    private void clear() {
        nodes.clear();
        edges.clear();
        positions.clear();
        focus = null;
        selected = null;
        selectedEdge = null;
        truncated = false;
        hidden = 0;
        merged = false;
        emptyHint = null;
        detail(null, null);
    }

    // ---- Auswahl

    private void select(String nodeId, GraphNeighbourhood.Edge edge) {
        selected = nodeId;
        selectedEdge = edge;
        detail(nodeId, edge);
        syncTable();
        refreshButtons();
        canvas.repaint();
    }

    /** Baut Titel, Text und Links des Detailfensters zur Auswahl; gezeigt wird es erst mit {@link #showDetails}. */
    private void detail(String nodeId, GraphNeighbourhood.Edge edge) {
        String text = edge != null ? describe(edge) : nodeId == null ? "" : describe(nodes.get(nodeId));
        int cut = text.indexOf('\n');
        detailTitle = cut < 0 ? text : text.substring(0, cut);
        detailText = cut < 0 ? "" : text.substring(cut + 1).strip();
        popup.text(detailTitle, detailText).clearLinks();
        if (edge == null && jumpable(nodeId == null ? null : nodes.get(nodeId))) {
            popup.link("Zum Beleg im Text", this::jumpToSelected);
        }
        if (popup.isVisible()) {
            popup.setVisible(false);
        }
    }

    /** Öffnet die Details zur Auswahl als Fenster neben der Mausposition {@code x, y} in {@code owner}. */
    private void showDetails(java.awt.Component owner, int x, int y) {
        if (detailTitle.isBlank() || !owner.isShowing()) {
            return;
        }
        popup.showAt(owner, x, y);
    }

    /** Hält die markierte Tabellenzeile bei derselben Auswahl wie die Grafik (Enter erweitert, was markiert ist). */
    private void syncTable() {
        int model = tableModel.indexOf(selected, selectedEdge);
        int view = model < 0 ? -1 : table.convertRowIndexToView(model);
        syncingTable = true;
        try {
            if (view < 0) {
                table.clearSelection();
            } else if (table.getSelectedRow() != view) {
                table.setRowSelectionInterval(view, view);
            }
        } finally {
            syncingTable = false;
        }
    }

    private void jumpToSelected() {
        GraphNeighbourhood.Node node = selected == null ? null : nodes.get(selected);
        if (jumpable(node)) {
            onJump.accept(List.of(new TermsTab.Span(node.start(), node.end())));
        }
    }

    private boolean jumpable(GraphNeighbourhood.Node node) {
        return node != null && page != null && node.page().key().equals(page) && node.start() >= 0
                && node.page().state() == PageStatements.State.CURRENT;
    }

    String describe(GraphNeighbourhood.Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        text.append(kindLabel(node.kind())).append(": ").append(node.label() == null ? "–" : node.label());
        if (node.lemma() != null && !node.lemma().equals(node.label())) {
            text.append(" (").append(node.lemma()).append(')');
        }
        if (node.core()) {
            text.append(" · Kernaussage");
        }
        text.append("\nSeite: ").append(pageLabel(node)).append('\n');
        if (node.page().state() == PageStatements.State.STALE) {
            text.append("Veraltet (").append(node.page().reason()).append("): Die Analyse passt nicht mehr zum "
                    + "aktuellen Text; nichts wird automatisch neu berechnet.\n");
        }
        for (GraphNeighbourhood.Edge edge : edges.values()) {
            if (edge.from().equals(node.id()) || edge.to().equals(node.id())) {
                text.append("• ").append(edgeLine(edge)).append('\n');
            }
        }
        if (node.page().key().equals(page) && node.start() >= 0) {
            text.append("Beleg: Zeichen ").append(node.start()).append('–').append(node.end())
                    .append(" im Reiter „Text“.\n");
        } else if (node.start() >= 0) {
            text.append("Beleg auf einer anderen Seite (Zeichen ").append(node.start()).append('–')
                    .append(node.end()).append(").\n");
        }
        return text.toString().strip();
    }

    String describe(GraphNeighbourhood.Edge edge) {
        StringBuilder text = new StringBuilder(edgeLine(edge)).append('\n');
        if (edge.type().crossPage()) {
            text.append(edge.type() == GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE
                    ? "Gleiche Grundform auf einer anderen Seite – Kandidat, keine bestätigte Identität.\n"
                    : "Bestätigte Begriffsbeziehung – trotzdem zwei eigenständige Erwähnungen.\n");
        } else {
            text.append("Herkunft: ").append(origin(edge.origin()));
            if (edge.relation() != null) {
                text.append(" · UD ").append(edge.relation());
            }
            if (edge.marker() != null) {
                text.append(" · „").append(edge.marker()).append('“');
            }
            text.append('\n').append(edge.certain() ? "Sicher zugeordnet" : "Unsicher zugeordnet").append('\n');
            if (edge.evidence() != null) {
                text.append("Beleg: Satz ").append(edge.evidence().substring(1)).append('\n');
            }
            if (edge.type() == GraphNeighbourhood.EdgeType.NOMINAL) {
                text.append("Nominaler Bezug, kein Besitz behauptet.\n");
            }
        }
        text.append("Parserbefund, nicht fachlich geprüft.");
        return text.toString();
    }

    private String edgeLine(GraphNeighbourhood.Edge edge) {
        return label(edge.from()) + " —" + typeLabel(edge.type()) + "→ " + label(edge.to());
    }

    private String label(String nodeId) {
        GraphNeighbourhood.Node node = nodes.get(nodeId);
        return node == null ? nodeId : "„" + (node.label() == null ? "–" : node.label()) + "“";
    }

    private static String pageLabel(GraphNeighbourhood.Node node) {
        String title = node.page().title() == null ? node.page().key().pageId() : node.page().title();
        return title + " (" + node.page().key().sourceId()
                + (node.page().spaceKey() == null ? "" : " / " + node.page().spaceKey()) + ")";
    }

    static String kindLabel(PageGraph.NodeKind kind) {
        return switch (kind) {
            case DOCUMENT -> "Dokument";
            case STATEMENT -> "Aussage";
            case MENTION -> "Erwähnung";
        };
    }

    static String typeLabel(GraphNeighbourhood.EdgeType type) {
        return switch (type) {
            case CONTAINS -> "enthält";
            case ACTOR -> "Handelnder";
            case AFFECTED_OBJECT -> "Betroffen";
            case RECIPIENT -> "Empfänger";
            case PREPOSITIONAL_OBJECT -> "Präpositionalobjekt";
            case INSTRUMENT -> "Mittel";
            case TIME -> "Zeit";
            case CIRCUMSTANCE -> "Umstand";
            case NOMINAL -> "nominaler Bezug";
            case LEXICAL_CANDIDATE -> "gleiche Grundform?";
            case CONFIRMED_TERM_RELATION -> "Begriffsbeziehung";
        };
    }

    private static String origin(String origin) {
        if (origin == null) {
            return "Parser";
        }
        return switch (origin) {
            case "STRUCTURE" -> "Struktur";
            case "PARSER" -> "Parser";
            case "COORDINATION" -> "Koordination";
            case "CASE_HEURISTIC" -> "Kasusregel";
            case "LEXICON" -> "Lexikon";
            case "HEURISTIC" -> "Heuristik";
            default -> origin;
        };
    }

    // ---- Anzeige

    private void refresh() {
        tableModel.reload();
        syncTable();
        canvas.hint(!nodes.isEmpty() ? null : emptyHint != null ? emptyHint
                : page == null ? "Keine Seite geöffnet." : pageLoaded ? "Kein Teilgraph." : null);
        if (nodes.isEmpty()) {
            status(page == null ? "Keine Seite geöffnet." : pageLoaded ? " " : "Teilgraph lädt beim Öffnen.");
        } else {
            long stalePages = nodes.values().stream().filter(node -> node.page().state() == PageStatements.State.STALE)
                    .map(node -> node.page().key()).distinct().count();
            status(nodes.size() + " Knoten, " + edges.size() + " Kanten"
                    + (truncated ? " · gekürzt" + (hidden > 0 && !merged ? ", "
                    + (entry instanceof Entry.Term ? "mindestens " : "") + hidden + " weitere Knoten" : "")
                    + " – gezielt erweitern" : "")
                    + (stalePages > 0 ? " · " + stalePages + " veraltete Seite(n)" : ""));
        }
        refreshButtons();
        canvas.repaint();
    }

    private void refreshButtons() {
        expand.setEnabled(selected != null);
        back.setEnabled(!history.isEmpty());
        pageButton.setEnabled(page != null);
        thisSource.setEnabled(page != null);
        selectionOnly.setEnabled(selectionPages != null);
        // Blau, sobald die Filter von der Grundeinstellung abweichen (Kandidaten und Veraltete sind anfangs aus).
        boolean filtered = !List.of(documents, statements, mentions, roles, nominal, crossPage).stream()
                .allMatch(JCheckBoxMenuItem::isSelected) || candidates.isSelected() || stale.isSelected()
                || (thisSource.isSelected() && page != null);
        filter.setForeground(filtered ? Comic.BLUE : Comic.INK);
        export.setEnabled(actions.get() != null && !exporting);
        deleteAll.setEnabled(actions.get() != null && !deleting);
    }

    /** Umfänge des Exports, in dieser Reihenfolge; ohne geöffnete Seite nur der gesamte Graph. */
    List<ExportChoice> exportChoices() {
        List<ExportChoice> choices = new ArrayList<>();
        PageKey key = page;
        choices.add(new ExportChoice("Diese Seite",
                key == null ? null : new KnowledgeGraphExportUseCase.Scope.Page(key.sourceId(), key.pageId())));
        choices.add(new ExportChoice("Unterzweig ab dieser Seite",
                key == null ? null : new KnowledgeGraphExportUseCase.Scope.Subtree(key.sourceId(), key.pageId())));
        choices.add(new ExportChoice(spaceKey == null ? "Space dieser Seite" : "Space „" + spaceKey + "“",
                key == null || spaceKey == null ? null
                        : new KnowledgeGraphExportUseCase.Scope.Space(key.sourceId(), spaceKey)));
        choices.add(new ExportChoice(key == null ? "Quelle dieser Seite" : "Quelle „" + key.sourceId() + "“",
                key == null ? null : new KnowledgeGraphExportUseCase.Scope.Source(key.sourceId())));
        choices.add(new ExportChoice("Gesamter Graph", new KnowledgeGraphExportUseCase.Scope.All()));
        return choices;
    }

    private JPopupMenu exportMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (ExportChoice choice : exportChoices()) {
            JMenuItem item = new JMenuItem(choice.label());
            item.setEnabled(choice.scope() != null);
            item.setToolTipText(choice.scope() != null ? "Analysen exportieren: " + choice.label()
                    : page == null ? "Keine Seite geöffnet" : "Space der Seite unbekannt");
            item.addActionListener(e -> startExport(choice));
            menu.add(item);
        }
        menu.addSeparator();
        javax.swing.JCheckBoxMenuItem withStale = new javax.swing.JCheckBoxMenuItem(
                "Veraltete Analysen einschließen", exportStale);
        withStale.setToolTipText("Sonst nur aktuelle Analysen; veraltete sind im Export als veraltet markiert");
        withStale.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        withStale.addActionListener(e -> exportStale = withStale.isSelected());
        menu.add(withStale);
        return menu;
    }

    /**
     * Löscht nach „Wirklich alle Aussagen aller Seiten löschen?“ alle gespeicherten Aussagen-Analysen aller Quellen
     * (auch veraltete); danach ist jede Seite wieder unanalysiert und der Graph leer. Läuft im Hintergrund.
     */
    void deleteAllStatements() {
        Actions current = actions.get();
        if (current == null || deleting || !confirmDelete.getAsBoolean()) {
            return;
        }
        deleting = true;
        deleteAll.setEnabled(false);
        exportNote("Aussagen werden gelöscht …");
        runner.run(current::deleteAllStatements, pages -> {
            deleting = false;
            deleteAll.setEnabled(actions.get() != null);
            exportNote(pages == 0 ? "Keine Aussagen vorhanden"
                    : "Aussagen von " + (pages == 1 ? "1 Seite" : pages + " Seiten") + " gelöscht");
            history.clear();
            dropHistory = true;
            reloadFocus();
            if (!loading) {
                // Keine Ansicht offen, nichts neu zu laden: nur „Zurück“ sperren.
                dropHistory = false;
                refresh();
            }
            onDeleted.run();
        }, failure -> {
            deleting = false;
            deleteAll.setEnabled(actions.get() != null);
            String reason = failure.getMessage();
            exportNote("Aussagen nicht gelöscht: "
                    + (reason == null || reason.isBlank() ? failure.getClass().getSimpleName() : reason));
        });
    }

    /** {@code listener} läuft, nachdem alle Aussagen gelöscht sind (Seitenansicht lädt den Aussagen-Reiter neu). */
    void onDeleted(Runnable listener) {
        onDeleted = Objects.requireNonNull(listener, "listener");
    }

    /**
     * Startet den Export als Auftrag; liest nur vorhandene Analysen. Veraltete kommen nur mit, wenn im Menü
     * „Veraltete Analysen einschließen“ gewählt ist.
     */
    void startExport(ExportChoice choice) {
        Actions current = actions.get();
        if (current == null || choice.scope() == null || exporting) {
            return;
        }
        boolean includeStale = exportStale;
        int startedFor = pageChanges;
        exporting = true;
        export.setEnabled(false);
        exportNote("Export wird gestartet …");
        runner.run(() -> current.export(choice.scope(), includeStale), started -> {
            exporting = false;
            export.setEnabled(actions.get() != null);
            if (startedFor != pageChanges) {
                return;
            }
            exportNote(EXPORT_STARTED);
        }, failure -> {
            exporting = false;
            export.setEnabled(actions.get() != null);
            if (startedFor != pageChanges) {
                return;
            }
            String reason = failure.getMessage();
            exportNote("Export nicht gestartet: "
                    + (reason == null || reason.isBlank() ? failure.getClass().getSimpleName() : reason));
        });
    }

    /** Ein Eintrag im Export-Menü; {@code scope} ist {@code null}, wenn er gerade nicht wählbar ist. */
    record ExportChoice(String label, KnowledgeGraphExportUseCase.Scope scope) {
    }

    private void toggleView() {
        tableShown = !tableShown;
        ((CardLayout) cards.getLayout()).show(cards, tableShown ? "table" : "graph");
        view.setIcon(tableShown ? Glyph.GRAPH : Glyph.TABLE);
        view.setToolTipText(tableShown ? GRAPH_TIP : TABLE_TIP);
        view.getAccessibleContext().setAccessibleName(view.getToolTipText());
    }

    private static String key(GraphNeighbourhood.Edge edge) {
        return edge.from() + "|" + edge.to() + "|" + edge.type();
    }

    /** Ein Filter im Filtermenü; der Klick lässt das Menü offen, damit man mehrere umschalten kann. */
    private static JCheckBoxMenuItem check(String text) {
        JCheckBoxMenuItem box = new JCheckBoxMenuItem(text, true);
        box.putClientProperty("CheckBoxMenuItem.doNotCloseOnMouseClick", Boolean.TRUE);
        return box;
    }


    /** Zeichenfläche: Zoom (Rad), Verschieben (Ziehen), Auswahl (Klick), Erweitern (Doppelklick). */
    private final class Canvas extends JComponent {

        private double scale = 1;
        private double dx;
        private double dy;
        private Point2D dragFrom;
        private String dragging;
        /** Ob {@link #fit()} ohne Größe aufgerufen wurde und beim ersten Auslegen nachzuholen ist. */
        private boolean fitPending;

        /** Warum nichts zu sehen ist, mitten in der Fläche; leer, sobald es Knoten gibt. */
        private final WrappedText hint = Comic.wrapped(" ", Comic.MUTED, 13f);

        Canvas() {
            setPreferredSize(new Dimension(600, 380));
            setFocusable(true);
            setToolTipText("");
            setLayout(new GridBagLayout());
            GridBagConstraints at = new GridBagConstraints();
            at.insets = new java.awt.Insets(24, 24, 24, 24);
            add(hint, at);
            hint.setVisible(false);

            MouseAdapter mouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow();
                    dragFrom = e.getPoint();
                    dragging = nodeAt(world(e.getX(), e.getY()));
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    if (dragFrom == null) {
                        return;
                    }
                    if (dragging != null) {
                        Point2D now = world(e.getX(), e.getY());
                        positions.put(dragging, now);
                    } else {
                        dx += e.getX() - dragFrom.getX();
                        dy += e.getY() - dragFrom.getY();
                    }
                    dragFrom = e.getPoint();
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    dragFrom = null;
                    dragging = null;
                }

                @Override
                public void mouseClicked(MouseEvent e) {
                    Point2D at = world(e.getX(), e.getY());
                    String node = nodeAt(at);
                    if (node != null) {
                        select(node, null);
                        if (e.getClickCount() == 2) {
                            expandSelected();
                        } else {
                            showDetails(Canvas.this, e.getX(), e.getY());
                        }
                        return;
                    }
                    GraphNeighbourhood.Edge edge = edgeAt(at).orElse(null);
                    select(null, edge);
                    if (edge != null && e.getClickCount() == 1) {
                        showDetails(Canvas.this, e.getX(), e.getY());
                    }
                }

                @Override
                public void mouseWheelMoved(MouseWheelEvent e) {
                    zoom(e.getWheelRotation() < 0 ? 1.15 : 1 / 1.15, e.getX(), e.getY());
                }
            };
            addMouseListener(mouse);
            addMouseMotionListener(mouse);
            addMouseWheelListener(mouse);
            bind(KeyEvent.VK_PLUS, () -> zoom(1.15, getWidth() / 2.0, getHeight() / 2.0));
            bind(KeyEvent.VK_ADD, () -> zoom(1.15, getWidth() / 2.0, getHeight() / 2.0));
            bind(KeyEvent.VK_MINUS, () -> zoom(1 / 1.15, getWidth() / 2.0, getHeight() / 2.0));
            bind(KeyEvent.VK_SUBTRACT, () -> zoom(1 / 1.15, getWidth() / 2.0, getHeight() / 2.0));
            bind(KeyEvent.VK_0, this::fit);
            // Leertaste: Details zum gewählten Knoten, an seiner Stelle in der Fläche.
            bind(KeyEvent.VK_SPACE, () -> {
                Point2D p = selected == null ? null : positions.get(selected);
                if (p != null) {
                    showDetails(this, (int) (p.getX() * scale + dx), (int) (p.getY() * scale + dy));
                }
            });
        }

        private void bind(int key, Runnable action) {
            String name = "graph-" + key;
            getInputMap(WHEN_FOCUSED).put(javax.swing.KeyStroke.getKeyStroke(key, 0), name);
            getActionMap().put(name, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    action.run();
                }
            });
        }

        @Override
        public void doLayout() {
            super.doLayout();
            // Geladen, bevor die Fläche eine Größe hatte: beim ersten Auslegen einpassen.
            if (fitPending && getWidth() > 0 && getHeight() > 0) {
                fit();
            }
        }

        /** Der Hinweis in der leeren Fläche; {@code null} blendet ihn aus. */
        void hint(String text) {
            hint.setVisible(text != null);
            hint.setText(text == null ? " " : text);
            hint.setPreferredSize(text == null ? null
                    : new Dimension(InfoPopup.MAX_WIDTH, hint.heightFor(InfoPopup.MAX_WIDTH)));
            revalidate();
            repaint();
        }

        void zoom(double factor, double x, double y) {
            double next = Math.max(0.2, Math.min(4, scale * factor));
            double applied = next / scale;
            dx = x - (x - dx) * applied;
            dy = y - (y - dy) * applied;
            scale = next;
            repaint();
        }

        void fit() {
            fitPending = !positions.isEmpty() && (getWidth() <= 0 || getHeight() <= 0);
            if (positions.isEmpty() || getWidth() <= 0 || getHeight() <= 0) {
                scale = 1;
                dx = getWidth() / 2.0;
                dy = getHeight() / 2.0;
                repaint();
                return;
            }
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (String id : nodes.keySet()) {
                Point2D p = positions.get(id);
                if (p == null) {
                    continue;
                }
                minX = Math.min(minX, p.getX() - 70);
                maxX = Math.max(maxX, p.getX() + 70);
                minY = Math.min(minY, p.getY() - 30);
                maxY = Math.max(maxY, p.getY() + 30);
            }
            if (minX > maxX) {
                return;
            }
            scale = Math.max(0.2, Math.min(1.5, Math.min(getWidth() / (maxX - minX), getHeight() / (maxY - minY))));
            dx = getWidth() / 2.0 - scale * (minX + maxX) / 2;
            dy = getHeight() / 2.0 - scale * (minY + maxY) / 2;
            repaint();
        }

        private Point2D world(double x, double y) {
            return new Point2D.Double((x - dx) / scale, (y - dy) / scale);
        }

        private String nodeAt(Point2D at) {
            List<String> ids = new ArrayList<>(nodes.keySet());
            for (int i = ids.size() - 1; i >= 0; i--) {
                Point2D p = positions.get(ids.get(i));
                if (p != null && shape(nodes.get(ids.get(i)), p).contains(at)) {
                    return ids.get(i);
                }
            }
            return null;
        }

        private Optional<GraphNeighbourhood.Edge> edgeAt(Point2D at) {
            for (GraphNeighbourhood.Edge edge : edges.values()) {
                Point2D a = positions.get(edge.from());
                Point2D b = positions.get(edge.to());
                if (a != null && b != null && Line2D.ptSegDist(a.getX(), a.getY(), b.getX(), b.getY(), at.getX(),
                        at.getY()) < 6 / scale) {
                    return Optional.of(edge);
                }
            }
            return Optional.empty();
        }

        @Override
        public String getToolTipText(MouseEvent e) {
            String node = nodeAt(world(e.getX(), e.getY()));
            if (node == null) {
                return null;
            }
            GraphNeighbourhood.Node n = nodes.get(node);
            return kindLabel(n.kind()) + " · " + pageLabel(n)
                    + (n.page().state() == PageStatements.State.STALE ? " · veraltet" : "");
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(Comic.SURFACE);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                if (nodes.isEmpty()) {
                    return;
                }
                AffineTransform base = g.getTransform();
                g.translate(dx, dy);
                g.scale(scale, scale);
                Font font = getFont() == null ? new Font(Font.SANS_SERIF, Font.PLAIN, 12) : getFont();
                g.setFont(font.deriveFont(11f));
                for (GraphNeighbourhood.Edge edge : edges.values()) {
                    paintEdge(g, edge);
                }
                for (GraphNeighbourhood.Node node : nodes.values()) {
                    Point2D p = positions.get(node.id());
                    if (p != null) {
                        paintNode(g, node, p);
                    }
                }
                g.setTransform(base);
            } finally {
                g.dispose();
            }
        }

        private void paintEdge(Graphics2D g, GraphNeighbourhood.Edge edge) {
            Point2D a = positions.get(edge.from());
            Point2D b = positions.get(edge.to());
            if (a == null || b == null) {
                return;
            }
            boolean chosen = edge.equals(selectedEdge);
            Stroke stroke = edge.type().crossPage()
                    ? new BasicStroke(chosen ? 2.2f : 1.4f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                    new float[] {7, 5}, 0)
                    : !edge.certain() ? new BasicStroke(chosen ? 2.2f : 1.2f, BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_MITER, 10, new float[] {2, 3}, 0)
                    : new BasicStroke(chosen ? 2.4f : 1.3f);
            g.setStroke(stroke);
            g.setColor(chosen ? Comic.ORANGE : edge.type() == GraphNeighbourhood.EdgeType.CONTAINS ? Comic.LINE
                    : Comic.INK);
            Point2D end = clip(b, a, nodes.get(edge.to()));
            g.draw(new Line2D.Double(a, end));
            arrow(g, a, end);
            if (edge.type() != GraphNeighbourhood.EdgeType.CONTAINS) {
                String label = typeLabel(edge.type());
                FontMetrics metrics = g.getFontMetrics();
                double mx = (a.getX() + end.getX()) / 2;
                double my = (a.getY() + end.getY()) / 2;
                g.setColor(Comic.SURFACE);
                g.fill(new Rectangle2D.Double(mx - metrics.stringWidth(label) / 2.0 - 2, my - 10,
                        metrics.stringWidth(label) + 4, 13));
                g.setColor(Comic.MUTED);
                g.drawString(label, (float) (mx - metrics.stringWidth(label) / 2.0), (float) my);
            }
        }

        private void arrow(Graphics2D g, Point2D from, Point2D to) {
            double angle = Math.atan2(to.getY() - from.getY(), to.getX() - from.getX());
            Path2D head = new Path2D.Double();
            head.moveTo(to.getX(), to.getY());
            head.lineTo(to.getX() - 9 * Math.cos(angle - 0.4), to.getY() - 9 * Math.sin(angle - 0.4));
            head.lineTo(to.getX() - 9 * Math.cos(angle + 0.4), to.getY() - 9 * Math.sin(angle + 0.4));
            head.closePath();
            g.setStroke(new BasicStroke(1f));
            g.fill(head);
        }

        /** Endpunkt am Rand des Zielknotens statt in seiner Mitte. */
        private Point2D clip(Point2D target, Point2D from, GraphNeighbourhood.Node node) {
            Rectangle2D bounds = shape(node, target).getBounds2D();
            double angle = Math.atan2(target.getY() - from.getY(), target.getX() - from.getX());
            double rx = bounds.getWidth() / 2;
            double ry = bounds.getHeight() / 2;
            double t = 1 / Math.sqrt(Math.pow(Math.cos(angle) / rx, 2) + Math.pow(Math.sin(angle) / ry, 2));
            return new Point2D.Double(target.getX() - t * Math.cos(angle), target.getY() - t * Math.sin(angle));
        }

        static double width(GraphNeighbourhood.Node node) {
            return Math.max(54, Math.min(170, 7.0 * text(node).length() + 18));
        }

        private Shape shape(GraphNeighbourhood.Node node, Point2D p) {
            double width = width(node);
            double height = node.kind() == PageGraph.NodeKind.DOCUMENT ? 34 : 26;
            double x = p.getX() - width / 2;
            double y = p.getY() - height / 2;
            return switch (node.kind()) {
                case DOCUMENT -> new Rectangle2D.Double(x, y, width, height);
                case STATEMENT -> new RoundRectangle2D.Double(x, y, width, height, 14, 14);
                case MENTION -> new Ellipse2D.Double(x, y, width, height);
            };
        }

        private static String text(GraphNeighbourhood.Node node) {
            String label = node.label() == null ? node.page().key().pageId() : node.label();
            if (label.length() > 22) {
                label = label.substring(0, 21) + "…";
            }
            return node.page().state() == PageStatements.State.STALE ? label + " (veraltet)" : label;
        }

        private void paintNode(Graphics2D g, GraphNeighbourhood.Node node, Point2D p) {
            Shape shape = shape(node, p);
            boolean staleNode = node.page().state() == PageStatements.State.STALE;
            Color tone = staleNode ? Comic.MUTED : switch (node.kind()) {
                case DOCUMENT -> Comic.PETROL;
                case STATEMENT -> Comic.ORANGE;
                case MENTION -> Comic.BLUE;
            };
            g.setColor(Comic.SURFACE);
            g.fill(shape);
            g.setColor(tone);
            boolean chosen = node.id().equals(selected);
            g.setStroke(new BasicStroke(chosen ? 3f : node.id().equals(focus) || node.core() ? 2f : 1.2f));
            g.draw(shape);
            String text = text(node);
            FontMetrics metrics = g.getFontMetrics();
            g.setColor(staleNode ? Comic.MUTED : Comic.INK);
            g.drawString(text, (float) (p.getX() - metrics.stringWidth(text) / 2.0),
                    (float) (p.getY() + metrics.getAscent() / 2.0 - 2));
        }
    }

    /**
     * Tabellenansicht ohne Farbcodierung: zuerst je Knoten eine Zeile (wählbar, erweiterbar, mit Sprung zum Beleg),
     * dann je Kante eine Zeile.
     */
    private final class EdgeTableModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Von", "Beziehung", "Nach", "Seite", "Sicher", "Herkunft",
                "Stand"};
        private final List<Row> rows = new ArrayList<>();

        void reload() {
            rows.clear();
            for (GraphNeighbourhood.Node node : nodes.values()) {
                rows.add(new Row(node.id(), null));
            }
            for (GraphNeighbourhood.Edge edge : edges.values()) {
                rows.add(new Row(edge.from(), edge));
            }
            fireTableDataChanged();
        }

        /** Zeile der Auswahl: die Kante, sonst der Knoten; -1 ohne. */
        int indexOf(String nodeId, GraphNeighbourhood.Edge edge) {
            for (int i = 0; i < rows.size(); i++) {
                Row row = rows.get(i);
                if (edge != null ? edge.equals(row.edge()) : row.edge() == null && row.node().equals(nodeId)) {
                    return i;
                }
            }
            return -1;
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
        public Object getValueAt(int rowIndex, int column) {
            Row row = rows.get(rowIndex);
            GraphNeighbourhood.Edge edge = row.edge();
            GraphNeighbourhood.Node from = nodes.get(row.node());
            GraphNeighbourhood.Node to = edge == null ? null : nodes.get(edge.to());
            return switch (column) {
                case 0 -> from == null ? row.node() : kindLabel(from.kind()) + " " + label(from.id());
                case 1 -> edge == null ? "–" : typeLabel(edge.type())
                        + (edge.type() == GraphNeighbourhood.EdgeType.LEXICAL_CANDIDATE ? " (Kandidat)"
                        : edge.type() == GraphNeighbourhood.EdgeType.CONFIRMED_TERM_RELATION ? " (bestätigt)" : "");
                case 2 -> to == null ? "–" : kindLabel(to.kind()) + " " + label(to.id());
                case 3 -> from == null ? "" : pageLabel(from) + (to != null && !to.page().key().equals(from.page()
                        .key()) ? " → " + pageLabel(to) : "");
                case 4 -> edge == null ? "–" : edge.certain() ? "sicher" : "unsicher";
                case 5 -> edge == null ? "–" : edge.type().crossPage() ? "Begriff „" + edge.origin() + "“"
                        : origin(edge.origin());
                case 6 -> from == null ? "" : from.page().state() == PageStatements.State.STALE
                        || to != null && to.page().state() == PageStatements.State.STALE ? "veraltet" : "aktuell";
                default -> "";
            };
        }
    }

    private record Row(String node, GraphNeighbourhood.Edge edge) {
    }

    private record View(Map<String, GraphNeighbourhood.Node> nodes, Map<String, GraphNeighbourhood.Edge> edges,
                        Map<String, Point2D> positions, String focus, boolean truncated, int hidden, boolean merged,
                        Entry entry, List<String> expansions, Filters filters) {
    }

    private record Filters(boolean documents, boolean statements, boolean mentions, boolean roles, boolean nominal,
                           boolean crossPage, boolean candidates, boolean stale, boolean thisSource,
                           boolean selectionOnly) {
    }

    private record Loaded(GraphNeighbourhood.Subgraph base, List<GraphNeighbourhood.Subgraph> more,
                          String emptyHint) {
    }

    /** Einstieg einer Ansicht. */
    private sealed interface Entry {

        GraphNeighbourhood.Subgraph run(Actions actions, GraphNeighbourhood.Options options);

        /** Hinweis bei leerem Ergebnis; {@code exists}: ohne Filter gäbe es etwas. */
        String emptyHint(boolean exists);

        record Page(PageKey key) implements Entry {
            @Override
            public GraphNeighbourhood.Subgraph run(Actions actions, GraphNeighbourhood.Options options) {
                return actions.page(key, options);
            }

            @Override
            public String emptyHint(boolean exists) {
                return exists ? FILTERED : NOT_ANALYSED;
            }
        }

        record Term(String term) implements Entry {
            @Override
            public GraphNeighbourhood.Subgraph run(Actions actions, GraphNeighbourhood.Options options) {
                return actions.term(term, options);
            }

            @Override
            public String emptyHint(boolean exists) {
                return exists ? FILTERED_TERM : EMPTY_TERM;
            }
        }
    }

    /** Führt Arbeit im Hintergrund aus und meldet auf dem EDT zurück (Tests: synchron). */
    interface Runner {
        <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure);
    }

    /** Die Graph-Abfragen der App; in Tests ersetzbar. */
    interface Actions {

        GraphNeighbourhood.Subgraph page(PageKey key, GraphNeighbourhood.Options options);

        GraphNeighbourhood.Subgraph node(String nodeId, GraphNeighbourhood.Options options);

        GraphNeighbourhood.Subgraph term(String term, GraphNeighbourhood.Options options);

        /** Startet den JSON-LD-Export vorhandener Analysen als Auftrag; analysiert nie. */
        JobStatus export(KnowledgeGraphExportUseCase.Scope scope, boolean includeStale);

        /** Löscht alle gespeicherten Aussagen-Analysen, die der Aufrufer lesen darf; liefert die Zahl der Seiten. */
        int deleteAllStatements();

        static Actions of(Workspace workspace) {
            if (workspace == null) {
                return null;
            }
            return new Actions() {
                @Override
                public GraphNeighbourhood.Subgraph page(PageKey key, GraphNeighbourhood.Options options) {
                    return workspace.knowledgeGraphOfPage(key, options);
                }

                @Override
                public GraphNeighbourhood.Subgraph node(String nodeId, GraphNeighbourhood.Options options) {
                    return workspace.knowledgeGraphOfNode(nodeId, options);
                }

                @Override
                public GraphNeighbourhood.Subgraph term(String term, GraphNeighbourhood.Options options) {
                    return workspace.knowledgeGraphOfTerm(term, options);
                }

                @Override
                public JobStatus export(KnowledgeGraphExportUseCase.Scope scope, boolean includeStale) {
                    return workspace.startKnowledgeGraphExport(scope, includeStale);
                }

                @Override
                public int deleteAllStatements() {
                    return workspace.deleteAllStatements();
                }
            };
        }
    }

    // ---- für Tests

    void confirmDeleteWith(BooleanSupplier confirm) {
        confirmDelete = Objects.requireNonNull(confirm, "confirm");
    }

    boolean deleteAllOffered() {
        return deleteAll.isVisible() && deleteAll.isEnabled() && deleteAll.getParent() != null;
    }

    void clickDeleteAll() {
        deleteAll.doClick();
    }

    String exportNoteText() {
        return exportNote;
    }

    void includeStaleInExport(boolean include) {
        exportStale = include;
    }

    boolean exportEnabled() {
        return export.isEnabled();
    }

    List<String> nodeIds() {
        return List.copyOf(nodes.keySet());
    }

    int edgeCount() {
        return edges.size();
    }

    String statusText() {
        return statusText;
    }

    /** Titel und Text des Detailfensters zur Auswahl (auch ungeöffnet). */
    String detailsText() {
        return detailTitle.isBlank() ? "" : detailTitle + "\n" + detailText;
    }

    /** Der Hinweis in der leeren Fläche oder {@code null}. */
    String hintText() {
        return emptyHint;
    }

    InfoPopup popup() {
        return popup;
    }

    int tableRows() {
        return tableModel.getRowCount();
    }

    Object tableValue(int row, int column) {
        return tableModel.getValueAt(row, column);
    }

    void selectNode(String nodeId) {
        select(nodeId, null);
    }

    void clickExpand() {
        expand.doClick();
    }

    void clickBack() {
        back.doClick();
    }

    boolean backOffered() {
        return back.isEnabled();
    }

    /** Klickt „Zum Beleg im Text“ im Detailfenster. */
    void clickJump() {
        popup.clickLink(0);
    }

    /** Enter auf „Zum Beleg im Text“, wie ein Tastaturbenutzer. */
    void pressEnterOnJump() {
        popup.pressEnterOnLink(0);
    }

    /** Leertaste in der Tabelle: öffnet die Details zur markierten Zeile. */
    void pressSpaceInTable() {
        table.getActionMap().get("details").actionPerformed(null);
    }

    boolean jumpOffered() {
        return popup.linkTexts().contains("Zum Beleg im Text");
    }

    void searchTerm(String term) {
        search.setText(term);
        showTerm(term);
    }

    void showPageNow() {
        showPage();
    }

    void setSelectionOnly(boolean only) {
        selectionOnly.setSelected(only);
    }

    void setShowStale(boolean show) {
        stale.setSelected(show);
    }

    void setShowCandidates(boolean show) {
        candidates.setSelected(show);
    }

    boolean showsCandidates() {
        return candidates.isSelected();
    }

    boolean showsStale() {
        return stale.isSelected();
    }

    boolean filterHighlighted() {
        return Comic.BLUE.equals(filter.getForeground());
    }

    void refreshNow() {
        refreshButtons();
    }

    /** Wie ein Klick auf „Veraltete zeigen“ (lädt neu). */
    void toggleShowStale() {
        stale.doClick();
    }

    void selectTableRow(int row) {
        table.setRowSelectionInterval(row, row);
    }

    int selectedTableRow() {
        return table.getSelectedRow();
    }

    boolean pageLoaded() {
        return pageLoaded;
    }

    String selectedNode() {
        return selected;
    }

    void pressEnterInTable() {
        table.getActionMap().get("expand").actionPerformed(null);
    }
}
