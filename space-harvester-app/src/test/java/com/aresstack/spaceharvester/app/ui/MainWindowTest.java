package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
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
        assertThat(buttonTexts(surface[0])).containsSubsequence("Suche", "Wissensquellen", "Inhalte",
                "Exportieren", "Aufträge", "Status", "Einstellungen");
        SwingUtilities.invokeAndWait(() -> window[0].show(AppContext.Section.STATUS));
        assertThat(labelTexts(surface[0])).as("„Zu erledigen“ steht im Dashboard, nicht in der Begrüßung")
                .contains("Nichts offen.");

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
