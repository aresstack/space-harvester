package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.controls.Starfield;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import java.awt.Component;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Der Titel schaltet die Perspektiven um (#1141): Space Harvester, Command Deck, Proving Ground, Mission Control. Die rechte
 * Seitenleiste bleibt dieselbe Instanz samt Zustand, ein Bereich des Space Harvesters führt in ihn zurück.
 */
class MainWindowPerspectiveTest {

    @TempDir
    Path home;

    private AppContext context;
    private long started;

    @org.junit.jupiter.api.BeforeEach
    void mark() {
        started = Background.mark();
    }

    /** Erst die eigene Hintergrundarbeit samt Rückmeldungen abwarten, dann die Laufzeit schließen. */
    @AfterEach
    void shutdown() throws Exception {
        if (context != null) {
            try {
                SwingUtilities.invokeAndWait(() -> { });
                Background.awaitIdle(started, java.time.Duration.ofSeconds(30));
            } finally {
                context.shutdown();
            }
        }
    }

    @Test
    void theTitleSwitchesPerspectivesAndKeepsTheSidebar() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nText.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Betriebshandbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            surface.doLayout();
            window.show(AppContext.Section.CONTENT);
            Perspectives perspectives = window.perspectives();

            // Menü: genau vier Einträge in dieser Reihenfolge, Mission Control als Platzhalter.
            var menu = window.perspectiveSwitcher().menu();
            assertThat(menu.getComponents()).hasSize(4);
            assertThat(((JMenuItem) menu.getComponent(0)).getAccessibleContext().getAccessibleName())
                    .isEqualTo("Space Harvester");
            assertThat(((JMenuItem) menu.getComponent(0)).isSelected()).isTrue();
            assertThat(((JMenuItem) menu.getComponent(1)).getAccessibleContext().getAccessibleName())
                    .isEqualTo("Command Deck");
            assertThat(((JMenuItem) menu.getComponent(2)).getAccessibleContext().getAccessibleName())
                    .isEqualTo("Proving Ground");
            assertThat(((JMenuItem) menu.getComponent(3)).getAccessibleContext().getAccessibleName())
                    .isEqualTo("Mission Control, in Vorbereitung");
            assertThat(window.perspectiveSwitcher().shownTitle()).isEqualTo("Space Harvester");

            window.chatOpen(true);
            Component sidebar = window.sideDeck();
            Component harvesterWork = window.chatSplit().getLeftComponent();

            ((JMenuItem) menu.getComponent(3)).doClick();
            assertThat(perspectives.isCurrent(Perspectives.MISSION_CONTROL)).isTrue();
            assertThat(window.perspectiveSwitcher().shownTitle()).isEqualTo("Mission Control");
            assertThat(visible(window.stage())).isInstanceOf(Starfield.class);
            assertThat(window.split().isLeftCollapsed()).as("ohne linke Leiste").isTrue();
            assertThat(window.split().getDividerSize()).isZero();
            // Dieselbe Seitenleiste, offen wie zuvor; keine zweite Instanz.
            assertThat(window.sideDeck()).isSameAs(sidebar);
            assertThat(window.chatSplit().getRightComponent()).isSameAs(sidebar);
            assertThat(window.chatSplit().getLeftComponent()).isSameAs(harvesterWork);
            assertThat(window.chatOpen()).isTrue();
            // Aufträge öffnen die globale Leiste, die Perspektive bleibt.
            window.show(AppContext.Section.ACTIVITIES);
            assertThat(perspectives.isCurrent(Perspectives.MISSION_CONTROL)).isTrue();
            assertThat(window.jobsOpen()).isTrue();

            // Ein Bereich des Space Harvesters (Suche, auch über einen Link) führt in dessen Perspektive zurück.
            window.show(AppContext.Section.SEARCH);
            assertThat(perspectives.isCurrent(Perspectives.HARVESTER)).isTrue();
            assertThat(window.current()).isEqualTo(AppContext.Section.SEARCH);
            assertThat(window.split().isLeftCollapsed()).as("Explorer wieder offen").isFalse();
            assertThat(window.jobsOpen()).isTrue();

            // War der Explorer zu, bleibt er nach dem Ausflug zu.
            var logo = (javax.swing.AbstractButton) window.perspectiveSwitcher().logo();
            assertThat(logo.getToolTipText()).isEqualTo("Seitenleiste einklappen");
            logo.doClick();
            assertThat(window.split().isLeftCollapsed()).isTrue();
            assertThat(logo.getToolTipText()).isEqualTo("Seitenleiste ausklappen");
            perspectives.select(Perspectives.COMMAND_DECK);
            assertThat(window.commandDeck().sessions().all()).as("Wechsel verbindet nicht").isEmpty();
            assertThat(window.commandDeck().area()).as("Wechsel landet auf Home")
                    .isEqualTo(com.aresstack.spaceharvester.app.ui.deck.DeckArea.HOME);
            assertThat(logo.getToolTipText()).isEqualTo("Keine Seitenleiste verfügbar");
            logo.doClick();
            assertThat(window.split().isLeftCollapsed()).isTrue();
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.FILES);
            var filesSidebar = window.commandDeck().activeSidebar();
            assertThat(filesSidebar).isNotNull();
            assertThat(logo.getToolTipText()).isEqualTo("Seitenleiste einklappen");
            logo.doClick();
            assertThat(filesSidebar.isLeftCollapsed()).isTrue();
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.TERMINAL);
            assertThat(window.commandDeck().activeSidebar()).isNull();
            assertThat(logo.getToolTipText()).isEqualTo("Keine Seitenleiste verfügbar");
            logo.doClick();
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.FILES);
            assertThat(window.commandDeck().activeSidebar()).isSameAs(filesSidebar);
            assertThat(filesSidebar.isLeftCollapsed()).isTrue();
            logo.doClick();
            assertThat(filesSidebar.isLeftCollapsed()).isFalse();
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.HOME);
            // Der Stand des Deck-Bereichs steht links in der Statuszeile der App, je Bereich eine Karte.
            assertThat(window.viewStatus().isVisible()).isTrue();
            assertThat(visible(window.viewStatus())).isSameAs(window.commandDeck().perspective().status());
            JComponent deckStatus = window.commandDeck().perspective().status();
            assertThat(deckStatus.getComponentCount())
                    .isEqualTo(com.aresstack.spaceharvester.app.ui.deck.DeckArea.values().length);
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.TERMINAL);
            Component terminalStatus = visible(deckStatus);
            assertThat(terminalStatus).isInstanceOf(com.aresstack.spaceharvester.app.ui.controls.StatusLabel.class);
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.FTP);
            assertThat(visible(deckStatus)).isNotSameAs(terminalStatus)
                    .isInstanceOf(com.aresstack.spaceharvester.app.ui.controls.StatusLabel.class);
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.TERMINAL);
            assertThat(SwingUtilities.isDescendingFrom(window.commandDeck().perspective().navigation(),
                    window.windowBar())).isTrue();
            perspectives.select(Perspectives.MISSION_CONTROL);
            assertThat(window.commandDeck().perspective().navigation().isShowing()).isFalse();
            assertThat(window.viewStatus().isVisible()).as("ohne eigenen Stand").isFalse();
            perspectives.select(Perspectives.PROVING_GROUND);
            assertThat(window.perspectiveSwitcher().shownTitle()).isEqualTo("Proving Ground");
            assertThat(visible(window.stage()))
                    .isInstanceOf(com.aresstack.spaceharvester.app.ui.proving.ProvingGroundWorkspace.class);
            perspectives.select(Perspectives.HARVESTER);
            assertThat(window.split().isLeftCollapsed()).isTrue();
            assertThat(window.split().getDividerSize()).isEqualTo(MainWindow.COLLAPSED_DIVIDER);
            window.toggleDrawer();

            // Einstellungen aus dem Command Deck: Die Perspektive bleibt, „Zurück“ führt ins Command Deck (Angelo, 23.11.).
            perspectives.select(Perspectives.COMMAND_DECK);
            window.show(AppContext.Section.SETTINGS);
            assertThat(perspectives.isCurrent(Perspectives.COMMAND_DECK)).as("kein Sprung zum Space Harvester").isTrue();
            assertThat(window.perspectiveSwitcher().shownTitle()).isEqualTo("Command Deck");
            assertThat(window.settingsOverPerspective()).isTrue();
            assertThat(window.current()).isEqualTo(AppContext.Section.SETTINGS);
            assertThat(visible(window.stage())).isNotSameAs(window.commandDeck().perspective().workspace());
            assertThat(window.split().isLeftCollapsed()).as("Leiste der Einstellungen offen").isFalse();
            window.settings().back.doClick();
            assertThat(perspectives.isCurrent(Perspectives.COMMAND_DECK)).isTrue();
            assertThat(window.settingsOverPerspective()).isFalse();
            assertThat(window.navSelected(AppContext.Section.SETTINGS)).as("Zahnrad nicht mehr gedrückt").isFalse();
            assertThat(visible(window.stage())).isSameAs(window.commandDeck().perspective().workspace());
            assertThat(window.split().isLeftCollapsed()).as("das Deck hat keine linke Leiste").isTrue();
            // Ein Knopf der Deck-Navigation schließt die Einstellungen ebenso.
            window.show(AppContext.Section.SETTINGS);
            ((javax.swing.AbstractButton) window.commandDeck().perspective().navigation().getComponent(1)).doClick();
            assertThat(window.settingsOverPerspective()).isFalse();
            assertThat(window.navSelected(AppContext.Section.SETTINGS)).as("Zahnrad nicht mehr gedrückt").isFalse();
            assertThat(visible(window.stage())).isSameAs(window.commandDeck().perspective().workspace());
            // Wechselt das Deck seinen Bereich ohne Knopf (Kachel auf Home, Chat), gehen die Einstellungen ebenso zu.
            window.show(AppContext.Section.SETTINGS);
            assertThat(window.navSelected(AppContext.Section.SETTINGS)).isTrue();
            window.commandDeck().show(com.aresstack.spaceharvester.app.ui.deck.DeckArea.TERMINAL);
            assertThat(window.settingsOverPerspective()).isFalse();
            assertThat(window.navSelected(AppContext.Section.SETTINGS)).as("Zahnrad nicht mehr gedrückt").isFalse();
            assertThat(visible(window.stage())).isSameAs(window.commandDeck().perspective().workspace());
            // Direkt in eine andere Perspektive gewechselt: Ein zugeklappter Explorer bleibt zu.
            perspectives.select(Perspectives.HARVESTER);
            assertThat(logo.getToolTipText()).isEqualTo("Seitenleiste einklappen");
            logo.doClick();
            assertThat(window.split().isLeftCollapsed()).isTrue();
            assertThat(logo.getToolTipText()).isEqualTo("Seitenleiste ausklappen");
            perspectives.select(Perspectives.COMMAND_DECK);
            window.show(AppContext.Section.SETTINGS);
            perspectives.select(Perspectives.MISSION_CONTROL);
            assertThat(window.settingsOverPerspective()).isFalse();
            assertThat(window.navSelected(AppContext.Section.SETTINGS)).as("Zahnrad nicht mehr gedrückt").isFalse();
            // Zurück im Space Harvester steht der Bereich von vorher, nicht die Einstellungen.
            perspectives.select(Perspectives.HARVESTER);
            assertThat(window.current()).isEqualTo(AppContext.Section.SEARCH);
            assertThat(window.split().isLeftCollapsed()).as("Explorer bleibt zu").isTrue();
            // Gemerkten Zustand zurückstellen: Die Tests teilen sich den App-Zustand.
            window.toggleDrawer();
        });
    }

    private static Component visible(JComponent stage) {
        for (Component child : stage.getComponents()) {
            if (child.isVisible()) {
                return child;
            }
        }
        return null;
    }
}
