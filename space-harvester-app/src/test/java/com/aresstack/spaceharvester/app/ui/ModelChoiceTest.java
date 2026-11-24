package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.ai.AiModel;
import com.aresstack.spaceharvester.app.settings.AiDraft;
import org.junit.jupiter.api.Test;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ModelChoiceTest {

    private static final List<AiModel> MODELS = List.of(
            new AiModel("text-gross", null, Set.of(AiModel.CHAT)),
            new AiModel("stimme-eins", null, Set.of(AiModel.TTS)),
            new AiModel("ohne-art", null, Set.of()));

    @Test
    void standardIsCheckedWhileTheSettingIsEmptyAndTheModelsOfTheServiceFollow() throws Exception {
        ModelChoice.Catalog catalog = loaded();
        List<String> labels = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            JMenu menu = ModelChoice.menu(null, "Modell Podcast", "Tipp", AiModel.CHAT, null, catalog, model -> { });
            labels.add(menu.getText());
            for (int i = 0; i < menu.getItemCount(); i++) {
                JMenuItem item = menu.getItem(i);
                if (item != null) {
                    labels.add(item.getText() + (item.isSelected() ? " ✓" : ""));
                }
            }
        });
        assertThat(labels).containsExactly("Modell Podcast: Standard", "Standard ✓", "text-gross", "ohne-art",
                ParallelChoice.CUSTOM);
    }

    @Test
    void aModelNotOfferedShowsAsCustomAndStandardGoesBackToEmpty() throws Exception {
        ModelChoice.Catalog catalog = loaded();
        List<String> chosen = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        SwingUtilities.invokeAndWait(() -> {
            JMenu menu = ModelChoice.menu(null, "Modell Podcast – Sprache", "Tipp", AiModel.TTS, "eigene-stimme",
                    catalog, chosen::add);
            labels.add(menu.getText());
            labels.add(menu.getItem(menu.getItemCount() - 1).getText());
            menu.getItem(0).doClick();
            menu.getItem(2).doClick();
        });
        assertThat(labels).containsExactly("Modell Podcast – Sprache: eigene-stimme",
                ParallelChoice.CUSTOM + " (eigene-stimme)");
        assertThat(chosen).containsExactly(null, "stimme-eins");
    }

    @Test
    void eachRowWritesOnlyItsOwnSettingAndKeepsTokensAndThinking() {
        AiDraft draft = AiDraft.EMPTY.withUses(AiDraft.Use.EMPTY, AiDraft.Use.EMPTY,
                new AiDraft.Use(null, "9000", "low"));
        for (PodcastModels.Row row : PodcastModels.ROWS) {
            AiDraft changed = row.write().apply(draft, "modell-x");
            assertThat(row.read().apply(changed)).as(row.label()).isEqualTo("modell-x");
            assertThat(row.read().apply(row.write().apply(changed, null))).as(row.label()).isNull();
            for (PodcastModels.Row other : PodcastModels.ROWS) {
                if (other != row) {
                    assertThat(other.read().apply(changed)).as(row.label() + " → " + other.label()).isNull();
                }
            }
        }
        AiDraft podcast = PodcastModels.ROWS.get(0).write().apply(draft, "modell-x");
        assertThat(podcast.podcast()).isEqualTo(new AiDraft.Use("modell-x", "9000", "low"));
    }

    private static ModelChoice.Catalog loaded() throws Exception {
        ModelChoice.Catalog catalog = new ModelChoice.Catalog(() -> MODELS);
        CountDownLatch done = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> catalog.load(done::countDown));
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        return catalog;
    }
}
