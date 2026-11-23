package com.aresstack.spaceharvester.app.ui;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.settings.AppSettingsLoader;
import com.aresstack.spaceharvester.app.settings.Placeholders;
import com.aresstack.spaceharvester.app.ui.content.ChatSidebar;
import com.aresstack.spaceharvester.app.ui.deck.DeckArea;
import com.aresstack.spaceharvester.app.ui.deck.DeckBars;
import com.aresstack.spaceharvester.app.ui.perspective.Perspectives;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Die Leisten des Command Deck sind so hoch wie die des Space Harvesters: Die Linien laufen beim Wechsel durch. */
class DeckBarHeightsTest {

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
    void theBarsOfTheCommandDeckAreAsHighAsThoseOfTheMainWindow() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            assertThat(DeckBars.HEAD_HEIGHT).isEqualTo(ChatSidebar.HEAD_HEIGHT);
            assertThat(DeckBars.FOOT_HEIGHT).isEqualTo(new FindBar(() -> null).getPreferredSize().height);
        });
    }

    /** Die Statuszeile bleibt gleich hoch, auch wenn eine Perspektive Zurück und Vor ausblendet. */
    @Test
    void theStatusLineKeepsItsHeightInEveryPerspective() throws Exception {
        Path config = home.resolve("app.yml");
        Files.writeString(config, """
                space-harvester-app:
                  data-directory: %s
                """.formatted(home.resolve("data")));
        context = new AppContext(new AppSettingsLoader(new Placeholders(name -> null), home), config, "test");
        CountDownLatch started = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> context.reload(started::countDown));
        assertThat(started.await(30, TimeUnit.SECONDS)).isTrue();
        SwingUtilities.invokeAndWait(() -> {
            MainWindow window = new MainWindow(context);
            JPanel surface = window.content(() -> { }, () -> { });
            surface.setSize(1280, 820);
            window.show(AppContext.Section.CONTENT);
            int harvester = StatusLine.HEIGHT;
            // Auch im Space Harvester selbst: Bereiche ohne eigenen Stand lassen die Zeile nicht schrumpfen.
            for (AppContext.Section section : AppContext.Section.values()) {
                window.show(section);
                assertThat(window.statusLine().getPreferredSize().height).as(section.name()).isEqualTo(harvester);
            }
            window.perspectives().select(Perspectives.COMMAND_DECK);
            for (DeckArea area : DeckArea.values()) {
                window.commandDeck().show(area);
                assertThat(window.statusLine().getPreferredSize().height).as(area.name()).isEqualTo(harvester);
            }
            String shots = System.getenv("SCREENSHOTS_DIR");
            if (shots != null) {
                window.commandDeck().show(DeckArea.FILES);
                Screenshots.write(surface, Path.of(shots, "deck-dateien.png"));
                window.commandDeck().show(DeckArea.BETAVIEW);
                Screenshots.write(surface, Path.of(shots, "deck-betaview.png"));
            }
        });
    }
}
