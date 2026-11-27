package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.controls.ClosableTab;
import com.aresstack.spaceharvester.app.ui.controls.StatusLabel;
import com.aresstack.spaceharvester.app.ui.controls.StatusNote;
import com.aresstack.spaceharvester.deck.access.ServerAccounts;
import com.aresstack.spaceharvester.deck.eakte.EakteSession;
import com.aresstack.spaceharvester.deck.eakte.EakteSessions;
import com.aresstack.spaceharvester.deck.eakte.EakteState;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.FlowLayout;
import java.awt.GridBagLayout;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „E-Akte“ des Command Deck: oben die Verbindungen als Reiter ({@link ClosableTab}, das Kreuz trennt
 * und schließt) und „Neue Verbindung“, darunter das Formular oder die Mappe der gewählten Verbindung. Wie alle Bereiche:
 * verbunden wird erst beim ersten Zeigen (Klick auf Kachel oder Symbol), mit dem gemerkten eigenen KeePass-Eintrag
 * ohne Formular; das Formular kommt nur ohne Eintrag, bei gesperrtem Eintrag oder wenn es scheitert. Vor jedem
 * automatischen Versuch fragt der Bereich den {@link DeckLogin#guard() LoginGuard} je KeePass-Eintrag; eine Ablehnung
 * sperrt den Eintrag, bis eine Anmeldung klappt.
 */
public final class EakteWorkspace extends JPanel implements DeckBars.FootTools {

    private static final String FORM = "form";

    /** Wo der gemerkte KeePass-Eintrag steht (für die Kachel auf der Startseite). */
    public static final String ENTRY_KEY = EakteConnectForm.ENTRY_KEY;

    private final StatusLabel statusInfo = new StatusLabel();
    private final EakteSessions sessions;
    /** Vorschau der Dokumente: eigene Editorliste (nicht die des Bereichs Dateien), Viewer der App, nur lesen. */
    private final com.aresstack.spaceharvester.deck.editor.EditorSessions previews =
            new com.aresstack.spaceharvester.deck.editor.EditorSessions(java.util.concurrent.ForkJoinPool.commonPool());
    private final com.aresstack.spaceharvester.app.deck.DocumentPreviewStore previewStore =
            new com.aresstack.spaceharvester.app.deck.DocumentPreviewStore();
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<EakteSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final Map<EakteSession, EakteBrowser> browserOf = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CardLayout cards = new CardLayout();
    private final JPanel center = new JPanel(cards);
    private final CardLayout toolCards = new CardLayout();
    private final JPanel footTools = new JPanel(toolCards);
    /** Titelleiste: der Umschalter „Vorschau | Extraktion“ der gewählten Sitzung. */
    private final EakteConnectForm form;
    /** Sitzungen in der Anmeldung mit einem KeePass-Eintrag, bis sie angemeldet sind oder scheitern. */
    private final Map<EakteSession, String> loggingIn = new HashMap<>();
    /** Sitzungen aus einer Verbindung ohne Formular: scheitern sie, verschwinden sie und das Formular erscheint. */
    private final Set<EakteSession> direct = new HashSet<>();
    private boolean directPending;
    private boolean autoTried;
    private EakteSession selected;
    /** Dokumentanalyse für die Extraktion jedes Browsers (ADR 0035); ohne Analysedienst nichts. */
    private com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider analyses =
            com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider.NONE;

    /**
     * @param accounts Zugänge aus KeePass (dieselbe Kopplung wie die anderen Bereiche, aber ein eigener Eintrag)
     */
    public EakteWorkspace(EakteSessions sessions, ServerAccounts accounts) {
        super(new BorderLayout());
        this.sessions = sessions;
        setBackground(Comic.SURFACE);
        form = new EakteConnectForm(accounts, sessions::open);
        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung", "Mit der E-Akte verbinden");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> connect());
        DeckCredentials.menu(newPill, this::editCredentials);
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel formHolder = new JPanel(new GridBagLayout());
        formHolder.setOpaque(false);
        formHolder.add(form);
        center.setOpaque(false);
        center.add(formHolder, FORM);
        footTools.setOpaque(false);
        JPanel none = new JPanel();
        none.setOpaque(false);
        footTools.add(none, FORM);
        add(DeckBars.head(pills, DeckCredentials.button(this::editCredentials)), BorderLayout.NORTH);
        add(center, BorderLayout.CENTER);
        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        sessions.all().forEach(this::added);
        select(null);
        addHierarchyListener(event -> {
            if ((event.getChangeFlags() & java.awt.event.HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && !autoTried) {
                autoTried = true;
                if (pillOf.isEmpty()) {
                    connect();
                }
            }
        });
    }

    /** Ob die E-Akte den Proxy der App nimmt (Erweitert im Formular; Vorgabe ja). */
    public static boolean proxyWanted() {
        return EakteConnectForm.proxyWanted();
    }

    /** Die Karte dieses Bereichs in der Statuszeile der App. */
    public JComponent statusInfo() {
        return statusInfo;
    }

    @Override
    public JComponent footTools() {
        return footTools;
    }

    /** Wie viele Verbindungen offen sind (Kachel auf der Startseite). */
    public long connectedCount() {
        return sessions.all().stream().filter(s -> s.state() == EakteState.CONNECTED).count();
    }

    /**
     * „Zugangsdaten ändern“ ({@link DeckCredentials}): das Formular ohne automatische Anmeldung, die Einträge neu aus
     * KeePass, eine Sperre nach einem Fehlschlag als Grund.
     */
    void editCredentials() {
        form.loadEntries();
        select(null);
        DeckCredentials.showLock(EakteConnectForm.savedEntry(), form::notice);
    }

    /**
     * „Neue Verbindung“ und das erste Zeigen: mit gemerktem Eintrag sofort verbinden, sonst das Formular. Ist der
     * Eintrag nach einer Ablehnung gesperrt (egal in welchem Bereich), kein Versuch, sondern das Formular mit dem
     * Grund.
     */
    void connect() {
        if (!EakteConnectForm.isConfigured()) {
            select(null);
            return;
        }
        String entry = EakteConnectForm.savedEntry();
        if (!DeckLogin.guard().mayTry(entry)) {
            select(null);
            form.notice(DeckLogin.notice(entry));
            return;
        }
        directPending = true;
        form.connectDirectly(reason -> {
            directPending = false;
            select(null);
        });
    }

    /**
     * Die Dokumentanalyse für die Extraktion aller vorhandenen und künftigen Browser (ADR 0035); {@code null} heißt
     * keine. Auf dem EDT.
     */
    public void analyses(com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider provider) {
        analyses = provider == null
                ? com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider.NONE : provider;
        browserOf.values().forEach(browser -> browser.extraction().provider(analyses));
    }

    /** Die E-Akte als Ablage der Dokumentanalyse: liest über die angemeldeten Sitzungen. */
    public com.aresstack.spaceharvester.application.extraction.DocumentContent content() {
        return new EakteDocumentContent(sessions);
    }

    private void added(EakteSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        if (directPending) {
            directPending = false;
            direct.add(session);
        }
        String entry = form.takeAttemptEntry();
        if (!entry.isEmpty()) {
            loggingIn.put(session, entry);
        }
        ComposerToggleButton pill = new ClosableTab(dot(session.state()), session.profile().label(),
                session.state().label(), "Trennen und schließen (auch Mittelklick auf den Reiter)",
                () -> close(session));
        pill.addActionListener(e -> select(session));
        DeckCredentials.menu(pill, this::editCredentials);
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        EakteBrowser browser = new EakteBrowser(session, previews, previewStore);
        if (analyses != com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider.NONE) {
            browser.extraction().provider(analyses);
        }
        browser.onStatus(note -> {
            if (selected == session) {
                statusInfo.show(note);
            }
        });
        browserOf.put(session, browser);
        center.add(browser, session.id());
        footTools.add(browser.footTools(), session.id());
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        select(session);
        pills.revalidate();
        pills.repaint();
        if (session.state() != EakteState.CONNECTING) {
            changed(session);
        }
    }

    private void changed(EakteSession session) {
        ComposerToggleButton pill = pillOf.get(session);
        if (pill == null) {
            return;
        }
        pill.setIcon(dot(session.state()));
        pill.setToolTipText(session.failure().map(reason -> session.state().label() + ": " + reason)
                .orElse(session.state().label()));
        String entry = loggingIn.get(session);
        EakteBrowser browser = browserOf.get(session);
        if (session.state() == EakteState.CONNECTED) {
            direct.remove(session);
            if (loggingIn.remove(session) != null) {
                DeckLogin.guard().succeeded(entry);
            }
            browser.connected();
            return;
        }
        if (session.state() == EakteState.FAILED) {
            String reason = session.failure().orElse("Anmeldung gescheitert");
            boolean refused = loggingIn.remove(session) != null && reason.startsWith("Anmeldung abgelehnt");
            if (refused) {
                // Der Anmeldedienst hat abgelehnt: Eintrag sperren, überall, bis eine Anmeldung klappt.
                DeckLogin.guard().failed(entry, reason);
            }
            // Direkte Anmeldung gescheitert oder Zugang abgelehnt: sofort das Formular mit dem Grund, damit die
            // Zugangsdaten geändert werden können (Angelo, 24.11.2026), keine tote Sitzung.
            if (direct.remove(session) | refused) {
                close(session);
                select(null);
                form.failed(reason);
                return;
            }
            if (selected == session) {
                statusInfo.show(new StatusNote(reason, StatusNote.Tone.ERROR, null, null));
            }
        }
    }

    /** Zeigt die Sitzung oder mit {@code null} das Formular. */
    void select(EakteSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        cards.show(center, session == null ? FORM : session.id());
        toolCards.show(footTools, session == null ? FORM : session.id());
        EakteBrowser browser = session == null ? null : browserOf.get(session);
        statusInfo.show(browser == null ? StatusNote.plain(" ") : session.failure()
                .map(reason -> new StatusNote(reason, StatusNote.Tone.ERROR, null, null))
                .orElse(browser.status()));
        if (session == null) {
            SwingUtilities.invokeLater(form::focusFirst);
        }
    }

    /** Das Kreuz am Reiter: trennen, schließen und aus der Liste nehmen. */
    void close(EakteSession session) {
        sessions.close(session);
        ComposerToggleButton pill = pillOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        EakteBrowser browser = browserOf.remove(session);
        if (browser != null) {
            browser.dispose();
            center.remove(browser);
            footTools.remove(browser.footTools());
        }
        loggingIn.remove(session);
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    /** Alle trennen (Programmende). */
    public void shutdown() {
        browserOf.values().forEach(EakteBrowser::dispose);
        sessions.closeAll();
    }

    /** Maustaste Zurück im Browser der gewählten Sitzung. */
    void navigateBack() {
        EakteBrowser browser = selected == null ? null : browserOf.get(selected);
        if (browser != null) {
            browser.navigateBack();
        }
    }

    /** Maustaste Vor. */
    void navigateForward() {
        EakteBrowser browser = selected == null ? null : browserOf.get(selected);
        if (browser != null) {
            browser.navigateForward();
        }
    }

    EakteSession selected() {
        return selected;
    }

    /** Das Dokument, das die gewählte Sitzung gerade zeigt; {@code null} ohne. */
    public com.aresstack.spaceharvester.application.extraction.DocumentRef shownDocument() {
        EakteBrowser browser = selected == null ? null : browserOf.get(selected);
        return browser == null ? null : browser.extraction().shown();
    }

    /** Die angeschlossene Dokumentanalyse (Fristen); {@code DocumentAnalysisProvider.NONE} ohne. */
    public com.aresstack.spaceharvester.application.extraction.DocumentAnalysisProvider analyses() {
        return analyses;
    }

    EakteBrowser browser(EakteSession session) {
        return browserOf.get(session);
    }

    EakteConnectForm form() {
        return form;
    }

    int sessionCount() {
        return pillOf.size();
    }

    /** Zustandspunkt wie in den anderen Bereichen. */
    static javax.swing.Icon dot(EakteState state) {
        Color color = switch (state) {
            case CONNECTED -> Comic.GREEN;
            case CONNECTING -> Comic.BLUE;
            case FAILED -> Comic.RED;
            case DISCONNECTED -> Comic.MUTED;
        };
        return FilesIcons.dot(color);
    }
}
