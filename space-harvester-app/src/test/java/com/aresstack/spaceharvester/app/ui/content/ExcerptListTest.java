package com.aresstack.spaceharvester.app.ui.content;

import static org.assertj.core.api.Assertions.assertThat;

import com.aresstack.spaceharvester.app.ui.Pager;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** Die Textausschnitte aller Fundstellen eines Worts unter der Wortsuche (Angelo 28.11.2026). */
class ExcerptListTest {

    private static final ExcerptList.Runner NOW = new ExcerptList.Runner() {
        @Override
        public <T> void run(Callable<T> work, Consumer<T> onSuccess, Consumer<Throwable> onFailure) {
            try {
                onSuccess.accept(work.call());
            } catch (Exception e) {
                onFailure.accept(e);
            }
        }
    };

    private static WordSearch.Excerpt excerpt(int page) {
        return new WordSearch.Excerpt(new WordSearch.Place("quelle", "RAUM", "seite-" + page, "Erfundene Seite "
                + page, 1), "Die Buchung läuft über das ", "Sammelkonto", " und wird am Monatsende abgeglichen.",
                true, page % 2 == 0);
    }

    private static Pager<WordSearch.Excerpt> pager(int total) {
        return new Pager<>((one, other) -> 0, List.of(List.of(cursor -> {
            int from = cursor == null ? 0 : Integer.parseInt(cursor);
            int to = Math.min(total, from + WordSearch.step());
            List<WordSearch.Excerpt> items = new ArrayList<>();
            for (int i = from; i < to; i++) {
                items.add(excerpt(i + 1));
            }
            return new Pager.Page<>(items, to < total ? String.valueOf(to) : null);
        })));
    }

    @Test
    void everyOccurrenceGetsItsOwnCardWithTheWordMarked() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            List<WordSearch.Place> opened = new ArrayList<>();
            ExcerptList list = new ExcerptList(opened::add);
            list.runner = NOW;
            list.show("Sammelkonto", pager(3));

            assertThat(list.cards()).hasSize(3);
            assertThat(list.headingText()).isEqualTo("„Sammelkonto“: 3 Fundstellen");
            assertThat(list.more().isVisible()).isFalse();
            ExcerptCard first = list.cards().get(0);
            assertThat(first.titleText()).isEqualTo("Erfundene Seite 1");
            assertThat(first.marked()).as("das Wort ist hervorgehoben").isEqualTo("Sammelkonto");
            assertThat(first.text()).startsWith(ExcerptCard.ELLIPSIS).doesNotEndWith(ExcerptCard.ELLIPSIS_AFTER);
            assertThat(list.cards().get(1).text()).endsWith(ExcerptCard.ELLIPSIS_AFTER);

            for (java.awt.event.MouseListener listener : first.title().getMouseListeners()) {
                listener.mouseClicked(new java.awt.event.MouseEvent(first.title(),
                        java.awt.event.MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false));
            }
            assertThat(opened).extracting(WordSearch.Place::pageId).containsExactly("seite-1");
        });
    }

    @Test
    void moreOccurrencesLoadOnDemandAndANewWordStartsOver() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            ExcerptList list = new ExcerptList(place -> { });
            list.runner = NOW;
            int total = WordSearch.step() + 2;
            list.show("Sammelkonto", pager(total));
            assertThat(list.cards()).hasSize(WordSearch.step());
            assertThat(list.more().isVisible()).isTrue();
            assertThat(list.headingText()).endsWith(", weitere folgen");

            list.more().doClick();
            assertThat(list.cards()).hasSize(total);
            assertThat(list.more().isVisible()).isFalse();

            list.show("Konto", pager(0));
            assertThat(list.cards()).isEmpty();
            assertThat(list.headingText()).isEqualTo(ExcerptList.NONE);

            list.show("Konto", pager(1));
            list.clear();
            assertThat(list.cards()).isEmpty();
        });
    }
}
