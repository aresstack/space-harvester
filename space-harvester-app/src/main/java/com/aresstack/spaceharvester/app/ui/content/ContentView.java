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
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.View;
import com.aresstack.spaceharvester.application.jobs.JobState;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheMissException;
import com.aresstack.spaceharvester.application.query.ListedPage;
import com.aresstack.spaceharvester.application.query.PageListing;
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
import javax.swing.JTextArea;
import javax.swing.JTree;
import javax.swing.Timer;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Inhalte: Wissensquelle → Space → Seiten → Seiteninhalt. Nach der Wahl eines Space erscheinen sofort seine Seiten:
 * aus dem lokalen Bestand, wenn der Space schon synchronisiert ist, sonst live aus der Quelle (ein leerer Bestand ist
 * bei einer neuen Quelle der Normalfall und kein Fehler). Eine gewählte Seite lädt sofort rechts. Woher die Liste
 * stammt, sagt eine Zeile über ihr, zusammen mit „Space synchronisieren“, das den Space in den lokalen Bestand
 * übernimmt. Der Seitenbaum ist eine zweite Darstellung derselben Seiten und braucht den Bestand.
 *
 * <p>Jeder Wechsel von Quelle oder Space leert Liste und Seitenansicht und verwirft noch laufende Ladevorgänge
 * ({@link #epoch}); weitere Seiten folgen beim Scrollen, der Cursor bleibt intern.
 */
public final class ContentView implements View {

    private static final int BATCH = 100;
    private static final String LIST = "list";
    private static final String TREE = "tree";

    /** Woher die angezeigten Seiten stammen. */
    enum Origin { STOCK, LIVE }

    private final AppContext context;
    private final JPanel root = new JPanel(new BorderLayout());
    private final JComboBox<SourceItem> source = Comic.combo();
    private final JComboBox<String> space = Comic.combo();
    private final PlaceholderField openId = new PlaceholderField(20, "Seiten-ID oder Adresse …");
    private final JLabel originTitle = new JLabel(" ");
    private final JTextArea originText = Comic.note("");
    private final ComposerButton sync = Comic.primary("Space synchronisieren",
            "Übernimmt alle Seiten dieses Space in den lokalen Bestand (für Suche, Seitenbaum und Export)");
    private final JPanel originBar = new JPanel(new BorderLayout(12, 0));
    private final ComposerToggleButton asList = toggle("Liste", "Seiten als Liste");
    private final ComposerToggleButton asTree = toggle("Seitenbaum", "Seiten nach Elternseiten geordnet");
    private final PlaceholderField filter = new PlaceholderField(14, "Seiten suchen …");
    private final PagesModel pagesModel = new PagesModel();
    private final JTable pages = Comic.table(new JTable(pagesModel));
    private final JTree tree = new JTree(new DefaultTreeModel(new DefaultMutableTreeNode("Seiten")));
    private final CardLayout listCards = new CardLayout();
    private final JPanel listDeck = new JPanel(listCards);
    private final JLabel status = Comic.muted(" ");
    private final Feedback feedback = new Feedback();
    private final PageDetailPanel detail;

    /** Zählt Wechsel von Quelle und Space; Ergebnisse eines älteren Stands werden verworfen. */
    private int epoch;
    private Origin origin;
    private CacheGeneration generation;
    private Batch loading;
    private String cursor;
    private boolean complete;
    private boolean treeLoaded;
    /** Während Quellen- oder Space-Liste programmatisch gefüllt werden, lösen ihre Ereignisse nichts aus. */
    private boolean filling;
    private Timer syncWatch;

    public ContentView(AppContext context) {
        this.context = context;
        this.detail = new PageDetailPanel(context);
        root.setBackground(Comic.SURFACE);

        source.addActionListener(e -> {
            if (!filling) {
                sourceChanged();
            }
        });
        space.setEditable(true);
        space.setToolTipText("Space wählen oder Space-Key eingeben");
        // Auswahl oder Enter im Feld öffnet den Space; dieselbe Wahl ein zweites Mal lädt nicht neu.
        space.addActionListener(e -> {
            if (!filling && !spaceKey().isEmpty() && !Objects.equals(spaceKey(), pagesModel.spaceKey)) {
                reload();
            }
        });
        openId.setToolTipText("Eine Seite direkt öffnen: Seiten-ID oder Adresse mit pageId, dann Enter");
        openId.addActionListener(e -> openTyped());
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

        ButtonGroup shapes = new ButtonGroup();
        shapes.add(asList);
        shapes.add(asTree);
        asList.setSelected(true);
        asList.addActionListener(e -> listCards.show(listDeck, LIST));
        asTree.addActionListener(e -> showTree());
        sync.addActionListener(e -> startSync());

        pages.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting() && pages.getSelectedRow() >= 0) {
                Row row = pagesModel.row(pages.getSelectedRow());
                detail.load(selectedSource(), row.pageId(), false);
            }
        });
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Comic.regular(13f));
        tree.addTreeSelectionListener(e -> {
            Object node = tree.getLastSelectedPathComponent();
            if (node instanceof DefaultMutableTreeNode n && n.getUserObject() instanceof TreeItem item) {
                detail.load(selectedSource(), item.pageId(), false);
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
        toolbar.add(Comic.caption("Wissensquelle"));
        toolbar.add(source);
        toolbar.add(Comic.caption("Space"));
        toolbar.add(space);
        JPanel opener = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        opener.setOpaque(false);
        opener.add(openId);

        originTitle.setFont(Comic.semiBold(13f));
        originTitle.setForeground(Comic.INK);
        JPanel originLines = new JPanel(new BorderLayout(0, 2));
        originLines.setOpaque(false);
        originLines.add(originTitle, BorderLayout.NORTH);
        originLines.add(originText, BorderLayout.CENTER);
        originBar.setOpaque(false);
        originBar.setBorder(BorderFactory.createEmptyBorder(4, 4, 6, 4));
        originBar.add(originLines, BorderLayout.CENTER);
        originBar.add(Comic.row(sync), BorderLayout.EAST);
        originBar.setVisible(false);

        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Comic.SURFACE);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 8, 6, 8)));
        top.add(toolbar, BorderLayout.CENTER);
        top.add(opener, BorderLayout.EAST);
        top.add(originBar, BorderLayout.SOUTH);

        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.setBackground(Comic.SURFACE);
        left.setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 8));
        JLabel heading = new JLabel("Seiten");
        heading.setFont(Comic.semiBold(15f));
        heading.setForeground(Comic.INK);
        JPanel headingRow = new JPanel(new BorderLayout(6, 0));
        headingRow.setOpaque(false);
        headingRow.add(heading, BorderLayout.WEST);
        headingRow.add(Comic.row(asList, asTree), BorderLayout.EAST);
        JPanel leftTop = new JPanel(new BorderLayout(0, 4));
        leftTop.setOpaque(false);
        leftTop.add(headingRow, BorderLayout.NORTH);
        leftTop.add(filter, BorderLayout.CENTER);
        leftTop.add(feedback, BorderLayout.SOUTH);
        left.add(leftTop, BorderLayout.NORTH);
        left.add(listDeck, BorderLayout.CENTER);
        left.add(status, BorderLayout.SOUTH);

        root.add(top, BorderLayout.NORTH);
        root.add(new ComicSplitPane(left, detail, 320, 720), BorderLayout.CENTER);
        context.onChange(ignored -> refreshSources());
        refreshSources();
        showOrigin();
    }

    @Override
    public JComponent component() {
        return root;
    }

    /** Öffnet einen Space (z. B. aus „Wissensquellen“): seine Seiten aus dem Bestand, ohne Bestand live. */
    public void openSpace(String sourceId, String spaceKey) {
        if (!Objects.equals(sourceId, selectedSource())) {
            selectSource(sourceId);
            sourceChanged();
        }
        filling = true;
        try {
            space.setSelectedItem(spaceKey);
        } finally {
            filling = false;
        }
        reload();
    }

    /** Öffnet eine Seite (z. B. aus der Suche). */
    public void openPage(String sourceId, String pageId) {
        if (!Objects.equals(sourceId, selectedSource())) {
            selectSource(sourceId);
            sourceChanged();
        }
        detail.load(sourceId, pageId, false);
    }

    private void refreshSources() {
        String selected = selectedSource();
        AppRuntime runtime = context.runtime();
        List<HarvestSource> all = runtime == null ? List.of() : runtime.workspace().sources();
        filling = true;
        try {
            source.removeAllItems();
            for (HarvestSource s : all) {
                source.addItem(new SourceItem(s.id(), s.title() == null ? s.id() : s.title()));
            }
            if (selected != null) {
                selectSource(selected);
            }
        } finally {
            filling = false;
        }
        if (!Objects.equals(selected, selectedSource())) {
            sourceChanged();
        } else {
            fillSpaces();
        }
    }

    private void selectSource(String sourceId) {
        boolean before = filling;
        filling = true;
        try {
            for (int i = 0; i < source.getItemCount(); i++) {
                if (source.getItemAt(i).id().equals(sourceId)) {
                    source.setSelectedIndex(i);
                    return;
                }
            }
        } finally {
            filling = before;
        }
    }

    /** Neue Quelle: Space, Seiten und Seitenansicht gehören zur alten und verschwinden. */
    private void sourceChanged() {
        String id = selectedSource();
        filling = true;
        try {
            space.setSelectedItem(null);
            space.getEditor().setItem("");
        } finally {
            filling = false;
        }
        reset("Space wählen.");
        if (id != null && context.connection(id).state() == AppContext.Connection.State.NOT_CHECKED) {
            context.checkConnection(id, this::fillSpaces);
        }
        fillSpaces();
    }

    private void fillSpaces() {
        String id = selectedSource();
        String current = spaceKey();
        filling = true;
        try {
            space.removeAllItems();
            if (id != null) {
                for (Partition partition : context.connection(id).spaces()) {
                    space.addItem(partition.key());
                }
            }
            space.setSelectedItem(current.isEmpty() ? null : current);
            space.getEditor().setItem(current);
        } finally {
            filling = false;
        }
        AppContext.Connection connection = id == null ? null : context.connection(id);
        if (connection != null && connection.state() == AppContext.Connection.State.FAILED && current.isEmpty()) {
            feedback.problem(connection.error(), Problems.Step.SPACES);
        }
    }

    /** Leert Liste, Baum und Seitenansicht und verwirft laufende Ladevorgänge. */
    private void reset(String hint) {
        epoch++;
        // Ein laufender Abgleich des vorigen Space läuft weiter (Aufträge); der Knopf gilt dem neuen Space.
        if (syncWatch != null) {
            syncWatch.stop();
            syncWatch = null;
        }
        sync.setEnabled(true);
        loading = null;
        cursor = null;
        complete = false;
        origin = null;
        generation = null;
        treeLoaded = false;
        pagesModel.clear(null);
        tree.setModel(new DefaultTreeModel(new DefaultMutableTreeNode("Seiten")));
        feedback.clear();
        detail.clear();
        asList.setSelected(true);
        listCards.show(listDeck, LIST);
        status.setText(hint);
        showOrigin();
    }

    private void reload() {
        String sourceId = selectedSource();
        String spaceKey = spaceKey();
        reset(" ");
        if (sourceId == null || spaceKey.isEmpty()) {
            status.setText("Space wählen.");
            return;
        }
        pagesModel.spaceKey = spaceKey;
        loadBatch(new Batch(epoch, sourceId, spaceKey, Origin.STOCK));
    }

    private void loadMore() {
        Batch last = pagesModel.batch;
        if (loading == null && cursor != null && !complete && last != null && last.epoch() == epoch) {
            loadBatch(last);
        }
    }

    private void loadBatch(Batch batch) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || loading != null) {
            return;
        }
        loading = batch;
        String from = cursor;
        status.setText(pagesModel.size() == 0 ? "Seiten werden geladen …"
                : pagesModel.size() + " Seiten, weitere werden geladen …");
        Background.run(() -> batch.origin() == Origin.LIVE
                        ? Result.of(runtime.workspace().liveMetadata(batch.sourceId(), batch.spaceKey(), from, BATCH))
                        : Result.of(runtime.workspace().cachedPages(batch.sourceId(), batch.spaceKey(), from, BATCH)),
                result -> {
                    if (loading != batch || batch.epoch() != epoch) {
                        return;
                    }
                    loading = null;
                    // Noch nichts im Bestand: Das ist bei einer neuen Quelle normal, die Seiten kommen dann live.
                    if (batch.origin() == Origin.STOCK && from == null && result.rows().isEmpty()) {
                        loadBatch(batch.live());
                        return;
                    }
                    if (origin == null) {
                        origin = batch.origin();
                        generation = result.generation();
                        showOrigin();
                    }
                    pagesModel.append(batch, result.rows());
                    cursor = result.nextCursor();
                    complete = result.complete();
                    status.setText(describe(result));
                    if (cursor != null && !complete && pagesModel.size() < BATCH * 3 && fitsOnScreen()) {
                        loadMore();
                    }
                },
                error -> {
                    if (loading != batch || batch.epoch() != epoch) {
                        return;
                    }
                    loading = null;
                    if (batch.origin() == Origin.STOCK && from == null && cacheMiss(error)) {
                        loadBatch(batch.live());
                        return;
                    }
                    status.setText(pagesModel.size() == 0 ? "Keine Seiten geladen."
                            : pagesModel.size() + " Seiten geladen; weitere nicht.");
                    feedback.problem(error, batch.origin() == Origin.LIVE ? Problems.Step.PAGE_LIST
                            : Problems.Step.STOCK);
                });
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

    private String describe(Result result) {
        String more = result.complete() ? "" : result.nextCursor() != null ? " · weitere beim Scrollen"
                : " · Liste unvollständig";
        return Texts.count(pagesModel.size(), "Seite", "Seiten") + more;
    }

    /** Die Zeile über der Liste: woher die Seiten kommen und was „Synchronisieren“ bewirkt. */
    private void showOrigin() {
        if (origin == null) {
            originBar.setVisible(false);
            asTree.setEnabled(false);
            asTree.setToolTipText("Erst einen Space wählen");
            return;
        }
        originBar.setVisible(true);
        if (origin == Origin.LIVE) {
            originTitle.setText("Seiten werden live aus der Quelle angezeigt.");
            originText.setText("Dieser Space ist noch nicht lokal gespeichert. „Space synchronisieren“ übernimmt "
                    + "alle Seiten in den lokalen Bestand; dann stehen Suche, Seitenbaum und Export bereit.");
            sync.setText("Space synchronisieren");
            asTree.setEnabled(false);
            asTree.setToolTipText("Der Seitenbaum entsteht aus dem lokalen Bestand; dafür den Space synchronisieren");
        } else {
            originTitle.setText("Seiten aus dem lokalen Bestand.");
            originText.setText("Stand: " + Texts.generation(generation) + ". Eine geöffnete Seite zeigt den "
                    + "gespeicherten Stand; „Erneut synchronisieren“ holt Änderungen aus der Quelle.");
            sync.setText("Erneut synchronisieren");
            asTree.setEnabled(true);
            asTree.setToolTipText("Seiten nach Elternseiten geordnet");
        }
        originBar.revalidate();
    }

    private void showTree() {
        listCards.show(listDeck, TREE);
        AppRuntime runtime = context.runtime();
        String sourceId = selectedSource();
        String spaceKey = pagesModel.spaceKey;
        if (treeLoaded || runtime == null || sourceId == null || spaceKey == null || origin != Origin.STOCK) {
            return;
        }
        int current = epoch;
        status.setText("Seitenbaum wird aufgebaut …");
        Background.run(() -> runtime.workspace().tree(sourceId, spaceKey), spaceTree -> {
            if (current != epoch) {
                return;
            }
            treeLoaded = true;
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
                    + Texts.count(spaceTree.tree().anomalies().size(), "Auffälligkeit", "Auffälligkeiten")));
        }, error -> {
            if (current == epoch) {
                status.setText("Seitenbaum nicht aufgebaut.");
                feedback.problem(error, Problems.Step.STOCK);
            }
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

    /** Übernimmt den Space in den lokalen Bestand und zeigt danach die Seiten aus dem Bestand. */
    private void startSync() {
        AppRuntime runtime = context.runtime();
        String sourceId = selectedSource();
        String spaceKey = pagesModel.spaceKey;
        if (runtime == null || sourceId == null || spaceKey == null) {
            return;
        }
        sync.setEnabled(false);
        feedback.working("Space " + spaceKey + " wird in den lokalen Bestand übernommen …");
        int current = epoch;
        Background.run(() -> runtime.workspace().startSync(sourceId, spaceKey),
                started -> {
                    if (current == epoch) {
                        watchSync(runtime, started, current, spaceKey);
                    }
                },
                error -> {
                    if (current == epoch) {
                        sync.setEnabled(true);
                        feedback.problem(error);
                    }
                });
    }

    private void watchSync(AppRuntime runtime, JobStatus started, int startedIn, String spaceKey) {
        if (syncWatch != null) {
            syncWatch.stop();
        }
        Timer timer = new Timer(1000, null);
        syncWatch = timer;
        timer.addActionListener(e -> Background.run(() -> runtime.workspace().job(started.id()), job -> {
            if (syncWatch != timer || !job.state().terminal()) {
                return;
            }
            timer.stop();
            sync.setEnabled(true);
            if (startedIn != epoch) {
                return;
            }
            if (job.state() == JobState.COMPLETED) {
                reload();
                feedback.success("Space " + spaceKey + " liegt jetzt im lokalen Bestand");
            } else {
                feedback.warning("Synchronisierung nicht abgeschlossen: " + Texts.state(job.state()),
                        "Einzelheiten stehen unter Aufträge.");
            }
        }, error -> {
            timer.stop();
            sync.setEnabled(true);
        }));
        timer.start();
    }

    private void openTyped() {
        String sourceId = selectedSource();
        String text = openId.getText().trim();
        if (sourceId == null || text.isEmpty()) {
            return;
        }
        pages.clearSelection();
        if (text.contains("://")) {
            detail.loadByUrl(sourceId, text);
        } else {
            detail.load(sourceId, text, false);
        }
    }

    private String selectedSource() {
        SourceItem item = (SourceItem) source.getSelectedItem();
        return item == null ? null : item.id();
    }

    private String spaceKey() {
        Object item = space.isEditable() ? space.getEditor().getItem() : space.getSelectedItem();
        return item == null ? "" : item.toString().trim();
    }

    private static ComposerToggleButton toggle(String text, String tooltip) {
        ComposerToggleButton button = new ComposerToggleButton(null, text, tooltip);
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        return button;
    }

    /** Für Tests: woher die angezeigten Seiten stammen, {@code null} vor dem ersten Ergebnis. */
    Origin origin() {
        return origin;
    }

    Feedback feedback() {
        return feedback;
    }

    boolean treeAvailable() {
        return asTree.isEnabled();
    }

    void synchronize() {
        startSync();
    }

    void chooseSource(String sourceId) {
        source.setSelectedItem(null);
        for (int i = 0; i < source.getItemCount(); i++) {
            if (source.getItemAt(i).id().equals(sourceId)) {
                source.setSelectedIndex(i);
            }
        }
    }

    PageDetailPanel detail() {
        return detail;
    }

    JTable pages() {
        return pages;
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

    private record Batch(int epoch, String sourceId, String spaceKey, Origin origin) {
        Batch live() {
            return new Batch(epoch, sourceId, spaceKey, Origin.LIVE);
        }
    }

    /** Eine Zeile der Seitenliste. */
    record Row(String pageId, String title, String version, Instant modifiedAt) {
    }

    private record Result(List<Row> rows, String nextCursor, boolean complete, CacheGeneration generation) {

        static Result of(PageListing listing) {
            List<Row> rows = new ArrayList<>();
            for (ListedPage page : listing.pages()) {
                PageMetadata m = page.header().metadata();
                rows.add(new Row(m.pageId(), m.title(), m.version(), m.modifiedAt()));
            }
            return new Result(rows, listing.nextCursor(), listing.complete(), listing.generation());
        }

        static Result of(PageMetadataListing listing) {
            List<Row> rows = new ArrayList<>();
            for (PageMetadataListing.Entry entry : listing.entries()) {
                PageMetadata m = entry.page();
                rows.add(new Row(m.pageId(), m.title(), m.version(), m.modifiedAt()));
            }
            return new Result(rows, listing.nextCursor(), listing.nextCursor() == null, null);
        }
    }

    private static final class PagesModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Geändert"};
        private final List<Row> all = new ArrayList<>();
        private List<Row> visible = new ArrayList<>();
        private String query = "";
        private Batch batch;
        private String spaceKey;

        void clear(String space) {
            all.clear();
            visible = new ArrayList<>();
            batch = null;
            spaceKey = space;
            fireTableDataChanged();
        }

        void append(Batch from, List<Row> rows) {
            batch = from;
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
            return column == 0 ? row.title() : Texts.time(row.modifiedAt());
        }
    }
}
