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
public final class FtpWorkspace extends JPanel {

    static final String VIEW_KEY = "deck.ftp.view";
    private static final String FORM = "form";

    private final FtpSessions sessions;
    private final ServerAccounts accounts;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<FtpSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<FtpSession, FtpSessionView> viewOf = new LinkedHashMap<>();
    /** Reiter samt ✕ je Sitzung: das ✕ trennt und schließt in einem Schritt (Angelo, 23.11.2026). */
    private final Map<FtpSession, JComponent> tabOf = new java.util.HashMap<>();
    /**
     * Sitzungen, die noch nie verbunden waren. Scheitern sie, bleibt kein toter Reiter: Das Formular kommt mit dem
     * Grund wieder. Klappt es, verbindet „Neue Verbindung“ künftig ohne Formular.
     */
    private final java.util.Set<FtpSession> firstConnect = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Beim ersten Zeigen des Bereichs ohne Sitzung wird die gemerkte Verbindung aufgebaut. */
    private boolean autoTried;
    private final ComposerToggleButton newPill;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final FtpConnectForm form;
    private final SegmentedToggle toggle = new SegmentedToggle();
    private final EditorSessions editors;
    /** Der Stand der gewählten Sitzung für die Statuszeile der App (links); leer beim Formular. */
    private final StatusLabel status = new StatusLabel();
    private FtpSession selected;

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
        // Rechtsklick: das Formular trotzdem zeigen, für einen anderen Server oder andere Einstellungen.
        newPill.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                formMenu(event);
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent event) {
                formMenu(event);
            }
        });
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel head = DeckBars.head(pills, null);

        JPanel formHolder = new JPanel(new GridBagLayout());
        formHolder.setBackground(Comic.SURFACE);
        formHolder.add(form);
        center.setBackground(Comic.SURFACE);
        center.add(formHolder, FORM);

        toggle.segment(FtpSessionView.DATASETS).segment(FtpSessionView.JOBS);
        toggle.setToolTipText("Unteransicht des FTP-Bereichs: MVS-Datasets oder JES-Jobs mit Spool");
        toggle.getAccessibleContext().setAccessibleName("Datasets oder JES-Jobs");
        String remembered = AppState.shared().get(VIEW_KEY);
        if (FtpSessionView.JOBS.equals(remembered)) {
            toggle.select(FtpSessionView.JOBS);
        }
        toggle.onSelect(view -> {
            AppState.shared().put(VIEW_KEY, view);
            if (selected != null) {
                viewOf.get(selected).show(view);
            }
        });

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        select(null);
        // Wer einmal erfolgreich verbunden war, bekommt beim Öffnen des Bereichs direkt seine Verbindung.
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !autoTried) {
                autoTried = true;
                if (pillOf.isEmpty() && FtpConnectForm.isConfigured()) {
                    newConnection();
                }
            }
        });
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
        status.show(StatusNote.plain("Verbinde mit der gemerkten Verbindung …"));
        if (selected != null) {
            pillOf.get(selected).setSelected(true);
        }
        form.connectDirectly(reason -> select(null));
    }

    /** Das Formular auch, wenn die Verbindung eingerichtet ist (anderer Server, andere Einstellungen). */
    void showForm() {
        select(null);
    }

    private void formMenu(java.awt.event.MouseEvent event) {
        if (!event.isPopupTrigger()) {
            return;
        }
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        javax.swing.JMenuItem item = new javax.swing.JMenuItem("Verbindung wählen … (anderer Server, Einstellungen)");
        item.setFont(Comic.regular(13f));
        item.addActionListener(e -> showForm());
        menu.add(item);
        menu.show(event.getComponent(), event.getX(), event.getY());
    }

    /** Der Stand des FTP-Bereichs für die Statuszeile der App: Meldungen der gewählten Sitzung, Fehler rot. */
    public JComponent statusInfo() {
        return status;
    }

    /** Für Tests: was die Statuszeile gerade zeigt. */
    StatusNote statusNote() {
        return status.note();
    }

    /** Der Umschalter „Datasets | JES-Jobs“ für die Titelleiste (nur im FTP-Bereich gezeigt). */
    public JComponent tools() {
        return toggle;
    }

    private void connect(FtpProfile profile, String entry) {
        FtpSession session = sessions.open(profile, entry, () -> accounts.credentials(entry));
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

    private void added(FtpSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        ComposerToggleButton pill = new ComposerToggleButton(dot(session.state()), session.label(),
                session.state().label());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.label());
        pill.addActionListener(e -> select(session));
        pill.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    closeTab(session);
                }
            }
        });
        // Gezeichnetes Kreuz (nicht das Zeichen „✕“, das nicht jede Schrift hat), direkt hinter dem Reiter.
        com.aresstack.spaceharvester.app.ui.IconButton cross = new com.aresstack.spaceharvester.app.ui.IconButton(
                ComposerIcons.close(), "Trennen und schließen (auch Mittelklick auf den Reiter)").compact();
        cross.addActionListener(e -> closeTab(session));
        JPanel tab = new JPanel(new BorderLayout(0, 0));
        tab.setOpaque(false);
        tab.add(pill, BorderLayout.CENTER);
        tab.add(cross, BorderLayout.EAST);
        pillGroup.add(pill);
        pills.add(tab, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        tabOf.put(session, tab);
        FtpSessionView view = new FtpSessionView(session,
                () -> toggle.select(FtpSessionView.JOBS), editors);
        view.show(toggle.selected());
        viewOf.put(session, view);
        center.add(view, session.id());
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        // Ein Wechsel vor dem Anmelden des Beobachters ginge sonst verloren.
        changed(session);
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(FtpSession session) {
        if (firstConnect.contains(session)) {
            if (session.state() == ConnectionState.CONNECTED) {
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
            status.show(StatusNote.plain(" "));
            cards.show(center, FORM);
            SwingUtilities.invokeLater(form::focusServer);
        } else {
            FtpSessionView view = viewOf.get(session);
            view.show(toggle.selected());
            view.statusTo(status::show);
            cards.show(center, session.id());
        }
    }

    /** Eine nie verbundene Sitzung ist gescheitert: Reiter weg, Formular mit dem Grund. */
    private void failedFirst(FtpSession session) {
        if (!firstConnect.remove(session) && session.state() != ConnectionState.FAILED) {
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
        sessions.close(session);
        ComposerToggleButton pill = pillOf.remove(session);
        FtpSessionView view = viewOf.remove(session);
        JComponent tab = tabOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(tab == null ? pill : tab);
            pills.revalidate();
            pills.repaint();
        }
        if (view != null) {
            center.remove(view);
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
}
