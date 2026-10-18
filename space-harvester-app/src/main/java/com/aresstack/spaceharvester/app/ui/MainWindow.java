package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowDragger;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowHoldCloseButton;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowResizer;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowShape;
import com.aresstack.enterpriseai.ui.comic.control.ComposerToggleButton;
import com.aresstack.enterpriseai.ui.comic.paint.ComposerIcons;
import com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons;
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
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Das Hauptfenster: rahmenloses Comic-Fenster wie der Enterprise AI Client. Oben eine Icon-Leiste für die Hauptbereiche
 * (Tooltip mit Namen, Strg+1 … Strg+7), rechts daneben Einstellungen und ✕; links die Seitenleiste des gewählten
 * Bereichs ({@link View#sidebar()}, Breite veränderbar, einklappbar), rechts dessen Arbeitsfläche; über der
 * Statusleiste die feste Aktions- und Fortschrittszeile des Bereichs ({@link View#actions()}). Navigation und
 * Seitenleiste sind getrennte Bausteine. {@link #content} baut den Inhalt ohne Fenster (für Bildschirmfotos und
 * Tests).
 */
public final class MainWindow implements AppContext.Navigator {

    static final int WINDOW_PADDING = 4;
    static final int RESIZE_GRIP = 6;

    private final AppContext context;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final Map<AppContext.Section, View> views = new EnumMap<>(AppContext.Section.class);
    private final Map<AppContext.Section, ComposerToggleButton> nav = new EnumMap<>(AppContext.Section.class);
    /** Seitenleisten der Bereiche; ein Bereich ohne Seitenleiste blendet sie aus. */
    private final CardLayout sideCards = new CardLayout();
    private final JPanel sideDeck = new JPanel(sideCards);
    /** Aktions- und Fortschrittszeilen der Bereiche, direkt über der Statusleiste. */
    private final CardLayout actionCards = new CardLayout();
    private final JPanel actionDeck = new JPanel(actionCards);
    private final javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.HORIZONTAL_SPLIT);
    /** Zuletzt gezogene Breite der Seitenleiste; bleibt beim Wechsel der Bereiche. */
    private int sideWidth = SIDEBAR_WIDTH;
    static final int SIDEBAR_WIDTH = 260;
    private final JLabel status = Comic.muted(" ");
    private final JPanel top = new JPanel(new BorderLayout(12, 0));
    private final StatusView statusView;
    private final SourcesView sources;
    private final ContentView content;
    private final ActivitiesView activities;
    private final ExportsView exports;
    private final SearchView searchView;
    private final SettingsView settings;

    public MainWindow(AppContext context) {
        this.context = context;
        context.setNavigator(this);
        statusView = new StatusView(context);
        sources = new SourcesView(context);
        content = new ContentView(context);
        activities = new ActivitiesView(context);
        exports = new ExportsView(context);
        searchView = new SearchView(context);
        settings = new SettingsView(context);
        views.put(AppContext.Section.STATUS, statusView);
        views.put(AppContext.Section.SOURCES, sources);
        views.put(AppContext.Section.CONTENT, content);
        views.put(AppContext.Section.ACTIVITIES, activities);
        views.put(AppContext.Section.EXPORTS, exports);
        views.put(AppContext.Section.SEARCH, searchView);
        views.put(AppContext.Section.SETTINGS, settings);
        deck.setBackground(Comic.SURFACE);
        views.forEach((section, view) -> deck.add(view.component(), section.name()));
        sideDeck.setBackground(Comic.SURFACE);
        actionDeck.setOpaque(false);
        views.forEach((section, view) -> {
            if (view.sidebar() != null) {
                sideDeck.add(view.sidebar(), section.name());
            }
            if (view.actions() != null) {
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
     * Der Fensterinhalt: Tintenrand, Kopfzeile mit Titel, Icon-Leiste, Einstellungen und ✕, Seitenleiste und
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
        int number = 1;
        for (AppContext.Section section : AppContext.Section.values()) {
            ComposerToggleButton button = navButton(section, number++);
            group.add(button);
            nav.put(section, button);
            if (section != AppContext.Section.SETTINGS) {
                icons.add(button);
            }
        }
        JPanel west = new JPanel(new BorderLayout());
        west.setOpaque(false);
        west.add(title, BorderLayout.WEST);
        west.add(icons, BorderLayout.CENTER);
        JPanel east = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 10, 0));
        east.setOpaque(false);
        east.add(nav.get(AppContext.Section.SETTINGS));
        east.add(Dialogs.closeButton(new ComicWindowHoldCloseButton(Comic.PALETTE, minimize, close, 24)));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 16, 8, 10)));
        top.add(west, BorderLayout.WEST);
        top.add(east, BorderLayout.EAST);
        ComicWindowDragger.install(top);

        sideDeck.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
        sideDeck.setMinimumSize(new Dimension(0, 0));
        deck.setMinimumSize(new Dimension(320, 0));
        split.setLeftComponent(sideDeck);
        split.setRightComponent(deck);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setBackground(Comic.SURFACE);
        split.setContinuousLayout(true);
        split.setOneTouchExpandable(true);
        split.setResizeWeight(0);
        split.setDividerLocation(sideWidth);
        if (split.getUI() instanceof javax.swing.plaf.basic.BasicSplitPaneUI basic) {
            basic.getDivider().setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Comic.LINE));
            basic.getDivider().setBackground(Comic.SURFACE);
        }
        split.addPropertyChangeListener(javax.swing.JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (sideDeck.isVisible() && split.getDividerLocation() > 0) {
                sideWidth = split.getDividerLocation();
            }
        });

        actionDeck.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0,
                Comic.LINE), BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        actionDeck.setVisible(false);

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
        boolean side = view.sidebar() != null;
        if (side) {
            sideCards.show(sideDeck, section.name());
        }
        if (side != sideDeck.isVisible()) {
            sideDeck.setVisible(side);
            split.setDividerSize(side ? 8 : 0);
            if (side) {
                split.setDividerLocation(sideWidth);
            }
        }
        boolean actions = view.actions() != null;
        if (actions) {
            actionCards.show(actionDeck, section.name());
        }
        actionDeck.setVisible(actions);
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
    public void openSpace(String sourceId, String spaceKey) {
        show(AppContext.Section.CONTENT);
        content.openSpace(sourceId, spaceKey);
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

    @Override
    public void prepareExport(String sourceId, List<String> spaceKeys) {
        show(AppContext.Section.EXPORTS);
        exports.prepare(sourceId, spaceKeys);
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
            case SOURCES -> SidebarIcons.folder();
            case CONTENT -> ComposerIcons.openBook();
            case ACTIVITIES -> ComposerIcons.refresh();
            case EXPORTS -> ComposerIcons.download();
            case SEARCH -> ComposerIcons.magnifier();
            case SETTINGS -> ComposerIcons.gear();
        };
    }

    /** Für Bildschirmfotos: der aktuell sichtbare Bereich. */
    JComponent deck() {
        return deck;
    }
}
