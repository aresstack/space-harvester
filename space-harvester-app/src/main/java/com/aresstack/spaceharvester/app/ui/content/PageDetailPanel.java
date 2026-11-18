package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.enterpriseai.ui.comic.control.ComicOverlayPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Facts;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.GlossaryTab;
import com.aresstack.spaceharvester.app.ui.PillTabs;
import com.aresstack.spaceharvester.app.ui.Problems;
import com.aresstack.spaceharvester.app.ui.TermGraphView;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.content.viewer.AttachmentViewerPanel;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachment;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderAttachmentLoader;
import com.aresstack.spaceharvester.app.ui.content.viewer.ReaderFileOpener;
import com.aresstack.spaceharvester.application.assets.AssetHarvest;
import com.aresstack.spaceharvester.application.assets.PageAssets;
import com.aresstack.spaceharvester.application.enrichment.Enrichments;
import com.aresstack.spaceharvester.application.enrichment.PageAcronyms;
import com.aresstack.spaceharvester.application.images.ImagePayload;
import com.aresstack.spaceharvester.application.images.PageImages;
import com.aresstack.spaceharvester.application.passages.PagePassages;
import com.aresstack.spaceharvester.application.passages.PassageId;
import com.aresstack.spaceharvester.application.passages.PassageLookup;
import com.aresstack.spaceharvester.application.sources.InvalidQueryException;
import com.aresstack.spaceharvester.application.terms.PageTerms;
import com.aresstack.spaceharvester.application.enrichment.PageText;
import com.aresstack.spaceharvester.application.query.DeliveredPage;
import com.aresstack.spaceharvester.application.query.PageBookmark;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotWarning;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextPane;
import javax.swing.KeyStroke;
import javax.swing.ToolTipManager;
import javax.swing.table.AbstractTableModel;
import java.awt.BorderLayout;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/**
 * Eine Seite: zuerst ihr lesbarer Text (statische Projektion, kein ausgeführtes HTML und keine nachgeladenen Bilder),
 * daneben Eigenschaften und Anhänge. Unter „Analysen“ liegen Absätze, Begriffe, Verweise, Bilder, Aussagen und
 * Graph als zweite Reiterzeile (#499); Analyse, Originaldaten, technische Angaben und die Aktionen dazu liegen unter
 * „Erweitert“. Ohne gewählte Seite steht dort nur ein Hinweis. Fehlt die Seite im lokalen Bestand, lädt sie live aus
 * der Quelle, ohne dass der Bediener etwas tun muss. Im Lesetext
 * sind die Akronyme markiert, die die Core-Analyse {@code acronyms} für genau diesen Text liefert ({@link PageAcronyms},
 * #115); Tooltip, Cursor und Alt+→/Alt+← zeigen ihre Ausschreibung.
 */
public final class PageDetailPanel extends JPanel {

    private static final System.Logger LOG = System.getLogger(PageDetailPanel.class.getName());

    private static final String READ = "Text";
    private static final String READING = "Leseansicht";
    private java.util.function.BiConsumer<String, String> pageShown = (source, space) -> { };
    /** Stand des sichtbaren Reiters für die Statuszeile des Hauptfensters ({@link #onStatus}). */
    private java.util.function.Consumer<String> statusShown = text -> { };
    /** Die Erläuterung je Reiter; gezeigt wird die des sichtbaren Reiters. */
    private final java.util.Map<String, String> tabStatus = new java.util.HashMap<>();
    private java.util.function.Consumer<com.aresstack.spaceharvester.application.navigation.PageNavigation.Position> positionShown = position -> { };
    private static final String PROPERTIES = "Eigenschaften";
    private static final String ATTACHMENTS = "Anhänge";
    private static final String REFERENCES = "Verweise";
    private static final String TERMS = "Begriffe";
    private static final String PASSAGES = "Absätze";
    private static final String IMAGES = "Bilder";
    private static final String ANALYSES = "Analysen";
    private static final String STATEMENTS = "Aussagen";
    static final String STATEMENTS_PLACEHOLDER = "Noch keine Aussagen – Analyse folgt";
    private static final String GRAPH = "Graph";
    static final String GRAPH_PLACEHOLDER = "Noch kein Wissensgraph – Analyse folgt";
    private static final String ADVANCED = "Erweitert";
    /** Reiter „Stand“ (ADR 0032, FL6): was in der Auswahl links gilt, geplant, unklar oder überholt ist. */
    static final String STAND = "Stand";
    /** Reiter „Export“ (Angelo, 13.11.2026): Stand und Ausgabe der Auswahl links, auch ohne gezeigte Seite. */
    static final String EXPORT = "Export";
    static final String CHAT = "Chat";
    private static final String ANALYSIS = "Analyse";
    private static final String RAW = "Originaldaten";
    private static final String TECHNICAL = "Technische Angaben";
    private static final String EMPTY = "empty";
    private static final String PAGE = "page";
    private static final String EXPORT_CARD = "export";
    private static final String CHAT_CARD = "chat";
    private static final String STAND_CARD = "standing";
    /** Die Quellenseite (ADR 0030): unter „Leseansicht“ die Updates der gewählten Wissensquelle. */
    private static final String SOURCE_CARD = "source";
    private static final String SOURCE_TEXT_CARD = "source-text";
    private static final String SOURCE_PROPERTIES_CARD = "source-properties";
    /** Technik-Reiter ohne Seite: dieselben drei Unterreiter, unter „Analyse“ nur die Beziehungen der Auswahl. */
    private static final String WIDE_ADVANCED_CARD = "wide-advanced";
    static final String EMPTY_PAGE = "Wähle links im Explorer eine Seite aus, um ihren Inhalt zu lesen.";
    /** Die übrigen Reiter der Quellenseite bleiben leer, bis sie Quelleninhalte zeigen; kein Hinweistext. */
    static final String EMPTY_SOURCE = " ";
    private static final int READER_PADDING_TOP = 10;

    private final AppContext context;
    private final JLabel title = new JLabel(" ");
    private final java.awt.CardLayout cards = new java.awt.CardLayout();
    private final JPanel deck = new JPanel(cards);
    /** Welche Karte Seite oder Hinweis zeigt, solange nicht der Reiter „Export“ gewählt ist. */
    private String pageCard = EMPTY;
    private final ExportPanel export;
    private final ChatPanel chat;
    private final StandingTab standing;
    /**
     * „Analysen“ ohne Seite (Wissensquellen-Kopf, Quelle, Space, Ordner): Unterreiter „Begriffe“ (dieselbe Darstellung
     * wie auf der Seite, über die Auswahl zusammengezählt) und „Aussagen“ (der Stand), Angelo 17.11.2026.
     */
    private final TermsTab overviewTerms = new TermsTab();
    /** Die Auswahl links, deren Begriffe {@link #overviewTerms} zeigen soll; gelesen erst, wenn sie zu sehen sind. */
    private ExportPanel.Selection overviewSelection;
    private boolean overviewTermsDirty;
    /** Schlüssel der Statuszeile für die Begriffe der Auswahl (getrennt von „Begriffe“ der Seite). */
    static final String OVERVIEW_TERMS = "Begriffe der Auswahl";
    /** Ob die Begriffe der Auswahl gerade zu sehen sind (Analysen ohne Seite, Reiter „Begriffe“). */
    private boolean overviewTermsShown;
    /** Fachglossar und Graph der Auswahl, hinten in der Reiterzeile von Analysen ohne Seite (BG2c). */
    private final GlossaryTab overviewGlossary;
    private final TermGraphView overviewGraph;
    /** Schlüssel der Statuszeile für das Fachglossar der Auswahl. */
    static final String OVERVIEW_GLOSSARY = "Fachglossar der Auswahl";
    /** Anmeldungen beim Bestand der Auswahl (ADR 0027): ändert er sich, zählen die Begriffe neu. */
    private final List<com.aresstack.spaceharvester.application.observe.Subscription> overviewHoldings =
            new ArrayList<>();
    /** Der Bestand hat sich geändert, während die Begriffe verdeckt waren: beim Zeigen neu zählen. */
    private boolean overviewRecount;
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton standingHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, STAND, StandingTab.HELP);
    /** Wohin nach dem Laden gesprungen wird (aus „Stand“); {@code null} ohne. */
    private StandingTab.Target pendingJump;
    /** Hilfe zu Fachglossar und Graph der Auswahl, je nur auf ihrem Reiter zu sehen. */
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton glossaryHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, StandingTab.GLOSSARY, GlossaryTab.HELP);
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton graphHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, StandingTab.GRAPH, TermGraphView.HELP);
    private final JLabel empty = Comic.muted(EMPTY_PAGE);
    private final JLabel path = Comic.muted(" ");
    private final Breadcrumbs breadcrumbs = new Breadcrumbs();
    private final References references = new References();
    private final TermsTab terms = new TermsTab();
    private final PassagesTab passages = new PassagesTab();
    private final ImagesTab images = new ImagesTab();
    /** Unterreiter „Analysen → Aussagen“ (#499): gespeicherte Analyse der Seite, neu nur auf Knopfdruck. */
    private final StatementsTab statementsView;
    /** Unterreiter „Analysen → Graph“ (#499 F): fokussierte Teilgraphen, geladen erst beim Anzeigen. */
    private final KnowledgeGraphTab graphView;
    /** Quellenseite: unter „Leseansicht“ die KI-Zusammenfassung des gewählten Updates. */
    private final SourceUpdatesView sourceView;
    /** Quellenseite: unter „Text“ die Updates mit ihren Änderungen (Angelo, 17.11.2026). */
    private final SourceUpdatesView sourceChanges;
    /** „Eigenschaften“ der Quellenseite: Kennzahlen und Einstellungen der Quelle. */
    private final SourcePropertiesView sourceProperties;
    private final JLabel state = Comic.muted(" ");
    /** Schließt das Seiteninfo-Popup, sobald die Maus Überschrift und Popup verlassen hat. */
    private final javax.swing.Timer detailsWatch = new javax.swing.Timer(120, e -> hideDetailsWhenLeft());
    private final Feedback feedback = new Feedback();
    /**
     * Hinweis über der Seite, wenn die Quelle einen neueren Stand hat als der sofort gezeigte aus dem Bestand (dieselbe
     * Platte wie in der Leseansicht, Angelo 17.11.2026); „Seite neu laden“ zeigt den schon geladenen neuen Stand.
     */
    private final MessagePlate update = new MessagePlate();
    private final JTextPane reader = new JTextPane() {
        @Override
        public String getToolTipText(MouseEvent event) {
            return acronymTooltip(event.getPoint());
        }
    };
    /** Reiter „Text“: der Lesetext mit dem Navigationsbanner der Dokumentnavigation darüber (Kopie aus dem EAC). */
    private final NavigableDocumentPage readerPage;
    private final JLabel acronymHint = Comic.muted(" ");
    private final JLabel acronymDetail = Comic.muted(" ");
    private final ComposerButton recomputeReader = Comic.button("Analyse neu berechnen",
            "Berechnet die Analysen dieser Seite neu und aktualisiert die Markierungen");
    private final Facts properties = new Facts();
    private final Facts technical = new Facts();
    private final JTextArea raw = Comic.code();
    private final JTextArea storage = Comic.code();
    private final AssetsModel assetsModel = new AssetsModel();
    private final JTable assets = Comic.table(new JTable(assetsModel));
    private final JLabel assetsHint = Comic.muted(" ");
    /** Unterreiter „Analyse“ der Seite: Analyseschritte und Beziehungen der Seite in einer Ansicht (BG2b). */
    private final AnalysisView analysisView;
    /** Derselbe Unterreiter ohne Seite (Ordner, Space, Quelle, Wissensquellen): nur die Beziehungen der Auswahl. */
    private final AnalysisView wideAnalysis;
    private final PillTabs advancedTabs = new PillTabs();
    private final PillTabs wideAdvanced = new PillTabs();
    private final PillTabs tabs = new PillTabs();
    /** Die Hauptreiter als quadratische Symbolknöpfe für die Icon-Leiste des Hauptfensters ({@link #tabIcons()}). */
    private final JComponent tabIcons;
    /** Zweite Reiterzeile unter „Analysen“ (#499): Absätze, Begriffe, Verweise, Bilder und Aussagen. */
    private final PillTabs analysisTabs = new PillTabs();
    /** Inhalt des Unterreiters „Aussagen“; bis {@link #statementsTab(JComponent)} ein Hinweis. */
    private final JPanel statements = new JPanel(new BorderLayout());
    /** Inhalt des Unterreiters „Graph“ (Wissensgraph, #499 F); bis {@link #knowledgeGraphTab(JComponent)} ein Hinweis. */
    private final JPanel graph = new JPanel(new BorderLayout());
    private final ComposerButton refresh = Comic.button("Aktualität prüfen",
            "Vergleicht die Seite live mit der Quelle (refresh); der Bestand ändert sich erst beim Abgleich");
    private final ComposerButton saveRaw = Comic.button("Original speichern …", "Native Payload Byte für Byte");
    private final ComposerButton browse = Comic.button("Im Browser öffnen", "Öffnet die Seite in der Quelle im Browser");
    /** Der Stern im Kopf (Angelo, 13.11.2026): dieselbe Markierung wie im Explorer, gefüllt als Favorit. */
    private final com.aresstack.spaceharvester.app.ui.IconButton star =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.STAR,
                    "Als Favorit markieren");
    /** Pfad der gezeigten Seite, sobald der Core ihn geliefert hat; für den Favoriten. */
    private com.aresstack.spaceharvester.application.navigation.PageNavigation.Position shownPosition;
    /** Die Überschrift für die Zeile der Bereichsanzeige ({@link #headerTitle()}): mittig, nie breiter als ihr Text. */
    private final JPanel heading = new JPanel(null) {
        /** Die Seitenüberschrift oder, im Reiter Chat, „Chat: ‹Name› ▾“ (Angelo, 17.11.2026): das sichtbare Kind. */
        private java.awt.Component shown() {
            for (java.awt.Component child : getComponents()) {
                if (child.isVisible()) {
                    return child;
                }
            }
            return title;
        }

        @Override
        public void doLayout() {
            java.awt.Component shown = shown();
            java.awt.Dimension t = shown.getPreferredSize();
            int width = Math.min(t.width, getWidth());
            shown.setBounds((getWidth() - width) / 2, (getHeight() - t.height) / 2, width, t.height);
        }

        @Override
        public java.awt.Dimension getPreferredSize() {
            return shown().getPreferredSize();
        }

        @Override
        public java.awt.Dimension getMinimumSize() {
            return new java.awt.Dimension(0, shown().getPreferredSize().height);
        }
    };
    /** Weitere Aktionen zur Seite hinter einem Dreieck neben dem Stern, wie im Audio Studio (Angelo, 16.11.2026). */
    private final com.aresstack.spaceharvester.app.ui.IconButton more =
            new com.aresstack.spaceharvester.app.ui.IconButton(
                    new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(), MORE_ACTIONS);
    static final String MORE_ACTIONS = "Weitere Aktionen zur Seite";
    private final ComposerButton bookmark = Comic.button("Lesezeichen kopieren",
            "Stabile Adresse dieser Seite und Version (space-harvester://…); unter Suche wieder zu öffnen");
    private final ComposerButton harvest = Comic.button("Alle Anhänge ablegen",
            "Lädt die Anhänge dieser Seite in die lokale Anhangsablage");
    private final ComposerButton saveAsset = Comic.button("Anhang speichern …", "Datei des gewählten Anhangs");
    private final ComposerButton previewAsset = Comic.button("Vorschau",
            "Zeigt den gewählten Anhang im Anhang-Viewer (auch per Doppelklick)");
    /** Reiter „Anhänge“: Liste unten, der Anhang-Viewer als Comic-Overlay darüber (wie im Enterprise AI Client). */
    private final JLayeredPane attachmentLayers = new JLayeredPane();
    private ComicOverlayPanel attachmentOverlay;
    private ReaderFileOpener fileOpener = ReaderFileOpener.system();
    private Executor attachmentRenderer;
    private final ComposerButton recompute = Comic.button("Neu berechnen", "Analysen dieser Seite neu berechnen");

    private String sourceId;
    private String pageId;
    private DeliveredPage page;
    private boolean assetsLoaded;
    private boolean readingLoaded;
    private boolean referencesLoaded;
    private boolean imagesLoaded;
    /** Anmeldung bei der Liste freigegebener Seiten (ADR 0028), damit „Bilder“ eine neue Freigabe der Seite zeigt. */
    private com.aresstack.spaceharvester.application.observe.Subscription releaseSubscription =
            com.aresstack.spaceharvester.application.observe.Subscription.NONE;
    private AppRuntime releaseRuntime;
    private static final String ORIGINAL_PAGE_CARD = "original-page";
    private static final String ORIGINAL_FILE_CARD = "original-file";
    private final ReadingPane readingPane = new ReadingPane();
    private final java.awt.CardLayout originalCards = new java.awt.CardLayout();
    private final JPanel originalContent = new JPanel(originalCards);
    private final JPanel fileContent = new JPanel(new BorderLayout());
    private AttachmentViewerPanel originalFileViewer;
    private boolean originalLinkShown;
    /** Hilfetext der Leseansicht einer Seite; nur sichtbar, solange sie gezeigt wird. */
    static final String READING_HELP = "Die Leseansicht zeigt die Seite, wie die Quelle sie darstellt. Liegt sie im "
            + "Cache, erscheint sie sofort; die Prüfung bei der Quelle läuft im Hintergrund.\n\n"
            + "Hat die Quelle inzwischen einen neueren Stand, bleibt der gezeigte stehen, und oben steht „Es gibt N "
            + "Updates seit Ihrem letzten Besuch – Seite neu laden“. Der Klick zeigt den neuen Stand an derselben "
            + "Stelle und markiert die Änderungen wie bei Git: neue Absätze grün, geänderte blau, entfernte rot und "
            + "durchgestrichen an ihrer alten Stelle. Die Farben bleiben, bis du die Seite verlässt.\n\n"
            + "Grenzen: Markiert wird nur der Unterschied zwischen dem gezeigten Stand aus dem Cache und dem neuen. "
            + "Wer die Seite verlässt, ohne zu klicken, sieht beim nächsten Öffnen den neuen Stand ohne Farben, weil "
            + "die Prüfung den Cache schon erneuert hat. Ist der geänderte Teil sehr lang (mehr als rund tausend "
            + "Absätze auf beiden Seiten), bleibt die Seite ohne Farben; Bilder ohne Text zählen nicht als Änderung.";
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton readingHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, "Leseansicht", READING_HELP);
    /** Maustaste Zurück (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_BACK_BUTTON}). */
    static final int MOUSE_BACK_BUTTON = 4;
    /** Maustaste Vor (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_FORWARD_BUTTON}). */
    static final int MOUSE_FORWARD_BUTTON = 5;
    /**
     * Verlauf wie im Lesebereich des EAC, hier über alles, was die Mitte zeigt (Angelo, 17.11.2026): zuvor gezeigte
     * Seiten und Quellenseiten (Quelle oder Space) und die per Zurück verlassenen.
     */
    private final java.util.Deque<Place> backStack = new java.util.ArrayDeque<>();
    private final java.util.Deque<Place> forwardStack = new java.util.ArrayDeque<>();
    /** Erfährt jeden Schritt per Zurück/Vor, damit Bereichsanzeige und Baum folgen. */
    private java.util.function.Consumer<Place> navigated = place -> { };
    /** Solange Zurück/Vor lädt, kommt die verlassene Seite nicht noch einmal in den Verlauf. */
    private boolean navigating;
    /** Zählt Link-Aktivierungen; nur die Auflösung des zuletzt geklickten Links wirkt. */
    private int linkRequest;
    /** Anker des Links, der die gerade ladende Seite geöffnet hat; die Leseansicht rollt dorthin. */
    private String pendingAnchor;
    private final com.aresstack.spaceharvester.app.ui.IconButton backButton = new com.aresstack.spaceharvester.app.ui
            .IconButton(com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.chevronLeft(),
            "Zurück (Maustaste Zurück)");
    private final com.aresstack.spaceharvester.app.ui.IconButton forwardButton = new com.aresstack.spaceharvester.app
            .ui.IconButton(com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.chevronRight(),
            "Vor (Maustaste Vor)");
    /** Die Maustasten Zurück (4) und Vor (5) irgendwo im Fenster, beim Loslassen wie im EAC. */
    private final java.awt.event.AWTEventListener mouseNavigation = event -> {
        if (event instanceof MouseEvent mouse && event.getID() == MouseEvent.MOUSE_RELEASED) {
            mouseButton(mouse.getButton(), mouse.getComponent());
        }
    };
    private List<Enrichments.View> views = List.of();
    private ReaderDocument shown;
    /** Zählt Lade- und Neuberechnungsaufträge; nur das Ergebnis des letzten wird angezeigt. */
    private int request;

    public PageDetailPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        this.statementsView = new StatementsTab(() -> context.runtime() == null ? null
                : context.runtime().workspace(), context.parserModel(), () -> context.settings() == null
                ? com.aresstack.spaceharvester.application.configuration.SimplificationSettings.DEFAULT
                : context.settings().simplification(), () -> context.settings() == null
                ? com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT
                : context.settings().check());
        this.sourceView = new SourceUpdatesView(() -> context.runtime() == null ? null
                : context.runtime().workspace(), SourceUpdatesView.Mode.SUMMARY);
        this.sourceChanges = new SourceUpdatesView(() -> context.runtime() == null ? null
                : context.runtime().workspace(), SourceUpdatesView.Mode.CHANGES);
        for (SourceUpdatesView view : List.of(sourceView, sourceChanges)) {
            view.onOpen(changed -> {
                if (context.navigator() != null) {
                    context.navigator().openPage(changed.sourceId(), changed.pageId());
                } else {
                    load(changed.sourceId(), changed.pageId(), false);
                }
            });
        }
        // Beide Reiter zeigen dasselbe Update: Wer es in einem wählt, sieht es im anderen auch.
        sourceView.onSelected(sourceChanges::select);
        sourceChanges.onSelected(sourceView::select);
        this.sourceProperties = new SourcePropertiesView(() -> context.runtime() == null ? null
                : context.runtime().workspace(), context::settings);
        sourceProperties.secretState((current, entry) -> com.aresstack.spaceharvester.app.ui.SecretState.of(current,
                entry, context.secrets().has(entry), context.vault().has(entry.id(),
                        com.aresstack.spaceharvester.app.runtime.AppRuntime.SessionSecrets.login(entry))));
        sourceProperties.onEdit(id -> {
            if (context.navigator() != null) {
                context.navigator().editSource(id);
            }
        });
        this.graphView = new KnowledgeGraphTab(() -> context.runtime() == null ? null
                : context.runtime().workspace());
        this.export = new ExportPanel(context);
        this.chat = new ChatPanel(context);
        this.standing = new StandingTab(context::runtime);
        this.analysisView = new AnalysisView(context, true);
        this.wideAnalysis = new AnalysisView(context, false);
        this.overviewGlossary = new GlossaryTab(context, () -> context.runtime() == null
                ? java.util.Optional.empty() : context.runtime().workspace().glossary());
        this.overviewGraph = new TermGraphView(context);
        setBackground(Comic.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 14));
        title.setFont(Comic.semiBold(15f));
        title.setForeground(Comic.INK);

        reader.setEditable(false);
        reader.setBorder(BorderFactory.createEmptyBorder(READER_PADDING_TOP, 12, 10, 12));
        reader.setFont(Comic.regular(13.5f));
        reader.getCaret().setVisible(true);
        ToolTipManager.sharedInstance().registerComponent(reader);
        reader.addCaretListener(e -> showAcronymAt(Math.min(e.getDot(), e.getMark())));
        bind("alt RIGHT", "nextAcronym", true);
        bind("alt LEFT", "previousAcronym", false);
        acronymDetail.setForeground(Comic.INK);

        JPanel readerPanel = new JPanel(new BorderLayout(0, 6));
        readerPanel.setOpaque(false);
        JPanel acronymBar = new JPanel(new BorderLayout(8, 0));
        acronymBar.setOpaque(false);
        acronymBar.add(acronymHint, BorderLayout.CENTER);
        acronymBar.add(Comic.row(recomputeReader), BorderLayout.EAST);
        readerPanel.add(acronymBar, BorderLayout.NORTH);
        // Der Banner liegt als eigene Ebene über dem Lesetext und nimmt ihm keine Breite.
        readerPage = new NavigableDocumentPage(Comic.scroll(reader), reader, READER_PADDING_TOP);
        readerPanel.add(readerPage, BorderLayout.CENTER);
        // Ein Themenabschnitt beginnt mit einem Absatz: unter „Absätze“ steht derselbe Absatz gewählt bereit.
        readerPage.onJump(section -> {
            if (section.passageId() != null) {
                passages.select(section.passageId());
            }
        });
        readerPanel.add(acronymDetail, BorderLayout.SOUTH);

        JPanel attachmentList = new JPanel(new BorderLayout(0, 6));
        attachmentList.setOpaque(false);
        attachmentList.add(assetsHint, BorderLayout.NORTH);
        attachmentList.add(Comic.scroll(assets), BorderLayout.CENTER);
        attachmentList.add(Comic.row(previewAsset, harvest, saveAsset), BorderLayout.SOUTH);
        attachmentLayers.setLayout(new FillLayout());
        attachmentLayers.add(attachmentList, JLayeredPane.DEFAULT_LAYER);
        JPanel attachmentsPanel = new JPanel(new BorderLayout());
        attachmentsPanel.setOpaque(false);
        attachmentsPanel.add(attachmentLayers, BorderLayout.CENTER);
        assets.getSelectionModel().addListSelectionListener(e -> updatePreviewEnabled());
        assets.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && assets.rowAtPoint(e.getPoint()) >= 0) {
                    openAttachment();
                }
            }
        });

        PillTabs rawTabs = new PillTabs()
                .tab("Payload", Comic.scroll(raw))
                .tab("Storage-Format", Comic.scroll(storage));
        advancedTabs.tab(TECHNICAL, Comic.scroll(padded(technical)))
                .tab(ANALYSIS, analysisView)
                .tab(RAW, rawTabs);
        // Die Aktionen stehen rechts in der Zeile der Unterreiter, nicht als eigene Knopfzeilen darüber und darunter;
        // „Synonyme vorschlagen“ und „Neue Beziehung …“ nur, solange „Analyse“ gewählt ist (BG2b).
        advancedTabs.actions(analysisView.proposeButton(), analysisView.createButton(), refresh, saveRaw, recompute);
        advancedTabs.onSelect(name -> showAdvanced());
        // Ohne Seite: dieselben Unterreiter; „Technische Angaben“ und „Originaldaten“ bleiben dort leer.
        wideAdvanced.tab(TECHNICAL, new JPanel())
                .tab(ANALYSIS, wideAnalysis)
                .tab(RAW, new JPanel())
                .actions(wideAnalysis.proposeButton(), wideAnalysis.createButton());
        wideAdvanced.select(ANALYSIS);
        wideAdvanced.onSelect(name -> showAdvanced());
        JPanel advanced = new JPanel(new BorderLayout(0, 8));
        advanced.setOpaque(false);
        advanced.add(advancedTabs, BorderLayout.CENTER);

        // Leseansicht zuerst und damit Standard; ein gewählter Reiter bleibt beim Laden der nächsten Seite.
        // Fachliche Analysen der Seite (#499) unter einem Hauptreiter; die Ansichten selbst bleiben dieselben.
        statements.setOpaque(false);
        statements.add(placeholder(STATEMENTS_PLACEHOLDER), BorderLayout.CENTER);
        graph.setOpaque(false);
        graph.add(placeholder(GRAPH_PLACEHOLDER), BorderLayout.CENTER);
        analysisTabs.tab(PASSAGES, passages)
                .tab(TERMS, terms)
                .tab(REFERENCES, references)
                .tab(IMAGES, images)
                .tab(STATEMENTS, statements)
                .tab(GRAPH, graph);
        originalContent.setOpaque(false);
        fileContent.setOpaque(false);
        originalContent.add(readingPane, ORIGINAL_PAGE_CARD);
        originalContent.add(fileContent, ORIGINAL_FILE_CARD);
        tabs.tab(READING, originalContent)
                .tab(READ, readerPanel)
                .tab(PROPERTIES, Comic.scroll(padded(properties)))
                .tab(ATTACHMENTS, attachmentsPanel)
                .tab(ANALYSES, analysisTabs)
                .tab(ADVANCED, advanced)
                // Der Export hängt nicht an der gezeigten Seite, sondern an der Auswahl links; er liegt deshalb als
                // eigene Karte neben Seite und Hinweis, der Reiter hält nur den Platz.
                .tab(EXPORT, new JPanel())
                // Der Chat ebenso: sein Wissen ist die Auswahl links, nicht die gezeigte Seite.
                .tab(CHAT, new JPanel());
        // Die Hauptreiter wandern als Symbolknöpfe in die Icon-Leiste oben (Angelo, 13.11.2026);
        // die Unterreiter von „Analysen“ rücken dadurch an die Stelle der bisherigen Reiterzeile.
        java.util.Map<String, Icon> icons = new java.util.LinkedHashMap<>();
        icons.put(READING, PageTabIcons.reading());
        icons.put(READ, PageTabIcons.text());
        icons.put(PROPERTIES, PageTabIcons.properties());
        icons.put(ATTACHMENTS, PageTabIcons.attachments());
        icons.put(ANALYSES, PageTabIcons.analyses());
        icons.put(ADVANCED, PageTabIcons.advanced());
        icons.put(EXPORT, PageTabIcons.export());
        icons.put(CHAT, ChatIcons.chat());
        tabIcons = tabs.iconBar(icons);
        // Nachladen nur für den sichtbaren Reiter: beim Wechsel des Haupt- wie des Unterreiters.
        tabs.onSelect(name -> {
            showDeck();
            loadVisible();
            showTabStatus();
        });
        analysisTabs.onSelect(name -> {
            if (ANALYSES.equals(tabs.selected())) {
                loadVisible();
                showTabStatus();
            }
        });
        passages.onStatus(text -> tabStatus(PASSAGES, text));
        images.onStatus(text -> tabStatus(IMAGES, text));
        references.onStatus(text -> tabStatus(REFERENCES, text));
        statementsView.onStatus(text -> tabStatus(STATEMENTS, text));
        sourceView.onStatus(text -> tabStatus(READING, text));
        standing.onStatus(text -> tabStatus(STAND, text));
        standing.onJump(this::openAt);
        // Nach jedem (Neu-)Start, etwa nach dem Speichern der Wörter für den Stand, liest „Stand“ neu (Beobachter, kein
        // Zeitgeber); verdeckt erst beim nächsten Zeigen.
        if (context != null) {
            context.onChange(ignored -> standing.settingsChanged());
        }
        sourceChanges.onStatus(text -> tabStatus(READ, text));
        graphView.onStatus(text -> tabStatus(GRAPH, text));
        // Lauf-, Erfolgs- und Infomeldungen gehen in die Statuszeile des Hauptfensters; die Platte zeigt nur Probleme.
        feedback.statusLine((text, tone) -> context.events().publish(
                new com.aresstack.spaceharvester.app.event.StatusMessageEvent(text,
                        tone == Comic.BLUE ? Integer.MAX_VALUE : STATUS_MS,
                        com.aresstack.spaceharvester.app.event.Severity.INFO)));
        breadcrumbs.onOpen(id -> load(sourceId, id, false));
        references.onOpen(id -> load(sourceId, id, false));
        terms.onJump(this::jumpTo);
        passages.onJump(this::jumpTo);
        statementsView.onJump(this::jumpTo);
        statementsTab(statementsView);
        graphView.onJump(this::jumpTo);
        statementsView.onAnalysed(graphView::analysisChanged);
        graphView.onDeleted(statementsView::reload);
        knowledgeGraphTab(graphView);
        images.onDescribe(() -> describeImages(false));
        images.onDescribeOnce(() -> describeImages(true));
        images.onRelease(this::releaseSourceAndDescribe);

        refresh.addActionListener(e -> load(sourceId, pageId, true));
        saveRaw.addActionListener(e -> saveRaw());
        browse.addActionListener(e -> browse());
        bookmark.addActionListener(e -> copyBookmark());
        star.addActionListener(e -> toggleFavorite());
        context.favorites().onChange(() -> javax.swing.SwingUtilities.invokeLater(this::updateStar));
        harvest.addActionListener(e -> harvestAssets());
        saveAsset.addActionListener(e -> saveAsset());
        previewAsset.addActionListener(e -> openAttachment());
        recompute.addActionListener(e -> recompute());
        recomputeReader.addActionListener(e -> recompute());
        readingPane.onLink(this::linkActivated);
        readingPane.onViewerLook(() -> Comic.viewerLook(context.settings() == null
                ? com.aresstack.spaceharvester.app.settings.ViewerBackground.DEFAULT
                : context.settings().viewerBackground()));
        backButton.addActionListener(e -> back());
        forwardButton.addActionListener(e -> forward());
        updateNavigation();

        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setOpaque(false);
        // Nur die fette Titelzeile bleibt stehen; Ort, Version, Herkunft und Bildzeile erscheinen,
        // sobald die Maus auf der Überschrift steht, und verschwinden, wenn sie Überschrift und Popup verlässt.
        title.getAccessibleContext().setAccessibleDescription("Details zur Seite erscheinen beim Überfahren");
        title.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                showDetails();
            }
        });
        // Die Überschrift steht mittig in der Zeile der Bereichsanzeige (Angelo, 16.11.2026) und ist nur so breit wie ihr
        // Text (mit „…“ bei Platzmangel), damit nur der Titel das Popup öffnet.
        heading.setOpaque(false);
        heading.add(title);
        heading.add(chat.header());
        chat.header().setVisible(false);
        chat.headerActions().setVisible(false);
        more.addActionListener(e -> PopupHover.clearOnClose(moreMenu(), more)
                .show(more, 0, more.getHeight()));
        JPanel notices = new JPanel();
        notices.setOpaque(false);
        notices.setLayout(new javax.swing.BoxLayout(notices, javax.swing.BoxLayout.Y_AXIS));
        update.setAlignmentX(LEFT_ALIGNMENT);
        feedback.setAlignmentX(LEFT_ALIGNMENT);
        notices.add(update);
        notices.add(feedback);
        header.add(notices, BorderLayout.SOUTH);
        JPanel page = new JPanel(new BorderLayout(0, 8));
        page.setOpaque(false);
        page.add(header, BorderLayout.NORTH);
        page.add(tabs, BorderLayout.CENTER);
        empty.setHorizontalAlignment(JLabel.CENTER);
        empty.setFont(Comic.regular(14f));
        deck.setOpaque(false);
        deck.add(empty, EMPTY);
        deck.add(page, PAGE);
        deck.add(export, EXPORT_CARD);
        deck.add(chat, CHAT_CARD);
        // Begriffe vorn in der Reiterzeile des Stands, wie auf der Seite; keine zweite Reiterebene (AT 17.11.2026).
        standing.terms(overviewTerms, visible -> {
            overviewTermsShown = visible;
            showOverviewHelp();
            if (visible) {
                loadOverviewTerms();
                if (overviewRecount) {
                    overviewRecount = false;
                    overviewTerms.recount();
                }
            }
        });
        overviewTerms.onOpen(hit -> openAt(new StandingTab.Target(hit.sourceId(), hit.pageId(), -1, -1)));
        overviewTerms.onStatus(text -> tabStatus(OVERVIEW_TERMS, text));
        // Fachglossar und Graph hinten in derselben Reiterzeile (BG2c, aus dem früheren Bereich „Begriffe“): dieselben
        // Ansichten, ihre Werkzeuge oben rechts nur auf ihrem Reiter, beide nach der Auswahl links.
        standing.tab(StandingTab.GLOSSARY, overviewGlossary.component(), visible -> {
            showOverviewHelp();
            if (visible) {
                overviewGlossary.shown();
            }
        }, overviewGlossary.tools(), overviewGlossary.addButton(), overviewGlossary.detectButton());
        overviewGlossary.onStatus(text -> tabStatus(OVERVIEW_GLOSSARY, text));
        standing.tab(StandingTab.GRAPH, overviewGraph.component(), visible -> {
            showOverviewHelp();
            if (visible) {
                overviewGraph.shown();
            }
        }, overviewGraph.tools());
        glossaryHelp.setVisible(false);
        graphHelp.setVisible(false);
        deck.add(standing, STAND_CARD);
        deck.add(wideAdvanced, WIDE_ADVANCED_CARD);
        deck.add(sourceView, SOURCE_CARD);
        deck.add(sourceChanges, SOURCE_TEXT_CARD);
        deck.add(sourceProperties, SOURCE_PROPERTIES_CARD);
        add(deck, BorderLayout.CENTER);
        clear();
    }

    /**
     * Keine Seite gewählt (z. B. nach dem Wechsel von Quelle oder Space): leert alles und verwirft laufende Lade- und
     * Neuberechnungsaufträge, damit keine Seite einer anderen Quelle nachträglich erscheint.
     */
    public void clear() {
        remember(null);
        clearState();
    }

    /** Wie {@link #clear()}, ohne den Verlauf zu berühren: für den Wechsel auf eine Quellenseite. */
    private void clearState() {
        request++;
        sourceId = null;
        pageId = null;
        page = null;
        assetsLoaded = false;
        readingLoaded = false;
        referencesLoaded = false;
        imagesLoaded = false;
        readingPane.clear(" ");
        closeOriginalFile();
        breadcrumbs.clear();
        positionShown.accept(null);
        references.message(" ");
        views = List.of();
        showReader(null, false);
        acronymHint.setText(" ");
        closeAttachmentOverlay();
        assetsModel.set(null);
        analysisView.steps(List.of(), null);
        analysisView.scope(null);
        properties.reset();
        technical.reset();
        raw.setText("");
        storage.setText("");
        title.setText(" ");
        path.setText(" ");
        state.setText(" ");
        renderDetails();
        feedback.clear();
        setActionsEnabled(false);
        showCard(EMPTY);
    }

    /** Setzt den Favoriten der gezeigten Seite oder nimmt ihn zurück (Pfad aus der geladenen Position). */
    private void toggleFavorite() {
        if (page == null || sourceId == null || pageId == null) {
            return;
        }
        String spaceKey = shownPosition != null && shownPosition.spaceKey() != null ? shownPosition.spaceKey()
                : page.header().metadata().partitionKey();
        if (spaceKey == null) {
            return;
        }
        String title = page.header().metadata().title();
        java.util.List<String> path = new java.util.ArrayList<>();
        java.util.List<String> ancestors = new java.util.ArrayList<>();
        path.add(sourceTitle(sourceId));
        if (shownPosition != null) {
            path.add(shownPosition.spaceName() == null || shownPosition.spaceName().isBlank() ? spaceKey
                    : shownPosition.spaceName());
            for (com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode ancestor
                    : shownPosition.ancestors()) {
                path.add(ancestor.title());
                ancestors.add(ancestor.pageId());
            }
        } else {
            path.add(spaceKey);
        }
        context.favorites().toggle(new com.aresstack.spaceharvester.app.ui.Favorites.Favorite(sourceId, spaceKey,
                pageId, title, path, ancestors));
    }

    private String sourceTitle(String id) {
        AppRuntime runtime = context.runtime();
        if (runtime != null) {
            for (com.aresstack.spaceharvester.application.sources.HarvestSource source
                    : runtime.workspace().sources()) {
                if (source.id().equals(id)) {
                    return source.title() == null || source.title().isBlank() ? id : source.title();
                }
            }
        }
        return id;
    }

    /** Gefüllt, wenn die gezeigte Seite ein Favorit ist; ohne Seite gesperrt. */
    private void updateStar() {
        boolean shown = page != null && sourceId != null && pageId != null;
        String spaceKey = !shown ? null : shownPosition != null && shownPosition.spaceKey() != null
                ? shownPosition.spaceKey() : page.header().metadata().partitionKey();
        boolean favorite = spaceKey != null && context.favorites().contains(sourceId, spaceKey, pageId);
        star.setEnabled(shown && spaceKey != null);
        star.setIcon(favorite ? com.aresstack.spaceharvester.app.ui.Glyph.STAR_FILLED
                : com.aresstack.spaceharvester.app.ui.Glyph.STAR);
        star.setToolTipText(favorite ? "Favorit entfernen" : "Als Favorit markieren");
        star.getAccessibleContext().setAccessibleName(favorite ? "Favorit entfernen" : "Als Favorit markieren");
    }

    /** Für Tests: der Stern im Kopf. */
    javax.swing.AbstractButton starButton() {
        return star;
    }

    /** Seite oder Hinweis merken; sichtbar wird es erst, wenn weder „Export“ noch „Chat“ gewählt ist. */
    private void showCard(String card) {
        if (!SOURCE_CARD.equals(card)) {
            // Keine Quellenseite mehr: Anmeldung beim Änderungsprotokoll endet, die leere Karte nennt wieder die Seite.
            sourceView.source(null, null);
            sourceChanges.source(null, null);
            sourceProperties.source(null);
            empty.setText(EMPTY_PAGE);
        }
        pageCard = card;
        showDeck();
    }

    private void showDeck() {
        String selected = tabs.selected();
        // Die Werkzeuge der Updates stehen nur da, wo die Updates zu sehen sind.
        sourceView.tools().setVisible(SOURCE_CARD.equals(pageCard) && READING.equals(selected));
        sourceChanges.tools().setVisible(SOURCE_CARD.equals(pageCard) && READ.equals(selected));
        sourceProperties.tools().setVisible(SOURCE_CARD.equals(pageCard) && PROPERTIES.equals(selected));
        readingHelp.setVisible(PAGE.equals(pageCard) && READING.equals(selected));
        // Analysen ohne Seite (Wissensquellen-Kopf, Quelle, Space, Ordner): der Stand der Auswahl links, mit den
        // Unterreitern „Aussagen“, „Zuerst lesen“, „Mögliche Widersprüche“ (Angelo 17.11.2026, kein eigenes Symbol).
        boolean standingShown = standingShown();
        standing.shown(standingShown);
        showOverviewHelp();
        // Im Reiter Chat steht mittig der Chat statt der Seitenüberschrift, rechts Neuer Chat und Chat löschen.
        boolean chatTab = CHAT.equals(selected);
        title.setVisible(!chatTab);
        chat.header().setVisible(chatTab);
        chat.headerActions().setVisible(chatTab);
        // Die Schnellaktionen in der unteren Leiste gehören nur zum Chat.
        chat.quickActions().setVisible(chatTab);
        heading.revalidate();
        heading.repaint();
        String page = pageCard;
        if (SOURCE_CARD.equals(page)) {
            // Quellenseite: „Leseansicht“ die KI-Zusammenfassung, „Text“ die Updates; die übrigen Reiter noch leer.
            page = READING.equals(selected) ? SOURCE_CARD : READ.equals(selected) ? SOURCE_TEXT_CARD
                    : PROPERTIES.equals(selected) ? SOURCE_PROPERTIES_CARD : EMPTY;
        }
        boolean wideAdvancedShown = ADVANCED.equals(selected) && wide;
        cards.show(deck, EXPORT.equals(selected) ? EXPORT_CARD : CHAT.equals(selected) ? CHAT_CARD
                : standingShown ? STAND_CARD : wideAdvancedShown ? WIDE_ADVANCED_CARD : page);
        showAdvanced();
    }

    /**
     * Die Quellenseite einer gewählten Wissensquelle (ADR 0030): leert die Seitenreiter und zeigt unter „Leseansicht“
     * die Updates der Quelle; Export und Chat folgen ohnehin der Auswahl.
     */
    public void showSource(String sourceId, String sourceTitle) {
        showSource(sourceId, null, sourceTitle);
    }

    /**
     * Dieselbe Seite für einen Space (ADR-0030-Nachtrag, TZ5): „Text“ und „Leseansicht“ zeigen die Updates der Quelle
     * als Ausschnitt dieses Space; {@code spaceKey == null} die ganze Quelle.
     */
    public void showSource(String sourceId, String spaceKey, String title) {
        String shownTitle = title == null || title.isBlank() ? spaceKey == null ? sourceId : spaceKey : title;
        remember(Place.level(sourceId, spaceKey, shownTitle));
        clearState();
        this.title.setText(shownTitle);
        empty.setText(EMPTY_SOURCE);
        sourceView.source(sourceId, spaceKey, shownTitle);
        sourceChanges.source(sourceId, spaceKey, shownTitle);
        // Kennzahlen und Einstellungen gibt es vorerst nur für die ganze Quelle.
        sourceProperties.source(spaceKey == null ? sourceId : null);
        pageCard = SOURCE_CARD;
        showDeck();
    }

    /**
     * „Wissensquellen“ im Explorer (Angelo 17.11.2026): die Seitenreiter zeigen leer statt einer vorher gezeigten
     * Seite oder Quelle, der Reiter Eigenschaften die Gesamtsicht über alle Quellen.
     */
    public void showAllSources(List<String> sourceIds, String title) {
        clear();
        this.title.setText(title);
        empty.setText(EMPTY_SOURCE);
        sourceView.source(null, null, title);
        sourceChanges.source(null, null, title);
        sourceProperties.all(sourceIds);
        pageCard = SOURCE_CARD;
        showDeck();
    }

    /** Ob gerade die Gesamtsicht über genau diese Wissensquellen gezeigt wird. */
    boolean showsAllSources(List<String> sourceIds) {
        return showsSource() && sourceProperties.showsAll() && sourceProperties.allIds().equals(sourceIds);
    }

    /** Zeigt den Reiter „Updates“ der Quellenseite (Doppelklick auf eine Quelle). */
    public void showReading() {
        tabs.select(READING);
    }

    /** Der Reiter „Export“ (Angelo, 13.11.2026): Stand und Ausgabe der Auswahl links. */
    public ExportPanel export() {
        return export;
    }

    /** Der Reiter „Chat“ (Angelo, 13.11.2026), vorerst ein Click-Dummy; sein Wissen ist die Auswahl links. */
    public ChatPanel chat() {
        return chat;
    }

    /** Der Stand der Auswahl links (ADR 0032, FL6), im Reiter „Analysen“, solange links keine Seite gewählt ist. */
    StandingTab standing() {
        return standing;
    }

    /**
     * Die Auswahl links für „Begriffe“ unter Analysen ohne Seite; gelesen wird erst, wenn der Unterreiter zu sehen ist.
     */
    void overviewSelection(ExportPanel.Selection selection) {
        if (java.util.Objects.equals(selection, overviewSelection)) {
            return;
        }
        overviewSelection = selection;
        overviewTermsDirty = true;
        overviewRecount = false;
        tabStatus(OVERVIEW_TERMS, " ");
        AppRuntime runtime = context.runtime();
        watchOverviewHoldings(runtime, selection);
        overviewGlossary.selection(selection);
        overviewGraph.pages(selection == null || runtime == null || selection.global() ? null
                : () -> runtime.workspace().resolveSelection(selection.toCore(runtime)).pages());
        wideAnalysis.scope(selection == null || runtime == null ? null : new AnalysisView.Scope() {
            @Override
            public PageSet pages() {
                return selection.global() ? null
                        : runtime.workspace().resolveSelection(selection.toCore(runtime)).pages();
            }

            @Override
            public int relations(PageSet pages) {
                return runtime.workspace().termRelationCounts(pages).values().stream().mapToInt(Integer::intValue)
                        .sum();
            }
        });
        if (overviewTermsShown) {
            loadOverviewTerms();
        }
    }

    /**
     * Meldet sich beim Bestand der Auswahl an (eine Quelle samt Space, bei „Wissensquellen“ jede Quelle), damit Zahlen
     * und Herkunft der Begriffe nach Laden, Abgleich oder Bereinigung stimmen; kein Zeitgeber.
     */
    private void watchOverviewHoldings(AppRuntime runtime, ExportPanel.Selection selection) {
        overviewHoldings.forEach(com.aresstack.spaceharvester.application.observe.Subscription::close);
        overviewHoldings.clear();
        if (runtime == null || selection == null) {
            return;
        }
        List<String> sourceIds = selection.global()
                ? runtime.workspace().sources().stream()
                        .map(com.aresstack.spaceharvester.application.sources.HarvestSource::id).toList()
                : List.of(selection.sourceId());
        String spaceKey = selection.global() ? null : selection.spaceKey();
        com.aresstack.spaceharvester.application.holdings.HoldingsListener listener =
                new com.aresstack.spaceharvester.application.holdings.HoldingsListener() {
                    @Override
                    public void pagesChanged(String source, String space, java.util.Set<String> pageIds) {
                        javax.swing.SwingUtilities.invokeLater(() -> holdingsChanged(selection));
                    }

                    @Override
                    public void spaceChanged(String source, String space) {
                        javax.swing.SwingUtilities.invokeLater(() -> holdingsChanged(selection));
                    }
                };
        for (String source : sourceIds) {
            try {
                overviewHoldings.add(runtime.workspace().subscribeHoldings(source, spaceKey, listener));
            } catch (RuntimeException gone) {
                // Die Quelle gibt es in dieser Laufzeit nicht mehr; der Baum räumt die Auswahl gleich weg.
            }
        }
    }

    private void holdingsChanged(ExportPanel.Selection selection) {
        if (!java.util.Objects.equals(selection, overviewSelection) || overviewTermsDirty) {
            return;
        }
        if (overviewTermsShown) {
            overviewTerms.recount();
        } else {
            overviewRecount = true;
        }
    }

    private boolean standingShown() {
        return ANALYSES.equals(tabs.selected()) && wide;
    }

    /**
     * Der Unterreiter „Analyse“ des Technik-Reiters liest nur, solange er zu sehen ist; seine beiden Aktionen stehen
     * nur dann in der Reiterzeile.
     */
    private void showAdvanced() {
        boolean advancedShown = ADVANCED.equals(tabs.selected());
        boolean pageAnalysis = advancedShown && !wide && ANALYSIS.equals(advancedTabs.selected());
        boolean wideShown = advancedShown && wide && ANALYSIS.equals(wideAdvanced.selected());
        analysisView.proposeButton().setVisible(ANALYSIS.equals(advancedTabs.selected()));
        analysisView.createButton().setVisible(ANALYSIS.equals(advancedTabs.selected()));
        wideAnalysis.proposeButton().setVisible(ANALYSIS.equals(wideAdvanced.selected()));
        wideAnalysis.createButton().setVisible(ANALYSIS.equals(wideAdvanced.selected()));
        analysisView.shown(pageAnalysis);
        wideAnalysis.shown(wideShown);
    }

    /** Die ?-Hilfe zum gewählten Reiter von Analysen ohne Seite: Stand, Fachglossar oder Graph, sonst keine. */
    private void showOverviewHelp() {
        boolean shown = standingShown();
        String selected = standing.tabs().selected();
        standingHelp.setVisible(shown && standing.ownTabSelected());
        glossaryHelp.setVisible(shown && StandingTab.GLOSSARY.equals(selected));
        graphHelp.setVisible(shown && StandingTab.GRAPH.equals(selected));
    }

    private void loadOverviewTerms() {
        if (!overviewTermsDirty || overviewSelection == null || context.runtime() == null) {
            return;
        }
        overviewTermsDirty = false;
        overviewTerms.selection(new SelectionTerms(context.runtime(), overviewSelection));
    }

    /** Die Unterreiter des Technik-Reiters einer Seite. */
    PillTabs advancedTabs() {
        return advancedTabs;
    }

    /** Die Unterreiter des Technik-Reiters ohne Seite. */
    PillTabs wideAdvanced() {
        return wideAdvanced;
    }

    /** Der Unterreiter „Analyse“ der Seite und ohne Seite. */
    AnalysisView analysisView() {
        return analysisView;
    }

    AnalysisView wideAnalysis() {
        return wideAnalysis;
    }

    /** Die Begriffe der Auswahl im Reiter „Begriffe“ unter Analysen ohne Seite. */
    TermsTab overviewTerms() {
        return overviewTerms;
    }

    /** Fachglossar und Graph der Auswahl unter Analysen ohne Seite. */
    GlossaryTab overviewGlossary() {
        return overviewGlossary;
    }

    TermGraphView overviewGraph() {
        return overviewGraph;
    }


    /** Ob links mehr als eine Seite gewählt ist (Wissensquellen-Kopf, Quelle, Space, Ordner): dann Analysen = Stand. */
    private boolean wide = true;

    /**
     * Die Ebene der Auswahl links: {@code true} für Wissensquellen-Kopf, Quelle, Space oder Ordner, {@code false} für
     * eine Seite. „Analysen“ zeigt dann den Stand der Auswahl bzw. die Analysen der Seite.
     */
    void wide(boolean wide) {
        if (this.wide != wide) {
            this.wide = wide;
            showDeck();
            showTabStatus();
        }
    }

    /** Für Tests: ob „Analysen“ gerade den Stand der Auswahl zeigt. */
    boolean showsStanding() {
        return ANALYSES.equals(tabs.selected()) && wide;
    }

    /**
     * Zeigt eine Aussage aus „Stand“ im Lesetext: die Seite öffnen (der Explorer folgt), dann die Stelle markieren.
     * Ist die Seite schon offen, wird sofort markiert. Ein Ziel ohne Stelle (Anfang negativ) öffnet nur die Seite.
     */
    void openAt(StandingTab.Target target) {
        boolean somewhere = target.start() >= 0 && target.end() > target.start();
        if (shown != null && page != null && target.sourceId().equals(sourceId) && target.pageId().equals(pageId)) {
            if (somewhere) {
                jumpTo(List.of(new TermsTab.Span(target.start(), target.end())));
            }
            return;
        }
        // Ohne Stelle („Zuerst lesen“ öffnet nur die Seite) wird nach dem Laden nichts markiert.
        pendingJump = somewhere ? target : null;
        if (context.navigator() != null) {
            context.navigator().openPage(target.sourceId(), target.pageId());
        } else {
            load(target.sourceId(), target.pageId(), false);
        }
    }

    /** Der Reiter „Analysen › Graph“; die Auswahl links grenzt ihn ein (ADR 0029). */
    SourceUpdatesView sourceChanges() {
        return sourceChanges;
    }

    SourcePropertiesView sourceProperties() {
        return sourceProperties;
    }

    SourceUpdatesView sourceView() {
        return sourceView;
    }

    /** Ob gerade die Quellenseite (ADR 0030) gezeigt wird, für die ganze Quelle oder einen Space. */
    boolean showsSource() {
        return SOURCE_CARD.equals(pageCard);
    }

    /** Ob gerade die Updates genau dieser Ebene gezeigt werden; {@code spaceKey == null} die ganze Quelle. */
    boolean showsLevel(String sourceId, String spaceKey) {
        return showsSource() && java.util.Objects.equals(sourceView.sourceId(), sourceId)
                && java.util.Objects.equals(sourceView.spaceKey(), spaceKey);
    }

    /** Ob gerade genau diese Seite gezeigt wird oder lädt. */
    boolean showsPage(String sourceId, String pageId) {
        return sourceId != null && sourceId.equals(this.sourceId) && pageId != null && pageId.equals(this.pageId);
    }

    public KnowledgeGraphTab graph() {
        return graphView;
    }

    /** Zeigt den Reiter „Export“, etwa aus „Inhalte öffnen“ eines Auftrags. */
    public void showExport() {
        tabs.select(EXPORT);
    }

    /** Ob gerade keine Seite angezeigt oder geladen wird. */
    public boolean isEmpty() {
        return pageId == null;
    }

    /** Lädt die gezeigte Seite aus dem Bestand neu; ohne gezeigte Seite nichts. EDT. */
    void reloadShown() {
        if (sourceId != null && pageId != null) {
            load(sourceId, pageId, false);
        }
    }

    /**
     * Lädt eine Seite. Ohne {@code refresh} zuerst aus dem Bestand, ohne die Quelle zu fragen: Liegt sie dort, steht
     * sie sofort, und die Versionsprüfung bei der Quelle läuft danach im Hintergrund ({@link #checkSource}); fehlt sie
     * dort, holt der Core sie bei der Quelle, legt sie im Bestand ab und liefert sie aus. Mit {@code refresh} wird sie
     * live geprüft und, wenn sie neuer ist, live geladen; die Ansicht wartet dann (Angelo, 17.11.2026).
     */
    public void load(String sourceId, String pageId, boolean refresh) {
        // Eine geöffnete Seite: „Analysen“ zeigt ihre eigenen Analysen (der Explorer folgt mit derselben Ebene).
        wide(false);
        load(sourceId, pageId, refresh, null);
    }

    /**
     * Öffnet die Seite eines Absatzes über seine stabile Passage-ID (#452, wie ein {@code ReaderLink} mit Passage im
     * Enterprise AI Client): Seite laden, den Absatz unter „Absätze“ wählen und im Lesetext markieren; der Reiter bleibt.
     * Gehört die ID zu einer älteren Fassung oder gibt es den Absatz nicht mehr, öffnet die Seite trotzdem mit einem
     * Hinweis; eine unbekannte oder nicht sichtbare Seite meldet sich wie jeder andere Ladefehler.
     */
    public void openPassage(String passageId) {
        if (!PassageId.looksLike(passageId)) {
            showCard(PAGE);
            feedback.problem(new InvalidQueryException("Keine Passage-ID"), Problems.Step.PAGE);
            return;
        }
        PassageId id = PassageId.parse(passageId);
        load(id.sourceId(), id.pageId(), false, passageId);
    }

    /** Wie {@link #load(String, String, boolean)}; mit {@code passageId} löst der Core zuerst den Absatz auf. */
    private void load(String sourceId, String pageId, boolean refresh, String passageId) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || sourceId == null || pageId == null) {
            return;
        }
        remember(Place.page(sourceId, pageId));
        this.sourceId = sourceId;
        this.pageId = pageId;
        this.assetsLoaded = false;
        this.readingLoaded = false;
        this.referencesLoaded = false;
        this.imagesLoaded = false;
        this.page = null;
        closeOriginalFile();
        readingPane.loading();
        breadcrumbs.clear();
        positionShown.accept(null);
        references.message(" ");
        int current = ++request;
        update.dismiss();
        showCard(PAGE);
        showReader(null, false);
        statementsView.pending(new PageKey(sourceId, pageId));
        graphView.page(null);
        acronymHint.setText(" ");
        closeAttachmentOverlay();
        assetsModel.set(null);
        title.setText("Seite " + pageId);
        path.setText(sourceId);
        state.setText(" ");
        renderDetails();
        properties.reset();
        technical.reset();
        raw.setText("");
        storage.setText("");
        feedback.working(refresh ? "Seite wird mit der Quelle verglichen …" : "Seite wird geladen …");
        setActionsEnabled(false);
        Font font = reader.getFont();
        Background.run(() -> {
            // Zuerst die ID: Der Core zieht dabei fehlende Absätze nach, die der Lesetext danach schon kennt.
            PassageLookup passage = passageId == null ? null : runtime.workspace().passage(passageId);
            if (!refresh) {
                Optional<DeliveredPage> cached = runtime.workspace().cachedPage(sourceId, pageId);
                if (cached.isPresent()) {
                    return loaded(runtime, sourceId, pageId, cached.get(), font, false, passage, true);
                }
            }
            DeliveredPage delivered = runtime.workspace().page(sourceId, pageId, refresh);
            return loaded(runtime, sourceId, pageId, delivered, font, refresh, passage, false);
        }, loaded -> {
            if (current != request) {
                return;
            }
            show(loaded);
            loadVisible();
            loadPosition();
            if (loaded.passage() != null) {
                showPassage(loaded.passage());
            }
            StandingTab.Target jump = pendingJump;
            pendingJump = null;
            if (jump != null && jump.sourceId().equals(sourceId) && jump.pageId().equals(pageId)) {
                jumpTo(List.of(new TermsTab.Span(jump.start(), jump.end())));
            }
            if (loaded.unchecked()) {
                checkSource(runtime, sourceId, pageId, loaded.page(), font, current);
            }
        }, error -> {
            if (current != request) {
                return;
            }
            closeOriginalFile();
            readingPane.clear(" ");
            feedback.problem(error, Problems.Step.PAGE);
            statementsView.unavailable();
            page = null;
            acronymHint.setText(" ");
        });
    }

    /** Alles, was {@link #show} zu einer ausgelieferten Seite braucht; außerhalb des EDT. */
    private Loaded loaded(AppRuntime runtime, String source, String id, DeliveredPage delivered, Font font,
                          boolean checked, PassageLookup passage, boolean unchecked) {
        // Avoid preparing an AI text document before showing a native image.
        if (isNativePreview(delivered.snapshot())) {
            return new Loaded(delivered, List.of(), null, Payloads.display(delivered.snapshot().payload()),
                    null, checked, passage, unchecked);
        }
        List<Enrichments.View> enrichments = runtime.workspace().enrichments(delivered);
        PageText text = runtime.workspace().pageText(delivered.snapshot());
        ReaderDocument document = document(source, id, enrichments, text, font);
        relations(runtime, document, source, delivered.header().metadata().partitionKey());
        navigation(runtime, document, source, id);
        return new Loaded(delivered, enrichments, document,
                Payloads.display(delivered.snapshot().payload()),
                Payloads.storage(delivered.snapshot().payload()).orElse(null), checked, passage, unchecked);
    }

    /**
     * Prüft die sofort aus dem Bestand gezeigte Seite im Hintergrund bei der Quelle ({@code page(…, refresh=false)},
     * dieselbe Prüfung wie bisher vor dem Anzeigen). Hat die Quelle eine andere Fassung, bleibt der gezeigte Stand
     * stehen und oben erscheint „Es gibt N Updates seit Ihrem letzten Besuch – Seite neu laden“; der Klick zeigt die
     * schon geladene neue Fassung. Sonst wird nur die Zustandszeile still nachgeführt; ein Fehler der Prüfung ändert
     * nichts, der Bestand bleibt lesbar.
     */
    private void checkSource(AppRuntime runtime, String source, String id, DeliveredPage shown, Font font,
                             int current) {
        String shownRevision = shown.snapshot().revision();
        String shownVersion = shown.header().metadata().version();
        Background.run(() -> {
            DeliveredPage checked = runtime.workspace().page(source, id, false);
            boolean newer = checked.live() || !java.util.Objects.equals(checked.snapshot().revision(), shownRevision);
            if (!newer) {
                return new Checked(checked, null);
            }
            return new Checked(checked, loaded(runtime, source, id, checked, font, false, null, false));
        }, checked -> {
            if (current != request || shown != page) {
                return;
            }
            if (checked.newer() == null) {
                state.setText(origin(checked.page()) + checkedNote(checked.page()));
                renderDetails();
                return;
            }
            Loaded newer = checked.newer();
            update.show(ReadingPane.updateSentence(shownVersion, newer.page().header().metadata().version()),
                    ReadingPane.RELOAD, () -> {
                        if (current != request || shown != page) {
                            return;
                        }
                        assetsLoaded = false;
                        readingLoaded = false;
                        referencesLoaded = false;
                        imagesLoaded = false;
                        closeAttachmentOverlay();
                        assetsModel.set(null);
                        show(newer);
                        loadVisible();
                        loadPosition();
                    });
        }, error -> {
            // Der Bestand steht; die Prüfung bei der Quelle ist still gescheitert (etwa nicht erreichbar).
        });
    }

    /** Zusatz der Zustandszeile nach der Hintergrundprüfung: was die Quelle zur gezeigten Fassung sagt. */
    private static String checkedNote(DeliveredPage checked) {
        if (checked.refresh() == null) {
            return "";
        }
        return switch (checked.refresh()) {
            case CURRENT -> " · mit der Quelle verglichen: aktuell";
            case HEADER_CHANGED -> " · Kopfdaten in der Quelle geändert";
            case GONE -> " · in der Quelle nicht mehr sichtbar";
            default -> "";
        };
    }

    /** Ergebnis der Hintergrundprüfung: die geprüfte Seite und, bei anderer Fassung, der fertig gebaute neue Stand. */
    private record Checked(DeliveredPage page, Loaded newer) {
    }

    /** Für Tests: der Satz des Hinweises auf einen neueren Stand der Seite, leer ohne Hinweis. */
    String updateOffer() {
        return update.sentence();
    }

    /** Für Tests: ein Klick auf „Seite neu laden“ im Hinweis. */
    void clickUpdate() {
        update.clickAction();
    }

    /** Meldet den Pfad der angezeigten Seite, sobald er geladen ist; ohne Seitenbaum der Quelle nie. */
    public void onPosition(java.util.function.Consumer<com.aresstack.spaceharvester.application.navigation.PageNavigation.Position> listener) {
        this.positionShown = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Meldet nach dem Laden Quelle und Space der angezeigten Seite. */
    public void onShown(java.util.function.BiConsumer<String, String> listener) {
        this.pageShown = listener;
    }

    /** So lange bleibt eine Erfolgs- oder Infomeldung in der Statuszeile stehen. */
    static final int STATUS_MS = 8000;

    /**
     * Meldet den Stand des sichtbaren Reiters (etwa „50 Knoten, 51 Kanten“ oder „verweist auf 3 Seiten“) für die
     * Statuszeile des Hauptfensters; " " ohne.
     */
    public void onStatus(java.util.function.Consumer<String> listener) {
        this.statusShown = java.util.Objects.requireNonNull(listener, "listener");
        showTabStatus();
    }

    private void tabStatus(String tab, String text) {
        tabStatus.put(tab, text == null ? " " : text);
        showTabStatus();
    }

    private void showTabStatus() {
        statusShown.accept(tabStatus.getOrDefault(selectedTab(), " "));
    }

    private void show(Loaded loaded) {
        page = loaded.page();
        views = loaded.enrichments();
        PageSnapshot snapshot = page.snapshot();
        PageMetadata metadata = page.header().metadata();
        statementsView.page(snapshot, loaded.reader() == null ? null : loaded.reader().text());
        graphView.page(snapshot.key(), snapshot.spaceKey());
        pageShown.accept(sourceId, metadata.partitionKey());
        title.setText(metadata.title());
        path.setText(sourceId + " · Space " + metadata.partitionKey() + " · Seite " + metadata.pageId()
                + (metadata.version() == null ? "" : " · Version " + metadata.version()));
        state.setText(origin(page));
        renderDetails();
        state.setForeground(Comic.TEXT);
        feedback.clear();
        if (page.refresh() != null && loaded.checked()) {
            switch (page.refresh()) {
                case CURRENT -> feedback.success("Aktuell: Bestand und Quelle stimmen überein");
                case HEADER_CHANGED -> feedback.info("Kopfdaten geändert",
                        "Inhalt unverändert, Titel/Labels/Eltern sind live; der Bestand folgt beim Abgleich.");
                case CONTENT_CHANGED -> feedback.warning("Neuere Fassung live geladen",
                        "Die Quelle hat eine neue Revision oder die Seite fehlt im Bestand. Angezeigt wird die "
                                + "Live-Fassung; der Bestand bleibt bis zum nächsten Abgleich.");
                case GONE -> feedback.warning("In der Quelle nicht mehr sichtbar",
                        "Angezeigt wird der letzte Stand aus dem Bestand.");
            }
        }

        showReader(loaded.reader(), page.live());
        showOriginalContent(metadata, snapshot.payload());
        raw.setText(loaded.display());
        raw.setCaretPosition(0);
        storage.setText(loaded.storage() == null ? "Diese Payload hat kein body.storage." : loaded.storage());
        storage.setCaretPosition(0);

        properties.reset();
        properties.add("Seiten-ID", metadata.pageId())
                .add("Titel", metadata.title())
                .add("Space", metadata.partitionKey())
                .add("Elternseite", metadata.parentPageId())
                .add("Version", metadata.version())
                .add("Geändert", Texts.time(metadata.modifiedAt()))
                .add("Adresse", metadata.url() == null ? null : metadata.url().toString())
                .add("Ressource", metadata.resourceId());
        metadata.attributes().forEach((key, value) -> properties.add(key, value));

        technical.reset();
        NativePayload payload = snapshot.payload();
        technical.add("Herkunft", page.live() ? "live aus der Quelle" : "Bestand (Cache)")
                .add("Prüfung", page.refresh() == null ? "keine" : page.refresh().name().toLowerCase()
                        + ", " + Texts.time(page.checkedAt()))
                .add("Cache-Generation", page.generation() == null ? null : page.generation().id())
                .add("Cache-Stand", Texts.generation(page.generation()))
                .add("Revision", snapshot.revision())
                .add("Medientyp", payload.mediaType())
                .add("Natives Format", payload.nativeFormat())
                .add("Größe", Texts.bytes(payload.length()))
                .add("Raw-SHA-256", payload.rawSha256())
                .add("Metadaten-Hash", snapshot.metadataHash())
                .add("Attribut-Hash", snapshot.attributesHash())
                .add("Abgerufen", Texts.time(snapshot.retrievedAt()))
                .add("Beobachtet", Texts.time(page.header().observedAt()));
        for (SnapshotWarning warning : snapshot.warnings()) {
            technical.add("Warnung", warning.code() + (warning.detail() == null ? "" : ": " + warning.detail()),
                    Comic.ORANGE);
        }

        analysisView.steps(views, this::stepResult);
        analysisView.scope(pageScope(snapshot.key()));
        setActionsEnabled(true);
        browse.setEnabled(metadata.url() != null && Desktop.isDesktopSupported());
    }

    /**
     * Show the unmodified source file in the original-view tab.
     * Keep extracted text and its analyses in the separate text and analysis tabs.
     */
    void showOriginalContent(PageMetadata metadata, NativePayload payload) {
        closeOriginalFile();
        if (PageMetadata.LINK_KIND.equals(metadata.attributes().get(PageMetadata.RESOURCE_KIND))) {
            readingPane.clear(" ");
            showOriginalLink(payload);
            return;
        }
        if (!PageMetadata.FILE_KIND.equals(metadata.attributes().get(PageMetadata.RESOURCE_KIND))) {
            return;
        }
        readingPane.clear(" ");
        ReaderAttachment attachment = new ReaderAttachment(metadata.pageId(), metadata.title(),
                originalMediaType(payload), payload.length());
        ReaderAttachmentLoader loader = (file, done, failed) ->
                Background.run(payload::bytes, done, error -> failed.accept(reason(error)));
        originalFileViewer = new AttachmentViewerPanel(attachment, loader, fileOpener, attachmentRenderer);
        fileContent.add(originalFileViewer, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
    }

    /**
     * Present a redirect target as a source-neutral link document.
     * Open the target only after an explicit user action; never reuse source credentials.
     */
    private void showOriginalLink(NativePayload payload) {
        String destination = new String(payload.bytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
        JPanel link = new JPanel(new BorderLayout(0, 12));
        link.setOpaque(false);
        JLabel explanation = new JLabel("Diese Ressource ist ein Verweis auf eine andere Adresse.");
        link.add(explanation, BorderLayout.NORTH);
        javax.swing.JTextArea address = new javax.swing.JTextArea(destination);
        address.setEditable(false);
        address.setLineWrap(true);
        address.setWrapStyleWord(true);
        link.add(address, BorderLayout.CENTER);
        javax.swing.JButton open = new javax.swing.JButton("Verweis im Browser öffnen");
        try {
            java.net.URI target = java.net.URI.create(destination);
            boolean supported = ("http".equalsIgnoreCase(target.getScheme())
                    || "https".equalsIgnoreCase(target.getScheme()))
                    && target.getHost() != null && target.getRawUserInfo() == null;
            open.setEnabled(supported);
            if (supported) {
                open.addActionListener(event -> {
                    try {
                        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                            Desktop.getDesktop().browse(target);
                        } else {
                            feedback.warning("Browser nicht verfügbar", "Den Verweis aus der Anzeige kopieren.");
                        }
                    } catch (IOException | RuntimeException failed) {
                        feedback.warning("Verweis konnte nicht geöffnet werden",
                                "Den Verweis aus der Anzeige kopieren.");
                    }
                });
            }
        } catch (IllegalArgumentException invalid) {
            open.setEnabled(false);
        }
        link.add(open, BorderLayout.SOUTH);
        fileContent.add(link, BorderLayout.CENTER);
        originalLinkShown = true;
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
    }

    /** Identify original images before running any optional text-analysis preparation. */
    private static boolean isNativePreview(PageSnapshot snapshot) {
        String kind = snapshot.metadata().attributes().get(PageMetadata.RESOURCE_KIND);
        return PageMetadata.LINK_KIND.equals(kind)
                || PageMetadata.FILE_KIND.equals(kind)
                && originalMediaType(snapshot.payload()).startsWith("image/");
    }

    /** Trust the actual file signature when WebDAV metadata or the name is misleading. */
    private static String originalMediaType(NativePayload payload) {
        if (payload.startsWith(new byte[] {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a})) {
            return "image/png";
        }
        if (payload.startsWith(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff})) {
            return "image/jpeg";
        }
        if (payload.startsWith(new byte[] {'G', 'I', 'F', '8'})) {
            return "image/gif";
        }
        if (payload.startsWith(new byte[] {'%', 'P', 'D', 'F', '-'})) {
            return "application/pdf";
        }
        return payload.mediaType();
    }

    /** Return the original-document viewer for UI regression tests. */
    AttachmentViewerPanel originalViewer() {
        return originalFileViewer;
    }

    /** Return whether a source-neutral external-link document is shown. */
    boolean originalLinkShown() {
        return originalLinkShown;
    }

    /** Expose the pending page indicator for navigation regression tests. */
    boolean originalLoadingShown() {
        return readingPane.loadingShown();
    }

    /** Release the previous document viewer before a page switch or source switch. */
    private void closeOriginalFile() {
        if (originalFileViewer != null) {
            originalFileViewer.close();
            originalFileViewer = null;
        }
        originalLinkShown = false;
        fileContent.removeAll();
        originalCards.show(originalContent, ORIGINAL_PAGE_CARD);
    }

    /** Zeigt einen fertig gebauten Lesetext; {@code null} leert ihn samt Markierungen. */
    private void showReader(ReaderDocument document, boolean live) {
        // Neu berechnet bei gleichem Text: die Navigation gilt weiter; sonst gehörte sie zum vorigen Text.
        boolean sameText = shown != null && document != null && shown.text() != null && document.text() != null
                && shown.text().text().equals(document.text().text());
        if (document != null && document.navigation() != null) {
            showNavigation(document.navigation());
        } else if (!sameText) {
            showNavigation(null);
        }
        shown = document;
        terms.show(document == null ? null : document.acronyms(), document == null ? null : document.terms());
        passages.show(document == null ? null : document.passages());
        terms.showRelations(document == null ? null : document.relations());
        acronymDetail.setText(" ");
        if (document == null) {
            reader.setText("");
            recomputeReader.setVisible(false);
            return;
        }
        reader.setStyledDocument(document.document());
        reader.setCaretPosition(0);
        readerPage.contentChanged();
        // Die Wolken der Leseansicht zählen wiederholte Überschriften im jetzt gezeigten Klartext.
        readingPane.setNavigation(readerPage.sky().navigation(), reader.getText());
        acronymHint.setText(document.hint(live));
        recomputeReader.setVisible(recomputeOffered(document, live));
    }

    /**
     * Ob „Analyse neu berechnen“ unter Text erscheint: wenn Akronyme oder Absätze nicht zum angezeigten Text passen,
     * denn auf diesen Knopf verweisen beide Hinweise.
     */
    private static boolean recomputeOffered(ReaderDocument document, boolean live) {
        return document.text() != null && !live && recomputeOffered(document.acronyms().state(),
                document.passages() == null ? null : document.passages().state());
    }

    static boolean recomputeOffered(PageAcronyms.State acronyms, PagePassages.State passages) {
        return acronyms != PageAcronyms.State.CURRENT
                || passages == PagePassages.State.STALE || passages == PagePassages.State.FAILED;
    }

    /** Baut den Lesetext mit den Akronymen der Seite {@code sourceId}/{@code pageId}; außerhalb des EDT. */
    private static ReaderDocument document(String sourceId, String pageId, List<Enrichments.View> views, PageText text,
                                           Font font) {
        PageKey key = new PageKey(sourceId, pageId);
        return ReaderDocument.build(text, PageAcronyms.read(key, views, text), PageTerms.read(key, views, text), font)
                .withPassages(PagePassages.read(key, views, text));
    }

    /** Hängt die Beziehungen der Begriffe dieser Seite an; ohne Begriffe oder Rechte bleibt es bei „nicht abgefragt“. */
    private static void relations(AppRuntime runtime, ReaderDocument document, String sourceId, String spaceKey) {
        java.util.Set<String> normalized = document.normalizedTerms();
        if (normalized.isEmpty()) {
            return;
        }
        try {
            document.withRelations(runtime.workspace().termRelationsFor(normalized, sourceId, spaceKey).stream()
                    .map(com.aresstack.spaceharvester.app.ui.Relation::of).toList());
        } catch (RuntimeException e) {
            // Beziehungen sind eine Zugabe; ohne sie zeigt der Reiter „Begriffe“ den Rest unverändert.
            LOG.log(System.Logger.Level.DEBUG, "Beziehungen nicht lesbar: {0}", e.getMessage());
        }
    }

    /**
     * Hängt die Dokumentnavigation des Core an, wenn sie zum gezeigten Text gehört ({@code inputSha256}); sonst bleibt
     * der Banner aus. Die Positionen des Core beziehen sich auf den Text des Cache, den der Reiter „Text“ zeigt.
     */
    private static void navigation(AppRuntime runtime, ReaderDocument document, String sourceId, String pageId) {
        if (document.text() == null) {
            return;
        }
        try {
            var view = runtime.workspace().documentNavigation(sourceId, pageId);
            // Wie die Capture-ID im Client: nur eine Antwort zu genau dem gezeigten Text gilt.
            if (document.text().inputSha256().equals(view.inputSha256())) {
                document.withNavigation(Navigation.of(view));
            }
        } catch (RuntimeException e) {
            // Die Navigation ist eine Zugabe; ohne sie zeigt der Reiter „Text“ den Lesetext allein.
            LOG.log(System.Logger.Level.DEBUG, "Dokumentnavigation nicht lesbar: {0}", e.getClass().getSimpleName());
        }
    }

    private String acronymTooltip(Point point) {
        ReaderDocument document = shown;
        if (document == null) {
            return null;
        }
        int offset = reader.viewToModel2D(point);
        PageAcronyms.Mark mark = offset < 0 ? null : document.markAt(offset);
        return mark == null ? null : ReaderDocument.tooltip(mark.acronym());
    }

    private void showAcronymAt(int offset) {
        ReaderDocument document = shown;
        PageAcronyms.Mark mark = document == null ? null : document.markAt(offset);
        acronymDetail.setText(mark == null ? " " : ReaderDocument.detail(mark.acronym()));
    }

    private void bind(String key, String name, boolean forward) {
        reader.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        reader.getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                jumpToAcronym(forward);
            }
        });
    }

    /**
     * Springt aus dem Reiter „Begriffe“ zur nächsten Fundstelle dieses Akronyms im Lesetext, nach der letzten wieder
     * zur ersten.
     */
    void jumpTo(PageAcronyms.Acronym acronym) {
        jumpTo(acronym.occurrences().stream()
                .map(occurrence -> new TermsTab.Span(occurrence.start(), occurrence.end())).toList());
    }

    /** Springt zur ersten dieser Fundstellen ab der Auswahl, nach der letzten wieder zur ersten. */
    void jumpTo(List<TermsTab.Span> spans) {
        ReaderDocument document = shown;
        if (document == null || spans.isEmpty()) {
            return;
        }
        int caret = Math.max(reader.getCaret().getDot(), reader.getCaret().getMark());
        TermsTab.Span target = spans.stream().filter(span -> span.start() >= caret).findFirst()
                .orElse(spans.get(0));
        if (target.end() > reader.getDocument().getLength()) {
            return;
        }
        tabs.select(READ);
        reader.requestFocusInWindow();
        mark(target);
    }

    /** Lädt nach, was der gerade sichtbare Reiter braucht (Haupt- oder Unterreiter unter „Analysen“). */
    private void loadVisible() {
        String visible = selectedTab();
        if (ATTACHMENTS.equals(visible)) {
            loadAssets(false);
        }
        if (READING.equals(visible) && originalFileViewer == null && !originalLinkShown) {
            loadReading();
        }
        if (REFERENCES.equals(visible)) {
            loadReferences();
        }
        if (IMAGES.equals(visible)) {
            loadImages();
        }
    }

    /**
     * Setzt den Inhalt des Unterreiters „Analysen → Aussagen“ (#499) und ersetzt damit den Platzhalter;
     * {@code null} stellt den Platzhalter wieder her. Der gewählte Reiter bleibt.
     */
    public void statementsTab(JComponent component) {
        replace(statements, component, STATEMENTS_PLACEHOLDER);
    }

    /**
     * Setzt den Inhalt des Unterreiters „Analysen → Graph“ (Wissensgraph, #499 F) und ersetzt damit den Platzhalter;
     * {@code null} stellt den Platzhalter wieder her. Der gewählte Reiter bleibt.
     */
    public void knowledgeGraphTab(JComponent component) {
        replace(graph, component, GRAPH_PLACEHOLDER);
    }

    private static void replace(JPanel slot, JComponent component, String placeholder) {
        slot.removeAll();
        slot.add(component == null ? placeholder(placeholder) : component, BorderLayout.CENTER);
        slot.revalidate();
        slot.repaint();
    }

    private static JComponent placeholder(String text) {
        JLabel hint = Comic.muted(text);
        hint.setHorizontalAlignment(JLabel.CENTER);
        return hint;
    }

    /** Markiert die Stelle im Lesetext, auch wenn gerade ein anderer Reiter vorne liegt. */
    private void mark(TermsTab.Span target) {
        reader.select(target.start(), target.end());
        reader.getCaret().setSelectionVisible(true);
        readerPage.revealBelowSky(target.start());
        showAcronymAt(target.start());
    }

    static final String PASSAGE_OLDER = "Der Absatz gehört zu einer älteren Fassung der Seite";
    static final String PASSAGE_CHANGED = "Den Absatz gibt es nicht mehr";
    static final String PASSAGE_MISSING = "Der Absatz steht nicht im Reiter „Absätze“";
    /** Seit #470 folgt die Passage-ID der ganzen Prozessorversion (Zerlegung, Modell, Format, Dienst). */
    static final String PASSAGE_RECOMPUTED = "Die Absätze wurden seither neu berechnet. Angezeigt werden alle Absätze.";

    /**
     * Zeigt den über seine Passage-ID geöffneten Absatz: im Reiter „Absätze“ gewählt und im Lesetext markiert, ohne den
     * Reiter zu wechseln. Die Hinweise folgen den Fällen von {@code KnowledgeResourceReader} im Client.
     */
    void showPassage(PassageLookup lookup) {
        // Der zuletzt gewählte Reiter bleibt vorne: „Text“ zeigt den markierten Absatz, „Absätze“ den gewählten.
        switch (lookup.match()) {
            case FOUND -> {
                PassagesTab.Passage passage = passages.select(lookup.requested().value());
                if (passage == null) {
                    // Der Reiter zeigt ein anderes Ergebnis als die Auflösung: lieber sagen als schweigen.
                    feedback.info(PASSAGE_MISSING, PASSAGE_RECOMPUTED);
                } else if (shown != null && passage.end() <= reader.getDocument().getLength()) {
                    mark(new TermsTab.Span(passage.start(), passage.end()));
                }
            }
            case OLDER_VERSION -> feedback.info(PASSAGE_OLDER, "Sein Text ist nicht mehr vorhanden (Seite seither "
                    + "geändert). Angezeigt werden die aktuellen Absätze.");
            case CHANGED -> feedback.info(PASSAGE_CHANGED, PASSAGE_RECOMPUTED);
            case UNAVAILABLE -> {
                // Der Reiter „Absätze“ nennt selbst, warum gerade keine vorliegen.
            }
        }
    }

    /** Markiert die nächste bzw. vorige Fundstelle und zeigt ihre Ausschreibung. */
    void jumpToAcronym(boolean forward) {
        ReaderDocument document = shown;
        if (document == null) {
            return;
        }
        int caret = Math.min(reader.getCaret().getDot(), reader.getCaret().getMark());
        PageAcronyms.Mark mark = forward ? document.next(caret) : document.previous(caret);
        if (mark != null) {
            reader.select(mark.occurrence().start(), mark.occurrence().end());
            readerPage.revealBelowSky(mark.occurrence().start());
            showAcronymAt(mark.occurrence().start());
        }
    }

    private static String origin(DeliveredPage page) {
        if (page.live()) {
            return "Live aus der Quelle";
        }
        return "Aus dem lokalen Bestand · " + Texts.generation(page.generation());
    }

    private void loadAssets(boolean force) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || (assetsLoaded && !force)) {
            return;
        }
        assetsLoaded = true;
        assetsHint.setText("Anhänge werden live geladen …");
        String source = sourceId;
        String id = pageId;
        // Eigene Gültigkeit statt request: Eine Neuberechnung der Analysen verwirft die Anhangsliste nicht.
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().assets(source, id), overview -> {
            if (shown != page) {
                return;
            }
            assetsModel.set(overview);
            assetsHint.setText(Texts.count(overview.entries().size(), "Anhang", "Anhänge")
                    + (overview.listComplete() ? "" : " (Liste unvollständig)") + " · live, "
                    + Texts.time(overview.retrievedAt()));
        }, error -> {
            if (shown != page) {
                return;
            }
            assetsLoaded = false;
            assetsHint.setText("Anhänge nicht geladen: " + com.aresstack.spaceharvester.app.ui.Problems
                    .describe(error).title());
        });
    }

    /**
     * Ein Bild der Leseansicht: die Vorschau eines draw.io-Diagramms als Grafik des Core (wie Mermaid), alle übrigen
     * und jede Vorschau, die sich nicht zeichnen lässt, wie die Quelle sie zeigt. Außerhalb des EDT.
     */
    public static byte[] image(AppRuntime runtime, com.aresstack.spaceharvester.application.reading.ReadingView view,
                        com.aresstack.spaceharvester.application.sources.HarvestSource.ViewImage image) {
        try {
            var drawn = runtime.workspace().viewDiagram(view, image);
            if (drawn.isPresent()) {
                return drawn.get().content();
            }
        } catch (RuntimeException e) {
            // Quelldaten nicht abrufbar: dann bleibt das Vorschaubild der Quelle.
        }
        return runtime.workspace().viewImage(view, image).content();
    }

    /**
     * Lädt die Leseansicht (#192) über den Core: HTML aus dem Bestand oder live, Bilder aus dem AssetStore bzw. einmalig
     * abgelegt. Wie die Anhänge gilt das Ergebnis nur für die Seite, für die es angefordert wurde.
     *
     * <p>Liegt ein Stand im Cache, erscheint er sofort ({@code Workspace.cachedReadingView}); die Prüfung bei der Quelle
     * läuft danach im Hintergrund. Hat sie eine andere Version, bleibt der gezeigte Stand stehen und oben steht „Es gibt
     * N Updates seit Ihrem letzten Besuch – Seite neu laden“; der Klick zeigt den schon geladenen Stand sofort (Angelo,
     * 17.11.2026). Ohne Cache-Stand wartet die Ansicht wie bisher.
     */
    private void loadReading() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || readingLoaded || originalFileViewer != null || originalLinkShown) {
            return;
        }
        readingLoaded = true;
        readingPane.loading();
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().cachedReadingView(source, id)
                .map(view -> new ShownView(view, prepare(runtime, view))), cached -> {
            if (shown != page) {
                return;
            }
            if (cached.isEmpty()) {
                loadLiveReading(runtime, source, id, shown, null);
                return;
            }
            showReading(cached.get().prepared());
            loadLiveReading(runtime, source, id, shown, cached.get());
        }, error -> {
            if (shown == page) {
                loadLiveReading(runtime, source, id, shown, null);
            }
        });
    }

    /** Eine Leseansicht und ihre vorbereitete Darstellung. */
    private record ShownView(com.aresstack.spaceharvester.application.reading.ReadingView view,
                             ReadingPane.Prepared prepared) {
    }

    private static ReadingPane.Prepared prepare(AppRuntime runtime,
                                                com.aresstack.spaceharvester.application.reading.ReadingView view) {
        // Ohne Bilder: Das HTML steht sofort, die Bilder holt die Ansicht nach dem Anzeigen nach (Angelo, 17.11.2026).
        return ReadingPane.prepareDeferred(view, image -> runtime.workspace().viewDiagramVector(view, image),
                image -> image(runtime, view, image),
                diagram -> runtime.workspace().mermaid(view, diagram).orElse(null), ReadingPane.screenScale());
    }

    /**
     * Holt die Leseansicht bei der Quelle ({@code refresh=false}). Ohne gezeigten Stand ({@code cached} null) erscheint
     * sie; sonst nur der Hinweis auf den neueren Stand, wenn die Version abweicht. Der neue Stand trägt dann die
     * Änderungen gegenüber dem gezeigten farbig markiert ({@link ReadingChanges}, Angelo 17.11.2026).
     */
    private void loadLiveReading(AppRuntime runtime, String source, String id, DeliveredPage shown,
                                 ShownView cached) {
        boolean waiting = cached == null;
        String cachedVersion = waiting ? null : cached.view().version();
        Background.run(() -> {
            var view = runtime.workspace().readingView(source, id, false);
            if (!waiting && java.util.Objects.equals(view.version(), cachedVersion)) {
                return java.util.Optional.<ShownView>empty();
            }
            ReadingPane.Prepared prepared = prepare(runtime, view);
            if (!waiting) {
                prepared = prepared.withHtml(ReadingChanges.mark(cached.prepared().html(), prepared.html()).html());
            }
            return java.util.Optional.of(new ShownView(view, prepared));
        }, fresh -> {
            if (shown != page || fresh.isEmpty()) {
                return;
            }
            if (waiting) {
                showReading(fresh.get().prepared());
                return;
            }
            ReadingPane.Prepared newer = fresh.get().prepared();
            readingPane.offerUpdate(ReadingPane.updateSentence(cachedVersion, fresh.get().view().version()), () -> {
                if (shown == page) {
                    readingPane.showKeepingPosition(newer);
                    renderDetails();
                }
            });
        }, error -> {
            if (shown != page || !waiting) {
                // Mit gezeigtem Stand aus dem Cache bleibt der stehen; die Prüfung ist dann still gescheitert.
                return;
            }
            readingLoaded = false;
            readingPane.clear("Leseansicht nicht geladen: " + Problems.describe(error).title());
        });
    }

    private void showReading(ReadingPane.Prepared prepared) {
        readingPane.show(prepared);
        renderDetails();
        String anchor = pendingAnchor;
        pendingAnchor = null;
        if (anchor != null) {
            javax.swing.SwingUtilities.invokeLater(() -> readingPane.scrollToAnchor(anchor));
        }
    }

    // ------------------------------------------------------------------ Links, Zurück/Vor

    /**
     * Ein Link der Leseansicht (wie {@code KnowledgeReadingController.pageLinkActivated} im EAC): Zeigt er auf denselben
     * Server wie die Seite und löst die Quelle ihn als eigene Seite auf, öffnet diese hier (ein Anker rollt danach
     * dorthin); alles andere, auch ein Fehler beim Auflösen, öffnet im Browser.
     */
    void linkActivated(java.net.URL url) {
        AppRuntime runtime = context.runtime();
        java.net.URI known = page == null ? null : page.header().metadata().url();
        String source = sourceId;
        if (runtime == null || source == null || !sameDomain(url, known)) {
            ReadingPane.browse(url);
            return;
        }
        String address = url.toString();
        String anchor = url.getRef();
        int current = request;
        int click = ++linkRequest;
        Background.run(() -> runtime.workspace().pageByUrl(source, address, false), found -> {
            if (current != request || click != linkRequest) {
                return; // inzwischen eine andere Seite gewählt oder ein anderer Link geklickt
            }
            String target = found.header().metadata().pageId();
            if (target.equals(pageId)) {
                readingPane.scrollToAnchor(anchor);
            } else {
                load(source, target, false);
                pendingAnchor = anchor;
            }
        }, error -> {
            if (click == linkRequest) {
                ReadingPane.browse(url);
            }
        });
    }

    /** Ob {@code url} auf dieselbe Domain zeigt wie die bekannte Adresse der Seite (ohne „www.“, Groß/klein egal). */
    static boolean sameDomain(java.net.URL url, java.net.URI known) {
        if (url == null || known == null || known.getHost() == null || url.getHost() == null) {
            return false;
        }
        return host(url.getHost()).equals(host(known.getHost()));
    }

    private static String host(String host) {
        String lower = host.toLowerCase(java.util.Locale.ROOT);
        return lower.startsWith("www.") ? lower.substring(4) : lower;
    }

    /**
     * Ein Ort im Verlauf: eine Seite ({@code pageId} gesetzt) oder die Quellenseite einer Quelle ({@code spaceKey}
     * {@code null}) bzw. eines Space, mit dem Titel, den die Quellenseite trug.
     */
    public record Place(String sourceId, String spaceKey, String pageId, String title) {

        static Place page(String sourceId, String pageId) {
            return new Place(sourceId, null, pageId, null);
        }

        static Place level(String sourceId, String spaceKey, String title) {
            return new Place(sourceId, spaceKey, null, title);
        }

        public boolean page() {
            return pageId != null;
        }

        /** Derselbe Ort, der Titel zählt nicht. */
        boolean sameAs(Place other) {
            return other != null && sourceId.equals(other.sourceId) && java.util.Objects.equals(spaceKey, other.spaceKey)
                    && java.util.Objects.equals(pageId, other.pageId);
        }
    }

    /** Was die Mitte gerade zeigt: die Seite, die Quellenseite oder nichts. */
    private Place current() {
        if (sourceId != null && pageId != null) {
            return Place.page(sourceId, pageId);
        }
        if (showsSource() && sourceView.sourceId() != null) {
            return Place.level(sourceView.sourceId(), sourceView.spaceKey(), title.getText());
        }
        return null;
    }

    /**
     * Merkt das Gezeigte im Verlauf, bevor {@code next} an seine Stelle tritt ({@code null}: die Mitte wird leer);
     * Zurück/Vor selbst merken nichts, und derselbe Ort noch einmal auch nicht.
     */
    private void remember(Place next) {
        pendingAnchor = null;
        if (navigating) {
            return;
        }
        Place now = current();
        if (next != null && next.sameAs(now)) {
            return;
        }
        if (now != null) {
            backStack.push(now);
        }
        // Ein neu geöffneter Ort verwirft den Vor-Verlauf, auch wenn gerade nichts gezeigt wird.
        forwardStack.clear();
        updateNavigation();
    }

    /** Zurück zum zuvor Gezeigten; das Aktuelle kommt in den Vor-Verlauf. */
    public boolean back() {
        return step(backStack, forwardStack);
    }

    /** Vor zum zuletzt per Zurück Verlassenen. */
    public boolean forward() {
        return step(forwardStack, backStack);
    }

    public boolean canGoBack() {
        return !backStack.isEmpty();
    }

    public boolean canGoForward() {
        return !forwardStack.isEmpty();
    }

    /** Erfährt jeden Schritt per Zurück/Vor (die Bereichsanzeige oben, der Baum links). */
    public void onNavigated(java.util.function.Consumer<Place> listener) {
        this.navigated = java.util.Objects.requireNonNull(listener, "listener");
    }

    private boolean step(java.util.Deque<Place> from, java.util.Deque<Place> to) {
        if (from.isEmpty() || context.runtime() == null) {
            return false;
        }
        Place next = from.pop();
        Place now = current();
        if (now != null) {
            to.push(now);
        }
        navigating = true;
        try {
            if (next.page()) {
                load(next.sourceId(), next.pageId(), false);
            } else {
                showSource(next.sourceId(), next.spaceKey(), next.title());
            }
        } finally {
            navigating = false;
        }
        updateNavigation();
        navigated.accept(next);
        return true;
    }

    private void updateNavigation() {
        backButton.setEnabled(canGoBack());
        forwardButton.setEnabled(canGoForward());
    }

    /**
     * Die Maustasten Zurück (4) und Vor (5) irgendwo im Fenster der Seitenansicht, auch über dem Baum (Angelo,
     * 17.11.2026); im EAC nur im Lesebereich.
     *
     * @return ob die Taste behandelt wurde
     */
    boolean mouseButton(int button, java.awt.Component source) {
        if (source == null || button != MOUSE_BACK_BUTTON && button != MOUSE_FORWARD_BUTTON) {
            return false;
        }
        java.awt.Window window = javax.swing.SwingUtilities.getWindowAncestor(this);
        if (!javax.swing.SwingUtilities.isDescendingFrom(source, this)
                && (window == null || javax.swing.SwingUtilities.getWindowAncestor(source) != window)) {
            return false;
        }
        if (button == MOUSE_BACK_BUTTON) {
            back();
        } else {
            forward();
        }
        return true;
    }

    @Override
    public void addNotify() {
        super.addNotify();
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(mouseNavigation, java.awt.AWTEvent.MOUSE_EVENT_MASK);
    }

    @Override
    public void removeNotify() {
        java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(mouseNavigation);
        super.removeNotify();
    }

    /** Der Zurück-Knopf (Tests). */
    javax.swing.JButton backButton() {
        return backButton;
    }

    /** Der Vor-Knopf (Tests). */
    javax.swing.JButton forwardButton() {
        return forwardButton;
    }

    /** Lädt den Pfad der angezeigten Seite; ohne Seitenbaum der Quelle bleibt er leer. */
    private void loadPosition() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().position(source, id), position -> {
            if (shown == page) {
                breadcrumbs.show(position);
                renderDetails();
                shownPosition = position;
                updateStar();
                positionShown.accept(position);
            }
        }, error -> {
            // Quellen ohne Seitenbaum und gesperrte Vorfahren: der Pfad fehlt, die Seite bleibt lesbar.
        });
    }

    /** Lädt die Querverweise einmal je Seite, wenn der Reiter „Verweise“ gewählt ist. */
    private void loadReferences() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || referencesLoaded) {
            return;
        }
        referencesLoaded = true;
        references.message("Verweise werden geladen …");
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().references(source, id), found -> {
            if (shown == page) {
                references.show(found);
            }
        }, error -> {
            if (shown != page) {
                return;
            }
            referencesLoaded = false;
            references.message("Verweise nicht geladen: " + Problems.describe(error).title());
        });
    }

    /** Liest die gespeicherten Bildbeschreibungen der Seite (#362) samt Vorschau aus der Ablage; beschreibt nichts. */
    private void loadImages() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || imagesLoaded) {
            return;
        }
        watchReleases(runtime);
        imagesLoaded = true;
        images.loading();
        String source = sourceId;
        DeliveredPage shown = page;
        Background.run(() -> imageRows(runtime, source, shown.snapshot().key().pageId(),
                runtime.workspace().images(shown)), found -> {
            if (shown == page) {
                images.describing(false);
                images.released(found.released());
                images.show(found.state(), found.reason(), found.warnings(), found.images());
            }
        }, error -> {
            if (shown == page) {
                images.describing(false);
                imagesLoaded = false;
                feedback.problem(error, Problems.Step.PAGE);
            }
        });
    }

    /**
     * „Bilder beschreiben“: nur auf Abruf, im Hintergrund, mit dem Vision-Modell des Core. Mit {@code once} auch für
     * eine nicht freigegebene Quelle, aber nur diese Seite und ohne gespeicherte Freigabe.
     */
    private void describeImages(boolean once) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        images.describing(true);
        feedback.working("Bilder werden beschrieben …");
        Background.run(() -> imageRows(runtime, source, id, once ? runtime.workspace().describeImagesOnce(source, id)
                : runtime.workspace().describeImages(source, id)), found -> {
            // Der Reiter gehört allen Seiten: auch nach einem Seitenwechsel wieder freigeben.
            images.describing(false);
            if (shown != page) {
                return;
            }
            images.released(found.released());
            images.show(found.state(), found.reason(), found.warnings(), found.images());
            feedback.success(found.images().isEmpty() ? "Keine Bilder auf dieser Seite"
                    : "Bilder beschrieben: " + found.described() + " von " + found.images().size());
        }, error -> {
            images.describing(false);
            if (shown != page) {
                return;
            }
            feedback.problem(error, Problems.Step.PAGE);
        });
    }

    /**
     * „Diese Quelle freigeben“: setzt dasselbe Häkchen wie im Quellenformular (dieselbe YAML), lässt den Core die
     * Freigabe übernehmen und beschreibt dann die Bilder dieser Seite.
     */
    private void releaseSourceAndDescribe() {
        if (context.runtime() == null || page == null || sourceId == null) {
            return;
        }
        String source = sourceId;
        DeliveredPage shown = page;
        images.describing(true);
        feedback.working("Quelle wird freigegeben …");
        context.releaseSource(source, () -> {
            if (shown != page) {
                images.describing(false);
                return;
            }
            describeImages(false);
        }, error -> {
            images.describing(false);
            if (shown == page) {
                feedback.problem(error, Problems.Step.PAGE);
            }
        });
    }

    /**
     * Meldet sich je Laufzeit einmal bei der Liste freigegebener Seiten an: Kommt die gezeigte Seite dazu oder geht sie
     * weg (etwa durch Senden im Chat), gilt der geladene Stand der Bilder nicht mehr; ist „Bilder“ offen, lädt er neu.
     */
    private void watchReleases(AppRuntime runtime) {
        if (runtime == releaseRuntime) {
            return;
        }
        releaseSubscription.close();
        releaseRuntime = runtime;
        releaseSubscription = runtime.workspace().subscribePageReleases(change -> {
            boolean mine = java.util.stream.Stream.concat(change.added().stream(), change.removed().stream())
                    .anyMatch(entry -> entry.key().sourceId().equals(sourceId)
                            && entry.key().pageId().equals(pageId));
            if (mine) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    imagesLoaded = false;
                    if (IMAGES.equals(selectedTab())) {
                        loadImages();
                    }
                });
            }
        });
    }

    /** Die Bilder für den Reiter; Vorschauen nur aus der Ablage, außerhalb des EDT. */
    private static ImageRows imageRows(AppRuntime runtime, String sourceId, String pageId, PageImages found) {
        List<ImagesTab.Image> rows = new ArrayList<>();
        java.util.Map<String, String> versions;
        try {
            versions = found.result() == null ? java.util.Map.of()
                    : ImagePayload.attachmentVersions(found.result().payloadJson());
        } catch (IllegalArgumentException e) {
            versions = java.util.Map.of();
        }
        for (ImagePayload.Entry entry : found.images()) {
            String version = entry.assetId() == null ? null : versions.get(entry.assetId());
            Icon preview = preview(() -> runtime.workspace().storedImage(sourceId, entry, version));
            String name = entry.title().isBlank() ? entry.filename().isBlank() ? "Bild " + (entry.index() + 1)
                    : entry.filename() : entry.title();
            rows.add(new ImagesTab.Image(name, ImagesTab.Status.valueOf(entry.status().name()), entry.description(),
                    entry.reason(), preview));
        }
        return new ImageRows(PassagesTab.State.valueOf(found.state().name()), found.reason(), found.warnings(),
                // Freigegeben ist die Quelle oder die Seite selbst (Liste freigegebener Seiten, ADR 0028).
                List.copyOf(rows), found.describedCount(), runtime.workspace().imagesReleased(sourceId, pageId));
    }

    /**
     * Die Vorschau eines Bildes oder {@code null}: Scheitert das Lesen (etwa {@code RATE_LIMITED} am LargeFileGate),
     * steht nur bei diesem Bild „Keine Vorschau“, die übrigen laden weiter.
     */
    static Icon preview(java.util.function.Supplier<java.util.Optional<byte[]>> stored) {
        try {
            return stored.get().map(PageDetailPanel::thumbnail).orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /**
     * Ein Vorschaubild höchstens 96 × 72 groß; {@code null}, wenn sich die Bytes nicht lesen lassen oder das Bild über
     * {@link com.aresstack.spaceharvester.app.ui.content.viewer.ReaderImageStore#MAX_PIXELS} liegt.
     */
    static Icon thumbnail(byte[] bytes) {
        try {
            if (com.aresstack.spaceharvester.app.ui.content.viewer.ReaderImageStore.tooLarge(bytes)) {
                return null;
            }
            java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            double scale = Math.min(1d, Math.min(96d / image.getWidth(), 72d / image.getHeight()));
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            return new javax.swing.ImageIcon(image.getScaledInstance(width, height, java.awt.Image.SCALE_SMOOTH));
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        }
    }

    private record ImageRows(PassagesTab.State state, String reason, List<String> warnings,
                             List<ImagesTab.Image> images, int described, boolean released) {
    }

    private void harvestAssets() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        feedback.working("Anhänge werden abgelegt …");
        Background.run(() -> runtime.workspace().harvestAssets(source, id), result -> {
            long failed = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.FAILED).count();
            long stored = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.STORED).count();
            long reused = result.entries().stream().filter(e -> e.status() == AssetHarvest.Status.REUSED).count();
            String summary = stored + " abgelegt, " + reused + " schon vorhanden, " + failed + " fehlgeschlagen";
            if (failed == 0 && result.listComplete()) {
                feedback.success("Anhänge abgelegt: " + summary);
            } else {
                feedback.warning("Anhänge nur teilweise abgelegt", summary
                        + (result.listComplete() ? "" : "; die Anhangsliste war unvollständig"));
            }
            loadAssets(true);
        }, feedback::problem);
    }

    private void saveAsset() {
        AppRuntime runtime = context.runtime();
        int row = assets.getSelectedRow();
        if (runtime == null || row < 0) {
            return;
        }
        PageAssets.Entry entry = assetsModel.rows.get(row);
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(safeName(entry.attachment().title())));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        String source = sourceId;
        String id = pageId;
        feedback.working("Anhang wird geladen …");
        Background.run(() -> {
            // Als Strom, ohne die ganze Datei in den Speicher zu laden (#571).
            try (com.aresstack.spaceharvester.application.assets.Assets.AssetStream stream =
                         runtime.workspace().assetStream(source, id, entry.attachment().attachmentId())) {
                return writeReplacing(stream.content(), target.toPath());
            }
        }, bytes -> feedback.success("Gespeichert: " + target + " (" + Texts.bytes(bytes) + ")"), feedback::problem);
    }

    /**
     * Schreibt den Strom zuerst in eine eigene, neu angelegte Zwischendatei im Zielordner und ersetzt das Ziel erst,
     * wenn alles gelesen ist. Bricht der Strom ab (etwa ein Hashfehler am Ende), wird nur diese Zwischendatei gelöscht
     * und ein vorhandenes Ziel bleibt, wie es war; fremde Dateien im Ordner bleiben unberührt.
     *
     * @return die Zahl der geschriebenen Bytes
     */
    static long writeReplacing(java.io.InputStream content, java.nio.file.Path target) throws IOException {
        java.nio.file.Path absolute = target.toAbsolutePath();
        java.nio.file.Path part = Files.createTempFile(absolute.getParent(), "." + absolute.getFileName() + "-",
                ".part");
        try {
            long bytes = Files.copy(content, part, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.move(part, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return bytes;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(part);
            throw e;
        }
    }

    /** „Vorschau“ nur mit geladener Seite und gewähltem Anhang. */
    private void updatePreviewEnabled() {
        previewAsset.setEnabled(page != null && assets.getSelectedRow() >= 0);
    }

    /**
     * Öffnet den gewählten Anhang im Anhang-Viewer als Overlay über der Liste, wie {@code
     * KnowledgeReaderPanel.openAttachment} im Enterprise AI Client; geladen wird erst jetzt. Office-Dateien zeigt
     * der Viewer über die Vorschau der Quelle, die nur Quellen mit {@code ATTACHMENT_PREVIEWS} anbieten.
     */
    void openAttachment() {
        AppRuntime runtime = context.runtime();
        int row = assets.getSelectedRow();
        if (runtime == null || page == null || row < 0 || row >= assetsModel.rows.size()) {
            return;
        }
        PageAssets.Entry entry = assetsModel.rows.get(row);
        com.aresstack.spaceharvester.application.sources.PageAttachment file = entry.attachment();
        String mediaType = file.mediaType() != null && !file.mediaType().isBlank() ? file.mediaType()
                : entry.stored() == null ? null : entry.stored().mediaType();
        ReaderAttachment attachment = new ReaderAttachment(file.attachmentId(), file.title(), mediaType,
                file.fileSize() == null ? -1 : file.fileSize());
        String source = sourceId;
        boolean previews = runtime.workspace().attachmentPreviews(source);
        showAttachmentOverlay(new AttachmentViewerPanel(attachment,
                attachmentLoader(runtime, source, pageId, previews), fileOpener, attachmentRenderer),
                attachment.fileName());
    }

    /** Grund im Anhang-Viewer, solange die Quelle einen Einmalcode verlangt; danach lädt die Seite neu. */
    static final String SIGN_IN_PENDING = "Für diesen Anhang ist eine zusätzliche Anmeldung (Einmalcode) "
            + "erforderlich.";

    /** Holt Originaldatei und Vorschau über den Workspace, abseits des EDT; Fehler als lesbarer Grund. */
    private static ReaderAttachmentLoader attachmentLoader(AppRuntime runtime, String source, String id,
                                                           boolean previews) {
        return new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                Background.run(() -> runtime.workspace().assetContent(source, id, attachment.key()).content(), done,
                        error -> failed.accept(reason(error)));
            }

            @Override
            public void preview(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                if (!previews) {
                    ReaderAttachmentLoader.super.preview(attachment, done, failed);
                    return;
                }
                Background.run(() -> runtime.workspace().assetPreview(source, id, attachment.key()).content(), done,
                        error -> failed.accept(reason(error)));
            }
        };
    }

    private static String reason(Throwable error) {
        if (error instanceof com.aresstack.spaceharvester.application.signin.SignInPendingException) {
            return SIGN_IN_PENDING;
        }
        Problems.Problem problem = Problems.describe(error);
        return problem.message() == null ? problem.title() : problem.title() + ": " + problem.message();
    }

    private void showAttachmentOverlay(JComponent content, String heading) {
        closeAttachmentOverlay();
        attachmentOverlay = new ComicOverlayPanel(heading, content, this::closeAttachmentOverlay, Comic.PALETTE);
        attachmentOverlay.getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "closeAttachment");
        attachmentOverlay.getActionMap().put("closeAttachment", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                closeAttachmentOverlay();
            }
        });
        attachmentLayers.add(attachmentOverlay, JLayeredPane.MODAL_LAYER);
        attachmentLayers.revalidate();
        attachmentLayers.repaint();
    }

    void closeAttachmentOverlay() {
        if (attachmentOverlay != null) {
            AttachmentViewerPanel viewer = find(attachmentOverlay);
            if (viewer != null) {
                viewer.close(); // erst hier endgültig: Aus- und Einhängen (Baum/Liste) lässt den Viewer leben
            }
            attachmentLayers.remove(attachmentOverlay);
            attachmentOverlay = null;
            attachmentLayers.revalidate();
            attachmentLayers.repaint();
        }
    }

    /** Für Tests: der offene Anhang-Viewer oder {@code null}. */
    AttachmentViewerPanel attachmentViewer() {
        return attachmentOverlay == null ? null : find(attachmentOverlay);
    }

    private static AttachmentViewerPanel find(java.awt.Component root) {
        if (root instanceof AttachmentViewerPanel viewer) {
            return viewer;
        }
        if (root instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                AttachmentViewerPanel found = find(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** Für Tests: womit „Öffnen“ im Anhang-Viewer die Datei öffnet und auf welchem Thread PDF-Seiten entstehen. */
    void setAttachmentViewerHooks(ReaderFileOpener opener, Executor renderer) {
        this.fileOpener = opener == null ? ReaderFileOpener.system() : opener;
        this.attachmentRenderer = renderer;
    }

    JTable assets() {
        return assets;
    }

    ComposerButton previewAsset() {
        return previewAsset;
    }

    private void saveRaw() {
        if (page == null) {
            return;
        }
        NativePayload payload = page.snapshot().payload();
        JFileChooser chooser = new JFileChooser();
        chooser.setSelectedFile(new File(safeName(page.header().metadata().title())
                + (Payloads.isJson(payload) ? ".json" : ".bin")));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File target = chooser.getSelectedFile();
        Background.execute(() -> Files.write(target.toPath(), payload.bytes()),
                () -> feedback.success("Original gespeichert: " + target + " (" + Texts.bytes(payload.length())
                        + ", SHA-256 " + Texts.shortHash(payload.rawSha256()) + ")"), feedback::problem);
    }

    /** Die Seitendetails als Text (auch Tooltip des Titels): Ort, Version, Herkunft und Bildzeile. */
    String details() {
        List<String> lines = new java.util.ArrayList<>();
        for (String line : new String[] {breadcrumbs.text(), path.getText(), state.getText(), readingPane.details()}) {
            if (line != null && !line.isBlank()) {
                lines.add(line.trim());
            }
        }
        return String.join("\n", lines);
    }

    private void renderDetails() {
        title.setCursor(page != null || !details().isEmpty()
                ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR) : null);
    }

    /** Popup mit den Details der Seite, geöffnet beim Überfahren der Überschrift. */
    private void showDetails() {
        renderDetails();
        if (page == null && details().isEmpty()) {
            return;
        }
        if (detailsPopup != null && detailsPopup.isVisible()) {
            return;
        }
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setBackground(Comic.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(8, 10, 6, 10));
        JLabel name = new JLabel(title.getText());
        name.setFont(Comic.semiBold(13f));
        name.setForeground(Comic.INK);
        body.add(name, BorderLayout.NORTH);
        JPanel text = new JPanel();
        text.setOpaque(false);
        text.setLayout(new javax.swing.BoxLayout(text, javax.swing.BoxLayout.Y_AXIS));
        breadcrumbs.setAlignmentX(LEFT_ALIGNMENT);
        if (!breadcrumbs.text().isBlank()) {
            text.add(breadcrumbs);
        }
        for (String line : new String[] {path.getText(), state.getText(), readingPane.details()}) {
            if (line != null && !line.isBlank()) {
                JLabel label = Comic.muted(line);
                label.setAlignmentX(LEFT_ALIGNMENT);
                text.add(label);
            }
        }
        body.add(text, BorderLayout.CENTER);
        popup.add(body);
        popup.addSeparator();
        // „Lesezeichen kopieren“ und „Im Browser öffnen“ stehen rechts in der Kopfzeile, hier nicht noch einmal.
        javax.swing.JMenuItem copyId = new javax.swing.JMenuItem("Seiten-ID kopieren");
        copyId.addActionListener(e -> copyPageId());
        copyId.setEnabled(pageId != null);
        popup.add(copyId);
        popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent event) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent event) {
                detailsWatch.stop();
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent event) {
            }
        });
        detailsPopup = popup;
        if (title.isShowing()) {
            // Direkt unter der Überschrift, ohne Lücke, damit die Maus ins Popup wandern kann.
            popup.show(title, 0, title.getHeight());
            detailsWatch.restart();
        }
    }

    /** Schließt das Popup, sobald der Mauszeiger weder auf der Überschrift noch im Popup steht. */
    private void hideDetailsWhenLeft() {
        javax.swing.JPopupMenu popup = detailsPopup;
        if (popup == null || !popup.isVisible()) {
            detailsWatch.stop();
            return;
        }
        java.awt.PointerInfo pointer = java.awt.MouseInfo.getPointerInfo();
        if (pointer == null) {
            return;
        }
        if (!pointerInside(pointer.getLocation(), title, popup)) {
            popup.setVisible(false);
            detailsWatch.stop();
        }
    }

    /** Liegt der Bildschirmpunkt auf der Überschrift oder im Popup (mit 4 px Spielraum an den Kanten)? */
    static boolean pointerInside(java.awt.Point screen, java.awt.Component... areas) {
        for (java.awt.Component area : areas) {
            if (area == null || !area.isShowing()) {
                continue;
            }
            java.awt.Point origin = area.getLocationOnScreen();
            java.awt.Rectangle bounds = new java.awt.Rectangle(origin, area.getSize());
            bounds.grow(4, 4);
            if (bounds.contains(screen)) {
                return true;
            }
        }
        return false;
    }

    private javax.swing.JPopupMenu detailsPopup;

    private void copyPageId() {
        if (pageId == null) {
            return;
        }
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(pageId), null);
        feedback.success("Seiten-ID kopiert: " + pageId);
    }

    /** Für Tests: die Überschrift (öffnet das Popup beim Überfahren) und das zuletzt geöffnete Popup. */
    JLabel titleLabel() {
        return title;
    }

    javax.swing.JPopupMenu detailsPopup() {
        return detailsPopup;
    }

    /** Legt das Lesezeichen des Core ({@code PageBookmark}, #178) in die Zwischenablage. */
    private void copyBookmark() {
        if (page == null) {
            return;
        }
        String value = PageBookmark.of(page.header()).value();
        java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new java.awt.datatransfer.StringSelection(value), null);
        feedback.success("Lesezeichen kopiert: " + value);
    }

    private void browse() {
        if (page == null || page.header().metadata().url() == null) {
            return;
        }
        try {
            Desktop.getDesktop().browse(page.header().metadata().url());
        } catch (IOException | UnsupportedOperationException e) {
            feedback.problem(e);
        }
    }

    void recompute() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        PageText text = shown == null ? null : shown.text();
        boolean live = page.live();
        Font font = reader.getFont();
        int current = ++request;
        feedback.working("Analysen werden berechnet …");
        Background.run(() -> {
            Enrichments.Run run = runtime.workspace().recomputeEnrichments(source, id, null);
            DeliveredPage cached = runtime.workspace().page(source, id, false);
            List<Enrichments.View> updated = runtime.workspace().enrichments(cached);
            ReaderDocument document = document(source, id, updated, text, font);
            relations(runtime, document, source, cached.header().metadata().partitionKey());
            navigation(runtime, document, source, id);
            return new Recomputed(run, updated, document);
        }, result -> {
            if (current != request) {
                return;
            }
            Enrichments.Run run = result.run();
            views = result.views();
            analysisView.steps(views, this::stepResult);
            int caret = reader.getCaretPosition();
            showReader(result.reader(), live);
            reader.setCaretPosition(Math.min(caret, reader.getDocument().getLength()));
            if (!run.supported()) {
                feedback.info("Keine Analysen für dieses Format", null);
            } else if (run.failed() > 0) {
                feedback.warning("Analysen teilweise fehlgeschlagen", run.computed() + " berechnet, " + run.failed()
                        + " fehlgeschlagen");
            } else {
                feedback.success(run.computed() + " Analysen berechnet");
            }
        }, error -> {
            if (current == request) {
                feedback.problem(error);
            }
        });
    }

    /** Das Ergebnis eines Analyseschritts in Worten, soweit die Seite es schon gelesen hat; sonst {@code null}. */
    private String stepResult(Enrichments.View view) {
        ReaderDocument document = shown;
        if (document == null) {
            return null;
        }
        return switch (view.enrichment().processorId()) {
            case PageAcronyms.PROCESSOR -> document.acronyms() == null ? null
                    : Texts.count(document.acronyms().acronyms().size(), "Akronym", "Akronyme");
            case com.aresstack.spaceharvester.application.terms.TermPayload.PROCESSOR -> document.terms() == null
                    ? null : Texts.count(document.terms().identifierCount(), "Bezeichner", "Bezeichner");
            case "passages" -> document.passages() == null ? null
                    : Texts.count(document.passages().passages().size(), "Absatz", "Absätze");
            case "navigation" -> document.navigation() == null ? null
                    : Texts.count(document.navigation().chapters().size(), "Themenabschnitt", "Themenabschnitte");
            default -> null;
        };
    }

    /** Die Beziehungen der Begriffe dieser Seite und ihre Zahl aus dem Core. */
    private AnalysisView.Scope pageScope(PageKey key) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return null;
        }
        PageSet page = PageSet.ofKeys(List.of(key));
        return new AnalysisView.Scope() {
            @Override
            public PageSet pages() {
                return page;
            }

            @Override
            public int relations(PageSet pages) {
                return runtime.workspace().termRelationCounts(pages).values().stream().mapToInt(Integer::intValue)
                        .sum();
            }
        };
    }

    private void setActionsEnabled(boolean enabled) {
        if (!enabled) {
            shownPosition = null;
        }
        updateStar();
        refresh.setEnabled(enabled || pageId != null);
        saveRaw.setEnabled(enabled);
        browse.setEnabled(enabled);
        bookmark.setEnabled(enabled);
        more.setEnabled(enabled);
        harvest.setEnabled(enabled);
        saveAsset.setEnabled(enabled);
        updatePreviewEnabled();
        recompute.setEnabled(enabled);
        recomputeReader.setEnabled(enabled);
    }

    /** Die Überschrift der Seite für die Zeile der Bereichsanzeige; öffnet beim Überfahren die Details. */
    JComponent headerTitle() {
        return heading;
    }

    /**
     * Stern und das Dreieck mit den weiteren Aktionen, rechts in der Zeile der Bereichsanzeige; auf der Quellenseite
     * links davon die Werkzeuge der Updates (keine eigene Leiste, Angelo 17.11.2026).
     */
    JComponent headerActions() {
        return Comic.row(sourceView.tools(), sourceChanges.tools(), sourceProperties.tools(), readingHelp,
                standingHelp, glossaryHelp, graphHelp, chat.headerActions(), star, more);
    }

    /** Zurück und Vor für die Statuszeile des Hauptfensters. */
    JComponent navigation() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 2, 0));
        row.setOpaque(false);
        row.add(backButton.compact());
        row.add(forwardButton.compact());
        return row;
    }

    javax.swing.AbstractButton moreButton() {
        return more;
    }

    /** „Lesezeichen kopieren“ und „Im Browser öffnen“; je Eintrag so wählbar wie die Aktion gerade gilt. */
    javax.swing.JPopupMenu moreMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        for (ComposerButton action : List.of(bookmark, browse)) {
            javax.swing.JMenuItem item = new javax.swing.JMenuItem(action.getText());
            item.setFont(Comic.regular(13f));
            item.setToolTipText(action.getToolTipText());
            item.setEnabled(action.isEnabled());
            item.addActionListener(e -> action.doClick());
            menu.add(item);
        }
        return menu;
    }

    Breadcrumbs breadcrumbs() {
        return breadcrumbs;
    }

    References references() {
        return references;
    }

    PassagesTab passages() {
        return passages;
    }

    ImagesTab images() {
        return images;
    }

    TermsTab terms() {
        return terms;
    }

    /**
     * Die Hauptreiter (Leseansicht, Text, Eigenschaften, Anhänge, Analysen, Erweitert) als quadratische Symbolknöpfe;
     * das Hauptfenster setzt sie in die Icon-Leiste oben, wo sie die Mitte umschalten. Immer sichtbar, auch ohne
     * Seite (dann zeigt die Mitte den Hinweis, eine Seite im Explorer zu wählen).
     */
    public JComponent tabIcons() {
        return tabIcons;
    }

    PillTabs tabs() {
        return tabs;
    }

    /** Für Tests: die Unterreiter unter „Analysen“. */
    PillTabs analysisTabs() {
        return analysisTabs;
    }

    /** Für Tests: der Inhalt des Unterreiters „Graph“. */
    JPanel knowledgeGraph() {
        return graph;
    }

    /** Für Tests: der Inhalt des Unterreiters „Aussagen“. */
    JPanel statements() {
        return statements;
    }

    /**
     * Wählt einen Reiter über seinen Namen; ein Unterreiter von „Analysen“ (z. B. „Absätze“) wählt zuerst den
     * Unterreiter und dann den Hauptreiter „Analysen“.
     */
    void selectTab(String name) {
        if (analysisTabs.button(name) != null) {
            analysisTabs.select(name);
            tabs.select(ANALYSES);
        } else {
            tabs.select(name);
        }
    }

    /** Der sichtbare Reiter: unter „Analysen“ der gewählte Unterreiter, sonst der Hauptreiter. */
    String selectedTab() {
        String main = tabs.selected();
        if (ANALYSES.equals(main) && wide) {
            return switch (java.util.Objects.requireNonNullElse(standing.tabs().selected(), "")) {
                case StandingTab.TERMS -> OVERVIEW_TERMS;
                case StandingTab.GLOSSARY -> OVERVIEW_GLOSSARY;
                default -> STAND;
            };
        }
        return ANALYSES.equals(main) ? analysisTabs.selected() : main;
    }

    /** Für Tests: die angezeigte Seite oder {@code null} ohne Seite und während des Ladens. */
    DeliveredPage shownPage() {
        return page;
    }

    /** Für Tests: die Rückmeldung über der Seite. */
    Feedback feedback() {
        return feedback;
    }

    /** Für Tests: der angezeigte Lesetext oder {@code null} während des Ladens. */
    ReaderDocument readerDocument() {
        return shown;
    }

    JTextPane reader() {
        return reader;
    }

    /**
     * Zeigt die Dokumentnavigation der angezeigten Seite als Wolkenbanner über dem Reiter „Text“; {@code null} oder
     * leer blendet ihn aus. Die Positionen beziehen sich auf den angezeigten Lesetext. Beim Laden fragt die App
     * {@code harvester.documentNavigation().page(caller, sourceId, pageId)} ab ({@link Navigation#of}). EDT.
     */
    void showNavigation(Navigation navigation) {
        Navigation shownNavigation = navigation == null || navigation.isEmpty() ? null : navigation;
        readerPage.setNavigation(shownNavigation);
        // Dieselben Inhaltswolken auch über der Leseansicht, als Übersicht beim Lesen.
        readingPane.setNavigation(shownNavigation, reader.getText());
    }

    /** Für Tests: der Navigationsbanner über dem Lesetext. */
    DocumentNavigationSky navigationSky() {
        return readerPage.sky();
    }

    /** Für Tests: die Leseansicht. */
    ReadingPane readingPane() {
        return readingPane;
    }

    /** Für Tests: der Lesetext mit dem Banner darüber. */
    NavigableDocumentPage readerPage() {
        return readerPage;
    }

    String acronymHint() {
        return acronymHint.getText();
    }

    String acronymDetail() {
        return acronymDetail.getText();
    }

    private static JPanel padded(JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBackground(java.awt.Color.WHITE);
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        panel.add(content, BorderLayout.NORTH);
        return panel;
    }

    static String safeName(String title) {
        String name = title == null ? "seite" : title.replaceAll("[\\p{C}\\\\/:*?\"<>|]", "_").trim();
        return name.isEmpty() ? "seite" : name.length() > 120 ? name.substring(0, 120) : name;
    }

    /** @param checked ob der Bediener ausdrücklich „Aktualität prüfen“ gewählt hat */
    /** {@code passage} nur beim Öffnen über eine Passage-ID ({@link #openPassage}). */
    /** {@code unchecked}: sofort aus dem Bestand gezeigt, die Prüfung bei der Quelle läuft noch ({@link #checkSource}). */
    private record Loaded(DeliveredPage page, List<Enrichments.View> enrichments, ReaderDocument reader,
                          String display, String storage, boolean checked, PassageLookup passage, boolean unchecked) {
    }

    private record Recomputed(Enrichments.Run run, List<Enrichments.View> views, ReaderDocument reader) {
    }

    /** Jede Ebene (Liste, Overlay) füllt den Reiter „Anhänge“ (wie {@code KnowledgeReaderPanel.FillLayout}). */
    private static final class FillLayout implements java.awt.LayoutManager {
        @Override
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component comp) {
        }

        @Override
        public Dimension preferredLayoutSize(java.awt.Container parent) {
            Dimension size = new Dimension();
            for (java.awt.Component child : parent.getComponents()) {
                Dimension preferred = child.getPreferredSize();
                size.width = Math.max(size.width, preferred.width);
                size.height = Math.max(size.height, preferred.height);
            }
            return size;
        }

        @Override
        public Dimension minimumLayoutSize(java.awt.Container parent) {
            return new Dimension();
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            for (java.awt.Component child : parent.getComponents()) {
                child.setBounds(0, 0, parent.getWidth(), parent.getHeight());
            }
        }
    }

    private static final class AssetsModel extends AbstractTableModel {

        private static final String[] COLUMNS = {"Titel", "Art", "Größe", "Version", "Abgelegt", "Vorschau von"};
        private List<PageAssets.Entry> rows = new ArrayList<>();

        void set(PageAssets overview) {
            rows = overview == null ? new ArrayList<>() : new ArrayList<>(overview.entries());
            fireTableDataChanged();
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
            PageAssets.Entry entry = rows.get(row);
            return switch (column) {
                case 0 -> entry.attachment().title();
                case 1 -> switch (entry.kind()) {
                    case DIAGRAM -> "draw.io-Diagramm";
                    case VECTOR -> "SVG";
                    case IMAGE -> "Bild";
                    case OTHER -> "Datei";
                };
                case 2 -> entry.attachment().fileSize() == null ? "–" : Texts.bytes(entry.attachment().fileSize());
                case 3 -> entry.attachment().version();
                case 4 -> entry.stored() == null ? "nein" : "ja, " + Texts.shortHash(entry.stored().sha256());
                default -> Optional.ofNullable(entry.previewOf()).orElse("");
            };
        }
    }
}
