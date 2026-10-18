package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
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
    private static final int MATCH_LIMIT = 50;
    private static final int TREE_LIMIT = 5_000;
    private static final int BATCH = 100;

    /** Umfang aus der Auswahl: eine Quelle, ein Space oder nichts (alle). */
    public record Scope(String sourceId, String spaceKey) {
        public static final Scope ALL = new Scope(null, null);
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
    private final DefaultListModel<Match> matchModel = new DefaultListModel<>();
    private final JList<Match> matches = new JList<>(matchModel);
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JLabel note = Comic.muted(" ");
    private final Timer debounce = new Timer(300, e -> runFilter());
    private List<String> shownSources = List.of();
    private Consumer<Scope> scopeListener = scope -> { };
    private int filterRequest;

    public Explorer(AppContext context) {
        this.context = context;
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setToggleClickCount(0);
        tree.setOpaque(false);
        tree.setRowHeight(0);
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
        tree.addTreeSelectionListener(e -> scopeListener.accept(scope()));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                TreePath path = tree.getPathForLocation(event.getX(), event.getY());
                if (path != null && event.getClickCount() == 1) {
                    activate(path);
                }
            }
        });
        bindEnter(tree, () -> {
            TreePath path = tree.getSelectionPath();
            if (path != null) {
                activate(path);
            }
        });
        matches.setOpaque(false);
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
        JScrollPane matchScroll = Comic.scroll(matches);
        deck.setOpaque(false);
        deck.add(treeScroll, TREE);
        deck.add(matchScroll, MATCHES);
        root.setOpaque(false);
        root.add(filter, BorderLayout.NORTH);
        root.add(deck, BorderLayout.CENTER);
        root.add(note, BorderLayout.SOUTH);
        note.setBorder(BorderFactory.createEmptyBorder(2, 2, 0, 0));
        context.onChange(ignored -> refreshSources());
        refreshSources();
    }

    public JComponent component() {
        return root;
    }

    /** Meldet jede Änderung der Auswahl als Umfang (für die Suche). */
    public void onScope(Consumer<Scope> listener) {
        this.scopeListener = Objects.requireNonNull(listener, "listener");
    }

    /** Umfang der Auswahl: Quelle, Space oder der Space der gewählten Seite; ohne Auswahl alle. */
    public Scope scope() {
        TreePath path = tree.getSelectionPath();
        if (path == null || !(path.getLastPathComponent() instanceof Node node)) {
            return Scope.ALL;
        }
        return switch (node.item()) {
            case SourceItem source -> new Scope(source.sourceId(), null);
            case SpaceItem space -> new Scope(space.sourceId(), space.spaceKey());
            case PageItem page -> new Scope(page.sourceId(), page.spaceKey());
            default -> Scope.ALL;
        };
    }

    // ------------------------------------------------------------------ Baum

    /** Die Wurzelknoten: alle Quellen der Laufzeit, in deren Reihenfolge; unverändert, solange dieselben bleiben. */
    private void refreshSources() {
        AppRuntime runtime = context.runtime();
        List<HarvestSource> sources = runtime == null ? List.of() : runtime.workspace().sources();
        List<String> ids = sources.stream().map(HarvestSource::id).toList();
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
        note.setText(sources.isEmpty() ? " " : Texts.count(sources.size(), "Wissensquelle", "Wissensquellen"));
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
        Background.run(() -> children(runtime, item, null), batch -> {
            node.loading = false;
            node.loaded = true;
            node.removeAllChildren();
            append(node, batch);
            model.nodeStructureChanged(node);
        }, error -> {
            node.loading = false;
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
            case MoreItem more -> loadMore(node, more);
            default -> {
            }
        }
    }

    private void loadMore(Node moreNode, MoreItem more) {
        AppRuntime runtime = context.runtime();
        Node parent = (Node) moreNode.getParent();
        if (runtime == null || parent == null || parent.loading) {
            return;
        }
        parent.loading = true;
        moreNode.setUserObject(new Note(LOADING));
        model.nodeChanged(moreNode);
        Background.run(() -> children(runtime, parent.item(), more.cursor()), batch -> {
            parent.loading = false;
            parent.remove(moreNode);
            append(parent, batch);
            model.nodeStructureChanged(parent);
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
            cards.show(deck, TREE);
            note.setText(shownSources.isEmpty() ? " "
                    : Texts.count(shownSources.size(), "Wissensquelle", "Wissensquellen"));
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
            show(found, false);
        }, error -> {
            if (id == filterRequest) {
                show(found, false);
            }
        });
    }

    private void show(Map<String, Match> found, boolean searching) {
        matchModel.clear();
        found.values().stream().limit(MATCH_LIMIT).forEach(matchModel::addElement);
        note.setText(searching ? "Bestand wird durchsucht …"
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

    /** Symbole je Ebene: Datenbank für Quellen, Ordner für Spaces, Seite für Seiten; Hinweise gedämpft. */
    private static final class Renderer extends javax.swing.tree.DefaultTreeCellRenderer {

        Renderer() {
            setBackgroundNonSelectionColor(new java.awt.Color(0, 0, 0, 0));
            setBorderSelectionColor(null);
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
            return this;
        }
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
    PlaceholderField filter() {
        return filter;
    }

    /** Für Tests. */
    JList<Match> matches() {
        return matches;
    }
}
