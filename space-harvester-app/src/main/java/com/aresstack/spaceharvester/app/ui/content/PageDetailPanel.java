package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.spaceharvester.app.ui.controls.MessagePlate;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.enterpriseai.ui.comic.control.ComicOverlayPanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.application.changelog.ChangeLevel;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
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
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoFrameGrabber;
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoViewerPanel;
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoTranscriptSource;
import com.aresstack.spaceharvester.app.ui.content.viewer.VideoTranscriptionPanel;
import com.aresstack.spaceharvester.app.transcription.TranscribeAudioUseCase;
import com.aresstack.spaceharvester.app.transcription.VlcPcmAudioDecoder;
import com.aresstack.spaceharvester.app.transcription.WhisperSpeechTranscriber;
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
import com.aresstack.spaceharvester.application.snapshot.LocalOriginalReference;
import com.aresstack.spaceharvester.application.snapshot.NativePayload;
import com.aresstack.spaceharvester.application.knowledge.GraphNeighbourhood;
import com.aresstack.spaceharvester.application.snapshot.PageKey;
import com.aresstack.spaceharvester.application.snapshot.PageSet;
import com.aresstack.spaceharvester.application.snapshot.PageSnapshot;
import com.aresstack.spaceharvester.application.snapshot.SnapshotWarning;
import com.aresstack.spaceharvester.application.sources.PageMetadata;

import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JCheckBox;
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

    private static final String READ = "Podcast-Ansicht";
    private static final String READING = "Leseansicht";
    private java.util.function.BiConsumer<String, String> pageShown = (source, space) -> { };
    /** Stand des sichtbaren Reiters für die Statuszeile des Hauptfensters ({@link #onStatus}). */
    private java.util.function.Consumer<StatusNote> statusShown =
            note -> { };
    /** Die Erläuterung je Reiter; gezeigt wird die des sichtbaren Reiters. */
    private final java.util.Map<String, StatusNote> tabStatus =
            new java.util.HashMap<>();
    private java.util.function.Consumer<com.aresstack.spaceharvester.application.navigation.PageNavigation.Position> positionShown = position -> { };
    private static final String PROPERTIES = "Eigenschaften";
    private static final String ATTACHMENTS = "Anhänge";
    private static final String REFERENCES = "Verweise";
    private static final String TERMS = "Statistik";
    private static final String PASSAGES = "Absätze";
    private static final String IMAGES = "Bilder";
    private static final String IMAGES_WORD = "Bilder";
    private static final String STATEMENTS_WORD = "Aussagen";
    static final String ANALYSES = "Analysen";
    private static final String STATEMENTS = "Aussagen";
    static final String STATEMENTS_PLACEHOLDER = "Noch keine Aussagen – Analyse folgt";
    private static final String GRAPH = "Graph";
    static final String GRAPH_PLACEHOLDER = "Noch kein Wissensgraph – Analyse folgt";
    /** Eigenschaften und Technik der Seite; „Eigenschaften“ ist sein erster Unterreiter (Angelo, 19.11.2026). */
    private static final String ADVANCED = "Info";
    /** Reiter „Stand“ (ADR 0032, FL6): was in der Auswahl links gilt, geplant, unklar oder überholt ist. */
    static final String STAND = "Stand";
    /** Reiter „Export“ (Angelo, 13.11.2026): Stand und Ausgabe der Auswahl links, auch ohne gezeigte Seite. */
    static final String EXPORT = "Export";
    /**
     * Unterreiter „Beziehungen“ unter „Analysen“ (vorher „Analyse“ unter „Info“, Angelo 20.11.2026): Analyseschritte
     * und Beziehungen der Begriffe der Seite, ohne Seite die der Auswahl.
     */
    static final String RELATIONS = "Beziehungen";
    private static final String RAW = "Originaldaten";
    private static final String TECHNICAL = "Technische Angaben";
    private static final String EMPTY = "empty";
    private static final String PAGE = "page";
    private static final String EXPORT_CARD = "export";
    private static final String STAND_CARD = "standing";
    /** Die Quellenseite (ADR 0030): unter „Leseansicht“ die Updates der gewählten Wissensquelle. */
    private static final String SOURCE_CARD = "source";
    private static final String SOURCE_TEXT_CARD = "source-text";
    /** Umschalter „Bereich“: der Überblick über den ganzen Bereich statt der Änderungen (Angelo 24.11.2026). */
    private static final String AREA_CARD = "area";
    private static final String AREA_TEXT_CARD = "area-text";
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
    /** Die Artefakte des Chats in der Export-Ansicht; {@code null}, solange keine eingesetzt sind. */
    private ArtifactTabs artifactTabs;
    /** „Aus dieser Seite erzeugt“: auf der Seite von „Export“ und im ▾-Menü dieselben Einträge. */
    private final LocalArtifacts localArtifacts;
    /**
     * Die untere Leiste von „Export“: mittig die Knöpfe der Ausgabe (Angelo 27.11.2026), je nach Reiter die der
     * Export-Fläche, eines Export-Reiters oder nur die Formate des gewählten Artefakts (ADR 0045 §5).
     */
    private final JPanel exportBarSlot = new JPanel(new BorderLayout());
    private final TabFoot exportFoot = new TabFoot(exportBarSlot, null);
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
    /**
     * Checkbox „mit Unterseiten“ der Analyse-Reiter einer Seite (Fahrplan P2/P3): eine Stellung für alle Reiter, nur
     * für die Sitzung, Standard aus.
     */
    private final Subpages subpages = new Subpages();
    /** „Aus Unterseiten“ unter Bildern, Aussagen und Graph (Fahrplan P3); zeigt nur Vorhandenes, nie ein KI-Lauf. */
    private final SubpageSection imagesBelow = new SubpageSection(IMAGES_WORD, 8, "Bild", "Beschreibung");
    /** „Aus Unterseiten“ unter Absätzen (Angelo 23.11.2026: „mit Unterseiten“ gilt in allen Analyse-Reitern). */
    private final SubpageSection passagesBelow = new SubpageSection(PASSAGES, 8, "Absatz", "Anriss");
    private final SubpageSection statementsBelow = new SubpageSection(STATEMENTS_WORD, 8, "Aussage", "Beteiligte");
    private final SubpageSection graphBelow = new SubpageSection("Unterseiten", 8).badge("Knoten");
    /** Unterreiter „Analysen → Aussagen“ (#499): gespeicherte Analyse der Seite, neu nur auf Knopfdruck. */
    private final StatementsTab statementsView;
    /** Unterreiter „Analysen → Graph“ (#499 F): fokussierte Teilgraphen, geladen erst beim Anzeigen. */
    private final KnowledgeGraphTab graphView;
    /** Quellenseite: unter „Leseansicht“ die KI-Zusammenfassung des gewählten Updates. */
    private final SourceUpdatesView sourceView;
    /** Quellenseite: unter „Text“ die Updates mit ihren Änderungen (Angelo, 17.11.2026). */
    private final SourceUpdatesView sourceChanges;
    /** Der Überblick über den Bereich zum Lesen (Leseansicht) und zum Hören (Podcast-Ansicht). */
    private final AreaSummaryView areaView;
    private final AreaSummaryView areaPodcast;
    /**
     * „Änderungen | Bereich“ mittig unten, je Leiste einer (Leseansicht, Podcast-Ansicht der Änderungen und des
     * Bereichs); alle zeigen dieselbe, global gemerkte Wahl.
     */
    private final List<com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle> scopeToggles =
            new java.util.ArrayList<>();
    private final List<JComponent> scopeRows = new java.util.ArrayList<>();
    private final List<com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle> bookToggles =
            new java.util.ArrayList<>();
    /** „Eigenschaften“ der Quellenseite: Kennzahlen und Einstellungen der Quelle. */
    private final SourcePropertiesView sourceProperties;
    private final JLabel state = Comic.muted(" ");
    /** Schließt das Seiteninfo-Popup, sobald die Maus Überschrift und Popup verlassen hat. */
    private final javax.swing.Timer detailsWatch = new javax.swing.Timer(120, e -> hideDetailsWhenLeft());
    private final Feedback feedback = new Feedback();
    /** Wartet auf das Ende von „Anhänge ablegen“ der gezeigten Seite (Fundstelle 24); nur auf dem EDT. */
    private com.aresstack.spaceharvester.application.observe.Subscription harvestWatch;
    /** Laufende „Anhänge ablegen“ je Seite (Auftrags-ID): Kehrt man zurück, erscheint die Meldung wieder. EDT. */
    private final java.util.Map<PageKey, String> harvests = new java.util.HashMap<>();
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
    private static final String TEXT_READER_CARD = "reader";
    private static final String TEXT_VIDEO_TRANSCRIPT_CARD = "video-transcript";
    /** Im Reiter „Text“ statt des Lesetexts: das Original einer Datei ohne Textauszug (Bild, …) im Viewer. */
    private static final String TEXT_ORIGINAL_CARD = "original";
    /** Hinweis im Reiter „Text“ zu einer leeren Datei. */
    static final String EMPTY_FILE = "Diese Datei ist leer: Die Quelle meldet 0 Bytes. Es gibt nichts anzuzeigen.";
    private final java.awt.CardLayout textCardLayout = new java.awt.CardLayout();
    private final JPanel textContents = new JPanel(textCardLayout);
    private final VideoTranscriptionPanel videoTranscript = new VideoTranscriptionPanel(
            new TranscribeAudioUseCase(new VlcPcmAudioDecoder(), new WhisperSpeechTranscriber()));
    private final NavigableDocumentPage readerPage;
    /** Der Erklär-Podcast der Seite (Angelo 21.11.2026): Play-Knopf oben rechts im Himmel des Lesetexts. */
    private final PodcastControl<PodcastPage> pagePodcast;
    /** Die Bilder und die Markierung des Seiten-Podcasts im Lesetext. */
    private final PagePodcastPictures pagePictures;
    /** Zähler der Bilder-Leseläufe: ein Ergebnis zu einer früheren Seite verfällt. */
    private int pagePictureLoads;
    /** Die Stellen, von denen der Seiten-Podcast gerade erzählt; nach einem Neuaufbau des Lesetexts wieder gelb. */
    private List<int[]> pageSpans = List.of();
    /** Wovon der ganze Seiten-Podcast erzählt; leer ohne Podcast. Der Rest des Texts ist grau (Angelo 27.11.2026). */
    private Optional<List<int[]>> pageCoverage = Optional.empty();

    /** Wofür der Erklär-Podcast entsteht: eine Seite samt Unterseiten. */
    record PodcastPage(String sourceId, String pageId) {
    }
    /** Die Sanduhr über dem Text, solange die Seite lädt (wie bei den Anhängen, ohne Esc). */
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay textBusy;
    private final JLabel acronymHint = Comic.muted(" ");
    private final JLabel acronymDetail = Comic.muted(" ");
    /**
     * Für welche Fassungen die veralteten Analysen schon selbsttätig neu berechnet wurden (Quelle, Seite, Text-Hash):
     * je geöffnetem Text einmal, damit ein Ergebnis, das veraltet bleibt, keine Schleife startet.
     */
    private final java.util.Set<String> autoRecomputed = new java.util.HashSet<>();
    private final Facts properties = new Facts();
    private final Facts technical = new Facts();
    private final JTextArea raw = Comic.code();
    private final JTextArea storage = Comic.code();
    private final AssetsModel assetsModel = new AssetsModel();
    private final JTable assets = Comic.table(new JTable(assetsModel));
    /** Die Sanduhr über der Anhangsliste, solange sie lädt. */
    private final com.aresstack.spaceharvester.app.ui.controls.BusyOverlay assetsBusy =
            new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(Comic.scroll(assets));
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
    private final ProgramAnalysisTabs programAnalysisTabs;
    /**
     * Ablage der Aussagenanalyse oben rechts in Aussagen und Graph (Fahrplan A3), also überall, wo der Inhalt von
     * der Ablage abhängt (UX). „Beziehungen“ liest nur Glossar-Beziehungen und Analyseschritte, nicht die Ablage.
     */
    private final AnalyzerStoreField storeField = new AnalyzerStoreField();
    /** Liegt beim Umschalten der Ablage über den Unterreitern von „Analysen“. */
    private com.aresstack.spaceharvester.app.ui.controls.BusyOverlay storeBusy;
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
        /** Die Seitenüberschrift: das sichtbare Kind. */
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
    /**
     * „Bilder anzeigen“ rechts in der Leiste der Anhänge (Angelo 27.11.2026): Bilder sieht man sich im Editor an, in der
     * Liste verwirren sie. Vorgabe aus; die Wahl gilt global und wird im Application State gemerkt.
     */
    private final JCheckBox showImages = Comic.checkBox("Bilder anzeigen");
    static final String SHOW_IMAGES_KEY = "attachments.images.shown";
    /** Die Leiste mit der Lupe unter „Anhänge“: Vorschau, Ablegen, Speichern links, „Bilder anzeigen“ rechts. */
    private final JPanel attachmentsFoot = footRow();
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
    /** Die Sprachausgabe der Leseansicht, beim ersten Gebrauch angelegt. */
    private com.aresstack.spaceharvester.app.speech.ReadAloudBinding readAloudBinding;
    private boolean referencesLoaded;
    /** Zählt die Ladevorgänge der Verweise; ein überholter Vorgang zeigt sein Ergebnis nicht mehr. */
    private long referencesGeneration;
    private boolean imagesLoaded;
    /** Anmeldung bei der Liste freigegebener Seiten (ADR 0028), damit „Bilder“ eine neue Freigabe der Seite zeigt. */
    private com.aresstack.spaceharvester.application.observe.Subscription releaseSubscription =
            com.aresstack.spaceharvester.application.observe.Subscription.NONE;
    private AppRuntime releaseRuntime;
    private com.aresstack.spaceharvester.application.observe.Subscription linksSubscription =
            com.aresstack.spaceharvester.application.observe.Subscription.NONE;
    private AppRuntime linksRuntime;
    private static final String ORIGINAL_PAGE_CARD = "original-page";
    private static final String ORIGINAL_FILE_CARD = "original-file";
    private final ReadingPane readingPane = new ReadingPane();
    private final java.awt.CardLayout originalCards = new java.awt.CardLayout();
    private final JPanel originalContent = new JPanel(originalCards);
    private final JPanel fileContent = new JPanel(new BorderLayout());
    private AttachmentViewerPanel originalFileViewer;
    /** Ob im Reiter „Text“ die Transkription eines Videos steht. */
    private boolean videoTranscriptShown;
    /** Das gezeigte Original, für den Viewer im Reiter „Text“; {@code null} ohne. */
    private ReaderAttachment originalAttachment;
    /** Lädt dieses Original höchstens einmal, für beide Viewer. */
    private ReaderAttachmentLoader originalLoader;
    /** Der Viewer im Reiter „Text“ für eine Datei ohne Textauszug. */
    private AttachmentViewerPanel textOriginalViewer;
    private final JPanel textOriginal = new JPanel(new BorderLayout());
    /** Die Zeile mit dem Akronym-Hinweis über dem Lesetext; über dem Transkript eines Videos ausgeblendet. */
    private final JPanel acronymBar = new JPanel(new BorderLayout(8, 0));
    /** Ob die gezeigte Datei leer ist (die Quelle meldet 0 Bytes). */
    private boolean emptyFileShown;
    /**
     * Die Werkzeuge dieser Ansicht in der Leiste mit der Lupe ({@code ContentView.tools()}), je nach Reiter: in der
     * Leseansicht einer Datei „Speichern“/„Öffnen“ samt „Typ · Größe“, im Text
     * eines Videos der Transkript-Knopf (Angelo, 19.11.2026: eine Leiste, die je nach Ansicht wechselt).
     */
    private final JPanel footTools = new JPanel(new BorderLayout());
    /**
     * Die Analysen stehen nicht mehr als Reiter in der Mitte, sondern in der rechten Seitenleiste zwischen Chat und
     * Aufträgen (Angelo 27.11.2026). Sie richten sich weiter nach der Auswahl links bzw. der offenen Seite: die
     * Analysen der Seite, die der Auswahl ({@link #standing}) oder ein Hinweis. Das Hauptfenster stellt die Fläche in
     * die Leiste und meldet über {@link #analysesShown(boolean)}, ob sie zu sehen ist.
     */
    private final java.awt.CardLayout analysesCards = new java.awt.CardLayout();
    private final JPanel analyses = new JPanel(analysesCards);
    /** Die Reiter der gezeigten Analysen unten in der Seitenleiste ({@link #analysisFoot}). */
    private final JPanel analysesFoot = new JPanel(new BorderLayout());
    /** Die eine Reiterzeile der Analysen für Seite und obere Knoten; die Ebene liefert nur ihre Ansichten. */
    private AnalysisTabRow analysisRow;
    private boolean analysesOpen;
    /** Klappt die Seitenleiste mit den Analysen auf; ohne Hauptfenster (Tests) gelten sie sofort als gezeigt. */
    private Runnable openAnalyses = () -> analysesShown(true);
    /** Ein Sprung aus den Analysen in die Mitte (Fundstelle, Seite): die groß gezeigte Leiste gibt die Mitte frei. */
    private Runnable revealMiddle = () -> { };
    private static final String ANALYSES_PAGE = "page";
    private static final String ANALYSES_NONE = "none";
    static final String NO_ANALYSES = "Wähle links eine Seite oder einen Bereich, um die Analysen dazu zu sehen.";
    /**
     * Die Reiter der Analysen jeder Ebene in der unteren Leiste der Seitenleiste, rechts die ?-Hilfe des gewählten
     * Reiters, darunter rechts „mit Unterseiten“. Die Reiter brechen um, wenn die Leiste zu schmal ist (viele Reiter
     * eines Programms), statt abgeschnitten zu werden.
     */
    private JPanel analysisFoot;
    /** Die Unterreiter von „Begriffe“ (Akronyme, Bezeichner, Verben, Beziehungen) neben denen von „Analysen“. */
    /** Die Unterreiter von „Erweitert“ einer Seite bzw. ohne Seite, in der unteren Leiste. */
    private final TabFoot advancedFoot;
    private final TabFoot wideAdvancedFoot;
    /**
     * „mit Unterseiten“ am Favoriten-Ordner (ADR 0034 §6), dieselbe Checkbox wie an der Seite ({@link
     * Subpages#checkBox}): aus = die direkten Favoriten, an = samt Unterordnern rekursiv. Sie steht im Fuß der breiten
     * Ebene, solange ein Ordner gewählt ist und der Reiter keine eigene Leiste hat; nie gesperrt.
     */
    private final JCheckBox folderSubfolders = Subpages.checkBox();
    private final TabFoot folderFoot = new TabFoot(javax.swing.Box.createHorizontalBox(), folderSubfolders);
    /** Unten rechts in {@link #analysisFoot}: „mit Unterseiten“ der Seite oder des Ordners. */
    private final JPanel standingTrailing = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
    /** Der gewählte Ordner, sonst {@code null}; seine Stellung zeigt {@link #folderSubfolders}. */
    private ExportPanel.Selection.Collection folder;
    /** Bekommt die Stellung, die der Benutzer an {@link #folderSubfolders} wählt. */
    private java.util.function.Consumer<Boolean> onFolderSubfolders = on -> { };
    /** Aktionen von „Analysen“ einer Seite in der Kopfzeile links vom Stern. */
    private final JPanel analysisActions = actionRow();
    /** Aktionen von „Erweitert“ einer Seite in der Kopfzeile links vom Stern. */
    private final JPanel advancedActions = actionRow();

    private static JPanel actionRow() {
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 6, 0));
        row.setOpaque(false);
        return row;
    }
    /** Neben der Lupe in der Podcast-Ansicht: „Transkript kopieren“ des Podcasts der Quellenseite bzw. der Seite. */
    private final JPanel sourceFoot = footRow();
    /** Leseansicht der Quellenseite: nur der Umschalter „Änderungen | Bereich“. */
    private final JPanel readingSourceFoot = footRow();
    /** Podcast-Ansicht des Bereichs: Transkript, Umschalter, Untertitel. */
    private final JPanel areaFoot = footRow();
    /** In der Podcast-Ansicht eines Ordners der Platz für „mit Unterseiten“. */
    private final JPanel areaFolderSlot = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
    private final JPanel pageFoot = footRow();

    private static JPanel footRow() {
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        return row;
    }

    /**
     * Ein Umschalter „Änderungen | Bereich“ mittig in seiner Leiste (Angelo 24.11.2026, wie „Aufträge | Hintergrund“);
     * die Wahl gilt global für alle Seiten, alle Umschalter folgen ihr.
     */
    private JComponent scopeRow(boolean withBook) {
        com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle toggle = AreaSummaryView.scopeToggle();
        scopeToggles.add(toggle);
        toggle.onSelect(this::scopeChosen);
        JPanel row = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        row.setOpaque(false);
        row.add(toggle);
        // Nur die Podcast-Ansicht eines Ordners mit Buch hat an derselben Stelle „Buch | Bereich“ (Architekt
        // 27.11.2026), Wahl je Ordner. Die Leseansicht zeigt den Überblick; das Buch steht unter „Artefakte“ (ADR 0047).
        if (withBook) {
            com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle book = AreaSummaryView.bookToggle();
            bookToggles.add(book);
            book.onSelect(this::bookChosen);
            book.setVisible(false);
            row.add(book);
        }
        scopeRows.add(row);
        return row;
    }

    private void bookChosen(String chosen) {
        ExportPanel.Selection.Collection folder = areaView.folder();
        AreaSummaryView.chooseBook(folder == null ? null : folder.folderId(), AreaSummaryView.BOOK.equals(chosen));
        for (com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle toggle : bookToggles) {
            toggle.select(chosen);
        }
        areaView.bookChanged();
        areaPodcast.bookChanged();
        showDeck();
    }

    /** Ob der gezeigte Ordner ein Buch hat: dann steht unten im Podcast „Buch | Bereich“. */
    private boolean bookAtFolder() {
        return folderShown() && areaView.edition() != null;
    }

    private void scopeChosen(String chosen) {
        AreaSummaryView.choose(AreaSummaryView.AREA.equals(chosen));
        for (com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle toggle : scopeToggles) {
            toggle.select(chosen);
        }
        showDeck();
    }

    /** Ob die Quellenseite statt der Änderungen den Überblick über den Bereich zeigt. */
    private boolean areaShown() {
        return folderShown()
                || SOURCE_CARD.equals(pageCard) && sourceView.sourceId() != null && AreaSummaryView.areaChosen();
    }

    /** Für Tests: der Überblick zum Lesen und zum Hören, der Umschalter der Leseansicht. */
    AreaSummaryView areaView() {
        return areaView;
    }

    AreaSummaryView areaPodcast() {
        return areaPodcast;
    }

    List<com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle> scopeToggles() {
        return scopeToggles;
    }

    /** Für Tests: die Umschalter „Buch | Bereich“ an einem Ordner mit Buch. */
    List<com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle> bookToggles() {
        return bookToggles;
    }
    private VideoViewerPanel originalVideoViewer;
    /** Das gezeigte MP4-Video für seine Standbilder in „Analysen › Bilder“; {@code null} ohne Video. */
    private VideoFrameSource videoFrames;

    /** Woher die Standbilder eines Videos kommen: dieselben Bereichsabrufe wie der Player, zu dieser Seite. */
    private record VideoFrameSource(String sourceId, String pageId,
            com.aresstack.spaceharvester.app.ui.content.viewer.MediaStreamBridge.ChunkSource reader, long size,
            String mediaType) {
    }
    private boolean originalLinkShown;
    private boolean originalProgramShown;
    private final javax.swing.JButton recomputeProgram = new javax.swing.JButton("Programmanalyse berechnen");
    private final javax.swing.JButton recomputeBusiness = new javax.swing.JButton("Fachliche KI-Analyse");
    private final com.aresstack.spaceharvester.app.ui.IconButton searchPrograms =
            com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.create();
    private java.util.concurrent.Future<?> programSearchTask;
    private int programSearchGeneration;
    private ProgramFocus pendingProgramFocus;

    private record ProgramFocus(PageKey key, String rawSha256, int line) { }
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
    /** ?-Hilfe zum Unterreiter „Aussagen“ einer Seite: Kennzeichen, Herkunft, Prüfstatus (A1). */
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton statementsHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, STATEMENTS, StatementProvenance.HELP);
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton readingHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, "Leseansicht", READING_HELP);
    /** Maustaste Zurück (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_BACK_BUTTON}). */
    static final int MOUSE_BACK_BUTTON = com.aresstack.spaceharvester.app.ui.controls.MouseNavigation.BACK_BUTTON;
    /** Client-Eigenschaft einer Ansicht mit eigenem Zurück/Vor (etwa die Suche): dort blättert die Seite nicht. */
    public static final String OWN_MOUSE_NAVIGATION = com.aresstack.spaceharvester.app.ui.controls.MouseNavigation.OWN;
    /** Maustaste Vor (Daumentaste) wie im EAC ({@code KnowledgeReaderPanel.MOUSE_FORWARD_BUTTON}). */
    static final int MOUSE_FORWARD_BUTTON = com.aresstack.spaceharvester.app.ui.controls.MouseNavigation.FORWARD_BUTTON;
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
    private Runnable beforeHistoryNavigation = () -> { };
    private boolean searchFront;
    private com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator workspaceHistory;
    private java.util.function.Consumer<Place> locationVisited = place -> { };
    /** Zählt Link-Aktivierungen; nur die Auflösung des zuletzt geklickten Links wirkt. */
    private int linkRequest;
    /** Anker des Links, der die gerade ladende Seite geöffnet hat; die Leseansicht rollt dorthin. */
    private String pendingAnchor;
    /** Das eine Zurück (gedrückt halten: Verlauf vor und zurück); Vor über Maustaste und Verlauf. */
    private final com.aresstack.spaceharvester.app.ui.controls.BackButton backButton = new com.aresstack.spaceharvester.app.ui.controls.BackButton("Zurück (Maustaste Zurück)");
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
        // Spaces in der Zusammenfassung sind anklickbar: links im Baum wählen.
        sourceView.onOpenSpace((spaceSource, spaceKey) -> {
            if (context.navigator() != null) {
                context.navigator().openSpace(spaceSource, spaceKey);
            }
        });
        for (SourceUpdatesView view : List.of(sourceView, sourceChanges)) {
            view.onRetry((retrySource, spaces) -> {
                if (context.navigator() != null) {
                    // Beide Reiter verfolgen die Wiederholung: ist sie durch, aktualisiert sich die offene Ansicht.
                    context.navigator().retrySyncs(retrySource, spaces, jobs -> {
                        sourceView.followRetry(retrySource, jobs);
                        sourceChanges.followRetry(retrySource, jobs);
                    });
                }
            });
        }
        // Beide Reiter zeigen dasselbe Update: Wer es in einem wählt, sieht es im anderen auch.
        sourceView.onSelected(sourceChanges::select);
        sourceChanges.podcastEvents(context.events()::publish);
        sourceChanges.onSelected(sourceView::select);
        // Der Podcast der Quelle bzw. des Spaces steht in der Podcast-Ansicht und erzählt die Zusammenfassung der
        // Leseansicht, ohne sie neu zu erzeugen; die Leseansicht markiert das laufende Thema mit (Angelo 23.11.2026).
        sourceView.onSummary(sourceChanges::adopt);
        sourceChanges.onFollow(sourceView::followPodcast);
        this.areaView = new AreaSummaryView(() -> context.runtime() == null ? null
                : context.runtime().workspace(), AreaSummaryView.Mode.READ);
        this.areaPodcast = new AreaSummaryView(() -> context.runtime() == null ? null
                : context.runtime().workspace(), AreaSummaryView.Mode.LISTEN);
        for (AreaSummaryView view : List.of(areaView, areaPodcast)) {
            view.onOpen((pageSource, pageId) -> {
                if (context.navigator() != null) {
                    context.navigator().openPage(pageSource, pageId);
                } else {
                    load(pageSource, pageId, false);
                }
            });
            view.onOpenSpace((spaceSource, spaceKey) -> {
                if (context.navigator() != null) {
                    context.navigator().openSpace(spaceSource, spaceKey);
                }
            });
        }
        areaPodcast.podcastEvents(context.events()::publish);
        areaView.onBook(() -> javax.swing.SwingUtilities.invokeLater(this::showDeck));
        // Hat ein Favoritenordner ein Buch, zeigt die Leseansicht dessen neueste Auflage (Architekt 27.11.2026).
        java.util.function.Function<String, com.aresstack.spaceharvester.app.research.BookEditions.Edition> latest =
                folderId -> context.settings() == null || context.settings().storage() == null ? null
                        : com.aresstack.spaceharvester.app.research.BookEditions.latest(
                                context.settings().storage().dataDirectory(), folderId).orElse(null);
        areaView.books(latest);
        // Die Podcast-Ansicht liest unter „Buch“ dieselbe Auflage vor (ADR 0044: der Podcast nutzt das Buch).
        areaPodcast.books(latest);
        // Geänderte Seiten seit der Auflage: gemerkter Stand gegen den jetzigen in der Ablage (kein Abfragen; die
        // Ansicht prüft nur, wenn eine Quelle eine Änderung meldet oder der Ordner neu gezeigt wird).
        // Die Leseansicht zeigt kein Buch mehr; „Neue Auflage“ steht an der Buchzeile unter „Artefakte“ (ADR 0047).
        areaPodcast.staleness((folderId, shown) -> context.bookArtifacts().stale(folderId, shown));
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
        // Die lokalen Artefakte der Auswahl auf der leeren Seite von „Export“ (ADR 0047 §5a).
        this.localArtifacts = new LocalArtifacts(context);
        localArtifacts.onOpen(() -> tabs.select(READING), () -> tabs.select(READ));
        export.extra(localArtifacts.component());
        this.chat = new ChatPanel(context);
        footTools.setOpaque(false);
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
        acronymBar.setOpaque(false);
        acronymBar.add(acronymHint, BorderLayout.CENTER);
        readerPanel.add(acronymBar, BorderLayout.NORTH);
        // Der Banner liegt als eigene Ebene über dem Lesetext und nimmt ihm keine Breite.
        readerPage = new NavigableDocumentPage(Comic.scroll(reader), reader, READER_PADDING_TOP);
        pagePodcast = new PodcastControl<>(this::pagePodcasts, new SourceUpdatesView.Runner() {
            @Override
            public <T> void run(java.util.concurrent.Callable<T> work, Consumer<T> onSuccess,
                                Consumer<Throwable> onFailure) {
                Background.run(work, onSuccess, onFailure);
            }
        }, PodcastControl.Labels.PAGE);
        pagePodcast.events(context.events()::publish);
        // Bilder neben dem Text, laufende Stelle gelb, das Bild des Kapitels geht aus seiner Stelle auf (23.11.2026).
        pagePictures = new PagePodcastPictures(reader);
        pagePodcast.follow(spans -> {
            pageSpans = spans(spans);
            pagePictures.mark(pageSpans);
        });
        pagePodcast.cover(covered -> {
            pageCoverage = covered.map(PageDetailPanel::spans);
            pagePictures.cover(pageCoverage);
        });
        pagePodcast.cue(new PodcastControl.Cue() {
            @Override
            public boolean open(int topic) {
                return pagePictures.open(topic);
            }

            @Override
            public void close() {
                pagePictures.close();
            }
        });
        pagePictures.onClosedByUser(pagePodcast::pictureClosed);
        pagePodcast.stored(this::loadPagePictures);
        // Links neben der Lupe das Kopieren, rechtsbündig die Schalter „Untertitel“ (Angelo
        // 22.11.2026) und „Minimieren | Aufklappen“ (Angelo 27.11.2026).
        pageFoot.add(Comic.row(pagePodcast.transcriptCopy()), BorderLayout.WEST);
        pageFoot.add(pagePodcast.footTools(), BorderLayout.EAST);
        sourceFoot.add(Comic.row(sourceChanges.podcast().transcriptCopy()), BorderLayout.WEST);
        sourceFoot.add(sourceChanges.podcast().footTools(), BorderLayout.EAST);
        sourceFoot.add(scopeRow(false), BorderLayout.CENTER);
        readingSourceFoot.add(scopeRow(false), BorderLayout.CENTER);
        areaFoot.add(Comic.row(areaPodcast.podcast().transcriptCopy()), BorderLayout.WEST);
        // „mit Unterseiten“ eines Ordners steht in jeder Ansicht an derselben Stelle: ganz rechts in der Ecke, wie in
        // der Ordnerleiste und unter „Analysen“ (TabFoot); früher hier mittig, so sprang sie (Angelo 27.11.2026).
        JPanel areaEast = new JPanel(new BorderLayout());
        areaEast.setOpaque(false);
        areaEast.add(areaPodcast.podcast().footTools(), BorderLayout.CENTER);
        areaFolderSlot.setOpaque(false);
        areaEast.add(areaFolderSlot, BorderLayout.EAST);
        areaFoot.add(areaEast, BorderLayout.EAST);
        areaFoot.add(scopeRow(true), BorderLayout.CENTER);
        readerPage.setOrb(pagePodcast.orb());
        // Der Chat liest mit derselben Sprachausgabe vor; sein Knopf steht auf der Höhe dieser Knöpfe (Angelo 23.11.2026).
        chat.useReadAloud(this::readAloudBinding);
        chat.alignReadAloudWith(readingPane.readAloud().orb(), pagePodcast.orb());
        textBusy = new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(readerPage);
        textBusy.setHighlight(Comic.BLUE);
        textBusy.setVeilColor(Comic.SURFACE);
        textContents.add(textBusy, TEXT_READER_CARD);
        textContents.add(videoTranscript, TEXT_VIDEO_TRANSCRIPT_CARD);
        textOriginal.setOpaque(false);
        textContents.add(textOriginal, TEXT_ORIGINAL_CARD);
        readerPanel.add(textContents, BorderLayout.CENTER);
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
        // Solange die Liste lädt, dreht sich die Sanduhr der Suche darüber, ohne Esc in der Mitte.
        assetsBusy.setHighlight(Comic.BLUE);
        assetsBusy.setVeilColor(Comic.SURFACE);
        attachmentList.add(assetsBusy, BorderLayout.CENTER);
        // Die Knöpfe stehen in der Leiste mit der Lupe, nicht in einer eigenen Zeile darüber (Angelo 27.11.2026).
        attachmentsFoot.add(Comic.row(previewAsset, harvest, saveAsset), BorderLayout.WEST);
        attachmentsFoot.add(showImages, BorderLayout.EAST);
        showImages.setToolTipText("Bilder und SVG-Grafiken in der Liste zeigen; gilt für alle Seiten");
        showImages.setSelected(com.aresstack.spaceharvester.app.ui.WindowSizes.recallInt(SHOW_IMAGES_KEY, 0) == 1);
        assetsModel.showImages(showImages.isSelected());
        showImages.addActionListener(e -> {
            com.aresstack.spaceharvester.app.ui.WindowSizes.rememberInt(SHOW_IMAGES_KEY,
                    showImages.isSelected() ? 1 : 0);
            assetsModel.showImages(showImages.isSelected());
            updateAssetsHint();
            updatePreviewEnabled();
        });
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
        advancedTabs.tab(PROPERTIES, Comic.scroll(padded(properties)))
                .tab(TECHNICAL, Comic.scroll(padded(technical)))
                .tab(RAW, rawTabs);
        // Die Unterreiter stehen mittig in der unteren Leiste mit der Lupe (Angelo 23.11.2026); ihre Aktionen in der
        // gemeinsamen Kopfzeile links vom Stern, solange „Erweitert“ eine Seite zeigt.
        advancedActions.add(refresh);
        advancedActions.add(saveRaw);
        advancedActions.add(recompute);
        advancedFoot = new TabFoot(advancedTabs.detachBar(), null);
        advancedTabs.select(PROPERTIES);
        advancedTabs.onSelect(name -> { });
        // Ohne Seite: dieselben Unterreiter; „Eigenschaften“ zeigt die der Quelle (oder aller Quellen),
        // „Technische Angaben“ und „Originaldaten“ bleiben dort leer.
        wideAdvanced.tab(PROPERTIES, sourceProperties)
                .tab(TECHNICAL, new JPanel())
                .tab(RAW, new JPanel());
        wideAdvanced.select(PROPERTIES);
        wideAdvanced.onSelect(name -> showDeck());
        wideAdvancedFoot = new TabFoot(wideAdvanced.detachBar(), null);
        folderSubfolders.addActionListener(e -> {
            if (folder != null) {
                onFolderSubfolders.accept(folderSubfolders.isSelected());
            }
        });
        JPanel advanced = new JPanel(new BorderLayout(0, 8));
        advanced.setOpaque(false);
        advanced.add(advancedTabs, BorderLayout.CENTER);

        // Leseansicht zuerst und damit Standard; ein gewählter Reiter bleibt beim Laden der nächsten Seite.
        // Fachliche Analysen der Seite (#499) unter einem Hauptreiter; die Ansichten selbst bleiben dieselben.
        statements.setOpaque(false);
        statements.add(placeholder(STATEMENTS_PLACEHOLDER), BorderLayout.CENTER);
        graph.setOpaque(false);
        graph.add(placeholder(GRAPH_PLACEHOLDER), BorderLayout.CENTER);
        analysisTabs.tab(PASSAGES, withBelow(passages, passagesBelow))
                .tab(TERMS, terms)
                .tab(REFERENCES, references)
                .tab(IMAGES, withBelow(images, imagesBelow))
                .tab(STATEMENTS, withBelow(statements, statementsBelow))
                .tab(GRAPH, withBelow(graph, graphBelow))
                .tab(RELATIONS, analysisView);
        programAnalysisTabs = new ProgramAnalysisTabs(analysisTabs);
        // Die Unterreiter mittig, „mit Unterseiten“ rechts in der unteren Leiste mit der Lupe (Angelo 23.11.2026); die
        // übrigen Aktionen in der Kopfzeile links vom Stern. „Synonyme vorschlagen“ und „Neue Beziehung …“ nur, solange
        // „Beziehungen“ gewählt ist (BG2b).
        analysisActions.add(searchPrograms);
        searchPrograms.setVisible(false);
        searchPrograms.addActionListener(event -> searchStoredPrograms());
        analysisActions.add(recomputeProgram);
        recomputeProgram.setVisible(false);
        recomputeProgram.addActionListener(event -> {
            if (page == null) return;
            String processor = com.aresstack.spaceharvester.app.runtime.program.StoredNaturalSource.read(page.snapshot()).isPresent()
                    ? com.aresstack.spaceharvester.app.runtime.program.NaturalProgramEnrichment.ID
                    : com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment.source(page.snapshot()).isPresent()
                        ? com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment.ID
                        : com.aresstack.spaceharvester.app.runtime.program.CobolProgramEnrichment.ID;
            recompute(processor);
        });
        analysisActions.add(recomputeBusiness);
        recomputeBusiness.setVisible(false);
        recomputeBusiness.addActionListener(event -> analyzeBusiness());
        analysisActions.add(storeField);
        analysisActions.add(analysisView.proposeIcon());
        analysisActions.add(analysisView.createIcon());
        analysisTabs.detachBar();
        terms.selectableKinds();
        storeField.onChange(this::switchStore);
        originalContent.setOpaque(false);
        fileContent.setOpaque(false);
        originalContent.add(readingPane, ORIGINAL_PAGE_CARD);
        originalContent.add(fileContent, ORIGINAL_FILE_CARD);
        storeBusy = new com.aresstack.spaceharvester.app.ui.controls.BusyOverlay(analysisTabs);
        analyses.setOpaque(false);
        analysesFoot.setOpaque(false);
        analyses.add(placeholder(NO_ANALYSES), ANALYSES_NONE);
        analyses.add(storeBusy, ANALYSES_PAGE);
        tabs.tab(READING, originalContent)
                .tab(READ, readerPanel)
                .tab(ATTACHMENTS, attachmentsPanel)
                .tab(ADVANCED, advanced)
                // Der Export hängt nicht an der gezeigten Seite, sondern an der Auswahl links; er liegt deshalb als
                // eigene Karte neben Seite und Hinweis, der Reiter hält nur den Platz.
                .tab(EXPORT, new JPanel());
        // Die Hauptreiter wandern als Symbolknöpfe in die Icon-Leiste oben (Angelo, 13.11.2026);
        // die Unterreiter von „Analysen“ rücken dadurch an die Stelle der bisherigen Reiterzeile.
        java.util.Map<String, Icon> icons = new java.util.LinkedHashMap<>();
        icons.put(READING, PageTabIcons.reading());
        icons.put(READ, PageTabIcons.text());
        icons.put(ATTACHMENTS, PageTabIcons.attachments());
        icons.put(ADVANCED, PageTabIcons.properties());
        icons.put(EXPORT, PageTabIcons.export());
        tabIcons = tabs.iconBar(icons);
        // Nachladen nur für den sichtbaren Reiter: beim Wechsel des Haupt- wie des Unterreiters.
        tabs.onSelect(name -> {
            showDeck();
            loadVisible();
            showTabStatus();
        });
        analysisTabs.onSelect(name -> {
            if (RELATIONS.equals(name)) {
                loadBelowRelations();
            }
            loadBelowSection(name);
            showRelations();
            if (analysesOpen) {
                loadVisible();
                showTabStatus();
            }
        });
        passages.onStatus(text -> tabStatus(PASSAGES, text));
        images.onStatus(text -> tabStatus(IMAGES, text));
        references.onStatus(text -> tabStatus(REFERENCES, text));
        statementsView.onStatus(text -> tabStatus(STATEMENTS, text));
        // Meldungen mit Ampel und Link (D3: „Aufträge“ öffnet die Auftragsverwaltung im Reiter „Hintergrund“)
        statementsView.onNote(note -> tabNote(STATEMENTS, note));
        statementsView.onShowJob(jobId -> context.navigator().showJob(jobId));
        sourceView.onStatus(text -> tabStatus(READING, text));
        areaView.onStatus(text -> tabStatus(READING, text));
        areaPodcast.onStatus(text -> tabStatus(READ, text));
        standing.onStatus(text -> tabStatus(STAND, text));
        standing.onJump(this::openAt);
        // Nach jedem (Neu-)Start, etwa nach dem Speichern der Wörter für den Stand, liest „Stand“ neu (Beobachter, kein
        // Zeitgeber); verdeckt erst beim nächsten Zeigen.
        if (context != null) {
            context.onChange(ignored -> {
                standing.settingsChanged();
                // Eine verfolgte Gruppe „Wissensgraph erzeugen“ auf der neuen Laufzeit weiter verfolgen (W5).
                graphView.runtimeChanged();
                storeChanged();
            });
        }
        shownStore = storeSetting();
        storeField.show(shownStore);
        sourceChanges.onStatus(text -> tabStatus(READ, text));
        // Mit Ampel und Link: „Wissensgraph erzeugen“ (W5) meldet gelbe Hinweise, ✕ und „Hintergrund“.
        graphView.onNote(note -> {
            graphStatus = note;
            showGraphStatus();
        });
        graphView.onShowJob(jobId -> context.navigator().showJob(jobId));
        graphView.onGenerated(() -> {
            statementsView.reload();
            loadVerbs();
        });
        graphView.onSubpages(this::belowGraphLoaded);
        // Lauf-, Erfolgs- und Infomeldungen gehen in die Statuszeile des Hauptfensters; die Platte zeigt nur Probleme.
        feedback.statusLine((text, tone) -> context.events().publish(
                new com.aresstack.spaceharvester.app.event.StatusMessageEvent(text,
                        tone == Comic.BLUE ? Integer.MAX_VALUE : STATUS_MS,
                        com.aresstack.spaceharvester.app.event.Severity.INFO)));
        breadcrumbs.onOpen(this::openLinked);
        references.onOpen(this::openLinked);
        terms.onJump(this::jumpTo);
        terms.onOpen(hit -> openAt(new StandingTab.Target(hit.sourceId(), hit.pageId(), -1, -1)));
        analysisView.below().onOpen(this::openLinked);
        imagesBelow.onOpen(this::openLinked);
        passagesBelow.onOpen(this::openLinked);
        statementsBelow.onOpen(this::openLinked);
        // Der Reiter „Graph“ bleibt beim Laden gewählt; die Unterseite öffnet sich also in ihrem Graphen.
        graphBelow.onOpen(this::openLinked);
        // Eine neue oder geänderte Beziehung kann eine der Unterseiten betreffen: unten neu laden.
        analysisView.onRelationsChanged(() -> {
            belowRelationsFor = null;
            if (RELATIONS.equals(analysisTabs.selected())) {
                loadBelowRelations();
            }
        });
        subpages.onChange(this::subpagesChanged);
        passages.onJump(this::jumpTo);
        statementsView.onJump(this::jumpTo);
        statementsView.withSubpages(subpages::active);
        statementsTab(statementsView);
        graphView.onJump(this::jumpTo);
        // Overlay „Belege“ der Fachsicht im Seitengraphen (W4b): der Seitentitel öffnet die Seite an der Stelle.
        graphView.onEvidenceOpen(target -> openAt(new StandingTab.Target(target.sourceId(), target.pageId(),
                target.start(), target.end())));
        statementsView.onAnalysed(() -> {
            graphView.analysisChanged();
            loadVerbs();
        });
        graphView.onDeleted(statementsView::reload);
        // Der leere Graph bietet die Analyse an; sie läuft unter „Aussagen“, wo auch ihr Fortschritt steht.
        graphView.onAnalyze(() -> {
            analysisTabs.select(STATEMENTS);
            statementsView.analyzeNow();
        });
        knowledgeGraphTab(graphView);
        images.onDescribe(() -> describeImages(false));
        images.onDescribeOne(this::describeImage);
        // Erzeugte Transkripte bleiben: gespeichert als virtueller Anhang, beim nächsten Öffnen wieder gezeigt.
        videoTranscript.onTranscript(this::storeTranscript);
        // Das Mikrofon transkribiert mit dem in der Auswahl gewählten Modell; fehlt es, erst fragen und laden.
        videoTranscript.modelGate((model, progress, then) -> context.whisperModel().ensure(model, this, progress,
                progress, then));
        images.onOpen(this::openImage);
        images.onDescribeOnce(() -> describeImages(true));
        images.onRelease(this::releaseSourceAndDescribe);

        refresh.addActionListener(e -> load(sourceId, pageId, true));
        saveRaw.addActionListener(e -> saveRaw());
        browse.addActionListener(e -> browse());
        bookmark.addActionListener(e -> copyBookmark());
        star.addActionListener(e -> toggleFavorite());
        // Rechtsklick auf den Stern: nur die Gruppen und „Neue Gruppe …“ (Angelo 27.11.2026).
        star.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                starMenu(e);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                starMenu(e);
            }
        });
        context.favorites().onChange(() -> javax.swing.SwingUtilities.invokeLater(this::updateStar));
        harvest.addActionListener(e -> harvestAssets());
        saveAsset.addActionListener(e -> saveAsset());
        previewAsset.addActionListener(e -> openAttachment());
        recompute.addActionListener(e -> recompute());
        // Der Stand „Veraltet“ (auch „Fehlgeschlagen“, „Nicht durchgeführt“) im Analysebereich ist zugleich der Knopf.
        terms.onRecompute(this::recompute);
        passages.onRecompute(this::recompute);
        readingPane.onLink(this::linkActivated);
        readingPane.onViewerLook(() -> Comic.viewerLook(context.settings() == null
                ? com.aresstack.spaceharvester.app.settings.ViewerBackground.DEFAULT
                : context.settings().viewerBackground()));
        backButton.navigator(new com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator() {
            @Override
            public com.aresstack.spaceharvester.app.ui.controls.BackButton.History history() {
                return documentHistory();
            }

            @Override
            public void go(int steps) {
                navigateHistory(steps);
            }
        });
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
        overviewGraph.onOpen(target -> openAt(new StandingTab.Target(target.sourceId(), target.pageId(),
                target.start(), target.end())));
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
        // Ohne Seite: die Beziehungen der Auswahl als Reiter beim Stand, mit ihren beiden Aktionen.
        standing.tab(RELATIONS, wideAnalysis, wideAnalysis::shown, wideAnalysis.proposeButton(),
                wideAnalysis.createButton());
        glossaryHelp.setVisible(false);
        graphHelp.setVisible(false);
        standingTrailing.setOpaque(false);
        // Die ?-Hilfe der Reiter steht unten rechts in der Ecke der Leiste, wie bei den Aufträgen (Angelo 27.11.2026).
        standingTrailing.add(subpages.box());
        // Eine Reiterzeile für jede Ebene (Angelo 27.11.2026): alle Reiter in einer Reihenfolge, je Name ein Symbol,
        // der Name im Tooltip. Gleiche Reiter stehen an derselben Stelle, ob Seite oder oberer Knoten; Reiter eines
        // Programms (Kontrollfluss …) kommen dahinter und behalten ihren Text.
        java.util.Map<String, Icon> analysisIcons = new java.util.LinkedHashMap<>();
        analysisIcons.put(PASSAGES, AnalysisTabIcons.passages());
        analysisIcons.put(TERMS, AnalysisTabIcons.terms());
        analysisIcons.put(REFERENCES, AnalysisTabIcons.references());
        analysisIcons.put(IMAGES, AnalysisTabIcons.images());
        analysisIcons.put(STATEMENTS, AnalysisTabIcons.statements());
        analysisIcons.put(StandingTab.READING, AnalysisTabIcons.readFirst());
        analysisIcons.put(StandingTab.CONFLICTS, AnalysisTabIcons.conflicts());
        analysisIcons.put(StandingTab.GLOSSARY, AnalysisTabIcons.glossary());
        analysisIcons.put(GRAPH, AnalysisTabIcons.graph());
        analysisIcons.put(RELATIONS, AnalysisTabIcons.relations());
        analysisRow = new AnalysisTabRow(analysisIcons);
        analysisRow.follow(analysisTabs);
        analysisRow.follow(standing.tabs());
        standing.onConflicts(found -> analysisRow.mark(StandingTab.CONFLICTS, found == 0 ? null : String.valueOf(found)));
        standing.tabs().detachBar();
        JPanel helps = new JPanel(new BorderLayout());
        helps.setOpaque(false);
        helps.add(Comic.row(statementsHelp, standingHelp, glossaryHelp, graphHelp), BorderLayout.NORTH);
        analysisFoot = new JPanel(new BorderLayout(8, 0));
        analysisFoot.setOpaque(false);
        analysisFoot.add(analysisRow.bar(), BorderLayout.CENTER);
        analysisFoot.add(helps, BorderLayout.EAST);
        analysisFoot.add(standingTrailing, BorderLayout.SOUTH);
        analysesFoot.add(analysisFoot, BorderLayout.CENTER);
        analyses.add(standing, STAND_CARD);
        deck.add(wideAdvanced, WIDE_ADVANCED_CARD);
        deck.add(sourceView, SOURCE_CARD);
        deck.add(sourceChanges, SOURCE_TEXT_CARD);
        deck.add(areaView, AREA_CARD);
        deck.add(areaPodcast, AREA_TEXT_CARD);
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
        programAnalysisTabs.reset();
        recomputeProgram.setVisible(false);
        recomputeBusiness.setVisible(false);
        searchPrograms.setVisible(false);
        cancelProgramSearch();
        pendingProgramFocus = null;
        programShown = false;
        subpagesShown();
        // Der Graph verlässt die Seite vor den Unterseiten: Deren Abmeldung soll für die alte Seite nichts mehr laden.
        graphView.page(null);
        subpages.page(null, null, null, null);
        pagePodcast.summary(null);
        assetsLoaded = false;
        readingLoaded = false;
        referencesLoaded = false;
        imagesLoaded = false;
        leaveHarvest();
        leavePrefetch(null);
        readingPane.clear(" ");
        closeOriginalFile();
        breadcrumbs.clear();
        positionShown.accept(null);
        chat.reading(null);
        references.message(" ");
        views = List.of();
        showReader(null, false);
        acronymHint.setText(" ");
        closeAttachmentOverlay();
        assetsModel.set(null);
        assetsBusy.setBusy(false);
        analysisView.steps(List.of(), null);
        analysisView.scope(null);
        properties.reset();
        technical.reset();
        ProgressiveText.clear(raw);
        ProgressiveText.clear(storage);
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
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite favorite = shownFavorite();
        if (favorite != null) {
            // Derselbe Weg wie der Stern im Baum (ADR 0034): Pfad aus der Position, Zielordner aus der Favoritenansicht.
            context.favorites().toggle(favorite);
        }
    }

    /** Der Favorit der gezeigten Seite mit ihrem Pfad, oder {@code null} ohne Seite oder Space. */
    private com.aresstack.spaceharvester.app.ui.Favorites.Favorite shownFavorite() {
        if (page == null || sourceId == null || pageId == null) {
            return null;
        }
        String spaceKey = shownPosition != null && shownPosition.spaceKey() != null ? shownPosition.spaceKey()
                : page.header().metadata().partitionKey();
        if (spaceKey == null) {
            return null;
        }
        return com.aresstack.spaceharvester.app.ui.Favorites.Favorite.page(sourceId, spaceKey, pageId,
                page.header().metadata().title(), shownPosition, sourceTitle(sourceId));
    }

    /** Rechtsklick auf den Stern: die Gruppen der Favoriten; die Wahl macht die Seite zum Favoriten darin. */
    private void starMenu(java.awt.event.MouseEvent e) {
        com.aresstack.spaceharvester.app.ui.Favorites.Favorite favorite = e.isPopupTrigger() ? shownFavorite() : null;
        if (favorite == null) {
            return;
        }
        var favorites = context.favorites();
        String current = favorites.groupOf(favorite.sourceId(), favorite.spaceKey(), favorite.pageId()).orElse(null);
        com.aresstack.spaceharvester.app.ui.GroupMenu.popup(star, favorites, current,
                id -> favorites.assign(favorite, id)).show(star, e.getX(), e.getY());
    }

    private String sourceTitle(String id) {
        return context.sourceTitle(id);
    }

    /** Gefüllt, wenn die gezeigte Seite ein Favorit ist; ohne Seite gesperrt. */
    private void updateStar() {
        boolean shown = page != null && sourceId != null && pageId != null;
        String spaceKey = !shown ? null : shownPosition != null && shownPosition.spaceKey() != null
                ? shownPosition.spaceKey() : page.header().metadata().partitionKey();
        boolean favorite = spaceKey != null && context.favorites().contains(sourceId, spaceKey, pageId);
        star.setEnabled(shown && spaceKey != null);
        star.setIcon(com.aresstack.spaceharvester.app.ui.GroupMenu.star(favorite, favorite
                ? context.favorites().colorOf(sourceId, spaceKey, pageId).orElse(null) : null));
        star.setToolTipText((favorite ? "Favorit entfernen" : "Als Favorit markieren") + " · Rechtsklick: Gruppe zuordnen");
        star.getAccessibleContext().setAccessibleName(favorite ? "Favorit entfernen" : "Als Favorit markieren");
    }

    /** Für Tests: der Stern im Kopf. */
    javax.swing.AbstractButton starButton() {
        return star;
    }

    /** Seite oder Hinweis merken; sichtbar wird es erst, wenn „Export“ nicht gewählt ist. */
    private void showCard(String card) {
        if (!SOURCE_CARD.equals(card)) {
            // Keine Quellenseite mehr: Anmeldung beim Änderungsprotokoll endet, die leere Karte nennt wieder die Seite.
            sourceView.source(null, null);
            sourceChanges.source(null, null);
            areaView.area(null, null);
            areaPodcast.area(null, null);
            sourceProperties.source(null);
            empty.setText(EMPTY_PAGE);
        }
        pageCard = card;
        showDeck();
    }

    private void showDeck() {
        String selected = tabs.selected();
        // Die Werkzeuge der Updates stehen nur da, wo die Updates zu sehen sind.
        boolean area = areaShown();
        // Ein Ordner hat keine Änderungen: dort kein „Änderungen | Bereich“, im Podcast mit Buch aber „Buch | Bereich“.
        boolean folderNow = folderShown();
        boolean book = bookAtFolder();
        ExportPanel.Selection.Collection folder = areaView.folder();
        String bookChoice = AreaSummaryView.bookChosen(folder == null ? null : folder.folderId())
                ? AreaSummaryView.BOOK : AreaSummaryView.AREA;
        for (JComponent row : scopeRows) {
            boolean hasBook = false;
            for (java.awt.Component part : row.getComponents()) {
                boolean isBook = bookToggles.contains(part);
                hasBook |= isBook;
                part.setVisible(isBook ? book : !folderNow);
            }
            row.setVisible(!folderNow || book && hasBook);
        }
        bookToggles.forEach(toggle -> toggle.select(bookChoice));
        sourceView.tools().setVisible(SOURCE_CARD.equals(pageCard) && READING.equals(selected) && !area);
        sourceChanges.tools().setVisible(SOURCE_CARD.equals(pageCard) && READ.equals(selected) && !area);
        areaView.tools().setVisible(SOURCE_CARD.equals(pageCard) && READING.equals(selected) && area);
        areaPodcast.tools().setVisible(SOURCE_CARD.equals(pageCard) && READ.equals(selected) && area);
        sourceProperties.tools().setVisible(SOURCE_CARD.equals(pageCard) && ADVANCED.equals(selected) && wide
                && PROPERTIES.equals(wideAdvanced.selected()));
        // Die Hilfe einer Ansicht ist zu sehen, solange ihre Werkzeuge im Kopf zu sehen sind.
        sourceView.help().setVisible(sourceView.tools().isVisible());
        sourceChanges.help().setVisible(sourceChanges.tools().isVisible());
        areaView.help().setVisible(areaView.tools().isVisible());
        areaPodcast.help().setVisible(areaPodcast.tools().isVisible());
        sourceProperties.help().setVisible(sourceProperties.tools().isVisible());
        // Jede Ansicht hat ihr „?“ (Angelo 27.11.2026): Wo keine eigene Hilfe einer Quelle oder eines Bereichs
        // steht, erklärt die des Reiters, was er zeigt.
        boolean other = sourceView.help().isVisible() || sourceChanges.help().isVisible()
                || areaView.help().isVisible() || areaPodcast.help().isVisible()
                || sourceProperties.help().isVisible();
        readingHelp.setVisible(!other && READING.equals(selected));
        podcastHelp.setVisible(!other && READ.equals(selected));
        attachmentsHelp.setVisible(!other && ATTACHMENTS.equals(selected));
        infoHelp.setVisible(!other && ADVANCED.equals(selected));
        exportHelp.setVisible(!other && EXPORT.equals(selected));
        reload.setEnabled(pageId != null || SOURCE_CARD.equals(pageCard));
        analysisActions.setVisible(PAGE.equals(pageCard) && analysesOpen && !wide);
        advancedActions.setVisible(PAGE.equals(pageCard) && ADVANCED.equals(selected) && !wide);
        // Analysen ohne Seite (Wissensquellen-Kopf, Quelle, Space, Ordner): der Stand der Auswahl links, mit den
        // Unterreitern „Aussagen“, „Zuerst lesen“, „Mögliche Widersprüche“ (Angelo 17.11.2026, kein eigenes Symbol).
        boolean standingShown = standingShown();
        standing.shown(standingShown);
        showOverviewHelp();
        updateFootTools(selected);
        if (sourceMenuShown()) {
            more.setEnabled(true);
        } else if (SOURCE_CARD.equals(pageCard) || wide && pageId == null) {
            more.setEnabled(false);
        }
        if (artifactTabs != null && export.selection() != null) {
            more.setEnabled(true); // „Neuer Export“ steht in jedem Fall im Menü
        }
        heading.revalidate();
        heading.repaint();
        String page = pageCard;
        if (SOURCE_CARD.equals(page)) {
            // Quellenseite: „Leseansicht“ die KI-Zusammenfassung, „Text“ die Updates; die übrigen Reiter noch leer.
            // Mit „Bereich“ statt der Änderungen der Überblick über den ganzen Bereich (Angelo 24.11.2026).
            page = READING.equals(selected) ? area ? AREA_CARD : SOURCE_CARD
                    : READ.equals(selected) ? area ? AREA_TEXT_CARD : SOURCE_TEXT_CARD : EMPTY;
        }
        boolean wideAdvancedShown = ADVANCED.equals(selected) && wide;
        cards.show(deck, EXPORT.equals(selected) ? EXPORT_CARD : wideAdvancedShown ? WIDE_ADVANCED_CARD : page);
        analysesCards.show(analyses, wide ? STAND_CARD : PAGE.equals(pageCard) ? ANALYSES_PAGE : ANALYSES_NONE);
        showRelations();
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
        ChangeLevel level = ChangeLevel.of(spaceKey);
        String shownTitle = title == null || title.isBlank() ? spaceKey == null ? sourceId
                : level.subtree() ? level.rootPageId() : spaceKey : title;
        remember(Place.level(sourceId, spaceKey, shownTitle));
        clearState();
        this.title.setText(shownTitle);
        empty.setText(EMPTY_SOURCE);
        sourceView.source(sourceId, spaceKey, shownTitle);
        sourceChanges.source(sourceId, spaceKey, shownTitle);
        areaView.area(sourceId, level.spaceKey(), level.rootPageId());
        areaPodcast.area(sourceId, level.spaceKey(), level.rootPageId());
        // Kennzahlen und Einstellungen gibt es vorerst nur für die ganze Quelle.
        sourceProperties.source(spaceKey == null ? sourceId : null);
        pageCard = SOURCE_CARD;
        showDeck();
    }

    /**
     * Ein Knoten ohne eigenen Inhalt, etwa ein Ordner in SharePoint, BSCW oder lokalen Dateien in jeder Tiefe (Angelo
     * 26.11.2026): dieselbe Seite wie Quelle und Space, mit „Änderungen | Bereich“ für den Teilbaum unter ihm, statt die
     * vorher geöffnete Seite stehen zu lassen. Eine KI-Zusammenfassung entsteht dort nur per Klick.
     */
    public void showContainer(String sourceId, String spaceKey, String pageId, String title) {
        showSource(sourceId, ChangeLevel.key(spaceKey, pageId), title);
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
        areaView.area(null, null);
        areaPodcast.area(null, null);
        sourceProperties.all(sourceIds);
        pageCard = SOURCE_CARD;
        showDeck();
    }

    /**
     * Ein Favoriten-Ordner (ADR 0034 §7, Paket 5c): Lese- und Podcast-Ansicht zeigen den Überblick über seine Favoriten,
     * mit derselben Ansicht wie „Bereich“ an Quelle und Space; Änderungen gibt es am Ordner nicht, also keinen
     * Umschalter. Die Überschrift ist der Ordnername.
     */
    public void showFolder(ExportPanel.Selection.Collection folder) {
        clearState();
        String shownTitle = folder.title() == null || folder.title().isBlank() ? folder.folderId() : folder.title();
        this.title.setText(shownTitle);
        empty.setText(EMPTY_SOURCE);
        sourceView.source(null, null, shownTitle);
        sourceChanges.source(null, null, shownTitle);
        sourceProperties.source(null);
        areaView.folder(folder);
        areaPodcast.folder(folder);
        pageCard = SOURCE_CARD;
        showDeck();
    }

    /** Ob gerade genau dieser Ordner (samt Favoriten und Unterordner-Stellung) gezeigt wird. */
    boolean showsFolder(ExportPanel.Selection.Collection folder) {
        return showsSource() && java.util.Objects.equals(areaView.folder(), folder);
    }

    /** Ob die Quellenseite gerade einen Favoriten-Ordner zeigt. */
    private boolean folderShown() {
        return SOURCE_CARD.equals(pageCard) && areaView.folder() != null;
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

    /**
     * Der Chat; sein Wissen ist die Auswahl links. Er steht nicht mehr als Reiter in dieser Ansicht, sondern in der
     * Seitenleiste des Hauptfensters ({@link ChatSidebar}, Angelo 21.11.2026).
     */
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
        folder = selection instanceof ExportPanel.Selection.Collection collection ? collection : null;
        if (folder != null) {
            // Ohne Ereignis: nur der Benutzer schaltet (ActionListener), das Setzen zeigt bloß die Stellung.
            folderSubfolders.setSelected(folder.withSubfolders());
        }
        updateFootTools(tabs.selected());
        overviewTermsDirty = true;
        overviewRecount = false;
        tabStatus(OVERVIEW_TERMS, " ");
        AppRuntime runtime = context.runtime();
        watchOverviewHoldings(runtime, selection);
        overviewGlossary.selection(selection);
        overviewGraph.pages(selection == null || runtime == null || selection.global() ? null
                : () -> SelectionHoldings.pages(runtime, selection));
        wideAnalysis.scope(selection == null || runtime == null ? null : new AnalysisView.Scope() {
            @Override
            public PageSet pages() {
                return SelectionHoldings.pages(runtime, selection);
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
     * Meldet sich beim Bestand der Auswahl an (je Wurzel Quelle samt Space, bei „Wissensquellen“ jede Quelle, bei einem
     * Favoriten-Ordner jeder Favorit; {@link SelectionHoldings}), damit Zahlen
     * und Herkunft der Begriffe nach Laden, Abgleich oder Bereinigung stimmen; kein Zeitgeber.
     */
    private void watchOverviewHoldings(AppRuntime runtime, ExportPanel.Selection selection) {
        overviewHoldings.forEach(com.aresstack.spaceharvester.application.observe.Subscription::close);
        overviewHoldings.clear();
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
        overviewHoldings.addAll(SelectionHoldings.watch(runtime, selection, listener));
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

    /** Ob die gezeigte Seite ein Programm ist: dann gibt es „mit Unterseiten“ nicht. */
    private boolean programShown;

    private void subpagesShown() {
        subpages.box().setVisible(!wide && PAGE.equals(pageCard) && !programShown);
    }

    private boolean standingShown() {
        return analysesOpen && wide;
    }

    /** Die Fläche der Analysen für die rechte Seitenleiste; ihr Inhalt folgt der Auswahl links und der offenen Seite. */
    JComponent analyses() {
        return analyses;
    }

    /** Die Reiter der gezeigten Analysen für den Fuß der Seitenleiste. */
    JComponent analysesFoot() {
        return analysesFoot;
    }

    /** Die Aktionen der Analysen einer Seite für die Kopfzeile der Seitenleiste. */
    JComponent analysesActions() {
        return analysisActions;
    }

    /** Was die Mitte wieder zeigt, wenn ein Sprung aus den groß gezeigten Analysen dorthin führt (das Hauptfenster). */
    void onRevealMiddle(Runnable action) {
        revealMiddle = java.util.Objects.requireNonNull(action, "action");
    }

    /** Was die Seitenleiste mit den Analysen aufklappt (das Hauptfenster); etwa wenn ein Sprung dorthin führt. */
    /** Was „Jetzt analysieren“ in den leeren Analysen der Auswahl tut; das bestimmt das Hauptfenster. */
    void onAnalyzeArea(Runnable action) {
        standing.onAnalyze(action);
    }

    void onOpenAnalyses(Runnable action) {
        openAnalyses = java.util.Objects.requireNonNull(action, "action");
    }

    /**
     * Ob die Analysen in der Seitenleiste gerade zu sehen sind: verdeckt lesen und zählen sie nicht, beim Zeigen laden
     * sie nach, was ihr gewählter Reiter braucht.
     */
    void analysesShown(boolean shown) {
        if (analysesOpen == shown) {
            return;
        }
        analysesOpen = shown;
        showDeck();
        if (shown) {
            loadVisible();
            if (!wide) {
                loadBelowSection(analysisTabs.selected());
            }
        }
        showTabStatus();
    }

    boolean analysesShown() {
        return analysesOpen;
    }

    /** Das Menü hinter dem Dreieck der Seitenleiste: nur was zu den gezeigten Analysen gehört. */
    java.util.List<javax.swing.JMenuItem> analysesMenu() {
        // Nur für den Fehlerfall und deshalb hier versteckt (Angelo 24.11.2026).
        return standingShown() ? java.util.List.of(menuItem(REBUILD_COUNTS, REBUILD_COUNTS_TIP, true,
                overviewTerms::rebuild)) : java.util.List.of();
    }

    /**
     * Der Unterreiter „Beziehungen“ unter „Analysen“ liest nur, solange er zu sehen ist; seine beiden Aktionen stehen
     * nur dann in der Reiterzeile. Ohne Seite steht er beim Stand und erfährt dort, ob er zu sehen ist.
     */
    private void showProgramAnalysis(PageSnapshot snapshot) {
        boolean program = programAnalysisTabs.show(snapshot, views);
        programShown = program;
        subpagesShown();
        if (analysisRow != null) {
            analysisRow.refresh();
        }
        recomputeProgram.setVisible(program);
        recomputeBusiness.setVisible(program && com.aresstack.spaceharvester.app.runtime.program.StoredCobolSource.read(snapshot).isPresent());
        searchPrograms.setVisible(snapshot.metadata().attributes().containsKey("ftp.path")
                || snapshot.metadata().attributes().containsKey("ndv.library"));
        showRelations();
    }

    private void showRelations() {
        String sub = analysisTabs.selected();
        storeField.setVisible(STATEMENTS.equals(sub) || GRAPH.equals(sub));
        boolean selected = RELATIONS.equals(analysisTabs.selected());
        analysisView.proposeIcon().setVisible(selected);
        analysisView.createIcon().setVisible(selected);
        analysisView.shown(analysesOpen && !wide && selected);
        statementsHelp.setVisible(analysesOpen && !wide && PAGE.equals(pageCard)
                && STATEMENTS.equals(analysisTabs.selected()));
    }

    /** Für Tests: der Abschnitt „Aus Unterseiten“ unter Bildern, Aussagen oder Graph. */
    SubpageSection belowSectionOf(String tab) {
        return belowSection(tab);
    }

    /** Für Tests: die Unterreiter von „Analysen“ samt „mit Unterseiten“ in der unteren Leiste. */
    JPanel analysisFoot() {
        return analysisFoot;
    }

    /** Für Tests: die Unterreiter von „Erweitert“ einer Seite in der unteren Leiste. */
    TabFoot advancedFoot() {
        return advancedFoot;
    }

    /**
     * {@code listener} bekommt die Stellung, die der Benutzer an „mit Unterseiten“ eines Ordners wählt; die Ansicht
     * löst den Ordner damit neu auf und wendet die Auswahl an ({@link ContentView#folder}).
     */
    void onFolderSubfolders(java.util.function.Consumer<Boolean> listener) {
        this.onFolderSubfolders = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Für Tests: „mit Unterseiten“ am gewählten Ordner im Fuß der breiten Ebene. */
    JCheckBox folderSubfolders() {
        return folderSubfolders;
    }

    /** Für Tests: der Fuß der breiten Ebene mit „mit Unterseiten“ eines Ordners. */
    AnalysisTabRow analysisRow() {
        return analysisRow;
    }

    TabFoot folderFoot() {
        return folderFoot;
    }

    /** Für Tests: die Checkbox „mit Unterseiten“ der Analyse-Reiter. */
    Subpages subpages() {
        return subpages;
    }

    /** Die eingestellte Ablage der Aussagenanalyse; ohne Datei der MaltParser. */
    private com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice storeSetting() {
        return context == null || context.settings() == null || context.settings().analyzer().analyzer() == null
                ? com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER
                : context.settings().analyzer().analyzer();
    }

    /**
     * Ablage umgeschaltet: schreibt dieselbe Einstellung wie Einstellungen › Modelle („Aussagenanalyse“) und übernimmt
     * sie ohne Neustart; währenddessen liegt das BusyOverlay über den Analysen. Neu gelesen wird per Beobachter
     * ({@link AppContext#onChange}), wenn die Laufzeit die neue Einstellung hat.
     */
    private void switchStore(com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice choice) {
        if (context == null || choice == storeSetting()) {
            return;
        }
        com.aresstack.spaceharvester.application.configuration.AnalyzerSettings next =
                (context.settings() == null
                        ? com.aresstack.spaceharvester.application.configuration.AnalyzerSettings.DEFAULT
                        : context.settings().analyzer()).withAnalyzer(choice);
        storeBusy.setText("Ablage „" + AnalyzerNames.shortName(choice) + "“ wird geladen …");
        storeBusy.setBusy(true);
        com.aresstack.spaceharvester.app.ui.Background.run(() -> {
            com.aresstack.spaceharvester.app.settings.ConfigFileEditor editor =
                    new com.aresstack.spaceharvester.app.settings.ConfigFileEditor(context.loader());
            java.nio.file.Path file = context.configFile();
            String text = java.nio.file.Files.isRegularFile(file)
                    ? java.nio.file.Files.readString(file, java.nio.charset.StandardCharsets.UTF_8) : "";
            com.aresstack.spaceharvester.app.settings.ConfigFileEditor.Draft written = editor.withAnalyzer(file,
                    new com.aresstack.spaceharvester.app.settings.ConfigFileEditor.Draft(text, null), next);
            editor.save(file, written, !editor.fileHasPlaintextSecret(file));
            return Boolean.TRUE;
        }, done -> context.reload(() -> storeBusy.setBusy(false)), error -> {
            storeBusy.setBusy(false);
            storeField.show(storeSetting());
            context.events().publish(new com.aresstack.spaceharvester.app.event.StatusMessageEvent(
                    "Ablage ließ sich nicht umschalten: " + com.aresstack.spaceharvester.app.ui.Problems
                            .describe(error).title(), STATUS_MS,
                    com.aresstack.spaceharvester.app.event.Severity.ERROR));
        });
    }

    /** Die Laufzeit hat neue Einstellungen: Auswahlfeld nachziehen, bei anderer Ablage die Ansichten neu lesen. */
    private void storeChanged() {
        com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice now = storeSetting();
        boolean switched = now != shownStore;
        shownStore = now;
        storeField.show(now);
        if (switched) {
            showTabStatus();
            statementsView.storeChanged();
            graphView.reload();
            // „Beziehungen“ (Glossar und Analyseschritte) liest nicht aus der Aussagen-Ablage: kein Neuladen nötig.
        }
    }

    private com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice shownStore =
            com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice.PARSER;

    /** Für Tests: das Ablage-Auswahlfeld der Reiterzeile. */
    AnalyzerStoreField storeField() {
        return storeField;
    }

    /** Für Tests: die ?-Hilfe des Unterreiters „Aussagen“. */
    com.aresstack.spaceharvester.app.ui.controls.HelpButton statementsHelp() {
        return statementsHelp;
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
        watchStatements(context.runtime());
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
        return standingShown();
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
        revealMiddle.run();
        if (context.navigator() != null) {
            context.navigator().openPage(target.sourceId(), target.pageId());
        } else {
            load(target.sourceId(), target.pageId(), false);
        }
    }

    /** Öffnet eine verlinkte Seite derselben Quelle wie {@link #openLinked(String, String)}. */
    private void openLinked(String id) {
        openLinked(sourceId, id);
    }

    /**
     * Öffnet eine Seite aus einem Link in der Ansicht (Brotkrumen, Verweise, „Aus Unterseiten“, Link im Text) über den
     * Navigator: Der Baum links wählt sie dann mit, wie bei einem Suchtreffer (Angelo 23.11.2026). Ohne Navigator
     * (Tests) nur laden.
     */
    private void openLinked(String source, String id) {
        if (source == null || id == null) {
            return;
        }
        revealMiddle.run();
        if (context != null && context.navigator() != null) {
            context.navigator().openPage(source, id);
        } else {
            load(source, id, false);
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

    /**
     * Setzt die Export-Ansicht mit den Export-Reitern ein (ADR 0045 §5, ADR 0047 §5): Sie umschließt den Export und
     * steht an seiner Stelle; ein neuer Export-Reiter holt den Reiter „Export“ nach vorn.
     */
    void artifactTabs(ArtifactTabs artifactTabs) {
        this.artifactTabs = artifactTabs;
        deck.remove(export);
        deck.add(artifactTabs.component(), EXPORT_CARD);
        artifactTabs.reveal(this::showExport);
        artifactTabs.onSelectionChanged(() -> updateFootTools(tabs.selected()));
        // Ein Knopf der Ausgabe ohne Reiter öffnet einen Export-Reiter, der bei dieser Auswahl stehen bleibt.
        export.launcher(artifactTabs::openExport);
        showDeck();
    }

    /** Die Karte des Reiters „Export“: mit Artefakt-Reitern deren Fläche, sonst der Export selbst. */
    JComponent exportCard() {
        return artifactTabs == null ? export : artifactTabs.component();
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
        cancelProgramSearch();
        if (pendingProgramFocus != null && !pendingProgramFocus.key().equals(new PageKey(sourceId, pageId))) {
            pendingProgramFocus = null;
        }
        remember(Place.page(sourceId, pageId));
        this.sourceId = sourceId;
        this.pageId = pageId;
        this.assetsLoaded = false;
        this.readingLoaded = false;
        this.referencesLoaded = false;
        this.imagesLoaded = false;
        this.page = null;
        programAnalysisTabs.pending();
        // Die Meldung von „Anhänge ablegen“ gehört zu ihrer Seite; läuft hier noch einer, erscheint sie wieder.
        leaveHarvest();
        // Ebenso die Rückmeldung von „Anhänge vorladen“: Eine fremde soll die neue Seite nicht neu laden.
        leavePrefetch(sourceId + "\n" + pageId);
        String harvest = harvests.get(new PageKey(sourceId, pageId));
        if (harvest != null) {
            watchHarvest(runtime, harvest, sourceId, pageId);
        }
        // Der Podcast der vorigen Seite endet sofort, nicht erst, wenn die neue geladen ist (oder nie, wenn das scheitert).
        pagePodcast.summary(null);
        closeOriginalFile();
        readingPane.loading();
        breadcrumbs.clear();
        positionShown.accept(null);
        // Die vorige Seite ist nicht mehr zu sehen: bis die neue geladen ist (oder nie, wenn das scheitert), gilt für
        // den Chat wieder die Auswahl im Baum.
        chat.reading(null);
        references.message(" ");
        // Die Unterseiten der vorigen Seite gelten ab jetzt nicht mehr, auch wenn das Laden scheitert. Der Graph
        // verlässt die Seite zuerst, damit deren Abmeldung für die alte Seite nichts mehr lädt.
        graphView.page(null);
        subpages.page(null, null, null, null);
        int current = ++request;
        update.dismiss();
        showCard(PAGE);
        showReader(null, false);
        // Bis die Seite da ist, dreht sich auch über dem Text die Sanduhr.
        textBusy.setBusy(true);
        statementsView.pending(new PageKey(sourceId, pageId));
        acronymHint.setText(" ");
        closeAttachmentOverlay();
        assetsModel.set(null);
        assetsBusy.setBusy(false);
        title.setText("Seite " + pageId);
        titleFromTree(runtime, sourceId, pageId, current);
        path.setText(sourceId);
        state.setText(" ");
        renderDetails();
        properties.reset();
        technical.reset();
        ProgressiveText.clear(raw);
        ProgressiveText.clear(storage);
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
            textBusy.setBusy(false);
            feedback.problem(error, Problems.Step.PAGE);
            statementsView.unavailable();
            page = null;
            acronymHint.setText(" ");
        });
    }

    /**
     * Den Namen aus dem Seitenbaum, solange die Seite lädt oder ihr Laden scheitert: sonst stünde dort die interne ID,
     * bei BSCW eine lange Base64-Adresse. Die geladene Seite setzt ihren Titel selbst.
     */
    private void titleFromTree(AppRuntime runtime, String source, String id, int current) {
        Background.run(() -> runtime.workspace().position(source, id), position -> {
            if (current != request || page != null || position == null || position.page() == null) {
                return;
            }
            String name = position.page().title();
            if (name != null && !name.isBlank()) {
                title.setText(name);
            }
            breadcrumbs.show(position);
        }, error -> {
            // Ohne Seitenbaum bleibt die ID.
        });
    }

    /** Alles, was {@link #show} zu einer ausgelieferten Seite braucht; außerhalb des EDT. */
    private Loaded loaded(AppRuntime runtime, String source, String id, DeliveredPage delivered, Font font,
                          boolean checked, PassageLookup passage, boolean unchecked) {
        // Avoid preparing an AI text document before showing a native image.
        // Ein Video mit Transkript hat Text (Angelo 20.11.2026): seine Analysen zeigen wie bei jeder Textseite.
        PageText text = !isNativePreview(delivered.snapshot()) || remoteMedia(delivered.snapshot())
                ? runtime.workspace().pageText(delivered.snapshot()) : null;
        if (isNativePreview(delivered.snapshot()) && text == null) {
            return new Loaded(delivered, List.of(), null, Payloads.display(delivered.snapshot().payload()),
                    null, checked, passage, unchecked);
        }
        List<Enrichments.View> enrichments = runtime.workspace().enrichments(delivered);
        ReaderDocument document = localDocument(runtime.workspace(), delivered, font)
                .orElseGet(() -> document(source, id, enrichments, text, font));
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
            boolean newer = SourceRevision.hasUpdate(shownRevision, checked.snapshot().revision());
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
                        assetsBusy.setBusy(false);
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
        java.util.Objects.requireNonNull(listener, "listener");
        onStatusNote(note -> listener.accept(note.text()));
    }

    /** Wie {@link #onStatus}, mit Ampelton und Link der Meldung ({@code controls.StatusLabel}). */
    public void onStatusNote(java.util.function.Consumer<StatusNote> listener) {
        this.statusShown = java.util.Objects.requireNonNull(listener, "listener");
        showTabStatus();
    }

    /**
     * „mit Unterseiten“ an- oder abgehakt, oder die Unterseiten haben sich geändert: die Analyse-Reiter zeigen unter
     * dem Eigenen abgetrennt das der Unterseiten (Fahrplan P2/P3), dynamisch abgefragt, nichts kopiert.
     */
    private void subpagesChanged() {
        Subpages.Below below = subpages.below();
        AppRuntime runtime = context.runtime();
        belowRelationsFor = null;
        belowRelationsGeneration++;
        belowSectionFor.clear();
        BELOW_TABS.forEach(tab -> belowSectionGeneration.merge(tab, 1L, Long::sum));
        // Der Seitengraph rechnet selbst über Seite und Unterseiten (W4); vor dem Abschnitt, der sein Ergebnis zeigt. Er
        // folgt der Checkbox und den ermittelten Nachfahren. Neu rechnen bei anderer Menge oder neuem Ergebnis der Nachfahren (geänderter Bestand, gleiche Menge).
        Subpages.Below known = subpages.checked() ? subpages.known() : null;
        boolean reload = subpages.revision() != graphSubpagesRevision;
        graphSubpagesRevision = subpages.revision();
        graphView.subpages(runtime == null ? null : graphSubpages(known), reload);
        graphBelowNote = "";
        showGraphStatus();
        if (below == null || runtime == null) {
            terms.below(null);
            references.belowLoading(-1);
            analysisView.below().off();
            passagesBelow.off();
            imagesBelow.off();
            statementsBelow.off();
            graphBelow.off();
            belowReferencesGeneration++;
            // Auch „Beziehungen“ zurücksetzen: Sonst stünde nach dem Wechsel auf eine Seite ohne Unterseiten noch die
            // Zahl der vorigen Seite in der Statuszeile („aus dieser Seite und 420 Unterseiten“, Angelo 23.11.2026).
            String none = subpages.checkedWithoutChildren() ? Subpages.TIP_NO_CHILDREN : " ";
            tabStatus(TERMS, none);
            tabStatus(RELATIONS, none);
            return;
        }
        // Bis die Beziehungen der neuen Unterseiten geladen sind, gilt keine Zahl der vorigen.
        tabStatus(RELATIONS, " ");
        watchLinks(runtime);
        loadBelowReferences();
        if (RELATIONS.equals(analysisTabs.selected())) {
            loadBelowRelations();
        }
        passagesBelow.off();
        imagesBelow.off();
        statementsBelow.off();
        graphBelow.off();
        loadBelowSection(analysisTabs.selected());
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        SelectionTerms selection = new SelectionTerms(workspace, "den Unterseiten", below.pages());
        terms.below(new TermsTab.Below() {
            @Override
            public TermsTab.Selection terms() {
                return selection;
            }

            @Override
            public java.util.Map<com.aresstack.spaceharvester.application.terms.TermQueries.TermRef, Integer>
                    pageCounts() {
                return workspace.termPageCounts(below.pages().pages());
            }

            @Override
            public int pages() {
                return below.size();
            }

            @Override
            public java.util.Map<TermsTab.VerbRef, Integer> verbPageCounts() {
                // Vollständig: alle Seiten der Verbliste durchlaufen, nicht nur die ersten 500 Verben.
                java.util.Map<TermsTab.VerbRef, Integer> counts = new java.util.HashMap<>();
                String cursor = null;
                int unanalysed = 0;
                do {
                    com.aresstack.spaceharvester.application.knowledge.VerbQueries.VerbList list = workspace.verbs(
                            below.pages().pages(), null,
                            com.aresstack.spaceharvester.application.knowledge.VerbQueries.MAX_LIMIT, cursor);
                    list.verbs().forEach(verb -> counts.put(TermsTab.VerbRef.of(verb), verb.pages()));
                    unanalysed = list.unanalysed();
                    cursor = list.nextCursor();
                } while (cursor != null);
                int missing = unanalysed;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (subpages.below() == below) {
                        tabStatus(TERMS, subpagesStatus("Begriffe", below.size()) + belowUnanalysed(missing));
                    }
                });
                return counts;
            }
        });
        tabStatus(TERMS, subpagesStatus("Begriffe", below.size()));
    }

    /** Zählt die Ladevorgänge der Verweise der Unterseiten; ein überholter zeigt sein Ergebnis nicht. */
    private long belowReferencesGeneration;

    /** Lädt die Verweise der Unterseiten aus dem Verzeichnis (Fahrplan P3); nie auf dem EDT. */
    private void loadBelowReferences() {
        Subpages.Below below = subpages.below();
        AppRuntime runtime = context.runtime();
        if (below == null || runtime == null) {
            return;
        }
        long generation = ++belowReferencesGeneration;
        references.belowLoading(below.size());
        Background.run(() -> runtime.workspace().subpageReferences(below.pages().pages()), found -> {
            if (generation == belowReferencesGeneration && below.equals(subpages.below())) {
                references.below(found);
            }
        }, error -> {
            if (generation == belowReferencesGeneration) {
                references.below(List.of());
            }
        });
    }

    /** Für welche Unterseiten die Beziehungen geladen sind; {@code null}: noch nicht. */
    private Subpages.Below belowRelationsFor;
    private long belowRelationsGeneration;
    /** Höchstens so viele Beziehungen der Unterseiten; darüber sagt die Statuszeile „die ersten“. */
    static final int BELOW_RELATIONS_LIMIT = 1000;

    /**
     * Lädt die Beziehungen der Unterseiten (Fahrplan P3): alle mit einem Begriff auf einer Unterseite, ohne die, die
     * oben bei der Seite schon stehen; Herkunft sind die Unterseiten ihrer beiden Begriffe. Nur, wenn „Beziehungen“
     * zu sehen ist, einmal je Stand der Unterseiten; im Hintergrund, Teilergebnisse sofort.
     */
    private void loadBelowRelations() {
        Subpages.Below below = subpages.below();
        AppRuntime runtime = context.runtime();
        if (below == null || runtime == null || below.equals(belowRelationsFor) || pageId == null) {
            return;
        }
        belowRelationsFor = below;
        long generation = ++belowRelationsGeneration;
        SubpageSection section = analysisView.below();
        section.loading();
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        String source = sourceId;
        String page = pageId;
        Background.run(() -> belowRelations(workspace, source, page, below), rows -> {
            if (generation == belowRelationsGeneration) {
                section.rows(rows, true);
                tabStatus(RELATIONS, subpagesStatus(RELATIONS, below.size())
                        + (rows.size() >= BELOW_RELATIONS_LIMIT ? " · unten die ersten 1.000 von mehr" : ""));
            }
        }, error -> {
            if (generation == belowRelationsGeneration) {
                section.rows(List.of(), true);
                tabStatus(RELATIONS, "Beziehungen der Unterseiten nicht lesbar: " + Problems.describe(error).title());
            }
        });
    }

    /** Die Reiter mit einem Abschnitt „Aus Unterseiten“; unter Graph aus dem Ergebnis des Graphen selbst (W4). */
    private static final List<String> BELOW_TABS = List.of(PASSAGES, IMAGES, STATEMENTS, GRAPH);
    /** Höchstens so viele Zeilen je Abschnitt; darüber sagt die Statuszeile „die ersten“. */
    static final int BELOW_ROWS_LIMIT = 1000;
    /** Für welche Unterseiten ein Abschnitt geladen ist, je Reiter. */
    private final java.util.Map<String, Subpages.Below> belowSectionFor = new java.util.HashMap<>();
    /** Zählt die Ladevorgänge je Reiter; auch der Hintergrund liest, ob seiner noch gilt. */
    private final java.util.Map<String, Long> belowSectionGeneration = new java.util.concurrent.ConcurrentHashMap<>();

    /** Ein Inhalt mit seinem Abschnitt „Aus Unterseiten“ darunter. */
    private static JPanel withBelow(JComponent content, SubpageSection below) {
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setOpaque(false);
        panel.add(content, BorderLayout.CENTER);
        panel.add(below, BorderLayout.SOUTH);
        return panel;
    }

    private SubpageSection belowSection(String tab) {
        return PASSAGES.equals(tab) ? passagesBelow : IMAGES.equals(tab) ? imagesBelow
                : STATEMENTS.equals(tab) ? statementsBelow
                : GRAPH.equals(tab) ? graphBelow : null;
    }

    /** Was ein Abschnitt aus den Unterseiten liest: Zeilen und wie viele Unterseiten noch nichts haben. */
    record BelowRows(List<SubpageSection.Row> rows, int missing, boolean cut) {
    }

    /**
     * Lädt den Abschnitt „Aus Unterseiten“ unter Bildern, Aussagen oder Graph (Fahrplan P3): nur, wenn der Reiter zu
     * sehen ist, einmal je Stand der Unterseiten, im Hintergrund, Teilergebnisse sofort. Es zeigt nur, was schon
     * vorliegt; die Statuszeile sagt, für wie viele Unterseiten noch nichts vorliegt (AT). Nie ein KI-Lauf.
     */
    private void loadBelowSection(String tab) {
        SubpageSection section = belowSection(tab);
        Subpages.Below below = subpages.below();
        AppRuntime runtime = context == null ? null : context.runtime();
        if (section == null || below == null || runtime == null || pageId == null
                || below.equals(belowSectionFor.get(tab))) {
            return;
        }
        belowSectionFor.put(tab, below);
        if (GRAPH.equals(tab)) {
            // Aus demselben Ergebnis wie der Graph darüber, nicht eigens je Unterseite (W4); sonst kommt es mit
            // graphView.onSubpages.
            graphBelow.loading();
            GraphNeighbourhood.Stack stack = graphView.subpageStack();
            if (stack != null) {
                showBelowGraph(below, runtime.workspace(), stack);
            } else {
                // Ohne Ergebnis lädt der Graph neu oder meldet sofort „nichts“ (etwa nach einem gescheiterten Laden,
                // dann „Absätze“ und zurück), damit der Abschnitt nicht ewig lädt.
                graphView.requestSubpages();
            }
            return;
        }
        long generation = belowSectionGeneration.merge(tab, 1L, Long::sum);
        java.util.function.BooleanSupplier current = () -> generation == belowSectionGeneration.getOrDefault(tab, 0L);
        section.loading();
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        Background.run(() -> belowRows(tab, workspace, below, current, partial ->
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (current.getAsBoolean()) {
                        section.rows(partial, false);
                    }
                })), found -> {
            if (current.getAsBoolean()) {
                section.rows(found.rows(), true);
                tabStatus(tab, belowStatus(tab, below.size(), found));
            }
        }, error -> {
            if (current.getAsBoolean()) {
                section.rows(List.of(), true);
                tabStatus(tab, "Unterseiten nicht lesbar: " + Problems.describe(error).title());
            }
        });
    }

    /** Statuszeile des Seitengraphen; nur sie nennt die Unterseiten ohne Analyse. */
    private StatusNote graphStatus = StatusNote.plain(" ");
    /** Das Ergebnis der Nachfahren ({@link Subpages#revision()}), das der Seitengraph zuletzt bekam. */
    private int graphSubpagesRevision = -1;
    /** Was der Abschnitt „Aus Unterseiten“ unter dem Graph dazu sagt („ · …“), sonst leer. */
    private String graphBelowNote = "";

    /** Graph und Abschnitt darunter teilen sich eine Statuszeile: erst der Graph, dann der Abschnitt. */
    private void showGraphStatus() {
        String base = graphStatus.text();
        // Der Abschnitt dahinter bleibt grau, auch hinter einem gelben oder roten Hinweis von „Wissensgraph erzeugen“.
        tabNote(GRAPH, graphBelowNote.isEmpty() ? graphStatus : base.isBlank()
                ? graphStatus.withText(graphBelowNote.substring(3)) : graphStatus.plus(graphBelowNote));
    }

    /**
     * Die Unterseiten, über die der Seitengraph rechnet: die ermittelten Nachfahren, solange wenigstens eine nicht
     * gesperrt ist; sonst {@code null} wie ohne Unterseiten. {@link PageSelection#size()} zählt gesperrte mit, die keinen
     * Inhalt haben.
     */
    static com.aresstack.spaceharvester.application.holdings.PageSelection graphSubpages(Subpages.Below known) {
        return known == null || known.pages().unlockedSize() == 0 ? null : known.pages();
    }

    /** Ein Ergebnis des Seitengraphen mit Unterseiten; zeigt es im Abschnitt, wenn der für diese Unterseiten gilt. */
    private void belowGraphLoaded(GraphNeighbourhood.Stack stack) {
        Subpages.Below below = subpages.below();
        AppRuntime runtime = context.runtime();
        if (below != null && runtime != null && below.equals(belowSectionFor.get(GRAPH))) {
            showBelowGraph(below, runtime.workspace(), stack);
        }
    }

    /** Füllt den Abschnitt unter dem Graph aus {@code stack} ({@code null}: Graph nicht geladen); Titel im Hintergrund. */
    private void showBelowGraph(Subpages.Below below, com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                                GraphNeighbourhood.Stack stack) {
        long generation = belowSectionGeneration.merge(GRAPH, 1L, Long::sum);
        java.util.function.BooleanSupplier current = () -> generation == belowSectionGeneration.getOrDefault(GRAPH, 0L);
        if (stack == null) {
            graphBelow.rows(List.of(), true);
            graphBelowNote = "";
            showGraphStatus();
            return;
        }
        Background.run(() -> belowGraphRows(workspace, below, stack), found -> {
            if (current.getAsBoolean()) {
                graphBelow.rows(found.rows(), true);
                graphBelowNote = found.cut() ? " · unten die ersten 1.000 von mehr" : "";
                showGraphStatus();
            }
        }, error -> {
            if (current.getAsBoolean()) {
                graphBelow.rows(List.of(), true);
                graphBelowNote = " · Unterseiten nicht lesbar: " + Problems.describe(error).title();
                showGraphStatus();
            }
        });
    }

    /**
     * Die Zeilen unter dem Graph aus dessen Ergebnis über Seite und Unterseiten (W4): je Unterseite mit Knoten im Graph
     * eine Zeile, Zahl ihrer gezeigten Knoten als Chip – mit denselben Filtern (auch „Veraltete zeigen“) und Grenzen
     * wie der Graph. {@code missing} zählt die Unterseiten ohne sichtbare Analyse wie dessen Statuszeile. Gesperrte
     * Unterseiten zählen nirgends, auch wenn eine Analyse von früher vorliegt. Nie auf dem EDT.
     */
    static BelowRows belowGraphRows(com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                                    Subpages.Below below, GraphNeighbourhood.Stack stack) {
        return belowGraphRows(workspace::pageNodes, below, stack);
    }

    /** Wie oben, mit {@code pageNodes} wie {@code Workspace#pageNodes}. */
    static BelowRows belowGraphRows(java.util.function.Function<java.util.Collection<PageKey>, java.util.Map<PageKey,
            com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode>> pageNodes,
                                    Subpages.Below below, GraphNeighbourhood.Stack stack) {
        PageSet pages = below.pages().pages();
        PageSet locked = below.pages().locked();
        java.util.function.Predicate<PageKey> counted = key -> pages.contains(key) && !locked.contains(key);
        java.util.Map<PageKey, Integer> counts = new java.util.HashMap<>();
        for (GraphNeighbourhood.Node node : stack.graph().nodes()) {
            if (counted.test(node.page().key())) {
                counts.merge(node.page().key(), 1, Integer::sum);
            }
        }
        int missing = (int) stack.missing().stream().filter(counted).count();
        var nodes = pageNodes.apply(counts.keySet());
        List<PageKey> ordered = new java.util.ArrayList<>(nodes.keySet());
        ordered.sort(java.util.Comparator.comparing((PageKey key) -> Breadcrumbs.title(nodes.get(key)),
                String.CASE_INSENSITIVE_ORDER).thenComparing(PageKey::pageId));
        List<SubpageSection.Row> rows = new java.util.ArrayList<>();
        for (PageKey key : ordered) {
            if (rows.size() >= BELOW_ROWS_LIMIT) {
                return new BelowRows(List.copyOf(rows), missing, true);
            }
            rows.add(new SubpageSection.Row(List.of(), nodes.get(key), List.of(), null, counts.get(key)));
        }
        return new BelowRows(List.copyOf(rows), missing, false);
    }

    /**
     * „für 2 Unterseiten liegt noch nichts vor“ für die Statuszeile, nur wenn etwas fehlt; die Zahl der Einträge steht
     * im Chip (UX). Leer ({@code " "}), wenn alles vorliegt.
     */
    static String belowStatus(String tab, int pages, BelowRows found) {
        List<String> parts = new java.util.ArrayList<>();
        if (found.missing() > 0) {
            parts.add("für " + (found.missing() == 1 ? "1 Unterseite" : found.missing() + " Unterseiten")
                    + " liegt noch nichts vor");
        }
        if (found.cut()) {
            parts.add("unten die ersten 1.000 von mehr");
        }
        return parts.isEmpty() ? " " : String.join(" · ", parts);
    }

    /**
     * Liest je Unterseite, was der Reiter {@code tab} zeigt, in Titelreihenfolge; meldet alle zehn Unterseiten den
     * Zwischenstand. Nie auf dem EDT.
     */
    static BelowRows belowRows(String tab, com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                               Subpages.Below below, java.util.function.BooleanSupplier current,
                               java.util.function.Consumer<List<SubpageSection.Row>> partial) {
        List<com.aresstack.spaceharvester.application.snapshot.PageKey> keys = new java.util.ArrayList<>();
        var pages = below.pages().pages();
        for (String source : pages.sourceIds()) {
            pages.pagesOf(source).forEach(id ->
                    keys.add(new com.aresstack.spaceharvester.application.snapshot.PageKey(source, id)));
        }
        var nodes = workspace.pageNodes(keys);
        List<com.aresstack.spaceharvester.application.snapshot.PageKey> ordered = new java.util.ArrayList<>(
                nodes.keySet());
        ordered.sort(java.util.Comparator.comparing(
                (com.aresstack.spaceharvester.application.snapshot.PageKey key) -> Breadcrumbs.title(nodes.get(key)),
                String.CASE_INSENSITIVE_ORDER).thenComparing(key -> key.pageId()));
        List<SubpageSection.Row> rows = new java.util.ArrayList<>();
        int missing = 0;
        int done = 0;
        boolean cut = false;
        for (var key : ordered) {
            if (!current.getAsBoolean()) {
                break;
            }
            List<SubpageSection.Row> found = belowRowsOf(tab, workspace, key, nodes.get(key));
            if (found == null) {
                missing++;
            } else {
                for (SubpageSection.Row row : found) {
                    if (rows.size() >= BELOW_ROWS_LIMIT) {
                        cut = true;
                        break;
                    }
                    rows.add(row);
                }
            }
            if (cut) {
                break;
            }
            if (++done % 10 == 0) {
                partial.accept(List.copyOf(rows));
            }
        }
        return new BelowRows(List.copyOf(rows), missing, cut);
    }

    /** Was eine Unterseite für den Reiter hat; {@code null}: noch nichts (nicht lokal, nicht analysiert). */
    private static List<SubpageSection.Row> belowRowsOf(String tab,
                                                        com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                                                        com.aresstack.spaceharvester.application.snapshot.PageKey key,
                                                        com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode node) {
        var page = workspace.cachedPage(key.sourceId(), key.pageId());
        if (page.isEmpty()) {
            return null;
        }
        if (PASSAGES.equals(tab)) {
            return belowPassages(workspace, key, node, page.get());
        }
        if (IMAGES.equals(tab)) {
            PageImages found = workspace.images(page.get());
            if (found.state() == PageImages.State.MISSING) {
                return PageImages.NO_TEXT.equals(found.reason()) ? List.of() : null;
            }
            List<SubpageSection.Row> rows = new java.util.ArrayList<>();
            // Veraltete Beschreibungen kenntlich machen wie im Reiter selbst.
            String stale = found.state() == PageImages.State.CURRENT ? "" : " (veraltet)";
            for (ImagePayload.Entry entry : found.images()) {
                ImagesTab.Image image = imageOf(entry);
                String description = image.description() == null || image.description().isBlank()
                        ? "noch nicht beschrieben" : image.description().strip().replaceAll("\\s+", " ");
                rows.add(new SubpageSection.Row(List.of(image.name() + stale, description), node));
            }
            return rows;
        }
        var found = workspace.statements(page.get().snapshot());
        if (found.state() == com.aresstack.spaceharvester.application.knowledge.PageStatements.State.MISSING) {
            return com.aresstack.spaceharvester.application.knowledge.PageStatements.NO_TEXT.equals(found.reason())
                    ? List.of() : null;
        }
        var result = found.analysis().result();
        java.util.Map<String, String> mentions = new java.util.HashMap<>();
        result.mentions().forEach(mention -> mentions.put(mention.id(), mention.text()));
        List<SubpageSection.Row> rows = new java.util.ArrayList<>();
        String stale = found.state() == com.aresstack.spaceharvester.application.knowledge.PageStatements.State.CURRENT
                ? "" : " (veraltet)";
        for (var statement : result.statements()) {
            List<String> parts = statement.arguments().stream().map(argument -> mentions.get(argument.mention()))
                    .filter(java.util.Objects::nonNull).distinct().toList();
            // Verneinung, Modalität und Zeit gehören zur Aussage (wie StatementsTab.describe).
            String features = StatementsTab.features(statement.features());
            String predicate = statement.predicateText() + (features.isEmpty() ? "" : " (" + features + ")") + stale;
            rows.add(new SubpageSection.Row(List.of(predicate, String.join(", ", parts)), node));
        }
        return rows;
    }

    /**
     * Die Absätze einer Unterseite für „Aus Unterseiten“ unter „Absätze“: nur ein vorliegendes, aktuelles Ergebnis, nie
     * eine neue Zerlegung; sonst {@code null} (zählt als „liegt noch nichts vor“). Ohne Lesetext leer.
     */
    static List<SubpageSection.Row> belowPassages(com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                                                  com.aresstack.spaceharvester.application.snapshot.PageKey key,
                                                  com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode node,
                                                  DeliveredPage page) {
        PageText text = workspace.pageText(page.snapshot());
        if (text == null) {
            return List.of();
        }
        PagePassages found = workspace.localReading(page).map(result -> result.passages())
                .orElseGet(() -> PagePassages.read(key, workspace.enrichments(page), text));
        if (found.state() != PagePassages.State.CURRENT) {
            return null;
        }
        List<SubpageSection.Row> rows = new java.util.ArrayList<>();
        for (PagePassages.Passage passage : found.passages()) {
            rows.add(new SubpageSection.Row(List.of(passage.title(), PassagesTab.preview(passage)), node));
        }
        return rows;
    }

    /** Die Zeilen des Abschnitts „Aus Unterseiten“ unter „Beziehungen“; nie auf dem EDT. */
    static List<SubpageSection.Row> belowRelations(com.aresstack.spaceharvester.app.runtime.Workspace workspace,
                                                   String sourceId, String pageId, Subpages.Below below) {
        com.aresstack.spaceharvester.application.snapshot.PageSet own =
                com.aresstack.spaceharvester.application.snapshot.PageSet.ofKeys(List.of(
                        new com.aresstack.spaceharvester.application.snapshot.PageKey(sourceId, pageId)));
        java.util.Set<String> ownIds = new java.util.HashSet<>();
        String cursor = null;
        do {
            var list = workspace.termRelations(null, null, null, null,
                    com.aresstack.spaceharvester.application.terms.TermRelations.MAX_LIMIT, cursor, own);
            list.relations().forEach(relation -> ownIds.add(relation.id()));
            cursor = list.nextCursor();
        } while (cursor != null);
        com.aresstack.spaceharvester.application.snapshot.PageSet pages = below.pages().pages();
        List<com.aresstack.spaceharvester.application.terms.TermRelation> found = new java.util.ArrayList<>();
        cursor = null;
        do {
            var list = workspace.termRelations(null, null, null, null,
                    com.aresstack.spaceharvester.application.terms.TermRelations.MAX_LIMIT, cursor, pages);
            for (var relation : list.relations()) {
                if (!ownIds.contains(relation.id()) && found.size() < BELOW_RELATIONS_LIMIT) {
                    found.add(relation);
                }
            }
            cursor = found.size() < BELOW_RELATIONS_LIMIT ? list.nextCursor() : null;
        } while (cursor != null);
        if (found.isEmpty()) {
            return List.of();
        }
        java.util.Map<String, List<com.aresstack.spaceharvester.application.snapshot.PageKey>> termPages =
                workspace.termPages(pages);
        java.util.Map<com.aresstack.spaceharvester.application.terms.TermRelation,
                List<com.aresstack.spaceharvester.application.snapshot.PageKey>> origins = new java.util.LinkedHashMap<>();
        java.util.Set<com.aresstack.spaceharvester.application.snapshot.PageKey> keys = new java.util.LinkedHashSet<>();
        for (var relation : found) {
            java.util.Set<com.aresstack.spaceharvester.application.snapshot.PageKey> where =
                    new java.util.LinkedHashSet<>();
            for (String term : List.of(relation.fromTerm(), relation.toTerm())) {
                where.addAll(termPages.getOrDefault(
                        com.aresstack.spaceharvester.application.terms.TermText.normalize(term), List.of()));
            }
            origins.put(relation, List.copyOf(where));
            keys.addAll(where);
        }
        var nodes = workspace.pageNodes(keys);
        List<SubpageSection.Row> rows = new java.util.ArrayList<>();
        origins.forEach((relation, where) -> {
            List<com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode> visible = where.stream()
                    .map(nodes::get).filter(java.util.Objects::nonNull).toList();
            if (!visible.isEmpty()) {
                com.aresstack.spaceharvester.app.ui.Relation shown = com.aresstack.spaceharvester.app.ui.Relation.of(relation);
                rows.add(new SubpageSection.Row(List.of(shown.fromTerm(),
                        com.aresstack.spaceharvester.app.ui.Relation.typeText(shown.type()),
                        shown.toTerm(), com.aresstack.spaceharvester.app.ui.Relation.statusText(shown.status())),
                        visible.get(0), visible.subList(1, visible.size()), null));
            }
        });
        return rows;
    }

    /** „Begriffe aus dieser Seite und 3 Unterseiten“ für die Statuszeile. */
    /** „ · 2 Unterseiten ohne Aussagenanalyse, ihre Verben fehlen“; leer, wenn alle analysiert sind. */
    static String belowUnanalysed(int pages) {
        return pages <= 0 ? "" : " · " + (pages == 1 ? "1 Unterseite" : pages + " Unterseiten")
                + " ohne Aussagenanalyse, ihre Verben fehlen";
    }

    static String subpagesStatus(String what, int pages) {
        return what + " aus dieser Seite und " + (pages == 1 ? "1 Unterseite" : pages + " Unterseiten");
    }

    private void tabStatus(String tab, String text) {
        tabNote(tab, StatusNote.plain(text));
    }

    private void tabNote(String tab, StatusNote note) {
        tabStatus.put(tab, note == null ? StatusNote.plain(" ") : note);
        showTabStatus();
    }

    private void showTabStatus() {
        String tab = selectedTab();
        StatusNote note = tabStatus.getOrDefault(tab,
                StatusNote.plain(" "));
        // Eine Statuszeile für Mitte und Seitenleiste: Hat der gezeigte Reiter der Analysen etwas zu melden, steht das.
        String side = analysesTab();
        StatusNote sideNote = side == null ? null : tabStatus.get(side);
        if (sideNote != null && sideNote.text() != null && !sideNote.text().isBlank()) {
            tab = side;
            note = sideNote;
        }
        statusShown.accept(withStore(tab, note, shownStore));
    }

    /**
     * Wie {@link #withStore(String, String, com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice)} für eine
     * Meldung: Die Ablage dahinter bleibt grau, auch wenn die Meldung gelb oder rot ist ({@link StatusNote#plus}).
     */
    static StatusNote withStore(String tab, StatusNote note,
                                com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice store) {
        String shown = withStore(tab, note.text(), store);
        return shown != null && shown.startsWith(note.text())
                ? note.plus(shown.substring(note.text().length())) : note.withText(shown);
    }

    /**
     * Die Statuszeile der Reiter, die aus der Ablage der Aussagenanalyse lesen (Aussagen, Graph, Stand, Glossar), nennt
     * den Analysator dieser Ablage (AT, D6a); leere Zeilen bleiben leer, andere Reiter unverändert.
     */
    static String withStore(String tab, String text,
                            com.aresstack.spaceharvester.application.knowledge.AnalyzerChoice store) {
        boolean reads = STATEMENTS.equals(tab) || GRAPH.equals(tab) || STAND.equals(tab)
                || OVERVIEW_GLOSSARY.equals(tab);
        return !reads || text == null || text.isBlank() ? text : text + " · Ablage: " + AnalyzerNames.name(store);
    }

    private void show(Loaded loaded) {
        page = loaded.page();
        views = loaded.enrichments();
        PageSnapshot snapshot = page.snapshot();
        PageMetadata metadata = page.header().metadata();
        // Der Graph zuerst: die Unterseiten, die die Checkbox danach meldet, gehören schon zur neuen Seite (W4).
        graphView.page(snapshot.key(), snapshot.spaceKey());
        subpages.page(context.runtime() == null ? null : context.runtime().workspace(), sourceId,
                metadata.partitionKey(), metadata.pageId());
        statementsView.page(snapshot, loaded.reader() == null ? null : loaded.reader().text());
        pagePodcast.summary(new PodcastPage(sourceId, metadata.pageId()));
        pageShown.accept(sourceId, metadata.partitionKey());
        // ADR 0033 „Was ich sehe, sieht der Bot“: die gelesene Seite bestimmt den Bereich des Chats, auch wenn sie aus
        // der Suche kommt und der Baum woanders steht; die Auswahl im Baum bleibt dabei unberührt.
        chat.reading(ExportPanel.Selection.of(sourceId, metadata.partitionKey(), metadata.pageId(), metadata.title()));
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
        showOriginalContent(viewable(metadata, snapshot.metadata()), snapshot.payload());
        showStoredProgram(snapshot);
        showTextOriginal(loaded.reader());
        ProgressiveText.show(raw, loaded.display());
        ProgressiveText.show(storage, loaded.storage() == null ? "Diese Payload hat kein body.storage." : loaded.storage());

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
        showProgramAnalysis(snapshot);
        setActionsEnabled(true);
        browse.setEnabled(metadata.url() != null && Desktop.isDesktopSupported());
    }

    /**
     * Show the unmodified source file in the original-view tab.
     * Keep extracted text and its analyses in the separate text and analysis tabs.
     */
    /** Natives Format eines entfernten Videos ohne Dateiinhalt (BSCW). */
    private static final String REMOTE_MEDIA_FORMAT = "remote-media-metadata";

    /**
     * Die Kopfdaten für die Original-Ansicht: Fehlen dem Kopf Attribute (etwa die Art der Ressource), ergänzt sie der
     * Snapshot; was der Kopf hat, gilt.
     */
    static PageMetadata viewable(PageMetadata header, PageMetadata snapshot) {
        if (snapshot == null || header.attributes().keySet().containsAll(snapshot.attributes().keySet())
                && (header.url() != null || snapshot.url() == null)) {
            return header;
        }
        java.util.SortedMap<String, String> attributes = new java.util.TreeMap<>(snapshot.attributes());
        attributes.putAll(header.attributes());
        return new PageMetadata(header.pageId(), header.resourceId(), header.title(), header.partitionKey(),
                header.parentPageId(), header.version(), header.modifiedAt(),
                header.url() != null ? header.url() : snapshot.url(), attributes);
    }

    private void searchStoredPrograms() {
        if (programSearchTask != null) {
            cancelProgramSearch();
            feedback.info("Programmsuche abgebrochen", null);
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) return;
        String source = sourceId;
        int current = request;
        int generation = ++programSearchGeneration;
        com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.busy(searchPrograms, true);
        feedback.working("Gespeicherte Programmanalysen werden gelesen …");
        var sources = runtime.workspace().programSearchSources();
        programSearchTask = Background.start(() -> runtime.workspace().storedProgramSearch(java.util.Set.copyOf(sources),
                () -> Thread.currentThread().isInterrupted()), snapshot -> {
            if (current != request || context.runtime() != runtime || generation != programSearchGeneration) return;
            programSearchTask = null;
            com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.busy(searchPrograms, false);
            feedback.clear();
            new StoredProgramSearchDialog(snapshot, sources, source).show(this).ifPresent(fact -> {
                if (current == request && context.runtime() == runtime) openStoredProgramHit(runtime, fact, current);
            });
        }, error -> {
            if (current != request || context.runtime() != runtime || generation != programSearchGeneration) return;
            programSearchTask = null;
            com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.busy(searchPrograms, false);
            feedback.problem(error);
        });
    }

    private void cancelProgramSearch() {
        programSearchGeneration++;
        if (programSearchTask != null) {
            programSearchTask.cancel(true);
            programSearchTask = null;
        }
        com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.busy(searchPrograms, false);
    }

    private void openStoredProgramHit(AppRuntime runtime,
            com.aresstack.spaceharvester.app.runtime.program.StoredProgramSearch.Fact fact, int current) {
        var location = fact.location();
        String source = location.key().sourceId();
        String id = location.key().pageId();
        String hash = location.rawSha256();
        Background.run(() -> runtime.workspace().programFactCurrent(fact), valid -> {
            if (current != request || context.runtime() != runtime) return;
            if (!valid) {
                feedback.warning("Fundstelle nicht mehr aktuell", "Programmsuche erneut öffnen.");
                return;
            }
            context.navigator().openProgram(source, id, hash, location.line());
        }, error -> {
            if (current == request && context.runtime() == runtime) feedback.problem(error);
        });
    }

    void openProgram(String source, String id, String rawSha256, int line) {
        pendingProgramFocus = new ProgramFocus(new PageKey(source, id), rawSha256, line);
        load(source, id, false);
    }

    private void showStoredProgram(PageSnapshot snapshot) {
        var focus = pendingProgramFocus;
        pendingProgramFocus = null;
        var natural = com.aresstack.spaceharvester.app.runtime.program.StoredNaturalSource.read(snapshot);
        if (natural.isPresent()) {
            boolean matchingNatural = focus != null && focus.key().equals(snapshot.key())
                    && focus.rawSha256().equals(snapshot.payload().rawSha256());
            showOriginalProgram(natural.get().content(), matchingNatural ? focus.line() : 0,
                    com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax.NATURAL);
            if (matchingNatural) tabs.select(READING);
            if (focus != null && !matchingNatural) feedback.warning("Fundstelle nicht mehr aktuell", "Quelltextrevision hat sich geändert.");
            return;
        }
        boolean matching = focus != null && focus.key().equals(snapshot.key())
                && focus.rawSha256().equals(snapshot.payload().rawSha256());
        boolean nativeFtp = com.aresstack.spaceharvester.app.runtime.program.StoredFtpText.nativeFormat(snapshot.payload());
        java.util.Optional<String> code = matching || nativeFtp
                ? com.aresstack.spaceharvester.app.runtime.program.StoredFtpText.read(snapshot)
                    .map(com.aresstack.spaceharvester.app.runtime.program.StoredFtpText::content)
                : com.aresstack.spaceharvester.app.runtime.program.JclProgramEnrichment.source(snapshot)
                    .map(com.aresstack.spaceharvester.app.runtime.program.StoredFtpText::content)
                    .or(() -> com.aresstack.spaceharvester.app.runtime.program.StoredCobolSource.read(snapshot)
                        .map(com.aresstack.spaceharvester.mainframe.cobol.CobolSource::content));
        code.ifPresent(source -> showOriginalProgram(source, matching ? focus.line() : 0));
        if (matching && code.isPresent()) tabs.select(READING);
        if (focus != null && (!matching || code.isEmpty())) {
            feedback.warning("Fundstelle nicht mehr aktuell", "Quelltextrevision hat sich geändert.");
        }
    }

    private void showOriginalProgram(String source, int line) {
        String syntax = switch (com.aresstack.enterpriseai.proasteion.emporion.holkas.ftp.FtpProgramClassifier
                .classify(source)) {
            case COBOL, COPYBOOK -> com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax.COBOL;
            case JCL -> com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax.JCL;
            case UNKNOWN -> org.fife.ui.rsyntaxtextarea.SyntaxConstants.SYNTAX_STYLE_NONE;
        };
        showOriginalProgram(source, line, syntax);
    }

    private void showOriginalProgram(String source, int line, String syntax) {
        com.aresstack.spaceharvester.mainframe.syntax.MainframeSyntax.register();
        var editor = new org.fife.ui.rsyntaxtextarea.RSyntaxTextArea();
        editor.setSyntaxEditingStyle(syntax);
        editor.setEditable(false);
        editor.setText(source);
        editor.setCaretPosition(0);
        fileContent.add(Comic.comicBars(new org.fife.ui.rtextarea.RTextScrollPane(editor)), BorderLayout.CENTER);
        originalProgramShown = true;
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        if (line > 0 && line <= editor.getLineCount()) {
            try {
                editor.setCaretPosition(editor.getLineStartOffset(line - 1));
                tabs.select(READING);
            } catch (javax.swing.text.BadLocationException invalid) {
                feedback.warning("Fundstelle nicht verfügbar", "Zeile liegt außerhalb des Quelltexts.");
            }
        }
        updateFootTools(tabs.selected());
    }

    void showOriginalContent(PageMetadata metadata, NativePayload payload) {
        closeOriginalFile();
        if (PageMetadata.LINK_KIND.equals(metadata.attributes().get(PageMetadata.RESOURCE_KIND))) {
            readingPane.clear(" ");
            showOriginalLink(payload);
            return;
        }
        // Entfernte Videos und zu große Dateien erkennt die Ansicht auch an der Payload: Kopfdaten aus einem älteren
        // Stand tragen die Art der Ressource nicht immer, sonst bliebe die Ansicht leer.
        if ("true".equals(metadata.attributes().get(PageMetadata.REMOTE_MEDIA))
                || REMOTE_MEDIA_FORMAT.equals(payload.nativeFormat())) {
            showOriginalVideo(metadata);
            return;
        }
        if (LocalOriginalReference.recognizes(payload)) {
            readingPane.clear(" ");
            if (!showLargeOriginal(metadata)) {
                showOriginalWithoutPreview(metadata, "Das Original verbleibt im lokalen Ordner und wird nicht "
                        + "im Cache gespeichert. Für dieses Format oder diese Größe ist keine Vorschau verfügbar. "
                        + "Öffnen und Speichern greifen bei Bedarf auf das Original zu.");
            }
            return;
        }
        if (com.aresstack.spaceharvester.application.snapshot.LargeFiles.placeholder(payload)) {
            readingPane.clear(" ");
            if (!showLargeOriginal(metadata)) {
                showLargeFile(metadata,
                        com.aresstack.spaceharvester.application.snapshot.LargeFiles.textOnly(payload));
            }
            return;
        }
        if (!isOriginalFile(metadata, payload)) {
            return;
        }
        readingPane.clear(" ");
        long fileSize = payload.length();
        if (fileSize <= 0) {
            try {
                fileSize = Long.parseLong(metadata.attributes().getOrDefault("dav.contentLength",
                        metadata.attributes().getOrDefault("file.size",
                                metadata.attributes().getOrDefault("fileSize", "-1"))));
            } catch (NumberFormatException ignored) {
                fileSize = -1L;
            }
        }
        if (payload.length() == 0 && fileSize == 0) {
            // Auch in der Quelle leer (etwa eine leere Vorlage in BSCW): keine Vorschau, aber Speichern und Öffnen.
            emptyFileShown = true;
            ReaderAttachmentLoader empty = (file, done, failed) -> done.accept(new byte[0]);
            originalFileViewer = AttachmentViewerPanel.withoutPreview(new ReaderAttachment(metadata.pageId(),
                    metadata.title(), originalMediaType(payload), 0), empty, fileOpener, attachmentRenderer,
                    EMPTY_FILE);
            originalFileViewer.detachFooter();
            fileContent.add(originalFileViewer, BorderLayout.CENTER);
            originalCards.show(originalContent, ORIGINAL_FILE_CARD);
            fileContent.revalidate();
            fileContent.repaint();
            updateFootTools(tabs.selected());
            return;
        }
        ReaderAttachment attachment = new ReaderAttachment(metadata.pageId(), metadata.title(),
                originalMediaType(payload), fileSize);
        final String source = sourceId;
        final String id = metadata.pageId();
        // A zero-byte BSCW snapshot may come from an older cached metadata-only revision.
        // Recheck it once against the source instead of trusting an empty cache as the original.
        final boolean originalAvailable = payload.length() > 0
                || (fileSize == 0 && !metadata.attributes().containsKey("bscw.path"));
        ReaderAttachmentLoader loader = new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment file, Consumer<byte[]> done, Consumer<String> failed) {
                if (originalAvailable) {
                    Background.run(payload::bytes, done, error -> failed.accept(reason(error)));
                    return;
                }
                AppRuntime runtime = context.runtime();
                if (runtime == null) {
                    failed.accept("Die Quelle ist nicht mehr verbunden.");
                    return;
                }
                // Refresh an older empty metadata shell directly from the owning BSCW page.
                Background.run(() -> runtime.workspace().page(source, id, true).snapshot().payload().bytes(), done,
                        error -> failed.accept(reason(error)));
            }

            /** Ohne Inhalt im Cache holen Speichern und Öffnen das Original direkt aus der Quelle auf die Platte. */
            @Override
            public boolean downloadsToFile() {
                return !originalAvailable && context.runtime() != null;
            }

            @Override
            public void downloadTo(ReaderAttachment file, java.nio.file.Path target, Runnable done,
                                   Consumer<String> failed) {
                AppRuntime runtime = context.runtime();
                if (runtime == null) {
                    failed.accept("Die Quelle ist nicht mehr verbunden.");
                    return;
                }
                Background.run(() -> {
                    runtime.workspace().downloadOriginal(source, metadata, target);
                    return Boolean.TRUE;
                }, ignored -> done.run(), error -> failed.accept(reason(error)));
            }
        };
        originalAttachment = attachment;
        originalLoader = once(loader);
        originalFileViewer = new AttachmentViewerPanel(attachment, originalLoader, fileOpener, attachmentRenderer);
        // Speichern, Öffnen und „Typ · Größe“ stehen unten in der Leiste mit der Lupe, nicht in der Vorschau.
        originalFileViewer.detachFooter();
        fileContent.add(originalFileViewer, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
        updateFootTools(tabs.selected());
    }

    /**
     * Der Reiter „Text“ einer Datei ohne Textauszug (Angelo, 20.11.2026): statt „keine Lesevorschau“ das Original im
     * Viewer, aus demselben, nur einmal geladenen Original wie in der Leseansicht; zu einer leeren Datei ein Hinweis.
     */
    private void showTextOriginal(ReaderDocument document) {
        if (emptyFileShown) {
            reader.setText(EMPTY_FILE);
            return;
        }
        if (videoTranscriptShown || originalAttachment == null || document != null && document.text() != null) {
            return;
        }
        textOriginalViewer = new AttachmentViewerPanel(originalAttachment, originalLoader, fileOpener,
                attachmentRenderer);
        textOriginal.add(textOriginalViewer, BorderLayout.CENTER);
        textCardLayout.show(textContents, TEXT_ORIGINAL_CARD);
        textOriginal.revalidate();
        textOriginal.repaint();
    }

    /** Lädt das Original höchstens einmal; wer während des Ladens fragt, bekommt dasselbe Ergebnis (EDT). */
    private static ReaderAttachmentLoader once(ReaderAttachmentLoader loader) {
        return new ReaderAttachmentLoader() {
            private byte[] bytes;
            private List<Consumer<byte[]>> waiting;
            private List<Consumer<String>> failing;

            @Override
            public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                if (bytes != null) {
                    done.accept(bytes);
                    return;
                }
                if (waiting != null) {
                    waiting.add(done);
                    failing.add(failed);
                    return;
                }
                waiting = new ArrayList<>(List.of(done));
                failing = new ArrayList<>(List.of(failed));
                loader.load(attachment, loaded -> {
                    bytes = loaded;
                    List<Consumer<byte[]>> all = waiting;
                    waiting = null;
                    failing = null;
                    all.forEach(each -> each.accept(loaded));
                }, reason -> {
                    List<Consumer<String>> all = failing;
                    waiting = null;
                    failing = null;
                    all.forEach(each -> each.accept(reason));
                });
            }

            @Override
            public void preview(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                loader.preview(attachment, done, failed);
            }

            @Override
            public boolean downloadsToFile() {
                return loader.downloadsToFile();
            }

            @Override
            public void downloadTo(ReaderAttachment attachment, java.nio.file.Path target, Runnable done,
                                   Consumer<String> failed) {
                if (bytes != null) {
                    ReaderAttachmentLoader.super.downloadTo(attachment, target, done, failed);
                } else {
                    loader.downloadTo(attachment, target, done, failed);
                }
            }
        };
    }

    /** Keep remote video content out of the native snapshot; stream it after a deliberate play action. */
    private void showOriginalVideo(PageMetadata metadata) {
        readingPane.clear(" ");
        // Without a usable size from the source the player stays disabled.
        long size = com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia.size(metadata);
        final String source = sourceId;
        final String id = metadata.pageId();
        String mediaType = com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia.mediaType(metadata);
        final java.util.concurrent.atomic.AtomicReference<
                com.aresstack.spaceharvester.application.sources.MediaByteRange> media =
                new java.util.concurrent.atomic.AtomicReference<>();
        com.aresstack.spaceharvester.application.sources.MediaByteRange ranges = (offset, count) -> {
            AppRuntime runtime = context.runtime();
            if (runtime == null) {
                throw new IllegalStateException("Die Quelle ist nicht mehr verbunden.");
            }
            com.aresstack.spaceharvester.application.sources.MediaByteRange reader = media.get();
            if (reader == null) {
                synchronized (media) {
                    reader = media.get();
                    if (reader == null) {
                        reader = runtime.workspace().openMediaRange(source, id);
                        media.set(reader);
                    }
                }
            }
            return reader.read(offset, count);
        };
        originalVideoViewer = new VideoViewerPanel(metadata.title(), size, mediaType, ranges::read, context.video());
        if (size > 0 && "video/mp4".equals(mediaType)) {
            VideoFrameSource frames = new VideoFrameSource(source, id, ranges::read, size, mediaType);
            videoFrames = frames;
            // Die Marken des Players sind die einzige Quelle der Folien; gespeichert erscheinen sie als Anhänge.
            originalVideoViewer.onSlides(changes -> slidesFound(frames, changes));
            showVideoMarks(originalVideoViewer, frames);
            // „In PPTX umwandeln“ neben „Herunterladen“ (Angelo 20.11.2026): fünf Aufträge unter „Aufträge“.
            long videoSize = size;
            String videoType = mediaType;
            originalVideoViewer.onConvert(pdf -> convertToPresentation(frames, ranges, videoSize, videoType,
                    pdf ? com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format.PDF : com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format.PPTX));
            VideoTranscriptSource transcriptSource = new VideoTranscriptSource(size, ranges, context.video());
            videoTranscript.setVideoSource(transcriptSource);
            // Angelo 20.11.2026: Korrigieren wie vor „In PPTX umwandeln“, Löschen samt Korrekturen und Analysen.
            videoTranscript.onCorrect(() -> correctTranscript(frames, ranges, videoSize, videoType));
            videoTranscript.onDelete(() -> deleteTranscript(transcriptSource, frames));
            // Angelo 20.11.2026: je Whisper-Modell ein Original; die Auswahl wählt es auch für Korrektur und Analysen.
            videoTranscript.onOriginal(whisper -> chooseOriginal(transcriptSource, frames, whisper));
            showStoredTranscript(transcriptSource, frames);
            textCardLayout.show(textContents, TEXT_VIDEO_TRANSCRIPT_CARD);
            // Das Transkript füllt den Reiter; die Akronymzeile gehört zum Lesetext.
            acronymBar.setVisible(false);
            videoTranscriptShown = true;
        }
        fileContent.add(originalVideoViewer, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
        updateFootTools(tabs.selected());
    }

    /**
     * Present a redirect target as a source-neutral link document.
     * Open the target only after an explicit user action; never reuse source credentials.
     */
    /**
     * Eine Datei über der Grenze „Größte Datei im Cache“, die der Viewer zeigen kann (Bild, PDF, Office, Text) und die
     * nicht größer ist als „Große Dateien ganz laden bis“: Der Viewer lädt ihr Original direkt aus der Quelle, ohne es
     * abzulegen (Angelo, 20.11.2026).
     *
     * @return ob der Viewer steht; sonst zeigt {@link #showLargeFile} die Karte
     */
    private boolean showLargeOriginal(PageMetadata metadata) {
        String type = com.aresstack.spaceharvester.application.snapshot.LargeFiles.viewableType(metadata);
        long size = com.aresstack.spaceharvester.application.snapshot.LargeFiles.size(metadata);
        long limit = context.settings() == null
                ? com.aresstack.spaceharvester.app.settings.AppSettings.Storage.DEFAULT_MAX_TEXT_FILE_BYTES
                : context.settings().storage().maxTextFileBytes();
        AppRuntime runtime = context.runtime();
        if (type == null || runtime == null || limit <= 0 || size > limit) {
            return false;
        }
        if ("application/onenote".equals(type)
                && !com.aresstack.spaceharvester.source.OneNotePreviewReader.supportsSize(size)) {
            return false;
        }
        ReaderAttachment attachment = new ReaderAttachment(metadata.pageId(), metadata.title(), type, size);
        String source = sourceId;
        ReaderAttachmentLoader loader = new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment file, Consumer<byte[]> done, Consumer<String> failed) {
                Background.run(() -> runtime.workspace().original(source, metadata), done,
                        error -> failed.accept(reason(error)));
            }

            @Override
            public boolean downloadsToFile() {
                return true;
            }

            @Override
            public void downloadTo(ReaderAttachment file, java.nio.file.Path target, Runnable done,
                                   Consumer<String> failed) {
                Background.run(() -> {
                    runtime.workspace().downloadOriginal(source, metadata, target);
                    return Boolean.TRUE;
                }, ignored -> done.run(), error -> failed.accept(reason(error)));
            }
        };
        originalAttachment = attachment;
        originalLoader = once(loader);
        originalFileViewer = new AttachmentViewerPanel(attachment, originalLoader, fileOpener, attachmentRenderer);
        originalFileViewer.detachFooter();
        fileContent.add(originalFileViewer, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
        updateFootTools(tabs.selected());
        return true;
    }

    /**
     * Eine Datei über der Grenze „Größte Datei im Cache“ (Angelo, 20.11.2026): Die App hat sie nicht heruntergeladen;
     * die Karte nennt Größe und Grenze und öffnet die Datei in der Quelle. Mit {@code textOnly} liegt ihr Textauszug
     * im Cache: Text-Ansicht und Suche haben ihn.
     */
    private void showLargeFile(PageMetadata metadata, boolean textOnly) {
        long size = com.aresstack.spaceharvester.application.snapshot.LargeFiles.size(metadata);
        long limit = context.settings() == null ? com.aresstack.spaceharvester.app.settings.AppSettings.Storage.DEFAULT_MAX_PAGE_BYTES
                : context.settings().storage().maxPageBytes();
        String reason = "Keine Vorschau: zu groß für den Cache (Grenze " + Texts.bytes(limit) + ")."
                + (textOnly ? "\nDer Textauszug steht im Reiter „Podcast-Ansicht“, die Suche findet die Datei darüber."
                : "\nAbgelegt sind nur ihre Kopfdaten (kein lesbarer Text oder größer als die Grenze zum Laden).")
                + "\nGrenzen unter Einstellungen › Daten › „Größte Datei im Cache“.";
        showOriginalWithoutPreview(metadata, reason);
    }

    /** Show an original that can be opened or saved but has no in-memory preview. */
    private void showOriginalWithoutPreview(PageMetadata metadata, String reason) {
        long size = com.aresstack.spaceharvester.application.snapshot.LargeFiles.size(metadata);
        AppRuntime runtime = context.runtime();
        String type = com.aresstack.spaceharvester.application.snapshot.LargeFiles.viewableType(metadata);
        ReaderAttachment attachment = new ReaderAttachment(metadata.pageId(), metadata.title(),
                type == null ? "application/octet-stream" : type, size);
        String source = sourceId;
        // Speichern und Öffnen laden das Original direkt aus der Quelle auf die Platte, ohne Größengrenze.
        ReaderAttachmentLoader loader = new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment file, Consumer<byte[]> done, Consumer<String> failed) {
                failed.accept("Zu groß, um sie ganz in den Speicher zu laden.");
            }

            @Override
            public boolean downloadsToFile() {
                return runtime != null;
            }

            @Override
            public void downloadTo(ReaderAttachment file, java.nio.file.Path target, Runnable done,
                                   Consumer<String> failed) {
                Background.run(() -> {
                    runtime.workspace().downloadOriginal(source, metadata, target);
                    return Boolean.TRUE;
                }, ignored -> done.run(), error -> failed.accept(reason(error)));
            }
        };
        originalFileViewer = AttachmentViewerPanel.withoutPreview(attachment, loader, fileOpener, attachmentRenderer,
                reason);
        originalFileViewer.detachFooter();
        fileContent.add(originalFileViewer, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
        updateFootTools(tabs.selected());
    }

    /** Eine Karte statt des Originals: Hinweis und „In der Quelle öffnen“. */
    private void showFileCard(PageMetadata metadata, String text) {
        JPanel card = new JPanel(new BorderLayout(0, 12));
        card.setOpaque(false);
        javax.swing.JTextArea explanation = new javax.swing.JTextArea(text);
        explanation.setEditable(false);
        explanation.setOpaque(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        card.add(explanation, BorderLayout.NORTH);
        javax.swing.JButton open = new javax.swing.JButton("In der Quelle öffnen");
        java.net.URI target = metadata.url();
        boolean supported = target != null && ("http".equalsIgnoreCase(target.getScheme())
                || "https".equalsIgnoreCase(target.getScheme())) && target.getHost() != null
                && target.getRawUserInfo() == null;
        open.setEnabled(supported);
        if (supported) {
            open.addActionListener(event -> {
                try {
                    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                        Desktop.getDesktop().browse(target);
                    } else {
                        feedback.warning("Browser nicht verfügbar", "Adresse: " + target);
                    }
                } catch (IOException | RuntimeException failed) {
                    feedback.warning("Datei konnte nicht geöffnet werden", "Adresse: " + target);
                }
            });
        }
        card.add(Comic.row(open), BorderLayout.SOUTH);
        fileContent.add(card, BorderLayout.CENTER);
        originalCards.show(originalContent, ORIGINAL_FILE_CARD);
        fileContent.revalidate();
        fileContent.repaint();
    }

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

    /** Ob die Seite ein entferntes Video ist (BSCW); sein Text ist das Transkript. */
    private static boolean remoteMedia(PageSnapshot snapshot) {
        return "true".equals(snapshot.metadata().attributes().get(PageMetadata.REMOTE_MEDIA))
                || REMOTE_MEDIA_FORMAT.equals(snapshot.payload().nativeFormat());
    }

    /** Recognize original file snapshots even when older cached metadata lacks the resource kind. */
    private static boolean isOriginalFile(PageMetadata metadata, NativePayload payload) {
        String kind = metadata.attributes().get(PageMetadata.RESOURCE_KIND);
        return PageMetadata.FILE_KIND.equals(kind)
                || (kind == null && "file".equals(payload.nativeFormat()));
    }

    /** Identify original images before running any optional text-analysis preparation. */
    private static boolean isNativePreview(PageSnapshot snapshot) {
        String kind = snapshot.metadata().attributes().get(PageMetadata.RESOURCE_KIND);
        String largeType = com.aresstack.spaceharvester.application.snapshot.LargeFiles.placeholder(snapshot.payload())
                || LocalOriginalReference.recognizes(snapshot.payload())
                ? com.aresstack.spaceharvester.application.snapshot.LargeFiles.viewableType(snapshot.metadata()) : null;
        return PageMetadata.LINK_KIND.equals(kind)
                || "true".equals(snapshot.metadata().attributes().get(PageMetadata.REMOTE_MEDIA))
                || isOriginalFile(snapshot.metadata(), snapshot.payload())
                && (originalMediaType(snapshot.payload()).startsWith("image/")
                || largeType != null && largeType.startsWith("image/"));
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
        videoTranscript.setVideoSource(null);
        textCardLayout.show(textContents, TEXT_READER_CARD);
        acronymBar.setVisible(true);
        if (originalFileViewer != null) {
            originalFileViewer.close();
            originalFileViewer = null;
        }
        if (textOriginalViewer != null) {
            textOriginal.remove(textOriginalViewer);
            textOriginalViewer.close();
            textOriginalViewer = null;
        }
        originalAttachment = null;
        originalLoader = null;
        emptyFileShown = false;
        if (originalVideoViewer != null) {
            originalVideoViewer.close();
            originalVideoViewer = null;
        }
        originalLinkShown = false;
        originalProgramShown = false;
        videoTranscriptShown = false;
        videoFrames = null;
        stopTranscriptWatch();
        fileContent.removeAll();
        originalCards.show(originalContent, ORIGINAL_PAGE_CARD);
        updateFootTools(tabs.selected());
    }

    /** Die Werkzeuge der Leiste mit der Lupe für diese Ansicht ({@link #footTools}). */
    public JComponent footTools() {
        return footTools;
    }

    /** Setzt in {@link #footTools}, was zum Reiter {@code selected} gehört; nichts, wenn er keine Werkzeuge hat. */
    private void updateFootTools(String selected) {
        // Viewer und Transkript gibt es nur zu einer gezeigten Seite; jeder Wechsel räumt sie ab (closeOriginalFile).
        boolean standingFootShown = analysesOpen && wide && analysisFoot != null;
        if (EXPORT.equals(selected)) {
            JComponent bar = artifactTabs == null ? null : artifactTabs.footBar();
            bar = bar == null ? export.formatBar() : bar;
            if (exportBarSlot.getComponentCount() != 1 || exportBarSlot.getComponent(0) != bar) {
                exportBarSlot.removeAll();
                exportBarSlot.add(bar, BorderLayout.CENTER);
                exportBarSlot.revalidate();
                exportBarSlot.repaint();
            }
        }
        if (standingFootShown && folder != null) {
            if (folderSubfolders.getParent() != standingTrailing) {
                standingTrailing.add(folderSubfolders);
                standingTrailing.revalidate();
            }
        } else {
            folderFoot.reclaimTrailing();
        }
        if (analysisRow != null) {
            // Die Ebene bestimmt nur, welche Ansichten es gibt; Zeile, Symbole und Fuß sind für alle dieselben.
            analysisRow.level(wide ? standing.tabs() : PAGE.equals(pageCard) ? analysisTabs : null);
            subpagesShown();
        }
        JComponent shown = EXPORT.equals(selected) ? exportFoot
                : ADVANCED.equals(selected) && wide ? wideAdvancedFoot
                : ADVANCED.equals(selected) && PAGE.equals(pageCard) ? advancedFoot
                : READING.equals(selected) && originalFileViewer != null ? originalFileViewer.footer()
                : READ.equals(selected) && videoTranscriptShown ? videoTranscript.action()
                : READ.equals(selected) && SOURCE_CARD.equals(pageCard) && areaShown() ? areaFoot
                : READ.equals(selected) && SOURCE_CARD.equals(pageCard) ? sourceFoot
                : READING.equals(selected) && SOURCE_CARD.equals(pageCard) && sourceView.sourceId() != null
                ? readingSourceFoot
                : READ.equals(selected) && PAGE.equals(pageCard) ? pageFoot
                : ATTACHMENTS.equals(selected) && PAGE.equals(pageCard) ? attachmentsFoot
                : wide && folder != null ? folderFoot
                : null;
        // Podcast-Ansicht eines Ordners: „mit Unterseiten“ rechts in ihrer Leiste, wie in der Ordnerleiste.
        if (shown == areaFoot && folderShown() && folderSubfolders.getParent() != areaFolderSlot) {
            areaFolderSlot.add(folderSubfolders);
            areaFolderSlot.revalidate();
        }
        if (footTools.getComponentCount() == (shown == null ? 0 : 1)
                && (shown == null || footTools.getComponent(0) == shown)) {
            return;
        }
        footTools.removeAll();
        if (shown != null) {
            footTools.add(shown, BorderLayout.CENTER);
        }
        // Datei-Aktionen und Transkript-Knopf direkt neben der Lupe über die ganze Zeile.
        footTools.putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, shown != null);
        footTools.revalidate();
        footTools.repaint();
    }

    /** Zählt die Ladevorgänge der Verben; ein überholter zeigt sein Ergebnis nicht. */
    private long verbsGeneration;

    /**
     * Lädt die Verben der gezeigten Seite aus ihrer Aussagenanalyse (K1) für den Reiter „Begriffe“, im Hintergrund;
     * nach jeder neu gespeicherten Analyse noch einmal. Ohne Seite oder Analyse bleibt die Kennzahl auf „–“.
     */
    private void loadVerbs() {
        long generation = ++verbsGeneration;
        AppRuntime runtime = context.runtime();
        String source = sourceId;
        String page = pageId;
        terms.showVerbs(null);
        if (runtime == null || source == null || page == null) {
            return;
        }
        watchStatements(runtime);
        Background.run(() -> runtime.workspace().pageVerbs(source, page), found -> {
            if (generation != verbsGeneration) {
                return;
            }
            terms.showVerbs(found == null ? null : found.verbs().stream().map(verb -> new TermsTab.PageVerb(
                    verb.verb(), verb.occurrences().stream().map(o -> new TermsTab.Span(o.start(), o.end()))
                    .toList())).toList(), found != null && found.stale());
        }, error -> LOG.log(System.Logger.Level.INFO, "Verben der Seite nicht gelesen ({0})",
                error.getClass().getName()));
    }

    private AppRuntime statementsRuntime;
    private com.aresstack.spaceharvester.application.observe.Subscription statementsSubscription =
            com.aresstack.spaceharvester.application.observe.Subscription.NONE;

    /**
     * Meldet sich je Laufzeit einmal bei den gespeicherten Aussagenanalysen an (ADR 0027, Auflage AT zu K1): Nach einer
     * neuen Analyse oder „Alle Aussagen löschen“ lesen die Verben der Seite, der Abschnitt „Aus Unterseiten“ und die
     * Begriffe einer Auswahl neu. Kein Zeitgeber.
     */
    private void watchStatements(AppRuntime runtime) {
        if (runtime == statementsRuntime) {
            return;
        }
        statementsSubscription.close();
        statementsRuntime = runtime;
        statementsSubscription = runtime.workspace().subscribeStatementAnalyses(() ->
                javax.swing.SwingUtilities.invokeLater(this::statementsChanged));
    }

    /** Mehrere Meldungen kurz hintereinander lesen nur einmal neu. */
    private boolean statementsQueued;

    private void statementsChanged() {
        if (statementsQueued) {
            return;
        }
        statementsQueued = true;
        javax.swing.SwingUtilities.invokeLater(() -> {
            statementsQueued = false;
            if (sourceId != null && pageId != null) {
                loadVerbs();
                terms.reloadBelow();
                // Eine gezeigte Fachsicht im Reiter „Graph“ zieht nach, etwa nach ✓/✕ im Overlay „Belege“ (W3); mit
                // offenem Overlay erst nach dem Schließen.
                graphView.domainChanged();
                // „Aus Unterseiten“ unter Aussagen und Graph zeigt, was vorliegt: neue oder gelöschte Analysen
                // (etwa einer Hintergrundgruppe „mit Unterseiten“) lesen neu; verdeckte Reiter beim nächsten Zeigen.
                belowSectionFor.remove(STATEMENTS);
                belowSectionFor.remove(GRAPH);
                if (analysesOpen) {
                    loadBelowSection(analysisTabs.selected());
                }
            }
            if (overviewSelection != null) {
                overviewTermsDirty = true;
                if (overviewTermsShown) {
                    loadOverviewTerms();
                }
            }
        });
    }

    /** Zeigt einen fertig gebauten Lesetext; {@code null} leert ihn samt Markierungen. */
    private void showReader(ReaderDocument document, boolean live) {
        textBusy.setBusy(false);
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
        loadVerbs();
        acronymDetail.setText(" ");
        pagePictures.mark(List.of());
        pagePictures.cover(Optional.empty());
        pagePictures.show(List.of());
        if (document == null) {
            reader.setText("");
            return;
        }
        reader.setStyledDocument(document.document());
        reader.setCaretPosition(0);
        // Neu berechnet bei gleichem Text, während der Podcast läuft: die laufende Stelle bleibt gelb.
        if (!sameText) {
            pageSpans = List.of();
        }
        pagePictures.mark(pageSpans);
        pagePictures.cover(pageCoverage);
        loadPagePictures();
        readerPage.contentChanged();
        // Die Wolken der Leseansicht zählen wiederholte Überschriften im jetzt gezeigten Klartext.
        readingPane.setNavigation(readerPage.sky().navigation(), reader.getText());
        acronymHint.setText(document.hint(live));
        if (!live) {
            recomputeStale(document);
        }
    }

    /**
     * Die Analysen, die die Leseansicht braucht und die nicht zum angezeigten Text passen (Prozessor-IDs): Akronyme
     * und Begriffe, wenn sie nicht aktuell sind, Absätze, wenn sie veraltet, fehlgeschlagen oder nicht vorhanden sind.
     */
    static List<String> staleAnalyses(PageAcronyms.State acronyms, PageTerms.State terms, PagePassages.State passages) {
        List<String> stale = new ArrayList<>();
        if (acronyms != null && acronyms != PageAcronyms.State.CURRENT) {
            stale.add(PageAcronyms.PROCESSOR);
        }
        if (terms != null && terms != PageTerms.State.CURRENT) {
            stale.add(com.aresstack.spaceharvester.application.terms.TermPayload.PROCESSOR);
        }
        if (passages != null && passages != PagePassages.State.CURRENT) {
            stale.add(com.aresstack.spaceharvester.application.passages.PassageProcessor.ID);
        }
        return stale;
    }

    /**
     * Braucht der gezeigte Text eine veraltete Analyse, rechnet ein Auftrag sie von selbst neu (Angelo 27.11.2026):
     * sichtbar und abbrechbar in der Auftragsliste, nur aus der gespeicherten Seite, ohne Netz und ohne Abgleich mit der
     * Quelle. Je Fassung einmal; was danach noch veraltet ist (etwa Absätze, die das Embedding-Modell bräuchten),
     * berechnet der Klick auf den Stand im Analysebereich.
     */
    private void recomputeStale(ReaderDocument document) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || page.live() || document.text() == null || sourceId == null
                || pageId == null) {
            return;
        }
        List<String> stale = staleAnalyses(document.acronyms() == null ? null : document.acronyms().state(),
                document.terms() == null ? null : document.terms().state(),
                document.passages() == null ? null : document.passages().state());
        if (stale.isEmpty() || !autoRecomputed.add(sourceId + '\n' + pageId + '\n'
                + document.text().inputSha256())) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        recomputation(runtime, request, true,
                state -> runtime.workspace().recomputeStaleLocally(source, id, stale, state));
    }

    private static java.util.Optional<ReaderDocument> localDocument(
            com.aresstack.spaceharvester.app.runtime.Workspace workspace, DeliveredPage page, Font font) {
        return workspace.localReading(page).map(result -> ReaderDocument.build(result.text(),
                result.acronyms(), result.terms(), font).withPassages(result.passages()));
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
        revealMiddle.run();
        tabs.select(READ);
        reader.requestFocusInWindow();
        mark(target);
    }

    /** Lädt nach, was der gerade sichtbare Reiter braucht (Haupt- oder Unterreiter unter „Analysen“). */
    private void loadVisible() {
        String visible = selectedTab();
        String side = analysesTab();
        if (ATTACHMENTS.equals(visible)) {
            loadAssets(false);
        }
        if (READING.equals(visible) && originalFileViewer == null && originalVideoViewer == null && !originalLinkShown && !originalProgramShown) {
            loadReading();
        }
        if (REFERENCES.equals(side)) {
            loadReferences();
        }
        if (IMAGES.equals(side)) {
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
        // Die Sanduhr nur, solange noch keine Liste zu sehen ist; ein Nachladen (etwa neue Standbilder) blendet die
        // gezeigte Liste nicht ab.
        if (!force || assetsModel.rows.isEmpty()) {
            assetsBusy.setBusy(true);
        }
        String source = sourceId;
        String id = pageId;
        // Eigene Gültigkeit statt request: Eine Neuberechnung der Analysen verwirft die Anhangsliste nicht.
        DeliveredPage shown = page;
        Background.run(() -> runtime.workspace().assets(source, id), overview -> {
            if (shown != page) {
                return;
            }
            assetsModel.set(overview);
            assetsBusy.setBusy(false);
            if (videoFrames != null && ATTACHMENTS.equals(selectedTab()) && context.runtime() != null) {
                // Unter „Anhänge“ bekommen die Marken ihre Standbilder, im Hintergrund und der Reihe nach.
                prefetch(context.runtime()).focus(attachmentsOfKind(
                        com.aresstack.spaceharvester.application.assets.AssetKind.MARK), null);
            }
            updateAssetsHint();
        }, error -> {
            if (shown != page) {
                return;
            }
            assetsLoaded = false;
            assetsBusy.setBusy(false);
            // Mit Ursache ins Protokoll: „Unerwarteter Fehler“ allein sagt nicht, was schiefging.
            LOG.log(System.Logger.Level.WARNING, "Anhänge von " + source + " nicht geladen", error);
            String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
            assetsHint.setText("Anhänge nicht geladen: " + com.aresstack.spaceharvester.app.ui.Problems
                    .describe(error).title() + " (" + detail + "); der nächste Wechsel auf „Anhänge“ versucht es "
                    + "erneut.");
        });
    }

    /** „N Anhänge · live, Zeit“ über der Liste; ausgeblendete Bilder stehen mit ihrer Zahl dabei. */
    private void updateAssetsHint() {
        PageAssets overview = assetsModel.overview;
        if (overview == null) {
            return;
        }
        int hidden = overview.entries().size() - assetsModel.rows.size();
        assetsHint.setText(Texts.count(overview.entries().size(), "Anhang", "Anhänge")
                + (overview.listComplete() ? "" : " (Liste unvollständig)")
                + (hidden == 0 ? "" : ", davon " + Texts.count(hidden, "Bild", "Bilder") + " ausgeblendet")
                + " · live, " + Texts.time(overview.retrievedAt()));
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
        if (runtime == null || page == null || readingLoaded || originalFileViewer != null || originalLinkShown || originalProgramShown) {
            return;
        }
        readingLoaded = true;
        readingPane.loading();
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        loadReadAloud(runtime, source, id, shown);
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

    /**
     * Vorlesen in der Leseansicht (Angelo 23.11.2026): der Knopf erscheint mit eingerichteter Sprachausgabe, vorgelesen
     * wird die Textfassung der Seite, die nur mit KI-Freigabe kommt.
     */
    private void loadReadAloud(AppRuntime runtime, String source, String id, DeliveredPage shown) {
        boolean available;
        try {
            available = runtime.workspace().pageReadAloudAvailable();
        } catch (RuntimeException e) {
            available = false;
        }
        readingPane.readAloud().bind(available ? readAloudBinding() : null);
        if (!available) {
            return;
        }
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        Background.run(() -> workspace.pageReadAloudText(source, id).orElse(""), text -> {
            if (shown == page) {
                // vor jedem Vorlesen erneut: eine zurückgenommene KI-Freigabe gilt sofort (ADR 0028)
                readingPane.readAloud().speechText(text, () -> {
                    try {
                        return workspace.pageReadAloudText(source, id).isPresent();
                    } catch (RuntimeException e) {
                        return false;
                    }
                });
            }
        }, error -> {
            // ohne Text bleibt der Knopf aus; der Tooltip nennt den Grund
        });
    }

    /** Die eine Sprachausgabe der Leseansicht, über den Vorlese-Ablauf des Enterprise AI Client. */
    private com.aresstack.spaceharvester.app.speech.ReadAloudBinding readAloudBinding() {
        if (readAloudBinding == null) {
            readAloudBinding = new com.aresstack.spaceharvester.app.speech.ReadAloudBinding(() -> {
                AppRuntime current = context.runtime();
                if (current == null) {
                    throw new IllegalStateException("Der Space Harvester läuft nicht");
                }
                return current.workspace().pageReadAloud(
                        new com.aresstack.spaceharvester.app.speech.JavaSoundAudioPlayback());
            }, message -> context.events().publish(new com.aresstack.spaceharvester.app.event.StatusMessageEvent(
                    "Vorlesen nicht möglich: " + message, STATUS_MS,
                    com.aresstack.spaceharvester.app.event.Severity.WARN)));
        }
        return readAloudBinding;
    }

    /** Eine Leseansicht und ihre vorbereitete Darstellung. */
    private record ShownView(com.aresstack.spaceharvester.application.reading.ReadingView view,
                             ReadingPane.Prepared prepared) {
    }

    static ReadingPane.Prepared prepare(AppRuntime runtime,
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
        if (runtime == null) {
            ReadingPane.browse(url);
            return;
        }
        if (source == null || !sameDomain(url, known)) {
            openInOtherSource(runtime, url, request, ++linkRequest);
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
                openLinked(source, target);
                pendingAnchor = anchor;
            }
        }, error -> {
            if (current == request && click == linkRequest) {
                openInOtherSource(runtime, url, current, click);
            }
        });
    }

    /**
     * Ein Link, den die Quelle der Seite nicht kennt, etwa ein Jira-Vorgang aus einem Jira-Makro oder -Link einer
     * Confluence-Seite: zuerst über die Adressauflösung aller Quellen ({@code Workspace.resolveAddress}, #178), damit
     * er intern im Lesebereich aufgeht, wenn eine eingerichtete Quelle (Jira, eine andere Confluence …) die Seite
     * kennt; sonst wie bisher im Browser. Nur {@code http}/{@code https}; {@code mailto:} und Ähnliches gehen direkt
     * an den Browser.
     */
    private void openInOtherSource(AppRuntime runtime, java.net.URL url, int current, int click) {
        String protocol = url.getProtocol() == null ? "" : url.getProtocol().toLowerCase(java.util.Locale.ROOT);
        if (!protocol.equals("http") && !protocol.equals("https") || context.navigator() == null) {
            ReadingPane.browse(url);
            return;
        }
        String address = url.toString();
        Background.run(() -> runtime.workspace().resolveAddress(address), resolution -> {
            if (current != request || click != linkRequest) {
                return; // inzwischen eine andere Seite gewählt oder ein anderer Link geklickt
            }
            internalTarget(resolution).ifPresentOrElse(match -> {
                // Öffnen lädt synchron (ContentView.openPage → load); wie im Weg derselben Quelle springt die
                // Leseansicht danach zum Abschnitt hinter „#“.
                context.navigator().openPage(match.sourceId(), match.pageId());
                pendingAnchor = url.getRef();
            }, () -> ReadingPane.browse(url));
        }, error -> {
            if (click == linkRequest) {
                ReadingPane.browse(url);
            }
        });
    }

    /** Die erste Seite, die eine eingerichtete Quelle zur Adresse kennt; leer, wenn keine. */
    static java.util.Optional<com.aresstack.spaceharvester.application.query.PageAddresses.Match> internalTarget(
            com.aresstack.spaceharvester.application.query.PageAddresses.Resolution resolution) {
        return resolution == null ? java.util.Optional.empty() : resolution.matches().stream().findFirst();
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
            // Mit dem gezeigten Titel, damit der Verlauf (Zurück gedrückt halten) Namen zeigt.
            return new Place(sourceId, null, pageId, title.getText());
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
        if (workspaceHistory != null) {
            if (next != null) {
                locationVisited.accept(next);
            }
            updateNavigation();
            return;
        }
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
        if (workspaceHistory != null) {
            return goWorkspace(-1);
        }
        return navigateHistory(-1);
    }

    /** Vor zum zuletzt per Zurück Verlassenen. */
    public boolean forward() {
        if (workspaceHistory != null) {
            return goWorkspace(1);
        }
        return navigateHistory(1);
    }

    public boolean canGoBack() {
        if (workspaceHistory != null) {
            return !workspaceHistory.history().back().isEmpty();
        }
        return searchFront && current() != null || !backStack.isEmpty();
    }

    public boolean canGoForward() {
        if (workspaceHistory != null) {
            return !workspaceHistory.history().forward().isEmpty();
        }
        return !forwardStack.isEmpty();
    }

    /** Erfährt jeden Schritt per Zurück/Vor (die Bereichsanzeige oben, der Baum links). */
    public void onNavigated(java.util.function.Consumer<Place> listener) {
        this.navigated = java.util.Objects.requireNonNull(listener, "listener");
    }

    /** Reveal the document view before loading a history destination from another view. */
    void beforeHistoryNavigation(Runnable listener) {
        beforeHistoryNavigation = java.util.Objects.requireNonNull(listener, "listener");
    }

    void searchFront(boolean active) {
        searchFront = active;
        updateNavigation();
    }

    private com.aresstack.spaceharvester.app.ui.controls.BackButton.History documentHistory() {
        List<String> back = new java.util.ArrayList<>();
        Place shown = current();
        if (searchFront && shown != null) {
            back.addAll(names(new java.util.ArrayDeque<>(List.of(shown))));
        }
        back.addAll(names(backStack));
        return new com.aresstack.spaceharvester.app.ui.controls.BackButton.History(names(forwardStack), back);
    }

    /** Return to the last document first when the search temporarily covers the document viewer. */
    private boolean navigateHistory(int steps) {
        if (steps == 0 || context.runtime() == null) {
            return false;
        }
        Place shown = current();
        if (steps < 0 && searchFront && shown != null) {
            int remaining = -steps - 1;
            if (remaining > backStack.size()) {
                return false;
            }
            if (remaining == 0) {
                beforeHistoryNavigation.run();
                searchFront(false);
                navigated.accept(shown);
                return true;
            }
            return step(backStack, forwardStack, remaining);
        }
        return steps < 0 ? step(backStack, forwardStack, -steps) : step(forwardStack, backStack, steps);
    }

    /** Springt {@code steps} Orte weit; die übersprungenen wandern ungeladen in den Verlauf der Gegenrichtung. */
    private boolean step(java.util.Deque<Place> from, java.util.Deque<Place> to, int steps) {
        if (steps < 1 || from.size() < steps || context.runtime() == null) {
            return false;
        }
        beforeHistoryNavigation.run();
        searchFront = false;
        Place now = current();
        Place next = null;
        for (int i = 0; i < steps; i++) {
            if (now != null) {
                to.push(now);
            }
            next = from.pop();
            now = next;
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

    void useWorkspaceHistory(com.aresstack.spaceharvester.app.ui.controls.BackButton.Navigator navigator,
                             java.util.function.Consumer<Place> listener) {
        workspaceHistory = java.util.Objects.requireNonNull(navigator);
        locationVisited = java.util.Objects.requireNonNull(listener);
        backButton.navigator(navigator);
    }

    private boolean goWorkspace(int steps) {
        if (steps < 0 ? !canGoBack() : !canGoForward()) {
            return false;
        }
        workspaceHistory.go(steps);
        updateNavigation();
        return true;
    }

    void refreshHistoryNavigation() {
        updateNavigation();
    }

    java.util.Optional<Place> currentHistoryPlace() {
        return java.util.Optional.ofNullable(current());
    }

    void restoreHistoryPlace(Place place) {
        navigating = true;
        try {
            if (place.page() && !showsPage(place.sourceId(), place.pageId())) {
                load(place.sourceId(), place.pageId(), false);
            } else if (!place.page() && !showsLevel(place.sourceId(), place.spaceKey())) {
                showSource(place.sourceId(), place.spaceKey(), place.title());
            }
        } finally {
            navigating = false;
        }
        navigated.accept(place);
    }

    private void updateNavigation() {
        backButton.refresh();
    }

    /** Namen der Orte im Verlauf, der nächste zuerst. */
    private static java.util.List<String> names(java.util.Deque<Place> places) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Place place : places) {
            String name = place.title() == null || place.title().isBlank() ? null : place.title().strip();
            names.add(name != null ? name : place.page() ? "Seite " + place.pageId()
                    : place.spaceKey() != null ? place.spaceKey() : place.sourceId());
        }
        return names;
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
        // Respect independent mouse navigation in other perspectives, not in the search view.
        if (javax.swing.SwingUtilities.getRootPane(source) instanceof javax.swing.JRootPane root
                && Boolean.TRUE.equals(root.getClientProperty(OWN_MOUSE_NAVIGATION))) {
            return false;
        }
        for (java.awt.Component c = source; c != null; c = c.getParent()) {
            if (c instanceof javax.swing.JComponent jc && jc.getClientProperty(OWN_MOUSE_NAVIGATION) != null) {
                return false;
            }
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
        // Das Verzeichnis der Verweise soll das Panel nicht festhalten; beim nächsten Laden meldet es sich neu an.
        linksSubscription.close();
        linksSubscription = com.aresstack.spaceharvester.application.observe.Subscription.NONE;
        linksRuntime = null;
        referencesLoaded = false;
        // Auch die Rückmeldung von „Anhänge ablegen“ soll das Panel nicht festhalten.
        if (harvestWatch != null) {
            harvestWatch.close();
            harvestWatch = null;
        }
        // Ebenso die Rückmeldung von „Anhänge vorladen“ (ein angehaltener Teil endet vielleicht nie).
        leavePrefetch(null);
        super.removeNotify();
    }

    /** Der Zurück-Knopf (Tests). */
    javax.swing.JButton backButton() {
        return backButton;
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
        watchLinks(runtime);
        referencesLoaded = true;
        if (!references.showing()) {
            references.message("Verweise werden geladen …");
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        long generation = ++referencesGeneration;
        Background.run(() -> runtime.workspace().references(source, id), found -> {
            if (shown == page && generation == referencesGeneration) {
                references.show(found);
            }
        }, error -> {
            if (shown != page || generation != referencesGeneration) {
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
                showImages(found);
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
     * eine nicht freigegebene Quelle, aber nur diese Seite und ohne gespeicherte Freigabe. Stehen die Bilder schon in
     * der Liste, Bild für Bild: jede Beschreibung erscheint, sobald sie fertig ist; schon beschriebene bleiben (bei
     * veralteten Beschreibungen kommen unveränderte Bilder aus dem Speicher der Beschreibungen).
     */
    private void describeImages(boolean once) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        ImageRows before = shownImages;
        List<ImagesTab.Image> rows = images.rows();
        if (before == null || rows.isEmpty()) {
            describeAllImages(runtime, once);
            return;
        }
        boolean stale = before.state() == PassagesTab.State.STALE;
        List<Integer> todo = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            ImagesTab.Status status = rows.get(index).status();
            boolean finished = status == ImagesTab.Status.SKIPPED || status == ImagesTab.Status.DESCRIBED && !stale;
            if (!finished && !images.running(index)) {
                todo.add(index);
            }
        }
        if (todo.isEmpty()) {
            feedback.success("Alle Bilder dieser Seite sind schon beschrieben");
            return;
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        images.describing(true);
        feedback.working("Bilder werden beschrieben … 0 von " + todo.size());
        int[] done = {0};
        Background.run(() -> {
            loadVisionModel(runtime, shown);
            ImageRows last = null;
            for (int index : todo) {
                if (shown != page) {
                    break;
                }
                int at = index;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (shown == page) {
                        images.running(at, true);
                    }
                });
                ImageRows found = imageRows(runtime, source, id,
                        runtime.workspace().describeImage(source, id, at, once, false));
                Icon icon = at < found.bytes().size() ? preview(found.bytes().get(at)) : null;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (shown == page) {
                        done[0]++;
                        imageDescribed(at, found, icon);
                        feedback.working("Bilder werden beschrieben … " + done[0] + " von " + todo.size());
                    }
                });
                last = found;
            }
            return last;
        }, found -> {
            images.describing(false);
            if (shown != page || found == null) {
                return;
            }
            showImages(found);
            feedback.success("Bilder beschrieben: " + found.described() + " von " + found.images().size());
        }, error -> {
            images.describing(false);
            if (shown != page) {
                return;
            }
            if (shownImages != null) {
                showImages(shownImages);
            }
            feedback.problem(error, Problems.Step.PAGE);
        });
    }

    /**
     * Beschreibt nur das Bild einer Zeile, etwa nach einem Fehler; mit {@code fresh} neu („Neu beschreiben“). Die
     * Zeile zeigt „Wird beschrieben …“, bis das Ergebnis da ist.
     */
    private void describeImage(int index, boolean fresh) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || images.running(index)) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        images.running(index, true);
        Background.run(() -> {
            loadVisionModel(runtime, shown);
            ImageRows found = imageRows(runtime, source, id,
                    runtime.workspace().describeImage(source, id, index, false, fresh));
            Icon icon = index < found.bytes().size() ? preview(found.bytes().get(index)) : null;
            return new DescribedRow(found, icon);
        }, described -> {
            if (shown != page) {
                return;
            }
            shownImages = described.rows();
            imageDescribed(index, described.rows(), described.icon());
        }, error -> {
            if (shown != page) {
                return;
            }
            images.running(index, false);
            feedback.problem(error, Problems.Step.PAGE);
        });
    }

    /**
     * Außerhalb des EDT: Läuft das Vision-Modell noch nicht (bei Ollama laut {@code /api/ps}), zeigt der Reiter
     * „Vision-Modell wird geladen …“, bis es geladen ist, höchstens die eingestellte Ladefrist. Scheitert das Laden,
     * meldet der Lauf den Fehler einmal statt je Bild.
     */
    private void loadVisionModel(AppRuntime runtime, DeliveredPage shown) {
        if (runtime.workspace().visionModelLoaded()) {
            return;
        }
        javax.swing.SwingUtilities.invokeLater(() -> {
            if (shown == page) {
                images.modelLoading(true);
                feedback.working(ImagesTab.MODEL_LOADING);
            }
        });
        try {
            runtime.workspace().loadVisionModel();
        } finally {
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (shown == page) {
                    images.modelLoading(false);
                }
            });
        }
    }

    /** Die Bilder nach einer Einzelbeschreibung und die Vorschau des eben beschriebenen. */
    private record DescribedRow(ImageRows rows, Icon icon) {
    }

    /** Zeigt ein eben beschriebenes Bild in seiner Zeile und im offenen Viewer. */
    private void imageDescribed(int index, ImageRows found, Icon icon) {
        if (index >= found.images().size()) {
            images.running(index, false);
            return;
        }
        ImagesTab.Image image = found.images().get(index);
        images.update(index, image, icon);
        liveImages.put(index, image);
        liveBytes.put(index, found.bytes().get(index));
        imageUpdated.accept(index);
    }

    /** Wie {@link #describeImages}, ohne Liste: alle Bilder in einem Lauf des Core, gestreamt. */
    private void describeAllImages(AppRuntime runtime, boolean once) {
        String source = sourceId;
        String id = pageId;
        DeliveredPage shown = page;
        images.describing(true);
        feedback.working("Bilder werden beschrieben …");
        int[] done = {0};
        Background.run(() -> imageRows(runtime, source, id, runtime.workspace().describeImages(source, id, once,
                entry -> {
                    // Gestreamt: jede fertige Beschreibung erscheint sofort in ihrer Zeile und im offenen Viewer.
                    ImagesTab.Image image = imageOf(entry);
                    Icon icon = entry.sha256() == null ? null
                            : preview(() -> runtime.workspace().storedImage(source, entry));
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (shown != page) {
                            return;
                        }
                        done[0]++;
                        images.update(entry.index(), image, icon);
                        if (shownImages != null && entry.index() < shownImages.images().size()) {
                            liveImages.put(entry.index(), image);
                            if (entry.sha256() != null) {
                                liveBytes.put(entry.index(), () -> runtime.workspace().storedImage(source, entry));
                            }
                            imageUpdated.accept(entry.index());
                        }
                        feedback.working("Bilder werden beschrieben … " + done[0]);
                    });
                })), found -> {
            // Der Reiter gehört allen Seiten: auch nach einem Seitenwechsel wieder freigeben.
            images.describing(false);
            if (shown != page) {
                return;
            }
            showImages(found);
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
     * Beim Öffnen eines Videos: gespeicherte Marken zeigt der Player sofort; sonst sucht er sie im Video
     * ({@link #slidesFound} speichert sie dann).
     */
    private void showVideoMarks(VideoViewerPanel viewer, VideoFrameSource video) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        Background.run(() -> runtime.workspace().videoMarks(video.sourceId(), video.pageId()), marks -> {
            if (viewer != originalVideoViewer) {
                return;
            }
            if (marks.isEmpty()) {
                viewer.findSlides();
            } else {
                viewer.showMarks(marks);
            }
        }, error -> {
            if (viewer == originalVideoViewer) {
                viewer.findSlides();
            }
        });
    }

    /**
     * Der Player hat die Marken des Videos gefunden: gespeichert erscheinen sie als virtuelle Anhänge (benannt nach
     * ihrer Position), deren Standbilder „Anhänge“ zieht. Ohne Marken gibt es keine virtuellen Anhänge.
     */
    private void slidesFound(VideoFrameSource video, List<Long> changes) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || video != videoFrames || changes.isEmpty()) {
            return;
        }
        // Wie viele Marken ein Video höchstens bekommt, hat die Erkennung schon begrenzt (die stärksten Wechsel).
        List<Long> marks = changes;
        Background.run(() -> {
            runtime.workspace().saveVideoMarks(video.sourceId(), video.pageId(), marks);
            return marks.size();
        }, saved -> attachmentsChanged(video), error -> {
            if (video == videoFrames) {
                feedback.problem(error, Problems.Step.PAGE);
            }
        });
    }

    /**
     * „In PPTX umwandeln“: startet die Aufträge Transkript ziehen, Folien beschreiben, Transkript korrigieren,
     * Folienkommentare und PPTX erzeugen; sie stehen einzeln unter „Aufträge“. Die Statusmeldung „gestartet“ ersetzt
     * sich durch das Ergebnis, sobald der letzte Auftrag endet.
     */
    private void convertToPresentation(VideoFrameSource video,
                                       com.aresstack.spaceharvester.application.sources.MediaByteRange ranges,
                                       long size, String mediaType, com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format format) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || video != videoFrames) {
            return;
        }
        String name = "„In " + format.label() + " umwandeln“";
        // „Transkript ziehen“ braucht das in der Transkriptansicht gewählte Whisper-Modell: fehlt es, erst fragen.
        com.aresstack.spaceharvester.app.transcription.WhisperModel whisper = videoTranscript.transcriptionModel();
        context.whisperModel().ensure(whisper, this, feedback::working,
                reason -> feedback.warning(name + " nicht gestartet", reason),
                () -> startPresentation(runtime, video, ranges, size, mediaType, format, whisper));
    }

    private void startPresentation(AppRuntime runtime, VideoFrameSource video,
                                   com.aresstack.spaceharvester.application.sources.MediaByteRange ranges,
                                   long size, String mediaType, com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format format,
                                   com.aresstack.spaceharvester.app.transcription.WhisperModel whisper) {
        if (video != videoFrames) {
            return;
        }
        String name = "„In " + format.label() + " umwandeln“";
        com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia media =
                new com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia(ranges, size, mediaType,
                        context.video(), new TranscribeAudioUseCase(new VlcPcmAudioDecoder(),
                                new WhisperSpeechTranscriber()), whisper);
        feedback.working(name + " wird gestartet …");
        java.nio.file.Path directory = context.settings() == null
                ? com.aresstack.spaceharvester.app.settings.AppSettings.defaultOutputDirectory()
                : context.exportDirectory();
        Background.run(() -> runtime.workspace().startVideoPresentation(video.sourceId(), video.pageId(), media,
                        format, directory),
                run -> {
                    if (video == videoFrames) {
                        feedback.working(name + " läuft: fünf Aufträge unter „Aufträge“, die Datei kommt "
                                + "in den Ausgabeordner.");
                    }
                    watchPresentation(runtime, video, run.file().id(), format);
                }, error -> {
                    if (video != videoFrames) {
                        return;
                    }
                    if (error instanceof com.aresstack.spaceharvester.application.presentation
                            .PresentationUnavailableException unavailable) {
                        feedback.warning(name + " kann nicht starten", unavailable.getMessage());
                    } else {
                        feedback.problem(error, Problems.Step.PAGE);
                    }
                });
    }

    /**
     * Wartet per Beobachter (ADR 0027) auf das Ende des Auftrags „PPTX erzeugen“ und meldet die Datei; liest nach dem
     * Anmelden einmal den Stand, damit ein schon beendeter Auftrag nicht verpasst wird.
     */
    private void watchPresentation(AppRuntime runtime, VideoFrameSource video, String fileJob,
                                   com.aresstack.spaceharvester.application.presentation.VideoPresentations.Format format) {
        java.util.concurrent.atomic.AtomicReference<com.aresstack.spaceharvester.application.observe.Subscription>
                watch = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicBoolean ended = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.function.Consumer<com.aresstack.spaceharvester.application.jobs.JobStatus> handle = status -> {
            if (!fileJob.equals(status.id()) || !status.state().terminal()
                    || status.state() == com.aresstack.spaceharvester.application.jobs.JobState.PAUSED
                    || !ended.compareAndSet(false, true)) {
                return;
            }
            java.util.Optional<java.nio.file.Path> file = status.state()
                    == com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED
                    ? runtime.workspace().presentationFile(fileJob) : java.util.Optional.empty();
            javax.swing.SwingUtilities.invokeLater(() -> {
                com.aresstack.spaceharvester.application.observe.Subscription subscription = watch.getAndSet(null);
                if (subscription != null) {
                    subscription.close();
                }
                if (video != videoFrames) {
                    return;
                }
                if (file.isPresent()) {
                    feedback.info(format.label() + " erzeugt: " + file.get().getFileName()
                            + (status.incomplete() ? " (unvollständig, siehe „Aufträge“)" : ""),
                            file.get().toString());
                } else {
                    feedback.warning("„In " + format.label() + " umwandeln“ ist nicht fertig geworden",
                            "Die Aufträge unter „Aufträge“ zeigen, welcher Schritt fehlt; Fortsetzen macht dort weiter.");
                }
            });
        };
        watch.set(runtime.workspace().subscribeJobs(handle::accept));
        Background.run(() -> runtime.workspace().job(fileJob), handle, error -> {
            // Unbekannt (etwa gelöscht): nichts zu melden.
        });
    }

    /**
     * „Korrigieren“ in der Transkriptansicht (Angelo 20.11.2026): Transkript, Standbilder, Folien beschreiben und
     * Transkript korrigieren als Aufträge wie vor „In PPTX umwandeln“, ohne Datei. Die Ansicht zeigt die neue Korrektur,
     * sobald der Auftrag fertig ist; die Analysen rechnen danach über sie.
     */
    private void correctTranscript(VideoFrameSource video,
                                   com.aresstack.spaceharvester.application.sources.MediaByteRange ranges,
                                   long size, String mediaType) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || video == null || video != videoFrames) {
            return;
        }
        String name = "„Transkript korrigieren“";
        com.aresstack.spaceharvester.app.transcription.WhisperModel whisper = videoTranscript.transcriptionModel();
        context.whisperModel().ensure(whisper, this, feedback::working,
                reason -> feedback.warning(name + " nicht gestartet", reason), () -> {
                    if (video != videoFrames) {
                        return;
                    }
                    com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia media =
                            new com.aresstack.spaceharvester.app.ui.content.viewer.VideoPresentationMedia(ranges,
                                    size, mediaType, context.video(), new TranscribeAudioUseCase(
                                            new VlcPcmAudioDecoder(), new WhisperSpeechTranscriber()), whisper);
                    feedback.working(name + " wird gestartet …");
                    Background.run(() -> runtime.workspace().startVideoCorrection(video.sourceId(), video.pageId(),
                            media), run -> {
                        if (video == videoFrames) {
                            feedback.working(name + " läuft: drei Aufträge unter „Aufträge“; danach rechnen die "
                                    + "Analysen über die Korrektur.");
                        }
                    }, error -> {
                        if (video != videoFrames) {
                            return;
                        }
                        if (error instanceof com.aresstack.spaceharvester.application.presentation
                                .PresentationUnavailableException unavailable) {
                            feedback.warning(name + " kann nicht starten", unavailable.getMessage());
                        } else {
                            feedback.problem(error, Problems.Step.PAGE);
                        }
                    });
                });
    }

    /**
     * Fragt nach und löscht dann das gezeigte Original, seine Korrekturen und die Analysen dazu (Angelo 20.11.2026);
     * gibt es ein weiteres Original, zeigt die Ansicht danach dieses.
     */
    private void deleteTranscript(VideoTranscriptSource transcriptSource, VideoFrameSource video) {
        AppRuntime runtime = context.runtime();
        String whisper = videoTranscript.whisper();
        if (runtime == null || video == null || video != videoFrames
                || !com.aresstack.spaceharvester.app.ui.Dialogs.confirm(this, "Transkript löschen?",
                transcriptDeleteQuestion(whisper))) {
            return;
        }
        Background.run(() -> {
            runtime.workspace().deleteVideoTranscript(video.sourceId(), video.pageId(), whisper);
            return runtime.workspace().videoTranscripts(video.sourceId(), video.pageId());
        }, transcripts -> {
            if (video != videoFrames) {
                return;
            }
            videoTranscript.cleared(transcriptSource);
            showTranscripts(transcriptSource, transcripts);
            attachmentsChanged(video);
        }, error -> {
            if (video == videoFrames) {
                feedback.problem(error, Problems.Step.PAGE);
            }
        });
    }

    /** Die Rückfrage vor dem Löschen des Transkripts. */
    static final String TRANSCRIPT_DELETE_QUESTION = "Wirklich das Transkript samt allen Korrekturen löschen? Die "
            + "Analysen dazu fallen mit weg.";

    /** Die Rückfrage vor dem Löschen des Originals dieses Whisper-Modells, etwa mit {@code (Whisper Tiny)}. */
    static String transcriptDeleteQuestion(String whisper) {
        if (whisper == null || whisper.isEmpty()) {
            return TRANSCRIPT_DELETE_QUESTION;
        }
        String name = new com.aresstack.spaceharvester.app.ui.content.viewer.VideoTranscriptionPanel.Original(whisper,
                false).toString();
        return "Wirklich das Transkript (" + name + ") samt allen Korrekturen löschen? Die Analysen dazu fallen mit "
                + "weg.";
    }

    /** Wählt das Original eines Whisper-Modells (Auswahl in der Transkriptansicht) und zeigt es samt Korrekturen. */
    private void chooseOriginal(VideoTranscriptSource transcriptSource, VideoFrameSource video, String whisper) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || video == null || video != videoFrames) {
            videoTranscript.loadingStored(transcriptSource, false);
            return;
        }
        Background.run(() -> runtime.workspace().chooseVideoTranscript(video.sourceId(), video.pageId(), whisper),
                transcripts -> {
                    if (video == videoFrames) {
                        showTranscripts(transcriptSource, transcripts);
                    }
                }, error -> {
                    videoTranscript.loadingStored(transcriptSource, false);
                    if (video == videoFrames) {
                        feedback.problem(error, Problems.Step.PAGE);
                    }
                });
    }

    /** Zeigt Originale, gewähltes Original und seine Fassungen in der Transkriptansicht. */
    private void showTranscripts(VideoTranscriptSource transcriptSource, Workspace.VideoTranscripts transcripts) {
        videoTranscript.loadingStored(transcriptSource, false);
        videoTranscript.showOriginals(transcriptSource, transcripts.originals(), transcripts.chosen());
        if (!transcripts.versions().isEmpty()) {
            videoTranscript.showStored(transcriptSource, transcripts.versions().get(0).text());
            videoTranscript.showVersions(transcriptSource, versions(transcripts));
        }
    }

    private static List<com.aresstack.spaceharvester.app.ui.content.viewer.VideoTranscriptionPanel.Version> versions(
            Workspace.VideoTranscripts transcripts) {
        return transcripts.versions().stream()
                .map(version -> new com.aresstack.spaceharvester.app.ui.content.viewer.VideoTranscriptionPanel
                        .Version(version.label(), version.text()))
                .toList();
    }

    /**
     * Liest Analysen und Lesetext der gezeigten Seite neu, nachdem der Core sie zum Transkript neu gerechnet hat; ein
     * Laden einer anderen Seite verwirft das Ergebnis.
     */
    private void reloadAnalyses(PageKey key) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || !page.snapshot().key().equals(key)) {
            return;
        }
        int current = request;
        DeliveredPage before = page;
        Font font = reader.getFont();
        Background.run(() -> {
            DeliveredPage cached = runtime.workspace().page(key.sourceId(), key.pageId(), false);
            List<Enrichments.View> updated = runtime.workspace().enrichments(cached);
            PageText text = runtime.workspace().pageText(cached.snapshot());
            ReaderDocument document = text == null ? null : document(key.sourceId(), key.pageId(), updated, text,
                    font);
            if (document != null) {
                relations(runtime, document, key.sourceId(), cached.header().metadata().partitionKey());
                navigation(runtime, document, key.sourceId(), key.pageId());
            }
            return new Recomputed(new Enrichments.Run(text != null, 0, 0, 0), updated, document);
        }, result -> {
            if (current != request || page != before) {
                return;
            }
            views = result.views();
            analysisView.steps(views, this::stepResult);
            if (page != null) showProgramAnalysis(page.snapshot());
            showReader(result.reader(), page.live());
            statementsView.page(page.snapshot(), result.reader() == null ? null : result.reader().text());
        }, error -> {
            // Nicht lesbar: die Ansicht bleibt beim vorigen Stand.
        });
    }

    /** Lädt das gespeicherte Transkript des geöffneten Videos in den Reiter „Text“, statt es neu zu erzeugen. */
    private void showStoredTranscript(VideoTranscriptSource transcriptSource, VideoFrameSource video) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || video == null) {
            return;
        }
        watchTranscript(runtime, transcriptSource, video);
        // Das gespeicherte Transkript kommt verzögert wie die Anhänge: bis dahin die Sanduhr.
        videoTranscript.loadingStored(transcriptSource, true);
        // Mit den Originalen je Whisper-Modell und den Korrekturen, die als Ergänzung daneben liegen.
        Background.run(() -> runtime.workspace().videoTranscripts(video.sourceId(), video.pageId()),
                transcripts -> showTranscripts(transcriptSource, transcripts),
                error -> videoTranscript.loadingStored(transcriptSource, false));
    }

    /** Meldungen der neu gerechneten Analysen zum Transkript des gezeigten Videos; {@code null} ohne Video. */
    private com.aresstack.spaceharvester.application.observe.Subscription analysesWatch;

    /** Ist „Transkript korrigieren“ für das gezeigte Video fertig, zeigt die Ansicht die Fassungen neu. */
    private void correctionEnded(AppRuntime runtime, VideoTranscriptSource transcriptSource, VideoFrameSource video,
                                 com.aresstack.spaceharvester.application.jobs.JobStatus status) {
        if (status.type() != com.aresstack.spaceharvester.application.jobs.JobType.TRANSCRIPT_CORRECTION
                || status.state() != com.aresstack.spaceharvester.application.jobs.JobState.COMPLETED
                || !video.sourceId().equals(status.sourceId()) || !video.pageId().equals(status.parameters()
                .get(com.aresstack.spaceharvester.application.presentation.VideoPresentations.PAGE_PARAMETER))) {
            return;
        }
        Background.run(() -> runtime.workspace().videoTranscripts(video.sourceId(), video.pageId()),
                transcripts -> {
                    if (video == videoFrames && !transcripts.versions().isEmpty()
                            && transcripts.chosen().equals(videoTranscript.whisper())) {
                        videoTranscript.showVersions(transcriptSource, versions(transcripts));
                    }
                }, error -> {
                    // Nicht lesbar: die Fassungen bleiben, wie sie sind.
                });
    }

    /** Meldungen der Aufträge „Transkript ziehen“ für das gezeigte Video; {@code null} ohne Video. */
    private com.aresstack.spaceharvester.application.observe.Subscription transcriptWatch;

    /**
     * Lässt den Reiter „Text“ mitwachsen, während „Transkript ziehen“ für dieses Video im Hintergrund läuft (Angelo
     * 20.11.2026): Jede Meldung des Auftrags aus dem Core (ADR 0027, kein Abfragen in Abständen) liest das abgelegte
     * Transkript neu; die Ansicht hängt nur das Neue an.
     */
    private void watchTranscript(AppRuntime runtime, VideoTranscriptSource transcriptSource, VideoFrameSource video) {
        stopTranscriptWatch();
        // Jede Meldung bekommt eine Nummer; ein Ergebnis, das nach dem einer jüngeren Meldung ankommt, fällt weg.
        java.util.concurrent.atomic.AtomicLong issued = new java.util.concurrent.atomic.AtomicLong();
        long[] applied = {0};
        java.util.function.Consumer<com.aresstack.spaceharvester.application.jobs.JobStatus> handle = status -> {
            if (status.type() != com.aresstack.spaceharvester.application.jobs.JobType.VIDEO_TRANSCRIPT
                    || !video.sourceId().equals(status.sourceId()) || !video.pageId().equals(status.parameters()
                    .get(com.aresstack.spaceharvester.application.presentation.VideoPresentations.PAGE_PARAMETER))) {
                return;
            }
            long ticket = issued.incrementAndGet();
            boolean running = !status.state().terminal();
            Background.run(() -> runtime.workspace().videoTranscripts(video.sourceId(), video.pageId()), stored -> {
                if (video != videoFrames || ticket < applied[0]) {
                    return;
                }
                applied[0] = ticket;
                // Der Auftrag schreibt in das gewählte Original; zeigt die Auswahl ein anderes, schaltet sie um.
                videoTranscript.followStored(transcriptSource, stored.chosen(),
                        stored.versions().isEmpty() ? null : stored.versions().get(0).text(), running);
            }, error -> {
                // Nicht lesbar (etwa Seite inzwischen gesperrt): die Ansicht bleibt, wie sie ist.
            });
        };
        transcriptWatch = runtime.workspace().subscribeJobs(status -> {
            handle.accept(status);
            correctionEnded(runtime, transcriptSource, video, status);
        });
        PageKey shownKey = new PageKey(video.sourceId(), video.pageId());
        analysesWatch = runtime.workspace().subscribeTranscriptAnalyses(key -> {
            if (shownKey.equals(key)) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (video == videoFrames) {
                        reloadAnalyses(key);
                    }
                });
            }
        });
        // Erst anmelden, dann den Stand lesen: Läuft der Auftrag schon, folgt die Ansicht sofort (ADR 0027).
        Background.run(() -> runtime.workspace().jobs(), jobs -> jobs.stream()
                .filter(job -> !job.state().terminal()).forEach(handle), error -> {
            // Ohne Liste folgt die Ansicht ab der nächsten Meldung.
        });
    }

    private void stopTranscriptWatch() {
        if (transcriptWatch != null) {
            transcriptWatch.close();
            transcriptWatch = null;
        }
        if (analysesWatch != null) {
            analysesWatch.close();
            analysesWatch = null;
        }
    }

    /**
     * Speichert das bisher erkannte Transkript des gezeigten Videos als virtuellen Anhang; die Liste unter „Anhänge“
     * zeigt es danach.
     */
    private void storeTranscript(String whisper, String text) {
        AppRuntime runtime = context.runtime();
        VideoFrameSource video = videoFrames;
        if (runtime == null || video == null || text.isBlank()) {
            return;
        }
        String name = transcriptName(page == null ? null : page.header().metadata().title());
        Background.run(() -> {
            runtime.workspace().storeVideoTranscript(video.sourceId(), video.pageId(), whisper, name, text);
            return null;
        }, saved -> attachmentsChanged(video), error -> {
            if (video == videoFrames) {
                feedback.problem(error, Problems.Step.PAGE);
            }
        });
    }

    /** Der Dateiname des Transkripts nach dem Video, etwa {@code Vortrag – Transkript.txt}. */
    static String transcriptName(String videoTitle) {
        String base = videoTitle == null || videoTitle.isBlank() ? "Video" : videoTitle.strip();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + " – Transkript.txt";
    }

    /** Lädt die Anhänge der gezeigten Seite im Hintergrund vor; für welche Seite. */
    private AttachmentPrefetch prefetch;
    private String prefetchPage;

    /**
     * Der Vorlader der gezeigten Seite: lädt Anhänge und legt sie ab, Marken eines Videos bekommen dabei ihr
     * Standbild aus einem offenen Player. Ein neuer für jede Seite.
     */
    private AttachmentPrefetch prefetch(AppRuntime runtime) {
        String key = sourceId + "\n" + pageId;
        if (prefetch != null && key.equals(prefetchPage)) {
            return prefetch;
        }
        if (prefetch != null) {
            prefetch.close();
        }
        String source = sourceId;
        String id = pageId;
        VideoFrameSource video = videoFrames;
        prefetchPage = key;
        prefetch = new AttachmentPrefetch(new AttachmentPrefetch.Source() {
            private VideoFrameGrabber.Session session;

            @Override
            public byte[] load(String attachment) throws Exception {
                if (video != null && com.aresstack.spaceharvester.application.assets.Assets.videoMark(attachment)) {
                    long millis = com.aresstack.spaceharvester.application.assets.Assets.markMillis(attachment);
                    if (runtime.workspace().missingVideoFrames(source, id).contains(millis)) {
                        if (session == null) {
                            session = VideoFrameGrabber.Session.open(video.reader(), video.size(), video.mediaType(),
                                    () -> video != videoFrames, context.video());
                        }
                        com.aresstack.spaceharvester.application.assets.Assets.VideoFrame frame = session.frame(millis);
                        if (frame != null) {
                            runtime.workspace().storeVideoFrame(source, id, frame);
                        }
                    }
                }
                return runtime.workspace().assetContent(source, id, attachment).content();
            }

            @Override
            public void idle() {
                if (session != null) {
                    session.close();
                    session = null;
                }
            }
        }, PageDetailPanel::reason, () -> attachmentsChanged(video),
                context.video().number(com.aresstack.spaceharvester.app.settings.VideoSettings.Value.PREFETCH_CACHED),
                context.video().number(com.aresstack.spaceharvester.app.settings.VideoSettings.Value.PREFETCH_BATCH),
                // Fundstelle 18: die übrigen als Hintergrundteil „Anhänge vorladen“; endet er, gilt die Liste neu.
                rest -> runtime.workspace().prefetchAttachments(source, id, rest).map(part -> {
                    javax.swing.SwingUtilities.invokeLater(() -> watchPrefetch(runtime, part.id(), video, key));
                    return true;
                }).orElse(false),
                com.aresstack.spaceharvester.application.assets.Assets::videoMark);
        return prefetch;
    }

    /** Wacht über den Hintergrundteil, der die übrigen Anhänge der Seite vorlädt; einer je Seite. */
    private com.aresstack.spaceharvester.application.observe.Subscription prefetchWatch;
    /** Für welche Seite ({@code Quelle \n Seite}). */
    private String prefetchWatchPage;

    /** Folgt dem Teil, der die übrigen Anhänge der Seite {@code page} ({@code Quelle \n Seite}) vorlädt (EDT). */
    void watchPrefetch(AppRuntime runtime, String jobId, VideoFrameSource video, String page) {
        String shown = sourceId + "\n" + pageId;
        if (!shown.equals(page)) {
            // Die Seite ist schon gewechselt: Ihre Rückmeldung gehört nicht hierher.
            return;
        }
        leavePrefetch(null);
        prefetchWatchPage = shown;
        prefetchWatch = runtime.workspace().whenJobFinished(jobId, done -> attachmentsChanged(video));
    }

    /** Löst die Rückmeldung von „Anhänge vorladen“, außer sie gehört zu {@code page} ({@code null}: immer). */
    private void leavePrefetch(String page) {
        if (prefetchWatch != null && !java.util.Objects.equals(page, prefetchWatchPage)) {
            prefetchWatch.close();
            prefetchWatch = null;
            prefetchWatchPage = null;
        }
    }

    /** Ob die Seitenansicht gerade einem Teil „Anhänge vorladen“ folgt (Tests). */
    boolean watchingPrefetch() {
        return prefetchWatch != null;
    }

    /** Die Anhänge derselben Art in der Reihenfolge der Liste. */
    private List<String> attachmentsOfKind(com.aresstack.spaceharvester.application.assets.AssetKind kind) {
        return assetsModel.rows.stream().filter(entry -> entry.kind() == kind)
                .map(entry -> entry.attachment().attachmentId()).toList();
    }

    /**
     * Die Folien des gezeigten Videos haben sich unter „Anhänge“ geändert: Liste, Bilder und die Marken im Player
     * gelten neu (ein neues Standbild kann eine Marke als Wiederholung derselben Folie entfallen lassen).
     */
    private void attachmentsChanged(VideoFrameSource video) {
        if (video != videoFrames) {
            return;
        }
        if (originalVideoViewer != null) {
            showVideoMarks(originalVideoViewer, video);
        }
        imagesLoaded = false;
        assetsLoaded = false;
        if (ATTACHMENTS.equals(selectedTab())) {
            loadAssets(true);
        }
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
     * Meldet sich je Laufzeit einmal beim Verzeichnis der Verweise an (Paket V1, ADR 0027): Ändert es sich in der Quelle
     * der gezeigten Seite, gilt der geladene Stand nicht mehr, auch nicht der Zähler „aus N von M Seiten“; ist
     * „Verweise“ offen, lädt er still neu, ohne die Tabelle zu leeren.
     */
    private void watchLinks(AppRuntime runtime) {
        if (runtime == linksRuntime) {
            return;
        }
        linksSubscription.close();
        linksRuntime = runtime;
        linksSubscription = runtime.workspace().subscribeLinks(change -> javax.swing.SwingUtilities.invokeLater(() -> {
            // Die Verweise der Unterseiten kommen nur aus dem Verzeichnis: bei jeder Änderung an einer Unterseite neu.
            Subpages.Below below = subpages.below();
            if (below != null && change.sourceId().equals(below.sourceId()) && (change.pageIds().isEmpty()
                    || change.pageIds().stream().anyMatch(id -> below.pages().contains(below.sourceId(), id)))) {
                loadBelowReferences();
            }
            // Nur die ganze Quelle oder die gezeigte Seite: references() fragt die ausgehenden Verweise live bei der
            // Quelle, das soll nicht jede gebündelte Meldung eines Sync auslösen.
            if (!affects(change, sourceId, pageId)) {
                return;
            }
            referencesLoaded = false;
            if (REFERENCES.equals(analysesTab())) {
                loadReferences();
            }
        }));
    }

    /** Ob eine Änderung des Verzeichnisses die gezeigte Seite betrifft: ganze Quelle oder genau diese Seite. */
    static boolean affects(com.aresstack.spaceharvester.application.links.PageLinks.LinksChanged change,
                           String sourceId, String pageId) {
        return change.sourceId().equals(sourceId)
                && (change.pageIds().isEmpty() || pageId != null && change.pageIds().contains(pageId));
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
                    // Vorlesen: die Freigabe hat sich geändert, das laufende endet und der Text wird neu gelesen
                    readingPane.readAloud().stop();
                    if (readingLoaded && page != null && context.runtime() != null) {
                        loadReadAloud(context.runtime(), sourceId, pageId, page);
                    }
                    imagesLoaded = false;
                    if (IMAGES.equals(analysesTab())) {
                        loadImages();
                    }
                });
            }
        });
    }

    /** Die Bilder für den Reiter: erst die Rahmen ohne Vorschau; die Bytes holt jeder aus der Ablage nach. */
    private static ImageRows imageRows(AppRuntime runtime, String sourceId, String pageId, PageImages found) {
        List<ImagesTab.Image> rows = new ArrayList<>();
        java.util.Map<String, String> versions;
        try {
            versions = found.result() == null ? java.util.Map.of()
                    : ImagePayload.attachmentVersions(found.result().payloadJson());
        } catch (IllegalArgumentException e) {
            versions = java.util.Map.of();
        }
        List<java.util.function.Supplier<java.util.Optional<byte[]>>> bytes = new ArrayList<>();
        for (ImagePayload.Entry entry : found.images()) {
            String version = entry.assetId() == null ? null : versions.get(entry.assetId());
            bytes.add(() -> entry.sha256() == null ? java.util.Optional.empty()
                    : runtime.workspace().storedImage(sourceId, entry, version));
            rows.add(imageOf(entry));
        }
        return new ImageRows(PassagesTab.State.valueOf(found.state().name()), found.reason(), found.warnings(),
                // Freigegeben ist die Quelle oder die Seite selbst (Liste freigegebener Seiten, ADR 0028).
                List.copyOf(rows), found.describedCount(), runtime.workspace().imagesReleased(sourceId, pageId),
                List.copyOf(bytes));
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

    /** @param bytes je Bild seine abgelegten Bytes, im Hintergrund zu lesen */
    private record ImageRows(PassagesTab.State state, String reason, List<String> warnings,
                             List<ImagesTab.Image> images, int described, boolean released,
                             List<java.util.function.Supplier<java.util.Optional<byte[]>>> bytes) {
    }

    /** Eine Zeile des Reiters aus einem Eintrag des Core, noch ohne Vorschau. */
    private static ImagesTab.Image imageOf(ImagePayload.Entry entry) {
        String name = entry.title().isBlank() ? entry.filename().isBlank() ? "Bild " + (entry.index() + 1)
                : entry.filename() : entry.title();
        return new ImagesTab.Image(name, ImagesTab.Status.valueOf(entry.status().name()), entry.description(),
                entry.reason(), null);
    }

    /** Die gezeigten Bilder, für den großen Viewer. */
    private ImageRows shownImages;
    /** Während eines Laufs fertig beschriebene Bilder je Zeile, bis die Liste neu gezeigt wird. */
    private final java.util.Map<Integer, ImagesTab.Image> liveImages = new java.util.HashMap<>();
    /** Die abgelegten Bytes dieser fertig beschriebenen Bilder. */
    private final java.util.Map<Integer, java.util.function.Supplier<java.util.Optional<byte[]>>> liveBytes =
            new java.util.HashMap<>();
    /** Meldet dem offenen Viewer, dass ein Bild eben beschrieben wurde. */
    private java.util.function.IntConsumer imageUpdated = index -> { };
    /** Zählt jedes Zeigen, damit ein später fertiger Lauf keine fremden Rahmen füllt. */
    private int imageFill;

    /**
     * Zeigt die Bilder: sofort die Rahmen mit Name und Stand, dann füllt ein Hintergrundlauf die Rahmen der Reihe nach
     * mit den Vorschauen aus der Ablage, ohne den EDT zu blockieren.
     */
    private void showImages(ImageRows found) {
        images.released(found.released());
        images.show(found.state(), found.reason(), found.warnings(), found.images());
        shownImages = found;
        liveImages.clear();
        liveBytes.clear();
        int fill = ++imageFill;
        Background.run(() -> {
            for (int index = 0; index < found.bytes().size() && fill == imageFill; index++) {
                Icon icon = preview(found.bytes().get(index));
                int at = index;
                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (fill == imageFill) {
                        images.preview(at, icon);
                    }
                });
            }
            return null;
        }, done -> { }, error -> { });
    }

    /**
     * Öffnet ein Bild aus „Analysen › Bilder“ groß, mit seiner Beschreibung darunter; die Pfeile blättern zu den
     * übrigen Bildern der Seite. So lässt sich jede Beschreibung am Bild prüfen.
     */
    private void openImage(int index) {
        ImageRows found = shownImages;
        if (found == null || index < 0 || index >= found.images().size()) {
            return;
        }
        JPanel body = new JPanel(new BorderLayout(0, 8));
        body.setOpaque(false);
        javax.swing.JTextArea text = new javax.swing.JTextArea(4, 60);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(Comic.regular(13f));
        JLabel heading = Comic.text(" ");
        heading.setFont(Comic.semiBold(13f));
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(heading, BorderLayout.NORTH);
        south.add(Comic.scroll(text), BorderLayout.CENTER);
        body.add(south, BorderLayout.SOUTH);
        java.util.function.IntConsumer[] show = new java.util.function.IntConsumer[1];
        AttachmentViewerPanel[] viewer = new AttachmentViewerPanel[1];
        int[] showing = {index};
        show[0] = at -> {
            showing[0] = at;
            ImagesTab.Image image = liveImages.getOrDefault(at, found.images().get(at));
            if (viewer[0] != null) {
                body.remove(viewer[0]);
                viewer[0].close();
            }
            ReaderAttachment picture = new ReaderAttachment("image-" + at, image.name(), "image/png", -1);
            viewer[0] = new AttachmentViewerPanel(picture, new ReaderAttachmentLoader() {
                @Override
                public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                    // Auf dem EDT gewählt: ein eben beschriebenes Bild liegt inzwischen ab.
                    java.util.function.Supplier<java.util.Optional<byte[]>> bytes =
                            liveBytes.getOrDefault(at, found.bytes().get(at));
                    Background.run(() -> bytes.get().orElseThrow(() -> new IllegalStateException(
                            "Das Bild ist noch nicht abgelegt.")), done, error -> failed.accept(reason(error)));
                }
            }, fileOpener, attachmentRenderer);
            viewer[0].neighbours(new AttachmentViewerPanel.Neighbours() {
                @Override
                public boolean hasPrevious() {
                    return at > 0;
                }

                @Override
                public boolean hasNext() {
                    return at < found.images().size() - 1;
                }

                @Override
                public void previous() {
                    show[0].accept(at - 1);
                }

                @Override
                public void next() {
                    show[0].accept(at + 1);
                }
            });
            body.add(viewer[0], BorderLayout.CENTER);
            heading.setText(image.name() + " · " + ImagesTab.statusText(image));
            text.setText(image.description() == null ? "" : image.description());
            text.setCaretPosition(0);
            body.revalidate();
            body.repaint();
            viewer[0].requestFocusInWindow();
        };
        show[0].accept(index);
        // Wird das gezeigte Bild gerade beschrieben, erscheint seine Beschreibung sofort im Viewer.
        imageUpdated = at -> {
            if (at == showing[0]) {
                ImagesTab.Image image = liveImages.get(at);
                heading.setText(image.name() + " · " + ImagesTab.statusText(image));
                text.setText(image.description() == null ? "" : image.description());
                text.setCaretPosition(0);
            }
        };
        Runnable release = () -> {
            imageUpdated = at -> { };
            if (viewer[0] != null) {
                viewer[0].close();
            }
        };
        if (javax.swing.SwingUtilities.getRootPane(this) == null) {
            release.run();
            return;
        }
        // Ohne Fensterrahmen, wie die Bilder im Lesetext; ein Klick außerhalb oder Esc schließt.
        body.setBorder(javax.swing.BorderFactory.createEmptyBorder(8, 8, 8, 8));
        com.aresstack.spaceharvester.app.ui.controls.ViewerPopup.open(this, body, null).onClosed(release);
    }

    private void harvestAssets() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        // Fundstelle 24: ein Auftrag in der Auftragsliste, mit Fortschritt und Abbrechen dort; das Ergebnis ersetzt die
        // Meldung „gestartet“, gemeldet vom Core (UX, D3).
        Background.run(() -> runtime.workspace().startAssetHarvest(source, id), started -> {
            harvests.put(new PageKey(source, id), started.id());
            if (source.equals(sourceId) && id.equals(pageId)) {
                leaveHarvest();
                watchHarvest(runtime, started.id(), source, id);
            }
        }, feedback::problem);
    }

    /** Meldet „gestartet“ und folgt dem Auftrag: „angehalten“ zwischendurch, das Ergebnis am Ende (EDT). */
    private void watchHarvest(AppRuntime runtime, String jobId, String source, String id) {
        Runnable openJob = () -> context.navigator().showJob(jobId);
        tabNote(ATTACHMENTS, new StatusNote(HARVEST_STARTED, StatusNote.Tone.PLAIN, JOBS, openJob));
        harvestWatch = runtime.workspace().whenJobFinished(jobId, status -> {
            boolean ended = status.state() != com.aresstack.spaceharvester.application.jobs.JobState.PAUSED;
            if (ended) {
                harvests.remove(new PageKey(source, id), jobId);
            }
            if (source.equals(sourceId) && id.equals(pageId)) {
                tabNote(ATTACHMENTS, harvested(status, openJob));
                if (ended) {
                    loadAssets(true);
                }
            }
        });
    }

    /** Seite verlassen: Meldung und Beobachtung von „Anhänge ablegen“ räumen, der Auftrag läuft weiter. */
    private void leaveHarvest() {
        if (harvestWatch != null) {
            harvestWatch.close();
            harvestWatch = null;
        }
        tabNote(ATTACHMENTS, null);
    }

    /** Die Meldung „gestartet“ von „Anhänge ablegen“; „Aufträge“ ist der Link. */
    static final String HARVEST_STARTED = "Anhänge werden abgelegt; Fortschritt und Abbruch unter Aufträge.";
    private static final String JOBS = "Aufträge";

    /** Das Ergebnis von „Anhänge ablegen“ für die Statuszeile des Reiters; rot nur bei Fehlern. */
    static StatusNote harvested(JobStatus done, Runnable openJob) {
        com.aresstack.spaceharvester.application.jobs.JobProgress p = done.progress();
        long reused = Math.max(0, p.processed() - p.written() - p.failed());
        String summary = p.written() + " abgelegt, " + reused + " schon vorhanden, " + p.failed() + " fehlgeschlagen";
        return switch (done.state()) {
            case COMPLETED -> p.failed() > 0
                    ? new StatusNote("Anhänge nur teilweise abgelegt: " + summary + "; Gründe unter " + JOBS + ".",
                            StatusNote.Tone.ERROR, JOBS, openJob)
                    : done.incomplete()
                    ? new StatusNote("Anhänge abgelegt: " + summary + "; die Anhangsliste war unvollständig.",
                            StatusNote.Tone.WARNING, null, null)
                    : StatusNote.plain("Anhänge abgelegt: " + summary);
            case FAILED -> new StatusNote("Anhänge ablegen gescheitert; Grund unter " + JOBS + ".",
                    StatusNote.Tone.ERROR, JOBS, openJob);
            case PAUSED -> new StatusNote("Anhänge ablegen angehalten nach " + summary + "; fortsetzen unter " + JOBS
                    + ".", StatusNote.Tone.PLAIN, JOBS, openJob);
            default -> StatusNote.plain("Anhänge ablegen abgebrochen nach " + summary + "; das Abgelegte bleibt.");
        };
    }

    private void saveAsset() {
        AppRuntime runtime = context.runtime();
        int row = assets.getSelectedRow();
        if (runtime == null || row < 0) {
            return;
        }
        PageAssets.Entry entry = assetsModel.rows.get(row);
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(safeName(entry.attachment().title()));
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
        openAttachment(assets.getSelectedRow());
    }

    /** Öffnet den Anhang dieser Zeile; die Pfeile des Viewers blättern zu den Anhängen derselben Art. */
    private void openAttachment(int row) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null || row < 0 || row >= assetsModel.rows.size()) {
            return;
        }
        PageAssets.Entry entry = assetsModel.rows.get(row);
        com.aresstack.spaceharvester.application.sources.PageAttachment file = entry.attachment();
        String mediaType = file.mediaType() != null && !file.mediaType().isBlank() ? file.mediaType()
                : entry.stored() == null ? null : entry.stored().mediaType();
        // Eine Marke zeigt ihr Standbild, ein Transkript seinen Text.
        String shownType = switch (entry.kind()) {
            case MARK -> "image/png";
            case TRANSCRIPT -> "text/plain";
            default -> mediaType;
        };
        ReaderAttachment attachment = new ReaderAttachment(file.attachmentId(), file.title(), shownType,
                file.fileSize() == null ? -1 : file.fileSize());
        String source = sourceId;
        boolean previews = runtime.workspace().attachmentPreviews(source);
        // Der gezeigte zuerst, dann die übrigen derselben Art nach Abstand: Blättern soll nicht warten.
        AttachmentPrefetch loader = prefetch(runtime);
        loader.focus(attachmentsOfKind(entry.kind()), file.attachmentId());
        AttachmentViewerPanel viewer = new AttachmentViewerPanel(attachment,
                attachmentLoader(runtime, source, pageId, previews, loader), fileOpener, attachmentRenderer);
        viewer.neighbours(neighbours(file.attachmentId(), entry.kind()));
        if (entry.kind() == com.aresstack.spaceharvester.application.assets.AssetKind.MARK) {
            // Unter dem Standbild die Folienkommentare, die an der Marke liegen, je Modell eine Fassung.
            JPanel marked = new JPanel(new BorderLayout(0, 8));
            marked.setOpaque(false);
            marked.add(viewer, BorderLayout.CENTER);
            SlideNotesPanel notes = new SlideNotesPanel();
            marked.add(notes, BorderLayout.SOUTH);
            long millis = com.aresstack.spaceharvester.application.assets.Assets.markMillis(file.attachmentId());
            String id = pageId;
            Background.run(() -> runtime.workspace().videoSlideNotes(source, id, millis), found -> notes.show(
                    found.stream().map(note -> new SlideNotesPanel.Version(note.label(), note.text())).toList()),
                    error -> { });
            showAttachmentOverlay(marked, attachment.fileName());
        } else {
            showAttachmentOverlay(viewer, attachment.fileName());
        }
        assets.getSelectionModel().setSelectionInterval(row, row);
    }

    /** Die Nachbarn eines Anhangs derselben Art in der Liste, für die Pfeile des Viewers. */
    private AttachmentViewerPanel.Neighbours neighbours(String attachmentId,
                                                        com.aresstack.spaceharvester.application.assets.AssetKind kind) {
        return new AttachmentViewerPanel.Neighbours() {
            @Override
            public boolean hasPrevious() {
                return neighbour(-1) >= 0;
            }

            @Override
            public boolean hasNext() {
                return neighbour(1) >= 0;
            }

            @Override
            public void previous() {
                openAttachment(neighbour(-1));
            }

            @Override
            public void next() {
                openAttachment(neighbour(1));
            }

            /** Die Zeile des nächsten Anhangs derselben Art in Richtung {@code step}, sonst -1. */
            private int neighbour(int step) {
                List<PageAssets.Entry> rows = assetsModel.rows;
                int at = -1;
                for (int i = 0; i < rows.size(); i++) {
                    if (rows.get(i).attachment().attachmentId().equals(attachmentId)) {
                        at = i;
                    }
                }
                for (int i = at + step; at >= 0 && i >= 0 && i < rows.size(); i += step) {
                    if (rows.get(i).kind() == kind) {
                        return i;
                    }
                }
                return -1;
            }
        };
    }

    /** Grund im Anhang-Viewer, solange die Quelle einen Einmalcode verlangt; danach lädt die Seite neu. */
    static final String SIGN_IN_PENDING = "Für diesen Anhang ist eine zusätzliche Anmeldung (Einmalcode) "
            + "erforderlich.";

    /**
     * Holt die Originaldatei über den Vorlader (aus dem Speicher oder mit Vorrang geladen und abgelegt), die Vorschau
     * über den Workspace, abseits des EDT; Fehler als lesbarer Grund.
     */
    private static ReaderAttachmentLoader attachmentLoader(AppRuntime runtime, String source, String id,
                                                           boolean previews, AttachmentPrefetch prefetch) {
        return new ReaderAttachmentLoader() {
            @Override
            public void load(ReaderAttachment attachment, Consumer<byte[]> done, Consumer<String> failed) {
                prefetch.load(attachment.key(), done, failed);
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

    JCheckBox showImages() {
        return showImages;
    }

    ComposerButton previewAsset() {
        return previewAsset;
    }

    private void saveRaw() {
        if (page == null) {
            return;
        }
        NativePayload payload = page.snapshot().payload();
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(safeName(page.header().metadata().title())
                + (Payloads.isJson(payload) ? ".json" : ".bin"));
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
        Cursors.set(title, page != null || !details().isEmpty()
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

    private void analyzeBusiness() {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) return;
        var snapshot = page.snapshot();
        var key = snapshot.key();
        var ai = runtime.settings().ai();
        if (ai.chatModel() == null || ai.chatModel().isBlank()) {
            feedback.warning("Kein Chat-Modell eingestellt", null);
            return;
        }
        if (!ai.externalProcessingSources().contains(key.sourceId())) {
            feedback.warning("FTP-Wissensquelle nicht für KI freigegeben", "Freigabe unter Einstellungen prüfen.");
            return;
        }
        com.aresstack.spaceharvester.app.runtime.ModelBudget budget;
        try {
            budget = com.aresstack.spaceharvester.app.runtime.ModelBudget.of(
                    ai.contextTokens(), ai.chatMaxTokens());
        } catch (IllegalStateException missing) {
            feedback.warning("Kontextbudget nicht bestimmbar", missing.getMessage());
            return;
        }
        int current = request;
        recomputeBusiness.setEnabled(false);
        feedback.working("Fachliche KI-Analyse wird berechnet …");
        Background.run(() -> {
            runtime.workspace().recomputeCobolBusiness(key, snapshot.payload().rawSha256(), ai.chatModel(), budget,
                    () -> context.runtime() == runtime && ai.equals(runtime.settings().ai()),
                    state -> javax.swing.SwingUtilities.invokeLater(() -> {
                        if (current == request && context.runtime() == runtime) {
                            feedback.working(state == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED
                                    ? "Wartet auf freien Platz …" : "Fachliche KI-Analyse wird berechnet …");
                        }
                    }));
            var cached = runtime.workspace().cachedPage(key.sourceId(), key.pageId())
                    .orElseThrow(() -> new IllegalStateException("Programm nicht mehr verfügbar."));
            return runtime.workspace().enrichments(cached);
        }, updated -> {
            if (current != request || context.runtime() != runtime) return;
            recomputeBusiness.setEnabled(true);
            views = updated;
            showProgramAnalysis(snapshot);
            analysisTabs.select("Fachliche Analyse");
            feedback.success("Fachliche KI-Analyse gespeichert");
        }, error -> {
            if (current != request || context.runtime() != runtime) return;
            recomputeBusiness.setEnabled(true);
            feedback.warning("Fachliche KI-Analyse nicht abgeschlossen", error.getMessage());
        });
    }

    void recompute() {
        recompute(null);
    }

    private void recompute(String processor) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || page == null) {
            return;
        }
        String source = sourceId;
        String id = pageId;
        feedback.working("Analysen werden berechnet …");
        recomputation(runtime, ++request, false,
                state -> runtime.workspace().recomputeEnrichments(source, id, processor, state));
    }

    /**
     * Der gemeinsame Ablauf von Klick und selbsttätiger Neuberechnung: {@code start} startet den Auftrag und kehrt
     * mit seinem Lauf zurück ({@code null}: kein Auftrag, dann bleibt alles, wie es ist); danach zeigt die Seite die
     * neuen Analysen. Selbsttätig zählt die Anfrage nicht weiter, damit sie das Laden der Seite nicht verwirft.
     */
    private void recomputation(AppRuntime runtime, int current, boolean automatic,
                               java.util.function.Function<Consumer<com.aresstack.spaceharvester.application.jobs
                                       .JobState>, Enrichments.Run> start) {
        String source = sourceId;
        String id = pageId;
        PageText text = shown == null ? null : shown.text();
        boolean live = page.live();
        Font font = reader.getFont();
        DeliveredPage before = page;
        String working = automatic ? "Veraltete Analysen werden neu berechnet …" : "Analysen werden berechnet …";
        Background.run(() -> {
            // Abnahme #1078: Wartet der Auftrag hinter laufenden Folgeaufträgen, sagt die Statuszeile es.
            Enrichments.Run run = start.apply(state ->
                    javax.swing.SwingUtilities.invokeLater(() -> {
                        if (current == request && page == before) {
                            feedback.working(state == com.aresstack.spaceharvester.application.jobs.JobState.QUEUED
                                    ? "Wartet auf freien Platz …" : working);
                        }
                    }));
            if (run == null) {
                return null;
            }
            // Selbsttätig nur aus dem Bestand: keine Prüfung bei der Quelle.
            DeliveredPage cached = automatic ? runtime.workspace().cachedPage(source, id).orElse(null)
                    : runtime.workspace().page(source, id, false);
            if (cached == null) {
                return null;
            }
            List<Enrichments.View> updated = runtime.workspace().enrichments(cached);
            ReaderDocument document = localDocument(runtime.workspace(), cached, font)
                    .orElseGet(() -> document(source, id, updated, text, font));
            relations(runtime, document, source, cached.header().metadata().partitionKey());
            navigation(runtime, document, source, id);
            return new Recomputed(run, updated, document);
        }, result -> {
            if (result == null || current != request || automatic && page != before) {
                return;
            }
            Enrichments.Run run = result.run();
            views = result.views();
            analysisView.steps(views, this::stepResult);
            if (page != null) showProgramAnalysis(page.snapshot());
            // Auswahl samt Anfang und Ende bleibt: die selbsttätige Neuberechnung kommt mitten im Lesen (Codex #1619).
            int dot = reader.getCaret().getDot();
            int mark = reader.getCaret().getMark();
            showReader(result.reader(), live);
            int length = reader.getDocument().getLength();
            reader.setCaretPosition(Math.min(mark, length));
            reader.moveCaretPosition(Math.min(dot, length));
            if (!run.supported()) {
                feedback.info("Keine Analysen für dieses Format", null);
            } else if (run.failed() > 0) {
                feedback.warning("Analysen teilweise fehlgeschlagen", run.computed() + " berechnet, " + run.failed()
                        + " fehlgeschlagen");
            } else {
                feedback.success(run.computed() + (automatic ? " veraltete Analysen neu berechnet"
                        : " Analysen berechnet"));
            }
        }, error -> {
            if (current != request || automatic && page != before) {
                return;
            }
            if (error instanceof com.aresstack.spaceharvester.application.jobs.JobCancelledException) {
                // Fundstelle 21: läuft als Auftrag, den der Benutzer in der Auftragsliste abbrechen kann.
                feedback.info("Neuberechnung abgebrochen", null);
            } else {
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
        more.setEnabled(enabled || sourceMenuShown());
        harvest.setEnabled(enabled);
        saveAsset.setEnabled(enabled);
        updatePreviewEnabled();
        recompute.setEnabled(enabled);
        recomputeProgram.setEnabled(enabled);
        recomputeBusiness.setEnabled(enabled);
        searchPrograms.setEnabled(enabled);
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
        return Comic.row(advancedActions, sourceView.tools(), sourceChanges.tools(),
                areaView.tools(), areaPodcast.tools(),
                sourceProperties.tools(), reload, star, more);
    }

    /**
     * „Seite neu laden“ im Kopf, an der früheren Stelle des „?“ (Angelo 27.11.2026): in jeder Ansicht derselbe Knopf.
     * Eine Seite wird bei der Quelle geprüft und, wenn sie dort neuer ist, neu geholt; Quelle und Bereich laden ihre
     * gezeigte Ansicht neu.
     */
    private final com.aresstack.spaceharvester.app.ui.IconButton reload =
            new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.SYNC,
                    ReadingPane.RELOAD);

    {
        reload.addActionListener(event -> reloadClicked());
    }

    com.aresstack.spaceharvester.app.ui.IconButton reloadButton() {
        return reload;
    }

    private void reloadClicked() {
        if (PAGE.equals(pageCard) || !SOURCE_CARD.equals(pageCard)) {
            if (sourceId != null && pageId != null) {
                load(sourceId, pageId, true, null);
            }
            return;
        }
        if (sourceView.tools().isVisible()) {
            sourceView.reload();
        } else if (sourceChanges.tools().isVisible()) {
            sourceChanges.reload();
        } else if (areaView.tools().isVisible()) {
            areaView.reload();
        } else if (areaPodcast.tools().isVisible()) {
            areaPodcast.reload();
        } else if (sourceProperties.tools().isVisible()) {
            sourceProperties.reload();
        }
    }

    /**
     * Die „?“-Hilfe der gezeigten Ansicht für die Ecke unten rechts in der Werkzeugleiste: Nur die passende ist
     * sichtbar; hat die Ansicht keine, bleibt die Ecke leer.
     */
    JComponent help() {
        if (help == null) {
            help = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 0, 0));
            help.setOpaque(false);
            for (JComponent part : new JComponent[] {sourceView.help(), sourceChanges.help(), areaView.help(),
                    areaPodcast.help(), sourceProperties.help(), readingHelp, podcastHelp, attachmentsHelp,
                    infoHelp, exportHelp}) {
                help.add(part);
            }
        }
        return help;
    }

    private JPanel help;

    static final String PODCAST_HELP = "Die Podcast-Ansicht zeigt den Text der Seite zum Lesen und zum Hören. Der "
            + "Podcast erzählt die Seite; die gerade erzählte Stelle ist gelb markiert, Stellen ohne Ton sind grau.\n\n"
            + "Unten neben der Lupe kopierst du das Transkript. Der Podcast entsteht erst, wenn du ihn startest; "
            + "dafür geht der Text der Seite an das eingestellte Modell.";
    static final String ATTACHMENTS_HELP = "Hier stehen die Anhänge der Seite. Wähle einen Anhang, um ihn in der "
            + "Vorschau zu sehen oder als Datei zu speichern.\n\n„Alle Anhänge ablegen“ lädt die Anhänge dieser Seite "
            + "in die lokale Anhangsablage; danach stehen sie auch ohne Verbindung zur Quelle bereit.";
    static final String INFO_HELP = "„Info“ zeigt, was der Bestand über die Seite weiß: unter „Eigenschaften“ Titel, "
            + "Version und Adresse, daneben die technischen Angaben und die Rohdaten, wie die Quelle sie geliefert "
            + "hat.\n\nDie Unterreiter stehen mittig in der Leiste unten; ihre Aktionen stehen oben links vom Stern.";
    static final String EXPORT_HELP = "Der Export gibt die Auswahl links aus, auch ohne geöffnete Seite. Die Formate "
            + "stehen unten in der Leiste; ein Klick öffnet einen eigenen Export-Reiter für diese Auswahl.\n\n"
            + "Exportierte Dateien liegen im Ausgabeordner und heißen nach dem Titel.";
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton podcastHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, READ, PODCAST_HELP);
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton attachmentsHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, ATTACHMENTS, ATTACHMENTS_HELP);
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton infoHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, ADVANCED, INFO_HELP);
    private final com.aresstack.spaceharvester.app.ui.controls.HelpButton exportHelp =
            new com.aresstack.spaceharvester.app.ui.controls.HelpButton(24, EXPORT, EXPORT_HELP);

    /** Return the existing document-history control for the fixed viewer header. */
    JComponent navigation() {
        return backButton;
    }

    javax.swing.AbstractButton moreButton() {
        return more;
    }

    static final String SAVE_SUMMARY = "Zusammenfassung speichern …";
    static final String SAVE_TRANSCRIPT = "Transkript speichern …";
    /** Ohne Rückfrage: der Podcast lässt sich jederzeit neu erzeugen (Angelo, UX-Manager 22.11.2026). */
    static final String RENEW_PODCAST = "Podcast neu erstellen";
    static final String RENEW_PODCAST_TIP = "Verwirft Texte, Audio und Bilder des Podcasts und erzeugt ihn neu";

    static final String REBUILD_COUNTS = "Kennzahlen neu aufbauen";
    static final String REBUILD_COUNTS_TIP = "Liest die Begriffe dieses Knotens und aller Unterknoten neu. Das kann bei "
            + "großen Beständen länger dauern und ist nur nötig, wenn Zahlen falsch wirken.";

    /** Auf der Quellenseite in der Leseansicht hat das Dreieck die Speicher-Einträge der Zusammenfassung. */
    private boolean sourceMenuShown() {
        return SOURCE_CARD.equals(pageCard) && (READING.equals(tabs.selected()) || READ.equals(tabs.selected()))
                && (sourceView.sourceId() != null || areaView.folder() != null);
    }

    /**
     * Zur Seite „Lesezeichen kopieren“ und „Im Browser öffnen“, mit Podcast auch „Transkript speichern …“; auf der
     * Quellenseite „Zusammenfassung speichern …“ und „Transkript speichern …“ (Angelo 22.11.2026). Wo das Transkript
     * steht, stehen auch „Als WAV exportieren …“ und „Als Video exportieren …“ (Angelo 27.11.2026). Je Eintrag so
     * wählbar wie die Aktion gerade gilt.
     */
    javax.swing.JPopupMenu moreMenu() {
        javax.swing.JPopupMenu menu = pageMenu();
        // Das Buch eines Ordners an erster Stelle (ADR 0044 Nachtrag N5).
        String bookFolder = folderShown() && areaView.folder() != null ? areaView.folder().folderId() : null;
        if (bookFolder != null && context.navigator() != null && context.navigator().opensArtifacts()
                && com.aresstack.spaceharvester.app.research.BookEditions.exists(
                context.settings() == null || context.settings().storage() == null ? null
                        : context.settings().storage().dataDirectory(), bookFolder)) {
            menu.insert(menuItem(com.aresstack.spaceharvester.app.ui.Texts.OPEN_BOOK,
                    com.aresstack.spaceharvester.app.ui.Texts.OPEN_BOOK_TIP, true, () -> {
                        if (context.navigator() != null) {
                            context.navigator().openBook(bookFolder);
                        }
                    }), 0);
            if (menu.getComponentCount() > 1) {
                menu.insert(new javax.swing.JPopupMenu.Separator(), 1);
            }
        }
        addArtifacts(menu);
        return menu;
    }

    /**
     * Unter einer Linie „Neuer Export“ (ADR 0045 §5), darunter „Aus dieser Seite erzeugt“ (ADR 0047 §5a). Die globalen
     * Artefakte stehen nicht mehr hier, sondern links unter
     * „Artefakte“ (ADR 0047 §5).
     */
    private void addArtifacts(javax.swing.JPopupMenu menu) {
        if (artifactTabs == null) {
            return;
        }
        if (menu.getComponentCount() > 0) {
            menu.addSeparator();
        }
        // Ein Export-Reiter für die Auswahl links, ohne einen Auftrag zu starten (ADR 0045 §5).
        ExportPanel.Selection current = export.selection();
        menu.add(menuItem(NEW_EXPORT, NEW_EXPORT_TIP, current != null,
                () -> artifactTabs.openExport(current, null)));
        localArtifacts.addTo(menu);
    }

    /** Die Auswahl links für „Aus dieser Seite erzeugt“. */
    void localArtifacts(ExportPanel.Selection selection) {
        localArtifacts.selection(selection);
    }

    /** Für Tests: „Aus dieser Seite erzeugt“. */
    LocalArtifacts localArtifacts() {
        return localArtifacts;
    }

    static final String NEW_EXPORT = "Neuer Export";
    static final String NEW_EXPORT_TIP = "Öffnet einen Export-Reiter für die Auswahl links";

    private javax.swing.JPopupMenu pageMenu() {
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        if (sourceMenuShown() && READING.equals(tabs.selected())) {
            menu.add(menuItem(SAVE_SUMMARY, "Die gezeigte Zusammenfassung als Textdatei", areaShown()
                    ? areaView.hasSummary() : sourceView.hasSummary(), this::saveSummary));
            addPodcastSettings(menu);
            return menu;
        }
        if (sourceMenuShown() && areaShown()) {
            menu.add(menuItem(SAVE_TRANSCRIPT, "Der gesprochene Text des Podcasts als Textdatei",
                    areaPodcast.podcast().hasTranscript(), () -> saveTranscript(areaPodcast.podcast())));
            addPodcastExports(menu, areaPodcast.podcast());
            addPodcastSettings(menu);
            return menu;
        }
        if (sourceMenuShown()) {
            // Der Podcast steht in der Podcast-Ansicht, seine Einträge also auch.
            menu.add(menuItem(SAVE_TRANSCRIPT, "Der gesprochene Text des Podcasts als Textdatei",
                    sourceChanges.podcast().hasTranscript(), () -> saveTranscript(sourceChanges.podcast())));
            addPodcastExports(menu, sourceChanges.podcast());
            menu.add(menuItem(RENEW_PODCAST, RENEW_PODCAST_TIP, sourceChanges.podcastRenewable(),
                    sourceChanges::renewPodcast));
            addPodcastSettings(menu);
            return menu;
        }
        for (ComposerButton action : List.of(bookmark, browse)) {
            menu.add(menuItem(action.getText(), action.getToolTipText(), action.isEnabled(), action::doClick));
        }
        if (PAGE.equals(pageCard) && pagePodcast.hasTranscript()) {
            menu.add(menuItem(SAVE_TRANSCRIPT, "Der gesprochene Text des Podcasts als Textdatei", true,
                    () -> saveTranscript(pagePodcast)));
            addPodcastExports(menu, pagePodcast);
        }
        return menu;
    }

    static final String EXPORT_WAV = "Als WAV exportieren …";
    static final String EXPORT_VIDEO = "Als Video exportieren …";
    static final String EXPORT_WAITING = "Erst möglich, wenn alle Abschnitte des Podcasts vertont sind";

    /**
     * „Als WAV exportieren …“, „Als Video exportieren …“ (die Bilder als Diashow, zeitlich passend zum Ton) und darunter
     * die „Video-Einstellungen“ (Angelo 27.11.2026). Wählbar, sobald jeder Abschnitt sein Audio hat.
     */
    private void addPodcastExports(javax.swing.JPopupMenu menu, PodcastControl<?> podcast) {
        boolean ready = podcast.exportable() != null;
        menu.add(menuItem(EXPORT_WAV, ready ? "Der Ton des Podcasts als eine WAV-Datei" : EXPORT_WAITING, ready,
                () -> exportPodcast(podcast, com.aresstack.spaceharvester.app.podcast.PodcastExport.Format.WAV)));
        menu.add(menuItem(EXPORT_VIDEO, ready ? "Der Podcast mit seinen Bildern als Video (MP4), zeitlich passend "
                + "zum Ton" : EXPORT_WAITING, ready,
                () -> exportPodcast(podcast, com.aresstack.spaceharvester.app.podcast.PodcastExport.Format.VIDEO)));
        menu.add(PodcastVideoSettings.menu(this, text -> tabStatus(tabs.selected(), text)));
    }

    /**
     * Fragt nach der Datei und startet den Export als Auftrag: er läuft auf der lokalen Spur, zeigt seinen Fortschritt
     * in der Auftragsliste und lässt sich dort abbrechen.
     */
    private void exportPodcast(PodcastControl<?> control,
                               com.aresstack.spaceharvester.app.podcast.PodcastExport.Format format) {
        com.aresstack.spaceharvester.application.podcast.Podcast podcast = control.exportable();
        com.aresstack.spaceharvester.app.runtime.AppRuntime runtime = context.runtime();
        if (podcast == null || runtime == null) {
            return;
        }
        String name = title.getText();
        java.nio.file.Path target = saveTarget.apply("Podcast " + safeName(name) + "." + format.extension());
        if (target == null) {
            return;
        }
        com.aresstack.spaceharvester.app.podcast.PodcastVideo.Options options = PodcastVideoSettings.options();
        java.nio.file.Path file = target.toAbsolutePath();
        runtime.workspace().startPodcastExport(name, java.util.Map.of(
                        com.aresstack.spaceharvester.app.podcast.PodcastExport.FILE_PARAMETER, file.toString(),
                        com.aresstack.spaceharvester.app.podcast.PodcastExport.FORMAT_PARAMETER, format.name()),
                com.aresstack.spaceharvester.app.podcast.PodcastExport.task(podcast, file, format, options));
        tabStatus(tabs.selected(), format.label() + " wird exportiert, Fortschritt unter Aufträge: " + file);
    }

    /**
     * Unter einer Linie die Grenzen der Parallelität und darunter die Modelle der Podcast-Schritte (Angelo 24.11.2026):
     * Grenzen merkt die App, die Modelle stehen direkt in den Einstellungen.
     */
    private void addPodcastSettings(javax.swing.JPopupMenu menu) {
        java.util.function.Consumer<String> status = text -> tabStatus(tabs.selected(), text);
        menu.addSeparator();
        for (javax.swing.JMenu parallel : PodcastParallel.menus(this, status)) {
            menu.add(parallel);
        }
        List<javax.swing.JMenu> models = com.aresstack.spaceharvester.app.ui.PodcastModels.menus(this, context,
                podcastModels, status);
        if (!models.isEmpty()) {
            menu.addSeparator();
            models.forEach(menu::add);
        }
    }

    /** Die Modelle des KI-Dienstes für die Modellwahl am Dreieck; einmal je Ansicht, beim Öffnen neu gefragt. */
    private final com.aresstack.spaceharvester.app.ui.ModelChoice.Catalog podcastModels =
            new com.aresstack.spaceharvester.app.ui.ModelChoice.Catalog(this::aiModels);

    private List<com.aresstack.spaceharvester.ai.AiModel> aiModels() {
        com.aresstack.spaceharvester.app.runtime.AppRuntime runtime = context.runtime();
        return runtime == null ? List.of() : runtime.workspace().aiModels();
    }

    private static javax.swing.JMenuItem menuItem(String text, String tooltip, boolean enabled, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
        item.setFont(Comic.regular(13f));
        item.setToolTipText(tooltip);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Fragt nach der Datei; Tests setzen einen Ersatz. */
    java.util.function.Function<String, java.nio.file.Path> saveTarget = this::chooseSaveTarget;

    private java.nio.file.Path chooseSaveTarget(String fileName) {
        JFileChooser chooser = com.aresstack.spaceharvester.app.ui.controls.SaveChooser.create(fileName);
        return chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION ? chooser.getSelectedFile().toPath()
                : null;
    }

    private void saveSummary() {
        if (areaShown()) {
            if (areaView.hasSummary()) {
                saveText("Überblick " + safeName(title.getText()) + ".txt", areaView.copyText(), "Überblick");
            }
        } else if (sourceView.hasSummary()) {
            saveText("Zusammenfassung " + safeName(title.getText()) + ".txt", sourceView.copyText(),
                    "Zusammenfassung");
        }
    }

    private void saveTranscript(PodcastControl<?> podcast) {
        String name = "Transkript " + safeName(title.getText()) + ".md";
        podcast.transcript(text -> {
            if (text.isBlank()) {
                tabStatus(tabs.selected(), "Transkript: Der Podcast hat noch keinen Text");
            } else {
                saveText(name, text, "Transkript");
            }
        });
    }

    private void saveText(String fileName, String text, String what) {
        java.nio.file.Path target = saveTarget.apply(fileName);
        if (target == null) {
            return;
        }
        Background.execute(() -> Files.writeString(target, text, java.nio.charset.StandardCharsets.UTF_8),
                () -> tabStatus(tabs.selected(), what + " gespeichert: " + target),
                failure -> tabStatus(tabs.selected(), what + " nicht gespeichert: "
                        + Problems.describe(failure).title()));
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
     * Wählt einen Reiter über seinen Namen; ein Unterreiter von „Analysen“ (z. B. „Absätze“) oder „Info“ (z. B.
     * „Eigenschaften“) wählt zuerst den Unterreiter und dann den Hauptreiter.
     */
    void selectTab(String name) {
        if (analysisTabs.button(name) != null) {
            analysisTabs.select(name);
            openAnalyses.run();
        } else if (advancedTabs.button(name) != null) {
            advancedTabs.select(name);
            wideAdvanced.select(name);
            tabs.select(ADVANCED);
        } else {
            tabs.select(name);
        }
    }

    /** Der sichtbare Reiter: unter „Analysen“ der gewählte Unterreiter, sonst der Hauptreiter. */
    String selectedTab() {
        return tabs.selected();
    }

    /** Der gezeigte Reiter der Analysen in der Seitenleiste; {@code null}, solange sie zu ist. */
    String analysesTab() {
        if (!analysesOpen) {
            return null;
        }
        if (wide) {
            return switch (java.util.Objects.requireNonNullElse(standing.tabs().selected(), "")) {
                case StandingTab.TERMS -> OVERVIEW_TERMS;
                case StandingTab.GLOSSARY -> OVERVIEW_GLOSSARY;
                default -> STAND;
            };
        }
        return analysisTabs.selected();
    }

    /** Für Tests: die angezeigte Seite oder {@code null} ohne Seite und während des Ladens. */
    /** Der Erklär-Podcast der gezeigten Seite über die Laufzeit; ohne Laufzeit bleibt der Knopf aus. */
    private PodcastControl.Source<PodcastPage> pagePodcasts() {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return PodcastControl.Source.none();
        }
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        return new PodcastControl.Source<>() {
            @Override
            public boolean available() {
                return workspace.pagePodcastsAvailable();
            }

            @Override
            public String key(PodcastPage page) {
                return workspace.pagePodcastKey(page.sourceId(), page.pageId());
            }

            @Override
            public Optional<com.aresstack.spaceharvester.application.podcast.Podcast> podcast(PodcastPage page) {
                return workspace.pagePodcast(page.sourceId(), page.pageId());
            }

            @Override
            public com.aresstack.spaceharvester.application.jobs.JobStatus start(PodcastPage page) {
                return PodcastParallel.apply(workspace).startPagePodcast(page.sourceId(), page.pageId());
            }

            @Override
            public com.aresstack.spaceharvester.application.observe.Subscription subscribe(String key,
                                                                                         Runnable listener) {
                return workspace.subscribePagePodcast(key, listener);
            }

            @Override
            public com.aresstack.spaceharvester.application.observe.Subscription subscribeJobs(
                    com.aresstack.spaceharvester.application.jobs.JobListener listener) {
                return workspace.subscribeJobs(listener);
            }

            @Override
            public Optional<String> transcript(PodcastPage page) {
                return workspace.pagePodcastTranscript(page.sourceId(), page.pageId());
            }

            @Override
            public List<Integer> focus(PodcastPage page, int topic) {
                List<Integer> pairs = new ArrayList<>();
                for (var span : workspace.pagePodcastFocus(page.sourceId(), page.pageId(), topic)) {
                    pairs.add(span.start());
                    pairs.add(span.end());
                }
                return pairs;
            }

            @Override
            public List<Integer> coverage(PodcastPage page, List<Integer> topics) {
                List<Integer> pairs = new ArrayList<>();
                workspace.pagePodcastCoverage(page.sourceId(), page.pageId()).orElse(List.of()).forEach(span -> {
                    pairs.add(span.start());
                    pairs.add(span.end());
                });
                return pairs;
            }
        };
    }

    /** Die Stellen aus {@link PodcastControl.Source#focus} der Seite: je zwei Zahlen Anfang und Ende. */
    static List<int[]> spans(List<Integer> pairs) {
        List<int[]> spans = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            spans.add(new int[]{pairs.get(i), pairs.get(i + 1)});
        }
        return spans;
    }

    /**
     * Liest im Hintergrund die fertigen Bilder des Seiten-Podcasts mit der Stelle ihres Themas und stellt sie neben
     * den Lesetext (Angelo 23.11.2026); ohne Podcast oder ohne Bilder bleibt die Spalte weg.
     */
    private void loadPagePictures() {
        AppRuntime runtime = context.runtime();
        String source = sourceId;
        String shownPage = pageId;
        if (runtime == null || source == null || shownPage == null || shown == null || shown.text() == null) {
            return;
        }
        com.aresstack.spaceharvester.app.runtime.Workspace workspace = runtime.workspace();
        int mine = ++pagePictureLoads;
        Background.run(() -> readPagePictures(workspace, source, shownPage), pictures -> {
            if (mine == pagePictureLoads && source.equals(sourceId) && shownPage.equals(pageId)) {
                pagePictures.show(pictures);
            }
        }, failure -> LOG.log(System.Logger.Level.INFO, "Bilder des Seiten-Podcasts nicht gelesen ({0})",
                failure.getClass().getName()));
    }

    private static List<PagePodcastPictures.Placed> readPagePictures(
            com.aresstack.spaceharvester.app.runtime.Workspace workspace, String source, String page) {
        List<PagePodcastPictures.Placed> placed = new ArrayList<>();
        var podcast = workspace.pagePodcast(source, page);
        if (podcast.isEmpty()) {
            return placed;
        }
        java.util.Set<Integer> topics = new java.util.HashSet<>();
        for (var part : podcast.get().parts()) {
            if (part.picture() == null || !topics.add(part.topic())) {
                continue;
            }
            var spans = workspace.pagePodcastFocus(source, page, part.topic());
            try {
                com.aresstack.spaceharvester.app.ui.controls.PictureStage.Picture read =
                        PodcastControl.read(part.picture());
                java.awt.image.BufferedImage image = read.image() != null ? read.image()
                        : read.vector().raster(2 * PagePodcastPictures.COLUMN);
                // ein Thema über Unterseiten hat keine Stelle im Text: sein Bild steht am Ende
                placed.add(new PagePodcastPictures.Placed(part.topic(),
                        spans.isEmpty() ? Integer.MAX_VALUE : spans.get(0).start(), image, read.caption(),
                        new com.aresstack.spaceharvester.app.ui.controls.ImageViewer.Picture(image, read.caption(),
                                read.vector())));
            } catch (java.io.IOException | RuntimeException e) {
                // ein unlesbares Bild fehlt neben dem Text; die übrigen stehen da
            }
        }
        return placed;
    }

    /** Der Erklär-Podcast der Seite (für Tests). */
    PodcastControl<PodcastPage> pagePodcast() {
        return pagePodcast;
    }

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

    public static String safeName(String title) {
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
        /** Die gezeigten Zeilen: ohne „Bilder anzeigen“ alle außer Bildern und SVG-Grafiken. */
        private List<PageAssets.Entry> rows = new ArrayList<>();
        private PageAssets overview;
        private boolean images;

        void set(PageAssets next) {
            overview = next;
            refilter();
        }

        void showImages(boolean shown) {
            images = shown;
            refilter();
        }

        private void refilter() {
            rows = overview == null ? new ArrayList<>() : new ArrayList<>(overview.entries().stream()
                    .filter(entry -> images || !isImage(entry.kind())).toList());
            fireTableDataChanged();
        }

        private static boolean isImage(com.aresstack.spaceharvester.application.assets.AssetKind kind) {
            return kind == com.aresstack.spaceharvester.application.assets.AssetKind.IMAGE
                    || kind == com.aresstack.spaceharvester.application.assets.AssetKind.VECTOR;
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
                    case MARK -> "Marke";
                    case TRANSCRIPT -> "Transkript";
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
