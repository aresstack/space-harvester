package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSession;
import com.aresstack.spaceharvester.deck.betaview.BetaViewSessions;
import com.aresstack.spaceharvester.deck.betaview.ConnectionState;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „BetaView“ des Command Deck (#1142), Funktionen wie im BetaView-Reiter von MainframeMate: oben die
 * Anmeldungen als Pillen mit Zustandspunkt und rechts „Neue Anmeldung“, darunter das Anmeldeformular oder die gewählte
 * Sitzung (Suche links, Dokumente rechts). Die Sitzungen gehören dem Anwendungsfall {@link BetaViewSessions}; der
 * Wechsel des Bereichs oder der Perspektive meldet nichts ab. Nichts läuft, bevor jemand „Anmelden“ drückt.
 */
public final class BetaViewWorkspace extends JPanel {

    /**
     * Stand dieses Bereichs links in der Statuszeile der App (über {@code CommandDeck}): Zustand, Hinweise, Fehler rot
     * ({@code StatusNote.Tone.ERROR}). Lange Texte kürzt das Label selbst, der volle Text steht im Tooltip.
     */
    private final com.aresstack.spaceharvester.app.ui.controls.StatusLabel statusInfo =
            new com.aresstack.spaceharvester.app.ui.controls.StatusLabel();

    /** Die Karte dieses Bereichs in der Statuszeile der App. */
    public javax.swing.JComponent statusInfo() {
        return statusInfo;
    }

    private static final String FORM = "form";

    private final BetaViewSessions sessions;
    private final Prompts prompts;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<BetaViewSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<BetaViewSession, SessionPanel> panelOf = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final BetaViewConnectForm form;
    private BetaViewSession selected;

    /**
     * @param accounts die gemeinsamen Host-Zugänge des Command Deck (KeePass), dieselben wie Terminal, FTP und NDV
     */
    public BetaViewWorkspace(BetaViewSessions sessions, ServerAccounts accounts) {
        this(sessions, accounts, Prompts.dialogs());
    }

    BetaViewWorkspace(BetaViewSessions sessions, ServerAccounts accounts, Prompts prompts) {
        super(new BorderLayout());
        this.sessions = sessions;
        this.prompts = prompts;
        setBackground(Comic.SURFACE);
        form = new BetaViewConnectForm(accounts, sessions::open);

        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Anmeldung",
                "Bei einem BetaView-Server anmelden");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> select(null));
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        head.add(pills, BorderLayout.CENTER);

        JPanel formHolder = new JPanel(new GridBagLayout());
        formHolder.setOpaque(false);
        formHolder.add(form);
        center.setOpaque(false);
        center.add(formHolder, FORM);

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        select(null);
    }

    private void added(BetaViewSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        ComposerToggleButton pill = new ComposerToggleButton(dot(session.state()), session.profile().label(),
                session.state().label());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.profile().label());
        pill.addActionListener(e -> select(session));
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        SessionPanel panel = new SessionPanel(session, prompts, () -> close(session));
        panelOf.put(session, panel);
        center.add(panel, session.id());
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(BetaViewSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            pill.setToolTipText(session.failure().map(reason -> session.state().label() + ": " + reason)
                    .orElse(session.state().label()));
        }
        SessionPanel panel = panelOf.get(session);
        if (panel != null) {
            panel.stateChanged();
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Anmeldung“). */
    void select(BetaViewSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        cards.show(center, session == null ? FORM : session.id());
        if (session == null) {
            SwingUtilities.invokeLater(form::focusFirst);
        }
    }

    /** „Abmelden“: Sitzung schließen und aus der Liste nehmen. */
    void close(BetaViewSession session) {
        sessions.close(session);
        ComposerToggleButton pill = pillOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        SessionPanel panel = panelOf.remove(session);
        if (panel != null) {
            center.remove(panel);
        }
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    /** Alle Sitzungen abmelden (Programmende). */
    public void shutdown() {
        sessions.closeAll();
    }

    BetaViewSession selected() {
        return selected;
    }

    SessionPanel panel(BetaViewSession session) {
        return panelOf.get(session);
    }

    BetaViewConnectForm form() {
        return form;
    }

    /** Zustandspunkt: grün angemeldet, blau im Aufbau, rot Fehler, grau abgemeldet (Ampel der App). */
    static Icon dot(ConnectionState state) {
        Color color = switch (state) {
            case CONNECTED -> Comic.GREEN;
            case CONNECTING -> Comic.BLUE;
            case FAILED -> Comic.RED;
            case DISCONNECTED -> Comic.MUTED;
        };
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x + 2, y + 2, 8, 8);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 12;
            }

            @Override
            public int getIconHeight() {
                return 12;
            }
        };
    }
}
