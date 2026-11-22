package com.aresstack.spaceharvester.app.ui.deck;

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
import java.util.Map;
import java.util.concurrent.Executor;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „NDV“ des Command Deck (Natural Development Server, #1142): oben links die Sitzungen als Pillen mit
 * Zustandspunkt und „Neue Verbindung“, oben rechts Filter und Neu laden der gewählten Sitzung, in der Mitte
 * Bibliotheken, Objekte und Quelltext ({@link NdvBrowser}), unten Zustand und Aktionen. Die Sitzungen gehören dem
 * Anwendungsfall {@link NdvSessions}; Wechsel des Bereichs oder der Perspektive trennen nichts und laden nichts.
 */
public final class NdvWorkspace extends JPanel {

    private static final String NEW = "new";

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
    private NdvSession selected;

    public NdvWorkspace(NdvSessions sessions, ServerAccounts accounts) {
        this(sessions, accounts, com.aresstack.spaceharvester.app.ui.Background.executor());
    }

    NdvWorkspace(NdvSessions sessions, ServerAccounts accounts, Executor background) {
        super(new BorderLayout());
        this.sessions = sessions;
        this.background = background;
        setBackground(Comic.SURFACE);
        getAccessibleContext().setAccessibleName("NDV");
        form = new NdvConnectForm(accounts, sessions::open);

        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung", "Neue NDV-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> select(null));
        pillGroup.add(newPill);
        pills.add(newPill);
        tools.setOpaque(false);
        JPanel noTools = new JPanel();
        noTools.setOpaque(false);
        tools.add(noTools, NEW);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        head.add(pills, BorderLayout.CENTER);
        head.add(tools, BorderLayout.EAST);

        JPanel formStage = new JPanel(new GridBagLayout());
        formStage.setBackground(Comic.SURFACE);
        formStage.add(form);
        center.setOpaque(false);
        center.add(formStage, NEW);

        foot.setOpaque(false);
        JPanel formFoot = new JPanel(new BorderLayout());
        formFoot.setOpaque(false);
        formFoot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        JLabel hint = Comic.muted("Keine Sitzung gewählt – Server wählen und verbinden");
        formFoot.add(hint, BorderLayout.CENTER);
        // Gleiche Höhe wie der Fuß einer Sitzung mit Knöpfen.
        formFoot.add(javax.swing.Box.createVerticalStrut(Comic.button("Öffnen", null).getPreferredSize().height),
                BorderLayout.EAST);
        foot.add(formFoot, NEW);

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        if (pillOf.isEmpty()) {
            select(null);
        }
    }

    private void added(NdvSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        NdvBrowser browser = new NdvBrowser(session, sessions, background, () -> closeSession(session));
        browsers.put(session, browser);
        String key = session.id();
        center.add(browser, key);
        tools.add(browser.tools(), key);
        foot.add(browser.foot(), key);
        ComposerToggleButton pill = new ComposerToggleButton(dot(session.state()), session.label(),
                session.state().label());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.label());
        pill.addActionListener(e -> select(session));
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(NdvSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            pill.setToolTipText(session.profile().label() + " · " + session.failure()
                    .map(reason -> session.state().label() + ": " + reason).orElse(session.state().label()));
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Verbindung“). */
    void select(NdvSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        String key = session == null ? NEW : session.id();
        centerCards.show(center, key);
        toolCards.show(tools, key);
        footCards.show(foot, key);
        if (session == null) {
            SwingUtilities.invokeLater(form::focusServer);
        }
    }

    private void closeSession(NdvSession session) {
        sessions.close(session);
        NdvBrowser browser = browsers.remove(session);
        if (browser != null) {
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
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
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
}
