package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.spaceharvester.app.ui.controls.ComicDivider;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowHoldCloseButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowResizer;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowShape;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import com.aresstack.enterpriseai.ui.comic.theme.ComicTheme;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.brand.ApplicationIcons;
import com.aresstack.spaceharvester.app.ui.content.ContentView;

import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Das Hauptfenster: rahmenloses Comic-Fenster wie der Enterprise AI Client. Oben die Icon-Leiste (Tooltip mit Namen,
 * Strg+1 … Strg+5): links die Suche und daneben die Reiter der Seitenansicht (Leseansicht, Text, Eigenschaften,
 * Anhänge, Analysen, Erweitert) als Umschalter der Mitte; rechts Aufträge, dann abgetrennt
 * Wissensquellen verwalten und Einstellungen, dann ✕ (Angelo, 13.11.2026: der Baum links wählt, die Reiter oben
 * entscheiden, wie die Mitte das Gewählte zeigt). Links dauerhaft der {@link Explorer} über
 * alle Wissensquellen (Breite veränderbar, einklappbar), rechts die Arbeitsfläche des gewählten Bereichs; über der
 * Statusleiste die feste Aktionszeile des Bereichs ({@link View#actions()}). Der Fortschritt der Aufträge füllt den
 * Hintergrund der Statuszeile ({@link StatusLine}). Navigation und
 * Explorer sind getrennte Bausteine. {@link #content} baut den Inhalt ohne Fenster (für Bildschirmfotos und Tests).
 */
public final class MainWindow implements AppContext.Navigator {

    static final int WINDOW_PADDING = 4;
    static final int RESIZE_GRIP = 6;

    private final AppContext context;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final Map<AppContext.Section, View> views = new EnumMap<>(AppContext.Section.class);
    private final Map<AppContext.Section, ComposerToggleButton> nav = new EnumMap<>(AppContext.Section.class);
    /** Die Bereichsknöpfe; ohne gewählten Knopf (Inhalte über die Reiter) ist keiner dunkel. */
    private final ButtonGroup navGroup = new ButtonGroup();
    /** Abstand zwischen der Suche und den Reitern der Seitenansicht in der Icon-Leiste. */
    private static final int TAB_GAP = 12;
    /** Der dauerhafte Explorer links: Wissensquelle → Space → Seite → Unterseite. */
    private final Explorer explorer;
    /**
     * Linker Drawer: der Explorer, unter Wissensquellen verwalten stattdessen die Quellenkarten, unter Einstellungen die
     * Bereiche der Einstellungen untereinander.
     */
    private final CardLayout sideCards = new CardLayout();
    private final JPanel side = new JPanel(sideCards);
    private static final String EXPLORER = "explorer";
    /** Die nächste geladene Seite im Explorer zeigen (gesetzt von {@link #openPage} und {@link #openPassage}). */
    private boolean revealOpened;
    /** Aktions- und Fortschrittszeilen der Bereiche, direkt über der Statusleiste. */
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionDeck = new JPanel(actionCards);
    static final int SIDEBAR_WIDTH = 280;
    private ComicSplitPane split;
    /** Arbeitsfläche links, Chat-Seitenleiste rechts (Angelo, 21.11.2026). */
    private javax.swing.JSplitPane chatSplit;
    private com.aresstack.spaceharvester.app.ui.content.ChatSidebar chatSidebar;
    private ComposerToggleButton chatToggle;
    private boolean chatOpen;
    /** Eine Antwort kam, während die Leiste zu war: der Chat-Knopf trägt bis zum Aufklappen einen Punkt. */
    private boolean chatUnread;
    private int chatWidth = com.aresstack.spaceharvester.app.ui.content.ChatSidebar.DEFAULT_WIDTH;
    static final String CHAT_OPEN_KEY = "chat.open";
    static final String CHAT_WIDTH_KEY = "chat.width";
    static final String CHAT_LARGE_KEY = "chat.large";
    /** Die Leiste über die ganze Arbeitsfläche („Groß anzeigen“); der Explorer bleibt. */
    private boolean chatLarge;
    static final String CHAT_TOOLTIP = "Chat (Strg+J)";
    static final String CHAT_UNREAD_TOOLTIP = "Chat (Strg+J) · neue Antwort";
    /** Leere Karte der Aktionszeile für Bereiche ohne eigene Aktionen. */
    private static final String JOBS = "jobs";
    private JobStatusLine jobStatus;
    private final JLabel status = Comic.muted(" ");
    /** ✕ hinter dem Stand der App: ein Ergebnis (etwa eines Exports) bleibt stehen, bis es der Benutzer wegklickt. */
    private final ComposerButton statusClose = closeButton();
    /** So lange bleibt eine kurze Meldung stehen. */
    private static final int NOTICE_MS = 8000;
    private final javax.swing.Timer statusClear = new javax.swing.Timer(NOTICE_MS, e -> clearStatus());
    /** Bis die Laufzeit steht, zeigt die Statuszeile „Startet …“. */
    private boolean starting = true;
    /** Stand des sichtbaren Bereichs ({@link View#statusInfo()}) links in der Statuszeile, vor dem Stand der App. */
    private final CardLayout viewStatusCards = new CardLayout();
    /**
     * Der Stand des sichtbaren Bereichs links in der Statuszeile. So breit wie der Stand, der gerade gezeigt wird:
     * Ein Kartenstapel wäre sonst so breit wie sein breitester, auch unsichtbarer Stand (etwa der lange Stand der
     * Suche) und schöbe den Auftragstext samt Zurück und Vor nach rechts, neben eine leere Fläche.
     */
    private final JPanel viewStatus = new JPanel(viewStatusCards) {
        @Override
        public java.awt.Dimension getPreferredSize() {
            for (java.awt.Component card : getComponents()) {
                if (card.isVisible()) {
                    return card.getPreferredSize();
                }
            }
            return new java.awt.Dimension(0, 0);
        }
    };
    /** Die Statuszeile; ihr Hintergrund ist der Fortschrittsbalken der Aufträge. */
    private StatusLine statusLine;
    /** „Confluence verlangt einen Einmalcode“ ganz links in der Statuszeile, solange eine Anforderung offen ist. */
    private SignInNotice signInNotice;
    /** „Proxy-Skript seit … nicht erneuert“ daneben, solange das Erneuern des PAC-Skripts scheitert. */
    private ProxyNotice proxyNotice;
    /** Suche im sichtbaren Bereich, über der Statuszeile und nur unter der Arbeitsfläche. */
    private final FindBar findBar;
    /** Platz der Zeitleiste über der Suchleiste ({@code TimelineEvent}). */
    private final JPanel timelineSlot = new JPanel(new BorderLayout());
    /** Platz einer Fläche über dem Inhaltsbereich ({@code ContentOverlayEvent}); sichtbar nur unter „Inhalte“. */
    private final JPanel overlaySlot = new JPanel(new BorderLayout());
    private AppContext.Section current = AppContext.Section.CONTENT;
    /** Die Auswahl links beim letzten Verlassen der Suche; zurück in der Suche gilt sie wieder. */
    private Explorer.Remembered searchSelection;
    /** Der zuletzt gezeigte Arbeitsbereich vor den Einstellungen: dorthin führt „Zurück“ im Drawer. */
    private AppContext.Section beforeSettings = AppContext.Section.CONTENT;
    /** Tooltips der freien Flächen der Fensterleiste: Der Doppelklick ersetzt den fehlenden Vergrößern-Knopf. */
    static final String MAXIMIZE_TOOLTIP = "Doppelklick: Vollbild";
    static final String RESTORE_TOOLTIP = "Doppelklick: Verkleinern";
    private final JPanel top = new JPanel(new HeaderLayout(12)) {
        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            // Nur die freien Flächen fragen hier: Knöpfe und Felder zeigen ihre eigenen Tooltips.
            return windowBarTooltip(
                    ComicWindowDragger.isMaximized(javax.swing.SwingUtilities.getWindowAncestor(this)));
        }
    };

    /** Was der Doppelklick auf die Fensterleiste gerade tut. */
    static String windowBarTooltip(boolean maximized) {
        return maximized ? RESTORE_TOOLTIP : MAXIMIZE_TOOLTIP;
    }

    /** Die Fensterleiste (für Tests). */
    JPanel windowBar() {
        return top;
    }
    private final StatusView statusView;
    private final SourcesView sources;
    private final ContentView content;
    private final ActivitiesView activities;
    private final SearchView searchView;
    private final SettingsView settings;

    public MainWindow(AppContext context) {
        this.context = context;
        context.setNavigator(this);
        statusView = new StatusView(context);
        sources = new SourcesView(context);
        content = new ContentView(context);
        activities = new ActivitiesView(context);
        searchView = new SearchView(context);
        settings = new SettingsView(context);
        settings.onLeave(() -> show(beforeSettings));
        explorer = new Explorer(context);
        // Die Auswahl links ist der Bereich der Suche, aber nur, solange die Suche zu sehen ist. Was man woanders
        // anklickt (etwa einen Treffer), ändert ihn nicht; zurück in der Suche gilt die gemerkte Auswahl
        // (Angelo, 21.11.2026).
        explorer.onScope(scopes -> {
            if (current == AppContext.Section.SEARCH) {
                searchView.scope(scopes);
            }
        });
        // Links gewählt bestimmt rechts (Angelo, 13.11.2026): der Reiter „Export“ folgt der Auswahl im Explorer.
        // Die Auswahl links bestimmt die Inhalte, darunter Begriffe, Fachglossar und Graph unter Analysen (ADR 0029).
        explorer.onSelection(content::selection);
        searchView.onSubmit(() -> show(AppContext.Section.SEARCH));
        // Über den Navigator geöffnete Seiten (etwa ein Suchtreffer) zeigt der Explorer aufgeklappt und gewählt.
        content.onPosition(position -> {
            explorer.currentPage(position);
            if (position != null && revealOpened) {
                revealOpened = false;
                explorer.revealPage(position);
            }
        });
        // Quelle oder Space in der Bereichsanzeige angeklickt: im Baum wählen; die Auswahl bestimmt wie immer die Mitte.
        content.onLocate(explorer::reveal);
        // Zurück/Vor zeigt eine Seite wieder: der Baum wählt sie, ohne die Mitte erneut zu laden.
        content.onLocatePage(explorer::revealPage);
        // Die Diagnose ist kein eigener Bereich mehr, sondern die Seite „Diagnose“ der Einstellungen.
        settings.diagnosis(statusView);
        views.put(AppContext.Section.SOURCES, sources);
        views.put(AppContext.Section.CONTENT, content);
        views.put(AppContext.Section.ACTIVITIES, activities);
        views.put(AppContext.Section.SEARCH, searchView);
        views.put(AppContext.Section.SETTINGS, settings);
        deck.setBackground(Comic.SURFACE);
        views.forEach((section, view) -> deck.add(view.component(), section.name()));
        actionDeck.setOpaque(false);
        // Der Fortschritt der Aufträge steht global in der Statuszeile; ohne eigene Aktionszeile bleibt die Zeile leer.
        actionDeck.add(new JPanel(), JOBS);
        views.forEach((section, view) -> {
            if (view.actions() != null && view != activities) {
                actionDeck.add(view.actions(), section.name());
            }
        });
        viewStatus.setOpaque(false);
        viewStatus.add(new JPanel(), JOBS);
        views.forEach((section, view) -> {
            if (view.statusInfo() != null) {
                view.statusInfo().setOpaque(false);
                viewStatus.add(view.statusInfo(), section.name());
            }
        });
        findBar = new FindBar(() -> views.get(current).component());
        statusClear.setRepeats(false);
        context.events().subscribe(StatusMessageEvent.class, this::showStatus);
        context.events().subscribe(com.aresstack.spaceharvester.app.event.TimelineEvent.class, this::timeline);
        context.events().subscribe(com.aresstack.spaceharvester.app.event.ContentOverlayEvent.class, this::overlay);
        context.onChange(ignored -> updateStatus());
        updateStatus();
        // „Nach dem Update Änderungen anzeigen“ (ADR 0030): eine neue Marke öffnet die Updates ihrer Quelle.
        UpdateFollower.install(context, this::openSource, content::shownSource);
    }

    /** Öffnet das rahmenlose Fenster; Schließen beendet die Laufzeit (Rückfrage bei laufenden Aufträgen). */
    public JFrame open() {
        ComicTheme.installMenuDefaults(Comic.PALETTE);
        JFrame frame = new JFrame("Space Harvester");
        frame.setUndecorated(true);
        ApplicationIcons.apply(frame);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel surface = content(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)),
                () -> frame.setState(Frame.ICONIFIED));
        frame.setContentPane(surface);
        ComicWindowShape.install(frame, surface, Comic.PALETTE, WINDOW_PADDING, ResearchUiMetrics.RADIUS_WINDOW);
        frame.setMinimumSize(new Dimension(960, 600));
        frame.setSize(new Dimension(1280, 820));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (confirmClose(frame)) {
                    frame.dispose();
                    context.shutdown();
                    System.exit(0);
                }
            }
        });
        frame.setVisible(true);
        // Die App startet in der Leseansicht; zur Suche geht es erst mit einer Eingabe oben (Angelo, 21.11.2026).
        show(AppContext.Section.CONTENT);
        return frame;
    }

    /** Öffnet beim ersten Start den Einrichtungsassistenten, solange die Datei keinen Abschluss kennt. */
    public static void offerSetup(Component parent, AppContext context) {
        if (SetupWizard.due(context)) {
            SetupWizard.open(parent, context);
        }
    }

    /**
     * Der Fensterinhalt: Tintenrand, Kopfzeile mit Titel, Icon-Leiste in zwei Gruppen und ✕, Explorer und
     * Arbeitsfläche mit Aktionszeile und Suche, Statuszeile mit Fortschritt.
     *
     * @param close    Gedrückthalten des ✕
     * @param minimize Klick auf das ✕
     */
    public JPanel content(Runnable close, Runnable minimize) {
        JPanel surface = new JPanel(new BorderLayout());
        surface.setBackground(Comic.SURFACE);
        surface.setBorder(ComicBorder.windowBorder(Comic.PALETTE, WINDOW_PADDING));

        JLabel title = new JLabel("Space Harvester");
        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        title.setIcon(ApplicationIcons.titleLogo());
        title.setIconTextGap(8);
        JPanel icons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        icons.setOpaque(false);
        // Knapper als früher (28), damit das Suchfeld auch bei 960 px Fensterbreite seine Mindestbreite behält.
        icons.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
        // Rechts: Begriffe und Aufträge, dann abgetrennt die Verwaltung (Angelo, 13.11.2026).
        JPanel trailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        trailing.setOpaque(false);
        JPanel administration = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        administration.setOpaque(false);
        administration.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(0, 8, 0, 0)));
        for (AppContext.Section section : AppContext.Section.values()) {
            if (!section.button()) {
                continue;
            }
            ComposerToggleButton button = navButton(section, shortcutNumber(section));
            navGroup.add(button);
            nav.put(section, button);
            (section.administration() ? administration : section.trailing() ? trailing : icons).add(button);
        }
        // Der Chat ist kein Bereich, sondern eine Leiste neben jedem Bereich: sein Umschalter steht rechts vor den
        // Aufträgen und gehört nicht zur Gruppe der Bereichsknöpfe.
        chatToggle = new ComposerToggleButton(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.icon(), null,
                CHAT_TOOLTIP);
        chatToggle.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        chatToggle.getAccessibleContext().setAccessibleName(
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar.TITLE);
        chatToggle.addActionListener(e -> chatOpen(chatToggle.isSelected()));
        trailing.add(chatToggle, 0);
        // Die Reiter der Seitenansicht schalten die Mitte um: ein Klick zeigt Inhalte, auch aus einem anderen Bereich.
        JComponent tabBar = content.tabBar();
        tabBar.setBorder(BorderFactory.createEmptyBorder(0, TAB_GAP, 0, 0));
        for (Component tab : tabBar.getComponents()) {
            if (tab instanceof javax.swing.AbstractButton button) {
                button.addActionListener(e -> show(AppContext.Section.CONTENT));
            }
        }
        icons.add(tabBar);
        // Der Titel steht in der Mitte der linken Gruppe und ist ihr einziger kürzbarer Teil (HeaderLayout).
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(title, BorderLayout.CENTER);
        west.add(icons, BorderLayout.EAST);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        east.setOpaque(false);
        east.add(trailing);
        east.add(administration);
        east.add(Dialogs.closeButton(new ComicWindowHoldCloseButton(Comic.PALETTE, minimize, close, 24)));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 16, 8, 10)));
        top.add(west, BorderLayout.WEST);
        top.add(east, BorderLayout.EAST);
        // Ein Suchfeld für das ganze Fenster, mittig zwischen den Icon-Gruppen (#515).
        top.add(SearchBarLayout.wrap(searchView.searchBar()), BorderLayout.CENTER);
        ComicWindowDragger.install(top);
        // Meldet die Leiste beim Tooltip-Dienst an; den Text liefert getToolTipText je nach Fensterzustand.
        top.setToolTipText(MAXIMIZE_TOOLTIP);

        side.setBackground(Comic.SURFACE);
        side.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        side.setMinimumSize(new Dimension(0, 0));
        side.add(explorer.component(), EXPLORER);
        side.add(sources.drawer(), AppContext.Section.SOURCES.name());
        side.add(settings.drawer(), AppContext.Section.SETTINGS.name());
        deck.setMinimumSize(new Dimension(320, 0));
        // Aktionszeile und Suche gehören zur Arbeitsfläche: sie reichen nicht unter den Explorer.
        JPanel work = new JPanel(new BorderLayout());
        work.setBackground(Comic.SURFACE);
        work.setMinimumSize(new Dimension(320, 0));
        JPanel workFoot = new JPanel(new BorderLayout());
        workFoot.setOpaque(false);
        workFoot.add(actionDeck, BorderLayout.CENTER);
        // Zeitleiste des Podcasts an der Stelle des Trenners über der Suchleiste (TimelineEvent).
        JPanel findFoot = new JPanel(new BorderLayout());
        findFoot.setOpaque(false);
        timelineSlot.setOpaque(false);
        timelineSlot.setVisible(false);
        findFoot.add(timelineSlot, BorderLayout.NORTH);
        findFoot.add(findBar, BorderLayout.CENTER);
        workFoot.add(findFoot, BorderLayout.SOUTH);
        // Über dem Inhaltsbereich kann eine Fläche liegen (ContentOverlayEvent, das Bild zum Podcast); der Inhalt
        // darunter bleibt, wie er ist.
        JPanel deckHolder = new JPanel(new java.awt.LayoutManager() {
            @Override
            public void addLayoutComponent(String name, java.awt.Component component) {
            }

            @Override
            public void removeLayoutComponent(java.awt.Component component) {
            }

            @Override
            public Dimension preferredLayoutSize(java.awt.Container parent) {
                return deck.getPreferredSize();
            }

            @Override
            public Dimension minimumLayoutSize(java.awt.Container parent) {
                return deck.getMinimumSize();
            }

            @Override
            public void layoutContainer(java.awt.Container parent) {
                for (java.awt.Component child : parent.getComponents()) {
                    child.setBounds(0, 0, parent.getWidth(), parent.getHeight());
                }
            }
        }) {
            @Override
            public boolean isOptimizedDrawingEnabled() {
                return false;
            }
        };
        deckHolder.setOpaque(false);
        overlaySlot.setOpaque(false);
        overlaySlot.setVisible(false);
        deckHolder.add(overlaySlot);
        deckHolder.add(deck);
        work.add(deckHolder, BorderLayout.CENTER);
        work.add(workFoot, BorderLayout.SOUTH);
        chatSidebar = content.chatSidebar();
        chatSidebar.collapseButton().addActionListener(e -> chatOpen(false));
        chatSidebar.chat().onAnswer(this::chatAnswered);
        // Ein Quellen-Link soll die Seite zeigen: die große Ansicht wird wieder zur Leiste.
        chatSidebar.chat().onSourceOpened(() -> {
            if (chatLarge) {
                chatLarge(false);
            }
        });
        chatSidebar.enlargeButton().addActionListener(e -> chatLarge(!chatLarge));
        chatSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, work, chatSidebar);
        chatSplit.setBorder(null);
        chatSplit.setBackground(Comic.SURFACE);
        chatSplit.setResizeWeight(1);
        chatSplit.setContinuousLayout(true);
        chatSplit.setMinimumSize(new Dimension(320, 0));
        ComicDivider.install(chatSplit, Comic.PALETTE);
        if (chatSplit.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        chatOpen(!chatOpen);
                    }
                }
            });
        }
        // Die Breite der Leiste gilt, solange sie offen ist: beim Ziehen merken, bei neuer Fenstergröße halten.
        chatSplit.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> rememberChatWidth());
        chatSplit.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                placeChat();
            }
        });
        chatWidth = Math.max(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH,
                WindowSizes.recallInt(CHAT_WIDTH_KEY, chatWidth));
        chatOpen = WindowSizes.recallInt(CHAT_OPEN_KEY, 0) == 1;
        chatLarge = WindowSizes.recallInt(CHAT_LARGE_KEY, 0) == 1;
        chatToggle.setSelected(chatOpen);
        placeChat();
        split = new ComicSplitPane(side, chatSplit, 200, 520, Comic.PALETTE);
        split.setBackground(Comic.SURFACE);
        // Ein Trenner für die ganze App: derselbe Renderer wie unter „Aufträge“ und im Graph (ComicDivider), damit
        // alle Teilungen gleich aussehen; die Breitenregeln des ComicSplitPane bleiben.
        ComicDivider.install(split, Comic.PALETTE);
        split.setContinuousLayout(true);
        split.setPreferredLeftWidth(SIDEBAR_WIDTH);
        // Einklappbar: Doppelklick auf den Teiler klappt den Drawer zu bzw. wieder auf.
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        toggleDrawer();
                    }
                }
            });
        }

        actionDeck.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(6, 16, 6, 16)));

        // Gesamtstand aller Aufträge; Anhalten und Abbrechen stehen für alle zugleich in der Kopfzeile der Aufträge.
        jobStatus = new JobStatusLine(context.events());
        jobStatus.navigation(content.navigation());
        // Verlangt eine Quelle einen Einmalcode, steht es ganz links; der Klick führt zum Codefeld unter „Aufträge“.
        signInNotice = new SignInNotice(context, request -> {
            show(AppContext.Section.ACTIVITIES);
            activities.showSignIn(request);
        });
        // Scheitert das Erneuern des Proxy-Skripts, steht es daneben; der Klick zeigt den Grund (Angelo 16.11.2026).
        proxyNotice = new ProxyNotice(context);
        JPanel statusBox = new JPanel(new BorderLayout(4, 0));
        statusBox.setOpaque(false);
        statusBox.add(status, BorderLayout.CENTER);
        statusBox.add(statusClose, BorderLayout.EAST);
        statusClose.setVisible(false);
        statusLine = new StatusLine(jobStatus.model(), StatusLine.alerts(signInNotice, proxyNotice), viewStatus,
                statusBox, jobStatus, new CopyrightLink());
        JComponent foot = statusLine;
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(foot, BorderLayout.SOUTH);

        surface.add(top, BorderLayout.NORTH);
        surface.add(split, BorderLayout.CENTER);
        surface.add(bottom, BorderLayout.SOUTH);
        ComicWindowResizer.install(surface, RESIZE_GRIP);
        installShortcuts(surface);
        return surface;
    }

    /** Die Ziffer des Tastenkürzels Strg+Ziffer: fortlaufend über alle Bereiche mit Kürzel, auch ohne Knopf. */
    static int shortcutNumber(AppContext.Section section) {
        int number = 0;
        for (AppContext.Section candidate : AppContext.Section.values()) {
            if (candidate.toolbar()) {
                number++;
            }
            if (candidate == section) {
                return number;
            }
        }
        return number;
    }

    /** Nur ein Symbol; Name als Tooltip und für Bildschirmleser, Strg+Ziffer wählt den Bereich. */
    private ComposerToggleButton navButton(AppContext.Section section, int number) {
        ComposerToggleButton button = new ComposerToggleButton(icon(section), null,
                section.title() + " (Strg+" + number + ")");
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.getAccessibleContext().setAccessibleName(section.title());
        button.addActionListener(e -> show(section));
        return button;
    }

    private void installShortcuts(JComponent surface) {
        int number = 1;
        for (AppContext.Section section : AppContext.Section.values()) {
            if (!section.toolbar()) {
                continue;
            }
            String key = "section-" + section.name();
            surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_0 + number++, java.awt.event.InputEvent.CTRL_DOWN_MASK), key);
            surface.getActionMap().put(key, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    show(section);
                }
            });
        }
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_J, java.awt.event.InputEvent.CTRL_DOWN_MASK), "chat");
        surface.getActionMap().put("chat", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                chatOpen(!chatOpen);
            }
        });
        // Esc stellt aus der großen Chat-Ansicht die Leiste wieder her, gleich wo der Fokus steht (auch im Explorer);
        // Felder und Overlays mit eigenem Esc gehen vor, weil diese Bindung als letzte gefragt wird.
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ESCAPE, 0), "chat-shrink");
        surface.getActionMap().put("chat-shrink", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (chatOpen && chatLarge) {
                    chatLarge(false);
                }
            }
        });
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "find");
        surface.getActionMap().put("find", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                findBar.focus();
            }
        });
    }

    @Override
    public void showGeneralSettings() {
        show(AppContext.Section.SETTINGS);
        settings.select(SettingsView.GENERAL);
    }

    /** Einstellungen mit dem Bereich „KI-Dienst“. */
    public void showAiSettings() {
        show(AppContext.Section.SETTINGS);
        settings.showAi();
    }

    /**
     * Klappt die Chat-Seitenleiste auf oder zu (Knopf oben rechts, Strg+J, „»“ in der Leiste, Doppelklick auf den
     * Teiler). Zugeklappt bleibt nur der schmale Teiler; der Zustand und die Breite gelten auch nach dem Neustart.
     */
    void chatOpen(boolean open) {
        chatOpen = open;
        chatToggle.setSelected(open);
        if (open) {
            chatUnread(false);
        }
        WindowSizes.rememberInt(CHAT_OPEN_KEY, open ? 1 : 0);
        placeChat();
        if (open) {
            chatSidebar.chat().focusEditor();
        }
    }

    /** Eine Antwort ist fertig: Ist die Leiste zu, zeigt der Chat-Knopf einen Punkt, bis sie aufgeklappt wird. */
    void chatAnswered() {
        if (!chatOpen) {
            chatUnread(true);
        }
    }

    boolean chatUnread() {
        return chatUnread;
    }

    private void chatUnread(boolean unread) {
        chatUnread = unread;
        chatToggle.setIcon(unread ? com.aresstack.spaceharvester.app.ui.content.ChatSidebar.unreadIcon()
                : com.aresstack.spaceharvester.app.ui.content.ChatSidebar.icon());
        chatToggle.setToolTipText(unread ? CHAT_UNREAD_TOOLTIP : CHAT_TOOLTIP);
    }

    boolean chatOpen() {
        return chatOpen;
    }

    /**
     * „Groß anzeigen“: dieselbe Leiste nimmt die ganze Arbeitsfläche, der Explorer bleibt; derselbe Knopf und Esc
     * stellen die vorige Breite wieder her. Gemerkt wie offen/zu.
     */
    void chatLarge(boolean large) {
        chatLarge = large;
        WindowSizes.rememberInt(CHAT_LARGE_KEY, large ? 1 : 0);
        placeChat();
        if (chatOpen) {
            chatSidebar.chat().focusEditor();
        }
    }

    boolean chatLarge() {
        return chatLarge;
    }

    /** Stellt den Teiler so, dass die Leiste ihre Breite hat bzw. zugeklappt ganz verschwindet. */
    private void placeChat() {
        placingChat = true;
        try {
            int min = com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH;
            chatSidebar.setMinimumSize(new Dimension(chatOpen ? min : 0, 0));
            chatSidebar.shown(chatOpen);
            boolean large = chatOpen && chatLarge;
            chatSidebar.large(large);
            // Groß: die Arbeitsfläche weicht ganz, die Teilung gibt der Leiste dann allen Platz und zeigt keinen Teiler.
            chatSplit.getLeftComponent().setVisible(!large);
            // Zugeklappt lässt sich der Teiler nicht ziehen, nur doppelt klicken. Die Teilung selbst bleibt
            // eingeschaltet: Abgeschaltet verlören alle Trenner der Arbeitsfläche ihren Doppelpfeil-Zeiger.
            ComicDivider.draggable(chatSplit, chatOpen && !large);
            chatSplit.setDividerSize(large ? 0 : chatOpen ? ComicDivider.THICKNESS : COLLAPSED_DIVIDER);
            int width = chatSplit.getWidth();
            if (width > 0 && !large) {
                // Die Arbeitsfläche behält ihre Mindestbreite; in einem schmalen Fenster gibt die Leiste nach.
                int room = Math.max(0, width - chatSplit.getDividerSize() - 320);
                int shown = chatOpen ? Math.max(Math.min(min, room), Math.min(chatWidth, room)) : 0;
                chatSplit.setDividerLocation(width - chatSplit.getDividerSize() - shown);
            }
            chatSplit.revalidate();
        } finally {
            placingChat = false;
        }
    }

    private boolean placingChat;

    /** Merkt die Breite, die der Benutzer der offenen Leiste durch Ziehen gegeben hat. */
    private void rememberChatWidth() {
        if (placingChat || !chatOpen || chatLarge || chatSplit.getWidth() <= 0) {
            return;
        }
        int width = chatSplit.getWidth() - chatSplit.getDividerSize() - chatSplit.getDividerLocation();
        if (width >= com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH && width != chatWidth) {
            chatWidth = width;
            WindowSizes.rememberInt(CHAT_WIDTH_KEY, width);
        }
    }

    /** Klappt den linken Drawer zu oder wieder auf. */
    /** Breite des Teilers bei zugeklapptem Drawer: sichtbar und per Doppelklick erreichbar. */
    static final int COLLAPSED_DIVIDER = 6;

    void toggleDrawer() {
        if (split.isLeftCollapsed()) {
            split.openLeft();
        } else {
            split.collapseLeft();
            // collapseLeft() nimmt den Teiler ganz weg; ein schmaler bleibt, damit ein Doppelklick wieder aufklappt.
            split.setDividerSize(COLLAPSED_DIVIDER);
        }
    }

    @Override
    public void show(AppContext.Section section) {
        // Wer einen Bereich aufruft, will ihn sehen: aus der großen Chat-Ansicht wird wieder die Leiste. Nur die
        // Auswahl im Explorer lässt sie groß, denn dort wählt man den Bereich der nächsten Frage.
        if (chatSplit != null && chatOpen && chatLarge) {
            chatLarge = false;
            WindowSizes.rememberInt(CHAT_LARGE_KEY, 0);
            placeChat();
        }
        showSection(section);
    }

    /** Zeigt den Bereich, ohne die große Chat-Ansicht zu verlassen (Auswahl im Explorer). */
    private void showSection(AppContext.Section section) {
        if (section == AppContext.Section.STATUS) {
            show(AppContext.Section.SETTINGS);
            settings.select(SettingsView.DIAGNOSIS);
            return;
        }
        cards.show(deck, section.name());
        boolean changed = section != current;
        if (changed && current != AppContext.Section.SETTINGS) {
            beforeSettings = current;
        }
        if (changed && current == AppContext.Section.SEARCH) {
            searchSelection = explorer.remember();
        }
        current = section;
        placeOverlay();
        if (changed && section == AppContext.Section.SEARCH) {
            if (searchSelection == null) {
                // Zum ersten Mal in der Suche: gesucht wird in dem, was links gewählt ist.
                searchView.scope(explorer.scopes());
            } else {
                explorer.restore(searchSelection);
            }
        }
        // Keine zweite Leiste: Die Quellenkarten bzw. die Bereiche der Einstellungen ersetzen im Drawer den Explorer,
        // solange der Bereich offen ist.
        boolean ownDrawer = section == AppContext.Section.SOURCES || section == AppContext.Section.SETTINGS;
        sideCards.show(side, ownDrawer ? section.name() : EXPLORER);
        if (ownDrawer && split != null && split.isLeftCollapsed()) {
            split.openLeft();
        }
        View view = views.get(section);
        boolean ownActions = view.actions() != null && view != activities;
        actionCards.show(actionDeck, ownActions ? section.name() : JOBS);
        actionDeck.setVisible(ownActions);
        boolean ownStatus = view.statusInfo() != null;
        viewStatusCards.show(viewStatus, ownStatus ? section.name() : JOBS);
        viewStatus.setVisible(ownStatus);
        ComposerToggleButton button = nav.get(section);
        if (button != null) {
            button.setSelected(true);
        } else {
            navGroup.clearSelection();
        }
        // Die Reiter in der Leiste zeigen ihre Auswahl nur, solange die Mitte die Seitenansicht zeigt.
        content.tabsActive(section == AppContext.Section.CONTENT);
        jobStatus.navigationVisible(section == AppContext.Section.CONTENT);
        findBar.tools(view.tools());
        view.shown();
        if (changed) {
            findBar.areaChanged();
        }
    }

    @Override
    public void openPage(String sourceId, String pageId) {
        showSection(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPage(sourceId, pageId);
    }

    @Override
    public void openPassage(String sourceId, String passageId) {
        showSection(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPassage(sourceId, passageId);
    }

    @Override
    public void revealPage(String sourceId, String pageId) {
        explorer.revealPage(sourceId, pageId);
    }

    @Override
    public void openSpace(String sourceId, String spaceKey) {
        showSection(AppContext.Section.CONTENT);
        content.openSpace(sourceId, spaceKey);
        explorer.reveal(sourceId, spaceKey);
    }

    @Override
    public void openFromTree(String sourceId, String pageId) {
        // In der Suche filtert die Auswahl links nur; die Suchansicht bleibt (Angelo, 21.11.2026).
        if (current == AppContext.Section.SEARCH) {
            return;
        }
        openPage(sourceId, pageId);
    }

    @Override
    public void openSource(String sourceId, String title) {
        showSection(AppContext.Section.CONTENT);
        content.openSource(sourceId, title);
    }

    @Override
    public void editSource(String sourceId) {
        show(AppContext.Section.SOURCES);
        sources.select(sourceId);
    }

    @Override
    public void openExport(String sourceId, String spaceKey, String rootPageId) {
        show(AppContext.Section.CONTENT);
        if (rootPageId == null) {
            content.openSpace(sourceId, spaceKey);
            explorer.reveal(sourceId, spaceKey);
        } else {
            revealOpened = true;
            content.openPage(sourceId, rootPageId);
        }
        content.showExport();
    }

    @Override
    public void showJob(String jobId) {
        show(AppContext.Section.ACTIVITIES);
        activities.show(jobId);
    }

    @Override
    public void followJobs() {
        activities.follow();
    }

    @Override
    public void showDiagnosis(String jobId) {
        show(AppContext.Section.STATUS);
        statusView.showJob(jobId);
    }

    /** Startet die Downloads sofort und zeigt den ersten Auftrag; was nicht startet, nennt die Statusleiste. */
    @Override
    public void download(String sourceId, List<String> spaceKeys) {
        notice("Download wird gestartet …", Comic.MUTED);
        Downloads.start(context, sourceId, spaceKeys, started -> {
            if (started.failed().isEmpty()) {
                notice(started.jobs().isEmpty() ? "Kein Space zum Herunterladen"
                        : Texts.count(started.jobs().size(), "Download", "Downloads") + " gestartet", Comic.MUTED);
            } else {
                notice(Texts.count(started.failed().size(), "Download ließ", "Downloads ließen")
                        + " sich nicht starten: " + String.join("; ", started.failed()), Comic.ORANGE);
            }
            if (!started.jobs().isEmpty()) {
                showJob(started.jobs().get(0).id());
            }
        }, error -> notice("Download ließ sich nicht starten: " + Problems.describe(error).title(), Comic.RED));
    }

    @Override
    public void downloadPage(String sourceId, String spaceKey, String pageId, boolean withChildren) {
        notice("Download wird gestartet …", Comic.MUTED);
        Downloads.startPage(context, sourceId, spaceKey, pageId, withChildren, started -> {
            notice(withChildren ? "Download der Seite samt Unterseiten gestartet" : "Download der Seite gestartet",
                    Comic.MUTED);
            showJob(started.jobs().get(0).id());
        }, error -> notice("Download ließ sich nicht starten: " + Problems.describe(error).title(), Comic.RED));
    }

    /** Indexieren aus dem Kontextmenü des Baums; das Ergebnis steht in der Statuszeile, der Auftrag unter Aufträge. */
    @Override
    public void index(String sourceId, String spaceKey, String pageId) {
        notice("Indexieren wird gestartet …", Comic.MUTED);
        Indexer.start(context, sourceId, spaceKey, pageId, started -> {
            if (started.failed().isEmpty()) {
                notice(started.jobs().isEmpty() ? "Kein Space zum Indexieren"
                        : Texts.count(started.jobs().size(), "Space wird", "Spaces werden") + " indexiert",
                        Comic.MUTED);
            } else {
                notice(String.join("; ", started.failed()), Comic.ORANGE);
            }
            if (!started.jobs().isEmpty()) {
                showJob(started.jobs().get(0).id());
            }
        }, error -> notice("Indexieren ließ sich nicht starten: " + Problems.describe(error).title(), Comic.RED));
    }

    @Override
    public void unindex(String sourceId, String spaceKey, String pageId) {
        Indexer.stop(context, sourceId, spaceKey, pageId,
                () -> notice("Nicht mehr indexiert; Geladenes bleibt und wird weiter aktuell gehalten", Comic.MUTED),
                error -> notice("Markierung ließ sich nicht aufheben: " + Problems.describe(error).title(),
                        Comic.RED));
    }

    /** Kreispfeile hinter gescheiterten Abgleichen eines Updates: wie die Kreispfeile im Baum für diese Spaces. */
    @Override
    public void retrySyncs(String sourceId, List<String> spaceKeys) {
        catchUp(sourceId, spaceKeys, jobs -> { });
    }

    @Override
    public void retrySyncs(String sourceId, List<String> spaceKeys,
                           java.util.function.Consumer<List<String>> started) {
        catchUp(sourceId, spaceKeys, started);
    }

    /** Kreispfeile im Baum: Quelle oder Space holen nach wie die Quellenkarte, eine Seite liest ihren Teilbaum neu. */
    @Override
    public void refresh(String sourceId, String spaceKey, String pageId) {
        if (pageId == null) {
            catchUp(sourceId, spaceKey == null ? List.of() : List.of(spaceKey), jobs -> { });
            return;
        }
        refreshPage(sourceId, spaceKey, pageId);
    }

    /** Holt die Spaces nach (ohne {@code spaceKeys} jeden der Quelle); der Ausgang kommt in die Statuszeile. */
    private void catchUp(String sourceId, List<String> spaceKeys,
                         java.util.function.Consumer<List<String>> startedJobs) {
        // Die Meldezeile der Quellen-Ansicht sieht man vom Baum aus nicht: der Ausgang kommt in die Statuszeile.
        if (sources.catchUp(sourceId, spaceKeys,
                new SourcesView.CatchUpReport() {
                    @Override
                    public void started(List<String> jobs) {
                        notice(Texts.count(jobs.size(), "Space wird", "Spaces werden") + " nachgeholt",
                                Comic.MUTED);
                        if (!jobs.isEmpty()) {
                            showJob(jobs.get(0));
                        }
                    }

                    @Override
                    public void accepted(List<String> jobs) {
                        // Auch bei teilweisem Scheitern: was angelaufen ist, verfolgt die offene Ansicht.
                        startedJobs.accept(jobs);
                    }

                    @Override
                    public void failed(String message) {
                        notice(message, Comic.RED);
                    }
                })) {
            notice("Nachholen wird gestartet …", Comic.MUTED);
        }
    }

    /** Liest eine Seite samt Unterseiten neu aus der Quelle. */
    private void refreshPage(String sourceId, String spaceKey, String pageId) {
        notice("Aktualisierung wird gestartet …", Comic.MUTED);
        Downloads.refreshSubtree(context, sourceId, spaceKey, pageId, started -> {
            notice("Aktualisierung der Seite samt Unterseiten gestartet", Comic.MUTED);
            showJob(started.jobs().get(0).id());
        }, error -> notice("Aktualisierung ließ sich nicht starten: " + Problems.describe(error).title(),
                Comic.RED));
    }

    /** Eine kurze Meldung in der Statusleiste, bis sich der Stand der Laufzeit ändert. */
    private void notice(String text, java.awt.Color color) {
        context.events().publish(new StatusMessageEvent(text, NOTICE_MS,
                Comic.RED.equals(color) ? Severity.ERROR : Severity.INFO));
    }

    @Override
    public void search(String text) {
        show(AppContext.Section.SEARCH);
        searchView.search(text == null ? "" : text.trim());
    }

    /** Für Tests und Bildschirmfotos: die Wissensquellen-Ansicht. */
    public SourcesView sources() {
        return sources;
    }

    /**
     * Links in der Statuszeile steht keine feste Angabe mehr (Version, Quellen und Datenordner stehen unter
     * Einstellungen), nur was auf dem Ereignisbus erscheint: beim Start „Startet …“ bzw. warum nichts startete.
     */
    private void updateStatus() {
        if (context.runtime() != null) {
            if (starting) {
                starting = false;
                clearStatus();
            }
        } else if (context.startProblem() != null) {
            // Nach einem späteren Fehlschlag löscht ein erfolgreicher Neustart die Meldung wieder.
            starting = true;
            context.events().publish(new StatusMessageEvent("Nicht gestartet: "
                    + Problems.describe(context.startProblem()).title(), Integer.MAX_VALUE, Severity.ERROR));
        } else {
            context.events().publish(new StatusMessageEvent("Startet …", Integer.MAX_VALUE, Severity.INFO));
        }
    }

    /** Legt die Fläche einer Ansicht über den Inhaltsbereich oder nimmt sie wieder weg (ContentOverlayEvent). */
    private void overlay(com.aresstack.spaceharvester.app.event.ContentOverlayEvent event) {
        Runnable apply = () -> {
            javax.swing.JComponent overlay = event.getPayload().overlay();
            boolean current = overlaySlot.getComponentCount() == 1 && overlaySlot.getComponent(0) == overlay;
            if (event.getPayload().show()) {
                if (!current) {
                    overlaySlot.removeAll();
                    overlaySlot.add(overlay, BorderLayout.CENTER);
                }
            } else if (current) {
                overlaySlot.removeAll();
            }
            placeOverlay();
            overlaySlot.revalidate();
            overlaySlot.repaint();
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(apply);
        }
    }

    /** Die Fläche gehört zum Bereich „Inhalte“: In anderen Bereichen liegt sie nicht über deren Ansicht. */
    private void placeOverlay() {
        overlaySlot.setVisible(overlaySlot.getComponentCount() == 1 && current == AppContext.Section.CONTENT);
    }

    /** Die Fläche über dem Inhaltsbereich, falls eine liegt (für Tests). */
    JComponent contentOverlay() {
        return overlaySlot.isVisible() && overlaySlot.getComponentCount() == 1
                ? (JComponent) overlaySlot.getComponent(0) : null;
    }

    /** Zeigt die Zeitleiste einer Ansicht über der Suchleiste, ohne den Trenner, oder nimmt sie wieder weg. */
    private void timeline(com.aresstack.spaceharvester.app.event.TimelineEvent event) {
        Runnable apply = () -> {
            javax.swing.JComponent timeline = event.getPayload().timeline();
            boolean current = timelineSlot.getComponentCount() == 1 && timelineSlot.getComponent(0) == timeline;
            if (event.getPayload().show()) {
                timelineSlot.removeAll();
                timeline.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE));
                timelineSlot.add(timeline, BorderLayout.CENTER);
                timelineSlot.setVisible(true);
                findBar.line(false);
            } else if (current) {
                timelineSlot.removeAll();
                timelineSlot.setVisible(false);
                findBar.line(true);
            }
            timelineSlot.revalidate();
            timelineSlot.repaint();
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(apply);
        }
    }

    /** Die Zeitleiste über der Suchleiste, falls eine steht (für Tests). */
    javax.swing.JComponent timelineShown() {
        return timelineSlot.isVisible() && timelineSlot.getComponentCount() == 1
                ? (javax.swing.JComponent) timelineSlot.getComponent(0) : null;
    }

    /** Zeigt eine Statusmeldung vom Bus und blendet sie nach ihrer Dauer aus (wie die Statusleiste in MainframeMate). */
    private void showStatus(StatusMessageEvent event) {
        Runnable apply = () -> {
            StatusMessageEvent.Payload message = event.getPayload();
            String text = message.getText() == null ? "" : message.getText();
            if (message.getReplaces() != null && !message.getReplaces().equals(status.getText())) {
                // Eine Ansicht beendet nur ihre eigene Meldung; die einer anderen Ansicht bleibt stehen.
                return;
            }
            status.setText(text.isEmpty() ? " " : text);
            status.setToolTipText(text.isEmpty() ? null : text);
            status.setForeground(message.getSeverity() == Severity.ERROR ? Comic.RED
                    : message.getSeverity() == Severity.WARN ? Comic.ORANGE : Comic.MUTED);
            statusClose.setVisible(!text.isEmpty() && message.isClosable());
            statusClear.stop();
            if (message.getDurationMs() < Integer.MAX_VALUE) {
                statusClear.setInitialDelay(message.getDurationMs());
                statusClear.restart();
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(apply);
        }
    }

    /** Leert den Stand der App in der Statuszeile (Ablauf der Dauer, ✕ oder Start der Laufzeit). */
    private void clearStatus() {
        status.setText(" ");
        status.setToolTipText(null);
        statusClose.setVisible(false);
    }

    /** Das ✕ hinter dem Stand der App, wie an der Meldungsplatte ({@link Feedback}): durchsichtiger Icon-Knopf. */
    private ComposerButton closeButton() {
        ComposerButton button = ComposerButton.iconButton(ComposerIcons.close(), "Meldung schließen");
        button.setContentAreaFilled(false);
        button.setBorderPainted(false);
        button.setFocusPainted(false);
        button.setOpaque(false);
        button.setPreferredSize(new Dimension(16, 16));
        button.addActionListener(e -> {
            statusClear.stop();
            clearStatus();
        });
        return button;
    }

    /** Für Tests: der Stand der App in der Statuszeile und sein ✕. */
    JLabel statusText() {
        return status;
    }

    ComposerButton statusClose() {
        return statusClose;
    }

    private boolean confirmClose(JFrame frame) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return true;
        }
        long running;
        try {
            running = runtime.workspace().jobs().stream().filter(j -> !j.state().terminal()).count();
        } catch (RuntimeException e) {
            running = 0;
        }
        return running == 0 || Dialogs.confirm(frame, "Space Harvester beenden?", "Space Harvester wirklich beenden? "
                + Texts.count(running, "Auftrag läuft", "Aufträge laufen")
                + " noch. Beim Beenden werden sie abgebrochen; ihr Ergebnis bleibt unbestätigt.");
    }

    private static Icon icon(AppContext.Section section) {
        return switch (section) {
            case STATUS -> Glyph.PULSE;
            case SOURCES -> database();
            // Inhalte hat keinen eigenen Knopf mehr; das Buch bleibt für Verweise (etwa „Inhalte öffnen“).
            case CONTENT -> ComposerIcons.openBook();
            case ACTIVITIES -> stack();
            case SEARCH -> ComposerIcons.magnifier();
            case SETTINGS -> ComposerIcons.gear();
        };
    }

    /** Der Stapel (drei Blätter übereinander, Stapelverarbeitung) für „Aufträge“; neutral, ohne Farbe. */
    static StrokeIcon stack() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawRoundRect(4, 1, 9, 3, 2, 2);
                g2.drawRoundRect(3, 6, 9, 3, 2, 2);
                g2.drawRoundRect(2, 11, 9, 3, 2, 2);
            }
        };
    }

    /** Die Datenbank (Zylinder mit Mittelring) für „Wissensquellen verwalten“. */
    static StrokeIcon database() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(2, 1, 10, 4);
                g2.drawLine(2, 3, 2, 11);
                g2.drawLine(12, 3, 12, 11);
                g2.drawArc(2, 5, 10, 4, 180, 180);
                g2.drawArc(2, 9, 10, 4, 180, 180);
            }
        };
    }

    /** Für Tests: die Suche im sichtbaren Bereich. */
    FindBar findBar() {
        return findBar;
    }

    /** Für Tests: die Statuszeile. */
    /** Für Tests: der Bereich Suche. */
    /** Der gezeigte Bereich; für Tests. */
    AppContext.Section current() {
        return current;
    }

    SearchView searchView() {
        return searchView;
    }

    /** Der Stand des sichtbaren Bereichs links in der Statuszeile (für Tests). */
    JComponent viewStatus() {
        return viewStatus;
    }

    StatusLine statusLine() {
        return statusLine;
    }

    /** Für Tests: der Einmalcode-Hinweis der Statuszeile. */
    SignInNotice signInNotice() {
        return signInNotice;
    }

    /** Für Tests: die Teilung zwischen Arbeitsfläche und Chat-Seitenleiste. */
    javax.swing.JSplitPane chatSplit() {
        return chatSplit;
    }

    /** Für Tests: der Umschalter der Chat-Seitenleiste oben rechts. */
    ComposerToggleButton chatToggle() {
        return chatToggle;
    }

    /** Für Tests: die Teilung zwischen Drawer und Arbeitsfläche. */
    com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane split() {
        return split;
    }

    /** Für Tests: der linke Drawer (Explorer, Quellenkarten oder Einstellungs-Navigation). */
    JPanel side() {
        return side;
    }

    /** Für Tests: die Einstellungen. */
    SettingsView settings() {
        return settings;
    }

    /** Für Tests: der Explorer links. */
    Explorer explorer() {
        return explorer;
    }

    /** Für Bildschirmfotos: der aktuell sichtbare Bereich. */
    JComponent deck() {
        return deck;
    }
}
