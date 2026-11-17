package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleMessageRow;
import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.PlaceholderTextArea;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.chat.ChatKnowledge;
import com.aresstack.spaceharvester.app.chat.ChatNode;
import com.aresstack.spaceharvester.app.chat.ChatRecord;
import com.aresstack.spaceharvester.app.chat.ChatStore;
import com.aresstack.spaceharvester.app.chat.ChatTurn;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
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
    static final String SEARCHING = "Wissen wird gesucht …";
    static final String CANCELLED = "Abgebrochen";
    static final String DOWNLOAD_DONE = "abgeschlossen";
    static final String DOWNLOAD_FAILED = "fehlgeschlagen";
    static final String ATTACH = "Dateien anhängen";
    static final String DICTATE = "Spracheingabe folgt";
    static final String WEB = "Websuche folgt";
    static final String NO_MODEL = "Kein Chat-Modell";
    static final String MODEL_TIP = "Chat-Modell aus Einstellungen › KI";
    static final String NO_SELECTION = "Links eine Wissensquelle, einen Space oder eine Seite wählen";
    static final String NOT_LOADED = "Seiten noch nicht erfasst";
    static final String LOAD = "Laden";
    static final String NOT_ALLOWED = "Quelle nicht für KI freigegeben; Senden gibt die gewählten Seiten dauerhaft "
            + "frei (Einstellungen › Sicherheit)";
    static final String EMPTY_TRANSCRIPT = "Noch keine Nachrichten";
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
    /** Der aktuelle Chat; immer gesetzt, anfangs leer und ungespeichert. */
    private ChatRecord chat = ChatRecord.fresh(System.currentTimeMillis());
    /** Der laufende Turn oder {@code null}. */
    private ChatTurn turn;
    /** Der Empfänger des laufenden Turns; beim Loslassen wird er abgemeldet, damit er nur noch speichert. */
    private TurnListener listening;
    /** Ob die Auswahl links im Core aufgelöst ist; bis dahin sendet der Chat nicht (sonst gälten alte Seiten). */
    private boolean settled;
    /** Ergebnisse der verfolgten Aufträge, gemeldet erst, wenn alle geendet haben. */
    private final List<JobStatus> jobOutcomes = new ArrayList<>();
    private SpeechBubblePanel answering;
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
            "Denkaufwand des Modells");
    private final ComposerButton web = ComposerButton.iconButton(ChatIcons.globe(), WEB);
    private final ComposerButton attach = ComposerButton.iconButton(ComposerIcons.paperclip(), ATTACH);
    private final ComposerButton mic = ComposerButton.iconButton(ComposerIcons.microphone(), DICTATE);
    private final ComposerButton send = ComposerButton.primary(ComposerIcons.send(), SEND, Comic.BLUE,
            "Senden (Enter)");
    /** Die Statuszeile des Composers („Chat folgt“); leer unsichtbar. */
    private final JLabel status = Comic.muted(" ");
    private final JPanel composer;
    private boolean editorFocused;

    private Selection selection;
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
        JPanel transcriptHolder = new JPanel(new BorderLayout());
        transcriptHolder.setOpaque(false);
        transcriptHolder.add(transcript, BorderLayout.NORTH);
        transcriptScroll = Comic.scroll(transcriptHolder);
        transcriptScroll.setBorder(BorderFactory.createEmptyBorder());
        add(transcriptScroll, BorderLayout.CENTER);

        composer = buildComposer();
        JPanel knowledgeRow = new JPanel(new BorderLayout(8, 0));
        knowledgeRow.setOpaque(false);
        knowledge.setForeground(Comic.MUTED);
        knowledgeRow.add(knowledge, BorderLayout.CENTER);
        knowledgeRow.add(load, BorderLayout.EAST);
        load.setVisible(false);
        load.addActionListener(e -> load());
        JPanel south = new JPanel(new BorderLayout(0, 4));
        south.setOpaque(false);
        south.add(feedback, BorderLayout.NORTH);
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
                    g2.setColor(editorFocused ? Comic.BLUE : Comic.LINE);
                    g2.setStroke(new BasicStroke(editorFocused ? 1.6f : 1f));
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
        JScrollPane editorScroll = new JScrollPane(editor, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        editorScroll.setOpaque(false);
        editorScroll.getViewport().setOpaque(false);
        editorScroll.setBorder(BorderFactory.createEmptyBorder());
        editorScroll.setPreferredSize(new Dimension(520, 64));

        chips.setOpaque(false);
        chips.setVisible(false);

        model.addActionListener(e -> showModelMenu());
        effort.addActionListener(e -> showEffortMenu());
        web.setEnabled(false);
        mic.setEnabled(false);
        attach.addActionListener(e -> chooseAttachments());
        send.addActionListener(e -> send());
        JPanel west = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        west.setOpaque(false);
        west.add(model);
        west.add(effort);
        JPanel east = new JPanel();
        east.setOpaque(false);
        east.setLayout(new BoxLayout(east, BoxLayout.X_AXIS));
        east.add(Box.createHorizontalGlue());
        east.add(web);
        east.add(Box.createHorizontalStrut(4));
        east.add(attach);
        east.add(Box.createHorizontalStrut(4));
        east.add(mic);
        east.add(Box.createHorizontalStrut(4));
        east.add(send);
        JPanel footer = new JPanel(new BorderLayout(8, 0));
        footer.setOpaque(false);
        footer.add(west, BorderLayout.WEST);
        footer.add(east, BorderLayout.EAST);

        JPanel body = new JPanel(new BorderLayout(0, 4));
        body.setOpaque(false);
        body.setBorder(BorderFactory.createEmptyBorder(9, 11, 8, 8));
        body.add(chips, BorderLayout.NORTH);
        body.add(editorScroll, BorderLayout.CENTER);
        body.add(footer, BorderLayout.SOUTH);
        status.setBorder(BorderFactory.createEmptyBorder(0, 12, 2, 0));
        surface.add(status, BorderLayout.NORTH);
        surface.add(body, BorderLayout.CENTER);
        return surface;
    }

    // ------------------------------------------------------------------ Auswahl und Wissen

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
     * Ein anderer Bereich: Ein laufender Turn schreibt seinen Chat noch zu Ende (er hängt am Chat, nicht am Reiter),
     * die Ansicht zeigt den zuletzt geänderten Chat des neuen Bereichs oder einen leeren.
     */
    private void switchNode(ChatNode next) {
        if (turn != null) {
            // Die Antwort läuft weiter und wird gespeichert, sobald sie fertig ist; die Ansicht lässt sie los.
            detachTurn();
        }
        node = next;
        List<ChatRecord> saved = store.list(node);
        open(saved.isEmpty() ? ChatRecord.fresh(System.currentTimeMillis()) : saved.get(0));
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
        send.setEnabled(current != null && settled);
        if (onResolved != null) {
            onResolved.accept(resolved);
        }
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
        model.setText(name == null || name.isBlank() ? NO_MODEL : name);
        model.setToolTipText(MODEL_TIP + (name == null || name.isBlank() ? "" : ": " + name));
    }

    private void showModelMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem current = new JMenuItem(model.getText());
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
            JMenuItem item = new JMenuItem((level.equals(effort.getText()) ? "✓ " : "    ") + level);
            item.addActionListener(e -> effort.setText(level));
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
        String question = withAttachments(text);
        addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du", text);
        long now = System.currentTimeMillis();
        chat = chat.plus(ChatRecord.Message.user(question, now), now);
        List<ChatRecord.Message> history = chat.messages().subList(0, chat.messages().size() - 1);
        store.save(node, chat);
        showChatName();
        editor.setText("");
        attachments.clear();
        chips.removeAll();
        chips.setVisible(false);
        attach.setToolTipText(ATTACH);
        status.setText(SEARCHING);
        composer.revalidate();
        composer.repaint();
        release();
        answering = addBubble(BubbleSide.LEFT, bubbles.getAssistantBackground(), bubbles.getAssistantForeground(),
                ASSISTANT, "…");
        ChatRecord target = chat;
        ChatNode targetNode = node;
        TurnListener listener = new TurnListener(target, targetNode, answering);
        listening = listener;
        turn = new ChatTurn(runtime.workspace(), port, modelName, effortValue(), history, question, resolved,
                areaText(selection, resolved == null ? null : resolved.counts()), listener,
                SwingUtilities::invokeLater);
        listener.turn = turn;
        send.setText(STOP);
        send.setIcon(ComposerIcons.stop());
        updateHeaderActions();
        send.setToolTipText("Antwort abbrechen");
        turn.start();
    }

    /** {@code low}, {@code medium}, {@code high} aus der Wahl im Composer. */
    private String effortValue() {
        return switch (EFFORTS.indexOf(effort.getText())) {
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
        private final ChatNode startedNode;
        private final SpeechBubblePanel bubble;
        private final StringBuilder text = new StringBuilder();
        ChatTurn turn;
        boolean detached;

        TurnListener(ChatRecord started, ChatNode startedNode, SpeechBubblePanel bubble) {
            this.started = started;
            this.startedNode = startedNode;
            this.bubble = bubble;
        }

        @Override
        public void context(ChatKnowledge.Context context) {
            if (!detached) {
                status.setText(context.isEmpty() ? ANSWERING + " · ohne Auszüge aus dem Bestand"
                        : ANSWERING + " · " + Texts.count(context.accepted().size(), "Auszug", "Auszüge")
                        + " aus dem Bestand");
            }
        }

        @Override
        public void delta(String delta) {
            if (text.isEmpty()) {
                bubble.setText(delta);
            } else {
                bubble.appendText(delta);
            }
            text.append(delta);
            if (!detached) {
                scrollDown();
            }
        }

        @Override
        public void completed(String full) {
            if (text.isEmpty()) {
                bubble.setText(full);
            }
            finish(ChatRecord.Message.assistant(full, false, System.currentTimeMillis()), " ");
        }

        @Override
        public void failed(String message) {
            bubble.setText(message);
            bubble.setHeaderColor(Comic.RED);
            // Eine gescheiterte Antwort wird nicht gespeichert (wie im EAC); die Frage bleibt im Chat.
            finish(null, message.lines().findFirst().orElse(" "));
        }

        @Override
        public void cancelled(String partial) {
            bubble.setText(partial.isEmpty() ? CANCELLED : partial + "\n\n" + CANCELLED);
            finish(partial.isEmpty() ? null
                    : ChatRecord.Message.assistant(partial, true, System.currentTimeMillis()), CANCELLED);
        }

        private void finish(ChatRecord.Message answer, String note) {
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
            updateHeaderActions();
            send.setToolTipText("Senden (Enter)");
            showChatName();
        }
    }

    /**
     * Die Ausnahme der KI-Freigabe (ADR 0028, Angelo 13.11.2026): Senden trägt die lokal gespeicherten Seiten der
     * Auswahl in die Liste freigegebener Seiten ein, auch ohne Haken an der Quelle; sie stehen danach unter
     * Einstellungen › Sicherheit. Die Statuszeile nennt, wie viele neu dazukamen.
     */
    private void release() {
        AppRuntime runtime = context.runtime();
        Selection current = selection;
        if (runtime == null || current == null) {
            return;
        }
        Background.run(() -> runtime.workspace().releasePagesForChat(current.toCore(runtime)), released -> {
            if (!released.isEmpty() && Objects.equals(current, selection)) {
                status.setText(status.getText().strip() + " · " + Texts.count(released.size(), "Seite", "Seiten")
                        + " für KI freigegeben");
            }
        }, error -> {
            if (Objects.equals(current, selection)) {
                feedback.problem(error);
            }
        });
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
        open(ChatRecord.fresh(System.currentTimeMillis()));
        editor.requestFocusInWindow();
    }

    /** Zeigt einen Chat: Verlauf als Blasen, Name in der Kopfzeile. */
    private void open(ChatRecord record) {
        if (turn != null) {
            detachTurn();
        }
        chat = record;
        transcript.removeAll();
        transcript.add(emptyNote);
        emptyNote.setVisible(true);
        for (ChatRecord.Message message : record.messages()) {
            if (message.user()) {
                addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du",
                        message.text());
            } else {
                addBubble(BubbleSide.LEFT, bubbles.getAssistantBackground(), bubbles.getAssistantForeground(),
                        ASSISTANT, message.cancelled() ? message.text() + "\n\n" + CANCELLED : message.text());
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
        chats.setEnabled(node != null);
        updateHeaderActions();
    }

    /** Löschen geht nur bei einem gespeicherten Chat (einer mit Nachrichten) und nicht während einer Antwort. */
    private void updateHeaderActions() {
        newChat.setEnabled(node != null);
        deleteChat.setEnabled(node != null && !chat.isEmpty() && turn == null);
    }

    /** „Chat: ‹Name› ▾“ für die Mitte der Kopfzeile des Inhaltsbereichs. */
    public JComponent header() {
        return header;
    }

    /** Neuer Chat und Chat löschen als Symbole für die Kopfzeile des Inhaltsbereichs, rechts. */
    public JComponent headerActions() {
        return headerActions;
    }

    /** Der Pfeil: die Chats dieses Bereichs, zuletzt geänderte zuerst, der aktuelle mit Haken. */
    private void showChatsMenu() {
        JPopupMenu menu = new JPopupMenu();
        List<ChatRecord> saved = store.list(node);
        if (saved.isEmpty()) {
            JMenuItem none = new JMenuItem("Noch keine Chats in diesem Bereich");
            none.setEnabled(false);
            menu.add(none);
        }
        for (ChatRecord record : saved) {
            boolean current = record.id().equals(chat.id());
            JMenuItem item = new JMenuItem((current ? "✓ " : "    ") + record.title());
            item.setToolTipText(Texts.count(record.messages().size(), "Nachricht", "Nachrichten"));
            item.addActionListener(e -> {
                if (!current) {
                    open(record);
                }
            });
            menu.add(item);
        }
        PopupHover.clearOnClose(menu, chats).show(chats, 0, chats.getHeight());
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
                store.save(node, chat);
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
        store.delete(node, chat.id());
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
        return transcript.getComponentCount() - 1;
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
