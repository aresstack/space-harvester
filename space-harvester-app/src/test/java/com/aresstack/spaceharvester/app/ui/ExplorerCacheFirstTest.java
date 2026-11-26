package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.runtime.Workspace;
import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.SwingUtilities;
import javax.swing.tree.TreePath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Angelo, 17.11.2026: Aufklappen zeigt zuerst den Stand aus dem Cache und gleicht danach mit der Quelle ab. */
class ExplorerCacheFirstTest {

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
    void childrenComeFromTheCacheFirstAndTheLiveAnswerIsMergedIn() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("wartungsfenster.md"), "# Wartungsfenster\n\nText.\n");
        Files.writeString(files.resolve("anderes.md"), "# Anderes\n\nText.\n");
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
        assertThat(Explorer.cachedChildren(context.runtime(), space, null).nodes()).as("vor dem Abgleich leer").isEmpty();

        Workspace workspace = context.runtime().workspace();
        JobStatus sync = await(workspace, workspace.startSync("handbuch", space.spaceKey()));
        assertThat(sync.state().terminal()).isTrue();
        List<String> cached = Explorer.cachedChildren(context.runtime(), space, null).nodes().stream()
                .map(node -> ((Explorer.PageItem) node.item()).title()).toList();
        assertThat(cached).as("nach Titel geordnet, ohne die Quelle zu fragen").hasSize(2);
        assertThat(cached).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        // Im Baum: Quelle und Space aufklappen; am Ende stehen die Seiten der Quelle, abgeglichen statt neu aufgebaut.
        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> explorer[0] = new Explorer(context));
        Explorer.Node[] spaceNode = new Explorer.Node[1];
        for (int i = 0; i < 200 && spaceNode[0] == null; i++) {
            SwingUtilities.invokeAndWait(() -> {
                Explorer.Node top = (Explorer.Node) explorer[0].tree().getModel().getRoot();
                Explorer.Node source = (Explorer.Node) top.getChildAt(0);
                explorer[0].tree().expandPath(new TreePath(source.getPath()));
                if (source.loaded && source.getChildAt(0) instanceof Explorer.Node child
                        && child.item() instanceof Explorer.SpaceItem) {
                    spaceNode[0] = child;
                    explorer[0].tree().expandPath(new TreePath(child.getPath()));
                }
            });
            Thread.sleep(50);
        }
        assertThat(spaceNode[0]).isNotNull();
        boolean[] done = new boolean[1];
        for (int i = 0; i < 200 && !done[0]; i++) {
            SwingUtilities.invokeAndWait(() -> done[0] = spaceNode[0].loaded && !spaceNode[0].loading);
            Thread.sleep(50);
        }
        SwingUtilities.invokeAndWait(() -> {
            assertThat(spaceNode[0].fromCache).isFalse();
            assertThat(spaceNode[0].getChildCount()).isEqualTo(2);
            for (int i = 0; i < spaceNode[0].getChildCount(); i++) {
                assertThat(((Explorer.Node) spaceNode[0].getChildAt(i)).item()).isInstanceOf(Explorer.PageItem.class);
            }
        });
    }

    /**
     * AT zu #861: Die Quelle antwortet in Stapeln und in anderer Reihenfolge als der Cache. Unbestätigte Kinder bleiben,
     * bis der letzte Stapel kommt; Auswahl und Aufklappung bleiben, die Mitte sieht keinen Auswahlwechsel.
     */
    @Test
    void theMergeKeepsUnconfirmedChildrenUntilTheLastBatchAndKeepsSelectionAndExpansion() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("seite.md"), "# Seite\n");
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

        SwingUtilities.invokeAndWait(() -> {
            Explorer explorer = new Explorer(context);
            int[] events = new int[1];
            explorer.onSelection(selection -> events[0]++);
            javax.swing.tree.DefaultTreeModel model = (javax.swing.tree.DefaultTreeModel) explorer.tree().getModel();
            Explorer.Node top = (Explorer.Node) model.getRoot();
            Explorer.Node space = new Explorer.Node(new Explorer.SpaceItem("handbuch", "X", "X"));
            space.loaded = true;
            space.fromCache = true;
            // Aus dem Cache, nach Titel: A, B, C (aufgeklappt, mit Kind), D.
            Explorer.Node a = page("a", "A", false);
            Explorer.Node b = page("b", "B", false);
            Explorer.Node c = page("c", "C", true);
            c.loaded = true;
            c.add(page("c1", "C1", false));
            Explorer.Node d = page("d", "D", false);
            for (Explorer.Node child : List.of(a, b, c, d)) {
                space.add(child);
            }
            model.insertNodeInto(space, top, top.getChildCount());
            explorer.tree().expandPath(new TreePath(space.getPath()));
            explorer.tree().expandPath(new TreePath(c.getPath()));
            explorer.tree().setSelectionPath(new TreePath(b.getPath()));
            events[0] = 0;

            // Erster Stapel der Quelle: C, A, Cursor offen.
            explorer.merge(space, new Explorer.Batch(List.of(page("c", "C", true), page("a", "A", false)), "weiter"),
                    true);
            assertThat(titles(space)).containsExactly("C", "A", "B", "D", "more");
            assertThat(explorer.tree().getSelectionPath().getLastPathComponent()).isSameAs(b);
            assertThat(explorer.tree().isExpanded(new TreePath(c.getPath()))).isTrue();
            assertThat(events[0]).as("die Mitte sieht keinen Auswahlwechsel").isZero();

            // Letzter Stapel: B; D kennt die Quelle nicht mehr.
            explorer.merge(space, new Explorer.Batch(List.of(page("b", "B", false)), null), false);
            assertThat(titles(space)).containsExactly("C", "A", "B");
            assertThat(explorer.tree().getSelectionPath().getLastPathComponent()).isSameAs(b);
            assertThat(explorer.tree().isExpanded(new TreePath(c.getPath()))).isTrue();
            assertThat(events[0]).isZero();
        });
    }

    private static Explorer.Node page(String id, String title, boolean children) {
        Explorer.Node node = new Explorer.Node(Explorer.PageItem.of("handbuch", "X", id, title, children));
        if (children) {
            node.add(new Explorer.Node(new Explorer.Note(Explorer.LOADING)));
        } else {
            node.loaded = true;
        }
        return node;
    }

    private static List<String> titles(Explorer.Node parent) {
        List<String> titles = new java.util.ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            Explorer.Item item = ((Explorer.Node) parent.getChildAt(i)).item();
            titles.add(item instanceof Explorer.PageItem page ? page.title()
                    : item instanceof Explorer.MoreItem ? "more" : String.valueOf(item));
        }
        return titles;
    }

    private static JobStatus await(Workspace workspace, JobStatus started) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(30));
        JobStatus status = started;
        while (!status.state().terminal() && Instant.now().isBefore(deadline)) {
            Thread.sleep(50);
            status = workspace.job(started.id());
        }
        return status;
    }
}
