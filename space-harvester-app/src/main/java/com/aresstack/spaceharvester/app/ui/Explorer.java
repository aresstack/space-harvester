package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.knowledge.StatementAnalysisJobs;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.query.PageSearchQuery;
import com.aresstack.spaceharvester.application.query.SearchHit;
import com.aresstack.spaceharvester.application.query.SearchSort;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.sources.SourceAccessException;
import com.aresstack.spaceharvester.application.tree.PageTree;
import com.aresstack.spaceharvester.application.tree.PageTreeBuilder;
import com.aresstack.spaceharvester.application.tree.TreeEntry;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.Set;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Der dauerhafte Explorer links im Hauptfenster: ein Baum über alle Wissensquellen, Wissensquelle → Space → Seite →
 * Unterseite. Spaces und Seiten lädt er erst beim Aufklappen, Kindseiten live über die Navigation des Core
 * ({@code PageNavigation.children}, #177, in der Reihenfolge der Quelle, stapelweise mit „Weitere laden …“); Quellen
 * ohne Seitenbaum zeigen den Seitenbaum des Space aus dem Bestand bzw. aus den Metadaten der Quelle. Ein Klick (oder
 * Enter) auf eine Seite öffnet sie unter Inhalte; eine gewählte Quelle oder ein Space wird als Umfang gemeldet. Oben
 * filtert das Feld „Titel filtern …“ nur zur Navigation, nicht zur Suche: Treffer aus den schon geladenen Knoten und aus
 * dem Bestand (Titelsuche des Core).
 */
public final class Explorer {

    static final String LOADING = "Lädt …";
    static final String MORE = "Weitere laden …";
    private static final String TREE = "tree";
    private static final String MATCHES = "matches";
    private static final String FAVORITES = "favorites";
    static final String FAVORITE = "Favorit";
    static final String UNFAVORITE = "Favorit entfernen";
    static final String ONLY_FAVORITES = "Nur Favoriten zeigen";
    static final String ALL_NODES = "Alle Knoten zeigen";
    private static final int MATCH_LIMIT = 50;
    private static final int TREE_LIMIT = 5_000;
    private static final int BATCH = 100;
    private static final int ACTION_WIDTH = 30;
    /** Spalte des Bestandszeichens links vom Stern. */
    private static final int BADGE_WIDTH = 22;
    static final String DOWNLOAD_TIP = "Herunterladen: Auftrag startet sofort, exportiert wird unter Aufträge";
    static final String PAGE_ONLY = "Nur diese Seite herunterladen";
    static final String PAGE_ONLY_TIP = "Nur diese Seite ohne Unterseiten; Auftrag startet sofort";
    static final String ANALYZE = "Analysieren";
    static final String ANALYZE_TIP = "Aussagen lokal mit MaltParser analysieren; mehrere Seiten erst nach Bestätigung,"
            + " Fortschritt unter Aufträge";
    static final String ANALYZE_PAGE = "Nur diese Seite analysieren";
    static final String ANALYZE_PAGE_TIP = "Nur diese Seite ohne Unterseiten analysieren; Auftrag startet sofort";
    static final String ANALYZE_UNAVAILABLE = "Nicht verfügbar: In dieser Installation ist kein Satzanalysator "
            + "(MaltParser-Modell) eingerichtet";
    static final String ANALYZE_NOT_STARTED = "Nicht verfügbar: Die Laufzeit ist nicht gestartet";
    static final String REANALYZE = "Bereits analysierte Seiten erneut analysieren";
    /** Sicherheitsgrenze: bis hierhin zählt die Schätzung, darüber fragt der Dialog ausdrücklich nach allen Seiten. */
    static final int ANALYSIS_LIMIT = 500;
    private static final int NOTICE_MS = 8000;

    /** Umfang aus der Auswahl: eine Quelle, ein Space oder nichts (alle). */
    public record Scope(String sourceId, String spaceKey) {
        public static final Scope ALL = new Scope(null, null);
    }

    /** Meldet jede Änderung der Auswahl als Quelle, Space oder Seite (für den Reiter „Export“); {@code null} ohne. */
    public void onSelection(Consumer<com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection> listener) {
        this.selectionListener = Objects.requireNonNull(listener, "listener");
    }

    /** Die erste gewählte Zeile als Quelle, Space oder Seite; {@code null} ohne Auswahl oder auf einem Hinweis. */
    public com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection selection() {
        TreePath[] paths = tree.getSelectionPaths();
        for (TreePath path : paths == null ? new TreePath[0] : paths) {
            if (path.getLastPathComponent() instanceof Node node) {
                var selection = switch (node.item()) {
                    case SourceItem source -> new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection(
                            source.sourceId(), null, null, source.title());
                    case SpaceItem space -> new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection(
                            space.sourceId(), space.spaceKey(), null, space.name());
                    case PageItem page -> new com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection(
                            page.sourceId(), page.spaceKey(), page.pageId(), page.title());
                    default -> null;
                };
                if (selection != null) {
                    return selection;
                }
            }
        }
        return null;
    }

    sealed interface Item permits SourceItem, SpaceItem, PageItem, MoreItem, Note {
    }

    record SourceItem(String sourceId, String title) implements Item {
        @Override
        public String toString() {
            return title;
        }
    }

    record SpaceItem(String sourceId, String spaceKey, String name) implements Item {
        @Override
        public String toString() {
            return name.equals(spaceKey) ? spaceKey : name + " (" + spaceKey + ")";
        }
    }

    record PageItem(String sourceId, String spaceKey, String pageId, String title, Boolean hasChildren)
            implements Item {
        @Override
        public String toString() {
            return title;
        }
    }

    /** Platzhalter für den nächsten Stapel Kindseiten. */
    record MoreItem(String cursor) implements Item {
        @Override
        public String toString() {
            return MORE;
        }
    }

    /** „Lädt …“, „Keine Seiten“ oder eine Fehlermeldung. */
    record Note(String text) implements Item {
        @Override
        public String toString() {
            return text;
        }
    }

    /** Ein Treffer des Titelfilters. */
    record Match(String sourceId, String spaceKey, String pageId, String title, String where) {
        @Override
        public String toString() {
            return title + "  ·  " + where;
        }
    }

    /** Knoten mit Ladezustand; Kinder kommen erst beim Aufklappen. */
    static final class Node extends DefaultMutableTreeNode {
        boolean loaded;
        boolean loading;
        /** Läuft einmal, sobald die Kinder geladen sind (siehe {@link #reveal}). */
        Runnable then;

        Node(Item item) {
            super(item);
        }

        Item item() {
            return (Item) getUserObject();
        }
    }

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout(0, 6));
    private final PlaceholderField filter = new PlaceholderField(14, "Titel filtern …");
    private final Node top = new Node(new Note("Wissensquellen"));
    private final DefaultTreeModel model = new DefaultTreeModel(top);
    private final JTree tree = new JTree(model);
    /** Die Bestandszeichen je Zeile (Angelo, 13.11.2026): aus dem Core gemeldet, nie abgefragt. */
    private final TreeStates states;
    private final DefaultListModel<Match> matchModel = new DefaultListModel<>();
    private final JList<Match> matches = new JList<>(matchModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel note = Comic.muted(" ");
    private final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alles zuklappen");
    private final IconButton showCurrent = new IconButton(Glyph.CROSSHAIR, "Aktuelles Dokument im Baum zeigen");
    /** Unten rechts (Angelo, 13.11.2026): Umriss = alle Knoten, gefüllt = nur die Favoriten als Liste mit Pfad. */
    private final IconButton favoritesOnly = new IconButton(Glyph.STAR, ONLY_FAVORITES);
    private boolean onlyFavorites;
    private final DefaultListModel<Favorites.Favorite> favoriteModel = new DefaultListModel<>();
    private final JList<Favorites.Favorite> favorites = new JList<>(favoriteModel);
    /** Die zuletzt in der Leseansicht gezeigte Seite; Ziel von „Aktuelles Dokument im Baum zeigen“. */
    private PageNavigation.Position current;
    private final Timer debounce = new Timer(300, e -> runFilter());
    private List<String> shownSources = List.of();
    private Consumer<List<Scope>> scopeListener = scopes -> { };
    private Consumer<com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection> selectionListener =
            selection -> { };
    private int filterRequest;
    /** Während {@link #revealPage} wählt der Baum selbst; der Suchumfang bleibt dann, wie er ist. */
    private boolean revealing;
    /** Zähler der Reveal-Anfragen: Suchtreffer A, dann B – A darf B nicht mehr überschreiben. */
    private int revealRequest;
    /** Zeile, über deren Download-Symbol die Maus steht; -1 sonst. */
    private int hoverRow = -1;
    /** „Analysieren“ über den Core ({@code Workspace}); Tests setzen eine eigene Umsetzung. */
    private Analyses analyses = new WorkspaceAnalyses();
    /** Bestätigung großer Analyseumfänge; Tests antworten ohne Dialog. */
    private AnalysisConfirmation confirmation = (parent, title, text, action) ->
            Dialogs.confirm(parent, title, text, REANALYZE, action);

    /** Was „Analysieren“ vom Core braucht. */
    interface Analyses {
        /** {@code null}, wenn Analysieren möglich ist, sonst der Grund für den Hinweis am Menüeintrag. */
        String unavailable();

        StatementAnalysisJobs.Estimate estimate(StatementAnalysisJobs.Scope scope, int limit);

        JobStatus start(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options);
    }

    /** Fragt vor einem großen Analyseauftrag; {@code null} heißt abgebrochen, sonst „erneut analysieren“. */
    interface AnalysisConfirmation {
        Boolean ask(java.awt.Component parent, String title, String text, String action);
    }

    private final class WorkspaceAnalyses implements Analyses {
        @Override
        public String unavailable() {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                return ANALYZE_NOT_STARTED;
            }
            try {
                return runtime.workspace().statementAnalysisJobsAvailable() ? null : ANALYZE_UNAVAILABLE;
            } catch (RuntimeException e) {
                return ANALYZE_UNAVAILABLE;
            }
        }

        @Override
        public StatementAnalysisJobs.Estimate estimate(StatementAnalysisJobs.Scope scope, int limit) {
            return runtime().workspace().estimateStatementAnalysis(scope, limit);
        }

        @Override
        public JobStatus start(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
            return runtime().workspace().startStatementAnalysis(scope, options);
        }

        private AppRuntime runtime() {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException("Die Laufzeit ist nicht gestartet");
            }
            return runtime;
        }
    }

    public Explorer(AppContext context) {
        this.context = context;
        this.states = new TreeStates(context, tree::repaint);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0);
        tree.setOpaque(false);
        tree.setRowHeight(0);
        tree.setFont(Comic.regular(13f));
        tree.getAccessibleContext().setAccessibleName("Wissensquellen");
        tree.setCellRenderer(new Renderer());
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof Node node) {
                    load(node);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.getSelectionModel().setSelectionMode(
                javax.swing.tree.TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION);
        tree.addTreeSelectionListener(e -> {
            if (!revealing) {
                scopeListener.accept(scopes());
            }
            // Der Export folgt auch einer Auswahl, die ein geöffneter Treffer oder Auftrag gesetzt hat.
            selectionListener.accept(selection());
        });
        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path == null || !javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                // Ein Klick wählt nur (Download-Symbol und „Weitere laden“ ausgenommen), erst Doppelklick öffnet.
                boolean single = event.getClickCount() == 1;
                if (onAction(path, event.getPoint())) {
                    if (single) {
                        download(((Node) path.getLastPathComponent()).item());
                    }
                } else if (onStar(path, event.getPoint())) {
                    if (single) {
                        toggleFavorite(((Node) path.getLastPathComponent()).item());
                    }
                } else if (path.getLastPathComponent() instanceof Node node && node.item() instanceof MoreItem) {
                    if (single) {
                        activate(path);
                    }
                } else if (event.getClickCount() == 2) {
                    activate(path);
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    popup(event);
                }
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (event.isPopupTrigger()) {
                    popup(event);
                }
            }

            @Override
            public void mouseMoved(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                int row = onAction(path, event.getPoint()) ? tree.getRowForPath(path) : -1;
                tree.setToolTipText(row >= 0 ? DOWNLOAD_TIP : onBadge(path, event.getPoint()) ? badgeTip(path) : null);
                if (row != hoverRow) {
                    hoverRow = row;
                    tree.repaint();
                }
            }

            @Override
            public void mouseExited(MouseEvent event) {
                if (hoverRow >= 0) {
                    hoverRow = -1;
                    tree.repaint();
                }
            }
        };
        tree.addMouseListener(mouse);
        tree.addMouseMotionListener(mouse);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        bindEnter(tree, () -> {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                activate(path);
            }
        });
        matches.setOpaque(false);
        matches.setFont(Comic.regular(13f));
        matches.setSelectionBackground(Comic.YELLOW);
        matches.setSelectionForeground(Comic.INK);
        matches.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Match match) {
                    setText("<html>" + escape(match.title()) + "<br><font color='#7a7a7a'>" + escape(match.where())
                            + "</font></html>");
                    setIcon(SidebarIcons.page());
                }
                setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
                setOpaque(selected);
                return this;
            }
        });
        matches.getAccessibleContext().setAccessibleName("Gefundene Seiten");
        matches.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                openMatch();
            }
        });
        bindEnter(matches, this::openMatch);
        debounce.setRepeats(false);
        filter.setToolTipText("Seitentitel in den geladenen Knoten und im Bestand finden; dient nur der "
                + "Navigation, gesucht wird im Bereich Suche.");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                debounce.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                debounce.restart();
            }
        });
        bindKey(filter, KeyEvent.VK_ESCAPE, () -> filter.setText(""));

        JScrollPane treeScroll = Comic.scroll(tree);
        treeScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        // Zeilen gehen mindestens über die volle Breite (Download-Symbol rechtsbündig); breitere Zeilen scrollen
        // waagerecht. Bei neuer Breite neu vermessen.
        treeScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                tree.setCellRenderer(new Renderer());
            }
        });
        JScrollPane matchScroll = Comic.scroll(matches);
        deck.setOpaque(false);
        deck.add(treeScroll, TREE);
        deck.add(matchScroll, MATCHES);
        favorites.setFont(Comic.regular(13f));
        favorites.setOpaque(false);
        favorites.setSelectionBackground(Comic.YELLOW);
        favorites.setSelectionForeground(Comic.INK);
        favorites.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public java.awt.Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                                   boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof Favorites.Favorite favorite) {
                    // Der Favorit selbst in Tinte, seine Eltern als Pfad gedämpft darunter.
                    setText("<html>" + escape(favorite.title()) + "<br><font color='#7a7a7a'>"
                            + escape(favorite.where()) + "</font></html>");
                    setIcon(favorite.pageId() == null ? SidebarIcons.folder() : SidebarIcons.page());
                }
                setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
                setOpaque(selected);
                return this;
            }
        });
        favorites.getAccessibleContext().setAccessibleName("Favoriten");
        favorites.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (javax.swing.SwingUtilities.isLeftMouseButton(event)) {
                    openFavorite();
                }
            }
        });
        bindEnter(favorites, this::openFavorite);
        javax.swing.JScrollPane favoriteScroll = Comic.scroll(favorites);
        favoriteScroll.setBorder(BorderFactory.createEmptyBorder());
        deck.add(favoriteScroll, FAVORITES);
        context.favorites().onChange(() -> javax.swing.SwingUtilities.invokeLater(this::favoritesChanged));
        root.setOpaque(false);
        // Kopf wie in der Quellenverwaltung: Comic-Überschrift, darunter das Filterfeld.
        JPanel head = new JPanel(new BorderLayout(0, 6));
        head.setOpaque(false);
        head.add(Comic.caption("Wissensquellen"), BorderLayout.NORTH);
        head.add(com.aresstack.spaceharvester.app.ui.controls.InputFrame.search(filter), BorderLayout.CENTER);
        root.add(head, BorderLayout.NORTH);
        root.add(deck, BorderLayout.CENTER);
        collapseAll.addActionListener(e -> collapseAll());
        showCurrent.setEnabled(false);
        showCurrent.addActionListener(e -> showCurrent());
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(collapseAll);
        buttons.add(showCurrent);
        foot.add(buttons, BorderLayout.WEST);
        // Hinweise nur beim Titelfilter und bei Fehlern; die Anzahl der Wissensquellen sieht man im Baum.
        note.setBorder(BorderFactory.createEmptyBorder(2, 6, 0, 0));
        foot.add(note, BorderLayout.CENTER);
        favoritesOnly.addActionListener(e -> showOnlyFavorites(!onlyFavorites));
        foot.add(favoritesOnly, BorderLayout.EAST);
        root.add(foot, BorderLayout.SOUTH);
        context.onChange(ignored -> refreshSources());
        refreshSources();
    }

    public JComponent component() {
        return root;
    }

    /** Meldet jede Änderung der Auswahl als Umfang (für die Suche); leer heißt alle. */
    public void onScope(Consumer<List<Scope>> listener) {
        this.scopeListener = Objects.requireNonNull(listener, "listener");
    }

    /** Merkt sich die in der Leseansicht gezeigte Seite (ohne den Baum zu ändern). */
    public void currentPage(PageNavigation.Position position) {
        this.current = position;
        showCurrent.setEnabled(position != null);
    }

    /** Klappt alle Knoten zu; die Wissensquellen bleiben sichtbar. */
    void collapseAll() {
        for (int row = tree.getRowCount() - 1; row >= 0; row--) {
            tree.collapseRow(row);
        }
    }

    /** Wie das Fadenkreuz in IntelliJ: nur den Pfad zur gezeigten Seite aufklappen und sie wählen, ohne zu öffnen. */
    void showCurrent() {
        if (current == null) {
            return;
        }
        cards.show(deck, TREE);
        // Beim Zuklappen wandert die Auswahl kurz zur Quelle; das darf den Suchumfang nicht verbreitern.
        revealing = true;
        try {
            collapseAll();
        } finally {
            revealing = false;
        }
        revealPage(current);
    }

    /** Hebt die Auswahl auf; der Umfang ist dann wieder alles. */
    public void clearSelection() {
        tree.clearSelection();
    }

    /** Zeigt einen Space im Baum: Quelle aufklappen, Space wählen und aufklappen; unbekannte bleiben ohne Wirkung. */
    public void reveal(String sourceId, String spaceKey) {
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(sourceId));
        if (source == null) {
            return;
        }
        Runnable select = () -> {
            Node space = child(source, item -> item instanceof SpaceItem s && s.spaceKey().equals(spaceKey));
            TreePath path = new TreePath((space == null ? source : space).getPath());
            tree.setSelectionPath(path);
            tree.expandPath(path);
            tree.scrollPathToVisible(path);
        };
        if (source.loaded) {
            tree.expandPath(new TreePath(source.getPath()));
            select.run();
        } else {
            source.then = select;
            tree.expandPath(new TreePath(source.getPath()));
        }
    }

    /**
     * Zeigt eine geöffnete Seite im Baum: Quelle, Space und Vorfahren aufklappen (Kinder werden bei Bedarf geladen) und
     * die Seite wählen. Fehlt ein Schritt im Baum (etwa auf einem noch nicht geladenen Stapel), bleibt der tiefste
     * gefundene Knoten gewählt. Der Suchumfang ändert sich dadurch nicht.
     */
    public void revealPage(PageNavigation.Position position) {
        reveal(position, ++revealRequest);
    }

    /** {@code ticket}: nur die jüngste Anfrage wählt; eine ältere, deren Kinder später laden, verfällt. */
    private void reveal(PageNavigation.Position position, int ticket) {
        if (position == null || position.page() == null) {
            return;
        }
        Node source = child(top, item -> item instanceof SourceItem s && s.sourceId().equals(position.sourceId()));
        if (source == null) {
            return;
        }
        List<java.util.function.Predicate<Item>> steps = new ArrayList<>();
        steps.add(item -> item instanceof SpaceItem s && s.spaceKey().equals(position.spaceKey()));
        for (HarvestSource.PageNode ancestor : position.ancestors()) {
            steps.add(item -> item instanceof PageItem p && p.pageId().equals(ancestor.pageId()));
        }
        String pageId = position.page().pageId();
        steps.add(item -> item instanceof PageItem p && p.pageId().equals(pageId));
        descend(source, steps, 0, ticket);
    }

    /** Wie {@link #revealPage(PageNavigation.Position)}; den Pfad der Seite holt der Core im Hintergrund. */
    public void revealPage(String sourceId, String pageId) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || sourceId == null || pageId == null) {
            return;
        }
        int ticket = ++revealRequest;
        Background.run(() -> runtime.workspace().position(sourceId, pageId), position -> {
            if (ticket == revealRequest) {
                reveal(position, ticket);
            }
        }, error -> {
            // Quellen ohne Seitenbaum: nichts zu zeigen.
        });
    }

    private void descend(Node node, List<java.util.function.Predicate<Item>> steps, int index, int ticket) {
        Runnable next = () -> step(node, steps, index, ticket);
        TreePath path = new TreePath(node.getPath());
        if (node.loaded) {
            tree.expandPath(path);
            next.run();
        } else {
            node.then = next;
            tree.expandPath(path);
        }
    }

    /** Ein Schritt des Abstiegs; liegt das Kind in einem noch nicht geladenen Stapel, lädt er ihn nach und sucht erneut. */
    private void step(Node node, List<java.util.function.Predicate<Item>> steps, int index, int ticket) {
        {
            if (ticket != revealRequest) {
                return;
            }
            Node child = index < steps.size() ? child(node, steps.get(index)) : null;
            if (child == null) {
                Node moreNode = child(node, item -> item instanceof MoreItem);
                if (moreNode != null && moreNode.item() instanceof MoreItem more) {
                    loadMore(node, moreNode, more, () -> step(node, steps, index, ticket));
                    return;
                }
            }
            if (child == null && index < steps.size() - 1 && node.item() instanceof SpaceItem) {
                // Vorfahren, die der Baum nicht als Wurzel zeigt: die Seite direkt unter dem Space suchen.
                child = child(node, steps.get(steps.size() - 1));
                if (child != null) {
                    select(child);
                    return;
                }
            }
            if (child == null || index == steps.size() - 1) {
                select(child == null ? node : child);
            } else {
                descend(child, steps, index + 1, ticket);
            }
        }
    }

    private void select(Node node) {
        TreePath path = new TreePath(node.getPath());
        revealing = true;
        try {
            tree.setSelectionPath(path);
        } finally {
            revealing = false;
        }
        tree.scrollPathToVisible(path);
    }

    private static Node child(Node parent, java.util.function.Predicate<Item> match) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            if (parent.getChildAt(i) instanceof Node node && match.test(node.item())) {
                return node;
            }
        }
        return null;
    }

    /** Umfang der ersten gewählten Zeile: Quelle, Space oder der Space einer Seite; ohne Auswahl alle. */
    public Scope scope() {
        List<Scope> scopes = scopes();
        return scopes.isEmpty() ? Scope.ALL : scopes.get(0);
    }

    /** Umfang aller gewählten Zeilen (Strg-Klick), ohne Doppelte; leer ohne Auswahl. */
    public List<Scope> scopes() {
        TreePath[] paths = tree.getSelectionPaths();
        List<Scope> scopes = new ArrayList<>();
        for (TreePath path : paths == null ? new TreePath[0] : paths) {
            if (path.getLastPathComponent() instanceof Node node) {
                Scope scope = switch (node.item()) {
                    case SourceItem source -> new Scope(source.sourceId(), null);
                    case SpaceItem space -> new Scope(space.sourceId(), space.spaceKey());
                    case PageItem page -> new Scope(page.sourceId(), page.spaceKey());
                    default -> null;
                };
                if (scope != null && !scopes.contains(scope)) {
                    scopes.add(scope);
                }
            }
        }
        return scopes;
    }

    // ------------------------------------------------------------------ Baum

    /** Die Wurzelknoten: alle Quellen der Laufzeit, in deren Reihenfolge; unverändert, solange dieselben bleiben. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources();
        List<String> ids = sources.stream().map(HarvestSource::id).toList();
        // Vor dem Vergleich: eine neue Laufzeit mit denselben Quellen meldet die Zeichen neu an.
        states.watch(ids);
        if (ids.equals(shownSources) && top.getChildCount() > 0) {
            return;
        }
        shownSources = ids;
        top.removeAllChildren();
        for (HarvestSource source : sources) {
            top.add(expandable(new SourceItem(source.id(), source.title())));
        }
        if (sources.isEmpty()) {
            top.add(new Node(new Note(runtime == null ? "Startet …" : "Keine Wissensquelle")));
        }
        model.reload();
        note.setText(" ");
    }

    private static Node expandable(Item item) {
        Node node = new Node(item);
        node.add(new Node(new Note(LOADING)));
        return node;
    }

    /** Lädt die Kinder eines Knotens beim ersten Aufklappen; nach einem Fehler beim nächsten erneut. */
    private void load(Node node) {
        if (node.loaded || node.loading) {
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        node.loading = true;
        Item item = node.item();
        if (item instanceof SpaceItem space) {
            states.open(space.sourceId(), space.spaceKey());
        } else if (item instanceof PageItem page) {
            states.open(page.sourceId(), page.spaceKey());
        }
        Background.run(() -> children(runtime, item, null), batch -> {
            node.loading = false;
            node.loaded = true;
            node.removeAllChildren();
            append(node, batch);
            model.nodeStructureChanged(node);
            Runnable then = node.then;
            node.then = null;
            if (then != null) {
                then.run();
            }
        }, error -> {
            node.loading = false;
            node.then = null;
            node.removeAllChildren();
            node.add(new Node(new Note("Nicht geladen: " + Problems.describe(error).title())));
            model.nodeStructureChanged(node);
        });
    }

    /** Ein Klick: Seite öffnen bzw. den nächsten Stapel holen. */
    private void activate(TreePath path) {
        if (!(path.getLastPathComponent() instanceof Node node)) {
            return;
        }
        switch (node.item()) {
            case PageItem page -> context.navigator().openPage(page.sourceId(), page.pageId());
            case MoreItem more -> loadMore((Node) node.getParent(), node, more, null);
            default -> {
            }
        }
    }

    /** Lädt den nächsten Stapel Kinder von {@code parent}; {@code then} läuft danach auf dem EDT (etwa der Abstieg). */
    private void loadMore(Node parent, Node moreNode, MoreItem more, Runnable then) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || parent == null || parent.loading) {
            return;
        }
        parent.loading = true;
        moreNode.setUserObject(new Note(LOADING));
        model.nodeChanged(moreNode);
        Background.run(() -> children(runtime, parent.item(), more.cursor()), batch -> {
            parent.loading = false;
            // Nur einfügen und entfernen, nicht den Knoten neu aufbauen: das ließe die Auswahl darunter (und damit den
            // Suchbereich) fallen.
            model.removeNodeFromParent(moreNode);
            int first = parent.getChildCount();
            append(parent, batch);
            int[] added = new int[parent.getChildCount() - first];
            for (int i = 0; i < added.length; i++) {
                added[i] = first + i;
            }
            model.nodesWereInserted(parent, added);
            if (then != null) {
                then.run();
            }
        }, error -> {
            parent.loading = false;
            moreNode.setUserObject(more);
            model.nodeChanged(moreNode);
            note.setText("Nicht geladen: " + Problems.describe(error).title());
        });
    }

    private static void append(Node parent, Batch batch) {
        for (Node child : batch.nodes()) {
            parent.add(child);
        }
        if (batch.nextCursor() != null) {
            parent.add(new Node(new MoreItem(batch.nextCursor())));
        }
        if (parent.getChildCount() == 0) {
            Node empty = new Node(new Note(parent.item() instanceof SourceItem ? "Keine Spaces" : "Keine Seiten"));
            empty.loaded = true;
            parent.add(empty);
        }
    }

    /** Ein Stapel Kinder; im Hintergrund. */
    record Batch(List<Node> nodes, String nextCursor) {
    }

    static Batch children(AppRuntime runtime, Item item, String cursor) {
        return switch (item) {
            case SourceItem source -> new Batch(runtime.workspace().spaces(source.sourceId()).stream()
                    .sorted(Comparator.comparing((Partition p) -> p.name().toLowerCase(Locale.ROOT)))
                    .map(p -> expandable(new SpaceItem(source.sourceId(), p.key(), p.name()))).toList(), null);
            case SpaceItem space -> pages(runtime, space.sourceId(), space.spaceKey(), null, cursor);
            case PageItem page -> pages(runtime, page.sourceId(), page.spaceKey(), page.pageId(), cursor);
            default -> new Batch(List.of(), null);
        };
    }

    private static Batch pages(AppRuntime runtime, String sourceId, String spaceKey, String parentPageId,
                               String cursor) {
        PageNavigation.Children children;
        try {
            children = runtime.workspace().children(sourceId, spaceKey, parentPageId, cursor);
        } catch (SourceAccessException e) {
            if (e.reason() == SourceAccessException.Reason.UNSUPPORTED && parentPageId == null) {
                return wholeSpace(runtime, sourceId, spaceKey);
            }
            throw e;
        }
        List<Node> nodes = new ArrayList<>();
        for (HarvestSource.PageNode page : children.pages()) {
            PageItem item = new PageItem(sourceId, page.partitionKey() == null ? children.spaceKey()
                    : page.partitionKey(), page.pageId(), page.title(), page.hasChildren());
            nodes.add(Boolean.FALSE.equals(page.hasChildren()) ? leaf(item) : expandable(item));
        }
        return new Batch(nodes, children.nextCursor());
    }

    private static Node leaf(Item item) {
        Node node = new Node(item);
        node.loaded = true;
        return node;
    }

    /**
     * Quellen ohne Live-Seitenbaum (etwa Ordner): der Seitenbaum des Space aus dem Bestand, sonst aus den Metadaten
     * der Quelle, auf einmal.
     */
    private static Batch wholeSpace(AppRuntime runtime, String sourceId, String spaceKey) {
        Map<String, String> titles = new HashMap<>();
        PageTree pageTree;
        try {
            SpaceTree stock = runtime.workspace().tree(sourceId, spaceKey);
            stock.pages().forEach((id, page) -> titles.put(id, page.header().metadata().title()));
            pageTree = stock.tree();
        } catch (RuntimeException notStocked) {
            Map<String, TreeEntry> entries = new LinkedHashMap<>();
            String next = null;
            do {
                PageMetadataListing listing = runtime.workspace().liveMetadata(sourceId, spaceKey, next, BATCH);
                for (PageMetadataListing.Entry entry : listing.entries()) {
                    PageMetadata page = entry.page();
                    entries.putIfAbsent(page.pageId(), new TreeEntry(page.pageId(), page.parentPageId()));
                    titles.putIfAbsent(page.pageId(), page.title());
                }
                next = entries.size() >= TREE_LIMIT ? null : listing.nextCursor();
            } while (next != null);
            pageTree = PageTreeBuilder.build(entries.values());
        }
        List<Node> nodes = new ArrayList<>();
        for (PageTree.Node child : pageTree.roots()) {
            nodes.add(built(sourceId, spaceKey, child, titles));
        }
        return new Batch(nodes, null);
    }

    private static Node built(String sourceId, String spaceKey, PageTree.Node node, Map<String, String> titles) {
        String title = titles.get(node.pageId());
        Node built = leaf(new PageItem(sourceId, spaceKey, node.pageId(),
                title == null || title.isBlank() ? node.pageId() : title, !node.children().isEmpty()));
        for (PageTree.Node child : node.children()) {
            built.add(built(sourceId, spaceKey, child, titles));
        }
        return built;
    }

    // ------------------------------------------------------------------ Titelfilter

    private void runFilter() {
        String text = filter.getText().trim();
        int id = ++filterRequest;
        if (text.isEmpty()) {
            cards.show(deck, onlyFavorites ? FAVORITES : TREE);
            note.setText(" ");
            return;
        }
        cards.show(deck, MATCHES);
        String needle = text.toLowerCase(Locale.ROOT);
        Map<String, Match> found = new LinkedHashMap<>();
        Enumeration<?> all = top.breadthFirstEnumeration();
        while (all.hasMoreElements() && found.size() < MATCH_LIMIT) {
            if (all.nextElement() instanceof Node node && node.item() instanceof PageItem page
                    && page.title().toLowerCase(Locale.ROOT).contains(needle)) {
                found.putIfAbsent(page.sourceId() + "/" + page.pageId(), new Match(page.sourceId(), page.spaceKey(),
                        page.pageId(), page.title(), where(page.sourceId(), page.spaceKey())));
            }
        }
        show(found, true);
        AppRuntime runtime = context.runtime();
        PageSearchQuery query;
        try {
            query = PageSearchQuery.of(null, List.of(), List.of(), text, null, null, null, null, null,
                    SearchSort.TITLE);
        } catch (InvalidQueryException invalid) {
            note.setText(invalid.getMessage());
            return;
        }
        if (runtime == null) {
            return;
        }
        List<String> sources = List.copyOf(shownSources);
        // Erst der Bestand, dann jede Quelle selbst (CQL „Titel enthält“), damit auch ungeladene Seiten erscheinen.
        Background.run(() -> runtime.workspace().search(query, null, MATCH_LIMIT), results -> {
            if (id != filterRequest) {
                return;
            }
            for (SearchHit hit : results.hits()) {
                PageMetadata page = hit.page().header().metadata();
                String sourceId = hit.page().header().key().sourceId();
                found.putIfAbsent(sourceId + "/" + page.pageId(), new Match(sourceId, page.partitionKey(),
                        page.pageId(), page.title(), where(sourceId, page.partitionKey())));
            }
            show(found, true);
            live(runtime, id, text, sources, found);
        }, error -> {
            if (id == filterRequest) {
                show(found, true);
                live(runtime, id, text, sources, found);
            }
        });
    }

    /** Die Titelsuche jeder Quelle ({@code PageNavigation.findByTitle}); eine Quelle, die scheitert, fehlt nur. */
    private void live(AppRuntime runtime, int id, String text, List<String> sources, Map<String, Match> found) {
        Background.run(() -> {
            List<Match> matches = new ArrayList<>();
            boolean truncated = false;
            for (String sourceId : sources) {
                try {
                    PageNavigation.TitleMatches result = runtime.workspace().findByTitle(sourceId, Set.of(), text,
                            MATCH_LIMIT);
                    truncated |= result.truncated();
                    for (HarvestSource.PageNode page : result.pages()) {
                        matches.add(new Match(sourceId, page.partitionKey(), page.pageId(), page.title(), null));
                    }
                } catch (RuntimeException unavailable) {
                    // Nicht erreichbar oder ohne Titelsuche: Bestand und geladene Knoten bleiben.
                }
            }
            return new LiveMatches(matches, truncated);
        }, live -> {
            if (id != filterRequest) {
                return;
            }
            for (Match match : live.matches()) {
                found.putIfAbsent(match.sourceId() + "/" + match.pageId(), new Match(match.sourceId(),
                        match.spaceKey(), match.pageId(), match.title(), where(match.sourceId(), match.spaceKey())));
            }
            show(found, false);
            if (live.truncated() || found.size() > MATCH_LIMIT) {
                note.setText(note.getText() + " · weitere vorhanden, Titel genauer eingeben");
            }
        }, error -> {
            if (id == filterRequest) {
                show(found, false);
            }
        });
    }

    private record LiveMatches(List<Match> matches, boolean truncated) {
    }

    private void show(Map<String, Match> found, boolean searching) {
        matchModel.clear();
        found.values().stream().limit(MATCH_LIMIT).forEach(matchModel::addElement);
        note.setText(searching ? "Wissensquellen werden durchsucht …"
                : found.isEmpty() ? "Kein Seitentitel passt" : Texts.count(Math.min(found.size(), MATCH_LIMIT),
                "Seite", "Seiten"));
    }

    private String where(String sourceId, String spaceKey) {
        String source = sourceId;
        for (int i = 0; i < top.getChildCount(); i++) {
            if (((Node) top.getChildAt(i)).item() instanceof SourceItem item && item.sourceId().equals(sourceId)) {
                source = item.title();
            }
        }
        return spaceKey == null ? source : source + " › " + spaceKey;
    }

    private void openMatch() {
        Match match = matches.getSelectedValue();
        if (match != null) {
            context.navigator().openPage(match.sourceId(), match.pageId());
        }
    }

    private static String escape(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /**
     * Eine Zeile über die volle Breite: links Symbol und Titel je Ebene (Datenbank für Quellen, Ordner für Spaces, Seite
     * für Seiten; Hinweise gedämpft), rechtsbündig das Download-Symbol im Stil der Space-Aktionen ({@link RoundHover}).
     */
    private final class Renderer extends javax.swing.tree.DefaultTreeCellRenderer {

        private final javax.swing.Icon download = ComposerIcons.download();
        private boolean action;
        /** Ob die Zeile einen Stern trägt (Space oder Seite): gefüllt als Favorit, sonst nur beim Darüberfahren. */
        private boolean starred;
        private TreeStates.Badge badge = TreeStates.Badge.NONE;
        /** Trägt die Farbe des Zeichens; {@link Glyph} malt in der Vordergrundfarbe seines Wirts. */
        private final JLabel badgeHost = new JLabel();
        private boolean favorite;
        private int row = -1;
        private int width;

        Renderer() {
            // Auswahl wie die gewählte Quellenkarte: Comic-Gelb mit Tinte.
            setBackgroundNonSelectionColor(new java.awt.Color(0, 0, 0, 0));
            setBackgroundSelectionColor(Comic.YELLOW);
            setTextSelectionColor(Comic.INK);
            setBorderSelectionColor(null);
            setFont(Comic.regular(13f));
        }

        @Override
        public java.awt.Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected,
                                                               boolean expanded, boolean leaf, int row,
                                                               boolean focus) {
            super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, focus);
            Item item = value instanceof Node node ? node.item() : null;
            setForeground(selected ? getTextSelectionColor() : Comic.INK);
            switch (item) {
                case SourceItem ignored -> setIcon(MainWindow.database());
                case SpaceItem ignored -> setIcon(SidebarIcons.folder());
                case PageItem ignored -> setIcon(SidebarIcons.page());
                case MoreItem ignored -> {
                    setIcon(null);
                    setForeground(Comic.BLUE);
                }
                case null, default -> {
                    setIcon(null);
                    setForeground(Comic.MUTED);
                }
            }
            action = downloadable(item);
            starred = starrable(item);
            favorite = starred && isFavorite(item);
            badge = badge(item);
            this.row = row;
            int depth = value instanceof Node node ? node.getLevel() : 1;
            int text = super.getPreferredSize().width;
            width = Math.max(text + (action ? ACTION_WIDTH : 0) + (starred ? ACTION_WIDTH + BADGE_WIDTH : 0),
                    tree.getParent() == null ? 0 : tree.getParent().getWidth() - indent(depth) - 4);
            return this;
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            java.awt.Dimension size = super.getPreferredSize();
            return new java.awt.Dimension(Math.max(size.width, width), Math.max(size.height, RoundHover.SIZE));
        }

        @Override
        public void paint(java.awt.Graphics graphics) {
            super.paint(graphics);
            if (!action) {
                return;
            }
            int x = getWidth() - ACTION_WIDTH;
            if (row == hoverRow) {
                RoundHover.paint(graphics, x, 0, ACTION_WIDTH, getHeight(), false);
            }
            java.awt.Graphics2D g = (java.awt.Graphics2D) graphics.create();
            try {
                g.setColor(Comic.INK);
                download.paintIcon(this, g, x + (ACTION_WIDTH - download.getIconWidth()) / 2,
                        (getHeight() - download.getIconHeight()) / 2);
                // Der Stern links daneben: als Favorit immer gefüllt, sonst nur als Umriss beim Darüberfahren.
                if (starred && (favorite || row == hoverRow)) {
                    Glyph star = favorite ? Glyph.STAR_FILLED : Glyph.STAR;
                    int sx = x - ACTION_WIDTH;
                    star.paintIcon(this, g, sx + (ACTION_WIDTH - star.getIconWidth()) / 2,
                            (getHeight() - star.getIconHeight()) / 2);
                }
                // Das Bestandszeichen links vom Stern, in seiner Farbe (Haken grün, Wolke gedämpft, Pfeile blau …).
                if (badge.kind() != TreeStates.Kind.NONE) {
                    int bx = x - ACTION_WIDTH - BADGE_WIDTH;
                    badgeHost.setForeground(badge.color());
                    badge.glyph().paintIcon(badgeHost, g, bx + (BADGE_WIDTH - badge.glyph().getIconWidth()) / 2,
                            (getHeight() - badge.glyph().getIconHeight()) / 2);
                }
            } finally {
                g.dispose();
            }
        }
    }

    /** Linker Rand einer Zeile dieser Tiefe, wie {@code BasicTreeUI} ihn ohne sichtbare Wurzel legt. */
    private int indent(int depth) {
        if (tree.getUI() instanceof javax.swing.plaf.basic.BasicTreeUI basic) {
            return (basic.getLeftChildIndent() + basic.getRightChildIndent()) * depth;
        }
        return 20 * depth;
    }

    /** Herunterladen gibt es für Quellen, Spaces und Seiten (Seite samt Unterseiten). */
    static boolean downloadable(Item item) {
        return item instanceof SourceItem || item instanceof SpaceItem || item instanceof PageItem;
    }

    /** Startet den Download des Knotens sofort ({@link Downloads}); der Auftrag erscheint unter Aufträge. */
    private void download(Item item) {
        switch (item) {
            case SourceItem source -> context.navigator().download(source.sourceId(), List.of());
            case SpaceItem space -> context.navigator().download(space.sourceId(), space.spaceKey());
            case PageItem page -> context.navigator().downloadPage(page.sourceId(), page.spaceKey(), page.pageId());
            default -> {
            }
        }
    }

    /** Ob der Punkt auf dem Download-Symbol der Zeile liegt. */
    private boolean onAction(TreePath path, java.awt.Point point) {
        java.awt.Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && path.getLastPathComponent() instanceof Node node && downloadable(node.item())
                && point.x >= bounds.x + bounds.width - ACTION_WIDTH;
    }

    /** Ob der Punkt auf dem Bestandszeichen der Zeile liegt (links vom Stern). */
    private boolean onBadge(TreePath path, java.awt.Point point) {
        java.awt.Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && path.getLastPathComponent() instanceof Node node && starrable(node.item())
                && point.x >= bounds.x + bounds.width - 2 * ACTION_WIDTH - BADGE_WIDTH
                && point.x < bounds.x + bounds.width - 2 * ACTION_WIDTH;
    }

    private String badgeTip(TreePath path) {
        TreeStates.Badge badge = path.getLastPathComponent() instanceof Node node ? badge(node.item())
                : TreeStates.Badge.NONE;
        return badge.tooltip();
    }

    /** Das Bestandszeichen einer Zeile: Spaces nach ihrem Stand, Seiten nach Zustand bzw. Teilbaum. */
    TreeStates.Badge badge(Item item) {
        return switch (item) {
            case SpaceItem space -> states.space(space.sourceId(), space.spaceKey());
            case PageItem page -> states.page(page.sourceId(), page.spaceKey(), page.pageId(),
                    !Boolean.FALSE.equals(page.hasChildren()));
            case null, default -> TreeStates.Badge.NONE;
        };
    }

    /** Für Tests: der Bestand. */
    TreeStates states() {
        return states;
    }

    /** Ob der Punkt auf dem Stern der Zeile liegt (links vom Download-Symbol). */
    private boolean onStar(TreePath path, java.awt.Point point) {
        java.awt.Rectangle bounds = path == null ? null : tree.getPathBounds(path);
        return bounds != null && path.getLastPathComponent() instanceof Node node && starrable(node.item())
                && point.x >= bounds.x + bounds.width - 2 * ACTION_WIDTH
                && point.x < bounds.x + bounds.width - ACTION_WIDTH;
    }

    // ------------------------------------------------------------------ Favoriten

    /** Favorit werden können Spaces und Seiten (Angelo, 13.11.2026). */
    static boolean starrable(Item item) {
        return item instanceof SpaceItem || item instanceof PageItem;
    }

    boolean isFavorite(Item item) {
        return switch (item) {
            case SpaceItem space -> context.favorites().contains(space.sourceId(), space.spaceKey(), null);
            case PageItem page -> context.favorites().contains(page.sourceId(), page.spaceKey(), page.pageId());
            default -> false;
        };
    }

    /**
     * Setzt den Favoriten des Knotens oder nimmt ihn zurück. Eine Seite merkt sich dabei ihren Pfad (Space und
     * Vorfahren aus {@code PageNavigation.position}, im Hintergrund); ein Space nur seine Quelle.
     */
    void toggleFavorite(Item item) {
        Favorites store = context.favorites();
        switch (item) {
            case SpaceItem space -> store.toggle(Favorites.Favorite.space(space.sourceId(), space.spaceKey(),
                    space.name(), sourceTitle(space.sourceId())));
            case PageItem page -> {
                if (store.contains(page.sourceId(), page.spaceKey(), page.pageId())) {
                    store.toggle(new Favorites.Favorite(page.sourceId(), page.spaceKey(), page.pageId(), page.title(),
                            List.of(), List.of()));
                    return;
                }
                AppRuntime runtime = context.runtime();
                String source = sourceTitle(page.sourceId());
                if (runtime == null) {
                    store.toggle(new Favorites.Favorite(page.sourceId(), page.spaceKey(), page.pageId(), page.title(),
                            List.of(source, page.spaceKey()), List.of()));
                    return;
                }
                Background.run(() -> runtime.workspace().position(page.sourceId(), page.pageId()), position ->
                        store.toggle(favorite(position, source, page)), error ->
                        store.toggle(new Favorites.Favorite(page.sourceId(), page.spaceKey(), page.pageId(),
                                page.title(), List.of(source, page.spaceKey()), List.of())));
            }
            default -> {
            }
        }
    }

    /** Ein Seiten-Favorit aus dem Pfad der Seite: Quelle › Space › Vorfahren, dazu die IDs der Vorfahren. */
    static Favorites.Favorite favorite(PageNavigation.Position position, String sourceTitle, PageItem page) {
        List<String> path = new ArrayList<>();
        List<String> ancestors = new ArrayList<>();
        path.add(sourceTitle);
        if (position == null || position.page() == null) {
            path.add(page.spaceKey());
            return new Favorites.Favorite(page.sourceId(), page.spaceKey(), page.pageId(), page.title(), path,
                    ancestors);
        }
        path.add(position.spaceName() == null || position.spaceName().isBlank() ? position.spaceKey()
                : position.spaceName());
        for (HarvestSource.PageNode ancestor : position.ancestors()) {
            path.add(ancestor.title());
            ancestors.add(ancestor.pageId());
        }
        return new Favorites.Favorite(page.sourceId(), position.spaceKey() == null ? page.spaceKey()
                : position.spaceKey(), page.pageId(), position.page().title() == null ? page.title()
                : position.page().title(), path, ancestors);
    }

    private String sourceTitle(String sourceId) {
        return where(sourceId, null);
    }

    /** Umschalter unten rechts: nur die Favoriten als Liste (Stern gefüllt) oder der ganze Baum (Umriss). */
    void showOnlyFavorites(boolean only) {
        onlyFavorites = only;
        favoritesOnly.setIcon(only ? Glyph.STAR_FILLED : Glyph.STAR);
        favoritesOnly.setToolTipText(only ? ALL_NODES : ONLY_FAVORITES);
        favoritesOnly.getAccessibleContext().setAccessibleName(only ? ALL_NODES : ONLY_FAVORITES);
        favoritesChanged();
        if (filter.getText().trim().isEmpty()) {
            cards.show(deck, only ? FAVORITES : TREE);
            note.setText(only && favoriteModel.isEmpty() ? "Noch keine Favoriten: Stern an Space oder Seite" : " ");
        }
    }

    boolean onlyFavorites() {
        return onlyFavorites;
    }

    private void favoritesChanged() {
        List<Favorites.Favorite> all = context.favorites().all();
        favoriteModel.clear();
        all.forEach(favoriteModel::addElement);
        if (onlyFavorites && filter.getText().trim().isEmpty()) {
            note.setText(all.isEmpty() ? "Noch keine Favoriten: Stern an Space oder Seite" : " ");
        }
        tree.repaint();
    }

    /** Ein Favorit in der Liste: die Seite öffnen bzw. den Space zeigen; der Baum folgt der geöffneten Seite. */
    private void openFavorite() {
        Favorites.Favorite favorite = favorites.getSelectedValue();
        if (favorite == null) {
            return;
        }
        if (favorite.pageId() == null) {
            context.navigator().openSpace(favorite.sourceId(), favorite.spaceKey());
        } else {
            context.navigator().openPage(favorite.sourceId(), favorite.pageId());
        }
    }

    /** Für Tests: die Favoritenliste und ihr Umschalter. */
    JList<Favorites.Favorite> favoriteList() {
        return favorites;
    }

    IconButton favoritesButton() {
        return favoritesOnly;
    }

    private void popup(MouseEvent event) {
        TreePath path = tree.getPathForLocation(event.getX(), event.getY());
        if (path == null || !(path.getLastPathComponent() instanceof Node node)) {
            return;
        }
        if (!tree.isPathSelected(path)) {
            tree.setSelectionPath(path);
        }
        javax.swing.JPopupMenu menu = menu(node.item());
        if (menu.getComponentCount() > 0) {
            menu.show(tree, event.getX(), event.getY());
        }
    }

    /** Das Kontextmenü eines Knotens (für Tests ohne Mausereignis). */
    javax.swing.JPopupMenu menu(Item item) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (item instanceof PageItem page) {
            javax.swing.JMenuItem open = new javax.swing.JMenuItem("Öffnen");
            open.addActionListener(e -> context.navigator().openPage(page.sourceId(), page.pageId()));
            menu.add(open);
        }
        if (downloadable(item)) {
            javax.swing.JMenuItem download = new javax.swing.JMenuItem("Herunterladen");
            download.setToolTipText(DOWNLOAD_TIP);
            download.addActionListener(e -> download(item));
            menu.add(download);
        }
        if (starrable(item)) {
            javax.swing.JMenuItem star = new javax.swing.JMenuItem(isFavorite(item) ? UNFAVORITE : FAVORITE);
            star.setIcon(isFavorite(item) ? Glyph.STAR_FILLED : Glyph.STAR);
            star.addActionListener(e -> toggleFavorite(item));
            menu.add(star);
        }
        if (analysisScope(item, false) != null) {
            String unavailable = analyses.unavailable();
            javax.swing.JMenuItem analyze = new javax.swing.JMenuItem(ANALYZE);
            analyze.setEnabled(unavailable == null);
            analyze.setToolTipText(unavailable == null ? ANALYZE_TIP : unavailable);
            analyze.addActionListener(e -> analyze(item, false));
            menu.add(analyze);
            if (item instanceof PageItem page && !leafPage(page)) {
                javax.swing.JMenuItem only = new javax.swing.JMenuItem(ANALYZE_PAGE);
                only.setEnabled(unavailable == null);
                only.setToolTipText(unavailable == null ? ANALYZE_PAGE_TIP : unavailable);
                only.addActionListener(e -> analyze(item, true));
                menu.add(only);
            }
        }
        if (item instanceof PageItem page) {
            javax.swing.JMenuItem only = new javax.swing.JMenuItem(PAGE_ONLY);
            only.setToolTipText(PAGE_ONLY_TIP);
            only.addActionListener(e -> context.navigator().downloadPage(page.sourceId(), page.spaceKey(),
                    page.pageId(), false));
            menu.add(only);
        }
        return menu;
    }

    /** Blattseite: die Quelle sagt ausdrücklich, dass sie keine Kinder hat (wie die Darstellung im Baum). */
    static boolean leafPage(PageItem page) {
        return Boolean.FALSE.equals(page.hasChildren());
    }

    /**
     * Umfang von „Analysieren“: Blattseite bzw. {@code pageOnly} → nur die Seite, übergeordnete Seite → samt allen
     * Nachfahren, Space und Quelle vollständig; {@code null} für Hilfseinträge („Weitere laden …“, Hinweise).
     */
    static StatementAnalysisJobs.Scope analysisScope(Item item, boolean pageOnly) {
        return switch (item) {
            case SourceItem source -> new StatementAnalysisJobs.Scope.Source(source.sourceId());
            case SpaceItem space -> new StatementAnalysisJobs.Scope.Space(space.sourceId(), space.spaceKey());
            case PageItem page -> pageOnly || leafPage(page)
                    ? new StatementAnalysisJobs.Scope.SinglePage(page.sourceId(), page.spaceKey(), page.pageId())
                    : new StatementAnalysisJobs.Scope.Subtree(page.sourceId(), page.spaceKey(), page.pageId());
            default -> null;
        };
    }

    /**
     * „Analysieren“: eine einzelne Seite startet sofort; größere Umfänge zählt der Core erst im Hintergrund, dann
     * fragt der Dialog mit der Zahl (exakt, „mehr als“ oder Schätzung) und „erneut analysieren“.
     */
    private void analyze(Item item, boolean pageOnly) {
        StatementAnalysisJobs.Scope scope = analysisScope(item, pageOnly);
        if (scope == null) {
            return;
        }
        if (scope instanceof StatementAnalysisJobs.Scope.SinglePage) {
            startAnalysis(scope, StatementAnalysisJobs.Options.DEFAULT);
            return;
        }
        notice("Umfang der Analyse wird ermittelt …", Severity.INFO);
        String where = analysisWhere(item);
        Background.run(() -> analyses.estimate(scope, ANALYSIS_LIMIT), estimate -> {
            if (estimate.exact() && estimate.pages() == 0) {
                notice("Keine Seiten zum Analysieren", Severity.INFO);
                return;
            }
            Boolean reanalyze = confirmation.ask(tree, ANALYZE, analysisQuestion(estimate, where),
                    estimate.exceeded() ? "Alle analysieren" : ANALYZE);
            if (reanalyze == null) {
                return;
            }
            // Über der Grenze hat der Benutzer ausdrücklich alle bestätigt; sonst schützt die Grenze davor, dass
            // der Umfang bis zum Start unbemerkt wächst (der Auftrag scheitert dann vor der ersten Analyse).
            startAnalysis(scope, new StatementAnalysisJobs.Options(reanalyze,
                    estimate.exceeded() ? 0 : ANALYSIS_LIMIT));
        }, error -> notice("Analyse ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    private void startAnalysis(StatementAnalysisJobs.Scope scope, StatementAnalysisJobs.Options options) {
        notice("Analyse wird gestartet …", Severity.INFO);
        Background.run(() -> analyses.start(scope, options), started -> {
            notice(scope instanceof StatementAnalysisJobs.Scope.SinglePage ? "Analyse der Seite gestartet"
                    : "Analyse gestartet", Severity.INFO);
            if (context.navigator() != null) {
                context.navigator().showJob(started.id());
            }
        }, error -> notice("Analyse ließ sich nicht starten: " + Problems.describe(error).title(), Severity.ERROR));
    }

    /** Wo analysiert wird, für den Bestätigungsdialog. */
    static String analysisWhere(Item item) {
        return switch (item) {
            case SourceItem source -> "Quelle „" + source.title() + "“ mit allen Spaces und Seiten";
            case SpaceItem space -> "Space „" + space + "“ der Quelle " + space.sourceId() + " mit allen Seiten";
            case PageItem page -> "Seite „" + page.title() + "“ samt allen Unterseiten (Quelle " + page.sourceId()
                    + (page.spaceKey() == null ? "" : " · Space " + page.spaceKey()) + ")";
            default -> "";
        };
    }

    /** Text des Bestätigungsdialogs: exakte Zahl, „mehr als“ über der Grenze oder Schätzung bei Lücken. */
    static String analysisQuestion(StatementAnalysisJobs.Estimate estimate, String where) {
        String pages = Texts.count(estimate.pages(), "Seite", "Seiten");
        StringBuilder text = new StringBuilder();
        if (estimate.exceeded()) {
            text.append("Mehr als ").append(pages).append(" analysieren?");
        } else if (estimate.incomplete()) {
            text.append(estimate.pages() == 0 ? "Umfang noch unbekannt (Schätzung). Analysieren?"
                    : "Etwa " + pages + " analysieren? (Schätzung)");
        } else {
            text.append(pages).append(" analysieren?");
        }
        text.append("\n\nUmfang: ").append(where).append('.');
        if (estimate.exceeded()) {
            text.append("\n\nDas ist mehr als die Sicherheitsgrenze von ").append(ANALYSIS_LIMIT)
                    .append(" Seiten. Nach Bestätigung werden alle Seiten des Umfangs analysiert.");
        } else if (estimate.incomplete()) {
            text.append("\n\nDer Umfang ließ sich nicht vollständig ermitteln; der Auftrag meldet fehlende Teile.");
        }
        text.append("\n\nFehlende Inhalte werden dafür heruntergeladen. Die Analyse läuft lokal und braucht Speicher "
                + "und Rechenzeit; Fortschritt und Abbruch unter Aufträge.");
        return text.toString();
    }

    /** Eine kurze Meldung in der Statuszeile (wie beim Herunterladen). */
    private void notice(String text, Severity severity) {
        context.events().publish(new StatusMessageEvent(text, NOTICE_MS, severity));
    }

    /** Für Tests. */
    void analyses(Analyses analyses, AnalysisConfirmation confirmation) {
        this.analyses = analyses;
        this.confirmation = confirmation;
    }

    private static void bindEnter(JComponent component, Runnable action) {
        bindKey(component, KeyEvent.VK_ENTER, action);
    }

    private static void bindKey(JComponent component, int key, Runnable action) {
        String name = "explorer-" + key;
        component.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key, 0), name);
        component.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    /** Für Tests. */
    JTree tree() {
        return tree;
    }

    /** Für Tests. */
    IconButton collapseAllButton() {
        return collapseAll;
    }

    /** Für Tests. */
    IconButton showCurrentButton() {
        return showCurrent;
    }

    /** Für Tests. */
    String note() {
        return note.getText();
    }

    /** Für Tests. */
    PlaceholderField filter() {
        return filter;
    }

    /** Für Tests. */
    JList<Match> matches() {
        return matches;
    }
}
