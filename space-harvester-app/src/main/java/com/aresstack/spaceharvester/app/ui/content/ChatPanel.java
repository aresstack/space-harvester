package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleMessageRow;
import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.MessageQrButton;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownMessage;
import com.aresstack.spaceharvester.app.ui.markdown.CachingMermaidImageRenderer;
import com.aresstack.spaceharvester.app.ui.markdown.DesktopLinkOpener;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownMessageView;
import com.aresstack.spaceharvester.app.ui.markdown.MarkdownTheme;
import com.aresstack.spaceharvester.app.ui.markdown.MermaidImageRenderer;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.PlaceholderTextArea;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.ChatKnowledge;
import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatRoom;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.ChatTurn;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.speech.Dictation;
import com.aresstack.spaceharvester.app.speech.LiveDictationSettings;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.Glyph;
import com.aresstack.spaceharvester.app.ui.IconButton;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import com.aresstack.spaceharvester.app.ui.controls.ActionChip;
import com.aresstack.spaceharvester.app.ui.controls.PopupHover;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.jobs.JobListener;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.observe.Subscription;
import com.aresstack.spaceharvester.application.sources.HarvestSource;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JTextField;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JProgressBar;
import javax.swing.Timer;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Point;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reiter „Chat“ (Angelo, 13. und 16.11.2026) nach {@code ChatShellPanel}, {@code ChatComposerPanel},
 * {@code ChatServiceBinding} und {@code ChatHistoryBinding} des Enterprise AI Client, im Design von Space Harvester:
 * oben links „Chat: ‹Name›“ mit dem Pfeil, der die Chats dieses Bereichs anbietet (Rechtsklick auf den Namen:
 * Umbenennen an Ort und Stelle, Chat löschen mit Rückfrage), rechts „Neuer Chat“; der Verlauf als Sprechblasen,
 * über dem Composer die stille Statuszeile des Wissens (sie folgt der Auswahl links und ist nicht abwählbar), unten
 * die eine abgerundete Composer-Fläche. Senden holt das Wissen aus dem Bestand und streamt die Antwort des
 * Chat-Modells ({@link ChatTurn}); währenddessen heißt der Knopf „Stopp“.
 *
 * <p>Chats sind keine globale Liste: Sie gehören zum Bereich, der links gewählt ist (Quelle, Space oder Seite samt
 * Unterseiten, {@link ChatNode}), und liegen je Bereich im Datenordner der App ({@link ChatStore}). Der Name eines
 * Chats ist seine UUID, bis der Benutzer ihn umbenennt; die Kennung bleibt.
 *
 * <p>Das Wissen ist, was links gewählt ist: Quelle, Space oder Seite samt Unterseiten. Die Seitenzahl kommt aus der
 * Bestandsübersicht des Core ({@code Workspace.holdings}, ADR 0027) und frischt sich über deren Meldungen auf, ohne
 * Zeitgeber. Ist der Umfang nicht (ganz) geladen, steht „Laden“ daneben; der gestartete Auftrag wird über die
 * Auftragsmeldungen des Core verfolgt (ADR 0027): „gestartet“ weicht von selbst „abgeschlossen“ oder
 * „fehlgeschlagen“, und die bleibt, bis sie der Benutzer mit ✕ wegklickt (Angelo, 16.11.2026). Der Chat ist die
 * einzige Ausnahme der KI-Freigabe (ADR 0028): eine nicht freigegebene Quelle wird genannt, nicht gesperrt; Senden
 * trägt die Seiten in die Liste freigegebener Seiten ein.
 */
public final class ChatPanel extends JPanel {

    static final String EFFORT_TIP = "Denkaufwand des Modells";
    private static final int FOOTER_GAP = 8;
    static final String FROM_HERE = "Ab hier: ";
    static final String CHATS_HERE = "In diesem Bereich";
    static final String CHATS_ELSEWHERE = "Übrige Chats";
    static final String NO_CHATS = "Noch keine Chats";
    static final String NO_CHATS_HERE = "Noch keine Chats in diesem Bereich";
    static final String NEW_CHAT = "Neuer Chat";
    static final String PLACEHOLDER = "Nachricht…";
    static final String SEND = "Senden";
    static final String STOP = "Stopp";
    static final String NO_RUNTIME = "Keine Laufzeit: Der Chat braucht eine gestartete Wissensquelle";
    static final String NO_CHAT_MODEL = "Kein Chat-Modell: unter Einstellungen › KI ein Text-Modell wählen";
    static final String RENAME = "Umbenennen";
    static final String DELETE = "Chat löschen";
    static final String DELETE_ALL = "Alle Chats löschen";
    static final String DELETED_TIP = "Gelöscht, bis zum Neustart wiederherstellbar: Klick öffnet ihn wieder";
    static final String DELETE_ALL_QUESTION = "Wirklich alle Chats aller Bereiche löschen?";
    static final String ASSISTANT = "Assistent";
    /** Wie in AskAI: solange Ollama das Modell lädt, steht das in der gelben Blase. */
    static final String MODEL_LOADING = "Modell wird geladen …";
    static final String CANCELLED = "Abgebrochen";
    static final String DOWNLOAD_DONE = "abgeschlossen";
    static final String DOWNLOAD_FAILED = "fehlgeschlagen";
    static final String ATTACH = "Dateien anhängen";
    static final String DICTATE = "Spracheingabe mit Whisper";
    /** Wie im Enterprise AI Client: die Erkennung nach dem Stopp ist orange in der Statuszeile zu sehen. */
    static final String RECOGNIZING_STATUS = "Erkenne Sprache …";
    /** Die beiden Arten der Spracheingabe, umgeschaltet per Klick auf die Wellen (Angelo 26.11.2026). */
    static final String LIVE_MODE = "live mitschreiben";
    static final String COMPLETE_MODE = "erst komplett";
    static final String SEND_AFTER_DICTATION_STATUS = "Erkenne Sprache … danach wird gesendet";
    private static final int MIN_EDITOR_HEIGHT = 64;
    private static final int MAX_NORMAL_EDITOR_HEIGHT = 400;
    private static final String NORMAL_EDITOR_HEIGHT = "composer.normalHeight";
    private static final java.util.prefs.Preferences PREFERENCES =
            java.util.prefs.Preferences.userNodeForPackage(ChatPanel.class);
    /** Schalter „Interne Suche“ (Angelo 23.11.2026): das Modell darf die globale Suche der App nutzen. */
    static final String APP_SEARCH_LABEL = "Interne Suche";
    static final String APP_SEARCH_OFF = "Interne Suche einschalten: Der Assistent darf alle für die KI "
            + "freigegebenen Wissensquellen durchsuchen, wie die Suchleiste";
    static final String APP_SEARCH_ON = "Interne Suche ist an: Der Assistent durchsucht alle für die KI freigegebenen "
            + "Wissensquellen (Klick: aus)";
    static final String WEB_LABEL = "Websuche";
    static final String WEB = "Websuche für diesen Chat einschalten: Der Assistent sucht mit einem eigenen Firefox im "
            + "Web und im Intranet";
    static final String WEB_ON = "Websuche für diesen Chat ist an (Klick: aus)";
    static final String WEB_UNAVAILABLE = "Websuche nicht verfügbar: Firefox nicht gefunden (Pfad mit "
            + "-Denterpriseai.firefox=... angeben)";
    static final String WEB_BUDGET_LABEL = "Browser-Einstellungen";
    /** Kein Rundenbudget mehr (Angelo 26.11.2026): Der Chat sucht, bis er fertig ist; am Pfeil bleibt der Browser. */
    static final String WEB_BUDGET_TIP = "Browser-Einstellungen: Profil, Verzögerung, menschliche Eingabe";
    static final String BOTH_ON_WARNING = "Websuche und interne Suche sind gleichzeitig an. "
            + "Inhalte aus Wissensquellen können in Browseraufrufe einfließen.";
    /** Vorgaben wie {@code BrowserBudget} und {@code BrowserLoad} im Enterprise AI Client. */
    /**
     * Die drei Grenzen der Parallelität im Menü am Dreieck der Chat-Leiste (Angelo 24.11.2026), mit denselben Stufen
     * und „Benutzerdefiniert …“ wie bei den Aufträgen: Tabs der Websuche, Agenten der internen Suche, Dateien und
     * Anhänge.
     */
    static final String PARALLEL_TABS = "Parallele Tabs";
    static final String PARALLEL_AGENTS = "Parallele Agenten (intern)";
    static final String PARALLEL_FILES = "Parallele Dateien";
    static final int WEB_TABS_DEFAULT = 16;
    static final int AGENTS_DEFAULT = 32;
    static final int FILES_DEFAULT = 8;
    /**
     * Stillstand (Angelo 26.11.2026, Verfahren aus askai {@code ResearchLead.endRule}): Nach so vielen Werkzeugrunden
     * ohne neues Ergebnis bittet der Chat das Modell, mit dem Gefundenen zu antworten; eine feste Rundengrenze gibt es
     * nicht. Vorbelegt wie askai ({@code stagnationRounds} 3), im Menü am Dreieck einstellbar.
     */
    static final String QUIET_ROUNDS = "Runden ohne Neues";
    /** Menüeintrag und Schlüssel der Suchauszüge je Frage ({@link ChatKnowledge#SOURCES_KEY}). */
    static final String SEARCH_EXCERPTS = "Suchauszüge je Frage";
    static final String EXCERPTS = ChatKnowledge.SOURCES_KEY.substring("chat.web.".length());
    static final int QUIET_ROUNDS_DEFAULT = 3;
    /** Die Stufen im Menü; darunter „Benutzerdefiniert …“. */
    static final List<Integer> PARALLEL_STEPS = com.aresstack.spaceharvester.app.ui.ParallelChoice.STEPS;
    /** Größte freie Eingabe unter „Benutzerdefiniert …“ (das Feld nimmt vier Ziffern). */
    static final int PARALLEL_MAX = com.aresstack.spaceharvester.app.ui.ParallelChoice.CUSTOM_MAX;
    static final int WEB_DELAY_MAX = 10000;
    static final int WEB_DELAY_SLIDER_MAX = 2000;
    private static final String WEB_KEY = "chat.web.";
    static final String APPROVE_TEXT = "Bestätigen";
    static final String REJECT_TEXT = "Abbrechen";
    static final String APPROVE_ONCE_TEXT = "Einmal erlauben";
    static final String APPROVE_ALWAYS_TEXT = "Immer erlauben";
    static final String REJECT_ALWAYS_TEXT = "Immer verbieten";
    static final String NO_MODEL = "Kein Chat-Modell";
    static final String MODEL_TIP = "Chat-Modell aus Einstellungen › KI";
    /**
     * Ohne Auswahl antwortet der Chat trotzdem (Angelo 24.11.2026): nur mit den zugeschalteten Suchen und Anhängen,
     * sonst aus dem eigenen Wissen des Modells.
     */
    static final String NO_SELECTION = "Keine Wissensquelle gewählt: Der Chat nutzt nur Websuche, interne Suche und "
            + "Anhänge, sonst sein eigenes Wissen";
    static final String NOT_LOADED = "Seiten noch nicht erfasst";
    static final String LOAD = "Laden";
    static final String NOT_ALLOWED = "Quelle nicht für KI freigegeben; Senden gibt die offene Seite und die Seiten, "
            + "die der Assistent liest, dauerhaft frei (Einstellungen › Sicherheit)";
    static final String EMPTY_TRANSCRIPT = "Noch keine Nachrichten";
    // Schnellaktionen in der unteren Leiste (Angelo 17.11.2026): immer auf den gewählten Knoten samt Unterknoten,
    // nie auf Eltern; sie gehen den normalen Sendeweg.
    static final String SUMMARIZE_PAGE = "Seite zusammenfassen";
    static final String SUMMARIZE_SUBTREE = "Seite samt Unterseiten zusammenfassen";
    static final String SUMMARIZE_AREA = "Bereich zusammenfassen";
    static final String WHATS_NEW = "Was ist neu?";
    static final String SUMMARIZE_PAGE_TIP = "Nur die gewählte Seite, ohne Unterseiten";
    static final String SUMMARIZE_SUBTREE_TIP = "Die gewählte Seite und alle Unterseiten";
    static final String SUMMARIZE_AREA_TIP = "Alle Seiten des links gewählten Bereichs";
    static final String WHATS_NEW_TIP = "Seit dem letzten Abgleich geänderte Seiten der Auswahl";
    static final List<String> EFFORTS = List.of("Niedrig", "Mittel", "Hoch");
    private static final int ARC = 18;

    private final AppContext context;
    /** „Chat:“ und der Name des aktuellen Chats; Rechtsklick bietet Umbenennen und Löschen. */
    private final JLabel chatName = new JLabel();
    private final IconButton titleAi = new IconButton(Glyph.SPARKLE, "Chat-Titel mit KI erzeugen").compact();
    private boolean titlePending;
    /** Der Name beim Umbenennen an Ort und Stelle. */
    private final JTextField chatNameEditor = new JTextField();
    private final JPanel nameHolder = new JPanel(new BorderLayout());
    /** Nur der Pfeil, durchsichtig: öffnet die Chats dieses Bereichs. */
    private final ComposerButton chats = ComposerButton.iconButton(ComposerIcons.chevronDown(),
            "Chats dieses Bereichs");
    /**
     * Oben rechts in der Kopfzeile, als Symbole (Angelo, 17.11.2026): Plus und Papierkorb, in voller Größe, damit sie
     * die Kopfzeile füllen und gut zu treffen sind (Angelo 24.11.2026).
     */
    private final IconButton newChat = new IconButton(ComposerIcons.plus(16), NEW_CHAT);
    private final IconButton deleteChat = new IconButton(Glyph.TRASH, DELETE);
    /** Die „wirklich“-Rückfrage (Ja/Nein) vor dem Löschen; Tests antworten selbst. */
    java.util.function.BiPredicate<String, String> confirm = (title, question) -> Dialogs.confirm(this, title,
            question);
    /** „Chat: ‹Name› ▾“ für die Mitte der Kopfzeile; der Name kürzt sich mit „…“, wenn der Platz nicht reicht. */
    private final JPanel header = new JPanel(new BorderLayout(6, 0));
    private final JPanel headerActions = Comic.row(newChat, deleteChat);
    private final ChatStore store;
    /** Der Bereich der Chats: die Auswahl links als Knoten; {@code null} ohne Auswahl. */
    private ChatNode node;
    /**
     * Der Ordner des offenen Chats: der Bereich, in dem er begonnen wurde. Der Chat bleibt beim Navigieren stehen
     * (Angelo, 21.11.2026), {@link #node} wandert mit der Auswahl und gilt je Frage.
     */
    private Path home;
    /** Ob die erste Auswahl nach dem Start schon ihren zuletzt geänderten Chat gezeigt hat. */
    private boolean resumed;
    /** Der aktuelle Chat; immer gesetzt, anfangs leer und ungespeichert. */
    private ChatRecord chat = ChatRecord.fresh(System.currentTimeMillis());
    /** Der laufende Turn oder {@code null}. */
    private ChatTurn turn;
    /** Der Empfänger des laufenden Turns; beim Loslassen wird er abgemeldet, damit er nur noch speichert. */
    private TurnListener listening;
    /** Wer erfährt, dass eine Antwort fertig ist (der Punkt am Chat-Knopf, wenn die Leiste zu ist; ADR 0027). */
    private Runnable answerObserver = () -> { };
    /** Wer erfährt, dass ein Quellen-Link eine Seite geöffnet hat (die große Ansicht gibt die Arbeitsfläche frei). */
    private Runnable sourceObserver = () -> { };
    /** Die Quellenzeile unter der zuletzt angelegten Antwortblase. */
    private SourceLinks answeringSources;
    /** Ob die Auswahl links im Core aufgelöst ist; bis dahin sendet der Chat nicht (sonst gälten alte Seiten). */
    private boolean settled;
    /** Ergebnisse der verfolgten Aufträge, gemeldet erst, wenn alle geendet haben. */
    private final List<JobStatus> jobOutcomes = new ArrayList<>();
    private AssistantMarkdownMessage answering;
    /** Antworten rendert der Chat als Markdown wie der EAC (AssistantMarkdownMessage, MarkdownMessageView). */
    private final DesktopLinkOpener linkOpener = DesktopLinkOpener.systemDefault();
    private MermaidImageRenderer mermaid;
    /** Die Auftragskennungen des zuletzt hier gestarteten Downloads, bis der Auftrag endet. */
    private final Set<String> watchedJobs = new HashSet<>();
    private Subscription jobSubscription;
    private AppRuntime jobsSubscribed;
    private final JPanel transcript = new JPanel();
    /** Vorlesen der Antworten mit dem Knopf des Lesebereichs (Angelo 23.11.2026). */
    private final ChatReadAloud readAloud = new ChatReadAloud(transcript);
    /** Liefert die eine Sprachausgabe der App; {@code null} = keine eingerichtet. */
    private java.util.function.Supplier<com.aresstack.spaceharvester.app.speech.ReadAloudBinding> readAloudBindings =
            () -> null;
    /** Die Vorlese-Knöpfe der großen Ansicht: der des Chats steht auf derselben Höhe wie der gerade sichtbare. */
    private final List<JComponent> orbAnchors = new ArrayList<>();
    private JLayeredPane transcriptLayers;
    private final JLabel emptyNote = Comic.muted(EMPTY_TRANSCRIPT);
    private final JScrollPane transcriptScroll;
    private final com.aresstack.spaceharvester.app.ui.controls.BottomFollow bottom;
    private final BubblePalette bubbles = BubblePalette.windowsPhoneInspired();
    /** Die stille Statuszeile des Wissens über dem Composer. */
    private final JTextArea knowledge = Comic.note(" ");
    private final ComposerButton load = Comic.button(LOAD, "Die gewählten Seiten jetzt laden; der Auftrag läuft "
            + "unter Aufträge");
    private final Feedback feedback = new Feedback();
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<Path> attachments = new ArrayList<>();
    /**
     * Vorgemerkte Zitate (Angelo 27.11.2026, wie bei ChatGPT): markierte Ausschnitte aus Antworten, der Seitenansicht
     * oder einem Viewer, je ein Chip mit Anführungszeichen und ✕; sie gehen mit der nächsten Frage hinaus.
     */
    private final List<String> quotes = new ArrayList<>();
    /** Die Chips der Zitate; ein Chip-Vorschlag („Seite zusammenfassen“) sendet sie nicht mit, sie bleiben stehen. */
    private final java.util.Map<String, JPanel> quoteChips = new java.util.LinkedHashMap<>();
    /** Die nächste Frage kommt aus der Eingabe und trägt die Zitate (dann sind sie danach weg). */
    private boolean sendingQuotes;
    /** Das schwebende „Zitieren“ über einer Markierung in einer Antwort (wie im EAC). */
    private final com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton quoteOverlay =
            new com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton();
    /** Die Markierung, zu der das Overlay gerade steht; {@code null} ohne. */
    private com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection quotedSelection;
    /**
     * Bilder, die das Vision-Modell schon beim Anhängen liest (Angelo 27.11.2026: „sobald ich das Bild anfüge“), damit
     * die Frage nach dem Senden nicht erst darauf wartet. Nur auf dem EDT verändert.
     */
    private final java.util.Map<Path, EarlyImage> earlyImages = new java.util.HashMap<>();
    private final PlaceholderTextArea editor = new PlaceholderTextArea(PLACEHOLDER, 3, 40);
    private final ComposerButton model = new ComposerButton(ComposerIcons.chevronDown(), NO_MODEL, false, MODEL_TIP);
    private final ComposerButton effort = new ComposerButton(ChatIcons.thought(), EFFORTS.get(1), false,
            EFFORT_TIP);
    /** Name des Chat-Modells und gewählter Denkaufwand; die Knöpfe zeigen sie nur, solange der Platz reicht. */
    private String modelName = NO_MODEL;
    private String effortLevel = EFFORTS.get(1);
    /**
     * Wie knapp die Fußzeile der Eingabe ist: 0 alles mit Text, 1 Modell nur als Symbol, 2 auch der Denkaufwand,
     * 3 ohne die Websuche.
     */
    private int footerStage;
    private JPanel footerWest;
    private JPanel footerEast;
    private final ComposerButton web = ComposerButton.iconButton(ChatIcons.globe(), WEB);
    private final ComposerButton appSearch = ComposerButton.iconButton(
            com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons.openBook(), APP_SEARCH_OFF);
    /** Ob die interne Suche an ist; gilt für die nächsten Fragen, bis man sie ausschaltet. */
    private boolean appSearchOn;
    /** Ob die Warnung „beide Suchen an“ gerade steht. */
    private boolean bothWarned;
    /** Der Abstand hinter „Interne Suche“; weicht mit ihr in der schmalsten Stufe der Fußzeile. */
    private final java.awt.Component appSearchGap = Box.createHorizontalStrut(4);
    /** Der kleine Pfeil an der Weltkugel: öffnet Budget, Tabs, Verzögerung, Profil (wie im EAC). */
    private final ComposerButton webBudget = new ComposerButton(ComposerIcons.chevronDown(), null, false, "");
    private boolean webOn;
    private WebSearchPopup lastWebPopup;
    private final ComposerButton attach = ComposerButton.iconButton(ComposerIcons.paperclip(), ATTACH);
    private final ComposerButton mic = ComposerButton.iconButton(ComposerIcons.microphone(), DICTATE);
    private final ComposerButton discardButton = ComposerButton.iconButton(
            ComposerIcons.close(), "Aufnahme verwerfen oder Erkennung abbrechen");
    private final ComposerButton retryButton = new ComposerButton(
            ComposerIcons.refresh(), "Erneut", false, "Letzte Aufnahme erneut erkennen");
    private final JProgressBar levelBar = new JProgressBar(0, 100);
    private final ComposerWaveformOverlay waveform = new ComposerWaveformOverlay();
    private final Timer levelTimer = new Timer(100, event -> sampleDictationLevel());
    private final Dictation dictation = new Dictation();
    /** Der Live-Text der laufenden Aufnahme im Eingabefeld („live mitschreiben“); {@code null} ohne. */
    private LiveDictationRegion dictationLive;
    private DictationPhase dictationPhase = DictationPhase.IDLE;
    /**
     * Senden wurde während der Spracheingabe gedrückt (Angelo 26.11.2026, wie ChatGPT): Aufnahme stoppen, fertig
     * erkennen und danach sofort senden. Verwerfen und Fehlschlag heben den Wunsch auf.
     */
    private boolean sendAfterDictation;
    private long recordingStartedMillis;

    private enum DictationPhase { IDLE, RECORDING, RECOGNIZING }
    private final ComposerButton send = ComposerButton.primary(ComposerIcons.send(), SEND, Comic.BLUE,
            "Senden (Enter)");
    /** Die Statuszeile des Composers („Chat folgt“); leer unsichtbar. */
    private final JLabel status = Comic.muted(" ");
    private final Color statusColor = status.getForeground();
    /** Die Editor-Fläche; Größenänderungen werden ab hier neu ausgemessen (der Rollbereich ist eine Layout-Wurzel). */
    private JComponent editorArea;
    private int normalEditorHeight = Math.max(MIN_EDITOR_HEIGHT,
            Math.min(MAX_NORMAL_EDITOR_HEIGHT, PREFERENCES.getInt(NORMAL_EDITOR_HEIGHT, MIN_EDITOR_HEIGHT)));
    private final ComposerResizeGrip resizeGrip = new ComposerResizeGrip(() -> normalEditorHeight,
            this::setNormalEditorHeight, () -> PREFERENCES.putInt(NORMAL_EDITOR_HEIGHT, normalEditorHeight));
    private final JPanel composer;
    private boolean editorFocused;

    private Selection selection;
    /**
     * Die offene Ansicht des Chats für den Vorrang ({@code Foreground}, Angelo 24.11.2026): je gewählter Seite bzw.
     * Bereich eine neue Kennung; beim Wechsel wird die alte verlassen, ihre Arbeit verliert den Vorrang.
     */
    private String view;
    private static final java.util.concurrent.atomic.AtomicLong VIEWS = new java.util.concurrent.atomic.AtomicLong();
    /** Die Schnellaktionen für die untere Leiste; sichtbar nur im Reiter Chat. */
    private final JPanel quickActions = new JPanel(
            new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 6, 4));
    /** Anmeldung bei den Bestandsmeldungen des Core für Quelle und Space der Auswahl. */
    /** Anmeldungen bei den Bestandsmeldungen: eine je Quelle und Space der Auswahl, global eine je Quelle. */
    private final List<Subscription> subscriptions = new ArrayList<>();
    private List<com.aresstack.spaceharvester.application.holdings.Selection.Root> subscribedAreas;
    private AppRuntime subscribed;
    private boolean refreshPending;
    private int request;
    private PageSelection resolved;
    private Consumer<PageSelection> onResolved;

    public ChatPanel(AppContext context) {
        super(new BorderLayout(0, 8));
        this.context = context;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 12, 12));

        store = new ChatStore(() -> context.settings() == null ? null
                : context.settings().storage().dataDirectory().resolve(ChatStore.FOLDER));
        JLabel heading = new JLabel("Chat:");
        heading.setFont(Comic.semiBold(16f));
        heading.setForeground(Comic.INK);
        chatName.setFont(Comic.regular(15f));
        chatName.setForeground(Comic.INK);
        chatName.setToolTipText("Rechtsklick: Umbenennen, Chat löschen");
        chatName.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showNameMenu(e);
                }
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) {
                    showNameMenu(e);
                }
            }
        });
        chatNameEditor.setFont(Comic.regular(15f));
        chatNameEditor.setBorder(Comic.fieldBorder());
        chatNameEditor.setColumns(28);
        chatNameEditor.addActionListener(e -> finishRename(true));
        chatNameEditor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "cancel-rename");
        chatNameEditor.getActionMap().put("cancel-rename", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                finishRename(false);
            }
        });
        chatNameEditor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                if (chatNameEditor.isShowing()) {
                    finishRename(true);
                }
            }
        });
        nameHolder.setOpaque(false);
        nameHolder.add(chatName, BorderLayout.CENTER);
        titleAi.setToolTipText("Chat-Titel mit KI erzeugen");
        titleAi.setContentAreaFilled(false);
        titleAi.setBorderPainted(false);
        titleAi.addActionListener(e -> generateChatTitle());
        nameHolder.add(titleAi, BorderLayout.EAST);
        chats.setContentAreaFilled(false);
        chats.setBorderPainted(false);
        chats.setOpaque(false);
        chats.addActionListener(e -> showChatsMenu());
        newChat.addActionListener(e -> newChat());
        newChat.getAccessibleContext().setAccessibleName(NEW_CHAT);
        deleteChat.addActionListener(e -> deleteChat());
        deleteChat.getAccessibleContext().setAccessibleName(DELETE);
        // Die Zeile gehört in die Kopfzeile des Inhaltsbereichs (Angelo, 17.11.2026): mittig statt der
        // Seitenüberschrift; hier oben steht nichts mehr.
        header.setOpaque(false);
        header.add(heading, BorderLayout.WEST);
        header.add(nameHolder, BorderLayout.CENTER);
        header.add(chats, BorderLayout.EAST);
        showChatName();

        transcript.setLayout(new BoxLayout(transcript, BoxLayout.Y_AXIS));
        transcript.setOpaque(false);
        emptyNote.setAlignmentX(LEFT_ALIGNMENT);
        transcript.add(emptyNote);
        // Der Verlauf folgt der Breite des Sichtfensters (Seitenleiste, Angelo 21.11.2026): die Zeilen bemessen ihre
        // Blasen nach der Zeilenbreite, also brechen sie um, statt den Verlauf zu verbreitern.
        JPanel transcriptHolder = new com.aresstack.spaceharvester.app.ui.controls.ScrollColumn(new BorderLayout());
        transcriptHolder.add(transcript, BorderLayout.NORTH);
        transcriptScroll = Comic.scroll(transcriptHolder);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        transcriptScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        // Angelo 26.11.2026: Während der Antwort nur mitrollen, solange er unten steht; hochrollen hält es an.
        bottom = com.aresstack.spaceharvester.app.ui.controls.BottomFollow.install(
                transcriptScroll.getVerticalScrollBar());
        // Reserve a separate right-hand lane for playback while keeping its alignment with the reader.
        transcriptLayers = new JLayeredPane() {
            @Override
            public void doLayout() {
                layoutTranscript();
            }

            @Override
            public Dimension getPreferredSize() {
                return transcriptScroll.getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return transcriptScroll.getMinimumSize();
            }
        };
        transcriptLayers.add(transcriptScroll, JLayeredPane.DEFAULT_LAYER);
        transcriptLayers.add(readAloud.orb(), JLayeredPane.MODAL_LAYER);
        // Der Knopf folgt dem sichtbaren Vorlese-Knopf der großen Ansicht (Podcast, Leseansicht, Zusammenfassung):
        // erscheint, verschwindet oder wandert einer, liegt der Knopf des Chats neu (Ereignis, kein Abfragen).
        java.awt.event.AWTEventListener bigOrbs = event -> {
            if (event.getSource() instanceof com.aresstack.spaceharvester.app.ui.controls.PodcastOrb
                    && event.getSource() != readAloud.orb()) {
                SwingUtilities.invokeLater(this::relayoutOrb);
            }
        };
        transcriptLayers.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            toolkit.removeAWTEventListener(bigOrbs);
            if (transcriptLayers.isShowing()) {
                toolkit.addAWTEventListener(bigOrbs, java.awt.AWTEvent.COMPONENT_EVENT_MASK
                        | java.awt.AWTEvent.HIERARCHY_EVENT_MASK);
            }
        });
        transcriptScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                transcriptLayers.doLayout(); // Bildlaufleiste kommt oder geht: der Knopf bleibt links daneben
            }
        });
        transcriptLayers.add(quoteOverlay, JLayeredPane.POPUP_LAYER);
        quoteOverlay.setVisible(false);
        quoteOverlay.addActionListener(e -> acceptQuote());
        // Rollt der Verlauf, stünde das Overlay neben der Markierung: es verschwindet (wie im EAC).
        transcriptScroll.getViewport().addChangeListener(e -> hideQuoteOverlay());
        add(transcriptLayers, BorderLayout.CENTER);
        com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.setQuoteTarget(quoteTarget);

        composer = buildComposer();
        // Angelo 24.11.2026: Über dem Eingabefeld stehen nur Aktionsknöpfe, Text und Anhänge. Die Wissenszeile samt
        // „Laden“ steht in der gelben Blase der Antwort (KnowledgeCard).
        knowledge.setForeground(Comic.MUTED);
        load.setVisible(false);
        load.addActionListener(e -> load());
        // Die Schnellaktionen stehen direkt über der Wissen-Zeile und brechen in der schmalen Leiste um.
        JPanel above = new JPanel(new BorderLayout(0, 4));
        above.setOpaque(false);
        above.add(feedback, BorderLayout.NORTH);
        above.add(quickActions, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(above, BorderLayout.NORTH);
        south.add(composer, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        context.onChange(ignored -> {
            showModel();
            bindReadAloud();
            refresh();
        });
        showModel();
        renderKnowledge(null, null, true);
    }

    /** Die eine abgerundete Composer-Fläche: Chips, Editor, Fußzeile mit Modell und Denkaufwand links, Werkzeugen rechts. */
    private JPanel buildComposer() {
        JPanel surface = new JPanel(new BorderLayout(0, 4)) {
            @Override
            protected void paintComponent(Graphics graphics) {
                Graphics2D g2 = (Graphics2D) graphics.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    int top = status.getHeight();
                    RoundRectangle2D shape = new RoundRectangle2D.Float(1, top + 1, getWidth() - 2,
                            getHeight() - top - 2, ARC, ARC);
                    g2.setColor(Color.WHITE);
                    g2.fill(shape);
                    boolean dictating = dictationPhase != DictationPhase.IDLE;
                    g2.setColor(dictating ? ResearchUiPalette.RECORDING_ORANGE
                            : editorFocused ? Comic.BLUE : Comic.LINE);
                    g2.setStroke(new BasicStroke(dictating || editorFocused ? 1.6f : 1f));
                    g2.draw(shape);
                } finally {
                    g2.dispose();
                }
            }
        };
        surface.setOpaque(false);

        editor.setFont(Comic.regular(13f));
        editor.setForeground(Comic.INK);
        editor.setCaretColor(Comic.INK);
        editor.setOpaque(false);
        editor.setLineWrap(true);
        editor.setWrapStyleWord(true);
        editor.setBorder(BorderFactory.createEmptyBorder(3, 4, 3, 4));
        editor.setToolTipText("Enter: senden · Umschalt+Enter: neue Zeile");
        editor.getAccessibleContext().setAccessibleName("Nachricht");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send");
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, KeyEvent.SHIFT_DOWN_MASK),
                "insert-break");
        editor.getActionMap().put("send", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                send();
            }
        });
        editor.addFocusListener(new FocusAdapter() {
            @Override
            public void focusGained(FocusEvent e) {
                editorFocused = true;
                surface.repaint();
            }

            @Override
            public void focusLost(FocusEvent e) {
                editorFocused = false;
                surface.repaint();
            }
        });
        // Leer hat der Editor die gezogene Normalhöhe; mit langem Text wächst er bis zu zwei Fünfteln der Chat-Höhe
        // und schrumpft nach dem Senden wieder auf die Normalhöhe.
        JScrollPane editorScroll = new JScrollPane(editor, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER) {
            @Override
            public Dimension getPreferredSize() {
                int content = editor.getPreferredSize().height;
                int maximum = Math.max(normalEditorHeight, ChatPanel.this.getHeight() * 2 / 5);
                return new Dimension(520, Math.max(normalEditorHeight, Math.min(content, maximum)));
            }
        };
        editorScroll.setOpaque(false);
        editorScroll.getViewport().setOpaque(false);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        com.aresstack.enterpriseai.ui.comic.control.ComicScrollBarUI.install(editorScroll.getVerticalScrollBar());
        editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                resizeEditor();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                resizeEditor();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                resizeEditor();
            }
        });

        chips.setOpaque(false);
        chips.setVisible(false);
        quickActions.setOpaque(false);
        quickActions.setVisible(false);
        updateQuickActions();

        model.addActionListener(e -> showModelMenu());
        effort.addActionListener(e -> showEffortMenu());
        appSearch.getAccessibleContext().setAccessibleName(APP_SEARCH_LABEL);
        appSearch.addActionListener(e -> appSearch(!appSearchOn));
        web.getAccessibleContext().setAccessibleName(WEB_LABEL);
        web.addActionListener(e -> webSearch(!webOn));
        webBudget.setPreferredSize(new Dimension(14, 28));
        webBudget.setMaximumSize(new Dimension(14, 28));
        webBudget.getAccessibleContext().setAccessibleName(WEB_BUDGET_LABEL);
        webBudget.addActionListener(e -> openWebPopup());
        showWebState();
        mic.setAccent(ResearchUiPalette.RECORDING_ORANGE);
        mic.addActionListener(e -> toggleDictation());
        discardButton.addActionListener(e -> discardDictation());
        retryButton.addActionListener(e -> retryDictation());
        discardButton.setVisible(false);
        retryButton.setVisible(false);
        levelBar.setOpaque(false);
        levelBar.setBorderPainted(false);
        levelBar.setPreferredSize(new Dimension(64, 6));
        levelBar.setMaximumSize(new Dimension(64, 6));
        levelBar.setVisible(false);
        attach.addActionListener(e -> chooseAttachments());
        // Dateien aus dem Explorer hineinziehen oder Bilder per Strg+V einfügen: derselbe Weg wie die Büroklammer.
        com.aresstack.spaceharvester.app.ui.controls.FileDrop.install(files -> files.forEach(this::attach), editor,
                this);
        send.addActionListener(e -> send());
        // Enger Abstand: bei 320 px Leistenbreite müssen beide Symbole neben „Senden“ passen.
        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
        west.setOpaque(false);
        west.add(model);
        west.add(effort);
        west.add(discardButton);
        west.add(retryButton);
        JPanel east = new JPanel();
        east.setOpaque(false);
        east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
        east.add(Box.createHorizontalGlue());
        east.add(levelBar);
        east.add(Box.createHorizontalStrut(4));
        east.add(appSearch);
        east.add(appSearchGap);
        east.add(web);
        east.add(webBudget);
        east.add(Box.createHorizontalStrut(4));
        east.add(attach);
        east.add(Box.createHorizontalStrut(4));
        east.add(mic);
        east.add(Box.createHorizontalStrut(4));
        east.add(send);
        JPanel footer = new JPanel(new BorderLayout(FOOTER_GAP, 0));
        footer.setOpaque(false);
        footer.add(west, BorderLayout.WEST);
        footer.add(east, BorderLayout.EAST);
        footerWest = west;
        footerEast = east;
        footer.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent event) {
                fitFooter();
            }
        });

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(0, 11, 8, 8));
        JPanel top = new JPanel(new BorderLayout(0, 0));
        top.setOpaque(false);
        JPanel gripRow = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.CENTER, 0, 0));
        gripRow.setOpaque(false);
        gripRow.add(resizeGrip);
        top.add(gripRow, BorderLayout.NORTH);
        top.add(chips, BorderLayout.CENTER);
        body.add(top, BorderLayout.NORTH);
        final JScrollPane scroll = editorScroll;
        JLayeredPane editorLayers = new JLayeredPane() {
            @Override
            public void doLayout() {
                for (Component child : getComponents()) {
                    child.setBounds(0, 0, getWidth(), getHeight());
                }
            }

            @Override
            public Dimension getPreferredSize() {
                return scroll.getPreferredSize();
            }

            @Override
            public Dimension getMinimumSize() {
                return scroll.getMinimumSize();
            }
        };
        editorLayers.add(editorScroll, JLayeredPane.DEFAULT_LAYER);
        editorArea = editorLayers;
        editorLayers.add(waveform, JLayeredPane.PALETTE_LAYER);
        // Klick auf die Wellen: zwischen „erst komplett“ und „live mitschreiben“ umschalten, auch während der Aufnahme.
        waveform.setLive(LiveDictationSettings.live(), LIVE_MODE, COMPLETE_MODE);
        waveform.onToggle(() -> LiveDictationSettings.live(!LiveDictationSettings.live()));
        LiveDictationSettings.listen(() -> SwingUtilities.invokeLater(() -> {
            boolean live = LiveDictationSettings.live();
            dictation.setLive(live);
            waveform.setLive(live, LIVE_MODE, COMPLETE_MODE);
            if (dictationPhase == DictationPhase.RECORDING) {
                showRecordingStatus();
            }
        }));
        body.add(editorLayers, BorderLayout.CENTER);
        body.add(footer, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(0, 12, 2, 0));
        surface.add(status, BorderLayout.NORTH);
        surface.add(body, BorderLayout.CENTER);
        trackComposerHover(surface, surface);
        return surface;
    }

    /** Den Ziehgriff nur zeigen, solange die Maus in seiner Nähe an der oberen Linie des Composers steht. */
    private void trackComposerHover(JComponent surface, Component component) {
        java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                resizeGrip.setShown(nearGrip(SwingUtilities.convertPoint(e.getComponent(), e.getPoint(), resizeGrip)));
            }

            @Override
            public void mouseEntered(java.awt.event.MouseEvent e) {
                mouseMoved(e);
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                SwingUtilities.invokeLater(() -> {
                    Point mouse = resizeGrip.getMousePosition(true);
                    if (mouse == null) {
                        // Einmal abfragen: Zwischen zwei Abfragen kann die Maus die Fläche verlassen haben.
                        Point onSurface = surface.getMousePosition(true);
                        mouse = onSurface == null ? null : SwingUtilities.convertPoint(surface, onSurface, resizeGrip);
                    }
                    resizeGrip.setShown(mouse != null && nearGrip(mouse));
                });
            }
        };
        component.addMouseListener(hover);
        component.addMouseMotionListener(hover);
        if (component instanceof java.awt.Container container) {
            for (Component child : container.getComponents()) {
                trackComposerHover(surface, child);
            }
        }
    }

    /** Ob ein Punkt (relativ zum Griff) in der Nähe des Griffs an der oberen Linie liegt. */
    private boolean nearGrip(Point point) {
        int margin = 8;
        return point.x >= -margin && point.x <= resizeGrip.getWidth() + margin
                && point.y >= -margin && point.y <= resizeGrip.getHeight() + margin;
    }

    /** Die Normalhöhe des leeren Editors setzen (Ziehgriff); lange Texte wachsen weiterhin darüber hinaus. */
    void setNormalEditorHeight(int height) {
        int maximum = Math.max(MIN_EDITOR_HEIGHT, getHeight() > 0 ? getHeight() * 2 / 5 : MAX_NORMAL_EDITOR_HEIGHT);
        normalEditorHeight = Math.max(MIN_EDITOR_HEIGHT, Math.min(height, Math.min(maximum, MAX_NORMAL_EDITOR_HEIGHT)));
        remeasureEditor();
    }

    int normalEditorHeight() {
        return normalEditorHeight;
    }

    ComposerResizeGrip resizeGrip() {
        return resizeGrip;
    }

    /** Den Composer nach einer Textänderung neu bemessen (nach dem laufenden Ereignis, wenn die Umbrüche feststehen). */
    private void resizeEditor() {
        SwingUtilities.invokeLater(this::remeasureEditor);
    }

    /**
     * Den Editor neu ausmessen: ab der Editor-Fläche, damit Swing die gemerkte bevorzugte Größe aller
     * Container bis zum Fenster verwirft (nur den Composer neu auszumessen behielt die alte Höhe).
     */
    private void remeasureEditor() {
        if (editorArea != null) {
            editorArea.revalidate();
        }
        composer.repaint();
    }

    /** Toggle the EAC dictation lifecycle without submitting the recognized draft. */
    private void toggleDictation() {
        if (dictationPhase == DictationPhase.RECORDING) {
            dictationPhase = DictationPhase.RECOGNIZING;
            stopDictationLevelMeter();
            refreshDictationControls();
            status.setText(RECOGNIZING_STATUS);
            dictation.finish();
            return;
        }
        if (dictationPhase == DictationPhase.RECOGNIZING) {
            discardDictation();
            return;
        }
        if (!dictation.isAvailable() || turn != null) {
            return;
        }
        DictationPhase idle = dictationPhase;
        // Die Spracheingabe hat ihr eigenes Whisper-Modell (Vorgabe Tiny). Fehlt es, fragt die App erst und lädt es;
        // danach beginnt die Aufnahme.
        context.whisperModel().ensure(context.dictationWhisper(), this, status::setText, status::setText, () -> {
            if (dictationPhase != idle || !dictation.isAvailable() || turn != null) {
                return;
            }
            dictationPhase = DictationPhase.RECORDING;
            startDictationLevelMeter();
            refreshDictationControls();
            dictation.start(dictationResult());
        });
    }

    /** Discard the active recording or ignore a pending recognition callback. */
    private void discardDictation() {
        if (dictationPhase == DictationPhase.IDLE) {
            return;
        }
        boolean wasRecording = dictationPhase == DictationPhase.RECORDING;
        sendAfterDictation = false;
        dictation.cancel();
        if (dictationLive != null) {
            dictationLive.remove();
            dictationLive = null;
        }
        dictationPhase = DictationPhase.IDLE;
        stopDictationLevelMeter();
        status.setText(wasRecording ? "Aufnahme verworfen" : "Spracherkennung abgebrochen");
        refreshDictationControls();
    }

    /** Retry the original microphone capture after a recognition failure. */
    private void retryDictation() {
        if (dictationPhase != DictationPhase.IDLE || !dictation.canRetry() || turn != null) {
            return;
        }
        dictationPhase = DictationPhase.RECOGNIZING;
        status.setText(RECOGNIZING_STATUS);
        refreshDictationControls();
        dictation.retry(dictationResult());
    }

    /** Insert the transcript at the caret, preserving adjacent text and selection. */
    private DictationControl.Result dictationResult() {
        return new DictationControl.Result() {
            /** Hinter dem zuletzt eingefügten Abschnitt geht es weiter; -1, solange noch nichts eingefügt ist. */
            private int streamCaret = -1;

            @Override
            public void partial(String text) {
                if (dictationPhase != DictationPhase.IDLE) {
                    insertDictated(text);
                }
            }

            @Override
            public void live(String committed, String provisional) {
                if (dictationPhase == DictationPhase.IDLE) {
                    return;
                }
                if (dictationLive == null) {
                    dictationLive = LiveDictationRegion.open(editor,
                            streamCaret < 0 ? editor.getSelectionStart() : streamCaret,
                            streamCaret < 0 ? editor.getSelectionEnd() : streamCaret);
                }
                dictationLive.show(committed, provisional);
            }

            @Override
            public void liveDone(String text) {
                if (dictationPhase == DictationPhase.IDLE) {
                    return;
                }
                if (dictationLive == null) {
                    if (text != null && !text.isBlank()) {
                        insertDictated(text);
                    }
                    return;
                }
                int caret = dictationLive.done(text);
                dictationLive = null;
                if (caret >= 0) {
                    editor.setCaretPosition(caret);
                    streamCaret = caret;
                }
            }

            @Override
            public void recognized(String text) {
                if (dictationLive != null) {
                    liveDone(text);
                }
                dictationPhase = DictationPhase.IDLE;
                stopDictationLevelMeter();
                if (streamCaret < 0 && text != null && !text.trim().isEmpty()) {
                    insertDictated(text);
                }
                status.setText(streamCaret < 0 ? "Keine Sprache erkannt" : "Sprache erkannt – Text vor dem Senden prüfen");
                if (streamCaret >= 0) {
                    editor.requestFocusInWindow();
                }
                refreshDictationControls();
                if (sendAfterDictation) {
                    sendAfterDictation = false;
                    send();
                }
            }

            /** Fügt einen erkannten Abschnitt ein: zuerst am Cursor bzw. statt der Auswahl, dann hinter dem vorigen. */
            private void insertDictated(String text) {
                int start = streamCaret < 0 ? editor.getSelectionStart() : streamCaret;
                int end = streamCaret < 0 ? editor.getSelectionEnd() : streamCaret;
                DictationInsertion insertion = DictationInsertion.insert(editor.getText(), start, end, text);
                editor.setText(insertion.text);
                editor.setCaretPosition(insertion.caret);
                streamCaret = insertion.caret;
            }

            @Override
            public void failed(String message) {
                if (dictationLive != null) {
                    int caret = dictationLive.keepCommitted();
                    dictationLive = null;
                    if (caret >= 0) {
                        editor.setCaretPosition(caret);
                    }
                }
                dictationPhase = DictationPhase.IDLE;
                sendAfterDictation = false;
                stopDictationLevelMeter();
                String reason = message == null || message.trim().isEmpty() ?
                        "unbekannter Fehler" : message.trim();
                status.setText("Spracherkennung fehlgeschlagen: " + reason);
                refreshDictationControls();
                if (isShowing()) {
                    javax.swing.JOptionPane.showMessageDialog(ChatPanel.this,
                            "Die Spracheingabe ist fehlgeschlagen.\n\n" + reason +
                                    "\n\n" + dictation.description(),
                            "Spracheingabe", javax.swing.JOptionPane.WARNING_MESSAGE);
                }
            }
        };
    }

    /** Restore the reference composer's recording, discard and retry controls. */
    private void refreshDictationControls() {
        boolean active = dictationPhase != DictationPhase.IDLE;
        mic.setEnabled(dictation.isAvailable() && (active || turn == null));
        mic.setEmphasized(active);
        mic.setIcon(dictationPhase == DictationPhase.RECORDING ? ComposerIcons.stop()
                : dictationPhase == DictationPhase.RECOGNIZING ? ComposerIcons.close() : ComposerIcons.microphone());
        status.setForeground(active ? ResearchUiPalette.RECORDING_ORANGE : statusColor);
        mic.setToolTipText(dictationPhase == DictationPhase.RECORDING ?
                "Aufnahme beenden und erkennen" :
                dictationPhase == DictationPhase.RECOGNIZING ?
                        "Spracherkennung abbrechen" : DICTATE + " (" + dictation.description() + ")");
        discardButton.setVisible(active);
        retryButton.setVisible(!active && dictation.canRetry());
        fitFooter();
        levelBar.setVisible(active);
        if (!active) {
            levelBar.setValue(0);
        }
        composer.revalidate();
        composer.repaint();
    }

    /** Start the original EAC 100-ms live waveform and decibel level feedback. */
    private void startDictationLevelMeter() {
        recordingStartedMillis = System.currentTimeMillis();
        levelBar.setValue(0);
        waveform.setActive(true);
        levelTimer.restart();
    }

    private void stopDictationLevelMeter() {
        levelTimer.stop();
        waveform.setActive(false);
        levelBar.setValue(0);
    }

    private void sampleDictationLevel() {
        if (dictationPhase != DictationPhase.RECORDING) {
            levelTimer.stop();
            return;
        }
        int level = dictation.level();
        levelBar.setValue(level);
        waveform.pushLevel(level);
        showRecordingStatus();
    }

    /** Statuszeile während der Aufnahme: Dauer, Art der Spracheingabe, Übersteuerung. */
    private void showRecordingStatus() {
        long seconds = Math.max(0, (System.currentTimeMillis() - recordingStartedMillis) / 1000L);
        status.setText(String.format(java.util.Locale.ROOT, "● Aufnahme %d:%02d – %s%s",
                seconds / 60, seconds % 60, LiveDictationSettings.live() ? LIVE_MODE : COMPLETE_MODE,
                dictation.clipping() ? " – übersteuert" : ""));
    }

    // ------------------------------------------------------------------ Auswahl und Wissen

    /**
     * Was der eine Chat in einer anderen Perspektive als dem Space Harvester bekommt (#1151): den Bereich in Worten
     * (für Marke und Systemnachricht), einen Schlüssel für die Marke „Ab hier: …“, die Werkzeuge und die
     * Systemnachricht. Der Chat selbst, sein Verlauf und sein Ablageort bleiben dieselben.
     */
    public record ToolScope(String area, String scopeKey,
                            com.aresstack.spaceharvester.app.chat.ChatToolset tools, String system) {
    }

    /** Hinweis über dem Eingabefeld und Vorschläge (Chips) einer Perspektive, je nach gewähltem Bereich. */
    public record ToolHints(String hint, List<QuickAction> actions) {

        public ToolHints {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }

    /** Ein Vorschlag als Chip: Beschriftung, Tooltip und die Frage, die er stellt. */
    public record QuickAction(String label, String tip, String question) {
    }

    private java.util.function.Supplier<ToolScope> toolScope;
    private java.util.function.Supplier<ToolHints> toolHints;

    /**
     * Setzt die Werkzeuge der aktiven Perspektive (beim Senden ausgewertet) samt Hinweis und Vorschlägen oder nimmt
     * sie mit {@code null} weg; dann gilt wieder der links gewählte Bereich des Space Harvesters.
     */
    public void toolScope(java.util.function.Supplier<ToolScope> scope,
                          java.util.function.Supplier<ToolHints> hints) {
        this.toolScope = scope;
        this.toolHints = scope == null ? null : hints;
        if (scope != null) {
            toolScopeChanged();
        } else {
            refresh();
        }
    }

    /** Der Bereich der Perspektive hat gewechselt: Hinweis und Vorschläge neu. */
    public void toolScopeChanged() {
        if (toolScope == null) {
            return;
        }
        knowledge.setText(toolScopeHint());
        load.setVisible(false);
        updateSend();
        updateQuickActions();
    }

    /** Der Hinweis über dem Eingabefeld, solange die Werkzeuge einer Perspektive gelten. */
    String toolScopeHint() {
        ToolHints hints = toolHints == null ? null : toolHints.get();
        return hints == null || hints.hint() == null ? TOOL_SCOPE_HINT : hints.hint();
    }

    /** Der Hinweis, wenn die Perspektive keinen eigenen nennt. */
    static final String TOOL_SCOPE_HINT = "Command Deck: Der Chat sieht den gewählten Bereich – ändern kannst nur "
            + "du.";

    /** Die Auswahl links im Baum (in der Suche der Filter); {@code null} ohne Auswahl. */
    private Selection treeSelection;
    /** Die Seite, die die Leseansicht gerade zeigt; {@code null}, wenn keine offen ist. */
    private Selection reading;
    /** Ob die Leseansicht (Inhalte) vorne ist; sonst etwa die Suche. */
    private boolean readingFront;

    /**
     * Die Auswahl links; {@code null} ohne Auswahl. Sie ist der Bereich, solange nicht die Leseansicht mit einer
     * offenen Seite vorne ist (siehe {@link #reading}). Erst anmelden, dann lesen (ADR 0027).
     */
    public void selection(Selection selection) {
        treeSelection = selection;
        apply(scope());
        if (!Objects.equals(scope(), treeSelection)) {
            resolveTree();
        }
    }

    /**
     * Die Seite, die die Leseansicht gerade zeigt; {@code null}, wenn keine offen ist. ADR 0033 „Was ich sehe, sieht der
     * Bot“, Regel von Angelo 23.11.2026: Es zählt die Ansicht, die vorne ist. In der Leseansicht ist das geöffnete
     * Dokument der Bereich (samt Unterseiten wie eine gewählte Seite), auch wenn es aus der Suche kam und der Baum
     * woanders steht; in der Suche ist es die Auswahl im Baum, also der Filter. Der Baum wird dafür nie verändert.
     */
    public void reading(Selection page) {
        reading = page == null || page.global() || page.pageId() == null ? null : page;
        apply(scope());
    }

    /** Ob die Suche vorne ist: Dort arbeitet der Chat als Suchagent (Angelo 24.11.2026). */
    private boolean searchFront;

    /** Ob die Suche vorne ist (Hauptfenster beim Wechsel der Ansicht). */
    public void searchFront(boolean front) {
        searchFront = front;
    }

    /** Ob die Leseansicht vorne ist (Hauptfenster beim Wechsel der Ansicht). */
    public void readingFront(boolean front) {
        readingFront = front;
        apply(scope());
    }

    /** Verlässt die bisherige Ansicht und öffnet die neue; ohne Auswahl keine. */
    private void openView(Selection selection) {
        com.aresstack.spaceharvester.application.jobs.Foreground.close(view);
        view = selection == null ? null : "ansicht-" + VIEWS.incrementAndGet();
        if (view != null) {
            com.aresstack.spaceharvester.application.jobs.Foreground.open(view);
        }
    }

    /** Für Tests: die offene Ansicht. */
    String view() {
        return view;
    }

    /** Was der Chat als Bereich nimmt: vorne die Leseansicht mit offener Seite, sonst die Auswahl im Baum. */
    Selection scope() {
        return readingFront && reading != null ? reading : treeSelection;
    }

    private void apply(Selection selection) {
        if (Objects.equals(this.selection, selection)) {
            return;
        }
        this.selection = selection;
        openView(selection);
        ChatNode next = selection == null ? null
                : selection.global() ? ChatNode.ALL
                : selection instanceof Selection.Collection collection ? ChatNode.collection(collection.folderId())
                : new ChatNode(selection.sourceId(), selection.spaceKey(), selection.pageId());
        if (!Objects.equals(node, next)) {
            switchNode(next);
        }
        refresh();
    }

    /**
     * Ein anderer Bereich (Angelo, 21.11.2026): Der offene Chat bleibt stehen, auch eine laufende Antwort; nur der
     * Bereich der nächsten Frage wandert. Ein leerer Chat hat noch keinen Bereich und zieht mit, damit er dort
     * gespeichert wird, wo seine erste Frage fällt. Die erste Auswahl nach dem Start zeigt den zuletzt geänderten Chat
     * ihres Bereichs, damit man dort weitermacht, wo man aufgehört hat.
     */
    private void switchNode(ChatNode next) {
        node = next;
        if (!chat.isEmpty() || turn != null) {
            showChatName();
            return;
        }
        home = store.folder(stored());
        if (!resumed && node != null) {
            resumed = true;
            List<ChatRecord> saved = store.list(stored());
            if (!saved.isEmpty()) {
                open(saved.get(0));
                return;
            }
        }
        showChatName();
    }

    /**
     * Meldet sich beim Bestand jeder Wurzel der Auswahl an ({@link SelectionHoldings}: Quelle samt Space, „Wissensquellen“
     * jede Quelle, ein Favoriten-Ordner jeder Favorit); eine andere Auswahl oder Laufzeit meldet neu an.
     */
    private void subscribe() {
        AppRuntime runtime = context.runtime();
        List<com.aresstack.spaceharvester.application.holdings.Selection.Root> areas =
                SelectionHoldings.areas(runtime, selection);
        if (runtime == subscribed && areas.equals(subscribedAreas)) {
            return;
        }
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        subscribed = runtime;
        subscribedAreas = areas;
        subscriptions.addAll(SelectionHoldings.watch(runtime, areas, new HoldingsListener() {
            @Override
            public void pagesChanged(String sourceId, String spaceKey, Set<String> pageIds) {
                scheduleRefresh();
            }

            @Override
            public void spaceChanged(String sourceId, String spaceKey) {
                scheduleRefresh();
            }

            @Override
            public void loading(String sourceId, String spaceKey, Set<String> pageIds) {
                scheduleRefresh();
            }
        }));
    }

    /** Vom Zustell-Thread des Core: einmal auf dem EDT auffrischen, auch bei mehreren Meldungen hintereinander. */
    private void scheduleRefresh() {
        synchronized (this) {
            if (refreshPending) {
                return;
            }
            refreshPending = true;
        }
        SwingUtilities.invokeLater(() -> {
            synchronized (this) {
                refreshPending = false;
            }
            refresh();
        });
    }

    /** Liest die Seitenzahl der Auswahl im Hintergrund; nur das Ergebnis der jüngsten Abfrage zählt. */
    void refresh() {
        subscribe();
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        int ticket = ++request;
        if (runtime == null || current == null) {
            renderKnowledge(current, null, true);
            return;
        }
        // Bis die neue Auswahl aufgelöst ist, gilt die alte nicht mehr: Senden wartet, sonst gingen fremde Seiten mit.
        renderKnowledge(current, null, false);
        // Ein Favoriten-Ordner nachsichtig (ADR 0034 §4): ein veralteter Favorit fällt heraus und wird nur gezählt.
        Background.run(() -> SelectionHoldings.lenient(runtime, current), lenient -> {
            if (ticket == request) {
                renderKnowledge(current, lenient, true);
            }
        }, error -> {
            if (ticket == request) {
                renderKnowledge(current, null, true);
            }
        });
    }

    /**
     * Meldet jede aufgelöste Auswahl (ADR 0029), damit Analysen wie der Graph dieselben Seiten eingrenzen;
     * {@code null} ohne Auswahl oder Laufzeit.
     */
    public void onResolved(Consumer<PageSelection> listener) {
        this.onResolved = listener;
    }

    /** Die zuletzt aufgelöste Auswahl: die Seiten, die der Chat als Wissen sendet; {@code null} ohne Auswahl. */
    PageSelection resolved() {
        return resolved;
    }

    /**
     * @param lenient die aufgelöste Auswahl samt übersprungener Favoriten; {@code null}, solange (oder wenn) sie nicht
     *                aufgelöst ist
     * @param settled ob der Core die Auswahl aufgelöst hat (auch ergebnislos); vorher ist Senden gesperrt
     */
    private void renderKnowledge(Selection current,
                                 com.aresstack.spaceharvester.application.holdings.Holdings.Lenient lenient,
                                 boolean settled) {
        PageSelection resolved = lenient == null ? null : lenient.selection();
        this.resolved = resolved;
        this.settled = settled;
        boolean allowed = current == null || allowed(context.settings(), context.runtime(), current);
        HoldingCounts counts = resolved == null ? null : resolved.counts();
        knowledge.setText(knowledgeText(current, counts, resolved == null || resolved.complete(), allowed,
                lenient == null ? 0 : lenient.skippedRoots().size()));
        // Nachladen je Quelle, Space oder Seite; die globale Auswahl lädt man links je Wissensquelle, einen
        // Favoriten-Ordner je Favorit im Baum.
        load.setVisible(current instanceof Selection.Node && (counts == null || counts.stored() < counts.pages()
                || counts.pages() == 0));
        if (toolScope != null) {
            // Im Command Deck gilt nicht die Auswahl links, sondern der gewählte Bereich (#1151).
            knowledge.setText(toolScopeHint());
            load.setVisible(false);
        }
        // Abbrechen geht, solange eine Antwort läuft, auch ohne Auswahl oder während die neue noch aufgelöst wird.
        updateSend();
        updateQuickActions();
        // Analysen wie der Graph grenzen sich auf die Auswahl im Baum ein, nie auf die gelesene Seite des Chats.
        if (onResolved != null && Objects.equals(current, treeSelection)) {
            onResolved.accept(resolved);
        }
    }

    private int treeRequest;

    /** Löst die Auswahl im Baum allein für {@link #onResolved} auf, solange der Chat einen anderen Bereich nimmt. */
    private void resolveTree() {
        AppRuntime runtime = context.runtime();
        Selection tree = treeSelection;
        int ticket = ++treeRequest;
        if (onResolved == null) {
            return;
        }
        if (runtime == null || tree == null) {
            onResolved.accept(null);
            return;
        }
        Background.run(() -> SelectionHoldings.resolve(runtime, tree), resolved -> {
            if (ticket == treeRequest && onResolved != null) {
                onResolved.accept(resolved);
            }
        }, error -> {
            if (ticket == treeRequest && onResolved != null) {
                onResolved.accept(null);
            }
        });
    }

    /**
     * Abbrechen geht, solange eine Antwort läuft, auch ohne Auswahl oder während die neue noch aufgelöst wird (der
     * Chat bleibt beim Navigieren stehen); Senden braucht eine aufgelöste Auswahl oder gar keine (dann antwortet der
     * Chat ohne Bereich, Angelo 24.11.2026).
     */
    private void updateSend() {
        send.setEnabled(!readingAttachments && (turn != null || toolScope != null || selection == null || settled));
    }

    // ------------------------------------------------------------------ Schnellaktionen

    /**
     * Die Schnellaktionen für die untere Leiste des Hauptfensters, mittig (Angelo 17.11.2026): bei einer Seite
     * „Seite zusammenfassen“ und „Seite samt Unterseiten zusammenfassen“, sonst „Bereich zusammenfassen“, dazu
     * „Was ist neu?“. Sie wirken immer auf den gewählten Knoten samt Unterknoten, nie auf Eltern.
     */
    public JComponent quickActions() {
        return quickActions;
    }

    /** Baut die Chips zur Auswahl neu; gesperrt ohne aufgelöste Auswahl oder während einer Antwort. */
    private void updateQuickActions() {
        quickActions.removeAll();
        Selection current = selection;
        if (toolScope != null) {
            // Command Deck: die Vorschläge des gewählten Bereichs; die Werkzeuge kommen beim Klick frisch.
            ToolHints hints = toolHints == null ? null : toolHints.get();
            for (QuickAction action : hints == null ? List.<QuickAction>of() : hints.actions()) {
                quickActions.add(chip(action.label(), action.tip(), turn == null, () -> {
                    ToolScope custom = currentToolScope(action.question());
                    if (custom != null) {
                        submit(action.question(), null, custom.area(), ChatTurn.Kind.QUESTION, custom);
                    }
                }));
            }
        } else if (current != null) {
            // Bereit wie Senden: Auswahl aufgelöst und keine Antwort unterwegs.
            boolean ready = settled && turn == null;
            String area = areaText(current, resolved == null ? null : resolved.counts());
            if (current.pageId() != null) {
                // Nur die Seite: der Bereichstext folgt dem Umfang der Aktion, nicht der Auswahl samt Unterseiten.
                quickActions.add(chip(SUMMARIZE_PAGE, SUMMARIZE_PAGE_TIP, ready, () -> submit(
                        "Fasse die Seite „" + current.title() + "“ zusammen.", onlyPage(resolved, current),
                        pageAreaText(current), ChatTurn.Kind.QUESTION, null, summaryUse())));
                quickActions.add(chip(SUMMARIZE_SUBTREE, SUMMARIZE_SUBTREE_TIP, ready, () -> submit(
                        "Fasse die Seite „" + current.title() + "“ samt ihren Unterseiten zusammen.", resolved, area,
                        ChatTurn.Kind.QUESTION, null, summaryUse())));
            } else {
                quickActions.add(chip(SUMMARIZE_AREA, SUMMARIZE_AREA_TIP, ready, () -> submit(
                        "Fasse den Bereich „" + current.title() + "“ zusammen.", resolved, area,
                        ChatTurn.Kind.QUESTION, null, summaryUse())));
            }
            quickActions.add(chip(WHATS_NEW, WHATS_NEW_TIP, ready, () -> submit(WHATS_NEW, resolved, area,
                    ChatTurn.Kind.WHATS_NEW, null, summaryUse())));
        }
        // Ohne Auswahl gibt es keine Chips, also auch keine leere Zeile über der Eingabe.
        quickActions.setVisible(quickActions.getComponentCount() > 0);
        quickActions.revalidate();
        quickActions.repaint();
    }

    /** Die Beschriftungen der Vorschläge über der Eingabe, für Tests. */
    List<String> quickActionLabels() {
        List<String> labels = new ArrayList<>();
        for (java.awt.Component component : quickActions.getComponents()) {
            if (component instanceof javax.swing.AbstractButton button) {
                labels.add(button.getText());
            }
        }
        return labels;
    }

    private static ActionChip chip(String text, String tip, boolean enabled, Runnable action) {
        ActionChip chip = new ActionChip(text, tip);
        chip.setEnabled(enabled);
        chip.addActionListener(e -> action.run());
        return chip;
    }

    /** Der Bereichstext für „Seite zusammenfassen“: nur die Seite, eine Seite. */
    static String pageAreaText(Selection current) {
        return current.title() + " · 1 Seite";
    }

    /** Dieselbe Auswahl auf die gewählte Seite allein verengt, ohne Unterseiten. */
    static PageSelection onlyPage(PageSelection resolved, Selection current) {
        if (resolved == null || current == null || current.pageId() == null
                || !resolved.contains(current.sourceId(), current.pageId())) {
            return resolved;
        }
        return new PageSelection(com.aresstack.spaceharvester.application.snapshot.PageSet.of(
                java.util.Map.of(current.sourceId(), List.of(current.pageId()))), resolved.counts(),
                resolved.spaces(), resolved.complete());
    }

    /**
     * Ob jede Quelle der Auswahl Inhalte an den KI-Dienst geben darf: je Wurzel ihre Quelle, für „Wissensquellen“ jede
     * Wissensquelle der Laufzeit, für einen Favoriten-Ordner die Quelle jedes Favoriten.
     */
    static boolean allowed(AppSettings settings, AppRuntime runtime, Selection selection) {
        if (runtime == null && selection.global()) {
            return true;
        }
        return selection.roots(runtime).stream().allMatch(root -> allowed(settings, root.sourceId()));
    }

    /** Ob die Quelle Inhalte an den KI-Dienst geben darf ({@code ai.external-processing-sources}). */
    static boolean allowed(AppSettings settings, String sourceId) {
        return settings != null && settings.ai() != null
                && settings.ai().externalProcessingSources().contains(sourceId);
    }

    /**
     * Die Statuszeile des Wissens: „Wissen: ‹Seite› samt Unterseiten · n Seiten“, für einen Space „Space ‹Name›“,
     * für eine Quelle „‹Quelle› · alle Spaces“; dazu, was nicht geladen ist, und ob die Quelle für KI freigegeben ist.
     */
    static String knowledgeText(Selection selection, HoldingCounts counts, boolean complete, boolean allowed,
                                int skipped) {
        if (selection == null) {
            return NO_SELECTION;
        }
        StringBuilder text = new StringBuilder("Wissen: ").append(areaText(selection, counts));
        if (!complete) {
            // Ein Space ohne Inventur: der Cache kennt nur einzeln geladene Seiten, Unterseiten fehlen womöglich.
            text.append(" · ").append(ExportPanel.INCOMPLETE);
        }
        if (skipped > 0) {
            // ADR 0034 §4: veraltete oder nicht sichtbare Favoriten fallen heraus, statt den Ordner zu brechen.
            text.append(" · ").append(Texts.count(skipped, "Favorit", "Favoriten")).append(" nicht verfügbar");
        }
        if (!allowed) {
            text.append(" · ").append(NOT_ALLOWED);
        }
        return text.toString();
    }

    /** Der gewählte Bereich in Worten, für die Zeile „Wissen:“ und die Systemnachricht; {@code null} ohne Auswahl. */
    static String areaText(Selection selection, HoldingCounts counts) {
        if (selection == null) {
            return null;
        }
        StringBuilder text = new StringBuilder();
        if (selection.global()) {
            text.append("alle Wissensquellen");
        } else if (selection instanceof Selection.Collection collection) {
            // ADR 0034 §5: nur die Bereichszeile, Ordnername und Seitenzahl.
            text.append("Ordner ").append(collection.title()).append(collection.withSubfolders()
                    ? " samt Unterordnern" : "");
        } else if (selection.pageId() != null) {
            text.append(selection.title()).append(" samt Unterseiten");
        } else if (selection.spaceKey() != null) {
            text.append("Space ").append(selection.title());
        } else {
            text.append(selection.title()).append(" · alle Spaces");
        }
        if (counts == null || counts.pages() == 0) {
            text.append(" · ").append(NOT_LOADED);
        } else {
            text.append(" · ").append(counts.pages()).append(counts.pages() == 1 ? " Seite" : " Seiten");
            // Veraltete Seiten sind geladen, nur nicht aktuell; „nicht geladen“ zählt nur, was ganz fehlt.
            int missing = counts.pages() - counts.stored();
            if (missing > 0) {
                text.append(" · ").append(missing).append(" davon nicht geladen");
            }
        }
        return text.toString();
    }

    /** Startet den Download der Auswahl sofort; der Auftrag läuft unter Aufträge, die Bestandsmeldung frischt hier auf. */
    private void load() {
        Selection current = selection;
        if (current == null) {
            return;
        }
        load.setEnabled(false);
        feedback.working("Download wird gestartet …");
        Consumer<Downloads.Started> started = result -> {
            if (!result.failed().isEmpty()) {
                feedback.warning(Texts.count(result.failed().size(), "Download ließ", "Downloads ließen")
                        + " sich nicht starten", String.join("; ", result.failed()));
            } else if (result.jobs().isEmpty()) {
                feedback.info("Kein Space zum Herunterladen", null);
            } else {
                feedback.working(downloadName(current) + " gestartet");
                watch(result.jobs());
            }
            load.setEnabled(true);
        };
        java.util.function.Consumer<Throwable> failed = error -> {
            feedback.problem(error);
            load.setEnabled(true);
        };
        if (current.page()) {
            Downloads.startPage(context, current.sourceId(), current.spaceKey(), current.pageId(), true, started,
                    failed);
        } else {
            Downloads.start(context, current.sourceId(), current.spaceKey() == null ? List.of()
                    : List.of(current.spaceKey()), started, failed);
        }
    }

    static String downloadName(Selection current) {
        return current.page() ? "Download der Seite samt Unterseiten"
                : current.spaceKey() == null ? "Download aller Spaces" : "Download des Space";
    }

    /**
     * Verfolgt die gestarteten Aufträge über die Auftragsmeldungen des Core (ADR 0027, nie per Abfrage): endet der
     * letzte, ersetzt „abgeschlossen“ (mit Seitenzahl und Ablageort) oder „fehlgeschlagen“ die Laufmeldung; die
     * bleibt stehen, bis ✕ sie schließt.
     */
    private void watch(List<JobStatus> jobs) {
        watchedJobs.clear();
        jobOutcomes.clear();
        for (JobStatus job : jobs) {
            watchedJobs.add(job.id());
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        if (runtime != jobsSubscribed) {
            if (jobSubscription != null) {
                jobSubscription.close();
            }
            jobsSubscribed = runtime;
            jobSubscription = runtime.workspace().subscribeJobs(new JobListener() {
                @Override
                public void changed(JobStatus status) {
                    SwingUtilities.invokeLater(() -> jobChanged(status));
                }

                @Override
                public void removed(JobStatus last) {
                    SwingUtilities.invokeLater(() -> watchedJobs.remove(last.id()));
                }
            });
        }
        // Erst anmelden, dann lesen: ein Auftrag, der schon fertig war, meldet sich sonst nie.
        for (JobStatus job : runtime.workspace().jobs()) {
            if (watchedJobs.contains(job.id())) {
                jobChanged(job);
            }
        }
    }

    private void jobChanged(JobStatus status) {
        if (!watchedJobs.contains(status.id()) || !status.state().terminal()) {
            return;
        }
        watchedJobs.remove(status.id());
        jobOutcomes.add(status);
        if (!watchedJobs.isEmpty()) {
            // Eine Quelle mit mehreren Spaces hat je Space einen Auftrag: erst das Ende des letzten zählt.
            return;
        }
        Selection current = selection;
        String name = current == null ? "Download" : downloadName(current);
        String where = context.settings() == null ? null : context.settings().storage().dataDirectory().toString();
        List<JobStatus> outcomes = List.copyOf(jobOutcomes);
        jobOutcomes.clear();
        showOutcome(name, outcomes, where);
    }

    /**
     * Die eine Meldung für alle verfolgten Aufträge: ein Fehler überwiegt jeden Erfolg, Seiten werden über alle
     * Aufträge gezählt; sie bleibt stehen, bis ✕ sie schließt.
     */
    private void showOutcome(String name, List<JobStatus> outcomes, String where) {
        long written = 0;
        boolean incomplete = false;
        List<String> failures = new ArrayList<>();
        int cancelled = 0;
        int paused = 0;
        for (JobStatus job : outcomes) {
            written += job.progress().written();
            incomplete |= job.incomplete();
            switch (job.state()) {
                case FAILED -> failures.add(job.error() == null ? Texts.state(job) : job.error().message());
                case CANCELLED -> cancelled++;
                case PAUSED -> paused++;
                default -> {
                }
            }
        }
        String pages = Texts.count(written, "Seite", "Seiten") + " im Bestand" + (where == null ? "" : " unter " + where);
        if (!failures.isEmpty()) {
            feedback.warning(name + " " + DOWNLOAD_FAILED + (outcomes.size() > 1
                    ? " (" + failures.size() + " von " + outcomes.size() + " Aufträgen)" : ""),
                    String.join("; ", failures) + " · " + pages);
        } else if (cancelled > 0) {
            feedback.info(name + " abgebrochen", pages);
        } else if (paused > 0) {
            feedback.info(name + " angehalten", pages);
        } else {
            feedback.success(name + " " + DOWNLOAD_DONE + " · " + pages + (incomplete ? " · unvollständig" : ""));
        }
    }

    // ------------------------------------------------------------------ Composer

    private void showModel() {
        AppSettings settings = context.settings();
        String name = settings == null || settings.ai() == null ? null : settings.ai().chatModel();
        modelName = name == null || name.isBlank() ? NO_MODEL : name;
        fitFooter();
    }

    /**
     * Die Fußzeile der Eingabe in der schmalen Leiste (Angelo, 21.11.2026): Reicht der Platz nicht, zeigt erst die
     * Modellwahl, dann auch der Denkaufwand nur noch das Symbol; der Name steht dann im Tooltip. Reicht auch das
     * nicht, weicht die Websuche. „Senden“, Anhängen und Diktat bleiben immer ganz sichtbar.
     */
    private void fitFooter() {
        int stage = 0;
        showFooter(0);
        if (footerWest != null && footerWest.getParent() != null && footerWest.getParent().getWidth() > 0) {
            // Die Breiten hängen von der Schrift ab, darum wird gemessen statt gerechnet.
            int width = footerWest.getParent().getWidth() - FOOTER_GAP;
            while (stage < 3
                    && footerWest.getPreferredSize().width + footerEast.getPreferredSize().width > width) {
                showFooter(++stage);
            }
        }
        if (stage != footerStage) {
            footerStage = stage;
            composer.revalidate();
            composer.repaint();
        }
    }

    private void showFooter(int stage) {
        model.setText(stage >= 1 ? null : modelName);
        model.setToolTipText(MODEL_TIP + (NO_MODEL.equals(modelName) && stage < 1 ? "" : ": " + modelName));
        effort.setText(stage >= 2 ? null : effortLevel);
        effort.setToolTipText(EFFORT_TIP + ": " + effortLevel);
        // Letzte Stufe für sehr schmale Leisten oder breite Schriften: die Websuche weicht.
        web.setVisible(stage < 3);
        webBudget.setVisible(stage < 3);
        appSearch.setVisible(stage < 3);
        appSearchGap.setVisible(stage < 3);
    }

    /** Schaltet die interne Suche: an ist der Knopf eingerastet. */
    void appSearch(boolean on) {
        appSearchOn = on;
        appSearch.setEmphasized(on);
        appSearch.setToolTipText(on ? APP_SEARCH_ON : APP_SEARCH_OFF);
        warnIfBothOn();
    }

    /**
     * Sind Websuche und interne Suche zugleich an, warnt die Meldung oben (mit ✕), dass Inhalte aus Wissensquellen in
     * Browseraufrufe einfließen können. Schaltet man eine aus, verschwindet die Warnung wieder.
     */
    private void warnIfBothOn() {
        boolean both = webOn && appSearchOn;
        if (both && !bothWarned) {
            feedback.warning(BOTH_ON_WARNING, null);
        } else if (!both && bothWarned && feedback.isVisible() && feedback.text().contains(BOTH_ON_WARNING)) {
            // Nur die eigene Warnung weg; hat inzwischen eine andere Meldung sie ersetzt, bleibt diese stehen.
            feedback.clear();
        }
        bothWarned = both;
    }

    /** Für Tests: die Meldung oben. */
    Feedback feedback() {
        return feedback;
    }

    boolean appSearchOn() {
        return appSearchOn;
    }

    /** Die Wissensquellen, die die interne Suche durchsuchen darf: alle für die KI freigegebenen (ADR 0028). */
    static Set<String> appSearchSources(AppSettings settings, AppRuntime runtime) {
        return appSearchSources(settings, runtime, false);
    }

    private static Set<String> appSearchSources(AppSettings settings, AppRuntime runtime, boolean explicit) {
        if (runtime == null) {
            return Set.of();
        }
        Set<String> ids = new java.util.TreeSet<>();
        for (HarvestSource source : runtime.workspace().sources()) {
            if (allowed(settings, source.id()) && (explicit || settings.sources().stream()
                    .filter(entry -> entry.id().equals(source.id()))
                    .allMatch(entry -> entry.config().includeInGlobalSearch()))) {
                ids.add(source.id());
            }
        }
        return ids;
    }

    /** Schaltet die Websuche dieses Chats (Composer-Knopf wie „Browser“ im Enterprise AI Client). */
    void webSearch(boolean on) {
        webOn = on && webAvailable();
        showWebState();
        warnIfBothOn();
    }

    boolean webSearchOn() {
        return webOn;
    }

    /** Ob es Firefox gibt; ohne bleibt der Knopf gesperrt und sagt warum. */
    private boolean webAvailable() {
        return context.webBrowser() != null;
    }

    /** Knopf und Pfeil zeigen den Zustand: an hervorgehoben, ohne Firefox gesperrt mit Grund. */
    private void showWebState() {
        boolean available = webAvailable();
        web.setEnabled(available);
        webBudget.setEnabled(available);
        web.setEmphasized(webOn);
        web.setToolTipText(!available ? WEB_UNAVAILABLE : webOn ? WEB_ON : WEB);
        webBudget.setToolTipText(WEB_BUDGET_TIP);
    }

    private static int webInt(String key, int fallback) {
        return com.aresstack.spaceharvester.app.ui.AppState.shared().getInt(WEB_KEY + key, fallback);
    }

    private static boolean webFlag(String key) {
        return com.aresstack.spaceharvester.app.ui.AppState.shared().getBoolean(WEB_KEY + key, false);
    }

    private static void webPut(String key, int value) {
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(WEB_KEY + key, value);
    }

    private static void webPut(String key, boolean value) {
        com.aresstack.spaceharvester.app.ui.AppState.shared().put(WEB_KEY + key, value);
    }

    /**
     * Die Einträge „Parallele Tabs“, „Parallele Agenten (intern)“ und „Parallele Dateien“ für das Menü am Dreieck der
     * Chat-Leiste, mit dem Code der Aufträge ({@link com.aresstack.spaceharvester.app.ui.ParallelChoice}).
     */
    public List<javax.swing.JMenu> parallelMenus() {
        return List.of(parallelChoice("Parallele Tabs: wie viele Seiten die Websuche gleichzeitig "
                        + "öffnet und auswertet", "tabs", WEB_TABS_DEFAULT).menu(PARALLEL_TABS),
                parallelChoice("Parallele Agenten (intern): wie viele Agenten Treffer der internen "
                        + "Suche gleichzeitig lesen und auswerten", "agents", AGENTS_DEFAULT).menu(PARALLEL_AGENTS),
                parallelChoice("Parallele Dateien: wie viele lokale Dateien und Anhänge (auch PDF) "
                        + "gleichzeitig gelesen werden", "files", FILES_DEFAULT).menu(PARALLEL_FILES),
                parallelChoice("Runden ohne Neues: nach so vielen Werkzeugrunden ohne neues Ergebnis bittet der "
                        + "Chat die KI, mit dem Gefundenen zu antworten; sonst sucht sie, bis sie fertig ist",
                        "quiet", QUIET_ROUNDS_DEFAULT).menu(QUIET_ROUNDS),
                parallelChoice("Suchauszüge je Frage: so viele Treffer der Suche gehen ohne Werkzeuge als Auszüge "
                        + "an die KI, zusammen nie mehr, als in die Anfrage passt", EXCERPTS,
                        ChatKnowledge.SOURCES_DEFAULT).menu(SEARCH_EXCERPTS));
    }

    private com.aresstack.spaceharvester.app.ui.ParallelChoice parallelChoice(String tip, String key, int fallback) {
        return com.aresstack.spaceharvester.app.ui.ParallelChoice.stored(this, tip, WEB_KEY + key, fallback,
                text -> feedback.warning(text, null));
    }

    /** Eine der Grenzen der Parallelität, mindestens 1. */
    static int parallel(String key, int fallback) {
        return com.aresstack.spaceharvester.app.ui.ParallelChoice.stored(WEB_KEY + key, fallback);
    }

    /** Öffnet das Menü am Pfeil: Profil, Verzögerung, Human Emulation, Maus. */
    private void openWebPopup() {
        java.util.List<WebSearchPopup.Row> rows = new ArrayList<>();
        rows.add(new WebSearchPopup.Row(WebSearchPopup.DELAY_TITLE, webInt("delay", 0), 0, WEB_DELAY_MAX, 0,
                WEB_DELAY_SLIDER_MAX, value -> webPut("delay", value)));
        java.util.List<WebSearchPopup.Check> checks = new ArrayList<>();
        checks.add(new WebSearchPopup.Check(WebSearchPopup.HUMAN_TITLE, WebSearchPopup.HUMAN_TIP, webFlag("human"),
                value -> webPut("human", value)));
        checks.add(new WebSearchPopup.Check(WebSearchPopup.MOUSE_TITLE, WebSearchPopup.MOUSE_TIP, webFlag("mouse"),
                value -> webPut("mouse", value)));
        WebSearchPopup.Check profile = new WebSearchPopup.Check(WebSearchPopup.PROFILE_TITLE,
                WebSearchPopup.PROFILE_TIP, webFlag("internal"), value -> webPut("internal", value));
        lastWebPopup = new WebSearchPopup(com.aresstack.enterpriseai.ui.comic.theme.ComicPalette.defaultPalette(),
                profile, rows, checks);
        lastWebPopup.showAbove(webBudget);
    }

    /** Für Tests: das zuletzt geöffnete Menü am Pfeil. */
    WebSearchPopup lastWebPopup() {
        return lastWebPopup;
    }

    /**
     * Die Websuche eines Turns: Browser-Werkzeuge des Chats, Regeln, Budget und das Ende (schließt den Browser und
     * lässt die Karte platzen); {@code null}, wenn sie aus ist oder Firefox fehlt.
     */
    private ChatTurn.WebSearch webSearchFor(String chatId, WebSearchCard card, TurnListener listener) {
        com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser browser = webOn ? context.webBrowser() : null;
        if (browser == null) {
            return null;
        }
        browser.profile(chatId, webFlag("internal"));
        browser.load(chatId, parallel("tabs", WEB_TABS_DEFAULT), webInt("delay", 0));
        browser.humanEmulation(chatId, webFlag("human"), webFlag("mouse"));
        // Rückfragen gehören zu diesem Chat und dieser Antwort, nie zum gerade sichtbaren Verlauf.
        com.aresstack.spaceharvester.app.chat.browser.ChatApprovalPrompt prompt =
                new com.aresstack.spaceharvester.app.chat.browser.ChatApprovalPrompt(listener::askApproval,
                        SwingUtilities::invokeLater);
        String rules = com.aresstack.spaceharvester.app.chat.browser.BrowserRules.BROWSER + "\n"
                + browser.profileRule(chatId);
        java.util.concurrent.atomic.AtomicLong begun = new java.util.concurrent.atomic.AtomicLong(-1);
        return new ChatTurn.WebSearch(new com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools(
                browser.tools(chatId), call -> browser.asking(prompt, call)), rules,
                () -> begun.set(browser.beginTurn(chatId,
                        progress -> SwingUtilities.invokeLater(() -> card.update(progress)))),
                () -> {
                    long turn = begun.get();
                    var last = turn < 0 ? null : browser.endTurn(chatId, turn);
                    if (last != null) {
                        SwingUtilities.invokeLater(() -> card.update(last));
                    }
                });
    }

    /**
     * Eine Rückfrage im Verlauf wie {@code ChatTranscriptPanel.showApproval} im Enterprise AI Client: gelbe
     * Aktivitätsblase mit „Bestätigen“/„Abbrechen“ oder, wenn gemerkte Antworten möglich sind, „Einmal erlauben“,
     * „Immer erlauben“, „Immer verbieten“ und „Abbrechen“. Der erste Klick gilt. Nur auf dem EDT.
     */
    com.aresstack.spaceharvester.app.chat.browser.ChatApprovalPrompt.Handle showApproval(String title,
            String description, boolean offersAlways,
            java.util.function.Consumer<com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome> answer) {
        if (listening != null) {
            listening.behindActivity();
        }
        com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel thought =
                new com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel(BubbleSide.LEFT, bubbles,
                        title, description);
        thought.setHeaderTimestamp(System.currentTimeMillis());
        JPanel row = new JPanel();
        row.setName("transcript.approval");
        row.setOpaque(false);
        row.setLayout(new javax.swing.BoxLayout(row, javax.swing.BoxLayout.Y_AXIS));
        BubbleMessageRow bubbleRow = new BubbleMessageRow(thought, BubbleSide.LEFT);
        bubbleRow.setAlignmentX(LEFT_ALIGNMENT);
        row.add(bubbleRow);
        // Knöpfe im App-Stil (Angelo 24.11.2026): erlauben als Hauptaktion, „Immer verbieten“ rot, Rest flach.
        java.util.Map<javax.swing.JButton, com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome> outcomes =
                new java.util.LinkedHashMap<>();
        outcomes.put(Comic.primary(offersAlways ? APPROVE_ONCE_TEXT : APPROVE_TEXT, null),
                com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.APPROVED);
        if (offersAlways) {
            outcomes.put(Comic.button(APPROVE_ALWAYS_TEXT, null),
                    com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.APPROVED_ALWAYS);
            outcomes.put(Comic.danger(REJECT_ALWAYS_TEXT, null),
                    com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.REJECTED_ALWAYS);
        }
        outcomes.put(Comic.button(REJECT_TEXT, null),
                com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.REJECTED);
        JPanel buttons = buttonPanel();
        boolean[] answered = {false};
        for (var entry : outcomes.entrySet()) {
            buttons.add(entry.getKey());
            entry.getKey().addActionListener(event -> {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                outcomes.keySet().forEach(button -> button.setEnabled(false));
                answer.accept(entry.getValue());
            });
        }
        row.add(buttons);
        row.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return (approved, outcome) -> {
            answered[0] = true;
            outcomes.keySet().forEach(button -> button.setEnabled(false));
            row.remove(buttons);
            if (thought.getVisualState()
                    == com.aresstack.enterpriseai.ui.comic.bubble.AnimatedThoughtBubblePanel.VisualState.RUNNING) {
                if (approved) {
                    thought.completeSuccessfully(outcome, null);
                } else {
                    thought.completeWithFailure(outcome, null);
                }
            }
            row.revalidate();
            row.repaint();
        };
    }

    /** Die Knopfzeile unter einer gelben Blase; umbrechend, damit in der schmalen Leiste nichts abgeschnitten wird. */
    private static JPanel buttonPanel() {
        JPanel buttons = new JPanel(
                new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 8, 4)) {
            @Override
            public Dimension getMaximumSize() {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(0, 22, 0, 12));
        buttons.setAlignmentX(LEFT_ALIGNMENT);
        return buttons;
    }

    /** Eine Aktivitätsblase im Verlauf: die Karte „Browserrecherche“, die Gedankenblase oder eine Werkzeugwolke. */
    private com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel addActivity(String title,
                                                                                         String body) {
        if (listening != null) {
            listening.behindActivity();
        }
        com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel bubble =
                new com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel(BubbleSide.LEFT, bubbles,
                        title, body);
        bubble.setHeaderTimestamp(System.currentTimeMillis());
        bubble.setMaximumBubbleWidth(640);
        BubbleMessageRow bubbleRow = new BubbleMessageRow(bubble, BubbleSide.LEFT);
        bubbleRow.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(bubbleRow);
        if (listening != null) {
            listening.linksBelow();
        }
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return bubble;
    }

    /** Tooltip des Kreispfeils hinter „… davon nicht geladen“. */
    static final String RETRY_MISSING = "Nicht geladene Seiten erneut laden";
    /** Tooltip des Fadenkreuzes dahinter. */
    static final String OPEN_MISSING = "Nicht geladene Seite öffnen (Zurück führt hierher zurück)";
    private static final java.util.regex.Pattern MISSING_COUNT =
            java.util.regex.Pattern.compile("\\d+ davon nicht geladen");

    /** Der Teil der Wissenszeile, hinter dem die Icons stehen; {@code null}, wenn nichts fehlt. */
    static String notLoadedAnchor(String knowledgeLine) {
        if (knowledgeLine == null) {
            return null;
        }
        java.util.regex.Matcher missing = MISSING_COUNT.matcher(knowledgeLine);
        if (missing.find()) {
            return missing.group();
        }
        return knowledgeLine.contains(NOT_LOADED) ? NOT_LOADED : null;
    }

    /**
     * Kreispfeil (erneut laden, wie bisher „Laden“) und, wenn der Bereich Seiten kennt, Fadenkreuz (die erste nicht
     * geladene Seite in der Seitenansicht öffnen; deren Verlauf führt mit Zurück und der Maustaste zurück).
     */
    private List<JComponent> missingActions(PageSelection scope) {
        List<JComponent> actions = new ArrayList<>();
        IconButton retry = new IconButton(Glyph.REFRESH, RETRY_MISSING).compact();
        retry.addActionListener(e -> {
            retry.setEnabled(false);
            load();
        });
        actions.add(retry);
        if (scope != null && scope.counts().pages() > scope.counts().stored()) {
            IconButton locate = new IconButton(Glyph.CROSSHAIR, OPEN_MISSING).compact();
            locate.addActionListener(e -> openMissing(scope, locate));
            actions.add(locate);
        }
        return actions;
    }

    private void openMissing(PageSelection scope, IconButton locate) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return;
        }
        locate.setEnabled(false);
        Background.run(() -> firstMissing(runtime.workspace(), scope), key -> {
            locate.setEnabled(true);
            if (key == null) {
                feedback.info("Keine nicht geladene Seite mehr", null);
            } else {
                context.navigator().openPage(key.sourceId(), key.pageId());
            }
        }, error -> {
            locate.setEnabled(true);
            feedback.problem(error);
        });
    }

    /**
     * Die erste Seite des Bereichs, die nicht geladen ist; {@code null}, wenn keine fehlt. Dieselbe Zählung wie
     * „… davon nicht geladen“ ({@link #areaText}: {@code pages() - stored()}, also auch gesperrte Seiten). Bis
     * 24.11.2026 nahm das Fadenkreuz nur {@code REMOTE}; war die angezeigte Seite gesperrt ({@code LOCKED}), meldete es
     * „Keine nicht geladene Seite mehr“ (Angelo 24.11.2026).
     */
    static com.aresstack.spaceharvester.application.snapshot.PageKey firstMissing(com.aresstack.spaceharvester.app.runtime.Workspace workspace, PageSelection scope) {
        for (com.aresstack.spaceharvester.application.holdings.SpaceSummary space : scope.spaces()) {
            Set<String> ids = scope.pagesOf(space.sourceId());
            if (ids.isEmpty()) {
                continue;
            }
            for (com.aresstack.spaceharvester.application.holdings.PageHolding page
                    : workspace.holdings(space.sourceId(), space.spaceKey(), ids).pages().values()) {
                if (ids.contains(page.pageId()) && notLoaded(page.state())) {
                    return new com.aresstack.spaceharvester.application.snapshot.PageKey(space.sourceId(), page.pageId());
                }
            }
        }
        return null;
    }

    /** Ob eine Seite in diesem Zustand als „nicht geladen“ zählt, genau wie in der Wissenszeile. */
    static boolean notLoaded(com.aresstack.spaceharvester.application.holdings.HoldingState state) {
        HoldingCounts one = HoldingCounts.of(state);
        return one.pages() > one.stored();
    }

    /** Für Tests: wie knapp die Fußzeile gerade ist (0 bis 3). */
    int footerStage() {
        return footerStage;
    }

    private void showModelMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem current = new JMenuItem(modelName);
        current.setEnabled(false);
        menu.add(current);
        menu.addSeparator();
        JMenuItem settings = new JMenuItem("Modelle unter Einstellungen › KI …");
        settings.addActionListener(e -> context.navigator().showGeneralSettings());
        menu.add(settings);
        PopupHover.clearOnClose(menu, model).show(model, 0, model.getHeight());
    }

    private void showEffortMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (String level : EFFORTS) {
            JMenuItem item = new JMenuItem((level.equals(effortLevel) ? "✓ " : "    ") + level);
            item.addActionListener(e -> {
                effortLevel = level;
                fitFooter();
            });
            menu.add(item);
        }
        PopupHover.clearOnClose(menu, effort).show(effort, 0, effort.getHeight());
    }

    private void chooseAttachments() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(ATTACH);
        chooser.setMultiSelectionEnabled(true);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File file : chooser.getSelectedFiles()) {
                attach(file.toPath());
            }
        }
    }

    /** Merkt eine Datei als Anhang vor: ein Chip mit Namen und ✕ über dem Editor. */
    void attach(Path file) {
        if (attachments.contains(file)) {
            return;
        }
        attachments.add(file);
        startImageReading(file);
        addChip(null, file.getFileName().toString(), file.toString(), "Anhang entfernen",
                () -> {
                    attachments.remove(file);
                    earlyImages.remove(file);
                });
        attach.setToolTipText(ATTACH + " (" + attachments.size() + " vorgemerkt)");
    }

    /**
     * Merkt einen markierten Ausschnitt als Zitat für die nächste Frage vor (Angelo 27.11.2026): ein Chip mit
     * Anführungszeichen, dem Anfang des Ausschnitts und ✕, im Tooltip der ganze (gekürzt). Derselbe Ausschnitt nur
     * einmal; die Eingabe bekommt den Fokus, damit gleich die Frage folgen kann.
     */
    void quote(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        String passage = text.strip();
        if (!quotes.contains(passage)) {
            quotes.add(passage);
            String line = passage.replaceAll("\\s+", " ");
            quoteChips.put(passage, addChip(new com.aresstack.spaceharvester.app.ui.controls.QuoteIcon(),
                    ellipsize(line, QUOTE_CHIP_CHARS),
                    QUOTE + ": \u201E" + ellipsize(line, QUOTE_TOOLTIP_CHARS) + "\u201C", "Zitat entfernen", () -> {
                        quotes.remove(passage);
                        quoteChips.remove(passage);
                    }));
        }
        editor.requestFocusInWindow();
    }

    static final String QUOTE = "Zitat";
    private static final int QUOTE_CHIP_CHARS = 28;
    private static final int QUOTE_TOOLTIP_CHARS = 240;

    private static String ellipsize(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }

    /** Ein Chip über dem Editor (Anhang oder Zitat): Symbol, Name, ✕; das ✕ nimmt ihn mit {@code removed} zurück. */
    private JPanel addChip(javax.swing.Icon icon, String label, String tooltip, String removeTip, Runnable removed) {
        JPanel chip = new JPanel(new BorderLayout(4, 0));
        chip.setOpaque(true);
        chip.setBackground(Comic.SURFACE);
        chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.LINE),
                BorderFactory.createEmptyBorder(1, 6, 1, 2)));
        JLabel name = new JLabel(label, icon, JLabel.LEADING);
        name.setFont(Comic.regular(12f));
        name.setForeground(Comic.INK);
        name.setToolTipText(tooltip);
        ComposerButton remove = ComposerButton.iconButton(ComposerIcons.close(), removeTip);
        remove.addActionListener(e -> {
            removed.run();
            chips.remove(chip);
            chips.setVisible(!attachments.isEmpty() || !quotes.isEmpty());
            chips.revalidate();
            composer.repaint();
        });
        chip.add(name, BorderLayout.CENTER);
        chip.add(remove, BorderLayout.EAST);
        chips.add(chip);
        chips.setVisible(true);
        chips.revalidate();
        composer.repaint();
        return chip;
    }

    /** Der Chat nimmt markierten Text aus jedem Textfeld der App an, solange er zu sehen ist, außer aus der Eingabe. */
    private final com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.QuoteTarget quoteTarget =
            new com.aresstack.spaceharvester.app.ui.controls.TextContextMenu.QuoteTarget() {
                @Override
                public boolean accepts(javax.swing.text.JTextComponent field) {
                    return field != editor && isShowing();
                }

                @Override
                public void quote(String text) {
                    ChatPanel.this.quote(text);
                }
            };

    /** Zeigt „Zitieren“ mittig über der Markierung (passt es oben nicht hin, darunter), wie im EAC. */
    private void showQuoteOverlay(com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection) {
        if (selection == null || selection.anchor() == null || !selection.anchor().isShowing()) {
            hideQuoteOverlay();
            return;
        }
        quotedSelection = selection;
        Dimension size = quoteOverlay.getPreferredSize();
        java.awt.Rectangle area = SwingUtilities.convertRectangle(selection.anchor(), selection.bounds(),
                transcriptLayers);
        int x = area.x + area.width / 2 - size.width / 2;
        x = Math.max(QUOTE_OVERLAY_GAP, Math.min(x, transcriptLayers.getWidth() - size.width - QUOTE_OVERLAY_GAP));
        int y = area.y - size.height - QUOTE_OVERLAY_GAP;
        if (y < QUOTE_OVERLAY_GAP) {
            y = area.y + area.height + QUOTE_OVERLAY_GAP;
        }
        // Reicht die Markierung über die ganze Höhe, bleibt der Knopf trotzdem im sichtbaren Verlauf.
        y = Math.max(QUOTE_OVERLAY_GAP, Math.min(y, transcriptLayers.getHeight() - size.height - QUOTE_OVERLAY_GAP));
        quoteOverlay.setBounds(x, y, size.width, size.height);
        quoteOverlay.setVisible(true);
        transcriptLayers.repaint();
    }

    private static final int QUOTE_OVERLAY_GAP = 6;

    private void hideQuoteOverlay() {
        quotedSelection = null;
        if (quoteOverlay.isVisible()) {
            quoteOverlay.setVisible(false);
            transcriptLayers.repaint();
        }
    }

    /** „Zitieren“ geklickt: der Ausschnitt wird ein Zitat-Chip, die Markierung verschwindet. */
    private void acceptQuote() {
        com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection = quotedSelection;
        hideQuoteOverlay();
        if (selection == null) {
            return;
        }
        selection.clearHighlight();
        quote(selection.selectedText());
    }

    /**
     * Die Frage mit den vorgemerkten Zitaten davor, je Zitat ein Markdown-Zitatblock ({@code > }), so steht es auch
     * in der eigenen Sprechblase und im Verlauf; ohne Zitate unverändert.
     */
    static String withQuotes(String text, List<String> passages) {
        if (passages.isEmpty()) {
            return text;
        }
        StringBuilder b = new StringBuilder();
        for (String passage : passages) {
            for (String line : passage.split("\\R", -1)) {
                b.append(line.isBlank() ? ">" : "> " + line).append('\n');
            }
            b.append('\n');
        }
        return b.append(text).toString();
    }

    /** Ein Bild, das schon beim Anhängen gelesen wird: mit welchem Vision-Modell, und sein Text. */
    record EarlyImage(String model, java.util.concurrent.CompletableFuture<String> text) {
    }

    /**
     * Startet das Lesen eines Bildanhangs sofort, ohne die Frage (die steht noch nicht fest); beim Senden wird nur noch
     * auf das Ergebnis gewartet. Ohne Vision-Modell oder für andere Dateien nichts.
     */
    private void startImageReading(Path file) {
        AppRuntime runtime = context.runtime();
        if (runtime == null || file.getFileName() == null
                || !com.aresstack.spaceharvester.app.chat.AttachmentImages.isImage(file.getFileName().toString())) {
            return;
        }
        String model = runtime.workspace().visionModel().orElse(null);
        if (model == null) {
            return;
        }
        earlyImages.put(file, new EarlyImage(model, readImageEarly(file, new AttachmentImageReading(model,
                runtime.workspace()::describeAttachmentImage), imageReaders())));
    }

    /**
     * Die Threads der Vorab-Lesung, höchstens „Parallele Dateien“ zugleich wie beim Senden: Ein Bild wird ganz geladen,
     * bevor es auf das Vision-Modell wartet, also dürfen nicht beliebig viele gleichzeitig im Speicher liegen. Ändert
     * sich die Einstellung, gilt sie für die nächsten Bilder; angefangene laufen zu Ende.
     */
    private java.util.concurrent.ExecutorService imageReaders() {
        int limit = Math.max(1, parallel("files", FILES_DEFAULT));
        if (imageReaders == null || imageReadersLimit != limit) {
            if (imageReaders != null) {
                imageReaders.shutdown();
            }
            imageReaders = java.util.concurrent.Executors.newFixedThreadPool(limit, work -> {
                Thread thread = new Thread(work, "chat-bild");
                thread.setDaemon(true);
                return thread;
            });
            imageReadersLimit = limit;
        }
        return imageReaders;
    }

    private java.util.concurrent.ExecutorService imageReaders;
    private int imageReadersLimit;

    /** Liest {@code file} auf {@code readers}; die Grenze des Vision-Modells ({@code ai.parallel}) gilt dort. */
    static java.util.concurrent.CompletableFuture<String> readImageEarly(Path file, AttachmentImageReading images,
                                                                        java.util.concurrent.Executor readers) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            try {
                return com.aresstack.spaceharvester.app.chat.AttachmentImages.read(file, null, images.model(),
                        images.reader());
            } catch (java.io.IOException unreadable) {
                throw new java.util.concurrent.CompletionException(unreadable);
            }
        }, readers);
    }

    /** Die schon gestarteten Bildlesungen für {@code images}; eine mit einem anderen Vision-Modell zählt nicht. */
    private java.util.Map<Path, java.util.concurrent.CompletableFuture<String>> earlyFor(
            AttachmentImageReading images) {
        java.util.Map<Path, java.util.concurrent.CompletableFuture<String>> early = new java.util.HashMap<>();
        if (images != null && images.model() != null) {
            earlyImages.forEach((file, read) -> {
                if (images.model().equals(read.model())) {
                    early.put(file, read.text());
                }
            });
        }
        return early;
    }

    /**
     * Senden (Enter): die Nachricht erscheint als Sprechblase, der Turn holt das Wissen aus dem Bestand und streamt die
     * Antwort in eine zweite Blase; der Knopf heißt solange „Stopp“. Dateien aus den Chips gehen als Text mit, wenn
     * sie lesbarer Text sind. Läuft schon ein Turn, bricht Senden ihn ab.
     */
    void send() {
        if (turn == null && dictationPhase != DictationPhase.IDLE) {
            sendAfterDictation = true;
            if (dictationPhase == DictationPhase.RECORDING) {
                toggleDictation();
            }
            status.setText(SEND_AFTER_DICTATION_STATUS);
            return;
        }
        if (turn != null) {
            if (listening != null) {
                listening.endApprovals();
            }
            turn.cancel();
            return;
        }
        if (readingAttachments) {
            return;
        }
        String typed = editor.getText().strip();
        if (typed.isEmpty() || !send.isEnabled()) {
            return;
        }
        String text = withQuotes(typed, quotes);
        sendingQuotes = true;
        if (attachments.isEmpty()) {
            sendAsked(text);
            sendingQuotes = false; // nicht gesendet (kein Modell …): die Zitate bleiben für den nächsten Versuch
            return;
        }
        // Anhänge einmal lesen, nicht auf dem EDT: ein Bild liest das Vision-Modell, das dauert. Ihr Text zählt zum
        // Platz der Anfrage.
        List<Path> files = List.copyOf(attachments);
        int room = roomBeside(text);
        AppRuntime runtime = context.runtime();
        AttachmentImageReading images = runtime == null ? null : new AttachmentImageReading(
                runtime.workspace().visionModel().orElse(null), runtime.workspace()::describeAttachmentImage);
        java.util.Map<Path, java.util.concurrent.CompletableFuture<String>> early = earlyFor(images);
        // Senden ging; bis die Anhänge gelesen sind, wartet die Frage sichtbar, ein zweites Senden gibt es nicht.
        readingAttachments = true;
        editor.setEditable(false);
        updateSend();
        send.setToolTipText(WAITING_FOR_ATTACHMENTS);
        status.setText(com.aresstack.spaceharvester.app.chat.AttachmentImages.anyImage(files) && images != null
                && images.model() != null ? READING_IMAGES_STATUS : READING_ATTACHMENTS_STATUS);
        // Die Frage gehört dem Chat, in dem sie gestellt wurde: Wechselt der Benutzer währenddessen den Chat (oder
        // löscht ihn), geht sie nicht in einen anderen.
        String startedChat = chat.id();
        Thread reading = new Thread(() -> {
            String asked = withAttachments(text, files, room, images, early);
            SwingUtilities.invokeLater(() -> {
                readingAttachments = false;
                editor.setEditable(true);
                send.setToolTipText("Senden (Enter)");
                updateSend();
                if (!startedChat.equals(chat.id())) {
                    sendingQuotes = false;
                    status.setText(CHAT_CHANGED_WHILE_READING);
                    return;
                }
                status.setText(" ");
                sendAsked(asked);
                sendingQuotes = false;
            });
        }, "chat-anhaenge");
        reading.setDaemon(true);
        reading.start();
    }

    static final String READING_ATTACHMENTS_STATUS = "Anhänge werden gelesen … die Frage geht danach von selbst hinaus";
    static final String READING_IMAGES_STATUS = "Bild wird vom Vision-Modell gelesen … die Frage geht danach von selbst "
            + "hinaus";
    static final String WAITING_FOR_ATTACHMENTS = "Wartet, bis die Anhänge gelesen sind";
    static final String CHAT_CHANGED_WHILE_READING = "Nicht gesendet: Der Chat wurde gewechselt, während die Anhänge "
            + "gelesen wurden";
    /** Bildangaben im Bild ohne Vision-Modell: wohin der Benutzer gehen muss. */
    static final String NO_VISION_MODEL = "(Bild: Kein Vision-Modell eingestellt – Einstellungen › KI-Dienst › Modelle "
            + "› „Vision-Modell“, etwa gemma)";

    /** Solange die Anhänge gelesen werden, sendet Enter nichts zweites. */
    private boolean readingAttachments;

    /** Vision-Modell und Leser für Bildanhänge; {@code model} ist {@code null} ohne Vision-Modell. */
    record AttachmentImageReading(String model, com.aresstack.spaceharvester.app.chat.AttachmentImages.Reader reader) {
    }

    /** Die Frage samt gelesener Anhänge geht hinaus (Perspektive oder Wissen). */
    private void sendAsked(String asked) {
        ToolScope custom = currentToolScope(asked);
        if (custom != null) {
            submit(asked, null, custom.area(), ChatTurn.Kind.QUESTION, custom);
            return;
        }
        submit(asked, resolved, areaText(selection, resolved == null ? null : resolved.counts()),
                ChatTurn.Kind.QUESTION);
    }

    /**
     * Die Werkzeuge der Perspektive für {@code question}, gelesen beim Senden: Die Ansicht in der Systemnachricht geht
     * so weit mit, wie sie neben Verlauf und Frage in die Anfrage passt ({@link ChatRoom}).
     */
    private ToolScope currentToolScope(String question) {
        if (toolScope == null) {
            return null;
        }
        return ChatRoom.within(roomBeside(question), toolScope::get);
    }

    /** Platz in Zeichen, der neben dem Verlauf des Chats und {@code question} in der Anfrage frei ist. */
    private int roomBeside(String question) {
        List<com.aresstack.spaceharvester.ai.AiChat.Message> asked = new ArrayList<>();
        for (ChatRecord.Message message : chat.messages()) {
            asked.add(com.aresstack.spaceharvester.ai.AiChat.Message.user(message.text() == null ? ""
                    : message.text()));
        }
        asked.add(com.aresstack.spaceharvester.ai.AiChat.Message.user(question));
        return ChatRoom.left(chatBudget(), asked, List.of(), 1);
    }

    /** Tokenbudget des Chat-Modells, ohne den Modellkatalog zu fragen (für den EDT). */
    private com.aresstack.spaceharvester.app.runtime.ModelBudget chatBudget() {
        return chatBudget(false);
    }

    /**
     * Tokenbudget des Chat-Modells ({@link com.aresstack.spaceharvester.app.runtime.ModelBudget#chat}): Kontextlänge
     * aus den Einstellungen, sonst aus dem Modellkatalog des Dienstes; mit {@code fetch} fragt der erste Aufruf den
     * Katalog (nur auf einem Hintergrund-Thread des Turns). {@code null}, wenn keine Kontextlänge bekannt ist.
     */
    private com.aresstack.spaceharvester.app.runtime.ModelBudget chatBudget(boolean fetch) {
        return chatBudget(fetch, null);
    }

    /**
     * Wie oben; mit {@code use} gelten Modell und Tokenlänge seiner Zeile (etwa „Zusammenfassung“), soweit dort
     * eingestellt, sonst die des Text-Modells.
     */
    private com.aresstack.spaceharvester.app.runtime.ModelBudget chatBudget(boolean fetch,
            com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse use) {
        AppSettings settings = context.settings();
        AppRuntime runtime = context.runtime();
        if (settings == null || settings.ai() == null) {
            return null;
        }
        String model = use != null && use.model() != null ? use.model() : settings.ai().chatModel();
        Integer maxTokens = use != null && use.maxTokens() != null ? use.maxTokens() : settings.ai().chatMaxTokens();
        Integer catalog = settings.ai().contextTokens() != null || runtime == null ? null
                : runtime.workspace().catalogContextTokens(model, fetch);
        return com.aresstack.spaceharvester.app.runtime.ModelBudget.chat(settings.ai().contextTokens(), catalog,
                maxTokens);
    }

    /** Die Zeile „Zusammenfassung“ (Modell, Tokenlänge, Nachdenken) für die Chips „Zusammenfassen“/„Was ist neu?“. */
    private com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse summaryUse() {
        AppSettings settings = context.settings();
        return settings == null || settings.ai() == null ? null : settings.ai().summary();
    }

    /**
     * Der gemeinsame Sendeweg für Eingabe und Schnellaktionen: {@code question} geht an das Modell und steht ebenso
     * in Sprechblase und Verlauf; {@code scope} sind die Seiten, aus denen das Wissen kommt, {@code area} nennt sie in
     * der Systemnachricht. Die Freigabe gilt immer dem links Gewählten (Angelo 13.11.2026).
     */
    private void submit(String question, PageSelection scope, String area, ChatTurn.Kind kind) {
        submit(question, scope, area, kind, null);
    }

    /** Wie oben; mit {@code custom} bekommt die Frage die Werkzeuge der Perspektive statt des Wissens (#1151). */
    private void submit(String question, PageSelection scope, String area, ChatTurn.Kind kind, ToolScope custom) {
        submit(question, scope, area, kind, custom, null);
    }

    /**
     * Wie oben; mit {@code use} antwortet das Modell einer eigenen Zeile unter KI-Dienst › Modelle (Angelo 27.11.2026:
     * der Chat nutzt die Spezialmodelle), etwa „Zusammenfassung“ für die Chips; was dort leer ist, kommt vom
     * Text-Modell und der Auswahl am Chat.
     */
    private void submit(String question, PageSelection scope, String area, ChatTurn.Kind kind, ToolScope custom,
                        com.aresstack.spaceharvester.ai.AiServiceSettings.ModelUse use) {
        if (turn != null || !send.isEnabled()) {
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            status.setText(NO_RUNTIME);
            return;
        }
        var port = use == null ? runtime.workspace().aiChat() : runtime.workspace().aiChat(use);
        String modelName = use != null && use.model() != null ? use.model()
                : context.settings() == null || context.settings().ai() == null ? null
                : context.settings().ai().chatModel();
        // Ist in der Zeile Nachdenken eingestellt, setzt es der Zugang selbst (auch „aus“ richtig je Dienst).
        String effort = use == null || use.thinking() == null ? effortValue() : null;
        if (port == null || modelName == null || modelName.isBlank()) {
            status.setText(NO_CHAT_MODEL);
            return;
        }
        // Nur die Seite statt der Auswahl samt Unterseiten ist ein eigener Bereich.
        String scopeKey = custom != null ? custom.scopeKey() : scopeKey(node, scope != resolved);
        addAreaMark(lastQuestion(chat), area, scopeKey);
        long now = System.currentTimeMillis();
        addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du", question, now);
        bottom.jump(); // die eigene Frage steht unten, die Antwort darunter rollt wieder mit
        if (home == null) {
            home = store.folder(stored());
        }
        chat = chat.plus(ChatRecord.Message.user(question, now, area, scopeKey), now);
        List<ChatRecord.Message> history = chat.messages().subList(0, chat.messages().size() - 1);
        store.save(home, chat);
        showChatName();
        editor.setText("");
        attachments.clear();
        hideQuoteOverlay();
        earlyImages.clear();
        chips.removeAll();
        if (sendingQuotes) {
            quotes.clear();
            quoteChips.clear();
        }
        sendingQuotes = false;
        quoteChips.values().forEach(chips::add);
        chips.setVisible(!quotes.isEmpty());
        attach.setToolTipText(ATTACH);
        composer.revalidate();
        composer.repaint();
        answering = addAnswer("…", now);
        ChatRecord target = chat;
        Path targetNode = home;
        TurnListener listener = new TurnListener(target, targetNode, answering, answeringSources);
        listening = listener;
        // Die gelbe Blase gleich mit dem Senden (Angelo 24.11.2026): Bereichssuche und interne Suche gleich, mit
        // Wissenszeile; die Antwortblase wartet unsichtbar dahinter, bis Text kommt.
        boolean withKnowledge = custom == null && (scope != null || appSearchOn);
        // Direkt hinter „… davon nicht geladen“ in derselben Zeile (Angelo 24.11.2026): Erneut laden (Kreispfeil) und
        // die nicht geladene Seite öffnen (Fadenkreuz), als Icons ohne Kasten, erst beim Darüberfahren als Knopf.
        String anchor = withKnowledge && load.isVisible() && load.isEnabled() ? notLoadedAnchor(knowledge.getText())
                : null;
        List<JComponent> inline = anchor == null ? List.of() : missingActions(scope);
        KnowledgeCard thinking = new KnowledgeCard(withKnowledge, (title, body) -> {
            com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel bubble = addActivity(title, body);
            bubble.setInlineActions(anchor, inline);
            return bubble;
        });
        if (withKnowledge) {
            thinking.knowledge(knowledge.getText());
        }
        listener.card = thinking;
        thinking.open();
        turn = new ChatTurn(runtime.workspace(), port, modelName, effort, history, question, scope, area, kind,
                listener, SwingUtilities::invokeLater).budget(() -> chatBudget(true, use))
                // Spezialmodelle (Angelo 27.11.2026): Schaubilder über die Zeilen „Bildgenerierung“.
                .diagrams(runtime.workspace()::composeDiagram)
                // Aussagen-, Vereinfachungs-, Prüf- und Podcast-Modell für Seiten des Bereichs.
                .pageModels(new com.aresstack.spaceharvester.app.chat.ChatTools.PageModels(
                        () -> context.settings() == null
                                ? com.aresstack.spaceharvester.application.configuration.SimplificationSettings.DEFAULT
                                : context.settings().simplification(),
                        () -> context.settings() == null
                                ? com.aresstack.spaceharvester.application.configuration.CheckSettings.DEFAULT
                                : context.settings().check()));
        if (custom != null) {
            // #1151: dieselbe Unterhaltung, aber die Werkzeuge der Perspektive (Command Deck: Maskenassistent).
            custom.tools().modelSelected(modelName);
            var perspectiveTools = custom.tools();
            if (appSearchOn && (custom.scopeKey().startsWith("command-deck/ftp")
                    || custom.scopeKey().startsWith("command-deck/ndv"))) {
                var programs = new com.aresstack.spaceharvester.app.chat.StoredProgramChatTools(runtime.workspace(),
                        () -> context.runtime() == runtime
                                ? appSearchSources(context.settings(), runtime, true) : Set.of(),
                        () -> context.runtime() != runtime,
                        (spaceKey, key) -> runtime.workspace().releasePageForChat(spaceKey, key));
                perspectiveTools = programs.attach(perspectiveTools, () -> context.runtime() == runtime);
            }
            turn.withToolset(perspectiveTools, custom.system());
        } else {
            // ADR 0033: Was offen ist, sieht das Modell; den Rest des Bereichs holt es sich per Werkzeug.
            Selection open = selection;
            turn.knowledgeByTools(open == null || open.pageId() == null ? null
                    : new com.aresstack.spaceharvester.application.snapshot.PageKey(open.sourceId(),
                            open.pageId()));
            turn.releasing(releases(runtime, open));
            // Die Wurzeln der Auswahl wie im Baum: Was der Katalog davon nicht kennt (etwa ein SharePoint-Ordner),
            // erschließen die Werkzeuge über die Navigation (Angelo 26.11.2026).
            turn.area(chatRoots(runtime, open));
            // ADR 0034 §5: Ein Favoriten-Ordner nennt seine Unterordner und Favoriten in seiten_auflisten.
            if (open instanceof Selection.Collection collection) {
                turn.collection(collectionNodes(context.favorites(), collection));
            }
            // R2/C1: Der Chat folgt der Suchmethode „Nachbarseiten“ der Suchoptionen, wie sie beim Senden steht.
            turn.searchingNeighbors(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().neighbors());
            // Interne Suche: die globale Suche über die freigegebenen Wissensquellen, gelesen beim Aufruf.
            if (appSearchOn) {
                turn.appSearch(() -> context.runtime() == runtime
                                ? appSearchSources(context.settings(), runtime) : Set.of(),
                        () -> context.runtime() == runtime
                                ? appSearchSources(context.settings(), runtime, true) : Set.of());
            }
            // K5a: ebenso der Suchmethode „Synonyme der Fachbegriffe“.
            turn.searchingGlossarySynonyms(
                    com.aresstack.spaceharvester.app.runtime.SearchMethods.user().glossarySynonyms());
            // Angelo 24.11.2026: In der Suche, und ohne Auswahl überhaupt, recherchiert der Chat als Suchagent, bis er
            // fertig ist; anderswo bleibt er, wie er ist.
            if (searchFront || open == null) {
                turn.searchAgent();
            }
            turn.parallelAgents(parallel("agents", AGENTS_DEFAULT));
        }
        turn.quietRounds(parallel("quiet", QUIET_ROUNDS_DEFAULT));
        // Websuche (Composer-Knopf): die Browser-Werkzeuge des Chats dazu, mit Karte „Browserrecherche“.
        WebSearchCard card = new WebSearchCard(body -> addActivity(WebSearchCard.TITLE, body));
        ChatTurn.WebSearch web = webSearchFor(chat.id(), card, listener);
        if (web != null) {
            turn.webSearch(web);
            // Angelo 24.11.2026: Webtreffer wertet je Seite ein eigener Agent aus, so viele gleichzeitig wie Tabs.
            context.webBrowser().pageEvaluator(chat.id(), turn::evaluatePage, parallel("tabs", WEB_TABS_DEFAULT));
            listener.webCard = card;
            listener.browserTools = web.tools();
        }
        listener.turn = turn;
        send.setText(STOP);
        send.setIcon(ComposerIcons.stop());
        updateHeaderActions();
        send.setToolTipText("Antwort abbrechen");
        turn.inView(view);
        turn.start();
    }

    /** Die Wurzeln der Auswahl für den Chat; leer ohne Auswahl oder wenn sie sich nicht bestimmen lassen. */
    static List<com.aresstack.spaceharvester.application.holdings.Selection.Root> chatRoots(AppRuntime runtime,
                                                                                         Selection open) {
        if (runtime == null || open == null) {
            return List.of();
        }
        try {
            return open.roots(runtime);
        } catch (RuntimeException gone) {
            return List.of();
        }
    }

    /** {@code low}, {@code medium}, {@code high} aus der Wahl im Composer. */
    private String effortValue() {
        return switch (EFFORTS.indexOf(effortLevel)) {
            case 0 -> "low";
            case 2 -> "high";
            default -> "medium";
        };
    }

    /**
     * Hängt lesbare Textdateien und Dokumente (PDF, Word …) aus den Chips an; Binäres wird nur genannt. Die Dateien
     * werden gleichzeitig gelesen, höchstens „Parallele Dateien“ auf einmal (Angelo 24.11.2026); die Reihenfolge der
     * Chips bleibt.
     */
    static String withAttachments(String text, List<Path> files, int room, AttachmentImageReading images) {
        return withAttachments(text, files, room, images, java.util.Map.of());
    }

    /** Wie oben; Bilder aus {@code early} werden schon gelesen, auf sie wird nur gewartet. */
    static String withAttachments(String text, List<Path> files, int room, AttachmentImageReading images,
                                  java.util.Map<Path, java.util.concurrent.CompletableFuture<String>> early) {
        if (files.isEmpty()) {
            return text;
        }
        String[] contents = new String[files.size()];
        // Die Anhänge teilen sich, was neben Verlauf und Frage in die Anfrage passt.
        int each = room == ChatRoom.UNLIMITED ? room : room / files.size();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(
                Math.min(parallel("files", FILES_DEFAULT), files.size()), work -> {
                    Thread thread = new Thread(work, "chat-anhang");
                    thread.setDaemon(true);
                    return thread;
                });
        try {
            List<java.util.concurrent.Future<?>> running = new ArrayList<>();
            for (int i = 0; i < files.size(); i++) {
                int index = i;
                running.add(pool.submit(() -> contents[index] = attachmentText(files.get(index), each, text, images,
                        early.get(files.get(index)))));
            }
            for (java.util.concurrent.Future<?> future : running) {
                try {
                    future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (java.util.concurrent.ExecutionException e) {
                    // Dann fehlt nur der Text dieser Datei; sie gilt unten als nicht lesbar.
                }
            }
        } finally {
            pool.shutdownNow();
        }
        StringBuilder b = new StringBuilder(text);
        for (int i = 0; i < files.size(); i++) {
            b.append("\n\n--- Anhang: ").append(files.get(i).getFileName()).append(" ---\n")
                    .append(contents[i] == null ? UNREADABLE : contents[i]);
        }
        return b.toString();
    }

    private static final String UNREADABLE = "(nicht als Text lesbar)";

    /**
     * Ein Bild liest das Vision-Modell ab (Angelo 27.11.2026); scheitert es, steht der Grund da, damit das Chat-Modell
     * ihn nennen kann statt zu raten.
     */
    private static String imageText(Path file, int room, String question, AttachmentImageReading images,
                                    java.util.concurrent.CompletableFuture<String> early) {
        if (images == null || images.model() == null) {
            return NO_VISION_MODEL;
        }
        try {
            String read;
            if (early != null) {
                try {
                    read = early.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    if (e.getCause() instanceof java.io.IOException unreadable) {
                        throw unreadable;
                    }
                    throw e.getCause() instanceof RuntimeException failed ? failed
                            : new IllegalStateException(e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("unterbrochen", e);
                }
            } else {
                read = com.aresstack.spaceharvester.app.chat.AttachmentImages.read(file, question, images.model(),
                        images.reader());
            }
            return ChatKnowledge.excerpt(read, room);
        } catch (java.io.IOException unreadable) {
            return "(Bild nicht lesbar: " + unreadable.getMessage() + ")";
        } catch (RuntimeException failed) {
            return "(Bild: Das Vision-Modell " + images.model() + " hat nicht geantwortet: "
                    + (failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage()) + ")";
        }
    }

    /**
     * Der Text einer angehängten Datei, so weit er in die Anfrage passt ({@code room}, {@link ChatRoom}; früher fest
     * 12.000 Zeichen); was nicht passt, steht als gekürzt da.
     */
    private static String attachmentText(Path file, int room, String question, AttachmentImageReading images,
                                         java.util.concurrent.CompletableFuture<String> early) {
        try {
            // Dokumente (PDF, Word, Excel …) wie die Vorschau im Command Deck über den Tika-Adapter des Core.
            String name = file.getFileName() == null ? "" : file.getFileName().toString();
            if (com.aresstack.spaceharvester.app.chat.AttachmentImages.isImage(name)) {
                return imageText(file, room, question, images, early);
            }
            String content = com.aresstack.spaceharvester.app.deck.DocumentPreviewStore.isDocument(name)
                    ? new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore().load(file.toString()).text()
                    : Files.readString(file, StandardCharsets.UTF_8);
            String shown = ChatKnowledge.excerpt(content, room);
            return shown.length() >= content.strip().length() ? shown
                    : shown + "\n(gekürzt: Die Anfrage hat für diesen Anhang nur Platz für " + room + " von "
                    + content.strip().length() + " Zeichen)";
        } catch (java.io.IOException | RuntimeException
                 | com.aresstack.spaceharvester.deck.editor.DocumentException unreadable) {
            return UNREADABLE;
        }
    }

    /** Nimmt die Ereignisse eines Turns entgegen; nach dem Ende speichert er den Chat, zu dem der Turn gehört. */
    private final class TurnListener implements ChatTurn.Listener {

        private final ChatRecord started;
        private final Path startedNode;
        private final AssistantMarkdownMessage bubble;
        private final SourceLinks links;
        private final StringBuilder text = new StringBuilder();
        /** Die nummerierten Quellen dieser Antwort, wie zuletzt gemeldet; gehen mit der Antwort in den Verlauf. */
        private List<ChatRecord.Source> sources = List.of();
        private final java.awt.event.ComponentListener follow;
        ChatTurn turn;
        boolean detached;
        /** Der Chat wurde gelöscht: Die Antwort wird nicht mehr gespeichert. */
        volatile boolean discarded;
        /** Die Karte „Browserrecherche“ dieser Antwort, {@code null} ohne Websuche. */
        WebSearchCard webCard;
        /** Die gelbe Gedankenblase dieser Antwort (Wissen, Schritte, Quellen, Balken); {@code null} nur in Tests. */
        KnowledgeCard card;
        /** Laufende Werkzeugaufrufe außer dem Browser: Kennung und Text, für die Schritte der Gedankenblase. */
        private final java.util.Map<String, String> steps = new java.util.HashMap<>();
        /** Die Browser-Werkzeuge dieser Antwort, {@code null} ohne Websuche. */
        com.aresstack.spaceharvester.app.chat.browser.BrowserChatTools browserTools;
        /** Die Zeile der Antwortblase; wartet unsichtbar hinter den Wolken, bis Text kommt (EAC). */
        private final JComponent row;
        private boolean waiting;
        /** Die gelbe Blase platzt gerade; die Antwort erscheint erst danach an ihrer Stelle. */
        private boolean bursting;
        /** Die laufenden Wolken der Werkzeugaufrufe; alle Wolken verschwinden mit dem Ende der Antwort (EAC). */
        private final java.util.Map<String, com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel> clouds =
                new java.util.HashMap<>();
        private final List<com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel> shownClouds =
                new ArrayList<>();
        /** Die Wolke „Browser wird gestartet“, bis das erste Browser-Werkzeug fertig ist. */
        private String browserStart;
        private boolean browserSeen;
        /** Offene Rückfragen der Websuche dieser Antwort (nur EDT). */
        private final List<java.util.function.Consumer<
                com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome>> approvals = new ArrayList<>();
        /** Nach Stopp oder Verlassen des Chats: keine neuen Rückfragen mehr. */
        private boolean approvalsEnded;

        /**
         * Die Rückfrage-Fläche dieser Antwort: Fragen erscheinen nur im Verlauf ihres Chats. Nach Stopp oder Wechsel
         * des Chats gibt es keine Fläche mehr (das Werkzeug läuft dann nicht), offene Fragen enden als abgebrochen.
         */
        com.aresstack.spaceharvester.app.chat.browser.ChatApprovalPrompt.Handle askApproval(String title,
                String description, boolean offersAlways,
                java.util.function.Consumer<com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome> answer) {
            if (approvalsEnded || detached) {
                throw new IllegalStateException("Der Chat der Rückfrage ist nicht mehr offen.");
            }
            approvals.add(answer);
            return showApproval(title, description, offersAlways, answer);
        }

        /** Beendet alle offenen Rückfragen als abgebrochen; neue gibt es nicht mehr. Nur auf dem EDT. */
        void endApprovals() {
            approvalsEnded = true;
            List<java.util.function.Consumer<com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome>>
                    open = new ArrayList<>(approvals);
            approvals.clear();
            open.forEach(answer -> answer.accept(
                    com.aresstack.enterpriseai.astu.approval.ApprovalDecision.Outcome.CANCELLED));
        }

        TurnListener(ChatRecord started, Path startedNode, AssistantMarkdownMessage bubble, SourceLinks links) {
            this.started = started;
            this.startedNode = startedNode;
            this.bubble = bubble;
            this.links = links;
            this.row = answeringRow;
            // Die Markdown-Ansicht rendert gedrosselt: erst wenn die Blase dadurch wächst, nach unten rollen (Codex-
            // Befund zu #966), auch nach dem letzten Delta und beim Abschluss.
            follow = new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent event) {
                    if (!detached && ChatPanel.this.answering == TurnListener.this.bubble) {
                        scrollDown();
                    }
                }
            };
            bubble.addComponentListener(follow);
        }

        /** Ersetzt den ganzen Text der Antwortblase (Platzhalter, Fehler, Abschluss) und beendet das Streaming. */
        private void show(String markdown) {
            if (!detached) {
                clearClouds();
            }
            reveal();
            MarkdownMessageView view = bubble.view();
            if (view.isStreaming()) {
                view.finishStreaming();
            }
            view.setMarkdown(markdown);
            bubble.revalidate();
            bubble.repaint();
        }

        /**
         * Wie {@code ChatShellModel.startActivity} im EAC: Eine noch leere Antwort rückt hinter die neue Wolke und
         * bleibt unsichtbar, bis ihr erster Text kommt; der „…“-Platzhalter steht so nie über der Recherche.
         */
        void behindActivity() {
            if (detached || !text.isEmpty() || row == null) {
                return;
            }
            waiting = true;
            row.setVisible(false);
            bubble.setSources(null);
            links.standalone(true);
            transcript.add(links);
            links.setVisible(false);
        }

        /**
         * Die blauen Quellen stehen während der Recherche unter der gelben Blase (Angelo 24.11.2026), also hinter jeder
         * neuen Blase am Ende des Verlaufs.
         */
        void linksBelow() {
            if (detached || !waiting || links.getParent() != transcript) {
                return;
            }
            transcript.remove(links);
            transcript.add(links);
        }

        /**
         * Wie im EAC ({@code ChatTranscriptPanel.applyActivity}/{@code settleActivity}): Die gelbe Blase platzt, und
         * erst danach verschwindet ihre Zeile und die Antwort erscheint bündig an ihrer Stelle, die blauen Quellen
         * darunter. Während des Platzens sind die Quellen kurz weg (Angelo 24.11.2026), sonst entstünde eine Lücke.
         */
        private void burst(boolean cancelled) {
            if (card == null || card.finished()) {
                return;
            }
            if (detached) {
                return;
            }
            var thought = card.bubble();
            bursting = true;
            links.setVisible(false);
            card.finish(cancelled, () -> {
                bursting = false;
                if (thought != null && thought.getParent() != null && thought.getParent().getParent() == transcript) {
                    transcript.remove(thought.getParent());
                }
                reveal();
                transcript.revalidate();
                transcript.repaint();
            });
        }

        /** Der erste Text (oder das Ende): die Antwort erscheint hinter den Wolken. */
        private void reveal() {
            if (!waiting || detached || bursting) {
                return;
            }
            waiting = false;
            transcript.remove(row);
            transcript.remove(links);
            transcript.add(row);
            links.standalone(false);
            bubble.setSources(links);
            row.setVisible(true);
            links.setVisible(links.hasEntries());
            transcript.revalidate();
            transcript.repaint();
            scrollDown();
        }

        /**
         * Eine Wolke je Werkzeugaufruf wie im EAC. Der erste Browser-Aufruf startet Firefox: seine Wolke heißt
         * „Browser wird gestartet“ und verschwindet, sobald er fertig ist; alle weiteren Browser-Schritte zeigt die
         * Karte „Browserrecherche“.
         */
        @Override
        public void toolStarted(String key, String name, String what) {
            if (detached) {
                return;
            }
            String title = what;
            if (browserTools != null && browserTools.handles(name)) {
                if (browserSeen) {
                    return;
                }
                browserSeen = true;
                browserStart = key;
                title = WebSearchCard.STARTING;
            } else if (card != null && !card.finished()) {
                // Suchen und Lesen im Wissen zeigt die eine Gedankenblase als Schritte, keine eigenen Wolken.
                steps.put(key, what);
                card.step(what);
                return;
            }
            var cloud = addActivity(title, "");
            clouds.put(key, cloud);
            shownClouds.add(cloud);
        }

        @Override
        public void toolFinished(String key) {
            String step = steps.remove(key);
            if (step != null && card != null && !detached) {
                card.stepDone(step);
            }
            var cloud = clouds.remove(key);
            if (cloud == null || detached) {
                return;
            }
            if (key.equals(browserStart)) {
                removeCloud(cloud);
                return;
            }
            if (cloud.getVisualState()
                    == com.aresstack.enterpriseai.ui.comic.bubble.AnimatedThoughtBubblePanel.VisualState.RUNNING) {
                cloud.completeSuccessfully("", null);
            }
        }

        private void removeCloud(com.aresstack.enterpriseai.ui.comic.bubble.AgentActivityBubblePanel cloud) {
            shownClouds.remove(cloud);
            if (cloud.getParent() != null) {
                transcript.remove(cloud.getParent());
                transcript.revalidate();
                transcript.repaint();
            }
        }

        /** Mit dem Ende der Antwort verschwinden die Wolken der Werkzeugaufrufe (EAC: transiente Wolken). */
        private void clearClouds() {
            clouds.clear();
            for (var cloud : List.copyOf(shownClouds)) {
                removeCloud(cloud);
            }
        }

        @Override
        public void loading() {
            if (text.isEmpty()) {
                bubble.view().setMarkdown(MODEL_LOADING);
            }
            if (!detached && card != null) {
                card.step(MODEL_LOADING);
            }
        }

        @Override
        public void released(int count) {
            if (!detached && card != null) {
                card.released(count);
            }
        }

        @Override
        public void releaseFailed(RuntimeException error) {
            if (!detached) {
                feedback.problem(error);
            }
        }

        @Override
        public void using(String what) {
            if (!detached && card != null) {
                card.step(what);
            }
        }

        @Override
        public void context(ChatKnowledge.Context context) {
            sources = context.accepted().stream().map(ChatRecord.Source::of).toList();
            // Die Quellen stehen schon während der Recherche unter der Blase und wachsen mit jeder neuen; während die
            // gelbe Blase platzt, bleiben sie weg und kommen mit der Antwort wieder.
            links.show(sources);
            if (bursting) {
                links.setVisible(false);
            }
            if (!detached && card != null) {
                card.sources(sources.stream().map(ChatRecord.Source::title).toList());
            }
        }

        @Override
        public void progress(int done, int total) {
            if (!detached && card != null) {
                card.progress(done, total);
            }
        }

        @Override
        public void delta(String delta) {
            // Die Antwort schreibt: Die Gedankenblase platzt mit ihrer Bilanz, danach erscheint die Antwort (wie im EAC).
            burst(false);
            reveal();
            MarkdownMessageView view = bubble.view();
            if (text.isEmpty()) {
                // Der Platzhalter „…“ weicht dem ersten Delta; danach wird nur angehängt (gedrosselt gerendert).
                view.setMarkdown("");
                view.startStreaming();
            }
            view.appendMarkdownDelta(delta);
            text.append(delta);
            if (!detached) {
                scrollDown();
            }
        }

        @Override
        public void replaced(String now) {
            // Ein Werkzeugaufruf als Text fällt aus der Blase (ChatTurn, TextToolCalls); weitere Deltas hängen sich an.
            text.setLength(0);
            text.append(now);
            MarkdownMessageView view = bubble.view();
            view.setMarkdown(now);
            if (!now.isEmpty()) {
                view.startStreaming();
                view.appendMarkdownDelta(now);
            }
        }

        @Override
        public void completed(String full) {
            finishWebCard(false);
            show(text.isEmpty() ? full : text.toString());
            if (!detached) {
                scrollDown();
            }
            boolean shown = !detached;
            finish(ChatRecord.Message.assistant(full, false, System.currentTimeMillis(), sources), " ");
            if (shown) {
                readAloud.answered(bubble); // bei aktivem Vorlesen gleich vorlesen (wie im EAC)
                answerObserver.run();
            }
        }

        @Override
        public void failed(String message) {
            finishWebCard(false);
            show(message);
            bubble.markFailed(Comic.RED);
            // Eine gescheiterte Antwort wird nicht gespeichert (wie im EAC); die Frage bleibt im Chat.
            boolean shown = !detached;
            finish(null, message.lines().findFirst().orElse(" "));
            if (shown) {
                answerObserver.run();
            }
        }

        @Override
        public void cancelled(String partial) {
            finishWebCard(true);
            show(partial.isEmpty() ? CANCELLED : partial + "\n\n" + CANCELLED);
            if (!partial.isEmpty() && !detached) {
                readAloud.answer(bubble);
            }
            finish(partial.isEmpty() ? null
                    : ChatRecord.Message.assistant(partial, true, System.currentTimeMillis(), sources), CANCELLED);
        }

        private void finishWebCard(boolean cancelled) {
            if (webCard != null) {
                webCard.finish(null, cancelled);
            }
            burst(cancelled);
        }

        private void finish(ChatRecord.Message answer, String note) {
            // Nach dem letzten Layout-Durchgang den Mitroll-Hörer lösen, damit die Blase den Zug nicht festhält
            // (Codex-Befund zu #967).
            SwingUtilities.invokeLater(() -> bubble.removeComponentListener(follow));
            ChatRecord next = started;
            if (discarded) {
                return;
            }
            ChatRecord latest = store.load(startedNode, started.id());
            next = latest == null ? started : latest;
            if (answer != null) {
                next = next.plus(answer, answer.at());
                store.save(startedNode, next);
                answerActions(bubble, next, answer);
            }
            if (detached) {
                return;
            }
            chat = next;
            ChatPanel.this.turn = null;
            listening = null;
            answering = null;
            status.setText(note);
            send.setText(SEND);
            send.setIcon(ComposerIcons.send());
            updateSend();
            updateHeaderActions();
            send.setToolTipText("Senden (Enter)");
            showChatName();
        }
    }

    /**
     * Die Ausnahme der KI-Freigabe (ADR 0028, Angelo 13.11.2026; mit Werkzeugen ADR 0033): Der Turn trägt ein, was an
     * das Modell geht, auch ohne Haken an der Quelle; die Seiten stehen danach unter Einstellungen › Sicherheit. Die
     * Statuszeile nennt, wie viele neu dazukamen.
     */
    private static ChatTurn.Releases releases(AppRuntime runtime, Selection current) {
        return new ChatTurn.Releases() {
            @Override
            public int page(String spaceKey, com.aresstack.spaceharvester.application.snapshot.PageKey key) {
                return runtime.workspace().releasePageForChat(spaceKey, key).size();
            }

        };
    }

    /**
     * Die Ordner und Favoriten einer Sammlung für {@code seiten_auflisten} (ADR 0034 §5), gelesen beim Aufruf: der
     * gewählte Ordner und, mit „mit Unterseiten“, jeder Ordner darunter; je direkter Favorit seine Wurzel, soweit sie in
     * der aufgelösten Sammlung steht. Ein Ordner außerhalb oder ein inzwischen gelöschter gehört nicht zum Bereich.
     */
    static com.aresstack.spaceharvester.app.chat.CollectionNodes collectionNodes(
            com.aresstack.spaceharvester.app.ui.Favorites favorites, Selection.Collection collection) {
        return folderId -> {
            var top = favorites.folder(collection.folderId());
            var folder = folderId == null ? top
                    : collection.withSubfolders() ? top.flatMap(found -> found.subfolder(folderId))
                    : java.util.Optional.<com.aresstack.spaceharvester.app.ui.Favorites.Folder>empty();
            if (folder.isEmpty()) {
                return null;
            }
            List<com.aresstack.spaceharvester.app.chat.CollectionNodes.Folder> folders = new ArrayList<>();
            List<com.aresstack.spaceharvester.application.holdings.Selection.Root> roots = new ArrayList<>();
            for (var entry : folder.get().children()) {
                switch (entry) {
                    case com.aresstack.spaceharvester.app.ui.Favorites.Folder child -> {
                        if (collection.withSubfolders()) {
                            folders.add(new com.aresstack.spaceharvester.app.chat.CollectionNodes.Folder(child.id(),
                                    child.name()));
                        }
                    }
                    case com.aresstack.spaceharvester.app.ui.Favorites.Bookmark bookmark -> {
                        var root = bookmark.favorite().root();
                        if (collection.roots().contains(root)) {
                            roots.add(root);
                        }
                    }
                }
            }
            return new com.aresstack.spaceharvester.app.chat.CollectionNodes.Content(folders, roots);
        };
    }

    /**
     * Meldet, sobald eine Antwort des angezeigten Chats fertig oder gescheitert ist (Observer, kein Polling). Ein
     * Abbruch meldet nichts: den löst der Benutzer selbst aus.
     */
    public void onAnswer(Runnable observer) {
        answerObserver = observer == null ? () -> { } : observer;
    }

    /** Löst die Ansicht vom laufenden Turn: er schreibt seinen Chat zu Ende, zeigt hier aber nichts mehr. */
    private void detachTurn() {
        if (listening != null) {
            listening.endApprovals();
            listening.detached = true;
            listening = null;
        }
        send.setText(SEND);
        send.setIcon(ComposerIcons.send());
        send.setToolTipText("Senden (Enter)");
        status.setText(" ");
        answering = null;
        turn = null;
    }

    /**
     * Eine Sprechblase wie im EAC ({@code ChatTranscriptPanel.createRow}): Datum und Uhrzeit oben rechts, links davon
     * QR- und Kopierknopf.
     */
    private SpeechBubblePanel addBubble(BubbleSide side, Color background, Color foreground, String header,
                                        String text, long at) {
        SpeechBubblePanel bubble = new SpeechBubblePanel(side, background, foreground, header, text);
        bubble.setMaximumBubbleWidth(640);
        bubble.setHeaderAccessory(copyAction(foreground, () -> text,
                com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.HEADER_HEIGHT));
        bubble.setHeaderTrailingAccessory(userMessageActions(foreground, header, () -> text, at));
        BubbleMessageRow row = new BubbleMessageRow(bubble, side);
        row.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return bubble;
    }

    /** Die Zeile der Antwortblase, die gerade geschrieben wird (für das Warten hinter den Wolken). */
    private JComponent answeringRow;

    /** Add a full-width assistant message using the existing Markdown renderer. */
    private AssistantMarkdownMessage addAnswer(String markdown, long at) {
        if (mermaid == null) {
            mermaid = CachingMermaidImageRenderer.forChat();
        }
        MarkdownTheme theme = MarkdownTheme.forSurface(transcriptScroll.getViewport().getBackground());
        MarkdownMessageView view = new MarkdownMessageView(theme, linkOpener, mermaid);
        view.setMarkdown(markdown);
        view.setQuoteSelectionListener(new com.aresstack.spaceharvester.app.ui.markdown.QuoteSelectionListener() {
            @Override
            public void selectionMade(com.aresstack.spaceharvester.app.ui.markdown.QuoteSelection selection) {
                showQuoteOverlay(selection);
            }

            @Override
            public void selectionCleared() {
                hideQuoteOverlay();
            }
        });
        AssistantMarkdownMessage bubble = new AssistantMarkdownMessage(view, theme.getMutedForeground());
        bubble.setTimestamp(at);
        // Read the current Markdown when an action is invoked, including a streamed response.
        bubble.setCopyAction(copyAction(theme.getMutedForeground(), view::getMarkdown,
                com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.SIZE));
        bubble.setQrAction(qrAction(theme.getMutedForeground(), ASSISTANT, view::getMarkdown,
                com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.SIZE));
        emptyNote.setVisible(false);
        transcript.add(bubble);
        answeringRow = bubble;
        answeringSources = new SourceLinks(this::openSource);
        answeringSources.setAlignmentX(LEFT_ALIGNMENT);
        bubble.setSources(answeringSources);
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return bubble;
    }

    /**
     * Uhrzeit und QR-Knopf rechts in der Kopfzeile einer Frage. Der Kopierknopf steht nicht hier, sondern direkt
     * hinter dem Namen (Angelo, 26.11.2026), damit er nie neben dem QR-Knopf liegt; bei der Antwort steht er dafür
     * abgesetzt links am Textanfang.
     */
    private JComponent userMessageActions(Color foreground, String name, java.util.function.Supplier<String> text, long at) {
        JPanel controls = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 3, 0));
        controls.setOpaque(false);
        com.aresstack.spaceharvester.app.ui.markdown.MessageTimestamp timestamp =
                new com.aresstack.spaceharvester.app.ui.markdown.MessageTimestamp(messageInk(foreground));
        timestamp.setTime(at);
        controls.add(timestamp);
        controls.add(qrAction(foreground, name, text,
                com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.HEADER_HEIGHT));
        return controls;
    }

    /**
     * Der Kopierknopf einer Nachricht: das Kopieren-Symbol der App (zwei Blätter), flach und in der Tinte der Zeile.
     *
     * @param height Höhe des Knopfs; die Kopfzeile einer Blase lässt nichts Höheres als ihre Beschriftung zu
     */
    private com.aresstack.spaceharvester.app.ui.controls.CopyButton copyAction(Color foreground,
            java.util.function.Supplier<String> text, int height) {
        com.aresstack.spaceharvester.app.ui.controls.CopyButton copy =
                new com.aresstack.spaceharvester.app.ui.controls.CopyButton(text, value -> clipboard.accept(value))
                        .flat(messageInk(foreground), height);
        copy.setName("transcript.copyMessage");
        return copy;
    }

    private MessageQrButton qrAction(Color foreground, String name, java.util.function.Supplier<String> text,
            int height) {
        MessageQrButton qr = new MessageQrButton(messageInk(foreground), () -> qrCodes.accept(name, text.get()));
        // Derselbe flache Stil wie Kopieren und Export daneben (Angelos Proxy-Programm).
        com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.style(qr, height);
        return qr;
    }

    private static Color messageInk(Color foreground) {
        return new Color(foreground.getRed(), foreground.getGreen(), foreground.getBlue(), 200);
    }

    private java.util.function.BiConsumer<String, String> qrCodes =
            (name, text) -> com.aresstack.spaceharvester.app.ui.QrCodesView.openText(this, name, text);

    void setQrCodes(java.util.function.BiConsumer<String, String> qrCodes) {
        this.qrCodes = qrCodes;
    }

    /** Wohin der Kopierknopf schreibt; für Tests ohne Systemzwischenablage austauschbar. */
    private java.util.function.Consumer<String> clipboard = value -> java.awt.Toolkit.getDefaultToolkit()
            .getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(value), null);

    void setClipboard(java.util.function.Consumer<String> clipboard) {
        this.clipboard = clipboard;
    }

    public List<JMenuItem> exportMenus() {
        ChatRecord snapshot = chat;
        JMenuItem id = new JMenuItem("Chat-ID kopieren");
        id.addActionListener(e -> {
            clipboard.accept(snapshot.id());
            status.setText("Chat-ID kopiert");
        });
        return List.of(exportMenu("Exportieren", snapshot, null,
                        com.aresstack.spaceharvester.app.chat.ChatExport.Scope.CHAT), id);
    }

    /** Untereintrag des Exports: nur die Quellen, als Markdown (Angelo, 26.11.2026: kein eigener Knopf, ein Format). */
    static final String SOURCES_ONLY = "Nur Quellen exportieren";

    /**
     * Die Formate für Chat oder Antwort und darunter, abgesetzt, „Nur Quellen exportieren“ (Markdown). Der Eintrag
     * ist aus, wenn es keine Quellen gibt.
     */
    private javax.swing.JMenu exportMenu(String label, ChatRecord snapshot, ChatRecord.Message answer,
            com.aresstack.spaceharvester.app.chat.ChatExport.Scope scope) {
        javax.swing.JMenu menu = new javax.swing.JMenu(label);
        for (com.aresstack.spaceharvester.app.chat.ChatExport.Format format :
                com.aresstack.spaceharvester.app.chat.ChatExport.Format.values()) {
            JMenuItem item = new JMenuItem(format.label);
            item.addActionListener(e -> export(snapshot, answer, scope, format));
            menu.add(item);
        }
        menu.addSeparator();
        JMenuItem sources = new JMenuItem(SOURCES_ONLY);
        sources.setEnabled(!com.aresstack.spaceharvester.app.chat.ChatExport.sources(answer == null
                ? snapshot.messages() : List.of(answer)).isEmpty());
        sources.addActionListener(e -> export(snapshot, answer,
                com.aresstack.spaceharvester.app.chat.ChatExport.Scope.SOURCES,
                com.aresstack.spaceharvester.app.chat.ChatExport.Format.MARKDOWN));
        menu.add(sources);
        menu.setEnabled(!snapshot.isEmpty());
        return menu;
    }

    private void answerActions(AssistantMarkdownMessage message, ChatRecord snapshot, ChatRecord.Message answer) {
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 3, 0));
        buttons.setOpaque(false);
        addExportButton(buttons, Glyph.EXPORT, "Antwort exportieren", snapshot, answer,
                com.aresstack.spaceharvester.app.chat.ChatExport.Scope.ANSWER);
        message.setActions(buttons);
    }

    private void addExportButton(JPanel buttons, javax.swing.Icon icon, String label, ChatRecord snapshot,
            ChatRecord.Message answer, com.aresstack.spaceharvester.app.chat.ChatExport.Scope scope) {
        com.aresstack.spaceharvester.app.ui.controls.FlatIconButton button =
                new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(icon, label);
        button.setForeground(MarkdownTheme.forSurface(transcriptScroll.getViewport().getBackground()).getMutedForeground());
        javax.swing.JMenu formats = exportMenu(label, snapshot, answer, scope);
        button.setEnabled(formats.isEnabled());
        button.addActionListener(e -> {
            ChatRecord current = chat.id().equals(snapshot.id()) ? chat : snapshot;
            ChatRecord named = snapshot.withTitle(current.title(), snapshot.modifiedAt());
            PopupHover.clearOnClose(exportMenu(label, named, answer, scope).getPopupMenu(), button)
                    .show(button, 0, button.getHeight());
        });
        buttons.add(button);
    }

    private void export(ChatRecord snapshot, ChatRecord.Message answer,
            com.aresstack.spaceharvester.app.chat.ChatExport.Scope scope,
            com.aresstack.spaceharvester.app.chat.ChatExport.Format format) {
        javax.swing.JFileChooser chooser = new javax.swing.JFileChooser();
        int index = 0;
        if (answer != null) {
            for (ChatRecord.Message entry : snapshot.messages()) {
                if (entry.assistant()) {
                    index++;
                }
                if (entry == answer || entry.equals(answer)) {
                    break;
                }
            }
        }
        chooser.setSelectedFile(new java.io.File(com.aresstack.spaceharvester.app.chat.ChatExport.filename(
                snapshot, index, scope, format)));
        if (chooser.showSaveDialog(this) != javax.swing.JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path chosen = chooser.getSelectedFile().toPath();
        Path target = chosen.toString().toLowerCase(java.util.Locale.ROOT).endsWith("." + format.extension)
                ? chosen : Path.of(chosen + "." + format.extension);
        if (java.nio.file.Files.exists(target) && javax.swing.JOptionPane.showConfirmDialog(this,
                "Datei überschreiben?", "Exportieren", javax.swing.JOptionPane.YES_NO_OPTION)
                != javax.swing.JOptionPane.YES_OPTION) {
            return;
        }
        new javax.swing.SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                com.aresstack.spaceharvester.app.chat.ChatExport.write(
                        com.aresstack.spaceharvester.app.chat.ChatExport.document(snapshot, answer, scope),
                        format, target);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                    status.setText("Export gespeichert: " + target.getFileName());
                } catch (Exception error) {
                    status.setText("Export fehlgeschlagen: " + error.getMessage());
                }
            }
        }.execute();
    }

    private void generateChatTitle() {
        if (titlePending || chat.isEmpty() || turn != null) {
            return;
        }
        AppRuntime runtime = context.runtime();
        String model = context.settings() == null || context.settings().ai() == null ? null
                : context.settings().ai().chatModel();
        if (runtime == null || runtime.workspace().aiChat() == null || model == null || model.isBlank()) {
            status.setText(NO_CHAT_MODEL);
            return;
        }
        ChatRecord snapshot = chat;
        Path folder = home;
        titlePending = true;
        titleAi.setEnabled(false);
        titleAi.setIcon(Glyph.SYNC);
        titleAi.setToolTipText("Chat-Titel wird erzeugt");
        try {
            runtime.workspace().aiChat().stream(
                    com.aresstack.spaceharvester.app.chat.ChatTitle.request(snapshot, model, effortValue()),
                    new com.aresstack.spaceharvester.ai.AiChat.Listener() {
                        @Override public void delta(String text) { }
                        @Override public void completed(String text) {
                            SwingUtilities.invokeLater(() -> finishTitle(snapshot, folder, text));
                        }
                        @Override public void failed(com.aresstack.spaceharvester.ai.AiChatException error) {
                            SwingUtilities.invokeLater(() -> finishTitle(snapshot, folder, null));
                        }
                        @Override public void cancelled() {
                            SwingUtilities.invokeLater(() -> finishTitle(snapshot, folder, null));
                        }
                    });
        } catch (RuntimeException error) {
            finishTitle(snapshot, folder, null);
        }
    }

    private void finishTitle(ChatRecord snapshot, Path folder, String result) {
        titlePending = false;
        titleAi.setIcon(Glyph.SPARKLE);
        titleAi.setToolTipText("Chat-Titel mit KI erzeugen");
        String title = com.aresstack.spaceharvester.app.chat.ChatTitle.clean(result);
        if (chat.id().equals(snapshot.id()) && java.util.Objects.equals(home, folder)
                && !title.isBlank() && chat.title().equals(snapshot.title())) {
            chat = chat.withTitle(title, System.currentTimeMillis());
            store.save(home, chat);
        } else if (chat.id().equals(snapshot.id()) && title.isBlank()) {
            status.setText("Chat-Titel konnte nicht erzeugt werden");
        }
        showChatName();
    }

    /** Rollt mit, solange der Nutzer unten steht; hat er hochgerollt, bleibt der Verlauf stehen
     * ({@link com.aresstack.spaceharvester.app.ui.controls.BottomFollow}). */
    private void scrollDown() {
        bottom.follow();
    }

    // ------------------------------------------------------------------ Chats des Bereichs

    /** Ein neuer, leerer Chat in diesem Bereich; ein laufender Turn schreibt seinen alten Chat zu Ende. */
    private void newChat() {
        if (turn != null) {
            detachTurn();
        }
        open(ChatRecord.fresh(System.currentTimeMillis()), store.folder(stored()));
        editor.requestFocusInWindow();
    }

    /** Zeigt einen Chat des gewählten Bereichs. */
    private void open(ChatRecord record) {
        open(record, store.folder(stored()));
    }

    /** Zeigt einen Chat aus seinem Ordner: Verlauf als Blasen samt Marken beim Bereichswechsel, Name in der Kopfzeile. */
    private void open(ChatRecord record, Path folder) {
        if (turn != null) {
            // Die Antwort läuft weiter und wird gespeichert, sobald sie fertig ist; die Ansicht lässt sie los.
            detachTurn();
        }
        chat = record;
        home = folder;
        ChatRecord.Message asked = null;
        readAloud.clear();
        transcript.removeAll();
        transcript.add(emptyNote);
        emptyNote.setVisible(true);
        for (ChatRecord.Message message : record.messages()) {
            if (message.user()) {
                addAreaMark(asked, message.area(), message.scope());
                asked = message.area() == null ? asked : message;
                addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du",
                        message.text(), message.at());
            } else {
                AssistantMarkdownMessage answer = addAnswer(message.cancelled()
                        ? message.text() + "\n\n" + CANCELLED : message.text(), message.at());
                answeringSources.show(message.sources());
                answerActions(answer, record, message);
                readAloud.answer(answer);
            }
        }
        transcript.revalidate();
        transcript.repaint();
        bottom.jump(); // ein geöffneter Chat steht unten, auch wenn im vorigen hochgerollt war
        status.setText(" ");
        editor.setText("");
        showChatName();
    }

    private void showChatName() {
        chatName.setText(com.aresstack.spaceharvester.app.chat.ChatExport.displayTitle(chat));
        titleAi.setEnabled(!chat.isEmpty() && !titlePending && turn == null);
        chats.setEnabled(true);
        updateHeaderActions();
    }

    /**
     * Der offene Chat ist immer löschbar (Angelo 24.11.2026), auch wenn er zu einem anderen Bereich gehört oder gerade
     * eine Antwort läuft; die Antwort wird dann abgebrochen und nicht mehr gespeichert.
     */
    private void updateHeaderActions() {
        newChat.setEnabled(true);
        deleteChat.setEnabled(true);
        updateQuickActions();
    }

    /** „Chat: ‹Name› ▾“ für die Kopfzeile der Seitenleiste ({@link ChatSidebar}). */
    public JComponent header() {
        return header;
    }

    /** Setzt die Schreibmarke ins Eingabefeld, etwa nach dem Aufklappen der Seitenleiste. */
    public void focusEditor() {
        editor.requestFocusInWindow();
    }

    /** Neuer Chat und Chat löschen als Symbole für die Kopfzeile der Seitenleiste, rechts. */
    public JComponent headerActions() {
        return headerActions;
    }

    /**
     * Der Pfeil: zuerst die Chats des links gewählten Bereichs, darunter abgetrennt alle übrigen, je Gruppe zuletzt
     * geänderte zuerst, der offene mit Haken (Angelo, 21.11.2026).
     */
    private void showChatsMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (Component item : chatMenuItems()) {
            menu.add(item);
        }
        PopupHover.clearOnClose(menu, chats).show(chats, 0, chats.getHeight());
    }

    /** Die Einträge des Menüs unter ▾; für Tests ohne geöffnetes Popup. */
    List<Component> chatMenuItems() {
        List<Component> items = new ArrayList<>();
        Path here = store.folder(stored());
        List<ChatStore.Entry> all = new ArrayList<>(store.all());
        // Vorläufig gelöschte Chats stehen bis zum Neustart durchgestrichen dabei (Angelo 24.11.2026).
        java.util.Set<String> deletedIds = new java.util.HashSet<>();
        for (ChatStore.Entry entry : store.deleted()) {
            deletedIds.add(entry.record().id());
            all.add(entry);
        }
        all.sort(java.util.Comparator.comparingLong((ChatStore.Entry entry) -> entry.record().modifiedAt())
                .reversed());
        List<ChatStore.Entry> local = all.stream().filter(entry -> entry.folder().equals(here)).toList();
        List<ChatStore.Entry> others = all.stream().filter(entry -> !entry.folder().equals(here)).toList();
        if (all.isEmpty()) {
            JMenuItem none = new JMenuItem(NO_CHATS);
            none.setEnabled(false);
            items.add(none);
            return items;
        }
        items.add(menuHeading(CHATS_HERE));
        if (local.isEmpty()) {
            JMenuItem none = new JMenuItem(NO_CHATS_HERE);
            none.setEnabled(false);
            items.add(none);
        }
        local.forEach(entry -> items.add(chatItem(entry, deletedIds.contains(entry.record().id()))));
        if (!others.isEmpty()) {
            items.add(new JPopupMenu.Separator());
            items.add(menuHeading(CHATS_ELSEWHERE));
            others.forEach(entry -> items.add(chatItem(entry, deletedIds.contains(entry.record().id()))));
        }
        return items;
    }

    private JMenuItem chatItem(ChatStore.Entry entry, boolean deleted) {
        ChatRecord record = entry.record();
        boolean current = !deleted && record.id().equals(chat.id());
        String label = (current ? "✓ " : "    ") + com.aresstack.spaceharvester.app.chat.ChatExport.displayTitle(record);
        JMenuItem item = new JMenuItem(deleted ? "<html><s>" + escapeHtml(label) + "</s></html>" : label);
        String area = lastArea(record);
        item.setToolTipText(deleted ? DELETED_TIP : Texts.count(record.messages().size(), "Nachricht", "Nachrichten")
                + (area == null ? "" : " · zuletzt: " + areaName(area)));
        item.addActionListener(e -> {
            if (deleted) {
                // Öffnen macht das Löschen rückgängig.
                ChatRecord restored = store.restore(entry.folder(), record.id());
                open(restored == null ? record : restored, entry.folder());
            } else if (!current) {
                open(record, entry.folder());
            }
        });
        return item;
    }

    private static String escapeHtml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static JMenuItem menuHeading(String text) {
        JMenuItem heading = new JMenuItem(text);
        heading.setEnabled(false);
        heading.setFont(Comic.semiBold(11f));
        return heading;
    }

    // ------------------------------------------------------------------ Bereichswechsel im Verlauf

    /** Die letzte Frage eines Chats, die ihren Bereich kennt; {@code null} ohne solche Frage. */
    static ChatRecord.Message lastQuestion(ChatRecord record) {
        ChatRecord.Message asked = null;
        for (ChatRecord.Message message : record.messages()) {
            if (message.user() && message.area() != null) {
                asked = message;
            }
        }
        return asked;
    }

    /** Der Bereich der letzten Frage eines Chats in Worten; {@code null} ohne Frage mit Bereich. */
    static String lastArea(ChatRecord record) {
        ChatRecord.Message asked = lastQuestion(record);
        return asked == null ? null : asked.area();
    }

    /**
     * Die Kennung eines Bereichs: Quelle, Space und Seite, dazu ob nur die Seite ohne Unterseiten gemeint ist, oder der
     * Favoriten-Ordner einer Sammlung. Zwei gleich benannte Spaces, Seiten oder Ordner sind damit verschiedene Bereiche;
     * {@code null} ohne Auswahl.
     */
    /**
     * Wo die Chats des Bereichs liegen: ohne Auswahl (etwa in der Suche nach der Lupe, Angelo 24.11.2026) bei denen
     * zu allen Wissensquellen.
     */
    private ChatNode stored() {
        return node == null ? ChatNode.ALL : node;
    }

    static String scopeKey(ChatNode node, boolean pageOnly) {
        if (node == null) {
            return null;
        }
        if (node.isCollection()) {
            return "ordner\u0000" + node.folderId();
        }
        if (node.sourceId() == null) {
            return "*";
        }
        return node.sourceId() + "\u0000" + (node.spaceKey() == null ? "" : node.spaceKey()) + "\u0000"
                + (node.pageId() == null ? "" : node.pageId()) + (pageOnly ? "\u0000nur" : "");
    }

    /** Derselbe Bereich: nach der Kennung, bei Chats ohne Kennung nach dem Namen ohne Zählung. */
    static boolean sameArea(ChatRecord.Message before, String next, String nextScope) {
        if (before.scope() != null && nextScope != null) {
            return before.scope().equals(nextScope);
        }
        return Objects.equals(areaName(before.area()), areaName(next));
    }

    /**
     * Der Bereich ohne seine Zählung („Handbuch · alle Spaces“ statt „… · 12 Seiten · 3 davon nicht geladen“): Lädt
     * der Bestand zwischen zwei Fragen nach, ist es noch derselbe Bereich.
     */
    static String areaName(String area) {
        return area == null ? null : AREA_COUNTS.matcher(area).replaceFirst("");
    }

    private static final java.util.regex.Pattern AREA_COUNTS = java.util.regex.Pattern.compile(
            " · (\\d+ Seiten?|" + java.util.regex.Pattern.quote(NOT_LOADED) + ")( · \\d+ davon nicht geladen)?$");

    /**
     * Vor einer Frage aus einem anderen Bereich als die vorige steht eine Marke über die ganze Breite: „Ab hier: …“.
     * Sie entsteht erst beim Senden, nicht bei jedem Klick im Baum; ältere Antworten sehen so nie aus, als kämen sie
     * aus dem neuen Bereich.
     */
    private void addAreaMark(ChatRecord.Message before, String next, String nextScope) {
        if (before == null || next == null || sameArea(before, next, nextScope)) {
            return;
        }
        AreaMark mark = new AreaMark(FROM_HERE + next);
        mark.setAlignmentX(LEFT_ALIGNMENT);
        transcript.add(mark);
    }

    /** Die Marke eines Bereichswechsels: gedämpfter Text zwischen zwei dünnen Linien, bricht nie die Breite. */
    static final class AreaMark extends JPanel {

        private final JLabel label;

        AreaMark(String text) {
            super(new BorderLayout(8, 0));
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(8, 14, 4, 14));
            label = Comic.muted(text);
            label.setFont(Comic.regular(11f));
            label.setToolTipText(text);
            // Der Text kürzt sich mit „…“, statt den Verlauf zu verbreitern.
            label.setMinimumSize(new Dimension(0, label.getPreferredSize().height));
            add(rule(), BorderLayout.WEST);
            add(label, BorderLayout.CENTER);
        }

        String text() {
            return label.getText();
        }

        private static JComponent rule() {
            JPanel rule = new JPanel() {
                @Override
                protected void paintComponent(java.awt.Graphics g) {
                    g.setColor(Comic.LINE);
                    g.fillRect(0, getHeight() / 2, getWidth(), 1);
                }
            };
            rule.setOpaque(false);
            rule.setPreferredSize(new Dimension(16, 1));
            return rule;
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /**
     * Meldet, wenn ein Quellen-Link eine Seite links geöffnet hat; die Leiste bleibt offen, die große Ansicht macht
     * der Arbeitsfläche Platz.
     */
    public void onSourceOpened(Runnable observer) {
        sourceObserver = observer == null ? () -> { } : observer;
    }

    /** Ein Klick auf eine Quelle öffnet ihre Seite links in der Leseansicht und zeigt sie im Baum. */
    private void openSource(ChatRecord.Source source) {
        context.navigator().openPage(source.sourceId(), source.pageId());
        sourceObserver.run();
    }

    /**
     * Die Quellen einer Antwort als Zeile unter ihrer Blase (Entwurf Chat-Seitenleiste, Punkt 8): je Quelle ein Link
     * „[n] Titel“ in der Nummer, die die Antwort nennt. Ohne Quellen ist die Zeile nicht da; die Links brechen in
     * der schmalen Leiste um. Eine Quelle ohne Seite (ein Dateianhang) steht als Text da.
     *
     * <p>Die Liste ist zunächst eingeklappt (Angelo, 26.11.2026): eine einzige Zeile, die die Quellen als schlichten
     * Text aufzählt und am Ende mit „…“ abschneidet, was nicht hineinpasst. Links davor, nah bei den Knöpfen der
     * Antwort, steht immer ein Pfeil: eingeklappt nach rechts, aufgeklappt nach unten. Aufgeklappt stehen die
     * Quellen untereinander, jede als Link.
     */
    static final class SourceLinks extends JPanel implements com.aresstack.enterpriseai.ui.comic.bubble.WidthAwareHeight {

        static final int TITLE_LENGTH = 48;

        static final String SHOW_ALL = "Alle Quellen zeigen";
        static final String SHOW_LESS = "Quellen einklappen";
        /** Zeilenhöhe wie die Knöpfe daneben, damit die erste Zeile mit ihnen auf einer Linie steht. */
        private static final int ROW = com.aresstack.spaceharvester.app.ui.controls.FlatIconButton.SIZE;
        private static final int ROW_GAP = 2;
        private static final int TOGGLE_GAP = 4;

        private final java.util.function.Consumer<ChatRecord.Source> open;
        private List<ChatRecord.Source> shown = List.of();
        private final List<JComponent> entries = new ArrayList<>();
        /** Die eingeklappte Zeile: alle Quellen als Text; JLabel kürzt selbst mit „…“, wenn der Platz nicht reicht. */
        private final JLabel summary = Comic.muted("");
        private final com.aresstack.spaceharvester.app.ui.controls.FlatIconButton toggle =
                new com.aresstack.spaceharvester.app.ui.controls.FlatIconButton(Glyph.EXPAND, SHOW_ALL);
        private boolean expanded;

        SourceLinks(java.util.function.Consumer<ChatRecord.Source> open) {
            super(null);
            this.open = open;
            setOpaque(false);
            standalone(false);
            setVisible(false);
            summary.setFont(Comic.regular(11f));
            summary.setName("transcript.sourcesSummary");
            // Auch ein Klick auf die Zeile klappt auf, nicht nur der kleine Pfeil.
            Cursors.set(summary, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            summary.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (SwingUtilities.isLeftMouseButton(event)) {
                        expanded(true);
                    }
                }
            });
            toggle.setName("transcript.sourcesToggle");
            toggle.setForeground(Comic.BLUE);
            toggle.setVisible(false);
            toggle.addActionListener(e -> expanded(!expanded));
        }

        /** Klappt die Liste auf oder zu; die Antwort darüber und der Verlauf rücken nach. */
        void expanded(boolean value) {
            if (expanded == value) {
                return;
            }
            expanded = value;
            toggle.setIcon(expanded ? Glyph.COLLAPSE : Glyph.EXPAND);
            toggle.setToolTipText(expanded ? SHOW_LESS : SHOW_ALL);
            toggle.getAccessibleContext().setAccessibleName(toggle.getToolTipText());
            for (java.awt.Container up = this; up != null; up = up.getParent()) {
                up.invalidate();
            }
            revalidate();
            repaint();
        }

        boolean expanded() {
            return expanded;
        }

        /** Der Pfeil links vor den Quellen; immer da, wenn es Quellen gibt. */
        javax.swing.JButton toggle() {
            return toggle;
        }

        boolean hasEntries() {
            return !entries.isEmpty();
        }

        void show(List<ChatRecord.Source> sources) {
            if (shown.equals(sources)) {
                return;
            }
            shown = List.copyOf(sources);
            removeAll();
            entries.clear();
            for (int index = 0; index < shown.size(); index++) {
                JComponent entry = entry(index + 1, shown.get(index));
                entries.add(entry);
                add(entry);
            }
            add(toggle);
            add(summary);
            summary.setText(String.join(", ", texts()));
            summary.setToolTipText(SHOW_ALL);
            setVisible(!shown.isEmpty());
            revalidate();
            repaint();
        }

        private JComponent entry(int number, ChatRecord.Source source) {
            String title = source.title() == null || source.title().isBlank() ? "ohne Titel" : source.title().strip();
            String text = "[" + number + "] "
                    + (title.length() > TITLE_LENGTH ? title.substring(0, TITLE_LENGTH - 1) + "…" : title);
            if (source.pageId() == null) {
                JLabel plain = Comic.muted(text);
                plain.setFont(Comic.regular(11f));
                plain.setToolTipText(title);
                return plain;
            }
            javax.swing.JButton link = new javax.swing.JButton(text);
            link.setFont(Comic.regular(11f));
            link.setForeground(Comic.BLUE);
            link.setBorder(BorderFactory.createEmptyBorder());
            link.setContentAreaFilled(false);
            link.setFocusPainted(false);
            Cursors.set(link, java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            link.setToolTipText(title + " öffnen");
            link.addActionListener(e -> open.accept(source));
            return link;
        }

        /** Die gezeigten Einträge als Text; für Tests. */
        List<String> texts() {
            List<String> texts = new ArrayList<>();
            for (Component entry : entries) {
                texts.add(entry instanceof javax.swing.AbstractButton button ? button.getText()
                        : ((JLabel) entry).getText());
            }
            return texts;
        }

        void standalone(boolean value) {
            setBorder(BorderFactory.createEmptyBorder(0, value ? 14 : 0, value ? 6 : 0, value ? 14 : 0));
        }

        @Override
        public int preferredHeightForWidth(int width) {
            return layoutEntries(width, false);
        }

        @Override
        public void doLayout() {
            layoutEntries(getWidth(), true);
        }

        /** Die eingeklappte Zeile als Text; für Tests. */
        String summary() {
            return summary.getText();
        }

        /**
         * Eingeklappt: Pfeil und eine Textzeile über die restliche Breite. Aufgeklappt: Pfeil und darunter bündig
         * je Quelle eine Zeile mit ihrem Link.
         */
        private int layoutEntries(int width, boolean place) {
            java.awt.Insets insets = getInsets();
            int available = Math.max(1, width - insets.left - insets.right);
            int indent = ROW + TOGGLE_GAP;
            int usable = Math.max(1, available - indent);
            int rows = expanded ? Math.max(1, entries.size()) : 1;
            if (place) {
                toggle.setVisible(!entries.isEmpty());
                toggle.setBounds(insets.left, insets.top, ROW, ROW);
                int summaryHeight = Math.min(ROW, summary.getPreferredSize().height);
                summary.setVisible(!expanded);
                summary.setBounds(insets.left + indent, insets.top + (ROW - summaryHeight) / 2,
                        Math.min(usable, summary.getPreferredSize().width), summaryHeight);
                int row = 0;
                for (JComponent entry : entries) {
                    Dimension preferred = entry.getPreferredSize();
                    int height = Math.min(ROW, preferred.height);
                    entry.setVisible(expanded);
                    entry.setBounds(insets.left + indent, insets.top + row * (ROW + ROW_GAP) + (ROW - height) / 2,
                            Math.min(usable, preferred.width), height);
                    row++;
                }
            }
            return insets.top + rows * ROW + (rows - 1) * ROW_GAP + insets.bottom;
        }

        @Override
        public Dimension getPreferredSize() {
            int width = getWidth();
            if (width <= 0 && getParent() != null) {
                width = getParent().getWidth();
            }
            width = Math.max(1, width);
            return new Dimension(width, preferredHeightForWidth(width));
        }

        @Override
        public Dimension getMinimumSize() {
            return new Dimension(0, getPreferredSize().height);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Die Quellenzeilen des Verlaufs in Reihenfolge, auch die leeren; für Tests. */
    List<SourceLinks> sourceLinks() {
        List<SourceLinks> rows = new ArrayList<>();
        for (Component row : transcript.getComponents()) {
            if (row instanceof SourceLinks links) {
                rows.add(links);
            } else if (row instanceof AssistantMarkdownMessage answer && answer.sources() instanceof SourceLinks links) {
                rows.add(links);
            }
        }
        return rows;
    }

    /** Die Marken des Verlaufs in Reihenfolge; für Tests. */
    List<String> areaMarks() {
        List<String> marks = new ArrayList<>();
        for (Component row : transcript.getComponents()) {
            if (row instanceof AreaMark mark) {
                marks.add(mark.text());
            }
        }
        return marks;
    }

    /** Rechtsklick auf den Namen: Umbenennen an Ort und Stelle, Chat löschen mit Rückfrage. */
    private void showNameMenu(MouseEvent e) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem rename = new JMenuItem(RENAME);
        rename.addActionListener(ignored -> startRename());
        menu.add(rename);
        JMenuItem delete = new JMenuItem(DELETE);
        delete.addActionListener(ignored -> deleteChat());
        menu.add(delete);
        menu.show(chatName, e.getX(), e.getY());
    }

    void startRename() {
        chatNameEditor.setText(chat.renamed() ? chat.title() : "");
        nameHolder.remove(chatName);
        nameHolder.add(chatNameEditor, BorderLayout.CENTER);
        nameHolder.revalidate();
        nameHolder.repaint();
        chatNameEditor.requestFocusInWindow();
        chatNameEditor.selectAll();
    }

    /** Übernimmt den eingegebenen Namen (leer: zurück zur Kennung) und speichert, wenn der Chat Nachrichten hat. */
    void finishRename(boolean accept) {
        if (!renaming()) {
            return;
        }
        if (accept) {
            String title = chatNameEditor.getText().strip();
            if (!title.equals(chat.title())) {
                chat = chat.withTitle(title.isEmpty() ? chat.id() : title, System.currentTimeMillis());
                store.save(home, chat);
            }
        }
        nameHolder.remove(chatNameEditor);
        nameHolder.add(chatName, BorderLayout.CENTER);
        nameHolder.revalidate();
        nameHolder.repaint();
        showChatName();
    }

    private boolean renaming() {
        return chatNameEditor.getParent() == nameHolder;
    }

    /**
     * Löscht den offenen Chat ohne Rückfrage (Angelo 24.11.2026): Er bleibt bis zum nächsten Start durchgestrichen im
     * Menü unter ▾ stehen, ein Klick dort holt ihn zurück; endgültig weg ist er erst nach dem Neustart. Danach steht der
     * nächste Chat des Bereichs da.
     */
    private void deleteChat() {
        discardTurn();
        if (home != null && !chat.isEmpty()) {
            store.trash(home, chat.id());
        }
        List<ChatRecord> saved = store.list(stored());
        open(saved.isEmpty() ? ChatRecord.fresh(System.currentTimeMillis()) : saved.get(0), store.folder(stored()));
    }

    /**
     * „Alle Chats löschen“ im Menü am Dreieck (Angelo 24.11.2026): nach „wirklich“-Rückfrage (Ja/Nein) alle Chats
     * aller Bereiche endgültig, auch die vorläufig gelöschten, samt ihren Browser-Profilen; danach ein neuer Chat.
     */
    void deleteAllChats() {
        if (!confirm.test(DELETE_ALL, DELETE_ALL_QUESTION)) {
            return;
        }
        discardTurn();
        List<String> ids = new ArrayList<>(store.deleteAll());
        ids.add(chat.id());
        deleteBrowserProfiles(ids);
        open(ChatRecord.fresh(System.currentTimeMillis()), store.folder(stored()));
    }

    /** Bricht eine laufende Antwort ab, deren Chat gelöscht wird: Sie schreibt ihn nicht wieder auf die Platte. */
    private void discardTurn() {
        if (turn == null) {
            return;
        }
        if (listening != null) {
            listening.discarded = true;
            listening.endApprovals();
        }
        turn.cancel();
        detachTurn();
    }

    /** Das Browser-Profil eines Chats geht mit dem Chat (wie im Enterprise AI Client), ohne die Oberfläche aufzuhalten. */
    private void deleteBrowserProfiles(List<String> chatIds) {
        com.aresstack.spaceharvester.app.chat.browser.ChatWebBrowser browser = context.webBrowser();
        if (browser == null) {
            return;
        }
        List<String> ids = List.copyOf(new java.util.LinkedHashSet<>(chatIds));
        Thread cleanup = new Thread(() -> ids.forEach(browser::deleteProfile), "chat-browser-profil");
        cleanup.setDaemon(true);
        cleanup.start();
    }

    // ------------------------------------------------------------------ für Tests

    String knowledge() {
        return knowledge.getText();
    }

    /** Ob „Neuer Chat“ geht (für Tests). */
    boolean newChatEnabled() {
        return newChat.isEnabled();
    }

    String status() {
        return status.getText();
    }

    JComponent editor() {
        return editor;
    }

    /** Die sichtbaren Zeilen des Verlaufs in ihrer Reihenfolge (für Tests). */
    List<Component> transcriptRows() {
        List<Component> rows = new ArrayList<>();
        for (Component row : transcript.getComponents()) {
            if (row.isVisible()) {
                rows.add(row);
            }
        }
        return rows;
    }

    /** Das Vorlesen des Chats (für Tests). */
    ChatReadAloud readAloud() {
        return readAloud;
    }

    /**
     * Schließt die Sprachausgabe der App an (dieselbe wie im Lesebereich, damit immer nur eine Stimme spricht); der
     * Knopf erscheint, sobald ein Dienst mit Sprachausgabe eingerichtet ist.
     */
    public void useReadAloud(
            java.util.function.Supplier<com.aresstack.spaceharvester.app.speech.ReadAloudBinding> bindings) {
        readAloudBindings = bindings == null ? () -> null : bindings;
        bindReadAloud();
    }

    /**
     * Knopf bereit, sobald die Laufzeit steht: vorgelesen wird über denselben Weg wie der Podcast
     * ({@code pagePodcasts().readAloud}); fehlt die Sprachausgabe, nennt die Statuszeile beim Klick den Grund. Nicht
     * vorab gesperrt: die Prüfung beim Start kam zu früh (Angelo 24.11.2026: „der Podcast funktioniert, der Chat nicht“).
     */
    private void bindReadAloud() {
        AppRuntime runtime = context.runtime();
        readAloud.bind(runtime != null ? readAloudBindings.get() : null, ChatReadAloud.NO_RUNTIME_TIP);
        if (transcriptLayers != null) {
            relayoutOrb();
            transcriptLayers.revalidate();
        }
    }

    /**
     * Die Vorlese-Knöpfe der großen Ansicht (Leseansicht, Podcast): steht einer davon sichtbar, liegt der Knopf des
     * Chats auf genau seiner Höhe, sonst auf der Höhe, die er dort hätte.
     */
    public void alignReadAloudWith(JComponent... anchors) {
        for (JComponent anchor : anchors) {
            if (anchor == null || orbAnchors.contains(anchor)) {
                continue;
            }
            orbAnchors.add(anchor);
            anchor.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentMoved(java.awt.event.ComponentEvent event) {
                    relayoutOrb();
                }

                @Override
                public void componentShown(java.awt.event.ComponentEvent event) {
                    relayoutOrb();
                }

                @Override
                public void componentHidden(java.awt.event.ComponentEvent event) {
                    relayoutOrb();
                }
            });
            anchor.addHierarchyBoundsListener(new java.awt.event.HierarchyBoundsAdapter() {
                @Override
                public void ancestorMoved(java.awt.event.HierarchyEvent event) {
                    relayoutOrb();
                }

                @Override
                public void ancestorResized(java.awt.event.HierarchyEvent event) {
                    relayoutOrb();
                }
            });
            anchor.addHierarchyListener(event -> {
                if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0) {
                    relayoutOrb();
                }
            });
        }
        relayoutOrb();
    }

    private void relayoutOrb() {
        if (transcriptLayers != null) {
            transcriptLayers.doLayout();
            transcriptLayers.repaint();
        }
    }

    private void layoutTranscript() {
        int width = transcriptLayers.getWidth();
        int height = transcriptLayers.getHeight();
        javax.swing.JScrollBar bar = transcriptScroll.getVerticalScrollBar();
        int barWidth = bar.isVisible() ? bar.getWidth() : 0;
        int size = com.aresstack.spaceharvester.app.ui.controls.PodcastOrb.SIZE;
        // Angelo 24.11.2026 („zu tief, sieht schief aus“): dieselbe Höhe und derselbe Abstand vom rechten Rand wie
        // der sichtbare Knopf der großen Ansicht; ohne ihn die Lage, die er dort hätte (NavigableDocumentPage).
        int top = NavigableDocumentPage.orbTop(size);
        int gap = NavigableDocumentPage.ORB_INSET;
        JComponent big = bigOrb();
        if (big != null) {
            top = SwingUtilities.convertPoint(big.getParent(), big.getLocation(), transcriptLayers).y;
            gap = big.getParent().getWidth() - big.getX() - big.getWidth();
        }
        top = Math.max(0, Math.min(top, height - size));
        gap = Math.max(gap, barWidth + NavigableDocumentPage.ORB_INSET);
        int orbX = Math.max(0, width - gap - size);
        transcriptScroll.setBounds(0, 0, width, height);
        int inset = transcriptInset(width, orbX, barWidth, readAloud.orb().isVisible());
        if (transcript.getInsets().right != inset) {
            transcript.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, inset));
            transcript.revalidate();
        }
        readAloud.orb().setBounds(orbX, top, size, size);
    }

    /**
     * Der sichtbare Vorlese-Knopf der großen Ansicht im selben Fenster: der rechteste links vom Chat, dessen Höhe
     * in den Verlauf passt; sonst {@code null}.
     */
    private JComponent bigOrb() {
        if (!transcriptLayers.isShowing()) {
            return null;
        }
        java.awt.Window window = SwingUtilities.getWindowAncestor(transcriptLayers);
        if (window == null) {
            return null;
        }
        int left = transcriptLayers.getLocationOnScreen().x;
        JComponent best = null;
        int bestX = Integer.MIN_VALUE;
        for (JComponent orb : orbs(window, new ArrayList<>())) {
            if (orb == readAloud.orb() || !orb.isShowing() || orb.getParent() == null) {
                continue;
            }
            int x = orb.getLocationOnScreen().x;
            int top = SwingUtilities.convertPoint(orb.getParent(), orb.getLocation(), transcriptLayers).y;
            if (x < left && x > bestX && top >= 0 && top + orb.getHeight() <= transcriptLayers.getHeight()) {
                best = orb;
                bestX = x;
            }
        }
        return best;
    }

    private static List<JComponent> orbs(java.awt.Container container, List<JComponent> found) {
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.PodcastOrb orb) {
                found.add(orb);
            } else if (child instanceof java.awt.Container inner) {
                orbs(inner, found);
            }
        }
        return found;
    }

    /** Reserve space inside the scrollable surface without moving the scrollbar. */
    static int transcriptInset(int width, int orbX, int scrollbarWidth, boolean orbVisible) {
        return orbVisible ? Math.max(0, width - scrollbarWidth - orbX + 6) : 0;
    }

    int bubbles() {
        int count = 0;
        for (Component row : transcript.getComponents()) {
            if (row instanceof BubbleMessageRow || row instanceof AssistantMarkdownMessage) {
                count++;
            }
        }
        return count;
    }

    List<Path> attachments() {
        return List.copyOf(attachments);
    }

    List<String> quotes() {
        return List.copyOf(quotes);
    }

    com.aresstack.spaceharvester.app.ui.controls.QuoteOverlayButton quoteOverlay() {
        return quoteOverlay;
    }

    boolean loadVisible() {
        return load.isVisible();
    }

    ComposerButton sendButton() {
        return send;
    }

    String chatName() {
        return chatName.getText();
    }

    ChatRecord chat() {
        return chat;
    }

    JTextField chatNameEditor() {
        return chatNameEditor;
    }

    boolean renamingShown() {
        return renaming();
    }
}
