package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ArtifactRow;
import com.aresstack.spaceharvester.app.ui.controls.PlaceholderList;
import com.aresstack.spaceharvester.application.changelog.UpdateSummary;
import com.aresstack.spaceharvester.application.podcast.PagePodcasts;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * „Aus dieser Seite erzeugt“ (ADR 0047 §5a, Angelo 27.11.2026): die lokalen Artefakte der Auswahl links auf der leeren
 * Seite von „Export“ und dieselben Einträge im ▾-Menü. Eine Seite hat ihren Erklär-Podcast; Ordner, Space und Quelle
 * haben Bereichszusammenfassung und Bereichs-Podcast, der Ordner zusätzlich den Podcast aus dem Buch. Gelesen,
 * neu erzeugt und gelöscht wird über die vorhandenen Ablagen; neu gelesen wird nur auf ihre Meldung, nie per Zeitgeber.
 * Nur auf dem EDT.
 */
final class LocalArtifacts {

    static final String TITLE = "Aus dieser Seite erzeugt";
    static final String NONE = "Zu dieser Auswahl wurde noch nichts erzeugt.";
    static final String SUMMARY = "Zusammenfassung";
    static final String PODCAST = "Podcast";
    static final String BOOK_PODCAST = "Podcast zum Buch";
    static final String OPEN = "Öffnen";
    static final String RENEW = "Neu erzeugen";
    static final String RENEW_TIP = "Erzeugt es neu; das bisherige wird überschrieben";
    static final String DELETE = "Löschen …";
    static final String DELETE_TITLE = "Löschen";
    static final String BUSY_TIP = "Entsteht gerade; löschen geht, sobald es fertig ist";
    static final String STALE_TIP = "Die Grundlage hat sich seither geändert – Klick auf „"
            + com.aresstack.spaceharvester.app.ui.controls.TabHeader.STALE + "“ erzeugt es neu";

    static String deleteQuestion(String name) {
        return "„" + name + "“ wirklich löschen?";
    }

    /** Die Art eines lokalen Artefakts. */
    enum Kind { SUMMARY, PODCAST, BOOK_PODCAST }

    /**
     * Ein lokales Artefakt der Auswahl.
     *
     * @param summary   die Zusammenfassung (bei SUMMARY und dem Bereichs-Podcast), sonst {@code null}
     * @param key       der Schlüssel eines Erklär-Podcasts (Seite oder Buch), sonst {@code null}
     * @param book      das Buch des Buch-Podcasts, sonst {@code null}
     * @param at        Zeitpunkt in Millisekunden, {@code null} ohne
     * @param deletable ob gerade kein Auftrag daran läuft
     */
    record Item(Kind kind, String name, boolean stale, Long at, boolean deletable, UpdateSummary summary, String key,
                PagePodcasts.Book book) {
    }

    /** Was die Einträge lesen und tun; in Tests ersetzbar. Alles außer {@link #subscribe} nie auf dem EDT. */
    interface Actions {
        List<Item> items(ExportPanel.Selection selection);

        void renew(ExportPanel.Selection selection, Item item);

        void delete(ExportPanel.Selection selection, Item item);

        /** Meldet jede Änderung an den Ablagen der Auswahl und ihrer Einträge; die Rückgabe meldet ab. */
        Subscription subscribe(ExportPanel.Selection selection, List<Item> items, Runnable changed);
    }

    private final DefaultListModel<Item> model = new DefaultListModel<>();
    private final PlaceholderList<Item> list = new PlaceholderList<>(model);
    private final JPanel root = new JPanel(new BorderLayout(0, 4));
    private final Actions actions;
    private Runnable openReading = () -> { };
    private Runnable openPodcast = () -> { };
    private ExportPanel.Selection selection;
    private Subscription watch;
    private int generation;
    /** Liest abseits des EDT und meldet auf dem EDT zurück; Tests rechnen gleich. */
    java.util.function.BiConsumer<java.util.concurrent.Callable<List<Item>>, java.util.function.Consumer<List<Item>>>
            background = (work, done) -> Background.run(work, done, failure -> done.accept(List.of()));
    /** Führt ein Neuerzeugen oder Löschen abseits des EDT aus; Tests rechnen gleich. */
    java.util.function.Consumer<Runnable> worker = work -> Background.execute(work::run, () -> { }, failure -> { });
    /** Die „wirklich“-Rückfrage vor dem Löschen; Tests antworten selbst. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) ->
            com.aresstack.spaceharvester.app.ui.Dialogs.confirm(root, title, question);

    LocalArtifacts(AppContext context) {
        this(new WorkspaceActions(context));
    }

    LocalArtifacts(Actions actions) {
        this.actions = Objects.requireNonNull(actions, "actions");
        list.setOpaque(false);
        list.setFont(Comic.regular(13f));
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.placeholder(NONE);
        list.setCellRenderer(new Row());
        list.getAccessibleContext().setAccessibleName(TITLE);
        javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                int row = list.locationToIndex(event.getPoint());
                java.awt.Rectangle cell = row < 0 ? null : list.getCellBounds(row, row);
                if (!SwingUtilities.isLeftMouseButton(event) || cell == null || !cell.contains(event.getPoint())) {
                    return;
                }
                Item item = model.get(row);
                var renderer = (ArtifactRow) list.getCellRenderer().getListCellRendererComponent(list, item, row,
                        list.isSelectedIndex(row), false);
                // „Veraltet“ ist zugleich der Knopf zum Neuerzeugen, wie im Analysebereich.
                if (renderer.onStaleBadge(cell, event.getPoint())) {
                    renew(item);
                } else {
                    open(item);
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
        });
        JLabel heading = new JLabel(TITLE);
        heading.setFont(Comic.semiBold(13f));
        heading.setForeground(Comic.MUTED);
        root.setOpaque(false);
        root.add(heading, BorderLayout.NORTH);
        root.add(list, BorderLayout.CENTER);
    }

    JComponent component() {
        return root;
    }

    /** Wohin „Öffnen“ führt: die Zusammenfassung in die Leseansicht, ein Podcast in die Podcast-Ansicht. */
    void onOpen(Runnable reading, Runnable podcast) {
        openReading = reading == null ? () -> { } : reading;
        openPodcast = podcast == null ? () -> { } : podcast;
    }

    /** Die Auswahl links hat gewechselt; {@code null} ohne. */
    void selection(ExportPanel.Selection selection) {
        if (Objects.equals(selection, this.selection)) {
            return;
        }
        this.selection = selection;
        reload();
    }

    /** Liest die Einträge der Auswahl neu und meldet sich bei ihren Ablagen an. */
    void reload() {
        int mine = ++generation;
        ExportPanel.Selection current = selection;
        if (current == null || current.global()) {
            show(List.of(), current);
            return;
        }
        background.accept(() -> actions.items(current), items -> {
            if (mine == generation) {
                show(items, current);
            }
        });
    }

    private void show(List<Item> items, ExportPanel.Selection current) {
        model.clear();
        items.forEach(model::addElement);
        if (watch != null) {
            watch.close();
            watch = null;
        }
        if (current != null && !current.global()) {
            watch = actions.subscribe(current, items, () -> SwingUtilities.invokeLater(this::reload));
        }
        root.revalidate();
        root.repaint();
    }

    /** Für Tests und das ▾-Menü: die Einträge, wie sie gerade stehen. */
    List<Item> items() {
        List<Item> items = new ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            items.add(model.get(i));
        }
        return items;
    }

    /** Für Tests: die Liste. */
    PlaceholderList<Item> list() {
        return list;
    }

    /**
     * Derselbe Abschnitt im ▾-Menü der Seite (ADR 0047 §5a): unter einer Linie die Überschrift und je Eintrag ein
     * Untermenü mit denselben Aktionen wie der Rechtsklick; ohne Einträge nichts.
     */
    void addTo(JPopupMenu menu) {
        List<Item> items = items();
        if (items.isEmpty()) {
            return;
        }
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }
        JMenuItem heading = new JMenuItem(TITLE);
        heading.setEnabled(false);
        menu.add(heading);
        for (Item item : items) {
            javax.swing.JMenu entry = new javax.swing.JMenu(item.name());
            entry.setIcon(icon(item));
            for (java.awt.Component part : menu(item).getComponents()) {
                if (part instanceof JMenuItem action) {
                    entry.add(action);
                } else {
                    entry.addSeparator();
                }
            }
            menu.add(entry);
        }
    }

    /** Rechtsklick: Öffnen, Neu erzeugen, Löschen mit Rückfrage (nicht, solange es entsteht). */
    JPopupMenu menu(Item item) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem open = new JMenuItem(OPEN);
        open.addActionListener(event -> open(item));
        JMenuItem renew = new JMenuItem(RENEW);
        renew.setToolTipText(RENEW_TIP);
        renew.addActionListener(event -> renew(item));
        renew.setEnabled(item.deletable());
        JMenuItem delete = new JMenuItem(DELETE);
        delete.addActionListener(event -> delete(item));
        delete.setEnabled(item.deletable());
        if (!item.deletable()) {
            renew.setToolTipText(BUSY_TIP);
            delete.setToolTipText(BUSY_TIP);
        }
        menu.add(open);
        menu.add(renew);
        menu.addSeparator();
        menu.add(delete);
        return menu;
    }

    void open(Item item) {
        (item.kind() == Kind.SUMMARY ? openReading : openPodcast).run();
    }

    void renew(Item item) {
        ExportPanel.Selection current = selection;
        if (current != null && item.deletable()) {
            if (item.kind() == Kind.SUMMARY) {
                AreaSummaryView.deletedByUser(current, false);
            }
            worker.accept(() -> actions.renew(current, item));
        }
    }

    void delete(Item item) {
        ExportPanel.Selection current = selection;
        if (current == null || !item.deletable() || !confirm.test(DELETE_TITLE, deleteQuestion(item.name()))) {
            return;
        }
        // Ein gelöschter Überblick entsteht beim nächsten Öffnen nicht von selbst neu.
        if (item.kind() == Kind.SUMMARY) {
            AreaSummaryView.deletedByUser(current, true);
        }
        worker.accept(() -> actions.delete(current, item));
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = list.locationToIndex(event.getPoint());
        if (row < 0 || !list.getCellBounds(row, row).contains(event.getPoint())) {
            return;
        }
        list.setSelectedIndex(row);
        menu(model.get(row)).show(list, event.getX(), event.getY());
    }

    private static Icon icon(Item item) {
        return item.kind() == Kind.SUMMARY ? PageTabIcons.reading() : PageTabIcons.text();
    }

    /** Dieselbe Zeile wie links unter „Artefakte“. */
    private static final class Row implements javax.swing.ListCellRenderer<Item> {
        private final ArtifactRow row = new ArtifactRow();

        @Override
        public java.awt.Component getListCellRendererComponent(javax.swing.JList<? extends Item> owner, Item item,
                                                               int index, boolean selected, boolean focus) {
            String when = item.at() == null ? null
                    : ArtifactRow.when(item.at(), java.time.ZoneId.systemDefault(), java.time.LocalDate.now());
            return row.show(owner, selected, null, icon(item), item.name(), false, item.stale(), when,
                    item.stale() ? item.name() + " · " + STALE_TIP : item.name());
        }
    }

    /** Die vorhandenen Ablagen des Workspace: Zusammenfassungen, Podcasts, Auflagen des Buchs. */
    private static final class WorkspaceActions implements Actions {
        private final AppContext context;

        WorkspaceActions(AppContext context) {
            this.context = context;
        }

        private Workspace workspace() {
            return context.runtime() == null ? null : context.runtime().workspace();
        }

        @Override
        public List<Item> items(ExportPanel.Selection selection) {
            Workspace workspace = workspace();
            if (workspace == null) {
                return List.of();
            }
            List<Item> items = new ArrayList<>();
            if (selection.page()) {
                String key = workspace.pagePodcastKey(selection.sourceId(), selection.pageId());
                workspace.pagePodcast(selection.sourceId(), selection.pageId()).ifPresent(podcast -> items.add(
                        new Item(Kind.PODCAST, PODCAST, false, null, workspace.pagePodcastDeletable(key), null, key,
                                null)));
                return items;
            }
            var core = selection.toCore(context.runtime());
            workspace.areaSummary(core).ifPresent(summary -> {
                items.add(new Item(Kind.SUMMARY, SUMMARY, summary.stale(), summary.createdAt().toEpochMilli(),
                        workspace.areaSummaryDeletable(core), summary, null, null));
                workspace.podcast(summary).ifPresent(podcast -> items.add(new Item(Kind.PODCAST, PODCAST,
                        summary.stale(), null, workspace.podcastDeletable(summary), summary, podcast.key(), null)));
            });
            if (selection instanceof ExportPanel.Selection.Collection folder) {
                book(folder).ifPresent(book -> workspace.bookPodcast(book).ifPresent(podcast -> {
                    String key = workspace.bookPodcastKey(folder.folderId());
                    items.add(new Item(Kind.BOOK_PODCAST, BOOK_PODCAST, false, null,
                            workspace.pagePodcastDeletable(key), null, key, book));
                }));
            }
            return items;
        }

        private java.util.Optional<PagePodcasts.Book> book(ExportPanel.Selection.Collection folder) {
            java.nio.file.Path data = context.settings() == null || context.settings().storage() == null ? null
                    : context.settings().storage().dataDirectory();
            if (data == null) {
                return java.util.Optional.empty();
            }
            return com.aresstack.spaceharvester.app.research.BookEditions.latest(data, folder.folderId())
                    .map(edition -> new PagePodcasts.Book(folder.folderId(), folder.title(), edition.book()));
        }

        @Override
        public void renew(ExportPanel.Selection selection, Item item) {
            Workspace workspace = workspace();
            if (workspace == null) {
                return;
            }
            switch (item.kind()) {
                case SUMMARY -> workspace.summarizeArea(selection.toCore(context.runtime()), true);
                case PODCAST -> {
                    if (item.summary() != null) {
                        workspace.renewPodcast(item.summary());
                    } else {
                        workspace.deletePagePodcast(item.key());
                        workspace.startPagePodcast(selection.sourceId(), selection.pageId());
                    }
                }
                case BOOK_PODCAST -> {
                    workspace.deletePagePodcast(item.key());
                    workspace.startBookPodcast(item.book());
                }
            }
        }

        @Override
        public void delete(ExportPanel.Selection selection, Item item) {
            Workspace workspace = workspace();
            if (workspace == null) {
                return;
            }
            switch (item.kind()) {
                case SUMMARY -> workspace.deleteAreaSummary(selection.toCore(context.runtime()));
                case PODCAST -> {
                    if (item.summary() != null) {
                        workspace.deletePodcast(item.summary());
                    } else {
                        workspace.deletePagePodcast(item.key());
                    }
                }
                case BOOK_PODCAST -> workspace.deletePagePodcast(item.key());
            }
        }

        @Override
        public Subscription subscribe(ExportPanel.Selection selection, List<Item> items, Runnable changed) {
            Workspace workspace = workspace();
            if (workspace == null) {
                return () -> { };
            }
            List<Subscription> all = new ArrayList<>();
            // Zusammenfassungen melden sich über das Änderungsprotokoll ihrer Quellen.
            selection.roots(context.runtime()).stream()
                    .map(com.aresstack.spaceharvester.application.holdings.Selection.Root::sourceId).distinct()
                    .forEach(sourceId -> all.add(workspace.subscribeChangeLog(sourceId, ignored -> changed.run())));
            if (selection.page()) {
                all.add(workspace.subscribePagePodcast(workspace.pagePodcastKey(selection.sourceId(),
                        selection.pageId()), changed));
            }
            // Der Podcast einer Zusammenfassung und der des Buchs melden sich auch, bevor es sie gibt.
            for (Item item : items) {
                if (item.kind() == Kind.SUMMARY) {
                    all.add(workspace.subscribePodcast(
                            com.aresstack.spaceharvester.application.podcast.UpdatePodcasts.key(item.summary()),
                            changed));
                }
            }
            if (selection instanceof ExportPanel.Selection.Collection folder) {
                all.add(workspace.subscribePagePodcast(workspace.bookPodcastKey(folder.folderId()), changed));
            }
            return () -> all.forEach(Subscription::close);
        }
    }

}
