package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
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
 * Statusleiste die feste Aktions- und Fortschrittszeile des Bereichs ({@link View#actions()}), sonst der globale
 * Fortschritt der Aufträge. Navigation und
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
    private final JPanel side = new JPanel(new BorderLayout());
    /** Aktions- und Fortschrittszeilen der Bereiche, direkt über der Statusleiste. */
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionDeck = new JPanel(actionCards);
    private final javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT);
    static final int SIDEBAR_WIDTH = 280;
    /** Karte der Aktionszeile mit dem globalen Fortschritt der Aufträge. */
    private static final String JOBS = "jobs";
    private final JLabel status = Comic.muted(" ");
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
        explorer = new Explorer(context);
        explorer.onScope(searchView::scope);
        searchView.onClearScope(explorer::clearSelection);
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
        // Der Fortschritt der Aufträge ist global: jeder Bereich ohne eigene Aktionszeile zeigt ihn.
        actionDeck.add(activities.actions(), JOBS);
        views.forEach((section, view) -> {
            if (view.actions() != null && view != activities) {
                actionDeck.add(view.actions(), section.name());
            }
        });
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
     * Arbeitsfläche, Aktionszeile, Statusleiste.
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
        ComicWindowDragger.install(top);

        side.setBackground(Comic.SURFACE);
        side.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        side.setMinimumSize(new Dimension(0, 0));
        side.add(explorer.component(), BorderLayout.CENTER);
        deck.setMinimumSize(new Dimension(320, 0));
        split.setLeftComponent(side);
        split.setRightComponent(deck);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(Comic.SURFACE);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setResizeWeight(0);
        split.setDividerLocation(SIDEBAR_WIDTH);
        split.setDividerSize(8);
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE));
            basic.getDivider().setBackground(Comic.SURFACE);
        }

        actionDeck.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(6, 16, 6, 16)));

        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.WEST);
        foot.add(new CopyrightLink(), BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(actionDeck, BorderLayout.NORTH);
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
    }

    /** Einstellungen mit dem Reiter „KI“. */
    public void showAiSettings() {
        show(AppContext.Section.SETTINGS);
        settings.showAi();
    }

    @Override
    public void show(AppContext.Section section) {
        cards.show(deck, section.name());
        View view = views.get(section);
        actionCards.show(actionDeck, view.actions() != null && view != activities ? section.name() : JOBS);
        ComposerToggleButton button = nav.get(section);
        if (button != null) {
            button.setSelected(true);
        }
        view.shown();
    }

    @Override
    public void openPage(String sourceId, String pageId) {
        show(AppContext.Section.CONTENT);
        content.openPage(sourceId, pageId);
    }

    @Override
    public void openPassage(String sourceId, String passageId) {
        show(AppContext.Section.CONTENT);
        content.openPassage(sourceId, passageId);
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
        updateStatus();
        status.setText(status.getText() + " · " + text);
        status.setForeground(color);
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

    private void updateStatus() {
        AppRuntime runtime = context.runtime();
        String state;
        if (runtime != null) {
            state = Texts.count(runtime.workspace().sources().size(), "Quelle", "Quellen") + " · Bestand "
                    + context.settings().storage().dataDirectory();
        } else if (context.startProblem() != null) {
            state = "Nicht gestartet: " + Problems.describe(context.startProblem()).title();
        } else {
            state = "Startet …";
        }
        status.setText("Space Harvester " + context.version() + " · " + state);
        status.setForeground(Comic.MUTED);
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

    /** Für Tests: der Explorer links. */
    Explorer explorer() {
        return explorer;
    }

    /** Für Bildschirmfotos: der aktuell sichtbare Bereich. */
    JComponent deck() {
        return deck;
    }
}
