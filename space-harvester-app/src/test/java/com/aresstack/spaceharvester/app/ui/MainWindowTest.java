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
        assertThat(SwingUtilities.isDescendingFrom(window[0].findBar(), window[0].deck().getParent())).isTrue();
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
        // und die Reiter der Seitenansicht, rechts Begriffe und Aufträge, dann Verwaltung und Einstellungen (Angelo,
        // 13.11.2026); Inhalte hat keinen eigenen Knopf mehr.
        assertThat(find(surface[0], AbstractButton.class).stream()
                .map(button -> button.getAccessibleContext().getAccessibleName()).toList())
                .containsSubsequence("Suche", "Leseansicht", "Text", "Eigenschaften", "Anhänge", "Analysen",
                        "Erweitert", "Begriffe", "Aufträge", "Wissensquellen verwalten", "Einstellungen")
                .doesNotContain("Inhalte")
                .as("Herunterladen geht über den Explorer, kein eigener Bereich").doesNotContain("Herunterladen")
                .as("Status ist kein Bereich mehr, sondern Einstellungen › Diagnose").doesNotContain("Status");
        assertThat(find(surface[0], AbstractButton.class).stream().map(AbstractButton::getToolTipText).toList())
                .contains("Suche (Strg+1)", "Begriffe (Strg+3)", "Aufträge (Strg+4)",
                        "Wissensquellen verwalten (Strg+5)", "Einstellungen (Strg+6)")
                .doesNotContain("Inhalte (Strg+2)");
        // Ein Reiter in der Leiste schaltet die Mitte auf Inhalte; in anderen Bereichen zeigt keiner der Reiter eine
        // Auswahl, zurück unter Inhalte ist der gewählte Reiter wieder dunkel.
        List<AbstractButton> tabs = find(surface[0], AbstractButton.class).stream()
                .filter(button -> "Text".equals(button.getAccessibleContext().getAccessibleName())
                        || "Leseansicht".equals(button.getAccessibleContext().getAccessibleName())).toList();
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.SEARCH));
        assertThat(tabs).noneMatch(AbstractButton::isSelected);
        SwingUtilities.invokeAndWait(() -> tabs.stream().filter(b -> "Text".equals(
                b.getAccessibleContext().getAccessibleName())).findFirst().orElseThrow().doClick());
        assertThat(window[0].current()).isEqualTo(AppContext.Section.CONTENT);
        assertThat(tabs).filteredOn(AbstractButton::isSelected)
                .extracting(b -> b.getAccessibleContext().getAccessibleName()).containsExactly("Text");
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.TERMS));
        assertThat(tabs).noneMatch(AbstractButton::isSelected);
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.CONTENT));
        assertThat(tabs).filteredOn(AbstractButton::isSelected)
                .extracting(b -> b.getAccessibleContext().getAccessibleName()).containsExactly("Text");
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

        // Rechtsbündig am Space das Download-Symbol: ein Klick startet den Auftrag sofort, ohne Dialog.
        List<String> jobsBefore = context.runtime().workspace().jobs().stream().map(JobStatus::id).toList();
        SwingUtilities.invokeAndWait(() -> {
            window[0].explorer().filter().setText("");
            java.awt.Rectangle row = tree.getRowBounds(1);
            java.awt.Point icon = new java.awt.Point(row.x + row.width - 10, row.y + row.height / 2);
            for (java.awt.event.MouseListener listener : tree.getMouseListeners()) {
                listener.mouseClicked(new MouseEvent(tree, MouseEvent.MOUSE_CLICKED, 0, 0, icon.x, icon.y, 1, false,
                        MouseEvent.BUTTON1));
            }
        });
        // Der Export plant nach dem Veröffentlichen selbst einen Folgeauftrag ein (Bilder); gezählt wird, was der Klick
        // startet.
        List<JobStatus> clicked = List.of();
        for (int i = 0; i < 100 && clicked.isEmpty(); i++) {
            Thread.sleep(50);
            clicked = context.runtime().workspace().jobs().stream().filter(j -> !jobsBefore.contains(j.id()))
                    .filter(j -> !j.type().followUp()).toList();
        }
        assertThat(clicked).as("vom Klick gestartete Aufträge").singleElement()
                .extracting(JobStatus::type).isEqualTo(com.aresstack.spaceharvester.application.jobs.JobType.EXPORT);
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
        });
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
                    SettingsView.AI, SettingsView.MODELS, SettingsView.NETWORK, SettingsView.CACHE,
                    SettingsView.SECURITY, SettingsView.DIAGNOSIS, SettingsView.LIBRARIES);
            layoutAll(surface);
            List<Integer> ys = new ArrayList<>();
            for (SettingsNavRow row : settings.navRows()) {
                ys.add(SwingUtilities.convertPoint(row.getParent(), row.getLocation(), settings.drawer()).y);
            }
            assertThat(ys).as("untereinander").isSorted().doesNotHaveDuplicates();
            // „Bibliotheken“ klebt am Boden des Drawers: unter dem Scrollbereich der Bereiche, über „Zurück“.
            SettingsNavRow libraries = settings.navRows().get(7);
            int librariesY = ys.get(7);
            int backY = SwingUtilities.convertPoint(settings.back.getParent(), settings.back.getLocation(),
                    settings.drawer()).y;
            assertThat(librariesY + libraries.getHeight()).isLessThanOrEqualTo(backY);
            assertThat(backY - librariesY).as("direkt über Zurück").isLessThan(libraries.getHeight() + 24);
            assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, libraries))
                    .as("scrollt nicht mit").isNull();
            assertThat(SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, settings.navRows().get(0)))
                    .isNotNull();
            assertThat(librariesY).isGreaterThan(ys.get(6) + settings.navRows().get(6).getHeight());
            assertThat(settings.selected()).isEqualTo(SettingsView.GENERAL);

            // Ein Klick auf „Netzwerk“ zeigt rechts nur diesen Bereich und markiert die Zeile.
            SettingsNavRow network = settings.navRows().get(3);
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
            assertThat(find(surface, ComicWindowCloseButton.class)).as("Hauptfenster: Ring bleibt rund")
                    .singleElement().satisfies(MainWindowTest::isSquare);

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
