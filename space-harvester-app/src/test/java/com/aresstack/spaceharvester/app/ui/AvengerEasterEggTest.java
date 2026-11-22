package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.controls.ShipArchive;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import java.awt.Component;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JComponent;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Avenger-Easter-Egg (#1141): fünf Klicks aufs Logo, Archiv über der Ansicht, Esc schließt, nichts ändert sich. */
class AvengerEasterEggTest {

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
    void fiveClicksOnTheLogoOpenTheArchiveWithoutChangingTheView() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();

        MainWindow[] window = new MainWindow[1];
        JRootPane[] root = new JRootPane[1];
        SwingUtilities.invokeAndWait(() -> {
            window[0] = new MainWindow(context);
            JPanel surface = window[0].content(() -> { }, () -> { });
            root[0] = new JRootPane();
            root[0].setContentPane(surface);
            root[0].setSize(1280, 820);
            root[0].doLayout();
            root[0].getLayeredPane().setSize(1280, 820);
            window[0].perspectives().select(Perspectives.COMMAND_DECK);
            JComponent logo = window[0].perspectiveSwitcher().logo();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 5; i++) {
                logo.dispatchEvent(new MouseEvent(logo, MouseEvent.MOUSE_PRESSED, now + i * 200L, 0, 3, 3, 1, false,
                        MouseEvent.BUTTON1));
            }
            assertThat(window[0].perspectiveSwitcher().heritage()).isEqualTo("AVENGER");
            // Ein zweiter Auslöser während des Ablaufs plant kein zweites Archiv.
            window[0].avenger().reveal();
        });
        // Nach dem kurzen Flackern öffnet sich das Archiv.
        ShipArchive archive = null;
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (archive == null && System.nanoTime() < until) {
            Thread.sleep(50);
            ShipArchive[] found = new ShipArchive[1];
            SwingUtilities.invokeAndWait(() -> found[0] = archive(root[0]));
            archive = found[0];
        }
        assertThat(archive).as("Bordarchiv offen").isNotNull();
        ShipArchive open = archive;
        SwingUtilities.invokeAndWait(() -> {
            open.finish();
            assertThat(open.shownText()).contains("Ursprüngliche Schiffskennung: AVENGER")
                    .contains("Neue Kennung: SPACE HARVESTER").contains("Proving Ground erprobt Technologien");
            assertThat(window[0].perspectives().isCurrent(Perspectives.COMMAND_DECK)).isTrue();
            // Ein zweiter Auslöser verdoppelt nichts.
            assertThat(window[0].avenger().openArchive()).isSameAs(open);
            // Esc schließt; Perspektive und Sitzungen bleiben.
            var action = open.getActionMap().get(open.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)));
            action.actionPerformed(null);
            assertThat(open.isOpen()).isFalse();
            assertThat(archive(root[0])).isNull();
        });
        // Auch nach der Wartezeit des Ablaufs öffnet sich nichts von selbst neu.
        Thread.sleep(3000);
        SwingUtilities.invokeAndWait(() -> {
            assertThat(archive(root[0])).isNull();
            assertThat(window[0].perspectives().isCurrent(Perspectives.COMMAND_DECK)).isTrue();
            assertThat(window[0].commandDeck().sessions().all()).isEmpty();
        });
    }

    private static ShipArchive archive(JRootPane root) {
        for (Component child : root.getLayeredPane().getComponentsInLayer(JLayeredPane.MODAL_LAYER)) {
            if (child instanceof ShipArchive archive) {
                return archive;
            }
        }
        return null;
    }
}
