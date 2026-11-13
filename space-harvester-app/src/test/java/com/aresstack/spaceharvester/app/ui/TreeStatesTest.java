package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Bestandszeichen des Explorers wie bei OneDrive: je Seite ihr Zustand, je Knoten der Teilbaum, mit Zahlen im Tooltip. */
class TreeStatesTest {

    @Test
    void aLeafShowsItsOwnStateAndLoadingWinsOverEverything() {
        assertThat(TreeStates.leaf(HoldingState.LOCAL, false).glyph()).isEqualTo(Glyph.CHECK_CIRCLE);
        assertThat(TreeStates.leaf(HoldingState.LOCAL, false).tooltip()).isEqualTo(TreeStates.TIP_LOCAL);
        assertThat(TreeStates.leaf(HoldingState.REMOTE, false).glyph()).isEqualTo(Glyph.CLOUD);
        assertThat(TreeStates.leaf(HoldingState.OUTDATED, false).glyph()).isEqualTo(Glyph.WARNING);
        assertThat(TreeStates.leaf(HoldingState.LOCKED, false).glyph()).isEqualTo(Glyph.LOCK);
        assertThat(TreeStates.leaf(HoldingState.REMOTE, true).glyph()).isEqualTo(Glyph.SYNC);
        assertThat(TreeStates.leaf(HoldingState.LOCAL, true).kind()).isEqualTo(TreeStates.Kind.LOADING);
    }

    @Test
    void aNodeIsFullHalfOrEmptyAndNamesTheNumbers() {
        assertThat(TreeStates.node(null, false).glyph()).isEqualTo(Glyph.CIRCLE_EMPTY);
        assertThat(TreeStates.node(null, false).tooltip()).isEqualTo(TreeStates.TIP_UNKNOWN);
        TreeStates.Badge empty = TreeStates.node(new HoldingCounts(2010, 0, 0, 0), false);
        assertThat(empty.glyph()).isEqualTo(Glyph.CIRCLE_EMPTY);
        assertThat(empty.tooltip()).isEqualTo("0 von 2010 Seiten lokal");
        TreeStates.Badge partial = TreeStates.node(new HoldingCounts(2010, 176, 8, 2), false);
        assertThat(partial.glyph()).isEqualTo(Glyph.CIRCLE_HALF);
        assertThat(partial.tooltip()).isEqualTo("184 von 2010 Seiten lokal, 8 veraltet, 2 gesperrt");
        TreeStates.Badge full = TreeStates.node(new HoldingCounts(12, 12, 0, 0), false);
        assertThat(full.glyph()).isEqualTo(Glyph.CIRCLE_FULL);
        assertThat(full.kind()).isEqualTo(TreeStates.Kind.FULL);
        // Alles da, aber etwas veraltet: halb in Orange, damit man es sieht.
        TreeStates.Badge stale = TreeStates.node(new HoldingCounts(12, 10, 2, 0), false);
        assertThat(stale.glyph()).isEqualTo(Glyph.CIRCLE_HALF);
        assertThat(stale.color()).isEqualTo(Comic.ORANGE);
        // Gesperrte zählen nicht als fehlend.
        assertThat(TreeStates.node(new HoldingCounts(12, 10, 0, 2), false).kind()).isEqualTo(TreeStates.Kind.FULL);
        TreeStates.Badge loading = TreeStates.node(new HoldingCounts(12, 3, 0, 0), true);
        assertThat(loading.glyph()).isEqualTo(Glyph.SYNC);
        assertThat(loading.tooltip()).isEqualTo("3 von 12 Seiten lokal · wird geladen");
    }

    @Test
    void loadingPagesMarkTheirAncestors() {
        Map<String, PageHolding> pages = Map.of(
                "root", new PageHolding("root", null, HoldingState.LOCAL, new HoldingCounts(4, 2, 0, 0)),
                "a", new PageHolding("a", "root", HoldingState.LOCAL, new HoldingCounts(2, 1, 0, 0)),
                "a1", new PageHolding("a1", "a", HoldingState.REMOTE, new HoldingCounts(1, 0, 0, 0)),
                "b", new PageHolding("b", "root", HoldingState.REMOTE, new HoldingCounts(1, 0, 0, 0)));
        assertThat(TreeStates.ancestors(pages, Set.of("a1"))).containsExactlyInAnyOrder("a", "root");
        assertThat(TreeStates.ancestors(pages, Set.of("b"))).containsExactly("root");
        assertThat(TreeStates.ancestors(pages, Set.of("unbekannt"))).isEmpty();
    }
}
