package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.controls.Cursors;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleMessageRow;
import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.spaceharvester.app.ui.markdown.AssistantMarkdownBubble;
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
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.ChatTurn;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.speech.Dictation;
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
    static final String DELETE_TITLE = "Chat löschen";
    static final String DELETE_QUESTION = "Diesen Chat wirklich löschen?";
    static final String ASSISTANT = "Assistent";
    static final String ANSWERING = "Antwort wird geschrieben …";
    /** Wie in AskAI: solange Ollama das Modell lädt, steht das in der Blase und in der Statuszeile. */
    static final String MODEL_LOADING = "Modell wird geladen …";
    static final String SEARCHING = "Wissen wird gesucht …";
    static final String CANCELLED = "Abgebrochen";
    static final String DOWNLOAD_DONE = "abgeschlossen";
    static final String DOWNLOAD_FAILED = "fehlgeschlagen";
    static final String ATTACH = "Dateien anhängen";
    static final String DICTATE = "Spracheingabe mit Whisper";
    /** Wie im Enterprise AI Client: die Erkennung nach dem Stopp ist orange in der Statuszeile zu sehen. */
    static final String RECOGNIZING_STATUS = "Erkenne Sprache …";
    private static final int MIN_EDITOR_HEIGHT = 64;
    private static final int MAX_NORMAL_EDITOR_HEIGHT = 400;
    private static final String NORMAL_EDITOR_HEIGHT = "composer.normalHeight";
    private static final java.util.prefs.Preferences PREFERENCES =
            java.util.prefs.Preferences.userNodeForPackage(ChatPanel.class);
    static final String WEB = "Websuche folgt";
    static final String NO_MODEL = "Kein Chat-Modell";
    static final String MODEL_TIP = "Chat-Modell aus Einstellungen › KI";
    static final String NO_SELECTION = "Links eine Wissensquelle, einen Space oder eine Seite wählen";
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
    /** Der Name beim Umbenennen an Ort und Stelle. */
    private final JTextField chatNameEditor = new JTextField();
    private final JPanel nameHolder = new JPanel(new BorderLayout());
    /** Nur der Pfeil, durchsichtig: öffnet die Chats dieses Bereichs. */
    private final ComposerButton chats = ComposerButton.iconButton(ComposerIcons.chevronDown(),
            "Chats dieses Bereichs");
    /** Oben rechts in der Kopfzeile des Inhaltsbereichs, als Symbole (Angelo, 17.11.2026): Plus und Papierkorb. */
    private final IconButton newChat = new IconButton(ComposerIcons.plus(16), NEW_CHAT).compact();
    private final IconButton deleteChat = new IconButton(Glyph.TRASH, DELETE).compact();
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
    private AssistantMarkdownBubble answering;
    /** Antworten rendert der Chat als Markdown wie der EAC (AssistantMarkdownBubble, MarkdownMessageView). */
    private final DesktopLinkOpener linkOpener = DesktopLinkOpener.systemDefault();
    private MermaidImageRenderer mermaid;
    /** Die Auftragskennungen des zuletzt hier gestarteten Downloads, bis der Auftrag endet. */
    private final Set<String> watchedJobs = new HashSet<>();
    private Subscription jobSubscription;
    private AppRuntime jobsSubscribed;
    private final JPanel transcript = new JPanel();
    private final JLabel emptyNote = Comic.muted(EMPTY_TRANSCRIPT);
    private final JScrollPane transcriptScroll;
    private final BubblePalette bubbles = BubblePalette.windowsPhoneInspired();
    /** Die stille Statuszeile des Wissens über dem Composer. */
    private final JTextArea knowledge = Comic.note(" ");
    private final ComposerButton load = Comic.button(LOAD, "Die gewählten Seiten jetzt laden; der Auftrag läuft "
            + "unter Aufträge");
    private final Feedback feedback = new Feedback();
    private final JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
    private final List<Path> attachments = new ArrayList<>();
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
    private DictationPhase dictationPhase = DictationPhase.IDLE;
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
    /** Die Schnellaktionen für die untere Leiste; sichtbar nur im Reiter Chat. */
    private final JPanel quickActions = new JPanel(
            new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 6, 4));
    /** Anmeldung bei den Bestandsmeldungen des Core für Quelle und Space der Auswahl. */
    /** Anmeldungen bei den Bestandsmeldungen: eine je Quelle und Space der Auswahl, global eine je Quelle. */
    private final List<Subscription> subscriptions = new ArrayList<>();
    private String subscribedKey;
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
        add(transcriptScroll, BorderLayout.CENTER);

        composer = buildComposer();
        JPanel knowledgeRow = new JPanel(new BorderLayout(8, 0));
        knowledgeRow.setOpaque(false);
        knowledge.setForeground(Comic.MUTED);
        knowledgeRow.add(knowledge, BorderLayout.CENTER);
        knowledgeRow.add(load, BorderLayout.EAST);
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
        south.add(knowledgeRow, BorderLayout.CENTER);
        south.add(composer, BorderLayout.SOUTH);
        add(south, BorderLayout.SOUTH);

        context.onChange(ignored -> {
            showModel();
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
        web.setEnabled(false);
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
        east.add(web);
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
        dictation.cancel();
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
            public void recognized(String text) {
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
                dictationPhase = DictationPhase.IDLE;
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
        long seconds = Math.max(0, (System.currentTimeMillis() - recordingStartedMillis) / 1000L);
        status.setText(String.format(java.util.Locale.ROOT, "● Aufnahme %d:%02d%s",
                seconds / 60, seconds % 60, dictation.clipping() ? " – übersteuert" : ""));
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

    private java.util.function.Supplier<ToolScope> toolScope;

    /**
     * Setzt die Werkzeuge der aktiven Perspektive (beim Senden ausgewertet) oder nimmt sie mit {@code null} weg; dann
     * gilt wieder der links gewählte Bereich des Space Harvesters.
     */
    public void toolScope(java.util.function.Supplier<ToolScope> scope) {
        this.toolScope = scope;
        if (scope != null) {
            knowledge.setText(TOOL_SCOPE_HINT);
            load.setVisible(false);
            updateSend();
        } else {
            refresh();
        }
    }

    /** Der Hinweis über dem Eingabefeld, solange die Werkzeuge einer Perspektive gelten. */
    static final String TOOL_SCOPE_HINT = "Command Deck: Der Chat liest die Terminal-Maske und füllt Felder aus – "
            + "abschicken kannst nur du.";

    /** Die Auswahl links; {@code null} ohne Auswahl. Erst anmelden, dann lesen (ADR 0027). */
    public void selection(Selection selection) {
        if (Objects.equals(this.selection, selection)) {
            return;
        }
        this.selection = selection;
        ChatNode next = selection == null ? null
                : selection.global() ? ChatNode.ALL
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
        home = store.folder(node);
        if (!resumed && node != null) {
            resumed = true;
            List<ChatRecord> saved = store.list(node);
            if (!saved.isEmpty()) {
                open(saved.get(0));
                return;
            }
        }
        showChatName();
    }

    /** Meldet sich für Quelle und Space der Auswahl an; eine andere Auswahl oder Laufzeit meldet neu an. */
    private void subscribe() {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        String key = runtime == null || current == null ? null
                : current.sourceId() + "\u0000" + (current.spaceKey() == null ? "" : current.spaceKey());
        if (runtime == subscribed && Objects.equals(key, subscribedKey)) {
            return;
        }
        for (Subscription subscription : subscriptions) {
            subscription.close();
        }
        subscriptions.clear();
        subscribed = runtime;
        subscribedKey = key;
        if (key == null) {
            return;
        }
        // Die globale Auswahl hört auf jede Wissensquelle der Laufzeit.
        List<String> sourceIds = current.global()
                ? runtime.workspace().sources().stream().map(HarvestSource::id).toList()
                : List.of(current.sourceId());
        for (String sourceId : sourceIds) {
            subscribe(runtime, sourceId, current.spaceKey() == null ? "" : current.spaceKey());
        }
    }

    private void subscribe(AppRuntime runtime, String sourceId, String spaceKey) {
        try {
            subscriptions.add(runtime.workspace().subscribeHoldings(sourceId, spaceKey, new HoldingsListener() {
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
        } catch (RuntimeException gone) {
            // Die gewählte Quelle gibt es in dieser Laufzeit nicht mehr (etwa entfernt): der Baum räumt die Auswahl
            // gleich weg; bis dahin ohne Meldungen, das Lesen meldet den Fehler selbst.
        }
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
        Background.run(() -> runtime.workspace().resolveSelection(current.toCore(runtime)), resolved -> {
            if (ticket == request) {
                renderKnowledge(current, resolved, true);
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

    /** @param settled ob der Core die Auswahl aufgelöst hat (auch ergebnislos); vorher ist Senden gesperrt */
    private void renderKnowledge(Selection current, PageSelection resolved, boolean settled) {
        this.resolved = resolved;
        this.settled = settled;
        boolean allowed = current == null || (current.global() ? allowedAll(context.settings(), context.runtime())
                : allowed(context.settings(), current.sourceId()));
        HoldingCounts counts = resolved == null ? null : resolved.counts();
        knowledge.setText(knowledgeText(current, counts, resolved == null || resolved.complete(), allowed));
        // Nachladen je Quelle, Space oder Seite; die globale Auswahl lädt man links je Wissensquelle.
        load.setVisible(current != null && !current.global() && (counts == null || counts.stored() < counts.pages()
                || counts.pages() == 0));
        if (toolScope != null) {
            // Im Command Deck gilt nicht die Auswahl links, sondern das Terminal (#1151).
            knowledge.setText(TOOL_SCOPE_HINT);
            load.setVisible(false);
        }
        // Abbrechen geht, solange eine Antwort läuft, auch ohne Auswahl oder während die neue noch aufgelöst wird.
        updateSend();
        updateQuickActions();
        if (onResolved != null) {
            onResolved.accept(resolved);
        }
    }

    /**
     * Abbrechen geht, solange eine Antwort läuft, auch ohne Auswahl oder während die neue noch aufgelöst wird (der
     * Chat bleibt beim Navigieren stehen); Senden braucht eine aufgelöste Auswahl.
     */
    private void updateSend() {
        send.setEnabled(turn != null || toolScope != null || (selection != null && settled));
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
        if (current != null) {
            // Bereit wie Senden: Auswahl aufgelöst und keine Antwort unterwegs.
            boolean ready = settled && turn == null;
            String area = areaText(current, resolved == null ? null : resolved.counts());
            if (current.pageId() != null) {
                // Nur die Seite: der Bereichstext folgt dem Umfang der Aktion, nicht der Auswahl samt Unterseiten.
                quickActions.add(chip(SUMMARIZE_PAGE, SUMMARIZE_PAGE_TIP, ready, () -> submit(
                        "Fasse die Seite „" + current.title() + "“ zusammen.", onlyPage(resolved, current),
                        pageAreaText(current), ChatTurn.Kind.QUESTION)));
                quickActions.add(chip(SUMMARIZE_SUBTREE, SUMMARIZE_SUBTREE_TIP, ready, () -> submit(
                        "Fasse die Seite „" + current.title() + "“ samt ihren Unterseiten zusammen.", resolved, area,
                        ChatTurn.Kind.QUESTION)));
            } else {
                quickActions.add(chip(SUMMARIZE_AREA, SUMMARIZE_AREA_TIP, ready, () -> submit(
                        "Fasse den Bereich „" + current.title() + "“ zusammen.", resolved, area,
                        ChatTurn.Kind.QUESTION)));
            }
            quickActions.add(chip(WHATS_NEW, WHATS_NEW_TIP, ready, () -> submit(WHATS_NEW, resolved, area,
                    ChatTurn.Kind.WHATS_NEW)));
        }
        // Ohne Auswahl gibt es keine Chips, also auch keine leere Zeile über der Eingabe.
        quickActions.setVisible(quickActions.getComponentCount() > 0);
        quickActions.revalidate();
        quickActions.repaint();
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

    /** Für die globale Auswahl: ob jede Wissensquelle der Laufzeit Inhalte an den KI-Dienst geben darf. */
    static boolean allowedAll(AppSettings settings, AppRuntime runtime) {
        return runtime == null || runtime.workspace().sources().stream()
                .allMatch(source -> allowed(settings, source.id()));
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
    static String knowledgeText(Selection selection, HoldingCounts counts, boolean complete, boolean allowed) {
        if (selection == null) {
            return NO_SELECTION;
        }
        StringBuilder text = new StringBuilder("Wissen: ").append(areaText(selection, counts));
        if (!complete) {
            // Ein Space ohne Inventur: der Cache kennt nur einzeln geladene Seiten, Unterseiten fehlen womöglich.
            text.append(" · ").append(ExportPanel.INCOMPLETE);
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
        // Letzte Stufe für sehr schmale Leisten oder breite Schriften: die (noch gesperrte) Websuche weicht.
        web.setVisible(stage < 3);
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
        JPanel chip = new JPanel(new BorderLayout(4, 0));
        chip.setOpaque(true);
        chip.setBackground(Comic.SURFACE);
        chip.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Comic.LINE),
                BorderFactory.createEmptyBorder(1, 6, 1, 2)));
        JLabel name = new JLabel(file.getFileName().toString());
        name.setFont(Comic.regular(12f));
        name.setForeground(Comic.INK);
        name.setToolTipText(file.toString());
        ComposerButton remove = ComposerButton.iconButton(ComposerIcons.close(), "Anhang entfernen");
        remove.addActionListener(e -> {
            attachments.remove(file);
            chips.remove(chip);
            chips.setVisible(!attachments.isEmpty());
            chips.revalidate();
            composer.repaint();
        });
        chip.add(name, BorderLayout.CENTER);
        chip.add(remove, BorderLayout.EAST);
        chips.add(chip);
        chips.setVisible(true);
        chips.revalidate();
        composer.repaint();
        attach.setToolTipText(ATTACH + " (" + attachments.size() + " vorgemerkt)");
    }

    /**
     * Senden (Enter): die Nachricht erscheint als Sprechblase, der Turn holt das Wissen aus dem Bestand und streamt die
     * Antwort in eine zweite Blase; der Knopf heißt solange „Stopp“. Dateien aus den Chips gehen als Text mit, wenn
     * sie lesbarer Text sind. Läuft schon ein Turn, bricht Senden ihn ab.
     */
    void send() {
        if (turn != null) {
            turn.cancel();
            return;
        }
        String text = editor.getText().strip();
        if (text.isEmpty() || !send.isEnabled()) {
            return;
        }
        ToolScope custom = toolScope == null ? null : toolScope.get();
        if (custom != null) {
            submit(withAttachments(text), null, custom.area(), ChatTurn.Kind.QUESTION, custom);
            return;
        }
        submit(withAttachments(text), resolved, areaText(selection, resolved == null ? null : resolved.counts()),
                ChatTurn.Kind.QUESTION);
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
        if (turn != null || !send.isEnabled()) {
            return;
        }
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            status.setText(NO_RUNTIME);
            return;
        }
        var port = runtime.workspace().aiChat();
        String modelName = context.settings() == null || context.settings().ai() == null ? null
                : context.settings().ai().chatModel();
        if (port == null || modelName == null || modelName.isBlank()) {
            status.setText(NO_CHAT_MODEL);
            return;
        }
        // Nur die Seite statt der Auswahl samt Unterseiten ist ein eigener Bereich.
        String scopeKey = custom != null ? custom.scopeKey() : scopeKey(node, scope != resolved);
        addAreaMark(lastQuestion(chat), area, scopeKey);
        addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du", question);
        long now = System.currentTimeMillis();
        if (home == null) {
            home = store.folder(node);
        }
        chat = chat.plus(ChatRecord.Message.user(question, now, area, scopeKey), now);
        List<ChatRecord.Message> history = chat.messages().subList(0, chat.messages().size() - 1);
        store.save(home, chat);
        showChatName();
        editor.setText("");
        attachments.clear();
        chips.removeAll();
        chips.setVisible(false);
        attach.setToolTipText(ATTACH);
        status.setText(SEARCHING);
        composer.revalidate();
        composer.repaint();
        answering = addAnswer("…");
        ChatRecord target = chat;
        Path targetNode = home;
        TurnListener listener = new TurnListener(target, targetNode, answering, answeringSources);
        listening = listener;
        turn = new ChatTurn(runtime.workspace(), port, modelName, effortValue(), history, question, scope, area, kind,
                listener, SwingUtilities::invokeLater);
        if (custom != null) {
            // #1151: dieselbe Unterhaltung, aber die Werkzeuge der Perspektive (Command Deck: Maskenassistent).
            turn.withToolset(custom.tools(), custom.system());
        } else {
            // ADR 0033: Was offen ist, sieht das Modell; den Rest des Bereichs holt es sich per Werkzeug.
            Selection open = selection;
            turn.knowledgeByTools(open == null || open.pageId() == null ? null
                    : new com.aresstack.spaceharvester.application.snapshot.PageKey(open.sourceId(),
                            open.pageId()));
            turn.releasing(releases(runtime, open));
            // R2/C1: Der Chat folgt der Suchmethode „Nachbarseiten“ der Suchoptionen, wie sie beim Senden steht.
            turn.searchingNeighbors(com.aresstack.spaceharvester.app.runtime.SearchMethods.user().neighbors());
            // K5a: ebenso der Suchmethode „Synonyme der Fachbegriffe“.
            turn.searchingGlossarySynonyms(
                    com.aresstack.spaceharvester.app.runtime.SearchMethods.user().glossarySynonyms());
        }
        listener.turn = turn;
        send.setText(STOP);
        send.setIcon(ComposerIcons.stop());
        updateHeaderActions();
        send.setToolTipText("Antwort abbrechen");
        turn.start();
    }

    /** {@code low}, {@code medium}, {@code high} aus der Wahl im Composer. */
    private String effortValue() {
        return switch (EFFORTS.indexOf(effortLevel)) {
            case 0 -> "low";
            case 2 -> "high";
            default -> "medium";
        };
    }

    /** Hängt lesbare Textdateien aus den Chips an die Nachricht; Binäres wird nur genannt. */
    private String withAttachments(String text) {
        if (attachments.isEmpty()) {
            return text;
        }
        StringBuilder b = new StringBuilder(text);
        for (Path file : attachments) {
            b.append("\n\n--- Anhang: ").append(file.getFileName()).append(" ---\n");
            try {
                String content = Files.readString(file, StandardCharsets.UTF_8);
                b.append(ChatKnowledge.excerpt(content, ChatKnowledge.MAX_PAGE_CHARS));
            } catch (java.io.IOException | RuntimeException unreadable) {
                b.append("(nicht als Text lesbar)");
            }
        }
        return b.toString();
    }

    /** Nimmt die Ereignisse eines Turns entgegen; nach dem Ende speichert er den Chat, zu dem der Turn gehört. */
    private final class TurnListener implements ChatTurn.Listener {

        private final ChatRecord started;
        private final Path startedNode;
        private final AssistantMarkdownBubble bubble;
        private final SourceLinks links;
        private final StringBuilder text = new StringBuilder();
        /** Die nummerierten Quellen dieser Antwort, wie zuletzt gemeldet; gehen mit der Antwort in den Verlauf. */
        private List<ChatRecord.Source> sources = List.of();
        private final java.awt.event.ComponentListener follow;
        ChatTurn turn;
        boolean detached;

        TurnListener(ChatRecord started, Path startedNode, AssistantMarkdownBubble bubble, SourceLinks links) {
            this.started = started;
            this.startedNode = startedNode;
            this.bubble = bubble;
            this.links = links;
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
            MarkdownMessageView view = bubble.view();
            if (view.isStreaming()) {
                view.finishStreaming();
            }
            view.setMarkdown(markdown);
            bubble.revalidate();
            bubble.repaint();
        }

        @Override
        public void loading() {
            if (text.isEmpty()) {
                bubble.view().setMarkdown(MODEL_LOADING);
            }
            if (!detached) {
                status.setText(MODEL_LOADING);
            }
        }

        @Override
        public void released(int count) {
            if (!detached) {
                status.setText(status.getText().strip() + " · " + Texts.count(count, "Seite", "Seiten")
                        + " für KI freigegeben");
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
            if (!detached) {
                status.setText(ANSWERING + " · " + what);
            }
        }

        @Override
        public void context(ChatKnowledge.Context context) {
            sources = context.accepted().stream().map(ChatRecord.Source::of).toList();
            // Die Quellen stehen schon während der Antwort unter der Blase und wachsen mit jeder neuen.
            links.show(sources);
            if (!detached) {
                status.setText(context.isEmpty() ? ANSWERING + " · ohne Auszüge aus dem Bestand"
                        : ANSWERING + " · " + Texts.count(context.accepted().size(), "Auszug", "Auszüge")
                        + " aus dem Bestand");
            }
        }

        @Override
        public void delta(String delta) {
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
        public void completed(String full) {
            show(text.isEmpty() ? full : text.toString());
            if (!detached) {
                scrollDown();
            }
            boolean shown = !detached;
            finish(ChatRecord.Message.assistant(full, false, System.currentTimeMillis(), sources), " ");
            if (shown) {
                answerObserver.run();
            }
        }

        @Override
        public void failed(String message) {
            show(message);
            bubble.setHeaderColor(Comic.RED);
            // Eine gescheiterte Antwort wird nicht gespeichert (wie im EAC); die Frage bleibt im Chat.
            boolean shown = !detached;
            finish(null, message.lines().findFirst().orElse(" "));
            if (shown) {
                answerObserver.run();
            }
        }

        @Override
        public void cancelled(String partial) {
            show(partial.isEmpty() ? CANCELLED : partial + "\n\n" + CANCELLED);
            finish(partial.isEmpty() ? null
                    : ChatRecord.Message.assistant(partial, true, System.currentTimeMillis(), sources), CANCELLED);
        }

        private void finish(ChatRecord.Message answer, String note) {
            // Nach dem letzten Layout-Durchgang den Mitroll-Hörer lösen, damit die Blase den Zug nicht festhält
            // (Codex-Befund zu #967).
            SwingUtilities.invokeLater(() -> bubble.removeComponentListener(follow));
            ChatRecord next = started;
            if (answer != null) {
                next = store.load(startedNode, started.id());
                next = (next == null ? started : next).plus(answer, answer.at());
                store.save(startedNode, next);
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

            @Override
            public int selection() {
                return current == null ? 0 : runtime.workspace().releasePagesForChat(current.toCore(runtime)).size();
            }
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

    private SpeechBubblePanel addBubble(BubbleSide side, Color background, Color foreground, String header,
                                        String text) {
        SpeechBubblePanel bubble = new SpeechBubblePanel(side, background, foreground, header, text);
        bubble.setMaximumBubbleWidth(640);
        BubbleMessageRow row = new BubbleMessageRow(bubble, side);
        row.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return bubble;
    }

    /** Eine Antwortblase mit Markdown-Körper wie im EAC ({@code ChatTranscriptPanel.createMarkdownBubble}). */
    private AssistantMarkdownBubble addAnswer(String markdown) {
        if (mermaid == null) {
            mermaid = CachingMermaidImageRenderer.forChat();
        }
        MarkdownMessageView view = new MarkdownMessageView(MarkdownTheme.forBubble(bubbles.getAssistantBackground(),
                bubbles.getAssistantForeground()), linkOpener, mermaid);
        view.setMarkdown(markdown);
        AssistantMarkdownBubble bubble = new AssistantMarkdownBubble(BubbleSide.LEFT, bubbles, ASSISTANT, view);
        BubbleMessageRow row = new BubbleMessageRow(bubble, BubbleSide.LEFT);
        row.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(row);
        answeringSources = new SourceLinks(this::openSource);
        answeringSources.setAlignmentX(LEFT_ALIGNMENT);
        transcript.add(answeringSources);
        transcript.revalidate();
        transcript.repaint();
        scrollDown();
        return bubble;
    }

    private void scrollDown() {
        SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    // ------------------------------------------------------------------ Chats des Bereichs

    /** Ein neuer, leerer Chat in diesem Bereich; ein laufender Turn schreibt seinen alten Chat zu Ende. */
    private void newChat() {
        if (turn != null) {
            detachTurn();
        }
        open(ChatRecord.fresh(System.currentTimeMillis()), store.folder(node));
        editor.requestFocusInWindow();
    }

    /** Zeigt einen Chat des gewählten Bereichs. */
    private void open(ChatRecord record) {
        open(record, store.folder(node));
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
        transcript.removeAll();
        transcript.add(emptyNote);
        emptyNote.setVisible(true);
        for (ChatRecord.Message message : record.messages()) {
            if (message.user()) {
                addAreaMark(asked, message.area(), message.scope());
                asked = message.area() == null ? asked : message;
                addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du",
                        message.text());
            } else {
                addAnswer(message.cancelled() ? message.text() + "\n\n" + CANCELLED : message.text());
                answeringSources.show(message.sources());
            }
        }
        transcript.revalidate();
        transcript.repaint();
        status.setText(" ");
        editor.setText("");
        showChatName();
    }

    private void showChatName() {
        chatName.setText(chat.title());
        chats.setEnabled(node != null || home != null);
        updateHeaderActions();
    }

    /** Löschen geht nur bei einem gespeicherten Chat (einer mit Nachrichten) und nicht während einer Antwort. */
    private void updateHeaderActions() {
        newChat.setEnabled(node != null);
        deleteChat.setEnabled(home != null && !chat.isEmpty() && turn == null);
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
        Path here = store.folder(node);
        List<ChatStore.Entry> all = store.all();
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
        local.forEach(entry -> items.add(chatItem(entry)));
        if (!others.isEmpty()) {
            items.add(new JPopupMenu.Separator());
            items.add(menuHeading(CHATS_ELSEWHERE));
            others.forEach(entry -> items.add(chatItem(entry)));
        }
        return items;
    }

    private JMenuItem chatItem(ChatStore.Entry entry) {
        ChatRecord record = entry.record();
        boolean current = record.id().equals(chat.id());
        JMenuItem item = new JMenuItem((current ? "✓ " : "    ") + record.title());
        String area = lastArea(record);
        item.setToolTipText(Texts.count(record.messages().size(), "Nachricht", "Nachrichten")
                + (area == null ? "" : " · zuletzt: " + areaName(area)));
        item.addActionListener(e -> {
            if (!current) {
                open(record, entry.folder());
            }
        });
        return item;
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
     * Die Kennung eines Bereichs: Quelle, Space und Seite, dazu ob nur die Seite ohne Unterseiten gemeint ist. Zwei
     * gleich benannte Spaces oder Seiten sind damit verschiedene Bereiche; {@code null} ohne Auswahl.
     */
    static String scopeKey(ChatNode node, boolean pageOnly) {
        if (node == null) {
            return null;
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
     */
    static final class SourceLinks extends JPanel {

        static final int TITLE_LENGTH = 48;

        private final java.util.function.Consumer<ChatRecord.Source> open;
        private List<ChatRecord.Source> shown = List.of();

        SourceLinks(java.util.function.Consumer<ChatRecord.Source> open) {
            super(new com.aresstack.spaceharvester.app.ui.controls.WrapLayout(FlowLayout.LEFT, 10, 2));
            this.open = open;
            setOpaque(false);
            setBorder(BorderFactory.createEmptyBorder(0, 14, 6, 14));
            setVisible(false);
        }

        void show(List<ChatRecord.Source> sources) {
            if (shown.equals(sources)) {
                return;
            }
            shown = List.copyOf(sources);
            removeAll();
            for (int index = 0; index < shown.size(); index++) {
                add(entry(index + 1, shown.get(index)));
            }
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
            for (Component entry : getComponents()) {
                texts.add(entry instanceof javax.swing.AbstractButton button ? button.getText()
                        : ((JLabel) entry).getText());
            }
            return texts;
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
        delete.setEnabled(!chat.isEmpty() && turn == null);
        delete.addActionListener(ignored -> deleteChat());
        menu.add(delete);
        menu.show(chatName, e.getX(), e.getY());
    }

    void startRename() {
        chatNameEditor.setText(chat.title());
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

    /** Löscht den aktuellen Chat nach „wirklich“-Rückfrage (Ja/Nein) und zeigt den nächsten des Bereichs. */
    private void deleteChat() {
        if (!Dialogs.confirm(this, DELETE_TITLE, DELETE_QUESTION)) {
            return;
        }
        store.delete(home, chat.id());
        List<ChatRecord> saved = store.list(node);
        open(saved.isEmpty() ? ChatRecord.fresh(System.currentTimeMillis()) : saved.get(0));
    }

    // ------------------------------------------------------------------ für Tests

    String knowledge() {
        return knowledge.getText();
    }

    String status() {
        return status.getText();
    }

    JComponent editor() {
        return editor;
    }

    int bubbles() {
        int count = 0;
        for (Component row : transcript.getComponents()) {
            if (row instanceof BubbleMessageRow) {
                count++;
            }
        }
        return count;
    }

    List<Path> attachments() {
        return List.copyOf(attachments);
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
