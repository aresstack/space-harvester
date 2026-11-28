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
    void selectingHiddenSourceKeepsTheExplicitSearchAndChatScope() throws Exception {
        Path visible = Files.createDirectories(home.resolve("visible"));
        Path hidden = Files.createDirectories(home.resolve("hidden"));
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: visible
                      type: files
                      options:
                        directory: %s
                    - id: hidden
                      type: files
                      show-in-explorer: false
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), visible, hidden));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            var scopes = new java.util.concurrent.atomic.AtomicReference<List<Explorer.Scope>>();
            var selection = new java.util.concurrent.atomic.AtomicReference<
                    com.aresstack.spaceharvester.app.ui.content.ExportPanel.Selection>();
            explorer.onScope(scopes::set);
            explorer.onSelection(selection::set);
            explorer.select(List.of(new Explorer.Scope("visible", null)));
            var wanted = List.of(new Explorer.Scope("hidden", null));
            explorer.select(wanted);
            assertThat(explorer.tree().getSelectionCount()).isZero();
            assertThat(explorer.scopes()).isEqualTo(wanted);
            assertThat(scopes.get()).isEqualTo(wanted);
            assertThat(selection.get().sourceId()).isEqualTo("hidden");
            explorer.selectAll();
            assertThat(explorer.scopes()).isEmpty();
            assertThat(selection.get().global()).isTrue();
        });
    }

    @Test
    void nodeKindControlsIconsWithoutInspectingTheSourceType() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            javax.swing.JTree tree = explorer.tree();
            javax.swing.tree.TreeCellRenderer renderer = tree.getCellRenderer();

            Explorer.PageItem directory = Explorer.PageItem.of("source", "space", "folder", "Ordner",
                    true, HarvestSource.PageNode.Kind.FOLDER);
            java.awt.Component folderComponent = renderer.getTreeCellRendererComponent(tree,
                    new Explorer.Node(directory), false, false, false, 0, false);
            Class<?> folderIcon = ((javax.swing.JLabel) folderComponent).getIcon().getClass();

            Explorer.PageItem page = Explorer.PageItem.of("source", "space", "page", "Seite",
                    true, HarvestSource.PageNode.Kind.PAGE);
            java.awt.Component pageComponent = renderer.getTreeCellRendererComponent(tree,
                    new Explorer.Node(page), false, false, false, 0, false);
            Class<?> pageIcon = ((javax.swing.JLabel) pageComponent).getIcon().getClass();

            Explorer.PageItem file = Explorer.PageItem.of("source", "space", "file", "Datei",
                    false, HarvestSource.PageNode.Kind.FILE);
            java.awt.Component fileComponent = renderer.getTreeCellRendererComponent(tree,
                    new Explorer.Node(file), false, false, true, 0, false);
            Class<?> fileIcon = ((javax.swing.JLabel) fileComponent).getIcon().getClass();

            assertThat(folderIcon).isEqualTo(
                    com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons.folder().getClass());
            assertThat(pageIcon).isEqualTo(
                    com.aresstack.enterpriseai.ui.comic.paint.SidebarIcons.page().getClass());
            assertThat(fileIcon).isEqualTo(pageIcon);
            assertThat(folderIcon).isNotEqualTo(pageIcon);
        });
    }

    /**
     * Regression (Angelo 26.11.2026): Ein Klick auf einen Ordner (SharePoint-Ordner sind Gruppen, BSCW- und lokale
     * Ordner Ordner) klappte nur auf und meldete nichts, also blieb die vorher geöffnete Seite stehen. Jeder Knoten ohne
     * eigenen Inhalt meldet sich jetzt als Ordner, jeder mit Inhalt öffnet wie bisher seine Seite.
     */
    @Test
    void aClickOnAFolderOpensTheFolderSoThePreviousPageNeverStays() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        List<String> containers = new java.util.ArrayList<>();
        List<String> pages = new java.util.ArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("openContainerFromTree")) {
                        containers.add(args[2] + "=" + args[3]);
                    } else if (method.getName().equals("openFromTree")) {
                        pages.add((String) args[1]);
                    }
                    return null;
                }));
        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            javax.swing.tree.DefaultTreeModel model = (javax.swing.tree.DefaultTreeModel) explorer.tree().getModel();
            javax.swing.tree.DefaultMutableTreeNode root = (javax.swing.tree.DefaultMutableTreeNode) model.getRoot();
            for (HarvestSource.PageNode.Kind kind : HarvestSource.PageNode.Kind.values()) {
                Explorer.Node node = new Explorer.Node(Explorer.PageItem.of("source", "space", kind.name(),
                        "Knoten " + kind, kind != HarvestSource.PageNode.Kind.FILE, kind));
                root.add(node);
                explorer.activate(new javax.swing.tree.TreePath(node.getPath()));
            }
        });
        assertThat(containers).containsExactly("FOLDER=Knoten FOLDER", "GROUP=Knoten GROUP");
        assertThat(pages).containsExactly("PAGE", "FILE");
    }

    @Test
    void confirmedFileReplacesAnUnknownCachedNodeWithoutAnExpandHandle() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            Explorer.Node root = (Explorer.Node) explorer.tree().getModel().getRoot();
            Explorer.Node parent = new Explorer.Node(new Explorer.SpaceItem("source", "space", "Space"));
            Explorer.Node cached = new Explorer.Node(Explorer.PageItem.of("source", "space", "doc",
                    "Dokument.pdf", null, HarvestSource.PageNode.Kind.PAGE));
            cached.add(new Explorer.Node(new Explorer.Note("Noch nicht geladen")));
            parent.add(cached);
            root.add(parent);
            ((javax.swing.tree.DefaultTreeModel) explorer.tree().getModel()).reload();

            Explorer.Node confirmed = new Explorer.Node(Explorer.PageItem.of("source", "space", "doc",
                    "Dokument.pdf", false, HarvestSource.PageNode.Kind.FILE));
            confirmed.loaded = true;
            explorer.merge(parent, new Explorer.Batch(List.of(confirmed), null), true);

            assertThat(cached.item()).isInstanceOf(Explorer.PageItem.class);
            assertThat(((Explorer.PageItem) cached.item()).kind()).isEqualTo(HarvestSource.PageNode.Kind.FILE);
            assertThat(cached.loaded).isTrue();
            assertThat(cached.getChildCount()).isZero();
            assertThat(explorer.tree().getModel().isLeaf(cached)).isTrue();
        });
    }

    @Test
    void sharePointExplorerLoadsMoreWithoutReadingAllServerPages() throws Exception {
        try (com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint site =
                     com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint.paginated(251, 100)) {
            Explorer explorer = sharePointExplorer(site);
            Explorer.Node library = expandSharePointLibrary(explorer);
            awaitChildren(library, 101);
            assertThat(site.continuationRequests()).isZero();
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 201);
            assertThat(site.continuationRequests()).isEqualTo(1);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 200));
            awaitChildren(library, 251);
            assertThat(site.continuationRequests()).isEqualTo(2);
            assertUniquePages(library, 251);
        }
    }

    @Test
    void sharePointExplorerRetriesTemporaryFailureAndDeduplicatesShiftedPages() throws Exception {
        try (com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint site =
                     com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint.paginated(251, 100)) {
            Explorer explorer = sharePointExplorer(site);
            Explorer.Node library = expandSharePointLibrary(explorer);
            awaitChildren(library, 101);
            Explorer.MoreItem before = moreItem(library);
            site.failFileRequestAfter(0);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 101);
            assertThat(moreItem(library)).isEqualTo(before);

            site.addFile("000-new.txt");
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 200);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 199));
            awaitChildren(library, 251);
            assertUniquePages(library, 251);
        }
    }

    @Test
    void sharePointExplorerOffersLocalReloadWhenInvalidCursorRecoveryFails() throws Exception {
        try (com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint site =
                     com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint.paginated(251, 250)) {
            Explorer explorer = sharePointExplorer(site);
            Explorer.Node library = expandSharePointLibrary(explorer);
            awaitChildren(library, 101);
            Explorer.Node retained = (Explorer.Node) library.getChildAt(1);
            site.removeFile("Datei-0000.txt");
            // Nach dem Entfernen kommt die erste Seite voll und ohne Fortsetzung: Die Quelle liest sie ohne $top
            // vollständig nach (zweite Anfrage), erkennt daran den veralteten Cursor; erst die Wiederaufnahme scheitert.
            site.failFileRequestAfter(2);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 101);
            assertThat(moreItem(library).cursor()).isNull();

            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 102);
            assertThat(moreItem(library).cursor()).isNotNull();
            assertThat(library.getChildAt(0)).isSameAs(retained);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 101));
            awaitChildren(library, 202);
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 201));
            awaitChildren(library, 250);
            assertUniquePages(library, 250);
        }
    }

    @Test
    void sharePointExplorerAutomaticallyRestartsInvalidCursorAndPreservesExistingNode() throws Exception {
        try (com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint site =
                     com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint.paginated(251, 250)) {
            Explorer explorer = sharePointExplorer(site);
            Explorer.Node library = expandSharePointLibrary(explorer);
            awaitChildren(library, 101);
            Explorer.Node retained = (Explorer.Node) library.getChildAt(1);
            SwingUtilities.invokeAndWait(() ->
                    explorer.tree().setSelectionPath(new javax.swing.tree.TreePath(retained.getPath())));
            site.removeFile("Datei-0000.txt");
            SwingUtilities.invokeAndWait(() -> clickMore(explorer, library, 100));
            awaitChildren(library, 102);
            SwingUtilities.invokeAndWait(() -> {
                assertThat(library.getChildAt(0)).isSameAs(retained);
                assertThat(explorer.tree().getSelectionPath().getLastPathComponent()).isSameAs(retained);
                assertThat(explorer.tree().isExpanded(new javax.swing.tree.TreePath(library.getPath()))).isTrue();
            });
            assertThat(moreItem(library).cursor()).isNotNull();
        }
    }

    private Explorer sharePointExplorer(
            com.aresstack.enterpriseai.proasteion.emporion.holkas.sharepoint.ParitySharePoint site) throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, "space-harvester-app:\n  data-directory: "
                + home.resolve("data").toString().replace('\\', '/') + "\n"
                + "space-harvester:\n  sources:\n    - id: sp\n      type: sharepoint\n"
                + "      title: SharePoint\n      options:\n        siteUrl: " + site.siteUrl() + "\n");
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> explorer[0] = new Explorer(context));
        return explorer[0];
    }

    private static Explorer.Node expandSharePointLibrary(Explorer explorer) throws Exception {
        Explorer.Node root = (Explorer.Node) explorer.tree().getModel().getRoot();
        awaitChildren(root, 1);
        Explorer.Node source = expandOnlyChild(explorer, root);
        awaitChildren(source, 1);
        Explorer.Node space = expandOnlyChild(explorer, source);
        awaitChildren(space, 1);
        return expandOnlyChild(explorer, space);
    }

    private static Explorer.Node expandOnlyChild(Explorer explorer, Explorer.Node parent) throws Exception {
        Explorer.Node[] child = new Explorer.Node[1];
        SwingUtilities.invokeAndWait(() -> {
            child[0] = (Explorer.Node) parent.getFirstChild();
            explorer.tree().setSize(500, 10000);
            explorer.tree().expandPath(new javax.swing.tree.TreePath(child[0].getPath()));
        });
        return child[0];
    }

    private static Explorer.MoreItem moreItem(Explorer.Node parent) throws Exception {
        Explorer.MoreItem[] item = new Explorer.MoreItem[1];
        SwingUtilities.invokeAndWait(() -> item[0] = (Explorer.MoreItem) ((Explorer.Node) parent.getLastChild()).item());
        return item[0];
    }

    private static void assertUniquePages(Explorer.Node parent, int count) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<String> ids = new java.util.ArrayList<>();
            for (int i = 0; i < parent.getChildCount(); i++) {
                Explorer.Node child = (Explorer.Node) parent.getChildAt(i);
                ids.add(((Explorer.PageItem) child.item()).pageId());
            }
            assertThat(ids).hasSize(count).doesNotHaveDuplicates();
        });
    }

    private static void clickMore(Explorer explorer, Explorer.Node parent, int count) {
        assertThat(parent.getChildCount()).isEqualTo(count + 1);
        Explorer.Node more = (Explorer.Node) parent.getLastChild();
        assertThat(more.item()).isInstanceOf(Explorer.MoreItem.class);
        javax.swing.JTree tree = explorer.tree();
        tree.setSize(500, 10000);
        javax.swing.tree.TreePath path = new javax.swing.tree.TreePath(more.getPath());
        tree.scrollPathToVisible(path);
        java.awt.Rectangle bounds = tree.getPathBounds(path);
        assertThat(bounds).isNotNull();
        explorer.treeMouse().mouseClicked(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                bounds.x + 5, bounds.y + bounds.height / 2, 1));
    }

    private static void awaitChildren(Explorer.Node parent, int count) throws Exception {
        boolean[] ready = new boolean[1];
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> ready[0] = !parent.loading && parent.getChildCount() == count
                    && !(parent.getFirstChild() instanceof Explorer.Node
                    && ((Explorer.Node) parent.getFirstChild()).item() instanceof Explorer.Note));
            if (ready[0]) return;
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            assertThat(parent.loading).isFalse();
            assertThat(parent.getChildCount()).isEqualTo(count);
            assertThat(ready[0]).isTrue();
        });
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

    /** Statt eines Titels ein Link zur Seite (Angelo, 27.11.2026): dieselbe Adressauflösung wie die Suchleiste. */
    @Test
    void theTitleFilterTakesALinkToAPage() throws Exception {
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
        String pageId = context.runtime().workspace().findByTitle("handbuch", java.util.Set.of(), "Wartungsfenster",
                10).pages().get(0).pageId();

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            // Beim Kopieren geratenes unsichtbares Zeichen vorn bleibt ohne Folgen.
            explorer[0].filter().setText("\u200Bspace-harvester://handbuch/page/" + pageId);
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
            assertThat(explorer[0].matches().getModel().getElementAt(0).pageId()).isEqualTo(pageId);
        });
    }

    /**
     * Ordnerquellen: der Baum kommt aus der Quelle, nicht aus dem Bestand. Der Bestand kann hinterherhinken oder
     * mitten in einer Befüllung nur einen Teil der Seiten haben; dann fehlten Seiten im Baum.
     */
    @Test
    void aFolderTreeShowsTheSourceEvenWhenTheStockLagsBehind() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("anderes.md"), "# Anderes\n");
        Files.writeString(files.resolve("wartungsfenster.md"), "# Wartungsfenster\n");
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
        Explorer.SpaceItem space = (Explorer.SpaceItem) Explorer.children(context.runtime(),
                new Explorer.SourceItem("handbuch", "Handbuch"), null).nodes().get(0).item();

        var workspace = context.runtime().workspace();
        var status = workspace.startSync("handbuch", space.spaceKey());
        for (int i = 0; i < 600 && !status.state().terminal(); i++) {
            Thread.sleep(50);
            status = workspace.job(status.id());
        }
        assertThat(status.state().terminal()).as("Abgleich fertig").isTrue();
        assertThat(workspace.tree("handbuch", space.spaceKey()).pages()).as("Bestand").hasSize(2);

        // Eine neue Datei ist noch nicht im Bestand, gehört aber in den Baum.
        Files.writeString(files.resolve("neu.md"), "# Neu\n");

        assertThat(Explorer.children(context.runtime(), space, null).nodes()).hasSize(3);
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

        // Resolve the real folder path without relying on the indexed cache.
        Explorer.Batch spaces = Explorer.children(context.runtime(), new Explorer.SourceItem("handbuch", "Handbuch"),
                null);
        Explorer.SpaceItem space = (Explorer.SpaceItem) spaces.nodes().get(0).item();
        List<Explorer.PageItem> pages = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .toList();
        Explorer.PageItem folder = pages.stream().filter(page -> page.title().equals("betrieb"))
                .findFirst().orElseThrow();
        assertThat(folder.kind()).isEqualTo(HarvestSource.PageNode.Kind.FOLDER);
        Explorer.PageItem target = Explorer.children(context.runtime(), folder, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .filter(page -> page.title().equals("wartungsfenster.md")).findFirst().orElseThrow();
        PageNavigation.Position position = new PageNavigation.Position("handbuch", space.spaceKey(), space.name(),
                List.of(new HarvestSource.PageNode(folder.pageId(), folder.title(), space.spaceKey(), true,
                        HarvestSource.PageNode.Kind.FOLDER)),
                new HarvestSource.PageNode(target.pageId(), target.title(), space.spaceKey(), false,
                        HarvestSource.PageNode.Kind.FILE));

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
        Explorer.PageItem other = pages.stream()
                .filter(page -> page.title().equals("anderes.md")).findFirst().orElseThrow();
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

        // Ein Klick öffnet die Seite (Angelo, 17.11.2026, einheitlich mit Quelle und Space); der Doppelklick klappt
        // nur auf und zu und öffnet nichts ein zweites Mal.
        List<String> opened = new java.util.ArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("openFromTree")) {
                        opened.add((String) args[1]);
                    }
                    return null;
                }));
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JTree tree = explorer[0].tree();
            tree.setSize(400, 400);
            javax.swing.tree.TreePath parent = tree.getSelectionPath().getParentPath();
            assertThat(tree.isExpanded(parent)).isTrue();
            java.awt.Rectangle row = tree.getPathBounds(tree.getSelectionPath());
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, row.x + 5, row.y + row.height / 2, 1, false,
                    java.awt.event.MouseEvent.BUTTON1));
            assertThat(opened).as("ein Klick öffnet").containsExactly(wanted);
            java.awt.Rectangle spaceRow = tree.getPathBounds(parent);
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, spaceRow.x + 5, spaceRow.y + spaceRow.height / 2, 2, false,
                    java.awt.event.MouseEvent.BUTTON1));
            assertThat(tree.isExpanded(parent)).as("Doppelklick klappt zu").isFalse();
            tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_CLICKED,
                    System.currentTimeMillis(), 0, spaceRow.x + 5, spaceRow.y + spaceRow.height / 2, 2, false,
                    java.awt.event.MouseEvent.BUTTON1));
            assertThat(tree.isExpanded(parent)).as("Doppelklick klappt auf").isTrue();

            // Angelo (20.11.2026): oft öffnete erst der zweite Klick. Wandert die Maus zwischen Drücken und Loslassen,
            // meldet Swing keinen Klick; losgelassen auf derselben Zeile öffnet die Seite trotzdem.
            java.awt.event.MouseAdapter mouse = explorer[0].treeMouse();
            int x = row.x + 5;
            int y = row.y + row.height / 2;
            mouse.mousePressed(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED, x, y, 1));
            // Nach der Bewegung meldet AWT beim Loslassen die Klickzahl 0 (Codex zu #975); es zählt die vom Drücken.
            mouse.mouseDragged(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_DRAGGED, x + 2, y + 1, 0));
            mouse.mouseReleased(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_RELEASED, x + 2, y + 1, 0));
            assertThat(opened).as("losgelassen nach kleiner Bewegung").containsExactly(wanted, wanted);
            // Ohne Bewegung kommt der Klick von Swing; das Loslassen öffnet nicht ein zweites Mal.
            mouse.mousePressed(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED, x, y, 1));
            mouse.mouseReleased(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_RELEASED, x, y, 1));
            assertThat(opened).as("ohne Bewegung nur über den Klick").hasSize(2);
            // Auf einer anderen Zeile losgelassen: nichts öffnen.
            mouse.mousePressed(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_PRESSED, x, y, 1));
            mouse.mouseDragged(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_DRAGGED, spaceRow.x + 5,
                    spaceRow.y + spaceRow.height / 2, 1));
            mouse.mouseReleased(mouseEvent(tree, java.awt.event.MouseEvent.MOUSE_RELEASED, spaceRow.x + 5,
                    spaceRow.y + spaceRow.height / 2, 1));
            assertThat(opened).as("auf anderer Zeile losgelassen").hasSize(2);
        });
        assertThat(opened).containsExactly(wanted, wanted);

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
        assertThat(opened).as("das Fadenkreuz öffnet nicht").containsExactly(wanted, wanted);

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
     * Fadenkreuz und Suchtreffer fanden eine Seite nie wieder, wenn ihr Space schon aufgeklappt, aber nicht fertig
     * geladen war (etwa nur aus dem Cache, weil die Quelle beim Aufklappen nicht antwortete): Das Aufklappen meldete
     * nichts mehr, der Abstieg wartete ewig. Erst ein Neuaufbau über den Titelfilter half (Angelo, 27.11.2026).
     */
    @Test
    void revealPageLoadsAnExpandedSpaceThatNeverFinishedLoading() throws Exception {
        Path files = Files.createDirectories(home.resolve("tickets"));
        Files.createDirectories(files.resolve("projekt"));
        Files.writeString(files.resolve("projekt").resolve("vorgang-7.md"), "# Vorgang 7\n\nText.\n");
        Files.writeString(files.resolve("erster.md"), "# Erster\n");
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                space-harvester:
                  sources:
                    - id: tickets
                      type: files
                      title: Tickets
                      options:
                        directory: %s
                """.formatted(home.resolve("data"), files));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        Explorer.SpaceItem space = (Explorer.SpaceItem) Explorer.children(context.runtime(),
                new Explorer.SourceItem("tickets", "Tickets"), null).nodes().get(0).item();
        List<Explorer.PageItem> pages = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .toList();
        Explorer.PageItem first = pages.stream().filter(page -> page.title().equals("erster.md")).findFirst()
                .orElseThrow();
        Explorer.PageItem folder = pages.stream().filter(page -> page.title().equals("projekt")).findFirst()
                .orElseThrow();
        Explorer.PageItem target = Explorer.children(context.runtime(), folder, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .filter(page -> page.title().equals("vorgang-7.md")).findFirst().orElseThrow();
        PageNavigation.Position firstPosition = new PageNavigation.Position("tickets", space.spaceKey(),
                space.name(), List.of(), new HarvestSource.PageNode(first.pageId(), first.title(), space.spaceKey(),
                false, HarvestSource.PageNode.Kind.FILE));
        PageNavigation.Position position = new PageNavigation.Position("tickets", space.spaceKey(), space.name(),
                List.of(new HarvestSource.PageNode(folder.pageId(), folder.title(), space.spaceKey(), true,
                        HarvestSource.PageNode.Kind.FOLDER)),
                new HarvestSource.PageNode(target.pageId(), target.title(), space.spaceKey(), false,
                        HarvestSource.PageNode.Kind.FILE));

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            explorer[0].revealPage(firstPosition);
        });
        assertThat(awaitSelected(explorer[0], first.pageId())).isEqualTo(first.pageId());

        // Der Space bleibt aufgeklappt, gilt aber als nicht fertig geladen, wie nach einer gescheiterten Abfrage
        // über einem Stand aus dem Cache.
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.tree.TreePath spacePath = explorer[0].tree().getSelectionPath().getParentPath();
            Explorer.Node spaceNode = (Explorer.Node) spacePath.getLastPathComponent();
            assertThat(explorer[0].tree().isExpanded(spacePath)).isTrue();
            spaceNode.loaded = false;
            spaceNode.fromCache = true;
            explorer[0].tree().clearSelection();
            explorer[0].revealPage(position);
        });

        assertThat(awaitSelected(explorer[0], target.pageId())).as("der Baum findet die Seite")
                .isEqualTo(target.pageId());
    }

    /** Wartet, bis {@code pageId} im Baum gewählt ist, und gibt die zuletzt gewählte Seiten-ID zurück. */
    private static String awaitSelected(Explorer explorer, String pageId) throws Exception {
        String[] selected = new String[1];
        for (int i = 0; i < 200; i++) {
            SwingUtilities.invokeAndWait(() -> {
                javax.swing.tree.TreePath path = explorer.tree().getSelectionPath();
                selected[0] = path != null && ((Explorer.Node) path.getLastPathComponent()).item()
                        instanceof Explorer.PageItem page ? page.pageId() : null;
            });
            if (pageId.equals(selected[0])) {
                break;
            }
            Thread.sleep(50);
        }
        return selected[0];
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
        // Beide Seiten aus einer Auflistung vor dem Explorer: später kann der Bestand erst teilweise gefüllt sein.
        List<Explorer.PageItem> pages = Explorer.children(context.runtime(), space, null).nodes().stream()
                .map(Explorer.Node::item).filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
                .toList();
        Explorer.PageItem target = pages.stream().filter(Explorer.PageItem.class::isInstance).map(Explorer.PageItem.class::cast)
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
            // Die lange Zeile: Label mit rechtem Rand für die zwei Spalten, Titel breiter als der Platz davor.
            javax.swing.tree.TreePath path = tree.getSelectionPath();
            javax.swing.JLabel label = (javax.swing.JLabel) tree.getCellRenderer().getTreeCellRendererComponent(tree,
                    path.getLastPathComponent(), true, false, true, tree.getRowForPath(path), false);
            java.awt.Rectangle bounds = tree.getPathBounds(path);
            assertThat(label.getInsets().right).isEqualTo(30 + 22);
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

            // Gemerkte Auswahl (Suche verlassen) kommt zurück: Knoten wie „Wissensquellen“ (Angelo, 21.11.2026).
            tree.setSelectionRow(0);
            Explorer.Remembered node = explorer[0].remember();
            explorer[0].selectAll();
            Explorer.Remembered all = explorer[0].remember();
            explorer[0].restore(node);
            assertThat(explorer[0].allSelected()).isFalse();
            assertThat(scopes.get(scopes.size() - 1)).containsExactly(new Explorer.Scope("handbuch", null));
            explorer[0].restore(all);
            assertThat(explorer[0].allSelected()).isTrue();
            assertThat(scopes.get(scopes.size() - 1)).isEmpty();
            // Unverändert wiederhergestellt: der Baum meldet nichts, die Suche startet nicht neu.
            int reported = scopes.size();
            explorer[0].restore(all);
            assertThat(scopes).hasSize(reported);
            explorer[0].clearSelection();
        });

        // Die Fußleiste ist dieselbe wie unter der Arbeitsfläche (Angelo 27.11.2026): links die Lupe, mittig „Alles
        // zuklappen“ und das Fadenkreuz, rechts das Bestandszeichen und in der Ecke die Hilfe. Die Lupe markiert
        // Titel im Baum, ohne zu filtern und ohne die Auswahl zu ändern.
        SwingUtilities.invokeAndWait(() -> {
            FindBar foot = explorer[0].footBar();
            javax.swing.JTree tree = explorer[0].tree();
            assertThat(SwingUtilities.isDescendingFrom(explorer[0].collapseAllButton(), foot.toolsArea())).isTrue();
            assertThat(SwingUtilities.isDescendingFrom(explorer[0].allBadge(), foot.cornerArea())).isTrue();
            assertThat(foot.helpArea().getComponent(0))
                    .isInstanceOf(com.aresstack.spaceharvester.app.ui.controls.HelpButton.class);
            assertThat(foot.foldToggle().isVisible()).as("im Baum wird nicht gefiltert").isFalse();
            int rows = tree.getRowCount();
            int before = scopes.size();

            foot.search("HANDB");
            assertThat(explorer[0].findHits()).extracting(String::valueOf).containsExactly("Handbuch");
            assertThat(foot.counterText()).isEqualTo("1 / 1");
            assertThat(tree.getRowCount()).as("nichts ausgeblendet").isEqualTo(rows);
            assertThat(tree.getSelectionCount()).as("Auswahl unverändert").isZero();
            assertThat(scopes).hasSize(before);

            foot.search("gibtesnicht");
            assertThat(foot.counterText()).isEqualTo("Keine Treffer");
            foot.close();
            assertThat(explorer[0].findHits()).isEmpty();
        });

        // „Alles indexieren“ im Kontextmenü der Überschrift indexiert jede Quelle mit allen Spaces (je Space ein
        // Auftrag, wie einzeln).
        List<String> indexed = new java.util.ArrayList<>();
        context.setNavigator((AppContext.Navigator) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {AppContext.Navigator.class}, (proxy, method, args) -> {
                    if (method.getName().equals("index")) {
                        indexed.add(args[0] + ":" + args[1] + ":" + args[2]);
                    }
                    return null;
                }));
        List<Boolean> answers = new java.util.ArrayList<>(List.of(false, true));
        SwingUtilities.invokeAndWait(() -> {
            javax.swing.JMenuItem all = (javax.swing.JMenuItem) explorer[0].headMenu().getComponent(0);
            assertThat(all.getText()).isEqualTo(Explorer.INDEX_ALL);
            // Erst die Rückfrage (Ja/Nein, „wirklich“): bei Nein passiert nichts, bei Ja indexiert jede Quelle.
            explorer[0].confirmIndexAll(parent -> answers.remove(0));
            all.doClick();
            assertThat(indexed).isEmpty();
            all.doClick();
        });
        assertThat(answers).isEmpty();
        assertThat(Explorer.INDEX_ALL_QUESTION).containsIgnoringCase("wirklich");
        assertThat(indexed).containsExactly("handbuch:null:null");
    }

    /** Die Hervorhebung des Sterns folgt der Maus über dem Stern (Angelo, 16.11.2026). */
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
            // Spalten von rechts: Stern (30), Bestandszeichen (22); das Download-Symbol ist weg (Angelo, 20.11.2026).
            int y = bounds.y + bounds.height / 2;
            move(tree, bounds.x + bounds.width - 15, y);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(row);
            assertThat(tree.getToolTipText()).isEqualTo(Explorer.STAR_TIP);
            move(tree, bounds.x + 5, y);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(-1);
            // Der Zustandspunkt links vom Stern: Kreispfeile unter der Maus, Klick holt die Seite samt Teilbaum nach.
            move(tree, bounds.x + bounds.width - 30 - 11, y);
            assertThat(explorer[0].hoverBadgeRow()).isEqualTo(row);
            assertThat(explorer[0].hoverStarRow()).isEqualTo(-1);
            assertThat(tree.getToolTipText()).endsWith(Explorer.REFRESH_TIP);
            click(tree, bounds.x + bounds.width - 30 - 11, y);
            assertThat(refreshed).containsExactly("handbuch/" + space.spaceKey() + "/" + page.pageId());
            // Die Wurzel der Quelle (Zeile 0) trägt dieselben Spalten: Zustandspunkt und Stern.
            java.awt.Rectangle top = tree.getRowBounds(0);
            assertThat(((Explorer.Node) tree.getPathForRow(0).getLastPathComponent()).item())
                    .isInstanceOf(Explorer.SourceItem.class);
            int ty = top.y + top.height / 2;
            move(tree, top.x + top.width - 30 - 11, ty);
            assertThat(explorer[0].hoverBadgeRow()).isZero();
            click(tree, top.x + top.width - 30 - 11, ty);
            assertThat(refreshed).hasSize(2);
            assertThat(refreshed.get(1)).isEqualTo("handbuch/null/null");
            // Der Stern an der Quelle merkt die ganze Quelle als Favorit (Angelo, 16.11.2026).
            move(tree, top.x + top.width - 15, ty);
            assertThat(explorer[0].hoverStarRow()).isZero();
            click(tree, top.x + top.width - 15, ty);
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

    /** Ein Ereignis der linken Maustaste, zum direkten Aufruf des Listeners (ohne Fenster, ohne Toolkit). */
    private static java.awt.event.MouseEvent mouseEvent(javax.swing.JTree tree, int id, int x, int y, int clicks) {
        return new java.awt.event.MouseEvent(tree, id, System.currentTimeMillis(),
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK, x, y, clicks, false, java.awt.event.MouseEvent.BUTTON1);
    }

    private static void move(javax.swing.JTree tree, int x, int y) {
        tree.dispatchEvent(new java.awt.event.MouseEvent(tree, java.awt.event.MouseEvent.MOUSE_MOVED,
                System.currentTimeMillis(), 0, x, y, 0, false));
    }
}
