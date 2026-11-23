package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.eakte.EakteEntry;
import com.aresstack.spaceharvester.deck.eakte.EakteSession;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * Eine E-Akte-Sitzung, nur lesend: links die Mappen als Baum (eine Mappe wird erst beim Aufklappen oder Wählen mit
 * {@code PROPFIND Depth: 1} gelesen, nie die ganze E-Akte), rechts der Inhalt der gewählten Mappe als Tabelle (Name,
 * Typ, Größe, Geändert; nur was der Server liefert), darunter die Vorschau mit den Viewern der App (wie im Bereich
 * Dateien: PDF, Word, Outlook-Nachricht … als Text). Aktionen in der Fußleiste des Deck ({@link #footTools()}), Stand und
 * Fehler in der Statuszeile der App; ein Fehler leert die Tabelle nie stillschweigend zu „leer“, er steht als Fehler da.
 * Kennung jedes Eintrags ist das vom Server gelieferte {@code DAV:href}, nie ein Name.
 */
final class EakteBrowser extends JPanel {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY).withZone(ZoneId.systemDefault());
    private static final String READ_ONLY = "nur lesen";
    private static final String NO_PREVIEW = "keine";

    private final EakteSession session;
    private final EditorSessions editors;
    private final DocumentStore preview;
    private final Folder root = new Folder(null, "E-Akte (" + READ_ONLY + ")");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root, true);
    private final JTree tree = new JTree(treeModel);
    private final Model model = new Model();
    private final JTable table = Comic.table(new JTable(model));
    private final CardLayout previewCards = new CardLayout();
    private final JPanel previewStage = new JPanel(previewCards);
    private final JSplitPane right;
    private final JPanel footTools;
    /** Was die Mappen zuletzt enthielten ({@code DAV:href} → Einträge); Aktualisieren liest neu. */
    private final Map<String, List<EakteEntry>> listing = new HashMap<>();
    private final Map<EditorSession, Path> previewFiles = new HashMap<>();
    private Consumer<StatusNote> status = note -> { };
    private StatusNote current = StatusNote.plain(" ");
    private EditorSession shownPreview;
    private Path temp;
    /** „Speichern unter“: Ziel wählen (Dialog im Ausgabeordner der App); Tests setzen ein festes Ziel. */
    Function<String, Optional<Path>> saveTarget = this::chooseTarget;

    EakteBrowser(EakteSession session, EditorSessions editors, DocumentStore preview) {
        super(new BorderLayout());
        this.session = session;
        this.editors = editors;
        this.preview = preview;
        setOpaque(false);

        treeModel.setAsksAllowsChildren(true);
        tree.setRootVisible(true);
        tree.setShowsRootHandles(true);
        tree.setOpaque(false);
        tree.setRowHeight(0);
        tree.setFont(Comic.regular(13f));
        tree.getSelectionModel().setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        tree.getAccessibleContext().setAccessibleName("Mappen der E-Akte");
        tree.setCellRenderer(new FolderRenderer());
        javax.swing.ToolTipManager.sharedInstance().registerComponent(tree);
        tree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) {
                if (event.getPath().getLastPathComponent() instanceof Folder folder && !folder.loaded) {
                    load(folder);
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) {
            }
        });
        tree.addTreeSelectionListener(event -> folderChosen());
        bind(tree, "BACK_SPACE", "up", this::up);

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getColumnModel().getColumn(0).setPreferredWidth(380);
        table.getColumnModel().getColumn(1).setPreferredWidth(150);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    openSelected();
                }
            }
        });
        bind(table, "ENTER", "open", this::openSelected);
        bind(table, "BACK_SPACE", "up", this::up);
        table.getAccessibleContext().setAccessibleName("Inhalt der Mappe");

        previewStage.setOpaque(false);
        JPanel none = new JPanel();
        none.setOpaque(false);
        previewStage.add(none, NO_PREVIEW);
        right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, Comic.scroll(table), previewStage);
        right.setResizeWeight(1.0);
        right.setOpaque(false);
        right.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        ComicDivider.install(right);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, Comic.scroll(tree), right);
        split.setResizeWeight(0.25);
        split.setDividerLocation(260);
        split.setOpaque(false);
        split.setBorder(BorderFactory.createEmptyBorder());
        ComicDivider.install(split);
        add(split, BorderLayout.CENTER);

        footTools = DeckBars.tools(
                DeckBars.action(Glyph.REFRESH, "Aktualisieren: gewählte Mappe neu lesen", this::reload),
                DeckBars.action(ComposerIcons.document(), "Vorschau in der App (Eingabe, Doppelklick); PDF, Word, "
                        + "Outlook-Nachricht … als Text", this::previewSelected),
                DeckBars.action(ComposerIcons.openExternal(), "Öffnen mit dem zugehörigen Programm (Kopie in einem "
                        + "temporären Ordner, der beim Schließen gelöscht wird)", this::openExternally),
                DeckBars.action(ComposerIcons.download(), "Speichern unter …", this::saveSelected));
    }

    JComponent footTools() {
        return footTools;
    }

    void onStatus(Consumer<StatusNote> observer) {
        status = observer;
    }

    StatusNote status() {
        return current;
    }

    /** Liest die Wurzel, sobald die Sitzung angemeldet ist (einmal). */
    void connected() {
        if (root.href == null) {
            session.rootHref().ifPresent(href -> {
                root.href = href;
                tree.setSelectionPath(new TreePath(root));
            });
        }
    }

    /** Inhalt der gewählten Mappe, wie die Tabelle ihn zeigt. */
    List<EakteEntry> entries() {
        return model.entries;
    }

    /** {@code DAV:href} der gewählten Mappe. */
    String folder() {
        Folder chosen = chosenFolder();
        return chosen == null ? null : chosen.href;
    }

    /** Wählt die Mappe mit diesem {@code DAV:href} unter der gewählten (aus der Tabelle) oder die Wurzel. */
    void show(String href) {
        Folder parent = chosenFolder();
        Folder target = href.equals(root.href) ? root : parent == null ? null : parent.child(href);
        if (target != null) {
            TreePath path = new TreePath(target.getPath());
            tree.expandPath(path.getParentPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
        }
    }

    /** Wählt den Eintrag mit diesem {@code DAV:href} in der Tabelle. */
    void selectEntry(String href) {
        for (int row = 0; row < table.getRowCount(); row++) {
            if (model.entries.get(table.convertRowIndexToModel(row)).href().equals(href)) {
                table.setRowSelectionInterval(row, row);
                return;
            }
        }
    }

    /** Zur übergeordneten Mappe. */
    void up() {
        Folder chosen = chosenFolder();
        if (chosen != null && chosen.getParent() instanceof Folder parent) {
            tree.setSelectionPath(new TreePath(parent.getPath()));
        }
    }

    /** Aktualisieren: die gewählte Mappe neu lesen. */
    void reload() {
        Folder chosen = chosenFolder();
        if (chosen == null) {
            connected();
            return;
        }
        listing.remove(chosen.href);
        load(chosen);
    }

    /** Doppelklick/Eingabe: Mappe öffnen, Dokument als Vorschau. */
    void openSelected() {
        EakteEntry entry = selected();
        if (entry == null) {
            return;
        }
        if (entry.folder()) {
            show(entry.href());
        } else {
            previewSelected();
        }
    }

    private void folderChosen() {
        Folder chosen = chosenFolder();
        if (chosen == null || chosen.href == null) {
            return;
        }
        List<EakteEntry> known = listing.get(chosen.href);
        if (known != null) {
            showEntries(chosen, known);
        } else {
            model.set(List.of());
            load(chosen);
        }
    }

    /** Liest eine Mappe ({@code Depth: 1}) außerhalb des EDT; Mappen kommen in den Baum, alles in die Tabelle. */
    private void load(Folder folder) {
        if (folder.href == null || folder.loading) {
            return;
        }
        folder.loading = true;
        folder.error = null;
        treeModel.nodeChanged(folder);
        if (folder == chosenFolder()) {
            report(StatusNote.plain("Lese " + label(folder) + " …"));
        }
        session.list(folder.href).whenComplete((entries, error) -> SwingUtilities.invokeLater(() -> {
            folder.loading = false;
            if (error != null) {
                folder.error = EakteSessions.describe(error);
                treeModel.nodeChanged(folder);
                if (folder == chosenFolder()) {
                    model.set(List.of());
                    report(new StatusNote(label(folder) + ": " + folder.error, StatusNote.Tone.ERROR, null, null));
                }
                return;
            }
            List<EakteEntry> sorted = entries.stream().sorted(ORDER).toList();
            listing.put(folder.href, sorted);
            // Ein Strukturwechsel nimmt dem Baum die Auswahl (an der Wurzel ganz): danach wiederherstellen.
            TreePath chosenPath = tree.getSelectionPath();
            folder.fill(sorted);
            treeModel.nodeStructureChanged(folder);
            if (chosenPath != null && chosenPath.getLastPathComponent() instanceof Folder still
                    && (still == folder || still.isNodeAncestor(folder))) {
                tree.setSelectionPath(chosenPath);
            }
            if (folder == chosenFolder()) {
                tree.expandPath(new TreePath(folder.getPath()));
                showEntries(folder, sorted);
            }
        }));
    }

    private void showEntries(Folder folder, List<EakteEntry> entries) {
        model.set(entries);
        if (!entries.isEmpty()) {
            table.setRowSelectionInterval(0, 0);
        }
        long folders = entries.stream().filter(EakteEntry::folder).count();
        long documents = entries.size() - folders;
        String content = entries.isEmpty() ? "leer"
                : folders + (folders == 1 ? " Mappe, " : " Mappen, ") + documents
                + (documents == 1 ? " Dokument" : " Dokumente");
        report(StatusNote.plain(session.profile().label() + " › " + label(folder) + " · " + content + " · "
                + READ_ONLY));
    }

    // ---- Dokumente ------------------------------------------------------------------------------------------------

    /** Vorschau mit den Viewern der App; es gibt immer nur eine, die vorige und ihre Kopie verschwinden. */
    void previewSelected() {
        EakteEntry entry = document();
        if (entry == null) {
            return;
        }
        fetchToTemp(entry).thenAccept(file -> SwingUtilities.invokeLater(() -> showPreview(entry, file)));
    }

    private void showPreview(EakteEntry entry, Path file) {
        dropPreview();
        EditorSession opened = editors.open(preview, file.toString(), entry.name());
        previewFiles.put(opened, file);
        shownPreview = opened;
        previewStage.add(new DocumentEditor(opened), opened.location());
        previewCards.show(previewStage, opened.location());
        if (right.getDividerLocation() > right.getHeight() - 60) {
            right.setDividerLocation(0.4);
        }
        report(StatusNote.plain("Vorschau: „" + entry.name() + "“ · " + READ_ONLY));
    }

    private void dropPreview() {
        if (shownPreview == null) {
            return;
        }
        EditorSession old = shownPreview;
        shownPreview = null;
        for (Component component : previewStage.getComponents()) {
            if (component instanceof DocumentEditor) {
                previewStage.remove(component);
            }
        }
        previewCards.show(previewStage, NO_PREVIEW);
        editors.close(old);
        deleteQuietly(previewFiles.remove(old));
    }

    /** Öffnen mit dem zugehörigen Programm über eine Kopie im temporären Ordner dieser Sitzung. */
    void openExternally() {
        EakteEntry entry = document();
        if (entry == null) {
            return;
        }
        fetchToTemp(entry).thenAccept(file -> {
            try {
                java.awt.Desktop.getDesktop().open(file.toFile());
            } catch (IOException | RuntimeException e) {
                SwingUtilities.invokeLater(() -> report(new StatusNote("„" + entry.name() + "“ lässt sich nicht "
                        + "öffnen: " + EakteSessions.describe(e) + " – „Vorschau“ oder „Speichern unter“ geht",
                        StatusNote.Tone.ERROR, null, null)));
            }
        });
    }

    /** „Speichern unter“: gestreamt in eine Teildatei daneben, erst am Ende an den Platz; bei Fehler weg. */
    void saveSelected() {
        EakteEntry entry = document();
        if (entry == null) {
            return;
        }
        saveTarget.apply(safeName(entry.name())).ifPresent(target -> fetch(entry, target)
                .thenAccept(path -> SwingUtilities.invokeLater(() -> report(StatusNote.plain("Gespeichert: " + path)))));
    }

    private Optional<Path> chooseTarget(String name) {
        JFileChooser chooser = SaveChooser.create(name);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return Optional.empty();
        }
        Path target = chooser.getSelectedFile().toPath();
        if (Files.exists(target) && !com.aresstack.spaceharvester.app.ui.Dialogs.confirm(this, "Speichern unter",
                "„" + target.getFileName() + "“ gibt es schon. Wirklich überschreiben?")) {
            return Optional.empty();
        }
        return Optional.of(target);
    }

    private CompletableFuture<Path> fetchToTemp(EakteEntry entry) {
        try {
            if (temp == null) {
                temp = Files.createTempDirectory("space-harvester-eakte-");
                temp.toFile().deleteOnExit();
            }
            // Eigener Unterordner je Abruf: gleiche Namen in verschiedenen Mappen stören sich nicht.
            Path folder = Files.createTempDirectory(temp, "d");
            folder.toFile().deleteOnExit();
            Path file = folder.resolve(safeName(entry.name()));
            file.toFile().deleteOnExit();
            return fetch(entry, file);
        } catch (IOException e) {
            report(new StatusNote("Kein temporärer Ordner: " + EakteSessions.describe(e), StatusNote.Tone.ERROR,
                    null, null));
            return new CompletableFuture<>();
        }
    }

    /** Lädt gestreamt nach {@code target}; Fehler stehen in der Statuszeile, die Zukunft endet dann nie. */
    private CompletableFuture<Path> fetch(EakteEntry entry, Path target) {
        report(StatusNote.plain("Lade „" + entry.name() + "“ …"));
        CompletableFuture<Path> done = new CompletableFuture<>();
        Path part;
        OutputStream out;
        try {
            Path directory = target.toAbsolutePath().getParent();
            part = Files.createTempFile(directory, "." + safeName(entry.name()), ".teil");
            out = Files.newOutputStream(part);
        } catch (IOException e) {
            failed(entry, e);
            return done;
        }
        session.download(entry.href(), out).whenComplete((bytes, error) -> {
            Throwable problem = error;
            try {
                out.close();
                if (problem == null) {
                    Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                problem = problem == null ? e : problem;
            }
            if (problem != null) {
                deleteQuietly(part);
                failed(entry, problem);
                return;
            }
            done.complete(target);
        });
        return done;
    }

    private void failed(EakteEntry entry, Throwable error) {
        SwingUtilities.invokeLater(() -> report(new StatusNote("„" + entry.name() + "“: "
                + EakteSessions.describe(error), StatusNote.Tone.ERROR, null, null)));
    }

    /** Schließt die Vorschau und löscht den temporären Ordner dieser Sitzung (Reiter zu, Programmende). */
    void dispose() {
        dropPreview();
        if (temp != null) {
            try (Stream<Path> files = Files.walk(temp)) {
                files.sorted(Comparator.reverseOrder()).forEach(EakteBrowser::deleteQuietly);
            } catch (IOException ignored) {
                // was bleibt, löscht deleteOnExit
            }
            temp = null;
        }
    }

    Path tempFolder() {
        return temp;
    }

    EditorSession shownPreview() {
        return shownPreview;
    }

    // ---- Hilfen ---------------------------------------------------------------------------------------------------

    private EakteEntry document() {
        EakteEntry entry = selected();
        if (entry == null || entry.folder()) {
            report(StatusNote.plain("Bitte ein Dokument wählen"));
            return null;
        }
        return entry;
    }

    private EakteEntry selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.entries.get(table.convertRowIndexToModel(row));
    }

    private Folder chosenFolder() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof Folder folder ? folder : null;
    }

    private static String label(Folder folder) {
        return folder.isRoot() ? "E-Akte" : "E-Akte › " + folder.name;
    }

    private void report(StatusNote note) {
        current = note;
        status.accept(note);
    }

    /** Ein Dateiname ohne Pfadteile und Zeichen, die Windows nicht nimmt. */
    static String safeName(String name) {
        String safe = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").strip();
        return safe.isEmpty() || safe.equals(".") || safe.equals("..") ? "Dokument" : safe;
    }

    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // deleteOnExit
        }
    }

    private static void bind(JComponent component, String key, String name, Runnable run) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                run.run();
            }
        });
    }

    /** Mappen zuerst, dann nach Name (deutsch, ohne Groß/klein). */
    private static final Comparator<EakteEntry> ORDER = Comparator.comparing((EakteEntry entry) -> !entry.folder())
            .thenComparing(EakteEntry::name, java.text.Collator.getInstance(Locale.GERMANY));

    /** Typ, wie man ihn liest: Mappe, bekannte Inhaltsarten mit Namen, sonst der Typ des Servers. */
    static String type(EakteEntry entry) {
        if (entry.folder()) {
            return "Mappe";
        }
        String type = entry.contentType() == null ? "" : entry.contentType().toLowerCase(Locale.ROOT);
        int semicolon = type.indexOf(';');
        type = (semicolon < 0 ? type : type.substring(0, semicolon)).strip();
        return switch (type) {
            case "application/vnd.ms-outlook" -> "Outlook-Nachricht";
            case "message/rfc822" -> "E-Mail";
            case "application/pdf" -> "PDF";
            case "text/plain" -> "Text";
            case "text/html" -> "HTML";
            case "application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                    "Word";
            case "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" ->
                    "Excel";
            case "application/vnd.ms-powerpoint",
                 "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> "PowerPoint";
            default -> type;
        };
    }

    /** Eine Mappe im Baum; ungelesen hat sie einen Platzhalter, damit sie aufklappbar ist. */
    private static final class Folder extends DefaultMutableTreeNode {

        private String href;
        private final String name;
        private boolean loaded;
        private boolean loading;
        private String error;

        Folder(String href, String name) {
            super(name, true);
            this.href = href;
            this.name = name;
        }

        Folder child(String href) {
            for (int i = 0; i < getChildCount(); i++) {
                if (getChildAt(i) instanceof Folder folder && href.equals(folder.href)) {
                    return folder;
                }
            }
            return null;
        }

        /** Übernimmt die gelesenen Untermappen; schon gelesene behalten ihren Inhalt. */
        void fill(List<EakteEntry> entries) {
            Map<String, Folder> known = new HashMap<>();
            for (int i = 0; i < getChildCount(); i++) {
                if (getChildAt(i) instanceof Folder folder) {
                    known.put(folder.href, folder);
                }
            }
            removeAllChildren();
            List<Folder> next = new ArrayList<>();
            for (EakteEntry entry : entries) {
                if (entry.folder()) {
                    next.add(known.getOrDefault(entry.href(), new Folder(entry.href(), entry.name())));
                }
            }
            next.forEach(this::add);
            loaded = true;
        }

        @Override
        public boolean getAllowsChildren() {
            return !loaded || getChildCount() > 0;
        }
    }

    private final class FolderRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
            setBackgroundNonSelectionColor(null);
            if (value instanceof Folder folder) {
                setIcon(FilesIcons.folder());
                setText(folder.loading ? folder.name + " …" : folder.name);
                if (folder.error != null) {
                    setForeground(Comic.RED);
                    setToolTipText(folder.error);
                } else {
                    setToolTipText(folder.loaded ? null : "Wird beim Aufklappen gelesen");
                }
            }
            return this;
        }
    }

    private final class NameRenderer extends DefaultTableCellRenderer {

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            super.getTableCellRendererComponent(t, value, selected, focus, row, column);
            EakteEntry entry = model.entries.get(t.convertRowIndexToModel(row));
            setIcon(entry.folder() ? FilesIcons.folder() : FilesIcons.forName(entry.name()));
            setToolTipText(details(entry));
            return this;
        }

        /** Was der Server sonst noch geliefert hat (nur Vorhandenes). */
        private static String details(EakteEntry entry) {
            List<String> lines = new ArrayList<>();
            if (!entry.objectAddress().isEmpty()) {
                lines.add("Objektadresse: " + entry.objectAddress());
            }
            if (entry.created() != null) {
                lines.add("Angelegt: " + WHEN.format(entry.created()));
            }
            if (!entry.contentType().isEmpty()) {
                lines.add("Inhaltsart: " + entry.contentType());
            }
            if (!entry.etag().isEmpty()) {
                lines.add("Version (ETag): " + entry.etag());
            }
            return lines.isEmpty() ? null : "<html>" + String.join("<br>", lines.stream()
                    .map(line -> line.replace("&", "&amp;").replace("<", "&lt;")).toList()) + "</html>";
        }
    }

    private static final class Model extends AbstractTableModel {

        private List<EakteEntry> entries = List.of();

        void set(List<EakteEntry> next) {
            entries = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return entries.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "Name";
                case 1 -> "Typ";
                case 2 -> "Größe";
                default -> "Geändert";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            EakteEntry entry = entries.get(row);
            return switch (column) {
                case 0 -> entry.name();
                case 1 -> type(entry);
                case 2 -> entry.folder() || entry.size() < 0 ? "" : size(entry.size());
                default -> entry.modified() == null ? "" : WHEN.format(entry.modified());
            };
        }

        private static String size(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
            }
            return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
