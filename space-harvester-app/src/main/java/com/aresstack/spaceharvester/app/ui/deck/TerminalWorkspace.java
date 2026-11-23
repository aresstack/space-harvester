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
public final class TerminalWorkspace extends JPanel {

    private static final String NEW = "new";

    private final TerminalSessions sessions;
    private final JPanel pills = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
    private final ButtonGroup pillGroup = new ButtonGroup();
    private final Map<TerminalSession, ComposerToggleButton> pillOf = new LinkedHashMap<>();
    private final ComposerToggleButton newPill;
    private final CosmicClockPanel sky = new CosmicClockPanel(120);
    private final ConnectForm form;
    private final JLabel status = Comic.muted(" ");
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton copy;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton paste;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton disconnect;
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton close;
    /** Knopf „Verbinden“ aus MainframeMate: eine beendete Sitzung mit demselben Ziel neu öffnen. */
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton reconnect;
    /** Eingabe-Verlauf (Makro-Aufzeichnung) der Sitzung, wie der Knopf 📜 in MainframeMate. */
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton history;
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
        showAssist();
    });
    /** KI-Maskenassistent (#1151): Hinweis und Zurücknehmen links neben den Aktionen. */
    private final JLabel assistHint = Comic.muted(" ");
    private final com.aresstack.enterpriseai.ui.comic.control.ComposerButton undoAssist;
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

        // Kopfzeile: Sitzungen links, „Neue Verbindung“ als letzte Pille.
        pills.setOpaque(false);
        newPill = new ComposerToggleButton(ComposerIcons.plus(12), "Neue Verbindung",
                "Neue TN3270-Verbindung öffnen");
        newPill.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        newPill.addActionListener(e -> select(null));
        pillGroup.add(newPill);
        pills.add(newPill);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 12, 8, 12)));
        head.add(pills, BorderLayout.CENTER);
        // Sondertasten wie in der Werkzeugleiste von MainframeMate: ENT, CLR, PA1–PA3, SYS.
        JPanel special = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        special.setOpaque(false);
        for (AidKey key : AidKey.SPECIAL) {
            var button = Comic.button(key.label(), key.hint());
            button.addActionListener(e -> press(key));
            specialKeys.put(key, button);
            special.add(button);
        }
        // Rechts daneben wie in MainframeMate: Eingabe-Verlauf und Lesezeichen.
        history = Comic.button("Verlauf", "Eingabe-Verlauf (Makro-Aufzeichnung) dieser Sitzung");
        history.addActionListener(e -> showHistory());
        special.add(history);
        star = new com.aresstack.spaceharvester.app.ui.IconButton(com.aresstack.spaceharvester.app.ui.Glyph.STAR,
                "Als Lesezeichen merken: Server, Anmeldung und die aufgezeichneten Eingaben");
        star.addActionListener(e -> bookmark());
        special.add(star);
        head.add(special, BorderLayout.EAST);

        // Mitte: Sternenhimmel, darin zentriert das Terminal bzw. das Formular. Er läuft nur, solange er sichtbar ist.
        sky.setLayout(new GridBagLayout());
        sky.setUseGermanNames(true);
        sky.addHierarchyListener(event -> {
            if ((event.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (sky.isShowing()) {
                    sky.start();
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

        // Unten: Aktionen der gewählten Sitzung, wie die Aktionszeile des Space Harvesters.
        copy = Comic.button("Kopieren", "Auswahl oder ganzes Bild kopieren (Strg+C)");
        copy.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::copy));
        paste = Comic.button("Einfügen", "Text der Zwischenablage an der Schreibmarke eintippen (Strg+V)");
        paste.addActionListener(e -> view(TerminalView.class).ifPresent(TerminalView::paste));
        disconnect = Comic.button("Trennen", "Verbindung dieser Sitzung trennen");
        disconnect.addActionListener(e -> {
            if (selected != null) {
                selected.disconnect();
            }
        });
        close = Comic.button("Schließen", "Getrennte Sitzung aus der Liste nehmen");
        close.addActionListener(e -> {
            if (selected != null) {
                closeSession(selected);
            }
        });
        reconnect = Comic.button("Verbinden", "Verbindung wiederherstellen: dasselbe Ziel, dieselbe Anmeldung, "
                + "dasselbe Lesezeichen-Makro");
        reconnect.addActionListener(e -> {
            if (selected != null) {
                reconnect(selected);
            }
        });
        undoAssist = Comic.button("KI-Eingaben zurücknehmen",
                "Setzt die Felder zurück, die die KI ausgefüllt hat; was du selbst geändert hast, bleibt");
        undoAssist.addActionListener(e -> undoAssist());
        undoAssist.setVisible(false);
        assistHint.setForeground(Comic.INK);
        assistHint.setVisible(false);
        JPanel actions = Comic.row(assistHint, undoAssist, copy, paste, reconnect, disconnect, close);
        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.CENTER);
        foot.add(actions, BorderLayout.EAST);

        // Der Himmel füllt die Fläche, die PF-Leiste schwebt halb durchsichtig am unteren Rand (wie in MainframeMate).
        JPanel stage = new JPanel() {
            @Override
            public boolean isOptimizedDrawingEnabled() {
                return false;
            }
        };
        stage.setLayout(new java.awt.LayoutManager() {
            @Override
            public void addLayoutComponent(String name, Component comp) {
            }

            @Override
            public void removeLayoutComponent(Component comp) {
            }

            @Override
            public java.awt.Dimension preferredLayoutSize(java.awt.Container parent) {
                return sky.getPreferredSize();
            }

            @Override
            public java.awt.Dimension minimumLayoutSize(java.awt.Container parent) {
                return new java.awt.Dimension(0, 0);
            }

            @Override
            public void layoutContainer(java.awt.Container parent) {
                int width = parent.getWidth();
                int height = parent.getHeight();
                int bar = functionKeys.isVisible() ? functionKeys.getPreferredSize().height : 0;
                // Das Terminal mittig über der Leiste, der Himmel trotzdem bis unten.
                if (!(sky.getBorder() instanceof javax.swing.border.EmptyBorder empty)
                        || empty.getBorderInsets().bottom != bar) {
                    sky.setBorder(BorderFactory.createEmptyBorder(0, 0, bar, 0));
                }
                sky.setBounds(0, 0, width, height);
                functionKeys.setBounds(0, height - bar, width, bar);
            }
        });
        stage.add(functionKeys);
        stage.add(sky);

        add(head, BorderLayout.NORTH);
        add(stage, BorderLayout.CENTER);
        add(foot, BorderLayout.SOUTH);

        sessions.onOpened(session -> SwingUtilities.invokeLater(() -> added(session)));
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
    }

    /** „Verbinden“: dieselbe Sitzung neu, die alte verschwindet aus der Liste. */
    void reconnect(TerminalSession ended) {
        if (!ended.state().ended()) {
            return;
        }
        ConnectForm.Request origin = origins.get(ended);
        closeSession(ended);
        TerminalSession fresh = sessions.reopen(ended);
        if (origin != null) {
            origins.put(fresh, origin);
        }
    }

    private void added(TerminalSession session) {
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
        select(session);
        pills.revalidate();
        pills.repaint();
    }

    private void changed(TerminalSession session) {
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
        Component center = session == null ? form : session.view(JComponent.class).orElse(null);
        if (center != null) {
            sky.add(center);
        }
        watch(session);
        sky.revalidate();
        sky.repaint();
        fitScreen();
        showState();
        if (session == null) {
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
        assistHint.setText(any ? assistText(spans.size()) : " ");
        assistHint.setVisible(any);
        undoAssist.setVisible(any);
        undoAssist.setEnabled(any);
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
            status.setText(refused.getMessage());
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
        popup.show(history, 0, history.getHeight());
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
        status.setText("Lesezeichen „" + name + "“ gemerkt");
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

    private void closeSession(TerminalSession session) {
        sessions.close(session);
        origins.remove(session);
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
        boolean live = session != null && !session.state().ended();
        copy.setEnabled(session != null && session.state() == SessionState.CONNECTED);
        paste.setEnabled(session != null && session.state() == SessionState.CONNECTED);
        disconnect.setEnabled(live);
        close.setEnabled(session != null && session.state().ended());
        reconnect.setEnabled(session != null && session.state().ended());
        history.setEnabled(session != null);
        star.setEnabled(session != null);
        boolean connected = session != null && session.state() == SessionState.CONNECTED;
        specialKeys.values().forEach(button -> button.setEnabled(connected));
        functionKeys.setEnabled(connected);
        if (session == null) {
            status.setText("Keine Sitzung gewählt – Server wählen und verbinden");
            status.setForeground(Comic.MUTED);
            return;
        }
        String text = session.profile().label() + " · " + session.state().label()
                + session.failure().map(reason -> " – " + reason).orElse("")
                + (session.state() == SessionState.CONNECTED ? session.note().map(note -> " · " + note).orElse("")
                        : "");
        status.setText(text);
        status.setForeground(session.state() == SessionState.FAILED ? Comic.RED : Comic.MUTED);
    }

    private void fitScreen() {
        // Unter der PF-Leiste bleibt nichts verborgen: das Bild bekommt die Höhe ohne sie.
        int bar = functionKeys.isVisible() ? functionKeys.getPreferredSize().height : 0;
        view(TerminalView.class).ifPresent(view -> view.fit(sky.getWidth() - 24, sky.getHeight() - 24 - bar));
    }

    private <T> Optional<T> view(Class<T> type) {
        return selected == null ? Optional.empty() : selected.view(type);
    }

    /** Die gewählte Sitzung; {@code null} beim Formular „Neue Verbindung“. Auf jedem Faden lesbar. */
    public TerminalSession selected() {
        return selected;
    }

    String assistHintText() {
        return assistHint.isVisible() ? assistHint.getText() : "";
    }

    com.aresstack.enterpriseai.ui.comic.control.ComposerButton undoAssistButton() {
        return undoAssist;
    }

    FunctionKeyBar functionKeys() {
        return functionKeys;
    }

    ConnectForm form() {
        return form;
    }

    String statusText() {
        return status.getText();
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
}
