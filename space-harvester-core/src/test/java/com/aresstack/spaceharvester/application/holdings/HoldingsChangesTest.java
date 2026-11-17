package com.aresstack.spaceharvester.application.holdings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Ein weiterer Empfänger hinter dem Bestand (ADR 0027), etwa die Hintergrundfüllung: jede Änderung, nie der Beginn. */
class HoldingsChangesTest {

    @Test
    void afterEveryChangeButNotAtTheStartOfLoading() {
        List<String> seen = new ArrayList<>();
        HoldingsChanges first = new HoldingsChanges() {
            @Override
            public void pagesChanged(String sourceId, String spaceKey, Collection<String> pageIds) {
                seen.add("pages");
            }

            @Override
            public void spaceChanged(String sourceId, String spaceKey) {
                seen.add("space");
            }

            @Override
            public void sourceChanged(String sourceId) {
                seen.add("source");
            }

            @Override
            public void loading(String sourceId, String spaceKey, Collection<String> pageIds) {
                seen.add("loading");
            }
        };
        HoldingsChanges both = first.andThen(() -> seen.add("danach"));

        both.loading("wiki", "DEV", List.of("1"));
        both.pagesChanged("wiki", "DEV", List.of("1"));
        both.spaceChanged("wiki", "DEV");
        both.sourceChanged("wiki");

        assertThat(seen).containsExactly("loading", "pages", "danach", "space", "danach", "source", "danach");
    }
}
