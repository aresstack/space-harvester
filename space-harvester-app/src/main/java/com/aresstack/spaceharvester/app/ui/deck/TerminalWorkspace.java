package com.aresstack.spaceharvester.app.ui.deck;

import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.ui.Comic;
import com.aresstack.spaceharvester.app.ui.deck.sky.CosmicClockPanel;
import com.aresstack.spaceharvester.app.ui.controls.FunctionKeyBar;
import com.aresstack.spaceharvester.deck.terminal.AidKey;
import com.aresstack.spaceharvester.deck.terminal.FunctionKeyLegend;
import com.aresstack.spaceharvester.deck.terminal.MouseFkeyBinding;
import com.aresstack.spaceharvester.deck.terminal.SessionState;
import com.aresstack.spaceharvester.deck.terminal.TerminalKeyboard;
import com.aresstack.spaceharvester.deck.terminal.TerminalProfile;
import com.aresstack.spaceharvester.deck.terminal.TerminalSession;
import com.aresstack.spaceharvester.deck.terminal.TerminalSessions;
import com.aresstack.spaceharvester.deck.terminal.TerminalView;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagLayout;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.HierarchyEvent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/**
 * Arbeitsbereich „Terminal“ des Command Deck: oben die Sitzungen als Pillen mit Zustandspunkt und rechts „Neue
 * Verbindung“, in der Mitte das 3270-Bild vor dem Sternenhimmel aus MainframeMate (kosmische Uhr), unten die Aktionen
 * der gewählten Sitzung über dem gemeinsamen Statusband. Die Sitzungen gehören dem Anwendungsfall
 * {@link TerminalSessions}; Wechsel des Bereichs oder der Perspektive trennen nichts.
 */
public final class TerminalWorkspace extends JPanel implements DeckBars.FootTools {

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

    private final TerminalSessions sessions;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<TerminalSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    /**
     * Sitzungen aus dem Formular oder einem Lesezeichen, die noch nie verbunden waren. Scheitern sie, bleibt kein toter
     * Reiter: Das Formular kommt mit dem Grund wieder. Der Wert sagt, ob ein Erfolg die gemerkte Verbindung einrichtet.
     */
    private final Map<TerminalSession, Boolean> firstConnect = new java.util.concurrent.ConcurrentHashMap<>();
    private final ComposerToggleButton newPill;
    private final CosmicClockPanel sky = new CosmicClockPanel(120);
    private final ConnectForm form;
    /**
     * Ruhebild, wenn die Verbindung eingerichtet ist und keine Sitzung offen: nur ein Knopf „Verbinden“, nicht jedes
     * Mal das Formular (Angelo, 23.11.2026). Das Formular kommt beim ersten Mal, wenn es scheitert oder auf Wunsch.
     */
    private final JPanel idle = new JPanel(new GridBagLayout());
    /** Das Formular statt des Ruhebilds zeigen (gewünscht oder nach einem Fehler). */
    private boolean formWanted;
    /** Kopieren und Einfügen als Symbolknöpfe rechts in der Kopfzeile (Angelo, 23.11.2026). */
    private final com.aresstack.spaceharvester.app.ui.IconButton copy;
    private final com.aresstack.spaceharvester.app.ui.IconButton paste;
    /**
     * Das ▾-Menü ganz rechts: Eingabe-Verlauf (📜 in MainframeMate), Neu verbinden, Trennen, Schließen. Trennen und
     * Schließen stehen nur hier bzw. als ✕ am Reiter (Angelo, 23.11.2026).
     */
    private final com.aresstack.spaceharvester.app.ui.IconButton more;
    /** Höhe der Kopfzeile samt Linie: wie alle Bereiche des Command Deck ({@link DeckBars}). */
    static final int HEAD_HEIGHT = DeckBars.HEAD_HEIGHT;
    /** Höhe der F-Tasten-Leiste unten: die Fußleiste des Command Deck mit der Lupe ({@link DeckBars}). */
    static final int FOOT_HEIGHT = DeckBars.FOOT_HEIGHT;
    /** Die F-Tasten für die Fußleiste des Command Deck, mittig neben der Lupe. */
    private final JPanel footKeys = new JPanel(new UnderTheScreen());
    /** Hält die F-Tasten unter dem Terminalbild, wenn es sich verschiebt oder die Größe ändert. */
    private final ComponentAdapter screenMoved = new ComponentAdapter() {
        @Override
        public void componentResized(ComponentEvent event) {
            footKeys.revalidate();
        }

        @Override
        public void componentMoved(ComponentEvent event) {
            footKeys.revalidate();
        }
    };
    /** Als Lesezeichen merken: Ziel, Anmeldung und Makro (Stern in MainframeMate). */
    private final com.aresstack.spaceharvester.app.ui.IconButton star;
    private final TerminalSettings settings;
    private final TerminalBookmarks bookmarks;
    /** Woher jede Sitzung kam (KeePass-Eintrag, Startkommando) – für Lesezeichen. */
    private final Map<TerminalSession, ConnectForm.Request> origins = new java.util.concurrent.ConcurrentHashMap<>();
    /** Wann die Maus zuletzt eine F-Taste schickte (gegen Fluten beim Scrollen, 150 ms wie in MainframeMate). */
    private long lastMouseKey;
    static final int MOUSE_DEBOUNCE_MS = 150;
    private final FunctionKeyBar functionKeys = new FunctionKeyBar(this::pressFunctionKey);
    private final Map<AidKey, com.aresstack.enterpriseai.ui.comic.control.ComposerButton> specialKeys =
            new java.util.EnumMap<>(AidKey.class);
    private final java.util.Set<Component> keyWatched = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private final Runnable screenChanged = () -> SwingUtilities.invokeLater(() -> {
        readLegend();
        forgetStaleFinds();
        showAssist();
    });
    /** KI-Maskenassistent (#1151): Hinweis und Zurücknehmen links neben den Aktionen. */
    /** Der Hinweis der KI-Vorbereitung; er steht in der Statuszeile und am Zurücknehmen-Knopf, nie als Text in der Leiste. */
    private String assistHint = "";
    private final com.aresstack.spaceharvester.app.ui.IconButton undoAssist;
    private final Runnable assistChanged = () -> SwingUtilities.invokeLater(this::showAssist);
    private TerminalSession watchedSession;
    private TerminalView watchedView;
    /** Auch vom Chat-Faden gelesen (Prüfung „noch dieselbe Sitzung?“ der Chat-Werkzeuge). */
    private volatile TerminalSession selected;

    public TerminalWorkspace(TerminalSessions sessions, com.aresstack.spaceharvester.deck.access.ServerAccounts accounts) {
        this(sessions, accounts, TerminalSettings.shared(),
                new TerminalBookmarks(com.aresstack.spaceharvester.app.ui.AppState.shared()));
    }

    TerminalWorkspace(TerminalSessions sessions, com.aresstack.spaceharvester.deck.access.ServerAccounts accounts,
                      TerminalSettings settings, TerminalBookmarks bookmarks) {
        super(new BorderLayout());
        this.sessions = sessions;
        this.settings = settings;
        this.bookmarks = bookmarks;
        setBackground(Comic.SURFACE);
        form = new ConnectForm(accounts, bookmarks, this::open);
        idle.setOpaque(false);
        JPanel idleRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0));
        idleRow.setOpaque(false);
        var connectNow = Comic.button("Verbinden", "Mit der gemerkten Verbindung verbinden (wie „Neue Verbindung“)");
        connectNow.addActionListener(e -> newConnection());
        var other = Comic.button("Andere Verbindung …", "Formular: anderer Server oder ein Lesezeichen");
        other.addActionListener(e -> showForm());
        idleRow.add(connectNow);
        idleRow.add(other);
        idle.add(idleRow);

        // Kopfzeile: Sitzungen links, „Neue Verbindung“ als letzte Pille.
        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung",
                "Neue TN3270-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> newConnection());
        // Rechtsklick: Zugangsdaten ändern (Formular trotz eingerichteter Verbindung, Lesezeichen, anderer Server).
        DeckCredentials.menu(newPill, this::editCredentials);
        pillGroup.add(newPill);
        pills.add(newPill);
        // Kopfzeile wie die der übrigen Perspektiven: Reiter links, Tasten mittig, Aktionen rechts (Angelo, 23.11.).
        // Reiter und Tasten teilen sich die linke Seite der Kopfleiste; die Tasten stehen dort mittig.
        JPanel head = new JPanel(new HeadLayout());
        head.setOpaque(false);
        head.add(pills, HeadLayout.LEFT);
        // Sondertasten wie in der Werkzeugleiste von MainframeMate: ENT, CLR, PA1–PA3, SYS.
        JPanel special = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0));
        special.setOpaque(false);
        for (AidKey key : AidKey.SPECIAL) {
            var button = Comic.button(key.label(), key.hint());
            button.addActionListener(e -> press(key));
            specialKeys.put(key, button);
            special.add(button);
        }
        head.add(special, HeadLayout.CENTER);
        // Rechts: KI-Hinweis, Kopieren, Einfügen, Lesezeichen (Stern wie in MainframeMate) und das ▾-Menü.
        copy = new com.aresstack.spaceharvester.app.ui.IconButton(
                com.aresstack.spaceharvester.app.ui.controls.CopyButton.glyph(),
                "Kopieren: Auswahl oder ganzes Bild (Strg+C)").compact();
        copy.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::copy));
        paste = new com.aresstack.spaceharvester.app.ui.IconButton(
                new com.aresstack.spaceharvester.app.ui.controls.PasteGlyph(),
                "Einfügen: Text der Zwischenablage an der Schreibmarke eintippen (Strg+V)").compact();
        paste.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::paste));
        star = new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.STAR,
                "Als Lesezeichen merken: Server, Anmeldung und die aufgezeichneten Eingaben").compact();
        star.addActionListener(e -> bookmark());
        more = new com.aresstack.spaceharvester.app.ui.IconButton(
                new com.aresstack.spaceharvester.app.ui.controls.DownTriangle(),
                "Weitere Aktionen: Verlauf, Neu verbinden, Trennen, Schließen").compact();
        more.addActionListener(e -> {
            javax.swing.JPopupMenu menu = com.aresstack.spaceharvester.app.ui.controls.PopupHover.clearOnClose(
                    moreMenu(), more);
            menu.show(more, more.getWidth() - menu.getPreferredSize().width, more.getHeight());
        });

        // Mitte: Sternenhimmel, darin zentriert das Terminal bzw. das Formular. Er läuft nur, solange er sichtbar ist.
        sky.setLayout(new GridBagLayout());
        sky.setUseGermanNames(true);
        sky.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (sky.isShowing()) {
                    sky.start();
                    connectOnArrival();
                } else {
                    sky.stop();
                }
            }
        });
        sky.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent event) {
                fitScreen();
            }
        });
        sky.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent event) {
                view(TerminalView.class).ifPresent(TerminalView::focus);
            }
        });

        undoAssist = new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.UNDO,
                UNDO_TIP).compact();
        undoAssist.addActionListener(e -> undoAssist());
        undoAssist.setVisible(false);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        actions.setOpaque(false);
        // Der Schlüssel: Zugangsdaten ändern, ohne dass vorher automatisch angemeldet wird (Angelo, 24.11.2026).
        JComponent key = DeckCredentials.button(this::editCredentials);
        for (JComponent part : new JComponent[] {undoAssist, copy, paste, star, key, more}) {
            actions.add(part);
        }
        JPanel headBar = DeckBars.head(head, actions);

        // Unten nur die F-Tasten, mittig neben der Lupe in der Fußleiste des Command Deck ({@link #footTools()}); der
        // Stand steht in der Statuszeile der App, nie hier.
        footKeys.setOpaque(false);
        // Die ganze Zeile rechts der Lupe, damit die Blöcke bis an die Ränder des Terminalbilds reichen.
        footKeys.putClientProperty(com.aresstack.spaceharvester.app.ui.View.TOOLS_FILL, Boolean.TRUE);
        footKeys.add(functionKeys);

        // Mitte: nur der Himmel mit dem Terminal bzw. dem Formular.
        JPanel stage = new JPanel(new BorderLayout());
        stage.setOpaque(false);
        stage.add(sky, BorderLayout.CENTER);

        add(headBar, BorderLayout.NORTH);
        add(stage, BorderLayout.CENTER);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
        // Lehnt der Host die Anmeldung ab (oder bleibt es unklar): Formular mit dem, was der Host sagte; ab jetzt
        // versucht niemand mehr automatisch mit diesem Eintrag (Angelo, 23.11.2026: sonst ist das Konto gesperrt).
        sessions.onLogin((session, result) -> {
            if (result.sent() && !result.accepted()) {
                String entry = session.loginSource() == null ? null : session.loginSource().entry();
                SwingUtilities.invokeLater(() -> {
                    showForm();
                    form.problem(entry == null ? "Anmeldung fehlgeschlagen: " + result.problem().orElse("")
                            : DeckLogin.notice(entry));
                });
            }
        });
        sessions.onReopened((lost, fresh) -> SwingUtilities.invokeLater(() -> reopened(lost, fresh)));
        sessions.all().forEach(this::added);
        settings.observe(() -> SwingUtilities.invokeLater(this::applySettings));
        applySettings();
        select(null);
    }

    /** Übernimmt die Einstellungen sofort (onSettingsChanged in MainframeMate). */
    private void applySettings() {
        functionKeys.setOpacity(settings.functionKeyOpacity());
        sky.getTimeModel().setTimeFactor(settings.skyFactor());
        sky.setUseGermanNames(settings.skyGerman());
        sky.setSkyEnabled(settings.sky());
    }

    /** Öffnet, was das Formular oder ein Lesezeichen verlangt, und merkt sich die Herkunft für Lesezeichen. */
    private void open(ConnectForm.Request request) {
        TerminalSession session = sessions.open(request.profile(), request.login(), request.steps());
        origins.put(session, request);
        if (session.state() == SessionState.CONNECTED) {
            if (request.fromForm()) {
                ConnectForm.configured();
            }
        } else if (session.state().ended()) {
            SwingUtilities.invokeLater(() -> failedFirst(session));
        } else {
            firstConnect.put(session, request.fromForm());
        }
    }

    /**
     * „Neue Verbindung“: Ist die Verbindung eingerichtet, verbindet sie direkt; das Formular kommt nur beim ersten Mal
     * und wenn es scheitert (Angelo, 23.11.2026).
     */
    /** Wann zuletzt beim Einblenden verbunden wurde; schnelles Hin- und Herschalten verbindet nicht doppelt. */
    private long arrivedAt = Long.MIN_VALUE;

    /**
     * Beim Öffnen des Bereichs Terminal gleich verbinden (Angelo, 23.11.2026). Das Deck selbst startet auf Home
     * ({@link DeckHome}), damit der Wechsel in die Perspektive noch nichts aufbaut; erst der Klick auf Terminal zeigt
     * diesen Bereich und verbindet. Nur wenn die Verbindung eingerichtet ist und noch kein Reiter steht: Ein beendeter Reiter wartet womöglich auf
     * seinen automatischen Neuaufbau, und den verbindet man über ihn neu, nicht daneben.
     */
    void connectOnArrival() {
        if (!ConnectForm.isConfigured() || !sessions.all().isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        if (arrivedAt != Long.MIN_VALUE && now - arrivedAt < java.util.concurrent.TimeUnit.SECONDS.toNanos(10)) {
            return;
        }
        arrivedAt = now;
        newConnection();
    }

    void newConnection() {
        if (!ConnectForm.isConfigured()) {
            select(null);
            return;
        }
        note("Verbinde mit der gemerkten Verbindung …", false);
        TerminalSession before = selected;
        if (before != null) {
            pillOf.get(before).setSelected(true);
        }
        form.connectDirectly(reason -> showForm());
    }

    /**
     * „Zugangsdaten ändern“ ({@link DeckCredentials}): das Formular ohne automatische Anmeldung, die Einträge neu aus
     * KeePass, eine Sperre nach einem Fehlschlag als Grund.
     */
    void editCredentials() {
        form.load();
        showForm();
        DeckCredentials.showLock(com.aresstack.spaceharvester.app.ui.AppState.shared().get(ConnectForm.ENTRY_KEY),
                form::problem);
    }

    /** Das Formular „Neue Verbindung“ auch, wenn die Verbindung eingerichtet ist (Lesezeichen, anderer Server). */
    void showForm() {
        formWanted = true;
        select(null);
    }

    /** Eine nie verbundene Sitzung ist gescheitert: Reiter weg, Formular mit dem Grund. */
    private void failedFirst(TerminalSession session) {
        firstConnect.remove(session);
        String reason = session.failure().map(text -> text + hint(session, text))
                .orElse("Der Host hat die Verbindung sofort getrennt");
        if (pillOf.containsKey(session)) {
            closeSession(session);
        } else {
            sessions.close(session);
            origins.remove(session);
        }
        form.problem(session.profile().label() + ": " + reason);
        formWanted = true;
        select(null);
    }

    /** „Verbinden“: dieselbe Sitzung neu, die alte verschwindet aus der Liste. */
    void reconnect(TerminalSession ended) {
        if (!ended.state().ended()) {
            return;
        }
        if (!sessions.mayTry(ended.loginSource())) {
            // Sperrschutz: Mit diesem Eintrag schlug die letzte Anmeldung fehl; erst prüfen, dann im Formular verbinden.
            showForm();
            form.problem(DeckLogin.notice(ended.loginSource().entry()));
            return;
        }
        ConnectForm.Request origin = origins.get(ended);
        closeSession(ended);
        TerminalSession fresh = sessions.reopen(ended);
        if (origin != null) {
            origins.put(fresh, origin);
        }
    }

    /** Automatischer Neuaufbau: Die Nachfolgerin übernimmt Herkunft und Auswahl, der alte Reiter verschwindet. */
    private void reopened(TerminalSession lost, TerminalSession fresh) {
        // Nur wer die verlorene Sitzung vor sich hatte, bekommt die Nachfolgerin; sonst bleibt die Eingabe, wo sie ist.
        TerminalSession target = selected == lost || selected == null ? fresh : selected;
        ConnectForm.Request origin = origins.get(lost);
        if (origin != null) {
            origins.put(fresh, origin);
        }
        if (pillOf.containsKey(lost)) {
            closeSession(lost);
        }
        added(fresh);
        select(target);
    }

    private void added(TerminalSession session) {
        if (pillOf.containsKey(session)) {
            return;
        }
        ComposerToggleButton pill = new com.aresstack.spaceharvester.app.ui.controls.ClosableTab(dot(session.state()), session.profile().label(),
                session.state().label(), "Trennen und schließen (auch Mittelklick auf den Reiter)",
                () -> askClose(session));
        pill.addActionListener(e -> select(session));
        DeckCredentials.menu(pill, this::editCredentials);
        pillGroup.add(pill);
        pills.add(pill, pills.getComponentCount() - 1);
        pillOf.put(session, pill);
        session.observe(changed -> SwingUtilities.invokeLater(() -> changed(changed)));
        // Makro-Aufzeichnung: was der Mensch an der Anzeige eingibt (verdeckte Felder meldet sie nie).
        session.view(TerminalView.class).ifPresent(view -> view.addInputListener(new TerminalView.InputListener() {
            @Override
            public void typed(String text) {
                session.macro().typed(text);
            }

            @Override
            public void aid(AidKey key) {
                session.macro().aid(key);
            }

            @Override
            public void tab() {
                session.macro().tab();
            }
        }));
        if (session.attempt() == 0) {
            // Ein automatischer Neuaufbau holt die Eingabe nicht von einer anderen Sitzung weg (reopened entscheidet).
            select(session);
        }
        pills.revalidate();
        pills.repaint();
        // Was vor dem Beobachter geschah (etwa eine sehr schnelle Abweisung), holt changed jetzt nach.
        if (session.state() != SessionState.CONNECTING) {
            changed(session);
        }
    }

    private void changed(TerminalSession session) {
        Boolean configures = firstConnect.get(session);
        if (configures != null) {
            if (session.state() == SessionState.CONNECTED) {
                firstConnect.remove(session);
                if (configures) {
                    ConnectForm.configured();
                }
            } else if (session.state().ended()) {
                failedFirst(session);
                return;
            }
        }
        ComposerToggleButton pill = pillOf.get(session);
        if (pill != null) {
            pill.setIcon(dot(session.state()));
            pill.setToolTipText(session.failure().map(reason -> session.state().label() + ": " + reason)
                    .orElse(session.state().label()));
        }
        if (session == selected) {
            showState();
            showAssist();
            if (session.state() == SessionState.CONNECTED) {
                view(TerminalView.class).ifPresent(TerminalView::focus);
            }
        }
    }

    /** Zeigt die Sitzung (oder mit {@code null} das Formular „Neue Verbindung“). */
    void select(TerminalSession session) {
        selected = session;
        ComposerToggleButton pill = session == null ? newPill : pillOf.get(session);
        if (pill != null) {
            pill.setSelected(true);
        }
        sky.removeAll();
        boolean quiet = session == null && !formWanted && ConnectForm.isConfigured();
        if (session != null) {
            formWanted = false;
        }
        Component center = session == null ? (quiet ? idle : form) : session.view(JComponent.class).orElse(null);
        if (center != null) {
            sky.add(center);
        }
        watch(session);
        sky.revalidate();
        sky.repaint();
        fitScreen();
        showState();
        if (session == null && !quiet) {
            SwingUtilities.invokeLater(form::focusServer);
        } else {
            SwingUtilities.invokeLater(() -> view(TerminalView.class).ifPresent(TerminalView::focus));
        }
    }

    /** Hört auf neue Bilder der gewählten Sitzung, um die PF-Leiste zu beschriften; die vorige wird abgemeldet. */
    private void watch(TerminalSession session) {
        if (watchedView != null) {
            watchedView.removeScreenListener(screenChanged);
            watchedView = null;
        }
        if (watchedSession != null) {
            watchedSession.assistant().unobserve(assistChanged);
            watchedSession = null;
        }
        if (session != null) {
            watchedSession = session;
            session.assistant().observe(assistChanged);
        }
        showAssist();
        functionKeys.setVisible(session != null);
        if (session == null) {
            return;
        }
        session.view(TerminalView.class).ifPresent(view -> {
            watchedView = view;
            view.addScreenListener(screenChanged);
        });
        session.view(JComponent.class).filter(keyWatched::add).ifPresent(screen -> {
            screen.addComponentListener(screenMoved);
            // Die Lupe der Fußleiste sucht auch im selbst gezeichneten Bild.
            session.view(TerminalView.class).ifPresent(view -> screen.putClientProperty(
                    com.aresstack.spaceharvester.app.ui.FindableSurface.KEY, new ScreenFind(view)));
            screen.addKeyListener(new java.awt.event.KeyAdapter() {
                @Override
                public void keyPressed(java.awt.event.KeyEvent event) {
                    functionKeys.showPressed(functionKeyNumber(event), true);
                }

                @Override
                public void keyReleased(java.awt.event.KeyEvent event) {
                    functionKeys.showPressed(functionKeyNumber(event), false);
                }
            });
            // Maus → F-Tasten wie in MainframeMate: Seitentasten (4, 5) und Scrollrad, mit Zusatztasten.
            screen.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent event) {
                    MouseFkeyBinding.MouseAction action = sideButton(event.getButton());
                    if (action != null && mouseKey(action, event)) {
                        event.consume();
                    }
                }
            });
            screen.addMouseWheelListener(event -> {
                if (event.getWheelRotation() == 0) {
                    return;
                }
                long now = System.currentTimeMillis();
                if (now - lastMouseKey < MOUSE_DEBOUNCE_MS) {
                    event.consume();
                    return;
                }
                if (mouseKey(event.getWheelRotation() > 0 ? MouseFkeyBinding.MouseAction.SCROLL_DOWN
                        : MouseFkeyBinding.MouseAction.SCROLL_UP, event)) {
                    lastMouseKey = now;
                    event.consume();
                }
            });
        });
        readLegend();
    }

    /**
     * Zeigt, was die KI vorbereitet hat (#1151): die Felder gelb umrandet im Terminal, daneben der Hinweis und
     * „KI-Eingaben zurücknehmen“. Nach einem neuen Bild vom Host (der Mensch hat abgeschickt) ist alles weg.
     */
    void showAssist() {
        TerminalSession session = selected;
        java.util.List<com.aresstack.spaceharvester.deck.terminal.MaskAssistant.Edit> edits =
                session == null || session.state() != SessionState.CONNECTED ? java.util.List.of()
                        : session.assistant().edits();
        java.util.Set<Integer> fields = new java.util.LinkedHashSet<>();
        java.util.List<TerminalView.Span> spans = new java.util.ArrayList<>();
        for (var edit : edits) {
            if (fields.add(edit.start())) {
                spans.add(new TerminalView.Span(edit.start(), edit.length()));
            }
        }
        view(TerminalView.class).ifPresent(view -> view.highlight(spans));
        boolean any = !spans.isEmpty();
        String hint = any ? assistText(spans.size()) : "";
        if (!hint.equals(assistHint)) {
            assistHint = hint;
            if (any) {
                note(hint, false);
            }
        }
        undoAssist.setToolTipText(any ? UNDO_TIP + " (" + hint + ")" : UNDO_TIP);
        undoAssist.setVisible(any);
        undoAssist.setEnabled(any);
    }

    static final String UNDO_TIP =
            "KI-Eingaben zurücknehmen: setzt die Felder zurück, die die KI ausgefüllt hat; was du selbst geändert hast, bleibt";

    /**
     * Legt die F-Tasten genau unter das Terminalbild (Angelo, 23.11.2026): linker Block bündig mit dessen linkem Rand,
     * rechter Block mit dem rechten, der mittlere mittig. Ohne Bild oder wenn es schmaler ist als die Tasten: mittig in
     * ihrer Wunschbreite.
     */
    private final class UnderTheScreen implements java.awt.LayoutManager {

        @Override
        public void addLayoutComponent(String name, java.awt.Component comp) {
        }

        @Override
        public void removeLayoutComponent(java.awt.Component comp) {
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            return functionKeys.getPreferredSize();
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            return new java.awt.Dimension(0, functionKeys.getPreferredSize().height);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Dimension wanted = functionKeys.getPreferredSize();
            int height = Math.min(parent.getHeight(), wanted.height);
            int y = (parent.getHeight() - height) / 2;
            java.awt.Rectangle under = screenSpan(parent);
            if (under != null && under.width >= wanted.width) {
                functionKeys.setBounds(under.x, y, under.width, height);
            } else {
                int width = Math.min(parent.getWidth(), wanted.width);
                functionKeys.setBounds((parent.getWidth() - width) / 2, y, width, height);
            }
        }

        /** Waagerechte Lage des Terminalbilds in {@code parent}, auf dessen Breite beschnitten; {@code null} ohne. */
        private java.awt.Rectangle screenSpan(java.awt.Container parent) {
            JComponent screen = view(JComponent.class).orElse(null);
            if (screen == null || !screen.isVisible() || screen.getWidth() <= 0 || screen.getParent() == null
                    || SwingUtilities.getRoot(screen) != SwingUtilities.getRoot(parent)) {
                return null;
            }
            java.awt.Rectangle at = SwingUtilities.convertRectangle(screen.getParent(), screen.getBounds(), parent);
            int left = Math.max(0, at.x);
            int right = Math.min(parent.getWidth(), at.x + at.width);
            return right <= left ? null : new java.awt.Rectangle(left, 0, right - left, parent.getHeight());
        }
    }

    /** F-Tasten neben die Lupe der gemeinsamen Fußleiste (Angelo, 23.11.2026: überall dieselbe Leiste). */
    @Override
    public JComponent footTools() {
        return footKeys;
    }

    /** Ein neues Bild vom Host: Treffer der Lupe auf dem alten Bild wegnehmen, sie stünden an falscher Stelle. */
    private void forgetStaleFinds() {
        view(JComponent.class).map(screen -> screen.getClientProperty(
                com.aresstack.spaceharvester.app.ui.FindableSurface.KEY)).filter(ScreenFind.class::isInstance)
                .map(ScreenFind.class::cast).ifPresent(ScreenFind::forgetIfChanged);
    }

    /**
     * Das Terminalbild für die Lupe: Der Text ist {@link TerminalView#text()} (Zeilen mit Zeilenumbruch), die Treffer
     * malt die Anzeige als Pufferbereiche.
     */
    static final class ScreenFind implements com.aresstack.spaceharvester.app.ui.FindableSurface {

        private final TerminalView view;
        private String marked;

        ScreenFind(TerminalView view) {
            this.view = view;
        }

        @Override
        public String findText() {
            return view.text();
        }

        @Override
        public void showFound(java.util.List<int[]> found, int current) {
            String text = view.text();
            marked = found.isEmpty() ? null : text;
            view.markFound(spans(text, found), current);
        }

        void forgetIfChanged() {
            if (marked != null && !marked.equals(view.text())) {
                marked = null;
                view.markFound(java.util.List.of(), -1);
            }
        }

        /** Textstellen in Pufferbereiche: Jede Zeile ist {@code Spalten + 1} Zeichen lang (der Zeilenumbruch). */
        static java.util.List<TerminalView.Span> spans(String text, java.util.List<int[]> found) {
            int newline = text.indexOf('\n');
            int columns = newline < 0 ? text.length() : newline;
            java.util.List<TerminalView.Span> spans = new java.util.ArrayList<>();
            if (columns <= 0) {
                return spans;
            }
            for (int[] range : found) {
                int start = (range[0] / (columns + 1)) * columns + range[0] % (columns + 1);
                spans.add(new TerminalView.Span(start, range[1] - range[0]));
            }
            return spans;
        }
    }

    static String assistText(int fields) {
        return (fields == 1 ? "KI hat 1 Feld" : "KI hat " + fields + " Felder")
                + " vorbereitet – prüfen, dann selbst abschicken";
    }

    private void undoAssist() {
        TerminalSession session = selected;
        if (session == null) {
            return;
        }
        try {
            session.assistant().undo();
        } catch (com.aresstack.spaceharvester.deck.terminal.MaskRefused refused) {
            note(refused.getMessage(), true);
        }
        showAssist();
        view(TerminalView.class).ifPresent(TerminalView::focus);
    }

    private void readLegend() {
        functionKeys.show(view(TerminalView.class).map(view -> FunctionKeyLegend.ofScreen(view.text()))
                .orElse(Map.of()));
    }

    private void pressFunctionKey(int number) {
        press(AidKey.pf(number));
    }

    /** Eine Taste aus der Oberfläche (Sondertasten, PF-Leiste, Maus); sie wird aufgezeichnet wie in MainframeMate. */
    private boolean press(AidKey key) {
        TerminalSession session = selected;
        if (session == null || session.state() != SessionState.CONNECTED) {
            return false;
        }
        TerminalKeyboard keyboard = view(TerminalKeyboard.class).orElse(null);
        if (keyboard == null) {
            return false;
        }
        session.macro().aid(key);
        keyboard.press(key);
        view(TerminalView.class).ifPresent(TerminalView::focus);
        return true;
    }

    /**
     * Maus-Aktion nach der Belegung aus den Einstellungen (sendFkeyFromMouse in MainframeMate): Taste schicken und
     * kurz gedrückt zeigen.
     *
     * @return ob eine Belegung passte
     */
    boolean mouseKey(MouseFkeyBinding.MouseAction action, java.awt.event.InputEvent event) {
        int number = MouseFkeyBinding.fkeyFor(settings.mouse(), action, event.isShiftDown(), event.isControlDown(),
                event.isAltDown());
        if (number == 0 || !press(AidKey.pf(number))) {
            return false;
        }
        functionKeys.showPressed(number, true);
        javax.swing.Timer release = new javax.swing.Timer(120, e -> functionKeys.showPressed(number, false));
        release.setRepeats(false);
        release.start();
        return true;
    }

    /** Seitentasten der Maus: 4 = Zurück, 5 = Vorwärts; sonst {@code null}. */
    static MouseFkeyBinding.MouseAction sideButton(int button) {
        return button == 4 ? MouseFkeyBinding.MouseAction.BACK
                : button == 5 ? MouseFkeyBinding.MouseAction.FORWARD : null;
    }

    /** Der Eingabe-Verlauf wie im Knopf 📜 von MainframeMate: jede Zeile kopierbar, oben „Leeren“. */
    void showHistory() {
        TerminalSession session = selected;
        if (session == null) {
            return;
        }
        javax.swing.JPopupMenu popup = new javax.swing.JPopupMenu();
        popup.setLayout(new BorderLayout());
        popup.add(historyPanel(session, () -> popup.setVisible(false)), BorderLayout.CENTER);
        popup.show(more, more.getWidth() - popup.getPreferredSize().width, more.getHeight());
    }

    /** Inhalt des Verlaufs (für Tests auch ohne Popup). */
    JComponent historyPanel(TerminalSession session, Runnable closed) {
        java.util.List<com.aresstack.spaceharvester.deck.terminal.TerminalMacro.Step> steps = session.macro().steps();
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBackground(Comic.SURFACE);
        panel.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        JPanel header = new JPanel(new BorderLayout(8, 0));
        header.setOpaque(false);
        JLabel title = new JLabel("Eingabe-Verlauf (" + steps.size() + ")");
        title.setFont(Comic.semiBold(13f));
        title.setForeground(Comic.INK);
        header.add(title, BorderLayout.WEST);
        var clear = Comic.button("Leeren", "Aufzeichnung dieser Sitzung leeren; sie läuft danach weiter");
        clear.addActionListener(e -> {
            session.macro().clear();
            closed.run();
        });
        header.add(clear, BorderLayout.EAST);
        panel.add(header, BorderLayout.NORTH);
        JPanel list = new JPanel();
        list.setOpaque(false);
        list.setLayout(new javax.swing.BoxLayout(list, javax.swing.BoxLayout.Y_AXIS));
        if (steps.isEmpty()) {
            list.add(Comic.muted("keine Eingaben aufgezeichnet"));
        }
        int index = 0;
        for (var step : steps) {
            index++;
            JPanel row = new JPanel(new BorderLayout(6, 0));
            row.setOpaque(false);
            row.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                    BorderFactory.createEmptyBorder(2, 0, 2, 0)));
            JLabel number = Comic.muted(String.valueOf(index));
            number.setPreferredSize(new java.awt.Dimension(24, number.getPreferredSize().height));
            number.setHorizontalAlignment(javax.swing.SwingConstants.RIGHT);
            JLabel text = new JLabel(step.display());
            text.setFont(Comic.regular(12.5f));
            text.setForeground(step.kind() == com.aresstack.spaceharvester.deck.terminal.TerminalMacro.Step.Kind.TEXT
                    ? Comic.GREEN : Comic.BLUE);
            text.setToolTipText(step.kind() + ": " + step.value());
            String value = step.value();
            row.add(number, BorderLayout.WEST);
            row.add(text, BorderLayout.CENTER);
            row.add(new com.aresstack.spaceharvester.app.ui.controls.CopyButton(() -> value), BorderLayout.EAST);
            row.setAlignmentX(LEFT_ALIGNMENT);
            list.add(row);
        }
        javax.swing.JScrollPane scroll = Comic.scroll(list);
        scroll.setPreferredSize(new java.awt.Dimension(360, Math.min(steps.size() * 28 + 24, 400)));
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Merkt die gewählte Sitzung als Lesezeichen; der Name wird abgefragt (Vorschlag „3270 → host:port“). */
    void bookmark() {
        TerminalSession session = selected;
        if (session == null) {
            return;
        }
        javax.swing.JTextField name = Comic.field(24);
        name.setText("3270 → " + session.profile().label());
        JPanel body = new JPanel(new BorderLayout(0, 6));
        body.setOpaque(false);
        body.add(Comic.text("Name für das Terminal-Lesezeichen"), BorderLayout.NORTH);
        body.add(name, BorderLayout.CENTER);
        body.add(Comic.muted(session.replay().size() + session.macro().steps().size() + " aufgezeichnete Schritte werden nach dem Anmelden "
                + "abgespielt"), BorderLayout.SOUTH);
        if (!com.aresstack.spaceharvester.app.ui.Dialogs.formOverlay(this, "Lesezeichen merken", body, "Merken")
                || name.getText().isBlank()) {
            return;
        }
        bookmark(session, name.getText().trim());
    }

    /** Speichert das Lesezeichen (ohne Rückfrage; für Tests). */
    void bookmark(TerminalSession session, String name) {
        ConnectForm.Request origin = origins.get(session);
        // Kam die Sitzung selbst aus einem Lesezeichen, gehört dessen Makro davor: sonst fehlte der Weg zum Startbild.
        java.util.List<com.aresstack.spaceharvester.deck.terminal.TerminalMacro.Step> steps =
                new java.util.ArrayList<>(session.replay());
        steps.addAll(session.macro().steps());
        bookmarks.add(new TerminalBookmarks.Bookmark(name, session.profile(), origin == null ? "" : origin.entry(),
                origin == null ? "" : origin.command(), steps));
        note("Lesezeichen „" + name + "“ gemerkt", false);
    }

    /** F1–F24 einer echten Taste, sonst 0 (wie fkeyNumberFromEvent in MainframeMate). */
    static int functionKeyNumber(java.awt.event.KeyEvent event) {
        int code = event.getKeyCode();
        if (code >= java.awt.event.KeyEvent.VK_F1 && code <= java.awt.event.KeyEvent.VK_F12) {
            return code - java.awt.event.KeyEvent.VK_F1 + 1;
        }
        if (code >= java.awt.event.KeyEvent.VK_F13 && code <= java.awt.event.KeyEvent.VK_F24) {
            return code - java.awt.event.KeyEvent.VK_F13 + 13;
        }
        return 0;
    }

    /** Schließt eine Sitzung wie der Knopf „Schließen“ (für Tests). */
    void closeSessionForTest(TerminalSession session) {
        closeSession(session);
    }

    /** ✕ oder Mittelklick: Eine laufende Sitzung wird erst nach „wirklich“ getrennt, eine beendete sofort entfernt. */
    /** ✕ am Reiter, Mittelklick, „Schließen“: trennt und schließt in einem Schritt (Angelo, 23.11.2026). */
    void askClose(TerminalSession session) {
        closeSession(session);
    }

    private void closeSession(TerminalSession session) {
        sessions.close(session);
        origins.remove(session);
        firstConnect.remove(session);
        ComposerToggleButton pill = pillOf.remove(session);
        if (pill != null) {
            pillGroup.remove(pill);
            pills.remove(pill);
            pills.revalidate();
            pills.repaint();
        }
        select(pillOf.isEmpty() ? null : pillOf.keySet().iterator().next());
    }

    private void showState() {
        TerminalSession session = selected;
        boolean connected = session != null && session.state() == SessionState.CONNECTED;
        copy.setEnabled(connected);
        paste.setEnabled(connected);
        star.setEnabled(session != null);
        specialKeys.values().forEach(button -> button.setEnabled(connected));
        functionKeys.setEnabled(connected);
        if (session == null) {
            note("Keine Sitzung gewählt – Server wählen und verbinden", false);
            return;
        }
        String text = session.profile().label() + " · " + session.state().label()
                + session.failure().map(reason -> " – " + reason + hint(session, reason)).orElse("")
                + session.note().filter(note -> session.state() == SessionState.CONNECTED
                        || note.startsWith("Verbindung verloren") || note.startsWith("Kein automatischer"))
                        .map(note -> " · " + note).orElse("");
        note(text, session.state() == SessionState.FAILED);
    }

    /** Der Stand links in der Statuszeile der App; Fehler rot (Angelo, 23.11.2026: nicht in einer eigenen Leiste). */
    private void note(String text, boolean error) {
        com.aresstack.spaceharvester.app.ui.controls.StatusLabel label =
                (com.aresstack.spaceharvester.app.ui.controls.StatusLabel) statusInfo();
        label.show(new com.aresstack.spaceharvester.app.ui.controls.StatusNote(text,
                error ? com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.ERROR
                        : com.aresstack.spaceharvester.app.ui.controls.StatusNote.Tone.PLAIN, null, null));
    }

    /**
     * Das ▾-Menü: Verlauf, Neu verbinden (nur beendet), Trennen (nur laufend), Schließen. Schließen gibt es auch als ✕
     * am Reiter.
     */
    javax.swing.JPopupMenu moreMenu() {
        TerminalSession session = selected;
        javax.swing.JPopupMenu menu = new javax.swing.JPopupMenu();
        menu.add(menuItem("Eingabe-Verlauf …", session != null, this::showHistory));
        menu.addSeparator();
        menu.add(menuItem("Neu verbinden", session != null && session.state().ended(), () -> reconnect(session)));
        menu.add(menuItem("Trennen", session != null && !session.state().ended(), session == null ? () -> { }
                : session::disconnect));
        menu.add(menuItem("Schließen", session != null, () -> askClose(session)));
        return menu;
    }

    private static javax.swing.JMenuItem menuItem(String text, boolean enabled, Runnable action) {
        javax.swing.JMenuItem item = new javax.swing.JMenuItem(text);
        item.setFont(Comic.regular(13f));
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        return item;
    }

    /** Abgewiesen heißt: dort lauscht nichts. Meist falscher Port bzw. TLS (MainframeMate: 992 mit TLS). */
    static String hint(TerminalSession session, String reason) {
        if (reason == null || !reason.contains("Connection refused")) {
            return "";
        }
        return session.profile().tls() ? " · Port prüfen"
                : " · Port oder TLS prüfen (MainframeMate verbindet mit TLS auf Port 992)";
    }

    private void fitScreen() {
        // Die PF-Leiste steht unten in der eigenen Leiste: das Bild bekommt den ganzen Himmel.
        view(TerminalView.class).ifPresent(view -> view.fit(sky.getWidth() - 24, sky.getHeight() - 24));
    }

    private <T> Optional<T> view(Class<T> type) {
        return selected == null ? Optional.empty() : selected.view(type);
    }

    /** Die gewählte Sitzung; {@code null} beim Formular „Neue Verbindung“. Auf jedem Faden lesbar. */
    public TerminalSession selected() {
        return selected;
    }

    String assistHintText() {
        return assistHint;
    }

    com.aresstack.spaceharvester.app.ui.IconButton undoAssistButton() {
        return undoAssist;
    }

    FunctionKeyBar functionKeys() {
        return functionKeys;
    }

    ConnectForm form() {
        return form;
    }

    String statusText() {
        return ((javax.swing.JLabel) statusInfo()).getText();
    }

    /** Zustandspunkt: grün verbunden, blau im Aufbau, rot Fehler, grau getrennt (Ampel der App). */
    static Icon dot(SessionState state) {
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

    /**
     * Kopfzeile in drei Teilen, alle senkrecht mittig: Reiter links, Tasten in der Mitte der ganzen Breite, Aktionen
     * rechts. Wird es eng, rückt die Mitte zwischen die beiden Seiten, statt sie zu überdecken.
     */
    static final class HeadLayout implements java.awt.LayoutManager2 {

        static final String LEFT = "left";
        static final String CENTER = "center";
        static final String RIGHT = "right";
        private final Map<String, Component> parts = new java.util.HashMap<>();

        @Override
        public void addLayoutComponent(Component comp, Object constraints) {
            parts.put(String.valueOf(constraints), comp);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
            parts.put(name, comp);
        }

        @Override
        public void removeLayoutComponent(Component comp) {
            parts.values().remove(comp);
        }

        @Override
        public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            int width = 0;
            int height = 0;
            for (Component part : parts.values()) {
                java.awt.Dimension size = part.getPreferredSize();
                width += size.width;
                height = Math.max(height, size.height);
            }
            return new java.awt.Dimension(width + in.left + in.right, height + in.top + in.bottom);
        }

        @Override
        public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
            return new java.awt.Dimension(0, preferredLayoutSize(parent).height);
        }

        @Override
        public java.awt.Dimension maximumLayoutSize(java.awt.Container target) {
            return new java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE);
        }

        @Override
        public void layoutContainer(java.awt.Container parent) {
            java.awt.Insets in = parent.getInsets();
            int x0 = in.left;
            int width = Math.max(0, parent.getWidth() - in.left - in.right);
            int height = Math.max(0, parent.getHeight() - in.top - in.bottom);
            Component right = parts.get(RIGHT);
            Component left = parts.get(LEFT);
            Component center = parts.get(CENTER);
            int rightWidth = right == null ? 0 : Math.min(width, right.getPreferredSize().width);
            if (right != null) {
                place(right, x0 + width - rightWidth, rightWidth, in.top, height);
            }
            int centerWidth = center == null ? 0 : center.getPreferredSize().width;
            int leftWidth = left == null ? 0 : left.getPreferredSize().width;
            int centerX = x0 + (width - centerWidth) / 2;
            // Nicht über die Reiter und nicht unter die Aktionen.
            centerX = Math.max(centerX, x0 + Math.min(leftWidth, width - rightWidth - centerWidth));
            centerX = Math.min(centerX, x0 + width - rightWidth - centerWidth);
            centerX = Math.max(centerX, x0);
            if (center != null) {
                place(center, centerX, Math.max(0, Math.min(centerWidth, x0 + width - rightWidth - centerX)),
                        in.top, height);
            }
            if (left != null) {
                int room = Math.max(0, (center == null ? x0 + width - rightWidth : centerX) - x0);
                place(left, x0, Math.min(leftWidth, room), in.top, height);
            }
        }

        private static void place(Component part, int x, int width, int top, int height) {
            int own = Math.min(height, part.getPreferredSize().height);
            part.setBounds(x, top + (height - own) / 2, width, own);
        }

        @Override
        public float getLayoutAlignmentX(java.awt.Container target) {
            return 0.5f;
        }

        @Override
        public float getLayoutAlignmentY(java.awt.Container target) {
            return 0.5f;
        }

        @Override
        public void invalidateLayout(java.awt.Container target) {
        }
    }
}
