package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewWorkspace;
import com.aresstack.spaceharvester.app.ui.perspective.Perspective;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSessions;
import com.aresstack.spaceharvester.deck.betaview.http.HttpBetaViewServer;
import com.aresstack.spaceharvester.deck.ftp.FtpSessions;
import com.aresstack.spaceharvester.deck.ftp.commonsnet.CommonsNetFtpConnector;
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
    /** Die Aktionsverzögerung (Anmeldung, Lesezeichen-Makro) kommt live aus Einstellungen › Terminal. */
    private final TerminalSessions sessions = new TerminalSessions(new OpenTermConnector(), connecting,
            new com.aresstack.spaceharvester.deck.terminal.LoginScript(() -> TerminalSettings.shared().actionDelay()));
    private final CardLayout cards = new CardLayout();
    private final JPanel workspace = new JPanel(cards);
    private final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final Map<DeckArea, ComposerToggleButton> buttons = new EnumMap<>(DeckArea.class);
    private final NdvSessions ndvSessions = new NdvSessions(new PalNdvConnector(), connecting);
    private final TerminalWorkspace terminal;
    private final BetaViewWorkspace betaView;
    /** Gemeinsamer Editor aller Bereiche (Dateien, NDV, später FTP); die Dokumente überleben jeden Wechsel. */
    private final com.aresstack.spaceharvester.deck.editor.EditorSessions editors =
            new com.aresstack.spaceharvester.deck.editor.EditorSessions(connecting);
    private final FilesWorkspace files;
    /** Freigegebene Ordner aus Einstellungen › Dateizugriff, als Orte im Bereich Dateien. */
    private volatile java.util.function.Supplier<java.util.List<java.nio.file.Path>> sharedFolders = java.util.List::of;
    private final FtpSessions ftpSessions = new FtpSessions(new CommonsNetFtpConnector(), connecting);
    private final FtpWorkspace ftp;
    /** Oben rechts in der Titelleiste: die Bedienelemente des gewählten Bereichs (FTP: Datasets | JES-Jobs). */
    private final JPanel tools = new JPanel(new BorderLayout());
    private final NdvWorkspace ndv;
    private final Perspective perspective;
    private DeckArea area = DeckArea.TERMINAL;

    /** Ohne Passwortspeicher und Proxy (Tests). */
    public CommandDeck() {
        this(ServerAccounts.NONE, BetaViewAccess.direct());
    }

    /**
     * @param accounts Host-Zugänge (KeePass), gemeinsam für Terminal, NDV und später FTP
     */
    public CommandDeck(ServerAccounts accounts) {
        this(accounts, BetaViewAccess.direct());
    }

    /**
     * @param accounts       Host-Zugänge (KeePass), gemeinsam für Terminal, NDV und später FTP
     * @param betaViewAccess Netzweg (Proxy, Zertifikate) der App für BetaView; Zugangsdaten kommen aus {@code accounts}
     */
    public CommandDeck(ServerAccounts accounts, BetaViewAccess betaViewAccess) {
        terminal = new TerminalWorkspace(sessions, accounts);
        betaView = new BetaViewWorkspace(new BetaViewSessions(new HttpBetaViewServer(betaViewAccess.http()),
                connecting), accounts);
        files = new FilesWorkspace(new com.aresstack.spaceharvester.deck.files.FileBrowser(
                new com.aresstack.spaceharvester.deck.files.local.LocalFileSource(() -> sharedFolders.get()),
                connecting), editors, new com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore());
        ftp = new FtpWorkspace(ftpSessions, accounts, editors);
        tools.setOpaque(false);
        ndv = new NdvWorkspace(ndvSessions, accounts, editors);
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
                case BETAVIEW -> betaView;
                case FILES -> files;
                case FTP -> ftp;
                case NDV -> ndv;
                default -> placeholder(candidate);
            }, candidate.name());
        }
        show(DeckArea.TERMINAL);
        perspective = Perspective.of(Perspectives.COMMAND_DECK, "Command Deck",
                "Interaktiv mit Host-Systemen arbeiten: Terminal, FTP, NDV, BetaView, Dateien", true, navigation,
                workspace, null, tools);
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
        tools.removeAll();
        if (next == DeckArea.FTP) {
            tools.add(ftp.tools(), BorderLayout.CENTER);
        }
        tools.revalidate();
        tools.repaint();
    }

    public DeckArea area() {
        return area;
    }

    /** Trennt alle Sitzungen (Programmende). */
    public void shutdown() {
        sessions.closeAll();
        betaView.shutdown();
        ftpSessions.closeAll();
        ndvSessions.closeAll();
        connecting.shutdownNow();
    }

    /** Der gemeinsame Editor: FTP und NDV öffnen ihre Dokumente hier mit einer eigenen Ablage. */
    public com.aresstack.spaceharvester.deck.editor.EditorSessions editors() {
        return editors;
    }

    /** Namen der offenen Dokumente mit ungespeicherten Änderungen (Rückfrage beim Beenden). */
    public java.util.List<String> unsavedDocuments() {
        return editors.all().stream().filter(com.aresstack.spaceharvester.deck.editor.EditorSession::dirty)
                .map(com.aresstack.spaceharvester.deck.editor.EditorSession::name).toList();
    }

    /** Woher der Bereich Dateien die freigegebenen Ordner (Einstellungen › Dateizugriff) als Orte nimmt. */
    public void sharedFolders(java.util.function.Supplier<java.util.List<java.nio.file.Path>> folders) {
        sharedFolders = folders == null ? java.util.List::of : folders;
    }

    FilesWorkspace files() {
        return files;
    }

    public FtpSessions ftpSessions() {
        return ftpSessions;
    }

    FtpWorkspace ftp() {
        return ftp;
    }

    JComponent tools() {
        return tools;
    }

    TerminalWorkspace terminal() {
        return terminal;
    }

    BetaViewWorkspace betaView() {
        return betaView;
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
