package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifact;
import com.aresstack.spaceharvester.app.chat.artifact.ChatArtifactStore;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JList;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Die globalen Artefakte des aktiven Projekts links unter „Artefakte“ (ADR 0047 §5, Angelo 27.11.2026): Dokumente,
 * Grafiken und Bücher aus dem Speicher des Projekts, ohne geladenen Chat; lokale Artefakte einer Seite (Zusammenfassung,
 * Podcast) stehen hier nicht. Neueste Änderung zuerst, das Filterfeld der Leiste filtert nach Titel. Ein Klick öffnet
 * das Artefakt in der Mitte. Neu gelesen wird nur auf Meldung (Wechsel des Projekts, Änderung am Speicher, Neustart der
 * App), nie per Zeitgeber. Nur auf dem EDT.
 */
final class ArtifactList {

    static final String EMPTY = "In diesem Projekt gibt es noch keine Artefakte.";
    static final String NOTHING_FOUND = "Kein Artefakt mit diesem Titel";
    static final String OPEN = "Öffnen";
    static final String RENAME = "Umbenennen …";
    static final String RENAME_TITLE = "Artefakt umbenennen";
    static final String DELETE = "Löschen …";
    static final String EXPORT = "Ausgeben";
    static final String RESTORE = "Wiederherstellen";
    static final String RESTORE_TIP = "Gelöscht – Klick stellt das Artefakt wieder her (bis zum nächsten Start)";
    static final String SHOW_FOLDER = "Ordner in den Wissensquellen zeigen";
    static final String GROUP_BY_KIND = "Nach Art gruppieren";
    /** Die Gruppen heißen wie die Arten in der App (ADR 0047 §5a), in dieser Reihenfolge. */
    static final String DOCUMENTS = "Dokumente";
    static final String GRAPHICS = "Grafiken";
    static final String BOOKS = "Bücher";
    private static final List<String> GROUPS = List.of(DOCUMENTS, GRAPHICS, BOOKS);
    static final String DELETED = "Gelöscht";
    /** Einmal für die ganze App gemerkt, nicht je Projekt oder Liste (Angelo 27.11.2026). */
    static final String GROUPED_KEY = "artifacts.groupByKind";
    static final String STALE_TIP = Texts.BOOK_STALE + " – Klick auf „" + com.aresstack.spaceharvester.app.ui
            .controls.TabHeader.STALE + "“ erzeugt eine neue Auflage";

    private final AppContext context;
    private final DefaultListModel<ChatArtifact> model = new DefaultListModel<>();
    /** So breit wie der sichtbare Bereich: lange Titel enden mit „…“, und die Zeit weicht zuerst. */
    private final JList<ChatArtifact> list = new JList<>(model) {
        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }
    };
    private final javax.swing.JScrollPane scroll;
    private final Consumer<ChatArtifactStore.Event> changed = event -> SwingUtilities.invokeLater(this::reload);
    private ChatArtifactStore store;
    private Consumer<String> open = id -> { };
    /** „Ausgeben ▸“: Kennung und Format; {@code null} als Format heißt SVG. */
    private java.util.function.BiConsumer<String, com.aresstack.spaceharvester.app.chat.ChatExport.Format> export =
            (id, format) -> { };
    private Consumer<String> note = text -> { };
    private String filter = "";
    private List<ChatArtifact> all = List.of();
    /** Gelöschte bis zum nächsten Start, durchgestrichen am Ende der Liste wie gelöschte Chats. */
    private List<ChatArtifact> trash = List.of();
    private final java.util.Set<String> trashed = new java.util.HashSet<>();
    /** Bücher: ihr Favoritenordner und ob die neueste Auflage veraltet ist; im Hintergrund gelesen. */
    private final java.util.Map<String, String> bookFolders = new java.util.HashMap<>();
    private final java.util.Set<String> staleBooks = new java.util.HashSet<>();
    private int bookCheck;
    private Consumer<String> newEdition = folderId -> { };
    private Consumer<String> showFolder = folderId -> { };
    /** Hält die Meldung neuer Auflagen, solange die Liste lebt. */
    @SuppressWarnings("unused")
    private final AutoCloseable editions;
    /** Liest Dateien abseits des EDT und meldet auf dem EDT zurück; Tests rechnen gleich. */
    java.util.function.BiConsumer<java.util.concurrent.Callable<BookStates>, Consumer<BookStates>> background =
            (work, done) -> Background.run(work, done, failure -> { });
    /** Die „wirklich“-Rückfrage (Ja/Nein) vor dem Löschen; Tests antworten selbst. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) ->
            Dialogs.confirm(list, title, question);
    /** Fragt den neuen Titel; {@code null} heißt abgebrochen. Tests antworten selbst. */
    java.util.function.UnaryOperator<String> askTitle = current ->
            Dialogs.askText(list, RENAME_TITLE, "Neuer Titel:", current);

    ArtifactList(AppContext context) {
        this.context = context;
        list.setOpaque(false);
        list.setFont(Comic.regular(13f));
        list.setSelectionBackground(Comic.YELLOW);
        list.setSelectionForeground(Comic.INK);
        list.setCellRenderer(new Row(trashed, staleBooks, this::groupOf));
        list.getAccessibleContext().setAccessibleName("Artefakte");
        javax.swing.ToolTipManager.sharedInstance().registerComponent(list);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int row = list.locationToIndex(event.getPoint());
                String folderId = row < 0 ? null : bookFolders.get(model.get(row).id());
                // „Veraltet“ am Buch ist zugleich der Knopf, wie im Analysebereich.
                if (folderId != null && onStaleBadge(row, event.getPoint())) {
                    newEdition.accept(folderId);
                } else {
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
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_DELETE, 0), "delete");
        list.getActionMap().put("delete", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                delete(list.getSelectedValue());
            }
        });
        list.getInputMap().put(javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ENTER, 0), "open");
        list.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent event) {
                openSelected();
            }
        });
        scroll = Comic.scroll(list);
        context.projects().addListener(() -> SwingUtilities.invokeLater(this::reload));
        context.onChange(ignored -> reload());
        // Eine neue Auflage macht das Buch wieder aktuell (oder legt es an).
        editions = com.aresstack.spaceharvester.app.research.BookArtifacts.onChange(
                folderId -> SwingUtilities.invokeLater(this::reload));
    }

    /** Ob nach Art gruppiert wird (Schalter unten, gemerkt in {@link AppState}). */
    boolean grouped() {
        return AppState.shared().getBoolean(GROUPED_KEY, false);
    }

    /** Schaltet das Gruppieren nach Art und merkt es für die ganze App. */
    void grouped(boolean grouped) {
        AppState.shared().put(GROUPED_KEY, grouped);
        show();
    }

    /**
     * Die Überschrift samt Zahl, wenn nach Art gruppiert wird und mit der Zeile eine neue Gruppe beginnt, sonst
     * {@code null}: „Dokumente“, „Grafiken“, „Bücher“, am Ende „Gelöscht“. Eine Art ohne Artefakte hat keine Gruppe.
     */
    private String groupOf(int index) {
        if (!grouped() || index < 0 || index >= model.size()) {
            return null;
        }
        String group = kindGroup(model.get(index));
        if (index > 0 && group.equals(kindGroup(model.get(index - 1)))) {
            return null;
        }
        int count = 0;
        for (int i = index; i < model.size() && group.equals(kindGroup(model.get(i))); i++) {
            count++;
        }
        return group + " (" + count + ")";
    }

    private String kindGroup(ChatArtifact artifact) {
        if (trashed.contains(artifact.id())) {
            return DELETED;
        }
        if (artifact.id().startsWith(ChatArtifactStore.BOOK)) {
            return BOOKS;
        }
        return artifact.kind() == ChatArtifact.Kind.GRAPHIC ? GRAPHICS : DOCUMENTS;
    }

    /** Was „Neue Auflage erzeugen“ und „Ordner in den Wissensquellen zeigen“ am Buch tun (Ordner-ID). */
    void books(Consumer<String> newEdition, Consumer<String> showFolder) {
        this.newEdition = newEdition == null ? folderId -> { } : newEdition;
        this.showFolder = showFolder == null ? folderId -> { } : showFolder;
    }

    JComponent component() {
        return scroll;
    }

    /**
     * Der Speicher der Artefakte (derselbe wie der des Chats, damit jede Änderung hier ankommt) und was ein Klick
     * öffnet.
     */
    void connect(ChatArtifactStore store, Consumer<String> open,
                 java.util.function.BiConsumer<String, com.aresstack.spaceharvester.app.chat.ChatExport.Format> export) {
        this.export = export == null ? (id, format) -> { } : export;
        if (this.store != null) {
            this.store.removeListener(changed);
        }
        this.store = store;
        this.open = open == null ? id -> { } : open;
        if (store != null) {
            store.addListener(changed);
        }
        reload();
    }

    /** Wohin der Hinweis unter der Liste geht (die Fußleiste der Seitenleiste). */
    void onNote(Consumer<String> note) {
        this.note = note == null ? text -> { } : note;
    }

    /** Filtert nach Titel, ohne Groß- und Kleinschreibung; leer zeigt alle. */
    void filter(String text) {
        filter = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        show();
    }

    /** Liest die Artefakte des aktiven Projekts neu. */
    void reload() {
        if (store == null) {
            all = List.of();
            trash = List.of();
        } else {
            trash = store.deletedInProject(projectId());
            all = store.listInProject(projectId()).stream()
                    .sorted(Comparator.comparingLong(ArtifactList::changedAt).reversed()
                            .thenComparing(ChatArtifact::id))
                    .toList();
        }
        show();
        checkBooks();
    }

    /** Ordner und Stand der Bücher; nach einer Auflage oder einem Wechsel des Projekts neu. */
    record BookStates(java.util.Map<String, String> folders, java.util.Set<String> stale) {
    }

    private void checkBooks() {
        List<String> books = all.stream().map(ChatArtifact::id).filter(id -> id.startsWith(ChatArtifactStore.BOOK))
                .toList();
        int mine = ++bookCheck;
        var artifacts = context.bookArtifacts();
        if (books.isEmpty() || artifacts == null) {
            applyBooks(new BookStates(java.util.Map.of(), java.util.Set.of()));
            return;
        }
        String projectId = projectId();
        background.accept(() -> {
            java.util.Map<String, String> folders = new java.util.HashMap<>();
            java.util.Set<String> stale = new java.util.HashSet<>();
            for (String id : books) {
                String folderId = artifacts.folderOf(projectId, id);
                if (folderId != null) {
                    folders.put(id, folderId);
                    if (artifacts.stale(folderId)) {
                        stale.add(id);
                    }
                }
            }
            return new BookStates(folders, stale);
        }, states -> {
            if (mine == bookCheck) {
                applyBooks(states);
            }
        });
    }

    private void applyBooks(BookStates states) {
        bookFolders.clear();
        bookFolders.putAll(states.folders());
        staleBooks.clear();
        staleBooks.addAll(states.stale());
        // Das Abzeichen macht die Zeile höher; ein Setzen der Buchzeilen lässt die Liste die Höhen neu messen.
        for (int i = 0; i < model.size(); i++) {
            if (model.get(i).id().startsWith(ChatArtifactStore.BOOK)) {
                model.set(i, model.get(i));
            }
        }
        list.repaint();
    }

    /** Für Tests: die Bücher mit veralteter neuester Auflage. */
    java.util.Set<String> staleBooks() {
        return java.util.Set.copyOf(staleBooks);
    }

    /** Ob der Punkt in der Zeile auf dem Abzeichen „Veraltet“ liegt. */
    boolean onStaleBadge(int row, java.awt.Point point) {
        java.awt.Rectangle cell = list.getCellBounds(row, row);
        if (cell == null || !cell.contains(point) || !staleBooks.contains(model.get(row).id())) {
            return false;
        }
        Row renderer = (Row) list.getCellRenderer().getListCellRendererComponent(list, model.get(row), row,
                list.isSelectedIndex(row), false);
        renderer.setBounds(0, 0, cell.width, cell.height);
        renderer.doLayout();
        renderer.line.doLayout();
        renderer.side.doLayout();
        java.awt.Rectangle badge = SwingUtilities.convertRectangle(renderer.side, renderer.stale.getBounds(), renderer);
        return renderer.stale.isVisible() && badge.contains(point.x - cell.x, point.y - cell.y);
    }

    /** Für Tests: die gezeigten Kennungen in ihrer Reihenfolge. */
    List<String> shownIds() {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < model.size(); i++) {
            ids.add(model.get(i).id());
        }
        return ids;
    }

    /** Für Tests: die Liste selbst. */
    JList<ChatArtifact> list() {
        return list;
    }

    private void show() {
        String keep = list.getSelectedValue() == null ? null : list.getSelectedValue().id();
        model.clear();
        trashed.clear();
        trash.forEach(artifact -> trashed.add(artifact.id()));
        // Gruppiert: Dokumente, Grafiken, Bücher, je die neueste Änderung zuerst; Gelöschte am Ende.
        List<ChatArtifact> live = !grouped() ? all : all.stream()
                .sorted(Comparator.comparingInt(artifact -> GROUPS.indexOf(kindGroup(artifact)))).toList();
        for (ChatArtifact artifact : java.util.stream.Stream.concat(live.stream(), trash.stream()).toList()) {
            if (filter.isEmpty() || artifact.title().toLowerCase(Locale.ROOT).contains(filter)) {
                model.addElement(artifact);
                if (artifact.id().equals(keep)) {
                    list.setSelectedIndex(model.size() - 1);
                }
            }
        }
        note.accept(all.isEmpty() ? EMPTY : model.isEmpty() ? NOTHING_FOUND : " ");
    }

    /** Das Rechtsklick-Menü: nur, was für die Art gilt (Entwurf UX 27.11.2026). */
    javax.swing.JPopupMenu menu(ChatArtifact artifact) {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (trashed.contains(artifact.id())) {
            javax.swing.JMenuItem restoreItem = new javax.swing.JMenuItem(RESTORE);
            restoreItem.addActionListener(event -> restore(artifact));
            menu.add(restoreItem);
            return menu;
        }
        javax.swing.JMenuItem openItem = new javax.swing.JMenuItem(OPEN);
        openItem.addActionListener(event -> open.accept(artifact.id()));
        javax.swing.JMenuItem renameItem = new javax.swing.JMenuItem(RENAME);
        renameItem.addActionListener(event -> rename(artifact));
        javax.swing.JMenuItem deleteItem = new javax.swing.JMenuItem(DELETE);
        deleteItem.addActionListener(event -> delete(artifact));
        menu.add(openItem);
        menu.add(exportMenu(artifact));
        menu.add(renameItem);
        String folderId = bookFolders.get(artifact.id());
        if (artifact.id().startsWith(ChatArtifactStore.BOOK)) {
            javax.swing.JMenuItem editionItem = new javax.swing.JMenuItem(Texts.NEW_EDITION);
            editionItem.setToolTipText(Texts.NEW_EDITION_TIP);
            editionItem.addActionListener(event -> newEdition.accept(folderId));
            editionItem.setEnabled(folderId != null);
            javax.swing.JMenuItem folderItem = new javax.swing.JMenuItem(SHOW_FOLDER);
            folderItem.addActionListener(event -> showFolder.accept(folderId));
            folderItem.setEnabled(folderId != null);
            menu.addSeparator();
            menu.add(editionItem);
            menu.add(folderItem);
        }
        menu.addSeparator();
        menu.add(deleteItem);
        return menu;
    }

    /** „Ausgeben ▸“ mit den Formaten der Art: beim Dokument Markdown, Word, PDF, bei der Grafik SVG. */
    private javax.swing.JMenu exportMenu(ChatArtifact artifact) {
        javax.swing.JMenu menu = new javax.swing.JMenu(EXPORT);
        if (artifact.kind() == ChatArtifact.Kind.GRAPHIC) {
            javax.swing.JMenuItem svg = new javax.swing.JMenuItem("SVG");
            svg.addActionListener(event -> export.accept(artifact.id(), null));
            menu.add(svg);
            return menu;
        }
        for (com.aresstack.spaceharvester.app.chat.ChatExport.Format format
                : com.aresstack.spaceharvester.app.chat.ChatExport.Format.values()) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(format.label);
            item.addActionListener(event -> export.accept(artifact.id(), format));
            menu.add(item);
        }
        return menu;
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

    /** Benennt um; der Titel ist keine Revision. */
    void rename(ChatArtifact artifact) {
        if (artifact == null || store == null) {
            return;
        }
        String title = askTitle.apply(artifact.title());
        if (title != null && !title.isBlank() && !title.strip().equals(artifact.title())) {
            store.renameInProject(projectId(), artifact.id(), title.strip());
        }
    }

    /** Löscht nach Rückfrage; bis zum nächsten Start im Papierkorb. */
    void delete(ChatArtifact artifact) {
        if (artifact == null || store == null || trashed.contains(artifact.id())
                || !confirm.test(Texts.ARTIFACT_DELETE_TITLE, Texts.artifactDeleteQuestion(artifact.title()))) {
            return;
        }
        store.deleteInProject(projectId(), artifact.id());
    }

    private String projectId() {
        return context.projects().active() == null ? null : context.projects().active().id();
    }

    private void openSelected() {
        ChatArtifact artifact = list.getSelectedValue();
        if (artifact == null) {
            return;
        }
        if (trashed.contains(artifact.id())) {
            restore(artifact);
        } else {
            open.accept(artifact.id());
        }
    }

    /** Holt ein gelöschtes Artefakt zurück und öffnet es. */
    void restore(ChatArtifact artifact) {
        if (artifact == null || store == null) {
            return;
        }
        ChatArtifact restored = store.restoreInProject(projectId(), artifact.id());
        if (restored != null) {
            open.accept(restored.id());
        }
    }

    /** Zeitpunkt des neuesten Stands. */
    private static long changedAt(ChatArtifact artifact) {
        return artifact.revisions().isEmpty() ? artifact.createdAt() : artifact.current().at();
    }

    /** Wann zuletzt geändert: heute als Uhrzeit, sonst als Datum. */
    static String when(long millis, java.time.ZoneId zone, java.time.LocalDate today) {
        java.time.ZonedDateTime at = Instant.ofEpochMilli(millis).atZone(zone);
        return at.toLocalDate().equals(today) ? TODAY.format(at) : DAY.format(at);
    }

    private static final java.time.format.DateTimeFormatter TODAY =
            java.time.format.DateTimeFormatter.ofPattern("HH:mm", Locale.GERMANY);
    private static final java.time.format.DateTimeFormatter DAY =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.", Locale.GERMANY);

    /**
     * Eine Zeile, einzeilig: Symbol der Art, Titel (gekürzt mit „…“, voll im Tooltip), Zeit rechts gedämpft. Reicht die
     * Breite nicht, weicht zuerst die Zeit, dann wird der Titel gekürzt.
     */
    private static final class Row extends javax.swing.JPanel implements javax.swing.ListCellRenderer<ChatArtifact> {
        private final javax.swing.JLabel title = new javax.swing.JLabel();
        private final javax.swing.JLabel time = new javax.swing.JLabel();
        /** „Veraltet“ am Buch, dasselbe Abzeichen wie im Analysebereich. */
        final javax.swing.JLabel stale = com.aresstack.spaceharvester.app.ui.controls.TabHeader.staleBadge();
        final javax.swing.JPanel side = new javax.swing.JPanel(new java.awt.BorderLayout(6, 0));
        private final java.util.Set<String> trashed;
        private final java.util.Set<String> staleBooks;
        private final java.util.function.IntFunction<String> groupOf;
        /** Überschrift über der ersten Zeile einer Gruppe (nur gruppiert). */
        private final javax.swing.JLabel header = new javax.swing.JLabel();
        final javax.swing.JPanel line = new javax.swing.JPanel(new java.awt.BorderLayout(6, 0));

        Row(java.util.Set<String> trashed, java.util.Set<String> staleBooks,
            java.util.function.IntFunction<String> groupOf) {
            super(new java.awt.BorderLayout(0, 2));
            this.trashed = trashed;
            this.staleBooks = staleBooks;
            this.groupOf = groupOf;
            header.setForeground(Comic.MUTED);
            header.setFont(Comic.semiBold(12f));
            line.setOpaque(false);
            line.setBorder(BorderFactory.createEmptyBorder(3, 2, 3, 6));
            header.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
            title.setIconTextGap(6);
            time.setForeground(Comic.MUTED);
            side.setOpaque(false);
            side.add(stale, java.awt.BorderLayout.WEST);
            side.add(time, java.awt.BorderLayout.EAST);
            line.add(title, java.awt.BorderLayout.CENTER);
            line.add(side, java.awt.BorderLayout.EAST);
            add(header, java.awt.BorderLayout.NORTH);
            add(line, java.awt.BorderLayout.CENTER);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends ChatArtifact> owner, ChatArtifact artifact,
                                                      int index, boolean selected, boolean focus) {
            String group = groupOf.apply(index);
            header.setText(group);
            header.setVisible(group != null);
            title.setFont(owner.getFont());
            time.setFont(owner.getFont().deriveFont(owner.getFont().getSize2D() - 1f));
            boolean gone = trashed.contains(artifact.id());
            title.setForeground(selected ? owner.getSelectionForeground() : gone ? Comic.MUTED : Comic.INK);
            String text = artifact.title() == null ? "" : artifact.title();
            title.setText(gone ? "<html><s>" + text.replace("&", "&amp;").replace("<", "&lt;") + "</s></html>"
                    : text);
            title.setIcon(icon(artifact));
            time.setText(when(changedAt(artifact), java.time.ZoneId.systemDefault(), java.time.LocalDate.now()));
            java.awt.Insets in = line.getInsets();
            int room = owner.getWidth() - in.left - in.right;
            // Das Abzeichen bleibt immer (der Titel kürzt sich davor); die Zeit weicht zuerst.
            boolean old = !gone && staleBooks.contains(artifact.id());
            stale.setVisible(old);
            time.setVisible(!gone);
            time.setVisible(!gone && title.getPreferredSize().width + 6 + side.getPreferredSize().width <= room);
            setToolTipText(gone ? RESTORE_TIP : old ? text + " · " + STALE_TIP : text);
            // Markiert wird die Zeile, nicht die Überschrift der Gruppe.
            setOpaque(false);
            line.setOpaque(selected);
            line.setBackground(owner.getSelectionBackground());
            return this;
        }
    }

    private static Icon icon(ChatArtifact artifact) {
        if (artifact.id().startsWith(ChatArtifactStore.BOOK)) {
            return ComposerIcons.openBook();
        }
        return artifact.kind() == ChatArtifact.Kind.GRAPHIC ? ComposerIcons.picture() : ComposerIcons.document();
    }
}
