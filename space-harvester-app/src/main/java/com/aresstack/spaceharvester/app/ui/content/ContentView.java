package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.View;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageListing;
import com.aresstack.spaceharvester.application.query.SpaceTree;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import com.aresstack.spaceharvester.application.sources.PageMetadata;
import com.aresstack.spaceharvester.application.sources.PageMetadataListing;
import com.aresstack.spaceharvester.application.sources.Partition;
import com.aresstack.spaceharvester.application.tree.PageTree;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Inhalte: Quelle und Space wählen, Seiten aus dem Bestand oder live aus der Quelle stückweise laden (der Cursor
 * bleibt intern; weitere Seiten folgen beim Scrollen), optional als Struktur, und eine Seite öffnen. Die Liste sagt
 * jederzeit, woher sie stammt und ob sie vollständig ist.
 */
public final class ContentView implements View {

    private static final int BATCH = 100;
    private static final String LIST = "list";
    private static final String TREE = "tree";

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JComboBox<SourceItem> source = Comic.combo();
    private final JComboBox<String> space = Comic.combo();
    private final ComposerToggleButton cached = toggle("Bestand", "Seiten aus dem lokalen Bestand (Cache)");
    private final ComposerToggleButton live = toggle("Live", "Seitenmetadaten live aus der Quelle");
    private final ComposerToggleButton structure = toggle("Struktur", "Seitenbaum aus dem Bestand");
    private final ComposerButton load = Comic.primary("Laden", "Seiten des Space laden");
    private final JTextField filter = Comic.field(14);
    private final JTextField openId = Comic.field(18);
    private final ComposerButton open = Comic.button("Seite öffnen", "Seiten-ID oder Adresse einer Seite öffnen");
    private final PagesModel pagesModel = new PagesModel();
    private final JTable pages = Comic.table(new JTable(pagesModel));
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("Struktur")));
    private final CardLayout listCards = new CardLayout();
    private final JPanel listDeck = new JPanel(listCards);
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final PageDetailPanel detail;

    private Batch loading;
    private String cursor;
    private boolean complete;
    /** Nach „Öffnen“ aus Wissensquellen: ist der Bestand leer, einmal auf live wechseln. */
    private boolean openedFresh;
    private boolean liveFallback;

    public ContentView(AppContext context) {
        this.context = context;
        this.detail = new PageDetailPanel(context);
        root.setBackground(Comic.SURFACE);

        ButtonGroup modes = new ButtonGroup();
        modes.add(cached);
        modes.add(live);
        modes.add(structure);
        cached.setSelected(true);
        cached.addActionListener(e -> reload());
        live.addActionListener(e -> reload());
        structure.addActionListener(e -> reload());
        load.addActionListener(e -> reload());
        source.addActionListener(e -> sourceChanged());
        space.setEditable(true);
        space.setToolTipText("Space-Key; die Liste füllt sich nach der Verbindungsprüfung");
        filter.setToolTipText("Filtert die geladenen Seiten nach Titel");
        filter.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                pagesModel.filter(filter.getText());
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                pagesModel.filter(filter.getText());
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                pagesModel.filter(filter.getText());
            }
        });
        openId.setToolTipText("Seiten-ID oder Adresse (URL mit pageId)");
        openId.addActionListener(e -> openTyped());
        open.addActionListener(e -> openTyped());

        pages.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && pages.getSelectedRow() >= 0) {
                Row row = pagesModel.row(pages.getSelectedRow());
                detail.load(selectedSource(), row.pageId(), row.live());
            }
        });
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Comic.regular(13f));
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Object node = tree.getLastSelectedPathComponent();
                if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof TreeItem item) {
                    detail.load(selectedSource(), item.pageId(), false);
                }
            }
        });
        JScrollPane scroll = Comic.scroll(pages);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            int bottom = e.getAdjustable().getValue() + e.getAdjustable().getVisibleAmount();
            if (!e.getValueIsAdjusting() && bottom >= e.getAdjustable().getMaximum() - 40) {
                loadMore();
            }
        });
        listDeck.setOpaque(false);
        listDeck.add(scroll, LIST);
        listDeck.add(Comic.scroll(tree), TREE);

        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
        toolbar.setOpaque(false);
        toolbar.add(Comic.caption("Quelle"));
        toolbar.add(source);
        toolbar.add(Comic.caption("Space"));
        toolbar.add(space);
        toolbar.add(cached);
        toolbar.add(live);
        toolbar.add(structure);
        toolbar.add(load);
        toolbar.add(Comic.caption("Seite"));
        toolbar.add(openId);
        toolbar.add(open);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBackground(Comic.SURFACE);
        left.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 8));
        JPanel filterRow = new JPanel(new BorderLayout(6, 0));
        filterRow.setOpaque(false);
        filterRow.add(Comic.caption("Filter"), BorderLayout.WEST);
        filterRow.add(filter, BorderLayout.CENTER);
        JPanel leftTop = new JPanel(new BorderLayout(0, 4));
        leftTop.setOpaque(false);
        leftTop.add(feedback, BorderLayout.NORTH);
        leftTop.add(filterRow, BorderLayout.SOUTH);
        left.add(leftTop, BorderLayout.NORTH);
        left.add(listDeck, BorderLayout.CENTER);
        left.add(status, BorderLayout.SOUTH);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Comic.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        top.add(toolbar, BorderLayout.CENTER);
        root.add(top, BorderLayout.NORTH);
        root.add(new ComicSplitPane(left, detail, 300, 720), BorderLayout.CENTER);
        context.onChange(ignored -> refreshSources());
        refreshSources();
    }

    @Override
    public JComponent component() {
        return root;
    }

    /**
     * Öffnet einen Space (z. B. aus „Wissensquellen“) und lädt seine Seiten aus dem Bestand; ist der Bestand noch leer
     * (neue, nicht abgeglichene Quelle), stattdessen die Seitenmetadaten live. Der volle Abgleich bleibt eine bewusste
     * Aktion („Synchronisieren“).
     */
    public void openSpace(String sourceId, String spaceKey) {
        selectSource(sourceId);
        space.setSelectedItem(spaceKey);
        cached.setSelected(true);
        openedFresh = true;
        reload();
    }

    /** Öffnet eine Seite (z. B. aus der Suche). */
    public void openPage(String sourceId, String pageId) {
        selectSource(sourceId);
        detail.load(sourceId, pageId, false);
    }

    private void refreshSources() {
        String selected = selectedSource();
        AppRuntime runtime = context.runtime();
        List<HarvestSource> all = runtime == null ? List.of() : runtime.workspace().sources();
        source.removeAllItems();
        for (HarvestSource s : all) {
            source.addItem(new SourceItem(s.id(), s.title() == null ? s.id() : s.title()));
        }
        if (selected != null) {
            selectSource(selected);
        }
        fillSpaces();
    }

    private void selectSource(String sourceId) {
        for (int i = 0; i < source.getItemCount(); i++) {
            if (source.getItemAt(i).id().equals(sourceId)) {
                source.setSelectedIndex(i);
                return;
            }
        }
    }

    private void sourceChanged() {
        String id = selectedSource();
        if (id != null && context.connection(id).state() == AppContext.Connection.State.NOT_CHECKED) {
            context.checkConnection(id, this::fillSpaces);
        }
        fillSpaces();
    }

    private void fillSpaces() {
        String id = selectedSource();
        Object current = space.getEditor().getItem();
        space.removeAllItems();
        if (id != null) {
            for (Partition partition : context.connection(id).spaces()) {
                space.addItem(partition.key());
            }
        }
        if (current != null && !current.toString().isBlank()) {
            space.setSelectedItem(current);
        }
    }

    private void reload() {
        String sourceId = selectedSource();
        Object item = space.getEditor().getItem();
        String spaceKey = item == null ? "" : item.toString().trim();
        pagesModel.clear();
        cursor = null;
        complete = false;
        liveFallback = openedFresh;
        openedFresh = false;
        feedback.clear();
        if (sourceId == null || spaceKey.isEmpty()) {
            status.setText("Quelle und Space wählen.");
            return;
        }
        if (structure.isSelected()) {
            listCards.show(listDeck, TREE);
            loadTree(sourceId, spaceKey);
            return;
        }
        listCards.show(listDeck, LIST);
        loading = null;
        loadBatch(new Batch(sourceId, spaceKey, live.isSelected()));
    }

    private void loadMore() {
        if (loading == null && cursor != null && !complete && !pagesModel.batchKey.isEmpty()) {
            Batch next = pagesModel.batch;
            if (next != null) {
                loadBatch(next);
            }
        }
    }

    private void loadBatch(Batch batch) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || loading != null) {
            return;
        }
        loading = batch;
        String from = cursor;
        status.setText(pagesModel.size() == 0 ? "Seiten werden geladen …" : pagesModel.size() + " Seiten, weitere werden geladen …");
        Background.run(() -> batch.live()
                        ? Result.of(runtime.workspace().liveMetadata(batch.sourceId(), batch.spaceKey(), from, BATCH))
                        : Result.of(runtime.workspace().cachedPages(batch.sourceId(), batch.spaceKey(), from, BATCH)),
                result -> {
                    if (loading != batch) {
                        return;
                    }
                    loading = null;
                    if (result.rows().isEmpty() && fallBackToLive(batch, from)) {
                        return;
                    }
                    liveFallback = false;
                    pagesModel.append(batch, result.rows());
                    cursor = result.nextCursor();
                    complete = result.complete();
                    status.setText(describe(batch, result));
                    if (cursor != null && !complete && pagesModel.size() < BATCH * 3 && fitsOnScreen()) {
                        loadMore();
                    }
                },
                error -> {
                    if (loading != batch) {
                        return;
                    }
                    loading = null;
                    // Nur der erwartete Fehlbestand führt zu live; ein beschädigter Bestand oder fehlende Rechte
                    // bleiben als Problem sichtbar.
                    if (cacheMiss(error) && fallBackToLive(batch, from)) {
                        return;
                    }
                    status.setText(pagesModel.size() + " Seiten geladen; Laden abgebrochen.");
                    feedback.problem(error);
                });
    }

    /**
     * Nach „Öffnen“ aus Wissensquellen: Bringt der erste Stapel aus dem Bestand nichts (noch nicht abgeglichen), lädt
     * dieselbe Ansicht die Seitenmetadaten live. Gilt einmal; der volle Abgleich bleibt eine bewusste Aktion.
     */
    private boolean fallBackToLive(Batch batch, String from) {
        if (!liveFallback || batch.live() || from != null) {
            return false;
        }
        liveFallback = false;
        live.setSelected(true);
        reload();
        feedback.info("Noch kein Bestand für " + batch.spaceKey() + ", die Seiten kommen live aus der Quelle.",
                "„Synchronisieren“ unter Wissensquellen legt den lokalen Bestand an.");
        return true;
    }

    private static boolean cacheMiss(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause()) {
            if (e instanceof CacheMissException) {
                return true;
            }
        }
        return false;
    }

    private boolean fitsOnScreen() {
        return pages.getRowCount() * pages.getRowHeight() < listDeck.getHeight() + 200;
    }

    private String describe(Batch batch, Result result) {
        String origin = batch.live() ? "live aus der Quelle, " + Texts.time(result.retrievedAt())
                : "aus dem Bestand · " + Texts.generation(result.generation());
        String more = result.complete() ? "vollständig" : result.nextCursor() != null ? "weitere beim Scrollen"
                : "unvollständig";
        return Texts.count(pagesModel.size(), "Seite", "Seiten") + " · " + more + " · " + origin;
    }

    private void loadTree(String sourceId, String spaceKey) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        status.setText("Struktur wird geladen …");
        feedback.clear();
        Background.run(() -> runtime.workspace().tree(sourceId, spaceKey), spaceTree -> {
            DefaultMutableTreeNode top = new DefaultMutableTreeNode(spaceKey);
            for (PageTree.Node node : spaceTree.tree().roots()) {
                top.add(node(node, spaceTree.pages()));
            }
            tree.setModel(new DefaultTreeModel(top));
            for (int i = 0; i < Math.min(tree.getRowCount(), 50); i++) {
                tree.expandRow(i);
            }
            status.setText(Texts.count(spaceTree.tree().nodeCount(), "Seite", "Seiten") + " · Tiefe "
                    + spaceTree.tree().depth()
                    + (spaceTree.tree().anomalies().isEmpty() ? "" : " · "
                    + Texts.count(spaceTree.tree().anomalies().size(), "Auffälligkeit", "Auffälligkeiten"))
                    + " · " + Texts.generation(spaceTree.generation()));
        }, error -> {
            status.setText("Struktur nicht geladen.");
            feedback.problem(error);
        });
    }

    private static DefaultMutableTreeNode node(PageTree.Node node, Map<String, ListedPage> pages) {
        ListedPage page = pages.get(node.pageId());
        DefaultMutableTreeNode treeNode = new DefaultMutableTreeNode(new TreeItem(node.pageId(),
                page == null ? node.pageId() : page.header().metadata().title()));
        for (PageTree.Node child : node.children()) {
            treeNode.add(node(child, pages));
        }
        return treeNode;
    }

    private void openTyped() {
        String sourceId = selectedSource();
        String text = openId.getText().trim();
        if (sourceId == null || text.isEmpty()) {
            return;
        }
        if (text.contains("://")) {
            detail.loadByUrl(sourceId, text);
        } else {
            detail.load(sourceId, text, live.isSelected());
        }
    }

    private String selectedSource() {
        SourceItem item = (SourceItem) source.getSelectedItem();
        return item == null ? null : item.id();
    }

    private static ComposerToggleButton toggle(String text, String tooltip) {
        ComposerToggleButton button = new ComposerToggleButton(null, text, tooltip);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        return button;
    }

    private record SourceItem(String id, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    private record TreeItem(String pageId, String title) {
        @Override
        public String toString() {
            return title;
        }
    }

    private record Batch(String sourceId, String spaceKey, boolean live) {
    }

    /** Eine Zeile der Seitenliste, aus dem Bestand oder live. */
    record Row(String pageId, String title, String version, Instant modifiedAt, boolean live) {
    }

    private record Result(List<Row> rows, String nextCursor, boolean complete, CacheGeneration generation,
                          Instant retrievedAt) {

        static Result of(PageListing listing) {
            List<Row> rows = new ArrayList<>();
            for (ListedPage page : listing.pages()) {
                PageMetadata m = page.header().metadata();
                rows.add(new Row(m.pageId(), m.title(), m.version(), m.modifiedAt(), false));
            }
            return new Result(rows, listing.nextCursor(), listing.complete(), listing.generation(), null);
        }

        static Result of(PageMetadataListing listing) {
            List<Row> rows = new ArrayList<>();
            for (PageMetadataListing.Entry entry : listing.entries()) {
                PageMetadata m = entry.page();
                rows.add(new Row(m.pageId(), m.title(), m.version(), m.modifiedAt(), true));
            }
            return new Result(rows, listing.nextCursor(), listing.nextCursor() == null, null, listing.retrievedAt());
        }
    }

    private static final class PagesModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Version", "Geändert", "Seiten-ID"};
        private final List<Row> all = new ArrayList<>();
        private List<Row> visible = new ArrayList<>();
        private String query = "";
        private Batch batch;
        private String batchKey = "";

        void clear() {
            all.clear();
            visible = new ArrayList<>();
            batch = null;
            batchKey = "";
            fireTableDataChanged();
        }

        void append(Batch from, List<Row> rows) {
            batch = from;
            batchKey = from.sourceId() + "/" + from.spaceKey() + "/" + from.live();
            all.addAll(rows);
            filter(query);
        }

        void filter(String text) {
            query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            visible = query.isEmpty() ? new ArrayList<>(all) : all.stream()
                    .filter(row -> Objects.toString(row.title(), "").toLowerCase(Locale.ROOT).contains(query))
                    .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
            fireTableDataChanged();
        }

        int size() {
            return all.size();
        }

        Row row(int index) {
            return visible.get(index);
        }

        @Override
        public int getRowCount() {
            return visible.size();
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
        public Object getValueAt(int index, int column) {
            Row row = visible.get(index);
            return switch (column) {
                case 0 -> row.title();
                case 1 -> row.version();
                case 2 -> Texts.time(row.modifiedAt());
                default -> row.pageId();
            };
        }
    }
}
