package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.enterpriseai.ui.comic.control.ComicWindowCloseButton;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.font.TextAttribute;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Oberfläche ohne Bildschirm: Das Hauptfenster baut alle Bereiche auf einer echten Laufzeit (lokale Dateien), zeigt
 * jeden Bereich, rendert ihn und navigiert vom Abgleich zur Seite.
 */
class MainWindowTest {

    @TempDir
    Path home;

    private AppContext context;

    @AfterEach
    void shutdown() {
        if (context != null) {
            context.shutdown();
        }
    }

    /** Eine Meldung mit ✕ (etwa das Ergebnis eines Exports) bleibt in der Statuszeile stehen, bis sie weggeklickt wird. */
    @Test
    void aClosableStatusMessageStaysUntilItsCrossIsClicked() throws Exception {
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
        MainWindow[] window = new MainWindow[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new MainWindow(context);
            window[0].content(() -> { }, () -> { }).setSize(1280, 820);
            // Ein langer Stand eines anderen Bereichs macht den linken Teil nicht breit: Sonst stünde der Auftragstext
            // mittig und Zurück und Vor rechts, neben einer leeren Fläche (Angelo 22.11.2026).
            window[0].show(AppContext.Section.CONTENT);
            int own = window[0].viewStatus().getPreferredSize().width;
            javax.swing.JLabel far = new javax.swing.JLabel("Sehr langer Stand eines anderen Bereichs ".repeat(8));
            ((JPanel) window[0].viewStatus()).add(far, "fremd");
            assertThat(far.getPreferredSize().width).isGreaterThan(900);
            assertThat(window[0].viewStatus().getPreferredSize().width).isEqualTo(own);
            ((JPanel) window[0].viewStatus()).remove(far);
            context.events().publish(new com.aresstack.spaceharvester.app.event.StatusMessageEvent(
                    "PDF wird eingeplant …", Integer.MAX_VALUE,
                    com.aresstack.spaceharvester.app.event.Severity.INFO, false));
            assertThat(window[0].statusText().getText()).isEqualTo("PDF wird eingeplant …");
            assertThat(window[0].statusClose().isVisible()).as("Laufmeldung ohne ✕").isFalse();
            context.events().publish(new com.aresstack.spaceharvester.app.event.StatusMessageEvent(
                    "handbuch.pdf gespeichert in Ausgabe", 8000,
                    com.aresstack.spaceharvester.app.event.Severity.INFO, true));
            assertThat(window[0].statusText().getText()).isEqualTo("handbuch.pdf gespeichert in Ausgabe");
            assertThat(window[0].statusClose().isVisible()).as("Ergebnis mit ✕").isTrue();
            assertThat(SwingUtilities.isDescendingFrom(window[0].statusClose(), window[0].statusLine())).isTrue();
            window[0].statusClose().doClick();
            assertThat(window[0].statusText().getText()).isEqualTo(" ");
            assertThat(window[0].statusClose().isVisible()).isFalse();
            // Eine Ansicht beendet nur ihre eigene Meldung: steht inzwischen eine andere dort, bleibt sie.
            context.events().publish(new com.aresstack.spaceharvester.app.event.StatusMessageEvent(
                    "Anhänge eingeplant", Integer.MAX_VALUE,
                    com.aresstack.spaceharvester.app.event.Severity.INFO, true));
            context.events().publish(com.aresstack.spaceharvester.app.event.StatusMessageEvent.clear(
                    "Seite wird geladen …"));
            assertThat(window[0].statusText().getText()).isEqualTo("Anhänge eingeplant");
            context.events().publish(com.aresstack.spaceharvester.app.event.StatusMessageEvent.clear(
                    "Anhänge eingeplant"));
            assertThat(window[0].statusText().getText()).isEqualTo(" ");
            assertThat(window[0].statusClose().isVisible()).isFalse();
        });
    }

    @Test
    void everySectionRendersAndNavigationReachesAPage() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nNeustart mit systemctl.\n");
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
        assertThat(context.runtime()).isNotNull();

        MainWindow[] window = new MainWindow[1];
        JPanel[] surface = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new MainWindow(context);
            surface[0] = window[0].content(() -> { }, () -> { });
            surface[0].setSize(1280, 820);
        });
        // Suche nur unter der Arbeitsfläche, nicht unter dem Explorer; der Fortschritt steckt in der Statuszeile.
        assertThat(SwingUtilities.isDescendingFrom(window[0].findBar(), window[0].deck().getParent().getParent()))
                .isTrue();
        assertThat(SwingUtilities.isDescendingFrom(window[0].findBar(), window[0].explorer().component())).isFalse();
        assertThat(SwingUtilities.isDescendingFrom(window[0].statusLine(), surface[0])).isTrue();
        for (AppContext.Section section : AppContext.Section.values()) {
            Path png = home.resolve(section.name() + ".png");
            SwingUtilities.invokeAndWait(() -> {
                window[0].show(section);
                Screenshots.write(surface[0], png);
            });
            assertThat(png).isNotEmptyFile();
        }
        // Oben nur Symbole; Name und Tastenkürzel stehen im Tooltip und für Bildschirmleser im Namen. Links die Suche
        // und die Reiter der Seitenansicht, rechts Aufträge, dann Verwaltung und Einstellungen (Angelo,
        // 13.11.2026); Inhalte hat keinen eigenen Knopf mehr.
        assertThat(find(surface[0], AbstractButton.class).stream()
                .map(button -> button.getAccessibleContext().getAccessibleName()).toList())
                .containsSubsequence("Suche", "Leseansicht", "Podcast-Ansicht", "Anhänge", "Analysen", "Info",
                        "Export", "Chat", "Aufträge", "Wissensquellen verwalten", "Einstellungen")
                .doesNotContain("Inhalte")
                .as("Herunterladen geht über den Explorer, kein eigener Bereich").doesNotContain("Herunterladen")
                .as("Status ist kein Bereich mehr, sondern Einstellungen › Diagnose").doesNotContain("Status");
        assertThat(find(surface[0], AbstractButton.class).stream().map(AbstractButton::getToolTipText).toList())
                .contains("Suche (Strg+1)", "Aufträge (Strg+3)", "Wissensquellen verwalten (Strg+4)",
                        "Einstellungen (Strg+5)")
                .as("Begriffe stehen unter Analysen, kein eigener Bereich (BG2c)")
                .noneMatch(tip -> tip != null && tip.startsWith("Begriffe (Strg+"))
                .doesNotContain("Inhalte (Strg+2)");
        // Ein Reiter in der Leiste schaltet die Mitte auf Inhalte; in anderen Bereichen zeigt keiner der Reiter eine
        // Auswahl, zurück unter Inhalte ist der gewählte Reiter wieder dunkel.
        List<AbstractButton> tabs = find(surface[0], AbstractButton.class).stream()
                .filter(button -> "Podcast-Ansicht".equals(button.getAccessibleContext().getAccessibleName())
                        || "Leseansicht".equals(button.getAccessibleContext().getAccessibleName())).toList();
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.SEARCH));
        assertThat(tabs).noneMatch(AbstractButton::isSelected);
        SwingUtilities.invokeAndWait(() -> tabs.stream().filter(b -> "Podcast-Ansicht".equals(
                b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().doClick());
        assertThat(window[0].current()).isEqualTo(AppContext.Section.CONTENT);
        assertThat(tabs).filteredOn(AbstractButton::isSelected)
                .extracting(b -> b.getAccessibleContext().getAccessibleName()).containsExactly("Podcast-Ansicht");
        // Die Aufträge sind kein Bereich der Mitte mehr: sie öffnen die Seitenleiste, die Mitte bleibt bei „Text“.
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.ACTIVITIES));
        assertThat(window[0].current()).isEqualTo(AppContext.Section.CONTENT);
        assertThat(window[0].jobsOpen()).isTrue();
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.SOURCES));
        assertThat(tabs).noneMatch(AbstractButton::isSelected);
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.CONTENT));
        assertThat(tabs).filteredOn(AbstractButton::isSelected)
                .extracting(b -> b.getAccessibleContext().getAccessibleName()).containsExactly("Podcast-Ansicht");
        assertThat(find(surface[0], com.aresstack.enterpriseai.ui.comic.control.ComicSearchBar.class))
                .as("keine globale Suchleiste mehr; gesucht wird im Bereich Suche").isEmpty();
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.STATUS));
        assertThat(window[0].current()).as("Status führt zu Einstellungen › Diagnose").isEqualTo(AppContext.Section.SETTINGS);
        assertThat(labelTexts(surface[0])).as("Status ist die Diagnose, nicht die Begrüßung")
                .doesNotContain(AboutDialog.TAGLINE);
        // Die Diagnose hält ihre Abfrage an, wenn der Bereich wechselt; kommt man über Einstellungen zurück und ihre
        // Seite steht noch an, wird sie erneut gezeigt und fragt weiter ab.
        int[] shown = new int[1];
        View counting = new View() {
            @Override
            public JComponent component() {
                return new JPanel();
            }

            @Override
            public void shown() {
                shown[0]++;
            }
        };
        SwingUtilities.invokeAndWait(() -> {
            window[0].settings().diagnosis(counting);
            window[0].settings().select(SettingsView.DIAGNOSIS);
            window[0].show(AppContext.Section.SEARCH);
            window[0].show(AppContext.Section.SETTINGS);
        });
        assertThat(shown[0]).as("Seite gewählt, Bereich erneut sichtbar").isEqualTo(2);
        // „Zu den Einstellungen“ aus der Diagnose führt zur Seite Allgemein, nicht zurück auf die Diagnose.
        SwingUtilities.invokeAndWait(() -> window[0].showGeneralSettings());
        assertThat(window[0].settings().selected()).isEqualTo(SettingsView.GENERAL);

        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        String job = context.runtime().workspace().startSync("handbuch", space).id();
        for (int i = 0; i < 600 && !context.runtime().workspace().job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        String pageId = context.runtime().workspace().cachedPages("handbuch", space, null, 10).pages().get(0)
                .header().key().pageId();

        // Der Explorer links: Wissensquelle → Space → Seite, erst beim Aufklappen geladen.
        javax.swing.JTree tree = window[0].explorer().tree();
        SwingUtilities.invokeAndWait(() -> {
            assertThat(tree.getRowCount()).isEqualTo(1);
            assertThat(tree.getPathForRow(0).getLastPathComponent().toString()).isEqualTo("Betriebshandbuch");
            tree.expandRow(0);
        });
        awaitRows(tree, 2);
        SwingUtilities.invokeAndWait(() -> tree.expandRow(1));
        awaitRows(tree, 3);
        String[] page = new String[1];
        SwingUtilities.invokeAndWait(() -> page[0] = tree.getPathForRow(2).getLastPathComponent().toString());
        assertThat(page[0]).isNotEqualTo(Explorer.LOADING).containsIgnoringCase("betrieb");
        SwingUtilities.invokeAndWait(() -> {
            tree.setSelectionRow(1);
            assertThat(window[0].explorer().scope()).isEqualTo(new Explorer.Scope("handbuch", space));
            window[0].explorer().filter().setText("betr");
        });
        for (int i = 0; i < 100; i++) {
            int[] found = new int[1];
            SwingUtilities.invokeAndWait(() -> found[0] = window[0].explorer().matches().getModel().getSize());
            if (found[0] > 0) {
                break;
            }
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> assertThat(window[0].explorer().matches().getModel().getSize())
                .as("Titelfilter findet die Seite").isEqualTo(1));
        SwingUtilities.invokeAndWait(() -> Screenshots.write(surface[0], home.resolve("explorer.png")));

        // „Indexieren“ im Kontextmenü des Space: markiert ihn und startet den Abgleich sofort, ohne Dialog.
        List<String> jobsBefore = context.runtime().workspace().jobs().stream().map(JobStatus::id).toList();
        SwingUtilities.invokeAndWait(() -> {
            window[0].explorer().filter().setText("");
            Explorer.Item spaceNode = ((Explorer.Node) tree.getPathForRow(1).getLastPathComponent()).item();
            assertThat(spaceNode).isInstanceOf(Explorer.SpaceItem.class);
            javax.swing.JPopupMenu menu = window[0].explorer().menu(spaceNode);
            javax.swing.JMenuItem index = (javax.swing.JMenuItem) java.util.Arrays.stream(menu.getComponents())
                    .filter(c -> c instanceof javax.swing.JMenuItem item && Explorer.INDEX.equals(item.getText()))
                    .findFirst().orElseThrow();
            index.doClick();
        });
        // Der Abgleich plant danach selbst Folgeaufträge ein (Anreicherung, Bilder), und Hintergrundaufträge des
        // Starts können noch nachkommen; gezählt wird, was der Klick startet.
        List<JobStatus> clicked = List.of();
        for (int i = 0; i < 100 && clicked.isEmpty(); i++) {
            Thread.sleep(50);
            clicked = context.runtime().workspace().jobs().stream().filter(j -> !jobsBefore.contains(j.id()))
                    .filter(j -> !j.type().followUp()).filter(j -> !j.type().background()).toList();
        }
        assertThat(clicked).as("vom Klick gestartete Aufträge").singleElement()
                .extracting(JobStatus::type).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobType.SYNC);
        SwingUtilities.invokeAndWait(() -> {
            window[0].showJob(job);
            window[0].showDiagnosis(job);
            window[0].openSpace("handbuch", space);
            window[0].openPage("handbuch", pageId);
            window[0].search("betrieb");
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> Screenshots.write(surface[0], home.resolve("export.png")));
    }

    @Test
    void anOverlayCoversTheContentAreaOnlyUnderContents() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            window.show(AppContext.Section.CONTENT);
            JPanel picture = new JPanel();
            assertThat(window.contentOverlay()).isNull();

            context.events().publish(com.aresstack.spaceharvester.app.event.ContentOverlayEvent.show(picture));
            layout(surface);
            assertThat(window.contentOverlay()).isSameAs(picture);
            // Die Fläche deckt genau den Inhaltsbereich; Zeitleiste und Suchleiste darunter bleiben frei.
            assertThat(SwingUtilities.convertRectangle(picture.getParent(), picture.getBounds(), surface))
                    .isEqualTo(SwingUtilities.convertRectangle(window.deck().getParent(), window.deck().getBounds(),
                            surface));

            // In einem anderen Bereich liegt sie nicht über dessen Ansicht, zurück unter „Inhalte“ wieder schon.
            window.show(AppContext.Section.STATUS);
            assertThat(window.contentOverlay()).isNull();
            window.show(AppContext.Section.CONTENT);
            assertThat(window.contentOverlay()).isSameAs(picture);

            context.events().publish(com.aresstack.spaceharvester.app.event.ContentOverlayEvent.hide(picture));
            assertThat(window.contentOverlay()).isNull();
        });
    }

    @Test
    void everyVisibleDividerShowsItsResizeCursor() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            // Zugeklappter Chat: sein Trenner lässt sich nicht ziehen und zeigt den normalen Zeiger.
            window.chatOpen(true);
            assertThat(com.aresstack.spaceharvester.app.ui.controls.ComicDivider.draggable(window.chatSplit()))
                    .isTrue();
            window.chatOpen(false);
            assertThat(com.aresstack.spaceharvester.app.ui.controls.ComicDivider.draggable(window.chatSplit()))
                    .isFalse();
            assertThat(window.chatSplit().isEnabled()).isTrue();
            // Auch F8 (Verschieben per Tastatur) greift am gesperrten Trenner nicht, offen wieder schon.
            javax.swing.KeyStroke f8 = javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_F8, 0);
            javax.swing.InputMap keys = window.chatSplit()
                    .getInputMap(javax.swing.JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
            assertThat(keys.get(f8)).isEqualTo("none");
            window.chatOpen(true);
            assertThat(keys.get(f8)).isEqualTo("startResize");
            window.chatOpen(false);
            for (AppContext.Section section : AppContext.Section.values()) {
                window.show(section);
                layout(surface);
                layout(surface);
                java.util.List<javax.swing.plaf.basic.BasicSplitPaneDivider> dividers = new java.util.ArrayList<>();
                dividers(surface, dividers);
                assertThat(dividers).as(section.name()).isNotEmpty();
                for (javax.swing.plaf.basic.BasicSplitPaneDivider divider : dividers) {
                    java.awt.Point middle = SwingUtilities.convertPoint(divider, divider.getWidth() / 2,
                            divider.getHeight() / 2, surface);
                    if (surface.findComponentAt(middle) != divider) {
                        continue; // liegt auf einer gerade nicht gezeigten Karte
                    }
                    javax.swing.JSplitPane pane = (javax.swing.JSplitPane) divider.getParent();
                    String where = section.name() + " " + divider.getBounds();
                    // AWT sucht den Zeiger nur in eingeschalteten Flächen: Eine abgeschaltete Fläche über dem
                    // Trenner nähme ihm den Doppelpfeil.
                    for (java.awt.Container up = divider.getParent(); up != null; up = up.getParent()) {
                        assertThat(up.isEnabled()).as(where + " in " + up.getClass().getSimpleName()).isTrue();
                    }
                    int expected = pane == window.chatSplit() && !window.jobsOpen() ? java.awt.Cursor.DEFAULT_CURSOR
                            : pane.getOrientation() == javax.swing.JSplitPane.VERTICAL_SPLIT
                                    ? java.awt.Cursor.S_RESIZE_CURSOR : java.awt.Cursor.E_RESIZE_CURSOR;
                    assertThat(divider.getCursor().getType()).as(where).isEqualTo(expected);
                }
            }
        });
    }

    private static void dividers(java.awt.Container root,
            java.util.List<javax.swing.plaf.basic.BasicSplitPaneDivider> found) {
        for (Component component : root.getComponents()) {
            if (component instanceof javax.swing.plaf.basic.BasicSplitPaneDivider divider) {
                found.add(divider);
            } else if (component instanceof java.awt.Container container) {
                dividers(container, found);
            }
        }
    }

    @Test
    void theFreeAreaOfTheWindowBarTellsAboutTheDoubleClick() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            layout(surface);
            JPanel bar = window.windowBar();
            java.awt.event.MouseEvent over = new java.awt.event.MouseEvent(bar, java.awt.event.MouseEvent.MOUSE_MOVED,
                    0, 0, 2, 2, 0, false);
            // Nicht maximiert (hier ohne Fenster) nennt die freie Fläche den Doppelklick zum Vergrößern.
            assertThat(bar.getToolTipText(over)).isEqualTo("Doppelklick: Vollbild");
            assertThat(MainWindow.windowBarTooltip(true)).isEqualTo("Doppelklick: Verkleinern");
            // Knöpfe und Felder der Leiste behalten ihre eigenen Tooltips, keiner zeigt den der Leiste.
            java.util.List<String> own = new java.util.ArrayList<>();
            tooltips(bar, own);
            assertThat(own).isNotEmpty().doesNotContain(MainWindow.MAXIMIZE_TOOLTIP, MainWindow.RESTORE_TOOLTIP);
        });
    }

    private static void tooltips(java.awt.Container root, java.util.List<String> found) {
        for (Component component : root.getComponents()) {
            if (component instanceof JComponent child && child.getToolTipText() != null) {
                found.add(child.getToolTipText());
            }
            if (component instanceof java.awt.Container container) {
                tooltips(container, found);
            }
        }
    }

    @Test
    void withoutSourcesTheOverviewWelcomesWithTheWayToTheFirstSource() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        JPanel[] surface = new JPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            surface[0] = window.content(() -> { }, () -> { });
            surface[0].setSize(1280, 820);
            window.show(AppContext.Section.STATUS);
            Screenshots.write(surface[0], home.resolve("welcome.png"));
        });
        assertThat(home.resolve("welcome.png")).isNotEmptyFile();
        assertThat(buttonTexts(surface[0])).contains("Erste Wissensquelle hinzufügen …");
        assertThat(labelTexts(surface[0])).contains("Space Harvester", AboutDialog.TAGLINE);
        assertThat(labelTexts(AboutDialog.body(context))).contains(AboutDialog.TAGLINE);
    }

    /**
     * Der Chat ist eine Seitenleiste rechts neben der Arbeitsfläche (Angelo, 21.11.2026): der Knopf oben rechts vor
     * „Aufträge“, Strg+J und „»“ in der Leiste klappen sie auf und zu; sie bleibt in jedem Bereich stehen, zugeklappt
     * bleibt nur der schmale Teiler, und Zustand samt Breite gelten für den nächsten Start.
     */
    @Test
    void theChatIsASidebarNextToTheWorkAreaThatTheButtonTopRightOpensAndCloses() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            try {
                surface.setSize(1400, 820);
                window.chatOpen(false);
                layoutAll(surface);
                javax.swing.JSplitPane chatSplit = window.chatSplit();
                // Rechts steht der Stapel aus Chat und Aufträgen; hier zählt die Chat-Leiste darin.
                Component sideDeck = chatSplit.getRightComponent();
                Component sidebar = find((java.awt.Container) sideDeck,
                        com.aresstack.spaceharvester.app.ui.content.ChatSidebar.class).get(0);
                assertThat(SwingUtilities.isDescendingFrom(window.deck(), chatSplit.getLeftComponent())).isTrue();
                assertThat(window.split().getRightComponent()).isSameAs(chatSplit);
                // Zu: kein Platz für die Leiste, nur der schmale Teiler; der Knopf ist hell.
                assertThat(window.chatToggle().isSelected()).isFalse();
                assertThat(window.chatToggle().getToolTipText()).isEqualTo(MainWindow.CHAT_TOOLTIP);
                assertThat(sidebar.getWidth()).isZero();
                assertThat(chatSplit.getDividerSize()).isEqualTo(MainWindow.COLLAPSED_DIVIDER);
                assertThat(((java.awt.Container) sidebar).getComponents())
                        .as("zugeklappt ist nichts aus der Leiste erreichbar").noneMatch(Component::isVisible);

                window.chatToggle().doClick();
                layoutAll(surface);
                assertThat(window.chatOpen()).isTrue();
                assertThat(window.chatToggle().isSelected()).isTrue();
                assertThat(sidebar.getWidth())
                        .isGreaterThanOrEqualTo(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.MIN_WIDTH);
                assertThat(chatSplit.getLeftComponent().getWidth()).as("die Arbeitsfläche behält ihre Mindestbreite")
                        .isGreaterThanOrEqualTo(320);
                assertThat(chatSplit.getDividerSize())
                        .isEqualTo(com.aresstack.spaceharvester.app.ui.controls.ComicDivider.THICKNESS);
                // Die Leiste bleibt in jedem Bereich stehen.
                int width = sidebar.getWidth();
                for (AppContext.Section section : AppContext.Section.values()) {
                    window.show(section);
                    layoutAll(surface);
                    assertThat(sideDeck.getWidth()).as(section.name()).isEqualTo(width);
                }
                // „Aufträge“ hat die Leiste auf die Aufträge umgeschaltet, die Mitte blieb; der Chat-Knopf holt den
                // Chat zurück, ein zweiter Klick auf den aktiven Knopf klappt zu.
                assertThat(window.jobsOpen()).isTrue();
                assertThat(window.chatOpen()).isFalse();
                assertThat(window.chatToggle().isSelected()).isFalse();
                assertThat(window.current()).isNotEqualTo(AppContext.Section.ACTIVITIES);
                assertThat(WindowSizes.recallInt(MainWindow.SIDE_JOBS_KEY, -1)).isEqualTo(1);
                // Strg+F sucht groß gezeigt in den Aufträgen, sonst (Fokus in der Mitte) in der Mitte.
                assertThat(window.findTarget()).isSameAs(window.findBar());
                window.chatLarge(true);
                assertThat(window.findTarget()).isSameAs(window.activities().findBar());
                window.chatLarge(false);
                window.jobsOpen(false);
                assertThat(window.jobsOpen()).isFalse();
                assertThat(window.findTarget()).isSameAs(window.findBar());
                window.jobsOpen(true);
                window.chatToggle().doClick();
                layoutAll(surface);
                assertThat(window.chatOpen()).isTrue();
                assertThat(window.jobsOpen()).isFalse();
                assertThat(sidebar.getWidth()).isEqualTo(width);
                // Ziehen am Teiler ändert die Breite, sie wird gemerkt und nach Zu/Auf wiederhergestellt.
                chatSplit.setDividerLocation(chatSplit.getDividerLocation() - 60);
                layoutAll(surface);
                assertThat(sidebar.getWidth()).isEqualTo(width + 60);
                assertThat(WindowSizes.recallInt(MainWindow.CHAT_WIDTH_KEY, -1)).isEqualTo(width + 60);
                assertThat(WindowSizes.recallInt(MainWindow.CHAT_OPEN_KEY, -1)).isEqualTo(1);
                ((javax.swing.JMenuItem) ((com.aresstack.spaceharvester.app.ui.content.ChatSidebar) sidebar).moreMenu()
                        .getComponent(0)).doClick(0);
                layoutAll(surface);
                assertThat(window.chatOpen()).isFalse();
                assertThat(window.chatToggle().isSelected()).isFalse();
                assertThat(sidebar.getWidth()).isZero();
                // Strg+J klappt wieder auf, mit der gemerkten Breite.
                surface.getActionMap().get("chat").actionPerformed(null);
                layoutAll(surface);
                assertThat(window.chatOpen()).isTrue();
                assertThat(sidebar.getWidth()).isEqualTo(width + 60);
                assertThat(surface.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_J,
                                java.awt.event.InputEvent.CTRL_DOWN_MASK))).isEqualTo("chat");
                // „Groß anzeigen“: die Leiste nimmt die ganze Arbeitsfläche, der Explorer bleibt; derselbe Knopf und Esc
                // stellen die vorige Breite wieder her.
                com.aresstack.spaceharvester.app.ui.content.ChatSidebar bar =
                        (com.aresstack.spaceharvester.app.ui.content.ChatSidebar) sidebar;
                assertThat(bar.enlargeButton().getToolTipText())
                        .isEqualTo(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.ENLARGE);
                bar.enlargeButton().doClick();
                layoutAll(surface);
                assertThat(window.chatLarge()).isTrue();
                assertThat(chatSplit.getLeftComponent().isVisible()).isFalse();
                assertThat(sidebar.getWidth()).isEqualTo(chatSplit.getWidth());
                assertThat(window.split().isLeftCollapsed()).as("der Explorer bleibt").isFalse();
                assertThat(bar.enlargeButton().getToolTipText())
                        .isEqualTo(com.aresstack.spaceharvester.app.ui.content.ChatSidebar.SHRINK);
                assertThat(WindowSizes.recallInt(MainWindow.CHAT_LARGE_KEY, -1)).isEqualTo(1);
                surface.getActionMap().get("chat-shrink").actionPerformed(null);
                layoutAll(surface);
                assertThat(window.chatLarge()).isFalse();
                assertThat(chatSplit.getLeftComponent().isVisible()).isTrue();
                assertThat(sidebar.getWidth()).isEqualTo(width + 60);
                assertThat(WindowSizes.recallInt(MainWindow.CHAT_WIDTH_KEY, -1)).isEqualTo(width + 60);
                assertThat(surface.getInputMap(javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW).get(
                        javax.swing.KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_ESCAPE, 0)))
                        .as("Esc gilt im ganzen Fenster, auch mit Fokus im Explorer").isEqualTo("chat-shrink");
                // Wer einen Bereich aufruft, sieht ihn: aus groß wird wieder die Leiste. Die Auswahl im Explorer
                // (openSource) lässt sie groß.
                bar.enlargeButton().doClick();
                window.openSource("gibt-es-nicht", "Quelle");
                assertThat(window.chatLarge()).isTrue();
                window.show(AppContext.Section.SETTINGS);
                layoutAll(surface);
                assertThat(window.chatLarge()).isFalse();
                assertThat(chatSplit.getLeftComponent().isVisible()).isTrue();
                assertThat(sidebar.getWidth()).isEqualTo(width + 60);
                // Esc tut in der Leiste nichts; zugeklappt und wieder auf bleibt „groß“ gemerkt.
                surface.getActionMap().get("chat-shrink").actionPerformed(null);
                assertThat(window.chatOpen()).isTrue();
                bar.enlargeButton().doClick();
                window.chatOpen(false);
                layoutAll(surface);
                assertThat(chatSplit.getLeftComponent().isVisible()).as("zugeklappt ist die Arbeitsfläche da").isTrue();
                assertThat(sidebar.getWidth()).isZero();
                window.chatOpen(true);
                layoutAll(surface);
                assertThat(sidebar.getWidth()).isEqualTo(chatSplit.getWidth());
                // Eine Antwort bei offener Leiste ist gelesen; bei zugeklappter trägt der Knopf einen Punkt, bis die
                // Leiste aufgeht.
                javax.swing.Icon plain = window.chatToggle().getIcon();
                window.chatAnswered();
                assertThat(window.chatUnread()).isFalse();
                assertThat(window.chatToggle().getIcon()).isSameAs(plain);
                window.chatOpen(false);
                window.chatAnswered();
                assertThat(window.chatUnread()).isTrue();
                assertThat(window.chatToggle().getIcon()).isNotSameAs(plain);
                assertThat(window.chatToggle().getToolTipText()).isEqualTo(MainWindow.CHAT_UNREAD_TOOLTIP);
                window.chatToggle().doClick();
                assertThat(window.chatUnread()).isFalse();
                assertThat(window.chatToggle().getToolTipText()).isEqualTo(MainWindow.CHAT_TOOLTIP);
            } finally {
                // Der Bedienzustand liegt in den Benutzer-Einstellungen: andere Tests starten wieder mit dem Standard.
                window.chatLarge(false);
                window.chatOpen(false);
                WindowSizes.rememberInt(MainWindow.CHAT_WIDTH_KEY,
                        com.aresstack.spaceharvester.app.ui.content.ChatSidebar.DEFAULT_WIDTH);
            }
        });
    }

    /** Zugeklappt bleibt ein schmaler Teiler: derselbe Doppelklick klappt den Drawer wieder auf. */
    @Test
    void aCollapsedDrawerKeepsAThinDividerToReopenIt() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            window.content(() -> { }, () -> { });
            window.toggleDrawer();
            assertThat(window.split().isLeftCollapsed()).isTrue();
            assertThat(window.split().getDividerSize()).isEqualTo(MainWindow.COLLAPSED_DIVIDER);
            window.toggleDrawer();
            assertThat(window.split().isLeftCollapsed()).isFalse();
            assertThat(window.split().getDividerSize()).isGreaterThan(0);
        });
    }

    @Test
    void theSearchShowsItsStateInTheStatusLineAndHasNoOwnActionRow() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            window.content(() -> { }, () -> { });
            window.show(AppContext.Section.SEARCH);
            SearchView search = window.searchView();
            assertThat(search.actions()).isNull();
            assertThat(SwingUtilities.isDescendingFrom(search.statusInfo(), window.statusLine())).isTrue();
            assertThat(search.statusInfo().getParent().isVisible()).isTrue();
            window.show(AppContext.Section.SETTINGS);
            assertThat(search.statusInfo().getParent().isVisible()).isFalse();
        });
    }

    @Test
    void theSearchTabByHandClearsTheSelectionAndLeavingItSelectsAllSources() throws Exception {
        // Angelo 24.11.2026: Der Suchreiter von Hand hebt die Auswahl auf, die Suche in der Titelleiste nicht; wer die
        // Suche ohne Auswahl verlässt, hat „Wissensquellen“ gewählt.
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        boolean[] global = new boolean[4];
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            window.content(() -> { }, () -> { });
            window.explorer().selectAll();
            window.openSearchTab();
            global[0] = window.explorer().allSelected();
            window.show(AppContext.Section.CONTENT);
            global[1] = window.explorer().allSelected();
            window.search("betrieb");
            global[2] = window.explorer().allSelected();
            window.openSearchTab();
            global[3] = window.explorer().allSelected();
        });
        assertThat(global).containsExactly(false, true, true, false);
    }

    @Test
    void theSearchHeaderReturnsToTheLastOpenedDocumentEvenWithoutAnEarlierPage() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("alpha.md"), "# Alpha\n\nText.\n");
        Files.writeString(files.resolve("beta.md"), "# Beta\n\nText.\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: handbuch
                      type: files
                      title: Handbuch
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        List<String> pages = context.runtime().workspace().liveMetadata("handbuch", space, null, 10).entries().stream()
                .map(entry -> entry.page().pageId()).toList();
        assertThat(pages).hasSize(2);
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            window.openPage("handbuch", pages.get(0));
            window.openSearchTab();
            window.explorer().selectAll();
            AbstractButton back = (AbstractButton) arrows(surface).get(0);
            assertThat(back.isEnabled()).isTrue();
            back.doClick();
            assertThat(window.current()).isEqualTo(AppContext.Section.CONTENT);
            assertThat(arrows(surface)).containsExactly(back);
            window.openPage("handbuch", pages.get(1));
            window.openSearchTab();
            assertThat(back.isEnabled()).isTrue();
            back.doClick();
            assertThat(window.current()).isEqualTo(AppContext.Section.CONTENT);
            assertThat(back.isEnabled()).isTrue();
            back.doClick();
            assertThat(window.current()).isEqualTo(AppContext.Section.CONTENT);
        });
    }

    @Test
    void documentAndSearchHistoryStayInTheirOwnViewHeaders() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            javax.swing.JPanel surface = window.content(() -> { }, () -> { });
            javax.swing.JComponent bar = window.searchView().searchBar();
            window.show(AppContext.Section.CONTENT);
            surface.setSize(1280, 820);
            layout(surface);
            assertThat(arrows(bar.getParent())).isEmpty();
            assertThat(arrows(surface)).hasSize(1);
            java.awt.Component documentBack = arrows(surface).get(0);
            assertThat(SwingUtilities.isDescendingFrom(documentBack,
                    window.deck().getComponent(AppContext.Section.CONTENT.ordinal()))).isTrue();
            assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, documentBack)).isNull();
            java.awt.Container header = documentBack.getParent();
            java.awt.Component breadcrumb = ((java.awt.BorderLayout) header.getLayout())
                    .getLayoutComponent(java.awt.BorderLayout.CENTER);
            assertThat(breadcrumb.getClass().getSimpleName()).isEqualTo("LocationBar");
            assertThat(documentBack.getX() + documentBack.getWidth()).isLessThan(breadcrumb.getX());

            window.show(AppContext.Section.SEARCH);
            for (int width : new int[] {960, 1280, 1920}) {
                surface.setSize(width, 820);
                layout(surface);
                assertThat(arrows(bar.getParent())).isEmpty();
                assertThat(bar.getWidth()).isBetween(SearchBarLayout.MIN_WIDTH, SearchBarLayout.MAX_WIDTH);
                java.awt.Component searchBack = documentBack;
                assertThat(arrows(surface)).containsExactly(documentBack);
                assertThat(window.searchView().backVisit.getParent()).isNull();
                assertThat(searchBack.getParent()).isSameAs(window.searchView().heading);
                assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, searchBack)).isNull();
                java.awt.Component title = ((java.awt.BorderLayout) searchBack.getParent().getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.CENTER);
                assertThat(searchBack.getWidth()).isPositive();
                assertThat(searchBack.getX() + searchBack.getWidth()).isLessThan(title.getX());
            }
            window.show(AppContext.Section.CONTENT);
            layout(surface);
            assertThat(arrows(surface)).containsExactly(documentBack);
            assertThat(documentBack.getParent()).isSameAs(header);
            window.show(AppContext.Section.SETTINGS);
            assertThat(arrows(bar.getParent())).isEmpty();
        });
    }

    /**
     * #515: Ein Suchfeld für das ganze Fenster steht mittig zwischen den Icon-Gruppen der Kopfzeile, ohne sie bei
     * 960, 1280 oder 1920 px zu überdecken; Enter aus jedem Bereich zeigt die Trefferansicht. Wird es eng (800 px oder
     * breitere Schriften), kürzt die Kopfzeile den Titel, nie das Suchfeld und nie die Knöpfe.
     */
    @Test
    void theSearchFieldSitsCenteredInTheHeaderAndEnterOpensTheResults() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            javax.swing.JPanel surface = window.content(() -> { }, () -> { });
            javax.swing.JComponent bar = window.searchView().searchBar();
            java.awt.Container top = bar.getParent().getParent();
            for (int width : new int[] {800, 960, 1280, 1920}) {
                surface.setSize(width, 600);
                layout(surface);
                java.awt.Rectangle field = SwingUtilities.convertRectangle(bar.getParent(), bar.getBounds(), top);
                java.awt.Component west = ((java.awt.BorderLayout) top.getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.WEST);
                java.awt.Component east = ((java.awt.BorderLayout) top.getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.EAST);
                assertThat(field.width).as("Breite bei %d", width).isBetween(SearchBarLayout.MIN_WIDTH,
                        SearchBarLayout.MAX_WIDTH);
                assertThat(field.x).as("links frei bei %d", width).isGreaterThanOrEqualTo(west.getX() + west.getWidth());
                assertThat(field.x + field.width).as("rechts frei bei %d", width).isLessThanOrEqualTo(east.getX());
                java.awt.Component icons = ((java.awt.BorderLayout) ((java.awt.Container) west).getLayout())
                        .getLayoutComponent(java.awt.BorderLayout.EAST);
                assertThat(icons.getX() + icons.getWidth()).as("Knöpfe ganz sichtbar bei %d", width)
                        .isLessThanOrEqualTo(west.getWidth());
                assertThat(icons.getWidth()).as("Knöpfe ungekürzt bei %d", width)
                        .isEqualTo(icons.getPreferredSize().width);
            }
            window.show(AppContext.Section.SETTINGS);
            javax.swing.JTextField field = (javax.swing.JTextField) ((java.awt.BorderLayout) bar.getLayout())
                    .getLayoutComponent(java.awt.BorderLayout.CENTER);
            field.setText("betrieb");
            field.postActionEvent();
            assertThat(window.current()).isEqualTo(AppContext.Section.SEARCH);
            assertThat(window.searchView().queryText()).isEqualTo("betrieb");
            assertThat(SwingUtilities.isDescendingFrom(bar, surface)).isTrue();
            // In der Suche ist die Auswahl links nur der Filter: ein Klick auf eine Seite lässt die Suche stehen.
            window.openFromTree("handbuch", "4711");
            assertThat(window.current()).isEqualTo(AppContext.Section.SEARCH);
        });
    }

    /** Alle sichtbaren Zurück/Vor-Knöpfe im Fenster. */
    private static java.util.List<java.awt.Component> arrows(java.awt.Container container) {
        java.util.List<java.awt.Component> found = new java.util.ArrayList<>();
        for (java.awt.Component child : container.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof com.aresstack.spaceharvester.app.ui.controls.BackButton) {
                found.add(child);
            }
            if (child instanceof java.awt.Container nested) {
                found.addAll(arrows(nested));
            }
        }
        return found;
    }

    private static void layout(java.awt.Container container) {
        container.doLayout();
        for (java.awt.Component child : container.getComponents()) {
            if (child instanceof java.awt.Container nested) {
                layout(nested);
            }
        }
    }

    /**
     * Die Einstellungen haben keine Reiterleiste mehr: Ihre Bereiche stehen untereinander im linken Drawer an Stelle
     * des Explorers, rechts nur der gewählte Bereich; wer die Einstellungen verlässt, sieht wieder den Explorer.
     */
    @Test
    void theSettingsListTheirSectionsInTheDrawerAndLeavingRestoresTheExplorer() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            window.show(AppContext.Section.CONTENT);
            assertThat(window.explorer().component().isVisible()).isTrue();
            SettingsView settings = window.settings();
            assertThat(find(settings.component(), PillTabs.class)).as("keine Reiterleiste mehr").isEmpty();

            window.show(AppContext.Section.SETTINGS);
            assertThat(settings.drawer().getParent()).isSameAs(window.side());
            assertThat(settings.drawer().isVisible()).isTrue();
            assertThat(window.explorer().component().isVisible()).isFalse();
            assertThat(settings.navRows()).extracting(SettingsNavRow::title).containsExactly(SettingsView.GENERAL,
                    SettingsView.AI, SettingsView.MODELS, SettingsView.WORDS, SettingsView.NETWORK, SettingsView.CACHE,
                    SettingsView.VIDEO, SettingsView.TERMINAL, SettingsView.NDV, SettingsView.BETAVIEW,
                    SettingsView.BROWSER, SettingsView.DIAGRAMS, SettingsView.SECURITY, SettingsView.DIAGNOSIS, SettingsView.LIBRARIES);
            layoutAll(surface);
            List<Integer> ys = new ArrayList<>();
            for (SettingsNavRow row : settings.navRows()) {
                ys.add(SwingUtilities.convertPoint(row.getParent(), row.getLocation(), settings.drawer()).y);
            }
            assertThat(ys).as("untereinander").isSorted().doesNotHaveDuplicates();
            // „Bibliotheken“ klebt am Boden des Drawers: unter dem Scrollbereich der Bereiche, über „Zurück“.
            SettingsNavRow libraries = settings.navRows().get(14);
            int librariesY = ys.get(14);
            int backY = SwingUtilities.convertPoint(settings.back.getParent(), settings.back.getLocation(),
                    settings.drawer()).y;
            assertThat(librariesY + libraries.getHeight()).isLessThanOrEqualTo(backY);
            assertThat(backY - librariesY).as("direkt über Zurück").isLessThan(libraries.getHeight() + 24);
            assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, libraries))
                    .as("scrollt nicht mit").isNull();
            assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, settings.navRows().get(0)))
                    .isNotNull();
            assertThat(librariesY).isGreaterThan(ys.get(13) + settings.navRows().get(13).getHeight());
            assertThat(settings.selected()).isEqualTo(SettingsView.GENERAL);

            // Ein Klick auf „Netzwerk“ zeigt rechts nur diesen Bereich und markiert die Zeile.
            SettingsNavRow network = settings.navRows().get(4);
            network.dispatchEvent(new MouseEvent(network, MouseEvent.MOUSE_PRESSED, 0, MouseEvent.BUTTON1_DOWN_MASK,
                    5, 5, 1, false, MouseEvent.BUTTON1));
            assertThat(settings.selected()).isEqualTo(SettingsView.NETWORK);
            assertThat(settings.navRows()).filteredOn(SettingsNavRow::selected).extracting(SettingsNavRow::title)
                    .containsExactly(SettingsView.NETWORK);
            assertThat(visibleTexts(settings.component())).contains("Netz und Zertifikate")
                    .doesNotContain("Konfiguration", "Tresor");

            // „Zurück“ führt zum vorigen Bereich; der Explorer kehrt in den Drawer zurück.
            settings.back.doClick();
            assertThat(window.explorer().component().isVisible()).isTrue();
            assertThat(settings.drawer().isVisible()).isFalse();
            assertThat(window.deck().getComponent(AppContext.Section.CONTENT.ordinal()).isVisible()).isTrue();

            // Ein anderer Bereich der Icon-Leiste verlässt die Einstellungen genauso.
            window.showAiSettings();
            assertThat(settings.drawer().isVisible()).isTrue();
            assertThat(settings.selected()).isEqualTo(SettingsView.AI);
            window.show(AppContext.Section.SEARCH);
            assertThat(window.explorer().component().isVisible()).isTrue();
            assertThat(settings.drawer().isVisible()).isFalse();
        });
    }

    /** Überschriften der sichtbaren Platten (nur Komponenten, die samt Eltern sichtbar sind). */
    private static List<String> visibleTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (!child.isVisible()) {
                continue;
            }
            if (child instanceof com.aresstack.enterpriseai.ui.comic.control.ComicSectionPanel plate
                    && plate.getComponentCount() > 0 && plate.getComponent(0) instanceof JLabel title) {
                texts.add(title.getText());
            }
            if (child instanceof Container nested) {
                texts.addAll(visibleTexts(nested));
            }
        }
        return texts;
    }

    @Test
    void at960PixelsTheSearchKeepsAbbrechenVisibleAndShortensOnlyTheHint() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            window.show(AppContext.Section.SEARCH);
            SearchView search = window.searchView();
            search.progressText.setText("Suche läuft · 3 von 9 Suchanfragen · 12 Treffer");
            search.cancel.setVisible(true);
            String hint = "12 Treffer · semantisch, nach Nähe zur Anfrage · Wiki: nicht erreichbar (Zeitüberschreitung)"
                    + " · unvollständig: nicht jede Quelle oder jeder Treffer ließ sich prüfen";
            search.status.setText(hint);
            surface.setSize(960, 700);
            layoutAll(surface);
            java.awt.Rectangle cancel = SwingUtilities.convertRectangle(search.cancel.getParent(),
                    search.cancel.getBounds(), window.statusLine());
            assertThat(search.cancel.getWidth()).isEqualTo(search.cancel.getPreferredSize().width);
            assertThat(cancel.x).isGreaterThanOrEqualTo(0);
            assertThat(cancel.x + cancel.width).isLessThanOrEqualTo(window.statusLine().getWidth());
            assertThat(search.progressText.getWidth()).isEqualTo(search.progressText.getPreferredSize().width);
            // Nur der lange Hinweis wird gekürzt; ganz steht er im Tooltip.
            assertThat(search.status.getWidth()).isLessThan(search.status.getPreferredSize().width);
            assertThat(search.status.getToolTipText()).isEqualTo(hint);
        });
    }

    private static void layoutAll(Component component) {
        component.doLayout();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                layoutAll(child);
            }
        }
    }

    @Test
    void closeRingsStayRoundAndTheCopyrightOpensTheReleases() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        List<URI> opened = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            JPanel surface = new MainWindow(context).content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            Screenshots.write(surface, home.resolve("window.png"));
            // Nur sichtbare: Ausgeblendete Meldungsplatten (MessagePlate) tragen ihr eigenes ✕.
            assertThat(find(surface, ComicWindowCloseButton.class).stream().filter(MainWindowTest::shown).toList())
                    .as("Hauptfenster: Ring bleibt rund").singleElement().satisfies(MainWindowTest::isSquare);

            JPanel dialog = Dialogs.surface("Wissensquelle hinzufügen", new JPanel(), () -> { });
            dialog.setSize(660, 560);
            Screenshots.write(dialog, home.resolve("dialog.png"));
            assertThat(find(dialog, ComicWindowCloseButton.class)).as("Dialog: eigene Kopfzeile mit rundem ✕")
                    .singleElement().satisfies(MainWindowTest::isSquare);
            assertThat(labelTexts(dialog)).contains("Wissensquelle hinzufügen");
            // ComicWindowShape setzt beim Anzeigen den Rand der Fläche neu; der Innenabstand darf daran nicht hängen.
            dialog.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
            Screenshots.write(dialog, home.resolve("dialog-shaped.png"));
            Component close = find(dialog, ComicWindowCloseButton.class).get(0);
            Point corner = SwingUtilities.convertPoint(close.getParent(), close.getLocation(), dialog);
            assertThat(corner.y).as("Abstand oben").isGreaterThanOrEqualTo(6 + 8);
            assertThat(dialog.getWidth() - corner.x - close.getWidth()).as("Abstand rechts")
                    .isGreaterThanOrEqualTo(6 + 12);

            // Mit Hilfe: das Fragezeichen steht links neben dem ✕, gleich groß und rund; der Text wandert ins Popup.
            com.aresstack.spaceharvester.app.ui.controls.HelpButton help =
                    new com.aresstack.spaceharvester.app.ui.controls.HelpButton(Dialogs.CLOSE_SIZE,
                            SourceForm.HELP_HEADING, SourceForm.HELP_TEXT);
            JPanel helped = Dialogs.surface("Wissensquelle hinzufügen", new JPanel(), () -> { }, help);
            helped.setSize(660, 560);
            helped.doLayout();
            layoutAll(helped);
            Screenshots.write(helped, home.resolve("dialog-help.png"));
            Component closeChip = find(helped, ComicWindowCloseButton.class).get(0);
            Point helpAt = SwingUtilities.convertPoint(help.getParent(), help.getLocation(), helped);
            Point closeAt = SwingUtilities.convertPoint(closeChip.getParent(), closeChip.getLocation(), helped);
            assertThat(helpAt.x).isLessThan(closeAt.x);
            assertThat(helpAt.y).isEqualTo(closeAt.y);
            assertThat(help.getWidth()).isEqualTo(closeChip.getWidth());
            assertThat(help.popup().bodyText()).contains("ändert nur die Konfigurationsdatei",
                    "keinen Vorrang zwischen Datei und Dialog", "SPACE_HARVESTER_APP_CONFIG");
            assertThat(labelTexts(surface)).as("Hinweis unter „Quelle hinzufügen …“ entfällt")
                    .noneMatch(text -> text.contains("Quellen stehen in der Konfigurationsdatei"));

            CopyrightLink copyright = new CopyrightLink(opened::add);
            assertThat(find(surface, CopyrightLink.class)).hasSize(1);
            JLabel link = copyright.link();
            assertThat(link.getToolTipText()).isEqualTo("https://github.com/aresstack/space-harvester/releases");
            assertThat(link.getCursor().getType()).isEqualTo(Cursor.HAND_CURSOR);
            Color grey = link.getForeground();
            link.dispatchEvent(new MouseEvent(link, MouseEvent.MOUSE_ENTERED, 0, 0, 1, 1, 0, false));
            assertThat(link.getForeground()).isEqualTo(Comic.BLUE);
            assertThat(link.getFont().getAttributes().get(TextAttribute.UNDERLINE))
                    .isEqualTo(TextAttribute.UNDERLINE_ON);
            link.dispatchEvent(new MouseEvent(link, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            link.dispatchEvent(new MouseEvent(link, MouseEvent.MOUSE_EXITED, 0, 0, 1, 1, 0, false));
            assertThat(link.getForeground()).isEqualTo(grey);
        });
        assertThat(opened).containsExactly(URI.create("https://github.com/aresstack/space-harvester/releases"));
    }

    private static void awaitRows(javax.swing.JTree tree, int rows) throws Exception {
        for (int i = 0; i < 200; i++) {
            boolean[] ready = new boolean[1];
            SwingUtilities.invokeAndWait(() -> ready[0] = tree.getRowCount() >= rows
                    && !Explorer.LOADING.equals(tree.getPathForRow(rows - 1).getLastPathComponent().toString()));
            if (ready[0]) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("Explorer lädt nicht: " + tree.getRowCount() + " Zeilen");
    }

    private static void isSquare(Component button) {
        assertThat(button.getWidth()).isEqualTo(24);
        assertThat(button.getHeight()).isEqualTo(24);
    }

    /** Ob die Komponente und alle ihre Eltern sichtbar sind (ohne angezeigtes Fenster). */
    private static boolean shown(Component component) {
        for (Component c = component; c != null; c = c.getParent()) {
            if (!c.isVisible()) {
                return false;
            }
        }
        return true;
    }

    private static <T> List<T> find(Container container, Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (type.isInstance(child)) {
                found.add(type.cast(child));
            }
            if (child instanceof Container nested) {
                found.addAll(find(nested, type));
            }
        }
        return found;
    }

    private static List<String> labelTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof javax.swing.JLabel label && label.getText() != null) {
                texts.add(label.getText());
            }
            if (child instanceof Container nested) {
                texts.addAll(labelTexts(nested));
            }
        }
        return texts;
    }

    private static List<String> buttonTexts(Container container) {
        List<String> texts = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton button && button.getText() != null) {
                texts.add(button.getText());
            }
            if (child instanceof Container nested) {
                texts.addAll(buttonTexts(nested));
            }
        }
        return texts;
    }
}
