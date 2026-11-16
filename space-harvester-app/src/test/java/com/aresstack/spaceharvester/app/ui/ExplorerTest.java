package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import com.aresstack.spaceharvester.application.sources.HarvestSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Der Titelfilter findet auch Seiten, die weder aufgeklappt noch im Bestand sind: die Quelle sucht selbst. */
class ExplorerTest {

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
    void theTitleFilterAsksTheSourceForPagesNeverLoaded() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("wartungsfenster.md"), "# Wartungsfenster\n\nText.\n");
        Files.writeString(files.resolve("anderes.md"), "# Anderes\n");
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

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].filter().setText("wartung");
        });
        for (int i = 0; i < 200; i++) {
            int[] size = new int[1];
            SwingUtilities.invokeAndWait(() -> size[0] = explorer[0].matches().getModel().getSize());
            if (size[0] > 0) {
                break;
            }
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            assertThat(explorer[0].matches().getModel().getSize()).isEqualTo(1);
            assertThat(explorer[0].matches().getModel().getElementAt(0).title()).containsIgnoringCase("wartung");
        });
    }

    /** Ein geöffneter Treffer: der Explorer klappt Quelle und Space auf und wählt die Seite, ohne den Suchumfang. */
    @Test
    void revealPageExpandsTheTreeAndSelectsThePage() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.createDirectories(files.resolve("betrieb"));
        Files.writeString(files.resolve("betrieb").resolve("wartungsfenster.md"), "# Wartungsfenster\n\nText.\n");
        Files.writeString(files.resolve("anderes.md"), "# Anderes\n");
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

        // Ordnerquellen zeigen ihre Seiten flach unter dem Space; ein Vorfahr, den der Baum nicht als Wurzel zeigt,
        // hindert nicht daran, die Seite direkt unter dem Space zu finden.
        Explorer.Batch spaces = Explorer.children(context.runtime(), new Explorer.SourceItem("handbuch", "Handbuch"),
                null);
        Explorer.SpaceItem space = (Explorer.SpaceItem) spaces.nodes().get(0).item();
        Explorer.PageItem target = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .filter(page -> page.title().toLowerCase(java.util.Locale.ROOT).contains("wartung"))
                .findFirst().orElseThrow();
        PageNavigation.Position position = new PageNavigation.Position("handbuch", space.spaceKey(), space.name(),
                List.of(new HarvestSource.PageNode("unbekannt", "Ordner", space.spaceKey(), true)),
                new HarvestSource.PageNode(target.pageId(), target.title(), space.spaceKey(), false));

        Explorer[] explorer = new Explorer[1];
        int[] scopeCalls = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].onScope(scopes -> scopeCalls[0]++);
            explorer[0].revealPage(position);
        });
        String wanted = target.pageId();
        Object[] selected = new Object[1];
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.tree.TreePath path = explorer[0].tree().getSelectionPath();
                selected[0] = path == null ? null : ((Explorer.Node) path.getLastPathComponent()).item();
            });
            if (selected[0] instanceof Explorer.PageItem page && page.pageId().equals(wanted)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(selected[0]).isInstanceOf(Explorer.PageItem.class);
        assertThat(((Explorer.PageItem) selected[0]).pageId()).isEqualTo(wanted);
        assertThat(scopeCalls[0]).isZero();

        // Erst Treffer A (Pfad wird im Hintergrund geholt), dann B: die spätere Antwort für A überschreibt B nicht.
        Explorer.PageItem other = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .filter(page -> !page.pageId().equals(wanted)).findFirst().orElseThrow();
        SwingUtilities.invokeAndWait(() -> {
            explorer[0].revealPage("handbuch", other.pageId());
            explorer[0].revealPage(position);
        });
        Thread.sleep(1500);
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.tree.TreePath path = explorer[0].tree().getSelectionPath();
            selected[0] = path == null ? null : ((Explorer.Node) path.getLastPathComponent()).item();
        });
        assertThat(((Explorer.PageItem) selected[0]).pageId()).isEqualTo(wanted);

        // Ein Klick auf die Seite wählt nur, erst der Doppelklick öffnet sie.
        List<String> opened = new java.util.ArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("openPage")) {
                        opened.add((String) args[1]);
                    }
                    return null;
                }));
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTree tree = explorer[0].tree();
            tree.setSize(400, 400);
            java.awt.Rectangle row = tree.getPathBounds(tree.getSelectionPath());
            for (int clicks = 1; clicks <= 2; clicks++) {
                tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                        System.currentTimeMillis(), 0, row.x + 5, row.y + row.height / 2, clicks, false,
                        java.awt.event.MouseEvent.BUTTON1));
                if (clicks == 1) {
                    assertThat(opened).as("ein Klick öffnet nicht").isEmpty();
                }
            }
        });
        assertThat(opened).containsExactly(wanted);

        // Fußleiste: keine Anzahl der Wissensquellen; „Alles zuklappen“ und das Fadenkreuz zeigen die gezeigte Seite.
        SwingUtilities.invokeAndWait(() -> {
            assertThat(explorer[0].note()).isBlank();
            assertThat(explorer[0].collapseAllButton().getToolTipText()).isEqualTo("Alles zuklappen");
            explorer[0].collapseAllButton().doClick();
            assertThat(explorer[0].tree().getRowCount()).isEqualTo(1);
            explorer[0].currentPage(position);
            explorer[0].showCurrentButton().doClick();
        });
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.tree.TreePath path = explorer[0].tree().getSelectionPath();
                selected[0] = path == null ? null : ((Explorer.Node) path.getLastPathComponent()).item();
            });
            if (selected[0] instanceof Explorer.PageItem page && page.pageId().equals(wanted)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(((Explorer.PageItem) selected[0]).pageId()).isEqualTo(wanted);
        assertThat(opened).as("das Fadenkreuz öffnet nicht").containsExactly(wanted);

        // Fadenkreuz bei gewählter Seite: das Zuklappen verbreitert den Suchumfang nicht auf die ganze Quelle.
        int before = scopeCalls[0];
        SwingUtilities.invokeAndWait(() -> explorer[0].showCurrentButton().doClick());
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.tree.TreePath path = explorer[0].tree().getSelectionPath();
                selected[0] = path == null ? null : ((Explorer.Node) path.getLastPathComponent()).item();
            });
            if (selected[0] instanceof Explorer.PageItem page && page.pageId().equals(wanted)) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(((Explorer.PageItem) selected[0]).pageId()).isEqualTo(wanted);
        assertThat(scopeCalls[0]).as("Suchumfang bleibt").isEqualTo(before);

        // Ohne gezeigte Seite (etwa eine Quelle ohne Seitenbaum) hat das Fadenkreuz kein altes Ziel.
        SwingUtilities.invokeAndWait(() -> {
            explorer[0].currentPage(null);
            assertThat(explorer[0].showCurrentButton().isEnabled()).isFalse();
        });
    }

    /**
     * Lange Titel schieben die Spalten rechts nicht aus dem sichtbaren Bereich: jede Zeile endet am rechten Rand des
     * Ausschnitts, Bestandszeichen, Stern und Download-Symbol stehen in festen Spalten, der Titel wird mit „…“
     * gekürzt (Angelo, 16.11.2026).
     */
    @Test
    void longTitlesAreCutBeforeTheColumnsOnTheRight() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        String title = "Programm Modernisierung der Haushaltsaufstellung und des Haushaltsvollzugs samt Rechnungslegung";
        Files.writeString(files.resolve(title + ".md"), "# " + title + "\n\nText.\n");
        Files.writeString(files.resolve("kurz.md"), "# Kurz\n");
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

        Explorer.Batch spaces = Explorer.children(context.runtime(), new Explorer.SourceItem("handbuch", "Handbuch"),
                null);
        Explorer.SpaceItem space = (Explorer.SpaceItem) spaces.nodes().get(0).item();
        Explorer.PageItem target = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .filter(page -> page.title().contains("Modernisierung")).findFirst().orElseThrow();
        PageNavigation.Position position = new PageNavigation.Position("handbuch", space.spaceKey(), space.name(),
                List.of(), new HarvestSource.PageNode(target.pageId(), target.title(), space.spaceKey(), false));

        int visible = 260;
        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].tree().getParent().setSize(visible, 400);
            explorer[0].remeasure();
            explorer[0].revealPage(position);
        });
        Object[] selected = new Object[1];
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.tree.TreePath path = explorer[0].tree().getSelectionPath();
                selected[0] = path == null ? null : ((Explorer.Node) path.getLastPathComponent()).item();
            });
            if (selected[0] instanceof Explorer.PageItem page && page.pageId().equals(target.pageId())) {
                break;
            }
            Thread.sleep(50);
        }
        assertThat(selected[0]).isInstanceOf(Explorer.PageItem.class);

        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTree tree = explorer[0].tree();
            assertThat(tree.getRowCount()).isGreaterThanOrEqualTo(3);
            for (int row = 0; row < tree.getRowCount(); row++) {
                java.awt.Rectangle bounds = tree.getRowBounds(row);
                assertThat(bounds.x + bounds.width).as("Zeile %d endet im sichtbaren Bereich", row)
                        .isLessThanOrEqualTo(visible);
            }
            assertThat(tree.getPreferredSize().width).as("keine waagerechte Scrollleiste").isLessThanOrEqualTo(visible);
            // Die lange Zeile: Label mit rechtem Rand für die drei Spalten, Titel breiter als der Platz davor.
            javax.swing.tree.TreePath path = tree.getSelectionPath();
            javax.swing.JLabel label = (javax.swing.JLabel) tree.getCellRenderer().getTreeCellRendererComponent(tree,
                    path.getLastPathComponent(), true, false, true, tree.getRowForPath(path), false);
            java.awt.Rectangle bounds = tree.getPathBounds(path);
            assertThat(label.getInsets().right).isEqualTo(30 + 30 + 22);
            label.setSize(bounds.width, bounds.height);
            int available = bounds.width - label.getInsets().right - label.getIcon().getIconWidth()
                    - label.getIconTextGap();
            assertThat(label.getFontMetrics(label.getFont()).stringWidth(label.getText())).isGreaterThan(available);
        });
    }

    /**
     * Die Überschrift „Wissensquellen“ (oder der Knopf daneben) wählt alle Quellen (Angelo, 16.11.2026): der Kopf ist
     * gelb hinterlegt, die Auswahl im Baum verschwindet, Umfang = alles, Chat und Export erhalten die globale Auswahl.
     * Ein Knoten im Baum löst sie wieder ab.
     */
    @Test
    void theHeadingSelectsAllSourcesAndClearsTheTree() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("kurz.md"), "# Kurz\n");
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

        Explorer[] explorer = new Explorer[1];
        List<com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection> selections =
                new java.util.ArrayList<>();
        List<List<Explorer.Scope>> scopes = new java.util.ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].onSelection(selections::add);
            explorer[0].onScope(scopes::add);
        });
        for (int i = 0; i < 200; i++) {
            int[] rows = new int[1];
            SwingUtilities.invokeAndWait(() -> rows[0] = explorer[0].tree().getRowCount());
            if (rows[0] > 0) {
                break;
            }
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTree tree = explorer[0].tree();
            tree.setSelectionRow(0);
            assertThat(explorer[0].allSelected()).isFalse();
            assertThat(selections.get(selections.size() - 1).sourceId()).isEqualTo("handbuch");
            assertThat(explorer[0].head().isOpaque()).isFalse();

            // Klick auf die Überschrift: alles gewählt, Baum leer, Kopf gelb.
            javax.swing.JLabel heading = explorer[0].heading();
            heading.setSize(120, 20);
            heading.dispatchEvent(new java.awt.event.MouseEvent(heading, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, 5, 5, 1, false, java.awt.event.MouseEvent.BUTTON1));
            assertThat(explorer[0].allSelected()).isTrue();
            assertThat(tree.getSelectionCount()).isZero();
            assertThat(selections.get(selections.size() - 1))
                    .isSameAs(com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection.ALL);
            assertThat(selections.get(selections.size() - 1).global()).isTrue();
            assertThat(scopes.get(scopes.size() - 1)).isEmpty();
            assertThat(explorer[0].head().isOpaque()).isTrue();
            assertThat(explorer[0].head().getBackground()).isEqualTo(Comic.YELLOW);
            assertThat(explorer[0].selectAllButton().getToolTipText()).isEqualTo(Explorer.ALL_TIP);

            // Ein Knoten im Baum löst die globale Auswahl ab.
            tree.setSelectionRow(0);
            assertThat(explorer[0].allSelected()).isFalse();
            assertThat(selections.get(selections.size() - 1).sourceId()).isEqualTo("handbuch");
            assertThat(explorer[0].head().isOpaque()).isFalse();

            // Der Knopf tut dasselbe wie die Überschrift; ohne Baumauswahl meldet er die Auswahl selbst.
            tree.clearSelection();
            int before = selections.size();
            explorer[0].selectAllButton().doClick();
            assertThat(explorer[0].allSelected()).isTrue();
            assertThat(selections).hasSize(before + 1);
            assertThat(selections.get(before).global()).isTrue();
            explorer[0].clearSelection();
            assertThat(explorer[0].allSelected()).isFalse();
            assertThat(selections.get(selections.size() - 1)).isNull();
        });

        // Der Download-Knopf daneben lädt jede Quelle mit allen Spaces (je Space ein Auftrag, wie einzeln).
        List<String> downloaded = new java.util.ArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("download") && args[1] instanceof List<?> keys) {
                        downloaded.add(args[0] + ":" + keys);
                    }
                    return null;
                }));
        List<Boolean> answers = new java.util.ArrayList<>(List.of(false, true));
        SwingUtilities.invokeAndWait(() -> {
            assertThat(explorer[0].downloadAllButton().getToolTipText()).isEqualTo(Explorer.DOWNLOAD_ALL_TIP);
            // Erst die Rückfrage (Ja/Nein, „wirklich“): bei Nein passiert nichts, bei Ja lädt jede Quelle.
            explorer[0].confirmDownloadAll(parent -> answers.remove(0));
            explorer[0].downloadAllButton().doClick();
            assertThat(downloaded).isEmpty();
            explorer[0].downloadAllButton().doClick();
        });
        assertThat(answers).isEmpty();
        assertThat(Explorer.DOWNLOAD_ALL_QUESTION).containsIgnoringCase("wirklich");
        assertThat(downloaded).containsExactly("handbuch:[]");
    }

    /** Die Hervorhebung des Sterns folgt der Maus über dem Stern, nicht über dem Download-Symbol (Angelo, 16.11.2026). */
    @Test
    void theStarHighlightsOnlyUnderTheMouse() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("kurz.md"), "# Kurz\n");
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
        Explorer.Batch spaces = Explorer.children(context.runtime(), new Explorer.SourceItem("handbuch", "Handbuch"),
                null);
        Explorer.SpaceItem space = (Explorer.SpaceItem) spaces.nodes().get(0).item();
        Explorer.PageItem page = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .findFirst().orElseThrow();
        PageNavigation.Position position = new PageNavigation.Position("handbuch", space.spaceKey(), space.name(),
                List.of(), new HarvestSource.PageNode(page.pageId(), page.title(), space.spaceKey(), false));
        // Die Kreispfeile am Zustandspunkt rufen das Nachholen des Knotens auf (Angelo, 16.11.2026).
        List<String> refreshed = new java.util.concurrent.CopyOnWriteArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("refresh")) {
                        refreshed.add(args[0] + "/" + args[1] + "/" + args[2]);
                    }
                    return null;
                }));
        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].tree().getParent().setSize(320, 400);
            explorer[0].remeasure();
            explorer[0].revealPage(position);
        });
        for (int i = 0; i < 200; i++) {
            boolean[] done = new boolean[1];
            SwingUtilities.invokeAndWait(() -> done[0] = explorer[0].tree().getSelectionPath() != null
                    && ((Explorer.Node) explorer[0].tree().getSelectionPath().getLastPathComponent()).item()
                    instanceof Explorer.PageItem);
            if (done[0]) {
                break;
            }
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTree tree = explorer[0].tree();
            tree.setSize(320, 400);
            javax.swing.tree.TreePath path = tree.getSelectionPath();
            int row = tree.getRowForPath(path);
            java.awt.Rectangle bounds = tree.getPathBounds(path);
            // Spalten von rechts: Download (30), Stern (30), Bestandszeichen (22).
            int y = bounds.y + bounds.height / 2;
            move(tree, bounds.x + bounds.width - 15, y);
            assertThat(explorer[0].hoverRow()).isEqualTo(row);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(-1);
            assertThat(tree.getToolTipText()).isEqualTo(Explorer.DOWNLOAD_TIP);
            move(tree, bounds.x + bounds.width - 45, y);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(row);
            assertThat(explorer[0].hoverRow()).isEqualTo(-1);
            assertThat(tree.getToolTipText()).isEqualTo(Explorer.STAR_TIP);
            move(tree, bounds.x + 5, y);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(-1);
            assertThat(explorer[0].hoverRow()).isEqualTo(-1);
            // Der Zustandspunkt links vom Stern: Kreispfeile unter der Maus, Klick holt die Seite samt Teilbaum nach.
            move(tree, bounds.x + bounds.width - 60 - 11, y);
            assertThat(explorer[0].hoverBadgeRow()).isEqualTo(row);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(-1);
            assertThat(tree.getToolTipText()).endsWith(Explorer.REFRESH_TIP);
            click(tree, bounds.x + bounds.width - 60 - 11, y);
            assertThat(refreshed).containsExactly("handbuch/" + space.spaceKey() + "/" + page.pageId());
            // Die Wurzel der Quelle (Zeile 0) trägt dieselben Spalten: Zustandspunkt, Stern, Download.
            java.awt.Rectangle top = tree.getRowBounds(0);
            assertThat(((Explorer.Node) tree.getPathForRow(0).getLastPathComponent()).item())
                    .isInstanceOf(Explorer.SourceItem.class);
            int ty = top.y + top.height / 2;
            move(tree, top.x + top.width - 60 - 11, ty);
            assertThat(explorer[0].hoverBadgeRow()).isZero();
            click(tree, top.x + top.width - 60 - 11, ty);
            assertThat(refreshed).hasSize(2);
            assertThat(refreshed.get(1)).isEqualTo("handbuch/null/null");
            // Der Stern an der Quelle merkt die ganze Quelle als Favorit (Angelo, 16.11.2026).
            move(tree, top.x + top.width - 45, ty);
            assertThat(explorer[0].hoverStarRow()).isZero();
            click(tree, top.x + top.width - 45, ty);
            assertThat(context.favorites().contains("handbuch", null, null)).isTrue();
            assertThat(context.favorites().all().get(0).isSource()).isTrue();
            assertThat(context.favorites().all().get(0).title()).isEqualTo("Handbuch");
            // Alle Quellen: erst die Rückfrage, „Nein“ startet nichts, „Ja“ je Quelle einmal.
            explorer[0].confirmRefreshAll(parent -> false);
            explorer[0].refreshAll();
            assertThat(refreshed).hasSize(2);
            explorer[0].confirmRefreshAll(parent -> true);
            explorer[0].refreshAll();
            assertThat(refreshed).hasSize(3);
            assertThat(refreshed.get(2)).isEqualTo("handbuch/null/null");
            assertThat(Explorer.REFRESH_ALL_QUESTION).containsIgnoringCase("wirklich");
        });
    }

    /** Nur das Klick-Ereignis (ohne Drücken/Loslassen): die Baum-Auswahl bräuchte headless das Toolkit. */
    private static void click(javax.swing.JTree tree, int x, int y) {
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                System.currentTimeMillis(), 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1));
    }

    private static void move(javax.swing.JTree tree, int x, int y) {
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, x, y, 0, false));
    }
}
