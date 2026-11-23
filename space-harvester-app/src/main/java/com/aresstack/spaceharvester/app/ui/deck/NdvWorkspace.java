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
public final class NdvWorkspace extends JPanel {

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
    private final ServerAccounts accounts;
    /** Reiter samt ✕ je Sitzung. */
    private final Map<NdvSession, JPanel> tabOf = new LinkedHashMap<>();
    /** Sitzungen, die noch nie verbunden waren: ihr erster Erfolg wird gemerkt, ihr Fehlschlag zeigt das Formular. */
    private final java.util.Set<NdvSession> fresh = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** Analysen der offenen Sitzungen, die gewählte zuerst; gelesen auch abseits des EDT (Beziehungen im Editor). */
    private volatile List<com.aresstack.spaceharvester.deck.ndv.analysis.NdvAnalysis> analyses = List.of();

    /** @param editors der gemeinsame Editor des Command Deck; NDV öffnet seine Quelltexte darin */
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
            NdvSession opened = sessions.open(profile, account, login);
            fresh.add(opened);
        });

        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung", "Neue NDV-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> newConnection());
        pillGroup.add(newPill);
        pills.add(newPill);
        tools.setOpaque(false);
        JPanel noTools = new JPanel();
        noTools.setOpaque(false);
        tools.add(noTools, NEW);
        JPanel head = DeckBars.head(pills, tools);

        JPanel formStage = new JPanel(new GridBagLayout());
        formStage.setBackground(Comic.SURFACE);
        formStage.add(form);
        center.setOpaque(false);
        center.add(formStage, NEW);

        // Fuß wie in den anderen Bereichen (DeckBars): nur die Aktionen der gewählten Sitzung, der Stand steht in der
        // Statuszeile der App. Das Formular hat keine Aktionen unten.
        foot.setOpaque(false);
        JPanel formFoot = new JPanel();
        formFoot.setOpaque(false);
        foot.add(formFoot, NEW);

        add(head, BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        add(DeckBars.foot(null, foot), BorderLayout.SOUTH);

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
        NdvBrowser browser = new NdvBrowser(session, sessions, editors, background, () -> closeSession(session));
        browsers.put(session, browser);
        refreshAnalyses();
        String key = session.id();
        center.add(browser, key);
        tools.add(browser.tools(), key);
        foot.add(browser.foot(), key);
        ComposerToggleButton pill = new ComposerToggleButton(dot(session.state()), session.label(),
                session.state().label());
        pill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        pill.getAccessibleContext().setAccessibleName(session.label());
        pill.addActionListener(e -> select(session));
        pill.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent event) {
                if (SwingUtilities.isMiddleMouseButton(event)) {
                    closeSession(session);
                }
            }
        });
        // Das ✕ trennt und schließt in einem Schritt (Angelo 23.11.), wie im Terminal direkt hinter dem Reiter.
        com.aresstack.spaceharvester.app.ui.IconButton cross = new com.aresstack.spaceharvester.app.ui.IconButton(
                ComposerIcons.close(), "Trennen und schließen (auch Mittelklick auf den Reiter)").compact();
        cross.addActionListener(e -> closeSession(session));
        JPanel tab = new JPanel(new BorderLayout(0, 0));
        tab.setOpaque(false);
        tab.add(pill, BorderLayout.CENTER);
        tab.add(cross, BorderLayout.EAST);
        pillGroup.add(pill);
        pills.add(tab, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        tabOf.put(session, tab);
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
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
     * „Neue Verbindung“: Nach dem ersten erfolgreichen Verbinden geht es sofort mit den gemerkten Daten los (Angelo
     * 23.11.); das Formular erscheint nur, wenn nichts gemerkt ist, diese Verbindung schon offen ist oder das
     * Verbinden misslingt (dann mit Grund).
     */
    void newConnection() {
        java.util.Optional<NdvConnectForm.Working> working = NdvConnectForm.working();
        if (working.isEmpty() || pillOf.keySet().stream().anyMatch(open -> open.profile().host()
                .equalsIgnoreCase(working.get().profile().host()) && open.profile().port() == working.get().profile()
                .port())) {
            select(null);
            return;
        }
        String entry = working.get().account();
        NdvSession opened = sessions.open(working.get().profile(), entry, () -> accounts.credentials(entry));
        fresh.add(opened);
        if (pillOf.containsKey(opened)) {
            changed(opened);
        }
    }

    /** Nimmt eine Sitzung ohne Rückfrage weg: trennen, Dokumente schließen, Reiter entfernen. */
    private void discard(NdvSession session) {
        fresh.remove(session);
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
        JPanel tab = tabOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(tab != null ? tab : pill);
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
}
