package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.app.ui.controls.SegmentedToggle;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.editor.EditorSessions;
import com.aresstack.spaceharvester.deck.ftp.ConnectionState;
import com.aresstack.spaceharvester.deck.ftp.FtpProfile;
import com.aresstack.spaceharvester.deck.ftp.FtpSession;
import com.aresstack.spaceharvester.deck.ftp.FtpSessions;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „FTP“ des Command Deck (#1142): oben die Sitzungen als Pillen mit Zustandspunkt und „Neue
 * Verbindung“, in der Mitte die gewählte Sitzung mit ihren Unteransichten Datasets und JES-Jobs. Den Umschalter
 * „Datasets | JES-Jobs“ stellt der Bereich oben rechts in die Titelleiste ({@link #tools()}); er gilt für alle
 * Sitzungen. Die Sitzungen gehören dem Anwendungsfall {@link FtpSessions}; Wechsel trennen nichts.
 */
public final class FtpWorkspace extends JPanel implements DeckBars.FootTools {

    static final String VIEW_KEY = "deck.ftp.view";
    private static final String FORM = "form";

    private final FtpSessions sessions;
    private final ServerAccounts accounts;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<FtpSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<FtpSession, FtpSessionView> viewOf = new LinkedHashMap<>();
    /**
     * Sitzungen, die noch nie verbunden waren. Scheitern sie, bleibt kein toter Reiter: Das Formular kommt mit dem
     * Grund wieder. Klappt es, verbindet „Neue Verbindung“ künftig ohne Formular.
     */
    private final java.util.Set<FtpSession> firstConnect = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /**
     * Sitzungen, die schon einmal verbunden waren – auf dem Faden des Zustandswechsels vermerkt, nicht verspätet über
     * die Oberfläche: Ein späterer Abriss (FAILED) ist dann kein „erster Verbindungsversuch gescheitert“ mehr.
     */
    private final java.util.Set<FtpSession> connectedOnce = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Ein Verbinden mit der gemerkten Verbindung läuft (KeePass wird gelesen), noch ohne Reiter. */
    private boolean directPending;
    private final ComposerToggleButton newPill;
    private final CardLayout cards = new CardLayout();
    /** Neben der Lupe in der Fußleiste des Command Deck: die Aktionen der gewählten Sitzung. */
    private final CardLayout footCards = new CardLayout();
    private final JPanel footTools = new JPanel(footCards);
    private final JPanel center = new JPanel(cards);
    private final FtpConnectForm form;
    private final SegmentedToggle toggle = new SegmentedToggle();
    private final javax.swing.JButton storedCobolSearch = new javax.swing.JButton("COBOL-Suche");
    private final EditorSessions editors;
    private FtpNavigationObserver navigation = FtpNavigationObserver.NONE;
    /** Der Stand der gewählten Sitzung für die Statuszeile der App (links); leer beim Formular. */
    private final StatusLabel status = new StatusLabel();
    private volatile FtpSession selected;
    private volatile long chatGeneration;
    private Runnable chatChanged = () -> { };

    /** Mit eigenem Editor (Tests). */
    public FtpWorkspace(FtpSessions sessions, ServerAccounts accounts) {
        this(sessions, accounts, new EditorSessions(java.util.concurrent.ForkJoinPool.commonPool()));
    }

    /**
     * @param editors der gemeinsame Editor des Command Deck; FTP-Members öffnen darin
     */
    public FtpWorkspace(FtpSessions sessions, ServerAccounts accounts, EditorSessions editors) {
        super(new BorderLayout());
        this.sessions = sessions;
        this.editors = editors;
        this.accounts = accounts;
        setBackground(Comic.SURFACE);
        form = new FtpConnectForm(accounts, this::connect);

        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung", "Neue FTP-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> newConnection());
        // Rechtsklick: Zugangsdaten ändern (Formular trotz eingerichteter Verbindung, anderer Server).
        DeckCredentials.menu(newPill, this::editCredentials);
        pillGroup.add(newPill);
        pills.add(newPill);
        var headActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        headActions.setOpaque(false);
        storedCobolSearch.setVisible(false);
        headActions.add(storedCobolSearch);
        headActions.add(DeckCredentials.button(this::editCredentials));
        JPanel head = DeckBars.head(pills, headActions);

        JPanel formHolder = new JPanel(new GridBagLayout());
        formHolder.setBackground(Comic.SURFACE);
        formHolder.add(form);
        center.setBackground(Comic.SURFACE);
        center.add(formHolder, FORM);
        footTools.setOpaque(false);
        JPanel noTools = new JPanel();
        noTools.setOpaque(false);
        footTools.add(noTools, FORM);

        toggle.segment(FtpSessionView.DATASETS).segment(FtpSessionView.JOBS);
        toggle.setToolTipText("Unteransicht des FTP-Bereichs: MVS-Datasets oder JES-Jobs mit Spool");
        toggle.getAccessibleContext().setAccessibleName("Datasets oder JES-Jobs");
        String remembered = AppState.shared().get(VIEW_KEY);
        if (FtpSessionView.JOBS.equals(remembered)) {
            toggle.select(FtpSessionView.JOBS);
        }
        toggle.onSelect(view -> {
            chatGeneration++;
            AppState.shared().put(VIEW_KEY, view);
            if (selected != null) {
                viewOf.get(selected).show(view);
            }
            chatChanged.run();
        });

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        sessions.onOpened(session -> {
            // Vor dem ersten Anmelden: Jede abgelehnte Anmeldung dieser Sitzung (Datasets, JES, DD-Namen) sperrt den
            // KeePass-Eintrag in allen Bereichen; nur ein Erfolg löst die Sperre wieder.
            session.onLoginRefused(reason -> DeckLogin.guard().failed(session.label(), reason));
            // Erfolge auf demselben Faden wie die Ablehnungen, in echter Reihenfolge (nicht verspätet über die
            // Oberfläche): Ein älterer Erfolg löscht keine neuere Sperre.
            session.onLoginAccepted(() -> DeckLogin.guard().succeeded(session.label()));
            session.observe(changed -> {
                if (changed.state() == ConnectionState.CONNECTED) {
                    connectedOnce.add(changed);
                }
            });
            SwingUtilities.invokeLater(() -> added(session));
        });
        sessions.all().forEach(this::added);
        select(null);
        // Wer einmal erfolgreich verbunden war, bekommt beim Öffnen des Bereichs direkt seine Verbindung.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()) {
                shown();
            }
        });
    }

    /**
     * Der Bereich wird gezeigt: Ohne Sitzung baut er die gemerkte Verbindung sofort auf, jedes Mal (Angelo, 23.11.2026).
     * So klappt es auch, wenn KeePass beim vorigen Zeigen noch nicht bereit war.
     */
    void shown() {
        if (pillOf.isEmpty() && !directPending && FtpConnectForm.isConfigured()) {
            newConnection();
        }
    }

    /**
     * „Neue Verbindung“: Hat die gemerkte Verbindung schon einmal geklappt, verbindet sie direkt; das Formular kommt nur
     * beim ersten Mal und wenn es scheitert (Angelo, 23.11.2026).
     */
    void newConnection() {
        if (!FtpConnectForm.isConfigured()) {
            select(null);
            return;
        }
        // Schutz vor der Kontosperre: Ist die letzte Anmeldung mit diesem KeePass-Eintrag gescheitert (hier oder in
        // einem anderen Bereich), versucht nur noch der Mensch im Formular (Angelo, 23.11.2026).
        String entry = FtpConnectForm.rememberedEntry();
        if (!DeckLogin.guard().mayTry(entry)) {
            form.problem(DeckLogin.notice(entry));
            select(null);
            return;
        }
        status.show(StatusNote.plain("Verbinde mit der gemerkten Verbindung …"));
        if (selected != null) {
            pillOf.get(selected).setSelected(true);
        }
        directPending = true;
        form.connectDirectly(reason -> {
            directPending = false;
            select(null);
        });
    }

    /** Das Formular auch, wenn die Verbindung eingerichtet ist (anderer Server, andere Einstellungen). */
    void showForm() {
        select(null);
    }

    /**
     * „Zugangsdaten ändern“ ({@link DeckCredentials}): das Formular ohne automatische Anmeldung, die Einträge neu aus
     * KeePass, eine Sperre nach einem Fehlschlag als Grund.
     */
    void editCredentials() {
        form.load();
        showForm();
        DeckCredentials.showLock(FtpConnectForm.rememberedEntry(), form::problem);
    }

    /** Der Stand des FTP-Bereichs für die Statuszeile der App: Meldungen der gewählten Sitzung, Fehler rot. */
    public JComponent statusInfo() {
        return status;
    }

    /** Für Tests: was die Statuszeile gerade zeigt. */
    StatusNote statusNote() {
        return status.note();
    }

    @Override
    public JComponent footTools() {
        return footTools;
    }

    /** Der Umschalter „Datasets | JES-Jobs“ für die Titelleiste (nur im FTP-Bereich gezeigt). */
    public JComponent tools() {
        return toggle;
    }

    private void connect(FtpProfile profile, String entry) {
        directPending = false;
        FtpSession session = sessions.open(profile, entry, guarded(entry));
        if (session.state() == ConnectionState.CONNECTED) {
            FtpConnectForm.configured();
        } else if (session.state() == ConnectionState.FAILED) {
            SwingUtilities.invokeLater(() -> failedFirst(session));
        } else {
            firstConnect.add(session);
            // Schon entschieden, bevor der Eintrag stand? Dann holt changed es nach.
            SwingUtilities.invokeLater(() -> changed(session));
        }
    }

    /**
     * Die Zugangsdaten einer Sitzung hinter dem Anmelde-Wächter (Angelo, 23.11.2026): Die erste Anmeldung ist schon
     * geprüft (Formular von Hand oder gemerkte Verbindung nach {@code mayTry}). Jede weitere – Neuaufbau nach Abriss,
     * erste JES-Verbindung, DD-Namen nachladen, „Erneut verbinden“ – liest das Passwort nur, wenn der KeePass-Eintrag
     * nirgends gesperrt ist; sonst scheitert sie mit dem Grund, ohne den Host zu fragen.
     */
    private FtpSession.CredentialSource guarded(String entry) {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(true);
        return () -> {
            if (!first.getAndSet(false) && !DeckLogin.guard().mayTry(entry)) {
                throw new IllegalStateException(DeckLogin.notice(entry));
            }
            return accounts.credentials(entry);
        };
    }

    void storedCobolSearch(com.aresstack.spaceharvester.app.ui.AppContext context,
                           java.util.function.BooleanSupplier active) {
        var action = new StoredCobolSearchAction(context, this, active,
                busy -> storedCobolSearch.setText(busy ? "Suche abbrechen" : "COBOL-Suche"));
        storedCobolSearch.setVisible(true);
        storedCobolSearch.setToolTipText("Gespeicherte COBOL-Analysen durchsuchen; erneut klicken zum Abbrechen");
        storedCobolSearch.addActionListener(event -> action.open());
    }

    public void navigationObserver(FtpNavigationObserver observer) {
        navigation = java.util.Objects.requireNonNull(observer);
    }

    private void added(FtpSession session) {
        // Schon wieder geschlossen (etwa eine sofort gescheiterte erste Verbindung)? Dann kein Reiter.
        if (pillOf.containsKey(session) || !sessions.all().contains(session)) {
            return;
        }
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(dot(session.state()), session.label(), session.state().label(),
                "Trennen und schließen (auch Mittelklick auf den Reiter)", () -> closeTab(session));
        pill.addActionListener(e -> select(session));
        DeckCredentials.menu(pill, this::editCredentials);
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        FtpSessionView view = new FtpSessionView(session,
                () -> toggle.select(FtpSessionView.JOBS), editors, navigation);
        view.show(toggle.selected());
        viewOf.put(session, view);
        center.add(view, session.id());
        footTools.add(view.footTools(), session.id());
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
        // Ein Wechsel vor dem Anmelden des Beobachters ginge sonst verloren. Zuletzt, denn eine schon gescheiterte
        // erste Verbindung schließt den Reiter wieder und zeigt das Formular.
        changed(session);
    }

    private void changed(FtpSession session) {
        if (firstConnect.contains(session)) {
            if (connectedOnce.contains(session) || session.state() == ConnectionState.CONNECTED) {
                firstConnect.remove(session);
                FtpConnectForm.configured();
            } else if (session.state() == ConnectionState.FAILED) {
                failedFirst(session);
                return;
            }
        }
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            pill.setToolTipText(session.failure().map(reason -> session.state().label() + ": " + reason)
                    .orElse(session.state().label() + " · " + session.profile().label()));
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Verbindung“). */
    void select(FtpSession session) {
        if (selected != session && activeSessionView() != null) activeSessionView().datasets().revokeDiagnosis();
        chatGeneration++;
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        // Ohne Sitzung gibt es nichts umzuschalten; die Wippe bleibt sichtbar, aber gedimmt.
        toggle.button(FtpSessionView.DATASETS).setEnabled(session != null);
        toggle.button(FtpSessionView.JOBS).setEnabled(session != null);
        viewOf.forEach((other, view) -> {
            if (other != session) {
                view.statusTo(null);
            }
        });
        if (session == null) {
            status.show(StatusNote.plain(NdvWorkspace.NO_SESSION));
            cards.show(center, FORM);
            footCards.show(footTools, FORM);
            SwingUtilities.invokeLater(form::focusServer);
        } else {
            FtpSessionView view = viewOf.get(session);
            view.show(toggle.selected());
            view.statusTo(status::show);
            cards.show(center, session.id());
            footCards.show(footTools, session.id());
        }
        chatChanged.run();
    }

    FtpSessionView activeSessionView() {
        return viewOf.get(selected);
    }

    long chatGeneration() {
        return chatGeneration;
    }

    void onChatChanged(Runnable listener) {
        chatChanged = listener;
    }

    /** Eine nie verbundene Sitzung ist gescheitert: Reiter weg, Formular mit dem Grund. */
    private void failedFirst(FtpSession session) {
        if (connectedOnce.contains(session)
                || (!firstConnect.remove(session) && session.state() != ConnectionState.FAILED)) {
            firstConnect.remove(session);
            return;
        }
        String reason = session.failure().orElse("Anmeldung gescheitert");
        closeSession(session);
        form.problem(session.label() + ": " + reason);
        select(null);
    }

    /** ✕ oder Mittelklick: trennt und schließt in einem Schritt; nur ungesicherte Änderungen fragen nach. */
    void closeTab(FtpSession session) {
        FtpSessionView view = viewOf.get(session);
        if (view == null || view.datasets().closeDocuments()) {
            closeSession(session);
        }
    }

    private void closeSession(FtpSession session) {
        firstConnect.remove(session);
        connectedOnce.remove(session);
        sessions.close(session);
        ComposerToggleButton pill = pillOf.remove(session);
        FtpSessionView view = viewOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        if (view != null) {
            center.remove(view);
            footTools.remove(view.footTools());
        }
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    /** Für Tests. */
    FtpSession selected() {
        return selected;
    }

    FtpSessionView view(FtpSession session) {
        return viewOf.get(session);
    }

    FtpConnectForm form() {
        return form;
    }

    SegmentedToggle toggle() {
        return toggle;
    }

    /** Zustandspunkt wie bei den Terminal-Sitzungen. */
    static javax.swing.Icon dot(ConnectionState state) {
        return TerminalWorkspace.dot(switch (state) {
            case CONNECTED -> com.aresstack.spaceharvester.deck.terminal.SessionState.CONNECTED;
            case CONNECTING -> com.aresstack.spaceharvester.deck.terminal.SessionState.CONNECTING;
            case FAILED -> com.aresstack.spaceharvester.deck.terminal.SessionState.FAILED;
            case DISCONNECTED -> com.aresstack.spaceharvester.deck.terminal.SessionState.DISCONNECTED;
        });
    }

    /** Ein lesbarer Grund ohne Stacktrace. */
    static String describe(Throwable error) {
        return FtpSession.describe(error);
    }

    /** Maustaste Zurück: in den Datasets der gewählten Sitzung zum vorher gezeigten Pfad (wie Pfeil links). */
    void navigateBack() {
        FtpSessionView view = selected == null ? null : viewOf.get(selected);
        if (view != null && FtpSessionView.DATASETS.equals(view.view())) {
            view.datasets().back();
        }
    }

    /** Maustaste Vor (wie Pfeil rechts). */
    void navigateForward() {
        FtpSessionView view = selected == null ? null : viewOf.get(selected);
        if (view != null && FtpSessionView.DATASETS.equals(view.view())) {
            view.datasets().forward();
        }
    }
}
