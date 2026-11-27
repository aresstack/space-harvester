package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.spaceharvester.app.chat.JesChatTools;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewAccess;
import com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewWorkspace;
import com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditors;
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
    private final TerminalSessions sessions;
    private final CardLayout cards = new CardLayout();
    private final JPanel workspace = new JPanel(cards);
    /**
     * Die Fußleiste wie im Space Harvester: links die Lupe (Strg+F), mittig die Bedienelemente des Bereichs. Jeder
     * Bereich hat seine eigene Leiste mit eigenem Suchtext (Angelo 24.11.2026: gemeinsame Klasse, nie gemeinsame
     * Instanz); sie sucht nur in ihrem Bereich.
     */
    private final java.util.Map<DeckArea, com.aresstack.spaceharvester.app.ui.FindBar> findBars =
            new java.util.EnumMap<>(DeckArea.class);
    /** Arbeitsfläche samt Fußleiste. */
    private final JPanel stage = new JPanel(new BorderLayout());
    private final JPanel navigation = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final Map<DeckArea, ComposerToggleButton> buttons = new EnumMap<>(DeckArea.class);
    /** Weg zum NDV-Server für Verbindungen mit „Proxy der App verwenden“; ohne App (Tests) direkt. */
    private volatile PalNdvConnector.Route ndvRoute = profile -> java.net.Proxy.NO_PROXY;
    private final NdvSessions ndvSessions = new NdvSessions(new PalNdvConnector(profile -> ndvRoute.to(profile)),
            connecting);
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
    /**
     * So breit wie der Stand des gewählten Bereichs: Ein Kartenstapel wäre so breit wie sein breitester, auch
     * unsichtbarer Stand (etwa ein langer Pfad der Dateien) und nähme dem Stand der Aufträge daneben den Platz.
     */
    private final JPanel status = new JPanel(statusCards) {
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
    private final NdvWorkspace ndv;
    /** E-Akte: Anmeldung und WebDAV über den Netzweg, den die App setzt ({@link #eakte}); ohne App direkt. */
    private volatile com.aresstack.spaceharvester.deck.eakte.EakteServer eakteServer =
            new com.aresstack.spaceharvester.deck.eakte.http.HttpEakteServer(
                    com.aresstack.spaceharvester.deck.http.HttpClientFactory.direct(), java.time.Duration.ofSeconds(30),
                    null);
    private final com.aresstack.spaceharvester.deck.eakte.EakteSessions eakteSessions =
            new com.aresstack.spaceharvester.deck.eakte.EakteSessions(
                    (profile, password) -> eakteServer.connect(profile, password), connecting);
    private final EakteWorkspace eakte;
    private final Perspective perspective;
    /** Startseite: beim Wechsel ins Deck gewählt, verbindet nichts. */
    private final DeckHome home;
    private volatile DeckArea area = DeckArea.HOME;
    private volatile long chatGeneration;
    private final java.util.List<Runnable> sidebarObservers = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        this(accounts, betaViewAccess, com.aresstack.spaceharvester.deck.terminal.TerminalTunnel.NONE);
    }

    /**
     * @param terminalTunnel Weg durch den Proxy der App für Terminal-Verbindungen mit „Proxy verwenden“
     */
    public CommandDeck(ServerAccounts accounts, BetaViewAccess betaViewAccess,
                       com.aresstack.spaceharvester.deck.terminal.TerminalTunnel terminalTunnel) {
        sessions = new TerminalSessions(new OpenTermConnector(terminalTunnel), connecting,
                new com.aresstack.spaceharvester.deck.terminal.LoginScript(
                        () -> TerminalSettings.shared().actionDelay()));
        // Das Terminal soll nicht tot bleiben: Trennt der Host, baut es sich neu auf (Einstellung „Neuaufbau“).
        // Schutz vor der Kontosperre: derselbe Wächter wie in allen Bereichen, je KeePass-Eintrag.
        sessions.guard(DeckLogin.guard());
        sessions.autoReconnect(com.aresstack.spaceharvester.deck.terminal.TerminalSessions.Reconnect.standard(
                () -> TerminalSettings.shared().autoReconnect()), (delay, task) -> {
                    javax.swing.Timer once = new javax.swing.Timer((int) delay.toMillis(), e -> task.run());
                    once.setRepeats(false);
                    once.start();
                });
        // Diagramm-Ansicht „Visuell“ (MainframeMate) an jedem Editor, gleich in welchem Bereich
        com.aresstack.spaceharvester.app.ui.deck.visual.VisualAction.install(editors);
        // Beziehungen und Diagramm teilen eine Liste (Einstellungen › Diagramme › Systemfunktionen).
        com.aresstack.spaceharvester.app.ui.deck.editor.DocumentEditors.systemFunctions(
                () -> com.aresstack.spaceharvester.app.ui.deck.visual.SystemFunctionsStore.shared().lookup());
        terminal = new TerminalWorkspace(sessions, accounts);
        betaView = new BetaViewWorkspace(new BetaViewSessions(new HttpBetaViewServer(betaViewAccess.http(),
                () -> DeckTimeouts.shared().get(DeckTimeouts.Value.BETAVIEW)),
                connecting), accounts);
        files = new FilesWorkspace(new com.aresstack.spaceharvester.deck.files.FileBrowser(
                new com.aresstack.spaceharvester.deck.files.local.LocalFileSource(() -> sharedFolders.get()),
                connecting), editors, new com.aresstack.spaceharvester.deck.files.local.LocalDocumentStore(),
                new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore(),
                new com.aresstack.spaceharvester.deck.files.sharepoint.SharePointAccess(
                        new com.aresstack.spaceharvester.deck.files.sharepoint.windows.NetUseLogin(
                                () -> DeckTimeouts.shared().get(DeckTimeouts.Value.SHAREPOINT_LOGIN)), connecting),
                () -> sharePointSites.get(), accounts);
        ftp = new FtpWorkspace(ftpSessions, accounts, editors);
        ftp.onChatChanged(() -> { if (area == DeckArea.FTP) areaObservers.forEach(Runnable::run); });
        tools.setOpaque(false);
        ndv = new NdvWorkspace(ndvSessions, accounts, editors);
        eakte = new EakteWorkspace(eakteSessions, accounts);
        wireNatural();
        workspace.setBackground(Comic.SURFACE);
        navigation.setOpaque(false);
        // Abstand zum Titel wie die Icon-Leiste des Space Harvesters.
        navigation.setBorder(BorderFactory.createEmptyBorder(0, 20, 0, 0));
        home = new DeckHome(java.util.List.of(
                new DeckHome.Tile(DeckArea.TERMINAL, () -> status(sessions.all().stream()
                        .filter(s -> s.state() == com.aresstack.spaceharvester.deck.terminal.SessionState.CONNECTED)
                        .count(), ConnectForm.ENTRY_KEY)),
                new DeckHome.Tile(DeckArea.FTP, () -> status(ftpSessions.all().stream()
                        .filter(s -> s.state() == com.aresstack.spaceharvester.deck.ftp.ConnectionState.CONNECTED)
                        .count(), FtpConnectForm.ENTRY_KEY)),
                new DeckHome.Tile(DeckArea.NDV, () -> status(ndvSessions.all().stream()
                        .filter(s -> s.state() == com.aresstack.spaceharvester.deck.ndv.NdvState.CONNECTED)
                        .count(), NdvConnectForm.ENTRY_KEY)),
                new DeckHome.Tile(DeckArea.BETAVIEW, () -> status(betaView.connectedCount(),
                        com.aresstack.spaceharvester.app.ui.deck.betaview.BetaViewWorkspace.ENTRY_KEY)),
                new DeckHome.Tile(DeckArea.FILES, () -> java.util.List.of("Lokale Ordner: ohne Anmeldung bereit")),
                new DeckHome.Tile(DeckArea.EAKTE, () -> status(eakte.connectedCount(), EakteWorkspace.ENTRY_KEY))),
                this::show);
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
                case HOME -> home;
                case TERMINAL -> terminal;
                case BETAVIEW -> betaView;
                case FILES -> files;
                case FTP -> ftp;
                case NDV -> ndv;
                case EAKTE -> eakte;
            }, candidate.name());
            javax.swing.JComponent info = switch (candidate) {
                case TERMINAL -> terminal.statusInfo();
                case BETAVIEW -> betaView.statusInfo();
                case FILES -> files.statusInfo();
                case FTP -> ftp.statusInfo();
                case NDV -> ndv.statusInfo();
                case EAKTE -> eakte.statusInfo();
                default -> new JPanel();
            };
            info.setOpaque(false);
            status.add(info, candidate.name());
        }
        status.setOpaque(false);
        stage.setOpaque(false);
        stage.add(workspace, BorderLayout.CENTER);
        for (DeckArea each : DeckArea.values()) {
            com.aresstack.spaceharvester.app.ui.FindBar bar =
                    new com.aresstack.spaceharvester.app.ui.FindBar(() -> areaView(each));
            if (areaView(each) instanceof DeckBars.FootTools with) {
                bar.tools(with.footTools());
            }
            findBars.put(each, bar);
        }
        stage.add(findBar(), BorderLayout.SOUTH);
        // Bereiche mit Seitenleiste nehmen die Lupe unter ihre Arbeitsfläche (DeckBars.FindSlot), wie im Space Harvester.
        stage.putClientProperty(DeckBars.FIND_HOST, (Runnable) this::placeFindBar);
        stage.putClientProperty(com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane.HOST,
                (Runnable) () -> sidebarObservers.forEach(Runnable::run));
        // Ein Editor wird gezeigt oder verdeckt: Falt-Umschalter, Sprachauswahl und Treffer folgen ihm.
        stage.putClientProperty(com.aresstack.spaceharvester.app.ui.FindBar.VIEW_HOST,
                (Runnable) () -> findBar().viewChanged());
        stage.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "find");
        stage.getActionMap().put("find", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                findBar().focus();
            }
        });
        // Maustasten Zurück/Vor im ganzen Fenster, solange das Deck zu sehen ist (wie im Space Harvester und in
        // MainframeMate): Sie wirken im Verlauf des gezeigten Bereichs.
        com.aresstack.spaceharvester.app.ui.controls.MouseNavigation.install(stage, () -> navigate(true),
                () -> navigate(false));
        show(DeckArea.HOME);
        // Angelo, 23.11.2026: Wer ins Deck wechselt, landet auf Home. Die Bereiche verbinden beim Öffnen sofort; das
        // soll erst passieren, wenn man einen davon anklickt. Schon beim Verlassen auf Home zurück: Beim Wiederkommen
        // wird die Arbeitsfläche sichtbar, bevor onShown läuft, und der zuletzt gewählte Bereich würde sonst verbinden.
        perspective = new Perspective(Perspectives.COMMAND_DECK, "Command Deck",
                "Interaktiv mit Host-Systemen arbeiten: Terminal, FTP, NDV, BetaView, Dateien, E-Akte", true, navigation,
                stage, null, tools, () -> show(DeckArea.HOME), () -> show(DeckArea.HOME), status);
    }

    /**
     * Der Stand eines Bereichs für seine Kachel auf Home: verbunden oder nicht, der gemerkte KeePass-Eintrag und eine
     * gesetzte Anmeldesperre. Liest nur gemerkte Werte, nie ein Passwort.
     */
    private static java.util.List<String> status(long connected, String entryKey) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(connected == 0 ? "nicht verbunden" : connected == 1 ? "1 Verbindung offen"
                : connected + " Verbindungen offen");
        String entry = com.aresstack.spaceharvester.app.ui.AppState.shared().get(entryKey);
        if (entry != null && !entry.isBlank()) {
            lines.add("Zugang: " + entry.strip());
            DeckLogin.guard().failure(entry).ifPresent(failure ->
                    lines.add("Automatische Anmeldung gesperrt: " + failure.reason()));
        }
        return lines;
    }

    /** Die Startseite (für Tests). */
    DeckHome home() {
        return home;
    }

    /** Die Fußleiste mit der Lupe des gezeigten Bereichs. */
    com.aresstack.spaceharvester.app.ui.FindBar findBar() {
        return findBars.get(area);
    }

    /** Die Fußleiste mit der Lupe von {@code which} (für Tests). */
    com.aresstack.spaceharvester.app.ui.FindBar findBar(DeckArea which) {
        return findBars.get(which);
    }

    /** Der Stand des gewählten Bereichs für die Statuszeile (für Tests). */
    JPanel statusPanel() {
        return status;
    }

    /**
     * Hängt NDV in die Editoren ein (wie MainframeMate {@code openNaturalFromJcl} und das Öffnen von
     * {@code ndv://}-Zielen): Natural aus der JCL und Doppelklicks in den Beziehungen wechseln in den NDV-Bereich und
     * öffnen dort in der gewählten Sitzung; Aufrufhierarchie und DDM-Benutzer kommen aus deren Abhängigkeitsgraphen.
     */
    private void wireNatural() {
        DocumentEditors.naturalFromJcl((stepLib, program) -> {
            show(DeckArea.NDV);
            ndv.openFromJcl(stepLib, program);
        });
        DocumentEditors.naturalOpener((library, object, line) -> {
            show(DeckArea.NDV);
            ndv.openNatural(library, object, line);
        });
        DocumentEditors.naturalRelations(new NdvNaturalRelations(ndv::analyses));
    }

    /** Der Proxy-Weg der App für NDV-Verbindungen, die ihn gewählt haben (Vorgabe direkt). */
    public void ftpStoredSearch(com.aresstack.spaceharvester.app.ui.AppContext context) {
        ftp.storedCobolSearch(context, () -> area == DeckArea.FTP);
        ndv.storedProgramSearch(context, () -> area == DeckArea.NDV);
    }

    /** „Als Wissensquelle hinzufügen“ im FTP-Bereich über die Wissensquellen der App. */
    public void ftpSources(FtpSourceLink link) {
        ftp.sourceLink(link);
    }

    public void ftpNavigation(FtpNavigationObserver observer) {
        ftp.navigationObserver(observer);
    }

    public void ndvRoute(PalNdvConnector.Route route) {
        ndvRoute = route == null ? profile -> java.net.Proxy.NO_PROXY : route;
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
        long areaGeneration = chatGeneration;
        long ftpGeneration = ftp.chatGeneration();
        java.util.function.BooleanSupplier stillShown = () -> area == sent && chatGeneration == areaGeneration
                && (sent != DeckArea.FTP || ftp.chatGeneration() == ftpGeneration);
        com.aresstack.spaceharvester.app.chat.ChatToolset extra = null;
        String listing = "";
        java.util.List<String> rules = java.util.List.of();
        String where = "Command Deck › " + sent.title();
        String key = "command-deck/" + sent.name().toLowerCase(java.util.Locale.ROOT);
        if (sent == DeckArea.FTP) {
            FtpSessionView active = ftp.activeSessionView();
            if (active != null) {
                where += " › " + active.view();
                key += "/" + active.session().id() + "/" + active.view();
                if (FtpSessionView.JOBS.equals(active.view())) {
                    shown = active.jobs();
                    JesChatTools jes = new JesChatTools(active.jobs(), stillShown);
                    extra = jes;
                    rules = JesChatTools.rules();

                } else {
                    shown = active.datasets();
                    extra = new com.aresstack.spaceharvester.app.chat.FtpChatTools(ftp.selected(), ftp::selected);
                    rules = com.aresstack.spaceharvester.app.chat.FtpChatTools.rules();
                    var diagnosis = active.datasets().diagnosis();
                    if (diagnosis != null) {
                        extra = new com.aresstack.spaceharvester.app.chat.MvsDiagnosisTools(extra, diagnosis, stillShown);
                    }
                }
            }
        } else if (sent == DeckArea.NDV) {
            extra = new com.aresstack.spaceharvester.app.chat.NdvChatTools(ndv.selected(), ndv::selected);
            rules = com.aresstack.spaceharvester.app.chat.NdvChatTools.rules();
        } else if (sent == DeckArea.EAKTE) {
            com.aresstack.spaceharvester.application.extraction.DocumentRef document = eakte.shownDocument();
            extra = new com.aresstack.spaceharvester.app.chat.DeadlineChatTools(
                    () -> stillShown.getAsBoolean() ? document : null, eakte.analyses());
        } else if (sent == DeckArea.FILES) {
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
        JComponent activeView = shown;
        com.aresstack.spaceharvester.app.chat.DeckChatTools tools =
                new com.aresstack.spaceharvester.app.chat.DeckChatTools(() -> activeView, stillShown, extra);
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolScope(where, key, tools,
                com.aresstack.spaceharvester.app.chat.DeckChatTools.systemMessage(where, about(sent), rules,
                        tools.snapshot() + (listing.isEmpty() ? ""
                                : "\n\nInhalt des offenen Ordners:\n" + listing)));
    }

    /** Hinweis über dem Eingabefeld und Vorschläge (Chips) für den gewählten Bereich. */
    public com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints chatHints() {
        if (area == DeckArea.FTP && ftp.activeSessionView() != null
                && FtpSessionView.JOBS.equals(ftp.activeSessionView().view())) {
            return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints(
                    "JES-Jobs: Der Chat darf suchen, Jobs und Spool auswählen und lesen. Keine Host-Änderungen.",
                    java.util.List.of(action("Job erklären", "Erkläre den ausgewählten Job."),
                            action("Spool zusammenfassen", "Fasse den geöffneten Spool zusammen.")));
        }
        if (area == DeckArea.FTP && ftp.activeSessionView() != null) {
            return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints(
                    "Datasets: Der Chat liest Datasets und Members. JES-Werkzeuge sind nur in JES-Jobs verfügbar.",
                    java.util.List.of(action("Ansicht erklären", "Erkläre mir, was ich gerade sehe."),
                            action("Member zusammenfassen", "Fasse den offenen Editorinhalt zusammen.")));
        }
        return chatHints(area);
    }

    static com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints chatHints(DeckArea shown) {
        return new com.aresstack.spaceharvester.app.ui.content.ChatPanel.ToolHints(switch (shown) {
            case HOME -> "Command Deck: Der Chat sieht die Übersicht der Bereiche – verbunden wird erst, wenn du einen "
                    + "öffnest.";
            case TERMINAL -> "Command Deck: Der Chat liest die Terminal-Maske und füllt Felder aus – abschicken kannst "
                    + "nur du.";
            case FTP -> "Command Deck › FTP: Der Chat sieht die Ansicht, liest Datasets, Members und Spools – ändern "
                    + "kannst nur du.";
            case NDV -> "Command Deck › NDV: Der Chat sieht die Ansicht, sucht Objekte und liest Quelltexte – ändern "
                    + "kannst nur du.";
            case BETAVIEW -> "Command Deck › BetaView: Der Chat sieht Trefferliste und offenes Dokument und erklärt "
                    + "sie.";
            case FILES -> "Command Deck › Dateien: Der Chat sieht den offenen Ordner und liest Dateien darin, auch "
                    + "PDF, Word und Excel – ändern kannst nur du.";
            case EAKTE -> "Command Deck › E-Akte: Der Chat sieht die offene Mappe – ändern kannst nur du.";
        }, switch (shown) {
            case HOME -> java.util.List.of(action("Bereiche erklären",
                    "Erkläre mir kurz, wofür die Bereiche des Command Deck da sind."));
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
            case EAKTE -> java.util.List.of(action("Ansicht erklären", "Erkläre mir, was ich gerade sehe."),
                    action("Fristen auswerten", "Werte die Fristen des offenen Dokuments aus."));
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
            case EAKTE -> "Dort sieht er Mappen und Dokumente der E-Akte (Fabasoft) über WebDAV.";
            case HOME -> "Dort sieht er die Übersicht der Bereiche Terminal, FTP, NDV, BetaView, Dateien und E-Akte mit ihrem "
                    + "Verbindungsstand; verbunden wird erst beim Öffnen eines Bereichs.";
        };
    }

    /** Die Oberfläche eines Bereichs, wie sie in der Mitte steht. */
    private JComponent areaView(DeckArea shown) {
        return switch (shown) {
            case HOME -> home;
            case TERMINAL -> terminal;
            case FTP -> ftp;
            case NDV -> ndv;
            case BETAVIEW -> betaView;
            case FILES -> files;
            case EAKTE -> eakte;
        };
    }

    public com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane activeSidebar() {
        return com.aresstack.spaceharvester.app.ui.controls.LeftSidebarPane.activeIn(workspace);
    }

    public void onSidebarChanged(Runnable observer) {
        sidebarObservers.add(observer);
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
        if (area != next) {
            chatGeneration++;
            if (area == DeckArea.FTP && ftp.activeSessionView() != null) {
                ftp.activeSessionView().datasets().revokeDiagnosis();
            }
        }
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
        // Jeder Bereich hat seine eigene Leiste mit eigenem Suchtext und seinen Bedienelementen neben der Lupe.
        placeFindBar();
        findBar().areaChanged();
        if (areaObservers != null) {
            areaObservers.forEach(Runnable::run);
        }
    }

    /**
     * Setzt die Leiste mit der Lupe unter die Arbeitsfläche des gezeigten Bereichs, wenn er eine Seitenleiste hat
     * ({@link DeckBars.FindSlot}); sonst unter den ganzen Bereich. Zweigeteilt wie im Space Harvester (Angelo
     * 24.11.2026): Unter der Seitenleiste steht deren eigene Fußleiste.
     */
    void placeFindBar() {
        com.aresstack.spaceharvester.app.ui.FindBar findBar = findBar();
        for (com.aresstack.spaceharvester.app.ui.FindBar other : findBars.values()) {
            java.awt.Container parent = other.getParent();
            if (other != findBar && parent != null) {
                parent.remove(other);
                parent.revalidate();
                parent.repaint();
            }
        }
        java.awt.Container target = DeckBars.showingSlot(workspace);
        if (target == null) {
            target = stage;
        }
        if (findBar.getParent() == target) {
            return;
        }
        java.awt.Container old = findBar.getParent();
        if (old != null) {
            old.remove(findBar);
            old.revalidate();
            old.repaint();
        }
        target.add(findBar, BorderLayout.SOUTH);
        target.revalidate();
        target.repaint();
    }

    /** Zurück ({@code true}) oder Vor im Verlauf des gezeigten Bereichs; Bereiche ohne Verlauf bleiben stehen. */
    void navigate(boolean back) {
        switch (area) {
            case FILES -> {
                if (back) {
                    files.navigateBack();
                } else {
                    files.navigateForward();
                }
            }
            case FTP -> {
                if (back) {
                    ftp.navigateBack();
                } else {
                    ftp.navigateForward();
                }
            }
            case NDV -> {
                if (back) {
                    ndv.navigateBack();
                } else {
                    ndv.navigateForward();
                }
            }
            case BETAVIEW -> {
                if (back) {
                    betaView.navigateBack();
                } else {
                    betaView.navigateForward();
                }
            }
            case EAKTE -> {
                if (back) {
                    eakte.navigateBack();
                } else {
                    eakte.navigateForward();
                }
            }
            default -> {
                // Home hat keinen Verlauf; das Terminal belegt die Seitentasten selbst mit F-Tasten (Einstellungen ›
                // Maus).
            }
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
        eakte.shutdown();
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
    public void folderIndexAction(com.aresstack.spaceharvester.app.ui.LocalFolderIndexAction action) {
        files.folderIndexAction((parent, location) -> action.start(parent, location, files::indexStatus));
    }

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

    /** Wo der Bereich Dateien SharePoint-Zugangsdaten nach der ersten erfolgreichen Anmeldung speichert. */
    public void sharePointLogins(com.aresstack.spaceharvester.deck.files.sharepoint.SavedLogins logins) {
        files.savedLogins(logins);
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

    EakteWorkspace eakte() {
        return eakte;
    }

    /**
     * Netzweg der E-Akte aus der App: Proxy und TLS-Vertrauen wie die anderen HTTP-Bereiche, das Protokoll der
     * Anmeldung (ohne Werte) ins Log der App. „Speichern unter“ öffnet wie überall im Ausgabeordner
     * ({@link com.aresstack.spaceharvester.app.ui.controls.SaveChooser}).
     */
    public void eakte(com.aresstack.spaceharvester.deck.eakte.EakteServer server) {
        if (server != null) {
            eakteServer = server;
        }
    }

    /**
     * Schließt die Dokumentanalyse der Fristen-Fachlogik an (ADR 0035): die E-Akte als Ablage, aus der sie liest, und
     * ihre Ergebnisse in der Extraktion jedes E-Akte-Browsers. {@code null} trennt beides (etwa beim Neustart des
     * Core). Auf dem EDT.
     */
    public void documentAnalyses(com.aresstack.spaceharvester.application.deadlines.DeadlineAnalysisService service) {
        if (service != null) {
            service.content(eakte.content());
        }
        eakte.analyses(service);
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
