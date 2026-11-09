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
    }
}
