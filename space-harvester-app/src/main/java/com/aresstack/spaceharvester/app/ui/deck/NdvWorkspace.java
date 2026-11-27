package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.spaceharvester.app.ui.Dialogs;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.ndv.NdvSession;
import com.aresstack.spaceharvester.deck.ndv.NdvSessions;
import com.aresstack.spaceharvester.deck.ndv.NdvState;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „NDV“ des Command Deck (Natural Development Server, #1142): oben links die Sitzungen als Pillen mit
 * Zustandspunkt und „Neue Verbindung“, oben rechts Filter und Neu laden der gewählten Sitzung, in der Mitte
 * Bibliotheken, Objekte und Quelltext ({@link NdvBrowser}), unten Zustand und Aktionen. Die Sitzungen gehören dem
 * Anwendungsfall {@link NdvSessions}; Wechsel des Bereichs oder der Perspektive trennen nichts und laden nichts.
 */
public final class NdvWorkspace extends JPanel implements DeckBars.FootTools {

    /**
     * Stand dieses Bereichs links in der Statuszeile der App (über {@code CommandDeck}): Zustand, Hinweise, Fehler rot
     * ({@code StatusNote.Tone.ERROR}). Lange Texte kürzt das Label selbst, der volle Text steht im Tooltip.
     */
    private final com.aresstack.spaceharvester.app.ui.IconButton storedProgramSearch =
            com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.create();
    private final com.aresstack.spaceharvester.app.ui.controls.StatusLabel statusInfo =
            new com.aresstack.spaceharvester.app.ui.controls.StatusLabel();

    /** Die Karte dieses Bereichs in der Statuszeile der App. */
    public javax.swing.JComponent statusInfo() {
        return statusInfo;
    }

    private static final String NEW = "new";
    static final String NO_SESSION = "Keine Sitzung gewählt – Server wählen und verbinden";

    private final NdvSessions sessions;
    private final Executor background;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<NdvSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<NdvSession, NdvBrowser> browsers = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CardLayout centerCards = new CardLayout();
    private final JPanel center = new JPanel(centerCards);
    private final CardLayout toolCards = new CardLayout();
    private final JPanel tools = new JPanel(toolCards);
    private final CardLayout footCards = new CardLayout();
    private final JPanel foot = new JPanel(footCards);
    private final NdvConnectForm form;
    private final EditorSessions editors;
    private NdvSession selected;
    /** Der zuletzt dem Sperrschutz gemeldete Zustand je Sitzung (jeder Übergang wird genau einmal gemeldet). */
    private final Map<NdvSession, NdvState> reported = new java.util.HashMap<>();
    /** Die bewachte Anmeldung je Sitzung (auch für das Selbstheilen nach einem Verbindungsabriss). */
    private final Map<NdvSession, GuardedLogin> logins = new java.util.concurrent.ConcurrentHashMap<>();
    /** Beobachter je Sitzung, beim Entfernen wieder abgemeldet. */
    private final Map<NdvSession, java.util.function.Consumer<NdvSession>> observers = new java.util.HashMap<>();
    /** Läuft gerade das Suchen des gemerkten Eintrags (Hintergrund)? Dann startet kein zweites. */
    private boolean discovering;

    /**
     * Anmeldung über einen KeePass-Eintrag, bewacht vom Sperrschutz des Deck: Ohne Zutun des Menschen (erstes
     * automatisches Verbinden, Selbstheilen) wird ein gesperrter Eintrag nicht versucht; was der Mensch auslöst
     * (Formular, „Neu verbinden“), darf einmal. Merkt sich, ob das Passwort wirklich gesendet wurde.
     */
    private static final class GuardedLogin implements NdvSessions.LoginSource {
        private final String entry;
        private final NdvSessions.LoginSource source;
        private final java.util.concurrent.atomic.AtomicBoolean manual;
        private final java.util.concurrent.atomic.AtomicBoolean sent = new java.util.concurrent.atomic.AtomicBoolean();
        /** Wann das Passwort zuletzt gesendet wurde; ein Erfolg löst nur ältere Sperren. */
        private volatile java.time.Instant attemptAt = java.time.Instant.EPOCH;

        GuardedLogin(String entry, NdvSessions.LoginSource source, boolean manual) {
            this.entry = entry;
            this.source = source;
            this.manual = new java.util.concurrent.atomic.AtomicBoolean(manual);
        }

        @Override
        public com.aresstack.spaceharvester.deck.access.ServerCredentials read() throws Exception {
            if (!manual.getAndSet(false) && !DeckLogin.guard().mayTry(entry)) {
                throw new java.io.IOException(DeckLogin.notice(entry));
            }
            // Der Guard speichert Millisekunden; abgeschnitten zählt eine Ablehnung in derselben ms als neuer.
            attemptAt = java.time.Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            com.aresstack.spaceharvester.deck.access.ServerCredentials credentials = source.read();
            sent.set(true);
            return credentials;
        }
    }
    private final ServerAccounts accounts;
    /** Sitzungen, die noch nie verbunden waren: ihr erster Erfolg wird gemerkt, ihr Fehlschlag zeigt das Formular. */
    private final java.util.Set<NdvSession> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Analysen der offenen Sitzungen, die gewählte zuerst; gelesen auch abseits des EDT (Beziehungen im Editor). */
    private volatile List<com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis> analyses = List.of();

    /** @param editors der gemeinsame Editor des Command Deck; NDV öffnet seine Quelltexte darin */
    void storedProgramSearch(com.aresstack.spaceharvester.app.ui.AppContext context,
                             java.util.function.BooleanSupplier active) {
        var action = new StoredProgramSearchAction(context, this, active,
                busy -> com.aresstack.spaceharvester.app.ui.controls.ProgramSearchButtons.busy(storedProgramSearch, busy),
                "Rechtsklick auf eine Bibliothek › „Indexieren (Regel anlegen)“; danach sind ihre Programme hier "
                        + "durchsuchbar.");
        storedProgramSearch.setVisible(true);
        storedProgramSearch.addActionListener(event -> action.open());
    }

    public NdvWorkspace(NdvSessions sessions, ServerAccounts accounts, EditorSessions editors) {
        this(sessions, accounts, editors, com.aresstack.spaceharvester.app.ui.Background.executor());
    }

    NdvWorkspace(NdvSessions sessions, ServerAccounts accounts, EditorSessions editors, Executor background) {
        super(new BorderLayout());
        this.sessions = sessions;
        this.editors = editors;
        this.background = background;
        setBackground(Comic.SURFACE);
        getAccessibleContext().setAccessibleName("NDV");
        this.accounts = accounts;
        form = new NdvConnectForm(accounts, (profile, account, login) -> {
            // Der Mensch hat „Verbinden“ gedrückt: das darf auch mit gesperrtem Eintrag.
            GuardedLogin guarded = new GuardedLogin(account, login, true);
            NdvSession opened = sessions.open(profile, account, guarded);
            logins.put(opened, guarded);
            fresh.add(opened);
        });

        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung", "Neue NDV-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> newConnection());
        DeckCredentials.menu(newPill, this::editCredentials);
        pillGroup.add(newPill);
        pills.add(newPill);
        tools.setOpaque(false);
        JPanel noTools = new JPanel();
        noTools.setOpaque(false);
        tools.add(noTools, NEW);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 2, 0));
        east.setOpaque(false);
        east.add(tools);
        storedProgramSearch.setVisible(false);
        east.add(storedProgramSearch);
        east.add(DeckCredentials.button(this::editCredentials));
        JPanel head = DeckBars.head(pills, east);

        JPanel formStage = new JPanel(new GridBagLayout());
        formStage.setBackground(Comic.SURFACE);
        formStage.add(form);
        center.setOpaque(false);
        center.add(formStage, NEW);

        // Kein eigener Fuß: die Aktionen der gewählten Sitzung stehen neben der Lupe der gemeinsamen Fußleiste
        // (footTools), der Stand in der Statuszeile der App. Das Formular hat keine Aktionen unten.
        foot.setOpaque(false);
        JPanel formFoot = new JPanel();
        formFoot.setOpaque(false);
        foot.add(formFoot, NEW);

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        if (pillOf.isEmpty()) {
            select(null);
        }
        // Beim Öffnen des Bereichs ohne Sitzung gleich verbinden, wenn Zugangsdaten da sind (Angelo 23.11.).
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                shown();
            }
        });
    }

    /**
     * Der Bereich wird gezeigt: ohne offene Sitzung automatisch verbinden. Ist der KeePass-Eintrag nach einem
     * Fehlschlag gesperrt (irgendwo im Deck), wird nicht versucht, sondern das Formular zeigt den Grund.
     */
    void shown() {
        if (pillOf.isEmpty() && fresh.isEmpty() && !discovering) {
            newConnection();
        }
    }

    private void added(NdvSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        NdvBrowser browser = new NdvBrowser(session, sessions, editors, background, () -> closeSession(session));
        browsers.put(session, browser);
        refreshAnalyses();
        String key = session.id();
        center.add(browser, key);
        tools.add(browser.tools(), key);
        foot.add(browser.foot(), key);
        // Das ✕ im Reiter trennt und schließt in einem Schritt (Angelo 23.11.).
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(dot(session.state()), session.label(), session.state().label(),
                "Trennen und schließen (auch Mittelklick auf den Reiter)", () -> closeSession(session));
        pill.addActionListener(e -> select(session));
        DeckCredentials.menu(pill, this::editCredentials);
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        java.util.function.Consumer<NdvSession> observer = changed -> SwingUtilities.invokeLater(() -> changed(changed));
        observers.put(session, observer);
        session.observe(observer);
        browser.beforeReconnect(() -> {
            GuardedLogin login = logins.get(session);
            if (login != null) {
                login.manual.set(true);
            }
        });
        browser.onLibraryChanged(() -> changed(session));
        browser.onStatusChanged(() -> {
            if (selected == session) {
                showStatus();
            }
        });
        changed(session);
        if (!pillOf.containsKey(session)) {
            return; // erstes Verbinden misslungen, das Formular zeigt den Grund
        }
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(NdvSession session) {
        if (!browsers.containsKey(session)) {
            return; // schon entfernt: eine nachgereichte Meldung (etwa „getrennt“) zählt nicht mehr
        }
        report(session);
        if (fresh.contains(session)) {
            if (session.state() == NdvState.CONNECTED) {
                fresh.remove(session);
                NdvConnectForm.rememberWorking(session.profile(), session.account());
            } else if (session.state() == NdvState.FAILED && pillOf.containsKey(session)) {
                // Erstes Verbinden misslungen: zurück ins Formular, mit Grund.
                fresh.remove(session);
                String reason = NdvBrowser.oneLine(session.failure().orElse("Verbindung fehlgeschlagen"));
                discard(session);
                select(null);
                form.problem(reason);
                return;
            }
        }
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            NdvBrowser browser = browsers.get(session);
            pill.setToolTipText((browser != null ? browser.tooltip() : session.profile().label()) + " · "
                    + session.failure()
                    .map(reason -> session.state().label() + ": " + reason).orElse(session.state().label()));
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Verbindung“). */
    void select(NdvSession session) {
        selected = session;
        refreshAnalyses();
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        String key = session == null ? NEW : session.id();
        centerCards.show(center, key);
        toolCards.show(tools, key);
        footCards.show(foot, key);
        showStatus();
        if (session == null) {
            SwingUtilities.invokeLater(form::focusServer);
        }
    }

    void closeSession(NdvSession session) {
        NdvBrowser known = browsers.get(session);
        if (known != null && !known.unsaved().isEmpty() && !Dialogs.confirm(this, "Sitzung schließen",
                "Ungespeicherte Änderungen in " + known.unsaved().stream().map(doc -> "„" + doc.name() + "“")
                        .collect(java.util.stream.Collectors.joining(", ")) + " wirklich verwerfen?")) {
            return;
        }
        discard(session);
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    /**
     * „Zugangsdaten ändern“ ({@link DeckCredentials}): das Formular ohne automatische Anmeldung, die Einträge neu aus
     * KeePass, eine Sperre nach einem Fehlschlag als Grund.
     */
    void editCredentials() {
        form.load();
        select(null);
        DeckCredentials.showLock(NdvConnectForm.rememberedEntry(), form::problem);
    }

    /**
     * „Neue Verbindung“: Nach dem ersten erfolgreichen Verbinden geht es sofort mit den gemerkten Daten los (Angelo
     * 23.11.); das Formular erscheint nur, wenn nichts gemerkt ist, diese Verbindung schon offen ist oder das
     * Verbinden misslingt (dann mit Grund).
     */
    void newConnection() {
        java.util.Optional<NdvConnectForm.Working> working = NdvConnectForm.working();
        if (working.isPresent()) {
            connectOrForm(working);
            return;
        }
        // Noch nie verbunden: der gemerkte KeePass-Eintrag (auch der des Terminals) reicht, wenn er Adresse und
        // Benutzer hat. Die Einträge kommen im Hintergrund.
        select(null);
        if (discovering) {
            return;
        }
        discovering = true;
        background.execute(() -> {
            java.util.List<com.aresstack.spaceharvester.deck.access.ServerAccount> list;
            try {
                list = accounts.accounts();
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> discovering = false);
                return; // das Formular lädt selbst und zeigt den Grund
            }
            java.util.Optional<NdvConnectForm.Working> remembered = NdvConnectForm.fromRemembered(list);
            SwingUtilities.invokeLater(() -> {
                discovering = false;
                if (remembered.isPresent() && selected == null && fresh.isEmpty()) {
                    connectOrForm(remembered);
                }
            });
        });
    }

    /**
     * Meldet dem Sperrschutz des Deck jeden Übergang (Angelo 23.11.: Schutz vor der Kontosperre, je KeePass-Eintrag):
     * Verbunden löst die Sperre des Eintrags, ein Fehlschlag setzt sie. Dass ein Fehlschlag am Netz statt am Passwort
     * lag, ist hier nicht zu unterscheiden; gesperrt wird trotzdem, lieber einmal zu vorsichtig als ein gesperrtes
     * Konto. Der Mensch kann im Formular jederzeit selbst verbinden.
     */
    private void report(NdvSession session) {
        NdvState now = session.state();
        if (reported.get(session) == now) {
            return;
        }
        reported.put(session, now);
        GuardedLogin login = logins.get(session);
        if (now == NdvState.CONNECTED) {
            if (login != null) {
                login.sent.set(false);
            }
            // Gemeldet wird erst auf dem EDT, also verspätet: Eine Ablehnung desselben Eintrags, die NACH diesem
            // Anmeldeversuch kam (etwa von FTP), ist neuer als der Erfolg und bleibt stehen.
            java.time.Instant attempt = login == null ? null : login.attemptAt;
            boolean newerRejection = attempt != null && DeckLogin.guard().failure(session.account())
                    .map(failure -> !failure.at().isBefore(attempt)).orElse(false);
            if (!newerRejection) {
                DeckLogin.guard().succeeded(session.account());
            }
        } else if (now == NdvState.FAILED && (login == null || login.sent.getAndSet(false))) {
            // Nur wenn das Passwort wirklich gesendet wurde; ein vom Sperrschutz verhinderter Versuch zählt nicht.
            DeckLogin.guard().failed(session.account(),
                    NdvBrowser.oneLine(session.failure().orElse("Anmeldung fehlgeschlagen")));
        }
    }

    /**
     * Verbindet von selbst mit den gemerkten Daten. Ist diese Verbindung schon offen, zeigt es das Formular für eine
     * andere; ist der Eintrag nach einem Fehlschlag gesperrt, das Formular mit dem Grund, ohne Versuch.
     */
    private void connectOrForm(java.util.Optional<NdvConnectForm.Working> working) {
        if (working.isEmpty() || pillOf.keySet().stream().anyMatch(open -> open.profile().host()
                .equalsIgnoreCase(working.get().profile().host()) && open.profile().port() == working.get().profile()
                .port())) {
            select(null);
            return;
        }
        String entry = working.get().account();
        if (!DeckLogin.guard().mayTry(entry)) {
            select(null);
            form.problem(DeckLogin.notice(entry));
            return;
        }
        GuardedLogin guarded = new GuardedLogin(entry, () -> accounts.credentials(entry), false);
        NdvSession opened = sessions.open(working.get().profile(), entry, guarded);
        logins.put(opened, guarded);
        fresh.add(opened);
        if (pillOf.containsKey(opened)) {
            changed(opened);
        }
    }

    /** Nimmt eine Sitzung ohne Rückfrage weg: trennen, Dokumente schließen, Reiter entfernen. */
    private void discard(NdvSession session) {
        fresh.remove(session);
        reported.remove(session);
        logins.remove(session);
        java.util.function.Consumer<NdvSession> observer = observers.remove(session);
        if (observer != null) {
            session.unobserve(observer);
        }
        sessions.close(session);
        NdvBrowser browser = browsers.remove(session);
        refreshAnalyses();
        if (browser != null) {
            browser.closeDocuments();
            center.remove(browser);
            tools.remove(browser.tools());
            foot.remove(browser.foot());
        }
        ComposerToggleButton pill = pillOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
    }

    /** Der Stand der gewählten Sitzung, ohne Sitzung der Hinweis zum Formular, links in der Statuszeile der App. */
    private void showStatus() {
        NdvBrowser browser = selected == null ? null : browsers.get(selected);
        statusInfo.show(browser == null ? com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(NO_SESSION)
                : browser.statusNote());
    }

    /**
     * Die Aktionen der gewählten Sitzung, mittig neben der Lupe in der gemeinsamen Fußleiste des Command Deck; das
     * Formular „Neue Verbindung“ hat keine.
     */
    @Override
    public javax.swing.JComponent footTools() {
        return foot;
    }

    private void refreshAnalyses() {
        List<com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis> list = new java.util.ArrayList<>();
        NdvBrowser first = selected == null ? null : browsers.get(selected);
        if (first != null) {
            list.add(first.analysis());
        }
        for (NdvBrowser browser : browsers.values()) {
            if (browser != first) {
                list.add(browser.analysis());
            }
        }
        analyses = List.copyOf(list);
    }

    /** Die Analysen der offenen Sitzungen, die gewählte zuerst (für die Natural-Beziehungen im Editor). */
    List<com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis> analyses() {
        return analyses;
    }

    /**
     * Natural-Programm aus der JCL ({@code STACK=(LOGON steplib;programm)}) in der gewählten Sitzung öffnen, wie
     * {@code MainFrame.openNaturalFromJcl}. Ohne Sitzung sagt die Statuszeile, dass erst verbunden werden muss.
     *
     * @return ob eine Sitzung das Öffnen übernommen hat
     */
    boolean openFromJcl(String stepLib, String program) {
        NdvBrowser browser = selectedBrowser();
        if (browser == null) {
            statusInfo.show(warning("„" + program + "“ aus der JCL: zuerst mit NDV verbinden"));
            return false;
        }
        browser.openFromJcl(stepLib, program);
        return true;
    }

    /** Ein Natural-Objekt ({@code ndv://LIB/OBJEKT}) in der gewählten Sitzung öffnen und zur Zeile springen. */
    boolean openNatural(String library, String object, int line) {
        NdvBrowser browser = selectedBrowser();
        if (browser == null) {
            statusInfo.show(warning("„" + object + "“: zuerst mit NDV verbinden"));
            return false;
        }
        browser.openInLibrary(library, object, line);
        return true;
    }

    private static com.aresstack.spaceharvester.app.ui.controls.StatusNote warning(String text) {
        return new com.aresstack.spaceharvester.app.ui.controls.StatusNote(text,
                com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.WARNING, null, null);
    }

    private NdvBrowser selectedBrowser() {
        return selected == null ? null : browsers.get(selected);
    }

    /** Für Tests: der Text in der Statuszeile. */
    String statusLine() {
        return statusInfo.getText();
    }

    NdvSession selected() {
        return selected;
    }

    NdvBrowser browser(NdvSession session) {
        return browsers.get(session);
    }

    NdvConnectForm form() {
        return form;
    }

    /** Zustandspunkt wie beim Terminal: grün verbunden, blau im Aufbau, rot Fehler, grau getrennt. */
    static Icon dot(NdvState state) {
        return TerminalWorkspace.dot(switch (state) {
            case CONNECTING -> SessionState.CONNECTING;
            case CONNECTED -> SessionState.CONNECTED;
            case DISCONNECTED -> SessionState.DISCONNECTED;
            case FAILED -> SessionState.FAILED;
        });
    }

    /** Maustaste Zurück: im Browser der gewählten Sitzung einen Schritt zurück, wie sein Zurück-Knopf. */
    void navigateBack() {
        NdvBrowser browser = selectedBrowser();
        if (browser != null) {
            browser.navigateBack();
        }
    }

    /** Maustaste Vor. */
    void navigateForward() {
        NdvBrowser browser = selectedBrowser();
        if (browser != null) {
            browser.navigateForward();
        }
    }
}
