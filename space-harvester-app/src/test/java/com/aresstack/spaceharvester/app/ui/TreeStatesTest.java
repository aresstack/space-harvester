package com.aresstack.spaceharvester.app.ui;

import com.aresstack.spaceharvester.application.holdings.HoldingCounts;
import com.aresstack.spaceharvester.application.holdings.HoldingState;
import com.aresstack.spaceharvester.application.holdings.IndexCoverage;
import com.aresstack.spaceharvester.application.holdings.IndexState;
import com.aresstack.spaceharvester.application.holdings.PageHolding;
import com.aresstack.spaceharvester.application.holdings.SpaceSummary;
import com.aresstack.spaceharvester.application.query.CacheGeneration;
import com.aresstack.spaceharvester.application.query.CacheState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Die Bestandszeichen des Explorers wie bei OneDrive: je Seite ihr Zustand, je Knoten der Teilbaum, mit Zahlen im Tooltip. */
class TreeStatesTest {

    @Test
    void localIndexBadgesDoNotTreatCachedMetadataAsSearchableContent() {
        assertThat(TreeStates.indexLeaf(IndexState.INDEXED, false).glyph()).isEqualTo(Glyph.CHECK_CIRCLE);
        for (IndexState state : IndexState.values()) {
            if (state != IndexState.INDEXED) {
                assertThat(TreeStates.indexLeaf(state, false).glyph()).isNotEqualTo(Glyph.CHECK_CIRCLE);
            }
        }
        assertThat(TreeStates.indexLeaf(IndexState.INDEXED, true).kind()).isEqualTo(TreeStates.Kind.LOADING);
        var coverage = IndexCoverage.of(IndexState.INDEXED).plus(IndexCoverage.of(IndexState.FOLDER))
                .plus(IndexCoverage.of(IndexState.NO_TEXT));
        assertThat(TreeStates.indexNode(coverage, false).kind()).isEqualTo(TreeStates.Kind.PARTIAL);
        assertThat(TreeStates.indexNode(coverage, false).tooltip())
                .contains("1 von 2 Dateien", "1 ohne durchsuchbaren Text");
        var complete = IndexCoverage.of(IndexState.INDEXED).plus(IndexCoverage.of(IndexState.FOLDER));
        assertThat(TreeStates.indexNode(complete, false).kind()).isEqualTo(TreeStates.Kind.FULL);
    }

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
        assertThat(partial.tooltip()).isEqualTo("184 von 2010 Seiten lokal, 8 veraltet (neuere Version in der Quelle, Inhalt nicht geladen), 2 gesperrt");
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

    /**
     * Der Gesamtstand der Kopfzeile (Angelo, 16.11.2026): grün nur, wenn jede Quelle und jeder bekannte Space
     * inventarisiert und vollständig ist; ein voller Space neben nicht geladenen macht den Stand nicht grün.
     */
    @Test
    void theOverallStateIsFullOnlyWhenEverySpaceOfEverySourceIsComplete() {
        CacheGeneration synced = new CacheGeneration("g1", CacheState.values()[0], Instant.EPOCH, Instant.EPOCH);
        SpaceSummary full = new SpaceSummary("a", "ACC", synced, new HoldingCounts(12, 12, 0, 0));
        SpaceSummary none = new SpaceSummary("a", "ADM", null, HoldingCounts.ZERO);
        SpaceSummary half = new SpaceSummary("b", "DEV", synced, new HoldingCounts(10, 4, 0, 0));
        Map<String, Set<String>> noLoading = Map.of();
        // Ein voller Space, ein Space ohne Inventur: halb, nicht grün.
        TreeStates.Badge mixed = TreeStates.all(List.of("a"), Map.of(TreeStates.key("a", "ACC"), full,
                TreeStates.key("a", "ADM"), none), noLoading, Map.of());
        assertThat(mixed.kind()).isEqualTo(TreeStates.Kind.PARTIAL);
        assertThat(mixed.tooltip()).isEqualTo("12 von 12 Seiten lokal · " + TreeStates.TIP_NOT_ALL);
        // Ein voller Space, aber der Baum kennt einen weiteren ohne Stand: halb.
        assertThat(TreeStates.all(List.of("a"), Map.of(TreeStates.key("a", "ACC"), full), noLoading,
                Map.of("a", Set.of("ACC", "XYZ"))).kind()).isEqualTo(TreeStates.Kind.PARTIAL);
        // Eine zweite Quelle ganz ohne Stand: halb.
        assertThat(TreeStates.all(List.of("a", "b"), Map.of(TreeStates.key("a", "ACC"), full), noLoading,
                Map.of()).kind()).isEqualTo(TreeStates.Kind.PARTIAL);
        // Alles inventarisiert und vollständig: grün.
        assertThat(TreeStates.all(List.of("a"), Map.of(TreeStates.key("a", "ACC"), full), noLoading,
                Map.of("a", Set.of("ACC"))).kind()).isEqualTo(TreeStates.Kind.FULL);
        // Zwei Quellen, eine halb: Summe halb.
        TreeStates.Badge two = TreeStates.all(List.of("a", "b"), Map.of(TreeStates.key("a", "ACC"), full,
                TreeStates.key("b", "DEV"), half), noLoading, Map.of());
        assertThat(two.kind()).isEqualTo(TreeStates.Kind.PARTIAL);
        assertThat(two.tooltip()).isEqualTo("16 von 22 Seiten lokal");
        // Ohne jeden Stand: leer; mit laufendem Download: Kreispfeile.
        assertThat(TreeStates.all(List.of("a"), Map.of(), noLoading, Map.of()).kind())
                .isEqualTo(TreeStates.Kind.EMPTY);
        assertThat(TreeStates.all(List.of("a"), Map.of(TreeStates.key("a", "ACC"), full),
                Map.of(TreeStates.key("a", "ADM"), Set.of("p1")), Map.of()).kind()).isEqualTo(TreeStates.Kind.LOADING);
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
