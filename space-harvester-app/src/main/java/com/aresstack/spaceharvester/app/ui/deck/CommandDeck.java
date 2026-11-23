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
            new com.aresstack.spaceharvester.deck.editor.EditorSessions(connecting, new HistoryDelegate());
    /** Lokaler Verlauf des Editors (Vergleich mit früheren Versionen); ohne Ort keiner (Tests). */
    private volatile com.aresstack.spaceharvester.deck.editor.VersionHistory history =
            com.aresstack.spaceharvester.deck.editor.VersionHistory.NONE;
    /** SharePoint-Sites für „Orte“ im Bereich Dateien (aus den Quellen der Einstellungen). */
    private volatile java.util.function.Supplier<java.util.List<
            com.aresstack.spaceharvester.deck.files.sharepoint.SharePointSite>> sharePointSites = java.util.List::of;
    private final FilesWorkspace files;
    /** Freigegebene Ordner aus Einstellungen › Dateizugriff, als Orte im Bereich Dateien. */
    private volatile java.util.function.Supplier<java.util.List<java.nio.file.Path>> sharedFolders = java.util.List::of;
    private final FtpSessions ftpSessions = new FtpSessions(new CommonsNetFtpConnector(), connecting);
    private final FtpWorkspace ftp;
    /** Oben rechts in der Titelleiste: die Bedienelemente des gewählten Bereichs (FTP: Datasets | JES-Jobs). */
    private final JPanel tools = new JPanel(new BorderLayout());
    /** Links in der Statuszeile der App: der Stand des gewählten Bereichs, eine Karte je Bereich. */
    private final CardLayout statusCards = new CardLayout();
    private final JPanel status = new JPanel(statusCards);
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
        // Das Terminal soll nicht tot bleiben: Trennt der Host, baut es sich neu auf (Einstellung „Neuaufbau“).
        sessions.autoReconnect(com.aresstack.spaceharvester.deck.terminal.TerminalSessions.Reconnect.standard(
                () -> TerminalSettings.shared().autoReconnect()), (delay, task) -> {
                    javax.swing.Timer once = new javax.swing.Timer((int) delay.toMillis(), e -> task.run());
                    once.setRepeats(false);
                    once.start();
                });
        terminal = new TerminalWorkspace(sessions, accounts);
        betaView = new BetaViewWorkspace(new BetaViewSessions(new HttpBetaViewServer(betaViewAccess.http()),
                connecting), accounts);
        files = new FilesWorkspace(new com.aresstack.spaceharvester.deck.files.FileBrowser(
                new com.aresstack.spaceharvester.deck.files.local.LocalFileSource(() -> sharedFolders.get()),
                connecting), editors, new com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore(),
                new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore(),
                new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess(
                        new com.aresstack.spaceharvester.deck.files.sharepoint.windows.NetUseLogin(), connecting),
                () -> sharePointSites.get(), accounts);
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
            javax.swing.JComponent info = switch (candidate) {
                case TERMINAL -> terminal.statusInfo();
                case BETAVIEW -> betaView.statusInfo();
                case FILES -> files.statusInfo();
                case FTP -> ftp.statusInfo();
                case NDV -> ndv.statusInfo();
                default -> new JPanel();
            };
            info.setOpaque(false);
            status.add(info, candidate.name());
        }
        status.setOpaque(false);
        show(DeckArea.TERMINAL);
        perspective = new Perspective(Perspectives.COMMAND_DECK, "Command Deck",
                "Interaktiv mit Host-Systemen arbeiten: Terminal, FTP, NDV, BetaView, Dateien", true, navigation,
                workspace, null, tools, null, null, status);
    }

    /** Der Stand des gewählten Bereichs für die Statuszeile (für Tests). */
    JPanel statusPanel() {
        return status;
    }

    public Perspective perspective() {
        return perspective;
    }

    /**
     * Was der globale Chat in dieser Perspektive bekommt (#1151, ADR 0033): die Werkzeuge des gerade gewählten
     * Bereichs. Terminal: der Maskenassistent (Maske lesen, Felder lokal ausfüllen, nie abschicken). FTP, NDV,
     * BetaView, Dateien: die aktuelle Ansicht geht mit, dazu Ansicht und Texte lesen, im Bereich Dateien auch Ordner
     * auflisten und Dateien lesen (PDF, Word … über die Dokumentvorschau). Beim Senden ausgewertet, auf dem EDT.
     */
    public com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope chatScope() {
        DeckArea sent = area;
        if (sent == DeckArea.TERMINAL) {
            com.aresstack.spaceharvester.deck.terminal.TerminalSession session = terminal.selected();
            String where = "Command Deck › Terminal" + (session == null ? " (keine Sitzung gewählt)"
                    : " " + session.profile().label());
            String key = "command-deck/terminal" + (session == null ? "" : "/" + session.profile().label());
            return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope(where, key,
                    new com.aresstack.spaceharvester.app.chat.TerminalChatTools(session, terminal::selected),
                    com.aresstack.spaceharvester.app.chat.TerminalChatTools.systemMessage(where));
        }
        JComponent shown = areaView(sent);
        com.aresstack.spaceharvester.app.chat.ChatToolset extra = null;
        String listing = "";
        java.util.List<String> rules = java.util.List.of();
        String where = "Command Deck › " + sent.title();
        String key = "command-deck/" + sent.name().toLowerCase(java.util.Locale.ROOT);
        if (sent == DeckArea.FILES) {
            String folder = files.browser().location().orElse(null);
            extra = new com.aresstack.spaceharvester.app.chat.FilesChatTools(files.browser().source(), folder,
                    files.textStore(), files.previewStore(),
                    com.aresstack.spaceharvester.app.deck.DocumentPreviewStore::isDocument);
            var filesTools = (com.aresstack.spaceharvester.app.chat.FilesChatTools) extra;
            rules = com.aresstack.spaceharvester.app.chat.FilesChatTools.rules();
            // Die Tabelle zeigt Ordner nur am Symbol; die Liste sagt es dem Modell ausdrücklich.
            listing = filesTools.overview();
            if (folder != null) {
                where += " (Ordner " + folder + ")";
                key += "/" + folder;
            }
        }
        com.aresstack.spaceharvester.app.chat.DeckChatTools tools =
                new com.aresstack.spaceharvester.app.chat.DeckChatTools(() -> shown, () -> area == sent, extra);
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope(where, key, tools,
                com.aresstack.spaceharvester.app.chat.DeckChatTools.systemMessage(where, about(sent), rules,
                        tools.snapshot() + (listing.isEmpty() ? ""
                                : "\n\nInhalt des offenen Ordners:\n" + listing)));
    }

    /** Hinweis über dem Eingabefeld und Vorschläge (Chips) für den gewählten Bereich. */
    public com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints chatHints() {
        return chatHints(area);
    }

    static com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints chatHints(DeckArea shown) {
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints(switch (shown) {
            case TERMINAL -> "Command Deck: Der Chat liest die Terminal-Maske und füllt Felder aus – abschicken kannst "
                    + "nur du.";
            case FTP -> "Command Deck › FTP: Der Chat sieht die Liste, das offene Member oder den Spool und erklärt "
                    + "sie – ändern kannst nur du.";
            case NDV -> "Command Deck › NDV: Der Chat sieht Objektliste und offenen Quelltext und erklärt sie – ändern "
                    + "kannst nur du.";
            case BETAVIEW -> "Command Deck › BetaView: Der Chat sieht Trefferliste und offenes Dokument und erklärt "
                    + "sie.";
            case FILES -> "Command Deck › Dateien: Der Chat sieht den offenen Ordner und liest Dateien darin, auch "
                    + "PDF, Word und Excel – ändern kannst nur du.";
        }, switch (shown) {
            case TERMINAL -> java.util.List.of(action("Maske erklären", "Erkläre mir die aktuelle Maske."));
            case FTP -> java.util.List.of(action("Ansicht erklären", "Erkläre mir, was ich gerade sehe."),
                    action("Offenes zusammenfassen", "Fasse das offene Member bzw. den offenen Spool zusammen."));
            case NDV -> java.util.List.of(action("Ansicht erklären", "Erkläre mir, was ich gerade sehe."),
                    action("Quelltext erklären", "Erkläre mir den offenen Quelltext: Zweck, Ablauf, Aufrufe."));
            case BETAVIEW -> java.util.List.of(action("Ansicht erklären", "Erkläre mir, was ich gerade sehe."),
                    action("Dokument zusammenfassen", "Fasse das offene Dokument zusammen."));
            case FILES -> java.util.List.of(action("Ordner zusammenfassen",
                            "Fasse zusammen, was in diesem Ordner liegt und worum es in den Dateien geht."),
                    action("Auswahl zusammenfassen", "Fasse die ausgewählte Datei zusammen."));
        });
    }

    private static com.aresstack.spaceharvester.app.ui.content.ChatPanel.QuickAction action(String label,
                                                                                         String question) {
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.QuickAction(label, question, question);
    }

    /** Ein, zwei Sätze für die Systemnachricht: was man im Bereich tut. */
    private static String about(DeckArea shown) {
        return switch (shown) {
            case FTP -> "Dort sieht er MVS-Datasets und Members eines z/OS-Hosts per FTP oder JES-Jobs mit Spool; "
                    + "offene Members und Spools stehen als Text im Editor.";
            case NDV -> "Dort sieht er Bibliotheken und Objekte eines Natural Development Servers; offene Objekte "
                    + "stehen als Natural-Quelltext im Editor.";
            case BETAVIEW -> "Dort sucht er Belege und Listen auf einem BetaView-Dokumentserver; Trefferliste und "
                    + "geöffnete Dokumente stehen in der Ansicht.";
            case FILES -> "Dort sieht er einen Ordner seiner Festplatte oder eines Netzlaufwerks; offene Dateien stehen "
                    + "als Text im Editor.";
            case TERMINAL -> "Dort arbeitet er in einer TN3270-Sitzung.";
        };
    }

    /** Die Oberfläche eines Bereichs, wie sie in der Mitte steht. */
    private JComponent areaView(DeckArea shown) {
        return switch (shown) {
            case TERMINAL -> terminal;
            case FTP -> ftp;
            case NDV -> ndv;
            case BETAVIEW -> betaView;
            case FILES -> files;
        };
    }

    /** Meldet jeden Wechsel des Bereichs (der Chat passt Hinweis und Vorschläge an). */
    public void onAreaChanged(Runnable observer) {
        areaObservers.add(observer);
    }

    private final java.util.List<Runnable> areaObservers = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        statusCards.show(status, next.name());
        tools.removeAll();
        if (next == DeckArea.FTP) {
            tools.add(ftp.tools(), BorderLayout.CENTER);
        }
        tools.revalidate();
        tools.repaint();
        if (areaObservers != null) {
            areaObservers.forEach(Runnable::run);
        }
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

    /** Wo der Editor frühere Versionen aufbewahrt (Vergleich); ohne Aufruf kein Verlauf. */
    public void history(com.aresstack.spaceharvester.deck.editor.VersionHistory versions) {
        history = versions == null ? com.aresstack.spaceharvester.deck.editor.VersionHistory.NONE : versions;
    }

    /** Woher der Bereich Dateien die SharePoint-Sites für „Orte“ nimmt. */
    public void sharePointSites(java.util.function.Supplier<java.util.List<
            com.aresstack.spaceharvester.deck.files.sharepoint.SharePointSite>> sites) {
        sharePointSites = sites == null ? java.util.List::of : sites;
    }

    /** Reicht an den gerade gesetzten Verlauf weiter (er kommt erst nach dem Bau aus der App). */
    private final class HistoryDelegate implements com.aresstack.spaceharvester.deck.editor.VersionHistory {
        @Override
        public void record(String store, String location, String text, String label) {
            history.record(store, location, text, label);
        }

        @Override
        public java.util.List<Version> versions(String store, String location) {
            return history.versions(store, location);
        }

        @Override
        public java.util.Optional<String> content(String store, String location, String id) {
            return history.content(store, location, id);
        }
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
