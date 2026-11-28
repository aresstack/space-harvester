package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.controls.BusyOverlay;
import com.aresstack.spaceharvester.app.ui.controls.InputFrame;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditor;
import com.aresstack.spaceharvester.deck.editor.DocumentStore;
import com.aresstack.spaceharvester.deck.editor.EditorSession;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.files.FileBrowser;
import com.aresstack.spaceharvester.deck.files.FileEntry;
import com.aresstack.spaceharvester.deck.files.FileSource;
import com.aresstack.spaceharvester.deck.files.SortMode;
import com.aresstack.spaceharvester.deck.access.ServerAccount;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.access.ServerCredentials;
import com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess;
import com.aresstack.spaceharvester.deck.files.sharepoint.SharePointPaths;
import com.aresstack.spaceharvester.deck.files.sharepoint.SharePointSite;
import com.aresstack.spaceharvester.deck.files.sharepoint.WebDavLogin;
import java.util.function.Supplier;
import javax.swing.JPasswordField;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import com.aresstack.spaceharvester.app.ui.theme.AppColors;

/**
 * Arbeitsbereich „Dateien“ des Command Deck (#1141/#1142), Funktionen aus dem lokalen Dateibrowser von MainframeMate
 * ({@code LocalConnectionTabImpl}): Ordner öffnen, Zurück/Vorwärts/Nach oben/Neu laden, Pfad eingeben, Orte
 * (Benutzerordner, freigegebene Ordner, Laufwerke), Regex-Filter, Sortierung, Ordner und Dateien anlegen, löschen und
 * Textdateien im gemeinsamen Editor öffnen. Dazu wie dort: nach Endung gruppieren (Gruppen auf- und zuklappen),
 * Details (Größe, Datum) ein/aus, alles/nichts auswählen, Symbole je Dateiart, Lesezeichen (Stern), Vorschau von
 * Dokumenten (Strg+Doppelklick, PDF/Office als Text) und SharePoint wie {@code SharePointConnectionTab}: Sites aus den
 * Quellen der Einstellungen unter „Orte“, jede SharePoint-Adresse im Pfadfeld, Anmeldung über die Windows-Anmeldung,
 * KeePass oder Benutzer und Kennwort; geblättert wird über WebDAV wie in einem lokalen Ordner.
 *
 * <p>Oben die Reiter als Pillen („Durchsuchen“ und je ein offenes Dokument), rechts daneben Filter und Sortierung;
 * darunter die Pfadzeile, in der Mitte die Liste, unten Status und die Aktionen des gewählten Reiters. Zustand und
 * Logik liegen im Anwendungsfall {@link FileBrowser} bzw. {@link EditorSessions}; die Oberfläche hört nur zu (kein
 * Abfragen). Gelesen wird erst, wenn der Bereich zum ersten Mal sichtbar ist; geschrieben nur auf ausdrücklichen Wunsch.
 */
public final class FilesWorkspace extends JPanel implements DeckBars.FootTools {

    /**
     * Stand dieses Bereichs links in der Statuszeile der App (über {@code CommandDeck}): Zustand, Hinweise, Fehler rot
     * ({@code StatusNote.Tone.ERROR}). Lange Texte kürzt das Label selbst, der volle Text steht im Tooltip.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.StatusLabel statusInfo =
            new com.aresstack.spaceharvester.app.ui.controls.StatusLabel();

    /** Die Karte dieses Bereichs in der Statuszeile der App. */
    public javax.swing.JComponent statusInfo() {
        return statusInfo;
    }

    /** Was gerade in der Statuszeile steht (für Tests und zum Vergleich). */
    private com.aresstack.spaceharvester.app.ui.controls.StatusNote shownStatus =
            com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(" ");

    /** Zeigt den Stand in der Statuszeile der App; Fehler rot. Die untere Leiste trägt nur Aktionen. */
    private void status(String text, boolean error) {
        shownStatus = new com.aresstack.spaceharvester.app.ui.controls.StatusNote(text.isBlank() ? " " : text,
                error ? com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR
                        : com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.PLAIN, null, null);
        statusInfo.show(shownStatus);
    }

    @Override
    public JComponent footTools() {
        return footTools;
    }

    static final String LOCATION_KEY = "deck.files.location";
    static final String SORT_KEY = "deck.files.sort";
    static final String BOOKMARKS_KEY = "deck.files.bookmarks";
    static final String GROUP_KEY = "deck.files.group";
    static final String DETAILS_KEY = "deck.files.details";
    private static final String BROWSER = "browser";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Locale.GERMANY);

    /** Der Browser des gezeigten Ordner-Reiters; jeder Ordner-Reiter hat seinen eigenen (Verlauf, Filter). */
    private FileBrowser browser;
    /** Die Ordner-Reiter, der erste immer da; weitere über „Neuer Reiter“ (wie Neue Verbindung in den anderen Bereichen). */
    private final List<BrowseTab> browseTabs = new ArrayList<>();
    private BrowseTab browseTab;
    private final ComposerToggleButton newPill;
    static final String TABS_KEY = "deck.files.tabs";
    private final EditorSessions editors;
    private final DocumentStore store;
    /** Vorschau von Dokumenten als Text (nur lesen); {@code null} ohne Vorschau. */
    private final DocumentStore preview;
    private final SharePointAccess sharePoint;
    private final Supplier<List<SharePointSite>> sites;
    private final ServerAccounts accounts;
    /**
     * Schutz vor der Kontosperre (Deck-Regel): Ein abgelehnter Login sperrt den Zugang für jeden automatischen Versuch,
     * bis eine Anmeldung klappt. KeePass-Zugänge über ihren Eintrag, gespeicherte über {@link #savedEntry}.
     */
    private com.aresstack.spaceharvester.deck.access.LoginGuard guard = DeckLogin.guard();
    /** Nach der ersten erfolgreichen Anmeldung gespeicherte SharePoint-Zugangsdaten (die App setzt den Tresor). */
    private volatile com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins savedLogins =
            com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins.inMemory();
    private final IconButton groupToggle = new IconButton(FilesIcons.group(), "Nach Dateiendung gruppieren");
    private final IconButton collapseAll = new IconButton(Glyph.COLLAPSE_ALL, "Alle Gruppen zuklappen");
    private final IconButton expandAll = new IconButton(Glyph.EXPAND_ALL, "Alle Gruppen aufklappen");
    private final IconButton detailsToggle = new IconButton(Glyph.TABLE, "Details (Größe, Geändert) ein/aus");
    private final IconButton star = new IconButton(Glyph.STAR, "Ordner als Lesezeichen merken");
    private final Set<String> collapsed = new HashSet<>();
    private TableColumn sizeColumn;
    private TableColumn dateColumn;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<EditorSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<EditorSession, DocumentEditor> editorOf = new LinkedHashMap<>();
    /** Fester Kartenname je Dokument – der Ort kann sich durch „Speichern unter“ ändern. */
    private final Map<EditorSession, String> cardOf = new LinkedHashMap<>();
    /** Dokumente (PDF, Word, PowerPoint, Excel) in den Anhang-Viewern, der Editor dahinter als Textansicht. */
    private final Map<EditorSession, DeckFileViewer> viewerOf = new LinkedHashMap<>();
    private int cardCounter;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final FileNavigationTree explorer = new FileNavigationTree(this::openEntry);
    private JPanel addressRow;
    private final JPanel browserTools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));

    /** Das eine Zurück wie im Space Harvester; gedrückt halten zeigt den Verlauf, Vor per Alt+Rechts/Maustaste. */
    private final com.aresstack.spaceharvester.app.ui.controls.BackButton back = new com.aresstack.spaceharvester.app.ui.controls.BackButton("Zurück (Alt+Links)");
    private final IconButton up = new IconButton(ComposerIcons.chevronUp(), "Übergeordneter Ordner (Rücktaste)");
    private final IconButton refresh = new IconButton(Glyph.REFRESH, "Neu laden (F5)");
    private final JTextField path = Comic.field(30);
    private final JTextField filter = Comic.field(14);
    private final InputFrame filterFrame;
    private final ComposerButton sortButton;
    private final JPanel nameRow = new JPanel(new BorderLayout(8, 0));
    private final JLabel nameLabel = Comic.caption("");
    private final JTextField name = Comic.field(24);
    private final EntryModel model = new EntryModel();
    private final JTable table = Comic.table(new JTable(model));
    private final JPanel browserActions;
    private final JPanel documentActions;
    private final javax.swing.JButton open;
    private final javax.swing.JButton indexFolder;
    private java.util.function.BiConsumer<Component, String> indexAction;
    private final javax.swing.JButton delete;
    private final javax.swing.JButton save;
    private final javax.swing.JButton reload;
    /** Neben der Lupe in der Fußleiste des Command Deck: die Aktionen des gewählten Reiters. */
    private final JPanel footTools = new JPanel(new CardLayout());
    private boolean creatingFolder;
    private boolean started;
    private EditorSession selected;

    /** Ohne Vorschau, SharePoint-Sites und Passwortspeicher (Tests). */
    public FilesWorkspace(FileBrowser browser, EditorSessions editors, DocumentStore store) {
        this(browser, editors, store, null, new SharePointAccess(new com.aresstack.spaceharvester.deck.files
                .sharepoint.windows.NetUseLogin(), Runnable::run), List::of, ServerAccounts.NONE);
    }

    /**
     * @param preview    Vorschau von Dokumenten als Text, {@code null} ohne
     * @param sharePoint Anmeldung an SharePoint (WebDAV)
     * @param sites      SharePoint-Sites für „Orte“, jeweils aktuell gelesen
     * @param accounts   Zugänge aus KeePass; passt einer zum SharePoint-Rechner, meldet er sich damit an
     */
    public FilesWorkspace(FileBrowser first, EditorSessions editors, DocumentStore store, DocumentStore preview,
                          SharePointAccess sharePoint, Supplier<List<SharePointSite>> sites, ServerAccounts accounts) {
        super(new BorderLayout());
        // Der Parameter heißt nicht „browser“: Rückrufe im Konstruktor sollen das Feld (gezeigter Reiter) lesen.
        this.browser = first;
        this.editors = editors;
        this.store = store;
        this.preview = preview;
        this.sharePoint = sharePoint;
        this.sites = sites;
        this.accounts = accounts;
        setBackground(Comic.SURFACE);

        // Kopfzeile: links die Reiter, rechts Filter und Sortierung der Liste (wie die Seitenansicht).
        pills.setOpaque(false);
        // „Neuer Reiter“ als letzte Pille wie „Neue Verbindung“ in Terminal, FTP und NDV (Angelo, 23.11.).
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neuer Reiter",
                "Weiteren Ordner in einem neuen Reiter öffnen (Strg+T)");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> newTab(null));
        pillGroup.add(newPill);
        pills.add(newPill);
        browseTab = addBrowseTab(first, false);
        filter.getAccessibleContext().setAccessibleName("Filter");
        filter.setToolTipText("<html>Regulärer Ausdruck auf den Namen, ohne Groß/klein<br>Beispiel: \\.jcl$</html>");
        filterFrame = InputFrame.search(filter);
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                browseTab.filter = filter.getText();
                browser.filter(filter.getText());
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                browseTab.filter = filter.getText();
                browser.filter(filter.getText());
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
            }
        });
        sortButton = Comic.button(ComposerIcons.chevronDown(), browser.sort().label(), "Sortierung");
        sortButton.addActionListener(e -> showSortMenu());
        browserTools.setOpaque(false);
        groupToggle.addActionListener(e -> group(!groupToggle.marked()));
        collapseAll.addActionListener(e -> {
            collapsed.addAll(model.groupKeys());
            render();
        });
        expandAll.addActionListener(e -> {
            collapsed.clear();
            render();
        });
        detailsToggle.addActionListener(e -> details(!detailsToggle.marked()));
        browserTools.add(groupToggle);
        browserTools.add(collapseAll);
        browserTools.add(expandAll);
        browserTools.add(detailsToggle);
        browserTools.add(sortButton);
        JPanel head = DeckBars.head(pills, browserTools);

        // Durchsuchen: Pfadzeile, Namenszeile (nur beim Anlegen), Liste.
        center.setOpaque(false);
        center.add(browserCard(), BROWSER);

        // Die Aktionen des gewählten Reiters stehen als Symbolknöpfe neben der Lupe in der Fußleiste des Command
        // Deck (footTools); der Stand steht in der Statuszeile der App. Ein Dokument schließt das Kreuz am Reiter.
        open = DeckBars.action(com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.openExternal(),
                "Öffnen: Ordner öffnen bzw. Datei im Editor öffnen (Enter)", this::openSelected);
        var newFolder = DeckBars.action(FilesIcons.newFolder(), "Neuer Ordner", () -> askName(true));
        var newFile = DeckBars.action(FilesIcons.newFile(), "Neue Datei", () -> askName(false));
        delete = DeckBars.action(Glyph.TRASH, "Gewählte Einträge löschen (Entf)", this::deleteSelected);
        indexFolder = DeckBars.action(Glyph.GRAPH_PLUS, "Ordner im Space Harvester indexieren", this::indexSelectedFolder);
        browserActions = DeckBars.tools(open, newFolder, newFile, delete, indexFolder);
        indexFolder.setVisible(false);
        save = DeckBars.action(FilesIcons.save(), "Änderungen speichern (Strg+S)", () -> {
            if (selected != null) {
                selected.save();
            }
        });
        reload = DeckBars.action(Glyph.REFRESH, "Datei neu laden; ungespeicherte Änderungen gehen verloren",
                this::reloadSelected);
        documentActions = DeckBars.tools(save, reload);
        footTools.setOpaque(false);
        footTools.add(browserActions, BROWSER);
        footTools.add(documentActions, "document");

        bind(this, KeyStroke.getKeyStroke(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK), "deck-new-tab",
                () -> newTab(null));
        JPanel reading = new JPanel(new BorderLayout());
        reading.setOpaque(false);
        reading.add(addressRow, BorderLayout.NORTH);
        reading.add(center, BorderLayout.CENTER);

        JPanel side = new JPanel(new BorderLayout(0, 6));
        side.setOpaque(false);
        JPanel sideHead = new JPanel(new BorderLayout(0, 4));
        sideHead.setOpaque(false);
        sideHead.add(new com.aresstack.spaceharvester.app.ui.controls.SideHead("Dateien", null), BorderLayout.NORTH);
        sideHead.add(filterFrame, BorderLayout.CENTER);
        side.add(sideHead, BorderLayout.NORTH);
        side.add(Comic.scroll(explorer), BorderLayout.CENTER);
        side.add(new com.aresstack.spaceharvester.app.ui.controls.SideFoot(null, null, null), BorderLayout.SOUTH);
        add(head, BorderLayout.NORTH);
        add(new com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane(
                side, DeckBars.withFind(reading), 280).withDividerToggle(), BorderLayout.CENTER);

        groupToggle.marked(AppState.shared().getBoolean(GROUP_KEY, false));
        collapseAll.setVisible(groupToggle.marked());
        expandAll.setVisible(groupToggle.marked());
        details(AppState.shared().getBoolean(DETAILS_KEY, true));
        String savedSort = AppState.shared().get(SORT_KEY);
        if (savedSort != null) {
            try {
                first.sort(SortMode.valueOf(savedSort));
            } catch (IllegalArgumentException ignored) {
                // Alter Wert: Standard bleibt.
            }
        }
        editors.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        editors.onReveal(session -> SwingUtilities.invokeLater(() -> {
            if (pillOf.containsKey(session)) {
                select(session);
            }
        }));
        editors.onClosed(session -> SwingUtilities.invokeLater(() -> removed(session)));
        editors.all().forEach(this::added);
        // Erst lesen, wenn man den Bereich sieht: das Öffnen der Perspektive fasst keine Dateien an.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                start();
            }
        });
        select(null);
    }

    /** Liest Textdateien wie der Editor (für den Chat). */
    DocumentStore textStore() {
        return store;
    }

    /** Liest Dokumente als Text wie die Vorschau (für den Chat); {@code null} ohne. */
    DocumentStore previewStore() {
        return preview;
    }

    /** Öffnet beim ersten Mal den zuletzt benutzten Ordner bzw. den Benutzerordner. */
    void start() {
        if (started) {
            return;
        }
        started = true;
        String last = AppState.shared().get(LOCATION_KEY);
        FileBrowser first = browseTabs.get(0).browser;
        first.open(last == null || last.isBlank() ? first.source().home() : last);
        String saved = AppState.shared().get(TABS_KEY);
        if (saved != null) {
            saved.lines().filter(line -> !line.isBlank()).forEach(location -> {
                BrowseTab tab = addBrowseTab(first.sibling(), true);
                tab.browser.open(location);
            });
        }
    }

    // ---- Ordner-Reiter ---------------------------------------------------------------------------------------------

    /** Ein Ordner-Reiter: eigener Browser (Ordner, Verlauf, Filter), Beschriftung = Ordnername. */
    private static final class BrowseTab {
        final FileBrowser browser;
        ComposerToggleButton pill;
        String filter = "";

        BrowseTab(FileBrowser browser) {
            this.browser = browser;
        }
    }

    /** Legt einen Ordner-Reiter an; der erste hat kein Kreuz, er bleibt immer. */
    private BrowseTab addBrowseTab(FileBrowser tabBrowser, boolean closable) {
        BrowseTab tab = new BrowseTab(tabBrowser);
        ComposerToggleButton pill = closable
                ? new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(FilesIcons.folder(), "Durchsuchen",
                        tabBrowser.source().name(), "Reiter schließen (auch Mittelklick auf den Reiter)",
                        () -> closeBrowseTab(tab))
                : new ComposerToggleButton(FilesIcons.folder(), "Durchsuchen", tabBrowser.source().name());
        tab.pill = pill;
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.addActionListener(e -> showBrowseTab(tab));
        pillGroup.add(pill);
        pills.add(pill, browseTabs.size());
        browseTabs.add(tab);
        tabBrowser.observe(changed -> SwingUtilities.invokeLater(() -> {
            titleBrowseTab(tab);
            if (changed == browser) {
                render();
            }
        }));
        pills.revalidate();
        pills.repaint();
        return tab;
    }

    /** Neuer Ordner-Reiter im gezeigten (oder angegebenen) Ordner. */
    void newTab(String location) {
        String where = location != null ? location : browser.location().orElse(browser.source().home());
        BrowseTab tab = addBrowseTab(browser.sibling(), true);
        showBrowseTab(tab);
        tab.browser.open(where);
        saveTabs();
    }

    private void showBrowseTab(BrowseTab tab) {
        browseTab = tab;
        browser = tab.browser;
        if (!filter.getText().equals(tab.filter)) {
            filter.setText(tab.filter);
        }
        // Reiterwechsel: Pfadfeld immer auf den Ort des neuen Reiters setzen, auch wenn es den Fokus hat.
        path.setText(browser.location().orElse(""));
        collapsed.clear();
        select(null);
        render();
    }

    private void closeBrowseTab(BrowseTab tab) {
        int index = browseTabs.indexOf(tab);
        if (index <= 0) {
            return;
        }
        browseTabs.remove(index);
        pillGroup.remove(tab.pill);
        pills.remove(tab.pill);
        pills.revalidate();
        pills.repaint();
        if (tab == browseTab) {
            showBrowseTab(browseTabs.get(Math.min(index, browseTabs.size() - 1)));
        }
        saveTabs();
    }

    private void titleBrowseTab(BrowseTab tab) {
        String location = tab.browser.location().orElse("");
        String title = location.isEmpty() ? "Durchsuchen" : folderName(location);
        if (!title.equals(tab.pill.getText())) {
            tab.pill.setText(title);
            tab.pill.getAccessibleContext().setAccessibleName(title);
            pills.revalidate();
        }
        tab.pill.setToolTipText(location.isEmpty() ? tab.browser.source().name() : location);
        if (tab != browseTabs.get(0)) {
            saveTabs();
        }
    }

    /** Letzter Teil eines Pfads (Laufwerk wie „C:\“ bleibt ganz). */
    static String folderName(String location) {
        String trimmed = location.replaceAll("[\\\\/]+$", "");
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String name = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
        return name.isEmpty() ? location : name;
    }

    /** Merkt die weiteren Ordner-Reiter (der erste steht unter {@link #LOCATION_KEY}). */
    private void saveTabs() {
        if (!started) {
            return;
        }
        StringBuilder lines = new StringBuilder();
        for (BrowseTab tab : browseTabs.subList(1, browseTabs.size())) {
            tab.browser.location().ifPresent(location -> lines.append(location).append('\n'));
        }
        AppState.shared().put(TABS_KEY, lines.toString());
    }

    private JComponent browserCard() {
        // Pfadzeile: Navigation, dann das Pfadfeld mit „Orte“ im Feld.
        back.navigator(com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator.of(
                () -> new com.aresstack.spaceharvester.app.ui.controls.BackButton.History(browser.forwardEntries(), browser.backEntries()),
                steps -> browser.go(steps)));
        up.addActionListener(e -> {
            select(null);
            browser.up();
        });
        refresh.addActionListener(e -> {
            if (selected == null) {
                browser.refresh();
            } else {
                reloadSelected();
            }
        });
        path.getAccessibleContext().setAccessibleName("Pfad");
        path.setToolTipText("<html>Pfad oder SharePoint-Adresse (https://…) eingeben und mit Enter öffnen</html>");
        path.addActionListener(e -> openAddress());
        InputFrame pathFrame = new InputFrame(path);
        star.addActionListener(e -> browser.location().ifPresent(this::toggleBookmark));
        pathFrame.addButton(star);
        JButton places = InputFrame.flatButton(null,
                "Orte: Benutzerordner, freigegebene Ordner, Laufwerke, Lesezeichen, SharePoint");
        places.setIcon(FilesIcons.places());
        places.addActionListener(e -> showPlaces(places));
        pathFrame.addButton(places);
        JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navigation.setOpaque(false);
        navigation.add(back);
        navigation.add(up);
        navigation.add(refresh);
        addressRow = new JPanel(new BorderLayout(8, 0));
        addressRow.setOpaque(false);
        addressRow.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        addressRow.add(navigation, BorderLayout.WEST);
        addressRow.add(pathFrame, BorderLayout.CENTER);

        // Namenszeile zum Anlegen: Feld mit „Anlegen“ im Feld, Esc bricht ab.
        name.getAccessibleContext().setAccessibleName("Name");
        InputFrame nameFrame = new InputFrame(name);
        JButton create = nameFrame.addButton(InputFrame.flatButton("Anlegen", "Mit diesem Namen anlegen (Enter)"));
        create.addActionListener(e -> createNamed());
        name.addActionListener(e -> createNamed());
        name.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "deck-cancel");
        name.getActionMap().put("deck-cancel", action(this::hideNameRow));
        nameRow.setOpaque(false);
        nameRow.add(nameLabel, BorderLayout.WEST);
        nameRow.add(nameFrame, BorderLayout.CENTER);
        nameRow.setVisible(false);

        JPanel rows = new JPanel();
        rows.setLayout(new javax.swing.BoxLayout(rows, javax.swing.BoxLayout.Y_AXIS));
        rows.setOpaque(false);
        rows.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));
        nameRow.setAlignmentX(LEFT_ALIGNMENT);
        rows.add(nameRow);

        // Liste: Name mit Symbol, Größe, Geändert; Mehrfachauswahl zum Löschen.
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean chosen, boolean focus,
                                                           int row, int column) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, chosen, focus, row, column);
                label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 18));
                return label;
            }
        };
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        // Größe und Datum fest schmal, der Name bekommt den Rest.
        fix(table, 1, 96);
        fix(table, 2, 150);
        sizeColumn = table.getColumnModel().getColumn(1);
        dateColumn = table.getColumnModel().getColumn(2);
        table.getAccessibleContext().setAccessibleName("Dateien");
        table.getSelectionModel().addListSelectionListener(e -> showActions());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent event) {
                if (!SwingUtilities.isLeftMouseButton(event)) {
                    return;
                }
                int row = table.rowAtPoint(event.getPoint());
                if (row >= 0 && model.group(row) != null && event.getClickCount() == 1) {
                    toggleGroup(model.group(row));
                } else if (event.getClickCount() == 2) {
                    if (event.isControlDown()) {
                        previewSelected();
                    } else {
                        openSelected();
                    }
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
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "deck-open", this::openSelected);
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "deck-delete", this::deleteSelected);
        bind(table, KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "deck-up", () -> browser.up());
        JComponent scroll = Comic.scroll(table);
        BusyOverlay overlay = new BusyOverlay(scroll);
        overlay.setText("Lade …");

        JPanel card = new JPanel(new BorderLayout());
        card.setOpaque(false);
        card.add(rows, BorderLayout.NORTH);
        card.add(overlay, BorderLayout.CENTER);
        card.putClientProperty(BusyOverlay.class, overlay);
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_F5, 0), "deck-refresh", () -> browser.refresh());
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK), "deck-back",
                () -> browser.back());
        bind(card, KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.ALT_DOWN_MASK), "deck-forward",
                () -> browser.forward());
        return card;
    }

    // ---- Anzeige ---------------------------------------------------------------------------------------------------

    private void render() {
        back.refresh();
        up.setEnabled(browser.canGoUp());
        String location = browser.location().orElse("");
        String address = selected == null ? location : selected.location();
        if (!path.isFocusOwner() && !path.getText().equals(address)) {
            path.setText(address);
        }
        if (!location.isEmpty() && !browser.loading() && browser.failure().isEmpty()) {
            explorer.showFolder(browser.source(), location, browser.visible());
        }
        if (!location.isEmpty() && !browser.loading() && browser.failure().isEmpty()
                && browser == browseTabs.get(0).browser) {
            AppState.shared().put(LOCATION_KEY, location);
        }
        filter.setBackground(browser.filterValid() ? AppColors.of("comic.fieldBg") : Comic.CHANGE_REMOVED);
        filterFrame.setBackground(filter.getBackground());
        Set<String> chosen = new HashSet<>();
        selectedEntries().forEach(entry -> chosen.add(entry.location()));
        model.show(browser.visible(), groupToggle.marked(), collapsed);
        for (int row = 0; row < model.getRowCount(); row++) {
            FileEntry entry = model.entry(row);
            if (entry != null && chosen.contains(entry.location())) {
                table.addRowSelectionInterval(row, row);
            }
        }
        boolean marked = !location.isEmpty() && bookmarks().contains(location);
        star.setIcon(marked ? Glyph.STAR_FILLED : Glyph.STAR);
        star.setToolTipText(marked ? "Lesezeichen entfernen" : "Ordner als Lesezeichen merken");
        sortButton.setText(browser.sort().label());
        busy().setBusy(browser.loading());
        showStatus();
        showActions();
    }

    private void showStatus() {
        if (selected != null) {
            showDocumentStatus();
            return;
        }
        Color color = Comic.MUTED;
        String text;
        if (browser.failure().isPresent()) {
            text = browser.location().orElse("") + ": " + browser.failure().get();
            color = Comic.RED;
        } else if (browser.message().isPresent()) {
            FileBrowser.Message message = browser.message().get();
            text = message.text();
            color = switch (message.tone()) {
                case ERROR -> Comic.RED;
                case SUCCESS -> Comic.STANDING_CURRENT;
                case INFO -> Comic.MUTED;
            };
        } else if (browser.loading()) {
            text = "Lade " + browser.location().orElse("") + " …";
        } else {
            int all = browser.entries().size();
            int shown = browser.visible().size();
            text = all == 1 ? "1 Eintrag" : all + " Einträge";
            if (shown != all) {
                text += " · " + shown + " passen zum Filter";
            }
            if (!browser.filterValid()) {
                text += " · Filter ist kein gültiger regulärer Ausdruck";
            }
        }
        status(text, color == Comic.RED);
    }

    private void showDocumentStatus() {
        EditorSession session = selected;
        String text = session.location() + " · " + session.state().label();
        Color color = Comic.MUTED;
        if (session.state() == EditorSession.State.FAILED || session.state() == EditorSession.State.CONFLICT) {
            text += session.problem().map(problem -> ": " + problem).orElse("");
            color = Comic.RED;
        } else if (session.note().isPresent()) {
            text += " · " + session.note().get();
            color = session.note().get().startsWith("Nicht") ? Comic.RED : Comic.STANDING_CURRENT;
        } else if (session.dirty()) {
            text += " · ungespeicherte Änderungen";
        }
        String charset = session.document().map(document -> " · " + document.charset()
                + ("\r\n".equals(document.lineEnding()) ? " · CRLF" : "")).orElse("");
        status(text + charset, color == Comic.RED);
    }

    private void showActions() {
        ((CardLayout) footTools.getLayout()).show(footTools, selected == null ? BROWSER : "document");
        int count = table.getSelectedRowCount();
        open.setEnabled(count == 1);
        delete.setEnabled(count > 0);
        indexFolder.setEnabled(indexAction != null && indexTarget().isPresent());
        if (selected != null) {
            EditorSession.State state = selected.state();
            save.setEnabled(selected.dirty() && (state == EditorSession.State.READY
                    || state == EditorSession.State.CONFLICT));
            reload.setEnabled(state != EditorSession.State.LOADING && state != EditorSession.State.SAVING);
        }
    }

    // ---- Reiter ----------------------------------------------------------------------------------------------------

    /** Zeigt die Liste ({@code null}) oder ein offenes Dokument. */
    void select(EditorSession session) {
        selected = session;
        path.setText(session == null ? browser.location().orElse("") : session.location());
        ComposerToggleButton pill = session == null ? browseTab.pill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        browserTools.setVisible(session == null);
        if (session == null) {
            cards.show(center, BROWSER);
            SwingUtilities.invokeLater(table::requestFocusInWindow);
        } else {
            String card = cardOf.get(session);
            if (card != null) {
                cards.show(center, card);
            }
            DocumentEditor editor = editorOf.get(session);
            if (editor != null) {
                SwingUtilities.invokeLater(editor::focusText);
            }
        }
        showStatus();
        showActions();
    }

    private void added(EditorSession session) {
        if ((session.store() != store && (preview == null || session.store() != preview))
                || pillOf.containsKey(session)) {
            return;
        }
        DocumentEditor editor = new DocumentEditor(session);
        editorOf.put(session, editor);
        String card = "doc-" + (++cardCounter);
        cardOf.put(session, card);
        if (session.store() == preview && DeckFileViewer.shows(session.name())) {
            DeckFileViewer viewer = new DeckFileViewer(java.nio.file.Path.of(session.location()), session.name(),
                    editor);
            viewerOf.put(session, viewer);
            center.add(viewer, card);
        } else {
            center.add(editor, card);
        }
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(FilesIcons.forName(session.name()),
                session.store() == preview ? session.name() + " (Vorschau)" : session.name(), session.location(),
                "Dokument schließen (auch Mittelklick auf den Reiter)", () -> closeDocument(session));
        pill.getAccessibleContext().setAccessibleName(session.name());
        pill.addActionListener(e -> select(session));
        pill.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                pillMenu(session, pill, event);
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                pillMenu(session, pill, event);
            }
        });
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> documentChanged(changed)));
        pills.revalidate();
        pills.repaint();
        select(session);
    }

    private void documentChanged(EditorSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill == null) {
            return;
        }
        pill.setIcon(session.state() == EditorSession.State.FAILED
                || session.state() == EditorSession.State.CONFLICT ? FilesIcons.dot(Comic.RED)
                : session.dirty() ? FilesIcons.unsaved() : FilesIcons.forName(session.name()));
        String title = session.store() == preview ? session.name() + " (Vorschau)" : session.name();
        if (!title.equals(pill.getText())) {
            pill.setText(title);
            pill.getAccessibleContext().setAccessibleName(session.name());
            pills.revalidate();
        }
        pill.setToolTipText(session.location() + (session.dirty() ? " – ungespeicherte Änderungen" : ""));
        if (session == selected) {
            if (!path.isFocusOwner()) {
                path.setText(session.location());
            }
            showStatus();
            showActions();
        }
    }

    private void removed(EditorSession session) {
        ComposerToggleButton pill = pillOf.remove(session);
        DocumentEditor editor = editorOf.remove(session);
        cardOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        DeckFileViewer viewer = viewerOf.remove(session);
        if (viewer != null) {
            viewer.close();
            center.remove(viewer);
        } else if (editor != null) {
            center.remove(editor);
        }
        if (session == selected) {
            select(null);
        }
    }

    private void closeDocument(EditorSession session) {
        if (session.dirty() && !Dialogs.confirm(this, "Dokument schließen",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich schließen und verwerfen?")) {
            return;
        }
        editors.close(session);
    }

    private void reloadSelected() {
        EditorSession session = selected;
        if (session == null) {
            return;
        }
        if (session.dirty() && !Dialogs.confirm(this, "Neu laden",
                "„" + session.name() + "“ hat ungespeicherte Änderungen. Wirklich neu laden und verwerfen?")) {
            return;
        }
        session.load();
    }

    // ---- Aktionen der Liste ----------------------------------------------------------------------------------------

    private void openSelected() {
        int[] rows = table.getSelectedRows();
        if (rows.length == 1 && model.group(rows[0]) != null) {
            toggleGroup(model.group(rows[0]));
            return;
        }
        List<FileEntry> chosen = selectedEntries();
        if (chosen.size() != 1) {
            return;
        }
        openEntry(chosen.get(0));
    }

    private void openEntry(FileEntry entry) {
        if (entry.directory()) {
            select(null);
            go(entry.location());
        } else if (preview != null && com.aresstack.spaceharvester.app.deck.DocumentPreviewStore
                .isDocument(entry.name())) {
            // PDF, Word, Excel … sind keine Textdateien: wie in MainframeMate als Vorschau öffnen.
            select(editors.open(preview, entry.location(), entry.name()));
        } else {
            EditorSession session = editors.open(store, entry.location(), entry.name());
            select(session);
        }
    }

    FileNavigationTree navigationTree() {
        return explorer;
    }

    private void openAddress() {
        String target = path.getText().strip();
        for (EditorSession session : pillOf.keySet()) {
            if (session.location().equals(target)) {
                select(session);
                return;
            }
        }
        for (FileEntry entry : browser.entries()) {
            if (entry.location().equals(target)) {
                openEntry(entry);
                return;
            }
        }
        select(null);
        go(target);
        table.requestFocusInWindow();
    }

    /** Strg+Doppelklick bzw. „Vorschau“: Datei schreibgeschützt öffnen, Dokumente als Text. */
    private void previewSelected() {
        List<FileEntry> chosen = selectedEntries();
        if (chosen.size() != 1 || chosen.get(0).directory()) {
            openSelected();
            return;
        }
        FileEntry entry = chosen.get(0);
        if (preview != null && com.aresstack.spaceharvester.app.deck.DocumentPreviewStore.isDocument(entry.name())) {
            select(editors.open(preview, entry.location(), entry.name()));
        } else {
            openSelected();
        }
    }

    private void toggleGroup(String key) {
        if (!collapsed.remove(key)) {
            collapsed.add(key);
        }
        render();
    }

    private void group(boolean on) {
        groupToggle.marked(on);
        collapseAll.setVisible(on);
        expandAll.setVisible(on);
        AppState.shared().put(GROUP_KEY, on);
        render();
    }

    private void details(boolean on) {
        detailsToggle.marked(on);
        AppState.shared().put(DETAILS_KEY, on);
        var columns = table.getColumnModel();
        if (sizeColumn == null) {
            return;
        }
        if (on && columns.getColumnCount() == 1) {
            columns.addColumn(sizeColumn);
            columns.addColumn(dateColumn);
        } else if (!on && columns.getColumnCount() == 3) {
            columns.removeColumn(dateColumn);
            columns.removeColumn(sizeColumn);
        }
    }

    // ---- Orte, Lesezeichen, SharePoint -----------------------------------------------------------------------------

    /** Öffnet einen Pfad; SharePoint-Adressen werden zum WebDAV-Pfad und brauchen vorher eine Anmeldung. */
    void go(String target) {
        FileBrowser tab = browser;
        String text = target == null ? "" : target.strip();
        if (SharePointPaths.isWebUrl(text)) {
            Optional<String> unc = SharePointPaths.toUncPath(text);
            if (unc.isEmpty()) {
                status("„" + text + "“ ist keine gültige SharePoint-Adresse.", true);
                return;
            }
            text = unc.get();
        }
        if (sharePoint.needsLogin(text)) {
            String unc = text;
            status("Melde an " + SharePointPaths.displayName(SharePointPaths.toWebUrl(unc)) + " an …", false);
            busy().setBusy(true);
            sharePoint.connect(unc, result -> SwingUtilities.invokeLater(() -> connected(tab, result)));
            return;
        }
        tab.open(text);
    }

    private void connected(FileBrowser tab, SharePointAccess.Result result) {
        busy().setBusy(browser.loading());
        if (result.connected()) {
            tab.open(result.uncPath());
            return;
        }
        if (!result.outcome().needsCredentials()) {
            status(result.outcome().reason(), true);
            return;
        }
        trySaved(tab, result.uncPath(), result.outcome().reason());
    }

    /** Woher gespeicherte SharePoint-Zugangsdaten kommen und wohin neue gehen; {@code null} = nur im Speicher. */
    public void savedLogins(com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins logins) {
        savedLogins = logins == null ? com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins.inMemory()
                : logins;
    }

    /**
     * Sperrschlüssel für gespeicherte SharePoint-Zugangsdaten: je Konto (Benutzer), denn gesperrt wird das Konto, gleich
     * auf welcher Site. Der Wächter legt den Schlüssel im App-Zustand ab, darum nie der Name im Klartext, sondern ein
     * kurzer SHA-256-Wert davon.
     */
    static String savedEntry(String user) {
        String name = user == null ? "" : user.strip().toLowerCase(Locale.ROOT);
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return "SharePoint " + java.util.HexFormat.of().formatHex(hash, 0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Hinweis im Dialog bei gesperrtem Konto, mit lesbarem Namen (der Schlüssel ist nur ein Hash). */
    private String lockedNotice(String user) {
        return guard.failure(savedEntry(user)).map(failure -> "Letzte Anmeldung als „" + user + "“ fehlgeschlagen: "
                + failure.reason() + ". Bitte Zugangsdaten prüfen.").orElse("");
    }

    /** Für Tests: ein eigener Wächter statt des gemeinsamen der App. */
    void loginGuard(com.aresstack.spaceharvester.deck.access.LoginGuard guard) {
        this.guard = guard;
    }

    private static String loginHost(String unc) {
        return SharePointPaths.host(unc).orElse("").replaceAll("(?i)@SSL", "").replaceAll("@\\d+$", "");
    }

    /** Erst die gespeicherten Zugangsdaten; passen sie nicht oder gibt es keine, weiter mit KeePass bzw. dem Dialog. */
    private void trySaved(FileBrowser tab, String unc, String reason) {
        busy().setBusy(true);
        com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins logins = savedLogins;
        Thread.ofVirtual().name("deck-sharepoint-saved").start(() -> {
            Optional<com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins.Login> saved = Optional.empty();
            try {
                saved = logins.load(loginHost(unc));
            } catch (Exception e) {
                // Tresor nicht lesbar: weiter wie ohne Gespeichertes
            }
            if (saved.isEmpty()) {
                SwingUtilities.invokeLater(() -> tryKeePass(tab, unc, reason, ""));
                return;
            }
            var login = saved.get();
            String entry = savedEntry(login.user());
            if (!guard.mayTry(entry)) {
                // Letzter Versuch abgelehnt: nicht noch einmal automatisch, der Mensch prüft im Dialog.
                java.util.Arrays.fill(login.password(), '\0');
                SwingUtilities.invokeLater(() -> askLogin(tab, unc, lockedNotice(login.user()), login.user()));
                return;
            }
            sharePoint.connect(unc, login.user(), login.password(), result -> SwingUtilities.invokeLater(() -> {
                busy().setBusy(browser.loading());
                if (result.connected()) {
                    guard.succeeded(entry);
                    tab.open(unc);
                } else {
                    if (result.outcome().needsCredentials()) {
                        guard.failed(entry, result.outcome().reason());
                    }
                    tryKeePass(tab, unc, "Gespeicherte Zugangsdaten passen nicht: " + result.outcome().reason(),
                            login.user());
                }
            }));
            java.util.Arrays.fill(login.password(), '\0');
        });
    }

    private void tryKeePass(FileBrowser tab, String unc, String reason, String user) {
        String host = loginHost(unc);
        // Wie MainframeMate: dann KeePass (Eintrag mit diesem Rechner), zuletzt fragen.
        Thread.ofVirtual().name("deck-sharepoint-keepass").start(() -> {
            Optional<ServerAccount> account = Optional.empty();
            try {
                account = accounts.accounts().stream().filter(a -> host.equalsIgnoreCase(a.host())).findFirst();
            } catch (Exception ignored) {
                // KeePass nicht gekoppelt: fragen
            }
            Optional<ServerAccount> found = account;
            SwingUtilities.invokeLater(() -> {
                if (found.isPresent() && !guard.mayTry(found.get().entry())) {
                    askLogin(tab, unc, DeckLogin.notice(found.get().entry()), found.get().user(),
                            found.get().entry());
                } else if (found.isPresent() && !reason.startsWith("KeePass")) {
                    loginWithKeePass(tab, unc, found.get());
                } else {
                    askLogin(tab, unc, reason, found.map(ServerAccount::user).orElse(user),
                            found.map(ServerAccount::entry).orElse(null));
                }
            });
        });
    }

    private void loginWithKeePass(FileBrowser tab, String unc, ServerAccount account) {
        busy().setBusy(true);
        Thread.ofVirtual().name("deck-sharepoint-login").start(() -> {
            try (ServerCredentials credentials = accounts.credentials(account.entry())) {
                char[] password = credentials.copyPassword();
                sharePoint.connect(unc, credentials.user(), password, result -> SwingUtilities.invokeLater(() -> {
                    busy().setBusy(browser.loading());
                    if (result.connected()) {
                        guard.succeeded(account.entry());
                        tab.open(unc);
                    } else {
                        if (result.outcome().needsCredentials()) {
                            guard.failed(account.entry(), result.outcome().reason());
                        }
                        askLogin(tab, unc, "KeePass-Eintrag „" + account.entry() + "“ passt nicht: "
                                + result.outcome().reason(), credentials.user(), account.entry());
                    }
                }));
                java.util.Arrays.fill(password, '\0');
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> askLogin(tab, unc, "KeePass nicht lesbar ("
                        + e.getClass().getSimpleName() + ")", account.user()));
            }
        });
    }

    private void askLogin(FileBrowser tab, String unc, String reason, String user) {
        askLogin(tab, unc, reason, user, null);
    }

    /**
     * Anmeldedialog wie {@code SharePointAuthenticator.showLoginDialog} in MainframeMate.
     *
     * @param keePassEntry der KeePass-Eintrag, dessen Ablehnung zum Dialog führte; klappt die Anmeldung mit seinem
     *                     Benutzer, ist das Konto wieder frei und die Sperre des Eintrags fällt (sonst bliebe er für
     *                     immer gesperrt, denn automatisch wird er nicht mehr versucht)
     */
    private void askLogin(FileBrowser tab, String unc, String reason, String user, String keePassEntry) {
        busy().setBusy(browser.loading());
        JTextField userField = Comic.field(24);
        userField.setText(user == null ? "" : user);
        JPasswordField passwordField = new JPasswordField(24);
        Comic.style(passwordField);
        JPanel form = new JPanel(new java.awt.GridLayout(0, 1, 0, 4));
        form.setOpaque(false);
        form.add(Comic.wrapped("Anmeldung an " + SharePointPaths.toWebUrl(unc) + (reason == null ? ""
                : " – " + reason), Comic.MUTED, 12f));
        form.add(Comic.caption("Benutzer (z. B. DOMÄNE\\name)"));
        form.add(new InputFrame(userField));
        form.add(Comic.caption("Kennwort"));
        form.add(new InputFrame(passwordField));
        if (!Dialogs.formOverlay(this, "SharePoint-Anmeldung", form, "Anmelden")) {
            status("Anmeldung abgebrochen", false);
            return;
        }
        char[] password = passwordField.getPassword();
        busy().setBusy(true);
        String entered = userField.getText();
        com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins logins = savedLogins;
        sharePoint.connect(unc, entered, password, result -> SwingUtilities.invokeLater(() -> {
            busy().setBusy(browser.loading());
            if (result.connected()) {
                guard.succeeded(savedEntry(entered));
                if (keePassEntry != null && user != null && entered.strip().equalsIgnoreCase(user.strip())) {
                    guard.succeeded(keePassEntry);
                }
                // Nach dem ersten Erfolg merken: beim nächsten Mal kein Dialog (Angelo, 23.11.).
                Thread.ofVirtual().name("deck-sharepoint-save").start(() -> {
                    try {
                        logins.save(loginHost(unc), entered, password);
                    } catch (Exception e) {
                        SwingUtilities.invokeLater(() -> status("Zugangsdaten nicht gespeichert ("
                                + e.getClass().getSimpleName() + ")", true));
                    } finally {
                        java.util.Arrays.fill(password, '\0');
                    }
                });
                tab.open(unc);
            } else {
                java.util.Arrays.fill(password, '\0');
                if (result.outcome().needsCredentials()) {
                    guard.failed(savedEntry(entered), result.outcome().reason());
                }
                askLogin(tab, unc, result.outcome().reason(), entered, keePassEntry);
            }
        }));
    }

    List<String> bookmarks() {
        String saved = AppState.shared().get(BOOKMARKS_KEY);
        if (saved == null || saved.isBlank()) {
            return List.of();
        }
        return saved.lines().filter(line -> !line.isBlank()).toList();
    }

    void toggleBookmark(String location) {
        List<String> marks = new ArrayList<>(bookmarks());
        if (!marks.remove(location)) {
            marks.add(location);
        }
        AppState.shared().put(BOOKMARKS_KEY, String.join("\n", marks));
        render();
    }

    private void openBookmark(String location) {
        java.nio.file.Path path;
        try {
            path = java.nio.file.Path.of(location);
        } catch (RuntimeException e) {
            go(location);
            return;
        }
        if (java.nio.file.Files.isRegularFile(path)) {
            String fileName = path.getFileName() == null ? location : path.getFileName().toString();
            boolean document = preview != null
                    && com.aresstack.spaceharvester.app.deck.DocumentPreviewStore.isDocument(fileName);
            select(editors.open(document ? preview : store, location, fileName));
        } else {
            go(location);
        }
    }

    private void pillMenu(EditorSession session, Component pill, MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        boolean marked = bookmarks().contains(session.location());
        menu.add(item(marked ? "Lesezeichen entfernen" : "Als Lesezeichen merken",
                () -> toggleBookmark(session.location())));
        if (session.store().canSaveAs()) {
            menu.add(item("Speichern unter …", () -> {
                select(session);
                DocumentEditor editor = editorOf.get(session);
                if (editor != null) {
                    editor.saveAsChosen();
                }
            }));
        }
        menu.add(item("Pfad kopieren", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(session.location()), null)));
        menu.addSeparator();
        menu.add(item("Schließen", () -> closeDocument(session)));
        menu.show(pill, event.getX(), event.getY());
    }

    private void askName(boolean folder) {
        creatingFolder = folder;
        nameLabel.setText(folder ? "Neuer Ordner:" : "Neue Datei:");
        name.setText("");
        nameRow.setVisible(true);
        nameRow.getParent().revalidate();
        name.requestFocusInWindow();
    }

    private void createNamed() {
        String text = name.getText();
        if (creatingFolder) {
            browser.createFolder(text);
        } else {
            browser.createFile(text);
        }
        if (com.aresstack.spaceharvester.deck.files.FileNames.problem(text).isEmpty()) {
            hideNameRow();
        }
    }

    private void hideNameRow() {
        nameRow.setVisible(false);
        nameRow.getParent().revalidate();
        table.requestFocusInWindow();
    }

    private void deleteSelected() {
        List<FileEntry> doomed = selectedEntries();
        if (doomed.isEmpty()) {
            return;
        }
        StringBuilder list = new StringBuilder();
        for (int i = 0; i < Math.min(doomed.size(), 5); i++) {
            list.append("\n• ").append(doomed.get(i).name());
        }
        if (doomed.size() > 5) {
            list.append("\n… und ").append(doomed.size() - 5).append(" weitere");
        }
        String question = (doomed.size() == 1 ? "„" + doomed.get(0).name() + "“ wirklich löschen?"
                : doomed.size() + " Einträge wirklich löschen?" + list)
                + "\nOrdner werden nur gelöscht, wenn sie leer sind.";
        if (Dialogs.confirm(this, "Löschen", question)) {
            browser.delete(doomed);
        }
    }

    private void popup(MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        int row = table.rowAtPoint(event.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        }
        JPopupMenu menu = new JPopupMenu();
        List<FileEntry> chosen = selectedEntries();
        if (chosen.size() == 1) {
            menu.add(item("Öffnen", this::openSelected));
            FileEntry entry = chosen.get(0);
            if (entry.directory()) {
                menu.add(item("In neuem Reiter öffnen", () -> newTab(entry.location())));
            }
            if (!entry.directory() && preview != null
                    && com.aresstack.spaceharvester.app.deck.DocumentPreviewStore.isDocument(entry.name())) {
                menu.add(item("Vorschau (Strg+Doppelklick)", this::previewSelected));
            }
            menu.add(item("Pfad kopieren", () -> Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(entry.location()), null)));
            boolean marked = bookmarks().contains(entry.location());
            menu.add(item(marked ? "Lesezeichen entfernen" : "Als Lesezeichen merken",
                    () -> toggleBookmark(entry.location())));
            menu.addSeparator();
        }
        if (indexAction != null && indexTarget().isPresent()) {
            menu.add(item("Ordner indexieren …", this::indexSelectedFolder));
            menu.addSeparator();
        }
        menu.add(item("Alles auswählen", table::selectAll));
        menu.add(item("Nichts auswählen", table::clearSelection));
        menu.addSeparator();
        menu.add(item("Neuer Ordner …", () -> askName(true)));
        menu.add(item("Neue Datei …", () -> askName(false)));
        if (!chosen.isEmpty()) {
            menu.addSeparator();
            menu.add(item(chosen.size() == 1 ? "Löschen …" : chosen.size() + " Einträge löschen …",
                    this::deleteSelected));
        }
        menu.show(table, event.getX(), event.getY());
    }

    public void folderIndexAction(java.util.function.BiConsumer<Component, String> action) {
        indexAction = action;
        indexFolder.setVisible(action != null);
        showActions();
    }

    private Optional<String> indexTarget() {
        if (selected != null || browser.loading() || browser.failure().isPresent()) {
            return Optional.empty();
        }
        List<FileEntry> chosen = selectedEntries();
        String location = chosen.isEmpty() ? browser.location().orElse(null)
                : chosen.size() == 1 && chosen.getFirst().directory() ? chosen.getFirst().location() : null;
        if (location == null || location.startsWith("\\\\") || SharePointPaths.isWebUrl(location)) {
            return Optional.empty();
        }
        try {
            return java.nio.file.Path.of(location).isAbsolute() ? Optional.of(location) : Optional.empty();
        } catch (java.nio.file.InvalidPathException invalid) {
            return Optional.empty();
        }
    }

    private void indexSelectedFolder() {
        if (indexAction != null) {
            indexTarget().ifPresent(location -> indexAction.accept(this, location));
        }
    }

    public void indexStatus(String message, boolean error) {
        status(message, error);
    }

    private void showPlaces(Component anchor) {
        JPopupMenu menu = new JPopupMenu();
        for (FileSource.Place place : browser.source().places()) {
            menu.add(item(place.label(), () -> go(place.location())));
        }
        List<String> marks = bookmarks();
        if (!marks.isEmpty()) {
            menu.addSeparator();
            menu.add(heading("Lesezeichen"));
            for (String mark : marks) {
                menu.add(item(mark, () -> openBookmark(mark)));
            }
        }
        List<SharePointSite> known = sites.get();
        menu.addSeparator();
        menu.add(heading("SharePoint"));
        for (SharePointSite site : known == null ? List.<SharePointSite>of() : known) {
            JMenuItem entry = item(site.name(), () -> go(site.url()));
            entry.setToolTipText(site.url());
            menu.add(entry);
        }
        menu.add(item("SharePoint-Adresse eingeben …", () -> {
            path.setText("https://");
            path.requestFocusInWindow();
            path.setCaretPosition(path.getText().length());
        }));
        menu.show(anchor, 0, anchor.getHeight());
    }

    private static JMenuItem heading(String text) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(Comic.semiBold(12f));
        item.setEnabled(false);
        return item;
    }

    private void showSortMenu() {
        JPopupMenu menu = new JPopupMenu();
        ButtonGroup group = new ButtonGroup();
        for (SortMode mode : SortMode.values()) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(mode.label(), mode == browser.sort());
            item.setFont(Comic.regular(13f));
            item.addActionListener(e -> {
                browser.sort(mode);
                AppState.shared().put(SORT_KEY, mode.name());
            });
            group.add(item);
            menu.add(item);
        }
        menu.show(sortButton, 0, sortButton.getHeight());
    }

    private List<FileEntry> selectedEntries() {
        List<FileEntry> chosen = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            if (row >= 0 && row < model.getRowCount() && model.entry(row) != null) {
                chosen.add(model.entry(row));
            }
        }
        return chosen;
    }

    private BusyOverlay busy() {
        return (BusyOverlay) ((JComponent) center.getComponent(0)).getClientProperty(BusyOverlay.class);
    }

    private static JMenuItem item(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> action.run());
        return item;
    }

    private static AbstractAction action(Runnable run) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                run.run();
            }
        };
    }

    private static void bind(JComponent component, KeyStroke key, String name, Runnable run) {
        int condition = component instanceof JTable ? JComponent.WHEN_FOCUSED
                : JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT;
        component.getInputMap(condition).put(key, name);
        component.getActionMap().put(name, action(run));
    }

    private static void fix(JTable table, int column, int width) {
        var col = table.getColumnModel().getColumn(column);
        col.setMinWidth(width);
        col.setMaxWidth(width);
        col.setPreferredWidth(width);
    }

    static String size(FileEntry entry) {
        if (entry.directory()) {
            return "";
        }
        long bytes = entry.size();
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format(Locale.GERMANY, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.GERMANY, "%.1f MB", bytes / (1024.0 * 1024.0));
    }

    // ---- für Tests -------------------------------------------------------------------------------------------------

    FileBrowser browser() {
        return browser;
    }

    JTable table() {
        return table;
    }

    JTextField pathField() {
        return path;
    }

    JTextField filterField() {
        return filter;
    }

    JTextField nameField() {
        return name;
    }

    boolean nameRowShown() {
        return nameRow.isVisible();
    }

    String statusText() {
        return shownStatus.text();
    }

    EditorSession selected() {
        return selected;
    }

    /** Die Anhang-Viewer eines Dokuments; {@code null} bei Text. */
    DeckFileViewer viewer(EditorSession session) {
        return viewerOf.get(session);
    }

    DocumentEditor editor(EditorSession session) {
        return editorOf.get(session);
    }

    /** Für Tests: zeigt den Ordner-Reiter an dieser Stelle. */
    void showBrowseTab(int index) {
        showBrowseTab(browseTabs.get(index));
    }

    /** Für Tests: schließt den Ordner-Reiter an dieser Stelle wie sein Kreuz. */
    void closeBrowseTab(int index) {
        closeBrowseTab(browseTabs.get(index));
    }

    List<String> pillTitles() {
        List<String> titles = new ArrayList<>();
        browseTabs.forEach(tab -> titles.add(tab.pill.getText()));
        pillOf.values().forEach(pill -> titles.add(pill.getText()));
        return titles;
    }

    void group(boolean on, boolean ignored) {
        group(on);
    }

    void detailsShown(boolean on) {
        details(on);
    }

    int columnCount() {
        return table.getColumnCount();
    }

    String rowLabel(int row) {
        Object value = model.getValueAt(row, 0);
        return value instanceof FileEntry entry ? entry.name() : ((Row) value).label() + " (" + ((Row) value).count()
                + ")";
    }

    void openRow(int row) {
        table.setRowSelectionInterval(row, row);
        openSelected();
    }

    void newFolder() {
        askName(true);
    }

    void newFile() {
        askName(false);
    }

    void confirmName() {
        createNamed();
    }

    // ---- Tabelle ---------------------------------------------------------------------------------------------------

    /** Eine Zeile der Liste: Gruppe (nach Endung) oder Eintrag. */
    private record Row(String group, String label, int count, FileEntry entry) {
    }

    private static final class EntryModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Name", "Größe", "Geändert"};
        private static final String FOLDERS = "\u0000Ordner";
        private static final String NONE = "\uffff";
        private List<Row> rows = List.of();

        /** Zeigt die Einträge, gruppiert nach Endung (Ordner zuerst), zugeklappte Gruppen ohne ihre Einträge. */
        void show(List<FileEntry> entries, boolean grouped, Set<String> collapsed) {
            List<Row> next = new ArrayList<>();
            if (!grouped) {
                entries.forEach(entry -> next.add(new Row(null, null, 0, entry)));
            } else {
                java.util.TreeMap<String, List<FileEntry>> groups = new java.util.TreeMap<>();
                for (FileEntry entry : entries) {
                    groups.computeIfAbsent(key(entry), k -> new ArrayList<>()).add(entry);
                }
                groups.forEach((key, members) -> {
                    next.add(new Row(key, label(key), members.size(), null));
                    if (!collapsed.contains(key)) {
                        members.forEach(entry -> next.add(new Row(null, null, 0, entry)));
                    }
                });
            }
            if (!next.equals(rows)) {
                rows = List.copyOf(next);
                fireTableDataChanged();
            }
        }

        static String key(FileEntry entry) {
            if (entry.directory()) {
                return FOLDERS;
            }
            String extension = entry.extension();
            return extension == null || extension.isEmpty() ? NONE : extension.toUpperCase(Locale.ROOT);
        }

        static String label(String key) {
            return switch (key) {
                case FOLDERS -> "Ordner";
                case NONE -> "Ohne Endung";
                default -> key;
            };
        }

        /** Der Eintrag einer Zeile; {@code null} für eine Gruppenzeile. */
        FileEntry entry(int row) {
            return rows.get(row).entry();
        }

        /** Schlüssel der Gruppe einer Zeile; {@code null} für einen Eintrag. */
        String group(int row) {
            return row < 0 || row >= rows.size() ? null : rows.get(row).group();
        }

        List<String> groupKeys() {
            return rows.stream().map(Row::group).filter(java.util.Objects::nonNull).toList();
        }

        boolean collapsedRow(int row, Set<String> collapsed) {
            return collapsed.contains(rows.get(row).group());
        }

        @Override
        public int getRowCount() {
            return rows.size();
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
        public Object getValueAt(int row, int column) {
            Row line = rows.get(row);
            if (line.entry() == null) {
                return column == 0 ? line : "";
            }
            FileEntry entry = line.entry();
            return switch (column) {
                case 0 -> entry;
                case 1 -> size(entry);
                default -> entry.modified() == null ? ""
                        : DATE.format(entry.modified().atZone(ZoneId.systemDefault()));
            };
        }
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {

        NameRenderer() {
            putClientProperty("html.disable", Boolean.TRUE);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int column) {
            if (value instanceof Row group) {
                JLabel label = (JLabel) super.getTableCellRendererComponent(table, group.label() + " (" + group.count()
                        + ")", selected, focus, row, column);
                label.setFont(Comic.semiBold(13f));
                boolean closed = table.getModel() instanceof EntryModel model
                        && model.collapsedRow(row, ((FilesWorkspace) SwingUtilities.getAncestorOfClass(
                        FilesWorkspace.class, table)).collapsed);
                label.setIcon(closed ? Glyph.EXPAND : Glyph.COLLAPSE);
                label.setIconTextGap(6);
                return label;
            }
            FileEntry entry = (FileEntry) value;
            JLabel label = (JLabel) super.getTableCellRendererComponent(table, entry.name(), selected, focus, row,
                    column);
            label.setFont(Comic.regular(13f));
            label.setIcon(entry.directory() ? FilesIcons.folder() : FilesIcons.forName(entry.name()));
            label.setIconTextGap(8);
            return label;
        }
    }

    /** Maustaste Zurück (wie Alt+Links): im gezeigten Reiter einen Schritt zurück. */
    void navigateBack() {
        if (browser != null) {
            browser.back();
        }
    }

    /** Maustaste Vor (wie Alt+Rechts). */
    void navigateForward() {
        if (browser != null) {
            browser.forward();
        }
    }
}
