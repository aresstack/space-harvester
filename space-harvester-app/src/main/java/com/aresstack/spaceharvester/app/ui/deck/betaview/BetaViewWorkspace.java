package com.aresstack.spaceharvester.app.ui.deck.betaview;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.deck.DeckLogin;
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
public final class BetaViewWorkspace extends JPanel
        implements com.aresstack.spaceharvester.app.ui.deck.DeckBars.FootTools {

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

    /** Wo der gemerkte KeePass-Eintrag steht (für die Kachel auf der Startseite des Deck). */
    public static final String ENTRY_KEY = BetaViewConnectForm.ENTRY_KEY;

    /** Wie viele Anmeldungen gerade offen sind (für die Kachel auf der Startseite des Deck). */
    public long connectedCount() {
        return sessions.all().stream()
                .filter(s -> s.state() == com.aresstack.spaceharvester.deck.betaview.ConnectionState.CONNECTED)
                .count();
    }
    private final Prompts prompts;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<BetaViewSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<BetaViewSession, SessionPanel> panelOf = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CardLayout cards = new CardLayout();
    /** Die Werkzeuge des gezeigten Dokuments je Sitzung, mittig neben der Lupe in der Fußleiste des Command Deck. */
    private final CardLayout footCards = new CardLayout();
    private final JPanel footTools = new JPanel(footCards);
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
        newPill.addActionListener(e -> newLogin());
        com.aresstack.spaceharvester.app.ui.deck.DeckCredentials.menu(newPill, this::editCredentials);
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel head = com.aresstack.spaceharvester.app.ui.deck.DeckBars.head(pills,
                com.aresstack.spaceharvester.app.ui.deck.DeckCredentials.button(this::editCredentials));

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
        // Sind die Zugangsdaten schon da, meldet BetaView beim ersten Zeigen von selbst an (Angelo, 23.11.).
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !autoTried) {
                autoTried = true;
                if (pillOf.isEmpty() && BetaViewConnectForm.isConfigured()) {
                    newLogin();
                }
            }
        });
    }

    /** Die automatische Anmeldung beim ersten Zeigen ist schon gelaufen. */
    private boolean autoTried;

    /**
     * „Zugangsdaten ändern“ ({@code DeckCredentials}): das Formular ohne automatische Anmeldung, die Einträge neu aus
     * KeePass, eine Sperre nach einem Fehlschlag als Grund. Wer zuletzt „Manuell“ gewählt hatte, gibt dort Benutzer
     * und neues Kennwort ein.
     */
    void editCredentials() {
        autoTried = true;
        form.loadEntries();
        select(null);
        com.aresstack.spaceharvester.app.ui.deck.DeckCredentials.showLock(BetaViewConnectForm.savedEntry(),
                form::notice);
    }

    /**
     * „Neue Anmeldung“ (und das erste Zeigen): Sind Zugangsdaten da (KeePass-Eintrag), meldet sie direkt an; das
     * Formular kommt nur ohne Zugangsdaten und wenn es scheitert (Deck-Regel, Angelo 23.11.).
     */
    void newLogin() {
        if (!BetaViewConnectForm.isConfigured()) {
            select(null);
            return;
        }
        // Schutz vor der Kontosperre (Angelo, 23.11.): Ist die letzte Anmeldung mit diesem KeePass-Eintrag
        // gescheitert, egal in welchem Bereich, kein automatischer Versuch, sondern das Formular mit dem Grund.
        String entry = BetaViewConnectForm.savedEntry();
        if (!DeckLogin.guard().mayTry(entry)) {
            select(null);
            form.notice(DeckLogin.notice(entry));
            return;
        }
        BetaViewSession before = selected;
        if (before != null && pillOf.containsKey(before)) {
            pillOf.get(before).setSelected(true);
        }
        directPending = true;
        form.connectDirectly(reason -> {
            directPending = false;
            select(null);
        });
    }

    /** Die nächste geöffnete Sitzung stammt aus einer direkten Anmeldung. */
    private boolean directPending;
    /** Sitzungen aus einer direkten Anmeldung, bis sie angemeldet sind oder scheitern. */
    private final java.util.Set<BetaViewSession> direct = new java.util.HashSet<>();

    private void added(BetaViewSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        if (directPending) {
            directPending = false;
            direct.add(session);
        }
        String entry = form.takeAttemptEntry();
        if (!entry.isEmpty() && session.state() != ConnectionState.DISCONNECTED) {
            loggingIn.put(session, entry);
        }
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(dot(session.state()), session.profile().label(),
                session.state().label(), "Abmelden und schließen (auch Mittelklick auf den Reiter)",
                () -> close(session));
        pill.addActionListener(e -> select(session));
        com.aresstack.spaceharvester.app.ui.deck.DeckCredentials.menu(pill, this::editCredentials);
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        SessionPanel panel = new SessionPanel(session, prompts, () -> close(session));
        panelOf.put(session, panel);
        panel.onStatus(note -> {
            if (selected == session) {
                statusInfo.show(note);
            }
        });
        center.add(panel, session.id());
        footTools.add(panel.documents.footTools(), session.id());
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
        if (session.state() != ConnectionState.CONNECTING) {
            // Die Anmeldung war schneller als der Reiter: Ergebnis jetzt auswerten.
            changed(session);
        }
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
        String entry = loggingIn.get(session);
        boolean refused = false;
        if (session.state() == ConnectionState.CONNECTED) {
            direct.remove(session);
            if (loggingIn.remove(session) != null) {
                DeckLogin.guard().succeeded(entry);
            }
            if (BetaViewConnectForm.KEEPASS.equals(com.aresstack.spaceharvester.app.ui.AppState.shared()
                    .get(BetaViewConnectForm.LOGIN_KEY))) {
                // Erst nach dem ersten Erfolg gilt die gemerkte Anmeldung als eingerichtet.
                BetaViewConnectForm.configured();
            }
        } else if (session.state() == ConnectionState.FAILED && loggingIn.remove(session) != null
                && rejected(session.failure().orElse(""))) {
            // Der Server hat die Anmeldung abgelehnt: Eintrag sperren, überall, bis eine Anmeldung klappt.
            DeckLogin.guard().failed(entry, session.failure().orElse("Anmeldung abgelehnt"));
            refused = true;
        }
        if (session.state() == ConnectionState.FAILED && (direct.remove(session) | refused)) {
            // Direkte Anmeldung gescheitert oder Zugang abgelehnt: keine tote Sitzung stehen lassen, sondern sofort
            // das Formular mit dem Grund, damit die Zugangsdaten geändert werden können (Angelo, 24.11.2026).
            String reason = session.failure().orElse("Anmeldung gescheitert");
            close(session);
            select(null);
            form.failed(reason);
        }
    }

    /** Sitzungen in der Anmeldung mit einem KeePass-Eintrag, bis sie angemeldet sind oder scheitern. */
    private final java.util.Map<BetaViewSession, String> loggingIn = new java.util.HashMap<>();

    /**
     * Ob der Grund eine Ablehnung der Anmeldung durch den Server ist (falsches oder abgelaufenes Kennwort, HTTP
     * 401/403) und nicht ein Netz- oder Proxyfehler; nur eine Ablehnung sperrt den Eintrag.
     */
    static boolean rejected(String reason) {
        return reason.startsWith("Anmeldung abgelehnt") && !reason.matches("Anmeldung abgelehnt: HTTP (?!40[13]).*");
    }

    /** Die Werkzeuge zum Dokument der gewählten Sitzung; das Formular „Neue Anmeldung“ hat keine. */
    @Override
    public javax.swing.JComponent footTools() {
        return footTools;
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Anmeldung“). */
    void select(BetaViewSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        cards.show(center, session == null ? FORM : session.id());
        footCards.show(footTools, session == null || !panelOf.containsKey(session) ? FORM : session.id());
        // Die Statuszeile der App zeigt den Stand der gewählten Sitzung; ohne Sitzung nichts.
        SessionPanel shown = session == null ? null : panelOf.get(session);
        statusInfo.show(shown == null ? com.aresstack.spaceharvester.app.ui.controls.StatusNote.plain(" ")
                : shown.status());
        if (session == null) {
            SwingUtilities.invokeLater(form::focusFirst);
        }
    }

    /** Das Kreuz am Reiter: abmelden, Sitzung schließen und aus der Liste nehmen, in einem Schritt. */
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
            footTools.remove(panel.documents.footTools());
        }
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    /** Alle Sitzungen abmelden (Programmende). */
    public void shutdown() {
        sessions.closeAll();
    }

    /** Maustaste Zurück in den Dokumenten der gewählten Sitzung. */
    public void navigateBack() {
        SessionPanel panel = selected == null ? null : panelOf.get(selected);
        if (panel != null) {
            panel.documents.navigateBack();
        }
    }

    /** Maustaste Vor. */
    public void navigateForward() {
        SessionPanel panel = selected == null ? null : panelOf.get(selected);
        if (panel != null) {
            panel.documents.navigateForward();
        }
    }

    BetaViewSession selected() {
        return selected;
    }

    SessionPanel panel(BetaViewSession session) {
        return panelOf.get(session);
    }

    /** Anzahl der Sitzungs-Reiter (für Tests). */
    int sessionCount() {
        return pillOf.size();
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
