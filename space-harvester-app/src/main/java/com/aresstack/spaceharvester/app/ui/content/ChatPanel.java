package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.enterpriseai.ui.comic.bubble.BubbleMessageRow;
import com.aresstack.enterpriseai.ui.comic.bubble.BubblePalette;
import com.aresstack.enterpriseai.ui.comic.bubble.BubbleSide;
import com.aresstack.enterpriseai.ui.comic.bubble.SpeechBubblePanel;
import com.aresstack.enterpriseai.ui.comic.control.ComposerButton;
import com.aresstack.enterpriseai.ui.comic.control.PlaceholderTextArea;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.settings.AppSettings;
import com.aresstack.spaceharvester.app.ui.AppContext;
import com.aresstack.spaceharvester.app.ui.Background;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.Downloads;
import com.aresstack.spaceharvester.app.ui.Feedback;
import com.aresstack.spaceharvester.app.ui.Texts;
import com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection;
import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingsListener;
import com.aresstack.spaceharvester.application.holdings.PageSelection;
import com.aresstack.spaceharvester.application.observe.Subscription;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComboBox;
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
import java.awt.event.KeyEvent;
import java.awt.geom.RoundRectangle2D;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Reiter „Chat“ (Angelo, 13.11.2026), vorerst als Click-Dummy nach {@code ChatShellPanel} und
 * {@code ChatComposerPanel} des Enterprise AI Client, im Design von Space Harvester: oben die Chats und „Neuer Chat“,
 * der Verlauf als Sprechblasen, darüber dem Composer die stille Statuszeile des Wissens (sie folgt der Auswahl links
 * und ist nicht abwählbar), unten die eine abgerundete Composer-Fläche. Senden zeigt nur „Chat folgt“.
 *
 * <p>Das Wissen ist, was links gewählt ist: Quelle, Space oder Seite samt Unterseiten. Die Seitenzahl kommt aus der
 * Bestandsübersicht des Core ({@code Workspace.holdings}, ADR 0027) und frischt sich über deren Meldungen auf, ohne
 * Zeitgeber. Ist der Umfang nicht (ganz) geladen, steht „Laden“ daneben. Der Chat ist die einzige Ausnahme der KI-Freigabe
 * (Angelo, 13.11.2026): eine nicht freigegebene Quelle wird genannt, nicht gesperrt.
 */
public final class ChatPanel extends JPanel {

    static final String NEW_CHAT = "Neuer Chat";
    static final String PLACEHOLDER = "Nachricht…";
    static final String SEND = "Senden";
    static final String FOLLOWS = "Chat folgt";
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
    private final JComboBox<String> chats = Comic.combo();
    private final ComposerButton newChat = Comic.button(NEW_CHAT, "Einen neuen Chat beginnen");
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
    private Subscription subscription;
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

        JLabel heading = new JLabel("Chat");
        heading.setFont(Comic.semiBold(16f));
        heading.setForeground(Comic.INK);
        chats.addItem(NEW_CHAT);
        chats.setToolTipText("Chats");
        newChat.addActionListener(e -> newChat());
        JPanel top = new JPanel(new BorderLayout(8, 0));
        top.setOpaque(false);
        top.add(heading, BorderLayout.CENTER);
        top.add(Comic.row(chats, newChat), BorderLayout.EAST);
        add(top, BorderLayout.NORTH);

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
        renderKnowledge(null, null);
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
        refresh();
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
        if (subscription != null) {
            subscription.close();
            subscription = null;
        }
        subscribed = runtime;
        subscribedKey = key;
        if (key == null) {
            return;
        }
        try {
            subscription = runtime.workspace().subscribeHoldings(current.sourceId(),
                    current.spaceKey() == null ? "" : current.spaceKey(), new HoldingsListener() {
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
                });
        } catch (RuntimeException gone) {
            // Die gewählte Quelle gibt es in dieser Laufzeit nicht mehr (etwa entfernt): der Baum räumt die Auswahl
            // gleich weg; bis dahin ohne Meldungen, das Lesen meldet den Fehler selbst.
            subscription = null;
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
            renderKnowledge(current, null);
            return;
        }
        Background.run(() -> runtime.workspace().resolveSelection(current.toCore()), resolved -> {
            if (ticket == request) {
                renderKnowledge(current, resolved);
            }
        }, error -> {
            if (ticket == request) {
                renderKnowledge(current, null);
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

    private void renderKnowledge(Selection current, PageSelection resolved) {
        this.resolved = resolved;
        boolean allowed = current == null || allowed(context.settings(), current.sourceId());
        HoldingCounts counts = resolved == null ? null : resolved.counts();
        knowledge.setText(knowledgeText(current, counts, resolved == null || resolved.complete(), allowed));
        load.setVisible(current != null && (counts == null || counts.stored() < counts.pages()
                || counts.pages() == 0));
        send.setEnabled(current != null);
        if (onResolved != null) {
            onResolved.accept(resolved);
        }
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
        StringBuilder text = new StringBuilder("Wissen: ");
        if (selection.pageId() != null) {
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
        if (!complete) {
            // Ein Space ohne Inventur: der Cache kennt nur einzeln geladene Seiten, Unterseiten fehlen womöglich.
            text.append(" · ").append(ExportPanel.INCOMPLETE);
        }
        if (!allowed) {
            text.append(" · ").append(NOT_ALLOWED);
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
                feedback.working(current.page() ? "Download der Seite samt Unterseiten gestartet"
                        : current.spaceKey() == null ? "Download aller Spaces gestartet"
                        : "Download des Space gestartet");
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
        menu.show(model, 0, model.getHeight());
    }

    private void showEffortMenu() {
        JPopupMenu menu = new JPopupMenu();
        for (String level : EFFORTS) {
            JMenuItem item = new JMenuItem((level.equals(effort.getText()) ? "✓ " : "    ") + level);
            item.addActionListener(e -> effort.setText(level));
            menu.add(item);
        }
        menu.show(effort, 0, effort.getHeight());
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

    /** Senden im Click-Dummy: die Nachricht erscheint als Sprechblase, die Statuszeile sagt „Chat folgt“. */
    void send() {
        String text = editor.getText().strip();
        if (text.isEmpty() || !send.isEnabled()) {
            return;
        }
        addBubble(BubbleSide.RIGHT, bubbles.getUserBackground(), bubbles.getUserForeground(), "Du", text);
        editor.setText("");
        attachments.clear();
        chips.removeAll();
        chips.setVisible(false);
        attach.setToolTipText(ATTACH);
        status.setText(FOLLOWS);
        composer.revalidate();
        composer.repaint();
        release();
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
        Background.run(() -> runtime.workspace().releasePagesForChat(current.toCore()), released -> {
            if (!released.isEmpty() && Objects.equals(current, selection)) {
                status.setText(FOLLOWS + " · " + Texts.count(released.size(), "Seite", "Seiten")
                        + " für KI freigegeben");
            }
        }, error -> {
            if (Objects.equals(current, selection)) {
                feedback.problem(error);
            }
        });
    }

    private void addBubble(BubbleSide side, Color background, Color foreground, String header, String text) {
        SpeechBubblePanel bubble = new SpeechBubblePanel(side, background, foreground, header, text);
        bubble.setMaximumBubbleWidth(640);
        BubbleMessageRow row = new BubbleMessageRow(bubble, side);
        row.setAlignmentX(LEFT_ALIGNMENT);
        emptyNote.setVisible(false);
        transcript.add(row);
        transcript.revalidate();
        transcript.repaint();
        SwingUtilities.invokeLater(() -> {
            javax.swing.JScrollBar bar = transcriptScroll.getVerticalScrollBar();
            bar.setValue(bar.getMaximum());
        });
    }

    private void newChat() {
        transcript.removeAll();
        transcript.add(emptyNote);
        emptyNote.setVisible(true);
        transcript.revalidate();
        transcript.repaint();
        status.setText(" ");
        editor.setText("");
        editor.requestFocusInWindow();
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
}
