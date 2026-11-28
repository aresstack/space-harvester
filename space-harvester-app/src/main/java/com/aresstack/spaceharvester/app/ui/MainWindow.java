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
import com.aresstack.spaceharvester.app.ui.controls.PerspectiveSwitcher;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;

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
    /** Die nächste geöffnete Seite kam aus der Suche: im Baum nur hervorheben, nicht wählen. */
    private boolean hintOpened;
    /** Aktions- und Fortschrittszeilen der Bereiche, direkt über der Statusleiste. */
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionDeck = new JPanel(actionCards);
    static final int SIDEBAR_WIDTH = 280;
    static final String LEFT_WIDTH_KEY = "sidebar.left.width";
    static final String LEFT_OPEN_KEY = "sidebar.left.open";
    private com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane split;
    /** Arbeitsfläche links, Chat-Seitenleiste rechts (Angelo, 21.11.2026). */
    private javax.swing.JSplitPane chatSplit;
    /** Die Knopfleisten als Griffe der Trenner beider Seitenleisten. */
    private com.aresstack.spaceharvester.app.ui.controls.DividerRail leftRail;
    private com.aresstack.spaceharvester.app.ui.controls.DividerRail rightRail;
    private com.aresstack.spaceharvester.app.ui.content.ChatSidebar chatSidebar;
    /**
     * Der Umschalter der rechten Seitenleiste (Angelo 27.11.2026): Chat | Analysen | Aufträge, nur Symbole, wie der
     * linke Umschalter. Er steht im Kopf der gezeigten Leiste; ein zweiter Klick auf das gewählte Symbol klappt sie zu.
     * Zugeklappt hat die Leiste keinen Kopf, dann steht er ohne gewähltes Symbol oben rechts in der Titelleiste.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle sideSwitch =
            new com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle();
    /** Platz des Umschalters in der Titelleiste, solange die Seitenleiste zugeklappt ist. */
    /** Die Fensterfläche; an ihren Rändern stehen die Umschalter der zugeklappten Seitenleisten. */
    private final com.aresstack.spaceharvester.app.ui.controls.EdgeOverlay edges =
            new com.aresstack.spaceharvester.app.ui.controls.EdgeOverlay();
    private static final String SWITCH_CHAT = com.aresstack.spaceharvester.app.ui.content.ChatSidebar.TITLE;
    private static final String SWITCH_ANALYSES = com.aresstack.spaceharvester.app.ui.content.AnalysesSidebar.TITLE;
    private static final String SWITCH_JOBS = AppContext.Section.ACTIVITIES.title();
    private boolean chatOpen;
    /** Eine Antwort kam, während die Leiste zu war: der Chat-Knopf trägt bis zum Aufklappen einen Punkt. */
    private boolean chatUnread;
    private int chatWidth = com.aresstack.spaceharvester.app.ui.content.ChatSidebar.DEFAULT_WIDTH;
    static final String CHAT_OPEN_KEY = "chat.open";
    static final String CHAT_WIDTH_KEY = "chat.width";
    static final String CHAT_LARGE_KEY = "chat.large";
    /** Ob die rechte Seitenleiste zuletzt die Aufträge zeigte (1) oder den Chat (0). */
    static final String SIDE_JOBS_KEY = "sidebar.right.jobs";
    private static final String SIDE_CHAT = "chat";
    private static final String SIDE_JOBS = "jobs";
    private static final String SIDE_ANALYSES = "analyses";
    /** Die Leisten in der Reihenfolge ihrer gemerkten Nummer ({@link #SIDE_JOBS_KEY}): 0 Chat, 1 Aufträge, 2 Analysen. */
    private static final java.util.List<String> SIDE_MODES = java.util.List.of(SIDE_CHAT, SIDE_JOBS, SIDE_ANALYSES);
    static final String ANALYSES_TOOLTIP = "Analysen zur Auswahl links ein- oder ausklappen";
    private com.aresstack.spaceharvester.app.ui.content.AnalysesSidebar analysesSidebar;

    /** Die rechte Seitenleiste zeigt entweder den Chat oder die Aufträge (Angelo 22.11.2026). */
    private final java.awt.CardLayout sideModes = new java.awt.CardLayout();
    private final JPanel sideDeck = new JPanel(sideModes);
    /**
     * Die Bereiche der rechten Leiste untereinander (Angelo 28.11.2026): oben, was die Knopfleiste gewählt hat
     * ({@link #sideDeck}), darunter der Bereich jeder offenen Kugel; sie teilen sich die Höhe.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.AreaStack sideStack =
            new com.aresstack.spaceharvester.app.ui.controls.AreaStack();
    /** Knopfleiste und Kugeln der rechten Leiste: Jedes Symbol lässt sich herausziehen und zurückschieben. */
    private final com.aresstack.spaceharvester.app.ui.controls.PearlString sidePearls =
            new com.aresstack.spaceharvester.app.ui.controls.PearlString(sideSwitch);
    /** Gemerkte Kugeln der rechten Leiste aus der ersten Fassung; gilt nur noch, solange nichts Neues gemerkt ist. */
    static final String SIDE_PEARLS_KEY = "sidebar.right.pearls";
    /**
     * Gemerkte Anordnung der Kugeln je Perspektive (Schlüssel plus Punkt und Kennung der Perspektive): links und
     * rechts je {@code PearlString.placement}, etwa {@code "L=R,*0:1;R=R,2:0"} (der Stern: ein Werkzeug vom anderen Rand).
     */
    static final String PEARLS_KEY = "sidebar.pearls";
    /**
     * Gemerkte Höhen von Knopfleisten und Kugeln je Perspektive (Schlüssel plus Punkt und Kennung der Perspektive),
     * links und rechts je {@code PearlString.seats}: Zugeklappt sitzen sie schon beim Start dort, wo aufgeklappt.
     */
    static final String SEATS_KEY = "sidebar.pearls.seats";
    /**
     * Die Bereiche der linken Leiste untereinander: ihr eigentlicher Inhalt ({@link #side}) und der Bereich jeder
     * Kugel, die an den linken Rand verschoben wurde.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.AreaStack leftStack =
            new com.aresstack.spaceharvester.app.ui.controls.AreaStack();
    /** Die Kette am linken Rand: die Knopfleiste des Explorers und Kugeln, die von rechts herüberkamen. */
    private com.aresstack.spaceharvester.app.ui.controls.PearlString leftPearls;
    /** Für welche Perspektive die Kugeln gerade angeordnet sind; jede merkt ihre eigene Anordnung. */
    private String arrangedFor;
    private boolean arranging;
    /**
     * Gemerkte Anteile der Bereiche der rechten Leiste an ihrer Höhe, in Tausendsteln des Mittels: zuerst der Bereich
     * der Knopfleiste, dann Chat, Aufträge und Analysen ({@link #SIDE_MODES}).
     */
    static final String SIDE_SHARES_KEY = "sidebar.right.shares";
    /** Wie {@link #SIDE_SHARES_KEY} für die linke Leiste: der Explorer und die Bereiche der Kugeln am linken Rand. */
    static final String LEFT_SHARES_KEY = "sidebar.left.shares";
    private static final int SHARE_SCALE = 1000;
    private String sideMode = SIDE_CHAT;
    /** Die Leiste über die ganze Arbeitsfläche („Groß anzeigen“); der Explorer bleibt. */
    private boolean chatLarge;
    static final String CHAT_TOOLTIP = "Chat (Strg+J)";
    static final String JOBS_TOOLTIP = "Aufträge (Strg+" + shortcutNumber(AppContext.Section.ACTIVITIES) + ")";
    static final String CHAT_UNREAD_TOOLTIP = "Chat (Strg+J) · neue Antwort";
    /** Leere Karte der Aktionszeile für Bereiche ohne eigene Aktionen. */
    private static final String JOBS = "jobs";
    /** Karte des Stands einer Perspektive in der Statuszeile (Präfix vor der Kennung). */
    private static final String PERSPECTIVE_STATUS = "perspective:";
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
     * Suche) und schöbe den Auftragstext nach rechts, neben eine leere Fläche.
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
    private NetworkNotice networkNotice;
    /** Suche im sichtbaren Bereich, über der Statuszeile und nur unter der Arbeitsfläche. */
    private final FindBar findBar;
    /** Platz der Zeitleiste über der Suchleiste ({@code TimelineEvent}). */
    private final JPanel timelineSlot = new JPanel(new BorderLayout());
    /** Platz einer Fläche über dem Inhaltsbereich ({@code ContentOverlayEvent}); sichtbar nur unter „Inhalte“. */
    private final JPanel overlaySlot = new JPanel(new BorderLayout());
    private AppContext.Section current = AppContext.Section.CONTENT;
    /** Der zuletzt gezeigte Arbeitsbereich vor den Einstellungen: dorthin führt „Zurück“ im Drawer. */
    private AppContext.Section beforeSettings = AppContext.Section.CONTENT;
    /**
     * Die Perspektive, aus der die Einstellungen geöffnet wurden, solange sie über ihr stehen; {@code null} sonst. Die
     * Einstellungen gehören allen Perspektiven: Wer sie aus dem Command Deck öffnet, bleibt im Command Deck (Angelo,
     * 23.11.2026), und „Zurück“ führt dorthin zurück.
     */
    private Perspective settingsOver;
    /** Tooltips der freien Flächen der Fensterleiste: Der Doppelklick ersetzt den fehlenden Vergrößern-Knopf. */
    static final String MAXIMIZE_TOOLTIP = "Doppelklick: Vollbild";
    static final String RESTORE_TOOLTIP = "Doppelklick: Verkleinern";
    private final JPanel top = new JPanel(new HeaderLayout(12)) {
        @Override
        protected void paintComponent(java.awt.Graphics graphics) {
            super.paintComponent(graphics);
            // In der Weihnachtszeit liegt dezenter Schnee in der Titelleiste (Angelo 28.11.2026).
            com.aresstack.spaceharvester.app.ui.controls.Snow.paint(graphics, getWidth(), getHeight(),
                    this::freeOfControls);
        }

        /** Ob in diesem Rechteck nur leere Fläche der Leiste liegt, kein Knopf, Text oder Feld. */
        private boolean freeOfControls(java.awt.Rectangle box) {
            int[][] points = {{box.x, box.y}, {box.x + box.width, box.y}, {box.x, box.y + box.height},
                    {box.x + box.width, box.y + box.height}, {box.x + box.width / 2, box.y + box.height / 2}};
            for (int[] point : points) {
                java.awt.Component at = javax.swing.SwingUtilities.getDeepestComponentAt(this, point[0], point[1]);
                if (at != null && !(at instanceof JPanel)) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public String getToolTipText(java.awt.event.MouseEvent event) {
            // Nur die freien Flächen fragen hier: Knöpfe und Felder zeigen ihre eigenen Tooltips.
            return windowBarTooltip(
                    ComicWindowDragger.isMaximized(javax.swing.SwingUtilities.getWindowAncestor(this)));
        }
    };

    /**
     * Wo die Reiter der Mitte in der Kopfzeile enden sollen (Angelo 27.11.2026): mittig über dem mittleren Fenster;
     * mit offener Seitenleiste rechts an dessen linkem Rand, also am linken Teiler, und sie wandern mit ihm. Negativ,
     * solange es nichts auszurichten gibt (andere Perspektive, Seitenleiste groß, noch kein Layout).
     */
    private int tabsAnchor() {
        if (harvesterNavigation == null || harvesterNavigation.getParent() != navigationSlot
                || stage.getWidth() <= 0 || !stage.isVisible()
                || javax.swing.SwingUtilities.getRoot(stage) != javax.swing.SwingUtilities.getRoot(top)) {
            return -1;
        }
        int left = javax.swing.SwingUtilities.convertPoint(stage, 0, 0, top).x;
        // Gemessen wird von Knopf zu Knopf, ohne die Abstände der Gruppe davor und dahinter.
        int width = harvesterNavigation.getPreferredSize().width;
        int[] span = {Integer.MAX_VALUE, Integer.MIN_VALUE};
        buttonSpan(harvesterNavigation, harvesterNavigation, span);
        if (span[0] > span[1]) {
            return -1;
        }
        int tabs = span[1] - span[0];
        int start = sideShown() ? left : left + (stage.getWidth() - tabs) / 2;
        return start + tabs + Math.max(0, width - span[1]);
    }

    /** Linke und rechte Kante der sichtbaren Knöpfe unter {@code parent}, in den Koordinaten von {@code root}. */
    private static void buttonSpan(java.awt.Container root, java.awt.Container parent, int[] span) {
        for (Component child : parent.getComponents()) {
            if (!child.isVisible() || child.getWidth() <= 0) {
                continue;
            }
            if (child instanceof javax.swing.AbstractButton) {
                int x = javax.swing.SwingUtilities.convertPoint(parent, child.getX(), 0, root).x;
                span[0] = Math.min(span[0], x);
                span[1] = Math.max(span[1], x + child.getWidth());
            } else if (child instanceof java.awt.Container nested) {
                buttonSpan(root, nested, span);
            }
        }
    }

    /** Was der Doppelklick auf die Fensterleiste gerade tut. */
    static String windowBarTooltip(boolean maximized) {
        return maximized ? RESTORE_TOOLTIP : MAXIMIZE_TOOLTIP;
    }

    /** Die Fensterleiste (für Tests). */
    JPanel windowBar() {
        return top;
    }
    /** Die Perspektiven des Fensters (#1141); entsteht mit {@link #content}. */
    private Perspectives perspectives;
    private PerspectiveSwitcher switcher;
    private AvengerEasterEgg avenger;
    /** Navigation der gewählten Perspektive rechts neben dem Titel. */
    /** Bedienelemente der Perspektive oben rechts vor Chat und Aufträgen (Perspective#tools, #1142). */
    private final JPanel toolsSlot = new JPanel(new BorderLayout());
    private final JPanel navigationSlot = new JPanel(new BorderLayout()) {
        @Override
        public Dimension getPreferredSize() {
            // Die Titelleiste behält in jeder Perspektive ihre Höhe: so hoch wie die Navigation des Space Harvesters.
            Dimension size = super.getPreferredSize();
            return harvesterNavigation == null ? size
                    : new Dimension(size.width, Math.max(size.height, harvesterNavigation.getPreferredSize().height));
        }
    };
    private JComponent harvesterNavigation;
    /** Arbeitsflächen aller Perspektiven; die rechte Seitenleiste liegt daneben, nicht darin. */
    private final CardLayout stageCards = new CardLayout();
    private final JPanel stage = new JPanel(stageCards);
    private JComponent searchSlot;
    /** Ob die linke Leiste des Space Harvesters zu war, als er verlassen wurde. */
    private boolean harvesterDrawerCollapsed;
    /** Das Command Deck (#1142); seine Sitzungen leben so lange wie das Fenster, über jeden Wechsel hinweg. */
    private final com.aresstack.spaceharvester.app.ui.deck.CommandDeck commandDeck;
    private final StatusView statusView;
    private final SourcesView sources;
    private final ContentView content;
    private final WorkspaceHistory<WorkspaceLocation> history = new WorkspaceHistory<>(WorkspaceLocation::label,
            this::restoreHistoryLocation);
    private final ActivitiesView activities;
    private final SearchView searchView;
    private final SettingsView settings;

    public MainWindow(AppContext context) {
        this.context = context;
        context.setNavigator(this);
        // Server des Command Deck aus KeePass, über dieselbe Kopplung wie die Wissensquellen; BetaView zusätzlich
        // über den Netzweg der App (Proxy, Zertifikate).
        commandDeck = new com.aresstack.spaceharvester.app.ui.deck.CommandDeck(
                new com.aresstack.spaceharvester.app.ui.deck.KeePassServerAccounts(context::keePass),
                com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess.of(context),
                new com.aresstack.spaceharvester.app.ui.deck.AppTerminalTunnel(context));
        commandDeck.folderIndexAction(new LocalFolderIndexAction(context));
        // NDV direkt wie MainframeMate; nur Verbindungen mit „Proxy der App verwenden“ nehmen den Proxy der App.
        commandDeck.ndvRoute(com.aresstack.spaceharvester.app.ui.deck.NdvNetwork.of(context));
        commandDeck.ftpNavigation(new FtpNavigationFill(context));
        commandDeck.ftpSources(new FtpSourceRules(context));
        commandDeck.ftpStoredSearch(context);
        // E-Akte: Proxy und TLS-Vertrauen der App (Windows-ROOT), das Anmeldeprotokoll ohne Werte ins Log.
        org.slf4j.Logger eakteLog = org.slf4j.LoggerFactory.getLogger("command-deck.eakte");
        commandDeck.eakte(new com.aresstack.spaceharvester.deck.eakte.http.HttpEakteServer(
                        com.aresstack.spaceharvester.app.ui.deck.DeckNetwork.http(context,
                                com.aresstack.spaceharvester.app.ui.deck.EakteWorkspace::proxyWanted),
                        java.time.Duration.ofSeconds(30), eakteLog::info,
                        // Abgerissene Dokumente so oft ab dem letzten Byte fortsetzen wie jobs.retry.max-attempts.
                        () -> context.settings() == null ? 0 : context.settings().jobLimits().retry().maxAttempts()));
        // ADR 0035: Dokumentanalyse der E-Akte aus dem laufenden Core, nach jedem (Neu-)Start neu angeschlossen.
        Runnable documentAnalyses = () -> {
            AppRuntime running = context.runtime();
            commandDeck.documentAnalyses(running == null ? null : running.workspace().documentAnalyses());
        };
        documentAnalyses.run();
        context.onChange(ignored -> documentAnalyses.run());
        // Freigegebene Ordner (Einstellungen › Dateizugriff) als Orte im Bereich Dateien, jeweils aktuell gelesen.
        commandDeck.sharedFolders(() -> context.settings() == null ? java.util.List.of()
                : context.settings().localFiles().roots());
        // Verlauf des Editors (Vergleich mit früheren Versionen) unter <Benutzer>/.space-harvester/history.
        commandDeck.history(new com.aresstack.spaceharvester.deck.editor.local.LocalVersionHistory(
                com.aresstack.spaceharvester.app.settings.AppSettingsLoader.defaultHome().resolve("history")));
        // SharePoint-Sites für „Orte“: die SharePoint-Quellen der Einstellungen (Titel und Adresse).
        commandDeck.sharePointSites(() -> context.settings() == null ? java.util.List.of()
                : context.settings().sources().stream()
                .filter(entry -> "sharepoint".equalsIgnoreCase(entry.config().type())
                        && entry.config().baseUrl() != null)
                .map(entry -> new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointSite(
                        entry.config().title(), entry.config().baseUrl().toString()))
                .toList());
        // SharePoint-Anmeldung nur einmal: nach dem ersten Erfolg verschlüsselt im Tresor (Unterordner sharepoint).
        commandDeck.sharePointLogins(new com.aresstack.spaceharvester.app.deck.VaultSavedLogins(
                context.vault() == null ? null : context.vault().scope("sharepoint")));
        // NDV im Command Deck: „Indexieren (Regel anlegen)“ legt eine Wissensquelle an (globale Suche, KI-Suche).
        com.aresstack.spaceharvester.app.ui.deck.NdvAnalysisSettings.searchLink(new NdvSourceRules(context));
        // Diagramm-Ansicht „Visuell“: KI-Beschreibung über die Zeile „Quellcode“, auf demselben Weg wie jede
        // Modellzeile des Chats (Workspace#aiChat(ModelUse)): Modell, Tokenlänge und Nachdenken setzt der Zugang selbst,
        // „aus“ richtig je Dienst; die Anfragen tragen kein eigenes reasoning_effort (Angelo 28.11.2026).
        com.aresstack.spaceharvester.app.ui.deck.visual.VisualAction.connect(() -> {
            var runtime = context.runtime();
            var settings = context.settings();
            if (runtime == null || settings == null || settings.ai() == null) {
                return null;
            }
            var use = com.aresstack.spaceharvester.ai.AiServiceSettings.sourceCodeUse(settings.ai().sourceCode());
            String model = use.model() != null ? use.model() : settings.ai().chatModel();
            // Was die Zeile offen lässt, kommt vom Text-Modell (Chat): dessen Tokenlänge, dessen Nachdenken.
            int maxTokens = use.maxTokens() != null ? use.maxTokens() : settings.ai().chatMaxTokens() != null
                    ? settings.ai().chatMaxTokens()
                    : com.aresstack.spaceharvester.ai.AiServiceSettings.DEFAULT_CHAT_MAX_TOKENS;
            // Tokenbudget aus Kontextlänge und demselben Ausgabelimit (F41-7); fehlt eines, geht der Code in einer
            // Anfrage. Kontextlänge aus den Einstellungen, sonst aus dem schon bekannten Modellkatalog des Dienstes.
            com.aresstack.spaceharvester.app.runtime.ModelBudget budget = com.aresstack.spaceharvester.app.runtime
                    .ModelBudget.chat(settings.ai().contextTokens(), runtime.workspace().catalogContextTokens(model,
                            false), maxTokens);
            return new com.aresstack.spaceharvester.app.ui.deck.visual.DiagramLabels.Target(
                    runtime.workspace().aiChat(use), model, null, budget, null, settings.ai().parallel());
        });
        statusView = new StatusView(context);
        sources = new SourcesView(context);
        content = new ContentView(context);
        activities = new ActivitiesView(context);
        searchView = new SearchView(context);
        content.useWorkspaceHistory(history, place -> {
            if (current == AppContext.Section.CONTENT) {
                history.visit(new WorkspaceLocation.Document(place));
                content.refreshHistoryNavigation();
            }
        });
        searchView.onSearchLocation(location -> {
            if (current == AppContext.Section.SEARCH) {
                history.visit(new WorkspaceLocation.Search(location));
                content.refreshHistoryNavigation();
            }
        });
        settings = new SettingsView(context);
        settings.onLeave(this::leaveSettings);
        explorer = new Explorer(context);
        // Die Auswahl links ist der Bereich der Suche, aber nur, solange die Suche zu sehen ist. Was man woanders
        // anklickt (etwa einen Treffer), ändert ihn nicht; die Lupe hebt die Auswahl beim Betreten der Suche auf
        // (Angelo, 24.11.2026).
        explorer.onScope(scopes -> {
            if (current == AppContext.Section.SEARCH && !history.restoring()) {
                searchView.scope(scopes);
            }
        });
        // Und umgekehrt: In der Suche an- oder abgehakte Wissensquellen werden links gewählt bzw. abgewählt, damit
        // man sieht, dass beides dasselbe ist (Angelo, 22.11.2026).
        // Klick auf die Überschrift „Suche in …“: links den Bereich der Suche wählen (Angelo, 23.11.2026).
        searchView.onLocate(explorer::select);
        searchView.onAnalyze(explorer::analyze);
        content.onAnalyzeArea(() -> explorer.analyze(explorer.scopes()));
        searchView.onSourcesChecked(scopes -> {
            if (current == AppContext.Section.SEARCH) {
                explorer.select(scopes);
            }
        });
        // Links gewählt bestimmt rechts (Angelo, 13.11.2026): der Reiter „Export“ folgt der Auswahl im Explorer.
        // Die Auswahl links bestimmt die Inhalte, darunter Begriffe, Fachglossar und Graph unter Analysen (ADR 0029).
        explorer.onSelection(content::selection);
        // Ein Favoriten-Ordner wählt die Sammlung über die eine Auflösung Ordner → Wurzeln (ADR 0034).
        explorer.onFolder(content::folder);
        // Die Liste „Artefakte“ links (ADR 0047 §5): derselbe Speicher wie der Chat; ein Klick öffnet in der Mitte.
        explorer.artifacts(content.artifactStore(), id -> {
            show(AppContext.Section.CONTENT);
            content.openArtifact(id);
        }, content::exportArtifact);
        // Die Mitte folgt der Wippe links; ein Verweis im Chat schaltet auf „Artefakte“, ein Reiter der Seitenansicht
        // oder eine geöffnete Seite zurück auf „Wissensquellen“. Was die KI ändert, zählt der Chip an der Wippe.
        explorer.onArtifactsShown(content::showArtifacts);
        content.onArtifactsWanted(() -> {
            show(AppContext.Section.CONTENT);
            explorer.showArtifacts(true);
        });
        content.onPageWanted(() -> explorer.showArtifacts(false));
        content.onUnseenArtifacts(explorer::unseenArtifacts);
        searchView.onSubmit(() -> show(AppContext.Section.SEARCH));
        // Über den Navigator geöffnete Seiten (etwa ein Suchtreffer) zeigt der Explorer aufgeklappt und gewählt.
        content.onPosition(position -> {
            if (position != null) {
                content.currentHistoryPlace().filter(place -> place.title() != null && !place.title().isBlank())
                        .ifPresent(place -> history.updateLabel(new WorkspaceLocation.Document(place)));
            }
            content.refreshHistoryNavigation();
            explorer.currentPage(position);
            if (position != null && revealOpened) {
                revealOpened = false;
                // Aus der Suche geöffnet: nur aufklappen und hervorheben, die Auswahl (der Suchfilter) bleibt
                // (Angelo, 23.11.2026); sonst wie bisher wählen.
                if (hintOpened) {
                    hintOpened = false;
                    explorer.hintPage(position);
                } else {
                    explorer.revealPage(position);
                }
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
        // Die Aufträge sind kein Bereich der Mitte mehr: sie stehen in der rechten Seitenleiste neben dem Chat.
        views.forEach((section, view) -> {
            if (view != activities) {
                deck.add(view.component(), section.name());
            }
        });
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
        com.aresstack.spaceharvester.app.ui.theme.ThemeSwitcher.activateStored();
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
        // Lage, Größe und maximiert wie beim letzten Beenden, passend zu den jetzt angeschlossenen Bildschirmen.
        WindowPlacement.restore(frame, new Dimension(1280, 820));
        var programSearch = new com.aresstack.spaceharvester.app.ui.deck.StoredProgramSearchAction(
                context, frame, frame::isDisplayable, busy -> {
                    String message = "Programmsuche wird vorbereitet … (zweimal Umschalt zum Abbrechen)";
                    context.events().publish(busy
                            ? new StatusMessageEvent(message, Integer.MAX_VALUE, Severity.INFO)
                            : StatusMessageEvent.clear(message));
                }, "FTP-Verzeichnis im Command Deck öffnen und „Als Wissensquelle hinzufügen“ klicken, oder in NDV "
                        + "Rechtsklick auf eine Bibliothek › „Indexieren (Regel anlegen)“.");
        var programShortcut = new DoubleShiftShortcut(frame, programSearch::open);
        programShortcut.install();
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                programShortcut.close();
                programSearch.cancel();
            }

            @Override
            public void windowClosing(WindowEvent event) {
                if (confirmUnsaved(frame) && confirmClose(frame)) {
                    WindowPlacement.remember(frame);
                    programShortcut.close();
                    programSearch.cancel();
                    frame.dispose();
                    // Aufräumen nicht auf dem Swing-Faden und mit Frist; danach enden Kindprozesse und die JVM sicher.
                    com.aresstack.spaceharvester.app.AppExit.exit(0,
                            java.util.List.of(commandDeck::shutdown, context::shutdown));
                }
            }
        });
        frame.setVisible(true);
        WindowPlacement.maximizeIfRemembered(frame);
        WindowPlacement.track(frame);
        // Die App startet in der Suche, ohne Auswahl, mit dem Chat als Suchagent (Angelo, 24.11.2026).
        openSearchTab();
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
        JPanel surface = edges;
        edges.bar(top);
        surface.setBackground(Comic.SURFACE);
        surface.setBorder(ComicBorder.windowBorder(Comic.PALETTE, WINDOW_PADDING));

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
            (section.administration() ? administration : icons).add(button);
        }
        // Chat, Analysen und Aufträge sind keine Bereiche, sondern Leisten neben jedem Bereich: ihr Umschalter steht
        // im Kopf der Leiste, zugeklappt senkrecht am rechten Fensterrand unter der Kopfleiste (Angelo 27.11.2026).
        sidePearls.tool(SWITCH_CHAT, com.aresstack.spaceharvester.app.ui.content.ChatSidebar.icon(), CHAT_TOOLTIP)
                .tool(SWITCH_ANALYSES, com.aresstack.spaceharvester.app.ui.content.AnalysesSidebar.icon(),
                        ANALYSES_TOOLTIP)
                .tool(SWITCH_JOBS, stack(), JOBS_TOOLTIP);
        sideSwitch.clear();
        sideSwitch.onSelect(name -> sideOpen(true, sideModeOf(name)));
        sideSwitch.onAgain(name -> sideOpen(false, sideMode));
        // Die Reiter der Seitenansicht schalten die Mitte um: ein Klick zeigt Inhalte, auch aus einem anderen Bereich.
        JComponent tabBar = content.tabBar();
        tabBar.setBorder(BorderFactory.createEmptyBorder(0, TAB_GAP, 0, 0));
        // Ein zweiter Klick auf den gezeigten Reiter wählt ihn ab: Ohne Auswahl zeigt die Mitte die Suche.
        content.onTabClick(() -> show(AppContext.Section.CONTENT), this::openSearchTab);
        icons.add(tabBar);
        // Der Titel steht in der Mitte der linken Gruppe und ist ihr einziger kürzbarer Teil (HeaderLayout). Er ist
        // zugleich der Umschalter der Perspektiven (#1141); rechts daneben steht die Navigation der gewählten.
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        perspectives = new Perspectives();
        switcher = new PerspectiveSwitcher(ApplicationIcons.titleLogo(), perspectives);
        // Avenger-Easter-Egg (#1141): vorläufiger Auslöser fünf Klicks auf das Logo; Ablauf und Auslöser sind getrennt.
        avenger = new AvengerEasterEgg(switcher);
        switcher.onSidebarToggle(this::logoClicked);
        com.aresstack.spaceharvester.app.ui.controls.ClickBurst.install(switcher.logo(), 5,
                java.time.Duration.ofMillis(700), avenger::reveal);
        west.add(HeaderLayout.leading(switcher), BorderLayout.CENTER);
        navigationSlot.setOpaque(false);
        west.add(navigationSlot, BorderLayout.EAST);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        east.setOpaque(false);
        toolsSlot.setOpaque(false);
        east.add(toolsSlot);
        east.add(trailing);
        east.add(administration);
        east.add(Dialogs.closeButton(new ComicWindowHoldCloseButton(Comic.PALETTE, minimize, close, 24)));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 16, 8, 10)));
        top.add(west, BorderLayout.WEST);
        top.add(east, BorderLayout.EAST);
        // Die Reiter der Mitte stehen über dem mittleren Fenster (Angelo 27.11.2026) und wandern mit seinen Teilern.
        ((HeaderLayout) top.getLayout()).anchor(this::tabsAnchor);
        stage.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                top.revalidate();
            }

            @Override
            public void componentMoved(java.awt.event.ComponentEvent event) {
                top.revalidate();
            }
        });
        // Ein Suchfeld für das ganze Fenster, rechtsbündig vor der Gruppe rechts (#515, Angelo 27.11.2026).
        searchSlot = SearchBarLayout.wrap(searchView.searchBar());
        top.add(searchSlot, BorderLayout.CENTER);
        ComicWindowDragger.install(top);
        // Meldet die Leiste beim Tooltip-Dienst an; den Text liefert getToolTipText je nach Fensterzustand.
        top.setToolTipText(MAXIMIZE_TOOLTIP);

        side.setBackground(Comic.SURFACE);
        // Unten ohne Rand: Die Fußleiste des Explorers (SideFoot) steht bündig auf einer Linie mit der Leiste mit der
        // Lupe unter der Arbeitsfläche, gleich hoch (Comic.FOOT_HEIGHT).
        // Oben ohne Rand: Die Kopfleiste des Explorers (HeadBar) steht auf einer Linie mit der der Mitte und der der
        // rechten Leiste (Angelo 27.11.2026); die Schubladen bringen ihren Abstand selbst mit.
        side.setBorder(BorderFactory.createEmptyBorder(0, 12, 0, 10));
        side.setMinimumSize(new Dimension(0, 0));
        side.add(explorer.component(), EXPLORER);
        side.add(bottomGap(sources.drawer()), AppContext.Section.SOURCES.name());
        side.add(bottomGap(settings.drawer()), AppContext.Section.SETTINGS.name());
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
        chatSidebar.onCollapse(() -> sideOpen(false, SIDE_CHAT));
        // Diagramm-Ansicht: „im Chat erklären lassen“ geht in diesen Chat, mit den Werkzeugen des Bereichs
        com.aresstack.spaceharvester.app.ui.deck.visual.VisualAction.chat((question, excerpt, reference) -> {
            sideOpen(true, SIDE_CHAT);
            return chatSidebar.chat().askAbout(question, excerpt, reference);
        });
        activities.sidebar(() -> chatLarge(!chatLarge), () -> sideOpen(false, SIDE_JOBS));
        analysesSidebar = content.analysesSidebar();
        analysesSidebar.onCollapse(() -> sideOpen(false, SIDE_ANALYSES));
        analysesSidebar.onOpen(() -> sideOpen(true, SIDE_ANALYSES));
        // Ein Sprung zu einer Fundstelle oder Seite soll sie zeigen: die große Ansicht wird wieder zur Leiste.
        analysesSidebar.onRevealMiddle(() -> {
            if (chatLarge && analysesOpen()) {
                chatLarge(false);
            }
        });
        analysesSidebar.enlargeButton().addActionListener(e -> chatLarge(!chatLarge));
        sideDeck.setBackground(Comic.SURFACE);
        sideDeck.add(chatSidebar, SIDE_CHAT);
        sideDeck.add(activities.component(), SIDE_JOBS);
        sideDeck.add(analysesSidebar, SIDE_ANALYSES);
        chatSidebar.chat().onAnswer(this::chatAnswered);
        // Command Deck: anderer Reiter, anderer Hinweis und andere Vorschläge im Chat.
        if (commandDeck != null) {
            commandDeck.onAreaChanged(chatSidebar.chat()::toolScopeChanged);
        }
        // Ein Quellen-Link soll die Seite zeigen: die große Ansicht wird wieder zur Leiste.
        chatSidebar.chat().onSourceOpened(() -> {
            if (chatLarge) {
                chatLarge(false);
            }
        });
        chatSidebar.enlargeButton().addActionListener(e -> chatLarge(!chatLarge));
        // Die Mitte gehört der gewählten Perspektive; die rechte Seitenleiste bleibt dieselbe Instanz für alle (#1141).
        stage.setBackground(Comic.SURFACE);
        stage.setMinimumSize(new Dimension(320, 0));
        perspectives.add(new Perspective(Perspectives.HARVESTER, "Space Harvester",
                "Quellen sammeln, suchen, lesen, chatten und analysieren", true, icons, work, side, null, null, null));
        perspectives.add(commandDeck.perspective());
        perspectives.add(new com.aresstack.spaceharvester.app.ui.proving.ProvingGroundWorkspace(context::keePass,
                context::dataDirectory).perspective());
        perspectives.add(Perspectives.missionControl());
        for (Perspective perspective : perspectives.all()) {
            stage.add(perspective.workspace(), perspective.id());
            if (perspective.status() != null) {
                // Der Stand der Perspektive steht in derselben Statuszeile wie der der Harvester-Bereiche.
                perspective.status().setOpaque(false);
                viewStatus.add(perspective.status(), PERSPECTIVE_STATUS + perspective.id());
            }
        }
        harvesterNavigation = icons;
        navigationSlot.add(icons, BorderLayout.CENTER);
        perspectives.onChange(this::perspectiveChanged);
        // Ein Knopf der eigenen Navigation schließt Einstellungen, die über der Perspektive stehen.
        for (Perspective perspective : perspectives.all()) {
            if (perspective.navigation() != null && !Perspectives.HARVESTER.equals(perspective.id())) {
                leaveSettingsOn(perspective.navigation());
            }
        }
        // Wechselt das Deck seinen Bereich auf anderem Weg (Kachel auf Home, Chat, Verbinden), gehen die Einstellungen
        // ebenso zu; sonst bliebe das Zahnrad gedrückt, obwohl links ein Deck-Bereich gewählt ist (Angelo, 24.11.).
        if (commandDeck != null) {
            commandDeck.onAreaChanged(() -> {
                if (settingsOver != null && Perspectives.COMMAND_DECK.equals(settingsOver.id())) {
                    leaveSettings();
                }
                refreshDrawerToggle();
            });
            commandDeck.onSidebarChanged(this::refreshDrawerToggle);
        }
        chatSplit = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT, stage, sideStack);
        chatSplit.setBorder(null);
        chatSplit.setBackground(Comic.SURFACE);
        chatSplit.setResizeWeight(1);
        chatSplit.setContinuousLayout(true);
        chatSplit.setMinimumSize(new Dimension(320, 0));
        ComicDivider.install(chatSplit, Comic.PALETTE);
        rightRail = com.aresstack.spaceharvester.app.ui.controls.DividerRail.install(chatSplit, sideSwitch, edges,
                edges.right()).spanning();
        // Leiste und Kugeln sitzen auf dem Trenner je auf Höhe ihres eigenen Bereichs.
        sidePearls.centres(tool -> {
            JComponent area = sidePearls.pearl(tool) ? pearlArea(tool) : sideDeck;
            return sideShown() && sideStack.shown().contains(area) && (sidePearls.pearl(tool) || chatOpen)
                    ? area.getY() + area.getHeight() / 2 : null;
        });
        sidePearls.onLayout(() -> rightRail.rests());
        sideStack.onLayout(() -> {
            edges.revalidate();
            edges.repaint();
        });
        sideStack.onShares(() -> rememberShares(sideStack, sideDeck, SIDE_SHARES_KEY));
        leftStack.onShares(() -> rememberShares(leftStack, side, LEFT_SHARES_KEY));
        // Zur Seite zieht die Knopfleiste ihren Trenner, nach oben oder unten löst sich ein Symbol als Kugel.
        sidePearls.carrier(edges);
        sidePearls.sideways(rightRail::onDivider, rightRail::dragging);
        rightRail.yieldWhile(sidePearls::carrying);
        sidePearls.onAttach((tool, wasOpen) -> {
            if (wasOpen) {
                sideOpen(true, sideModeOf(tool));
            }
        });
        if (chatSplit.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        sideClose();
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
        int recalled = WindowSizes.recallInt(SIDE_JOBS_KEY, 0);
        sideMode = recalled >= 0 && recalled < SIDE_MODES.size() ? SIDE_MODES.get(recalled) : SIDE_CHAT;
        recallShares(sideStack, sideDeck, SIDE_SHARES_KEY);
        recallShares(leftStack, side, LEFT_SHARES_KEY);
        placePearls();
        selectSideToggles();
        placeChat();
        leftStack.show(java.util.List.of(side));
        split = new com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane(
                leftStack, chatSplit, 200, 520, WindowSizes.recallInt(LEFT_WIDTH_KEY, SIDEBAR_WIDTH));
        split.setLeftWidthListener(width -> WindowSizes.rememberInt(LEFT_WIDTH_KEY, width));
        leftRail = com.aresstack.spaceharvester.app.ui.controls.DividerRail.install(split, explorer.view, edges,
                edges.left()).spanning();
        // Die Kette am linken Rand nimmt Kugeln auf, die von rechts herübergezogen werden; ihre Bereiche stehen in
        // der linken Leiste unter deren eigentlichem Inhalt.
        leftPearls = new com.aresstack.spaceharvester.app.ui.controls.PearlString(explorer.view);
        leftPearls.carrier(edges);
        leftPearls.sideways(leftRail::onDivider, leftRail::dragging);
        leftRail.yieldWhile(leftPearls::carrying);
        java.util.function.Function<java.awt.Point, com.aresstack.spaceharvester.app.ui.controls.PearlString> sides =
                point -> point.x < edges.getWidth() / 2 ? leftPearls : sidePearls;
        leftPearls.sides(sides);
        sidePearls.sides(sides);
        // Favoriten und Artefakte lassen sich wie Chat und Aufträge herausziehen; die Wissensquellen sind die
        // Leiste selbst und bleiben in ihr.
        explorer.string(leftPearls);
        for (String tab : explorer.standaloneTabs()) {
            // Dieselben Ränder wie der Explorer, damit die Fußleisten untereinander in einer Flucht stehen.
            explorer.area(tab).setBorder(side.getBorder());
        }
        leftPearls.centres(tool -> {
            JComponent area = leftPearls.pearl(tool) ? pearlArea(tool) : side;
            return leftStack.shown().contains(area) && split.getDividerSize() > 0
                    && (area != side || !split.isLeftCollapsed()) ? area.getY() + area.getHeight() / 2 : null;
        });
        leftPearls.onLayout(() -> leftRail.rests());
        leftStack.onLayout(() -> {
            edges.revalidate();
            edges.repaint();
        });
        split.onPrimary(this::placeLeft);
        arrange(perspectives.current() == null ? null : perspectives.current().id());
        // Die wiederhergestellte Anordnung gilt auch für die Bereiche: Jeder steht in der Leiste seines Rands.
        placePearls();
        selectSideToggles();
        placeChat();
        // Erst jetzt hören: Das Wiederherstellen der gemerkten Kugeln ist keine Änderung des Benutzers.
        sidePearls.onChange(this::sideChanged);
        leftPearls.onChange(this::sideChanged);
        sidePearls.onSeats(this::rememberSeats);
        leftPearls.onSeats(this::rememberSeats);
        if (WindowSizes.recallInt(LEFT_OPEN_KEY, 1) == 0) {
            collapseDrawer();
        }
        // Einklappbar: Doppelklick auf den Teiler klappt den Drawer zu bzw. wieder auf.
        split.onDividerToggle(this::toggleDrawer);

        actionDeck.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(6, 16, 6, 16)));

        // Gesamtstand aller Aufträge; Anhalten und Abbrechen stehen für alle zugleich in der Kopfzeile der Aufträge.
        jobStatus = new JobStatusLine(context.events());
        // Verlangt eine Quelle einen Einmalcode, steht es ganz links; der Klick führt zum Codefeld unter „Aufträge“.
        signInNotice = new SignInNotice(context, request -> {
            show(AppContext.Section.ACTIVITIES);
            activities.showSignIn(request);
        });
        // Scheitert das Erneuern des Proxy-Skripts, steht es daneben; der Klick zeigt den Grund (Angelo 16.11.2026).
        proxyNotice = new ProxyNotice(context);
        // Ohne Netz steht es daneben, bis es zurück ist (Angelo 22.11.2026).
        networkNotice = new NetworkNotice(context);
        JPanel statusBox = new JPanel(new BorderLayout(4, 0));
        statusBox.setOpaque(false);
        statusBox.add(status, BorderLayout.CENTER);
        statusBox.add(statusClose, BorderLayout.EAST);
        statusClose.setVisible(false);
        // Unten in der Ecke: „© Jahr AresStack“ bzw. das aktive Projekt, rechts der Pfeil zur Projektwahl (ADR 0043 F6).
        CopyrightLink corner = new CopyrightLink();
        ProjectChooser projectChooser = new ProjectChooser(context, surface);
        corner.chooser(projectChooser::show, projectChooser::open);
        corner.show(context.projects().active());
        Runnable showProject = () -> javax.swing.SwingUtilities.invokeLater(
                () -> corner.show(context.projects().active()));
        context.projects().addListener(showProject);
        // Das Fenster steht vor dem Laden der Einstellungen; erst danach ist der Datenordner und damit das gewählte
        // Projekt bekannt.
        context.onChange(ignored -> showProject.run());
        statusLine = new StatusLine(jobStatus.model(), StatusLine.alerts(signInNotice, networkNotice, proxyNotice), viewStatus,
                statusBox, jobStatus, corner);
        JComponent foot = statusLine;
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(foot, BorderLayout.SOUTH);

        surface.add(top, BorderLayout.NORTH);
        surface.add(split, BorderLayout.CENTER);
        // Der zweite Klick auf das gewählte Symbol klappt die linke Leiste zu; der Umschalter wandert dann senkrecht an
        // den linken Fensterrand, und ein Klick dort klappt sie wieder auf (Angelo 27.11.2026).
        explorer.view.onAgain(name -> toggleDrawer());
        explorer.view.onSelect(name -> {
            if (explorerDrawer() && split.isLeftCollapsed()) {
                toggleDrawer();
            }
        });
        refreshDrawerToggle();
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
        button.addActionListener(e -> clicked(section));
        return button;
    }

    /**
     * Ein Klick auf einen Bereichsknopf (Angelo, 27.11.2026): Der zweite Klick auf den gezeigten Bereich wählt ihn ab.
     * Ohne Auswahl zeigt die Mitte die Suche; die Einstellungen über einer anderen Perspektive geben diese wieder frei.
     */
    private void clicked(AppContext.Section section) {
        if (settingsOver != null && section == AppContext.Section.SETTINGS) {
            leaveSettings();
        } else if (section == current && settingsOver == null && perspectives.isCurrent(Perspectives.HARVESTER)) {
            openSearchTab();
        } else {
            showByHand(section);
        }
    }

    /** Ein Bereich, den der Benutzer selbst wählt (Knopf oder Strg+Zahl): die Suche von Hand hebt die Auswahl auf. */
    private void showByHand(AppContext.Section section) {
        if (section == AppContext.Section.SEARCH) {
            openSearchTab();
        } else {
            show(section);
        }
    }

    /**
     * Die Suche von Hand (Strg+Zahl oder Abwählen des gezeigten Knopfs; Angelo, 24.11. und 27.11.2026): Die Auswahl der Wissensquellen verschwindet ganz, auch die globale;
     * gesucht wird überall, und der Chat arbeitet ohne Bereich nur mit den zugeschalteten Suchen, ohne die langsame
     * Suche im Bereich. Die Suche in der Titelleiste lässt die Auswahl dagegen stehen.
     */
    void openSearchTab() {
        show(AppContext.Section.SEARCH);
        explorer.clearSelection();
        searchView.scope(explorer.scopes());
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
                    showByHand(section);
                }
            });
        }
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_J, java.awt.event.InputEvent.CTRL_DOWN_MASK), "chat");
        surface.getActionMap().put("chat", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                chatOpen(!chatOpen());
            }
        });
        // Esc stellt aus der großen Chat-Ansicht die Leiste wieder her, gleich wo der Fokus steht (auch im Explorer);
        // Felder und Overlays mit eigenem Esc gehen vor, weil diese Bindung als letzte gefragt wird.
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_ESCAPE, 0), "chat-shrink");
        surface.getActionMap().put("chat-shrink", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (sideShown() && chatLarge) {
                    chatLarge(false);
                }
            }
        });
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "find");
        surface.getActionMap().put("find", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                FindBar target = findTarget();
                // Die Suchleiste unten gehört zum Space Harvester; in anderen Perspektiven nur die der Aufträge.
                if (target == findBar && perspectives != null && !perspectives.isCurrent(Perspectives.HARVESTER)) {
                    return;
                }
                target.focus();
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
        sideOpen(open, SIDE_CHAT);
    }

    /** Klappt die Seitenleiste mit den Aufträgen auf oder zu (Knopf oben rechts, Strg+Ziffer). */
    void jobsOpen(boolean open) {
        sideOpen(open, SIDE_JOBS);
    }

    /** Klappt die Seitenleiste mit den Analysen auf oder zu (Knopf oben rechts zwischen Chat und Aufträgen). */
    void analysesOpen(boolean open) {
        sideOpen(open, SIDE_ANALYSES);
    }

    boolean analysesOpen() {
        return sideShows(SIDE_ANALYSES);
    }

    /** Ob der Bereich dieser Leiste zu sehen ist: als Wahl der Knopfleiste oder als offene Kugel. */
    private boolean sideShows(String mode) {
        String tool = switchOf(mode);
        return sidePearls.pearl(tool) ? sidePearls.open(tool) : chatOpen && mode.equals(sideMode);
    }

    /** Ob rechts überhaupt etwas offen ist: die Wahl der Knopfleiste oder der Bereich einer Kugel. */
    private boolean sideShown() {
        return chatOpen || !sidePearls.openPearls().isEmpty();
    }

    private static String switchOf(String mode) {
        return SIDE_JOBS.equals(mode) ? SWITCH_JOBS : SIDE_ANALYSES.equals(mode) ? SWITCH_ANALYSES : SWITCH_CHAT;
    }

    private javax.swing.AbstractButton enlargeOf(String mode) {
        return SIDE_JOBS.equals(mode) ? activities.enlarge
                : SIDE_ANALYSES.equals(mode) ? analysesSidebar.enlargeButton() : chatSidebar.enlargeButton();
    }

    private boolean onTheRight(String mode) {
        return sidePearls.host(switchOf(mode)) == sidePearls;
    }

    private JComponent sideArea(String mode) {
        return SIDE_JOBS.equals(mode) ? activities.component()
                : SIDE_ANALYSES.equals(mode) ? analysesSidebar : chatSidebar;
    }

    /** Der Bereich, den eine Kugel öffnet, gleich von welcher Leiste ihr Werkzeug stammt. */
    private JComponent pearlArea(String tool) {
        return sideTool(tool) ? sideArea(sideModeOf(tool)) : explorer.area(tool);
    }

    /** Stellt einen Bereich in den Stapel seines Rands. */
    private static void seat(JComponent area, java.awt.Container home) {
        if (area.getParent() != home) {
            if (area.getParent() != null) {
                area.getParent().remove(area);
            }
            home.add(area);
        }
    }

    /** Die Kugeln der rechten Leiste (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.PearlString sidePearls() {
        return sidePearls;
    }

    /** Die Bereiche der rechten Leiste untereinander (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.AreaStack sideStack() {
        return sideStack;
    }

    /**
     * Stellt jeden Bereich dorthin, wo sein Werkzeug steht: Der einer Kugel steht für sich in {@link #sideStack}, der
     * eines Symbols der Knopfleiste in {@link #sideDeck}. Wird das gewählte Symbol der Leiste zur Kugel, zeigt die
     * Leiste nichts mehr; der Bereich bleibt als der der Kugel offen.
     */
    private void placePearls() {
        for (String mode : SIDE_MODES) {
            JComponent area = sideArea(mode);
            String tool = switchOf(mode);
            // Als Kugel steht der Bereich in der Leiste des Rands, an dem die Kugel sitzt.
            java.awt.Container home = !sidePearls.pearl(tool) ? sideDeck
                    : sidePearls.host(tool) == sidePearls ? sideStack : leftStack;
            if (area.getParent() != home) {
                if (area.getParent() != null) {
                    area.getParent().remove(area);
                }
                if (home == sideDeck) {
                    area.setVisible(true);
                    sideDeck.add(area, mode);
                } else {
                    home.add(area);
                }
            }
            // Groß wird nur die rechte Leiste (die Arbeitsfläche weicht ihr); am linken Rand gibt es das nicht.
            enlargeOf(mode).setVisible(home != leftStack);
        }
        // Die herausgezogenen Hälften des Explorers stehen ebenso am Rand ihrer Kugel, sonst in seinem Stapel.
        if (leftPearls != null) {
            for (String tab : explorer.standaloneTabs()) {
                if (leftPearls.pearl(tab)) {
                    seat(explorer.area(tab), leftPearls.host(tab) == leftPearls ? leftStack : sideStack);
                }
            }
        }
        if (chatOpen && sidePearls.pearl(switchOf(sideMode))) {
            chatOpen = false;
            WindowSizes.rememberInt(CHAT_OPEN_KEY, 0);
        }
        if (!sidePearls.pearl(switchOf(sideMode))) {
            sideModes.show(sideDeck, sideMode);
        }
        // Die Bereiche stehen in der Reihenfolge ihrer Gruppen: Die oberste Kugel hat den oberen Bereich.
        java.util.List<java.awt.Component> areas = new java.util.ArrayList<>();
        for (String unit : sidePearls.units()) {
            if (com.aresstack.spaceharvester.app.ui.controls.PearlString.RAIL.equals(unit)) {
                // Ist rechts alles zu, bleibt der Bereich der Leiste der eine, der mit ihr auf null schrumpft.
                if (chatOpen || sidePearls.openPearls().isEmpty()) {
                    areas.add(sideDeck);
                }
            } else if (sidePearls.open(unit)) {
                areas.add(pearlArea(unit));
            }
        }
        sideStack.show(areas);
        placeLeft();
    }

    private static boolean sideTool(String tool) {
        return SWITCH_CHAT.equals(tool) || SWITCH_JOBS.equals(tool) || SWITCH_ANALYSES.equals(tool);
    }

    /**
     * Die Bereiche der linken Leiste: ihr eigentlicher Inhalt und darunter oder darüber, in der Reihenfolge der Kette,
     * der Bereich jeder offenen Kugel am linken Rand. Offene Kugeln halten die Leiste offen, auch wenn ihr
     * eigentlicher Inhalt zugeklappt ist.
     */
    private void placeLeft() {
        if (split == null || leftPearls == null) {
            return;
        }
        boolean any = !leftPearls.openPearls().isEmpty();
        boolean guests = leftPearls.openPearls().stream().anyMatch(MainWindow::sideTool);
        split.extras(any);
        java.util.List<java.awt.Component> areas = new java.util.ArrayList<>();
        for (String unit : leftPearls.units()) {
            if (com.aresstack.spaceharvester.app.ui.controls.PearlString.RAIL.equals(unit)) {
                if (!any || !split.isLeftCollapsed()) {
                    areas.add(side);
                }
            } else if (leftPearls.open(unit)) {
                areas.add(pearlArea(unit));
            }
        }
        leftStack.show(areas);
        fitLeftWidth(guests);
    }

    /** Die Breite, die die linke Leiste hatte, bevor ein Bereich der rechten sie breiter brauchte; sonst -1. */
    private int leftWidthBefore = -1;

    /**
     * Chat, Analysen und Aufträge brauchen ihre Mindestbreite auch am linken Rand (in schmalerer Leiste überlappen
     * sich die Knöpfe neben „Senden“). Solange einer dort offen ist, ist die linke Leiste mindestens so breit und
     * lässt sich nicht schmaler ziehen; danach hat sie wieder ihre alte Breite, wenn niemand sie verstellt hat.
     */
    private void fitLeftWidth(boolean guests) {
        int min = com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH;
        leftStack.setMinimumSize(new Dimension(guests ? min : 0, 0));
        // Über die gemerkte Breite der Teilung, nicht über den Teiler: Das gilt auch, solange die Leiste zugeklappt
        // ist, und zählt nicht als Ziehen des Benutzers (die gespeicherte Breite bleibt seine).
        int width = split.getPreferredLeftWidth();
        if (guests && width < min) {
            leftWidthBefore = width;
            split.setPreferredLeftWidth(min);
        } else if (!guests && leftWidthBefore >= 0) {
            if (width == min) {
                split.setPreferredLeftWidth(leftWidthBefore);
            }
            leftWidthBefore = -1;
        }
    }

    /** Die Kette am linken Rand (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.PearlString leftPearls() {
        return leftPearls;
    }

    /** Die Bereiche der linken Leiste untereinander (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.AreaStack leftStack() {
        return leftStack;
    }

    private static String side(String[] sides, String prefix) {
        for (String side : sides) {
            if (side.startsWith(prefix)) {
                return side.substring(prefix.length());
            }
        }
        return null;
    }

    private void rememberSeats() {
        if (!arranging && arrangedFor != null) {
            AppState.shared().put(SEATS_KEY + "." + arrangedFor,
                    "L=" + leftPearls.seats() + ";R=" + sidePearls.seats());
        }
    }

    /**
     * Ordnet die Kugeln so an, wie es für diese Perspektive gemerkt ist (Angelo 28.11.2026: jede Perspektive hat
     * ihre eigene Anordnung); ohne Gemerktes stehen alle Werkzeuge in ihrer Knopfleiste.
     */
    private void arrange(String perspective) {
        arranging = true;
        try {
            for (String tool : sidePearls.tools()) {
                sidePearls.home(tool);
            }
            for (String tool : leftPearls.tools()) {
                leftPearls.home(tool);
            }
            String stored = perspective == null ? null : AppState.shared().get(PEARLS_KEY + "." + perspective);
            if (stored == null) {
                String first = AppState.shared().get(SIDE_PEARLS_KEY);
                stored = first == null ? "" : "R=" + first;
            }
            for (String part : stored.split(";")) {
                if (part.startsWith("R=")) {
                    sidePearls.restore(part.substring(2), leftPearls);
                } else if (part.startsWith("L=")) {
                    leftPearls.restore(part.substring(2), sidePearls);
                }
            }
            String seats = perspective == null ? null : AppState.shared().get(SEATS_KEY + "." + perspective);
            String[] sides = seats == null ? new String[0] : seats.split(";");
            leftPearls.seats(side(sides, "L="), sidePearls);
            sidePearls.seats(side(sides, "R="), leftPearls);
        } finally {
            arranging = false;
        }
        arrangedFor = perspective;
    }

    /**
     * Die Bereiche, die in einer Leiste untereinander stehen können, in der Reihenfolge der gemerkten Anteile
     * ({@link #SIDE_SHARES_KEY}, {@link #LEFT_SHARES_KEY}): zuerst der eigene Inhalt der Leiste, dann die Bereiche
     * der Kugeln beider Ränder. Neue kommen hinten an, damit Gemerktes lesbar bleibt.
     */
    private java.util.List<JComponent> shareAreas(JComponent own) {
        java.util.List<JComponent> areas = new java.util.ArrayList<>();
        areas.add(own);
        for (String mode : SIDE_MODES) {
            areas.add(sideArea(mode));
        }
        for (String tab : explorer.standaloneTabs()) {
            areas.add(explorer.area(tab));
        }
        return areas;
    }

    private void rememberShares(com.aresstack.spaceharvester.app.ui.controls.AreaStack stack, JComponent own,
                                String key) {
        StringBuilder text = new StringBuilder();
        for (JComponent area : shareAreas(own)) {
            text.append(text.length() > 0 ? "," : "").append(Math.round(stack.share(area) * SHARE_SCALE));
        }
        AppState.shared().put(key, text.toString());
    }

    private void recallShares(com.aresstack.spaceharvester.app.ui.controls.AreaStack stack, JComponent own,
                              String key) {
        String text = AppState.shared().get(key);
        if (text == null || text.isBlank()) {
            return;
        }
        String[] parts = text.split(",");
        java.util.List<JComponent> areas = shareAreas(own);
        for (int index = 0; index < Math.min(parts.length, areas.size()); index++) {
            try {
                stack.share(areas.get(index), Integer.parseInt(parts[index].trim()) / (double) SHARE_SCALE);
            } catch (NumberFormatException unreadable) {
                // Ein unlesbarer Anteil bleibt gleich groß wie die anderen.
            }
        }
    }

    /** Klappt die rechte Leiste ganz zu: die Wahl der Knopfleiste und die Bereiche aller Kugeln. */
    private void sideClose() {
        chatOpen = false;
        WindowSizes.rememberInt(CHAT_OPEN_KEY, 0);
        if (sidePearls.openPearls().isEmpty()) {
            sideChanged();
        } else {
            sidePearls.closePearls();
        }
    }

    /** Die Hälfte „Analysen“ des Umschalters der rechten Leiste (für Tests). */
    javax.swing.AbstractButton analysesToggle() {
        return sidePearls.button(SWITCH_ANALYSES);
    }

    /** Die Hälfte „Aufträge“ des Umschalters der rechten Leiste (für Tests). */
    javax.swing.AbstractButton jobsToggle() {
        return sidePearls.button(SWITCH_JOBS);
    }

    private static String sideModeOf(String name) {
        return SWITCH_JOBS.equals(name) ? SIDE_JOBS : SWITCH_ANALYSES.equals(name) ? SIDE_ANALYSES : SIDE_CHAT;
    }

    /**
     * Zeigt am Umschalter, welche Leiste offen ist. Er steht immer senkrecht als Knopfleiste: offen als Griff auf dem
     * Trenner der Leiste ({@link com.aresstack.spaceharvester.app.ui.controls.DividerRail}), zugeklappt ohne
     * gewähltes Symbol am rechten Fensterrand.
     */
    private void selectSideToggles() {
        if (!chatOpen) {
            sideSwitch.clear();
        } else {
            sideSwitch.show(switchOf(sideMode));
        }
        sideSwitch.vertical(true);
        edges.place(edges.right(), sidePearls.column());
        edges.revalidate();
        edges.repaint();
    }


    /** Die Knopfleiste am Trenner der linken Leiste (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.DividerRail leftRail() {
        return leftRail;
    }

    /** Die Knopfleiste am Trenner der rechten Leiste (für Tests). */
    com.aresstack.spaceharvester.app.ui.controls.DividerRail rightRail() {
        return rightRail;
    }

    /** Der Umschalter der rechten Leiste (für Tests). */
    JComponent sideSwitch() {
        return sideSwitch;
    }

    /** Ob links der Baum der Wissensquellen steht: Nur er hat einen Umschalter, der die Leiste auch zuklappt. */
    private boolean explorerDrawer() {
        return settingsOver == null && perspectives.isCurrent(Perspectives.HARVESTER);
    }

    /** Das Logo klappt nur Leisten ohne eigenen Umschalter (Command Deck, Einstellungen). */
    private void logoClicked() {
        if (!explorerDrawer()) {
            toggleDrawer();
        }
    }

    /**
     * Stellt den Umschalter der linken Leiste an seinen Platz: als Knopfleiste auf ihren Trenner oder, zugeklappt,
     * ohne gewähltes Symbol an den linken Fensterrand. Leisten ohne Umschalter behalten den gelben Griff.
     */
    private void placeExplorerSwitch() {
        boolean drawer = explorerDrawer();
        if (drawer && split.isLeftCollapsed()) {
            explorer.switchAway();
        } else {
            explorer.switchHome();
        }
        // Die Knopfleiste des Explorers gibt es nur im Space Harvester; Kugeln am linken Rand stehen überall.
        leftPearls.rail(drawer);
        edges.place(edges.left(), leftPearls.column());
        leftRail.active(drawer || !leftPearls.pearls().isEmpty());
        edges.revalidate();
        edges.repaint();
    }

    /**
     * Die rechte Seitenleiste zeigt den Chat, die Analysen oder die Aufträge: der Knopf des einen blendet das andere aus, ein
     * zweiter Klick auf den aktiven Knopf klappt die Leiste zu (Angelo 22.11.2026). Breite und „groß“ gelten für
     * beide; was zuletzt offen war, gilt auch nach dem Neustart.
     */
    private void sideOpen(boolean open, String mode) {
        String tool = switchOf(mode);
        if (sidePearls.pearl(tool)) {
            // Eine Kugel öffnet ihren eigenen Bereich, unabhängig von der Knopfleiste.
            if (sidePearls.open(tool) != open) {
                sidePearls.open(tool, open);
            }
            return;
        }
        chatOpen = open;
        if (open) {
            sideMode = mode;
            WindowSizes.rememberInt(SIDE_JOBS_KEY, SIDE_MODES.indexOf(mode));
        }
        WindowSizes.rememberInt(CHAT_OPEN_KEY, open ? 1 : 0);
        sideChanged();
    }

    /** Nach jeder Änderung rechts: Bereiche stellen, Knopfleiste und Kugeln zeigen den Stand, Plätze merken. */
    private void sideChanged() {
        placePearls();
        if (!arranging && arrangedFor != null && leftPearls != null) {
            AppState.shared().put(PEARLS_KEY + "." + arrangedFor,
                    "L=" + leftPearls.placement() + ";R=" + sidePearls.placement());
        }
        if (split != null && leftPearls != null) {
            placeExplorerSwitch();
        }
        selectSideToggles();
        if (chatOpen()) {
            chatUnread(false);
        }
        placeChat();
        if (jobsOpen()) {
            activities.shown();
        } else if (chatOpen()) {
            chatSidebar.chat().focusEditor();
        }
    }

    boolean jobsOpen() {
        return sideShows(SIDE_JOBS);
    }

    /** Eine Antwort ist fertig: Ist die Leiste zu, zeigt der Chat-Knopf einen Punkt, bis sie aufgeklappt wird. */
    void chatAnswered() {
        if (!chatOpen()) {
            chatUnread(true);
        }
    }

    boolean chatUnread() {
        return chatUnread;
    }

    private void chatUnread(boolean unread) {
        chatUnread = unread;
        sidePearls.icon(SWITCH_CHAT, unread ? com.aresstack.spaceharvester.app.ui.content.ChatSidebar.unreadIcon()
                : com.aresstack.spaceharvester.app.ui.content.ChatSidebar.icon());
        sidePearls.tip(SWITCH_CHAT, unread ? CHAT_UNREAD_TOOLTIP : CHAT_TOOLTIP);
    }

    boolean chatOpen() {
        return sideShows(SIDE_CHAT);
    }

    /**
     * „Groß anzeigen“: dieselbe Leiste nimmt die ganze Arbeitsfläche, der Explorer bleibt; derselbe Knopf und Esc
     * stellen die vorige Breite wieder her. Gemerkt wie offen/zu.
     */
    void chatLarge(boolean large) {
        chatLarge = large;
        WindowSizes.rememberInt(CHAT_LARGE_KEY, large ? 1 : 0);
        placeChat();
        if (chatOpen()) {
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
            boolean shown = sideShown();
            sideStack.setMinimumSize(new Dimension(shown ? min : 0, 0));
            chatSidebar.shown(chatOpen());
            activities.sidebarShown(jobsOpen());
            analysesSidebar.shown(analysesOpen());
            boolean large = shown && chatLarge;
            chatSidebar.large(large && onTheRight(SIDE_CHAT));
            analysesSidebar.large(large && onTheRight(SIDE_ANALYSES));
            activities.large(large && onTheRight(SIDE_JOBS));
            // Groß: die Arbeitsfläche weicht ganz, die Teilung gibt der Leiste dann allen Platz und zeigt keinen Teiler.
            chatSplit.getLeftComponent().setVisible(!large);
            // Zugeklappt oder groß gibt es keinen Teiler (Angelo 28.11.2026: kein Griff, der sich nicht bewegen lässt);
            // auf geht die Leiste über ihre Knöpfe. Die Teilung selbst bleibt eingeschaltet: Abgeschaltet verlören
            // alle Trenner der Arbeitsfläche ihren Doppelpfeil-Zeiger.
            ComicDivider.draggable(chatSplit, shown && !large);
            chatSplit.setDividerSize(shown && !large ? ComicDivider.THICKNESS : 0);
            int width = chatSplit.getWidth();
            if (width > 0 && !large) {
                // Die Arbeitsfläche behält ihre Mindestbreite; in einem schmalen Fenster gibt die Leiste nach.
                int room = Math.max(0, width - chatSplit.getDividerSize() - 320);
                int wide = shown ? Math.max(Math.min(min, room), Math.min(chatWidth, room)) : 0;
                chatSplit.setDividerLocation(width - chatSplit.getDividerSize() - wide);
            }
            chatSplit.revalidate();
        } finally {
            placingChat = false;
        }
    }

    private boolean placingChat;

    /** Merkt die Breite, die der Benutzer der offenen Leiste durch Ziehen gegeben hat. */
    private void rememberChatWidth() {
        if (placingChat || !sideShown() || chatLarge || chatSplit.getWidth() <= 0) {
            return;
        }
        int width = chatSplit.getWidth() - chatSplit.getDividerSize() - chatSplit.getDividerLocation();
        if (width >= com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH && width != chatWidth) {
            chatWidth = width;
            WindowSizes.rememberInt(CHAT_WIDTH_KEY, width);
        }
    }

    /** Klappt den linken Drawer zu oder wieder auf. */
    void toggleDrawer() {
        if (split == null || perspectives == null) {
            return;
        }
        if (harvesterDrawerActive()) {
            split.toggle();
            WindowSizes.rememberInt(LEFT_OPEN_KEY, split.isLeftCollapsed() ? 0 : 1);
        } else {
            var pane = activeDeckSidebar();
            if (pane != null) {
                pane.toggle();
            }
        }
        refreshDrawerToggle();
    }

    private boolean harvesterDrawerActive() {
        return settingsOver != null || perspectives.isCurrent(Perspectives.HARVESTER);
    }

    /**
     * Die Seitenleiste der gezeigten Perspektive außerhalb des Space Harvester. Auch Perspektiven ohne eigenen
     * Umschalter (Proving Ground) klappen ihre Leiste über das Logo wieder auf: Zugeklappt hat sie keinen Trenner
     * mehr, an dem das ginge.
     */
    private com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane activeDeckSidebar() {
        return perspectives.isCurrent(Perspectives.COMMAND_DECK) ? commandDeck.activeSidebar()
                : com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane.activeIn(stage);
    }

    private void refreshDrawerToggle() {
        if (switcher == null || split == null || perspectives == null) {
            return;
        }
        placeExplorerSwitch();
        if (explorerDrawer()) {
            switcher.sidebarElsewhere();
        } else if (harvesterDrawerActive()) {
            switcher.sidebarState(true, !split.isLeftCollapsed());
        } else {
            var pane = activeDeckSidebar();
            switcher.sidebarState(pane != null, pane != null && !pane.isLeftCollapsed());
        }
    }

    private void collapseDrawer() {
        split.collapseByUser();
    }

    /**
     * Tauscht Navigation, Arbeitsfläche und linke Leiste gegen die der neuen Perspektive. Titelleiste, rechte
     * Seitenleiste (Chat und Aufträge), Statuszeile und laufende Aufträge bleiben unberührt (#1141).
     */
    private void perspectiveChanged(Perspective next) {
        if (leftPearls != null && !next.id().equals(arrangedFor)) {
            arrange(next.id());
            sideChanged();
        }
        boolean harvester = Perspectives.HARVESTER.equals(next.id());
        if (settingsOver != null) {
            // Gewechselt, während die Einstellungen über einer Perspektive standen: Sie gehen mit zu.
            settingsOver = null;
            current = beforeSettings;
            if (next.drawer() == null) {
                // Ihre Leiste geht zu, ohne den gemerkten Zustand des Space Harvesters zu überschreiben.
                split.collapseLeft();
            }
        }
        navigationSlot.removeAll();
        if (next.navigation() != null) {
            navigationSlot.add(next.navigation(), BorderLayout.CENTER);
        }
        navigationSlot.revalidate();
        navigationSlot.repaint();
        toolsSlot.removeAll();
        if (next.tools() != null) {
            toolsSlot.add(next.tools(), BorderLayout.CENTER);
        }
        toolsSlot.revalidate();
        toolsSlot.repaint();
        // Die Suchleiste gehört zum Space Harvester; für die anderen Perspektiven ist keine Suche festgelegt.
        searchSlot.setVisible(harvester);
        // Ein Chat für alle Perspektiven; die Werkzeuge kommen je Frage aus der aktiven Perspektive (#1151).
        if (chatSidebar != null) {
            boolean deck = Perspectives.COMMAND_DECK.equals(next.id());
            chatSidebar.chat().toolScope(deck ? commandDeck::chatScope : null, deck ? commandDeck::chatHints : null);
        }
        stageCards.show(stage, next.id());
        if (!harvester) {
            // Stand und Aktionen der Bereiche gehören zum Space Harvester; Aufträge und Copyright bleiben.
            // Eine Perspektive mit eigenem Stand zeigt ihn an derselben Stelle.
            if (next.status() != null) {
                viewStatusCards.show(viewStatus, PERSPECTIVE_STATUS + next.id());
                viewStatus.setVisible(true);
            } else {
                viewStatus.setVisible(false);
            }
            // Die Bereichsknöpfe (auch das Zahnrad der Einstellungen) gehören zum Space Harvester: In einer anderen
            // Perspektive bleibt keiner gedrückt, wenn die Einstellungen zugehen (Angelo, 24.11.).
            navGroup.clearSelection();
        }
        if (next.drawer() == null) {
            if (split.hiddenByUser() || !split.isLeftCollapsed()) {
                harvesterDrawerCollapsed = split.isLeftCollapsed();
            }
            // Ganz ohne Teiler: die Mitte nimmt die volle Breite; der gemerkte Zustand der Leiste bleibt.
            split.collapseLeft();
        } else if (harvesterDrawerCollapsed) {
            collapseDrawer();
        } else {
            split.openLeft();
        }
        if (harvester) {
            // Zurück im Space Harvester: der Bereich von vorhin mit seinem Stand und seinen Knöpfen.
            showSection(current);
        }
        refreshDrawerToggle();
        top.revalidate();
        top.repaint();
    }

    /** Die Perspektiven (für Tests und künftige Aufrufer). */
    Perspectives perspectives() {
        return perspectives;
    }

    /** Der Umschalter im Titel (für Tests). */
    AvengerEasterEgg avenger() {
        return avenger;
    }

    PerspectiveSwitcher perspectiveSwitcher() {
        return switcher;
    }

    /** Die Arbeitsfläche der gewählten Perspektive (für Tests). */
    JComponent stage() {
        return stage;
    }

    /** Das Command Deck (für Tests). */
    com.aresstack.spaceharvester.app.ui.deck.CommandDeck commandDeck() {
        return commandDeck;
    }

    /** Die Chat-/Auftrags-Seitenleiste (für Tests). */
    JComponent sideDeck() {
        return sideStack;
    }

    @Override
    public void show(AppContext.Section section) {
        // Wer einen Bereich aufruft, will ihn sehen: aus der großen Chat-Ansicht wird wieder die Leiste. Nur die
        // Auswahl im Explorer lässt sie groß, denn dort wählt man den Bereich der nächsten Frage.
        if (chatSplit != null && sideShown() && chatLarge) {
            chatLarge = false;
            WindowSizes.rememberInt(CHAT_LARGE_KEY, 0);
            placeChat();
        }
        showSection(section);
        if (section == AppContext.Section.CONTENT && !history.restoring()) {
            content.currentHistoryPlace().ifPresent(place -> history.visit(new WorkspaceLocation.Document(place)));
            content.refreshHistoryNavigation();
        }
    }

    /** Zeigt den Bereich, ohne die große Chat-Ansicht zu verlassen (Auswahl im Explorer). */
    private void showSection(AppContext.Section section) {
        showSection(section, false);
    }

    private void showSection(AppContext.Section section, boolean followingDocumentHistory) {
        // Ein Bereich des Space Harvesters (auch über einen Link im Chat) führt in dessen Perspektive zurück; die Aufträge
        // stehen in der globalen Seitenleiste und lassen die Perspektive, wie sie ist. Die Einstellungen gehören allen
        // Perspektiven und öffnen über der aktuellen.
        boolean settingsSection = section == AppContext.Section.SETTINGS || section == AppContext.Section.STATUS;
        if (perspectives != null && section != AppContext.Section.ACTIVITIES
                && !perspectives.isCurrent(Perspectives.HARVESTER)) {
            if (settingsSection) {
                if (settingsOver == null && current != AppContext.Section.SETTINGS) {
                    beforeSettings = current;
                }
                settingsOver = perspectives.current();
            } else {
                perspectives.select(Perspectives.HARVESTER);
            }
        }
        if (section == AppContext.Section.STATUS) {
            show(AppContext.Section.SETTINGS);
            settings.select(SettingsView.DIAGNOSIS);
            return;
        }
        if (section == AppContext.Section.ACTIVITIES) {
            // Die Aufträge stehen in der Seitenleiste; die Mitte bleibt, wie sie ist.
            jobsOpen(true);
            return;
        }
        cards.show(deck, section.name());
        boolean changed = section != current;
        if (changed && current != AppContext.Section.SETTINGS && settingsOver == null) {
            beforeSettings = current;
        }
        boolean leftSearch = changed && current == AppContext.Section.SEARCH;
        current = section;
        if (!followingDocumentHistory && leftSearch && explorer.scopes().isEmpty() && !explorer.allSelected()) {
            // Raus aus der Suche ohne Auswahl: „Wissensquellen“ wird gewählt und gelb markiert, als hätte man es
            // angeklickt (Angelo, 24.11.2026).
            explorer.selectAll();
        }
        content.searchFront(section == AppContext.Section.SEARCH);
        placeDocumentNavigation(section);
        placeOverlay();
        if (changed && section == AppContext.Section.SEARCH && !history.restoring()) {
            history.preview();
            content.refreshHistoryNavigation();
            // Gesucht wird in dem, was links gewählt ist; nur die Suche von Hand hebt die Auswahl auf.
            searchView.scope(explorer.scopes());
        }
        // Keine zweite Leiste: Die Quellenkarten bzw. die Bereiche der Einstellungen ersetzen im Drawer den Explorer,
        // solange der Bereich offen ist.
        boolean ownDrawer = section == AppContext.Section.SOURCES || section == AppContext.Section.SETTINGS;
        sideCards.show(side, ownDrawer ? section.name() : EXPLORER);
        if (ownDrawer && split != null && split.isLeftCollapsed()) {
            split.openLeft();
        }
        refreshDrawerToggle();
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
        // ADR 0033: Der Chat bezieht sich auf die Ansicht, die vorne ist (Leseansicht: offene Seite; Suche: Filter).
        content.chatSidebar().chat().searchFront(section == AppContext.Section.SEARCH);
        content.chatSidebar().chat().readingFront(section == AppContext.Section.CONTENT);
        findBar.tools(view.tools());
        findBar.help(view.help());
        view.shown();
        if (changed) {
            findBar.areaChanged();
        }
        if (settingsOver != null && section == AppContext.Section.SETTINGS) {
            // Über der Perspektive: deren Navigation und Titel bleiben, die Mitte zeigt die Einstellungen.
            stageCards.show(stage, Perspectives.HARVESTER);
            viewStatus.setVisible(false);
        }
    }

    private void restoreHistoryLocation(WorkspaceLocation location) {
        if (location instanceof WorkspaceLocation.Document document) {
            showSection(AppContext.Section.CONTENT, true);
            content.restoreHistoryPlace(document.place());
        } else if (location instanceof WorkspaceLocation.Search search) {
            showSection(AppContext.Section.SEARCH, true);
            explorer.select(search.location().area());
            searchView.restoreSearchLocation(search.location());
        }
        content.refreshHistoryNavigation();
    }

    /** Move the same document history control between the two view headers. */
    private void placeDocumentNavigation(AppContext.Section section) {
        if (section == AppContext.Section.SEARCH) {
            searchView.heading.leading(content.documentNavigation());
        } else {
            searchView.heading.leading(null);
            content.restoreDocumentNavigation();
        }
    }

    /** „Zurück“ der Einstellungen: dorthin, woher sie kamen, auch in eine andere Perspektive. */
    private void leaveSettings() {
        if (settingsOver == null) {
            show(beforeSettings);
            return;
        }
        Perspective back = settingsOver;
        settingsOver = null;
        current = beforeSettings;
        if (back.drawer() == null) {
            // Die Leiste der Einstellungen geht zu, ohne den gemerkten Zustand des Space Harvesters zu ändern.
            split.collapseLeft();
        }
        perspectiveChanged(back);
    }

    /** Hängt an jeden Knopf der Navigation: Einstellungen über der Perspektive schließen. */
    private void leaveSettingsOn(java.awt.Container navigation) {
        for (java.awt.Component child : navigation.getComponents()) {
            if (child instanceof javax.swing.AbstractButton button) {
                button.addActionListener(e -> {
                    if (settingsOver != null) {
                        leaveSettings();
                    }
                });
            } else if (child instanceof java.awt.Container container) {
                leaveSettingsOn(container);
            }
        }
    }

    /** Ob die Einstellungen gerade über einer anderen Perspektive stehen (für Tests). */
    boolean settingsOverPerspective() {
        return settingsOver != null;
    }

    /** Ob der Knopf des Bereichs gedrückt ist (für Tests). */
    boolean navSelected(AppContext.Section section) {
        return nav.get(section).isSelected();
    }

    @Override
    public void openPage(String sourceId, String pageId) {
        hintOpened = current == AppContext.Section.SEARCH;
        showSection(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPage(sourceId, pageId);
    }

    @Override
    public void openProgram(String sourceId, String pageId, String rawSha256, int line) {
        showSection(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openProgram(sourceId, pageId, rawSha256, line);
    }

    @Override
    public void openPassage(String sourceId, String passageId) {
        hintOpened = current == AppContext.Section.SEARCH;
        showSection(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPassage(sourceId, passageId);
    }

    @Override
    public void revealPage(String sourceId, String pageId) {
        explorer.revealPage(sourceId, pageId);
    }

    @Override
    public void revealPage(String sourceId, String spaceKey, String pageId) {
        explorer.revealPage(sourceId, spaceKey, pageId);
    }

    @Override
    public void hintPage(String sourceId, String spaceKey, String pageId) {
        explorer.hintPage(sourceId, spaceKey, pageId);
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
    public void openContainerFromTree(String sourceId, String spaceKey, String pageId, String title) {
        // Wie eine Seite: In der Suche filtert die Auswahl links nur.
        if (current == AppContext.Section.SEARCH) {
            return;
        }
        showSection(AppContext.Section.CONTENT);
        content.openContainer(sourceId, spaceKey, pageId, title);
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
    public void openBook(String folderId) {
        explorer.openBook(folderId);
    }

    /**
     * Öffnet ein globales Artefakt (etwa das Buch aus „Buch öffnen“) in der Mitte, links unter „Artefakte“ (ADR 0047 §5);
     * gehört es einem anderen Projekt, wird dieses aktiv.
     */
    @Override
    public void openArtifact(String projectId, String artifactId) {
        show(AppContext.Section.CONTENT);
        if (projectId != null && (context.projects().active() == null
                || !projectId.equals(context.projects().active().id()))) {
            context.projects().activate(projectId);
            // Der Chat wechselt auf die Meldung des Projekts hin; erst danach öffnen.
            javax.swing.SwingUtilities.invokeLater(() -> {
                explorer.revealArtifacts();
                content.openArtifact(artifactId);
            });
            return;
        }
        explorer.revealArtifacts();
        content.openArtifact(artifactId);
    }

    @Override
    public boolean opensArtifacts() {
        return true;
    }

    @Override
    public void newBookEdition(String folderId) {
        explorer.newBookEdition(folderId);
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

    @Override
    public void exclusionsChanged(String sourceId) {
        explorer.reloadSource(sourceId);
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

    /** Fragt nach, wenn Dokumente im Editor des Command Deck ungespeicherte Änderungen haben. */
    private boolean confirmUnsaved(JFrame frame) {
        java.util.List<String> unsaved = commandDeck.unsavedDocuments();
        if (unsaved.isEmpty()) {
            return true;
        }
        String names = String.join(", ", unsaved.subList(0, Math.min(unsaved.size(), 5)))
                + (unsaved.size() > 5 ? " …" : "");
        return Dialogs.confirm(frame, "Ungespeicherte Änderungen", (unsaved.size() == 1
                ? "„" + names + "“ hat" : unsaved.size() + " Dokumente (" + names + ") haben")
                + " ungespeicherte Änderungen. Space Harvester wirklich beenden und die Änderungen verwerfen?");
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
                + " noch. Beim Beenden werden sie unterbrochen: Hintergrundaufträge setzen beim nächsten Start "
                + "fort, die übrigen werden abgebrochen und ihr Ergebnis bleibt unbestätigt.");
    }

    /**
     * Gibt einer Schublade der linken Leiste den oberen und unteren Rand, den die Leiste selbst nicht mehr hat. Ohne Hülle: Die
     * Schublade bleibt direktes Kind der Leiste.
     */
    private static JComponent bottomGap(JComponent drawer) {
        drawer.setBorder(BorderFactory.createCompoundBorder(drawer.getBorder(),
                BorderFactory.createEmptyBorder(10, 0, 10, 0)));
        return drawer;
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
    /**
     * Wohin Strg+F führt: in die Leiste der Aufträge, wenn sie groß gezeigt wird oder der Fokus in ihr steht, sonst
     * in die Leiste der Mitte.
     */
    FindBar findTarget() {
        Component focus = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        boolean inJobs = focus != null && javax.swing.SwingUtilities.isDescendingFrom(focus, activities.component());
        return jobsOpen() && (chatLarge || inJobs) ? activities.findBar() : findBar;
    }

    ActivitiesView activities() {
        return activities;
    }

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
    /** Die Hälfte „Chat“ des Umschalters der rechten Leiste (für Tests). */
    javax.swing.AbstractButton chatToggle() {
        return sidePearls.button(SWITCH_CHAT);
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
