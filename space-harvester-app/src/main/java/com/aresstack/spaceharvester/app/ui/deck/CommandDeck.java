package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.pal.PalNdvConnector;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.openterm.OpenTermConnector;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingConstants;

/**
 * Die Perspektive „Command Deck“ (#1142): manuell bediente Host-Systeme. Oben neben dem Titel die fünf Arbeitsbereiche
 * Terminal, FTP, NDV, BetaView, Dateien (in dieser Reihenfolge), in der Mitte der gewählte Bereich. Die rechte
 * Seitenleiste und die Statuszeile stellt das Hauptfenster; das Deck baut keine eigene. Terminal-Sitzungen sind keine
 * Wissensquellen und werden nie indiziert.
 */
public final class CommandDeck {

    private final ExecutorService connecting = Executors.newThreadPerTaskExecutor(
            Thread.ofVirtual().name("command-deck-", 0).factory());
    private final TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(), connecting);
    private final CardLayout cards = new CardLayout();
    private final JPanel workspace = new JPanel(cards);
    private final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final Map<DeckArea, ComposerToggleButton> buttons = new EnumMap<>(DeckArea.class);
    private final NdvSessions ndvSessions = new NdvSessions(new PalNdvConnector(), connecting);
    private final TerminalWorkspace terminal;
    private final NdvWorkspace ndv;
    private final Perspective perspective;
    private DeckArea area = DeckArea.TERMINAL;

    /** Ohne Passwortspeicher (Tests). */
    public CommandDeck() {
        this(com.aresstack.spaceharvester.deck.access.ServerAccounts.NONE);
    }

    /**
     * @param accounts Host-Zugänge (KeePass), gemeinsam für Terminal, NDV und später FTP
     */
    public CommandDeck(com.aresstack.spaceharvester.deck.access.ServerAccounts accounts) {
        terminal = new TerminalWorkspace(sessions, accounts);
        ndv = new NdvWorkspace(ndvSessions, accounts);
        workspace.setBackground(Comic.SURFACE);
        navigation.setOpaque(false);
        // Abstand zum Titel wie die Icon-Leiste des Space Harvesters.
        navigation.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
        ButtonGroup group = new ButtonGroup();
        for (DeckArea candidate : DeckArea.values()) {
            ComposerToggleButton button = new ComposerToggleButton(candidate.icon(), null,
                    candidate.title() + " – " + candidate.hint() + (candidate.ready() ? "" : " (folgt)"));
            button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
            button.getAccessibleContext().setAccessibleName(candidate.title());
            button.addActionListener(e -> show(candidate));
            group.add(button);
            buttons.put(candidate, button);
            navigation.add(button);
            workspace.add(switch (candidate) {
                case TERMINAL -> terminal;
                case NDV -> ndv;
                default -> placeholder(candidate);
            }, candidate.name());
        }
        show(DeckArea.TERMINAL);
        perspective = Perspective.of(Perspectives.COMMAND_DECK, "Command Deck",
                "Interaktiv mit Host-Systemen arbeiten: Terminal, FTP, NDV, BetaView, Dateien", true, navigation,
                workspace, null);
    }

    public Perspective perspective() {
        return perspective;
    }

    /**
     * Was der globale Chat in dieser Perspektive bekommt (#1151): die Werkzeuge des Maskenassistenten für die gerade
     * gewählte Terminal-Sitzung – Maske lesen, Felder lokal ausfüllen, Schreibmarke bewegen, nie abschicken. Beim
     * Senden ausgewertet; ohne Sitzung sagen die Werkzeuge das dem Modell.
     */
    public com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope chatScope() {
        com.aresstack.spaceharvester.deck.terminal.TerminalSession session = terminal.selected();
        String area = "Command Deck › Terminal" + (session == null ? " (keine Sitzung gewählt)"
                : " " + session.profile().label());
        String key = "command-deck/terminal" + (session == null ? "" : "/" + session.profile().label());
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope(area, key,
                new com.aresstack.spaceharvester.app.chat.TerminalChatTools(session, terminal::selected),
                com.aresstack.spaceharvester.app.chat.TerminalChatTools.systemMessage(area));
    }

    public TerminalSessions sessions() {
        return sessions;
    }

    /** Die NDV-Sitzungen; sie überleben jeden Wechsel von Bereich und Perspektive. */
    public NdvSessions ndvSessions() {
        return ndvSessions;
    }

    /** Zeigt einen Arbeitsbereich; offene Sitzungen der anderen bleiben, wie sie sind. */
    public void show(DeckArea next) {
        area = next;
        buttons.get(next).setSelected(true);
        cards.show(workspace, next.name());
    }

    public DeckArea area() {
        return area;
    }

    /** Trennt alle Sitzungen (Programmende). */
    public void shutdown() {
        sessions.closeAll();
        ndvSessions.closeAll();
        connecting.shutdownNow();
    }

    TerminalWorkspace terminal() {
        return terminal;
    }

    NdvWorkspace ndv() {
        return ndv;
    }

    JComponent navigation() {
        return navigation;
    }

    /** Ein Bereich, der in einem späteren Schritt kommt: nur Name und was dort hinein gehört. */
    private static JComponent placeholder(DeckArea area) {
        JPanel panel = new JPanel(new java.awt.GridBagLayout());
        panel.setBackground(Comic.SURFACE);
        JPanel text = new JPanel(new BorderLayout(0, 6));
        text.setOpaque(false);
        javax.swing.JLabel title = new javax.swing.JLabel(area.title(), SwingConstants.CENTER);
        title.setFont(Comic.semiBold(20f));
        title.setForeground(Comic.INK);
        javax.swing.JLabel hint = new javax.swing.JLabel(area.hint() + " – folgt in einem nächsten Schritt.",
                SwingConstants.CENTER);
        hint.setFont(Comic.regular(13f));
        hint.setForeground(Comic.MUTED);
        text.add(title, BorderLayout.NORTH);
        text.add(hint, BorderLayout.CENTER);
        panel.add(text);
        panel.getAccessibleContext().setAccessibleName(area.title());
        return panel;
    }
}
