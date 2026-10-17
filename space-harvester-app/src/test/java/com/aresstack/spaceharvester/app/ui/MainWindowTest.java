package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.AbstractButton;
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
        for (AppContext.Section section : AppContext.Section.values()) {
            Path png = home.resolve(section.name() + ".png");
            SwingUtilities.invokeAndWait(() -> {
                window[0].show(section);
                Screenshots.write(surface[0], png);
            });
            assertThat(png).isNotEmptyFile();
        }
        assertThat(buttonTexts(surface[0])).contains("Übersicht", "Wissensquellen", "Inhalte", "Aktivitäten",
                "Exporte", "Suche", "Einstellungen");

        String space = context.runtime().workspace().spaces("handbuch").get(0).key();
        String job = context.runtime().workspace().startSync("handbuch", space).id();
        for (int i = 0; i < 600 && !context.runtime().workspace().job(job).state().terminal(); i++) {
            Thread.sleep(50);
        }
        String pageId = context.runtime().workspace().cachedPages("handbuch", space, null, 10).pages().get(0)
                .header().key().pageId();
        SwingUtilities.invokeAndWait(() -> {
            window[0].showJob(job);
            window[0].openSpace("handbuch", space);
            window[0].openPage("handbuch", pageId);
            window[0].search("betrieb");
            window[0].prepareExport("handbuch", space);
        });
        Thread.sleep(500);
        SwingUtilities.invokeAndWait(() -> Screenshots.write(surface[0], home.resolve("export.png")));
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
