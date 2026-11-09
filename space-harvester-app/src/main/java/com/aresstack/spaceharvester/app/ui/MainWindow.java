package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowHoldCloseButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowResizer;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowShape;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
import com.aresstack.enterpriseai.ui.comic.paint.StrokeIcon;
import com.aresstack.enterpriseai.ui.comic.theme.ComicTheme;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiMetrics;
import com.aresstack.enterpriseai.ui.comic.theme.ResearchUiPalette;
import com.aresstack.spaceharvester.app.runtime.AppRuntime;
import com.aresstack.spaceharvester.app.ui.brand.ApplicationIcons;
import com.aresstack.spaceharvester.app.ui.content.ContentView;

import com.aresstack.spaceharvester.app.event.Severity;
import com.aresstack.spaceharvester.app.event.StatusMessageEvent;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.Graphics2D;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Das Hauptfenster: rahmenloses Comic-Fenster wie der Enterprise AI Client. Oben die Icon-Leiste in zwei Gruppen
 * (Tooltip mit Namen, Strg+1 … Strg+7): links die Arbeitsbereiche Suche, Inhalte, Begriffe, Aufträge und Status,
 * rechts, abgetrennt, Wissensquellen verwalten und Einstellungen, dann ✕. Links dauerhaft der {@link Explorer} über
 * alle Wissensquellen (Breite veränderbar, einklappbar), rechts die Arbeitsfläche des gewählten Bereichs; über der
 * Statusleiste die feste Aktionszeile des Bereichs ({@link View#actions()}). Der Fortschritt der Aufträge füllt den
 * Hintergrund der Statuszeile ({@link StatusLine}). Navigation und
 * Explorer sind getrennte Bausteine. {@link #content} baut den Inhalt ohne Fenster (für Bildschirmfotos und Tests).
 */
public final class MainWindow implements AppContext.Navigator {

    static final int WINDOW_PADDING = 4;
    static final int RESIZE_GRIP = 6;

    private final AppContext context;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final Map<AppContext.Section, View> views = new EnumMap<>(AppContext.Section.class);
    private final Map<AppContext.Section, ComposerToggleButton> nav = new EnumMap<>(AppContext.Section.class);
    /** Der dauerhafte Explorer links: Wissensquelle → Space → Seite → Unterseite. */
    private final Explorer explorer;
    /**
     * Linker Drawer: der Explorer, unter Wissensquellen verwalten stattdessen die Quellenkarten, unter Einstellungen die
     * Bereiche der Einstellungen untereinander.
     */
    private final CardLayout sideCards = new CardLayout();
    private final JPanel side = new JPanel(sideCards);
    private static final String EXPLORER = "explorer";
    /** Die nächste geladene Seite im Explorer zeigen (gesetzt von {@link #openPage} und {@link #openPassage}). */
    private boolean revealOpened;
    /** Aktions- und Fortschrittszeilen der Bereiche, direkt über der Statusleiste. */
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionDeck = new JPanel(actionCards);
    static final int SIDEBAR_WIDTH = 280;
    private ComicSplitPane split;
    /** Leere Karte der Aktionszeile für Bereiche ohne eigene Aktionen. */
    private static final String JOBS = "jobs";
    private JobStatusLine jobStatus;
    private final JLabel status = Comic.muted(" ");
    /** So lange bleibt eine kurze Meldung stehen. */
    private static final int NOTICE_MS = 8000;
    private final javax.swing.Timer statusClear = new javax.swing.Timer(NOTICE_MS, e -> {
        status.setText(" ");
        status.setToolTipText(null);
    });
    /** Bis die Laufzeit steht, zeigt die Statuszeile „Startet …“. */
    private boolean starting = true;
    /** Stand des sichtbaren Bereichs ({@link View#statusInfo()}) links in der Statuszeile, vor dem Stand der App. */
    private final CardLayout viewStatusCards = new CardLayout();
    private final JPanel viewStatus = new JPanel(viewStatusCards);
    /** Die Statuszeile; ihr Hintergrund ist der Fortschrittsbalken der Aufträge. */
    private StatusLine statusLine;
    /** Suche im sichtbaren Bereich, über der Statuszeile und nur unter der Arbeitsfläche. */
    private final FindBar findBar;
    private AppContext.Section current = AppContext.Section.SEARCH;
    /** Der zuletzt gezeigte Arbeitsbereich vor den Einstellungen: dorthin führt „Zurück“ im Drawer. */
    private AppContext.Section beforeSettings = AppContext.Section.SEARCH;
    private final JPanel top = new JPanel(new BorderLayout(12, 0));
    private final StatusView statusView;
    private final SourcesView sources;
    private final ContentView content;
    private final ActivitiesView activities;
    private final SearchView searchView;
    private final SettingsView settings;
    private final TermsView terms;

    public MainWindow(AppContext context) {
        this.context = context;
        context.setNavigator(this);
        statusView = new StatusView(context);
        sources = new SourcesView(context);
        content = new ContentView(context);
        activities = new ActivitiesView(context);
        searchView = new SearchView(context);
        settings = new SettingsView(context);
        terms = new TermsView(context);
        settings.onLeave(() -> show(beforeSettings));
        explorer = new Explorer(context);
        explorer.onScope(searchView::scope);
        searchView.onSubmit(() -> show(AppContext.Section.SEARCH));
        // Über den Navigator geöffnete Seiten (etwa ein Suchtreffer) zeigt der Explorer aufgeklappt und gewählt.
        content.onPosition(position -> {
            explorer.currentPage(position);
            if (position != null && revealOpened) {
                revealOpened = false;
                explorer.revealPage(position);
            }
        });
        views.put(AppContext.Section.STATUS, statusView);
        views.put(AppContext.Section.SOURCES, sources);
        views.put(AppContext.Section.CONTENT, content);
        views.put(AppContext.Section.ACTIVITIES, activities);
        views.put(AppContext.Section.SEARCH, searchView);
        views.put(AppContext.Section.TERMS, terms);
        views.put(AppContext.Section.SETTINGS, settings);
        deck.setBackground(Comic.SURFACE);
        views.forEach((section, view) -> deck.add(view.component(), section.name()));
        actionDeck.setOpaque(false);
        // Der Fortschritt der Aufträge steht global in der Statuszeile; ohne eigene Aktionszeile bleibt die Zeile leer.
        actionDeck.add(new JPanel(), JOBS);
        views.forEach((section, view) -> {
            if (view.actions() != null && view != activities) {
                actionDeck.add(view.actions(), section.name());
            }
        });
        viewStatus.setOpaque(false);
        viewStatus.add(new JPanel(), JOBS);
        views.forEach((section, view) -> {
            if (view.statusInfo() != null) {
                view.statusInfo().setOpaque(false);
                viewStatus.add(view.statusInfo(), section.name());
            }
        });
        findBar = new FindBar(() -> views.get(current).component());
        statusClear.setRepeats(false);
        context.events().subscribe(StatusMessageEvent.class, this::showStatus);
        context.onChange(ignored -> updateStatus());
        updateStatus();
    }

    /** Öffnet das rahmenlose Fenster; Schließen beendet die Laufzeit (Rückfrage bei laufenden Aufträgen). */
    public JFrame open() {
        ComicTheme.installMenuDefaults(Comic.PALETTE);
        JFrame frame = new JFrame("Space Harvester");
        frame.setUndecorated(true);
        ApplicationIcons.apply(frame);
        frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        JPanel surface = content(() -> frame.dispatchEvent(new WindowEvent(frame, WindowEvent.WINDOW_CLOSING)),
                () -> frame.setState(Frame.ICONIFIED));
        frame.setContentPane(surface);
        ComicWindowShape.install(frame, surface, Comic.PALETTE, WINDOW_PADDING, ResearchUiMetrics.RADIUS_WINDOW);
        frame.setMinimumSize(new Dimension(960, 600));
        frame.setSize(new Dimension(1280, 820));
        frame.setLocationRelativeTo(null);
        frame.addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent event) {
                if (confirmClose(frame)) {
                    frame.dispose();
                    context.shutdown();
                    System.exit(0);
                }
            }
        });
        frame.setVisible(true);
        show(AppContext.Section.SEARCH);
        return frame;
    }

    /** Öffnet beim ersten Start den Einrichtungsassistenten, solange die Datei keinen Abschluss kennt. */
    public static void offerSetup(Component parent, AppContext context) {
        if (SetupWizard.due(context)) {
            SetupWizard.open(parent, context);
        }
    }

    /**
     * Der Fensterinhalt: Tintenrand, Kopfzeile mit Titel, Icon-Leiste in zwei Gruppen und ✕, Explorer und
     * Arbeitsfläche mit Aktionszeile und Suche, Statuszeile mit Fortschritt.
     *
     * @param close    Gedrückthalten des ✕
     * @param minimize Klick auf das ✕
     */
    public JPanel content(Runnable close, Runnable minimize) {
        JPanel surface = new JPanel(new BorderLayout());
        surface.setBackground(Comic.SURFACE);
        surface.setBorder(ComicBorder.windowBorder(Comic.PALETTE, WINDOW_PADDING));

        JLabel title = new JLabel("Space Harvester");
        title.setFont(Comic.semiBold(18f));
        title.setForeground(Comic.INK);
        title.setIcon(ApplicationIcons.titleLogo());
        title.setIconTextGap(8);
        ButtonGroup group = new ButtonGroup();
        JPanel icons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 0));
        icons.setOpaque(false);
        icons.setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 0));
        JPanel administration = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0));
        administration.setOpaque(false);
        administration.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 1, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(0, 8, 0, 0)));
        int number = 1;
        for (AppContext.Section section : AppContext.Section.values()) {
            ComposerToggleButton button = navButton(section, number++);
            group.add(button);
            nav.put(section, button);
            (section.administration() ? administration : icons).add(button);
        }
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(title, BorderLayout.WEST);
        west.add(icons, BorderLayout.CENTER);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 0));
        east.setOpaque(false);
        east.add(administration);
        east.add(Dialogs.closeButton(new ComicWindowHoldCloseButton(Comic.PALETTE, minimize, close, 24)));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 16, 8, 10)));
        top.add(west, BorderLayout.WEST);
        top.add(east, BorderLayout.EAST);
        // Ein Suchfeld für das ganze Fenster, mittig zwischen den Icon-Gruppen (#515).
        top.add(SearchBarLayout.wrap(searchView.searchBar()), BorderLayout.CENTER);
        ComicWindowDragger.install(top);

        side.setBackground(Comic.SURFACE);
        side.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        side.setMinimumSize(new Dimension(0, 0));
        side.add(explorer.component(), EXPLORER);
        side.add(sources.drawer(), AppContext.Section.SOURCES.name());
        side.add(settings.drawer(), AppContext.Section.SETTINGS.name());
        deck.setMinimumSize(new Dimension(320, 0));
        // Aktionszeile und Suche gehören zur Arbeitsfläche: sie reichen nicht unter den Explorer.
        JPanel work = new JPanel(new BorderLayout());
        work.setBackground(Comic.SURFACE);
        work.setMinimumSize(new Dimension(320, 0));
        JPanel workFoot = new JPanel(new BorderLayout());
        workFoot.setOpaque(false);
        workFoot.add(actionDeck, BorderLayout.CENTER);
        workFoot.add(findBar, BorderLayout.SOUTH);
        work.add(deck, BorderLayout.CENTER);
        work.add(workFoot, BorderLayout.SOUTH);
        split = new ComicSplitPane(side, work, 200, 520, Comic.PALETTE);
        split.setBackground(Comic.SURFACE);
        split.setContinuousLayout(true);
        split.setPreferredLeftWidth(SIDEBAR_WIDTH);
        // Einklappbar: Doppelklick auf den Teiler klappt den Drawer zu bzw. wieder auf.
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent event) {
                    if (event.getClickCount() == 2) {
                        toggleDrawer();
                    }
                }
            });
        }

        actionDeck.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(6, 16, 6, 16)));

        jobStatus = new JobStatusLine(context.events(), activities::cancelShownJob);
        statusLine = new StatusLine(jobStatus.model(), viewStatus, status, jobStatus, new CopyrightLink());
        JComponent foot = statusLine;
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(foot, BorderLayout.SOUTH);

        surface.add(top, BorderLayout.NORTH);
        surface.add(split, BorderLayout.CENTER);
        surface.add(bottom, BorderLayout.SOUTH);
        ComicWindowResizer.install(surface, RESIZE_GRIP);
        installShortcuts(surface);
        return surface;
    }

    /** Nur ein Symbol; Name als Tooltip und für Bildschirmleser, Strg+Ziffer wählt den Bereich. */
    private ComposerToggleButton navButton(AppContext.Section section, int number) {
        ComposerToggleButton button = new ComposerToggleButton(icon(section), null,
                section.title() + " (Strg+" + number + ")");
        button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
        button.getAccessibleContext().setAccessibleName(section.title());
        button.addActionListener(e -> show(section));
        return button;
    }

    private void installShortcuts(JComponent surface) {
        int number = 1;
        for (AppContext.Section section : AppContext.Section.values()) {
            String key = "section-" + section.name();
            surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                    java.awt.event.KeyEvent.VK_0 + number++, java.awt.event.InputEvent.CTRL_DOWN_MASK), key);
            surface.getActionMap().put(key, new javax.swing.AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    show(section);
                }
            });
        }
        surface.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(javax.swing.KeyStroke.getKeyStroke(
                java.awt.event.KeyEvent.VK_F, java.awt.event.InputEvent.CTRL_DOWN_MASK), "find");
        surface.getActionMap().put("find", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                findBar.focus();
            }
        });
    }

    /** Einstellungen mit dem Bereich „KI-Dienst“. */
    public void showAiSettings() {
        show(AppContext.Section.SETTINGS);
        settings.showAi();
    }

    /** Klappt den linken Drawer zu oder wieder auf. */
    /** Breite des Teilers bei zugeklapptem Drawer: sichtbar und per Doppelklick erreichbar. */
    static final int COLLAPSED_DIVIDER = 6;

    void toggleDrawer() {
        if (split.isLeftCollapsed()) {
            split.openLeft();
        } else {
            split.collapseLeft();
            // collapseLeft() nimmt den Teiler ganz weg; ein schmaler bleibt, damit ein Doppelklick wieder aufklappt.
            split.setDividerSize(COLLAPSED_DIVIDER);
        }
    }

    @Override
    public void show(AppContext.Section section) {
        cards.show(deck, section.name());
        boolean changed = section != current;
        if (changed && current != AppContext.Section.SETTINGS) {
            beforeSettings = current;
        }
        current = section;
        // Keine zweite Leiste: Die Quellenkarten bzw. die Bereiche der Einstellungen ersetzen im Drawer den Explorer,
        // solange der Bereich offen ist.
        boolean ownDrawer = section == AppContext.Section.SOURCES || section == AppContext.Section.SETTINGS;
        sideCards.show(side, ownDrawer ? section.name() : EXPLORER);
        if (ownDrawer && split != null && split.isLeftCollapsed()) {
            split.openLeft();
        }
        View view = views.get(section);
        boolean ownActions = view.actions() != null && view != activities;
        actionCards.show(actionDeck, ownActions ? section.name() : JOBS);
        actionDeck.setVisible(ownActions);
        boolean ownStatus = view.statusInfo() != null;
        viewStatusCards.show(viewStatus, ownStatus ? section.name() : JOBS);
        viewStatus.setVisible(ownStatus);
        ComposerToggleButton button = nav.get(section);
        if (button != null) {
            button.setSelected(true);
        }
        view.shown();
        if (changed) {
            findBar.areaChanged();
        }
    }

    @Override
    public void openPage(String sourceId, String pageId) {
        show(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPage(sourceId, pageId);
    }

    @Override
    public void openPassage(String sourceId, String passageId) {
        show(AppContext.Section.CONTENT);
        revealOpened = true;
        content.openPassage(sourceId, passageId);
    }

    @Override
    public void revealPage(String sourceId, String pageId) {
        explorer.revealPage(sourceId, pageId);
    }

    @Override
    public void openSpace(String sourceId, String spaceKey) {
        show(AppContext.Section.CONTENT);
        content.openSpace(sourceId, spaceKey);
        explorer.reveal(sourceId, spaceKey);
    }

    @Override
    public void showJob(String jobId) {
        show(AppContext.Section.ACTIVITIES);
        activities.show(jobId);
    }

    @Override
    public void showDiagnosis(String jobId) {
        show(AppContext.Section.STATUS);
        statusView.showJob(jobId);
    }

    /** Startet die Downloads sofort und zeigt den ersten Auftrag; was nicht startet, nennt die Statusleiste. */
    @Override
    public void download(String sourceId, List<String> spaceKeys) {
        notice("Download wird gestartet …", Comic.MUTED);
        Downloads.start(context, sourceId, spaceKeys, started -> {
            if (started.failed().isEmpty()) {
                notice(started.jobs().isEmpty() ? "Kein Space zum Herunterladen"
                        : Texts.count(started.jobs().size(), "Download", "Downloads") + " gestartet", Comic.MUTED);
            } else {
                notice(Texts.count(started.failed().size(), "Download ließ", "Downloads ließen")
                        + " sich nicht starten: " + String.join("; ", started.failed()), Comic.ORANGE);
            }
            if (!started.jobs().isEmpty()) {
                showJob(started.jobs().get(0).id());
            }
        }, error -> notice("Download ließ sich nicht starten: " + Problems.describe(error).title(), Comic.RED));
    }

    @Override
    public void downloadPage(String sourceId, String spaceKey, String pageId, boolean withChildren) {
        notice("Download wird gestartet …", Comic.MUTED);
        Downloads.startPage(context, sourceId, spaceKey, pageId, withChildren, started -> {
            notice(withChildren ? "Download der Seite samt Unterseiten gestartet" : "Download der Seite gestartet",
                    Comic.MUTED);
            showJob(started.jobs().get(0).id());
        }, error -> notice("Download ließ sich nicht starten: " + Problems.describe(error).title(), Comic.RED));
    }

    /** Eine kurze Meldung in der Statusleiste, bis sich der Stand der Laufzeit ändert. */
    private void notice(String text, java.awt.Color color) {
        context.events().publish(new StatusMessageEvent(text, NOTICE_MS,
                Comic.RED.equals(color) ? Severity.ERROR : Severity.INFO));
    }

    @Override
    public void search(String text) {
        show(AppContext.Section.SEARCH);
        searchView.search(text == null ? "" : text.trim());
    }

    /** Für Tests und Bildschirmfotos: die Wissensquellen-Ansicht. */
    public SourcesView sources() {
        return sources;
    }

    /**
     * Links in der Statuszeile steht keine feste Angabe mehr (Version, Quellen und Datenordner stehen unter
     * Einstellungen), nur was auf dem Ereignisbus erscheint: beim Start „Startet …“ bzw. warum nichts startete.
     */
    private void updateStatus() {
        if (context.runtime() != null) {
            if (starting) {
                starting = false;
                status.setText(" ");
                status.setToolTipText(null);
            }
        } else if (context.startProblem() != null) {
            // Nach einem späteren Fehlschlag löscht ein erfolgreicher Neustart die Meldung wieder.
            starting = true;
            context.events().publish(new StatusMessageEvent("Nicht gestartet: "
                    + Problems.describe(context.startProblem()).title(), Integer.MAX_VALUE, Severity.ERROR));
        } else {
            context.events().publish(new StatusMessageEvent("Startet …", Integer.MAX_VALUE, Severity.INFO));
        }
    }

    /** Zeigt eine Statusmeldung vom Bus und blendet sie nach ihrer Dauer aus (wie die Statusleiste in MainframeMate). */
    private void showStatus(StatusMessageEvent event) {
        Runnable apply = () -> {
            StatusMessageEvent.Payload message = event.getPayload();
            String text = message.getText() == null ? "" : message.getText();
            status.setText(text.isEmpty() ? " " : text);
            status.setToolTipText(text.isEmpty() ? null : text);
            status.setForeground(message.getSeverity() == Severity.ERROR ? Comic.RED
                    : message.getSeverity() == Severity.WARN ? Comic.ORANGE : Comic.MUTED);
            statusClear.stop();
            if (message.getDurationMs() < Integer.MAX_VALUE) {
                statusClear.setInitialDelay(message.getDurationMs());
                statusClear.restart();
            }
        };
        if (javax.swing.SwingUtilities.isEventDispatchThread()) {
            apply.run();
        } else {
            javax.swing.SwingUtilities.invokeLater(apply);
        }
    }

    private boolean confirmClose(JFrame frame) {
        AppRuntime runtime = context.runtime();
        if (runtime == null) {
            return true;
        }
        long running;
        try {
            running = runtime.workspace().jobs().stream().filter(j -> !j.state().terminal()).count();
        } catch (RuntimeException e) {
            running = 0;
        }
        return running == 0 || Dialogs.confirm(frame, "Space Harvester beenden?", Texts.count(running, "Auftrag läuft",
                "Aufträge laufen") + " noch. Beim Beenden werden sie abgebrochen; ihr Ergebnis bleibt unbestätigt.",
                "Beenden");
    }

    private static Icon icon(AppContext.Section section) {
        return switch (section) {
            case STATUS -> SidebarIcons.compass();
            case SOURCES -> database();
            case CONTENT -> ComposerIcons.openBook();
            case TERMS -> ComposerIcons.marker();
            case ACTIVITIES -> ComposerIcons.refresh();
            case SEARCH -> ComposerIcons.magnifier();
            case SETTINGS -> ComposerIcons.gear();
        };
    }

    /** Die Datenbank (Zylinder mit Mittelring) für „Wissensquellen verwalten“. */
    static StrokeIcon database() {
        return new StrokeIcon() {
            @Override
            protected void paint(Graphics2D g2) {
                g2.drawOval(2, 1, 10, 4);
                g2.drawLine(2, 3, 2, 11);
                g2.drawLine(12, 3, 12, 11);
                g2.drawArc(2, 5, 10, 4, 180, 180);
                g2.drawArc(2, 9, 10, 4, 180, 180);
            }
        };
    }

    /** Für Tests: die Suche im sichtbaren Bereich. */
    FindBar findBar() {
        return findBar;
    }

    /** Für Tests: die Statuszeile. */
    /** Für Tests: der Bereich Suche. */
    /** Der gezeigte Bereich; für Tests. */
    AppContext.Section current() {
        return current;
    }

    SearchView searchView() {
        return searchView;
    }

    StatusLine statusLine() {
        return statusLine;
    }

    /** Für Tests: die Teilung zwischen Drawer und Arbeitsfläche. */
    com.aresstack.enterpriseai.ui.comic.control.ComicSplitPane split() {
        return split;
    }

    /** Für Tests: der linke Drawer (Explorer, Quellenkarten oder Einstellungs-Navigation). */
    JPanel side() {
        return side;
    }

    /** Für Tests: die Einstellungen. */
    SettingsView settings() {
        return settings;
    }

    /** Für Tests: der Explorer links. */
    Explorer explorer() {
        return explorer;
    }

    /** Für Bildschirmfotos: der aktuell sichtbare Bereich. */
    JComponent deck() {
        return deck;
    }
}
