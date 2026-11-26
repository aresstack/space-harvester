package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.application.jobs.JobStatus;
import com.aresstack.spaceharvester.application.navigation.PageNavigation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Die Bestandszeichen im Explorer (Angelo, 13.11.2026): nach dem Aufklappen eines Space zeigt jede Seite ihren
 * Zustand, ein Download ändert ihn über die Meldung des Core, ohne dass der Baum nachfragt.
 */
class ExplorerStatesTest {

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
    void pagesShowTheirHoldingStateAndFollowADownloadWithoutPolling() throws Exception {
        Path files = Files.createDirectories(home.resolve("handbuch"));
        Files.writeString(files.resolve("wartung.md"), "# Wartung\n\nText.\n");
        Files.writeString(files.resolve("betrieb.md"), "# Betrieb\n\nText.\n");
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
                .filter(item -> item.title().toLowerCase(java.util.Locale.ROOT).contains("wartung"))
                .findFirst().orElseThrow();

        Explorer[] explorer = new Explorer[1];
        SwingUtilities.invokeAndWait(() -> {
            explorer[0] = new Explorer(context);
            // Aufklappen wie der Benutzer: Quelle, dann Space; das liest die Übersicht des Space einmal.
            explorer[0].revealPage(new PageNavigation.Position("handbuch", space.spaceKey(), space.name(), List.of(),
                    new com.aresstack.spaceharvester.application.sources.HarvestSource.PageNode(page.pageId(),
                            page.title(), space.spaceKey(), false)));
        });
        await(() -> explorer[0].states().knows("handbuch", space.spaceKey()));
        // Noch nichts geladen: die Seite ist nur in der Quelle oder dem Bestand unbekannt, der Space leer oder ohne Inventur.
        TreeStates.Badge before = onEdt(() -> explorer[0].badge(page));
        assertThat(before.kind()).isIn(TreeStates.Kind.NONE, TreeStates.Kind.NOT_INDEXED);
        assertThat(onEdt(() -> explorer[0].badge(space)).kind()).isIn(TreeStates.Kind.EMPTY, TreeStates.Kind.NONE);

        JobStatus job = context.runtime().workspace().startExport("handbuch", space.spaceKey(), List.of("json"));
        await(() -> {
            JobStatus status = context.runtime().workspace().job(job.id());
            return status != null && status.state().terminal();
        });
        // Die Meldung des Core trifft ein, der Baum liest nur die genannten Seiten nach: Haken und voller Kreis.
        await(() -> onEdtQuietly(() -> explorer[0].badge(page)).kind() == TreeStates.Kind.LOCAL);
        assertThat(onEdt(() -> explorer[0].badge(page)).glyph()).isEqualTo(Glyph.CHECK_CIRCLE);
        assertThat(onEdt(() -> explorer[0].badge(page)).tooltip()).isEqualTo("Volltext indexiert und aktuell");
        await(() -> onEdtQuietly(() -> explorer[0].badge(space)).kind() == TreeStates.Kind.FULL);
        assertThat(onEdt(() -> explorer[0].badge(space)).tooltip()).isEqualTo("2 von 2 Dateien vollständig und aktuell indexiert");
        // Das Zeichen der Kopfzeile fasst alle Quellen zusammen (Angelo, 16.11.2026), ebenfalls ohne Abfrage.
        await(() -> onEdtQuietly(() -> explorer[0].states().all(List.of("handbuch"), java.util.Map.of())).kind() == TreeStates.Kind.FULL);
        assertThat(onEdt(() -> explorer[0].allBadge().getIcon())).isEqualTo(Glyph.CIRCLE_FULL);
        assertThat(onEdt(() -> explorer[0].allBadge().getToolTipText())).isEqualTo(Explorer.refreshTip("2 von 2 Dateien vollständig und aktuell indexiert"));
        // Auch die Wurzel der Quelle trägt den Zustandspunkt (Angelo, 16.11.2026): über alle ihre Spaces.
        Explorer.SourceItem source = new Explorer.SourceItem("handbuch", "Handbuch");
        assertThat(onEdt(() -> explorer[0].badge(source)).kind()).isEqualTo(TreeStates.Kind.FULL);
        assertThat(onEdt(() -> explorer[0].badge(source)).tooltip()).isEqualTo("2 von 2 Dateien vollständig und aktuell indexiert");

        // Nach einem neuen Stand des Space (Core-Meldung) liest der aufgeklappte Knoten seine Kinder nach: die neue
        // Seite erscheint, Aufklappung und Auswahl bleiben (Codex zu #802).
        javax.swing.tree.TreePath selected = onEdt(() -> explorer[0].tree().getSelectionPath());
        assertThat(selected).isNotNull();
        Files.writeString(files.resolve("neu.md"), "# Neu\n\nText.\n");
        JobStatus sync = context.runtime().workspace().startSync("handbuch", space.spaceKey());
        await(() -> {
            JobStatus status = context.runtime().workspace().job(sync.id());
            return status != null && status.state().terminal();
        });
        await(() -> onEdtQuietly(() -> titles(explorer[0], space)).stream()
                .anyMatch(title -> title.toLowerCase(java.util.Locale.ROOT).contains("neu")));
        assertThat(onEdt(() -> titles(explorer[0], space))).hasSize(3);
        assertThat(onEdt(() -> explorer[0].tree().getSelectionPath())).isEqualTo(selected);
        assertThat(onEdt(() -> explorer[0].tree().isExpanded(selected.getParentPath()))).isTrue();
    }

    /** Die Titel der Kinder des Space-Knotens im Baum. */
    private static List<String> titles(Explorer explorer, Explorer.SpaceItem space) {
        javax.swing.tree.TreeModel model = explorer.tree().getModel();
        Object root = model.getRoot();
        for (int i = 0; i < model.getChildCount(root); i++) {
            Explorer.Node source = (Explorer.Node) model.getChild(root, i);
            for (int j = 0; j < source.getChildCount(); j++) {
                Explorer.Node node = (Explorer.Node) source.getChildAt(j);
                if (node.item() instanceof Explorer.SpaceItem item && item.spaceKey().equals(space.spaceKey())) {
                    List<String> titles = new java.util.ArrayList<>();
                    for (int k = 0; k < node.getChildCount(); k++) {
                        if (((Explorer.Node) node.getChildAt(k)).item() instanceof Explorer.PageItem page) {
                            titles.add(page.title());
                        }
                    }
                    return titles;
                }
            }
        }
        return List.of();
    }

    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + 120_000_000_000L;
        while (!condition.getAsBoolean()) {
            assertThat(System.nanoTime()).as("Zeitlimit").isLessThan(deadline);
            Thread.sleep(50);
        }
    }

    private static <T> T onEdtQuietly(java.util.concurrent.Callable<T> work) {
        try {
            return onEdt(work);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdt(java.util.concurrent.Callable<T> work) throws Exception {
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<Exception> failure = new java.util.concurrent.atomic.AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(work.call());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return result.get();
    }
}
