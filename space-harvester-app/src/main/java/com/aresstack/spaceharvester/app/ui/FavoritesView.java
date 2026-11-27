package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.spaceharvester.app.ui.controls.FolderTree;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTree;
import javax.swing.JScrollPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Die Favoritenansicht des Explorers (ADR 0034): der Ordnerbaum der Favoriten, den der Stern-Umschalter unten rechts an
 * Stelle des Seitenbaums zeigt. Darstellung und Bedienung über das generische {@link FolderTree} (Ziehen mit
 * Einfügemarke, „Verschieben nach …“, F2, Entf, Rückfrage nur bei Ordnern mit Inhalt); jede Änderung geht an
 * {@link Favorites}, der Baum baut sich auf dessen Meldung neu auf. „Neuer Ordner“ steht als Symbolknopf unten in der Fußleiste des Explorers ({@link #newFolderButton()}).
 *
 * <p>Ein Klick oder Enter auf einen Favoriten öffnet ihn wie bisher; auf einen Ordner wählt ihn als Sammlung (die
 * Mitte und rechts folgen, {@link #onFolder}). Ein neuer Stern landet im gewählten Ordner ({@link Favorites#target}),
 * sonst oben. Der Baum markiert den Favoriten des im Seitenbaum gewählten Knotens.
 */
final class FavoritesView {

    static final String NEW_FOLDER_TIP = "Neuer Ordner";

    private final AppContext context;
    /** Der im Baum gewählte Knoten, damit die Ansicht dessen Favoriten markiert; null ohne Auswahl. */
    private final Supplier<Explorer.Item> current;
    /** Zeigt eine ganze Quelle im Baum; Mitte und rechts folgen der Auswahl. */
    private final Consumer<String> revealSource;
    private final FolderTree<Favorites.Entry> tree;
    private final IconButton newFolder = new IconButton(
            com.aresstack.spaceharvester.app.ui.deck.FilesIcons.newFolder(), NEW_FOLDER_TIP);
    private final JPanel component = new JPanel(new BorderLayout());
    /** Ordner gewählt: ID und Name; die Auswahl als Sammlung baut der Empfänger (ADR 0034, eine Auflösung). */
    private BiConsumer<String, String> onFolder = (id, name) -> { };
    /** „Buch schreiben …“ am Korb-Ordner (Buchfunktion Phase 4, Arbeitszweig). */
    private final BookFlow book;

    FavoritesView(AppContext context, Supplier<Explorer.Item> current, Consumer<String> revealSource) {
        this.context = Objects.requireNonNull(context, "context");
        this.current = Objects.requireNonNull(current, "current");
        this.revealSource = Objects.requireNonNull(revealSource, "revealSource");
        this.book = new BookFlow(context, component);
        Favorites favorites = context.favorites();
        tree = new FolderTree<>(new FolderTree.Shape<>() {
            @Override
            public String id(Favorites.Entry entry) {
                return entry.id();
            }

            @Override
            public boolean isFolder(Favorites.Entry entry) {
                return entry instanceof Favorites.Folder;
            }

            @Override
            public List<Favorites.Entry> children(Favorites.Entry entry) {
                return entry instanceof Favorites.Folder folder ? folder.children() : List.of();
            }

            @Override
            public String name(Favorites.Entry entry) {
                return switch (entry) {
                    case Favorites.Folder folder -> folder.name();
                    case Favorites.Bookmark bookmark -> bookmark.favorite().title();
                };
            }
        }, new FolderTree.Actions() {
            @Override
            public String createFolder(String parentId, String name) {
                return favorites.createFolder(name, parentId);
            }

            @Override
            public void renameFolder(String id, String name) {
                favorites.renameFolder(id, name);
            }

            @Override
            public void remove(String id) {
                favorites.remove(id);
            }

            @Override
            public boolean move(List<String> ids, String targetFolderId, int index) {
                return favorites.moveAll(ids, targetFolderId, index);
            }
        });
        favorites.target(() -> SwingUtilities.isEventDispatchThread() ? tree.targetFolderId() : null);
        JTree view = tree.component();
        view.getAccessibleContext().setAccessibleName("Favoriten");
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree owner, Object value, boolean selected,
                                                          boolean expanded, boolean leaf, int row, boolean focus) {
                super.getTreeCellRendererComponent(owner, value, selected, expanded, leaf, row, focus);
                switch (tree.entryOf(value)) {
                    case Favorites.Folder folder -> {
                        setText(folder.name());
                        setIcon(SidebarIcons.folder());
                    }
                    case Favorites.Bookmark bookmark -> {
                        Favorites.Favorite favorite = bookmark.favorite();
                        // Der Favorit selbst in Tinte, seine Eltern als Pfad gedämpft darunter.
                        setText("<html>" + Explorer.escape(favorite.title()) + "<br><font color='#7a7a7a'>"
                                + Explorer.escape(favorite.where()) + "</font></html>");
                        setIcon(favorite.isSource() ? MainWindow.database()
                                : favorite.pageId() == null ? SidebarIcons.folder() : SidebarIcons.page());
                    }
                    case null -> setText("");
                }
                setFont(Comic.regular(13f));
                setBackgroundSelectionColor(Comic.YELLOW);
                setTextSelectionColor(Comic.INK);
                setBorderSelectionColor(null);
                setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 2));
                return this;
            }
        });
        view.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (SwingUtilities.isLeftMouseButton(event) && event.getClickCount() == 1
                        && !event.isControlDown() && !event.isShiftDown()
                        && view.getRowForLocation(event.getX(), event.getY()) >= 0) {
                    open();
                }
            }
        });
        view.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "favorite-open");
        view.getActionMap().put("favorite-open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                open();
            }
        });
        newFolder.addActionListener(e -> tree.createFolder());
        tree.folderItems((id, menu) -> {
            javax.swing.JMenuItem fill = new javax.swing.JMenuItem(BasketResearchForm.TITLE + " …");
            fill.setToolTipText("Recherche im Wissensbestand legt passende Seiten als Favoriten in diesen Ordner");
            fill.addActionListener(e -> fillBasket(id));
            menu.add(fill);
            javax.swing.JMenuItem write = new javax.swing.JMenuItem(BasketResearchForm.BOOK_TITLE + " …");
            write.setToolTipText("Aus den Seiten dieses Ordners entsteht ein Buch zum Auftrag");
            write.addActionListener(e -> book.start(id));
            menu.add(write);
            if (book.prepared(id)) {
                javax.swing.JMenuItem choose = new javax.swing.JMenuItem(BookTermsView.TITLE + " …");
                choose.setToolTipText("Die vorbereiteten Begriffe dieses Ordners wählen und das Buch schreiben");
                choose.addActionListener(e -> book.choose(id));
                menu.add(choose);
            }
        });
        component.setOpaque(false);
        JScrollPane scroll = Comic.scroll(view);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        component.add(scroll, BorderLayout.CENTER);
    }

    JComponent component() {
        return component;
    }

    /**
     * „Korb füllen“ (Buchfunktion Phase 3, Arbeitszweig): fragt Auftrag und Weidezaun und startet die Recherche als
     * Auftrag; der Ordner ist der Korb. Nur auf diesen Klick, nie im Hintergrund.
     */
    private void fillBasket(String folderId) {
        Favorites favorites = context.favorites();
        String name = favorites.folder(folderId).map(Favorites.Folder::name).orElse("Ordner");
        var chat = BookFlow.chatModel(context, component, BasketResearchForm.TITLE);
        if (chat.isEmpty()) {
            return;
        }
        BasketResearchForm form = new BasketResearchForm();
        book.notes(folderId).ifPresent(notes -> form.prefill(notes.fence(), notes.targetWords()));
        var fence = form.ask(component, name);
        if (fence.isEmpty()) {
            return;
        }
        // Der Weidezaun bleibt am Ordner, damit „Buch schreiben …“ ihn vorab zeigt.
        BookFlow.rememberFence(context, folderId, fence.get(), book.notes(folderId).map(
                com.aresstack.spaceharvester.app.research.BookNotes::targetWords).orElse(0));
        var workspace = context.runtime().workspace();
        int capacity = BookFlow.capacity(context);
        var basket = new com.aresstack.spaceharvester.app.research.FavoritesBasket(favorites, folderId,
                context::sourceTitle);
        // Sprache der Oberfläche.
        var task = com.aresstack.spaceharvester.app.research.BasketResearchJob.task(chat.get(),
                new com.aresstack.spaceharvester.app.research.WorkspacePageStock(workspace, null), basket, name,
                fence.get(), SearchView.LIMIT,
                com.aresstack.research4j.basket.BasketResearch.Settings.defaults("de", capacity, capacity));
        workspace.startBasketResearch(task);
    }


    /** „Neuer Ordner“ für die Fußleiste des Explorers (Angelo 24.11.: unten in der Leiste, keine eigene Leiste oben). */
    IconButton newFolderButton() {
        return newFolder;
    }

    /** Ordner gewählt: bekommt ID und Namen, um die Sammlung zu wählen (ADR 0034 Abschnitt 3). */
    void onFolder(BiConsumer<String, String> listener) {
        this.onFolder = Objects.requireNonNull(listener, "listener");
    }

    /** Liest den Baum neu ein und markiert den aktuellen Favoriten; liefert, ob es keine Favoriten gibt. */
    boolean reload() {
        List<Favorites.Entry> entries = context.favorites().tree();
        tree.setRoots(entries);
        markCurrent();
        return entries.isEmpty();
    }

    boolean isEmpty() {
        return context.favorites().tree().isEmpty();
    }

    /**
     * Markiert den Favoriten des im Baum gewählten Knotens, also der geöffneten Seite. Ohne diese Markierung sah ein
     * Klick auf den Favoriten der schon offenen Seite so aus, als passiere nichts. Ein gewählter Ordner bleibt
     * gewählt, solange der Seitenbaum nichts markiert, das ein Favorit ist.
     */
    void markCurrent() {
        Explorer.Item item = current.get();
        if (item == null) {
            if (!(selected() instanceof Favorites.Folder)) {
                tree.component().clearSelection();
            }
            return;
        }
        String id = find(context.favorites().tree(), item);
        if (id != null) {
            tree.select(List.of(id));
        } else if (!(selected() instanceof Favorites.Folder)) {
            tree.component().clearSelection();
        }
    }

    private static String find(List<Favorites.Entry> entries, Explorer.Item item) {
        for (Favorites.Entry entry : entries) {
            switch (entry) {
                case Favorites.Bookmark bookmark -> {
                    if (matches(bookmark.favorite(), item)) {
                        return bookmark.id();
                    }
                }
                case Favorites.Folder folder -> {
                    String found = find(folder.children(), item);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    static boolean matches(Favorites.Favorite favorite, Explorer.Item item) {
        return switch (item) {
            case Explorer.SourceItem source -> favorite.isSource() && favorite.sourceId().equals(source.sourceId());
            case Explorer.SpaceItem space -> !favorite.isSource() && favorite.pageId() == null
                    && favorite.sourceId().equals(space.sourceId())
                    && Objects.equals(favorite.spaceKey(), space.spaceKey());
            case Explorer.PageItem page -> favorite.sourceId().equals(page.sourceId())
                    && page.pageId().equals(favorite.pageId());
            default -> false;
        };
    }

    private Favorites.Entry selected() {
        List<Favorites.Entry> selection = tree.selection();
        return selection.size() == 1 ? selection.get(0) : null;
    }

    /** Ein Favorit: die Seite öffnen bzw. den Space zeigen; ein Ordner: als Sammlung wählen. */
    private void open() {
        switch (selected()) {
            case Favorites.Folder folder -> onFolder.accept(folder.id(), folder.name());
            case Favorites.Bookmark bookmark -> {
                Favorites.Favorite favorite = bookmark.favorite();
                if (favorite.isSource()) {
                    revealSource.accept(favorite.sourceId());
                } else if (favorite.pageId() == null) {
                    context.navigator().openSpace(favorite.sourceId(), favorite.spaceKey());
                } else {
                    context.navigator().openPage(favorite.sourceId(), favorite.pageId());
                }
            }
            case null -> {
            }
        }
    }

    /** Für Tests: der Ordnerbaum. */
    FolderTree<Favorites.Entry> tree() {
        return tree;
    }

    /** Für Tests: die Favoriten in Baumreihenfolge, wie die Ansicht sie zeigt. */
    List<Favorites.Favorite> shown() {
        List<Favorites.Favorite> out = new ArrayList<>();
        collect(context.favorites().tree(), out);
        return out;
    }

    private static void collect(List<Favorites.Entry> entries, List<Favorites.Favorite> out) {
        for (Favorites.Entry entry : entries) {
            switch (entry) {
                case Favorites.Bookmark bookmark -> out.add(bookmark.favorite());
                case Favorites.Folder folder -> collect(folder.children(), out);
            }
        }
    }

    /** Für Tests: der markierte Favorit, null ohne oder bei einem Ordner. */
    Favorites.Favorite marked() {
        return selected() instanceof Favorites.Bookmark bookmark ? bookmark.favorite() : null;
    }
}
