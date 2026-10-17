package com.aresstack.spaceharvester.app.ui;

import com.aresstack.enterpriseai.ui.comic.border.ComicBorder;
import com.aresstack.enterpriseai.ui.comic.control.ComicSearchBar;
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
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.WindowConstants;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Dimension;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.EnumMap;
import java.util.Map;

/**
 * Das Hauptfenster: rahmenloses Comic-Fenster wie der Enterprise AI Client, links die sieben Bereiche, oben die
 * globale Suche, unten die Statuszeile. {@link #content} baut den Inhalt ohne Fenster (für Bildschirmfotos und Tests).
 */
public final class MainWindow implements AppContext.Navigator {

    static final int WINDOW_PADDING = 4;
    static final int RESIZE_GRIP = 6;

    private final AppContext context;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final Map<AppContext.Section, View> views = new EnumMap<>(AppContext.Section.class);
    private final Map<AppContext.Section, ComposerToggleButton> nav = new EnumMap<>(AppContext.Section.class);
    private final ComicSearchBar search = new ComicSearchBar("Suchen …", "Titel und Inhalt in den Quellen suchen",
            Comic.PALETTE);
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
        search.addSearchAction(e -> search(search.getText()));
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

    /**
     * Der Fensterinhalt: Tintenrand, Kopfzeile mit Titel, Suche und ✕, Navigation, Bereiche, Statuszeile.
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
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 10));
        top.add(title, BorderLayout.WEST);
        JPanel searchHolder = new JPanel(new BorderLayout());
        searchHolder.setOpaque(false);
        searchHolder.setBorder(BorderFactory.createEmptyBorder(0, 80, 0, 80));
        searchHolder.add(search, BorderLayout.CENTER);
        top.add(searchHolder, BorderLayout.CENTER);
        top.add(Dialogs.closeButton(new ComicWindowHoldCloseButton(Comic.PALETTE, minimize, close, 24)),
                BorderLayout.EAST);
        ComicWindowDragger.install(top);

        JPanel side = new JPanel();
        side.setLayout(new BoxLayout(side, BoxLayout.Y_AXIS));
        side.setBackground(Comic.SURFACE);
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Comic.LINE),
                BorderFactory.createEmptyBorder(8, 10, 10, 10)));
        ButtonGroup group = new ButtonGroup();
        for (AppContext.Section section : AppContext.Section.values()) {
            if (section == AppContext.Section.SETTINGS) {
                side.add(Box.createVerticalGlue());
            }
            ComposerToggleButton button = new ComposerToggleButton(icon(section), section.title(), null);
            button.setAccent(ResearchUiPalette.SECONDARY_SURFACE);
            button.setHorizontalAlignment(ComposerToggleButton.LEFT);
            button.setIconTextGap(10);
            button.setAlignmentX(0f);
            button.setMaximumSize(new Dimension(Integer.MAX_VALUE, button.getPreferredSize().height));
            button.addActionListener(e -> show(section));
            group.add(button);
            nav.put(section, button);
            side.add(button);
            side.add(Box.createVerticalStrut(4));
        }
        Dimension width = new Dimension(190, 10);
        side.setPreferredSize(new Dimension(width.width, side.getPreferredSize().height));

        JPanel foot = new JPanel(new BorderLayout());
        foot.setOpaque(false);
        foot.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Comic.LINE),
                BorderFactory.createEmptyBorder(6, 16, 6, 16)));
        foot.add(status, BorderLayout.WEST);
        foot.add(new CopyrightLink(), BorderLayout.EAST);

        JPanel middle = new JPanel(new BorderLayout());
        middle.setOpaque(false);
        middle.add(side, BorderLayout.WEST);
        middle.add(deck, BorderLayout.CENTER);
        surface.add(top, BorderLayout.NORTH);
        surface.add(middle, BorderLayout.CENTER);
        surface.add(foot, BorderLayout.SOUTH);
        ComicWindowResizer.install(surface, RESIZE_GRIP);
        return surface;
    }

    @Override
    public void show(AppContext.Section section) {
        cards.show(deck, section.name());
        ComposerToggleButton button = nav.get(section);
        if (button != null) {
            button.setSelected(true);
        }
        views.get(section).shown();
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
    public void prepareExport(String sourceId, String spaceKey) {
        show(AppContext.Section.EXPORTS);
        exports.prepare(sourceId, spaceKey);
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
            case CONTENT -> SidebarIcons.pageTree();
            case ACTIVITIES -> ComposerIcons.refresh();
            case EXPORTS -> ComposerIcons.export();
            case SEARCH -> ComposerIcons.magnifier();
            case SETTINGS -> ComposerIcons.gear();
        };
    }

    /** Für Bildschirmfotos: der aktuell sichtbare Bereich. */
    JComponent deck() {
        return deck;
    }
}
