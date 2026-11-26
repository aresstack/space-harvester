package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvBookmark;
import com.aresstack.spaceharvester.deck.ndv.NdvDocumentStore;
import com.aresstack.spaceharvester.deck.ndv.NdvHistory;
import com.aresstack.spaceharvester.deck.ndv.NdvNameFilter;
import com.aresstack.spaceharvester.deck.ndv.NdvObject;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvState;
import com.aresstack.spaceharvester.deck.ndv.NdvTypeGroups;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAccessRules;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis;
import com.aresstack.spaceharvester.deck.ndv.analysis.NdvSourceCache;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.DefaultListModel;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.RowFilter;
import javax.swing.RowSorter;
import javax.swing.ScrollPaneConstants;
import javax.swing.SortOrder;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import javax.swing.table.TableRowSorter;

/**
 * Eine NDV-Sitzung im Command Deck, mit allen Funktionen des NDV-Reiters aus MainframeMate ({@code NdvConnectionTab}):
 * links die Bibliotheken, rechts oben Pfadleiste mit Zurück/Vorwärts und Lesezeichen, darunter die Objekte als Liste
 * oder nach Art gruppiert (Natural Navigator), unten der Quelltext im gemeinsamen Editor mit den Angaben des Servers.
 * Oben rechts ({@link #tools()}) Namensfilter, Arten, Namens-Ausdruck, Ansicht, Details und Neu laden; unten
 * ({@link #foot()}) Zustand und Aktionen der Sitzung.
 *
 * <p>Auflisten und Öffnen schreiben nichts auf den Server. Der Quelltext erscheint unten im gemeinsamen Editor des
 * Command Deck ({@link NdvDocumentStore}); auf den Server geschrieben wird nur mit „Speichern“ (Strg+S), und nur, wenn
 * ihn seit dem Laden niemand geändert hat – wie {@code writeIfUnchanged} in MainframeMate. Alle Gespräche mit dem
 * Server laufen im Hintergrund; die Sitzung reiht sie.
 */
final class NdvBrowser extends JPanel {

    static final String LIBRARY_KEY = "deck.ndv.library";
    private static final String HINT = "hint";
    private static final String LIST = "Liste";
    private static final String GROUPED = "Nach Art";
    /** Spalten, die „Details“ zeigt (wie die Detailzeile in MainframeMate). */
    private static final int[] DETAIL_COLUMNS = {5, 6, 7};

    private final NdvSession session;
    private final NdvSessions sessions;
    private final Executor background;
    private final NdvNavigatorState state = new NdvNavigatorState();
    private final DefaultListModel<String> libraries = new DefaultListModel<>();
    private final List<String> allLibraries = new ArrayList<>();
    private final JList<String> libraryList = new JList<>(libraries);
    private final PlaceholderField libraryFilter = new PlaceholderField(10, "Bibliothek filtern");
    /**
     * Seitenleiste links (Angelo 24.11.): oben die eine Suche, darunter ein Reiter, unten in der Fußleiste die Reiter.
     * Nichts mehr neben dem Quelltext; Ressourcen, Callees/Caller, Aufrufe/Daten und Gliederung stehen hier.
     */
    static final String SIDE_LIBRARIES = "Bibliotheken";
    static final String SIDE_RESOURCES = EditorSideBar.RESOURCES;
    static final String SIDE_CALLS = EditorSideBar.CALLS;
    static final String SIDE_RELATIONS = "Aufrufe/Daten";
    static final String SIDE_OUTLINE = EditorSideBar.OUTLINE;
    /** Nur in der Diagramm-Ansicht („Visuell“): deren Werkzeuge; danach zurück zum vorigen Reiter. */
    static final String SIDE_DIAGRAM = EditorSideBar.DIAGRAM;
    /** Die Seitenleiste des Code-Viewers (gemeinsam mit FTP); erster Reiter: Bibliotheken. */
    private EditorSideBar sideBar;
    private final ObjectModel objects = new ObjectModel();
    private final JTable objectTable = Comic.table(new JTable(objects));
    private final TableRowSorter<ObjectModel> sorter = new TableRowSorter<>(objects);
    private final List<TableColumn> detailColumns = new ArrayList<>();
    private final NdvObjectTree objectTree;
    private final CardLayout viewCards = new CardLayout();
    private final JPanel views = new JPanel(viewCards);
    private final PlaceholderField objectFilter = new PlaceholderField(12, "Objekt filtern, * erlaubt");
    private final IconButton typeFilter = new IconButton(Glyph.FILTER, "Arten ein- und ausblenden");
    private final ComposerButton nameFilterButton = Comic.button(".*",
            "Namens-Ausdruck: nur passende Objekte zeigen, je Bibliothek gemerkt");
    private final SegmentedToggle viewToggle = new SegmentedToggle();
    private final IconButton detailsToggle = new IconButton(Glyph.TABLE, "Details zeigen (Modus, DBID, FNR)");
    private final IconButton linkToggle = new IconButton(Glyph.CROSSHAIR,
            "Mit Editor verknüpfen: das geöffnete Objekt in der Liste markieren");
    private final IconButton expandAll = new IconButton(Glyph.EXPAND_ALL, "Alle Gruppen aufklappen");
    private final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alle Gruppen zuklappen");
    /** Das eine Zurück wie im Space Harvester; gedrückt halten zeigt den Verlauf, Vor per Pfeil rechts/Maustaste. */
    private final com.aresstack.spaceharvester.app.ui.controls.BackButton backButton = new com.aresstack.spaceharvester.app.ui.controls.BackButton("Zurück (Pfeil links in der Liste)");
    private final PlaceholderField pathField = new PlaceholderField(20, "host:port/BIBLIOTHEK");
    private final IconButton bookmarkStar = new IconButton(Glyph.STAR, "Lesezeichen für diese Bibliothek");
    private final IconButton bookmarkMenu = new IconButton(Glyph.MORE, "Lesezeichen");
    private final NdvNameFilter nameFilter = new NdvNameFilter();
    private final Set<Integer> hiddenTypes = new LinkedHashSet<>();
    private final NdvHistory history = new NdvHistory();
    private final EditorSessions editors;
    private final NdvDocumentStore store;
    private final CardLayout sourceCards = new CardLayout();
    private final JPanel sourceStage = new JPanel(sourceCards);
    private final Map<EditorSession, DocumentEditor> editorOf = new LinkedHashMap<>();
    private final JSplitPane right;
    private final JLabel sourceTitle = Comic.caption("Quelltext – Objekt doppelt anklicken");
    private final JLabel sourceDetails = Comic.muted(" ");
    private final BusyOverlay libraryBusy;
    private final BusyOverlay objectBusy;
    /**
     * Stand der Sitzung (Angelo 23.11.): steht links in der Statuszeile der App, nicht im Fuß; der Fuß trägt nur noch
     * Aktionen. {@link NdvWorkspace} zeigt den Stand der gewählten Sitzung.
     */
    private StatusNote statusNote = StatusNote.plain(" ");
    private Runnable statusChanged = () -> { };
    // Aktionen als Icon-Knöpfe mit Tooltip (Angelo 23.11.: keine Textknöpfe in den Leisten).
    private final IconButton open = new IconButton(FilesIcons.file(), "Öffnen: Quelltext des gewählten Objekts laden "
            + "(Eingabe)");
    private final IconButton save = new IconButton(FilesIcons.save(),
            "Speichern: Quelltext auf den Server schreiben (Strg+S); nur, wenn ihn seit dem Laden niemand geändert hat");
    private final IconButton reconnect = new IconButton(Glyph.SYNC, "Neu verbinden: Verbindung neu aufbauen");
    private final JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
    private final JPanel foot = new JPanel(new BorderLayout());
    /** Zwischenspeicher, Abhängigkeiten und Sperrliste dieses Servers (wie NdvSourceCacheService in MainframeMate). */
    private NdvAnalysis analysis;
    private final NdvAccessRules rules = NdvAnalysisSettings.rules();
    private final IconButton hideBlockedToggle = new IconButton(Glyph.LOCK, "Gesperrte ausblenden (Sperrliste)");
    private final IconButton indexToggle = new IconButton(Glyph.PULSE,
            "Index-Leiste: Zwischenspeicher, Sperrliste, Suche im Quelltext");
    private final NdvIndexPanel indexPanel;
    private final NdvRelationsPanel relationsPanel;
    /** Objekt zu jedem geöffneten Dokument (Ort im Editor → Objekt). */
    private final Map<String, NdvObject> objectOf = new LinkedHashMap<>();
    /** Zeile, zu der nach dem Laden gesprungen wird (Ort im Editor → Zeile). */
    private final Map<String, Integer> pendingLine = new LinkedHashMap<>();
    private final Runnable rulesChanged = () -> SwingUtilities.invokeLater(this::rulesChanged);
    private final Runnable analysisChanged = () -> SwingUtilities.invokeLater(this::analysisChanged);
    /** Geänderte Wissensquellen oder Einstellungen: Zustand „Globale Suche“ neu zeigen. */
    private final Runnable settingsChanged = () -> SwingUtilities.invokeLater(this::updateSearchLink);
    /** Rückfrage (in Tests ersetzbar). */
    java.util.function.BiPredicate<String, String> confirm = (title, question) -> Dialogs.confirm(this, title,
            question);
    /** Frage nach der NDV-Bibliothek zu einer STEPLIB (in Tests ersetzbar); {@code null} = abgebrochen. */
    java.util.function.BiFunction<String, String, String> askLibrary = this::askLibrary;
    private boolean hideBlocked;
    private boolean indexVisible;
    private String lastPrefetch = "";
    /** Je Bibliothek die Regeln, mit denen der Graph zuletzt gebaut wurde. */
    private final Map<String, Integer> builtWith = new java.util.concurrent.ConcurrentHashMap<>();
    /** Zählt Auflistungen; eine überholte hört bei der nächsten Seite auf. */
    private final AtomicInteger listing = new AtomicInteger();
    private String library;
    private NdvObject shown;
    /** Objekt, das nach dem Laden der Bibliothek geöffnet wird (Lesezeichen, wie {@code pendingObjectName}). */
    private String pendingObject;
    /** Die Bibliothek, zu der {@link #pendingObject} gehört; eine andere Navigation verwirft es. */
    private String pendingLibrary;
    /** Das Dokument, das unten im Editor steht. */
    private EditorSession document;
    private long announcedRevision;
    private boolean everConnected;
    private boolean groupByType;
    private boolean showDetails;
    private boolean linkWithEditor;
    private String note = "";
    private Runnable libraryChanged = () -> { };
    private boolean noteIsError;

    NdvBrowser(NdvSession session, NdvSessions sessions, EditorSessions editors, Executor background,
               Runnable closed) {
        super(new BorderLayout());
        this.session = Objects.requireNonNull(session, "session");
        this.editors = Objects.requireNonNull(editors, "editors");
        this.store = new NdvDocumentStore(session);
        this.sessions = sessions;
        this.background = background;
        // Aufrufziel aus der Diagramm-Ansicht: unten im Editor zeigen (auch frisch geöffnete Quelltexte)
        editors.onReveal(revealed -> SwingUtilities.invokeLater(() -> {
            if (revealed.store() == store) {
                show(revealed);
            }
        }));
        setBackground(Comic.SURFACE);
        groupByType = state.groupByType();
        showDetails = state.showDetails();
        linkWithEditor = state.linkWithEditor();
        hiddenTypes.addAll(state.hiddenTypes());
        hideBlocked = state.hideBlacklisted();
        indexVisible = state.indexVisible();
        analysis = NdvAnalysisSettings.analysis(session);
        indexPanel = new NdvIndexPanel(this::openHit);
        relationsPanel = new NdvRelationsPanel(new NdvRelationsPanel.Navigator() {
            @Override
            public void site(String object, int line) {
                openByName(object, line);
            }

            @Override
            public void target(String object) {
                openByName(object, 0);
            }
        });

        // Links: Bibliotheken mit Filter im Feld (✕ leert).
        libraryList.setFont(Comic.regular(13f));
        libraryList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        libraryList.setSelectionBackground(Comic.YELLOW);
        libraryList.setSelectionForeground(Comic.INK);
        libraryList.putClientProperty("html.disable", Boolean.TRUE);
        libraryList.getAccessibleContext().setAccessibleName("Bibliotheken");
        libraryList.addListSelectionListener(event -> {
            if (!event.getValueIsAdjusting() && libraryList.getSelectedValue() != null
                    && !libraryList.getSelectedValue().equals(library)) {
                openLibrary(libraryList.getSelectedValue());
            }
        });
        libraryFilter.getDocument().addDocumentListener(changes(this::filterLibraries));
        libraryBusy = new BusyOverlay(Comic.scroll(libraryList));
        JPanel left = sideBar();

        // Rechts oben: Objekte als Liste (sortierbar, Mehrfachauswahl) oder nach Art gruppiert.
        objectTable.setRowSorter(sorter);
        objectTable.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        objectTable.getAccessibleContext().setAccessibleName("Objekte");
        int[] widths = {170, 150, 60, 130, 80, 90, 50, 50};
        for (int i = 0; i < widths.length; i++) {
            objectTable.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        // Zahlen rechtsbündig mit Abstand zur nächsten Spalte; sortiert wird nach der Zahl.
        javax.swing.table.DefaultTableCellRenderer number = new javax.swing.table.DefaultTableCellRenderer();
        number.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
        for (int column : new int[] {2, 6, 7}) {
            objectTable.getColumnModel().getColumn(column).setCellRenderer((table, value, selected, focus, row, col) -> {
                JLabel label = (JLabel) number.getTableCellRendererComponent(table,
                        value instanceof Integer n && n <= 0 ? "" : value, selected, false, row, col);
                label.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 24));
                return label;
            });
        }
        for (int column : DETAIL_COLUMNS) {
            detailColumns.add(objectTable.getColumnModel().getColumn(column));
        }
        restoreSort();
        sorter.addRowSorterListener(event -> {
            if (event.getType() == javax.swing.event.RowSorterEvent.Type.SORT_ORDER_CHANGED) {
                saveSort();
                if (groupByType) {
                    filterObjects();
                }
            }
        });
        MouseAdapter tableMouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    openSelected();
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
        };
        objectTable.addMouseListener(tableMouse);
        objectTable.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ndv-open");
        objectTable.getActionMap().put("ndv-open", action(this::openSelected));
        objectTable.getSelectionModel().addListSelectionListener(event -> {
            showState();
            updateFilterState();
        });
        objectTree = new NdvObjectTree(state.expandedTypes(), state::expandedTypes);
        objectTree.details(showDetails);
        objectTree.addMouseListener(tableMouse);
        objectTree.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "ndv-open");
        objectTree.getActionMap().put("ndv-open", action(this::openSelected));
        objectTree.addTreeSelectionListener(event -> {
            showState();
            updateFilterState();
        });
        installKeys(objectTable, objectTable::getRowCount, objectTable::getSelectedRow,
                row -> objectTable.setRowSelectionInterval(row, row),
                row -> objectTable.scrollRectToVisible(objectTable.getCellRect(row, 0, true)));
        installKeys(objectTree, objectTree::getRowCount, () -> {
            int[] rows = objectTree.getSelectionRows();
            return rows == null || rows.length == 0 ? -1 : rows[0];
        }, objectTree::setSelectionRow, objectTree::scrollRowToVisible);
        installLibraryKeys();
        views.setOpaque(false);
        views.add(Comic.scroll(objectTable), LIST);
        views.add(Comic.scroll(objectTree), GROUPED);
        objectBusy = new BusyOverlay(views);
        objectBusy.onCancel(() -> {
            listing.incrementAndGet();
            objectBusy.setBusy(false);
            note(objects.getRowCount() + " Objekte in " + library + " (abgebrochen, unvollständig)", false);
        });

        // Pfadleiste über den Objekten: Zurück, Vorwärts, Pfad mit „Öffnen“ im Feld, Lesezeichen.
        Comic.style(pathField);
        pathField.getAccessibleContext().setAccessibleName("Pfad");
        pathField.addActionListener(e -> navigateToPath());
        pathField.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                // Wie ListKeyboardNavigation.installFieldNavigation: Pfeil runter = erste Zeile, hoch = letzte.
                int count = rowCount();
                if (count == 0) {
                    return;
                }
                if (event.getKeyCode() == KeyEvent.VK_DOWN || event.getKeyCode() == KeyEvent.VK_UP) {
                    selectRow(event.getKeyCode() == KeyEvent.VK_DOWN ? 0 : count - 1);
                    activeView().requestFocusInWindow();
                    event.consume();
                }
            }
        });
        InputFrame pathFrame = new InputFrame(pathField);
        javax.swing.JButton go = pathFrame.addButton(InputFrame.flatButton("Öffnen", "Pfad öffnen (Eingabe)"));
        go.addActionListener(e -> navigateToPath());
        backButton.navigator(com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator.of(this::visits, this::jump));
        bookmarkStar.addActionListener(e -> toggleLibraryBookmark());
        bookmarkMenu.addActionListener(e -> bookmarkMenu().show(bookmarkMenu, 0, bookmarkMenu.getHeight()));
        JPanel pathBar = new JPanel(new BorderLayout(4, 0));
        pathBar.setOpaque(false);
        pathBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        pathBar.add(backButton, BorderLayout.WEST);
        pathBar.add(pathFrame, BorderLayout.CENTER);
        pathBar.add(Comic.row(bookmarkStar, bookmarkMenu), BorderLayout.EAST);
        JPanel objectPanel = new JPanel(new BorderLayout());
        objectPanel.setOpaque(false);
        objectPanel.add(pathBar, BorderLayout.NORTH);
        objectPanel.add(objectBusy, BorderLayout.CENTER);

        // Rechts unten: Quelltext im gemeinsamen Editor, darüber die Angaben des Servers (wie der Kopf in NaturalONE).
        sourceStage.setOpaque(false);
        JPanel empty = new JPanel();
        empty.setOpaque(false);
        sourceStage.add(empty, HINT);
        JPanel heading = new JPanel();
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        heading.setOpaque(false);
        heading.add(sourceTitle);
        heading.add(sourceDetails);
        sourceDetails.setVisible(false);
        JPanel sourcePanel = new JPanel(new BorderLayout(0, 4));
        sourcePanel.setOpaque(false);
        sourcePanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
        sourcePanel.add(heading, BorderLayout.NORTH);
        sourcePanel.add(sourceStage, BorderLayout.CENTER);

        right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, objectPanel, sourcePanel);
        right.setResizeWeight(0.4);
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(8, 6, 0, 12));
        ComicDivider.install(right);
        // Unter der Arbeitsfläche rechts die Leiste mit der Lupe, auf einer Linie mit der Fußleiste der Seitenleiste.
        var split = new com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane(
                left, DeckBars.withFind(right), 280).withDividerToggle();
        add(split, BorderLayout.CENTER);
        add(indexPanel, BorderLayout.EAST);
        indexPanel.setVisible(indexVisible);

        // Kopfzeile rechts: Namensfilter (✕ im Feld), Arten, Namens-Ausdruck, Ansicht, Details, Verknüpfen, Neu laden.
        Comic.style(objectFilter);
        objectFilter.getDocument().addDocumentListener(changes(this::filterObjects));
        typeFilter.addActionListener(e -> typeMenu().show(typeFilter, 0, typeFilter.getHeight()));
        nameFilterButton.addActionListener(e -> nameFilterMenu().show(nameFilterButton, 0,
                nameFilterButton.getHeight()));
        nameFilter.observe(() -> {
            state.saveFilter(nameFilter, session.profile().host(), session.user(), scope());
            filterObjects();
        });
        viewToggle.segment(LIST).segment(GROUPED);
        viewToggle.select(groupByType ? GROUPED : LIST);
        viewToggle.onSelect(name -> {
            groupByType = GROUPED.equals(name);
            state.groupByType(groupByType);
            filterObjects();
        });
        detailsToggle.addActionListener(e -> {
            showDetails = !showDetails;
            state.showDetails(showDetails);
            applyDetails();
        });
        linkToggle.addActionListener(e -> {
            linkWithEditor = !linkWithEditor;
            state.linkWithEditor(linkWithEditor);
            linkToggle.marked(linkWithEditor);
            if (linkWithEditor && shown != null) {
                selectShown();
            }
        });
        linkToggle.marked(linkWithEditor);
        expandAll.addActionListener(e -> objectTree.expandAll());
        collapseAll.addActionListener(e -> objectTree.collapseAll());
        hideBlockedToggle.addActionListener(e -> {
            hideBlocked = !hideBlocked;
            state.hideBlacklisted(hideBlocked);
            hideBlockedToggle.marked(hideBlocked);
            filterObjects();
            filterLibraries();
        });
        hideBlockedToggle.marked(hideBlocked);
        indexToggle.addActionListener(e -> showIndex(!indexVisible));
        indexToggle.marked(indexVisible);
        indexPanel.cacheSelection.addActionListener(e -> cacheSelectedOrAll());
        indexPanel.cancel.addActionListener(e -> {
            if (library != null) {
                analysis.cache().cancel(library);
            }
        });
        indexPanel.clearCache.addActionListener(e -> clearCache());
        indexPanel.indexRule.addActionListener(e -> createSearchRule());
        indexPanel.indexNow.addActionListener(e -> indexNow());
        indexPanel.whitelist.addActionListener(e -> rules.whitelist(selectedPaths()));
        indexPanel.blacklist.addActionListener(e -> rules.blacklist(selectedPaths()));
        indexPanel.removeFilter.addActionListener(e -> rules.remove(selectedPaths()));
        indexPanel.search.getDocument().addDocumentListener(changes(this::searchSource));
        IconButton refresh = new IconButton(Glyph.REFRESH, "Bibliotheken und Objekte neu vom Server laden");
        refresh.addActionListener(e -> refresh());
        tools.setOpaque(false);
        InputFrame filterFrame = InputFrame.search(objectFilter);
        filterFrame.setPreferredSize(new Dimension(200, filterFrame.getPreferredSize().height));
        tools.add(filterFrame);
        tools.add(typeFilter);
        tools.add(nameFilterButton);
        tools.add(viewToggle);
        tools.add(expandAll);
        tools.add(collapseAll);
        tools.add(detailsToggle);
        tools.add(linkToggle);
        tools.add(hideBlockedToggle);
        tools.add(indexToggle);
        tools.add(refresh);

        // Aktionen neben der Lupe der gemeinsamen Fußleiste (Angelo 23.11.); Trennen und Schließen macht das ✕ am Reiter, der Stand steht in der
        // Statuszeile der App. „Neu verbinden“ erscheint nur, wenn die Verbindung weg ist.
        open.addActionListener(e -> openSelected());
        save.addActionListener(e -> save());
        reconnect.addActionListener(e -> {
            beforeReconnect.run(); // vom Menschen ausgelöst: darf auch mit gesperrtem Eintrag
            sessions.reconnect(session);
        });
        foot.setOpaque(false);
        foot.add(DeckBars.tools(open, save, reconnect), BorderLayout.CENTER);

        state.restoreFilter(nameFilter, session.profile().host(), session.user(), scope());
        applyDetails();
        updateFilterButtons();
        updatePath();
        session.observe(changed -> SwingUtilities.invokeLater(this::stateChanged));
        // Beobachtet werden Sperrliste und Analyse nur, solange der Bereich sichtbar ist (wie removeChangeListener).
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.DISPLAYABILITY_CHANGED) != 0) {
                if (isDisplayable()) {
                    watch();
                } else {
                    unwatch();
                }
            }
        });
        watch();
        stateChanged();
        updateIndex();
        // Wie flashActiveFilterButtons in MainframeMate: aktive Arten- und Sperrfilter blinken beim Öffnen kurz auf.
        if (!hiddenTypes.isEmpty()) {
            flash(typeFilter);
        }
        if (hideBlocked) {
            flash(hideBlockedToggle);
        }
    }

    JComponent tools() {
        return tools;
    }

    /** Läuft, bevor der Mensch „Neu verbinden“ drückt (der Bereich gibt den Sperrschutz dafür einmal frei). */
    private Runnable beforeReconnect = () -> { };

    void beforeReconnect(Runnable action) {
        beforeReconnect = action == null ? () -> { } : action;
    }

    JComponent foot() {
        return foot;
    }

    NdvSession session() {
        return session;
    }

    /** Die Ablage dieser Sitzung im gemeinsamen Editor. */
    NdvDocumentStore store() {
        return store;
    }

    /** Für den Reiter: {@code NDV: user@host:port – BIBLIOTHEK} wie der Tooltip in MainframeMate. */
    String tooltip() {
        return "NDV: " + (session.user().isEmpty() ? "" : session.user() + "@") + session.profile().label()
                + (library != null ? " – " + library : "");
    }

    private void stateChanged() {
        if (session.state() == NdvState.CONNECTED && !everConnected) {
            everConnected = true;
            unwatch();
            analysis = NdvAnalysisSettings.analysis(session);
            watch();
            // Erst jetzt ist der Benutzer bekannt: den gemerkten Filter dieses Benutzers holen (nicht den anonymen).
            state.restoreFilter(nameFilter, session.profile().host(), session.user(), scope());
            updateFilterButtons();
            loadLibraries();
        }
        showState();
    }

    // ---- Navigation ------------------------------------------------------------------------------------------------

    /** Bibliotheken neu und, wenn eine gewählt ist, ihre Objekte. */
    void refresh() {
        pendingObject = null;
        pendingLibrary = null;
        String current = library;
        library = null;
        loadLibraries();
        if (current != null) {
            openLibraryInternal(current);
        }
    }

    void loadLibraries() {
        libraryBusy.setBusy(true);
        libraryBusy.setText("Lade Bibliotheken …");
        note("Lade Bibliotheken …", false);
        work(() -> session.libraries("*"), list -> {
            libraryBusy.setBusy(false);
            allLibraries.clear();
            allLibraries.addAll(list);
            filterLibraries();
            note(list.isEmpty() ? "Keine Bibliotheken gefunden" : list.size() + " Bibliotheken", list.isEmpty());
            if (library == null && history.canBack() == false && libraryList.getSelectedValue() == null) {
                // Erst die Standard-Bibliothek des Formulars, sonst die zuletzt geöffnete.
                String preferred = session.profile().defaultLibrary();
                String wanted = !preferred.isEmpty() ? preferred : AppState.shared().get(LIBRARY_KEY);
                if (wanted != null && allLibraries.contains(wanted)) {
                    // Die Bibliotheksliste zuerst in den Verlauf, damit „Zurück“ dorthin und „Vor“ wieder zurück führt.
                    history.add("");
                    libraryList.setSelectedValue(wanted, true);
                } else if (wanted != null && wanted.equals(preferred) && !preferred.isEmpty()) {
                    history.add("");
                    openLibrary(preferred);
                } else {
                    history.add("");
                    updatePath();
                }
            }
        }, error -> {
            libraryBusy.setBusy(false);
            note("Bibliotheken nicht lesbar: " + ConnectForm.describe(error), true);
        });
    }

    /** Öffnet eine Bibliothek und merkt sie im Verlauf. */
    void openLibrary(String name) {
        openLibraryInternal(name);
        history.add(library);
        updatePath();
    }

    /** Öffnet eine Bibliothek und danach ein Objekt darin (Lesezeichen, {@code navigateToLibraryAndOpen}). */
    void openLibraryAndObject(String name, String objectName) {
        pendingObject = objectName;
        pendingLibrary = name == null ? null : name.toUpperCase(Locale.ROOT);
        openLibrary(name);
    }

    /** Listet die Objekte einer Bibliothek; jede Seite erscheint sofort, Abbrechen lässt sie stehen. */
    private void openLibraryInternal(String name) {
        library = name.toUpperCase(Locale.ROOT);
        if (pendingLibrary != null && !pendingLibrary.equals(library)) {
            // Eine andere Bibliothek gewählt, bevor das Lesezeichen geladen war: das Lesezeichen gilt nicht mehr.
            pendingObject = null;
            pendingLibrary = null;
        }
        AppState.shared().put(LIBRARY_KEY, library);
        if (!library.equals(libraryList.getSelectedValue())) {
            if (libraries.contains(library)) {
                libraryList.setSelectedValue(library, true);
            } else {
                libraryList.clearSelection();
            }
        }
        state.restoreFilter(nameFilter, session.profile().host(), session.user(), scope());
        updateFilterButtons();
        int generation = listing.incrementAndGet();
        objects.clear();
        filterObjects();
        String current = library;
        objectBusy.setText("Lade Objekte aus " + current + " …");
        objectBusy.setBusy(true);
        note("Lade Objekte aus " + current + " …", false);
        updatePath();
        work(() -> session.objects(current, "*", (page, total) -> {
            if (listing.get() != generation) {
                return false;
            }
            List<NdvObject> copy = List.copyOf(page);
            SwingUtilities.invokeLater(() -> {
                if (listing.get() == generation) {
                    if (total == copy.size()) {
                        // Erste Seite, auch nach einem Wiederholen ab vorn.
                        objects.clear();
                    }
                    objects.add(copy);
                    filterObjects();
                    objectBusy.setText(objects.getRowCount() + " Objekte aus " + current + " …");
                    note(objects.getRowCount() + " Objekte in " + current + " (laden …)", false);
                }
            });
            return true;
        }), count -> {
            if (listing.get() != generation) {
                return;
            }
            objectBusy.setBusy(false);
            note(objects.getRowCount() == 0 ? "Keine Objekte in " + current
                    : objects.getRowCount() + " Objekte in " + current, false);
            openPending();
            // Wie in MainframeMate: nach dem Auflisten Abhängigkeiten aufbauen und Quelltexte vorab laden.
            startAnalysis(current, List.copyOf(objects.rows), false);
        }, error -> {
            if (listing.get() != generation) {
                return;
            }
            objectBusy.setBusy(false);
            note((objects.getRowCount() > 0 ? objects.getRowCount() + " Objekte (unvollständig) – " : "")
                    + "Objekte nicht lesbar: " + ConnectForm.describe(error), true);
        });
    }

    /** Zeigt wieder die Bibliotheksliste (Eintrag „host:port“ im Verlauf). */
    private void showLibraries() {
        listing.incrementAndGet();
        pendingObject = null;
        pendingLibrary = null;
        library = null;
        libraryList.clearSelection();
        objects.clear();
        objectBusy.setBusy(false);
        state.restoreFilter(nameFilter, session.profile().host(), session.user(), scope());
        updateFilterButtons();
        filterObjects();
        note(allLibraries.size() + " Bibliotheken", false);
        updatePath();
        libraryList.requestFocusInWindow();
    }

    /** Pfadleiste wie {@code navigateToPath}: leer oder {@code host:port} = Bibliotheken, sonst die Bibliothek. */
    void navigateToPath() {
        String wanted = session.profile().libraryFromPath(pathField.getText());
        if (wanted.isEmpty()) {
            showLibraries();
            history.add("");
        } else {
            openLibrary(wanted);
        }
        updatePath();
    }

    void navigateBack() {
        history.back().ifPresentOrElse(this::go, () -> {
            if (library != null) {
                showLibraries();
                history.add("");
            }
        });
        updatePath();
    }

    void navigateForward() {
        history.forward().ifPresent(this::go);
        updatePath();
    }

    /** Springt {@code steps} Einträge weit (Verlauf des Zurück-Knopfs); ein Schritt wie die Maustasten. */
    void jump(int steps) {
        if (steps == -1) {
            navigateBack();
        } else if (steps == 1) {
            navigateForward();
        } else {
            history.go(steps).ifPresent(this::go);
            updatePath();
        }
    }

    /** Der Verlauf mit Namen; ohne Einträge zurück, aber in einer Bibliothek, führt Zurück zur Bibliotheksliste. */
    private com.aresstack.spaceharvester.app.ui.controls.BackButton.History visits() {
        java.util.List<String> back = history.backEntries().stream().map(NdvBrowser::visitName).toList();
        if (back.isEmpty() && library != null) {
            back = java.util.List.of(visitName(""));
        }
        return new com.aresstack.spaceharvester.app.ui.controls.BackButton.History(history.forwardEntries().stream().map(NdvBrowser::visitName).toList(), back);
    }

    private static String visitName(String entry) {
        return entry.isEmpty() ? "Bibliotheken" : entry;
    }

    private void go(String entry) {
        if (entry.isEmpty()) {
            showLibraries();
        } else {
            openLibraryInternal(entry);
        }
    }

    /** Meldet einen Wechsel der Bibliothek (Tooltip der Sitzung). */
    void onLibraryChanged(Runnable listener) {
        libraryChanged = Objects.requireNonNull(listener, "listener");
    }

    private void updatePath() {
        libraryChanged.run();
        updateIndex();
        pathField.setText(session.profile().label() + (library != null ? "/" + library : ""));
        backButton.refresh();
        boolean marked = library != null && state.bookmarks(session.profile().host())
                .contains(new NdvBookmark(library, ""));
        bookmarkStar.setIcon(marked ? Glyph.STAR_FILLED : Glyph.STAR);
        bookmarkStar.setEnabled(library != null);
        bookmarkStar.setToolTipText(marked ? "Lesezeichen für " + library + " entfernen"
                : "Lesezeichen für " + (library != null ? library : "diese Bibliothek") + " setzen");
    }

    /** Nach dem Laden das vorgemerkte Objekt öffnen (wie {@code autoOpenPendingObject}). */
    private void openPending() {
        String wanted = pendingObject;
        String wantedLibrary = pendingLibrary;
        pendingObject = null;
        pendingLibrary = null;
        if (wanted == null || wanted.isEmpty() || wantedLibrary != null && !wantedLibrary.equals(library)) {
            return;
        }
        NdvBookmark probe = new NdvBookmark(library, wanted);
        List<NdvObject> matches = objects.rows.stream()
                .filter(object -> object.displayName().equalsIgnoreCase(probe.objectName())).toList();
        int line = pendingLineByName;
        pendingLineByName = 0;
        if (matches.size() > 1) {
            note("Mehrdeutiges Lesezeichen: " + wanted + " – bitte Objekt in der Liste auswählen", true);
            return;
        }
        if (matches.size() == 1) {
            openAt(matches.getFirst(), line);
            return;
        }
        note("Lesezeichen nicht gefunden: " + library + "/" + wanted, true);
    }

    // ---- Lesezeichen -----------------------------------------------------------------------------------------------

    private void toggleLibraryBookmark() {
        if (library == null) {
            return;
        }
        toggleBookmark(new NdvBookmark(library, ""));
    }

    private void toggleBookmark(NdvBookmark bookmark) {
        List<NdvBookmark> all = new ArrayList<>(state.bookmarks(session.profile().host()));
        if (!all.remove(bookmark)) {
            all.add(bookmark);
        }
        state.bookmarks(session.profile().host(), all);
        updatePath();
    }

    /** Lesezeichen dieses Servers: öffnen oder entfernen; das geöffnete Objekt merken. */
    JPopupMenu bookmarkMenu() {
        JPopupMenu menu = new JPopupMenu();
        if (shown != null) {
            NdvBookmark current = NdvBookmark.of(shown);
            boolean known = state.bookmarks(session.profile().host()).contains(current);
            JMenuItem item = new JMenuItem((known ? "Lesezeichen entfernen: " : "Lesezeichen setzen: ")
                    + current.label());
            item.addActionListener(e -> toggleBookmark(current));
            menu.add(item);
            menu.addSeparator();
        }
        List<NdvBookmark> all = state.bookmarks(session.profile().host());
        if (all.isEmpty()) {
            JMenuItem none = new JMenuItem("Keine Lesezeichen – Stern neben dem Pfad setzt eins");
            none.setEnabled(false);
            menu.add(none);
        }
        for (NdvBookmark bookmark : all) {
            JMenu entry = new JMenu(bookmark.label());
            JMenuItem go = new JMenuItem("Öffnen");
            go.addActionListener(e -> openBookmark(bookmark));
            JMenuItem remove = new JMenuItem("Entfernen");
            remove.addActionListener(e -> toggleBookmark(bookmark));
            entry.add(go);
            entry.add(remove);
            menu.add(entry);
        }
        return menu;
    }

    void openBookmark(NdvBookmark bookmark) {
        if (bookmark.object().isEmpty()) {
            pendingObject = null;
            openLibrary(bookmark.library());
        } else {
            openLibraryAndObject(bookmark.library(), bookmark.object());
        }
    }

    // ---- Öffnen und Editor -----------------------------------------------------------------------------------------

    /**
     * Öffnet den Quelltext des gewählten Objekts unten im gemeinsamen Editor; schreibt nichts. Ist er schon offen und
     * unverändert, wird er neu vom Server gelesen; ungespeicherte Änderungen bleiben stehen.
     */
    void openSelected() {
        NdvObject object = leadObject();
        if (object != null) {
            open(object);
        }
    }

    private void open(NdvObject object) {
        if (!object.source()) {
            note("„" + object.displayName() + "“ hat keinen Quelltext (Art: " + object.type().name() + ")", true);
            return;
        }
        String location = store.register(object);
        objectOf.put(location, object);
        boolean known = editors.find(store, location).isPresent();
        EditorSession opened = editors.open(store, location, NdvDocumentStore.name(object));
        shown = object;
        show(opened);
        if (known && !opened.dirty() && opened.state() != EditorSession.State.LOADING
                && opened.state() != EditorSession.State.SAVING) {
            opened.load();
        }
        if (linkWithEditor) {
            selectShown();
        }
    }

    /** Die Seitenleiste: Suche oben (je Reiter ein Feld, sichtbar immer eins), Reiter, Fußleiste mit den Reitern. */
    private JPanel sideBar() {
        sideBar = new EditorSideBar(
                new EditorSideBar.Home(SIDE_LIBRARIES, FilesIcons.folder(), "Bibliotheken",
                        new com.aresstack.spaceharvester.app.ui.controls.SideHead(SIDE_LIBRARIES, libraryFilter),
                        libraryBusy, null),
                List.of(new EditorSideBar.Extra(SIDE_RELATIONS, Glyph.GRAPH, "Aufrufe/Aufrufer/Daten aus dem Index der "
                        + "Bibliothek (Tiefe bis " + NdvAnalysis.HIERARCHY_DEPTH + ")", "Aufrufe/Daten filtern",
                        relationsPanel, relationsPanel::query)),
                state.sideTab(), state::sideTab);
        sideBar.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 6));
        return sideBar;
    }

    /** Zeigt einen Reiter der Seitenleiste samt seiner Suche. */
    void selectSide(String name) {
        sideBar.select(name);
    }

    String sideTab() {
        return sideBar.tab();
    }

    PlaceholderField sideFilter(String name) {
        return name.equals(SIDE_LIBRARIES) ? libraryFilter : sideBar.filter(name);
    }

    /** Schreibt den Quelltext im Editor auf den Server (Konfliktprüfung macht die Ablage). */
    void save() {
        if (document != null && document.dirty()) {
            document.save();
        }
    }

    private void show(EditorSession opened) {
        DocumentEditor editor = editorOf.get(opened);
        if (editor == null) {
            if (editorOf.isEmpty()) {
                // Mit dem ersten Quelltext bekommt der Editor den größeren Teil; die Liste bleibt sichtbar.
                right.setDividerLocation(0.4);
            }
            editor = new DocumentEditor(opened);
            sideBar.attach(editor);
            editorOf.put(opened, editor);
            sourceStage.add(editor, opened.location());
            opened.observe(changed -> SwingUtilities.invokeLater(() -> documentChanged(changed)));
        }
        document = opened;
        // Neu gezeigt: der geladene Stand wird gemeldet, auch wenn das Laden schon fertig war.
        announcedRevision = -1;
        sourceCards.show(sourceStage, opened.location());
        sideBar.show(editor);
        if (opened.state() == EditorSession.State.LOADING) {
            note("Lade Quelltext: " + opened.location() + " …", false);
        }
        showDetailsOf(shown);
        documentChanged(opened);
    }

    /**
     * Die Angaben des Servers über dem Editor, wie der Kopf in MainframeMate ({@code createNdvMetadataBanner}):
     * Bibliothek, Art (Modus) [Kind], DBID/FNR, Benutzer, Datum, Codepage; darunter GP, Zugriff und Größe.
     */
    static String[] metadata(NdvObject object) {
        String sep = "  |  ";
        StringBuilder first = new StringBuilder("Library: ").append(object.library()).append(sep)
                .append(object.type().name());
        NdvObject.Details details = object.details();
        if (!details.mode().isEmpty()) {
            first.append(" (").append(details.mode()).append(")");
        }
        if (!details.kind().isEmpty() && !"SOURCE".equalsIgnoreCase(details.kind())) {
            first.append(" [").append(details.kind()).append("]");
        }
        int dbid = object.location().databaseId();
        int fnr = object.location().fileNumber();
        if (dbid > 0 || fnr > 0) {
            first.append(sep);
            if (dbid > 0) {
                first.append("DBID: ").append(dbid);
            }
            if (fnr > 0) {
                first.append(dbid > 0 ? "  " : "").append("FNR: ").append(fnr);
            }
        }
        if (!object.user().isEmpty()) {
            first.append(sep).append("User: ").append(object.user());
        }
        if (!object.changed().isEmpty()) {
            first.append(sep).append(object.changed());
        }
        if (!details.codePage().isEmpty()) {
            first.append(sep).append("CP: ").append(details.codePage());
        }
        List<String> second = new ArrayList<>();
        if (!details.gpUser().isEmpty()) {
            second.add("GP-User: " + details.gpUser());
        }
        if (!details.gpDate().isEmpty()) {
            second.add("GP-Datum: " + details.gpDate());
        }
        if (!details.accessDate().isEmpty()) {
            second.add("Zugriff: " + details.accessDate());
        }
        if (object.sourceSize() > 0) {
            second.add("Größe: " + object.sourceSize() + " Bytes");
        }
        return new String[] {first.toString(), String.join(sep, second)};
    }

    private void showDetailsOf(NdvObject object) {
        if (object == null) {
            sourceDetails.setVisible(false);
            return;
        }
        String[] lines = metadata(object);
        sourceDetails.setText(lines[1].isEmpty() ? lines[0] : "<html>" + escape(lines[0]) + "<br>"
                + escape(lines[1]) + "</html>");
        sourceDetails.setToolTipText(lines[0] + (lines[1].isEmpty() ? "" : " – " + lines[1]));
        sourceDetails.setVisible(true);
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void documentChanged(EditorSession changed) {
        if (changed != document) {
            return;
        }
        EditorSession.State docState = changed.state();
        String title = changed.location();
        if (docState == EditorSession.State.LOADING || docState == EditorSession.State.SAVING) {
            title += " · " + docState.label() + " …";
        } else {
            title += changed.text().map(text -> " · " + lines(text)).orElse("");
            if (changed.dirty()) {
                title += " · ungespeichert";
            }
        }
        sourceTitle.setText(title);
        if (changed.revision() != announcedRevision && docState == EditorSession.State.READY) {
            announcedRevision = changed.revision();
            String text = changed.text().orElse("");
            // In den Zwischenspeicher nur der Stand des Servers (geladen oder gespeichert), nie ungespeicherter Text.
            loaded(changed, changed.document().map(com.aresstack.spaceharvester.deck.editor.EditorDocument::text)
                    .orElse(""));
            note(text.isEmpty() ? "Leerer Quelltext: " + changed.location() : "Geöffnet: " + changed.location(),
                    text.isEmpty());
        } else if (docState == EditorSession.State.READY && changed.note().isPresent()) {
            String message = changed.note().get();
            note(message.equals("Gespeichert") ? "Gespeichert: " + changed.location() : message,
                    !message.equals("Gespeichert"));
            if (message.equals("Gespeichert") && objectOf.containsKey(changed.location())) {
                // Wie onSourceSaved: Zwischenspeicher und Abhängigkeiten nachführen.
                NdvObject saved = objectOf.get(changed.location());
                String text = changed.document().map(com.aresstack.spaceharvester.deck.editor.EditorDocument::text)
                        .orElse("");
                work(() -> {
                    analysis.saved(saved, text);
                    return Boolean.TRUE;
                }, ok -> showRelations(), error -> note("Abhängigkeiten nicht nachgeführt: "
                        + ConnectForm.describe(error), true));
            }
        } else if (docState == EditorSession.State.CONFLICT) {
            // Den Grund und „Überschreiben“ zeigt der Hinweis im Editor; die Statuszeile sagt nur das Ergebnis.
            note("Nicht gespeichert: " + changed.location() + " wurde auf dem Server geändert", true);
        } else if (docState == EditorSession.State.FAILED) {
            note("Quelltext nicht lesbar: " + changed.location(), true);
        }
        showState();
    }

    private static String lines(String text) {
        long count = text.isEmpty() ? 0 : text.lines().count();
        return count == 1 ? "1 Zeile" : count + " Zeilen";
    }

    /** Ungespeicherte Dokumente dieser Sitzung (Rückfrage vor dem Schließen). */
    List<EditorSession> unsaved() {
        return editors.all().stream().filter(doc -> doc.store() == store && doc.dirty()).toList();
    }

    /** Nimmt alle Dokumente dieser Sitzung aus dem gemeinsamen Editor. */
    void closeDocuments() {
        editors.all().stream().filter(doc -> doc.store() == store).forEach(editors::close);
    }

    private void selectShown() {
        if (shown == null) {
            return;
        }
        if (groupByType) {
            objectTree.select(shown);
            return;
        }
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            if (objects.at(objectTable.convertRowIndexToModel(i)).equals(shown)) {
                objectTable.setRowSelectionInterval(i, i);
                objectTable.scrollRectToVisible(objectTable.getCellRect(i, 0, true));
                return;
            }
        }
    }

    /**
     * Ob eine Bibliothek begehbar ist: erlaubt, oder bei „Alles sperren“ nicht selbst gesperrt und mit freigegebenen
     * Objekten darin (die Objekte prüft danach jedes einzeln).
     */
    boolean libraryAccessible(String lib) {
        String path = NdvAccessRules.path(lib, "");
        return rules.isAllowed(path) || !rules.isBlacklisted(path) && rules.hasWhitelistedDescendants(path);
    }

    // ---- Analyse: Zwischenspeicher, Abhängigkeiten, Sperrliste (wie NdvSourceCacheService in MainframeMate) ------

    private void watch() {
        rules.unobserve(rulesChanged);
        analysis.unobserve(analysisChanged);
        analysis.cache().unobserve(analysisChanged);
        NdvAnalysisSettings.unobserve(settingsChanged);
        rules.observe(rulesChanged);
        analysis.observe(analysisChanged);
        analysis.cache().observe(analysisChanged);
        NdvAnalysisSettings.observe(settingsChanged);
    }

    private void unwatch() {
        NdvAnalysisSettings.unobserve(settingsChanged);
        rules.unobserve(rulesChanged);
        analysis.unobserve(analysisChanged);
        analysis.cache().unobserve(analysisChanged);
    }

    /** Die Analyse dieses Servers (für Tests). */
    NdvAnalysis analysis() {
        return analysis;
    }

    /**
     * Baut aus dem Zwischenspeicher sofort den Graphen und lädt dann im Hintergrund nach, was fehlt oder sich
     * geändert hat (nur Erlaubtes); danach wird der Graph neu gebaut. Mit {@code explicit} auch ohne Änderung der
     * Sperrliste erneut.
     */
    private void startAnalysis(String lib, List<NdvObject> listed, boolean explicit) {
        if (lib == null || listed.isEmpty()) {
            return;
        }
        // Mit Größe und Datum jedes Objekts: Ein auf dem Server geänderter Quelltext ändert meist weder Anzahl noch
        // Regeln, muss nach „Neu laden“ aber neu geprüft werden.
        String key = lib + "|" + rules.snapshot().hashCode() + "|" + listed.stream()
                .map(o -> o.path() + ":" + o.location().databaseId() + ":" + o.location().fileNumber()
                        + ":" + o.sourceSize() + ":" + o.changed()).toList().hashCode();
        if (!explicit && key.equals(lastPrefetch) && analysis.graph(lib).isPresent()) {
            return;
        }
        lastPrefetch = key;
        List<NdvObject> allowed = listed.stream()
                .filter(o -> rules.isAllowed(NdvAccessRules.path(lib, o.displayName()))).toList();
        work(() -> {
            if (analysis.cache().size(lib) > 0) {
                analysis.build(lib);
            }
            return Boolean.TRUE;
        }, ok -> showRelations(), error -> { });
        work(() -> analysis.cache().prefetch(session, lib, allowed, new NdvSourceCache.PrefetchListener() {
            @Override
            public void progress(int current, int total, String name) {
                SwingUtilities.invokeLater(() -> {
                    indexPanel.progress(current, total);
                    updateIndex();
                });
            }

            @Override
            public void complete(NdvSourceCache.PrefetchResult result) {
                // Nach „Cache leeren“ nichts neu bauen; nach geänderten Regeln auch ohne Neues (Gesperrtes fällt heraus).
                int rulesNow = rules.snapshot().hashCode();
                if (analysis.cache().size(lib) > 0 && (result.loaded() > 0 || analysis.graph(lib).isEmpty()
                        || !Integer.valueOf(rulesNow).equals(builtWith.get(lib)))) {
                    analysis.build(lib);
                    builtWith.put(lib, rulesNow);
                }
            }
        }), result -> {
            indexPanel.done();
            updateIndex();
            showRelations();
            if (!result.failed().isEmpty()) {
                // Jeder Fehler mit Grund: das erste Objekt mit Grund, die übrigen gezählt.
                Map.Entry<String, String> first = result.failed().entrySet().iterator().next();
                note(result.failed().size() + " Quelltexte nicht zwischengespeichert, z. B. " + first.getKey() + ": "
                        + first.getValue(), true);
            }
        }, error -> {
            indexPanel.done();
            updateIndex();
            note("Zwischenspeichern fehlgeschlagen: " + ConnectForm.describe(error), true);
        });
    }

    /**
     * „Auswahl zwischenspeichern“ wie {@code indexSelectedOrAll}: auf Objektebene die gewählten oder alle sichtbaren
     * Objekte, in der Bibliotheksliste die gewählten oder alle sichtbaren Bibliotheken.
     */
    void cacheSelectedOrAll() {
        showIndex(true);
        if (library == null) {
            List<String> libs = libraryList.getSelectedValuesList().isEmpty() ? visibleLibraries()
                    : List.copyOf(libraryList.getSelectedValuesList());
            indexPanel.place(session.profile().label(), libs.size() == 1 ? "Bibliothek: " + libs.getFirst()
                    : libs.size() + " Bibliotheken");
            for (String lib : libs) {
                if (!libraryAccessible(lib)) {
                    continue;
                }
                work(() -> {
                    List<NdvObject> listed = new ArrayList<>();
                    session.objects(lib, "*", (page, total) -> {
                        listed.addAll(page);
                        return true;
                    });
                    return listed;
                }, listed -> startAnalysis(lib, listed, true), error -> note("Objekte nicht lesbar in " + lib + ": "
                        + ConnectForm.describe(error), true));
            }
            return;
        }
        List<NdvObject> chosen = selectedObjects();
        if (chosen.isEmpty()) {
            chosen = visibleObjectList();
        }
        indexPanel.place(session.profile().label() + "/" + library, chosen.size() + " Objekte");
        startAnalysis(library, chosen, true);
    }

    /** „Cache leeren“ wie in MainframeMate, mit Rückfrage. */
    void clearCache() {
        if (library == null) {
            note("Keine Bibliothek ausgewählt", true);
            return;
        }
        String lib = library;
        if (!confirm.test("Cache leeren", "Zwischengespeicherte Quelltexte, Suche und Abhängigkeiten von " + lib
                + " wirklich verwerfen? Sie werden beim nächsten Öffnen neu geladen.")) {
            return;
        }
        work(() -> {
            analysis.clear(lib);
            return Boolean.TRUE;
        }, ok -> {
            lastPrefetch = "";
            note("Cache für " + lib + " geleert", false);
            updateIndex();
            showRelations();
        }, error -> note("Cache nicht geleert: " + ConnectForm.describe(error), true));
    }

    private void showIndex(boolean visible) {
        indexVisible = visible;
        state.indexVisible(visible);
        indexToggle.marked(visible);
        indexPanel.setVisible(visible);
        updateIndex();
        revalidate();
        repaint();
    }

    /** Pfade der Auswahl für die Sperrliste; ohne Auswahl die Bibliothek (wie {@code getSelectedPrefixedPaths}). */
    List<String> selectedPaths() {
        List<String> paths = new ArrayList<>();
        if (library == null) {
            libraryList.getSelectedValuesList().forEach(lib -> paths.add(NdvAccessRules.path(lib, "")));
            return paths;
        }
        selectedObjects().forEach(o -> paths.add(NdvAccessRules.path(o.library(), o.displayName())));
        if (paths.isEmpty()) {
            paths.add(NdvAccessRules.path(library, ""));
        }
        return paths;
    }

    private void rulesChanged() {
        filterObjects();
        filterLibraries();
        updateFilterState();
        // Wie securityChangeListener: geänderte Regeln lösen das Vorabladen erneut aus.
        if (library != null && !objectsLoading() && !objects.rows.isEmpty()) {
            startAnalysis(library, List.copyOf(objects.rows), false);
        }
    }

    // ---- Globale Suche: „Indexieren (Regel anlegen)“ und „Jetzt indexieren“ wie die IndexingSidebar -------------

    /** Die Bibliotheken für die Regel: die geöffnete, sonst die in der Liste gewählten. */
    List<String> indexLibraries() {
        if (library != null) {
            return List.of(library);
        }
        return List.copyOf(libraryList.getSelectedValuesList());
    }

    /** Zustand „Globale Suche“ in der Index-Leiste: Regel vorhanden, deckt die Bibliothek ab, oder fehlt. */
    void updateSearchLink() {
        if (indexPanel == null) {
            return;
        }
        NdvSearchLink link = NdvAnalysisSettings.searchLink();
        List<String> libs = indexLibraries();
        if (!link.available()) {
            indexPanel.searchRule("Ohne Wissensquellen nicht verfügbar", Comic.MUTED, false, false);
            return;
        }
        java.util.Optional<NdvSearchLink.Rule> rule;
        try {
            rule = link.rule(session.profile(), session.account());
        } catch (RuntimeException e) {
            indexPanel.searchRule("Wissensquellen nicht lesbar: " + ConnectForm.describe(e), Comic.RED, false,
                    false);
            return;
        }
        if (rule.isEmpty()) {
            indexPanel.searchRule("Nicht in der globalen Suche", Comic.MUTED, !libs.isEmpty(), false);
        } else if (!libs.isEmpty() && libs.stream().allMatch(rule.get()::covers)) {
            indexPanel.searchRule("In „" + rule.get().title() + "“, global durchsuchbar", Comic.GREEN, true, true);
        } else if (libs.isEmpty()) {
            indexPanel.searchRule("Quelle „" + rule.get().title() + "“: " + String.join(", ",
                    rule.get().libraries()), Comic.GREEN, false, true);
        } else {
            indexPanel.searchRule("„" + rule.get().title() + "“ ohne " + String.join(", ", libs.stream()
                    .filter(lib -> !rule.get().covers(lib)).toList()), Comic.YELLOW, true, true);
        }
    }

    /**
     * „Indexieren (Regel anlegen)“: Wissensquelle für diesen Server anlegen oder um die Bibliothek ergänzen; nach dem
     * Speichern gleich indexieren (wie die erste Ausführung der Regel in MainframeMate).
     */
    void createSearchRule() {
        List<String> libs = indexLibraries();
        NdvSearchLink link = NdvAnalysisSettings.searchLink();
        if (libs.isEmpty() || !link.available()) {
            note("Für die globale Suche erst eine Bibliothek wählen", true);
            return;
        }
        List<String> blocked = libs.stream().filter(lib -> !rules.isAllowed(NdvAccessRules.path(lib, ""))).toList();
        if (!blocked.isEmpty()) {
            note("Gesperrt, nicht indiziert: " + String.join(", ", blocked), true);
            return;
        }
        try {
            link.createRule(this, session.profile(), session.account(), libs, sourceId -> {
                note("Regel für " + String.join(", ", libs) + " gespeichert; Indexieren startet", false);
                indexNow(sourceId);
            });
        } catch (RuntimeException e) {
            note("Regel nicht angelegt: " + ConnectForm.describe(e), true);
        }
    }

    /** „Jetzt indexieren“: Abgleich der Wissensquelle dieses Servers als eigener Auftrag. */
    void indexNow() {
        NdvAnalysisSettings.searchLink().rule(session.profile(), session.account()).ifPresentOrElse(rule -> indexNow(rule.sourceId()),
                () -> note("Für diesen Server gibt es noch keine Regel", true));
    }

    private void indexNow(String sourceId) {
        NdvSearchLink link = NdvAnalysisSettings.searchLink();
        indexPanel.indexNow.setEnabled(false);
        work(() -> link.indexNow(sourceId), started -> {
            note(started, false);
            updateSearchLink();
        }, error -> {
            note("Indexieren nicht gestartet: " + ConnectForm.describe(error), true);
            updateSearchLink();
        });
    }

    private void analysisChanged() {
        updateIndex();
    }

    private void updateFilterState() {
        updateSearchLink();
        List<String> paths = selectedPaths();
        if (paths.isEmpty()) {
            indexPanel.filter("-");
        } else if (paths.size() == 1) {
            indexPanel.filter(rules.describe(paths.getFirst()));
        } else {
            long blocked = paths.stream().filter(p -> !rules.isAllowed(p)).count();
            indexPanel.filter(paths.size() + " gewählt, " + blocked + " gesperrt");
        }
    }

    /** Zustand des Zwischenspeichers wie {@code computeNdvCacheStatus}. */
    private void updateIndex() {
        if (indexPanel == null) {
            return;
        }
        String when = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern(
                "dd.MM.yyyy HH:mm"));
        if (library == null) {
            indexPanel.place(session.profile().label(), "Bibliotheksliste");
            int total = analysis.cache().totalSize();
            indexPanel.status(total > 0 ? new NdvIndexPanel.Status("Zwischengespeichert", Comic.GREEN,
                    total + " Quelltexte", when) : new NdvIndexPanel.Status("Nicht zwischengespeichert", Comic.MUTED,
                    "-", "-"));
        } else {
            indexPanel.place(session.profile().label() + "/" + library, objects.getRowCount() + " Objekte");
            int cached = analysis.cache().size(library);
            int total = (int) objects.rows.stream().filter(NdvSourceCache::naturalSource).count();
            if (analysis.cache().prefetching(library)) {
                indexPanel.status(new NdvIndexPanel.Status("Wird zwischengespeichert …", Comic.BLUE,
                        cached + " / " + total, "-"));
            } else if (cached > 0) {
                String graph = analysis.graph(library).map(g -> ", " + g.getKnownSources().size() + " analysiert")
                        .orElse("");
                indexPanel.status(new NdvIndexPanel.Status("Zwischengespeichert", Comic.GREEN,
                        cached + " / " + total + graph, when));
            } else {
                indexPanel.status(new NdvIndexPanel.Status("Nicht zwischengespeichert", Comic.MUTED,
                        "0 / " + total, "-"));
            }
        }
        updateFilterState();
    }

    private static final int SEARCH_LIMIT = 200;

    private void searchSource() {
        String needle = indexPanel.search.getText();
        String lib = library;
        if (lib == null) {
            indexPanel.hits(List.of(), needle, SEARCH_LIMIT);
            return;
        }
        indexPanel.hits(analysis.cache().search(lib, needle, SEARCH_LIMIT), needle, SEARCH_LIMIT);
    }

    private void openHit(NdvSourceCache.Hit hit) {
        List<NdvObject> matches = objects.rows.stream().filter(hit::matches).toList();
        if (matches.size() != 1 || objects.rows.stream()
                .filter(object -> object.path().equalsIgnoreCase(matches.getFirst().path())).count() != 1) {
            note("Quelltextfundstelle nicht eindeutig in der aktuellen Bibliothek auflösbar", true);
            return;
        }
        openAt(matches.getFirst(), hit.line());
    }

    /** Beziehungen des geöffneten Objekts neu zeigen. */
    private void showRelations() {
        if (shown == null) {
            relationsPanel.show(null, java.util.Optional.empty(), null);
            return;
        }
        String text = document != null ? document.text().orElse("") : "";
        relationsPanel.show(shown.displayName(), analysis.graph(shown.library()),
                analysis.analyze(text, shown.displayName()));
    }

    /** Nach dem Laden: zwischenspeichern, Beziehungen zeigen, vorgemerkte Zeile anspringen. */
    private void loaded(EditorSession changed, String text) {
        NdvObject object = objectOf.get(changed.location());
        if (object != null && !text.isEmpty()) {
            analysis.cache().put(object, text);
        }
        showRelations();
        Integer line = pendingLine.remove(changed.location());
        DocumentEditor editor = editorOf.get(changed);
        if (line != null && editor != null) {
            SwingUtilities.invokeLater(() -> editor.goToLine(line));
        }
    }

    /**
     * Öffnet ein Objekt nach Namen, wie {@code openNdvDependencyTarget}: erst in der eigenen Bibliothek, sonst über
     * die Suchreihenfolge; mit Zeile springt der Editor dorthin.
     */
    void openByName(String name, int line) {
        openByName(name, line, null);
    }

    /** Öffnet ein Objekt einer bestimmten Bibliothek ({@code null} = Suchreihenfolge) und springt zur Zeile. */
    void openInLibrary(String library, String name, int line) {
        openByName(name, line, library == null || library.isBlank() ? null : library.trim().toUpperCase(Locale.ROOT));
    }

    private void openByName(String name, int line, String inLibrary) {
        String wanted = name.toUpperCase(Locale.ROOT);
        String lib = inLibrary != null ? inLibrary : library;
        if (lib != null && lib.equals(library)) {
            List<NdvObject> matches = objects.rows.stream()
                    .filter(object -> object.displayName().equalsIgnoreCase(wanted)).toList();
            if (matches.size() > 1) {
                note("Mehrdeutiger Objektname: " + wanted + " – bitte Objekt in der Liste auswählen", true);
                return;
            }
            if (matches.size() == 1) {
                openAt(matches.getFirst(), line);
                return;
            }
        }
        java.util.Optional<String> target = inLibrary != null ? java.util.Optional.of(inLibrary)
                : NdvAnalysisSettings.resolver().resolve(wanted, library, session.profile().defaultLibrary(),
                analysis.cache());
        if (target.isEmpty() || target.get().equals(library)) {
            note("„" + wanted + "“ nicht gefunden – Suchreihenfolge unter Einstellungen › NDV", true);
            return;
        }
        pendingLineByName = line;
        openLibraryAndObject(target.get(), wanted);
    }

    /** Zeile für ein Objekt, das erst nach dem Laden einer anderen Bibliothek geöffnet wird. */
    private int pendingLineByName;

    private void openAt(NdvObject object, int line) {
        String location = store.register(object);
        DocumentEditor existing = editors.find(store, location).map(editorOf::get).orElse(null);
        if (line > 0) {
            pendingLine.put(location, line);
        }
        open(object);
        EditorSession opened = editors.find(store, location).orElse(null);
        if (existing != null && opened != null && opened.state() == EditorSession.State.READY && line > 0
                && opened.dirty()) {
            pendingLine.remove(location);
            existing.goToLine(line);
        }
    }

    /**
     * Öffnet ein Natural-Programm aus der JCL über die STEPLIB-Zuordnung (wie {@code openNaturalFromJcl}): fehlt die
     * Zuordnung, gilt die Suchreihenfolge, sonst wird einmal gefragt und die Antwort gemerkt.
     */
    void openFromJcl(String stepLib, String program) {
        var resolver = NdvAnalysisSettings.resolver();
        String lib = resolver.forStepLib(stepLib).orElse(null);
        if (lib == null && !resolver.searchOrder().isEmpty()) {
            lib = resolver.resolve(program, null, session.profile().defaultLibrary(), analysis.cache()).orElse(null);
        }
        if (lib == null) {
            String answer = askLibrary.apply(stepLib, com.aresstack.spaceharvester.deck.ndv.analysis
                    .NdvLibraryResolver.suggest(stepLib));
            if (answer == null || answer.isBlank()) {
                return;
            }
            lib = answer.trim().toUpperCase(Locale.ROOT);
            if (stepLib != null && !stepLib.isBlank()) {
                NdvAnalysisSettings.stepLibs(resolver.withStepLib(stepLib, lib).stepLibs());
            }
        }
        openLibraryAndObject(lib, program.toUpperCase(Locale.ROOT));
    }

    private String askLibrary(String stepLib, String suggestion) {
        PlaceholderField field = new PlaceholderField(16, suggestion);
        Comic.style(field);
        field.setText(suggestion);
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(Comic.wrapped("In der JCL wird die STEPLIB „" + stepLib + "“ verwendet. Welche NDV-Bibliothek "
                + "entspricht ihr? Die Zuordnung wird gemerkt (Einstellungen › NDV).", Comic.TEXT, 12.5f),
                BorderLayout.NORTH);
        body.add(new InputFrame(field), BorderLayout.CENTER);
        return Dialogs.formOverlay(this, "Natural-Bibliothek zuordnen", body, "Übernehmen") ? field.getText() : null;
    }

    private List<NdvObject> visibleObjectList() {
        List<NdvObject> list = new ArrayList<>();
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            list.add(objects.at(objectTable.convertRowIndexToModel(i)));
        }
        return list;
    }

    NdvIndexPanel indexPanel() {
        return indexPanel;
    }

    NdvRelationsPanel relationsPanel() {
        return relationsPanel;
    }

    IconButton hideBlockedToggle() {
        return hideBlockedToggle;
    }

    IconButton indexToggle() {
        return indexToggle;
    }

    // ---- Auswahl, Kontextmenü, Tastatur ----------------------------------------------------------------------------

    private JComponent activeView() {
        return groupByType ? objectTree : objectTable;
    }

    private int rowCount() {
        return groupByType ? objectTree.getRowCount() : objectTable.getRowCount();
    }

    private void selectRow(int row) {
        if (groupByType) {
            objectTree.setSelectionRow(row);
            objectTree.scrollRowToVisible(row);
        } else {
            objectTable.setRowSelectionInterval(row, row);
            objectTable.scrollRectToVisible(objectTable.getCellRect(row, 0, true));
        }
    }

    /** Die gewählten Objekte der aktiven Ansicht; eine gewählte Gruppe zählt mit allen Objekten. */
    List<NdvObject> selectedObjects() {
        if (groupByType) {
            return objectTree.selectedObjects();
        }
        List<NdvObject> result = new ArrayList<>();
        for (int row : objectTable.getSelectedRows()) {
            result.add(objects.at(objectTable.convertRowIndexToModel(row)));
        }
        return result;
    }

    private NdvObject leadObject() {
        if (groupByType) {
            return objectTree.leadObject();
        }
        int row = objectTable.getSelectionModel().getLeadSelectionIndex();
        if (row < 0 || !objectTable.isRowSelected(row)) {
            row = objectTable.getSelectedRow();
        }
        return row < 0 ? null : objects.at(objectTable.convertRowIndexToModel(row));
    }

    /** Rechtsklick wie in MainframeMate: nicht gewählte Zeile wählen, dann Öffnen, Alles/Nichts auswählen. */
    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        if (event.getComponent() == objectTable) {
            int row = objectTable.rowAtPoint(event.getPoint());
            if (row >= 0 && !objectTable.isRowSelected(row)) {
                objectTable.setRowSelectionInterval(row, row);
            }
        } else {
            int row = objectTree.getRowForLocation(event.getX(), event.getY());
            if (row >= 0 && !objectTree.isRowSelected(row)) {
                objectTree.setSelectionRow(row);
            }
        }
        contextMenu().show(event.getComponent(), event.getX(), event.getY());
    }

    JPopupMenu contextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("Öffnen");
        openItem.setEnabled(leadObject() != null);
        openItem.addActionListener(e -> openSelected());
        menu.add(openItem);
        menu.addSeparator();
        JMenuItem all = new JMenuItem("Alles auswählen");
        all.addActionListener(e -> selectAll());
        menu.add(all);
        JMenuItem none = new JMenuItem("Nichts auswählen");
        none.addActionListener(e -> selectNone());
        menu.add(none);
        menu.addSeparator();
        int selectedCount = selectedObjects().size();
        JMenuItem cache = new JMenuItem(selectedCount == 0 ? "Alles zwischenspeichern"
                : "Auswahl zwischenspeichern (" + selectedCount + ")");
        cache.addActionListener(e -> cacheSelectedOrAll());
        menu.add(cache);
        JMenuItem rule = new JMenuItem("Indexieren (Regel anlegen)");
        rule.setEnabled(NdvAnalysisSettings.searchLink().available() && !indexLibraries().isEmpty());
        rule.addActionListener(e -> createSearchRule());
        menu.add(rule);
        menu.addSeparator();
        JMenuItem block = new JMenuItem("Sperren");
        block.addActionListener(e -> rules.blacklist(selectedPaths()));
        menu.add(block);
        JMenuItem allow = new JMenuItem("Freigeben");
        allow.addActionListener(e -> rules.whitelist(selectedPaths()));
        menu.add(allow);
        JMenuItem unfilter = new JMenuItem("Filter entfernen");
        unfilter.addActionListener(e -> rules.remove(selectedPaths()));
        menu.add(unfilter);
        return menu;
    }

    void selectAll() {
        if (groupByType) {
            objectTree.setSelectionInterval(0, objectTree.getRowCount() - 1);
        } else if (objectTable.getRowCount() > 0) {
            objectTable.setRowSelectionInterval(0, objectTable.getRowCount() - 1);
        }
    }

    void selectNone() {
        if (groupByType) {
            objectTree.clearSelection();
        } else {
            objectTable.clearSelection();
        }
    }

    /**
     * Tastatur wie {@code ListKeyboardNavigation} in MainframeMate: Pfeil links zurück, rechts vorwärts, oben an der
     * ersten Zeile weiter zur letzten und unten an der letzten weiter zur ersten.
     */
    private void installKeys(JComponent view, java.util.function.IntSupplier count,
                             java.util.function.IntSupplier selected, java.util.function.IntConsumer select,
                             java.util.function.IntConsumer scroll) {
        view.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int size = count.getAsInt();
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_LEFT -> {
                        if (view == objectTable || !(view instanceof NdvObjectTree)) {
                            navigateBack();
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_RIGHT -> {
                        if (view == objectTable) {
                            navigateForward();
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        if (size > 0 && selected.getAsInt() == 0) {
                            select.accept(size - 1);
                            scroll.accept(size - 1);
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_DOWN -> {
                        if (size > 0 && selected.getAsInt() == size - 1) {
                            select.accept(0);
                            scroll.accept(0);
                            event.consume();
                        }
                    }
                    default -> {
                        // Übrige Tasten wie gewohnt.
                    }
                }
            }
        });
    }

    /** Bibliotheksliste: Eingabe oder Pfeil rechts springt in die Objekte, oben/unten im Kreis. */
    private void installLibraryKeys() {
        libraryList.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int size = libraries.size();
                if (size == 0) {
                    return;
                }
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_ENTER, KeyEvent.VK_RIGHT -> {
                        if (libraryList.getSelectedValue() != null) {
                            if (!libraryList.getSelectedValue().equals(library)) {
                                openLibrary(libraryList.getSelectedValue());
                            }
                            activeView().requestFocusInWindow();
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        if (libraryList.getSelectedIndex() == 0) {
                            libraryList.setSelectedIndex(size - 1);
                            libraryList.ensureIndexIsVisible(size - 1);
                            event.consume();
                        }
                    }
                    case KeyEvent.VK_DOWN -> {
                        if (libraryList.getSelectedIndex() == size - 1) {
                            libraryList.setSelectedIndex(0);
                            libraryList.ensureIndexIsVisible(0);
                            event.consume();
                        }
                    }
                    default -> {
                        // Übrige Tasten wie gewohnt.
                    }
                }
            }
        });
        libraryFilter.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int size = libraries.size();
                if (size > 0 && (event.getKeyCode() == KeyEvent.VK_DOWN || event.getKeyCode() == KeyEvent.VK_UP)) {
                    int index = event.getKeyCode() == KeyEvent.VK_DOWN ? 0 : size - 1;
                    libraryList.setSelectedIndex(index);
                    libraryList.ensureIndexIsVisible(index);
                    libraryList.requestFocusInWindow();
                    event.consume();
                }
            }
        });
        objectFilter.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                int size = rowCount();
                if (size > 0 && (event.getKeyCode() == KeyEvent.VK_DOWN || event.getKeyCode() == KeyEvent.VK_UP)) {
                    selectRow(event.getKeyCode() == KeyEvent.VK_DOWN ? 0 : size - 1);
                    activeView().requestFocusInWindow();
                    event.consume();
                }
            }
        });
    }

    // ---- Filter, Ansicht, Sortierung -------------------------------------------------------------------------------

    /** Bereich des Namensfilters: die Bibliothek oder die Bibliotheksliste (wie {@code currentScope}). */
    private String scope() {
        return library == null ? NdvNavigatorState.SCOPE_LIBRARIES : library;
    }

    /** Artenfilter wie {@code createTypeFilterMenu}: Alle anzeigen, Auswahl umkehren, Häkchen je Art. */
    JPopupMenu typeMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem all = new JMenuItem("Alle anzeigen");
        all.addActionListener(e -> setHiddenTypes(Set.of()));
        menu.add(all);
        JMenuItem invert = new JMenuItem("Auswahl umkehren");
        invert.addActionListener(e -> {
            Set<Integer> inverted = new LinkedHashSet<>(NdvTypeGroups.ORDER);
            inverted.removeAll(hiddenTypes);
            setHiddenTypes(inverted);
        });
        menu.add(invert);
        menu.addSeparator();
        for (int typeId : NdvTypeGroups.ORDER) {
            JCheckBoxMenuItem item = new JCheckBoxMenuItem(NdvTypeGroups.groupName(typeId),
                    !hiddenTypes.contains(typeId));
            item.addActionListener(e -> hideType(typeId, !item.isSelected()));
            menu.add(item);
        }
        return menu;
    }

    void hideType(int typeId, boolean hidden) {
        Set<Integer> next = new LinkedHashSet<>(hiddenTypes);
        if (hidden) {
            next.add(typeId);
        } else {
            next.remove(typeId);
        }
        setHiddenTypes(next);
    }

    private void setHiddenTypes(Set<Integer> types) {
        hiddenTypes.clear();
        hiddenTypes.addAll(types);
        state.hiddenTypes(hiddenTypes);
        updateFilterButtons();
        filterObjects();
    }

    /**
     * Namens-Ausdruck wie {@code RegexNameFilter.buildPopupMenu}: aktueller Ausdruck, bearbeiten, zurücksetzen,
     * darunter die Präfixe (erste drei Buchstaben) zum Ankreuzen; das Menü bleibt beim Ankreuzen offen.
     */
    JPopupMenu nameFilterMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem info = new JMenuItem(nameFilter.regex().isEmpty() ? "(kein Ausdruck aktiv)"
                : "Aktuell: " + nameFilter.regex() + (nameFilter.invalid() ? " – ungültig, filtert nichts" : ""));
        info.setEnabled(false);
        menu.add(info);
        menu.addSeparator();
        JMenuItem edit = new JMenuItem("Ausdruck bearbeiten …");
        edit.addActionListener(e -> editNameFilter());
        menu.add(edit);
        JMenuItem reset = new JMenuItem("Filter zurücksetzen");
        reset.setEnabled(!nameFilter.regex().isEmpty());
        reset.addActionListener(e -> nameFilter.clear());
        menu.add(reset);
        menu.addSeparator();
        List<String> names = new ArrayList<>();
        if (library == null) {
            names.addAll(allLibraries);
        } else {
            objects.rows.forEach(object -> names.add(object.displayName()));
        }
        java.util.SortedSet<String> candidates = NdvNameFilter.candidates(names);
        if (candidates.isEmpty()) {
            JMenuItem empty = new JMenuItem("(keine Einträge verfügbar)");
            empty.setEnabled(false);
            menu.add(empty);
            return menu;
        }
        JMenuItem header = new JMenuItem("Präfixe (erste 3 Buchstaben) ankreuzen:");
        header.setEnabled(false);
        menu.add(header);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBackground(javax.swing.UIManager.getColor("MenuItem.background"));
        for (String prefix : candidates) {
            JCheckBox box = Comic.checkBox(prefix);
            box.setSelected(nameFilter.prefixes().contains(prefix));
            box.setFocusable(false);
            box.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 6));
            box.addActionListener(e -> nameFilter.toggle(prefix, box.isSelected()));
            list.add(box);
        }
        JScrollPane scroll = Comic.scroll(list);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getViewport().setBackground(list.getBackground());
        Dimension natural = list.getPreferredSize();
        scroll.setPreferredSize(new Dimension(Math.max(natural.width + 24, 140), Math.min(natural.height + 4, 360)));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        menu.add(scroll);
        return menu;
    }

    private void editNameFilter() {
        PlaceholderField field = new PlaceholderField(28, "z. B. ^LAG|^KAS");
        Comic.style(field);
        field.setText(nameFilter.regex());
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(Comic.wrapped("Regulärer Ausdruck für die Objektnamen dieser Bibliothek, ohne Rücksicht auf Groß- und "
                + "Kleinschreibung.", Comic.TEXT, 12.5f), BorderLayout.NORTH);
        body.add(new InputFrame(field), BorderLayout.CENTER);
        if (Dialogs.formOverlay(this, "Namens-Ausdruck", body, "Übernehmen")) {
            nameFilter.manual(field.getText());
        }
    }

    /** Ausdruck setzen ohne Dialog (für Tests und die Pfadleiste). */
    void nameFilter(String regex) {
        nameFilter.manual(regex);
    }

    void namePrefix(String prefix, boolean selected) {
        nameFilter.toggle(prefix, selected);
    }

    private void updateFilterButtons() {
        typeFilter.marked(!hiddenTypes.isEmpty());
        typeFilter.setToolTipText(hiddenTypes.isEmpty() ? "Arten ein- und ausblenden"
                : hiddenTypes.size() + " Arten ausgeblendet – ein- und ausblenden");
        boolean active = nameFilter.active();
        nameFilterButton.setText(active ? ".*•" : ".*");
        nameFilterButton.setToolTipText(active ? "Namens-Ausdruck aktiv: " + nameFilter.regex()
                : "Namens-Ausdruck: nur passende Objekte zeigen, je Bibliothek gemerkt");
    }

    private void filterLibraries() {
        Pattern pattern = glob(libraryFilter.getText());
        String selected = libraryList.getSelectedValue();
        libraries.clear();
        allLibraries.stream().filter(name -> pattern.matcher(name).find())
                .filter(name -> library != null || nameFilter.matches(name))
                .filter(name -> !hideBlocked || libraryAccessible(name))
                .forEach(libraries::addElement);
        if (selected != null && libraries.contains(selected)) {
            libraryList.setSelectedValue(selected, false);
        }
    }

    /** Ob ein Objekt durch Text-, Arten- und Namensfilter kommt (wie {@code applyFilter}). */
    private boolean visible(NdvObject object, Pattern pattern) {
        return !hiddenTypes.contains(object.type().id()) && nameFilter.matches(object.displayName())
                && (!hideBlocked || rules.isAllowed(NdvAccessRules.path(object.library(), object.displayName())))
                && (pattern.matcher(object.displayName()).find() || pattern.matcher(object.type().label()).find()
                || pattern.matcher(object.user()).find());
    }

    private void filterObjects() {
        Pattern pattern = glob(objectFilter.getText());
        sorter.setRowFilter(new RowFilter<>() {
            @Override
            public boolean include(Entry<? extends ObjectModel, ? extends Integer> entry) {
                return visible(objects.at(entry.getIdentifier()), pattern);
            }
        });
        if (library == null) {
            filterLibraries();
        }
        if (groupByType) {
            List<NdvObject> visible = new ArrayList<>(objects.rows.stream()
                    .filter(object -> visible(object, pattern)).toList());
            visible.sort(sortComparator());
            objectTree.show(visible);
        }
        viewCards.show(views, groupByType ? GROUPED : LIST);
        expandAll.setVisible(groupByType);
        collapseAll.setVisible(groupByType);
        showState();
    }

    /** Die Sortierung der Liste gilt auch innerhalb der Gruppen. */
    private Comparator<NdvObject> sortComparator() {
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        if (keys.isEmpty() || keys.getFirst().getSortOrder() == SortOrder.UNSORTED) {
            return NdvObjectTree.byName();
        }
        RowSorter.SortKey key = keys.getFirst();
        int column = key.getColumn();
        @SuppressWarnings("unchecked")
        Comparator<Object> values = (Comparator<Object>) sorter.getComparator(column);
        Comparator<NdvObject> comparator = (a, b) -> values.compare(objects.valueOf(a, column),
                objects.valueOf(b, column));
        comparator = comparator.thenComparing(NdvObjectTree.byName());
        return key.getSortOrder() == SortOrder.DESCENDING ? comparator.reversed() : comparator;
    }

    private void restoreSort() {
        String saved = state.sort();
        int colon = saved.indexOf(':');
        if (colon > 0) {
            try {
                int column = Integer.parseInt(saved.substring(0, colon));
                SortOrder order = SortOrder.valueOf(saved.substring(colon + 1));
                if (column >= 0 && column < objects.getColumnCount()) {
                    sorter.setSortKeys(List.of(new RowSorter.SortKey(column, order)));
                    return;
                }
            } catch (IllegalArgumentException ignored) {
                // Alter oder kaputter Wert: Vorgabe.
            }
        }
        sorter.setSortKeys(List.of(new RowSorter.SortKey(0, SortOrder.ASCENDING)));
    }

    private void saveSort() {
        List<? extends RowSorter.SortKey> keys = sorter.getSortKeys();
        state.sort(keys.isEmpty() ? "" : keys.getFirst().getColumn() + ":" + keys.getFirst().getSortOrder().name());
    }

    /** Sortiert wie über einen Klick auf den Spaltenkopf (für Tests). */
    void sortBy(int column, SortOrder order) {
        sorter.setSortKeys(List.of(new RowSorter.SortKey(column, order)));
    }

    private void applyDetails() {
        detailsToggle.marked(showDetails);
        javax.swing.table.TableColumnModel columns = objectTable.getColumnModel();
        for (TableColumn column : detailColumns) {
            boolean present = false;
            for (int i = 0; i < columns.getColumnCount(); i++) {
                present |= columns.getColumn(i) == column;
            }
            if (showDetails && !present) {
                columns.addColumn(column);
            } else if (!showDetails && present) {
                columns.removeColumn(column);
            }
        }
        objectTree.details(showDetails);
    }

    /** Blinkt dreimal gelb hinter einem Knopf (Erinnerung an einen aktiven Filter). */
    private static void flash(JComponent button) {
        Color original = button.getBackground();
        boolean opaque = button.isOpaque();
        int[] count = {0};
        javax.swing.Timer timer = new javax.swing.Timer(250, null);
        timer.addActionListener(e -> {
            if (count[0] >= 6) {
                button.setBackground(original);
                button.setOpaque(opaque);
                button.repaint();
                timer.stop();
                return;
            }
            boolean on = count[0] % 2 == 0;
            button.setOpaque(on || opaque);
            button.setBackground(on ? Comic.YELLOW : original);
            button.repaint();
            count[0]++;
        });
        timer.setInitialDelay(500);
        timer.start();
    }

    /** Teil eines Namens; {@code *} steht für beliebig viel, ohne Rücksicht auf Groß- und Kleinschreibung. */
    static Pattern glob(String text) {
        String trimmed = text == null ? "" : text.trim();
        if (trimmed.isEmpty()) {
            return Pattern.compile("");
        }
        return Pattern.compile(Pattern.quote(trimmed).replace("*", "\\E.*\\Q"), Pattern.CASE_INSENSITIVE);
    }

    private void note(String text, boolean error) {
        note = text;
        noteIsError = error;
        showState();
    }

    void showState() {
        NdvState current = session.state();
        boolean connected = current == NdvState.CONNECTED;
        open.setEnabled(connected && leadObject() != null);
        EditorSession.State documentState = document == null ? null : document.state();
        save.setEnabled(connected && document != null && document.dirty()
                && (documentState == EditorSession.State.READY || documentState == EditorSession.State.CONFLICT));
        reconnect.setVisible(current == NdvState.FAILED || current == NdvState.DISCONNECTED);
        String head = session.profile().label() + (session.user().isEmpty() ? "" : " · " + session.user()) + " · "
                + current.label();
        String filtered = "";
        if (library != null && !groupByType && objectTable.getRowCount() != objects.getRowCount()) {
            filtered = " · " + objectTable.getRowCount() + " passen zum Filter";
        }
        String text = switch (current) {
            case FAILED -> head + session.failure().map(reason -> " – " + oneLine(reason)).orElse("");
            case CONNECTED -> note.isEmpty() ? head : head + " · " + note + filtered;
            default -> head;
        };
        boolean error = current == NdvState.FAILED || connected && noteIsError;
        statusNote = new StatusNote(text, error ? StatusNote.Tone.ERROR : StatusNote.Tone.PLAIN, null, null);
        statusChanged.run();
    }

    /** Der Stand der Sitzung für die Statuszeile der App. */
    StatusNote statusNote() {
        return statusNote;
    }

    /** Meldet jede Änderung des Stands (auf dem EDT); ein Beobachter je Sitzung, der Bereich NDV. */
    void onStatusChanged(Runnable listener) {
        statusChanged = listener == null ? () -> { } : listener;
    }

    private <T> void work(java.util.concurrent.Callable<T> task, java.util.function.Consumer<T> done,
                          java.util.function.Consumer<Throwable> failed) {
        background.execute(() -> {
            try {
                T result = task.call();
                SwingUtilities.invokeLater(() -> done.accept(result));
            } catch (Throwable e) {
                SwingUtilities.invokeLater(() -> failed.accept(e));
            }
        });
    }

    private static javax.swing.AbstractAction action(Runnable run) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                run.run();
            }
        };
    }

    private static javax.swing.event.DocumentListener changes(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                action.run();
            }
        };
    }

    /**
     * Eine mehrzeilige Meldung (Ursache, Leerzeile, „Mögliche Ursachen:“ mit Aufzählung) als eine Zeile für die
     * Statuszeile: Zeilen mit „ · “ getrennt, nach einem Doppelpunkt nur ein Leerzeichen. JLabel zeigt Umbrüche
     * nicht an, ohne Trenner liefen die Zeilen zusammen („…:2700Mögliche Ursachen:•“).
     */
    static String oneLine(String message) {
        StringBuilder line = new StringBuilder();
        for (String part : message.split("\\R")) {
            String text = part.strip();
            if (text.startsWith("•")) {
                text = text.substring(1).strip();
            }
            if (text.isEmpty()) {
                continue;
            }
            if (!line.isEmpty()) {
                line.append(line.charAt(line.length() - 1) == ':' ? " " : " · ");
            }
            line.append(text);
        }
        return line.toString();
    }

    // Für Tests
    String statusText() {
        return statusNote.text();
    }

    List<String> visibleLibraries() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < libraries.size(); i++) {
            list.add(libraries.get(i));
        }
        return list;
    }

    List<String> visibleObjects() {
        List<String> list = new ArrayList<>();
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            list.add(objects.at(objectTable.convertRowIndexToModel(i)).displayName());
        }
        return list;
    }

    void selectObject(String name) {
        if (groupByType) {
            for (NdvObject object : objects.rows) {
                if (object.displayName().equals(name)) {
                    objectTree.select(object);
                    return;
                }
            }
            throw new IllegalArgumentException(name);
        }
        for (int i = 0; i < objectTable.getRowCount(); i++) {
            if (objects.at(objectTable.convertRowIndexToModel(i)).displayName().equals(name)) {
                objectTable.setRowSelectionInterval(i, i);
                return;
            }
        }
        throw new IllegalArgumentException(name);
    }

    String sourceText() {
        DocumentEditor editor = document == null ? null : editorOf.get(document);
        return editor == null ? "" : editor.text();
    }

    DocumentEditor editor() {
        return document == null ? null : editorOf.get(document);
    }

    EditorSession document() {
        return document;
    }

    boolean saveEnabled() {
        return save.isEnabled();
    }

    NdvObject shown() {
        return shown;
    }

    String library() {
        return library;
    }

    PlaceholderField objectFilter() {
        return objectFilter;
    }

    PlaceholderField libraryFilter() {
        return libraryFilter;
    }

    PlaceholderField pathField() {
        return pathField;
    }

    String sourceDetailsText() {
        return sourceDetails.isVisible() ? sourceDetails.getText() : "";
    }

    NdvObjectTree tree() {
        return objectTree;
    }

    SegmentedToggle viewToggle() {
        return viewToggle;
    }

    IconButton detailsToggle() {
        return detailsToggle;
    }

    IconButton typeFilterButton() {
        return typeFilter;
    }

    IconButton bookmarkStar() {
        return bookmarkStar;
    }

    boolean canBack() {
        return history.canBack() || library != null;
    }

    boolean canForward() {
        return history.canForward();
    }

    int visibleColumns() {
        return objectTable.getColumnCount();
    }

    boolean objectsLoading() {
        return objectBusy.isBusy();
    }

    /** Die Objekte als Tabelle: Name, Art, Größe, Geändert, Benutzer und mit Details Modus, DBID, FNR. */
    static final class ObjectModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Art", "Größe", "Geändert", "Benutzer", "Modus", "DBID",
                "FNR"};
        private final List<NdvObject> rows = new ArrayList<>();

        void clear() {
            int size = rows.size();
            rows.clear();
            if (size > 0) {
                fireTableRowsDeleted(0, size - 1);
            }
        }

        void add(List<NdvObject> page) {
            if (page.isEmpty()) {
                return;
            }
            int first = rows.size();
            rows.addAll(page);
            fireTableRowsInserted(first, rows.size() - 1);
        }

        NdvObject at(int row) {
            return rows.get(row);
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
        public Class<?> getColumnClass(int column) {
            return column == 2 || column == 6 || column == 7 ? Integer.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            return valueOf(rows.get(row), column);
        }

        Object valueOf(NdvObject object, int column) {
            return switch (column) {
                case 0 -> object.displayName();
                case 1 -> object.type().label();
                case 2 -> object.sourceSize();
                case 3 -> object.changed();
                case 4 -> object.user().toUpperCase(Locale.ROOT);
                case 5 -> object.details().mode();
                case 6 -> object.location().databaseId();
                default -> object.location().fileNumber();
            };
        }
    }
}
