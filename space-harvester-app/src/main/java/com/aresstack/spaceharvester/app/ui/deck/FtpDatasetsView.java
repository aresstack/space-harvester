package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ftp.FtpDocumentStore;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.PlaceholderField;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.controls.SaveChooser;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.deck.ftp.DatasetAttributes;
import com.aresstack.spaceharvester.deck.ftp.EntryList;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.MvsPath;
import com.aresstack.spaceharvester.deck.ftp.RemoteEntry;
import com.aresstack.spaceharvester.deck.ftp.RemoteFile;
import com.aresstack.spaceharvester.mainframe.outline.SourceLanguage;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.SwingUtilities;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.table.AbstractTableModel;

/**
 * Unteransicht „Datasets“ des FTP-Bereichs (MVS-Navigator und FTP-Dateibrowser wie in MainframeMate): oben der Pfad
 * mit Zurück, Vor, Hoch und Neu laden im Feld, rechts die Werkzeuge (Sortieren, Details, nach Endung gruppieren) und der
 * Filter (Regex; auf MVS „enthält“, mit führendem {@code /} ein Regex), links die Liste mit Mehrfachauswahl und
 * Kontextmenü (Datasets wie Ordner, Members wie Dateien), rechts der gemeinsame Editor des Command Deck mit einem
 * Reiter je geöffnetem Member (Doppelklick oder Enter öffnet). Die Aktionen stehen unten in der Werkzeugleiste; ist ein
 * Member offen, kommen Speichern, Neu laden und Schließen dazu. Sortierung, Details und Gruppierung merkt sich die App.
 */
final class FtpDatasetsView extends JPanel {

    private final FtpSessionView owner;
    private final Runnable showJobs;
    private final PlaceholderField path = new PlaceholderField(30, "HLQ oder Dataset, etwa 'HLQ.JCL'");
    private final PlaceholderField filter = new PlaceholderField(12, "Filtern");
    /** Erster Reiter der Seitenleiste. */
    static final String SIDE_DATASETS = "Datasets";
    /** Zuletzt gewählter Reiter der Seitenleiste. */
    static final String SIDE_TAB_KEY = "deck.ftp.side-tab";
    private EditorSideBar sideBar;
    private final Entries model = new Entries();
    private final JTable table = Comic.table(new JTable(model));
    private static final String HINT = "hint";
    private final EditorSessions editors;
    private final FtpDocumentStore store;
    private final JLabel documentsTitle = Comic.caption("Editor");
    private final JPanel documentPills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup documentGroup = new ButtonGroup();
    private final CardLayout documentCards = new CardLayout();
    private final JPanel documents = new JPanel(documentCards);
    private final Map<EditorSession, DocumentEditor> editorOf = new LinkedHashMap<>();
    private final Map<EditorSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private static final String DELETE_TIP =
            "Gewählte Einträge löschen (mit Rückfrage; Mehrfachauswahl mit Strg/Umschalt)";
    private final IconButton save;
    private final IconButton reloadDocument;
    private final JPanel documentsHead;
    /** Rückfrage vor dem Überschreiben eines vorhandenen Members (Tests ersetzen sie). */
    java.util.function.Predicate<String> confirmOverwrite =
            question -> Dialogs.confirm(this, "Überschreiben", question);
    private EditorSession document;
    private final PlaceholderField newName = new PlaceholderField(12, "");
    private final JLabel newLabel = Comic.text("");
    private final JPanel newBar = new JPanel(new BorderLayout(8, 0));
    private final JPanel actions;
    private final IconButton download;
    private final IconButton upload;
    private final IconButton newMember;
    private final IconButton newDataset;
    private final IconButton delete;
    private final IconButton submit;
    /** Das eine Zurück wie im Space Harvester; gedrückt halten zeigt den Verlauf, Vor per Pfeil rechts/Maustaste. */
    private final com.aresstack.spaceharvester.app.ui.controls.BackButton back = new com.aresstack.spaceharvester.app.ui.controls.BackButton("Zurück (zum vorher gezeigten Pfad, Pfeil links)");
    private final Deque<String> history = new ArrayDeque<>();
    private final Deque<String> future = new ArrayDeque<>();
    static final String SORT_KEY = "deck.ftp.sort";
    static final String DETAILS_KEY = "deck.ftp.details";
    static final String GROUP_KEY = "deck.ftp.group";
    private static final String LIST = "list";
    private static final String TREE = "tree";
    private static final String NAVIGATION = "navigation";
    private final FtpNavigationTree navigation = new FtpNavigationTree(target -> navigate(target, true));
    private boolean navigatingTree;
    private long navigationTicket;
    private static final String MESSAGE = "message";
    private final CardLayout listCards = new CardLayout();
    private final JPanel listArea = new JPanel(listCards);
    private final JLabel listMessage = new JLabel(" ", JLabel.CENTER);
    private final javax.swing.tree.DefaultMutableTreeNode treeRoot = new javax.swing.tree.DefaultMutableTreeNode();
    private final javax.swing.JTree tree = new javax.swing.JTree(treeRoot);
    private final IconButton sortButton = new IconButton(new DrawnIcon(true), "Sortieren …");
    private final IconButton detailsButton = new IconButton(Glyph.TABLE, "Detailtabelle statt Navigationsbaum (Größe, Datum, RECFM/LRECL)");
    private final IconButton groupButton = new IconButton(new DrawnIcon(false), "Nach Endung gruppieren");
    private final IconButton expandAll = new IconButton(Glyph.EXPAND_ALL, "Alle Gruppen aufklappen");
    private final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alle Gruppen zuklappen");
    private EntryList.Sort sort = EntryList.Sort.NAME_ASC;
    private boolean details;
    private boolean grouped;
    /** Rückfragen „… wirklich …?“ (Titel, Frage); Tests ersetzen sie. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) -> Dialogs.confirm(this, title, question);
    /** Der Dialog „Neues Dataset anlegen“: Kontext → Name und Parameter, {@code null} bei Abbruch; Tests ersetzen ihn. */
    java.util.function.Function<String, NewDataset> datasetDialog = context -> NewDatasetForm.ask(this, context);
    /** Meldet Teilfehler nach dem Löschen mehrerer Einträge; Tests ersetzen es. */
    java.util.function.Consumer<String> report = text -> Dialogs.view(this, "Löschen", Comic.note(text));

    /** Name und Anlageparameter aus dem Dialog. */
    record NewDataset(String name, DatasetAttributes attributes) {
    }
    private String current = "";
    private boolean creatingDataset;
    private boolean connected;
    private volatile com.aresstack.spaceharvester.app.chat.MvsDiagnosisTools.Grant diagnosis;

    FtpDatasetsView(FtpSessionView owner, Runnable showJobs, EditorSessions editors) {
        super(new BorderLayout());
        this.owner = owner;
        this.showJobs = showJobs;
        this.editors = editors;
        this.store = new FtpDocumentStore(owner.session());
        setOpaque(false);

        // Kopf: Pfad mit seinen Knöpfen im Feld, rechts der Filter.
        InputFrame pathFrame = new InputFrame(path);
        back.navigator(com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator.of(
                () -> new com.aresstack.spaceharvester.app.ui.controls.BackButton.History(List.copyOf(future), List.copyOf(history)), this::go));
        var up = InputFrame.flatButton("↑", "Eine Ebene höher");
        up.addActionListener(e -> up());
        IconButton reload = new IconButton(Glyph.REFRESH, "Neu laden");
        reload.addActionListener(e -> navigate(current.isEmpty() ? path.getText() : current, false));
        pathFrame.addButton(up);
        pathFrame.addButton(reload);
        path.addActionListener(e -> openAddress());
        path.setToolTipText("HLQ, Dataset oder Pfad; Pfeil runter springt in die Liste");
        filter.setToolTipText("<html>Filter auf den Namen, Groß-/Kleinschreibung egal.<br>"
                + "Auf MVS: enthält; mit / am Anfang ein Regex (etwa <b>/^J.*L$</b>).<br>"
                + "Sonst ein Regex. Rot: ungültig oder nichts gefunden.</html>");
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                refilter();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                refilter();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                refilter();
            }
        });
        AppState state = AppState.shared();
        try {
            sort = EntryList.Sort.valueOf(java.util.Objects.requireNonNullElse(state.get(SORT_KEY), "NAME_ASC"));
        } catch (IllegalArgumentException unknown) {
            sort = EntryList.Sort.NAME_ASC;
        }
        details = state.getBoolean(DETAILS_KEY, false);
        grouped = state.getBoolean(GROUP_KEY, false);
        sortButton.addActionListener(e -> sortMenu().show(sortButton, 0, sortButton.getHeight()));
        detailsButton.addActionListener(e -> {
            details = !details;
            AppState.shared().put(DETAILS_KEY, details);
            navigate(current, false);
        });
        groupButton.addActionListener(e -> {
            grouped = !grouped;
            AppState.shared().put(GROUP_KEY, grouped);
            showList();
        });
        expandAll.addActionListener(e -> expandTree(true));
        collapseAll.addActionListener(e -> expandTree(false));
        // Oben nur der Pfad; Filter und Knöpfe der Liste stehen im Kopf der Seitenleiste wie im Space Harvester,
        // Zuklappen/Aufklappen in ihrer Fußleiste (Angelo 24.11.2026).
        JPanel head = new JPanel(new BorderLayout(10, 0));
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        head.add(back, BorderLayout.WEST);
        head.add(pathFrame, BorderLayout.CENTER);

        // „Neu …“ als Zeile über der Liste, mit ✕ (Meldungskästen oben mit ✕).
        newBar.setOpaque(false);
        newBar.setBorder(BorderFactory.createEmptyBorder(0, 12, 8, 12));
        InputFrame nameFrame = new InputFrame(newName);
        var create = InputFrame.flatButton("Anlegen", "Anlegen (Eingabe)");
        create.addActionListener(e -> create());
        var cancel = InputFrame.flatButton("✕", "Nicht anlegen (Esc)");
        cancel.addActionListener(e -> newBar.setVisible(false));
        nameFrame.addButton(create);
        nameFrame.addButton(cancel);
        newName.addActionListener(e -> create());
        newName.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "cancel-new");
        newName.getActionMap().put("cancel-new", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                newBar.setVisible(false);
            }
        });
        newBar.add(newLabel, BorderLayout.WEST);
        newBar.add(nameFrame, BorderLayout.CENTER);
        newBar.setVisible(false);
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(head, BorderLayout.NORTH);
        north.add(newBar, BorderLayout.SOUTH);

        // Mitte: Liste | Vorschau.
        table.setSelectionMode(javax.swing.ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        widths();
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(selected());
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
        installKeys();
        table.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "open-entry");
        table.getActionMap().put("open-entry", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                open(selected());
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                enableActions();
            }
        });
        table.getAccessibleContext().setAccessibleName("Datasets und Members");
        documentPills.setOpaque(false);
        documentsHead = new JPanel(new BorderLayout(8, 0));
        documentsHead.setOpaque(false);
        documentsHead.add(documentsTitle, BorderLayout.WEST);
        documentsHead.add(documentPills, BorderLayout.CENTER);
        JLabel hint = Comic.muted("Doppelklick oder Enter auf ein Member öffnet es hier zum Bearbeiten.");
        hint.setHorizontalAlignment(JLabel.CENTER);
        documents.setOpaque(false);
        documents.add(hint, HINT);
        JPanel documentPanel = new JPanel(new BorderLayout(0, 4));
        documentPanel.setOpaque(false);
        documentPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        JPanel readingHead = new JPanel(new BorderLayout());
        readingHead.setOpaque(false);
        readingHead.add(north, BorderLayout.NORTH);
        readingHead.add(documentsHead, BorderLayout.SOUTH);
        documentPanel.add(readingHead, BorderLayout.NORTH);
        documentPanel.add(documents, BorderLayout.CENTER);
        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setFont(Comic.regular(13f));
        tree.setCellRenderer(new javax.swing.tree.DefaultTreeCellRenderer() {
            @Override
            public java.awt.Component getTreeCellRendererComponent(javax.swing.JTree t, Object value, boolean selected,
                                                                   boolean expanded, boolean leaf, int row,
                                                                   boolean focus) {
                super.getTreeCellRendererComponent(t, value, selected, expanded, leaf, row, focus);
                Object item = ((javax.swing.tree.DefaultMutableTreeNode) value).getUserObject();
                if (item instanceof RemoteEntry entry) {
                    setText(entry.name() + (entry.size() >= 0 && !entry.directory() ? "  (" + Entries.size(entry.size())
                            + ")" : ""));
                    setIcon(entry.directory() ? FilesIcons.folder() : FilesIcons.file());
                } else {
                    setIcon(null);
                }
                return this;
            }
        });
        // Im gruppierten Baum hängt die Auswahl nicht an der Tabelle: Knöpfe auch hier nachführen.
        tree.addTreeSelectionListener(e -> enableActions());
        tree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && treeEntry() != null) {
                    open(treeEntry());
                }
            }
        });
        tree.getAccessibleContext().setAccessibleName("Nach Endung gruppiert");
        listMessage.setFont(Comic.regular(14f));
        listMessage.setForeground(Comic.MUTED);
        listArea.setOpaque(false);
        listArea.add(Comic.scroll(table), LIST);
        listArea.add(Comic.scroll(tree), TREE);
        listArea.add(Comic.scroll(navigation), NAVIGATION);
        navigation.addTreeSelectionListener(e -> enableActions());
        navigation.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (event.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(event)) {
                    open(navigation.selectedEntry());
                }
            }

            @Override
            public void mousePressed(MouseEvent event) {
                navigationPopup(event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                navigationPopup(event);
            }
        });
        bind(navigation, "ENTER", "ftp-open-tree", () -> open(navigation.selectedEntry()));
        bind(navigation, "DELETE", "ftp-delete-tree", this::delete);
        bind(navigation, "ctrl A", "ftp-select-tree", navigation::selectCurrentChildren);
        JPanel messagePanel = new JPanel(new BorderLayout());
        messagePanel.setBackground(Comic.SURFACE);
        messagePanel.add(listMessage, BorderLayout.CENTER);
        listArea.add(messagePanel, MESSAGE);
        message("Bitte HLQ eingeben (z.B. BENUTZERKENNUNG)", false);
        // Seitenleiste wie in NDV (EditorSideBar, Angelo 24.11.2026): erster Reiter die Datasets mit Kopf und Filter,
        // dann Ressourcen, Callees/Caller, Gliederung des gezeigten Editors und in der Diagramm-Ansicht deren
        // Werkzeuge; unten die gemeinsame Fußleiste mit den Reitern. Unter der Arbeitsfläche rechts die Leiste mit der
        // Lupe (DeckBars.withFind); nichts steht rechts neben Quelltext oder Diagramm.
        sideBar = new EditorSideBar(new EditorSideBar.Home(SIDE_DATASETS, FilesIcons.folder(), "Datasets",
                new com.aresstack.spaceharvester.app.ui.controls.SideHead(SIDE_DATASETS, filter)
                        .actions(sortButton, detailsButton, groupButton),
                listArea, com.aresstack.spaceharvester.app.ui.controls.SideFoot.buttons(collapseAll, expandAll)),
                List.of(), AppState.shared().get(SIDE_TAB_KEY),
                name -> AppState.shared().put(SIDE_TAB_KEY, name));
        javax.swing.JComponent side = sideBar;
        var split = new com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane(
                side, DeckBars.withFind(documentPanel), 280).withDividerToggle();
        add(split, BorderLayout.CENTER);

        download = new IconButton(ComposerIcons.download(), "Herunterladen … – gewähltes Member als Textdatei speichern");
        download.addActionListener(e -> download());
        upload = new IconButton(ComposerIcons.export(), "Hochladen … – lokale Textdatei hierher hochladen (als Member bzw. Datei)");
        upload.addActionListener(e -> upload());
        newMember = new IconButton(FilesIcons.newFile(), "Neues Member … – leeres Member in diesem Dataset anlegen");
        newMember.addActionListener(e -> askNew(false));
        newDataset = new IconButton(FilesIcons.newFolder(),
                "Neues Dataset … – unter diesem Pfad anlegen (mit DSORG, RECFM, LRECL …)");
        newDataset.addActionListener(e -> askNew(true));
        delete = new IconButton(Glyph.TRASH, DELETE_TIP);
        delete.addActionListener(e -> delete());
        submit = new IconButton(new PlayIcon(), "Als Job einreichen – gewähltes Member als JCL an JES übergeben");
        submit.addActionListener(e -> submit());
        save = new IconButton(FilesIcons.save(), "Speichern – Änderungen zurück auf den Host schreiben (Strg+S)");
        save.addActionListener(e -> {
            if (document != null) {
                document.save();
            }
        });
        reloadDocument = new IconButton(Glyph.REFRESH, "Member neu lesen; ungespeicherte Änderungen gehen verloren");
        reloadDocument.addActionListener(e -> reloadDocument());
        actions = Comic.row(download, upload, newMember, newDataset, submit, delete);
        // Die Knöpfe des Dokuments stehen rechts über dem Editor, damit die Leiste unten auch bei 960 px passt.
        documentsHead.add(Comic.row(reloadDocument, save), BorderLayout.EAST);
        editors.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        editors.onReveal(session -> SwingUtilities.invokeLater(() -> {
            if (pillOf.containsKey(session)) {
                showDocument(session);
            }
        }));
        editors.onClosed(session -> SwingUtilities.invokeLater(() -> removed(session)));
        editors.all().forEach(this::added);
        enableActions();
    }

    JPanel actions() {
        return actions;
    }

    void setConnected(boolean value) {
        if (!value) revokeDiagnosis();
        connected = value;
        enableActions();
    }

    /** Erstes Zeigen nach dem Verbinden: der HLQ des Benutzers. */
    void start() {
        FtpSession session = owner.session();
        owner.run(session.files(files -> files.home()), "Startpfad nicht ermittelt", home -> navigate(home, false));
    }

    /** Listet einen Pfad. */
    void navigate(String target, boolean remember) {
        navigate(target, remember ? Move.NEW : Move.STAY);
    }

    /** Wie der Pfad in den Verlauf kommt: neu (Vor wird verworfen), zurück, vor oder gar nicht (Neu laden). */
    private enum Move {
        NEW, BACK, FORWARD, STAY
    }

    private void navigate(String target, Move move) {
        navigate(target, move, () -> { });
    }

    private void navigate(String target, Move move, Runnable afterRefresh) {
        revokeDiagnosis();
        FtpSession session = owner.session();
        if (session.mvs() && (target == null || target.isBlank() || MvsPath.ROOT.equals(MvsPath.absolute(target)))) {
            message("Bitte HLQ eingeben (z.B. BENUTZERKENNUNG)", false);
            path.requestFocusInWindow();
            return;
        }
        String resolved = session.mvs() ? MvsPath.absolute(target) : (target == null || target.isBlank() ? "/" : target);
        boolean withDetails = details;
        boolean acquire = owner.navigationEnabled();
        owner.status("Liste " + resolved + " …", false);
        if (!navigatingTree && (model.all.isEmpty() || !resolved.equals(current))) {
            message("Lade Einträge …", false);
        }
        String from = current;
        long ticket = ++navigationTicket;
        owner.session().files(files -> files.list(resolved, withDetails || acquire)).whenComplete((list, error) ->
                SwingUtilities.invokeLater(() -> {
                    if (ticket != navigationTicket) {
                        return;
                    }
                    if (error != null) {
                        Throwable cause = error instanceof java.util.concurrent.CompletionException
                                && error.getCause() != null ? error.getCause() : error;
                        owner.status("Liste von " + resolved + " nicht geladen – " + FtpWorkspace.describe(cause), true);
                        if (!navigatingTree) {
                            message("Fehler: " + FtpWorkspace.describe(cause), true);
                        }
                        return;
                    }
                    if (!from.isEmpty() && !from.equals(resolved)) {
                        switch (move) {
                            case NEW -> {
                                history.push(from);
                                future.clear();
                            }
                            case BACK -> future.push(from);
                            case FORWARD -> history.push(from);
                            case STAY -> { }
                        }
                    }
                    current = resolved;
                    path.setText(document == null ? resolved : document.location());
                    model.columns(session.mvs(), withDetails);
                    widths();
                    model.show(list);
                    newBar.setVisible(false);
                    showList();
                    enableActions();
                    afterRefresh.run();
                    if (acquire) {
                        owner.navigationListed(list);
                    }
                }));
    }

    void back() {
        if (!history.isEmpty()) {
            navigate(history.pop(), Move.BACK);
        }
    }

    void forward() {
        if (!future.isEmpty()) {
            navigate(future.pop(), Move.FORWARD);
        }
    }

    /**
     * Springt {@code steps} Pfade weit im Verlauf (negativ zurück, positiv vor); die übersprungenen wandern wie im
     * Browser in die Gegenrichtung.
     */
    void go(int steps) {
        if (steps == -1) {
            back();
            return;
        }
        if (steps == 1) {
            forward();
            return;
        }
        Deque<String> from = steps < 0 ? history : future;
        Deque<String> to = steps < 0 ? future : history;
        int count = Math.abs(steps);
        if (count == 0 || from.size() < count) {
            return;
        }
        String now = current;
        for (int i = 0; i < count; i++) {
            if (!now.isEmpty()) {
                to.push(now);
            }
            now = from.pop();
        }
        navigate(now, Move.STAY);
    }

    /** Liste, Baum oder Hinweis zeigen und den Stand „n Einträge“ bzw. „x / y Einträge“ setzen. */
    private void showList() {
        int total = model.all.size();
        int shown = model.shown.size();
        boolean groupable = !owner.session().mvs();
        navigatingTree = !details && !(grouped && groupable) && !current.isEmpty();
        if (navigatingTree) {
            navigation.showPath(current, owner.session().mvs(), EntryList.sorted(model.all, sort), model.shown);
            listCards.show(listArea, NAVIGATION);
        } else if (total == 0) {
            message("Keine Einträge gefunden", false);
        } else if (grouped && groupable) {
            rebuildTree();
            listCards.show(listArea, TREE);
        } else {
            listCards.show(listArea, LIST);
        }
        if (!current.isEmpty()) {
            owner.status(current + " · " + (shown == total ? total + (total == 1 ? " Eintrag" : " Einträge")
                    : shown + " / " + total + " Einträge"), false);
        }
        groupButton.setVisible(groupable);
        groupButton.marked(grouped);
        enableActions();
        expandAll.setVisible(navigatingTree || groupable && grouped);
        collapseAll.setVisible(navigatingTree || groupable && grouped);
        updateDiagnosisTitle();
        sortButton.marked(sort != EntryList.Sort.NAME_ASC);
    }

    /** Hinweis statt Liste (MainframeMate: Overlay über der Liste); rot nur für Fehler. */
    private void message(String text, boolean error) {
        listMessage.setText(text);
        listMessage.setForeground(error ? Comic.RED : Comic.MUTED);
        listCards.show(listArea, MESSAGE);
    }

    String listMessage() {
        return listMessage.getText();
    }

    private void refilter() {
        model.apply();
        EntryList.Filtered result = model.lastFilter;
        boolean alarm = result != null && result.alarming(model.all.size());
        filter.setBackground(alarm ? Comic.CHANGE_REMOVED : javax.swing.UIManager.getColor("TextField.background"));
        if (model.all.isEmpty()) {
            return;
        }
        showList();
    }

    private javax.swing.JPopupMenu sortMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
        for (EntryList.Sort candidate : EntryList.Sort.values()) {
            javax.swing.JRadioButtonMenuItem item = new javax.swing.JRadioButtonMenuItem(candidate.label(),
                    candidate == sort);
            item.addActionListener(e -> sort(candidate));
            group.add(item);
            menu.add(item);
        }
        return menu;
    }

    /** Sortiert neu (Ordner bzw. Datasets bleiben vorn) und merkt sich die Wahl. */
    void sort(EntryList.Sort value) {
        sort = value;
        AppState.shared().put(SORT_KEY, value.name());
        refilter();
    }

    private void rebuildTree() {
        treeRoot.removeAllChildren();
        EntryList.grouped(model.shown).forEach((name, entries) -> {
            javax.swing.tree.DefaultMutableTreeNode group = new javax.swing.tree.DefaultMutableTreeNode(
                    name + " (" + entries.size() + ")");
            entries.forEach(entry -> group.add(new javax.swing.tree.DefaultMutableTreeNode(entry, false)));
            treeRoot.add(group);
        });
        ((javax.swing.tree.DefaultTreeModel) tree.getModel()).reload();
        expandTree(true);
    }

    private void expandTree(boolean open) {
        if (navigatingTree) {
            navigation.expandLoaded(open);
            return;
        }
        for (int row = tree.getRowCount() - 1; row >= 0; row--) {
            if (open) {
                tree.expandRow(row);
            } else {
                tree.collapseRow(row);
            }
        }
    }

    private RemoteEntry treeEntry() {
        javax.swing.tree.TreePath selection = tree.getSelectionPath();
        if (selection == null) {
            return null;
        }
        Object item = ((javax.swing.tree.DefaultMutableTreeNode) selection.getLastPathComponent()).getUserObject();
        return item instanceof RemoteEntry entry ? entry : null;
    }

    private void widths() {
        javax.swing.table.TableColumnModel columns = table.getColumnModel();
        int[] preferred = model.widths();
        for (int i = 0; i < Math.min(preferred.length, columns.getColumnCount()); i++) {
            columns.getColumn(i).setPreferredWidth(preferred[i]);
        }
    }

    /**
     * Tastaturführung wie MainframeMate ({@code ListKeyboardNavigation}): Enter öffnet, Pfeil links/rechts gehen zurück
     * und vor, Pfeil hoch/runter laufen am Ende im Kreis, aus Pfad- und Filterfeld springt Pfeil runter in die Liste.
     */
    private void installKeys() {
        bind(table, "LEFT", "ftp-back", this::back);
        bind(table, "RIGHT", "ftp-forward", this::forward);
        bind(table, "UP", "ftp-up-circular", () -> step(-1));
        bind(table, "DOWN", "ftp-down-circular", () -> step(1));
        bind(table, "ctrl A", "ftp-select-all", table::selectAll);
        bind(table, "DELETE", "ftp-delete", this::delete);
        for (javax.swing.JTextField field : new javax.swing.JTextField[] {path, filter}) {
            bind(field, "DOWN", "ftp-into-list", this::intoList);
        }
    }

    private static void bind(javax.swing.JComponent component, String key, String name, Runnable action) {
        component.getInputMap(javax.swing.JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    private void step(int delta) {
        int rows = table.getRowCount();
        if (rows == 0) {
            return;
        }
        int row = table.getSelectionModel().getLeadSelectionIndex();
        int next = row < 0 ? (delta > 0 ? 0 : rows - 1) : Math.floorMod(row + delta, rows);
        table.getSelectionModel().setSelectionInterval(next, next);
        table.scrollRectToVisible(table.getCellRect(next, 0, true));
    }

    private void intoList() {
        if (navigatingTree) {
            navigation.requestFocusInWindow();
            return;
        }
        if (table.getRowCount() > 0) {
            if (table.getSelectedRow() < 0) {
                table.getSelectionModel().setSelectionInterval(0, 0);
            }
            table.requestFocusInWindow();
        }
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.getSelectionModel().setSelectionInterval(row, row);
        }
        contextMenu().show(table, event.getX(), event.getY());
    }

    private void navigationPopup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        var selection = navigation.getPathForLocation(event.getX(), event.getY());
        if (selection == null) {
            return;
        }
        if (!navigation.isPathSelected(selection)) {
            navigation.setSelectionPath(selection);
        }
        contextMenu().show(navigation, event.getX(), event.getY());
    }

    /** Kontextmenü der Liste wie MainframeMate. */
    javax.swing.JPopupMenu contextMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        List<RemoteEntry> chosen = selectedAll();
        boolean mvs = owner.session().mvs();
        RemoteEntry single = chosen.size() == 1 ? chosen.get(0) : null;
        RemoteEntry openTarget = navigatingTree ? navigation.selectedEntry() : single;
        menu.add(item("Öffnen", openTarget != null, () -> open(openTarget)));
        menu.addSeparator();
        menu.add(item("Herunterladen …", readable(single), this::download));
        if (mvs) {
            menu.add(item("Binär herunterladen …", connected && readable(single), () -> downloadBinary(single)));
            menu.add(item("Hex-Ansicht …", connected && readable(single), () -> showHex(single)));
            menu.add(item("Binärdiagnose für MCP freigeben …", connected && readable(single), () -> armDiagnosis(single)));
            menu.add(item("Binärdiagnose beenden", diagnosis != null, this::revokeDiagnosis));
        }
        menu.add(item("Als Job einreichen", readable(single), this::submit));
        menu.addSeparator();
        menu.add(item(mvs ? "Neues Dataset …" : "Neuer Ordner …", connected && !current.isEmpty(),
                () -> askNew(true)));
        menu.add(item(mvs ? "Neues Member …" : "Neue Datei …", newMember.isEnabled(), () -> askNew(false)));
        menu.addSeparator();
        menu.add(item("Alles auswählen", table.getRowCount() > 0,
                navigatingTree ? navigation::selectCurrentChildren : table::selectAll));
        menu.add(item("Nichts auswählen", !chosen.isEmpty(),
                navigatingTree ? navigation::clearSelection : table::clearSelection));
        menu.addSeparator();
        menu.add(item("Löschen (" + chosen.size() + ") …", connected && !chosen.isEmpty(), this::delete));
        return menu;
    }

    void armDiagnosis(RemoteEntry entry) {
        if (!connected || !owner.session().mvs() || !readable(entry)
                || selectedAll().size() != 1 || !entry.path().equals(selected().path())) return;
        MvsDiagnosisSettings diagnosisSettings = MvsDiagnosisSettings.shared();
        if (!confirm.test("MCP-Binärdiagnose", "Originalbytes von " + entry.path()
                + " für gpt-oss-120b am konfigurierten KI-Endpunkt freigeben? "
                + "Der Chat kann bis zu " + diagnosisSettings.get(MvsDiagnosisSettings.Value.BUDGET_BYTES)
                + " Bytes aus einer Stichprobe von " + diagnosisSettings.get(MvsDiagnosisSettings.Value.SAMPLE_BYTES)
                + " Bytes abrufen (Einstellungen › Mainframe). "
                + "Die Übertragung wird vollständig gelesen. Chatantworten können im Verlauf stehen. "
                + "Nur bei einem autorisierten Endpunkt freigeben.")) return;
        revokeDiagnosis();
        diagnosis = new com.aresstack.spaceharvester.app.chat.MvsDiagnosisTools.Grant(owner.session(), entry);
        updateDiagnosisTitle();
    }

    com.aresstack.spaceharvester.app.chat.MvsDiagnosisTools.Grant diagnosis() {
        return diagnosis;
    }

    void revokeDiagnosis() {
        var previous = diagnosis;
        diagnosis = null;
        if (previous != null) {
            previous.revoke();
            updateDiagnosisTitle();
        }
    }

    private void updateDiagnosisTitle() {
        detailsButton.marked(details || diagnosis != null);
        detailsButton.setToolTipText(diagnosis == null ? "Detailtabelle statt Navigationsbaum (Größe, Datum, RECFM/LRECL)"
                : "MCP-Binärdiagnose freigegeben: " + diagnosis.path() + " (Kontextmenü zum Beenden)");
    }

    private static javax.swing.JMenuItem item(String text, boolean enabled, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    private void up() {
        if (current.isEmpty()) {
            return;
        }
        String parent = owner.session().mvs() ? MvsPath.parent(current) : unixParent(current);
        if (!MvsPath.ROOT.equals(parent)) {
            navigate(parent, true);
        }
    }

    private static String unixParent(String path) {
        int slash = path.endsWith("/") ? path.lastIndexOf('/', path.length() - 2) : path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }

    private void open(RemoteEntry entry) {
        if (entry == null) {
            return;
        }
        if (!owner.session().mvs()) {
            if (entry.directory()) {
                navigate(entry.path(), true);
            } else {
                openDocument(entry.path());
            }
            return;
        }
        // Wie MainframeMate (MvsConnectionTab.handleDoubleClick → MvsBrowserController.openResource): HLQ/Qualifier
        // und partitionierte Datasets öffnen als Liste, sequenzielle Datasets und Member im Editor.
        owner.run(owner.session().files(files -> files.opensAsFolder(entry)), "Öffnen von " + entry.path()
                + " fehlgeschlagen", folder -> {
                    if (folder) {
                        navigate(entry.path(), true);
                    } else {
                        openDocument(entry.path());
                    }
                });
    }

    private void openAddress() {
        String target = path.getText().strip();
        if (owner.session().mvs() && MvsPath.isMember(target)) {
            openDocument(MvsPath.absolute(target));
            return;
        }
        for (RemoteEntry entry : model.all) {
            if (entry.path().equals(target)) {
                open(entry);
                return;
            }
        }
        showDocument(null);
        sideBar.select(SIDE_DATASETS);
        navigate(target, true);
    }

    private void openDocument(String remotePath) {
        EditorSession session = editors.open(store, remotePath, FtpDocumentStore.name(remotePath));
        added(session);
        showDocument(session);
    }

    private static boolean partitioned(RemoteEntry entry) {
        return entry.details().organization().toUpperCase(Locale.ROOT).startsWith("PO");
    }

    /**
     * Ob sich ein Eintrag lesen lässt (Herunterladen, Einreichen): Dateien und Members immer, auf dem Mainframe auch
     * ein Dataset, das nicht als partitioniert bekannt ist – NLST sagt nicht, ob es sequenziell ist; ein PDS meldet
     * dann beim Lesen einen verständlichen Fehler.
     */
    private boolean readable(RemoteEntry entry) {
        if (entry == null) {
            return false;
        }
        if (!entry.directory()) {
            return true;
        }
        return owner.session().mvs() && !partitioned(entry) && MvsPath.unquote(entry.path()).contains(".");
    }

    // ---- Editor ----------------------------------------------------------------------------------------------------

    private void added(EditorSession session) {
        if (session.store() != store || pillOf.containsKey(session)) {
            return;
        }
        DocumentEditor editor = new DocumentEditor(session);
        sideBar.attach(editor);
        if (owner.session().mvs()) {
            // Wie MainframeMate (FileTabImpl „▶ Ausführen“, nur FTP + MVS + JCL): den Editor-Inhalt einreichen,
            // auch ungespeichert.
            editor.addAction("JCL als Job einreichen (▶ Ausführen)", new PlayIcon(), this::runJcl);
        }
        editorOf.put(session, editor);
        documents.add(editor, session.location());
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(FilesIcons.file(), session.name(), session.location(),
                "Dokument schließen (auch Mittelklick auf den Reiter)", () -> closeDocument(session));
        pill.addActionListener(e -> showDocument(session));
        documentGroup.add(pill);
        documentPills.add(pill);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> documentChanged(changed)));
        documentPills.revalidate();
        documentPills.repaint();
    }

    private void showDocument(EditorSession session) {
        document = session;
        path.setText(session == null ? current : session.location());
        ComposerToggleButton pill = session == null ? null : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        documentCards.show(documents, session == null ? HINT : session.location());
        if (session != null) {
            DocumentEditor editor = editorOf.get(session);
            if (editor != null) {
                sideBar.show(editor);
                SwingUtilities.invokeLater(editor::focusText);
            }
            documentStatus();
        }
        enableActions();
    }

    private void documentChanged(EditorSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill == null) {
            return;
        }
        boolean broken = session.state() == EditorSession.State.FAILED
                || session.state() == EditorSession.State.CONFLICT;
        pill.setIcon(broken ? FilesIcons.dot(Comic.RED) : session.dirty() ? FilesIcons.unsaved() : FilesIcons.file());
        pill.setToolTipText(session.location() + (session.dirty() ? " – ungespeicherte Änderungen" : ""));
        if (session == document) {
            documentStatus();
            enableActions();
        }
    }

    /** Der Stand des gezeigten Dokuments unten links; rot nur bei Fehler oder Konflikt. */
    private void documentStatus() {
        EditorSession session = document;
        String text = session.location() + " · " + session.state().label();
        boolean error = false;
        if (session.state() == EditorSession.State.READY && !session.writable()) {
            text += " · " + session.document().map(d -> d.writeRestriction()).orElse("Nur lesen");
            error = true;
        } else if (session.state() == EditorSession.State.FAILED || session.state() == EditorSession.State.CONFLICT) {
            text += session.problem().map(problem -> ": " + problem).orElse("");
            error = true;
        } else if (session.note().isPresent()) {
            text += " · " + session.note().get();
            error = session.note().get().startsWith("Nicht");
        } else if (session.dirty()) {
            text += " · ungespeicherte Änderungen";
        } else {
            text += session.text().map(content -> " · " + lines(content) + " Zeilen").orElse("");
        }
        owner.status(text, error);
    }

    private static int lines(String text) {
        return text.isEmpty() ? 0 : text.split("\n", -1).length;
    }

    private void removed(EditorSession session) {
        ComposerToggleButton pill = pillOf.remove(session);
        DocumentEditor editor = editorOf.remove(session);
        if (pill != null) {
            documentGroup.remove(pill);
            documentPills.remove(pill);
            documentPills.revalidate();
            documentPills.repaint();
        }
        if (editor != null) {
            documents.remove(editor);
        }
        if (session == document) {
            showDocument(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
        }
    }

    private void closeDocument(EditorSession session) {
        if (session.dirty() && !Dialogs.confirm(this, "Dokument schließen",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich schließen und verwerfen?")) {
            return;
        }
        editors.close(session);
    }

    private void reloadDocument() {
        EditorSession session = document;
        if (session == null) {
            return;
        }
        if (session.dirty() && !Dialogs.confirm(this, "Neu laden",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich neu laden und verwerfen?")) {
            return;
        }
        session.load();
    }

    /**
     * Schließt alle Dokumente dieser Sitzung (beim Schließen der Sitzung); bei ungespeicherten Änderungen erst nach
     * einer Rückfrage.
     *
     * @return {@code false}, wenn die Rückfrage verneint wurde
     */
    boolean closeDocuments() {
        List<EditorSession> open = new ArrayList<>(pillOf.keySet());
        long dirty = open.stream().filter(EditorSession::dirty).count();
        if (dirty > 0 && !Dialogs.confirm(this, "Sitzung schließen", (dirty == 1 ? "Ein Member hat"
                : dirty + " Members haben") + " ungespeicherte Änderungen. Wirklich schließen und verwerfen?")) {
            return false;
        }
        open.forEach(editors::close);
        return true;
    }

    /** Der eine gewählte Eintrag; bei Mehrfachauswahl der zuletzt angeklickte. */
    private RemoteEntry selected() {
        if (navigatingTree) {
            return navigation.selectedListedEntry();
        }
        if (grouped && !owner.session().mvs() && treeEntry() != null) {
            return treeEntry();
        }
        int row = table.getSelectionModel().getLeadSelectionIndex();
        if (row < 0 || !table.isRowSelected(row)) {
            row = table.getSelectedRow();
        }
        return row < 0 || row >= table.getRowCount() ? null : model.at(table.convertRowIndexToModel(row));
    }

    /** Alle gewählten Einträge (Mehrfachauswahl). */
    List<RemoteEntry> selectedAll() {
        if (navigatingTree) {
            return navigation.selectedEntries();
        }
        if (grouped && !owner.session().mvs()) {
            RemoteEntry entry = treeEntry();
            return entry == null ? List.of() : List.of(entry);
        }
        List<RemoteEntry> chosen = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            chosen.add(model.at(table.convertRowIndexToModel(row)));
        }
        return chosen;
    }

    private void enableActions() {
        RemoteEntry entry = selected();
        if (diagnosis != null && (!connected || entry == null || selectedAll().size() != 1
                || !diagnosis.path().equals(entry.path()))) revokeDiagnosis();
        boolean file = readable(entry);
        boolean mvs = owner.session().mvs();
        download.setEnabled(connected && file);
        submit.setEnabled(connected && file);
        int count = selectedAll().size();
        delete.setEnabled(connected && count > 0);
        delete.setToolTipText(count > 1 ? "Löschen (" + count + ") – " + DELETE_TIP : DELETE_TIP);
        upload.setEnabled(connected && !current.isEmpty());
        newMember.setEnabled(connected && !current.isEmpty() && (!mvs || MvsPath.unquote(current).contains(".")));
        newDataset.setEnabled(connected && !current.isEmpty());
        newMember.setToolTipText(mvs ? "Neues Member … – leeres Member in diesem Dataset anlegen"
                : "Neue Datei … – leere Datei in diesem Ordner anlegen");
        newDataset.setToolTipText(mvs ? "Neues Dataset … – unter diesem Pfad anlegen (mit DSORG, RECFM, LRECL …)"
                : "Neuer Ordner … – Ordner unter diesem Pfad anlegen");
        back.refresh();
        boolean showing = document != null;
        save.setVisible(showing);
        reloadDocument.setVisible(showing);
        if (showing) {
            EditorSession.State state = document.state();
            save.setEnabled(connected && document.writable() && document.dirty()
                    && (state == EditorSession.State.READY || state == EditorSession.State.CONFLICT));
            reloadDocument.setEnabled(connected && state != EditorSession.State.LOADING && state != EditorSession.State.SAVING);
        }
    }

    private void askNew(boolean dataset) {
        boolean mvs = owner.session().mvs();
        if (mvs && dataset) {
            createDataset();
            return;
        }
        if (mvs && EntryList.onlyDatasets(model.all)) {
            // Wie MainframeMate: hier gibt es nur Datasets darunter, also kein PDS für ein Member.
            if (confirm.test("Kein PDS", "Der Pfad " + current + " enthält nur Datasets und ist kein PDS; Members gibt "
                    + "es nur in einem PDS. Wirklich zuerst ein neues PDS als Dataset anlegen?")) {
                createDataset();
            }
            return;
        }
        creatingDataset = dataset;
        String where = current;
        newLabel.setText(dataset ? "Neuer Ordner:  " : (mvs ? "Erstellt Member in PDS " + where + ":  " : "Neue Datei:  "));
        newName.setPlaceholder(dataset ? "Name" : (mvs ? "max. 8 Zeichen, etwa NEWMEMBR" : "Name"));
        newName.setText("");
        newBar.setVisible(true);
        revalidate();
        newName.requestFocusInWindow();
    }

    private void create() {
        String name = newName.getText().trim();
        if (name.isEmpty()) {
            owner.status("Bitte einen Namen eingeben", true);
            return;
        }
        boolean mvs = owner.session().mvs();
        String target;
        if (mvs) {
            name = name.toUpperCase(Locale.ROOT);
            if (!creatingDataset && name.length() > 8) {
                // Wie MainframeMate: auf die erlaubten 8 Zeichen gekürzt.
                name = name.substring(0, 8);
            }
            if (!creatingDataset && !MvsPath.isMemberName(name)) {
                owner.status("„" + name + "“ ist kein gültiger Member-Name (1–8 Zeichen, Buchstaben, Ziffern, @ # $)",
                        true);
                return;
            }
            target = creatingDataset ? MvsPath.absolute(MvsPath.unquote(current) + "." + name)
                    : MvsPath.absolute(MvsPath.memberSpec(MvsPath.unquote(current), name));
        } else {
            target = current.endsWith("/") ? current + name : current + "/" + name;
        }
        String created = target;
        boolean dataset = creatingDataset;
        if (!dataset && !mayOverwrite(created)) {
            return;
        }
        owner.run(owner.session().filesOnce(files -> {
            if (dataset) {
                files.createDirectory(created);
            } else {
                files.write(created, "");
            }
            return created;
        }), "Anlegen von " + created + " fehlgeschlagen", done -> {
            newBar.setVisible(false);
            navigate(current, false);
            owner.status("Angelegt: " + done, false);
        });
    }

    /**
     * Löscht die gewählten Einträge nach einer Rückfrage mit ihren Namen (bis acht, dann „… und n weitere“), einen nach
     * dem anderen; danach „x gelöscht, y fehlgeschlagen“ mit den Gründen (MainframeMate).
     */
    private void delete() {
        List<RemoteEntry> chosen = selectedAll();
        if (chosen.isEmpty() || !connected) {
            return;
        }
        if (!confirm.test("Löschen bestätigen", deleteQuestion(chosen))) {
            return;
        }
        deleteEntries(chosen);
    }

    static String deleteQuestion(List<RemoteEntry> chosen) {
        if (chosen.size() == 1) {
            RemoteEntry entry = chosen.get(0);
            return "„" + entry.name() + "“ " + (entry.directory() ? "(Dataset bzw. Ordner) " : "") + "wirklich löschen?";
        }
        StringBuilder question = new StringBuilder(chosen.size() + " Einträge wirklich löschen?\n");
        chosen.stream().limit(8).forEach(entry -> question.append("\n  • ").append(entry.name()));
        if (chosen.size() > 8) {
            question.append("\n  … und ").append(chosen.size() - 8).append(" weitere");
        }
        return question.toString();
    }

    /** Löscht ohne Rückfrage (für Tests direkt). */
    void deleteEntries(List<RemoteEntry> chosen) {
        owner.status("Lösche " + chosen.size() + (chosen.size() == 1 ? " Eintrag" : " Einträge") + " …", false);
        owner.run(owner.session().filesOnce(files -> {
            List<String> failures = new ArrayList<>();
            List<RemoteEntry> deleted = new ArrayList<>();
            for (RemoteEntry entry : chosen) {
                try {
                    files.delete(entry.path());
                    deleted.add(entry);
                } catch (com.aresstack.spaceharvester.deck.ftp.FtpReplyException refused) {
                    failures.add("• " + entry.name() + " – " + refused.getMessage());
                }
            }
            return new DeleteResult(List.copyOf(deleted), failures);
        }), "Löschen fehlgeschlagen", result -> {
            boolean failed = !result.failures().isEmpty();
            String status = failed ? result.done() + " gelöscht, " + result.failures().size() + " fehlgeschlagen"
                    : result.done() == 1 ? "Gelöscht: " + chosen.get(0).name() : result.done() + " gelöscht";
            navigation.removed(result.deleted());
            // Preserve the operation result when the asynchronous refresh completes.
            navigate(current, Move.STAY, () -> owner.status(status, failed));
            owner.status(status, failed);
            if (failed) {
                report.accept(status + ":\n\n" + String.join("\n", result.failures()));
            }
        });
    }

    private record DeleteResult(List<RemoteEntry> deleted, List<String> failures) {
        int done() {
            return deleted.size();
        }
    }

    /** „Neues Dataset anlegen“ mit Anlageparametern (Dialog wie MainframeMate). */
    private void createDataset() {
        String context = MvsPath.unquote(current).replaceAll("(\\.\\*|\\*|\\.)+$", "");
        NewDataset request = datasetDialog.apply(context);
        if (request == null) {
            return;
        }
        createDataset(request);
    }

    /** Legt an, wie der Dialog es verlangt (für Tests direkt). */
    void createDataset(NewDataset request) {
        String target = MvsPath.absolute(NewDatasetForm.fullName(MvsPath.unquote(current), request.name()));
        owner.status("Lege " + target + " an …", false);
        owner.run(owner.session().filesOnce(files -> {
            files.createDataset(target, request.attributes());
            return target;
        }), "Anlegen von " + target + " fehlgeschlagen – bitte DCB-Parameter (RECFM, LRECL, BLKSIZE) und Platz prüfen",
                done -> {
                    navigate(current, false);
                    owner.status("Angelegt: " + done + " · " + request.attributes().summary(), false);
                });
    }

    private void download() {
        RemoteEntry entry = selected();
        if (!readable(entry)) {
            return;
        }
        JFileChooser chooser = SaveChooser.create(fileName(entry) + ".txt");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path target = chooser.getSelectedFile().toPath();
        owner.run(owner.session().files(files -> {
            RemoteFile file = files.read(entry.path());
            Files.writeString(target, file.text(), StandardCharsets.UTF_8);
            return target;
        }), "Herunterladen von " + entry.path() + " fehlgeschlagen",
                saved -> owner.status("Gespeichert: " + saved, false));
    }

    private void downloadBinary(RemoteEntry entry) {
        if (!readable(entry) || !connected) return;
        JFileChooser chooser = SaveChooser.create(fileName(entry) + ".bin");
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path target = chooser.getSelectedFile().toPath().toAbsolutePath();
        if (Files.exists(target) && !confirm.test("Überschreiben", target + " ersetzen?")) return;
        owner.run(owner.session().filesOnce(files -> {
            Path temporary = Files.createTempFile(target.getParent(), ".ftp-", ".part");
            try {
                try (var out = Files.newOutputStream(temporary)) {
                    files.downloadBinary(entry.path(), out);
                }
                Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return target;
            } finally {
                Files.deleteIfExists(temporary);
            }
        }), "Binärdownload fehlgeschlagen", saved -> owner.status("Binär gespeichert: " + saved, false));
    }

    private void showHex(RemoteEntry entry) {
        if (!readable(entry) || !connected) return;
        MvsDiagnosisSettings settings = MvsDiagnosisSettings.shared();
        int sampleBytes = settings.number(MvsDiagnosisSettings.Value.SAMPLE_BYTES);
        int pageBytes = settings.number(MvsDiagnosisSettings.Value.READ_BYTES);
        owner.run(owner.session().filesOnce(files -> files.readBinarySample(entry.path(), sampleBytes)),
                "Hex-Ansicht fehlgeschlagen", sample -> {
                    javax.swing.JTextArea content = new javax.swing.JTextArea(24, 100);
                    content.setEditable(false);
                    content.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
                    JPanel panel = new JPanel(new BorderLayout(4, 4));
                    JPanel navigation = new JPanel();
                    javax.swing.JSpinner offset = new javax.swing.JSpinner(new javax.swing.SpinnerNumberModel(
                            0, 0, Math.max(0, sample.capturedBytes() - 1), pageBytes));
                    navigation.add(new JLabel("Byteoffset:"));
                    navigation.add(offset);
                    navigation.add(new JLabel("Stichprobe: " + sample.capturedBytes() + " / " + sample.totalBytes() + " Bytes"));
                    Runnable render = () -> {
                        int position = (Integer) offset.getValue();
                        content.setText(com.aresstack.spaceharvester.app.chat.MvsDiagnosisTools.hex(
                                sample.slice(position, pageBytes), position));
                        content.setCaretPosition(0);
                    };
                    offset.addChangeListener(e -> render.run());
                    panel.add(navigation, BorderLayout.NORTH);
                    panel.add(new javax.swing.JScrollPane(content), BorderLayout.CENTER);
                    render.run();
                    Dialogs.view(this, "Hex · " + entry.path(), panel);
                });
    }

    private void upload() {
        JFileChooser chooser = new JFileChooser(SaveChooser.directory().toFile());
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path source = chooser.getSelectedFile().toPath();
        uploadFile(source);
    }

    /** Lädt eine lokale Textdatei in den gezeigten Pfad (für Tests direkt). */
    void uploadFile(Path source) {
        boolean mvs = owner.session().mvs();
        String base = source.getFileName().toString();
        int dot = base.indexOf('.');
        String stem = dot > 0 ? base.substring(0, dot) : base;
        String target;
        if (mvs) {
            String member = stem.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9@#$]", "");
            member = member.length() > 8 ? member.substring(0, 8) : member;
            if (!MvsPath.isMemberName(member) || !MvsPath.unquote(current).contains(".")) {
                owner.status("Hochladen geht in ein Dataset (PDS); der Dateiname muss als Member-Name taugen", true);
                return;
            }
            target = MvsPath.absolute(MvsPath.memberSpec(MvsPath.unquote(current), member));
        } else {
            target = current.endsWith("/") ? current + base : current + "/" + base;
        }
        String destination = target;
        if (!mayOverwrite(destination)) {
            return;
        }
        owner.run(owner.session().filesOnce(files -> {
            files.write(destination, asText(Files.readString(source, StandardCharsets.UTF_8)));
            return destination;
        }), "Hochladen nach " + destination + " fehlgeschlagen", done -> {
            navigate(current, false);
            owner.status("Hochgeladen: " + done, false);
        });
    }

    /**
     * Gibt es das Ziel in der gezeigten Liste schon, wird erst gefragt; sonst ginge sein Inhalt still verloren.
     *
     * @return ob geschrieben werden darf
     */
    private boolean mayOverwrite(String target) {
        boolean mvs = owner.session().mvs();
        RemoteEntry existing = model.all.stream()
                .filter(entry -> !entry.directory())
                .filter(entry -> mvs ? MvsPath.unquote(entry.path()).equalsIgnoreCase(MvsPath.unquote(target))
                        : entry.path().equals(target))
                .findFirst().orElse(null);
        if (existing == null) {
            return true;
        }
        boolean yes = confirmOverwrite.test("„" + existing.name() + "“ gibt es hier schon. Wirklich überschreiben? "
                + "Der bisherige Inhalt geht dabei verloren.");
        if (!yes) {
            owner.status("Nicht überschrieben: " + existing.name(), false);
        }
        return yes;
    }

    /**
     * Text einer lokalen Datei wie im Editor: Windows-Zeilenenden werden {@code \n}, ein letzter Umbruch fällt weg (sonst
     * entstünde auf dem Host ein leerer letzter Satz).
     */
    static String asText(String local) {
        String text = local.replace("\r\n", "\n");
        return text.endsWith("\n") ? text.substring(0, text.length() - 1) : text;
    }

    private void submit() {
        RemoteEntry entry = selected();
        if (!readable(entry)) {
            return;
        }
        FtpSession session = owner.session();
        owner.status("Reiche " + entry.path() + " ein …", false);
        owner.run(session.files(files -> files.read(entry.path()).text())
                        .thenCompose(jcl -> session.jobsOnce(jes -> jes.submit(jcl))),
                "Einreichen von " + entry.path() + " fehlgeschlagen", job -> {
                    owner.status(FtpJobsView.submitted(job), false);
                    owner.jobs().refreshLater(FtpJobsView.submitted(job));
                    showJobs.run();
                });
    }

    /** „▶ Ausführen“: den Text im Editor an JES übergeben, sofern er als JCL gelesen wird. */
    void runJcl(DocumentEditor editor) {
        if (editor.language().filter(language -> language == SourceLanguage.JCL).isEmpty()) {
            owner.status("Ausführen geht nur mit JCL – im Editor die Sprache JCL wählen", true);
            return;
        }
        String jcl = editor.text();
        String name = editor.session().name();
        FtpSession session = owner.session();
        owner.status("Reiche " + name + " ein …", false);
        owner.run(session.jobsOnce(jes -> jes.submit(jcl)), "Einreichen von " + name + " fehlgeschlagen", job -> {
            owner.status(FtpJobsView.submitted(job), false);
            owner.jobs().refreshLater(FtpJobsView.submitted(job));
            showJobs.run();
        });
    }

    private static String fileName(RemoteEntry entry) {
        String name = MvsPath.isMember(entry.path()) ? MvsPath.splitMember(entry.path())[1] : entry.name();
        return name.replaceAll("[\\\\/:*?\"<>|']", "_");
    }

    /** Für Tests. */
    List<RemoteEntry> shown() {
        return List.copyOf(model.shown);
    }

    String current() {
        return current;
    }

    /** Der Text im gezeigten Editor (leer ohne Dokument). */
    String documentText() {
        DocumentEditor editor = document == null ? null : editorOf.get(document);
        return editor == null ? "" : editor.text();
    }

    /** Schaltet die Detail-Spalten um wie der Knopf (für Tests). */
    void toggleDetails() {
        detailsButton.doClick();
    }

    /** Die Spaltenköpfe der Liste (für Tests). */
    List<String> columns() {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < model.getColumnCount(); i++) {
            names.add(model.getColumnName(i));
        }
        return names;
    }

    /** „Neues Member …“ wie aus dem Kontextmenü (für Tests). */
    void askNewMember() {
        askNew(false);
    }

    /** Filtert die Liste wie das Filterfeld (für Tests). */
    void filterFor(String text) {
        filter.setText(text);
    }

    boolean filterAlarming() {
        return Comic.CHANGE_REMOVED.equals(filter.getBackground());
    }

    /** Legt wie über „Neues Member …“ an (für Tests). */
    void createNamed(String name) {
        askNew(false);
        newName.setText(name);
        create();
    }

    EditorSession document() {
        return document;
    }

    DocumentEditor editor(EditorSession session) {
        return editorOf.get(session);
    }

    JTable table() {
        return table;
    }

    void openRow(int row) {
        RemoteEntry entry = model.at(row);
        // Select where the user would click: the navigation tree when it is shown, otherwise the table.
        var path = navigatingTree ? navigation.pathFor(entry.path()) : null;
        if (path != null) {
            navigation.setSelectionPath(path);
        } else {
            table.getSelectionModel().setSelectionInterval(row, row);
        }
        open(entry);
    }

    FtpNavigationTree navigationTree() {
        return navigation;
    }

    /** Die Einträge mit Sortierung und Filter; Spalten je nach MVS und Details. */
    private final class Entries extends AbstractTableModel {

        private final List<RemoteEntry> all = new ArrayList<>();
        private final List<RemoteEntry> shown = new ArrayList<>();
        private EntryList.Filtered lastFilter;
        private String[] columns = {"Name", "Art"};

        /** MVS ohne Details: Name, Art; MVS mit Details: dazu Format, Zeilen, Geändert, Benutzer; sonst Größe (und Datum). */
        void columns(boolean mvs, boolean withDetails) {
            String[] next = mvs ? (withDetails ? new String[] {"Name", "Art", "Format", "Zeilen", "Geändert", "Benutzer"}
                    : new String[] {"Name", "Art"})
                    : (withDetails ? new String[] {"Name", "Art", "Größe", "Geändert"} : new String[] {"Name", "Art", "Größe"});
            if (!java.util.Arrays.equals(columns, next)) {
                columns = next;
                fireTableStructureChanged();
            }
        }

        int[] widths() {
            return columns.length <= 3 ? new int[] {220, 110, 70} : new int[] {120, 90, 60, 55, 140, 70};
        }

        void show(List<RemoteEntry> entries) {
            all.clear();
            all.addAll(entries);
            apply();
        }

        void apply() {
            lastFilter = EntryList.filtered(EntryList.sorted(all, sort), filter.getText(), owner.session().mvs());
            shown.clear();
            shown.addAll(lastFilter.entries());
            fireTableDataChanged();
        }

        RemoteEntry at(int row) {
            return shown.get(row);
        }

        @Override
        public int getRowCount() {
            return shown.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int row, int column) {
            RemoteEntry entry = shown.get(row);
            boolean mvs = owner.session().mvs();
            return switch (columns[column]) {
                case "Name" -> entry.name();
                case "Art" -> entry.directory() ? (mvs ? (entry.details().organization().isEmpty() ? "Dataset"
                        : "Dataset (" + entry.details().organization() + ")") : "Ordner")
                        : (mvs ? (entry.path().contains("(") ? "Member" : "Dataset (sequenziell)") : "Datei");
                case "Format" -> entry.details().format();
                case "Zeilen" -> entry.details().lines() > 0 ? String.valueOf(entry.details().lines()) : "";
                case "Benutzer" -> entry.details().changedBy();
                case "Geändert" -> entry.modified().map(time -> DATE.format(time.atZone(java.time.ZoneId.systemDefault())))
                        .orElse("");
                default -> entry.size() < 0 ? "" : size(entry.size());
            };
        }

        static String size(long bytes) {
            if (bytes < 1024) {
                return bytes + " B";
            }
            if (bytes < 1024 * 1024) {
                return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
            }
            return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }

    private static final java.time.format.DateTimeFormatter DATE =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    /** Dreieck „Ausführen“ als 16-px-Symbol. */
    record PlayIcon() implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c == null || !c.isEnabled() ? Comic.MUTED : Comic.GREEN);
                g2.fillPolygon(new int[] {x + 4, x + 13, x + 4}, new int[] {y + 2, y + 8, y + 14}, 3);
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }

    /** Gezeichnetes 16-px-Symbol: Sortieren (Pfeil hoch und runter) oder Gruppieren (Liste mit Einzug). */
    private record DrawnIcon(boolean sort) implements javax.swing.Icon {
        @Override
        public void paintIcon(java.awt.Component c, java.awt.Graphics g, int x, int y) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            try {
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                        java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(c == null || !c.isEnabled() ? Comic.MUTED : Comic.INK);
                g2.setStroke(new java.awt.BasicStroke(1.6f, java.awt.BasicStroke.CAP_ROUND,
                        java.awt.BasicStroke.JOIN_ROUND));
                g2.translate(x, y);
                if (sort) {
                    g2.drawLine(5, 2, 5, 14);
                    g2.drawLine(2, 5, 5, 2);
                    g2.drawLine(8, 5, 5, 2);
                    g2.drawLine(11, 2, 11, 14);
                    g2.drawLine(8, 11, 11, 14);
                    g2.drawLine(14, 11, 11, 14);
                } else {
                    g2.drawLine(2, 3, 14, 3);
                    g2.drawLine(5, 7, 14, 7);
                    g2.drawLine(5, 11, 14, 11);
                    g2.drawLine(2, 15, 14, 15);
                }
            } finally {
                g2.dispose();
            }
        }

        @Override
        public int getIconWidth() {
            return 16;
        }

        @Override
        public int getIconHeight() {
            return 16;
        }
    }
}
