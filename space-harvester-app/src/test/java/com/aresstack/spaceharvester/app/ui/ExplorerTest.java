package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
