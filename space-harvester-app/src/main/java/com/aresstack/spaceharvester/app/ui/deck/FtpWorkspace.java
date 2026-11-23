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
        newPill.addActionListener(e -> select(null));
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
        sessions.open(profile, entry, () -> accounts.credentials(entry));
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
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        FtpSessionView view = new FtpSessionView(session, this::closeSession,
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

    private void closeSession(FtpSession session) {
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
