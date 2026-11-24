package com.aresstack.spaceharvester.app.ui.content;

import com.aresstack.spaceharvester.app.ui.AppState;
import com.aresstack.spaceharvester.app.ui.ParallelChoice;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PodcastParallelTest {

    @AfterEach
    void reset() {
        AppState.shared().put(PodcastParallel.TEXTS_KEY, PodcastParallel.TEXTS_DEFAULT);
        AppState.shared().put(PodcastParallel.PICTURES_KEY, PodcastParallel.PICTURES_DEFAULT);
    }

    @Test
    void theTriangleOffersTextsAndPicturesWithTheStepsOfTheChatAndPicturesStartAtEight() throws Exception {
        // Angelo 24.11.2026: Bilder in eigenem Pool, Standard 8; Texte eigene Grenze; im Dreieck-Menü wie im Chat.
        assertThat(PodcastParallel.pictures()).isEqualTo(8);
        assertThat(PodcastParallel.texts()).isEqualTo(4);
        List<List<String>> menus = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            for (JMenu menu : PodcastParallel.menus(null, text -> { })) {
                List<String> labels = new ArrayList<>(List.of(menu.getText()));
                for (int i = 0; i < menu.getItemCount(); i++) {
                    if (menu.getItem(i) != null) {
                        labels.add(menu.getItem(i).getText());
                    }
                }
                menus.add(labels);
            }
        });
        assertThat(menus).extracting(labels -> labels.get(0))
                .containsExactly(PodcastParallel.TEXTS + ": 4", PodcastParallel.PICTURES + ": 8");
        assertThat(menus).allSatisfy(labels -> assertThat(labels.subList(1, 8))
                .containsExactly("1", "2", "4", "8", "16", "32", "64"))
                .allSatisfy(labels -> assertThat(labels.get(8)).startsWith(ParallelChoice.CUSTOM));
    }

    @Test
    void aChoiceInTheMenuIsWhatTheNextPodcastReads() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JMenu pictures = PodcastParallel.menus(null, text -> { }).get(1);
            pictures.getItem(5).doClick(); // „32“
        });
        assertThat(PodcastParallel.pictures()).isEqualTo(32);
        assertThat(PodcastParallel.texts()).isEqualTo(4);
    }
}
