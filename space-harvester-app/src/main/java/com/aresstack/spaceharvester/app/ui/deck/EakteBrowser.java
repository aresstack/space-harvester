package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachment;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachmentLoader;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.app.ui.extraction.ExtractionView;
import com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider;
import com.aresstack.spaceharvester.application.extraction.DocumentRef;
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
import javax.swing.JTree;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

/**
 * Eine E-Akte-Sitzung, nur lesend: links der Baum mit Mappen und Dokumenten (eine Mappe wird erst beim Aufklappen oder
 * Wählen mit {@code PROPFIND Depth: 1} gelesen, nie die ganze E-Akte), rechts auf ganzer Fläche das gewählte Dokument
 * mit den Viewern der App (wie im Bereich Dateien: PDF, Word, Outlook-Nachricht …). Keine zweite Liste neben dem Baum
 * (Angelo 24.11.): Typ, Größe, Datum stehen im Tooltip. Aktionen in der Fußleiste des Deck ({@link #footTools()}), Stand
 * und Fehler in der Statuszeile der App; ein Fehler wird nie stillschweigend zu „leer“, er steht als Fehler da.
 * Kennung jedes Eintrags ist das vom Server gelieferte {@code DAV:href}, nie ein Name.
 *
 * <p>Zum gezeigten Dokument gibt es zwei Sichten, umgeschaltet über „Vorschau | Extraktion“ in der Titelleiste
 * ({@link #viewToggle()}, nur sichtbar, solange ein Dokument gezeigt wird; die Stellung gilt je Sitzungsreiter). Die
 * Extraktion ({@link ExtractionView}, experimentell) ordnet ihre Ergebnisse dem Dokumentstand {@code href} + ETag zu.
 */
final class EakteBrowser extends JPanel {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY).withZone(ZoneId.systemDefault());
    private static final String READ_ONLY = "nur lesen";
    private static final String NO_PREVIEW = "keine";
    private static final String EXTRACTION_CARD = "extraktion";
    private static final String VIEWER_CARD = "viewer";
    static final String PREVIEW = "Vorschau";
    static final String TEXT = "Text";
    static final String EXTRACTION = "Extraktion";
    /** Herkunft der Dokumentreferenzen der Extraktion. */
    static final String ORIGIN = "eakte";

    private final EakteSession session;
    private final EditorSessions editors;
    private final DocumentStore preview;
    private final Folder root = new Folder(null, "E-Akte (" + READ_ONLY + ")");
    private final DefaultTreeModel treeModel = new DefaultTreeModel(root, true);
    private final JTree tree = new JTree(treeModel);
    private final CardLayout previewCards = new CardLayout();
    private final JPanel previewStage = new JPanel(previewCards);
    /** Kurz warten, bevor ein gewähltes Dokument geladen wird (Pfeiltasten durch den Baum laden nicht alles). */
    private final javax.swing.Timer previewDelay = new javax.swing.Timer(250, e -> previewSelected());
    private final JPanel footTools;
    /** Was die Mappen zuletzt enthielten ({@code DAV:href} → Einträge); Aktualisieren liest neu. */
    private final Map<String, List<EakteEntry>> listing = new HashMap<>();
    private final Map<EditorSession, Path> previewFiles = new HashMap<>();
    private Consumer<StatusNote> status = note -> { };
    private StatusNote current = StatusNote.plain(" ");
    private EditorSession shownPreview;
    /** Das gezeigte Dokument formatiert, mit den Anhang-Viewern der Leseansicht (Jira, Confluence): PDF, Word, … */
    private AttachmentViewerPanel viewer;
    static final String FOLDERS = "Ordner";
    static final String OUTLINE = "Gliederung";
    private final SegmentedToggle leftToggle = new SegmentedToggle();
    private final CardLayout leftCards = new CardLayout();
    private final JPanel leftStage = new JPanel(leftCards);
    private final javax.swing.DefaultListModel<AttachmentViewerPanel.Heading> outlineModel =
            new javax.swing.DefaultListModel<>();
    private final javax.swing.JList<AttachmentViewerPanel.Heading> outlineList = new javax.swing.JList<>(outlineModel);
    private final javax.swing.JLabel outlineEmpty = Comic.muted(" ");
    private final SegmentedToggle viewToggle = new SegmentedToggle();
    private final ExtractionView extraction = new ExtractionView(DocumentAnalysisProvider.NONE);
    private Path temp;
    /** Reiter geschlossen: nichts mehr laden, späte Kopien gleich wieder löschen. */
    private boolean disposed;
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
        tree.addTreeSelectionListener(event -> selectionChanged());
        bind(tree, "BACK_SPACE", "up", this::up);
        bind(tree, "ENTER", "open", this::openSelected);
        previewDelay.setRepeats(false);

        previewStage.setOpaque(false);
        JPanel none = new JPanel(new java.awt.GridBagLayout());
        none.setOpaque(false);
        none.add(Comic.muted("Dokument im Baum wählen, es erscheint hier"));
        previewStage.add(none, NO_PREVIEW);
        previewStage.add(extraction, EXTRACTION_CARD);
        viewToggle.segment(PREVIEW).segment(TEXT).segment(EXTRACTION);
        viewToggle.setToolTipText("Sicht auf das gezeigte Dokument: formatiert (Viewer wie bei Jira-Anhängen), "
                + "als Text oder experimentelle Extraktion");
        viewToggle.getAccessibleContext().setAccessibleName("Vorschau, Text oder Extraktion");
        viewToggle.setVisible(false);
        viewToggle.onSelect(view -> showView());
        extraction.onSource(source -> viewToggle.select(PREVIEW));
        extraction.onStatus(note -> {
            if (extractionShown()) {
                report(note);
            }
        });
        previewStage.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane(), previewStage);
        split.setResizeWeight(0.0);
        split.setDividerLocation(300);
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
                DeckBars.action(ComposerIcons.download(), "Speichern unter …", this::saveSelected),
                extraction.actions());
        extraction.actions().setVisible(false);
    }

    /**
     * Links „Ordner | Gliederung“ in der Fußleiste der Seitenleiste (Angelo 24.11.): die Gliederung steht an Stelle
     * des Baums, nie neben dem Text.
     */
    private JComponent leftPane() {
        leftToggle.segment(FOLDERS).segment(OUTLINE);
        leftToggle.select(FOLDERS);
        leftToggle.setToolTipText("Links die Mappen oder die Überschriften des gezeigten Dokuments");
        leftToggle.getAccessibleContext().setAccessibleName("Ordner oder Gliederung");
        leftToggle.onSelect(choice -> leftCards.show(leftStage, choice));
        outlineList.setOpaque(false);
        outlineList.setFont(Comic.regular(13f));
        outlineList.getAccessibleContext().setAccessibleName("Gliederung des Dokuments");
        outlineList.setCellRenderer(new javax.swing.DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(javax.swing.JList<?> list, Object value, int index,
                                                          boolean selected, boolean focus) {
                super.getListCellRendererComponent(list, value, index, selected, focus);
                if (value instanceof AttachmentViewerPanel.Heading heading) {
                    setText(heading.text());
                    setBorder(BorderFactory.createEmptyBorder(3, 6 + 16 * (heading.level() - 1), 3, 6));
                    setFont(Comic.regular(13f).deriveFont(heading.level() == 1 ? java.awt.Font.BOLD : java.awt.Font.PLAIN));
                }
                if (!selected) {
                    setOpaque(false);
                }
                return this;
            }
        });
        outlineList.addListSelectionListener(event -> {
            AttachmentViewerPanel.Heading heading = outlineList.getSelectedValue();
            if (!event.getValueIsAdjusting() && heading != null && viewer != null) {
                viewToggle.select(PREVIEW);
                viewer.showHeading(heading);
            }
        });
        outlineEmpty.setHorizontalAlignment(javax.swing.SwingConstants.CENTER);
        JPanel outline = new JPanel(new BorderLayout());
        outline.setOpaque(false);
        outline.add(Comic.scroll(outlineList), BorderLayout.CENTER);
        outline.add(outlineEmpty, BorderLayout.NORTH);
        refreshOutline();
        leftStage.setOpaque(false);
        leftStage.add(Comic.scroll(tree), FOLDERS);
        leftStage.add(outline, OUTLINE);
        JPanel left = new JPanel(new BorderLayout());
        left.setOpaque(false);
        left.add(leftStage, BorderLayout.CENTER);
        // Angelo 24.11.: der Umschalter einer Seitenleiste steht unten in ihrer Fußleiste, überall gleich hoch
        left.add(DeckBars.foot(leftToggle, null), BorderLayout.SOUTH);
        return left;
    }

    /** Überschriften des gezeigten Dokuments in die Gliederung; ohne sagt sie, warum sie leer ist. */
    private void refreshOutline() {
        java.util.List<AttachmentViewerPanel.Heading> headings = viewer == null ? List.of() : viewer.headings();
        outlineModel.clear();
        headings.forEach(outlineModel::addElement);
        outlineEmpty.setText(headings.isEmpty() ? viewer == null ? "Kein Dokument gezeigt"
                : "Keine Überschriften (Word-Formatvorlagen „Überschrift 1–3“)" : "");
        outlineEmpty.setVisible(headings.isEmpty());
    }

    /** „Ordner | Gliederung“ unten in der Seitenleiste. */
    JComponent leftToggle() {
        return leftToggle;
    }

    /** Die Überschriften, wie die Gliederung sie zeigt. */
    List<AttachmentViewerPanel.Heading> outline() {
        return java.util.Collections.list(outlineModel.elements());
    }

    /** Für Tests: den gezeigten Viewer. */
    AttachmentViewerPanel viewer() {
        return viewer;
    }

    /** Der Umschalter „Vorschau | Extraktion“ für die Titelleiste des Bereichs. */
    JComponent viewToggle() {
        return viewToggle;
    }

    /** Die Extraktionsansicht dieser Sitzung (später schließt der Bereich hier die Analyse an). */
    ExtractionView extraction() {
        return extraction;
    }

    /** Die Dokumentreferenz der Extraktion: {@code DAV:href} plus ETag, nie der Name. */
    static DocumentRef documentRef(EakteEntry entry) {
        return new DocumentRef(ORIGIN, entry.href(), entry.etag());
    }

    private boolean extractionShown() {
        return shownPreview != null && EXTRACTION.equals(viewToggle.selected());
    }

    /** Zeigt im Vorschaubereich die gewählte Sicht des gezeigten Dokuments. */
    private void showView() {
        boolean extracting = extractionShown();
        extraction.actions().setVisible(extracting);
        if (shownPreview == null) {
            previewCards.show(previewStage, NO_PREVIEW);
            return;
        }
        previewCards.show(previewStage, extracting ? EXTRACTION_CARD
                : TEXT.equals(viewToggle.selected()) || viewer == null ? shownPreview.location() : VIEWER_CARD);
        if (extracting) {
            report(extraction.currentStatus());
        }
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

    /** Inhalt der gewählten Mappe (bzw. der Mappe des gewählten Dokuments), wie der Baum ihn zeigt. */
    List<EakteEntry> entries() {
        Folder chosen = chosenFolder();
        return chosen == null || chosen.href == null ? List.of() : listing.getOrDefault(chosen.href, List.of());
    }

    /** {@code DAV:href} der gewählten Mappe. */
    String folder() {
        Folder chosen = chosenFolder();
        return chosen == null ? null : chosen.href;
    }

    /** Wählt die Mappe mit diesem {@code DAV:href} unter der gewählten oder die Wurzel. */
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

    /** Wählt den Eintrag mit diesem {@code DAV:href} in der gewählten Mappe im Baum. */
    void selectEntry(String href) {
        Folder chosen = chosenFolder();
        DefaultMutableTreeNode node = chosen == null ? null : chosen.node(href);
        if (node != null) {
            TreePath path = new TreePath(node.getPath());
            tree.setSelectionPath(path);
            tree.scrollPathToVisible(path);
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

    /** Eingabe: Mappe auf-/zuklappen, Dokument (neu) zeigen. */
    void openSelected() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return;
        }
        if (path.getLastPathComponent() instanceof Doc) {
            previewSelected();
        } else if (tree.isExpanded(path)) {
            tree.collapsePath(path);
        } else {
            tree.expandPath(path);
        }
    }

    /** Ein Dokument im Baum gewählt: rechts zeigen; eine Mappe: lesen. */
    private void selectionChanged() {
        if (selected() != null) {
            previewDelay.restart();
        } else {
            previewDelay.stop();
            folderChosen();
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
            if (chosenPath != null && chosenPath.getLastPathComponent() instanceof DefaultMutableTreeNode still
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
        DocumentEditor editor = new DocumentEditor(opened);
        editor.readOnlyView(); // ganze Fläche fürs Dokument; gesucht wird nur mit der Lupe des Deck
        previewStage.add(editor, opened.location());
        ReaderAttachment attachment = new ReaderAttachment(entry.href(), entry.name(), entry.contentType(),
                entry.size());
        viewer = new AttachmentViewerPanel(attachment, fileLoader(file), null, null);
        viewer.detachFooter(); // Speichern/Öffnen stehen schon in der Fußleiste des Deck
        viewer.onHeadings(this::refreshOutline);
        previewStage.add(viewer, VIEWER_CARD);
        if (!AttachmentViewerPanel.showsFormatted(attachment) && PREVIEW.equals(viewToggle.selected())) {
            viewToggle.select(TEXT); // etwa Outlook-Nachrichten: dafür hat der Viewer keine Vorschau, der Text schon
        }
        extraction.show(documentRef(entry));
        viewToggle.setVisible(true);
        showView();
        if (!extractionShown()) {
            report(StatusNote.plain("Vorschau: „" + entry.name() + "“ · " + READ_ONLY));
        }
    }

    private void dropPreview() {
        if (shownPreview == null) {
            return;
        }
        EditorSession old = shownPreview;
        shownPreview = null;
        for (Component component : previewStage.getComponents()) {
            if (component instanceof DocumentEditor || component instanceof AttachmentViewerPanel) {
                previewStage.remove(component);
            }
        }
        if (viewer != null) {
            viewer.close();
            viewer = null;
            refreshOutline();
        }
        extraction.show(null);
        viewToggle.setVisible(false);
        showView();
        editors.close(old);
        deleteQuietly(previewFiles.remove(old));
    }

    /** Liefert dem Viewer die schon geholte Kopie im temporären Ordner (gelesen abseits des EDT). */
    private static ReaderAttachmentLoader fileLoader(Path file) {
        return (attachment, done, failed) -> CompletableFuture.supplyAsync(() -> {
            try {
                return Files.readAllBytes(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }).whenComplete((bytes, error) -> SwingUtilities.invokeLater(() -> {
            if (error != null) {
                failed.accept(EakteSessions.describe(error));
            } else {
                done.accept(bytes);
            }
        }));
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
        if (disposed) {
            return new CompletableFuture<>();
        }
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
            Path root = temp;
            CompletableFuture<Path> fetched = new CompletableFuture<>();
            fetch(entry, file).thenAccept(path -> SwingUtilities.invokeLater(() -> {
                if (disposed) { // Reiter inzwischen zu: die späte Kopie nicht liegen lassen
                    deleteQuietly(path);
                    deleteQuietly(folder);
                    deleteQuietly(root);
                } else {
                    fetched.complete(path);
                }
            }));
            return fetched;
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
        disposed = true;
        previewDelay.stop();
        dropPreview();
        extraction.close();
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

    /** Das im Baum gewählte Dokument, sonst {@code null}. */
    private EakteEntry selected() {
        TreePath path = tree.getSelectionPath();
        return path != null && path.getLastPathComponent() instanceof Doc doc ? doc.entry : null;
    }

    /** Die gewählte Mappe; ist ein Dokument gewählt, seine Mappe. */
    private Folder chosenFolder() {
        TreePath path = tree.getSelectionPath();
        if (path == null) {
            return null;
        }
        Object last = path.getLastPathComponent();
        if (last instanceof Doc doc) {
            last = doc.getParent();
        }
        return last instanceof Folder folder ? folder : null;
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

        /** Ein Kind (Mappe oder Dokument) mit diesem {@code DAV:href}. */
        DefaultMutableTreeNode node(String href) {
            for (int i = 0; i < getChildCount(); i++) {
                if (getChildAt(i) instanceof Folder folder && href.equals(folder.href)) {
                    return folder;
                }
                if (getChildAt(i) instanceof Doc doc && href.equals(doc.entry.href())) {
                    return doc;
                }
            }
            return null;
        }

        /** Übernimmt Untermappen und Dokumente (Reihenfolge wie gelesen); schon gelesene Mappen behalten ihren Inhalt. */
        void fill(List<EakteEntry> entries) {
            Map<String, Folder> known = new HashMap<>();
            for (int i = 0; i < getChildCount(); i++) {
                if (getChildAt(i) instanceof Folder folder) {
                    known.put(folder.href, folder);
                }
            }
            removeAllChildren();
            List<DefaultMutableTreeNode> next = new ArrayList<>();
            for (EakteEntry entry : entries) {
                next.add(entry.folder() ? known.getOrDefault(entry.href(), new Folder(entry.href(), entry.name()))
                        : new Doc(entry));
            }
            next.forEach(this::add);
            loaded = true;
        }

        @Override
        public boolean getAllowsChildren() {
            return !loaded || getChildCount() > 0;
        }
    }

    /** Ein Dokument im Baum (Blatt). */
    private static final class Doc extends DefaultMutableTreeNode {

        private final EakteEntry entry;

        Doc(EakteEntry entry) {
            super(entry.name(), false);
            this.entry = entry;
        }
    }

    private final class FolderRenderer extends DefaultTreeCellRenderer {

        @Override
        public Component getTreeCellRendererComponent(JTree t, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean focus) {
            super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
            setBackgroundNonSelectionColor(null);
            if (value instanceof Doc doc) {
                setIcon(FilesIcons.forName(doc.entry.name()));
                setText(doc.entry.name());
                setToolTipText(details(doc.entry));
            } else if (value instanceof Folder folder) {
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

    /** Typ, Größe, Datum und was der Server sonst noch geliefert hat (nur Vorhandenes). */
    private static String details(EakteEntry entry) {
            List<String> lines = new ArrayList<>();
            lines.add("Typ: " + type(entry));
            if (entry.size() >= 0) {
                lines.add("Größe: " + size(entry.size()));
            }
            if (entry.modified() != null) {
                lines.add("Geändert: " + WHEN.format(entry.modified()));
            }
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
